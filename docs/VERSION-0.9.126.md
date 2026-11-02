# Neovide Cursor for JetBrains v0.9.126 版本说明

> 面向用户与决策层 · 2026-11-02
>
> 本文所有数字都附了来源；仓库里查不到、无法复现的项会明确标「待核实」，不写成结论。
>
> 关联交付报告：[GLOW-FADE-0.9.126-REPORT-2026-11-02.md](GLOW-FADE-0.9.126-REPORT-2026-11-02.md)

---

## 一、版本速览

| 项 | 值 | 依据 |
|---|---|---|
| 版本 | **0.9.126** | jar 内 `META-INF/plugin.xml` 的 `<version>0.9.126`（文件名恒为 `jetbrains-neovide-cursor-0.9.8.jar`，不能拿文件名判断版本） |
| 日期 | **2026-11-02** | tag / 提交时间 |
| git tag | **annotated tag `v0.9.126`**，指向提交 **`1dcaa7a`** | `git cat-file -t v0.9.126` 返回 `tag`；`git rev-list -n 1 v0.9.126` = `1dcaa7a` |
| 主修复提交 | **`2c1004b`**（10 文件，+565 / −589） | 提交记录与报告 §9.2 |
| 审查补强提交 | **`3aabb0c`**（5 文件，+71 / −23，只改探针与注释） | 报告 §9.2 |
| 报告 / tag 提交 | **`1dcaa7a`** | `git show -s v0.9.126` |
| 状态文档同步 | **`54cebef`**；版本说明 / README **`9933be5`**；当前 HEAD 见 `git log -1` | `git log -1` |
| 已安装 IDE | **IDEA 2026.1** 与 **PyCharm 2025.2**，均为 0.9.126 | 两边 jar 内 `<version>0.9.126`；md5 与 `dist/neovide-cursor-0.9.126.jar` 一致 |
| dist 产物 | `dist/neovide-cursor-0.9.126.jar`，**246,919 B**，md5 **`4cbbd854719879bfd522b63df8e80897`** | `md5sum` / `stat` 实测；报告 §5.4 |
| 发布状态 | 本地与 `fork` 已同步（`git rev-list --left-right --count fork/main...HEAD` = `0 0`），tag 已推到 `fork`；**GitHub Release 原本停在 v0.9.83**；v0.9.126 的 Release 在本次发布流程中创建（见仓库 Releases）；Marketplace 上架状态未在仓库内记录，**待核实** | `git ls-remote --tags fork refs/tags/v0.9.126`；`gh release list` |

**版本区间背景**：自 `v0.9.83` 以来共有 **106 个提交**（含版本说明与文档同步；`git log --oneline v0.9.83..HEAD | wc -l`）。`v0.9.83` 之后，**0.9.84 至 0.9.125 连续 42 个版本号没有打 tag**；`v0.9.126` 是这一段区间里唯一新打的 tag。因此不要用「tag 列表」判断中间版本，`git tag` 在 0.9.83 与 0.9.126 之间是空的。

---

## 二、这是什么

**一句话定位**：给 JetBrains IDE 换上 Neovide 风格的光标拖尾 —— 光标移动时不是整块平移，而是被四个角上的阻尼弹簧「拉开」，再收拢。

**核心机制**（细节见 `CLAUDE.md` §4.1 与 `README.md` 的「Trail physics」）：

1. 光标矩形有四个角，每个角各挂一套 X / Y 阻尼弹簧（`DampedSpring`）。
2. 每次光标跳动，按「运动方向与角点方向的夹角」给四个角排名（`assignRanks`）。
3. 排名映射到 `trailFactors[4]` 的不同系数，再乘上动画时长 —— 领先的角跟得紧，落后的角跟得慢。
4. 四个角速度不同，规整矩形就被拉成不规则四边形。**视觉上是「拖尾」，不是叠加残影。**

所以「拖尾」和「残影」是两套模型：

- **弹簧模式（SPRING，默认）**：实时计算四角位置，多光标也走这一套。
- **残影模式（AFTERIMAGE，实验性）**：记录真实历史位置，掉帧只会让残影变稀，但轨迹本身不变形。目前只跟随主光标，且辉光淡出不适用于它。

---

## 三、当前功能总览

### 3.1 总开关与运行模式

- `enabled`：总开关，默认开。
- `trailMode`：`SPRING`（默认）/ `AFTERIMAGE`（实验性）。
- `hideNativeCaret`：默认开。插件用覆盖层自己画光标，避免和 IDE 原生光标叠成「两个光标」。
- `alwaysHighFps`：默认开；不让光标在停手后立刻降到空闲帧率。
- `targetFps`：只允许 **125 / 144** 两档（代码里的白名单 setter 写死；手改 XML 塞 240 也会被改回 125）。
- `multiCursorNoGlow`：多光标时关闭辉光，默认关。

来源：`NeovideCursorSettings.State` 默认值、`SettingsField.build()`、`PARAMETER-MAP.md`。

### 3.2 颜色与渐变

当前有三条互斥的填充路径：

```
cursorRgbWash = true                      -> 彩虹：色相循环（buildRgbWashPaint）
cursorRgbWash = false + rgbGradient = true -> 双拼：上端拖尾色 / 下端由辉光色算出（buildDualTonePaint）
两者都 false                               -> 纯色
```

- **彩虹判定不在 State 里**：`CaretAnimator.resolveRgbWash()` 从「当前颜色匹配哪一个颜色预设」反推，匹配到带 `rgbWash` 标记的预设才走彩虹。匹配不上任何预设时返回 `false`，不会误触发。
- **辉光始终使用 `shadowColor`**，不跟随光标本体色。这是从引入到现在一直是这样的行为（`CLAUDE.md` §4.3 查过全部 git 历史），不是本版新增。
- 内置颜色预设 **6 个**：原版粉色、冰蓝、薄荷、藕荷、暖沙、彩虹（动态）。`CursorPresets.COLOR_PRESETS` 实测 6 项。
- 动画预设**完全不管颜色**：切动画预设不会改 `tailColor` / `shadowColor` / `rgbGradient`。这是 2026-10-28 的决定，详见 `PARAMETER-MAP.md` §五。

### 3.3 单行输入手感（打字 / 退格）

- `shortAnimationLength`：同短跳的拖尾时长，默认 **0.11s**。
- `typingSmoothness`：把四角系数往 1 拉平，默认 0。**它是全局项，不跟随动画预设**（2026-10-28 起真正移出预设域）。
- `stretchRatioK`：拉伸强度，默认 **4.5**，范围 1–8；用纯函数 `deriveTrailFactors(k, smooth, slope)` 派生四角系数。
- `slopeTicks`：斜度，默认 **0**（纯矩形），范围 0–100；100 = 完全对齐上游的四档梯度。
- `leadingSnap`：「前缘贴合（半边拖尾 · 全局）」复选框，默认关。**当前是占位开关：能勾、能存，但不改变任何观感**，文案已写「待实现」。原因见 §4.3。

