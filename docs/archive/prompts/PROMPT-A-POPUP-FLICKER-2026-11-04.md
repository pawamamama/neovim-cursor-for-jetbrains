# A 修复施工提示词：补全弹窗打开时辉光闪烁 / 短跳变短（2026-11-04）

> 整段复制给执行会话。自包含；新会话不需要回翻对话。
> 仓库：`D:\deepseekwork\neovim-cursor-pr`
> 基线：**已装 0.9.131**（`79acfd3` 的构建；仓库没有 `v0.9.131` tag，用 jar 内版本核实）。
> 从当前分支新建分支 `fix/a-popup-flicker`。
> **现有 `fix/bug-016-completion-flicker` 工作区保留不动**（它是 B/C 的证据，不要 reset、不要在里面改 A）。
> 前置：已装实测 **0.9.131**；如果 jar 内版本不是它，停下报告，
> 不要自己安装/回退——先与用户确认。
> 历史说明：旧版本曾把基线写成 `v0.9.127` 并要求回退到它；2026-11-04 更正为 0.9.131，
> **不再回退 0.9.127**。
> 记录：`docs/BUG-LOG.md §BUG-016`（A 部分）、`docs/archive/prompts/PROMPT-BUG-016-2026-11-03.md`（A 取证部分）、
> `docs/archive/decisions/DECISION-REPORT-2026-11-03.md` §1.1

---

【任务】
只修 A：代码补全候选弹窗（`private` 自动候选 / Tab 补全）打开时，
拖尾动画「一闪一闪」、辉光淡入淡出像突然消失、短跳动画变短。
目标是让弹窗打开时的打字手感与关闭时一致。

**不修 B / C / E / D**：
- B（同行 `charsMoved ≥ 4`）是**未复现的代码疑点**，不是 bug；50+ 版本 / 8 天未出现，已停止。
- C（真跨行前缘 0 ms）**保留、已验收**；`aeff707` 的 55ms 下限已由 Step C 撤回，
  **不要把下限加回去**。0.9.132 / 0.9.133 是中间版，不作为基线。
- E（上游跨行模型实验）必须在 B 之后；B 已停止，E 不启动。
- D（删除延迟 / `alwaysHighFps=false`）另一条线，不碰。

【已知环境】
- Windows 11，IDEA 2026.1（build 261），已装插件 **0.9.131**（读 jar 内
  `META-INF/plugin.xml` 实测，不认文件名）。
- 用户当前观感：面板选中「基础测试2」（派生自纯平滑输入），`trailSize=1.0`，
  `slopeTicks=100`。`trailSize=1.0` 既是当前值也是期望值（密度最高，不要调低）。
- 0.9.131 基线探针：`118` 个 `V*.java` / `133` 个 `.java` / `115` 个注册探针
  （2026-11-04 实测；开工时再跑一遍，以实际为准）。
- 诊断日志本身会拖慢 EDT（历史出现过 `cost=57ms`）。**判断手感必须关诊断**；
  任何手感结论必须标注诊断开关状态、jar 内嵌版本、预设实际字段、电源状态。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
① 工作流（`development-workflow.md` 五步；第一步按 bug 修正执行「复现 + 证据」）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**Step 0 Research = 复现 + 证据（先不改生产代码）**
- 读：`docs/BUG-LOG.md §BUG-016` 的现象、假设 H1-H4、取证步骤；
  `docs/archive/prompts/PROMPT-BUG-016-2026-11-03.md` 的 A 取证部分；
  `docs/archive/decisions/DECISION-REPORT-2026-11-03.md` §1.1（3162 帧的 `rawDt` / `dispatchLag` 数据）。
- 在 **0.9.131** 基线上复现：
  1. **先关诊断**，用肉眼/录屏做一次「弹窗开 vs 关」的对照，确认 A 仍存在。
  2. 再开诊断，重复同样序列，收集 `[tick]` 的
     `rawDt / dt / recovery / dispatchLag / cost / note`，
     `[evt]`、`[paint]`、`[clear]`。
  3. 对比：弹窗开/关的 `rawDt`、`dispatchLag`、`delayMs`、`measuredFps` 的 P50/P95/max；
     `note=not-showing`、`[clear]` 原因是否出现。
- 待验证假设（先取证，不许直接选一个改）：
  - H1：弹窗期间 EDT 交付间隔变大，落在 20~100ms 的「中间地带」，
    `recovery` 不触发，`dt` 被放大；短跳弹簧一帧走完，辉光一帧掉一大截。
  - H2：弹窗触发额外 caret 事件 / `forceResync` / `lastMoveNanos` 重置。
  - H3：弹窗/重绘导致覆盖层被清空或降频（`isShowing` / hidden tier / adaptive delay）。
  - H4：弹窗绘制与覆盖层重绘冲突（Swing 子组件层级 / 裁剪）。
