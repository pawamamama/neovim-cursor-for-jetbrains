# 0.9.138-a1 发布与 README 交接提示词（2026-11-06）

> 状态：待执行
> 仓库：`D:\deepseekwork\neovim-cursor-pr`
> 执行位置：从分支 `claude/a1-visual-line-05e7f0`（HEAD `fe1a4fa`，A1 已验收）新开 worktree；不要在主仓库直接改。
> 角色：发布/文档执行会话；不改生产代码、不改 A1 行为、不安装 jar；README / 发布说明 / 全量门禁 / 合并推送 / GitHub Release 由本会话完成。
> 自包含：背景、边界、发布流程、验收与报告要求都在本文件。

---

## 1. 背景与既有事实

- A1（视觉行分支统一）已实现并实机验收通过：同一屏幕视觉行 → SHORT，视觉行变化 → CROSS；单/多光标统一；预览同源；旧字符数/像素兜底已删除。
- 当前 A1 分支：`claude/a1-visual-line-05e7f0`，HEAD `fe1a4fa`（含 `0caa546` A1 实现、`0a75ccb` 验收记录、`a8eabf4` IME 旧探针迁移、`fe1a4fa` 状态文档同步）。
- 已安装验收包：`0.9.138-a1`（IDEA 2026.1.3，8/8 配置还原；用户实机验收 PASS）。
- `fork` = `git@github.com:pawamamama/neovim-cursor-for-jetbrains.git`（SSH）；`origin` 是 https，**禁止推送**。
- 用户明确：**新的实验性开关不建议用（还没改好）**；README/发布说明必须写清。

## 2. 目标

1. 按项目 `docs/RELEASE-SAFETY.md` 的发布前检查清单，跑全量门禁 + D0，确认发布基线。
2. 重写 `README.md`（用户反馈当前 README 质量差）；必要时同步 `README.zh-CN.md`，但不要编造未验证内容。
3. 写 `0.9.138-a1` 版本发布说明（沿用 `docs/archive/reports/VERSION-0.9.126.md` 的结构）。
4. 把 A1 分支以快进方式推送到 `fork/main`，打 tag `v0.9.138-a1`，创建 GitHub Release（若 `gh` 鉴权不可用，停下报告，不伪造）。
5. README / 发布说明必须包含：
   - A1 已验收：单/多光标同一视觉行短跳、真实换行跨行；旧像素/字符数分支已移除；
   - `crossShapeExperiment`（跨行形状实验）**默认关、实验性、不建议日常使用**，A-like 形状还没改好；
   - `trailSize=1.0` 保留；不要建议用户降低密度；
   - 当前版本 `0.9.138-a1`，兼容 IDEA 2026.1（since 242 / until 261.*）；
   - 安装/回滚方式（引用现有 `build/install.ps1` 文档，不在本任务安装）。
6. 只提交 README / 发布说明 / 会话 REPORT；不改 `src/`、`tools/`、`V*.java`、安装脚本、D0。
7. 完成后写 `docs/sessions/2026-11-06-release-0.9.138/REPORT.md`，贴命令与输出。

## 3. 非目标

- 不改 A1 代码、不改 A-like/`trailSize`/单行手感、不实施 B1/B2。
- 不安装/覆盖当前 `0.9.138-a1`，不自动启动 IDEA。
- 不改 `core/NeovideConfig.java`、`CursorPresets.java`、`SettingsField.java`、`NeovideCursorSettings.java`、`NeovideCursorConfigurable.java`、门禁脚本。
- 不修改 `CLAUDE.md` / `CURRENT-STATE` / `NEXT-STEPS` / `INDEX`（这些已在 `fe1a4fa` 同步；如需改动，停下问）。
- 不 push `origin`（https）；不 force push；不改写历史；不 amend 已有提交。
- 不把实验开关写成稳定功能；不把「未安装/未验证」写成已验证。

## 4. 允许改的文件

- `README.md`
- `README.zh-CN.md`（若存在；与 README 同步，不新增未验证声明）
- `docs/sessions/2026-11-06-release-0.9.138/PROMPT.md`（本文件）
- `docs/sessions/2026-11-06-release-0.9.138/REPORT.md`
- `docs/sessions/2026-11-06-release-0.9.138/RELEASE-NOTES.md`（或按项目既有版本说明落点；若选 `docs/archive/reports/VERSION-0.9.138.md`，先确认不违反「archive 不放现状」的规则并写理由）
- `docs/sessions/2026-11-06-release-0.9.138/evidence/*.txt`（可选）

其余只读。需要改清单外文件：停下，在 REPORT §6 写「待用户裁决」。

## 5. 发布流程（按项目文档执行）

