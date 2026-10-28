# UPSTREAM-REPORT-2026-10-18.md —— 给上游作者的汇总报告

> 对象仓库：`yyyyolo7a79-sketch/neovim-cursor-for-jetbrains`
> 上游基线：`6bed09d`（v0.5.0）
> 我方 fork：`pawamamama/neovim-cursor-for-jetbrains`，分支 `feat/settings-ui-ready`，HEAD `321b0a0`
> 整理：2026-10-18
>
> **说明**：本文一切结论均以**逐文件核对上游 `6bed09d` 源码**为准，不是凭印象。
> 分三部分：①可直接提 PR 的缺陷（含补丁）②建议但不宜由 PR 承载的（需作者决策）③踩坑记录 + 优化方向。

---

# 第一部分：可直接提 PR 的缺陷

> 判定标准：**缺陷明确 + 修复局部 + 不涉及架构决策 + 可独立验证**。

## ⚠️ 勘误：本报告初稿的 PR-1 是**误报**，已撤销

初稿曾断言「上游 0.5.0 叠加模式下仍给控制台挂 animator，导致粉色静止光标」。**该结论错误。**

复核实证（`git show 6bed09d:.../NeovideCaretManager.java` L158-163）：

```java
if (CONFIG.hideNativeCaret) {
    applyNativeCaretHidden();
} else if (editor.getEditorKind() == EditorKind.CONSOLE) {
    // 叠加模式：原生光标未被隐藏，控制台光标完好，无需补画
    return;
}
```

**上游已经处理了控制台**，做法与我们一致。误判原因：初稿只 grep 了 `hideNativeCaret` 那一行，**没看紧跟的 `else if` 分支**。

教训留在这里：**引用代码必须看完整分支结构，不能只 grep 关键字段**。这与本会话早前"误把测试脚本当契约原文"是同一类错误。

---

## PR-1（唯一推荐的低风险 PR）：`wakeUp()` 漏重置 `lastNanos`

| 项 | 内容 |
|---|---|
| 位置 | `ui/CaretAnimator.java` `wakeUp()`（上游 L258-263）与 `tick()`（L291-294） |
| 上游现状（逐行核实） | ```java // wakeUp() 只动这两处 currentDelayMs = ACTIVE_DELAY_MS; nextDueNanos = System.nanoTime(); RenderScheduler.wakeUp();  // tick() 里 lastNanos 才更新 long now = System.nanoTime(); double dt = Math.min((now - lastNanos) / 1e9, MAX_DT); lastNanos = now; ``` —— 全文件 `lastNanos` 只有这两处，**`wakeUp()` 确实没重置它** |
| 后果 | 空闲期间（120ms / 600ms 档，甚至失焦数分钟）`lastNanos` 停在最后一次 tick。恢复后第一帧：`dt = min(整个空闲期, MAX_DT=1/30≈33.3ms)`。按 `animationLength=0.05`、`o=80` 算 `c = exp(-80×0.0333) ≈ 0.070` → **一帧衰减 93%**（正常 8ms 时 `c≈0.527`，只衰减 47%）→ 视觉上**瞬移** |
| 现象对应 | 「停几秒后第一次删除到上一行，卡一下」；而**快速连续删除反而流畅**（因为那时 `lastNanos` 每 8ms 都在更新）—— 这个"越连续越顺"的反差只有 dt 这条链能解释 |
| 修法 | 一行：在 `wakeUp()` 里加 `lastNanos = System.nanoTime();` |
| 注意 | 上游已换成 `RenderScheduler`，需确认**在唤醒路径上**重设，而不是在 `tick()` 里重设（`tick()` 已经在做了，正是问题所在） |
| 风险 | **极低**。只影响「从空闲恢复后的第一帧」，活跃档完全不受影响（`wakeUp` 在活跃档时 `lastNanos` 本来就是新的） |

---

## PR-2（建议先提 issue，不改架构）：`runDue()` 批量 tick + `FRAME_PENDING` 复位时机

