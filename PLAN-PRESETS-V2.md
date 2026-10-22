# 预设重构方案（0.9.9 → 0.10.0）

> 用户实测反馈（2026-10-22）｜ 需求来源：截图 + 三条 bug + 预设精简

---

## 一、用户明确要求

### 1. 预设精简到 3 个

| # | 名称 | 说明 |
|---|---|---|
| 0 | **基础** | 原「原版粉色」改名。= NeovideConfig 默认值 |
| 1 | **缎带** | 不动（跨行拉长，anim 28） |
| 2 | **纯平滑输入** | 新增：**无辉光扩散**，对齐 VSCode 原版拖尾动效 |

**其余 4 个（Neovide 官方 / 社区·快 / 社区·平滑 / 柔和 / 高对比）全部删除。**

### 2. 「纯平滑输入」的技术定义

用户描述：
> 「原版的好像角是不一样的加速度，不像我们是规整矩形。如果不能改参数实现就直接关闭辉光和淡出就行了」

**两层实现**：
- **参数层（优先）**：四角加速度不同 —— 由 `stretchRatioK` + `trailFactors` 控制。
  VSCode 上游用单路径 + 每角独立弹簧，我们已经是四角独立弹簧，只是 `stretchRatioK=4.5` 让梯度偏平。
  提高 `stretchRatioK`（→ 6–8）+ 降低 `shadowBlurFactor`（→ 0.05，最锐）可以接近。
- **兜底层（若参数不够）**：`useShadow=false` + `overlayFadeOut=false`。

**必须实测**：先试参数组合，用户看效果；参数不够再关辉光。

### 3. 三条 bug（面板）

| # | 症状 | 根因（已确认） |
|---|---|---|
| B1 | 辉光/淡出关了，恢复预设没用 | `restoreAnimPreset()` 内置分支只写 6 个动画字段，不碰 `useShadow`/`overlayFadeOut` |
| B2 | 副本恢复无效 | 同上，自定义预设分支用 `SettingsField.restore` 恢复了值，但 `shadowOn`/`fadeOn` 联动属性没刷新 → 控件仍显示关闭态 |
| B3 | 切换预设不能让辉光回来（缎带没扩散=持久化了） | `onAnimPresetPicked()` 内置分支只写 6 个动画字段 |

**三条是同一个设计缺陷**：内置预设只定义 6 个字段，而用户期望「预设 = 整套观感」。

---

## 二、根因分析：为什么会出现这三条 bug

### 2.1 字段域不一致（核心矛盾）

```
内置预设（AnimPreset）        = 6 个字段（anim/short/trail/layers/blur/opacity）
自定义预设（snapshot）        = 19 个字段（全部参数）
用户心智                     = 预设 = 一整套观感
```

于是：
- 用户在「基础」上关了辉光 → `useShadow=false` 被持久化
- 切到「缎带」→ 只写 6 个动画字段，`useShadow` 保持 false
- 用户看到「缎带没有扩散」，以为是预设坏了

**这不是 bug，是模型设计错了。**

### 2.2 联动属性的刷新缺口

`shadowOn` / `fadeOn` 是 `GraphProperty<Boolean>`，控制「辉光相关控件是否置灰」。
它们只在 `graphReset()` 里同步。

`SettingsField.restore()` 改了 `pending.useShadow`，但没调 `graphReset()`
→ 控件值对、置灰状态错。

---

## 三、修复方案

### 3.1 统一字段域：内置预设也存全参数

**方案 A（推荐）**：`AnimPreset` 从 6 字段扩到 19 字段
- 优点：模型统一，切预设 = 换整套观感，符合用户心智
- 缺点：要改 `CursorPresets` + 探针断言大量改动

**方案 B**：保留 6 字段，但切换预设时**显式重置** `useShadow`/`overlayFadeOut` 为预设定义值
- 优点：改动小
- 缺点：仍不统一，用户改颜色后切预设会丢

**决策：走 A。** 因为「纯平滑输入」这个新预设**必须**能定义 `useShadow=false`，
而 6 字段模型表达不了这件事。

### 3.2 修复联动属性刷新

`SettingsField.restore()` 之后必须调 `graphReset(pending)`，
否则置灰状态与值不一致。

统一做法：在 `syncAllControlsFromPending()` 里加 `graphReset(pending)`
（它已经是「把 pending 灌进所有控件」的唯一入口）。

### 3.3 预设精简

`ANIM_PRESETS` 从 7 个降到 3 个：

```java
new AnimPreset("基础",       10, 11, 100, 8, 20, 55, /*useShadow*/true,  /*fade*/true)
new AnimPreset("缎带",       28, 11, 100, 8, 20, 55, /*useShadow*/true,  /*fade*/true)
new AnimPreset("纯平滑输入", 10, 11, 100, 8,  5, 30, /*useShadow*/false, /*fade*/false)
```

**「纯平滑输入」的参数待实测**：
- `blur=5`（最锐，外扩 0.05）
- `opacity=30`（低辉光）
- `useShadow=false`（关闭辉光扩散）
- `overlayFadeOut=false`（关闭淡出）

先用这组，然后让用户看效果再调。

---

## 四、必须补的断言（防止再犯）

| ID | 断言 | 抓什么问题 |
|---|---|---|
| **P-1** | 切到每个内置预设后，`useShadow`/`overlayFadeOut` = 该预设定义值 | B3 |
| **P-2** | 关闭辉光 → 切预设 → 辉光按预设恢复 | B3 具体场景 |
| **P-3** | `restoreAnimPreset()` 后 `graphReset` 已调，`shadowOn.get()` = `pending.useShadow` | B2 |
| **P-4** | 自定义预设快照恢复后，`shadowOn`/`fadeOn` 与 `pending` 一致 | B1/B2 |
| **P-5** | 3 个预设的名称与数量精确匹配（防止再加回来） | 需求漂移 |

---

## 五、执行顺序

1. 扩 `AnimPreset` 到 19 字段（关键是 `useShadow` / `overlayFadeOut`）
2. 精简到 3 个预设（含「纯平滑输入」）
3. 修 `onAnimPresetPicked` / `restoreAnimPreset` 写全字段
4. `syncAllControlsFromPending` 补 `graphReset`
5. 补 5 条断言 + 变异
6. 跑全量门禁
7. 出 jar（**不安装**，等用户验手感）

---

## 六、风险

| 风险 | 缓解 |
|---|---|
| 预设从 7 改 3 会**破坏已有用户的 `animPresetBase` 下标** | 持久化里存的是下标 0–6，改成 0–2 后旧值要 clamp |
| 「纯平滑输入」参数需实测 | 先给一组，用户看了再调，不猜 |
| 扩到 19 字段会动大量探针 | 先跑一次全量看红多少，逐个改 |
