# 多光标实现调研：上游方案是否最优

> 2026-10-23 ｜ 基于上游 v0.5.1 源码逐行阅读 + 本项目现状评估

---

## 一、上游方案解剖

### 数据结构

```java
Map<Caret, SpringTrail> trails = new IdentityHashMap<>();   // Caret → 状态
List<SpringTrail> activeTrails = new ArrayList<>();          // 每帧复用
static final int MAX_TRAILS = 64;                            // 上限
```

### SpringTrail（167 行，每光标一份）

| 成员 | 作用 |
|---|---|
| `TrailCorner[] corners` | 四角弹簧 |
| `centerX/centerY/width/height` | 目标点与尺寸 |
| `lastLine/lastColumn/lastStamp` | 快速路径缓存键（**含文档修改戳**） |
| `jumped` | 目标变化标记，下帧重分配 rank |
| `initialized` | 首次定位直接吸附 |
| `maxOffset` | 驱动辉光强度 |

### 绘制层：扁平数组 + 步长

```java
private double[] quads = new double[QUAD_STRIDE * 4];   // QUAD_STRIDE = 9
// 布局：[x0,y0,x1,y1,x2,y2,x3,y3,glow] × N

public void addQuad(double[] xs, double[] ys, float glow) {
    int need = (quadCount + 1) * QUAD_STRIDE;
    if (need > quads.length) quads = Arrays.copyOf(quads, Math.max(need, quads.length * 2));
    ...
}
```

**优点**：零对象分配（数组按需扩容，稳定后不再分配）。

---

## 二、上游方案的四个问题

### 问题 1：`activeTrails.contains(t)` 是 O(n²)

```java
// 回收已消失的光标
if (trails.size() > activeTrails.size()) {
    trails.values().removeIf(t -> !activeTrails.contains(t));   // ← 线性查找
}
```

64 个光标时最坏 64×64 = 4096 次比较，**每帧**。
实际影响小（多光标一般 < 20 个），但是可避免的。

**更好做法**：把 `activeTrails` 换成 `IdentityHashMap` 的键集合，或用「标记-清除」：
给 SpringTrail 加 `lastSeenFrame` 字段，遍历时打标，回收时按帧号筛选。

### 问题 2：`startFrom(source)` 只从「上一个」复制，列选会错位

```java
SpringTrail source = previous != null ? previous : firstInitializedTrail();
```

`previous` 是**遍历顺序的前一个**，不一定是**空间上最近的**。

Alt+向下拖出列选时，carets 是按 offset 排序的，所以"上一个"确实在上一行 ——
这个场景碰巧对。但如果是 **Alt+点击散点选**（多个不连续位置），
新光标会从"offset 更小的任意光标"复制形状，可能出现形状从远处飞来。

**更好做法**：按**像素距离**找最近的光标：

```java
SpringTrail nearest = null;
double bestDist = Double.MAX_VALUE;
for (SpringTrail t : initializedTrails) {
    double d = Math.hypot(t.centerX - newCx, t.centerY - newCy);
    if (d < bestDist) { bestDist = d; nearest = t; }
}
```

### 问题 3：`IdentityHashMap` 不清理已移除的 `Caret` 对象

代码里确实有回收，但只在 `trails.size() > activeTrails.size()` 时触发。
如果 `Caret` 对象被平台重建（同一位置、新对象），旧条目会一直留着，
因为 `IdentityHashMap` 按引用判等 —— 旧对象永远"不在 activeTrails 里"，
下次清理时会被删掉。**所以最终是安全的，但有延迟。**

**风险**：如果 `activeTrails.size()` 恰好等于 `trails.size()`（新旧数量相同），
清理分支不触发，旧条目滞留到下一次数量变化。长时间编辑可能积累。

**更好做法**：无条件按帧号清理，不依赖数量比较。

### 问题 4：辉光强度是「每光标独立」

```java
quads[base + 8] = glow;   // 每个四边形自己的辉光
```

**这其实是优点**（每个光标独立发光），但要确认：当 10 个光标同时运动时，
10 × N 层辉光绘制的总开销。上游 `glowLayers = 12`，我们 8。

