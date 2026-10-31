# 第 3 批审查修复计划（2026-10-31）

> 依据：`docs/HANDOFF-BATCH3-AUDIT-2026-10-31.md` + `docs/CODE-AUDIT-2026-10-30.md` §九
> 分支：`p11-panel-refactor`（HEAD `b5d2270`，= `a1efc13` + 一条 docs 提交）
> 本轮只改白名单内的文件；`core/NeovideConfig.java`（D0）零 diff；
> `ui/settings/AnimationPreview.java` 零 diff；`docs/BUG-LOG.md` 零 diff。

---

## 零、门禁基线（本轮开工前实测）

```
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr -Quiet
→ 条目 103 / PASS 1103 / FAIL 2（仅 VRegr2，informational）/ V-PROBES RESULT: ALL PASS / exit 0
日志：build/gate-baseline.log
D0：core/NeovideConfig.java 零 diff（本轮开工前）
```

> 交接文档写「102 条目」，实测 103（`VAuditBatch2Round5` 在报告写完之后才登记进
> `build-v-probes.ps1` 的 `$mains`）。**以实测 103 为准**，这是「文档数字会漂」的又一例。

---

## 一、四条修复的根因 → 改法

### P-C-1（CRITICAL）落盘失败被吞掉

- **根因**：`NeovideCursorConfigurable.apply()` 调 `svc.persistQuietly()` 后**丢弃返回值**。
  `persistQuietly()` 的三态（`CONFIRMED / DEFERRED / FAILED`）在产生侧有完整语义
  （只有 `CONFIRMED` 才能删旧键），消费侧却当它不存在。磁盘只读 / 序列化自检失败 /
  `saveSettings()` 抛异常时，面板照常关闭，**零提示**，重启即回滚。
- **改法**（用户在 2026-10-31 选 A：弹错误框、不阻止关闭）：
  1. 接住 `PersistResult`；
  2. `FAILED` 时**不执行** `session.reloadFrom(target)` —— baseline 不前移，
     下一次 apply 仍会按「pending vs baseline」把这些字段判脏并**重试落盘**；
  3. 给用户可见信号：错误框文案写「已应用…但未能写入磁盘…重启后会恢复为上一次成功保存的值」。
     **不写「已保存」** —— 因为 `CONFIRMED` 也不等于磁盘已写入（`NeovideCursorSettings:688-694` 自述）。
  4. 提示同时记进 `lastUserNotice`（探针据此断言「确实给用户看了」；headless 下弹框不可能，
     记录本身就是那次「给用户看」的等价证据 —— 与项目既有 `*ForProbe()` 的做法一致）。
- **不改** `CONFIRMED/DEFERRED` 的语义，不改 `persistQuietly()` 本身。
- **同类调用点登记不修**：`CursorPresetApplier:73/138/200/228`、`NeovideCaretManager:348`
  （不在本轮白名单；实际登记在 `docs/DECISIONS-PANEL-2026-10-31.md` §10.2 ——
  `docs/NEXT-STEPS.md` 本轮只允许改探针数量那一行）。

### P-H-1（HIGH）两条派生提示用两参 `deriveTrailFactors`

- **根因**：运行期 `syncStateToConfig()` 用**三参**（`k, smooth, slope`），面板两条提示用**两参**，
  而默认 `slopeTicks = 0`（纯矩形）与「不传 slope」在数学上是**不同结果**：
  ```
  k=4.5, smooth=0, slope=0   → [0.86, 0.86, 0.32, 0.32]  ← 运行期实际
  两参版（= 旧 slope 默认）    → [1.00, 0.72, 0.42, 0.22]  ← 面板自称
  ```
  默认参数下面板写「后缘 110 ms / 前缘 46 ms」，真实是 95 / 35 ms。同一页的预览已用三参，
  于是「预览」与「数字」互相矛盾。
- **改法**：`refreshDerivedHints()`（`:3000-3001`）与 `stretchText()`（`:3081-3082`）都改成
  `deriveTrailFactors(k, pending.typingSmoothness, pending.slopeTicks)`。
- **契约变更**：`VHints:54-56` 的 oracle 是两参自算，必须同步改三参（读 `pending.slopeTicks`）。
  这是**契约变更（与运行期对齐），非缺陷**。
