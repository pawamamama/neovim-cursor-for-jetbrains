# NEXT-REFACTOR（v2）—— 结构重构方案与工作安排

> 生成：2026-10-20　｜　基线：已装 0.9.5-settings-ui（95,664 B / 09B927C8）
> **v1 是"把大类拆成 4 个类"的直觉方案；v2 换成"分阶段 + 每阶段有机器判据"的工程方案，并修正 v1 的两处误判。**
> 用户要求：先讨论清楚再动手（「你和我讨论咋重构，工作安排」）。**本文只讨论，不含任何代码改动。**

---

## 0. 一句话结论（和 v1 不同）

**病根不是"类太大"，而是"同一个参数在 4 个地方各有一份副本，靠 18 段手工代码互相同步"。**

- 拆类 = 把 1,594 行切成 3 份 —— **治标**，18 段手工同步原样存在，只是搬了家；
- **字段表（一张表描述 19 个参数，UI / 持久化 / 同步 / 审计全部从表生成）= 治本。**

所以顺序是：**先修护栏 → 再收写入口 → 再拆类 → 最后表驱动**。不是先拆。

---

## 1. 证据（2026-10-20 实测，不是印象）

| 文件 | 行数 | 说明 |
|---|---|---|
| `ui/settings/NeovideCursorConfigurable.java` | **1,594** | 54 个方法；`apply()` 从 1093 行起；10 处 `addSlider` 调用 + 1 处定义 |
| `ui/CaretAnimator.java` | 1,458 | 绘制热路径，本计划**不碰** |
| `ui/NeovideCaretManager.java` | 900 | 运行期唯一快照 `CONFIG` + 双向同步 |
| `settings/NeovideCursorSettings.java` | 719 | 持久化服务 + `syncStateToConfig` / `syncConfigToState` |
| `core/NeovideConfig.java` | 290 | 编译期默认值，**I-1 零 diff 铁律**，本计划**不碰** |
| `settings/CursorPresets.java` | 215 | 纯数据，切分已经合理 |

**关键实测数**：`apply()` 里有 **18 段逐字段脏合并**（`if (pending.X != baseline.X) target.X = pending.X;`），每一段都要单独处理 `Float.compare` / `eqHex` / `!=` 三种比较语义。

**探针耦合实测**：`tools/settings-probe/` 下 **15 个 java 文件、197 处**引用 `NeovideCursorConfigurable` 或 `*ForProbe()`。

---

## 2. v1 的两处误判（修正）

| v1 的说法 | 实测 | 修正 |
|---|---|---|
| 「第一步：拆成 4 个类」 | 拆类不改同步逻辑 | **错了**：拆完 18 段 if 还在，而且探针 197 处引用全部编译失败 → 你无法区分"重构坏了"还是"探针坏了" |
| 「54 字段 / 65 方法」 | 54 个方法 | 方法数对不上（v1 把字段/注释算进去了）；字段数按 State 的 19 项参数算更准 |

---

## 3. 病根模型：加一个参数，今天要改 8 处

```
① NeovideConfig 加字段 + 默认值            （常量源头，I-1 保护，不改默认值只加字段）
② NeovideCursorSettings.State 加 @Attribute 字段
③ NeovideCursorSettings.syncStateToConfig  ← State → CONFIG
④ NeovideCursorSettings.syncConfigToState  ← CONFIG → State（15 项回写）
⑤ NeovideCursorConfigurable 的 SliderSpec  + addSlider 调用
⑥ NeovideCursorConfigurable.apply() 加一段逐字段脏合并
⑦ reset() / 控件回灌（syncAllControlsFromPending）
⑧ 探针 *_ForProbe + 契约文档 + 审计清单
```

**这 8 处就是"改一处崩一处"的物理来源** —— 漏掉任何一处，症状都是"存了但读不到""读到了但显示不刷"。

**可度量的目标**：加一个参数的改动点 **8 → 2**（① 表定义 + ② State 字段）。

---

## 4. 四个阶段（每阶段独立可交付、可回退）

### Phase 0：探针解耦（护栏先立）　≈1 天

**做什么**：加一层 `ProbeFacade`（package-private，只在探针 classpath 可见），探针改成只通过它拿数据；`*ForProbe()` 从生产类迁出。
**触什么**：`tools/settings-probe/` 15 个 java 文件（197 处引用）、生产类里的 12 个 `*ForProbe()`。
**判据**：**旧探针跑旧 jar 的输出 == 新探针跑同一个 jar 的输出**（逐行 diff 为空）。断言内容一条不许改。
**为什么必须第一**：探针是唯一的"改成没改成"的判据机器。护栏碎着做重构 = 盲飞。

### Phase 1：`EditSession` 收写入口　≈1 天

**做什么**：把 `pending` / `baseline` / `syncingControls` 三个字段收进一个类，**所有写操作只能走它的方法**。

