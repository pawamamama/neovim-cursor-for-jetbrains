# OPTIM-BATCH1.md —— 「零观感优化·批次1」契约（冻结版）

> **本文件是契约，不是设计稿。** 实现者按它做、验证者按它取证、审查者按它裁决。
> 实现过程中**不得反向修改本文件来迁就实现**；若某项在本契约下无法达成，只能由 captain 批准修订，并留下「原条款为何不可行」的记录。

| 项 | 值 |
|---|---|
| 文档状态 | **FROZEN**（t1 requirements 产出，round 1） |
| 适用批次 | 批次1 = Tier 0（5 项）+ Tier 1（1 项） |
| 目标交付版本 | `0.9.1-zeroopt` |
| 源码基线 | `git HEAD` = **`321b0a0`**（分支 `feat/settings-ui-ready`，工作区 `src/` 无未提交改动） |
| 运行基线（回退权威） | `dist\jetbrains-neovide-cursor-0.9.0-ahf-default.jar`，SHA256 = `FA161E4F77C8C99992381DD489F7C172A38F56BB62DC3C2D45D8856AD8C95CD5` |
| 行号约定 | 本文行号**全部基于基线 `321b0a0`**；改动后会漂移，凡判定一律以**符号名**为准，行号仅作定位参考 |
| 文件编码 | 一律 UTF-8 |
| 本文件不在本批实现改动范围内 | `src/**`、`build/**`、`dist/**`、`VERSIONS.md` 都**不是**本文件的产出物 |

---

## 0. 怎么用这份契约（三条流水线）

| 角色 | 用法 |
|---|---|
| **impl-core** | 逐项对照第 2 章的【允许】/【禁止】；每完成一项，在提交说明里写出「本项对应 T0-x / T1-1」+ 实际改动符号名。**不得做第 4.4 节「本批明确不做」里的任何事。** |
| **verifier** | 按第 3 章的三条硬证据逐条取证，命令可直接复制。**必须自己重跑，不得引用实现者给出的结论**。命令草案已在本文实测通过（见附录 B）。 |
| **reviewer** | 逐项核对第 2 章的【判定】+ 第 3 章证据是否真实存在 + 第 4 章禁止清单是否被遵守；任何一条不满足 → `needs_revision`；命中第 4.3 节任一条 → `reject`。 |

**裁决门槛**：`pass` 需要「客观判据全部满足」且「三条硬证据齐备」；缺一不可。

---

## 1. 判据总纲：「零观感」的定义

### 1.1 「手感」的四个可观测面

本批把「手感」拆成四个**可客观观测**的面。所谓「零观感」= 在**相同输入序列**（同样的事件、同样的 dt 序列、同样的光标几何）下，下列四面**逐项不变**：

| 面 | 内容 | 观测手段 |
|---|---|---|
| **(a) 物理/几何数值轨迹** | `TrailCorner` 每帧的 `currentX/currentY`、`offsetMagnitude`、四角动画时长、四角外扩方向向量 | 逐位（`doubleToRawLongBits`）parity harness |
| **(b) 观感常量** | `NeovideConfig` 字段 + `CaretAnimator` / `CaretTrailPanel` / `TrailCorner` / `DampedSpring` 中的数值常量 | 常量清单断言 + 白名单 diff 审计 |
| **(c) 调度时序语义** | 何时投递、每次投递做什么、投递几次、积压丢弃规则、唤醒延迟 | 源码结构判定（+ 可选运行期计数） |
| **(d) 绘制指令序列** | `Graphics2D` 的调用顺序与每次调用的参数（颜色、路径点、层序、stroke） | 源码级 diff 审计 + (a) 的几何结果 |

### 1.2 允许变化的三件事（本批的收益点）

只有下列三类变化**不算**改动观感：

1. **日志输出**：fps 日志的等级、门控、窗口、附加上下文；新增只读埋点日志。
2. **每帧临时对象的数量**：把每帧 `new` 的对象提为可复用字段 —— 前提是 (d) 中**每次调用传给 Graphics2D 的参数依然逐位相同**。
3. **线程拓扑**：N 条 per-animator 调度线程 → 1 条共享调度线程 —— 前提是 (c) 中**每个 animator 的投递次数与时序语义不变**（见 2.6）。

### 1.3 基线（唯一口径）

- **源码基线**：`git HEAD`（`321b0a0`）。任何「与基线比较」都以它为参照，**不是** `dist/` 里的历史 jar。
- **运行基线**：已安装 jar 与 `dist\jetbrains-neovide-cursor-0.9.0-ahf-default.jar` **同哈希**（本机实测两者 SHA256 完全一致，见附录 B-6）。
- Tier 0 的五项都是「重构/删死代码/加日志」，**不产生新行为**；唯一允许的数值变化是日志窗口与新增埋点阈值（见 4.1）。

---

## 2. 本批要做的事（逐项契约）

### 2.1 T0-1 fps 日志门控与降噪（Tier 0）

**目标文件**：`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java`
**目标符号**：`recordFrameStats(long now, boolean active)`（基线 `L634-681`），日志点基线 `L665-675`。

现状（基线）：

```java
long elapsed = now - fpsWindowStart;
if (elapsed >= 1_000_000_000L) {                                   // ← 1s 窗口
    LOG.info(String.format(                                        // ← 无条件 info
            "neovide-cursor: fps=%.1f  frames=%d  window=%.0fms  maxGap=%.0fms  "
                    + "peakRepaintArea=%dpx  delay=%dms",
            frameCount * 1_000_000_000.0 / elapsed, frameCount,
            elapsed / 1_000_000.0, maxGapNanos / 1_000_000.0,
            peakRepaintArea, currentDelayMs));
    frameCount = 0; peakRepaintArea = 0; maxGapNanos = 0L; fpsWindowStart = now;
}
```

实测背景（本机复查）：`idea.log` 共 6,284,402 B，其中 `neovide-cursor: fps=` 行 **2283 行 / 454,872 B = 7.24%**。

**【允许的 diff】**

1. 窗口阈值：`1_000_000_000L` → `>= 5_000_000_000L`（或提为 `private static final long STATS_WINDOW_NANOS = 5_000_000_000L;`，取值 **≥5s**）。
2. 日志等级与门控：`LOG.info(String.format(...))` → **`if (LOG.isDebugEnabled()) { LOG.debug(...); }`**；`String.format` 必须在门控**内部**（门控外求值等于没省）。
3. **行尾追加**编辑器标识（见下）。
4. 只允许上述三处改变；`frameCount / peakRepaintArea / maxGapNanos / fpsWindowStart` 的统计口径与重置时机**一字节都不许动**。

**【编辑器标识的硬性要求】**

- 必须**追加在行尾**（`delay=%dms` 之后）。**不得插在字段中间**：`build/analyze-log.ps1:8` 与 `diag/extract.py:6` 用固定顺序的正则解析这一行，中间插字段会让现有诊断证据链失效，而这两个脚本在本批**禁止修改**。
- 只在「`LOG.isDebugEnabled()` 为真 **且** 窗口到期」时才计算；**每帧不得计算**。
- 取值必须 `try/catch(Throwable)` 兜底；**禁止读取文档内容**（`getDocument().getText()`）之类昂贵操作。
- 最低要求：能区分同一 IDEA 会话中的不同编辑器。推荐形式：
  `... delay=%dms  editor=<EditorKind>#<System.identityHashCode(editor)> doc=<文件名|(untitled)>`
- `fps=` / `frames=` / `window=` / `maxGap=` / `peakRepaintArea=` / `delay=` 六个字段的**名字、顺序、格式串**保持不变。

**【明确禁止的 diff】**

- 改 fps / gap 的**计算公式**（`frameCount * 1e9 / elapsed`、`maxGap` 取相邻两次记账的间隔）。
- 改 `if (!active) return;` 提前返回、`currentBounds` 读取等任何既有行为。
- 改本文件 `L520-523` 那处**已经**门控好的 recovery `LOG.debug`（它不属于本项）。
- 改 `NeovideCaretManager` 里的 `LOG.info`（`L101`、`L220` 是一次性事件日志，**不在本批范围**）。
- 把日志改成 `System.out`、写文件、异步入队、限流器等任何形式的"重构"。
- 给 `recordFrameStats` 增删参数或改调用点（`tick()` 末尾 `recordFrameStats(now, moved || anyAnimating)` 不变）。

**【判定】**

- `CaretAnimator.java` 中 `LOG.info` 出现次数 = **0**（该文件唯一的 info 就在此）。
- `LOG.debug` 出现在 `recordFrameStats` 内且被 `LOG.isDebugEnabled()` 包裹。
- 关掉 Debug Log 后 `idea.log` 不再新增 `neovide-cursor: fps=` 行；打开后每 **≥5s** 一行且行尾带编辑器标识。
- 口径提醒：窗口变 5s 后 `fps` 是 5s 均值，**不要**与旧 1s 窗口数据直接比较（`UPSTREAM-PITFALLS.md` #21 的"统计口径失真"警告依旧适用）。

---

### 2.2 T0-2 每帧分配复用（Tier 0）

**目标文件**：`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretTrailPanel.java`
**目标符号**：`drawQuadGlow(Graphics2D, Polygon)`（基线 `L227-274`）、`computeOffsetDirections(Polygon, double[], double[])`（基线 `L288-365`）。

