# REVIEW-SETTINGS-V2 — 第 3 轮复审（reviewer-2 / attempt 3）

> **审查者**：reviewer-2　**任务**：t10（review-round-3）　**attempt_id**：`46b45514-13ef-41de-8fb6-3b4759b58659`
> **被审对象**：`t9`（修 t8 第 2 轮提出的 R-09 / R-10 / R-11 / R-12），并连带复核 `t7` 的 R-01/R-02/R-03 与 `t2` 的 4 个缺陷修法。
> **工作目录**：`D:\deepseekwork\neovim-cursor-pr`　**基线**：HEAD = `041794b559371ff43cbb17e9bd04bb46b7c3694d`（相对 `0e323df` 只多一个 docs 提交，`git diff 0e323df HEAD -- src/ build.gradle.kts` 为空 —— 我实测过）
> **纪律**：只审查。未改 `src/`、未改任何交付物、未装 jar、未重启 IDEA。我自建的探针放在 `build\t10-probe\`（构建产物目录，不是交付物）。
>
> **关于本文件的历史**：t10 attempt 1 曾写过一版第 3 轮报告（`31482 B / 446 行 / SHA256 3568E9F8…`）。本轮我**没有采信它**：
> 全部哈希、行号、探针、账目都是我自己重算/重跑的；过程中**纠正了它两处数值**（§8 的变异变红条数）与**一处结论口径**（§6.1 的 N-1），
> 并新增了 5 条它和实现者、verifier 都没提到的风险（§10）。前两轮结论保留在 §3。

---

## 0. 结论摘要

**t7 + t9 对 R-01 / R-02 / R-03 / R-09 / R-10 / R-11 / R-12 的修复，我逐条独立复核，全部成立。**
**契约条文全部 PASS**（唯一红项 V13 是契约判据本身不可达，三轮一致，见 §4.2）—— 因此 **verdict = `pass`**。

但本轮**不是"没找到东西"**。我另外找出 **5 条 low（其中 2 条是本任务点名要的"三方都没提到的风险点"，并且有可复跑的探针实证）**：

| id | 级别 | 一句话 |
|---|---|---|
| **R-17** | low | `restoreNativeCaret()` 在 `clear()` 时不区分"恢复成功/失败"——某个 scheme 抛一次异常，它的记录就永久丢失、光标停在透明且**无法自愈**（我的 B 组探针 4 条实证） |
| **R-18** | low | 无参 `applyNativeCaretHidden()` 的早退判据仍是**全局** `savedCaretColor != null`，而不是"当前 scheme 是否在记录表里"——"同内容不同对象"的 scheme 会静默不隐藏（我的 A 组探针 3 条实证，是缺陷 4 的同一根因形态残留） |
| **R-19** | low | 面板「跨行提示」按 **1×** 报毫秒（0.35 s → 350 ms），而同一仓库的 `VSpring` 用真实 `DampedSpring`(dt=8ms) 量到 **920 ms**；**同一交付里两个数字互相矛盾**，且与短跳提示的口径不一致 |
| **R-20** | low | `run-v-mutations.ps1` 的"变红条数/互不连带"判据用宽松 `-match 'FAIL '`，把探针自己的汇总行也算成一条 FAIL → **每个变异多报 1 条**（真实 12/11/3/4/1/2，脚本报 13/12/4/5/2/3），且"连带变红"恒有 1 条假阳性。**交付报告里的 Red 列数字不可复现** |
| **R-21** | low | 契约 §10.2 **N-1 的机械判据逐字复跑会红**：`grep -c "new NeovideConfig" CursorPresets.java` = **1**（命中 `:14` 的 javadoc），且判据路径写的是 `core/CursorPresets.java`（文件实际在 `settings/`）。"N-1 = 0"只对"排除注释"的解释成立 |

**blocker 0 / high 0 / medium 0**。5 条 low 都不阻塞 verdict，但 R-20 与 R-21 属"证据链的口径问题"，**建议 t6 收尾时必须改**，否则下一轮复审又会把这些数字当权威引用。

---

## 1. 我做了什么（全部本轮现跑，可复跑）

| # | 动作 | 结果 |
|---|---|---|
| 1 | `pwsh tools\settings-probe\run-t9-full.ps1` | **ALL T9 VERIFICATION PASSED**，11 段 exit 全 0（build / parity / audit / ime / t7 / br / v-mutations / settings-v2 / 4bugs / t4-verify / D0） |
| 2 | 重算 `Get-FileHash` 全部证据字节 | 见 §2；与 captain 记录的 4 个前缀逐字节吻合 |
| 3 | `SETTINGS-CONTRACT.md §5.3` 的 35 项脚本**原样复跑** | `禁改项总数 = 35　缺失数 = 0` |
| 4 | D0：`git diff --exit-code -- core/NeovideConfig.java` + `git status --porcelain -- core/` | exit 0 / 空 |
| 5 | 独立比对 `NeovideConfig` 22 个默认值与 `State` 18 个初值 | **逐条一致**（含 `stretchRatioK=4.5f` ↔ `trailFactors {1.0,0.72,0.42,0.22}` 的 4.5f 特例） |
| 6 | 跳过注释的 plugin.xml 解析 | applicationService 1 / applicationConfigurable 1 / editorFactoryListener 1 / group 2 / action 5 / **真实 `<separator>` = 1，带 id = 0** |
| 7 | N-1/N-2/N-3、V14、§10.4、§10.5、§10.6、D 段 | 见 §6（两处口径纠正写在 §6.1） |
| 8 | **自建探针 `build\t10-probe\T10Risk.java`（18 条断言，exit 0）** | R-09 独立复现 7 条全绿；两处残留风险 11 条实证 |
| 9 | `run-v-mutations.ps1 -Only MUT-C` 并逐行核 diff | 变异忠实（只把 17 处条件换 `if (true)`，赋值体未动）；同时暴露 R-20 |
| 10 | 从 `build\v-mut\*\*.txt` 重新点数每个变异的断言级 FAIL | 真实 12/11/3/4/1/2（脚本口径 +1，见 §8.2） |
| 11 | 从 `build\probe-t7\mut-*.txt` 重新点数 t7 的 5 个变异 | 2/1/5/1/1，与 t9 报告**完全一致**（它的正则是对的） |
| 12 | 独立跑 `VSpring`（真实 `DampedSpring`，dt=8ms） | **0.35 s → 115 帧 / 920 ms；0.50 s → 164 帧 / 1312 ms**（逐值复现契约 §7.2） |
| 13 | 重跑 `run-parity.ps1` / `run-audit.ps1` 取 A 段原文 | `ALL PARITY OK` / `DIFF 数 = 1` 且就是 `hideNativeCaret` |

---

## 2. SHA256 绑定表（全部本轮现算；**这是本报告的裁决对象**）

### 2.1 冻结输入（契约 / 证据 / 方案）

| 产物 | bytes | 行 | SHA256 | 与 captain 记录 |
|---|---|---|---|---|
| `SETTINGS-UI-CONTRACT.md`（v1.2） | 98780 | 1546 | `DBEAD2787D2DCD3A85EEC96896DF0CE2F1DC79B695F563793D0ECC31241412A7` | **吻合**（`DBEAD278…`） |
| `VERIFY-SETTINGS-V2.md` | 67808 | 1096 | `AB8AED738EAFC3D80AFDFF93605A628A00944625DECCFE2BFBA1D25E3BAB093B` | **吻合**（`AB8AED73…`） |
| `SETTINGS-UI-REDESIGN.md` | 10387 | 248 | `C188AA3B19F60D17BC0D0B939A1A712E64E26818FBADF910695BAA4B909327C1` | 三轮未变 |
| `LESSONS.md` | 26193 | 612 | `FA6E3B6DBC696E4F38F9CC93D2E9FF2B9935B3D188924FFA67D16A695D2149CA` | t9 已更新（坑 18–22） |
| `START-HERE.md` | 16935 | 343 | `2687FA94A164E92CF7144EB2815785BB861FDAFFB17984961BB580705208271D` | t9 已同步 |

### 2.2 被审源码 / 配置（本轮裁决的实际字节）

| 文件 | SHA256 | 与 captain 记录 |
|---|---|---|
| `ui/NeovideCaretManager.java`（R-09 两处修法本体） | `AC4ED7E1C92633546924EF6D515A11FCD34DFBF429BF3A001BDD1CF0BA86D72C` | **吻合**（`AC4ED7E1…`） |
| `ui/settings/NeovideCursorConfigurable.java`（R-11 / R-12 那轮 + 面板本体） | `76E578AFC879CE235B5F918BB463C215A0B2AB52FF4BF167C2AED3CF174DB9D0` | **吻合**（`76E578AF…`） |
| `src/main/resources/META-INF/plugin.xml` | `8CD0B35E33D1FE3C7DADBFD95910C7D10D0EC98568CAF2064D40D217BF68F192` | **吻合**（`8CD0B35E…`） |
| `settings/NeovideCursorSettings.java` | `4B8BBF7674B1A0F568FBF69B0963A0D50DB03C331CEBE473816F43C079451232` | t9 未动 |
| `settings/CursorPresets.java` | `5F5325227B8C676034A200E34B0C0604CDEA8E2273D1AAFD9C52AC306F908355` | — |
| `ui/CursorPresetApplier.java` | `1298D295DA51C25E235B108554EF5BEC755A09D7746EDC5C254F1F121CE3EB22` | — |
| `ui/CaretSchemeWatcher.java`（缺陷 4 的监听器） | `C1F75533B5C434C3CD41879A0D3185D6BC5C0E8AE0FF19BD318A87C5FD399A92` | — |
| `ui/AnimPresetGroup.java` / `ui/ColorPresetGroup.java` | `49272E3D…6177337` / `B7FD7D9B…ADD5ABCA` | — |

### 2.3 探针 / 脚本 / 我自建的证据

| 文件 | SHA256 |
|---|---|
| `tools/settings-probe/ProbeT7.java` | `6A6D30E31489A06820A4C5C8411ADEBF3FF8DF613DF11D969231C45D4976FC60` |
| `tools/settings-probe/run-v-mutations.ps1` | `1D2A8334AD1D2EE88D7670D3068B03EDCBB899B04F1446736FF3A76AA3D3CE2F` |
| `tools/settings-probe/run-t7-probe.ps1` | `AA960ACCD2F035C4B86AB4E7FFCAEC9127C90DBB3F632677EC8BEFD0964A057A` |
| `tools/settings-probe/ProbeBrRendering.java` / `run-br-probe.ps1` | `B901C8D8…6C6D36EA` / `99CDB585…4B4D274466` |
| `tools/settings-probe/run-t9-full.ps1` | `9E018D9ACDDEBB6FA99519E785AB15F5A4812AF4B7D7A2AB528DF8BF4155D278` |
| `tools/settings-probe/VSpring.java` / `VOrder.java` / `VHints.java` | `7A9727A1…B00AFE46` / `BF4E5B04…7BB5A476` / `37D99AAF…A5D9E807` |
| **我自建 `build/t10-probe/T10Risk.java`** | `2283AB39DEC843E34F9CD070CFD232E01E3AB3DE602237E9EEE04E67C97F507F` |
| **我自建 `build/t10-probe/run-t10-probe.ps1`** | `A5FFF3D6B67FE7DFBABEDBB3471ABF1766BDD52C6DDF81D348DF732C5AC9F7D6` |

> 结论：**本报告裁决的字节与 captain 记录、与 t9/t7 声明的改动一一对应**。没有"报告与磁盘不一致"的情况。
> `tools/frame-probe/run-frame-compare.ps1` = 被审改动之一（t7 修 `BaseRef`），其内容我读过（`:37` `BaseRef = '0e323df'`）。

---

## 3. 前两轮 finding 台账（保留）+ 本轮复验

| id | 级别 | 提出轮 | 描述 | 本轮复验 |
|---|---|---|---|---|
| R-01 | medium | R1 | 缺陷 3「第二次 apply 冲掉 Tools」 | **PASS**（MUT-R01 红 2：T-01b/T-01e，我真跑） |
| R-02 | medium | R1 | 预设下拉不刷新（显示层撒谎） | **PASS**（MUT-R02 红 1：T-02b） |
| R-03 | medium | R1 | 切主题后旧 scheme 不恢复 | **PASS**（MUT-R03 红 5） |
| R-04 | low | R1 | §8.4 分隔符未实现 | **PASS**（裸 `<separator>`，plugin.xml:137） |
| R-05 | low | R1 | §7.4 逐字 / §7.3 条件警告 | 判契约过严，不要求改（§4.2） |
| R-06 | medium | R1 | §7.1 量程 vs §9.4 文案矛盾 | **PASS**（两边界文案，Configurable:149-155） |
| R-07 | low | R1 | 帧率基线标错 | 代码侧已改（`0e323df`）；文档侧仍开（= R-15） |
| R-08 | low | R1 | CursorPresets 位置 | captain 已裁，契约该改 |
| **R-09** | medium | R2 | 切回已隐藏 scheme 覆盖原色（t7 引入） | **PASS —— 我用自己写的探针（D 组）独立复现，见 §7.1** |
| R-10 | medium | R2 | `run-v-mutations.ps1` 锚点失效 | **PASS**（6 个变异全跑通，exit 0） |
| R-11 | low | R2 | W_SHORT_LEN 多行被折叠 | **PASS**（6 个 `<br/>`；br-probe 4/0） |
| R-12 | low | R2 | `<separator>` 带 id 无先例 | **PASS**（跳过注释后：真实 separator 1、带 id 0） |
| R-13 / R-15 | low | R2/R3 | `VERIFY:740` 仍写 `8906609` | **仍未做**（属 t6，我复核仍在，见 §9） |
| R-14 | low | R3 | 顶层 `<separator>` 无平台先例 | 已知未验证面，只能真机确认，不阻塞 |
| R-16 | low | R3 | HTML 空白规范化教训未入 LESSONS | 待 t6 |
| **R-17…R-21** | low | **R3（本轮新增）** | 见 §10 | 都是新的，都有实证 |

---

## 4. 契约逐条裁决表（PASS/FAIL + file:line）

### 4.1 条文表

| 条文 | 强度 | 裁决 | 证据（file:line，当前字节） |
|---|---|---|---|
| §1 缺陷 1（三条修法缺一不可） | 【必须】 | **PASS** | `NeovideCursorConfigurable:700-713`（显式 ChangeListener）/ `:688-695`（getter snap）/ `:1224-1252`（isModified 三半） |
| §1.3 修法 3 与 B-2 对向陷阱 | 【必须】 | **PASS** | `:736-740` `snapToTick`；MUT-F 红 2（V11-3c / V11-4b） |
| §2 缺陷 2（min/mid/max 三刻度） | 【必须】 | **PASS** | `:646-658`；MUT-B 红 11（labelTable 变 11/41/21…、paintTicks=true） |
| §2.3 禁止 showValueHint | 【禁止】 | **PASS** | 全 `src` grep `showValueHint` = **0**；D1-2「listener 恰 1」 |
| §3 缺陷 3（**逐字段**脏标记） | 【必须】 | **PASS** | `:1107-1162`（17 处逐字段 if，**不是** `if(dirtyAny字段) 整份替换`） |
| §3.4 apply 后 pending/baseline 同源前移 + 控件回灌 | 【必须】 | **PASS** | `:1172`（A7 `pending = deepCopy(target)`）+ `:1197-1203`（A8 `super.reset()` + `syncAllControlsFromPending()`） |
| §4 缺陷 4（force 重入 + TOPIC 订阅） | 【必须】 | **PASS** | `NeovideCaretManager:661-690`；`CaretSchemeWatcher:61-88`（`bus.connect(this)` + `subscribe(TOPIC)`）/`:112-124`（两条早退 + force） |
| §4.2 恢复**所有**历史 scheme | 【必须】 | **PASS** | `:773-835` 按 `HIDDEN_SCHEMES` 逐个 `setColor` |
| §4.2 已记录的 scheme 不得重取 | 契约缺（t8 判） | **PASS** | `:682` `putIfAbsent`；`:817` `currentHandledByMap ? null : savedCaretColor`；`:785-794` 判据在 `clear()` **之前**取（`:811` 才 clear） |
| §4.4 禁止轮询 | 【禁止】 | **PASS** | `src` 全量 `getSchemeModificationCounter` = **1**，且那一处是 `FakeColorsManager` 之外的接口适配（`CaretSchemeWatcher` 零引用轮询语义） |
| §5.1 三层真相源 | 【必须】 | **PASS** | `NeovideCursorSettings:300-327`（State→CONFIG 单向拷贝，新建 `trailFactors` 数组） |
| §5.2 **P-1** 预设不持久化 | 【必须】 | **PASS** | `State` public 实例字段 = **19**（我逐行数）；`CursorPresets` 零 `State` 引用 |
| §5.2 **P-2** 实时推导当前预设 | 【必须】 | **PASS** | `:946-961` + 4 个入口（`:712` / `:788` / `:813` / `:901-912`） |
| §5.2 **P-3** 表形状（纯常量） | 【必须】 | **PASS** | `CursorPresets` 全 `static final`；`NeovideConfig` 零 diff |
| §5.2 **P-4** 预设 1 = 默认值 | 【必须】 | **PASS** | `CursorPresets:146` = `(10,11,100,8,20,55)` → 0.10/0.11/1.00/8/0.20/0.55，与 `NeovideConfig` 逐字一致 |
| §5.5 7 动画预设逐值 | 【必须】 | **PASS** | `CursorPresets:144-153`（我逐个与方案 §4.1 对过） |
| §5.6 5 颜色预设逐值（大写） | 【必须】 | **PASS** | `CursorPresets:205-211` |
| §5.7 「自定义」不写字段 | 【必须】 | **PASS** | `:853-861` / `:874-882`（越界即只 `refreshPresetDisplay()`） |
| §6 吸附 + 双向绑定 | 【必须】 | **PASS** | `:644` `setSnapToTicks(true)`；`:745-814` 双向；`:736-740` 吸附 |
| §6.4 抑制标志 | 【必须】 | **PASS** | `:352` / `:701` / `:759` / `:772-780` / `:818` |
| §6.5 非法值三档 | 【必须】 | **PASS** | `:758-789`（键入不 clamp）/ `:792-814`（失焦 clamp + 回写） |
| §7.1 范围与持久化 | 【必须】 | **PASS** | `NeovideCursorSettings:77-87`（0.05–0.35 / 0.02–0.25）+ `:142-144` clamp + `:349-350` 回写；滑块 `:243-249`（5–35 / 2–25） |
| §7.2 上限量化依据 | 【必须】 | **PASS** | `VSpring` 我自己跑：0.35→115 帧/920ms、0.50→164/1312ms（见 §9.1） |
| §7.3 恒显示文案 | 【必须】 | **PASS** | `:158-159` |
| §7.3 两条条件警告 | 【建议】 | 未做（allow） | 判定同前两轮（§4.2） |
| §7.4 动态耦合提示（禁写死） | 【必须】 | **PASS** | `:993-1015`（读 `pending`）；**但口径存疑 → R-19** |
| §7.4 逐字措辞 | 【必须】 | 偏离（允许） | `:998-1008` 见 §4.2 |
| §7.5 禁止 animationResetThreshold | 【禁止】 | **PASS** | `ui/settings` grep = 0 |
| §8.1–§8.3 / §8.6 Tools 子菜单 | 【必须】 | **PASS** | `AnimPresetGroup:26-31` / `ColorPresetGroup:16-21`（`extends DefaultActionGroup` + 构造器 `add()`）；写入口唯一 `CursorPresetApplier` |
| §8.4 菜单顺序 + 分隔符 | 图示/必须 | **PASS** | plugin.xml `:137-139`（裸 separator）夹在 `:49-99` 的 5 个 action 与 `:141-153` 的两个 group 之间；全部 `anchor="last"` 按文档顺序追加 |
| §8.5 plugin.xml 片段 | 【必须】 | **PASS** | `:141-153`（两个 group，`popup="true"`，**无内嵌 action**） |
| §9.1–§9.3 布局 / collapsibleGroup / 间距 | 【必须】 | **PASS** | `:429-621`（预设置顶 `:437-447`；`collapsibleGroup` `:568` `true` / `:584` `false`；每组首行 `topGap(MEDIUM)`） |
| §9.4 文案（含 `<br/>` 分行） | 【必须】 | **PASS** | `:91-159`；`BR` 常量 `:147`，`W_SHORT_LEN` 6 个 `<br/>` `:149-155`；br-probe 4/0 |
| §10.1 D0/D1/D2 | 【禁止】 | **PASS** | §6.1 |
| §10.2 N-1/N-2/N-3 | 【禁止】 | **PASS（口径修正，见 §6.1）** | N-1 逐字复跑读到 1 → **R-21** |
| §10.3 三件套 | 【必须】 | **PASS** | parity 逐字节 / audit DIFF=1 且 `hideNativeCaret` / ime 29-0（我重跑） |
| §10.4 不做项 | 【禁止】 | **PASS** | getCarets 0 / shadowColor2 0 / @Composable 0 / AfterimageTrail+build.gradle.kts 零 diff |
| §10.5 ApiStatus 支路 | 【禁止】 | **PASS** | `ui/settings` 里 onChanged 0 / cellValidation 0 / validationInfo 0 |
| §10.6 脚本安全 | 【必须】 | **PASS** | 27 个 `.ps1`，自动变量赋值命中 **0** |
| §11 V1–V15 | — | **14/15** | 唯一 FAIL = V13（契约判据不可达，§4.2） |

### 4.2 两处"红但不算实现错"的裁决（与前两轮一致，我复核后维持）

**V13（构建/回调顺序）**：契约要求 `getApplyCallbacks()` 里每行 `slider` 在 `textField` 之前；实测 `slider-先 0 / textField-先 10`
（`VOrder` 打印；根因：`bindValue` 的调用点在 `intTextField` 之后，`NeovideCursorConfigurable:661-695`）。
**真正保护"精确值不被覆盖"的是 §1.3 的修法 3（getter 返回 `snap(pending)`）** —— 决定性证据是
**MUT-F（摘掉 snap）→ V11-3c 与 **V11-4b**（`apply()` 把 333 原样落库）同时变红**。所以 V13 的红是**契约判据错**，不是实现错。
> 补充（我本轮新注意到）：`VOrder:110-112` 的 O-3 是 `check(name, true, ...)` —— **恒真断言**，本质是注释。不影响 O-2 的有效性，但"O-3 PASS"不能当证据引用。

**§7.3 两条条件警告 / §7.4 逐字措辞**：三轮一致判"契约过严"（`:121` 的 `W_ANIM_LEN` 与 §7.4 的逐字模板不一致，但语义等价且含运行时数字）。维持。

---

## 5. 4 个 bug 的**根因级**复核（不是"探针绿了就算"）

| 缺陷 | 根因（契约） | 我核到的实现 | 反证（变异） | 裁决 |
|---|---|---|---|---|
| **1 滑块不跟手** | `SliderKt.bindValue` **不挂** `ChangeListener`（A-1 实测 listener=0）→ 拖动只改组件值 | `:700-713` 显式 `addChangeListener`，写 `pending` + 刷 label + 同步输入框 + 刷预设显示；**恰好 1 个** listener | MUT-A（删整段）→ **12 条断言红**，10 个滑块 label 全不动、listener=0 | **根因级修复成立** |
| **2 刻度挤压** | `RowImpl.slider` 硬编码 `setPaintLabels(true)`，labelTable 条目 = `(max-min)/major+1`（51/101/201） | `:646-658` 自建 3 条目表 + **显式** `setPaintLabels(true)` + `setPaintTicks(false)`，mid 对齐 step | MUT-B（整条网格）→ **11 条红**，labelTable 变 11/41/21/12/12/21/15/31/24 | **成立**（"labelTable 不会替你开 paintLabels"这一条我核了源码顺序：`SliderKt.labelTable` → `setPaintLabels(true)`，顺序正确） |
| **3 面板覆盖 Tools** | `apply()` 无条件全量写 18 字段 | `:1107-1162` **逐字段** `pending vs baseline`；float 用 `Float.compare`、HEX 用 `equalsIgnoreCase`；A7+A8 | MUT-C → **3 条红**（D3-1/D3-2/D3-4）；**D3-3「用户改动生效」仍绿** | **成立**（语义边界 §3.5 我也核了：改回原值 + Tools 也改 → 判"没改过"→ Tools 胜，写在 `:1076-1078`） |
| **4 切主题光标复活** | `if (savedCaretColor != null) return;` 早退 + 全仓 0 处 `EditorColorsListener` | `:663` `!force &&`；`:669` force 时重取；`CaretSchemeWatcher` 订阅 `EditorColorsManager.TOPIC`；`:151-153` 排在一切早退之前 | MUT-D（摘 `!force &&`）→ **4 条红**（S-2/S-2b/S-3/S-4d）；R-09 两处另有 MUT-R09a/b 各红 1 | **成立**；**残留同形问题 → R-18** |

---

## 6. 机械判据（我自己跑，含两处口径纠正）

### 6.1 表格

| 判据 | 命令 | 我的结果 |
|---|---|---|
| **D0** | `git diff --exit-code -- core/NeovideConfig.java` | **exit 0**；`core/` 也无新增 |
| **D1** | `SETTINGS-CONTRACT.md §5.3` 脚本**原样** | **35 项 / 缺失 0** |
| **D2** | `run-audit.ps1` A 段 | **DIFF = 1**，且就是 `public boolean hideNativeCaret = true;`（其余 18 项 SAME） |
| 默认值一致性（D0 的补充，我自己加的） | 逐条比对 `NeovideConfig` 22 个默认值 vs `State` 18 个初值 | **全一致**（含 `trailFactors {1.0,0.72,0.42,0.22}` ↔ `stretchRatioK=4.5f` 特例） |
| **N-1** | `grep -c "new NeovideConfig" CursorPresets.java` | **读到 1**（`:14` 的 javadoc 里写了 `new NeovideConfig()`）→ 逐字判据红 → **R-21**；"排除注释后 = 0" 成立 |
| **N-2** | `SHORT_ANIMATION_LENGTH = ` | **0** |
| **N-3** | `State` 里 `0.1f`/`0.11f` | 代码行 **各 1 次**（`:653` / `:663`）；**但含注释共 3 次 / 2 次**（`:642,644` 是 javadoc）→ 见 R-21 同族提醒 |
| **V14** | `State` public 实例字段 | **19**（`:583`…`:663`，我逐行数，不含 `static final` 常量） |
| §10.3 **D 段** | 死字段 grep | 总数 **37**，排除 `settings\` 与 `ui/settings\` 后 = **12**（NeovideConfig 4 + CaretAnimator 4 + CaretTrailPanel 2 + NeovideCaretManager 2）→ **与契约要求的 12 一致**（契约基线写总数 32，现 37 是 t7/t9 在面板侧新增的引用；**被约束的那个数不变**） |
| §10.4 | grep 4 类 + 2 个零 diff | 全 **0** / exit 0 |
| §10.5 | `ui/settings` grep | **0 / 0 / 0** |
| §10.6 | 27 个 `.ps1` | 自动变量赋值 **0** 命中；`$repoRoot`/`$tmpRoot` 均在用 |
| **EP** | plugin.xml（**跳过注释**） | service 1 / configurable 1 / editorFactoryListener 1 / group 2 / action 5 / **真实 separator 1、带 id 0** |

> 两个口径陷阱（我自己踩过，记下来）：
> 1. `[regex]::Replace($x,'<!--.*?-->','')` **不带 `Singleline`** 不会跨行匹配 → separator 会被数成 3（1 真 + 2 注释）。带 `Singleline` 后才是 1。
> 2. "N-1 = 0 / N-3 = 2" 这两条只在**排除注释**的解释下成立。契约原文的 `grep -c` 是逐字匹配 → 见 R-21。

---

## 7. 我的独立探针（`build/t10-probe/T10Risk.java`，18 条断言，exit 0）

> 设计要点：**真实原色与 `getDefaultForeground()` 故意不同**（`#A9B7C6` vs `#00FF00`），否则"恢复真原色"与"被兜底覆盖"无法区分 —— 这是 t8/t9 那条断言能成立的关键，我在自己的探针里同样保留。

