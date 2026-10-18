# 上游踩坑档案：README #1–#21 + 「已知限制」 × 当前源码逐条对照

> **用途**：改代码前的必查清单。每条坑都给出「上游怎么错的 / 正确做法 / 当前源码是否又踩了」。
> **只读审计产物**：本文件不改任何源码；判定全部基于实际 grep/阅读当前 `src/`，不采信 README 自称「已修复」。

---

## 0. 审计基线与可复现信息

| 项 | 值 |
|---|---|
| 仓库 | `D:\deepseekwork\neovim-cursor-pr` |
| 分支 | `fix/adaptive-delay-reset-on-wakeup` |
| HEAD | `3a7b0a9 perf(trail): 统一 12ms 帧间隔 + 移除单帧位移截断 + 重绘合并` |
| 工作区 | 有未提交改动（4 个 java 文件），**审计对象是工作区实际内容**，不是 HEAD |
| 快照时刻 | 2026-10-18 16:45 |
| 快照位置 | `%TEMP%\nc-audit-final-20261018-164523\src`（审计期间队友仍在改文件，故留快照） |

审计时各文件 SHA-256 前 12 位（若你的 grep 结果行号对不上，先比对哈希）：

| 文件 | SHA-256(12) | 行数 |
|---|---|---|
| `ui/CaretAnimator.java` | `CD1A6F84BA0B` | 1085 |
| `ui/CaretTrailPanel.java` | `AC84AEB0C269` | 412 |
| `ui/NeovideCaretManager.java` | `E094140FF394` | 398 |
| `core/NeovideConfig.java` | `45954ABD4E71` | 257 |
| `core/TrailCorner.java` | `EE15A576B55C` | 361 |
| `core/DampedSpring.java` | `0F19F5D9D794` | 101 |
| `core/AfterimageTrail.java` | `FBA802892EF6` | 174 |

**数据源说明（重要）**：本地 `README.zh-CN.md` 是 **0.5.0 之前**的版本 —— 它只有 #1–#18，**没有 #19/#20/#21**，
「已知限制」也还是被 #21 证伪的旧口径。含 #19–#21 的版本在 commit **`6bed09d`**（v0.5.0）里。
本档案的 #19–#21 与「已知限制」章节取自 `git show 6bed09d:README.zh-CN.md`（680 行）。
`README.md` / `README.ja-JP.md` / `README.ko-KR.md` / `README.zh-TW.md` 四个语言版本**同样都缺 #19–#21**（已逐个 grep 确认）。

### 本文件的 grep 用法

```bash
grep -n '^### #'            UPSTREAM-PITFALLS.md   # 21 条目录
grep -n '^STATUS=VIOLATED'  UPSTREAM-PITFALLS.md   # 只看已违反
grep -n '^STATUS=' UPSTREAM-PITFALLS.md            # 只看判定
grep -n '^## ' UPSTREAM-PITFALLS.md                # 看章节
```

---

## 1. 速览表

| # | 标题（简） | 状态 |
|---|---|---|
| #1 | 不要调用 `container.revalidate()` | 已遵守 |
| #2 | 不要在 `paintComponent` 里绘制背景 | 已遵守 |
| #3 | 不要用 `paintImmediately` | 已遵守 |
| #4 | 不要改弹簧的衰减因子 `c` | 已遵守 |
| #5 | 不要依赖 `caretPositionChanged` 的时序 | 已遵守 |
| **#6** | **拖尾最大长度要收紧** | **已违反** |
| #7 | 重绘区域要分别提交，不能 union | 已遵守（有条件，见节内说明） |
| #8 | 控制台编辑器：不能一概过滤，也不能一概接管 | 已遵守 |
| #9 | 残影必须补插值点 | 已遵守 |
| #10 | 残影模式必须额外画常驻光标 | 已遵守（附残留缺陷，见节内） |
| **#11** | **拖尾最大长度要按重绘代价来定** | **已违反**（与 #6 同源） |
| **#12** | **辉光不能用「居中描边」实现** | **已违反**（仅残影分支；弹簧分支已遵守） |
| #13 | 光晕形状不能用包围盒 | 已遵守 |
| #14 | 精确外扩在锐角处会失控 | 已遵守 |
| #15 | 辉光尺寸不能直接套用原版 `shadowBlurFactor` | 已遵守 |
| #16 | 静止的光标不该有光晕 | 已遵守（弹簧分支；残影分支未接入，见节内） |
| #17 | 不要写死 `untilBuild` | 已遵守 |
| #18 | "只改内存"不等于"不会落盘" | 已遵守（防护已实现） |
| **#19** | **拖尾不该"当光标用"——保留原生光标** | **已违反** |
| #20 | 「低帧率补偿」把拖尾的拉伸抹平了 | 已遵守 |
| **#21** | **fps 统计把空闲时间算进了分母** | **已违反**（部分修复） |

**统计：已遵守 16 条 / 已违反 5 条 / 不适用 0 条。**
其中 **#6 与 #11 同源**（同一个参数 `maxTrailDistanceFactor`），因此**独立缺陷为 4 处**：
`#6/#11`、`#12`、`#19`、`#21`。

---

## 2. 逐条档案

### #1 不要调用 `container.revalidate()`

