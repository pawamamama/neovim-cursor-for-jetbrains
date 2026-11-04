# md 收缩到「可用范围」+ 加「最后核对版本」

> 2026-10-28 · 方案（**未执行**）

---

## Context

**问题不是「文档多」，是「分不清哪份是现状」。**

实测（本轮 grep/awk，非印象）：`docs/design/` 共 37 份、约 9,000 行，其中**只有 2 份被活跃文档高频引用**：

```
UPSTREAM-VSCODE-RESEARCH.md   6 次  (CLAUDE / INDEX / NEXT-STEPS / SKILL / WORK-DISCIPLINE / START-HERE)
RANK-COLLAPSE-FIX.md          5 次  (CLAUDE / INDEX / NEXT-STEPS / SKILL / START-HERE)
```

其余 35 份：最大三份（`SETTINGS-UI-CONTRACT` 1546 行 / `OPTIM-BATCH1` 1022 行 / `SETTINGS-CONTRACT` 837 行 = **3405 行**）在 `INDEX.md:80-82` 里**已被写成「历史留痕」**，却仍留在 `design/`；另有约 20 份零引用。

新会话打开文档树，看到几十份 md 无法判断该读哪份 —— 这就是「旧文档影响 agent 干活」的实际形态。

**用户已定的三个方向**：
1. 收缩力度：**只留「现在能用的」**（活跃 md 78 → 约 25）
2. 作废文档：**只归档，不删**
3. `SETTINGS-UI-CONTRACT` / `OPTIM-BATCH1` / `SETTINGS-CONTRACT` 这 3405 行 → **移入 archive**

---

## 指导原则

1. **只挪不删**：全部 `git mv` 进 `docs/archive/`，git 历史完整。
2. **不重写历史**：归档文档内容一字不改。
3. **单一真相源**：「现在该读什么」只在 `docs/INDEX.md` 与 `CLAUDE.md §9` 各声明一次。
4. **先列清单再动手**（纪律 §8.3）：第 1 步产出逐份判定表给你确认，确认后才移动。
5. **不碰 `docs/archive/` 既有内容**，只往里加。
6. **不动生产代码、不动探针。**

---

## 第 0 步：产出逐份判定表（先给你确认，不移动任何文件）

用本轮已跑的两个盘点（引用网络 / 主题聚类）+ 我自测的引用数据，生成一张表：

| 文件 | 行数 | 被引用次数 | 文中最高版本 | 判定 |
|---|---|---|---|---|

判定规则（按优先级）：
1. 被 `CLAUDE.md` / `START-HERE.md` / `docs/INDEX.md` 指向 → **必须保留**（除非同时满足规则 3）
2. 被引用 0 次，且不在任何入口 → **归档**
3. 文档自述「已完成 / 已作废 / 已取代 / 已回退」→ **归档**，并顺手删掉入口里指向它的行
4. 与现行代码冲突（旧字段数、旧「8 处」清单）→ 已在其中加横幅的保留，未加的看是否仍是入口

> 确认方式：把表贴给你，你说「按这个走」或圈出要改的。

---

## 第 1 步：确定保留集合（预期 ~25 份）

**根目录（5）**：`CLAUDE.md`、`START-HERE.md`、`LESSONS.md`（用户明确要留根）、`README.md`、`README.zh-CN.md`

**`docs/` 顶层**：`INDEX.md`、`WORK-DISCIPLINE.md`、`PARAMETER-MAP.md`、`NEXT-STEPS.md`、`BUG-LOG.md`、`COLOR-STATE-DECISIONS.md`、`PERFORMANCE-FPS.md`、`RELEASE-SAFETY.md`、`IDEA-PLATFORM-OPTIMIZATION.md`、`SKILL-neovide-cursor-plugin-handoff.md`，以及第 0 步判定为「仍活跃」的少数几份（如 `TYPING-JITTER-PLAN.md`）。

**`docs/design/`（预计保留 6 份）**：

| 文档 | 保留理由 |
|---|---|
| `UPSTREAM-VSCODE-RESEARCH.md` | 6 处引用，CLAUDE.md「对齐上游」入口 |
| `RANK-COLLAPSE-FIX.md` | 5 处引用，CLAUDE.md「改物理」入口 |
| `NEXT-REFACTOR.md` | 已加状态横幅，`VPreset.java:378` 点名引用 |
| `PREVIEW-STRETCH-MISMATCH.md` | `BUG-LOG.md` 技术债第 1 条指向它 |
| `IDEAS-2026-10-21.md` | `BUG-LOG.md` BUG-005 指向它 |
| `INPUT-ALL-SURFACES-RESEARCH.md` | `BUG-LOG.md` BUG-005 指向它（终端路线 C） |