1. **先找/读规则与技能**：
   - 读 `docs/RELEASE-SAFETY.md` 全文、`docs/WORKFLOW.md`、`docs/SESSION-PROTOCOL.md`、`CLAUDE.md` 顶部执行摘要。
   - 点名使用 `docs-governance` / `update-docs` / `delivery-gate` / `github-ops` 技能（若本机不存在，按项目文档执行并在 REPORT 写明）。
   - 参考 `docs/archive/reports/VERSION-0.9.126.md` 的版本说明结构，但不要照抄旧数字。
2. **开工核对**：
   - `git status` 干净；分支/HEAD 为 `claude/a1-visual-line-05e7f0` / `fe1a4fa`（或发布 worktree 的对应提交）。
   - jar 内版本 `0.9.138-a1`（当前已装验收包）。
   - `fork/main` 是 A1 分支祖先；用 `git merge-base --is-ancestor fork/main HEAD` 确认快进可行。
3. **全量门禁 + D0**：
   ```powershell
   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1 -Repo <RELEASE_WORKTREE> -Libs D:\libs
   ```
   ```bash
   git -C <RELEASE_WORKTREE> diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
   ```
   串行、只跑一份；保留退出码；最后一行必须 `V-PROBES RESULT: ALL PASS`。
4. **README 重写**：按当前真实功能、设置 7 标签页、安装/兼容、已知限制、实验开关警告重组；不要保留 0.9.131 等旧状态；不要宣传未完成形状。
5. **发布说明**：写 `0.9.138-a1`，包含 A1 行为变化、验证数字（门禁通过、117 注册探针、D0=0、用户实机 PASS）、实验开关警告、回滚方式；不写「修好了 B1」。
6. **提交**：只 stage README / 发布说明 / 本会话文档；新 commit，不 amend、不 push 前先核对 `git diff --cached --name-only`。
7. **推送 / tag / release**：
   - `git push fork HEAD:main`（SSH；必须先确认快进；不要 force）
   - `git tag v0.9.138-a1`，`git push fork v0.9.138-a1`
   - `gh release create v0.9.138-a1 --repo pawamamama/neovim-cursor-for-jetbrains --title ... --notes-file ...`（若 `gh` 未登录或仓库不对，停下报告，不伪造 URL）
8. **报告**：贴命令、门禁最后一行、D0、commit、push 的 refs、tag、release URL（或阻塞原因）。

## 6. 验收标准

| 编号 | 标准 | 怎么验 |
|---|---|---|
| R-1 | 全量门禁 `V-PROBES RESULT: ALL PASS`；D0 `EXIT=0`；无并发 | 日志/命令输出 |
| R-2 | README 不再含 0.9.131 等旧状态；写明 A1 已验收 / 0.9.138-a1 / 实验开关不建议用 | 人工抽查 + `grep` |
| R-3 | 发布说明不宣传未完成功能；不写 B1 已做；数字来自命令 | 人工抽查 |
| R-4 | 只改允许文件；`src/`、`tools/`、D0、状态文档零 diff | `git diff --stat` |
| R-5 | 新 commit（不 amend），未 force push；`fork/main` 快进到发布提交 | `git log` / `git rev-parse` |
| R-6 | tag `v0.9.138-a1` 在发布提交上；release 创建成功或明确报告阻塞 | `git show` / `gh release view` |
| R-7 | REPORT 记录所有命令、数字、未验证项、偏离 | 人工抽查 |

## 7. 禁止事项

- 不改生产代码 / 探针 / 门禁脚本 / 安装脚本 / D0 / 状态文档。
- 不把实验开关 `crossShapeExperiment` 写成稳定功能；不删除它，只标注不建议用。
- 不建议降低 `trailSize`/拖尾密度。
- 不 push `origin`、不 force、不 rewrite history、不 amend。
- 不编造 GitHub release URL、不伪造门禁结果。
- 若 `gh` 无权限、fork 无写权限、tag 已存在、快进失败：停下，写 REPORT §6，不要绕过。

## 8. 分支与版本

- 发布基线：`claude/a1-visual-line-05e7f0` / `fe1a4fa`（A1 已验收）。
- 发布版本：`0.9.138-a1`；tag `v0.9.138-a1`。
- 目标 remote：`fork`（`git@github.com:pawamamama/neovim-cursor-for-jetbrains.git`）；禁止 `origin` https。
- 不改版本号文件；不重新打包安装（jar 已存在并验收）。

## 9. 报告要求

`docs/sessions/2026-11-06-release-0.9.138/REPORT.md` 至少包含：

1. 结论：发布完成 / 部分完成 / 被阻塞；
2. README 与发布说明改动清单；
3. 全量门禁最后一行、D0、注册探针数；
4. 新 commit、push refs、tag、release URL（或阻塞原因）；
5. 未验证项与偏离；
6. 用户下一步（如有）。

---

## 10. 停止线

**README/发布说明完成、门禁 ALL PASS、D0=0、fork/main 快进、tag 与 release 完成（或明确报告阻塞）后停下；不改生产代码、不安装、不 force、不伪造。**