短跳判据用的是编辑器的**真实字符偏移量**（`charsMoved`），不是像素宽度；CJK、emoji、改字号都不会让判据漂移。这个设计在 0.9.83 之前就已存在，不是本版新增。

> 数值来源：`NeovideCursorSettings.State` 默认值；范围来源：`SettingsField.build()`。

### 3.4 多行缎带（跨行 / 跳转）

- `animationLength`：跨行跳转的基础拖尾时长，默认 **0.1s**。
- `trailSize`：前缘与后缘的时长差，默认 **1.0**。
- 跨行时位移大（约一行高 32px），四角排名差被放大，视觉上就是一条斜向的「缎带」。
- 预设「缎带」会把这条缎带拉得更长；跨行拖尾在报告里实测可见约 430ms（`GLOW-FADE-0.9.126-REPORT-2026-11-02.md` §3.2 的 0.28s 缎带数据）。

> 数值来源：`NeovideCursorSettings.State` 默认值。

### 3.5 辉光与淡出

**辉光参数**：

- `useShadow`：启用辉光，默认开。
- `shadowColor`：辉光颜色，默认 `#FFC0CB`。
- `glowOpacity`：辉光峰值不透明度，默认 **0.55**。
- `glowLayers`：辉光层数，默认 **8**；层数只影响过渡平滑度，**不改变整体亮度**。
- `shadowBlurFactor`：辉光锐度 / 外扩半径，默认 **0.2**。

**淡出参数**：

- `overlayFadeOut`：辉光淡出总开关，默认开。
- `cursorDisappearDelay`：淡出前保持时长，默认 **50ms**（最大 500ms）。
- `cursorFadeOutDuration`：淡出时长，默认 **0.075s**（最大 1000ms）。

**0.9.126 的淡出模型**（核心变化）：

```
目标亮度 = strengthFor(当前四角最大偏移)      // 拖尾还在，目标就不会归零
当前亮度上升：立即到目标                      // 按键立刻回满
当前亮度下降：每帧最多下降 dt / 淡出时长       // 淡出时长决定「降多慢」
保持期：从「最后一次移动」起算                  // 不是从拖尾收拢起算
```

每一根 trail 只保存自己的 `brightness` 与 `lastMoveNanos`；同组渲染时取组内最大亮度，但**不再把组亮度写回成员**。这样拆组后，留下的静止光标不会单独发光。

**边界**：只淡**辉光层**，光标本体始终不透明；残影模式不适用（残影有自己的生命周期淡出）。

> 数值来源：`NeovideCursorSettings.State` 默认值；量程来源：`SettingsField.build()` 的 `.range(...)`。

### 3.6 预设

- **3 个内置动画预设**：基础 / 缎带 / 纯平滑输入（`ANIM_PRESETS` 实测 3 项）。
- **6 个内置颜色预设**：见 §3.2。
- **自定义预设**：最多 **50** 个（`CustomPresets.MAX_PRESETS = 50`）；动画与颜色各有一张表，共用同一套父链与序列化格式。
- **内置预设只读**：改任何参数会自动派生一个「XX 副本」自定义预设，内置值本身不变。
- **父链模型**（2026-10-29，`199519a`）：自定义预设记住「派生自谁」；点「恢复默认值」回到它派生自的内置预设，而不是跳回父预设的当前值。修的是 BUG-009 那类「恢复默认值反而跳回父预设」的回归。
- **预设域统一为 13 个生效字段**：`PRESET_FIELDS` == `applyAnimPresetFields` 写入集合 == `matchAnimPreset` 比较集合。2026-10-28 由 26 收缩，颜色三字段与 `typingSmoothness` 移出。守门探针 `VFieldDomain`。
- **已知技术债**：`AnimPreset` 里仍保留 `smoothTicks` 字段与访问器，但已经没有生产用途（纯死数据）；删除会牵动构造器签名与三个预设定义，收益低于风险，暂时保留。

### 3.7 多光标

- 弹簧模式（默认）支持多光标：`CaretAnimator` 用 `Map<Caret, SpringTrail>` 管理每一根光标。
- `multiCursorNoGlow` 打开后，光标数 ≥ 2 时不画辉光（代码在 `CaretAnimator` 里按 activeTrails 数量判断）。
- 残影模式只跟随主光标（`README.md` Known Issues #1），这是已知限制。
- 0.9.126 的「逐 trail 淡出」顺带修了多光标的一个隐藏缺陷：一个光标移动、另一个静止，移动的光标跳走或组拆开后，静止光标不再以「组满亮」为起点单独淡出一整个淡出时长。

### 3.8 IME（中文输入法组合态）

- 组合中间态的判据是**几何判据**：比较 `editor.offsetToXY(offset).x` 与 `editor.visualPositionToXY(vp).x`；不一致即视为组合中间态，本帧跳过重算。
- 连续跳过有上界 `IME_MAX_SKIP_FRAMES = 15` 帧（约 120ms，`CaretAnimator.java`），防止无限期冻结。
- 排除了 virtual space（光标在行尾之后时两个 API 本来就该不同），否则会误判成组合态。
- 0.9.83 之后补了三类几何缺口：Ctrl+滚轮改字号后的纵向漂移（`8d3e787`）、block inlay 撑高行导致的错位（BUG-006，`0956d44`）、慢路径抛出后重算标志丢失（U-M-7，`8ce6524`）。
- 平台 252 要求「读 Editor / Caret / Document 必须在 read action 内」，相关读取已包进 `ReadAction.compute`（0.9.104）。

### 3.9 性能

- 帧调度用「后台线程按 `nanoTime` 睡眠 + `invokeLater` 回 EDT 绘制」，不用 `javax.swing.Timer`（后者在本机实测被钉在 15.61ms / 约 64fps）。
- `targetFps` 125 → 8.00ms，144 → 6.94ms（`CaretAnimator.computeNextDelayNs`）；`alwaysHighFps` 控制停手后是否保持活跃档。
- 不可见编辑器会降频（BUG-007 的修复，`c51a9e7`），避免隐藏标签页按 144fps 空转 EDT。
- `[trail]` 诊断日志有行数上限（每个 animator 5000 行 + 一条汇总，`b533ee6`），避免长时间开着诊断把日志撑大。
- 渲染任务仍走 EDT，编辑器忙时会掉帧；这是平台限制，不是本插件能完全消除的（`README.md` Known Issues #3，2609 条日志样本中位 36.4fps / P90 279ms）。

### 3.10 设置面板（7 个标签页）

| 标签 | 内容 |
|---|---|
| 1 总开关 | 版本行、预设行、组「总开关」 |
| 2 颜色 | 拖尾颜色、拖尾不透明度、辉光颜色、光标渐变 |
| 3 光标行手感 | 组「单行输入输出（打字 / 退格）」+ 光标尺寸 / 颜色效果 |
| 4 列手感 | 组「多行缎带（换行 / 跳转）」 |
| 5 辉光 | 预览窗 + 组「辉光」+ 组「淡出」 |
| 6 特殊 | 「实验性（官方不建议使用）」平铺组 |
| 7 调试 | 「高级（诊断）」平铺组 |

