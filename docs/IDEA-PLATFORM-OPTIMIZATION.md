# IDEA 平台优化指南 + 240Hz 支持核查

> 来源：子代理核查 JetBrains 官方文档 / Oracle 规范 / YouTrack ticket，
> 本轮由我逐条对照本项目代码核实「是否踩到」。
> 相关：[PERFORMANCE-FPS.md](PERFORMANCE-FPS.md)（实测数据）

---

## 一、结论速览

| 项 | 官方有无明确规定 | 我们是否踩到 |
|---|---|---|
| EDT 上做自定义绘制 | 有硬约束（「必须尽可能快」） | ⚠️ 部分（见 §2.3） |
| 「不要在 EDT 上做 X」清单 | 有，只覆盖慢操作 | ✅ 未踩 |
| 局部重绘必须传矩形 | **Oracle 规范级要求** | ✅ 已合规 |
| 高频 `invokeLater` 的专门警告 | **官方没有**（明确否定） | — |
| Plugin Verifier 性能检查 | **没有**（只查兼容性） | — |
| 240Hz 官方支持声明 | **零文档** | — |

---

## 二、官方明确规定的内容

### 2.1 EDT 线程模型（Threading Model）

**来源**：https://plugins.jetbrains.com/docs/intellij/threading-model.html

> "Operations performed on EDT must be as fast as possible to not block other events
> in the queue and freeze the UI."

> "It is **forbidden** to modify PSI, VFS, or project model from inside UI renderers or
> `SwingUtilities.invokeLater()`."

**注意用词**：不是 "should avoid"，是 **"forbidden"**（禁止）。

### 2.2 「不要在 EDT 上做 X」清单

官方原文（同页）：

> "don't traverse VFS, parse PSI, resolve references, or query indexes"
>
> "Listeners mustn't perform any heavy operations. Ideally, they should only clear some caches."

**我们的情况**：`CaretAnimator.refreshTarget()` 在 EDT 上只读 `Caret` 几何信息
（`VisualPosition`、`offset`），不遍历 VFS、不解析 PSI、不查索引。**未踩。**

### 2.3 运行时断言 SlowOperations（EAP 才有）

官方源码中的断言信息：

> "The slow operation may freeze UI: it runs on EDT, blocks user input and nothing is repainted."

**关键限制**：这个断言**只在 EAP / internal / dev 构建开启**。
正式版用户完全看不到 —— 也就是说，我们若在 EDT 上做了慢操作，
**用户只会感到卡，不会收到任何提示**。

### 2.4 局部重绘（Oracle 规范）

**来源**：https://www.oracle.com/java/technologies/painting.html

> "`repaint()` should be invoked with arguments which define **only the rectangle that
> needs updating**, rather than the no-arg version"

> 用 `paintComponent()` 而不用 `paint()`；用 clip 矩形收窄；`doubleBuffered` 保持开启。

`RepaintManager` javadoc：

> "allowing the number of repaints to be minimized, for example by collapsing multiple
> requests into a single repaint"

### 2.5 透明组件的代价（Oracle 点名）

> "Setting either the `opaque` or `optimizedDrawingEnabled` properties to **false** on a
> component will cause more processing on each paint operation, therefore we recommend
> **judicious use** of both transparency and overlapping components."

---

## 三、逐条对照我们的代码

### 3.1 重绘路径：合规 ✅

| 位置 | 调用 | 是否每帧 | 判定 |
|---|---|---|---|
| `CaretAnimator:1089` | `panel.repaint(x, y, w, h)` | **每帧** | ✅ 带矩形，符合 Oracle |
| `CaretAnimator:2325` | `content.repaint()` | 否（`clearTrail()`） | ✅ 低频，可接受 |
| `CaretAnimator:2350` | `content.repaint()` | 否（`dispose()`） | ✅ 一次性 |
| `NeovideCaretManager:926` | `contentComponent.repaint()` | 否（改配色后） | ✅ 低频 |

**每帧路径只有一条 `repaint`，且带矩形。** 这是本项目最该守住的规范，已经守住。

> 行号对应当前版本 0.9.77。**行号会随代码改动漂移** —— 重新核对时用
> `Select-String -Pattern "panel\.repaint\(" src` 定位，不要信文档里的数字。

