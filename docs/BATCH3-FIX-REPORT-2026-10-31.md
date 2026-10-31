# 第 3 批审查修复 · 交付报告（2026-10-31）

> 依据：`docs/HANDOFF-BATCH3-AUDIT-2026-10-31.md` + `docs/CODE-AUDIT-2026-10-30.md` §九
> 施工计划与逐条证据：`plans/batch3-fix.md`
> 用户决策记录：`docs/DECISIONS-PANEL-2026-10-31.md` §十
> 分支：`p11-panel-refactor` · 代码提交：`434fbdb`（**未 push**）

---

## 零、一句话结论

第 3 批审查的 **1 CRITICAL + 3 HIGH + 11 MEDIUM + 16 LOW** 中，本轮修完
**1 CRITICAL + 3 HIGH + 2 条用户拍板的 MEDIUM（P-M-2 / P-M-10）**；
另按两位审查代理的意见补了 1 条平台语义缺口（落盘失败后的重试闸门）与 4 处注释/边界修正。
全量门禁 **104 条目 / 1124 PASS / 2 FAIL（仅 VRegr2 informational）/ ALL PASS**；
**8 条变异测试全部按预期变红**；D0 与 `AnimationPreview.java`、`BUG-LOG.md` 零 diff。

---

## 一、代码与分支状态（收工时实测）

```
分支            p11-panel-refactor
HEAD            434fbdb  fix(settings): 第 3 批审查修复 …
上一个提交      b5d2270  docs: 第 3 批审查（ui/settings 面板）+ 交接报告
fork/p11-panel-refactor...HEAD = 0 2（未 push）
工作区          仅两个开工前就存在的未跟踪文件：
                build/night-run-report.md、plans/batch3-review-prompt.md
门禁            104 条目 / 1124 PASS / 2 FAIL（仅 VRegr2）/ V-PROBES RESULT: ALL PASS
D0              core/NeovideConfig.java 零 diff
禁区            ui/settings/AnimationPreview.java、docs/BUG-LOG.md 零 diff
```

开工基线与交接文档的差异：交接写「102 条目」，实测 **103**；
`VAuditBatch2Round5` 是在报告写完之后才登记进 `build-v-probes.ps1` 的 `$mains`。
本轮新增 `VBatch3Fixes` 后为 **104**。

---

## 二、六条修复：根因 → 改法 → 证据

### P-C-1（CRITICAL）落盘失败被吞掉

**根因**：`apply()` 调 `svc.persistQuietly()` 后丢弃返回值。产生侧区分
`CONFIRMED / DEFERRED / FAILED`，消费侧却当它不存在。磁盘只读、序列化自检失败、
`saveSettings()` 抛异常时，面板照常「应用成功」并关闭，重启后全部回滚，全程零提示。

**改法**：
1. 接住 `PersistResult`；
2. `FAILED` 时不执行 `session.reloadFrom(target)`（baseline 不前移）；
3. 置 `persistFailed` 标志，让 `isModified()` 保持真 —— 平台的
   `ConfigurableEditor.apply()` 先看 `isModified()`，为假根本不会再调 `apply()`，
   不补这一条「重试」只是注释里的一句话；
4. 弹**带 owner** 的错误框（headless 走记录），文案：
   「本次设置已应用到当前会话，但未能写入磁盘。重启 IDE 后会恢复为上一次成功保存的值。」
   —— 不写「已保存」，因为 `CONFIRMED` 也不等于磁盘已写入；
5. `reset()` / 成功落盘时清 `persistFailed`。

**证据**：

```
RED      baseline=0.2（应停在 0.11）；提示=null
GREEN    baseline 0.11；提示含「未能写入磁盘 / 重启」；isModified=true
变异 1   删掉整个 FAILED 分支            → T-1a / T-1b 红
变异 2   只删 persistFailed = true       → T-1c 红（isModified 变假）
```

### P-H-1（HIGH）两条派生提示用两参 `deriveTrailFactors`

