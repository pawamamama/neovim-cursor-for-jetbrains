# 上游 v0.6.0 对比核实（2026-11-02）

> 本文只记录可复现的技术事实，不对动机或法律归属作判断；技术定性仅就代码证据。
> 所有「相似 / 不相似」均附命令、行号或统计数字。

## 零、一句话结论

上游 v0.6.0 共 21 个 `.java`，本地主源码 37 个；上游本次新增 7 个文件（6 个设置相关类 + `CursorSettings`）。
设置页、实时预览、滑块三个模块的**结构不同**：去注释归一化后，最长连续相同块分别只有 **6 / 4 / 2 行**，且都是 `}`、`@Override`、import 这类通用行；difflib 整体 ratio 为 **2.8% / 6.8% / 0.4%**，逐字相同的注释 **0 条**。
唯一需要单独记录的是 UI 文案「弹簧拉伸（Neovide 原版效果）」在两边逐字相同，本地首次引入于 **2026-10-19**，早于上游本次设置页提交 **2026-11-01**。
`core/` 与部分 `ui/` 同名文件相似度高（58%–100%），属共同基座继承，已单独隔离，不计入新代码对比。
上游 0.6.0 已按用户选择安装到 PyCharm 2025.2；PyCharm 中的本地 0.9.126 已整体移出并备份。用户已完成主观试用（打字手感、设置页、跨行观感），未做帧率/延迟等量化测量。
跨行缎带、打字手感、设置页预览三项观感差异，均已在两边代码中找到对应机制（见 6.4）。

---

## 一、取证

### 1.1 上游 v0.6.0

```bash
gh api repos/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/tags \
  --jq '.[] | "\(.name) \(.commit.sha)"'

gh api repos/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/commits/v0.6.0 \
  --jq '{sha, author_date: .commit.author.date, committer_date: .commit.committer.date, message: .commit.message}'
```

结果：

| 项 | 值 |
|---|---|
| tag | `v0.6.0` |
| commit | `d6a43fb6a383928d6f7d036023e043c28b1c652f` |
| author / committer date | `2026-11-01T14:10:34Z`（北京时间 22:10） |
| 提交说明 | `Merge pull request #11 ... 0.6.0 — 设置页（可视化调色 + 实时预览）` |
| 设置页功能提交 | `e951e2a5`，`2026-11-01T14:07:47Z`（北京时间 22:07） |

上游 `v0.5.1...v0.6.0` 共改 16 个文件，其中新增 7 个 Java 文件：

```text
core/CursorSettings.java                 新增 181 行
settings/NeovideSettings.java            新增  41 行
settings/NeovideSettingsConfigurable.java 新增 62 行
settings/NeovideSettingsPanel.java       新增 319 行
settings/TrailPreview.java               新增 182 行
settings/ValueSlider.java                新增  89 行
ui/OpenSettingsAction.java               新增  20 行
```

另修改 `ui/CaretAnimator.java`（28 行）、`ui/NeovideCaretManager.java`（126 行）、`plugin.xml`、`gradle.properties` 与 5 个 README；`core/` 物理引擎文件本次未改。

### 1.2 本地版本

```bash
git rev-parse v0.9.83^{commit} v0.9.126^{commit} HEAD
git log -1 --format="%H %ai %s" v0.9.83^{commit}
```

| 项 | 值 |
|---|---|
| `v0.9.83` | `9df135609a4b9a8c7d2bc00873100683b8348c08`，`2026-10-25 19:53:50 +0800` |
| `v0.9.126` | `1dcaa7afa3da06f5948864e22357640332fb8ed2`，`2026-11-02 04:17:54 +0000`（北京时间 12:17） |
| `HEAD` | `262db56bcad8f1934348201f12905137cfbbfc46`，`2026-11-02 05:16:11 +0000` |
| 设置面板首次提交 | `4ffd29d`，`2026-10-19 23:00:53 +0800` |
| 动画预览首次提交 | `dece706`，`2026-10-24 16:47:42 +0800` |

### 1.3 真实分叉点（一处更正）

本地 `v0.5.1` 标签指向的 commit 是 `91fb7d0`，与上游 `v0.5.1` 相同；但 `91fb7d0` **不是本地 HEAD 的祖先**：

```bash
git merge-base --is-ancestor v0.5.1 HEAD   # exit=1
git merge-base v0.5.1 HEAD                 # ee378a20ef542f4020469e25663ca4c37413c06e
```

真实共同基点是 **`ee378a2`（2026-09-19 23:25:14 +0800）**，它是 `origin/main` 的祖先；本地 main 从该点分出，之后有 316 个本地提交：

```bash
git merge-base --is-ancestor ee378a20 origin/main   # exit=0
git rev-list --count ee378a20..HEAD                 # 316
```

因此本文所有「基座继承」判断以 `ee378a2` 为基线，而不是 `v0.5.1`。