- **一句话结论**：给带 `LayoutManager` 的容器调 `revalidate()` 会触发父级重新布局，把覆盖层从光标大小拉伸到整个编辑器 → 全屏变色。只用 `repaint()`。
- **错误做法**：`content.getParent().revalidate()` / 任何把覆盖层挂到带布局管理器容器上并 revalidate 的做法。
- **后果**：覆盖层被布局管理器撑满可视区 → 整块编辑器变色。
- **正确做法**：挂到 `contentComponent`（`layout == null`），尺寸用 `setBounds` 直接给，刷新只用 `repaint()`。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java:311-312`（直接 `setBounds` + `add`，不 revalidate）
- 证据：`.../CaretAnimator.java:556`（尺寸变化时同样只 `setBounds`）
- 证据：`.../CaretAnimator.java:1078-1079`（销毁时 `content.remove(panel)` + `repaint()`，注释明确写「只 repaint，不 revalidate」）
- grep 复核：全 `src/` 中 `revalidate` 只出现在注释里，**没有任何实际调用**。

---

### #2 不要在 `paintComponent` 里绘制背景

- **一句话结论**：覆盖层只画多边形，绝不画矩形背景；这样即使被意外拉伸，也只显示图形本身。
- **错误做法**：`g.setColor(bg); g.fillRect(0, 0, getWidth(), getHeight());` 或 `setBackground(...)`。
- **后果**：覆盖层一旦被布局管理器拉伸，整个编辑器被涂成纯色。
- **正确做法**：`setOpaque(false)`，`paintComponent` 内只画目标图形。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`.../ui/CaretTrailPanel.java:80` `setOpaque(false);`
- 证据：`.../CaretTrailPanel.java:167-186` `paintComponent` 只做 `paintGhosts` / `paintQuad`
- 说明：`CaretTrailPanel.java:409` 的 `g2.fillRect(x, y, w, h)` 是**逐个残影矩形本身**（画的是图案），不是整面板背景，不构成本条违规。

---

### #3 不要用 `paintImmediately`（重要）

- **一句话结论**：`paintImmediately` 会把 `RepaintManager` 里所有排队的脏区一次性同步处理掉，EDT 被长时间阻塞；`repaint` 的「异步」是特性不是缺陷。
- **错误做法**：用 `panel.paintImmediately(rect)` 替代 `panel.repaint(rect)`。
- **后果**（上游实测，同机同场景）：`repaint` 峰值 **105.6 fps** → `paintImmediately` **36.9 fps**；重绘面积只有 1680px 时仍低到 **8.9 fps**。
- **正确做法**：始终 `repaint(x, y, w, h)`。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`.../CaretAnimator.java:701` `panel.repaint(x1, y1, x2 - x1, y2 - y1);`
- grep 复核：`paintImmediately` 全 `src/` 仅出现在注释（`CaretAnimator.java:691-710`）。

---

### #4 不要改弹簧的衰减因子 `c`

- **一句话结论**：`c` 不只衰减，它还参与速度积分（`b` 里已含速度位移），给它设下界会把位置**反向放大**。
- **错误做法**：`c = Math.max(c, 0.5)` 之类「给 c 设下界改善低帧率观感」。
- **后果**（上游实测）：`c=0.135` 时 `(100 + 4000×0.05)×0.135 = 40.5` ✓ 衰减；`c=0.5` 时同样输入得 `150` ✗ **放大 1.5 倍**，逐帧累积后动画鬼畜。
- **正确做法**：要限制单帧位移，只能在算出 `position` 之后限幅；**不要动 `c` 的公式**。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`.../core/DampedSpring.java:64-71`，`c = Math.exp(-o * dt)` 原样保留，无 `max/min` 包裹：
  ```java
  final double o = 4.0 / animationLength;
  final double c = Math.exp(-o * dt);
  final double a = position;
  final double b = position * o + velocity;
  double newPosition = (a + b * dt) * c;
  double newVelocity = c * (-a * o - b * dt * o + b);
  ```
- 证据：`.../DampedSpring.java:86-87` 注释明确警告「绝不能改衰减因子 c」。
- 备注：本条「正确做法」的后半句（限制 `position` 变化量）对应上游 `MAX_STEP_RATIO`，本版**整体删除**了截断（见 #20），属于另一条决策，不算违反本条。

---

### #5 不要依赖 `caretPositionChanged` 的时序

- **一句话结论**：删除文本时事件可能在 `visualPositionToXY()` 数据更新**之前**触发，读到旧位置且之后没有新事件 → 拖尾永久卡在旧位置。必须**每帧重读光标**，事件只用于唤醒。
- **错误做法**：在 `caretPositionChanged` 里计算目标点并启动动画。
- **后果**：删除/合并行后拖尾永久停在旧位置，不再跟随。
- **正确做法**：`tick()` 里每帧 `refreshTarget()`；事件回调只做 `wakeUp()`；用轻量 `getVisualPosition()` 做快速路径。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`.../CaretAnimator.java:459-462`，`caretPositionChanged` 只有一行 `wakeUp();`
- 证据：`.../CaretAnimator.java:540` 每帧 `boolean moved = refreshTarget();`
- 证据：`.../CaretAnimator.java:749-...`（`refreshTarget` 内 `getVisualPosition()` 快速路径 + `forceResync` 兜底）

---

### #6 拖尾最大长度要收紧

- **一句话结论**：`maxTrailDistanceFactor = 60`（≈1920px）在 Canvas 全屏重绘下没问题，但 Swing 覆盖层必须局部重绘 —— 拖尾越长，重绘包围盒越大，跨行跳转时退化成接近全屏重绘。**应收紧到 4（≈128px）**。
- **错误做法**：沿用原版 `60`。
- **后果**：跨多行跳转时重绘包围盒膨胀到接近整个内容区，叠加重绘成本（上游结论：收紧到 4 后「正常移动完全不受影响」）。
- **正确做法**：`maxTrailDistanceFactor = 4f`。
- **当前源码关系**：**已违反**

```
STATUS=VIOLATED
```
- 违反点：`src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java:222`
  ```java
  public float maxTrailDistanceFactor = 60f;
  ```
- 生效点：`.../core/TrailCorner.java:306-308`
  ```java
  double maxDistance = Math.max(width, height) * config.maxTrailDistanceFactor;
  clampSpring(springX, maxDistance);
  clampSpring(springY, maxDistance);
  ```
  以默认行高 32 计：`maxDistance = 32 × 60 = 1920px`（上游建议值 4 → `128px`，差 **15 倍**）。
- 加重情节：配置类自己的 javadoc（`NeovideConfig.java:209-221`）写着「本项已与原版 JS 严格对齐（60，约 1920px）」，并承认「若跳转时明显掉帧，把本值调小即可（曾用过 4）」—— **即代码作者知道 #6 的结论，却把值改回了 60**。
- 修复其实已经存在但**不在本分支**：commit `6f89ad9 fix(perf): 拖尾最大位移系数 60 -> 4，对齐 README 踩坑 #6 的实测结论` 只落在 `experiment/perf-attempts` 与 `fork/fix/adaptive-delay-reset-on-wakeup` 上，当前分支 `git merge-base --is-ancestor 6f89ad9 HEAD` = **否**。

---

### #7 重绘区域要分别提交，不能 union

- **一句话结论**：把「上一帧区域」和「当前帧区域」union 成一个大矩形再重绘，跨行跳转时会覆盖大半个编辑器；两区域不重叠时必须**分开提交两次 `repaint`**。
- **错误做法**：无条件 `union(lastRegion, currentRegion)` 一次提交。
- **后果**：跨行跳转时重绘面积 ≈ 两区域跨度 × 编辑器宽，接近全屏。
- **正确做法**：分开两次 `repaint`。
- **当前源码关系**：**已遵守（有条件）**

```
STATUS=COMPLIANT
```
- 证据：`.../CaretAnimator.java:960-969`
  ```java
  if (lastDirtyRegion.isEmpty() || currentBounds.isEmpty()
          || lastDirtyRegion.intersects(currentBounds)) {
      scratchUnion.setBounds(lastDirtyRegion);
      scratchUnion.add(currentBounds);
      repaintRegion(scratchUnion);
  } else {
      repaintRegion(lastDirtyRegion);
      repaintRegion(currentBounds);
  }
  ```
