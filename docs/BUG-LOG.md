# Bug 记录（按发现顺序，含已被推翻的错误结论）

> 这份文件的目的：**记下我判断错过的地方**。只记"修好了什么"会让下一个人
> 重复同样的错误推理。每条都写「我怎么想的 / 实际是什么 / 为什么错」。

---

## BUG-001 新打开的编辑器不同步全局设置（0.9.61）

**用户报**：「我选 RGB 勾选上之后，我新打开的文件光标坍缩回没 RGB 的时候了」

**根因**：光标宽度 / 高度比例 / RGB 三项是持久级配置，但只在 `onModeChanged()`
里从 State 读，而 `onModeChanged()` 只由 `applyConfig()` / `setTrailMode()` 触发 ——
全是「用户点完设置面板」这条路径。新编辑器走构造函数 `new CaretAnimator`，
一条都没读，停在 Java 默认值。

**修法**：抽出 `syncGlobalSettings()`，构造函数与 `onModeChanged()` 都调它。
字段默认值补成 2 / 1.0f / false，保证服务取不到时也不退回 0。

**教训**：凡是「持久级配置」都要问一句「新实例怎么拿到它」。
只在"设置面板回调"里读的字段，新窗口必然是默认值。

---

## BUG-002 光标变短后不居中（0.9.61）

**用户报**：「光标长度变小后不在中间 很怪 不是上下缩减的」

**根因**：垂直锚点写的是 `cp.y + height / 2.0`。height 缩小后这个中心跟着上移，
而 `cp.y`（行顶）不动 —— 上边界恒在行顶、下边界往上收，看起来从下往上被砍掉。

**修法**：改用整行的垂直中心 `cp.y + lineHeight / 2.0`，上下各让一半。
水平仍锚 `cp.x`（左边缘），与 IDEA 原生光标对齐。

**教训**：几何锚点要问「这个尺寸变化时，哪个方向应该对称」。

---

## BUG-003 fps 统计少算 15 倍（0.9.62 / 0.9.63）

**用户报**：「比之前卡了至少20帧」（依据是日志里的 `fps=6.8` 与 `fps=58`）

**实际情况：那个数字是错的，真机是满帧。**

用日志时间戳重算（相邻时刻差 ÷ frameSeq 差）：
```
carets=1 → 每帧 8.0ms  → 125fps
carets=5 → 每帧 8.0ms  → 125fps
carets=8 → 每帧 8.53ms → 117fps
```

**两个错叠在一起**：
1. `frameSummary` 只在采样帧被调（`frameSeq % 15 == 0`），计数器数的是
   「采样次数」不是「帧数」，直接除以时间得到的是采样率 → 少算 15 倍。
2. 那个计数器是 `static`，多个编辑器的 animator 往同一个计数器累加 → 会翻倍。

**修法**：`frameSummary` 自己用「序号差 ÷ 时间差」算，只用本行能拿到的两个量
（`frameSeq` 是每个 animator 的实例字段，`System.nanoTime` 单调）。
同时打印 `fps=`（真实帧率）与 `activeFps=`（活动期帧率）。

**教训**：**先验证仪表，再相信仪表**。我基于一个错误的 fps 读数做了整整一轮
性能分析和优化，方向全错。

---

## BUG-004 诊断日志的 layers 字段恒为 0（0.9.63 发现）

**现象**：日志里 `layers=0`，看起来像"辉光根本没画"。用户反驳「辉光有啊」。

**根因**：`CursorDiag.glowLayersNow` 只在 `tickStaticCaret()` 里被赋值，
弹簧路径（`tickSpring`）从不写它。而弹簧路径才是日常使用的那条。

**修法**：在弹簧路径按本帧实际生效的层数写入。

**教训**：诊断字段的写入点必须覆盖**所有**调用路径，否则它会安静地撒谎。

---

## BUG-005 IDEA 终端/控制台里光标不播动画（2026-10-28 用户报，**未定位**）

**用户报**：IDEA 终端里「没有效果」——**只有我们的光标，没有动画**；
但 **git 提交消息框里正常**；**运行时的黑色控制台里也有我们的光标，同样没有动画**。

**当前状态：未定位、未验证。优先级低** —— 用户已明确「005 这个**不算严重** bug」。
本条只记录现象，未做任何代码排查。

**与已有结论的张力（后续核对时不要直接采信任何一边）**：
`docs/design/IDEAS-2026-10-21.md` 记过「终端**不接管**（终端自己画光标，接管会双光标）…保持排除」，
并把验收写成「提交框、控制台、主编辑器三者都要有拖尾，**终端没有**」。
但用户现在看到的是**终端里已经有我们的光标**（说明被接管了），只是不播动画 ——
这与「不接管」的假设不一致。可能的情况（**均为猜测，未验证**）：
- 终端走的是另一条接管路径，落在静态光标分支，没进帧调度；
- 或接管发生了，但 `RenderScheduler` 没为该编辑器注册。

动手前先读 `docs/design/INPUT-ALL-SURFACES-RESEARCH.md`（终端路线 C / `JediTermWidget` 反射）。

**为什么记下来**：这条会把「终端不接管」当既定事实的人带偏 ——
现状更接近「接管了，但动画不工作」。

---

## BUG-006 自动生成 `@Contract(pure = true)` 后光标停在上一行（**严重**）

**用户报（2026-10-28，按用户原意转述，不改其判断）**：

> 我正在写方法，方法写好了在 **20 行**，结果 IDEA 自动在方法上方生成了一个
> `@Contract(pure = true)`（**灰色，点不动**）。本来在 20 行的光标跳到 19 或 21 行。
> 准确描述是：**19 行空了一段放 `@Contract(pure = true)`**，这个是**方法成形后突然生成的**；
> 然后是 20 行方法头部。假如我打上 `{}`，在括号中间打 enter 到下一行，
> 光标本应该在 **21**，**结果跳到 20**。

**定性：严重 bug**（用户明确：「n1 就是严重 bug」）。

**用户给出的成因判断**：

> 坐标没有考虑到 `@Contract(pure = true)` 这种**不计入文件、但会显示在 IDEA 里的空行**，
> 绘制坐标错误，以为我没动，或者重定位错误。

即：存在**一行只参与渲染、不参与文件内容**的行，而我们的坐标换算没有把它算进去 ——
于是「光标有没有动」与「该画在哪」两个判断同时错位。

**为什么算严重**：
1. 发生在**最高频的编码动作**（写方法）上；
2. 后果是**下一次敲的字符进错行**，不是观感瑕疵；
3. 属于「光标服务不可信」，而光标服务是本插件唯一的产品价值。

**当前状态（2026-10-28 更新）：已定位、已修复、离线探针验证通过；真机复验待用户执行。**

**待查方向（从用户描述可拆成两个正交问题，别混在一起修）**：
- **坐标换算**：文件行号 → 绘制坐标时，走的是哪条换算路径？是否用了**不感知虚拟行**的那条。
  先定位是 `refreshTarget()` 取 `VisualPosition` 时错的，还是 `CaretTrailPanel` 算 y 时错的；
- **位移判定**：「动没动」（`charsMoved` / 目标变化判定）是否也基于同一份未计入虚拟行的坐标。

**最小可复现**：写一个方法触发 @Contract 自动插入，先量出错位是 1 行还是 2 行，再动手。
`CaretAnimator.refreshTarget()`（611 行巨石方法，也是审计 P-4 未修缺陷所在）大概率相关，
但**不要一上来就改那里**。

---

### 根因（2026-10-28 定位）

**先回答「1 行还是 2 行、行号还是像素」这两个问题**，因为它们的答案决定了修法：

```
错位量      1 个行高（= inlay 的高度），不是 2 行
错位维度    「行号没变、像素变了」
           视觉行号 (VisualPosition.line) 不动
           文档修改戳 不动（inlay 不是文件内容）
           只有 visualPositionToXY() 的 y 下移了一个 inlay 高度
```

这不是从现象猜的，是从**平台字节码**读出来的（`intellij.platform.ide.impl.jar`，
`EditorCoordinateMapper`，javap 反编译）：

```java
// ① 行号：只把 after-line-end 元素算进视觉行
logToVisWithInlays(logicalLine, visualLine, softWrap):
    if (visualLine > 1 || (visualLine == 1 && softWrap))
        visualLine += inlayModel.getAfterLineEndElementsForLogicalLine(logicalLine).size();
    // ↑ block inlay（ABOVE_LINE / BELOW_LINE）不参与 → 行号不变

// ② 像素：把「显示在上方」的 block inlay 高度加进 y
visualLineToYRange(line):
    y = insets.top + line * lineHeight + foldingAdjustment
        + inlayModel.getHeightOfBlockElementsBeforeVisualLine(line, offset, collapsed)
    // 而该方法的 lambda 只累加 myShowAbove 的 getHeightInPixels()
    // ↑ block inlay 撑高了 y
```

`@Contract(pure = true)` 那一行正是 **ABOVE_LINE block inlay**：不改文档、不占行号、只撑高像素。

**然后是我们自己的两处判断，全都只看这三个分量：**

```
第一道门（顶层早退，CaretAnimator.refreshTarget）
    cameraTrail.isAt(vp.line, vp.column, document.getModificationStamp())

第二道门（逐光标，同一方法的多光标循环里）
    trail.isAt(cvp.line, cvp.column, stamp)   → needsUpdate
```

inlay 出现时 `(line, column, stamp)` **三个分量一个都不变** → 两道门都判定
「这个光标没动」→ `cp = visualPositionToXY(cvp)` 根本不执行 → 覆盖层继续用
inlay 出现**之前**的 `cp.y` 绘制 → 屏幕上比真实位置高 1 个行高。

用户描述的「光标跳到 19 行」（19 是 inlay 那一行）与「该在 21 行却画在 20 行」
都是这 1 格偏移的不同表现。

**与 BUG 里两个「待查方向」的对照**：

| 待查方向 | 结论 |
|---|---|
| 坐标换算哪一层不感知虚拟行 | **`visualPositionToXY` 本身是感知的，没错**；错的是我们**没去调用它** |
| 位移判定是否也用同一份坐标 | **是**，而且这里才是真正的坑（见下） |

### 修法

1. **订阅 `InlayModel` 的事件**，inlay 增 / 删 / 改时置一个「几何已脏」标志
   （`CaretAnimator.onInlayGeometryChanged()` → `inlayGeometryDirty = true` + `wakeUp()`）。
   **不按 placement 过滤**：ABOVE_LINE / BELOW_LINE 撑高行（y 变），
   INLINE 推后文本（x 变），AFTER_LINE_END 两者都占 —— 四类都影响绘制几何。
   *（在这里加过滤器，等于给另外三类各留一个同样的坑。）*
2. **两道门都要放行**：顶层早退条件加 `&& !inlayChanged`，
   逐光标的 `needsUpdate` 加 `|| inlayChanged`。
3. 标志的清零点与 `forceResync = false` 放在同一处 —— 只有确认进入慢路径才清，
   被 IME 判据提前 `return false` 时保留给下一帧。
4. dispose 时 `Disposer.dispose(inlayListenerHandle)`：平台**没有** `removeListener`，
   只能靠 `addListener(listener, parentDisposable)` 的第二参数回收，
   而本类的 `dispose()` 是自己调的、不经过 `Disposer` 管理 —— 不显式释放就会一直挂在编辑器的 InlayModel 上。

**第一版修法错在哪（值得记下来，因为下次还会想这么干）**：
第一版只置 `forceResync = true`，以为它能让所有光标重算。
实测探针 I-1 位移 **0.0px**（期望 19px）—— 因为 `refreshTarget()` 中段有一句
`forceResync = false;`（「已进入慢路径，标志使命完成」），它在**逐光标 `needsUpdate` 判定之前**执行。
所以 `forceResync` 实际只能绕过**顶层**早退，管不到第二道门。
这才有了独立的 `inlayGeometryDirty` 字段。

### 证据

**离线探针** `tools/settings-probe/VInlay.java`（已加入 `build-v-probes.ps1` 的 `$mains`）：

```
=== V-INLAY：block inlay 撑高一行后拖尾必须重新落位 ===
  PASS  I-0  生产代码必须订阅 InlayModel 的变化
  PASS  I-3  没有 inlay 事件时快速路径仍然生效（修复不得退化成每帧重算）
  PASS  I-1  inlay 撑高 1 行后，垂直中心必须下移 1 个行高  [centerY 129.5 → 148.5，位移 19.0px]
  PASS  I-1b 该帧必须报告「位置变了」
  PASS  I-2  inlay 移除后必须回到原位置（双向）
```

它构造的正是「行号 / 列号 / 文档戳恒定、只有 `visualPositionToXY().y` 变化」的场景，
断言读的是 `SpringTrail.centerY`（绘制真正用的目标几何，不是内部标志）。

**变异测试（两次，均全量重编译）**：