| 项 | 内容 |
|---|---|
| 位置 | `ui/RenderScheduler.java`（上游 L86 / L96 / L101-105） |
| 上游现状（逐行核实） | ```java if (!FRAME_PENDING.compareAndSet(false, true)) { ... } // L86 SwingUtilities.invokeLater(RenderScheduler::runDue);        // L96  private static void runDue() {     FRAME_PENDING.set(false);          // L102 ← 在循环之前     long threshold = ...;     for (CaretAnimator a : ANIMATORS) {   // L105 批量 tick         if (a.nextDueNanos <= threshold) a.runTick();     } } ``` |
| 问题 1 | `FRAME_PENDING.set(false)` 在循环**之前** —— 长 tick 执行期间标志已复位，调度线程可立刻再投递一批，防堆积作用被削弱 |
| 问题 2 | 一次 `invokeLater` 里 tick **所有**到期 animator。AWT EventQueue 顺序处理，原生键鼠事件必须等整个循环跑完。**拆成 N 个小任务时输入事件还能插空处理** —— 编辑器/标签页越多越明显 |
| 为何不直接提 PR | 这是**调度架构**。作者正用新模型重构，此时提架构改动容易撞车。**建议先提 issue 附实测数据，由作者决定** |
| 我方对照实现 | 每 animator 各自一次 `invokeLater` + 各自 pending 标志。实测：3 个同时到期 → 3 个独立投递；N 编辑器 → 1 条线程；`runDue`/全局 `FRAME_PENDING` 归零 |

---

## PR-3（可选，需作者同意）：`lastDispatchedDueNanos` 式的到期守卫

若作者采纳 `nextDueNanos` 排程但**不做**上面的批量 tick 改造，则会遇到另一个问题：`framePending` 在 **EDT 任务开始**时复位，而 `nextDueNanos` 在 **tick 结束**时才改写 → 调度线程在长 tick 期间读到「已过期且无在途任务」的假象 → **重复投递**（我们实测无护栏时投递数达 2~3 倍）。

我们加了一个 per-animator 的 `lastDispatchedDueNanos`（记录已投递的到期值，值未变则跳过）解决。实测：到期值 800ms 不变时只投递 1 次。

**这条属于"上游若采用我们的写法才需要"，不是独立缺陷**，故列在最后。

---

# 第二部分：建议写进 issue、但**不宜由 PR 承载**的（4 项）

> 判定标准：涉及**手感决策**或**架构选择**，必须作者拍板。

## ISSUE-A：CJK 短跳判据系统性误判（**最重要的一条**）

| 项 | 内容 |
|---|---|
| 位置 | `core/TrailCorner.java` `jump()`（上游 L~200） |
| 上游现状 | ```java double jumpX = (destX - previousDestX) / width;   // width = cursorWidth = 2px double jumpY = ... / height; boolean isShortMove = Math.hypot(jumpX, jumpY) <= config.shortMoveThreshold;  // 8 ``` |
| 缺陷 | 判据用**像素 ÷ 光标宽度**归一化。中文全角字符约 20px（字号 20），`jumpX = 20/2 = 10 > 8` → **删/输一个中文就落进跨行分支**；而跨行分支 `trailSize=1` 使 `leading = 0` → rank≥2 前缘时长 0 → `DampedSpring` 短路 → **中文瞬移无动画，英文丝滑** |
| 实测对照 | 英文 1 字符 10px → jumpX=5 → 短跳 ✅；**中文 1 字 20px → jumpX=10 → 跨行** ❌ |
| 建议修法 | 用编辑器原生**字符偏移**判定，不用像素：<br>`int charsMoved = abs(editor.getCaretModel().getOffset() - lastOffset);`<br>`isShortMove = sameLine && charsMoved <= 3;` |
| 为什么这是最优解 | `offset` 差值**就是字符个数**，中/英/日/韩/emoji（代理对按 2 计）全部正确，与字体/字号/全角半角/连字无关；且 `getOffset()` 比 `visualPositionToXY()` 便宜 |
| 说明 | 本条对应我们已提交的 issue #3 问题一。**上游 0.5.0 仍未修**（实测：`TrailCorner` 里搜 `charsMoved`/`getOffset` 均为 0 命中） |