### 7.1 D 组 —— R-09 独立复现（**7/7 PASS，R-09 确实修好了**）

```text
D1  在 A 上隐藏后 A 变全透明                                 PASS
D1b 记录数=1                                                PASS
D2  切到 B(force) 后 B 变全透明                              PASS
D2b 记录数=2                                                PASS
D3  切回 A(force) 后 A 仍透明                                PASS
D3b savedCaretColor 被重取为【兜底色 #00FF00】               PASS  ← 这就是 (a) putIfAbsent 必须存在的原因
D4  取消勾选后 A 恢复到【真实原色 #A9B7C6】(不是 #00FF00)     PASS  ← R-09 决定性
D4b B 恢复到它的原色 #FF0000                                 PASS
D4c 恢复后记录表清空                                         PASS
```

**判定**：t9 的两处修法（`putIfAbsent` + 兜底段 `currentHandledByMap` 跳过）**都真的需要，且都已生效**；
我的探针与 t9 的 `T-03i-*` 形状不同（我是三步：隐藏/切换/切回，直接断言 `Color` 值），结论一致 —— **不是"看别人的探针绿了"**。

### 7.2 A 组 —— **R-18 实证**（3/3，风险成立）

```text
A1  首次隐藏成功                                             PASS
A2  无参版本在「同内容不同对象」的 scheme 上静默失效（光标仍可见） PASS  ← 风险复现
A2b 记录表没有收进这个新对象（count 仍 = 1）                    PASS
A3  对照：同一对象上 force=true 立即修好（写路径没问题，问题在判据） PASS
```

