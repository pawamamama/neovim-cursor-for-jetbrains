# 校验下沉 —— U-1 / U-2 / U-3（跨层重构）计划与证据

> 起点：`main` @ `923170f`（已推 fork，同步 0 0）。
> 依据：`docs/BATCH3-FIX-REPORT2-2026-10-31.md` §七 + `plans/batch3-fix2.md` §七（已知未修 U-1/U-2/U-3）
> + `docs/CODE-AUDIT-2026-10-30.md` §HIGH 的 H-1 / H-2。
> 性质：**跨层重构**，不是补丁。目标是把「预设快照的校验」从三个各写一遍的调用方，
> 下沉到唯一一层 —— `settings/SettingsField`（本项目的「字段表」层）。
> 改完之后，面板入口、Tools 菜单入口、以及未来新增的任何入口**自动同源**。

---

## 零、Research：第 1 批的校验入口长什么样（实测）

### 0.1 现状：同一个校验，三个入口各写一遍

| 入口 | 位置 | 校验方式 | 问题 |
|---|---|---|---|
| 面板「选自定义动画预设」 | `NeovideCursorConfigurable.onAnimPresetPicked()` :2110 | `restoreSkipping` + 自己的 `clampPresetNumerics` | 量程取自面板的 `SPECS`（**面板私有**） |
| 面板「恢复默认值」 | `NeovideCursorConfigurable.restoreAnimPreset()` :2649 | `restoreSkipping` + 同一个 `clampPresetNumerics` | 同上 |
| **Tools 菜单** | `ui/CursorPresetApplier.applyCustomAnimPreset()` :116 | 只有 `restoreSkipping`，**不 clamp** | `glowLayers=99` 直接进 State 并落盘 |
| **Tools 菜单（颜色）** | `ui/CursorPresetApplier.applyCustomColorPreset()` :219 | 只判 `hex == null`，**不校验 HEX** | `tailColor=not-a-color` → 光标变黑 |
| 启动读盘 | `NeovideCursorSettings.loadState()` :124-237 | `clamp(from.x, lo, hi)` **内联字面量** | 区间常量与字段表分家 |

**根因一句话**：校验写在了**调用方**，而不是**被调用方**。`SettingsField.restoreSkipping`
是唯一的恢复入口，它自己不校验，于是每个调用方都得记得校验 —— 漏一个就是一个洞。
第 1 批修了面板那一个入口（P-M-5 / P-M-6 / P-M-3），Tools 那条原样保留，这就是 U-1 / U-2。

### 0.2 第 1 批建了什么、本轮怎么**增强**它（不是推翻）

| 第 1 批的东西 | 内容 | 本轮的处置 |
|---|---|---|
| **H-2**（`decode` 失败可观测） | `decode` 拒绝时 `reject()` 记一行 warn；`applied == 0` 时调用方不写名字 | **增强**：越界值不再静默进 State，而是**夹回 + 记日志 + 计入 applied**；返回值升格为**报告**（夹回/拒绝分别计数），调用方不用再自己算 |
| **H-1**（区间收进字段表） | 审计给出的方向：「`INT`/`FLOAT` 各带一个 clamp 算子，让 `decode` 与 `loadState` 共用同一份常量」 | **落地**：区间写进字段表每一行（`range(lo, hi)`），`decode` 与 `loadState` 都从它取 |
| **P-M-5**（面板快照 clamp） | 面板侧 `clampPresetNumerics()` 遍历 `SPECS` | **下沉**：校验搬到 `decode`；面板的 `clampPresetNumerics` / `rawAtTick` 删除，改为消费下沉层返回的报告 |
| **P-M-6 / P-M-3**（颜色快照校验） | 面板侧 `applyColorSnapshot()` 调 `isValidHexColor` | **下沉**：同一个方法搬进 `SettingsField`，Tools 入口直接调它 |

**没有推翻任何一条。** 唯一的口径收窄是「越界值夹回」而不是「拒绝」——
这一点由第 1 批的 N-1 探针自己钉死（它拿 `loadState` 当 oracle，断言面板夹回值 == loadState 夹回值），
本轮把这条 oracle 关系原样搬到下沉层，所以 N-1a..c 一条都不用改。

### 0.3 一处需要写明的语义（任务书 §三-d 的两种读法）

任务书 §三-d 写「非法值的行为：**保持当前值** + 记一行日志 + 计入 applied 统计」。
实测这条要拆成两类，否则自相矛盾（保持当前值就不可能计入 applied）：

