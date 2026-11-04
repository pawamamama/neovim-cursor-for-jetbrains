# 文档纠正 + 注释审查 合并施工提示词（2026-11-04）

> 整段复制给执行会话。自包含；新会话不需要回翻对话。
> 仓库：`D:\deepseekwork\neovim-cursor-pr`
> **基线：0.9.131**（已装 0.9.131）。不再提回退 0.9.127。
> 当前 HEAD `fddd529`（Step B 已撤回，构建 0.9.133，**行为与 0.9.131 相同**）；
> 0.9.132/0.9.133 不是新基线，但**不要回退/重装**。工作区有未提交文档改动，**不要 reset**。
> **本任务只改文档 + 注释；不改代码逻辑/常量/测试；不安装；不跑 install.ps1。**
> 文档阶段不跑门禁；注释阶段才跑全量门禁 + D0。

---

## ★ 提问规则（最高优先级，必须遵守）

这是一份**需要随时问用户**的任务。遇到下面任何情况，**立刻停下，用大白话问**，
不要自己猜、不要自己选一个继续：

1. 两份文档说法冲突（例：一份说 B 是 bug，一份说 B 未复现）。
2. 某个数字/结论无法用命令核实（例：当前预设、安装版本、trailSize）。
3. 要删除、合并、作废任何文档或提示词。
4. 涉及产品观感/取舍（例：选词斜拖尾保留还是单独做选区）。
5. 要改白名单之外的文件。
6. 注释和代码冲突，而“让注释成立”需要改代码。
7. 你不确定用户会怎么看这个变化。

**怎么问：**
- 用大白话，不用术语；一句话说清“现在有两个说法/两个选择”。
- 给 **2~3 个选项**，说明每个选项用户会看到什么。
- 给一个推荐，但不要替用户拍板。
- 例：
  - 「文档 A 说 B 是 bug，文档 B 说 B 没复现。你要以哪个为准？1）B 只是代码疑点，不再修；2）保留 B 为待修 bug。」
  - 「这一步会把两份旧提示词合并。1）保留旧文件并在文首标『已合并』；2）删除旧文件（需要你确认）。」
  - 「选词斜拖尾是 1.0 下的正常形状。1）接受它；2）单独给选区做一条观感（新功能，先不动代码）。」

**不要问实现细节**（哪个字段、哪一行、怎么迁移）；这些自己定，并写进报告。

---

## 当前真相（先写进 `docs/CURRENT-STATE-2026-11-04.md`）

| 项 | 事实 |
|---|---|
| 已装 jar | **0.9.131**（读 jar 内 `META-INF/plugin.xml` 实测） |
| 基线 | **文档/验收基线 0.9.131**；当前构建 0.9.133 行为=0.9.131；0.9.132/0.9.133 不是新基线，但不回退/重装 |
| 当前分支/HEAD | `fix/bug-016-completion-flicker` / `fddd529`（Step B 已撤回） |
| 远程 | `fork/main...HEAD = 0/7`（未 push） |
| 当前预设 | 面板当前选中「基础测试2」，`trailSize=1.0`；**1.0 是当前值也是期望值** |
| `trailSize=0.2` | 属于另一个预设「纯平滑输入 副本」；**不是当前值，是过期记录** |
| A（弹窗辉光闪烁） | 记录为待修；**2026-11-04 用户决定暂缓，不修**（很难告） |
| B（同行 charsMoved≥4） | **未复现的代码疑点，不是 bug**；Step B 已撤回（`fddd529`），不再修 |
| C（撤 55ms 下限） | **保留**；真跨行前缘回到 0ms，密度最高；用户已验收 |
| D（删除延迟） | 不做；另一条线 |
| E（上游跨行模型） | 不做；B 已撤回，前提不成立 |
| 选词斜拖尾 | `trailSize=1.0` 下跨行分支的正常形状：前缘 0ms、后缘 100/280ms；0.9.131 一样。**不是配置重置，不是 bug** |

**当前阶段目标**：先把文档/注释整理到可开工状态；下一步只做「跨行密度」实验，
看能否改善跨行斜拖尾的观感；A/B/C/D/E 的代码都不动。其余（选区分类、tab 缩进、
预设更新）只是附加，等主目标完成后再排。

