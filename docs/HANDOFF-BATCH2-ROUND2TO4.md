# 交接 · 审计第 2 批第 2~4 轮（E → F → B 三批，未提交）

> 给接手的会话：读完这一份就能接着干，不需要回翻对话。
> 生成时间：2026-10-30 夜间 · 工作区改动**未提交**（按任务书阶段 5：停在事务区等用户醒来验）

---

## 一句话

审计第 2 批的剩余 **20 条**（E 批 7 / F 批 2 / B 批 11）已全部修完、探针守门、变异验证；
门禁 **101 条目 / 1069 PASS / 2 FAIL（仅 VRegr2，informational）/ ALL PASS**，D0 零 diff。
**唯一挂起**：U-M-21 的「运行时禁用插件」钩子需要 `plugin.xml`（超白名单），只做了 `Disposer.register` 最小接点。

---

## 一、环境事实

```
仓库      D:\deepseekwork\neovim-cursor-pr
分支      main
开工 HEAD 8ee1d46（fork/main 同步 0 0）
门禁基线  100 条目 / 1048 PASS / 2 FAIL（仅 VRegr2）/ ALL PASS
门禁收尾  101 条目 / 1072 PASS / 2 FAIL（仅 VRegr2）/ ALL PASS
D0        git diff --exit-code -- .../core/NeovideConfig.java → 0
探针      104 个 V*.java（119 文件 / 28,891 行）；本批新增 1 个文件、24 条断言
```

**不要并发跑两份门禁**（共用 `build\v2-probe\classes` → ClassNotFoundException 假红）。

本轮为变异测试新增了本地脚本 `build/run-one-probe.ps1`（**未入库**，只在 build/ 下）：
它全量重编译源码树 + 全部探针，然后只跑指定 main。用法：

```
pwsh -NoProfile -ExecutionPolicy Bypass -File build\run-one-probe.ps1 -Main VAuditBatch2Round2to4
```

---

## 二、三批改动一览

### E 批（原生光标与挂载链，7 条）

| 编号 | 问题 | 修法 | 文件 |
|---|---|---|---|
| **U-H-5** | `install()` 空 catch 吞掉挂载失败 → 该编辑器永久无覆盖层且零日志 | `install` 改三态 `Mount{MOUNTED,SKIPPED,FAILED}`；失败 `LOG.warn("installEditor …")` + `swallowed`；两个循环调用点改 `installAll(...)` 汇总「本轮挂载失败 N 个」 | NeovideCaretManager |
| **U-M-22** | 构造期 `this` 逃逸（panel/caret 监听/inlay/调度器） | 构造器 try/catch + **逆序 undo 栈**（先登记后执行，撤销幂等）；不做 `init()` 两段式 | CaretAnimator |
| **U-H-6** | 先记账后写色，`setColor` 抛 → 假账 → 双光标不可救 | 顺序反转：`setColor` 成功后才 `putIfAbsent` 与更新 `savedCaretColor`；catch 接 `swallowed` | NeovideCaretManager |
| **U-M-17** | 恢复失败九处静默、永久重试 | 新增 `RESTORE_FAILURES`（同锁 IdentityHashMap）；首次失败 `swallowed`，同 scheme 连续 3 次 → 删账 + 一次 warn | NeovideCaretManager |
| **U-M-18** | `handleSchemeChange` 空 catch | 拆出 `handleSchemeChangeOnEdt`，catch 接 `swallowed("schemeChange")` | CaretSchemeWatcher |
| **U-M-16** | `installSchemeWatcher` 返回值三处被丢弃 | 新增 `ensureSchemeWatcher()`：成功/已装静默，真失败一次性 warn；三处调用点全换 | NeovideCaretManager / CaretSchemeWatcher |
| **U-M-20** | 「回调在 EDT」假设被 javap 否证 | 防御式：`app.isDispatchThread()` 判断 + `invokeLater` 转投；**拆两个方法**防无限递归；类注释改成实测结论 | CaretSchemeWatcher |

### F 批（生命周期，2 条）

| 编号 | 问题 | 修法 |
|---|---|---|
| **U-M-24** | `applyConfigOnEdt` 失败后仍无条件打「完成」 | `failures` 计数进每个 catch；抽出唯一决策点 `applySummary(failures, animators)`；>0 用 `LOG.warn` |
| **U-M-21** | 无卸载/禁用收尾钩子 | `Disposer.register(Application, ::shutdownCleanup)`；`shutdownCleanup` 恢复原生光标 + dispose 全部 animator + 清表 + 断主题监听，不写盘不改 CONFIG。**运行时禁用插件不覆盖（需 plugin.xml，已挂起）** |

### B 批（诊断基础设施，11 条）