```java
final class EditSession {
    NeovideCursorSettings.State pending;   // 编辑中的值（不再对外暴露 setter）
    NeovideCursorSettings.State baseline;  // 打开面板时的快照
    boolean syncingControls;               // 抑制回环

    void onControlChanged(String key, Object v);
    void commitTo(NeovideCursorSettings.State target);  // 只写"真改过"的字段
    boolean isDirty();
    void advanceBaseline(NeovideCursorSettings.State target);
}
```

**判据**：`pending` / `baseline` 的赋值点全部收敛到 `EditSession` 内部（grep 生产代码 = 0 处外部赋值）；R-01/R-02 那类 bug 在结构上不可能再发生。
**风险**：低（机械抽取，逻辑不变）。

### Phase 2：拆类（纯搬家，不改逻辑）　≈1.5 天

| 新类 | 职责 | 预估 |
|---|---|---|
| `SettingsPanelBuilder` | 只建 UI：`createPanel()` + `addSlider()` + 各分组 | ~550 |
| `SettingsPanelBinder` | 控件 ↔ `EditSession` 绑定、回灌、`SliderBinding` | ~450 |
| `NeovideCursorConfigurable` | 只留 Configurable 契约：`apply/reset/isModified/disposeUIResources/enableSearch` | ~300 |

**判据**：每个类 ≤ 600 行；54 个方法全部有归属（清单对照）；**行为零变化**（Phase 0 的探针全绿 + parity 逐字节一致）。
**风险**：中（纯搬家但跨文件，容易漏 `private` → `package-private`）。

### Phase 3：字段表（治本）　≈2 天

一张表描述 19 个参数，UI / 持久化 / 同步 / 审计 / 探针**全部从表生成**：

```java
sealed interface FieldSpec<T> permits FloatField, BoolField, ColorField, EnumField {
    String key();                    // "glowOpacity"
    String label();                  // "辉光强度"
    T compileDefault();              // ★ 从 NeovideConfig 读，不重复字面量（天然满足 I-1）
    Range range();                   // min / mid / max / step
    T getState(State s);  void setState(State s, T v);
    T getConfig(NeovideConfig c);  void setConfig(NeovideConfig c, T v);
}
```

有了表，`apply()` 的 18 段 if 变成：

```java
for (FieldSpec<?> f : FIELDS) {
    if (f.isDirty(pending, baseline)) f.copy(pending, target);   // 比较语义由子类型负责
}
```

**判据**：
- `apply()` 内不再出现逐字段 `if`（grep `pending.` 在 `apply()` 内 = 0）；
- 加一个参数只需改 2 处（表定义 + State 字段）—— 用 `git diff --stat` 数文件数；
- 审计清单 19 项改为**从表导出**，表和 `NeovideConfig` 默认值不一致就红；
- 全量探针 + parity 全绿。

**风险**：中高（这是唯一真正改语义的一步：比较语义必须**逐字段保持** —— `Float.compare` / `equalsIgnoreCase` / `!=` 三种不能统一）。
**缓解**：Phase 1/2 已经把爆炸半径削小；先做 3 个字段（1 个 float / 1 个 bool / 1 个颜色）的试点，绿了再全量。

### Phase 4：让 State 成为唯一真相（**建议不做**）

原设想：删掉 `NeovideCaretManager.CONFIG`，绘制热路径直接读 `State`。

**建议不做**，理由：
1. Phase 3 之后，`CONFIG` 已经只是"由表生成的双向复制快照"，病根（手工同步）已经消失；
2. 删 `CONFIG` 要动 `CaretAnimator` / `TrailCorner` / `DampedSpring` 的读取路径 —— 那是 125 fps 热路径，`State` 是 `@State` 组件、可能触发平台锁；
3. **风险/收益比不划算**：省掉的东西是"一次复制"，换来的是物理核心被搅动的可能。

---

## 5. 为什么 Phase 0 非要第一（这条最容易被我做错）

现在探针的写法是：`new NeovideCursorConfigurable()` → 直接调 `pendingForProbe()` / `slidersForProbe()` 断言内部状态。

- Phase 2 一拆类，`pendingForProbe()` 就换家了 → **15 个探针文件编译失败**；
- 这时你面对一个 200+ 断言的套件全红，分不清"我拆错了"和"探针找错地方了"；
- 0.9.5 那三轮返工，有一半时间就是这么浪费掉的（见 LESSONS 第 21 条）。

所以 Phase 0 先做一层门面，探针只依赖"面板能做什么"，不依赖"面板内部长什么样"。**这才是让重构可验证的前提。**

---

## 6. 每阶段的验收判据（全部机器可判）