---

## ISSUE-B：`maxTrailDistanceFactor = 60` 与 README 踩坑 #6 自相矛盾

| 项 | 内容 |
|---|---|
| 实测 | 上游 `NeovideConfig.java`：`public float maxTrailDistanceFactor = 60f;`（**60**） |
| 矛盾 | README 踩坑 #6 写「收紧到 **4**（约 128px）后正常移动完全不受影响」，而 `NeovideConfig` 自己的注释写着「曾用过 4」 |
| 数值含义 | 限幅 = `max(cursorWidth, lineHeight) × factor`。行高 32px 时 **60 → 1920px**，比多数编辑器可视高度还大；4 → 128px |
| 为何 4 不影响日常 | 钳位对象是 `spring.position`（像素偏移）。相邻列约 8px、上下移一行约 32px，**都远小于 128px，碰不到阈值**；只有删换行/跨十行跳转才触发 |
| 代价 | 大跨度跳转时拖尾被截断在约 4 行高，比原版短。`clampSpring` 只改 position 不动 velocity，不引入抖动 |
| 建议 | **需要作者决策**：这是观感取舍。但**文档与代码不一致本身**应该修（要么改代码、要么改 README 并说明为何选 60） |

---

## ISSUE-C：IME 组合输入期间光标错位（**新发现，我方确证机制**）

> 这是我们今天新查出来的，上游 0.5.0 **同样存在**，且比 issue #3 里的问题更隐蔽。

| 项 | 内容 |
|---|---|
| 现象 | 中文输入法组合态（未上屏）期间，光标会卡在某个字的**半个字宽**处（如「中」的竖笔画上）；切标签页/滚动都不恢复，**继续输入才跳回** |
| 上游是否同样存在 | **是**。实测上游 `CaretAnimator` L505-509 有相同的快速路径 + `forceResync` 缓存结构 |
| 根因（我方四条实验确证） | ①**E1**：英文输入法**不复现** → IME 组合态特有<br>②**E3**：滚动无效 → 全仓 `Scroll\|VisibleArea\|Viewport` **0 命中**，插件感知不到滚动<br>③**E4**：切标签页无效 → 不改内容区尺寸，不触发 `forceResync`<br>④**继续输入自愈** → 说明是**坐标缓存**而非算错 |
| 机制链 | 组合期间 `getVisualPosition().column` 指向组合串内部 → `visualPositionToXY()` 算出半个字宽的 x → 该中间态被存入 `lastVisualLine/lastVisualColumn` → 组合结束后若列值恰好等于缓存值，**快速路径判定"没动"→ 永不重算** → 直到下次输入改变列值才重算 |
| 三个候选修法 | **F2（最小）**：`caretPositionChanged` 里置 `forceResync = true`（一行）<br>**F2'（最准）**：用 `getOffset()` 校验 `vp` —— 组合期间 offset 不变但 vp 在变，两者不一致则视为中间态跳过<br>**F1（根治）**：监听 IME 组合开始/结束 |
| 严重性 | **纯视觉、不损数据**（覆盖层鼠标穿透、不参与输入）。我们用户评估"不影响使用" |
| 为何提 issue 不提 PR | 修法涉及"组合期间该画在哪"的判断，需作者定策略；且 F1 依赖未验证的平台 API |

**建议我们主动提 issue 并附完整取证**（我们这边有 17,923 B 的详细报告，可直接摘）。

---

## ~~ISSUE-D~~（已撤销 —— 基于误报）

初稿的 ISSUE-D 断言「`hideNativeCaret=false` 时控制台逻辑与注释意图不符」。**该结论错误**，上游 `install()` L158 已正确处理（见第一部分勘误）。已删除。

---

# 第三部分：踩坑记录 + 优化方向（不需要上游做什么，供作者参考）

## 3.1 我方已排除的方向（**别让作者重走**）

