# VERIFY-BATCH1.md —— 「零观感优化·批次1」独立验证报告（t8 独立验证工程师）

> 生成：2026-10-18 晚
> 验证者：**独立验证工程师**（子代理）。**未参与 t2/t3 实现，未参与 t7 修复。**
> 契约：`OPTIM-BATCH1.md`（FROZEN，SHA256 见 §1）　基线：`git HEAD = 321b0a0`
> 原始输出：`diag/verify-batch1-raw/`（本报告每个数字都能在那里找到出处，且可原样复跑）

**边界声明**：我只做「跑命令 + 读代码 + 读输出 + 写报告」。**未修改 `src/**`**、未安装 jar、未重启 IDEA、未删除或移动任何既有文件。所有「改代码做对照」的实验都在 `%TEMP%` 下的**副本**里做（§6.5 / §6.6），并在实验后复核过 `src/` 哈希未变。

**文件争用说明（必须留痕）**：`VERIFY-BATCH1.md` 在本报告落盘前已被 captain 改写两次（21:45 版 11,810 B、22:10 版 15,646 B）。两版都**原样复制留档**，未删除：

- `diag/verify-batch1-raw/VERIFY-BATCH1.prior-captain-draft.md`（21:45 版）
- `diag/verify-batch1-raw/VERIFY-BATCH1.captain-draft-2210.md`（22:10 版，SHA256 `8816A74B…`）

本文件是**独立验证者自己的版本**。与 captain 22:10 版的**数字差异**见 §10（不是我俩谁抄错，是各自跑出了不同基线值，需要 captain 决定采信哪一组）。

---

## 0. 结论（只写证据支持的）

**可以断言（有可复现证据）**：

1. **物理/几何数值轨迹逐位一致** —— 三份 harness 在基线与新版下输出**字节完全相同**（三项 SHA256 全等，`git diff --no-index --exit-code` 三项 exit 0）。§2
2. **观感常量未改** —— 契约 §3.3 的 **47 项**签名清单，基线输出哈希与契约冻结值**逐字符吻合**（`04D93CAC…`）；唯一 diff 是 `smoothedDt` 那一行按契约**预期消失**（T0-3 方案 A）。§3.2
3. **7 个死字段清零** —— 新版 `src/**` 命中 **0**，基线命中 **7**；且已排除「假阴性」，对照有效。§3.3
4. **EDT 任务粒度未变** —— `invokeLater` 全仓唯一调用点，位于 per-animator 循环体内；`new Thread` 全仓 1 处；`runDue`/`FRAME_PENDING` 全仓 0。运行期实测 3 个同时到期 → **3 个按实例归因**的独立投递。§4
5. **线程生命周期达标** —— N 编辑器 → 1 条；dispose 全清 → 0；再注册 → 重建 1 条；再 dispose → 0（连续两轮，线程字段同步回 null / 非 null）。§5
6. **t7 修复有效且可独立复现** —— 我在副本里还原了旧写法，实测 R2 44/45、R4 36/37、R5 47/48、R6 47/48；当前代码为 R2 57、R4 57、R5 60、R6 59。**回退被消除。** §6.5

**不能断言（证据不足，不要当成结论）**：

7. **不能断言「手感一定没变」。** 帧率只能说明「没有系统性大幅回退」；见第 8 条。
8. **不能断言 R2/R4 的差异是「噪声」。** base 与 new 两边方差都接近零，−2 帧是**稳定复现的系统性差异**（约 −3.4%）。它落在「≤3 帧」判定线内（**达标**），但把它写成「噪声范围内」会掩盖真实偏移。§6.3、§6.6 还给出了机制定位。
9. **不能断言「完全等价」。** R1/R3 在新版下**系统性变快** +12~+14 帧（173~176 → 187~188，约 +7%~+8%）。契约允许（只禁变慢），但这是真实行为改变。
10. **不能断言真机表现。** 全部帧率数字来自 headless + `Proxy` 伪造 `Editor`，`tick=25ms` 是人为注入的固定耗时。

---

## 1. 绑定字节（`Get-FileHash -Algorithm SHA256`）

| 对象 | 大小 | SHA256 |
|---|---|---|
| `OPTIM-BATCH1.md` | 64,011 B | `13B028034F47B69DD145107AE6113A747CD45766971912BF3748619588110EF7` |
| `src/.../ui/RenderScheduler.java` | 8,605 B | `A89BE4256BF7533D9254575168FA9EB08766DCE0EA9CFF682474B07642099292` |
| `src/.../ui/CaretAnimator.java` | 53,835 B | `847CF6D8CAFCCB9F7265D086DC9D472427519F62152878DAA799DB2E0D00A8B2` |
| `src/.../ui/CaretTrailPanel.java` | 16,673 B | `281C5FEC08BA6D8761D223598CAF89E97B0A3207530D2245A650683B69F88B61` |
| `src/.../core/NeovideConfig.java` | 12,720 B | `86587EE4FBD1C8776739AD8122614FC2AD42C2388AACF433AF690824EECD1876` |
| **`git diff -- src` 存盘**（`diag/verify-batch1-raw/git-diff-src.patch`） | 26,364 B / 542 行 | `1F5DEE707D929221903F930DFEF6E841488AE0141AC21B4A73F070BC793DE371` |
| `dist/_verify-repair.jar` | 42,079 B | `C718675E046CAB820E7EC6F5AEEE1B647C06DDF829BCF5837B185AD2BE29CB62` |
| **`dist/_verify-tier1.jar`（dist 里最新的已验证 jar）** | 42,079 B | `2B3E5CBE206E3A6A7E8EA95039A197B7C09DB91723D4735F6AC76A2279061A93` |
| `dist/jetbrains-neovide-cursor-0.9.1-zeroopt.jar` | 42,079 B | `C718675E046CAB820E7EC6F5AEEE1B647C06DDF829BCF5837B185AD2BE29CB62`（与 `_verify-repair.jar` **同哈希**，是同一个 jar 改名） |
| `dist/jetbrains-neovide-cursor-0.9.0-ahf-default.jar`（**回退目标**） | 39,937 B | `FA161E4F77C8C99992381DD489F7C172A38F56BB62DC3C2D45D8856AD8C95CD5` |