| 类别 | 例子 | 行为 | 依据 |
|---|---|---|---|
| **类型非法** | `tailOpacity=abc`、`NaN`、非法枚举名、非 HEX 串 | **保持当前值** + warn + **不计入** applied | `decode` 的既有契约（绝不抛异常、宁干净不崩），第 1 批 H-2 已定的语义 |
| **量程越界** | `glowLayers=99`、`animationLength=0.9` | **夹回合法区间** + warn + **计入** applied | H-1 建议方向（clamp 算子）；`loadState` 对同一字段就是这么做的；第 1 批 N-1 已把「== loadState」写成断言 |

如果按「越界也保持当前值」，N-1a..c（第 1 批断言：夹回值 == loadState 夹回值）会当场变红，
而 `loadState` 也必须跟着改成「拒绝」，那等于推翻第 1 批的 N-1 探针。
按纪律，**不动既有断言**，取上表这一读法，并在本文 §六 登记为已知口径。

---

## 一、设计：唯一一层是谁，区间表落在哪

### 1.1 唯一一层 = `settings/SettingsField`

```
ui/CursorPresetApplier（Tools 菜单）┐
ui/settings/NeovideCursorConfigurable（面板）├──▶ SettingsField.decode（唯一校验点）
未来新增的第 N 个入口              ┘        └──▶ range 只在 SettingsField.build() 出现
settings/NeovideCursorSettings.loadState ──▶ 遍历 SettingsField.FIELDS 取区间（不是第二份表）
```

选它的理由（三条，任一成立即足）：

1. **它就是被调用方**：`restore` / `restoreSkipping` 是快照恢复的唯一入口，
   把校验写在入口里面，新增调用方**不可能**漏 —— 这正是「下沉」与「补丁」的区别。
2. **它已经在做同一件事的半边**：`decode` 的 HEX 分支从 0.9.74 起就调 `isValidHexColor`
   （类型/格式校验），本轮只是把「量程」这半边补进同一处，不是新开一条路。
3. **它是唯一能同时服务 `loadState` 与 `decode` 的地方**：两者都在 `settings/` 包内，
   不需要把「区间」暴露成 UI 概念，也不会让 `settings/` 反向依赖 `ui/`。

### 1.2 区间表长什么样

字段表每一行自带区间（与 `runtimeSwitch` 同一个思路：**标志写在字段自己那一行**）：

```java
out.add(floats("glowOpacity", s -> s.glowOpacity, (s, v) -> s.glowOpacity = v,
        c -> c.glowOpacity, (c, v) -> c.glowOpacity = v).range(0, 1));
out.add(ints("glowLayers", s -> s.glowLayers, (s, v) -> s.glowLayers = v,
        c -> c.glowLayers, (c, v) -> c.glowLayers = v).range(1, 12));
```

`range()` 返回**副本**（字段对象仍然不可变），不参与区间的字段保持 `NaN`（= 不校验）。

### 1.3 面板的 `SPECS` 怎么办（AC-002 的关键）

`SPECS` 是 `ui/settings/` 私有的「刻度表」，它携带 `min/max/step` **刻度值**与
`readTick/writeTick` 换算。本轮**不再保留任何刻度端点字面量**：端点由字段表的区间**导出**：

```
field.range(0.05, 0.35)  ──writeRaw(端点)──▶ readTick ──▶ 刻度端点 {5, 35}
```

`shadowBlurFactor` 的刻度是**反的**（锐度 0 = 外扩 0.60，锐度 100 = 外扩 0.05），
导出后取 `min/max` 排序，得 `{0, 100}`，与改造前逐项相同 —— 所以 `VUi.SPEC`（探针侧的
独立 oracle）一条都不用改，这同时是「观感零变化」的可测证据。

> **为什么不直接在 `SPECS` 里留数字**：AC-002 要求「区间常量只出现在一处」。
> 13 个字段 × 2 个端点 = 26 个数字留在 `ui/` 里，就是第二份量程表 ——
> 第 0.1 节那个「补了 4 项忘了 5 项」的病根（陷阱 8）正是这么来的。

### 1.4 `loadState` 怎么改

把 13 处内联 `clamp(from.x, lo, hi)` 换成「原样拷贝 + 表驱动统一夹取」：