### 7.3 B 组 —— **R-17 实证**（4/4，风险成立）

```text
B1  两个 scheme 都隐藏（记录 2）                               PASS
B2  好的那个正常恢复                                           PASS
B3  抛异常的那个仍停在全透明（没恢复）                          PASS ← 风险复现
B3b 它的记录被 clear() 一起删掉（count=0）                      PASS ← 关键
B4  异常消失后再调 restore 也救不回来（无法自愈）               PASS ← 关键
```

> 复跑：`pwsh -NoProfile -ExecutionPolicy Bypass -File build\t10-probe\run-t10-probe.ps1`
> （编译整棵 `src/main/java` + 我的探针，`-Djava.awt.headless=true`，假 `idea.home.path`；不需要那三个桩，因为我只驱动 `NeovideCaretManager`/`NeovideCursorSettings`。）

---

## 8. 变异测试忠实度审查（含**账目纠正**）

### 8.1 形态忠实度（逐个核 diff，不是看结论）

| 变异 | 声明的回滚 | 我看到的 diff 形态 | 忠实？ |
|---|---|---|---|
| MUT-A | 删 `addChangeListener` 整段 | 只删 `:700-713` 那 14 行，保留 `snapToTick`/labelTable | ✔ |
| MUT-B | 三刻度 → 整条网格 | 只换 labelTable 构建段 + `paintTicks(true)` | ✔ |
| MUT-C | 17 处脏标记 → 恒真 | **只替换条件表达式**，赋值体一字未动（我 `-Only MUT-C` 现跑逐行看过） | ✔ |
| MUT-D | 摘 `!force &&` | 单行替换 | ✔ |
| MUT-E | isModified 去掉第三半 | 只删 `|| dirtyAnyField(...)` | ✔ |
| MUT-F | getter 不 snap | 只改 `bindValue` 的 getter lambda | ✔ |
| MUT-R01 | 删 `pending = deepCopy(target)` | 单行（A8 保留） | ✔ |
| MUT-R02 | 删滑块监听器里的 ④ 段 | 删 5 行，保留 `refreshDerivedHints()` | ✔ |
| MUT-R03 | 挖空"按记录恢复"循环体 | 循环体换成 1 行注释，`clear()` 保留 | **方向忠实，但形态不是"逐字还原修复前"**：它同时保留了 R-09 的 `(b)` 判据，于是变异体"一个都不恢复"（比修复前更极端）。**结论方向不受影响**（探针仍按期望变红），但按契约 §11.1「逐字一致」的严格口径，这条应标注为"近似还原" |
| MUT-R09a / R09b | 单行回滚 | 单行替换，各自独立 | ✔（**各自都能让同一条 `T-03i-4` 变红** —— 这是"(a)(b) 缺一不可"的决定性证据，我认） |