基线每帧分配（同一条绘制链路上共 5 次 `new`）：

| 位置 | 分配 |
|---|---|
| `drawQuadGlow` `L251` | `double[] dirX = new double[4]` |
| `drawQuadGlow` `L252` | `double[] dirY = new double[4]` |
| `drawQuadGlow` `L255` | `Path2D.Double path = new Path2D.Double()` |
| `computeOffsetDirections` `L290/L291` | `double[] nx = new double[4]`、`double[] ny = new double[4]` |

**【允许的 diff】**

1. `dirX / dirY / path` 提为**实例 final 字段**（构造时分配一次），`drawQuadGlow` 内改用字段。
2. `computeOffsetDirections` 由 `private static` 改成 `private`（实例方法）**仅为**访问上述字段 —— 方法体算法逐行不变。
3. 是否一并复用 `nx / ny` 二选一：
   - **A2（保守，等价容易证明）**：`nx/ny` 保持局部数组（每帧仍有 2 次分配）。本选择**不需要**额外论证。
   - **A1（彻底）**：`nx/ny` 也提为字段，但**必须**满足二选一：
     - A1a：每次调用入口显式清零（`Arrays.fill(nx, 0); Arrays.fill(ny, 0);`），等价性论证 =「基线每次调用都以全零数组起始」；或
     - A1b：把退化分支的 `continue` 改成「先写 `nx[j]=0; ny[j]=0;` 再 continue」，等价性论证 =「基线被跳过的槽位本来就保留初值 0」。
4. 允许把 `path.reset()` 保留在循环内原位置（**推荐**）；也允许改成循环外 `path.reset()` 一次 + 循环内不再 reset，**但必须**由证据①的几何用例证明逐位一致。

**【明确禁止的 diff】**（命中即 `reject`）

- **`nx/ny` 提为字段却不清零**：`len < 1e-6` 时 `continue` 不写值，基线靠「每次 new 全零」保证为 0；字段复用后会读到**上一帧残留法线** → 退化四边形（四角重合、零长边）的几何直接改变。
- **删除或移动 `path.reset()`**：字段复用后 `path` 会残留上一帧子路径，`fill()` 会填出上一帧的四边形。
- 任何算术表达式、循环边界、取整、截断的改动：`SQRT_2 = Math.sqrt(2.0)`、`Compute 中的 1e-6`（两处）、`mag > SQRT_2` 截断、`glowWidth <= 0.5f`、`Math.abs(newWidth - glowWidth) > 0.5f`（`updateGlowRadius` 的迟滞）、`alpha <= 0.02f`、`alpha > 0.55f`、`Math.min(255, glowAlpha)`、`argb < 1`。
- 改 `layers = config.glowLayers`、`peak` 计算、`layerAlpha = 1.0 - Math.pow(1.0 - peak, 1.0 / layers)`、`radius = glowWidth * i / layers`、层循环方向（`i = layers → 1`）、`g2.setColor` 与 `computeOffsetDirections` 的**相对顺序**。
- **坐标 `int` → `double`（明令禁止）**：`polyX/polyY` 保持 `int[4]`；`updateCorners` 里的 `(int) Math.round(...)` 语义不变。
- 顺手复用同类分配：`new Polygon(polyX, polyY, 4)`（`L191`）、`new Color(...)`（`L247`、`L401`、`L407`）、`new BasicStroke(...)`（`L398`）—— **本批一律不做**（见 4.4）。
- 缓存颜色对象、把 `Color` 换成 int 常量、改 `RenderingHints`、改 `paintGhosts` 的任何数值。

**【判定】**

- 源码 diff 只含「分配 → 字段」的搬移与（A1 允许的）清零等价改写；`g2.fill(path)` 的入参几何来源不变。
- 证据①的几何用例（含**四角重合**与**零长边**两个退化用例）逐位一致。

---

### 2.3 T0-3 删除空转链路 `smoothedDt / effectiveDt`（Tier 0）

**目标文件**：`CaretAnimator.java`
**涉及符号**（基线行号）：字段 `smoothedDt` `L243`；写入 `L431`（`wakeUp` 内）、`L513`（recovery 分支）、`L568`（`tick` 内）；`L569` `double effectiveDt = Math.max(dt, smoothedDt);`；`L578` `tickSpring(dt, effectiveDt)`；`L853` `tickSpring(double dt, double effectiveDt)`；`L855` `assignRanks(effectiveDt)`；`L1027` `assignRanks(double dt)`。

事实（基线已确认，证据已留档）：

- `assignRanks` 的形参 `dt` 在方法体内**一次都没被读取**（方法体只做 alignment 计算 → 排名 → `corners[i].jump(cursorWidth, cursorHeight, centerX, centerY, ranks[i])`）。
- `TrailCorner.jump(...)`（`TrailCorner.java:139`）的签名**已不含** `frameDt`，正是"早已不接"的状态。
- 因此 `effectiveDt` 与 `smoothedDt` 对本批所有观感面 (a)(b)(c)(d) 均**无影响**（`smoothedDt` 除自身更新外无读者）。

**【允许的 diff】**（两种都可，选哪种必须写在证据里）

- **方案 A（推荐，彻底）**：删除字段 `smoothedDt`、`L431` 与 `L513` 两处赋值、`L568` 的更新、`L569` 的 `effectiveDt`，`tickSpring(dt)` 与 `assignRanks()` 均改无参；同时删除 `L412-431` 那段已失效的注释块（可选）。
- **方案 B（最小）**：仅删 `effectiveDt` 与 `assignRanks` 的形参；`smoothedDt` 的三处写入保留（保留一个无人读取的字段写入，属行为中性）。

**【明确禁止的 diff】**

- 改 `dt`（真实帧间隔）的算法与任何使用者：`L469-470` 的 `rawDt/dt` 计算、`MAX_DT` 钳位、`L509-524` 的 recovery 分支、`corner.update(..., dt, false)`（`L862` 与残影/静态分支的对应调用）。
- 改 `assignRanks` **方法体**语义：alignment 计算、`alignment[j] < alignment[i]` 的排名规则、`jump(...)` 的调用与参数。
- 改 `jumped` 标志与 `if (jumped)` 分支的调用时机（仍必须"目标变化后的第一帧、只在 `tickSpring` 内"）。
- 顺手复用 `assignRanks` 里的 `new double[corners.length]` / `new int[corners.length]`（`L1028`、`L1035`）—— **本批不做**。
- 删除或改 `wakeUp()` / recovery 分支里**其它**的归位语句（`smoothedIntervalMs`、`lastNanos`、`skipNextIntervalMeasurement` 必须全部保留）。

**【判定】**

- `assignRanks` 调用点不再传参；`effectiveDt` 从 `CaretAnimator.java` 消失。
- 基线 vs 新版 `javap -p -c` 的 `tick` / `tickSpring` / `assignRanks` 指令差异**只允许**出现在 `smoothedDt` 写入与形参传递相关处（见 3.1 的 TD0-3 命令）。

---

### 2.4 T0-4 删除 7 个零引用死字段（Tier 0）

**目标文件**：`src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java`

| 字段 | 基线行 | 当前值 |
|---|---|---|
| `cursorDisappearDelay` | `L101` | `50` |
| `cursorFadeOutDuration` | `L104` | `0.075f` |
| `shortJumpMaxChars` | `L199` | `2.0f`（`L197-198` 注释 + `@Deprecated`） |
| `typingMergeChars` | `L203` | `6.0f`（含注释 + `@Deprecated`） |
| `useHardSnap` | `L208` | `true`（含注释 + `@Deprecated`） |
| `leadingSnapFactor` | `L212` | `0.1f`（含注释 + `@Deprecated`） |
| `snapAnimationLength` | `L233` | `0.02f` |

**前置条件（本契约已代为确认，命令与结果见附录 B-4）**：全仓库对 `src/**` 的引用数 = **0**（命中的只有这 7 处声明本身）。文档类命中（`README*.md` / `HANDOFF*.md` / `UPSTREAM-PITFALLS.md` / `diag/*.md` / `_backup-*/workspace-changes.patch`）**不构成引用**，且**不在本批修改范围**（改文档同样禁止）。

**【允许的 diff】**

- 删除这 7 个字段及其**专属**注释/`@Deprecated` 标记。
- 若删完后 `L195` 的小节标题注释 `// ==== 以下为历史遗留… ====` 变成空节，可一并删除该标题行。

**【明确禁止的 diff】**

- 动任何**其它**字段。特别提示：`leadingSnapThreshold` 在本仓库**根本不存在**（`UPSTREAM-PITFALLS.md` 已澄清），**不要新增**；`animationResetThreshold`、`shortMoveThreshold`、`typingMaxChars`、`trailFactors`、`glowLayers`、`maxTrailDistanceFactor`、`cursorWidth`、`hideNativeCaret`、`alwaysHighFps` 一律不得动。
- 为"兼容"保留空的 `@Deprecated` 字段或改名为 `unused`。
- 改 `plugin.xml`、任何 `README*.md`、`VERSIONS.md`、`build/**`。
- 为了"顺便清理"而调整字段的**声明顺序**或**分组注释**（除 2.4 允许的空标题）。

