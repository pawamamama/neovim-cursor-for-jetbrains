# Step B 施工提示词：同行 charsMoved≥4 误判跨行（2026-11-04）

> 整段复制给执行会话。自包含；新会话不需要回翻对话。
> 仓库：`D:\deepseekwork\neovim-cursor-pr`
> 分支：`fix/bug-016-completion-flicker`（已存在；不要新建、不要 reset）
> 前置：Step C 已实机验收通过，已装 0.9.131；当前 HEAD `cda7141`（未 push）
> 记录：`docs/archive/reports/STEP-C-FIX-REPORT-2026-11-04.md` / `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md`

> **【2026-11-04 已作废】B 未复现，Step B 已撤回（`fddd529`）。**
> 用户表示「连打偶发无拖尾」从未感受到，而选词 / End / 鼠标拖选等副作用可见。
> 本提示词只保留为历史记录，**不要再执行**。
> 当前状态见 `docs/CURRENT-STATE-2026-11-04.md` 与
> `docs/archive/reports/STEP-B-REVERT-REPORT-2026-11-04.md`。

---

【任务】
修 Step B：同一行内 `charsMoved ≥ 4` 被误判成跨行分支，导致连打/删词时
「同一个按键，这次有拖尾、下次没有」。

方案 A：
- 新增 `isHorizontalBurst = sameLine && !isShortJump`；
- `isShortJump || isHorizontalBurst` 都走短跳分支
  （时长 = `shortAnimationLength × trailFactors[rank]`）；
- 真跨行（`sameLine == false`）保持 Step C 恢复的 0ms 前缘公式不变
  （`leading = animationLength × (1 - trailSize)`，`trailSize=1.0` → 0）。

**这不是修 C（跨行密度）、A（弹窗闪烁）、D（删除延迟）或 E（上游跨行模型实验）**。
C 已验收通过，B 不能把它改回去。

【已知环境】
- Windows 11，IDEA 2026.1（build 261），已装插件 **0.9.131**（读 jar 内
  `META-INF/plugin.xml` 实测，不认文件名）。
- 当前分支 `fix/bug-016-completion-flicker`，HEAD `cda7141`，`fork/main...HEAD = 0/5`（未 push）。
- 用户当前观感：`animPresetBase=2`（纯平滑输入），`trailSize=0.2`，`slopeTicks=100`。
  **验收必须切到 `trailSize=1.0` 的三个内置预设**（基础 / 缎带 / 纯平滑输入），
  否则同行误判的「前缘 0ms」特征不明显。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
① 工作流（`development-workflow.md` 五步；第一步按 bug 修正执行「复现 + 证据」）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**Step 0 Research = 复现 + 证据（先不改生产代码）**
- 读：`docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md` §四、`docs/BUG-LOG.md §BUG-016`、
  `docs/archive/reports/STEP-C-FIX-REPORT-2026-11-04.md` §7（未修事项）。
- 读代码：`core/TrailCorner.java` 的 `jump()` 判据与两个分支、
  `SpringTrail.updateCharsMoved()`、`tools/settings-probe/VTypingSpan.java`。
- 跑当前 `VTypingSpan` 基线；确认 T-2b 断言的是「同行 4..6 仍走跨行、前缘 0」，
  这正是 B 要改掉的已知误判。
- 用代码事实复算（`shortAnimationLength=0.11`）：
  - 同行 1..3：短跳 `short × trailFactors`；
  - 同行 4..6：当前跨行，`leading=0`、`trailing=animationLength`
    （基础/纯平滑 100ms，缎带 280ms）→ 同一个按键忽有忽无；
  - 真跨行：Step C 已恢复 0ms 前缘，B 不得改。
- 确认 B 的起源：2026-10-18 `74d3a8a`（当时 pluginVersion=0.4.3），
  不是 0.9.130 的回归；`git log --oneline --all -- core/TrailCorner.java` 可辅助核实。
- 产出：一段「修复前证据」写进 `docs/BUG-LOG.md §BUG-016`，**不先改生产代码**。

**Step 1 Plan = `planner` 代理**
- 输入：Step 0 证据；输出：改哪几行、探针契约怎么改、已知会红的探针、风险、验证顺序。
- 证据不足就停下报告，不许猜。

**Step 2 TDD = `tdd-workflow` 技能**
- 先改探针到「B 的目标契约」（红）：
  - `VTypingSpan` T-2b：从「同行 4..6 走跨行」改为「同行 4..6 走短跳」；
  - `VTypingSpan` T-3：必须改成**真跨行**（`sameLine=false`）；
    否则 B 之后它测的是短跳分支，名字与行为不符；
  - `VTypingSpan` 新增 T-6（真跨行密度守卫）与 T-7（同行 burst 守卫）；
  - `VBlockStretch` B-2、`VUniform` U-3 的旧误判断言按新契约改方向；
  - `VAuditBatch1` A-24 优先通过 `lastShortJump` 语义修正保持绿。
