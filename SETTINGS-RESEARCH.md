# SETTINGS-RESEARCH —— IntelliJ IDEA 设置界面设计调研

> 生成：2026-10-19　｜　工作目录 `D:\deepseekwork\neovim-cursor-pr`
> 基线：HEAD `8906609`，分支 `feat/settings-ui-ready`，已安装 `0.9.3-imegeom`
> **本文只做调研，不改 `src/`，不写实现代码。**
>
> 取证方式：全部结论来自 `web_fetch`（GitHub REST API / JetBrains 官方文档 / 本地 jar 字节码 `javap`）
> 与本地源码 `grep`/`read`。**没有一条参数语义来自模型记忆。**
> 抓不到、或与宣称不一致的地方一律标 **未证实** / **与宣称不符**。

---

## 目录

- [0. 先说三条会直接影响设计的事实纠正](#0-先说三条会直接影响设计的事实纠正)
- [一、参数语义参照（外部项目）](#一参数语义参照外部项目)
- [二、IntelliJ 平台设置界面的标准做法（实现层）](#二intellij-平台设置界面的标准做法实现层)
- [三、物理可行性审查（24 个字段逐个过）](#三物理可行性审查24-个字段逐个过)
- [四、风险清单](#四风险清单)
- [五、还没查到 / 无法证实的部分](#五还没查到--无法证实的部分)
- [六、一句话结论](#六一句话结论)

---

## 0. 先说三条会直接影响设计的"事实纠正"

这三条都是我在取证过程中撞出来的，和仓库里既有文档的表述**不一致**，
如果不先纠正，后面任何"给 20 个字段做控件"的方案都会做错。

### 0.1 `NeovideConfig` 现在**不是 20 个字段，是 24 个**

`src/main/java/.../core/NeovideConfig.java` 里实际有 **24 个可配置字段 + 1 个枚举类型**：

| # | 字段 | 类型 | 当前默认值 |
|---|---|---|---|
| 1 | `tailColor` | String | `#FFC0CB` |
| 2 | `tailOpacity` | float | `1.0f` |
| 3 | `useShadow` | boolean | `true` |
| 4 | `shadowColor` | String | `#FFC0CB` |
| 5 | `shadowBlurFactor` | float | `0.2f` |
| 6 | `glowLayers` | int | **`8`** |
| 7 | `glowOpacity` | float | `0.55f` |
| 8 | `alwaysHighFps` | boolean | `true` |
| 9 | `animationLength` | float | `0.1f` |
| 10 | `shortAnimationLength` | float | `0.11f` |
| 11 | `trailSize` | float | `1f` |
| 12 | `trailFactors` | float[] | `{1.0, 0.72, 0.42, 0.22}` |
| 13 | `shortMoveThreshold` | float | `8f` |
| 14 | `typingMaxChars` | float | `3f` |
| 15 | `animationResetThreshold` | float | `0.09f` |
| 16 | `overlayFadeOut` | boolean | `true` |
| 17 | `cursorDisappearDelay` | int | `50` |
| 18 | `cursorFadeOutDuration` | float | `0.075f` |
| 19 | `maxTrailDistanceFactor` | float | `60f` |
| 20 | `cursorWidth` | float | `2f` |
| 21 | `hideNativeCaret` | boolean | `true`（批次2 起偏离上游） |
| 22 | `enabled` | boolean | `true` |
| 23 | `trailMode` | `TrailMode` | `SPRING` |
| 24 | `afterimageLifetime` | float | `0.22f` |

证据：`src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java:14-278`（逐行读）。

> ⚠️ **陷阱**：仓库里有三份同名文件。
> `build/v050/...`、`build/v050-src-backup/...` 里的是**上游 0.5.0 原版**（20 个字段，`glowLayers=12`、
> `shortAnimationLength=0.05`、`trailFactors={1.0,0.9,0.5,0.3}`、`hideNativeCaret=false`），
> **只有 `src/` 下这份是真正参与构建的**。
> 本报告一律以 `src/` 为准。任何按 `build/` 那份做设计的方案都会给出错误的默认值。

### 0.2 `useHardSnap` / `leadingSnapFactor` / `leadingSnapThreshold` / `snapAnimationLength` **已经不存在**

你说的 20 项里包含这几个名字 —— 它们在 `build/` 的上游备份里有，
但在 `src/` 里**已被删除**（批次1 "删 7 死字段"）。
证据：`grep -r "useHardSnap|leadingSnap|snapAnimationLength" src` → **0 命中**。

### 0.3 "20 项"的另一种来源：JS 原版也只有 7 项

`LengineerC/vscode-neovide-cursor` 的 `neovide-cursor.js` 顶部配置块**只有 8 个可调值**（5 个顶层 const + `ANIMATION_SETTINGS` 里 3 个），
而且是**散装的 const**，不是 `cursorConfig` 对象（详见 §1.2）。
所以"20 项"既不是指 `NeovideConfig` 的现状（24），也不是指 VS Code 原版（8），也不是指 Neovide 官方 cursor 设置（10）—— 它是本仓库中间某一版的数字。

---

## 一、参数语义参照（外部项目）

### 1.0 取证可达性说明（先讲清楚哪些站点抓不到）

| 站点 | 结果 |
|---|---|
| `neovide.github.io` / `neovide.dev` | **抓取失败**（`fetch failed` / 跨域重定向到 neovide.dev 后失败） |
| `raw.githubusercontent.com` | **抓取失败**（本机网络对该域不可达） |
| `github.com` HTML 页面 | 可达，但单页被截断在 100 KB，且 wiki 页是 JS 渲染，**拿不到正文** |
| **`api.github.com`** | **可达**（REST contents API 取 base64 解码，全文可得） |

**因此本文所有 Neovide 官方结论均取自仓库内文档源文件**
`website/docs/configuration.md`（经 `api.github.com` 解码，sha `1bac9b09c03b0086ef078eb402e6258c1f4e7177`），
它就是 neovide.dev 的正文来源。
可点击对照：<https://github.com/neovide/neovide/blob/main/website/docs/configuration.md>
（你本机能打开这个 HTML 页；我这边只能走 API，但内容同源。）

### 1.1 Neovide 官方文档里与 cursor / animation / trail 相关的配置项

来源：<https://github.com/neovide/neovide/blob/main/website/docs/configuration.md> §"Cursor Settings"（文档第 1057 行起）

| 设置项（Vim 全局变量） | 类型 | 文档写明的默认值 | 取值范围 / 说明 |
|---|---|---|---|
| `g:neovide_cursor_animation_length` | float（秒） | `0.150` | 0 表示关闭动画。见 `configuration.md` L1070/L1079-1080 |
| `g:neovide_cursor_short_animation_length` | float（秒） | `0.04` | 仅用于"同行 1~2 个字符"的短移动（打字）。L1087/L1096-1097 |
| `g:neovide_cursor_trail_size` | float | `1.0` | **文档明写 "Range 0.0 to 1.0"**。1.0 = 前缘瞬间到位、拖尾最大；越小越平滑但越滞后。L1110/L1119-1123 |
| `g:neovide_cursor_antialiasing` | bool | `v:true` | 关闭可修某些光标视觉问题。L1130 |
| `g:neovide_cursor_animate_in_insert_mode` | bool | `v:true` | 关闭后 insert 模式光标瞬间跳。L1146 |
| `g:neovide_cursor_animate_command_line` | bool | `v:true` | 关闭后编辑器↔命令行切换不动画。L1163 |
| `g:neovide_cursor_unfocused_outline_width` | float（em） | `0.125` | 建议 0~0.5；`<=0` 则不可见。L1181/L1190-1191 |
| `g:neovide_cursor_smooth_blink` | bool | `v:false` | 光标闪烁平滑过渡。L1200 |
| `g:neovide_cursor_cell_color_fallback` | bool | `v:false` | 用光标下格子颜色做兜底。L1218 |
| `g:neovide_cursor_hack` | bool | `v:true` | 防光标闪到命令行。L800 |

**关键缺口（必须如实写）：Neovide 官方文档里没有任何"辉光/glow/shadow"配置项，也没有"消失延迟/淡出时长"配置项。**
`grep -i "glow\|shadow\|fade" website/docs/configuration.md` 在 Cursor Settings 段内是 **0 命中**。
我在源码里也确认了：`src/renderer/cursor_renderer/mod.rs` 的 `CursorSettings` 结构体
（<https://github.com/neovide/neovide/blob/main/src/renderer/cursor_renderer/mod.rs#L45-L85>）只有
`antialiasing / animation_length / short_animation_length / animate_in_insert_mode / animate_command_line /
trail_size / unfocused_outline_width / smooth_blink / cell_color_fallback` + 一组 `vfx_*`，
**没有 glow、没有 shadow、没有 fade**。

默认值也在该文件 `impl Default for CursorSettings` 里逐条核实：
`animation_length: 0.150`、`short_animation_length: 0.04`、`trail_size: 1.0`、`antialiasing: true`。

### 1.2 VS Code 版 `neovide-cursor` 的配置定义 —— **没有 `cursorConfig` 对象**

**这条与你的假设不符，必须更正。**

我按可点击路径取了原始文件：
<https://github.com/LengineerC/vscode-neovide-cursor/blob/main/neovide-cursor.js>
（75★，最后 push 2026-10-03；本地也缓存了一份，见 `diag/upstream-neovide-cursor.js` 的字节码 dump，我解码后与之**逐字节一致**，sha `c7f1d82520f59d21739e29dc7c95b85d98b411fc`，15481 B）

文件**第 1-14 行**就是全部配置，而且是**顶层 const，不是 `cursorConfig` 对象**：

```js
// --- Configurations ---
const cursorColor = "#C8D3F5";                 // 光标颜色
const cursorUpdatePollingRate = 500;           // DOM 探测间隔 (ms)
const useShadow = true;                        // 辉光开关
const shadowColor = cursorColor;               // 辉光颜色
const shadowBlur = 10;                         // 辉光模糊半径（Canvas shadowBlur 语义）

const ANIMATION_SETTINGS = {
  animationLength: 0.10,                       // 跨行动画时长
  shortAnimationLength: 0.04,                  // 同行短移动动画时长
  trailSize: 1,                                // 拖尾密度 (0-1)
};
```

我全文搜过：`cursorConfig` **0 命中**；`trailFactors` **0 命中**；`glowLayers` **0 命中**；
`hideNative` **0 命中**；`fade` / `disappear` / `snap`（硬吸附）**全部 0 命中**。

> 所以本仓库 `NeovideConfig` 头部注释"原始定义见 neo-cursor-fix/extension/assets/neovide-cursor.js 第 2-26 行"
> 描述的**不是**这个 75★ 的仓库。可能是另一个 fork / 私有版本，**未证实**。
> 另外 `NeovideConfig.java` 里提到 `cursorConfig.shadowBlurFactor = 0.6`，
> 但上面这份原版写的是 `const shadowBlur = 10`（**10，不是 0.6**）—— 也对不上。

**顺带发现一个更符合"四角梯度"描述的第三方参考**（非本插件来源，但对参数语义有直接可比性）：
`Tasomei/vscode-neovide-cursor-lite` 的 `CONFIG` 里有
`rankTrailFactors: [1.05, 0.82, 0.36, 0.08]`、`useLeadingBoost`、`leadingBoostFactor`、`resetThreshold`、
`maxStretchFactor: 56` —— 见 <https://github.com/Tasomei/vscode-neovide-cursor-lite/blob/main/cursor-trail.js> 第 3-37 行。
**这些字段名与本插件的 `trailFactors` / `useHardSnap` / `animationResetThreshold` / `maxTrailDistanceFactor` 高度同构**，
但那是另一个作者、另一套实现，**不能据此认定为本插件来源**（未证实）。

### 1.3 三方对照表（项目字段 / Neovide / VS Code）

图例：
`✅` 语义一致　`⚠️` 语义相近但不同　`❌` 对方没有　`🔧` 我们有意偏离　`👻` 我方死字段（代码里没人读）

| 项目字段 | Neovide 对应项 | VS Code 对应项 | 语义是否一致 | 我们的默认值是否偏离 |
|---|---|---|---|---|
| `tailColor` | ❌ 无（颜色来自 `guicursor`） | `cursorColor`（但那是**光标本体**色，不是拖尾色） | ⚠️ 概念错位 | 值不同且语义不同：原版 `#C8D3F5`，我们 `#FFC0CB` |
| `tailOpacity` | ❌ 无 | ❌ 无（原版无独立 alpha） | ❌ 独创 | — |
| `useShadow` | ❌ 无 | `useShadow = true` | ✅ 一致 | 一致（均 true） |
| `shadowColor` | ❌ 无 | `shadowColor = cursorColor` | ✅ 一致 | 原版跟随光标色，我们独立写死 #FFC0CB |
| `shadowBlurFactor` | ❌ 无 | `shadowBlur = 10`（**不是 factor**） | ⚠️ 单位/语义都变了：原版是 Canvas 模糊半径像素值；我们是"×光标长边"的系数 | **有意偏离**（0.2 vs 0.6），且原版根本没有 0.6 |
| `glowLayers` | ❌ 无 | ❌ 无（Canvas 一层 shadowBlur） | ❌ 独创 | — |
| `glowOpacity` | ❌ 无 | ❌ 无 | ❌ 独创 | — |
| `alwaysHighFps` | ❌ 无（Neovide 是 vsync + 事件驱动渲染） | ❌ 无 | ❌ 独创 | — |
| `animationLength` | `g:neovide_cursor_animation_length` = **0.150** | `ANIMATION_SETTINGS.animationLength` = **0.10** | ✅ 语义一致 | 我们 0.1 = VS Code 值；**比 Neovide 短 33%** |
| `shortAnimationLength` | `..._short_animation_length` = **0.04** | `shortAnimationLength` = **0.04** | ✅ 语义一致 | **我们 0.11，是两者的 2.75 倍**（有明确理由，见注释） |
| `trailSize` | `..._trail_size` = 1.0，range 0~1 | `trailSize` = 1 | ✅ 语义一致（公式逐字相同） | 一致 |
| `trailFactors` | ❌ 无 | ❌ 无 | ❌ 独创（Neovide 用 alignment 连续插值，不用四档系数） | — |
| `shortMoveThreshold` | ❌ 无（硬编码 `jump_vec.x.abs() <= 2.001`） | ❌ 无（硬编码 2.001） | ❌ 独创 | — |
| `typingMaxChars` | ❌ 无（同上是 2.001 **字符**） | ❌ 无 | ⚠️ 是对原版魔数 2.001 的参数化 | 我们 3 vs 原版硬编码 2.001 |
| `animationResetThreshold` | ❌ 无 | ❌ 无 | ❌ 独创 | **👻 死字段**：`grep` 只在注释里出现，代码无读取 |
| `overlayFadeOut` | ❌ 无 | ❌ 无 | ❌ 独创 | — |
| `cursorDisappearDelay` | ❌ 无 | ❌ 无 | ❌ 独创 | — |
| `cursorFadeOutDuration` | ❌ 无 | ❌ 无 | ❌ 独创 | — |
| `maxTrailDistanceFactor` | ❌ 无 | ❌ 无（见 issue #7：上游 README 说 4，代码是 60） | ❌ 独创/未证实 | **👻 近似死字段**：仅 `TrailCorner:306` 做限幅，常规移动达不到阈值 |
| `cursorWidth` | ❌ 无（宽度由 Neovim 网格决定） | ❌ 无（DOM 实测） | ❌ 独创 | — |
| `hideNativeCaret` | ❌ 无 | `hideNativeCursor`（**该名在 75★ 原版里同样 0 命中**，见 §1.2） | ⚠️ 概念有，来源未证实 | **有意偏离**（true，上游 false） |
| `enabled` | ❌ 无 | ❌ 无 | ❌ 独创 | **👻 死字段**：`CONFIG.enabled` 0 命中，代码用的是 `NeovideCaretManager.enabled`（另一个 static） |
| `trailMode`（SPRING/AFTERIMAGE） | ❌ 无 | ❌ 无 | ❌ 独创 | — |
| `afterimageLifetime` | ❌ 无 | ❌ 无 | ❌ 独创 | — |

**取值范围**：
- `trailSize`：**有官方文档依据** 0.0~1.0（见 §1.1）。
- `animationLength` / `shortAnimationLength`：文档只说"秒"、"0 表示关闭"，**没给上限**。
  我们的 `MAX_DT = 1/20 = 0.05s` 与 `RECOVERY_GAP_SECONDS = 0.1` 是内部常量
  （`CaretAnimator.java:101/115`），意味着 **动画时长超过约 50ms 就会与单帧 dt 同量级**，
  超过 100ms 会撞恢复判据 —— 这是硬约束，UI 必须限制。
- 其余全部字段：**Neovide 和 VS Code 都没有对应项，无官方范围可依**，只能由我方实测确定。

### 1.4 明确属于"我们独创"的参数（两方都没有）

按 §1.3 全 `❌`/`❌` 的项：

`tailOpacity`、`glowLayers`、`glowOpacity`、`alwaysHighFps`、`trailFactors`、
`shortMoveThreshold`、`typingMaxChars`、`animationResetThreshold`、`overlayFadeOut`、
`cursorDisappearDelay`、`cursorFadeOutDuration`、`maxTrailDistanceFactor`、`cursorWidth`、
`enabled`、`trailMode`、`afterimageLifetime` —— **共 16 项**。

其中你点名的三项确认属实：
- **`alwaysHighFps`** —— 独创。Neovide 用 vsync 驱动的渲染循环，没有"常驻高帧率"这个开关概念。
- **`overlayFadeOut`** —— 独创。Neovide 官方文档 Cursor Settings 段无 fade 项（源码也无）。
- **`cursorDisappearDelay`** —— 独创。同上，两方均无。

另有 4 项属于"参数化了原版硬编码魔数"，不是全独创但也不是对齐：
`shadowBlurFactor`（原版是像素值 10）、`typingMaxChars` / `shortMoveThreshold`（原版硬编码 2.001）、
`hideNativeCaret`（名字对应，但 75★ 原版里没有该名）。

---

## 二、IntelliJ 平台设置界面的标准做法（实现层）

### 2.0 一个前置实测：本项目**可以用 Kotlin UI DSL，且不必引入 Kotlin 编译器**

这是第二节最重要的结论，因为本机是**离线手工 javac 构建**（`build/offline-build.ps1:49`，
`javac --release 21` + `D:\deepseekwork\_idea-anim\idea-libs` 里 381 个 jar），
没有 Gradle、没有 Kotlin 插件。**如果 DSL 只能在 Kotlin 里用，整条路就断了。**

我写了两个探针类（用完即删，**没有留在 `src/`**），用与构建脚本完全相同的 classpath 编译：

```
javac --release 21 -nowarn -proc:none -cp <381 jar 全量> DslProbe.java FbProbe.java
EXIT=0     ← 两种方案都通过
```

`DslProbe` 直接 `extends BoundConfigurable` 并调用
`BuilderKt.panel(...)` / `ButtonKt.bindSelected(...)` / `SliderKt.bindValue(...)` /
`ComboBoxKt.bindItem(...)` / `Cell.onChanged(...)`。

**结论**：Kotlin UI DSL 是 `@JvmStatic` 友好的普通 JVM API，**Java 可以直接调**，
只需要显式写 `returns Unit.INSTANCE`（Kotlin 的 `Unit` 返回类型在 Java lambda 里必须显式返回）。

对应的 jar 归属（本机实测，逐个 `grep class 条目` 得到的）：

| 类 | 所在 jar |
|---|---|
| `com.intellij.ui.dsl.builder.*`（Panel/Row/Cell/BuilderKt/SliderKt…） | `intellij.platform.ide.impl.jar` |
| `com.intellij.openapi.options.BoundConfigurable` / `SearchableConfigurable` / `Configurable` | `intellij.platform.ide.jar`（`Configurable.class` 在 `intellij.platform.ide.core.jar`） |
| `com.intellij.util.ui.FormBuilder` | `intellij.platform.ide.jar` |
| `com.intellij.ui.ColorPanel` / `JBSlider` / `JBCheckBox` | `intellij.platform.ide.jar` |
| `com.intellij.openapi.ui.DialogPanel` | `intellij.platform.ide.impl.jar` |

> 注意：`BoundConfigurable` 的构造器是 **两个 String**（`displayName, helpTopic`），
> 不是官方 Kotlin 示例里那种单参数。这一点我编译报错后才确认，属于容易踩的坑。

### 2.1 Configurable vs SearchableConfigurable

**实现方式（官方）**：
<https://plugins.jetbrains.com/docs/intellij/settings-guide.html>

官方原文要点（逐条对应文档）：
- "Many Settings … implement `Configurable` or one of its subtypes, such as `SearchableConfigurable`."
- `Configurable` = `UnnamedConfigurable` + `getDisplayName()`；
  `UnnamedConfigurable` 的契约是 `createComponent() / isModified() / apply() / reset() / disposeUIResources()`。
- **生命周期顺序（官方明写）**：`createComponent()` → `reset()` → 中间的 `isModified()`（"called very often"）
  → OK 时 `apply()` → 关闭时 `disposeUIResources()`。
  来源：<https://github.com/JetBrains/intellij-community/blob/master/platform/ide-core/src/com/intellij/openapi/options/UnnamedConfigurable.java>
- **构造器要求**：application 级必须**无参**构造；project 级必须**单 `Project` 参数**构造；
  并且 "The IntelliJ Platform may instantiate a Configurable implementation on a background thread,
  so creating Swing components in a constructor can degrade UI responsiveness." —— **别在构造器里建 Swing**。
- `reset()` 在 `createComponent()` **之后立刻**被调用，所以构造器/createComponent 里不需要初始化值。

`SearchableConfigurable` 在 `Configurable` 之上多两件事（源码：
<https://github.com/JetBrains/intellij-community/blob/master/platform/ide-core/src/com/intellij/openapi/options/SearchableConfigurable.java>）：
- `getId()`（来自 `ConfigurableWithId`）—— 用于设置树/搜索的稳定 id；
- `enableSearch(String option)` —— 设置对话框搜索框命中该项时，返回一个 `Runnable` 用于高亮/选中具体控件。

**对本项目的取舍**：

| | `Configurable` | `SearchableConfigurable` |
|---|---|---|
| 代码量 | 少一个 `getId()` | 多一个 `getId()` |
| 设置搜索 | 只能搜到"页"，搜不到"页内控件" | 可用 `enableSearch` 定位到具体控件 |
| 官方倾向 | 够用 | 推荐（"Most Settings providers … implement … SearchableConfigurable"） |
| **本插件建议** | | ✅ **首选**：字段有 20+，用户一定会在设置里搜 "cursor"/"trail" |

> 补充：`SearchableConfigurable` 的 javadoc 说明它会在**安装包构建期**
> （`buildSearchableOptions` / `TraverseUIStarter`）被实例化并索引，
> 所以 `createComponent()` **必须能在无项目、无编辑器的情况下跑通**，不能依赖 `EditorFactory` 里的编辑器。

### 2.2 FormBuilder vs 手写 Swing vs Kotlin UI DSL

#### (a) FormBuilder（`com.intellij.util.ui.FormBuilder`）

官方示例就用它：`AppSettingsComponent`（`FormBuilder.createFormBuilder().addLabeledComponent(...)`）
来源：<https://github.com/JetBrains/intellij-sdk-code-samples/blob/main/settings/src/main/java/org/intellij/sdk/settings/AppSettingsComponent.java>

- 本质是 **JGoodies `FormLayout`** 的薄封装（本机 `idea-libs` 里确实是 `intellij.libraries.jgoodies.forms.jar`）。
- 优点：零学习成本、Java 原生、`addComponent / addLabeledComponent / addComponentFillVertically` 够用。
- 缺点：
  - **没有数据绑定**，每个字段手写 getter/setter；
  - 没有内置的"标签对齐网格"，多行标签需要自己处理；
  - **官方新代码已经不推荐它做主布局**——新文档的示例基本都是 Kotlin UI DSL。

#### (b) 手写 Swing

- 完全自由，也完全自己负责 `GridBagLayout` 的疼。
- 只有在**必须自绘**（比如要放一个实时预览的画布）时才值得。

#### (c) Kotlin UI DSL（`com.intellij.ui.dsl.builder`）—— **重点**

官方文档两处：
- 现行版（Version 2）：<https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl-version-2.html>
- 旧版（Version 1，正在废弃）：<https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl.html>

**是否可用 / 需要什么平台版本 / 与 FormBuilder 的优劣**：

| 维度 | 结论 | 证据 |
|---|---|---|
| 包名 | V2 = `com.intellij.ui.dsl.builder`；V1 = `com.intellij.ui.layout` | DSL 文档 "Migration Steps" |
| V1 状态 | **2025.1 起"usage is considered an error"**，**2026.2 已"completely removed"** | V1 文档首段；2026 API changes："Kotlin UI DSL Version 1.0 Removal … completely removed. Any access … will lead to compilation or runtime errors." <https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html> |
| 我们能用哪个 | **只能用 V2**。本项目支持 2024.2~2026.2+，V1 在 2026.2 已删除 | 同上 + 本机 jar 里 `com/intellij/ui/layout/*` 我扫过：**没有** DSL 类（只有 `com.intellij.ui.layout.ComponentPredicate` 这种残留类型） |
| 平台版本下限 | V2 在 **2021.3** 就有；本项目基线 2024.2（build 242）**远高于它** | V1 文档："When targeting IntelliJ Platform 2021.3 and later, only use Kotlin UI DSL Version 2" |
| Java 能否调用 | **能**（本报告 §2.0 已实测编译通过） | 我的探针 |
| 可用控件（本机 `javap` 实测） | `row / group / collapsibleGroup / panel / indent / separator`；`checkBox / threeStateCheckBox / radioButton / buttonsGroup / button / label / link / comment`；`textField / intTextField / expandableTextField / textArea / passwordField`；`comboBox / slider(min,max,value,step) / spinner(IntRange) / spinner(ClosedRange<Double>)` | `javap com.intellij.ui.dsl.builder.Row` 输出 |
| `slider` 签名 | `Cell<JSlider> slider(int min, int max, int value, int step)` —— **只接受 int** | 同上（所以 float 参数要做缩放映射，见 §3） |
| 颜色选择器 | DSL **没有** `colorPanel()` 工厂方法（文档里 `colorPanel` 0 命中）；要 `cell(ColorPanel())` 自己塞 | 文档 + `javap Row` |
| 数据绑定 | `bindSelected / bindValue / bindIntValue / bindItem / bindText / bindIntText` 齐全；Java 里要显式写 `() -> x, v -> { x = v; return Unit.INSTANCE; }` | `javap ButtonKt/SliderKt/SpinnerKt/ComboBoxKt` |
| 即时回调 | `Cell.onChanged(Function1<T,Unit>)` 存在；但 `javap -v` 显示它带 `@ApiStatus.NonExtendable`，且官方文档正文**根本没提 onChanged**（只有 `onApply/onReset/onIsModified`） | `javap -v Cell` + DSL v2 文档 |
| 与 FormBuilder 比 | DSL 优势：绑定省样板、自动对齐网格、官方现行推荐、`enabledIf` 做联动。FormBuilder 优势：Java 原生无需 `Unit.INSTANCE`、`DialogPanel` 依赖更少 | — |

**⚠️ 关于 `onChanged` 的诚实说明**：
它**存在于字节码**（我可以编译通过），但**官方文档没有为它背书**，
且带 `@ApiStatus.NonExtendable`。用它做"实时预览"属于**走文档外的路**，
风险是未来版本可能改语义。→ 这也是我建议 **第一期不要依赖 onChanged** 的原因（见 §4.4）。

### 2.3 PersistentStateComponent 的正确用法

官方：<https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html>
（另有教程 <https://plugins.jetbrains.com/docs/intellij/settings-tutorial.html>）

**必需的四件事**（逐条对应文档原文）：

1. **注册成 service**（application 或 project 级），**不是 extension**。
   官方："instances of extensions can't persist their state by implementing PersistentStateComponent.
   If an extension needs to have a persistent state, define a separate service responsible for managing that state."
   → 本项目 `NeovideCaretManager` **是 `editorFactoryListener`（extension）**，所以它**不能**自己实现持久化，
   必须另建 service。这一条直接决定了改造方案（见 §4.3）。

2. **实现 `getState()` / `loadState()`**，泛型参数 = 状态类型。

3. **`@State(name=..., storages=@Storage("xxx.xml"))`**。
   官方强调："For application-level storage, it is strongly recommended to use a custom file.
   Using of other.xml is deprecated."
   → 本项目应该用一个专属文件，例如 `NeovideCursorSettings.xml`（在 IDE 配置目录的 `options/` 下）。

4. **`roamingType` 的选择**（`@Storage` 的参数）：
   官方三个取值：
   - `RoamingType.DEFAULT` —— 参与同步
   - `RoamingType.PER_OS` —— 按操作系统分别同步
   - `RoamingType.DISABLED` —— 不同步
   并且："If there are multiple components that store state in the same file, they must have the same roamingType."
   以及：`other.xml` 是 non-roamable，声明它会**关闭**漫游。

   **对本项目的建议**：`RoamingType.DEFAULT`（或显式 `PER_OS`）。
   理由：这些是"观感偏好"，不含机器相关路径，**适合跨机同步**；
   唯一例外是 `alwaysHighFps`（性能/EDT 负载相关，弱机器上该关）——
   如果在意，可以把"性能类"字段单独拆一个 `@State` 文件并设 `PER_OS`。
   文档说"Only the settings that aren't specific to a given machine should be shared"。

**序列化能力（文档明写）**：支持 numbers / booleans / strings / collections / maps / enums。
别的类型要写 `Converter`。
→ **`float[]` 不在白名单里，但数组是可以序列化的**（XmlSerializer 支持数组），
   稳妥做法见 §3 的 `trailFactors` 处理建议。

**状态类要求**：默认构造器；建议实现 `equals()`（不实现则逐字段比较）。

### 2.4 注册方式：applicationConfigurable vs projectConfigurable，parentId，id 命名

官方表格（<https://plugins.jetbrains.com/docs/intellij/settings-guide.html>）：

```xml
<extensions defaultExtensionNs="com.intellij">
  <applicationConfigurable
     parentId="tools"
     instance="com.example.ApplicationSettingsConfigurable"
     id="com.example.ApplicationSettingsConfigurable"
     displayName="My Application Settings"/>
</extensions>
```

| 选择 | applicationConfigurable | projectConfigurable |
|---|---|---|
| 作用域 | 全 IDE | 单项目 |
| 构造器 | 无参 | 单 `Project` 参数 |
| 适用 | **本插件**（观感是全局的，与当前项目无关） | 不适用 |

**`parentId="editor"` 的语义**：把该项挂在 **Settings | Editor** 这一组下面，成为它的子节点。
官方 parentId 取值表：`appearance` / `build` / `build.tools` / **`editor`** / `language` / `tools` / `root`(不要用) / `other`(不要用)。
注意："The `parentId` and the `groupId` attributes should not be used together and the `parentId` has precedence."；
`groupId` 已废弃。

**对本项目的建议**：
- 语义上最贴的是 **`editor`**（这是"编辑器的光标长什么样"），
  但 `editor` 组在 IDEA 里内容极多、子项很深，用户不容易找到自定义插件。
- **`tools`** 是"第三方插件设置"的约定位置（官方表格原话：
  "3rd Party Settings … configure integration with third-party applications"）。
- 折中：**`parentId="editor"` + 明确的 displayName**（如 "Neovide Cursor"）**+ `SearchableConfigurable`**，
  靠搜索兜底；或者主面板放 `tools`。

**id 命名规范**：官方原话 "The FQN should be based on the plugin id to ensure uniqueness."
→ 用 `com.github.yyyolo7a79.neovidecursor.settings`（与本插件 id 同前缀）。
官方还警告：**`displayName` 能写在 XML 里就写在 XML 里**，
否则平台为了拿名字必须先实例化类，"degrades UI responsiveness"。

### 2.5 实时预览：用户拖滑块就想立刻看到效果

**平台层面的事实（必须说清楚）**：
- `Configurable` 的契约里，**"`apply()` 只在用户点 OK 时被调用"**（官方 Tutorial 的 Controller 模式就是这么写的）。
- **但是** `UnnamedConfigurable` 从 2026 版起有一个 `default void cancel()`（本机 `javap` 实测，
  `intellij.platform.ide.core.jar`），它的注释就是 **"Called when 'Cancel' is pressed in the Settings dialog."**
  → 说明平台**承认并支持**"你在 apply 之前就把状态改了"这种用法，并给了你撤销的钩子。
  （**注意**：官方 settings-guide 页面正文里**没有**介绍 cancel，我的 web_fetch 拿到的
  `UnnamedConfigurable.java` 源码因 GitHub API 限流只回显了部分内容，这一段**以本机字节码为准**；
  "从哪个版本开始有" **未证实**。）

**因此平台支持"apply 前的 preview"，但它是靠你自己在 `cancel()` 里回滚实现的，不是框架代劳。**

**惯用做法（三种，按侵入性排序）**：

| 做法 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 立即写 + cancel 回滚** | 控件监听器里直接改 `service` 的字段 → animator 下一帧就读取到新值；`cancel()` 里恢复进入时的快照 | 真·实时；代码最少 | 若用户点 Cancel，得保证每字段都回滚干净；`disposeUIResources` 之后不能再读控件 |
| **B. 立即写 + 只提供 OK** | 不实现 `cancel()`，或在 `cancel()` 里也 `apply()` | 简单 | 违背平台"Cancel 应该撤销"的预期，用户会困惑 |
| **C. 预览态与生效态分离** | 控件改的是"预览副本"，animator 读副本；`apply()` 时才拷进正式 state | 语义最干净 | 需要 animator 支持"读预览副本"这条路径，改动最大 |

**对本项目的额外约束（很关键）**：
本插件的视觉效果**不在设置面板里**，而**在编辑器里**。用户在 Settings 对话框里拖滑块时，
**编辑器被对话框挡住了** —— 所谓"实时预览"其实**看不见**。
所以真正合理的形态是：
1. **面板内的静态预览缩略图**（自绘一个小矩形演示拖尾/辉光形状）—— 但那是"另写一套绘制"，成本高且易与真实效果不一致；
2. **或者回到插件现状**：用 Tools 菜单的即时开关（已经这么做了：`trailMode` / `hideNativeCaret` / `overlayFadeOut` / `alwaysHighFps` 都是即时生效、立即落盘），
   设置面板只负责数值型参数，并**明确写"改完点 OK 生效，或用 Tools 菜单即时切换"**。

→ 我建议 **第一期走"OK 生效"**，把"实时预览"当作第二阶段课题（见 §4.4）。这不是偷懒，是因为
**现有代码的更新链路根本不支持热改值**（§4.3 会给出证据）。

### 2.6 真实开源范例（有设置界面、代码可读）

我找了三个，都给可点击路径：

#### 范例 1（推荐，最小可读）：`izhangzhihao/intellij-rainbow-brackets`（4667★，Lite 分支）

- 仓库：<https://github.com/izhangzhihao/intellij-rainbow-brackets>
- 方案：**`SearchableConfigurable` + IntelliJ GUI Designer（`.form` 文件）+ 手写 Swing 组件**
- 关键文件：
  - `src/main/kotlin/com/github/izhangzhihao/rainbow/brackets/lite/settings/RainbowConfigurable.kt`
    —— `class RainbowConfigurable : SearchableConfigurable`，实现 `createComponent / isModified / apply / reset / disposeUIResources / getDisplayName / getId`
  - `src/main/kotlin/com/github/izhangzhihao/rainbow/brackets/lite/settings/RainbowSettings.kt`
    —— `@State(name="RainbowLiteSettings", storages=[Storage("rainbow_brackets_lite.xml")])` +
    `PersistentStateComponent<RainbowSettings>`，`getState()=this` + `XmlSerializerUtil.copyBean`
  - `src/main/kotlin/com/github/izhangzhihao/rainbow/brackets/lite/settings/form/RainbowSettingsForm.form` + `.kt`（GUI Designer 生成的视图层）
  - `src/main/resources/META-INF/plugin.xml` 里：
    ```xml
    <applicationConfigurable instance="...settings.RainbowConfigurable"/>
    <applicationService serviceImplementation="...settings.RainbowSettings"/>
    ```
    （**注意：它连 `parentId` 都没写** —— 会落到 "Other Settings" 下，这正好说明"不写 parentId"也是常见但不够好的做法；
    官方明确说 `other` group 是 "Do not use"。）
- 参考价值：**三个类的职责切分（View / Model / Controller）可以 1:1 搬到本插件**。
- 局限：它用 GUI Designer，本机**没有 Gradle/IDEA GUI Designer**，不能照抄；`apply()` 是 OK 时一次性拷贝，**没有实时预览**。

#### 范例 2（官方，最小）：`JetBrains/intellij-sdk-code-samples` 的 `settings` 模块

- 仓库：<https://github.com/JetBrains/intellij-sdk-code-samples/tree/main/settings>
- 方案：**`Configurable` + `FormBuilder` + 三段式（`AppSettings` / `AppSettingsConfigurable` / `AppSettingsComponent`）**
- 关键文件：
  - `settings/src/main/java/org/intellij/sdk/settings/AppSettings.java`（`@State` + `PersistentStateComponent`，内部 `static class State`）
  - `.../AppSettingsConfigurable.java`（`implements Configurable`，无参构造，`apply()` 里把 UI 值写进 state）
  - `.../AppSettingsComponent.java`（`FormBuilder.createFormBuilder()...getPanel()`，纯 Swing 组件）
- 参考价值：**最贴合本项目的技术栈（纯 Java、无 Kotlin、无 Gradle 生成物）**。
- 局限：只有 2 个字段（1 个文本框 + 1 个复选框），**没有滑块、没有颜色、没有数组**。

#### 范例 3（反面参照，本地已有）：`D:\deepseekwork\_idea-anim\smear-cursor-intellij`

- 不是 GitHub 仓库（本机目录），但**同为"光标拖尾"插件**，可以直接对照：
  - `src/main/kotlin/com/smearcursor/settings/SmearCursorConfigurable.kt`
    —— `class SmearCursorConfigurable : Configurable`，**`FormBuilder` + `JBSlider` + `ColorPanel` + `JBCheckBox`**，
    用 `createTitledPanel(BoxLayout)` 分了 4 组（General / Animation / Particle / Color）。
  - `src/main/kotlin/com/smearcursor/settings/SmearCursorSettings.kt`
    —— `@State(name="SmearCursorSettings", storages=[Storage("SmearCursorSettings.xml")])` + `PersistentStateComponent`，
    颜色存成 `Int`（`Color.rgb`）而不是 String（**这一点值得抄**，见 §3）。
- 参考价值：**它把 20+ 个光标动画参数做成了滑块的先例**，控件选型和分组方式可以直接借鉴。
- 局限：`apply()` 仍是 OK 时一次性写；**没有实时预览**；`isModified()` 是 20 行手写 `||` 比较（可读性差）。

> **共性结论**：这三个范例**没有一个**做到了"拖滑块立刻在编辑器里看到效果"。
> 说明在本平台上，"设置面板实时预览编辑器效果"**不是惯例**，更多是"OK 后生效"。
> 这个观察支持 §2.5 的建议。

---

## 三、物理可行性审查（24 个字段逐个过）

**判定口径**：
- **能** = 有现成控件且不需要动动画核心；
- **需处理** = 能做，但控件类型不是直觉中的那个（缩放映射 / 复合控件 / 需要联动）；
- **不能/不建议** = 做成 UI 会引入不可控观感或性能风险，或该字段本身是死的。

### 3.1 逐个字段表

| # | 字段 | 控件类型 | 建议取值范围 | 需要警告？ | 备注 |
|---|---|---|---|---|---|
| 1 | `tailColor` | 颜色选择器（`com.intellij.ui.ColorPanel`，`javap` 实测有 `getSelectedColor/setSelectedColor/setSupportTransparency`） | 任意 RGB | 否 | 现为 String `#FFC0CB`；**建议内部改存 int**（见 §4.3-④）。用 ColorPanel 可自动带透明度，但 `tailOpacity` 是独立字段，两者要协调 |
| 2 | `tailOpacity` | 滑块（0-100 映射到 0.0-1.0） | 0.0 – 1.0，步长建议 0.05（滑块 0-100/step 5） | 否 | DSL `slider` 只收 int，必须做 ×100 映射 |
| 3 | `useShadow` | 复选框 | true/false | 否 | 关掉后 `shadowColor/shadowBlurFactor/glowLayers/glowOpacity` 应 `enabledIf(false)`（DSL 有 `enabledIf`） |
| 4 | `shadowColor` | 颜色选择器 | 任意 RGB | 否 | `useShadow=false` 时应置灰 |
| 5 | `shadowBlurFactor` | 滑块 | **0.05 – 0.60**（默认 0.2）。上方 0.6 = 原版数值 | **是** | 上界必须写清楚"0.6 是原版 JS 的夸张光雾（约 40px）"。**它同时决定重绘 padding**（`CaretAnimator:388/1018-1019`），调到 0.6 会让每个编辑器每帧的重绘包围盒显著变大。给一句提示即可，不必阻止 |
| 6 | `glowLayers` | 滑块/数字框 | **1 – 12**（默认 8） | **是** | 有性能代价：`NeovideConfig` 注释引 JFR——`drawQuadGlow` 的 `fill(Path2D)` 曾占 EDT 9.3%，所以从 12 降到 8。**警告文案**："层数越多越平滑，但每层都是一次 CPU 抗锯齿+alpha 混合；12 层是旧默认值。" 另：**层数不影响亮度**（内部按层数反解 alpha），这点要在 tooltip 里说，否则用户会以为调层数能变亮 |
| 7 | `glowOpacity` | 滑块（0-100 → 0.0-1.0） | 0.0 – 1.0 | 否 | `useShadow=false` 时置灰 |
| 8 | `alwaysHighFps` | 复选框 | true/false | **强警告** | 见 §4.1/§4.2。EDT 每秒 ~125 次投递且**永不停歇**；弱机器/多标签页时可能更卡。且它**与 `hideNativeCaret` 强耦合**（§4.2） |
| 9 | `animationLength` | 滑块（毫秒更直观：10-300ms → ÷1000） | **建议 0.05 – 0.30 秒**（默认 0.1） | 是（上界） | 硬约束：`MAX_DT = 1/20 = 0.05s`、`RECOVERY_GAP_SECONDS = 0.1`（`CaretAnimator:101/115`）。**超过 0.1s 会与恢复判据同量级**，建议 UI 上限 0.3，并在 >0.2 时提示"可能显得拖沓" |
| 10 | `shortAnimationLength` | 滑块（毫秒） | **建议 0.02 – 0.30 秒**（默认 0.11，2.75× 于原版） | 是（上界） | 同上约束；它还要**乘 `trailFactors`**，所以上限比 `animationLength` 更要小心（见 §4.2） |
| 11 | `trailSize` | 滑块（0-100 → 0.0-1.0） | **0.0 – 1.0（有官方依据）** | 否 | Neovide 文档明写 "Range 0.0 to 1.0"。见 §1.1 |
| 12 | `trailFactors` | **4 个独立滑块**（rank0..rank3），或一个复合的"拉伸强度"主滑块 + 高级展开 | **每项 0.0 – 1.0，且建议强制单调不增**（rank0 ≥ rank1 ≥ rank2 ≥ rank3） | **是** | 见 §3.2 专门讨论 |
| 13 | `shortMoveThreshold` | 滑块/数字框 | 1 – 40（默认 8） | 否 | **它只是兜底判据**（`TrailCorner:206`，仅当拿不到字符偏移时才用）。正常路径走 `typingMaxChars`。UI 上应标注"一般不用改"或藏进高级 |
| 14 | `typingMaxChars` | 滑块/数字框（int） | **1 – 10**（默认 3） | 是 | 会**改变分支判定**：超过它的移动从"短跳"变"跨行"，观感差异巨大（拉伸突然变大）。改动后建议提示"改完请连续打字/删除验证" |
| 15 | `animationResetThreshold` | **不做**（👻 死字段） | — | — | `grep` 只在注释中出现，代码零读取（`TrailCorner:259/268` 是注释）。**做进 UI 就是骗用户**。要么删字段，要么先接线再做 UI |
| 16 | `overlayFadeOut` | 复选框 | true/false | 否 | ⚠️ **已存在同名 Tools 菜单开关**（`ToggleFadeOutAction`）。两处必须同源，否则状态不一致（§4.3） |
| 17 | `cursorDisappearDelay` | 滑块（毫秒，int） | **0 – 500 ms**（默认 50） | 否 | `overlayFadeOut=false` 时置灰 |
| 18 | `cursorFadeOutDuration` | 滑块（毫秒 → ÷1000） | **0 – 1.0 秒**（默认 0.075） | 否 | 同上置灰。注意 `glowFade()` 里 `fade <= 0` 会被当作"立即结束"（`CaretAnimator:1231`），所以 0 是合法的 |
| 19 | `maxTrailDistanceFactor` | 滑块/数字框 | **1 – 60**（默认 60） | **是** | 它是**限幅**，常规移动无影响，只影响跨多行跳转。值越大拖尾越完整，但**重绘包围盒越大**（注释原话："跨十行以上跳转时可能退化成接近全屏的重绘"）。issue #7 还指出与 README 矛盾（4 vs 60） |
| 20 | `cursorWidth` | 滑块（0.5 步长） | **1.0 – 6.0 px**（默认 2） | 是 | 它同时是**几何基准**：短跳归一化、硬吸附方向、`maxTrailDistanceFactor` 的乘数都用它（`TrailCorner:306`、`CaretAnimator` 多处）。改它会**成比例放大/缩小所有拉伸尺度**，不是单纯"变粗"。见 §4.2 |
| 21 | `hideNativeCaret` | 复选框 | true/false | **强警告 + 副作用说明** | 改它 = 改**全局配色方案**的 `CARET_COLOR` 为透明。`NeovideCaretManager` 的类注释已记录过真实事故：**透明值会被 IDE 落盘写进 `colors/*.icls`，卸载插件后光标依然隐形**。UI 上必须有这句警告，并建议保留"恢复原生光标"入口 |
| 22 | `enabled` | 复选框 | true/false | 否 | 👻 **死字段**：代码用的是 `NeovideCaretManager.enabled`（另一个 static boolean），`CONFIG.enabled` 零读取。做 UI 前必须先统一（§4.3-③） |
| 23 | `trailMode` | 下拉框（`comboBox`）或单选组 | `SPRING` / `AFTERIMAGE` | 否 | 已有 Tools 菜单开关，**两处必须同源** |
| 24 | `afterimageLifetime` | 滑块（毫秒 → 秒） | **0.05 – 1.0 秒**（默认 0.22） | 是 | **仅 `trailMode==AFTERIMAGE` 生效**，用 `enabledIf` 联动。注意 `afterimageLifetime` 在运行期是**通过 `setLifetime()` 传入 AfterimageTrail 的**（`CaretAnimator:383/1355`），热改需要重新调一次（§4.3） |

**统计（24 项逐项点过）**：

- **可以做成控件：21 项**；
- **死字段、做 UI 前必须先接线或直接删：2 项** —— `animationResetThreshold`（代码零读取）、`enabled`（`CONFIG.enabled` 零读取，实际用的是 `NeovideCaretManager.enabled`）；
- **半死、只在兜底路径生效：1 项** —— `shortMoveThreshold`（`TrailCorner:206`，正常路径走 `typingMaxChars`）；
- **需要警告文案：10 项** —— `shadowBlurFactor`、`glowLayers`、`alwaysHighFps`、`animationLength`、`shortAnimationLength`、`typingMaxChars`、`maxTrailDistanceFactor`、`cursorWidth`、`hideNativeCaret`、`afterimageLifetime`；
- **需要联动置灰：6 项** —— `shadowColor / shadowBlurFactor / glowLayers / glowOpacity` ↔ `useShadow`；`cursorDisappearDelay / cursorFadeOutDuration` ↔ `overlayFadeOut`；`afterimageLifetime` ↔ `trailMode`。

> 我说"手感的三大来源"特指 `trailFactors`、`shortAnimationLength`、`trailSize`（见 §4.1），
> 它们同时也是**最不该在第一期开放**的三项。

### 3.2 重点：`float[] trailFactors` 怎么呈现

这是你点名的问题。先看它**实际怎么被用**（`TrailCorner.java:218-221`）：

```java
// 短跳分支
double factor = (rank < config.trailFactors.length ? config.trailFactors[rank] : 1.0f);
animationLength = config.shortAnimationLength * clamp(factor, 0, 1);
```

即：**四个角点按"运动方向对齐度"排名 rank(0..3)，每个 rank 取一个系数，乘以短动画时长**。
后缘（rank0，系数最大）= 慢、拖得长；前缘（rank3，系数最小）= 跟得紧。
**后缘/前缘比值 = 拉伸感**（当前 `1.0/0.22 ≈ 4.5 倍`）。

三种呈现方案：

| 方案 | 形态 | 优点 | 缺点 | 建议 |
|---|---|---|---|---|
| **A. 四个独立滑块** | rank0/1/2/3 各一个 0-100 滑块 + 一段说明 | 完全透明，对应代码 1:1 | 用户不知道"rank 是啥"；四个数可以调成非单调，产生**扭曲的四边形**（视觉 bug） | ⚠️ 可做，但要**强制单调不增**（在 `apply()` 里排序后写回），并加警告 |
| **B. 一个"拉伸强度"主滑块 + 高级区** | 主滑块控制比值 `k = f0/f3`（1.0 – 8.0），四角按固定形状 `{1, 0.72, 0.42, 0.22}` 等比缩放 | 用户只需一个旋钮就懂 | 失去了"四角独立"的自由度；形状被写死 | ✅ **推荐做默认** |
| **C. 不做** | — | 零风险 | 用户没法调拉伸 | 只在第一期 |

> 我的建议：**第一期只做 B（一个"拖尾拉伸强度"滑块，内部等比缩放 `trailFactors`）；
> 第二期若要 A，必须加单调性约束**。理由见 §4.1：`trailFactors` 是"手感"的三大来源之一，
> 让用户随便调四个数，等于把 LESSONS.md 里"不要凭直觉调参数"的坑直接交到用户手上。

### 3.3 其他"不是直觉控件"的字段

- **所有 float 参数**：DSL `slider(int,int,int,int)` 只收 int（本机 `javap Row` 实测），
  必须 `×100` / `×1000` 映射，并在标签旁显示真实值（"0.11 s"）。
  这是纯粹的样板代码，不是难点，但**每条都要写**。
- **颜色**：`ColorPanel` 在本机 jar 里可用，但它给的是 `java.awt.Color`；
  而配置里是 `#RRGGBB` 的 String，且**透明度是独立字段 `tailOpacity`**。
  建议内部改成存 `int rgb`（学 `smear-cursor` 的 `cursorColorRgb`），UI 层做 String ↔ Color 转换。
- **毫秒 vs 秒**：`cursorDisappearDelay` 是 int 毫秒，其余 4 个时长是 float 秒。
  UI 上**统一显示毫秒**（"110 ms"），内部各按原单位写回 —— 否则用户会被 `0.11` vs `110` 搞晕。
- **`maxTrailDistanceFactor` 的量纲**：它是"×光标长边"，60 × 32px 行高 ≈ 1920px（注释原话）。
  UI 上显示 **"最大拖尾长度 = 60 × 32px ≈ 1920px"** 比裸数字友好。

---

## 四、风险清单

### 4.1 哪些参数改动会影响"手感"（对照 LESSONS.md「不要再做的三件事」）

LESSONS.md 的"三件事"是**针对"优化 fps"**的三条死路（优化绘制 / 缩小重绘面积 / 降辉光层数）。
它们对"设置界面"的约束是**间接但致命**的：既然插件性能已经到顶（12 层辉光只占 0.6% 预算），
那么**任何"更炫"的参数都会直接吃 EDT 时间，且没有优化空间可回退**。

| 字段 | 手感/性能影响 | 与 LESSONS 的关系 | 风险级 |
|---|---|---|---|
| `trailFactors` | **拉伸感的根本来源**（4.5 倍梯度）。改动直接改变"有没有拖尾" | 与坑 3（`MIN_ANIMATION_FRAMES` 把梯度抹平）同类：**梯度一没，拉伸就消失** | 🔴 高 |
| `shortAnimationLength` | 与 `trailFactors` **相乘**决定四角时长。0.11 是实测调出来的 | 坑 1/2/3 全是"时长/步进被改坏" | 🔴 高 |
| `animationLength` | 跨行动画时长；`leading = animationLength × (1 - trailSize)` | 同上 | 🔴 高 |
| `trailSize` | `leading = animationLength × (1 - trailSize)`。**=1 时 leading=0 → 前缘瞬时吸附** | 这是"拖尾有没有"的总开关 | 🔴 高 |
| `typingMaxChars` | 决定短跳/跨行**分支切换点**。改小会让"连删 3 字符"跳到大拖尾分支 | 坑 4（短跳判据用错单位导致"英文丝滑、中文卡"）**就是这一类** | 🟠 中高 |
| `cursorWidth` | 它不只是粗细：**所有归一化位移、方向对齐、最大拖尾长度都以它为基准** | 坑 4 里 `cursorWidth = 2px` 是判据失败的直接原因之一 | 🟠 中高 |
| `animationResetThreshold` | **无影响（死字段）** | — | ⚪ 无 |
| `maxTrailDistanceFactor` | 常规移动无影响；跨多行跳转时决定重绘包围盒 | 与"减少重绘面积"那条**方向相反**（这条是"面积大了反而快"的边界） | 🟡 中 |
| `glowLayers` | 12→8 是因为 `fill(Path2D)` 占 9.3% EDT | **正是 LESSONS 说"降辉光层数无 fps 收益"的那一项** —— 注意这两句不矛盾：<br>降层数**不提 fps**，但**确实省 EDT**（9.3% 是 JFR 采样，不是 fps 差异） | 🟡 中 |
| `shadowBlurFactor` | 直接决定 `glowPadding` → 每帧重绘包围盒 | 同上 | 🟡 中 |
| `alwaysHighFps` | **每秒 125 次 EDT 投递、永不停歇**。是"冷启动延迟"与"EDT 抢占"的直接权衡 | LESSONS 第三部分"真正的瓶颈是 EDT 抢占"—— 这个开关就是在和那个瓶颈对赌 | 🔴 高 |
| `hideNativeCaret` | 改动会重排"谁负责画光标"：true = 覆盖层当光标（依赖 EDT 调度）；false = 原生光标零延迟 + 覆盖层叠加 | LESSONS 第五部分明确写"上游用 `hideNativeCaret` 分两条路，**这是对的**" | 🔴 高 |
| `overlayFadeOut` / `cursorDisappearDelay` / `cursorFadeOutDuration` | 只影响"停下后辉光怎么灭"；但 `fading` 判据曾导致 **EDT 永不降频**（坑 8） | 坑 8 原文：`fade == 0` 时 `0 < 1` 恒真 → 空闲也 8ms 空转。**改这三个值会改变 `fading` 为真的持续时间**，间接影响空闲功耗 | 🟡 中 |
| `trailMode` / `afterimageLifetime` | 换渲染模型，行为完全不同 | — | 🟠 中高 |
| `tailColor` / `tailOpacity` / `shadowColor` / `glowOpacity` / `useShadow` | 纯颜色，不参与时序 | 但 `tailOpacity` 改了以后 `CaretTrailPanel.refreshStyles()` **必须被调用**（它把 String 解析成 Color 缓存在字段里，`CaretTrailPanel:122-125`） | 🟢 低（前提是调 refreshStyles） |

### 4.2 参数之间的耦合（必须成对考虑）

我按"代码里真的互相引用"列出来的，不是想象：

#### 耦合 1：`trailFactors` × `shortAnimationLength` → 拉伸强度
```java
animationLength = config.shortAnimationLength * clamp(config.trailFactors[rank], 0, 1);
```
（`TrailCorner.java:220-221`）
- 后缘时长 = `shortAnimationLength × trailFactors[0]`
- 前缘时长 = `shortAnimationLength × trailFactors[3]`
- **"拉伸感" = 这两个的比值**（当前 0.11×1.0 = 110ms vs 0.11×0.22 = 24ms → 4.58 倍）
- **改任何一个都会改变比值或绝对时长**。UI 上这两个必须放在同一组，并显示"当前后缘 110ms / 前缘 24ms / 比值 4.6×"。

#### 耦合 2：`animationLength` × `trailSize` → 跨行拖尾
```java
double leading  = config.animationLength * clamp(1f - config.trailSize, 0f, 1f);
double trailing = config.animationLength;
```
（`TrailCorner.java:227-228`；与 Neovide 源码 `leading = animation_length * (1 - trail_size)` **逐字一致**，
见 `src/renderer/cursor_renderer/mod.rs:174-175`）
- `trailSize = 1` → leading = 0 → rank≥2 的角**瞬间到位**（"硬吸附"的替代品）
- 所以 `trailSize` 调到 1 附近时，`animationLength` 的改动**只影响后缘**；
  调到 0 附近时，四角同长、**拖尾消失**。UI 上应在 `trailSize → 1` 时提示"前缘不再有过渡"。

#### 耦合 3：`shortAnimationLength` × `animationLength` → 分支不一致
原版 Neovide 的短跳是 `animation_length.min(short_animation_length)`（取 min，见 `mod.rs:172`），
**本插件改成了直接 `shortAnimationLength × factor`**（不取 min）。
→ 当前默认 `shortAnimationLength(0.11) > animationLength(0.10)`，
**如果按 Neovide 语义取 min，短跳会变成 0.10 而不是 0.11**。
用户把任一值调过对方时，观感会"突然反过来"。UI 上应显示一行提示：
"⚠️ 当前短动画(110ms) > 常规动画(100ms)，这是本插件有意为之"。

#### 耦合 4：`trailFactors` × 排名方向（易错点）
```java
// rank 0 = 最靠后（最长动画、最慢），3 = 最靠前、最跟手
```
（`TrailCorner.java:136-137` 的 javadoc）
→ **索引 0 是"最拖"**。如果 UI 用户以为 index0 是"最快"，调反了会得到"光标跑得比拖尾慢"的怪状。
**这是 `trailFactors` 做 UI 最容易出的错**，必须在界面上写明。

#### 耦合 5：`cursorWidth` × 一切几何
```java
double maxDistance = Math.max(width, height) * config.maxTrailDistanceFactor;
```（`TrailCorner:306`）
以及短跳归一化 `jumpX = deltaXpx / width`（`TrailCorner:204`）。
→ 改 `cursorWidth` 会**按比例改变**"多少算一次短跳"和"最大拖尾多长"。

#### 耦合 6：`alwaysHighFps` × `hideNativeCaret`（最强的耦合）
- `hideNativeCaret = true` 时，覆盖层就是唯一的"光标"，跟随完全依赖 EDT 调度；
  `alwaysHighFps = false` 时空闲降到 120ms/600ms → **看起来就像光标卡住**。
- `hideNativeCaret = false` 时原生光标零延迟跟随，`alwaysHighFps` 可以关掉。
- 所以这两项**必须联动提示**，理想形态是一个二选一的"档位"：
  ```
  ○ 覆盖层当光标（需常驻刷新，占用 EDT）
  ○ 原生光标 + 拖尾叠加（省电，但两个光标重叠）
  ```
  而不是两个独立复选框。**这是本报告最重要的 UI 结构建议。**

#### 耦合 7：`useShadow` → `shadowColor / shadowBlurFactor / glowLayers / glowOpacity`
`CaretTrailPanel:265/433` 用 `useShadow` 直接门控整段辉光绘制。
→ 四个从属字段应 `enabledIf(useShadow)`。

#### 耦合 8：`overlayFadeOut` → `cursorDisappearDelay / cursorFadeOutDuration`
`CaretAnimator:1215` 是硬门控：`if (!config.overlayFadeOut) return 1f;`
→ 后两者应 `enabledIf(overlayFadeOut)`。

#### 耦合 9：`trailMode` → `afterimageLifetime`（以及所有 SPRING 相关字段）
`CaretAnimator:672` 按 `trailMode` 二选一；`afterimageLifetime` 只在 AFTERIMAGE 用。
→ AFTERIMAGE 时，`animationLength / shortAnimationLength / trailSize / trailFactors / shortMoveThreshold / typingMaxChars / maxTrailDistanceFactor` **全部无效**。
→ 反过来，`afterimageLifetime` 在 SPRING 下无效。
**建议 UI 按 `trailMode` 整组置灰**，否则用户会调一堆"没反应"的滑块 —— 这是最容易被投诉的体验问题。

#### 耦合 10：`shadowBlurFactor` → `glowPadding` → 重绘包围盒
`CaretAnimator:388`（构造时算一次）、`1018-1019`（每次 `refreshTarget` 更新）。
→ 改了 `shadowBlurFactor` 之后，**所有已经存在的 animator 的 `glowPadding` 要等下一次 `refreshTarget` 才会更新**，
而 `refreshTarget` 有快速路径（位置没变就跳过）。**热改可能不立即生效**（§4.3）。

### 4.3 已有代码里阻碍设置界面的东西（逐条给证据）

#### ① `NeovideConfig` 是纯静态单例、无持久化

```java
// NeovideCaretManager.java:48
private static final NeovideConfig CONFIG = new NeovideConfig();
```
- **不是 service**，没有 `@State`，没有 `getState/loadState`。
- 官方明确要求持久化必须走 service（不能是 extension），所以**必须新建一个 application service**。
- 迁移路径（低风险）：新增 `NeovideCursorSettings implements PersistentStateComponent<State>`，
  `CONFIG` 改为从该 service 取；**`NeovideConfig` 保留为纯 POJO/默认值来源**，避免大改。

#### ② 两个开关走 `PropertiesComponent`，与 `NeovideConfig` 不一致

```java
// NeovideCaretManager.java:92 / 100
private static final String PROP_ALWAYS_HIGH_FPS   = "neovide.cursor.alwaysHighFps";
private static final String PROP_OVERLAY_FADE_OUT  = "neovide.cursor.overlayFadeOut";
```
- 这两个字段**既是 `NeovideConfig` 的字段**（`alwaysHighFps` / `overlayFadeOut`），
  **又在 `PropertiesComponent` 里各自存了一份**（`setAlwaysHighFps` / `setOverlayFadeOut` 里写盘）。
- **后果**：一旦做了 `PersistentStateComponent`，就出现**三个真相源**
  （NeovideConfig 字段 / PropertiesComponent / 新的 XML service）。必须**迁移并删除 PropertiesComponent**，
  否则用户会遇到"设置面板里是 A，Tools 菜单里是 B"。
- 迁移注意：老用户的 `alwaysHighFps` / `overlayFadeOut` 现值在 `PropertiesComponent` 里，
  **要做一次性迁移**（读旧键 → 写新 state → 删旧键），否则升级后用户设置被重置。
- 另外 `restorePersistedSettings()` 的默认值也不一致：
  `alwaysHighFps` 读不到时用 **false**（`NeovideCaretManager:350`），
  而 `NeovideConfig.alwaysHighFps` 字段默认是 **true**。**这是个已存在的 bug**：
  干净安装 + 从未点过菜单 → 实际是 false，与文档宣称的默认 true 不符（只有点过菜单才会写 true）。

#### ③ 参数被多个类直接读 static 字段

**这是最难的一条。** 让我把真实情况列出来：

```
CaretAnimator     持有 private final NeovideConfig config;   （构造时注入，:178/:367）
TrailCorner       持有 private final NeovideConfig config;   （:45/:60）
CaretTrailPanel   持有 private final NeovideConfig config;   （:39/:115）
AfterimageTrail   持有 private final NeovideConfig config;   （:58/:66）
```

好消息：**这四个类的 `config` 都是同一个对象引用**（都来自 `NeovideCaretManager.CONFIG`），
不是拷贝。所以 **改字段值本身是"立即全局可见"的** —— 这是我原本担心的点，实测不成立。

坏消息：**只有一部分读取发生在"每帧"，另一部分被缓存/快照了**：

| 读取方式 | 字段 | 是否热改立即生效 |
|---|---|---|
| **每帧直接读** | `maxTrailDistanceFactor`（`TrailCorner:306`）、`alwaysHighFps`（`CaretAnimator:700`）、`overlayFadeOut` + `cursorDisappearDelay` + `cursorFadeOutDuration`（`glowFade()`，`:1193/1215/1226/1230`）、`cursorWidth`（`refreshTarget` 每帧）、`trailMode`（`:672`） | ✅ 下一帧生效 |
| **每次绘制时读** | `glowLayers` / `glowOpacity` / `useShadow` / `tailColor` / `tailOpacity` / `shadowColor`（`CaretTrailPanel.drawQuadGlow:265-283`） | ✅ 下一帧生效（颜色缓存见下一行） |
| **仅在 caret 移动（`jump()`）时读** | `animationLength` / `shortAnimationLength` / `trailSize` / `trailFactors` / `typingMaxChars` / `shortMoveThreshold`（`assignRanks→jump`，`CaretAnimator:1136/1397` → `TrailCorner:201-235`） | ✅ 生效（下一次光标移动时；用户感知与立即生效无差别） |
| **构造时算一次 / 缓存** | `shadowBlurFactor` → `glowPadding`（`:388`，之后只在 `refreshTarget` 命慢路径时更新 `:1018-1019`）；`tailColor`/`tailOpacity`/`shadowColor` → `CaretTrailPanel.fillColor/glowColor`（`:122-125` `refreshStyles()`）；`afterimageLifetime` → `AfterimageTrail.setLifetime()`（`:383`，只有 `onModeChanged` 会重调 `:1355`） | ❌ **需要主动触发刷新** |
| 由 `NeovideCaretManager` 的 static 方法做副作用 | `hideNativeCaret`（要改配色方案）、`enabled`（要 install/dispose animator） | ❌ **必须走 setter，不能直接改字段** |

**所以设置面板的 `apply()` 不能只写字段**，必须：

1. 写字段；
2. 对颜色类改动，遍历 `ANIMATORS` 调 `animator.onModeChanged()`（它内部会 `panel.refreshStyles()`，`:1362`）
   —— 这是**现成可用的"全量刷新"入口**，虽然名字是"模式切换"，副作用正好够用；
3. 对 `shadowBlurFactor` 改动，同样借 `onModeChanged()`（它会 `forceResync = true`，强制走慢路径重算 `glowPadding`）；
4. 对 `hideNativeCaret` / `enabled` / `trailMode` / `alwaysHighFps` / `overlayFadeOut`，
   **必须调用 `NeovideCaretManager` 已有的 setter**（它们已经写好了副作用与落盘），
   不能直接赋值。

> **这就是我建议第一期"OK 生效"而不是"实时预览"的技术原因**：
> `apply()` 需要跑一套"字段 → 副作用 → 刷新"的编排，这套编排**现在不存在**，
> 得先补出来（`NeovideCaretManager.applyConfig(...)` 之类）。
> 而一旦有了这套编排，**实时预览也就只是把它挂到控件的 onChanged 上** —— 所以顺序应该是
> "先做编排，再考虑实时"。

#### ④ 其余零散阻碍

| 阻碍 | 证据 | 影响 |
|---|---|---|
| 颜色是 `String "#RRGGBB"` | `NeovideConfig:14/23` + `NeovideColors.parse` | `ColorPanel` 给 `Color`，要转换；非法字符串行为未知（`NeovideColors.parse` 未审查异常路径） |
| `trailMode` 是枚举 | `NeovideConfig:272/281` | 持久化没问题（enum 在官方白名单），但 UI 要用 `comboBox` |
| `plugin.xml` 源码里没有 `<idea-version>` | 源文件 `src/main/resources/META-INF/plugin.xml` **0 命中** | **已查明是构建期注入**：`build/offline-build.ps1` 的 "[2/4] 注入 plugin.xml" 段把 `<version>` 和 `<idea-version since-build="$SinceBuild" />`（默认 `242`）拼在 `<idea-plugin>` 开标签后。**不是 bug**，但意味着**直接读 repo 里的 plugin.xml 会漏看这两个标签** —— 做设置界面新增 EP 时要记住它们是在构建期才出现在 jar 里的 |
| 没有 `<applicationService>` 声明 | 同上 | 新增 service 要加 EP |
| `ToggleAlwaysHighFpsAction` 是**孤儿类** | 它在 `src/ui/` 里、`NeovideCaretManager` 有 `setAlwaysHighFps`，但 `plugin.xml` 的 `<actions>` **没有注册它**（5 个 action 里只有 Toggle / PaintDiag / TrailMode / HideNativeCaret / FadeOut） | 死代码。做设置面板时正好可以把它清掉或重新注册 |
| `CONFIG.enabled` 与 `NeovideCaretManager.enabled` 双份 | `NeovideCaretManager:50` vs `NeovideConfig:267` | 见 §3.1 #22 |
| `animationResetThreshold` 完全没接线 | `TrailCorner:259/268` 仅注释 | 见 §3.1 #15 |

### 4.4 分阶段实现建议

#### 第一期（低风险 · 高价值 · 建议只做这些）

**目标**：让用户能调"观感"和"颜色"，不改动画时序，不碰副作用路径。

| 字段 | 为什么放第一期 |
|---|---|
| `enabled`（开关） | 用户第一需求；但**必须先把死字段 `CONFIG.enabled` 与 `NeovideCaretManager.enabled` 统一** |
| `trailMode`（下拉） | 已有 Tools 菜单实现，直接复用 setter |
| `hideNativeCaret`（复选框 + 强警告） | 已有 setter 与完整副作用处理 |
| `alwaysHighFps`（复选框 + 强警告） | 已有 setter；但要**顺手修掉 §4.3-② 的默认值 bug** |
| `overlayFadeOut`（复选框） | 已有 setter；同样要迁移 PropertiesComponent |
| `tailColor` / `tailOpacity` | 纯观感，靠 `onModeChanged()` 刷新即可 |
| `useShadow` / `shadowColor` / `glowOpacity` | 同上；`glowOpacity` 是每帧读取，天然热生效 |
| `glowLayers`（1-12 滑块 + 性能提示） | 每帧读取；只需一句警告 |
| `trailSize`（0-1 滑块） | **有官方取值范围依据**，每帧读取，语义最清晰 |
| `cursorDisappearDelay` / `cursorFadeOutDuration` | 每帧读取；配合 `overlayFadeOut` 联动 |
| `"拖尾拉伸强度"` 复合滑块（内部等比缩放 `trailFactors`） | 把最危险的数组包装成**一个安全旋钮**（§3.2 方案 B） |
| `shadowBlurFactor`（0.05-0.6 + 提示） | 每帧间接影响；借 `onModeChanged()` 刷新 padding |

**第一期明确不做**：任何 float 数组的直接四滑块、`shortMoveThreshold`、`animationResetThreshold`、`afterimageLifetime`、`cursorWidth`、`maxTrailDistanceFactor`。

**第一期的技术前提（必须先做，否则做不了）**：
1. 新建 `NeovideCursorSettings`（`@State` + `PersistentStateComponent`）+ `<applicationService>`；
2. 一次性迁移 `PropertiesComponent` 的两个旧键，然后**删掉它们**；
3. 在 `NeovideCaretManager` 加一个 `applyConfig()` 编排方法（写字段 → 调 setter → 遍历 ANIMATORS 刷新）；
4. 修掉 `alwaysHighFps` 默认值不一致（`restorePersistedSettings` 用 false，字段默认 true）。

#### 第二期（需要先补编排 / 需要联动验证）

| 字段 | 前置条件 |
|---|---|
| `animationLength` / `shortAnimationLength` | 必须与 `trailFactors` 同组显示"后缘/前缘/比值"，且要有上界保护（≤ 0.3 s） |
| `typingMaxChars` | 需要"改完请连续打字/删除验证"的提示 |
| `maxTrailDistanceFactor` | 需要先解决 issue #7（README 4 vs 代码 60 的矛盾），否则 UI 上的说明没有权威依据 |
| `afterimageLifetime` | 需要 `trailMode` 整组联动（AFTERIMAGE 才启用） |
| `cursorWidth` | 影响所有几何尺度，建议只给 1-4 的窄范围 + 强提示 |
| `trailFactors` 四滑块（高级） | 必须强制单调不增 + 显示 rank 方向说明 |
| 真正的"实时预览" | 需要先决定"预览画在哪"（面板内自绘 vs 编辑器内），见 §2.5 |

#### 建议永远不做成 UI

| 字段 | 理由 |
|---|---|
| `animationResetThreshold` | **死字段**，做进 UI 就是欺骗用户。应当**删除该字段**而不是给它做控件 |
| `shortMoveThreshold` | 只在"拿不到字符偏移"的兜底路径生效（`TrailCorner:206`），正常使用中**永远不会触发**；做成 UI 只会让用户调了没反应 |
| `enabled`（作为 config 字段） | 它本身就是死字段；开关应该是 UI 元数据而不是 config 字段（或者干脆统一到 `NeovideCaretManager.enabled`） |
| `ACTIVE_DELAY_MS`（`CaretAnimator:77`，不是 config 字段） | START-HERE.md 已实测"12 会傻快"，**明确写"不要改"**。同理 `IDLE_DELAY_MS = 120`、`IDLE_UNFOCUSED_DELAY_MS = 600`、`MAX_ADAPTIVE_DELAY_MS = 40`、`MAX_DT`、`RECOVERY_GAP_SECONDS`、`BUSY_RETRY_MS` 都不应暴露 |

---

## 五、还没查到 / 无法证实的部分（如实列出）

| 问题 | 状态 |
|---|---|
| "neo-cursor-fix/extension/assets/neovide-cursor.js" 这个路径 | **未证实**：在 75★ 的 `LengineerC/vscode-neovide-cursor` 里不存在；也没搜到叫 `neo-cursor-fix` 的仓库 |
| `cursorConfig` 对象 | **未证实存在**：上述仓库全文 0 命中。可能是另一个版本/fork |
| 原版 `shadowBlurFactor = 0.6` | **与 75★ 原版不符**：该版写的是 `const shadowBlur = 10` |
| `useHardSnap` / `leadingSnapFactor` / `leadingSnapThreshold` / `snapAnimationLength` 的出处 | **未证实**：`src/` 已删除；75★ 原版也 0 命中；只有 `Tasomei` 那个第三方变体有高度相似但不同名的 `useLeadingBoost/leadingBoostFactor` |
| Neovide 官方是否有辉光/淡出配置 | **已证实没有**（文档 Cursor Settings 段 + 源码 `CursorSettings` 结构体） |
| Kotlin UI DSL 的 `onChanged` 官方文档 | **文档未提及**（只有字节码里有，带 `@ApiStatus.NonExtendable`） |
| `Configurable.cancel()` 从哪个平台版本开始有 | **未证实**（本机 2026.1 字节码里有；GitHub 源码因 API 限流只拿到部分；官方 settings-guide 正文没写） |
| ~~`plugin.xml` 里为什么没有 `<idea-version>`~~ | **已查明**：`build/offline-build.ps1` 在构建期把 `<version>` 与 `<idea-version since-build="242" />` 注入到 `<idea-plugin>` 之后（"[2/4] 注入 plugin.xml" 段，变量 `$SinceBuild` 默认 `'242'`）。源码里没有是正确的 |
| Neovide 文档站直连 | **抓不到**（`neovide.dev` / `neovide.github.io` 在本机网络下 fetch 失败），只能走 GitHub API 读源文件 |

---

## 六、一句话结论

**技术上完全可行（已用编译探针证明 Java 能调 Kotlin UI DSL），但真正的成本不在"画面板"，
而在"改完值怎么让 20 多个已存在的 animator 立刻按新参数跑"——这套编排现在不存在，
而且颜色/辉光 padding/afterimage 生命周期这三类值是缓存的，必须显式刷新。
所以第一期应该：先把配置搬进 `PersistentStateComponent` + 建一个 `applyConfig()` 编排，
然后只上"开关类 + 颜色类 + 有官方取值依据的 trailSize + 一个包装过的拉伸强度滑块"。**