| 编号 | 问题 | 修法 |
|---|---|---|
| **U-H-7** | 写文件失败静默（无 `checkError`） | 每 64 行 `checkError`；失败关闭置 null、限次重建（3 次）、向 `idea.log` 报一次/放弃一次 |
| **U-M-12** | `fileWriter`/`fileTried` 跨线程无守卫 | 整个 `writeFile` 走 `FILE_LOCK`（字段名与类型保留，`VTrailCap` 反射依赖） |
| **U-H-8** | `paintTrace` 终身只记一次 | 改「状态翻转才记」：进入空绘制 / 恢复各一行，异常类名变化也记 |
| **U-M-11** | `enabled()` 每帧多次服务定位；catch→false 哑掉通道 | 缓存**服务引用**（不缓存布尔值，否则既有探针全假红）；失败沿用上次值 + 一次 warn；`enabledForBackgroundThread()` 供看门狗线程读 volatile 快照；`invalidateEnabled()` 在 `applyConfig()` 开头调用 |
| **U-M-13** | 三张按 id 分桶的 static 表不回收 | 新增 `forget(id)` 清四张表；`CaretAnimator.dispose()` 最后一步调用 |
| **U-M-14** | `static glowLayersNow` 被多 animator 覆写 | 删字段；层数改为 `frameSummary(..., int glowLayers, ...)` 入参 |
| **U-M-15** | EdtWatchdog 三重失效 | 空闲 5 分钟退出并复位 `started`（`owner` 校验防双线程）；`enabled()` false→true 时也启动；loop 整体 try/catch |
| **U-M-26** | 诊断文件写在 config 目录（会被漫游/导出带走） | 改反射 `PathManager.getLogPath()`，保留 config/tmpdir 兜底 |
| **U-M-27** | 5000 行上限只接 2/10 出口 | 总闸门放进 `writeFile`（200k 行 / 16MB）；`[frame]` 加每 animator 5000 行预算 + 汇总；单行截断 2000 字符 |
| **U-L-7** | 固定文件名 + TRUNCATE，双实例互截断 | 文件名加 PID |
| **U-L-8** | `[font-scale]`/`[ime-skip]` 只走 `LOG.info` | 改走 `writeFileAndReport`（独立文件） |

---

## 三、探针与变异测试

新探针 `tools/settings-probe/VAuditBatch2Round2to4.java` —— **24 条断言**，T-0 是桩自检。

| 断言 | 守什么 | 变异（改坏 → 必须红） | 实测 |
|---|---|---|---|
| T-0 | 桩自检：正常编辑器可挂载 | — | 绿 |
| T-1 | 构造抛 → install 返 false + warn 点名 | 删 install catch 的两条日志 | **红（warn=无）** |
| T-2 | 构造抛后 caret 监听器被摘 | 删 removeCaretListener 的 undo | **红（remove=0）** |
| T-3 | `setColor` 抛不得入账 | 恢复「先记账后写色」 | **红（账本 0→1）** |
| T-4 | 总线连接失败有一行日志 | 删 `ensureSchemeWatcher` 的 warn | **红（warn=无）** |
| T-5 | 非 EDT 必须转投（只证转发，线程正确性需实机） | 删 EDT 判断 | **红（非EDT 转投=0）** |
| T-5b | 非 EDT 时是「转发」不是当场执行：队列 1、色未变，drain 后才变 | 同 T-5（删转投） | 与 T-5 同组变异 |
| T-6 | 连续 3 次恢复失败 → 删账 + warn | 删 `n>=3` 分支 | **红（第3次仍留）** |
| T-7 | 完成行必须带失败数（端到端需实机） | 两分支写成同一句 | **红** |
| T-8 | shutdownCleanup 幂等 + 清空 ANIMATORS | 删 `ANIMATORS.clear()` | **红（收尾后=1）** |
| T-8b | 收尾挂在 Disposer 上（文本） | 删注册 | 未单独变异（与 T-8 同段） |
| T-9 | paintTrace 状态翻转 | 只记进入不记恢复 | **红（n3=1）** |
| T-10 | dispose 后四张表不含该 id | 删 `forget` | **红（后=true）** |
| T-11 | 无 `static int glowLayersNow` + 签名含参数 | 加回字段 | **红** |
| T-11b | `enabled()` 设置变更立即生效 | 改成布尔缓存 | **红（关时=true）** |
| T-12 | 空闲 31s 判退出 | — | 绿 |
| T-12b | loop 真调用空闲判定（文本） | 删调用 | **红** |
| T-13 | 文件名带 PID | 固定名 | **红** |
| T-13b | 源码走 `getLogPath` | 只留 `getConfigPath` | **红** |
| T-14 | `[frame]` 5000 行 + 1 汇总 | 删预算 | **红（5002）** |
| T-15 | `[font-scale]`/`[ime-skip]` 进独立文件 | 改回 `LOG.info` | **红（font-scale=false）** |
| T-16 | 写盘失败被 `checkError` 发现 | 短路 `checkError` | **红（writer 非 null）** |
| T-17 | 诊断文件「打不开」必须留痕（复查 H-1） | 删「首次失败」返回的事件 | **红（warn=无）** |
| T-18 | 连续打不开必须收敛：只补一条「已放弃」 | 删 `openFailures >= 3` 分支 | **红（已放弃=0）** |

