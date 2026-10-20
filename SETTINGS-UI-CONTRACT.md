# SETTINGS-UI-CONTRACT —— 设置界面重做（0.9.5）实现契约 v1

> **状态**：**v1.2 冻结**（本文件即为下游实现与审查的唯一依据）。
> v1 由 captain 独立复核通过后，按其要求做了**五处增补 + 一处自我更正**（均不改设计裁决），见附录 C.1。
> **基线**：HEAD `0e323df`，已安装 `0.9.4-settings`
> **上游方案**：[`SETTINGS-UI-REDESIGN.md`](SETTINGS-UI-REDESIGN.md)（已获用户口头同意）。**本文件把方案冻结成可逐条裁决的条文**；方案与本文件冲突处以本文件为准，且必须在 §0.2 给出理由。
> **前置契约（从属关系）**：本文件**继承** [`SETTINGS-CONTRACT.md`](SETTINGS-CONTRACT.md) v2.1 的 §5（35 项禁改字面量）与 §3（持久化/编排模型），**只新增** UI 层与 4 个缺陷的修法。冲突时：**批次契约（`OPTIM-BATCH1` / `FIX-BATCH2` / `FIX-BATCH3`）> 本契约**。
>
> **三条硬纪律（贯穿全文，违反即验收失败）**
> 1. **不改任何观感常量的默认值** —— `core/NeovideConfig.java` 整个文件零 diff，35 项禁改字面量缺失数必须为 0（§10.1）。
> 2. **不装 jar、不重启 IDEA** —— 本契约全部判据可在 `build/offline-build.ps1` + 三件套 harness 内裁决。
> 3. **只用 `build/offline-build.ps1` 构建，BaseJar 固定为 `dist/jetbrains-neovide-cursor-0.9.4-settings.jar`。**

---

## 目录