### 8.2 **账目纠正（R-20）**：变红条数被脚本自己多算了 1

`run-v-mutations.ps1:248` 用 `$_ -match 'FAIL '`（宽松）统计变红，而探针的汇总行
`=== V-DEFECTS 结果：PASS 16，FAIL 3 ===` **也含 "FAIL "** → 每条变异多算 1，并让 `:281-300` 的"互不连带"永远至少报 1 条假连带。

我从 `build\v-mut\*\*.txt`（脚本自己落的盘）重新点数 **断言级**的输出：

| 变异 | 脚本/交付报告写的 | **真实断言数** | 真实变红的断言 |
|---|---|---|---|
| MUT-A | 13 | **12** | 10 个滑块明细 + D1-1 + D1-2 |
| MUT-B | 12 | **11** | 10 个滑块明细 + D2-1 |
| MUT-C | 4 | **3** | D3-1 / D3-2 / D3-4 |
| MUT-D | 5 | **4** | S-2 / S-2b / S-3 / S-4d |
| MUT-E | 2 | **1** | V11-1 |
| MUT-F | 3 | **2** | V11-3c / **V11-4b** |
| (t7) R01/R02/R03/R09a/R09b | 2/1/5/1/1 | **2/1/5/1/1** | 脚本用的是 `'^\s+FAIL\s'`，**是对的** |