**按我们实测**：8 层 = 130μs/帧/光标。10 个光标 = 1.3ms/帧。
在 8ms 帧预算里占 16% —— 可接受，但**多光标场景应降低层数**。

---

## 三、我们的现状与改造难度

| 项 | 上游 | 我们 | 难度 |
|---|---|---|---|
| 光标状态 | `SpringTrail` 独立类 | `TrailCorner[] corners` 内联 | 中 |
| 面板绘制 | 扁平数组 ×N | 单个 `polyX/polyY` | **中** |
| 重绘区域 | 合并所有四边形 | 单个包围盒 | 中 |
| 快速路径 | 每光标 (line,col,stamp) | 单个 (line,col) | 中 |
| 事件 | `caretAdded/Removed` 唤醒 | 只有 `caretPositionChanged` | **低** |
| 字符判定 | 无（像素反推） | `charsMoved` | **需扩展** |

### 关键差异：我们的 `charsMoved` 是「单光标」语义

```java
// 我们现在
charsMoved = Math.abs(caretOffset - lastCaretOffset);
```

多光标时，每个 `Caret` 有独立 offset。**必须改成每光标独立记录**，
否则光标 A 的移动量会被光标 B 的读数污染。

---

## 四、结论：不照搬，取其骨架

### 采用上游的（架构正确）

| 项 | 理由 |
|---|---|
| `SpringTrail` 独立类 | 职责清晰，四角 + 目标点 + 缓存键内聚 |
| `Map<Caret, SpringTrail>` | 天然按光标索引 |
| 扁平数组绘制 | 零分配，正确 |
| `MAX_TRAILS = 64` | 防全选爆掉，必要 |
| `caretAdded/Removed` 只唤醒 | 事件时序不可靠（README #5） |
| 缓存键含**文档修改戳** | 修 IME 错位（issue #6） |

### 改进上游的（4 点）

| 问题 | 我们的做法 |
|---|---|
| `activeTrails.contains` O(n²) | 改帧号标记-清除 |
| `startFrom` 只从上一个 | 按**像素最近**选择源 |
| 清理依赖数量比较 | 无条件按帧号清理 |
| 多光标时辉光层数不减 | 光标数 > 4 时自动降到 4 层 |

### 保留我们自己的（不要被上游同化）

| 项 | 理由 |
|---|---|
| `charsMoved` 按字符判断 | 上游的像素反推在中文/大字号下误判（我们踩了 4 次坑） |
| `stretchRatioK` / `typingSmoothness` | 连续可调 vs 上游硬编码数组 |
| 无条件 `reset()` | 上游的 `>90ms` 守卫导致删除卡顿 |

---

## 五、实施计划（三步，每步可独立验证）

### 步骤 1：引入 SpringTrail，仍只画主光标

- 新建 `SpringTrail.java`（改自上游，含上述 4 点改进）
- `CaretAnimator` 内 `TrailCorner[] corners` → `Map<Caret, SpringTrail>`
- 绘制仍只提交 `primary` 那一份

**验证**：parity 逐位一致 + 全部现有探针通过 + 新增探针断言"单光标行为不变"

### 步骤 2：面板支持 N 个四边形

- `CaretTrailPanel` 加 `beginQuads()` / `addQuad(xs, ys, glow)`
- 保留旧 `updateCorners(...)` 作为单四边形快捷方式（向后兼容探针）
- 重绘区域改为**所有四边形的并集**

**验证**：新增探针断言"1 个四边形时与旧路径字节级一致"

### 步骤 3：事件 + 起飞

- 加 `caretAdded` / `caretRemoved` 只唤醒
- `startFrom` 按像素最近选择源
- `charsMoved` 改为每光标独立

**验证**：新增探针断言"新光标从最近的光标起飞"

---

## 六、风险

| 风险 | 应对 |
|---|---|
| `charsMoved` 改造可能影响单光标手感 | 步骤 1 后先跑 parity + VSmooth |
| 多光标时 EDT 压力 | 光标数 > 4 时辉光层数降级 |
| 缓存键改造可能引入 IME 回归 | 保留 `lastStamp`，加探针测 IME 场景 |
| 重绘区域合并可能变大 | 用并集但裁剪到面板，实测峰值面积 |