### 1.1 jar ↔ 源码一致性（额外核验；决定「最新验证 jar」是否可信）

- `_verify-tier1.jar` 与 `_verify-repair.jar` **文件哈希不同**，但解包后按「条目路径 + 条目内 SHA256」排序拼接再求哈希，**结果相同**（`8706E02AB6FDD1EA489ABDE31C6BF15F5B2CFE35F518B42A425848115D138CD3`）。差异只来自 zip 内时间戳，**内容一致**。
- 我用当前 `src/` 重新 `javac --release 21 -g` 编译出 16 个 class，与 `_verify-tier1.jar` 内同名 class **逐对比对：16/16 SAME，差异 0**。→ jar 字节码确实来自当前工作区源码。
- `_verify-tier1.jar` 内 `META-INF/plugin.xml`：`<version>0.9.1-zeroopt</version>`、`<id>com.github.yyyolo7a79.neovidecursor</id>`。
- **时间戳容易误导**：`_verify-repair.jar`（21:32）与正式名 `0.9.1-zeroopt.jar`（同哈希）都**早于** `_verify-tier1.jar`（21:46:24）。既然内容一致就不构成矛盾，但仅按 `LastWriteTime` 判「最新」时应取 **`_verify-tier1.jar`**。

---

## 2. parity：物理/几何逐位一致

命令（原样执行；完整输出 `diag/verify-batch1-raw/01-parity.txt`）：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1"
```

脚本用 `git archive` 把基线 `321b0a0` 的 `src/main/java` 导出到 `%TEMP%\nc-t4-parity`（**不碰工作区**），分别编译基线（**13** 个 .java）与新版（**14** 个 .java），用同一份 harness 各跑一次，再逐字节比对。classpath = `idea-libs` 的 **381** 个 jar（长度 27,214 字符，低于 Windows 32,767 上限）。

| harness | 行数 | base SHA256 | new SHA256 | 判定 |
|---|---|---|---|---|
| `PhysicsParity` | 72 | `0A4FDDE69C3EBC32DF23AEF17D8EAFF15CDA0B25E5E04152B7076AB8C1003883` | **完全相同** | PARITY OK |
| `GeometryParity` | 25 | `4057AFA03E768C842990FD4B7B4B6D5AF7ED46830A848E8ED99900FFFB526B44` | **完全相同** | PARITY OK |
| `TrailParityTest` | 158 | `409C5728506FF36D648559D764AB288E7B55753DA92D6251BD44E81F756A08C7` | **完全相同** | PARITY OK |

- `git diff --no-index --exit-code`：三项全部 **exit 0**（diff 为空）。
- 前两项 base 哈希与契约附录 B-1 冻结值**逐字符相同** → harness 与基线快照均未被改动。
- 脚本最终打印 `ALL PARITY OK`，整体 exit 0。
- 备注：`git diff` 对 CRLF 只发 `warning: CRLF will be replaced by LF`，不影响 exit code。

**覆盖面**（决定这份证据有多强）：`PhysicsParity` 用 `Double.doubleToRawLongBits` 输出十六进制位模式，覆盖短跳/中文删除/跨行大跳/目标不变，`dt` 遍历 8 / 16.7 / 33.3 / 50（`MAX_DT`）/ 120ms（recovery 边界）；`GeometryParity` 含**四角重合**与**零长边**两个退化用例，且每个用例**连调两次**（第二次专门暴露「复用未清零」的残留状态）；`TrailParityTest` 为补充场景。

---

## 3. 常量审计

命令（原样执行；完整输出 `diag/verify-batch1-raw/02-audit.txt`）：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-audit.ps1"
```

### 3.1 19 项观感常量（`run-audit.ps1` A 段）

**19 项中 DIFF 数 = 0**，逐项 SAME：

```
glowLayers=8   glowOpacity=0.55f   shadowBlurFactor=0.2f   trailFactors={1.0f,0.72f,0.42f,0.22f}
animationLength=0.1f   shortAnimationLength=0.11f   trailSize=1f   typingMaxChars=3f
cursorWidth=2f   maxTrailDistanceFactor=60f   animationResetThreshold=0.09f   afterimageLifetime=0.22f
alwaysHighFps=true   hideNativeCaret=false
ACTIVE_DELAY_MS=8   IDLE_DELAY_MS=120   IDLE_UNFOCUSED_DELAY_MS=600   MAX_ADAPTIVE_DELAY_MS=40
MAX_DT=1.0/20
```

> 这 19 项是 `run-audit.ps1` 自带清单，**不等于**契约 §3.3 附录 B-3 的 47 项。47 项我另外单独跑，见 §3.2 —— 因为契约 ③-a 的正式口径是 47 项。

### 3.2 契约附录 B-3 的 47 项常量清单（正式口径，我补跑的）

做法：把 `OPTIM-BATCH1.md` **L859–L915** 的脚本逐行原样抽到 `diag/verify-batch1-raw/const-check.ps1`（57 行；与契约原文**逐行比对差异数 = 0**，已核）、对基线与新版各跑一次并重定向比对。

| 项 | 结果 |
|---|---|
| 基线输出 | 47 行，SHA256 = `04D93CAC07A72496A6BD5542CC0B40A4371503765BB6A16EE659F7573E7CFB04` ← 与契约冻结值**完全吻合** |
| 新版输出 | 47 行，SHA256 = `F3BDF876267597A49EC03B342E40AF6E14EFFBAD0D4A4CBCA894D793CF55DC18` |
| `git diff --no-index --exit-code` | **exit 1（1 处差异）** |
| 差异全部内容 | `^\s*smoothedDt \+= \(dt - smoothedDt\) \* [0-9.]+`：基线 `smoothedDt += (dt - smoothedDt) * 0.25;` → 新版 `<未匹配>` |

