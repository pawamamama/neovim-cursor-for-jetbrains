# 审计第 2 批修复 · 第 1 轮计划（调度面与 read action 覆盖面）

> 依据：`docs/CODE-AUDIT-2026-10-30.md` 第八节 · 任务书《审查第 2 批修复 · 第 1 轮》
> 状态：**待用户确认后开工**（本轮停在工作区，不提交）
> 编写时间：2026-10-30

---

## 零、前置检查（实测原文）

```
$ git status --porcelain
 M docs/BUG-LOG.md
 M docs/INDEX.md
?? build/night-run-report.md
?? docs/CODE-AUDIT-2026-10-30.md
?? docs/HANDOFF-DECISION-2026-10-30.md

$ git log --oneline -3
6f7d585 fix(robustness): 审计第 1 批 8 条——失败可观测化 + NaN 守卫（零观感变化）
36cbaa4 fix(install): 结构指纹漏计 *Global 工厂，把 27 个字段数成 25
f112bff docs: 新增 2026-10-30 交接文档

$ git rev-list --left-right --count fork/main...HEAD
0	0

$ git status --porcelain -- src/ | wc -l
0
```

| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| HEAD | 6f7d585 | 6f7d585 | ✅ |
| 与 fork/main 同步 | 0 0 | 0 0 | ✅ |
| 已修改（M） | 只有两个文档 | 只有两个文档 | ✅ |
| `src/` 是否干净 | 干净 | 0 行改动 | ✅ |

### 门禁基线（`pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1`，仓库根，单份运行）

```
总条目      100
PASS        1035
FAIL        2   （仅 VRegr2 的 R-5 / R-6，informational，不计入通过门）
末行        V-PROBES RESULT: ALL PASS   （exit 0）
```

统计方法：脚本对每个 `$mains` 探针写 `build\v2-probe\<Probe>.txt`；本次运行产物共 100 个
（按 mtime 13:52–13:54 筛出，目录里另有 17 个更早的残留 txt，不属于本次口径），
`awk` 对其中 `PASS ` / `FAIL ` 行求和得 1035 / 2。**非目测**。

---

## 一、陷阱记录检索（四处，逐条命中/未命中）

### ① `CLAUDE.md` 第五节十四类陷阱

| 陷阱 | 命中？ | 与本批的关系 |
|---|---|---|
| 陷阱 9（`invokeLater` 抛出 → 永久活锁） | **命中** | 0.9.88 的修复本体（回滚两个标志）覆盖完整（第 2 批 §8.3 正面结论 1 已复核）。本批是**退出后的世界**：U-H-3（置 null 后没人重建）、U-H-4（catch 体自己抛）、U-M-5（回滚抹掉 `posted`）。 |
| 陷阱 4（探针假通过） | **命中（本批核心）** | `VReadAction` 给 `getAllCarets`/`removeCaretListener` 挂了 guard，却从不驱动 `runTick`/`dispose` → U-H-2 / U-M-19 在 1035 PASS 下静默存在。`VReviewFixes` A-1 断言的是源码 token（`contains("refreshFailureCount")`），字段恒为 0 也绿 → U-M-4 的「假通过②（断言太弱）」。 |
| 陷阱 2（字段接通 ≠ 生效） | **命中（反向形态）** | U-M-4 的 `refreshFrameGap` 是「声明了、读了两处、写入也写了，但全仓没有一处 `+1`」——比 `targetFps` 事故更隐晦：字段名看起来在限流，实际恒 0，`>= 1000` 恒假。 |
| 陷阱 3（探针复刻逻辑） | 未命中 | 本批新断言全部调用生产方法（`readEditor` 包装、`shouldLogFailure` 判据），不复刻算法。 |
| 陷阱 14（D0 门禁） | 未命中（本批不碰 `core/NeovideConfig.java`） | 收工前仍跑 `git diff --exit-code` 自证。 |

### ② `docs/WORK-DISCIPLINE.md` 四-E / 四-G / 四-J

- **四-E**（字段接通 ≠ 生效）——命中：U-M-4 的 `refreshFrameGap` 是「写侧存在、读侧存在、增量不存在」的第三种形态。
- **四-G**（探针会骗你，纪律 1-3）——命中：本批两条探针缺陷分别是
  「假通过②：断言太弱」（`VReviewFixes` A-1，源码文本断言）与
  「覆盖面缺失：探针从不驱动被测路径」（`VReadAction` 不跑 tick/dispose）。
  四-G 第 3 条「把字段/方法改坏，探针必须红」→ 本批每条修复都配变异测试。
