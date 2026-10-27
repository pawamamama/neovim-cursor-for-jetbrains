# 设置系统重构方案

> 基线 `4bbbcfc`（main）· 2026-10-27 · **本文只是方案，一行生产代码都没改**
>
> **【2026-10-27 更新 · 步骤 1 已完成】**
> 本文 §4 步骤 1 是「摸底与基线固化」，现已执行完毕，实际结果比预期好：
> **8 红 → 6 红**（`VDefects` 与 `VSnap` 双双全绿，不只是预期中的「VDefects 变绿」）。
> 根因比我当时判断的更精确：不是「过期断言」，是**索引契约破裂**
> （探针用 `sliders.get(0)` / `textFields.get(8)` 定位控件，面板重排后指向了别的字段）。
>
> **所以下文中凡是写「8 红名单不变」的地方，现在都应理解为「6 红名单不变」**：
> `VTools` `VHints` `VCustom` `VReset2` `VSepBound` `VSleep`（`VRegr2` 是 informational）。
> 步骤 2~5 尚未开始。
>
> 本文所有数字都是本轮在 worktree `sleepy-swanson-ffc2cf` 里实测得到的。
> 凡与 brief 给的数字不一致的，我都写明了「brief 说 X / 实测 Y / 差在哪」，
> 因为这正是本项目最大的风险源（CLAUDE.md §11：历史数字大量失真）。
>
> **前置文档**：`docs/REFACTOR-FEASIBILITY.md` 是同一主题的调研（2026-10-27），
> 但它的「第一优先」已在 `4bbbcfc` 完成，其余结论（尤其「面板混乱应单独做」）本文沿用。
> 本文不复述它，只补充它没有的实测数据与分步执行方案。

---

## 零、先说三个结论（怕你只看开头）

**结论 1：「加一个新设置项从 8 处降到 1~2 处」这个目标，架构上做不到。**
8 个落点里有 5 个携带实际语义（产品决策 / 消费逻辑 / 探针自证禁忌），
不可能压缩成 1~2 处。**能做到的是 8 → 6。**

**结论 2：但你这个目标里更值钱的那半句——「改错时编译不过，而不是静默失效」——是可以做到的，而且这才是重构真正的价值。**
「处数」是虚荣指标，「漏了会红」才是真收益。本方案的核心是把 3 处静默失败点
变成 3 处编译期或门禁期失败点。

**结论 3：brief 里问的三个问题，我给出的答案和 brief 的预设方向有出入，
最重要的两处是：**
- 「三件套重复 16 次，抽出来省 200 行」——**实测省不了 200 行，最多省 50~60 行真代码**（占全文件 1.7%）。原因见 §2.1。
- 「让 VUi 的 SPEC 从生产代码派生」——**这条路必须否决**，它是自证式探针，
  项目里已经写明了理由（见 §2.3 与 §4 步骤 3）。

---

# 一、现状诊断（复核过的数字）

## 1.1 基线快照（第 1 步的产出）

**环境**

```
worktree   D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\sleepy-swanson-ffc2cf
HEAD       4bbbcfc  merge: runtimeSwitch 重构（字符串数组 -> 字段标志）
工作区     干净（git status --porcelain 无输出）
```

**门禁（同一份命令跑了两遍，数字一致）**

```
D0 门禁    PASS（core/NeovideConfig.java 零 diff）
全量探针   V-PROBES RESULT: FAILED（8 个探针未通过）
```

**8 个红灯的完整名单与逐条断言数**

| 探针 | PASS | FAIL | 与本次重构的关系 |
|---|---|---|---|
| `VDefects` | 46 | 1 | **有关**：V11-3b 用 `textFields.get(0)` 取输入框 |
| `VSnap` | 22 | 10 | **有关**：10 条全部同根因，用 `sliders.get(0)` 当 `cursorDisappearDelay` |
| `VTools` | 11 | 1 | 无关：M-11 预设域变化计数 |
| `VHints` | 10 | 1 | 无关：H-6 提示文案里没有「280 ms」 |
| `VCustom` | 40 | 1 | 无关：C-19 未改动时派生了「基础 副本」 |
| `VReset2` | 5 | 2 | 无关：A-1 勾选换预设丢失、A-4 派生存活 |
| `VSepBound` | 36 | 7 | 无关：预设名含分隔符被静默丢弃 |
| `VSleep` | 3 | 1 | 无关：Timer 精度（机器相关） |

> `VRegr2`（PASS=13 FAIL=2）是 informational，不计入 8 个，与 brief 一致。

**这 8 个红里，只有 11 条断言（VDefects 1 + VSnap 10）与「面板控件索引」有关。**
其余 34 条与本次重构的改动面完全不相交。这个数字后面会反复用到——
它决定了「哪些改动会碰红哪些探针」。

**失败断言原文（存档，用于事后归因）**

```
VDefects  V11-3b 精确值 333 → 输入框/标签显示精确值（不篡改用户数据）
          [tf="11" label="0.11 s"]          ← 拿到的是 shortAnimationLength 的控件
VSnap     TYP-1 键入 333 后滑块吸附到 350   [slider=25]
VSnap     TYP-3 标签显示用户输入的精确值 333 [label="3.33 s"]
VSnap     cursorHeightRatio 量程=[30,100] step=10 面板=[30,100] 请求 65 → 落位 60
```

**根因（这一条很关键，它推翻了 brief 对痛点位置的判断）**

`VDefects.java:266-268` 用的是 `sliderByKey("cursorDisappearDelay")`（按 key 定位，**正确**），
但紧接着的输入框和标签用的是 `ui.textFields.get(0)` / `ui.valueLabels.get(0)`（**按索引，错误**）。

- 历史上面板第一个滑块是 `cursorDisappearDelay`，索引 0 对得上；
- 现在 `SPECS` 数组第一项仍是它，但**面板渲染顺序**已改成 Inline 组在前，
  索引 0 变成了 `shortAnimationLength`（量程 2..25）；
- 于是「键入 333」打到短动画时长上，被 clamp 到 25 —— 报错里那个 `slider=25` 就是它。

**所以这不是「过期断言」，是「索引契约破裂」。** 两者的修法完全不同：
过期断言改期望值即可；索引契约破裂必须让探针**按 key 定位**，否则下一次挪顺序又会红。

## 1.2 行数的真相：3110 行里只有 1034 行是代码

