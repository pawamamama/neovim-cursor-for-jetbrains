# 多光标实现探查 + 后续开发文档

> 2026-10-23 ｜ 基于上游 v0.5.1 源码探查 ｜ **仅调研，未改动本项目**

---

## 一、上游多光标架构

### 核心数据结构

```java
// CaretAnimator.java
private static final int MAX_TRAILS = 64;                    // 上限
private final Map<Caret, SpringTrail> trails = new IdentityHashMap<>();  // Caret → 状态
private final List<SpringTrail> activeTrails = new ArrayList<>();        // 本帧活跃的
```

**为什么用 `IdentityHashMap`**：`Caret` 对象没有可靠的 `equals/hashCode`，
必须按**引用**判等，否则不同光标可能撞键。

**为什么 `MAX_TRAILS = 64`**：防止"全选整个文件"时创建上万条拖尾。
超出部分直接不画（不是不记录）。

### 每帧流程（`refreshTarget()`）

```
1. carets = editor.getCaretModel().getAllCarets()
2. count = min(carets.size(), MAX_TRAILS)
3. activeTrails.clear()
4. for i in 0..count:
     caret = carets[i]
     trail = trails.get(caret)
     if (trail == null) {                       // 新光标
         trail = new SpringTrail(config)
         source = previous != null ? previous : firstInitializedTrail()
         if (source != null) trail.startFrom(source)   // ★ 从相邻光标"起飞"
         trails.put(caret, trail)
     }
     activeTrails.add(trail)
     previous = trail
     
     // 快速路径：视觉位置 + 文档修改戳都没变 → 跳过坐标换算
     vp = caret.getVisualPosition()
     if (resync || !trail.isInitialized() || !trail.isAt(vp.line, vp.column, stamp)) {
         p = editor.visualPositionToXY(vp)
         trail.markAt(vp.line, vp.column, stamp)
         trail.setTarget(p.x + width/2, p.y + height/2, width, height)
     }
     
     if (caret == primary) { centerX = trail.centerX; centerY = trail.centerY; }
5. 回收：trails.values().removeIf(t -> !activeTrails.contains(t))
```

### 关键设计 1：新光标「从相邻光标起飞」

```java
SpringTrail source = previous != null ? previous : firstInitializedTrail();
trail.startFrom(source);
```

```java
// SpringTrail.startFrom
void startFrom(SpringTrail source) {
    if (!source.initialized) return;           // 源还没就位 → 交给首次 setTarget 直接吸附
    for (int i = 0; i < corners.length; i++) {
        corners[i].placeAt(source.corners[i].getCurrentX(), source.corners[i].getCurrentY());
    }
    width = source.width;
    height = source.height;
    initialized = true;
}
```

**效果**：按 Alt 向下拖出列选光标时，新光标会从上一行的形状**滑下来**，
而不是从 (0,0) 或目标点凭空出现。

对应原版 JS 为新 `.cursor` 元素指定 `jumpSource` 的做法。

### 关键设计 2：`activeTrails` 复用

每帧 `clear()` 再 `add()`，不新建 List —— 避免每帧分配。

### 关键设计 3：`Caret` 生命周期对齐

```java
@Override
public void caretAdded(@NotNull CaretEvent event) { wakeUp(); }

@Override
public void caretRemoved(@NotNull CaretEvent event) { wakeUp(); }
```

三个事件都只调 `wakeUp()`，不直接改状态 —— 因为「事件时序不可靠」
（README 踩坑 #5）：事件可能在坐标数据更新前触发。

**真正的状态更新统一在 `tick()` 里做。**

### SpringTrail 状态清单

```java
final TrailCorner[] corners;      // 4 个角
double centerX, centerY;          // 目标中心
double width, height;             // 光标尺寸
int lastLine, lastColumn;         // 快速路径缓存键（视觉位置）
long lastStamp;                   // ★ 文档修改戳
boolean jumped;                   // 目标刚变 → 下帧重分配 rank
boolean initialized;              // 首次定位直接吸附
double maxOffset;                 // 本帧最大偏移 → 驱动辉光
```

### 「文档修改戳」为什么是必需的

```java
private boolean isAt(int line, int column, long stamp) {
    return line == lastLine && column == lastColumn && stamp == lastStamp;
}
```

**只看 (行, 列) 不够**：第三方输入法上屏后，**列号不变但像素位置已变**
（拼音组合串与最终汉字宽度不同）→ 误判"没移动"→ 覆盖层停在半个字宽处。

加 `modificationStamp` 后，文档一变就强制重算。

**这是 v0.5.1 的修复之一（issue #6）。**

---

## 二、我们当前的实现对比

### 我们的多光标状况

```java
// NeovideCaretManager（我们的）
private static final Map<Editor, CaretAnimator> ANIMATORS = ...;   // 每编辑器一个 animator

// CaretAnimator（我们的）
private final TrailCorner[] corners = new TrailCorner[4];           // ★ 单光标，只有一个四角组
```

**我们只支持单光标。** 一个编辑器里一个 `CaretAnimator` 管一组四角。

### 要改哪里

