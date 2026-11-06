# A1 基础方案（产品对齐版，交决策会话审核）

> 状态：产品语义已由用户确认；**本文件不实施代码**，交决策会话审核后再开新的执行会话。
> 依据：`REPORT.md` §9 的 320 条实机 `[branch]` 日志分析。
> 当前 worktree：`claude/determined-kowalevski-c1c64b`，基线 `45d3911`（本轮只改文档）。

## 1. 用户已确认的产品语义（逐字依据）

> 「a1吧统一语义最重要，然后以后想实现也好弄 3.1 多光标做a1 那单光标 Tab/End/鼠标跳转，选区，按词移动（ctrl + 方向左右 或者 ctrl shift + 方向左右）也要同步吧 现在是tab斜的 ctrl + 方向左右 或者 ctrl shift + 方向左右 这个也是斜的，这个单光标部分怎么办 要统一一起统一」

> 「1 视觉 2 是的 3 可以 5 ok」

对应决策：

1. **A1**：采用「同一视觉行 → 短跳；视觉行变化 → 跨行」的统一语义。
2. **单光标与多光标统一**：不再让单光标继续走 `charsMoved` 的字符数分支、多光标走像素兜底；两者共用视觉行规则。
3. **视觉行 = 屏幕视觉行**：使用 IDEA 的 `VisualPosition.line`；软换行跨到下一屏行算「视觉行变化」。
4. **同一视觉行的大跳也短跳**：End/Home、Tab、Ctrl+←/→、Ctrl+Shift+←/→、鼠标同视觉行点击/拖拽、按词移动，全部短跳。
5. **真实换行仍跨行**：Enter、上下行、行尾回绕、软换行跨视觉行、跨行选区/鼠标跨行，全部跨行。
6. **同帧混合可接受**：同一帧里一个光标真实换行走 CROSS、另一个同行走 SHORT 是语义正确，不是旧的「速度不同导致的分裂」。
7. **B1 暂缓**：本轮不改跨行后缘 `trailFactors`；A-like 前缘 20ms / 后缘斜度、`trailSize=1.0` 保持不变。

## 2. 目标与非目标

### 目标

- 用一条产品规则替换现有两套分支判据（单光标字符数 / 多光标像素阈值）。
- 消除同一视觉行内「一个光标斜、一个直」的速度分裂。
- 保留真换行的跨行形状（A-like 或旧三档，按现有开关）。
- 保持单光标和多光标在同一操作下行为一致。

### 非目标

- 不做 B1（跨行后缘按 `trailFactors`）。
- 不做 B2（跨行完全改用短跳四档）。
- 不改 `TrailCorner.jump()` 的时长公式、`rank`、`trailFactors` 派生、A-like 常量/前缘 20ms。
- 不改 `trailSize`、动画时长、预设、颜色/辉光/淡出、UI 控件、State 字段、`NeovideConfig.java`（D0）。
- 不在本文件/本会话实施；不安装、不 push、不合并。

## 3. 行为真值表

| 操作 | 动画跟随点视觉行变化 | 分支 |
|---|---|---|
| 普通输入 / 退格（同行） | 否 | SHORT |
| Tab / Shift+Tab | 否 | SHORT |
| End / Home（同视觉行） | 否 | SHORT |
| Ctrl+← / Ctrl+→（同视觉行） | 否 | SHORT |
| Ctrl+Shift+← / Ctrl+Shift+→（同视觉行） | 否 | SHORT |
| 鼠标点击 / 拖拽（同视觉行） | 否 | SHORT |
| 按词移动（同视觉行） | 否 | SHORT |
| 任意同行大跳（含 End/Tab/鼠标远距离） | 否 | SHORT |
| Enter / 上下行 | 是 | CROSS |
| 行尾回绕到下一行 | 是 | CROSS |
| 软换行跨到下一视觉行 | 是 | CROSS |
| 跨行选区 / 跨行鼠标拖拽 | 是 | CROSS |

说明：SHORT 仍保留 `shortAnimationLength × trailFactors[rank]` 的 rank 梯度和 slope；它不再是跨行的「一边直、一边斜」楔形。CROSS 仍走现有 A-like / 旧三档形状。

## 4. 实现落点（供决策会话改写成执行 PROMPT）

### 4.1 分支选择

文件：`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java`

建议做法：

1. 在 `refreshTargetInReadAction` 的每个 trail 处理中，在 `trail.markAt(...)` **之前**读取上一帧视觉行：
   - 复用/新增包内只读访问器：`SpringTrail.lastVisualLine()`（现有 `lastVisualLineForProbe()` 可保留为诊断别名）。
2. 计算：
   - `boolean sameVisualLine = wasInitialized && previousVisualLine == cvp.line;`
   - `int semanticChars = sameVisualLine ? 0 : Integer.MAX_VALUE;`
3. **单光标与多光标都使用 `semanticChars`**：
   - 不再对单光标调用 `trail.updateCharsMoved(caretOff)` 做分支；
   - 不再对多光标调用 `updateBlockCharsMoved()` 获取 `-1` 做像素兜底。
4. 仍调用 `trail.applyCharsMoved(semanticChars)`；`TrailCorner.jump()` 不改：
   - `charsMoved=0` + 同一行 → SHORT；
   - `charsMoved=Integer.MAX_VALUE` + 视觉行变化 → CROSS（`charsMoved <= typingMaxChars` 必然为 false）。
5. 新 trail 首帧只在 `setTarget` 中 `snapToTarget`，不执行 `jump()`；首帧不需要产生分支日志。