---

## ① 工作流（一个会话，两个阶段）

### 阶段 1：文档纠正（先做，不碰代码/注释）

1. **Step 0 读现状**：
   - `git status --porcelain`、`git log -5 --oneline`、分支/远程；
   - 读当前 `docs/BUG-LOG.md`、`docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md`、
     `docs/archive/reports/STEP-C-FIX-REPORT-2026-11-04.md`、`docs/archive/reports/STEP-B-FIX-REPORT-2026-11-04.md`、
     `docs/archive/reports/STEP-B-REVERT-REPORT-2026-11-04.md`、
     `docs/archive/decisions/DECISION-REPORT-2026-11-03.md`、`docs/archive/decisions/DECISION-REPORT-2026-11-04-STEP-B.md`、
     `CLAUDE.md`、`docs/INDEX.md`、现有提示词。
   - **不要 reset、不要 checkout、不要 clean**；工作区改动是其他会话留下的证据。
2. **Step 1 列清单**：`planner` 代理出「要改哪些文档、每处为什么、哪些保留」；
   遇到冲突/删除/合并，先按「提问规则」问用户。
3. **Step 2 写真相页**：新建 `docs/CURRENT-STATE-2026-11-04.md`，
   用上面的「当前真相」表 + 一段大白话总结（用户 2 分钟能看懂）。
4. **Step 3 追加更正**（只追加，不删历史）：
   - `docs/BUG-LOG.md` §BUG-016：
     - 当前预设是「基础测试2」`trailSize=1.0`；`0.2` 是「纯平滑输入 副本」的旧值；
     - B 降级为「未复现的代码疑点」，Step B 已撤回；C 保留、已验收；
     - A 仍未修但暂缓；D/E 不做；选词斜拖尾是 1.0 下正常形状。
   - `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md`：追加「2026-11-04 更正」，
     说明 Step B 撤回、C 保留、A 未修、0.2 误读。
   - `docs/archive/reports/STEP-C-FIX-REPORT-2026-11-04.md`：把「当前 trailSize=0.2」标为误读；
     写明当前/期望都是 1.0，55ms 下限在当前预设下确实生效，Step C 撤回后 55→0。
   - `docs/archive/reports/STEP-B-FIX-REPORT-2026-11-04.md`：文首标「已撤回；
     用户从未感受到该症状；不作为基线」。
   - `docs/archive/reports/STEP-B-REVERT-REPORT-2026-11-04.md`：保留；补一句「0.9.133 行为=0.9.131」。
   - `docs/archive/decisions/DECISION-REPORT-2026-11-03.md` / `docs/archive/decisions/DECISION-REPORT-2026-11-04-STEP-B.md`：
     追加「2026-11-04 更正」；把「已装 0.9.133」改成实测 0.9.131；
     0.9.132/0.9.133 标中间版。
   - `docs/archive/prompts/PROMPT-STEP-B-2026-11-04.md`：文首标「已作废（B 未复现，Step B 已撤回）」。
   - `docs/archive/prompts/PROMPT-STEP-C-2026-11-03.md`：文首标「已验收、保留；C 不下调」。
   - `docs/archive/prompts/PROMPT-BUG-016-2026-11-03.md`：B/E 段标「停止」；A 段保留并指向 A 提示词。
   - `docs/archive/prompts/PROMPT-A-POPUP-FLICKER-2026-11-04.md`：
     只修正「当前预设/trailSize」描述为「基础测试2 / 1.0」；A 的方案不动。
   - `docs/archive/prompts/PROMPT-DOC-CORRECTION-2026-11-04.md`、`docs/archive/prompts/PROMPT-COMMENT-AUDIT-2026-11-04.md`：
     文首标「已合并到本文（PROMPT-DOC-COMMENT-COMBINED-2026-11-04.md）」；
     **是否删除先问用户**。
   - `CLAUDE.md` / `docs/INDEX.md`：更新状态数字（探针数、jar 版本、HEAD、远程），
     指向 `CURRENT-STATE-2026-11-04.md`；**数字必须实测**，不抄旧数。
5. **Step 4 自查**：`git diff --stat` 只应包含文档；读一遍真相页，确认没有互相矛盾。

