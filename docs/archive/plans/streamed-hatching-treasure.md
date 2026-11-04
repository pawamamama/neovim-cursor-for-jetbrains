# 工作区整理、分支归档与 fork 同步

> 2026-10-28 · 更新计划
>
> **已完成的前置工作**：文档清理、探针索引契约修复、`install.ps1` 入库保护，均已同步到 `D:\deepseekwork\neovim-cursor-pr` 的 `main`，当前两处 HEAD 均为 `384d87e`，且工作区干净。
>
> **本计划接下来只处理 Git 工作区卫生与远端备份；不合入、不删除任何尚未逐条审核的历史功能分支。**

---

## Context

两个问题：

**1. 文档里的状态数字大量失真。** 你说「版本不对吧不是早到93了吗」——我引用了 CLAUDE.md 的 `v0.9.89`，实际是 **0.9.93**。这类错误不是孤例：

- `NEXT-STEPS.md` 写「探针 82 个 / 72 通过」，实际 **91 个文件 / 6 红**
- 这个数字 `CLAUDE.md` 第 293 行**专门批评过**（「那个数字来自一份从未执行过的脚本」），批评完它还在原地
- `START-HERE.md` 写「74/83」「9 个失败」，也不对
- `INDEX.md` 说「40+ 个 md」，实际 **103 个**（含 plans）；标了 18 个行数，**几乎全部过期**

项目自己有一条纪律（CLAUDE.md §11）：「任何数字，自己跑一遍再信」。**这次清理就是把这句执行一遍。**

**2. 同步到主仓库已完成，但 Git 工作区还没有整理。** `D:\deepseekwork\neovim-cursor-pr` 的 `main` 已 fast-forward 到 `384d87e`；我的 worktree 分支也指向同一提交。现在的遗留是：`fork/main` 落后 12 个提交、Git 仍注册了多个不用的 worktree、10 条未并入分支共携带 44 个独有提交，必须按用途分类而不是盲删。

---

## 当前 Git 事实基线（2026-10-28 实测）

本次操作以这组事实为准：

| 项 | 值 | 来源 |
|---|---|---|
| IDEA 已装版本 | **0.9.93** | 读 jar 内 `META-INF/plugin.xml` |
| dist 最新 jar | **0.9.92**（2026-10-27 06:46） | `ls -lt dist/*.jar` |
| git tag 最高 | **v0.9.83** | `git tag --sort=-v:refname` |
| 探针文件数 | **91 个 `V*.java`**（106 个 .java 全部） | `ls tools/settings-probe/V*.java \| wc -l` |
| 探针代码量 | **23,570 行** | `cat tools/settings-probe/*.java \| wc -l` |
| 探针基线 | **6 红**：VTools VHints VCustom VReset2 VSepBound VSleep | 全量门禁 |
| 生产代码 | **34 文件 / 15,164 行** | `find src/main/java -name "*.java"` |
| 构建系统 | **有 Gradle**（git 跟踪 `build.gradle.kts` / `gradlew` / `gradle/wrapper/`） | `git ls-files \| grep gradle` |
| worktree `build/` | **11 个跟踪的 .ps1**（+ 本轮新入 9 个 → 20） | `git ls-files build/` |
| 主仓库 `build/` | **207 MB / 14,609 文件 / 250 目录**（未跟踪） | `du -sh` / `find` |
| 主仓库 `main` | `384d87e`，工作区干净 | `git -C D:/deepseekwork/neovim-cursor-pr status --short` |
| 当前 worktree 分支 | `claude/sleepy-swanson-ffc2cf` → `384d87e`，工作区干净 | `git status --short` |
| fork 距离 | `main` 比 `fork/main` 领先 **12** 个提交、落后 **0** 个 | `git rev-list --left-right --count fork/main...main` |
| origin 距离 | `origin/main` 相对 `main` 领先 6、落后 242，已分叉 | `git rev-list --left-right --count origin/main...main` |
| worktree 注册数 | 13 个；其中 3 个 prunable（目录已不存在） | `git worktree list --porcelain` |
| 带独有提交的分支 | 10 条，共 **44** 个 `main` 之外的提交 | `git rev-list --count main..<branch>` |

---

## 已完成（留档，不重复执行）

1. 通过 fast-forward 把 `claude/sleepy-swanson-ffc2cf` 的文档/探针提交同步到了主仓库 `main`；两者当前同为 `384d87e`。
2. `VDefects` / `VSnap` 已从按 UI 索引定位改为按字段 key 定位，断言语义未删除。
3. `build/install.ps1` 与 release notes 已纳入版本控制；两轮活跃文档的状态数字与失效指引已纠正。

