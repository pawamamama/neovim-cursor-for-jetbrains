# 决策报告：2026-11-03 这一天的发现（给决策会话）

> 报告对象：另一个 Claude 会话（决策会话）。
> 目的：把 2026-11-03 从早到晚的排查、修复、失败与取舍完整交出去，供决策。
> 仓库 `D:\deepseekwork\neovim-cursor-pr`，分支 `fix/bug-016-completion-flicker`，
> 当前提交 `aeff707`（**未 push**），已装 jar 已由用户回退到旧版。
> 【2026-11-04 实测】HEAD `fddd529`、已装 **0.9.131**；本页其余版本描述见 §7 更正。

---

## 0. 一句话总结

今天查了两件**互不相关**的事，都被「同一个现象」搅在一起过：

1. **代码补全弹窗打开时拖尾闪烁 / 短跳变短**（BUG-016 原始报障）——
   **没有修好**。唯一的尝试（目标变化帧限速）被实机否决并整体回退。
2. **删除时「文字先消失、光标随后才跟上」**（后来才明确是另一件事）——
   根因确认是**关闭「常驻刷新」后的空闲/自适应档**，与预设、与 BUG-016 代码都无关。

此外今天修了两个审查确认的真缺陷，其中一个（跨行前缘下限）**又造成了新的手感回归**，
需要决策。

---

## 1. 时间线与关键结论

### 1.1 阶段 A：取证（0.9.127，诊断日志开启）

采集 `<log>/neovide-cursor-diag-1792.log`，主编辑器 animator 3162 帧：

| 指标 | 数值 |
|---|---|
| 普通帧 `rawDt` | P50 **6.99ms** / P90 8.48 / P95 12.07 / P99 25.27 / max 72.15 |
| `rawDt > 20ms` 的帧 | 68 / 3162（2.2%），全部聚集在击键前后 |
| 每次击键后第一帧 | 中位数 ≈20ms，常见 30~45ms，最大 72.2ms（被 `MAX_DT=50ms` 截断） |
| 同帧 `dispatchLag` | 最高 **65.4ms** |
| 同帧我们自己的 `cost` | 8~250µs（少数诊断帧 57ms） |

**结论**：按键后的帧延迟来自 EDT 队列等待，不是插件计算/绘制。

### 1.2 阶段 B：尝试 1「目标变化帧限速」（**已否决并回退**）

思路：`tick()` 先算 `dt`、再设新目标、再用同一 `dt` 推进弹簧；
目标变化帧把「没渲染过的时间」喂给了新动画。修法是把该帧步长钳到
`max(8ms, smoothedInterval)`。

实机结果（0.9.128）：**更差**。用户原话：「未解决，手感更不对了，还不如之前的，
你方向错了，还是会跳变」「连续推进动画也有问题，ffff… 这样的」。

否决证据（日志 id=90150598，连打）：

```
16:09:09.444  rawDt=13.5 dt=13.5 step=12.4 jumped=1 off=25.10
16:09:09.925  rawDt=12.7 dt=12.7 step= 8.0 jumped=1 off=47.50
16:09:10.413  rawDt= 7.0 dt= 7.0 step= 8.0 jumped=1 off=48.40
```

连打时几乎每帧都是「起手帧」，限速把动画时钟压到真实时间的 1/3，
拖尾偏移从正常的约 15px（一个字符）累积到 **47~51px（三个多字符）**。

**处置**：`git checkout` 六个文件 + 删除 VLookupFlicker 探针；补丁存档在
`build/bug016-attempt1.patch`、`build/bug016-attempt1-VLookupFlicker.java`。
**教训**：`dt` 不是这个 bug 的可修点。

### 1.3 安装脚本「只还原 7 个字段」的误判与更正

- 曾以为 `install.ps1` 只备份固定 7 个字段 → **错误**。
  `Read-PersonalOptions` 读的是 XML 里所有 `<option>`；当时只有 7 个，
  是因为 IntelliJ 序列化省略了等于默认值的字段。
- 真正的 bug：备份发生在**关闭 IDEA 之前**，而 IDEA 退出时才把内存状态写回 XML
  → 退出前刚改过、还没落盘的值会被旧快照覆盖。
- 已修（提交 `aeff707`）：备份挪到 IDEA 关闭之后（步骤 2b），重新读一遍 XML。
  `-DryRun` 验证当前 9 个字段全部纳入备份。
- **安装仍有一个坑**：脚本只还原「XML 里实际存在的字段」。装完 0.9.130 后又丢过
  `stretchRatioK=8.0 / animationLength=0.28 / animPresetBase=1`，已手动补回。
  下次安装后必须核对这三项 + `alwaysHighFps`。

### 1.4 删除延迟：根因确认（**方向曾被记反**）

用户实机开关 Tools →「Neovide 光标：常驻刷新」：

```
常驻刷新 开 → 删除/打字 丝滑
常驻刷新 关 → 文字先消失、光标随后才跟上
```

