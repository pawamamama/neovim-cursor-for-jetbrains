# 设备级跨行/鼠标拖动形状实验开关 施工提示词（2026-11-05）

> 状态：待执行
> 仓库：`D:\deepseekwork\neovim-cursor-pr`
> 角色：执行会话只按本 PROMPT 干活、写 REPORT；决策会话不改生产代码；用户是唯一裁决人。
> 自包含：背景、方向、边界、命令、验收与报告要求全在本文件；执行会话不需要回翻对话。
> 一轮只干一件事：本轮交付一个**设备级实验开关**，开启后完全接管「跨行 / 鼠标拖动」缎带形状；不改造既有参数体系、不复活失败模型、不改单行手感。

---

## 1. 背景与证据

### 1.1 用户原话（逐字引用）

本轮开工令：

> 「完全正确，但是记得我们做形状不要把失败案例回归　判断对了就是做一个设备级的跨行和鼠标拖动的实验性设备级别开关放在，开启就完全接管之前行缎带路径，把不能调节的置灰不伤害到行手感，其他参数，记得跨行动画预览也要接入，现在开始写入施工提示词」

前一轮对「难不难、和现有设置参数兼容性」的判断，用户确认：

> 「完全正确」

再往前的版本口径与已验收结论：

> 「就是工作区域，文档修复和bug０１６－２」

> 「文档记录，无手感变化现在开始生成提示词给我按照施工流程」

功能探索过程（供理解方向，不代替本轮开工令）：

> 「应该还有一个上游的０６０　版本他那个拖尾缎带比较好看你看看怎么做的」

> 「这轮你先看那个py实现」

> 「０６０实现」

> 「密度管什么，我想不那么像缎带我拖动的鼠标拖动换行更流畅现在基础像是不那么实，动画长了快速拖动也不好看，就是想让他动画不变缎带效果更流畅丝滑真实那唯一高收益就只有加密度」

用户随后叫停了决策会话擅自写出的「叠层补密」方案：

> 「你这个方案太垃圾了叫停」

另一个会话转达的产品边界（供理解，不代替用户原话）：A（弹窗闪烁）暂缓、B（`charsMoved≥4` 误判）已撤回、C（撤 55ms 下限）保留、D/E 不做；Tab 缩进 / 单行块选 / 跨行选区分类、辉光 16 层、更新内置预设、删个人预设都是附加，不进主线；观感取舍要给用户 2~3 个选项，不许猜。

### 1.2 决策会话只读复核过的事实（2026-11-05，执行会话开工时用 §6 命令重新核对）

| 项目 | 事实 | 来源 |
|---|---|---|
| 分支 / HEAD | `fix/bug-016-completion-flicker` / `f43ca55 docs: 交接给决策会话并修正 D-3 违约` | `git branch --show-current` / `git log -1 --oneline` |
| 工作区 | 只有未跟踪 `docs/sessions/2026-11-05-handfeel-observation/`；本 PROMPT 写入后又多一个未跟踪目录 | `git status --porcelain` |
| 已装 IDEA 插件 | jar 内 `<version>0.9.134-workspace</version>`，文件名恒为 `jetbrains-neovide-cursor-0.9.8.jar` | `unzip -p ... META-INF/plugin.xml` |
| 已装版口径 | 当前工作区源码现场编译（文档/注释修复 + 保留 BUG-016-2）；`fddd529..HEAD` / `79acfd3..HEAD` 生产代码剥注释后 0 行差异 | `git diff --stat` + 剥注释 diff |
| 用户验收结论 | 用户报告「文档记录，无手感变化」；这是 `0.9.134-workspace` 的用户实机结论，决策会话未重跑 A/B，不扩大解释 | 用户原话 |
| 探针 / md | `118 / 133` 个探针文件；跟踪 md `191 / 126 / 65` | `ls` / `git ls-files` |
| D0 | `core/NeovideConfig.java` 零 diff，`EXIT=0` | `git diff --exit-code -- ...` |
| 上游 0.6.0 实现 | 仓库 git 历史 commit `d6a43fb`；PyCharm 2025.2 已装 `jetbrains-neovide-cursor-0.6.0.jar`（67485 B，26 个 class） | `git show d6a43fb:...` / `unzip -l` |
| 上游形状机制 | `NeovideConfig.trailFactors = {1.0f, 0.9f, 0.5f, 0.3f}`；`SpringTrail.assignRanks()` 按对齐度升序给 rank；`TrailCorner.jump()` 用 `baseTime × factor`，另有 `useHardSnap && alignment > 0.5 → snapAnimationLength=0` | 0.6.0 源码 |
| 当前跨行机制 | `TrailCorner.java:368`：`leading = animationLength × clamp(1 - trailSize, 0, 1)`、`trailing = animationLength`；rank0=后缘、rank1=均值、rank2/3=前缘。`trailSize=1.0` → `100 / 50 / 0 / 0 ms` | 当前源码 |
| 现有参数兼容结论 | [UPSTREAM-COMPARE-2026-11-02.md](../../UPSTREAM-COMPARE-2026-11-02.md) §6.4：本地 `trailSize` 三档公式**不存在精确调成 0.6.0 形状的解**；`trailSize≈0.2` 只能近似，且用户觉得「密度小、尾巴短、难看」 | 既有对比文档 |
| 设备级开关现成范例 | `SettingsField.java:860-861`：`bools("leadingSnap", ... null, null, true)`；末参 `true = runtimeSwitch`，自动不进 `PRESET_FIELDS`、恢复预设时跳过 | 当前源码 |
| 跨行预览入口 | `AnimationPreview.cross()` / `Mode.CROSS`；面板 `crossPreview()`、`refreshPreviews()`、`pushStyleTo(...)` 遍历 `{inlinePreview, crossPreview, glowPreview}` | 当前源码 |
| 失败案例边界 | `docs/archive/decisions/LEADING-SNAP-DECISION-2026-11-01.md`、`docs/archive/reports/LEADING-SNAP-ISSUES-2026-11-01.md`：三版「前缘 0ms 吸附 / 四角同长 + 固定尾巴 / 前缘 0ms + 短后缘」实机全部不可行，已回退；当前 `leadingSnap` 只剩可保存但无行为的 UI 壳 | 历史报告 |

