# 文档索引

> 本项目有 **113 个** git 跟踪的 md 文件
> （根 9 + docs 28 + docs/design 37 + docs/archive 29 + plans 2 + build 8），
> 其中大部分是历史留痕。
> **只读这张表标记为「必读」的，其他按需查。**
>
> 最后更新：v0.9.93 / 2026-10-27

---

## 第一层：必读（新会话从这里开始）

| 文档 | 行数 | 内容 |
|---|---|---|
| [START-HERE.md](../START-HERE.md) | 215 | **唯一的状态入口**。现状 / 门禁 / 地形 / 能做什么不能做什么 |
| [WORK-DISCIPLINE.md](WORK-DISCIPLINE.md) | 741 | **动手前必读**。13 条编号纪律 + 13 个专题（四-B~四-M） |
| [PARAMETER-MAP.md](PARAMETER-MAP.md) | 164 | 每个参数是否跟随预设，含字段归属表 |
| [NEXT-STEPS.md](NEXT-STEPS.md) | 163 | 当前状态 / 已完成 / 已放弃 / 待办 |

---

## 第二层：按任务查

| 任务 | 文档 | 行数 |
|---|---|---|
| 改物理 / rank / 斜度 | [design/RANK-COLLAPSE-FIX.md](design/RANK-COLLAPSE-FIX.md) | 196 |
| 对齐上游手感 | [design/UPSTREAM-VSCODE-RESEARCH.md](design/UPSTREAM-VSCODE-RESEARCH.md) | 165 |
| 改颜色 / 渐变 / 彩虹 | [COLOR-STATE-DECISIONS.md](COLOR-STATE-DECISIONS.md) | 113 |
| 优化性能（含已证伪方向） | [PERFORMANCE-FPS.md](PERFORMANCE-FPS.md) | 111 |
| 理解 IDEA 平台限制 | [IDEA-PLATFORM-OPTIMIZATION.md](IDEA-PLATFORM-OPTIMIZATION.md) | 260 |
| 发版 | [RELEASE-SAFETY.md](RELEASE-SAFETY.md) | 161 |
| 接手这个项目 | [SKILL-neovide-cursor-plugin-handoff.md](SKILL-neovide-cursor-plugin-handoff.md) | 152 |
| 查历史 bug | [BUG-LOG.md](BUG-LOG.md) | 146 |
| 探针覆盖度怎么补回来的 | [DEAD-PROBE-CLEANUP.md](DEAD-PROBE-CLEANUP.md) | 82 |
| **重构值不值得做 / 优先级怎么排** | [REFACTOR-FEASIBILITY.md](REFACTOR-FEASIBILITY.md) | 266 |
| **设置系统重构分步方案** | [../plans/settings-refactor.md](../plans/settings-refactor.md) | 830 |
| 清理清单与执行记录 | [../plans/cleanup-inventory.md](../plans/cleanup-inventory.md) | 222 |
| 「加参数要改 8 处」的原始论证（**0.9.5 时代，勿当现状**） | [design/NEXT-REFACTOR.md](design/NEXT-REFACTOR.md) | 263 |
| **连续输入时拖尾跳变的修复方案** | [TYPING-JITTER-PLAN.md](TYPING-JITTER-PLAN.md) | 303 |
| VSnap 十条红的根因诊断 | [VSNAP-DIAGNOSIS.md](VSNAP-DIAGNOSIS.md) | 178 |
| 分支该怎么整理（2026-10-27） | [BRANCH-AUDIT-20261027.md](BRANCH-AUDIT-20261027.md) | 281 |
| **做 runtimeSwitch 重构（新会话交接）** | [HANDOFF-REFACTOR-RUNTIME-SWITCH.md](HANDOFF-REFACTOR-RUNTIME-SWITCH.md) | 398 |

---

## 第三层：设计调研（一次性，结论已沉淀）

这些是当时的调研过程记录。**结论已经写进上面第二层了**，
这里保留是为了追溯「为什么这么选」。

| 文档 | 内容 |
|---|---|
| [design/RGB-WASH-REFERENCE.md](design/RGB-WASH-REFERENCE.md) | 上游 dsh-claude-style 的 wash 实现（七层径向 + 双动画） |
| [design/RGB-CURSORSIZE-RESEARCH.md](design/RGB-CURSORSIZE-RESEARCH.md) | 光标尺寸 + RGB 最早的可行性调研 |
| [design/PREVIEW-STRETCH-MISMATCH.md](design/PREVIEW-STRETCH-MISMATCH.md) | 预览窗与真实光标拉伸不一致 |
| [design/MULTICURSOR-*.md](design/) | 多光标系列（已全部实现并验证） |
| [design/SETTINGS-*.md](design/) | 设置面板重构系列 |

---

## 第四层：历史留痕（**不要当现状**）

这些停在 0.9.5 ~ 0.9.33 期间。只保留历史价值。

**【2026-10-27】下列 8 个根目录文档与 `docs/TONIGHT-*.md` 已移入 `docs/archive/`。**
移动方式为 `git mv`，提交历史完整保留。

```
docs/archive/PROGRESS.md              停在 0.9.9，自己标注了「已过时」
docs/archive/VERSIONS.md              停在 0.9.4 附近，但「三个版本陷阱」一节仍有价值
docs/archive/ROADMAP.md               停在 2026-10-21
docs/archive/0.9.8-TASKS.md           当时的任务清单
docs/archive/AUTONOMOUS-TASKS.md      同上
docs/archive/ASSERTIONS.md            早期断言清单
docs/archive/MANUAL-ACCEPTANCE-0.9.5.md   手工验收用例
docs/archive/USER-FEEDBACK-0.9.5.md       用户反馈记录
docs/archive/TONIGHT-*.md             某晚的工作记录
docs/archive/                         归档目录
docs/design/OPTIM-BATCH1.md           1022 行优化批次记录
docs/design/SETTINGS-CONTRACT.md      75.9 KB 契约（0.9.19 时代的权威）
docs/design/SETTINGS-UI-CONTRACT.md   96.5 KB（同上）
```

**一个例外留在根目录**：`LESSONS.md`（612 行，踩坑记录）。
它自称「权威汇总·遇到问题先查这里」，被 `START-HERE.md` / `docs/PROJECT-GUIDE.md`
等 14 个文件当参考链接引用 —— 是活文档，只是内容停在 0.9.5。
部分内容已升级进 `docs/WORK-DISCIPLINE.md`，但**它本身仍然有效，不要归档**。

---

## 维护规则

**新增文档时**：
1. 先想清楚属于哪一层，加进对应表格
2. 写在 `docs/` 下，不要往根目录堆
3. 根目录只留：`README*`（用户看的）+ `START-HERE.md`（开发档案入口）

**文档过时时**：
- 在第一行加 `> ⚠️ 本文停在 vX.Y.Z，当前状态看 START-HERE.md`
- **不要直接删** —— 历史价值往往在于「为什么当时这么决定」