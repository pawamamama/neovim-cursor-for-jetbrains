# 修复：连续输入时拖尾长度跳变（charMoved 累计误判）

> 2026-10-27 诊断完成。**本文是给新会话的执行 brief，自包含。**

---

## 一、问题现象

按住 f 键连续输入 `f'f'f'f'` 时：
- 单个字母的**拖尾长度不一致**（有时有拖尾、有时直接瞬移）
- 因此**动画效果不一致**
- **辉光部分最明显**（因为辉光沿 quad 边缘描一整圈，形状变化被放大）

不是撕裂，不是卡顿。是「同一个按键，每次的动画不一样」。

---

## 二、根因（已查实，有文件:行号）

### 2.1 主因：注释承诺的修复从未实现

`src/main/java/com/github/yyyolo7a79/neovidecursor/core/TrailCorner.java:204-212`

注释原文（`:203-208`）：

```java
// 修复：判据不再直接用「累计字符数」，而是看<b>是否沿同一方向持续推进</b>
// ……这样无论一帧合并了几次击键，行为都一致。
```

紧接着的实现（`:210`）：

```java
isShortJump = sameLine && charsMoved <= config.typingMaxChars;
```

**`charsMoved` 就是那个「累计字符数」。注释描述的是没写出来的方案。**
`deltaXpx` 在 `:157` 算出来了，但在 `charsMoved >= 0` 的主路径里从未被使用。

### 2.2 `charsMoved` 确实是帧间累计

`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/SpringTrail.java:127-136`

```java
int updateCharsMoved(int caretOffset) {
    if (caretOffset >= 0) {
        if (lastCaretOffset >= 0) {
            moved = Math.abs(caretOffset - lastCaretOffset);   // 帧间差
        }
        lastCaretOffset = caretOffset;                          // 每帧才更新
    }
    return moved;
}
```

### 2.3 后果：`leading` 精确为 0 = 每帧瞬移

跨行分支（`TrailCorner.java:288-296`）：

```java
double leading = config.animationLength * clamp(1f - config.trailSize, 0f, 1f);
double trailing = config.animationLength;
```

「基础」「缎带」「纯平滑输入」三个预设的 `trailSize`（拖尾长度）**都是 100（即 1.0）**，
所以 `leading = 0.10 × (1 - 1.0) = 0.0000s` —— **精确为 0**。

而 `DampedSpring.update()` 第一句是 `if (animationLength <= dt) { position = 0; return false; }`
—— 时长 0 意味着**每帧瞬移到位**。

### 2.4 量化：两个分支的实际时长

实测 `deriveTrailFactors(4.5f, 0f, 0f)` → `[0.86, 0.86, 0.32, 0.32]`（斜度已是 0）

| rank | 短跳分支 | 跨行分支 | 倍数 |
|---|---|---|---|
| 0（最后缘） | 0.0946 s | **0.0000 s** | **∞（瞬移）** |
| 1 | 0.0946 s | 0.0500 s | 0.53x |
| 2 | 0.0352 s | 0.1000 s | 2.84x |
| 3（最前缘） | 0.0352 s | 0.1000 s | 2.84x |

### 2.5 触发条件

- `ACTIVE_DELAY_MS = 8`（`CaretAnimator.java:116`）常驻刷新档
- `IDLE_DELAY_MS = 120`（`:143`）、`IDLE_UNFOCUSED_DELAY_MS = 600`（`:146`）
- `typingMaxChars = 3`（`NeovideConfig.java:187`，**D0 冻结，不可改**）

按住 f 时系统重复约 30 次/秒。8ms 帧通常每帧 0~1 次击键，
但只要一帧内落进 4 次以上就切跨行 → 瞬移。降频 600ms 帧时更易触发。

### 2.6 为什么辉光最明显

`CaretTrailPanel.java:901-915`：

```java
computeOffsetDirections(quadX, quadY, dirX, dirY);
for (int i = layers; i >= 1; i--) {
    double radius = glowWidth * (double) i / layers;
    for (int j = 0; j < 4; j++) {
        double x = quadX[j] + dirX[j] * radius;   // ← 当前帧四角位置
```

辉光完全按当前帧 quad 形状画，且 `glowWidth = shadowBlurFactor × max(宽,高)`（`:466`）。
三层放大：① 拖尾拉长时周长变长，面积增长 2~3 倍；② 8 层半透明叠加，
形状一变整个重叠区重排；③ 本体是不透明实心块只看到长度，辉光看到的是整圈光晕在重排。