**机制（代码事实）**：
1. `alwaysHighFps=false` 时，空闲档 120ms / 失焦 600ms。
2. 击键后 `wakeUp()` 置 `nextDueNanos=now` 并打断调度线程，但恢复后的间隔走
   自适应 `smoothedIntervalMs × 0.85`，上限 40ms；空闲帧把平滑值喂大时，
   打字期间只有 20~40ms/帧 → 100ms 的短跳只剩 3~5 帧。
3. 空闲 >100ms 后的第一帧进 `recovery`，dt 被强制成 8ms，动画时钟慢于墙钟。

**与预设无关**：同一条行内退格只读 `shortAnimationLength`，基础 vs 缎带差 ≤8ms；
`animationLength` 只在跨行分支被读。

**交给另一个会话**：目标路径是 `alwaysHighFps=false` 的 `wakeUp()` /
`computeNextDelayNs`；**不要动 `alwaysHighFps=true` 路径**（用户确认丝滑）。

### 1.5 两个审查确认的真缺陷（提交 `aeff707`）

#### 缺陷 1：跨行分支前缘时长没有下限（**修完造成新回归，需决策**）

`TrailCorner.jump()`：`trailSize=1.0` 时 `leading = animationLength × 0 = 0`，
`DampedSpring` 首句短路 → 前缘每帧瞬移。而 `charsMoved` 是帧间累计量，
一帧落进 4+ 次击键就 > `typingMaxChars(3)` → 误切跨行分支
→「同一个按键，这次有拖尾、下次没有」。

修法（移植自 `claude/typing-jitter-fix-6c8c96` / c13f517）：
`leading = max(leading, shortAnimationLength × 0.5)` = 固定 55ms。

**实机结果（0.9.130）**：用户报「跨行密度像没有了，鼠标拉伸明显感觉跨行拖尾变小，
之前很密的现在三个预设都变了」。

数字原因（`shortAnimationLength=0.11`，`trailSize=1.0`）：

| 预设 | animationLength | 后缘 rank0 | rank1 | 前缘修前 | 前缘修后 |
|---|---|---|---|---|---|
| 基础 | 0.10s | 100ms | 50ms | 0ms | **55ms（占 55%）** |
| 缎带 | 0.28s | 280ms | 140ms | 0ms | 55ms（占 20%） |
| 纯平滑输入 | 0.10s | 100ms | 50ms | 0ms | **55ms（占 55%）** |

0ms 前缘不是 bug，是缎带形状的来源；固定 55ms 与 `animationLength` 无关，
短动画预设被改得最狠 →「三个预设都变了」。

**推荐方案 A**：只给「同一行内 burst」单独一条分支，真跨行保持 0ms：
```java
boolean sameLine = Math.abs(deltaYpx) <= height * 0.5;
boolean isShortJump = sameLine && charsMoved <= typingMaxChars;
boolean isHorizontalBurst = sameLine && !isShortJump;
if (isShortJump || isHorizontalBurst) { 短跳时长 = shortAnimationLength × factors }
else { 真跨行：leading = animationLength × (1 - trailSize)  /* 0ms 保留 */ }
```
**方案 B**：整体回退前缘下限，承认误判是既有行为。
详见 `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md`。

#### 缺陷 2：旧像素被缓存固化（保留，未造成回归）

`CaretAnimator.refreshTargetInReadAction()` 同一帧先 `visualPositionToXY` 读像素、
再 `markAt(line, col, stamp)`。若平台在文档修改后的第一帧返回旧像素，
缓存会把「新位置 + 旧像素」固化，下一帧快速路径早退 → 覆盖层停在旧位置
直到下一次编辑。

修法：文档戳本帧变过 → 记入 `recheckNextFrame`，下一帧强制再走一次慢路径。
`SpringTrail` 新增 `lastStamp()`。
**覆盖边界**：`VMultiCursorFix` S6-1 守「登记复查」；S6-2 在假编辑器里因
`cameraTrail` 缓存不同步，变异「去掉 contains」不会红——快速路径分支只有代码审查证据。

---

## 2. 诊断与日志的坑（以后排查必须知道）

1. **诊断日志本身会明显拖慢 EDT**：日志里出现过我们自己的 tick `cost=57739us`，
   随后连续 `rawDt=100~186ms` 并触发 `recovery`。**判断手感必须关诊断**；
   任何「实测手感」的结论，必须标明诊断开关状态。
2. **`[preset]` 日志会撒谎**：它在 `applyAnimPresetFields` 内部打印，
   面板以 `pending` 调用时也会打，**State 一个字节都没变**。
   真正能证明状态被改的只有 `[config] animationLength: A → B`（走 `syncStateToConfig`）。
3. **门禁不能并发跑**：共用 `build\v2-probe\classes`，并发会 ClassNotFoundException，
   失败数从 1 暴涨到 16/17（今天实际遇到一次）。
4. **`-Repo` 必须用正斜杠**（Git Bash 会吃掉反斜杠）：
   `-Repo 'D:/deepseekwork/neovim-cursor-pr'`。

---

## 3. 两个审查代理的主要发现（2026-11-03）

### code-reviewer
- 0 CRITICAL / 2 HIGH / 3 MEDIUM。HIGH：
  - 跨行判据用累计 `charsMoved`，而注释承诺的「同方向持续推进」修复不存在；
  - `markAt` 早于 `setTarget`，存在旧像素固化窗口。