**【判定】**

- 删除后编译通过（命令见 TD0-COMPILE）。
- 7 个名字在 `src/**` 命中数 = 0；而附录 B-3 清单里的观感常量**逐项未变**。
- 证据③（3.3）通过。

---

### 2.5 T0-5 `refreshTarget()` 耗时埋点（Tier 0）

**目标文件**：`CaretAnimator.java`
**目标符号**：`refreshTarget()`（基线 `L742-820`）。

**【允许的 diff】**

1. 新增一个**仅供日志**的阈值常量（如 `private static final double REFRESH_TARGET_SLOW_MS = 2.0;`），语义 = **耗时 > 2ms 才记录**。
2. 一次 `System.nanoTime()` 计时对 + `if (LOG.isDebugEnabled() && slow) { LOG.debug(...); }`。
3. 日志文本需含足以定位的上下文（例如 `neovide-cursor: refreshTarget 3.4ms  forceResync=false  moved=true`）。

**【计时区间的硬性要求】**

- 计时**不得落在每帧快速路径上**：起点必须在快速路径提前返回（`if (!forceResync && vp.line == lastVisualLine && vp.column == lastVisualColumn) return false;`）**之后**，终点在方法返回之前。
  理由：本项是"确认/排除 `visualPositionToXY` 软换行换算贵"，昂贵段正是快速路径未命中的那段；把两次 `nanoTime()` 加到 120~125fps 的每帧路径上，等于本批自己引入每帧开销 —— 与批次目标相反。
- 只允许在**未命中**快速路径的那一次调用里记录（最多一条），不得循环里逐 corner 记录。

**【明确禁止的 diff】**

- 改快速路径条件、`forceResync` 的置零时机、`charsMoved` 的计算与 `corner.setCharsMoved` 调用、`centerX/centerY/cursorWidth/cursorHeight` 的更新顺序、`panel.updateGlowRadius` / `glowPadding` 计算、`initialized` / `jumped` 分支。
- **破坏外层 `catch (Throwable t) { return false; }` 的吞异常语义**（不得改成 `LOG.error` + 抛出，也不得改成返回值变化）。
- 把 `refreshTarget` 改成异步、加 `visualPositionToXY` 缓存、或跳过换算。
- 阈值取 **2ms 之外**的数值（白名单只此一项，见 4.1）。
- 用 `LOG.info`（必须 `LOG.debug`）。

**【判定】**

- Debug Log **关闭**时：仅多出两次 `nanoTime()`（只在非快速路径），无任何日志与行为改变。
- Debug Log **开启**时：仅在耗时 >2ms 时才出现该行。
- 常量审计中该值恰为 2ms 且**仅在此处使用**。

---

### 2.6 T1-1 共享调度线程 `RenderScheduler`（Tier 1）

**新增文件**：`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/RenderScheduler.java`
**修改文件**：`CaretAnimator.java`（调度注册/注销/唤醒；`scheduler` 字段、`startScheduler()` 基线 `L187/L330-360`、`wakeUp()` `L368-438`、`requestWakeUp()` `L446-451`、`dispose()` `L1063-1084`）
**禁止修改**：`NeovideCaretManager.java`（它调用 `requestWakeUp()`/`dispose()` 的方式不变）、`TrailCorner.java`、`DampedSpring.java`、`plugin.xml`。

**要达成的形态**：

- 一条 daemon 线程，线程名**必须**是 `neovide-cursor-render`（沿用基线命名，便于线程枚举取证）。
- 维护所有 animator 的 `volatile long nextDueNanos`（**EDT 写、调度线程读**）。
- 调度线程睡到「最早到期时刻」，然后**对每个到期 animator 各自调用一次 `SwingUtilities.invokeLater(...)`**。
- 每个 animator 保留**自己的**防堆积标志（基线 `framePending` 语义）。
- animator 集合为空 → 线程退出；下次注册时重建。
- `wakeUp()` 改为打断该共享线程（并见下方"必须保留的语义"）。

**【明确禁止的 diff —— 本条是 T1-1 的红线】**

- **照搬上游 v0.5.0 的"一次 `invokeLater` 批量 tick 全部 animator"**（`invokeLater(RenderScheduler::runDue)` + `runDue` 里 `for (a : ANIMATORS) a.runTick()`，见 `build/v050/src/neovim-cursor-for-jetbrains-0.5.0/src/main/java/.../ui/RenderScheduler.java:96-110`）。这会改变 EDT 任务粒度与可插空性（用户已就 issue #2 指出该风险）→ 命中即 **reject**。
- 在**同一个** EDT Runnable 里 tick **两个及以上** animator（哪怕只多一个）。
- 引入**全局单一** pending 标志（上游 `FRAME_PENDING`），或写下「某个 animator 未完成时 `Thread.sleep(1)` + `continue`」这类**让所有 animator 一起等**的逻辑：到期投递的判定只能依赖「该 animator 自己是否到期 + 该 animator 自己是否已有未执行的任务 + 该 animator 是否已 dispose」。
- 为了让"批量"变得可行而把 tick 从 EDT 移到调度线程、改成 `invokeAndWait`、或改用 `javax.swing.Timer`。

**【必须保留的调度语义（逐条核对）】**

1. **`wakeUp()` / `requestWakeUp()` 必须保留「立即投递一帧」的现状延迟**：基线的 `interrupt()` 会从 `Thread.sleep` 中醒来并**直接落到 `invokeLater`**（不重新睡），所以"停手后第一次操作"是**立刻**投递，不是等 8ms 或一个空闲周期。实现方式 = `nextDueNanos = System.nanoTime();` **然后**打断共享线程。
   - **只打断共享线程而不把该 animator 置为立即到期 = `needs_revision` 以上**：调度线程会睡到旧到期时刻（最长 120ms / 失焦 600ms），把「停手后第一次操作卡一下」原样找回来。
   - 注意 `wakeUp()` 里 `currentDelayMs = ACTIVE_DELAY_MS` 与 `fromIdleTier` 分支的四处归位（`smoothedIntervalMs` / `skipNextIntervalMeasurement` / `lastNanos` / `smoothedDt`）必须**全部保留**（T0-3 若删除 `smoothedDt`，则只删那一处）。
2. **到期登记**：EDT 在 tick 结束时写 `nextDueNanos = now + currentDelayMs * 1_000_000L`；`currentDelayMs` 的**计算方式**（`computeNextDelay`：`alwaysHighFps` 优先 / `adaptive = round(smoothedIntervalMs * 0.85)` 且夹在 `[ACTIVE_DELAY_MS, MAX_ADAPTIVE_DELAY_MS]` / 空闲档 `IDLE_DELAY_MS` 或 `IDLE_UNFOCUSED_DELAY_MS`）**一字节都不许改**。
3. **tick 的执行体不变**：`tick()` 内的读取位置 → 物理推进 → 重绘提交 → 统计记账的顺序与内容不变（除 T0-1/T0-3/T0-5 允许的三处）。
4. **dispose 保护**：EDT 任务仍必须在执行前判 `disposed`（基线 `if (!disposed) tick();`），`dispose()` 必须 `unregister`（否则关标签页后残留 tick = 幽灵光标 + 线程泄漏）。
5. **异常隔离**：一个 animator 抛异常不得影响其它 animator（逐 animator 投递天然满足；批量投递会破坏隔离性）。
6. **注册时的首次 tick 时机**：基线新线程先 `sleep(currentDelayMs)` 再投递。注册可以唤醒共享线程重算最早到期，但**不得**因此提前 tick 一个未到期的 animator。
7. **单 animator 视角的投递次数不变**：每个到期周期 ≤1 次 `invokeLater`；`framePending` 的置位/复位时机与基线一致（投递前置位、EDT 任务开始即复位）。

**【判定】**

- 证据②（3.2）结构判定通过；**不得**出现批量 Runnable。
- 编译通过；`neovide-cursor-render` 线程数在多编辑器场景下为 **1**（可选运行期证据 TD0-THREAD）。
- 单 animator 相邻两次 tick 间隔不得**系统性大于** `currentDelayMs`（只允许 `Thread.sleep` 的毫秒取整精度差，且必须是**提前**而非延后）。

---

## 3. 三条硬证据的判定口径与命令草案

> 三条全部必做。命令可直接复制（PowerShell 7 / `pwsh`）。附录 B 记录了这些命令在**基线**上的实测结果，verifier 可先复现基线再对新版重复一遍。

### 3.1 证据① 物理/几何数值轨迹**逐位一致**（parity harness）

**口径**：把基线源码与本批源码各跑同一份 harness，输出必须**字节完全相同**；任何一位不同 = 失败。

**基线获取（不污染工作区）**：

```powershell
$repo = 'D:\deepseekwork\neovim-cursor-pr'
$zip  = Join-Path $env:TEMP 'nc-base-src.zip'
$dst  = Join-Path $env:TEMP 'nc-base-src'
if (Test-Path $zip) { Remove-Item -Force $zip }
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
git -C $repo archive --format=zip -o $zip HEAD src/main/java
Expand-Archive -Path $zip -DestinationPath $dst -Force
(Get-ChildItem -Recurse -File $dst -Filter *.java).Count      # 期望 13
```

