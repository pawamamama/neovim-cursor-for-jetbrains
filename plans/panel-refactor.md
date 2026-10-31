# 设置面板二次重构（P11）—— 施工计划

> 依据：`docs/DECISIONS-PANEL-2026-10-31.md`（用户逐字决策）+ `docs/PROJECT-DECISIONS.md §四 P11`
> 开工 HEAD：`ea3f12d`；分支 `main`；与 `fork/main` 同步 0 0
> 门禁基线（本轮实测，非手抄）：**102 个探针 / 1103 PASS / 2 FAIL（仅 VRegr2，informational）/ `V-PROBES RESULT: ALL PASS`**
> └ VRegr2 那 2 条红之后仍抛 NPE 崩溃 —— 是**已知存量状态**（见 memory `probe-regr2-red-with-npe`），本轮不碰。
> 可视化草稿：`build/panel-mockup.html`（七页可切换、改前 / 改后一键对比；未入库的一次性产物）

---

## 零、Research 结论（先证事实，再谈改法）

### 0.1 `make-interfaces-feel-better` 取了哪几条

该技能是 Web 向的，圆角 / 阴影 / 动效 / `transition` 全部不适用 Swing。取三条可迁移原则：

| 原则 | 原意 | 本项目落地方式 |
|---|---|---|
| **Spacing（间距要有节奏）** | 间距成体系，不写零散像素 | 只用平台 DSL 的 `TopGap` / `BottomGap` 三档（NONE/SMALL/MEDIUM），不自创像素值；「组间 > 行间」的层级 |
| **Text Wrapping（别让文字被截断）** | 换行交给渲染引擎，不要硬截 | 说明文字必须走**会渲染 HTML 的组件**（`rowComment` 的 `DslLabel`）；纯 `JLabel` 不换行、不解析标签 —— 这正是本次 bug 的根因 |
| **Alignment（视觉对齐）** | 相邻元素要视觉上成组 | 预览窗与右侧尺寸块用 `Cell.verticalAlign(CENTER)` 对齐，而不是各自顶端对齐留出参差 |

### 0.2 平台 DSL 的事实（javap + 实测，不是推测）

```
Row.rowComment(text, w, action) → UtilsKt.createComment() → DslLabel
DslLabel extends JEditorPane，contentType=text/html，
  updateEditorPaneText() 把用户串包进 HtmlChunk.body() 再 setText
  → <br/> 会被解析成真正的换行
DslLabel 拒绝的标签（会抛 UiDslException）：<html> </html> <body> <a href="">
  → 所以「给 comment 加 <html> 前缀」这条路【不可行】
```

**实测证据**（临时探针，见 §五「验证方法」）：

```
DslLabel doc = [前一句\n后一句]          ← <br/> 变成真换行（document 里是 \n）
DslLabel kit = com.intellij.util.ui.JBHtmlEditorKit
JLabel 明文   = [前一句<br/>后一句]      ← 字面量原样显示
JLabel html前缀 = [<html>前一句<br/>后一句</html>]  ← 走 HTML 渲染
```

### 0.3 由此推翻任务书的一处前提（诚实记录）

任务书与 `DECISIONS-PANEL-2026-10-31.md §1-2` 写的是「`row.rowComment` 不解析 HTML，所以 `<br/>` 以字面量显示」。
**实测不成立**：`rowComment` 走 `DslLabel`（JEditorPane + text/html），24 处 `<br/>` 全部正常换行。

真正把 `<br/>` 漏到界面上的是**另一个东西**：

```
NeovideCursorConfigurable.java:2940-2966  crossMoveHint = new JLabel()   ← 普通 JLabel
                                          setText("...<br/>实际拖尾约为该值的 2.6 倍...")
```

`JLabel` 不解析 HTML → 字面量显示；而且 `JLabel` 不换行 → 文本超出面板右边界。
两条症状（AC-003 超界 + AC-004 字面量）**是同一个根因**，都在这一个标签上。
用户截图里「列手感页最后一行被截断」就是它。

### 0.4 宽度实测（决定 2.1 能不能做成「并排」）

| 对象 | 实测 preferred 宽度 | 说明 |
|---|---|---|
| 单行预览窗 | 520 × 110 | 锁定，不许改 |
| 跨行预览窗 | 300 × 150 | 同上 |
| 旧 `crossMoveHint`（单行文本 + 字面 `<br/>`） | **1160** | 超界元凶（用户实际看到的） |
| 旧 `singleMoveHint` | 657 | 用户没抱怨 → 视为可接受上限 |
| 「光标尺寸」右侧块（含组框 + 原行标题） | 450 | 独立 `DialogPanel` |
| 同上但行标题缩短为「光标高度」 | 402 | |
| 原型「预览行放组外」整页 | 938 | **原型值，不是最终值**（见下方更正） |

