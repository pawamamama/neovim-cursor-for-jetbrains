# 代码核实与审计 · 0.9.87

> 审计对象：`src/main/java/com/github/yyyolo7a79/neovidecursor/`（34 文件）
> 审计时间：2026-10-26
> 方法与口径：**每个结论都有 `文件:行号` 证据或实测数字**，没有"我认为"。
> 纪律依据：`docs/WORK-DISCIPLINE.md` 四-B（字段四层拷贝）/ 四-D（一字段两职责）/ 四-G（探针假通过）/ 四-J（看可维护性）

---

## 零、这次审计先撞上的两件事

**这两条比代码里的任何一条问题都严重，所以放最前面。**

### 0.1 门禁脚本从 0.9.83 起就从未真正执行过

`tools/settings-probe/build-v-probes.ps1` 第 197 行（当前版本）缺一个右括号 ——
`$mains = @(...)` 这个数组从未闭合，后面所有代码在解析阶段就被当成数组元素的一部分。

实测：

```
[System.Management.Automation.Language.Parser]::ParseInput(...)
→ L197: 子表达式中缺少右括号"）"。
```

回溯 git 历史，**从 rev `3b8de23` 起（即 0.9.83「探针覆盖度 49→80」那次）每一版都 ParseError**：

```
rev 46747b8  parseErr=1 (L197)   ← 当前 HEAD
rev 3b8de23  parseErr=1 (L120)   ← 首次出现
rev 0602852  parseErr=0          ← 最后一个能跑的版本
```

**后果**：`NEXT-STEPS.md` 里写的「探针通过 72/82」「74/83」这个基线，
**没有任何一次是由这份脚本产出的**。它跑不起来，`throw` 和 `exit 1` 一个都没执行过。
「门禁」在这个项目里事实上是空的 —— 这直接解释了为什么 9 个探针能"已知失败"这么久没人追。

### 0.2 语法修好之后，第二步也是断的

补上右括号后再跑，暴露第二个故障：

```
& javac ... -d $probeDir $vprobes
→ 程序'javac.exe'运行失败：文件名或扩展名太长。
```

87 个绝对路径当命令行参数，超过 Windows 的 32767 字符上限。
也就是说：**即使语法正确，探针编译这一步同样从未成功过。**

### 0.3 本次修复（唯一改动的文件）

`tools/settings-probe/build-v-probes.ps1`：

1. 第 105 行补上 `)` 闭合 `$mains` 数组（并加注释说明缺了多久）
2. javac 改用 `@argfile`（JDK 官方解法，把路径写进文件让 javac 自己读）
3. 把 **`VReset` 补进清单** —— 它写好了却从没进过表，与 0.9.83 那次"写了但从不运行"同类

修复后 **真正跑通了一次全量门禁**，结果见 §四。

---

## 一、核实结果

### a) `SpringTrail.assignRanks()` 的排序逻辑

**结论：正确且稳定。** `ui/SpringTrail.java:234-304`

| 检查项 | 证据 | 判定 |
|---|---|---|
| 每次重置为 0..n-1 | `:287-289` `for (i) order[i] = i;` | ✅ 正确，避免了"复用数组导致 tiebreak 依赖历史" |
| 稳定排序 | `:291-299` 三路插入排序，比较用 `alignment[order[j]] > alignment[key]`（严格大于） | ✅ 严格大于 ⇒ 相等时保持原相对顺序 = 稳定 |
| tiebreak 确定性 | `:301-303` `corners[order[k]].jump(..., k)` | ✅ 并列时按角点固定序 TL/TR/BR/BL 拆开，方向恒定 |
| rank 互不相同 | 排序后 k = 0..3 逐个赋值 | ✅ 恒为 4 个互异值（这正是 0.9.77 修的） |
| 触发时机 | `:205-208` 只在 `jumped` 时算，`setTarget` 置位 | ✅ 目标不变就不重算，无每帧浪费 |

**一个瑕疵（Low）**：`:270` 用 `Integer[] idxScratch`，而 `:290` 的注释写着
「避免 `Arrays.sort` 的**装箱** Comparator 开销」—— 数组本身已经是装箱的。
实际无害（n=4，`Integer.valueOf` 缓存 -128..127 覆盖全部下标，不会分配），
但注释的理由与实现不符。改 `int[]` 可让注释成真，收益≈0。

### b) `deriveTrailFactors(k, smooth, slope)` 的实际作用范围与边界

三参版本见 `settings/NeovideCursorSettings.java:583`，实际分解为两次叠加：

```
deriveTrailFactors(k, smooth, slope)
  = applySlopeOverlay( deriveTrailFactors(k, smooth), slope )
       ↑ :593-604                    ↑ :553-574
```

**实测（本次用 `build/ZzAuditCheck.java` 直接调生产方法，非复刻）**：

| k | smooth | slope | 产出四角系数 | 判定 |
|---|---|---|---|---|
| 4.5 | 0 | 100 | `[1.00, 0.72, 0.42, 0.22]` | = 上游原值 |
| 4.5 | 0 | 50 | `[0.93, 0.79, 0.37, 0.27]` | 线性插值 |
| 4.5 | 0 | 0 | `[0.86, 0.86, 0.32, 0.32]` | 纯矩形（同侧等速） |
| 4.5 | 0 | **-1** | `[0.86, 0.86, 0.32, 0.32]` | **← 见下面的缺陷** |
| 4.5 | 0.5 | 100 | `[1.00, 0.83, 0.65, 0.53]` | smooth 压平 |
| 0 (→clamp 1) | 0 | 100 | `[1.00, 1.00, 1.00, 1.00]` | 四角同速、无拖尾 |
| 99 (→clamp 8) | 0 | 100 | `[1.00, 0.69, 0.35, 0.13]` | 上限 |
| NaN | 0 | 100 | `[1.00, 1.00, 1.00, 1.00]` | 静默退化为 k=1 |