- **四-J**（看可维护性，不是最小改动）——命中两条判据：
  - 判据 4「产生死代码 → 要么接回去，要么删干净」：`refreshFrameGap` 改计数取模后**必须删除**，
    不许留着。同理 `FAILURE_LOG_EVERY` 保留（新判据要用）。
  - 判据 2「同一语义存在于两处 → 先统一再改」：两处失败限流（`refreshTarget` 的 catch 与
    `runTick` 的 catch）本批统一走**同一个** `shouldLogFailure(long)` 判据，
    不许各写一份取模表达式。

### ③ `docs/PERFORMANCE-FPS.md` 已证伪清单

命中，四条禁令全部遵守：本批**不重提** `timeBeginPeriod` / 忙等 / `parkNanos` / 描边替代。
本批唯一与性能沾边的是 U-M-5（warn 洪水 1000 行/秒 → 日志 IO 拖慢调度线程），
方向是「减少异常路径的无效 IO」，不涉及睡眠精度。
另外 U-H-3 的修法会让失败路径每 1ms 重试一次——这是**既有行为**（`BUSY_RETRY_MS` 已存在），
本批不改退避长度，不新增空转。

### ④ `docs/CODE-AUDIT-2026-10-30.md` 第八节对应条目 + §8.6 待确认 5

- §8.6 第 5 条原文：「VReadAction 盲区修复需要改探针……**这是本批最重要的一条『验证方法存在但不能现在用』**：
  U-H-2/U-M-19 的修复落地前，门禁对这两条路径永远全绿。」
  → 本轮正是执行这条：先改探针让它红（AC-001），再修生产代码。
- §8.7 末行「变异测试 | 本批白名单禁改生产代码与探针，无法执行」→ 本轮补上，8 条各一组。
- §8.6 第 1 条（U-H-1 需实机）与第 3 条（U-M-20 javap 绑定平台版本）**不在本批范围**，不碰。

---

## 二、阶段 0 · Research & Reuse

### 2.1 仓内范式（找到并复用）

| 需要的东西 | 仓内现成范式 | 复用方式 |
|---|---|---|
| 编辑器读取必须包 read action | `CaretAnimator.readEditor(...)`（`:1563`，实现 `ReadAction.compute`），注释 `:1536-1562` 记录了「在 EDT 上 ≠ 持有读锁」的 252 教训 | U-H-2 / U-M-19 直接用同一个 helper，不新写包装 |
| caret 事件读取 + 异常兜底 | `traceCaretEvent`（`:893-905`）已经在 `readEditor` 外**包了 try**，并接 `CursorDiag.swallowed` | U-M-9 照抄这个结构（一次取值 + try + swallowed） |
| 失败日志限流 | `refreshTarget` 的 catch（`:2219-2225`）已有「首帧 + 每 1000 次」的意图 | 抽成 `shouldLogFailure(long)`，`refreshTarget` 与 `runTick` 共用（U-M-4 + U-M-25） |
| 调度线程的锁与重建 | `register`（`:85-98`）已用 `synchronized (RenderScheduler.class)` 统一判空/重建；`loop` 的退出分支用同一把锁 | U-H-3 的重建走同一把锁，不引入第二把（死锁分析的依据） |
| 探针造 252 环境 | `VReadAction` 的 `guard(...)` + `V0Env.inReadAction()` | 新用例复用同一套桩，只在桩上加「让 tick 走到底」所需的最小 shim |
| 探针做行为断言 | `VReadAction` 的 R-10「自证」、`VFieldDomain` | U-M-4 的断言改为「调用生产判据」，不是读源码文本 |

### 2.2 外部检索（如实记录）