> **【更正 2026-10-31】最终实测：新面板最宽 1174px（标签 3），不是 938。**
> 原型里没有真实滑块（`JSlider` 首选宽 200）、没有长说明，所以偏小。
> 判断「够不够宽」的正确口径是**与改前对比**：
> 改前最宽 **1332**（被 1160px 的 `crossMoveHint` 撑开），改后 **1174** ——
> 同一条线左移 158px，**不可能比以前更需要宽窗口**。
> 用户截图里那扇设置窗口：拿 520 宽的预览窗当尺子量，内容区约 1400（±10%），
> 余量 ~230px；即使他们把窗口缩窄到改前能用的程度，新面板也只会更宽松。

> 「组外」= 与辉光页同款做法：预览行放 `p.row(...)`，参数组另起 `p.group(...)`。
> 必要性实测：用 `row.panel{...}`（不新建 DialogPanel）时，嵌套行会**并进同一张 grid**，
> 列宽与预览列共享 → 整页被撑到 **1173**。所以必须用独立 `DialogPanel` 放进 `row.cell(...)`。

---

## 一、逐项改法

### 2.1 光标行手感页：预览窗左移，光标尺寸并排到右侧 ★头号目标★

**改前**

```
标签3「光标行手感」
└─ 组「单行输入输出（打字 / 退格）」
     [预览窗 520×110]           ← 缩进在组内，左右留白
     -- 短动画时长（打字/退格）
     -- 输入平滑（拖尾形状 · 全局）
     -- 拉伸强度（行内后缘）
     -- 斜度（单行倾斜）
└─ 组「光标（尺寸 / 颜色效果）」   ← 组名里的「颜色效果」早就是空的
     -- 光标宽度
     -- 光标高度（% 行高）
（页高 738，要往下滚才看得到宽/高）
```

**改后**

```
标签3「光标行手感」
  [预览窗 520×110]  |  组「光标尺寸」   ← 同一行、垂直居中对齐
                    |   -- 光标宽度
                    |   -- 光标高度（% 行高）
└─ 组「单行输入输出（打字 / 退格）」
     -- 短动画时长（打字/退格）
     -- 输入平滑（拖尾形状 · 全局）
     -- 拉伸强度（行内后缘）
     -- 斜度（单行倾斜）
```

实现要点：
1. 与「辉光」页同款：`p.row("", row -> { row.cell(inlinePreview()); row.cell(sizePanel()); })`
2. `sizePanel` = 独立 `DialogPanel`（`BuilderKt.panel`），内部一个 `panel.group("光标尺寸", true, ...)`
3. **必须** `outer.registerIntegratedPanel(sizePanel)` —— 否则尺寸两项「改了不生效」，
   这正是 `VTabPages` 注释里记录过的静默失效（陷阱 2 家族）
4. 两个 cell 都 `.verticalAlign(VerticalAlign.CENTER)`（视觉对齐）
5. 组名「光标（尺寸 / 颜色效果）」→ 内嵌组名「光标尺寸」（§十 点名的过期文案自然消失）

**不动**：`AnimationPreview.getPreferredSize()` 一行不改（AC-002）。

### 2.2 + 2.4 列手感页：文案超界 + `<br/>` 字面量（同一根因）

`singleMoveHint` / `crossMoveHint` 是**普通 JLabel**。改法：

- 文本整体包 `<html>…</html>`（JLabel 走 HTML 渲染）
- 用已有的 `BR` 常量显式分行，每行控制在 **≤ 450px**（旧 657px 已被用户接受，留余量）
- 保留「一条提示」的语义（`:215` 的注释明确反对拆成多条 `rowComment`）

新文案（数字仍由 `refreshDerivedHints()` 运行时计算，不写死）：

```
单行动画时长 = 短动画时长 × 拉伸系数
后缘 {n} ms / 横向前缘 {n} ms
（系数 {x}×，已含输入平滑 {p}% 的压平效果）。

跨行动画时长：DampedSpring 时间常数 —— 后缘 = {n} ms；前缘 = {n} ms
（拖尾密度 {x}，为 1.00 时前缘只剩 1 帧）。
实际运动时长约为该值的 2.6 倍（示例：0.35 s → 约 920 ms / 115 帧）。
```

实测宽度：单行 **266px**、跨行 **412px**（原来 657 / 1160）。
字段类型仍为 `JLabel` → `couplingHintsForProbe()` 签名不变 → **VHints 一行不用改**。

> 词句微调说明：「实际拖尾约为该值的 2.6 倍」→「实际运动时长约为该值的 2.6 倍（示例：…）」，
> 把「2.6 倍」从『普遍规律』的口径降为『实测示例』的口径 —— 它是 t10 在 dt=8ms 下量出来的，
> 不是解析解。数字来源保留，不引入新数字。