这是我本轮最重要的一个发现，它直接改变了「省多少行」的评价标准。

```
NeovideCursorConfigurable.java   3111 行
  ├─ 空行                          262 行   8.4%
  ├─ 注释                        1815 行  58.3%   ← 超过一半
  └─ 代码（含声明/花括号）        1034 行  33.2%
```

**含义**：如果拿「文件从 3110 行变成 2900 行」当交付指标，那省下来的多半是注释——
而这个项目的 CLAUDE.md 第零章明确写着「省 token 是错误行为」「把『为什么』写完整」。
**把注释砍掉换行数是负收益。**

所以本方案里所有「省 N 行」的估算，**一律只算真代码行**。

## 1.3 brief 里 6 个数字要更正

brief 给的样板重复计数是用 `grep -c` 直接数的，**把定义行和注释行也算进去了**。
逐个核对如下：

| 项 | brief 说 | 实测真实调用 | 差在哪 |
|---|---|---|---|
| `addSlider` | 17 | **14** | 2 处注释（381 行、2556 行）+ 1 处定义（1415 行） |
| `specOf` | 16 | **14** | 2 处注释（373 行、1521 行 javadoc） |
| `syncAllControlsFromPending` | 14 | **9 次调用** | 1 定义 + 4 处注释 |
| `bindCheck` | 12 | **8** | 1 定义 + 3 处注释 |
| `row.comboBox` | 5 | **4** | brief 多算 1 |
| `new ColorPanel` | 5 | **5，但只有 2 处在 group 里** | 另外 3 处是字段声明（561/628/632 行），不在 `build*Group` |
| `rowComment` | 18 | **16** | 2 处注释 |

**这些更正不是抠字眼**：后面「省 M 行」的估算全部建立在这些基数上，
基数错 20%，收益估算就错 20%。

其余数字核对无误：

```
NeovideCursorConfigurable.java   3110 行 ✓（wc -l 报 3111，差在末行无换行）
SettingsField.java                724 行 ✓
NeovideCursorSettings.java       1102 行 ✓
方法数                             94 ✓（brief 说 94~97，在区间内）
if 83 / for 17 / while 0          ✓
State 字段数                       30（brief 说 28，是 0.9.72 时代的数字）
SettingsField.FIELDS 条目数        27（brief 说 28，见 §3 判断 3）
```

## 1.4 「三份量程副本」的实测比对：目前是 14/14 完全一致

brief 判断 2 说「滑块量程同时存在于 SliderSpec / SettingsField / VUi 的 SPEC 表」。
我把三份逐项列出来比了：

| 字段 | `SliderSpec`（生产） | `VUi.SPEC`（探针） | 一致 | `loadState` clamp（换算成真值） | 一致 |
|---|---|---|---|---|---|
| `cursorDisappearDelay（淡出前保持）` | 0..500 / 50 | 0..500 / 50 | ✓ | 0..500 | ✓ |
| `cursorFadeOutDuration（淡出时长）` | 0..1000 / 25 | 0..1000 / 25 | ✓ | 0..1.0 s | ✓ |
| `tailOpacity（拖尾不透明度）` | 0..100 / 5 | 0..100 / 5 | ✓ | 0..1.0 | ✓ |
| `glowOpacity（辉光不透明度）` | 0..100 / 5 | 0..100 / 5 | ✓ | 0..1.0 | ✓ |
| `glowLayers（辉光层数）` | 1..12 / 1 | 1..12 / 1 | ✓ | 1..12 | ✓ |
| `shadowBlurFactor（辉光锐度）` | 0..100 / 1 | 0..100 / 1 | ✓ | 0.05..0.60 非线性 | n/a |
| `trailSize（拖尾密度）` | 0..100 / 5 | 0..100 / 5 | ✓ | 0..1.0 | ✓ |
| `stretchRatioK（拉伸强度）` | 10..80 / 5 | 10..80 / 5 | ✓ | 1.0..8.0 | ✓ |
| `slopeTicks（斜度）` | 0..100 / 5 | 0..100 / 5 | ✓ | 0..100 | ✓ |
| `animationLength（跨行动画）` | 5..35 / 1 | 5..35 / 1 | ✓ | 0.05..0.35 s | ✓ |
| `shortAnimationLength（短动画）` | 2..25 / 1 | 2..25 / 1 | ✓ | 0.02..0.25 s | ✓ |
| `typingSmoothness（输入平滑）` | 0..100 / 5 | 0..100 / 5 | ✓ | 0..1.0 | ✓ |
| `cursorWidthPx（光标宽度）` | 1..5 / 1 | 1..5 / 1 | ✓ | 1..5 | ✓ |
| `cursorHeightRatio（光标高度）` | 30..100 / 10 | 30..100 / 10 | ✓ | 0.3..1.0 | ✓ |

**两个必须写下来的事实：**

1. **`SettingsField.java` 里根本没有量程。** 我通读了 724 行，
   它只有 `key / kind / getState / setState / getConfig / setConfig / same /
   syncsToConfig / inPreset / runtimeSwitch` 这些，没有任何 min/max/step。
   **所以不是「3 个地方」，是「2 个地方 + 1 个换算后的真值域」。**

2. **当前零漂移。** 14 项全部一致。brief 提到的 0.9.62「量程表错位」事故，
   我查了真正的原因（见 `VUi.java:131-145` 的注释）：
   > 之前 VUi 用 SPECS 顺序，探针拿到的 key 与实际滑块错位…直到 0.9.79 加了第 14 个滑块
   > （slopeTicks）才全线暴露：面板[0] 量程 [2,25]（= cursorFadeOut 的旧值）却对应
   > KEYS[0]=cursorDisappearDelay。

   **根因是「声明顺序 vs 渲染顺序不一致」，不是「三处维护」**。而且已经修好了。

---

# 二、三个问题的答案

## Q1：有多少重复模式能真正消掉？

先给结论：**能消掉的重复总量约 50~60 行真代码，占全文件 1034 行真代码的 5~6%。**
brief 估的「200 行」不成立。

逐项拆解，每个 `build*Group` 都统计了。

**第一步：每个 group 的结构**