### 1.4 时间先后

| 事件 | 时间（UTC） |
|---|---|
| 共同基点 `ee378a2` | 2026-09-19 15:25 |
| 本地设置面板 `4ffd29d`（含「弹簧拉伸（Neovide 原版效果）」） | **2026-10-19 15:00** |
| 本地动画预览 `dece706` | 2026-10-24 08:47 |
| 本地设置文案迁入资源包 `caca717` | 2026-11-01 08:29 |
| 上游设置页 `e951e2a5` | **2026-11-01 14:07** |
| 上游 `v0.6.0` tag | 2026-11-01 14:10 |

**本地设置面板与预览的首次提交，早于上游 v0.6.0 设置页约 13 天。**

---

## 二、文件清单对比

上游 v0.6.0：21 个 `.java`。
本地 HEAD 主源码：37 个 `.java`（不含 `tools/` 下 160 个探针/基准文件）。

### 2.1 两边都有、类名完全相同（14 个）

```text
core/AfterimageTrail.java
core/DampedSpring.java
core/NeovideColors.java
core/NeovideConfig.java
core/TrailCorner.java
ui/CaretAnimator.java
ui/CaretTrailPanel.java
ui/NeovideCaretManager.java
ui/RenderScheduler.java
ui/SpringTrail.java
ui/ToggleHideNativeCaretAction.java
ui/ToggleNeovideAction.java
ui/TogglePaintDiagAction.java
ui/ToggleTrailModeAction.java
```

### 2.2 上游有、本地没有同名文件（7 个，本次新增）

```text
core/CursorSettings.java
settings/NeovideSettings.java
settings/NeovideSettingsConfigurable.java
settings/NeovideSettingsPanel.java
settings/TrailPreview.java
settings/ValueSlider.java
ui/OpenSettingsAction.java
```

### 2.3 本地有、上游没有同名文件（23 个）

```text
core/GlowIntensity.java
settings/CursorPresets.java
settings/CustomPresets.java
settings/NeovideCursorSettings.java
settings/SettingsField.java
ui/AnimPresetGroup.java
ui/CaretAnimatorProbe.java
ui/CaretSchemeWatcher.java
ui/ColorPresetGroup.java
ui/CursorDiag.java
ui/CursorPresetApplier.java
ui/EdtWatchdog.java
ui/ToggleAlwaysHighFpsAction.java
ui/ToggleFadeOutAction.java
ui/settings/AnimationPreview.java
ui/settings/ColorPanelBinding.java
ui/settings/EditSession.java
ui/settings/NeovideCursorBundle.java
ui/settings/NeovideCursorConfigurable.java
ui/settings/PluginInfoBar.java
ui/settings/ProbeFacade.java
ui/settings/SearchIndex.java
ui/settings/SettingsUiUtil.java
```

（`SearchIndex.java` 全名为 `SettingsSearchIndex.java`。）

---

## 三、关键文件对比（逐模块）

对比方法：去掉 `//`、`/* ... */` 注释与空行后比较；同时计算最长连续相同块与 difflib ratio。

### 3.1 设置页：`NeovideSettingsPanel.java` vs `NeovideCursorConfigurable.java`

| 维度 | 上游 | 本地 |
|---|---|---|
| 文件行数 | 319 | 4552 |
| 归一化行数 | 263 | 2301 |
| 布局方式 | `FormBuilder.createFormBuilder()` 顺序直排 | DSL `panel.group(...)` 分区构建 |
| 分组 | 4 组：基本 / 颜色与辉光 / 动画 / 高级 | 10 组：presets / master / inline / cross / color / glow / cursorSize / fade / experimental / diag |
| 控件组织 | 11 个 `ValueSlider` 字段 + `FormBuilder` 行 | `addSlider(...)` 按 `SliderSpec` 表生成「滑块 + 输入框 + 数值标签」三列 |
| 文案来源 | Java 字符串硬编码 | `NeovideCursorBundle_zh_CN.properties` 国际化键 |
| 状态模型 | `CursorSettings` 快照 + `base` 回写 | `pending` 状态 + 脏标记 + 预设联动 |

量化：

| 指标 | 结果 |
|---|---|
| LCS 相同行 / 上游归一化行 | 20 / 263 = **7.6%** |
| 非平凡共享行（≥15 字符，去重） | 5 / 215 = **2.3%** |
| 最长连续相同块 | **6 行**，内容为 `}`、`@Override`、`protected void paintComponent(Graphics g) {` 等通用行 |
| difflib 整体 ratio | **2.8%** |
| 逐字相同的注释行（≥10 字） | **0 / 21** |

结论：**结构不相似**。上游是平台 `FormBuilder` 的线性表单；本地是自定义 DSL 分区 + 10 组 + i18n + 预设联动，代码规模相差约 14 倍。

### 3.2 实时预览：`TrailPreview.java` vs `AnimationPreview.java`

