补充一条 **PR #1 的后续修复**，都是实机复现后定位的。PR #1 已把两个新提交推到同一分支（`9739ae5` → `7962f11`），下面说明它们分别解决什么。

---

## 一、`wakeUp()` 漏了 `lastNanos`（PR #1 的疏漏）

PR #1 处理了 `smoothedIntervalMs`，但同一个问题还有另一半：**`lastNanos`**。

`tick()` 开头：

```java
long now = System.nanoTime();
double dt = Math.min((now - lastNanos) / 1_000_000_000.0, MAX_DT);
lastNanos = now;
```

空闲期间 `lastNanos` 停在最后一次 tick。从空闲档唤醒后，**第一帧的 `dt` 直接拉满 `MAX_DT` = 1/30 ≈ 33.3ms**（正常约 8ms，大 4 倍）。

`dt` 会原样喂给弹簧，`DampedSpring.update()` 里 `c = exp(-(4/animationLength)·dt)`。
取 `animationLength = 0.05s`、`o = 80`：

| `dt` | `c` | 一帧后剩余 | 效果 |
|---|---|---|---|
| 0.008（正常） | 0.527 | 47% | 跨多帧平滑展开 |
| 0.033（空闲后） | 0.070 | **7%** | **一帧衰减 93%，等于瞬移** |

**现象**：停几秒后第一次「删除到上一行、下方文本并入原行」时卡一下 —— 位置突变本应由弹簧平滑展开，却被超大 `dt` 在一帧内走完。

**关键佐证**：快速连续删除反而流畅。因为那时 `lastNanos` 每 8ms 都在更新，`dt` 始终正常。这个「越连续越顺」的反差只有 `dt` 这条链能解释。

注意 `skipNextIntervalMeasurement` **只丢弃了统计测量**，并不能阻止 `dt` 被算成整个空闲期，所以必须单独重置 `lastNanos`。

修法只有一行，放在已有的 `if (fromIdleTier)` 块里：

```java
if (fromIdleTier) {
    smoothedIntervalMs = ACTIVE_DELAY_MS;
    skipNextIntervalMeasurement = true;
    lastNanos = System.nanoTime();      // ← 新增
}
```

影响面仅「从空闲档唤醒」的第一帧；活跃档 `fromIdleTier = false`，打字与连续删除完全不受影响。

---

## 二、`maxTrailDistanceFactor = 60` 与 README 踩坑 #6 不一致

README 踩坑 #6 写的是：

> 收紧到 **4**（约 128px）后，正常移动完全不受影响。

但 `ee378a2` / `9739ae5` / 当前 `main` 三个版本的代码里都是 **`60f`**。
`NeovideConfig` 自己的注释也写着「曾用过 4」—— 说明这个值落地过又被改回去了，实现与文档长期不一致。

**为什么 60 有问题**：限幅公式是
`maxDistance = max(cursorWidth, lineHeight) × factor`。
行高约 32px 时，60 → **1920px**，比多数编辑器的可视高度还大。
四边形可被拉长到横跨整个屏幕 —— 既盖住大量字符（删除换行时最明显），
又把重绘包围盒撑到接近全屏（正是踩坑 #7 描述的退化场景）。

**为什么 4 不影响日常观感**：钳位对象是 `spring.position`（像素偏移），
量级即本次移动的位移。相邻列约 8px、上下移一行或回车约一个行高（32px 上下），
**都远小于 128px，碰不到阈值**。只有删除换行、跨十行跳转才会触发。

**损失**：大跨度跳转时拖尾被截断在约 4 行高，比原版短。
`clampSpring` 只改 `position`、不动 `velocity`，不会引入抖动或弹跳。

---

## 关于 0.5.0

我这边实测 0.5.0 观感与 0.4.x 差异较大（`hideNativeCaret` 默认改为 `false` 后，拖尾只在移动时叠加、停下即淡出，与原本常驻绘制的形态不同），这一点理解是对齐 VS Code 版的取舍，不作为问题报告。

但需要说明：**上述两条修复都不在 0.5.0 里**。
0.5.0 的 `wakeUp()` 仍然只重置 `currentDelayMs` 与 `nextDueNanos`，全文件搜 `skipNextIntervalMeasurement` → 0 处；
`maxTrailDistanceFactor` 仍是 `60f`。

考虑到 0.5.0 对 `CaretAnimator` 与调度做了较大重构（新增 `RenderScheduler` / `SpringTrail`），这两个修复若要移植，需要按新结构重新核对 `lastNanos` / `smoothedIntervalMs` 在单线程调度下的语义 —— 我暂时没动，想先确认方向：

1. `lastNanos` 的修复要不要在 0.5.0 上重做一版？
2. `RenderScheduler` 把所有 animator 合进一个 EDT 任务（`runDue()` 里循环 `runTick()`），
   且 `FRAME_PENDING.set(false)` 位于该循环之前 —— 单次 EDT 占用变长，
   且长循环期间标志已复位、调度线程可再投递一批。这两点是否有意为之？

如果需要，我可以按你指定的口径（标签页数量、操作序列、看哪个指标）出一组对照数据。
