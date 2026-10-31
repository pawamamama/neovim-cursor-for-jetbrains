# 第 3 批审查收尾 · 第一批（5 条 MEDIUM）—— 计划与证据

> 起点：`main` @ `ce39138`（已推 fork，同步 0 0）。
> 依据：`docs/HANDOFF-BATCH3-AUDIT-2026-10-31.md` §三 + `docs/CODE-AUDIT-2026-10-30.md` §9.3。
> 上游清单：`docs/HANDOFF-NEXT-2026-10-31.md` §3.1（第 6 步第 1 批）。
> 本轮只做 5 条：**P-M-11 / P-M-8 / P-M-5 / P-M-6 / P-M-3**。其余（P-M-1 / P-M-4 / P-M-7 / P-M-9、16 条 LOW）不在本轮。

---

## 零、开工前先校正三条前提（Research 阶段的实测结论）

这三条与任务书的措辞不一致。按纪律五「诚实原则」先写清楚，再按实测结果施工。

### 0.1 工作区不是「只有两个未跟踪文件」，是三个

```
$ git status --porcelain
?? build/night-run-report.md
?? docs/HANDOFF-NEXT-2026-10-31.md      ← 多出来的这一个
?? plans/batch3-review-prompt.md
```

`docs/HANDOFF-NEXT-2026-10-31.md` 是另一个会话留下的进度快照（未跟踪、未改动任何跟踪文件）。
它不构成阻塞，本轮不提交它，也不删它。

### 0.2「第 1 批的 clamp 区间表」在代码里**不存在**

任务书说「复用第 1 批已建立的校验入口（SettingsField 的 isValidHexColor / clamp 区间表）」。
实测（`grep -n "min\|max\|range\|clamp" src/.../settings/SettingsField.java` → **零命中**）：

| 说法 | 实测 |
|---|---|
| `SettingsField.isValidHexColor` | ✅ 存在，`public static`，可复用 |
| `SettingsField` 里的「clamp 区间表」 | ❌ **不存在** |
| 量程实际写在哪 | `NeovideCursorSettings.loadState()` 里逐字段的 `clamp(...)`（`private static`，不对外） |
| 第 1 批 H-1 的建议 | 「把合法区间收进字段表」—— **这一步至今没做** |

所以能复用的只有两样，本轮就用这两样，**不在面板里另造一套数值**：

1. `SettingsField.isValidHexColor(String)` —— P-M-6 直接调。
2. 面板自己的 `SPECS`（`SliderSpec{key,min,max,step,readTick,writeTick}`）——
   它已经是「每个数值字段的合法刻度区间 + 原始值↔刻度换算」的唯一现成表。

**为什么用 SPECS 不算「另写一套」**：它不是新表，是面板构建滑块时早就在用的那张表
（14 项，`specOf(key)` 按 key 查）。本轮只加一个「把 pending 里越界的刻度夹回去」的遍历，
不新增任何量程常数。

**而且它与 `loadState` 的量程逐项一致（已核对）**：

| 字段 | SPECS 刻度区间 | loadState clamp | 一致 |
|---|---|---|---|
| `cursorDisappearDelay` | 0–500 | 0–500 | ✅ |
| `cursorFadeOutDuration` | 0–1000 ms | 0–1.0 s | ✅ |
| `tailOpacity` / `glowOpacity` / `trailSize` / `typingSmoothness` | 0–100 % | 0–1.0 | ✅ |
| `glowLayers` | 1–12 | 1–12 | ✅ |
| `shadowBlurFactor` | 锐度 0–100 | 0.05–0.60 | ✅（`blurOfSharpness` 端点即 0.60/0.05） |
| `stretchRatioK` | 10–80（1.0–8.0×） | 1.0–8.0 | ✅ |
| `animationLength` | 5–35（0.05–0.35 s） | `ANIM_LENGTH_MIN/MAX` = 0.05–0.35 | ✅ |
| `shortAnimationLength` | 2–25（0.02–0.25 s） | `SHORT_LENGTH_MIN/MAX` = 0.02–0.25 | ✅ |
| `slopeTicks` | 0–100 | 0–100 | ✅ |
| `cursorWidthPx` / `cursorHeightRatio` | 1–5 / 30–100 % | 1–5 / 0.3–1.0 | ✅（但两者是 runtimeSwitch，预设还原不碰） |

> **留一条结构性守门**（见 §三 V-5）：`PRESET_FIELDS` 里每个非 runtimeSwitch 的
> FLOAT/INT 字段都必须在 `SPECS` 里有一项。将来加数值字段忘了配滑块，探针当场红 ——
> 否则新字段会绕过本轮这套校验，回到「越界值进 State」。

