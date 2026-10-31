# 第 3 批收尾 —— 5 条 MEDIUM + 16 条 LOW 实施计划

> 生成：2026-10-31 · 依据 `docs/HANDOFF-BATCH3-AUDIT-2026-10-31.md` §三 +
> `docs/HANDOFF-NEXT-2026-10-31.md` §3.1 + `docs/CODE-AUDIT-2026-10-30.md` §9.3（LOW 全表）。
> 基线：HEAD `8ce6524`，门禁 ALL PASS（exit 0），D0 零 diff。
> **本批全部是内部质量，正常使用零观感变化。**

---

## 零、Research 记录

### 0.1 第 1 轮：搜本仓库（项目内既有模式）

| 待修项 | 抄哪个既有实现 | 位置（当前实测） |
|---|---|---|
| P-M-9 抑制标志 | 已正确的「保存前值 + finally 恢复」写法 | `NeovideCursorConfigurable.java:2025-2026 / 2069`、`:2152-2157`、`:2234-2239`、`:2644-2651`、`:2705-2712` |
| P-M-4 失败可见出口 | `showUserNotice` + `PERSIST_FAILED_*` 文案常量 | `NeovideCursorConfigurable.java:108-110`、`:3926-3947`；`PluginInfoBar.java:102-166` |
| U-M-6 退避升级 | `consecutiveFailures`（loop 局部变量）+ `shouldLogFailure` 限流 | `RenderScheduler.java:191 / 277-288 / 302-336`；`CaretAnimator.java:2501-2503` |
| U-M-8 沿用上一帧 key | `CursorDiag.swallowed` + `swallowedQuietly` | `CaretAnimator.java:2519-2525`；`blockTrailKey` `:418-421`；`trails` 清理 `:2401` |
| P-M-1 apply 事务 | `SettingsField.readState/writeRaw` | `SettingsField.java:287-295 / 322-324`；`NeovideCursorSettings.getState()` `:103-105`（**返回内部引用，无 replaceState**） |
| 探针注入钩子 | `CaretAnimatorProbe.setAfterMarkHook` | `CaretAnimatorProbe.java:29-31`；生产侧 `CaretAnimator.java:2316-2319` |
| 源码扫描型断言 | `VDeadField` D-2 | `tools/settings-probe/VDeadField.java:38-45` |

**关键结论（决定 P-M-1 的路线）**：`NeovideCursorSettings.getState()` 的 javadoc 明确写
「返回内部 `state` 对象本身 —— 不新建、不复制」，且全仓 `grep -n "replaceState\|setState"` 零命中。
所以 P-M-1 **不能**「整体替换引用」，只能「先拼副本、成功后再逐字段写回同一对象」。

### 0.2 第 2 轮：搜外部

```text
$ gh search code "Swing Configurable apply rollback on exception" --limit 5
（空结果）
$ gh search code "IntelliJ Configurable apply persist failure notify" --limit 5
（空结果）
```

本机 `gh` 未认证 / 代理对 GitHub 间歇失效（CLAUDE.md 已记录）。改自家代码搜不到是正常结果；
两条通用模式在项目内已有先例，直接沿用：

- 抑制标志的嵌套安全 → 「保存前值 + finally 恢复」（RAII 的 Java 手动版，项目已有 5 处）；
- 设置写入的事务性 → 「全部在副本上完成，最后统一提交」（本计划 P-M-1）。

---

## 一、5 条 MEDIUM

### 1. P-M-9 抑制标志不保存前值

**根因（证据）**
`syncingControls`（`:615`）与 `suppressDerive`（`:626`）是裸 `boolean`。全部写入点分两类：

| 类别 | 位置 | 现状 |
|---|---|---|
| ✅ 已保存前值 | `:2025-2026 → :2069`、`:2152-2157`、`:2234-2239`、`:2644-2651`、`:2705-2712` | 正确 |
| ❌ 硬置 true→false | `:992 → :1143`、`:1933 → :1940`、`:1959 → :1966`、`:1990 → :1995`、`:2305 → :2312`、`:2372 → :2410`、`:3345-3346 → :3350-3351`、`:3379-3380 → :3384-3385`、`:3534-3535 → :3539-3540` | 嵌套时把外层 true 清成 false |