- 明确反对「用 `animationLength` 解释删除延迟」：同一条行内退格只差 7.7~8ms。

### java-reviewer
- 2 HIGH：`tickSpring` 签名变更打断 `VMultiCursorFix` 反射（已修，保留两参重载）；
  文档计数未同步（已修）。
- 时序推演：删除延迟主体是**首帧交付延迟**，不是弹簧时长；
  `anim=0.28` 反而会让跨行后缘更长（280ms），与「更正常」相矛盾。
- 提醒：实测 jar 与 HEAD 不一致（仓库里没有 `step=`/`jumped=` 字段），
  说明当时装的是 attempt-1 构建；任何跨版本手感对比都要先核对 jar。

---

## 4. 当前状态

```
分支        fix/bug-016-completion-flicker
提交        aeff707（跨行前缘下限 + 像素复查 + install.ps1 时机 + 文档/探针）
已装 jar    用户已回退到旧版（不含 aeff707 的跨行修改）
工作区      未回退（保留给下一步）
探针        VTypingSpan 5/5、VMultiCursorFix 11/11（含 S6）
全量门禁    ALL PASS（VRegr2 R-5/R-6 仍 informational）
D0          core/NeovideConfig.java 零 diff
未 push
```

相关文档：
- `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md`：下一步修法与验证步骤（方案 A/B、T-6/T-7）
- `docs/BUG-LOG.md §BUG-016`：现象、证据、两个缺陷、常驻刷新根因
- `build/bug016-attempt1.patch`：被否决的限速方案（存档，未入库）

---

## 5. 需要决策的事项

1. **跨行手感**：按方案 A（同一行 burst 单独分支）继续修，还是先整体回退
   `aeff707` 里 `TrailCorner` 的那一行？用户已把安装版回退，工作区未退。
2. **常驻刷新 OFF 路径**：交给另一个会话（已给落点：`wakeUp()` /
   `computeNextDelayNs` 自适应；不要动 ON 路径）。
3. **BUG-016 原始报障**（弹窗打开时闪烁/短跳）：仍未修；attempt-1 已否决，
   下一步需要重新定方向（但今天的证据表明它可能也与 EDT 交付延迟有关，
   需要无诊断条件下的干净对照）。
4. **探针覆盖缺口**：S6-2 不能区分「快速路径分支」；是否要投入重构假编辑器
   （让 cameraTrail 与活动 trail 同步）由决策会话定。

---

## 6. 给决策会话的建议

- **先回退跨行下限**（一行 `Math.max`），让三个预设恢复原密度；
  再按方案 A 只修误判路径——这是唯一能同时满足「密度」和「连打稳定」的顺序。
- **删除延迟不要和 BUG-016 混**：前者是常驻刷新 OFF 的空闲/自适应档；
  后者是弹窗打开时的帧交付。两条线要分开验收。
- **任何手感结论必须标注**：诊断开关状态、jar 版本（内嵌 version）、
  预设指针与实际字段（`stretchRatioK` / `animationLength`）、电源状态
  （今天有一次是电池+安静模式造成的误判）。
- **不要再用「固定时长下限」去修共享分支**：它按定义会同时改掉正常路径；
  要改就改判据，让正常路径根本不进来。

---

## 7. 2026-11-04 更正（本文的现状以此为准）

> 本文写于 2026-11-03，正文里的「当前 / 已装 / 下一步」都是那一天的快照。
> 原文保留，下面逐条更正。

| 位置 | 当时的说法 | 2026-11-04 的事实 |
|---|---|---|
| 文首 / §4 | 提交 `aeff707`；已装 jar「用户已回退到旧版」 | HEAD 已是 `fddd529`；**已装 0.9.131**（jar 内版本实测） |
| §1.5 / §5 决策项 1 | 按方案 A（同行 burst 单独分支）继续修 | **方案 A 已实施（`5de3b37`），当天撤回（`fddd529`）**；B 降级为「未复现的代码疑点」，不再修 |
| §1.5 缺陷 1 | 55ms 前缘下限「需决策」 | Step C（`79acfd3`）已撤回，**真跨行前缘回到 0 ms**；用户已验收 |
| §1.5 缺陷 2 | 编辑后像素复查「保留」 | 保留，未回退 |
| §5 决策项 3 | A 仍未修，需重新定方向 | 仍未修；2026-11-04 用户决定**暂缓**（「很难告」），不是放弃 |
| §5 决策项 4 | 探针覆盖缺口待决策 | 维持现状；本轮不投入假编辑器重构 |
| §1.1 / §2 | 诊断采集于 0.9.127 | 那是**历史取证版本**；当前基线是 **0.9.131**，不再回退 0.9.127 |
| — | 未提 `trailSize` | 当前预设「基础测试2」，`trailSize = 1.0`（当前值 = 期望值） |

**下一步**：只做「跨行密度」实验，A/B/C/D/E 的代码都不动。
完整摘要见 `docs/CURRENT-STATE-2026-11-04.md`。
