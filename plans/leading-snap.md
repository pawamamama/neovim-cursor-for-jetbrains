# 前缘贴合（半边拖尾）实现计划 —— leadingSnap

> 生成：2026-10-31 · 依据 CLAUDE.md §7.1 + §六「左半边」实测数据
> 状态：**已实现**（2026-10-31）。最终落点与探针以 `CLAUDE.md` §7.1 与
> `tools/settings-probe/VLeadingSnap.java` 为准；本文件 §二/§三 是当时的计划稿。

---

## 一、Research 记录

### 1.1 仓内范式（第 1 轮，更重要）

抄的是三个现成的「设备级字段」样本，不是新发明：

| 要抄什么 | 抄的是谁 | 位置 |
|---|---|---|
| 设备级布尔字段怎么注册 | `multiCursorNoGlow` | `SettingsField.build()`：`out.add(bools("multiCursorNoGlow", …, null, null, true))` —— 最后那个 `true` 就是 `runtimeSwitch`，内部推出 `inPreset = !runtimeSwitch = false` |
| State → 运行时怎么同步（不走 CONFIG） | `multiCursorNoGlow` / `targetFps` | `CaretAnimator.syncGlobalSettings()`：`multiCursorNoGlow = s.multiCursorNoGlow;`，catch 里退回默认值 |
| 切预设不影响它怎么守 | `VMultiNoGlow` | N-4：遍历 `ANIM_PRESETS` 调 `applyAnimPresetFields`，断言字段不变；N-5：断言 `AnimPreset` 无该访问器 |
| 驱动生产的 `SpringTrail` 量物理量 | `VPreviewRank.productionRanks` | 反射构造 `SpringTrail`，`setTarget` 两次 + `applyCharsMoved` + `update`，读 `corners[].getCurrentX()` |
| 斜度怎么量 | `tools/bench/BenchSlope` | 2×32 光标、右移一字符、`dt=0.008`、逐帧取左右边缘 X 差峰值 |

**关键确认（不是猜）**：`SpringTrail.assignRanks()` 排序后
`order[k]` = 「第 k 靠后」的角；`k=2/3` 是对齐度最大的两个角 = **前缘**。
纯水平右移时 align 为 `TL=-0.707 / TR=+0.707 / BR=+0.707 / BL=-0.707`，
稳定排序 + 索引 tiebreak 后 `order = [TL, BL, TR, BR]` → 排序后两名（即
`order[n-1] / order[n-2]`）= TR/BR = 右边缘 = 运动方向那一侧。
删除时镜像到 TL/BL。与 CLAUDE.md §六表格一致。
（最终实现用的是 `order[n-1]/order[n-2]`，不写死 2/3。）

### 1.2 外部搜索（第 2 轮）

- `gh search code "useHardSnap"` 命中上游实现：
  `yyyyolo7a79-sketch/neocursor-fix/extension/assets/neovide-cursor.js`
  ```js
  useHardSnap: true,
  leadingSnapFactor: 0.1,
  leadingSnapThreshold: 0.5,
  snapAnimationLength: 0,
  ...
  const alignment = jvNorm.x * this.rpNorm.x + jvNorm.y * this.rpNorm.y;
  const useSnap = cursorConfig.useHardSnap && alignment > cursorConfig.leadingSnapThreshold;
  ```
- `gh search repos "neovide cursor animation"` 只命中一个无关的展示页。
- **结论**：上游是 **2 角点模型**（每边一个点），`alignment > 0.5` 只命中运动前方那一个点，
  另一侧自然拖出去 → 半边。我们是 **4 角点**，逐角判对齐度时纯轴向移动会同时命中
  同侧两个角（0.9.83 试过，见 UPSTREAM §3.1），所以**不能照搬阈值吸附**。
  本方案改走「rank 前两名 = 前缘」的判据 —— 不依赖 alignment 连续值，
  只依赖已经稳定下来的 rank 排序，因此不受 §3.1 那条「对齐度离散、没有中间档」的限制。

### 1.3 上游手感报告的必读结论