`apply()` 的 A2 段（`:3379-3386`）在 `super.apply()` 期间置两个标志；`A8` 段（`:3534-3541`）
在 `super.reset()` 期间再置一次。若将来任何一次 `super.*()` 内部触发一条也会进入抑制态的
生产路径，内层 `finally` 会把外层标志清成 false —— 外层剩余语句被当成「用户编辑」，
重复派生副本 / 脏标记乱置，且无任何报错。

**改法**
全部 9 个 ❌ 点改成与已有 5 处完全一致的写法：

```java
boolean prevSync = syncingControls;          // suppressDerive 同理用 prevDerive
syncingControls = true;
try {
    ...
} finally {
    syncingControls = prevSync;
}
```

不加计数器：这两个标志是「嵌套作用域」语义，布尔的前值保存已经精确表达；
计数器只在「并发 + 非异常复位」时才必要，本类是单线程 EDT。

**影响面**：仅异常/嵌套路径；单层调用时 `prev == false`，行为与硬置 false 完全一致 → 零观感变化。

**怎么测（探针 `VBatch3Tail` 的 T-1）**
生产侧加一个探针入口（放在 G 区「探针 API」）：

```java
/** 探针用（P-M-9）：模拟「外层已处于抑制态」时调用一条真实回灌路径，
 *  返回调用结束后的两个标志值。修好后两者都必须仍是 true。 */
public boolean[] suppressionNestingForProbe() {
    boolean prevSync = syncingControls, prevDerive = suppressDerive;
    syncingControls = true;
    suppressDerive = true;
    try {
        syncAllControlsFromPending();      // 真实生产路径，不是复刻
        return new boolean[]{ syncingControls, suppressDerive };
    } finally {
        syncingControls = prevSync;
        suppressDerive = prevDerive;
    }
}
```

断言：返回 `[true, true]`。
**变异**：把 `syncAllControlsFromPending` 的 `finally` 改回
`syncingControls = false; suppressDerive = false;` → T-1 必须变红。

**风险**：极低。唯一的语义变化是「嵌套时外层保持抑制」——正是目标。

---

### 2. P-M-4 「检查更新」失败不可见

**根因（证据）**
`PluginInfoBar.doCheck` 失败分支（`:148-152`）把按钮文字复位成与点击前完全一致的
「检查更新」，原因只写进 tooltip。类注释（`:42`）自述「失败时静默（不弹窗骚扰用户）」——
这条设计在「本机代理经常对 GitHub 失效」的现实下让用户无法区分「已是最新」与「按钮坏了」。

**改法**
1. 新增纯函数（包级 static，可离线断言）：

```java
/** 失败时按钮上的一行可见文案（P-M-4）。超时 / HTTP 错误 / 其它给不同话术。 */
static String failureText(String error) {
    if (error == null) return "检查失败";
    String e = error.toLowerCase(java.util.Locale.ROOT);
    if (e.contains("sockettimeout") || e.contains("timed out") || e.contains("timeout")) {
        return "检查失败（超时）";
    }
    if (e.startsWith("http ")) return "检查失败（" + error + "）";
    if (e.contains("仓库暂无 release")) return "暂无 Release";
    return "检查失败";
}
```

2. 失败分支改为 `check.setText(failureText(err));` + tooltip 保留完整原因。
3. 更新类注释第 42 行（旧设计已改）。
4. 不做模态弹窗：该按钮是常驻控件，每次点击失败都弹框会打断设置流程；
   按钮文字本身就是「一行可见提示」，且可被探针断言。此为实现细节，按纪律四-L 自行决定并记录理由。

**影响面**：仅失败路径；成功路径（有新版本 / 已是最新）一字不动 → 正常使用零变化。

**怎么测（T-2）**：断言 `failureText("SocketTimeoutException: ...")`、
`failureText("HTTP 500")`、`failureText("仓库暂无 Release")`、`failureText("其它")`
四者都**不等于**「检查更新」，且超时与 HTTP 两类文案互不相同。
**变异**：让 `failureText` 恒返回「检查更新」→ T-2 全红。

