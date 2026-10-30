# 参数归属地图（0.9.87 建立 · 2026-10-28 重构后重写）

> 由子代理核实 + 主会话验证。回答一个问题：**每个参数跟随预设吗？**
> 依据全部来自代码（`文件:行号`），不是印象。

---

## 〇、2026-10-28 的关键变更（先看这段，下面的表才是现状）

用户要求：「凡是**设备/功能开关**都不该进预设」。检查后发现三份清单互不一致，已按以下三条决策统一：

| 决策 | 内容 |
|---|---|
| 颜色 | **动画预设完全不管颜色**（`tailColor` / `shadowColor` / `rgbGradient` 移出动画预设） |
| 输入平滑 | `typingSmoothness` **彻底移出**（0.9.73 就说过不参与，但一直留在快照里） |
| 快照内容 | 只存**真正会生效**的字段；不再「存了不生效」 |

结果：`SettingsField.PRESET_FIELDS` 从 26 收缩到 **13**，与 `applyAnimPresetFields`
写入的 13 个**完全相等**，并且 `matchAnimPreset` 也补齐到 13 个。

**守门探针 `VFieldDomain`** 断言这三者同源（D-1/D-2/D-3/D-4），变异测试两次均能抓红。

---

## 一、为什么会有这个疑问

重构前，项目里有**三份清单**，数量对不上：

```
AnimPreset 的观感参数       14 个
SettingsField.PRESET_FIELDS  26 个（inPreset=true）   ← 2026-10-28 起是 13
applyAnimPresetFields 实写   13 个
```

**26 ≠ 14 曾是「两套机制」的设计结果**，但 26 里混着 13 个不生效的字段（9 个设备开关 +
3 个颜色 + 平滑），这正是「切自定义预设会改颜色 / 改平滑」的根因。现已统一为 13。

---

## 二、两套机制

| | 内置预设 | 自定义预设（副本） |
|---|---|---|
| 写入方法 | `applyAnimPresetFields`（Config:2286-2334） | `restoreSkipping`（SettingsField:594） |
| 数据来源 | `AnimPreset` 14 个常量 | `PRESET_FIELDS` 13 个字段的快照（2026-10-28 前是 26） |
| 入口 | Tools 菜单 / 面板下拉 / 恢复默认 | 新建 · 派生副本 · 重命名 |
| 覆盖面 | 只写观感 | 写全部 26 个，**跳过**环境开关 |

`PRESET_FIELDS` 的唯一生产使用点是 `SettingsField.java:512`（`snapshot()` 内部）。
`restore()` 在生产代码里**零调用**，一律走 `restoreSkipping()`。

**所以 26 ≠ 14 是设计结果。**

---

## 三、完整的字段归属表

图例：`[P]` 跟随预设（内置与自定义都快照/写入） · `[L]` 设备/功能开关，**两条路径都不动**
· `[C]` 颜色预设域（动画预设不碰） · `[G]` 全局项（永不随预设）

> **`[S]` 已经不存在了。** 它原来的含义是「内置预设不动、但自定义快照会写」——
> 那种「两条路径不一致」的状态正是 2026-10-28 统一掉的。现在
> **快照里出现的字段 == 内置预设会写的字段 == 匹配判据比较的字段**，三者同源。

### 总开关组（buildMasterGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 启用拖尾光标（总开关） | `enabled` | [L] |
| 隐藏原生光标 | `hideNativeCaret` | [L] |
| 常驻刷新（不停手降频） | `alwaysHighFps` | [L] |
| 目标帧率 | `targetFps` | [L]（0.9.87 改） |
| 多光标时关闭辉光 | `multiCursorNoGlow` | [L]（0.9.87 改） |

### 单行输入输出组（buildInlineGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 短动画时长 | `shortAnimationLength` | [P] |
| 输入平滑 | `typingSmoothness` | [G] 全局项（0.9.73 起不参与预设；2026-10-28 才真正移出快照） |
| 拉伸强度 | `stretchRatioK` | [P] |
| 斜度（单行倾斜） | `slopeTicks` | [P] —— 三个内置预设的常量是 **基础 0 / 缎带 0 / 纯平滑输入 100**，所以**切到「纯平滑输入」会把斜度改成 100**（设计如此，不是数据丢失，见 [BUG-LOG BUG-013](BUG-LOG.md)） |

### 多行缎带组（buildCrossGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 拖尾密度 | `trailSize` | [P] |
| 跨行动画时长 | `animationLength` | [P] |

### 颜色组（buildColorGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 拖尾颜色 | `tailColor` | [C] 颜色预设域（动画预设不碰） |
| 拖尾不透明度 | `tailOpacity` | [P] |

### 光标组（buildCursorGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 光标宽度 | `cursorWidthPx` | [L]（0.9.87 改） |
| 光标渐变（上下深浅） | `rgbGradient` | [C] 颜色预设域（动画预设不碰） |
| 光标高度（% 行高） | `cursorHeightRatio` | [L]（0.9.87 改） |