| 维度 | 上游 | 本地 |
|---|---|---|
| 文件行数 | 182 | 1027 |
| 归一化行数 | 139 | 480 |
| 实现方式 | 创建真实 `EditorEx`，挂真实 `CaretAnimator` | `extends JComponent` 自绘，直接持有 `TrailCorner[4]` |
| 物理计算 | 不在预览内实现，委托生产 `CaretAnimator` | 预览内自行 `pushTarget()` / `assignRanks()` / 弹簧积分 |
| 演示方式 | `Timer` 驱动光标跳转示例路线 | `inline` / `cross` 两种模式，模拟打字、停顿、逐字退格 |
| 实例数 | 1 个预览 | 3 个预览（inline / cross / glow） |
| 依赖 | `EditorFactory`、`CaretAnimator` | `TrailCorner`、`CaretTrailPanel`、`GlowIntensity` |

量化：

| 指标 | 结果 |
|---|---|
| LCS 相同行 / 上游归一化行 | 15 / 139 = **10.8%** |
| 非平凡共享行（≥15 字符，去重） | 5 / 109 = **4.6%** |
| 最长连续相同块 | **4 行**：`import javax.swing.JComponent;`、`import javax.swing.Timer;`、`import java.awt.Color;` 等 import |
| difflib 整体 ratio | **6.8%** |
| 逐字相同的注释行（≥10 字） | **0 / 17** |
| 逐字相同的中文字符串 | **0** |

结论：**结构不相似**。上游预览是「真编辑器 + 复用生产动画器」，本地预览是「自绘组件 + 预览内独立物理积分」，两者在数据流和绘制路径上都不同。

### 3.3 滑块：`ValueSlider.java` vs 本地 `addSlider`

| 维度 | 上游 `ValueSlider` | 本地 `addSlider` |
|---|---|---|
| 形态 | 独立类，89 行 | `NeovideCursorConfigurable` 内方法，约 115 行（1821–1935） |
| 组成 | `JSlider` + `JBLabel`，`BorderLayout` | 三列：`JSlider` → `JBTextField` → `JLabel` |
| 刻度 | `scale` 换算（100 / 1000） | `SliderSpec` 表 + `snapToTick` + `labelTable` |
| 未改动回写 | `get(float original)`：刻度未变则返回原值，避免手改配置被取整 | **无此方法**；用 `pending` + `syncingControls` + 显式 `ChangeListener` 处理 |
| 联动置灰 | `setEnabled` 手动逐项调用 | `GraphProperty<Boolean> enabledIf` 响应式绑定 |

量化：

| 指标 | 结果 |
|---|---|
| LCS 相同行 / 上游归一化行 | 7 / 60 = **11.7%** |
| 非平凡共享行（≥15 字符，去重） | 3 / 47 = **6.4%** |
| 最长连续相同块 | **2 行**：`}`、`}` |
| difflib 整体 ratio | **0.4%** |
| 逐字相同的注释行（≥10 字） | **0 / 8** |

结论：**结构不相似**。上游是「滑块 + 单个数值标签」的两段式独立组件；本地是「滑块 + 输入框 + 标签」的三列绑定，且带吸附、刻度表、预设联动。

---

## 四、非必要相似清单（a–f 六项）

「非必要相似」指本可有很多写法却写成一样。以下逐项给结论与证据。

### a. 常量值

- **未发现双方共有的、非通用的魔数。**
- 上游设置页的数字（`10, 20, 50, 60, 95, 100, 200, 300, 500, 1, 12, 16`）都是参数上下界与步长；本地对同一组参数使用相同物理范围（不透明度 10–100、辉光 0–95、层数 1–16 等），属于参数定义一致，不是特殊魔数。
- 上游预览的 `420`（预览宽度）、`650`（演示间隔）、`18`（色块尺寸）在本地预览中没有对应；本地预览的 `170 / 130 / 800 / 1100 / 14 / 28` 在上游没有对应。
- `0.58f`：只出现在本地 `ui/CaretTrailPanel.java:372`（`DUAL_TONE_LUMA_FACTOR`），上游没有；该值在共同基点 `ee378a2` 也不存在。

结论：**不相似**（无共同特殊常量）。

### b. 方法名

- `assignRanks` 在共同基点 `ee378a2` 已存在（当时位于 `ui/CaretAnimator.java:732`），本地后来的 `SpringTrail.assignRanks` 与预览里的 `assignRanks` 是同一基座演化的结果，属继承，不作为新代码相似证据。
- 上游新增文件的特有方法名 `demoStep`、`pickSample`、`setNativeCaretHidden`、`disposeAnimator`、`percent`、`millis`、`setTick`、`tick` 在本地对应文件中出现次数均为 **0**。
- 本地特有方法名 `addSlider`、`specOf`、`deriveTrailFactors`、`ranksForAlignment`、`pushTarget`、`refreshDerivedHints` 在上游新增文件中出现次数为 **0**。

