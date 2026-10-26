# 参数归属地图（0.9.87）

> 由子代理核实 + 主会话验证。回答一个问题：**每个参数跟随预设吗？**
> 依据全部来自代码（`文件:行号`），不是印象。

---

## 一、为什么会有这个疑问

项目里有**三份清单**，数量对不上：

```
AnimPreset 的观感参数       14 个
SettingsField.PRESET_FIELDS  26 个（inPreset=true）
applyAnimPresetFields 实写   13 个
```

**结论：不是 bug，是两套机制。** 但确实藏着一个真缺陷（见第四节）。

---

## 二、两套机制

| | 内置预设 | 自定义预设（副本） |
|---|---|---|
| 写入方法 | `applyAnimPresetFields`（Config:2286-2334） | `restoreSkipping`（SettingsField:594） |
| 数据来源 | `AnimPreset` 14 个常量 | `PRESET_FIELDS` 26 个字段的快照 |
| 入口 | Tools 菜单 / 面板下拉 / 恢复默认 | 新建 · 派生副本 · 重命名 |
| 覆盖面 | 只写观感 | 写全部 26 个，**跳过**环境开关 |

`PRESET_FIELDS` 的唯一生产使用点是 `SettingsField.java:512`（`snapshot()` 内部）。
`restore()` 在生产代码里**零调用**，一律走 `restoreSkipping()`。

**所以 26 ≠ 14 是设计结果。**

---

## 三、完整的字段归属表

图例：`[P]` 跟随内置预设 · `[S]` 持久化、内置不动但**自定义快照会写** ·
`[L]` 内置与自定义都**不动**（在跳过清单） · `[C]` 颜色预设域

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
| 输入平滑 | `typingSmoothness` | [S]（**名义在预设但实际不写**） |
| 拉伸强度 | `stretchRatioK` | [P] |
| 斜度（单行倾斜） | `slopeTicks` | [P] |

### 多行缎带组（buildCrossGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 拖尾密度 | `trailSize` | [P] |
| 跨行动画时长 | `animationLength` | [P] |

### 颜色组（buildColorGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 拖尾颜色 | `tailColor` | [C][S] |
| 拖尾不透明度 | `tailOpacity` | [P] |

### 光标组（buildCursorGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 光标宽度 | `cursorWidthPx` | [L]（0.9.87 改） |
| 光标渐变（上下深浅） | `rgbGradient` | [C][S] |
| 光标高度（% 行高） | `cursorHeightRatio` | [L]（0.9.87 改） |

### 辉光组（buildGlowGroup）

| 控件 | 字段 | 归属 |
|---|---|---|
| 启用辉光 | `useShadow` | [P] |
| 辉光颜色 | `shadowColor` | [C][S] |
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

`RUNTIME_SWITCH_KEYS`（恢复自定义预设时跳过的字段）原来只有 **4 项**，
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

---

## 五、已知的机制重叠（暂不改）

`tailColor` / `shadowColor` / `rgbGradient` 三个颜色字段：

- 由**颜色预设**路径写（`Config:1770-1773`、`:2097-2101`、`CursorPresetApplier:150-162`）
- 同时标着 `inPreset=true`，所以**自定义动画预设也会写它们**

**后果**：`CursorPresetApplier.java:18-20` 宣称的「动画与颜色正交」
只在**内置预设**这条路径上成立；自定义动画预设会连颜色一起恢复。

**为什么不改**：改了会破坏「自定义预设恢复全部观感」的预期 ——
用户派生副本的动机常常就是「这套颜色+动画我都想要」。
要改得先设计「颜色预设」与「动画预设」的优先级规则，是独立的一轮。

---

## 六、另一个已知不一致

`typingSmoothness` **名义上在 `AnimPreset` 里**（有 `smoothTicks` 字段和访问器），
但 `applyAnimPresetFields` 不写它、`matchAnimPreset` 不比它。

来源：0.9.73 用户要求「输入平滑不参与预设」，实现时只删了写入、没删字段。

**当前行为正确**（平滑确实是全局持久化的），只是字段是死数据。
清理它会牵动 `AnimPreset` 构造器签名（14→13 参）+ 三个预设定义 + 探针，
收益低于风险，记为技术债。