# 0.9.138-a1 发布执行报告（2026-11-06）

> 状态：**发布完成**
> 分支：`claude/release-0-9-138-ad1ad7`（从 A1 分支 `claude/a1-visual-line-05e7f0` 快进）
> 发布提交：`b5c8558`（`docs: 发布 0.9.138-a1（A1 视觉行分支统一）`）
> 发布 tag：`v0.9.138-a1`（annotated，指向 `b5c8558`）
> Release：<https://github.com/pawamamama/neovim-cursor-for-jetbrains/releases/tag/v0.9.138-a1>

---

## 1. 结论

**发布完成。** README（英文 + 简体中文）已重写，`0.9.138-a1` 发布说明已写，
全量门禁 `V-PROBES RESULT: ALL PASS`（117 个注册探针，`GATE_EXIT=0`），
D0 `EXIT=0`；发布提交已快进推送到 `fork/main`，tag `v0.9.138-a1` 已推送，
GitHub Release 已创建并上传验收 jar。

**没有改生产代码、探针、门禁脚本、安装脚本、D0 或状态文档，没有安装/覆盖 IDEA。**

---

## 2. 改动清单

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `README.md` | 重写为面向用户的当前版本说明：A1 行为、7 标签设置页、实验开关警告、安装/兼容/已知限制；删除 0.9.126 时代的历史叙述与过时数字 | 用户反馈旧 README 质量差；旧版仍在讲字符数判据与 `v0.9.126`，与 A1 后的代码不一致 |
| `README.zh-CN.md` | 与英文版同步重写 | 保持中英文入口一致 |
| `docs/sessions/2026-11-06-release-0.9.138/PROMPT.md` | 从主仓库同名未跟踪文件只读复制，内容零改动（SHA-256 `ab531447...`） | 让发布分支同时保留施工提示词；主仓库原件未改 |
| `docs/sessions/2026-11-06-release-0.9.138/RELEASE-NOTES.md` | 新增 `0.9.138-a1` 版本说明；数字全部来自本次命令 | 发布说明 / GitHub Release notes |
| `docs/sessions/2026-11-06-release-0.9.138/evidence/FINAL-gate.txt` | 全量门禁完整输出与 `GATE_EXIT=0` | 可复现证据 |
| `docs/sessions/2026-11-06-release-0.9.138/REPORT.md` | 本报告 | PROMPT §9 |

**未改**：`src/`、`tools/`、`V*.java`、`build/*.ps1`、`core/NeovideConfig.java`、
`CLAUDE.md`、`START-HERE.md`、`docs/CURRENT-STATE-2026-11-04.md`、
`docs/NEXT-STEPS.md`、`docs/INDEX.md`、`CursorPresets.java`、`SettingsField.java`、
`NeovideCursorSettings.java`、`NeovideCursorConfigurable.java`。

---

## 3. 开工核对与基线

应用创建的 worktree 初始在 `claude/release-0-9-138-ad1ad7` / `f43ca55`
（与 `fork/main` 相同），**不包含 A1 分支**。已确认 `f43ca55` 是 A1 分支
`claude/a1-visual-line-05e7f0`（`fe1a4fa`）的祖先，随后执行：

```bash
git merge --ff-only claude/a1-visual-line-05e7f0
```

结果：`Updating f43ca55..fe1a4fa`，快进成功，工作区干净。
因此本次发布基于 A1 验收提交 `fe1a4fa`，没有改写历史。

发布前核对：

```text
工作区              clean
HEAD                fe1a4fa（A1 验收提交）
fork/main           f43ca55（HEAD 的祖先）
git rev-list --left-right --count fork/main...HEAD  ->  0 8
已装 jar 内版本      <version>0.9.138-a1
已装 jar 兼容区间    <idea-version since-build="242" until-build="261.*"
```

---

## 4. 门禁与证据

### 4.1 全量门禁（串行、只跑一份）