顺带记录一个**曾经的错误尝试**（代码注释里存着）：

```
paintImmediately  → 峰值 105.6 fps → 36.9 fps（重绘 1680px 时低至 8.9 fps）
repaint           → 峰值 105.6 fps
```

`paintImmediately` 会顺带把 `RepaintManager` 里所有排队的脏区一次性同步处理，
删除文本时产生大量脏区，EDT 被长时间阻塞。**已回退，不要重试。**

### 3.2 透明组件：无法避免 ⚠️

`CaretTrailPanel.setOpaque(false)` —— Oracle 明确说这是成本放大器。

**但这是覆盖层绘制光标的必然代价**：必须透明才能看见底下的代码。
没有替代方案（除非改成修改原生光标渲染，那是另一个量级的工程）。

### 3.3 高频 invokeLater：官方无警告，但有替代方案

**官方没有专门警告**（这一点子代理明确核实：没有）。最接近的两条：

1. 通用约束 —— EDT 操作必须尽可能快
2. **官方推荐的替代方案 `DebouncedUpdates`**（批处理/去抖）
   来源：JetBrains 2026.2 起 Experimental

`EventQueue.invokeLater` **没有优先级参数**。这是硬限制：原生输入事件永远排在
我们的渲染任务之前 —— 编辑器繁忙时，我们的帧会被推后（这也是 `computeNextDelay`
里「自适应跟随实测间隔」那段注释的由来）。

**我们是否该改用 `DebouncedUpdates`**：不建议。
它是 2026.2 起才 Experimental 的 API，而我们要求兼容更早版本；
且我们的场景是「稳定 8ms 节拍」而非「不可预测的突发更新」，去抖反而会破坏节奏。

### 3.4 性能门禁：只有人工审核

**Plugin Verifier 完全不做性能检查**。它只查：
- 二进制兼容性
- internal / deprecated API 使用
- 插件结构合法性

**输出文件列表里没有任何性能项。**

唯一的性能门禁在 Marketplace 人工审核 §2.2.b：

> "The Plugin does not negatively affect the performance of JetBrains products in a
> significant way"

**没有量化阈值**。这意味着：我们不能靠工具守住性能，只能靠自建基准（`tools/bench/`）。

### 3.5 可用的诊断工具（非门禁）

- `com.jetbrains.performancePlugin`（IDEA 内置性能测试插件）
- Async Profiler for IDE Performance Testing
- `Help | Diagnostic Tools | Start CPU Usage Profiling`
- JBR RenderPerfTest

---

## 四、240Hz 支持核查

### 4.1 官方：零文档、零声明

**JetBrains 从未就 240Hz 发过任何文档或声明。** 这是明确结论，不是"没找到"。

### 4.2 唯一的官方修复：IJPL-123404（2022.1 已修，但默认关闭）

官方 Maksim Pelevin 原话：

> 2021-04-23："Windows has a nearly **16 ms timer by default**, that's why the refresh rate
> is about 60 frames."
>
> 2022-02-11："implemented Windows high-precision timer for scrolling ... For now, the
> option is **off by default** ... scrolling can now do scroll with **144 frames and greater**"

**注意**：官方目标是「144 帧及以上」，且该开关**默认关闭**。

### 4.3 社区实测证据（同一 ticket）

> 2024-07-30："the scroll is at 60hz while the display is 240hz. edit: actually the
> **'enable windows high precision timer' fixes the lag**"
>
> 2025-09-27："paint requests go up to 400 in the test animation, but the scrolling is
> **still very choppy**"

**含义**：即使 paint 请求能到 400fps，屏幕上的观感仍然卡 —— 说明瓶颈不在应用层提交频率。

### 4.4 已知未修的缺陷

| Ticket | 问题 | 状态 |
|---|---|---|
| `JBR-6047` | 高刷（>60Hz）+ GPU 加速 → **闪烁**（>=2023.2） | Submitted，**未修** |
| `JBR-9265` | 官方承认 Swing **没有 vsync 定时器 API**，「默认实现就是一个重复的 Timer（8ms=120fps 或 16ms=60fps）」 | Submitted |
| `IJPL-56158` | 要求自动检测刷新率并默认开启高精度定时器 | Submitted，**还没做** |
| `JBR-10615` | `WComponentPeer.getLocationOnScreen` 阻塞 EDT，栈顶即 `JComponent.paintImmediately` | — |