| 变异 | 结果 |
|---|---|
| 去掉 `addListener` 注册（`if (false)`） | **I-0 + I-1 + I-1b 三红**，位移 0.0px |
| 把 `needsUpdate` 里的 `inlayChanged` 换成 `false` | **I-1 + I-1b 双红**，位移 0.0px |

第二条尤其重要：它证明「只绕过顶层早退」是不够的，与上面第一版修法的失败记录对得上。

**门禁**：`build-v-probes.ps1` 全量跑完，失败名单仍是改动前那 6 个
（`VTools` / `VHints` / `VCustom` / `VReset2` / `VSepBound` / `VSleep`），
逐字一致，没有新增红，也没有顺手去修它们。

> **事后注（2026-10-28 晚）**：那 6 个已在同日后续一轮里全部修绿 —— 只改探针、生产代码零改动，
> 门禁现为 `V-PROBES RESULT: ALL PASS`。本段保留当时的逐字输出作审计痕迹，
> 不要据此认为它们现在还是红的；逐条根因见 `docs/NEXT-STEPS.md` §7.1.1。

**D0**：`core/NeovideConfig.java` 零 diff。

### 仍未验证的部分（不要当成已修）

用户描述的**现象二**（「补上 `{}` 后在括号中间回车，光标该到 21，结果跳到 20」）
无法在离线探针里构造（它需要真实编辑器重排）。本修复在机制上覆盖了它 ——
按 Enter 会改文档、进而触发 inlay 的重新生成/更新事件，被同一个
`inlayGeometryDirty` 兜住 —— **但这只是推理，没有真机数据。**

真机复验步骤（用户执行）：

1. 装本版本；
2. 打开任意 Java 文件，写一个方法，等到 IDEA 在方法上方显示出灰色的
   `@Contract(pure = true)` —— 此时光标**不应**跳走；
3. 在方法体内按 Enter 换行 —— 光标应落在新行，而不是上一行；
4. 若现象二仍复现，说明另有根因（优先怀疑「动画过渡中被打断」与「布局滞后一帧」），
   届时不要沿用本条的结论。

---

## BUG-007 刚打开 IDEA 后「第一次使用光标」必定卡一下（**已定位：平台侧一次性初始化；本轮修掉一个伴生缺陷**）

**用户报（2026-10-28）**：

> 光标刚打开 IDEA 时候，第一次使用光标一定会卡一下，**卡到没有**，然后正常用才好。
> 我测试过两分钟没动他，我开头以为是 EDT 抢占资源，结果不是，就是 bug。

**现象拆解（按用户原话）**：

1. 触发时机：**每次启动 IDEA 后的第一次光标使用**（移动 / 输入都算）；
2. 表现：那一下「卡到没有」—— 光标**消失**了一瞬，不是变慢、不是拖尾变长；
3. 之后：恢复正常，同一次会话内不再复现；
4. **排除了「冷启动 / CPU 忙」这一解释**：用户特意启动后干等两分钟再动，照样复现。

**为什么这个排除很重要**：它把「首帧 EDT 负载高」这类解释基本否掉了 ——
等了 120 秒，AWT 队列早该空了。所以更像是**某处状态在「第一次」才被建立**，
或者是「第一次」走了一条与后续不同的代码路径。

**用户补充的关键观察（2026-10-28，决定了整个排查方向）**：

> 开头不碰键盘 → 用鼠标引导光标 17→20 **不会卡**；只要第一次碰键盘就卡一下。

即触发条件是「**本次会话第一次修改文档**」，不是「第一次使用光标」。鼠标点击只改光标
位置、不改文档；键盘第一次改文档。这条把「首帧 EDT 负载高」「首次建立几何」等解释全部排掉。

### 取证方式（为此新增的诊断，默认关）

`diagLogging` 打开后写 `<config>/neovide-cursor-diag.log`，新增 6 类行：
`[boot]`（构造环境）、`[evt]`（光标事件 + `stampChanged`，首次改文档打 `FIRST-EDIT`）、
`[tick]`（限量帧：`rawDt`/`dt`/`recovery`/`showing`/`quads`/`dispatchLag`/`cost`/`note`）、
`[geom]`（`vpX` vs `offsetX` 的 `dx`）、`[paint]`（quads/cost/earlyReturn/异常）、
`[clear]`（清空原因）。**只写独立文件，不走 `idea.log`**（144fps 会刷爆），每 animator 上限 5000 行。

### 证据（2026-10-28 19:38 一次真实采集）

操作序列与现象完全对应 —— 鼠标四次点击零停顿，第一次按键 427ms 停顿，第二次按键零停顿：

```
[evt] 19:38:45.536 vp=272:49 stamp=0     stampChanged=false
[evt] 19:38:46.218 vp=271:26 stamp=0     stampChanged=false
[evt] 19:38:47.476 vp=271:27 stamp=1432  stampChanged=true FIRST-EDIT   ← 第一次按键
[tick] 19:38:47.489 n=1875 rawDt=427.460 dt=8.000 recovery=true
       showing=true quads=1 delayMs=6 dispatchLag=420.663 cost=240us note=ok
[evt] 19:38:48.700 vp=271:28 stamp=1550  stampChanged=true              ← 第二次按键，无停顿
```

**那 427ms 不是本插件花的**，三条独立证据：

| 证据 | 数值 | 含义 |
|---|---|---|
| `[tick]` 的 `dispatchLag` / `cost` | 420.663ms / 240µs | 这一帧在 EDT 上**等了** 420ms 才开始；我们自己的活只有 240 微秒 |
| `[paint]` | 该窗口内**没有**任何 ≥4ms 的绘制（门限就是 4ms） | 不是我们画得慢 |
| `idea.log`（平台自己的告警） | `EditorCaretMoveProcessor - Caret animation experienced a significant slowdown … (last frame took 31 ms)` | 平台自己的光标动画同时报降速 |
| `idea.log`（同一时刻在做什么） | `CpuDetector - [Inline Completion] CPU detector` → `checkCancelled is invoked while write-action is pending` → `Full line native server is started` + 6× `DynamicBundle - Cleanup bundle cache` | 补全子系统在首次编辑时做惰性初始化 |

**根因**：平台/补全插件在「本次会话第一次编辑」时做一次性惰性初始化（Full Line 原生服务器
启动 + bundle 缓存清理等），把 EDT 占住约 420ms。本插件不参与这 420ms；它之所以看起来
像"光标坏了"，是因为**原生光标被隐藏、覆盖层是唯一可见光标**，它跟着 EDT 一起冻住 ——
于是你看到的是「光标把字符的一半吸附过去」或「直接消失到下一个位置」。

**排除掉的旧推测**（都已用日志否掉，不要重捡）：
`lastNanos=0` 的恢复分支确实会在每个 animator 的 `n=1` 命中（`rawDt=5863359ms`，见日志），
但那时 `showing=false`、什么都不画，与现象无关；`lastDispatchedDueNanos` 的首次投递正常
（`dispatchLag` 稳定在个位数毫秒）；`applyConfig()` 在启动期没有被调用。

### 本轮修掉的伴生缺陷（**这是我们自己的问题**）

`tick()` 的「编辑器不可见」早退分支原来**直接 return，不改 `currentDelayNs`**，而
`alwaysHighFps` 默认开启时那个值就是 6.94ms —— 于是**每个不可见的编辑器也继续按 144fps
被投递**。实测：5 个从未显示的编辑器各自刷了约 4880 次 `not-showing`，
等于 **720 次 EDT 任务/秒的纯空转**（每次还要 3 个平台调用 + 一次 clear）。

**修法**（`CaretAnimator`）：
1. 不可见时把 `currentDelayNs` 置为 `hiddenTierDelayNs(false)`（空闲档 120ms）；
2. 记 `wasHidden`；切回可见的第一帧 `forceResync = true` + `RenderScheduler.wakeUp(this)`，
   否则最多会有 120ms 的陈旧光标（那正是本类注释里要避免的"光标莫名悬浮"）；
3. `hiddenTierDelayNs(boolean)` 抽成**包内静态纯函数**，探针直接调用（不复刻逻辑）。
   诊断日志的 `[clear]` 同时改成**同原因只记一次**（原来每帧一条，24444 行把正片埋了）。

**这一条修好了吗**：修好了，但它**不会**消除那 420ms —— 那在平台侧。它消除的是
每秒 720 次无意义 EDT 任务，让任何停顿的恢复期（实测其后还有 32ms / 16ms 的抖动帧）更干净。

### 守门探针与变异测试

`tools/settings-probe/VBoot.java`（已登记进 `build-v-probes.ps1` 的 `$mains`）：
B-1 不可见必须返回正数；B-2 可见必须返回 -1；B-3 ≥ 活跃档 10 倍；B-4 ≠ 活跃档；
B-5 ≥ 120ms。断言调用 `CaretAnimatorProbe.hiddenTierDelayNs(...)`（生产同一个函数）。

变异测试（两次都是**全量重编译到空目录**，不用增量字节码）：

| 变异 | 结果 |
|---|---|
| `hiddenTierDelayNs` 改成恒返回 `-1`（等价于撤回修复） | **B-1 / B-3 / B-5 三红**，`V-BOOT 结果：PASS 2，FAIL 3`，`EXIT=1` |
| 恢复原实现 | `V-BOOT 结果：PASS 5，FAIL 0`，`EXIT=0` |

门禁（`build-v-probes.ps1 -Repo …` 全量）：`FAILED（6 个探针未通过）`，
名单仍是 `VTools / VHints / VCustom / VReset2 / VSepBound / VSleep`，逐字不变
（`VRegr2` 2 红仍按 informational 不计入）。`VBoot` 5/5 绿。
D0：`core/NeovideConfig.java` 零 diff。

> **事后注（2026-10-28 晚）**：上面这份名单已在同日后续一轮修绿，门禁现为 `ALL PASS`；
> 本段是 BUG-007 那一轮的门禁快照，保留原文不改。见 `docs/NEXT-STEPS.md` §7.1.1。

### 真机复验（0.9.100，2026-10-28 19:56 一次采集）

| 检查项 | 0.9.99 | 0.9.100 | 结论 |
|---|---|---|---|
| `[clear]` 总条数 | 24444 | **6** | 去重生效（每 animator 一次状态转移） |
| 隐藏编辑器帧间隔（4322 样本，`note=not-showing`） | ~6.94ms | **中位 120.078ms** | 降频生效 |
| 第一次按键那帧 `dispatchLag` | 420.663ms | **238.485ms** | 仍存在（Full Line 未关，符合预期） |
| 其后抖动帧 | 32.8 / 16.7 / 16.0 / 12.2ms | 6.9 / 2.3 / 5.2 / 33.7ms | 恢复期更干净 |

### 复验中发现的新回归（0.9.101 已修）

降频带来一个副作用：隐藏期间 `clearCorners()` 把 `quadCount` 清零，而**切回可见时平台
会立刻重绘一次** —— 那一帧什么都没画。日志实测：

```
19:57:15.026 [paint] id=887907989 quads=0 earlyReturn=true   ← 切到该标签页，画出空面板
19:57:15.142 [paint] id=887907989 quads=1 cost=4641us        ← 116ms 后才画出光标
```

原实现是在 `tick()` 里发现 `wasHidden` 才唤醒，本身就滞后最多一个空闲周期（120ms）
—— 观感是「切标签页后光标迟到」。**修法**：监听 `HierarchyEvent.SHOWING_CHANGED`，
显示状态一变就 `forceResync = true` + `RenderScheduler.wakeUp(this)`；`dispose()` 里摘掉监听。
这条**探针覆盖不到**（造不出真实显示/隐藏的组件层级），只能靠真机日志复验。

**0.9.101 复验（2026-10-28 20:05 采集）**：全程**没有再出现任何 `quads=0` 的空绘制**
（0.9.100 每个 animator 至少一次），`[clear]` 仍是 6 条，隐藏编辑器 `delayMs=120`。
共观察到 6 次「隐藏 → 可见」切换。缺口：`[paint]` 只在 ≥4ms 或空绘制时才记，
所以"切换→首帧"的毫秒差没有直接读数 —— 若将来要把这条也变成硬指标，需在切换后
N 帧内无条件记录绘制耗时。

**用户真机确认（2026-10-28）**：「切标签页本来就是不迟到，现在优化的更好了。」
即：0.9.100 引入的那个 116ms 迟到在 0.9.101 已消失，且隐藏降频没有带来新的观感问题。

**另一个结论（值得记下来）**：首键停顿三轮实测 420.663ms（0.9.99）→ 238.485ms（0.9.100）
→ 401.453ms（0.9.101），波动区间 238~420ms，**与本插件的改动无关**（两轮降频改动前后
都在 400ms 上下）。这也反证了它不是我们造成的。

### 仍未解决 / 下一步

