# 文档清理 / 查漏补缺施工提示词（2026-11-04）

> **【2026-11-04 已合并】** 本文已合并到
> `docs/archive/prompts/PROMPT-DOC-COMMENT-COMBINED-2026-11-04.md`，**以合并版为准**。
> 本文保留作历史，不删除。注意：文中「回退 0.9.127 / C 链停止」等说法已被合并版更正
> （基线是 **0.9.131**，C **保留、已验收**，0.9.132/0.9.133 是中间版）。
>
> 整段复制给执行会话。自包含；新会话不需要回翻对话。
> 仓库：`D:\deepseekwork\neovim-cursor-pr`
> **只改文档 + 回退已装 jar；不改代码、不 reset、不跑门禁、不安装新 jar。**
> 现有 `fix/bug-016-completion-flicker` 工作区（含 Step B 改动）是**证据，保留不动**。

---

【任务】
1. 把 IDEA 已装 jar 从 **0.9.132** 回退到 **0.9.127**（用户确认能用的版本）。
2. 纠正文档：B/C/E 链停止，A 保留为唯一近期 bug；0.9.132 / Step B 标注无效。
3. 更新 `CLAUDE.md` / `docs/INDEX.md` 的状态数字，必须实测，不许抄旧数。
4. 保留原文，追加「2026-11-04 更正」节；不删历史。

【当前实测状态（2026-11-04，开工时再核一遍）】
- HEAD：`5de3b37 fix(trail): 同行 charsMoved>=4 拆出 burst 分支（Step B）`
- 分支：`fix/bug-016-completion-flicker`；`fork/main...HEAD = 0/6`（未 push）
- 已装 jar：**0.9.132**（不是 0.9.127）
- 工作区：`M docs/INDEX.md`；未跟踪 `DECISION-REPORT`、`PROMPT-STEP-B/C` 等；
  Step B 的代码/探针改动**已在 HEAD `5de3b37` 中**，工作区没有未提交的代码改动
- `dist/neovide-cursor-0.9.127.jar` 存在（246973 bytes，2026-11-02）
- 0.9.127 基线探针：117 个 `V*.java` / 132 个 `.java` / 114 个注册（实测）
- 当前工作区探针：118 / 133 / 115（含 Step B 的 `VTypingSpan` 等；不是发布基线）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
① 工作流（文档任务；不适用 tdd-workflow / java-reviewer）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

- **Step 0 Research**：核实 git/已装 jar/dist；读现有文档；列出要改的章节与数字。
- **Step 1 Plan**：`planner` 代理出「改哪些文档、每处为什么、哪些不删」的清单。
- **Step 2 Edit**：按清单改文档 + 回退 jar；**不碰代码/探针/资源**。
- **Step 3 Review**：`code-reviewer` 审文档一致性（版本号、事实、链接）；
  没有代码改动，不跑 `java-reviewer`。
- **Step 4 Verify**：`verification-loop` 的「数字自检」部分：
  重跑探针计数、jar 内嵌版本、`wc -l`；不跑全量门禁（没有代码改动）。
- **Step 5 Commit**：本地修改文档，**不 commit/push**，除非用户明确要求。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
② 前置检查（数字自己跑）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

```
git status --porcelain
git log -2 --oneline
git branch --show-current
git rev-list --left-right --count fork/main...HEAD
```

预期：分支 `fix/bug-016-completion-flicker`，HEAD `5de3b37` 或其后代；
Step B 改动已在 HEAD 中，工作区只有 `M docs/INDEX.md` 与未跟踪文档；
**不要 reset/checkout/clean**。

已装版本：

```
unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" META-INF/plugin.xml | grep -o "<version>[^<]*"
```

当前应为 `0.9.132`，目标是 `0.9.127`。

```
ls -l dist/neovide-cursor-0.9.127.jar
ls -d "D:/deepseekwork/_config-backup"
```

`dist/neovide-cursor-0.9.127.jar` 必须存在；不存在就停下报告。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
③ 回退已装 jar（手动覆盖，不要用 install.ps1）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**为什么不能用 `install.ps1 -Version 0.9.127`**：它会从**当前源码**重新打包，
当前源码是 Step B（0.9.132）的代码，打出来的“0.9.127”不是真正的 0.9.127。
必须复制 `dist/neovide-cursor-0.9.127.jar` 这个已验证的产物。

步骤：

1. **先让用户手动关闭 IDEA**；不要强杀进程。
   ```
   Get-Process idea64 -ErrorAction SilentlyContinue
   ```
   如果还在运行，停下，请用户关闭后再继续。
