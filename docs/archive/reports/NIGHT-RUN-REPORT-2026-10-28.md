# 夜间任务报告 —— P0：修 refreshTarget 在平台 252 上的 read-access 断言

> **归档说明（2026-11-04）**：本文写于 2026-10-28 深夜，原文件在 `build/night-run-report.md`
> （`build/` 下未跟踪的一次性产物）。它记录的是平台 252（PyCharm 2025.2）光标冻结的
> read-access 修复。**文中的版本号、探针数（97）、代码行数都是当时快照**，不是现状；
> 改动已在 2026-10-29 以 `37355ae` 合入并安装（见 §10）。
> 现状请看 `docs/CURRENT-STATE-2026-11-04.md`。

生成时间：2026-10-28 深夜
分支：main（未提交、未推送，原因见 §7）
版本产物：dist/neovide-cursor-0.9.104.jar（已打，未安装）

---

## 1. 根因（一句话）

**平台 252 要求「读 Editor / Caret / Document 必须在 read action 内」，而我们的 tick 由
`invokeLater` 投递到 EDT —— 在 EDT 上不等于持有读锁**，于是 `CaretImpl.getOffset()`
触发 `EditorThreading.assertInteractionAllowed`，`refreshTarget` 每帧抛异常、
被自身的 `catch (Throwable)` 吞掉并返回 `false`，覆盖层光标永久冻结在第一行第一字符。
IDEA 2026.1（build 261）放宽了 EDT 上的读访问判定，所以同一份 jar 不触发 ——
**这是平台版本差异，不是产品差异**。

平台字节码核实（`javap -c com.intellij.openapi.application.ReadAction`）：

```
ReadAction.compute(T)
  → ReadAction.computeBlocking(T)
    → ApplicationManager.getApplication().runReadAction(ThrowableComputable)
```

即 `compute` 就是 `runReadAction` 的**带返回值版本**，与 Runnable 版同一实现。
所以用 `ReadAction.compute` 不会引入第二套语义。

---

## 2. 改了哪些文件与行

### 2.1 生产代码（1 个文件）

`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java`（+132 / −37）

| 位置 | 改了什么 | 为什么 |
|---|---|---|
| import 区 | 新增 `com.intellij.openapi.application.ReadAction`、`com.intellij.openapi.util.ThrowableComputable` | 包装入口 |
| **:1567** | 新增 `private static <T> T readEditor(ThrowableComputable<T,RuntimeException>)` | 唯一的 read action 入口；注释写全了 252 的根因、为什么用 `compute` 而不是 `runReadAction(Runnable)`、为什么无条件包装、开销量级 |
| **:1584** | `refreshTarget()` → 只剩一行 `return readEditor(() -> refreshTargetInReadAction());` | 包装层只有 6 行，实现体一行未动 —— 让「行为不变」可验证，而不是靠声明 |
| **:1593** | 原 `refreshTarget()` 方法体原样搬进 `refreshTargetInReadAction()` | 500 行方法体零改动（只换方法名/签名外层），避免全文重排导致 diff 不可读 |
| **:758** | `this.content = readEditor(() -> editor.getContentComponent());` | 构造函数首次读取 |
| **:763** | `this.staticCaret = readEditor(() -> editor.getEditorKind() == EditorKind.CONSOLE);` | 同上 |
| **:806** | `editor.getCaretModel().addCaretListener(this)` 包进 `readEditor` | getCaretModel 是编辑器读取；注册动作与它同一读锁内 |
| **:820** | `editor.getInlayModel().addListener(...)` 包进 `readEditor` | BUG-006 的 inlay 监听注册 |
| **:873** | `safeEditorKind()` 内 `readEditor(() -> editor.getEditorKind())` | 构造函数里 bootTrace 会调它 |
| **:898** | `traceCaretEvent()` 的四次读取（vp / offset / 文档戳）包进一次 `readEditor`，结果用 `long[4]` 带回 | caret 事件回调**不保证**持有读锁 |
| **:1101** | `caretAdded()` 的两次 `getAllCarets()` 包进 `readEditor` | 保留「调用两次」的原始语义（诊断契约是 `before = now-1` / `after = now`） |
| **:1120** | `caretRemoved()` 同上 | 同上 |
| **:1437** | `recordFrameStats()` 的 `getEditorKind()` 包进 `readEditor` | 仅 Debug 窗口到期时执行，非每帧 |

