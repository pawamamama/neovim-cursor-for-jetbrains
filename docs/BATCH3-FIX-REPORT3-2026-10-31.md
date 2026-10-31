# 第 3 批审查收尾 · 第二批（5 条 MEDIUM + 16 条 LOW）交付报告（2026-10-31）

> 依据：`docs/HANDOFF-BATCH3-AUDIT-2026-10-31.md` §三 + `docs/CODE-AUDIT-2026-10-30.md` §9.3
> 上游清单：`docs/HANDOFF-NEXT-2026-10-31.md` §3.1（第 6 步第 2 批）
> 施工计划与逐条证据：`plans/batch3-tail.md`
> 分支：`main` · 代码提交：`067cfcb` · 文档提交：`a5cb427`（**均已 push 到 `fork/main`**）

---

## 零、一句话结论

本轮修完第 3 批审查剩下的 **5 条 MEDIUM**（P-M-9 / P-M-4 / U-M-6 / U-M-8 / P-M-1）
与 **12 条 LOW**（另 4 条记录不改理由）。

- 全量门禁 **108 条目 / 1231 PASS / 2 FAIL（仅 VRegr2 informational）/ ALL PASS**；
- 新增探针 `VBatch3Tail`（36 条断言），**每条 MEDIUM 都做过变异测试**：改坏生产代码 → 对应断言变红 → 恢复变绿；
- **D0**（`core/NeovideConfig.java`）零 diff；`settings/` 层与 `docs/BUG-LOG.md` 零改动；
- 两个独立审查代理均为 **0 CRITICAL / 0 HIGH**，它们提的 3 条 MEDIUM 已全部修掉并复验；
- **本批零观感变化**：正常使用时界面、光标、动画与改动前一致（唯一可见变化是 P-M-4 的失败文案，只在点「检查更新」失败时出现）。

---

## 一、代码与分支状态（收工时实测）

```
分支            main
HEAD            a5cb427  docs: 第 3 批收尾交付报告与探针数同步
上一个提交      067cfcb  fix(ui): 第 3 批收尾 —— 5 条 MEDIUM + 12 条 LOW（零观感变化）
远端            fork/main...HEAD = 0 0（推送后实测）
工作区          仅一个开工前就存在的未跟踪文件：build/night-run-report.md
门禁            108 条目 / 1231 PASS / 2 FAIL（仅 VRegr2）/ V-PROBES RESULT: ALL PASS
探针            111 个 V*.java / 126 个 .java / 32,057 行（磁盘实测）
D0              core/NeovideConfig.java 零 diff
禁区            settings/ 层、docs/BUG-LOG.md 零改动
```

**开工基线与任务书一致**：HEAD `8ce6524`、同步 0 0、门禁 ALL PASS、D0 零 diff。
任务书给的基线「107 条目 / 1195 PASS」与本轮实测的增量对得上：
107 + 1（新探针）= 108 条目，1195 + 36（新断言）= 1231 PASS。

---

## 二、五条 MEDIUM：根因 → 改法 → 证据

### 1. P-M-9 抑制标志不保存前值

**根因**：`syncingControls` / `suppressDerive` 是裸 boolean，9 个写入点用 `finally { x = false; }` 硬置。
嵌套时内层会把外层的 `true` 清成 `false` —— 外层剩余语句被当成「用户编辑」，重复派生副本、脏标记乱置，且无报错。
今天生产路径没有触发这条嵌套（内层回调入口都早退），属**潜伏缺陷**。

**改法**：9 处统一改成「保存前值 + finally 恢复」，与文件里已有的 5 处正确写法一致。
改后全仓 `syncingControls = false` / `suppressDerive = false` **零命中**。

**证据**：
- 探针 `T-1`：`suppressionNestingForProbe()` 模拟「外层已处于抑制态」后调用真实回灌路径，
  断言两个标志仍是 `true`；另有两条「探针收尾不污染面板」的对照。
- 红：改前 `T-1a 实际 false`。
- 变异：把 `syncAllControlsFromPending` 的 `finally` 改回硬置 → `T-1a` 红。