**已排除：本地 Full Line 补全。** 用户关掉它（`fullLine`）后实测首键 `dispatchLag`
仍是 **385.600ms**（0.9.101 那一轮），与本轮其它数据一致 —— 它不是（唯一）元凶。
三轮数字：420.663（补全开）→ 238.485（补开）→ 401.453（补开）→ 385.600（补全关）。
波动 238~420ms，说明这是几件事叠加或平台侧别处，而不是单一插件。

**正在做：EDT 看门狗**（`ui/EdtWatchdog.java`，0.9.102）。后台线程每 50ms 往 EDT 投一个
空任务；若投递到执行超过 200ms，就**在阻塞期间**立刻抓 `AWT-EventQueue-0` 的调用栈，
写成一行：

```
[edt] <时间戳> waited=XXXms stackCost=Yms AWT-EventQueue-0 <- 类.方法 <- 类.方法 <- …
```

判读方法（设计如此，见类注释）：

| `waited` | `stackCost` | 含义 |
|---|---|---|
| ~390ms | ≈0.2ms | EDT 上**某个方法**在跑 —— 栈里第一层非 JDK 的方法就是真凶 |
| ~390ms | ≈390ms | JVM 全局暂停（GC / 安全点），栈不可信 |

离线自检已确认它真能抓到：人为让 EDT 睡 700ms，抓到
`AWT-EventQueue-0 <- Thread.sleep <- TmpWatchdogSmoke.lambda$main$0 <- InvocationEvent.dispatch`。

**为什么第一版抓不到**（值得记）：最初是「等 ping 跑完再判断耗时」，结果 waited=851ms
但栈里是 `EventQueue.getNextEvent` —— 那时 EDT 已经空闲，真凶早走了。必须在超时**那一刻**
抓，而不是事后抓。

### 根因定案（0.9.102 首次采到栈，2026-10-28 20:48）

```
[edt] 20:48:03.389 waited=202.711ms stackCost=2.254ms AWT-EventQueue-0
  <- com.intellij.codeInsight.template.LiveTemplateContextsSnapshot.getLiveTemplateContext
  <- com.intellij.codeInsight.template.impl.TemplateContext.isEnabledNoSync   (递归 5 层)
  <- TemplateContext.makeInheritanceExplicit
  <- TemplateContext.readTemplateContext
  <- TemplateSettings.readTemplateFromElement
  <- TemplateSettings.parseTemplateGroup
  <- TemplateSettings$1.readScheme            ← 解析整份 Live Templates 配置
  <- …(共 179 层)
```

紧跟其后就是首次按键那一帧：`rawDt=427.473ms` / `dispatchLag≈420ms`。
`stackCost` 只有 2.254ms（远小于 `waited`），**排除 GC**：确实是某个方法在 EDT 上跑。

**触发条件 = 本次会话第一次编辑**，与时间无关（所以"等两分钟"没用）。
代价与模板数量成正比，本机实测：

| 来源 | 条数 |
|---|---|
| `Zen CSS.xml` | **641** |
| `Zen HTML.xml` | **220** |
| 其余（Groovy 31 / JS 26 / Shell 26 / Kotlin 25 / SQL 13 / …） | ~181 |
| 合计 | **1062** |

**解除办法（用户侧配置，非本插件代码）**：设置 → 编辑器 → Live Templates，
删掉或取消勾选 `Zen CSS` / `Zen HTML` 两个组（861 条，占 81%）。
**但实测无效** —— 见下一节的反证。**本插件无法从代码侧消除这 420ms。**

#### 这 861 条模板从哪来（2026-10-28 核实，纠正一次错误猜测）

早先我口头猜过「是导入 VSCode 设置时带进来的」——**这个猜测是错的**，三条证据：

1. `Zen CSS.xml` 里每条模板都带 `deactivated="true"`（插件已缺席时的典型形态）；
2. 文件时间 **6 月 4 日 17:22**，与 `Java.xml` / `Kotlin.xml` / `SQL.xml` / `Groovy.xml`
   同一批 —— 那是**首次启动**落盘的，不是 10-28 那次导入；
3. `options/` 下**没有任何 VSCode 导入痕迹**。

结论：它们是 **IDEA 自带的 Web（HTML / JavaScript）插件模板包**，首次启动就写进了
`<config>/templates/`；用户 10-23 禁用了 `JavaScript` / `HtmlTools` 等 Web 插件后，
这些模板变成「失效但保留」（`deactivated`），IDEA 仍会在下次首次编辑时解析它们。
已移到 `D:\deepseekwork\_config-backup\zen-templates-20261028-205241\`（只挪未删）。

### 反证：删掉 81% 的模板**没有**改善（2026-10-28 20:57 复验）

把 `Zen CSS.xml`（641 条）+ `Zen HTML.xml`（220 条）移出 `templates/`（模板总数
1062 → 201）后重启再测：

| 版本 | 首键 `dispatchLag` |
|---|---|
| 0.9.102，Zen 模板在（1062 条，20:48） | ≈420ms（`rawDt=427.473ms`） |
| 0.9.102，Zen 模板移走（201 条，20:57） | **413.770ms**（同一量级，无改善） |

所以**模板数量不是主因**。同一轮抓到两条 `[edt]`（都 ~205ms，`stackCost` 2~4.5ms，
仍然排除 GC）：

```
[edt] <- Properties.load <- IntelliJResourceBundle.<init>          ← 本地化资源包加载
[edt] <- TemplateContext.isEnabledNoSync (递归) …                  ← 模板上下文解析
```

**修正后的根因**：首次编辑时平台的一整套惰性初始化 —— **本地化（zh_CN）资源包加载**
+ Live Template 上下文解析 —— 合计约 410ms。它与"模板有多少条"无关，而与"这些代码
第一次被走到"有关。用户侧可做的只剩"换英文 UI 试试"，但代价是界面变英文，不推荐；
本插件对此**无能为力**。

> 教训：先别急着让用户删配置。这次"删 81% 模板"的假设被实测直接否掉，
> 如果当时没复测就写进结论，BUG-LOG 就会多一条假因果 —— 与文首那份"错误结论"是同一种病。

**订正**：本条目早先写的「抓 `[frame]`/`[trail]` 的前 20 行看 `rawDt`」是错的 ——
`rawDt` 从来不在 diag 文件里（只在 `idea.log` 的 debug 行），且 `[frame] #N` 是慢路径
计数不是渲染帧号、默认采样永远跳过首帧。正确做法就是本轮新增的 `[tick]`/`[evt]` 行。

---

## 已推翻的错误结论（重要 —— 避免重犯）

### 错误结论 1：「0.9.61 之前光标高度被锁在 30%」

**我怎么想的**：对比 0.9.61 前后，认为 `cursorHeightRatio` 之前没有初始值（= 0f），
被 clamp 成 0.3，所以只画三成高；修好后恢复 100%，绘制面积涨 4.8 倍，于是变卡。

**实际是什么**：`git show 8fcd895` 显示字段声明一直是
`private float cursorHeightRatio = 1.0f;` —— **0.9.61 之前就是 1.0**。

**为什么错**：我没有 `git show` 就去猜字段初始值，还拿这个假前提做了一个
「30% vs 100% 高度」的微基准（得到 4.8 倍差异），把假数字当成证据汇报。

**用户怎么发现的**：「三成高我看不出来吗」—— 一句常识反问就击穿了。

**教训**：**涉及"某版本之前是什么样"的陈述，必须 `git show <rev>:<file>` 核对**。
凭印象说历史行为等于编造。

### 错误结论 2：「改成描边渲染会更快」

**我怎么想的**：N 层 `fill` 是把整块面光栅化 N 遍，改成 N 道同心 `stroke`
只需光栅化边，面积近乎恒定 → 应该更快。

**实际是什么**：用生产代码实测（3000 帧均值）：
```
1 光标 8 层：fill 216µs → stroke 230µs（0.94x）
8 光标 8 层：fill 1603µs → stroke 1701µs（0.94x）
```
**反而慢 6%**，各档位一致。

**为什么错**：Java2D 的 `draw` 要先由 `BasicStroke` 把路径"加宽"成新多边形
再填充 —— 等于「构造几何 + 填充」两步，而 `fill` 只有一步。
细长四边形（2×41）的 stroke 几何构造尤其不划算。

**处置**：代码与配置字段一并删除，不留死路。

### 错误结论 3：「多光标 8 个掉到 58fps，是辉光太贵」

见 BUG-003。真实是 117fps，长尾（p99=44ms）才是问题。

### 错误结论 4：自适应降级辉光层数（0.9.63 加入，0.9.64 删除）

**我怎么想的**：8 光标 × 8 层 = 1603µs（20% 预算），按光标数摊薄到 3 层
（727µs，9%），1~3 光标不受影响 —— 看起来是个精确对着瓶颈的方案。

**为什么被否决（用户提出，理由成立）**：
1. 与已有的「多光标时关闭辉光（只保留拉伸）」开关**语义重复**。
2. 更糟：它写在那个开关的 `else` 分支里 —— **用户一勾开关，自适应永远不生效**，
   等于给"需要救帧的人"提供的路直接失效。
3. 自动降级是**隐式行为**，用户不知道辉光为什么变淡。
4. 真要救帧，「关掉」的收益（0µs）远大于「降 3 层」（727µs）。

**教训**：
- 加一个自动机制之前，先查**已有开关**是否已经覆盖同一意图。
- 新机制写在已有开关的 else 分支里，等于两者互斥且没人知道。
- 「灵活」不等于「好」：显式开关可预期，隐式自适应不可预期。

---

## 技术债（未修，已记录）

1. **预览窗把「跨行」的拉伸套在「单行」标题下** —— 见
   `docs/design/PREVIEW-STRETCH-MISMATCH.md`。预览失真，实际行为是对的。
2. **`trailSize = 1.0` 时跨行前缘时长恒为 0** —— 强效果，UI 里没有任何解释。
3. **`stretchRatioK` 与 `typingSmoothness` 互相抵消** —— k 从 4.5 拉到 8.0
   的净效果很小（最短角 0.38 → 0.26，被 round2 与 smooth 吃掉大半），面板无提示。
4. **`physics` 侧长尾未定位** —— 8 光标 p99 = 44ms，其中辉光只占约 1.6ms，
   剩余约 40ms 在 IntelliJ 自身编辑器重绘（JFR 显示 EDT 采样里我们只占 1.6%）。

---

## 非缺陷观察（不算 bug，别当 bug 修）

> 2026-10-28 用户报。**本会话按要求未做任何验证**，只记录现象与待查方向。
> 这些不是「坏了」，是「行为与预期/与别处不一致」；动手前先确认到底是哪一种。

## BUG-008 PyCharm 2025.2（平台 252）覆盖层光标冻结在第一行第一字符（**已修复，0.9.104**）

**用户报**：在 PyCharm 2025.2（PY-252.23892.515）里覆盖层光标停在第一行第一字符，
移动与打字都不动；换 IDEA 2026.1（build 261）就正常。

**我怎么想的（错误的推理方向）**：第一反应会怀疑「事件没收到 / trail 没初始化 /
坐标换算坏了」—— 因为这些都是本项目历史上光标不动的常见原因。
但本地 IDEA 复现不了、日志里连一条异常都没有，说明问题不在业务逻辑，
而在**平台对访问上下文的限制**。

**实际是什么**：栈是
```
CaretAnimator.refreshTarget → CaretImpl.getOffset
  → EditorThreading.assertInteractionAllowed
  → "Read access is allowed from inside read-action only"
```
252 要求「读 Editor / Caret / Document 必须在 read action 内」。
我们的 tick 由 `invokeLater` 投递到 EDT —— **在 EDT 上不等于持有读锁**。
异常被 `refreshTarget` 自己的 `catch (Throwable)` 吞掉并返回 `false`，
于是拖尾永远停在初始位置，日志里只有一条被限流的 warn（默认还看不到）。
261 放宽了该判定，所以同一份 jar 不触发 —— **平台版本差异**。

**修法**：新增 `readEditor(ThrowableComputable) = ReadAction.compute`，
把 `refreshTarget`（拆成 6 行包装 + 原实现体**一行未动**）、构造函数 4 处读取、
`traceCaretEvent`、`caretAdded/Removed`、`recordFrameStats` 全部包进 read action。
用 `compute` 而不是 `runReadAction(Runnable)`：后者拿不到 `changed`，
只能靠字段绕，会破坏 P0-5 的调用次数契约。

**守门**：`VReadAction`（12 条断言）。**关键设计**：它不检查字符串，
而是用「只允许 read action 内访问」的 Editor/Caret/Document 桩复现 252 判据 ——
桩在读取时若不在 read action 内就抛与平台同文的 `AssertionError`。
三组变异分别红 8 / 1 / 1 条（去 refreshTarget 包装 / 去构造期包装 / 去 caretAdded 包装），
证明桩抓得住。另有一条 R-10 常驻自证：绕过包装直接调实现体时桩必须判红，
否则整份探针就是空转。