**变异总数：E 批 6 组 + F 批 2 组 + B 批 8 组 + 复查 2 组 = 18 组，全部按预期变红，恢复后全绿。**

既有断言零放宽：门禁从 1048 PASS 增加到 1069 PASS，**没有一条旧断言被修改**（本批只新增探针文件与 V0Env 的默认不变注入开关）。

---

## 四、未验证 / 需实机

| 项 | 为什么离线证不了 | 实机验证方法 |
|---|---|---|
| **T-5 EDT 转投** | 探针只能证明「走了 `invokeLater` 分支」，桩里的 `invokeLater` 是立即执行 | 在非 EDT 的发布者下切主题，看是否不再出现中途态双光标 |
| **T-7 applyConfig 失败计数** | 端到端要造一个会抛的 animator，需要真编辑器 | 正常路径看日志仍为 `完成（…）`；失败路径需人为造故障 |
| **T-13 诊断文件真实落点** | 探针无真 `PathManager`，反射失败落到 tmpdir | 开诊断后确认文件在 `<log>/neovide-cursor-diag-<pid>.log`，不在 `<config>` |
| **U-M-15 看门狗负载收益** | 无法离线量化 20 次/秒 → 5 分钟后退出 | 开诊断挂 10 分钟，看 `neovide-cursor-edt-watchdog` 是否已退出、再卡顿是否还能抓到 |
| **U-M-20 平台前提** | javap 结论绑定本地 jar 版本 | 升级平台后复跑 `MessageBusImpl` 的 `immediateDelivery` 语义 |

---

## 五、挂起条目

| 条目 | 原因 | 需要人做什么 |
|---|---|---|
| **U-M-21 运行时禁用插件** | 真正的 unload 钩子要 `plugin.xml`（`postStartupActivity` / 插件 Disposable 注册），**超出本批白名单** | 决定是否允许改 `plugin.xml`；允许的话加一个 `DynamicPluginListener` 或 `Disposer` 注册的 service |
| **U-L-9 / U-L-11 / U-L-12 / U-L-13** | 本批范围只到 U-L-8（任务书列出的 11 条） | 后续批次 |

---

## 六、白名单外字节变化

**应为空。** 本轮改动文件（`git status --porcelain`，未提交）：

```
M  CLAUDE.md / START-HERE.md / docs/NEXT-STEPS.md   ← 探针数量 103→104、规模数字
M  src/.../ui/CaretAnimator.java
M  src/.../ui/CaretSchemeWatcher.java
M  src/.../ui/NeovideCaretManager.java
M  src/.../ui/CursorDiag.java
M  src/.../ui/EdtWatchdog.java
M  tools/settings-probe/V0Env.java
M  tools/settings-probe/build-v-probes.ps1
?? tools/settings-probe/VAuditBatch2Round2to4.java
?? plans/audit-fix-batch2-round2to4.md
?? docs/HANDOFF-BATCH2-ROUND2TO4.md（本文件）
```

`docs/BUG-LOG.md` 与 `docs/INDEX.md` 的 `M` 是**本轮开工前就存在的**（更早会话写的），本轮**未触碰、未 stage**。
`core/NeovideConfig.java`、`ui/RenderScheduler.java`、`CaretTrailPanel.java`、`SpringTrail.java`、`plugin.xml`：**零改动**。
本轮的本地变异脚本 `build/run-one-probe.ps1` 被 `.gitignore` 的 `build/*` 覆盖（`git check-ignore` 已确认），**不会进入提交**。

---

## 七、代码复查（code-reviewer + java-reviewer 一次性复查整个 diff）

**结论：1 条 HIGH + 4 条 MEDIUM + 13 条 LOW。HIGH 与可修的 MEDIUM/LOW 已在本轮内修完，并补了 2 条守门断言（T-17 / T-18）。**
两位复查者都确认：**成功路径零观感变化、探针与生产同源、没有恒真断言**。

