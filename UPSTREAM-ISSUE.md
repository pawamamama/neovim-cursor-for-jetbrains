# 上游 Issue 草稿（可直接粘贴到 GitHub）

**提交地址**：https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/new

---

## 标题

```
结合一个用户两天的实测：CJK 短跳误判、hideNativeCaret 死字段、effectiveDt 空转链路
```

---

## 正文

> 先说明背景：我是这个插件的使用者，花了整整两天、几十次构建，
> 还请了三个 AI 审计员做交叉验证。作者说「我已经燃尽了」，
> 所以我把所有实测数据整理在这里，希望能帮他少走弯路。

### 环境

- IntelliJ IDEA 2026.1.3（`IU-261.25134.95`）
- JBR 25.0.3
- 字号 20px（中文语境，日常大量输入中文）
- 上游基线：`6bed09d`（v0.5.0）

---

## 问题一：短跳判据对 CJK 有系统性误判（**最重要**）

### 现象

- **英文输入/删除：非常丝滑**
- **中文输入/删除：明显卡顿，像是「跟不上」**

### 根因

`TrailCorner.jump()` 的短跳判据：

```java
double jumpX = (destX - previousDestX) / width;   // width = cursorWidth = 2px
double jumpY = (destY - previousDestY) / height;
double len = Math.hypot(jumpX, jumpY);
boolean isShortMove = len <= config.shortMoveThreshold;   // 8
```

`jumpX = dx / 2px`，结果取决于「一行能装几个字符」：

| 操作 | dx | jumpX | len | 判定 |
|---|---|---|---|---|
| 英文 1 字符 | 10px | 5.0 | 5.0 | ✅ 短跳 |
| **中文 1 个字** | **20px** | **10.0** | **10.0** | ❌ **跨行分支** |
| 英文 3 字符 | 30px | 15.0 | 15.0 | ❌ 跨行分支 |

### 为什么「卡」

跨行分支下 `trailSize = 1` 使 `leading = 0`：

```java
double leading = animationLength * (1 - trailSize);   // = 0
if (rank >= 2) animationLength = leading;             // = 0
```

而 `DampedSpring.update()` 开头有短路：

```java
if (animationLength <= dt) { position = 0; velocity = 0; return false; }
```

**→ rank2/rank3 两个前缘角第一帧就贴死，完全没有动画。**

视觉上就是四边形两条边在飞、另两条边瞬间到位 —— 表现为「卡」。

### 建议修复

用编辑器的原生字符偏移（IntelliJ 标准做法）：

```java
// CaretAnimator.refreshTarget()
int offset = editor.getCaretModel().getOffset();
int charsMoved = Math.abs(offset - lastCaretOffset);
lastCaretOffset = offset;
for (TrailCorner corner : corners) corner.setCharsMoved(charsMoved);
```

```java
// TrailCorner.jump()
boolean isShortMove;
if (charsMoved >= 0) {
    boolean sameLine = Math.abs(deltaYpx) <= height * 0.5;
    isShortMove = sameLine && charsMoved <= 2;
} else {
    isShortMove = len <= config.shortMoveThreshold;   // 兜底
}
```

**`offset` 差值就是精确的字符个数**，中英文都是 1，
与字体、字号、全角半角、连字、emoji（代理对按 2 计）全部无关。

> 我试过三种替代方案，都不行，**别再走这些弯路**：
> - 硬编码 16px → 字号 20px 时中文 20px，直接超出
> - `FontMetrics.charWidth('m') × 2.5` → `'m'` 是西文推进量，中文是 2 倍
> - `visualPositionToXY(line,1) - visualPositionToXY(line,0)` → **取的是行首字符**，光标在中文区时仍翻倍

---

## 问题二：`hideNativeCaret` 是死字段

### 现状

`NeovideConfig.hideNativeCaret` 声明为 `true`，但**全仓库 0 处读取**：

```java
// NeovideCaretManager.java:179（install 内）
applyNativeCaretHidden();      // ← 无条件调用

// NeovideCaretManager.java:292（setEnabled 内）
applyNativeCaretHidden();      // ← 同样无条件
```

**设成 `false` 也照样隐藏原生光标——行为与配置相反。**

### 为什么重要

**隐藏原生光标会改写全局配色方案的 `CARET_COLOR`，这是有落盘副作用的操作**（踩坑 #18 的根源）。

而且踩坑 #19 自己说得很清楚：

> 原生光标隐藏后，肉眼看到的「光标」就是覆盖层，于是「光标何时出现在新位置」
> 完全由调度器决定：默认档空闲降频到 120ms（失焦 600ms）→ 停手后第一次删除
> 要等一个空闲周期。

**这正是「开常驻刷新就好、不开就卡」的结构性原因。**

### 建议

```java
if (CONFIG.hideNativeCaret) {
    applyNativeCaretHidden();
} else {
    restoreNativeCaret();
}
```

并加一个 Tools 菜单开关让用户随时切换。

---