### 0.3 P-M-8「改成调用生产方法」在本轮白名单内做不到

`SpringTrail.assignRanks()` 是 `private`，且**直接把结果写进四角**（`corners[order[k]].jump(..., k)`），
没有任何可复用的纯函数入口。要「两处共用一份实现」必须动 `ui/SpringTrail.java`
（把算法抽成 `static int[] ranks(double[] alignment)`），审计报告 §9.3 的原话也是
「抽到 core，两处共用」。

但本轮白名单只允许改 `ui/settings/` 下两个文件，**`ui/SpringTrail.java` 与 `core/` 都在白名单外**。
按任务书第六节第 1 条，这属于「需要改白名单外的文件」。

任务书对 P-M-8 给了两条路：「调用生产方法」**或**【至少断言两者结果一致（二选一，说明理由）】。
第一条路被白名单堵死，所以**选第二条**，理由：

- 陷阱 3 的**验真方法**本来就是「变异测试」——改坏生产 → 探针必须红。
  一个「喂同一份对齐度、比对两边 rank」的探针，在生产排序被改动的那一刻就会红，
  这正是要守的东西（「以后改生产排序，预览不跟随」）。
- 抽公共函数的正确位置是 `core/`（角点数不该由 UI 决定），属于跨模块重构，
  与 P-M-7 的重构排期同族，不该混在「5 条 MEDIUM」里做。

**登记为后续**：`core/RankOrder.ranks(double[])` 抽取 + `SpringTrail` / `AnimationPreview` 共用，
建议与 P-M-7 的 B 簇一起做。

---

## 一、五条改法（逐条：根因 → 改法 → 为什么这么改）

### 1. P-M-11 —— `disposeUIResources()` 漏释放 3 个预览 + 5 个组件引用

**根因**：`disposeUIResources()` 逐字段置 null 了颜色面板 / 下拉 / 按钮，
但 `inlinePreview` / `crossPreview` / `glowPreview` / `previewConfig` /
`trailModeCombo` / `paintDiagCheck` / 两个恢复按钮 从未置 null。
而 `refreshPreviews()` 的**唯一门槛**是 `previewConfig == null` ——
previewConfig 不置 null，dispose 之后的排队事件仍会走进来，
对三棵已脱离组件树的预览各调一次 `onConfigChanged()` + `repaint()`。

**改法**：dispose 末尾补 **8 个 null**（顺序与字段声明同序，便于对照）：

```
inlinePreview / crossPreview / glowPreview      ← 3 个预览（各持一棵组件树）
previewConfig                                    ← refreshPreviews 的门槛
trailModeCombo                                   ← 非 DSL 绑定的下拉
paintDiagCheck                                   ← 非 DSL 绑定的复选框
animRestoreBtn / colorRestoreBtn                 ← 两个「恢复默认值」按钮
```

**为什么正好 8 个**：审计 §9.3 的 P-M-11 逐项点名了 8 个字段（3 预览 + previewConfig +
paintDiagCheck + trailModeCombo + 2 恢复按钮）。不是「凑数」，是同一条判据
「已脱离组件树/已 dispose 的对象不得被继续强引用」在 8 个字段上的落地。
置 null 之后 `refreshPreviews()` 在门槛处直接返回，dispose 后不再有写入路径。

**观感**：零变化（只在 dispose 之后生效，面板此时已经关闭）。

### 2. P-M-8 —— 预览的 rank 排序是生产算法的复制品

**根因**：`AnimationPreview.assignRanks(cx,cy)` 手写了一份「稳定插入排序 + 索引 tiebreak」，
与 `SpringTrail.assignRanks()` 分家。0.9.77 修生产时预览没跟上，已出过一次
「生产变斜、预览仍是矩形」。

**改法（本轮）**：不动算法，**加一条同源守门**：

1. `AnimationPreview` 里把排序抽成 `public static int[] ranksForAlignment(double[] al)`，
   `assignRanks(cx,cy)` 改为「算对齐度 → 调它」，**行为逐位不变**（纯提取，无逻辑改动）。
2. 新增探针 `VPreviewRank`：对 5 个移动场景（右/左/下一行/上一行/斜跳），
   - 生产侧：反射构造 `SpringTrail`，`setTarget` 两次 → `update(0)` 触发 `assignRanks()`，
     读私有的 `alignmentScratch`（对齐度）与四角 `animationLengthForProbe()`；
     rank 由「时长降序的名次」解出（rank0 最长，rank3 最短）；
   - 预览侧：把**同一份** `alignmentScratch` 喂给 `ranksForAlignment`；
   - 断言两边逐角相等。