**教训**：
1. **「在 EDT 上」不等于「持有读锁」**。跨大版本做兼容时，先查平台在目标版本上
   对「读访问上下文」的要求，而不是先怀疑自己的业务逻辑。
2. **`catch (Throwable)` 会把平台断言吞成「什么都没发生」**。这类吞异常只在
   诊断日志开启时留痕，默认配置下用户看到的就是「光标不动、日志干净」——
   排查时必须先把 `diagLogging` 打开。
3. 新增平台调用时，要问一句「这个调用在旧版本上有没有前置条件」。
   本次的 `getLineHeight` / `getVisualPosition` / `getOffset` 在 261 上无条件可用，
   在 252 上却都要求读锁。

---

### N-2 开辉光 + 渐变时形状看着像梯形（**视觉错位，非代码缺陷** —— 2026-10-28 用户定性）

**用户观察**：斜度设成 0 时，开辉光 + 渐变，光标形状看着像一个梯形（下宽上窄）。
**关掉辉光就没有这个问题。** 曾怀疑是「渐变 + 辉光半透明」共同造成的。

**代码层的解释（已查证）**：
- 斜度 0 只控制「四角时长如何分配」，**不保证动画中间帧的形状是矩形** ——
  四个角各自挂独立弹簧、时长不同，中间帧本来就是任意四边形（梯形/斜形都可能）；
- 辉光不是一层，而是**沿四边形逐边外法线外扩 8 层半透明**（`drawQuadGlow`），
  外扩量 = `shadowBlurFactor × 较长边`（基础预设 0.20 → 约 3.8px）；
- 主体只有 2~3px 宽，形变肉眼不明显；辉光把同一个形状**外扩 8 层描粗**，
  形变被放大到 4px 以外，于是「看出梯形」。

**定性（用户）**：这是**渐变/辉光共同造成的视觉错位，不是代码有问题**。不做修改。

**验证方法（留档）**：把辉光层数调到 1、锐度调到最锐，梯形会大幅消失 ——
如果调完仍在，才需要怀疑渐变计算。

---

### N-3 输入动画「过快」、预览开辉光「突兀」（**疑似参数问题，只记录** —— 2026-10-28）

**用户观察**：即使打字很慢，正常的输入动画也偏快；动画预览窗开辉光时更突兀。
用户原话：「其实打字慢了也有正常输入动画也过快了，感觉是参数问题」。

**已知的代码事实**：
- 预览每帧调 `panel.setGlowStrength(min(1, maxOffset / 8))` ——
  **辉光亮度按拖尾当前偏移量动态计算**，拖尾回弹时亮度跟着收；
  预览动作稀疏（170ms 一次），这种脉动比真实打字显眼；
- 三个内置预设的动画时长：基础 0.10s / 缎带 0.28s / 纯平滑输入 0.10s；
  基础预设的斜度是 **0**（0.9.80 引入斜度时是 82）。

**状态：只记录，不修。** 若后续要调，先确认是「改预设数值」还是「改强度映射」——
两者改的是完全不同的地方（`ANIM_PRESETS` vs `AnimationPreview:737`）。

---

### N-1 用 Tab 缩进出现缎带，用空格缩进是规整矩形（**产品特性，用户确认保留**）

**用户描述**：按 **Tab** 缩进时，**本来没有开启行内斜缎带**，但实际有缎带效果；
换成**空格**缩进则是规整的拉伸矩形。用户觉得「其实比较好看」，
并怀疑是**两处判定方式不一样**。

**定性**：不是缺陷（用户甚至更喜欢），但**不一致本身值得记** ——
同一次「缩进」操作，Tab 与空格得到两种不同几何。

**待验证**（本会话按要求未验证）：
- Tab 宽度与单空格宽度差异，是否让位移跨过/未跨过某个判据阈值；
- 是否与 `TrailCorner` 的跨行分支判据（`charsMoved <= typingMaxChars`）
  或斜度派生 `slopeTicks` 的输入有关；
- 与上面「技术债第 2 条」（`trailSize = 1.0` 时跨行前缘时长恒为 0）**可能同源**，一起看。

**若确认是阈值所致**：「保留哪种行为」是产品决策（纪律四-L），
**不要自行统一成一种** —— 用户明确说缎带那版更好看。

---

## BUG-009 自定义预设点「恢复为默认值」跳回父预设、并抹掉副本条目（**已修复，2026-10-29**）

**用户报**：「对自定义预设点恢复默认值，数值跳到了父预设，而不是派生时的状态。
这个问题之前修好了又出来了。」

**实际是什么（根因）**：

`ui/settings/NeovideCursorConfigurable.java` 的 `restoreAnimPreset()` 读的是全局可变指针
`pending.animPresetBase`。而这个指针**只在「选中内置预设」时被写入** ——
`onAnimPresetPicked()` 的**自定义分支**只做 `restoreSkipping(...)` 和
`pending.animCustomName = 名字`，**从不更新 `animPresetBase`**。

于是选中「基础 副本」后：按钮文案 `refreshRestoreButtonText()` 读这个指针 → 显示
「恢复为「缎带」默认值」；点下去 `restoreAnimPreset()` 读**同一个**指针 → 真的回到缎带。
显示和目标一起错，用户无从察觉。

**为什么二次复发**（查证到的是第一种：**当初的修复被后续 commit 覆盖**）：

1. `b47443b`（2026-10-25）把「副本上 restore 自己的快照 = 空操作」改成
   「回到派生的内置预设 + 清副本名」—— 用户当时认可的那次修复。
2. **`bd932b9`（2026-10-28）覆盖了它**：在同一方法里追加「把副本快照改写成父预设值」，
   并把按钮文案从副本名改成父预设名。前半句直接抹掉用户手调的整套参数（数据丢失），
   后半句让「显示的名字」继续绑在同一个错误指针上。证据：

   ```
   $ git log --oneline -S 'String customName = pending.animCustomName;' -- <该文件>
   bd932b9 feat(settings): 清理占位项 + 恢复默认值对齐副本 + 预设归属总说明
   ```

3. 根上的原因：两次修复都在「猜父是谁」，因为预设条目里**从来没存过**「我派生自谁」——
   `CustomPresets.Entry` 当时只有 `name` + `snapshot` 两个字段。

**修法**：给条目加「父引用」（`b<内置下标>` / `c<名字>`，`settings/CustomPresets.java`），
恢复时沿父链求值（`resolveBase`）；父被删由 `remove()` 把子节点接到祖父；
找不到祖先 → **按钮置灰**，绝不回退到某个内置预设。
颜色预设同步做成完整预设（State 三件套 + 面板三个按钮 + Tools 菜单自定义项）。

**守门**：
- `tools/settings-probe/VPresetReset.java`（6 条 R-0~R-5）：值回到父 / 仍留在副本上 /
  **副本条目一个字节都不许被改写** / 父预设常量不被改动。
- `tools/settings-probe/VPresetChain.java`（11 条 C-1~C-11）：派生记父 / 自定义派生自定义 /
  恢复目标 / 删除接管祖父 / 改名同步子引用 / 旧两段式兼容 / 无父则无目标 / 自环防护 /
  往返序列化 / 颜色快照往返与坏串。

**怎么防**：改预设相关逻辑前先读本条，再跑门禁看 `VPresetReset` / `VPresetChain` 是否变红。
结构性教训一句话：**「按钮显示的目标」与「恢复动作实际用的目标」必须读同一个函数** ——
本案两次复发都出在这两者之间的空隙里。

---

## BUG-010（A1）调颜色时自定义颜色预设变空、没有「XX 颜色 副本」（**待修复，2026-10-29 用户报**）

**用户报**：「调整颜色时候自定义颜色会为空，就是没有某某颜色 -副本」。

**初步定位（未验证，需新会话确认）**：

颜色侧**没有**动画侧那种「编辑即派生副本」。`onColorPanelChanged()` 只把控件值推进
`pending` 并刷新显示；此时若没站在任何自定义颜色预设上，`matchColorPreset()` 返回 null，
下拉置 `-1`（空白）—— 改动**无处可存**，视觉上就是「自定义颜色变成空的了」。

动画侧的对应机制在 `afterPendingEdited()`：改预设域字段 → 偏离内置常量 → 自动派生「X 副本」。

**需要产品拍板（纪律四-L）**：颜色侧要不要也自动派生？
- 要：必须加「本会话只派生一次」之类的保护 —— 取色器拖动会连发事件，
  不加保护一次拖动能生成几十个副本。
- 不要：补一句注释写明为什么两边不对称，否则下一个人会当成漏改。

**与 BUG-009 无关**：那个是「恢复的目标指错」，这个是「改动没有落脚点」。

---

## BUG-011 彩虹模式下辉光从粉雾变成淡紫（**已修复，0.9.105，2026-10-29 用户报**）

**用户报**：「彩虹模式下这俩不对，实际是彩虹的颜色光标，辉光是淡紫色的」。

### 现象与四个颜色的真实来源

彩虹模式（颜色预设「彩虹（动态）」）下：

| 变量 | 来源 | 取值 |
|---|---|---|
| 光标本体 | `CaretTrailPanel.buildRgbWashPaint` 色相循环 | 随时间旋转，S=0.42 V=0.95 |
| 辉光 | `CaretTrailPanel.refreshStyles()` → `parse(config.shadowColor, 1.0f)` | **固定色** |
| 拖尾色 | 只借 `fillColor.getAlpha()` 当不透明度，色相不参与 | 死数据 |

所以「光标在转、辉光钉死一个颜色」是这条链路的必然结果；
问题只在于**钉死的是哪个颜色**。

### 根因

`2d15824`（0.9.88）修 `matchColorPreset` 判据时，顺手给彩虹换了一对独有 HEX：

```diff
-  new ColorPreset("彩虹（动态）", "#FFC0CB", "#FFC0CB", true),
+  new ColorPreset("彩虹（动态）", "#4FC3F7", "#B388FF", true),
```

同一次提交的理由写着「`rgbWash=true` 走色相循环时**两个 HEX 都不参与绘制**」。

**这句话只对 `tailColor` 成立。** `shadowColor` 从头到尾都在喂辉光
（`CaretTrailPanel.java:461` → `:921` 算峰值 alpha → `:934-935` 直接 setColor）。
于是 0.9.88 之前彩虹旁的粉色薄雾，变成了紫罗兰 `#B388FF` ——
用户记忆里的「半透明雾色」就是被这次改动抹掉的。

### 判定

**(a) bug —— 代码意图与实现不符。**
不是 (b) 设计：设计意图本身就是「这两个值不影响彩虹绘制」，与实现矛盾。
不是 (c) 从未定义：历史上真实存在过 `#FFC0CB` 的粉雾。

### 修法

`CursorPresets.java` 彩虹预设的 `shadowColor` 改 `#FFFFFF`（中性白雾）。只改一个 HEX 字面量：

- 色相环上任何颜色经过它旁边都不打架；
- 经 `glowOpacity` 压暗后就是「半透明雾色」；
- 匹配唯一性由 `tailColor`（`#4FC3F7`）保证，与「原版粉色」不撞车 ——
  判据要**两个** HEX 都相等。

### 守门探针

`tools/settings-probe/VGlowFog.java`（8 条，已登记进 `build-v-probes.ps1` 的 `$mains`）：

```
1 找到彩虹预设                                  2 雾色 == #FFFFFF
3 预设 shadowColor 原样流到 CaretTrailPanel.glowColor
4 辉光 alpha 恒 255                              5 反证：换成 #123456，辉光必须跟着变
6 彩虹仍能被唯一匹配                             7 HEX 对与其它预设无重复
8 雾色低饱和（S <= 0.10）
```

第 5 条是这条探针的关键：没有它，第 3 条可能只是恒真的死数据（纪律四-G 的「假通过」）。

**变异测试（三组，全部按预期变红，还原后 `CaretTrailPanel` 零 diff）**：

| 变异 | 改坏的地方 | 变红的断言 |
|---|---|---|
| MUT-G1 | 彩虹 shadowColor 改回 `#B388FF` | 2、8 |
| MUT-G2 | 辉光改读 `config.tailColor` | 3、5 |
| MUT-G3 | 辉光 alpha 系数 `1.0f → 0.5f` | 4 |

### 结构性教训

**同一个字段在不同绘制路径下的「参与度」不同，注释必须逐条核对。**
「两个 HEX 都不参与绘制」这种全称判断，写下时必须逐一验证每个 HEX 的每个消费点
（本案 `tailColor` 有 1 个消费点、`shadowColor` 有 2 个）。

`VFieldDomain` 管的是字段在「快照 / 内置预设 / 匹配判据」三处的同源，
但**「字段最终有没有被画到屏幕上」是另一张图** —— 之前没有任何探针覆盖它，
这才是它能活到用户发现的原因。