- 本会话的 Web 检索工具**无实时联网**（返回的是模型自述，不是真实结果），因此**没有可引用的外部来源**。
- 平台语义一律以**仓内既有实证**为准：
  - `ReadAction.compute` = `computeBlocking → Application.runReadAction(ThrowableComputable)`（`:1544-1551` 已记录，
    且嵌套调用由平台去重，注释 `:1558`）；
  - `CaretModelImpl.getAllCarets()` 在 `monitorexit` 后 `List.sort(...)`（第 2 批 java-reviewer 的 javap 实证，见 §8.3 U-H-2）；
  - 生产侧已在 EDT 上大量使用 `readEditor`（`refreshTarget`、构造、caret 事件），0.9.104 实机验证过（记忆 `platform-252-readaccess-fixed`）。
- **结论**：本批不引入任何新的平台假设；所有平台调用都走仓内已验证的同一个 helper。

---

## 三、8 条逐条方案

> 每条格式：**根因 → 改法 → 正常路径影响 → 验证 → 变异测试**。
> 生产代码只动两个文件：`ui/CaretAnimator.java`、`ui/RenderScheduler.java`。

### 1) U-H-2 · tick 链里 `getAllCarets()` 未包 read action

- **根因**：`tickSpring` 的 `CursorDiag.frameSummary(...)` 实参在调用点求值，
  `editor.getCaretModel().getAllCarets()` 在 `if (diagFrame)` 分支里裸调；
  252 类平台在 `getAllCarets()` 内部排序时抛 `Read access is allowed from inside read-action only`。
- **改法**（选「整体包进 `readEditor`」——任务书二选一里改动最小、且不改诊断语义的那个）：
  ```java
  if (diagFrame) {
      double spread = ...;
      // 【平台 252】getAllCarets() 是编辑器读取：与 refreshTarget 走同一个包装，
      // 不能因为"在 EDT 上"就省略（tick 由 invokeLater 投递，EDT 上不等于持有读锁）。
      int caretCount = readEditor(() -> editor.getCaretModel().getAllCarets().size());
      CursorDiag.frameSummary(System.identityHashCode(this), frameSeq,
              caretCount, diagTotal, false, anyAnimating, spread, measuredFps);
  }
  ```
  不选「改用本帧已有计数」：`diagTotal = activeTrails.size()` 是**拖尾数**，
  与 caret 数在静态光标 / 块选区 / 合并光标三种情形下不相等，会悄悄改掉 `[frame]` 日志语义。
- **正常路径影响**：只在 `diagFrame == true`（诊断开启且 `frameSeq % 15 == 0`）时多一次
  `ReadAction.compute`（平台侧 ThreadLocal 深度 +1/-1，纳秒级）；诊断关闭时该分支整体不进。
- **验证**：`VReadAction` 新增 R-11（驱动 `runTick()` 到 `diagFrame` 命中，断言零违规）。
- **变异测试**：把 `readEditor(...)` 拆掉 → R-11 必须红。

### 2) U-M-19 · `dispose()` 摘 caret 监听器未包 read action

- **根因**：`:2865` 裸调 `editor.getCaretModel().removeCaretListener(this)`，
  与注册侧 `:801-805` 的显式 `readEditor` 口径矛盾；252 上 `getCaretModel()` 抛断言被 `catch (Throwable ignored)` 吞掉，
  监听器永久挂在正在销毁的 `CaretModel` 上。
- **改法**：
  ```java
  try {
      // 【平台 252】与注册侧同一口径：getCaretModel() 是编辑器读取，注销也要进 read action。
      readEditor(() -> {
          editor.getCaretModel().removeCaretListener(this);
          return null;
      });
  } catch (Throwable ignored) {
      // 编辑器可能已关闭
  }
  ```
- **正常路径影响**：`dispose()` 只在关闭编辑器时执行一次，不在热路径；try 保留原语义（销毁期仍不抛穿）。
- **验证**：`VReadAction` 新增 R-12（调 `dispose()`，断言零违规）。
- **变异测试**：把 `readEditor` 拆掉 → R-12 必须红。
- **明确不做**：catch 里**不加** `CursorDiag.swallowed`。理由：任务书对 U-M-19 指定改法只有「注销进 readEditor」，
  加日志属额外改动；若要加，留到第 2 轮单独裁决。

### 3) U-M-4 · `refreshFrameGap` 恒 0，限流只打得出一行

- **根因**：`:2234` 声明、`:2202` 清零、`:2220` 读、`:2221` 清零——**全仓没有一处 `+1`**，
  所以 `>= FAILURE_LOG_EVERY` 恒假；确定性异常时只有第一帧那行 warn。
