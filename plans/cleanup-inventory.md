# 清理清单（待你逐项确认）

> 生成于 2026-10-27 · 基于两个探索代理的实测数据
> **本文件只是清单。每一项都要你点头才动。**

---

## 执行结果（2026-10-27 已完成）

你的三个选择：**做 install.ps1 保护** / **A 类（零风险）** / **移到 archive**（LESSONS.md 留根）。

| 项 | 状态 | 提交 |
|---|---|---|
| `install.ps1` + 8 个 release notes 入库 | ✅ 完成 | `c951a65` |
| A1 `build/v2-probe/`（1.9 MB / 259 文件） | ✅ 已删，可重建 | — |
| A2 `verify-state.xml`（1.3 KB） | ✅ 已删，自动生成 | — |
| A3 `D:\deepseekwork\.ctrl-baseline`（14.9 MB / 584 文件） | ✅ 已删 | — |
| 8 个根目录文档 + 3 个 TONIGHT 移入 archive | ✅ 完成，全部 100% 重命名 | `abfac92` |
| `LESSONS.md` | ✅ 按你的决定**留在根目录** | — |
| B 类（主仓库 build/ 207MB） | ⏸️ 本轮不动 | — |
| D 类历史文档 | ✅ 改为移动而非删除 | `abfac92` |

**实际回收**：worktree 2.6M → 2.0M，仓库外 14.9 MB。
**门禁验证**：D0 PASS、6 红名单与清理前逐字一致、`src/` 零改动。

---

## 以下是原始清单（保留作为依据）

---

## 先说三个必须让你知道的发现

### 发现 1：`build/install.ps1` 从未进过 git，删了就永久没了

```
$ git log --all --oneline -- '*install.ps1'
（空输出）
```

CLAUDE.md 第 136 行把它当标准打包入口，但它**只存在于主仓库磁盘上**。
`.gitignore` 的 `build/*` 把它忽略了，而白名单只放行了 `rollback-*.ps1` 和 `offline-build.ps1`。

**这意味着**：换机器、误删、磁盘故障 → 打包能力彻底丢失，git 里找不回来。

**建议先做一件事**（不属于清理，但比清理重要）：把它加进 `.gitignore` 白名单入库。
`!build/install.ps1` 一行的事。**要不要做，你说了算。**

### 发现 2：CLAUDE.md 的「无 Gradle」是过时信息

worktree 根目录有 **git 跟踪的** `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradlew`、`gradlew.bat`、`gradle/wrapper/`。

这些是**正式配置，不能删**。CLAUDE.md 那句话该改。

### 发现 3：`mut-*.ps1` 会覆写生产代码

主仓库 `build/mut-{width,quad,multi,w2,q2}.ps1` 里有：

```powershell
$cfg = "src\main\java\...\core\NeovideConfig.java"
Set-Content -Path $cfg -Value $mutated -NoNewline -Encoding UTF8
```

**它们会直接改写 `core/NeovideConfig.java`** —— 那是 D0 门禁要求零 diff 的文件。
脚本本身跑了会还原，但一旦中途失败/中断，生产代码就停在被改坏的状态。

清理它们**反而是降低风险**。

---

## A 类：可 100% 安全重建（建议清理）

这些全部未被 git 跟踪，且**删掉后一条命令就能重新生成**。

| # | 对象 | 大小 | 证据 | 重建方式 |
|---|---|---|---|---|
| A1 | `build/v2-probe/`（worktree） | 1.9 MB / 259 文件 | `git status --porcelain --ignored` 标 `!!`；`.gitignore:4 build/*` | 跑一次 `build-v-probes.ps1` 即重建 |
| A2 | `verify-state.xml`（worktree 根） | 1.3 KB | `.gitignore:44 *-state.xml` | 探针运行时自动生成 |
| A3 | `D:\deepseekwork\.ctrl-baseline` | 17 MB / 584 文件 | **不是 git 仓库**，无 `.git`；内容 = `4bbbcfc` 快照 + 我上轮跑出的 v2-probe | 无需重建（是我上轮的对照实验残留） |