**影响**：不影响任何一条 finding 的成立（每个变异都确实按期望变红），但**交付报告与上一轮复审里的 Red 列数字全部 +1、不可复现**。
`VERIFY-SETTINGS-V2.md` 若引用了这些数字，需要一并订正。

---

## 9. 帧率 / 时长数据的可信度

### 9.1 §7.2 的"上限 0.35"量化依据 —— **可复现，可信**

我自己编译并运行 `VSpring`（驱动**真实** `core/DampedSpring`，`dt = 8 ms`，初始位移 32 px，数到静止）：

```text
animLen  运动帧数  运动时长
0.10     32        256 ms
0.28     92        736 ms
0.35     115       920 ms      ← 契约 §7.2 的 115 帧 / 920 ms
0.50     164       1312 ms     ← 契约 §7.2 的 164 帧 / 1312 ms
```

**结论**：这两个数字**不是拍的**，能逐值复现；`0.35` 与 `0.50` 差 49 帧，量化意义成立。
**但**：它同时暴露 **R-19** —— 同一份交付里，面板的"跨行提示"把 0.35 s 报成 **350 ms**，而这里同一颗弹簧是 **920 ms**。

### 9.2 V10「帧率不回归」—— 本轮**未重跑**，理由与边界

- 热路径 5 个文件（`CaretAnimator` / `RenderScheduler` / `CaretTrailPanel` / `DampedSpring` / `TrailCorner`）在本批**逐字节未变**（`git diff 0e323df HEAD -- src/` 为空，我实测），t9 改的是配色与 UI → 不重跑帧率有依据。
- V10 的口径仍是**离线 EDT 调度测量**（p50/p95/p99），**不含真实渲染后端 / GPU** —— verifier 自己已声明（`VERIFY:1061`），我认可这个边界。
- **遗留 R-13/R-15 仍在**：`VERIFY-SETTINGS-V2.md:740` 仍写"默认 `8906609`"，而 `run-frame-compare.ps1:37` 已是 `0e323df`。属 t6，不阻塞。
- 一处**代码卫生**（不单列 finding）：`run-frame-compare.ps1:56-57` 取了 `$headSubject` 却从未使用（本意应是打印"当前 HEAD"）；`:96` 的标题里 `$BaseRef` 写成了单引号字符串 → 打印出来是字面 `$BaseRef`。**不影响测量值**，但会让日志少一条基线身份，正好是 R-07 那类"基线标错"最容易复发的地方。

