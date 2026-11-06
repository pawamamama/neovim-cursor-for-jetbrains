# 多光标跨行斜度 · 诊断日志取证 施工提示词（2026-11-06）

> 状态：待执行
> 仓库：`D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\sleepy-swanson-ffc2cf`
> 分支：`claude/determined-kowalevski-c1c64b`
> 角色：执行会话只按本 PROMPT 干活、写 REPORT；决策会话不改生产代码；用户是唯一裁决人。
> 自包含：背景、证据、范围、命令、验收、报告要求全在本文件；执行会话不需要回翻对话。
> **本轮只做方案 C：加有界诊断日志供用户测试，拿到日志后再由用户决定 A / B1。不许实施 A、B1、B2 的任何行为修复。**

---

## 1. 背景与证据

### 1.1 用户原话（逐字引用）

peer 会话转达用户问题：

> 「用户问『走什么流程修复』」

本轮方向选择（用户在决策会话回答）：

> 「修复日志我测试-拿到日志-再看ab」

> 「新提交」

A-like 方向的前一条用户裁决（必须保持）：

> 「a-like方向对了，单行没有退化，只是做了前缘吸附 后缘没有做 现在是四个角左上 左下 对齐了，另外两边还是原来的斜度其实不错了」

此前用户明确授权过一次提交追溯：

> 「提交追溯」

### 1.2 只读诊断结论（来自 `multicursor-slant-bug` 会话，未改代码）

完整诊断报告在仓库外：

`C:\Users\wangw\AppData\Local\Claude-3p\side-session-notes\local_ee1e9e0b-bb2b-49b6-a4e0-f1c5e47e50c8\DIAGNOSIS-multicursor-crossline-2026-11-06.md`（213 行，决策会话已读）

两层根因（本轮**只记录，不修**）：

1. **形状结构**：跨行分支里 `rank2 == rank3` 且 `rank0 != rank1`，所以「一条边直、一条边斜」；`slopeTicks` 只被短跳分支读取，跨行分支不读 `config.trailFactors`。A-like 与旧三档都一样，与多光标无必然关系。
2. **分支判据**：`CaretAnimator.updateBlockCharsMoved()` 恒返回 `-1`（0.9.56 `83787a2` 起），多光标退化成像素兜底 `|deltaXpx/width| <= shortMoveThreshold(8)`；CJK 字宽约双倍、掉帧合并按键、同帧各光标速度不同，会让「中文斜、英文不斜」「同一帧一个斜一个直」。

视频证据：`D:\deepseekwork\video\neovim\2026-11-06 12-43-37.mkv`；逐帧发现 f1472/f1474 同一帧两光标一矩形一斜带，f1484 行尾回绕出现长斜带。视频里没有中文输入；中英文差异是代码推导，**必须靠本轮日志在用户机器上复核**。

诊断报告建议的方案：

| 方案 | 内容 | 本轮是否做 |
|---|---|---|
| A | 多光标判据改为「同一视觉行 → 短跳，视觉行变化 → 跨行」 | **不做** |
| B1 | 跨行后缘 rank0/1 按 `trailFactors` 取值，让 `slope=0` 真跨行变规整矩形 | **不做** |
| B2 | 跨行完全改用短跳四档，取代 A-like | **不做** |
| C | 先加只在 `diagLogging=true` 时输出的分支日志，用户复现 10 秒，再用日志决定 A/B | **本轮只做这个** |

### 1.3 决策会话实测状态（2026-11-06；执行会话开工必须重跑 §6 命令）