**参数作用范围**：

- `k` ∈ [1, 8]，clamp 在 `:466`。k=4.5 有显式特例（`:467-470`）直接返回规范常量数组，
  目的是与 `NeovideConfig.trailFactors` 逐位相等（parity 基准要求）
- `smooth` ∈ [0, 1]（面板 0–100），内部再乘 0.8（`:595`），所以**最大只能压平 80%**
- `slope` ∈ [0, 100]，clamp 在 `applySlopeOverlay:557`

**缺陷 1（Medium）：`slope < 0` 的语义与 javadoc 相反**

javadoc 两处写着「`slope < 0` 表示「用 k 推导」（旧值兼容）」——`:515` 与 `:581`。
但 `:557` 是 `clamp(slope, 0f, 100f)`，**-1 被夹成 0**，而 0 的语义是「纯矩形」。

实测对比（k=4.5, smooth=0）：

```
k 推导（正确预期） = [1.00, 0.72, 0.42, 0.22]
slope = -1 实际结果 = [0.86, 0.86, 0.32, 0.32]   ← 完全不同
```

`NeovideCursorSettings.java:401` 的注释也还写着「slopeTicks >= 0 时用斜度，否则沿用 k 推导（默认 -1 = 旧行为）」，
而 `State.slopeTicks` 的默认值早已是 100（`:879`）。
这正是纪律四-J 第 3 条「名字/注释与行为不符，要改不要只加注释解释」。

**缺陷 2（Low）：`applySlopeOverlay(base, 100)` 返回入参同一实例**

`:558-559` `if (t >= 1f) return base;` —— 实测 `out100 == base` 为 `true`。
目前调用方（`syncStateToConfig:402`）每次都拿到新数组，所以没炸；
但这是"返回别名"契约，任何一个调用方改成持有并修改就会污染 config。

### c) `CaretTrailPanel` 的绘制路径

**结论：三条填充路径确实互斥；存在一处真死参数和一处冗余状态。**

调用链（全部在 EDT 上）：

```
paintComponent(Graphics)            :727
  ├─ hasGhosts ? paintGhosts(g2)     :1046   ← 残影模式
  └─ for i in [0, quadCount)          :741   ← 弹簧模式（多光标逐个）
       paintQuad(g2, i)              :751
         ├─ buildQuadPath(index)     :833    → 复用 quadPath
         ├─ drawQuadGlow(g2, path, glow)  :871
         └─ 三选一填充               :799-822
              cursorRgbWash   → buildRgbWashPaint   （彩虹）
              rgbEnabled      → buildDualTonePaint  （双拼）
              else            → setColor(fillColor) （纯色）
         g2.fill(path)               :823
```

| 核实点 | 证据 | 判定 |
|---|---|---|
| 三条路径互斥 | `:799` `if` / `:812` `else if` / `:822` `else` | ✅ 结构上互斥，彩虹优先 |
| 残影与弹簧互斥 | `beginQuads`:514-517 与 `updateGhosts`:570-573 互相清对方标志 | ✅ |
| `paintForPreview` 复用同一套 | `:1098-1108` 与 `paintComponent` 同构 | ✅ 预览与生产同源 |

**死代码 1（Medium）：`drawQuadGlow` 的第二个参数从未使用**

