# 重构的真实收益：能解决什么、不能解决什么

> 2026-10-27。基于实测代码。回答用户三个具体痛点：
> ① 参数保存 ② 职责划分（持久级 vs 预设） ③ 加功能时面板混乱

---

## 一、先说结论

**重构不能修 bug，但能消灭「加功能时必然产生 bug」的那套机制。**

你提的三个痛点里，**第二个（职责划分）已经有半个答案在代码里了，只是没做完**。
第一个（参数保存）是纯机械劳动，重构能归零。
第三个（面板混乱）跟重构关系最弱 —— 那是另一个问题。

---

## 二、实测：现在的真实结构

### 2.1 `SettingsField` 已经是个不错的抽象

`SettingsField.java:69-106` 的字段：

```java
public final String key;              // 字段名
public final Kind kind;               // 类型
public final boolean syncsToConfig;   // 是否进 NeovideConfig
public final boolean inPreset;        // 是否进预设域
```

**已经有两个分类标志了。** 这比 CLAUDE.md 描述的状态好得多。

### 2.2 `PRESET_FIELDS` 不是另一张表 —— 它是筛出来的

`SettingsField.java:472-479`：

```java
private static List<SettingsField> buildPresetFields() {
    List<SettingsField> out = new ArrayList<>();
    for (SettingsField f : FIELDS) {
        if (f.inPreset) {          // ← 自动筛选
            out.add(f);
        }
    }
    return Collections.unmodifiableList(out);
}
```

**CLAUDE.md 说「26 ≠ 14 是两套机制」—— 实际上 `PRESET_FIELDS` 是 `FIELDS` 的子集，
靠 `inPreset` 标志自动筛出来的。不是手写维护的第二份清单。**

### 2.3 但 `RUNTIME_SWITCH_KEYS` 是真空档

`SettingsField.java` 里：

```java
public static final String[] RUNTIME_SWITCH_KEYS = {
        "enabled", "hideNativeCaret", "trailMode", "alwaysHighFps",
        "targetFps", "cursorWidthPx", "cursorHeightRatio",
        "diagLogging", "multiCursorNoGlow"
};
```

**这是 9 个字符串，跟 `inPreset` 标志平行维护。**

实测确认：这 9 项**全都在 `FIELDS` 里登记过**，也都有 `inPreset` 值。
但它们被**单独用一份字符串数组**标记为「运行时开关，不进预设」。

**这就是「职责划分」混乱的精确位置**：
一个字段属不属于预设，由两处决定 —— `inPreset` 布尔值 + `RUNTIME_SWITCH_KEYS` 字符串数组。
两处不一致时，谁赢？

`restoreSkipping(s, snap, RUNTIME_SWITCH_KEYS)` —— 用字符串数组赢。
但 `snapshot()` 用 `PRESET_FIELDS`（即 `inPreset`）决定存哪些。

**存和恢复用的不是同一个判据。**

---

## 三、回答你的三个痛点

### 痛点 1：参数保存

**现状：6 层，其中 2 层手写**

```
① State 字段声明            手写（必须）
② SettingsField.FIELDS      手写（但是表）
③ State.copyFrom()          手写 30 条    ← 漏了就「一应用就回滚」
④ loadState()               手写 12 条+迁移 ← 漏了就「重启即失效」
⑤ 消费者代码                手写（必须）
⑥ UI 控件                   手写（必须）
```

**重构能做的**：把 ③④ 改成从 `FIELDS` 派生 → 加字段从「改 6 处」变成「改 4 处」，
且漏 ③④ 的可能性**归零**。

**重构不能做的**：⑤（谁读它）和 ⑥（面板控件）永远手写。
而且这两层才是最隐蔽的 —— `targetFps` 那次就是漏了 ⑤。

**所以重构解决的是「机械拷贝」，不是「语义接线」。**

### 痛点 2：职责划分（持久级 vs 预设）—— **这是重构收益最大的地方**

**现在的问题**：

| 概念 | 存在哪 | 谁读 |
|---|---|---|
| 是否进 CONFIG | `SettingsField.syncsToConfig` | `syncStateToConfig` |
| 是否进预设快照 | `SettingsField.inPreset` | `snapshot()` |
| 恢复时要跳过 | `RUNTIME_SWITCH_KEYS`（字符串数组） | `restoreSkipping()` |

**三套判据，两个在表里，一个在外面。** 而且第三种跟前两种语义重叠
（`inPreset=false` 的字段，本来就该在恢复时跳过）。

**重构方案：把 `RUNTIME_SWITCH_KEYS` 干掉，改成第三个布尔标志。**

```java
public final boolean syncsToConfig;   // 进 CONFIG 吗
public final boolean inPreset;        // 进预设快照吗
public final boolean runtimeSwitch;   // 恢复预设时跳过吗  ← 新增
```

然后：

```java
// 原来是字符串数组比对
restoreSkipping(s, snap, RUNTIME_SWITCH_KEYS);

// 改成
restore(s, snap);   // 内部按 f.runtimeSwitch 跳过
```

**收益**：
- 一个字段的归属，在**一处**声明（表里的三个布尔值）
- 加字段时，IDE 会逼你填这三个值（构造器参数）
- `RUNTIME_SWITCH_KEYS` 那种「补了 4 项忘了 5 项」的事**不可能再发生**