- 控件数量（门禁实测）：**14 个滑块 / 10 个复选框 / 4 个下拉**（`VAddSetting:120`、`VerifyPanel:98/106/110`）。
- 标签 3 / 4 / 5 各有一个 `AnimationPreview` 实时预览；三个实例共用同一份 `previewConfig`。
- 每个标签页是独立 `DialogPanel`，必须 `registerIntegratedPanel(...)` 登记；漏登记的症状是「控件能改，但点应用没反应，且不报错」。守门探针 `VTabPages`（8 条断言）。
- 界面文案已国际化：**165 个键**，英文 root 默认 + `zh_CN` 覆盖（`NeovideCursorBundle.properties` / `..._zh_CN.properties` 实测各 165 个键）。IDE 语言为中文时显示中文，其余语言回退英文。
- 设置搜索交给平台自己的 `SettingsSearchIndex`；插件自带的 `enableSearch` 覆写已在面板重构期间移除（用户决策 P-M-2）。

---

## 四、与 0.9.83 相比的变化

> 这一节只写 **0.9.83 之后**发生的变化。0.9.83 之前已有的核心机制（四角弹簧、字符偏移短跳、斜度参数、rank 塌缩修复等）放在 §二 / §三 介绍，不在这里冒充新功能。

### 4.1 设置面板与预设

| 变化 | 改了什么 | 为什么 / 用户价值 | 依据 |
|---|---|---|---|
| 面板分标签页 | 2026-10-29 `373a666` 先分 6 页；2026-10-30 `96a8f70` 增加「辉光」页，最终 7 页 | 控件不再挤在一页里；辉光参数有了自己的预览窗，调层数 / 锐度 / 淡出时能当场看到变化，不再是「盲调」 | 提交 `373a666` / `96a8f70`；`CLAUDE.md` §10 |
| 面板按标签空间重排（P11） | `a1efc13`（0.9.110 / 0.9.111 实装）：预览窗左移，光标宽高放到右边；列手感页两条提示改三行；特殊 / 调试改平铺 | 面板从 **1332×780** 缩到 **1174×约 700**；说明文字不再被右边界截断；预览窗右边直接调光标尺寸，不用往下滚 | `DECISIONS-PANEL-2026-10-31.md` §9.1 / §9.3 |
| 界面国际化 | `caca717`（2026-11-01）：设置面板、信息条、诊断对话框共 165 个键；英文 root + 中文覆盖 | 英文 IDE 里不再中英混排；中英文两套 locale 都跑过全量门禁 | `I18N-REPORT-2026-11-01.md` §一 / §四 |
| 自定义预设父链 | `199519a`（2026-10-29）：派生记父、自定义可派生自定义、删除接管祖父、改名同步子引用、旧格式兼容、环检测 | 修「点恢复默认值反而跳回父预设的当前值」；用户手调的副本不再被父预设覆盖 | 提交 `199519a`；守门 `VPresetReset` / `VPresetChain` |
| 预设域统一 | 2026-10-28 起 `PRESET_FIELDS` 由 26 收缩到 **13**，与 `applyAnimPresetFields` / `matchAnimPreset` 同源 | 修两类真实症状：切自定义预设会把光标颜色改回预设里的粉色；改斜度 / 拖尾不透明度不派生副本 | `PARAMETER-MAP.md` §〇 / §三；`VFieldDomain` |
| 面板健壮性 | 落盘失败可见并回滚 baseline、锐度换算改成整数网格、坏快照不写名字、复选框改值也派生副本、去掉自带搜索 | 都是「零观感变化」的修复：正常路径看不出差别，但失败时不再静默 | `DECISIONS-PANEL-2026-10-31.md` §十；`VBatch3Fixes` / `VBatch3Medium` / `VBatch3Tail` |

**0.9.126 本身没有再改面板 UI**：主修复只碰辉光与淡出的计算链路，设置面板的标签、控件、文案保持 0.9.125 的样子。

### 4.2 颜色与渐变

| 变化 | 改了什么 | 为什么 / 用户价值 | 依据 |
|---|---|---|---|
| 0.9.84 回滚「马卡龙」渐变 | 撤掉上一轮实验性的马卡龙动态渐变 | 用户反馈「太暗」；先回到可控的三条路径，再单独做颜色系统 | `COLOR-STATE-DECISIONS.md` §五 |
| 0.9.85 「光标渐变」改成双拼 | `a8110fd`：渐变从色相循环改成「上端拖尾色 / 下端由辉光色算出」 | 恢复用户最早要的「上下深浅」语义；不再把「渐变」和「彩虹」混成一个东西 | 提交 `a8110fd` |
| 0.9.86 彩虹 / 双拼 / 纯色三条互斥路径 | `305fdae`：`cursorRgbWash` 决定彩虹；`rgbGradient` 只在非彩虹时决定双拼 | 修「选彩虹 → 光标变成粉色双拼」这种名字与行为对不上的问题 | 提交 `305fdae`；`CLAUDE.md` §4.2 |
| 0.9.88 彩虹匹配判据 | `2d15824`：给彩虹一对独有的 HEX，`matchColorPreset` 回到纯 HEX 比对 | 修「粉色 + 开渐变」被误认成彩虹、「彩虹 + 关渐变」被误认成粉色 | 提交 `2d15824`；`CursorPresets.java` 注释 |
| 0.9.105 彩虹辉光雾色 | `b8cc478`：彩虹预设的 `shadowColor` 改回中性白 `#FFFFFF`（BUG-011） | 0.9.88 顺手挑的紫罗兰色把彩虹旁边的雾换成了淡紫，用户看到「光标和辉光不搭」；中性白在色相环上不打架 | 提交 `b8cc478`；守门 `VGlowFog` |
| 颜色移出动画预设 | 2026-10-28：`tailColor` / `shadowColor` / `rgbGradient` 标为颜色域，不再进动画预设快照 | 切「个人自定义」不再把用户的绿色光标改回粉色 | `PARAMETER-MAP.md` §五 |
| 预览与生产同源 | `6895655` 预览补上斜度与彩虹；`72d5130` 渐变覆盖所有纯色预设 | 设置页预览看到的效果，和真实编辑器一致；不再出现「预览不变但实际变了」 | 提交 `6895655` / `72d5130`；`VPreviewParity` |

**未做**：颜色工坊（P10）仍待办 —— 当前是「颜色预设 + 手选颜色」，还不是在颜色标签下直接创建自定义颜色预设。见 `PROJECT-DECISIONS.md` P10。

### 4.3 拖尾物理与输入

