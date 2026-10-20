# 上游审查 + 多光标方案（2026-10-20）

> 触发：用户提问「能拿上游 0.5.0 改吗」「上游 md 有一些断言你中立的看一下」「多光标怎么做」「改线程手感会掉吗」。
> 全部结论基于**本机实测**或**上游源码**，来源逐条标注。

---

## 0. 上游现状（先对齐事实）

| 项 | 值 |
|---|---|
| 最新提交 | `6bed09d`（v0.5.0，2026-10-16）——**没有比 0.5.0 更新的版本** |
| 上游 vs 我们 | 上游**没有**设置界面、没有持久化；设置层全是我们写的 |
| 「上游线程变了」的真相 | **不是上游改的，是我们改的**：上游是"一条线程 + 批量 invokeLater 一次 tick 全部"；我们是"一条线程 + 每个 animator 各自一条 invokeLater"。见 §3 |
| 上游唯一比我们强 | 多光标拖尾（`SpringTrail` 159 行 + animator 内 `Map<Caret, SpringTrail>`） |

---

## 1. 能直接拿 0.5.0 改吗 —— 不能

| 原因 | 证据 |
|---|---|
| 我们把 6 类 bug 修在了它上面 | 隐藏原生光标污染配色（上游 #18 只修了一半，`sanitizeCaretColor` 是我们补的兜底）、scheme 恢复、Tools 不同步、第二次 apply 冲掉、IME 29 边界、预设匹配……回迁 = 把这些 bug 带回来 |
| 上游没有设置层 | `NeovideCursorSettings`(719) + `NeovideCursorConfigurable`(1,594) + `CursorPresets`(215) 全是我们的 |
| 它的构建是 Gradle | 我们只能用 `build/offline-build.ps1`（无 Gradle 环境） |

**可以移植的只有三样（这正是我们缺的）**：

1. `SpringTrail`（159 行）—— 把"单光标 4 角状态"抽成类；我们目前是内联在 1,458 行的 `CaretAnimator` 里；
2. 多光标循环（~60 行）—— `IdentityHashMap<Caret, SpringTrail>` + `caretAdded/removed`；
3. `startFrom(source)` 手法 —— 新增光标时**从已有光标的形状起飞**，而不是凭空出现（列选时视觉上很关键）。

---

## 2. 上游 README 断言的**中立核查**（本机实测）

### 2.1 它说：`Thread.sleep` 1.56ms ✅ / `parkNanos` 15.35ms ❌ / `Timer` 15.61ms ❌（JDK 21 + Win11）

我在**本机**（Win11 + JDK **25.0.2**）重跑，1ms 间隔，各 300 次：

| 机制 | 平均超时 | p50 | 最大 |
|---|---|---|---|
| `Thread.sleep(1ms)` | **1.71 ms** | 2.00 | 3.02 |
| `LockSupport.parkNanos(1ms)` | **1.16 ms** | 1.00 | 2.52 |
| `javax.swing.Timer(1ms)` | 2.12 ms | 2.00 | **99.58**（抖动尖峰） |

再按**我们的实际帧间隔 8ms** 重跑（各 150 次，看超时量）：

| 机制 | 平均超时 | p50 | p99 |
|---|---|---|---|
| `sleep(8ms)` | 0.47 ms | 0.29 | 2.01 |
| `parkNanos(8ms)` | **0.26 ms** | 0.04 | 1.36 |
| `Timer(8ms)` | 0.75 ms | 0.71 | 2.14 |

**中立结论**：
- ✅ 它的**结论**成立：别用 `javax.swing.Timer` 做帧调度；
- ❌ 它的**理由**在本机不成立：`parkNanos` 不是 15.35ms，反而**比 sleep 更准**；`Timer(8ms)` 也没有被钉在 64fps（8.75ms ≈ 114fps）；
- 🔍 真相：15.6ms 是 **Windows 系统时间分辨率**，是否被提升取决于**有没有进程调用过 `timeBeginPeriod(1)`**（IntelliJ 自己会调）。所以那张表是"某台机器某时刻"的快照，**不是定律**；
- 💡 真正该记的理由是别的：`Timer` 的任务跑在 **EDT** 上，会和绘制抢同一个线程；而不是"精度只有 15.6ms"。

### 2.2 它说：`repaint` 105.6fps vs `paintImmediately` 36.9fps