### 2. P-M-4 「检查更新」失败与「没反应」不可区分

**根因**：`PluginInfoBar.doCheck` 失败分支把按钮文字复位成与点击前完全一致的「检查更新」，
原因只写进 tooltip，失败路径零日志。本机代理对 GitHub 常失效，用户分不清「没网 / 超时 / 没 Release / 按钮坏了」。

**改法**：
- 抽出纯函数 `resolveOutcome(latestTag, currentVersion, error)`（生产与探针调同一个，不是复制品）；
- 失败时按钮显示「检查失败，点击重试」，tooltip 带具体原因；
- 异常分类：`SocketTimeoutException` → 「网络超时（4 秒无响应）」；
  `UnknownHostException` / `ConnectException` → 「无法连接 GitHub（网络或代理不可用）」；其余保留原始类型与消息；
- 失败时补一条 `LOG.warn`；
- **不弹模态框**（实现决策，纪律四-L）：常驻按钮每次断网弹框会打断设置流程，
  按钮文字本身就是一行可见提示，且可被探针断言。

**证据**：`T-2a~g` 覆盖超时 / 404 / 版本相同 / 有新版本 / `error` 为 null 五种结果；
变异：失败文案改回「检查更新」→ `T-2a`、`T-2g` 红。

### 3. U-M-6 `dispatchDue` 连续 false 无升级检测

**根因**：`loop()` 里 `if (!dispatchDue()) Thread.sleep(1ms)`。EDT 被长任务卡住时，
所有到期 animator 的 `framePending` 都是 true，`dispatchDue()` 恒返回 false →
调度线程 **1000 次/秒空转且零日志**，与异常路径的 `consecutiveFailures` 待遇不对等。

**改法**：
- 计数是 `loop()` 局部变量（不 static —— H-1 的教训：重建线程不能继承上一代计数）；
- 前 10 轮保持 1ms（EDT 上长 tick 造成的瞬态是设计内的，不是故障）；
- 第 11 轮起指数退避 2→4→8→16→32ms 封顶；
- 第 10 轮与每 100 轮各一条 warn（**首条门槛是「开始退避」那一轮**，不是第 1 轮 ——
  见 §五 java-reviewer M-1：第 1 轮就打会在 IDE 建索引时按帧率刷屏）；
- 异常路径把计数归零（java-reviewer L-2）。

**证据**：`T-3a~g`；变异 `busyBackoffMs` 恒 1 → `T-3b/T-3c` 红；`shouldLogBusy` 恒 false → `T-3d/T-3f` 红。
`T-3g` 是源码结构断言（计数必须是 loop 局部变量）。

### 4. U-M-8 块选区 key 退化

**根因**：`refreshTarget()` 里 `try { lineNo = doc.getLineNumber(...) } catch (Throwable ignored) { }`
—— 空 catch 吞掉异常，且 `blockTrailKey(lineNo<0, offset)` 退化为 offset key。
下一帧行号恢复 → key 变回行号 key → `trails.get()` 未命中 → 新建 `SpringTrail`（视觉上「闪一下」），
连续失败时每帧重建、每帧分配。

**改法**：
- 新增包级纯函数 `resolveBlockKey(lineNo, blockStart, prevKey)`：行号可用 → 行号 key；
  失败 → 沿用上一帧该槽位 key；首见 → offset 兜底；
- 新增 `prevBlockKeys[64]` 缓存，块选区行数变化或退出块选区时清空；
- 两处 `getLineNumber` 的 catch 都接 `swallowedQuietly`（吞了要留痕）。

**证据**：`T-4a~e` 覆盖三种分支 + 连续三帧稳定性；`T-4f/T-4g` 是结构断言（生产真的调用它、catch 接了诊断通道）。
变异：删掉「沿用上一帧」→ `T-4b/T-4e` 红。

### 5. P-M-1 `apply()` 逐字段写活 State，无回滚