```java
for (SettingsField f : SettingsField.FIELDS) {
    f.clampStateToRange(target);      // 区间只来自字段表
}
```

保留在 `loadState` 里的**不是区间**，而是三类**语义**，它们不属于「合法区间」：
`trailMode` 的 null 回落、`animPresetBase` / `colorPresetBase` 的下标校验（依赖数组长度）、
`slopeTicks == 82` 的一次性迁移、`targetFps` 的白名单（setter 自带）。

### 1.5 下沉层返回「报告」，而不是只返回一个 int

```java
public static final class RestoreReport {
    public int applied;                 // 真正写进 State 的字段数（含夹回的）
    public final List<String> clamped;  // 被夹回的字段 key（有序）
    public final List<String> rejected; // 类型非法被拒的字段 key（有序）
}
public static int restoreSkipping(State s, String snapshot)                     // 旧签名不变
public static RestoreReport restoreSkippingReported(State s, String snapshot)   // 新增
```

**为什么要报告**：面板的「夹回后给用户一行提示 + 自愈回写快照」（第 1 批 N-1e / N-1f）
必须知道「夹了哪些」。校验下沉之后面板不再自己算，就必须从下沉层拿这个信息 ——
否则要么重复实现一遍（又变成两份），要么把提示删掉（静默改值，正是原缺陷）。
Tools 入口同样能用它把日志写准（夹回几项 / 拒绝几项），而不是只说「已应用」。

---

## 二、三条改法（根因 → 改法 → 为什么）

### U-1 / U-2：校验下沉到 `restoreSkipping`

**根因**：见 §0.1。`decode` 的 FLOAT/INT 分支只拒 NaN/Inf 与解析失败，**不 clamp**；
真正的 clamp 只发生在下次启动的 `loadState` 里。于是
`glowLayers=99` 会：进 State → 进 CONFIG → **落盘 99** → 重启被 `loadState` 夹回 12
→ 用户看到「值自己变了」。Tools 入口连这层都没有：面板入口至少夹回，Tools 入口原样落盘。

**改法**：

1. `SettingsField` 加 `min/max` + `range(lo, hi)`，13 个数值字段各自带区间（§1.2）。
2. `decode` 的 FLOAT / INT 分支在类型校验之后**按本字段区间夹回**，夹回时
   `LOG.warn` 一行（带字段 key、原值、夹后值），并计入 `applied`。
3. `restore` / `restoreSkipping` 接受一个可选的 `RestoreReport` 收集器；
   `restoreSkippingReported` 是新的公开入口。
4. 面板删掉 `clampPresetNumerics` / `rawAtTick`，改消费报告；自愈回写与用户提示的**文案与时机不变**。
5. Tools 入口（`CursorPresetApplier.applyCustomAnimPreset`）改消费报告：日志写清
   `applied/total`、`clamped`、`rejected`；`applied == 0` 仍然返回 false 不写名字（第 1 批 H-2 语义）。
6. `loadState` 的 13 处内联 clamp 换成表驱动（§1.4）。

**为什么不是「补一条 clamp 到 Tools 入口」**：那正是第 1 批做过的事（补在面板），
下一个入口还会漏。下沉之后，`restoreSkipping` 的**所有**调用方（当前 5 个，含 3 个探针）
自动获得同一套校验。

### U-3：`sanitizeColor` 支持 9 位

**根因**：`NeovideCursorSettings.sanitizeColor` 只收 7 位 `#RRGGBB`；
而 `SettingsField.isValidHexColor` 收 7 位与 9 位。两条对「颜色合法性」的定义分家，
于是 9 位 `#RRGGBBAA` 在面板里能用、**应用后能生效，重启被 `loadState` 打回默认色**。

**改法**：`sanitizeColor` 不再自带格式规则，直接
`return SettingsField.isValidHexColor(value) ? 规范化 : fallback;`
—— 全仓「颜色合法性」只剩 `isValidHexColor` 一个定义（AC-003）。

**语义确认**：`core/NeovideColors.parse()`（:103-136）对 8 位 hex 的解析顺序是
`RR GG BB AA`，alpha 与 `opacity`（`tailOpacity` / `glowOpacity`）相乘 ——
与用户 2026-10-30 拍板的「9 位按 `#RRGGBBAA` 解释，末两位 alpha 与 `tailOpacity` 相乘」一致。
所以本轮**只放开 `sanitizeColor` 的入口**，不改任何绘制语义（`core/` 零 diff）。

