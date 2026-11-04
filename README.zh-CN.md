# Neovide Cursor for JetBrains——可配置版

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2%2B-000000?logo=intellijidea&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)

> 把 **Neovide 风格的拖尾光标**带到 JetBrains IDE —— 光标移动时留下渐隐的拉伸拖尾，带辉光光晕。
>
> **适用**：IntelliJ IDEA / CLion / PyCharm 等 **2024.2 ~ 2026.2+**
>
> **本版重点**：在原始效果之上，补上了 **完整设置面板 + 持久化 + 预设系统**。
> 原版只能改代码调参数，本版可以在 IDE 里直接调、存、切换。

> **当前版本：v0.9.126** — 见 [docs/archive/reports/VERSION-0.9.126.md](docs/archive/reports/VERSION-0.9.126.md)（完整版本说明）。
>
> **维护者：** [pawamamama](https://github.com/pawamamama) — 自 v0.9.83 以来 **106 个提交、239 个文件、+42,323 / −3,464 行**。

**语言 / Language** — [English](README.md) | [**简体中文**](README.zh-CN.md)

> 繁體中文 / 日本語 / 한국어 三份翻译已停止同步（停在多光标支持之前），
> 已移入 `docs/archive/`。以本文件与 `README.md` 为准。

---

## 效果

> ### ⚠️ 先说一件事：视频看着比实际卡
>
> 视频存放在本仓库 [`docs/media/`](docs/media/)，
> **点击链接可下载观看（GitHub 不支持在 README 内嵌 mp4 播放）**。
>
> 下面四个视频是 **60fps 屏幕录制**，但录屏本身会掉帧、压缩也会损失细节。
>
> **作者实测手感明显好于视频呈现** —— 尤其是连续打字时的拖尾连贯度，
> 视频里看起来有断续感，实机上要顺得多。
>
> 不要以视频帧率判断实际体验，请自己装上看。

---

### 1. 大幅拖尾（跨行跳转）

https://github.com/pawamamama/neovim-cursor-for-jetbrains/raw/main/docs/media/01-large-trail.mp4

`docs/media/01-large-trail.mp4` · 11 秒 · 1436×444 · 60fps

### 2. 打字与删除

https://github.com/pawamamama/neovim-cursor-for-jetbrains/raw/main/docs/media/02-typing-delete.mp4

`docs/media/02-typing-delete.mp4` · 10 秒 · 1040×442 · 60fps

> 这一段最能看出拖尾的"拉伸感"——注意删除时光标是怎么被"拽回"的。

### 3. Tools 菜单快捷切换预设（动画 + 颜色）

https://github.com/pawamamama/neovim-cursor-for-jetbrains/raw/main/docs/media/03-tools-preset.mp4

`docs/media/03-tools-preset.mp4` · 45 秒 · 1630×878 · 60fps

> 不用进设置页，`Tools → Neovide 光标：动画预设 ▸` 直接切。

### 4. 设置面板（可调参数全览）

https://github.com/pawamamama/neovim-cursor-for-jetbrains/raw/main/docs/media/04-settings-panel.mp4

`docs/media/04-settings-panel.mp4` · 39 秒 · 1722×1074 · 60fps

> **这是本版和原版最大的区别** —— 14 个滑块 + 10 个复选框 + 4 个下拉，全部实时生效、持久化。

---

### 关于界面语言

**0.9.126 已做 i18n**：设置面板、信息条、诊断对话框共 **165 个键**，
英文 root + 简体中文覆盖。英文 IDE 显示英文，中文 IDE 显示中文；
繁体中文 / 日文 / 韩文翻译仍停在旧版（`docs/archive/`）。

---

## 与上游的区别

本版基于 [yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains) 的 v0.5.x 开发，**核心拖尾物理同源**，补上了上游没有的一整层可用性：

| 能力 | 上游 | **本版** |
|---|---|---|
| 拖尾 / 辉光 / 残影 | ✅ | ✅ |
| 多光标 | ✅ | ✅ **0.9.32 起支持** |
| 保留原生光标 | ✅ | ⚠️ 默认关闭（见缺陷 2） |
| **设置面板** | ❌ 改代码 | ✅ **14 个滑块 + 10 个复选框** |
| **持久化** | ❌ | ✅ 存进 IDE 配置 |
| **动画预设** | ❌ | ✅ 3 个 |
| **颜色预设** | ❌ | ✅ 6 个（含动态彩虹） |
| **自定义预设** | ❌ | ✅ 上限 50 个 |
| **Tools 菜单** | 4 项 | ✅ **8 项** |
| **实时预览** | ❌ | ✅ 面板内有预览副本 |
| **辉光淡出** | ❌ | ✅ **限速下降、参数可调（0.9.126）** |
| **i18n** | ❌ | ✅ **165 个键（英文 + 简体中文）** |

### 0.9.83 以来发生了什么

`v0.9.83` 是最后一个打过 tag 的基线。此后本 fork 有 **106 个提交**、
**239 个文件**、**+42,323 / −3,464 行**（含文档，2026-11-02 实测）。
逐版本细节见 [docs/archive/reports/VERSION-0.9.126.md](docs/archive/reports/VERSION-0.9.126.md)。

| 领域 | 改了什么 | 为什么有价值 |
|---|---|---|
| 设置面板 | 7 个标签页；14 滑块 + 10 复选框 + 4 下拉 | 所有参数在 IDE 内直接调，不用改代码 |
| 预设 | 3 动画 + 6 颜色 + 最多 50 自定义；父链快照；13 个预设域字段 | 切预设不再偷偷改颜色、不再丢手调值 |
| 国际化 | 165 个键；英文 root + 简体中文 | 英文 IDE 不再中英混排 |
| 颜色路径 | 彩虹 / 双拼 / 纯色三条互斥路径 | 名字和行为一致，不再「选彩虹变粉色」 |
| 拖尾 / 输入 | 默认斜度 0；字号 / block inlay 漂移修复；按字符判断短跳 | 新安装是干净矩形；中文 / emoji / 换字号不飘 |
| 辉光 / 淡出（0.9.126） | 目标曲线 + 限速下降；逐 trail 状态；淡出参数真实影响尾巴 | 跟着拖尾一起暗，不是停下才暗；跨行不中断 |
| 多光标 / 平台 | 逐 trail 辉光状态；平台 252 read action；PyCharm 2025.2 同步 | 拆组后静止光标不孤光；PyCharm 不再冻结 |
| 质量 | 门禁脚本修复；113 个注册探针 ALL PASS；116 个 `V*.java`；VDocCheck | 「全绿」可复现，不再手抄数字 |

### 核心贡献：按字符判断的输入逻辑

**这是本项目与上游最本质的差异，也是手感更好的根源。**

上游用**像素反推字符数**：

```java
jumpX = 位移 / 光标宽;
isShortMove = |jumpX| <= threshold;   // 阈值 = max(16px, 字宽 × 2.5)
```

本项目用**编辑器的原生字符偏移**：

```java
charsMoved = |当前 offset - 上一帧 offset|;   // 精确的字符个数
isShortJump = sameLine && charsMoved <= typingMaxChars(3);
```

| 场景 | 上游 | **本项目** |
|---|---|---|
| 中文单字 | 大字号下需 `shortMoveMinChars` 补偿 | **天然正确** |
| emoji / 代理对 | 需自行换算 | `getOffset()` 直接给对 |
| 一帧合并多次击键 | 会误判成长距离 | `sameLine` + 字符数双重约束 |
| 换字号 | 阈值也跟着变，手感漂移 | **完全无关** |

**这个方案是踩了四次坑才定下来的**（源码注释完整记录了失败过程）：

1. 用光标宽归一化 → 本插件 2px 光标永远超不过阈值，短跳分支**永不生效**
2. 写死 16px → 字号 20px 的中文被判成长距离，「**英文不卡、删中文卡**」
3. `FontMetrics.charWidth('m')` → 'm' 是西文推进量，中文是 2 倍
4. 取「第 0 列到第 1 列宽度」→ 取的是**行首**字符，光标在中文区仍翻倍

### 精心调整的输入手感参数

上游的 `trailFactors` 是**硬编码数组** `{1.0, 0.9, 0.5, 0.3}`，改要重新编译。

本项目把它拆成三个**连续可调**的参数：

| 参数 | 作用 | 范围 |
|---|---|---|
| **拉伸强度**（`stretchRatioK`） | 派生四角梯度 | 1 ~ 8 连续 |
| **输入平滑**（`typingSmoothness`） | 把梯度朝 1 压平 | 0 ~ 100 |
| **斜度**（`slopeTicks`） | 纯矩形 ←→ 完全斜 | 0 ~ 100 |

由纯函数 `deriveTrailFactors(k, smooth, slope)` 计算，
**面板显示的数字与实际运行期用同一套公式**（修过一个「面板虚报 6.4 倍」的 bug）。

#### 斜度（0–100）

控制四边形**倾斜多少**，与拉伸多长解耦：

| 斜度 | 四角系数 | 拉伸 | 倾斜 |
|---|---|---|---|
| **0** | `0.86/0.86/0.32/0.32` | 5.84px | **0.00px** ← 纯矩形 |
| 50 | `0.93/0.79/0.37/0.27` | 7.10px | 2.02px |
| **100** | `1.00/0.72/0.42/0.22` | 8.26px | 4.06px ← 上游原版 |

**斜度 0 仍有拉伸**（5.84px）—— 它只取消倾斜，不取消形变。
原理：倾斜来自**同侧**上下角的时长差，把同侧两角取平均即消除倾斜，
而上下之间仍有梯度，拉伸保留。

斜度属于预设域：改它会派生副本，切预设回到该预设的值。

#### 单行打字曾经画不出斜缎带（0.9.82 修复）

四角排名用的是「比我小的有几个」计数：

```java
for (j) if (alignment[j] < alignment[i]) rank++;   // 并列时不计数
```

打字右移时四个对齐度是 `-0.71 / 0.71 / 0.71 / -0.71` —— 左右各有一对相等，
于是同侧两角拿到**同一个 rank**：

```
修前：TL=0  TR=2  BR=2  BL=0    只有 2 档
修后：TL=0  TR=2  BR=3  BL=1    4 档
```

同 rank → 同时长 → 同速度 → 左右边缘都垂直 → **只能画矩形，斜度恒为 0**。

改成稳定排序 + 索引 tiebreak（上游四个 JS 版本一致做法），
结果与上游逐位一致：打字 `0 2 3 1`、删除 `2 0 1 3`。

### 向上游回馈的三项修复（已合并进 v0.5.1 / PR #10）

| 修复 | 影响 |
|---|---|
| 只在空闲档唤醒时重置计时 | 停手后拖尾不再整段以 25fps 播放 |
| 中文短距离判定（`shortMoveMinChars`） | 大字号下单个汉字不再被误判成长距离 |
| IME 坐标缓存加入文档修改戳 | 第三方输入法上屏后不再错位 |

### 为什么手感更好

- **拉伸，不是残影。** 四个角各挂独立阻尼弹簧；按运动方向排名，前缘时长短、后缘时长长，
  矩形被拉成不规则四边形，而不是叠一层残影。
- **按字符判断短跳。** 用编辑器原生 offset 算精确字符数，中文单字、emoji、换字号
  都不会被误判成长跳。
- **三个连续可调参数。** 拉伸强度 / 输入平滑 / 斜度，替代上游写死的四档系数；
  面板和运行期共用 `deriveTrailFactors`。
- **辉光跟着拖尾暗（0.9.126）。** 目标就是拖尾自身强度曲线，亮度每帧最多下降
  `dt / 淡出时长`；参数决定「降多慢」，不是「何时开始」；跨行不中途灭，不按层数跳变。
- **多光标隔离。** 每个 trail 保存自己的亮度，组内只共享「显示最大值」；
  静止光标不再被邻居点亮，拆组后也不会单独发光。

---

## 安装

### 方式一：下载 Release（推荐）

1. 从 [Releases](../../releases) 下载 `neovide-cursor-*.jar`
2. IDEA → `Settings → Plugins → ⚙ → Install Plugin from Disk...`
3. 选择 jar，重启 IDE

### 方式二：自行构建

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 `
  -Version 0.9.126 `
  -OutName neovide-cursor-0.9.126.jar `
  -BaseJar "<path-to-any-idea-plugin-jar>"
```

本项目**不使用 Gradle** —— `build\offline-build.ps1` 用 `javac` + `jar` 手工打包，
只需一个 IDEA 自带的 jar 作为 classpath 基底。

---

## 使用

### 设置面板在哪里

`Settings / Preferences → 其他设置 → Neovide 光标`

面板分 7 个标签页：

| 标签页 | 内容 |
|---|---|
| **1 总开关** | 启用、渲染模式（弹簧 / 残影）、隐藏原生光标、常驻刷新 |
| **2 颜色** | 拖尾颜色、不透明度、辉光颜色、光标渐变 |
| **3 光标行手感** | 单行输入 / 退格的时长、拉伸、平滑、斜度、光标尺寸 |
| **4 列手感** | 多行缎带（换行 / 跳转） |
| **5 辉光** | 辉光预览、层数、锐度、不透明度、淡出 |
| **6 特殊** | 实验性选项（官方不建议） |
| **7 调试** | 高级诊断 |

### Tools 菜单

`Tools → Neovide 光标：动画预设 ▸` / `颜色预设 ▸`

外加四个开关：总开关、隐藏原生光标、性能诊断、残影模式。

### 预设的语义

**内置预设只读。** 你在某个内置预设上改任何一个参数，会自动派生出一个
「XX 副本」自定义预设并选中它 —— **内置那份永远不变**。

想保留一套配置，点「新建预设」起个名字即可。

> 自定义预设存的是**全部 19 个参数**的快照，不是增量。

---

## 参数说明

| 参数 | 默认 | 说明 |
|---|---|---|
| **拉伸强度**（`stretchRatioK`） | `4.5` | 四角时长梯度。越大越斜、越"缎带"。范围 1~8 |
| **输入平滑**（`typingSmoothness`） | `0` | 把四角系数朝 1 压平。**0 = 保持梯度** |
| **短动画时长**（`shortAnimationLength`） | `0.11` | 同行打字/退格的拖尾总时长 |
| **跨行动画时长**（`animationLength`） | `0.1` | 跨行跳转的拖尾时长 |
| **辉光锐度**（`shadowBlurFactor`） | `0.20` | 外扩半径 = 该值 × 光标较长边 |
| **辉光层数**（`glowLayers`） | `8` | **只影响过渡细腻度，不影响亮度** |
| **拖尾密度**（`trailSize`） | `1.0` | 前缘与后缘的时长差 |

> ⚠️ **「输入平滑」是纯粹的梯度压平，不是"让动画更顺"。**
> 实测 `smooth=0` 的二阶差分比 `smooth=0.8` **更小**（更顺），而梯度大 3.3 倍。
> 默认 0 是实测选择，不建议随意调高。

---

## 已知缺陷

> 如实记录，不粉饰。

### 1. 残影模式仍是单光标

拖尾（弹簧）模式已完整支持多光标；**残影模式只跟主光标**。
`AfterimageTrail` 是「单条历史轨迹」模型，多光标需要每光标一条轨迹 + 各自淘汰。

### 2. 保留原生光标时可能"两个光标"

本版默认**隐藏原生光标**（`hideNativeCaret = true`），由覆盖层全权绘制。

若你选择保留原生光标，可能出现：

- IDEA 原生光标约 **5px** 宽
- 覆盖层用 `NeovideConfig.cursorWidth`（默认 **2px**）
- **宽度不一致 → 两条边都露出来，看起来像两个光标**

修法已定位（读 `EditorSettings.getLineCursorWidth()` 对齐），但**尚未实现**。

### 3. 帧率受 EDT 影响

Swing/AWT 的 `invokeLater` 优先级低于原生输入事件，编辑器繁忙时会掉帧。

**实测**（2609 条日志）：fps 中位 **36.4**，maxGap 中位 **51ms**、P90 **279ms**。

**替代方案**：`Tools → 残影模式`。它记录真实历史位置，掉帧只让残影稀疏、轨迹不失真。

### 4. 快速连打吃不到长拖尾

`TrailCorner.jump()` 每次按键无条件 `reset()`，
所以后缘时长超过键间隔（80~150ms）时**实际达不到**。
声明 240ms 的拖尾在连打时感受不到。

### 5. 不采用"前缘硬吸附"

上游用「前缘瞬间到位」换拉伸感。本版没采用 —— 因为前缘时长归零
意味着前缘完全没有平滑，与「平滑输入」直接矛盾。

**0.9.83 又试了一次，实测确认不能采用**：

```
阈值      吸附角数   拉伸px   斜度px
关闭        0         8.26     4.06
0.3~0.707   2        11.58     2.11   ← 斜度反而变小
>0.707     0         8.26     4.06   ← 完全不触发
```

两个硬障碍：

1. **对齐度是离散的。** 纯轴向移动恒为 ±0.707，没有可调区间。
   阈值只能一刀切 —— 想「只吸附 1 个角」在纯轴向移动下不可能实现。
2. **吸附会压小斜度。** 2 个角同时吸附消除了同侧两角原本的时长差，
   而「斜」正是由那个差产生的 —— 效果与目标相反。

要真做得先改 rank 体系（用移动向量而非角点残余位移），那是动核心物理。

### 6. 「基础」预设是三者里最跳的

因为「基础」必须逐位等于 `NeovideConfig` 默认值（契约要求），
而另两个预设的短跳时长更长、单帧位移更小。

### 7. 连续 10 键/秒时辉光会呼吸

两次按键之间拖尾几乎收拢，0.9.126 的限速淡出会在下一次按键前先降一点亮度，
按键时再回满。当前配置（保持 25ms / 淡出 50ms）约在 **0.32 ~ 1.0** 之间起伏；
保持 50 / 淡出 75 时约 0.57 ~ 1.0。这是参数权衡，不是正确性 bug：
把「淡出前保持时长」调到 **100ms** 可覆盖 100ms 的按键间隔，或把「淡出时长」调大。
可选的「连续输入保护」在报告里，但 0.9.126 未启用。

---

## 技术实现

### 挂载点：`contentComponent`

`editor.getContentComponent()`（`EditorComponentImpl`）是唯一安全点：
它是 `JTextComponent`、`layout` 为 `null`，不会被 `revalidate()` 拉伸；
坐标系与 `visualPositionToXY()` 天然对齐。

**已证伪**：`JBViewport`、`editor.getComponent()`、祖先 `JLayeredPane`、窗口根 `LayeredPane`
—— 都会让覆盖层被拉伸到整个编辑器（"全屏变色"）。

### 拖尾原理：拉伸，不是残影

1. 4 个角点，各挂 X/Y 阻尼弹簧（`DampedSpring`，闭式解，不受帧率波动影响）
2. 移动时按「运动方向与角点方向的夹角」排序（`assignRanks`）
3. 前方角拿大系数（跟得紧），后方角拿小系数（拖沓）
4. 矩形被拉成不规则四边形 → 视觉上就是拖尾

### 高精度调度

| 机制 | 实测精度 |
|---|---|
| `javax.swing.Timer`（`Object.wait`） | 15.61 ms ❌ 钉死 64fps |
| `Thread.sleep` | **1.56 ms** ✅ |

### 辉光：多层同心外扩

每层 alpha 由层数反解：`A = 1 - (1 - peak)^(1/N)`
——「层数」与「强度」互不干扰。半径线性分布，在 `r ≈ glowWidth/2` 处衰减到峰值 60%。

---

## 贡献者

- **pawamamama**（[@pawamamama](https://github.com/pawamamama)）—— 本 fork 维护者与提交作者。
  自 v0.9.83 以来：**106 个提交、239 个文件、+42,323 / −3,464 行**（代码 + 测试 + 文档，2026-11-02 实测）。
  当前代码：**生产 37 文件 / 21,342 行**，**探针 131 文件 / 33,487 行**（116 个 `V*.java`，113 个注册）。
  维护了设置 / 预设层、i18n、平台 252 read action 修复和 0.9.126 辉光淡出模型。
- **yyyyolo7a79-sketch** —— 原项目与核心拖尾物理，见「致谢」。

---

## 致谢

- **[yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains)**
  —— 本项目的起点。核心拖尾物理、踩坑记录、目录结构均来自该项目。
- **[LengineerC/vscode-neovide-cursor](https://github.com/LengineerC/vscode-neovide-cursor)**
  —— 参数语义与 VS Code 版对齐的原始依据。
- **[Neovide](https://github.com/neovide/neovide)** —— 拖尾效果的最初来源。

---

## License

MIT，见 [LICENSE](LICENSE)。

算法源自 VS Code 版 `neovide-cursor`（MIT），详见 [NOTICE.md](NOTICE.md)。