| 项目 | 事实 | 来源 |
|---|---|---|
| 执行 worktree 分支 / HEAD | `claude/determined-kowalevski-c1c64b` / `1733c11 feat(trail): 记录设备级跨行形状实验与 A-like 形状` | `git branch --show-current` / `git log -2 --oneline` |
| 工作区 | 干净（`git status --porcelain` 0 行）；peer 消息里「HEAD f43ca55 / 19 项未提交」是**过期信息** | `git status --porcelain` |
| 已装 IDEA jar | `<version>0.9.136-a-like-snap</version>`，249220 B，SHA256 `bb686d537ae921f0a94337444e63c08d2f172c862d11c9c590aae64f97bb5b20`；与 worktree `dist/neovide-cursor-0.9.136-a-like-snap.jar` 完全一致 | `unzip -p` / `sha256sum` |
| 提交追溯 | `1733c11` 已包含 21 个文件（生产代码、探针、两轮 PROMPT/REPORT）；**本轮修复完成后另起一个新提交，不 amend、不 push** | 用户答「新提交」 |
| 用户配置 | 诊断报告记录 `crossShapeExperiment=true`、`diagLogging=true`、`cursorHeightRatio=0.9`、`targetFps=144`；`slopeTicks` / `cursorWidthPx` 未写盘即取默认 `0` / `2`。执行会话只读复核，不改 XML | 诊断报告 + `grep` 配置 XML |
| D0 | `core/NeovideConfig.java` 零 diff | `git diff --exit-code -- ...` |
| 门禁日志 | `build/a-like-gate-alignfix.log`：`VCrossShapeExperiment 34/0`、`V-PROBES RESULT: ALL PASS`；这是既有日志，不是本轮重跑 | `grep` 日志 |
| A-like 当前形状 | `crossShapeExperiment=true` 时非短跳分支：对齐度 >0.5 的角固定 20ms，其余角按 `animationLength × {1.0,0.72,0.42,0.22}[rank]`；用户认可「前缘两角对齐、后缘保留斜度、单行无退化」 | 当前源码 + 用户原话 |

### 1.4 本轮到底交付什么

**只交付一条可关、可有界采样、能落盘的诊断日志**，让用户在真实机器上跑多光标中英文 / 长按方向键 / Tab / 真回绕，拿到日志后由用户决定 A / B1：

- 记录每次「决定走短跳还是跨行」的判据输入，不记录源码内容、文档文本、凭据或个人配置长串。
- 字段至少包含：时间、animator/caret 身份、是否多光标、`vp.line/column`、`deltaXpx/deltaYpx`、`width/height`、`charsMoved`、视觉行是否变化、最终分支 `SHORT` / `CROSS`、A-like 开关状态、`slopeTicks`。
- 只在 `diagLogging=true` 时输出；关掉后零额外成本、零额外日志。
- 采样要有上限（沿用 `CursorDiag` 既有预算表），不能把 `idea.log` 撑爆；到上限后写一条汇总。
- **不改任何动画时长、判据、rank、斜度、A-like 形状、UI 控件、State 字段、预设域**；日志是观察窗，不是修复。

---

## 2. 目标

一句话：**在 `diagLogging=true` 时，为「多光标 / 跨行分支选择」加有界诊断日志，产出可让用户实机复现并交付日志文件的最小改动；默认配置与所有动画行为零变化；用户拿到日志后再决定 A / B1，本轮不实施修复。**

完成后应达到：

1. 用户按 §5 的复现脚本操作 10 秒后，能从 `idea.log` 或既有诊断日志里筛出一行一条的 `[branch]` 记录，足以区分「真回绕」与「像素阈值误判」。
2. 日志字段能直接回答：同一帧不同光标是否走了不同分支、中文/英文是否因 `deltaXpx` 阈值分裂、Tab/End/行尾回绕走哪条分支、A-like 开关是否影响分支选择（应不影响）。
3. `diagLogging=false` 时没有任何新增日志与性能负担；`diagLogging=true` 时有明确上限/汇总。
4. 默认关的 `crossShapeExperiment` 与当前开着的 A-like 实验都不改变四角时长；单行手感零退化。
5. 新增守门探针证明日志开关、字段、上限和「零行为变化」；全量门禁 `ALL PASS`、D0 零 diff。
6. 产出一个**不自动安装**的 jar 给用户；REPORT 写清「日志在哪、怎么筛、怎么交回决策会话」。
7. 修复完成后基于 `1733c11` 新建一个 commit，不 amend、不 push、不合并。