| 方法 | 行范围 | 总行 | row 数 | 滑块行 | 复选 | 下拉 | ColorPanel |
|---|---|---|---|---|---|---|---|
| `buildMasterGroup` | 1006-1072 | 67 | 6 | 0 | 4 | 1 | 0 |
| `buildInlineGroup` | 1073-1125 | 53 | 5 | 4 | 0 | 0 | 0 |
| `buildCrossGroup` | 1126-1184 | 59 | 6 | 2 | 0 | 0 | 0 |
| `buildColorGroup` | 1185-1207 | 23 | 2 | 1 | 0 | 0 | 1 |
| `buildGlowGroup` | 1208-1255 | 48 | 5 | 3 | 1 | 0 | 1 |
| `buildCursorGroup` | 1256-1291 | 36 | 3 | 2 | 1 | 0 | 0 |
| `buildFadeGroup` | 1292-1323 | 32 | 3 | 2 | 1 | 0 | 0 |
| `buildExperimentalGroup` | 1324-1356 | 33 | 2 | 0 | 0 | 1 | 0 |
| `buildDiagGroup` | 1357-1414 | 58 | 2 | 0 | 2 | 0 | 0 |
| **合计** | | **409** | **34** | **14** | **8** | **2** | **2** |

**第二步：34 个 row 块按形态分类（只算真代码行）**

我用一个脚本把每个 `g.row(...)` / `cur.row(...)` 块切出来，
剔掉三类样板行（`g.row("...", row -> {`、`return kotlin.Unit.INSTANCE;`、`});`），
剩下的叫「实义行」：

| 类别 | 处数 | 代码行 | 其中样板 | **实义行** | 平均实义 |
|---|---|---|---|---|---|
| 滑块行 | 14 | 71 | 28 | **43** | 3.1 |
| 复选行 | 8 | 54 | 16 | **38** | 4.8 |
| cell 行 | 6 | 35 | 12 | **23** | 3.8 |
| 下拉行 | 2 | 26 | 4 | **22** | 11.0 |
| 纯注释行 | 4 | 24 | 8 | **16** | 4.0 |
| **合计** | **34** | **210** | **68** | **142** | — |

**「整个面板的行构建」只有 142 行真代码。** 这就是 Q1 的天花板——
哪怕把 142 行全删了，也只占全文件 13.7%。

**第三步：能省多少（逐项）**

| 重复模式 | 处数 | 现状实义行 | 模板化后 | 省 | 可行性 |
|---|---|---|---|---|---|
| 滑块行 ·「`addSlider` + `rowComment` + `enabledIf`」 | 14 | 43 | 14 | **29** | 高 |
| 复选行 ·「`bindCheck` + `comment`」纯赋值型 | 6 | ~29 | 6 | **23** | 中（见下） |
| 复选行 ·带副作用 setter（`useShadow` / `overlayFadeOut`） | 2 | ~9 | — | **0** | 不能模板化 |
| `cell` 行（预览 / 提示标签） | 6 | 23 | 6 | **0** | 不建议（每处结构都不同） |
| 下拉行（`row.comboBox`） | 2 | 22 | — | **0** | 不建议（两处语义完全不同） |
| 纯注释行 | 4 | 16 | 4 | 0 | 无需 |
| **合计可省** | | | | **约 52 行** | |

**为什么「复选行」只能省 6 处的那 23 行**：

8 处 `bindCheck` 里有 2 处带额外动作——

```java
// 1212 行
bindCheck(row, () -> pending.useShadow, v -> { pending.useShadow = v; shadowOn.set(v); });
// 1296 行
bindCheck(row, () -> pending.overlayFadeOut, v -> { pending.overlayFadeOut = v; fadeOn.set(v); });
```

那个 `shadowOn.set(v)` 是 GraphProperty（联动置灰），不在 `pending` 里。
模板化要么给它一个额外参数（那就不省了），要么把 `graphReset` 的语义塞进模板
（那就变成「复选行模板偷偷知道 GraphProperty」——名字与行为不符，违反纪律四-J 第 3 条）。

**所以复选行的模板化只在「纯赋值」这 6 处成立，省 23 行，不是 brief 想的那种 8 处全消。**

**第四步：结论**

```
能省的真代码：约 52 行 / 1034 行 = 5.0%
若按文件总行算：52 / 3110 = 1.7%
```

这就是 Q1 的诚实答案。**「抽三件套」的价值不在省行数，在于「新增一个滑块」的边际成本。**

现状：新增一个滑块要写

```java
g.row("新参数名", row -> {
    addSlider(row, specOf("newKey"), springMode);                        // 1 行
    row.rowComment(W_NEW_KEY, COMMENT, NO_LINK).enabledIf(springMode);   // 1 行
    return kotlin.Unit.INSTANCE;                                          // 样板
});
```

模板化后：

```java
sliderRow(g, "新参数名", "newKey", springMode, W_NEW_KEY);
```

**从 5 行变 1 行。** 这个才是真收益——不是省了 29 行，而是「下一次加滑块只花 1 分钟」。

## Q2：8 处落点里哪些能合并？

**先纠正一个前提：这 8 个落点不是「同一件事写在 8 个地方」。
它们的性质完全不同，其中有 5 个携带不可消除的语义。**

| # | 落点 | 现状 | 性质 | 能不能合并 |
|---|---|---|---|---|
| ① | `State` 字段声明 | `NeovideCursorSettings.java:788-1025`，30 个字段 | **Java 语言要求**，字段必须显式声明 | **不能**。这是类型系统层面的事 |
| ② | `SettingsField.FIELDS` 注册 | 27 条 `out.add(...)` | **语义表**：携带 getter/setter/比较语义/进不进 CONFIG/进不进预设 | **不能合并掉**，但探针已守门（`VAddSetting` A-1/A-2） |
| ③ | `State.copyFrom()` | `:1031-1071`，25 行逐字段 `x = from.x` | **纯样板**，零语义 | **能，且应该**。可从 `FIELDS` 完全派生 |
| ④ | `loadState()` | `:108-230`，32 行 | **半样板**：每字段有 clamp，另有 5 处迁移 | **部分**。clamp 可表驱动，迁移必须手写 |
| ⑤ | 面板控件 | 9 个 `build*Group`，142 行实义代码 | **产品决策**：分组、顺序、文案、联动全是人定的 | **不能**。这是「加 UI 控件前先问用户」的地盘 |
| ⑥ | `syncAllControlsFromPending` | 1 个定义 + **9 个调用点** | **设计问题**，不是重复问题 | **不能靠模板解决**，见下 |
| ⑦ | 消费者 | 散在 `CaretAnimator` / `TrailCorner` / `CaretTrailPanel` | **语义接线**，必须人写 | **不能**。`targetFps` 事故就是漏在这一层 |
| ⑧ | `VUi` 探针量程表 | `VUi.java:130-179`，KEYS 14 项 + SPEC 14 项 | **自证禁忌**：探针必须独立于被测方 | **不能**（见 §2.3） |

