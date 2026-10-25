# 下一步做什么

> 状态：**v0.9.73 已发布**（https://github.com/pawamamama/neovim-cursor-for-jetbrains/releases/tag/v0.9.73）
> 待办文档上次更新停留在 0.9.64，2026-10-25 全面核对后重写。

---

## 已完成（不要再做一次）

| 项 | 完成版本 | 守门探针 |
|---|---|---|
| 预览窗失真（`charsMoved` 显式设置） | 0.9.65 | `VPreviewBranch` |
| 预览实时（尺寸 + RGB 真的进入绘制） | 0.9.67 | `VPreviewRealtime` |
| 面板文案三处缺失说明 | 0.9.72 | `VTextFacts` |
| GitHub 发布（v0.9.33 → v0.9.73） | 0.9.73 | — |
| 设置面板 S1/S3 重构 | 0.9.60 / 0.9.62 | 组件树逐位比对 |
| `SPECS[]` 下标改按 key 查 | 0.9.71 | `VAddSetting` |
| `ColorPanelBinding` 三向同步 | 0.9.71 | `VColorBinding` |
| `loadState` 漏拷 5 字段（重启即失效） | 0.9.72 | `VLoadState` |
| 自定义预设恢复未跳过环境开关 | 0.9.72 | `VLoadState` L-5 |
| 平滑移出预设 | 0.9.73 | `VSmoothGlobal` |
| 审查抓到的 4 条 bug（静默 catch / volatile / loop 无保护 / 无初始化守卫） | 0.9.73 | `VReviewFixes` |

---

## P2 — 8 光标长尾（p99 = 44ms）**只记录，暂不做**

用户决定：「p2 收益低 只记录」。

**已排除**：不是「平滑掉帧」—— 真实 p50 = 8.5ms（117fps）。

**已知证据**：
- 73 个慢帧里 14 个 >12ms，其中 **13 个是 `offsetMoved=true`**（拖尾正在动）
- 辉光绘制占其中约 1.6ms
- JFR：EDT 采样 873 个，**我们的代码只占 14 个（1.6%）**
- 剩余约 40ms 在 IntelliJ 自身的编辑器重绘
  （`IntervalTreeImpl` / `DocumentImpl.processRangeMarkers` 占 46/873）

**如果将来要做，从这三处入手**：
1. 8 光标时 IDEA 自己在做什么（为啥 `IntervalTreeImpl` 占比这么高）
2. `repaintRegion` 提交的区域是否过大 —— 8 个光标各提交一次，区域重叠会让
   RepaintManager 重复合成
3. 是否该把 8 个光标的 repaint 合并成一次（当前每个 animator 独立提交）

**为什么收益低**：辉光只占 1.6ms，砍它救不了长尾；真正的 40ms 在 IDEA 侧，
不受本插件控制。而且用户实测「关掉辉光后长尾明显减轻」，已有出口。

---

## 技术债（记录，不做）

### 1. 块选区 `charsMoved = -1`（**结论：不是缺陷**）
`updateBlockCharsMoved` 恒返回 -1，让 `TrailCorner.jump` 走归一化兜底判据。

**这是正确做法**：块选区的 offset 跨行时天然差几十上百个字符，用它算字符数
必然 > `typingMaxChars` → 连行内小步移动也被判成跨行 → 横向拉伸暴涨 2.55 倍。

**唯一瑕疵**：归一化判据 `|deltaX/width| <= 8` 依赖光标宽度 —— 宽度 1px 时
等价 8px 位移、5px 时等价 40px。影响极轻微，与预览窗那个「随字号漂移」同类。

### 2. `CursorDiag` 的 `[trail]` 前缀每帧 8 行
8 光标时日志涨到 328KB / 2 分钟。可以把逐光标快照降频到每 N 次采样一次。
不影响功能，只在长时间开诊断时撑大日志。

### 3. `AnimPreset.smoothTicks` 与访问器已成死字段
0.9.73 平滑移出预设后，该字段不再被任何生产代码读写，
只有 `VRegr2` / `VPreset9` 等探针在比对预设字段域时读它。
删它要改一串探针，保留成本更低。已在 javadoc 明确标注。

### 4. `origin` 仓库落后 197 个提交
`origin` = `yyyyolo7a79-sketch/neovim-cursor-for-jetbrains` 停在 v0.5.1，
而发布走的是 `fork` = `pawamamama/neovim-cursor-for-jetbrains`（v0.9.73）。
需确认 `origin` 是否废弃；若废弃可归档。

---

## P4 — 审查剩下的 high / medium（未修）

0.9.72~0.9.73 修了 2 个 blocker、1 个 high、4 条 bug。
审查报告里还有以下**未修**项，按风险排序：

| 级别 | 问题 | 位置 |
|---|---|---|
| high | `matchAnimPreset` 不比 `typingSmoothness`，但（0.9.73 前）`applyAnimPresetFields` 会写它 → 显示层撒谎 | `CursorPresetApplier:182` |
| medium | `persistQuietly` 在 EDT 上同步 `saveSettings`（可能卡 UI） | `NeovideCursorSettings:458` |
| medium | `CaretAnimator:1291-1313` 宽度决策被下一行**无条件覆盖**，注释与行为相反 | `CaretAnimator` |
| medium | `CaretTrailPanel.updateCorners` **零调用** → `polyX/polyY` 恒为 0，但探针仍在读它（在验证死数据） | `CaretTrailPanel:248` |
| medium | `SettingsField.decode` 的 HEX 分支不校验 → 手改坏的自定义预设能把拖尾变黑且返回 true | `SettingsField` |
| medium | `addQuad` 每光标每帧 `new double[4]` ×2，与「零分配热路径」的注释矛盾 | `CaretTrailPanel:285` |
| low | `CursorPresets:353-528` 是 180 行零调用的 0.9.8 override 死 API | `CursorPresets` |
| low | `CaretAnimator` 的 `glowStrengthFor` / `collectTrailTargets` / `__unusedLastVisualLine` 全是死代码 | `CaretAnimator` |
| low | `disposeUIResources` 不清 `inlinePreview` / `crossPreview` | `NeovideCursorConfigurable` |
| low | `apply()` 在 `baseline == null` 时 NPE | `NeovideCursorConfigurable` |

> 完整清单见用户 2026-10-25 收到的审查报告（2 blocker / 6 high / 9 medium / 12 low）。

**注意 high 那条已在 0.9.73 间接触发变化**：`applyAnimPresetFields` 不再写
`typingSmoothness`，所以「写了却不比对」的矛盾消失了。但 `matchAnimPreset`
本身仍不看 smooth，若将来又把 smooth 放回预设域会立刻复发。

---

## 建议顺序

```
P4 里那几条 medium（死代码 / 死数据探针 / 覆盖式赋值）  ← 低风险、纯清理
其余按需
```

P2 与 P3 按用户决定**只记录不做**。