- **连带 oracle**（同一类契约依赖，报告 §三没列全）：
  - `VLabel` 的 `f` 用两参自算 —— 改三参，并把场景的 slope 设为 100
    （它的 L-2/L-3/L-4 断言的是「rank3 比 rank2 短」，这个前提只有在斜度 > 0 时才成立；
    断言本身一字不改）；
  - `VPreset9` 的 P-9a/P-9b oracle 用两参 —— 改三参（读 `pending.slopeTicks`）；
  - `VVerify` 是**打印型**探针（无断言），其「真实运行期」也是两参 —— 改三参，保持输出真实。

### P-H-3（HIGH）坏快照时下拉「声称已切换」

- **根因**：`onAnimPresetPicked()`（`:2047`）与 `restoreAnimPreset()`（`:2525`）都调
  `SettingsField.restoreSkipping(...)`，**返回值被丢弃**。返回 0（整条快照一个字段都解不出）
  时，前者仍写 `animCustomName` → 下拉显示已选中，实际一个参数都没变。
  而 `CursorPresetApplier:120-145` 早就处理了 `applied == 0`（不写名字、不落盘、返回 false）——
  两条路径分家。
- **改法**：两处都接住 `applied`；`applied == 0` 时：不写 `animCustomName`、不继续、
  给一行用户提示（警告框 + 记录），并 `refreshPresetDisplay()` 把下拉拉回真实状态。
  `0 < applied < total` 是**文档化的向后兼容**（旧预设少键），**不报错、不改判为失败**。
- 不把这段抽到 `CursorPresetApplier` 共享（那要改白名单外的文件与 `State` 语义）；
  在面板内按同样的判据实现，并在注释里指向参照实现。

### P-H-2（HIGH）锐度换算不可逆

- **根因**：`blur = 0.05 + (100 - sharp) × 0.0055`。`State.shadowBlurFactor` 的出厂值是
  **0.2f**，而 0.2 不在这条网格上：`sharpnessOf(0.2f)=73`，`blurOfSharpness(73)=0.1985`，
  差 1.5e-3 ≫ `EPS=1e-6`。点一下输入框再移开（什么都不改）→ `normalizeTextField()` 写回
  0.1985 → pending 变脏 → 应用按钮点亮 → 派生「基础 副本」。
- **改法**（用户选 A：改换算网格，不改默认值 / 预设常量）：把映射改成**以默认值为锚点的两段线性**：
  ```
  锐度 0   → 外扩 0.60   （最柔，端点不变）
  锐度 73  → 外扩 0.20   （= State 出厂默认，精确落在整数网格上）
  锐度 100 → 外扩 0.05   （最锐，端点不变）
  ```
  两段各自的步长由端点算出，因此：
  - `sharpnessOf(0.2f) == 73`；
  - `blurOfSharpness(73) == 0.2f`（逐位相等）；
  - `sharpnessOf(blurOfSharpness(v)) == v` 对 0..100 全部成立；
  - 与旧映射的最大偏差 0.0015（≈32px 行高下 0.05px），端点与既有断言
    （`sharpnessOf(0.05)=100 / (0.60)=0 / (0.15)=82 / (0.20)=73`、滑块拖到 100 = 0.05）
    **全部不变**。
- **为什么不改默认值**（用户否决的 B 案）：那会改 `State` 默认值与 `ANIM_PRESETS` 常量，
  老用户升级后观感漂移，是最难排查的一类问题（`VDefaultFreeze` 就是为它设的）。

---

## 二、两条用户拍板（不是 HIGH，但一起落地）

### P-M-2 搜索框：去掉

用户 2026-10-31 拍板「平时不用，去掉」。**移除 `enableSearch` 覆写**，让 `SearchableConfigurable`
的默认实现返回 null，平台自己的设置搜索接管。
`SettingsSearchIndex.java` **不删**（独立类，删了牵动不必要的探针/白名单审查）；
断开注册后它变成零引用，登记为「后续可删」。

### P-M-10 复选框也生成副本

用户 2026-10-31 拍板「也生成副本」。`bindCheck` 的 ActionListener 在写完 pending 后
从 `refreshPreviews()` 改为 `refreshPresetDisplay()`（= 与滑块的 ChangeListener 同一条落脚链路：
`afterPendingEdited()` → 派生/更新快照 → 重算下拉）。
**两个抑制标志必须保住**：
- 程序化回灌（`reset()` / `switch preset` / `apply()` 后的回填）全程 `syncingControls = true`，
  监听器第一行就 return；