### 阶段 2：注释审查（同一会话，文档完成后再做）

1. **先问用户**：是否现在继续注释审查；如果用户说“先看文档”，就停在这里。
2. **开代理**：`code-reviewer` + `java-reviewer`，把注释清单和 `git diff` 交给它们；
   等两个代理都返回再继续（Delegation Completion Contract）。
3. **只改注释/javadoc**：不改逻辑、常量、测试、资源、构建脚本。
4. **重点 grep**：
   ```
   grep -rnE "MAX_DT|1/30|33\.3|55 ?ms|shortAnimationLength \* 0\.5|Math\.max\(leading" src/main/java tools/settings-probe
   grep -rnE "charsMoved ?>= ?4|charsMoved ?≥ ?4|误判|误切" src/main/java tools/settings-probe
   grep -rnE "leadingSnap|useHardSnap|半边拖尾|前缘贴合" src/main/java tools/settings-probe
   grep -rnE "crossMoveFloor|floorLeading|floorNote|已触底|at the floor" src/main/java tools/settings-probe
   grep -rnE "0\.4\.3|0\.9\.130|0\.9\.132|0\.9\.133|Step B|Step C" src/main/java tools/settings-probe
   ```
5. **每处改动写**：
   - 原注释为什么过期（代码行/测量/用户确认）；
   - 新注释依据；
   - 历史理由保留，追加「2026-11-04 更正」。
6. **注释与代码冲突时**：不要为了注释去改代码；先按「提问规则」问用户，
   是标注「历史/已撤回」还是另开任务。

---

## ② 前置检查（数字自己跑，不许抄）

```
git status --porcelain
git log -3 --oneline
git branch --show-current
git rev-list --left-right --count fork/main...HEAD
```

预期：HEAD `fddd529` 或其后代；远程 `0/7`；工作区有文档改动/未跟踪文件；
**不要 reset**。

已装版本：

```
unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" META-INF/plugin.xml | grep -o "<version>[^<]*"
```

预期 `<version>0.9.131`；如果不同，先按「提问规则」问用户，不要自己回退/安装。

探针数量（开工时自己跑）：

```
ls tools/settings-probe/V*.java | wc -l        # 2026-11-04 实测 118
ls tools/settings-probe/*.java | wc -l         # 2026-11-04 实测 133
```

注册探针数：

```
awk '/\$mains *= *@\(/{f=1} f{print} f&&/^\)/{exit}' tools/settings-probe/build-v-probes.ps1 \
  | grep -o "'V[A-Za-z0-9]*'" | tr -d "'" | sort -u | wc -l   # 实测 115
```

注释阶段开始前，先记录 diff 基线：

```
New-Item -ItemType Directory -Force -Path build\comment-audit | Out-Null
git diff > build\comment-audit\before.patch
```

门禁基线（**只在注释阶段跑，只跑一份，`-Repo` 用正斜杠**）：

```
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo 'D:/deepseekwork/neovim-cursor-pr'
```

D0：