- **改法**（四-J 判据 2 + 4：统一判据、删死字段）：
  ```java
  // 失败日志节流判据：首次必打，之后每 FAILURE_LOG_EVERY 次打一次。
  // 独立成方法是为了让探针能直接断言行为（原来 VReviewFixes 只查源码里有没有这个 token）。
  static boolean shouldLogFailure(long failureCount) {
      return failureCount == 1 || failureCount % FAILURE_LOG_EVERY == 1;
  }
  ```
  catch 改为 `if (shouldLogFailure(refreshFailureCount)) { LOG.warn(...); }`；
  **删除** `refreshFrameGap` 字段与 `:2202` 的清零语句（死代码必须删干净）。
  注意：`failureCount % N == 1` 而非 `== 0`，因为首次单独命中 `== 1`，
  取模也要在 1 处（1001、2001…）才与首次同节奏。
- **正常路径影响**：成功路径只剩删掉一行 `refreshFrameGap = 0;`（该字段恒 0，删除不改变任何可观测行为）。
- **验证**：`VReviewFixes` A-1 从「源码文本断言」改为「反射调 `shouldLogFailure` 的行为断言」：
  `1→true, 2→false, 1000→false, 1001→true, 2000→false, 2001→true`；
  同时**保留**结构断言（`logSrc.contains("refreshTarget 抛异常")`），确保日志文本还在——
  新断言语义 ⊇ 旧断言。
- **变异测试**：把 `shouldLogFailure` 改回「恒返回 `failureCount == 1`」→ A-1 必须红。

### 4) U-M-9 · caret 事件白付两次平台读取，且 `readEditor` 外无 try

- **根因**：`caretAdded`（`:1097-1100`）与 `caretRemoved`（`:1116-1119`）各调两次
  `getAllCarets().size()`，两次返回值必然相同（都在事件之后读）；且 `readEditor` 外无 try，
  销毁瞬间抛出会穿出平台 `CaretListener` 多播器。
- **改法**（任务书：一次取值、两处用；外面包 try 接 `CursorDiag.swallowed`）：
  ```java
  @Override
  public void caretAdded(@NotNull CaretEvent event) {
      forceResync = true;
      wakeUp();
      // 【平台 252 + U-M-9】一次取值两处用：原实现两次 getAllCarets() 读数必然相同
      //（都在事件之后），白付一次整表复制 + 排序。try 是必需的 ——
      // 这个回调跑在平台 CaretListener 多播器里，抛出会中断后续监听器与该次编辑。
      try {
          int caretCount = readEditor(() -> editor.getCaretModel().getAllCarets().size());
          CursorDiag.caretCountChanged(caretCount - 1, caretCount, "added");
      } catch (Throwable t) {
          CursorDiag.swallowed("caretAdded", t);
      }
      traceCaretEvent("added");
  }
  ```
  `caretRemoved` 同构，`(caretCount + 1, caretCount)`。
- **正常路径影响**：读数由 2 次降为 1 次（同一份值），日志输出完全相同；
  异常时不再穿出多播器（新行为，仅异常路径）。
- **验证**：`VReadAction` R-9 保持（零违规）；新增 R-13：`caretAdded` 期间 `getAllCarets`
  恰好 1 次（锁住新契约，防回归到两次）。
- **变异测试**：把「一次取值」改回两次调用 → R-13 必须红。

### 5) U-H-3 · 连续失败 3 次置 `thread=null` 后没人重建

- **根因**：`loop()` 的 `>=3` 分支置 null 并 `return`；重建入口只有 `register()`
  （唯一调用点是 `CaretAnimator.startScheduler()`，构造期）。`wakeUp()`（`:117-123`）只 `interrupt` 一个 null。
  **全仓 `RenderScheduler.` 调用点实测 6 处**（`CaretAnimator:796/935/1055/1069/1277/2855`），
  其中 `register` 只有 935（构造）。
