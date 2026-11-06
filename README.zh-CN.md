# Neovide Cursor for JetBrains

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2%20–%202026.1-000000?logo=intellijidea&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)

> 给 JetBrains IDE 换上 Neovide 风格的光标拖尾：光标矩形的四个角各挂一套阻尼弹簧，
> 移动时被拉成一条渐隐的缎带，外面裹一层柔和的多层辉光。

**当前版本：`v0.9.138-a1`**（2026-11-06）。
A1「视觉行分支统一」已实现，并已由维护者在 **IntelliJ IDEA 2026.1.3**（Windows 11）
上**实机验收通过**。

**语言 / Language：** [English](README.md) | 简体中文

---

## 0.9.138-a1（A1）改了什么

A1 之前，插件用**两套不同的判据**选择动画分支：

- 单光标：`|offset 变化| <= typingMaxChars`（字符数）；
- 多光标：写死的 **16px** 像素兜底（`charsMoved = -1`）。

实机日志证明这个组合会把同行大跳（`Tab`、`End`、`Ctrl+方向`、按词移动、
同行鼠标跳转）误判成跨行：320 条样本里，多光标的 100 次 CROSS 有 93 次发生在
同一视觉行，其中 49 次位移只有 17px，刚过 16px 阈值。

A1 把两者统一成**一条规则**：

```text
同一屏幕视觉行  -> SHORT 分支（shortAnimationLength）
视觉行发生变化  -> CROSS 分支（animationLength）
```

具体改动：

- 单光标与多光标共用
  `SpringTrail.semanticCharsMoved(initialized, previousVisualLine, currentVisualLine)`。
- 设置页预览发送同一组语义值：`INLINE -> 0`，`CROSS -> Integer.MAX_VALUE`。
- 旧的字符数判据（`lastCaretOffset` / `updateCharsMoved`）和多光标像素兜底
  （`updateBlockCharsMoved`）已从**生产路径删除**。
- `TrailCorner` 的旧像素兜底（`charsMoved < 0`）只保留给「没有提供语义值」的调用方；
  生产路径不再走它。
- 行为变化：同行大跳现在走 **SHORT** 分支，不再被误判成跨行；真实换行仍走
  **CROSS** 分支，并保持现有的 A-like 缎带形状。
- 每个光标保存自己的上一帧视觉行：同一帧里，真的换行的光标走 CROSS，
  同行移动的光标走 SHORT，互不串扰。

**本版验证**（命令与完整日志见
[发布报告](docs/sessions/2026-11-06-release-0.9.138/REPORT.md)）：

| 检查项 | 结果 |
|---|---|
| 全量门禁 | `V-PROBES RESULT: ALL PASS`（117 个注册探针） |
| D0 门禁 | `core/NeovideConfig.java` 零 diff（`EXIT=0`） |
| A1 专项探针 | `VMultiBranchDiag` 35/35、`VPreviewBranch` 8/8、`VTypingSpan` 9/9 |
| 实机验收 | **PASS**，IDEA 2026.1.3，2026-11-06 |

---

## 与上游的关系

本仓库是 [yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains)
的独立 **MIT fork**。核心拖尾物理与基础代码来自上游；上游版权声明与 MIT
[`LICENSE`](LICENSE) 原样保留。上游来源与改动表见 [NOTICE.md](NOTICE.md)；
该表是较早的快照，当前参数与预设数量以本 README 为准。

按维护者的实机体验，上游基线在日常使用中不够可用。本 fork 的工作主要集中在：

- 手感与物理调优（短跳梯度、拉伸 / 斜度、辉光行为）；
- 设置面板、持久化与预设；
- 辉光淡出、多光标行为与平台 read-action 兼容；
- A1 视觉行分支判据，替换旧的字符数 / 像素兜底两套判据；
- 探针、发布门禁、打包与发布文档。

本 fork **不主张上游核心实现的原创性**；贡献集中在可用性、交互、调优、验证与发布工程。

---

## 功能