### 1.3 本轮「做形状」的准确含义

**做的是一个可开关的独立形状路径，不是把失败案例接回来，也不是在现有 `trailSize` 公式上加密度。**

大白话：

- **关（默认）**：今天 `0.9.134-workspace` 什么样就什么样，跨行仍是 `trailSize + animationLength` 三档斜缎带，单行手感、辉光、预设、颜色、淡出、帧调度全部不动。
- **开**：只在「跨行 / 鼠标拖动产生的大跨度路径」上，由新路径**完全接管**形状分配；照 0.6.0 那种「四角时间梯度较接近、尾巴短而平行」的形状走，让快速拖动换行更实、更流畅、不飘成薄斜片。
- 「完全接管」指：开启时旧跨行三档公式不再决定这条路径的四角时长；不是给旧公式再加一个倍率、下限或叠层。
- **不复活失败案例**：0.6.0 源码里那句 `useHardSnap && alignment > 0.5 → snapAnimationLength = 0` 属于历史失败机制；本轮不允许把 `useHardSnap` / `leadingSnapFactor` / `snapAnimationLength` / `前缘 0ms 吸附` / `四角同长 + 固定尾巴` / `前缘 0ms + 短后缘` 任一模型搬回生产路径。借鉴的是「四档相对平缓的时间梯度」这一形状思想，不是照搬硬吸附。
- 上游 0.6.0 的短跳判据（像素阈值 / `shortMoveMinChars`）也不移植：它是打字手感机制，用户明确不伤单行手感。
- 新路径里前缘仍走正常弹簧过渡，不瞬时归零；具体四角时长由执行会话按「默认关零差异 / 开启不与失败模型同形」的可测断言先写探针，再实现最小公式。决策会话**不替执行会话拍板具体系数**。

### 1.4 用户已拍板的产品边界

| 项 | 决定 |
|---|---|
| 开关归属 | **设备级**（`runtimeSwitch=true`）：不进动画预设、不进自定义预设快照、切预设不改变它；默认 **关** |
| 作用范围 | 仅「跨行 / 鼠标拖动」对应的大跨度路径；单行输入、退格、打字、块选分类不碰 |
| 开启行为 | **完全接管**旧行缎带路径，不再读旧三档公式决定该路径形状 |
| 置灰 | 开启后，被接管而不再起调节作用的参数在设置页置灰；必须写清置灰清单与「为什么」，不许静默禁用 |
| 其它参数 | `slopeTicks`（单行斜度）、`stretchRatioK`（单行拉伸）、`typingSmoothness`（单行平滑）、`typingMaxChars` 判据、辉光、颜色、淡出、帧率、多光标、预设数值、个人预设全部不伤 |
| 预览 | **跨行动画预览必须接入**：开关状态改变时 `crossPreview` 当场显示接管后的形状；`inlinePreview` 不受影响 |
| 失败案例 | 三版硬吸附 / 半边拖尾模型不回归；`leadingSnap` 旧壳子不许偷偷接上 |
| 默认观感 | 默认关 = 当前观感零变化；开启后是否「更好看」由用户实机裁决，执行会话不宣称 |

