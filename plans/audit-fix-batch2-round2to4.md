# 审计第 2 批 · 第 2~4 轮修复计划（E → F → B）

> 依据：`docs/CODE-AUDIT-2026-10-30.md` 第八节。
> 基线：HEAD `8ee1d46`，`fork/main...HEAD = 0 0`，门禁 **100 条目 / 1048 PASS / 2 FAIL（仅 VRegr2，informational）/ ALL PASS**，D0 零 diff。
> 三批必须**零观感变化**：每条修复都要说明「正常路径走不到这里」。

---

## 零、阶段 0：Research & Reuse（三批共用，一次）

本批不引入任何新依赖、新类、新机制。四处都复用**项目里已有且被证明过的**东西：

| 需要的东西 | 复用什么 | 出处 |
|---|---|---|
| 异常留痕 | `CursorDiag.swallowed(where, t)` | 0.9.88 起的统一安静通道；diag 关时第一句就返回，零成本 |
| 编辑器读取 | `CaretAnimator.readEditor(...)`（`ReadAction.compute`） | 平台 252 冻结的修法，`VReadAction` 守门 |
| 探针读私有状态 | `*ForProbe()` 访问器惯例 | `savedCaretColorForProbe()` / `hiddenSchemeCountForProbe()` 等 |
| 离线平台桩 | `V0Env`（Application/Colors/Bus/EditorFactory/Props） | 60 个既有探针共用 |
| 日志断言 | `Logger.setFactory` 捕获（`VAuditBatch1` 的模式） | 第 1 批已证明可用 |
| 单探针快速红绿循环 | 新增 `build/run-one-probe.ps1`（未入库，仅本地） | 全量门禁每次 100 个探针，变异测试需要更快的循环 |

**没有搜索外部实现的原因**：这 20 条全部是「本项目自己的 catch 静默 / 状态记账顺序 / 生命周期」，
不是通用算法，外部库无从复用；同类修法在本仓库已有 8 处先例（6f7d585 / 0410976），
本批是**沿用同一判据把漏网点补齐**，不是发明新机制。

---

## 一、E 批：原生光标与挂载链（7 条）

### E-1 `U-H-5` install() 空 catch + `U-M-22` 构造期 this 逃逸

**根因**：`NeovideCaretManager.install()`（268-314）把整个挂载流程包在
`catch (Throwable t) { /* 吞掉 */ }` 里。`CaretAnimator` 构造器（745-863）
在中段已经把 `this` 交给了三个持有者：

| 步骤 | 交出给谁 | 失败后现状 |
|---|---|---|
| `content.add(panel)`（788） | Swing 容器 | panel 永久挂在编辑器上 |
| `addCaretListener(this)`（802-805） | 编辑器 CaretModel | 监听器永久挂着，animator 不可回收 |
| `InlayModel.addListener(..., inlayListenerHandle)`（816-839） | 编辑器 | 有 `Disposer` 句柄可回收（已 try/catch） |
| `RenderScheduler.register(this)`（848） | 全局静态表 | 调度线程因集合非空永不退出 |

**改法**（不做 init() 两段式）：

1. `install()` 返回 `boolean`，catch 里
   `LOG.warn(..., safeEditorText(editor))` + `CursorDiag.swallowed("installEditor", t)`；
   返回 `false`。**跳过的编辑器（无 project / 终端）返回 `true`** —— 它们是正常路径。
2. 三个调用点汇总：
   - `editorCreated` 的 `initialScanDone` 循环：统计失败数，`N > 0` 时打**一行**「本轮挂载失败 N 个」；
   - `editorCreated` 单编辑器：`install()` 自带 warn，不重复汇总；
   - `setEnabled(true)` 循环：同上汇总一行。
   - 新增私有 `installAll(Collection<Editor>, String where)` 统一这三处（其中两处是循环）。
