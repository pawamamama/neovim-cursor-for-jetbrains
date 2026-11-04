# 文档索引

> 本项目有 **169 个** git 跟踪的 md 文件，其中 **108 份在 `docs/archive/`**，**其余 61 份**在活跃目录。
>
> 分布（2026-11-04 实测）：根 6 + `docs/` 28（顶层 20 + `docs/design/` 8）+ `plans/` 18 + `build/` 9 = 61。
>
> **只读下面标为「必读」的，其他按需查。** 归档目录里的东西**不是现状**。
>
> 最后更新：2026-11-04

---

## 第一层：必读（新会话从这里开始）

| 文档 | 行数 | 内容 |
|---|---|---|
| [CURRENT-STATE-2026-11-04.md](CURRENT-STATE-2026-11-04.md) | 146 | **当前真相页（先读这一页）**。已装 0.9.131 / `trailSize=1.0` / A 暂缓 / B 撤回 / C 保留 / D、E 不做 |
| [START-HERE.md](../START-HERE.md) | 228 | 开发档案入口。现状 / 门禁 / 地形 / 能做什么不能做什么 |
| [WORK-DISCIPLINE.md](WORK-DISCIPLINE.md) | 747 | **动手前必读**。13 条编号纪律 + 13 个专题（四-B~四-M） |
| [PARAMETER-MAP.md](PARAMETER-MAP.md) | 205 | 每个参数是否跟随预设，含字段归属表 |
| [NEXT-STEPS.md](NEXT-STEPS.md) | 357 | 当前状态 / 已完成 / 已放弃 / 待办 |

---

## 第二层：按任务查

> 想知道「能在哪些 IDE / 哪些版本 / 哪些操作系统上跑」→ 直接看
> [COMPATIBILITY.md](COMPATIBILITY.md)（2026-10-28，含代码级证据与未验证项清单）。

> **「最后核对」这一列怎么读**：填的是**该文档内容最后一次被验证与代码一致的版本**，
> 不是它的修改日期。写「未核对」的，说明本轮整理**没有逐条验证它**，
> **照抄里面的行号、字段数、文件清单之前，先自己 grep 一遍**（CLAUDE.md §11 的规矩）。

| 任务 | 文档 | 行数 | 最后核对 |
|---|---|---:|---|
| 改物理 / rank / 斜度 | [design/RANK-COLLAPSE-FIX.md](design/RANK-COLLAPSE-FIX.md) | 197 | 0.9.77，未核对 |
| 对齐上游手感 | [design/UPSTREAM-VSCODE-RESEARCH.md](design/UPSTREAM-VSCODE-RESEARCH.md) | 166 | 0.9.83，未核对 |
| **上游 vs 我们各自做了什么** | [design/UPSTREAM-PITFALLS.md](design/UPSTREAM-PITFALLS.md) | 703 | 0.9.93（2026-10-28 补分工一节） |
| 改颜色 / 渐变 / 彩虹 | [COLOR-STATE-DECISIONS.md](COLOR-STATE-DECISIONS.md) | 115 | 未核对 |
| 优化性能（含已证伪方向） | [PERFORMANCE-FPS.md](PERFORMANCE-FPS.md) | 112 | 0.9.76，未核对 |
| 理解 IDEA 平台限制 | [IDEA-PLATFORM-OPTIMIZATION.md](IDEA-PLATFORM-OPTIMIZATION.md) | 261 | 0.9.77，未核对 |
| 发版 | [RELEASE-SAFETY.md](RELEASE-SAFETY.md) | 162 | 0.9.70，未核对 |
| 接手这个项目 | [SKILL-neovide-cursor-plugin-handoff.md](SKILL-neovide-cursor-plugin-handoff.md) | 165 | 0.9.98 |
| 离线编译打包插件（通用技巧） | [SKILL-jetbrains-plugin-offline-build.md](SKILL-jetbrains-plugin-offline-build.md) | 148 | 未核对 |
| 查历史 bug | [BUG-LOG.md](BUG-LOG.md) | 1618 | 2026-11-04（§BUG-016 末尾有「结论汇总」；过程记录保留） |
| **2026-11-03 全天排查与决策材料** | [DECISION-REPORT-2026-11-03.md](archive/decisions/DECISION-REPORT-2026-11-03.md) | 249 | 看文末「2026-11-04 更正」：已装 0.9.131、B 撤回、C 保留、A 暂缓 |
| **Step C 交付（已验收）** | [STEP-C-FIX-REPORT-2026-11-04.md](archive/reports/STEP-C-FIX-REPORT-2026-11-04.md) | 328 | 2026-11-04：撤回 55ms 下限，真跨行前缘 0 ms |
| **Step B 交付（已撤回，勿当基线）** | [STEP-B-FIX-REPORT-2026-11-04.md](archive/reports/STEP-B-FIX-REPORT-2026-11-04.md) | 263 | 2026-11-04：用户从未感受到症状；仅作历史 |
| **Step B 撤回报告** | [STEP-B-REVERT-REPORT-2026-11-04.md](archive/reports/STEP-B-REVERT-REPORT-2026-11-04.md) | 207 | 2026-11-04：0.9.133 行为 = 0.9.131 |
| **Step B 撤回后的决策材料** | [DECISION-REPORT-2026-11-04-STEP-B.md](archive/decisions/DECISION-REPORT-2026-11-04-STEP-B.md) | 286 | 2026-11-04：已装版本已更正为 0.9.131 |
| **A 施工提示词（暂缓）** | [PROMPT-A-POPUP-FLICKER-2026-11-04.md](archive/prompts/PROMPT-A-POPUP-FLICKER-2026-11-04.md) | 250 | 基线已更正为 0.9.131；A 暂缓不修 |
| BUG-016 交接（2026-11-03） | [HANDOFF-2026-11-03-BUG-016.md](archive/handoffs/HANDOFF-2026-11-03-BUG-016.md) | 410 | 看文末「2026-11-04 更正」 |
| **重构值不值得做 / 优先级怎么排** | [REFACTOR-FEASIBILITY.md](REFACTOR-FEASIBILITY.md) | 266 | 0.9.92，未核对 |
| 代码审计记录 | [CODE-AUDIT-0.9.87.md](archive/reports/CODE-AUDIT-0.9.87.md) | 722 | 0.9.87（描述当时） |
| **面板二次重构决策（2026-10-31）** | [DECISIONS-PANEL-2026-10-31.md](archive/decisions/DECISIONS-PANEL-2026-10-31.md) | 192 | 2026-10-31，用户看完 0.9.108 实机后拍板 |
| **数字输入框 / 辉光淡出 修复交付（2026-11-01）** | [FIX-INPUT-FADE-REPORT-2026-11-01.md](archive/reports/FIX-INPUT-FADE-REPORT-2026-11-01.md) | 506 | 0.9.120，含根因、变异测试与验收步骤 |
| **辉光淡出交付与决策层优化报告（2026-11-02）** | [GLOW-FADE-0.9.126-REPORT-2026-11-02.md](archive/reports/GLOW-FADE-0.9.126-REPORT-2026-11-02.md) | 294 | 0.9.126，含 B 方案、113 探针、6 组变异、P0~P3 路线图 |
| **v0.9.126 版本说明（功能 + 0.9.83 对比）** | [VERSION-0.9.126.md](archive/reports/VERSION-0.9.126.md) | 505 | 面向用户/决策层；功能总览与 0.9.83 以来变化 |
| **发版前最终审查（2026-11-01）** | [FINAL-AUDIT-2026-11-01.md](archive/reports/FINAL-AUDIT-2026-11-01.md) | 517 | 3 代理并行；P0 修复记录见 §十 |
| **设置系统重构分步方案** | [../plans/settings-refactor.md](../plans/settings-refactor.md) | 830 | 0.9.80 实测结论 |
| 清理清单与执行记录 | [../plans/cleanup-inventory.md](../plans/cleanup-inventory.md) | 222 | 0.9.61 实测 |
| 「加参数要改 8 处」的原始论证（**0.9.5 时代，勿当现状**） | [design/NEXT-REFACTOR.md](design/NEXT-REFACTOR.md) | 280 | 已加失效横幅 |
| **连续输入时拖尾跳变的修复方案** | [TYPING-JITTER-PLAN.md](TYPING-JITTER-PLAN.md) | 303 | 0.9.91，未核对 |
| 预览窗与真实光标拉伸不一致 | [design/PREVIEW-STRETCH-MISMATCH.md](design/PREVIEW-STRETCH-MISMATCH.md) | 74 | 未核对 |
| 终端/控制台接管范围调研 | [design/INPUT-ALL-SURFACES-RESEARCH.md](design/INPUT-ALL-SURFACES-RESEARCH.md) | 152 | 未核对（BUG-005 引用） |
| 输入框接管的原始想法 | [design/IDEAS-2026-10-21.md](design/IDEAS-2026-10-21.md) | 94 | 未核对（BUG-005 引用） |