**根因**：运行期 `syncStateToConfig()` 用三参 `(k, smooth, slopeTicks)`，面板提示用两参。
默认 `slopeTicks = 0`（纯矩形）与「不传 slope」结果不同：

```
slope=0   → [0.86, 0.86, 0.32, 0.32]   ← 运行期真实
两参版     → [1.00, 0.72, 0.42, 0.22]   ← 面板自称
```

默认参数下面板写「后缘 110 ms / 前缘 46 ms」，真实是 **95 / 35 ms**；
同一页预览早已用三参，于是「预览」与「数字」互相矛盾。

**改法**：`refreshDerivedHints()` 与 `stretchText()` 都改成三参。

**证据**：VHints H-2/H-3/H-8 由红转绿；变异（改回两参）→ 三条同时红。

### P-H-3（HIGH）坏快照时下拉「声称已切换」

**根因**：`onAnimPresetPicked()` 与 `restoreAnimPreset()` 都丢弃
`SettingsField.restoreSkipping(...)` 的返回值。返回 0（整条快照一个字段都解不出）时，
前者仍写 `animCustomName` → 下拉显示已选中，实际一个参数都没变。

**改法**：两处接住 `applied`；`applied == 0` 时：
不写 `animCustomName`、不继续、给一行警告（记录 + 弹框）、
并用 `suppressDerive` 包住 `refreshPresetDisplay()` 把下拉拉回真实状态
（否则兜底刷新会「顺手派生 X 副本」，与文案自相矛盾）。
`0 < applied < total` 是文档化的向后兼容（旧预设少键），**不报错**。

**证据**：

```
RED      之前=「」之后=「坏预设」；下拉 0→3；提示=null
GREEN    名字不变；提示含「损坏」；下拉回到 0；部分缺失仍正常切换且不报错
变异 1   删掉 applied==0 分支            → T-3a/b/c 红
变异 2   去掉兜底的 suppressDerive       → T-3e 红（凭空多一个副本）
```

### P-H-2（HIGH）锐度换算不可逆

**根因**：`blur = 0.05 + (100-sharp) × 0.0055` 的网格上没有出厂默认 `0.2f`：
`sharpnessOf(0.2)=73`，`blurOfSharpness(73)=0.1985`，差 1.5e-3 ≫ `EPS=1e-6`。
点一下输入框再移开（什么都没改）→ pending 变脏 → 派生「基础 副本」。

**改法（用户拍板 A）**：改成以默认值为锚点的**两段线性**：

```
锐度 0   → 外扩 0.60   （最柔，端点不变）
锐度 73  → 外扩 0.20   （= 出厂默认，精确落在整数网格上）
锐度 100 → 外扩 0.05   （最锐，端点不变）
```

不碰 `State` 默认值与 `ANIM_PRESETS` 常量。

**验证**（探针 T-2，实测）：

```
sharpnessOf(blurOfSharpness(v)) == v 对 0..100 共 101 档全部成立
blurOfSharpness(73) == 0.2f（逐位相等）
端点：0.05↔100、0.60↔0、0.15↔82 全部不变
与旧映射最大偏差 0.0015（32px 行高约 0.05px）
变异（改回单直线）→ T-2b 红
```

### P-M-2（用户拍板：去掉自带搜索）

移除 `enableSearch` 覆写，让 `SearchableConfigurable` 的 default 返回 null，
平台自己的设置搜索接管。`SettingsSearchIndex.java` 保留（零引用的独立类，登记待删）。

### P-M-10（用户拍板：复选框也生成副本）

`bindCheck` 的 ActionListener 从 `refreshPreviews()` 改为 `refreshPresetDisplay()`
（与滑块同一条落脚链路：`afterPendingEdited()` → 派生/更新快照 → 重算下拉）。
`syncingControls` / `suppressDerive` 保留：程序化回灌（reset / 切预设 / apply 回填）
不派生，也不会无限派生。