3. `CaretAnimator` 构造器：把构造体包进 `try { ... } catch (Throwable t) { rollbackPartialInstall(); throw t; }`。
   `rollbackPartialInstall()` 按**逆序**摘除，且每一步单独 try：
   ```
   startScheduler 之后 → RenderScheduler.unregister(this)
   addCaretListener 之后 → readEditor(removeCaretListener)
   content.add(panel) 之后 → content.remove(panel)
   inlay 句柄 → Disposer.dispose(inlayListenerHandle)
   ```
   用三个 `boolean` 字段（`panelAttached` / `caretListenerAttached` / `schedulerAttached`）登记「做到哪一步」。
   它们只在构造器里写、dispose 里读一次再置 false（防重复摘除）。
4. **final 字段安全**：`content` / `panel` 若在赋值前抛出，字段保持 `null`（Java 实例字段有默认值，
   构造器内读取合法）。`rollbackPartialInstall()` 每一步都判 `!= null` / 标志位，不会 NPE。

**为什么不改成 `init()` 两段式**：两段式会让 `new CaretAnimator(...)` 返回一个**半成品对象**，
调用方忘调 `init()` 就得到一个永不工作的 animator —— 比现在更难发现。
「构造器要么给出完整对象、要么抛异常」是更少的失败模式。

**观感**：正常路径（构造成功）一行行为都不变；只有原来被静默吞掉的失败变成「有日志 + 不上账」。
`ANIMATORS.computeIfAbsent` 在构造抛异常时本来就不会写入，所以「不上账」是既有行为。

**探针**：T-1（构造抛 → install 返回 false + warn）、T-2（抛后 CaretModel 上无监听器残留）。
**变异**：① 把 `install` 的 catch 改回空吞 → T-1 红；② 删 `rollbackPartialInstall()` 的
`removeCaretListener` 那一步 → T-2 红。

---

### E-2 `U-H-6` applyNativeCaretHidden 先记账后写色

**根因**：`:732` `HIDDEN_SCHEMES.putIfAbsent(...)` 在 `:735` `scheme.setColor(透明)` **之前**。
`:735` 抛出 → 账本说「已隐藏」，实际没写 → 早退判据（`:712`）此后永久早退，双光标不可救。

**改法**：交换顺序 —— 先 `scheme.setColor(...)`，成功后才 `putIfAbsent`；
catch 接 `CursorDiag.swallowed("hideNativeCaret", t)`。`repaintAllEditors()` 留在最后。

**观感**：成功路径的副作用顺序不影响结果（两处都执行）；失败路径从「记假账」变成「不记账 + 留痕」。

**探针**：T-3（`setColor` 抛 → `hiddenSchemeCountForProbe()` 不变）。
**变异**：把两行换回原顺序 → T-3 红。

---

### E-3 `U-M-17` restoreNativeCaret 九处 catch 静默

**根因**：循环里 `setColor` 失败被静默吞，条目永久留在表里「等下次重试」，
而重试源是「用户亲手再拨一次开关」——事实上永远不会发生。

**改法**：
1. 新增 `IdentityHashMap<EditorColorsScheme,Integer> RESTORE_FAILURES`（与 `HIDDEN_SCHEMES` 同样
   `synchronized` 包装、同样用 `==` 语义）。
2. 循环内 catch：`n = RESTORE_FAILURES.merge(s, 1, Integer::sum)`；
   `n == 1` → `CursorDiag.swallowed("restoreNativeCaret", t)`；
   `n >= 3` → `it.remove()` + `RESTORE_FAILURES.remove(s)` + **一次** `LOG.warn`
   （「连续 3 次恢复失败，已从账本移除，请手动检查配色方案」）。
3. 成功恢复时 `RESTORE_FAILURES.remove(s)`（计数归零）。
4. 其余 5 处 catch（取当前 scheme、兜底 setColor、外层）接 `swallowed`。

**观感**：成功路径零变化。失败路径：原来「永久静默重试」，现在「3 次后放弃并明说」。
**探针**：T-6（一直抛的 scheme，连调 3 次 → 表里没有它了 + 有 warn；前 2 次不在表外）。
**变异**：把 `n >= 3` 分支删掉 → T-6 红。

---

### E-4 `U-M-18` handleSchemeChange 静默 + `U-M-20` EDT 假设