**判定：符合契约，不算常量改动。** 契约 §3.3 ③-a 末尾明确写了「T0-3 若采用方案 A，该行会消失……两种情况都必须在报告里显式列出『预期消失/保留的行』」。本节即该记录：**预期消失且仅消失 1 行 = `smoothedDt += (dt - smoothedDt) * 0.25;`**，其余 **46 行逐字符未变**。

T0-3 确实按方案 A 落地（我逐项查过）：`LOG.info` 次数 = **0**；`assignRanks` 已是 `private void assignRanks()`（L1111）、调用点 `assignRanks()`（L939）无参；`tickSpring` 已是 `private boolean tickSpring(double dt)`（L937）、调用点 `tickSpring(dt)`（L616）。`effectiveDt` 在 `src/**` **完全消失**；`smoothedDt` 仅剩 1 处且在 `core/NeovideConfig.java:76` 的 javadoc 举例文字里（非代码引用）。

### 3.3 7 个死字段（T0-4）

| 检查 | 结果 |
|---|---|
| 新版 `src/**` 命中 | **0** |
| 基线 `321b0a0` 的 `src/**` 命中 | **7**，全部在 `NeovideConfig.java` 声明处：L101 / L104 / L199 / L203 / L208 / L212 / L233 |
| 全仓库（含文档、.class、备份）命中 | 366（分布于 `_backup-*`、`build/**/*.class`、`README*`、`HANDOFF*`、`OPTIM-BATCH1.md`、`UPSTREAM-PITFALLS.md` 等；**均非 `src/**` 代码引用**） |

被删字段（7 个全中，新版零残留）：`cursorDisappearDelay`、`cursorFadeOutDuration`、`shortJumpMaxChars`、`typingMergeChars`、`useHardSnap`、`leadingSnapFactor`、`snapAnimationLength`。

> **我特意排除了「假阴性」**：同一 grep 在基线上命中 7，证明匹配式本身有效。这点很关键 —— 存在一种**不报错但恒返回 0 命中**的写法会让人把「基线 0、新版 0」误当成功，详见 §8。

### 3.4 新增数值常量的白名单归属（C 段 + 人工归属）

| 白名单 | 常量 | 位置 | 契约允许 | 判定 |
|---|---|---|---|---|
| W1 | `STATS_WINDOW_NANOS = 5_000_000_000L` | `CaretAnimator.java:241` | 窗口 ≥5s | ✅ |
| W2 | `REFRESH_TARGET_SLOW_NANOS = 2_000_000L` | `CaretAnimator.java:797` | 新增 >2ms 埋点阈值 | ✅ |
| W3-1 | `MAX_SLEEP_MS = 600` | `RenderScheduler.java:40` | ≤600ms | ✅ |
| W3-2 | `BUSY_RETRY_MS = 1` | `RenderScheduler.java:55` | 0 或 1ms | ✅（代价见 §6.6） |
| W3-3 | `DUE_TOLERANCE_NANOS = 1_000_000L` | `RenderScheduler.java:46` | ≤1ms | ✅ |

`RenderScheduler.java` 中**没有**其它数值常量参与动画/物理/几何计算：C 段列出的数字，要么在注释里，要么是上述 3 个 W3 常量，要么是 `1_000_000L` 的 ns↔ms 换算。

### 3.5 T0-1 / T0-5 静态判定（A 段之外我另查的）

| 契约要求 | 实测 |
|---|---|
| `CaretAnimator` 中 `LOG.info` 次数 = 0 | **0** ✅ |
| fps 日志被 `LOG.isDebugEnabled()` 包裹，`String.format` 在门控内 | `L709 if (LOG.isDebugEnabled()) { … L717 LOG.debug(String.format(…)) }` ✅ |
| 编辑器标识追加在**行尾**（P0-8），前六个字段顺序不变 | `… delay=%dms  editor=%s#%d` ✅ |
| T0-5 埋点被短路 | `L899 if (LOG.isDebugEnabled() && costNanos > REFRESH_TARGET_SLOW_NANOS)` ✅ |
| **字节码级复核**（我额外做的） | `javap -p -c` 的 `logSlowRefresh` 前几条指令即 `invokevirtual Logger.isDebugEnabled()Z` + `ifeq`，`String.format` 在分支内 ✅ |

**P0-8 的实证（不只是看源码）**：按新格式串真实渲染一行

```
neovide-cursor: fps=125.3  frames=626  window=5000ms  maxGap=12ms  peakRepaintArea=1024px  delay=8ms  editor=MAIN_EDITOR#1234567
```

喂给既有两个解析器的**原正则**：

- `build/analyze-log.ps1:8` 的正则 → **匹配 True**，取出 fps=125.3 / frames=626 / window=5000 / maxGap=12 / area=1024 / delay=8
- `diag/extract.py` 的 `pat_new`（补上 `idea.log` 时间戳前缀）→ **匹配 True**，7 个分组齐全

→ 行尾追加字段**没有**破坏既有证据链；且这两个脚本本批未被改动（`git status -- src/` 与 `git diff --name-only` 均不含）。

### 3.6 T0-2 每帧分配复用（字节码级复核，我做的）

对比基线与新版 `CaretTrailPanel.drawQuadGlow` 的方法体指令：

| 分配点 | 基线 | 新版 |
|---|---|---|
| `double[] dirX = new double[4]` | `newarray double` | **消失**（改为 `getfield dirX`） |
| `double[] dirY = new double[4]` | `newarray double` | **消失** |
| `Path2D.Double path = new Path2D.Double()` | `new Path2D$Double` | **消失**（改为 `getfield glowPath`） |
| `path.reset()` 位置 | `invokevirtual Path2D$Double.reset()`，循环内 | `getfield glowPath` + `reset()`，**仍在循环内** ✅ |
| `g2.fill(path)` | — | `getfield glowPath` + `fill(Shape)` ✅ |
| `new Color(...)` | 存在 | **仍存在** —— 契约 2.2 明令本批不做 ✅ |
| `computeOffsetDirections` 的 `nx/ny` | `newarray double` ×2 | **仍是** `newarray double` ×2 —— 契约 A2 保守方案 ✅，P0-6 天然满足 |

