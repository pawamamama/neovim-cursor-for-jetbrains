# 帧率上限实测报告

> 环境：本机 Windows，IDEA 2026.1，JDK 21。
> 基准代码：`tools/bench/BenchDispatch.java` / `BenchGranularity.java` / `Bench240.java`

---

## 一、结论

| 目标 | 能否达成 | 实测 fps | 说明 |
|---|---|---|---|
| 125 fps (8.00ms) | ✅ 精准 | 124.5 | 均值 8.03ms，p99 9.25ms |
| 144 fps (6.94ms) | ✅ 精准 | 143.5 | 均值 6.97ms，p99 8.23ms |
| 165 fps (6.06ms) | ❌ | 129.9 | 均值漂到 7.70ms，p99 20.66ms |
| 240 fps (4.17ms) | ❌ | 91.7 | 均值漂到 10.91ms，p99 18.95ms |

**144fps 是这台机器上能稳定达成的上限。** 再往上，sleep 抖动与线程唤醒开销
成为主导，目标间隔越小反而越不准。

---

## 二、整毫秒档位曾是真限制（已修）

改动前 `computeNextDelay()` 返回 `int` 毫秒，调度线程按 `Thread.sleep(ms)` 睡 ——
于是帧率被钉在整毫秒档位：

```
8ms = 125fps    6ms = 166fps    5ms = 200fps    4ms = 250fps
```

165Hz 屏想要的 6.06ms 根本表达不出来（只能取 6 或 5）。

**0.9.76 已改为纳秒调度**：`computeNextDelayNs()` 返回 `long` 纳秒，
调度线程睡「毫秒下界」+ 不足 1ms 用 `Thread.onSpinWait()` 补齐。

---

## 三、为什么 165fps 就上不去了

### 实测排除的假设

| 假设 | 实测 | 结论 |
|---|---|---|
| 需要 `timeBeginPeriod(1)` 提高系统定时器分辨率 | 调用前后连续 `sleep(4)` 都是 218~225fps | **不需要** |
| 需要忙等补齐 | `sleep(4)+忙等1ms` = 180fps，纯 `sleep(4)` = 221fps | **忙等更差** |
| `LockSupport.parkNanos` 更准 | `parkNanos(4ms)` 实际 8.65ms | **远差于 sleep** |

### 真正的瓶颈

```
裸 sleep(4) 连续调用        221~223 fps   ← 单次睡眠本身很快
目标 4.17ms 的截止时间循环   91.7 fps     ← 加上判定/累加/抖动后就崩了
```

差别在于**累计误差**：每次 `sleep(4)` 实际耗时 4.0~4.6ms 不等，
但当目标是 4.17ms 时，任何一次多睡 0.5ms 都吃掉 12% 的预算，
且下一轮无法追回（截止时间已过，只能立刻执行，节奏被打乱）。

125/144 能成，是因为预算（8ms/6.94ms）远大于抖动（约 0.3~1ms）。

---

## 四、IDEA 侧的额外限制（研究结论）

来自子代理核查 JetBrains 官方文档与 YouTrack：

1. **官方从未就 240Hz 发过文档或声明**，零文档。
2. `IJPL-123404`（2022.1 已修）：官方承认「Windows 默认定时器约 16ms，
   所以刷新率只有 60 帧左右」，实现了高精度定时器但**默认关闭**。
3. `IJPL-56158`（至今 Submitted）：要求自动检测刷新率并默认开启 —— **还没做**。
4. `JBR-6047`：高刷（>60Hz）+ GPU 加速 → 闪烁，**未修**。
5. `JBR-9265`（Submitted）：官方承认 Swing **没有 vsync 定时器 API**，
   「默认实现就是一个重复的 Timer（8ms=120fps 或 16ms=60fps）」。
6. `JBR-10525`：官方实测无节拍时 ComposePanel 能到 ~1229 renders/sec，
   **说明 Java 侧没有硬上限**；但也没有 vsync，快过头会撕裂。

**对本插件的含义**：144fps 是我们在 AWT/EDT 链路上能稳定拿到的；
再往上是跟操作系统定时器和 AWT 缺少 vsync 较劲，收益极低。

---

## 五、如果将来真要冲更高

按可行性排序：

1. **接受 144fps 上限**（当前做法）。用户显示器哪怕 240Hz，
   眼睛能分辨的流畅度收益在 144 以上已很小。
2. **改用 `AnimationTimer`/Skiko**（IDEA 2024+ 内部有 Compose 渲染通道，
   但那是内部 API，插件用不了）。
3. **短睡 + 精确忙等**：实测反而更差（180 vs 221），不要做。
4. **`timeBeginPeriod(1)`**：实测无收益（本来就能到 221fps），且会拉高整机功耗，
   微软文档警告妨碍省电 —— 不建议。

---

## 六、顺带确认：重绘路径是合规的

研究报告指出 Oracle 的建议「`repaint()` 应带矩形参数，无参版本会整组件重画」。

核查本插件：

| 位置 | 调用 | 是否每帧 |
|---|---|---|
| `CaretAnimator:1041` | `repaint(x, y, w, h)` | ✅ 每帧，**带矩形** |
| `CaretAnimator:2277` | `content.repaint()` | ❌ `clearTrail()`，低频 |
| `CaretAnimator:2302` | `content.repaint()` | ❌ `dispose()`，一次性 |
| `NeovideCaretManager:926` | `contentComponent.repaint()` | ❌ 改配色后，低频 |

**每帧路径只有一条带矩形的 `repaint`，符合 Oracle 建议。**

另外 `CaretTrailPanel.setOpaque(false)` 是 Oracle 点名的「透明白层会放大每帧成本」，
但这是透明叠加层的**必然代价**（要能看见底下的代码），无法避免。