``:871` 签名 `private void drawQuadGlow(Graphics2D g2, Path2D.Double ignoredPath, float quadGlow)`，
体内 `:872` 至 `:929` **一次都没引用 `ignoredPath`** —— 连名字都直说了 `ignored`。
调用点 `:776` 还在传 `path`。删参数即可，零风险。

**冗余状态（Low）：`hasQuad` 与 `quadCount > 0` 恒等价**

`hasQuad` 的全部写入点：`:70` 初值 /`:515` beginQuads 置 false /`:543` addQuad 置 true /
`:571` updateGhosts 置 false /`:707` clearCorners 置 false。
读取点只有两处 `if (!hasQuad && !hasGhosts) return;`（`:730`、`:1102`）。
而 `quadCount` 与它**永远同步变化**（addQuad 同时 `++quadCount` 和 `hasQuad=true`）。
`hasQuad` 可整体替换为 `quadCount > 0`，少一个字段、少三处赋值、
少一类"两个标志打架"的可能（`:712-713` 的注释正是在描述这种打架）。

### d) `CaretAnimator` 的帧调度

| 方法 | 证据 | 判定 |
|---|---|---|
| `activeDelayNsForTarget()` | `:436-442` `1_000_000_000L / fps`，fps≤0 回退 `ACTIVE_DELAY_NS` | ✅ 正确。125→8.000ms / 144→6.944ms，整数截断误差 -0.2%（实测 143.8fps） |
| `computeNextDelayNs(active)` | `:988-1007` | ✅ 三档逻辑正确；`alwaysHighFps=true`（默认）恒走活跃档 |
| `scheduleNextDue()` | `:707-720` `next = dispatched + currentDelayNs; nextDueNanos = max(next, now)` | ✅ 实现与 javadoc 的"不多等一个 delay"一致 |
| 调度循环睡眠 | `RenderScheduler.java:170-186` | ⚠️ 见下 |

**实测帧率（`build/ZzSchedDispatch.java` 忠实复刻 loop + dispatchDue 的计时结构）**：

```
fps=125  实测 125.0  间隔均值 8.000ms  min 6.008  max 10.001  提前派发 525/1200（平均提前 318µs）
fps=144  实测 144.0  间隔均值 6.945ms  min 5.008  max  8.506  提前派发 571/1200（平均提前 344µs）
fps=165  实测 165.0  间隔均值 6.061ms  min 4.010  max  7.905  提前派发 582/1200（平均提前 373µs）
fps=240  实测 240.0  间隔均值 4.166ms  min 2.009  max  6.998  提前派发 654/1200（平均提前 373µs）
```

**均值精准达成，但单帧间隔在 ±2ms 波动**（125fps 档 6.0~10.0ms）。
根源是 `dispatchDue():230` 的容差 `DUE_TOLERANCE_NANOS = 1ms`：
`if (due > now + 1ms) continue;` ⇒ 允许**提前最多 1ms 派发**。
提前量被下一轮 `due += delay` 自动吸收，所以长期均值不掉，但短时抖动存在。
这是"125fps 是平均值、不是稳定节拍"的事实口径 —— 值得写进 `PERFORMANCE-FPS.md`。

**真发现（Medium）：`onSpinWait` 忙等补偿是死分支**

`RenderScheduler.java:166` 的注释：
> 「现在：睡到「毫秒下界」，剩下的不足 1ms 部分用 nanoTime 忙等补齐」

实测（`build/ZzSchedFaithful.java`，800 帧 / 4 个档位）：

```
分支命中：sleep 分支 = 799/800，忙等分支 = 0/800，不等待 = 1/800
忙等累计时长 = 0.0µs
```

**忙等分支一次都没进过。** 原因：Windows 的 `Thread.sleep` 有过冲（实测平均 18µs ~ 1.5ms），
醒来时 `due - now` 通常已经 ≤0，直接落到 `else` 的"不等待"路径。
所以 `:179-186` 那段 `while (System.nanoTime() < due) Thread.onSpinWait();` 在 Windows 上不会被执行。

这条**顺带解释了 `NEXT-STEPS.md` §3.4 的结论**：「忙等更差（180 vs 221）」——
那个 180fps 测的是一段在生产中从不执行的代码。

### e) `RenderScheduler` 的自愈逻辑

**结论：主体正确，但漏掉了一条会永久停摆的路径。**

已覆盖的（`RenderScheduler.java:198-217`）：

| 保障 | 证据 |
|---|---|
| 整轮 try/catch(Throwable) | `:150` try 开始，`:198` catch |
| 连续 3 次失败 → 置 `thread=null` 让下次 register 重建 | `:201-211` |
| 失败后退避 1ms 防自旋 | `:213-216` |
| 成功一轮清零计数 | `:197` |
| 置 null 前比对 `thread == currentThread()` | `:204` 防误清新线程 |

**漏掉的（High）：`invokeLater` 抛异常会造成该 animator 永久活锁**

`dispatchDue():247-254`：

```java
if (!animator.framePending.compareAndSet(false, true)) continue;   // :247
animator.lastDispatchedDueNanos = due;                            // :250
posted = true;
SwingUtilities.invokeLater(animator::runTick);                    // :254  ← 这里抛异常
```

若 `:254` 抛出（EDT shutdown 期间 `invokeLater` 可以抛 `RejectedExecutionException`）：

1. `framePending` 已经是 **true**，但 `runTick` 永远不执行 ⇒ `framePending.set(false)\u200b`（`:673`）永不发生
2. `lastDispatchedDueNanos` 已经是 `due` ⇒ 下一次 `:243` `if (animator.lastDispatchedDueNanos == due) continue;` 恒命中
3. `nextDueNanos` 只由 `scheduleNextDue()`（在 `runTick` 的 finally 里）改写 ⇒ 永不更新

**该 animator 从此一帧都不会再被投递，且没有任何日志。**
循环级的 `catch(Throwable)` 会捕获这次异常并继续，但它无从得知"某个 animator 已经被卡死"。

这不是理论风险：`RenderScheduler` 的 `:144-147` 注释本身就写着
「`SwingUtilities.invokeLater()` 在 EDT shutdown 期间可能抛异常」——
作者知道这一点，但只修了"线程死掉"，没修"某个 animator 卡死"。

### f) 「一个字段承担两个职责」的残留

**这是本项目历史重灾区，本轮找到 2 处仍在生效的、1 处被冻结的。**

#### f-1（High）`rgbGradient` 仍是双职责 —— 且正在造成用户可见错误

State 里的语义（`NeovideCursorSettings.java:830`）：**「光标渐变（双拼）开关」**。
但三处代码仍把它当**「色相循环开关」**写：

| 位置 | 代码 | 后果 |
|---|---|---|
| `ui/CursorPresetApplier.java:162` | `s.rgbGradient = p.rgbWash();` | Tools 菜单选颜色预设时改写双拼开关 |
| `ui/settings/NeovideCursorConfigurable.java:1773` | `pending.rgbGradient = p.rgbWash();` | 面板选颜色预设时改写 |
| `ui/settings/NeovideCursorConfigurable.java:2101` | `pending.rgbGradient = p.rgbWash();` | 「恢复颜色预设」时改写 |

实测后果：选「彩虹（动态）」→ `rgbGradient=true` → 用户手动关掉的双拼被打开；
选任何其他颜色预设 → `rgbGradient=false` → 用户手动打开的双拼被关掉。

这是纪律四-D 的教科书案例：**「`rgbGradient` 是否等于 `rgbWash`」是 0.9.79 的语义，
0.9.85 改了语义却没改这三处写入。**

> 注：工作区当前有并发改动（另一路在改 `matchColorPreset` 的**读**判据，
> 并给彩虹换了独立 HEX）。那修的是"读"，上面三处"写"仍在。

#### f-2（High）`charsMoved` 的「传 -1 绕开」已产生可测量的行为分裂

`ui/CaretAnimator.java:327-345` 的 `updateBlockCharsMoved()`：

```java
if (caretOffset >= 0) { lastBlockOffset = caretOffset; }  // :342 只为诊断保留
return -1;                                                 // :344 恒返回 -1
```

于是块选区永远走 `TrailCorner.java:211-216` 的**像素兜底分支**：

```java
double jumpX = deltaXpx / width;
isShortJump = Math.abs(jumpX) <= config.shortMoveThreshold && Math.abs(jumpY) <= 0.001;
```

`width` 默认 2px、`shortMoveThreshold` 默认 8 ⇒ **阈值实际是 16px**。

**实测两种光标的分支分歧（`build/ZzBlockBranch.java`，直接用生产 `TrailCorner.jump()`）**：

```
=== 块选区路径（charsMoved = -1）===
位移      jumpX    分支        四角时长(ms)
 8px      4.00    短跳        110 46 24 79