**分三类给出结论：**

### 第一类：必须分开的（5 个，理由如上表）

①⑤⑦ 携带产品/语义内容，②是唯一的字段真相源，⑧是探针独立性的硬要求。
这 5 个不管怎么重构都不会消失。**这就是「8 → 1~2 处」做不到的根本原因。**

### 第二类：纯样板，给生成方案（1.5 个）

**③ `copyFrom()` —— 完全归零，25 行 → 约 6 行**

```java
public void copyFrom(State from) {
    if (from == null) {
        return;
    }
    stateVersion = from.stateVersion;          // 不在 FIELDS 里，必须单独保留
    for (SettingsField f : SettingsField.FIELDS) {
        f.copy(from, this);                    // ← SettingsField.copy 已经存在（:153）
    }
}
```

注意 `SettingsField.copy(from, to)` 在 `:153-155` **已经存在**。
这个能力早就写好了，只是 `copyFrom` 没用它。

但有一个坑：`FIELDS` 里有 `diagLogging` / `multiCursorNoGlow` / `cursorWidthPx` /
`cursorHeightRatio` / `rgbGradient` 这 5 个，`copyFrom` 现在是**分两段**写的
（前 24 行一段、`// 【0.9.51 修复】` 之后 5 行一段）。派生后两段合一，
那段「为什么分两段」的历史注释要挪到方法头，**不能直接删掉**——
它记录的是「这个 bug 修过两次」的事实。

**④ `loadState()` —— 半归零，32 行 → 约 15 行 + 迁移保留**

`loadState` 的 12 个 clamp 可以搬到 `SettingsField` 上，
但**有五处绝对不能自动化的**，必须继续手写：

| 迁移 | 位置 | 为什么不能表驱动 |
|---|---|---|
| `slopeTicks` 82→0 | `:211-217` | 这是**版本语义**（0.9.80 的出厂默认），不是范围校验 |
| `targetFps` 白名单 | `:200` | 只收 125/144，不是区间 |
| `animPresetBase` 重映射 | `:168-172` | 依赖 `CursorPresets.ANIM_PRESETS.length` |
| `tailColor` / `shadowColor` `sanitizeColor` | `:127,131` | 有 fallback 值，不是纯 clamp |
| `customAnimPresets` / `animCustomName` `null`→`""` | `:177-180` | 类型转换，不是规整 |

**所以 ④ 能省的很少（约 12 行），而且会让 `SettingsField` 增加一个字段。**
这是我建议**降级处理、只做 ③ 不做 ④** 的一项——理由见 §4 步骤 4。

### 第三类：探针（1 个）

**⑧ `VUi` 的 KEYS / SPEC —— 只改方向，不删断言，而且不能改成派生**

详见 §2.3。

## Q3：重构边界在哪？

### 会动的文件

| 文件 | 行数 | 改什么 | 风险 |
|---|---|---|---|
| `ui/settings/NeovideCursorConfigurable.java` | 3110 | 步骤 2 抽 `sliderRow()`；步骤 5 收口 `syncAllControlsFromPending` | **中** |
| `settings/NeovideCursorSettings.java` | 1102 | 步骤 4 的 `copyFrom` 派生 | **低**（有 `VAddSetting` B-1 守门） |
| `settings/SettingsField.java` | 724 | 步骤 4 只做 ③ 的话**不用改它**（`copy` 已存在） | — |
| `tools/settings-probe/VUi.java` | 305 | 步骤 3 加「渲染顺序 == KEYS 顺序」校验 | **低**（探针侧） |
| `tools/settings-probe/VDefects.java` / `VSnap.java` | 323 / 319 | 步骤 1 顺手修索引定位（改方向） | **低** |

### 绝对不动的文件

| 文件 | 理由 |
|---|---|
| `core/NeovideConfig.java` | **D0 门禁**，必须零 diff |
| `core/DampedSpring.java` / `core/TrailCorner.java` | 物理禁区，`PhysicsParity` 逐位比对 |
| `ui/CaretTrailPanel.java` | 绘制层，与设置系统无接口关系 |
| `settings/CursorPresets.java` | 预设数据表，被探针按索引断言（`VPreset` 42 条） |
| 任何 `docs/` 里的历史文档 | 不改历史记录 |

### 哪些探针会红、为什么

**步骤 2（抽滑块行模板）会碰的红：**

| 探针 | 会变吗 | 为什么 |
|---|---|---|
| `VSnap`（现 10 红） | **可能变多或变少** | 它按 `sliders.get(i)` 索引取控件。只要**渲染顺序不变**就不受影响；一旦抽模板时不小心改了 `addSlider` 的调用顺序，索引全错位 |
| `VAddSetting` C-2 | **会红** | 硬编码 `滑块 == 14 && 复选框 == 9 && 下拉 == 4`。数字本身不变就不红；但如果模板把某些控件包进了新容器，`VUi.build` 的扁平遍历会数错 |
| `VDefects` | 不受影响 | 用 `sliderByKey` 定位（正确路径） |
| 其余 6 红 | 不受影响 | 与面板结构无关 |

**结论：步骤 2 的硬约束是「渲染顺序与控件数量一个字节都不许变」。**
这不是靠小心，是靠验证——见 §5.2 的「改前改后控件指纹比对」。

**步骤 5（收口 `syncAllControlsFromPending`）会碰的红：**

它是 9 个调用点的唯一回灌路径（`onAnimPresetPicked` / `onColorPresetPicked` /
`restoreAnimPreset` / `restoreColorPreset` / `createCustomPreset` / `renameCustomPreset` /
`deleteCustomPreset` / `reset` / `apply`）。

一旦改错，`VReset`（18 条）/ `VReset2`（现 2 红）/ `VCustom`（现 1 红）/
`VRestoreDefault`（7 条）/ `VDefaultFreeze`（5 条）会大面积红。
这就是 brief 说「风险最高单点」的原因——**这个判断我完全同意**。

---

# 三、对 brief「四条判断」的逐条回应

brief 给了四条判断，说「可参考可反驳」。我逐条给结论：