### 用户侧注意

如果升级前已经把「彩虹（动态）」保存进配置，升级后 `shadowColor` 仍是 `#B388FF`，
`matchColorPreset` 会匹配不上 → 彩虹消失、退回纯色 `#4FC3F7`。
**重新点一次「彩虹（动态）」即可。**

本次修复时用户机器上存的是「暖沙」（`colorPresetBase=4`），不受影响；
按纪律四-K（只有你一个用户，不为不存在的老用户写迁移代码），没有加迁移逻辑。

---

## BUG-012 开辉光后预览里「辉光很大、像只有一层、拖参数没反应」，关掉设置再打开才好（**已修复，2026-10-29 用户报**）

**用户报**（原话，未改写）：

> 「第一次从个人自定义预设（从平滑派生的）开辉光，动画预览窗口光标辉光很大」
> 「滑块参数是 8 层看起来是一层在动画预览里面，而且很大，拉动参数没用，
>   只有关闭设置回来才好」

### 拆成三个可验证的点

| # | 现象 | 实际是什么 |
|---|---|---|
| a | 滑块显示 8 层，预览里像 1 层 | **算术结果**，不是分层失效（见下） |
| b | 辉光「很大」 | 半径公式两边相同，**窗口尺寸**差 10 倍（见下） |
| c | 拖参数没反应、关掉再打开才好 | **复选框根本不写 `pending`**（真 bug，见下） |

### 根因（三处，按严重度）

**① CRITICAL —— 复选框点击时连 `pending` 都不写。**

`bindCheck` 用的是 DSL 的 `ButtonKt.bindSelected(row.checkBox(""), MutableProperty)`。
实测（`VPreviewParity` 8/9 条 + `VReset` R-9）：那个 `MutableProperty.set()`
**只在 `DialogPanel.apply()` 时被调用**，用户点一下复选框时不触发。

于是同一张面板上两种控件两种语义：

```
滑块   拖动 → ChangeListener → 立刻写 pending            （实时）
复选框 勾选 → 只改 Swing 状态，pending 纹丝不动           （要等「应用」）
```

后果正是用户说的：勾「启用辉光」后预览毫无反应（预览读 pending → previewConfig），
要等点应用甚至关掉设置页再打开。`shadowOn` 联动属性同样不跟，三个辉光滑块该置灰时不置灰。

**② HIGH —— 预览的辉光永不熄灭，且亮度曲线和生产是两条。**

- `AnimationPreview.step()` 原来只做 `panel.setGlowStrength(min(1, maxOffset / 8))`；
- 生产是 `(offset - 0.5) / 2`（`GlowIntensity.OFFSET_START/END`）——**两条不同曲线**：
  预览要偏移 8px 才满强度，生产 2.5px 就满了；
- 预览**从不调用** `panel.setGlowFade(...)`，所以 `glowFade` 恒为初值 1 ——
  预览辉光永远满强度，真实编辑器里会熄灭；
- `refreshPreviews()` 的 7 个推送项里也没有 `overlayFadeOut / cursorDisappearDelay /
  cursorFadeOutDuration`，淡出两个滑块在预览里**从来没有作用**。

**③ MEDIUM —— 0.9.105 分标签把预览与辉光控件拆到了不同页。**

预览在标签 3（光标行手感）/ 4（列手感），辉光控件在标签 5（特殊）。
**拖辉光滑块时预览窗根本不在屏幕上**，所以「盯着预览拖滑块」这个动作
在 0.9.105 之后物理上做不到。

### a / b 两个现象是算术，不是 bug

用户机器实测参数：`shadowBlurFactor=0.6`、`glowOpacity=0.3`、`glowLayers` 未落盘（默认 8）、
`cursorHeightRatio=0.8`。预览窗 520×110、字号上限 28pt、行距 1.5：

```
lineH     = 28 × 1.5   = 42
cursorH   = 42 × 0.8   = 33.6
glowWidth = 0.6 × 33.6 = 20.16 px          ← 半径
每层步进  = 20.16 / 8  = 2.52 px
每层 alpha= 1-(1-0.3)^(1/8) = 4.4%  (11/255)
```

**「像一层」**：8 层铺在 20px 上，每层 2.5px、只有 4.4% 不透明度 ——
最外几层的累计不透明度是 4/8/12/16%，在 `#1E1F22` 上读不出分层，
只剩最里那团 ≈30% 的雾。把层数从 8 拖到 12，步进只从 2.52px 变 1.68px ——
**「拉动参数没用」这一半也是算术**。

**「很大」**：真实编辑器光标高约 25.6px、`glowWidth = 0.6×25.6 = 15.4px`，
落在约 900px 高的窗口里占 1.7%；预览窗只有 110px 高，同样 20px 半径占 **18%** ——
差约 10 倍。半径是同一个公式算的，视觉膨胀来自**预览窗太小**，
再叠加 `shadowBlurFactor` 被选在最柔端 0.6（默认 0.2，是 3 倍）。

### 修法

1. `bindCheck` 显式挂 `addActionListener`：点一下当场写 `pending` + `refreshPreviews()`
   （与当年修滑块「显式 addChangeListener」同一招，见文件头）。
   **刻意不调 `refreshPresetDisplay()`** —— 它会带出 `afterPendingEdited()`，
   副作用是立刻派生「XX 副本」，那属于产品可见的变更，按纪律四-L 不在本次范围。
2. 新建 `core/GlowIntensity`，把强度曲线与淡出曲线收成**唯一实现**：
   生产三处（`tickSpring` / `glowStrengthForValue` / `glowFade`）全部改为调用它，
   预览也调用它，并补上 `panel.setGlowFade(fade)`。
3. `refreshPreviews()` 补推淡出三件套。
4. 「辉光锐度」说明补一句：预览窗小，同样的外扩量在预览里会显得更大。

### 证据（改之前红、改之后绿）

| 阶段 | VPreviewParity |
|---|---|
| 只在 property 的 `set()` 里加 `refreshPreviews()`（错误修法） | **FAIL** 8「勾选态 false→true，pending.useShadow=**false**，previewConfig.useShadow=**false**」 |
| 改为显式 `addActionListener` | **PASS** 12 / FAIL 0 |

第一次失败本身就是变异证据：它证明了「DSL 的 `set()` 不响应点击」这一判断，
也证明了探针抓得住——如果只按第一版改法提交，用户侧的 bug 一点没好。

### 契约变更（不是缺陷）

- `VReset` **R-9**：原断言「点击复选框【不】写 pending」→ 反转为「【就】写」。
  那条断言记录的正是本 bug 的成因，方向必须跟着改，断言本身保留。
- `VReset.findShadowCheckbox`：去掉一行「先把 pending 顶成 true」——
  点击当场写 pending 之后，那一行会让「控制态」与「pending」分离，
  初始 value 恰为 true 时永远找不到复选框。
- `VPreviewParity` 新增 7 条（6~12）：复选框双向推送、淡出三件套到达预览、
  强度曲线边界、淡出曲线边界。

### 没解决的部分（明确记录）

- **`AnimationPreview.step()` 里那几行「读 offset → 调 GlowIntensity → 写回面板」
  没有探针覆盖**（它的时间源是 `System.nanoTime()`，没有可注入时钟，
  断言淡出会引入 flaky）。曲线本身已被 11/12 两条钉死，
  `setGlowFade` 的读口有 `glowFadeForProbe()`，但「preview 真的周期性调它」只能靠代码审查。
- 分标签导致的「看不到预览」只是**文案缓解**，没改布局（改布局会动 `VUi.KEYS` 下标契约）。

---

## BUG-013 「我配的自定义单行斜度丢了，之前不是 100」（**不是 bug：预设常量覆盖，已修注释与文档，2026-10-29 用户报**）

**用户报**：

> 「可能丢了我配置的自定义单行斜度字段了，之前不是 100」（截图显示斜度 = 100%）

### 判定：（ii）逻辑重置 —— 且是**设计行为**，不是数据丢失

`slopeTicks` 完全属于**预设域**：

- 字段定义走 6 参工厂 → `inPreset=true`、`runtimeSwitch=false`（`SettingsField:539-541`）；
- 出现在自定义预设快照里（快照字符串第一个键就是 `slopeTicks=`）；
- `restoreSkipping()` 不跳过它（`SettingsField:742-769`）；
- **切内置预设直接写它**：`applyAnimPresetFields` → `s.slopeTicks = p.slopeTicksValue()`；
- 匹配判据也含它：`matchAnimPreset` → `s.slopeTicks == p.slopeTicksValue()`。

三个内置预设的常量（`CursorPresets.ANIM_PRESETS`，最后一参）：

```
基础 = 0        缎带 = 0        纯平滑输入 = 100
```

用户当前配置：`animPresetBase = 2`（= 纯平滑输入）、无 `animCustomName`。
把当前 State 与该预设逐字段比对，**13 个预设字段全中**：

| 字段 | 当前 State | 纯平滑输入 |
|---|---|---|
| slopeTicks | 100 | 100 |
| shadowBlurFactor | 0.6 | 60（=0.60） |
| glowOpacity | 0.3 | 30（=0.30） |
| useShadow | false | false |
| glowLayers | 8（未落盘=默认） | 8 |
| stretchRatioK | 2.0 | 2.0 |

**所以 100 不是「丢失后变成的值」，而是这个预设声明的值。**

### 排除「数据丢失」的证据

- 三份安装备份（`_config-backup/personal-state-*.json`，版本 0.9.105 / 0.9.106 / 0.9.107，
  覆盖 10-29 全天）：`slopeTicks` **全部 = 100**，`dropped` 全为空数组；
- `build/install.ps1:95` 是 `$dropFields = @()` —— 丢弃名单为空，全量还原，
  `slopeTicks` 不在任何幽灵名单里（`grep slopeTicks build/install.ps1` 零命中）；
- `loadState` 的迁移只把遗留值 **82** 归到 0，其余 0–100 原样保留。

最早的一份备份也已经是 100，所以「之前是别的值」在磁盘上找不到任何凭据。

### 真正该修的是「会误导人的注释」（陷阱 12 同型）

两处注释写着「三个内置预设都是 82」，而实际常量早在 0.9.87 就改成了 0/0/100：

- `CursorPresets.slopeTicksValue()` 的 javadoc；
- `applyAnimPresetFields` 上方的说明。

照着旧注释排查，只会往「数据丢失」的方向找 —— 这正是本次浪费时间的直接原因。
两处已改正，并写明「切纯平滑输入 → 斜度变 100」是设计。

### 现有的守门（不需要新增探针）

`VSlopePreset` 第 5/6 条早已把这件事锁成契约：
「只有一个内置预设带斜度（纯平滑输入）」+「切预设真的写 slopeTicks（基础→0、纯平滑→100）」。
换句话说，**用户报的这个现象在门禁里本来就是绿的一条断言** —— 它是设计，不是回归。

---

## BUG-014 切到「个人自定义」预设后辉光是开的（**待核实，低优先级，2026-10-30 用户报**）

**用户报**：「我调到个人自定义的那个预设 — 没有辉光的，现在是辉光开启的，还原脚本有问题」

**用户判断**：`build/install.ps1` 的配置还原把辉光状态弄丢了。

**实测证据（2026-10-30 19:45，0.9.107 安装后）**：

1. 两个自定义预设的快照里，**`useShadow=1`（开辉光）**：
   - `个人自定义`：`useShadow=1`，`glowOpacity=0.3`，`glowLayers=12`
   - `基础调试1`：`useShadow=1`，`glowOpacity=0.55`，`glowLayers=12`

   原文（`NeovideCursorSettings.xml` 的 `customAnimPresets`）：
   ```
   个人自定义␟slopeTicks=100;…;useShadow=1;glowOpacity=0.3;glowLayers=12;…
   基础调试1␟b0␝slopeTicks=0;…;useShadow=1;glowOpacity=0.55;glowLayers=12;…
   ```

2. 安装前的备份 `_config-backup/personal-state-20261030-194430.json` 的键里**没有** `useShadow`：
   ```
   stateVersion targetFps tailColor shadowColor glowLayers shadowBlurFactor
   customAnimPresets animCustomName colorPresetBase
   ```

3. `build/install.ps1:81` 的策略是「**不在丢弃名单里的一律备份并还原**」（全量，不是白名单）。
   DryRun 只列出 9 个字段，是因为 **XML 里本来就只有这 9 个键**，不是脚本漏备份。

4. 为什么 XML 里没有 `useShadow`：IntelliJ 的 `XmlSerializer` **不写默认值**。
   `useShadow` 的默认值是 `true`（见 `VDefaultFreeze.GOLDEN`），从未偏离过 →
   一直不落盘 → 安装前后运行时取到的都是 `true`。