| 假设 | 排除依据 |
|---|---|
| 绘制慢 | 12 层辉光 **0.05ms/帧**（8ms 预算的 0.6%）；完整 Swing paint 0.09ms/帧 |
| 重绘面积大 | **面积大 67 倍反而快 15 倍**（106万 px→66.6fps vs 3551px→15.7fps） |
| GC | 最长停顿 15ms，而卡顿是 900ms~31s |
| GPU | JBR 25 无 D3D/OpenGL 管线；光标仅 64px；Java2D 的 GPU 管线也不加速 `fill(Path2D)` |
| 插件占 EDT | 45 分钟 JFR：插件仅 **3.26%** |
| **IDEA 自身绘制** | `ScaledBlit.Scale` 占 EDT **12.6%** ← 真正的头 |

## 3.2 真正瓶颈：EDT 抢占

插件日志（`delay=8ms` 说明调度器正常）：

```
maxGap=358ms   peakRepaintArea=5494px
maxGap=399ms   peakRepaintArea=4958px
```

**投递出去的任务在 EDT 队列里排了 120~400ms** —— 不是我们画得慢，是排不上队。

## 3.3 五个已踩过的坑（都有实测，别再犯）

| # | 改动 | 后果 |
|---|---|---|
| 1 | `ACTIVE_DELAY_MS` 12 → 8ms | 一切都变快，用户说「傻快」 |
| 2 | 删掉 `MIN_ANIMATION_FRAMES` | 低帧率下动画消失 |
| 3 | 硬编码 16px 短跳阈值 | 中文卡（字号 20px 中文宽 20px） |
| 4 | `FontMetrics('m') × 2.5` | 换个魔数位置，仍不准 |
| 5 | 第0列→第1列测宽 | **取行首字符宽度**，中文区仍翻倍 |
| 6 | `recovery` 分支 `dt = rawDt` | **不开常驻刷新一帧走完位移 = 卡爆** |
| 7 | **分步推进（substep）** | **审计证明是"正确的空操作"，且更糟 38~46pp** |
| 8 | `hideNativeCaret` 改成 `true` | 违反上游踩坑 #19 |

### 第 7 条最值得说：为什么"分步推进"是错的

```
一次 update(0.12)      = 6.828984590243152
分15次 update(0.008)   = 6.828984590243153
差 = 8.88e-16  ← 完全等价
```

**而 `updateCorners()` / `submitRepaint()` 在 steps 循环之外 —— 一次 tick 只画一帧，中间状态根本不渲染。**

**「15 步塞进 1 帧」=「1 步推进」，对用户完全一样。**

## 3.4 优化方向（按收益排序，供重构参考）

| 优先级 | 内容 | 收益/依据 |
|---|---|---|
| **P0** | **空闲时不再向 EDT 交脏区** | 常驻刷新下即使完全静止，也每 8ms 走一遍 `tick → computeTrailBounds → submitRepaint`。8 个编辑器 ≈ 1000 次脏区提交/秒，全在重画同一张已正确的画面。保守做法：仅在"无焦点 + 完全静止"时跳过，获焦/任何事件立即恢复 |
| **P1** | **隐藏标签页降频** | 现在 `!content.isShowing()` 只是 `clearCorners()` 后 return，**调度仍是 8ms**，每帧照样调 `visualPositionToXY`。可降到 120ms + 重新显示时 `forceResync` |
| **P2** | 静止帧的 `refreshTarget` 短路 | 已用 `getVisualPosition()` 做快速路径，但**面板尺寸/光标尺寸的检查**仍在每帧跑 |
| **P3** | 残影辉光改法 | `CaretTrailPanel` 残影分支用「居中描边」，外圈硬边无渐变（上游踩坑 #12 已否定该做法），仅影响残影模式 |
| **P4** | fps 统计补 `FPS_IDLE_GAP_NANOS` | 仅诊断精度 |

## 3.5 我方在 fork 上已验证有效的做法（作者可参考）