## 判断 1：「最大重复是三件套，抽出来省 200 行」

**反驳。** 实测最多省约 52 行真代码（§2.1 第三步）。

省 200 行的算法一定是把注释算进了收益，或者把 34 个 row 块全部算成可完全消除。
前者违反项目「注释是资产」的取向，后者在数学上不成立（142 行天花板）。

**但你的直觉方向是对的**：三件套确实是最大的一块重复（14 处，43 行实义代码），
只是收益量级要打折。**真正的价值是「新增滑块从 5 行变 1 行」，不是省行数。**

## 判断 2：「同一份信息写在 3 个地方（SliderSpec / SettingsField / VUi）」

**一半对，一半错。**

- **错**：`SettingsField` 里没有量程（§1.4 事实 1）。是 **2 个地方**，不是 3 个。
- **错**：当前**零漂移**（14/14 一致，§1.4 事实 2）。
- **对**：`SliderSpec` 和 `VUi.SPEC` 确实是两份独立维护的量程表，**有漂移风险**。
- **但 0.9.62 那次事故的根因不是这个**，是「声明顺序 vs 渲染顺序」（§1.4）。

**所以这一条要修正为**：「有 2 份独立量程表，有漂移风险，但目前一致；
真正的历史事故根因是顺序不一致，那个已经修了。」

## 判断 3：「`SettingsField` 的 28 个 `out.add()` 不要动，它是地基」

**同意，但要更正一个数字，并补一个更强的理由。**

- 数字：实测是 **27 条** `out.add(...)`（26 条带 key + 1 条 `presetPointer()`）。
  brief 说 28，差 1。可能把 `buildPresetFields()` 里那个筛选循环的 `out.add(f)` 也算进去了
  （那个不是字段注册）。按「字段注册」计，是 27。

- **更强的理由**：这张表不只是「字段真相源」，它还是**探针的校验对象**。
  `VAddSetting` 的 A-1（每个 `State` 字段都在 `FIELDS` 里）和 A-2（`FIELDS` 里没有幽灵 key）
  都是直接读这张表。动它等于动探针的基准，会连带影响 6 条断言。

  **结论：不是「不要动」，是「改它必须先跑 `VAddSetting` 看 6 条断言」**——
  探针本来就是为改它准备的。

## 判断 4：「`syncAllControlsFromPending` 是最高风险单点，最后动，动之前先补行为探针」

**完全同意，而且我要加一条：这个探针现在就该补，不管后面做不做重构。**

理由是它已经有 9 个调用点，其中 6 个是预设相关操作。
现在**没有任何一个探针直接测试「这个方法把每个控件都刷对了」**——
`VReset` 测的是 reset 之后的状态，`VReset2` 测的是勾选保持，
`VCustom` 测的是派生存活，都是**间接**覆盖。

而它已经红过一次（`VReset2` 的 A-1：勾选在换预设后丢失）。

**具体补什么**（§4 步骤 5 有完整设计）：
造一个「所有字段都是非默认值」的 `pending` → 调 `syncAllControlsFromPending()` →
逐个断言「第 i 个控件的值 == `pending` 里对应字段的值」。
这是唯一能抓到「漏刷某个控件」的形态。

---

# 四、分步方案

> 每一步都是「一个 PR 大小」。每步独立可回滚。
> 每步都必须满足：D0 零 diff + 8 红名单不变（除非该步明确要修其中几条）。

## 步骤 1　摸底与基线固化（只读，本轮已完成）

**状态：✅ 已完成**

| 项 | 内容 |
|---|---|
| **目标** | 把「重构前长什么样」固化成可复现的数字，后面每步都拿它对比 |
| **范围** | 只读代码 + 跑门禁 + 统计。零生产改动 |
| **不改** | 任何文件（本文除外） |
| **产出** | 本文 §1；8 红名单 + 逐探针断言数；量程三副本比对表 |
| **验证** | 同一份命令跑两遍，失败名单与数字一致（已做，一致） |
| **回滚** | 无需（只读） |

**为什么这一步不能省**：没有基线，后面就分不清「省了」还是「搬走了」。
本项目吃过这个亏——`NEXT-STEPS.md` 里那个「72/82 通过」的数字来自从未执行过的脚本。

**顺带可做（推荐单独一个 commit）**：修 `VDefects:267-268` 和 `VSnap` 的索引定位，
把 `textFields.get(0)` / `sliders.get(0)` 改成 `textFieldOf(sliderByKey("cursorDisappearDelay"))`
（`VUi` 里这两个方法**都已经存在**，分别在 269 行和 275 行）。

**这是「改方向不删断言」的标准案例**：断言「键入 333 后标签显示 333」是对的，
错的是「用索引 0 去找那个输入框」。改定位方式，断言一字不动。

做完后预期：8 红 → **7 红**（`VDefects` 变绿），`VSnap` 从 10 条红降到 0~2 条
（`cursorHeightRatio` 那条跟索引无关，可能要单独看）。
**这个变化必须写进 commit message，并做控变量验证**（纪律四-G-2 的三行数字）。

## 步骤 2　抽「滑块行」模板

| 项 | 内容 |
|---|---|
| **目标** | 新增一个滑块从「写 5 行」降到「写 1 行」；14 处滑块 row 变成 14 行单调用 |
| **范围** | 只动 `NeovideCursorConfigurable.java` 的 14 处 `addSlider` 调用点 + 新增 1 个 `sliderRow()` 方法 |
| **不改** | `SliderSpec` 内容、`SPECS` 数组、`SettingsField` 表、任何探针、任何文案、**任何控件创建顺序** |
| **净减** | 约 29 行真代码（43 → 14），文件总行数约 -35 |
| **验证** | ① D0 零 diff ② 8 红名单不变 ③ **改前改后控件指纹比对**（§5.2） ④ 面板手工截图对比 |
| **退出条件** | 14 处调用点全部变成单行；`sliderKeysForProbe()` 返回的 14 个 key **顺序与改前逐字相同** |
| **回滚** | `git revert` 单个 commit。因为是纯机械替换，无状态残留 |

**设计草案**

