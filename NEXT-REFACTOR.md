# NEXT-REFACTOR —— 设置面板结构重构计划（0.9.6 候选）

> 生成：2026-10-20　｜　基线：0.9.5-settings-ui（待交付）
> **本文档只规划结构重构，不改变任何用户可见行为。**
> 触发原因：0.9.5 三轮审查抓出 13 条 findings，**全部集中在同一个类的状态同步上**。

---

## 0. 一句话

**功能是对的，验证是充分的，但 `NeovideCursorConfigurable`（1,582 行 / 54 字段）是 God Class，
而本轮所有 bug 都出在它的状态同步上。下一版应做一次纯结构重构。**

---

## 1. 为什么要重构（不是「代码丑」，是「bug 集中在一个地方」）

### 1.1 数据

| 文件 | 行数 | 说明 |
|---|---|---|
| `ui/settings/NeovideCursorConfigurable.java` | **1,582** | 54 字段 / 65 方法 / `createPanel()` 单方法 200+ 行 |
| `ui/CaretAnimator.java` | 1,458 | 原有核心，本批几乎未动 |
| `ui/NeovideCaretManager.java` | 857 | 从 481 涨到 857 |
| `settings/NeovideCursorSettings.java` | 719 | 新增（持久化服务） |
| `settings/CursorPresets.java` | 215 | 纯数据，切分合理 |
| `ui/CursorPresetApplier.java` | 128 | 单一职责，切分合理 |
| `ui/CaretSchemeWatcher.java` | 138 | 单一职责，切分合理 |

### 1.2 bug 的分布

0.9.5 三轮审查共 13 条 findings，**8 条 medium 全部出在 `NeovideCursorConfigurable` 的状态同步上**：

| id | 内容 | 涉及的状态 |
|---|---|---|
| B-1 | 补 listener 后 `super.isModified()` 恒 false → 应用按钮变灰 | `pending` vs 控件 |
| B-2 | getter 用 snap 后 `isModified()` 永真 | `pending` vs 控件 |
| R-01 | 第二次 apply 冲掉 Tools 的改动 | `pending` vs `baseline` |
| R-02 | 拖滑块后预设下拉不刷新 | `pending` 变了但显示没刷 |
| R-03 | 旧 scheme 不恢复 | `HIDDEN_SCHEMES` 账本 |
| R-09 | 切回旧主题时原色被兜底色覆盖 | `HIDDEN_SCHEMES` 账本 |

### 1.3 病根

19 个可调参数分布在 **4 个地方**：

```
NeovideConfig（编译期默认值）
NeovideCursorSettings.State（持久化）
NeovideCaretManager.CONFIG（运行期）
NeovideCursorConfigurable.pending / baseline（面板预览副本）
```

**「同步」这件事没有单一权威。** 每次修复都是在某两地之间补一条同步，于是引入新边界：

```
修缺陷 1 → 引入 B-1/B-2
修 B-1/B-2 → 引入 R-01/R-02/R-03
修 R-03   → 引入 R-09
```

---

## 2. 做什么（三件事，互不依赖，可按顺序做）

### 重构 1：拆 `NeovideCursorConfigurable`

| 新类 | 职责 | 预估行数 |
|---|---|---|
| `PanelBuilder` | 只建 UI：`createPanel()` + `addSlider()` + 各 `group(...)` | ~500 |
| `PanelBinder` | 只做绑定与同步：`SliderBinding`、`syncingControls`、`syncAllControlsFromPending` | ~400 |
| `NeovideSettingsConfigurable` | 只做 Configurable 契约：`apply/reset/isModified/disposeUIResources/enableSearch` | ~300 |
| `ProbeAccessors`（package-private） | 把 12 个 `*ForProbe()` 从生产类移出 | ~120 |

**验收**：`NeovideCursorConfigurable` 不再存在或被这三个类替代；原有 65 个方法全部有归属。

### 重构 2：引入单一 `EditSession`

```java
final class EditSession {
    State pending;              // 编辑中的值
    State baseline;             // 打开面板时的快照（脏判据的基线）
    boolean syncingControls;    // 抑制回环

    void onControlChanged(String field, Object value);   // 唯一写入口
    void commitTo(State target);                          // 只写「真改过」的字段
    boolean isDirty();                                    // 逐字段比较
    void advanceBaseline();                               // apply 后调用
}
```