**用户 2026-10-30 补充的关键事实**：「那个是纯平滑派生来的，本来没开启辉光，
现在默认开启的，而且辉光数值对不上疑似用的初始数值，但是能改说明不是严重 bug。」

**由此定位到真正的差异（同一天核实）**：这个自定义预设派生自内置「纯平滑输入」。
两者逐字段对比，13 个预设域字段里 **10 个继承正确，3 个对不上**：

| 字段 | 纯平滑输入（内置常量） | 个人自定义（快照里存的） |
|---|---|---|
| `useShadow` | **false** | **true** ✗ |
| `overlayFadeOut` | **false** | **true** ✗ |
| `glowLayers` | **8** | **12** ✗ |
| `slopeTicks` | 100 | 100 ✓ |
| `glowOpacity` | 0.30 | 0.3 ✓ |
| `shadowBlurFactor` | 0.60 | 0.6 ✓ |
| `stretchRatioK` | 2.0 | 2.0 ✓ |
| `trailSize` / `animationLength` / `shortAnimationLength` / `tailOpacity` / `cursorDisappearDelay` / `cursorFadeOutDuration` | — | 全部吻合 ✓ |

**已排除的方向**：

- 不是「这三个字段不在预设域」——`applyAnimPresetFields` 明确写了它们：
  `ui/settings/NeovideCursorConfigurable.java:2818-2820`（`writeAnimValues` 的第 4 项是
  `p.glowLayers()`）、`:2821`（`s.useShadow`）、`:2822`（`s.overlayFadeOut`）。
- 不是「还原脚本把值改坏了」——见上面四条证据；而且安装前后的 `useShadow`
  都是「不落盘 → 运行时取默认 true」，中间没有发生过 false → true 的转换。

**根因方向（未定位，但已收窄）**：问题出在「派生那一刻 `pending` 里这三个字段是什么」，
不在字段域本身。最可疑的是 `NeovideCursorConfigurable.afterPendingEdited()` 的 A 分支
（`:2256-2266`）：**站在自定义预设上时，任何一次编辑都会把 `snapshot(pending)` 整份写回快照**。
于是只要 `pending` 被任何路径（面板回灌、`reset()`、配置同步）改回默认值，
用户再随手拖一个滑块，这个「污染值」就被**永久固化**进快照。

这正好解释了用户说的「能改说明不是严重 bug」——手动改回来即可，
但改回来之后**不会再自动恢复**，因为快照已经被那次编辑写死了。

**优先级**：低（用户 2026-10-30 明确「先不弄」）。

**真要查时的第一步**：在 `afterPendingEdited()` 的 A 分支前后各打一行诊断，
记录 `pending` 的 `useShadow` / `overlayFadeOut` / `glowLayers`，
复现路径是「点纯平滑输入 → 派生副本 → 改任意一个预设域滑块」。
看这三个值在哪一步变成了 true / true / 12。

---

## BUG-015 拉伸是从两边均匀，还是按输入/删除的方向？（**待核实，2026-10-30 用户提出**）

**用户问**：「拉伸是从两边均匀拉伸还是按照输入删除的方向」

**已知的代码事实**（`CLAUDE.md` §4.1 / `deriveTrailFactors`）：

- 四个角各挂一个阻尼弹簧，按「运动方向与角点的夹角」排名 rank 0..3；
- rank 0 = 最靠后（拿最长时长、最慢到位），rank 3 = 最靠前（最短时长、最快到位）；
- 于是前缘紧跟、后缘拖沓 —— 拖尾落在**运动方向的反面**；
- `slope=0` 时同侧两角系数相等（`[0.86, 0.86, 0.32, 0.32]`）→ 只拉伸不倾斜，
  视觉上像「两边对称地扩」；
- `slope=100` 时是四档梯度（`[1.00, 0.72, 0.42, 0.22]`）→ 平行四边形，方向感明显。

**要核实的问题**：用户当前 `slopeTicks=100`，实际观感究竟是
「跟着输入/删除方向拖」，还是「左右两边对称地扩」。
如果是后者，说明 rank 分配或 `applySlopeOverlay` 没有按预期生效。

**判定方法**（离线可做，不必实机）：
写一条探针 —— 光标 2×32，单行右移一字符，`jump()` 后推进 N 帧，
分别量「左边缘两角的 x 差」与「右边缘两角的 x 差」，
与 `docs/NEXT-STEPS.md` §四-B 的基线（左 2.11 / 右 4.06）对照。
两者若接近相等，就是「对称拉伸」；若右明显大于左，就是「按方向」。

**注意**：§四-B 那张表是在「四角全不锁」的基线下测的。
如果之后实现 `leadingSnap`（锁前缘两角），前缘会精确变成 0.00 ——
届时本条要给新的基线，不能沿用旧数字。

**优先级**：待核实（未排期）。

---

## BUG-016 补全候选弹窗打开时拖尾闪烁、短跳变短、辉光瞬灭（**待定位，2026-11-03 用户报**）

**用户报（原话）**：

> 有候选词输入时候（触发条件为 tab 补全或者 private 那种上边有框框的候选），
> 打字手感变得很闪一闪一闪的动画帧不流畅，辉光最明显了（淡入淡出类似消失），
> 其次是纯平滑输入（影响小），短跳动画变短，退化成了我们上游 fork 之前的手感。

**环境**：Windows 11 · IDEA 2026.1（build 261）· 插件 0.9.127（读 jar 内
`META-INF/plugin.xml` 实测，不认文件名）。

**触发条件**：

- 代码补全 lookup 弹窗打开时（自动弹出 `private` 候选，或 Tab 补全）；
- 在弹窗打开期间继续输入 / 删除 / 接受候选；
- 关闭弹窗后手感恢复。

**已知代码事实（不是根因，只是排查起点）**：

- 每帧 `dt = min(rawDt, MAX_DT = 50ms)`；`recovery = rawDt > 100ms`。
  见 `CaretAnimator.tick()`。
- `MAX_DT` 的注释要求它 ≥ `MAX_ADAPTIVE_DELAY_MS = 40ms`，否则调度器交付的
  33~40ms 间隔会被物理步长丢掉，累计成慢放。
- `recovery` 分支：`rawDt ≤ RECOVERY_STEP_CAP_SECONDS` 时按 8ms 一帧推进；
  更长则一次性归位。
- `DampedSpring.update(dt)` 首句 `animationLength <= dt` 直接归零 —— 一帧到位。
- 辉光 `GlowIntensity.advanceTrailGlow(...)` 每帧最多下降
  `dt / fadeOutSeconds`；`dt` 变大会让单帧降幅成倍增加。
- 诊断：`diagLogging` 打开后 `[tick]` 行含
  `rawDt / dt / recovery / dispatchLag / cost / note`，落点
  `<log>/neovide-cursor-diag-<pid>.log`。取证链路见 `docs/BUG-LOG.md §BUG-007`。

**待验证假设（按优先级；先取证再改代码）**：

- **H1（最可能）**：弹窗期间 EDT 交付间隔变大，落在 20~100ms 的「中间地带」——
  `recovery` 不触发，`dt` 被放大到 2~6 倍；短跳弹簧一帧走完，辉光一帧掉一大截。
  证据：`[tick]` 的 `rawDt` / `dispatchLag` 在弹窗打开时显著大于关闭时。
- **H2**：弹窗触发额外 caret 事件 / `forceResync` / `lastMoveNanos` 重置，
  拖尾反复从零开始。证据：`[evt]` 事件在弹窗打开时激增；caret offset 未变但事件频繁。
- **H3**：弹窗/重绘导致覆盖层被清空或降频（`isShowing` / hidden tier / adaptive delay）。
  证据：`[tick]` 的 `note=not-showing`、`[clear]` 原因、`delayMs` / `measuredFps`。
- **H4**：弹窗绘制与覆盖层重绘冲突（Swing 子组件层级 / 裁剪）。
  证据：`[paint]`、脏区、闪烁位置与弹窗位置相关。

**取证步骤**：

1. 设置 → 调试 → 勾选「诊断日志」。
2. 输入 `priv` 触发 `private` 候选；保持弹窗，继续输入/删除 20 次；
   按 Tab 接受候选；关闭弹窗，重复同样序列作为对照。
3. 对比日志：`rawDt` / `dt` / `recovery` / `dispatchLag` 的 P50、P95、max；
   `note` / `delayMs` / `measuredFps`。
4. 把对比表回填到本节；结论未出之前不要改生产代码。

**优先级**：高（用户可见的打字手感回归）。**状态**：根因已确认（**关闭常驻刷新**后的空闲/自适应档），两个伴生缺陷已修。

### 2026-11-03 实机确认：关闭「常驻刷新」才会出现删除延迟

用户最终确认（同一 jar、同一预设，只切 Tools →「Neovide 光标：常驻刷新」）：

```
常驻刷新 开  → 删除 / 打字 丝滑
常驻刷新 关  → 删除出现「文字先消失、光标随后才跟上」
```

即：延迟是 **非常驻（空闲 / 自适应档）路径**的代价，不是预设参数，也不是
「144fps 占满 EDT」。**此前把方向记反了，这里更正。**

**为什么关掉会延迟（代码事实）**：

1. `alwaysHighFps=false` 时 `computeNextDelayNs(active=false)` 把调度间隔设成
   空闲档：聚焦 120ms / 失焦 600ms（`CaretAnimator` 的 `IDLE_DELAY_MS` /
   `IDLE_UNFOCUSED_DELAY_MS`）。
2. 击键后 `wakeUp()` 会置 `nextDueNanos=now` 并打断调度线程，理论上应立刻投递；
   但恢复后的间隔走自适应 `smoothedIntervalMs × 0.85`，上限 40ms
   （`MAX_ADAPTIVE_DELAY_MS`）。空闲帧（120/600ms）会把平滑值喂大；若复位
   时机没覆盖到，打字期间会以 20~40ms/帧推进 —— 100ms 的短跳只剩 3~5 帧，
   观感就是「光标落后、随后追上」。
3. 空闲超过 100ms 后的第一帧还会进 `recovery`：dt 被强制成 8ms，动画时钟慢于
   墙钟，进一步放大「落后」。

**给「常驻刷新」那个会话的落点（方向已更正）**：
- 目标路径是 `alwaysHighFps=false`：`wakeUp()` 的 `fromIdleTier` 复位是否覆盖
  「空闲档 → 活跃档」的所有入口（隐藏切回、失焦切回、首次击键）；
  `computeNextDelayNs` 的自适应是否应在击键后强制回到 `ACTIVE_DELAY` 若干帧。
- 观感目标：关掉常驻刷新时，按键后的第一帧仍在 ~8ms 内交付，且接下来十几帧
  保持活跃档，而不是立刻被 40ms 上限拖慢。
- 不要动的：`alwaysHighFps=true` 路径（用户确认丝滑）。
- 相关日志：`[tick]` 的 `delayMs`（空闲档是 120）/ `rawDt` / `recovery`；
  `[boot]` 行的 `alwaysHighFps`。

**附带更正的旧结论**：
- 0.9.128 期间的「更差」主要是 attempt-1 的「目标变化帧限速」（已整体回退），
  不是常驻刷新；
- `install.ps1` 备份时机修复仍然有效（避免退出前的旧快照覆盖），但它不是
  本次删除延迟的原因。

### 两个伴生缺陷（2026-11-03 已修，分支 `fix/bug-016-completion-flicker`）

1. **跨行分支前缘时长没有下限**（`TrailCorner.jump()`）——**2026-11-03 Step C 已撤回**（见本节「Step C 实施结果」）：
   `trailSize=1.0` 时 `leading = animationLength × (1 - trailSize)` 精确为 0，
   `DampedSpring.update()` 首句 `animationLength <= dt` 命中 → 前缘每帧瞬移。
   而 `charsMoved` 是帧间累计量（`SpringTrail.updateCharsMoved`），一帧落进 4+
   次击键（连打、删词、帧稀疏合并）就越过 `typingMaxChars(3)` → 误切跨行分支
   → 「同一个按键，这次有拖尾、下次没有」。
   修法：`leading = max(leading, shortAnimationLength × 0.5)`（移植自
   `claude/typing-jitter-fix-6c8c96`，commit c13f517），面板文案同步注明「已触底」。
   守门探针 `VTypingSpan`（5 条）。

2. **旧像素被缓存固化**（`CaretAnimator.refreshTargetInReadAction`）：
   同一帧里先 `visualPositionToXY(cvp)` 读像素、再 `markAt(line, col, stamp)`。
   若平台在文档修改后的第一帧仍返回旧像素，缓存会记住「新位置 + 旧像素」，
   下一帧走快速路径早退 → 覆盖层停在旧位置，直到下一次编辑。
   修法：文档戳本帧变过 → 记入 `recheckNextFrame`，下一帧强制再走一次慢路径
   （`recheckThisFrame.contains(trail)`）。守门探针 `VMultiCursorFix` S6：
   S6-1 断言「戳变化的帧必须登记复查」；S6-2 断言复查后目标点纠正。
   **覆盖边界（如实记录）**：离线假编辑器里 `cameraTrail` 的缓存标记与实际
   活动 trail 不同步，S6-2 在「去掉 contains」的变异下仍会因顶层慢路径而通过；
   它守的是最终位置，不是「快速路径分支」本身。快速路径分支目前只有代码审查证据。