`docs/design/UPSTREAM-VSCODE-RESEARCH.md`：
- `:54-61` 上游 `useHardSnap` 原始实现（已抄录在 1.2）；
- `:85` **B 版在 2px 光标上每次击键都是「大跳」**（短跳阈值 `2.001`，`jv.x = 9.2/2 = 4.6` 判成跨行 →
  前缘 0ms、后缘 100ms）。**这是本功能的最大风险**。
- `:105-143` §3.1「为什么做不出来」= 0.9.83 的旧结论；
  该结论已被 CLAUDE.md §六（0.9.87 实测）推翻：**锁两个前缘角，前缘斜度精确 0.00**。

**必须回答的问题：我们锁两个角会不会变成 B 版那种大跳？**

判据与回答：
- B 版是**整套时长切换**（短跳判成跨行 → 前缘 0、后缘 100ms），四角全部改档 → 每次击键都是大跳。
- 我们**只覆盖 rank 2/3 两个角**，rank 0/1（后缘）仍走原来的短跳梯度（`shortAnimationLength × trailFactors`），
  `trailSize` / 跨行分支完全不动。所以后缘的拖尾长度不变，只有前缘被钉死。
- 理论上不会出现「整条线被拉成长对角线」，**但必须实机确认**（AC 之外的用户验收项）。
  若实机像「大跳」而不是「顺滑半边」，停下来报告，不调参数硬凑（任务书硬边界）。

---

## 二、六层落点（设备级 = 7 个语义位置）

| 层 | 文件 | 改什么 | 漏了的症状 |
|---|---|---|---|
| ① 字段声明 | `NeovideCursorSettings.State` | `public boolean leadingSnap = false;` | 编译不过（最好发现） |
| ② 字段表 | `SettingsField.build()` | `out.add(bools("leadingSnap", …, null, null, true));` | apply 不拷贝 → 一应用就回滚 |
| ③ copyFrom | `NeovideCursorSettings.copyFrom()` | 一行赋值 | 面板回显不出 |
| ④ loadState | `NeovideCursorSettings.loadState()` | 一行赋值（布尔无需 clamp） | 重启即失效 |
| ⑤ 消费者 | `CaretAnimator` + `SpringTrail` + `TrailCorner` | 见 §三 | **能存能读，界面毫无反应** |
| ⑥ UI 控件 | `NeovideCursorConfigurable.buildInlineGroup()` | 复选框（斜度之后）+ `TAG_GLOBAL` | 用户没有入口 |

**明确不碰**（设备级省掉的四处）：`CursorPresets.AnimPreset`、三个 `ANIM_PRESETS`、
`applyAnimPresetFields()`、`matchAnimPreset()`。

---

## 三、开关怎么传到 SpringTrail（不动 D0）

`core/NeovideConfig.java` 是 D0 红线，不能加字段。走 `CaretAnimator` 的 State 通道：

```
TrailCorner.setLeadingSnap()        → springX/springY.setAnimationLength(0.0)
SpringTrail.leadingSnap（包私有）+ setLeadingSnap(boolean)
  assignRanks() 末尾：if (leadingSnap) { corners[order[2]].setLeadingSnap(); corners[order[3]].setLeadingSnap(); }
CaretAnimator.leadingSnap
  syncGlobalSettings()：leadingSnap = s.leadingSnap;  catch 里 = false
  syncGlobalSettings() 末尾：遍历 trails.values() 逐个 setLeadingSnap(leadingSnap)
  refreshTarget 新建 trail 后：trail.setLeadingSnap(leadingSnap)
  生效路径：由 onModeChanged() 既有的 snapToTarget + forceResync + wakeUp 保证
  （最终实现**不在** syncGlobalSettings 里重复做，避免死代码）
```

**必须在 `jump()` 之后调用**：`jump()` 内部 `setAnimationLength(animationLength)`，
先调会被覆盖。`assignRanks()` 的 `corners[order[k]].jump(...)` 循环之后正好。

**硬吸附为什么成立**：`DampedSpring.update(dt)` 首句判 `animationLength <= dt`
（0.0 <= 0.008）→ `position = 0` → `currentX = destX`，角点当帧到位。
这不是「时长很短」，是精确归零 —— AC-001 因此可以断言 `== 0.00`
（若实测只是「接近 0」，说明走的不是短路，属于任务书硬边界 4，必须停下报告）。