**根因**：`CaretSchemeWatcher.handleSchemeChange` 的 catch 空吞；且类注释断言
「回调在 EDT」——javap 已否证（TOPIC 无 `immediateDelivery`，总线投递不改线程）。

**改法**（防御式，不用 `assertIsDispatchThread()`）：
```java
public void handleSchemeChange(EditorColorsScheme scheme) {
    if (!isDispatchThreadNow()) {
        Application app = ApplicationManager.getApplication();
        if (app != null) {
            app.invokeLater(() -> handleSchemeChangeOnEdt(scheme));   // 转投，不递归
            return;
        }
    }
    handleSchemeChangeOnEdt(scheme);
}
```
- `handleSchemeChangeOnEdt` = 原来的 try/catch 体，catch 接 `CursorDiag.swallowed("schemeChange", t)`。
- `isDispatchThreadNow()`：优先读 **包级可注入字段** `edtOverrideForProbe`（探针用），
  否则 `ApplicationManager.getApplication() != null && app.isDispatchThread()`；
  Application 为 null（离线/早期）时按「是 EDT」处理（直接执行，不排队）。
- **为什么拆两个方法**：V0Env 桩把 `invokeLater` 实现成立即 `run()`，
  若转发后再次进入同一个「判断 + 转发」方法就会无限递归。`...OnEdt` 不再判断，结构上断了递归。

**观感**：EDT 上调用（正常路径）完全不变；非 EDT 调用从「直接写全局颜色（中途态）」变成「转投 EDT」。

**探针**：T-4（`V0Env.throwOnBusConnect` → install 返回 false 且留痕）、
T-5（`dispatchThreadOverride=false` → `V0Env.invokeLaterCalls` +1 且最终效果等价；
**断言在「转投发生」上，不在「结果正确」上** —— 后者在桩里恒真，AC-008 红线）。
实机边界：EDT 转发的正确性仍需实机一验（探针只证明「走了转投分支」）。
**变异**：删掉 `if (!isDispatchThreadNow())` 整段 → T-5 红（invokeLaterCalls 差 1）。

---

### E-5 `U-M-16` installSchemeWatcher 返回值被丢弃

**根因**：`NeovideCaretManager:153/394/644` 三处 `installSchemeWatcher();` 不看返回值；
`CaretSchemeWatcher.install()` 的 catch 也空吞。

**改法**：
1. `CaretSchemeWatcher.install()` catch → `CursorDiag.swallowed("installSchemeWatcher", t)` + `LOG.warn` 一次。
2. `NeovideCaretManager` 新增 `ensureSchemeWatcher()`：
   ```java
   if (installSchemeWatcher()) return;                  // 本次真正建立了订阅
   if (CaretSchemeWatcher.getInstance().isInstalled()) return;  // 幂等命中，正常
   if (!schemeWatcherWarned) { schemeWatcherWarned = true; LOG.warn("主题监听未能安装…"); }
   ```
   三处调用点都换成它 —— **一次一行，不刷屏**（每个 editorCreated 都会调，逐次记就是洪水）。

**观感**：零变化（正常路径返回值 true 或已安装）。
**探针**：T-4 覆盖「装不上时有一行日志出口」。
**变异**：把 `ensureSchemeWatcher` 的最后一段 warn 删掉 → T-4 红。

---

## 二、F 批：其余生命周期（2 条）

### F-1 `U-M-24` applyConfigOnEdt 无条件打「完成」

**根因**：S2..S6 的 catch 吞掉 setter 链失败后，末尾仍打「applyConfig 完成」
—— 与第 1 批 H-2 同型（失败仍宣称成功）。

**改法**：局部 `int failures = 0`，S2..S6 的 catch 里 `failures++`；
S7 两个 catch 各 `failures++`；完成行改成
`完成（animators=N，失败=K）`，`K > 0` 时用 `LOG.warn` 而非 `info`。

**观感**：零变化（成功路径文案多一个「失败=0」）。
**探针**：T-7（注入一个会抛的 animator → 完成行含 `失败=1` 且是 warn）。
  注入方式：`ANIMATORS` 是私有静态表，探针拿不到 —— 用**源码文本断言**（行存在）+ 日志断言
  （`V0Env` 里没有 animator 时失败数恒 0）。**T-7 降级为「完成行必须带失败计数」的文本断言**，
  并注明「实际计数需实机验证」；不写恒真断言。