**根因**：A3 循环逐字段 `sf.copy(pending, target)`，`target` 是 `getState()` 返回的**内部引用**；
从第一次 copy 到 `session.reloadFrom(target)` 之间没有回滚点。中途抛出 → State 半新半旧、未落盘、baseline 不动。

**方案选择：整份快照 + `target.copyFrom(rollback)`**（不是「副本拼装」也不是「replaceState」）：

| 方案 | 结论 |
|---|---|
| `replaceState(State)` | **不选**。`getState()` 契约是「返回内部对象，不新建不复制」；换引用会让 Tools 菜单 / CONFIG 同步持有的旧引用失效；且要改 `settings/` 层（白名单禁止） |
| 逐字段「已写清单」回滚 | **不选**。那是第二份要与 `SettingsField.FIELDS` 同步的清单，正是本项目反复踩的「同一语义两处维护」病根 |
| **整份快照 + `copyFrom`** | **选**。`State.copyFrom` 本来就跟随字段表更新（六层链路的既有契约），复用它不会多出一份会漏的清单；`State` 里全是标量与不可变 `String`，`copyFrom` 就是完整深拷贝 |

**与 P-C-1 的关系（不合并成一条闸门）**：
- P-C-1 管「**已写进 State、落盘返回 FAILED**」：保留内存新值、不推进 baseline、提示「活不过重启」；
- P-M-1 管「**写 State 中途抛出**」：`copyFrom` 回滚、不推进 baseline、提示「已回滚」；
- 两者的判定**在代码结构上分开**：`persisted` 在 `try` 外声明，FAILED 判定移到 `try/catch` 之后
  （java-reviewer L-1：否则 `showUserNotice` 万一抛出会被误判成「State 没写完」并回滚）。
- 回滚落盘接住 `PersistResult`：非 `CONFIRMED` 时改用另一段文案（「内存已回滚，但磁盘写入未确认」），
  不再无条件宣称「已回滚到应用前」。
- `VirtualMachineError` 在回滚与提示之后重新抛出（回滚已做，但致命错误必须让平台看见）。

**证据**：`T-5a~h` + `T-5b2/T-5b3`：
注入故障后 State **逐字段**（含预设 5 件套与 `paintDiag`）与 apply 前一致、pending 保留、
baseline 未前移、`isModified()` 为真、有「应用失败」提示、清故障后重试成功。
变异：注释掉 `target.copyFrom(rollback)` → `T-5b`（差异 `animationLength`）与 `T-5b2`（5 件套）红。

---

## 三、16 条 LOW 的处置

| 编号 | 处置 | 改了什么 / 不改的理由 |
|---|---|---|
| P-L-1 | 改 | 5 处连续 javadoc 合并；`AnimationPreview.editorFont()` 原三块堆叠导致**方法完全没有 javadoc**（详版块悬空），已把详版移到方法前、删简版 |
| P-L-2 | 改 | 「6 个标签页 / 6 个内层」→ 7；「10 个数值标签」→「全部数值标签（不写死数量）」 |
| P-L-3 | 改 | 列位注释原来写反：`row("")` 已自动补标签列，显式 `cell()` 占的是**下拉**列，按钮与「恢复默认值」同列 |
| P-L-4 | 改 | 删除零调用的 `probeFontSize()`（与 `probeFontSizeNow()` 完全重复） |
| P-L-5 | **不改** | 装自定义渲染器必然改渲染路径 → 与「零观感变化」冲突；风险仅限用户自己导入的配置，登记为后续安全项 |
| P-L-6 | **不改** | 正解在 `CustomPresets.nameError()`（`settings/` 层），白名单明令不许改；面板侧只能拦一个入口，属半修 |
| P-L-7 | 改 | 滑块监听里「写 pending 之后」的界面刷新包 `try/catch + LOG.warn`（当前无已知抛点，结构性防护） |
| P-L-8 | 改 | `reset()` 手工复位 `paintDiag` 与复选框（它不是 State 字段，不走 `bindCheck`） |
| P-L-9 | 改 | `addSlider` 里按 `pending` 显式 `setValue`（DSL 不设初值 → JSlider 默认 50；此时监听器未挂，无副作用） |
| P-L-10 | 改（并入 P-M-1） | `apply()` 顶部 `baseline == null` 守卫 + warn（`SettingsField.differs` 无空检查） |
| P-L-11 | 改 | `isRainbowSelected()` 的 `catch (Throwable ignored)` 改为 `LOG.warn` |
| P-L-12 | 改 | `isShowing()` 守卫放在 **Timer 回调**里，不放 `step()` —— 放 `step()` 会废掉 `PreviewShowcase` 对未 realize 实例的 `probeTick` |
| P-L-13 | **不改** | 改 `JBUI` 会让默认字号与配色跟随平台主题 → 观感变化 |
| P-L-14 | 改（注释） | `probeTick()` javadoc 补线程契约：「必须 EDT、仅用于未 realize 实例」 |
| P-L-15 | **不改** | 数组字面量是故意的可维护性选择（「加第四个预览只改一行」）；`refreshPreviews` 不在每帧热路径，一次 3 元素数组分配可忽略 |
| P-L-16 | 改（改名） | `syncPreviewCursorSize → syncPreviewStyle`（它推尺寸 + 渐变 + 彩虹） |

