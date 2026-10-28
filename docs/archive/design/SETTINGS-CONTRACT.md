# SETTINGS-CONTRACT —— 设置界面（0.9.4-settings）第一期实现契约

> **状态**：**v2**（v1 冻结 → 下游实现 → t5 独立审查 → 本轮修订）。修订只涉及类名/包名与
> 「第一期不做」条款的显式标注，**不改任何实现要求与默认值**。变更清单见文末 §8「v2 修订记录」。
> **基线**：HEAD `ba67ed3`，分支 `feat/settings-ui-ready`，已安装 `0.9.3-imegeom`
> **唯一依据**：[`SETTINGS-RESEARCH.md`](SETTINGS-RESEARCH.md)（已实测调研）+ 本仓库 `src/` 现状源码。
> **本文件不改任何代码**，只冻结条文。凡与 `SETTINGS-RESEARCH.md` 冲突处，以本文件为准并**必须**在文中给出理由。
>
> **三条硬纪律（贯穿全文，违反即验收失败）**
> 1. **不改任何观感常量的默认值**（只改「可调」性）—— 清单见 §5。
> 2. **不装 jar、不重启 IDEA** —— 本契约的所有判据必须能在 `build/offline-build.ps1` + 三件套 harness 内裁决。
> 3. **必须按顺序先落地三个前置改造**（§3.1 → §3.2 → §3.3），面板 UI 只允许在这三者完成之后开始。

---

## 目录