---

## 第三层：已归档（**不要当现状**）

`docs/archive/` 下共 **116 份**（含未跟踪的 8 份），按主题分：

| 位置 | 主题 |
|---|---|
| `docs/archive/reports/` | 20 份：修复 / 审计 / 交付报告（BATCH3、CODE-AUDIT、FINAL-AUDIT、STEP-B/C、VERSION / RELEASE-NOTES 等） |
| `docs/archive/handoffs/` | 11 份：历次交接（HANDOFF-2026-10-30 ~ 11-03、BATCH2 / BATCH3、FADE、NEXT） |
| `docs/archive/decisions/` | 4 份：决策报告与面板决策（DECISION-REPORT、DECISIONS-PANEL、LEADING-SNAP-DECISION） |
| `docs/archive/prompts/` | 8 份：一次性施工提示词（`PROMPT-*`，含已作废 / 已合并） |
| `docs/archive/design/` | 30 份：旧设计文档（设置系统重构系列、上游调研、多光标、IME 调研、优化批次） |
| `docs/archive/` 顶层 | 43 份：早期进度与旧版任务书（`PROGRESS` / `ROADMAP` / `VERSIONS` / `README.xx` 等） |

**要追溯「为什么当时这么决定」才去翻它们。** 它们描述的是当时，不是现在。

---

## 维护规则

**新增文档时**：
1. 先想清楚属于哪一层，加进对应表格，**填上「最后核对」版本**
2. 写在 `docs/` 下，不要往根目录堆
3. 根目录只留：`README*`（用户看的）+ `START-HERE.md`（开发档案入口）+ `LESSONS.md`

**文档过时时**：
- **移到 `docs/archive/`**，不要留在 `docs/` 里冒充现状，**也不要直接删**
- 归档子目录按类型放：`reports/`（修复/审计/交付）、`handoffs/`（交接）、
  `decisions/`（决策）、`prompts/`（一次性提示词）；旧的放 `docs/archive/` 顶层
- 移动后，把本文件与 `CLAUDE.md §9` 里指向它的行一起改掉 —— 否则索引会指向不存在的文件
- 移动后跑一次断链检查（2026-11-04 本轮用临时 Python 脚本检查，149 条链接 0 断）

**不要做的事**：
- 不要把「当前版本 / 探针数量 / 通过数」写进任何文档而不跑一遍命令（CLAUDE.md §11）
- 不要在几十份文档里各写一份状态横幅 —— 同一个数字被抄 6 份，改的时候必然漏