---

## 三、探针（每条都要「红 → 绿 → 变异红 → 恢复绿」）

新增 1 个探针文件 `tools/settings-probe/VValidationSink.java`（108 → 109），
登记进 `build-v-probes.ps1`。

| 编号 | 覆盖 | 断言要点 |
|---|---|---|
| T-1 | U-1 Tools 入口 | 越界快照经 `CursorPresetApplier.applyCustomAnimPreset` → 值夹回（oracle = `loadState` 对同一脏值的处理）、有 warn、State 里**没有任何数值字段越界** |
| T-2 | U-1 两条入口同源 | 同一份越界快照 + 同一份起始 State，分别从 Tools 与面板喂 → **全部 `PRESET_FIELDS` 的编码逐项相同** |
| T-3 | U-3 | 9 位 `#FFC0CB80` 与 `#ffc0cb80` → `loadState` 往返后仍在（含「大写规范化」）；7 位仍工作；坏串仍回落默认 |
| T-4 | U-2 颜色 | 坏 HEX 从 Tools 入口 → 返回 false、State 两个颜色都不动、有 warn；同一份坏快照从面板入口 → 同样不写半套 |
| S-1 | 结构：区间只有一份 | 对每个带区间的字段，`loadState` 对「越界值」的结果 == 字段表端点（**行为断言**，不是 grep 字面量） |
| S-2 | 结构：颜色合法性只有一份 | `sanitizeColor` 的接受集合 == `isValidHexColor` 的接受集合（9 组输入逐项比对） |
| S-3 | 结构：报告口径 | 一份「一半合法一半越界」的快照 → `applied`/`clamped`/`rejected` 三个数各自正确 |

> S-1 是 AC-002 的**可执行形式**：区间表有两份时，两边会对不上；只有 grep 抓不住
> 「数字相同但语义不同」的情况（例如面板刻度 vs 原始值）。

---

## 四、变异测试计划（改坏生产 → 必须变红 → 恢复）

| # | 变异 | 期望变红 |
|---|---|---|
| M-1 | 删掉 `glowLayers` 的 `.range(1, 12)` | T-1 / T-2 / S-1 / S-3 |
| M-2 | `decode` 的 FLOAT/INT 分支不调 `clampRange`（原样写入） | T-1 / T-2 / S-3 |
| M-3 | `loadState` 的表驱动夹取整段删掉 | S-1 |
| M-4 | `sanitizeColor` 改回「只收 7 位」 | T-3 / S-2 |
| M-5 | `restoreColorSnapshot` 跳过 `isValidHexColor` | T-4 |
| M-6 | 夹回日志改成静默（不 `LOG.warn`） | T-1 |
| M-7 | 面板 `SPECS` 端点手写回字面量（= 第二份量程） | S-1（面板侧端点与字段表不一致时红） |

> 每次变异后**必须删 `build\v2-probe\classes` 全量重编译**（增量编译会复用旧字节码）。

---

## 五、AC 对照

| AC | 怎么验 |
|---|---|
| AC-001 三条各自四段输出 | 本文 §七 记录表，逐条贴红/绿/变异红/恢复绿 |
| AC-002 量程表只有一份 | `grep -rn` 生产代码：**持久化量程**的端点只出现在 `SettingsField.build()`（`.range(...)`，14 条）；S-1a/b/c/d 行为断言。**派生函数里的兜底 clamp 不算量程**（`deriveTrailFactors` 的 `1..8` 等三处，见 §8.2 F-1）—— 它们是纯函数对入参的定义域保护，策略与量程不同 |
| AC-003 颜色合法性只有一个定义 | `sanitizeColor` 只剩 `isValidHexColor` 调用；S-2 |
| AC-004 两条入口同源 | T-2（同一份坏快照，两个结果逐项比对） |
| AC-005 9 位颜色重启仍在 | T-3（`loadState` 往返，不是只看 apply） |
| AC-006 既有断言零放宽 | 全量门禁 PASS 数不下降；`VBatch3Medium` 的 N-1a..f / N-3 / N-4 一条不动 |
| AC-007 观感零变化 | 合法值逐位不变（`VCustom` C-3、`VDefaultFreeze`、`VUi` 的量程 oracle 都不改） |
| AC-008 D0 零 diff | `git diff --exit-code -- core/NeovideConfig.java` |
| AC-009 改动只在白名单内 | `git status --porcelain` 逐文件核对 |
| AC-010 探针数三份文档同步 | 108 → 109，`VDocCheck` D-2 绿 |