2. 备份当前 0.9.132：
   ```
   $installed = "$env:APPDATA\JetBrains\IntelliJIdea2026.1\plugins\neovide-cursor-pawamamama\lib\jetbrains-neovide-cursor-0.9.8.jar"
   $backup = "D:\deepseekwork\_config-backup\neovide-cursor-0.9.132-$(Get-Date -Format yyyyMMdd-HHmmss).jar"
   Copy-Item -LiteralPath $installed -Destination $backup
   ```
3. 覆盖为 0.9.127：
   ```
   Copy-Item -LiteralPath "D:\deepseekwork\neovim-cursor-pr\dist\neovide-cursor-0.9.127.jar" -Destination $installed -Force
   ```
4. 验证 jar 内版本：
   ```
   unzip -p "$installed" META-INF/plugin.xml | grep -o "<version>[^<]*"
   ```
   必须是 `<version>0.9.127`。
5. 在报告中记录：备份路径、覆盖前后版本、IDEA 关闭时间。
6. **不要**运行 `install.ps1`；**不要**改用户配置；**不要**启动 IDEA。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④ 文档纠正清单（每处写「为什么改」）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**核心定性（覆盖旧计划）**：

- **A**：补全弹窗打开时辉光闪烁 / 短跳变短（2026-11-03 用户报）——
  **唯一要修的近期 bug**，仍未修。
- **B**：同行 `charsMoved ≥ 4` 误判 —— **未复现的代码疑点，不是 bug**。
  50+ 版本 / 8 天没出现；不再修、不再进待办。
- **C**：0.9.130 跨行密度回归 —— `aeff707` 为修 B 自造；Step C 撤回后
  对用户当前 `trailSize=0.2` 无收益，反而让 B 的旧行为回来；这条链停止。
- **0.9.132（Step B）**：建立在无效 B 上，**不作为基线**；已装 jar 回退 0.9.127。
- **D / 旧 BUG-005/010/014/015 / 0.9.90 之前记录**：本轮全部不做。

逐文件：

1. `docs/BUG-LOG.md`
   - 在 §BUG-016 末尾追加「### 2026-11-04 更正：B/C/E 停止，A 是唯一近期 bug」。
   - 写明：B 是未复现代码疑点，不是 bug；C 是 aeff707 自造、Step C 对 trailSize=0.2
     无收益；0.9.132/Step B 无效；A 仍未修；D 另一条线。
   - **保留** 1366 行起的 Step B 取证与 Step C 记录，只在文首/文末标注
     「已停止 / 历史证据，不作为待办」；不要删段落。
   - 修正 1196 行「根因已确认」的表述：那是 **D 的根因**，不是 A 的；
     A 仍未定位。
2. `docs/archive/decisions/DECISION-REPORT-2026-11-03.md`
   - 追加「## 2026-11-04 更正」：B/C/E 结论全部降级/停止；A 是唯一近期 bug；
     0.9.132 不作为基线；原报告保留。
3. `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md`
   - 追加「## 2026-11-04 更正」：Step B 不执行（B 未复现）；Step C 已停止；
     E 不启动；A 走新的 A 提示词。
4. `docs/archive/reports/STEP-C-FIX-REPORT-2026-11-04.md`
   - 追加「## 2026-11-04 更正」：C 对用户当前 trailSize=0.2 无收益；
     0.9.130/0.9.131/0.9.132 链停止；原报告保留为历史。
5. `docs/archive/prompts/PROMPT-STEP-B-2026-11-04.md`
   - 文首加：「**已作废（2026-11-04）：B 未复现，不执行。**」
   - 不删正文；这是历史记录。
6. `docs/archive/prompts/PROMPT-STEP-C-2026-11-03.md`
   - 文首加：「**已撤回、无收益（2026-11-04）：C 链停止。**」
7. `docs/archive/prompts/PROMPT-BUG-016-2026-11-03.md`
   - 在阶段 B/E 相关段前标注「**2026-11-04 停止：B 未复现，E 不启动。**」
   - A 取证部分保留，并指向新的 `docs/archive/prompts/PROMPT-A-POPUP-FLICKER-2026-11-04.md`。
8. `CLAUDE.md`
   - 更新状态块：已装 **0.9.127**；`fix/bug-016-completion-flicker` 是保留证据的
     Step B 工作区，**不是发布基线**；A 是唯一近期 bug；B/C/E/D 停止；
     0.9.132 不再使用。
   - 探针数字必须实测：当前工作区 118/133/115；v0.9.127 基线 117/132/114
     （开工时再跑一遍，以实际为准）。
   - 不要写死 HEAD hash；写「见 `git log -1`」。