以下旧的“修文档 / 同步主仓库”章节仅作本轮已经完成的变更记录，不作为后续执行步骤。

---

## 第一步：修文档（已完成，保留历史记录）

### 1.1 CLAUDE.md（5 处）

| 行 | 现在 | 改为 |
|---|---|---|
| 56 | `构建  **无 Gradle** —— javac 直接编译 + 手写 plugin.xml` | 说明**两条构建路径并存**：Gradle 已入库（`build.gradle.kts` + `gradle/wrapper/`），日常用 javac + 手写 plugin.xml 更快 |
| 85 | `版本  v0.9.89（本地已装）` | `版本  v0.9.93（本地已装）· dist 最新 0.9.92 · git tag 停在 0.9.83` |
| 90 | `探针  87 个运行 / 8 个失败 / 79 个通过` | `探针  91 个文件 / 6 个失败` |
| 93-100 | `## 那 8 个失败的探针` | 改成 6 个，**并更新名单**（VDefects/VSnap 已修绿） |
| 297 / 370 | `build/ 目录的 150+ 个一次性脚本` | 说明那些脚本**在主仓库磁盘上、未跟踪**；worktree 里只有 11 个跟踪的 .ps1 |

### 1.2 NEXT-STEPS.md（整表重做 + 加教训横幅）

| 行 | 现在 | 改为 |
|---|---|---|
| 3 | `状态：v0.9.87 本地已装` | `v0.9.93` |
| 12 | `版本 v0.9.87` | `v0.9.93` |
| 13 | `探针清单 82 个` | `91 个文件` |
| 14 | `探针通过 72 / 82` | `6 红`（列名单） |
| 18 | `探针代码 ~21725 行 / 84 文件` | `23,570 行 / 106 文件` |

**头部要加一句教训**：它曾写「72/82」，而 `CLAUDE.md` 第 293 行专门批评过这个数字来自从未执行的脚本。要让下一个人一眼看到这个教训，而不是又抄一遍。

### 1.3 START-HERE.md（4 处）

| 行 | 现在 | 改为 |
|---|---|---|
| 3 | `版本：v0.9.87 · 最后更新：2026-10-25` | `v0.9.93 · 2026-10-27` |
| 15 | `技术栈：Java 21，**无 Gradle**` | 同 CLAUDE.md 第 56 行的改法 |
| 20 | `探针 83 个（74 通过）` | `91 个文件（6 红）` |
| 38 | `当前基线：探针 74/83。9 个失败` | `6 红`，名单更新 |

### 1.4 docs/INDEX.md（整表重做）

- 第 3 行：`40+ 个 md` → 实测 **103 个**（根 9 + docs 28 + design 37 + archive 29）
- 第 6 行：`最后更新：v0.9.87 / 2026-10-25` → `v0.9.93 / 2026-10-27`
- 第一层 / 第二层表格的**所有行数**按实测更新（下表的右边是实测值）

```
START-HERE.md                        215（标 205）
WORK-DISCIPLINE.md                   741（标 570）
PARAMETER-MAP.md                     164（标 165）
NEXT-STEPS.md                        163（标 150）
design/RANK-COLLAPSE-FIX.md          196（标 158）
design/UPSTREAM-VSCODE-RESEARCH.md   165（标 168）
COLOR-STATE-DECISIONS.md             113（标 114）
PERFORMANCE-FPS.md                   111（标 112）
IDEA-PLATFORM-OPTIMIZATION.md        260（标 261）
RELEASE-SAFETY.md                    161（标 162）
SKILL-neovide-cursor-plugin-handoff.md  152（标 153）
BUG-LOG.md                           146（标 146 ✓ 唯一正确的）
DEAD-PROBE-CLEANUP.md                 82（标 83）
REFACTOR-FEASIBILITY.md              266（标 200）
TYPING-JITTER-PLAN.md                303（标 290）
VSNAP-DIAGNOSIS.md                   178（标 157）
BRANCH-AUDIT-20261027.md             281（标 280）
HANDOFF-REFACTOR-RUNTIME-SWITCH.md   398（标 320）
```

- 新增 `plans/` 两份文档的条目

### 1.5 不改的