16px      8.00    短跳        110 46 24 79
17px      8.50    跨行三档     100  0  0 50   ← 只多 1px 就翻分支，且前缘直接归 0
24px     12.00    跨行三档     100  0  0 50

=== 普通光标路径（charsMoved 由 offset 差给出）===
charsMoved=1 (全角16px)  短跳
charsMoved=2 (全角32px)  短跳   ← 32px 仍是短跳
charsMoved=3 (全角48px)  短跳   ← 48px 仍是短跳
charsMoved=4 (全角64px)  跨行三档
```

**同一次「移动 2 个全角字符」：普通光标走短跳（四角 110/79/46/24ms），
块选区走跨行三档（100/50/0/0ms）。** 前者有拉伸梯度，后者前缘瞬移、后缘拖满。
这正是 `WORK-DISCIPLINE.md` 四-D 里说的「加速度不一致」的技术债 —— 0.9.56 用"传 -1"绕开，
把判据从"字符数"换成了"光标宽度"，而光标宽度是用户可调的（1–5px）。
**用户把光标调到 4px，块选区的短跳阈值就变成 32px。** 这是新的隐式耦合。

#### f-3（Medium，被 D0 冻结）`NeovideConfig` 里 3 个死字段

| 字段 | 位置 | 生产读取点 | 现状 |
|---|---|---|---|
| `animationResetThreshold` | `core/NeovideConfig.java:190` | **0 处** | 只在 `TrailCorner.java:325/334` 的注释里被提到（说明"已放弃"） |
| `cursorWidth` | `core/NeovideConfig.java:241` | **0 处** | 宽度全走 `State.cursorWidthPx` |
| `shortMoveThreshold` | `core/NeovideConfig.java:165` | 1 处（`TrailCorner:215` 兜底分支） | 职责已漂移成"块选区主判据"，见 f-2 |

`NeovideConfig.java` 有 D0 零 diff 门禁，**这三个字段不能删**，只能记录。

### g) 未使用的字段 / 常量 / 方法

扫描方法：读全部 34 个生产文件，剥离注释后统计标识符出现次数（=1 即只有声明），
再逐个用 grep 在全仓（src + tools）复核。

| # | 位置 | 符号 | src | tools | 说明 |
|---|---|---|---|---|---|
| 1 | `ui/CaretAnimator.java:568` | `__unusedLastVisualLine` | 1 | 0 | 零引用，名字已标 `__unused` |
| 2 | `ui/CaretAnimator.java:569` | `lastVisualColumn` | 1 | 0 | 零代码引用（仅注释提到"不更新它"）；`:1317` 的注释是唯一痕迹 |
| 3 | `ui/CaretAnimator.java:313` | `BLOCK_SELECTION_KEY` | 1 | 0 | 块选区改成"每行一条线 + 行号 key"后失去用途 |
| 4 | `ui/CaretAnimator.java:316` | `lastBlockOffset` | 2 | 0 | 只在 `:342` 写，**零读取**（注释"只为诊断保留"但无读者） |
| 5 | `ui/CaretAnimator.java:2353` | `glowStrengthFor(...)` | 1 | 1 | 私有、生产零调用（tickSpring 用 `glowStrengthForValue`）。VMCDiff 用反射**只查存在性不调用** |
| 6 | `ui/CaretAnimator.java:2480` | `collectTrailTargets(...)` | 1 | 1 | public static、**生产零调用**（`refreshTarget:1408-1670` 是内联实现）。见 §二 P-6 |
| 7 | `ui/CursorDiag.java:376` | `configChanged(...)` | 1 | 0 | 零调用 ⇒ `[config]` 前缀从未输出过 |
| 8 | `ui/CursorDiag.java:400` | `swallowed(...)` | 1 | 0 | 零调用 ⇒ `[error]` 前缀从未输出过（而全仓有 57 处 `catch (Throwable ignored)`） |
| 9 | `ui/CaretTrailPanel.java:486` | `updateCorners(...)` | 2 | 4 | 生产零调用（仅探针 VQuad/VSubpixel） |
| 10 | `ui/CaretTrailPanel.java:548` | `quadCount()` | 1 | 11 | 生产零调用（仅探针） |
| 11 | `ui/CaretTrailPanel.java:871` | `drawQuadGlow` 的 `ignoredPath` 参数 | — | — | 死参数，见 §一 c |
| 12 | `ui/settings/NeovideCursorConfigurable.java:2691` | `eqHex` | 1 | 0 | 零引用，javadoc 却详细解释"为什么必须忽略大小写" |
| 13 | `ui/settings/NeovideCursorConfigurable.java:2702` | `eqText` | 1 | 0 | 零引用，同上 |
| 14 | `settings/CursorPresets.java:205` | `tailOpacityTicksValue()` | 1 | 0 | 零引用 |
| 15 | `settings/CursorPresets.java:224` | `typingSmoothnessTicks()` | 1 | 0 | 零引用 |
| 16 | `settings/CursorPresets.java:239` | `stretchRatioKTicks()` | 1 | 0 | 零引用 |
| 17 | `settings/CursorPresets.java:380-555` | **override 整块** | 内部自洽 | **0** | 见下 |

**最大的死块（Medium）：`CursorPresets` 的 override 机制约 175 行**

``:380` 到 `:555` 包含 `OVERRIDE_FIELDS` / `OVERRIDE_MIN` / `OVERRIDE_MAX` /
`parseOverride` / `putOverride` / `removeOverride` / `formatEntry` / `parseValues` /
`keyOf` / `splitEntries` / `join`。