结论：**不相似**（除基座继承的 `assignRanks` 外，无共同的不常见命名）。

### c. 变量名

- 共同出现的字段名只有 `tailColorPanel`、`tailOpacity`、`shadowColorPanel`、`glowOpacity`、`glowLayers` 等。
- 这些名字直接来自共享配置项（`tailColor`、`tailOpacity`、`glowOpacity`、`glowLayers`），属于领域自然命名，不是缩写或拼写错误型「指纹」。
- 未发现双方共同使用的不常见缩写或相同拼写错误。

结论：**不相似**（共享的是领域词，不是异常命名）。

### d. 注释文本

- 对上游新增 7 个文件逐文件比对，**逐字相同的注释行均为 0**：

| 文件 | 完全相同注释 / 注释总数 |
|---|---|
| `core/CursorSettings.java` | 0 / 24 |
| `settings/NeovideSettings.java` | 0 / 6 |
| `settings/NeovideSettingsConfigurable.java` | 0 / 6 |
| `settings/NeovideSettingsPanel.java` | 0 / 21 |
| `settings/TrailPreview.java` | 0 / 17 |
| `settings/ValueSlider.java` | 0 / 8 |
| `ui/OpenSettingsAction.java` | 0 / 2 |

- 全库范围另有 419 条逐字相同注释，但集中在 `core/AfterimageTrail.java` 等同名基座文件，属 `ee378a2` 之后共同继承的历史文本，已隔离。

结论：**不相似**（新增代码无相同注释）。

### e. 同样的怪癖

- 上游 `ValueSlider.get(float original)` 有一个明确怪癖：滑块刻度仍等于载入值时，原样返回 `original`，避免 `0.205` 被取整成 `0.21` 后立刻显示「已修改」。
- 本地全库检索 `get(original)` / `float get(`：**0 处**。本地用 `pending` 状态、`syncingControls` 与显式 `ChangeListener` 处理同一类问题，机制不同。
- 上游预览「复用真 `CaretAnimator`」与本地预览「自绘 + 独立积分」也是两套不同做法，不存在相同边界处理顺序。

结论：**不相似**（未发现共同怪癖）。

### f. 同样的 bug

- 未做系统性行为测试；主观试用不足以确认是否存在同一个 bug。
- 静态阅读未发现两边共有、可明确判定的同一错误。

结论：**无法判定**。

### 附：逐字相同的中文 UI 文案

对上游新增文件的中文字符串字面量做全库比对，共 8 条在本地文本中出现。逐条核对来源后：

| 上游文案 | 本地位置 | 是否完整 UI 标签 | 来源 / 时间 |
|---|---|---|---|
| `弹簧拉伸（Neovide 原版效果）` | `NeovideCursorBundle_zh_CN.properties:101`；原在 `NeovideCursorConfigurable.java`（`MODE_SPRING_TEXT`） | **是** | 本地 `4ffd29d`，2026-10-19 引入；基点 `ee378a2` 只有「弹簧（Neovide 原版效果）」，无「拉伸」二字 |
| `残影（低帧率更稳）` | `NeovideConfig.java:118` 注释 | 否 | 共同基点 `ee378a2` 已存在 |
| `启用辉光` | 本地 bundle 标签、`NeovideConfig.java` 注释 | 是 | 基点已有「是否启用辉光」注释，属继承词 |
| `辉光层数：` | `NeovideConfig.java:41`、`NeovideCursorConfigurable.java:494` 注释 | 否（本地标签无冒号） | 领域词 + 注释 |
| `拖尾不透明度：` | `NeovideCursorConfigurable.java:480` 注释 | 否（本地标签无冒号） | 领域词 + 注释 |
| `淡出时长：` | `NeovideCursorConfigurable.java:473` 注释 | 否（本地标签为「淡出时长（之后线性熄灭所需时间）」） | 领域词 + 注释 |
| `启用拖尾光标` | 本地标签为「启用拖尾光标（总开关）」 | 否（子串） | 本地 `4ffd29d` 引入的更长标签 |
| `颜色与辉光` | 本地设计文档与源码注释；当前分组标题为「颜色」 | 否 | 本地设计文档用语 |

需要单独记录的事实：

- 只有 **`弹簧拉伸（Neovide 原版效果）`** 这一条是两边**完整的 UI 标签逐字相同**。
- 本地在 `ee378a2` 的原文是「弹簧（Neovide 原版效果）」；本地于 2026-10-19 改为「弹簧拉伸（Neovide 原版效果）」；上游 2026-11-01 的设置页使用「弹簧拉伸（Neovide 原版效果）」。
- 其余 7 条或属基点继承，或只是本地更长标签/注释中的子串，不构成完整文案相同。

结论（仅就文案）：**部分相似（1 条完整标签）**；时间上本地在前。