---

## 10. 本轮新发现（5 条 low，全部可复跑）

### R-17（low）`restoreNativeCaret()` 的 `clear()` 不区分恢复成功/失败 → 一次异常 = 永久隐形光标

- **problem**：`NeovideCaretManager:797-812` 在同一个 `synchronized` 块里"逐个 `setColor` + 无条件 `clear()`"，单条失败被 `catch` 吞掉后**记录一起被删**；随后 `:828` `savedCaretColor = null`。于是那个 scheme 永久停在全透明，且**再调多少次 `restoreNativeCaret()` 都救不回来**（我 B4 实证）——这正是缺陷 4 当初"无法自愈"的同一种伤害形态。
- **requiredFix**：把 `any = true` 的成功判定用于清理：只 `remove` 恢复成功的条目，失败者保留（`HIDDEN_SCHEMES` 本就是 `synchronizedMap`，用 `entrySet().iterator().remove()`）；`savedCaretColor` 仅在"记录表已空"时置 null，否则保留以便下次重试。
- **file**：`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/NeovideCaretManager.java` **line**：811（`HIDDEN_SCHEMES.clear()`）
- **证据**：`build/t10-probe/T10Risk.java` B1..B4（4/4 复现）；本报告 §7.3

### R-18（low）无参 `applyNativeCaretHidden()` 的早退判据仍是全局 `savedCaretColor`，不是"当前 scheme 是否已记录"