- **禁止重试已否决的 attempt-1**：目标变化帧限速（`max(8ms, smoothedInterval)`）
  在 0.9.128 实机更差，已整体回退；`dt` 不是这个 bug 的可修点。
- 产出：一张「弹窗开 vs 关」的对比表，写进 `docs/BUG-LOG.md §BUG-016`；
  证据不足就停下报告，不要先改代码。

**Step 1 Plan = `planner` 代理**
- 输入：Step 0 对比表 + 代码事实；输出：根因（一句话）、候选修法（至少 2 个）、
  否决理由、风险、探针计划。
- 不允许用「固定时长下限」修共享分支；不允许用「降低帧率/关闭辉光/改默认颜色」掩盖。

**Step 2 TDD = `tdd-workflow` 技能**
- 先写失败探针（建议新建 `tools/settings-probe/VLookupFlicker.java`，
  或扩展 `VFadeTick` / `VFrameRate`）；断言的是可观察契约，不是复刻实现。
- 探针必须在修复前代码上红；修复后绿；再做变异测试（改回旧行为必须红）。
- 全量门禁只跑一份；改完删 `build\v2-probe\classes` 全量重编译。

**Step 3 Review = `code-reviewer` + `java-reviewer`（硬性，等结果）**
- 把 `git diff` 完整交给两个代理；要求 CRITICAL / HIGH / MEDIUM 清单。
- CRITICAL/HIGH 必须修；MEDIUM 要么修，要么写清不修理由。
- **等两个代理都返回再继续**；不许 fire-and-forget（遵守
  `rules/common/agents.md` 的 Delegation Completion Contract）。
- 特别让 `java-reviewer` 核对：探针是否调用生产方法、是否复刻实现；
  修法是否只影响弹窗场景、是否误伤无弹窗路径。

**Step 4 Verify = `verification-loop` + `delivery-gate`**
- 全量门禁（**只跑一份**，`-Repo` 用正斜杠）；D0；编译；构建候选 jar
  （建议 0.9.134；0.9.132 / 0.9.133 已被 Step B 占用）。
- **先不要安装候选 jar**：保持已装 0.9.131，等用户同意后再装。
- 实机验收必须关诊断；记录 jar 内嵌版本、预设实际字段、电源状态。

**Step 5 Commit**
- 在 `fix/a-popup-flicker` 分支本地 commit，**不要 push**。
- 等用户实机验收；用户确认后再合并/推送。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
② 前置检查（数字自己跑，不许抄上一份文档）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

在 **0.9.131** 的新工作区执行：

```
git status --porcelain        # 期望干净；主仓库的脏工作区不要碰
git log -1 --oneline          # 期望与 79acfd3（Step C 撤回提交）同源或其后代
git describe --tags --exact-match   # 0.9.131 没有 tag（tag 停在 v0.9.127），
                                    # 以 jar 内 <version> 为准
```

已装版本（读 jar 内部，不信文件名）：

```
unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" META-INF/plugin.xml | grep -o "<version>[^<]*"
```

**必须是 `<version>0.9.131`**；如果不同，停下报告，先与用户确认，不要自行回退/安装。

探针数量（开工时自己跑，**不要抄 CLAUDE.md 的旧数字**）：

```
ls tools/settings-probe/V*.java | wc -l        # 0.9.131 实测 118
ls tools/settings-probe/*.java | wc -l         # 0.9.131 实测 133
```

注册探针数（`$mains` 去重）：

```
awk '/\$mains *= *@\(/{f=1} f{print} f&&/^\)/{exit}' tools/settings-probe/build-v-probes.ps1 \
  | grep -o "'V[A-Za-z0-9]*'" | tr -d "'" | sort -u | wc -l   # 0.9.131 实测 115
```

门禁基线（**只跑一次，严禁并发**；并发会导致 ClassNotFoundException 假红）：

```
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo 'D:/deepseekwork/neovim-cursor-pr-a'
```

`-Repo` 改成你的新工作区路径，**必须用正斜杠**。记录最后一行 `RESULT`；基线预期 ALL PASS。

D0：

