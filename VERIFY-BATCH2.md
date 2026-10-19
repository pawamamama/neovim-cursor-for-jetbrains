# VERIFY-BATCH2.md —— 批次2 独立验证报告

> **验证方**：独立验证工程师（未参与实现；实现由 captain 完成）
> **验证对象**：`D:\deepseekwork\neovim-cursor-pr` 工作区未提交改动（相对 `git HEAD = 9f8ccb7`）
> **交付产物**：`dist\jetbrains-neovide-cursor-0.9.2-imetime-fade.jar`（43,871 B）
> **契约**：`FIX-BATCH2.md`（FROZEN）
> **验证方式**：全部实跑。包括契约要求的两个脚本、离线构建、jar 解包逐文件比对、
> 以及**自建的 8 个 Java 探针**（反射驱动真实编译产物 `build\classes`，不重写被测逻辑）。
> **探针源码 + 原始输出**：`diag\verify-batch2-raw\`（可原样复跑）
> **未修改 src/**、未安装 jar、未重启 IDEA、未删除/移动任何用户文件。

---

## 1. 结论

### 1.1 证据支持可断言（已实测）

| 编号 | 断言 | 强度 |
|---|---|---|
| C-1 | parity 三份 harness 的 base 与 new **逐字节相同**，且用 `9f8ccb7`（真实 HEAD）作基线复核也相同 | **确定** |
| C-2 | 19 项常量审计 **DIFF = 1**，唯一 DIFF 是 `hideNativeCaret`（`false → true`），其余 18 项 SAME | **确定** |
| C-3 | 调度语义未被破坏：`invokeLater(` 调用点 = 1、per-animator 循环内、`new Thread` = 1 | **确定** |
| C-4 | F1-a 已正确落地：`caretPositionChanged()` 里 `forceResync = true` 在 `wakeUp()` 之前，两者都在 | **确定** |
| C-5 | F1-b 的 5 条边界（首次 / forceResync / 正常移动 / IME 中间态 / 组合结束）**逐条满足契约** | **确定** |
| C-6 | F1-b **未新增** `getOffset()` 调用：非快速路径帧恒为 1 次；快速路径帧为 0 次（P0-5 满足） | **确定** |
| C-7 | `charsMoved` 计算与基线**语义等价**（含 `-1` 降级路径） | **确定** |
| C-8 | F2 已按方案 B 实现：**光标本体不淡**（无 `AlphaComposite`，`fillPolygon` 未受任何 alpha 影响） | **确定** |
| C-9 | F3 已落地：`hideNativeCaret = true`、javadoc 写明有意偏离踩坑 #19 及理由、菜单开关仍在、`alwaysHighFps` 仍为 `true` | **确定** |
| C-10 | `overlayFadeOut=false` 时，辉光包络与 `9f8ccb7` 基线**逐位相同**（450 帧 × 6 场景，0 处不同） | **确定** |
| C-11 | 交付 jar 与我重新构建的 jar **20/20 个文件内容完全一致**（整包 SHA 不同仅因 `jar --update` 写时间戳） | **确定** |
| C-12 | 构建 4/4 步通过，`_verify-b2.jar` 与交付 jar 同为 43,871 B | **确定** |

### 1.2 证据支持的反面结论（本批最重的两条）

| 编号 | 断言 | 严重性 |
|---|---|---|
| **F-1** | **F2 的辉光淡出在像素上完全不可见 —— 功能是 no-op。** 在全部 6 个运动场景、450 帧内，`glowFade < 1` **从未**与 `glowStrength > 0` 同时出现。淡出窗口恒定比辉光归零时刻晚 **56ms**。 | **高** |
| **F-2** | **`fading` 判据写反了终止条件，导致 `tickSpring()` 恒返回 `true`，调度器永不降频。** 判据是 `fade < 1f`，而淡出结束后 `fade == 0f`，`0 < 1` 恒真。实测 695/700 帧返回 true；`currentDelayMs` 从 8ms 起**永不回落**（对照 0.9.1 为 600ms）。 | **高** |
| **F-3** | F1-b 的拦截**不是「跳过一帧」而是「无限期冻结」**。在「offset 不变、视觉列合法改变」的场景（折叠展开、inlay 提示出现、制表符宽度变化）下，连续 200 帧全部被拦，光标像素位置停在旧值（实测偏差 30px ≈ 1.5 字符），直到下一次 caret 事件才自愈。 | **中** |
| **F-4** | 契约 §6.3 要求新增 `build/rollback-0.9.2.ps1` —— **未交付**（仓库里只有 `rollback-0.9.1.ps1`，且它实际回退到 0.9.0）。 | **低** |
| **F-5** | `glowFade()` 在 `lastAnimatingNanos == 0L` 时返回 `1f`，而契约 §3.3 原文要求 `0f`。行为上无差异（此时 `glowStrength` 亦为 0），但属**偏离冻结契约**。 | **低** |

### 1.3 不能推出（无法自动化验证）

见 §8。真机 IME 行为、淡出主观观感、Tools 菜单实际勾选状态，均**未验证**。

---

## 2. parity 与常量审计（我自己跑的原始输出）

### 2.1 run-parity.ps1

命令（契约给的原文）：

```
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1"
```

原始输出（关键段）：

```
==> 基线 java 文件数 = 13
==> 新版 java 文件数 = 15
==> idea-libs jar 数 = 381，classpath 长度 = 27214
base             PhysicsParity    行数=72    SHA256=0A4FDDE69C3EBC32DF23AEF17D8EAFF15CDA0B25E5E04152B7076AB8C1003883
base             GeometryParity   行数=25    SHA256=4057AFA03E768C842990FD4B7B4B6D5AF7ED46830A848E8ED99900FFFB526B44
base             TrailParityTest  行数=158   SHA256=409C5728506FF36D648559D764AB288E7B55753DA92D6251BD44E81F756A08C7
new              PhysicsParity    行数=72    SHA256=0A4FDDE69C3EBC32DF23AEF17D8EAFF15CDA0B25E5E04152B7076AB8C1003883
new              GeometryParity   行数=25    SHA256=4057AFA03E768C842990FD4B7B4B6D5AF7ED46830A848E8ED99900FFFB526B44
new              TrailParityTest  行数=158   SHA256=409C5728506FF36D648559D764AB288E7B55753DA92D6251BD44E81F756A08C7
PhysicsParity    PARITY OK   （diff 为空）
GeometryParity   PARITY OK   （diff 为空）
TrailParityTest  PARITY OK   （diff 为空）
==> ALL PARITY OK
```

**我自己算出的三份哈希**（与契约给出的前 8 位/后 4 位吻合，但这是我自己跑出来的）：

| harness | SHA256（完整） |
|---|---|
| PhysicsParity | `0A4FDDE69C3EBC32DF23AEF17D8EAFF15CDA0B25E5E04152B7076AB8C1003883` |
| GeometryParity | `4057AFA03E768C842990FD4B7B4B6D5AF7ED46830A848E8ED99900FFFB526B44` |
| TrailParityTest | `409C5728506FF36D648559D764AB288E7B55753DA92D6251BD44E81F756A08C7` |

**额外加做的一步**：`run-parity.ps1` 的 `-BaseRef` 默认是 `321b0a0`（批次1 的基线），
而本批真实基线是 `9f8ccb7`。我改用真实 HEAD 复核了一遍：

```
pwsh ... -BaseRef 9f8ccb7 -Work %TEMP%\nc-b2-parity-head
```

结果：**三份哈希与上面完全相同，ALL PARITY OK**。说明批次1 对 `core` 包的改动未影响
`TrailCorner`/`DampedSpring` 的数值行为，本批亦未误改 core 包。

> 判定依据（契约 §6.1）：parity harness 直接驱动 `TrailCorner`/`DampedSpring`，不经过
> `refreshTarget`，因此 F1-b 改「何时重算」本就不该影响它。**一致 → 未误改 core，通过。**

### 2.2 run-audit.ps1

命令：

```
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-audit.ps1"
```

原始输出（A 段完整）：

```
========== A. 19 项观感常量逐项对照（基线 vs 新版） ==========
SAME     glowLayers = [0-9]+                | public int glowLayers = 8;
SAME     glowOpacity = [0-9.]+f             | public float glowOpacity = 0.55f;
SAME     shadowBlurFactor = [0-9.]+f        | public float shadowBlurFactor = 0.2f;
SAME     trailFactors = \{[^}]+\}           | public float[] trailFactors = {1.0f, 0.72f, 0.42f, 0.22f};
SAME     float animationLength = [0-9.]+f   | public float animationLength = 0.1f;
SAME     shortAnimationLength = [0-9.]+f    | public float shortAnimationLength = 0.11f;
SAME     trailSize = [0-9.]+f               | public float trailSize = 1f;
SAME     typingMaxChars = [0-9.]+f          | public float typingMaxChars = 3f;
SAME     cursorWidth = [0-9.]+f             | public float cursorWidth = 2f;
SAME     maxTrailDistanceFactor = [0-9.]+f  | public float maxTrailDistanceFactor = 60f;
SAME     animationResetThreshold = [0-9.]+f | public float animationResetThreshold = 0.09f;
SAME     afterimageLifetime = [0-9.]+f      | public float afterimageLifetime = 0.22f;
SAME     alwaysHighFps = (true|false)       | public boolean alwaysHighFps = true;
DIFF     hideNativeCaret = (true|false)     | public boolean hideNativeCaret = true;
         基线 = public boolean hideNativeCaret = false;