---

## 六、已知行为变化（必须写清，不许说「零变化」）

| 变化 | 影响面 | 为什么可以接受 |
|---|---|---|
| `slopeTicks` 越界值：原来一律回落 0，现在夹到最近的端点（-1 → 0，999 → 100） | 只有手改 XML 塞坏值才会遇到；正常使用进不来 | 「所有数值字段同一套规则」正是本轮目标；面板侧的 `clampPresetNumerics` **本来就**会把 999 夹成 100，下沉后两条入口才真正一致 |
| 9 位颜色不再在重启时被打回默认 | 手选过 9 位颜色的用户，重启后颜色保留 | 这是 U-3 要修的症状本身 |
| 面板的「夹回提示」来源从自身 `SPECS` 改为下沉层报告 | 文案与时机不变 | 提示触发的**条件**仍是「有字段被夹回」，只是判定者换成了唯一一层 |

---

## 七、执行记录（实测，2026-10-31）

### 7.1 探针规模与基线

```
新探针      tools/settings-probe/VValidationSink.java   34 条断言
探针文件数   108 → 109（已同步 CLAUDE.md / START-HERE.md / docs/NEXT-STEPS.md，VDocCheck D-2 绿）
收工门禁     见 §7.5
```

### 7.2 四条「改前红」的证据来源（如实说明）

`VValidationSink` 是**先写探针、后改生产**的（TDD）。上一会话完成红→绿时的那份输出没有留档，
本轮没有凭记忆重写它 —— 而是用**等价变异**重新造出「改前行为」，逐条复现红：

| 条 | 等价于「改前」的变异 | 看到的现象 |
|---|---|---|
| T-1 / S-3（U-1） | M-2：`decode` 的 FLOAT/INT 不夹取 | `glowLayers=99 / animationLength=0.9 / tailOpacity=5 / shadowBlurFactor=99 / stretchRatioK=0.1 / slopeTicks=999` 原样进 State；`clamped=[]` |
| T-3 / S-2（U-3） | M-4：`sanitizeColor` 只收 7 位 | 9 位颜色经 `loadState` 读回 `#FFC0CB`（默认粉） |
| T-4（U-2） | M-5：`restoreColorSnapshot` 跳过 `isValidHexColor` | Tools 入口返回 true 且把 `not-a-color` 写进 State |

这三条变异与「改前代码」逐句等价 —— 它们既是红证据，也是变异证据。

### 7.3 逐条四段（红 / 绿 / 变异红 / 恢复绿）

| 条 | ① 红（改前） | ② 绿（改后） | ③ 变异红（实测） | ④ 恢复绿 |
|---|---|---|---|---|
| U-1 校验下沉 | M-2 见下 | T-1a..j / T-2 / T-2b / S-3a..f 全 PASS | **M-1 / M-2 / M-3 / M-6** | PASS |
| U-2 颜色下沉 | M-5 见下 | T-4a..d 全 PASS | **M-5** | PASS |
| U-3 9 位颜色 | M-4 见下 | T-3a..d / S-2 全 PASS | **M-4** | PASS |
| 结构（区间唯一） | —（本项是**守卫**：旧代码把区间分散在三处，没有任何一条断言能单独变红） | S-1a..c 全 PASS | **M-3 / M-7** | PASS |

### 7.4 变异清单（7 条，实测逐条结果）

每条：改坏生产 → **删 `build\v2-probe\classes` 全量重编译**（本轮用全新空目录达到同一目的）
→ 跑 `VValidationSink` → 记录 → 撤销 → 复跑。