### F-2 `U-M-21` 无 unload/disable 收尾钩子

**边界判定**：真正的插件 unload 钩子需要 `plugin.xml`（`postStartupActivity` / `Disposable` 注册），
**超出白名单**。按任务书 §十二：**登记挂起，不强行做**。

**白名单内的最小接点**（做）：在 `NeovideCaretManager` 首次 `editorCreated` 时
`Disposer.register(ApplicationManager.getApplication(), this::shutdownCleanup)`：
- `shutdownCleanup()`：遍历 `ANIMATORS` 调 `dispose()`、`ANIMATORS.clear()`、
  `CursorDiag.forget`（见 B-4）。幂等，每步 try/catch + `swallowed`。
- 覆盖范围：**JVM 退出 / 平台 Application dispose**。**不覆盖**「运行时禁用插件」。

**探针**：T-8（源码文本：`Disposer.register` 存在 + `shutdownCleanup` 方法存在；
可离线断言的只有「方法存在且幂等」——用反射调两次不抛）。

---

## 三、B 批：诊断基础设施（11 条）

### B-1 `U-H-7` + `U-M-12` 写文件失败静默 + 跨线程无守卫（合并改）

**改法**：
1. `writeFile` 整体 `synchronized (CursorDiag.class)`（`fileWriter` / `fileTried` 不再裸奔）。
2. 每 `FILE_CHECK_EVERY = 64` 行 `fileWriter.checkError()`；为真 →
   `close()` + `fileWriter = null` + `fileTried = false`（**限次重建** `MAX_FILE_REOPEN = 3`），
   并向 `LOG.warn` 打**一次**「独立文件写入失败，[tick] 类证据已中断，后续改走 idea.log」。
3. 关闭失败也 try/catch，不递归 `emit`（原来的 `emit` 在 catch 里会造成递归写文件）。

**观感**：诊断关闭时整段不执行；开启时正常路径多一次 `checkError()`（PrintWriter 的
`checkError` 只读一个 boolean，代价可忽略）。

### B-2 `U-H-8` paintTrace 终身只响一次

**改法**：`Trace` 增加 `boolean lastEmptyPaint` / `boolean lastPaintError`；改为**状态翻转才记**：
- `empty` 由 false→true 记一行（进入「光标消失」形态）；
- 由 true→false 也记一行（恢复），并在行尾加 `recovered=true`；
- 异常类名变化（`lastPaintErrorClass` 不同）也记一行 —— 不同类型异常不能互相吃掉名额。
- 慢绘制（`costNs >= TRACE_SLOW_PAINT_NS`）逻辑保留。

**探针**：T-9（同一 animator：空绘制 → 记录 1 行；再空 → 0 行；恢复 → 1 行；
再次空 → 1 行）。**变异**：改回终身去重 → T-9 红。

### B-3 `U-M-11` enabled() 每帧多次全局查找

**改法**：
1. `volatile NeovideCursorSettings cachedService`：只缓存**服务引用**，
   每次仍读 `state.diagLogging` —— 这样设置一变立即生效（既有 `VReadAction`
   在运行中翻转 `diagLogging`，缓存布尔值会让它变红；缓存引用不会）。
2. catch：`LOG.warn` **一次**（`lookupWarned` 标志）+ `cachedService = null`。
3. `volatile boolean diagSnapshot`：每次 `enabled()` 写一次；新增
   `enabledForBackgroundThread()` 读快照，供 `EdtWatchdog` 线程用（跨线程可见性）。
4. 失效点：`NeovideCaretManager.applyConfig()` 开头 + `syncStateToConfig()` 开头
   调 `CursorDiag.invalidateEnabled()`（清服务缓存）。

**观感**：零变化。

### B-4 `U-M-13` 三张表不回收