- 判定理由（为何算遵守而非违反）：只有在**两区域相交**时才合并 —— 此时并集面积 ≈ 两者之和，不存在 #7 描述的「中间大片从未画过的区域被一起重绘」的失效模式；**两区域不相交（跨行跳转）时严格分开提交**，正是 #7 的要求。注释（`CaretAnimator.java:951-959`）也显式引用了踩坑 #7。
- 残余风险（低）：`intersects()` 对「部分重叠但跨度很大」的情形仍会合并；若要更保守，可加「并集面积 / 两区域面积之和 > 1.5 则分开提交」的门限。属可选优化，不是违规。

---

### #8 控制台编辑器：不能一概过滤，也不能一概接管

- **一句话结论**：隐藏光标改的是**全局** `CARET_COLOR`，所以「跳过某类对象」≠「放过某类对象」。正确划分：代码编辑器 = 隐藏+拖尾；输出控制台 = **补画静态光标**；终端 = **跳过**（终端模拟器自绘，不受 `CARET_COLOR` 影响）。后两者 `EditorKind` 都是 `CONSOLE`，只能靠**组件树包名前缀**区分。
- **错误做法**：① `if (kind == CONSOLE) return;`（输出区光标全消失）；② 给所有 `CONSOLE` 补画光标（终端变双光标，且实测差一个字符）。
- **后果**：① Build/Run/Services/Problems 输出区光标彻底消失；② 终端出现位置不同步的两个光标。
- **正确做法**：见上表三分法；终端判定用包名前缀（`com.intellij.terminal` / `org.jetbrains.plugins.terminal`），不用具体类名。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据（不过滤 CONSOLE）：`.../ui/NeovideCaretManager.java:166-179` 注释块 + 照常挂载，**没有任何 `EditorKind.CONSOLE` 过滤**。
- 证据（终端跳过，包名前缀）：`.../NeovideCaretManager.java:162-164`（调用点）、`:203-220`（`isTerminalEditor`，`name.startsWith("com.intellij.terminal") || name.startsWith("org.jetbrains.plugins.terminal") || name.contains("JediTerm")`，向上最多 16 层）。
- 证据（输出区补画静态光标）：`.../CaretAnimator.java:295` `this.staticCaret = editor.getEditorKind() == EditorKind.CONSOLE;`，`:833-850` `tickStaticCaret()` 每帧 `snapTo` + 不画辉光。

---

### #9 残影必须补插值点，否则只是"光标在跳"

- **一句话结论**：低帧率下每帧位移可达 200px+，只记采样端点会得到几个孤立方块；必须按固定间距补插值点，**时间戳也要同步插值**（否则补出的点等亮度，形成「一串生硬方块」）。
- **错误做法**：`history.add(new Ghost(pos))` 只存端点。
- **后果**：残影模式完全失效，视觉上是「光标在跳」。
- **正确做法**：`MAX_GAP_PX = 5` 补点，`timeNanos` 同步插值。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`.../core/AfterimageTrail.java:34` `MAX_GAP_PX = 5.0`；`:37` `MAX_GAP_STEPS = 28`
- 证据：`.../AfterimageTrail.java:94-107` 补点 + `interpolatedTime = last.timeNanos + (nowNanos - last.timeNanos) * t`（时间戳同步插值）
- 证据：`.../AfterimageTrail.java:100-101`（时间插值）、`:113-120`（过期淘汰 + `MAX_GHOSTS=48` 上限）

---

### #10 残影模式必须额外画常驻光标

- **一句话结论**：残影有寿命，静止一会儿全部淡出 → 画面空了；每帧必须额外画一个 `alpha = 1` 的「光标本体」，且画尾巴时跳过与当前位置重合的采样点（否则叠加偏深）。
- **错误做法**：只画 `history` 里的残影。
- **后果**：静止约 `lifetime`（本版 `0.22s`）后光标消失。
- **正确做法**：额外 push 一个不透明矩形；尾巴循环里 `skip if ghost.center == center`。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据（跳过重合点）：`.../CaretAnimator.java:904-907`
- 证据（常驻光标）：`.../CaretAnimator.java:926-932`，末尾 alpha `1.0f`（`:931`）
- ⚠️ **附带发现的残留缺陷（属 #10 语义的延伸，建议一并修）**：`.../CaretAnimator.java:934-941`
  ```java
  if (moved || hasTail) {
      panel.updateGhosts(ghostBuffer);
      computeGhostBounds(currentBounds);
      submitRepaint();
  }
  return hasTail;
  ```
  尾巴淡完的最后一帧起，`moved=false && hasTail=false` → **不再 `updateGhosts`、不再 `submitRepaint`**，
  但面板里还留着上一帧的 `hasGhosts=true` 与旧 `ghosts` 列表（其中仍有 `alpha > 0.02` 的尾巴），
  Swing 不会自动擦除 → **残影尾巴会以「最后一帧的残留浓度」定格在屏幕上**（不是全黑，是很淡的一层），
  直到外部（滚动 / 编辑器自身重绘 / 切标签页）把该区域重画。属观感瑕疵，优先级低于 #6/#12/#19/#21。
  建议：`hasTail` 由 true 变 false 的那一帧强制提交一次「空内容」重绘（`panel.clearCorners()` + 重绘 `lastDirtyRegion`）。

---

### #11 拖尾最大长度要按"重绘代价"而非"视觉效果"来定

- **一句话结论**：这是 #6 的重申 + 方法论：**跨行跳转是最严苛场景**（同时放大「拖尾拉伸」与「重绘面积」），调参必须用它验证，不能只在同行内移动测试。
- **错误做法**：只按「跨行拖尾好看」定值。
- **后果**：同行观感良好，跨行跳转掉帧。
- **正确做法**：以跨行跳转的重绘面积为约束定值（结论仍是 4）。
- **当前源码关系**：**已违反（与 #6 同源）**

```
STATUS=VIOLATED
```
- 违反点与证据同 #6：`NeovideConfig.java:222` = `60f`，生效于 `TrailCorner.java:306`。
- 与 #6 的关系：**同一个参数、同一个缺陷**，不重复计为独立缺陷；此处单列是为了保持 21 条完整对应。
- 方法论部分的落地建议：仓库已有 `tools/OffsetTest.java`（外扩验算），但**没有跨行跳转的重绘面积回归测试**；建议加一个「跳 N 行 → 断言 `peakRepaintArea` 上限」的诊断断言，避免参数再次回退。

---

### #12 辉光不能用「居中描边」实现

