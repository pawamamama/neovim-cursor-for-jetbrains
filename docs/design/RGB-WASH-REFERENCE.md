# dsh-claude-style 的「动态 RGB」实现解析

> 来源：https://github.com/Nwflower/dsh-claude-style
> 文件：`packages/client/src/shared/motion.css` + `theme/tokens.json`
> 用途：为光标插件的 RGB 动态效果提供参考（用户要求「不混色的动态 RGB」）

---

## 核心机制：七层径向渐变 + 色相旋转（不是调色相混色）

这个效果叫 **“wash”（色洗）**，注释里写着自己源自 transitions.dev 的
**“Pro gradient text”**（MIT 许可，见其 `docs/THIRD_PARTY_NOTICES.md`）。

关键设计：**七层独立的径向渐变叠加，然后整体做 `hue-rotate(-360deg)`**。
这**不是**「把几层颜色混在一起」——每层是各自独立的半透明径向渐变，
通过 `background-clip: text` 只作用在字形上。所以视觉上是**多个色团在漂移**，
而不是均匀混成一个色。

## 七层颜色（按叠加顺序，从底层到顶层）

| # | 颜色 | 不透明度 | 说明 |
|---|---|---|---|
| 1 | `rgba(0, 110, 245, 1)` | 0.88 | 蓝 |
| 2 | `rgba(0, 110, 245, 1)` | 0.50 | 蓝（另一位置） |
| 3 | `rgba(204, 0, 167, 1)` | 0.40 | 品红 |
| 4 | `rgba(204, 0, 167, 1)` | 1.00 | 品红（另一位置） |
| 5 | `rgba(255, 173, 85, 1)` | 1.00 | 橙 |
| 6 | `rgba(0, 204, 68, 1)` | 1.00 | 绿（有 4 段渐变：绿 → 青绿 → 青 → 透明） |
| 7 | `rgba(0, 170, 204, 1)` | 1.00 | 青 |

第 6 层最特殊，是**四段渐变**：
```
rgba(0,204,68,1)     offset 0
rgba(0,196,102,0.75) offset 0.25
rgba(0,187,136,0.5)  offset 0.5
rgba(0,170,204,0)    offset 1
```
即「绿 → 青绿 → 青 → 透明」，是唯一自带内部过渡的层。

## 两个动画（叠加播放）

```css
animation:
  dsh-claude-wash-hue   var(--dsh-claude-period-wash-hue)   linear      infinite,
  dsh-claude-wash-drift var(--dsh-claude-period-wash-drift) ease-in-out infinite;
```

### 1. 色相旋转（4000ms，linear）
```css
@keyframes dsh-claude-wash-hue {
  from { filter: hue-rotate(0deg)    saturate(1.3); }
  to   { filter: hue-rotate(-360deg) saturate(1.3); }
}
```
负方向旋转一整圈。`saturate(1.3)` 提升饱和度让颜色更鲜艳。

### 2. 位置漂移（5000ms，ease-in-out）
四段关键帧，每层渐变的位置按八个坐标循环移动：
```css
@keyframes dsh-claude-wash-drift {
  0%, 100% { background-position: 100% 0%, 0% 50%, 100% 50%, 0% 100%, 0% 0%, 50% 0%, 50% 100%, 0 0; }
  25%      { background-position: 100% 100%, 50% 0%, 50% 100%, 0% 0%, 100% 0%, 100% 50%, 0% 50%, 0 0; }
  50%      { background-position: 0% 100%, 100% 50%, 0% 50%, 100% 0%, 100% 100%, 50% 100%, 50% 0%, 0 0; }
  75%      { background-position: 0% 0%, 50% 100%, 50% 0%, 100% 100%, 0% 100%, 0% 50%, 100% 50%, 0 0; }
}
```
注意每行末尾都有第 8 项 `0 0` —— 那是底层的 `linear-gradient` 兜底色，
对应 `wash-sizes` 里的 `100% 100%`（前七层都是 `180% 180%`）。

## 尺寸

```css
--dsh-claude-wash-sizes:
  180% 180%, 180% 180%, 180% 180%, 180% 180%,
  180% 180%, 180% 180%, 180% 180%, 100% 100%;
```
七层都用 **180% × 180%**（大于容器，留出漂移余量），
底层兜底渐变用 100% × 100%。

## 关键技术细节

### 只作用在字形上
```css
-webkit-background-clip: text;
background-clip: text;
color: transparent;
-webkit-text-fill-color: transparent;
```
渐变被裁剪到文字形状内，文字本身透明 —— 所以看到的是「颜色在字里流动」。

### 径向渐变的 transform 矩阵
每层用一个 SVG `radialGradient` 配 `gradientTransform` 矩阵，
让色团变成**拉长的椭圆**而不是正圆（`matrix(...)` 里带旋转和缩放）。

例如第 1 层：`matrix(0.34726 -1.3118e-7 8.6482e-7 0.89585 33.5 21)`
—— 基本是纯缩放 + 平移到中心 (33.5, 21)。

第 3 层带旋转：`matrix(1.05 -0.8 0.46018 3.6184 33.5 21)`。

### 减少动画的降级
```css
body[...][data-dsh-claude-motion="reduced"] .dsh-claude-wash::before {
  animation-play-state: paused;
}
```
直接**暂停在第一帧**，而不是移除效果 —— 注释解释：「保持在那里是暂停，不是重写」。

## 与「混色」的区别（回答用户的关键点）

| | 混色（用户不要的） | 这个 wash（用户要的） |
|---|---|---|
| 做法 | 两层颜色叠加/插值 → 均匀的中间色 | 七层独立径向渐变各自漂移 |
| 结果 | 整体一个混合色，随时间变 | **多个色团同时在动，能看到各自的边界** |
| 色相 | 固定或线性插值 | 整体 `hue-rotate(-360deg)` 循环 |

**所以「不混色」的实现方式不是「避免混色」，而是「让每一层保持独立、可辨认」。**

## 迁移到光标动画的可行性（初步）

我们的是 `Graphics2D` 绘制的细长矩形（2×40px），不是文字。可以直接复用：

1. **七层 RadialGradientPaint 叠加**：每层一个色团，各自位置随时间移动
2. **色相旋转**：`Color.RGBtoHSB` 转 HSB → 色相减 `(t/4000)*360` → 转回 RGB
   （Java 没有 CSS 的 `hue-rotate`，要自己算）
3. **不混色**：关键是**逐层独立绘制**（`SrcOver` 叠加），而不是先算一个平均色

**代价**：七层 × 8 光标 = 每帧 56 次径向渐变填充。这是**昂贵**的 ——
之前的实测数据：8 光标 8 层同心外扩 fill 已达 1603µs（占 8ms 预算 20%）。
径向渐变比纯色 fill 更贵。**真要做必须实测成本，不能假设。**

## 待查（如果真要做）

- [ ] 七层 RadialGradientPaint 在 2×40px 细长矩形上的实际效果（可能看不出来）
- [ ] 性能：8 光标 × 7 层径向渐变的每帧耗时
- [ ] `hue-rotate` 的 Java 等价实现精度（HSB 往返是否掉色）
- [ ] 是否该只在「光标移动时」启用（静止时不画，省成本）