---

## 2. 目标

一句话：**交付一个默认关闭、设备级、可持久化、可回显的「跨行/鼠标拖动形状实验开关」；开启时由独立形状路径完全接管旧跨行缎带，置灰被接管参数，跨行预览同源显示，且单行手感与其它参数零变化；用守门探针、变异测试、全量门禁和 D0 证明「关 = 零差异 / 开 = 只改该路径 / 失败模型未回归」。**

完成后应达到：

1. 设置页有明确中文标签「跨行形状实验（设备级 · 默认关）」的复选框；字段名带中文注释；状态能存盘、重启能读回、面板能回显。
2. 关：所有生产路径的四角时长、绘制、预览、配置与当前 `0.9.134-workspace` 逐位一致。
3. 开：仅跨行 / 鼠标拖动大跨度路径的四角时长由新公式决定；旧 `trailSize` 三档公式不参与该路径；单行短跳四角时长、rank 判据、斜度、拉伸、平滑、辉光、颜色、淡出、多光标、帧调度不变。
4. 开启后被接管参数在 UI 置灰，且只是「不再调节本路径」，不清空、不改写、不丢弃用户值；关闭后全部恢复可调、值不变。
5. `crossPreview` 在开关切换时实时显示对应形状；`inlinePreview` / `glowPreview` 不受影响。
6. 新守门探针证明：默认关零差异、开启只改跨行路径、失败模型的关键代码形态不出现、置灰清单正确、预览同源；变异测试能抓坏。
7. 全量门禁 `ALL PASS`、D0 零 diff、编译/打包成功；产出一个**不自动安装**的实验 jar 交用户决定。
8. REPORT 带命令输出、门禁数字、变异测试、未验证项；不写「手感已变好 / 已验收」。

---

## 3. 非目标（明确不做）

- 不复活 `useHardSnap` / `snapAnimationLength=0` / `leadingSnapFactor` / `alignment>0.5` 硬吸附；不接回 `leadingSnap` 旧空壳；不做「四角同长 + 绘制层固定尾巴」「前缘 0ms + 短后缘」三版失败模型。
- 不把 0.6.0 的短跳判据、像素阈值、`shortMoveMinChars`、原生光标显示策略移植到本地单行路径。
- 不改 `trailSize` 的字段名 / 量程 / 公式 / 预设域；不把 `trailSize` 上限改成 2；不新增「密度」滑块；不做「叠层补密 / 多叠几层半透明形状」。
- 不改 `animationLength` / `shortAnimationLength` 的默认值与语义；不改三个内置预设、不改自定义预设内容、不删个人预设、不改 `install.ps1` 还原语义。
- 不处理 Tab 缩进、单行块选、跨行选区的分类；不改 `charsMoved` / `typingMaxChars` / `hasSelection()` 判据，不改 `isShortJump` 的现有判定。
- 不修 A / B / D / E，不动 C（55ms 下限保持撤回）；不修 BUG-016-2，不回退 0.9.127。
- 不改辉光层数上限到 16，不改颜色 / 淡出 / 多光标 / 帧调度 / 绘制层叠次数。
- 不改 `core/NeovideConfig.java`（D0）、不动 Gradle 配置、不改 `docs/archive/` 历史文档、不改 `CLAUDE.md` / `START-HERE.md` / `CURRENT-STATE` / `NEXT-STEPS` / `INDEX.md` / `BUG-LOG.md` / 工作流协议。
- 不自动安装、不自动启动 IDEA、不覆盖当前已装 jar；不提交、不 push、不建 PR、不建新分支、不打 tag、不归档本会话目录。
- 不替用户决定「开后好不好看」或把默认改成开；不把主观观感写成客观结论。

---

## 4. 允许改的文件

按 `CLAUDE.md` §7.0「加设置项的完整落点清单」逐层落地；执行会话开工先 `Grep` 确认行号，再在下列白名单内改。

### 4.1 生产代码