- **一句话结论**：`BasicStroke` 描边路径是矩形**边界线**，各层只向外扩 `strokeWidth/2`；IntelliJ 光标极窄（2×32），最亮层只外扩 <1px 又被不透明主体盖住，真正扩得远的层 alpha 已衰减到 2% 以下 → **「只有模糊、没有发光」**。正确做法是**多层同心外扩（填充）**。
- **错误做法**：`g2.setStroke(...); g2.draw(path);` 作为辉光。
- **后果**：手感上「感觉不到辉光」（上游最初「弹簧模式没辉光」的根因）。
- **正确做法**：从最外层向内逐层 `fill(path)`，半径线性分布，`A = 1 - (1-peak)^(1/N)`。
- **当前源码关系**：**已违反（残影分支）／已遵守（弹簧分支）**

```
STATUS=VIOLATED
```
- ✅ 弹簧分支已按正确做法实现：`.../ui/CaretTrailPanel.java:227-274`（`drawQuadGlow`：`computeOffsetDirections` + 循环 `g2.fill(path)`，半径 `glowWidth * i / layers` 线性分布，alpha 反解 `1 - Math.pow(1 - peak, 1.0 / layers)`）。
- ❌ **残影分支仍在用被 #12 否定的「居中描边」**：`.../ui/CaretTrailPanel.java:395-403`
  ```java
  if (config.useShadow && alpha > 0.55f) {
      int glowAlpha = Math.round(alpha * config.glowOpacity * 60f);
      if (glowAlpha > 3) {
          g2.setStroke(new BasicStroke(
                  Math.max(2f, glowWidth * 2f),
                  BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
          g2.setColor(new Color(red, green, blue, Math.min(255, glowAlpha)));
          g2.drawRect(x, y, w, h);
  ```
  - 注释（`:392-394`）自己写明「描边居中绘制，外扩量 = strokeWidth / 2，因此取 2 倍 glowWidth」—— 这正是 #12 描述的做法。
  - 后果与 #12 完全一致：单层描边 → 外圈**硬边、无渐变**（不是高斯近似）；内半边被随后 `fillRect`（`:409`）盖掉，只剩一圈等 alpha 的环。
  - 且该处**不读 `glowStrength`**（对比弹簧分支 `:235` 乘了 `glowStrength`），与 #16 的目标不一致（见 #16）。
  - 建议修法：复用 `drawQuadGlow` 的多层外扩思路，对每个残影矩形做 N 层同心 `fillRect`（`x-r, y-r, w+2r, h+2r`），alpha 同样按 `1-(1-peak)^(1/N)` 反解，并乘 `glowStrength * alpha`。

---

### #13 光晕形状不能用包围盒

- **一句话结论**：`polygon.getBounds()` 在拖尾时能覆盖大半个编辑器 —— 跨行跳转瞬间光晕先撑成巨大方块再收缩。
- **错误做法**：`Shape glowShape = polygon.getBounds2D();`
- **后果**：跨行跳转时光晕与四边形完全脱节，出现巨大方块。
- **正确做法**：逐边外法线平移 + 相邻边求交，光晕贴住四边形本身。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`.../CaretTrailPanel.java:288-365` `computeOffsetDirections(...)`（逐边单位外法线 → 角点由相邻两边合成）
- 证据：`.../CaretTrailPanel.java:250-273` 用 `polygon.xpoints/ypoints + dir*radius` 构造 `Path2D`
- grep 复核：全 `src/` **没有** `getBounds()` / `getBounds2D()` 用于绘制形状。

---

### #14 精确外扩在锐角处会失控

- **一句话结论**：角点偏移 ∝ `1/cos(θ/2)`，拖尾时前后两端是锐角，实测偏移可达目标值 **2.4 倍**，光晕甩出长尖刺。**修正：偏移向量模长截断到 √2**。
- **错误做法**：直接用精确解 `(n1+n2)/(1+n1·n2)` 不截断。
- **后果**：锐角处尖刺；正交处（静止矩形）本身恰好 √2，所以不该再放大。
- **正确做法**：`if (mag > √2) scale = √2 / mag;`
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`.../CaretTrailPanel.java:70` `SQRT_2 = Math.sqrt(2.0)`；`:348-361`
  ```java
  double mag = Math.hypot(dirX, dirY);
  if (mag > SQRT_2) {
      double scale = SQRT_2 / mag;
      dirX *= scale;  dirY *= scale;
  }
  ```
- 独立验算：`tools/OffsetTest.java`（仓库内仍存在，5504 B）。

---

### #15 辉光尺寸不能直接套用原版的 `shadowBlurFactor`

- **一句话结论**：原版 `0.6` 描述的是 Canvas `shadowBlur`（高斯**直径**），是「能量摊开」语义；直接当外扩半径用，在 2px 宽的光标上会得到约 40px 的椭圆雾。**降到 0.2（≈15px）**。
- **错误做法**：`shadowBlurFactor = 0.6f` 直接当外扩半径。
- **后果**：光晕宽约 40px = 光标宽度的 20 倍，完全谈不上「贴着光标发光」。
- **正确做法**：`0.2f`（这是本项目**唯一有意偏离原版**的参数）。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`.../core/NeovideConfig.java:38` `public float shadowBlurFactor = 0.2f;`
- 证据：`.../ui/CaretTrailPanel.java:98-104` `updateGlowRadius` = `shadowBlurFactor × max(width, height)`；32 行高 → `6.4px`，光晕总宽 ≈ 12.8px（README 说的 15px 含抗锯齿边缘）。
- 证据：`.../NeovideConfig.java:25-37` 注释完整记录偏离原因。

---

### #16 静止的光标不该有光晕

- **一句话结论**：辉光是「运动中」才该有的；强度由四角相对目标的最大偏移驱动，偏移 ≤0.5px 完全跳过绘制，≥2.5px 满强度。
- **错误做法**：光晕恒定开启。
- **后果**：静止时像被粉色方块框住。
- **正确做法**：`maxOffset → glowStrength` 映射，峰值再乘 `glowStrength`，为 0 时提前返回。
- **当前源码关系**：**已遵守（弹簧分支）；残影分支未接入（见下）**