- **problem**：R-09 之后，"哪些 scheme 被改过"的权威是 `HIDDEN_SCHEMES`（IdentityHashMap），但 `:663` 的早退仍问 `savedCaretColor != null`（一个**全局**标志）。两者一旦分叉——最典型的是 `getGlobalScheme()` 给回一个**内容相同、对象不同**的 scheme（`restoreNativeCaret` 自己的 javadoc 就承认"还可能是克隆体"）——无参路径（`install()` 每建一个编辑器都会走 `:272-276`，`setEnabled(true)` 也走 `:614-618`）就会**静默不隐藏**，而插件以为自己还隐藏着 → **双套绘制**（就是缺陷 4 的现象）。
- **为什么不是"重复缺陷 4"**：正常切主题路径上有 TOPIC → force，不会踩；我这条是**最后一道防线**（监听器缺失/未安装/对象身份变化时）失效。所以判 low。
- **requiredFix**：`:663` 改成按 scheme 判定 ——
  ```java
  EditorColorsScheme cur = EditorColorsManager.getInstance().getGlobalScheme();
  if (!force && HIDDEN_SCHEMES.containsKey(cur)) return;   // 已按 scheme 记录过
  ```
  并把 `savedCaretColor` 降级为"仅用于兜底恢复"的缓存（它的语义已经被记录表取代）。
- **file**：同上 **line**：663
- **证据**：`build/t10-probe/T10Risk.java` A1..A3（3/3 复现）

### R-19（low）面板「跨行提示」按 1× 报毫秒，与实测拖尾时长（2.6×）及 §7.2 自证数字矛盾

- **problem**：`NeovideCursorConfigurable:1002-1008` 报"跨行动画时长：后缘恒 = `animationLength×1000` ms"。但渲染侧 `TrailCorner:227-234` 把这个值交给 `DampedSpring`（`o = 4/animationLength`），实测运动时长是 **2.63×**：0.35 s → **920 ms**（115 帧，见 §9.1）。于是同一份交付里：面板说 350 ms、契约 §7.2 与 `VSpring` 说 920 ms。**同一段代码的短跳提示用的却是真实系数公式**（`× trailFactors[rank]`），两条提示口径不一致。用户按面板数字判断"会不会看到上一个位置还在拖"会被低报 ~62%。
- **requiredFix**（择一，建议 b）：
  (a) 文案改成"参数值（DampedSpring 时间常数）；实际拖尾约为其 2.6×"；
  (b) 直接报**实测等效时长**：`Math.round(animationLength * 1000f * SPRING_SETTLE_FACTOR)`，并把 `SPRING_SETTLE_FACTOR` 用 `VSpring` 量出的 2.63 固化成一个**常量 + 注释 + 探针**（注意：这又会引入新的"写死数字"，需同时修改契约 §7.4）。
- **file**：`NeovideCursorConfigurable.java` **line**：1003-1007
- **证据**：我跑的 `VSpring`（§9.1）+ `TrailCorner:227-234` + `DampedSpring:57-64`；`VHints` H-4/H-6 只验证了"与 1× 公式一致"，**没有和真实时长对照**，所以三方都没发现。

### R-20（low）`run-v-mutations.ps1` 的变红/连带判据把探针汇总行也算成 FAIL

- **problem**：`:248` `$_ -match 'FAIL '`；探针汇总行 `=== V-DEFECTS 结果：PASS 16，FAIL 3 ===` 命中 → 每个变异 **+1 假变红**，`:281-300` 的"互不连带"**恒报至少 1 条假连带**。交付报告与上一轮复审的 Red 列（13/12/4/5/2/3）**均不可复现**，真实是 12/11/3/4/1/2。
- **requiredFix**：与 `run-t7-probe.ps1:150` 对齐，用 `'^\s+FAIL\s'`（只匹配断言行）；连带核查另加一条"必须出现在期望集合里"的正向断言。
- **file**：`tools/settings-probe/run-v-mutations.ps1` **line**：248、286
- **证据**：§8.2 的对照表；`build/v-mut/*/*.txt` 原始输出