## 问题三：`effectiveDt` 是空转链路

### 现状

```java
// CaretAnimator.tick()
smoothedDt += (dt - smoothedDt) * 0.25;
double effectiveDt = Math.max(dt, smoothedDt);     // :568

// tickSpring(dt, effectiveDt)
if (jumped) {
    assignRanks(effectiveDt);                      // :855 传进去
    jumped = false;
}

// assignRanks(double dt)
private void assignRanks(double dt) {
    // :1027-1049 方法体内【一次都没用到 dt】
    ...
    corners[i].jump(cursorWidth, cursorHeight, centerX, centerY, ranks[i], dt);
    //                                                              ↑ 又传出去了
}

// TrailCorner.jump(..., double frameDt)
// 但 v0.5.0 已经不再使用 frameDt
```

**弹簧推进用的是真实 `dt`（`CaretAnimator:862`），不是 `effectiveDt`。**

**所以「低帧率补偿」这套机制当前没有任何行为效果**——既不能作为卡顿原因，
也不能作为修复手段。它是前几轮反复救火留下的**空壳**。

**建议：删掉，或者真的接上。**

---

## 问题四：`MIN_ANIMATION_FRAMES` 的去留（我持保留意见）

踩坑 #20 说「低帧率补偿把拖尾的拉伸抹平了」——**这条对**，
因为它把四角时长抬成同一值（0.015~0.05s 全变成约 0.06s）。

**但在帧率不稳定时（`dt` 常达 20~30ms），它也是「动画还能被看见」的唯一保障。**

我的实测：在 12ms 帧率下，保留它 + `trailFactors` 四档，两者可以共存：

```
rank0 = 110ms（13.8 帧）  后缘慢拖
rank1 =  79ms（ 9.9 帧）
rank2 =  46ms（ 5.8 帧）
rank3 =  24ms（ 3.0 帧）  前缘紧跟
```

**建议：如果帧率能稳在 8ms，去掉；否则保留。**

---

## 附：性能实测数据（可能对你有用）

### 插件本身不慢

| 指标 | 实测值 |
|---|---|
| 12 层辉光绘制 | **0.05ms/帧**（占 8ms 预算的 **0.6%**）|
| 完整 Swing paint | **0.09ms/帧**（占 1.1%）|
| 插件占 EDT 采样 | **3.26%**（45 分钟 JFR，9641 采样）|
| `drawQuadGlow` 占 EDT | **2.40%** |

### 真正的问题：EDT 抢占

插件自己的日志（`delay=8ms` 说明调度器正常）：

```
delay=8ms   maxGap=358ms   peakRepaintArea=5494px
delay=8ms   maxGap=399ms   peakRepaintArea=4958px
delay=8ms   maxGap=121ms   peakRepaintArea=4422px
```

- `delay=8ms` → 每 8ms 都正确投递了任务
- **`maxGap=120~400ms` → 任务在 EDT 队列里排了几百毫秒**
- `peakRepaintArea` 仅 4000~5000px → **重绘面积小，不是绘制慢**

### 已排除的方向

| 假设 | 数据 | 结论 |
|---|---|---|
| 绘制太慢 | 0.05ms/帧 | ❌ |
| 重绘面积大 | 面积 157290px→141fps；面积 2345px→8.9fps | ❌ **面积大反而快 15 倍** |
| GC | 最长 15ms，卡顿是 900ms~31s | ❌ |
| GPU | JBR 25 无 D3D/OpenGL 管线；光标仅 64 像素 | ❌ |
| **IDEA 自身绘制** | `ScaledBlit.Scale` 占 EDT **12.6%** | ⚠️ **主因** |

### 最大的优化机会：全局 RenderScheduler

**你已经在 v0.5.0 实现了**（`RenderScheduler.java`，111 行）。

**JFR 证据**：`CaretAnimator.startScheduler` 占插件采样的 **90%**，全是空转 ——
因为每个编辑器（含每个标签页、每个控制台）各开一条 `Thread.sleep` 线程。

**收益**：EDT 投递次数从「N 个编辑器 × 125 次/秒」降到「125 次/秒」。

**建议**：把这个当作下一个重点。标签页多的用户会明显受益。

---

## 最后

这款插件的核心算法（`DampedSpring` / `TrailCorner`）设计得很漂亮。
问题不在算法，在于 **Swing 覆盖层 + EDT 调度**这个平台限制。

我这一路踩的坑、8 次改错的记录、37 个版本的参数对照，
都整理在 fork 的 `HANDOFF-FINAL.md` 和 `UPSTREAM-PITFALLS.md` 里，供参考：

- https://github.com/pawamamama/neovim-cursor-for-jetbrains/blob/feat/settings-ui-ready/HANDOFF-FINAL.md
- https://github.com/pawamamama/neovim-cursor-for-jetbrains/blob/feat/settings-ui-ready/UPSTREAM-PITFALLS.md

**愿你能把它做得更好。这个用户为它花了整整两天。**