- 再改生产代码（见 ⑤ 白名单）。
- 变异测试：
  - 把 `isHorizontalBurst` 去掉 → T-2b / T-7 / VBlockStretch B-2 / VUniform U-3 必红；
  - 把真跨行 `leading` 改成非 0 → T-1 / T-6 必红；
  - 把 `lastShortJump` 只记 `isShortJump` → A-24 必红（零尺寸兜底丢分支）。
- **不许删断言**；只许改方向，并在注释/提交信息里写「契约变更，非缺陷」。

**Step 3 Review = `code-reviewer` + `java-reviewer`（硬性，等结果）**
- 把 `git diff` 完整交给两个代理；要求 CRITICAL / HIGH / MEDIUM 清单。
- CRITICAL/HIGH 必须修；MEDIUM 要么修，要么写清不修理由。
- **等两个代理都返回再继续**；不许 fire-and-forget（遵守
  `rules/common/agents.md` 的 Delegation Completion Contract）。
- 特别让 `java-reviewer` 核对：
  - `isHorizontalBurst` 是否只在 `charsMoved >= 0` 且 `sameLine` 时成立；
  - `charsMoved < 0` 的兜底分支是否保持原行为；
  - `lastShortJump` 是否同时记住「短跳」与「同行 burst」；
  - 真跨行公式是否与 Step C 逐字一致；
  - 是否还有探针把「同行 4..6 = 跨行」写死。

**Step 4 Verify = `verification-loop` + `delivery-gate`**
- 全量门禁（**只跑一份**，`-Repo` 用正斜杠）；D0；编译；打包 0.9.132；安装；实机。
- 安装用 `build/install.ps1 -Version 0.9.132 -NoStart`；装完先核对
  `alwaysHighFps`、`stretchRatioK`、`animationLength`、`animPresetBase`、`trailSize`
  是否被脚本意外改写（以用户实际 XML 为准）。

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
 M docs/INDEX.md
