### 环境

- IntelliJ IDEA 2026.1 / Windows 11 / 主题 Material Oceanic
- 对比：`0.4.4 + #1`（本地自编译）vs Release **`0.5.0`**

### 结论先说

主观手感上，**`0.4.4 + #1` 在我这台机器上比 `0.5.0` 更顺**，多开标签页时尤其明显。
另外发现 **#1 的修复并没有进入 `0.5.0`**，所以 #1 当初解决的那个问题在 0.5.0 上依然存在。

下面刻意分成「已核实的代码事实」和「未量化的主观报告」两类，请按可信度分别看待。

---

## 一、【已核实】#1 的修复不在 0.5.0 里

`0.5.0` 的 `CaretAnimator.wakeUp()`（第 260-264 行）：

```java
private void wakeUp() {
    currentDelayMs = ACTIVE_DELAY_MS;
    nextDueNanos = System.nanoTime();
    RenderScheduler.wakeUp();
}
```

只重置了 `currentDelayMs` 与 `nextDueNanos`，**没有重置 `smoothedIntervalMs`**。
全文件搜索 `skipNextIntervalMeasurement` → **0 处**。

而 `smoothedIntervalMs` 依然只在 `tick()` 内更新（第 298-302 行），
`computeNextDelay()` 依然拿它算间隔（第 356 行）：

```java
int adaptive = (int) Math.round(smoothedIntervalMs * 0.85);
return Math.max(ACTIVE_DELAY_MS, Math.min(adaptive, MAX_ADAPTIVE_DELAY_MS));
```

空闲档（`IDLE_DELAY_MS` = 120 / `IDLE_UNFOCUSED_DELAY_MS` = 600）会把它喂大，
切回后 `600 * 0.85 = 510` 被 `MAX_ADAPTIVE_DELAY_MS`(40) 截断 ——
于是开头一段时间按 25fps 跑，要几十帧才收敛回 8ms。

**想确认**：0.5.0 重构调度时，是有意放弃了 #1 的做法，还是漏掉了？
如果是后者，考虑到 `wakeUp()` 现在还会调 `RenderScheduler.wakeUp()`，
补回去时判据是否仍应沿用 #1 的「看刚才所处档位」（`currentDelayMs > MAX_ADAPTIVE_DELAY_MS`），
而不是看 `smoothedIntervalMs` —— #1 的第二个提交正是为了修掉「每次击键都重置」的副作用。

---

## 二、【读代码的推断，无量化数据】单线程调度把所有 animator 挤进一个 EDT 任务

`RenderScheduler` 的设计是「所有编辑器共用一条调度线程」，并在**一个** EDT 任务里
tick 所有到期的 animator：

```java
// RenderScheduler.java:101-110
private static void runDue() {
    FRAME_PENDING.set(false);
    long threshold = System.nanoTime() + 1_000_000L;
    for (CaretAnimator a : ANIMATORS) {
        if (a.nextDueNanos <= threshold) {
            a.runTick();
        }
    }
}
```

两点疑问：

1. **合并成一个 EDT 任务会延长单次 EDT 占用时间。**
   AWT `EventQueue` 是顺序处理的，原生键鼠事件必须等当前任务跑完。
   拆成 N 个小任务时，输入事件还能插空处理；合成一个长任务反而让输入等待更久，
   **编辑器/标签页越多越明显**。这与「减轻 EDT 队列压力」的初衷方向相反。
2. **`FRAME_PENDING.set(false)` 位于 `runDue()` 第一行**（第 102 行），
   意味着在可能很长的 tick 循环执行期间该标志已是 `false`，
   调度线程可以立刻再投递一批 —— `framePending` 的防堆积作用被削弱了。

作为对照，`0.4.4` 是每个 `CaretAnimator` 各持一条线程、各有独立的 `framePending`，
单个编辑器丢帧不影响其他编辑器。

**这两点我没有实测数据，纯属读代码的推断。** 如果设计上已有考虑，
希望能说明一下；如果确实值得测，我按你指定的口径去采集。

---

## 三、【主观报告，未量化】

1. **拖尾像「没跟上」**：`hideNativeCaret` 默认 `false` 之后，原生光标瞬时到位，
   覆盖层四边形滞后 `animationLength`（短距离 0.05s / 常规 0.1s）。
   两者同时可见，观感是「一个光标本体 + 一条落后 50~100ms 的尾巴」。
   这应该是有意对齐 VS Code 版（默认也不隐藏原生光标），
   只是想确认：这个错位量是预期效果，还是可以再收一点？
2. **多开时更卡** —— 与第二节方向一致，但没有 fps 数据支撑。

补充：勾上「隐藏原生光标（常驻绘制）」后，观感确实回到 0.4.4 那种形态，
但按文档说明跟随会依赖 EDT 调度，实测也仍然偏卡，所以无法作为替代方案。

---

## 四、我希望得到的

不必立刻动代码。只想知道两件事：

1. #1 是被有意省略，还是漏掉了？
2. 第二节那两点，值不值得我用「性能诊断（禁用重绘）」+ fps 日志出一组对比数据？
   如果需要，请给一个你认为有说服力的口径（标签页数量、操作序列、看哪个指标）。

按你的要求补数据我随时可以做。
