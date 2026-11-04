# Step C 施工提示词：撤回 55ms 跨行前缘下限（2026-11-03）

> 整段复制给执行会话。自包含；新会话不需要回翻对话。
> 仓库：`D:\deepseekwork\neovim-cursor-pr`
> 分支：`fix/bug-016-completion-flicker`（已存在；不要新建、不要 reset）
> 当前 HEAD：`aeff707`（未 push）
> 记录：`docs/archive/decisions/DECISION-REPORT-2026-11-03.md` §1.5 / `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md` §二、§三

> **【2026-11-04 已验收、保留；C 不下调】**
> 真跨行前缘已回到 **0 ms**，三个内置预设的跨行密度恢复，用户已实机验收。
> 本提示词保留为「为什么撤下限」的证据；**不要再执行，也不要把下限加回去**。
> 当前状态见 `docs/CURRENT-STATE-2026-11-04.md`。

---

【任务】
修 Step C：撤回 0.9.130（`aeff707`）在 `TrailCorner.jump()` 跨行分支加的固定 55ms 前缘下限，
恢复「真跨行前缘 0ms」的原公式，让基础 / 缎带 / 纯平滑三个预设的跨行密度回到 0.9.130 之前。

**这不是修 B（连打误判为跨行）**——B 等 C 实机通过后另开提示词。
**也不是修 A（补全弹窗闪烁）或 D（删除延迟）**——那两条线不要碰。

【已知环境】
- Windows 11，IDEA 2026.1（build 261），已装插件 **0.9.127**（读 jar 内
  `META-INF/plugin.xml` 实测，不认文件名）。
- 当前 `aeff707` 含三部分：
  - 跨行前缘下限（**本次要撤**）；
  - 编辑后像素复查（**保留**，与手感无关）；
  - `install.ps1` 备份时机（**保留**，与手感无关）。
- 工作区有未提交/未跟踪项，见「② 前置检查」；**不要 reset，不要动无关文件**。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
① 工作流（`development-workflow.md` 五步；第一步按 bug 修正执行「复现 + 证据」）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**Step 0 Research = 复现 + 证据（先不改生产代码）**
- 读：`docs/archive/decisions/DECISION-REPORT-2026-11-03.md` §1.5、`docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md` §二/§三、
  `docs/BUG-LOG.md` §BUG-016。
- 读代码：`core/TrailCorner.java` 的 `jump()` 跨行分支、`ui/settings/NeovideCursorConfigurable.java`
  的跨行提示、`tools/settings-probe/VTypingSpan.java` 的 T-1/T-2、`tools/settings-probe/VHints.java` 的 H-5。
- 跑当前 `VTypingSpan` 基线，确认它断言的是 55ms 下限；用代码事实复算：
  - `trailSize=1.0` → `leading = animationLength × (1 - 1) = 0`；
  - `aeff707` 改成 `max(0, shortAnimationLength × 0.5) = 55ms`；
  - 基础 / 纯平滑 `animationLength=0.10s`（55ms 占 55%）；缎带 `0.28s`（占 20%）。
- 确认面板提示与 `VHints` H-5 也绑定了 55ms + 「触底」。
- 产出：一段「修复前证据」写进 `docs/BUG-LOG.md §BUG-016` / `HANDOFF`，**不先改生产代码**。

**Step 1 Plan = `planner` 代理**
- 输入：Step 0 证据；输出：改哪几行、探针契约怎么改、风险、验证顺序。
- 证据不足就停下报告，不许猜。

**Step 2 TDD = `tdd-workflow` 技能**
- 先改探针到「恢复后的契约」（红）：
  - `VTypingSpan` T-1：**真跨行**（`deltaY > height/2`）`leading == 0`；
    不要用同一行 `charsMoved=4` 冒充真跨行。
  - `VTypingSpan` T-2：短跳 `charsMoved=1..3` 逐位等于
    `shortAnimationLength × trailFactors`；跨行 `charsMoved=4..6` 的 `leading == 0`
    （这是 Step B 要修的已知误判，Step C 只恢复它）。
  - `VHints` H-5：从「55ms + 触底」改为「0 ms + 无触底」。
  - T-3/T-4/T-5 保留，不改方向。
- 再改生产代码（见 ⑤ 白名单）：删 `Math.max`；删面板 floor 逻辑；改两语言文案。
- 变异测试：
  - 把 `Math.max` 加回去 → T-1 / H-5 必红；
  - 把真跨行 `leading` 改成非 0 → T-1 必红。
- **不许删断言**；只许改方向，并在注释/提交信息里写「契约恢复，非缺陷」。

**Step 3 Review = `code-reviewer` + `java-reviewer`（硬性，等结果）**
- 把 `git diff` 完整交给两个代理；要求 CRITICAL / HIGH / MEDIUM 清单。
- CRITICAL/HIGH 必须修；MEDIUM 要么修，要么写清不修理由。
- **等两个代理都返回再继续**；不许 fire-and-forget（遵守
  `rules/common/agents.md` 的 Delegation Completion Contract）。