```
STATUS=COMPLIANT
```
- 证据：`.../CaretAnimator.java:143-144` `GLOW_FADE_START = 0.5` / `GLOW_FADE_END = 2.5`
- 证据：`.../CaretAnimator.java:873-874` `panel.setGlowStrength((maxOffset - GLOW_FADE_START) / (GLOW_FADE_END - GLOW_FADE_START))`
- 证据：`.../CaretTrailPanel.java:235-238` `peak = glowOpacity * alpha * glowStrength; if (peak <= 0f) return;`
- 证据（控制台静态光标不画辉光）：`.../CaretAnimator.java:839` `panel.setGlowStrength(0f)`
- ⚠️ **残影分支的偏差**：`paintGhosts`（`CaretTrailPanel.java:376-411`）**从不读 `glowStrength`**，只按 `alpha > 0.55f` 决定描边。而常驻光标恒为 `alpha=1.0` → 只要该处被**任何外部原因**重绘（滚动、IDE 自身刷新），静止光标也会顶着一圈描边光晕。属 #16 的部分未实现，与 #12 同一处代码，建议一起改。

---

### #17 不要写死 `untilBuild`

- **一句话结论**：`untilBuild` 是**元数据声明**问题，不在区间内 IDE **直接拒绝加载**，根本走不到代码执行。不含版本特定代码时应**省略**。
- **错误做法**：`untilBuild.set("252.*")`
- **后果**：插件只能在 2025.2 上装，2024.x / 2025.1 / 2025.3 / 2026.x 全部被拒，用户只看到「插件与当前 IDE 不兼容」。
- **正确做法**：只设 `sinceBuild`，省略 `untilBuild`；下限必须实测（本例 2024.1 卡在 JBR 17 无法加载 Java 21 字节码）。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`build.gradle.kts:38-54`
  ```kotlin
  ideaVersion {
      sinceBuild.set("242")
      // 【刻意不设置 untilBuild】...
  }
  ```
- grep 复核：`untilBuild` 全仓库只出现在注释里。

---

### #18 "只改内存"不等于"不会落盘"——配色方案被静默污染

- **一句话结论**：`setColor` 只改内存没错，但**内存状态会被 IDE 间接落盘**：用户改任意编辑器设置点 OK → IDE 整体保存配色方案 → `CARET_COLOR = 00000000` 写进 `colors/_@user_*.icls`，此后**卸载插件也无法自愈**。
- **错误做法**：`savedCaretColor = scheme.getColor(CARET_COLOR)` 直接存下来当「原始值」。
- **后果**：恢复后光标仍不可见（把隐形原样还回去）；或读到 `null` 导致恢复分支提前返回。
- **正确做法**：保存前过滤 —— 全透明/`null` 时改取 `getDefaultForeground()`，再退常量兜底。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据：`.../ui/NeovideCaretManager.java:322` `savedCaretColor = sanitizeCaretColor(scheme, scheme.getColor(EditorColors.CARET_COLOR));`
- 证据：`.../NeovideCaretManager.java:350-369` `sanitizeCaretColor`：`alpha == 0 || null` → `getDefaultForeground()` → 再退 `FALLBACK_CARET_COLOR`（`#A9B7C6`）
- 证据：`.../NeovideCaretManager.java:61` 常量、`:371-386` 恢复逻辑
- 备注：本条只覆盖「恢复侧防护」；**写入侧（全局隐藏）本身仍然无条件执行**，这才是 #19 的问题。

---

### #19 拖尾不该"当光标用"——保留原生光标才是原版观感

> 只存在于 `6bed09d:README.zh-CN.md:578-590`（本地 README 缺失此条）

- **一句话结论**：把原生光标隐藏、由覆盖层四边形充当光标，等于让**光标跟随**完全依赖 EDT 调度 —— 编辑器一忙，光标本身就「跟不上手」，这是体验远不如 VS Code 版的**首要原因**。原版默认 `hideNativeCursor: false`：**原生光标负责精确跟随，canvas 只在移动时叠加拖尾**。
- **错误做法**：`hideNativeCaret = true` + 全局把 `CARET_COLOR` 置透明，靠覆盖层画光标。
- **后果**：① 光标本体跟随延迟 = 调度延迟（默认档最长 120ms / 失焦 600ms）；② 连带问题一堆：必须改全局配色（#18 的根源）、控制台必须补画（#8 的一半）、光标无法闪烁。
- **正确做法**：保留原生光标，只叠加拖尾；提供 `hideNativeCaret` 开关（默认 `false`）。
- **当前源码关系**：**已违反**

```
STATUS=VIOLATED
```
- 违反点 1（开关是**死字段**，永远不生效）：`.../core/NeovideConfig.java:230-231`
  ```java
  /** 是否隐藏原生光标（改 CARET_COLOR 为透明） */
  public boolean hideNativeCaret = true;
  ```
  grep 复核：`hideNativeCaret` 在**整个 `src/` 中只有这一处声明，0 处读取**。
- 违反点 2（隐藏是**无条件**执行的）：`.../ui/NeovideCaretManager.java:179`
  ```java
  applyNativeCaretHidden();
  ```
  它在 `install()` 末尾被无条件调用（`:148-185`），既不看 `hideNativeCaret`，也不看任何菜单开关。
- 违反点 3（实际写透明色）：`.../ui/NeovideCaretManager.java:324`
  ```java
  scheme.setColor(EditorColors.CARET_COLOR, new Color(0, 0, 0, 0));
  ```
- 违反点 4（没有「保留原生光标」的 UI）：`src/main/resources/META-INF/plugin.xml` 只注册了 4 个 Tools 菜单动作 —— `Toggle` / `PaintDiag` / `TrailMode` / `AlwaysHighFps`，**没有 hide-caret 开关**。
- **与本次症状的关系（推断，已标注为推断；精确的帧级归因属缺陷清单的职责，不属本档案）**：
  原生光标被隐藏后，**肉眼看到的「光标」就是覆盖层**，于是「光标何时出现在新位置」完全由调度器决定，而不是由 IDE 的原生绘制决定 —— 这正是 #19 所说「跟随完全取决于 EDT 调度」。
  - 默认档：光标停止后 `computeNextDelay()` 返回 `IDLE_DELAY_MS = 120ms`（失焦 `600ms`，`CaretAnimator.java:79/82`、`:611`）。停手后第一次删除，从按键到画出第一帧最长要等一个空闲周期。
  - 常驻刷新：`computeNextDelay()` 恒返回 `ACTIVE_DELAY_MS = 8ms`（`CaretAnimator.java:603-604`），该等待消失。
  - 两端观感差异还叠加了 `dt` 口径问题（`rawDt` 的大小决定 recovery 分支是否命中，见 `CaretAnimator.java:509-512`），源码注释自己记录了实测「唤醒后 15 帧、峰值拉伸 5.51px」。
  - **可确证的结论**：这两个症状的**共同前提**是「原生光标被隐藏、覆盖层充当光标」。修掉 #19 后，「跟手」由 IDE 原生光标保证，调度延迟只影响拖尾的好看程度，不再影响「光标在哪」。