- **⚠️ 任务书指定修法（「仅重置 `nextDueNanos`」）经代码判定无效**：
  `nextDueNanos` 早已过期，且**没有任何线程再遍历 `ANIMATORS`**（唯一遍历者是 `loop()`），
  所以「只重置到期时间」不会产生一个 tick——它需要「有一个遍历者」这个前提，而该前提正是故障本身。
  因此本计划采用**两段式**（也即审计 §8.3 U-H-3「建议方向」的第二种，任务书第 6 条列的第二个选项）：
  1. **退出前**把每个现存 animator 的 `nextDueNanos` 置为「现在」（重建后可立即投递，不白等一个周期）；
  2. **`wakeUp()` 里检测 `thread == null` 就地重建**，重建与 `register`/`loop` 共用
     `synchronized (RenderScheduler.class)` 同一把锁，防双线程。
  ```java
  static void wakeUp(CaretAnimator animator) {
      animator.nextDueNanos = System.nanoTime();
      Thread t = thread;
      if (t == null) {
          // 调度线程因连续失败已退出（U-H-3）：就地在同一把类锁内重建，防止 register 与
          // wakeUp 同时新建两条线程。只在集合非空时重建，避免留下一条空转线程。
          synchronized (RenderScheduler.class) {
              if (thread == null && !ANIMATORS.isEmpty()) {
                  Thread nt = new Thread(RenderScheduler::loop, THREAD_NAME);
                  nt.setDaemon(true);
                  installUncaughtHandler(nt);
                  thread = nt;
                  nt.start();
              }
              t = thread;
          }
      }
      if (t != null) {
          t.interrupt();
      }
  }
  ```
- **死锁分析**（任务书第 6 条要求）：`wakeUp` 在锁内只做 `ANIMATORS` 判空、`new Thread`、`start()`；
  `start()` 不阻塞、不获取其他锁；`register`/`loop` 拿的是同一把锁，且锁内不嵌套其他监视器。
  **无环、无嵌套 → 无死锁**。`interrupt()` 留在锁外。
- **正常路径影响**：`thread != null` 时走原来的路径（无锁、无重建、只 interrupt）——
  正常运行时**一个字节的执行路径都不变**；只有「线程已死」这个异常态才进新分支。
- **验证**：本批**不新增**专门的 RenderScheduler 探针（要造「让 loop 连续抛 3 次」的桩，
  且 `RenderScheduler` 是包私有类，探针需反射——工作量大且易碎）。
  改为：`VReviewFixes` 保留 R-2b 并**加强**为
  「`wakeUp` 内出现 `thread == null` 重建分支且判 `ANIMATORS.isEmpty()`」的结构断言；
  行为验证留到与批 B 的 `EdtWatchdog` 一起做（**标「需实机/需桩」**）。
- **变异测试**：把 `wakeUp` 的重建分支删掉 → 加强后的 A-3 必须红。

### 6) U-H-4 · catch 体自己无人守护

- **根因**：`catch (Throwable t)` 里的 `LOG.warn`（含字符串拼接）若抛出，
  异常穿出 `loop()` 的 run 边界 → 线程死亡，而 `thread` **仍非 null**（`>=3` 分支到不了）→
  此后每次 `register` 都 `interrupt` 死线程，全体永久停摆。
- **改法**（与 U-H-3 合并成一次改动）：
  1. catch 体内**日志调用与「≥3 判定」分离**：日志包 try，判定在 try 外，保证 `LOG.warn` 抛了也走得到退出/重建；
  2. 给调度线程装 `UncaughtExceptionHandler`：若 `thread == 本线程` 则置 null（让 `wakeUp`/`register` 能重建），
     handler 内的日志**再包一层 try**。
  ```java
  } catch (Throwable t) {
      consecutiveFailures++;
      try {
          LOG.warn("neovide-cursor: 调度线程本轮异常（连续 " + consecutiveFailures + " 次）", t);
      } catch (Throwable ignored) {
          // 日志子系统本身不可用（shutdown / 工厂被摘）：不能让「处理异常」的代码杀死调度线程。
      }
      if (consecutiveFailures >= 3) {
          // 见 U-H-3：置 null 后由 wakeUp()/register() 就地重建；退出前把到期时间置为现在。
          for (CaretAnimator a : ANIMATORS) {
              a.nextDueNanos = System.nanoTime();
          }
          synchronized (RenderScheduler.class) {
              if (thread == Thread.currentThread()) {
                  thread = null;
              }
          }
          try {
              LOG.warn("neovide-cursor: 调度线程连续失败 " + consecutiveFailures
                      + " 次，已置空；下一次 wakeUp/register 将重建");
          } catch (Throwable ignored) {
          }
          return;
      }
      try {
          Thread.sleep(BUSY_RETRY_MS);
      } catch (InterruptedException ignored) {
      }
  }
  ```
  注：原 warn 文本「已置空以便下次 register 重建」是 U-H-3 指出的误导措辞（暗示自己会好），
  本批改为「下一次 wakeUp/register 将重建」——这是**修正文档与行为不符**（四-J 判据 3），
  不是「顺带优化」。