### 2026-11-03 Step C 取证：55ms 下限本身就是手感回归源（修复前证据）

**结论（先给）**：`aeff707` 的 `leading = max(leading, shortAnimationLength × 0.5)`
加在**真跨行与「同行误判成跨行」共用**的那支画笔上。真跨行前缘本该是 0ms
（`trailSize=1.0` 时公式精确为 0，`DampedSpring.update()` 首句使其第一帧到位），
下限把它抬到 55ms，于是三个内置预设的跨行缎带都被压窄。

**代码事实（HEAD = aeff707）**：

| 位置 | 内容 |
|---|---|
| `core/TrailCorner.java:368` | 原公式 `leading = animationLength × clamp(1 - trailSize, 0, 1)` |
| `core/TrailCorner.java:393` | `leading = Math.max(leading, config.shortAnimationLength * 0.5)` |
| `core/TrailCorner.java:256-257` | 判据 `sameLine = \|deltaYpx\| ≤ height × 0.5`；`isShortJump = sameLine && charsMoved ≤ typingMaxChars` |
| `ui/settings/NeovideCursorConfigurable.java:3426-3428` | 面板同源下限 `floorLeading` / `floored` / `Math.max` |
| `resources/messages/…properties:128` | `settings.hint.crossMoveFloor`（中英各一条） |

**触发范围**：`isShortJump` 为假就走跨行分支。除了 `deltaY > height/2` 的真跨行，
**同一行内 `charsMoved ≥ 4` 也走这一支**（Step B 才修）；下限对这一支同样生效。

**三个内置预设的实测参数**（`settings/CursorPresets.java:355/357/369`）：
`animationLength` = 0.10 / 0.28 / 0.10 s，`shortAnimationLength` = 0.11 s，
`trailSize` = 1.0（三者相同）。

**跨行四角时长（rank0 = 后缘，rank1 = 中档，rank2/3 = 前缘）**：

| 预设 | 有下限（现状 aeff707） | 无下限（0.9.127 / 恢复后） |
|---|---|---|
| 基础 | 100 / 77.5 / **55** / 55 ms | 100 / 50 / **0** / 0 ms |
| 缎带 | 280 / 167.5 / **55** / 55 ms | 280 / 140 / **0** / 0 ms |
| 纯平滑输入 | 100 / 77.5 / **55** / 55 ms | 100 / 50 / **0** / 0 ms |

下限占后缘的比例：基础 / 纯平滑 **55%**，缎带 **19.6%** —— 这就是「短动画预设变化最大」。

**实测（`VTypingSpan` 单跑，HEAD aeff707，`animationLength=0.10 / trailSize=1.0`）**：

```
charsMoved=1..3（短跳） 四角 = [0.0946, 0.0946, 0.0352, 0.0352]
charsMoved=4..6（跨行） 四角 = [0.1000, 0.0775, 0.0550, 0.0550]   ← 前缘 55ms
```

恢复后 `charsMoved=4..6` 的 rank2/3 应为 `0.0000`（第一帧短路，观感 8ms 到位），
rank0 仍为 `0.1000`（缎带 0.2800）。

**为什么不能留在「共享分支」上**：固定时长下限按定义会同时改掉正常路径；
真跨行 0ms 是缎带形状的来源，不是 bug。

### 2026-11-03 Step C 实施结果（撤回 55ms 下限）

**改了什么**（生产代码只有一条行为改动）：

| 文件 | 改动 |
|---|---|
| `core/TrailCorner.java` | 删除 `leading = Math.max(leading, shortAnimationLength * 0.5)`；0.9.90 注释块改为「已撤回 / 误判留给 Step B」 |
| `ui/settings/NeovideCursorConfigurable.java` | 恢复 `leading = animationLength × clamp(1 - trailSize)`（含 clamp，与生产逐字同源）；删 `floorLeading` / `floored` / `floorNote` |
| `NeovideCursorBundle.properties` / `_zh_CN` | 恢复 `comment.trailSize` / `comment.animLenExtra` / `hint.crossMove`（去掉 `{3}` 与下限句）；删除 `hint.crossMoveFloor` |
| `tools/settings-probe/VTypingSpan.java` | 契约恢复：T-1 改真跨行（垂直位移）前缘 = 0；T-2 拆 T-2a（短跳 1..3 逐位 = `shortAnimationLength × trailFactors`）/ T-2b（同行 4..6 走跨行分支，前缘 = 0，已知误判）；T-3/T-4/T-5 保留 |
| `tools/settings-probe/VHints.java` | H-5 改为 `= 0 ms` 且不含「触底 / the floor」 |
| `tools/settings-probe/VTextFacts.java` | T-2 恢复「前缘 ≤ 1 帧、后缘 ≥ 400ms」 |
| `tools/settings-probe/build-v-probes.ps1` | 仅注册注释同步（不再把下限写成修法） |

**四段验证（实跑）**：

```
① 红（只改探针、生产未动）
   VTypingSpan  PASS 4 / FAIL 2（T-1、T-2b）
   VHints       PASS 10 / FAIL 1（H-5）
   VTextFacts   PASS 5 / FAIL 1（T-2）
   V-PROBES RESULT: FAILED（3 个探针未通过）

② 绿（改生产 + 文案后）
   VTypingSpan 6/6、VHints 11/11、VTextFacts 6/6
   V-PROBES RESULT: ALL PASS

③ 变异红
   A. 把 Math.max 加回 TrailCorner → VTypingSpan T-1/T-2b + VTextFacts T-2 红，VHints 仍绿
   B. 只在面板加回下限 → 仅 VHints H-5 红（实际输出「前缘 = 55 ms」），其余全绿

④ 恢复绿
   V-PROBES RESULT: ALL PASS；D0 零 diff
```

**安装**：`build/install.ps1 -Version 0.9.131 -NoStart` 成功；jar 内版本 0.9.131；
个人配置 15/15 字段还原、0 丢弃。**按「用户当前实际」还原**，实际为
`stretchRatioK=2.0`、`animPresetBase=2`、`trailSize=0.2`（与任务书里的旧快照不同，
不是丢失）。注意：`trailSize=0.2` 时 `leading = 0.10 × 0.8 = 80ms`，55ms 下限本来就不生效；
要对比密度必须选 `trailSize=1.0` 的三个内置预设（基础 / 缎带 / 纯平滑输入）。

> 【2026-11-04 用户确认，更正】上面的 `trailSize=0.2` 是**旧记录，已过时**。
> 当前与期望值均为 **`trailSize=1.0`**（密度最高；调低会更难看）。
> 选词斜拖尾是 `trailSize=1.0` 下跨行 `leading=0` 的固有形状，**不是**安装重置。

**明确未修**：Step B（同行 `charsMoved ≥ 4` 误判）、A（补全弹窗闪烁）、D（删除延迟 / 常驻刷新）。
**状态**：探针绿、已安装 0.9.131，**待用户实机验收**。

**修复提示词**：[PROMPT-BUG-016-2026-11-03.md](archive/prompts/PROMPT-BUG-016-2026-11-03.md)
（自包含，可整段粘贴给执行会话）。

**下一步交接（跨行拖尾手感回归）**：[HANDOFF-2026-11-03-BUG-016.md](archive/handoffs/HANDOFF-2026-11-03-BUG-016.md)
—— 记录 `aeff707` 的前缘下限为什么让三个预设的跨行拖尾都变短，以及推荐改法
（只给「同一行内的连打/删词」单独分支，真跨行保持 0ms 前缘）。

**全天决策报告**：[DECISION-REPORT-2026-11-03.md](archive/decisions/DECISION-REPORT-2026-11-03.md)

**Step C 修复报告（交付记录）**：[STEP-C-FIX-REPORT-2026-11-04.md](archive/reports/STEP-C-FIX-REPORT-2026-11-04.md)
—— 根因、改动清单、四段验证、变异测试、审查结论、安装与实机验收步骤。
—— 给决策会话的完整素材（attempt-1 否决证据、常驻刷新根因、两个缺陷、待决策项）。

### 2026-11-04 Step B 取证：同一行 `charsMoved ≥ 4` 误判成跨行（修复前证据）

**结论（先给）**：`TrailCorner.jump()` 只把「同一行且 `charsMoved ≤ typingMaxChars(3)`」
判成短跳；同一行内一帧累计到 4 个字符以上时，`isShortJump == false`，
掉进**与真跨行共用**的跨行分支。该分支在 `trailSize=1.0` 时前缘精确为 0，
所以同一按键会出现「这次有拖尾、下次瞬移」。这是 0.9.127 时代就存在的行为，
**不是 0.9.130 的回归**。

#### 代码事实（HEAD = `cda7141`）

| 位置 | 内容 |
|---|---|
| `core/TrailCorner.java:199-200` | `deltaXpx = destX - previousDestX` / `deltaYpx = destY - previousDestY`（与上一帧**目标**的差） |
| `core/TrailCorner.java:253-257` | 判据：`sameLine = \|deltaYpx\| <= height × 0.5`；`isShortJump = sameLine && charsMoved <= config.typingMaxChars` |
| `core/TrailCorner.java:283-287` | `lastShortJump` 只在 `decided == true` 时更新，供零尺寸兜底（`:271`）使用 |
| `core/TrailCorner.java:289-301` | 短跳分支：`animationLength = shortAnimationLength × trailFactors[rank]` |
| `core/TrailCorner.java:363-393` | 跨行分支：`leading = animationLength × clamp(1 - trailSize, 0, 1)`、`trailing = animationLength`；rank2/3 → `leading`，rank1 → 中值，rank0 → `trailing` |
| `ui/SpringTrail.java:139-148` | `updateCharsMoved()` 返回 `\|caretOffset - lastCaretOffset\|` —— **帧间累计量**，不是单次击键 |
| `tools/settings-probe/VTypingSpan.java:98-107` | T-2b 当前把「同行 4..6 走跨行、前缘 = 0」写成了契约（Step B 要改方向） |

#### 参数（`settings/CursorPresets.java:355/357/369` + `NeovideConfig` 默认值）

`shortAnimationLength = 0.11 s`，`typingMaxChars = 3`，三个内置预设的
`animationLength` = **0.10 / 0.28 / 0.10 s**，`trailSize` 均为 **1.0**。
四角系数（`NeovideCursorSettings.deriveTrailFactors`）：

```
基础        k=4.5 smooth=0.0 slope=0   → [0.86, 0.86, 0.32, 0.32]
缎带        k=8.0 smooth=0.0 slope=0   → [0.85, 0.85, 0.24, 0.24]
纯平滑输入  k=2.0 smooth=0.2 slope=100 → [1.00, 0.85, 0.69, 0.58]
```

> 注意：纯平滑输入的短跳 rank0 = `0.11 × 1.00 = 110 ms`，**大于**它的
> `animationLength = 100 ms`。这是合法值（短跳分支不读 `animationLength`），
> 所以 B 的探针不能用 `rank0 <= animationLength` 当判据。

#### 四角时长复算（单位 ms；rank0 = 后缘，rank1 = 中档，rank2/3 = 前缘）

以 Step 0 基线实测为准（下面的数字来自当时的输出；`build/v2-probe/VTypingSpan.txt`
会被后续门禁重跑覆盖，不能作为长期出处）：

```
配置：animationLength=0.10s shortAnimationLength=0.11s trailSize=1.00 typingMaxChars=3
charsMoved=1..3（同一行，短跳）       四角 = [0.0946, 0.0946, 0.0352, 0.0352]
charsMoved=4..6（同一行，误判跨行）   四角 = [0.1000, 0.0500, 0.0000, 0.0000]
真跨行（deltaY = height）             四角 = [0.1000, 0.0500, 0.0000, 0.0000]
```

按同一公式推广到三个预设：

| 场景 | 分支 | 基础 | 缎带 | 纯平滑输入 |
|---|---|---|---|---|
| 同行 1..3 字符 | 短跳 | 94.6 / 94.6 / 35.2 / 35.2 | 93.5 / 93.5 / 26.4 / 26.4 | 110 / 93.5 / 75.9 / 63.8 |
| **同行 4..6 字符（误判）** | **跨行** | **100 / 50 / 0 / 0** | **280 / 140 / 0 / 0** | **100 / 50 / 0 / 0** |
| 真跨行 | 跨行 | 100 / 50 / 0 / 0 | 280 / 140 / 0 / 0 | 100 / 50 / 0 / 0 |