---

## 3. 非目标（明确不做）

- **不做方案 A**：不把多光标判据改成「同一视觉行 → `applyCharsMoved(0/MAX)`」；`updateBlockCharsMoved()` 仍可返回 `-1`。
- **不做方案 B1**：不让跨行分支后缘读取 `trailFactors`；`slope=0` 真跨行仍是当前一边斜的楔形。
- **不做方案 B2**：不把跨行完全改成短跳四档，不取代 A-like。
- 不改 A-like 前缘 20ms 语义、不改旧三档 `trailSize` 公式、不改 `animationLength` / `shortAnimationLength` / `rank` / `assignRanks` / `trailFactors` 派生。
- 不改 `slopeTicks`、`stretchRatioK`、`typingSmoothness`、`typingMaxChars`、`shortMoveThreshold` 的默认值与量程；不改 `isShortJump` 判定结果。
- 不加 UI 控件、不加 State 字段、不改预设、不改 `customAnimPresets`、不改 `install.ps1` 还原语义。
- 不改 D0 文件 `core/NeovideConfig.java`；不改 `CursorPresets.java`、既有 `docs/` 文档、`CLAUDE.md`、`START-HERE.md`、状态文档、`docs/archive/`。
- 不自动安装、不自动启动 IDEA、不覆盖当前 `0.9.136-a-like-snap` 已装 jar；不提交前不得装新包。
- 不 amend `1733c11`、不 push、不建 PR、不合并到 main、不打 tag、不归档会话目录。
- 不替用户判断「该选 A 还是 B1」；不把日志推导写成已验收结论；不声称「修好了多光标斜度」。

---

## 4. 允许改的文件

### 4.1 生产代码（最小日志改动）

- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java`：在已有「算出 `charsMoved` / `applyCharsMoved(...)` / `needsUpdate`」附近加一次日志采集；只读现有变量，不改赋值与判据。
- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CursorDiag.java`：若需要新增 `[branch]` 行类型、预算表条目、汇总行，允许加最小静态方法和字段；必须沿用现有 `enabled()` / `diagLogging` 门控与 `synchronized` 写文件方式。
- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/SpringTrail.java`：仅允许加包内只读访问器（例如取上一帧视觉行 / `lastLine`），**不许改 `applyCharsMoved` / `assignRanks` / `setTarget` 行为**。
- `src/main/resources/messages/NeovideCursorBundle.properties` 与 `NeovideCursorBundle_zh_CN.properties`：仅当新增面向用户的诊断说明时必须中英同步；本轮优先不加 UI 文案，避免额外控件。

> 若执行会话查证后发现不碰白名单外文件就无法取得「视觉行是否变化」字段：允许退化为记录现有 `vp.line` / `deltaYpx` 与 `sameLine` 结果，并在 REPORT §6 写清「视觉行访问器未加、字段如何从现有值推断」；**不许自行扩大到改判据**。

### 4.2 探针与门禁注册

- 允许新增一个 `tools/settings-probe/VMultiBranchDiag.java`（或按 `VDocCheck D-2` 约束，把新探针类作为同文件顶层 package-private 类放进白名单内既有 `V*.java`；若新增独立 `V*.java` 会让 `V*.java` 数从 118 变 119，必须停下问，不许改三份状态文档）。
- `tools/settings-probe/build-v-probes.ps1`：若新增独立主类，只加一行类名；不改编译方式。
- 允许按契约变更更新必要探针常数（例如 `VAddSetting` / `VerifyPanel` 的控件数仅在真的加了控件时才改；本轮**不加控件，原则上不应改控件数**）。
- 不许删除既有断言；不许复刻生产算法；探针必须调用生产方法或断言生产行为。

### 4.3 本轮会话文档

- `docs/sessions/2026-11-06-multicursor-branch-diag/PROMPT.md`（本文件）
- `docs/sessions/2026-11-06-multicursor-branch-diag/REPORT.md`
- `docs/sessions/2026-11-06-multicursor-branch-diag/evidence/*.txt`（可选，只放命令输出、日志格式示例、变异记录）

### 4.4 明确禁区（只读）

- `src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java`（D0）
- `src/main/java/com/github/yyyolo7a79/neovidecursor/core/TrailCorner.java`（本轮不改形状/判据）
- `src/main/java/com/github/yyyolo7a79/neovidecursor/settings/CursorPresets.java`、`SettingsField.java`、`NeovideCursorSettings.java`
- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/settings/NeovideCursorConfigurable.java`、`AnimationPreview.java`
- `build/install.ps1`、`build/offline-build.ps1`、全部 `build/*.md`
- `CLAUDE.md`、`START-HERE.md`、`docs/CURRENT-STATE-2026-11-04.md`、`docs/NEXT-STEPS.md`、`docs/INDEX.md`、`docs/BUG-LOG.md`、`docs/WORKFLOW.md`、`docs/SESSION-PROTOCOL.md`、`docs/WORK-DISCIPLINE.md`、`docs/UPSTREAM-COMPARE-2026-11-02.md`、`docs/archive/`、既有 `docs/sessions/2026-11-05-*` / `2026-11-06-a-like-hard-snap/`
- 用户配置 XML、`%APPDATA%`、`_config-backup/`、`_jar-backup-20261024/`

> 需要改清单外的文件：停下，在 REPORT §6 写清原因，等用户裁决，不自行扩大范围。

---

## 5. 验收标准（可测）

| 编号 | 标准 | 怎么验 |
|---|---|---|
| AC-1 | `diagLogging=false`：新增日志路径零输出、零额外文件写入；探针断言 `CursorDiag.enabled()` 门控 | 新探针 + 单测式反射/静态断言 |
| AC-2 | `diagLogging=true`：一次「多光标同行 / 跨行」能产出 `[branch]` 行，字段至少含时间、animator/caret 身份、多光标、line/column、deltaXpx/deltaYpx、width/height、charsMoved、视觉行变化或 `sameLine`、分支 `SHORT` / `CROSS`、A-like 开关、slopeTicks | 探针解析格式化方法输出；日志格式示例贴 REPORT |
| AC-3 | 日志有界：沿用 `CursorDiag` 预算表，达到上限只补一条汇总，不无限增长 | 新探针循环触发 > 上限，断言条数与汇总行 |
| AC-4 | 零行为变化：改前/改后同一组输入下 `TrailCorner.jump()` 四角时长、A-like 开关 true/false、单行短跳时长逐位相同 | 新探针调用生产方法；`VCrossShapeExperiment` / `VTypingSpan` / `VUniform` / `VBlockStretch` 仍绿 |
| AC-5 | 不改判据：`updateBlockCharsMoved()` 仍返回 `-1`；`isShortJump` 代码路径与输入不变；不出现 `applyCharsMoved(0/MAX)` 等 A 方案实现 | 静态扫描 + 新探针 |
| AC-6 | 不实施 B1/B2：跨行分支不出现新的 `trailFactors` 读取；A-like 仍 `{1.0,0.72,0.42,0.22}` 与前缘 20ms；旧三档仍 `trailSize` 公式 | 静态扫描 + `VCrossShapeExperiment` |
| AC-7 | 不加 UI 控件 / State 字段 / 预设字段；`VAddSetting` / `VerifyPanel` / `VPreset` / `VDefaultFreeze` / `VTabPages` 的写死数量不变 | 全量门禁 |
| AC-8 | D0 `EXIT=0`；禁区文件零 diff；`git status --porcelain` 逐项可解释 | §6 命令 |
| AC-9 | 全量门禁最后一行 `V-PROBES RESULT: ALL PASS`；门禁串行、保留退出码、不并发 | §6 命令 |
| AC-10 | 三组变异测试：关掉 diag 仍输出 / 日志字段缺分支 / 日志误改 `charsMoved` 或判据，都必须有断言红；恢复后全绿 | REPORT 贴命令、红名单、恢复绿 |
| AC-11 | 只打包不安装：`dist/neovide-cursor-0.9.137-branch-diag.jar` 内 `<version>0.9.137-branch-diag</version>`、`since-build=242` / `until-build=261.*` | `offline-build.ps1` + `unzip -p` |
| AC-12 | 基于 `1733c11` 新建**一个**提交，包含本轮白名单文件与本 PROMPT/REPORT；不 amend、不 push、不合并 | `git log -2 --oneline` / `git status --porcelain` |
| AC-13 | REPORT 写清：日志文件路径、筛选命令、用户复现步骤（多光标中英文 / 长按方向键 / Tab / 真回绕各 10 秒）、如何把日志交给决策会话；不写「已修好」 | 人工抽查 |
| AC-14 | 用户实机取证由用户在 REPORT §7 填；执行会话不替用户判 A/B | 人工抽查 |

---

## 6. 必跑命令

在 `D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\sleepy-swanson-ffc2cf` 下执行。门禁**只跑一份，禁止并发**；管道必须保留退出码，不许 `| tail -N && git add`。关键输出贴进 REPORT §4。

### 6.1 开工前 30 秒 + D0

```bash
W="D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/sleepy-swanson-ffc2cf"
printf '=== status / branch / HEAD ===\n'
git -C "$W" status --porcelain
git -C "$W" branch --show-current
git -C "$W" log -2 --oneline
git -C "$W" rev-list --left-right --count fork/main...HEAD 2>/dev/null || true
printf '=== installed jar version ===\n'
J="/c/Users/wangw/AppData/Roaming/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar"
unzip -p "$J" META-INF/plugin.xml | grep -o '<version>[^<]*' | head -1
sha256sum "$J"
printf '=== D0 ===\n'
git -C "$W" diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
printf 'D0_EXIT=%s\n' "$?"
printf '=== probes / md ===\n'
ls -1 "$W"/tools/settings-probe/V*.java | wc -l
ls -1 "$W"/tools/settings-probe/*.java | wc -l
T=$(git -C "$W" ls-files '*.md' | wc -l)
A=$(git -C "$W" ls-files 'docs/archive/*.md' 'docs/archive/**/*.md' | sort -u | wc -l)
printf 'md total=%s archive=%s active=%s\n' "$T" "$A" "$((T-A))"
```

### 6.2 先搜现成日志设施 / 调用面（点名 `search-first` 技能 + `Explore` 代理）

```bash
W="D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/sleepy-swanson-ffc2cf"
printf '=== CursorDiag gate / budgets / writer ===\n'
grep -n -E 'enabled\(|diagLogging|TRAIL_MAX_LINES|TRAIL_BUDGETS|synchronized|writeFile|invalidateEnabled' "$W"/src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CursorDiag.java | head -80
printf '=== branch decision site ===\n'
grep -n -A 18 -B 12 'updateBlockCharsMoved\|applyCharsMoved\|needsUpdate' "$W"/src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java | head -120
printf '=== existing diag line formats ===\n'
grep -n -E '\[evt\]|\[tick\]|frameSummary|caretCountChanged|swallowed' "$W"/src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CursorDiag.java | head -60
```

### 6.3 先断言后代码（点名 `tdd-workflow` 技能）

```bash
W="D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/sleepy-swanson-ffc2cf"
printf '=== registered probes baseline ===\n'
awk '/\$mains *= *@\(/{f=1} f{print} f&&/^\)/{exit}' "$W"/tools/settings-probe/build-v-probes.ps1 | grep -o "'V[A-Za-z0-9]*'" | tr -d "'" | sort -u | wc -l
printf '=== VDocCheck D-2 contract ===\n'
grep -n -E 'countProbes|CLAUDE.md=|START-HERE|NEXT-STEPS' "$W"/tools/settings-probe/VDocCheck.java | head -30
printf '=== existing contract constants (should stay unchanged) ===\n'
grep -n -E 'sliders ==|checks ==|combos ==|== 35|GOLDEN|EXPECTED_COMPOSITION' "$W"/tools/settings-probe/VAddSetting.java "$W"/tools/settings-probe/VerifyPanel.java "$W"/tools/settings-probe/VPreset.java "$W"/tools/settings-probe/VDefaultFreeze.java "$W"/tools/settings-probe/VTabPages.java | head -50
```

> 先写守门探针让它红，再写日志代码让它绿。若新增独立 `V*.java` 会触发 `VDocCheck D-2`（118 → 119），**停下问**；优先把新探针类放进白名单内既有 `V*.java` 同文件，并在 REPORT §6 写清偏离理由。

### 6.4 全量门禁 + D0（点名 `verification-loop` 技能；串行，只跑一份）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1 -Repo D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\sleepy-swanson-ffc2cf -Libs D:\libs
```