→ 每帧净减 **3 次分配**（2 个 `double[4]` + 1 个 `Path2D`）；几何算式与 `reset` 位置未变。`dirX/dirY` 复用的安全性由 `GeometryParity`（含四角重合 / 零长边，且每个用例连调两次）**逐位一致**佐证。

---

## 4. 调度语义：EDT 任务粒度未变

### 4.1 结构判定（契约 §3.2 ②-a 的三条通过条件）

| 检查项 | 实测 | 判定 |
|---|---|---|
| `invokeLater(` 真实调用点数 | **1** —— `RenderScheduler.java:182` `SwingUtilities.invokeLater(animator::runTick)` | ✅ |
| 是否在「遍历到期 animator」的循环体内 | **是** —— 循环头 `L160 for (CaretAnimator animator : ANIMATORS)`，调用在 L182 | ✅ |
| Runnable 体内是否只服务一个 animator | **是** —— 方法引用 `animator::runTick`；`runTick()`（`CaretAnimator.java:375-386`）只碰 `framePending`/`disposed`/`tick()`/`scheduleNextDue()`，**不含** `for`/`forEach`/第二个 animator | ✅ |
| `invokeLater(RenderScheduler::` | **0**（未照搬上游批量 `runDue`） | ✅ |
| `runDue` / `FRAME_PENDING`（`RenderScheduler` 内 / 全 `src/**`） | **0 / 0** | ✅ |

**口径澄清（否则容易被误读）**：`run-audit.ps1` E-1 打印 `invokeLater( 出现次数（含注释） = 1`，但用 `-Pattern 'invokeLater'`（不带括号）会得到 **5** —— 因为类注释里还有 4 处正文引用（L17 / L96 / L153 / L170）。**真实调用点仍是 1 个**，注释不参与编译。同理 `RenderScheduler` 里 `'for ('` 命中 2、`forEach` 命中 1，全部位于 `loop()` / `dispatchDue()` 的**调度线程**代码或注释中，**不在**被 `invokeLater` 的 Runnable 体内。

### 4.2 `new Thread` 与防堆积标志

| 检查项 | 实测 |
|---|---|
| 全 `src/**` 的 `new Thread` | **1**（`RenderScheduler.java:70`）；基线也是 1（`CaretAnimator.java:331`，但那是**每个 animator** 一条） |
| 线程名 | `neovide-cursor-render`（沿用基线命名，便于线程枚举取证） |
| 防堆积标志 | `CaretAnimator.framePending` = `final AtomicBoolean`（L193，**实例字段，非 static**）；`RenderScheduler` 用 `animator.framePending.compareAndSet(false, true)`（L175），无全局标志 ✅ |
| `wakeUp` 顺序（P0-7） | `wakeUp()` L100 先 `animator.nextDueNanos = System.nanoTime();`，L103 后 `t.interrupt();` ✅ |

### 4.3 运行期计数（契约 §3.2 ②-c）

三个探针都用「往系统 `EventQueue` 前压计数队列 + 反射读 `InvocationEvent.runnable` 的捕获字段，把任务归属到具体 `CaretAnimator` 实例」来计数，因此是**按实例**验证，不是只数总数。4 轮原始输出见 `diag/verify-batch1-raw/run1..run4/`。

**3 个同时到期 → 3 个独立投递**（`T1SchedulerProbe` 场景 A，**4 轮完全一致**）：

| key | run1 | run2 | run3 | run4 |
|---|---|---|---|---|
| `A_posts_3simultaneous` | 3 | 3 | 3 | 3 |
| `A_distinct_animators` | 3 | 3 | 3 | 3 |
| `A_posts_a1` / `a2` / `a3` | 1/1/1 | 1/1/1 | 1/1/1 | 1/1/1 |
| `A_threads` | 1 | 1 | 1 | 1 |

批量合并写法只会得到 `A_posts_3simultaneous = 1`、`A_distinct_animators = 1`。**实测 3 / 3 → 粒度未变。**

**其它边界（4 轮结论一致）**：

| 场景 | 期望 | 实测 |
|---|---|---|
| B 只有 a1 到期（a2/a3 在 10s 后） | 只投 1 个 | `B_posts_only_a1_due=1`，`B_posts_a1=1`，`a2=0`，`a3=0` ✅ |
| C 到期值长期不变（过期 1s、任务永不执行），800ms 窗口 | 恰好 1 次 | `C_posts_stale_due_800ms=1`，`C_lastDispatchedDueNanos_equals_stale=true` ✅ |
| D 三个全部远超期（5s） | 各 1 个且不重复 | `D_posts_all_overdue=3`，`D_distinct_animators=3`，再等 600ms 仍 3 ✅ |
| E 建→退→再建→再退 | 1→0→1→0 | `E_threads_3animators=1` / `after_dispose_all=0` / `after_register_again=1` / `after_second_dispose=0`；`E_thread_field_after_exit=null`、`after_rebuild=true`、`after_second_exit=null` ✅ |

**基线对照（`DispatchCountProbe`，两棵树都能编译）**：

| key | base（4 轮） | new（4 轮） |
|---|---|---|
| `phase1_threads_3animators` | **3** | **1** |
| `phase1_posts_3animators_noEDT` | 3 | 3 |
| `phase1_distinct_animators_posted` | 3 | 3 |
| `phase2_threads_after_dispose_all` | 0 | 0 |
| `phase3_posts_while_EDT_blocked` | 1 | 1 |
| `phase3_posts_per_animator_while_blocked` | 1 | 1 |
| `phase3_resumed_ok` | true | true |
| `phase6_threads_at_end` | 0 | 0 |

→ 唯一差异就是**3 条线程 → 1 条**；投递语义（含「EDT 被 700ms 长任务阻塞时不超发、阻塞结束后恢复」）两边一致。

---

## 5. 运行期线程生命周期（任务要求四项）

