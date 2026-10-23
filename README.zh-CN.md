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

> **这是本版和原版最大的区别** —— 11 个滑块 + 7 个复选框，全部实时生效、持久化。

---

### 关于界面语言

**当前版本界面为简体中文**，暂未做国际化（i18n）。

原因是项目还在早期，想先确认是否有实际需求。如果使用人数够多，后续会补英文/日文。
**设置项的名称和参数说明都是技术术语，配合本文档的参数表不影响使用。**

---

## 与上游的区别

本版基于 [yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains) 的 v0.5.x 开发，**核心拖尾物理同源**，补上了上游没有的一整层可用性：

| 能力 | 上游 | **本版** |
|---|---|---|
| 拖尾 / 辉光 / 残影 | ✅ | ✅ |
| 多光标 | ✅ | ❌ 见[已知缺陷](#已知缺陷) |
| 保留原生光标 | ✅ | ⚠️ 默认关闭（见缺陷 2） |
| **设置面板** | ❌ 改代码 | ✅ **11 个滑块 + 7 个复选框** |
| **持久化** | ❌ | ✅ 存进 IDE 配置 |
| **动画预设** | ❌ | ✅ 3 个 |
| **颜色预设** | ❌ | ✅ 5 个 |
| **自定义预设** | ❌ | ✅ 上限 50 个 |
| **Tools 菜单** | 4 项 | ✅ **8 项** |
| **实时预览** | ❌ | ✅ 面板内有预览副本 |

### 向上游回馈的修复

以下三个修复已合并进上游 **v0.5.1**（PR #10）：

| 修复 | 影响 |
|---|---|
| 只在空闲档唤醒时重置计时 | 停手后拖尾不再整段以 25fps 播放 |
| 中文短距离判定（`shortMoveMinChars`） | 大字号下单个汉字不再被误判成长距离 |
| IME 坐标缓存加入文档修改戳 | 第三方输入法上屏后不再错位 |

---

## 安装

### 方式一：下载 Release（推荐）

1. 从 [Releases](../../releases) 下载 `neovide-cursor-*.jar`
2. IDEA → `Settings → Plugins → ⚙ → Install Plugin from Disk...`
3. 选择 jar，重启 IDE

### 方式二：自行构建

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 `
  -Version 0.9.14 `
  -OutName neovide-cursor-0.9.14.jar `
  -BaseJar "<path-to-any-idea-plugin-jar>"
```

本项目**不使用 Gradle** —— `build\offline-build.ps1` 用 `javac` + `jar` 手工打包，
只需一个 IDEA 自带的 jar 作为 classpath 基底。

---

## 使用

### 设置面板在哪里

`Settings / Preferences → 其他设置 → Neovide 光标`

面板分四组：

| 分组 | 内容 |
|---|---|
| **总开关** | 启用、渲染模式（弹簧 / 残影）、隐藏原生光标、常驻刷新 |
| **动画时序** | 跨行动画时长、短跳时长、拉伸强度、输入平滑 |
| **外观** | 拖尾/辉光颜色、辉光层数、锐度、不透明度、淡出 |
| **预设** | 动画预设、颜色预设、新建 / 重命名 / 删除 |

**支持搜索**：在设置页搜索框输入「拖尾」「辉光」，可直接定位到对应项。

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

### 1. 多光标不支持

上游有（`Map<Caret, SpringTrail>`），本版只画主光标。
**Alt + 拖拽列选时，只有主光标有拖尾。**

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

### 6. 「基础」预设是三者里最跳的

因为「基础」必须逐位等于 `NeovideConfig` 默认值（契约要求），
而另两个预设的短跳时长更长、单帧位移更小。

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