- `src/main/java/com/github/yyyolo7a79/neovidecursor/settings/SettingsField.java`：新增设备级布尔字段（建议名 `crossShapeExperiment`，中文注释「跨行形状实验（设备级）」），末参 `runtimeSwitch=true`，不进 `PRESET_FIELDS`。
- `src/main/java/com/github/yyyolo7a79/neovidecursor/settings/NeovideCursorSettings.java`：State 字段 + 默认 `false` + `copyFrom()` + `loadState()`；不写兼容/迁移代码（四-K）。
- `src/main/java/com/github/yyyolo7a79/neovidecursor/core/TrailCorner.java`：只读核对旧跨行公式；允许增加一条「开关开启 → 走独立形状分配」的最小分支与方法，**不许改关状态下的旧公式**；不许引入硬吸附字段。
- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/SpringTrail.java`：若需要把设备开关推进逐 trail 状态，允许加最小字段/读取；不改 `assignRanks()` 对单行路径的行为。
- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java`：允许在现有 `syncGlobalSettings` / `runTick` 链路上把 State 只读值推进对应消费者；**不改 BUG-016-2 像素复查路径**，不改 `refreshTarget` 判据。
- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/settings/AnimationPreview.java`：`Mode.CROSS` 必须消费同一开关；允许新增一个 setter 与最小形状分支；`Mode.INLINE` 不读取该开关。
- `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/settings/NeovideCursorConfigurable.java`：复选框、置灰绑定、`pushStyleTo(...)` / `refreshPreviews()` 推送、跨行提示文案在开关开启时说明「已由实验形状接管」；三个预览中只让 `crossPreview` 形状切换。
- `src/main/resources/messages/NeovideCursorBundle.properties`、`NeovideCursorBundle_zh_CN.properties`：中英标签与说明同步；中文必须写「默认关 / 不随预设 / 只影响跨行与鼠标拖动 / 开启后相关参数置灰」。

### 4.2 探针与门禁注册

- `tools/settings-probe/` 新增 `VCrossShapeExperiment.java`（名称可改，但必须 `V*.java`）作为守门探针。
- 允许按契约变更更新：`VAddSetting.java` / `VerifyPanel.java` 的控件数、`VPreset.java` 的 State public 字段数、`VDefaultFreeze.java` 的 GOLDEN 默认值、`VUi.java` 的 `KEYS` / `SPEC`（**按面板真实渲染顺序插入**，不许追加末尾）。
- `tools/settings-probe/build-v-probes.ps1`：把新探针登记进 `$mains`，只加类名。
- 允许新增最小探针辅助类；不许删除既有断言，不许复刻生产算法（必须调用生产方法或断言生产行为）。

### 4.3 本轮会话文档

- `docs/sessions/2026-11-05-cross-shape-experiment/REPORT.md`
- `docs/sessions/2026-11-05-cross-shape-experiment/evidence/*.txt`（可选，只放命令输出 / 变异记录）

### 4.4 明确禁区（只读）

- `src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java`（D0 零 diff）
- `src/main/java/com/github/yyyolo7a79/neovidecursor/settings/CursorPresets.java`（三个内置预设数值）
- `build/install.ps1`、`build/offline-build.ps1`、全部 `build/*.md`
- `CLAUDE.md`、`START-HERE.md`、`docs/CURRENT-STATE-2026-11-04.md`、`docs/NEXT-STEPS.md`、`docs/INDEX.md`、`docs/BUG-LOG.md`、`docs/WORKFLOW.md`、`docs/SESSION-PROTOCOL.md`、`docs/WORK-DISCIPLINE.md`、`docs/UPSTREAM-COMPARE-2026-11-02.md`
- `docs/archive/` 全部历史文档、`docs/sessions/2026-11-05-*/` 其它既有文件
- 用户配置 XML、`%APPDATA%`、`_config-backup/`、`_jar-backup-20261024/`

> 需要改清单外的文件：停下，在 REPORT §6 写清原因，等用户裁决，不自行扩大范围。

---

## 5. 验收标准（可测）

| 编号 | 标准 | 怎么验 |
|---|---|---|
| AC-1 | 新布尔字段默认 `false`、设备级 `runtimeSwitch=true`；`PRESET_FIELDS` 不含它；切任何动画预设不改变它；字段名/中文注释齐全 | 新探针 + `Grep` 字段表 |
| AC-2 | 六层接通：声明 / `FIELDS` / `copyFrom` / `loadState` / 消费者 / UI 控件；能存、重启能读、能回显 | `VLoadState` / `VDeadField` / 新探针 |
| AC-3 | **关 = 零差异**：同一组输入下，跨行四角时长、单行短跳四角时长、预览输出、绘制参数与改动前逐位一致 | 新探针调用生产 `TrailCorner.jump()`；对照当前基线输出 |
| AC-4 | **开 = 完全接管**：开启后跨行 / 大跨度路径的四角时长不再由 `leading=animationLength×(1-trailSize)` 与 rank1 均值公式产生；同一 `trailSize` 改值不再改变该路径形状 | 新探针参数扫描 + 变异测试 |
| AC-5 | **单行零伤**：开关 true/false 两态下，`isShortJump` 判据、单行四角时长、`slopeTicks` / `stretchRatioK` / `typingSmoothness` 派生结果逐位相同 | 新探针 + `VTypingSpan` / `VHints` / `VPreviewParity` 仍绿 |
| AC-6 | **失败模型未回归**：生产源码剥注释后不含 `useHardSnap` / `leadingSnapFactor` / `leadingSnapThreshold` / `snapAnimationLength` 的生效读取；不含「前缘 0ms 吸附 / 四角同长 + 固定尾巴 / 前缘 0ms + 短后缘」三版实现；`leadingSnap` 旧壳仍无行为 | 新探针静态扫描 + `VLeadingSnap` 仍绿 |
| AC-7 | **形状不是简单叠层**：开启后仍每帧一次四边形填充；`glowLayers`、颜色、淡出、透明叠加次数不变；不新增历史帧/残影数组 | `CaretTrailPanel` 静态断言 + 新探针 |
| AC-8 | **置灰正确**：开启时被接管参数控件 `enabled=false`，关闭后 `enabled=true` 且值不变；置灰清单在 REPORT 逐项列出并写理由；不清空、不改写、不落盘丢弃 | UI 探针断言 `isEnabled()` + 配置回读 |
| AC-9 | **跨行预览接入**：开关切换时 `crossPreview` 形状输出随开关变化；`inlinePreview` / `glowPreview` 输出不变；`refreshPreviews()` / `pushStyleTo(...)` 覆盖新字段 | `VPreviewParity` 风格探针 + 新探针 |
| AC-10 | 控件位置与 `VUi.KEYS` / `SPEC` 一致；控件数 / State 字段数 / GOLDEN 常数按契约变更更新并写「契约变更，非缺陷」 | `VUi` / `VAddSetting` / `VPreset` / `VDefaultFreeze` |
| AC-11 | 新守门探针至少覆盖 AC-1~AC-9；三组变异测试（默认改成 true / 消费者不读开关 / 预览不接开关）都必须红，恢复后全绿 | REPORT 贴命令、红名单、恢复绿 |
| AC-12 | 全量门禁最后一行 `V-PROBES RESULT: ALL PASS`；D0 `EXIT=0`；编译 37 个 `.java` 成功；无并发、无吞退出码 | §6 命令 |
| AC-13 | 只打包不安装：`dist/neovide-cursor-0.9.135-cross-shape.jar` 内 `<version>0.9.135-cross-shape</version>`、`since-build=242` / `until-build=261.*` | `offline-build.ps1` + `unzip -p` |
| AC-14 | 禁区文件全部零改动；三个内置预设、`customAnimPresets`、`glowLayers` 上限、`install.ps1`、既有 `docs/` 文档不动 | `git diff --stat` / `git status --porcelain` |
| AC-15 | REPORT 按 §9 写全；不写「已实机验收」「手感变好」；默认关状态明确 | 人工抽查 |
| AC-16 | 用户实机验收：同一预设、同一快速鼠标拖过换行路径，分别关/开对比；REPORT §7 由用户填「更像 0.6.0 / 不如当前 / 需调形状」 | 用户填；执行会话不替用户裁决 |

---

## 6. 必跑命令

在 `D:\deepseekwork\neovim-cursor-pr` 下执行。门禁**只跑一份，禁止并发**；所有管道必须保留退出码，不许 `| tail -N && git add`。关键输出贴进 REPORT §4。

### 6.1 开工前 30 秒 + D0

```bash
R="D:/deepseekwork/neovim-cursor-pr"
printf '=== status / branch / HEAD ===\n'
git -C "$R" status --porcelain
git -C "$R" branch --show-current
git -C "$R" log -1 --oneline
git -C "$R" remote -v
git -C "$R" rev-list --left-right --count fork/main...HEAD
printf '=== installed jar version ===\n'
J="/c/Users/wangw/AppData/Roaming/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar"
unzip -p "$J" META-INF/plugin.xml | grep -o '<version>[^<]*' | head -1
printf '=== D0 ===\n'
git -C "$R" diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
printf 'D0_EXIT=%s\n' "$?"
printf '=== probes / md ===\n'
ls -1 "$R"/tools/settings-probe/V*.java | wc -l
ls -1 "$R"/tools/settings-probe/*.java | wc -l
T=$(git -C "$R" ls-files '*.md' | wc -l)
A=$(git -C "$R" ls-files 'docs/archive/*.md' 'docs/archive/**/*.md' | sort -u | wc -l)
printf 'md total=%s archive=%s active=%s\n' "$T" "$A" "$((T-A))"
```

### 6.2 先搜现成实现 / 调用面（点名 `search-first` 技能 + `Explore` 代理）

```bash
R="D:/deepseekwork/neovim-cursor-pr"
printf '=== old cross formula / device switch / preview entry ===\n'
grep -R -n --include='*.java' -E 'trailSize|leading =|assignRanks|runtimeSwitch|leadingSnap|crossPreview|Mode\.CROSS|refreshPreviews|pushStyleTo|syncGlobalSettings' "$R"/src/main/java | head -160
printf '=== failure model names must stay absent from live reads ===\n'
grep -R -n --include='*.java' -E 'useHardSnap|leadingSnapFactor|leadingSnapThreshold|snapAnimationLength|HALF_TRAIL' "$R"/src/main/java | head -40
printf '=== upstream 0.6.0 shape source (read-only reference) ===\n'
git -C "$R" show d6a43fb:src/main/java/com/github/yyyolo7a79/neovidecursor/core/TrailCorner.java | sed -n '98,135p'
git -C "$R" show d6a43fb:src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java | grep -n 'trailFactors\|animationLength\|shortAnimationLength' | head -20
```

### 6.3 先断言后代码（点名 `tdd-workflow` 技能）

```bash
R="D:/deepseekwork/neovim-cursor-pr"
printf '=== registered probes baseline ===\n'
awk '/\$mains *= *@\(/{f=1} f{print} f&&/^\)/{exit}' "$R"/tools/settings-probe/build-v-probes.ps1 | grep -o "'V[A-Za-z0-9]*'" | tr -d "'" | sort -u | wc -l
printf '=== contract constants ===\n'
grep -n -E 'sliders ==|checks ==|combos ==|== 34|GOLDEN|EXPECTED_COMPOSITION' "$R"/tools/settings-probe/VAddSetting.java "$R"/tools/settings-probe/VerifyPanel.java "$R"/tools/settings-probe/VPreset.java "$R"/tools/settings-probe/VDefaultFreeze.java "$R"/tools/settings-probe/VTabPages.java | head -50
printf '=== VUi order table head ===\n'
sed -n '1,30p' "$R"/tools/settings-probe/VUi.java
```

> 先写 `VCrossShapeExperiment` 让它红，再写生产代码让它绿。改旧探针常数只能按 AC-10 的契约变更处理，并在 REPORT 写「为什么旧断言失效」，**不许删断言**。

### 6.4 全量门禁 + D0（点名 `verification-loop` 技能；串行，只跑一份）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1 -Repo D:\deepseekwork\neovim-cursor-pr
```