- **拉伸，不是残影。** 光标矩形的四个角各有一套 X/Y 阻尼弹簧；按运动方向排名，
  前缘角跟得快、后缘角跟得慢，矩形被拉成不规则四边形，而不是叠一层残影。
- **视觉行分支判据（A1）。** 单行打字 / 退格与跨行缎带由光标的屏幕视觉行选择，
  单光标与多光标规则相同。
- **七标签设置面板。** 所有观感参数都能在 IDE 里直接调、直接存，不用改代码、不用重编译。
- **预设。** 3 个内置动画预设、6 个内置颜色预设、最多 50 个自定义预设。
  内置预设只读：在内置预设上改参数会自动派生一个「XX 副本」。
- **实时预览。** 「光标行手感」「列手感」「辉光」三页各有一个动画预览，
  与真机共用同一套生产逻辑。
- **Tools 菜单快捷方式。** 不进设置页即可切换动画 / 颜色预设和几个主开关。
- **辉光淡出。** 亮度跟随拖尾强度，下降速度每帧最多 `dt / 淡出时长`；
  淡出参数决定「降多慢」，而不是「何时开始」。
- **多光标支持**（弹簧模式），每根 trail 保存自己的辉光状态。
- **国际化。** 168 个消息键，英文 root + 简体中文。

---

## 设置面板（7 个标签页）

`Settings / Preferences -> 其他设置 -> Neovide 光标`

| 标签页 | 内容 |
|---|---|
| **1 总开关** | 启用、隐藏原生光标、常驻高帧率、目标帧率（125 / 144）、多光标关闭辉光 |
| **2 颜色** | 拖尾颜色、拖尾不透明度、辉光颜色、光标渐变（双拼） |
| **3 光标行手感** | 短跳时长、输入平滑、拉伸强度、斜度、前缘贴合（占位）、光标宽高 |
| **4 列手感** | 跨行动画时长、拖尾密度、跨行形状实验（默认关） |
| **5 辉光** | 实时预览、辉光层数、锐度、不透明度、淡出保持 / 时长、淡出开关 |
| **6 特殊** | 渲染模式（弹簧 / 残影）、实验性选项 |
| **7 调试** | 诊断日志、绘制诊断 |

控件数量：**14 个滑块 + 11 个复选框 + 4 个下拉**。设置模型共 **29 个字段**，
其中 **13 个**属于预设域。

### 关键参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| 拉伸强度（`stretchRatioK`） | `4.5` | 四角时长梯度，范围 1–8 |
| 输入平滑（`typingSmoothness`） | `0` | 把梯度朝 1 压平；`0` = 保留梯度 |
| 斜度（`slopeTicks`） | `0` | 0 = 矩形，100 = 上游四档倾斜 |
| 短跳时长（`shortAnimationLength`） | `0.11 s` | 同一视觉行的打字 / 退格 |
| 跨行时长（`animationLength`） | `0.10 s` | 视觉行变化；上限 0.35 s |
| 拖尾密度（`trailSize`） | `1.0` | 前缘与后缘的时长差。**1.0 是期望值**；调低只会让缎带更短 |
| 辉光层数（`glowLayers`） | `8` | 只影响过渡细腻度，不改变整体亮度 |
| 辉光锐度（`shadowBlurFactor`） | `0.20` | 外扩半径 = 该值 × 光标较长边 |
| 辉光不透明度（`glowOpacity`） | `0.55` | 辉光峰值不透明度 |
| 淡出前保持（`cursorDisappearDelay`） | `50 ms` | 开始下降前的保持时长 |
| 淡出时长（`cursorFadeOutDuration`） | `0.075 s` | 每帧最大下降速度 |
| 光标宽 / 高 | `2 px` / `1.0` | 高度 1.0 = 顶格 |

> **`trailSize = 1.0` 是有意为之。** 它给出最长的可见缎带。调低会让拖尾更短、
> 更不明显，它不是任何已知问题的解决办法。

---

## 实验性功能 —— 不建议日常使用

### 跨行形状实验（`crossShapeExperiment`）

