# 性能调查：真实瓶颈在哪（附我犯的 5 个错）

> 2026-10-23 ｜ 数据来源：用户机器上 3 个 IDEA 日志的 2609 条真实 fps 记录
> **本文档替换 ARCHIVE-SMOOTH-CONCLUSION.md 里所有未经实测的性能结论**

---

## 一、先说结论

### 瓶颈不是绘制，是 EDT 调度

| 项 | 实测值 | 说明 |
|---|---|---|
| 绘制代码（8层辉光） | **130 微秒** | 理论上限 7000fps |
| 重绘面积 vs fps | **无关** | 面积大 30 倍，fps 反而略高 |
| 物理计算 | 0.03 微秒 | 可忽略 |
| **实际帧率中位** | **36.4 fps** | 目标 125fps，差 3.4 倍 |
| 屏幕刷新率 | **240Hz** | 4.17ms，远未饱和 |

**结论：GUI 完全够用，240Hz 屏幕完全够用。卡的不是画图。**

### 真正的机制：自适应调度是负反馈陷阱

`CaretAnimator.computeNextDelay()`：

```java
// 常驻刷新开着 → 永远 8ms
if (config.alwaysHighFps) return ACTIVE_DELAY_MS;

// 否则：跟随"实测帧间隔"
int adaptive = (int) Math.round(smoothedIntervalMs * 0.85);
return Math.max(ACTIVE_DELAY_MS, Math.min(adaptive, MAX_ADAPTIVE_DELAY_MS));
```

**陷阱**：
```
某次卡顿 → smoothedIntervalMs 变大 → 退避到 40ms
        → 出帧变少 → 但"测到的间隔"依然是大值
        → 继续退避 → 永远回不来
```

日志证据（delay=8 是目标档，delay=40 是退避上限）：

| delay | 样本数 | fps 中位 |
|---|---|---|
| **8ms（目标）** | 1885 | **36.4** |
| 40ms（退避上限） | 249 | **17.6** |

**即使目标设在 8ms，实测也只有 36.4fps（27.5ms/帧）。**

---

## 二、真实数据（2609 条样本的正确解析）

### 用正确字段重新统计

日志格式（旧版本）：
```
neovide-cursor: fps=77.2  peakRepaintArea=3484px  delay=14ms
```

**注意：没有 maxGap 字段。** 我先前报的"maxGap 中位 375ms"是**错误解析**的产物（见第四节第 4 条）。

### delay=8（活跃档）的 fps 分位数

| 分位 | fps | 每帧耗时 |
|---|---|---|
| P10 | 3.6 | 277.8 ms |
| P25 | 15.6 | 64.1 ms |
| **P50** | **36.4** | **27.5 ms** |
| P75 | 59.3 | 16.9 ms |
| P90 | 81.7 | 12.2 ms |
| **P95** | **93.3** | **10.7 ms** |

**只有 P95 接近 8ms 的目标。中位数慢了 3.4 倍。**

### 重绘面积与帧率无关（关键反证）

| 重绘面积 | 样本 | fps 均值 |
|---|---|---|
| <5k px（很小） | 958 | 52.3 |
| 5k-20k | 409 | 54.4 |
| 20k-60k | 262 | 53.7 |
| **60k-150k（很大）** | 135 | **67.1** |
| ≥150k | 36 | 60.9 |

**面积大 30 倍，fps 反而更高。** 这一条彻底否定了"绘制拖累帧率"。

---

## 三、IDEA 其实提供了独立绘制入口

### `AbstractPainter`（已验证存在）

位置：`intellij.platform.ide.jar` → `com.intellij.openapi.ui.AbstractPainter`

```java
public abstract class AbstractPainter implements Painter {
    public boolean needsRepaint();                       // 这一帧要不要重画
    public void setNeedsRepaint(boolean);                // 告诉 IDEA
    public void setNeedsRepaint(boolean, JComponent);
    protected void fireNeedsRepaint(JComponent);
    public abstract void executePaint(Component, Graphics2D);   // 自己画
}
```