### 2.3 特殊 / 调试页：折叠组改平铺

```
collapsibleGroup("实验性（官方不建议使用）", false, …)  →  group("实验性（官方不建议使用）", true, …)
collapsibleGroup("高级（诊断）",            false, …)  →  group("高级（诊断）",            true, …)
```

- 警示文案不删：组名里的「官方不建议使用」+ 组内那条 ⚠ `rowComment` 全部保留
- 两页内容本来就短（实测折叠组展开后各只有 1~2 行），平铺不会把页面撑高

### 2.5 适度压缩留白

只动一处参数：**把「预览行 / 组首行」的 `TopGap.MEDIUM` 降为 `SMALL`**。
涉及 8 处（`buildPresetRows` / `buildMasterGroup` / 三条预览行 / `buildGlowGroup` / `buildFadeGroup` / `buildDiagGroup`）。
不动：预览窗尺寸、控件高度、字号、组间分隔线。

> 「动画窗口内的上下边缘」：`AnimationPreview` 的内容由 `contentTop()` 按行高**居中**绘制，
> 内部没有额外固定留白可压 —— 这一点会在报告里附证据说明，而不是去改绘制代码（白名单不允许）。

---

## 二、探针影响与处理（全部为「契约变更，非缺陷」）

| 探针 | 断言 | 本次是否变 |
|---|---|---|
| `VAddSetting.java:115` | 滑块 14 / 复选框 9 / 下拉 4 | **不变**（只挪位置，不增删控件） |
| `VerifyPanel.java:97/104/108` | 同上三个数 | **不变** |
| `VTabPages.EXPECTED_TITLES` | 7 个标签名 | **不变** |
| `VTabPages.EXPECTED_COMPOSITION` | 每页控件构成 | **不变**（尺寸两项仍属标签 3） |
| `VTabPages.T-6` | 集成面板数 == 标签页数 | **不变**：实现改用 `Row.panel { }`（官方指南推荐的多列写法，绑定仍在同一个 DialogPanelConfig 上），不新增集成面板 |
| `VUi.KEYS` / `SPEC` | 滑块渲染顺序与量程 | **要改**：标签 3 内顺序变成 `cursorWidthPx, cursorHeightRatio, shortAnimationLength, typingSmoothness, stretchRatioK, slopeTicks` |
| `VHints` H-1..H-11 | 两条提示存在 / 含运行时数字 | **不改**（`getText()` 仍含 `100 ms` 等子串） |

`VUi.KEYS` 是**渲染顺序契约**，挪控件必须连位置一起挪（CLAUDE.md §7.0 C），不能追加到末尾。

---

## 三、§十 文案一致性检查（逐项）

| 检查项 | 结论 | 处理 |
|---|---|---|
| 组名「光标（尺寸 / 颜色效果）」 | 组里只剩尺寸，「颜色效果」是空的 | 该组消失 → 内嵌组名「光标尺寸」 |
| 组名「单行输入输出（打字 / 退格）」vs 组内 4 项 | 一致 | 不动 |
| 「多行缎带（换行 / 跳转）」vs 组内 | 一致 | 不动 |
| 「颜色」「辉光」「淡出」「总开关」「实验性」「高级（诊断）」 | 一致 | 不动 |
| `PRESET_SCOPE_HINT` 列的 9 个「全局」字段 | 与 `SettingsField` 的 9 个 `runtimeSwitch` 一致 | 不动，报告里附核对方法 |
| `buildDiagGroup` javadoc 里「（1326 行）」 | 行号早漂了 | 删掉行号引用 |
| 「10 个滑块」类注释（328 / 637 / 3531 / 3558 行） | 实际 14 个 | 改为不写死数量的表述 |
| 交叉引用 `TrailCorner.jump()` `:209-221` | 需复核行号 | 复核后保留或改文案 |

**界限**：只改文案/注释。查出「文案说 A、实现做 B」一律**只登记、不修**。

---

## 四、实施顺序

1. 2.3 平铺（最小改动，先验证「改完能编译、门禁怎么变」）
2. 2.2/2.4 两个提示标签改 HTML + 分行（独立、可单独验证）
3. 2.1 预览 + 光标尺寸并排（最大改动，含 `registerIntegratedPanel`）
4. 2.5 间距
5. 探针常数统一调整（`VUi.KEYS/SPEC`、`VTabPages.T-6`）
6. 全量门禁 + D0 + 预览窗尺寸零 diff 校验

## 五、验证方法

- 构建：`build-v-probes.ps1`（一次编译生产 + 探针）
- 量化：临时测量探针（放 `%TEMP%`，不进仓库）逐页打印 preferred 尺寸，
  证明「标签 3 整页宽 ≤ 987（旧 `总开关` 页）」「两条提示 ≤ 450px」