- 修复建议（与 `HANDOFF.md:245`、`BUGS-FOUND.md:187` 的结论一致）：把 `hideNativeCaret` 默认改 `false`、让 `install()` 尊重它、新增 Tools 菜单开关（`ToggleHideNativeCaretAction`），并按 #18 的防护路径处理恢复。

---

### #20 「低帧率补偿」把拖尾的拉伸抹平了

> 只存在于 `6bed09d:README.zh-CN.md:592-602`（本地 README 缺失此条）

- **一句话结论**：为防止短动画被 `animationLength <= dt` 短路吃掉而强制「每个角至少跨 6 帧」，会把原版**有梯度**的四角时长（短距离 0.05/0.045/0.025/0.015s）全部抬到 ≈0.06s → 四角同速，**光标只是整体平移，不再被拉伸**。同时撤销的还有单帧位移截断、前缘吸附 0.02→0。
- **错误做法**：`animationLength = Math.max(animationLength, MIN_ANIMATION_FRAMES * dt)`。
- **后果**：短距离移动完全失去拉伸（四角梯度消失）；单帧截断把临界阻尼曲线切成「走一半、停一下」的锯齿。
- **正确做法**：**忠实还原原版** —— 不加帧数下限；四角时长由 `trailSize` 三档（跨行分支）或 `trailFactors` 梯度（短跳分支）决定；`jump()` 结尾无条件 `reset()` 弹簧。
- **当前源码关系**：**已遵守**

```
STATUS=COMPLIANT
```
- 证据（补偿已删，且有完整说明）：`.../core/TrailCorner.java:15-23`（`MIN_ANIMATION_FRAMES` 移除说明）、`:238-241`
- 证据（梯度来源）：`.../TrailCorner.java:220-221` `factor = config.trailFactors[rank]; animationLength = config.shortAnimationLength * clamp(factor, 0, 1);`
  配合 `NeovideConfig.java:160` `{1.0f, 0.72f, 0.42f, 0.22f}` 与 `:117` `shortAnimationLength = 0.11f` → 四角 110 / 79 / 46 / 24 ms，**梯度 4.5 倍**。
- 证据（单帧截断已删）：`.../core/DampedSpring.java:15-22`、`:73-87`
- 证据（速度归零，对齐原版无条件 reset）：`.../TrailCorner.java:263-270`
- ⚠️ **附带发现：补偿的「副作用管道」还在，但是空转**。`CaretAnimator.java:568-569` 仍计算
  ```java
  smoothedDt += (dt - smoothedDt) * 0.25;
  double effectiveDt = Math.max(dt, smoothedDt);
  ```
  并把它传给 `assignRanks(effectiveDt)`（`:855`）—— 但 `assignRanks(double dt)`（`:1027-1049`）**方法体内一次都没用到 `dt`**。
  也就是说：`effectiveDt` / `smoothedDt` 这套「低帧率补偿」现在**没有任何行为效果**，而弹簧推进用的是**真实 `dt`**（`:862` `corner.update(..., dt, false)`）。
  这正是任务背景里「tick() 里弹簧用真实 dt 而非 effectiveDt」这一疑点在代码上的确切形态 —— 注意它**不是** #20 的违反（#20 要求别补偿），而是**补偿链路的残留空壳**：要么删掉 `effectiveDt`，要么真把它用起来（取决于要不要恢复补偿，二者不可兼得）。
- ⚠️ **HEAD 与工作区在这条上不同**：HEAD(`3a7b0a9`) 的 `TrailCorner` 里仍有
  `private static final double MIN_ANIMATION_FRAMES = 6.0;` 与 `double floor = frameDt * MIN_ANIMATION_FRAMES;`
  （只是加了 `min(下限, 原时长*1.5)` 的上限）。按 #20 的结论，**任何**「至少跨 N 帧」的下限都算违反。
  工作区已把它删干净（`TrailCorner.java:15-23`、`:238-241` 只剩注释）→ 所以本档案判定「工作区已遵守」，
  但**若把工作区改动丢掉、回到 HEAD，本条就会变成「已违反」**。合并时请保留这处删除。

---

### #21 fps 统计把空闲时间算进了分母

> 只存在于 `6bed09d:README.zh-CN.md:604-612`（本地 README 缺失此条）

- **一句话结论**：统计窗口只在「超过 1 秒且有活动帧」时才输出并重置，**空闲期间窗口起点不动**，于是「短暂移动 + 停顿几秒 + 再移动」会让 `帧数/经过时间` 严重偏低。**上游 0.5.0 的修正是：两次活动帧间隔超过 200ms 即视为新一段运动，重开统计窗口。**
- **错误做法**：`elapsed = now - fpsWindowStart`，只在 `elapsed >= 1s` 时重置。
- **后果**（上游实测）：按一下键动画 10 帧、停 2 秒再按，日志报 `fps≈5`，而真实帧率是 100。旧 README 那组 `fps = 0.4 ~ 10.9` 的数据就是由此得来，并导致了「瓶颈在 EDT 调度」的错误结论。
- **正确做法**：用「相邻活动帧间隔 > 200ms」判定新一段运动，重开窗口。
- **当前源码关系**：**已违反（只修了一半）**

```
STATUS=VIOLATED
```
- 已修的部分：空闲帧不再计数 —— `.../CaretAnimator.java:634-637`
  ```java
  private void recordFrameStats(long now, boolean active) {
      if (!active) {
          return;
      }
  ```
- **未修的部分（违反点）**：窗口起点 `fpsWindowStart` 只在 `:638-639` 首次置位、`:679` 输出后置位；
  **没有**「间隔 > 200ms 就重开窗口」的逻辑：
  ```java
  long gap = lastStatsNanos == 0L ? 0L : now - lastStatsNanos;   // :659
  if (gap > maxGapNanos) { maxGapNanos = gap; }                  // :660-662
  ...
  long elapsed = now - fpsWindowStart;                           // :665
  if (elapsed >= 1_000_000_000L) { ... }                         // :666
  ```
  `gap` 只被记进 `maxGap` 日志字段（`:668-674`），**从未触发窗口重置**。
- 数值示例（按当前代码推算）：一段 80ms 的移动产生 10 帧（真 125fps），随后静止 2s（`active=false`，不计帧），
  再一段 80ms 移动 10 帧 → `elapsed = 2.16s`、`frameCount = 20` → 日志 `fps = 9.3`，**与真实 125fps 差 13 倍**。
  也就是说 #21 的症状**在当前版本仍然可复现**，只是触发条件从「任何空闲」收窄到「两段运动之间仍有空闲」。