> 若第 0 步确认某份「只被即将归档的文档引用」，则它也能一并归档 —— 归档是**连锁**的：`PROJECT-GUIDE.md` 归档后，它引用的 `MULTICURSOR-*.md` / `UPSTREAM-PITFALLS.md` 就失去最后一个活跃引用。

---

## 第 2 步：归档集合（预期 ~50 份）

**明确归档（用户已定）**
```
docs/design/SETTINGS-UI-CONTRACT.md   1546 行
docs/design/OPTIM-BATCH1.md           1022 行
docs/design/SETTINGS-CONTRACT.md       837 行
README.ja-JP.md / README.ko-KR.md / README.zh-TW.md
```
> 注：我先前选项里写「归档四份」与文件数不符 —— README 共 5 份，留 2 份应归档 **3 份**。执行前会先读 `README.md` 与 `README.zh-CN.md` 确认两者都是中文。

**自述已失效（归档并清理入口引用）**
```
docs/F7-SUPERSEDED.md
docs/HANDOFF-REFACTOR-RUNTIME-SWITCH.md   （已加失效横幅，重构已完成）
docs/HANDOFF-runtimeSwitch-重构报告.md      （同上）
docs/BRANCH-AUDIT-20261027.md             （2026-10-28 已按它实际整理完分支）
docs/RELEASE-0.9.33.md
```

**停在旧版本、被替代（归档）**
```
docs/PROJECT-GUIDE.md        0.9.24（已有过期横幅）
docs/RESUME-HERE.md          0.9.57（已有过期横幅）
docs/NEXT-DEVELOPMENT.md
docs/DEAD-PROBE-CLEANUP.md
docs/VSNAP-DIAGNOSIS.md      （VSnap 已修绿）
docs/design/ 其余零引用文档  （约 20 份）
```

**归档去向**：`docs/archive/design/`（新建子目录，与既有 archive 平级），避免 `docs/archive/` 顶层再堆 40 份。

---

## 第 3 步：执行移动

```bash
# 每批 5~8 个，逐批确认
git mv docs/design/<old>.md docs/archive/design/<old>.md
git status --porcelain          # 必须是 R（重命名），出现 D 就停下查
```

不与其他会话的改动混在一起（`git status --porcelain` 逐个核对，纪律陷阱 10）。

---

## 第 4 步：更新索引（唯一声明处）

**`docs/INDEX.md`**
- 第一层「必读」收敛到 ≤6 份
- 第二层「按任务查」**加「最后核对版本」列** —— 这是用户本轮要的另一件事
- 第三层、第四层合并为「已归档（不要当现状）」，**只列主题不再逐份列举**

**`CLAUDE.md §9`**：同步「按任务查」表 + 同样的「最后核对版本」列。

**不在 30 份文档里各写横幅** —— 那正是本项目「同一个数字被抄 6 份」的老病根（`NEXT-STEPS` 的「72/82」就是这么来的）。

「最后核对版本」列的取值规则：填该文档**内容最后一次被验证与代码一致的版本**；没验证过的填「未核对」。禁止填最后改动日期冒充。

---

## 第 5 步：验证

| 检查 | 命令 | 期望 |
|---|---|---|
| 活跃 md 数 | `git ls-files '*.md' \| grep -v '^docs/archive/' \| wc -l` | ~25 |
| **无内容丢失** | `git ls-files '*.md' \| wc -l` | **与移动前完全相同** |
| 全部是重命名 | `git status --porcelain` | 只有 `R`，无 `D` |
| 入口链接可达 | 逐条 `ls` INDEX 与 CLAUDE.md §9 指向的路径 | 全部存在 |
| 生产代码零改动 | `git status --porcelain -- src/ tools/` | 空 |

---

## 不做什么

| # | 不做 | 理由 |
|---|---|---|
| 1 | 不删任何 md | 用户选择「只归档不删」 |
| 2 | 不改归档文档正文 | 历史记录 |
| 3 | 不动 `LESSONS.md` | 用户此前明确要留在根目录 |
| 4 | 不移动 `plans/` | 方案记录保持现状 |
| 5 | 不动生产代码 / 探针 | 本轮纯文档 |
| 6 | 不逐份写横幅 | 避免重复声明再次漂移 |