**为什么不复制生产算法进探针**：探针只读生产输出的两个数字（对齐度、时长），
不做任何排序 —— 排序的唯一实现仍是被测的那两份。

**为什么用「时长降序」解 rank 而不是读 rank**：`SpringTrail` 不暴露 rank，
只把 rank 落成 `animationLength = shortAnimationLength × trailFactors[rank]`。
探针把 `config.trailFactors` 设成四个互不相等的值，于是「谁长谁是 rank0」是恒真的映射，
不需要探针自己算系数表（那才是复制逻辑）。

**顺带**：`trailFactors` 用互异值时四角时长必然四档，`VRankCollapse` 守的
「rank 互不相同」在预览侧也有同一条兜底断言。

### 3. P-M-5 —— 自定义动画预设的数值快照不经量程校验就进 State

**根因**：`SettingsField.decode` 的 FLOAT/INT 分支只拒 NaN/Inf 与解析失败，
**不 clamp**；clamp 只在下次启动的 `loadState` 里发生。
于是 `glowLayers=99` 这种手改值会：进 pending → 应用 → 落盘 → 同会话内
滑块显示 12、输入框显示 99（`syncAllControlsFromPending` 写的是 exact） →
重启后 `loadState` 悄悄改回 12 → 用户看到「值自己变了」。

**改法**：面板侧新增一次**归一化**（不是拒绝整条预设）：

```java
/** 把 pending 里越界的数值字段夹回 SPECS 的量程；返回被夹过的字段名。 */
private java.util.List<String> clampPresetNumerics(NeovideCursorSettings.State s)
```

在**两条**自定义预设还原路径上调用（`onAnimPresetPicked` 的自定义分支、
`restoreAnimPreset` 的自定义分支），并且：

- 只遍历 `SPECS`（14 项，含每项自己的 `readTick`/`writeTick` 换算），
- `tick < min || tick > max` → `writeTick.accept(s, clamp(tick, min, max))`，
- 每个被夹的字段打一行 `LOG.warn`，并累计到 `showUserNotice(..., error=false)`。

**为什么 clamp 而不是整条拒绝**：

- 条目名就叫「快照不 clamp」，loadState 的语义也是 clamp —— 两条入口对齐才是「同一条规则」；
- 整条拒绝会让一份「只有一个字段越界」的老预设**全部失效**，这是用户可感知的行为变化，
  超出「内部质量修复」的范围（AC-003）；
- clamp 之后「应用的值 == 重启后读回的值」，用户再也看不到「值自己变了」。

**为什么只遍历 SPECS 就够**：`PRESET_FIELDS` 里非 runtimeSwitch 的数值字段恰好是
SPECS 的 11 项（其余是 3 个颜色/开关 + 2 个全局项）。V-5 会把这个「包含关系」变成断言。

### 4. P-M-6 —— 颜色快照不校验 HEX

**根因**：`CursorPresets.parseColorSnapshot` 只判「两个键都在」，不校验格式；
而动画快照走 `SettingsField.decode` 的 HEX 分支，那里有 `isValidHexColor`。
同一类数据两条入口，一条设防一条不设防。

**改法**：面板侧在写入 `pending` **之前**调用
`SettingsField.isValidHexColor(hex[0]) && SettingsField.isValidHexColor(hex[1])`，
不合法 → 按「坏快照」处理（不写半套、给提示、下拉复位），**不新写校验规则**。

**注意颜色与动画的口径差异**：`isValidHexColor` 收 `#RRGGBB` 与 `#AARRGGBB`。
颜色预设快照里的值本来就由 `CursorPresets.colorSnapshot` 产出（7 位），
手改出 9 位也仍是合法颜色 —— 本轮不收紧，只挡「根本不是颜色」的串。

### 5. P-M-3 —— 颜色坏快照静默

**根因**：`hex == null` 分支只 `refreshPresetDisplay()` 就 `return`，
用户看到的是「点了没反应」；`restoreColorPreset()` 里同类分支连刷新都没有。

**改法**：两处都换成与动画侧**同构**的处置（这是 P-H-3 已经建立好的范式）：

```
showUserNotice(PRESET_BROKEN_TITLE, "预设「X」的内容已损坏…本次切换已取消…", false)
  + suppressDerive 包住 refreshPresetDisplay()（避免顺手派生出一个「X 副本」）
```