| # | 变异 | 实测结果 | 判决 |
|---|---|---|---|
| **M-1** | 删掉 `glowLayers` 的 `.range(1, 12)` | `T-1h`、`T-1j` 红（`glowLayers=99 不∈[1.0,12.0]`）；**并且** `T-2` 一进入就抛 `IllegalStateException: SPECS 的字段 glowLayers 在字段表里没有区间` —— 面板 `spec()` 的类初始化守卫当场炸 | ✅ 抓住 |
| **M-2** | `decode` 的 FLOAT/INT 分支不调 `clampFloat/clampInt` | **11 条红**：`T-1b/c/d/e/f/g/h/j` + `S-3b` + `S-3e` + `S-3f`（`state=99 loadState=12` 这类对照全反） | ✅ 抓住 |
| **M-3** | 删掉 `loadState` 末尾的 `clampStateToRange` 循环 | **7 条红**：`T-1b..g`（`state=12 loadState=99` —— **oracle 关系反转**）+ `S-1a`（14 个字段端点全部回到 ±1e9 / ±2^31） | ✅ 抓住 |
| **M-4** | `sanitizeColor` 改回「只收 7 位」 | **3 条红**：`T-3a`、`T-3b`、`S-2`（`#80FF0000` 与 `#ff000080` 被判非法） | ✅ 抓住 |
| **M-5** | `restoreColorSnapshot` 把 `if (!isValidHexColor…)` 改成 `if (false)` | **4 条红**：`T-4a`（返回 true）、`T-4b`（`tail=not-a-color` 已写入）、`T-4c`（无 warn）、`T-4d`（面板同样被写坏） | ✅ 抓住，且**同一个变异同时打穿两条入口** —— 这就是「下沉」的直接证据 |
| **M-6** | 把 `noteClamped` 里的 `LOG.warn` 改成静默 | **第一次跑：0 条红**（见 §7.4.1，断言太弱）→ 加强 `T-1h` 后重跑：**1 条红** | ✅ 抓住（加强后） |
| **M-7** | 面板 `spec()` 把 `animationLength` 的端点手写回 `{0, 50}`（= 第二份量程） | **1 条红**：`S-1c`（`最小刻度→0.0 期望 0.05，最大刻度→0.5 期望 0.35`） | ✅ 抓住 |
| **M-8** | 审查后新增：把 `slopeTicks` 的 `.range(0, 100)` 改成 `.range(0, 100.5)`（INT 非整数端点） | 类初始化直接抛 `IllegalStateException: INT 字段的区间端点必须是整数：slopeTicks [0.0,100.5]`，探针一条都跑不起来 | ✅ 抓住 |
| **M-9** | 审查后新增：把 `glowLayers` 的 `.range(1, 12)` 改成 `.range(12, 12)`（零宽区间） | 类初始化直接抛 `IllegalStateException: 区间非法：glowLayers [12.0,12.0]` | ✅ 抓住 |

#### 7.4.1 M-6 第一次没红 —— 这是真发现，不是变异失败

`T-1h` 原来写的是 `anyWarnSince(m, "glowLayers")`，只要**任意一行**日志里出现字段名就算过。
把下沉层那条「值越界（99），已夹回…内的 12」改成静默后，Tools 入口自己还有一条
「有 N 个参数越界，已按合法区间夹回：glowLayers」—— 同样含字段名，断言仍然绿。

也就是说：那条断言只证明了「某处提到过这个字段」，**没有**证明用户能看出「从多少夹到了多少」，
而后者才是 `noteClamped` 存在的理由（用户报「我点的预设重启后效果变了」时唯一可查的证据）。

已把 `T-1h` 改成「同一行 warn 里必须同时出现 字段名 + 原值 `99` + 夹后值 `12` + "越界"」，
并加了 `anyWarnSince(int, String...)` 的多片段重载。加强后 M-6 如期变红，撤销后复绿。
**断言是从「宽」改到「窄」，没有任何一条既有断言被放宽。**

### 7.5 收工门禁与 D0

```
$ pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 \
       -Repo D:/deepseekwork/neovim-cursor-pr        # exit 0
107 条目 / 1195 PASS / 2 FAIL（仅 VRegr2，informational）/ ALL PASS
VValidationSink 34 / 0     VDocCheck 4 / 0
```

**账目闭合**（为什么这组数字可信）：

```
开工基线        105 条目 / 1155 PASS / 2 FAIL（仅 VRegr2）
+ VValidationSink（本轮新探针）          1 条目 / +34 PASS
+ VCatchRollback（另一个会话的并发工作） 1 条目 /  +6 PASS
=               107 条目 / 1195 PASS / 2 FAIL   ← 与实测逐项相同
```

**没有任何既有断言的通过数下降**（AC-006）。唯一的非零失败仍是 `VRegr2` 的 2 条
（R-5 / R-6，informational，本轮没动）。

D0：

```
$ git diff --exit-code -- .../core/NeovideConfig.java   → exit 0（零 diff）
$ git status --porcelain docs/BUG-LOG.md                → 空（未触碰）
$ git diff --stat -- .../core/                          → 空（core/ 整目录零改动）
```

