# 代码注释审查 / 过期注释清理施工提示词（2026-11-04）

> **【2026-11-04 已合并】** 本文已合并到
> `docs/archive/prompts/PROMPT-DOC-COMMENT-COMBINED-2026-11-04.md`，**以合并版为准**。
> 本文保留作历史，不删除。注意：文中「C 对 `trailSize=0.2` 无收益、链停止」与
> 「已装 jar 回退 0.9.127」已被合并版更正：C **保留、已验收**，
> 当前 `trailSize=1.0`，已装 **0.9.131**。
>
> 整段复制给执行会话。自包含；新会话不需要回翻对话。
> 仓库：`D:\deepseekwork\neovim-cursor-pr`
> **只改注释 / javadoc；不改任何逻辑、常量、测试、资源、安装脚本。**
> 当前分支 `fix/bug-016-completion-flicker`，HEAD `5de3b37` 或其后代，
> Step B 改动已在 HEAD 中；**不要 reset**。本次只做注释审查，不改变代码状态。

---

【任务】
清理 `src/main/java` 与 `tools/settings-probe` 里的过期注释/javadoc：
- 代码已经改了、注释还停在旧版本；
- B/C/E 已停止，但注释还在描述「修复 / 待修 / 下限」；
- 历史理由要保留，追加「2026-11-04 更正」，不要删。

**不是修 A/B/C/E/D**：不改行为、不改常量、不改测试断言。

【背景定性（2026-11-04）】
- A：补全弹窗辉光闪烁，唯一要修的近期 bug，仍未修。
- B：同行 `charsMoved ≥ 4` 是**未复现的代码疑点，不是 bug**；50+ 版本 / 8 天未出现。
- C：0.9.130 跨行密度回归是 `aeff707` 为修 B 自造；Step C 对 `trailSize=0.2` 无收益，链停止。
- 0.9.132 / Step B：无效，不作为基线；已装 jar 回退 0.9.127（由文档清理会话完成）。
- D、旧 BUG-005/010/014/015、0.9.90 之前记录：本轮不做。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
① 工作流（注释任务；不适用 tdd-workflow）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

- **Step 0 Research**：跑下面的 grep 清单，逐条核对「注释说的」与「代码实际」；
  读 `CLAUDE.md` §五/§十一（文档会撒谎）。
- **Step 1 Plan**：`planner` 代理出清单：文件、原注释、为什么过期、
  新注释依据（代码行/测量），哪些历史理由保留。
- **Step 2 Edit**：只改注释/javadoc；不改代码行。
- **Step 3 Review**：`code-reviewer` + `java-reviewer` 审注释准确性；
  特别检查「注释是否仍把 B/C 写成 bug/修复」。
- **Step 4 Verify**：`verification-loop` + 全量门禁 + D0 + `git diff` 证明只改注释。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
② 前置检查（数字自己跑）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

```
git status --porcelain
git log -1 --oneline
git branch --show-current
```

预期：分支 `fix/bug-016-completion-flicker`，HEAD `5de3b37` 或其后代；
Step B 的代码/探针改动**已在 HEAD 中**（`TrailCorner`、`VTypingSpan`、
`VBlockStretch`、`VUniform`、`VAuditBatch1` 等）；工作区可能只有
`M docs/INDEX.md` 与未跟踪文档。**不要 reset。**

先记录注释审查前的 diff 基线：

```
New-Item -ItemType Directory -Force -Path build\comment-audit | Out-Null
git diff > build\comment-audit\before.patch
git diff --stat > build\comment-audit\before-stat.txt
```

门禁基线（**只跑一次，严禁并发**，`-Repo` 用正斜杠）：

```
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo 'D:/deepseekwork/neovim-cursor-pr'
```

如果基线不是 ALL PASS，**停下报告**；不要在注释审查里夹带代码修复。

D0：