---

## 三、为什么不能改回上游的像素判据

**用户已 fork 自上游，上游用像素。实测数据（`docs/CODE-AUDIT-0.9.87.md:300-311`）：**

```
=== 块选区路径（走像素判据，charsMoved = -1）===
位移      jumpX    分支        四角时长(ms)
 8px      4.00    短跳        110 46 24 79
16px      8.00    短跳        110 46 24 79
17px      8.50    跨行三档     100  0  0 50   ← 只多 1px 就翻分支
24px     12.00    跨行三档     100  0  0 50

=== 普通光标路径（走字符判据）===
charsMoved=1 (全角16px)  短跳
charsMoved=2 (全角32px)  短跳
charsMoved=3 (全角48px)  短跳
charsMoved=4 (全角64px)  跨行三档
```

`shortMoveThreshold = 8`、`width = 2px` ⇒ 实际阈值 16px。
**中文一个字约 16px，输一个中文就踩线，两个就翻分支 → 瞬移。**

`docs/design/UPSTREAM-REPORT-2026-10-18.md:85` 原文确认：
> 中文全角字符约 20px，`jumpX = 20/2 = 10 > 8` → 删/输一个中文就落进跨行分支
> ……**中文瞬移无动画，英文丝滑**

**这就是当初换成字符判据的原因，改回去是开倒车。**

而且 `TrailCorner.java:161-186` 记录了另外三种尝试的失败（光标宽归一化、
写死 16px 阈值、`FontMetrics.charWidth('m')`、行首列宽），**全部因字号/字体耦合而失败**。

---

## 四、修复方案（唯一推荐）

### 改动：给 `leading` 加下限

**文件**：`src/main/java/com/github/yyyolo7a79/neovidecursor/core/TrailCorner.java`
**位置**：跨行分支的 `:289` 附近（`double leading = ...` 之后）

```java
double leading = config.animationLength * clamp(1f - config.trailSize, 0f, 1f);

// 【修复：连打时拖尾长度跳变】给前缘时长加下限。
//
// 问题：trailSize=1.0 时 leading 精确为 0 → DampedSpring 每帧瞬移。
// 而 charsMoved（帧间累计）在 8ms 帧下偶尔累积到 4+ → 越过 typingMaxChars(3)
// → 误切跨行分支 → 用户看到「这次有拖尾、下次没有」。
//
// 为什么不改判据：TrailCorner:161-186 记录了三种像素判据都因字号耦合失败，
// 而上游的 |jumpX| <= 8 在 2px 光标上等于 16px，中文一个字就踩线。
// 改判据风险远大于收益。
//
// 为什么加下限是安全的：
//   · 对短跳分支零影响（不走这段代码）
//   · 对真跨行（换行/大跳）只让过渡从「瞬移」变成「稍快」，更接近自然
//   · 只在「误判成跨行」时起作用，把后果从「没有拖尾」降级为「拖尾略短」
leading = Math.max(leading, config.shortAnimationLength * 0.5);
```

### 为什么系数取 `shortAnimationLength * 0.5`

- `shortAnimationLength = 0.11s` → 下限 = **55ms**
- 55ms 在一帧 8ms 下约 7 帧，肉眼能看出过渡
- 短跳的 rank0 是 94.6ms，55ms 明显更快但不至于「瞬移」
- 不取更大值：真跨行需要「前缘紧跟」的观感，下限太高会拖慢换行时的响应

**这个系数是我的判断，如果用户觉得过渡太慢/太快，改这一个数就行。**

### 不改什么

| 不改 | 原因 |
|---|---|
| `isShortJump` 主判据 | 像素判据会退化中文手感（见第三节） |
| `charsMoved` 语义 | 改成「帧内逐次累加」需要监听每次 caret 事件，架构改动大 |
| `NeovideConfig.typingMaxChars` | D0 冻结（`NeovideConfig.java:187`） |
| `trailSize` 预设值 | 属观感参数，必须用户决定（见下） |

---

## 五、验证方案

### 5.1 新增守门探针 `tools/settings-probe/VTypingSpan.java`

放进 `tools/settings-probe/`，并在 `build-v-probes.ps1:80` 的 `$mains` 数组里注册。

断言（按纪律四-G，探针必须调用生产方法，不许自己重写算法）：