| 变化 | 改了什么 | 为什么 / 用户价值 | 依据 |
|---|---|---|---|
| 默认斜度 100 → 0 | `63a3d6a`：新安装默认 `slopeTicks = 0`（纯矩形） | 修「全新安装仍出现单行斜拉伸」；斜度交给用户或预设决定，不再默认吃上游的 100 | 提交 `63a3d6a`；`State.slopeTicks = 0` |
| Ctrl+滚轮改字号后的纵向漂移 | `8d3e787`：字号变化后强制重新贴合行位置 | 放大 / 缩小字体后光标不再偏上一行 | 提交 `8d3e787`；守门 `VFontScale` |
| block inlay 撑高行导致的错位（BUG-006） | `0956d44`：监听 inlay 几何变化，下一帧强制重算；`8ce6524` 补上慢路径抛出后的重算标志回滚 | IDEA 自动显示的 `@Contract` 等 block inlay 会把下方文本往下撑，而行号 / 列号 / 文档戳都不变；不补这一手，覆盖层会停在旧 y 上，看起来像「光标跳到上一行」 | 提交 `0956d44` / `8ce6524`；守门 `VInlay` / `VCatchRollback` |
| 前缘贴合（`leadingSnap`） | 0.9.116 / 0.9.117 期间实现三版模型，2026-11-01 `b5e34d7` 全部回退为「只留开关，逻辑掏空」 | 三版实机验收都不可行：前缘 0ms = 瞬移（像原生光标）；四角同长 + 固定尾巴 = 只有一个残影、连续跟不上；路线 C 前后缘都短 = 没有尾巴。纯弹簧里「尾巴短」和「停留久」由同一个参数决定，无解 | `LEADING-SNAP-REVERT-REPORT-2026-11-01.md`；`LEADING-SNAP-DECISION-2026-11-01.md` |
| 预览打字 / 退格节奏 | `0e8d46d`（0.9.98）：预览窗的打字 / 退格间隔加 ±30% 抖动（固定种子） | 预览更像真人输入，不再像节拍器；只影响设置页预览，不影响真实编辑器 | 提交 `0e8d46d`；`NEXT-STEPS.md` §五-C |

**`leadingSnap` 当前状态（必须说清）**：

- 复选框仍在「标签 3 → 单行输入输出 → 斜度之后」，能勾、能存、重启不丢。
- **勾选不改变任何观感**，文案写的是「待实现：此功能尚未实现，勾选不会改变观感」。
- 设备级：不进动画预设、不进自定义快照、不参与 `matchAnimPreset`。
- 守门探针 `VLeadingSnap` 12 条：其中 L-21 走真实 UI 链路，断言开关 true / false 两种状态的四角时长逐位相同；L-22 断言五个行为层源文件剥掉注释后零出现，防止功能被悄悄复活。

**0.9.83 之前已有、不要误认为新功能**：rank 稳定排序 + 索引 tiebreak（0.9.77，修单行斜缎带）、斜度独立参数（0.9.79–0.9.82）、字符偏移短跳判据（0.7.1 起）。它们在 0.9.83 基线里已经存在；版本对应见 `docs/archive/VERSIONS.md` 与 `NEXT-STEPS.md` §二。

### 4.4 辉光与淡出

这是 0.9.126 的核心，也是 0.9.83 之后改动最密集的一条链路。版本行为对照（来源：`HANDOFF-FADE-2026-11-02.md` §三 / §四 / §五、`FIX-INPUT-FADE-REPORT-2026-11-01.md` §14.2、`GLOW-FADE-0.9.126-REPORT-2026-11-02.md` §二 / §三）：

| 版本 | 淡出计时起点 | 展开期亮度 | 用户看到的现象 |
|---|---|---|---|
| ≤ 0.9.117 | 偏移 > 0 的每一帧刷新 | `strengthFor(偏移)` 曲线 | 「用了很久的手感」；但淡出从约 **1.5%** 开始，参数肉眼不可见 |
| 0.9.118–0.9.120 | 按键那一帧（`moved`） | 保持满亮 | 淡出看得见；但跨行缎带约 **72%** 的时间无辉光，中途熄灭 |
| 0.9.121 | 偏移 > 0 每帧刷新 | 保持满亮 | 「停下后才暗」 |
| 0.9.122 | 偏移 > 0 每帧刷新 | 偏移曲线 | 「没有淡入淡出，只是变快了」 |
| 0.9.123 | 偏移 > 0 每帧刷新 | 偏移曲线；收拢帧把保持值钉到 1.0 | 「闪两下再暗」（先暗、突然亮、再淡出） |
| 0.9.124 | 偏移 > 0 每帧刷新 | 偏移曲线 | 「光随拖尾消失」，淡出参数基本无效 |
| 0.9.125 | 偏移 > 0 每帧刷新 | 保持满亮 + 收拢后 50+75ms 淡出 | 仍在「已经缩到最小」的光晕上淡出，仍然突兀 |
| **0.9.126** | **最后一次移动** | **目标曲线 + 限速下降** | **跟着拖尾一起暗，参数决定降多慢；跨行不中断；无闪灯；拆组不孤光** |

**0.9.126 具体改了什么**：

- 新增 `GlowIntensity.advanceGlow` / `advanceTrailGlow`：目标亮度仍是 `strengthFor(offset)`，但下降不再「到点乘 0」，而是每帧最多下降 `dt / fadeOutSeconds`。
- 每根 `SpringTrail` 保存自己的 `brightness` 与 `lastMoveNanos`；组亮度只是显示时取 max，**不再写回成员**。这修的是审查 H-1：一个光标移动、另一个静止，移动的光标跳走 / 被移除、组拆开后，静止 trail 会以组满亮为起点单独淡出一整个淡出时长。
- 保持期从「最后一次移动」起算，不是从「拖尾收拢」起算；保持期内不下降。
- 上升立即到目标：每次按键立刻回满，满足「按键就要亮」。
- 去掉重复的 `× fade`：最终亮度只由逐 trail 的 `quadGlow` 携带；`CaretTrailPanel.glowFade` 生产恒为 1（历史兼容，待删除）。

**用户价值**：

- 「淡出前保持时长 / 淡出时长」两个参数重新可感知 —— 它们决定「降多慢」，不再决定「何时开始」。
- 跨行长拖尾不会在半路熄灭（0.9.118 的 72% 无光已修）。
- 不会先暗、突然亮、再暗（0.9.123 的闪灯已修）。
- 多光标拆组或移走一个光标后，留下的静止光标不会单独发光。

**仍然存在的手感问题**：连续 10 键/秒时亮度会呼吸，当前配置（保持 25ms / 淡出 50ms）约在 **0.32 ~ 1.0** 之间起伏；保持 50 / 淡出 75 时约 0.57 ~ 1.0（报告 §六）。报告建议先把保持时长调到 100ms 零代码规避，仍不可接受再做「连续输入保护」。见 §七。

### 4.5 多光标与平台兼容