**证据**：T-4a 点「启用辉光」→ 生成「基础 副本」；T-4b reset 装载偏离配置 → 不生成；
变异（改回 `refreshPreviews()`）→ T-4a 红。

---

## 三、探针变更

### 新增

`tools/settings-probe/VBatch3Fixes.java` —— **21 条断言**：

| 编号 | 守什么 |
|---|---|
| T-1a~d | 落盘失败：baseline 不前移、有提示、`isModified()` 保持真、成功后恢复 |
| T-2a~d | 锐度：默认值互逆、101 档往返、端点与既有取值不变 |
| T-3a~e | 坏快照不写名字/有提示/下拉复位；部分缺失兼容；兜底不派生副本 |
| T-4a~b | 复选框派生；程序化回灌不派生 |
| T-5a~b | `enableSearch` 返回 null 且方法声明落在接口 default 上 |

### 契约变更（oracle，不改断言）

任务书只点名 `VHints`；实测另外三个探针也把两参版当「真实运行期」，不一起对齐必然假红：

```
VHints    两参 → 三参（任务书点名）
VLabel    两参 → 三参 + 场景固定 slopeTicks=100（rank2/rank3 的差异只由斜度产生）
VPreset9  P-9a/P-9b 两参 → 三参，且 oracle 改为单一来源（全部读 pending）
VVerify   打印型探针，口径两参 → 三参（无断言）
```

全部注明「契约变更（与运行期对齐），非缺陷」。

### 工具改动

- `V0Env`：新增 `throwOnSaveSettings` 注入（构造真实的 `PersistResult.FAILED`）。
- `build-v-probes.ps1`：登记 `VBatch3Fixes`。

---

## 四、代码审查（阶段 3）与复审

两位代理对 diff 独立只读审查：

```
code-reviewer  APPROVE；MEDIUM 2、LOW 5
java-reviewer  Block；HIGH 1、MEDIUM 4、LOW 9
```

### 已处理

| 级别 | 问题 | 处理 |
|---|---|---|
| HIGH | 「落盘失败后可重试」在平台流程里不成立（`isModified()` 闸门） | 新增 `persistFailed`，`isModified()` 保持真；补 T-1c 与变异 M7 |
| MEDIUM | 坏快照兜底刷新可能凭空派生副本 | 用 `suppressDerive` 包住；补 T-3e 与变异 M8 |
| MEDIUM | `bindCheck` 旧 javadoc 与新实现相反 | 整段重写为「用户 2026-10-31 拍板：也生成副本」 |
| MEDIUM | 错误框无 owner，最坏被遮挡后降级成日志 | 改为 `Messages.showErrorDialog(createComponent(), …)` |
| MEDIUM | headless 只看 AWT，平台 headless 可能漏判 | 判据取 AWT 与 `Application.isHeadlessEnvironment()` 的或 |
| LOW | `sharpnessOf(NaN)` 静默变最柔 | 显式返回默认档 |
| LOW | 网格锚点无编译期/运行期不变量 | 类加载时校验并在越界时立即抛错 |
| LOW | `DEFAULT_BLUR` 与 `State` 默认值同源但无提示 | 注释写明「改 State 必须同步改这里」，T-2b 守门 |
| LOW | T-5 只断言返回 null 可被假实现绕过 | 增加反射断言（声明必须落在 `SearchableConfigurable`） |
| LOW | VPreset9 oracle 混用局部 `s` 与 pending | 统一读 `pending` |

### 未修、只登记（DECISIONS §10.2）

- `CursorPresetApplier:73/138/200/228`、`NeovideCaretManager:348` 同类落盘失败被吞（白名单外）。
- `SettingsSearchIndex.java` 零引用，待删。
- `ProbeFacade` 未登记本轮两个新访问器（白名单外）。
- `PersistResult.DEFERRED` 仍推进 baseline 且无提示（现实中几乎不可达）。
- `ProbeSettingsV2` / `Probe4Bugs` 等不在 `$mains` 的旧探针未跑；它们对「非网格 blur」
  的既有断言在旧映射下同样不成立，不是本轮引入。