```
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

必须零 diff。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
③ AC（可观察行为，不是「门禁绿」）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**A-AC-1 主症状**
`private` 候选弹窗打开时，连续输入/删除 20 次，拖尾连续，
无肉眼可见的「闪一下/跳一下」；辉光不出现「突然熄灭再亮」。

**A-AC-2 对照一致性**
同一段按键序列，弹窗开/关两种状态下，单字符短跳的拖尾可见时长一致
（录屏逐帧对比，差异不超过 1 帧）；不能出现「弹窗开时一帧到位」。

**A-AC-3 短跳形变**
弹窗打开时，单字符短跳仍有可辨认的拉伸/斜度；四角 rank 梯度还在
（后缘比前缘慢），不是四角同速平移。

**A-AC-4 辉光**
弹窗打开时，辉光淡入淡出与关闭时一致；
单帧亮度降幅不超过「关闭时同帧降幅」的 1.5 倍。

**A-AC-5 无回归**
弹窗关闭、跨行跳转、删除、多光标、IME、不可见编辑器降频行为不变；
0.9.131 的既有单行/跨行手感不变。

**A-AC-6 探针与门禁**
新增/扩展探针在修复前代码上必须红；四段验证
「红 → 绿 → 变异红 → 恢复绿」都要有记录；全量门禁 ALL PASS；D0 零 diff。

**A-AC-7 用户实机验收**
用户在 IDEA 2026.1 实机验收，弹窗打开时打字手感与关闭时一致。
没有用户确认，不许说「修好了」。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④ 禁止话术 / 禁止做法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

- 禁止在没有 `[tick]` / `[edt]` 证据前说「应该是 EDT 卡顿」「应该是补全弹窗」。
- 禁止重试 attempt-1「目标变化帧限速」；它已被实机否决。
- 禁止用「固定时长下限」修共享分支；禁止改 B/C/E/D。
- 禁止用「降低帧率」「关闭辉光」「改颜色默认值」「关闭补全弹窗」掩盖症状。
- 禁止说「上游也这样」「平台限制，改不了」——除非有字节码/日志证据，
  并给出插件侧可观察的缓解方案。
- 禁止删断言、改断言迁就代码、放宽阈值让红变绿；契约变更必须写明「非缺陷」。
- 禁止改 `core/NeovideConfig.java`（D0）、B/C/E/D 相关文件、面板/资源/预设。
- 禁止并发跑门禁；`-Repo` 必须用正斜杠。
- 禁止在未实机验收前说「修好了」；只能说「探针绿，待实机」。
- 禁止把诊断开着做手感判断；诊断本身会拖慢 EDT。
- 禁止先 commit 再验证；禁止 push。
- 禁止安装候选 jar 到用户机器，除非用户明确同意；已装保持 0.9.131。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑤ 白名单（只许改这些；要扩先停下报告）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**直接允许：**

- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java`
- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/RenderScheduler.java`
- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretTrailPanel.java`
- `src/main/java/com/github/yyyolo7a79/neovidecursor/core/GlowIntensity.java`
- `src/main/java/com/github/yyyolo7a79/neovidecursor/core/DampedSpring.java`
- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CursorDiag.java`
  （仅新增诊断字段/门限；临时部分在最终提交前还原）
- `tools/settings-probe/VLookupFlicker.java`（新建，如需要）
- `tools/settings-probe/build-v-probes.ps1`（只加注册行）
- `docs/BUG-LOG.md`（只回填 A 的证据与结论）
- `docs/A-FIX-REPORT-2026-11-04.md`（新建交付报告；建议有）

**需先报告、经用户同意才能动：**

- `core/TrailCorner.java`
- `ui/SpringTrail.java`
- `settings/NeovideCursorSettings.java`
- `ui/settings/AnimationPreview.java`

这些文件属于 B/C/动画时长区域；如果证据指向它们，先停下报告，
不要在 A 的提示词里顺手改 B/C。

**禁止修改：**

- `core/NeovideConfig.java`（D0）
- `ui/settings/NeovideCursorConfigurable.java`、两语言资源文件、预设表
- `build/install.ps1`、`gradle.properties`、`build.gradle.kts`
- `tools/settings-probe/VTypingSpan.java`、`VBlockStretch.java`、`VUniform.java`、
  `VAuditBatch1.java`（B 已停止，不再改这些）
- 现有 `fix/bug-016-completion-flicker` 工作区（保留为证据，不要 reset/checkout）
- 任何 worktree / 主仓库之外的路径

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑥ 交付格式（最终报告必须包含）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. 根因（一句话）+ 证据（日志行/数字，不是猜测）
2. 改法（为什么选它；否决了哪些候选；明确没有动 B/C/E/D）
3. 四段验证：红 → 绿 → 变异红 → 恢复绿；全量门禁 RESULT；D0 结果
4. 实机验收：jar 内嵌版本、操作序列、用户观察结论、诊断开关状态
5. 两个代理的审查结论（CRITICAL/HIGH/MEDIUM 清单与处理）
6. 分支名 + commit hash（不 push）
7. 明确写出「A 未修 B/C/E/D」以及已装 jar 仍是 0.9.131

【第一条回复要求】
开工后第一条消息只报：你读到的基线 tag、门禁基线 RESULT、已装版本、
探针实际数量（V*.java / 注册数），以及你准备先做的复现/取证步骤。不要先改代码。

【停止线】
A 修完或时间盒到期就停；不再做 B/C/E/D，不再加滑块，不再为「感觉还能更好」继续改。