- `syncAllControlsFromPending()` 另有 `suppressDerive` 兜底，`afterPendingEdited()` 第一行 return。
因此不会出现「装载历史配置凭空派生副本」，也不会无限派生。

---

## 三、探针计划（先红后绿，每条都有变异测试）

新建 `tools/settings-probe/VBatch3Fixes.java`（登记进 `$mains`）：

| 编号 | 断言 | 变异（改坏生产代码 → 必须红） |
|---|---|---|
| T-1a | `FAILED` 时 apply 后 `baseline` 未推进 | 去掉 `return`，照旧 `reloadFrom` → 红 |
| T-1b | `FAILED` 时记录了用户可见提示（含「未能写入磁盘 / 重启」） | 删掉提示调用 → 红 |
| T-1c | 对照：不注入失败时 baseline 正常推进（证明 T-1a 不是恒真） | —— |
| T-2a | `sharpnessOf(blurOfSharpness(73)) == 73` | 改回 0.05 基准 → 红 |
| T-2b | `blurOfSharpness(73) == State 默认 0.2f`（< 1e-6） | 同上 → 红 |
| T-2c | 0..100 全部往返 | 同上 → 红 |
| T-3a | 坏快照 `applied==0`：不写 `animCustomName`、有提示 | 丢掉返回值 → 红 |
| T-3b | 部分缺失（0<applied<total）：仍写名字、**不**报错 | 把 `applied < total` 也当失败 → 红 |
| T-4a | 用户点复选框 → 派生副本（与滑块一致） | 改回 `refreshPreviews()` → 红 |
| T-4b | 程序化回灌（reset）→ 不派生 | 去掉 `syncingControls` 早退 → 红 |
| T-5 | `enableSearch("辉光") == null`（注册已摘除） | 恢复覆写 → 红 |

契约变更（只此几处，全部注明「契约变更（与运行期对齐），非缺陷」）：
`VHints` oracle、`VLabel` oracle+场景、`VPreset9` oracle、`VVerify` 打印口径。

---

## 四、顺序

```
1. 生产侧只加「可观测接缝」（lastUserNotice 记录 + 三个 ForProbe 访问器），不加行为
2. 写 VBatch3Fixes + 改 VHints/VLabel/VPreset9/VVerify 的 oracle → 跑 → RED（贴输出）
3. 实施 6 条修复 → 跑 → GREEN
4. 逐条变异测试（改坏 → 红 → 恢复 → 绿；每次删 classes 全量重编译）
5. 全量门禁 + D0 + AnimationPreview 零 diff + 白名单外零字节变化
6. 文档：DECISIONS-PANEL 追加 §十（含同类调用点登记）、CLAUDE/START-HERE/NEXT-STEPS 探针数
7. 提交（不 push）
```

---

## 五、风险与边界

- `Messages.showErrorDialog` 在 headless 下会抛 `HeadlessException`：提示方法里先判
  `GraphicsEnvironment.isHeadless()`（记录照做、不弹框），再 `try/catch` 兜底。
  真实 IDE 非 headless，行为就是弹框；这是**唯一**为可测性加的分支，注释写明理由。
- 不做 P-M-11 等其它 MEDIUM/LOW（不在本轮任务书）。
- 不在本轮改 `CursorPresetApplier` / `NeovideCaretManager` 的同类落盘调用（只登记）。

---

## 六、实测证据（2026-10-31 收工）

### 6.1 RED（探针先写、生产未改）

```
VBatch3Fixes   PASS 10 / FAIL 8
  FAIL T-1a baseline=0.2（应停在 0.11）
  FAIL T-1b 提示=null
  FAIL T-2b 写回=0.19849999 默认=0.2 差=0.0015000105
  FAIL T-3a 之前=「」之后=「坏预设」
  FAIL T-3b 提示=null
  FAIL T-3c 之前=0 之后=3
  FAIL T-4a 点前名字=「」点后=「」
  FAIL T-5 enableSearch(辉光)=Lambda@...
VHints         PASS 8 / FAIL 3（H-2/H-3/H-8：面板仍是 110/46，oracle 已是 95/35）
VPreset9       PASS 40 / FAIL 2（P-9a/P-9b 同一契约）
门禁：V-PROBES RESULT: FAILED（4 个探针未通过，含 informational VRegr2）
日志：build/gate-red.log
```