---

## 四、探针变更

| 项 | 内容 |
|---|---|
| 新增 | `tools/settings-probe/VBatch3Tail.java`（36 条断言，T-1~T-5） |
| 登记 | `build-v-probes.ps1` 的 `$mains` 数组（**不登记 = 从不运行**，本项目踩过） |
| 既有探针 | 零修改、零放宽。`VAddSetting` / `VerifyPanel` / `VPreset` / `VDefaultFreeze` 的常数未变（本批不加字段、不加控件） |
| 文档同步 | `CLAUDE.md` / `START-HERE.md` / `docs/NEXT-STEPS.md` 探针数 110→111、文件 125→126、行 31,690→32,057（`VDocCheck` D-2 绿） |

**探针自身的一处缺陷（复查后修掉）**：结构断言原先用相对路径读源码，
`build-v-probes.ps1 -Repo <worktree>` 时会读到**主仓库**的源码 → 假绿。
已改为先读 `-Dneovide.repo`（与 `VBuildDecl` / `VDocCheck` 同约定）。

---

## 五、代码审查（两位代理）

两个代理只审本批 diff，均为 **0 CRITICAL / 0 HIGH**。

**`code-reviewer`**：2 MEDIUM / 8 LOW，结论 APPROVE。
**`java-reviewer`**（已注明跳过 Spring/Quarkus Framework Detection）：3 MEDIUM / LOW，结论 Approve with warnings。

采纳并修掉的：

| 来源 | 问题 | 修法 |
|---|---|---|
| java M-1 | `shouldLogBusy(1)` 在 EDT 常态繁忙下按帧率刷 warn | 首条门槛抬到第 10 轮（开始退避时）；T-3d/T-3e 断言方向同步改 |
| java M-2 / code L-4 | 回滚路径 `persistQuietly()` 返回值被丢弃，文案可能撒谎 | 接住三态；非 `CONFIRMED` 改用另一段文案并 warn |
| java M-3 | `apply()` 的 `catch (Throwable)` 吞掉 `VirtualMachineError` | 回滚与提示做完后重抛 |
| java L-1 | P-C-1 的 FAILED 分支在 P-M-1 的 try 内 | `persisted` 提到 try 外、FAILED 判定移到 try/catch 之后 |
| java L-2 | 调度异常路径不重置计数 | catch 开头归零 |
| java L-5 / code L-5 | 回滚路径无条件调 `setPaintDisabled` | 与 A5.5 同一条「只在变化时下发」 |
| code M-1 | 探针结构断言用相对路径读源码 | 新增 `repoRoot()`，先读 `-Dneovide.repo` |
| code M-2 / java L-10 | T-5 漏测预设 5 件套与 `paintDiag` | 补 `T-5b2` / `T-5b3` |
| code L-1 | 我改的列位注释引用了撑不起的数字（77/180/280/367） | 改回 `plans/panel-refactor.md:276` 原文的 21 / 97 / 220 |
| code L-2 | `applyFaultForProbe` javadoc 写「静态字段读」 | 改为「实例字段读」 |
| code L-6 | 探针类 javadoc 只登记 T-1 | 补 T-2~T-5 |