---

## 五、量化相似度

### 5.1 三个关键模块

| 对比 | LCS 相同行 / 上游归一化行 | 非平凡共享行 | 最长连续块 | difflib ratio | 相同注释 |
|---|---|---|---|---|---|
| 设置页 | 20 / 263（7.6%） | 5 / 215（2.3%） | 6 行（通用行） | 2.8% | 0 |
| 预览 | 15 / 139（10.8%） | 5 / 109（4.6%） | 4 行（import） | 6.8% | 0 |
| 滑块 | 7 / 60（11.7%） | 3 / 47（6.4%） | 2 行（`}`） | 0.4% | 0 |

说明：LCS 百分比里包含 `}`、`return;`、import 等通用行，因此高于「非平凡共享行」；判断结构相似度应以后者与最长连续块为准。

### 5.2 同名文件（含基座继承，单独隔离）

| 文件 | LCS / 上游归一化 | 非平凡共享 |
|---|---|---|
| `core/AfterimageTrail.java` | 94 / 95（98.9%） | 72 / 73（98.6%） |
| `core/DampedSpring.java` | 34 / 36（94.4%） | 22 / 24（91.7%） |
| `core/NeovideColors.java` | 41 / 41（100%） | 26 / 26（100%） |
| `core/NeovideConfig.java` | 21 / 32（65.6%） | 19 / 28（67.9%） |
| `core/TrailCorner.java` | 111 / 133（83.5%） | 74 / 95（77.9%） |
| `ui/CaretAnimator.java` | 314 / 491（64.0%） | 186 / 304（61.2%） |
| `ui/CaretTrailPanel.java` | 195 / 249（78.3%） | 120 / 163（73.6%） |
| `ui/NeovideCaretManager.java` | 162 / 279（58.1%） | 87 / 145（60.0%） |
| `ui/RenderScheduler.java` | 49 / 71（69.0%） | 23 / 41（56.1%） |
| `ui/SpringTrail.java` | 86 / 106（81.1%） | 45 / 58（77.6%） |
| `ui/ToggleHideNativeCaretAction.java` | 13 / 14（92.9%） | 8 / 9（88.9%） |
| `ui/ToggleNeovideAction.java` | 14 / 14（100%） | 9 / 9（100%） |
| `ui/TogglePaintDiagAction.java` | 17 / 20（85.0%） | 11 / 14（78.6%） |
| `ui/ToggleTrailModeAction.java` | 17 / 17（100%） | 12 / 12（100%） |

**这 14 个文件是共同基点 `ee378a2` 之后各自演化的结果，相似度属基座继承，不计入本次「新代码」对比。**

### 5.3 整体哈希（按任务给定方法）

```bash
# 上游
find src -name "*.java" -exec cat {} \; | sed 's|//.*||; /^[[:space:]]*$/d' | md5sum
# 本地
find src/main/java -name "*.java" -exec cat {} \; | sed 's|//.*||; /^[[:space:]]*$/d' | md5sum
```

| 侧 | MD5 |
|---|---|
| 上游 v0.6.0 | `ebbe52793cafcb3243fe7e08d3cd8ce2` |
| 本地 HEAD | `d8192366ed21d85e7338b8d7b5d17469` |

该哈希只能证明「整体文本不同」，不能表达差异幅度；差异幅度以上表为准。

---

## 六、安装记录与实测

### 6.1 安装（2026-11-02）

用户选择：装到 PyCharm 2025.2，先移除 PyCharm 中的本地 0.9.126。