无法离线复现（要真机 + IDEA）。**逻辑上可信**：`paintImmediately` 会同步刷 `RepaintManager` 里所有脏区，删字时会阻塞 EDT。**记为"采信但未验证"**。

### 2.3 它说（Lesson #19，**最重要的一条**）：隐藏原生光标是"体验远不如 VS Code 版的主要原因"

原文大意：早早期把原生光标藏掉、让覆盖层当光标本体 → 光标跟手完全依赖 EDT 调度 → 编辑器一忙"手就跟不上"；后来改回原版默认（`hideNativeCursor: false`，原生光标负责跟手，覆盖层只画拖尾、停手 50ms 后淡出），一整串问题消失。

**我们的现状恰好相反**（`NeovideConfig:98,264`）：

```java
public boolean alwaysHighFps = true;    // 常驻刷新，默认开
public boolean hideNativeCaret = true;  // 隐藏原生光标，默认开
```

**这条断言解释了我们 A（输入不平滑）和 D（隐藏原生 + 关常驻会轻微卡）为什么同时出现**：因为拖尾不是"装饰"，它承担了"光标本体"的职责，所以它必须每帧跟手 —— 一旦帧率掉、或 short 时长太短，直接表现为"光标跳/字先没了"。

→ 这给 D 议题提供了一条**上游实证过的出路**（不是我们拍脑袋）：**默认叠加模式**（原生光标可见 + 常驻刷新可关），拖尾只做特效。
→ 但会改变"看起来是谁的光标"，属于用户可感知的默认值变更，**必须他拍板**。

### 2.4 它说（Lesson #20）：低帧率补偿把四角时长抹平 → 拖尾不再拉伸

和我们的 A 组同源：**四角时长必须是梯度的**（短跳 0.05/0.045/0.025/0.015s）。
我们在 0.9.6 里把 `shortAnimationLength` 统一到 0.11 是改**总时长**，不碰梯度 → 不冲突。✅

### 2.5 它说（Lesson #21）：fps 计数器把空闲时间算进分母，导致 0.4–10.9fps 的假结论

**这条我们要抄**：我们自己的帧率数据也是很久没重跑（`HANDOFF` 里标着"未验证"）。做性能结论前先把测量方法验一遍。

---

## 3. 线程：上游 vs 我们，改不改

| | 上游 v0.5.0 | 我们 |
|---|---|---|
| 线程数 | 1 条 daemon 线程 | 1 条 daemon 线程（相同） |
| 投递方式 | **一个全局 `FRAME_PENDING`**，一次 `invokeLater` 里 tick **所有**到期 animator | **每个 animator 自己的 `framePending`**，各投一条 `invokeLater` |
| 后果 | 一个慢编辑器会拖住所有光标（队头阻塞） | 互不阻塞，代价是 EDT 任务数 = 到期 animator 数 |
| 出处 | 上游 `RenderScheduler.java` 111 行 | 我们 `RenderScheduler.java` 186 行，契约 P0-1 明确禁止批量 tick |

**回答"改线程手感会掉吗"**：

| 方案 | 手感 | 结论 |
|---|---|---|
| 换回上游批量 tick | 基本不变，但引入队头阻塞 | ❌ 不做（契约禁止，收益为负） |
| 改成非 EDT 线程直接画 | **会掉**（Swing 非线程安全，必须与 `RepaintManager` 协调） | ❌ 绝不做 |
| **保持现状** | **不掉** | ✅ 多光标也不需要改线程 |

**关键结论：多光标不需要动调度层。** 一个 editor 仍然一个 animator；N 个光标在**同一个 EDT tick 内**循环画 N 个四边形 —— 与现在完全同构。

---

## 4. 多光标怎么做（方案）

### 4.1 现在的单光标假设（证据）

| 位置 | 现状 |
|---|---|
| `NeovideCaretManager` | `Map<Editor, CaretAnimator>` —— 每编辑器一个 |
| `CaretAnimator:865` | `editor.getVisualPosition()`（主光标） |
| `CaretAnimator:917` | `editor.getCaretModel().getOffset()`（主光标） |
| `CaretTrailPanel` | 画**一个**四边形 |
| `grep getCarets` | **0**（确认不支持） |

### 4.2 上游的做法（可移植）