| 编号 | 严重度 | 问题 | 处理 |
|---|---|---|---|
| **H-1** | **HIGH** | `ensureWriterLocked` 的 catch 丢弃异常 → 「诊断文件根本打不开」零日志，**相对旧代码是回归**（旧代码会 `emit` 一行） | **已修**：返回事件文案，首次与放弃各 `LOG.warn` 一条；T-17 / T-18 守门 |
| H-2 | HIGH（java-reviewer）/ MEDIUM（code-reviewer） | 「打不开」与「写失败」共用一个计数、重建用 TRUNCATE → 无限重建 + 每 64 行一条 warn + 反复抹掉已采证据 | **已修**：`openFailures`/`writeFailures` 分开计数、重建改 `APPEND`、成功打开不再清零写失败计数；T-18 守门 |
| M-1 | MEDIUM | `enabled()` 里 `EdtWatchdog.start()` 无 try → 线程创建失败会穿到 `paintComponent` | **已修**：包 try；`start()` 失败回滚 `started/owner` |
| M-2 | MEDIUM | `swallowed` 自身可能抛（服务定位/日志框架），会替换原始异常 | **已修**：整体 try 包裹；并 `LOG.info(text, t)` 保留异常本体（原来栈永远不存在） |
| M-3 | MEDIUM | 在 `synchronized (HIDDEN_SCHEMES)` 内做日志 IO / 服务定位 | **已修**：收集到 `pendingLogs`，出同步块再打 |
| M-4 | MEDIUM | 看门狗空闲退出 / 配额耗尽**零日志** → 「没有 `[edt]` 行」会被误读成「没有卡顿」 | **已修**：`CursorDiag.watchdogExit(reason)` 写一行「停止采样及原因」 |
| M-5 | MEDIUM | 文件名带 PID 后，每次 IDE 启动新增一个文件、无人清理 | **未修**（见「已知未修」） |
| L-1 | LOW | `applyNativeCaretHidden` 失败时保留旧主题的 `savedCaretColor` → restore 兜底可能写错色 | **已修**：失败时清空（账本条目仍能按条目恢复） |
| L-2 | LOW | 主题回调的「取 app / 判线程 / 转投」在 try 外（dispose 后可能抛穿总线） | **已修**：整段包 try，失败降级直接执行 |
| L-3 | LOW | `bus.connect` 成功后 `subscribe` 抛 → connection 泄漏 | **已修**：先登记再 subscribe |
| L-4 | LOW | undo 路径不置 `disposed` → 迟到 runTick 会 tick 一个失败对象 | **已修**：catch 首句置位 |
| L-5 | LOW | `shutdownHookInstalled` 在 `app == null` 时已置位 → 该 JVM 永不注册 | **已修**：先判 app 再置位 |
| L-6 | LOW | 字节上限按 char 计（UTF-8 中文虚高 3 倍）；死条件；sleep 中断后空转；`MAX_DUMPS` 注释 6 vs 20；两张表 clear 锁不成对 | **已修** |
| L-7 | LOW | 探针脚手架 `queueInvokeLater` / `nullMessageBus` 从未使用（死脚手架） | **已修**：T-5b 用队列模式真正断言「转发而非当场执行」；`nullMessageBus` 删除 |
| L-8 | LOW | `VTrailCap` 用反射写 `fileTried`/`fileWriter`，绕过 `FILE_LOCK` | **无法修**（既有探针不在白名单，且它是第 1 批的既有资产）；风险已登记 |
| L-9 | LOW | 看门狗只在「新建编辑器 / 诊断关→开」时重启，空闲退出后不再自启 | 设计取舍；现已留痕，不再「无日志 = 无卡顿」 |

### 已知未修（明确登记，不当成已解决）

| 项 | 原因 / 建议 |
|---|---|
| **PID 文件累积**（M-5） | 用户 log 目录里会积累 `neovide-cursor-diag-<pid>.log`。**删除用户文件属于「先列清单确认」的动作**（项目纪律），本轮不做。建议下批加：打开时清理同前缀、mtime 超过 7 天的旧文件 |
| `VTrailCap` 反射绕过锁（L-8） | 探针不在本批白名单 |
| `-Dneovide.cursor.diag=true` 启动时不拉看门狗（L-11） | 低价值：该模式只在开发者调试时用 |
| `[ime-skip]` 改为 EDT 同步 `flush()`（L-7） | 频率约每 5 帧一次、量级很小；换来的是「回收日志的人能看到它」 |
| `applySummary`/`applySummaryForProbe` 双 public、`EdtWatchdog` 整类 public | 探针在默认包，这是最小必要面；已写注释说明 |

---

## 八、下一步

1. 用户醒来先跑一次门禁确认（命令见第一节）。
2. 决定 U-M-21 是否允许改 `plugin.xml`。
3. 若认可，按批次提交（建议 E / F / B 三次提交，回退粒度独立；`docs/BUG-LOG.md`、`docs/INDEX.md` 不 stage）。

---

自动关机已触发（120 秒后）。恢复上网/继续工作请先开机。
取消方式：`shutdown -a`（120 秒窗口内）。
