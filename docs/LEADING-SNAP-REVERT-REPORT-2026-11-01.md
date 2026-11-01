# 半边拖尾（leadingSnap）回退 —— 交付报告

日期：2026-11-01 ｜ 提交：`b5e34d7`（分支 `main`，基于 `c2ae1e9`）
性质：**有选择的回退，不是 `git revert`**。实现逻辑删掉，开关控件与 State 字段留下。
规划稿：[plans/leading-snap-revert.md](../plans/leading-snap-revert.md) ｜ 决策依据：[LEADING-SNAP-DECISION-2026-11-01.md](LEADING-SNAP-DECISION-2026-11-01.md)、[LEADING-SNAP-ISSUES-2026-11-01.md](LEADING-SNAP-ISSUES-2026-11-01.md)

---

## 〇、一句话结论

「前缘贴合（半边拖尾）」的三种实现模型实机验收全部不可行，代码逻辑已整体删除；
设置页的复选框还在原位、还能勾、状态还能存，但**勾了不改变任何观感**，文案已明写「待实现」。
生产代码 + 探针净减 **523 行**，全量门禁 `ALL PASS`，D0 与两份禁改文件零 diff。

---

## 一、为什么是「有选择的回退」而不是 `git revert`

功能由两个提交叠加而成：

| 提交 | 内容 | 结果 |
|---|---|---|
| `30fe004` | feat：新增设备级开关，前缘 0ms 吸附模型 | 实机不可行 |
| `c2ae1e9` | fix：改成「四角同长 + 绘制层固定尾巴」 | 实机仍不可行 |

`git revert` 会把开关本身一起撤掉 —— 但用户要的是**保留那个复选框**（外观与位置不变），
只把背后的行为掏空。所以采用定向删除：逐符号移除消费点，保留设置层五层接线。

这样做的代价是留下一个「能勾但无效」的开关，因此文案必须让用户一眼看出它没生效，
否则会被当成 bug 反复上报。这是本次改动里唯一需要靠文字兜底的地方。

---

## 二、删除了什么（逐符号）

生产代码 **+42 / −304**，探针 **+121 / −382**，合计 **+163 / −686，净 −523 行**。

| 文件 | +/− | 删除内容 |
|---|---|---|
| `core/TrailCorner.java` | +14 / −35 | `HALF_TRAIL_CAP_WIDTHS` 常量、`setAnimationLengthForTrail()` 方法；类头与 `jump()` 的 javadoc 改为「试过三版、已回退」口径 |
| `ui/SpringTrail.java` | +1 / −53 | `leadingSnap` 字段与 `setLeadingSnap()`；`dirX`/`dirY` 字段及 `setTarget()` 里的方向记录；`assignRanks()` 末尾的四角时长覆盖块 |
| `ui/CaretTrailPanel.java` | +1 / −84 | `halfTrail`、`quadDirs`、`setHalfTrail()`、`addQuad` 五参重载、`fillHalfTrail()` 整体；填充分支回到单一 `g2.fill(path)` |
| `ui/CaretAnimator.java` | +1 / −39 | `leadingSnap` 字段；`refreshTarget` / `tickStaticCaret` / `tickSpring` 三处 `setHalfTrail`/`setLeadingSnap` 调用；`addQuad` 改回三参；`syncGlobalSettings` 里的读取、catch 重置、推送循环及其注释 |
| `ui/settings/AnimationPreview.java` | +6 / −72 | `leadingSnap`、`setLeadingSnap()`、`leadingSnapForProbe()`；`dirX`/`dirY`/`lastPushX`/`lastPushY`/`pushSeen` 及 `pushTarget()` 里的方向记录与覆盖块 |
| `ui/settings/NeovideCursorConfigurable.java` | +15 / −15 | `pushStyleTo` 里的 `preview.setLeadingSnap(...)` 一行；复选框行注释改写为回退口径（控件本身一行未动） |
| `tools/settings-probe/VLeadingSnap.java` | +121 / −382 | 按新契约整体重写（见第五节） |

**特意保留的一处**：`CaretTrailPanel` 里的 `dirX`/`dirY` 两个数组和 `computeOffsetDirections()`。
名字撞车但功能无关 —— 它们算的是辉光层的偏移方向，不是半边拖尾。删除会连带破坏辉光。

**收尾 grep 证据**（HEAD 上实测）：

```
$ grep -rn "halfTrail|setHalfTrail|HALF_TRAIL|fillHalfTrail|quadDirs|setAnimationLengthForTrail|setLeadingSnap"
  src/main/java --include=*.java
（零命中）
```