**生产代码对这 11 个符号的引用数 = 0**。`State` 里对应的 `animPresetOverrides` 字段也已不存在
（全仓 grep 命中 0）。只有两处 javadoc 提到它（`NeovideCursorConfigurable.java:2256/2698`）。

这是 0.9.8 的设计，后来被"自定义预设副本"（`CustomPresets`）取代，**旧实现没删**。
它自带边界校验、NaN 检查、可逆序列化 —— 145 行精心写的代码，一行都没跑过。

---

## 二、问题清单（按 影响 × 修复成本 分级）

### Blocker

#### P-1 门禁脚本 ParseError，全项目无有效门禁

- **现象**：`build-v-probes.ps1` 每次执行在第 197 行 ParseError，一个探针都不跑
- **根因**：`$mains = @(...)` 数组从未闭合（0.9.83 增补探针时漏了右括号）
- **证据**：`ParseInput` → `L197: 子表达式中缺少右括号`；git 回溯 `3b8de23` 起每版皆然
- **修法**：第 105 行补 `)`。**已修**
- **风险**：零。修复后第一次真正运行，暴露的真实失败见 §四
- **影响延伸**：`NEXT-STEPS.md` / `WORK-DISCIPLINE.md` 里所有「探针 X/Y 通过」的基线都不可复现

#### P-2 javac 命令行超长，探针编译从未成功

- **现象**：语法修好后报「文件名或扩展名太长」
- **根因**：87 个绝对路径直接当参数，超 Windows 32767 字符上限
- **证据**：`ResourceUnavailable: ... javac.exe ... 文件名或扩展名太长`
- **修法**：改 `@argfile`。**已修**
- **风险**：零

### High

#### P-3 `rgbGradient` 双职责：选颜色预设会静默改写「光标渐变」开关

- **现象**：用户手动打开双拼 → 点任何一个颜色预设 → 双拼被关掉（或反之）
- **根因**：0.9.85 把 `rgbGradient` 的语义从"色相循环开关"改成"双拼开关"，
  但三处**写入**仍按旧语义赋 `p.rgbWash()`
- **证据**：`CursorPresetApplier.java:162`、`NeovideCursorConfigurable.java:1773`、`:2101`
- **修法**：这三处写入删除；「彩虹」的开关应由 `matchColorPreset().rgbWash()` **反推**（读路径已经是这样，`CaretAnimator:2259 resolveRgbWash`），不需要往 `rgbGradient` 里写
- **风险**：**中** —— 涉及颜色域，用户明确要求"不要碰"。且当前工作区另一路正在改这块

#### P-4 块选区与普通光标的短跳判据不一致（可测量）

- **现象**：同一次"移动 2 个全角字符"，块选区走跨行三档（前缘瞬移），普通光标走短跳（有梯度）
- **根因**：`updateBlockCharsMoved` 恒返回 -1 ⇒ 退到像素判据 ⇒ 阈值 = `shortMoveThreshold × cursorWidth = 8 × 2 = 16px`，且随用户调光标宽度漂移
- **证据**：§一 f-2 的实测表（块选区 16px→短跳 / 17px→跨行；普通光标 48px→仍短跳）
- **修法**：按行号 key 缓存上一帧 `blockStarts[i]`，用 offset 差算 `charsMoved`（与普通光标同源）
- **风险**：**中** —— 会改变块选区的观感。需要用户确认

#### P-5 `invokeLater` 抛异常 ⇒ 该 animator 永久活锁，自愈逻辑不覆盖

- **现象**：某个编辑器光标彻底不动，无日志
- **根因**：`dispatchDue:247-254` 先置 `framePending=true` 和 `lastDispatchedDueNanos=due`，再 `invokeLater`；
  后者抛出时两个标志都已落地，形成"已投递但永不执行"的死态
- **证据**：`framePending` 的唯一复位点 `CaretAnimator:673`（在 `runTick` 内）；
  `lastDispatchedDueNanos` 的唯一重置路径也在 tick 结束；跳过条件 `:243`
- **修法**：`invokeLater` 包 try/catch，失败时回滚 `framePending.set(false)` 与 `lastDispatchedDueNanos = Long.MIN_VALUE`，并记一行 warn
- **风险**：低（只在异常路径生效，正常路径零变化）

#### P-6 探针验收的是生产不用的并行实现（四-G「假通过」变种）

- **现象**：`VBlock` 7 项全绿，证明"块选区与多光标的目标收集正确"
- **根因**：它调的是 `CaretAnimator.collectTrailTargets()`，而该方法**生产零调用** ——
  真正的实现在 `refreshTarget():1408-1670` 里内联展开，两者早已分家
- **证据**：`collectTrailTargets` 全仓 src 引用 = 1（仅声明）；`VBlock.java:92` 是唯一调用者
- **修法**：要么把 `refreshTarget` 改回调它（并让探针随之有效），要么删它 + 改 `VBlock` 断言真实路径
- **风险**：改生产就中等；删探针方向需按纪律"改方向不删断言"

