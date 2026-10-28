# 文档索引

> 本项目有 **113 个** git 跟踪的 md 文件，**活跃 41 份，其余 72 份在 `docs/archive/`**。
>
> 活跃 41 = 根 6 + `docs/` 17 + `docs/design/` 7 + `plans/` 2 + `build/` 8（发布说明，不用读）+ 其他 1
>
> **只读下面标为「必读」的，其他按需查。** 归档目录里的东西**不是现状**。
>
> 最后更新：2026-10-28

---

## 第一层：必读（新会话从这里开始）

| 文档 | 行数 | 内容 |
|---|---|---|
| [START-HERE.md](../START-HERE.md) | 219 | **唯一的状态入口**。现状 / 门禁 / 地形 / 能做什么不能做什么 |
| [WORK-DISCIPLINE.md](WORK-DISCIPLINE.md) | 747 | **动手前必读**。13 条编号纪律 + 13 个专题（四-B~四-M） |
| [PARAMETER-MAP.md](PARAMETER-MAP.md) | 172 | 每个参数是否跟随预设，含字段归属表 |
| [NEXT-STEPS.md](NEXT-STEPS.md) | 176 | 当前状态 / 已完成 / 已放弃 / 待办 |

---

## 第二层：按任务查

> **「最后核对」这一列怎么读**：填的是**该文档内容最后一次被验证与代码一致的版本**，
> 不是它的修改日期。写「未核对」的，说明本轮整理**没有逐条验证它**，
> **照抄里面的行号、字段数、文件清单之前，先自己 grep 一遍**（CLAUDE.md §11 的规矩）。

| 任务 | 文档 | 行数 | 最后核对 |
|---|---|---:|---|
| 改物理 / rank / 斜度 | [design/RANK-COLLAPSE-FIX.md](design/RANK-COLLAPSE-FIX.md) | 197 | 0.9.77，未核对 |
| 对齐上游手感 | [design/UPSTREAM-VSCODE-RESEARCH.md](design/UPSTREAM-VSCODE-RESEARCH.md) | 166 | 0.9.83，未核对 |
| **上游 vs 我们各自做了什么** | [design/UPSTREAM-PITFALLS.md](design/UPSTREAM-PITFALLS.md) | 672 | 0.9.93（2026-10-28 补分工一节） |
| 改颜色 / 渐变 / 彩虹 | [COLOR-STATE-DECISIONS.md](COLOR-STATE-DECISIONS.md) | 115 | 未核对 |
| 优化性能（含已证伪方向） | [PERFORMANCE-FPS.md](PERFORMANCE-FPS.md) | 112 | 0.9.76，未核对 |
| 理解 IDEA 平台限制 | [IDEA-PLATFORM-OPTIMIZATION.md](IDEA-PLATFORM-OPTIMIZATION.md) | 261 | 0.9.77，未核对 |
| 发版 | [RELEASE-SAFETY.md](RELEASE-SAFETY.md) | 162 | 0.9.70，未核对 |
| 接手这个项目 | [SKILL-neovide-cursor-plugin-handoff.md](SKILL-neovide-cursor-plugin-handoff.md) | 154 | 0.9.93 |
| 离线编译打包插件（通用技巧） | [SKILL-jetbrains-plugin-offline-build.md](SKILL-jetbrains-plugin-offline-build.md) | 148 | 未核对 |
| 查历史 bug | [BUG-LOG.md](BUG-LOG.md) | 359 | 0.9.93（2026-10-28 补 BUG-006 根因/修法/证据） |
| **重构值不值得做 / 优先级怎么排** | [REFACTOR-FEASIBILITY.md](REFACTOR-FEASIBILITY.md) | 266 | 0.9.92，未核对 |
| 代码审计记录 | [CODE-AUDIT-0.9.87.md](CODE-AUDIT-0.9.87.md) | 722 | 0.9.87（描述当时） |
| **设置系统重构分步方案** | [../plans/settings-refactor.md](../plans/settings-refactor.md) | 830 | 0.9.80 实测结论 |
| 清理清单与执行记录 | [../plans/cleanup-inventory.md](../plans/cleanup-inventory.md) | 222 | 0.9.61 实测 |
| 「加参数要改 8 处」的原始论证（**0.9.5 时代，勿当现状**） | [design/NEXT-REFACTOR.md](design/NEXT-REFACTOR.md) | 280 | 已加失效横幅 |
| **连续输入时拖尾跳变的修复方案** | [TYPING-JITTER-PLAN.md](TYPING-JITTER-PLAN.md) | 303 | 0.9.91，未核对 |
| 预览窗与真实光标拉伸不一致 | [design/PREVIEW-STRETCH-MISMATCH.md](design/PREVIEW-STRETCH-MISMATCH.md) | 74 | 未核对 |
| 终端/控制台接管范围调研 | [design/INPUT-ALL-SURFACES-RESEARCH.md](design/INPUT-ALL-SURFACES-RESEARCH.md) | 152 | 未核对（BUG-005 引用） |
| 输入框接管的原始想法 | [design/IDEAS-2026-10-21.md](design/IDEAS-2026-10-21.md) | 94 | 未核对（BUG-005 引用） |

---

## 第三层：已归档（**不要当现状**）

`docs/archive/` 下共 **76 份**，按主题分：

| 位置 | 主题 |
|---|---|
| `docs/archive/design/` | 30 份：设置系统重构系列（含 `SETTINGS-CONTRACT` / `SETTINGS-UI-CONTRACT` 两份旧权威契约）、上游调研、多光标系列、IME 调研、优化批次 |
| `docs/archive/` | 17 份：早期交接与进度（`PROGRESS` / `ROADMAP` / `VERSIONS` / `HANDOFF-*`）、旧版任务书与验收清单、旧 release notes |
| `docs/archive/README.*.md` | 3 份：中日韩三语 README（已不再同步） |

**要追溯「为什么当时这么决定」才去翻它们。** 它们描述的是当时，不是现在。

---

## 维护规则

**新增文档时**：
1. 先想清楚属于哪一层，加进对应表格，**填上「最后核对」版本**
2. 写在 `docs/` 下，不要往根目录堆
3. 根目录只留：`README*`（用户看的）+ `START-HERE.md`（开发档案入口）+ `LESSONS.md`

**文档过时时**：
- **移到 `docs/archive/`**，不要留在 `docs/` 里冒充现状，**也不要直接删**
- 移动后，把本文件与 `CLAUDE.md §9` 里指向它的行一起改掉 —— 否则索引会指向不存在的文件

**不要做的事**：
- 不要把「当前版本 / 探针数量 / 通过数」写进任何文档而不跑一遍命令（CLAUDE.md §11）
- 不要在几十份文档里各写一份状态横幅 —— 同一个数字被抄 6 份，改的时候必然漏