**关键约束**：`pending` / `baseline` / `syncingControls` **不再暴露给面板类**，
所有变更走 `EditSession` 的方法。这样 R-01（pending 与 baseline 不同步）、
R-02（值变了但显示没刷）在结构上**不可能发生** —— 因为只有一个写入口。

### 重构 3：`SliderBinding` 提为独立类

现在它是内部类，只管数据和一段逻辑。提出后自管：

```
值（原始 tick + 精确值）
控件引用（slider / textField / label）
刻度表（min / mid / max / step）
预设匹配（当前值对应哪个预设，或「自定义」）
```

**验收**：65 个方法里的重复（值↔tick 换算、标签格式化）集中到这一个类。

---

## 3. 不做什么

| 项 | 原因 |
|---|---|
| 不改任何用户可见行为 | 这是纯结构重构，验收判据是「行为逐字节不变」 |
| 不改 `NeovideConfig` 默认值 | 硬纪律 1 永远成立 |
| 不引入新框架（Jewel / Compose） | 需要 Compose 编译器插件，本项目纯 javac 构建调不了 |
| 不动 `CaretAnimator` / `TrailCorner` 等绘制热路径 | 与本次重构无关 |
| 不顺手改预设值或文案 | 如需改动，另开任务 |

---

## 4. 怎么验证（复用现有资产，成本极低）

### 4.1 现成的回归护栏

本轮已积累 **200+ 条可复跑断言**，重构后全部重跑即可：

| 探针 | 断言数 | 覆盖 |
|---|---|---|
| `run-parity.ps1` | 3 份逐字节 | 物理/几何/轨迹 |
| `run-audit.ps1` | 19 项 + D 段 | 观感常量 |
| `run-ime-geom-test.ps1` | 29 | IME 边界 |
| `V-PROBES`（verifier） | 119 | 契约 V1–V15 |
| `V-MUTATIONS` | 6 个变异 | 变异忠实度 |
| `settings-v2` | 68 | 布局/预设/吸附 |
| `4bugs` | 全绿 | 4 个原始缺陷 |
| `t7-probe` | 24 | R-01/R-02/R-03 |
| `t9-full` | 11 项 | 全量一键复跑 |

### 4.2 新增的验收判据

| # | 判据 |
|---|---|
| RF-1 | 上述 200+ 条断言**全部保持绿**（一条都不许红） |
| RF-2 | `NeovideCursorConfigurable` 拆成 4 个类后，每个 ≤ 500 行 |
| RF-3 | `pending`/`baseline`/`syncingControls` **不再被面板类直接访问**（grep 验证） |
| RF-4 | `EditSession` 是唯一写入口（grep 所有 `pending.` 赋值点） |
| RF-5 | 默认值零改动（`NeovideConfig` 零 diff + 35 项禁改） |
| RF-6 | 变异测试仍然有效（t7/t9 的变异仍能变红） |

---

## 5. 风险评估

| 风险 | 可能性 | 影响 | 缓解 |
|---|---|---|---|
| 重构引入新 bug | 中 | 高 | 200+ 探针全跑；变异测试验证探针仍有效 |
| 拆分后发现耦合比想象深 | 中 | 中 | 分三步做，每步独立可验证、可回退 |
| 探针因重构而失效 | 低 | 高 | 探针只依赖公开行为（`*ForProbe()` 移出后要同步改探针） |
| 时间超预期 | 中 | 低 | 按需只做重构 1+2（重构 3 可延后） |

**总体判断：风险低。** 纯结构整理 + 200+ 条现成护栏 + 每步可独立回退。

---

## 6. 建议顺序

```
第 1 步：重构 1（拆分）+ 跑全部探针          ← 最小风险，先做
第 2 步：重构 2（EditSession）+ 跑全部探针    ← 收益最大（消灭病根）
第 3 步：重构 3（SliderBinding）+ 跑全部探针  ← 可延后
```

每步完成后都是**可交付状态**，不会出现「重构到一半不能用」。

---

## 7. 与其它计划的关系

| 计划 | 关系 |
|---|---|
| 多光标（Alt 列选） | **独立**。需要 `Map<Editor, List<Animator>>` 重构，与本计划无交集 |
| 流光 RGB / 辉光双色 | **独立**。改绘制路径，与本计划无交集 |
| 残影输入平滑 | **独立** |
| P0 空闲不交脏区 / P1 隐藏标签页降频 | **独立**（调度层，不是 UI 层） |

**结论**：本计划可以与上述任何一项并行或先后进行，不冲突。