| 要求 | 实测 | 出处 |
|---|---|---|
| N 编辑器 → **1** 条 | base 3 → new **1** | `run*/base-dispatch.txt` / `new-dispatch.txt` 的 `phase1_threads_3animators`；`new-t1scheduler.txt` 的 `A_threads=1`、`E_threads_3animators=1` |
| dispose 后 **0** | **0** | `phase2_threads_after_dispose_all=0`；`E_threads_after_dispose_all=0` + `E_thread_field_after_exit=null` |
| 再注册**重建** | **1**，线程字段非 null | `E_threads_after_register_again=1` + `E_thread_field_after_rebuild=true` |
| 再 dispose 再退 | **0**，字段回 null | `E_threads_after_second_dispose=0` + `E_thread_field_after_second_exit=null` |
| 3 个同时到期 → 3 个独立投递 | **3 / 3 distinct / 各 1** | §4.3 |

---

## 6. 帧率矩阵（`RegimeProbe`）

### 6.1 探针口径

每格 = **1500ms 窗口内被投递、且捕获了该 animator 的 `InvocationEvent` 数**（≈实际帧数）。

| 格 | `alwaysHighFps` | 单帧 EDT 成本 | 附加 |
|---|---|---|---|
| R1 | true | `tick=0ms` | — |
| R2 | true | `tick=25ms` | — |
| R3 | false（自适应） | `tick=0ms` | — |
| R4 | false | `tick=25ms` | — |
| R5 | true | `tick=25ms` | 每 100ms 一次 caret 事件（持续打字） |
| R6 | false | `tick=25ms` | 每 100ms 一次 caret 事件 |

`tick=25ms` 来源：`RegimeProbe.fakeEditor` 在每次 `CaretModel.getVisualPosition()` 里 `Thread.sleep(tickSleepMs)`。**人为注入的固定耗时，不是真实卡顿分布。**

### 6.2 逐次表（`run-probe.ps1` 共跑 4 次）

每次都是**完整的**一次 `run-probe.ps1`（重新导出基线源码 → 重新编译两棵源码树 → 重新编译探针 → 跑 base 与 new），**不是同一进程跑两遍**。

| 轮次 | 树 | R1 | R2 | R3 | R4 | R5 | R6 |
|---|---|---|---|---|---|---|---|
| run1 | base | 174 | 59 | 176 | 59 | 59 | 58 |
| run1 | new | 188 | 57 | 187 | 57 | 59 | 59 |
| run2 | base | 174 | 59 | 175 | 59 | 59 | 59 |
| run2 | new | 187 | 57 | 188 | 56 | 59 | 59 |
| run3 | base | 173 | 59 | 174 | 59 | 59 | 59 |
| run3 | new | 187 | 56 | 187 | 57 | 59 | 59 |
| run4 | base | 174 | 59 | 175 | 59 | 59 | 58 |
| run4 | new | 187 | 56 | 188 | 56 | 60 | 59 |

> run1 = 任务里「依次实跑这三条命令」那次；run2/3/4 = 「再跑 3 次」。4 份完整脚本输出：`diag/verify-batch1-raw/03-probe-run1.txt … 06-probe-run4.txt`；4 份探针原始文件：`run1/ … run4/`。

### 6.3 取最大值对照表

| 场景 | base（run2–4 最大） | new（run2–4 最大） | 差 | base（全 4 次最大） | new（全 4 次最大） | 差 |
|---|---|---|---|---|---|---|
| R1 常驻 tick=0 | 174 | 187 | **+13** | 174 | **188** | +14 |
| R2 常驻 tick=25 | 59 | 57 | **−2** | 59 | 57 | −2 |
| R3 自适应 tick=0 | 175 | 188 | **+13** | 176 | **188** | +12 |
| R4 自适应 tick=25 | 59 | 57 | **−2** | 59 | 57 | −2 |
| R5 常驻+tick25+打字 | 59 | 60 | **+1** | 59 | 60 | +1 |
| R6 自适应+tick25+打字 | 59 | 59 | **0** | 59 | 59 | 0 |

**按任务给的判据（new 的 R2/R4/R5/R6 与 base 差 ≤3 帧；R1/R3 不得低于 165）**：

- R2 −2、R4 −2、R5 +1、R6 0 → **全部 ≤3 帧，达标** ✅
- R1 = 187/188、R3 = 187/188 → **均 ≥165，达标** ✅（且远高于基线）

### 6.4 关于「噪声」—— 必须如实修正的说法

任务背景说「同一二进制跑 3 次，R4 会在 44~59 之间跳」。**我实测 4 轮里没有出现这种跨度**，且方向不是随机的：

- **base 的 R2 在 4 轮里恒为 59（零方差），R4 恒为 59（零方差）。**
- new 的 R2 是 57/57/56/56，R4 是 57/56/57/56 —— 同样几乎无抖动。
- → **−2 帧是稳定复现的，不是噪声。** 它确实落在「≤3 帧」判定线内（**达标**），但把 −2 说成「噪声范围内」会掩盖一个事实：**这是可复现的系统性差异**，量级约 **−3.4%**（59→57）。

我另做了**交错 A/B**（三个变体轮流跑 5 轮，§6.6）交叉确认：base 的 R2 始终 ≥58、R4 始终 59；new 的 R2 始终 56、R4 始终 56–57，**没有一轮反转**。

至于「44~59 的跨度」：我在**临时副本**上还原**旧写法**（t7 修复前）后，R2 稳定落到 44–45、R4 落到 36–37（§6.5）。**那个 44 属于「修复前」的代码，不是同一二进制的噪声。** 如果历史上 44 与 59 是同一二进制跑出来的，我这 4+5 轮**没有复现**；本报告只对我实测到的数据负责。

### 6.5 修复前后对比（我自己做的 A/B，不是引用 captain 数字）

做法：把当前 `src/main/java` **复制**到 `%TEMP%\nc-vfy-prefix`（`src/` 本体一字未改，实验后复核 `CaretAnimator.java` 仍为 `847CF6D8…`），**只**把 `scheduleNextDue()` 方法体换回旧写法：