### 辉光组（buildGlowGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 启用辉光 | `useShadow` | [P] |
| 辉光颜色 | `shadowColor` | [C] 颜色预设域（动画预设不碰） |
| 辉光峰值不透明度 | `glowOpacity` | [P] |
| 辉光层数 | `glowLayers` | [P] |
| 辉光锐度 | `shadowBlurFactor` | [P] |

### 淡出组（buildFadeGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 辉光淡出 | `overlayFadeOut` | [P] |
| 淡出前保持时长 | `cursorDisappearDelay` | [P] |
| 淡出时长 | `cursorFadeOutDuration` | [P] |

### 实验性（折叠）

| 控件 | 字段 | 归属 |
|---|---|---|
| 拖尾渲染模式 | `trailMode` | [L] |

### 高级 / 诊断（折叠）

| 控件 | 字段 | 归属 |
|---|---|---|
| 性能诊断（跳过重绘） | `paintDiag` | **不是 State 字段，不持久化** |
| 诊断日志 | `diagLogging` | [L]（0.9.87 改） |

---

## 四、0.9.87 修的真缺陷

> ⚠️ **0.9.87 时的机制，现已变更**：那时用独立数组 `RUNTIME_SWITCH_KEYS`
> （恢复自定义预设时跳过的字段）标记，原来只有 **4 项**，
是「从 CursorPresetApplier 的硬写清单提成常量」时原样搬的，没按同一标准重审。

**后果**：切自定义预设时，5 个设备级字段被写回旧快照值。

| 字段 | 为什么该跳过 |
|---|---|
| `targetFps` | 跟屏幕刷新率绑定；且带白名单 setter，恢复时把历史值静默改成 125 |
| `cursorWidthPx` | State 注释明写「设备级设置，不进预设」 |
| `cursorHeightRatio` | 同上，与字形/字号相关 |
| `diagLogging` | 排查开关，长期开会撑大 idea.log |
| `multiCursorNoGlow` | State 注释明写「不随预设变化」 |

**判定标准**：这个字段改变的是「光标长什么样」，还是「插件怎么跑 / 在什么设备上跑」？
后者一律跳过。按此标准，`cursorWidthPx` / `cursorHeightRatio` 虽然影响观感，
但它们跟着**屏幕/字体**走而不是跟着**风格**走 —— 换机器该保持，所以也跳过。

清单从 4 项扩到 **9 项**。守门探针：`VSwitchKeysGap`。

> **【2026-10-27 后续】** 该数组已在 runtimeSwitch 重构中**删除**
> （commit `016046a`，merge `4bbbcfc`）。现在由 `SettingsField` 每个字段自身的
> `boolean runtimeSwitch` 标志表达，判定标准不变（仍是这 9 个字段），
> 但「清单与字段表分家维护」这个根因消失了。
> **`RUNTIME_SWITCH_KEYS` 这个符号已经不存在**，`grep` 找不到它是正常的。

---

## 五、机制重叠：已于 2026-10-28 解决

**原状**：`tailColor` / `shadowColor` / `rgbGradient` 三个颜色字段同时标着 `inPreset=true`，
所以自定义动画预设会连颜色一起恢复。实测症状 ——
「点『个人自定义』，光标颜色从用户的绿色变回该预设里存的粉色」。

**用户决定（2026-10-28）**：动画预设**完全不管颜色**。

**实现**：
- `hexColors` 工厂统一 `inPreset=false` → `tailColor` / `shadowColor` 不再进动画预设快照；
- `rgbGradient` 改用 `boolsGlobal` —— 它属于**颜色域**，但不是设备开关，
  用 `runtimeSwitch` 表达会让下一个人误读成「渐变是设备设置」。

**现在的行为（预期）**：
- 切任何动画预设（内置 / 自定义）都**不改颜色**；
- 颜色只由「颜色预设」路径与用户手选控制；
- 旧预设串里的颜色键会被**自动忽略**（不报错、也不再被应用）。

**代价**：自定义预设不再「连颜色一起存」。以后若真需要「整套风格（颜色+动画）」，
应当引入独立的『主题』概念，而不是把颜色塞回动画预设 —— 否则又会回到今天这个不一致。

---

## 六、输入平滑：行为与声明终于一致（2026-10-28）

`typingSmoothness` 在 `AnimPreset` 里有 `smoothTicks` 字段和访问器，
但 `applyAnimPresetFields` 不写它、`matchAnimPreset` 不比它。

**重构前的真实情况比文档更糟**：它虽被上面两条路径跳过，但
`inPreset=true` 且不是 `runtimeSwitch`，所以**自定义预设快照仍会写它、恢复时也会应用它** ——
即「切自定义预设会把平滑一起改掉」。本文档此前写「当前行为正确」，是错的。

**现在**：改用 `floatsGlobal` 工厂（`inPreset=false`），快照不再含它、恢复也不会碰它。

**仍留的技术债**：`AnimPreset.smoothTicks` 字段与访问器已无生产用途（纯死数据）。
删它会牵动 `AnimPreset` 构造器签名（14→13 参）+ 三个预设定义 + 探针，
收益低于风险，保留并记录。