**挂载方式**：`IdeGlassPaneImpl.addPainter(Component, Painter, Disposable)`

**意义**：这是 IDEA 自己的**按需增量重绘**机制。只重画指定区域，不走完整 Swing repaint 管线。

**但注意**：它仍在 EDT 上跑。**能改善"怎么画"，不能解决"EDT 被谁占了"。**

### 完全独立的方案（高风险）

`JWindow` 透明窗口 + `BufferStrategy`：真脱离 EDT。
**风险**：与 IDEA 多窗口体系冲突，可能引起界面撕裂/闪烁/焦点问题。**未验证。**

---

## 四、我这一轮犯的 5 个错（全部记录下来）

| # | 错误 | 真相 |
|---|---|---|
| 1 | **"98% 时间在 repaint"** | 从注释里的 105.6fps **反推**出来的，从未实测。实测绘制 130μs |
| 2 | **"105fps 是硬上限"** | 那是注释里的历史数字。实测中位 36.4fps，且分布平坦无上限特征 |
| 3 | **"降帧间隔能提帧率"** | 改成 5ms 后用户反馈"帧率下降"，已回退 |
| 4 | **"maxGap 中位 375ms"** | **正则错位解析**——那三个日志根本没有 maxGap 字段，我把别的数字错当成它 |
| 5 | **"前缘/后缘解耦可行"** | 违反 `f[0]≡1` 恒等式，几何必翻折（审查员证伪） |

**共同模式：先用推断/错误解析造一个"数据"，再基于它下结论。**

### 由此得出的硬规矩

1. **报数字前，先验证字段真实存在**（这次栽在正则错位）
2. **注释里的历史数字不能当现值**（105.6fps 是旧版本、旧场景）
3. **改物理前先验几何约束**（单调性/恒等式/极限）
4. **区分"我测的"和"我推的"**——文档里必须标注来源

---

## 五、待验证的假设（按优先级）

### H1：负反馈退避是主因（高置信）

`smoothedIntervalMs × 0.85` 的退避逻辑一旦被卡顿触发，就自我维持。
**验证方式**：强制 `computeNextDelay` 恒返回 8ms（不管测量值），看 fps 是否回升。
**风险**：低（改一个分支）。
**注意**：代码注释说这是"减少无效提交"的有意设计，不是 bug。需实测对比两种策略。

### H2：多 animator 抢 EDT（中置信）

日志显示 `animators=8`（8 个编辑器）。
`RenderScheduler.dispatchDue()` 给每个到期 animator **各投一次** `invokeLater`。
**验证方式**：只给可见编辑器注册 animator，看 fps 是否改善。
**风险**：低（不影响可见编辑器）。

### H3：EDT 被 IDE 其他任务占用（未知）

索引、GC、UI 更新等。
**验证方式**：需要运行时埋点统计 EDT 队列深度。
**目前无数据。**

---

## 六、下一步（需要用户配合）

**所有现有数据都来自 10-18/19 的旧版本。** 当前是 0.9.14，中间差了很多轮改动。

**要确定原因，必须在新版本上取一次数据。** 最小操作：

1. 打开 IDEA，连续打字 30 秒 + 连续删 30 秒
2. 关闭 IDEA
3. 我从 `idea.log` 读新数据

**注意**：新版本的日志格式可能已包含 `maxGap` 字段（源码里有，旧日志没有）。这样能拿到更准确的数据。

---

## 七、已排除的可能性（不必再查）

| 排除项 | 依据 |
|---|---|
| 绘制代码太慢 | 130 微秒，面积无关 |
| 屏幕刷新率不够 | 240Hz = 4.17ms |
| 物理计算开销 | 0.03 微秒 |
| 辉光层数太多 | 8 层 → 130μs；即使 16 层也只 275μs |
| 亚像素精度问题 | 已用 Path2D.Double 解决 |