```
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

必须零 diff。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
③ 重点 grep 清单（逐条核对）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

```
grep -rnE "MAX_DT|1/30|33\.3|55 ?ms|shortAnimationLength \* 0\.5|Math\.max\(leading" src/main/java tools/settings-probe
grep -rnE "charsMoved ?>= ?4|charsMoved ?≥ ?4|误判|误切" src/main/java tools/settings-probe
grep -rnE "leadingSnap|useHardSnap|半边拖尾|前缘贴合" src/main/java tools/settings-probe
grep -rnE "crossMoveFloor|floorLeading|floorNote|已触底|at the floor" src/main/java tools/settings-probe
grep -rnE "0\.4\.3|0\.9\.130|0\.9\.132|Step B|Step C" src/main/java tools/settings-probe
```

已知重点（2026-11-04 实测命中）：

- `ui/CaretAnimator.java`：注释仍写 `MAX_DT = 1/30 ≈ 33.3ms`，
  但代码是 `MAX_DT = 1.0 / 20 = 50ms`（`CaretAnimator.java:173`）。
  原注释解释的是「为什么从 1/30 改到 1/20」，不是当前值；
  要改成「当前 50ms；1/30=33.3ms 是旧值/历史」。
- `settings/NeovideCursorSettings.java`：`MAX_DT = 0.05s` 的注释要与
  `CaretAnimator` 当前值一致。
- `ui/settings/NeovideCursorConfigurable.java`：`0.9.90~0.9.130` 的 55ms 下限
  已撤回；注释不要再说「下限仍在」或「面板必须报 55ms」。
- `core/TrailCorner.java`：`useHardSnap` / 55ms / Step B/C 注释；
  保留历史理由，追加「2026-11-04 更正：B 未复现，C 停止；当前代码无 55ms 下限」。
- `tools/settings-probe/VTypingSpan.java`：头部仍可能把 B 写成「已知误判，Step B 修」；
  改为「B 是未复现代码疑点，2026-11-04 停止；本探针只保留当前判据」。
- `tools/settings-probe/VTextFacts.java` / `VHints.java`：Step C 注释保留，
  但追加「C 链停止，仅历史」。
- `tools/settings-probe/build-v-probes.ps1`：注册注释若还写「修法/下限」，改成历史说明。
- `leadingSnap`：功能已回退，只剩 UI 壳；注释不要描述成有效行为。
- `crossMoveFloor` / `floorLeading` / `floorNote`：代码已删；注释若还引用，改成「已删除」。
- `0.4.3`：如果只出现在 `gradle.properties` / `build.gradle.kts`，**不在本次范围**，
  只在报告里记一笔；不要改构建文件。
- `0.9.130` / `0.9.132`：一律标注「历史/无效链，不作为基线」。

**每处改动必须写：**
1. 原注释为什么过期（代码行/测量/决策）；
2. 新注释依据（引用代码行或命令输出）；
3. 历史理由保留，追加 `2026-11-04 更正：……`。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
④ AC（可观察结果）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**AC-1 只改注释**
`git diff` 的新增/删除行只允许是注释行：
`//`、`/*`、`*/`、`/**`、`*` 或纯注释文本；
不得出现代码、常量、断言、测试逻辑变化。

**AC-2 注释与代码一致**
`MAX_DT` 注释写 50ms / 1.0÷20；55ms 下限注释写「已撤回」；
`charsMoved ≥ 4` 写「未复现代码疑点，B 停止」；`leadingSnap` 写「已回退，仅 UI 壳」。

**AC-3 历史保留**
原注释的历史理由（为什么曾经这么做、哪次失败）仍在；
新结论以「2026-11-04 更正」追加，不覆盖原文。

**AC-4 门禁与 D0**
全量门禁 **ALL PASS**；D0 零 diff。

**AC-5 diff 证明**
把 `build/comment-audit/before.patch` 与审查后的 `git diff` 对比：
- Step B 代码在 HEAD 中，本次 `git diff` 不应出现 Step B 代码 hunk；
- 新增 hunk 全部是注释行；
- 在最终报告里贴 `git diff --stat` 与注释行过滤结果。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑤ 禁止话术 / 禁止做法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

- 禁止改任何逻辑、常量、断言、测试、资源、构建脚本；注释审查不是修 bug。
- 禁止用注释审查夹带 A/B/C/E/D 的代码改动。
- 禁止 `git reset` / `checkout` / `clean`；Step B 改动在 HEAD `5de3b37` 中，是证据。
- 禁止删除历史注释；只能追加更正。
- 禁止把 B 重新写成 bug、把 C/0.9.132 重新写成基线。
- 禁止为了「让注释成立」去改代码；如果代码与注释冲突，先报告，不改代码。
- 禁止只跑单个探针；必须全量门禁 + D0。
- 禁止并发跑门禁；`-Repo` 用正斜杠。
- 禁止安装 jar、启动 IDEA、改用户配置。
- 禁止 commit/push，除非用户明确要求。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑥ 白名单（只给注释改动）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

**允许（仅注释/javadoc）：**

- `src/main/java/**/*.java` 中命中 grep 的文件，优先：
  - `ui/CaretAnimator.java`
  - `settings/NeovideCursorSettings.java`
  - `ui/settings/NeovideCursorConfigurable.java`
  - `ui/settings/AnimationPreview.java`
  - `core/TrailCorner.java`
- `tools/settings-probe/*.java` 中命中 grep 的文件，优先：
  - `VTypingSpan.java`
  - `VTextFacts.java`
  - `VHints.java`
  - `VLeadingSnap.java`
  - `build-v-probes.ps1`（仅注释）
- `docs/COMMENT-AUDIT-2026-11-04.md`（新建审查报告）

**禁止：**
- `core/NeovideConfig.java`（D0）
- `build/install.ps1`、`gradle.properties`、`build.gradle.kts`
- `src/main/resources/**`（资源文案不在本次范围）
- 任何构造/方法体里的代码行、常量、断言
- 现有 `fix/bug-016-completion-flicker` 工作区的 Step B 代码（保留不动）

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑦ 交付格式
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. 注释改动清单：文件 / 原注释 / 为什么过期 / 新注释依据
2. `git diff --stat` 与注释行过滤结果，证明只改注释
3. 全量门禁 RESULT；D0 结果
4. 两个代理的审查结论（注释准确性）
5. 明确写出：未改任何代码/常量/测试；A/B/C/E/D 状态未变

【第一条回复要求】
开工后第一条消息只报：HEAD、工作区是否仍是 Step B 证据、门禁基线 RESULT、
准备先核对哪几条 grep；不要先改注释。