- 特别让 `java-reviewer` 核对：
  - 面板提示公式与 `TrailCorner.jump()` 是否再次同源；
  - `VHints` 断言是否与两语言文案一致；
  - 是否还有别的地方把 55ms 下限写死（全仓 grep `shortAnimationLength * 0.5`）。

**Step 4 Verify = `verification-loop` + `delivery-gate`**
- 全量门禁（**只跑一份**，`-Repo` 用正斜杠）；D0；编译；打包 0.9.131；安装；实机。
- 安装用 `build/install.ps1 -Version 0.9.131 -NoStart`；脚本会先备份、装完还原个人配置，
  但只还原 XML 里实际存在的字段。装完先核对（以用户当前实际为准）：
  `alwaysHighFps=true`、`stretchRatioK=8.0`、`animationLength=0.28`、`animPresetBase=1`。
- 实机对比基线：**0.9.127**（当前已装）的跨行密度。装 0.9.131 后逐项看：
  鼠标拖拽、行首退格、正常换行、上下跳。

**Step 5 Commit**
- 在 `fix/bug-016-completion-flicker` 分支本地 commit，**不要 push**。
- 等用户实机验收；用户确认后再合并/推送。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
② 前置检查（数字自己跑，不许抄上一份文档）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

在仓库根执行，并把输出记进最终报告：

```
git status --porcelain
```

预期（**不要 reset**）：

```
 M docs/BUG-LOG.md
 M docs/INDEX.md
?? build/night-run-report.md
?? deepseekworkneovim-cursor-pr/
?? docs/archive/decisions/DECISION-REPORT-2026-11-03.md
?? docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md
?? docs/archive/prompts/PROMPT-STEP-C-2026-11-03.md   （本提示词自身；不算意外）
```

- `docs/INDEX.md` 的未提交改动不是本任务的，**不要 stage/commit**；只在最终报告里注明它仍保留。
- 出现其他未跟踪/已修改文件，先停下报告。

```
git branch --show-current        # 预期 fix/bug-016-completion-flicker
git log -1 --oneline             # 预期 aeff707
```

已装版本（读 jar 内部，不信文件名）：

```
unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" META-INF/plugin.xml | grep -o "<version>[^<]*"
```

预期 `<version>0.9.127`。

探针数量（开工时自己跑，**不要抄 CLAUDE.md 的旧数字**）：

```
ls tools/settings-probe/V*.java | wc -l        # 2026-11-03 实测 118
ls tools/settings-probe/*.java | wc -l         # 2026-11-03 实测 133
```

注册探针数（`$mains` 去重）：

```
awk '/\$mains *= *@\(/{f=1} f{print} f&&/^\)/{exit}' tools/settings-probe/build-v-probes.ps1 \
  | grep -o "'V[A-Za-z0-9]*'" | tr -d "'" | sort -u | wc -l   # 2026-11-03 实测 115
```

门禁基线（**只跑一次，严禁并发**；并发会导致 ClassNotFoundException 假红）：

```
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo 'D:/deepseekwork/neovim-cursor-pr'
```

记录最后一行 `RESULT`；**以实际输出为准**，不要抄文档。基线预期 ALL PASS。

D0：