**风险**：低。文案属失败路径新增。

---

### 3. U-M-6 `dispatchDue` 连续 false 无升级检测

**根因（证据）**
`RenderScheduler.loop()`（`:269-276`）：

```java
if (!dispatchDue()) {
    try { Thread.sleep(BUSY_RETRY_MS); } catch (InterruptedException ignored) { }
}
```

`BUSY_RETRY_MS = 1`（`:55`）。`dispatchDue()` 返回 false 的唯一现实原因：
所有到期 animator 的 `framePending` 都是 true（EDT 卡死，`runTick` 还没跑完）。
此时调度线程 1000 次/秒空转、**零日志**；与 `consecutiveFailures`（`:191`、`:277-288`、
`:302-336`）的异常路径待遇完全不对等。

**改法**
照 `consecutiveFailures` 的线程级局部变量模式：

```java
int consecutiveNotPosted = 0;      // loop() 局部，不能 static（H-1 的教训）
...
if (!dispatchDue()) {
    consecutiveNotPosted++;
    long backoff = busyBackoffMs(consecutiveNotPosted);
    if (shouldLogBusy(consecutiveNotPosted)) {
        try { LOG.warn("neovide-cursor: 调度到期但投递不出去（连续 " + consecutiveNotPosted
                + " 轮），EDT 可能卡死；本轮退避 " + backoff + " ms"); } catch (Throwable ignored) { }
    }
    try { Thread.sleep(backoff); } catch (InterruptedException ignored) { }
} else {
    consecutiveNotPosted = 0;
}
```

两个包级 static 纯函数（可离线断言）：

```java
static long busyBackoffMs(int consecutive) {
    if (consecutive <= BUSY_BACKOFF_START_ROUNDS) return BUSY_RETRY_MS;
    int shift = Math.min(consecutive - BUSY_BACKOFF_START_ROUNDS - 1, BUSY_BACKOFF_MAX_SHIFT);
    return Math.min(BUSY_RETRY_MS << shift, BUSY_BACKOFF_CAP_MS);
}
static boolean shouldLogBusy(int consecutive) {
    return consecutive == 1 || consecutive % BUSY_LOG_EVERY == 0;
}
```

常量：`BUSY_BACKOFF_START_ROUNDS = 10`（前 10 轮保持 1ms，不影响正常帧调度）、
`BUSY_BACKOFF_MAX_SHIFT = 5`、`BUSY_BACKOFF_CAP_MS = 32`、`BUSY_LOG_EVERY = 100`
（100 轮 ≈ 100ms 一次 warn，既不洪水也不静默）。

**为什么前 10 轮保持 1ms**：EDT 正常繁忙（长 tick）会短暂让 `dispatchDue()` 返回 false，
这是设计内的；立即退避会拖慢帧率。连续 10 轮（≈10ms）才认为「不是偶发」。

**影响面**：正常路径 `dispatchDue()` 恒 true → 计数器恒 0 → 完全不变。
只在 EDT 卡死时改变退避曲线与增加日志。

**怎么测（T-3）**：
- E-1：`busyBackoffMs(1..10)` 全等于 1；
- E-2：`busyBackoffMs(11) == 2`，单调不减，`busyBackoffMs(1000) == 32`（封顶）；
- E-3：`shouldLogBusy(1)` 为真、`shouldLogBusy(99)` 为假、`shouldLogBusy(100)` 为真。
**变异**：`busyBackoffMs` 恒返回 `BUSY_RETRY_MS` → E-2 红；`shouldLogBusy` 恒 false → E-3 红。

**风险**：低。纯函数 + 局部变量；不碰 `consecutiveFailures` 与自愈链路。

---

### 4. U-M-8 块选区 key 退化

**根因（证据）**
`CaretAnimator.refreshTarget()`（`:2174-2183`）：

```java
int lineNo = -1;
try {
    lineNo = editor.getDocument().getLineNumber(blockStarts[i]);
} catch (Throwable ignored) { }              // ← 空 catch
key = blockTrailKey(lineNo, blockStarts[i]); // ← lineNo<0 时退化为 offset
```