- `docs/archive/**` —— 历史档案，改了就是篡改历史
- 文件名带版本号的（`release-notes-0.9.82.md`、`CODE-AUDIT-0.9.87.md`）—— 描述的是**当时**，不是当前状态
- `docs/design/**` 里的历史契约 —— 同上

---

## 第二步：加防复发机制

**问题的根不是「数字错了」，是「没有东西阻止它再错」。**

在 `CLAUDE.md` 第 11 章加一节「状态数字的自检命令」，把三条命令贴出来：

```bash
# ① 已装版本（不要信文件名，读 jar 内部）
unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" META-INF/plugin.xml | grep -o "<version>[^<]*"

# ② 探针文件数
ls tools/settings-probe/V*.java | wc -l

# ③ 探针基线（唯一权威）
pwsh -File tools/settings-probe/build-v-probes.ps1 -Repo <目录>
```

理由：本项目已知至少 3 次「文档数字来自从未执行的脚本」（NEXT-STEPS 的 72/82、CLAUDE.md 的 v0.9.89、START-HERE 的 74/83）。把命令贴出来，抄之前至少要跑一遍。

---

## 第三步：同步到主仓库 `D:\deepseekwork\neovim-cursor-pr`

### 已确认无冲突（三种方法交叉验证）

```
main            4bbbcfc  ← 主仓库 HEAD，工作区干净
分叉点           4bbbcfc
我的分支         c7565de → c951a65 → abfac92 → 1ea8efc → 1511c24（+ 本轮文档 commit）
main 之后新提交   0 个（git log 4bbbcfc..main 为空）
git merge-base --is-ancestor main <branch>  → 退出码 0
git merge-tree --write-tree main <branch>   → exit 0，无冲突段
```

**纯 fast-forward，结构上不可能冲突。**

### 执行命令（在主仓库做，不要在 worktree 里 checkout main）

```bash
git -C D:/deepseekwork/neovim-cursor-pr merge --ff-only claude/sleepy-swanson-ffc2cf
```

结果：`main` 从 `4bbbcfc` 前进到本轮最新 commit。主仓库工作区被改写——这是快进的一部分，不是覆盖你的改动。

### 合并后主仓库会看到的磁盘变化（预期，不是异常）

1. 根目录 8 个历史 md 消失，出现在 `docs/archive/`
2. `docs/TONIGHT-*.md` 3 个移到 `docs/archive/`
3. 新建 `plans/` 目录（主仓库磁盘上当前不存在）
4. `build/install.ps1` + 8 个 md 从 ignored 变 tracked（内容与磁盘逐字节一致，checkout 不会重写）

### 远端状态（关键：**只能推 fork**）

| 目标 | 状态 |
|---|---|
| `fork/main`（pawamamama） | main 相对它 **+5 / −0**，可快进推送 ✅ |
| `origin/main`（yyyyolo7a79-sketch） | main 相对它 **ahead 235 / behind 6，已分叉**；`origin/main` = upstream 0.5.1 ❌ |

**origin 是上游（upstream 0.5.1），已与本项目完全分叉。推过去是错的。**

正确命令：

```bash
git -C D:/deepseekwork/neovim-cursor-pr push fork main
```

**这一步我不会自动做**——推是对外动作，你点头再执行。

---

## 验证

| 检查 | 命令 | 期望 |
|---|---|---|
| D0 门禁 | `git diff --exit-code -- src/.../core/NeovideConfig.java` | 无输出 |
| 生产代码零改动 | `git status --porcelain -- src/` | 无输出 |
| 探针基线不变 | 全量门禁 | **6 红**，名单一致 |
| 文档数字可复现 | 第二步那三条命令 | 与文档一致 |
| 主仓库同步成功 | 主仓库 `git log -1` | 等于 worktree HEAD |

---

## 不做什么

| # | 不做 | 理由 |
|---|---|---|
| 1 | 不改 `docs/archive/**` | 历史档案 |
| 2 | 不改文件名带版本号的文档 | 它们描述当时 |
| 3 | 不动主仓库 `build/`（207 MB） | 你上次选的 A 类不含它 |
| 4 | 不补 git tag（0.9.84~0.9.93 缺 10 个） | 需逐个确认版本对应的 commit，是独立工作；**会在文档里记下这个缺口** |
| 5 | 不重新打包 / 不改版本号 | 本轮零功能改动 |
| 6 | 不自动推送 | 对外动作 |
| 7 | 不删 worktree | 合并后你确认没问题再删 |

---

## 新的执行方案：按标准 Git 流程整理工作区

### Context

