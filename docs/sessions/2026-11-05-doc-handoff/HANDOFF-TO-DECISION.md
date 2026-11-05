# 交接给 neovim 决策会话（2026-11-05）

> 本文件是**上一轮会话做完的事**，不是 PROMPT。
> 决策会话读完这份 + 自己跑命令复核，再按 `docs/SESSION-PROTOCOL.md` 生成下一轮 PROMPT。

---

## 1. 用户要的循环（原话）

> 「我一般是一个决策会话，他决策生成提示词另一个会话干，干完了我验证写报告决策会话读取，循环现在ai能知道怎么弄了吗 还有开发的时候会用代理和skill技能吗」

以及本轮指令：

> 「发给neovim决策会话 告诉你做了什么然后他 交接决策会话生成交接提示词我复制到新会话，你这边同步推送main 和fork」

**结论**：流程已写进项目文档，但角色不会自动判定 —— 决策会话必须被告知「你是决策会话」。代理不会自动启动；技能按描述匹配，不保证命中。最稳的是点名。

---

## 2. 本会话实际做了什么（只改文档，不改生产代码）

| 提交 | 内容 |
|---|---|
| `16e9de7` | `docs/INDEX.md` 数量 169→179 / 117 / 62 |
| `a5373f7` | 归档 `build/night-run-report.md` → `docs/archive/reports/NIGHT-RUN-REPORT-2026-10-28.md`（加归档说明，正文未改）；INDEX 180/118/62 |
| `02e378d` | `CLAUDE.md` 顶部增 68 行执行摘要；新建 `docs/WORKFLOW.md`（129 行五步）；INDEX 181/118/63 |
| `9386780` | 8 份插件项目会话计划从 `C:\Users\wangw\.claude\plans\` 移入 `docs/archive/plans/`；INDEX 189/126/63 |
| `b30c537` | 新建 `docs/SESSION-PROTOCOL.md`（170 行）：双会话角色、PROMPT / REPORT 模板、代理与技能分工；INDEX 190/126/64 |
| `d6c2bc1` | 更正 `CLAUDE.md §二` 远程 / 工作区 / 门禁三行状态（**该提交信息里的 ALL PASS 是错的，见 §4**） |

另外：`CLAUDE.md` 还有一处未提交修正 —— 删掉 `§二` 里写死的 `b30c537` 与过期的 `0 1` 快照，改成「只讲命令怎么读」。

**`~/.claude` 的处理**：只动了 `plans/` 下 8 份属于本插件的文件（移到仓库归档）。根目录三个 md（`CLAUDE.md` / `CLAUDE-WORKFLOW-HANDOFF.md` / `WORKFLOW-ALIGNMENT.md`）与 `agents/`、`commands/`、`rules/` 全部没碰；`~/.claude/plans/` 只剩另一个项目的 `1-5-deep-grove.md`。

---

## 3. 现在的项目事实（命令可复核）

```
仓库 D:\deepseekwork\neovim-cursor-pr
分支 fix/bug-016-completion-flicker
已装版本 v0.9.131（读 jar 内 plugin.xml；文件名恒为 0.9.8.jar）
当前构建 0.9.133 行为 = 0.9.131；0.9.132 / 0.9.133 是中间版
探针 118 个 V*.java / 133 个 *.java / 115 个注册探针
md 190 个跟踪 / archive 126 / active 64
D0 core/NeovideConfig.java 零 diff
```

远程：`fork` = `git@github.com:pawamamama/...`（SSH，推送必须走它）；`origin` 是 https，**不要推**。

---

## 4. 必须诚实记下的一次错误（不要被决策会话照抄）

`d6c2bc1` 的提交信息写了「验证：V-PROBES RESULT: ALL PASS」，**这是错的**。

- 那次命令是 `pwsh ... | tail -3 && git add ...`，管道退出码来自 `tail`，门禁实际输出 `FAILED（1 个探针未通过）` 被吞掉了。
- 真正原因：`CLAUDE.md §二` 写了 `b30c537`，触发 `VDocCheck` 的 D-3（三份状态文档不得写死 7~40 位十六进制串）。
- 随后又因**两份门禁并发**共用 `build\v2-probe\probe`，出现过 `FAILED（9 个探针未通过）` 与 `VMultiCursorFix exit=1 PASS=0 FAIL=0` —— 这是 `CLAUDE.md` 陷阱 4 / 四-G-2 的假红，不是回归。
- 修法：删掉 hash 与快照数字；单份重跑，`V-PROBES RESULT: ALL PASS`，`PIPE=0 0 0 0`，D0 `EXIT=0`。

**给下一轮的约束**：任何门禁命令都不要用 `| tail` 后再 `&&`，必须保留管道退出码（`PIPESTATUS`）或写日志文件；不许并发跑两份。

---

## 5. 文档现状（决策会话要读的）

| 文件 | 行数 | 作用 |
|---|---:|---|
| `CLAUDE.md` | 913 | 每轮自动注入；顶部执行摘要 + 全文规则 |
| `docs/WORKFLOW.md` | 129 | 单会话五步（复现 → 定位 → 实现 → 门禁 → 提交） |
| `docs/SESSION-PROTOCOL.md` | 170 | 双会话角色、PROMPT / REPORT 模板、代理技能分工 |
| `docs/CURRENT-STATE-2026-11-04.md` | 146 | 版本 / trailSize / A-E 状态 |
| `docs/INDEX.md` | — | 190 / 126 / 64 与全部文档索引 |
| `docs/WORK-DISCIPLINE.md` | 747 | 14 条纪律（为什么） |

`docs/archive/` 现有 `reports/`、`handoffs/`、`decisions/`、`prompts/`、`design/`、`plans/`、顶层历史文件；**一律不是现状**。

---

## 6. 决策会话现在该做什么

1. 读 `CLAUDE.md` 顶部执行摘要、`docs/SESSION-PROTOCOL.md`、`docs/WORKFLOW.md`。
2. 自己跑命令复核 §3 的数字（不要照抄本报告）。
3. 按 `SESSION-PROTOCOL.md` 第三节生成**下一轮 PROMPT**，写到
   `docs/sessions/<YYYY-MM-DD>-<主题>/PROMPT.md`，**自包含**。
4. PROMPT 里必须明确：目标 / 非目标 / 允许改的文件 / 可测验收标准 / 必跑命令 /
   禁止事项 / 分支与版本 / 报告要求。
5. 把 PROMPT 全文给用户复制；**不要改代码，不要替执行会话做决定**。

**当前没有待修的产品 bug**。若用户没给新需求，下一轮 PROMPT 只能围绕用户明确提出的观感 /
功能，不要凭空从 `NEXT-STEPS.md` 挑一项开工（四-I：语义不清先问）。

---

## 7. 未验证 / 遗留

- 本地 `main` 仍指向旧提交；本会话会把它快进到当前 HEAD，并同步 `fork/main`。
- 本轮所有改动都是文档；没有安装 jar、没有实机观感验证，不能声称「观感没变」。
- `d6c2bc1` 的错误提交信息用本文件 §4 更正，不悄悄改历史。