`blockTrailKey`（`:418-421`）在 `lineNo < 0` 时返回 `Integer.valueOf(blockStart)`。
下一帧行号恢复 → key 从 offset 变回行号 → `trails.get(key)` 未命中 → **新建 SpringTrail**；
旧条目在 `:2401` 的 `removeIf` 里被清掉。每帧一次，块选区每行一条。

**改法**
1. 抽一个包级 static 纯函数（可离线断言，生产真正调用）：

```java
/**
 * 块选区 key（U-M-8）：行号可用时用行号 key 并记住；
 * 行号查询失败时沿用该槽位上一帧的 key；只有首见才退化为 offset。
 */
static Object blockTrailKeySticky(Object[] lastKeys, int slot, int lineNo, int blockStart) {
    if (lineNo >= 0) {
        Object k = blockTrailKey(lineNo, blockStart);
        if (slot >= 0 && slot < lastKeys.length) {
            lastKeys[slot] = k;
        }
        return k;
    }
    if (slot >= 0 && slot < lastKeys.length && lastKeys[slot] != null) {
        return lastKeys[slot];
    }
    return blockTrailKey(-1, blockStart);
}
```

2. 新增实例字段 `private final Object[] lastBlockKeys = new Object[MAX_TRAILS];`
3. 调用点改：

```java
int lineNo = -1;
try {
    lineNo = editor.getDocument().getLineNumber(blockStarts[i]);
} catch (Throwable t) {
    swallowedQuietly("blockTrailKey", t);
}
key = blockTrailKeySticky(lastBlockKeys, i, lineNo, blockStarts[i]);
```

**边界**：块选区行数减少时，多出来的槽位保持旧值，但下一帧不会被读到（`i` 只到
`count-1`）；行数增加时新槽位是 null → 首见走 offset 兜底，下一帧自动纠正。
`MAX_TRAILS` 截断在 `trailSlotCount`，`i` 不会越界。

**影响面**：只在 `getLineNumber` 抛异常时改变行为（从「每帧重建」变为「沿用」）→ 正常路径零变化。

**怎么测（T-4）**：
- F-1：`blockTrailKeySticky(arr, 0, 5, 100)` 返回行号 key，且 `arr[0]` 被记住；
- F-2：紧接着 `blockTrailKeySticky(arr, 0, -1, 100)` 返回**同一个** key（沿用，不退化）；
- F-3：新数组 `blockTrailKeySticky(new Object[4], 1, -1, 777)` 返回 `blockTrailKey(-1,777)`（首见兜底）；
- F-4：恢复行号后返回行号 key（不是 offset）；
- F-5（结构断言，照 `VDeadField` 模式）：源码里 `refreshTarget` 的 key 计算必须调用
  `blockTrailKeySticky` —— 防止「纯函数绿、生产没用」。
**变异**：删掉「沿用」分支（恒走 offset 兜底）→ F-2 红；调用点改回 `blockTrailKey` → F-5 红。

**风险**：低。新增纯函数 + 一个 64 元素数组字段。

---

### 5. P-M-1 `apply()` 逐字段写活 State，无回滚

**根因（证据）**
`apply()` A3 循环（`:3418-3423`）：

```java
for (SettingsField sf : SettingsField.FIELDS) {
    if (sf.differs(pending, baseline)) {
        sf.copy(pending, target);        // target = svc.getState() 本体
    }
}
```

`target` 是内部引用（`NeovideCursorSettings.getState()` `:103-105` 明确「不新建、不复制」）。
从第一次 `sf.copy` 到 `session.reloadFrom(target)`（`:3509`）之间没有回滚点；
预设同步段（`:3435-3454`）同样就地写。中途任一处抛出 → State 半新半旧、未落盘、baseline 不动。

**方案选择：B1（副本拼装 + 写回段回滚），不选 B2（replaceState）**