> 门禁采集方式说明：脚本的格式表在长时间运行下会被管道截断，本节数字是
> **对同一份运行**留在 `build/v2-probe/<探针名>.txt` 的逐探针输出求和得到的，
> 并按 `$mains` 里真实存在的 `V*.java` 过滤（排除了历史遗留的同名 `*.txt`）。
> 求和规则与脚本一致（`^\s+PASS ` / `^\s+FAIL `）。

### 7.6 白名单核对（`git status --porcelain` 实测）

**本轮改动的文件（全部在白名单内）**：

```
M  CLAUDE.md / START-HERE.md / docs/NEXT-STEPS.md      探针数与规模数字同步
M  settings/SettingsField.java                         区间表 + decode 夹取 + 报告 + 颜色下沉点
M  settings/NeovideCursorSettings.java                 loadState 表驱动 + sanitizeColor 同源
M  ui/CursorPresetApplier.java                         消费报告；颜色快照走下沉点
M  ui/settings/NeovideCursorConfigurable.java          SPECS 端点由区间导出；消费报告
M  tools/settings-probe/VFieldDomain.java              私有夹具 perturb 改用 writeRaw（断言未改）
M  tools/settings-probe/build-v-probes.ps1             $mains 登记 VValidationSink
?? tools/settings-probe/VValidationSink.java           新探针（31 条断言）
?? plans/validation-sink.md                            本文件
?? docs/HANDOFF-NEXT-2026-10-31.md                     §3.1.1 标记 U-1/U-2/U-3 已完成
```

**白名单外的字节变化：本轮 0**。

```
core/NeovideConfig.java                      零 diff（D0 门禁，`git diff --exit-code` 退出码 0）
docs/BUG-LOG.md                              未触碰
core/ 整个目录                               零改动
ui/CaretAnimator.java / ui/CaretAnimatorProbe.java / tools/settings-probe/VCatchRollback.java
    ← 同一工作区里**另一个会话**的并发改动（U-M-7），与本轮无关；本轮一个字节都没碰
build/night-run-report.md / plans/batch3-review-prompt.md
    ← 开工前就存在的未跟踪文件，本轮未动
```

> **并发须知**：本轮施工期间另一个会话正在改 `ui/CaretAnimator.java`，中途源码树一度编不过。
> 变异测试的每一次编译都使用**独立的空产物目录**，因此不存在「复用别人的旧字节码」；
> 有两次编译失败（`CaretAnimator` 正在编辑中）已被识别并重试，没有计入任何结论。

---

## 八、独立审查记录（两个代理，2026-10-31）

两个代理都只审本轮 diff，并被明确告知 `ui/CaretAnimator*` 属于另一会话、不要审。