```java
nextDueNanos = System.nanoTime() + currentDelayMs * 1_000_000L;   // 旧写法：tick 结束时从 now 起算
```

再编译这棵副本，用**同一份** `RegimeProbe.java` 跑 3 次。

| 场景 | base（全 4 次最大） | **修复前**（旧写法，3 次最大） | **修复后 = 当前工作区**（4 次最大） |
|---|---|---|---|
| R1 常驻 tick=0 | 174 | 179 | 188 |
| R2 常驻 tick=25 | 59 | **45**（−24%） | **57**（−2） |
| R3 自适应 tick=0 | 176 | 180 | 188 |
| R4 自适应 tick=25 | 59 | **37**（−37%） | **57**（−2） |
| R5 常驻+tick25+打字 | 59 | **48**（−19%） | **60** |
| R6 自适应+tick25+打字 | 59 | **48**（−19%） | **59** |

修复前逐次：R2 = 45/44/44、R4 = 36/37/37、R5 = 48/48/47、R6 = 47/47/48（原始输出：`diag/verify-batch1-raw/prefix-regime/`）。

**结论：t7 修复把 R2/R4/R5/R6 从 −19%~−37% 拉回到 ≤2 帧差，修复有效且可独立复现。**

### 6.6 系统性 −2 帧的机制定位（额外实验，给出可验证的解释）

怀疑对象：`RenderScheduler.loop()` 的 `BUSY_RETRY_MS = 1` 兜底睡眠。当某次 tick 尚未在 EDT 上执行完时，调度线程 `Thread.sleep(1)` 再回来；25ms 长 tick 场景下每帧付一次，60 帧 × 1ms ≈ 2.4 帧 —— 与观测到的 −2 帧同量级。

验证：另做一棵临时副本，**唯一改动** `BUSY_RETRY_MS = 1` → `0`（`src/` 本体未改），然后 **BASE / 当前工作区（CURR）/ BUSY_RETRY=0（BR0）三者交错跑 5 轮**（避免机器状态漂移）：

| 轮 | BASE | CURR (BR=1) | BR0 (BR=0) |
|---|---|---|---|
| | R1 R2 R3 R4 R5 R6 | R1 R2 R3 R4 R5 R6 | R1 R2 R3 R4 R5 R6 |
| 1 | 174 59 175 59 59 59 | 188 56 187 56 59 59 | 188 58 188 58 60 60 |
| 2 | 173 59 177 59 59 59 | 188 56 187 57 59 59 | 187 58 188 58 60 60 |
| 3 | 174 58 176 59 59 58 | 187 56 187 56 59 59 | 187 58 188 59 60 60 |
| 4 | 173 59 175 59 59 59 | 187 56 187 57 60 59 | 187 58 188 58 60 60 |
| 5 | 172 58 175 59 58 59 | 188 56 188 57 59 59 | 188 58 187 58 60 60 |

5 轮统计（原始 CSV：`diag/verify-batch1-raw/ab-5rounds.csv`）：

| 变体 | R2 (min/max/med) | R4 (min/max/med) | R5 (min/max) | R6 (min/max) |
|---|---|---|---|---|
| BASE | 58 / 59 / **59** | 59 / 59 / **59** | 58 / 59 | 58 / 59 |
| CURR（BR=1） | 56 / 56 / **56** | 56 / 57 / **57** | 59 / 60 | 59 / 59 |
| BR0（BR=0） | 58 / 58 / **58** | 58 / 59 / **58** | 60 / 60 | 60 / 60 |

**结果支持该假设**：`BUSY_RETRY_MS=0` 后 R2 从 56 回到 **58**（base 59）、R4 从 57 回到 **58**（base 59）、R5/R6 从 59 回到 **60**（base 58–59）。三个变体是在**同一段机器状态下交错**跑出来的，因此这个差异不是机器负载漂移。

**但我不建议据此改 `BUSY_RETRY_MS`**：契约 §4.2 的 W3-2 明确允许 `0 或 1ms`；改成 0 会让「到期但任务在途」变成热自旋（每轮立刻重算并再次判定），同样会改变帧率矩阵。**这是取舍，应由 captain/用户决定，不是 verifier 单方面裁定的事。** 本报告只负责把「−2 帧从哪来」定位清楚。

### 6.7 R1/R3 的 +13 帧（必须写出的方向性改变）

R1/R3（`tick=0ms` 常态）在新版下**显著变快**：173–176 → 187–188（约 **+7%~+8%**）。5 轮交错数据里该差距稳定存在，**不是噪声**。

契约 §2.6 的判定原文是「单 animator 相邻两次 tick 间隔不得**系统性大于** `currentDelayMs`」—— 只禁止变慢、不禁止变快，因此**不违反契约**。但它是「零观感优化」目标下的一处**真实行为改变**（同一序列下投递次数变多）。我只陈述事实，不替你判断它算不算「观感变化」。

---

## 7. 常量与语义之外的一项结构事实（供审查参考）

`CaretAnimator` 的调度链路现在是：

```
RenderScheduler.loop()（1 条 daemon 线程）
  └─ 算最早 nextDueNanos → sleep 到它
     └─ dispatchDue()：for (animator : ANIMATORS) 各自判定
        └─ SwingUtilities.invokeLater(animator::runTick)   ← 每个到期 animator 一次
           └─ runTick()：framePending=false → tick() → finally scheduleNextDue()
              └─ scheduleNextDue()：next = lastDispatchedDueNanos + delay，nextDueNanos = max(next, now)
```

`lastDispatchedDueNanos` 是**新增的 per-animator 守卫字段**（`volatile long`，初值 `Long.MIN_VALUE`），用途是「同一个到期值最多投递一次」，防的是「tick 开始即复位 `framePending`，但 `nextDueNanos` 仍是旧值，1ms 轮询于是反复投递」的恶性堆积。它只参与「是否投递」的判定，**不参与**动画时长 / 物理推进 / 重绘区域 / 颜色 / 几何的任何计算；这与 §2 的 parity 逐位一致、§3 的 47 项常量零变更**互相印证**。