**改法**：`CursorDiag.forget(int animatorId)`：删 `TRAIL_BUDGETS` / `SAMPLE_STATE` / `TRACES`
+ 新增的 `EMIT_BUDGETS` 四个键。`CaretAnimator.dispose()` 末尾调
`CursorDiag.forget(traceId)`。新增 `public static int[] tableSizesForProbe()` 供断言。

**探针**：T-10（构造 → 用诊断 → dispose → 四张表都不含该 id）。
**变异**：删 dispose 里的 `forget` → T-10 红。

### B-5 `U-M-14` glowLayersNow 是 static

**改法**：删 `CursorDiag.glowLayersNow`；`frameSummary(...)` 增加 `int glowLayers` 参数；
`CaretAnimator` 两处赋值改为把 `effectiveGlowLayers` / `config.glowLayers` 传给 `frameSummary`。

**探针**：T-11（源码文本：`static int glowLayersNow` 已不存在 + `frameSummary` 签名含 glowLayers）。

### B-6 `U-M-15` EdtWatchdog 三重失效

**改法**：
1. `CursorDiag.enabled()` 从 false→true 时调 `EdtWatchdog.start()`（B-3 的接线）；
   `CaretAnimator` 构造器那处保留（幂等）。
2. 空闲退出：`IDLE_EXIT_NANOS = 30s`；循环里若 `now - lastDumpNs > IDLE_EXIT_NANOS`
   → `started = false` + `return`（下次 enabled() 翻转或新 animator 会重启）。
3. javadoc 改成与实现一致：「抓够 MAX_DUMPS 次**或**连续 30 秒无卡顿即退出；
   退出后可由下一次诊断开启/新 animator 重新拉起」。
4. `loop()` 整体 try/catch（日志抛出不能杀 watchdog 自己）。

**探针**：T-12（反射读 `started`，调 `start()` 两次只起一条；把「空闲退出」判定抽成
包级纯函数 `idleExpired(long lastDumpNs, long nowNs)` → 断言 30s 后为 true）。
**变异**：删空闲退出分支 → T-12 红。

### B-7 `U-M-26` 诊断文件写在 config 目录

**改法**：`diagFile()` 反射改调 `PathManager.getLogPath()`（保留 `getConfigPath` 兜底，
最后回退 `java.io.tmpdir`）；失败时 `LOG.warn` 一次。

**探针**：T-13（源码文本含 `getLogPath`）。落点正确性**需实机验证**（探针无真 PathManager）。

### B-8 `U-M-27` 5000 行上限只接 2/10 出口

**改法**：
1. `emit()` 加**总闸门**：`TOTAL_MAX_LINES = 200_000`；超限后打一条
   `LOG.warn` 说明并停止 `LOG.info` + 文件写入（`silenced` 标志）。
2. `[frame]` / `[caret]` / `[pick]` 走新的 `emitBudgeted(animatorId, line)`：
   每 animator `TRACE_MAX_LINES` 行，超限只补一条汇总（与 `[trail]` 同策略）。
   行格式**逐字节不变**（闸门只决定打不打，不参与拼接）——`build/analyze-log.ps1` 不受影响。
3. 文件字节上限 `FILE_MAX_BYTES = 16MB`：累计 `line.length()+1`，超限关闭文件 + 一条 warn。

**探针**：T-14（`emitBudgeted` 超限后行数不再增长且补一条汇总；总闸门超限后 `LOG.info` 不再增长）。

### B-9 `U-L-7` 固定文件名 + TRUNCATE 双实例互截断

**改法**：文件名 `neovide-cursor-diag-<pid>.log`。
「每次启动清空」语义保持：同一 PID 重启时 `TRUNCATE_EXISTING` 仍清空；不同 PID 是不同文件
（这正是修复目标）。

**探针**：T-13（`diagFile()` 文件名含当前 `ProcessHandle.current().pid()`）。

### B-10 `U-L-8` [font-scale]/[ime-skip] 只走 LOG.info

**改法**：两处 `LOG.info(...)` → `writeFile(...)`（诊断开启时已有 `enabled()` 早退）。
与 `:515` 的纪律「boot/evt/tick/geom/paint/clear 只写独立文件」一致。

**探针**：T-15（源码文本：`fontScaleResync` / `imeSkipPicked` 里不再有 `LOG.info`）。