- **正常路径影响**：正常无异常时 catch 体一次都不执行。
- **验证**：`VReviewFixes` 新增结构断言「catch 体内 LOG.warn 被 try 包裹」+
  「存在 setUncaughtExceptionHandler」。
- **变异测试**：把 UEH 删掉 → 新断言必须红。

### 7) U-M-5 · `dispatchDue` 的回滚把「本轮已成功投递」抹掉

- **根因**：`:288` 在 catch 里无条件 `posted = false`，而 `posted` 的契约是「本轮**至少**投了一个」。
  A 成功、B 抛 → 结论被写成「没投」→ `loop()` 多睡 1ms，且 B 每秒重试约 1000 次 → warn 洪水。
- **改法**：catch 里**不动 `posted`**；warn 按 animator 限流（复用 `shouldLogFailure`）：
  ```java
  } catch (Throwable t) {
      animator.framePending.set(false);
      animator.lastDispatchedDueNanos = Long.MIN_VALUE;
      // 【U-M-5】不回滚 posted：它的语义是「本轮至少投递了一个」，
      // 某个 animator 失败不等于本轮什么都没投出去（回滚会让 loop 多睡 1ms，
      // 并让失败的 animator 以约 1000 次/秒重试 → warn 洪水）。
      animator.invokeLaterFailureCount++;
      if (CaretAnimator.shouldLogFailure(animator.invokeLaterFailureCount)) {
          try {
              LOG.warn("neovide-cursor: invokeLater failed, rolled back dispatch flags "
                      + "(animator=" + System.identityHashCode(animator) + ")", t);
          } catch (Throwable ignored) {
              // 日志失败不能连累调度循环（U-H-4 同一原则）
          }
      }
  }
  ```
  `CaretAnimator` 侧新增包内字段 `long invokeLaterFailureCount`（单调递增，不清零——
  限流语义只需单调计数，清零会多一处热路径写）。
- **正常路径影响**：`invokeLaterFailureCount` 只在异常路径递增；正常路径的执行语句与原来一致
  （成功分支仍是 `posted = true`）。
- **验证**：`VReviewFixes` 结构断言「`dispatchDue` 的 catch 里不再出现 `posted = false`」+「有 shouldLogFailure 节流」。
- **变异测试**：把 `posted = false` 加回去 → A-5 必须红。

### 8) U-M-25 · `tick()` 抛出无计数无限流

- **根因**：`runTick()`（`:944-955`）只有 `finally`，没有 `catch`；tick 链上任何一步持续抛
  → 每帧一条异常冲进 IDE 错误记录，插件自己一行不记（与 `refreshTarget` 的待遇不对等）。
- **改法**（与 U-M-4 共用同一套限流写法）：
  ```java
  void runTick() {
      framePending.set(false);
      if (disposed) {
          return;
      }
      try {
          tick();
      } catch (Throwable t) {
          // 【U-M-25】tick 抛出既无计数也无限流：确定性异常时 125~144 次/秒堆栈外抛，
          // 插件自己零线索。与 refreshTarget 用同一套节流判据。
          tickFailureCount++;
          if (shouldLogFailure(tickFailureCount)) {
              try {
                  LOG.warn("neovide-cursor: tick 抛异常（累计 " + tickFailureCount
                          + " 次）；若是确定性异常拖尾会永久冻结", t);
              } catch (Throwable ignored) {
              }
          }
      } finally {
          scheduleNextDue();
      }
  }
  ```
  注：`framePending.set(false)` / `disposed` 早退保持在 try 外（与原结构一致）。
- **正常路径影响**：正常无异常时 catch 一次不执行；多一条字段（`long tickFailureCount`）。
- **验证**：`VReviewFixes` 结构断言「`runTick` 内有 catch + `shouldLogFailure(tickFailureCount)`」。
- **变异测试**：删掉 runTick 的 catch → A-2 必须红。