```bash
W="D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/sleepy-swanson-ffc2cf"
git -C "$W" diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
printf 'D0_EXIT=%s\n' "$?"
```

> 若 `-Libs D:\libs` 不存在或脚本不接受该参数，按实际脚本参数核对后使用项目既有可用写法；不要把失败藏在 `| tail` 后面。Bash 调 pwsh 时写日志并保留 `$?`：
> `pwsh ... > build/multicursor-diag-gate.log 2>&1; G=$?; grep -E 'RESULT|FAIL' build/multicursor-diag-gate.log; printf 'GATE_EXIT=%s\n' "$G"; test "$G" -eq 0`。

### 6.5 变异测试（三组，恢复后全绿）

```bash
W="D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/sleepy-swanson-ffc2cf"
printf '=== mutation plan (write exact commands in REPORT) ===\n'
printf 'M1: 去掉 diagLogging 门控 -> AC-1 必须红\n'
printf 'M2: 日志格式删掉 branch/charsMoved 字段 -> AC-2 必须红\n'
printf 'M3: 日志采集处误改 charsMoved 或顺手实施 A/B1 -> AC-4 / AC-5 / AC-6 必须红\n'
printf '每次变异后删除 build/v2-probe/classes 全量重编译，恢复源码后再跑一次全量门禁\n'
```