```bash
R="D:/deepseekwork/neovim-cursor-pr"
git -C "$R" diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
printf 'D0_EXIT=%s\n' "$?"
```

> 若用 Bash 调 pwsh，必须写日志并保留 `PIPESTATUS` / `$?`：
> `pwsh ... > build/cross-shape-gate.log 2>&1; G=$?; grep -E 'RESULT|FAIL' build/cross-shape-gate.log; printf 'GATE_EXIT=%s\n' "$G"; test "$G" -eq 0`。
> 不许并发跑第二份门禁（共用 `build\v2-probe\classes` 会假红）。

### 6.5 变异测试（三组，恢复后全绿）

```bash
R="D:/deepseekwork/neovim-cursor-pr"
printf '=== mutation plan (write exact commands in REPORT) ===\n'
printf 'M1: 默认 false 改成 true -> AC-1 / AC-3 默认零差异断言必须红\n'
printf 'M2: 消费者删掉开关读取或恒走旧公式 -> AC-4 / AC-9 必须红\n'
printf 'M3: crossPreview 不推开关 / inlinePreview 误读开关 -> AC-9 / 单行零伤断言必须红\n'
printf '每次变异后删除 build/v2-probe/classes 全量重编译，恢复源码后再跑一次全量门禁\n'
```