| 代理 | 结论 | 真实问题 | 本轮处置 |
|---|---|---|---|
| **code-reviewer** | **APPROVE**：0 CRITICAL / 0 HIGH / 1 MEDIUM / 9 LOW | M-1：9 位颜色的**位序**在注释与三条用户可见文案里写成 `#AARRGGBB`，而 `NeovideColors.parse` 按 `RR GG BB AA` 读 | **已修**（5 处改成 `#RRGGBBAA`，并在 `isValidHexColor` 的 javadoc 里写死位序与理由）。这是两条最贵的一条：U-3 把 9 位从「重启会自愈」放开成「持久可用」，错误提示会让用户手写出 alpha=0 的全透明光标 |
| | | L-1：「预设名字不存在」与「快照损坏」被合并成同一句「内容已损坏」 | **已修**：先判 `CustomPresets.get(...) == null` 单独报「不存在」，并新增探针 T-4e 守门 |
| | | L-2：同一字段在快照里出现两次会重复计数（提示里的个数会撒谎） | **已修**：`clamped`/`rejected` 记 key 时去重；新增 S-3g 守门 |
| | | L-4：`restoreReported` 是死代码，且口径与 `restoreSkippingReported` 不同 | **已删**（四-J ④：死代码要么接回去要么删干净） |
| | | L-8：S-3d 的断言文案与它的计算不符（会诱导后人「修正」成错的） | **已改文案** |
| | | L-6：AC-002 的「grep 生产代码」按字面做不到 —— 派生函数内还有三处同名 clamp | **已改口径**见 §五 |
| | | L-3：int 溢出被当成「解析抛异常」并带堆栈 | **已修**：`NumberFormatException` 单独接住、不带堆栈（见下 java-reviewer M-5） |
| | | L-5：`shadowBlurFactor` 的端点存在双来源（`SettingsUiUtil` 的 SOFT/SHARP_BLUR） | **登记**为后续（当前值一致，属预防性） |
| | | L-9：T-4 分不清 `MISSING_FIELDS` 与 `BAD_FORMAT` | **部分已修**：新增 T-4e 把「名字不存在」分支钉住 |
| | | L-7：§七 执行记录为空 | 审查看到的是旧副本，**本轮已补** |
| **java-reviewer** | **不阻塞**：0 CRITICAL / 0 HIGH / 6 MEDIUM / 9 LOW；自行复跑探针 31/0 | M-1：同一字段两套取整规则（`writeRaw` 用 `Math.round`，夹取用 `ceil/floor`）—— 非整数 INT 端点时会差 1 | **已修**：`range()` 拒绝非整数 INT 端点；`intLo()/intHi()` 收成一处，`clampInt` 与 `clampStateToRange` 共用 |
| | | M-2：`range()` 接受零宽区间与无穷端点，失败是静默的 | **已修**：`!(lo < hi)` 与无穷一律类加载期抛 |
| | | M-4：与 code-reviewer M-1 同一问题（两个代理独立命中） | **已修** |
| | | M-5：`catch (RuntimeException)` 的 javadoc 说「只有代码 bug 会走到」，实际 `parseInt("abc")` 就在 try 里 | **已修**：单独 `catch (NumberFormatException)` → 不带堆栈的 `reject("不是数字")`；javadoc 改成事实 |
| | | M-6：探针 `RANGES` 是独立 oracle，但没有覆盖度自检 —— 新字段漏登记会裸奔 | **已修**：新增 S-1d（`RANGES` 的 key 集合必须等于字段表里 `hasRange()` 的集合） |
| | | M-3：`deriveTrailFactors` / `applySlopeOverlay` / `syncConfigToState` 里还有三处同数值 clamp | **登记**为后续：它们的策略与持久化量程**不同**（回落默认 4.5 / 夹端点），改它等于改运行时行为，不属于「下沉」 |
| | | L-1 / L-2 / L-3 / L-4 / L-5 / L-6 / L-7 / L-8 / L-9 | L-1 已删、L-9 已修（夹具改成非默认值 0.47）；其余**登记** |

### 8.1 新增代码的自我验证（审查后补的两条守卫）

`range()` 的两条新断言属于**新增生产代码**，按纪律必须证明它们真会炸（M-8 / M-9）：

```
M-8  slopeTicks 的 .range(0, 100) 改成 .range(0, 100.5)
     → ExceptionInInitializerError: INT 字段的区间端点必须是整数：slopeTicks [0.0,100.5]
M-9  glowLayers 的 .range(1, 12) 改成 .range(12, 12)
     → ExceptionInInitializerError: 区间非法：glowLayers [12.0,12.0]
撤销后：VValidationSink 34/0 全绿。
```

### 8.2 登记为后续（本轮没做，写明原因）

| # | 项 | 为什么不在本轮 |
|---|---|---|
| F-1 | `deriveTrailFactors` / `applySlopeOverlay` / `syncConfigToState` 里的三处 clamp 仍自带 `1..8` / `0..100` / `0..1` | 它们是**纯函数的定义域保护**，策略是「回落默认 / 夹到端点」，与持久化量程不是同一条规则；改它等于改运行时行为，应单独一轮并带观感回归 |
| F-2 | `shadowBlurFactor` 的端点双来源（`SettingsUiUtil.SOFT_BLUR/SHARP_BLUR` × 字段表 `.range(0.05, 0.60)`） | 当前两端一致（S-1c 实测）；要消除必须让 UI 换算层反向依赖字段表，属下一轮结构改动 |
| F-3 | Tools 入口夹回后**不回写快照**（面板会自愈） | 行为正确（幂等），只是重复日志噪音；改它要动用户数据（快照），需单独决定 |
| F-4 | 一次夹回会打三条 warn（字段级 + 聚合级 + 面板提示） | 留痕是刻意的；降级字段级日志需要先定「哪一条是给用户看的」 |
| F-5 | `ColorRestoreResult` 把 `s == null` 与「快照缺键」合进 `MISSING_FIELDS` | 所有调用方都保证非 null，属 API 洁癖 |