### 6.6 只打包、不安装

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 -Version 0.9.137-branch-diag -OutName neovide-cursor-0.9.137-branch-diag.jar -BaseJar D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\sleepy-swanson-ffc2cf\dist\jetbrains-neovide-cursor-0.9.14-conclusion.jar
```

```bash
J="D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/sleepy-swanson-ffc2cf/dist/neovide-cursor-0.9.137-branch-diag.jar"
ls -l "$J"
unzip -p "$J" META-INF/plugin.xml | grep -o '<version>[^<]*\|<idea-version[^>]*' | head -2
sha256sum "$J"
```

### 6.7 收尾审查 + 新提交（点名 `code-reviewer` 代理，只读）

```bash
W="D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/sleepy-swanson-ffc2cf"
printf '=== changed files ===\n'
git -C "$W" status --porcelain
printf '=== production diff stat ===\n'
git -C "$W" diff --stat -- src/main/java src/main/resources
printf '=== forbidden files must be untouched ===\n'
git -C "$W" diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java src/main/java/com/github/yyyolo7a79/neovidecursor/core/TrailCorner.java src/main/java/com/github/yyyolo7a79/neovidecursor/settings/CursorPresets.java src/main/java/com/github/yyyolo7a79/neovidecursor/settings/SettingsField.java src/main/java/com/github/yyyolo7a79/neovidecursor/settings/NeovideCursorSettings.java build/install.ps1 build/offline-build.ps1 CLAUDE.md START-HERE.md docs/CURRENT-STATE-2026-11-04.md docs/NEXT-STEPS.md docs/INDEX.md docs/BUG-LOG.md
printf 'FORBIDDEN_DIFF_EXIT=%s\n' "$?"
printf '=== no A/B1/B2 implementation leaked ===\n'
grep -R -n --include='*.java' -E 'applyCharsMoved\(0\)|applyCharsMoved\(Integer\.MAX_VALUE\)|A_LIKE_TRAIL_FACTORS.*trailFactors|trailFactors\[rank\].*cross' "$W"/src/main/java | head -30
printf '=== no debug leftovers / secrets ===\n'
grep -R -n --include='*.java' -E 'System\.out\.print|TODO|FIXME|password|token|secret' "$W"/src/main/java | head -30
```

提交时只暂存白名单文件，逐个核对：

```bash
W="D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/sleepy-swanson-ffc2cf"
git -C "$W" add -- <本轮实际改动的白名单文件>
git -C "$W" diff --cached --name-only
git -C "$W" diff --cached --check
git -C "$W" commit -F - <<'EOF'
test(diag): 增加多光标跨行分支诊断日志