| | B1 副本拼装 | B2 新增 `replaceState(State)` |
|---|---|---|
| 是否改 `NeovideCursorSettings` | 否 | 是 —— **违反「唯一真相源」契约**（`getState()` 的 javadoc） |
| 外部持有旧引用 | 不受影响 | Tools 菜单 / CONFIG 同步持有的旧引用全部失效 |
| 白名单 | `ui/settings/` 内 | 需改 `settings/` 层 —— **任务书明令不许** |

**改法**

```java
// A3（P-M-1）：先在副本上拼好，全部成功后再写回活 State。
NeovideCursorSettings.State staged = deepCopy(target);
java.util.List<SettingsField> dirty = new java.util.ArrayList<>();
for (SettingsField sf : SettingsField.FIELDS) {
    if (sf.differs(pending, baseline)) {
        sf.copy(pending, staged);
        dirty.add(sf);
    }
}
// 预设 5 件套同样先写 staged（保留原来的逐项脏判据）

// ---- 提交段：只有这里写活 State，且自带回滚 ----
Object[] before = new Object[dirty.size()];
for (int i = 0; i < dirty.size(); i++) before[i] = dirty.get(i).readState(target);
try {
    int written = 0;
    for (SettingsField sf : dirty) {
        sf.copy(staged, target);
        written++;
        if (applyWriteHookForProbe != null) applyWriteHookForProbe.accept(written); // 探针注入
    }
    // 5 件套写回 target
    // A4 落盘（原有 PersistResult 三态闸门一字不动）
    // A5 applyConfig
} catch (Throwable t) {
    for (int i = 0; i < before.length; i++) {
        try { dirty.get(i).writeRaw(target, before[i]); } catch (Throwable ignored) { }
    }
    // 5 件套回滚
    try { NeovideCaretManager.applyConfig(); } catch (Throwable ignored) { }  // 把 CONFIG 拉回
    LOG.warn("neovide-cursor: apply 中途失败，已回滚 State", t);
    persistFailed = true;      // 保持 isModified=true，允许重试
    showUserNotice("设置未能完整应用", "本次改动已撤销，请重试。", true);
    return;
}
```

**与 P-C-1 的关系（为什么不冲突）**
- P-C-1 管「**已写到 State、落盘返回 FAILED**」：不推进 baseline、`persistFailed = true`、提示。
- P-M-1 管「**写到 State 中途抛出**」：回滚 State、不推进 baseline、提示。
- 两者共用 `persistFailed` 标志让 `isModified()` 保持真，但**触发条件与语义分开**，
  不是把三态闸门合并成一个。落盘成功路径（CONFIRMED / DEFERRED）完全不受新代码影响。

**影响面**：正常路径多一次 `deepCopy(target)`（apply 是用户点击，频率极低）；
写入顺序与值完全一致 → 零观感变化。

**怎么测（T-5）**：
- G-1：注入「第 3 个脏字段写入时抛」→ 断言 State 逐字段等于 apply 前的快照
  （用 `SettingsField.FIELDS` 的 `readState` 全面比对，不是只比那几个）；
- G-2：正常 apply 后 State 与改动前逐字段一致（证明零观感）；
- G-3：注入失败后 `isModified()` 仍为真（可重试）。
**变异**：删掉 catch 里的回滚循环 → G-1 红。

**风险**：中。集中在 `apply()` 一个方法内，但触及写回顺序；由 G-1/G-2 双向守。

> **实施记录（2026-10-31）**：最终采用「**整份 State 快照 + `target.copyFrom(rollback)`**」，
> 不是本文前面写的「副本拼装 + 逐字段写回」。理由：
> ① `State.copyFrom` 本来就要跟随 `SettingsField.FIELDS` 更新（六层链路的既有契约），
> 复用它不会多出一份「已写字段清单」这种必然会漏的第二清单；
> ② 整份快照天然覆盖 A3 的字段表 + 预设 5 件套 + 将来新增字段。
> 探针 T-5 覆盖：注入故障后**逐字段**比对 State、pending 保留、baseline 未前移、
> `isModified()` 为真、有「应用失败」提示、清故障后重试成功。变异验证：注释掉
> `target.copyFrom(rollback)` → T-5b 变红（差异字段 `animationLength`）。