**调用次数契约（P0-5）不变**：包装层是「一次 lambda 执行」，内部的
`getOffset` / `visualPositionToXY` / `offsetToXY` 调用次数与改造前逐位一致，
已由新探针 R-7 / R-7b / R-8 直接计数验证（见 §4）。

### 2.2 探针与平台桩

| 文件 | 改动 |
|---|---|
| `tools/settings-probe/VReadAction.java` | **新增 501 行**（12 条断言，见 §4） |
| `tools/settings-probe/V0Env.java`（+76） | Application 代理新增 `runReadAction` / `maybeRunReadAction` / `isReadAccessAllowed`；**真的执行** lambda 并维护 `READ_DEPTH` 读锁深度；新增 `readActionCalls` 计数器。原来这三个方法落到 `defaultValue` → 返回 null 且**不执行**，会让修复后的生产代码在探针里一次编辑器读取都不发生（典型假通过） |
| `tools/settings-probe/VerifyApplyConfig.java`（+25，:393–416） | 它自带假 Application，同样补 `runReadAction` 的真实执行 + `isReadAccessAllowed`。**这是第 1 次门禁红的原因**：桩不执行 lambda → `readEditor` 返回 null → `boolean` 拆箱 NPE（`CaretAnimator.java:763`）。只改桩，断言一字未动 |
| `tools/settings-probe/build-v-probes.ps1`（:136–141） | `$mains` 末尾登记 `'VReadAction'`（不登记 = 永远不跑） |

### 2.3 文档状态块（VDocCheck 的 D-2 强制要求）

| 文件 | 改动 |
|---|---|
| `CLAUDE.md` | 规模块 111/24,902/96 → **112/25,463/97**；状态行 96 → **97**，新增探针名 `VReadAction` |
| `START-HERE.md` | 规模块 111/24,902/96 → **112/25,463/97** |
| `docs/NEXT-STEPS.md` | 探针文件 96/111 → **97/112**；探针代码 23,570/106 → **25,463/112**；探针总数 96 → **97**（+`VReadAction`）；状态行 96 → **97** |

> 这三份文件在开工时**已含他人未提交改动**（P0 until-build 那轮）。我只改了上述
> 与探针数量绑定的数字，没有动其它任何内容 —— 但 `git add` 会把整份文件带走，
> 所以最终没有提交（见 §7）。

### 2.4 其它入口排查结论（任务要求逐项覆盖）

| 入口 | 是否读编辑器 | 处置 |
|---|---|---|
| `tick()` → `refreshTarget()` | 是 | 已包（:1584） |
| `tickStaticCaret()` | **否**（只读 `SpringTrail` 角点与 panel） | 无需改 |
| `tickAfterimage()` | **否**（只读 `AfterimageTrail` / cameraTrail） | 无需改 |
| `traceCaretEvent()` | 是 | 已包（:898） |
| 构造函数首次 `refreshTarget()` | 是 | 入口本身已包；另把 4 处构造函数内的编辑器读取也包了（:758/763/806/820） |
| `caretAdded()` / `caretRemoved()` | 是（`getAllCarets`） | 已包（:1101/:1120） |
| `caretPositionChanged()` | **否**（只置 `forceResync` + 唤醒） | 无需改 |
| `recordFrameStats()` | 是（`getEditorKind`，仅 debug） | 已包（:1437） |
| `isEditorFocused()` | 否（AWT `KeyboardFocusManager`） | 无需改 |
| `dispose()` 的 `removeCaretListener` | 否（移除监听器，不是读） | 未包，保持原样 |
| `NeovideCaretManager.getProject/getContentComponent/repaint` | 否（普通 getter / UI 重绘，不参与 `assertInteractionAllowed`） | 无需改 |