```bash
pwsh -NoProfile -ExecutionPolicy Bypass \
  -File tools/settings-probe/build-v-probes.ps1 \
  -Repo "D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/release-0-9-138-ad1ad7" \
  -Libs "D:\libs" > docs/sessions/2026-11-06-release-0.9.138/evidence/FINAL-gate.txt 2>&1
```

关键输出（原文，见 `evidence/FINAL-gate.txt`）：

```text
==> javac 源码树（37 个 .java）
==> javac V 探针（118 个）
...
VMultiBranchDiag         0   35    0 === V-MULTI-BRANCH-DIAG 结果：PASS 35，FAIL 0 ===
VPreviewBranch           0    8    0 === V-PREVIEW-BRANCH 结果：PASS 8，FAIL 0 ===
VTypingSpan              0    9    0 === V-TYPING-SPAN 结果：PASS 9，FAIL 0 ===
VPreviewParity           0   12    0 RESULT: ALL PASS

V-PROBES RESULT: ALL PASS
GATE_EXIT=0
```

- 注册探针：**117**（门禁输出中 `exit=... PASS=...` 行数实测）。
- 探针文件：**118** 个 `V*.java` / **133** 个 `*.java` / **34,901** 行。
- 生产代码：**37** 文件 / **21,498** 行。
- `VRegr2` 仍是 informational：`exit=1 PASS=13 FAIL=2`（R-5 / R-6，既有功能缺口，
  按脚本 `$informational` 不计入失败门，本版未修）。

### 4.2 D0

```bash
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
# D0_EXIT=0
```

### 4.3 发布产物核对

```text
dist/neovide-cursor-0.9.138-a1.jar   253,941 B
SHA256  b81ec276b403eb81d2caaf68393daac492f0245c6542ef5bef99ede0252688ff
<version>0.9.138-a1
<idea-version since-build="242" until-build="261.*"
```

该 jar 来自 A1 验收 worktree 的 `dist/`，与 A1 报告 §4.7 记录的原始包
大小 / SHA-256 一致；**未重新打包、未安装**。

### 4.4 实机验收（引用既有记录，非本轮重跑）

A1 报告 §7 记录：IDEA 2026.1.3（Windows 11），安装包 `0.9.138-a1`，
单/多光标 × 英文/中文 × Tab / End / Ctrl+方向 / 鼠标同行与跨行 / 选区 /
按词移动 / Enter / 行尾回绕，用户结论 **PASS**（2026-11-06）。
本会话没有重新安装或启动 IDEA，如实引用。

---

## 5. 提交 / 推送 / tag / Release

### 5.1 新 commit（不 amend）

```text
b5c85580d78e6714b53c756c5eeb323f3f5c48cc
docs: 发布 0.9.138-a1（A1 视觉行分支统一）
```

暂存前核对过 `git diff --cached --name-only`，只含 README ×2、PROMPT、
RELEASE-NOTES、FINAL-gate；未夹带生产代码或其他文档。

### 5.2 推送（SSH、非 force、快进）

```bash
git merge-base --is-ancestor fork/main HEAD   # ANCESTOR_EXIT=0
git rev-list --left-right --count fork/main...HEAD   # 0 8
git push fork HEAD:main
```

```text
To github.com:pawamamama/neovim-cursor-for-jetbrains.git
   f43ca55..b5c8558  HEAD -> main
PUSH_EXIT=0
```

远程核对：`refs/heads/main = b5c85580d78e6714b53c756c5eeb323f3f5c48cc`。

### 5.3 tag

```bash
git tag -a v0.9.138-a1 -m "Neovide Cursor 0.9.138-a1 (A1 visual-line branch unification)" b5c8558
git push fork v0.9.138-a1
```

```text
* [new tag]         v0.9.138-a1 -> v0.9.138-a1
TAG_PUSH_EXIT=0
git rev-list -n 1 v0.9.138-a1  ->  b5c85580d78e6714b53c756c5eeb323f3f5c48cc
```

### 5.4 GitHub Release