| 项 | 值 |
|---|---|
| 安装目标 | `C:\Users\wangw\AppData\Roaming\JetBrains\PyCharm2025.2\plugins\jetbrains-neovide-cursor\` |
| 上游安装包 | `jetbrains-neovide-cursor-0.6.0.zip`，63,367 字节 |
| 安装包 SHA-256 | `1829e5691ff0513a95c13fbe2944fc7342b70268bf1f493908d7ad9e68b638e9` |
| 安装包内 jar | `lib/jetbrains-neovide-cursor-0.6.0.jar`，67,485 字节，26 个 class |
| 上游插件 id | `com.github.yyyolo7a79.neovidecursor` |
| 上游插件 version | `0.6.0` |
| 本地插件 id | `com.pawamamama.neovidecursor` |
| 本地插件 version | `0.9.126` |
| 本地插件备份路径 | `D:\deepseekwork\_upstream-compare\backup\pycharm-neovide-cursor-pawamamama-0.9.126\` |

说明：

- 上游与本地**插件 id 不同**，不是同一插件的文件级覆盖；为避免两个插件同时挂钩编辑器，按用户要求把 PyCharm 中的本地插件目录整体移出（未做不可恢复删除）。
- 安装时 PyCharm 与 IDEA 均未运行；安装后 PyCharm `plugins/` 下只有 `jetbrains-neovide-cursor`。
- 本地插件原有的设置文件 `PyCharm2025.2/options/NeovideCursorSettings.xml` 未动；上游使用不同的配置服务，互不覆盖。

### 6.2 实测记录

#### 主观试用（用户反馈，非量化）

| 对比项 | 上游 0.6.0 | 本地 0.9.126 | 用户判断 |
|---|---|---|---|
| 打字手感 | 上游默认参数 | 本地 0.9.126 | 上游不如本地 |
| 设置页 | 真编辑器预览、4 组 | 自绘 3 个预览、10 组 | 上游设置页不错 |
| 跨行观感 | 短矩形尾、非斜缎带 | 默认斜缎带 | 上游更丝滑 |
| 调参空间 | 11 个滑块 | 更多滑块 + 预设 | — |

#### 量化测量（未做）

未采集帧率、输入延迟、掉帧率、预览刷新间隔等数据。上表只是主观试用记录，不作为性能结论。

### 6.3 回滚方法

1. 关闭 PyCharm。
2. 将 `plugins\jetbrains-neovide-cursor\` 移出 `plugins\`。
3. 将 `D:\deepseekwork\_upstream-compare\backup\pycharm-neovide-cursor-pawamamama-0.9.126\` 移回 `plugins\`，恢复为 `plugins\neovide-cursor-pawamamama\`。
4. 重新启动 PyCharm。

### 6.4 补充对比：跨行缎带、打字手感、设置页预览

用户实测反馈：上游打字手感不如本地；上游设置页不错；上游跨行不是斜缎带、因而更丝滑；本地可以改成「不拉伸」。

#### （1）跨行 / 缎带

**上游机制**（`core/NeovideConfig.java`、`core/TrailCorner.java`）：

- `useHardSnap = true`，`leadingSnapThreshold = 0.5`，`snapAnimationLength = 0`：对齐度大于 0.5 的角点**瞬时到位**。
- 其余角点按 `trailFactors = {1.0, 0.9, 0.5, 0.3}` 分配 `animationLength = 0.1s`。
- 于是最慢的两个角是 100ms 与 90ms，只差 10ms，尾部接近平行 —— 观感是**短矩形尾**，不是细长斜缎带。

**本地机制**（`core/TrailCorner.java:366-378`）：

```text
leading  = animationLength × (1 - trailSize)
trailing = animationLength
rank ≥ 2 → leading
rank = 1 → (leading + trailing) / 2
rank = 0 → trailing
```

- 默认 `trailSize = 1.0`（`NeovideCursorSettings.java:1027`），所以 `leading = 0`。
- 以「基础」预设 `animationLength = 0.10s` 为例：rank0 = 100ms，rank1 = 50ms，rank2/3 = 0。
- 最慢的 rank0 与 rank1 相差 **50ms**，只有一个角明显滞后 → 更容易形成**斜缎带**。
- 「缎带」预设把 `animationLength` 拉到 0.28s，这个差异进一步放大。

**本地可调项（对应你说的「能改不拉伸」）**：

| 设置项 | 范围 / 默认 | 实际作用 |
|---|---|---|
| 拖尾密度（跨行缎带）`trailSize` | 0–1 / 默认 1.0 | **调到 0 → 前后缘等速，缎带消失**；调小则前缘也开始拖 |
| 跨行动画时长 `animationLength` | 基础 0.10s / 缎带 0.28s | 直接决定尾巴长度 |
| 斜度 `slopeTicks` | 0–100 / 基础 0 | **只影响单行倾斜**；0% = 纯矩形，但拉伸仍存在 |
| 拉伸强度 `stretchRatioK` | 1.0–8.0× / 默认 4.5× | **只影响单行后缘**；k=1.0× 时四角系数全为 1.0，单行完全不拉伸 |

要点：跨行缎带归「拖尾密度 + 跨行动画时长」管；「斜度」「拉伸强度」只管单行。想接近上游的跨行观感，优先把「拖尾密度」调到 0（或很小），必要时把「跨行动画时长」降到 0.10s 附近。

**能否精确调成上游那样？不能，只能近似。** 本地跨行只有一个自由参数 `trailSize`，四角时长被公式锁成三档：

| `trailSize` | rank0 / rank1 / rank2 / rank3 | 观感 |
|---|---|---|
| 1.0（默认） | 1.0 / 0.5 / 0 / 0 | 最斜缎带 |
| 0.5 | 1.0 / 0.75 / 0.5 / 0.5 | 中等 |
| 0.2 | 1.0 / 0.9 / 0.8 / 0.8 | 尾部近乎平行，**最接近上游** |
| 0 | 1.0 / 1.0 / 1.0 / 1.0 | 缎带消失，整体平移 |

上游的目标曲线是 `{1.0, 0.9, 0.5, 0.3}` 再叠加「对齐度 > 0.5 → 0ms 硬吸附」，竖直跨行时等效为「两个后角 100/90ms + 两个前角瞬时到位」。本地公式里 rank1 恒等于 `(leading + trailing) / 2`，无法同时做到 rank1 = 0.9A 且 rank2/3 = 0 —— 所以**参数空间里不存在精确解**。

另外，本地设置里的「前缘贴合」开关目前只保存状态、没有接入渲染：

- `SettingsField.java:854-861` 注释写明「只进 State、不进 CONFIG」；
- `TrailCorner.java` 明确记录「hardSnap 不做」，三版模型实机验收后已回退；
- 中文资源包对该项的说明是「待实现：此功能尚未实现，勾选不会改变观感」。

因此，`trailSize ≈ 0.2` + `animationLength ≈ 0.10s` 是当前参数能到的最接近值：尾部两角接近平行，但前缘仍以 0.8A 移动，不会像上游那样瞬间贴合。

#### （2）打字手感

| 维度 | 上游 | 本地 |
|---|---|---|
| 短跳判据 | 像素阈值 `shortMoveThreshold = 8` + `shortMoveMinChars = 2.5` | 光标 offset 差 `charsMoved ≤ typingMaxChars(3)`，与字体/全半角无关 |
| 短跳四角 | 直接乘 `trailFactors` | 保留 rank 梯度，另有 `typingSmoothness` 压平 |
| 退格残留速度 | 未单独处理 | 退格时 velocity 归零，消除第一帧「停顿一下」 |
| 硬吸附 | `useHardSnap = true`，前缘可能瞬时贴合 | 短跳明确**不做**硬吸附（`TrailCorner.java` 注释记录了实测否决） |

上游 `NeovideConfig` / `CaretAnimator` / `SpringTrail` 中没有 `typingSmoothness` 或等价的输入平滑字段。这与你「上游打字手感不如本地」的反馈一致。

#### （3）设置页预览

| 维度 | 上游 | 本地 |
|---|---|---|
| 预览实现 | 真 `EditorEx` + **生产** `CaretAnimator` | `JComponent` 自绘 + `TrailCorner[]` 独立积分 |
| 预览数量 | 1 个 | 3 个（inline / cross / glow） |
| 保真方式 | 结构上复用生产代码，天然所见即所得 | 再实现一套物理；靠探针做一致性校验 |
| 分组 / 控件 | 4 组、11 个滑块 | 10 组、更多滑块 + 预设 + 搜索 + 诊断 |

本地 `AnimationPreview.java` 的注释自己记录过：0.9.125 之前预览的辉光淡出与生产不同源，导致「预览里辉光永不熄灭、真实编辑器却会熄灭」。这说明本地预览的保真依赖持续对齐，而上游从结构上就不存在这个问题。

这可以解释你的观感：上游设置页「不错」主要不是功能更多，而是**预览可信 + 布局轻**；本地胜在可调项、预设、i18n 和诊断，但预览是再实现，复杂度更高。

---

## 七、观察结论与技术定性（非法律意见）

| 项目 | 结论 | 证据 |
|---|---|---|
| 设置页结构 | **不相似** | 上游 `FormBuilder` 4 组 / 319 行；本地 DSL 10 组 / 4552 行；最长连续块 6 行通用行；ratio 2.8%；相同注释 0 |
| 实时预览实现 | **不相似** | 上游真 `EditorEx` + 生产 `CaretAnimator`；本地 `extends JComponent` 自绘 + `TrailCorner[]` 独立积分；最长块 4 行 import；ratio 6.8%；相同注释 0；相同中文串 0 |
| 滑块实现 | **不相似** | 上游独立 `ValueSlider`（滑块 + 标签，`get(original)` 怪癖）；本地三列 `addSlider`（滑块 + 输入框 + 标签，无该怪癖）；最长块 2 行；ratio 0.4% |
| 设置页文案 | **部分相似（1 条）** | 仅「弹簧拉伸（Neovide 原版效果）」为完整标签逐字相同；本地 2026-10-19 引入，上游 2026-11-01 使用；其余 7 条为基点继承或子串 |
| `core/` 与同名 `ui/` 文件 | **相似（基座继承）** | 共同基点 `ee378a2`；同名文件 58%–100%；属 fork 同源，单独隔离，不计入新代码 |
| 是否存在大段逐行相同的新代码 | **不相似** | 三个关键模块最长连续相同块 6 / 4 / 2 行，均为通用行；无 ≥10 字相同注释 |
| 跨行 / 缎带实现 | **不相似** | 上游硬吸附 0ms + `trailFactors {1.0, 0.9, 0.5, 0.3}`，最慢两角只差 10ms；本地 `trailSize` 前/后缘三档，基础预设下 rank0 与 rank1 差 50ms，更易形成斜缎带 |
| 打字手感机制 | **不相似** | 上游像素阈值短跳、无 `typingSmoothness`；本地 offset 字符数短跳 + `typingSmoothness` + 退格速度归零 |
| 设置页预览 | **不相似（上游保真度更高）** | 上游真 `EditorEx` + 生产 `CaretAnimator`；本地自绘 `JComponent` + `TrailCorner[]`，3 个预览、功能更多但为再实现 |
| 是否存在同一 bug / 主观意图 | **无法判定** | 未做系统性行为测试；主观试用不足以判定 bug；本文不做动机判断 |

### 7.1 技术定性（仅就代码证据）

1. **代码层面：不相似。** 设置页、实时预览、滑块三个模块的最长连续相同块分别为 6 / 4 / 2 行，且都是 `}`、`@Override`、import 等通用行；逐字相同的注释 0 条；整体 ratio 2.8% / 6.8% / 0.4%。
2. **新代码没有大段逐行相同证据。** 上游新增 7 个文件与本地对应实现的数据流、类结构、控件组织均不同。
3. **文案层面：部分相似（1 条）。** 只有「弹簧拉伸（Neovide 原版效果）」是完整 UI 标签逐字相同；本地 2026-10-19 引入，上游 2026-11-01 使用，本地在前约 13 天。
4. **同名文件：相似，但属基座继承。** 14 个同名文件的相似度来自共同基点 `ee378a2` 之后的同源演化，不计入新代码判定。
5. **综合：就现有代码证据，不支持「上游整体照搬本地新代码」这一判断。** 能确认的相似点只有一条 UI 文案，且时间上本地在前；观感差异（打字手感、跨行缎带、设置页预览）分别由不同的代码机制解释，不是同一套实现换了参数。
6. **这不是法律定性。** 是否构成权利侵害，需要由具备资质的专业人士做独立比对与法律评估。

### 7.2 调查完成度

| 项 | 状态 | 说明 |
|---|---|---|
| 上游版本与时间取证 | 完成 | tag / commit / author date 可复现 |
| 本地版本与时间线 | 完成 | `v0.9.83` / `v0.9.126` / `HEAD` / 设置面板首次提交 |
| 文件清单对比 | 完成 | 14 同名 / 7 上游独有 / 23 本地独有 |
| 三个关键模块逐行对比 | 完成 | 最长连续块 6 / 4 / 2 行；相同注释 0 条 |
| 非必要相似六项 | 完成 | a–f 逐项结论 |
| 量化相似度 | 完成 | LCS、非平凡共享行、difflib ratio、整体 MD5 |
| 安装上游 0.6.0 | 完成 | PyCharm 2025.2；本地 0.9.126 已备份 |
| 主观试用 | 完成 | 用户反馈已记录（6.2 / 6.4） |
| 量化性能测量 | 未做 | 帧率、输入延迟、掉帧率 |
| 法律定性 | 不在范围 | 需具备资质的专业人士 |

调查范围：上游 `v0.6.0` 与本地 `HEAD` / `v0.9.126` 的代码文本、文件清单、时间线与主观试用；不包含完整历史的逐提交比对，也不包含量化性能测量。

**本次调查到此结束。**

---

## 八、免责

本文档是基于公开仓库与本地仓库的技术对比记录，不构成法律意见。
文中「相似 / 不相似」仅描述代码与文本的统计关系，不表示对创作过程、归属或权利状态的判断。
如涉及权利主张或争议处理，应由具备资质的专业人士进行独立的代码比对与法律评估。

---

## 附录：复现命令

```bash
# 1) 上游版本与时间
gh api repos/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/tags \
  --jq '.[] | "\(.name) \(.commit.sha)"'
gh api repos/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/commits/v0.6.0 \
  --jq '{sha, author_date: .commit.author.date, message: .commit.message}'

# 2) 拉取上游到仓库外
git clone --depth 1 --branch v0.6.0 \
  https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains.git \
  /d/deepseekwork/_upstream-compare

# 3) 本地版本
git rev-parse v0.9.83^{commit} v0.9.126^{commit} HEAD
git log -1 --format="%H %ai %s" v0.9.83^{commit}
git log -1 --format="%H %ai %s" v0.9.126^{commit}

# 4) 共同基点
git merge-base --is-ancestor v0.5.1 HEAD; echo $?
git merge-base v0.5.1 HEAD
git merge-base --is-ancestor ee378a20 origin/main; echo $?

# 5) 上游 v0.5.1..v0.6.0 文件变更
gh api repos/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/compare/v0.5.1...v0.6.0 \
  --jq '.files[] | [.status, .changes, .filename] | @tsv'

# 6) 去注释归一化后比较（核心逻辑）
#    见临时目录 _upstream-compare/_compare.sh
bash /d/deepseekwork/_upstream-compare/_compare.sh
```

临时目录 `D:\deepseekwork\_upstream-compare` 位于仓库外，可在对比与实测结束后删除。