历史事故印证：`WORK-DISCIPLINE.md` 陷阱 8 记着
> `RUNTIME_SWITCH_KEYS` 原来只有 4 项，漏了 5 个：targetFps / cursorWidthPx /
> cursorHeightRatio / diagLogging / multiCursorNoGlow

**那次是因为它是一份独立清单，加字段时没人提醒你去改它。**

### 痛点 3：加功能时面板混乱

**这个重构解决不了。**

面板混乱的根源不在数据结构，在 **`NeovideCursorConfigurable.java` 的 3,110 行**：

```
9 个 build*Group() 方法：
  buildMasterGroup      buildInlineGroup    buildCrossGroup
  buildColorGroup       buildGlowGroup      buildCursorGroup
  buildFadeGroup        buildExperimentalGroup  buildDiagGroup

SPECS 表（滑块定义）14 项
手写复选框/下拉 17 处
```

**为什么乱**：
- 分组是**手工划分**的（「光标」组里放什么，靠人判断）
- 同一个参数可能被多个组引用（`cursorWidthPx` 属光标，但也在「光标」组）
- 加一个控件要改 3 处：`SPECS` 表 + 某个 `build*Group` + 布局

**重构能做的（有限）**：把 `SPECS` 表扩展成「包含分组信息」，
让 `build*Group` 从表生成。

**但这是面板层重构，跟「参数保存」是两件事。** 范围比 ③④ 大得多。

**我的建议：这一条单独做一个项目，不跟参数重构混。**

---

## 四、重构到底能解决什么问题（一张表）

| 问题 | 重构能解决吗 | 收益 | 成本 |
|---|---|---|---|
| **参数保存漏层**（copyFrom / loadState） | ✅ 能 | 高（加字段从 6 处→4 处） | 中（~90 行） |
| **职责划分混乱**（RUNTIME_SWITCH_KEYS） | ✅ 能 | **最高**（消灭一个已知事故类） | 低（~50 行） |
| 消费者漏接线（targetFps 那类） | ❌ 不能 | — | — |
| **面板混乱** | ⚠️ 部分能 | 中 | **高**（3110 行） |
| 连打拖尾跳变（当前 bug） | ❌ 不能 | — | — |
| 斜拉伸（当前 bug） | ❌ 不能 | — | — |

**结论：重构不修 bug，但能消灭「加功能时必然踩坑」的机制。**
**而且你三个痛点里，收益最高的那个（职责划分）成本最低。**

---

## 五、重建议的优先级排序（如果将来做）

### 第一优先：`RUNTIME_SWITCH_KEYS` → `runtimeSwitch` 布尔标志

**为什么最优先**：
- 成本最低（~50 行）
- 收益最高（消灭「补了清单忘了项」这一整类事故）
- 语义最清晰（「是否进预设」和「恢复时是否跳过」本就该是字段属性）
- 有现成的探针 `VSwitchKeysGap` 可以改方向验证

**做法**：
1. `SettingsField` 加 `boolean runtimeSwitch`
2. 9 个字段的构造调用加上这个参数
3. `restoreSkipping` 改成内部判断
4. `RUNTIME_SWITCH_KEYS` 数组可以保留（向后兼容）或删掉
5. `VSwitchKeysGap` 探针改成「断言表里的 runtimeSwitch 与预期一致」

### 第二优先：`copyFrom` / `loadState` 表驱动

**为什么第二**：收益高但要动 30 个字段的拷贝，风险比第一个大。

### 第三优先（独立项目）：面板结构

**为什么单独做**：3,110 行，跟参数层完全不同的复杂度。

---

## 六、但现在还是不该做（保持之前的判断）

理由不变，而且更强了：

**理由 1：当前没有 bug 在发作**
实测 `copyFrom` 是 30/30 全覆盖，`RUNTIME_SWITCH_KEYS` 也已经补到 9 项。
**债还在，但没违约。**

**理由 2：另一个会话正在修观感 bug**
两件事混做会互相污染，出问题时无法归因（纪律三 / 四-G-2）。

**理由 3：先验证 0.9.92 装完的观感**
如果连打修复没达到预期，可能还要再改物理层 —— 那时重构的东西可能白做。

---

## 七、建议的时间线

```
现在          等另一个会话修完连打跳变
   ↓
① 一起验证    D0 + 全量探针 + 手动连打测试
   ↓
② 装 0.9.92   斜度 + 连打两个修复一起
   ↓
③ 观察 3~5 天  确认观感稳定，没有回归
   ↓
④ 重构第一项  RUNTIME_SWITCH_KEYS → 布尔标志（~50 行，风险低）
   ↓
⑤ 重构第二项  copyFrom / loadState 表驱动（~90 行）
   ↓
⑥ 面板重构    单独评估，可能需要独立项目
```

---

## 八、一句话回答你的问题

**「重构会解决什么问题，能解决 bug 吗？」**

**不能解决 bug（连打跳变、斜拉伸都跟它无关）。
但能消灭「每次加功能都可能引入 bug」的那套机制。**

**你的三个痛点里**：
- 参数保存 → 重构能归零机械遗漏
- **职责划分 → 重构收益最高，且成本最低（优先做这个）**
- 面板混乱 → 重构只能解决一部分，应该单独做

**现在别动，等 0.9.92 验证稳定之后，从「职责划分」那条开始 —— 它最便宜，最安全。**