---

## 四、探针契约变更（先改探针 → 必须红 → 再改生产代码）

### 4.1 `tools/settings-probe/VReadAction.java`（新增 4 条，现有 12 条一字不改）

现状盲区：给了 `getAllCarets`（`:321-323`）和 `removeCaretListener`（`:327-329`）挂 guard，
但驱动面只有 `refreshTarget` 系——**从不调 `runTick` / `tick` / `dispose`**（`VReadAction` 全文实测）。

新增：

| 编号 | 断言 | 怎么驱动 |
|---|---|---|
| **R-11** | 帧级诊断采样帧（`diagFrame == true`）的 tick 链路**零违规** | ① `diagLogging = true`；② 反射把 `frameSeq` 置 14；③ `vp` 换一个新行（让 `refreshTarget` 走慢路径并把 `frameSeq` 推到 15）；④ 把 `nextDueNanos` 推到未来防止调度线程插队；⑤ 反射调 `runTick()`；⑥ 断言 `violations` 不增，且 `readActionCalls` 增加 |
| **R-11b** | 上述帧**确实到达了 `frameSummary` 的读取点**（不是空转） | 桩里给 `getAllCarets` 加计数器 `getAllCaretsCalls`；断言该窗口内 `> 0` |
| **R-12** | `dispose()` 摘监听器**零违规** | 反射调 `dispose()`；断言 `violations` 不增 |
| **R-13** | `caretAdded` 期间 `getAllCarets` 恰好 1 次（U-M-9 新契约） | 复用 R-9 的驱动，单独归零计数器后调一次 `caretAdded` |

配套改动（**加强，非放宽**）：
- 桩的 `content` 从匿名 `JComponent` 改为 `isShowing()` 返回 `true` 的子类。
  理由：`tick()` 在 `:1256` 有 `if (!content.isShowing()) { … return; }`，不 override 就永远走不到
  `tickSpring` 的 `frameSummary`，R-11 会变成**空转的假绿**（正是四-G 假通过 A）。
  该 override 只影响 tick 路径；现有 R-1..R-10 都不读 `isShowing()`，语义不变。
- R-11 执行前把 `animator.nextDueNanos` 设为 `Long.MAX_VALUE / 2`，避免构造期启动的调度线程
  并发投递 `runTick` 干扰计数。

### 4.2 `tools/settings-probe/VReviewFixes.java`（A-1 弱→强；另加 4 条结构断言）

**A-1 改动**（任务书 4.2 要求的「文本断言 → 行为断言」）：
```java
// 契约变更（弱→强），非缺陷：
//   旧断言守的是「源码里存在 refreshFailureCount 这个 token」——字段恒为 0 也 PASS；
//   新断言守的是「节流行为正确」，直接调用生产判据 shouldLogFailure。
Method jud = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator")
        .getDeclaredMethod("shouldLogFailure", long.class);
jud.setAccessible(true);
check("A-1 失败日志节流行为：首次必打，之后每 1000 次一次",
        (Boolean) jud.invoke(null, 1L) && !(Boolean) jud.invoke(null, 2L)
                && !(Boolean) jud.invoke(null, 1000L) && (Boolean) jud.invoke(null, 1001L)
                && !(Boolean) jud.invoke(null, 2000L) && (Boolean) jud.invoke(null, 2001L),
        "1/1001/2001 应打，2/1000/2000 不应打");
// 旧断言的语义（catch 里确实有日志）继续由下面这条守住 —— 新断言语义 ⊇ 旧断言
check("A-1b refreshTarget 的 catch 仍有日志文本（旧断言语义保留）",
        ca.contains("refreshTarget 抛异常"), "…");
```
**结构断言（新增，只查结构，因为离线探针无法制造日志子系统故障）**：
- `A-2` `runTick` 内有 `catch` 且用 `shouldLogFailure(tickFailureCount)`（U-M-25）
- `A-3` `wakeUp` 内有 `thread == null` 重建分支且判 `ANIMATORS.isEmpty()`（U-H-3）
- `A-4` `RenderScheduler` 内有 `setUncaughtExceptionHandler`，且 catch 体的 `LOG.warn` 被 try 包裹（U-H-4）
- `A-5` `dispatchDue` 的 catch 里**不再出现** `posted = false`（U-M-5）