`JBR-10525` 官方实测：无节拍时 ComposePanel 能到 **~1229 renders/sec**，
说明 **Java 侧没有硬性帧率上限**；但也没有 vsync，快过头会**撕裂**。

### 4.5 macOS 的情况（供参考）

- `JBR-7857`：内置 120Hz 屏上 Swing ≤80fps
- `JBR-6243`：外接显示器可到 240Hz（官方评论确认）

---

## 五、Windows 定时精度的真相（实测推翻子代理结论）

子代理引用微软文档说「Windows 默认定时器一跳约 15.6ms，240fps 需 4.17ms，结构性不可达」。

**这个结论在本机部分不成立。** 我实测（`tools/bench/BenchGranularity.java`）：

```
裸 sleep(4) 连续调用        221~223 fps   ← 单次睡眠本身很快
目标 4.17ms 的截止时间循环   91.7 fps     ← 加上判定/累加/抖动后就崩了
```

**结论修正**：

| 说法 | 实测 |
|---|---|
| 「Windows 定时器 15.6ms，做不到 4ms」 | ❌ 单次 `sleep(4)` 能到 4ms 级 |
| 「需要 `timeBeginPeriod(1)`」 | ❌ 调用前后都是 218~225fps，**无收益** |
| 「需要忙等补齐」 | ❌ `sleep+忙等` = 180fps，**比纯 sleep 的 221fps 更差** |
| 「`LockSupport.parkNanos` 更准」 | ❌ `parkNanos(4ms)` 实际 8.65ms |

**真正的瓶颈是累计误差**：每次 `sleep(4)` 实际耗时 4.0~4.6ms 不等，
目标 4.17ms 时任何一次多睡 0.5ms 就吃掉 12% 预算且无法追回。

125/144 能成，因为预算（8ms / 6.94ms）远大于抖动（0.3~1ms）。

**所以 `timeBeginPeriod` 不建议加** —— 无收益，且微软文档警告会拉高整机功耗、妨碍省电。

---

## 六、对本项目的行动项

| # | 项 | 状态 |
|---|---|---|
| 1 | 每帧重绘带矩形 | ✅ 已合规 |
| 2 | 亚毫秒调度（`computeNextDelayNs`） | ✅ 0.9.76 已做 |
| 3 | `targetFps` 只给 125/144（实测可达的两档） | ✅ 0.9.76 已做 |
| 4 | `timeBeginPeriod` | ❌ 不做（实测无收益 + 功耗代价） |
| 5 | 忙等补齐 | ❌ 不做（实测更差） |
| 6 | 改 `DebouncedUpdates` | ❌ 不做（2026.2 才 Experimental，且破坏稳定节拍） |
| 7 | 降低透明层成本 | ⬜ 无法避免（覆盖层必需） |
| 8 | 自建性能基准守住回归 | ⚠️ 已有 `tools/bench/`，但未纳入门禁 |

---

## 七、三处官方「没有答案」的地方（不要误以为有）

子代理明确核实，以下三项**官方确实没有**，不要继续找：

1. **A4**：高频 `invokeLater` 的专门警告 —— 没有
2. **A5**：Plugin Verifier 的性能检查项 —— 没有
3. **B1**：官方 240Hz 声明 —— 没有

---

## 附：报告可信度说明

- §2、§4 的官方引文由子代理核查原始文档与 YouTrack，**可点开链接验证**
- §5 的定时器结论是**我在本机实测**的，与子代理引用的微软文档**部分冲突**；
  冲突原因是「微软文档说默认定时器 15.6ms」与「实测 JVM 能做到 4ms 级」并不矛盾 ——
  JVM 内部可能已调用 `timeBeginPeriod`，或 Windows 10/11 的行为与文档描述有差异。
  **以实测为准，但仅限本机**：换机器需要重测。
- §3 的代码对照是我逐个文件核实的，行号对应当前版本 0.9.77