- 改了什么：在 diagLogging=true 时输出有界 [branch] 记录，字段覆盖 caret 身份、像素位移、charsMoved、sameLine、SHORT/CROSS、A-like 开关与 slopeTicks；不改任何动画判据或时长。
- 为什么：用户先测试并拿日志复核「真回绕 vs 像素阈值误判」，再决定方案 A / B1；本轮只做方案 C 取证。
- 验证方式：全量门禁 V-PROBES RESULT: ALL PASS、D0_EXIT=0、三组变异测试、只打包不安装；未实机取证、未实施 A/B1、未 push/合并。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
printf 'COMMIT_EXIT=%s\n' "$?"
git -C "$W" log -2 --oneline
git -C "$W" status --porcelain
```

> 提交是基于 `1733c11` 的**新提交**，不 amend。若用户中途改主意「不提交」，停在 REPORT §6 写「待用户裁决」，不要先斩后奏。

---

## 7. 禁止事项

1. 不扩大范围；清单外文件不改，需要改就停下问。
2. 不为了让门禁变绿而删断言、改断言方向；新增探针不得触发 `VDocCheck D-2` 写死探针数冲突，遇到就停下问。
3. 不实施方案 A / B1 / B2；不改 `updateBlockCharsMoved()` 的返回值、不改 `isShortJump`、不改跨行四角时长。
4. 不改 D0 文件、预设表、State 字段、UI 控件、内置/个人预设、颜色/辉光/淡出/帧调度、多光标身份 key。
5. 不把日志变成常驻输出；`diagLogging=false` 必须零成本；不许打印源码内容、文档文本或配置长串。
6. 不自动安装、不自动启动 IDEA、不覆盖当前 `0.9.136-a-like-snap` 已装 jar。
7. 不 amend `1733c11`、不 push、不建 PR、不合并到 main、不打 tag、不归档会话目录。
8. 不并发跑门禁，不用 `| tail -N && ...` 吞退出码；变异后必须删 `build/v2-probe/classes` 全量重编译。
9. 不静默吞异常；`catch` 必须回滚状态 + 打 warn，日志写入失败不得影响动画。
10. 不猜产品观感；「选 A 还是 B1」由用户看日志后裁决。
11. 不写死 commit hash / 版本号 / 探针数当永恒事实；所有数字以当次命令输出为准。
12. 不从 `docs/NEXT-STEPS.md` 挑其它待办开工；A-like「前缘两角对齐、后缘保留斜度、单行零退化」是用户已认可边界。

---

## 8. 分支与版本

- 分支：留在 `claude/determined-kowalevski-c1c64b`；不新建、不切换、不合并、不 rebase。
- 基线：决策会话复核 HEAD `1733c11`；执行会话以当次 `git log -2 --oneline` 为准。peer 消息里的 `f43ca55 / 19 项未提交` 已过期，不照抄。
- 已装 IDEA 插件：`0.9.136-a-like-snap`，SHA256 `bb686d53…ad23`；本轮不安装、不回退、不重装。
- 本轮产物版本：`0.9.137-branch-diag`，只写进新打包 jar 的 `plugin.xml`；不改仓库版本声明。
- 提交策略：基于 `1733c11` 新建**一个**提交（用户答「新提交」），不 amend、不 push、不合并。
- 上游/对照：PyCharm 2025.2 的 0.6.0 与 A-like 形状均不是本轮修复对象。
- 工作区：决策会话复核时执行 worktree 干净；执行会话开工前重新 `git status --porcelain`，有别人的改动先停下问。

---

## 9. 报告要求

按 `docs/SESSION-PROTOCOL.md` 第四节 REPORT 模板，写到同目录 `REPORT.md`：

`````markdown
# 多光标跨行斜度 · 诊断日志取证 执行报告（2026-11-06）