> 探针文件的**数量不变**（只改两个已有文件）→ 不触发 `VDocCheck` D-2，也不需要改
> `CLAUDE.md` / `START-HERE.md` / `docs/NEXT-STEPS.md` 的探针数量。
> 条目数仍 100，PASS 数会从 1035 增加（新增断言数 − 1 条被替换的旧断言）。

### 4.3 RED 证据（必须先跑出红）

改完探针、**尚未改生产代码**时跑全量门禁，预期：

```
VReadAction   R-11  FAIL（diagFrame 帧的 getAllCarets 未在 read action 内）
VReadAction   R-12  FAIL（dispose 的 removeCaretListener 未在 read action 内）
VReadAction   R-13  FAIL（caretAdded 读了两次 getAllCarets）
VReviewFixes  A-1   FAIL（shouldLogFailure 方法不存在 → 反射抛 NoSuchMethodException）
VReviewFixes  A-2..A-5 FAIL（结构不存在）
```

这份红名单会原文贴进报告——**它就是 U-H-2 / U-M-19 / U-M-9 / U-M-4 / U-M-25 的 RED 证据**，
不许因为「红了」回退探针。

---

## 五、白名单自检

| 文件 | 本批是否改 | 说明 |
|---|---|---|
| `ui/CaretAnimator.java` | ✅ | U-H-2 / U-M-19 / U-M-4 / U-M-9 / U-M-25（+ U-M-5 需要的包内字段 `invokeLaterFailureCount`） |
| `ui/RenderScheduler.java` | ✅ | U-H-3 / U-H-4 / U-M-5 |
| `tools/settings-probe/VReadAction.java` | ✅ | 新增 R-11/R-11b/R-12/R-13 |
| `tools/settings-probe/VReviewFixes.java` | ✅ | A-1 改行为断言 + A-1b + A-2..A-5 |
| `build-v-probes.ps1` | ❌ | 未新建探针文件 |
| `CLAUDE.md` / `START-HERE.md` / `docs/NEXT-STEPS.md` | ❌ | 探针数量未变 |
| `core/NeovideConfig.java` | ❌ | D0 红线 |
| 其余 `ui/*`、`docs/BUG-LOG.md`、`docs/INDEX.md` | ❌ | 不碰 |

---

## 六、执行顺序

1. **改探针**（VReadAction 4 条、VReviewFixes A-1 重写 + 4 条）→ 跑门禁 → **贴红名单**（AC-001）
2. **改生产代码**（先 CaretAnimator 5 条，再 RenderScheduler 3 条）→ 跑门禁 → 全绿
3. **变异测试** 8 组，每组「改坏 → 红 → 恢复 → **删 classes 全量重编译** → 绿」（AC-002）
4. **代码审查**：`code-reviewer` + `java-reviewer` 并行复查本批 diff（AC-003/004 佐证）
5. **全量门禁 + D0**（AC-005）、`git status --porcelain` 逐项对照（AC-006）
6. **按八项回报**

---

## 七、未验证 / 需实机（计划阶段即声明）

| 项 | 原因 |
|---|---|
| U-H-3 的「重建后真的恢复动画」 | 需要真平台 + 制造连续 3 次异常；离线探针只能验结构。**标「需实机」** |
| U-H-4 的「LOG.warn 抛出不杀线程」 | 触发概率本就极低，且离线无法让 Logger 抛。**标「需实机/结构性论证」** |
| U-M-5 的「warn 洪水消失」 | 同上，异常路径。**标「需实机」** |
| U-H-2 的「252 上诊断开启不再抖」 | 需装 jar 启 IDEA（任务书 §6 第 5 条：不做） |

---

## 八、需要用户裁决的一点（任务书第六节第 4 条的延伸）

任务书对 U-H-3 指定「选『重置 `nextDueNanos`』」，但代码判定该写法**无法达成目标**：
置 null 后没有任何线程再遍历 `ANIMATORS`，重置到期时间不会产生 tick。
本计划改为**两段式**（退出前置过期 + `wakeUp` 锁内重建），并已给出死锁分析。
**这是本轮唯一偏离任务书字面指示的地方，请在开工前确认。**