- 直接后果（对本次审计很关键）：**日志里的 `fps=` 不能直接当帧率用**，必须同时看 `frames` / `window` / `maxGap`（`8d041a9` 新增的字段）。判据：`maxGap ≪ window` → 统计口径失真；`maxGap` 本身达数百毫秒 → 真阻塞。
- 修复建议：
  ```java
  if (lastStatsNanos != 0L && now - lastStatsNanos > 200_000_000L) {
      frameCount = 0; peakRepaintArea = 0; maxGapNanos = 0L;
      fpsWindowStart = now;            // 新一段运动，重开窗口
  }
  ```
  （放在 `frameCount++` 之前；`lastStatsNanos` 已在 `:663` 更新）
- **该修复其实已经写好过，但只落在别的分支**：commit `8b5037b`（分支 `fix/glow8-stable`）引入了
  `FPS_IDLE_GAP_NANOS = 200_000_000L` 与 `lastActiveFrameNanos`，判据写成
  `if (fpsWindowStart == 0L || now - lastActiveFrameNanos > FPS_IDLE_GAP_NANOS) { fpsWindowStart = now; frameCount = 0; lastStatsNanos = 0L; }`。
  grep 复核：当前工作区 `src/` **既无 `FPS_IDLE_GAP_NANOS` 也无 `lastActiveFrameNanos`** —— 即该修复未进入本分支。
  注意该提交的说明里自己写了「fps 统计…（上游 README 踩坑 #21）」，说明作者知道这条坑，只是没合并进来。

---

## 3. 参数表 vs 源码实际值（文档值 ✗ 源码值）

对照对象：`README.zh-CN.md:147-156` 的参数表（本地版，10 行）与 `6bed09d:README.zh-CN.md:151-163`（13 行，多出 `snapAnimationLength`、`hideNativeCaret`、`cursorDisappearDelay` 等）。
源码以 `core/NeovideConfig.java` 与其读取点为准。

| 参数 | 文档值 | 源码实际值（证据） | 结论 |
|---|---|---|---|
| `tailColor` | `#FFC0CB` | `#FFC0CB`（`NeovideConfig.java:14`） | ✅ 一致 |
| `useShadow` / `shadowColor` | `true` / `#FFC0CB` | `true` / `#FFC0CB`（`:20`/`:23`） | ✅ 一致 |
| `shadowBlurFactor` | `0.2` | `0.2f`（`:38`） | ✅ 一致 |
| `animationLength` | `0.1` | `0.1f`（`:99`） | ✅ 一致 |
| **`shortAnimationLength`** | **`0.05`** | **`0.11f`（`:117`）** | ❌ **不一致（.05 → .11，2.2 倍）** |
| `shortMoveThreshold` | `8` | `8f`（`:163`） | ✅ 一致（语义已变：现为「光标尺寸倍数」，仅兜底用） |
| **`trailFactors`** | **`1 / 0.9 / 0.5 / 0.3`** | **`{1.0f, 0.72f, 0.42f, 0.22f}`（`:160`）** | ❌ **不一致（四档全变）** |
| **`useHardSnap` / `leadingSnapThreshold`** | **`true` / `0.5`** | `useHardSnap = true`（`:200`，`@Deprecated` 且 0 处读取）；**`leadingSnapThreshold` 字段根本不存在**（相近字段是 `leadingSnapFactor = 0.1f`，`:204`） | ❌ **不一致（阈值字段不存在；`useHardSnap` 是死字段）** |
| **`snapAnimationLength`**（仅 6bed09d 表） | **`0`** | **`0.02f`（`:225`）**，且 0 处读取（死字段） | ❌ **不一致** |
| **`hideNativeCaret`**（仅 6bed09d 表） | **`false`** | **`true`（`:231`）**，且 0 处读取（隐藏被无条件执行，见 #19） | ❌ **不一致（且开关失效）** |
| `cursorDisappearDelay` / `cursorFadeOutDuration` | `50` ms / `0.075` s | `50`（`:93`）/ `0.075f`（`:96`） | ✅ 数值一致，但**两字段 0 处读取**（残影/弹簧模型都没实现「停止后淡出」） |
| **`maxTrailDistanceFactor`** | **`60`** | `60f`（`:222`） | ⚠️ 表与代码一致，但**与踩坑 #6 的结论（应收紧到 4）直接矛盾** —— 文档自相矛盾 |
| **`glowLayers` / `glowOpacity`** | **`12` / `0.55`** | **`8` / `0.55f`（`:59`/`:65`）** | ❌ **不一致（层数 12 → 8）** |

### 3.1 文档里还有几处「说法」与源码不符（不属于参数值，但同样会误导）

| 位置 | 文档说法 | 源码事实 |
|---|---|---|
| `README.zh-CN.md:158` | 「除 `shadowBlurFactor` 外，本表与原版 `cursorConfig` **逐项一致**」 | `trailFactors` 是**本插件自创**，原版没有四档系数 —— `TrailCorner.java:133-134` 明确写「原版**没有** `trailFactors` 四档系数 —— 那是本插件早期自创的」。`trailSize` / `typingMaxChars` / `alwaysHighFps` / `afterimageLifetime` 等**新参数根本没进表**。 |
| `README.zh-CN.md:156` | 辉光「层数不影响亮度」 | ✅ 与 `CaretTrailPanel.java:240-245` 的 `1-(1-peak)^(1/N)` 反解一致（这条是对的） |
| `README.zh-CN.md:569-602`（已知限制） | 「完全不绘制也只有 2~4fps → 瓶颈在 EDT 调度」 | 该结论被 `6bed09d` 自我**推翻**：那组数据来自 #21 的统计 bug。本地 README 仍是旧口径。 |
| `git show 3a7b0a9`（HEAD 提交信息「统一 12ms 帧间隔」） | 12ms | **HEAD 的代码确实是 `ACTIVE_DELAY_MS = 12`，但工作区已把它改回 `8`（未提交）**：`CaretAnimator.java:76` `ACTIVE_DELAY_MS = 8`，注释写「曾试过 12ms…因此回退」。即提交信息描述的是 HEAD，工作区与其不一致 —— 任何按提交信息做的推测都会错。 |
| `CaretAnimator.java:92/93/107/396/415/421/423` | 多处注释仍写 `MAX_DT = 1/30 ≈ 33.3ms` | 实际 `MAX_DT = 1.0/20 = 50ms`（`CaretAnimator.java:100`）。注释里的 33.3ms 是旧值，据此做的数值论证（「一帧衰减 93%」等）需按 50ms 重算 —— 取 `animationLength = 0.11s`（当前值）时，`o = 36.4`，`dt = 0.05` → `c = e^-1.82 ≈ 0.16`，即一帧衰减约 84%，比注释说的 93% 略缓，但结论方向不变。 |
| `glowLayers` 注释（`NeovideConfig.java:47-57`） | 「12 层 → 6 层，若看出硬边调到 8」 | 实际值是 **8**（`:59`），注释里的「12→6」与「调到 8」都成了历史残留；`75ad0cc`(12→6) 与 `8b5037b`(6→8) 的提交说明互相矛盾。 |