全仓 `grep` 确认：除 `CaretAnimator` 外，`src/main/java` 里没有任何其它类读
`getCaretModel / getDocument / getSelectionModel / visualPositionToXY / offsetToXY /
offsetToVisualPosition / getLineHeight / getEditorKind`（`CursorDiag` 与 `TrailCorner`
只在注释里提到这些 API）。

---

## 3. 新探针 VReadAction 的判据（它抓什么 bug）

**不测「代码里有没有 `ReadAction` 这个字符串」，而是造出 252 的判据本身**：

1. `V0Env` 的假 Application **真的执行** `runReadAction(ThrowableComputable)`，并维护
   `READ_DEPTH`（当前线程被几层 read action 包着）；
2. 探针造的 `Editor / CaretModel / Caret / Document / SelectionModel / InlayModel` 桩，
   **每个读取方法**都先检查 `V0Env.inReadAction()`：
   - 在内部 → `guardedReads++`，正常返回；
   - 不在内部 → `violations++` 并抛
     `AssertionError("Read access is allowed from inside read-action only [<哪个 API>]")`
     —— 与平台原文同文本，真实机复现时可直接比对。

这满足任务里「若能用 Proxy 造出"只允许 read action 内访问"的 Editor 桩，就直接断言
不再抛断言错误」的要求；同时不是复刻生产算法（纪律四-G）。

---

## 4. 探针与变异测试的数字

### 4.1 新探针基线（最终）

```
=== V-READACTION 结果：PASS 12，FAIL 0 ===
    （read action 累计调用 14 次，受保护的编辑器读取 56 次）
V-READACTION RESULT: ALL PASS
```

12 条断言：

| 编号 | 断言 | 最终实测 |
|---|---|---|
| R-1 | 构造函数（含首次 refreshTarget）全程零违规 | 违规=0，guardedReads=24 |
| R-2 | 构造函数确实进入过 read action | 6 次 |
| R-3 | 慢路径帧零违规 | 违规=0 |
| R-4 | 慢路径帧确实进过 read action | 1 次 |
| R-5 | 慢路径帧仍报告「位置变了」 | 返回 true |
| R-7 | 慢路径帧 `Caret.getOffset` 恰好 1 次 | 1 次 |
| R-7b | 慢路径帧 `CaretModel.getOffset` 恰好 1 次 | 1 次 |
| R-6 | 快速路径帧零违规且返回 false | 违规=0，返回 false |
| R-6b | 快速路径帧也确实进过 read action | 1 次 |
| R-8 | 快速路径帧不读 offset | 0 / 0 |
| R-9 | caret 事件入口（caretAdded/Removed/traceCaretEvent）零违规 | 违规=0，5 次 read action |
| R-10 | **自证**：绕过包装直接调实现体时桩必须判红 | 违规 ≥ 1 |

R-10 是常驻的「变异测试固化」：它自己绕过 `refreshTarget()` 直接反射调用
`refreshTargetInReadAction()`，桩必须判红 —— 否则前面 11 条全绿也毫无意义。

### 4.2 三组真实变异（改坏生产代码 → 探针必须红）

| 变异 | 改法 | VReadAction 结果 | 判红的断言 |
|---|---|---|---|
| **M1** | `refreshTarget()` 改成直接 `return refreshTargetInReadAction();`（去掉包装） | **PASS 4 / FAIL 8**，exit 1 | R-1、R-3、R-4、R-5、R-6、R-6b、R-7、R-7b |
| **M2** | 构造函数 `this.content = editor.getContentComponent();`（去掉包装） | **PASS 0 / FAIL 1**，exit 1 | R-1，detail 直接给出 `AssertionError: Read access is allowed from inside read-action only [Editor.getContentComponent]` |
| **M3** | `caretAdded()` 的 `getAllCarets()` 去掉包装 | **PASS 11 / FAIL 1**，exit 1 | R-9，detail 给出 `[Editor.getCaretModel]` |
| **恢复** | 三处全部还原 | **PASS 12 / FAIL 0**，exit 0 | 无 |