```java
/**
 * 一条「滑块 + 说明 + 可选联动置灰」的标准行。
 *
 * <p>取代原来每处都要写的 3 件套：addSlider + rowComment + return Unit。
 * 三者必须同进同退 —— 只加滑块不加说明，用户不知道这个数字管什么；
 * 只加说明不加滑块，说明就成了悬空的文案。
 *
 * @param comment 为 null 表示本行**本来就没有**说明（3 处如此，不是漏写）
 */
private void sliderRow(Panel.Group g, String title, String key,
                       GraphProperty<Boolean> enabledIf, String comment) {
    g.row(title, row -> {
        addSlider(row, specOf(key), enabledIf);
        if (comment != null) {
            Cell<?> c = row.rowComment(comment, COMMENT, NO_LINK);
            if (enabledIf != null) {
                c.enabledIf(enabledIf);      // ← 注释也要跟着置灰，见下面第 3 条
            }
        }
        return kotlin.Unit.INSTANCE;
    });
}
```

**必须写进代码注释的四条约束**：

1. **3 处传 `null`**：`buildColorGroup:1198`（拖尾不透明度）、
   `buildGlowGroup:1227`（辉光峰值不透明度）、`buildFadeGroup:1309`（淡出时长）。
   这 3 处**本来就没有说明**，不是漏了。模板不能「统一给个默认文案」——
   那会增加用户可见文案，违反硬约束 4。

2. **`enabledIf` 有两个责任人**：`addSlider` 内部已给滑块 + 输入框 + 标签加 `enabledIf`
   （`:1493-1497`），注释不在其中。所以模板要**再给注释加一次**。
   漏了它 → 「辉光关着时，说明文字是黑的、滑块是灰的」——观感不一致。

3. **`buildInlineGroup` 的两处只有注释置灰**（`:1098`、`:1110`）：
   滑块传了 `springMode`，注释也 `.enabledIf(springMode)`。模板行为必须与之一致。

4. **`buildInlineGroup:1081`（短动画时长）的注释没有 `enabledIf`**——
   它的 `enabledIf` 传 `null`，注释自然也不置灰。这条不影响模板结构。

## 步骤 3　让「滑块渲染顺序」可校验（**推荐的重点，取代 brief 步骤 3 的原方案**）

| 项 | 内容 |
|---|---|
| **目标** | 让「面板实际渲染出 14 个滑块的顺序」与 `VUi.KEYS` 的**逐项一致**成为一条门禁断言，而不是靠人记忆 |
| **范围** | 探针侧：`VUi` 加一个 `assertOrderMatches(conf)` 方法，由 `VAddSetting` 调用（那里已经有建好的面板实例，零额外开销） |
| **不改** | `VUi.SPEC` 的数值、`VUi.KEYS` 的内容、`SliderSpec` 的任何量程、任何生产代码 |
| **验证** | **变异测试**：把 `buildInlineGroup` 里两个 `sliderRow` 对调 → 新断言必须红；恢复 + 删 classes 全量重编译 → 全绿 |
| **退出条件** | 新断言能抓住「顺序错位」，且当前代码下为绿；不新增红 |
| **回滚** | 只加断言不加生产逻辑，回滚等于删掉那十几行探针 |

**为什么不用 brief 原方案（让 VUi.SPEC 从 SliderSpec 派生）——这是本文最重要的一节：**

`VUi.java:119-120` 已经写明了理由，我原样引用：

> 这张表是探针侧的独立副本（不读生产代码的 SliderSpec），因为它就是被校验的对象 —
> **用被测方的值去校验被测方，等于自证。**

这是纪律四-G 的直接应用（「探针必须调用生产方法，不许自己实现一遍算法再验证自己的实现」）。
但四-G 说的是**算法**不能自写；**校验用的基准值**恰恰相反——必须独立。
如果 `VUi.SPEC` 从 `SliderSpec` 派生，那么「改生产代码的量程」和「改探针的期望值」
会同时发生，探针**永远不可能红**，量程漂移就再也抓不到了。

**打个比方**：考试答案不能从学生自己的答卷里抄。那样每次都是 100 分，
但那个 100 分什么也不说明。

**所以正确的目标是**：
- **量程**：保持两份独立。比对已经存在（`VSnap` 的 `SNAP-0b`），不需要新做。
- **顺序**：这是唯一真正缺的守卫。补上它。

**这条断言应该长这样**（伪代码）：

```
// 面板实际渲染顺序（走生产代码的 slidersForProbe()）
List<String> actual = conf.sliderKeysForProbe();
// 探针独立维护的顺序
List<String> expected = Arrays.asList(VUi.KEYS);
check("顺序契约：面板渲染顺序 == VUi.KEYS", actual.equals(expected),
      "实际=" + actual + " 期望=" + expected);
```

**关键点**：`expected` 来自探针自己维护的 `KEYS`（独立基准），
`actual` 来自生产代码。两者比对才有意义。

## 步骤 4　字段表派生 `copyFrom`（**建议降级：只做 copyFrom，不做 loadState**）

| 项 | 内容 |
|---|---|
| **目标** | `copyFrom()` 从 25 行手写变成 `FIELDS` 遍历，漏拷某字段**在结构上不可能** |
| **范围** | `NeovideCursorSettings.java:1031-1071`（约 25 行 → 约 6 行） |
| **不改** | `SettingsField.java`（`copy` 已存在于 `:153`，不用加东西）；`SettingsField.FIELDS` 表；`loadState` |
| **净减** | 约 19 行 |
| **验证** | `VAddSetting` B-1（copyFrom 覆盖全部字段）+ `VLoadState` L-1/L-2 必须保持绿；再做一次变异测试 |
| **退出条件** | `VAddSetting` 6 条全绿；从 `FIELDS` 临时注释掉一条 `out.add(...)` 时，A-1 与 B-1 **都**会红 |
| **回滚** | 单 commit revert |

**为什么不做 `loadState` 表驱动：**

`loadState` 的 32 行里只有 12 行是纯 clamp，其余全是**语义迁移**（§2.2 第二类已列表）。
表驱动这些需要给 `SettingsField` 加 `normalize` 字段，
**结果是 `SettingsField` 变复杂、`loadState` 只省 12 行、迁移逻辑还是手写。**
投入产出比不成立。

**而且它有一个隐藏的语义陷阱**：`copyFrom` 是「原样深拷贝」，
`loadState` 是「拷贝 + 规整」。如果两者都表驱动，很容易让人以为它们同构——
**但它们从来就不同构**（这正是 0.9.72「重启即失效」bug 的根源：
有人以为 `loadState` 也是纯拷贝，于是漏了字段）。

**保留这个差异，比消除它更安全。**