| 变化 | 改了什么 | 为什么 / 用户价值 | 依据 |
|---|---|---|---|
| 平台 252 光标冻结 | `37355ae`（0.9.104）：把 Editor / Caret / Document 的读取包进 `ReadAction.compute`；`refreshTarget` 拆成 6 行包装 + 原实现体 | PyCharm 2025.2（build 252）要求读编辑器必须在 read action 内；在 EDT 上不等于持有读锁。不包会抛 `assertInteractionAllowed`，异常被吞后覆盖层永久冻结在第一行第一字符 | 提交 `37355ae`；`NEXT-STEPS.md` §一甲；守门 `VReadAction`（当前 16/16） |
| 多光标静止光标发光 | 0.9.120 `be0d987` 先改逐组判据；0.9.126 `2c1004b` 改成逐 trail 状态 + 组内显示 max | 修「一个光标在动，另一个静止的光标也满亮」；0.9.126 进一步修「拆组后静止 trail 以组满亮为起点单独淡出」 | 提交 `be0d987` / `2c1004b`；`VFadeTick` D-10 / D-13 |
| 不可见编辑器空转 | `c51a9e7`：不可见编辑器降频 + 首用诊断（BUG-007） | 5 个隐藏标签页不再按 144fps 刷 EDT 空任务；首用停顿的诊断数据也留下了 | 提交 `c51a9e7`；守门 `VBoot` |
| PyCharm 同步 | 2026-11-02 已装 0.9.126（此前 0.9.113） | 两个 IDE 版本一致，跨 IDE 行为可对照 | jar 内版本 + md5 实测 |
| 兼容区间 | `since-build="242"` / `until-build="261.*"` | 下界 = 2024.2（再低会撞 Java 21 字节码锁）；上界 = 2026.1 全系放行，2026.2 起需新版 | `build/offline-build.ps1`；jar 内 `plugin.xml`；守门 `VBuildDecl` |

**真机验证范围**：IDEA 2026.1 与 PyCharm 2025.2 已实测；其他 JetBrains 产品（WebStorm / GoLand / CLion / Rider 等）只从依赖声明上判定「理论可用」，**没有逐个真机验证**（`COMPATIBILITY.md` §三 / §五）。Linux / macOS 代码上无 OS 分支，但**从未真机跑过**（`COMPATIBILITY.md` §四）。

### 4.6 构建 / 探针 / 文档

| 变化 | 改了什么 | 为什么 / 用户价值 | 依据 |
|---|---|---|---|
| 门禁脚本修复 | `build-v-probes.ps1` 的 `$mains` 数组从 0.9.83 起缺右括号，整份脚本 ParseError，**一个探针都没跑过**；补括号后又遇到 Windows 32767 字符路径上限，改成 `@argfile` | 「探针 X/Y 通过」这类数字从「手写、不可复现」变成「脚本真的在跑」；这是本项目历史上最大的假绿来源 | `CLAUDE.md` 陷阱 5；脚本 `$mains` 数组结尾的注释 |
| 6 个过期断言修绿 | 2026-10-28 `84d9fd5`：`VTools` / `VHints` / `VCustom` / `VReset2` / `VSepBound` / `VSleep` | 只改探针方向、生产代码零改动；5 组变异测试证明改后的断言仍抓得住 | `NEXT-STEPS.md` §7.1.1 |
| 探针规模 | 0.9.83 时记录的覆盖度是 **49 → 80**（`NEXT-STEPS.md` §二）；此后探针文件继续增加，当前为 **116 个 `V*.java` / 131 个 `.java` / 33,487 行**，其中 **113 个注册进 `$mains`** | 新功能都有守门：`VFadeTick` / `VFadePreview` / `VInputCommit` / `VI18n` / `VReadAction` / `VTabPages` / `VAuditBatch1` / `VAuditBatch2Round2to4` / `VAuditBatch2Round5` / `VBatch3Fixes` / `VBatch3Medium` / `VBatch3Tail` / `VLeadingSnap` 等 | `ls tools/settings-probe/V*.java` 的行数；`build-v-probes.ps1` 数组实测 113 个唯一名 |
| 文档数字纳入门禁 | `VDocCheck`：三处状态块版本一致、探针数一致且等于磁盘实测、状态文档里不写死 commit hash | 修「文档数字来自从未执行的脚本」这个老问题；`CLAUDE.md` 第 11 章专门批评过 | 守门 `VDocCheck` 4/4；`NEXT-STEPS.md` 开头 |
| 兼容区间纳入门禁 | `VBuildDecl`：模板里真有 `until-build`、上界 ≥ 下界、只锁大版本；`install.ps1` 自检段也校验 | 防止「上界留空 → 未来任何 IDE 都放行 → 平台 API 一移除就 NoSuchMethodError」 | 守门 `VBuildDecl` 9/9；`COMPATIBILITY.md` §六 |
| 安装脚本结构指纹 | `36cbaa4`：正则从只认部分工厂改成 `\w+`，修「`*Global` 工厂漏计，把 27 个数成 25」 | 加新字段时「结构指纹对不上就警告」这个唯一用途不再静默失效 | 提交 `36cbaa4`；`VBuildDecl` B-7 |
| 安装脚本入库 | `c951a65`：`install.ps1` 与 release notes 入库保护 | 安装流程可追溯、可复现，不再只存在于某台机器的 `build/` 里 | 提交 `c951a65` |
| 文档整理 | 2026-10-28 起把 44 份历史文档移入 `docs/archive/`；`docs/INDEX.md` 加「最后核对版本」；`CLAUDE.md` 重写为接手入口 | 根目录不再被历史留痕占位；新会话能先读一份状态入口再干活 | 提交 `49bcc94` / `e1fce94` / `2807b5f` |

**规模变化**：`v0.9.83..HEAD` 共 **106 个提交 / 239 个文件 / +42,323 −3,464 行**（含版本说明与文档同步；`git diff --stat v0.9.83..HEAD` 实测）。其中生产代码当前是 **37 文件 / 21,342 行**，探针是 **131 文件 / 33,487 行**（`find` + `wc -l`，2026-11-02 实测）。

---

## 五、行为 / 兼容性变化