`leadingSnap` 本身仍有 7 处命中，全部属于保留层：State 字段一处、`copyFrom`、`loadState`、
`SettingsField` 注册与 setter、复选框绑定两处、`AnimationPreview` 里一句历史注释。符合预期。

---

## 三、保留了什么（六层留五层）

这个项目的设置项按「六层」接线，本次是**刻意少一层**：

| 层 | 状态 | 位置 |
|---|---|---|
| ① State 字段 | ✅ 留（默认 `false`） | `NeovideCursorSettings.java:910` |
| ② `SettingsField.build()` 注册项 | ✅ 留（第 28 项，`runtimeSwitch=true`） | `SettingsField.java:860` |
| ③ `copyFrom()` | ✅ 留 | `NeovideCursorSettings.java:237` |
| ④ `loadState()` | ✅ 留 | `NeovideCursorSettings.java:1186` |
| ⑤ 设置页复选框 | ✅ 留（位置、绑定、置灰规则不变） | `NeovideCursorConfigurable.java:1477` |
| ⑥ 行为消费点 | ❌ **删** | —— 回退后不存在 |

第 ⑥ 层的消失就是这个功能「失效」的本体。前五层保证：勾得上、存得住、重启不丢、
下次真要做实现时接线是现成的。

**归属不变**：设备级开关（`syncsToConfig=false`、`inPreset=false`、`runtimeSwitch=true`），
不跟随任何动画预设、不进自定义预设快照、不参与预设匹配。

---

## 四、用户看到的最终文案

```
前缘贴合（半边拖尾 · 全局）  ☐
[全局] 待实现：此功能尚未实现，勾选不会改变观感。
       开关状态会被保存，供实现后使用。
       不随动画预设变化：切任何预设都不改本项。
       残影模式下置灰。
```

三条约束都满足：①第一句直接说「勾了没效果」；②没有承诺任何时间点；③没有「即将推出」之类的暗示。
位置仍在「斜度」之后、「多行缎带」之前，未移动。

---

## 五、探针契约变更（22 条 → 12 条）

旧探针断言的是「开关打开后行为变成 X」，功能删掉后这些断言的对象不存在了。
这是**契约变更**，不是断言过期，所以每一条都写清去向，没有静默删除。

### 新契约（12 条，全部通过）

| 断言 | 内容 |
|---|---|
| L-4a / L-4b / L-4c / L-4d | 字段已注册；`syncsToConfig=false`；`inPreset=false`；`runtimeSwitch=true` |
| L-7 | 默认值 `false`（全新安装与老配置升级都是关） |
| L-5 | `copyFrom` 会带上该字段 |
| L-6 | 写 `true` → `loadState` → 读回 `true`（持久化闭环） |
| L-8 | 切任何动画预设都不改它 |
| L-9 | `AnimPreset` 不含该字段 |
| L-20 | `PRESET_FIELDS` 不含该字段 |
| **L-21** | **走真实 UI 链路，开关 true / false 两种状态同一场景，四角动画时长逐位相同** |
| **L-22** | **五个行为层源文件剥掉注释后零出现**（防止功能被悄悄复活） |

L-21 是本次最关键的一条，它就是验收标准 AC-002 的机器化表达。实测两侧输出：
`[0.09460000106096267, 0.03519999902248383, 0.03519999902248383, 0.09460000106096267]`，
完全一致。

L-22 是结构性守门：光靠 L-21 不够 —— 如果有人加回一个只在极端分支生效的读取点，
逐位对比可能碰巧不红。L-22 直接断言源码零出现，把「复活」堵在运行期之前。

### 旧断言去向（13 行覆盖 22 条）

| 旧断言 | 去向 |
|---|---|
| L-1 / L-1b / L-12 / L-13a / L-13b | 删。断言的是「开启后行为变 X」，功能已整体移除；行为由 L-21 取代 |
| L-2 / L-3 / L-3a | 删。开关不再影响行为，不存在「关闭态基线」与「可逆性」 |
| L-14 | 删。由 L-21 取代（true/false 预览结果逐位相同） |
| L-16 | 删。两侧都回到原版梯度模型，一致性由 `VPreviewRank` 等既有探针覆盖 |
| L-15 | 改造进 L-21（L-21 走的就是这条 pending → 预览链路） |
| L-10 / L-11 | 删。`syncGlobalSettings` 不再读也不再推；由 L-22 反向守门 |

其余探针（`VDefaultFreeze` 的 GOLDEN、`VerifyPanel` U8/U19、`VAddSetting`、`VLoadState`、
`VPreset`、`VSwitchKeysGap`、`VTabPages`）只依赖「开关存在」，壳子保留后全部不受影响，未改动。