SAME     int ACTIVE_DELAY_MS = [0-9]+       | static final int ACTIVE_DELAY_MS = 8;
SAME     int IDLE_DELAY_MS = [0-9]+         | private static final int IDLE_DELAY_MS = 120;
SAME     int IDLE_UNFOCUSED_DELAY_MS = [0-9]+ | private static final int IDLE_UNFOCUSED_DELAY_MS = 600;
SAME     int MAX_ADAPTIVE_DELAY_MS = [0-9]+ | private static final int MAX_ADAPTIVE_DELAY_MS = 40;
SAME     MAX_DT = [0-9.]+ / [0-9]+          | private static final double MAX_DT = 1.0 / 20;
---- 19 项中 DIFF 数 = 1 ----
```

**DIFF 项清单（只有一项）**：`hideNativeCaret`：基线 `false` → 新版 `true`。
**这正是 F3 要改的那一项，符合预期；没有其他 DIFF。**

> **口径说明（我自己补的一步验证）**：`run-audit.ps1` 的 `-BaseRef` 默认也是 `321b0a0`，
> 而本批基线是 `9f8ccb7`。我逐项比对了这 19 项在 `321b0a0` 与 `9f8ccb7` 之间是否一致：
> **19 项全部 SAME**（含 `alwaysHighFps=true`、`hideNativeCaret=false`）。
> 因此「DIFF=1」这个结论在「基线=真实 HEAD」的口径下同样成立。

### 2.3 调度语义（审计 E 段，我自己复跑的关键行）

```
E-1  RenderScheduler 中 invokeLater( 出现次数（含注释） = 1
     invokeLater(RenderScheduler:: 命中 = 0
     runDue|FRAME_PENDING 命中 = 0
E-2  157: private static boolean dispatchDue() {
     160:     for (CaretAnimator animator : ANIMATORS) {
     182:         SwingUtilities.invokeLater(animator::runTick);
E-3  全仓库 src/** 中 new Thread 命中 = 1
     ...\ui\RenderScheduler.java:70:  Thread t = new Thread(RenderScheduler::loop, THREAD_NAME);
```

`invokeLater(animator::runTick)` 仍在 per-animator 循环体内，Runnable 体内无 for/forEach
→ **P0-1 未被破坏**。

### 2.4 数值白名单核对（反射读真实字段，不是读源码文本）

我用探针直接 `new NeovideConfig()` 读字段，结果：

```
hideNativeCaret            = true          ← F3 预期
alwaysHighFps              = true          ← 未被顺手改
overlayFadeOut             = true          ← §5.1 允许新增
cursorDisappearDelay       = 50            ← §5.1 允许恢复
cursorFadeOutDuration      = 0.075         ← §5.1 允许恢复
glowLayers                 = 8
glowOpacity                = 0.55
shadowBlurFactor           = 0.2
animationLength            = 0.1
shortAnimationLength       = 0.11
trailSize                  = 1.0
typingMaxChars             = 3.0
cursorWidth                = 2.0
maxTrailDistanceFactor     = 60.0
useShadow                  = true
tailOpacity                = 1.0
trailFactors               = [1.0, 0.72, 0.42, 0.22]
trailMode                  = SPRING
```

**§5.1 白名单外无任何变动。**

---

## 3. F1-b 重点审查（本批最大风险）

### 3.1 代码位置与结构（`CaretAnimator.refreshTarget()`，L825–938）

```
L827  VisualPosition vp = editor.getCaretModel().getVisualPosition();
L833  if (!forceResync && vp.line == lastVisualLine && vp.column == lastVisualColumn) return false;   ← 快速路径
L852  int caretOffset = -1;
L853  try { caretOffset = editor.getCaretModel().getOffset(); } catch (Throwable ignored) {}
L859  if (!forceResync && lastCaretOffset >= 0 && caretOffset == lastCaretOffset
L862                        && vp.column != lastVisualColumn) { return false; }        ← F1-b
L867  forceResync = false;
L871  long startedNanos = System.nanoTime();
L873  Point p = editor.visualPositionToXY(vp);
L874  lastVisualLine = vp.line;  L875 lastVisualColumn = vp.column;
L892  int charsMoved = -1;
L893  if (caretOffset >= 0) { if (lastCaretOffset >= 0) charsMoved = |caretOffset - lastCaretOffset|;
L898                          lastCaretOffset = caretOffset; }
```

位置符合契约 §2.3：**在快速路径之后、`visualPositionToXY` 之前**。
`forceResync` 只在 F1-b 之后才清零 → forceResync 帧不会被 F1-b 拦。

### 3.2 `getOffset()` 调用点计数（契约 P0-5）

**在 `refreshTarget()` 里，`getOffset()` 只有 1 个调用点（L854）**，且在 `try` 里只读一次。
基线版本同样是 1 个调用点（当时在 L~G 段供 `charsMoved` 用）。**新代码复用了这一次结果**。

实测（探针 A7/A8，驱动真实 `refreshTarget`）：

```
A7 快速路径命中(vp未变) -> refreshTarget=false (期望 false)  getOffset次数=0 (期望 0)  getVisualPosition次数=1
A8 5 个非快速路径帧各自的 getOffset 调用次数 = 1 1 1 1 1  (期望全 1)
```

→ **P0-5 满足。每帧没有新增调用；快速路径帧反而仍未调用。**

### 3.3 边界覆盖表

探针 `B2Verify` 直接 `method.setAccessible` 调真实 `refreshTarget()`，用 Proxy 伪造
`Editor`/`CaretModel` 注入 `vp`/`offset`/`visualPositionToXY`。原始输出：

| # | 场景 | 契约期望 | 实际代码行为（实测） | 是否满足 |
|---|---|---|---|---|
| A1 | 首次调用（`lastCaretOffset = -1`），vp `(0,0)→(0,5)`，offset=42 | 不得误判，正常首次定位 | `refreshTarget=true`；`lastVisualColumn=5`、`lastCaretOffset=42`；getOffset=1 | ✅ |
| A2 | `forceResync=true`（窗口尺寸变化），offset 不变(42)、列 `4→7` | **优先放行**，不得被 F1-b 拦 | `refreshTarget=true`；`lastVisualColumn=7`、`lastVisualLine=3` | ✅ |
| A3 | 正常移动：offset `42→44`、列 `7→9` | 正常放行 | `refreshTarget=true`；`lastCaretOffset=44`；getOffset=1 | ✅ |
| A4 | IME 组合中：offset 不变(44)、列 `9→11` | 跳过本帧，**不更新** `lastVisualColumn` | `refreshTarget=false`；`lastVisualColumn=9`（未更新）；`centerX` 201.0→201.0（未变） | ✅ |
| A4b | 同一中间态连续 100 帧（列 11~13 抖动，offset 恒 44） | （契约未明说） | **被拦 100/100 帧**，`lastVisualColumn` 仍为 9 | ⚠️ 见 §3.4 |
| A5 | 组合结束：offset `44→46`、列 `11→12` | 正常放行，落到正确位置 | `refreshTarget=true`；`lastVisualColumn=12`；`lastCaretOffset=46` | ✅ |
| A6 | `getOffset()` 抛异常（降级路径） | 不崩、不污染状态 | `refreshTarget=true`（放行）；`lastCaretOffset=46`（未被写成 -1）；`lastVisualColumn=0` 正常更新 | ✅ |
| A7 | 快速路径命中 | `getOffset()` 不应被调用 | `false`；getOffset=0、getVisualPosition=1 | ✅ |
| A8 | 5 个非快速路径帧 | 每帧 `getOffset()` 恰好 1 次 | `1 1 1 1 1` | ✅ |
| A9 | F1-a：调用 `caretPositionChanged()` | `forceResync` 置位，`wakeUp()` 保留 | 捕获到 listener = `CaretAnimator`；调用后 `forceResync=true`；源码 L530–531 `forceResync = true; wakeUp();` | ✅ |

A6 原始行（修正版探针 `B2Final` 第 1 段）：

```
初始：centerX=301.0 lastVisualColumn=12 lastCaretOffset=46
getOffset 抛异常 + vp/像素都变 -> refreshTarget=true (期望 true)  centerX=401.0 (期望 401.0)
  lastCaretOffset=46 (期望仍 46)  lastVisualColumn=0 (期望 0)
```

### 3.4 关键风险：判据会不会在**正常场景**误拦？（本批最大风险）

#### 3.4.1 先纠正一个认知偏差：它不是「跳过一帧」，是「无限期冻结」

契约 §2.3 的措辞是「本帧 `return false`」，暗示只丢一帧。**实际行为不是这样。**
原因：被拦时**不更新** `lastVisualColumn`（这是契约要求的），于是 `vp.column != lastVisualColumn`
在下一帧仍然成立，而 `offset` 仍然没变 → 条件永久为真。

实测（`B2Probe` G 段，真实 `refreshTarget`）：

```
G0 初始：centerX=101.0 centerY=80.0 lastVisualColumn=5
G1 offset 不变、列 5→9（合法重排）后连续 200 帧：被拦截 200/200，centerX 仍为 101.0（正确应为 133.0）
G2 用户按一次方向键（offset 200→204）后：refreshTarget=true，centerX=133.0（已修正）
G3 forceResync=true（面板尺寸变化 / caret 事件 / 模式切换）时：refreshTarget=true，centerX=157.0
```

**偏差 30px ≈ 1.5 个字符宽**，且停留时间 = 用户下一次导致 caret 事件的操作之前。
默认档 `hideNativeCaret=true` 时**覆盖层就是肉眼看到的光标**，所以这是「光标画在错位置」。

#### 3.4.2 哪些正常场景会出现「offset 不变但视觉列变」

我把可能场景逐条过了一遍（**基于 IntelliJ API 语义推理，未在真机复现，见 §8**）：

| 场景 | offset 是否变 | 视觉列是否变 | 是否误拦 | 严重性判断 |
|---|---|---|---|---|
| **IME 组合态**（本批目标） | 否 | 是 | 拦 = 想要的行为 | — |
| **代码折叠展开/折叠**（折叠区在光标所在行、光标之后） | 否 | **是** | **会拦** | 中。折叠不改面板尺寸，`forceResync` 不会被 tick 里的尺寸检查触发 |
| **Inlay 提示出现/消失**（参数名提示、类型提示、Code Vision、延迟出现的 inlay） | 否 | **是**（inlay 插在光标前则列右移） | **会拦** | **中高**。这是 IDE 里很常见的事件，且往往发生在「停手后」 |
| **制表符宽度 / 字体设置变更** | 否 | 是（tab 展开列数变） | 会拦 | 低（一次性，且用户此时通常会点别处） |
| **软换行开关切换** | 否 | 是 | 会拦 | 低（同上） |
| 软换行因**窗口缩放**重排 | 否 | 是 | **不拦**（面板尺寸变 → `forceResync=true`，L624–628） | 已被覆盖 |
| 在光标**之前**插入文本（自动导入、格式化缩进） | 是（绝对 offset 后移） | 是 | 不拦 | 安全 |
| 纯键盘移动 / 输入 / 删除 | 是 | 是 | 不拦 | 安全 |

**结论**：误拦风险**真实存在**，主要触发面是「折叠」与「inlay 提示」这两类
**不动 offset、不动面板尺寸、未必发 caret 事件**的视觉重排。

#### 3.4.3 为什么它最终会自愈

两条解除路径（都实测过）：

1. `caretPositionChanged()` 被 F1-a 置 `forceResync=true` → 下一帧放行（G2/G3、A2）。
2. 任何 offset 变化 → 条件不成立 → 放行（A5）。

所以最坏情况是「**在用户下一次按键/点击之前，光标停在错位置**」，不是永久损坏。
但契约 §2.3 的自我描述「至多一帧不掉」是不成立的。

#### 3.4.4 一个设计层面的隐患：F1-a 与 F1-b 是**互斥**的

- F1-a 在**每次** `caretPositionChanged` 时置 `forceResync = true`；
- F1-b 只在 `!forceResync` 时生效。

推论：**如果 IntelliJ 在 IME 组合期间也发 `caretPositionChanged`**（组合串增长时视觉位置变化，
平台很可能发事件），那 `forceResync` 每帧都为 true，**F1-b 永远不会触发** —— 它成了死代码。
反之若平台不发事件，F1-b 才会接管，同时 §3.4.2 的误拦风险全部生效。

这两种结局我**都无法在离线环境判定**（需要真实中文输入法 + 真机插桩）。
建议手测时专门区分：**F1-b 是否真的被走到过**（可在其 `return false` 前临时加一行 debug 日志观察）。

#### 3.4.5 建议的修法（若 captain 决定返修）

最小改动是给冻结加一个「有限次数」上界，既不破坏 IME 初衷，也不至于无限期停在错位置：

```java
// 新增字段：连续被 F1-b 拦掉的帧数
private int imeSkipFrames = 0;
// 判据里追加 && imeSkipFrames < MAX_IME_SKIP_FRAMES（例如 15 帧 ≈ 120ms）
// 被拦分支里 imeSkipFrames++；放行分支里 imeSkipFrames = 0;
```

或更保守：只在「编辑器当前确实有 IME 组合」时才启用（平台若有可用判据的话）。

### 3.5 `charsMoved` 是否仍正确

| 关注点 | 结论 |
|---|---|
| 是否改了几何/弹簧算法 | **否**。`update()/jump()/snapTo()` 一字未改（diff 中 `CaretAnimator` 的 `tickSpring` 只多了 `now` 参数与 F2 段） |
| `charsMoved` 是否复用同一 offset | 是，L893–898 直接用提前读到的 `caretOffset`；差值语义 `abs(caretOffset - lastCaretOffset)` 与基线字面等价 |
| `-1` 降级路径 | 与基线等价：`getOffset` 抛异常时 `caretOffset=-1` → 跳过赋值 → `charsMoved` 保持 `-1` → `TrailCorner.jump()` 走 `charsMoved < 0` 兼容分支（`shortMoveThreshold`）。**与基线行为相同**（基线也是 catch 里什么都不做） |
| 被 F1-b 拦掉的帧 | 不调用 `corner.setCharsMoved(...)`，但被拦帧 `charsMoved` 必为 0（offset 未变）且不发生 jump → 无影响 |
| 是否遗漏 `lastCaretOffset` 更新 | 只在 `caretOffset >= 0` 时更新，与基线一致 |

---

## 4. F2 审查（辉光淡出，方案 B）

### 4.1 「光标本体不淡」的证据（先确认方案 B 的前提成立）

| 检查 | 方法 | 结果 |
|---|---|---|
| `paintQuad()` 里 `fillPolygon` 是否受 alpha/composite 影响 | 读 `CaretTrailPanel.java` L227–235 | ❌ 不受影响。`g2.setColor(fillColor); g2.fillPolygon(polygon);` —— `fillColor` 的 alpha 来自 `NeovideColors.parse(config.tailColor, config.tailOpacity)`，与 `glowFade` 无关 |
| `paintComponent` 上是否有全局 `AlphaComposite` | 全仓 grep `AlphaComposite\|setComposite\|Composite` | **0 处实际调用**。唯一命中是 `CaretTrailPanel.java:98` 的一句 javadoc 注释 |
| `glowFade` 是否只乘在 `drawQuadGlow` 的 `peak` | grep `glowFade` | 只出现在 L102（字段）、L112（setter）、L276–277（`peak` 表达式）。**无第二处乘法** |
| `drawQuadGlow` 的几何算式是否被动 | git diff | 只改了 `peak` 表达式的最后一项，角点/外扩/层数/半序/`Math.pow` 反解**一字未改** |
| 残影模式是否被接入淡出 | `paintGhosts` | **未接入**（`glowFade` 未出现），符合 §3.2 要求 |

**→ 方案 B「只淡辉光、不淡光标本体」在代码结构上确实成立。**

### 4.2 `glowFade()` 逻辑逐条核对

`CaretAnimator.glowFade(long now)`（L1046–1063）实测（探针 B，真实方法）：

| 分支 | 契约 §3.3 要求 | 实现 | 实测输出 |
|---|---|---|---|
| 开关关闭 | `return 1f` | `if (!config.overlayFadeOut) return 1f;` | `B1 overlayFadeOut=false, +10s -> 1.000000` ✅ |
| `lastAnimatingNanos == 0L` | **`return 0f`** | `return 1f` | `B2 -> 1.000000` ⚠️ **偏离契约**（见 F-5） |
| delay 内 | `return 1f` | `if (sinceStop <= 0) return 1f;` | `+0/25/50ms -> 1.000000` ✅ |
| fade 期间线性下降 | `1 - sinceStop/fade` | 同 | `+60ms -> 0.866667`、`+87.5ms -> 0.500000`、`+100ms -> 0.333333` ✅ |
| 淡出结束 | `return 0f` | `if (fade <= 0 \|\| sinceStop >= fade) return 0f;` | `+125ms -> 0.000000` ✅ |
| `fade <= 0` 边界 | 0f | 同 | `B4a fade=0, +60ms -> 0.000000` ✅ |
| `delay < 0` 边界 | 立即进淡出 | 同 | `B4b delay=-100, +10ms -> 0.000000` ✅ |
| `setGlowFade` 截断 | 0~1 | 三元截断 | `-3 -> 0.0`、`9 -> 1.0`、`0.5 -> 0.5` ✅ |

数值对齐：`lastAnimatingNanos=t0`、`now=t0+50ms` 时 `glowFade=1.0`（`sinceStop=0` 走 `<=0`）；
`t0+125ms` 时 `0.0`。**数学上正确。**

### 4.3 `lastAnimatingNanos` 是否只在 `anyAnimating` 时更新

代码 L1019–1021：

```java
if (anyAnimating) {
    lastAnimatingNanos = now;
}
```

**是。** 位置在 `setGlowStrength(...)` 之后、`glowFade(now)` 之前，顺序正确（同一帧的 `anyAnimating`
能立刻生效，不会让首帧误判成「已停止」）。

### 4.4 返回值 `anyAnimating || fading` —— 这里有一个**高危 bug**

代码 L1025、L1035：

```java
boolean fading = fade < 1f && config.overlayFadeOut;
...
return anyAnimating || fading;
```

`glowFade()` 淡出结束后返回 **`0f`**。于是 `fade < 1f` → `0 < 1` → **恒真**。
`fading` 一旦变真就**永远为真**，`tickSpring()` 永远返回 `true`。

实测（`diag\verify-batch2-raw\06-B2Real.txt`，走真实 `tick()` 路径 + 真实 `System.nanoTime()`，
`alwaysHighFps=false` 以便观察降频）：

```
=== overlayFadeOut=true / alwaysHighFps=false ===
 帧   delay  glowStr     glowFade
  0      8  1.0         1.0
 12      8  0.1749456   1.0
 20      8  0.0         0.9068147
 30      8  0.0         0.0
 40      8  0.0         0.0
 50      8  0.0         0.0
 60 帧后：delay=8 glowFade=0.0 lastAnimatingNanos置位=true

=== overlayFadeOut=false / alwaysHighFps=false ===
 帧   delay  glowStr     glowFade
 20    600  0.0         1.0
 50    600  0.0         1.0
 60 帧后：delay=600 glowFade=1.0 lastAnimatingNanos置位=true
```

另一组（`05-B2Delay.txt`，注入时钟，700 帧 = 5.6s）：

```
=== overlayFadeOut=true ===
   帧700  t=5600ms tickSpring=true  glowFade=0.0  → computeNextDelay(active)=8ms
  700 帧内 tickSpring 返回 true=695，false=6，最后一次 true 在 t=5600ms

=== overlayFadeOut=false ===
   帧700  t=5600ms tickSpring=false glowFade=1.0  → computeNextDelay(active)=600ms
  700 帧内 tickSpring 返回 true=15，false=686，最后一次 true 在 t=112ms
```

**后果链**：

```
fading 恒 true
  → tickSpring() 恒 true
    → anyAnimating 变量恒 true
      → computeNextDelay(moved || anyAnimating) 收到 active=true
        → alwaysHighFps=false 时永不降到 IDLE_DELAY_MS(120) / IDLE_UNFOCUSED(600)
          → 每个打开过光标的编辑器，在第一次移动后永久 8~40ms 投递 invokeLater
      → recordFrameStats(now, true) 从"空闲时立即 return"变成"每帧都记账"
```

**触发面比看上去更大**：`NeovideCaretManager.restorePersistedSettings()` 里
`alwaysHighFps` 的读取默认值是 **`false`**

```java
CONFIG.alwaysHighFps = PropertiesComponent.getInstance()
        .getBoolean(PROP_ALWAYS_HIGH_FPS, false);   // ← 默认 false
```

（这一行在 `9f8ccb7` 就已存在，**不是本批引入**；但它决定了本批 bug 的实际影响面。）
即：**用户当前机器上持久化值恰好是 `constantHighFps=true`**（我在
`%APPDATA%\JetBrains\IntelliJIdea2026.1\options\other.xml` 里只看到
`"neovide.cursor.alwaysHighFps": "true"` 这一个 neovide 键），所以**这位用户看不到本 bug**；
而**任何干净安装 / 重置配置的用户**会拿到 `alwaysHighFps=false`，于是
**第一次移动光标之后，EDT 再也不休息**。

> 注意：`alwaysHighFps=true` 时 `computeNextDelay` 直接返回 `ACTIVE_DELAY_MS`，
> 所以本 bug 在默认持久化档下**不改变**调度行为 —— 这也解释了为什么手测很可能发现不了它。
> 但它仍然是契约 §3.4 第 2 条的直接违反（「淡出期间必须继续 tick」被实现成「永远继续 tick」）。

**正确写法**应是给 fading 一个上界，例如：

```java
// 只在"淡出进行中"（0 < fade < 1）继续 tick；fade == 0 表示已结束
boolean fading = config.overlayFadeOut && fade > 0f && fade < 1f;
```

### 4.5 数值分析：`glowStrength × glowFade` 叠加是否合理 —— **不合理，辉光淡出完全不可见**

这是本批最实质的问题。

**`glowStrength` 的定义**（L1007–1008）：

```java
panel.setGlowStrength((float) ((maxOffset - GLOW_FADE_START) / (GLOW_FADE_END - GLOW_FADE_START)));
// GLOW_FADE_START = 0.5, GLOW_FADE_END = 2.5  →  clamp 到 [0,1]
// maxOffset = 四角 spring 偏移的 hypot 最大值
```

**`anyAnimating` 的定义**（`TrailCorner.update()` L313）：任一角 `|sx| > 0.5 || |sy| > 0.5`。

两者是**同一条曲线上的两个读数**：`maxOffset` 降到 0.5 附近时，`anyAnimating` 同时转为 `false`。
而 `lastAnimatingNanos` **只在 `anyAnimating == true` 时刷新** —— 于是：

> 淡出窗口 = [最后一次 anyAnimating + 50ms, + 125ms]
> 而辉光归零时刻 ≈ 最后一次 anyAnimating + 8ms（下一帧）

**淡出窗口整体落在「辉光早已为 0」之后约 56ms。**

实测证据（`B2Final` 第 2 段，真实 `tickSpring` + 注入时钟，单次 8px 移动）：

```
   t=  144ms any=true  glowStrength=1.0          glowFade=1.0        peak=0.55000000
   t=  152ms any=true  glowStrength=0.93651813   glowFade=1.0        peak=0.51508497
   t=  160ms any=true  glowStrength=0.6488469    glowFade=1.0        peak=0.35686581
   t=  168ms any=true  glowStrength=0.4297516    glowFade=1.0        peak=0.23636338
   t=  176ms any=true  glowStrength=0.2632468    glowFade=1.0        peak=0.14478574
   t=  184ms any=true  glowStrength=0.13695945   glowFade=1.0        peak=0.07532770
   t=  192ms any=true  glowStrength=0.04134932   glowFade=1.0        peak=0.02274213
   t=  200ms any=false glowStrength=0.0          glowFade=1.0        peak=0.00000000
   ...
   t=  240ms any=false glowStrength=0.0          glowFade=1.0        peak=0.00000000
   t=  248ms any=true  glowStrength=0.0          glowFade=0.92       peak=0.00000000   ← 辉光已为 0 才开始淡
   t=  256ms any=true  glowStrength=0.0          glowFade=0.81333333 peak=0.00000000
   t=  280ms any=true  glowStrength=0.0          glowFade=0.49333334 peak=0.00000000
```

注意 `t=248ms` 起 `any` 变回 `true` —— 那正是 `fading` 把返回值顶上去了（§4.4 的 bug），
而 `glowStrength` 始终为 0，所以 `peak` 始终为 0。

**六场景 × 450 帧的位级统计**（`EnvelopeParity`：同一份反射 harness 分别编译到
「`9f8ccb7` 基线源码树」与「批次2 新版源码树」，逐帧输出 `glowStrength`/`glowFade`/`peak` 的
IEEE-754 位模式）：

| 场景 | `glowStrength` 最后 > 0 | `glowFade` 首次 < 1 | 差值 | `peak` 最后 > 0 | **`gs>0 且 gf<1` 的帧数** |
|---|---|---|---|---|---|
| T1 单字符右移 8px | 112ms | 168ms | **+56ms** | 112ms | **0** |
| T2 跨行下移 32px | 144ms | 200ms | **+56ms** | 144ms | **0** |
| T3 斜向 (8,32) | 144ms | 200ms | **+56ms** | 144ms | **0** |
| T4 大步跳 (0,200) | 192ms | 248ms | **+56ms** | 192ms | **0** |
| T5 连打 5 次（间隔 24ms） | 232ms | 288ms | **+56ms** | 232ms | **0** |
| T6 左移删除 8px | 112ms | 168ms | **+56ms** | 112ms | **0** |

**逐位比对结论**：把 `glowFade` 字段从记录里剔除后，
「新版（`overlayFadeOut=true`）」与「`9f8ccb7` 基线」的 `(glowStrength, peak)` 序列
**450 帧全部相同，差异帧数 = 0**。

```
>>> 只看 glowStrength 与 peak：不同帧数 = 0
```

**所以：**

- **不存在「两者叠加导致辉光过早消失或永远到不了满强度」的问题** —— 因为 `glowFade` 乘上去时
  `glowStrength` 已经是 0，`peak` 早已是 0，`drawQuadGlow` 在 `if (peak <= 0f) return;` 处直接返回。
- **`glowFade` 在每一个 `peak > 0` 的帧上都恒为 `1.0`**，所以乘积与 0.9.1 逐位相同。
- 用户看到的仍然是 0.9.1 的「由 `glowStrength` 提供的约 48ms 自然收敛」（见上表
  `t=144→192ms` 那段 `1.0 → 0.041` 的下降）。**契约 §3.6 要求的「柔和熄灭约 125ms」没有发生。**

**根因一句话**：方案 B 把淡出挂在了「已经不需要淡出」的那条曲线上。要让淡出可见，`glowFade`
必须在 `glowStrength` 还很高的时候就开始下降 —— 也就是淡出的计时起点不能是
「`anyAnimating` 变 false」（那正是辉光归零的时刻），而应该是「停止输入的时刻」，
且淡出期间必须**冻结** `glowStrength`（或改用 `peak = max(glowStrength, ...) × glowFade` 之类的
组合方式）。这属于设计层面要重做，不是调参能修好的。

### 4.6 `glowFade == 0` 之后是否还会 clearCorners / 无用重绘

| 检查 | 结果 |
|---|---|
| 是否会 `clearCorners()` | **不会**。`clearCorners()` 只在两处调用：`tick()` 的 `!content.isShowing()` 分支（L618）和 `onModeChanged()`/`clearTrail()`。实现正确地**没有**照抄上游的 `if (alpha <= 0f) { clearCorners(); return false; }` → §3.4 第 3 条满足 ✅ |
| 是否继续做「无用重绘」 | **会**，而且是**永久**的。因为 `tickSpring` 恒返回 true，调度器恒为活跃档，每帧仍执行 `panel.updateCorners(...)` + `computeTrailBounds(...)` + `submitRepaint()`。不过：基线在 `anyAnimating=false` 时**同样**每帧执行这三步（`tickSpring` 无论返回值都调），所以**单帧开销没变**；变的是**帧率没降下来**（8ms vs 120/600ms）→ 这才是真实开销 |

---

## 5. F3 核对

| 检查项 | 方法 | 结果 |
|---|---|---|
| `hideNativeCaret` 是否改成 `true` | 反射 `new NeovideConfig().hideNativeCaret` | `true` ✅ |
| javadoc 是否写明「有意偏离上游踩坑 #19」及理由 | 读 L243–264 | ✅ 明确写了「**【批次2 / F3】默认值改为 true —— 此处有意偏离上游踩坑 #19**」，并写明「`alwaysHighFps` 默认为 `true`，调度恒为 8ms（约 125fps），上游担心的『空闲降频导致跟随滞后』在这台机器上并不存在」+「用户实测**『取消勾选之后卡一点』**」 |
| Tools 菜单开关是否仍存在 | `ToggleHideNativeCaretAction.java` 存在 + `plugin.xml` L53–58 有 `NeovideCursor.HideNativeCaret` 且 `add-to-group group-id="ToolsMenu"` | ✅ 仍在，且 `description` 已同步改为「默认勾选」 |
| `alwaysHighFps` 默认值是否仍为 `true` | 反射读字段 | `true` ✅（未被顺手改） |
| 是否改了 `setHideNativeCaret` / `install` 逻辑 | git diff | **未改** ✅（diff 里 `NeovideConfig` 只动了默认值 + javadoc） |

**附带（非契约要求，但确认无害）**：新增了 `NeovideCursor.FadeOut` 菜单项 +
`ToggleFadeOutAction.java`（新文件），持久化键 `neovide.cursor.overlayFadeOut`，
`restorePersistedSettings()` 用默认值 `true` 读取 —— 与 `NeovideConfig.overlayFadeOut` 默认一致。
不在 §5 禁止清单内。

---

## 6. 开关等价性（契约 §6.1）

契约要求：`overlayFadeOut=false` 时行为应与 0.9.1 一致。我用**两条彼此独立的证据**验证。

### 6.1 代码层论证：`glowFade()` 恒返回 1f 时 `peak` 是否与改动前完全相同

改动前的表达式（`9f8ccb7`，L~250）：

```java
float peak = config.glowOpacity * (glowColor.getAlpha() / 255f) * glowStrength;
```

改动后：

```java
float peak = config.glowOpacity * (glowColor.getAlpha() / 255f) * glowStrength * glowFade;
```

`overlayFadeOut=false` 时 `glowFade()` 在方法**第一行**就 `return 1f`（L1047–1048），
之后 `panel.setGlowFade(1f)` → 字段 `glowFade == 1.0f`。

**关键**：IEEE-754 下 `y * 1.0f` 对任意有限 `y` **逐位恒等**（含 `±0.0`、次正规数；
`NaN` 除外，而此处 `y` 由常量与 clamp 过的字段相乘，不可能为 `NaN`）。
所以 `peak` 的**位模式**与改动前完全相同。

我实测扫了 10001 个采样点确认这一点（不是靠推理）：

```
B6 对 x∈[0,1] 扫 10001 点：peak(x*1.0f) 与 base(x) 位级相同 = true
```

### 6.2 端到端复现：`overlayFadeOut=false` 版 vs `9f8ccb7` 的完整辉光包络

我写了一份**只用反射**的 harness `EnvelopeParity`（因为两版 `tickSpring` 签名不同、
`NeovideConfig` 字段不同，无法直接调用共用），把它分别编译到两棵源码树：

- `classes-base` ← `git archive 9f8ccb7 src/main/java`（14 个 .java）
- `classes-new`  ← 当前工作区 `src/main/java`（15 个 .java）

每棵树各跑 6 个运动场景（单字符 / 跨行 / 斜向 / 大步跳 / 连打 5 次 / 左移删除），
逐帧输出 `帧号 glowStrength位模式 glowFade位模式 peak位模式 anyAnimating`。

剥掉含路径信息的 `# tree=` 头行后**逐字节比对**：

```
=== 剥掉 tree= 行后 base vs new-fadeOff 逐字节比对 ===
diff exit=0
base SHA=03DE783548BBAEA16054C36982E17963A608912D934E13E10D54BCF73FC210AC
off  SHA=03DE783548BBAEA16054C36982E17963A608912D934E13E10D54BCF73FC210AC
```

**450 行逐字节相同**（两份文件各 24,210 B，`git diff --no-index` 退出码 0）。

→ **§6.1「开关关闭档的 parity 必须与 0.9.1 一致」：满足。**

### 6.3 但请注意：这个「等价」的含金量有限

由 §4.5，`overlayFadeOut=true`（默认档）时 `(glowStrength, peak)` 序列与基线**也**是 450 帧全同。
所以准确的说法是：

> **这个开关在像素上不产生任何差异；它唯一的净效果是让调度器永不降频（§4.4）。**

即「开关等价性」这一条通过，但它通过的原因是功能没生效，而不是「关闭时正确退化」。

---

## 7. 构建产物指纹与 jar 内容一致性

### 7.1 构建（4/4 步通过）

```
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1" `
     -Version 0.9.2-imetime-fade -OutName "_verify-b2.jar" `
     -BaseJar "D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.1-zeroopt.jar"
```

原始输出：

```
[1/4] javac：15 个 .java，classpath 381 个 jar
[2/4] plugin.xml 已注入 version=0.9.2-imetime-fade since-build=242
[3/4] 已打包：D:\deepseekwork\neovim-cursor-pr\dist\_verify-b2.jar  (43871 字节)
[4/4] 自检：
      <version>0.9.2-imetime-fade</version>
      <idea-version since-build="242" />

完成。安装前必须先关闭 IDEA（它会锁住 jar）。
```

前置条件我自己复核过：`javac 25.0.2`（`D:\java_learning\jdk25\bin\javac.exe`）、
`D:\deepseekwork\_idea-anim\idea-libs` = **381 个 jar**、`java 25.0.2`。

### 7.2 文件 SHA256（我自己算的）

| 文件 | 字节 | SHA256 |
|---|---|---|
| `OPTIM-BATCH1.md` | 64,011 | `13B028034F47B69DD145107AE6113A747CD45766971912BF3748619588110EF7` |
| `FIX-BATCH2.md` | 16,628 | `36496CB38D104284249378CDBA67B4B2A52CDBC4A34166977A02930CBF194C93` |
| `core\NeovideConfig.java` | 14,202 | `A43F8F2712C0CB2D1183BAB409EF8BD649E024B6541E1C89407E90F4C2A4A8F8` |
| `ui\CaretAnimator.java` | 59,088 | `C8D4D8EC193FD83FCBA0C10CBB69386D55A39A5EB7DC8025170E3B793E23EB73` |
| `ui\CaretTrailPanel.java` | 18,027 | `7436683CB6EAB8CE836D5F65464153EDD42FD8B9D4750E4555D4FBC441C3BD19` |
| `ui\NeovideCaretManager.java` | 20,644 | `36D0F7495EA5F5E895BFB47D5EB5EB5E5EBBA57515FAAFC87123E609CA62F625` |
| `resources\META-INF\plugin.xml` | 3,820 | `09F7F6E8EB774EB70F28FBEE3EE4233A73FA0A52A0A9F6E7193D3F38552D9CD6` |
| `ui\ToggleFadeOutAction.java`（新增） | 1,160 | `2E44C5DE2F590E4AC28ECD3750F4BAA5B87701F1DC64AFC9CCF070D733962164` |
| **`dist\jetbrains-neovide-cursor-0.9.2-imetime-fade.jar`** | **43,871** | **`6F7422058304A7A9EC8F73E16B75A634F7D373EACDACC9B295A11C232EF352A0`** |
| **`dist\_verify-b2.jar`**（我构建的） | **43,871** | **`8765DDC5833FF1F76BBDF6F4D374876172A805A8D548D81795082B0249F6BDCB`** |
| `dist\jetbrains-neovide-cursor-0.9.1-zeroopt.jar`（回退基线） | 42,079 | `C718675E046CAB820E7EC6F5AEEE1B647C06DDF829BCF5837B185AD2BE29CB62` |

> 回退基线的哈希前 16 位 `C718675E046CAB82` 与契约 §6.3 一致（我自己算的）。

**整包 SHA 不同是预期的**：`jar --update` 会重写条目时间戳，字节流必变。
因此按契约要求做了**解包后逐文件比对**。

### 7.3 解包逐文件比对：交付 jar vs 我的 `_verify-b2.jar`

```
=== 交付 jar 文件数: 20 / 我的 jar 文件数: 20 ===
仅交付包有:
仅我的包有:
=== 内容不同的文件数 = 0 ===
```

**20 个条目全部同名、全部逐字节相同。** 交付 jar 与我用当前工作区源码重新构建的产物
**内容完全一致** → 交付 jar 确实是由「当前工作区这份源码 + 当前 `plugin.xml`」构建的，
不存在「源码改了但 jar 是旧的」或反之的情况。

### 7.4 0.9.1 → 0.9.2 的条目级变更（回答「有没有改坏东西」）

```
=== 0.9.1 有 / 0.9.2 无 ===
（空）
=== 0.9.2 有 / 0.9.1 无 ===
com\github\yyyolo7a79\neovidecursor\ui\ToggleFadeOutAction.class
=== 逐文件哈希对照（只列不同） ===
CHANGED  com\...\core\NeovideConfig.class
CHANGED  com\...\core\NeovideConfig$TrailMode.class
CHANGED  com\...\ui\CaretAnimator.class
CHANGED  com\...\ui\CaretTrailPanel.class
CHANGED  com\...\ui\NeovideCaretManager.class
CHANGED  META-INF\plugin.xml
=== 变化文件数 = 6 ===
```

**我逐个确认了每个 CHANGED 文件都对应契约内的改动**：

| 变化条目 | 对应的契约项 |
|---|---|
| `NeovideConfig.class` | F2 三字段 + F3 默认值 |
| `CaretAnimator.class` | F1-a + F1-b + F2（`lastAnimatingNanos`/`glowFade`/`tickSpring(now)`） |
| `CaretTrailPanel.class` | F2（`glowFade` 字段 + `setGlowFade` + `peak` 乘子） |
| `NeovideCaretManager.class` | F2（`overlayFadeOut` 持久化 + `setOverlayFadeOut`/`isOverlayFadeOut`） |
| `plugin.xml` | F3 注释/description + 新增 FadeOut 菜单项 |
| `NeovideConfig$TrailMode.class` | ⚠️ 见下 |
| `ToggleFadeOutAction.class`（新增） | F2 开关 |

**`NeovideConfig$TrailMode.class` 的「变化」是假警报** —— 它是内部枚举类，
我做了 `javap -v` 逐行比对，**唯一差异是 `LineNumberTable` 的行号**（`line 248 → 281`，
因为 `NeovideConfig.java` 新增了 F2 的 33 行注释/代码把枚举往后推了）。
常量池、方法体、签名、`SourceFile` 全部相同：

```
长度 old=1437 new=1437  首个不同偏移=1096  不同字节数=14
（diff 内容全部是 "line 248: 0" → "line 281: 0" 之类）
```

→ **无语义变化。不是改坏。`core` 包里真正有语义变化的只有 `NeovideConfig` 的字段默认值。**

### 7.5 一个**既有**问题（不是本批引入）

`jar` 里含 `OffsetTest.class`（`build\classes` 下 17 个 class 之一，文件名在 jar 根目录）。
我确认了：**0.9.1-zeroopt.jar 里也有它，且哈希完全相同**
（`649DB9A22D1265F20713277566F63D2EA1278FCA39E2B1CF4E35C66D254F64EC`）。
说明它来自 `BaseJar` 容器，是历史遗留，**本批未新引入、也未清理**。
不在本批范围（§5 也没要求清理构建脚本），仅记录。

---

## 8. 未覆盖 / 存疑项（明确声明）

| # | 未能验证 | 原因 | 影响 |
|---|---|---|---|
| U-1 | **真机 IME 组合行为**：F1-b 是否真的被走到、错位是否真的消失 | 需要真实中文输入法 + 运行中的 IDEA + 插桩 | **F1 的整体验收未完成**，只能验证代码逻辑与边界 |
| U-2 | **IntelliJ 是否在 IME 组合期间发 `caretPositionChanged`** | 同上 | 决定 F1-a 与 F1-b 谁生效（§3.4.4）。若发事件，F1-b 是死代码 |
| U-3 | **折叠 / inlay 提示是否发 caret 事件** | 需要真机事件日志 | 决定 §3.4.2 的误拦是「自愈」还是「长时间错位」 |
| U-4 | **淡出主观观感** | 需要人眼 | 由 §4.5 的位级证据推断「与 0.9.1 无差别」，但「看起来是否柔和」只能人判 |
| U-5 | **Tools 菜单实际勾选状态（首次启动/重置配置后）** | 不能在离线环境跑 IDEA | F3 的 §6.2 判据未验证；我只能验证默认值 = `true` 且菜单项已注册 |
| U-6 | **`alwaysHighFps` 在**其他**用户机器上的持久化值** | 只查了本机 `other.xml`（`"neovide.cursor.alwaysHighFps": "true"`） | 决定 §4.4 的 bug 对哪些用户可见 |
| U-7 | **`overlayFadeOut` 持久化的读写往返** | 需要 `PropertiesComponent` 运行实例 | 只做了代码层核对（键名、默认值一致） |
| U-8 | 真机 `fps`/`EDT` 占用回归 | 需要 JFR/日志采集 | §4.4 的调度后果是代码级 + 注入时钟实测，非真机 EDT 采样 |

---

## 9. 发现的问题（如实清单）

| ID | 严重性 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| **F-1** | **高** | **F2 辉光淡出在像素上完全不可见 —— 功能是 no-op。** 淡出窗口恒定比辉光归零晚 56ms，`glowFade` 在每一个 `peak>0` 的帧上恒为 1.0 | 6 场景 × 450 帧位级比对：`(glowStrength, peak)` 与 `9f8ccb7` **差异帧数 = 0**；`gs>0 且 gf<1` 的帧数 **= 0** | 需要**重做设计**：淡出计时起点改为「停止输入」而非「`anyAnimating` 变 false」，并在淡出期间冻结 `glowStrength`。调参无用 |
| **F-2** | **高** | **`fading = fade < 1f` 缺终止条件**（淡出后 `fade==0`，`0<1` 恒真）→ `tickSpring()` 恒返回 `true` → `computeNextDelay` 收到 `active=true` → 调度器永不降频 | 695/700 帧返回 true；`currentDelayMs` 恒 8ms（对照 `overlayFadeOut=false` 为 600ms）；`alwaysHighFps=false` 时真实 `tick()` 复现 | 改为 `fading = config.overlayFadeOut && fade > 0f && fade < 1f` |
| **F-3** | **中** | **F1-b 的拦截是「无限期冻结」而非「跳过一帧」**。折叠展开 / inlay 提示出现 / tab 宽度变化等「offset 不变、视觉列合法改变」场景会被误拦，光标停在旧像素位置直到下一次 caret 事件 | 连续 200 帧全拦；`centerX` 停在 101.0 而正确值 133.0（偏差 30px ≈ 1.5 字符） | 加「连续跳过帧数」上界（如 ≤15 帧），或仅在确有 IME 组合时启用 |
| **F-4** | **低** | 契约 §6.3 要求新增 `build/rollback-0.9.2.ps1`，**未交付**。仓库里只有 `rollback-0.9.1.ps1`，而且它的内容是回退到 **0.9.0**（`FA161E4F77C8C999`），与文件名不符 | `Get-ChildItem -Recurse -Filter '*0.9.2*'` 只命中 jar 本身 | 补 `rollback-0.9.2.ps1`（目标 `C718675E046CAB82`）；顺带修 `rollback-0.9.1.ps1` 的错配 |
| **F-5** | **低** | `glowFade()` 在 `lastAnimatingNanos == 0L` 时返回 `1f`，契约 §3.3 原文要求 `0f` | 探针 B2 实测 `1.000000` | 行为无差异（此时 `glowStrength` 亦为 0），可不改；但若要严格守约应改回 `0f` |
| **F-6** | **低** | 契约 §3.4 第 3 条「`fade == 0` 后不必再 clearCorners」**已正确满足**，但同段第 2 条的「淡出期间才继续 tick」被实现成「永远继续 tick」 | 同 F-2 | 同 F-2 |
| **F-7** | **提示** | F1-a 与 F1-b 在设计上互斥：F1-a 每帧置 `forceResync`，F1-b 只在 `!forceResync` 时生效。若平台在 IME 组合期间发 caret 事件，F1-b 永不触发 | 代码结构（L530 vs L859）+ 实测 A9/A2 | 手测时在 F1-b 的 `return false` 前加一行 debug 日志，确认它是否被走到 |

**未发现的问题（明确排除）**：常量被顺手改动、`core` 包被误改、EDT 任务粒度被破坏、
多线程被引入、`alwaysHighFps` 被改、`hideNativeCaret` 菜单被删、构建产物与源码不一致、
光标本体被一起淡出、`getOffset()` 每帧重复调用。

---

## 10. 可复跑命令（我自己用的）

```powershell
# 1) parity（真实 HEAD 作基线，比默认的 321b0a0 更严格）
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1" `
     -BaseRef 9f8ccb7 -Work "$env:TEMP\nc-b2-parity-head"

# 2) 常量审计（默认基线 321b0a0；我已验证 19 项与 9f8ccb7 完全一致）
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-audit.ps1"

# 3) 构建
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1" `
     -Version 0.9.2-imetime-fade -OutName "_verify-b2.jar" `
     -BaseJar "D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.1-zeroopt.jar"

# 4) 探针（源码已存到 diag\verify-batch2-raw\harness\）
$libs = 'D:\deepseekwork\_idea-anim\idea-libs'
$cp = (Get-ChildItem $libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
$classes = 'D:\deepseekwork\neovim-cursor-pr\build\classes'
$h = 'D:\deepseekwork\neovim-cursor-pr\diag\verify-batch2-raw\harness'
javac --release 21 -nowarn -encoding UTF-8 -classpath "$classes;$cp" -d "$env:TEMP\b2out" "$h\B2Verify.java"
java -Dfile.encoding=UTF-8 -cp "$env:TEMP\b2out;$classes;$cp" B2Verify
# 其余：B2Probe / B2Final / B2Exact / B2Delay / B2Real（同上）
# 包络 parity：EnvelopeParity 需分别编译到基线与新版两棵源码树，见 §6.2
```

**原始输出存档**：`diag\verify-batch2-raw\`
（`01-B2Verify.txt` ~ `06-B2Real.txt`、`10~12-envelope-*.txt`、`harness\*.java`）

---

## 11. 最终判定

| 契约条款 | 判定 |
|---|---|
| §2.2 F1-a | **通过** |
| §2.3 F1-b 边界 5 条 / P0-5 | **通过**（边界逐条实测满足、`getOffset` 未新增调用） |
| §2.3 F1-b「至多一帧不掉」 | **不通过**（实际是无限期冻结，见 F-3） |
| §3.2 方案 B（光标本体不淡） | **通过**（结构正确） |
| §3.3 淡出逻辑 | **基本通过**（除 `lastAnimatingNanos==0` 返回 1f 这一处偏离，F-5） |
| §3.4 第 2 条（返回值带 fading，不得残留） | **不通过**（`fading` 无终止条件，F-2） |
| §3.4 第 3 条（fade==0 后不 clearCorners） | **通过** |
| §3.6 判定「停止后约 125ms 柔和熄灭」 | **不通过**（淡出不可见，F-1） |
| §4 F3 | **通过** |
| §5 禁止清单 | **未命中任何一条** |
| §6.1 编译 / parity / 审计 / 调度语义 | **全部通过** |
| §6.1 开关等价性 | **通过**（逐字节相同），但见 §6.3 的含金量说明 |
| §6.1 jar 内容 | **通过**（20/20 逐字节一致） |
| §6.3 回退脚本 | **不通过**（缺 `rollback-0.9.2.ps1`，F-4） |

**总体**：**F1（代码层）与 F3 可以放行；F2 不能放行** —— 它当前既没有产生承诺的视觉效果（F-1），
又在 `alwaysHighFps=false` 的配置下引入了一条永久占用的调度路径（F-2）。
F1-b 需要补一个冻结上界（F-3）才适合交付。

按契约的语气：**F-2 命中 §3.4 的明确要求（「必须保证」第 2 点），F-1 命中 §3.6 的判定标准，
两条都足以 reject F2 这一项。**