每次变异后都**重新编译生产类再跑**（没有复用旧字节码）。

> **覆盖边界（诚实说明）**：`recordFrameStats()` 里的 `getEditorKind()` 只在
> 「Debug 日志开启 + 统计窗口到期」时执行，本探针没有覆盖它（已包，但无守门断言）。
> `dispose()` 的 `removeCaretListener` 未包（那不是读操作）。

---

## 5. 门禁最终结果

三次全量门禁（唯一权威入口，**从未并发**）：

| 轮次 | 结果 | 说明 |
|---|---|---|
| 第 1 次 | `V-PROBES RESULT: FAILED（1 个探针未通过）` | `VerifyApplyConfig exit=1 PASS=0 FAIL=0`，NPE 于 `CaretAnimator.java:763` —— 它自带的假 Application 不执行 `runReadAction`。修桩后复跑 |
| 第 2 次 | `V-PROBES RESULT: ALL PASS` | 96→97 探针、VDocCheck 三项全绿 |
| **第 3 次（最终，变异全部恢复后）** | **`V-PROBES RESULT: ALL PASS`** | 见下 |

最终门禁原文（`build/gate-3.log` 最后一行）：

```
V-PROBES RESULT: ALL PASS
```

关键探针逐条（最终）：

```
VFontScale exit=0 PASS=5  FAIL=0
VInlay     exit=0 PASS=5  FAIL=0
VDocCheck  exit=0 PASS=4  FAIL=0
VReadAction exit=0 PASS=12 FAIL=0
VerifyApplyConfig exit=0 PASS=26 FAIL=0
VerifyPanel       exit=0 PASS=34 FAIL=0
VerifyPersistence exit=0 PASS=52 FAIL=0
```

- 探针总数：**97 个 V*.java**（磁盘实测）
- `exit != 0` 的探针：**只有 VRegr2**（13 PASS / 2 FAIL），它是脚本里
  `$informational` 明确排除的「缺陷上报器」（R-5 漏看 `typingSmoothness`、
  R-6 该字段不派生副本），**已知未修功能缺口，与本次改动无关**，门禁不计入失败门。
- **D0 门禁**：`git diff --exit-code -- core/NeovideConfig.java` → **EXIT=0（零 diff）**。

---

## 6. 打包产物

```
dist/neovide-cursor-0.9.104.jar   187,645 字节   2026-10-28 15:49
```

打包命令（注意：`-OutName` 是脚本的**必填**参数，任务书给的命令缺它，
实际必须带；`-BaseJar` 也已按任务要求显式传入）：

```
pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 -Version 0.9.104 -OutName neovide-cursor-0.9.104.jar -BaseJar dist\neovide-cursor-0.9.103.jar
```

包内 `META-INF/plugin.xml` 头部（三个标签齐全）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<idea-plugin>
    <version>0.9.104</version>
    <idea-version since-build="242" until-build="261.*" />
    <id>com.pawamamama.neovidecursor</id>