- **设备级复选框，默认关**，位于「特殊」标签页。
- 开启后只接管**跨行 / 鼠标拖动 / 键盘大跨度**路径；单行打字、斜度、拉伸、
  平滑、辉光、颜色、淡出都不变。
- 当前公式（A-like，VS Code 1.0.1 风格）：运动对齐度 `> 0.5` 的角固定给 **20 ms**，
  其余角取 `animationLength × {1.0, 0.72, 0.42, 0.22}`。
- **状态：实验性，尚未改好。** A-like 形状还没有通过观感验收，
  **不要在日常工作里使用**。
- 开启后**「拖尾密度」会置灰**，不再影响该路径；它的值会保留，不会被清空。
  跨行动画时长仍然是时间基准。
- 与「缎带」预设组合时可能很难看；这是已知的组合限制。

### 前缘贴合（`leadingSnap`）

占位复选框。能勾选、能持久化，但**不改变任何行为**；界面文案已写明「待实现」。

### 残影模式

实验性渲染模式。它记录真实历史位置，掉帧只会让残影变稀；但它**只跟随主光标**，
辉光淡出也不适用于它。除非你确实需要在低帧率环境里使用，否则请保持默认的
**弹簧（拉伸）** 模式。

---

## 安装

### 从 Release 安装（推荐）