?? build/night-run-report.md
?? deepseekworkneovim-cursor-pr/
?? docs/archive/decisions/DECISION-REPORT-2026-11-03.md
?? docs/archive/prompts/PROMPT-STEP-C-2026-11-03.md
?? docs/archive/prompts/PROMPT-STEP-B-2026-11-04.md   （本提示词自身；不算意外）
```

- `docs/INDEX.md` 的未提交改动不是本任务的，**不要 stage/commit**。
- `docs/archive/reports/STEP-C-FIX-REPORT-2026-11-04.md` 已随 `cda7141` 提交；若你的工作区看不到它，
  先确认 HEAD 是否是 `cda7141` 或其后代。
- 出现其他未跟踪/已修改文件，先停下报告。

```
git branch --show-current        # 预期 fix/bug-016-completion-flicker
git log -1 --oneline             # 预期 cda7141 或其后代
```

已装版本（读 jar 内部，不信文件名）：

```
unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" META-INF/plugin.xml | grep -o "<version>[^<]*"
```

预期 `<version>0.9.131`。

探针数量（开工时自己跑，**不要抄 CLAUDE.md 的旧数字**）：

```
ls tools/settings-probe/V*.java | wc -l        # 2026-11-04 实测 118
ls tools/settings-probe/*.java | wc -l         # 2026-11-04 实测 133
```

注册探针数（`$mains` 去重）：

```
awk '/\$mains *= *@\(/{f=1} f{print} f&&/^\)/{exit}' tools/settings-probe/build-v-probes.ps1 \
  | grep -o "'V[A-Za-z0-9]*'" | tr -d "'" | sort -u | wc -l   # 2026-11-04 实测 115
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

**B-AC-1 同行 burst 走短跳（核心）**
同一行内 `charsMoved=4..10`：四角时长必须逐位等于
`shortAnimationLength × trailFactors[rank]`；不再掉进跨行分支。
「同一个按键，这次有拖尾、下次没有」必须消失。

**B-AC-2 真跨行密度不能被改回去**
真跨行（换行 / 上下跳 / 鼠标纵向拖拽）保持 Step C 的公式：
`leading = animationLength × (1 - trailSize)`，`trailSize=1.0` → 0；
`rank0 = animationLength`。三个预设的跨行密度与 0.9.131 一致。

**B-AC-3 单行 1..3 字符不变**
同一行 `charsMoved=1..3` 仍走短跳，四角逐位等于
`shortAnimationLength × trailFactors`；单行打字手感与 0.9.131 一致。

**B-AC-4 同行大跳的已知副作用**
Ctrl+End、鼠标拖到行尾等同一条大横向移动，现在也走短跳分支；
必须记录观感变化（尾巴可能变短）。**不要临时加固定时长下限**；
如果用户觉得太短，记录操作序列并报告，等用户拍板是否加「上限兜底」。

**B-AC-5 用户实机验收**
切到 `trailSize=1.0` 的基础 / 缎带 / 纯平滑输入三个预设：
- 连打 `ffff...`、长按退格、Ctrl+Backspace：拖尾稳定，不再忽有忽无；
- 正常换行 / 上下跳：跨行密度与 0.9.131 一致；
- 再用当前 `trailSize=0.2` 的「纯平滑输入」做一次无回归对照。
没有用户确认，不许说「修好了」。

**B-AC-6 探针与门禁**
`VTypingSpan` T-2b/T-3 改方向，T-6/T-7 新增；
`VBlockStretch` B-2、`VUniform` U-3、`VAuditBatch1` A-24 按契约更新；
全量门禁 **ALL PASS**；D0 零 diff；变异测试四段齐全。

**B-AC-7 明确不修 A/C/D/E**
B 只改同行误判；A（弹窗闪烁）、D（删除延迟）、E（上游跨行模型）均不碰。
C 的 0ms 真跨行前缘不得回退。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④ 禁止话术 / 禁止做法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

- 禁止 `git reset` / 全量回退；禁止把 Step C 的 0ms 真跨行前缘改回去。
- 禁止再用「固定时长下限」修共享分支：B 的修法是拆判据，不是调时长。
- 禁止删断言、改断言迁就代码、放宽阈值让红变绿；契约变更必须写明「非缺陷」。
- 禁止把 `VBlockStretch` B-2 / `VUniform` U-3 的红当成「B 引入的回归」：
  它们断言的是旧误判行为，B 修好后必须改方向。
- 禁止改 `core/NeovideConfig.java`（D0）、`CaretAnimator`、`SpringTrail`、`install.ps1`。
- 禁止改面板文案/资源：B 不改变真跨行公式，`VHints` / `NeovideCursorConfigurable` 不该动。
- 禁止混入 A/C/D/E 的改动；禁止声称 B 修好了弹窗闪烁。
- 禁止并发跑门禁；`-Repo` 必须用正斜杠 `'D:/deepseekwork/neovim-cursor-pr'`。
- 禁止在未实机验收前说「修好了」；只能说「探针绿，待实机」。
- 禁止说「同行大跳尾巴短是 bug」——这是方案 A 的已知副作用，先记录、后拍板。
- 禁止新建探针文件或改 `build-v-probes.ps1` 注册；优先扩展 `VTypingSpan`。
  如果确实需要新探针，先停下报告。
- 禁止先 commit 再验证；禁止 push。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑤ 白名单（只许改这些；要扩先停下报告）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**直接允许：**

- `src/main/java/com/github/yyyolo7a79/neovidecursor/core/TrailCorner.java`
  - 新增 `isHorizontalBurst`：
    - 在 `charsMoved >= 0` 块内计算 `boolean sameLine = ...` 后，
      设 `isHorizontalBurst = sameLine && !isShortJump`；
    - 在块外先声明 `boolean isHorizontalBurst = false;`，
      `charsMoved < 0` 的两个兜底分支保持 false，**不改变兜底行为**。
  - 分支条件从 `if (isShortJump)` 改为 `if (isShortJump || isHorizontalBurst)`。
  - `lastShortJump` 在 `decided` 时改为 `isShortJump || isHorizontalBurst`
    （否则零尺寸兜底会把 burst 丢回跨行，`VAuditBatch1` A-24 会红）。
  - 真跨行分支公式**逐字不动**：`leading = animationLength × (1 - trailSize)`；
    不要再加 `Math.max`。
  - 更新 0.9.90 / Step C 注释块：写明「Step B 已把同行 burst 拆到短跳分支；
    真跨行保持 0ms」。

- `tools/settings-probe/VTypingSpan.java`
  - T-2b：改为「同行 4..6 走短跳，四角 = short × trailFactors」；
  - T-3：改用真跨行（`sameLine=false`），不要再用同行 `charsMoved=10`；
  - T-6（新增）：真跨行在 `trailSize=0.0/0.5/1.0` 三档下，
    `leading == animationLength × (1 - trailSize)`、`rank0 == animationLength`；
  - T-7（新增）：同行 `charsMoved=4..10`，四角逐位等于
    `shortAnimationLength × trailFactors`；**至少覆盖 slope=0 与 slope=100 两组**。
    **不要**断言 `rank0 <= animationLength`：slope=100 时 short rank0=110ms
    > 基础 `animationLength=100ms`，会写出假红。若需上界，用
    `max(animationLength, shortAnimationLength)`。
  - T-1/T-2a/T-4/T-5 保留。
  - 更新头部契约描述与横幅，删除「Step B 另修」的过期文字。

- `tools/settings-probe/VBlockStretch.java`
  - B-2 从「修前确实用了更长的 animationLength」改为
    「同一行 `charsMoved=50` 现在与单行短跳一致」；
    保留 B-2 编号与断言数量，注释写明「Step B 契约变更」。

- `tools/settings-probe/VUniform.java`
  - U-3 的 `cLong` 改为真跨行（纵向 `dy = h`），继续证明「两个分支位移不同」；
    如果要覆盖同行 burst，**新增**一条断言（如 U-4），不要改 U-3 的原意。

- `tools/settings-probe/VAuditBatch1.java`
  - 优先不改断言：通过 `lastShortJump` 包含 burst 让 A-24 保持绿；
  - 只改 A-24 的注释/标签「上一帧是 burst（同行大字移）或跨行」；
  - 如果 A-24 仍红，才允许按新契约改断言方向，并标注「Step B 契约变更」。

- `docs/BUG-LOG.md`
- `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md`
- `docs/archive/reports/STEP-B-FIX-REPORT-2026-11-04.md`（新建交付报告；可选，但建议有）

**禁止修改：**

- `core/NeovideConfig.java`（D0）
- `ui/CaretAnimator.java`、`ui/SpringTrail.java`、`build/install.ps1`
- `ui/settings/NeovideCursorConfigurable.java`、两语言资源文件、`VHints.java`
  （B 不改面板；若它们意外变红，先停下报告）
- `VTextFacts.java`、`VPreviewBranch.java`、`VMultiCursorFix.java` 等其他探针
  （它们只测真跨行/短跳；若意外变红，先停下报告）
- `settings/SettingsField.java`、`settings/CursorPresets.java`
- `docs/INDEX.md`（未提交改动不是本任务的）
- `build-v-probes.ps1`（不新增探针；优先扩展 VTypingSpan）
- 任何 worktree / 主仓库之外的路径

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑥ 后续计划（不在本次实施）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

- **E（跨行上游模型实验）必须在 B 之后**：
  - 只改 `!sameLine`，单行完全不动；
  - `CROSS_TRAIL_FACTORS = {1.0, 0.9, 0.5, 0.3}`；
  - `alignment > 0.5` → 0，否则 `animationLength × CROSS_TRAIL_FACTORS[rank]`；
  - 跨行不再读 `trailSize`，滑块对跨行暂时失效；
  - **临时构建，不 commit、不打 tag**；go/no-go 后再决定是否正式化。
  - 为什么必须等 B：如果同行 `charsMoved ≥ 4` 还掉进跨行分支，
    硬吸附会打到输入上，「输入不丝滑」会重演。
- **A（弹窗闪烁）** 可以并行，但不要改 `TrailCorner` / `VTypingSpan`；
  时间盒重新取证，不要再用「目标变化帧限速」或固定时长下限。
- **D（删除延迟）** 另一条线，只碰 `alwaysHighFps=false` 的 `wakeUp()` /
  `computeNextDelayNs`，不要动 `alwaysHighFps=true`。
- **跨 IDE / 发布** 可以并行：Gradle 版本/平台路径可配置、打正式 plugin zip、
  PyCharm 2025.2（252）+ IDEA 2026.1（261）+ 一个 2026.x 产品冒烟；
  不要与本分支改同一批文件。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑦ 交付格式（最终报告必须包含）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. 根因（一句话）+ 证据（代码行/数字，不是猜测）
2. 改动清单（每个文件改了什么；明确没有动 C/A/D/E）
3. 四段验证：红 → 绿 → 变异红 → 恢复绿；全量门禁 RESULT；D0 结果
4. 实机验收：安装版本、操作序列、用户观察结论（同行 burst / 真跨行密度）
5. 两个代理的审查结论（CRITICAL/HIGH/MEDIUM 清单与处理）
6. 分支名 + commit hash（不 push）
7. 明确写出「B 未修 A/C/D/E」以及工作区仍保留的未提交文件

【第一条回复要求】
开工后第一条消息只报：你读到的 HEAD、门禁基线 RESULT、已装版本、
探针实际数量（V*.java / 注册数），以及你准备先做的复现/取证步骤。不要先改代码。