---

## 六、验证证据

| 项目 | 结果 |
|---|---|
| 全量探针门禁 `build-v-probes.ps1` | **`V-PROBES RESULT: ALL PASS`**（112 个 `V*.java`） |
| `VLeadingSnap` | 12 / 12 通过 |
| `VDocCheck` | 4 / 4 通过（D-0/D-1/D-2/D-3，文档不自相矛盾、不写死 hash） |
| `VRegr2` | 13 通过 / 2 informational（R-5、R-6，均关于 `typingSmoothness`）—— **既有**功能缺口，不是本次引入，按项目惯例豁免 |
| D0 门禁 `core/NeovideConfig.java` | **零 diff**（`git diff --exit-code c2ae1e9 3f8f9c0` 通过） |
| 禁改文件 `CursorPresets.java` / `CursorPresetApplier.java` | **零 diff** |
| code-reviewer | 0 条发现 |
| java-reviewer | 1 条 LOW（`lastRanks` 注释仍写「保留给探针与方向判断」），已修正并复跑绿 |

**RED → GREEN 证据链**：删除前先按新契约改探针，跑出 `PASS 10 / FAIL 2`
（L-21 显示 true 侧四角全 0.11 无梯度、false 侧 0.0946/0.0352 有梯度；L-22 列出全部消费点）。
删完代码后 12/12 通过。

### 顺带查清的一件事：`VRegr2` 会抛 NPE（既有问题，非本次引入）

单独运行 `VRegr2` 时，在报完 R-5 / R-6 两条失败之后，会继续崩溃：

```
java.lang.NullPointerException: Cannot invoke
  "javax.swing.JCheckBox.setSelected(boolean)" because "<local3>" is null
    at VRegr2.deriveOnSmoothChange(VRegr2.java:338)
```

第 338 行是 `ui.checkBoxByLabel("启用辉光")` 取到 `null`。因为怀疑是本次删控件删出来的，
我把仓库切到**回退前的 `c2ae1e9`** 单独跑了一遍同一个探针 —— 报错行号、两条失败、NPE 全部一致。
**结论：这是既有问题，与本次回退无关。**

但要提醒一点：门禁的汇总行对 `VRegr2` 只读取 `PASS=` / `FAIL=` 两个数字（13 / 2），
进程随后的崩溃不会被计入，所以 `V-PROBES RESULT: ALL PASS` 对它是「免疫」的。
这条盲区值得单独排期修，本次不动。

---

## 七、白名单外的一处改动（主动披露）

任务书给出的生产白名单是六个文件，实际改了**七个**：多了
`settings/NeovideCursorSettings.java`（+4 / −6）。

原因：该文件里 `leadingSnap` 字段的 javadoc 原文写着「见 `TrailCorner.setLeadingSnap()`」，
而 `setLeadingSnap` 已被删除 —— 不改就留下一个指向不存在方法的引用，零残留验收也就过不了。
改动内容仅为这段 javadoc 改成「已回退、当前无消费者」口径，**没有动任何代码**。

除此之外没有越界：另外两份禁改文件、`NeovideConfig`、`CursorPresets`、`CursorPresetApplier`
全部零 diff。

---

## 八、现状与需要决策的事

**现在的事实**：

- `main` 上开关还在、能勾、能存，勾了没有任何效果，文案已写明。
- 三版实现模型全部实机否决，短期内不建议在原方向继续试第四版 ——
  问题不在参数调法，而在「前半边刚性 + 四角弹簧」这个组合本身在 Neovide 风格下不成立。
- 功能的历史实现完整保留在两个提交里，将来若要重启，`git show 30fe004` / `git show c2ae1e9`
  可以拿回全部代码，本次的 diff 只是删除，不是覆写。

**留给下一个会话的决策点**：

1. 这个开关是**长期保留占位**，还是在下个版本前**连复选框一起删掉**？
   保留的代价是设置页多一个永远无效的项；删掉则用户下次想要时得从零重做。
2. 是否要向用户展示「方案已试尽」的结论（`LEADING-SNAP-ISSUES-2026-11-01.md` 里有三版的实机数据），
   还是把文档留在仓库里备查即可。
3. `AnimationPreview.lastRanks` 字段回退后已无读取者，属于独立清理项，本次未动 —— 是否排入下一批。
4. `VRegr2` 崩溃发生在门禁统计之外（详见第六节），等于这颗探针后半段从来没被真正跑完过 ——
   是否优先修它的 NPE 与门禁的判绿口径。
