补充一轮**带 JFR 采样数据的实测报告**。结论是：插件本身的性能是正常的，之前 felt 的卡顿不应当由这个插件的绘制成本来解释。这些数据可能对 0.5.0 的架构取舍有参考价值。

## 测试环境

- IntelliJ IDEA 2026.1.3 (IU-261.25134.95)，Windows 11，32 核
- 插件版本：0.4.4 基线 + 本 PR 的 `lastNanos` 修复
- 主题 Material Oceanic，缩放 1.5x
- JFR 录制 1223 秒，1823 条 `jdk.ExecutionSample`

---

## 一、【否定】重绘面积不是瓶颈

`peakRepaintArea` 与 `fps` 完全无相关性。来自插件自身的每秒统计日志：

| peakRepaintArea | fps | maxGap |
|---|---|---|
| **1,063,867px** | **66.6** | 116ms |
| 85,387px | 101.7 | 33ms |
| 85,387px | 88.4 | 174ms |
| **3,551px** | **15.7** | **640ms** |
| 2,881px | 34.9 | 261ms |

面积相差 **300 倍**（106 万 vs 3551），帧率却是大面积更高。
所以「收紧 `maxTrailDistanceFactor` 以提升帧率」这条路，实测无效。

## 二、【否定】辉光层数是瓶颈

JFR 显示 `drawQuadGlow` 的 `g2.fill(path)` 占 EDT 采样的 **9.3%**（25/270），是全插件最大热点。调用链证实它完全在 CPU 上：

```
CaretTrailPanel.drawQuadGlow:272
  -> SunGraphics2D.fill
  -> PixelToParallelogramConverter.fill
  -> AAShapePipe.renderTiles          (抗锯齿)
  -> AlphaColorPipe.renderPathTile
  -> MaskFill.MaskFill -> jbr/bin/awt.dll   (alpha 混合)
```

于是把 `glowLayers` 从 12 降到 6（alpha 按 `A=1-(1-peak)^(1/N)` 反解，**峰值亮度不变**），实测 **fps 与卡顿感均无变化**。

补充：本机 `jdk.NativeLibrary` 里未加载 `d3d8.dll` / `d3d9.dll` / `dxgi.dll`，Java2D 走**纯 CPU** 路径（只有 UI 的 Skiko/Compose 部分走 GPU）。而 Java2D 的 GPU 管线本身也不加速 `fill(Path2D)`，所以这个问题无法靠硬件加速绕开。

## 三、【定位】停顿来自 EDT 被占用，且插件占比很小

JFR 全局采样分布：

| 线程 | 占比 |
|---|---|
| native thread | 28.3% |
| **AWT-EventQueue-0** | **14.8%** |
| DefaultDispatcher-worker | 8.4% |
| neovide-cursor-render | 2.2% |

EDT 内部栈顶 TOP 5：

| 方法 | 占 EDT |
|---|---|
| `sun.java2d.loops.ScaledBlit.Scale` | **12.6%** |
| `sun.java2d.loops.FillParallelogram` | 11.9% |
| `sun.java2d.loops.MaskFill` | 5.9% |
| `DrawGlyphListLCD` | 3.7% |
| `WComponentPeer.getLocationOnScreen` | 3.3% |

**含插件帧的 EDT 采样只占 10.7%（29/270）**，而 `ScaledBlit.Scale`（图像缩放，与插件无关）单项就 **12.6%**，比整个插件还高。

插件在**全局**采样中占 **3.6%**（65/1823）。

## 四、现象特征（可能是最有用的部分）

用户操作层面的差异非常稳定：

- **Enter（换行到下一行）：不卡**
- **删除到上一行（下方文本并入光标所在行）：卡**

两者在插件侧的区别在于光标位移方向：

- Enter：列坐标基本不变，只垂直下移一行
- 删除到上一行：从当前行开头跳到**上一行末尾**，水平大跨度 + 垂直上移

这会在 `refreshTarget()` 里触发一次 `editor.visualPositionToXY()`。JFR 中确实采到了这条链：

```
EditorCoordinateMapper.visualPositionToXY line: 420
  -> logicalToVisualPosition(LogicalPosition, boolean) line: 117/121
  -> visualToLogicalPosition(VisualPosition) line: 191/198
  -> visualPositionToXY line: 427
```

即**涉及 soft-wrap 的逻辑/视觉位置互换**。跳到「某行最末尾」时可能需要遍历该行的折行结构，而 Enter 到「行首、列不变」则不必。

**但需要说明**：`visualPositionToXY` 的采样大部分不是插件发起的（`refreshTarget` 自身只有 1 个采样），所以这条线索尚未确证是主因，仅作为现象关联报告。

## 五、由此得到的三个结论

1. **插件的绘制成本不是卡顿主因**（面积/层数两项实测均无效果，且插件在 EDT 上仅 10.7%）。
2. **0.5.0 把 `hideNativeCaret` 默认改为 `false` 的方向是对的** —— 让 IDE 原生绘制光标、插件只叠加拖尾，可以把这个插件从 EDT 竞争里摘出去。这与我之前反馈过的 #1 未合并是两件事，但都指向同一结论：**减少插件在 EDT 上的常驻开销比优化绘制算法更有效**。
3. **建议给 `refreshTarget()` 加一个可选的耗时埋点**（例如 `visualPositionToXY` 超过阈值才记日志），这样能直接定位第四节里的现象。如果确认它在中长行末尾的换算很贵，可能值得像 0.5.0 那样改为复用 IDE 自己的光标位置回调，而不是每帧主动换算。

## 六、附：本轮的代码改动

本 PR 分支已推两个提交：

- `lastNanos` 重置（见上一条评论，属于 #1 的补全）
- `glowLayers` 12→6（实测无效果，**如果不想收可以忽略这个提交**，我留作对照用）

如果只想要 `lastNanos` 这一条，我可以单独整理一个干净的分支。