**风险**：零。A3 是我自己造的垃圾，A1/A2 是构建缓存。

> 注：`plans/` 目录（`settings-refactor.md` + 本文件）也是 `??` 未跟踪，但**不删** —— 那是交付物，只是提醒你要提交。

---

## B 类：主仓库 `build/` 的一次性产物（建议清理，但清单较长）

主仓库 `D:\deepseekwork\neovim-cursor-pr\build\`：**207 MB / 14,609 文件 / 250 个顶层子目录**。
全部**未跟踪**（被 `build/*` 忽略），所以删除**不进 git 历史**——但也**无法从 git 恢复**。

按证据强度分六组：

### B1. 临时编译/探针目录 —— 118 个 `tmp-*`，证据最强

名字就叫 tmp，内容是临时探针输出。

```
tmp, tmp-45, tmp-43, tmp-43b, tmp-back, tmp-bc2, tmp-blk..., tmp-c..., tmp-cb...,
tmp-cd..., tmp-cf..., tmp-cmt..., tmp-d..., tmp-dd..., tmp-diag..., tmp-dir,
tmp-dl, tmp-f..., tmp-fi, tmp-fix..., tmp-fp, tmp-fps, tmp-fs..., tmp-g...,
tmp-grp..., tmp-h..., tmp-hint, tmp-idx, tmp-k, tmp-lay, tmp-lg..., tmp-line...,
tmp-mc..., tmp-mg..., tmp-mv, tmp-np..., tmp-nv..., tmp-ok, tmp-ord, tmp-p0,
tmp-pf, tmp-pv, tmp-pv2..16, tmp-rb..., tmp-rel, tmp-reo2, tmp-ret, tmp-rg...,
tmp-rv, tmp-s1..., tmp-sc, tmp-sel, tmp-split, tmp-st, tmp-t1..., tmp-t3,
tmp-txt, tmp-uni, tmp-ver, tmp-w
```

### B2. 版本化的临时目录 —— 39 个 `v-*` + 11 个 `p-*`

`v-mut`(14 MB)、`v-baseline`、`v-order-exp`、`v-snap-1`、`v-smoke` 等；
`p-audit`、`p_new`、`p_pr` 等。都是某一次排查的产物。

### B3. 历史验证/审计目录 —— `verify-*`、`audit-*`、`t*-verify`、`t*-scratch`、`probe-*`、`scratch-*`

`probe-t7`(9 MB)、`probe-4bugs`(6 MB)、`audit-t3`(5 MB)、`verify-t4`(3 MB) 等。

### B4. 大体积性能记录 —— `jfr/`(66 MB) + `neovide.jfr`(11 MB) + `jfr-exec.txt`(2.3 MB)

JFR 是 Java 飞行记录。**这类数据只在排查当时有用**，事后无法复现同样的运行。

### B5. 一次性脚本 —— 145 个顶层 `.ps1`（抽样 8 个已确认）

| 家族 | 数量 | 证据 |
|---|---|---|
| `cb*.ps1` | 31 | 每个写死版本号 0.9.19~0.9.47 + 绝对路径 + AppData 路径 |
| `i[0-9]*.ps1` | 12 | 写死 0.9.50~0.9.61 |
| `inst*.ps1` | 5 | 写死 0.9.17~0.9.33 |
| `snap*.ps1` | 6 | **已失效**：引用不存在的 `build\tmp-pv11`、`build\pv-run` |
| `dbg*.ps1` / `d5*.ps1` / `v-*.ps1` / `mut-*.ps1` 等 | 其余 | 一次性调试/变异测试 |
| `install-final.ps1` / `install-44.ps1` | 2 | 旧版安装器，`install-final` 写死 0.9.16 |

时间分布佐证：**145 个顶层 .ps1 里 108 个是 2026-10-24 单日生成**。

### B6. 杂项文件

- 约 90 个 `msg-*.txt`（commit message 模板）
- 约 50 个 `t9-*.txt`（探针输出快照）
- 17 个 `*.log`
- 9 个 `.png` 截图
- 9 个散落 `.java`（`NeovideConfig-main.java`、`VOverride-a8.java`、`Zz*.java` 等）
- 3 个源码备份（`VGradAllPresets.java.bak` 等）
- 4 个 0 字节文件

---

## C 类：必须保留，一个都不能动

### C1. git 跟踪的正式资产（worktree `build/`，11 个）

```
build/offline-build.ps1          ← 打包核心
build/rollback-*.ps1 × 10        ← 版本回退
```

### C2. 主仓库 `build/` 里不可恢复的（未跟踪，但只有一份）

| 文件 | 大小 | 为什么不能删 |
|---|---|---|
| **`build/install.ps1`** | 5.7 KB | **从未进 git**，删了永久丢失（见发现 1） |
| `build/*.md` × 8 | 约 30 KB | release notes / issue 正文，发布用 |

### C3. 根目录空目录 / 嵌套 git 仓库

- `build/c-audit/gate/gr/` 和 `gr2/` —— **是独立 git 仓库**（各带 `.git`），删之前要单独确认
- 17 个空目录 —— 删了无害，但收益也接近零

---

## D 类：历史文档 —— 我不建议删，但你决定

`CLAUDE.md` 第 375-385 行点名说这批「停在 0.9.5 ~ 0.9.33，不要当现状」：

| 文件 | 行数 | 最后提交 |
|---|---|---|
| `PROGRESS.md` | 162 | 2026-10-27 |
| `VERSIONS.md` | 390 | 2026-10-25 |
| `ROADMAP.md` | 130 | 2026-10-25 |
| `LESSONS.md` | 612 | 2026-10-20 |
| `0.9.8-TASKS.md` | 111 | 2026-10-21 |
| `AUTONOMOUS-TASKS.md` | 105 | 2026-10-21 |
| `ASSERTIONS.md` | 99 | 2026-10-21 |
| `MANUAL-ACCEPTANCE-0.9.5.md` | 351 | 2026-10-20 |
| `USER-FEEDBACK-0.9.5.md` | 174 | 2026-10-20 |
| `docs/archive/*` | 8,120 | 2026-10-23/24 |
| `docs/TONIGHT-*.md` × 3 | 347 | 2026-10-25 |

合计 **约 10,600 行**。

**为什么我建议不删**：

1. 它们**全部是 git 跟踪的**。删 = `git rm` = 进历史 = 影响所有分支和 worktree。
2. `docs/archive/` 恰恰是「已经归档过一次」的产物 —— 再删就是删档案。
3. `LESSONS.md`(612 行) 是这个项目最贵的资产，**教训不该被清理**。
4. 项目已有先例：`docs/DEAD-PROBE-CLEANUP.md` 记着「84 个探针里 35 个从不运行，但**不能直接删**，因为它们覆盖有效功能」。

**如果你要减少噪音**，我建议的替代方案是**移到 `docs/archive/` 下**（一次性 `git mv`，历史保留，根目录清爽），而不是删除。

---

## 我建议的执行顺序

```
第 0 步  （保护）给 .gitignore 加 !build/install.ps1，把它入库 ← 最重要
第 1 步  A 类全清（零风险，回收 ~19 MB）
第 2 步  B1+B2+B4 （tmp/v-*/jfr，回收 ~100 MB，证据最强）
第 3 步  B3+B5+B6 （verify/audit/probe/脚本/杂项，回收 ~90 MB）
第 4 步  单独问你：C3 的嵌套 git 仓库、D 类历史文档
```

每一步我会：**先列出精确文件清单 → 你确认 → 执行 → 报告实际回收量**。
不会一次全删。

---

## 需要你回答的问题

1. **第 0 步做不做**（把 `install.ps1` 入库）？
2. **A 类**能全清吗？
3. **B 类**清到哪一组？B1+B2+B4 / 还是 B 全清？
4. **D 类历史文档**：删、移到 archive、还是不动？
5. 主仓库 `build/` 的清理要不要做？（它是**另一个工作目录**，不在我这个 worktree 里）