```
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

必须零 diff。

---

## ③ AC（可观察结果）

**AC-1 用户能看懂**
`docs/CURRENT-STATE-2026-11-04.md` 存在；用户读 2 分钟能说清：
装的是 0.9.131、trailSize=1.0、A 暂缓不修、B 已撤、C 保留、D/E 不做；
下一步只做跨行密度实验。

**AC-2 旧记录不再误导**
所有 `trailSize=0.2` 都标成「纯平滑输入 副本 / 历史」；
所有「C 对 0.2 无收益」「回退 0.9.127」「已装 0.9.133」等过期说法都有更正；
原文保留，未被删除。

**AC-3 旧提示词已归位**
Step B 提示词标「已作废」；Step C 提示词标「已验收、保留」；
文档/注释两份旧提示词标「已合并」；是否删除由用户确认。

**AC-4 状态数字实测**
`CLAUDE.md` / `INDEX` 的探针数、jar 版本、HEAD、远程均来自本轮命令；
0.9.131 是基线；0.9.132/0.9.133 标中间版。

**AC-5 注释只改注释**
`git diff` 新增/删除行只允许注释行；Step B 代码在 HEAD 中，
本次 diff 不应出现 Step B 代码 hunk；全量门禁 ALL PASS；D0 零 diff。

**AC-6 没有执行/安装/重置**
没有安装 jar、没有跑 `install.ps1`、没有 reset、没有启动 IDEA。

---

## ④ 禁止话术 / 禁止做法

- 禁止在文档冲突时自己选一个继续；必须按「提问规则」问用户。
- 禁止删除文档/历史；只能追加「2026-11-04 更正」。
- 禁止删除/合并旧提示词而不问用户。
- 禁止把 B 写成 bug、把 C 写成无收益、把 0.2 写成当前值、把 0.9.133 写成基线。
- 禁止改代码逻辑/常量/测试/资源/构建脚本；注释阶段只改注释。
- 禁止为让注释成立去改代码；冲突先问用户。
- 禁止 `git reset` / `checkout` / `clean`。
- 禁止安装 jar、跑 `install.ps1`、改用户配置、启动 IDEA。
- 禁止 commit/push，除非用户明确要求。
- 禁止说「门禁绿所以文档对了」；文档的 AC 是内容一致 + 数字实测。

---

## ⑤ 白名单

**文档（阶段 1）：**

- `docs/CURRENT-STATE-2026-11-04.md`（新建）
- `docs/BUG-LOG.md`
- `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md`
- `docs/archive/reports/STEP-C-FIX-REPORT-2026-11-04.md`
- `docs/archive/reports/STEP-B-FIX-REPORT-2026-11-04.md`
- `docs/archive/reports/STEP-B-REVERT-REPORT-2026-11-04.md`
- `docs/archive/decisions/DECISION-REPORT-2026-11-03.md`
- `docs/archive/decisions/DECISION-REPORT-2026-11-04-STEP-B.md`
- `docs/archive/prompts/PROMPT-STEP-B-2026-11-04.md`
- `docs/archive/prompts/PROMPT-STEP-C-2026-11-03.md`
- `docs/archive/prompts/PROMPT-BUG-016-2026-11-03.md`
- `docs/archive/prompts/PROMPT-A-POPUP-FLICKER-2026-11-04.md`（只改当前预设/trailSize 描述）
- `docs/archive/prompts/PROMPT-DOC-CORRECTION-2026-11-04.md`（只加「已合并」标注）
- `docs/archive/prompts/PROMPT-COMMENT-AUDIT-2026-11-04.md`（只加「已合并」标注）
- `CLAUDE.md`
- `docs/INDEX.md`

**注释（阶段 2，仅注释/javadoc）：**

- `src/main/java/**/*.java` 中命中 grep 的文件，优先：
  `ui/CaretAnimator.java`、`settings/NeovideCursorSettings.java`、
  `ui/settings/NeovideCursorConfigurable.java`、`ui/settings/AnimationPreview.java`、
  `core/TrailCorner.java`
- `tools/settings-probe/*.java` 中命中 grep 的文件，优先：
  `VTypingSpan.java`、`VTextFacts.java`、`VHints.java`、`VLeadingSnap.java`
- `tools/settings-probe/build-v-probes.ps1`（仅注释）
- `docs/COMMENT-AUDIT-2026-11-04.md`（新建审查报告）

**禁止：**

- 代码逻辑/常量/断言/测试/资源/构建脚本
- `core/NeovideConfig.java`（D0）
- `build/install.ps1`、`gradle.properties`、`build.gradle.kts`
- 用户配置 XML
- 任何 worktree / 主仓库之外的路径

---

## ⑥ 交付格式

1. **先问**：开工后第一条消息只报「当前状态 + 你准备先改哪些文档 + 需要用户拍板的冲突」；
   不要先改任何文件。
2. 文档阶段：改了哪些文档、每处为什么、原文是否保留；
   `git diff --stat` 证明只改文档。
3. 注释阶段：注释清单、`git diff` 证明只改注释、全量门禁 RESULT、D0。
4. 两个代理的审查结论。
5. 明确写出：A/B/C/D/E 状态未变；已装仍 0.9.131；没有安装、没有 reset。

**停止线：文档 + 注释完成就停；不改 A/B/C/D/E 代码，不再加滑块。
A 暂缓，不修复；跨行密度实验是下一份提示词，不在本任务里。**