| 改动 | 当前 | 目标 |
|---|---|---|
| 四角存储 | `TrailCorner[] corners` | `List<TrailCorner[]>` 或 `List<SpringTrail>` |
| 目标点 | `centerX/centerY`（单点） | 每光标一组 |
| 绘制 | `panel.updateCorners(x0..y3)` | `panel.updateQuads(List<float[]>)` |
| 快速路径 | `lastVisualLine/Column` | 每光标一套 + 文档修改戳 |
| 事件 | `caretPositionChanged` 唤醒 | + `caretAdded` / `caretRemoved` |

---

## 三、移植建议（分三步，每步可独立验证）

### 第 1 步：引入 SpringTrail（不改绘制）

```
把 TrailCorner[] + target 状态收进 SpringTrail 类
CaretAnimator 持有 Map<Caret, SpringTrail>
```

**此时仍然只画主光标**（`primary`），但数据结构已经支持多光标。

**验证**：单光标手感与原版逐位一致（parity 可守）。

### 第 2 步：绘制层支持多四边形

```java
// CaretTrailPanel 新增
void updateQuads(List<float[]> quads);   // 每项 [x0,y0,x1,y1,...x3,y3]
```

**验证**：Alt+拖拽列选，能看到多条拖尾。

### 第 3 步：新光标「从相邻起飞」

移植 `startFrom` + `firstInitializedTrail`。

**验证**：列选向下拖时，新光标从上一行滑下来而不是凭空出现。

---

## 四、关于「保留原生光标 + 硬吸附」的结论

### 你的质疑是对的：硬吸附与平滑冲突

**实测数据**（单行移动 8px，上游 tuned 参数）：

| 配置 | 第 1 帧拉伸宽度 | 前缘行为 |
|---|---|---|
| 开硬吸附 | **9.82 px** | 前缘**瞬吸**到目标 |
| 关硬吸附 | 3.34 px | 前缘走 49ms 弹簧 |

**硬吸附用「前缘瞬间到位」换「拉伸感」，代价是前缘完全没有平滑。**

**你的判断**：
> 「硬吸附我不同意，如果硬吸附了平滑怎么做，idea 原生平滑就不好用」

**这个判断成立**：
- 硬吸附 = 前缘时长 0 = 没有过渡 = 与"平滑"直接矛盾
- 如果依赖 IDEA 原生平滑（`isSmoothCaretMovement`），那我们的覆盖层无法控制它的缓动曲线

### 所以「保留原生光标」需要重新设计

**上游方案**：原生光标管跟手 + 前缘硬吸附贴合 → 一个光标
**问题**：前缘没有平滑

**可能的替代方案**：

| 方案 | 做法 | 风险 |
|---|---|---|
| A. 不要原生光标 | 我们现在的做法（隐藏原生，覆盖层全责） | 跟手受 EDT 影响 |
| B. 原生光标 + 软吸附 | 前缘时长设一个**小但非零**的值（如 20ms = 2.5 帧） | 需实测是否够贴 |
| C. 双轨 | 静止时显示原生，移动时隐藏原生改用覆盖层 | 切换瞬间可能闪烁 |

**B 是折中**：前缘 20ms 在 8ms 帧下 2.5 帧，肉眼几乎看不出滞后，
但保留了连续动画（不是瞬移）。**这个值需要你实测确认。**

---

## 五、光标宽度不一致的修复（已定位，未合入）

### 问题

```java
// 上游
double width = config.cursorWidth;   // 硬编码 2px
```

IDEA 原生光标宽度是 `EditorSettings.getLineCursorWidth()`，**实测约 5px**。

### 后果

| 模式 | 现象 |
|---|---|
| `hideNativeCaret = false`（保留原生） | 原生 5px + 覆盖层 2px 重叠 → **两条边都露出来，像两个光标** |
| `hideNativeCaret = true`（隐藏原生） | 覆盖层比用户惯用光标细 → **光标看起来变窄** |

### 修法

```java
double width = config.cursorWidth;
try {
    int real = editor.getSettings().getLineCursorWidth();
    if (real > 0) width = real;
} catch (Throwable ignored) { }
```

**你的要求**：「常驻绘制关闭 idea 的我们的不需要改，只需要开启 idea 时候对齐就可以了」

**对应做法**：
- `hideNativeCaret = true`（隐藏原生）→ 用 `config.cursorWidth`（用户可调）
- `hideNativeCaret = false`（保留原生）→ **读 `getLineCursorWidth()` 对齐**

这样两种模式都对。

---

## 六、后续开发优先级建议

| 优先级 | 任务 | 理由 |
|---|---|---|
| **P0** | 光标宽度对齐（保留原生时读真实值） | 你已实测到"像两个光标"，直接影响可用性 |
| **P1** | 多光标支持（三步中的第 1~2 步） | 上游有，我们缺，且是明确的功能需求 |
| **P2** | 保留原生光标的实验（软吸附方案 B） | 需要实测 20ms 前缘是否够贴 |
| **P3** | 文档修改戳加入快速路径 | IME 场景的正确性修复 |

---

## 七、未做的事

本轮**只做探查，未改动本项目代码**。

上游代码在 `D:\deepseekwork\upstream-research\`（独立目录，可随时删除）。
我们对上游的本地调参（`NeovideConfig` 改动）也只在该目录内。