---

## 4. 「已知限制」档案（两个版本，差异明显）

### 4.1 `6bed09d`（v0.5.0，**当前应以此为准**）`6bed09d:README.zh-CN.md:616-639`

| 限制 | 内容 | 与源码的关系 |
|---|---|---|
| 帧率 | 0.5.0 之前「完全不绘制也只有 2~4fps」的结论**已被推翻**，那组数据来自 #21 的统计 bug；修复后统计只计入连续运动的帧。帧率仍受 EDT 负载影响（AWT `EventQueue` 中原生输入事件优先于 `invokeLater`），但在叠加模式下原生光标零延迟跟随，拖尾偶尔掉帧不再影响「跟手」 | ⚠️ **前半段的修复未落地**（见 #21 违反）；「叠加模式」也未落地（见 #19 违反）。因此这条「已缓解」的结论在当前源码上**不成立** |
| 另一个反直觉现象 | 大跨度跳转反而比相邻两行流畅：① 视觉错觉；② 低帧率时 `dt` 较大，短距离动画被 `animationLength <= dt` 短路整体吃掉。第 2 点**曾**用「强制至少跨 6 帧」补偿，0.5.0 **已撤销**（代价是四角同速、拉伸消失） | ✅ 与源码一致：`DampedSpring.java:57-61` 的短路条件仍在，`MIN_ANIMATION_FRAMES` 已删（`TrailCorner.java:15-23`）。**结论：这个「反直觉现象」在当前源码上会复现，且是设计行为** |

### 4.2 本地 `README.zh-CN.md:569-602`（0.5.0 之前，**已被上游推翻**）

| 限制 | 旧结论 | 现状 |
|---|---|---|
| 帧率天花板 | 「完全不绘制 fps 仍只有 2~43 → 瓶颈在 EDT 调度，与绘制无关」，附 `fps=10.9 / 4.1 / 4.0 / 2.0 / 0.4` 数据 | ❌ **数据不成立**（#21 统计口径失真）。当前 `recordFrameStats` 已增加 `frames/window/maxGap` 三字段用于自证（`CaretAnimator.java:667-675`） |
| 另一个反直觉现象 | 第 2 点「已通过低帧率动画补偿（保证至少跨越 6 帧）修复」 | ❌ 该修复已被上游 #20 撤销，现读作「是设计行为，不是缺陷」 |

> **结论**：讨论「帧率低」之前，必须先按 #21 修好统计口径，否则所有 fps 结论都不可信（包括 `fps=0.5 / delay=8ms` 这类日志）。

---

## 5. 结论与改代码前的检查清单

**已违反共 5 条：`#6`、`#11`、`#12`、`#19`、`#21`**（独立缺陷 4 处，`#6`/`#11` 同源）。

按「对本项目症状（卡顿 / 动画异常）的贡献度」排序：

1. **#19（最高）** —— 原生光标被无条件隐藏（`hideNativeCaret` 是死字段），肉眼看到的「光标」就是覆盖层，于是**跟随延迟 = 调度延迟**（默认档 120ms / 失焦 600ms）。这直接解释了「空闲后第一次删除卡一下」（先等一个空闲周期才画出第一帧），也解释了「开常驻刷新就不卡」。
2. **#21（高，影响所有结论）** —— fps 日志仍会把空闲时间算进分母，任何基于 `fps=` 的性能判断都要先打折扣，必须配合 `frames/window/maxGap` 交叉验证。
3. **#12（中）** —— 残影分支仍在用「居中描边」做辉光：外圈硬边、无渐变，且不乘 `glowStrength`（连带 #16 在残影模式下不成立）。
4. **#6 / #11（中，取决于操作）** —— `maxTrailDistanceFactor = 60`（1920px）：同行移动无影响，**跨多行跳转时重绘包围盒膨胀到接近全屏**。修复补丁已存在但在别的分支（`6f89ad9`）。

改代码前请按顺序确认：

- [ ] 是否要采纳 #19（保留原生光标 + 叠加拖尾）？这会同时消掉 #18 的根源、#8 的一半工作量。
- [ ] 若保留「隐藏光标」路径，`hideNativeCaret` 必须**真正被读取**，否则它是误导性死字段。
- [ ] 先修 #21 的统计，再谈任何帧率优化（否则测不出来）。
- [ ] `maxTrailDistanceFactor` 定 4 还是 60？文档表（60）与踩坑 #6（4）互相矛盾，必须二选一并同步文档。
- [ ] 残影分支的辉光（#12）建议直接复用弹簧分支的多层外扩，不要再维护两套语义。
- [ ] 参数表与源码的 **6 处**不一致（`shortAnimationLength` / `trailFactors` / `useHardSnap`+`leadingSnapThreshold` / `snapAnimationLength` / `hideNativeCaret` / `glowLayers`）要么改代码要么改文档，别让下一轮审计再踩。

---

## 6. 复核方法（任何人都能复现）

```powershell
cd D:\deepseekwork\neovim-cursor-pr

# 1) 已违反项的现场
Select-String -Path src\main\java\com\github\yyyolo7a79\neovidecursor\core\NeovideConfig.java -Pattern 'maxTrailDistanceFactor =|glowLayers =|shortAnimationLength =|trailFactors =|hideNativeCaret ='
Select-String -Path src\main\java\com\github\yyyolo7a79\neovidecursor\ui\CaretAnimator.java -Pattern 'applyNativeCaretHidden|recordFrameStats|fpsWindowStart|panel\.repaint\('
Select-String -Path src\main\java\com\github\yyyolo7a79\neovidecursor\ui\CaretTrailPanel.java -Pattern 'setStroke|drawRect|g2\.fill\(path\)'

# 2) 死字段（声明有、0 处读取）
$cfg='hideNativeCaret','cursorDisappearDelay','cursorFadeOutDuration','snapAnimationLength','useHardSnap','leadingSnapFactor'
$files=(Get-ChildItem -Recurse src -Filter *.java | Where-Object Name -ne 'NeovideConfig.java').FullName
$cfg | ForEach-Object { "{0,-24} {1}" -f $_, (Select-String -Path $files -Pattern "\b$_\b" | Measure-Object).Count }

# 3) 上游 #19~#21 与「已知限制」原文
git show 6bed09d:README.zh-CN.md | Select-String -Pattern '^### #19|^### #20|^### #21|^## 已知限制'
```