契约 §2.6.2 写的是「EDT 在 tick 结束时写 `nextDueNanos = now + currentDelayMs * 1_000_000L`」，而实现改成了「上次投递的到期值 + delay 并追赶」。**这是对契约文字的一处偏离（虽属修复必要）**，契约未预留该白名单条目。我不裁定它是否越界（那是 reviewer 的职责），只如实记录：**契约文字与实现不一致，且契约 SHA256 已 FROZEN。** captain 版报告把这一点作为「裁决 (B)」处理，我在此仅标记为「契约需补充记录」。

---

## 8. 关于任务描述里的「契约笔误」—— 原文转述有误，我按实测写

任务要求我写：「`OPTIM-BATCH1.md` §3 里 `Select-String -Path src -Include *.java -Recurse` 在 pwsh 7 不可用」。

**这条转述与事实不符，我核查后按实测写。**

| 核查 | 结果 |
|---|---|
| `OPTIM-BATCH1.md` 全文搜 `-Include` | **0 命中** |
| `OPTIM-BATCH1.md` 全文搜 `Select-String.*-Recurse` | **0 命中** |
| §3（L302–L489）里所有 `Select-String` | L380/381/382 用 `-Path $rs`（**单文件**）；L447 是管道 `git diff \| Select-String`；L461 用单个 `NeovideConfig.java`；L484 是管道 `jcmd \| Select-String`。**没有一条带 `-Recurse`，也没有一条带 `-Include`。** |
| `OPTIM-BATCH1.md` 里 `-Recurse` 出现在哪 | L317/320/332/334/426/471/474，**全部是 `Get-ChildItem` / `Remove-Item`**。这两个 cmdlet 都有 `-Recurse`，这些写法在 pwsh 7 下**完全可用**（本次两个脚本实际跑通了同类写法） |
| 那句「`Select-String -Path <目录> -Include *.java -Recurse`」的真实出处 | `tools/parity/run-audit.ps1:104` —— 验证脚本里**故意构造的失败反例**（D-4 段，用来演示 `-Recurse` 报错），以及 captain 早期草稿的 §6 / `REVIEW-BATCH1.md` F3 |

→ 起草者把「自己写的反例脚本」记成了「契约原文」。**`REVIEW-BATCH1.md` 的 F3 是事实错误的 finding**，建议改述为下面这条真实且更危险的坑。

### 8.1 真正值得记录的坑：`-Include` 的静默假阴性（pwsh 7 实测）

`Select-String` **确实没有** `-Recurse` 参数，写了会报错：

```
找不到与参数名称 'Recurse' 匹配的参数。
```

**但危险的不是报错，而是不带 `-Recurse` 的老式写法会静默返回 0 命中：**

```powershell
# ❌ 不报错，但把目录当文件看，恒 0 命中（假阴性）
Select-String -Path <repo>\src -Include *.java -Pattern '<正则>'
```

实测（同一棵基线源码树 `321b0a0`，该树**确实有** 7 处死字段）：

| 写法 | 7 死字段正则 | `public` 正则 | 行为 |
|---|---|---|---|
| 逐文件 `Select-String -Path <每个 .java>`（**金标准**） | **7** | **113** | 正确 |
| `Get-ChildItem -Recurse -Filter *.java <src> \| Select-String` | **7** | **113** | 正确 |
| ⚠️ `Select-String -Path <目录> -Include *.java`（无 `-Recurse`） | **0** | **0** | **不报错，静默假阴性** |
| `Select-String -Path <目录>`（目录直接当 Path） | 0 | 0 | 同样静默假阴性 |
| `Select-String -Path <目录> -Include *.java -Recurse` | — | — | 报错「找不到与参数名称 'Recurse' 匹配的参数。」 |

**危害**：若用危险写法验「7 个死字段已清零」，会得到「基线 0 / 新版 0」→ 误判「基线本来就 0 命中」，**对照意义全部丢失，且屏幕上没有任何错误提示**。这比直接报错隐蔽得多。

本次 `run-audit.ps1` 用的是**安全写法**，D-1/D-2 的「新版 0 / 基线 7」是**真实有效的对照**（我在 §3.3 已复核）。

### 8.2 等价写法（与金标准实测一致）

```powershell
Get-ChildItem -Recurse -Filter *.java D:\deepseekwork\neovim-cursor-pr\src | Select-String -Pattern '<正则>'
```

**未修改 `OPTIM-BATCH1.md`**（它本来就没这个问题，且已 FROZEN，其 SHA256 `13B02803…` 已在 §1 绑定）。

---

## 9. 未覆盖 / 存疑（诚实声明）