1. 从 [Releases](https://github.com/pawamamama/neovide-cursor-for-jetbrains/releases)
   下载 `neovide-cursor-0.9.138-a1.jar`。
2. IDE：`Settings -> Plugins -> 齿轮 -> Install Plugin from Disk...`
3. 选择 jar，重启 IDE。

### 用仓库脚本安装（Windows，开发期便利）

```powershell
# 只预览备份/丢弃清单，不动任何文件
pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.138-a1 -DryRun

# 安装但不启动 IDE
pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.138-a1 -NoStart
```

脚本会关闭 IDEA、清空插件 `lib` 目录、设置 XML 和诊断日志，拷贝新 jar，
再把备份的个人配置写回。它写死了 Windows 上 **IDEA 2026.1** 的配置路径，
不会自动安装到 PyCharm。

> 已安装目录里的文件名恒为 `jetbrains-neovide-cursor-0.9.8.jar`（加载约定）。
> **不要用文件名判断版本**；请读 `META-INF/plugin.xml` 里的 `<version>`。

### 从源码构建

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 `
  -Version 0.9.138-a1 `
  -OutName neovide-cursor-0.9.138-a1.jar `
  -BaseJar "<任意一个 IDEA 插件 jar 的路径>"
```

不需要 Gradle：`offline-build.ps1` 用 `javac` 编译、用 `jar` 打包，
只需要一个 IDE 自带的 jar 作为 classpath 基底。

---

## 兼容性

| 项 | 值 |
|---|---|
| IDE 构建区间 | `since-build="242"` / `until-build="261.*"` -> IntelliJ Platform **2024.2 至 2026.1** |
| Java | 21（class 文件版本 65） |
| 已实机验证 | **IntelliJ IDEA 2026.1.3**（build 261），Windows 11 |
| 已验证构建 | `0.9.138-a1`，jar SHA-256 `b81ec276b403eb81d2caaf68393daac492f0245c6542ef5bef99ede0252688ff` |
| 未验证 | 2024.2 运行时；其他 JetBrains 产品（WebStorm、GoLand、CLion、Rider、PhpStorm 等）；Linux / macOS |
| 不支持 | Fleet |

维护者机器上的 PyCharm 2025.2 装的是**上游 0.6.0**，仅用于对比，不是本版。

---

## 已知限制

1. **残影模式只支持单光标。** 弹簧模式支持多光标；残影模式只跟随主光标。
2. **保留原生光标时可能像「两个光标」。** 原生光标约 5px 宽，覆盖层默认 2px。
   保持 `hideNativeCaret = true`（默认）即可避免。
3. **帧率受 EDT 限制。** 渲染走 `invokeLater`，优先级低于原生输入事件。
   2609 条日志样本实测：fps 中位 36.4，maxGap 中位 51ms、P90 279ms。
4. **快速连打吃不到长拖尾。** `TrailCorner.jump()` 每次按键都会 `reset()`，
   后缘时长超过按键间隔（80–150ms）时实际达不到。
5. **「基础」预设是三者里最跳的。** 它必须逐位等于 `NeovideConfig` 默认值；
   另外两个预设的短跳时长更长。
6. **约 10 键/秒时辉光会呼吸。** 默认 50ms 保持 / 75ms 淡出时，亮度约在
   0.57–1.0 之间起伏；25/50 配置可低到约 0.32。介意的话把「淡出前保持」
   调到 100ms，或增大「淡出时长」。
7. **`crossShapeExperiment` 尚未完成。** 见上面的警告；日常使用请保持关闭。
8. **`leadingSnap` 是占位开关。** 勾选它不会改变任何东西。
9. **终端不会播放动画。** 终端光标不受 `CARET_COLOR` 控制，这是已知的平台限制。
10. **A1 改变了同行大跳的行为。** `Tab`、`End`、`Ctrl+方向`、按词移动和同行鼠标跳转
    现在走短跳分支。这是 A1 的有意行为，并已实机验收通过，不是 bug；
    真实换行仍走 CROSS 缎带。

---

## 工作原理

1. 光标矩形有四个角；每个角挂一套 X/Y 阻尼弹簧（`DampedSpring`，闭式解，
   不受帧率波动影响）。
2. 移动时按「运动方向与角点方向的夹角」排名（`assignRanks`，稳定排序 + 索引 tiebreak）。
3. 排名映射为时长系数：前缘角系数大（跟得紧），后缘角系数小（拖沓）。
4. A1 用光标上一帧 / 当前帧的**屏幕视觉行**选择 SHORT 或 CROSS，
   单光标与多光标规则相同。
5. 辉光按同心层绘制；每层 alpha 由层数反解，所以「层数」与「强度」互不干扰。
   淡出按 `dt / 淡出时长` 限速。
6. 帧调度由后台线程按 `nanoTime` 睡眠，再通过 `invokeLater` 回 EDT 绘制。

延伸阅读：

- [docs/CURRENT-STATE-2026-11-04.md](docs/CURRENT-STATE-2026-11-04.md) —— 当前版本、`trailSize`、A–E 状态
- [docs/WORKFLOW.md](docs/WORKFLOW.md) —— 项目五步工作流
- [docs/WORK-DISCIPLINE.md](docs/WORK-DISCIPLINE.md) —— 14 条项目纪律
- [docs/PARAMETER-MAP.md](docs/PARAMETER-MAP.md) —— 哪些参数跟随预设
- [docs/INDEX.md](docs/INDEX.md) —— 全部文档索引
- [CLAUDE.md](CLAUDE.md) —— 新会话接手入口

---

## 贡献者

- **pawamamama**（[@pawamamama](https://github.com/pawamamama)）—— 本 fork 维护者：
  设置 / 预设层、国际化、平台 252 read action 修复、辉光淡出模型，
  以及 A1 视觉行分支统一。
- **yyyyolo7a79-sketch** —— 原项目与核心拖尾物理。

---

## 致谢

- **[yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains)**
  —— 本项目的起点：核心拖尾物理、踩坑记录和目录结构。
- **[LengineerC/vscode-neovide-cursor](https://github.com/LengineerC/vscode-neovide-cursor)**
  —— 参数语义与 VS Code 版对齐的原始依据。
- **[Neovide](https://github.com/neovide/neovide)** —— 拖尾效果的最初来源。

---

## License

MIT，见 [LICENSE](LICENSE)。

算法源自 VS Code 版 `neovide-cursor` 扩展（MIT），详见 [NOTICE.md](NOTICE.md)。

---

<sub>**给贡献者：** 本项目的「测试」就是探针门禁 —— 运行
`tools/settings-probe/build-v-probes.ps1`，看最后一行的 `V-PROBES RESULT`。
`core/NeovideConfig.java` 是 D0 冻结文件：任何 diff 都会让发布门禁失败。</sub>