---

## 二、16 条 LOW 的处置

行号以改动前的 HEAD 为准（改完后自然漂移）。「改」的条目全部是注释 / 死代码 / 局部加固。

| 编号 | 处置 | 改了什么 / 不改的理由 |
|---|---|---|
| P-L-1 | **改** | 5 处连续 javadoc 合并（ID / RESTORE_TPL / syncAllControlsFromPending / SliderBinding / animRestoreButtonForProbe）+ `AnimationPreview.editorFont()`：原三块堆叠导致 **editorFont 完全没有 javadoc**（详版块悬空），已把详版移到方法前、删简版 |
| P-L-2 | **改** | `6 个标签页 → 7`、`这 6 个内层 → 7`；`10 个数值标签 → 「全部数值标签（不写死数量）」`（写死必然再过期）。`TopGap.MEDIUM` 那条全仓已 0 命中，无需改 |
| P-L-3 | **改** | 列位注释原来写「空 cell 占住**标签**列」—— 实际 `row("")` 已自动补标签列，显式 `cell()` 占的是**下拉**列，按钮与「恢复默认值」同列（`plans/panel-refactor.md:276` 实测 180px）。已改正并写明「不要删这个空 cell」 |
| P-L-4 | **改（删死代码）** | 删除 `AnimationPreview.probeFontSize()`（与 `probeFontSizeNow()` 完全重复，`src/` + `tools/` + 反射字符串全仓零调用） |
| P-L-5 | **不改** | 给 combo 装自定义渲染器必然改变渲染路径（字体/边距由平台默认渲染器决定）→ 与「本批零观感变化」冲突。风险仅限用户自己/导入的配置（无权限边界），登记为后续安全项 |
| P-L-6 | **不改** | 正解在 `CustomPresets.nameError()`（`settings/` 层），而本批白名单**明令不许改 settings/**。面板侧只能拦一个入口，属半修，不如不做 |
| P-L-7 | **改** | 滑块 ChangeListener 里「写 pending 之后」的界面刷新包 `try/catch + LOG.warn`。当前无已知抛点，属结构性防护 |
| P-L-8 | **改** | `reset()` 手工复位 `paintDiag` 与 `paintDiagCheck`（它不是 State 字段，不走 `bindCheck`） |
| P-L-9 | **改** | `addSlider` 里 `setSnapToTicks(true)` 之后按 `pending` 显式 `setValue`（DSL 不设初值 → JSlider 默认 50；此时监听器未挂，无副作用） |
| P-L-10 | **改（并入 P-M-1）** | `apply()` 顶部加 `baseline == null` 守卫 + `LOG.warn`（`SettingsField.differs` 无空检查） |
| P-L-11 | **改** | `isRainbowSelected()` 的 `catch (Throwable ignored)` 改为 `LOG.warn`（吞了要留痕） |
| P-L-12 | **改** | `isShowing()` 守卫放在 **Timer 回调**里，不放 `step()` —— 放 `step()` 会废掉 `PreviewShowcase` 对未 realize 实例的 `probeTick` |
| P-L-13 | **不改** | 改 `JBUI` 字体/颜色会让默认字号与配色跟随平台主题 → 观感变化。本批要求零观感，登记为后续 |
| P-L-14 | **改（纯注释）** | `probeTick()` javadoc 补线程契约：「必须 EDT、仅用于未 realize 实例，否则与 8ms Timer 交错积分」 |
| P-L-15 | **不改** | 数组字面量 `new AnimationPreview[]{...}` 是**故意的可维护性选择**（注释写明「加第四个预览只改这一行」）；`refreshPreviews` 不在每帧热路径，一次 3 元素数组分配可忽略。改成三段 if 反而引入「加预览忘记通知」风险（§8.2 可维护性） |
| P-L-16 | **改（改名）** | `syncPreviewCursorSize → syncPreviewStyle`（它经 `pushStyleTo` 推尺寸 + 渐变 + 彩虹）。已 grep 全仓无反射/外部引用 |

## 三、探针文件规划

| 探针 | 覆盖 |
|---|---|
| 新建 `VBatch3Tail.java` | T-1 ~ T-5（5 条 MEDIUM 的行为断言）+ 结构与注释扫描 |
| 既有 `VBlock.java` | 不动（K 系列仍测 `blockTrailKey` 纯函数） |
| 既有 `VAddSetting` / `VerifyPanel` / `VPreset` / `VDefaultFreeze` | 本批不加字段、不加控件 → 常数不变 |

## 四、复查后的修正（2026-10-31，两个代理）

`code-reviewer`：0 CRITICAL / 0 HIGH / 2 MEDIUM / 8 LOW，结论 APPROVE。
`java-reviewer`（已注明跳过 Spring/Quarkus Framework Detection）：0 CRITICAL / 0 HIGH / 3 MEDIUM / LOW，结论 Approve with warnings。

采纳并修掉的：

| 来源 | 问题 | 修法 |
|---|---|---|
| java M-1 | `shouldLogBusy(1)` 在 EDT 常态繁忙（长 tick）下会按帧率刷 warn | 首条门槛抬到 `BUSY_BACKOFF_START_ROUNDS`（第 10 轮，开始退避时）；T-3d/T-3e 断言方向同步改 |
| java M-2 / code L-4 | 回滚路径 `persistQuietly()` 返回值被丢弃，且「已回滚」文案在磁盘未确认时撒谎 | 接住三态；`!= CONFIRMED` 时改用 `APPLY_FAILED_MESSAGE_DISK` 并 warn |
| java M-3 | `apply()` 的 `catch (Throwable)` 吞掉 `VirtualMachineError` | 回滚与提示做完后重新抛出 VMError |
| java L-1 | P-C-1 的 FAILED 分支在 P-M-1 的 try 内，`showUserNotice` 抛出会被误判为「State 没写完」并回滚 | 把 `persisted` 声明提到 try 外、FAILED 判定移到 try/catch 之后 |
| java L-2 | 调度异常路径不重置 `consecutiveNotPosted` | catch 开头归零 |
| java L-5 / code L-5 | 回滚路径无条件调 `setPaintDisabled`（会写日志 + 清屏） | 与 A5.5 同一条「只在变化时下发」 |
| code M-1 | 探针结构断言用相对路径读源码，`-Repo <worktree>` 时会读到主仓库 → 假绿 | 新增 `repoRoot()`，先读 `-Dneovide.repo`（与 `VBuildDecl` / `VDocCheck` 同约定） |
| code M-2 / java L-10 | T-5 只比对 `SettingsField.FIELDS`，漏了预设 5 件套与 `paintDiag` | 补 T-5b2 / T-5b3 |
| code L-1 | 我改的列位注释引用了 `panel-refactor.md:276` 撑不起的数字（77/180/280/367） | 改回原文的 21 / 97 / 220 |
| code L-2 | `applyFaultForProbe` javadoc 写「静态字段读」 | 改为「实例字段读」 |
| code L-6 | 探针类 javadoc 只登记 T-1 | 补 T-2~T-5 |

未采纳（记录理由）：

| 来源 | 条目 | 理由 |
|---|---|---|
| code L-3 | `prevBlockKeys` 在「槽位数不变、行变了」时不清缓存 | 只在 `getLineNumber` 同时失败的组合下发生；key 命名空间不重叠（行号 key 为负、offset key 非负），**不会 key 冲突**，最坏是一帧沿用邻行 key 后自愈。补断言的成本高于收益，已在字段注释写明 |
| code L-7 | 探针侧 `VBaseline`/`VDead`/`VReset`/`VUi` 里「6 个标签页」注释残留 | 不在本批 diff 内；属探针注释清理，单独登记 |
| code L-8 | `build/night-run-report.md` 未跟踪 | 本批不提交；提交时排除 |

## 五、施工顺序

```
P-M-9 → P-M-4 → U-M-6 → U-M-8 → P-M-1 → 16 条 LOW（死代码 → 注释 → 命名）
```

每条 MEDIUM 独立完成四段：红 → 绿 → 变异红 → 恢复绿。LOW 每条可单独 revert。