`restoreColorPreset()` 处额外加 `return`（原来 `hex == null` 时还会继续往下走 `syncAllControlsFromPending`）。

**三、四、五条合并成一个助手**：`boolean applyColorSnapshotTo(State s, Entry e, String verb)` ——
解析 → 校验 HEX → 写两个字段，任一步失败就返回 false，调用方负责提示 + 复位。
两条路径共用一份判据，不再各写一遍。

---

## 二、探针（每条都要「红 → 绿 → 变异红 → 恢复绿」）

新增 **2** 个探针文件：

| 探针 | 覆盖 | 主要断言 |
|---|---|---|
| `VBatch3Medium` | P-M-11 / P-M-5 / P-M-6 / P-M-3 | D-1 dispose 后 8 个字段全 null（含「dispose 前非 null」的对照）；N-1 越界数值被夹回并留日志、**且自愈（再选不重复提示）**；N-2 合法值一位不变；N-3 非法 HEX 不写进 State、**写入前 trim**；N-4 坏快照不写半套且有提示；N-5 SPECS 覆盖所有非 runtimeSwitch 数值字段（共 25 条） |
| `VPreviewRank` | P-M-8 | R-1..R-5 五个场景下预览 rank == 生产 rank；R-6 预览 rank 仍是 4 个互不相同的值 |

两条都登记进 `build-v-probes.ps1` 的 `$mains`（**不登记 = 从不运行**）。

---

## 三、变异测试计划（改坏生产 → 探针必须红 → 恢复）

| # | 变异 | 期望变红 |
|---|---|---|
| M-1 | 删掉 `disposeUIResources` 里的 `previewConfig = null;` | D-1 |
| M-2 | 删掉 `inlinePreview = null;` | D-1 |
| M-3 | `clampPresetNumerics` 改成直接 `return emptyList()` | N-1 |
| M-4 | clamp 的上下界对调（`min`/`max` 互换） | N-1 |
| M-5 | HEX 校验改成 `true` | N-3 |
| M-6 | 坏快照分支去掉 `showUserNotice` | N-4 |
| M-7 | 生产 `SpringTrail` 的插入排序 `>` 改成 `>=` | R-1..R-5 |
| M-8 | 预览 `ranksForAlignment` 的 `>` 改成 `>=` | R-1..R-5 |

> 每次变异后**必须删 `build\v2-probe\classes` 全量重编译**（增量编译会复用旧字节码）。

---

## 四、AC 对照

| AC | 怎么验 |
|---|---|
| AC-001 五条各自四段输出 | §五 记录表，逐条贴红/绿/变异红/恢复绿 |
| AC-002 既有断言零放宽 | 全量门禁 PASS 数不得下降；无任何既有探针文件被改（除 `build-v-probes.ps1` 追加两行） |
| AC-003 观感零变化 | 五条都在「预设还原 / dispose」路径上；正常参数一位不变（N-2 断言合法值逐位不变） |
| AC-004 D0 零 diff | `git diff --exit-code -- core/NeovideConfig.java` |
| AC-005 改动只在白名单内 | `git status --porcelain` 逐文件核对 |
| AC-006 全量门禁 ALL PASS | 探针数 106 → 108，三份文档同步（CLAUDE.md / START-HERE.md / docs/NEXT-STEPS.md） |

---

## 五、执行记录（四段输出）

### 5.1 基线（开工前实测，与任务书一致）

```
$ pwsh ... build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr   # exit 0
103 条目 / 1124 PASS / 2 FAIL（仅 VRegr2，informational）/ ALL PASS
```

### 5.2 逐条四段