### Medium

| # | 问题 | 证据 | 修法 |
|---|---|---|---|
| P-7 | `slope < 0` 被 clamp 成 0（纯矩形），与 javadoc「用 k 推导」相反 | `NeovideCursorSettings.java:557` vs 注释 `:515`、`:581`；实测 §一 b | 改注释（行为已成事实），或把 `applySlopeOverlay` 加 `slope<0 → return base` 分支 |
| P-8 | `applySlopeOverlay(base, 100)` 返回入参同一实例 | `:558-559`；实测 `out100 == base` 为 true | 加 `.clone()`，或把契约写进 javadoc |
| P-9 | `onSpinWait` 忙等补偿是死分支（Windows 上一次不命中） | 实测 799 sleep / **0 spin** / 800 帧 | 删掉该分支改注释，或保留但注明"仅非 Windows 时钟粒度下生效" |
| P-10 | `hasQuad` 与 `quadCount>0` 冗余等价 | `CaretTrailPanel:70/515/543/571/707` 全同步 | 删字段，改判 `quadCount > 0` |
| P-11 | `drawQuadGlow` 的死参数 `ignoredPath` | `:871` 签名，体内零引用 | 删参数 |
| P-12 | override 整块 175 行死代码 | `CursorPresets.java:380-555`，src 引用 0 | 整块删除 + 3 个死访问器 |
| P-13 | `CursorDiag.configChanged` / `swallowed` 零调用 | 全仓 0 调用 | 要么接进 `catch` 块（能兑现"异常不许静默"），要么删 |
| P-14 | `animationResetThreshold` / `cursorWidth` 生产零读取 | `NeovideConfig:190/241` | D0 冻结，只能记录 |
| P-15 | `collectTrailTargets` / `quadCount()` / `updateCorners()` 生产零调用 | §一 g | 见 P-6 与 P-12 |

### Low

| # | 问题 | 证据 |
|---|---|---|
| P-16 | `activeDelayNsForTarget` 整数截断 -0.2% | 实测 143.8 vs 144；`:441` |
| P-17 | `idxScratch` 是 `Integer[]`，注释却说"避免装箱" | `SpringTrail:270` vs `:290` |
| P-18 | `__unusedLastVisualLine` / `lastVisualColumn` 遗留 | §一 g #1 #2 |
| P-19 | 单帧间隔 ±2ms 抖动（125fps 档 6.0~10.0ms） | 实测表 §一 d；根因 `DUE_TOLERANCE_NANOS = 1ms` |

---

## 三、优化方案（按 收益 ÷ 风险 排序）

### 方案 1：清理 override 死块 + 3 个死访问器 + 死参数（收益高 / 风险极低）

**改什么**

- 删 `CursorPresets.java:380-555`（override 机制 175 行，11 个符号）
- 删 `tailOpacityTicksValue()` `:205` /`typingSmoothnessTicks()` `:224` /`stretchRatioTicks()` `:239`
- 删 `drawQuadGlow` 的 `ignoredPath` 参数（`CaretTrailPanel:871` + 调用点 `:776`）
- 删 `CaretTrailPanel.hasQuad`（改判 `quadCount > 0`）

**为什么**：这 175 行是 0.9.8 的设计，已被 `CustomPresets` 完全取代。
它含 NaN 检查、边界 clamp、可逆序列化 —— 精心写满，**一次都没跑过**。
留着它的代价不是行数，是"下一个人以为改预设要走这里"。

**预期收益**：生产代码 -180 行；`CursorPresets` 从 639 行降到 ~460 行。

**风险**：极低。生产引用数 0，删了编译器就能证明。
唯一要动的是 `NeovideCursorConfigurable:2256/2698` 两处 javadoc 引用（`{@link}` 会断，改成纯文本）。

**验证**：`build-v-probes.ps1` 全量跑，**失败数必须与基线一字不差**（仍是那 10 个）。

---

### 方案 2：修 `invokeLater` 活锁（收益中高 / 风险低）

**改什么**：`RenderScheduler.java:247-254`

```java
if (!animator.framePending.compareAndSet(false, true)) continue;
animator.lastDispatchedDueNanos = due;
posted = true;
try {
    SwingUtilities.invokeLater(animator::runTick);
} catch (Throwable t) {
    // 回滚：否则这个 animator 永久活锁（framePending 恒 true、due 恒相等）
    animator.framePending.set(false);
    animator.lastDispatchedDueNanos = Long.MIN_VALUE;
    posted = false;
    LOG.warn("neovide-cursor: invokeLater 失败，已回滚投递状态", t);
}
```

**为什么**：现状下这一次异常会让该编辑器的光标**永久不动且无日志**。
`RenderScheduler:144-147` 的注释已经承认"EDT shutdown 期间可能抛"，但只处理了线程级死亡。

**预期收益**：消除一类"光标莫名不响应、只能重启 IDE"的故障。

**风险**：低。异常路径零成本；正常路径只多一个 try 块（JIT 会消除）。

**验证**：新探针 `VDispatchRollback`：
1. 反射构造 animator，手工把 `framePending` 置 true、`lastDispatchedDueNanos` 置某值
2. 用一个必然抛异常的 `Runnable` 替身（或直接测回滚静态方法）
3. 断言回滚后 `framePending == false` 且 `lastDispatchedDueNanos == Long.MIN_VALUE`
4. **变异测试**：删掉 catch 里的回滚 → 探针必须红

---

### 方案 3：拆开 `rgbGradient` 的双职责（收益高 / 风险中）

**改什么**：