```

`CaretAnimator.class` 的 md5 与 0.9.103 不同（`86de424f…` → `b663d141…`），
确认替换的是本次编译产物，不是旧的。

**兼容区间对本任务的影响**：`since-build=242` / `until-build=261.*`。
PyCharm 2025.2 是 **252**，落在 `[242, 262)` 区间内 → **可以安装、会被加载**，
所以这份 jar 能直接用于实机验证。上界 261.* 只拒绝 262 及以后（那时必须出新版）。

---

## 7. 为什么没有提交（未 push 是硬约束，未 commit 是判断）

- 硬约束要求「**不要 push**」「**不要把别人那 8 个文件带进提交**」。
- 本次必须修改的 5 个文件里有 4 个（`CLAUDE.md` / `START-HERE.md` /
  `docs/NEXT-STEPS.md` / `build-v-probes.ps1`）**已经含有他人未提交的改动**
  （P0 until-build 那轮）。对这 4 个文件执行 `git add <file>` 会把他们的改动
  一起提交，直接违反硬约束；而只提交其余文件（`CaretAnimator.java` /
  `V0Env.java` / `VerifyApplyConfig.java` / `VReadAction.java`）会让
  「探针文件数 97」与「`$mains` 未登记」在提交后自相矛盾，门禁也会红。
- 所以**全部保留在工作区、未提交**。需要提交时建议：
  1. 先与那 8 个文件的作者确认他们的改动；
  2. 一起提交（或让作者先提交他们的部分），再 `git add` 本次这 7 个文件；
  3. 提交信息建议：

```
fix(ui): 编辑器读取包进 read action（平台 252 光标冻结）

根因：平台 252（PyCharm 2025.2）要求读 Editor/Caret/Document 必须持有读锁；
我们的 tick 由 invokeLater 投递到 EDT，在 EDT 上不等于持有读锁，于是
CaretImpl.getOffset 触发 EditorThreading.assertInteractionAllowed，
异常被 refreshTarget 自己的 catch(Throwable) 吞掉并返回 false →
覆盖层光标冻结在第一行第一字符。IDEA 261 放宽了该判定，故不触发。

改法：新增 readEditor(ThrowableComputable) = ReadAction.compute；
refreshTarget 拆成「6 行包装 + 原实现体（一行未动）」；构造函数 4 处读取、
traceCaretEvent、caretAdded/Removed、recordFrameStats 一并包装。

验证：新增 VReadAction（12 断言）用「只允许 read action 内访问」的 Editor 桩
复现 252 判据；三组变异（去 refreshTarget 包装 / 去构造期包装 / 去 caretAdded
包装）分别红 8 / 1 / 1 条，恢复后 12 全绿；全量门禁 97 探针 ALL PASS；
D0 零 diff；P0-5 调用次数契约由 R-7/R-8 直接计数确认不变。