- 门禁：对比基线的 **1103 PASS / 2 FAIL**，逐条说明新增红
- D0：`git diff --exit-code -- core/NeovideConfig.java`
- AC-002：`git diff -- ui/settings/AnimationPreview.java` 必须为空

## 六、实测结果（2026-10-31 本轮）

| 指标 | 改前 | 改后 | 说明 |
|---|---|---|---|
| 门禁 | 102 / 1103 PASS / 2 FAIL | **102 / 1103 PASS / 2 FAIL** | 完全一致，VRegr2 那 2 条为存量 |
| 外层面板 preferred 宽 | 1332 | **1174** | 收窄 158px |
| 外层面板 preferred 高 | 780 | **626** | 降 154px |
| 标签3 页高 | 738 | **584** | 一屏看完，不用滚 |
| 标签4 页高 | 466 | **356** | |
| 标签5 页高 | 638 | **460** | |
| `singleMoveHint` 宽 | 657（单行） | **266**（3 行） | |
| `crossMoveHint` 宽 | 1160（单行，超界） | **527**（3 行，最长一行） | 用户已接受的上限是 657 |
| 标签6 页高 | 40（折叠） | 95（平铺） | 折叠→平铺的必然结果 |
| 标签7 页高 | 40（折叠） | 150（平铺） | 同上 |
| D0 `core/NeovideConfig.java` | — | **零 diff** | |
| `ui/settings/AnimationPreview.java` | — | **零改动** | AC-002 |

七页实装截图（离屏渲染真实 Swing 组件，非 mock）：`build/render/01-*.png … 07-*.png`。

## 七、风险

| 风险 | 缓解 |
|---|---|
| 并排后整页超宽（真实对话框宽度未知，只能从「总开关 987 没被抱怨」反推 ≥987） | 实测整页 938 < 987；若仍不够，先缩行标题再考虑拆行 |
| 内嵌 DialogPanel 漏登记 → 改尺寸不生效 | `registerIntegratedPanel` + 门禁里 VReset / VerifyApplyConfig / VDefects 会红 |
| 换行点按字数硬写，DPI 变化后仍可能超界 | 用**实测像素**而不是字数；每行 ≤450px，用户已接受 657px |
| 折叠改平铺后页面变长 | 实测两页展开后各仅 1~2 行 |

---

## 八、第二轮改动（2026-10-31 晚 · 用户实机看过 0.9.110 之后）

### 8.1 用户反馈（原话）

> 不错很好，总开关预设控件对齐可以吗，有空间
> 还有所有的文案放的太随意了，先出方案

### 8.2 根因（实测，不是推测）

| 现象 | 根因 |
|---|---|
| 预设区三行按钮都顶在最左，与下拉不在一条竖线 | 预设区是**页级独立行**，DSL 给每行单独一套网格 → 「下拉在第 2 列」和「按钮在第 1 列」各算各的 |
| 同一页说明左缘有两个（x≈580 / x≈330） | 5 处用 `Cell.comment`（控件**右侧**），17 处用 `Row.rowComment`（控件**下方**） |

### 8.3 做法

1. **预设区**收进 `panel.group("预设", …)`，四行全部 `Row.layout(RowLayout.PARENT_GRID)`，
   动作行最前面放 `row.cell()` 占住标签列。
   实测列位：标签 21 / 下拉 97 / 恢复与动作首列 220 / 次列 / 末列。
2. **5 处右侧说明改到下方**（`Cell.comment` → `Row.rowComment`）：
   隐藏原生光标、常驻刷新、多光标时关闭辉光、光标渐变、诊断日志。
   规则：**说明永远在控件下方，左缘 = 所在容器的标题列**；子块内说明跟随子块标题列（缩进一级表示层级）。
3. **不删字**（用户选择「只改位置，不删字」）。

### 8.4 第二轮实测

| 指标 | 第一轮 | 第二轮 |
|---|---|---|
| 门禁 | 102 / 1103 PASS / 2 FAIL | **102 / 1103 PASS / 2 FAIL**（不变） |
| 总开关页高 | 542 | **623**（+81，说明移到下方） |
| 面板 preferred | 1174×626 | 1174×~700 |
| 探针契约变更 | VUi.KEYS/SPEC | **无新增**（说明换成 rowComment 后仍是 DslLabel） |

### 8.5 交付物

- 实装：`v0.9.111`（IDEA 2026.1，个人配置 15/15 还原）
- 离屏渲染截图：`build/render/01-*.png … 07-*.png`、`proposal-preset-copy.png`
- 可视化草稿：`build/panel-mockup.html`
- 均未提交（按任务书阶段 5：停在工作区等实机确认）