---

## 五、四条用户决策的落点

`docs/DECISIONS-PANEL-2026-10-31.md` **§十**：

| # | 决策 | 代码落点 |
|---|---|---|
| 1 | 落盘失败弹错误框、不阻止关闭 | `apply()` 的 FAILED 分支 + `showUserNotice` |
| 2 | 改换算步长，让 0.2f 落在整数网格上 | `SettingsUiUtil` 两段线性 |
| 3 | 去掉插件自带设置搜索 | 删除 `enableSearch` 覆写 |
| 4 | 复选框也生成副本 | `bindCheck` 改走 `refreshPresetDisplay()` |

---

## 六、门禁与变异（可复现命令）

```powershell
# 全量门禁（唯一权威入口）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr

# D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

变异测试是一次性脚本 `build/mut-batch3.sh`（`build/` 被 `.gitignore` 忽略，未入库）；
未修改白名单外的 `run-v-mutations.ps1`。

```
MUT-PC1    删掉 FAILED 分支                 → T-1a/T-1b 红    ✅
MUT-PCFLAG 只删 persistFailed 置位           → T-1c 红         ✅
MUT-PH2    两段线性改回旧单直线              → T-2b 红         ✅
MUT-PH3    删掉 applied==0 分支              → T-3a/b/c 红     ✅
MUT-M1     去掉兜底的 suppressDerive        → T-3e 红         ✅
MUT-PM10   bindCheck 改回 refreshPreviews    → T-4a 红         ✅
MUT-PM2    注入 enableSearch 覆写             → T-5 红          ✅
MUT-PH1    两处三参改回两参                   → VHints H-2/3/8 红 ✅
结果：RED-OK=8 / NOT-RED=0
```

---

## 七、需要实机确认

| 项 | 怎么确认 |
|---|---|
| P-C-1 错误框 | 把配置目录设为只读（或临时注入失败）→ 点应用 → 应看到带 owner 的错误框浮在设置窗之上，文案含「未能写入磁盘 / 重启」 |
| P-H-2 观感 | 默认辉光与最柔/最锐两端与升级前对比；网格最大偏移 0.05px |
| P-M-10 | 在「基础」预设上勾/取消「启用辉光」→ 下拉立即出现「基础 副本」 |
| P-M-2 | 设置页搜索框仍应能搜到本页字段（平台接管）；插件不再自绘定位逻辑 |

---

## 八、本轮改动文件清单

```
M  CLAUDE.md                     仅探针数量 105→106（含 VBatch3Fixes）
M  START-HERE.md                 同上
M  docs/DECISIONS-PANEL-2026-10-31.md   追加 §十
M  docs/NEXT-STEPS.md            仅探针数量 105→106
A  plans/batch3-fix.md           计划 + RED/GREEN/变异/审查证据
A  docs/BATCH3-FIX-REPORT-2026-10-31.md  本报告（随文档提交入库）
M  ui/settings/NeovideCursorConfigurable.java  P-C-1 / P-H-1 / P-H-3 / P-M-2 / P-M-10
M  ui/settings/SettingsUiUtil.java             P-H-2
M  tools/settings-probe/V0Env.java             注入开关
A  tools/settings-probe/VBatch3Fixes.java      21 条守门断言
M  tools/settings-probe/VHints.java            oracle 契约变更
M  tools/settings-probe/VLabel.java            oracle + 场景
M  tools/settings-probe/VPreset9.java          oracle
M  tools/settings-probe/VVerify.java           打印口径
M  tools/settings-probe/build-v-probes.ps1     新探针登记
```

代码改动在提交 `434fbdb`；本报告单独作为文档提交（仍在 `p11-panel-refactor`，**未 push**）。
白名单外字节变化：**无**（`build/` 下的一次性日志与脚本被 `.gitignore` 忽略）。