- [0. 裁决口径](#0-裁决口径)
- [1. 缺陷 1：滑块不跟手（**三层缺陷，不是一个 bug**）](#1-缺陷-1滑块不跟手三层缺陷不是一个-bug)（含 §1.3.1 **可直接照抄的参考实现**）
- [2. 缺陷 2：刻度数字挤压](#2-缺陷-2刻度数字挤压)
- [3. 缺陷 3：Tools 覆盖面板（并发写冲突）](#3-缺陷-3tools-覆盖面板并发写冲突)
- [4. 缺陷 4：切主题后原生光标复活](#4-缺陷-4切主题后原生光标复活)
- [5. 预设机制的持久化模型（防第四真相源）](#5-预设机制的持久化模型防第四真相源)
- [6. 滑块吸附 + 输入框双向绑定（权威参考实现）](#6-滑块吸附--输入框双向绑定权威参考实现)
- [7. animationLength / shortAnimationLength 纳入可调](#7-animationlength--shortanimationlength-纳入可调)
- [8. Tools 菜单新增两个预设子菜单](#8-tools-菜单新增两个预设子菜单)
- [9. 布局重排](#9-布局重排)
- [10. 禁止项与不做项（可机械校验）](#10-禁止项与不做项可机械校验)
- [11. 验收判据 V1–V15](#11-验收判据-v1v15)（含 §11.1 V11 的单一判据精确措辞）
- [附录 A：证据索引（javap / 实测 / 源码行号）](#附录-a证据索引javap--实测--源码行号)
- [附录 B：本契约新增的 3 条「次生缺陷」](#附录-b本契约新增的-3-条次生缺陷)
- [附录 C：v1 冻结记录](#附录-cv1-冻结记录)（含 C.1 的 v1.1 修订记录）
- [附录 D：下游实现的最小顺序](#附录-d下游实现的最小顺序违反即返工)

---

## 0. 裁决口径

### 0.1 三类条文强度

| 标记 | 含义 | 裁决方式 |
|---|---|---|
| **【必须】** | 违反即验收失败，不可商量 | 静态检查 / harness / 探针 |
| **【禁止】** | 出现即验收失败 | 静态检查（§10 的 grep 清单） |
| **【建议】** | 允许偏离，但必须在实现 PR 里写明理由 | 人工 / 审查 |

### 0.2 本文与 `SETTINGS-UI-REDESIGN.md` 的三处**显式修订**

| # | 方案原文 | 本文裁决 | 理由 |
|---|---|---|---|
| R-1 | §7：缺陷 3 采用方案 **(c)**（Tools 改动时通知面板刷新 `pending`） | **否决 (c)，改用 (b) 字段级脏标记** | (c) 会把「Tools 被覆盖」换成「用户被覆盖」，等价严重；详见 §3.2。**captain 已裁决采纳本否决**（附录 C.1） |
| R-2 | §5：淡出时长刻度 `0/50/100…1000`；拖尾不透明度 `0/25/50/75/100` | 改为 `0/25/50…1000`（步长 25）与 `0/5/10…100`（步长 5） | 默认值 0.075 → 刻度 **75** 不在 50 的网格上；预设 0.45 / 默认 0.55 不在 25 的网格上。**否则一打开设置页就漂移，违反硬纪律 1**（实测 A-8）。**captain 已逐项复算确认原方案 4 处漂移、本文 12 项零漂移**（附录 C.1） |
| R-3 | §4.1 列了 8 个动画预设（第 8 个是「残影模式」） | **7 个**；「残影模式」归入 Tools 菜单既有第 3 项 | 「残影模式」只改 `trailMode`，不改任何动画字段；混入预设会让「预设」同时承载两种语义（§5.6） |

---

## 1. 缺陷 1：滑块不跟手（**三层缺陷，不是一个 bug**）

### 1.1 已验证的事实链（三层，逐层实测）

| 层 | 事实 | 证据 |
|---|---|---|
| **L1** | `SliderKt.bindValue(...)` **不在 JSlider 上注册任何 ChangeListener** | javap 反编译 `SliderKt.bindValue` → 只调 `Cell.bind(getComponent, setComponent, prop)`；而 `CellImpl.bind` 只挂 `onApply` / `onReset` / `onIsModified` 三个回调。**实测 `getChangeListeners().length == 0`**（A-1） |
| **L2** | 因此拖动**只改组件值**，`pending` 不变 → 标签不刷新 | 实测：`setValue(120)` 后 `pending` 仍 50、标签仍是 `L`（A-1 的 B1/B2） |
| **L3（本文新发现）** | **只补 ChangeListener 会打坏 `isModified()`** —— 平台只比「控件值 vs getter()」；一旦 listener 写进 `pending`、getter 返回 `pending`，两者恒等 → `super.isModified()` **恒 false** → 应用按钮变灰 → 改动静默丢失 | 实测：现状 `isModified()=true`；补 listener 后 **`isModified()=false`**（A-2 的 P1/P2） |

> **L3 是本文最重要的发现**：它意味着「缺陷 1 的修法」与「缺陷 3 的修法」**必须联合设计**，单独修任何一个都会引入新缺陷（见附录 B）。

### 1.2 三个候选修法的实测裁决

| 候选 | 编译 | 拖动时 setter 回调 | 标签跟随 | `isModified()` | `@ApiStatus` | 裁决 |
|---|---|---|---|---|---|---|
| **(a)** 显式 `addChangeListener` | 通过（`javac --release 21`） | 1 次 | 跟随 | 需自写 | 无注解（`JSlider` 是 JDK 类） | **【必须】采纳（主）** |
| **(b)** `Cell.onChanged` | 通过 | 1 次 | 跟随 | 需自写 | **类级** `@ApiStatus.NonExtendable` | **【禁止】** |
| **(c)** `slider + intTextField` 双向 | 通过 | `bindIntText` 的 setter **只在 apply 时**回调 | 靠 (a) | 需自写 | 方法级无注解 | **【必须】采纳（与 (a) 并用，见 §6）** |

**(b) 被禁止的确切理由**：`@ApiStatus.NonExtendable` 挂在 **`Cell` 接口的类级**（不是方法级）—— 语义是**整个 `Cell` 接口不可被第三方实现/扩展**，官方保留随时重构的自由。`SETTINGS-CONTRACT.md` v2.1 附录 A-5 已裁决第一期不用，本文沿用。

**(c) 的关键实测**：`TextFieldKt.bindIntText` 的 setter 在**键入时不回调**，只在 §6 的两个时机回调（A-3）：

| 时机 | setter 是否回调 |
|---|---|
| 键入（Document 变化） | **否** |
| `transferFocus()`（headless 下不派发 FOCUS_LOST） | 否 |
| **回车 `postActionEvent()`** | **是** |
| **`panel.apply()`** | **是** |

所以**不能**指望 `bindIntText` 提供「键入即生效」；它只提供「apply 时回写 + 非法值过滤」。

### 1.3 【必须】缺陷 1 的完整修法（三条缺一不可）

    // ---- 修法 1：显式 ChangeListener，写 pending + 刷标签 + 同步输入框 ----
    slider.addChangeListener(e -> {
        if (suppressUiSync) return;              // §6.4 的抑制标志
        int v = slider.getValue();
        pending.<字段> = v;                       // 写 pending（脏标记需要）
        valueLabel.setText(format(v));           // ← 缺陷 1 的直接修复
        syncTextFieldIfDifferent(v);             // §6.3 的输入框同步
    });

    // ---- 修法 2：isModified() 必须自己重写（不能只信 super）----
    //      【只修它 = 踩 B-2；必须与修法 3 同时落地，判据见 §11.1 的 V11 单一判据】
    @Override
    public boolean isModified() {
        NeovideCursorSettings svc = serviceOrNull();
        if (svc == null) return false;
        NeovideCursorSettings.State st = svc.getState();
        return dirtyAnyField(pending, st) || colorPanelModified(st);   // §3.3 的脏字段判据同源
    }

    // ---- 修法 3：滑块的 getter 必须返回【吸附后的 pending】----
    //      【只修它 = 踩 B-1；必须与修法 2 同时落地，判据见 §11.1 的 V11 单一判据】
    SliderKt.bindValue(cell,
        () -> snapToTick(pending.<字段>),        // ← snap 抹平「精确值 vs 刻度值」的差异
        v -> { pending.<字段> = v; return Unit.INSTANCE; });

**修法 3 的作用**（实测 A-4 的 C5/E1）：用户键入 `333`（不在 50 的刻度上）时 `pending=333`、滑块显示 `350`。若 getter 直接返回 `pending`，`onIsModified` 会判 `333 != 350` → **`isModified()` 永真**，OK 按钮永远亮着。用 `snapToTick(pending)` 作 getter 后两者一致，实测 `isModified()=false`。

### 1.3.1 参考实现（**可直接照抄**，已编译并跑通 23/23 断言）

> 本节不是"应该这样"，而是**已经写出来、编译过、跑通 V11 全部 4 条断言**的代码骨架（探针 `RefImpl3Probe`，A-14）。
> impl-core 照抄即可，**不必自己再推三层缺陷**。

**A. 公共谓词（§3.3 的脏字段判据，`isModified()` 与 `apply()` 共用同一份）**

```java
/** pending 与 state 是否有【任一】字段不同。isModified() 的第 2 半，也是 apply() 的合并判据。 */
private static boolean dirtyAnyField(NeovideCursorSettings.State p, NeovideCursorSettings.State s) {
    return p.enabled               != s.enabled
        || p.trailMode             != s.trailMode
        || p.hideNativeCaret       != s.hideNativeCaret
        || p.alwaysHighFps         != s.alwaysHighFps
        || p.overlayFadeOut        != s.overlayFadeOut
        || p.cursorDisappearDelay  != s.cursorDisappearDelay
        || Float.compare(p.cursorFadeOutDuration, s.cursorFadeOutDuration) != 0
        || !eqHex(p.tailColor, s.tailColor)
        || Float.compare(p.tailOpacity, s.tailOpacity) != 0
        || p.useShadow             != s.useShadow
        || !eqHex(p.shadowColor, s.shadowColor)
        || Float.compare(p.glowOpacity, s.glowOpacity) != 0
        || p.glowLayers            != s.glowLayers
        || Float.compare(p.shadowBlurFactor, s.shadowBlurFactor) != 0
        || Float.compare(p.trailSize, s.trailSize) != 0
        || Float.compare(p.stretchRatioK, s.stretchRatioK) != 0
        || Float.compare(p.animationLength, s.animationLength) != 0           // §7 新增
        || Float.compare(p.shortAnimationLength, s.shortAnimationLength) != 0; // §7 新增
}

/** HEX 必须忽略大小写：sanitizeColor 统一成大写，用户手改 XML 可能是小写。 */
private static boolean eqHex(String a, String b) {
    return a == null ? b == null : a.equalsIgnoreCase(b);
}
```

**B. 修法 3 的 snap（滑块 getter 的基石）**

```java
/**
 * 把任意原始值吸附到最近刻度，并 clamp 到 [min, max]。
 * <p>它【必须】同时做两件事：clamp（越界输入不能进滑块）+ 吸附（抹平"精确值 vs 刻度值"）。
 * 只做 clamp 不做吸附，isModified() 会在"精确值 333 / 刻度 350"时恒真（B-2）。
 */
private static int snapToTick(int raw, int min, int max, int step) {
    int clamped = Math.max(min, Math.min(max, raw));
    int k = Math.round((float) (clamped - min) / step);   // Math.round 对 .5 向上，与 JSlider 一致
    return Math.max(min, Math.min(max, min + k * step));
}
```

**C. 修法 2：`isModified()` 的两半**

```java
/**
 * 两半都必须有，缺一即踩 B-1 或 B-2（判据是 §11.1 的 V11 单一判据）：
 *   ① super.isModified() —— 抓「控件值 vs getter()」的差异（键入但未 apply 的【文字】）
 *   ② dirtyAnyField    —— 抓「pending vs state」的差异（滑块拖动已写 pending，但组件值与 getter 一致）
 *
 * <p>为什么 ② 不可省：修法 3 让 getter 返回 snap(pending)，于是组件值恒等于 getter()，
 * super 对滑块【永远】返回 false（实测 A-14 的 B-1a：拖到 250 后 super=false）。
 * 而拖动已经把 pending 写成 250，故 ② 能抓到。
 *
 * <p>为什么 ① 不可省：用户在输入框打 "333" 但没按回车/失焦时，pending 仍是旧值，
 * 此时只有 super 能看见「控件文字 333 != getter 250」。
 */
@Override
public boolean isModified() {
    // ColorPanel 不是 DialogPanel 的绑定控件，super 看不到它 → 用 pending 与 state 的比较兜住
    return panel.isModified() || dirtyAnyField(pending, stateOf());
}

private static NeovideCursorSettings.State stateOf() {
    NeovideCursorSettings svc = serviceOrNull();
    return svc != null ? svc.getState() : new NeovideCursorSettings.State();
}
```

**D. 修法 1：滑块的构建（含吸附 + 标签 + 输入框同步）**

```java
// ---- 顺序【必须】滑块 → 输入框 → 标签（§6.7：apply 回调按构建顺序跑，输入框最后写才赢）----
Cell<JSlider> cell = row.slider(MIN, MAX, STEP, STEP);      // minor == major == STEP
slider = cell.getComponent();
slider.setSnapToTicks(true);

// §2.2：只给 3 个关键刻度，且【必须】显式 setPaintLabels(true)
Map<Integer, JComponent> marks = new LinkedHashMap<>();
marks.put(MIN, new JLabel(fmt(MIN)));
int mid = MIN + ((MAX - MIN) / 2 / STEP) * STEP;
if (mid > MIN && mid < MAX) marks.put(mid, new JLabel(fmt(mid)));
marks.put(MAX, new JLabel(fmt(MAX)));
SliderKt.labelTable(cell, marks);
slider.setPaintLabels(true);
slider.setPaintTicks(false);

// ---- 修法 3：getter 返回【吸附后的 pending】----
SliderKt.bindValue(cell,
    (Function0<Integer>) () -> snapToTick(pending.cursorDisappearDelay, MIN, MAX, STEP),
    (Function1<Integer, kotlin.Unit>) v -> {
        pending.cursorDisappearDelay = v;
        return kotlin.Unit.INSTANCE;
    });

// ---- 修法 1：显式 ChangeListener（缺陷 1 的直接修复）----
slider.addChangeListener(e -> {
    if (syncingControls) return;                    // §6.4 的抑制标志
    int v = slider.getValue();                      // 已被 JSlider 吸附过
    pending.cursorDisappearDelay = v;               // ① 写 pending（脏标记需要）
    valueLabel.setText(v + " ms");                  // ② 右侧标签实时跟随 ← 缺陷 1
    syncTextFieldIfDifferent(v);                    // ③ 输入框文字同步
});

// ---- 输入框（必须建在滑块【之后】）----
Cell<JBTextField> tf = row.intTextField(new IntRange(MIN, MAX), pending.cursorDisappearDelay);
textField = tf.getComponent();
TextFieldKt.bindIntText(tf,
    (Function0<Integer>) () -> pending.cursorDisappearDelay,   // ← 精确值，不 snap
    (Function1<Integer, kotlin.Unit>) v -> {
        pending.cursorDisappearDelay = v;
        return kotlin.Unit.INSTANCE;
    });
textField.getDocument().addDocumentListener(new DocumentListener() {
    @Override public void insertUpdate(DocumentEvent e)  { onTextEdited(); }
    @Override public void removeUpdate(DocumentEvent e)  { onTextEdited(); }
    @Override public void changedUpdate(DocumentEvent e) { onTextEdited(); }
});
// §6.5：失焦才做 clamp + 回写（键入过程中不 clamp，否则 999 会被改成 500 打不下去）
textField.addFocusListener(new FocusAdapter() {
    @Override public void focusLost(FocusEvent e) { normalizeTextField(); }
});

valueLabel = new JLabel();
row.cell(valueLabel);
```

**E. 双向同步的三个辅助（`syncingControls` 是唯一抑制标志）**

```java
/** 方向 1：滑块 → 输入框。只改文字，不碰 pending。 */
private void syncTextFieldIfDifferent(int v) {
    syncingControls = true;
    try {
        String txt = String.valueOf(v);
        if (!txt.equals(textField.getText())) textField.setText(txt);
    } finally { syncingControls = false; }
}

/** 方向 2：输入框 → 滑块位置。只挪滑块，pending 交给 apply（§6.1 的语义表）。 */
private void onTextEdited() {
    if (syncingControls) return;
    String t = textField.getText();
    if (t == null || !t.trim().matches("-?\\d+")) return;    // 非数字：什么都不做
    int v;
    try { v = Integer.parseInt(t.trim()); } catch (NumberFormatException ex) { return; }
    syncingControls = true;
    try {
        int snapped = snapToTick(v, MIN, MAX, STEP);
        if (slider.getValue() != snapped) slider.setValue(snapped);
    } finally { syncingControls = false; }
    valueLabel.setText(v + " ms");     // 标签显示【用户输入】而不是滑块值（B-2b）
}

/** §6.5：失焦规整 —— 非法回滚、越界 clamp 后写回 pending。 */
private void normalizeTextField() {
    String t = textField.getText();
    int v = pending.cursorDisappearDelay;
    if (t != null && t.trim().matches("-?\\d+")) {
        try { v = Integer.parseInt(t.trim()); } catch (NumberFormatException ignored) { }
    }
    int clamped = Math.max(MIN, Math.min(MAX, v));
    syncingControls = true;
    try {
        textField.setText(String.valueOf(clamped));
        slider.setValue(snapToTick(clamped, MIN, MAX, STEP));
    } finally { syncingControls = false; }
    pending.cursorDisappearDelay = clamped;
    valueLabel.setText(clamped + " ms");
}
```

**F. `apply()` / `reset()`（§3.3 的脏字段合并）**

```java
@Override
public void apply() {
    ...回读 ColorPanel 到 pending...
    panel.apply();                       // DialogPanel 把控件值写进 pending（滑块先、输入框后）

    NeovideCursorSettings svc = serviceOrNull();
    if (svc == null) return;
    NeovideCursorSettings.State target = svc.getState();

    // 逐字段：只有「pending 与 baseline 不同」才写（缺陷 3 的修法）
    if (pending.cursorDisappearDelay != baseline.cursorDisappearDelay)
        target.cursorDisappearDelay = pending.cursorDisappearDelay;
    ...其余 17 个字段同构，逐字段三行...
    // ★ 预设不持久化（§5），因此不在此处写

    svc.persistQuietly();
    NeovideCaretManager.applyConfig();
    baseline = deepCopy(target);         // ← apply 后 baseline 前移
}

@Override
public void reset() {
    pending  = deepCopy(stateOf());
    baseline = deepCopy(stateOf());      // ← 【必须】与 pending 同时拍
    syncAllControlsFromPending();        // 把 pending 灌进 slider / textField / label
    panel.reset();
}

/** 以 pending 为准刷新全部控件（reset 与"预设应用"共用）。 */
private void syncAllControlsFromPending() {
    syncingControls = true;
    try {
        int snapped = snapToTick(pending.cursorDisappearDelay, MIN, MAX, STEP);
        if (slider.getValue() != snapped) slider.setValue(snapped);
        String txt = String.valueOf(pending.cursorDisappearDelay);
        if (!txt.equals(textField.getText())) textField.setText(txt);
        valueLabel.setText(pending.cursorDisappearDelay + " ms");
    } finally { syncingControls = false; }
}
```

**G. 这 23 个断言证明什么（A-14 实测输出）**

| 断言组 | 结果 |
|---|---|
| V11-1 拖滑块 → `isModified()` = true | 通过；且 `super` 实测已是 false（证明 ② 不可省） |
| V11-2 只点复选框 → `isModified()` = true | 通过；且 `pending` 仍未被提前写（证明复选框不受修法影响） |
| V11-3 键入 333 → apply → 333 落库 + 滑块 350 + `isModified()`=false | 通过（防 B-2 的核心） |
| V11-4 reset/apply 后 → false | 通过（含"精确值 333 与滑块 350 并存"的场景） |
| snapToTick 边界 | 333→350 / 37→50 / -9→0 / 9999→500 / 25→50（.5 向上）；覆盖 10 个滑块的端点 |

**H. 【禁止】把 `dirtyAnyField` 写成"任一字段不同就整份替换"**

那是缺陷 3 的原缺陷。**必须逐字段 if**，不能 `if (dirtyAnyField) target = pending` —— 后者会把用户没碰的字段也用面板值覆盖掉 Tools 的改动。


### 1.4 【必须】吸附的实现

| 项 | 规定 | 证据 |
|---|---|---|
| 吸附开关 | `slider.setSnapToTicks(true)` | 实测 `setValue(37)` → `getValue()==50`，A-4 的 B1 |
| 刻度间距 | `row.slider(min, max, step, step)` —— **minor 与 major 都传 step** | `RowImpl.slider` 字节码把第 3 参设给 `setMinorTickSpacing`、第 4 参设给 `setMajorTickSpacing`（A-6）；吸附只认 tick spacing |
| `paintTicks` | `setPaintTicks(false)` —— **不影响吸附** | 实测 A-4 的 Z1：`paintTicks=false` 时 `setValue(333)` 仍吸附到 `350` |
| 事件次数 | 吸附开启后一次 `setValue` 可能触发 **2 次** ChangeEvent | 实测 A-5 的 F2：`[100, 100]`；目标值正好在刻度上则只 1 次（F3）。**监听器必须幂等** |

---

## 2. 缺陷 2：刻度数字挤压

### 2.1 已验证的事实

| 事实 | 证据 |
|---|---|
| `RowImpl.slider` 硬编码 `setPaintLabels(true)` | javap 字节码：`iconst_1; invokevirtual JSlider.setPaintLabels(Z)V`（A-6） |
| `labelTable` 条目数 = `(max-min)/majorTickSpacing + 1` | 实测：`slider(0,500,50,10)` → **51** 条；`slider(0,1000,5,5)` → **201** 条（A-1 的 D1/D2） |
| `SliderKt.labelTable(cell, Map<Integer,JComponent>)` 存在且**立即** `setLabelTable(new Hashtable<>(map))` | javap 反编译。**它不会自动打开 `paintLabels`** |
| `SliderKt.showValueHint(cell)` **会自己再挂一个 ChangeListener** | javap 反编译：`JSlider.addChangeListener(showValueHint$lambda$0$0)`，该 listener 只 `setToolTipText(value + "%")`。实测 listener 数 0 → 1（A-1） |

### 2.2 【必须】修法

    Cell<JSlider> cell = row.slider(min, max, step, step);
    JSlider slider = cell.getComponent();

    Map<Integer, JComponent> marks = new LinkedHashMap<>();
    marks.put(min, new JLabel(labelFor(min)));
    int mid = min + ((max - min) / 2 / step) * step;         // 中间刻度必须对齐到 step
    if (mid > min && mid < max) marks.put(mid, new JLabel(labelFor(mid)));
    marks.put(max, new JLabel(labelFor(max)));

    SliderKt.labelTable(cell, marks);      // ← 条目数 ≤ 3
    slider.setPaintLabels(true);           // ← 【必须显式设置】labelTable 不会替你打开
    slider.setPaintTicks(false);           // ← 不画刻度线，只保留 label 的位置语义

**调用顺序约束【必须】**：`labelTable(cell, marks)` 必须在 `row.slider(...)` 返回之后调用，并在 `slider.setPaintLabels(true)` 之前完成；与 `SliderKt.bindValue(...)` 的先后**不敏感**（两者互不依赖）。

### 2.3 【建议】不用 `showValueHint()`

理由：它在 JSlider 上**额外挂一个 ChangeListener**，使 `getChangeListeners().length` 从 1 变 2，**污染 V2 的机械判据**（「每个滑块恰好 1 个自己挂的 listener」）。而且 tooltip 格式 `"50%"` 对毫秒/层数类参数是错的；右侧已有数值标签，tooltip 冗余。

### 2.4 【禁止】`setPaintLabels(false)` 后不设 labelTable

那样滑块完全无刻度也无标签，用户无法判断「吸附到哪几档」。**必须**保留 ≤3 个关键刻度（min / mid / max）。

---

## 3. 缺陷 3：Tools 覆盖面板（并发写冲突）

### 3.1 问题重述

`NeovideCursorConfigurable.apply()` 在 `:440-456` **无条件**把 `pending` 的 16 个字段全量写进 `state`。若面板打开期间 Tools 菜单改了 `state`，`apply()` 会把那次改动冲掉（`START-HERE.md` §6.5 已实测复现）。

### 3.2 方案裁决：(b) 字段级脏标记，**否决 (c)**

| 方案 | 做法 | 问题 | 裁决 |
|---|---|---|---|
| (a) | 面板打开时禁用 Tools 菜单项 | 需面板反向控制菜单 enable；面板生命周期与菜单渲染时机不同步；用户会认为「菜单坏了」 | **【禁止】** |
| **(b)** | `apply()` 前比 `pending` 与 `baseline`，只写**用户真改过**的字段 | 需新增 1 个 `baseline` 字段（`reset()` 时拍快照）——它是**快照不是真相源** | **【必须】采纳** |
| (c) | Tools 改动时通知面板刷新 `pending` | **① 会覆盖用户正在拖的值**；② 需面板注册/注销监听 + 弱引用；③ `Configurable` 实例生命周期由平台管，`disposeUIResources` 之后仍可能收到回调 | **【禁止】** |

**否决 (c) 的决定性理由**：方案 §7 自己写的复现路径是「面板开着 → 点 Tools → 回来点 OK」。(c) 在 Tools 改动时把 `state` 刷进 `pending`，**同一条路径下**如果用户此前正在拖某个滑块（`pending` 已有新值但 `state` 还是旧值），这次刷新会**把用户刚拖的值冲掉**——把「Tools 被覆盖」换成「用户被覆盖」，是等价严重的缺陷。(b) 的语义是**「谁的字段谁负责」**，两个方向的覆盖都不会发生（实测 A-7 的 C1/C2/K5/K6）。

### 3.3 【必须】脏字段的精确判据

    // reset() / createPanel() 时拍快照
    @Override
    public void reset() {
        pending  = deepCopy(currentState());
        baseline = deepCopy(currentState());     // ← 新增：只作比较基准，不是真相源
        ...
    }

    // apply() 时逐字段合并
    @Override
    public void apply() {
        ...回读 ColorPanel 到 pending...
        super.apply();                            // DialogPanel 把控件值写进 pending

        NeovideCursorSettings svc = serviceOrNull();
        if (svc == null) return;
        NeovideCursorSettings.State target = svc.getState();

        if (pending.enabled             != baseline.enabled)             target.enabled = pending.enabled;
        if (pending.trailMode           != baseline.trailMode)           target.trailMode = pending.trailMode;
        if (pending.hideNativeCaret     != baseline.hideNativeCaret)     target.hideNativeCaret = pending.hideNativeCaret;
        if (pending.alwaysHighFps       != baseline.alwaysHighFps)       target.alwaysHighFps = pending.alwaysHighFps;
        if (pending.overlayFadeOut      != baseline.overlayFadeOut)      target.overlayFadeOut = pending.overlayFadeOut;
        if (pending.cursorDisappearDelay != baseline.cursorDisappearDelay) target.cursorDisappearDelay = pending.cursorDisappearDelay;
        if (Float.compare(pending.cursorFadeOutDuration, baseline.cursorFadeOutDuration) != 0)
            target.cursorFadeOutDuration = pending.cursorFadeOutDuration;
        if (!eqHex(pending.tailColor, baseline.tailColor))               target.tailColor = pending.tailColor;
        if (Float.compare(pending.tailOpacity, baseline.tailOpacity) != 0) target.tailOpacity = pending.tailOpacity;
        if (pending.useShadow != baseline.useShadow)                     target.useShadow = pending.useShadow;
        if (!eqHex(pending.shadowColor, baseline.shadowColor))           target.shadowColor = pending.shadowColor;
        if (Float.compare(pending.glowOpacity, baseline.glowOpacity) != 0) target.glowOpacity = pending.glowOpacity;
        if (pending.glowLayers != baseline.glowLayers)                   target.glowLayers = pending.glowLayers;
        if (Float.compare(pending.shadowBlurFactor, baseline.shadowBlurFactor) != 0) target.shadowBlurFactor = pending.shadowBlurFactor;
        if (Float.compare(pending.trailSize, baseline.trailSize) != 0)   target.trailSize = pending.trailSize;
        if (Float.compare(pending.stretchRatioK, baseline.stretchRatioK) != 0) target.stretchRatioK = pending.stretchRatioK;
        // ★ 新增 2 项（§7）
        if (Float.compare(pending.animationLength, baseline.animationLength) != 0) target.animationLength = pending.animationLength;
        if (Float.compare(pending.shortAnimationLength, baseline.shortAnimationLength) != 0) target.shortAnimationLength = pending.shortAnimationLength;
        // ★ 预设不持久化（§5），因此不在此处写

        svc.persistQuietly();
        NeovideCaretManager.applyConfig();
        baseline = deepCopy(target);              // ← apply 之后 baseline 前移
    }

**float 比较【必须】用 `Float.compare` 而不是 `==`**：`Float.NaN == Float.NaN` 为 false，`-0.0f == 0.0f` 为 true；`Float.compare` 两个边界都正确。

**字符串比较【必须】用 `equalsIgnoreCase`**：`sanitizeColor`（`NeovideCursorSettings:522`）把 HEX 统一成大写，但用户手改 XML 可能留下小写。

### 3.4 【必须】「用户正在拖时不被覆盖」的处理

**本方案下这个场景自然消失**：

1. 拖动**只写 `pending`**（§1.3 修法 1），**从不写 `state`**；
2. `apply()` 只在用户点 OK 时跑一次，此时用户已停止拖动；
3. 用户拖过的字段 `pending != baseline` → 判为脏 → **以用户为准**（实测 A-7 的 C1/K5）；
4. 用户没碰的字段 `pending == baseline` → 不写 → **Tools 的值保住**（实测 A-7 的 C2/K6）。

**唯一需要显式规定的是 `baseline` 的时效**：

| 时机 | `baseline` 的动作 | 理由 |
|---|---|---|
| `createPanel()` | `baseline = deepCopy(state)` | 平台可能只调 createPanel 不调 reset |
| `reset()` | `baseline = deepCopy(state)` | 面板刚打开/刚取消，此刻 state 就是「尚未改动」的基准 |
| `apply()` 成功后 | `baseline = deepCopy(target)` | 否则连续两次 apply 时第二次会把第一次的值误判为非脏 |
| `disposeUIResources()` | `baseline = null` | 防泄漏 |

### 3.5 【必须】已知语义边界（必须写进代码注释）

**用户把某字段改了又改回原值，且期间 Tools 也改了它** → `pending == baseline` → 判为「没改过」 → **Tools 的值胜出**（实测 A-7 的 Z2）。这是 (b) 的固有语义，**不是缺陷**：用户最终值就是「原值」，行为上等于没改，而 Tools 的改动是更晚发生的真实事件。

### 3.6 【禁止】用 `PropertyGraph` 做脏标记

`graph.property(...)` 只在 `graphReset()` 时被 `set()`，而 `set()` 只在复选框的绑定 setter 里跑。滑块**没有**接入 PropertyGraph（现状 `NeovideCursorConfigurable:133-137` 只有 `springMode` / `shadowOn` / `fadeOn`）。用它做脏标记需给 10 个滑块各加一个 property，比 `baseline` 逐字段比更复杂、更易漏。

---

## 4. 缺陷 4：切主题后原生光标复活

### 4.1 已验证的事实

| 事实 | 证据 |
|---|---|
| `EditorColorsListener` 是**只有一个方法**的接口：`void globalSchemeChange(EditorColorsScheme)` | javap（A-9）；实测 `getDeclaredMethods().length == 1`（A-10） |
| 纯 Java 可实现、可用 lambda | 实测编译通过 + `isInterface=true`（A-10） |
| `EditorColorsManager.TOPIC` 类型是 `Topic<EditorColorsListener>` | javap + 实测 `TOPIC.getListenerClass() == EditorColorsListener.class`（A-10） |
| `TOPIC` 广播方向 = `TO_DIRECT_CHILDREN`，`immediateDelivery=false` | 实测（A-10） |
| 订阅方式：`app.getMessageBus().connect(disposable).subscribe(TOPIC, listener)` | `MessageBus.connect(Disposable)` + `SimpleMessageBusConnection.subscribe(Topic<L>, L)` 均 javap 实证（A-9） |
| **`AnActionGroup` 在本机 381 jar 里不存在** | javap 报「找不到类」。正确类是 `DefaultActionGroup`（§8） |

### 4.2 【必须】`applyNativeCaretHidden` 的早退修法

现状（`NeovideCaretManager:604-618`）：

    public static void applyNativeCaretHidden() {
        if (savedCaretColor != null) {
            return;                     // :606  ← 以为「已经隐藏过」
        }
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        savedCaretColor = sanitizeCaretColor(scheme, scheme.getColor(EditorColors.CARET_COLOR));
        scheme.setColor(EditorColors.CARET_COLOR, new Color(0, 0, 0, 0));
        repaintAllEditors();
    }

**问题**：切主题后 `getGlobalScheme()` 返回**新对象**，其 `CARET_COLOR` 是可见的；但 `savedCaretColor` 仍非 null → 早退 → 不重新隐藏（实测 A-10 的 D3：`执行=false, caret=0xFF0000`）。

**修法【必须】**：加强制重入，**不改变现有签名语义**（老调用点行为不变）：

    /** 现有签名：幂等，已隐藏则不动。所有既有调用点保持不变。 */
    public static void applyNativeCaretHidden() {
        applyNativeCaretHidden(false);
    }

    /**
     * @param force true = 忽略 savedCaretColor 早退，按【当前】scheme 重新隐藏。
     *              配色方案变更监听器必须传 true。
     */
    public static void applyNativeCaretHidden(boolean force) {
        try {
            if (!force && savedCaretColor != null) {
                return;
            }
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            // force 时【必须】重新取原始色：新 scheme 的可见色才是要保存的值
            savedCaretColor = sanitizeCaretColor(scheme, scheme.getColor(EditorColors.CARET_COLOR));
            scheme.setColor(EditorColors.CARET_COLOR, new Color(0, 0, 0, 0));
            repaintAllEditors();
        } catch (Throwable ignored) { }
    }

**为什么 `force=true` 时必须重新取 `savedCaretColor`**：实测 A-10 的 D4/D5 —— 强制重入后 `saved` 从旧 scheme 的 `0xA9B7C6` 更新为新 scheme 的 `0xFF0000`；否则用户取消勾选后光标会恢复成**旧主题**的颜色，在新主题上可能几乎不可见。

### 4.3 【必须】监听器注册的位置与生命周期

    public final class CaretSchemeWatcher implements Disposable {
        private MessageBusConnection connection;

        public void install() {
            Application app = ApplicationManager.getApplication();
            if (app == null) return;
            connection = app.getMessageBus().connect(this);       // ← 绑到 this，dispose 时自动断开
            connection.subscribe(EditorColorsManager.TOPIC, (EditorColorsListener) scheme -> {
                if (!NeovideCaretManager.isHideNativeCaret()) return;   // 未勾选则不管
                NeovideCaretManager.applyNativeCaretHidden(true);       // ← force
            });
        }

        @Override public void dispose() {
            if (connection != null) { connection.disconnect(); connection = null; }
        }
    }

| 约束 | 规定 | 理由 |
|---|---|---|
| 注册时机 | 在 `editorCreated` 第一帧之前（或插件初始化时） | 否则用户加载后立刻切主题会漏掉 |
| 持有方式 | `connect(this)`，`this` 是 `Disposable` | 平台在 dispose 时自动断开，**不会泄漏** |
| 线程 | 回调在 EDT（`immediateDelivery=false` → 走 `invokeLater`） | `setColor` 必须 EDT，无需再包 `invokeLater` |
| 幂等 | 必须 —— `force` 每次都写 | 写的是同一个透明色，幂等安全 |
| 与 `enabled` 的关系 | `enabled=false` 时不注册 / 收到回调直接 return | 否则关闭插件后仍在改配色 |

### 4.4 【禁止】用 `getSchemeModificationCounter()` 轮询

它是 `abstract long`（javap 实证），可用但没有变更通知语义，轮询必然引入延迟与无谓开销。**必须**用 `TOPIC`。

---

## 5. 预设机制的持久化模型（防第四真相源）

### 5.1 现状的三层真相源

| 层 | 位置 | 角色 |
|---|---|---|
| **默认值** | `core/NeovideConfig.java` 的字段初值 | 编译期常量，**禁改** |
| **持久化** | `settings/NeovideCursorSettings.State` → `<config>/options/NeovideCursorSettings.xml` | 唯一持久化真相 |
| **运行期** | `NeovideConfig CONFIG`（`NeovideCaretManager:49`）+ Tools 菜单的 5 个 `ToggleAction` | 运行期快照 |

### 5.2 【必须】四条裁决

| # | 问题 | 裁决 | 判据（可机械裁决） |
|---|---|---|---|
| **P-1** | 预设是否持久化「当前选中哪个预设」？ | **不持久化。预设是纯动作（Action），不是状态。** | `State` 的 `public` 字段数 == **19**（`stateVersion` + 16 既有 + 2 新增）；XML 里不出现 `preset` 任何键 |
| **P-2** | 界面怎么显示「当前是哪个预设」？ | **每次 `reset()` / 任一控件变更后用「逐字段比对」实时推导**；匹配失败显示 `自定义` | §5.3 的 `matchAnimPreset()` |
| **P-3** | 预设表放哪里？ | **新建 `core/CursorPresets.java`，纯 `static final` 常量表**。**禁止**放进 `State`、**禁止**放进 `NeovideConfig` | 文件存在 + `git diff --exit-code core/NeovideConfig.java` 仍为 0 |
| **P-4** | 预设 1（原版粉色）是否等于 `NeovideConfig` 默认值？ | **是，且【必须】逐字段相等**（0.10 / 0.11 / 1.0 / 8 / 0.20 / 0.55） | 探针逐字段断言（V4） |

### 5.3 【必须】「变成自定义」的判据

**逐字段比对，不看历史**。任何「记住上次选了哪个」的做法都是第四个真相源（P-1 已禁止）。

    /** 动画预设的匹配维度（顺序即比对顺序；不参与比对的字段绝不出现在这里）。 */
    private static final float EPS = 1e-6f;

    static CursorPresets.AnimPreset matchAnimPreset(NeovideCursorSettings.State s) {
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            if (Math.abs(s.animationLength      - p.animationLength())      < EPS
             && Math.abs(s.shortAnimationLength - p.shortAnimationLength()) < EPS
             && Math.abs(s.trailSize            - p.trailSize())            < EPS
             && s.glowLayers == p.glowLayers()
             && Math.abs(s.shadowBlurFactor     - p.shadowBlurFactor())     < EPS
             && Math.abs(s.glowOpacity          - p.glowOpacity())          < EPS) {
                return p;
            }
        }
        return null;      // null = 界面显示「自定义」
    }

**必须用 EPS 而不是 `==`**：`float` 经 XML 往返在当前实现下是逐位精确的（实测 A-8），但 `/100f` 这类除法在往返中可能产生 1 ulp 差异。`EPS = 1e-6f` 远大于 ulp 量级（`0.1f` 的 ulp ≈ `7.5e-9`），又远小于最小刻度（0.01）。

**匹配维度表【必须逐字】**（多一个字段就多一种「永远匹配不上」的可能）：

| 预设种类 | 参与比对的字段 | **不**参与比对 | 理由 |
|---|---|---|---|
| 动画预设 | `animationLength` / `shortAnimationLength` / `trailSize` / `glowLayers` / `shadowBlurFactor` / `glowOpacity` | `trailFactors` / `stretchRatioK` | 方案 §4.1 明写「预设一律沿用 4.5×」；若参与比对，用户调过拉伸强度后所有预设都变「自定义」，违背「动画/颜色正交」 |
| 颜色预设 | `tailColor` / `shadowColor` | 全部动画字段 | 方案 §4.2 的「完全正交」要求 |
| `trailMode` | **不参与任何比对** | — | 它是独立开关；参与比对会让「残影模式」与「自定义」混淆 |

### 5.4 【必须】预设表的形状

    package com.github.yyyolo7a79.neovidecursor.core;

    /**
     * 0.9.5 的预设表。<b>纯常量，不是真相源。</b>
     *
     * <p>它是 <b>NeovideConfig 默认值之外的唯一一份「成套取值」</b>，但同一时刻只有
     * 一组值生效（写进 State）。它<b>不被持久化</b>，也<b>不被任何绘制代码直接读取</b>。
     */
    public final class CursorPresets {

        private CursorPresets() { }

        /** 一个动画预设：6 个字段，全部是【刻度整数】，避免 float 字面量精度问题。 */
        public static final class AnimPreset {
            private final String name;
            private final int animLengthTicks;      // ×100 → 0.10s 存 10
            private final int shortLengthTicks;     // ×100 → 0.11s 存 11
            private final int trailSizeTicks;       // ×100 → 1.0 存 100
            private final int glowLayers;
            private final int blurFactorTicks;      // ×100 → 0.20 存 20
            private final int glowOpacityTicks;     // ×100 → 0.55 存 55
            // 访问器：animationLength() = animLengthTicks / 100f，其余同理
        }

        public static final AnimPreset[] ANIM_PRESETS = { ... };   // §5.5 的 7 条

        public static final class ColorPreset {
            private final String name, tailColor, shadowColor;
            // 访问器
        }

        public static final ColorPreset[] COLOR_PRESETS = { ... }; // §5.6 的 5 条
    }

**用刻度整数而不是 `float` 字面量作预设表【必须】**：`0.28f` 这类字面量在源码里看着对，但 1 ulp 问题会让 V4 探针的 `==` 断言随机失败。用 `28` 这个整数、写入时 `28 / 100f` 就完全可控（实测 A-8：7 个默认值的「刻度 ↔ float」往返**逐位一致**）。

### 5.5 【必须】7 个动画预设的精确取值

| # | 名称 | `animationLength` | `shortAnimationLength` | `trailSize` | `glowLayers` | `shadowBlurFactor` | `glowOpacity` |
|---|---|---|---|---|---|---|---|
| 1 | 原版粉色（= 默认值） | 0.10（刻度 10） | 0.11（11） | 1.00（100） | 8 | 0.20（20） | 0.55（55） |
| 2 | 缎带 | **0.28（28）** | 0.11（11） | 1.00（100） | 8 | 0.20（20） | 0.55（55） |
| 3 | Neovide 官方 | 0.15（15） | **0.04（4）** | 1.00（100） | 8 | 0.20（20） | 0.55（55） |
| 4 | 社区·快 | 0.06（6） | 0.04（4） | 1.00（100） | 8 | 0.20（20） | 0.55（55） |
| 5 | 社区·平滑 | 0.10（10） | 0.04（4） | **0.30（30）** | 8 | 0.25（25） | 0.45（45） |
| 6 | 柔和 | 0.18（18） | 0.08（8） | 0.80（80） | **6** | 0.30（30） | 0.40（40） |
| 7 | 高对比 | 0.12（12） | 0.10（10） | 1.00（100） | **10** | 0.15（15） | 0.75（75） |

**全部落在各自滑块的刻度网格上（实测 A-8 的 P1：越界项 = 0）**。

`缎带` 取 `0.28` 而非上限 `0.35` 的理由：`0.35` 是**安全上限**（§7.2），不是**推荐值**；`0.28` 已能表达方案 §1 要的「跨行拉长」，同时留出「用户觉得不够再自己拖」的空间。

### 5.6 【必须】5 个颜色预设的精确取值

| # | 名称 | `tailColor` | `shadowColor` |
|---|---|---|---|
| 1 | 原版粉色（= 默认值） | `#FFC0CB` | `#FFC0CB` |
| 2 | 冰蓝 | `#7DD3FC` | `#38BDF8` |
| 3 | 薄荷 | `#6EE7B7` | `#34D399` |
| 4 | 藕荷 | `#C4B5FD` | `#A78BFA` |
| 5 | 暖沙 | `#FDE68A` | `#FCD34D` |

**HEX 必须大写**（`sanitizeColor` 会统一成大写；预设表若写小写会造成 `equalsIgnoreCase` 匹配但 `equals` 不匹配的双口径）。

### 5.7 【必须】预设应用的原子性

预设只是**往 `pending` 写一组值**（面板场景），**绝不**直接写 `state` / `CONFIG`：

    comboBox.addActionListener(e -> {
        CursorPresets.AnimPreset p = ANIM_PRESETS[combo.getSelectedIndex()];
        if (p == null) return;                    // 「自定义」项，不做事
        pending.animationLength      = p.animationLength();
        pending.shortAnimationLength = p.shortAnimationLength();
        pending.trailSize            = p.trailSize();
        pending.glowLayers           = p.glowLayers();
        pending.shadowBlurFactor     = p.shadowBlurFactor();
        pending.glowOpacity          = p.glowOpacity();
        refreshAllControlsFromPending();           // 把 pending 灌进所有控件（含 slider / label / intTextField）
        refreshPresetDisplay();                    // 重算「当前预设」显示
    });

**「自定义」不是可选项，是显示态的兜底**：下拉永远包含一个末尾的 `自定义` 项，它**不可被选中执行**（选中它不写任何字段），只在 `matchAnimPreset()` 返回 null 时被程序置为当前项。

### 5.8 【禁止】清单（预设相关的第四真相源）

| 禁止项 | 机械判据 |
|---|---|
| 在 `State` 里加 `animPresetName` / `colorPresetName` / `lastPreset` | `grep -c preset settings/NeovideCursorSettings.java` → **必须为 0** |
| 在 `PropertiesComponent` 里存预设名 | `PropertiesComponent` 在 `settings/NeovideCursorSettings.java` 只允许出现在既有迁移代码处 |
| 预设表读 `NeovideConfig` 的实例 | `CursorPresets.java` 里 `new NeovideConfig()` → **必须为 0** |
| 预设直接写 `CONFIG` | `CursorPresets.java` 里 `NeovideCaretManager` 出现次数 → **必须为 0** |

---

## 6. 滑块吸附 + 输入框双向绑定（权威参考实现）

> 本节的原型已编译并跑通 **21/21 断言**（A-4），可直接照抄。

### 6.1 【必须】精确语义

| 场景 | 滑块 | 输入框 | `pending` | 右侧标签 |
|---|---|---|---|---|
| 用户拖滑块 | 跟随鼠标，吸附到刻度 | **同步为该值** | **写入吸附后的值** | 显示该值 |
| 用户键入合法值 | **吸附到最近刻度**（位置变了但值不变） | 保留用户输入 | **仅在 `apply()` 时写**（写精确值） | 显示**用户输入的精确值** |
| 用户键入非法（非数字 / 越界） | 不动 | 保留用户输入 | **不变** | 保持上次合法值 |
| 输入框失焦 | 吸附到（clamp 后的）值 | **规整为合法值文本** | **写入规整后的值** | 显示规整后的值 |
| `reset()` | 吸附到 `pending` 的刻度 | 显示 `pending` 的精确值 | 不动 | 显示 `pending` 的精确值 |

**「精确值 vs 刻度值」的共存是【必须】的**：用户键入 `333` 时滑块只能显示 `350`（吸附），但标签必须显示 `333` —— 否则用户会认为输入被篡改。实测 A-4 的 C1/C2/C3 验证了三者正确并存。

### 6.2 【必须】10 个滑块的刻度表

| # | 字段 | `slider(min,max,minor,major)` | 换算 | 标签格式 | 默认刻度 |
|---|---|---|---|---|---|
| 1 | `cursorDisappearDelay` | `(0, 500, 50, 50)` | 刻度 = 毫秒 | `%d ms` | 50 |
| 2 | `cursorFadeOutDuration` | `(0, 1000, 25, 25)` | `刻度 / 1000f` | `%d ms` | 75 |
| 3 | `tailOpacity` | `(0, 100, 5, 5)` | `刻度 / 100f` | `%.2f` | 100 |
| 4 | `glowOpacity` | `(0, 100, 5, 5)` | `刻度 / 100f` | `%.2f` | 55 |
| 5 | `glowLayers` | `(1, 12, 1, 1)` | 刻度 = 层数 | `%d 层` | 8 |
| 6 | `shadowBlurFactor` | `(5, 60, 5, 5)` | `刻度 / 100f` | `%.2f` | 20 |
| 7 | `trailSize` | `(0, 100, 5, 5)` | `刻度 / 100f` | `%.2f` | 100 |
| 8 | `stretchRatioK` | `(10, 80, 5, 5)` | `刻度 / 10f` | `%.1f× …` | 45 |
| 9 | **`animationLength`** | `(5, 35, 1, 1)` | `刻度 / 100f` | `%.2f s` | 10 |
| 10 | **`shortAnimationLength`** | `(2, 25, 1, 1)` | `刻度 / 100f` | `%.2f s` | 11 |

### 6.3 与方案 §5 的两处差异（**必须**说明，见 §0.2 的 R-2）

| # | 方案 §5 写法 | 本文写法 | 理由（可机械裁决） |
|---|---|---|---|
| S-1 | 淡出时长 `0 / 50 / 100 … 1000` | `0 / 25 / 50 … 1000` | 默认值 `0.075f` = 刻度 **75**，不在 `50` 的网格上 → 一打开设置页就吸附成 50 或 100（**默认值漂移，违反硬纪律 1**） |
| S-2 | 拖尾不透明度 / 辉光峰值不透明度 `0/25/50/75/100` | `0 / 5 / 10 … 100` | 默认 `0.55` = 刻度 55、预设 `0.45` = 45、`0.40` = 40、`0.75` = 75 —— 均不在 `25` 的网格上 |

**判据（V3）**：每个滑块的 `min` / `max` / `step` 必须满足：**默认刻度与全部预设值都落在网格上**（`(v - min) % step == 0`）。已实测 A-8 全过。

### 6.4 【必须】双向同步的抑制标志

    /** 正在由代码改控件 → 监听器不要回写 pending / 不要反向同步。 */
    private boolean fromCode;

**为什么一个标志够**：滑块监听器**写 `pending`** 但只「显示」输入框文字（不提交）；输入框监听器**只挪滑块位置**。两者唯一交叉点是 `syncTextFieldIfDifferent()` 与 `slider.setValue()`，把它们都包在 `fromCode = true` 里即可。实测 A-4 的 G1：`syncAll()` 连调两次幂等，`pending` 不被拉回 `350`。

### 6.5 【必须】非法值的处理（三档）

| 输入 | 键入时 | `apply()` 时 | 失焦时 |
|---|---|---|---|
| 非数字 `abc` | 不写 `pending`（`bindIntText` 的 `backwardFilter` 挡住） | 不写（实测 A-4 F1） | 回滚为 `pending` 的显示值 |
| 越界 `9999` | 不写（`IntRange` 挡住，**不是 clamp**） | 不写（实测 A-4 F2） | **clamp 到上限并写入**（实测 A-4 F4：9999 → 500） |
| 空串 | 不写 | 不写 | 回滚 |

**「越界时不 clamp 而失焦时 clamp」是刻意的**：键入过程中 `9999` 可能是 `999` 的前缀（上限 500 时用户会先经过 9 / 99 / 999），若每次键入都 clamp，输入框会被强行改成 500 而无法继续输入。**必须**等到失焦/回车才规整。

### 6.6 【必须】布局写法（滑块 + 输入框 + 标签同一行）

    g.row("淡出前保持时长", row -> {
        SnapRow sr = new SnapRow(0, 500, 50, 1, " ms", pending.cursorDisappearDelay);
        sr.build(row);                       // 内部先 slider、后 intTextField（§6.7）
        row.cell(sr.valueLabel);             // 右侧数值
        sr.register(() -> pending.cursorDisappearDelay, v -> pending.cursorDisappearDelay = v);
        return kotlin.Unit.INSTANCE;
    });

**三列的先后【必须】**：滑块 → 输入框 → 数值标签。理由：DSL 的 `row.cell()` 按调用顺序追加到**同一行**，`intTextField` 已占第 3 列，数值标签只能是第 4 列。方案 §9 的线框把 `50 ms` 画在输入框左边，**实现上做不到**（不构成违规）；V6 只断言「三个组件在同一条 row 里且顺序为 slider → textField → label」。

### 6.7 【必须】构建顺序（本文最重要的实现约束）

**`slider` 必须先于 `intTextField` 构建。** 证据（实测 A-4 的 O1–O4）：`DialogPanel` 的 apply 回调表是 **`LinkedHashMap`**（javap 实证：`DialogPanelConfig.getApplyCallbacks():LinkedHashMap`），因此**回调按构建顺序执行**：

    A 段（滑块先建）：[A-slider-set(350), A-text-set(333)]   → 最终 pending = 333  ✓
    B 段（输入框先建）：[B-text-set(333), B-slider-set(350)]  → 最终 pending = 350  ✗

用户键入 `333`（不在刻度上）时，若顺序反了，`apply()` 会用滑块的吸附值 `350` **覆盖用户输入的精确值 `333`** —— 一个静默的数据丢失。

---

## 7. animationLength / shortAnimationLength 纳入可调

### 7.1 【必须】范围与换算

| 字段 | 滑块 | 换算 | 范围 | 步长 | 默认刻度 |
|---|---|---|---|---|---|
| `animationLength` | `(5, 35, 1, 1)` | `刻度 / 100f` | **0.05 – 0.35 s** | 0.01 s | 10（= 0.10） |
| `shortAnimationLength` | `(2, 25, 1, 1)` | `刻度 / 100f` | **0.02 – 0.25 s** | 0.01 s | 11（= 0.11） |

**持久化【必须】**：两者**必须**进 `NeovideCursorSettings.State`（新增 2 个 `public float` 字段）。现状实测：`State` 有 **17** 个 `public` 字段（`stateVersion` + 16 个业务字段），新增后为 **19**。并在 `loadState` 里分别 clamp 到 `[0.05, 0.35]` / `[0.02, 0.25]`。

**`syncStateToConfig` / `syncConfigToState` 必须各加 2 行**（否则 XML 存了但运行期读不到）。

### 7.2 【必须】上限 0.35 的量化依据

上限不是拍的。用**真实的 `DampedSpring.update(dt)`** 逐帧推进（`dt = 8ms = ACTIVE_DELAY_MS`，初始位移 32px = 一行），实测（A-11）：

| `animationLength` | 首帧走完位移 | 运动帧数 | 运动时长 |
|---|---|---|---|
| 0.05 | 13.5% | 16 | 128 ms |
| 0.10（默认） | 4.1% | 32 | 256 ms |
| 0.15 | 2.0% | 49 | 392 ms |
| 0.28（缎带） | 0.6% | 92 | 736 ms |
| **0.35（上限）** | **0.4%** | **115** | **920 ms** |
| 0.40 | 0.3% | 131 | 1048 ms |
| 0.50 | 0.2% | 164 | 1312 ms |

**三条判据共同定出 0.35**：

1. **与 `RECOVERY_GAP_SECONDS = 0.1`（`CaretAnimator:115`）的关系**：该常量判「本帧间隔是否跨过了长停顿」；`animationLength` 本身不参与该判据（只进 `DampedSpring`），但**观感上**一段动画若远长于一个「长停顿阈值」，用户切屏回来会看到「拖尾还在动」。实测 `0.35` 的运动时长 **920ms** 是「可见的余韵」；`0.50` 的 **1312ms** 就会出现「回来时上一个光标位置还在拖」。
2. **与 `MAX_DT = 1.0/20 = 0.05`（`CaretAnimator:101`）的关系**：`DampedSpring.update` 首句是 `if (animationLength <= dt) 归零`。若 `animationLength ≤ 0.05`，**任何一次最大 dt 都会把动画一帧短路成瞬移**（实测 A-11 末段：`animLen=0.05 + dt=0.05 → moving=false, position=0`）。这定出了**下限 0.05**，且因为是「闭区间端点即失效」，实际可用的最小推荐值是 0.06。
3. **上限定在 0.35 而非 0.40/0.50**：`0.35` 的运动时长 920ms 已接近 1 秒心理阈值；再长就与「光标跟随」这一基本功能冲突（按键后光标要近 1 秒才落位）。

**必须写进代码注释的一句话**：

    // 上限 0.35s 的量化依据（A-11 实测，驱动真实 DampedSpring、dt=8ms）：
    //   0.35 → 跨行拖尾持续 115 帧 / 920ms，已接近 1 秒心理阈值；
    //   0.50 → 164 帧 / 1312ms，切屏回来会看到「上一个位置还在拖」。
    // 下限 0.05s 是硬边界：DampedSpring.update() 首句 animationLength <= dt 时直接归零，
    //   而 MAX_DT = 0.05s（CaretAnimator:101），因此 animationLength = 0.05 在一帧 dt 下即瞬移。

### 7.3 【建议】警告文案

| 条件 | 文案 | 强度 |
|---|---|---|
| `animationLength > 0.25` | 跨行动画超过 250ms 后，光标落位会明显「慢半拍」；仅在刻意追求「缎带感」时调到这里。 | 【建议】 |
| `shortAnimationLength > 0.15` | 单行动画超过 150ms 后，连续打字会看到光标「追不上」文字；打字手感以 0.04–0.12 为佳。 | 【建议】 |
| 恒显示（动画时序组底部） | 这两个值只影响【动画时长】，不改变拉伸倍率；拉伸倍率见上方「拖尾拉伸强度」。 | 【必须】 |

### 7.4 【必须】与 `trailSize` / `trailFactors` 的耦合提示

**事实链**（`TrailCorner.jump()` `:220-235`）：

- **短跳分支**（同一行、≤3 字符）：`时长 = shortAnimationLength × trailFactors[rank]`（`:221`）
- **跨行分支**：`leading = animationLength × (1 - trailSize)`（`:227`），`trailing = animationLength`（`:228`）

因此：

| 耦合 | 提示文案（【必须】逐字，其中 {N}/{M}/{K} 为运行时计算） |
|---|---|
| `shortAnimationLength` × `stretchRatioK` | 单行动画时长 = 本值 × 拉伸系数：后缘 = shortAnimationLength × 1.00，前缘 = shortAnimationLength × trailFactors[3]。当前设置下后缘 {N} ms / 前缘 {M} ms（系数 {K}×）。 |
| `animationLength` × `trailSize` | 跨行动画时长：后缘恒 = 本值 = {N} ms；前缘 = 本值 × (1 − 拖尾密度) = {M} ms。拖尾密度为 1.00 时前缘为 0（瞬时吸附）。 |

**这两行必须是动态计算的 `String`（不是常量）**，且在 `animationLength` / `shortAnimationLength` / `trailSize` / `stretchRatioK` 任一变化时刷新。**禁止**写死数字。

### 7.5 【禁止】`animationResetThreshold` 进 UI

`NeovideConfig.animationResetThreshold = 0.09f` 是**零读取的死字段**（`grep` 只命中 `NeovideConfig.java:190` 的声明和 `TrailCorner.java:259/268` 的**注释**）。`TrailCorner.jump()` 的 `springX.reset()`（`:269`）是**无条件**执行的（`:263-268` 的注释解释了为什么不再用阈值守卫）。把它做成 UI 等于给一个不存在的旋钮。**禁止**。

---

## 8. Tools 菜单新增两个预设子菜单

### 8.1 【必须】正确的类名（方案写的 `AnActionGroup` 不存在）

`javap com.intellij.openapi.actionSystem.AnActionGroup` → **「找不到类」**（实测 A-9）。本机 381 jar 里存在的是：

- `com.intellij.openapi.actionSystem.DefaultActionGroup`（`extends ActionGroup`，有 `add(...)` / `addSeparator()` / `getChildren(AnActionEvent)`）
- `com.intellij.openapi.actionSystem.ActionGroup`（抽象基类，`getChildren` 抽象）

`plugin.xml` 的 `<group class=...>` 用的就是你写的那个类的全名，平台会实例化它。本机平台自带例子（`product-backend.jar!/META-INF/*.xml` 实测搜到）：

    <group id="SettingsEntryPoint" class="com.intellij.ide.actions.SettingsEntryPointAction" icon="AllIcons.General.GearPlain" />

### 8.2 【必须】实现

    public final class AnimPresetGroup extends DefaultActionGroup {
        public AnimPresetGroup() {
            super(CursorPresets.MENU_ANIM, true);          // true = popup
            for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
                add(new ApplyAnimPresetAction(p));
            }
        }
    }

    public final class ApplyAnimPresetAction extends ToggleAction {
        private final CursorPresets.AnimPreset preset;
        ApplyAnimPresetAction(CursorPresets.AnimPreset p) { super(p.name()); this.preset = p; }

        @Override public boolean isSelected(@NotNull AnActionEvent e) {
            // 与面板同源的判据：matchAnimPreset(state) == preset
            return CursorPresets.matchAnimPreset(currentState()) == preset;
        }
        @Override public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (!state) return;                     // 取消勾选不做事（预设是单选语义）
            CursorPresets.applyAnimPreset(preset);  // 逐字段写 state → applyConfig() → 落盘
        }
    }

### 8.3 【必须】「当前选中项」的显示方式

**用 `ToggleAction` 的勾选态，不用加粗。**

| 方式 | 可行性 | 裁决 |
|---|---|---|
| `ToggleAction.isSelected()` → 勾选标记 | 平台原生（现有 5 个菜单项里 4 个已是 `ToggleAction`） | **【必须】** |
| `Cell.bold()` → 菜单项加粗 | 仅 DSL 面板可用，菜单不适用 | 【禁止】 |
| `Presentation.setText("✓ ...")` | 菜单项宽度跳动，且与 `ToggleAction` 的勾选重复 | 【禁止】 |

**多选问题的处理【必须】**：`ToggleAction` 在 `ActionGroup` 里默认**不是**互斥的。因此 `isSelected` **必须**用 `matchAnimPreset(state) == preset` 而不是「记住上次点了哪个」—— 这样天然保证最多一个勾选（`matchAnimPreset` 只返回一个）。

**`setSelected(e, false)` 的处理【必须】**：用户点已勾选的项时平台会回调 `false`。此时**必须**忽略（不能「取消该预设」—— 取消之后的状态没有定义）。

### 8.4 【必须】菜单顺序

    ToolsMenu
    ├─ Neovide 拖尾光标（开关）                        ← 既有，anchor=first
    ├─ ─────────────────────────────
    ├─ Neovide 光标：性能诊断（禁用重绘）                ← 既有，anchor=last
    ├─ Neovide 光标：残影模式（低帧率更稳）               ← 既有，anchor=last
    ├─ Neovide 光标：隐藏原生光标（常驻绘制）             ← 既有，anchor=last
    ├─ Neovide 光标：辉光淡出（停止后柔和熄灭）           ← 既有，anchor=last
    ├─ ─────────────────────────────                    ← 新增分隔符
    ├─ Neovide 光标：动画预设 ▸                         ← 新增，anchor=last
    └─ Neovide 光标：颜色预设 ▸                         ← 新增，anchor=last

**理由**：既有 5 项是「开关」语义，预设是「切换一整套值」语义；混排会让用户误以为预设也是开关。**两个新组必须排在既有 5 项之后**（`anchor="last"`），因为 `anchor="first"` 只有一个槽位（已被总开关占用）。

### 8.5 【必须】plugin.xml 片段

    <actions>
        ...既有的 5 个 action 保持不变...

        <group id="NeovideCursor.AnimPresetGroup"
               class="com.github.yyyolo7a79.neovidecursor.ui.AnimPresetGroup"
               text="Neovide 光标：动画预设"
               popup="true">
            <add-to-group group-id="ToolsMenu" anchor="last"/>
        </group>

        <group id="NeovideCursor.ColorPresetGroup"
               class="com.github.yyyolo7a79.neovidecursor.ui.ColorPresetGroup"
               text="Neovide 光标：颜色预设"
               popup="true">
            <add-to-group group-id="ToolsMenu" anchor="last"/>
        </group>
    </actions>

**注意【必须】**：`<group>` 里**不能再列 `<action>` 子项** —— 子项由 `DefaultActionGroup` 的构造器动态 `add()`。两处都列会出现重名冲突。

### 8.6 【必须】预设应用后的编排

    /** Tools 菜单专用：全量替换 6 个字段（与面板的「脏字段合并」语义不同，不可合并）。 */
    public static void applyAnimPreset(CursorPresets.AnimPreset p) {
        NeovideCursorSettings svc = NeovideCursorSettings.getInstance();
        NeovideCursorSettings.State s = svc.getState();
        s.animationLength      = p.animationLength();
        s.shortAnimationLength = p.shortAnimationLength();
        s.trailSize            = p.trailSize();
        s.glowLayers           = p.glowLayers();
        s.shadowBlurFactor     = p.shadowBlurFactor();
        s.glowOpacity          = p.glowOpacity();
        svc.persistQuietly();
        NeovideCaretManager.applyConfig();      // ← S1..S8 唯一生效入口（既有）
    }

**为什么预设是「全量替换」而面板是「脏字段合并」**：预设是**用户明确要求的整套替换**（用户知道它会改 6 个字段）；面板 apply 是**用户可能只改了 1 个字段**。两者语义不同，**不能合并成一个方法**。

---

## 9. 布局重排

### 9.1 【必须】线框

    ┌─ 预设 ──────────────────────────────────────────────────────────┐
    │ 动画预设 [原版粉色 ▾]        颜色预设 [原版粉色 ▾]               │  ← 置顶
    └─────────────────────────────────────────────────────────────────┘

    总开关
      启用拖尾光标（总开关）          [x]
      拖尾渲染模式                    [弹簧拉伸（Neovide 原版效果）▾]
      隐藏原生光标（覆盖层充当光标）  [x]
          (i) 隐藏原生光标 = 把全局配色方案的 CARET_COLOR 改成全透明…
      常驻刷新（永远按 8ms 调度）     [x]
          (i) 常驻刷新 = 调度线程每秒向 EDT 投递约 125 次任务…
          (i) 「隐藏原生光标」勾选时覆盖层就是唯一的「光标」…

    淡出
      辉光淡出（停止后柔和熄灭）      [x]
      淡出前保持时长   [====|====]  [ 50 ]   50 ms
      淡出时长         [====|====]  [ 75 ]   75 ms
          (i) 辉光淡出关闭时不使用这两个值…

    颜色与辉光
      拖尾颜色   [@ #FFC0CB]      拖尾不透明度      [====] [100]  1.00
      启用辉光                        [x]
      辉光颜色   [@ #FFC0CB]      辉光峰值不透明度   [====] [ 55]  0.55
      辉光层数         [====|===]  [  8]   8 层
          (i) 层数越多渐变越平滑…
      辉光外扩幅度     [====|===]  [ 20]   0.20
          (i) 0.6 是原版 JS 的夸张光雾（约 40px）…

    ▾ 拖尾手感（可折叠，默认展开）
      拖尾密度         [====|====] [100]   1.00
      拖尾拉伸强度     [===|=====] [ 45]   4.5×   后缘 110 ms / 前缘 24 ms
          (i) 只调整后缘与前缘的时长比（拉伸倍率）…

    ▾ 动画时序（可折叠，默认折叠 = 高级）
      跨行动画时长     [====|====] [ 10]   0.10 s
      短动画时长       [====|====] [ 11]   0.11 s
          (i) 这两个值只影响【动画时长】，不改变拉伸倍率…
          (i) 单行动画时长 = 本值 × 拉伸系数：后缘 {N} ms / 前缘 {M} ms…
          (i) 跨行动画时长：后缘恒 = 本值 = {N} ms；前缘 = 本值 × (1 − 拖尾密度) = {M} ms…

### 9.2 【必须】`collapsibleGroup` 的用法

javap（A-9）确认：

    public abstract CollapsibleRow collapsibleGroup(String title, boolean @NotNull expanded, Function1<Panel, Unit> content);
    // CollapsibleRow: getExpanded/setExpanded/getPackWindowHeight/setPackWindowHeight/setTitle/setTitleFont/addExpandedListener

**必须用 `collapsibleGroup(title, expanded, content)`**，不用 `group(title, isBold, content)`。理由：`group` 是**静态**粗体标题；`collapsibleGroup` 才可折叠（方案 §9 要求「高级折叠」）。

**离线可测性【必须】**（实测 A-12）：`collapsibleGroup` 的构造会调 `ActionManager.getInstance().getAction("CollapsiblePanel-toggle")`，**在没有 Application 的环境下会 NPE**。因此：

- **V6 探针必须注入 `ActionManager` 桩**（`ActionManager` 是**抽象类**不是接口，不能用 `Proxy`，必须手写匿名子类实现 17 个抽象方法）
- 真机上无此问题（平台自带 `CollapsiblePanel-toggle`）
- 探针输出会有 `WARN: Can not find action by id CollapsiblePanel-toggle`，**这是正常噪声**

### 9.3 【必须】垂直间距

javap（A-9）：`Row.topGap(TopGap)` / `Row.bottomGap(BottomGap)`，枚举均为 `{NONE, SMALL, MEDIUM}`。

| 位置 | 间距 | 写法 |
|---|---|---|
| 组与组之间 | `MEDIUM` | 每组第一条 `row` 上 `.topGap(TopGap.MEDIUM)` |
| 同一组内相邻 row | `NONE`（默认） | 不写 |
| 复选框与其 `comment` 之间 | `NONE`（DSL 自动处理） | 不写 |
| 折叠组标题前后 | `MEDIUM` | 在组内首行 `.topGap(MEDIUM)` |

**注意**：`Panel` 接口**没有** `topGap` / `bottomGap`（javap 实证），只有 `Row` 有。因此「给整组加间距」必须落到该组的**第一条 row** 上。

### 9.4 【必须】保留既有文案 + 新增文案

`SETTINGS-CONTRACT.md` §2.2 的 7 条警告文案（`W_ALWAYS_HIGH_FPS` / `W_HIDE_NATIVE` / `W_COUPLING` / `W_GLOW_LAYERS` / `W_SHADOW_BLUR` / `W_STRETCH` / `W_FADE_OFF`）与 §2.4 的 2 条下拉文案**必须逐字保留**（`VERIFY-SETTINGS.md` 的 U18 探针已覆盖）。

**新增文案【必须】逐字**：

| 常量名 | 使用位置 | 文案 |
|---|---|---|
| `W_ANIM_LEN` | 跨行动画时长的 `comment` | 跨行动画时长 = 光标从一行移到另一行时的拖尾时长。上限 0.35s：再长切屏回来会看到「上一个位置还在拖」。 |
| `W_SHORT_LEN` | 短动画时长的 `comment` | 短动画时长 = 同行打字/退格时的拖尾时长。下限 0.05s 是硬边界（单帧最大步长 0.05s，再短会被一帧归零）。 |
| `W_ANIM_COUPLING` | 「动画时序」组底部 | 见 §7.3 第三行（**必须**逐字） |

---

## 10. 禁止项与不做项（可机械校验）

### 10.1 【禁止】改默认值

| # | 判据 | 命令 | 期望 |
|---|---|---|---|
| D0 | `core/NeovideConfig.java` **零 diff**（最强） | `git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java` | exit 0，无输出 |
| D1 | 35 项禁改字面量缺失数 = 0 | `SETTINGS-CONTRACT.md` §5.3 的脚本（**原样可跑**） | `禁改项缺失数 = 0` |
| D2 | 19 项观感常量审计 | `tools/parity/run-audit.ps1` A 段 | **DIFF 数 == 1**，且该项**必须**是 `hideNativeCaret = (true\|false)`；其余 18 项 `SAME` |

> D0 与 D1 是**不同强度**的：D1 只查字面量（有人可以在别的行加代码），D0 查整文件（连注释改动都算）。**两者都必须过**。

### 10.2 【禁止】新增的 3 个「默认值复制点」

| # | 禁止 | 判据 |
|---|---|---|
| N-1 | 在 `CursorPresets.java` 里 `new NeovideConfig()` | `grep -c "new NeovideConfig" core/CursorPresets.java` → **0** |
| N-2 | 在 `NeovideCursorConfigurable.java` 里保留 `SHORT_ANIMATION_LENGTH = 0.11f` 这类「只读镜像常量` | 现有的 `:106` 那一个**必须删除**（改从 `pending.shortAnimationLength` 读）；`grep -c "SHORT_ANIMATION_LENGTH = " ui/settings/NeovideCursorConfigurable.java` → **0** |
| N-3 | 在 `State` 的 18 个字段初值之外再造一份默认值 | `State` 里 `0.1f` / `0.11f` 字面量只允许出现在新增的两个字段声明处（各 1 次） |

> **N-2 是本契约的重要发现**：`NeovideCursorConfigurable:106` 的 `private static final float SHORT_ANIMATION_LENGTH = 0.11f` 注释写着「它是 NeovideConfig 现值的一个只读镜像，不是新的默认值来源」——**一旦 `shortAnimationLength` 变成可调，这个镜像就是错的**（用户调到 0.20 后，「后缘 110ms」的显示就开始撒谎）。**必须删除**，改为从 `pending` 读。

### 10.3 【禁止】三件套不能过

| 命令 | 期望 | 当前基线（已实测） |
|---|---|---|
| `tools/parity/run-parity.ps1` | 三份**逐字节一致**（`PARITY OK` × 3，`ALL PARITY OK`） | 通过（A-13） |
| `tools/parity/run-audit.ps1` | A 段 DIFF=1（`hideNativeCaret`）；**D 段排除 `settings/` 后 = 12** | 通过（A-13：32 总数 → 排除后 12） |
| `tools/ime-probe/run-ime-geom-test.ps1` | **29 项全过** | 通过（A-13） |

**D 段 = 12 的口径【必须】**：`run-audit.ps1` 的 D-1 段 grep 命中 32 处，其中 `settings/NeovideCursorSettings.java` 12 处、`ui/settings/NeovideCursorConfigurable.java` 8 处。**排除 `settings/` 目录后剩 12 处**（`NeovideConfig` 4 + `CaretAnimator` 4 + `CaretTrailPanel` 2 + `NeovideCaretManager` 2）。本契约新增 `cursorDisappearDelay` / `cursorFadeOutDuration` 的引用点**只允许在 `settings/` 与 `ui/settings/` 里**，因此**排除后仍必须 = 12**。

### 10.4 【禁止】不做项（方案 §8 的冻结）

| 项 | 判据（机械） |
|---|---|
| 多光标（Alt 列选） | `grep -c "getCarets\|CaretModel\.getCarets" src/**/*.java` → **0** |
| 残影模式输入平滑 | `core/AfterimageTrail.java` 零 diff |
| 流光 RGB 特效 | `CaretTrailPanel.java` 的 `drawQuadGlow` / `fill` 调用点数不变 |
| 辉光双色渐变 | `grep -c "shadowColor2\|shadowColorEnd" src/**/*.java` → **0** |
| `ACTIVE_DELAY_MS` 可配 | `CaretAnimator.java:77` 字面量不变（已在 D1 的 35 项里） |
| Jewel / Compose UI | `grep -c "@Composable\|org.jetbrains.jewel" src/**/*.java` → **0**；`build.gradle.kts` 零 diff |

### 10.5 【禁止】`@ApiStatus` 支路上的其他 API

| API | 注解（javap 实证） | 判据 |
|---|---|---|
| `Cell.onChanged` / `Cell.onChangedContext` | **类级** `@ApiStatus.NonExtendable`（`Cell.class`） | `grep -c "onChanged" ui/settings/` → **0** |
| `Cell.cellValidation` / `Cell.validationInfo` / `Cell.validation(DialogValidation.WithParameter)` | **方法级** `@ApiStatus.Experimental` | `grep -c "cellValidation\|validationInfo" ` → **0** |
| `Row.twoColumnsRow` / `threeColumnsRow` | 无注解，但会把行拆成多个 Panel，破坏「一条 row = 一个参数」的可测性 | 【建议】不用 |

**`@ApiStatus.NonExtendable` 挂在 `Cell`/`Row`/`Panel`/`CollapsibleRow` 的【类级】上**（javap 实测 A-9）：这不是「某个方法被禁」，而是「整个接口不允许第三方实现/扩展」。用它做业务回调（`onChanged`）在语义上越界；用它做「读组件」（`getComponent()`）和「链式扩展」（`enabledIf`）是官方本意。**本契约的界线**：只要不调 `onChanged` / `onChangedContext`，其余用法与 0.9.4 一致。

### 10.6 【必须】安全（脚本层）

| 项 | 判据 |
|---|---|
| 不用 PowerShell 自动变量做赋值 | 新增/修改的 `.ps1` 里 `\$(home|env|args|input|host|pwd|error|matches)\s*=` → **0 命中** |
| 用 `$repoRoot` / `$tmpRoot` | 同上，且 `grep -c "\$repoRoot|\$tmpRoot"` > 0 |
| 不删用户目录 | 任何 `Remove-Item -Recurse` 的目标必须包含 `$tmpRoot` 或 `build\` 前缀 |

---

## 11. 验收判据 V1–V15

> 每条都必须**可机械裁决**：一条命令 / 一个探针断言，不接受「看起来对」。

| # | 判据 | 裁决方式 | 期望 |
|---|---|---|---|
| **V1** | 4 个缺陷全部修复（含**变异测试**反向验证） | 每条的探针；把修法回滚后探针必须变红 | 4/4 通过 + 4/4 变异后变红 |
| **V2** | **10** 个滑块拖动时标签实时跟随 | 驱动 `slider.setValue(v)`，断言 `label.getText()` 随之变化；并断言每个滑块 `getChangeListeners().length == 1` | **10/10 跟随**，且 listener 数恰为 1 |
| **V3** | 每个滑块的刻度网格覆盖默认值与全部预设值 | 对 10 个滑块 × (默认 + 该滑块参与的全部预设值) 断言 `(v - min) % step == 0` | **0 个越界** |
| **V4** | 预设应用正确、且与默认值同源 | 逐预设逐字段 before/after；断言「原版粉色」6 个字段 == `NeovideConfig` 默认值 | 7 + 5 组全对；预设 1 逐位等于默认值 |
| **V5** | 动画与颜色预设**正交** | 换动画后断言两个 HEX 不变；换颜色后断言 6 个动画字段不变 | 双向均不变 |
| **V6** | Tools 改动**不被**面板覆盖；用户拖动**不被** Tools 覆盖 | 复现缺陷 3 场景两次（用户没碰该字段 / 用户碰了该字段） | 第一次保住 Tools、第二次保住用户（A-7 的 C1/C2） |
| **V7** | 切主题后原生光标**不复活** | 仿真 scheme 变更 → 断言 `applyNativeCaretHidden(true)` 被调用、透明色重新生效、`savedCaretColor` 更新为**新** scheme 的原色 | A-10 的 D4/D5 |
| **V8** | 默认值零改动 | D0（零 diff）+ D1（35 项缺失数 0） | 都过 |
| **V9** | 三件套全过 | `run-parity.ps1` / `run-audit.ps1` / `run-ime-geom-test.ps1` | 3 份一致 / DIFF=1 且是 hideNativeCaret、D 段排除 settings 后=12 / 29 项 |
| **V10** | 帧率不回归 | p50/p95/p99，≥3 轮 | 与 0.9.4 同档（噪声内） |
| **V11** | `isModified()` 的**双向**正确性（**单一判据，B-1 与 B-2 必须同时成立，缺一即失败**） | 见 §11.1 的精确措辞 | §11.1 的 4 条断言全过 |
| **V12** | 精确值（不在刻度上）**不被** `apply()` 覆盖（**新增**） | 键入 `333`（步长 50）→ apply → 断言落库值 == `333` | A-4 的 C4/E5/E6 |
| **V13** | 构建顺序：滑块必须先于输入框（**新增**） | 反射读 `DialogPanelConfig.getApplyCallbacks()` 的 `LinkedHashMap` key 顺序，断言每行的 slider 在 textField 之前 | 10/10 行满足；反例（顺序反）探针必须变红 |
| **V14** | 预设**不持久化**（**新增**） | (a) `State` 的 `public` 字段数 == **19**（`stateVersion` + 16 既有业务字段 + 2 新增）；(b) 生成的 XML 里无 `preset` 字样；(c) `grep -c preset settings/NeovideCursorSettings.java` == 0 | 3/3 |
| **V15** | 无第四真相源（**新增**） | N-1/N-2/N-3 三条（§10.2）全部 0 命中；且 `CursorPresets` 里 `NeovideCaretManager` 出现次数 == 0 | 4/4 |

### 11.1 V11 的精确措辞【必须逐字】

> **V11（单一判据，两条必须同时成立，缺一即失败）**
>
> 1. **改任一滑块 → `isModified()` 必须为 `true`**（防 B-1：应用按钮变灰导致改动静默丢失）
> 2. **精确值 `333` 与刻度 `350` 不一致时，`isModified()` 必须为 `false` 且滑块仍是 `350`**（防 B-2：OK 按钮永远亮着）
>
> 两条必须**同时**成立，缺一即失败。

**为什么必须合成一条**（这是 captain 的裁决，也是本文附录 B 的技术依据）：B-1 与 B-2 是**对向陷阱** ——

| 只修 B-1 | 只修 B-2 | 同时修 |
|---|---|---|
| `isModified()` 自比 `pending vs state` | getter 用 `snapToTick(pending)` | 两者都做 |
| → 精确值 333 与刻度 350 不一致时**恒真**，踩 B-2 | → 组件值 == getter，`super.isModified()` **恒 false**，踩 B-1 | → **两条断言同时成立** |

若 V11 被拆成两条独立判据，实现者完全可能只做一条就宣称"V11 部分通过"。**合成一条 = 不给只修一半的余地**。

**V11 的探针断言（4 条，必须全部为真）**：

    // 断言 1（防 B-1）：拖动滑块 → isModified() == true
    slider.setValue(250);
    assertTrue(configurable.isModified());

    // 断言 2（防 B-1 的另一半）：只点复选框 → isModified() == true
    //   实测现状为 true（bindSelected 也不挂 ItemListener，setter 只在 apply 时跑）；
    //   改完之后必须仍是 true —— 这是一条「不得回归」断言。见附录 A-2 的更正。
    checkBox.doClick();
    assertTrue(configurable.isModified());

    // 断言 3（防 B-2）：键入精确值 333（步长 50）→ 滑块吸附到 350，
    //                 但 isModified() 必须仍能正确回落为 false（不是永真）
    textField.getDocument().remove(0, len); textField.getDocument().insertString(0, "333", null);
    apply();                                   // 让 333 落库
    assertEquals(333, state.cursorDisappearDelay);
    assertEquals(350, slider.getValue());
    assertFalse(configurable.isModified());    // ← 若 getter 未用 snap，这里会是 true

    // 断言 4（防 B-2 的边界）：reset() / apply() 后 isModified() == false
    configurable.reset();
    assertFalse(configurable.isModified());

**变异测试要求【必须】**（与 V1 同口径）：把 §1.3 的「修法 2」单独回滚 → 断言 1 与 2 必须变红；把「修法 3」单独回滚 → 断言 3 必须变红。两次变异都必须只影响自己那两条，**不能连带**（否则说明两个修法耦合过度，实现需要重写）。

**变异必须「忠实还原」【必须】**（`LESSONS.md` 坑 12）：

> 变异后**必须** `git diff` 确认形态与修复前**逐字一致**，再跑探针。
>
> **反例（上一轮真实发生）**：t8 复现 R-01 时，第一次只「在顶部插一条早退」、**保留了同步后的重判** ——
> 探针仍然全绿，于是错误地得出「R-01 不成立」。改成**删掉重判**的忠实还原后才变红。
>
> **判据**：变异后 `git diff` 的删除行必须**恰好**是本修法引入的那几行，不多不少。
> 若「回滚」后还残留修复痕迹（多余的守卫、重复的判空、顺序未还原），该次变异**无效**，
> 必须重做 —— 否则「探针没变红」会被误读成「修法不必要」。

**三次变异的具体形态【必须】**：

| 变异 | 忠实还原的形态 | 期望变红的断言 |
|---|---|---|
| **MUT-1** 回滚修法 2 | `isModified()` 改回 `return super.isModified() \|\| colorPanelModified(st)`（即 0.9.4 的 `:473-488` 原样） | V11 断言 1、2 |
| **MUT-2** 回滚修法 3 | 滑块 getter 改回 `() -> pending.<字段>`（不 snap） | V11 断言 3 |
| **MUT-3** 回滚修法 1 | 删掉 `slider.addChangeListener(...)` 整段 | V2（标签跟随）+ V11 断言 1 |

---

## 附录 A：证据索引（javap / 实测 / 源码行号）

> ⚠️ **A 段证据是临时的，权威化在 t4。**
>
> 本附录的 13 组探针全部在临时目录（`%TEMP%\neovide-ui-probe`），**未入库、不可复跑**。
> 它们的价值是「证明本契约的裁决有实测依据」，**不是「可执行的验收证据」**。
>
> - **审查方不得把 A 段当作「探针已入库」** —— 它们不在仓库里。
> - **权威形态由 t4 交付**：verifier 必须把它们（或等价重写）固化为 `tools/settings-ui-probe/`，
>   并对 V1 / V11 / V12 / V13 做**变异测试反向验证**（回滚修法后必须变红）。
> - 在 t4 交付之前，**A 段只能作为「设计依据」引用，不能作为「验收通过」引用**。
>
> 编译固定为 `javac --release 21 -nowarn -encoding UTF-8 -classpath <381 jars 拼成的 cp>`；
> 运行固定带 `-Djava.awt.headless=true` + 4 个 `--add-opens`（`java.desktop/javax.swing` / `java.awt` / `sun.swing` / `javax.swing.text.html`）+ 假 `idea.home.path`。

### A-1 `SliderBindingProbe`（缺陷 1 根因 + 缺陷 2 根因）

| 断言 | 结果 |
|---|---|
| `bindValue` 后 `getChangeListeners().length` | **0** |
| `onChanged` 后 / 显式 `addChangeListener` 后 | 1 / 1 |
| `bindValue` 后 `setValue(120)`：setter 调用次数 / 标签 | **0 次 / 不跟随** |
| `onChanged` 后同上 | 1 次 / 跟随到 `120` |
| 显式 `addChangeListener` 后同上 | 1 次 / 跟随到 `120` |
| `slider(0,500,50,10)` 的 `labelTable` 条目 | **51** |
| `slider(0,1000,5,5)` 的条目 | **201** |
| `labelTable(cell, {0,500})` 后条目 | **2** |
| `apply()` 后 `bindValue` 的 setter 才被调用 | +1 次（证明「面板级双向」） |
| `setSnapToTicks(true)` + `setValue(37)` → `getValue()` | **25** |

### A-2 `IsModifiedProbe`（**L3 次生缺陷**）

| 断言 | 结果 |
|---|---|
| 现状（`bindValue`，无 listener）拖动后 `pending` | 不变（50），`isModified()=true` |
| 补 listener（写 `pending`）后拖动 `isModified()` | **false** ← 应用按钮变灰 |
| 用 `pending != state` 自写判据 | **true**（正确） |

**补充：复选框与滑块是「同构」的**（`BindSelectedWiringProbe` + `CheckboxV11Probe`，**本文 v1.1 更正**）：

```
构建后（reset 前）：ItemListener=0  ChangeListener=1  ActionListener=0
doClick() 后：     pending 不变、setterCalls=0、isModified()=true
apply() 后：       >> bindSelected setter v=false、pending 才变
```

**关键事实**：`bindSelected(MutableProperty)` 内部走的是 `Cell.bind(...)`，**同样只挂 onApply / onReset / onIsModified，不挂 ItemListener**。
所以「点击复选框 → 立即写 pending」**是错的** —— setter 只在 `apply()` 时被调用。复选框与滑块**完全同构**：

| | 点击/拖动后 pending | isModified() 现状 |
|---|---|---|
| 复选框 | **不变**（setter 只在 apply 时跑） | **true** |
| 滑块 | **不变**（bindValue 不挂 listener） | **true** |

**这条更正很重要**：v1 曾写「复选框场景 0.9.4 已有 B-1 同款缺陷」，**实测证明不成立** —— 现状两者都是 `true`，因为 pending 都没被提前写。B-1 是**修法引入的**新缺陷，不是既有缺陷。

### A-3 `IntTextTimingProbe`（`bindIntText` 的真实时机）

同 §1.2 的表。

### A-4 `ContractRefProbe`（**21/21 全过**，本契约 §6 的权威原型）

覆盖：初始五者一致 / 拖动跟随 / 脏标记 / Tools 不被覆盖 / 用户拖动不被覆盖 / 基线前移 / 精确值 333 / apply 保住精确值 / 非法值与越界 / 失焦 clamp / `reset()` 幂等。

关键输出：

    拖到 250：pending=250 slider=250 text=250 label=250 ms
    B4 拖滑块 → isModified()=true（super=false, dirty=true）
    C1 用户拖动的 250 赢了 Tools 写入的默认 50
    C2 用户没碰的 overlayFadeOut：Tools 的 false 被保住
    键入 333：slider=350 text=333 label=333 ms
    E5 输入框最后写 → pending 保住精确值 333
    F2 越界 9999：pending 不变（IntRange 拒绝，不是 clamp）
    F4 9999 失焦规整后：pending=500 text=500

**apply 回调顺序的反例**（`OrderProbe2`，V13 的依据）：

    A 段（滑块先建）：[A-slider-set(350), A-text-set(333)]   → modelA = 333 ✓
    B 段（输入框先建）：[B-slider-get, B-slider-set(350)]    → modelB = 350 ✗

### A-5 `FireCountProbe`（吸附下的事件次数）

| 场景 | 事件序列 |
|---|---|
| 无吸附 `setValue(120)` | `[120]`（1 次） |
| **吸附** `setValue(120)` | `[100, 100]`（**2 次**） |
| 吸附 `setValue(150)`（正好在刻度） | `[150]`（1 次） |

### A-6 `RowImpl.slider` 的字节码（`javap -p -c`）

    new JSlider
    UIUtil.setSliderIsFilled(slider, true)
    slider.setPaintLabels(true)      ← 硬编码
    slider.setPaintTicks(true)       ← 硬编码
    slider.setPaintTrack(true)
    slider.setMinimum(min); slider.setMaximum(max)
    slider.setMinorTickSpacing(3rd); slider.setMajorTickSpacing(4th)

### A-7 `DirtyFieldProbe` + `ComposeProbe`（缺陷 3 的定量对照）

| 断言 | 结果 |
|---|---|
| 现状 `apply()`：Tools 的 `overlayFadeOut=false` | **被冲回 `true`**（复现缺陷 3） |
| 修法 (b)：同场景 | **`false` 保住 + 用户改动同样生效** |
| 修法 (b)：用户没碰的字段 | `delay=50, layers=8` 不变 |
| 同一字段双写 | 面板赢 |
| 用户改回原值 + Tools 同改 | Tools 赢（已知语义，§3.5） |
| 组合（补 listener + 自写 isModified） | 拖动 `250` 赢 Tools 的 `100`，`fadeOut=false` 保住 |

### A-8 `PresetGridProbe` + `FloatRoundTripProbe`

| 断言 | 结果 |
|---|---|
| 7 个动画预设 × 6 字段全部落在网格上 | **越界 0** |
| 6 个默认值在网格上 | **漂移 0** |
| 越界刻度 clamp | `4→5`，`36→35` |
| 7 个 float 默认值的「刻度 ↔ float」往返 | **逐位一致 7/7** |

### A-9 `javap` 的平台 API 事实（本机 `D:\deepseekwork\_idea-anim\idea-libs`，381 jar）

| 事实 | 结论 |
|---|---|
| `EditorColorsListener` | **接口，1 个方法** `void globalSchemeChange(EditorColorsScheme)` |
| `EditorColorsManager.TOPIC` | `public static final Topic<EditorColorsListener>`，构造为 `Topic(Class, BroadcastDirection.TO_DIRECT_CHILDREN)` |
| `EditorColorsManager.getSchemeModificationCounter()` | `abstract long`（存在，但**禁用作变更通知**，§4.4） |
| `MessageBus.connect()` / `connect(Disposable)` | 均存在；`connect()` **未标 `@Deprecated`** |
| `SimpleMessageBusConnection.subscribe(Topic<L>, L)` / `disconnect()` | 均存在 |
| `AnActionGroup` | **不存在**（「找不到类」） |
| `DefaultActionGroup` | 存在，10 个构造器；`add/addSeparator/getChildren` 齐全 |
| `ToggleAction` | 抽象类，`isSelected(AnActionEvent)` / `setSelected(AnActionEvent, boolean)` 抽象 |
| `Panel.collapsibleGroup(String, boolean, Function1)` | 存在，返回 `CollapsibleRow`，**无 `@Deprecated`** |
| `CollapsibleRow` | `getExpanded/setExpanded/getPackWindowHeight/setPackWindowHeight/setTitle/setTitleFont/addExpandedListener` |
| `Row.topGap(TopGap)` / `bottomGap(BottomGap)` | 存在；枚举 `{NONE, SMALL, MEDIUM}` |
| `Panel` | **没有** `topGap/bottomGap`（只有 `Row` 有） |
| `SliderKt.labelTable(Cell, Map)` | 存在；内部 `new Hashtable(map)` → `JSlider.setLabelTable` |
| `SliderKt.showValueHint(Cell)` | 存在；**内部会 `addChangeListener`** 只改 tooltip |
| `TextFieldKt.bindIntText(Cell, Function0, Function1)` | 存在；内部走 `Cell.bind(...)` |
| `Row.intTextField(IntRange, Integer)` | 存在；内部设 `validationOnInput(IntRange 校验)` + `INT_TEXT_RANGE` client property + `KeyListener` |
| `Cell` / `Row` / `Panel` / `CollapsibleRow` 类级注解 | **全部 `@ApiStatus.NonExtendable`** |
| `Cell.cellValidation` / `validationInfo` / `validation(WithParameter)` | 方法级 **`@ApiStatus.Experimental`** |
| `SliderKt` / `ButtonKt` / `TextFieldKt` / `CollapsibleRow` 的方法级注解 | **无** |
| `DialogPanelConfig.getApplyCallbacks()` | 返回 **`LinkedHashMap`** → apply 回调顺序 == 构建顺序 |

### A-10 `SchemeListenerProbe`（缺陷 4）

| 断言 | 结果 |
|---|---|
| ① 纯 Java 可实现 `EditorColorsListener` | 编译通过 + `isInterface=true` |
| ② 只有 1 个方法 | `getDeclaredMethods().length == 1` |
| ③ `TOPIC.getListenerClass() == EditorColorsListener.class` | 成立；`TO_DIRECT_CHILDREN`，`immediate=false` |
| ③b lambda 可用 | 成立 |
| D1 首次隐藏：`saved=0xA9B7C6, caret=0x00000000` | 成立 |
| D2 切 Monokai 后 `caret=0xFF0000`（透明丢失） | 成立 |
| **D3 现有实现再次隐藏：`执行=false, caret=0xFF0000`** | **缺陷复现** |
| **D4 强制重入：`执行=true, caret=0x00000000, saved=0xFF0000`** | **修法验证** |
| D5 恢复用新 scheme 的原色 | `0xFF0000` |
| D6 新 scheme 已全透明时 `sanitizeCaretColor` 兜底 | `0xA9B7C6` |

### A-11 `SpringBoundProbe`（上限 0.35 的量化依据，驱动**真实的** `DampedSpring`）

见 §7.2 的表。补充末端：

    0.05  + 一帧 dt=0.05 → moving=false, position=0.0000    ← 短路归零
    0.06  + 一帧 dt=0.05 → moving=true,  position=4.9468
    0.10  + 一帧 dt=0.05 → moving=true,  position=12.9922
    0.35  + 一帧 dt=0.05 → moving=true,  position=28.3973

### A-12 `CollapsibleProbe2`（`collapsibleGroup` 的离线可测性）

| 断言 | 结果 |
|---|---|
| 无 Application 时构建 `collapsibleGroup` | **NPE**（`ActionManager.getInstance()`） |
| 注入 `ActionManager` 匿名桩后 | **构建成功**；组件 `{CollapsibleTitledSeparatorImpl, DialogPanel, JLabel×4, JSeparator}` |
| 输出噪声 | `WARN: Can not find action by id CollapsiblePanel-toggle`（正常） |

### A-13 基线三件套（**本契约冻结前实测**）

| 命令 | 结果 |
|---|---|
| `run-parity.ps1` | `PhysicsParity / GeometryParity / TrailParityTest` 三份 `PARITY OK`，`ALL PARITY OK` |
| `run-audit.ps1` | A 段 19 项 **DIFF=1**（`hideNativeCaret`，基线 false → 现 true）；D-1 命中 32 → **排除 `settings/` 后 = 12** |
| `run-ime-geom-test.ps1` | **29 项全过**，`ALL OK（29 项边界全通过）` |
| 35 项禁改字面量 | **缺失数 = 0** |

---

### A-14 `RefImpl3Probe`（**§1.3.1 参考实现的权威验证，23/23 全过**）

> ⚠️ 同样按附录 A 开头的声明：**这是临时证据，权威化在 t4**。

本探针与 §1.3.1 的伪代码**逐字一致**（`dirtyAnyField` / `eqHex` / `snapToTick` / `isModified()` / `applySettings()` / `resetSettings()` 全部照抄）。
**23 个断言全过**，关键输出：

    拖到 250：pending=250 slider=250 label="250 ms" text="250" super=false
    V11-1  拖滑块 → isModified() = true        ← 靠 dirtyAnyField（super 已是 false）
    B-1a   super.isModified() 已是 false        ← 证明 ② 不可省
    V11-4b apply() 后 isModified() = false  state.delay=250
    V11-2  只点复选框 → isModified() = true     ← 不得回归
    B-1e   只点复选框 → pending 仍未被提前写     ← 复选框不受修法影响
    键入 333：pending=250 slider=350 label="333 ms" isModified=true
    V11-3a apply() 后 state.delay=333            ← 精确值落库
    V11-3b 滑块仍是 350
    V11-3c isModified() 回落 false（不是永真）    ← 防 B-2 的核心
    V11-4c reset() 后 isModified() = false（pending=333）
    S1..S6 snapToTick(333)=350 / (37)=50 / (-9)=0 / (9999)=500 / (25)=50（.5 向上）/ 覆盖 10 滑块端点

**这组断言的价值**：它证明「修法 1 + 2 + 3 三者放一起是自洽的」，且**单独看任何一条都会得出错误结论**
（只回滚修法 2 → V11-1 变红；只回滚修法 3 → V11-3 变红）。

## 附录 B：本契约新增的 3 条「次生缺陷」

> 这三条**不在** `START-HERE.md` §6.5 与 `SETTINGS-UI-REDESIGN.md` 里，是本契约在实测中发现的。它们**都由「修 4 个缺陷」的动作本身引入或暴露**，因此必须一并纳入验收。

| # | 次生缺陷 | 由什么触发 | 机械判据 | 修法 |
|---|---|---|---|---|
| **B-1** | 补 `addChangeListener` 后 `isModified()` **恒 false**，应用按钮变灰，改动静默丢失 | 修缺陷 1 的 (a) 方案 | **V11 第 1 条**（§11.1） | 自写 `isModified()`（§1.3 修法 2） |
| **B-2** | 吸附滑块 + 精确值共存时 `isModified()` **永真**，OK 按钮永远亮着 | 修缺陷 1 + §6 的「精确值」设计 | **V11 第 2 条**（§11.1） | 滑块 getter 返回 `snapToTick(pending)`（§1.3 修法 3） |
| **B-3** | 若输入框先于滑块构建，`apply()` 会用吸附值**覆盖**用户的精确输入 | §6 的双向绑定 | V12 / V13 | 构建顺序固定「滑块 → 输入框」（§6.7） |

**B-1 与 B-2 是「对向陷阱」**：只修 B-1（`isModified` 自己比 pending vs state）会踩到 B-2（精确值与刻度不同 → 恒真）；只修 B-2（getter 用 snap）会踩到 B-1（组件值 == getter → `super.isModified()` 恒 false）。**两者必须同时修**：`isModified()` 自己比 `pending vs state` **且** getter 用 `snap`。实测 A-4 的 A2/B4/C5/E7 证明这套组合五态自洽。

---

## 附录 C：v1 冻结记录

| 项 | 值 |
|---|---|
| 冻结日期 | 2026-10-20 |
| 基线 HEAD | `0e323df` |
| 前置契约 | `SETTINGS-CONTRACT.md` v2.1（继承 §3 与 §5） |
| 上游方案 | `SETTINGS-UI-REDESIGN.md`（三处显式修订见 §0.2） |
| 实测探针 | 13 组（A-1 ~ A-13），**全部在临时目录，未入库**；**权威化在 t4**（见附录 A 的红字声明） |
| 条文强度标记出现次数 | 【必须】59 / 【禁止】17 / 【建议】7（含正文引用，非章节数） |
| 版本 | **v1.2**（v1 经 captain 独立复核通过；v1.1 三处增补 + 一处自我更正；v1.2 再增 §1.3.1 参考实现与变异忠实性要求，见 C.1） |

### C.1 v1 → v1.1 修订记录（三处增补 + 一处自我更正）

> captain 已独立验证了 A 段的 7 条关键论断（AnActionGroup 不存在 / DefaultActionGroup 存在 /
> EditorColorsListener 单方法 / TOPIC 存在 / labelTable 签名 / 三个 callback map / :106 谎言常量），
> 并逐项复算了 R-2 的刻度漂移（按本文步长 12 项零漂移；按原方案步长 4 处漂移）。
> 本节记录他要求的**五处增补**（M-1/M-2/M-3/M-5/M-6）与本文的**一处自我更正**（M-4），**不改任何设计裁决**。

| # | 位置 | 修订内容 | 依据 |
|---|---|---|---|
| M-1 | §11 的 V11 行 + **新增 §11.1** | V11 从「5 条分散断言」改为**「单一判据，两条必须同时成立，缺一即失败」**：<br>① 改任一滑块 → `isModified()` 必须为 `true`（防 B-1）<br>② 精确值 333 与刻度 350 不一致时，`isModified()` 必须为 `false` 且滑块仍是 350（防 B-2）<br>并补 4 条探针断言 + 两次独立变异要求 | captain 原话：「请在契约里写成一条单一判据，避免实现者只修一半」 |
| M-2 | 附录 A 开头 | 加**红字声明**：A 段证据是**临时的**，权威化在 **t4**；审查方不得把它当「探针已入库」；t4 交付前只能作「设计依据」不作「验收通过」引用 | captain 原话：「契约里请注明『A 段证据是临时的，权威化在 t4』，这样审查时不会误以为探针已入库」 |
| M-3 | 本节 | 新增 C.1 修订记录，保留 M-1/M-2 的可追溯性 | 契约自身要求「增删必须显式说明」（§0.2 前置契约的同一口径） |

**M-5｜变异必须「忠实还原」（v1.2 追加，来自 captain）**

captain 要求把这条写进 §11.1：**每次变异后必须 `git diff` 确认形态与修复前逐字一致，再跑探针。**
依据是 `LESSONS.md` 坑 12（上一轮 reviewer 复现 R-01 时，第一次只插顶部早退、保留了同步后的重判，
探针仍全绿，于是错误地得出「R-01 不成立」）。已写入 §11.1，并给了三次变异的**具体形态表**：

| 变异 | 忠实还原的形态 | 期望变红的断言 |
|---|---|---|
| MUT-1 回滚修法 2 | `isModified()` 改回 0.9.4 的 `:473-488` 原样 | V11 断言 1、2 |
| MUT-2 回滚修法 3 | 滑块 getter 改回 `() -> pending.<字段>`（不 snap） | V11 断言 3 |
| MUT-3 回滚修法 1 | 删掉 `slider.addChangeListener(...)` 整段 | V2 + V11 断言 1 |

**M-6｜新增 §1.3.1「参考实现」（v1.2 追加，来自 captain）**

captain 要求：「把 §1.3 的修法 2 / 修法 3 写成可直接照抄的伪代码（含 `isModified()` 自比 pending vs state
的具体写法、getter 的 snap 实现），这样 impl-core 照着写就行，不用自己再推一遍」。

本文交付的 **§1.3.1** 不是「应该这样」，而是**已编译、已跑通 23/23 断言**的骨架（A-14）：

| 小节 | 内容 |
|---|---|
| **A** | 公共谓词 `dirtyAnyField` / `eqHex`（`isModified()` 与 `apply()` 共用同一份） |
| **B** | `snapToTick`（clamp + 吸附，**缺一即踩 B-2**） |
| **C** | `isModified()` 的两半，含「为什么 ② 不可省」的实测依据（拖到 250 后 `super=false`） |
| **D** | 滑块完整构建（吸附 + 3 关键刻度 + `setPaintLabels(true)` + 修法 1 的 listener + **输入框后建**） |
| **E** | 双向同步三个辅助（`syncingControls` 单标志 + `normalizeTextField` 失焦规整） |
| **F** | `apply()` / `reset()`（脏字段**逐字段**合并 + baseline 前移） |
| **G** | 23 个断言证明什么 |
| **H** | 【禁止】把 `dirtyAnyField` 写成「任一字段不同就整份替换」（那是缺陷 3 的原缺陷） |

**M-4｜本文的自我更正（v1 → v1.1 追加）**

v1 的附录 A-2 曾写「复选框场景 0.9.4 就有 B-1 同款缺陷（点击后 isModified()=false）」。**该结论是错的，已更正**：

```
构建后：ItemListener=0  ChangeListener=1  ActionListener=0
doClick() → pending 不变、setterCalls=0、isModified()=true
apply()   → bindSelected setter 才被调用、pending 才变
```

**原因**：`bindSelected(MutableProperty)` 与 `bindValue` 走同一条 `Cell.bind(...)`，**都不挂 ItemListener / ChangeListener**；setter 一律只在 `apply()` 时调用。

**更正后的结论**：复选框与滑块**完全同构**（点击/拖动都不写 `pending`，`isModified()` 现状都是 `true`）。
**B-1 是修法引入的新缺陷，不是既有缺陷** —— 这反而加强了「V11 必须合成一条单一判据」的理由：
实现者一旦给滑块补 listener，就会把 `isModified()` 从「真阳性」打成「恒 false」，而复选框不受影响，
于是**同一个面板里滑块与复选框的 `isModified()` 行为会分叉** —— 这种分叉只有合成判据能抓住。

**未修订的两处**（captain 已明确采纳本文裁决，无需改动）：

- **R-1**（缺陷 3 否决方案 (c) 改 (b)）：captain 裁决「采纳你的否决」，理由认同「(c) 只是把『Tools 被覆盖』换成『用户被覆盖』」。
- **R-2**（刻度步长修订）：captain 逐项复算确认「按你给的步长全部零漂移；按我方案的步长 4 处漂移」，确认原方案会违反硬纪律 1。

---

## 附录 D：下游实现的最小顺序（违反即返工）

1. §5.4 建 `core/CursorPresets.java`（纯常量，无依赖）
2. §7.1 给 `State` 加 2 个字段 + `loadState` clamp + 两个 sync 方法各加 2 行
3. §4 给 `NeovideCaretManager` 加 `applyNativeCaretHidden(boolean force)` + `CaretSchemeWatcher`
4. §3 给面板加 `baseline` + 重写 `isModified()` + 脏字段 `apply()`
5. §1 / §2 / §6 重写 10 个滑块的构建（吸附 + labelTable + listener + intTextField）
   —— **直接照抄 §1.3.1 的骨架**（已跑通 23/23，A-14），不要自己再推三层缺陷
6. §9 布局重排
7. §8 加两个 `DefaultActionGroup` + `plugin.xml`
8. 跑三件套 + V1~V15