未采纳（理由已记录在 `plans/batch3-tail.md` §四）：

| 来源 | 条目 | 理由 |
|---|---|---|
| code L-3 | `prevBlockKeys` 在「槽位数不变、行变了」时不清缓存 | 只在 `getLineNumber` 同时失败的组合下发生；key 命名空间不重叠（行号 key 为负、offset key 非负），不会冲突，最坏一帧后自愈 |
| code L-7 | 探针侧四处「6 个标签页」注释残留 | 不在本批 diff 内，单独登记 |
| code L-8 | `build/night-run-report.md` 未跟踪 | 本批不提交；提交时已排除 |

---

## 六、门禁与变异（可复现命令）

```powershell
# 全量门禁（唯一权威入口；不要并发跑两份，四-G-2 会假红）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1

# D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

结果：`V-PROBES RESULT: ALL PASS`（108 条目 / 1231 PASS / 2 FAIL 仅 VRegr2）；D0 零 diff。

**变异测试记录（每条都是「改坏 → 重编译 → 断言红 → 恢复 → 绿」）**：

| 条目 | 改坏什么 | 变红的断言 |
|---|---|---|
| P-M-9 | `syncAllControlsFromPending` 的 `finally` 改回硬置 false | T-1a |
| P-M-4 | `resolveOutcome` 失败文案改回「检查更新」 | T-2a、T-2g |
| U-M-6 | `busyBackoffMs` 恒返回 1 | T-3b、T-3c |
| U-M-6 | `shouldLogBusy` 恒返回 false | T-3d、T-3f |
| U-M-8 | 删掉 `resolveBlockKey` 的「沿用上一帧」分支 | T-4b、T-4e |
| P-M-1 | 注释掉 `target.copyFrom(rollback)` | T-5b、T-5b2 |

---

## 七、需要实机确认

| 项 | 说明 |
|---|---|
| **P-M-4 的提示长什么样** | 断网点「检查更新」后按钮应显示「检查失败，点击重试」，悬停能看到具体原因。本机代理对 GitHub 常失效，正好能真实验证 |
| **P-L-3 的列位** | 注释已按 `plans/panel-refactor.md:276` 的实测值（标签 21 / 下拉 97 / 动作首列 220）改正；审计文档原本就标注「需实机确认」，若实机不同以实机为准 |
| U-M-6 的退避 | 离线只能验证纯函数曲线与结构；「EDT 真的卡死」无法离线造出 |
| U-M-8 的沿用 | 离线验证纯函数 + 结构断言；逐帧缓存行为需要真实 Editor |

**本批未做**：装 jar 实机（按任务书，本轮不安装）、`build/night-run-report.md` 的清理。

---

## 八、本轮改动文件清单

```
代码与探针（067cfcb，7 文件 +896/−143）
  src/main/java/.../ui/CaretAnimator.java                      +56
  src/main/java/.../ui/RenderScheduler.java                    +76
  src/main/java/.../ui/settings/AnimationPreview.java          +60/−
  src/main/java/.../ui/settings/NeovideCursorConfigurable.java +402/−
  src/main/java/.../ui/settings/PluginInfoBar.java             +70
  tools/settings-probe/VBatch3Tail.java                        新增 367
  tools/settings-probe/build-v-probes.ps1                      +8

文档与计划（a5cb427，4 文件 +452/−4）
  plans/batch3-tail.md                                         新增 448
  CLAUDE.md / START-HERE.md / docs/NEXT-STEPS.md               探针数同步
```

**零改动**：`core/NeovideConfig.java`（D0 红线）、`settings/` 层、`docs/BUG-LOG.md`、
`ui/SpringTrail.java`、`ui/CaretTrailPanel.java`。