**harness 落盘**：把附录 A-1 / A-2 两份源码**逐行照抄**到 `tools/parity/PhysicsParity.java`、`tools/parity/GeometryParity.java`（**不要改格式**，否则哈希不可比）。

**编译 + 运行（对指定源码树）**：

```powershell
$libs = 'D:\deepseekwork\_idea-anim\idea-libs'
$cp   = (Get-ChildItem $libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
function Run-Parity([string]$srcRoot, [string]$tag) {
  $out = Join-Path $env:TEMP "nc-parity-$tag"
  if (Test-Path $out) { Remove-Item -Recurse -Force $out }
  New-Item -ItemType Directory -Force $out | Out-Null
  $srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcRoot | ForEach-Object { $_.FullName }) +
          @((Resolve-Path 'D:\deepseekwork\neovim-cursor-pr\tools\parity\PhysicsParity.java').Path,
            (Resolve-Path 'D:\deepseekwork\neovim-cursor-pr\tools\parity\GeometryParity.java').Path)
  & javac --release 21 -nowarn -encoding UTF-8 -classpath $cp -d $out $srcs
  if ($LASTEXITCODE -ne 0) { throw "javac 失败 ($tag)" }
  & java -cp "$out;$cp" PhysicsParity  > (Join-Path $env:TEMP "parity-$tag-physics.txt")
  & java -cp "$out;$cp" GeometryParity > (Join-Path $env:TEMP "parity-$tag-geometry.txt")
}
Run-Parity (Join-Path $dst 'src\main\java') 'base'
Run-Parity 'D:\deepseekwork\neovim-cursor-pr\src\main\java' 'new'

# 判定：两次都必须是 exit 0，且 diff 为空
git diff --no-index --exit-code (Join-Path $env:TEMP 'parity-base-physics.txt')  (Join-Path $env:TEMP 'parity-new-physics.txt')
if ($LASTEXITCODE -ne 0) { 'PHYSICS PARITY FAIL' }
git diff --no-index --exit-code (Join-Path $env:TEMP 'parity-base-geometry.txt') (Join-Path $env:TEMP 'parity-new-geometry.txt')
if ($LASTEXITCODE -ne 0) { 'GEOMETRY PARITY FAIL' }
```

**基线自检（可选但强烈建议）**：先对**基线自己**跑一次，输出哈希必须等于附录 B-1 记录的两个值 —— 这证明 harness 与基线快照没被改动。

**覆盖面要求**（附录 A 已内置，不得删减）：

| harness | 覆盖 |
|---|---|
| A-1 PhysicsParity | 同行逐字符（短跳）/ 删中文宽字符 / 跨行大跳 / 目标不变；dt 覆盖 8ms、16.7ms、33.3ms、50ms（`MAX_DT`）、120ms（recovery 边界） |
| A-2 GeometryParity | 静止矩形、斜四边形、极端细长、**四角重合（退化）**、**零长边（退化）**、大矩形、负坐标、反向绕行；每个用例**连调两次**（第二次专门暴露"复用未清零"的残留状态） |

**TD0-3（T0-3 的补充判定：字节码级）**：

```powershell
$cBase = Join-Path $env:TEMP 'nc-parity-base\com\github\yyyolo7a79\neovidecursor\ui'
$cNew  = Join-Path $env:TEMP 'nc-parity-new\com\github\yyyolo7a79\neovidecursor\ui'
& javap -p -c (Join-Path $cBase 'CaretAnimator.class') | Out-File -Encoding utf8 (Join-Path $env:TEMP 'javap-base.txt')
& javap -p -c (Join-Path $cNew  'CaretAnimator.class') | Out-File -Encoding utf8 (Join-Path $env:TEMP 'javap-new.txt')
git diff --no-index --exit-code (Join-Path $env:TEMP 'javap-base.txt') (Join-Path $env:TEMP 'javap-new.txt')
# 期望：exit=1（有差异），但差异只允许出现在 smoothedDt / effectiveDt / 形参相关指令处
```

判据：`tick` / `tickSpring` / `assignRanks` 的指令差异若出现在**其它**位置（尤其是 `update` 调用、算术运算、`jump` 调用的操作数）→ 失败。

### 3.2 证据② EDT 任务粒度仍是「每 animator 一次 `invokeLater`」

**②-a 结构判定（必做）**

```powershell
$rs = 'D:\deepseekwork\neovim-cursor-pr\src\main\java\com\github\yyyolo7a79\neovidecursor\ui\RenderScheduler.java'
Select-String -Path $rs -Pattern 'invokeLater' -Context 8,12
Select-String -Path $rs -Pattern 'invokeLater\s*\(\s*RenderScheduler::'          # 必须 0 命中
Select-String -Path $rs -Pattern 'runDue|FRAME_PENDING'                          # 必须 0 命中（上游批量写法的标志）
```

通过条件（三条同时满足）：

1. `invokeLater` 出现次数 = **1**，且该调用点位于「遍历到期 animator」的循环体内；
2. 该 `Runnable` 的方法体**只**引用一个 animator（`a.runTick()` / `() -> { if (!a.disposed) a.runTick(); }` 之类），**不含** `for` / `forEach` / 任何多 animator 遍历；
3. 另外两处 `Select-String` 均为空。

**②-b 反例判定（命中即 `reject`）**：`Runnable` 体内出现 `for (`、`forEach`、或对 2 个以上 animator 调用 tick/runTick。

**②-c 运行期计数（推荐；本机可行性已评估）**

口径：让 3 个到期时刻相同的 animator 各产生**一个独立**的 EDT 任务，而不是一个任务里 tick 3 次。

可行做法（需要构造 `CaretAnimator` 实例，用 `java.lang.reflect.Proxy` 伪造 `Editor` / `CaretModel`，返回可用的 `JComponent`，`headless` 下 `SwingUtilities.invokeLater` 仍可投递）：

```java
// 用自定义 EventQueue 统计 InvocationEvent 数量即可，无需真的执行它们
final int[] posted = {0};
EventQueue q = new EventQueue() {
    @Override protected void postEvent(AWTEvent e) {
        if (e instanceof InvocationEvent) posted[0]++;
        // 不转发给父类实现 → 不执行，纯粹计数
    }
};
Toolkit.getDefaultToolkit().getSystemEventQueue().push(q);
```

判据：3 个同时到期的 animator → `posted[0] == 3`（若为 1，即批量合并 → `reject`）。

> 若 verifier 判定该运行期 harness 不可行，**必须在报告里显式声明**「证据②仅达到结构判定层级」，并给出替代证据；**不得**含糊地写成"已验证运行期粒度"。

### 3.3 证据③ 观感常量清单逐项未改 + 白名单 diff 审计

**③-a 常量清单断言（必做）**：运行附录 B-3 的清单脚本（**脚本逐行照抄，格式不得改**），逐行对照 B-3 的冻结值表；再用 B-3 记录的输出哈希做机器复核。

```powershell
$check = Join-Path $env:TEMP 'const-check.ps1'    # 内容 = 附录 B-3 脚本，逐行照抄
$repo  = 'D:\deepseekwork\neovim-cursor-pr'

# ① 基线（用 git archive 抽出 HEAD 源码，不污染工作区）
$zip = Join-Path $env:TEMP 'nc-base-src.zip'; $dst = Join-Path $env:TEMP 'nc-base-src'
if (Test-Path $zip) { Remove-Item -Force $zip }
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
git -C $repo archive --format=zip -o $zip HEAD src/main/java
Expand-Archive -Path $zip -DestinationPath $dst -Force
& pwsh -NoProfile -ExecutionPolicy Bypass -File $check -Repo $dst > (Join-Path $env:TEMP 'const-base.txt')

# ② 新版（直接量当前工作区）
& pwsh -NoProfile -ExecutionPolicy Bypass -File $check > (Join-Path $env:TEMP 'const-new.txt')

git diff --no-index --exit-code (Join-Path $env:TEMP 'const-base.txt') (Join-Path $env:TEMP 'const-new.txt')
(Get-FileHash (Join-Path $env:TEMP 'const-new.txt') -Algorithm SHA256).Hash
# 期望：diff 为空；哈希 = 04D93CAC07A72496A6BD5542CC0B40A4371503765BB6A16EE659F7573E7CFB04
```

> 哈希对**换行符**敏感（请用与上面相同的 `> file` 重定向方式）。若 `const-base.txt` 已由 B-3 的基线运行产出，可直接复用它做 diff；若哈希不符但**逐行内容**与 B-3 表格一致，以逐行判定为准，并在报告中注明换行差异。

**③-b diff 白名单审计（必做）**

```powershell
$repo = 'D:\deepseekwork\neovim-cursor-pr'
git -C $repo status --porcelain -- src/            # 必须只出现：3 个已改文件(M) + 1 个新增 RenderScheduler.java(??)
git -C $repo diff -U0 HEAD -- src/                 # 逐行核对：每条 +/- 必须能归入 T0-1..T0-5 / T1-1
git -C $repo diff -U0 HEAD -- src/ | Select-String '^[+-].*[0-9]'   # 数字行逐条归属
```

判据：任何**无法归属**到 T0-1..T0-5 / T1-1 的增删行 → 失败。允许出现在数字行里的，只有：