- 删 `CursorPresetApplier.java:162`、`NeovideCursorConfigurable.java:1773`、`:2101` 三处 `= p.rgbWash()` 写入
- 「彩虹」状态一律由 `matchColorPreset(s).rgbWash()` 反推（读路径已如此，`CaretAnimator:2259`）
- `rgbGradient` 退回纯"双拼开关"语义，只有用户在光标组勾选时才变

**为什么**：这是纪律四-D 的活标本。用户手动开双拼后点颜色预设就被关掉 —— 是 bug，不是设计。

**预期收益**：消掉一个用户可复现的静默覆盖；`rgbGradient` 语义单一。

**风险**：**中**。① 在颜色域，用户明确要求"先不碰"；② 会改变"选彩虹时双拼自动关"的现有观感
（虽然实际不会同时触发，因为绘制侧 `cursorRgbWash` 优先）。

**验证**：`VRainbowSep` 已有 6 项断言覆盖三条互斥路径；
新增 `VDualTonePersist`：开双拼 → 选"薄荷" → 断言 `rgbGradient` 仍为 true。

> **这条需要用户点头**，因为落在颜色域。

---

### 方案 4：块选区改用 offset 差算 `charsMoved`（收益中 / 风险中）

**改什么**：`CaretAnimator.updateBlockCharsMoved()` `:327-345`

```java
// 现在：return -1（永远走像素兜底，阈值随光标宽度漂移）
// 改成：按行号缓存上一帧 offset，返回 |差|
private final java.util.Map<Integer,Integer> lastBlockOffsets = new java.util.HashMap<>();
private int updateBlockCharsMoved(int lineNo, int caretOffset) {
    if (caretOffset < 0) return -1;
    Integer prev = lastBlockOffsets.put(lineNo, caretOffset);
    return (prev == null) ? -1 : Math.abs(caretOffset - prev);
}
```

**为什么**：块选区语义上就是"整体移动 N 个字符"，与普通光标同一套判据才对。
现在它靠像素，而像素除以 `cursorWidth`（用户可调 1–5px）—— 用户把光标调粗，短跳阈值跟着翻倍。

**预期收益**：块选区与普通光标的加速度判据统一；消除"光标宽度影响短跳阈值"的隐式耦合。

**风险**：**中**。会改变块选区手感（位移 17px~48px 这段现在会变成短跳而不是跨行三档）。

**验证**：新探针 `VBlockCharsMoved`：
1. 构造 5 行块选区，逐帧模拟 `Ctrl+Shift+→`
2. 断言五行拿到的 `charsMoved` **完全相等**（现在靠"共享一个值"保证，改后靠"每行 offset 差相同"保证）
3. 断言 `cursorWidthPx` 从 2 改到 5 时，分支判定**不变**（现在会变）

**这条也需要用户点头** —— 会改观感。

---

### 方案 5：让 `CursorDiag` 的两个死方法活过来（收益中 / 风险极低）

**改什么**：

- `CursorDiag.swallowed`（`:400`）接进 `CaretAnimator` 的关键 `catch (Throwable ignored)`
  —— 至少 `refreshTarget` 的坐标转换、`applyConfigOnEdt` 的 setter 链
- `CursorDiag.configChanged`（`:376`）接进 `syncStateToConfig` 的字段回写

**为什么**：`WORK-DISCIPLINE.md` 四-C 的表里，`[config]` 和 `[error]` 是排查
「改了没生效」和「静默失败」的主力前缀 —— **它们从来没输出过。**
全仓 57 处 `catch (Throwable ignored)`，一处日志都没有。

**预期收益**：诊断日志从 8 条前缀里 2 条是死变成 10 条全活。
这类"承诺了但没接线"和 `targetFps` 死字段是同一类（四-E）。

**风险**：极低。都在 `enabled()` 门控内，关着就是零成本。

**验证**：`VSwitchKeysGap` 已经是"零断言也会 PASS"的反面教材（`PASS 0，FAIL 0` ⇒ exit 0）。
新增 `VDiagWiring`：源码扫描断言 `CaretAnimator.java` 里存在 `CursorDiag.swallowed(` 调用，
且 `NeovideCursorSettings.java` 里存在 `CursorDiag.configChanged(`。
**变异测试**：删掉调用 → 探针红。

---

## 四、门禁结果（本次实跑）

### D0：`core/NeovideConfig.java` 零 diff

```
git diff --exit-code -- src/main/java/.../core/NeovideConfig.java
D0_EXIT=0        ← PASS
```

### 全量探针（修复脚本后第一次真正跑通）

```
==> javac 源码树（34 个 .java）
==> javac V 探针（88 个）        ← 修复前：ParseError，一行都没跑过
...
V-PROBES RESULT: FAILED（9 个探针未通过）
GATE_EXIT=1

探针 85 个 / PASS 864 项 / FAIL 32 项 / exit0 75 个 / 非零退出 10 个
（脚本的 $informational 把 VRegr2 / VOrder / VOrtho 排除在通过门之外，
  所以"未通过 9 个"= 10 个非零退出 − 被豁免的 VRegr2。）
```

> **口径提醒**：脚本报的"9 个"不是"只有 9 个红"，而是"9 个计入通过门"。
> 实际 FAIL 断言共 **32 条**，分布在 10 个探针里。

| 探针 | FAIL | 与旧基线对比 |
|---|---|---|
| `VDefects` | 6 | 已知（`VUi` 量程表不同步） |
| `VSnap` | 10 | 已知 |
| `VRegr2` | 2 | 已知 |
| `VReset2` | 2 | 已知 |
| `VSepBound` | 7 | 已知 |
| `VSleep` | 1 | 已知 |
| `VTools` | 1 | 已知 |
| `VHints` | 1 | 已知 |
| `VCustom` | 1 | 已知 |
| **`VReset`** | **1** | **新增在门禁内**（它从没进过清单，见下） |

