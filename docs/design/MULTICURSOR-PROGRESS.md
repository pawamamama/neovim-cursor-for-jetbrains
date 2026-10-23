# 多光标实现进度

> 2026-10-23 ｜ 状态：**步骤 1、2 已完成，步骤 3 未开始**

---

## 一、总体设计（三步，每步可独立验证）

| 步 | 内容 | 状态 | 可见行为变化 |
|---|---|---|---|
| 0 | 调研 + 判断上游是否最优 | ✅ 完成 | 无 |
| **1** | 引入 `SpringTrail` 数据结构 | ⚠️ **部分完成**（见下） | 无 |
| **2** | 面板支持 N 个四边形 | ✅ **完成** `a0d83f6` | **无**（animator 仍只提交 1 个） |
| **3** | 多光标事件 + 起飞 + charsMoved 每光标 | ❌ 未开始 | 有 |

---

## 二、步骤 2 已完成的内容

### 面板改造（`CaretTrailPanel`）

从「单个 `polyX/polyY` + 全局 `glowStrength`」改为
「步长式扁平数组 `[x0,y0,...,x3,y3,glow] × N`」：

```java
private static final int QUAD_STRIDE = 9;   // 8 坐标 + 1 辉光
private double[] quads = new double[QUAD_STRIDE * 4];
private int quadCount = 0;

public void beginQuads();
public void addQuad(double[] xs, double[] ys, float glow);
public int quadCount();
public double[] quadForProbe(int i);   // 测试用
```

**为什么用扁平数组**：零对象分配。32 个四边形若每帧新建对象，
GC 压力会直接反映到 EDT 掉帧。

### 向后兼容

`updateCorners(x0..y3)` 保留，内部转调 `beginQuads() + addQuad()`。
`cornersForProbe()` 不变。**既有探针一条都没改。**

### 一处必须改的内部逻辑

`drawQuadGlow` 原来读全局 `glowStrength`。多光标时这会让
**已静止的光标也发光**（因为另一个光标还在动）。
已改为逐四边形传入 `quadGlow`。

### 验证

- 门禁 12 段全绿 / parity ALL OK / D0、D0b exit=0
- 新增 `VQuad.java`（11 条断言）全过
- **变异测试 3/3 全部检出**：
  - 去掉辉光钳制 → 检出
  - `addQuad` 永远写第 0 个 → 检出
  - 坐标取整（破坏亚像素）→ 检出

---

## 三、步骤 1 的实际状态（部分完成）

### 已完成

- 面板数据结构支持 N 个
- `CaretAnimator` 的绘制入口已改为「逐四边形循环」（`quadCount == 1` 时等价旧路径）

### 未完成

**`SpringTrail` 类还没建，`TrailCorner[] corners` 仍是单光标内联结构。**

这是刻意停在安全点的原因：**步骤 1 一旦动 `CaretAnimator` 的字段结构，
就必须同时处理下面四件事，不能停在中途**——

---

## 四、步骤 3 开工前必须处理的问题清单

### 问题 1：Caret 会被平台自动合并（官方文档确认）

> 两个 caret 落到同一视觉位置时，平台**合并成一个**，选区也合并。
> 选区重叠时同样只保留一个。

**后果**：`Map<Caret, SpringTrail>` 里必然残留「已死」的 Caret 对象。

**上游的做法有问题**：

```java
if (trails.size() > activeTrails.size()) {          // ← 靠数量判断
    trails.values().removeIf(t -> !activeTrails.contains(t));
}
```

**合并时一增一减，数量可能不变** → 清理不触发 → 死条目滞留。

**我们的做法**：给 `SpringTrail` 加 `lastSeenFrame`，每帧遍历时打标，
回收时按帧号无条件清理。**不依赖数量比较。**

### 问题 2：`charsMoved` 是单光标语义

```java
charactersMoved = Math.abs(caretOffset - lastCaretOffset);   // 当前实现
```

多光标时每个 `Caret` 有独立 offset。**必须改成每光标独立记录**，
否则光标 A 的移动量会被光标 B 的读数污染 —— 这会让「按字符判断」这个
我们最重要的差异点直接失效。

**注意**：这是**上游没有的能力**（上游用像素反推），
所以移植时不能照抄，必须自己扩展。

### 问题 3：`startFrom` 该从哪个光标起飞

上游：

```java
SpringTrail source = previous != null ? previous : firstInitializedTrail();
```

`previous` 是**遍历顺序的前一个**，不是**空间最近的**。

- Alt+向下拖列选：carets 按 offset 排序，碰巧正确
- **Alt+点击散点选**：新光标会从远处光标复制形状，视觉上"从别处飞来"

**我们的做法**：按**像素距离最近**选源。

### 问题 4：`hideNativeCaret=true` 时每个光标都要完整绘制

**这是用户特别强调的**：

> 我们现在用的是关闭 idea 光标，所以多光标的时候也应该复制单光标的状态

含义：原生光标被全局隐藏后，**每个 caret 都必须由我们补画**，
否则除了主光标外全部不可见。