## 步骤 5　`syncAllControlsFromPending` 收口（最高风险，最后做）

| 项 | 内容 |
|---|---|
| **目标** | 先回答「它到底该不该改」，再决定改不改 |
| **前置** | **先补一个行为探针**（见下），无论后面改不改 |
| **范围** | 探针侧：新增 `VSyncAll.java`；生产侧（若判定要改）：`NeovideCursorConfigurable.java:1668-1718` |
| **不改** | 9 个调用点的位置（它们各有业务理由）；`super.reset()` 与 `graphReset()` 的调用（平台机制） |
| **验证** | 新探针 + 8 红不变 + 面板手工验证（换预设 / 恢复默认 / 应用三路径，§5.5） |
| **退出条件** | **二选一**：① 新探针全绿且证明改动无回归 → 合入；② 明确判定「不值得动」→ 写清理由，关掉这一项 |
| **回滚** | 新探针独立 commit；生产改动独立 commit，分开 revert |

**先补的探针设计（这才是本步真正的价值）**

```
VSyncAll 的四段：

A. 全覆盖性 —— 造 pending（10 个可编辑字段全部设成非默认值）
              → 调 syncAllControlsFromPending()
              → 断言「14 个滑块 / 8 个复选框 / 4 个下拉 / 2 个 ColorPanel
                      的每一个值都等于 pending 对应字段」
   ↑ 这是唯一能抓到「漏刷某个控件」的形态

B. 幂等性 —— 连调两次，控件值不变（防止 setValue 触发 ChangeListener 回写 pending）

C. 抑制态正确 —— syncingControls 必须复位（走异常路径也要复位）

D. 9 个调用点抽样 —— 切内置预设 / 切自定义预设 / 恢复默认 / 应用 四条路径
                    断言「调完之后，控件值 == pending，且 pending 未被控件反冲」
   ↑ VReset2 的 A-1 就是这个形态的红，现在没人守
```

**我的预判（先说，跑完再校正）**：这个方法的实现本身写得不错——
`super.reset()` 用平台完整路径、`graphReset` 单独补、`try/finally` 单块恢复
（注释里写明「两个 finally 时第一段抛异常会让第二段被跳过」）。**结构上没有明显缺陷。**

**真正的问题不在这个方法里，在它外面**：9 个调用点意味着「谁改了 pending 谁就得记得调它」，
而这是一份**没有守门的行为约束**。

所以我的建议是：**先补探针（A/B/C/D 四段），跑完看结果再决定。**
如果探针全绿，说明现状是对的，那么「收口」这件事**可能根本不值得做**——
把 9 个调用点合并成一个「改动 pending 的收尾钩子」，会让非预设路径（如 `reset`）
也被迫走预设路径的语义，那是引入 bug 而不是消除 bug。

---

# 五、每步的「改完怎么证明没改坏」

## 5.1 通用门禁（每步必跑，且**不许并发**）

```bash
# ① D0：NeovideConfig 必须零 diff
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java

# ② 全量探针（在 worktree 里必须带 -Repo，否则编译主仓库代码给假绿）
pwsh -NoProfile -ExecutionPolicy Bypass \
  -File tools/settings-probe/build-v-probes.ps1 \
  -Repo "D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\sleepy-swanson-ffc2cf"
```

**⚠️ 不要并发跑两份。** 共用 `build\v2-probe\classes`，编译第一步就 `Remove-Item`，
另一份正在加载探针主类时会 `ClassNotFoundException`，失败数从 8 暴涨到 18/19。
**看到失败数暴涨，先重跑一次再判断**（纪律四-G-2）。

## 5.2 步骤 2 专用：改前改后「控件指纹」比对

这是本方案里我最推荐的一个新工具。**滑块行模板化最容易出的错是「控件顺序悄悄变了」，
而 8 红基线抓不到它**（那 10 条索引相关的红本来就是红的，变动被判为「还是红的」）。

做法：写一个一次性的幂等脚本（不用进正式套件），输出一行指纹：

```
slider[0..13]  : shortAnimationLength, typingSmoothness, stretchRatioK, slopeTicks,
                 trailSize, animationLength, tailOpacity, cursorWidthPx,
                 cursorHeightRatio, glowOpacity, glowLayers, shadowBlurFactor,
                 cursorDisappearDelay, cursorFadeOutDuration
checkbox[0..7] : <8 个文案>
combo[0..3]    : <4 个文案>
sliderRanges   : <14 组 min/max/step>
```

**改前跑一次存下来，改后跑一次 diff。** 零差异才算过。

（`ZzSliderOrder.java` 已经在打印类似信息，可以复用它，只是要补 checkbox / combo 两段。）

## 5.3 步骤 3 专用：变异测试（证明新断言抓得住）

```
① 当前代码 → 新断言绿
② 把 buildInlineGroup 里 slopeTicks 和 stretchRatioK 两行对调 → 新断言必须红
③ 恢复源码 + 删 classes 全量重编译 → 必须回绿
```

**第 ③ 步必须删 classes。** 增量编译会复用旧字节码，你测的是旧代码
（纪律四-G 明确记录过这个坑）。

## 5.4 步骤 4 专用：变异测试（证明 copyFrom 派生真的等效）

```
① FIELDS 派生版 → VAddSetting B-1 绿 + VLoadState L-1/L-2 绿
② 故意从 FIELDS 里注释掉一个字段的 out.add(...)
   → VAddSetting A-1 必须红（说明探针抓得住漏注册）
   → copyFrom 也跟着漏这一字段（这证明派生是真的在遍历表，不是假的）
③ 恢复 + 全量重编译 → 全绿
```

## 5.5 步骤 5 专用：新探针 + 手工三路径

面板手工验证清单（用户可见行为零变化的验收）：

```
□ 打开设置 → 所有控件显示当前值
□ 切「基础」→ 切「缎带」→ 切「纯平滑输入」→ 辉光/淡出复选框跟着变
□ 新建自定义预设 → 改参数 → 切走 → 切回，参数完整回来
□ 点「恢复默认」→ 所有控件回到默认值
□ 拖一个滑块 → 「应用」按钮亮 → 点应用 → 不亮
□ 键入精确值 333 → 应用 → 重启 IDEA → 还是 333
```

---

# 六、明确「不做什么」及理由

这一节和方案本身一样重要。**本项目的两个最大事故都来自「顺手多做一点」。**