> 状态：待用户实机取证 / 部分完成 / 失败
> 提交：<新提交 hash 或「未提交」>　分支：claude/determined-kowalevski-c1c64b
> 产物：dist/neovide-cursor-0.9.137-branch-diag.jar（SHA256 ...）

## 1. 结论
一句话：交付了有界 [branch] 日志 / 部分完成 / 被阻塞；不得写「已修好多光标斜度」。

## 2. 根因
功能类写「为什么先做方案 C 取证，不实施 A/B1」；引用诊断报告两层根因与用户「拿到日志-再看ab」。

## 3. 改动清单
| 文件 | 改了什么 | 为什么 |
逐行列出，含日志字段、预算上限、新探针/门禁注册、本 PROMPT/REPORT。

## 4. 证据
- §6.1~6.7 每条命令 + 关键输出（不是「跑过了」）。
- 门禁：V-PROBES RESULT: ALL PASS 与注册探针数；D0_EXIT=0。
- 编译 / 打包：jar 内版本、兼容区间、SHA256。
- 变异测试：M1/M2/M3 各贴「改坏什么 → 哪条断言红 → 恢复 → 全绿」。
- AC-1~AC-13 逐条写 PASS/FAIL 与证据出处。
- 日志格式示例（脱敏，不含源码内容/配置长串）。

## 5. 用户实机取证步骤
1. 确认设置页「诊断日志」已开；记录当前 crossShapeExperiment 开关状态与 slopeTicks。
2. 安装或加载 0.9.137-branch-diag 后，在 IDEA 2026.1 做四组各 10 秒：
   - 多光标英文同行输入 / 长按 →；
   - 多光标中文同行输入；
   - 多光标 Tab / End / 行尾回绕；
   - 单光标对照。
3. 从 idea.log 或既有诊断日志筛 [branch]：
   grep -n '\[branch\]' "%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.1\log\idea.log" > branch.log
4. 把 branch.log 交回决策会话；不要自行判断 A/B。

## 6. 阻塞与偏离
- 任何没按本 PROMPT 做的点逐条写原因；没有写「无」。
- 需要改白名单外文件、或新增 V*.java 触发 D-2 时，写「待用户裁决」。

## 7. 验收记录（用户填）
- 实机环境、日志文件路径、四组操作是否都复现
- 哪些帧是真回绕、哪些是阈值误判
- 结论：选 A / 选 A+B1 / 需要下一轮 PROMPT + 现象
`````

额外要求：

1. 每个数字都有命令出处；没有就写「未验证」。
2. 用户原话逐字引用，不润色；「修复日志我测试-拿到日志-再看ab」「新提交」要体现在背景与提交策略里。
3. 不贴整份门禁日志、不贴整份 `customAnimPresets`、不贴整个 `plugin.xml`。
4. 不用「同上 / 略 / 见上文」代替内容。
5. 报告写完即停；不自行建议「下一步直接做 A+B1」，下一份 PROMPT 由决策会话在用户看完日志后另写。

---

## 10. 一句话停止线

**交付「可关、有界、字段够用的 [branch] 日志」+ 绿门禁 + 只打包不安装的 jar + 新提交 + 带用户取证步骤的 REPORT 后停下；不实施 A/B1、不安装、不替用户从日志下结论。**