---

## 四、探针设计（TDD 的 RED）

新探针 `tools/settings-probe/VLeadingSnap.java`，全部调用生产代码（陷阱 3）：

| 断言 | 内容 | 对应 AC |
|---|---|---|
| L-1 | 开启后：右边缘（TR/BR）X 差峰值 == 0.00，左边缘（TL/BL）> 0 | AC-001 |
| L-1b | 开启不改变后缘斜度（与关闭时相同，容差 0.05） | AC-002 |
| L-2 | 关闭时斜度与基线一致（左 2.11 / 右 4.06，容差 0.05） | AC-002 |
| L-3 / L-3a | 开启 → 关闭后右边缘恢复；可逆场景前半段也必须精确 0 | AC-002 |
| L-4a~d | 字段在 FIELDS、`syncsToConfig=false`、`inPreset=false`、`runtimeSwitch=true` | AC-004 |
| L-5 | `copyFrom` 带上 | 六层③ |
| L-6 | `loadState` 带上（同值读回） | 六层④ |
| L-7 | 默认值 false | AC-003 |
| L-8 | 切任何动画预设都不改它 | AC-004 |
| L-9 | `AnimPreset` 无该访问器（确认设备级） | AC-004 |
| L-10 / L-11 | 剥注释后的 `syncGlobalSettings` 方法体：读 State + 遍历 trails 推送 | AC-005/006 |
| L-12 | 左移（退格）镜像：左边缘精确 0、右边缘 > 0 | AC-001 |
| L-13a/b | 出厂默认（slope=0）：关闭时前缘首帧有位移；开启时前缘精确 0、后缘滞后 | AC-001 |
| L-14 | 预览窗把前缘两角（TR/BR）钉死、后缘两角不钉（断言「身份」而非个数） | 陷阱 6 |
| L-15 | `pending` → `refreshPreviews` → `inlinePreview` 收到开关（不点应用也生效） | 用户要求 |
| L-16 | 预览与生产「被钉住的角集合」必须一致（两边各读自己的行为结果） | 陷阱 6 |

变异方案（AC-010）：删掉 `assignRanks()` 里对 `order[n-1]/order[n-2]` 的
`setLeadingSnap()` 调用 → L-1 必须红。改完删 `build/v2-probe/classes` 全量重编译。

---

## 五、契约变更（非缺陷）

| 探针 | 原值 | 新值 |
|---|---|---|
| `VAddSetting` | `checks == 9` | 10 |
| `VerifyPanel` | `checks == 9`（U8）、`boxes == 9`（U19） | 10 / 10 |
| `VPreset` | State public 实例字段数 33 | 34（计划稿写的 30→31 已过时：颜色预设三件套早把基线推到 33） |
| `VDefaultFreeze` | GOLDEN 33 项 | 加 `leadingSnap = "false"`（34 项） |
| `VLoadState` | `runtimeSwitch` 计数 9 | 10 |
| `VSwitchKeysGap` | `EXPECTED_RUNTIME` 9 项 | 加 `leadingSnap`（10 项） |
| `VTabPages` | 标签3 `{6,0,0,0}` | `{6,1,0,0}` |
| `VUi` | 滑块/复选框表 | 复选框不进 `KEYS`/`SPEC`（那是滑块表）；只改 `VTabPages` |

`VUi.KEYS` 只收滑块（`VUi.collect` 后按 sliders 取），复选框不在其中 —— 先读表结构确认再动。

> 本节的数字已按**最终实现**校订（审查 L-6）。计划稿最初的 30→31 是当时未考虑颜色预设三件套的估算。

---

## 六、风险

1. **大跳风险**（B 版前车之鉴）：本方案只改前缘两角，后缘梯度不动 → 理论上不会；
   仍列为实机验收项。
2. **连打稳定性**：前缘每帧钉死，rank 若在连打中抖动，形状可能「抽一下」。
   rank 抖动是已知缺口（UPSTREAM 第三节第 4 条），本功能不修它，只观察是否被放大。
3. **多光标**：每个 `SpringTrail` 各自保存标志，`CaretAnimator` 统一推送 —— 与 `multiCursorNoGlow` 同构。