主仓库已经同步，但本机 Git 元数据仍同时保留历史试验、未审查修复和已合并 worktree。目标是把“日常主线”“待评审候选”“可回收工作区”分开：`main` 成为唯一开发入口，`fork/main` 成为可靠远端备份，任何尚未核验的实验提交在删除前都先获得明确去留。

这一步不会改生产代码、不会合并候选功能、不会删除 `build/` 的未跟踪文件。

### 1. ~~推送 main 到 fork~~ —— **本轮不做（网络不好）**

2026-10-28 修正：推送推迟。已实测确认的状态：

```
本地 main       384d87e609bbcd39fb92276f1b4872047e514abb
远端 fork/main  8f58b857c9dd2249814106f362c6ebe273ff267e   ← 落后 12 个提交
```

**风险必须写明**：这 12 个提交（含 `install.ps1` 首次入库、探针索引契约修复、两轮文档纠正）目前**只在这台机器上**，远端没有备份。网络恢复后要执行：

```bash
git -C D:/deepseekwork/neovim-cursor-pr push fork main
```

> 注意：第一次推送尝试被权限分类器当场拦下（不是网络挂起），第二次由用户打断。**没有任何部分推送发生**，`fork/main` 仍是干净的旧值，因此恢复网络后直接普通 push 即可，不需要 `--force`、不是强推。绝不推 `origin`（上游已分叉 6/242）。

<details><summary>原计划的推送前复核步骤（推迟，留档）</summary>

执行前再次读出 `main`、`fork/main` 与工作区状态，确认仍是：

```bash
git -C D:/deepseekwork/neovim-cursor-pr status --short
git -C D:/deepseekwork/neovim-cursor-pr log -1 --oneline
git -C D:/deepseekwork/neovim-cursor-pr rev-list --left-right --count fork/main...main
```

预期是干净、`384d87e`、`0 12`。随后只执行：

```bash
git -C D:/deepseekwork/neovim-cursor-pr push fork main
```

绝不推 `origin`：它是上游 `yyyyolo7a79-sketch`，当前已与本地分叉（6 / 242）。推完以 `git ls-remote fork refs/heads/main` 验证远端 SHA 等于本地 `main`。

</details>

### 2. 清理失效的 worktree 元数据（安全、无工作文件）

先运行：

```bash
git -C D:/deepseekwork/neovim-cursor-pr worktree prune
```

它只移除三个标记为 `prunable` 且目录已不存在的记录：

- `C:/Users/wangw/AppData/Local/Temp/nc-faith`
- `C:/Users/wangw/AppData/Local/Temp/nc-fork`
- `C:/Users/wangw/AppData/Local/Temp/nc-glow8`

这不是删除 worktree 目录——目录早已不存在；只删除 Git 的悬挂登记。

### 3. 回收“已并入 main 且干净”的 Claude worktree

以下 worktree 均指向已经被 `main` 包含的提交，且 `git status --short` 无输出：

| worktree | 分支/状态 | 原因 |
|---|---|---|
| `.claude/worktrees/sleepy-swanson-ffc2cf` | `claude/sleepy-swanson-ffc2cf` → `384d87e` | 当前整理分支，内容已在 main |
| `.claude/worktrees/d0-gate-probe-status-caadda` | `claude/d0-gate-probe-status-caadda` → 已合入 | 只有旧交接文档 |
| `.claude/worktrees/quizzical-raman-7c159c` | `claude/quizzical-raman-7c159c` → 已合入 | 只有旧交接文档 |
| `.claude/worktrees/admiring-ramanujan-0664de` | `claude/admiring-ramanujan-0664de` → `384d87e` | 与 main 相同 |
| `.claude/worktrees/beautiful-raman-bd6c1f` | detached → `384d87e` | 与 main 相同 |
| `.claude/worktrees/strange-cohen-8bec04` | detached → `384d87e` | 与 main 相同 |

操作顺序：从主仓库调用 `git worktree remove <路径>`，每移除一个就 `git worktree list --porcelain` 复核；随后仅删除已没有 worktree 占用的 **零独有提交**分支。当前会话所在的 `sleepy-swanson` 必须最后移除，且只能在本计划执行完、会话切回主仓库后进行。

### 4. 保留并归类 10 条未并入分支；不自动合并、不自动删除

它们共 44 个独有提交，全部不应被“清工作区”顺手清掉。当前用途与建议如下：