| 项 | 现状 | 影响 / 说明 |
|---|---|---|
| 设置字段表 | `SettingsField.FIELDS` **28 项**（`build()` 里 28 条 `out.add(<工厂>(`；不含 `buildPresetFields()` 里的 `out.add(f)`） | 加设置项必须按 `CLAUDE.md` §7.0 的六层落点走；漏一层就是「存了读不到 / 界面没反应」 |
| State 字段总数 | `State` 里还有 `stateVersion`、预设指针、自定义预设等非设置字段；`VPreset:389` 的公开实例字段断言是 **34 项** | 「28」是设置字段表，「34」是 State 公开实例字段，两个口径不要混 |
| 预设域 | **13 个生效字段**；动画预设不碰颜色；`typingSmoothness` 是全局项 | 切动画预设不会改颜色；切颜色预设不会改动画；改斜度 / 不透明度会派生副本 |
| `leadingSnap` | 复选框保留、默认关、设备级；**勾选不改变任何观感** | 文案已写明「待实现」；不要当 bug 上报 |
| 安装脚本 | 每次安装仍会清空插件 lib + 设置 XML + 诊断日志；但**清空前备份、装完全量还原个人配置**；`$dropFields` 当前为空 | 0.9.126 安装实测 **12/12 字段还原、丢弃 0**（报告 §5.4）；备份在 `D:\deepseekwork\_config-backup\` |
| IDE 兼容区间 | `since-build="242"` / `until-build="261.*"`；Java 21 字节码 | 2024.2 至 2026.1 全系；2024.2 以前装不上；2026.2 起需新版；Fleet 不支持 |
| 已装 jar 文件名 | 恒为 `jetbrains-neovide-cursor-0.9.8.jar` | **不能拿文件名判断版本**；必须读 jar 内 `META-INF/plugin.xml` 的 `<version>` |
| 默认值 | 0.9.126 **没有改任何默认值** | `RELEASE-SAFETY.md` 的「默认值变更记录」仍为空；`VDefaultFreeze` 的 28 个黄金值全绿。升级不会因为默认值漂移改变观感 |
| 国际化 | 设置面板 / 信息条 / 诊断对话框跟随 IDE 语言；英文 root + `zh_CN` 覆盖 | `plugin.xml` 菜单标题、Tools 菜单里的内置预设名、自定义预设名校验原因仍是中文（已知缺口，见 §七） |
| 残影模式 | 仍标「实验性」，只跟随主光标；辉光淡出不适用 | 用户明确保留但暂不投入修（`PROJECT-DECISIONS.md` P8） |
| `targetFps` | 只接受 125 / 144 | 手改 XML 塞其他值会被 setter 改回 125 |

---

## 六、验证与质量

> 下面的门禁结果是 **2026-11-02 写本文前重新跑的一次**，不是从旧报告抄的。命令：
> `pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr`

| 项 | 结果 | 依据 |
|---|---|---|
| 全量门禁 | **`V-PROBES RESULT: ALL PASS`**；**113 个注册探针** 0 红（`VRegr2` 是 informational，见下） | 本次复跑输出；`build-v-probes.ps1` 的 `$mains` 去重后 113 个 |
| 辉光专项纯函数 | `VFadeTick` **PASS 45 / FAIL 0** | `build/v2-probe/VFadeTick.txt`（本次门禁输出） |
| 辉光专项预览 | `VFadePreview` **PASS 8 / FAIL 0** | `build/v2-probe/VFadePreview.txt`（本次门禁输出） |
| 性能 / 结构审计 | `VAuditBatch2Round5` **PASS 32 / FAIL 0** | `build/v2-probe/VAuditBatch2Round5.txt`（本次门禁输出） |
| 平台 read action | `VReadAction` **PASS 16 / FAIL 0** | 本次门禁输出 |
| 标签页登记 | `VTabPages` **PASS 8 / FAIL 0** | 本次门禁输出 |
| 国际化 | `VI18n` **PASS 13 / FAIL 0**；中英文两套 locale 都跑过全量门禁 | `build/v2-probe/VI18n.txt`；`I18N-REPORT-2026-11-01.md` §五 |
| D0 红线 | **EXIT=0**，`core/NeovideConfig.java` 零 diff | 本次执行 `git diff --exit-code` |
| 变异测试 | **6 组**：M1 忽略淡出时长 / M2 去掉限速 / M3 保持期失效 / M5 组亮度直接写回 / M6 两步写回（假通过，已补 D-13）/ M7 每帧无条件分配（已补 G1-e） | 报告 §5.2 |
| 独立代码审查 | 2 位子代理（`code-reviewer` / `java-reviewer`），**0 CRITICAL / 0 HIGH**；2 条「假通过」MEDIUM 已在 `3aabb0c` 修复 | 报告 §5.3 |
| 安装核验 | jar 内 `<version>0.9.126`；md5 `4cbbd854719879bfd522b63df8e80897`（246,919 B）与 dist 一致；个人配置 **12/12** 还原、丢弃 0；安装时 IDEA / PyCharm 均未运行 | 报告 §5.4；本次 md5 / 版本实测 |
| 实机验收 | 用户实机验收**通过**；IDEA 2026.1 与 PyCharm 2025.2 均已装 0.9.126 | 报告 §5.4；两边 jar 内版本 + md5 实测 |
| 探针规模 | **116 个 `V*.java` / 131 个 `.java` / 33,487 行**；生产 **37 文件 / 21,342 行** | `ls` / `find` / `wc -l`，2026-11-02 实测 |
| 已知 informational | `VRegr2` 仍有 **R-5 / R-6 两条红**，且报完后在 `VRegr2.java:338` 抛 NPE，后续断言不再执行；门禁按 `$informational` 排除 | `build/v2-probe/VRegr2.txt` 实测；`build-v-probes.ps1` 的 `$informational = @('VOrder','VOrtho','VRegr2')` |

**关于「113 ALL PASS」的口径**：它指的是 `$mains` 里 113 个注册探针的通过门全部通过；`VRegr2` 是注册了但被显式排除在失败门外的 informational 探针。它那两条红是**真功能缺口**（`typingSmoothness` 不进 `matchAnimPreset`、改内置预设不派生副本），不是过期断言，也不是本版引入的。`VRegr2` 的 NPE 会让它后半段断言根本不执行 —— 这一点在 `LEADING-SNAP-REVERT-REPORT-2026-11-01.md` §六也单独记录过。

---

## 七、已知问题与限制

### 7.1 用户体验

| 问题 | 级别 | 现状 / 规避 | 来源 |
|---|---|---|---|
| 连续 10 键/秒时辉光亮度呼吸 | 体验（中） | 当前配置（保持 25ms / 淡出 50ms）约在 **0.32 ~ 1.0** 起伏；保持 50 / 淡出 75 时约 0.57 ~ 1.0。可先把保持时长调到 **100ms** 零代码规避；仍不可接受再做「连续输入保护」 | 报告 §六 |
| 残影模式只跟随主光标 | 已知限制 | 多光标请用默认的弹簧模式；残影模式保留但标实验性 | `README.md` Known Issues #1；`PROJECT-DECISIONS.md` P8 |
| 保留原生光标时可能「两个光标」 | 已知限制 | 原生光标约 5px，覆盖层默认 2px，宽度不一致时两边都会露出来。默认 `hideNativeCaret = true` 可避开 | `README.md` Known Issues #2 |
| 终端 / 控制台不播动画 | 平台限制 | 终端光标不受 `CARET_COLOR` 控制，机制上已知难点；未定位 | `PROJECT-DECISIONS.md` §8.5；BUG-005 |
| EDT 繁忙时掉帧 | 平台限制 | 渲染任务排在原生输入事件之后；2609 条日志样本中位 36.4fps / P90 279ms。残影模式掉帧只让残影变稀，轨迹不变形 | `README.md` Known Issues #3 |
| 快速连打时用不上长拖尾 | 机制限制 | `TrailCorner.jump()` 每次按键无条件 `reset()`，超过按键间隔（80–150ms）的拖尾时长实际到不了 | `README.md` Known Issues #4 |
| 部分界面仍是中文 | 国际化缺口 | `plugin.xml` 菜单标题、Tools 菜单内置预设名、`CustomPresets.nameError` 的中文原因、副本后缀「副本」未翻译 | `I18N-REPORT-2026-11-01.md` §八 |

### 7.2 工程 / 发布

| 问题 | 级别 | 现状 / 建议 | 来源 |
|---|---|---|---|
| `glowFade` / `setGlowFade` 是死乘子 | 技术债（低） | 生产恒写 1，代码已注明「当前无读者、待删除」；建议下一迭代删除字段 / 乘法 / 探针 | 报告 §六 / §七 P1 |
| `VRegr2` R-5 / R-6 + NPE | 测试债 / 功能缺口 | 不在失败门内；R-5 / R-6 是 `typingSmoothness` 的真功能缺口，要修得先做产品决策；NPE 让后半段断言不执行 | `build/v2-probe/VRegr2.txt`；`LEADING-SNAP-REVERT-REPORT-2026-11-01.md` §六 |
| 部分探针 0 断言 | 测试债（低） | 报告记录约 **9 个**（既有问题，非本次引入）；`ALL PASS` 里可能混着「写了但没断言」的空转探针，建议清理或补断言 | 报告 §六 |
| 0.9.84 ~ 0.9.125 未打 tag | 运维（中） | 连续 **42** 个版本号没有 tag；`v0.9.126` 是 0.9.83 之后唯一新 tag。补打需要逐个确认 commit，是独立工作 | `git tag -l`；`NEXT-STEPS.md` §7.4 |
| GitHub Release 未更新 | 发布（中） | `gh release list` 最新仍是 **v0.9.83**；v0.9.126 只有 git tag，没有对应 Release | `gh release list` 实测 |
| Marketplace 上架状态 | 待核实 | 仓库内没有上架记录，本文不宣称已上架 | 仓库文档检索 |
| `targetFps=144` 端到端帧率未正式留档 | 未验证项 | 工具已具备（`[tick]` 诊断日志），但未正式跑一次留档 | `NEXT-STEPS.md` §7.1-8 |
| 多 trail 分组缺少动态探针 | 测试覆盖（低） | D-13 + A-13/A-14 覆盖核心不变量，但没有 `CaretAnimator` 多 trail 动态驱动；建议下一迭代做可驱动的组场景探针 | 报告 §六 |
| 调度与渲染各算一遍 target/hold | 技术债（低） | 两份公式当前一致，代码已注明必须同步；建议合并为一个返回结果 | 报告 §六 / §七 P1 |
| 预览用固定 8ms 作为 dt | 技术债（低） | 只影响设置页预览，生产用量测 dt；建议改成真实间隔 | 报告 §六 / §七 P1 |
| 未在 2024.2 平台真机验证 | 未验证项 | 插件声明 `since-build=242`，但只在 IDEA 2026.1（261）与 PyCharm 2025.2（252）真机跑过；242 上的平台 API 兼容性未核实 | `COMPATIBILITY.md` §二 / §五；`I18N-REPORT-2026-11-01.md` §八-6 |

---

## 八、安装 / 升级 / 回滚

### 8.1 推荐：用仓库里的 `install.ps1`

```powershell
# 先看清单，不动任何文件、不编译、不关 IDE
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\build\install.ps1 `
  -Version 0.9.126 -DryRun

# 确认后安装（不自动启动 IDE）
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\build\install.ps1 `
  -Version 0.9.126 -NoStart