```
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

必须零 diff。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
③ AC（可观察行为，不是「门禁绿」）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**AC-1 真跨行前缘恢复 0ms**
换行 / 上下跳 / 鼠标拖拽等 `deltaY > height/2` 的真跨行：
`leading == 0`（`trailSize=1.0`），`rank0 == animationLength`。

**AC-2 三个预设的跨行密度恢复**
基础 / 缎带 / 纯平滑输入三个预设，跨行拖尾密度回到 **0.9.130 之前**（以 0.9.127 为基线）。
用户实机看：鼠标拖拽拉伸、行首退格、正常换行，确认「密度回来」。

**AC-3 面板文案不再撒谎**
面板「跨行动画时长」在 `trailSize=1.0` 时显示 `leading = 0 ms`；
不再出现「已触底」/「at the floor」；中英两语言一致。

**AC-4 探针契约恢复，不删断言**
`VTypingSpan` T-1/T-2 改为恢复后的契约；`VHints` H-5 改为 0ms + 无触底；
T-3/T-4/T-5 保持通过；全量门禁 **ALL PASS**；D0 零 diff。

**AC-5 明确不修 B/A/D**
Step C 只恢复 0.9.130 之前的行为；「同一行连打被误判成跨行」的旧问题仍在，
Step B 另开提示词。**不要声称 B/A/D 已修。**

**AC-6 无回归**
短跳分支 `charsMoved=1..3` 的四角时长与 `shortAnimationLength × trailFactors`
逐位一致；真跨行梯度（前缘比后缘快）保持；跨行前缘不超过后缘。

**AC-7 用户实机验收**
用户确认「三个预设的跨行密度回来、面板不再显示已触底、鼠标拖拽正常」。
没有用户确认，不许说「修好了」。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④ 禁止话术 / 禁止做法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

- 禁止 `git reset --hard v0.9.127` 或任何全量回退：会丢失像素复查、`install.ps1` 修复和本文档。
- 禁止再用「固定时长下限」修共享分支：它按定义会同时改掉正常路径。
- 禁止删断言、改断言迁就代码、放宽阈值让红变绿；契约变更必须写明「契约恢复/契约变更，非缺陷」。
- 禁止把 `VHints` H-5 删掉、改成 warning 或 `exit 0` 绕过。
- 禁止混入 B（连打判据）、A（弹窗闪烁）、D（常驻刷新 OFF）的改动。
- 禁止改 `core/NeovideConfig.java`（D0）、`CaretAnimator`、`SpringTrail`、`install.ps1`。
- 禁止并发跑门禁；`-Repo` 必须用正斜杠 `'D:/deepseekwork/neovim-cursor-pr'`。
- 禁止在未实机验收前说「修好了」；只能说「探针绿，待实机」。
- 禁止说「0ms 是 bug」——真跨行 0ms 前缘是缎带形状的来源。
- 禁止「只删一行，不用跑全量门禁」——必须全量门禁 + 变异测试。
- 禁止先 commit 再验证；禁止 push。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑤ 白名单（只许改这些；要扩先停下报告）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**直接允许：**

- `src/main/java/com/github/yyyolo7a79/neovidecursor/core/TrailCorner.java`
  - 只删跨行分支的 `leading = Math.max(leading, config.shortAnimationLength * 0.5);`
  - 恢复原公式 `double leading = config.animationLength * clamp(1f - config.trailSize, 0f, 1f);`
  - 更新 0.9.90 注释块，写明「Step C 撤回；连打误判留给 Step B」，不留误导注释。

- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/settings/NeovideCursorConfigurable.java`
  - 删除 `floorLeading` / `floored` / `Math.max`；
  - 恢复 `long leading = Math.round(rawLeading * 1000f);`
  - 删除 `floorNote` 及其传参；更新注释（面板与 `TrailCorner` 再次同源）。

- `src/main/resources/messages/NeovideCursorBundle.properties`
- `src/main/resources/messages/NeovideCursorBundle_zh_CN.properties`
  - 删除 `settings.hint.crossMoveFloor` 键（两语言同步）；
  - 从 `settings.hint.crossMove` 模板里删掉 `{3}` 和「前缘最低为短动画时长的一半」/
    「the leading edge never goes below half...」的句子；
  - 两语言保持键集合一致。

- `tools/settings-probe/VTypingSpan.java`
  - T-1：改为真跨行（垂直位移）`leading == 0`；如需垂直 helper，在本文件内加。
  - T-2：改为「短跳 1..3 = short × factors；跨行 4..6 leading == 0」；
    不要保留「极差 <= 3 倍」这种会强迫 55ms 下限回来的断言。
  - T-3/T-4/T-5 保留。

- `tools/settings-probe/VHints.java`
  - **必须加入白名单**：H-5 直接断言「55ms + 触底」，撤掉下限后它必红；
    不改它就不可能「全量门禁 ALL PASS」。
  - 只改 H-5：断言 `0 ms` 且不含「触底」/「at the floor」；其他断言不动。
  - 这是任务书白名单的**必要修正**，不是扩大范围；若不同意，先停下报告，
    不要静默删 H-5 或放宽它。

- `docs/BUG-LOG.md`
- `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md`
  - 只回填 Step C 的证据、结论、验证结果；不重写其他条目。

**禁止修改：**

- `core/NeovideConfig.java`（D0）
- `ui/CaretAnimator.java`、`ui/SpringTrail.java`、`build/install.ps1`
- 其他探针（除 `VTypingSpan` / `VHints`）
- `settings/SettingsField.java`、`settings/CursorPresets.java`
- 颜色/辉光默认值、UI 控件、release 脚本
- `docs/INDEX.md`（它的未提交改动不是本任务的）
- 任何 worktree / 主仓库之外的路径

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑥ 交付格式（最终报告必须包含）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. 根因（一句话）+ 证据（代码行/数字，不是猜测）
2. 改动清单（每个文件改了什么；明确没有动 B/A/D）
3. 四段验证：红 → 绿 → 变异红 → 恢复绿；全量门禁 RESULT；D0 结果
4. 实机验收：安装版本、操作序列、用户观察结论（跨行密度 / 面板文案）
5. 两个代理的审查结论（CRITICAL/HIGH/MEDIUM 清单与处理）
6. 分支名 + commit hash（不 push）
7. 明确写出「Step C 未修 B/A/D」以及工作区仍保留的未提交文件

【第一条回复要求】
开工后第一条消息只报：你读到的 HEAD、门禁基线 RESULT、已装版本、
探针实际数量（V*.java / 注册数），以及你准备先做的复现/取证步骤。不要先改代码。