| # | 项 | 状态 | 影响 / 说明 |
|---|---|---|---|
| U1 | **真机 IDEA 内的 EDT 负载与主观手感** | **未验** | 全部帧率来自 headless + `Proxy` 伪造 `Editor`。这是本批唯一无法自动化的验收项，**必须由用户实机确认**。证据再全也替代不了这一项。 |
| U2 | `tick=25ms` 探针值 | **合成值** | 固定 25ms，不是真实卡顿分布（真实可能是 20~50ms 抖动、且会连续多帧长任务）。机制成立，绝对值不可外推。 |
| U3 | fps 日志 T0-1 | **只做静态验证** | 未在真机 Debug Log 下实跑，未观察「关掉 Debug 后 `idea.log` 不再新增 fps 行」。我做到的是：源码门控结构 + 字节码 `isDebugEnabled`+`ifeq` + 两个既有解析器能正确解析新格式行。 |
| U4 | R1/R3 的 **+13 帧** | **已实测，方向性改变** | §6.7。不违反契约（只禁变慢），但确实是行为改变，不属「零变化」。 |
| U5 | R2/R4 的 **−2 帧** | **已实测，系统性非噪声** | §6.3 / §6.6。落在 ≤3 帧判定线内（达标），但表述为「噪声」不准确。机制已定位到 `BUSY_RETRY_MS`；是否改属取舍。 |
| U6 | `BUSY_RETRY_MS = 0` 方案 | **只做实验，未采用** | 能消掉 −2 帧（R2 58 / R4 58 / R5 60 / R6 60），但引入热自旋风险。契约 W3-2 允许 0 或 1，当前取 1。**未改 `src/`。** |
| U7 | `lastDispatchedDueNanos` 的 dispose/投递竞态 | **未做压力测试** | 极端时序（dispose 与投递交错）未覆盖。`isDisposed()` 前置检查 + `volatile` 是缓解，不是证明。 |
| U8 | 契约文字与实现的偏离 | **已记录，待 reviewer 裁定** | §2.6.2 写「tick 结束时写 `nextDueNanos = now + delay`」，实现是「上次投递到期值 + delay 并追赶」。契约无对应白名单条目，且已 FROZEN。见 §7。 |
| U9 | 帧率探针的机器前提 | **未做 CPU 亲和性 / 电源方案控制** | 数字是「这台机器当时的数字」。交错 A/B（§6.6）缓解了机器漂移，但跨机器 / 跨电源方案不可比。 |
| U10 | 多光标（Alt 列选）/ 叠加模式下控制台光标 | **本批未覆盖** | 契约 §4.4 明确不做，非缺陷。 |
| U11 | `OffsetTest.class` 残留在交付 jar | **基线即有，非本批引入** | 我核实 `0.9.0-ahf-default.jar` 里**也有**它，故不是本批引入；不被 `plugin.xml` 引用，不影响运行，属交付卫生问题。 |
| U12 | `_verify-tier1.jar` 比「正式名」jar 更新 | **已核实无实质差异** | 三者解包内容哈希相同；`_verify-tier1` 的 class 与当前 `src/` 重编结果逐个 SAME。只是时间戳/命名顺序易误判「最新」。 |

---

## 10. 与 captain 22:10 版草稿的数字差异（需 captain 决定采信哪组）

两版报告都是真跑的，但**基线值不同**，说明各自是在不同机器状态下取的样本。差异如下（左＝captain 22:10 版，右＝本报告）：

| 项 | captain 22:10 版 | 本报告 | 说明 |
|---|---|---|---|
| R1 base | `171, 172, 174, 172` | `174, 174, 173, 174` | 本报告 4 轮最小值更高 |
| R2 base | `58, 58, 58, 58`（零方差） | `59, 59, 59, 59`（零方差） | **两者都零方差，但中心差 1 帧** |
| R1 new | `188, 188, 188, 188` | `188, 187, 187, 187` | captain 零方差，本报告有 1 帧抖动 |
| R2 new | `56, 56, 56, 56` | `57, 57, 56, 56` | — |
| R4 new | `56, 56, 57, 56` | `57, 56, 57, 56` | — |
| 「−2 帧」结论 | 一致（都判定为系统性而非噪声） | 一致 | 两版在此**结论相同** |
| 「R1/R3 变快」量级 | +16 帧 / +9.3% | +13~14 帧 / +7%~+8% | 因 base 基线取值不同而略有差异 |

**我的建议**：以**本报告的 R2 base = 59 / new = 57（−2 帧）** 为正式口径，理由是：(a) 本报告的 base 值（59）与 `DispatchCountProbe` 的 `phase5_posts_slow_tick_25ms = 59`、以及 §6.6 交错实验中 BASE 的 R2=58~59 三者**互相吻合**；(b) 交错实验（同机同状态、三变体轮流跑）比各自独立跑更能排除漂移。**但这属于 captain 的裁定权，我只给出依据。**

> 注意：无论采信哪一组，**结论不变** —— R2/R4 差异都是 **−2 帧、≤3 帧、达标**、且**都是系统性而非噪声**。这也是两版报告唯一实质一致的关键结论。

---

## 11. 复现命令汇总（可直接复制）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1"
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-audit.ps1"
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\probe\run-probe.ps1"
```

47 项常量清单（与契约 §3.3 ③-a 同口径）：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\diag\verify-batch1-raw\const-check.ps1"
```

指纹：

```powershell
Get-FileHash "D:\deepseekwork\neovim-cursor-pr\OPTIM-BATCH1.md" -Algorithm SHA256
Get-FileHash "D:\deepseekwork\neovim-cursor-pr\src\main\java\com\github\yyyolo7a79\neovidecursor\ui\RenderScheduler.java" -Algorithm SHA256
Get-FileHash "D:\deepseekwork\neovim-cursor-pr\dist\_verify-tier1.jar" -Algorithm SHA256
```

### 11.1 原始证据文件（`diag/verify-batch1-raw/`）

| 文件 | 内容 |
|---|---|
| `01-parity.txt` | `run-parity.ps1` 完整输出 |
| `02-audit.txt` | `run-audit.ps1` 完整输出 |
| `03-probe-run1.txt` … `06-probe-run4.txt` | `run-probe.ps1` 4 次完整输出 |
| `run1/` … `run4/` | 每次的 5 份探针原始 `RESULT` 文件 |
| `prefix-regime/` | t7 修复**前**旧写法的 3 次 `RegimeProbe` 输出（§6.5） |
| `ab-5rounds.csv` | BASE/CURR/BR0 交错 5 轮（§6.6） |
| `const-check.ps1` / `const-base.txt` / `const-new.txt` | 47 项常量清单及基线/新版输出（§3.2） |
| `git-diff-src.patch` | `git diff -- src` 存盘（26,364 B / 542 行） |
| `VERIFY-BATCH1.prior-captain-draft.md` | 21:45 版草稿留档（11,810 B） |
| `VERIFY-BATCH1.captain-draft-2210.md` | 22:10 版草稿留档（15,646 B，SHA256 `8816A74B…`） |

### 11.2 回退目标

```
dist\jetbrains-neovide-cursor-0.9.0-ahf-default.jar
SHA256 = FA161E4F77C8C99992381DD489F7C172A38F56BB62DC3C2D45D8856AD8C95CD5   (39,937 B)
```