```java
// CaretAnimator 内部（上游 :114）
private final Map<Caret, SpringTrail> trails = new IdentityHashMap<>();
private final List<SpringTrail> activeTrails = new ArrayList<>();

// caretAdded（:279）→ 新建 trail，并从已有 trail 起飞
SpringTrail source = previous != null ? previous : firstInitializedTrail();
trail.startFrom(source);
```

要点：
1. **键是 `Caret` 对象**（`IdentityHashMap`）—— 光标的增删用 `caretAdded/removed` 维护，不用每帧重建；
2. **新光标从旧形状起飞**（`startFrom`）—— 列选时第二个光标不是"啪"冒出来；
3. 渲染时遍历 `activeTrails` 画 N 个四边形；
4. 静态光标（控制台 / 未聚焦）走 `snapToTarget()`。

### 4.3 我们的改造点（比上游多，因为多了两样东西）

| 要改 | 现状 | 工作量 |
|---|---|---|
| 把 4 角状态从 animator 字段搬进 `CaretTrail` 类 | 内联，单份 | 中（机械搬） |
| 辉光 padding（`refreshGlowPadding`）按 per-trail 最大位移算 | 单光标 | 小 |
| 快路径（`:871` 未移动就早退）改为"所有光标都未移动" | 单光标 | 小 |
| IME snap / 模式切换 snap 改遍历 | 单光标 | 小 |
| **残影模式** per-caret ghost 列表 | 单份 `MAX_GHOSTS` | **中**（可后置） |
| 探针：多光标列选场景 | 无 | 中（新增断言） |

**范围建议**：多光标**先只做弹簧模式**（`trailMode == SPRING`），残影模式的 per-caret 化留到 0.9.8。这样第一版工作量砍掉 1/3。

---

## 5. 顺序：多光标放哪（回答"重构顺便做还是以后"）

| 选项 | 评价 |
|---|---|
| 塞进结构重构（Phase 2/3） | ❌ 不对：重构动的是 `settings` 那个 1,594 行的类；多光标动的是 `CaretAnimator`（1,458 行）。**不是同一片代码**，塞进去只会让两件事互相污染 |
| 放到"以后" | ❌ 不对：行缎带（0.9.7 计划里的累积物理）**也要 per-caret 化**。先做行缎带再上多光标 = 同一片代码改两遍 |
| **插在行缎带之前** | ✅ **推荐**：多光标先把"单光标假设"从 trail 层拔掉，行缎带的累积状态天然就是 per-caret 的 |

**修订后的顺序**：

```
0.9.6  修问题（Tools 3 项 / 不同步 / short 统一 / 输入平滑 / 已修改标记 / 高对比锐度）
0.9.7  多光标（SpringTrail 移植 + per-caret 渲染，先只弹簧模式）
0.9.8  行缎带新物理（累积拉伸 + 欠阻尼回弹，天然 per-caret）
0.9.9  结构重构 Phase 0–3（settings 大类）
```

**理由一句话**：0.9.7 和 0.9.8 动同一片代码，所以先做结构性的那个（per-caret），再做物理性的那个（累积）。

---

## 6. 工期（净时间，不含你验收和来回）

| 版本 | 内容 | 净工期 |
|---|---|---|
| 0.9.6 | 你提的问题全量 | 2–3 天（**只做 P0 = 1 天能给你第一版**） |
| 0.9.7 | 多光标（弹簧模式；含探针） | 3–4 天 |
| 0.9.8 | 行缎带累积物理 | 3–5 天（含调研） |
| 0.9.9 | 结构重构 | 5.5 天 |

**为什么之前报的"几天"看起来多**：那是我一个人改 + 跑 200+ 条探针 + 出 jar 的净时间，不含你的体验反馈轮次。如果只算"能装上看效果"，0.9.6 的 P0 是 1 天。

---

## 7. 要你拍板

| # | 问题 | 我的建议 |
|---|---|---|
| **M1** | 多光标排在哪 | 0.9.7（行缎带之前） |
| **M2** | 多光标范围 | 先只弹簧模式，残影模式后置 |
| **M3** | 默认模式要不要按上游 Lesson #19 改成"叠加" | 这条影响很大（可感知默认值），**建议先做实验再定**，见 §2.3 |
| **M4** | 线程 | 不改（多光标也不需要改） |