1. 日志窗口 `1_000_000_000L` → `5_000_000_000L`（W1）；
2. 新增的 2ms 埋点阈值（W2）；
3. 2.4 删除的 7 个字段声明行；
4. 日志文本/格式串中的编辑器标识（无行为影响）；
5. T1-1 的**基础设施时序常量**（W3，见 4.2）：无到期时的睡眠兜底上限（≤600ms）、`sleep <= 0` 立即派发的判定、EDT 到期判定容差（≤1ms）。

**③-c 死字段清零断言（必做）**

```powershell
Select-String -Path 'D:\deepseekwork\neovim-cursor-pr\src\main\java\com\github\yyyolo7a79\neovidecursor\core\NeovideConfig.java' `
  -Pattern 'cursorDisappearDelay|cursorFadeOutDuration|snapAnimationLength|useHardSnap|leadingSnapFactor|shortJumpMaxChars|typingMergeChars'
# 期望：0 命中
```

**TD0-COMPILE（编译判定，任何一项交付前必跑）**

```powershell
$repo = 'D:\deepseekwork\neovim-cursor-pr'
$out  = Join-Path $env:TEMP 'nc-zeroopt-classes'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
$cp = (Get-ChildItem 'D:\deepseekwork\_idea-anim\idea-libs' -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
$src = Get-ChildItem -Recurse -File -Filter '*.java' (Join-Path $repo 'src\main\java') | ForEach-Object { $_.FullName }
& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $out $src
$LASTEXITCODE      # 期望 0
```

**TD0-THREAD（可选运行期证据：真的只有一条调度线程）**

```powershell
# 只读；会触发一次短暂安全点，执行前需 captain/用户同意
$pid_ = (Get-Process idea64 -ErrorAction SilentlyContinue | Select-Object -First 1).Id
& jcmd $pid_ Thread.print | Select-String 'neovide-cursor-render'
# 期望：恰好 1 行（无论开了几个编辑器）
```

---

## 4. 禁止清单（黑名单）

### 4.1 观感数值常量白名单 —— **只有两项**

| 编号 | 项 | 允许的变化 | 约束 |
|---|---|---|---|
| **W1** | fps 统计窗口 | `1s → ≥5s` | 只影响日志节奏；`fps/frames/window/maxGap/peakRepaintArea/delay` 的计算口径不变 |
| **W2** | `refreshTarget()` 埋点阈值 | **新增** `>2ms` | 只用于决定是否写 debug 日志；不得参与任何行为分支 |

**除此之外的任何观感数值常量变化都算破坏契约**，包括但不限于（列举即禁止，不是穷举）：

- `ACTIVE_DELAY_MS=8`、`IDLE_DELAY_MS=120`、`IDLE_UNFOCUSED_DELAY_MS=600`、`MAX_ADAPTIVE_DELAY_MS=40`、`MAX_DT=1.0/20`、`RECOVERY_GAP_SECONDS=0.1`、`RECOVERY_STEP_CAP_SECONDS=0.5`、`GLOW_FADE_START=0.5`、`GLOW_FADE_END=2.5`；
- `glowLayers=8`、`glowOpacity=0.55f`、`shadowBlurFactor=0.2f`、`animationLength=0.1f`、`shortAnimationLength=0.11f`、`trailSize=1f`、`trailFactors={1.0,0.72,0.42,0.22}`、`typingMaxChars=3f`、`shortMoveThreshold=8f`、`animationResetThreshold=0.09f`、`maxTrailDistanceFactor=60f`、`cursorWidth=2f`、`afterimageLifetime=0.22f`、`alwaysHighFps=true`、`hideNativeCaret=false`、`useShadow=true`、`tailOpacity=1.0f`、颜色值；
- `CaretTrailPanel` 的 `glowWidth=12f` 初值、`updateGlowRadius` 的 `0.5f` 迟滞、`0.5f`/`1e-6`/`√2` 截断、`0.02f`/`0.55f`/`60f`/`255`/`2f`（残影辉光）；
- `TrailCorner` 的 `height*0.5`、`0.001`、`trailFactors` 用法、`(leading+trailing)/2.0`、`rank>=2`；
- `DampedSpring` 的 `4.0/animationLength`、`Math.abs(position) < 0.001`、`>= 0.01`；
- **坐标 `int` → `double`**；`smoothedIntervalMs` 的权重 `0.4` / `0.1` 与 `*0.85`。

### 4.2 T1-1 基础设施时序常量白名单（W3 —— 非观感，必须界定）

T1-1 必然引入调度基础设施的时序常量。**允许且仅允许**下面三类，且**不得**参与动画时长、物理推进、重绘区域、颜色/几何的任何计算：

| 编号 | 常量 | 允许范围 | 作用 |
|---|---|---|---|
| **W3-1** | 无到期任务时的睡眠兜底上限 | ≤600ms | 防止调度线程永久沉睡（等价量级 `IDLE_UNFOCUSED_DELAY_MS`） |
| **W3-2** | "睡眠时长 ≤0 即立刻派发"的判定 | 0 或 1ms | 毫秒取整后的边界处理 |
| **W3-3** | EDT 到期判定的容差 | ≤1ms | 调度线程按毫秒取整睡醒，略早醒也应视为到期 |

任何超出此三类的新数值常量、或把上述常量用于观感语义 → 命中禁止。

### 4.3 绝对禁止（命中即 `reject`）

| 编号 | 禁止项 |
|---|---|
| **P0-1** | 把多个 animator 的 tick 合并进**同一个 EDT 任务**（含照搬上游 `invokeLater(RenderScheduler::runDue)` 批量写法） |
| **P0-2** | 坐标类型 `int → double`（`CaretTrailPanel.polyX/polyY`、`updateCorners` 的取整语义） |
| **P0-3** | `glowLayers` 变更；`maxTrailDistanceFactor` 变更 |
| **P0-4** | 改 `src/**` 之外**任何**会影响运行行为的东西：`plugin.xml`（菜单/服务/版本注入除外且由 t6 处理）、`build/*.ps1`、`dist/**`、`VERSIONS.md`（仅 t6 追加） |
| **P0-5** | 触碰 `TrailCorner.jump()` 的 rank/时长分支、`DampedSpring.update()` 的闭式解、`computeNextDelay()` 的三项策略 |
| **P0-6** | `nx/ny` 提为字段却不清零（见 2.2）；删除 `path.reset()` |
| **P0-7** | 改 `wakeUp()` 的「立即投递」语义（见 2.6 第 1 条） |
| **P0-8** | 改 `analyze-log.ps1` / `diag/extract.py` 或在 fps 行中间插字段导致其正则失配 |
| **P0-9** | 改 `hideNativeCaret` / `alwaysHighFps` 的默认值，或恢复"常驻刷新"菜单项 |
| **P0-10** | 在本批引入 `PersistentStateComponent` / `NeovideConfigurable` / 设置界面（属下一批） |

### 4.4 本批明确不做（防止范围膨胀）

即使看起来"顺手且无害"，也**不许**在本批做（写进报告才算记录，不做才算合规）：

- `maxTrailDistanceFactor 60 → 4`（`START-HERE.md` P2）；
- 残影辉光改为「居中描边」之外的方案（P3）；
- `recordFrameStats` 补 `FPS_IDLE_GAP_NANOS`（P4）；
- `CaretTrailPanel` 复用 `new Polygon` / `new Color` / `new BasicStroke`；
- `assignRanks` 里 `new double[]` / `new int[]` 的复用；
- 残影模式（`AfterimageTrail`）的任何改动；
- 任何重构、重命名、改注释措辞、"顺便修 typo"（除 2.4 允许的删除项）；
- 更新 `README*.md` 与 `HANDOFF*.md`。

---

## 5. 回退方式

**权威回退目标**（哈希可验证）：

```
文件：D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.0-ahf-default.jar
SHA256：FA161E4F77C8C99992381DD489F7C172A38F56BB62DC3C2D45D8856AD8C95CD5
前 16 位：FA161E4F77C8C999
大小：39937 B
```

**验证回退目标未被污染**：

```powershell
(Get-FileHash 'D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.0-ahf-default.jar' -Algorithm SHA256).Hash
# 必须等于 FA161E4F77C8C99992381DD489F7C172A38F56BB62DC3C2D45D8856AD8C95CD5
```

**运行期回退（必须完全退出 IDEA 后再执行；此步骤由 captain/用户执行，本批的 AI 成员不代为安装）**：

```powershell
$lib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib'
$cur = Join-Path $lib 'jetbrains-neovide-cursor-0.5.0.jar'
Move-Item $cur "$cur.replaced-$(Get-Date -Format 'yyyyMMdd-HHmmss')"     # 先挪，不删
Copy-Item 'D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.0-ahf-default.jar' $cur
(Get-FileHash $cur -Algorithm SHA256).Hash
# 必须等于 FA161E4F77C8C99992381DD489F7C172A38F56BB62DC3C2D45D8856AD8C95CD5
```

**源码回退（本批改动未提交时）**：

```powershell
cd D:\deepseekwork\neovim-cursor-pr
git status --porcelain -- src/                 # 先看清要回退什么
git stash push -u -- src/                      # 或 git checkout -- src/ + 手动删除新增文件
```

**规则**：

1. 回退以 **jar 为准**（哈希可验证）；源码回退只是辅助。
2. **`dist/` 里的 jar 一律不删不覆盖**（`dist/` 是唯一历史档）。
3. 回退**前**必须完全退出 IDEA（它内存映射锁住 jar，直接覆盖会报"用户映射区域"错误）。
4. 回退不改变本契约的效力：批次1 交付的判定仍按本文件执行。

---

## 6. fps 日志的开启方法（Debug Log Settings）

1. IDEA：**Help → Diagnostic Tools → Debug Log Settings**。
2. 在文本框里填 logger 名（严格照抄，`#` 开头表示"该类别及其子类别"）：

```
#com.github.yyyolo7a79.neovidecursor
```

3. OK 后**立即生效**，无需重启。日志文件：`C:\Users\wangw\AppData\Local\JetBrains\IntelliJIdea2026.1\log\idea.log`
   （或 Help → Show Log in Explorer）。
4. 关闭方法：把那一行从 Debug Log Settings 里删掉。

**注意事项**

- 该 logger 名依据：`LOG = Logger.getInstance(CaretAnimator.class)`，类别为 `com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator`；`#com.github.yyyolo7a79.neovidecursor` 覆盖本插件全部日志。
- **开启 debug 后才会出现 fps 行**（T0-1 之后）；同时 `recovery` 与 `refreshTarget` 两条 `LOG.debug` 也会出现 —— 这正是诊断所需，但要意识到这只是**诊断态**。
- 多开编辑器时，用行尾的编辑器标识区分；**标识字段在行尾**，`build/analyze-log.ps1` 与 `diag/extract.py` 的既有正则仍可用。
- 直接抓取（不经脚本）的等价命令：

```powershell
Select-String -Path 'C:\Users\wangw\AppData\Local\JetBrains\IntelliJIdea2026.1\log\idea.log' `
  -Pattern 'neovide-cursor: fps=' | Select-Object -Last 20 | ForEach-Object { $_.Line }
```

---

## 附录 A：parity harness 源码（逐行照抄，不得改格式）

### A-1 `tools/parity/PhysicsParity.java`

```java
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

/**
 * 物理「逐位一致」parity harness（契约附件 A-1，由 verifier 落盘到 tools/parity/，逐行照抄、不得改格式）。
 *
 * <p>判定：基线源码与本批源码各跑一次，输出必须【字节完全相同】。
 * 输出只用 doubleToRawLongBits 的 16 进制，避免任何格式化误差。
 *
 * <p>只依赖 core 包（不依赖 IntelliJ 平台），因此可直接 javac 编译。
 */
public class PhysicsParity {

    static final double[][] REL = {
            {-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5},
    };

    /**
     * 固定的 dt 序列（秒），每步按 f 下标取：
     * 8ms（正常帧）×2 → 16.7ms → 33.3ms → 50ms（MAX_DT 边界）→ 120ms（&gt;RECOVERY_GAP，触发短路归位）。
     * 首帧恒为 8ms，保证每一步都能看到弹簧展开过程，同时覆盖极端 dt 边界。
     */
    static final double[] DTS = {
            8.0 / 1000, 8.0 / 1000, 16.7 / 1000, 33.3 / 1000, 50.0 / 1000, 0.12,
    };

    static void scenario(String name, NeovideConfig cfg,
                         double[][] path, int[] charsMoved) {
        System.out.println("### " + name);
        double w = cfg.cursorWidth;
        double h = 32;

        TrailCorner[] c = new TrailCorner[4];
        for (int i = 0; i < 4; i++) {
            c[i] = new TrailCorner(REL[i][0], REL[i][1], cfg);
        }

        // 初始吸附到第一个目标点
        for (TrailCorner t : c) {
            t.snapTo(w, h, path[0][0] + w / 2.0, path[0][1] + h / 2.0);
        }

        for (int step = 1; step < path.length; step++) {
            double cx = path[step][0] + w / 2.0;
            double cy = path[step][1] + h / 2.0;
            for (TrailCorner t : c) {
                t.setCharsMoved(charsMoved[step]);
            }
            // rank 0..3 各调一次（与 CaretAnimator.assignRanks 的调用语义一致）
            for (int r = 0; r < 4; r++) {
                c[r].jump(w, h, cx, cy, r);
            }
            for (int f = 0; f < DTS.length; f++) {
                boolean any = false;
                for (int r = 0; r < 4; r++) {
                    any |= c[r].update(w, h, cx, cy, DTS[f], false);
                }
                System.out.print("s" + step + "f" + f + " any=" + any);
                for (int r = 0; r < 4; r++) {
                    System.out.print(" x" + r + "=" + Long.toHexString(
                            Double.doubleToRawLongBits(c[r].getCurrentX())));
                    System.out.print(" y" + r + "=" + Long.toHexString(
                            Double.doubleToRawLongBits(c[r].getCurrentY())));
                    System.out.print(" o" + r + "=" + Long.toHexString(
                            Double.doubleToRawLongBits(c[r].getOffsetMagnitude())));
                }
                System.out.println();
            }
        }
    }

    public static void main(String[] args) {
        NeovideConfig cfg = new NeovideConfig();
        System.out.println("config: cursorWidth=" + cfg.cursorWidth
                + " animationLength=" + cfg.animationLength
                + " shortAnimationLength=" + cfg.shortAnimationLength
                + " trailSize=" + cfg.trailSize
                + " typingMaxChars=" + cfg.typingMaxChars
                + " trailFactors=" + java.util.Arrays.toString(cfg.trailFactors));

        // ① 同行逐字符（短跳分支）
        scenario("A_shortjump_typing", cfg,
                new double[][]{{0, 0}, {8, 0}, {16, 0}, {24, 0}, {32, 0}},
                new int[]{-1, 1, 1, 1, 1});

        // ② 删除中文（宽字符）：offset 差 1、像素差 20
        scenario("B_cjk_delete", cfg,
                new double[][]{{100, 0}, {80, 0}, {60, 0}, {40, 0}},
                new int[]{-1, 1, 1, 1});

        // ③ 跨行大跳（trailSize 三档分支）
        scenario("C_crossline_jump", cfg,
                new double[][]{{10, 10}, {10, 10 + 32 * 6}, {10, 10 + 32 * 12}},
                new int[]{-1, 40, 40});

        // ④ 退化：目标不变（长时间静止后再次 jump）
        scenario("D_static_repeat", cfg,
                new double[][]{{5, 5}, {5, 5}, {5, 5}},
                new int[]{-1, -1, -1});

        System.out.println("### end");
    }
}
```

### A-2 `tools/parity/GeometryParity.java`

```java
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;

import java.awt.Polygon;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 几何「逐位一致」parity harness（契约附件 A-2，由 verifier 落盘到 tools/parity/）。
 *
 * <p>与 PhysicsParity 的区别：本 harness 反射调用**真实类**的私有方法
 * {@code CaretTrailPanel.computeOffsetDirections}，因此能抓住「把 nx/ny 提为字段复用后
 * 忘记清零 → 退化四边形读到上一帧残留法线」这类陷阱（本地副本式验算做不到这一点）。
 *
 * <p>每个多边形连调两次：第二次模拟"同一次绘制链路的后续调用"，残留状态会在这里暴露。
 *
 * <p>编译需要 idea-libs（CaretTrailPanel 依赖 org.jetbrains.annotations）；
 * 运行 headless 即可（不创建窗口）。
 */
public class GeometryParity {

    static int seq = 0;

    static void run(CaretTrailPanel panel, Method m, String name, Polygon p) throws Exception {
        boolean isStatic = Modifier.isStatic(m.getModifiers());
        System.out.println("### " + name);
        for (int pass = 0; pass < 2; pass++) {
            double[] dx = new double[4];
            double[] dy = new double[4];
            Object target = isStatic ? null : panel;
            m.invoke(target, p, dx, dy);
            StringBuilder sb = new StringBuilder();
            sb.append("seq=").append(seq++).append(" pass=").append(pass);
            for (int i = 0; i < 4; i++) {
                sb.append(" dx").append(i).append('=')
                        .append(Long.toHexString(Double.doubleToRawLongBits(dx[i])));
                sb.append(" dy").append(i).append('=')
                        .append(Long.toHexString(Double.doubleToRawLongBits(dy[i])));
            }
            System.out.println(sb);
        }
    }

    public static void main(String[] args) throws Exception {
        NeovideConfig cfg = new NeovideConfig();
        CaretTrailPanel panel = new CaretTrailPanel(cfg);
        Method m = CaretTrailPanel.class.getDeclaredMethod(
                "computeOffsetDirections", Polygon.class, double[].class, double[].class);
        m.setAccessible(true);

        // ① 静止矩形 2×32（用户最关注的场景）
        run(panel, m, "R_rest_rect", new Polygon(
                new int[]{0, 2, 2, 0}, new int[]{0, 0, 32, 32}, 4));

        // ② 拖尾中间态（斜四边形）
        run(panel, m, "S_trail_slant", new Polygon(
                new int[]{10, 12, 40, 38}, new int[]{0, 0, 28, 28}, 4));

        // ③ 极端跳转（细长斜条，锐角被 SQRT_2 压住）
        run(panel, m, "T_extreme_thin", new Polygon(
                new int[]{100, 102, 104, 102}, new int[]{0, 0, 300, 300}, 4));

        // ④ 退化：四角重合  ← nx/ny 残留陷阱的探测器（基线期望全 0）
        run(panel, m, "D_degenerate_point", new Polygon(
                new int[]{50, 50, 50, 50}, new int[]{50, 50, 50, 50}, 4));

        // ⑤ 退化：单条零长边（j=0→j=1 重合），其余正常
        run(panel, m, "E_zero_length_edge", new Polygon(
                new int[]{7, 7, 9, 7}, new int[]{3, 3, 33, 33}, 4));

        // ⑥ 大矩形（跨行拖到尾）
        run(panel, m, "F_wide", new Polygon(
                new int[]{0, 400, 400, 0}, new int[]{0, 0, 32, 32}, 4));

        // ⑦ 负坐标（面板夹紧前可能出现的越界值）
        run(panel, m, "G_negative", new Polygon(
                new int[]{-40, -20, -20, -40}, new int[]{-10, -10, 60, 60}, 4));

        // ⑧ 反向绕行（顶点顺序相反 → 法线应指向外）
        run(panel, m, "H_reversed_order", new Polygon(
                new int[]{0, 0, 2, 2}, new int[]{0, 32, 32, 0}, 4));

        System.out.println("### end");
    }
}
```

> A-2 说明：若 T0-2 采用「A1 彻底」方案把 `computeOffsetDirections` 改为实例方法，harness 已用 `Modifier.isStatic` 自适应，无需改动。

---

## 附录 B：基线快照与已实测的命令结果

> 以下全部在基线 `321b0a0` 上由 reviewer 实测（Windows / `pwsh` 7 / `javac 25.0.2`，`--release 21`）。
> verifier 先复现这些基线值，再对新版做同样操作。

### B-1 parity harness 基线输出

| 项 | 值 |
|---|---|
| `PhysicsParity` 输出 | 72 行，SHA256 = `0A4FDDE69C3EBC32DF23AEF17D8EAFF15CDA0B25E5E04152B7076AB8C1003883` |
| `GeometryParity` 输出 | 25 行，SHA256 = `4057AFA03E768C842990FD4B7B4B6D5AF7ED46830A848E8ED99900FFFB526B44` |
| A-1 源码 SHA256 | `B2CE9268049F006B7DE758A671DD0E19520EC39BC9B61598B82ACA9199150A90`（**该哈希含行尾差异**：CRLF/LF 不同即不同。请以「与附录 A 逐行一致」为准；parity 结论只取决于下面两个**输出**哈希） |
| A-2 源码 SHA256 | `24AD2408D77EB4541F56CEF584FCCF96A2E6369610CDD26053E5862C3C3DE622`（同上说明） |
| `GeometryParity` 退化用例期望 | 四角重合（`D_degenerate_point`）→ `dx*/dy*` 全为 `0`（16 进制的 `0`） |

### B-2 基线编译

```
javac --release 21 -nowarn -g -encoding UTF-8 -classpath <idea-libs 381 jar> -d <tmp> <src 13 个 .java>
→ exit 0，sources=13
core-only 编译（不挂 idea-libs）→ exit 0   ← 证明 A-1 可独立编译
```

**3.1 的整段命令块（含 `Run-Parity` 函数）也已实测通过**：`javac`/`java` 均 exit 0；因为此刻「新版 = 基线」，
两次输出 diff 均为 exit 0（这正是交付前应有的状态）。classpath 实测长度 **27,214 字符**，低于 Windows
单条命令行的 32,767 上限 —— 因此该命令块可以直接复制执行，不需要改用 `@argfile`。

### B-3 观感常量清单脚本 + 基线输出（冻结期望）

脚本（另存为 `$env:TEMP\const-check.ps1`；`-Repo` 指向待检源码树的仓库根，默认当前仓库）：

```powershell
param([string]$Repo = 'D:\deepseekwork\neovim-cursor-pr')
$src = Join-Path $Repo 'src\main\java\com\github\yyyolo7a79\neovidecursor'
$P = @(
 @('core/NeovideConfig.java','tailColor = "[^"]+"'),
 @('core/NeovideConfig.java','tailOpacity = [0-9.]+f'),
 @('core/NeovideConfig.java','useShadow = (true|false)'),
 @('core/NeovideConfig.java','shadowColor = "[^"]+"'),
 @('core/NeovideConfig.java','shadowBlurFactor = [0-9.]+f'),
 @('core/NeovideConfig.java','glowLayers = [0-9]+'),
 @('core/NeovideConfig.java','glowOpacity = [0-9.]+f'),
 @('core/NeovideConfig.java','alwaysHighFps = (true|false)'),
 @('core/NeovideConfig.java','float animationLength = [0-9.]+f'),
 @('core/NeovideConfig.java','shortAnimationLength = [0-9.]+f'),
 @('core/NeovideConfig.java','trailSize = [0-9.]+f'),
 @('core/NeovideConfig.java','trailFactors = \{[^}]+\}'),
 @('core/NeovideConfig.java','shortMoveThreshold = [0-9.]+f'),
 @('core/NeovideConfig.java','typingMaxChars = [0-9.]+f'),
 @('core/NeovideConfig.java','animationResetThreshold = [0-9.]+f'),
 @('core/NeovideConfig.java','maxTrailDistanceFactor = [0-9.]+f'),
 @('core/NeovideConfig.java','cursorWidth = [0-9.]+f'),
 @('core/NeovideConfig.java','hideNativeCaret = (true|false)'),
 @('core/NeovideConfig.java','trailMode = TrailMode\.[A-Z]+'),
 @('core/NeovideConfig.java','afterimageLifetime = [0-9.]+f'),
 @('ui/CaretAnimator.java','int ACTIVE_DELAY_MS = [0-9]+'),
 @('ui/CaretAnimator.java','int IDLE_DELAY_MS = [0-9]+'),
 @('ui/CaretAnimator.java','int IDLE_UNFOCUSED_DELAY_MS = [0-9]+'),
 @('ui/CaretAnimator.java','int MAX_ADAPTIVE_DELAY_MS = [0-9]+'),
 @('ui/CaretAnimator.java','MAX_DT = [0-9.]+ / [0-9]+'),
 @('ui/CaretAnimator.java','RECOVERY_GAP_SECONDS = [0-9.]+'),
 @('ui/CaretAnimator.java','RECOVERY_STEP_CAP_SECONDS = [0-9.]+'),
 @('ui/CaretAnimator.java','GLOW_FADE_START = [0-9.]+'),
 @('ui/CaretAnimator.java','GLOW_FADE_END = [0-9.]+'),
 @('ui/CaretAnimator.java','^\s*smoothedDt \+= \(dt - smoothedDt\) \* [0-9.]+'),
 @('ui/CaretAnimator.java','smoothedIntervalMs \* [0-9.]+'),
 @('ui/CaretAnimator.java','weight = actualMs < smoothedIntervalMs \? [0-9.]+ : [0-9.]+'),
 @('ui/CaretTrailPanel.java','glowWidth = [0-9.]+f'),
 @('ui/CaretTrailPanel.java','SQRT_2 = Math\.sqrt\(2\.0\)'),
 @('ui/CaretTrailPanel.java','glowStrength = [0-9.]+f'),
 @('ui/CaretTrailPanel.java','glowWidth <= [0-9.]+f'),
 @('ui/CaretTrailPanel.java','newWidth - glowWidth\) > [0-9.]+f'),
 @('ui/CaretTrailPanel.java','alpha <= [0-9.]+f'),
 @('ui/CaretTrailPanel.java','alpha > [0-9.]+f'),
 @('ui/CaretTrailPanel.java','glowOpacity \* [0-9.]+f'),
 @('ui/CaretTrailPanel.java','Math\.min\(255,'),
 @('core/TrailCorner.java','height \* [0-9.]+'),
 @('core/TrailCorner.java','Math\.abs\(jumpY\) <= [0-9.]+'),
 @('core/TrailCorner.java','Math\.max\(width, height\) \* config\.maxTrailDistanceFactor'),
 @('core/DampedSpring.java','Math\.abs\(position\) < [0-9.]+'),
 @('core/DampedSpring.java','Math\.abs\(position\) >= [0-9.]+'),
 @('core/DampedSpring.java','double o = [0-9.]+ / animationLength')
)
foreach ($p in $P) {
  $f = Join-Path $src ($p[0].Replace('/','\'))
  $h = Select-String -Path $f -Pattern $p[1]
  $v = if ($h) { ($h | ForEach-Object { $_.Line.Trim() }) -join ' || ' } else { '<未匹配>' }
  "{0,-58} | {1}" -f $p[1], $v
}
```

基线值冻结表（**③-a 必须逐项、逐字相同**）；下面的哈希用于机器复核：

```text
（机器判定：下面是对基线运行上表脚本所得的输出哈希；人类判定：逐行对照下表）
SHA256 = 04D93CAC07A72496A6BD5542CC0B40A4371503765BB6A16EE659F7573E7CFB04
行数 = 47
```

| 检查项（正则） | 基线值（必须逐字不变） |
|---|---|
| `tailColor = "[^"]+"` | `public String tailColor = "#FFC0CB";` |
| `tailOpacity = [0-9.]+f` | `public float tailOpacity = 1.0f;` |
| `useShadow = (true\|false)` | `public boolean useShadow = true;` |
| `shadowColor = "[^"]+"` | `public String shadowColor = "#FFC0CB";` |
| `shadowBlurFactor = [0-9.]+f` | `public float shadowBlurFactor = 0.2f;` |
| `glowLayers = [0-9]+` | `public int glowLayers = 8;` |
| `glowOpacity = [0-9.]+f` | `public float glowOpacity = 0.55f;` |
| `alwaysHighFps = (true\|false)` | `public boolean alwaysHighFps = true;` |
| `float animationLength = [0-9.]+f` | `public float animationLength = 0.1f;` |
| `shortAnimationLength = [0-9.]+f` | `public float shortAnimationLength = 0.11f;` |
| `trailSize = [0-9.]+f` | `public float trailSize = 1f;` |
| `trailFactors = \{[^}]+\}` | `public float[] trailFactors = {1.0f, 0.72f, 0.42f, 0.22f};` |
| `shortMoveThreshold = [0-9.]+f` | `public float shortMoveThreshold = 8f;` |
| `typingMaxChars = [0-9.]+f` | `public float typingMaxChars = 3f;` |
| `animationResetThreshold = [0-9.]+f` | `public float animationResetThreshold = 0.09f;` |
| `maxTrailDistanceFactor = [0-9.]+f` | `public float maxTrailDistanceFactor = 60f;` |
| `cursorWidth = [0-9.]+f` | `public float cursorWidth = 2f;` |
| `hideNativeCaret = (true\|false)` | `public boolean hideNativeCaret = false;` |
| `trailMode = TrailMode\.[A-Z]+` | `public TrailMode trailMode = TrailMode.SPRING;` |
| `afterimageLifetime = [0-9.]+f` | `public float afterimageLifetime = 0.22f;` |
| `int ACTIVE_DELAY_MS = [0-9]+` | `static final int ACTIVE_DELAY_MS = 8;` |
| `int IDLE_DELAY_MS = [0-9]+` | `private static final int IDLE_DELAY_MS = 120;` |
| `int IDLE_UNFOCUSED_DELAY_MS = [0-9]+` | `private static final int IDLE_UNFOCUSED_DELAY_MS = 600;` |
| `int MAX_ADAPTIVE_DELAY_MS = [0-9]+` | `private static final int MAX_ADAPTIVE_DELAY_MS = 40;` |
| `MAX_DT = [0-9.]+ / [0-9]+` | `private static final double MAX_DT = 1.0 / 20;` |
| `RECOVERY_GAP_SECONDS = [0-9.]+` | `private static final double RECOVERY_GAP_SECONDS = 0.1;` |
| `RECOVERY_STEP_CAP_SECONDS = [0-9.]+` | `private static final double RECOVERY_STEP_CAP_SECONDS = 0.5;` |
| `GLOW_FADE_START = [0-9.]+` | `private static final double GLOW_FADE_START = 0.5;` |
| `GLOW_FADE_END = [0-9.]+` | `private static final double GLOW_FADE_END = 2.5;` |
| `^\s*smoothedDt \+= \(dt - smoothedDt\) \* [0-9.]+` | `smoothedDt += (dt - smoothedDt) * 0.25;` |
| `smoothedIntervalMs \* [0-9.]+` | `int adaptive = (int) Math.round(smoothedIntervalMs * 0.85);` |
| `weight = actualMs < smoothedIntervalMs \? [0-9.]+ : [0-9.]+` | `double weight = actualMs < smoothedIntervalMs ? 0.4 : 0.1;` |
| `glowWidth = [0-9.]+f` | `private float glowWidth = 12f;` |
| `SQRT_2 = Math\.sqrt\(2\.0\)` | `private static final double SQRT_2 = Math.sqrt(2.0);` |
| `glowStrength = [0-9.]+f` | `private float glowStrength = 1f;` |
| `glowWidth <= [0-9.]+f` | `if (!config.useShadow \|\| config.glowLayers <= 0 \|\| glowWidth <= 0.5f) {` |
| `newWidth - glowWidth\) > [0-9.]+f` | `if (Math.abs(newWidth - glowWidth) > 0.5f) {` |
| `alpha <= [0-9.]+f` | `if (alpha <= 0.02f) {` |
| `alpha > [0-9.]+f` | `if (config.useShadow && alpha > 0.55f) {` |
| `glowOpacity \* [0-9.]+f` | `int glowAlpha = Math.round(alpha * config.glowOpacity * 60f);` |
| `Math\.min\(255,` | `g2.setColor(new Color(red, green, blue, Math.min(255, glowAlpha)));` |
| `height \* [0-9.]+` | `boolean sameLine = Math.abs(deltaYpx) <= height * 0.5;` |
| `Math\.abs\(jumpY\) <= [0-9.]+` | `isShortJump = Math.abs(jumpX) <= config.shortMoveThreshold && Math.abs(jumpY) <= 0.001;` |
| `Math\.max\(width, height\) \* config\.maxTrailDistanceFactor` | `double maxDistance = Math.max(width, height) * config.maxTrailDistanceFactor;` |
| `Math\.abs\(position\) < [0-9.]+` | `if (animationLength <= dt \|\| Math.abs(position) < 0.001) {` |
| `Math\.abs\(position\) >= [0-9.]+` | `return Math.abs(position) >= 0.01;` |
| `double o = [0-9.]+ / animationLength` | `final double o = 4.0 / animationLength;` |

> 说明：表中最后一项用 `^\s*smoothedDt \+=` 锚定，只会命中**代码行**，不会命中注释（基线 `L418` 的注释行不算）。**T0-3 若采用方案 A**，该行会消失（clean 的"删除死链路"）；**若采用方案 B**，该行必须原样保留 —— 两种情况都必须在报告里显式列出「预期消失/保留的行」，不得当成"常量被改动"。

### B-4 T0-4 前置 grep（基线结果）

```
grep 'cursorDisappearDelay|cursorFadeOutDuration|snapAnimationLength|useHardSnap|leadingSnapFactor|shortJumpMaxChars|typingMergeChars'
→ src/** 命中仅 7 处，全部是 NeovideConfig.java 的声明本身（L101/L104/L199/L203/L208/L212/L233）
→ 其它命中位于：README*.md、HANDOFF*.md、HANDOFF-full-history.md、UPSTREAM-PITFALLS.md、diag/AUDIT-CAPTAIN-NOTES.md、_backup-20261018-160453/workspace-changes.patch（均非代码引用）
```

### B-5 fps 日志实测（基线）

```
idea.log 路径 : C:\Users\wangw\AppData\Local\JetBrains\IntelliJIdea2026.1\log\idea.log
文件大小      : 6,284,402 B
'neovide-cursor: fps=' 行数 : 2283
这些行的字节数 : 454,872 B  →  占全文件 7.24%
```

### B-6 回退目标哈希实测

```
dist\jetbrains-neovide-cursor-0.9.0-ahf-default.jar
  = FA161E4F77C8C99992381DD489F7C172A38F56BB62DC3C2D45D8856AD8C95CD5   (39937 B)

已安装 jar: ...\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.5.0.jar
  = FA161E4F77C8C99992381DD489F7C172A38F56BB62DC3C2D45D8856AD8C95CD5   (39937 B)   ← 与 dist 同哈希
```

---

## 附录 C：交付与记录要求（供 t6 集成）

1. `dist/` 产出命名：`jetbrains-neovide-cursor-0.9.1-zeroopt.jar`；产物 SHA256 前 16 位写入报告。
2. `VERSIONS.md` **必须追加一行**（不得改写历史行），至少含：时间、版本 `0.9.1-zeroopt`、本批 6 项改动关键词、SHA256 前 8 位、回退命令指向 0.9.0（`FA161E4F77C8C999`）。
3. `README*.md` / `HANDOFF*.md` 本批**不改**。
4. 安装说明必须写清：**先完全退出 IDEA**，先 `Move-Item` 旧 jar 再 `Copy-Item` 新 jar。
5. 证据文件建议放在新目录（如 `diag/zeroopt-b1/`），**不得覆盖**既有诊断产物。

---

## 附录 D：本契约的已知边界（诚实声明）

1. 证据①只能覆盖**纯计算**部分（`TrailCorner`/`DampedSpring` 物理 + `computeOffsetDirections` 几何）。`drawQuadGlow` 每层 `g2.fill(path)` 的**最终像素**没有纳入自动化比对（需要录制型 `Graphics2D`，成本高）。本批对该处的零观感判定依赖：几何结果逐位一致 + 源码 diff 审计（算术行未变）。
2. 证据②的"运行期任务计数"需要 `java.lang.reflect.Proxy` 伪造平台对象，属推荐项；若未达成，报告必须显式声明只到"结构判定"层级。
3. 证据③的常量清单是**枚举式**的（覆盖已知观感量），不是"任意数值都拦得住"的形式化证明；因此 ③-b 的 diff 白名单审计是**必做**的补充，二者同时通过才算证据③成立。
4. 本契约不覆盖"用户主观手感"，那是最终一步的人工验收；本契约的职责是把**除主观之外**的所有可判定项钉死。