```

它按顺序做这些事（`build/install.ps1` 文件头）：

1. 读出当前个人配置，打印「备份 / 丢弃」清单（`-DryRun` 到此结束）。
2. 编译 + 打包（调 `offline-build.ps1`）。
3. 关闭 IDEA。
4. 干净安装：清空插件 `lib` 目录 + 删除设置 XML + 删除诊断日志。
5. 拷贝新 jar，自检 jar 内版本号与兼容区间，然后把个人配置写回一份新 XML。
6. 启动 IDEA（`-NoStart` 则跳过）。

> 注意：`install.ps1` 当前把 IDEA 2026.1 的配置目录与插件目录写死在脚本里（`$ideaCfg = "...\JetBrains\IntelliJIdea2026.1"`），**不会自动安装到 PyCharm**。PyCharm 2025.2 需要按 §8.2 手动替换 jar 到它自己的 `plugins` 目录。

**配置备份 / 还原**：

- 备份目录：`D:\deepseekwork\_config-backup\`；文件名 `personal-state-<时间戳>.json`，另存一份原始 XML 副本。
- `$dropFields` 当前为空数组 —— **全量还原，一个字段都不丢**。包括内置预设指针、颜色三件套、自定义预设（含「XX 副本」）、光标宽高与全部观感参数。
- 只有某个字段**语义变更、旧值写回会变脏数据**时，才把它加进 `$dropFields`；0.9.126 没有这种字段。
- 0.9.126 安装实测：**12/12 字段还原，丢弃 0**（报告 §5.4）。

### 8.2 手动安装（从 dist）

1. 取 `dist/neovide-cursor-0.9.126.jar`。
2. IDE → `Settings → Plugins → 齿轮 → Install Plugin from Disk...`。
3. 选择 jar，重启 IDE。

> 已装目录里的文件名恒为 `jetbrains-neovide-cursor-0.9.8.jar`，这是本插件加载约定；**版本看 jar 内 `META-INF/plugin.xml`**，不要看文件名。

### 8.3 从 0.9.125 升级

- 0.9.126 **没有改任何默认值**（`RELEASE-SAFETY.md` 的默认值变更记录仍为空；`VDefaultFreeze` 28 个黄金值全绿），所以不需要迁移代码。
- 用 `install.ps1` 升级时，个人配置会先备份、装完再全量写回；不会因为「干净安装」丢掉你调好的参数或自定义预设。
- 升级后可以做一次版本核验：

```bash
unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" \
  META-INF/plugin.xml | grep -o "<version>[^<]*"