### 4.2 需要清理/保留的旧链路

A1 后以下内容对分支不再有作用，需决策会话决定保留还是删除：

- `updateBlockCharsMoved()`（多光标恒 `-1` 的方法）；
- `SpringTrail.updateCharsMoved(caretOff)` / `lastCaretOffset`（单光标字符差）；
- `TrailCorner.charsMoved` 的 `>=0` 分支仍会被 `0/MAX` 使用，但 `typingMaxChars` 不再参与 A1 决策；
- `[branch]` 日志里的 `charsMoved` 将显示 `0` / `2147483647`，不再是 `-1`。

建议：**保留字段作诊断/未来扩展，但分支不再读取字符数**；是否删除必须在执行 PROMPT 写清，不能留下两套判据。

### 4.3 块选区 / 多光标

- 每个 `SpringTrail` 用自己的上一帧 `lastLine` 与当前 `cvp.line` 判断；
- 块选区同一行内移动 → SHORT；跨视觉行 → CROSS；
- 不把所有光标强行共享一个 `0/MAX`，否则会重新引入「一行换行、另一行没换」的错误统一。

### 4.4 模式边界

- `TrailMode.AFTERIMAGE` 不使用 `TrailCorner` 分支；A1 只影响 SPRING 模式；
- 静态光标/控制台只画静止光标，不受影响；
- A-like 开关只影响 CROSS 内部形状，不影响 A1 的 SHORT/CROSS 选择。

## 5. 守门探针（先 RED 后 GREEN）

不新增独立 `V*.java`（避免 `VDocCheck D-2` 的 118→119）；在既有探针文件加同文件顶层类或扩展 `VMultiBranchDiag`。

至少断言：

1. 单光标同行 1/2/4/10 字符、Tab/End 模拟 → 全部 SHORT；
2. 单光标 Enter/上下行 → CROSS；
3. 多光标同行、不同速度 → 全部 SHORT（不再一斜一直）；
4. 多光标真实换行 → CROSS；
5. 同帧一个真实换行、一个同行 → 允许 SHORT + CROSS，且 CROSS 只来自换行者；
6. A-like true/false：CROSS 前缘 20ms / 后缘值不变；同行 SHORT 不读 A-like；
7. `slopeTicks=0/100`：SHORT 分支按 `trailFactors`；CROSS 内部形状不变；
8. `TrailCorner.jump()` 四角时长与现有一致（A1 只改传入的 `charsMoved`，不改公式）；
9. 现有 `VMultiBranchDiag` 的 D-10d 等写死 `-1`/`charsMoved=0` 断言按新契约更新，不能删除断言；
10. 变异测试：删视觉行判断、全部 `0`、全部 `MAX`、单光标误改，都必须有断言红。

## 6. 验证与交付

```bash
# 全量门禁（串行，唯一权威）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 \
  -Repo <worktree> -Libs D:\libs

# D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

- 变异测试至少三组，恢复后全量门禁 `ALL PASS`；
- 只打包不安装；建议版本 `0.9.138-a1`（具体由决策会话定）；
- 用户实机验证：单光标 + 多光标 × 英文/中文 × Tab/End/Ctrl+方向/Ctrl+Shift+方向/鼠标/选区/按词移动/Enter/行尾回绕；
- 特别验收：同一行大跳不再楔形；真实换行仍有现有 A-like 形状；同一帧真实换行混合可接受。

## 7. 风险

| 风险 | 概率 | 说明 / 处理 |
|---|---|---|
| 44 条同行大跳（Tab/End/Ctrl+方向）变短跳，观感变化 | 高（已接受） | 用户已确认「统一一起统一」；实机重点看这些操作 |
| 软换行跨视觉行被判 CROSS | 中 | 用户已确认视觉行定义；长软换行文本要实机看 |
| 块选区跨行时不同 trail 分支不同 | 中 | 这是语义正确；探针覆盖 |
| 旧 `charsMoved`/`typingMaxChars` 链路变成死代码 | 中 | 决策会话决定保留作诊断或删除，不能两套判据并存 |
| 现有探针写死 `-1` 的断言变红 | 高 | 按契约变更更新断言并做变异，不删断言 |
| A-like/slope 变化样本缺失 | 中 | A1 不动这些形状；实机补 A-like=true、slope=100 |
| B1 被顺手混入 | 中 | 明确非目标；B1 另立方案 |

## 8. 交给决策会话的未决实现问题

1. `updateBlockCharsMoved()` / `SpringTrail.updateCharsMoved()` / `typingMaxChars`：A1 后保留作诊断，还是删除？执行 PROMPT 必须明确，避免留下第二套判据。
2. `SpringTrail.lastVisualLine()` 的命名：新增生产访问器，还是把现有 `lastVisualLineForProbe()` 改名（诊断调用同步）？
3. 新探针放 `VTrailCap.java` 同文件，还是扩 `VMultiBranchDiag`？不得新增第 119 个 `V*.java`。
4. A1 的版本号、提交策略（单提交/分两次）、是否保留诊断日志默认开关。
5. 块选区跨行是否按「每个 trail 各自视觉行」执行；本方案倾向是，需要决策会话确认。
6. 单光标 `Ctrl+End` 等跨行大跳：按视觉行变化走 CROSS；如用户希望特殊处理，需另立规则，不在 A1。

## 9. 停止线

本文件只到「基础方案」；**不实施、不安装、不 push、不合并**。决策会话审核并改写为新的执行 PROMPT 后，由新的执行会话实施；当前会话上下文已不足，不在这里继续改代码。