### B-11 `U-M-21` 见 F-2（同一条，跨批登记）

---

## 四、探针断言清单（`VAuditBatch2Round2to4`）

| 编号 | 断言 | 对应 | 变异方法（必须变红） |
|---|---|---|---|
| T-1 | 构造抛 → `installForProbe` 返回 false 且 warn 含 `installEditor` | E-1 | 空 catch |
| T-2 | 构造抛后 CaretModel 的 removeCount == addCount | E-1 | 删清理里的 removeCaretListener |
| T-3 | `setColor` 抛 → `hiddenSchemeCountForProbe()` 不变 | E-2 | 换回先记账后写色 |
| T-4 | 总线连接失败 → install 返 false + 有 warn 出口 | E-4/E-5 | 删 ensureSchemeWatcher 的 warn |
| T-5 | `dispatchThreadOverride=false` → invokeLaterCalls +1（**只证转投**） | E-4 | 删 EDT 判断 |
| T-6 | 连续 3 次恢复失败 → 账本移除 + warn | E-3 | 删 `n>=3` 分支 |
| T-7 | 完成行含 `失败=`（文本断言，计数需实机） | F-1 | 删失败计数 |
| T-8 | `Disposer.register` 与 `shutdownCleanup` 存在且幂等 | F-2 | 删注册 |
| T-9 | paintTrace 状态翻转：空→记 1，再空→0，恢复→1，再空→1 | B-2 | 改回终身去重 |
| T-10 | dispose 后四张表都不含该 id | B-4 | 删 forget |
| T-11 | 源码无 `static int glowLayersNow`；frameSummary 签名含 glowLayers | B-5 | 恢复 static |
| T-12 | `idleExpired(31s)==true` | B-6 | 删空闲退出 |
| T-13 | 源码含 `getLogPath`；文件名含 `-diag-<pid>.log`（落点需实机） | B-7/B-9 | 去掉 pid |
| T-14 | 预算超限后行数不增 + 一条汇总 | B-8 | 去掉预算 |
| T-15 | `fontScaleResync`/`imeSkipPicked` 不含 `LOG.info` | B-10 | 改回 LOG.info |
| T-16 | `checkError` 失败后重建受限（用可注入的失败 writer） | B-1 | 删 checkError |

---

## 五、实施顺序

```
E 批：探针 T-1~T-6 + 生产入口脚手架（installForProbe）
   → 跑单探针（RED）→ 改 5 个生产文件（GREEN）→ 跑单探针（绿）
   → 6 次变异（每条 1 次）→ 恢复 → **全量门禁**
F 批：T-7/T-8 → 改 → 变异 → 恢复 → **全量门禁**
B 批：T-9~T-16 → 改 → 变异 → 恢复 → **全量门禁**
→ code-reviewer + java-reviewer 复查整个 diff
→ 三份文档 103→104、报告落盘
```

**每批结束的门禁命令**（不带 `-Repo`，绝不并发）：
```
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1
```

---

## 六、风险与已知边界

| 风险 | 处理 |
|---|---|
| 既有探针 `VReadAction` 在运行中翻转 `diagLogging` | B-3 只缓存**服务引用**、不缓存布尔值 → 不受影响 |
| `VTabPages` / `VAddSetting` / `VPreset` 的控件与字段计数 | 本批不加 State 字段、不加 UI 控件 → 不触发契约变更 |
| `VReadAction` 驱动 `CaretAnimator` 构造 | 构造器加 try/catch 不改变成功路径；失败路径它本来就不测 |
| `VDocCheck` D-2 要求三份文档 = 磁盘实测 | 新增 1 个探针 → 103→104，三份文档同步改 |
| `build/analyze-log.ps1` 解析 `[frame]`/`[trail]` 固定列 | B-8 只加闸门、不改行格式 |
| U-M-21 真正 unload 需 plugin.xml | 超白名单 → 只做 `Disposer.register` 最小接点，报告登记 |
| T-5/T-7/T-13 无法完全离线断言 | 断言降级并**写明「需实机验证」**，不写恒真断言（AC-008） |