9. `docs/INDEX.md`
   - 更新 BUG-LOG 行数与描述（`wc -l docs/BUG-LOG.md` 实测）；
   - 增加三份新提示词的索引（A / 文档清理 / 注释审查）；
   - 标注 Step B/C 提示词作废/无收益。

**每一处都要写「为什么改」**，不要只改数字。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑤ AC（可观察结果，不是「门禁绿」）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**AC-1 已装 jar = 0.9.127**
读 jar 内 `META-INF/plugin.xml`，`<version>0.9.127`；备份的 0.9.132 文件存在。

**AC-2 文档纠正完整**
BUG-LOG/DECISION/HANDOFF/STEP-C 都有「2026-11-04 更正」节；
B 降级、C 停止、0.9.132/Step B 无效、A 唯一近期 bug；原文未删。

**AC-3 旧提示词标注**
PROMPT-STEP-B 标「已作废」；PROMPT-STEP-C 标「已撤回、无收益」；
PROMPT-BUG-016 的 B/E 段标「停止」；A 段保留并指向新 A 提示词。

**AC-4 状态数字实测**
CLAUDE.md / INDEX 的探针数、jar 版本、HEAD、fork 同步均来自本轮命令；
没有抄旧数；0.9.132 不再作为基线。

**AC-5 零代码改动**
`git diff --stat` 只包含文档；`src/`、`tools/`、`resources/`、`build/install.ps1`
零改动。Step B 改动在 HEAD `5de3b37` 中，HEAD 未被 reset，这是预期。

**AC-6 不安装、不跑门禁**
没有运行 `install.ps1`；没有启动 IDEA；没有跑全量门禁（本任务不需要）。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑥ 禁止话术 / 禁止做法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

- 禁止 `git reset --hard/--soft`、`git checkout -- .`、`git clean`；工作区是证据。
- 禁止用 `install.ps1 -Version 0.9.127` 回退；它会从当前 Step B 源码重打包。
- 禁止删除文档段落/历史；只能追加「更正」节。
- 禁止改 `src/`、`tools/`、`resources/`、`build/install.ps1`。
- 禁止把 B 重新写成 bug；禁止把 C/0.9.132 重新写成基线。
- 禁止跑全量门禁用「绿」证明文档正确；文档的 AC 是数字实测与内容一致性。
- 禁止 commit/push，除非用户明确要求。
- 禁止启动 IDEA 或改用户配置。
- 禁止在报告中省略「哪些没做」（A 未修、B/C/E/D 停止）。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑦ 白名单（只许改这些）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**文档：**

- `docs/BUG-LOG.md`
- `docs/archive/decisions/DECISION-REPORT-2026-11-03.md`
- `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md`
- `docs/archive/reports/STEP-C-FIX-REPORT-2026-11-04.md`
- `docs/archive/prompts/PROMPT-STEP-B-2026-11-04.md`
- `docs/archive/prompts/PROMPT-STEP-C-2026-11-03.md`
- `docs/archive/prompts/PROMPT-BUG-016-2026-11-03.md`
- `CLAUDE.md`
- `docs/INDEX.md`
- `docs/archive/prompts/PROMPT-A-POPUP-FLICKER-2026-11-04.md`（如需要补一句链接）

**安装目录（只允许覆盖 jar + 备份）：**

- `%APPDATA%\JetBrains\IntelliJIdea2026.1\plugins\neovide-cursor-pawamamama\lib\jetbrains-neovide-cursor-0.9.8.jar`
- `D:\deepseekwork\_config-backup\neovide-cursor-0.9.132-<timestamp>.jar`

**禁止：** 代码、探针、资源、`build/install.ps1`、`gradle.properties`、
`build.gradle.kts`、用户配置 XML。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑧ 交付格式
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. 回退结果：覆盖前后版本、备份路径、IDEA 是否已关闭
2. 文档改动清单：每份文档改了什么、为什么、原文是否保留
3. 状态数字：探针数、jar 版本、HEAD、fork 同步（附命令）
4. `git diff --stat`：证明只改文档
5. 明确写出：A 未修；B/C/E/D 停止；0.9.132 不再使用

【第一条回复要求】
开工后第一条消息只报：HEAD、已装版本、`dist/0.9.127` 是否存在、
工作区是否仍是 Step B 证据；不要先改文档。