| 项 | 效果 | 备注 |
|---|---|---|
| fps 日志 `LOG.info` → `isDebugEnabled()` 门控 + 窗口 1s→5s | idea.log 里 2283 行/0.44MB/7.24% 不再持续增长 | 上游 0.5.0 仍是无条件 `LOG.info`（实测 L404） |
| 每帧临时对象复用（`Path2D`、两个 `double[4]`） | 10 编辑器 × 125fps ≈ 3750 对象/秒不再分配 | 上游 `CaretTrailPanel` 也是每帧 `new` |
| 删 7 个零引用字段 | 为设置界面做准备 | 上游 0.5.0 实测仍有 13 处命中（`NeovideConfig` 5 + `CaretAnimator` 5 + `TrailCorner` 3） |
| 共享调度线程 + **每 animator 各自 invokeLater** | 线程 N→1，EDT 任务粒度不变 | 与上游 P0 方向一致，但**实现方式不同**（见 PR-3） |
| `nextDueNanos` 从「tick 结束的 now + delay」改为「上次到期 + delay」并追赶 | 修掉 EDT 繁忙时 −17%~−37% 的回退 | 上游若采用 `nextDueNanos` 也会有同样问题 |

**最后一条的实测数据**（供作者参考）：

| 场景 | 基线 | 旧写法 | 修复后 |
|---|---|---|---|
| 常驻刷新 + 单帧 25ms | 58 帧 | **44**（−25%） | 56 |
| 自适应 + 单帧 25ms | 59 帧 | **37**（−37%） | 57 |

根因：基线是「先 sleep 再投递」，单帧耗时 > delay 时**不再多等**；而从「tick 结束的 now」起算会**多等一个 delay**。

---

## 附录：验证方法（可复现，便于作者复核）

我们用的 parity harness（不依赖 IDE 平台类，只用 `core` 包）：

- **物理逐位**：`Double.doubleToRawLongBits` 输出四角坐标/偏移的十六进制，覆盖短跳、中文删除、跨行大跳、退化场景 × dt 序列 8/16.7/33.3/50/120ms
- **几何逐位**：反射调用真实 `CaretTrailPanel.computeOffsetDirections`，含四角重合、零长边两个退化用例，每个多边形连调两次（第二次可暴露残留状态）
- **判定**：批次前后源码分别编译运行，stdout **逐字节相同**

这套方法用来证明"改动没有影响物理/几何"，比主观判断可靠。

---

## 我们建议的提法

| 类别 | 内容 | 建议 |
|---|---|---|
| **提 1 个 PR** | **PR-1**：`wakeUp()` 补 `lastNanos` 重置（**一行**） | 风险最低、收益明确、极易 review。上游 0.5.0 实测确实缺这一行 |
| **提 issue（附数据）** | **PR-2**：`runDue()` 批量 tick + `FRAME_PENDING` 复位时机 | 改调度架构，作者正在重构 → 先讨论，别撞车 |
| **提 issue** | **ISSUE-A**（CJK 短跳判据）、**ISSUE-C**（IME 组合态错位）、**ISSUE-B**（`maxTrailDistanceFactor` 文档矛盾） | 需作者拍板；A/C 是算法与平台交互问题，重构也不会自动消失 |
| **附在 issue 正文** | 第三部分踩坑 + 优化方向 | 不必单独成篇，但价值高（省作者重走弯路） |
| ~~不提~~ | ~~控制台粉光标~~ | **初稿误报，已撤销** |

**关于"上游 0.5.0 架构大改 / 作者要用 Opus 5.5 重构"的判断**：

1. **正因为要重构，PR 必须只改局部**。`lastNanos` 那一行在任何架构下都成立，是本报告唯一值得单独开 PR 的项。
2. **调度类问题（PR-2/PR-3）一律降级为 issue**。作者重构时本来就会重写 `RenderScheduler`，此时提架构 PR 既难 review 又可能白做；但**把实测数据留给他**，能直接影响他的设计。
3. **ISSUE-A（CJK）和 ISSUE-C（IME）最值得现在就让他看见** —— 这两条是**平台交互层面的固有陷阱**，不随重构消失，而且都很难靠自己发现：
   - CJK：需要一台中文环境 + 字号 20px 才能触发，英文环境完全测不出
   - IME：需要中文输入法组合态，且现象"随机、不影响使用"，很容易被判为用户环境问题