```
T-1  trailSize=1.0 时，跨行分支的 leading 必须 > 0
T-2  charsMoved = 1..6 时，四角 animationLength 的极差 <= 3 倍
     （当前是 ∞，因为 rank0 短跳 94.6ms vs 跨行 0ms）
T-3  真跨行（charsMoved = 10）时，前缘仍应比后缘快（不破坏原有梯度）
T-4  短跳分支完全不受影响：charsMoved=1 的四角时长与修复前逐位相同
```

**T-4 是关键**——它证明这个修复不会让正常打字的手感变差。

### 5.2 变异测试（纪律四-G 强制）

把 `leading = Math.max(...)` 那行注释掉，重编译，跑探针。

**期望**：T-1、T-2 变红；T-3、T-4 仍绿。
**如果全绿，说明探针没抓住，必须重写。**

### 5.3 门禁三连

```bash
# ① D0（必须 exit=0）
cd D:/deepseekwork/neovim-cursor-pr
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java

# ② 全量探针（不许并发跑两次，见纪律四-G-2）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1

# ③ 逐行看 git diff
git diff
```

**基线数字（2026-10-27 实测）**：
```
89 个探针 / 8 个未通过 / 总 FAIL 26
未通过：VDefects(1) VSnap(10) VTools(1) VHints(1) VCustom(1)
        VReset2(2) VSepBound(7) VSleep(1)
另有 VRegr2 为红但脚本归入 informational，不计入 8
```
修复后**未通过数不得增加**。

### 5.4 手动验证

装 jar 后按住 f 连打 3 秒，观察拖尾长度是否稳定。
（`build/install.ps1 -Version 0.9.91 -NoStart`，注意它会清空配置）

---

## 六、遗留的观感决策（必须问用户，不许自己定）

### `trailSize`（拖尾长度）现在是 1.0

三个预设都是 100。它同时承担两个职责：
1. 短跳分支的「参考基准长度」
2. 跨行分支的 `(1 - trailSize)` 系数

**降到 0.8 的话**：`leading = 0.10 × 0.2 = 20ms`，误判时也有 20ms 过渡。
**但所有跨行拖尾会变短**，属观感变化。

按纪律四-L，这是**产品决策**，必须问用户，不能自己定。

---

## 七、给新会话的注意事项

1. **先读 `CLAUDE.md`**（项目根，469 行）和 `docs/WORK-DISCIPLINE.md` 的四-K/四-L/四-M
2. **不许并发跑两次门禁** —— 共用 `build/v2-probe/classes`，会假红（失败数从 8 暴涨到 18）
3. **变异测试后必须删 classes 全量重编译**，否则增量编译复用旧字节码
4. **只用中文回答**，字段名必须带中文：`slopeTicks（斜度）`、`trailSize（拖尾长度）`
5. **不要问用户实现细节**（哪个字段、怎么迁移）—— 自己定，在 commit 里说理由
6. 当前 HEAD = `63a3d6a`，分支 `fix/f7-override-read`，工作区干净
7. 用户只有一个，没有老用户 —— 不用写兼容代码

---

## 八、commit message 模板

```
fix(trail): 给跨行分支的前缘时长加下限，修复连打时拖尾跳变

## 改了什么

TrailCorner.jump() 跨行分支：
  leading = animationLength × (1 - trailSize)
  → 新增下限 Math.max(leading, shortAnimationLength × 0.5)

## 为什么

用户观察：按住 f 连打时拖尾长度不一致，辉光最明显。

根因：charsMoved 是帧间累计量，一帧内落进 4+ 次击键就越过
typingMaxChars(3) → 误切跨行分支。而 trailSize=1.0 让
leading 精确为 0 → DampedSpring 每帧瞬移。

TrailCorner:203-208 的注释写着「修复：判据不再直接用累计字符数」，
但 :210 的实际代码仍是 charsMoved <= typingMaxChars —— 注释描述的是
没写出来的方案。

不改判据的原因：像素判据会让中文退化（上游 |jumpX|<=8 在 2px 光标上
等于 16px，中文一个字就踩线），TrailCorner:161-186 记录了三种尝试的失败。

## 怎么验的

新增探针 VTypingSpan（4 条）：……
变异测试：注释掉那行 → T-1/T-2 变红，T-3/T-4 仍绿
D0 exit=0
全量探针：8 个未通过，与基线一致
```