Co-Authored-By: Claude Code <noreply@anthropic.com>
```

---

## 8. PyCharm 实机仍需你验证（我不能声称已验证）

把 `dist/neovide-cursor-0.9.104.jar` 装进 **PyCharm 2025.2（PY-252.23892.515）** 后逐项确认：

1. **打开任意 `.py` 文件** → 光标位置是否出现覆盖层光标（不再是「冻结在第一行第一字符」）；
2. **移动光标**：方向键 / 鼠标点击跨行移动 → 拖尾是否跟随、有动画（不是原地不动）；
3. **打字**：连续输入、删除、跨行输入 → 拖尾是否每帧更新（按字符判断短跳的观感是否正常）；
4. **终端双光标**：打开 Terminal 工具窗，在终端里移动光标 → 内置终端的光标是否被正确补画
   （静态光标路径，`tickStaticCaret`）；
5. **Run 输出区**：运行一个脚本，在 Run 输出面板里点击/移动光标 → 输出区光标是否正常
   （同样是 `EditorKind.CONSOLE` 的静态光标路径，且它**整体跳过** `refreshTarget`）；
6. **看 `idea.log`**：确认**没有**新的
   `Read access is allowed from inside read-action only`；
   也没有 `neovide-cursor: refreshTarget 抛异常` 的 WARN（这条一旦出现就是还没修好）。

另外建议在 **IDEA 2026.1（261）** 上回归一次 1–5 项，确认观感与 0.9.103 完全一致
（本次改动理论上零观感变化，但需要人眼确认）。

> 若 PyCharm 上仍有冻结：先看 `idea.log` 里是否换了新的 API 名字（例如
> `ScrollingModel` / `FoldingModel` / `CaretModel.getPrimaryCaret`），
> 桩的报错文本会精确到「哪个 API」，按名字补包装即可。

---

## 9. 已知边界与遗留

1. **VRegr2 的 2 条红**（R-5 / R-6）依然存在：`typingSmoothness` 既不进
   `matchAnimPreset` 也不派生自定义副本。这是**真功能缺口**，按项目纪律不碰
   （informational，未计入失败门），需要产品决策后再改生产代码。
2. **`recordFrameStats` 的包装没有守门断言**（见 §4.2 覆盖边界）。
3. **文档里生产代码行数已漂移**：`CLAUDE.md` 写 35 文件 / 16,166 行、
   `START-HERE.md` 写 34 文件 / 15,164 行，两者互相矛盾，本次未改
   （不属于本任务，且属于他人正在编辑的段落）。按 `find + wc -l` 实测：
   生产代码 **35 文件 / 16,261 行**（含 CaretAnimator 本次 +132/−37）。
4. **`install.ps1` 未执行**：安装会先清空、再还原用户个人配置，属于需要人确认的动作，
   且 IDEA 运行时会锁住 jar。请自行在关闭 IDE 后安装，或运行
   `build/install.ps1 -Version 0.9.104 -NoStart`。

---

## 10. 2026-10-29 补记：提交与安装（用户指示「你决定 / 安装好我测试」）

### 提交

```
commit 37355ae  fix(ui): 编辑器读取包进 read action，修平台 252 光标冻结
12 files changed, 1143 insertions(+), 63 deletions(-)
```

- 决定：把「read-action 修复」与工作区里原有的「until-build 兼容区间」一并提交。
  理由：二者在同一工作区且互相依赖（97 个探针必须与 `$mains` 登记一致、
  `until-build` 必须与打包脚本一致），拆开会让中间状态门禁变红。
- 未 push（硬约束）。
- `build/night-run-report.md` 与 `build/commit-msg.txt` 未提交（`build/` 下一次性产物）。

### 安装（两个 IDE 都已装 0.9.104）

| IDE | 版本 | 插件路径 | 校验 |
|---|---|---|---|
| **PyCharm 2025.2** | PY-252.23892.515 | `%APPDATA%\JetBrains\PyCharm2025.2\plugins\neovide-cursor-pawamamama\lib\jetbrains-neovide-cursor-0.9.8.jar` | `<version>0.9.104</version>`、`since-build="242" until-build="261.*"`（252 在区间内） |
| IDEA 2026.1 | 261 | `%APPDATA%\JetBrains\IntelliJIdea2026.1\plugins\neovide-cursor-pawamamama\lib\jetbrains-neovide-cursor-0.9.8.jar` | 同上（原 0.9.102 已备份到 `D:\deepseekwork\_config-backup\idea-neovide-0.9.102-20261029-121757.jar`） |

两处 jar 与 `dist/neovide-cursor-0.9.104.jar` 的 md5 完全一致：`4305a0947f6c210770da87c2fefbca4d`。
PyCharm 的 `disabled_plugins.txt` 只禁用 `com.intellij.modules.ultimate`，与本插件无关。
两个 IDE 安装时均未运行进程 → 无文件锁冲突。**配置未清空**（手动拷贝，没有走
`install.ps1` 的干净安装流程），所以你原有的设置应当都在。

### 现在可以测了

- **PyCharm 2025.2**：启动 → 打开任意 `.py` → 按 §8 的 1–6 项逐条走。
  这是唯一能验 252 修复的入口。
- **IDEA 2026.1**：启动后确认观感与 0.9.102 一致（回归确认，验不出 252 的问题，
  因为它本来就不触发）。
- 若 PyCharm 里仍冻结：贴 `%LOCALAPPDATA%\JetBrains\PyCharm2025.2\log\idea.log`
  里含 `Read access` 或 `refreshTarget 抛异常` 的行，桩的报错文本会精确到具体 API。