4. **不要给重构提"请照我们的实现改"**。我们只在报告里给**现象 + 机制 + 数据**，实现方式留给作者 —— 他会自己判断。

---

## 附：我们这份报告的自我审查

本报告初稿有 **3 处错误**，均已更正并留痕：

| # | 错误 | 更正 | 原因 |
|---|---|---|---|
| 1 | 断言上游未处理控制台 → 提"粉光标"PR | **撤销**。上游 L158 已有 `else if (CONSOLE) return` | 只 grep 了 `hideNativeCaret` 一行，没看紧跟的 `else if` |
| 2 | 曾把 `tools/parity/run-audit.ps1:104` 的**故意失败反例**当成契约原文引用 | 已在下游文档更正 | 把测试代码当成了规范 |
| 3 | 初稿把 `cursorDisappearDelay` / `cursorFadeOutDuration` / `useHardSnap` / `leadingSnapFactor` / `snapAnimationLength` 当成"上游死字段" | **撤销，且方向相反** | 见下方 3.6 |

三处共性：**只看了片段，没看完整上下文**。

---

## 3.6 【重要更正】上游 0.5.0 的淡出机制比我们 fork 更完整（我方是倒退）

初稿曾把这 5 个字段列为"零引用死字段"。**逐行核实后完全相反** —— 上游**真的在用它们**：

```java
// CaretAnimator.overlayAlpha()  —— 叠加模式下覆盖层的不透明度
private float overlayAlpha(long now) {
    if (lastAnimatingNanos == 0L) return 0f;
    double sinceStop = (now - lastAnimatingNanos) / 1e9
                     - config.cursorDisappearDelay / 1000.0;   // ← 用上了
    if (sinceStop <= 0) return 1f;
    double fade = config.cursorFadeOutDuration;                 // ← 用上了
    if (fade <= 0 || sinceStop >= fade) return 0f;
    return (float) (1.0 - sinceStop / fade);
}
```

`TrailCorner.jump()` 里也是真用：

```java
boolean useSnap = config.useHardSnap && alignment > config.leadingSnapThreshold;  // ← 真用
double factor = useSnap ? config.leadingSnapFactor : ...;                         // ← 真用
double animationLength = useSnap ? config.snapAnimationLength : ...;              // ← 真用
```

**而我们 fork 里这 5 个字段确实是零引用**（搜 `overlayAlpha` = 0 命中），因为我们在早前的移植中**没有把淡出这条路径拿过来**，于是字段变成死的、被我们删了。

**结论（必须记下来，避免继续误判）**：

| 项 | 上游 0.5.0 | 我们 fork | 谁更好 |
|---|---|---|---|
| 停止后淡出 | ✅ `overlayAlpha()` 延迟 + 线性淡出 | ❌ 直接 `clearCorners()` 硬切 | **上游** |
| 前缘硬吸附 | ✅ `useHardSnap` + 三档因子 | ⚠️ 改为 `trailFactors` 四档梯度 | 取舍（各有取向） |
| `shortJumpMaxChars` / `typingMergeChars` | **上游根本没有**（全 5 文件 0 命中） | 曾有、已删 | 我方历史遗留，与上游无关 |

**这条对我们自己的意义**：
1. **绝不能拿这 5 个字段去给上游提"死字段"issue** —— 那是我们的欠缺，不是他的问题。
2. **淡出是上游有、我们没有的能力**。若我们的用户觉得"停手后拖尾消失得太突然"，可以直接把 `overlayAlpha()` 移植回来（字段我们刚删，需要一并恢复）。
3. `shortJumpMaxChars` / `typingMergeChars` 只删我们自己的历史遗留，**与上游无关**，不要写进给作者的文档。