| 条 | ① 红（改前） | ② 绿（改后） | ③ 变异红 | ④ 恢复绿 |
|---|---|---|---|---|
| P-M-11 | `D-1b` FAIL：8 个引用全 `非null` | `D-1a/b/c` PASS | M-1 删 `previewConfig=null` → `D-1b` FAIL（只 `previewConfig` 非 null）；M-2 删 `inlinePreview=null` → `D-1b` FAIL（只 `inlinePreview` 非 null） | PASS |
| P-M-8 | **无自然红**（两份实现今天恰好一致 —— 缺陷是潜伏的，见 §0.3） | `R-1..R-6` PASS | M-7 生产 `>`→`>=` → R-1/R-2/R-5 FAIL；M-8 预览 `>`→`>=` → R-1/R-2/R-5 FAIL | PASS |
| P-M-5 | `N-1a/b/c/d/e` FAIL（`pending=99/0.9/5.0`，无 warn、无提示） | 6 条 PASS（`N-1a..c` 与 `loadState` 逐项相等；`N-1f` 自愈） | M-3 clamp 直接返回空 → N-1a..e 5 条 FAIL；M-4 夹取方向对调 → N-1a/b/c FAIL（`pending=1/0.05/0.0`）；**M-9 去掉自愈 → N-1f FAIL**（`第一次=1 第二次=2`） | PASS |
| P-M-6 | `N-3a/b/c` FAIL（`tailColor=not-a-color` 直接进 pending） | 5 条 PASS（含 `N-3e` trim） | M-5 关掉 HEX 校验 → N-3a/b/c FAIL | PASS |
| P-M-3 | `N-4b` FAIL（无提示） | 5 条 PASS | M-6 去掉 `showUserNotice` → N-3c + N-4b FAIL | PASS |

> **P-M-8 为什么没有自然红**：缺陷不是「现在两边不一致」，而是「将来改一边另一边不跟随」。
> 今天两份实现的排序完全相同，所以任何行为断言在改前都只能绿。它的红由变异制造 ——
> 这正是陷阱 3 的验真方式（改坏生产 → 守门必须红），M-7 / M-8 两条都验证了。

### 5.3 变异清单（9 条，全部按预期变红）

```
M-1 删 previewConfig = null                        → D-1b FAIL
M-2 删 inlinePreview = null                        → D-1b FAIL
M-3 clampPresetNumerics 直接返回空列表             → N-1a..N-1e FAIL（5 条）
M-4 clamp 的夹取方向对调                           → N-1a/b/c FAIL（3 条）
M-5 applyColorSnapshot 关掉 isValidHexColor 判断   → N-3a/b/c FAIL（3 条）
M-6 颜色坏快照分支去掉 showUserNotice              → N-3c + N-4b FAIL
M-7 生产 SpringTrail 排序 `>` → `>=`               → R-1/R-2/R-5 FAIL
M-8 预览 ranksForAlignment 排序 `>` → `>=`         → R-1/R-2/R-5 FAIL
M-9 去掉夹回后的「快照自愈回写」                    → N-1f FAIL（第二次仍弹提示）
撤改动后：VBATCH3-MEDIUM 25/0、V-PREVIEW-RANK 6/0 全绿；
SpringTrail 零 diff（git diff --stat 为空）。
```

> M-3 / M-4 / M-5 / M-6 / M-9 在**审查修改之后重跑过一遍**（改的是同一段代码），
> 结论与上表一致；M-1 / M-2 / M-7 / M-8 对应的代码在审查修改中没有变动。

> M-7 的变异改的是白名单外的 `ui/SpringTrail.java` —— 那是任务书要求的「把生产代码改坏」，
> **撤改动后 `git diff --stat` 为空**，未进入交付 diff。

### 5.4 门禁（收工）

```
$ pwsh ... build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr   # exit 0
105 条目 / 1155 PASS / 2 FAIL（仅 VRegr2）/ ALL PASS
```

- 条目 103 → 105（新增两个探针），PASS 1124 → 1155（+31：`VBatch3Medium` 25 + `VPreviewRank` 6）。
- **没有任何既有断言的通过数下降**（AC-002）。唯一非零退出的仍是 `VRegr2`（informational，2 条已知红）。
- `VDocCheck` 4/4 通过 —— 三份文档的探针数（108）与磁盘一致。

---

## 六、白名单核对

```
M  CLAUDE.md                                                      ← 白名单「文档」
M  START-HERE.md                                                  ← 白名单「文档」
M  docs/NEXT-STEPS.md                                             ← 白名单「文档」
M  src/.../ui/settings/AnimationPreview.java                      ← 白名单「生产」
M  src/.../ui/settings/NeovideCursorConfigurable.java             ← 白名单「生产」
M  tools/settings-probe/build-v-probes.ps1                        ← 白名单「探针」
?? tools/settings-probe/VBatch3Medium.java                        ← 白名单「探针」
?? tools/settings-probe/VPreviewRank.java                         ← 白名单「探针」
?? plans/batch3-fix2.md                                           ← 白名单「计划与文档」
```

白名单之外**零字节改动**：`core/NeovideConfig.java` 零 diff（D0），
`ui/SpringTrail.java` 零 diff（变异已撤销，`git diff --stat` 为空），
`docs/BUG-LOG.md` 未触碰，`settings/` 层未改（只读复用 `isValidHexColor` / `Field.readState`）。