| 分支 | 最后日期 | 独有提交 | 实际用途 | 本次处置 |
|---|---:|---:|---|---|
| `claude/typing-jitter-fix-6c8c96` | 10-27 | 1 | 跨行时给前缘动画时长下限，附 `VTypingSpan` 变异测试，试图修连打拖尾跳变 | **保留，另开评审任务** |
| `wip/bad-refactor-631db51` | 10-22 | 1 | 字段表驱动 `apply/dirty/sync` 的第一次重构试验 | **保留，仅供对照** |
| `wip/docs-980377f` | 10-22 | 2 | 上述重构试验 + 旧交接文档 | **保留，仅供对照** |
| `feat/settings-ui-ready` | 10-21 | 29 | 0.9.9 前的面板/物理/预设探索历史 | **保留，先归档说明后再决定** |
| `fix/wakeup-reset-lastnanos` | 10-19 | 2 | 空闲唤醒首帧 dt 修复的旧实现 | **保留，需与现行 `RenderScheduler` 对照** |
| `fix/glow8-stable` | 10-18 | 1 | 8 层辉光与帧统计旧实现 | **保留，现有功能可能已重写替代** |
| `experiment/perf-attempts` | 10-17 | 3 | 位移截断、唤醒重置、编辑后降频的性能试验 | **保留，已有一项补丁等价吸收，其他待核验** |
| `experiment/smooth-and-stretch` | 10-16 | 3 | 平滑档、空闲闪烁、关闭拉伸的试验 | **保留，已被后续设计替代的可能高** |
| `feat/no-stretch` | 10-16 | 2 | 关闭拉伸的旧菜单开关 | **保留，待确认产品上是否还需要** |
| `feat/no-stretch-params` | 10-16 | 1 | 用参数关闭拉伸的更小试验 | **保留，待确认产品上是否还需要** |

对于每条候选分支，后续评审以 `git diff main...<branch>`、现行守门探针和必要的变异测试判断，而不是按提交标题或日期猜测。候选分支既不直接 merge，也不因“旧”而删除。

### 5. 处理两处非 Claude worktree

| 路径 | 当前状态 | 本次建议 |
|---|---|---|
| `D:/deepseekwork/_wt_base` | detached `e3f61e6`，有未跟踪 `t7base.txt` | **不碰**：先确认该文本是否需要留存；不能用 `worktree remove --force` |
| `D:/deepseekwork/nc-upstream-pr` | `fix/wakeup-reset-lastnanos`，干净但分支有 2 个独有提交 | **不碰**：它承载候选修复分支，先做第 4 步评审 |

### 6. 验证与收尾

1. `git -C D:/deepseekwork/neovim-cursor-pr status --short` 仍为空。
2. `git -C D:/deepseekwork/neovim-cursor-pr rev-parse main` 等于 `git ls-remote fork refs/heads/main` 的 SHA。
3. `git worktree list --porcelain` 中不再有 `prunable`，也不再有第 3 步列出的六个已合入 worktree。
4. 所有 10 条有独有提交的分支仍可由 `git show <branch>` 读取。
5. 不运行构建、探针或安装：本次只动 Git 元数据与远端引用，生产源码没有改动；先前 D0 与 6 红基线已在 `384d87e` 验证。

### 已回答的架构问题（不把它混入本次 Git 整理）

- **新增普通滑块的变更点已从历史的 8 个机械链路减少。** 现在 `SettingsField.FIELDS` 自动驱动 apply、判脏、State/Config 双向同步、自定义预设快照与控件回灌。
- **但目标不是 1～2 处。** 预设域且由 Config 消费的滑块仍有约 12 个语义位置（生产代码约 24 行手写，另要更新 6 个探针文件）；设备级 State-only 项约 7 个位置（约 13 行）。默认值、`copyFrom`、`loadState` 的 clamp/迁移、真正的物理消费者与内置预设不属于可安全消除的重复。
- **代码质量两极分化。** 设置数据流与分层依赖较好；动画核心仍有结构债：34 个生产 Java 文件 15,164 行，`CaretAnimator.refreshTarget()` 为 611 行，31 个方法超过 50 行，66 个真实 catch 中 25 个为空/仅注释。下一项代码工作应单独计划为“拆分动画核心 + 明确异常策略”，不能夹带在 Git 清理里。

## 需要你定的两件事

1. **是否按第 1～3 步执行：推送 fork、清掉 3 条 prunable 记录、回收 6 个已合入且干净的 worktree？**
2. **第 4 步 10 条候选分支：是否先按表保留，并把 `typing-jitter` 单独进入代码评审？**