| 判据 | 怎么判 | 谁来跑 |
|---|---|---|
| RF-1 | 200+ 条断言全绿（`run-t9-full.ps1` 11 段） | 我跑，贴输出 |
| RF-2 | parity 3 份逐字节相同 | 同上 |
| RF-3 | `NeovideConfig` 零 diff（`git diff --stat` = 0 行） | 同上 |
| RF-4 | `pending`/`baseline` 外部赋值点 = 0（grep） | 同上 |
| RF-5 | 每类 ≤ 600 行；54 个方法全部有归属 | 行数统计 + 清单 |
| RF-6 | 加一个参数的改动文件数 ≤ 2 | `git diff --stat` 数 |
| RF-7 | 变异测试仍有效（6 个变异仍能变红；真实值 12/11/3/4/1/2） | `run-v-mutations.ps1` |
| RF-8 | 每阶段结束都产出可安装 jar（否则等于没交付） | `build\offline-build.ps1` |

---

## 7. Phase 0 的授权边界（**必须你先同意，我才动**）

| 允许 | 不允许 |
|---|---|
| 改探针**怎么访问**数据（经 `ProbeFacade`） | 改**断言什么**（一条断言都不许改、不许删、不许放宽） |
| 把 `*ForProbe()` 从生产类迁到门面 | 借机新增/删除断言 |
| 改探针的编译入口 | 改探针依赖的 jar 或 classpath |

**等价性证明（硬要求）**：旧探针 + 旧 jar 的输出，与新探针 + **同一个**旧 jar 的输出，**逐行 diff 为空**。做不到就回退 Phase 0。

---

## 8. 工作安排（和 0.9.6 / 0.9.7 的关系）

| 版本 | 内容 | 谁会感觉到 | 预估 |
|---|---|---|---|
| **0.9.6** | 你提的问题：Tools 3 项 + 修不同步 + 预设统一 0.11 + 输入平滑字段 + 已修改标记 + 高对比锐度 | **你直接感觉到** | 2–3 天 |
| **0.9.7** | 行缎带新物理（累积拉伸 + 欠阻尼回弹） | 你直接感觉到 | 3–5 天（含调研） |
| **0.9.8** | Phase 0 + Phase 1（护栏 + 收写入口） | 感觉不到，但之后改东西不再"改一处崩一处" | 2 天 |
| **0.9.9** | Phase 2 + Phase 3（拆类 + 字段表） | 感觉不到；**加参数成本从 8 处降到 2 处** | 3.5 天 |
| ~~0.10.0~~ | ~~Phase 4~~ | 建议不做 | — |

**每版交付物固定 5 件**（和 0.9.5 一致）：jar + SHA256 + `build\rollback-*.ps1` + `VERSIONS.md` 一行 + 探针报告。

**为什么重构放最后**（不是"重构不重要"，是三个具体理由）：
1. **C 组（Tools 语义重构）本身就要动 `NeovideCursorConfigurable`** —— 先结构重构等于把它改两遍；
2. 修完 0.9.6 的问题后，探针断言会**更多**，之后重构的护栏更厚；
3. 0.9.7 的新物理会**新增参数**（累积长度、阻尼比），正好用来检验 Phase 3 的"加参数只改 2 处"是否真的成立。

---

## 9. 要你拍板的 4 件事

| # | 问题 | 我的建议 | 你的选择 |
|---|---|---|---|
| **D1** | 重构排在哪？ | 排在 0.9.7 之后（0.9.8 开始），即"先修问题 + 先做新物理" | ☐ |
| **D2** | Phase 4（删 `CONFIG`）做不做？ | **不做** —— Phase 3 后已无收益，风险在热路径 | ☐ |
| **D3** | "加一个参数"的目标成本定多少？ | ≤ 2 个文件（表 + State 字段） | ☐ |
| **D4** | Phase 0 允许改 15 个探针文件吗？ | 允许，但只能改"怎么访问"，断言一条不许动 + 逐行 diff 等价性证明 | ☐ |

**你拍完这 4 个，我写 `REFACTOR-CONTRACT.md`（像 `SETTINGS-UI-CONTRACT.md` 那样的逐条契约），然后才动代码。**

---

## 10. 不做什么

| 项 | 原因 |
|---|---|
| 不改任何用户可见行为（Phase 0–3） | 纯结构；判据是"行为逐字节不变" |
| 不改 `NeovideConfig` 任何默认值 | 硬纪律 1，永远成立 |
| 不动 `CaretAnimator` / `TrailCorner` / `DampedSpring` | 绘制热路径，与本计划无关 |
| 不换 UI 框架（Jewel / Compose） | 需要 Compose 编译器插件，纯 javac 构建调不了 |
| 不并入 0.9.6 / 0.9.7 | 一版只做一类事，否则出问题分不清是谁引起的 |

---

## 11. 与旧文档的关系

- 本文 **取代** v1 的 NEXT-REFACTOR.md 全文（v1 的 RF-1..RF-6 判据保留并扩充为 RF-1..RF-8）；
- 决策台账见 `DECISIONS-0.9.6.md` §8.3（排序推迟到下次讨论）；
- 踩坑史见 `LESSONS.md` 第 21 条（"修 bug 引起新 bug"的根因就是本计划要消灭的东西）。