| # | 不做 | 理由 |
|---|---|---|
| 1 | **不动 `SettingsField.FIELDS` 的 27 条 `out.add()` 结构** | 它是探针 `VAddSetting` A-1/A-2 的校验对象。改它可以，但必须同步跑 6 条断言，且收益（省几行）远小于风险 |
| 2 | **不让 `VUi.SPEC` 从 `SliderSpec` 派生** | 自证禁忌（§2.3 / §4 步骤 3）。派生后探针永远抓不到量程漂移 |
| 3 | **不做 `loadState` 表驱动** | 32 行里只有 12 行是纯 clamp，其余是版本迁移。省 12 行换 `SettingsField` 加一个字段，投入产出不成立 |
| 4 | **不合并 9 个 `syncAllControlsFromPending` 调用点** | 除非步骤 5 的新探针证明现状有问题。预设路径与非预设路径（`reset`）的语义不同，强行合并会引入 bug |
| 5 | **不重组 `build*Group` 的分组与顺序** | 分组是产品决策（「加 UI 控件前先问用户」），而且顺序是探针索引契约的一部分。**这是独立项目，不跟本次混**（`REFACTOR-FEASIBILITY.md` 也持此判断） |
| 6 | **不改 `buildCrossGroup` 注释里的「6 → 7 → 8」顺序约束** | 那是 `Probe4Bugs` 按索引硬编码的历史契约，注释里写明了改顺序会红 |
| 7 | **不删 `docs/REFACTOR-FEASIBILITY.md`** | 它的「第一优先」已在 `4bbbcfc` 完成。这是历史记录，说明「当时为什么这么判断」 |
| 8 | **不动 `core/` 下任何文件** | D0 门禁 + 物理禁区 |
| 9 | **不为「减少 if 数量」而重构** | brief 提到 `if=83`。我逐个看了这 83 个 if 的形态（见下），**没有一个是可以靠抽函数消除的结构性重复**。为指标而重构 = 制造死代码 |
| 10 | **不在本次修那 6 个无关红灯** | `VTools` / `VHints` / `VCustom` / `VReset2` / `VSepBound` / `VSleep` 与设置系统零交集。混做会让「哪个改动导致哪个数字变了」无法归因（纪律三 / 四-G-2） |

**第 9 条的展开（83 个 if 的形态分布）**：

```
enabledIf / 联动置灰        约 20 处   业务必要
空值 / null 判断            约 15 处   防御必要
文案分支（模式 / 预设名）    约 20 处   显示逻辑
范围 / 边界判断             约 15 处   正确性必要
其余（状态机 / 回调保护）    约 13 处   并发与生命周期必要
```

**没有一处是「同一段判断抄了 5 遍」那种可消除的重复。** 所以「减 if」不是本次的目标。

---

# 七、需要你拍板的产品决策

按纪律四-L，我只问「答错了你能看出来」的问题。

## 决策 1：这次重构做几步？

**建议：做步骤 1 + 2 + 3 + 4(只 copyFrom)，步骤 5 只补探针不改实现。**

| 步骤 | 收益 | 风险 | 建议 |
|---|---|---|---|
| 1 摸底 | 基线（已完成） | 零 | ✅ 已做 |
| 2 滑块模板 | 新增滑块 5 行→1 行 | 低 | ✅ 做 |
| 3 顺序守卫 | 防「拖 A 滑块 B 变了」 | 低 | ✅ 做（**我最推荐的一项**） |
| 4 copyFrom 派生 | 漏拷字段在结构上不可能 | 低 | ✅ 只做 copyFrom |
| 5 syncAll 收口 | 不确定 | **高** | ⚠️ 只补探针，先不改实现 |

**合计约 50 行真代码净变化，4~5 个独立 commit。**
这不是一个「大重构」，是一个「四小步加固」。

## 决策 2：面板一行都不改，你接受吗？

**本方案 0 个用户可见变化**：没有新控件、没有新文案、没有控件挪位、没有量程变化。

这是硬约束（brief 硬约束 4）。但如果你其实**希望**顺手整理面板（比如把参数重新分组），
那是另一个项目，必须在本次之外单独做——因为「整理分组」＝「改控件顺序」＝「探针索引全错位」，
必须配套改 8+ 个探针，风险面完全不同。

## 决策 3：那 8 个红灯怎么办？

**本方案不动它们**（除了步骤 1 顺手修的 `VDefects` / `VSnap` 索引问题）。

其余 6 个与设置系统重构**零交集**，各自是独立的 bug 或过期断言：

```
VTools    M-11   预设域变化计数 2 vs 6      → 可能是真 bug（预设多改了 2 个字段）
VHints    H-6    提示文案缺「280 ms」        → 文案或派生值变了
VCustom   C-19   未改动时派生了副本          → 真 bug（不该派生）
VReset2   A-1    勾选换预设丢失              → 真 bug（paintDiag 路径）
VSepBound 7 条   名字含分隔符被静默丢弃      → 真 bug（数据丢失）
VSleep    S-4    Timer 精度 5.78ms           → 环境相关，可能该改判据
```

**建议：这 6 个单独开一轮排查，不要混进重构。**（纪律三「一次只改一件事」）

---

# 八、一页速查表

```
基线 4bbbcfc · D0 零 diff · 8 红（VDefects VSnap VTools VHints VCustom VReset2 VSepBound VSleep）

行数真相      3110 行 = 1034 代码 + 1815 注释 + 262 空行
行构建真相    34 个 row 块 = 210 代码行 = 142 实义行（天花板）
可省上限      约 52 行真代码（5% of 代码 / 1.7% of 总行）
8 处落点      5 个必须分开 + 1 个纯样板(copyFrom) + 1 个半样板(loadState) + 1 个探针
              → 8 → 6 是现实上限，不是 1~2
量程副本      是 2 份（不是 3 份；SettingsField 无量程），当前 14/14 零漂移
8 红归因      只有 11 条（VDefects 1 + VSnap 10）与控件索引相关，其余 34 条无关

最强的两个反对
  反对 1  三件套抽不出来 200 行，最多 52 行
  反对 2  VUi.SPEC 绝不能从生产派生（自证禁忌，VUi.java:119）

最推荐的一项   步骤 3「滑块渲染顺序守卫」——它防的是「拖 A 滑块 B 变了」这类最难查的 bug
最高风险一项   步骤 5 syncAllControlsFromPending（9 个调用点，先补探针再决定改不改）
```