---

## 七、已知未修（本轮白名单外，必须登记，不许说「已全部修完」）

两个独立审查代理在收工前指出：**P-M-5 / P-M-6 / P-M-3 只修了设置面板这一条入口**，
Tools 菜单那条入口在 `ui/CursorPresetApplier.java`，不在本轮白名单内，**原样保留同一缺陷**。

| # | 位置 | 现状 | 后果 |
|---|---|---|---|
| U-1 | `ui/CursorPresetApplier.applyCustomAnimPreset()`（约 116-140） | `restoreSkipping` 后直接写活 State 并 `persistQuietly()`，**不 clamp** | 菜单选中 `glowLayers=99` 的预设 → 落盘 99 → 重启被 `loadState` 夹回 12 → 「值自己变了」仍是原症状 |
| U-2 | `ui/CursorPresetApplier.applyCustomColorPreset()`（约 219-227） | 只判 `hex == null`，**不校验 HEX** | 菜单选中坏颜色预设 → 写进 State → `NeovideColors.parse` 失败 → 光标变黑 |
| U-3 | `NeovideCursorSettings.sanitizeColor()`（约 806-822） | 只收 7 位 | 9 位 `#AARRGGBB` 应用后生效、**重启被打回默认色**（第 1 批 M-1「颜色合法性三套规则」未修） |
| U-4 | `NeovideCursorSettings.loadState()`（`slopeTicks == 82` 迁移，约 225-231） | 82 → 0 | 含 82 的旧快照仍是「应用 82、重启后 0」。历史迁移，不是本轮引入 |
| U-5 | `AnimationPreview` 的 `rel` 与 `SpringTrail.CORNER_RELATIVE` | 两张角点顺序表 | `VPreviewRank` 只比对排序，**发现不了**顺序表被改。要收掉必须把算法抽到 core 共用 |
| U-6 | `shadowBlurFactor` 的锐度换算 | `blurOfSharpness(100) = 0.049999997f`，比 `loadState` 的 `0.05f` 低 1 ulp | 极端越界值夹回后，重启会再动 3.7e-9（观感无差别）。要逐位同源必须让端点直接取 `NeovideCursorSettings` 的常量 |

修 U-1 / U-2 的正确位置是「把校验下沉到 `SettingsField.restoreSkipping` 或一个共用入口」，
属于改 `settings/` 层，与本轮白名单冲突，**下一轮优先**。

---

## 八、独立审查记录（两个代理）

| 代理 | 结论 | 找到的真实问题 | 本轮处置 |
|---|---|---|---|
| `code-reviewer` | APPROVE（0 CRITICAL / 0 HIGH / 3 MEDIUM / 5 LOW） | M-1 夹回后不回写快照 → 每次选中重复弹框（**已实测**）；M-2/M-3 只修面板入口；L-1 文档行数差 4；L-2 提示里有英文 key；L-3 D-1c 名不副实 | **M-1 已修**（自愈回写 + `N-1f` 守门 + `M-9` 变异验证）；M-2/M-3 登记进 §七；L-1 重测重写；L-2 改成不含 key 的中文文案；L-3 改成直接调 `refreshPreviews` |
| `java-reviewer` | 无 CRITICAL/HIGH；生命周期/状态同步/浮点边界/反射探针四项专项**均无缺陷** | 缺陷 2 `isValidHexColor` 会 trim 但写入未 trim → 带空格的合法串会变黑（**真 bug**）；缺陷 3 9 位 RGBA 与 `sanitizeColor` 不同源；缺陷 4 `paintDiagCheck` 的监听器解引用字段（dispose 后潜在 NPE）；缺陷 5 clamp 未跳过 runtimeSwitch；缺陷 7 `VPreviewRank` 注释写错「默认严格递减」；缺陷 8 D-1c 太弱 | **缺陷 2 已修**（trim 后写入 + `N-3e` 守门）；缺陷 3 登记 §七 U-3，`N-3d` 加注释说明不背书；缺陷 4 已修（改用 `e.getSource()`）；缺陷 5 已修（`runtimeSwitch` 跳过）；缺陷 7 注释改写并加「守不到的地方」；缺陷 8 已修 |

**两个代理都跑了一遍门禁，且都遇到过一次并发假红**（`java-reviewer` 明确记录：
5 条 `NoClassDefFoundError` 来自与本会话并发的另一份门禁 —— 纪律四-G-2 的已知现象）。
最终门禁以本会话最后一次单份运行为准。