```text
URL        https://github.com/pawamamama/neovim-cursor-for-jetbrains/releases/tag/v0.9.138-a1
title      v0.9.138-a1 — A1 视觉行分支统一
draft      false
prerelease false
asset      neovide-cursor-0.9.138-a1.jar（253,941 B，state=uploaded）
```

`gh` 账户 `pawamamama` 已登录，仓库权限 `ADMIN`；release 创建退出码 0。

---

## 6. 未验证项与偏离

### 6.1 未验证项

- **未重新安装 / 未启动 IDEA**：按 PROMPT 要求，本轮只做发布与文档；
  实机 PASS 引用 A1 报告，不是本轮重跑。
- **未在 2024.2 / 其他 JetBrains 产品 / Linux / macOS 验证**：README 与
  发布说明都按“未验证”如实标注。
- **门禁是 headless 探针门禁**：验证的是生产语义、面板与预览链路，
  不等于真实编辑器渲染的逐像素验证。
- **`VRegr2` 的 R-5 / R-6 仍红**：既有 informational，本版未修。
- **Release notes 的观感描述来自用户验收记录**：本会话没有重新评价手感。

### 6.2 偏离 / 补充说明

1. **worktree 基线偏差（已修正）**：应用创建的 worktree 在 `f43ca55`
   （fork/main），不是 A1 HEAD。已确认祖先关系后用 `git merge --ff-only`
   快进到 `fe1a4fa`，无历史改写。
2. **Release 附加了 jar 资产**：PROMPT §5.7 未显式要求附件，但 README 的
   “从 Release 下载”安装方式需要可下载产物；附加的是 A1 验收 worktree 中
   已存在的 jar，未重新打包、未安装。
3. **REPORT 在 Release 之后以第二个提交入库**：tag `v0.9.138-a1` 指向发布提交
   `b5c8558`（README / 发布说明 / 证据）；本 REPORT 含 Release URL，只能在
   Release 创建后写，因此作为后续文档提交推送。tag 不包含本 REPORT，
   这是发布流程的已知顺序，不是漏提交。
4. **PROMPT.md 来源**：主仓库同名文件本轮开始时是**未跟踪**状态
   （`?? docs/sessions/2026-11-06-release-0.9.138/`）；只读复制进发布分支，
   SHA-256 与主仓库原件一致，内容未改。
5. **门禁使用 `-Libs D:\libs`**：按 PROMPT §5.3 给出的命令；`-Repo` 使用真实
   worktree 路径，未用 junction，避免 `VI18n I-0` 路径规范化误判。
6. **技能使用**：
   - `docs-governance`：本机是路由器；本项目已有 `docs/INDEX.md` /
     `CURRENT-STATE` 等治理结构，按现有结构执行，未新增治理层。
   - `update-docs`：其通用流程针对 `package.json` / `.env` / OpenAPI，
     本项目不适用；按项目事实源（源码、命令、探针）同步 README。
   - `delivery-gate`：本机是 Stop-hook 形态，本项目未配置该 hook；
     判据以项目全量探针门禁为准。
   - `github-ops`：用于 Release 创建与核对。
7. **未归档会话目录**：`docs/sessions/2026-11-06-release-0.9.138/` 留在
   `docs/sessions/`，按协议等用户验收后再移入 `docs/archive/sessions/`；
   本轮不做移动。

---

## 7. 用户下一步

1. 打开 Release 页面核对 notes 与附件：
   <https://github.com/pawamamama/neovim-cursor-for-jetbrains/releases/tag/v0.9.138-a1>
2. 若需要，在 GitHub 上抽查 README 渲染（英文 / 中文切换、链接、表格）。
3. 若验收通过，可把 `docs/sessions/2026-11-06-release-0.9.138/` 移入
   `docs/archive/sessions/`（独立文档提交，本轮未做）。

---

## 8. 验收记录（用户填）

- 实机环境：IDEA 2026.1.3（Windows 11），已装 `0.9.138-a1`（A1 验收时）
- 发布页面核对：待用户填写
- README 渲染核对：待用户填写
- 结论：待用户填写（通过 / 不通过）