**10 个非零退出 = 9 个已知失败 + 1 个新纳入的 `VReset`。**

前 9 个与 `NEXT-STEPS.md` §四列的名单**逐个对上**（`VSnap`/`VDefects`/`VRegr2`/`VReset2`/
`VSepBound`/`VTools`/`VHints`/`VCustom`/`VSleep`），说明那 9 条"过期断言"的判断是对的。
这是本项目**第一次有实测证据支持**这个结论 —— 在此之前它只是文档里的一句话。

**`VReset` 的 R-16 是新纳入门禁的失败**，独立核实过：

```
FAIL  R-16 点「性能诊断」不参与 isModified（源码注释如此承诺）   [isModified false -> true]
```

根因：`NeovideCursorConfigurable:1341` 的 `bindCheck(row, () -> paintDiag, v -> paintDiag = v)`
把 `paintDiag` 注册成了 **DialogPanel 的绑定控件**，而 `isModified():2664` 的
`super.isModified()` 会遍历所有绑定控件 —— 于是它必然参与判脏。
`:1326` 的注释"不参与 isModified()"与实现相反。

**判定**：这是**真实存在的产品级不一致**（注释撒谎），不是过期断言。
按纪律「不许改成警告绕过」，放在门禁内 —— 红就是红。修法见文末待确认项。

---

## 五、待用户确认

以下 4 条超出"技术上该怎么改"，需要用户拍板：

### 1. `rgbGradient` 双职责要不要拆？（方案 3）

落在**颜色域**，而用户明确要求"先不碰"。
- 选项 A：拆（修 P-3 的用户可复现 bug，但动颜色域）
- 选项 B：不拆，只在三处写入旁加注释说明语义冲突
- 选项 C：等颜色域定型后的下一轮

### 2. 块选区短跳判据要不要改？（方案 4）

会改变块选区手感（位移 17–48px 的区间从"跨行三档"变"短跳"）。
- 选项 A：改（统一判据，消除光标宽度耦合）
- 选项 B：不改，只把 `shortMoveThreshold` 的隐含耦合写进文档

### 3. `VReset` R-16 怎么定性？

- 选项 A：认它是**产品 bug**（注释撒谎），改注释 + 改断言方向
- 选项 B：认它是**设计如此**（性能诊断本来就算改动），改注释
- 当前门禁里它是红的，需要先定性才能修

### 4. 死代码清理范围

- `CursorPresets` override 块 175 行 + 3 个访问器（方案 1）—— 建议删，需确认
- `CursorAnimator` 的 4 个死字段 + `glowStrengthFor` + `collectTrailTargets`
  —— `collectTrailTargets` 删了要同时改 `VBlock`，按纪律"改方向不删断言"
- `NeovideCursorConfigurable` 的 `eqHex` / `eqText` —— 零引用但 javadoc 写得很认真，
  可能是"准备用但忘了接"，需要确认

---

## 附：核实方法

### 用到的工具与产物

| 文件 | 用途 |
|---|---|
| `build/ZzAuditCheck.java` | 直接调生产 `deriveTrailFactors` 六个边界组合，验证 clamp / NaN / 别名 |
| `build/ZzBlockBranch.java` | 直接调生产 `TrailCorner.jump()`，对比块选区与普通光标的分支 |
| `build/ZzSchedFaithful.java` | 忠实复刻 `RenderScheduler.loop` 计时结构，统计 sleep/忙等/不等待分支命中数 |
| `build/ZzSchedDispatch.java` | 复刻 loop + dispatchDue 联合行为，测真实派发时刻与提前量 |
| `build/ZzSpinCost2.java` | 统计忙等分支在四个帧率档的实际命中次数 |
| `build/run-probes2.ps1` | 绕过坏脚本，直连 java 跑全部探针（用于交叉验证） |
| `build/gate-final.log` | 修复后门禁的完整输出 |

### 三条口径（按四-G）

1. **数值类结论必须调生产方法**，不许复刻。`ZzAuditCheck` / `ZzBlockBranch` 调的都是生产类。
2. **调度类结论必须复刻到循环结构**，因为生产类是包私有的、且依赖 EDT。
   复刻时逐行对照 `RenderScheduler.loop` 与 `CaretAnimator.scheduleNextDue`，
   连 `DUE_TOLERANCE_NANOS` 的 1ms 容差都照抄。
3. **性能数字必须标口径**。本文所有 fps 都注明"复刻循环、非端到端"。
   端到端 fps 只能来自诊断日志，本文没有端到端结论。

### 死代码扫描方法

1. 分块读全部 34 个生产文件（read 单次上限 2000 行）
2. 剥离 `//` 行注释与 `/* */` 块注释
3. 对每个"字段声明 / 方法声明"统计标识符出现次数，= 1 即只有声明
4. **再逐个用 grep 在全仓（src + tools）复核** —— 关键：有些符号在 javadoc 的 `{@link}` 里出现，
   那是"被引用"但不是"被调用"。第 4 步区分了这两者。

### 本次改动的完整范围

**只改了 1 个文件**：`tools/settings-probe/build-v-probes.ps1`（补右括号 + `@argfile` + VReset 入表）。

**未改任何生产代码**。`core/NeovideConfig.java` 零 diff（D0 PASS）。

> ⚠️ 工作区当前另有**并发改动**（非本次审计产生）：
> `CursorPresets.java` / `CaretAnimator.java` / `CaretTrailPanel.java` / `CursorPresetApplier.java`
> 被另一路修改，内容与「彩虹预设 HEX + `matchColorPreset` 判据」有关。
> 本文对 P-3 的描述基于**当时读到的代码**（三处写入仍在），如那一路已处理请以最新代码为准。