### 6.6 只打包、不安装

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 -Version 0.9.135-cross-shape -OutName neovide-cursor-0.9.135-cross-shape.jar -BaseJar D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.14-conclusion.jar
```

```bash
J="D:/deepseekwork/neovim-cursor-pr/dist/neovide-cursor-0.9.135-cross-shape.jar"
ls -l "$J"
unzip -p "$J" META-INF/plugin.xml | grep -o '<version>[^<]*\|<idea-version[^>]*' | head -2
sha256sum "$J"
```

### 6.7 收尾审查（点名 `code-reviewer` / `java-reviewer` 代理，只读）

```bash
R="D:/deepseekwork/neovim-cursor-pr"
printf '=== changed files ===\n'
git -C "$R" status --porcelain
printf '=== production diff stat ===\n'
git -C "$R" diff --stat -- src/main/java src/main/resources
printf '=== forbidden files must be untouched ===\n'
git -C "$R" diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java src/main/java/com/github/yyyolo7a79/neovidecursor/settings/CursorPresets.java build/install.ps1 build/offline-build.ps1 CLAUDE.md START-HERE.md docs/CURRENT-STATE-2026-11-04.md docs/NEXT-STEPS.md docs/INDEX.md docs/BUG-LOG.md
printf 'FORBIDDEN_DIFF_EXIT=%s\n' "$?"
printf '=== no failed-model revival / debug leftovers ===\n'
grep -R -n --include='*.java' -E 'useHardSnap|leadingSnapFactor|snapAnimationLength|HALF_TRAIL|System\.out\.print|TODO|FIXME|password|token|secret' "$R"/src/main/java | head -40
printf '=== no ASK-HUMAN or stale markers in changed code ===\n'
grep -R -n --include='*.java' 'ASK-HUMAN' "$R"/src/main/java "$R"/tools/settings-probe | head -20
```

---

## 7. 禁止事项

1. 不扩大范围；清单外文件不改，需要改就停下问。
2. 不为了让门禁变绿而删断言、改断言方向（契约变更除外，且必须写理由 + 变异测试）。
3. 不改 `core/NeovideConfig.java`（D0）、不改 `CursorPresets.java` 三个内置预设数值、不改 `install.ps1` / `offline-build.ps1`。
4. 不复活三版失败模型；不引入 `useHardSnap` / `snapAnimationLength=0` / 前缘硬吸附；不把 `leadingSnap` 旧壳接回行为。
5. 不做「叠层补密 / 密度到 2 / 改 `trailSize` 量程 / 拉长动画 / 调 `glowLayers`」；不改辉光层数上限到 16。
6. 不改单行判据、单行四角时长、`slopeTicks` / `stretchRatioK` / `typingSmoothness`；不处理 Tab / 块选 / 跨行选区分类。
7. 不删个人预设、不改 `customAnimPresets`、不重置用户配置；置灰不等于清空或丢弃值。
8. 不自动安装、不自动启动 IDEA、不覆盖当前 `0.9.134-workspace` 已装 jar。
9. 不提交、不 push、不建分支、不打 tag、不开 PR、不归档会话目录。
10. 不并发跑门禁，不用 `| tail -N && ...` 吞退出码；变异后必须删 `build/v2-probe/classes` 全量重编译。
11. 不静默吞异常；`catch` 必须回滚状态 + 打 warn。
12. 不加第二个 UI 控件；本复选框已由本 PROMPT 明确授权。
13. 不猜产品观感；「开后是否更好看」「默认是否改成开」由用户实机裁决。
14. 不从 `docs/NEXT-STEPS.md` 挑其它待办开工；A 暂缓、B 已撤回、C 保留、D/E 不做不变。
15. 不写死 commit hash / 版本号 / 探针数当永恒事实；所有数字以当次命令输出为准。

---

## 8. 分支与版本

- 分支：留在 `fix/bug-016-completion-flicker`，不新建、不切换、不合并、不 rebase。
- 基线：决策会话复核时 HEAD `f43ca55`、已装 `0.9.134-workspace`；执行会话以当次 `git log -1 --oneline` / jar 内 `<version>` 为准。
- 已装版口径：当前工作区源码现场构建（文档/注释修复 + 保留 BUG-016-2）；用户已报告「无手感变化」。本轮**不安装、不回退、不重装**。
- 上游参照：0.6.0 源码在 git 历史 `d6a43fb`；PyCharm 2025.2 已装上游 0.6.0，用于实机对照，不覆盖、不回退。
- 本轮产物版本：`0.9.135-cross-shape`，只写进新打包 jar 的 `plugin.xml`；不改仓库里任何版本声明文件（`gradle.properties` 的 0.4.3 不动）。
- 新开关：默认 `false`；设备级；不进预设；不改现有 `trailSize=1.0` 等默认值。
- 远程：`fork` 是 SSH、`origin` 是 https 且不推；本轮不 push。
- 工作区：除本 PROMPT 所在目录外，决策会话复核时只有 `docs/sessions/2026-11-05-handfeel-observation/` 未跟踪；执行会话开工前重新 `git status --porcelain`，有别人的改动先停下问。

---

## 9. 报告要求

按 `docs/SESSION-PROTOCOL.md` 第四节 REPORT 模板，写到同目录 `REPORT.md`：

````markdown
# 设备级跨行/鼠标拖动形状实验开关 执行报告（2026-11-05）

> 状态：待验收 / 部分完成 / 失败
> 提交：未提交　分支：<当次 git branch --show-current>
> 产物：dist/neovide-cursor-0.9.135-cross-shape.jar（SHA256 ...）

## 1. 结论
一句话：交付了设备级实验开关 / 部分完成 / 被阻塞；不得写「手感已变好」。

## 2. 根因
功能类写「为什么用独立接管路径而不是改 trailSize / 复活硬吸附」；
引用 §1.3、上游 0.6.0 源码与三版失败记录，不照抄成「需求就是加密度」。

## 3. 改动清单
| 文件 | 改了什么 | 为什么 |
逐行列出，含新探针 / 门禁注册 / 契约常数更新 / 置灰清单 / 中英文案。

## 4. 证据
- §6.1~6.7 每条命令 + 关键输出（不是「跑过了」）。
- 门禁：`V-PROBES RESULT: ALL PASS` 与注册探针数；D0 `D0_EXIT=0`。
- 编译：37 个 .java / javac --release 21 结果。
- 打包：jar 内版本、兼容区间、SHA256。
- 变异测试：M1/M2/M3 各贴「改坏什么 → 哪条断言红 → 恢复 → 全绿」。
- AC-1~AC-15 逐条写 PASS/FAIL 与证据出处。
- 置灰清单：控件名、开启/关闭时 enabled 状态、值是否保持。

## 5. 未验证 / 未覆盖
- 没有安装 jar、没有实机 A/B、没有替用户裁决「开后是否更好看」。
- 快速鼠标拖过换行的「更实、更流畅、真实」是主观观感，列为用户验收项。
- 若现有预览/绘制架构无法做到「开后只接管跨行路径且不触碰单行」，写清停在何处、为什么。

## 6. 阻塞与偏离
- 任何没按本 PROMPT 做的点逐条写原因；没有写「无」。
- 需要改白名单外文件、或发现必须改 D0 / 预设域时，写「待用户裁决」，不自行决定。

## 7. 验收记录（用户填）
- 实机环境：IDEA 2026.1 + 已装 jar 内版本；PyCharm 2025.2 上游 0.6.0 作对照
- 同一预设、同一快速鼠标拖过换行路径，分别关 / 开对比
- 是否更像上游 0.6.0 的短平行尾？单行打字手感有无变化？置灰是否清楚？
- 结论：通过 / 不通过 / 需要下一轮 PROMPT + 现象
````

额外要求：

1. 每个数字都有命令出处；没有就写「未验证」。
2. 用户原话逐字引用，不润色；「无手感变化」只作为已装 `0.9.134-workspace` 的用户结论记录，不扩写成「新开关也不改变手感」。
3. 不贴整份门禁日志、不贴整份 `customAnimPresets`、不贴整个 `plugin.xml`。
4. 不用「同上 / 略 / 见上文」代替内容。
5. 报告写完即停；不自行建议「下一步修 X」，下一份 PROMPT 由决策会话在用户验收后另写。

---

## 10. 一句话停止线

**交付「默认关、开启完全接管跨行/鼠标拖动形状、置灰被接管参数、跨行预览同源」的版本 + 绿门禁 + 可安装 jar + 带证据的 REPORT 后停下；不安装、不复活失败模型、不替用户宣布手感变好。**