### R-21（low）契约 §10.2 的 N-1/N-3 机械判据逐字复跑会红（实现侧只需改一个 javadoc 措辞）

- **problem**：
  (a) `grep -c "new NeovideConfig" CursorPresets.java` = **1** —— `CursorPresets.java:14` 的 javadoc 里写了 `new NeovideConfig()`；
  (b) 判据写的路径是 `core/CursorPresets.java`，文件实际在 `settings/CursorPresets.java`（逐字复跑会 `找不到路径`，而不是 0）；
  (c) 同族的 N-3（`State` 里 `0.1f`/`0.11f` 各 1 次）**含注释**时读到 3 / 2。
  于是"V15 = 4/4 / N-1 = 0"只在"人工排除注释"的解释下成立 —— 而契约的原话是机器判据。上一个 attempt 的报告直接写"N-1 = 0"，**读数错了**。
- **requiredFix**：实现侧把 `:14` 的 `{@code new NeovideConfig()}` 改成 `{@code new NeovideConfig}`（去掉括号）或改写措辞；契约侧把 N-1/N-2/N-3 的判据统一加 `| Where-Object { $_.Line -notmatch '^\s*(\*|//)' }`，并把路径改成 `settings/CursorPresets.java`。
- **file**：`src/main/java/com/github/yyyolo7a79/neovidecursor/settings/CursorPresets.java` **line**：14
- **证据**：§6.1 的 N-1/N-3 行

---

## 11. verdict

> ## `pass`
>
> 判定依据（任务书：「只有全部条文 PASS 且无 blocker/high 才能给 pass」）：
> 1. **契约条文全部 PASS**：§1–§10 的【必须】条文我逐条核了 `file:line`，全绿；唯一红项 **V13** 是**契约判据不可达**（构建顺序 ≠ 回调注册顺序；真正的保护是 getter snap，MUT-F 实证），三轮一致判为非实现错。
> 2. **R-01/R-02/R-03/R-09/R-10/R-11/R-12 全部修复成立**，其中 **R-09 我用自己写的探针独立复现**（不是采信 t9 的 `T-03i-*`），并且 `putIfAbsent` 与兜底判据**各自必要**这一点我认（MUT-R09a/b 各红 1）。
> 3. **机械判据全过**：D0 exit 0、D1 35/0、D2 DIFF=1 且是 hideNativeCaret、N-2 = 0、V14 = 19、D 段排除 settings 后 = 12、§10.4/§10.5/§10.6 全 0、EP 正确（真实 separator 1 且无 id）。
> 4. **三件套 + 11 段回归 exit 全 0**（我现跑 `run-t9-full.ps1`）。
> 5. **无 blocker / 无 high / 无 medium**；本轮 5 条新 finding 全是 low，其中 R-17/R-18 有自建探针实证，R-20/R-21 是**证据链口径**问题（会误导下一轮），R-19 是**用户可见的文案与实测不符**。
>
> **这条 verdict 的边界（必须一起读）**：
> - 仍是**离线探针 + 字节码/源码静态分析**的结论；**不含**真机 IDEA 渲染、**不含**真机 GPU 帧率、**不含**配色方案真实落盘时机。
> - **顶层 `<separator>`（R-14）在 381 个 jar 里 0 先例**，只能装机后第一次打开 Tools 菜单确认；若没显示，按 t9 写的补救方案改契约，**不要回来改代码**。
> - R-17 的触发前提是"某个 scheme 的 `setColor` 抛异常"，R-18 的前提是"平台交回同内容不同对象的 scheme" —— 两者都不是日常路径，**所以是 low 而不是 medium**；但它们都是**零覆盖区**，建议 t6 收尾时至少补一条探针进 `tools/settings-probe/`。

---

## 12. 给 t6 的交付前清单（不阻塞 verdict）

| 优先级 | 项 | 归属 |
|---|---|---|
| **P1** | **R-20**：`run-v-mutations.ps1` 判据改 `'^\s+FAIL\s'`，并把 `VERIFY` / 本报告 §8.2 的真实条数（12/11/3/4/1/2）同步订正 | t6 |
| **P1** | **R-21**：`CursorPresets.java:14` 去掉 javadoc 里的 `()`；契约 N-1/N-2/N-3 判据加"排除注释"与路径订正 | t6（须同时改契约，属契约订正清单） |
| P2 | **R-19**：跨行提示的口径（文案或系数），并同步 §7.4 | t6 + 契约 |
| P2 | **R-17 / R-18**：按上文 requiredFix 各改 3–5 行 + 各 1 条探针 | 下一轮 repair |
| P2 | R-15：`VERIFY:740` 的 `8906609` → `0e323df`；V10 措辞同步 | t6 |
| P2 | R-16：把「HTML 渲染组件做文本断言前先规范化空白」写进 `LESSONS.md` | t6 |
| P3 | 契约订正 6 条（§3.4 补 A7+A8；§4.2 补"已记录不得重取"；V13 改行为判据；§7.4 由逐字降为"含三个运行时数字"；§7.3 两条条件警告改可选；§8.4 分隔符正文说明） | t6 |
| P3 | `run-frame-compare.ps1` 的 `$headSubject` 未使用 + `:96` 单引号 `$BaseRef` 字面打印 | t6 |
| P3 | 装机后确认那条 `<separator>` 是否显示（R-14） | 人工 |

---

## 13. 我做了什么 / 没做什么

**新增的证据（都在 `build\t10-probe\`，不入交付清单）**：
`T10Risk.java`（`2283AB39…7F507F`）、`run-t10-probe.ps1`（`A5FFF3D6…C9F7D6`）、输出存档 `t10-risk.txt`。

**我没做**：
- 没装 jar、没重启 IDEA、没在真机里点过一次；
- 没重跑帧率（理由与边界见 §9.2）；
- 没验证 R-14 的真机渲染、没验证配色方案落盘的真实时机。

**本文件**：`REVIEW-SETTINGS-V2.md`（第 3 轮，覆盖并保留前两轮结论）。写入后 SHA256 现算，随任务报告提交（不写回自身）。