```

期望看到 `<version>0.9.126</version>`。

### 8.4 回滚到 0.9.125

**保留的回滚点**（均已实测存在）：

- `dist/neovide-cursor-0.9.125.jar`（246,346 B）
- `build/fade-pre-0.9.126.patch`（15,585 B，0.9.126 淡出改动的前后 diff；回滚时用 `git apply -R`）

**推荐做法：手动替换 jar**。把 `dist/neovide-cursor-0.9.125.jar` 复制到已装插件的 `lib` 目录（文件名改成 `jetbrains-neovide-cursor-0.9.8.jar`），重启 IDE。这样回滚的是**真实 0.9.125 的字节码**。

**不要**只把 `install.ps1 -Version` 改成 0.9.125 来「回滚」：`install.ps1` 会用**当前源码**重新编译打包，只是给 jar 贴上旧版本号，代码仍是 0.9.126。真要沿源码回滚，先用 `git apply -R build/fade-pre-0.9.126.patch` 反向应用补丁，再重新打包。

### 8.5 兼容性

| 项 | 值 |
|---|---|
| 支持的 IDE 版本 | `since-build="242"` ~ `until-build="261.*"`，即 2024.2 至 2026.1 全系 |
| Java | 21（`javac --release 21`，class 文件版本 65） |
| 真机验证过 | IDEA 2026.1（build 261）、PyCharm 2025.2（build 252） |
| 未真机验证 | WebStorm / GoLand / CLion / Rider / PhpStorm / RubyMine / DataGrip / Android Studio；Linux / macOS |
| 不支持 | Fleet（插件 API 不是 IntelliJ 平台那一套） |

---

## 九、来源索引

### 9.1 文档

| 文档 | 本文用到什么 |
|---|---|
| [GLOW-FADE-0.9.126-REPORT-2026-11-02.md](GLOW-FADE-0.9.126-REPORT-2026-11-02.md) | 0.9.126 主修复、验证数字、变异测试、审查结论、已知问题、路线图 |
| [FIX-INPUT-FADE-REPORT-2026-11-01.md](FIX-INPUT-FADE-REPORT-2026-11-01.md) | 数字输入框提交链路、淡出起点 1.5% 的实测、0.9.120 |
| [HANDOFF-FADE-2026-11-02.md](HANDOFF-FADE-2026-11-02.md) | 0.9.117~0.9.125 行为对照、72% 无光、闪灯、绘制层事实 |
| [I18N-REPORT-2026-11-01.md](I18N-REPORT-2026-11-01.md) | 165 个键、两套 locale 门禁、已知缺口 |
| [DECISIONS-PANEL-2026-10-31.md](DECISIONS-PANEL-2026-10-31.md) | 面板二次重排、面板尺寸 1332×780 → 1174×约 700、第 3 批审查决策 |
| [PARAMETER-MAP.md](PARAMETER-MAP.md) | 28 个设置字段的归属、预设域 13 字段、颜色退出动画预设 |
| [COLOR-STATE-DECISIONS.md](COLOR-STATE-DECISIONS.md) | 0.9.84 马卡龙回滚、0.9.85 双拼、0.9.86 三条路径 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | 242 ~ 261.*、真机验证范围、Linux / macOS 未验证 |
| [RELEASE-SAFETY.md](RELEASE-SAFETY.md) | 默认值冻结、安装脚本备份还原、默认值变更记录为空 |
| [NEXT-STEPS.md](NEXT-STEPS.md) | 当前硬状态、0.9.104 read action、未完成清单、tag 缺口 |
| [LEADING-SNAP-REVERT-REPORT-2026-11-01.md](LEADING-SNAP-REVERT-REPORT-2026-11-01.md) | `leadingSnap` 回退、12 条新契约、VRegr2 NPE |
| [LEADING-SNAP-DECISION-2026-11-01.md](LEADING-SNAP-DECISION-2026-11-01.md) | 三版模型失败原因、路线 C 实机反馈 |
| [FINAL-AUDIT-2026-11-01.md](FINAL-AUDIT-2026-11-01.md) | 发版前审查、P0-1 多光标串扰、探针体系 |
| [PROJECT-DECISIONS.md](PROJECT-DECISIONS.md) | P5 / P10 / P11 等产品决策、残影模式定位 |
| [../START-HERE.md](../START-HERE.md) | 项目状态入口、规模数字、探针基线 |
| [../README.md](../README.md) | 核心机制、已知问题 #1~#6、安装方式 |
| [archive/VERSIONS.md](archive/VERSIONS.md) | 0.7.1-offset 等历史版本对应（历史留痕，不当现状） |

### 9.2 提交

| 提交 | 内容 |
|---|---|
| `2c1004b` | 0.9.126 主修复：辉光淡出改为逐 trail 限速下降 |
| `3aabb0c` | 0.9.126 审查补强：H-1 与分配守卫，修假通过 |
| `1dcaa7a` | 0.9.126 交付与决策层优化报告（tag 指向此提交） |
| `54cebef` | 状态文档同步（当前 HEAD） |
| `373a666` / `96a8f70` / `a1efc13` | 面板分标签 / 辉光页 / P11 重排 |
| `caca717` | 界面文案国际化 |
| `199519a` | 自定义预设父链模型 |
| `305fdae` / `a8110fd` / `2d15824` / `b8cc478` | 颜色三条路径 / 双拼 / 彩虹匹配 / 彩虹辉光雾色 |
| `63a3d6a` / `8d3e787` / `0956d44` / `8ce6524` | 默认斜度 0 / 字号漂移 / block inlay / 慢路径回滚 |
| `37355ae` | 平台 252 read action |
| `4af8965` / `be0d987` / `2c1004b` | 输入框 + 淡出接线 / 多光标逐组 / 逐 trail 限速 |
| `30fe004` / `c2ae1e9` / `b5e34d7` | `leadingSnap` 实现与回退 |
| `84d9fd5` | 6 个过期断言探针修绿 |
| `36cbaa4` / `c951a65` | 安装脚本结构指纹 / 安装脚本入库 |

### 9.3 本文数字的复核命令

```bash
# 版本 / tag / 提交
git show -s --format='%H %ci %s' v0.9.126
git rev-list -n 1 v0.9.126
git log --oneline v0.9.83..HEAD | wc -l

# 已装 jar 的版本（文件名恒为 0.9.8.jar，读内部）
unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" META-INF/plugin.xml | grep -o "<version>[^<]*"

# 设置字段表 28 项 / 探针 113 注册 / 116 个 V*.java
grep -cE '^\s+out\.add\(\w+\(' src/main/java/com/github/yyyolo7a79/neovidecursor/settings/SettingsField.java
ls tools/settings-probe/V*.java | wc -l
cat tools/settings-probe/*.java | wc -l

# 全量门禁（唯一权威入口）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr

# D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

---

## 十、贡献者与代码量

- **维护者 / 提交作者：帕瓦马马马（pawamamama）**（[@pawamamama](https://github.com/pawamamama)）。
  自 v0.9.83 以来：**106 个提交 / 239 个文件 / +42,323 −3,464 行**（代码 + 测试 + 文档，2026-11-02 实测）。
  当前仓库：**生产 37 文件 / 21,342 行**；**探针 131 文件 / 33,487 行**（116 个 `V*.java`，113 个注册）。
- **上游：yyyyolo7a79-sketch/neovim-cursor-for-jetbrains** —— 原项目与核心拖尾物理。
- **参数语义与效果来源：LengineerC/vscode-neovide-cursor、Neovide**。

> 注：`grep -cE '^\s+out\.add\(\w+\('` 会数到 `SettingsField.java` 第 200 行 javadoc 里的示例；按 `build()` 方法体内的 28 条工厂调用计，实际就是 **28**。`buildPresetFields()` 里的 `out.add(f)` 不算字段声明。

---

**一句话总结**：0.9.126 把辉光从「停下后才暗」改成「跟着拖尾限速变暗」，淡出参数第一次真正可感知，多光标拆组孤光也修了；113 个注册探针全绿、D0 零 diff、IDEA 2026.1 与 PyCharm 2025.2 均已安装并通过实机验收。发布状态上，git tag 已就位，但 GitHub Release 仍停在 v0.9.83。