### 6.2 GREEN（六条修复完成后）

```
VBatch3Fixes   PASS 21 / FAIL 0
门禁：104 条目 / 1124 PASS / 2 FAIL（仅 VRegr2）/ V-PROBES RESULT: ALL PASS / exit 0
日志：build/gate-final3.log
D0：core/NeovideConfig.java 零 diff（exit 0）
AnimationPreview.java / docs/BUG-LOG.md：零 diff
```

### 6.3 变异测试（在 `src` 副本上改坏，工作区不被污染）

```
MUT-PC1    删掉 FAILED 分支                → VBatch3Fixes T-1a/T-1b 红   ✅
MUT-PH2    两段线性改回旧单直线             → T-2b 红（0.1985 ≠ 0.2）    ✅
MUT-PH3    删掉 applied==0 分支             → T-3a/T-3b/T-3c 红           ✅
MUT-PM10   bindCheck 改回 refreshPreviews   → T-4a 红                     ✅
MUT-PM2    注入一个 enableSearch 覆写        → T-5 红                     ✅
MUT-PH1    两处三参改回两参                  → VHints H-2/H-3/H-8 红      ✅
MUT-PCFLAG 只删 persistFailed 置位（保留分支）→ T-1c 红（isModified 假）  ✅
MUT-M1     去掉坏快照兜底的 suppressDerive   → T-3e 红（凭空派生副本）    ✅
结果：RED-OK=8 / NOT-RED=0
```

> 变异脚本是一次性的（`build/mut-batch3.sh`，build/ 被 .gitignore 忽略、未入库）；
> 未修改 `tools/settings-probe/run-v-mutations.ps1`（不在白名单）。

### 6.4 契约变更清单（全部已注明「与运行期对齐，非缺陷」）

```
VHints   oracle 两参 → 三参（任务书点名）
VLabel   oracle 两参 → 三参 + 场景固定 slopeTicks=100（否则 rank2/rank3 无差）
VPreset9 P-9a/P-9b oracle 两参 → 三参
VVerify  打印口径两参 → 三参（无断言）
```

### 6.5 代码审查与复审（阶段 3）

两位代理（通用质量 / Java 平台语义）对 diff 独立审查，结论与处理：

```
code-reviewer  APPROVE；MEDIUM 2（注释与实现相反 ×2）、LOW 5
java-reviewer  Block；HIGH 1（「失败后可重试」在平台流程里不成立）、MEDIUM 4、LOW 9
```

处理（全部落在本轮白名单内）：
- **H-1 → 新增 `persistFailed` 标志**：`isModified()` 在落盘失败后保持 true。
  平台的 `ConfigurableEditor.apply()` 先用 `isModified()` 当闸门，为假根本不调
  `apply()`；A3 已把 pending 写进活 state，没有这个标志「重试」只是注释里的一句话。
  成功落盘 / `reset()` 清零。新增 T-1c 断言 `isModified()`，并补变异 M7。
- **M-1 → 坏快照兜底刷新包 `suppressDerive`**：否则用户值已偏离基准时会凭空派生
  「X 副本」，与「当前设置保持不变」的文案矛盾。新增 T-3e，并补变异 M8。
- **M-2 → 重写 `bindCheck` 的旧 javadoc**（原文还写着「为什么不调
  refreshPresetDisplay」）。
- **M-3/M-4 → 提示框带 owner（`createComponent()`）+ headless 判据取
  AWT 与平台 `isHeadlessEnvironment()` 的或。**
- **LOW**：`sharpnessOf(NaN)` 显式回默认档；网格锚点加类加载不变量检查；
  `DEFAULT_BLUR` 注释写明与 `State` 默认值同源；T-5 增加反射断言
  （方法声明必须落在 `SearchableConfigurable` 的 default 上）；
  VPreset9 的 oracle 改为单一来源（全部读 `pending`）。
- **不修、只登记**：`ProbeFacade` 未登记两个新访问器（白名单外）、
  DEFERRED 无提示、不在 `$mains` 的旧探针未跑 —— 见 DECISIONS §10.2。