**当前实现的隐患**：`staticCaret` 分支（控制台）已经在做「补画静止光标」，
但它只画`centerX/centerY` 一个位置。多光标时必须循环所有 caret。

### 问题 5：重绘区域要取并集

现在是单个包围盒。多光标时要合并所有四边形的包围盒 ——
但**不能简单 union**（README 踩坑 #7：重绘区域要分别提交，不能 union）。

正确做法：每个四边形各自 `repaint(x,y,w,h)`，让 RepaintManager 自己合并。

### 问题 6：辉光层数应随光标数降级

实测 8 层辉光 = 130μs/帧/光标。10 个光标 = **1.3ms/帧**，占 8ms 预算的 16%。

**建议**：光标数 > 4 时自动降到 4 层。

---

## 五、步骤 3 的实施顺序（建议）

**必须按这个顺序，每步跑门禁：**

1. **建 `SpringTrail` 类**，含 `lastSeenFrame` 字段（为问题 1 准备）
   - 验证：新建类不影响任何现有行为，直接编译 + 门禁
2. **`CaretAnimator` 字段替换**：`TrailCorner[] corners` → `Map<Caret, SpringTrail>`
   - **同时**实现帧号标记-清除（问题 1）
   - **同时**改 `charsMoved` 为每光标（问题 2）
   - 验证：parity 逐位一致 + VSmooth + VDefect 全过
3. **绘制提交改为循环所有光标**（问题 4）
   - 验证：新探针断言「N 个光标 → N 个四边形」
4. **`caretAdded/Removed` 事件 + 最近起飞**（问题 3）
5. **重绘区域分别提交**（问题 5）
6. **辉光层数降级**（问题 6）

---

## 六、当前代码状态（可安全停在此处）

```
CaretAnimator:
  TrailCorner[] corners          ← 仍是单光标，未动
  panel.updateCorners(...)       ← 仍只提交 1 个四边形

CaretTrailPanel:
  beginQuads() / addQuad()       ← 已支持 N 个
  quadCount == 1                 ← 单光标时等价旧路径
```

**结论：功能上完全是单光标行为，但绘制层已具备多光标能力。**
门禁 12 段全绿，parity 逐位一致。

---

## 七、开工时的一句话指令

```
读 docs/design/MULTICURSOR-PROGRESS.md，继续多光标步骤 3。
严格按「五、实施顺序」推进，每一步都要：先写探针 → 再改代码 → 跑门禁 → 变异测试验证探针。
特别注意问题 1（Caret 自动合并）和问题 2（charsMoved 每光标）。
不要照抄上游的 trails.size() > activeTrails.size() 清理方式。
```


---

## 八、代码审查记录（2026-10-23）

对面板改造做逐行审查，**发现并修复了 2 个真实缺陷**。记在这里，避免重犯。

### 缺陷 1（严重）：辉光画在错误位置

```java
// 错：drawQuadGlow 的外层循环仍读 polyX/polyY
double x = polyX[j] + dirX[j] * radius;
```

`polyX/polyY` 只保存「最后一次 `updateCorners` 写入的那个四边形」。
多光标时所有光晕会重叠在同一个位置。

**为什么容易漏**：我改了 `paintQuad` 传 `quadX/quadY`，但 `drawQuadGlow`
内部的**两层循环中只有一层**用了它 —— 外层（实际绘制路径）还是旧的。

**教训**：改「坐标来源」时必须搜全文，不能只改第一处。

### 缺陷 2（中等）：`clearCorners` 清不干净

```java
public void clearCorners() {
    hasQuad = false;      // ← 只清了这个
    ghosts.clear();
}
```

但绘制循环看的是 `quadCount`，不是 `hasQuad`。
于是「光标不可见时清空」之后，下一帧仍会把旧四边形画出来。

**教训**：引入新状态字段（`quadCount`）后，所有「清空」路径都要一并更新。

### 新增的防回归断言

| 断言 | 防的是 |
|---|---|
| Q-12 | `clearCorners` 必须清 `quadCount` |
| Q-13 | 每个四边形的辉光坐标必须是它自己的 |

**变异测试 3/3 全部检出**，证明这两条真的能抓到缺陷：
- 回退 `clearCorners` → Q-12 检出
- 辉光改回 `polyX` → Q-13 检出
- `glowCoords` 走错坐标源 → Q-13 检出

### Q-13 的判据迭代了三次（探针自身错误）

| 尝试 | 结果 | 原因 |
|---|---|---|
| 方向向量首分量不同 | ❌ 误报 | 矩形四角外法线恒为轴向 (±1,0)，与尺寸无关 |
| 归一化垂直度不同 | ❌ 误报 | 同上 |
| **直接比对坐标** | ✅ 正确 | 错用 polyX 时所有四边形返回同一坐标，一测即知 |

**另有一次**：拿 `xs/ys` 当期望值，但实际写入的是 `wideX/tallX` —— 也是探针错误。

**教训：探针报 FAIL 时，先怀疑探针。** 这轮三次 FAIL 里，两次是探针错，一次是产品错。