- [0. 裁决口径与本文的边界](#0-裁决口径与本文的边界)
- [1. 第一期字段清单（16 项）](#1-第一期字段清单16-项)
- [2. 控件 / 范围 / 默认值 / 换算 / 警告（逐字段表）](#2-控件--范围--默认值--换算--警告逐字段表)
- [3. 三个前置改造的详细设计](#3-三个前置改造的详细设计)
- [4. 顺手要修的 bug：alwaysHighFps 兜底 false](#4-顺手要修的-bugalwayshighfps-兜底-false)
- [5. 禁止改默认值清单（可机械校验）](#5-禁止改默认值清单可机械校验)
- [6. 第一期不做 / 永远不做成 UI](#6-第一期不做--永远不做成-ui)
- [7. 验收判据（可机械裁决）](#7-验收判据可机械裁决)
- [附录 A：证据行号索引](#附录-a证据行号索引)

---

## 0. 裁决口径与本文的边界

### 0.1 三类条文强度

| 标记 | 含义 | 裁决方式 |
|---|---|---|
| **【必须】** | 违反即验收失败，不可商量 | 静态检查 / harness / git diff |
| **【禁止】** | 出现即验收失败 | 静态检查（见 §5/§6 的 grep 清单） |
| **【建议】** | 允许偏离，但必须在实现 PR 里写明理由 | 人工 / 审查 |

### 0.2 与 `SETTINGS-RESEARCH.md` §4.4 的对账（**增删必须显式说明**）

§4.4「第一期」表共 14 行，展开成**字段**是 16 项（`tailColor/tailOpacity` 同行、`useShadow/shadowColor/glowOpacity` 同行、`cursorDisappearDelay/cursorFadeOutDuration` 同行）。本契约 §1 的 16 项**与之逐项一致，不多不少**。

**两处必须写明的"未采纳"**（都**不是**增删字段，而是呈现方式）：

| # | §4.4 / 调研中的说法 | 本契约裁决 | 理由 |
|---|---|---|---|
| D-1 | §4.2 耦合 6 建议把 `alwaysHighFps`+`hideNativeCaret` 合并成"二选一档位"单选组 | **不做**，保持两个独立复选框 + 一行耦合提示（`W_COUPLING`） | ① §4.4 第一期表本身就是两个复选框，分期裁决优先；② 两个开关各自已有 Tools 菜单入口（`ToggleHideNativeCaretAction` / `ToggleAlwaysHighFpsAction`），合并成组会逼着改菜单语义 —— 越出「设置界面」范围。提示文案达到同样的告知目的 |
| D-2 | §3.1 备注建议 `tailColor/shadowColor` **内部改存 `int rgb`** | **不改**，State 里仍是 `String "#RRGGBB"` | `NeovideConfig.java` 必须逐字不变（parity 的三个 harness 都 `new NeovideConfig()` 取默认值，见附录 A-3），改存储类型会连带改 `NeovideColors.parse` 的 4 处**每帧**调用点。零观感收益、纯风险 |

### 0.3 与既有批次契约的从属关系

本契约**不覆盖** `OPTIM-BATCH1.md` / `FIX-BATCH2.md` / `FIX-BATCH3.md` 的任何观感或时序裁决。冲突时：**批次契约 > 本契约**（因为批次契约对应已安装、已被用户接受的行为）。本契约只新增「可调性 + 持久化 + 编排」这三层。

---

## 1. 第一期字段清单（16 项）

**排序即面板上的顺序**（分组见 §2.4）。

| # | 字段 | 中文名（面板文案） | 分组 |
|---|---|---|---|
| 1 | `enabled` | 启用拖尾光标（总开关） | A 总开关 |
| 2 | `trailMode` | 拖尾渲染模式 | A 总开关 |
| 3 | `hideNativeCaret` | 隐藏原生光标（覆盖层充当光标） | A 总开关 |
| 4 | `alwaysHighFps` | 常驻刷新（永远按 8ms 调度） | A 总开关 |
| 5 | `overlayFadeOut` | 辉光淡出（停止后柔和熄灭） | B 淡出 |
| 6 | `cursorDisappearDelay` | 淡出前保持时长 | B 淡出 |
| 7 | `cursorFadeOutDuration` | 淡出时长 | B 淡出 |
| 8 | `tailColor` | 拖尾颜色 | C 颜色 |
| 9 | `tailOpacity` | 拖尾不透明度 | C 颜色 |
| 10 | `useShadow` | 启用辉光 | C 颜色 |
| 11 | `shadowColor` | 辉光颜色 | C 颜色 |
| 12 | `glowOpacity` | 辉光峰值不透明度 | C 颜色 |
| 13 | `glowLayers` | 辉光层数 | C 颜色 |
| 14 | `shadowBlurFactor` | 辉光外扩幅度 | C 颜色 |
| 15 | `trailSize` | 拖尾密度 | D 拖尾手感 |
| 16 | `"拖尾拉伸强度"`（复合滑块，写 `trailFactors`） | 拖尾拉伸强度 | D 拖尾手感 |

**关于第 16 项的定位【必须】**：这一项的**持久化真相是标量** `stretchRatioK`（默认 `4.5f`），`trailFactors` 是它的**派生值**（公式见 §2.5）。它占 `SETTINGS-RESEARCH.md` §4.4 表里的「"拖尾拉伸强度"复合滑块（内部等比缩放 trailFactors，方案 B）」这一行，因此**字段计数按 16 项算，不按 17 项算**；§2 表里它单独占一行并在默认值列注明"派生"。

**关于第 1 项与"死字段"的关系【必须】**：`NeovideConfig.enabled` 现在是**零读取**的死字段（证据：`grep -r "CONFIG.enabled\|config.enabled" src/` → 0 命中；运行期用的是 `NeovideCaretManager.enabled`，见附录 A-2）。第一期**必须先统一**这两个布尔（§3.1.4），统一后**面板上只出现一个"启用"控件**，绑定到统一后**唯一那一个**布尔。

---

## 2. 控件 / 范围 / 默认值 / 换算 / 警告（逐字段表）

### 2.1 总表

**控件类型列口径**：`DSL` = Kotlin UI DSL v2（`com.intellij.ui.dsl.builder`，**已实测 Java 可直接调用**，见 `SETTINGS-RESEARCH.md` §2.0）；`FB` = FormBuilder 回退（§2.6）。**默认值列必须与 `src/` 现值逐字一致**（举证见附录 A-1）。

| # | 字段 | 控件类型 | 取值范围（刻度） | 默认值（`src` 现值，逐字） | 单位换算 / 映射 | 警告 | 联动置灰 |
|---|---|---|---|---|---|---|---|
| 1 | `enabled` | DSL `checkBox`（FB: `JBCheckBox`） | true / false | `public boolean enabled = true;` | 无 | 否 | 无 |
| 2 | `trailMode` | DSL `comboBox`（FB: `ComboBox`） | `SPRING` / `AFTERIMAGE` | `public TrailMode trailMode = TrailMode.SPRING;` | 枚举 ⇄ 下拉项，文案见 §2.4 | 否 | 它是 #15 #16 的门控 |
| 3 | `hideNativeCaret` | DSL `checkBox`（FB: `JBCheckBox`） | true / false | `public boolean hideNativeCaret = true;` | 无 | **是**（`W_HIDE_NATIVE`） | 无（但对 #4 有耦合提示 `W_COUPLING`） |
| 4 | `alwaysHighFps` | DSL `checkBox`（FB: `JBCheckBox`） | true / false | `public boolean alwaysHighFps = true;` | 无 | **是**（`W_ALWAYS_HIGH_FPS`） | 无（但有 `W_COUPLING`） |
| 5 | `overlayFadeOut` | DSL `checkBox`（FB: `JBCheckBox`） | true / false | `public boolean overlayFadeOut = true;` | 无 | 否 | 它是 #6 #7 的门控 |
| 6 | `cursorDisappearDelay` | DSL `slider(0,500,50,10)`（FB: `JBSlider`） | 0 – 500 ms，步长 10 | `public int cursorDisappearDelay = 50;` | **本身就是毫秒**：刻度 = 值，无换算 | 否 | `enabledIf(overlayFadeOut)` |
| 7 | `cursorFadeOutDuration` | DSL `slider(0,1000,75,5)`（FB: `JBSlider`） | 0 – 1000 ms，步长 5 | `public float cursorFadeOutDuration = 0.075f;` | **毫秒 → 秒**：`值 = 刻度 / 1000.0f`（默认刻度 75 = 0.075f）；标签 `"75 ms"` | 否 | `enabledIf(overlayFadeOut)` |
| 8 | `tailColor` | `ColorPanel`（**DSL 无 `colorPanel()` 工厂方法**，必须 `row.cell(colorPanel)` 自己塞；FB 同） | 任意 RGB（`#RRGGBB`） | `public String tailColor = "#FFC0CB";` | `String "#RRGGBB"` ⇄ `java.awt.Color`（`getSelectedColor()`/`setSelectedColor()`）；**只写回六位大写 HEX** | 否 | 无 |
| 9 | `tailOpacity` | DSL `slider(0,100,100,5)`（FB: `JBSlider`） | 0 – 100，步长 5 | `public float tailOpacity = 1.0f;` | **0-100 → 0.0-1.0**：`值 = 刻度 / 100.0f`（默认刻度 100）；标签 `"1.00"` | 否 | 无 |
| 10 | `useShadow` | DSL `checkBox`（FB: `JBCheckBox`） | true / false | `public boolean useShadow = true;` | 无 | 否 | 它是 #11 #12 #13 #14 的门控 |
| 11 | `shadowColor` | `ColorPanel`（同 #8） | 任意 RGB | `public String shadowColor = "#FFC0CB";` | 同 #8 | 否 | `enabledIf(useShadow)` |
| 12 | `glowOpacity` | DSL `slider(0,100,55,1)`（FB: `JBSlider`） | 0 – 100，步长 1 | `public float glowOpacity = 0.55f;` | **0-100 → 0.0-1.0**：`值 = 刻度 / 100.0f`（默认刻度 55） | 否 | `enabledIf(useShadow)` |
| 13 | `glowLayers` | DSL `slider(1,12,8,1)`（FB: `JBSlider`） | **1 – 12**，步长 1 | `public int glowLayers = 8;` | 无换算（整数层数） | **是**（`W_GLOW_LAYERS`） | `enabledIf(useShadow)` |
| 14 | `shadowBlurFactor` | DSL `slider(5,60,20,1)`（FB: `JBSlider`） | **0.05 – 0.60**，刻度 5–60，步长 1 | `public float shadowBlurFactor = 0.2f;` | **刻度 / 100**：`值 = 刻度 / 100.0f`（默认刻度 20）；标签 `"0.20"` | **是**（`W_SHADOW_BLUR`） | `enabledIf(useShadow)` |
| 15 | `trailSize` | DSL `slider(0,100,100,1)`（FB: `JBSlider`） | **0.0 – 1.0**（官方文档明写 Range 0.0 to 1.0，见 `SETTINGS-RESEARCH.md` §1.1），刻度 0–100 | `public float trailSize = 1f;` | **0-100 → 0.0-1.0**：`值 = 刻度 / 100.0f`（默认刻度 100） | 否 | `trailMode == SPRING` 才启用（§2.3） |
| 16 | **拖尾拉伸强度**（派生 `trailFactors`） | DSL `slider(10,80,45,1)`（FB: `JBSlider`） | 拉伸倍率 **1.0× – 8.0×**，刻度 10–80 | 派生自 `public float[] trailFactors = {1.0f, 0.72f, 0.42f, 0.22f};`；标量真相 `stretchRatioK = 4.5f` | `k = 刻度 / 10.0`；`trailFactors = f(k)`（**精确公式 §2.5**）；标签 `"4.5×  后缘 110 ms / 前缘 24 ms"` | **是**（`W_STRETCH`） | `trailMode == SPRING` 才启用（§2.3） |

**关于 #15/#16 的置灰方向【必须】**：仅当 `trailMode == AFTERIMAGE` 时置灰。依据：`CaretAnimator.java:672` 按 `trailMode` 二选一，AFTERIMAGE 走 `tickAfterimage`，**完全不碰 `TrailCorner`**；而 `trailSize` 与 `trailFactors` 只在 `TrailCorner.jump()` 里被读（`:220-228`）。

### 2.2 警告文案常量表【必须逐字】

实现时**必须**用下面这些字符串（可加换行/缩进，**不得改动文字**）。审查方按本表逐字 diff。

| 常量名 | 使用位置 | 确切文案 |
|---|---|---|
| `W_ALWAYS_HIGH_FPS` | #4 的 `comment` | `常驻刷新 = 调度线程每秒向 EDT 投递约 125 次任务，且永不停歇。标签页很多或机器较慢时，它可能让 IDE 反而更卡。关掉后空闲会降到 120ms（失焦 600ms），代价是「停手后第一次操作」可能等一个空闲周期才动。` |
| `W_HIDE_NATIVE` | #3 的 `comment` | `隐藏原生光标 = 把全局配色方案的 CARET_COLOR 改成全透明。IDE 会把它落盘写进 colors/*.icls，卸载插件后光标可能依然是隐形的。取消勾选即可恢复原生光标（也建议从此处恢复）。` |
| `W_COUPLING` | #3/#4 下方的 `rowComment` | `「隐藏原生光标」勾选时覆盖层就是唯一的「光标」，跟随完全依赖调度，建议同时保持常驻刷新；取消勾选后原生光标零延迟跟随，常驻刷新可以关掉以省电。` |
| `W_GLOW_LAYERS` | #13 的 `comment` | `层数越多渐变越平滑，但每层都是一次 CPU 抗锯齿 + alpha 混合（fill(Path2D) 不享受 Java2D 的 GPU 加速）。层数不影响亮度：内部按层数反解每层 alpha，8 层与 12 层的峰值亮度相同。` |
| `W_SHADOW_BLUR` | #14 的 `comment` | `0.6 是原版 JS 的夸张光雾（约 40px）。它同时决定重绘 padding：每个编辑器每帧的重绘包围盒会随之变大，慢机器上可能掉帧。默认 0.2（约 14px）是本插件按「光晕总宽与残影模式一致」反推的值。` |
| `W_STRETCH` | #16 的 `comment` | `只调整后缘与前缘的时长比（拉伸倍率）。默认 4.5×＝后缘 110ms / 前缘 24ms，是实测调出来的观感；调低会让拖尾变平，调高会把后缘拉长。rank0 最拖、rank3 最跟手，方向固定，不可对调。` |
| `W_FADE_OFF` | #6/#7 置灰时的 `comment` | `辉光淡出关闭时不使用这两个值，辉光瞬时归零（行为与 0.9.1 一致）。` |

> `W_STRETCH` 里"rank0 最拖 / rank3 最跟手"不是装饰：这是 `trailFactors` 做 UI 最容易出的错（`SETTINGS-RESEARCH.md` §4.2 耦合 4，证据 `TrailCorner.java:136-137` 的 javadoc）。

### 2.3 联动置灰矩阵（**必须实现，缺一即验收失败**）

| 门控（predicate） | 被置灰的字段 | 条数 | 代码依据 |
|---|---|---|---|
| `useShadow == false` | `shadowColor`、`shadowBlurFactor`、`glowLayers`、`glowOpacity` | **4** | `CaretTrailPanel.java:265`：`if (!config.useShadow ...)` 直接门控整段辉光绘制 |
| `overlayFadeOut == false` | `cursorDisappearDelay`、`cursorFadeOutDuration` | **2** | `CaretAnimator.java:1215`：`if (!config.overlayFadeOut) return 1f;` 是硬门控 |
| `trailMode == AFTERIMAGE` | `trailSize`、`拖尾拉伸强度`（#15 #16） | **2** | `CaretAnimator.java:672`；`TrailCorner` 只在弹簧分支被调用 |
| `trailMode == SPRING` | （本期无）`afterimageLifetime` **不在第一期**，故这一侧**没有**被置灰项 | 0 | 同上 |

**DSL 写法【必须】**：用 `Cell.enabledIf(ComponentPredicate)` 或 `Row.enabledIf(...)`。checkbox 的 predicate 直接取 `ButtonKt.getSelected(cell)`（`javap` 实测存在：`public static ComponentPredicate getSelected(Cell<? extends AbstractButton>)`）—— **不要**自己 new `ComponentPredicate` 子类（它是 abstract 且 `addListener` 语义要正确实现，自己写会漏刷新）。

**FormBuilder 回退写法【必须】**：门控 checkbox 加 `ActionListener` → 调一个集中的 `setEnabledByPredicates()`，遍历 4/2/2 个控件 `setEnabled(...)`；并**在 `reset()` 末尾也调一次**（否则初次打开面板时置灰状态不对）。

### 2.4 分组与显示格式【建议，除标注外】

    A. 总开关       : #1 enabled / #2 trailMode / #3 hideNativeCaret / #4 alwaysHighFps
    B. 淡出         : #5 overlayFadeOut / #6 cursorDisappearDelay / #7 cursorFadeOutDuration
    C. 颜色与辉光   : #8 tailColor / #9 tailOpacity / #10 useShadow / #11 shadowColor
                      / #12 glowOpacity / #13 glowLayers / #14 shadowBlurFactor
    D. 拖尾手感     : #15 trailSize / #16 拖尾拉伸强度

- `trailMode` 下拉项文案【必须】：`SPRING` → `"弹簧拉伸（Neovide 原版效果）"`；`AFTERIMAGE` → `"残影轨迹（低帧率更稳）"`。**不得**只显示枚举名。
- 每个滑块**右侧必须有一个 `JBLabel` 显示真实值**（不是刻度值）：`"1.00"` / `"0.55"` / `"8 层"` / `"50 ms"` / `"75 ms"` / `"0.20"` / `"4.5×  后缘 110 ms / 前缘 24 ms"`。理由：DSL `slider` 只收 int（`SETTINGS-RESEARCH.md` §3.3），用户看到的必须是真实物理量。
- 滑块**必须**在 `reset()` 之后回显一次；**禁止**在 `onChanged` 里做实时改写（§3.4）。

### 2.5 复合滑块 `stretchRatioK → trailFactors` 的**精确公式**【必须】

记刻度为整数 `t ∈ [10, 80]`，`k = t / 10.0`（1.0–8.0）。基准形状取 `L = {0.0, 0.28, 0.58, 0.78}`（即 `1 - {1.0,0.72,0.42,0.22}`，`TrailCorner.java:220` 的现有梯度），则

    m  = (1.0 - 1.0 / k) / 0.78
    f0 = 1.0
    f1 = round2(1.0 - 0.28 * m)
    f2 = round2(1.0 - 0.58 * m)
    f3 = round2(1.0 - 0.78 * m)          // 恒等于 round2(1/k)
    round2(x) = (float)(Math.round(x * 100) / 100.0)
    trailFactors = new float[]{ f0, f1, f2, f3 }

**默认必须精确复现现有数组【必须】**：`t = 45` ⇒ `k = 4.5` ⇒ `m = 0.997150997...` ⇒ `f = {1.00, 0.72, 0.42, 0.22}`。验收时用 `Float.floatToRawIntBits` 逐元素比对 `{1.0f, 0.72f, 0.42f, 0.22f}`。若某元素差 1 ulp，**唯一允许的修法**是在 `k == 4.5` 时直接返回规范常量数组（显式特例，且只允许这一个特例），**禁止**为了凑值去改 `round2` 的语义。

**为什么不是"整体乘一个系数"的朴素等比缩放【必须写进实现注释】**：把四个系数同乘 `c` 会同时改变 rank0 的**绝对时长**（`0.11 × 1.0 = 110ms` 是实测调出来的观感，见 `NeovideConfig.java:149-157`），而用户拖"拉伸强度"时想要的只是**比值**变化。上述公式保持 `f0 = 1`（后缘绝对时长恒 110ms），只压缩前三档滞后量 —— 这才是"等比缩放"在 lag 域的正确实现。

**非规范数组的处理【v2 裁决：第一期不做】**：原 v1 要求「面板打开时用 `t = clamp(round(10 / f3), 10, 80)` 反推刻度；只要用户不拖动该滑块，`apply()` 不得改写 `trailFactors`」。

> **v2 改判理由（t5 审查 R-04）**：该条款在第一期**不可达**，且实现它反而有害。
> ① 第一期的 `trailFactors` 唯一来源是 `NeovideConfig` 的编译期常量 `{1.0, 0.72, 0.42, 0.22}`，
> 它恰好就是 `t = 45`（`k = 4.5`）的规范数组 —— 不存在"非规范数组"这个输入。
> ② 反推本身会引入漂移：`1 / 0.22 = 4.545`，而 `deriveTrailFactors(4.545)` 要经过 `round2`，
> 面板滑块会在打开→应用→再打开的循环里自己挪位。
> ③ 一旦接上反推，就必须额外维护"用户是否拖动过"这个状态，而它与「OK 生效」的单一路径相冲突。
>
> **因此第一期明确不做**：不提供反推方法（v1 里那个 `kFromTrailFactors()` 已从源码删除），
> 面板恒以 `State.stretchRatioK` 为准。留给第二期（届时 `trailFactors` 才可能被外部写入）。
> 这是本契约里**唯一**一处「v1 要求、v2 不做」的降级，其余条款不变。

**端点与中段的自检表【必须】**（实现方按此逐行断言，避免"公式看起来对但数值不对"）：

| 刻度 t | k | 派生 `trailFactors` | 实际比值 `f0/f3` |
|---|---|---|---|
| 10 | 1.0 | `{1.00, 1.00, 1.00, 1.00}` | 1.000 |
| 20 | 2.0 | `{1.00, 0.82, 0.63, 0.50}` | 2.000 |
| **45（默认）** | **4.5** | `{1.00, 0.72, 0.42, 0.22}` | **4.545** |
| 80 | 8.0 | `{1.00, 0.69, 0.35, 0.13}` | 7.692 |

**关于"标称 4.5× 但实际 4.545×"【必须】**：刻度标注用**标称值**（`4.5×`），因为 `round2` 把 `f3` 从 `0.2222...` 取整到 `0.22`，实际比值必落在标称值的 ±1.5% 内。**不得**为了"显示与实际完全一致"去改 `round2` 或去改 `f0`（前者破坏默认值精确复现，后者破坏"后缘恒 110ms"）。

**联动显示【必须】**：`后缘 ms = round(shortAnimationLength * 1000 * f0)`，`前缘 ms = round(shortAnimationLength * 1000 * f3)`。第一期 `shortAnimationLength` 固定 `0.11f`（不可调），故显示值确定：默认 `110 ms / 24 ms`。

### 2.6 FormBuilder 回退映射表（**只在 DSL 编译失败时启用**）

**启用条件【必须】**：`build/offline-build.ps1` 下 `javac` 因 DSL 相关类报错失败（例如 Kotlin 生成签名与 Java lambda 不匹配）。**不允许**"因为偏好"而选回退。启用时**字段名 / 范围 / 默认值 / 换算 / 警告文案必须与 §2.1、§2.2 逐字一致**。

| DSL | FormBuilder |
|---|---|
| `checkBox(text)` | `new JBCheckBox(text)` + `FormBuilder.addComponent` |
| `slider(min,max,val,step)` | `new JBSlider(min,max,val)`；**手动实现 step 吸附**：`setValue(round(v/step)*step)` |
| `comboBox(items)` | `new ComboBox<>(items)` |
| `cell(ColorPanel())` | 同左（`ColorPanel` 不依赖 DSL） |
| `enabledIf(pred)` | 手写监听 + 集中式 `setEnabledByPredicates()`（§2.3） |
| `comment(text)` | `FormBuilder.addComponent(new JBLabel("<html>...</html>"))` |
| `bindSelected / bindValue / bindItem` | 手写 getter/setter，并在 `apply()/reset()/isModified()` 里逐字段处理 |

---

## 3. 三个前置改造的详细设计

> 顺序不可交换：**3.1 → 3.2 → 3.3 → 面板**。每一步都必须先能独立编译 + 通过三件套，才能进下一步。

### 3.1 ① NeovideCursorSettings（持久化服务）

#### 3.1.1 类结构与注解（**逐字**）

新文件：`src/main/java/com/github/yyyolo7a79/neovidecursor/settings/NeovideCursorSettings.java`

    @State(name = "NeovideCursorSettings",
           storages = @Storage(value = "NeovideCursorSettings.xml",
                               roamingType = RoamingType.DISABLED))
    public final class NeovideCursorSettings implements PersistentStateComponent<NeovideCursorSettings.State> {

        public static NeovideCursorSettings getInstance() {
            return ApplicationManager.getApplication().getService(NeovideCursorSettings.class);
        }

        private State state = new State();

        @Override public State getState() { return state; }

        @Override public void loadState(@NotNull State from) { /* §3.1.3 */ }

        @Override public void noStateLoaded() { /* §3.2 迁移入口 */ }
    }

**`roamingType` 的裁决【必须】**：`RoamingType.DISABLED`。理由：这批字段里 `alwaysHighFps` / `glowLayers` / `shadowBlurFactor` 是**性能相关**（弱机器上该关），`SETTINGS-RESEARCH.md` §2.3 明确说 "Only the settings that aren't specific to a given machine should be shared"；而按 §2.3 建议拆成两个 `@State` 文件会把"单一真相源"切成两半，收益不抵风险。**第一期统一 DISABLED**（不同步），"可漫游"作为第二期课题。

**`@Storage` 用法注意**：`value` 与 `file` 是同一语义（`javap` 实测两个属性都在），本项目统一写 `value`。文件落在 `<config>/options/NeovideCursorSettings.xml`（平台文档 `persisting-state-of-components` 对 application 级存储强烈推荐自定义文件，`other.xml` 已废弃）。

#### 3.1.2 状态类字段清单（`public static final class State`）

**只放第一期 16 项的持久化形态 + 1 个迁移版本号**。理由：第一期没有任何 UI 通路能改 `animationLength` 等字段，把它们也持久化只会扩大迁移面与"三真相源"风险。

| 顺序 | 字段 | 类型 | 初值（**必须** = `NeovideConfig` 现值） | 备注 |
|---|---|---|---|---|
| — | `stateVersion` | `int` | `0` | 迁移闸门，见 §3.2 |
| 1 | `enabled` | `boolean` | `true` | 与 manager 的 `enabled` 统一后（§3.1.4） |
| 2 | `trailMode` | `NeovideConfig.TrailMode` | `NeovideConfig.TrailMode.SPRING` | 枚举在官方序列化白名单（`SETTINGS-RESEARCH.md` §2.3） |
| 3 | `hideNativeCaret` | `boolean` | `true` | |
| 4 | `alwaysHighFps` | `boolean` | `true` | **默认值 bug 的落点，见 §4** |
| 5 | `overlayFadeOut` | `boolean` | `true` | |
| 6 | `cursorDisappearDelay` | `int` | `50` | 毫秒 |
| 7 | `cursorFadeOutDuration` | `float` | `0.075f` | 秒 |
| 8 | `tailColor` | `String` | `"#FFC0CB"` | 见 D-2，仍存 String |
| 9 | `tailOpacity` | `float` | `1.0f` | |
| 10 | `useShadow` | `boolean` | `true` | |
| 11 | `shadowColor` | `String` | `"#FFC0CB"` | |
| 12 | `glowOpacity` | `float` | `0.55f` | |
| 13 | `glowLayers` | `int` | `8` | |
| 14 | `shadowBlurFactor` | `float` | `0.2f` | |
| 15 | `trailSize` | `float` | `1f` | |
| 16 | `stretchRatioK` | `float` | `4.5f` | **派生源**；`trailFactors` 不单独持久化（§2.5） |

**约束【必须】**：状态类必须有**公开无参构造器**（平台 `XmlSerializer` 要求）；状态字段必须 `public`（`XmlSerializer` 默认按字段名序列化）；**不得**出现 `float[] trailFactors`（`SETTINGS-RESEARCH.md` §2.3 明确 `float[]` 不在白名单，而 `stretchRatioK` 标量彻底规避了它）。

#### 3.1.3 `getState()` / `loadState()` / `noStateLoaded()` 语义【必须】

- `getState()`：**返回内部 `state` 对象本身**，不新建、不复制。理由：`state` 是唯一持久化真相，拷贝会制造第二个真相源；平台只在保存时才读它。
- `loadState(State from)`：**逐字段**拷贝，**每个字段都必须容忍缺省与越界**（XML 可能是旧版本或手改过的）：
  - 引用类型 `null` → 保留 `State` 的初值（**不得**赋 null）。
  - `trailMode == null` → `SPRING`。
  - `glowLayers` → `clamp(1, 12)`；`cursorDisappearDelay` → `clamp(0, 500)`；`cursorFadeOutDuration` → `clamp(0, 1.0)`；`tailOpacity`/`glowOpacity`/`trailSize` → `clamp(0, 1)`；`shadowBlurFactor` → `clamp(0.05, 0.60)`；`stretchRatioK` → `clamp(1.0, 8.0)`。
  - 颜色串不合法（非 `#RRGGBB`）→ 用 `NeovideConfig` 的对应默认值（`NeovideColors.parse` 对非法串静默返回黑色，坏值不能进绘制路径）。
  - 末尾必须调 `migrateIfNeeded()`（§3.2）。**顺序**：先逐字段落地，再迁移（迁移只在 `stateVersion < 1` 时才覆盖两个布尔）。
- `noStateLoaded()`：首次安装 / XML 不存在时调用。语义：`state` 保持构造初值（= `NeovideConfig` 默认值）→ 调 `migrateIfNeeded()`（旧键若有值会覆盖两个布尔）→ 若迁移有变更则落盘。
- **时序保证【必须】**：`loadState`/`noStateLoaded` 在平台启动时执行，**必然早于**任何编辑器事件。因此 `NeovideCaretManager.editorCreated` 的首次回调**不再需要读磁盘**，只需一次纯字段同步（§3.1.5）。

#### 3.1.4 `enabled` 的统一裁决【必须】

- **删除** `NeovideCaretManager` 的 `private static volatile boolean enabled = true;`（`:50`）。
- 所有读点改用 `CONFIG.enabled`：`:130` `if (!enabled)`、`:123` `return enabled`。
- `setEnabled(boolean on)` 改为写 `CONFIG.enabled = on;`，并**新增一行落盘**：`NeovideCursorSettings.getInstance().update(s -> s.enabled = on)`（或等价的服务方法 + `persist()`）。
- 判据：`grep -n "static volatile boolean enabled" src/` → **0 命中**；`grep -n "CONFIG.enabled" src/` → ≥ 3 命中。

**理由**：State 必须持久化这个开关，而 `CONFIG` 是运行期唯一快照；保留独立 static 就会出现 "state.enabled / CONFIG.enabled / static enabled" 三处，正是 `SETTINGS-RESEARCH.md` §4.3-② 要根除的问题。

#### 3.1.5 与 `NeovideConfig` 的关系裁决（二选一：**保留**）【必须】

**裁决：`NeovideConfig` 保留为「运行期快照 + 默认值来源」的纯 POJO，不被 `NeovideCursorSettings` 取代。**

三条独立理由，任一成立即足：

1. **热路径零改动**：`CaretAnimator`（`:367-369`）、`TrailCorner`（`:45/60`）、`CaretTrailPanel`（`:39/115-116`）、`AfterimageTrail`（`:58/66-68`）都持有同一份 `NeovideConfig` 引用并**每帧**直接读字段。改成"每次读都问 service"会把每帧几十次字段读变成 service 调用，是与"零观感优化"相反的方向。
2. **parity harness 完全依赖它**：`tools/parity/PhysicsParity.java:73`、`tools/parity/GeometryParity.java:45`、`tools/TrailParityTest.java:75`、`tools/ime-probe/ImeGeomCriterionTest.java:203` 全部 `new NeovideConfig()` 并依赖其默认值。取代它就是让三件套失效。
3. **审计脚本只认这个文件**：`tools/parity/run-audit.ps1:32-45` 的 19 项字面量检查全部指向 `core/NeovideConfig.java`。把默认值搬走等于让审计变成瞎子（`LESSONS.md` 第三部分 3.3 的"假阴性"教训）。

**由此产生的机械约束【必须】**：`src/main/java/.../core/NeovideConfig.java` **本次改动必须零 diff**（`git diff --exit-code -- <该文件>` 必须返回 0）。所有新增行为都写在 `settings/` 包与 manager 里。

**两个方向的数据流【必须】**：

    持久化 → 运行期： loadState()/noStateLoaded() → State（唯一持久真相）
                         ↓  NeovideCaretManager 首次 editorCreated 时调用一次
                      syncStateToConfig()  —— 纯字段拷贝，无副作用
                         ↓
                      NeovideConfig CONFIG（运行期唯一快照）

    运行期 → 持久化：  UI apply() / Tools 菜单 → manager setter
                         ↓  先写 CONFIG（保持既有语义）
                      service State + 平台落盘

- `syncStateToConfig()`【必须】：把 State 的 15 个字段写进 `CONFIG`，并按 §2.5 重建 `CONFIG.trailFactors = new float[4]`（**新数组，不得把 State 的对象引用出去**）；`CONFIG.enabled = state.enabled`。**禁止**在该方法里调用任何 setter（它是启动期同步，`savedCaretColor` 等副作用状态此时还不该动）。
- `persist()`【必须】：只写 State 内存，落盘由平台负责（`PersistentStateComponent` 的既有机制）；**不得**手写 XML 文件。

#### 3.1.6 `plugin.xml` 增量（**在现有 `<extensions>` 内追加，不改已有条目**）

    <extensions defaultExtensionNs="com.intellij">
        <!-- 既有，勿动 -->
        <editorFactoryListener implementation="...ui.NeovideCaretManager"/>

        <!-- 新增：持久化服务（application 级） -->
        <applicationService
            serviceImplementation="com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings"/>

        <!-- 新增：设置页（parentId=tools：第三方插件设置的约定位置） -->
        <applicationConfigurable
            parentId="tools"
            id="com.github.yyyolo7a79.neovidecursor.settings"
            displayName="Neovide Cursor"
            instance="com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable"/>
    </extensions>

**要点【必须】**：
- `displayName` 必须写在 XML 里（`Configurable.getDisplayNameFast` 会优先读它；写不全平台为了取名字不得不先实例化类，`SETTINGS-RESEARCH.md` §2.4 原文警告 "degrades UI responsiveness"）。
- `id` 必须是插件 id 前缀（官方原话 "The FQN should be based on the plugin id"）。
- `src/main/resources/META-INF/plugin.xml` 里**没有** `<idea-version>` 是**正确的** —— 它由 `build/offline-build.ps1:52-63` 在构建期注入（`SETTINGS-RESEARCH.md` §4.3-④ 已查明）。**不许**手工往源码 xml 里加 `<version>`/`<idea-version>`。

#### 3.1.7 面板类的骨架契约【必须】

    public class NeovideCursorConfigurable extends BoundConfigurable
            implements SearchableConfigurable {

        private NeovideCursorSettings.State pending;   // 预览副本

        public NeovideCursorConfigurable() { super("Neovide Cursor", null); }  // ① 无参

        @Override public String getId() { return "com.github.yyyolo7a79.neovidecursor.settings"; }

        @Override public DialogPanel createPanel() { /* ② 纯 DSL 构建，禁止依赖项目/编辑器 */ }

        @Override public void reset() { /* ③ 先刷新 pending，再 super.reset() */ }

        @Override public void apply() { /* ④ §3.3 的 S0..S8 */ }

        @Override public boolean isModified() { /* ⑤ super.isModified() || 非绑定控件有变 */ }
    }

- **实际落点（v2 更正）**：面板类放在 `ui/settings/` 包、类名 `NeovideCursorConfigurable`，
  **不是** v1 写的 `settings/NeovideSettingsConfigurable`。理由：① 包级职责划分 —— `settings/` 放持久化服务，
  `ui/settings/` 放 UI；② 类名与插件名 `NeovideCursor` 一致，更贴合官方「id 基于插件 id」的建议。
  `plugin.xml` 的 `instance` 与 jar 内真实 class 已自洽（t4 验证过）。
- **构造器是两个 String**【必须】：`BoundConfigurable(String displayName, String helpTopic)`（`javap` 实测；官方 Kotlin 示例是单参数，照抄会编译失败 —— `SETTINGS-RESEARCH.md` §2.0 已踩过）。
- **必须无参构造**（application 级 Configurable 的官方契约），**且构造器里不得创建 Swing 组件**（平台可能在线程上实例化它）。所有 UI 都放 `createPanel()`。
- `createPanel()` **必须能在无项目、无编辑器的情况下跑通**（`buildSearchableOptions` / `TraverseUIStarter` 会实例化它）。
- `SearchableConfigurable` 的 `enableSearch(String)` 用默认实现即可（可后补）。
- `cancel()`【必须】**不实现**（保留 `UnnamedConfigurable` 的空默认）。理由：第一期是"OK 生效"（`SETTINGS-RESEARCH.md` §2.5 的建议），运行期状态在 `apply()` 之前**完全没被改动**，没有东西需要回滚。这也是**禁止用 `onChanged` 做实时预览**的直接后果（`onChanged` 带 `@ApiStatus.NonExtendable` 且官方文档无背书）。
- `isModified()`【必须】：`ColorPanel` 这类非 DSL 绑定控件不会被 `DialogPanel` 追踪，必须在 `isModified()` 里显式比较其当前值与 `pending` 里的 HEX 串；与 `super.isModified()` 取逻辑或。

### 3.2 ② PropertiesComponent 两个旧键的一次性迁移

#### 3.2.1 旧键（**逐字**）

| 常量 | 键名 | 默认值（现 `:350` / `:356-357`） |
|---|---|---|
| `OLD_KEY_ALWAYS_HIGH_FPS` | `neovide.cursor.alwaysHighFps` | `false` ← **bug，见 §4** |
| `OLD_KEY_OVERLAY_FADE_OUT` | `neovide.cursor.overlayFadeOut` | `true` |

#### 3.2.2 算法（**按此顺序实现**）

    migrateIfNeeded():
      if (state.stateVersion >= 1) {                      // 分支 B：新 state 已存在且已迁移
          deleteOldKeysIfPresent();                       //   只删不读（防降级-再升级后旧键复活）
          return;
      }
      boolean changed = false;
      PropertiesComponent pc = tryGetPropertiesComponent();   // 失败 → 直接跳到版本号处理
      if (pc != null) {
          // 分支 A-1：旧键存在 → 旧键优先，写进 state
          if (pc.isValueSet(OLD_KEY_ALWAYS_HIGH_FPS)) {
              state.alwaysHighFps = pc.getBoolean(OLD_KEY_ALWAYS_HIGH_FPS, true);   // 注意 default=true
              changed = true;
          }
          // 分支 A-2：旧键不存在 → state 保留 loadState/构造给出的值（= NeovideConfig 默认 true / XML 现值）
          if (pc.isValueSet(OLD_KEY_OVERLAY_FADE_OUT)) {
              state.overlayFadeOut = pc.getBoolean(OLD_KEY_OVERLAY_FADE_OUT, true);
              changed = true;
          }
      }
      state.stateVersion = 1;
      PersistResult persisted = persistQuietly();         // 三态：CONFIRMED / DEFERRED / FAILED（v2）
      switch (persisted) {
        case CONFIRMED: deleteOldKeysIfPresent(); break;  // 已确认落盘 → 唯一允许删键的分支
        case DEFERRED:  /* 版本号保持 1，不删键；删键推迟到延迟回调 saveSettings 成功后 */ break;
        case FAILED:    state.stateVersion = 0; break;     // 下次启动重试（绝不半途而废）
      }

> **v2 为什么从 boolean 改成三态**（t5 审查 R-03）：非 EDT 时「已排队待刷盘」与「真失败」对
> **是否删旧键**、**是否回退版本号**的要求正好相反 —— 排队中不该删键（未确认）但也不该回退
> 版本号（内存值是对的，回退会导致永不收敛）；真失败才回退并保留旧键。一个 boolean 装不下
> 这两种语义，v1 会出现「日志说保留旧键、几毫秒后回调却把它删了，且 stateVersion 永远停在 0」。
> 实现见 `NeovideCursorSettings.java` 的 `PersistResult` 枚举与 `migrateIfNeeded()` 的 switch。

#### 3.2.3 两个分支的行为定义【必须】

| 分支 | 条件 | `alwaysHighFps` 取值 | `overlayFadeOut` 取值 | 旧键处理 |
|---|---|---|---|---|
| **A-1 旧键存在** | `isValueSet(KEY)` | 旧键的值（用户存过的 `false` **必须保留**） | 旧键的值 | 迁移落盘成功后 `unsetValue(KEY)` |
| **A-2 旧键不存在** | `!isValueSet(KEY)` | `NeovideConfig.alwaysHighFps` 的编译期默认 `true`（**不是** `false`） | `true` | 无键可删，跳过 |
| **B 新 state 已存在** | `stateVersion >= 1` | 新 state 优先；**旧键一律不读** | 同左 | 若旧键仍在（用户降级后再升级），直接 `unsetValue` |

**"避免三真相源"的可机械判据【必须】**
1. `grep -rn "PropertiesComponent" src/` 只允许出现在新 `settings/` 包**一个**文件里，且只出现在迁移与删键两处；**不得**出现在 `NeovideCaretManager`（判据：`grep -c "PropertiesComponent" src/main/java/.../ui/NeovideCaretManager.java` → **0**）。
2. `grep -rn "neovide.cursor." src/` 只允许 `settings/` 包内出现两个旧键常量。
3. 迁移完成后 `PropertiesComponent` 里两个键都必须不存在（加一条启动期 `LOG.info` 记录迁移结果，便于事后取证）。

#### 3.2.4 迁移期"不丢用户已存值"的具体保证【必须】

- `alwaysHighFps`：老用户若点过菜单关掉它（值 `false`），迁移必须搬 `false`；**只有**"从未存过"的干净安装才落 `true`。这正是 `isValueSet` 存在的意义 —— 不能用 `getBoolean(KEY, false)` 一把梭。
- `overlayFadeOut`：老用户若关过，搬 `false`；从未存过则 `true`（与 `NeovideConfig` 默认一致）。
- 迁移失败（落盘抛异常）时**不删旧键、不置版本号**，下次启动重试 —— 宁可多迁移一次，不可丢值。

### 3.3 ③ `NeovideCaretManager.applyConfig()` 编排

#### 3.3.1 签名与定位【必须】

    /** 把 NeovideCursorSettings 的 State（已是最新值）编排成"写字段 → 调 setter → 刷新缓存"的完整生效链。 */
    public static void applyConfig()

它**不是**"再写一遍字段"的便利方法，而是**唯一的全局生效入口**：面板 `apply()`、以及以后任何"批量改配置"都必须走它。已有单点 setter（`setTrailMode` 等）继续为 Tools 菜单服务，**语义不变**。

#### 3.3.2 精确调用顺序（**顺序即契约**）

    S0  取 service 的 state（已由面板 apply() 写入）；记 old = 当前 CONFIG 的关键值快照
    S1  syncStateToConfig()                    // 15 个字段一次性写到位 + 重建 trailFactors
    S2  if (changed(trailMode))        setTrailMode(state.trailMode)
    S3  if (changed(hideNativeCaret))  setHideNativeCaret(state.hideNativeCaret)
    S4  if (changed(alwaysHighFps))    setAlwaysHighFps(state.alwaysHighFps)
    S5  if (changed(overlayFadeOut))   setOverlayFadeOut(state.overlayFadeOut)
    S6  if (changed(enabled))          setEnabled(state.enabled)
    S7  for (CaretAnimator a : ANIMATORS.values()) {
            a.onModeChanged();        // refreshStyles + setLifetime + forceResync
            a.refreshGlowPadding();   // ★ v2 补充：兜住"光标静止 / 静态光标编辑器"
        }
    S8  for (CaretAnimator a : ANIMATORS.values()) a.requestWakeUp();

**每一步的理由（裁决时必须逐条对照）**

| 步 | 为什么必须在这 | 反例（顺序错了会怎样） |
|---|---|---|
| **S1** | 后面所有 setter 与刷新都读 `CONFIG`。`setEnabled(true)` 内部要在 `:371` 读 `CONFIG.hideNativeCaret`；`onModeChanged` 要读 `shadowBlurFactor`/`afterimageLifetime`/颜色 | 先调 setter 再写字段 → 新装的 animator 用旧字段构造，`glowPadding` 还要等下一次慢路径 |
| **S2** | 结构刷新最早做：`setTrailMode` 无条件遍历 `ANIMATORS` 调 `onModeChanged()`（`:250-255`），会 `clear()` 残影、重设 lifetime、清角点、`refreshStyles()`、`forceResync=true`、`wakeUp()` | 放到 S7 之后就白做一次；放到 S6 之后，被 dispose 的 animator 已不在集合里 |
| **S3** | `setHideNativeCaret` 有**全局配色副作用**并 `clearTrail()`（`:276-287`），必须在任何会重装/销毁 animator 的操作**之前** | 放到 S6 之后：`setEnabled(false)` 调 `restoreNativeCaret()`（`:381`）把刚隐藏的光标又恢复 → 开关与画面不一致 |
| **S4** | `requestWakeUp()` 只重排调度（`:324-336`），无结构副作用，顺序上无强约束；放在 S5 前让"调度档位"先就位 | 放最后也可 —— 但**必须在 S6 之前**，否则新建的 animator 首帧仍按旧档位排队 |
| **S5** | `setOverlayFadeOut` 落盘 + `requestWakeUp()`（`:300-311`） | 同上 |
| **S6** | **必须最后**：开 → 对所有编辑器 `install()`（`:377-379`），新 `CaretAnimator` 在构造器里一次性算 `glowPadding`（`:388`）并 `setLifetime`（`:383`），所以必须在字段就位之后；关 → `dispose` 全部并 `ANIMATORS.clear()`（`:382-385`），之后 S7/S8 空转无害 | 放到 S1 之前：新 animator 用旧字段构造 |
| **S7** | 覆盖 S2..S6 都没碰的缓存：`glowPadding`、`fillColor/glowColor`、`AfterimageTrail.lifetime`。`onModeChanged()`（`:1353-1368`）是**现成的全量刷新入口**，副作用恰好够用 | 不做 S7 → 改颜色/改 `shadowBlurFactor` 后画面不变（`SETTINGS-RESEARCH.md` §4.3-③ 的"坏消息"） |
| **S8** | S7 内部已 `wakeUp()` 一次；S8 是无害加固（`ANIMATORS` 可能很大，`requestWakeUp` 只置 `nextDueNanos` 并打断共享线程，见 `CaretAnimator:545-547`） | —— |

**幂等性要求【必须】**：`applyConfig()` 连调两次，第二次的 S2–S6 **全部跳过**（`changed()` 全 false），S7/S8 仍执行（幂等）。验收时用两次调用的副作用日志对比。

#### 3.3.3 三类"构造时算一次 / 缓存"的值：刷新依据与**可验证判据**

| 缓存值 | 缓存位置 | 谁负责刷新 | 为什么这样能刷到 | **可验证判据** |
|---|---|---|---|---|
| **`glowPadding`** | `CaretAnimator.java:203`（字段）；构造时 `:388` 算一次；之后在 `refreshTarget()` **慢路径** `:1018-1019` 更新 | S7 **两步**：① `onModeChanged()` → 置 `forceResync = true`（`:1365`）→ 下一次 tick 的 `refreshTarget()` **绕过**"视觉位置没变就返回"的快速路径（`:871` 的判据含 `!forceResync`）；② 紧接着 `refreshGlowPadding()` 直接按当前 `shadowBlurFactor` 重算一次 | **单靠 ① 不够**（v1 曾断言"`forceResync` 一置位就必走慢路径"，**该断言已被证伪**）：慢路径在算 padding 之前还有一条早退 —— `if (newCenterX == centerX && newCenterY == centerY && height == cursorHeight) return false;`（`CaretAnimator:1007`），**光标静止时它恒成立**；静态光标编辑器（控制台）更是整体跳过 `refreshTarget`。所以真实保证是**两步**：① 管"光标在动"的场景，② 兜住"光标静止 / 静态光标编辑器"。**这正是 R-05 当初产生的根源** | 用与 `tools/ime-probe/ImeGeomCriterionTest.java:203-207` 相同的反射手法构造 `CaretAnimator`（假 `Editor` 返回固定 lineHeight=32）：① `shadowBlurFactor = 0.2f`，tick 一帧，反射读 `glowPadding` == **11**（`ceil(max(3, 0.2*32)) + 4 = 7+4`）；② 改成 `0.6f` 并调 `applyConfig()`，**不再 tick**，直接断言 == **24**（`ceil(19.2)+4 = 20+4`）—— 这一步专门证明 `refreshGlowPadding()` 在光标静止时也生效；③ 断言 `forceResync` 已被消费（tick 后为 false），证明慢路径那条路也仍然工作 |
| **`CaretTrailPanel.fillColor` / `glowColor`** | `CaretTrailPanel.java:77-78`（字段）；构造时经 `refreshStyles()`（`:118`）由 `NeovideColors.parse` 算一次 | S7 `onModeChanged()` → `panel.refreshStyles()`（`:1362`）→ `:122-125` 重新 `parse` | 这两个 `Color` 是**解析结果的快照**，不解析就永远是旧色；`drawQuadGlow`/`fillPolygon` 只读字段 | 反射读 `fillColor`/`glowColor`：设 `tailColor="#00FF00"` + `tailOpacity=0.5f` → `fillColor` 必须是 `(0,255,0,128)`（`NeovideColors.java:53-54`：`round(1.0*0.5*255)=128`），`glowColor` alpha 必须是 **255**（`:124` 硬编码 1.0f）。改前是 `#FFC0CB`，改后必须变 |
| **`AfterimageTrail.lifetime`** | `AfterimageTrail.java:61`（`private double lifetime = 0.15` 的**局部默认**，比 config 的 0.22 更小）；`CaretAnimator` 构造时 `:383` 用 config 覆盖；之后**只有** `onModeChanged()` 的 `:1355` 会再覆盖 | S7 `onModeChanged()` → `:1355` `setLifetime(config.afterimageLifetime)` | `collectVisible`/`alphaOf`/`record` 用的都是这个字段（`:113/130/147`），无人每帧读 config | 反射调 `getLifetime()`：`config.afterimageLifetime = 0.22f` 时必须是 **0.22**（`setLifetime` 有 `max(0.02, ...)` 下限，`:172`）；把 config 改成 0.5f 且**不**调 `onModeChanged` 时仍是 0.22（证明它不会自愈），调完变 0.5 |

**反例（用来证明 S7 真的必要）【必须】**：验收报告里要给出"**跳过 S7** 时上面三条判据全部失败"的记录。这是把 `SETTINGS-RESEARCH.md` §4.3-③ 的"❌ 需要主动触发刷新"从叙述变成证据。


#### 3.3.4 N-1 裁决：逐格拖动时 `glowPadding` 最多滞后 1 px（**第一期不改代码**）

> 来源：t8 复审 N-1。reviewer 用真实 `CaretTrailPanel` 实测：`sbf` 0.20→pad 11、**0.21→仍 11**、
> 0.22→12、**0.23→仍 12**。本条按「**不改代码，写入契约**」处置，理由与实测证据如下。

**成因**：`CaretTrailPanel.java:138` 有一条 0.5 px 死区 ——

    if (Math.abs(newWidth - glowWidth) > 0.5f) { glowWidth = newWidth; }

面板 slider 步长 0.01，单步 `Δ = 0.01 × 32 = 0.32 px < 0.5`，所以每隔一步才跟一次。

**定量测量**（探针 `build/settings-probe/GlowRadiusProbe.java`，驱动真实 `CaretTrailPanel`，10 项全过）：

| 项 | 实测 |
|---|---|
| 全量程 0.20~0.60 逐格拖动，出现滞后的刻度 | 29 / 35 / 41 / 47 / 51 / 57（共 6 格） |
| **最大 `padding` 滞后** | **1 px**（`ceil()` 是阶梯函数，最多差一档） |
| reviewer 四个点复核 | 0.20→11、0.21→11、0.22→12、0.23→12 —— **完全复现** |
| 单调性 | `sbf` 单调递增时 `padding` 单调不减（不倒退） |
| 端点 | 0.2→11、0.6→24（与 §3.3.3 断言一致） |
| 固定输入连调 10000 次，`glowWidth` 变化次数 | **0**（无振荡、无累积漂移） |

**为什么判「不改」而不是「改死区」**：

1. **不是本批回归**。基线 `8906609` 的 `CaretTrailPanel:135-141` 就是同一条判据，本批对该文件
   **numstat 为空**（完全未改）。它只是被新 UI 的高分辨率滑块**暴露**出来，而非引入。
2. **影响可忽略且自愈**。最大滞后 1 px，落在重绘包围盒余量里；而 `updateGlowRadius()` 在
   `CaretAnimator:1018` 的**每次光标移动慢路径**都会调用 —— 用户一移动光标就立刻纠正。
   换句话说，滞后只存在于『拖完滑块、还没动光标』这一小段静止时间里。
3. **改它属于「没验证的性能改动」**。虽然探针已证明去掉死区不会振荡（`newWidth` 是
   `(shadowBlurFactor, maxDim)` 的**纯函数**，重复赋值不可能来回跳；即使 `maxDim` 有 1e-7 级抖动，
   `ceil()` 后的 padding 也稳定），但那是**观感绘制路径**的代码，且本轮 `src/` 不在集成交付的
   inScope 内。按本批「不改观感常量、不做没验证的性能改动」的原则，留到第二期单独评估。

**给第二期的决策依据（已就绪，无需重测）**：若届时决定改，把 `> 0.5f` 收紧到 `>= 0.01f`
（远小于真实 `maxDim` 抖动）即可；探针 E2 已证明它不会引入每帧反复重算。
**不在上表、也不需要显式刷新的字段**（每帧或每次移动都直接读 `config`，证据是 `SETTINGS-RESEARCH.md` §4.3-③ 前两行表）：`glowLayers`/`glowOpacity`/`useShadow`/`tailColor`/`tailOpacity`/`shadowColor`（每次绘制读）、`overlayFadeOut`+`cursorDisappearDelay`+`cursorFadeOutDuration`（`glowFade()` 每帧）、`trailMode`（`:672` 每帧）、`trailSize`/`trailFactors`（下一次光标移动时）。**因此 §2 表里这些字段的热改只需 S1（写字段）即可生效** —— 但 S7 仍统一执行（幂等、只在 apply 时一次）。

### 3.4 面板 `apply()` 与 `reset()` 的精确流程【必须】

    apply():
      A1  非绑定控件回读：把 ColorPanel.getSelectedColor() 转成 "#RRGGBB" 写进 pending
      A2  super.apply();                       // DialogPanel 把绑定的 checkbox/slider/combo 值写进 pending
      A3  service.getState() ← pending 的 15 个字段（含 stretchRatioK）；
          若"拉伸强度"滑块刻度自 reset() 起未变 → 跳过 trailFactors 相关写入（§2.5）
      A4  service.persistQuietly();
      A5  NeovideCaretManager.applyConfig();   // §3.3

    reset():
      R1  pending = deepCopy(service.getState());
      R2  super.reset();                       // DialogPanel 从 pending 重绘
      R3  非绑定控件重填（ColorPanel.setSelectedColor(...)）+ 所有派生标签重算（§2.4）
      R4  setEnabledByPredicates();            // 联动置灰（§2.3）

**禁止**：在 `onChanged`/`ActionListener` 里直接改 `CONFIG` 或调 `applyConfig()`（第一期不做实时预览，见 §0.2 与 `SETTINGS-RESEARCH.md` §2.5）。

---

## 4. 顺手要修的 bug：`alwaysHighFps` 兜底 `false`

### 4.1 缺陷陈述（**已实测确认**）

| 位置 | 现在写的 | 应该是 |
|---|---|---|
| `src/main/java/.../core/NeovideConfig.java:98` | `public boolean alwaysHighFps = true;` | 不变（**它就是对的**） |
| `src/main/java/.../ui/NeovideCaretManager.java:349-350` | `.getBoolean(PROP_ALWAYS_HIGH_FPS, false)` | **默认值必须是 `true`**（或直接删掉这段读取，由 §3.2 迁移接管） |

**后果**：干净安装 + 从未点过 Tools 菜单的用户，运行期实际是 `alwaysHighFps = false`，与文档宣称的默认 `true` 相反。`LESSONS.md` 坑 8 记录过"手测发现不了"的原因 —— 本机持久化成了 `true`，只有干净安装的用户才会踩到（`LESSONS.md:237-238`）。

### 4.2 修法（三选一，推荐第 1 条）

| 方案 | 做法 | 判据 | 评价 |
|---|---|---|---|
| **1【必须采纳】** | **删除** `restorePersistedSettings()` 整个方法（`:347-361`）及其调用点（`:145`），改为 `NeovideCaretManager` 首次 `editorCreated` 时调 `NeovideCursorSettings.getInstance().syncStateToConfig()`；默认值由 `State` 字段初值（= `NeovideConfig` 默认 `true`）与 §3.2 迁移共同保证 | `grep -c "restorePersistedSettings" src/` → **0** | 从根上消灭"两个默认值"，且与 §3.2 的迁移天然一体 |
| 2 | 保留方法名，把 `:350` 的 `false` 改成 `CONFIG.alwaysHighFps`（即 `NeovideConfig` 的现值） | diff 只有 1 行 | 治标：将来再有人改 `NeovideConfig` 默认值，这里又对不上 |
| 3 | 保留方法名，把 `:350` 改成字面量 `true` | diff 只有 1 行 | **不采纳**：又制造第二个 "true"，与方案 2 同样脆，且违反"默认值只有一处" |

### 4.3 迁移期"不丢用户已存值"【必须】

1. 只要 `isValueSet(OLD_KEY_ALWAYS_HIGH_FPS)` 为真，就**无条件**搬旧值（用户点过菜单关掉 → `false` 必须保留）。
2. 只有"从未存过"才落 `true`。等价写法 `pc.getBoolean(KEY, true)` 但**必须**与 `isValueSet` 配合，否则无法在日志里区分"存过 true"与"没存过"（事后取证要用）。
3. 迁移写入新 state 并成功落盘**之后**才删旧键（§3.2.2）。
4. 修 bug 前后必须给出**行为对照**：干净安装（无 XML、无旧键）下 `isAlwaysHighFps()` 从 `false` → `true`；老用户关过（旧键 `false`）迁移后仍为 `false`。这两条是验收项。

---

## 5. 禁止改默认值清单（可机械校验）

### 5.1 `core/NeovideConfig.java`（**整个文件零 diff**）

【禁止】本次改动对 `NeovideConfig.java` 的任何字符修改（含格式、注释、换行）。判据 D0（**最强**）：

    git -C D:\deepseekwork\neovim-cursor-pr diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
    # 期望：exit code 0，无输出

下表是"即使有人绕过 D0 也必须成立**字面量**"清单（审查方按 `SimpleMatch` 逐行核）：

| # | 行 | 期望字面量（逐字） |
|---|---|---|
| 1 | 14 | `public String tailColor = "#FFC0CB";` |
| 2 | 17 | `public float tailOpacity = 1.0f;` |
| 3 | 20 | `public boolean useShadow = true;` |
| 4 | 23 | `public String shadowColor = "#FFC0CB";` |
| 5 | 38 | `public float shadowBlurFactor = 0.2f;` |
| 6 | 59 | `public int glowLayers = 8;` |
| 7 | 65 | `public float glowOpacity = 0.55f;` |
| 8 | 98 | `public boolean alwaysHighFps = true;` |
| 9 | 101 | `public float animationLength = 0.1f;` |
| 10 | 119 | `public float shortAnimationLength = 0.11f;` |
| 11 | 133 | `public float trailSize = 1f;` |
| 12 | 162 | `public float[] trailFactors = {1.0f, 0.72f, 0.42f, 0.22f};` |
| 13 | 165 | `public float shortMoveThreshold = 8f;` |
| 14 | 187 | `public float typingMaxChars = 3f;` |
| 15 | 190 | `public float animationResetThreshold = 0.09f;` |
| 16 | 207 | `public boolean overlayFadeOut = true;` |
| 17 | 215 | `public int cursorDisappearDelay = 50;` |
| 18 | 223 | `public float cursorFadeOutDuration = 0.075f;` |
| 19 | 238 | `public float maxTrailDistanceFactor = 60f;` |
| 20 | 241 | `public float cursorWidth = 2f;` |
| 21 | 264 | `public boolean hideNativeCaret = true;` |
| 22 | 267 | `public boolean enabled = true;` |
| 23 | 272 | `public TrailMode trailMode = TrailMode.SPRING;` |
| 24 | 278 | `public float afterimageLifetime = 0.22f;` |

### 5.2 调度 / 时序常量（在别的文件里，同样禁改）

| # | 文件:行 | 期望字面量 |
|---|---|---|
| 25 | `ui/CaretAnimator.java:77` | `static final int ACTIVE_DELAY_MS = 8;` |
| 26 | `ui/CaretAnimator.java:80` | `private static final int IDLE_DELAY_MS = 120;` |
| 27 | `ui/CaretAnimator.java:83` | `private static final int IDLE_UNFOCUSED_DELAY_MS = 600;` |
| 28 | `ui/CaretAnimator.java:86` | `private static final int MAX_ADAPTIVE_DELAY_MS = 40;` |
| 29 | `ui/CaretAnimator.java:101` | `private static final double MAX_DT = 1.0 / 20;` |
| 30 | `ui/CaretAnimator.java:115` | `private static final double RECOVERY_GAP_SECONDS = 0.1;` |
| 31 | `ui/CaretAnimator.java:165` | `private static final int IME_MAX_SKIP_FRAMES = 15;` |
| 32 | `ui/RenderScheduler.java:40` | `private static final long MAX_SLEEP_MS = 600;` |
| 33 | `ui/RenderScheduler.java:46` | `private static final long DUE_TOLERANCE_NANOS = 1_000_000L;` |
| 34 | `ui/RenderScheduler.java:55` | `private static final long BUSY_RETRY_MS = 1;` |
| 35 | `core/AfterimageTrail.java:24` | `private static final int MAX_GHOSTS = 48;` |

### 5.3 一键校验脚本（**必须原样可跑**）

    $root = 'D:\deepseekwork\neovim-cursor-pr\src\main\java\com\github\yyyolo7a79\neovidecursor'
    $checks = @(
      @("$root/core/NeovideConfig.java",   'public String tailColor = "#FFC0CB";'),
      @("$root/core/NeovideConfig.java",   'public float tailOpacity = 1.0f;'),
      @("$root/core/NeovideConfig.java",   'public boolean useShadow = true;'),
      @("$root/core/NeovideConfig.java",   'public String shadowColor = "#FFC0CB";'),
      @("$root/core/NeovideConfig.java",   'public float shadowBlurFactor = 0.2f;'),
      @("$root/core/NeovideConfig.java",   'public int glowLayers = 8;'),
      @("$root/core/NeovideConfig.java",   'public float glowOpacity = 0.55f;'),
      @("$root/core/NeovideConfig.java",   'public boolean alwaysHighFps = true;'),
      @("$root/core/NeovideConfig.java",   'public float animationLength = 0.1f;'),
      @("$root/core/NeovideConfig.java",   'public float shortAnimationLength = 0.11f;'),
      @("$root/core/NeovideConfig.java",   'public float trailSize = 1f;'),
      @("$root/core/NeovideConfig.java",   'public float[] trailFactors = {1.0f, 0.72f, 0.42f, 0.22f};'),
      @("$root/core/NeovideConfig.java",   'public float shortMoveThreshold = 8f;'),
      @("$root/core/NeovideConfig.java",   'public float typingMaxChars = 3f;'),
      @("$root/core/NeovideConfig.java",   'public float animationResetThreshold = 0.09f;'),
      @("$root/core/NeovideConfig.java",   'public boolean overlayFadeOut = true;'),
      @("$root/core/NeovideConfig.java",   'public int cursorDisappearDelay = 50;'),
      @("$root/core/NeovideConfig.java",   'public float cursorFadeOutDuration = 0.075f;'),
      @("$root/core/NeovideConfig.java",   'public float maxTrailDistanceFactor = 60f;'),
      @("$root/core/NeovideConfig.java",   'public float cursorWidth = 2f;'),
      @("$root/core/NeovideConfig.java",   'public boolean hideNativeCaret = true;'),
      @("$root/core/NeovideConfig.java",   'public boolean enabled = true;'),
      @("$root/core/NeovideConfig.java",   'public TrailMode trailMode = TrailMode.SPRING;'),
      @("$root/core/NeovideConfig.java",   'public float afterimageLifetime = 0.22f;'),
      @("$root/ui/CaretAnimator.java",     'static final int ACTIVE_DELAY_MS = 8;'),
      @("$root/ui/CaretAnimator.java",     'private static final int IDLE_DELAY_MS = 120;'),
      @("$root/ui/CaretAnimator.java",     'private static final int IDLE_UNFOCUSED_DELAY_MS = 600;'),
      @("$root/ui/CaretAnimator.java",     'private static final int MAX_ADAPTIVE_DELAY_MS = 40;'),
      @("$root/ui/CaretAnimator.java",     'private static final double MAX_DT = 1.0 / 20;'),
      @("$root/ui/CaretAnimator.java",     'private static final double RECOVERY_GAP_SECONDS = 0.1;'),
      @("$root/ui/CaretAnimator.java",     'private static final int IME_MAX_SKIP_FRAMES = 15;'),
      @("$root/ui/RenderScheduler.java",   'private static final long MAX_SLEEP_MS = 600;'),
      @("$root/ui/RenderScheduler.java",   'private static final long DUE_TOLERANCE_NANOS = 1_000_000L;'),
      @("$root/ui/RenderScheduler.java",   'private static final long BUSY_RETRY_MS = 1;'),
      @("$root/core/AfterimageTrail.java", 'private static final int MAX_GHOSTS = 48;')
    )
    $fail = 0
    foreach ($c in $checks) {
        if (-not (Select-String -Path $c[0] -SimpleMatch -Pattern $c[1])) {
            $fail++
            "MISSING  $($c[0])  ::  $($c[1])"
        }
    }
    "禁改项缺失数 = $fail   （期望 0）"

> 注意写法：**逐文件 `Select-String -Path <单个文件>`**，**不要**用 `-Path <目录> -Include *.java` —— 那个写法在 pwsh 7 下恒返回 0 命中（假阴性），`LESSONS.md` 第三部分 3.3 有实测表。

### 5.4 三件套的期望值（**硬门槛**）

| 命令 | 期望 |
|---|---|
| `tools/parity/run-parity.ps1` | `PhysicsParity` / `GeometryParity` / `TrailParityTest` **三份逐字节一致**（`PARITY OK` × 3，`ALL PARITY OK`） |
| `tools/parity/run-audit.ps1` | A 段 19 项里 **DIFF 数 == 1**，且**那一项必须是 `hideNativeCaret = (true\|false)`**；其余 18 项必须 `SAME` |
| `tools/ime-probe/run-ime-geom-test.ps1` | **29 项全过** |

**注意**：`run-audit.ps1` 的 D 段（死字段 grep）与 E 段（调度结构）不受本契约影响，**不新增 DIFF**；若出现新的 DIFF，说明改到了不该改的文件。

---

## 6. 第一期不做 / 永远不做成 UI

### 6.1 第一期**明确不做**（第二期候选）

| 字段 | 默认值（不变） | 为什么不进第一期 | 证据 |
|---|---|---|---|
| `animationLength` | `0.1f` | 与 `trailSize` 相乘决定跨行拖尾（`leading = animationLength × (1 - trailSize)`）。单独调它而不同时显示 `trailSize` 的效果，用户会得到"调了没反应/反应怪异"；且上界必须与 `MAX_DT`/`RECOVERY_GAP_SECONDS` 一起解释，第一期不承担 | `TrailCorner.java:227-228`；`CaretAnimator.java:101/115` |
| `shortAnimationLength` | `0.11f` | 与 `trailFactors` **相乘**决定四角绝对时长（110/79/46/24ms）。0.11 是实测调出来的；放开它等于把"不要凭直觉调参数"的坑交给用户。它与 #16 复合滑块是同一组耦合，第一期只开放"比值"这一维 | `TrailCorner.java:220-221`；`NeovideConfig.java:149-157` |
| `trailFactors` **裸四滑块** | `{1.0,0.72,0.42,0.22}` | 四个数可被调成非单调 → 产生扭曲四边形（视觉 bug）；且 rank 方向极易调反（index0 才是"最拖"）。第一期只给"一个安全旋钮"（§2.5） | `SETTINGS-RESEARCH.md` §3.2 方案 A/B；`TrailCorner.java:136-137` |
| `typingMaxChars` | `3f` | 它改变**短跳/跨行分支的切换点**，观感突变（拉伸幅度跳到另一档）。改完必须"连续打字/删除验证"，属第二期的联动验证课题 | `TrailCorner.java:201`；`SETTINGS-RESEARCH.md` §4.1 |
| `maxTrailDistanceFactor` | `60f` | 它只做**限幅**（常规移动完全无影响），且与上游 README 的 "4" 矛盾（issue #7）—— UI 上的说明目前**没有权威依据**。先解决 issue 再做 UI | `TrailCorner.java:306`；`START-HERE.md` §5 issue #7 |
| `cursorWidth` | `2f` | 它不只是粗细：短跳归一化、方向对齐、最大拖尾长度**全部以它为基准**（`jumpX = deltaXpx / width`），改它会按比例缩放所有几何尺度 | `TrailCorner.java:204/306`；`SETTINGS-RESEARCH.md` §4.2 耦合 5 |
| `afterimageLifetime` | `0.22f` | 只在 `trailMode == AFTERIMAGE` 生效，需要"按模式整组置灰"的联动框架；第一期只做了 §2.3 的 4 / 2 / 2 三项置灰，扩到"整组"属第二期 | `CaretAnimator.java:672`；`SETTINGS-RESEARCH.md` §4.2 耦合 9 |

### 6.2 **永远不做成 UI**

| 字段 / 常量 | 位置 | 理由（含证据） |
|---|---|---|
| `animationResetThreshold` | `core/NeovideConfig.java:190` | **死字段**：全仓 `grep` 只在两条注释里出现（`TrailCorner.java:259/268`），**代码零读取**。做进 UI 就是骗用户 —— 应删除字段而不是给它做控件 |
| `shortMoveThreshold` | `core/NeovideConfig.java:165` | **半死**：只在"拿不到字符偏移"的兜底分支生效（`TrailCorner.java:206`），正常使用中**永不触发**（正常路径走 `typingMaxChars`，`:201`）。做成 UI 只会让用户调了没反应 |
| `enabled` 作为**独立的 config 字段** | `core/NeovideConfig.java:267` | `CONFIG.enabled` 现在**零读取**（`grep` 0 命中）。第一期把它与 `NeovideCaretManager.enabled` **统一成一个**布尔（§3.1.4），面板上**只允许出现一个**"启用"控件。**禁止**出现第二个绑定到 `enabled` 的控件（判据：面板源码里 `enabled` 作为绑定目标只出现 1 次） |
| `ACTIVE_DELAY_MS` | `ui/CaretAnimator.java:77` | `START-HERE.md` §2 与 `NeovideConfig` 注释都写明"**不要改**"（12ms 会"傻快"，用户实测）。它是内部调度常量，不是用户偏好 |
| `IDLE_DELAY_MS` | `ui/CaretAnimator.java:80` | 空闲降频档；暴露它等于让用户手搓调度策略，与 `alwaysHighFps` 这个"唯一该给的旋钮"重复 |
| `IDLE_UNFOCUSED_DELAY_MS` | `ui/CaretAnimator.java:83` | 同上（失焦档） |
| `MAX_ADAPTIVE_DELAY_MS` | `ui/CaretAnimator.java:86` | 自适应上限；与 `MAX_DT` 有硬约束关系（`MAX_DT` 必须 ≥ 它，否则每帧丢时间，`:91-100` 已写明缺陷 1） |
| `MAX_DT` | `ui/CaretAnimator.java:101` | 物理步长钳位。改它会让弹簧在卡顿时跳变（`LESSONS.md` 坑 2） |
| `RECOVERY_GAP_SECONDS` | `ui/CaretAnimator.java:115` | 恢复检测阈值；与 `MAX_DT` 同属"内部时序契约"，用户没有任何语义可以映射到它 |
| `BUSY_RETRY_MS` | `ui/RenderScheduler.java:55` | 1ms 退避的策略常量；改成 0 会引入空转（issue #8，代价约 3.4%），且它是"调度实现细节"而非偏好 |
| `MAX_SLEEP_MS` / `DUE_TOLERANCE_NANOS` / `MAX_GHOSTS` / `IME_MAX_SKIP_FRAMES` | `RenderScheduler:40/46`、`AfterimageTrail:24`、`CaretAnimator:165` | 同上：纯实现细节，没有用户可理解的语义 |

### 6.3 顺带发现（**只记录，不在本契约范围内动手**）

| 项 | 事实 | 本期裁决 |
|---|---|---|
| `ToggleAlwaysHighFpsAction` 是**孤儿类** | 它存在且调用 `NeovideCaretManager.setAlwaysHighFps`，但 `plugin.xml:20-72` 的 `<actions>` **没有注册它**（5 个 action 里没有它）。见 `SETTINGS-RESEARCH.md` §4.3-④ | **不注册、不删除**。第一期由设置面板接管 `alwaysHighFps`；注册它会新增第 6 个 Tools 菜单项，超出"设置界面"范围 |

---

## 7. 验收判据（可机械裁决）

实现完成（t2/t3）后，审查（t6）**必须**逐条裁决下表。任何一条失败即 `needs_revision`。

| # | 判据 | 命令 / 方法 | 期望 |
|---|---|---|---|
| V1 | 契约字段表与 §4.4 一致 | 人工比对本文 §1 与 `SETTINGS-RESEARCH.md` §4.4 | 16 项，仅 D-1/D-2 两处显式偏离 |
| V2 | 每个字段有 控件/范围/默认/换算/警告 | 本文 §2.1 表完整性 | 5 列无空缺 |
| V3 | 三个前置改造有可执行设计 | 本文 §3.1/§3.2/§3.3 | 含注解、XML 片段、算法分支、调用顺序表 |
| V4 | 默认值逐字未变 | §5.3 脚本 + `git diff` | 缺失数 `0`；`NeovideConfig.java` 零 diff |
| V5 | parity | `tools/parity/run-parity.ps1` | 3 × `PARITY OK` |
| V6 | audit | `tools/parity/run-audit.ps1` | DIFF == 1 且是 `hideNativeCaret` |
| V7 | IME | `tools/ime-probe/run-ime-geom-test.ps1` | 29 项全过 |
| V8 | 不再有 PropertiesComponent 双写 | `grep -c PropertiesComponent` 于 `ui/NeovideCaretManager.java` | `0` |
| V9 | `restorePersistedSettings` 已消失 | `grep -rc restorePersistedSettings src/` | `0` |
| V10 | `enabled` 只有一个运行期真相 | `grep -rn "static volatile boolean enabled" src/` | `0`；`CONFIG.enabled` ≥3 命中 |
| V11 | §2.5 公式在默认刻度复现原数组 | 单元 harness（`Float.floatToRawIntBits` 逐元素） | `{1.0f,0.72f,0.42f,0.22f}` 精确相等 |
| V12 | 三类缓存真的被刷新（含"跳过 S7 则失败"的反例） | §3.3.3 的三条反射判据 | 三条全过；跳过 S7 时三条全失败 |
| V13 | `applyConfig()` 幂等 | 连调两次，比较副作用日志 | 第二次 S2–S6 全跳过 |
| V14 | 面板无 `onChanged` 实时改写 | `grep -rn "onChanged\|whenStateChangedFromUi" src/main/java/.../settings/` | `0` 命中 |
| V15 | §6 的字段确实没有控件 | `grep -rn "animationLength\|typingMaxChars\|cursorWidth\|afterimageLifetime\|maxTrailDistanceFactor\|shortMoveThreshold\|animationResetThreshold" src/main/java/.../settings/` | 仅允许出现在**注释**里，不得出现在绑定/控件调用中 |

---

## 附录 A：证据行号索引

> 所有行号基于 HEAD `ba67ed3`。改代码后行号会漂移，**核证据请以字面量为准**。

### A-1 `core/NeovideConfig.java`（默认值来源，24 字段）

`tailColor:14`、`tailOpacity:17`、`useShadow:20`、`shadowColor:23`、`shadowBlurFactor:38`（注释 26-37 解释为何偏离原版 0.6）、`glowLayers:59`（注释 47-57 记录 JFR：`fill(Path2D)` 占 EDT 9.3%）、`glowOpacity:65`、`alwaysHighFps:98`（注释 88-96 说明"本版默认 true 且不再提供菜单开关"）、`animationLength:101`、`shortAnimationLength:119`、`trailSize:133`、`trailFactors:162`、`shortMoveThreshold:165`、`typingMaxChars:187`、`animationResetThreshold:190`、`overlayFadeOut:207`、`cursorDisappearDelay:215`、`cursorFadeOutDuration:223`、`maxTrailDistanceFactor:238`、`cursorWidth:241`、`hideNativeCaret:264`（注释 246-262 记录"有意偏离上游踩坑 #19"）、`enabled:267`、`trailMode:272`、`afterimageLifetime:278`。

### A-2 `ui/NeovideCaretManager.java`（本次改动的主要落点）

| 行 | 内容 | 与本契约的关系 |
|---|---|---|
| 45 | `ANIMATORS`（`ConcurrentHashMap<Editor, CaretAnimator>`） | S7/S8 的遍历对象 |
| 48 | `private static final NeovideConfig CONFIG = new NeovideConfig();` | 运行期唯一快照（§3.1.5） |
| 50 | `private static volatile boolean enabled = true;` | **删除**（§3.1.4） |
| 92 / 100 | 两个 `PropertiesComponent` 旧键 | **迁移后删除**（§3.2） |
| 118 | `getConfig()` | 保留（对外 API） |
| 123 / 130 | `return enabled` / `if (!enabled)` | 改 `CONFIG.enabled` |
| 144-150 | 首次回调：`restorePersistedSettings()` → `initialScanDone=true` → 补建 | 替换为 `syncStateToConfig()`（§4.2 方案 1） |
| 192-196 | `if (CONFIG.hideNativeCaret) applyNativeCaretHidden(); else restoreNativeCaret();` | 说明 #3 的副作用严重性 |
| 250-255 | `setTrailMode`：写字段 + 遍历 `onModeChanged()` | S2 |
| 276-287 | `setHideNativeCaret`：改配色 + `clearTrail()` | S3 |
| 300-311 | `setOverlayFadeOut`：落盘 + `requestWakeUp()` | S5 |
| 324-336 | `setAlwaysHighFps`：落盘 + `requestWakeUp()` | S4 |
| 347-361 | `restorePersistedSettings()`；**350 行默认 `false`** | **删除**（§4） |
| 366-387 | `setEnabled`：开→`install()` 全部编辑器；关→`dispose()`+`clear()` | S6（必须最后） |
| 398-412 | `applyNativeCaretHidden()`（`savedCaretColor != null` 早退 → 幂等） | S3/S6 可重复调用 |
| 455-469 | `restoreNativeCaret()` | S6 关闭路径 |

### A-3 三件套 harness 对本契约的约束

- `tools/parity/PhysicsParity.java:73`、`tools/parity/GeometryParity.java:45`、`tools/TrailParityTest.java:75`、`tools/ime-probe/ImeGeomCriterionTest.java:203` 均 `new NeovideConfig()` → `NeovideConfig` 的构造器与默认值**不可动**。
- `tools/ime-probe/ImeGeomCriterionTest.java:205-207` 用反射拿 `CaretAnimator(Editor, NeovideConfig)` 构造器 → **构造器签名不可动**（这就是 §3.3.3 判据可落地的原因）。
- `tools/parity/run-audit.ps1:32-45`：19 项字面量全部指向 `core/NeovideConfig.java` 与 `ui/CaretAnimator.java`。

### A-4 缓存与门控的行号（§2.3 / §3.3.3 的依据）

- `CaretTrailPanel.java`: `77-78`（`fillColor/glowColor` 字段）、`81`（`glowWidth = 12f` 初值）、`118`（构造器调 `refreshStyles`）、`122-125`（`refreshStyles` 实现）、`135-141`（`updateGlowRadius`：`glowWidth = shadowBlurFactor × max(w,h)`）、`144`（`getGlowWidth`）、`265`（`useShadow` 门控）、`276`（`peak = glowOpacity × alpha`）。
- `CaretAnimator.java`: `203`（`glowPadding` 字段）、`383`（`setLifetime`）、`388`（`glowPadding` 构造初算）、`545-547`（`requestWakeUp`）、`568`（caret 事件置 `forceResync`）、`617-621`（恢复分支）、`672`（`trailMode` 二选一）、`871`（快速路径）、`1018-1019`（慢路径重算 `glowPadding`）、`1193`（`fading` 判据）、`1215`（`overlayFadeOut` 硬门控）、`1226`（`cursorDisappearDelay`）、`1230-1231`（`cursorFadeOutDuration`）、`1344`（`pad = glowPadding`）、`1353-1368`（`onModeChanged`：`setLifetime`/`refreshStyles`/`forceResync`/`wakeUp`）。
- `core/AfterimageTrail.java`: `24`（`MAX_GHOSTS`）、`61`（`lifetime = 0.15` —— 注意与 config 的 0.22 不同，靠 `setLifetime` 覆盖）、`113/130/147`（读 `lifetime`）、`171-173`（`setLifetime` + `max(0.02, ...)`）。
- `core/TrailCorner.java`: `136-137`（rank 方向 javadoc）、`201`（`typingMaxChars`）、`206`（`shortMoveThreshold` 兜底）、`220-221`（`shortAnimationLength × trailFactors[rank]`）、`227-228`（`leading/trailing`）、`259/268`（`animationResetThreshold` 仅注释）、`306`（`maxTrailDistanceFactor` 限幅）。
- `ui/RenderScheduler.java`: `40/46/55`（三个调度常量）、`58`（`ANIMATORS` 集合）。

### A-5 平台 API 的 `javap` 实测（本机 `D:\deepseekwork\_idea-anim\idea-libs`，381 jar）

| 事实 | 结论 |
|---|---|
| `Panel.row / group / collapsibleGroup / panel / separator / indent / rowsRange` | 存在（`intellij.platform.ide.impl.jar`） |
| `Row.slider(int,int,int,int)` | **只收 int**（float 必须刻度映射） |
| `Row.cell(T)` | 存在 → `ColorPanel` 靠它塞（DSL **没有** `colorPanel()` 工厂方法） |
| `Cell.enabledIf(ComponentPredicate)` / `Cell.onChanged(Function1)` | 都存在；`onChanged` 带 `@ApiStatus.NonExtendable`，**第一期不用** |
| `ButtonKt.getSelected(Cell)` | 存在 → 联动置灰直接拿 checkbox 的 predicate（§2.3） |
| `BoundConfigurable(String, String)` | **两个 String** 的构造器；`createPanel()` 是抽象方法，`createComponent/isModified/reset/apply/disposeUIResources` 已在 `DslConfigurableBase` 实现 |
| `UnnamedConfigurable.cancel()` | 存在（默认空实现）→ 支持"apply 前不改运行期"的设计 |
| `SearchableConfigurable` | 只有 `enableSearch` 默认方法 + 继承来的 `getId()` |
| `@State(name, storages)` / `@Storage(value, roamingType, ...)` / `RoamingType.{DEFAULT,LOCAL,PER_OS,DISABLED}` | 全部存在 |
| `PropertiesComponent.isValueSet(String)` / `getBoolean(String, boolean)` / `unsetValue(String)` | 全部存在（迁移算法的三个原语） |
| `ColorPanel.getSelectedColor/setSelectedColor/setSupportTransparency/setEnabled` | 全部存在 |
| `DialogPanel.apply/reset/isModified/validateAll` | 全部存在（`isModified` 由绑定自动算） |

---

## 8. v2 修订记录（v1 冻结 → t5 独立审查之后）

> 本次修订**不改任何实现要求、不改任何默认值**，只做三类动作：更正类名/包名（v1 笔误）、
> 把一条不可达的条款显式降级为「第一期不做」、以及补齐 §3.2/§3.3 在实现期发现的判据缺失。

| # | 位置 | v1 写法 | v2 写法 | 依据 |
|---|---|---|---|---|
| R1 | §3.1.6 XML 片段 | `instance="...settings.NeovideSettingsConfigurable"` | `instance="...ui.settings.NeovideCursorConfigurable"` | 实际落点；`plugin.xml` 与 jar 内 class 已自洽（t4 验证）。**v1 是笔误**，同一错误名在 §3.1.6/§3.1.7 复制了两处 |
| R2 | §3.1.7 类骨架 | `class NeovideSettingsConfigurable` | `class NeovideCursorConfigurable` | 同上；且新名与插件名 `NeovideCursor` 一致，更贴合官方「id 基于插件 id」的建议 |
| R3 | §3.1.7 包名 | 隐含 `settings/` | 明确 `ui/settings/` | 职责划分：`settings/` 放持久化服务，`ui/settings/` 放 UI |
| R4 | §2.5「非规范数组的处理」 | 【必须】反推刻度 + 不拖动则不改写 | 【**第一期不做**】 | `trailFactors` 第一期唯一来源是编译期常量，恰为 t=45 的规范数组 → 该分支**不可达**；且反推经 `round2` 会引入滑块漂移。v1 里零调用的 `kFromTrailFactors()` 已从源码删除，消除「文档说要、代码没有、方法没人调」的三方不一致 |
| R5 | §3.3.2 S7 判据 | 「`onModeChanged()` 置 `forceResync=true` → 下一帧慢路径重算 `glowPadding`」 | 追加：**该判据不充分**，S7 必须再显式调 `CaretAnimator.refreshGlowPadding()` | `CaretAnimator.refreshTarget()` 在重算 padding **之前**还有一条「中心与高度都没变就 return」的早退；光标静止时它恒成立，静态光标编辑器更整体跳过 `refreshTarget`。t2 实测：不补这一步，改 `shadowBlurFactor` 后 `glowPadding` 停在 11 而非 24 |
| R6 | §3.3.2 非 EDT 分支 | 未定义 S0 的采集时机 | **S0 必须早于任何写 `CONFIG`**；非 EDT 时「先拍快照 → 同步 → 带快照 `invokeLater`」 | t5 审查 R-02：v1 语义下 EDT 侧现拍 S0 拍到的是已同步的新值，`changed()` 全 false → setter 链永不执行 |
| R7 | §3.2.2 落盘判定 | `boolean persistQuietly()` | 三态 `PersistResult{CONFIRMED, DEFERRED, FAILED}` | t5 审查 R-03：非 EDT 时「排队中」与「真失败」对删键/回退版本号的要求**相反**，一个 boolean 装不下；v1 会出现「日志说保留旧键、实际却删了，且 stateVersion 永不收敛」 |

### 8.1 v2.1 增补（t8 复审的 2 条 low，在 t6 集成交付时收尾）

| # | 位置 | 改前 | 改后 | 依据 |
|---|---|---|---|---|
| N-2a | §3.3.2 伪码 S7 | 只有 `a.onModeChanged();` | 补 `a.refreshGlowPadding();`（附注释说明它兜住"光标静止 / 静态光标编辑器"） | 伪码与实现不同步：实际 `NeovideCaretManager.java:536/542` 是两步 |
| N-2b | §3.2.2 伪码 | `boolean persisted = persistQuietly()` | `PersistResult` 三态 + `switch`（CONFIRMED/DEFERRED/FAILED），并附一段「为什么必须三态」 | 伪码与实现不同步：实际 `NeovideCursorSettings.java:208` 用 `PersistResult`，`:206-226` 是 switch |
| N-2c | §3.3.3 `glowPadding` 行 | 「`forceResync` 一置位就必走慢路径。**这是……的唯一保证**」 | **该断言已被证伪**，改为**两步保证**：① `forceResync` 管"光标在动"，② `refreshGlowPadding()` 兜住"光标静止 / 静态光标编辑器"；并在判据里把 ② 的断言改成「改值后**不 tick** 直接读 24」 | `CaretAnimator:1007` 的早退（中心与高度都没变就 return）在重算 padding **之前**，光标静止时恒成立。**这句"唯一保证"正是 R-05 当初产生的根源** |
| N-1 | 新增 §3.3.4 | （无此条） | 记录「逐格拖动时 `glowPadding` 最多滞后 1 px」，判**第一期不改代码**，并附探针 E 的定量证据与第二期的改法 | `CaretTrailPanel:138` 的 0.5 px 死区 × slider 步长 0.01×32=0.32 px。基线 `8906609` 同一条判据、本批 numstat 为空 → **非本批回归**；最大滞后 1 px 且光标一动即纠正；本轮 `src/` 不在集成交付 inScope 内 |

**未修订的部分**：§1 字段清单（16 项）、§2.1 控件/范围/默认值、§2.2 警告文案、§2.3 联动置灰矩阵、
§5 禁止改默认值清单、§7 验收判据 V1–V15 —— 全部保持 v1 原样，且已被 t2/t3/t4 逐条验证。