**观感差异在哪**：同一行 4..6 字符时，前缘（rank2/3）从 35.2 / 26.4 / 75.9 ms
变成 **0 ms** —— `DampedSpring.update()` 首句 `animationLength <= dt` 让前缘第一帧
就咬到新位置，只剩后缘拖出去。对缎带还叠加后缘从 93.5 ms 变成 280 ms。
这就是「同一个按键，这次有拖尾、下次没有」。

#### 起源（不是回归）

```
74d3a8a  2026-10-18 18:01:16 +0800
feat: 常驻刷新默认开启 + 原生光标开关 + CJK 短跳修复 + 完整交接文档
gradle.properties: pluginVersion=0.4.3
```

该提交引入 `charsMoved` / `setCharsMoved()` 与
`isShortJump = sameLine && charsMoved <= typingMaxChars`，误判从这时起就存在。
`git log --follow` 之后判据主线未变：`6f7d585`（2026-10-30）只补了
`charsMoved < 0` 的零尺寸兜底，Step C 的 `aeff707` / `79acfd3` 只改跨行
`leading` 下限，**都没有改 `isShortJump` 判据本身**。

#### 本次不碰的边界

- 真跨行（`sameLine == false`）保持 Step C 恢复的
  `leading = animationLength × clamp(1 - trailSize, 0, 1)`（`trailSize=1.0` → 0）；
- A（补全弹窗闪烁）、D（删除延迟）、E（上游跨行模型实验）均不属于 Step B；
- `core/NeovideConfig.java`（D0）、`CaretAnimator`、`SpringTrail`、`install.ps1` 不动。

#### 修复方向（方案 A，已由用户拍板）

```java
boolean sameLine = Math.abs(deltaYpx) <= height * 0.5;
boolean isShortJump = sameLine && charsMoved <= config.typingMaxChars;   // 保持
boolean isHorizontalBurst = sameLine && !isShortJump;                    // 新增

if (isShortJump || isHorizontalBurst) {
    // 短跳那支：shortAnimationLength × trailFactors[rank]
} else {
    // 真跨行那支：leading = animationLength × clamp(1 - trailSize, 0, 1)  ← 逐字不动
}
```

`lastShortJump` 必须同时记住「短跳」与「同行 burst」（`isShortJump || isHorizontalBurst`），
否则零尺寸兜底会把 burst 丢回跨行，`VAuditBatch1` A-24 会红。

### 2026-11-04 Step B 实施结果（方案 A：同行 burst 拆到短跳分支）

> **【2026-11-04 已撤回】** 本节记录的实施已于当天撤回：用户反馈目标症状
> 「连打偶发无拖尾」从未感受到，而选词 / End / 鼠标拖选等副作用可见。
> 当前行为见本节末尾「Step B 撤回」及
> `docs/archive/reports/STEP-B-REVERT-REPORT-2026-11-04.md`；以下内容仅保留为历史记录。

**生产行为改动只有一处**：`TrailCorner.jump()` 的判据从两分类改为三分类。

```java
boolean isShortJump = false;
boolean isHorizontalBurst = false;
if (charsMoved >= 0) {
    boolean sameLine = Math.abs(deltaYpx) <= height * 0.5;
    isShortJump = sameLine && charsMoved <= config.typingMaxChars;
    isHorizontalBurst = sameLine && !isShortJump;
}
boolean useShortBranch = isShortJump || isHorizontalBurst;
if (decided) lastShortJump = useShortBranch;
if (useShortBranch) { /* shortAnimationLength × trailFactors[rank] */ }
```

- 短跳公式与真跨行公式**逐字未动**；`git diff` 只出现判据、注释与 `lastShortJump` 赋值。
- `charsMoved < 0` 的两个兜底分支行为不变：零尺寸只读 `lastShortJump` 且 `decided=false`，
  归一化兜底照旧写记忆。
- 真跨行 `leading = animationLength × clamp(1 - trailSize, 0, 1)` 保持 Step C 原样，
  没有 `Math.max`、没有新下限。

**新契约（实测）**：

| 场景 | 分支 | 基础预设四角（秒） |
|---|---|---|
| 同行 1..3 字符 | 短跳 | 0.0946 / 0.0946 / 0.0352 / 0.0352 |
| 同行 4..6 字符 | **短跳（burst）** | 0.0946 / 0.0946 / 0.0352 / 0.0352 |
| 真跨行（trailSize=1.0） | 跨行 | 0.1000 / 0.0500 / 0.0000 / 0.0000 |

真跨行在 `trailSize = -0.5 / 0 / 0.5 / 1 / 1.5` 五档下均与
`animationLength × clamp(1 - trailSize)` 逐位一致（T-6）。

**探针改动（契约变更，非缺陷；未删断言）**：

| 文件 | 改动 |
|---|---|
| `VTypingSpan` | 6 → **9 条**：T-2b 改「同行 4..6 走短跳」；T-3 改真跨行；新增 T-6（跨行密度五档含 clamp 越界）、T-7（同行 burst 4..10、slope=0/100）、T-8（真跨行 + charsMoved=1，锁 `sameLine`）；`sameWithIn` 增加 NaN/Infinity 守卫 |
| `VBlockStretch` | B-2 改为「同行 charsMoved=50 与单行短跳一致」；B-1 未动 |
| `VUniform` | U-3 改用真跨行（`animationLength=0.28` 拉开差距）；新增 U-4（同行 10 与 1 同速） |
| `VAuditBatch1` | A-24 仅改注释/标签/变量名，断言逻辑与数量不变 |

**四段验证（实跑）**：

```
① 红（只改探针，生产未动）
   VTypingSpan 6/2（T-2b、T-7）、VBlockStretch 1/1（B-2）、VUniform 3/1（U-4）
   V-PROBES RESULT: FAILED（3 个探针未通过）
② 绿（改生产后）
   VTypingSpan 9/9、VBlockStretch 2/2、VUniform 4/4、VAuditBatch1 27/27
   V-PROBES RESULT: ALL PASS
③ 变异红（六段）
   M1 isHorizontalBurst=false          → T-2b/T-7/B-2/U-4 红（4 条断言）
   M2 真跨行加回 55ms 下限             → T-1/T-6 + VTextFacts T-2 红；VHints 绿
   M3 lastShortJump=isShortJump        → 仅 A-24 红
   M4 isShortJump 丢掉 sameLine        → 仅 T-8 红
   M5 删掉跨行 clamp                   → 仅 T-6 红（trailSize=1.5）
   M6 短跳时长改 Double.NaN            → T-2a/T-2b/T-4/T-7 红（NaN 盲区已堵）
④ 恢复绿
   V-PROBES RESULT: ALL PASS；D0 零 diff；无 MUTATION 残留
```

**审查结论**：`code-reviewer` / `java-reviewer` 均 **0 CRITICAL / 0 HIGH，APPROVE**。
MEDIUM 已在白名单内处理（T-3 假绿、NaN 盲区、T-6 未镜像 clamp、U-3 阈值、注释措辞）；
白名单外的过期注释只登记（见下）。

**已知副作用（B-AC-4，方案 A 的固有行为，不是缺陷）**：
同一行内所有 `charsMoved > 3` 的移动现在都走短跳，包括 Ctrl+End、行内远点击、
粘贴短文本、接受补全的大横向跳转。缎带预设的后缘从 280ms 变为 93.5ms（约 1/3）；
基础/纯平滑后缘几乎不变，前缘从 0 变为 35.2 / 75.9ms。**没有加固定时长上限**，
是否需要「同行大跳兜底」属于新的产品决策，等实机验收后由用户拍板。

**明确未修**：A（补全弹窗闪烁）、C（已验收，保持）、D（删除延迟）、E（上游跨行模型）。

**已知文档漂移（白名单外，未改）**：
- `tools/settings-probe/build-v-probes.ps1:273-275` 仍写「六条 / 同行 4..6 已知误判」；
- `ui/settings/AnimationPreview.java:670/686/735`、`ui/CaretAnimator.java:347/2481`、
  `ui/settings/NeovideCursorConfigurable.java:1438` 仍按旧判据解释跨行；
- `CLAUDE.md` / `docs/NEXT-STEPS.md` 的 `VTypingSpan 5` 旧数字。
以上行为仍正确（真跨行由 `sameLine=false` 决定），只是归因文字过期。

**状态**：探针绿、D0 零 diff；待打包 0.9.132 与用户实机验收。

### 2026-11-04 Step B 撤回（无可感知收益、副作用可见）

**结论**：Step B 当天撤回。生产判据恢复为 0.9.131——同一行 4+ 字符仍走跨行。
真跨行公式、Step C、A/D/E 均不受影响。

**为什么撤回**：

- 用户明确表示「连打 `ffff...` / 长按退格 / Ctrl+Backspace 时有时无拖尾」
  **从未感受到**；
- 而 `Ctrl+Shift+方向键`选词、`End` / `Home`、鼠标拖选、粘贴/接受补全等
  **同行大跳**的拖尾变短、变斜、帧变少，用户能明显看到；
- 该「bug」的来源是 `c13f517`（2026-10-27，分支 `claude/typing-jitter-fix-6c8c96`，
  Claude 会话）里的一句「用户观察」，无 issue / 日志 / 原始对话；
  0.9.90 距撤回时已 **42 个版本**，执行前未再确认症状是否可复现。

**改了什么**：

| 文件 | 改动 |
|---|---|
| `core/TrailCorner.java` | 删除 `isHorizontalBurst` / `useShortBranch`；`lastShortJump = isShortJump`；`if (isShortJump)` |
| `VTypingSpan.java` | T-2b / T-7 改回「同行 4+ 仍走跨行」；保留 T-3/T-6/T-8、NaN 守卫、clamp 镜像 |
| `VBlockStretch.java` | B-2 恢复「修前确实用了更长 animationLength」 |
| `VUniform.java` | U-3 保留真跨行；U-4 改为「同行 10 仍与 1 不同（已知误判）」 |
| `VAuditBatch1.java` | A-24 注释恢复「上一帧是跨行」 |

**验证（实跑）**：

```
撤回后门禁：V-PROBES RESULT: ALL PASS（VTypingSpan 9/9、VBlockStretch 2/2、
            VUniform 4/4、VAuditBatch1 27/27）
D0：零 diff
反证 R1（重新加回 Step B）：T-2b / T-7 / B-2 / U-4 红；恢复后 ALL PASS
安装：0.9.133（jar 内版本核验）；个人配置 12/12 还原、0 丢弃
```

**仍未解决**：跨行帧变少与 Step B 公式无关；`trailSize=1.0` 已由用户确认是期望值
（不要调低），优先核对预设指针与 `animationLength` 是否被安装重置。

**流程教训**：二手归因不能当既定 bug；跨 40+ 版本的旧观察必须重新取证；
探针只能证明分支变了，不能证明用户感受到了修复。

### 2026-11-04 结论汇总（本节是全条 BUG-016 的现状，以上都是过程记录）

> 上面的正文按时间顺序记录「发生过什么」，包括已经作废的判断。
> **要判断现在是什么状态，只看这一段。** 完整摘要见
> `docs/CURRENT-STATE-2026-11-04.md`。

| 项 | 当前结论 |
|---|---|
| 已装版本 | **0.9.131**（读 jar 内 `META-INF/plugin.xml` 实测；文件名恒为 `0.9.8.jar`） |
| 当前预设 / `trailSize` | 面板选中「基础测试2」，`trailSize = 1.0`（当前值 = 期望值） |
| `trailSize = 0.2` | 属于另一个预设「纯平滑输入 副本」，是**过期记录**，不是当前值、不是安装重置 |
| **A**（补全弹窗闪烁 / 短跳变短） | **未修；2026-11-04 用户决定暂缓**（理由：「很难告」）。需要修时按 `PROMPT-A-POPUP-FLICKER-2026-11-04.md` |
| **B**（同行 `charsMoved ≥ 4` 误判跨行） | **降级为「未复现的代码疑点」，不是 bug**；Step B 已实施后当天撤回（`fddd529`），不再修 |
| **C**（撤回 55ms 跨行前缘下限） | **保留，已验收**：真跨行前缘回到 0 ms，三个内置预设密度恢复 |
| **D**（删除延迟 / 常驻刷新） | **不做**；另一条线，目标路径 `alwaysHighFps=false` |
| **E**（上游跨行模型实验） | **不做**；B 已撤回，前提不成立 |
| 选词斜拖尾 | **正常形状**：`trailSize=1.0` 时跨行 `leading=0`，前缘 0 ms、后缘 100/280 ms；0.9.131 同样如此 |
| 0.9.132 / 0.9.133 | **中间版**，运行时行为 = 0.9.131；不作为基线，也不回退/重装 |

**下一步**：只做「跨行密度」实验，A/B/C/D/E 的代码都不动。
