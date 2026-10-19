# REVIEW-BATCH1.md —— 「零观感优化·批次1」代码审查报告

> 生成：2026-10-18（t5 review round 1）
> 审查者：captain
> 契约：`OPTIM-BATCH1.md`（FROZEN）　基线：`git HEAD = 321b0a0`
> 证据：`VERIFY-BATCH1.md`

## ⚠️ 独立性缺陷声明（必须先读）

原 `reviewer` 成员所属的 **raccoon provider 发生账号级限流**（`QUOTA_EXCEEDED`，解禁 2026/10/19 21:17），三个成员路由全部不可用。captain 先后派出的两个独立子代理**均未落盘产出**（`subagent` 亦受同一 provider 传输错误影响）。

因此**本次审查由 captain 执行**，构成两处独立性削弱，必须如实记录：

| 削弱点 | 说明 |
|---|---|
| captain 修复了 t7 | 审查"自己写的修复"存在确认偏误风险 |
| captain 撰写了 VERIFY-BATCH1.md | 审查证据与生产证据同源 |

**缓解措施**：所有结论均基于**可复现脚本的原始输出**（`run-parity.ps1` / `run-audit.ps1` / `run-probe.ps1`），而非主观判断；任何读者可用 §7 的命令独立复跑。此外 captain **未参与 t2/t3 实现**（由 impl-core 完成），故 Tier 0 与 Tier 1 的主体审查独立性成立。

**结论定性**：本审查**可判定"代码是否违反契约"**（这部分是客观的、可复现的）；但**不能替代独立的人类/第三方复核**。建议在额度恢复后由 reviewer 重跑一遍。

---

## 0. 绑定字节

| 对象 | SHA256 |
|---|---|
| `OPTIM-BATCH1.md` | `13B028034F47B69DD145107AE6113A747CD45766971912BF3748619588110EF7` |
| `ui/RenderScheduler.java` | `A89BE4256BF7533D9254575168FA9EB08766DCE0EA9CFF682474B07642099292` |
| `ui/CaretAnimator.java` | `847CF6D8CAFCCB9F7265D086DC9D472427519F62152878DAA799DB2E0D00A8B2` |
| `dist/_verify-repair.jar` | `C718675E046CAB820E7EC6F5AEEE1B647C06DDF829BCF5837B185AD2BE29CB62`（42,079 B） |

被审查改动仅 3 个文件（`git diff --name-only -- src`）：

```
src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java   (-28)
src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java     (286±)
src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretTrailPanel.java   (28±)
```
（另 `ui/RenderScheduler.java` 为新增未跟踪文件）

---

## 1. 逐条核对表

| # | 审查项 | 结论 | 证据 |
|---|---|---|---|
| 1 | `core/TrailCorner.java` 未改动 | ✅ | `git diff --name-only` 不含该文件 |
| 2 | `core/DampedSpring.java` 未改动 | ✅ | 同上 |
| 3 | `computeOffsetDirections` 的 `nx/ny` 仍是函数内局部数组（P0-6） | ✅ | `CaretTrailPanel.java:300-301` `double[] nx = new double[4]; double[] ny = new double[4];`；字段声明里 `private final double[] (nx\|ny)` 命中数 = **0** |
| 4 | `dirX/dirY` 提为字段是否安全 | ✅ | 第二个循环对 `j=0..3` 无条件完整覆写（`nx[j]=vx` / `ny[j]=vy` 必写），无残留路径 |
| 5 | `glowPath.reset()` 保留 | ✅ | 循环内 `glowPath.reset()` 每层覆盖前调用 |
| 6 | 几何表达式一字未改 | ✅ | diff 中仅 `path` → `glowPath` 重命名，坐标算式未变；且 `GeometryParity` 逐字节一致佐证 |
| 7 | `tick()/tickSpring()` 只删了 `effectiveDt` 空转链路 | ✅ | diff 仅删 `smoothedDt +=` / `effectiveDt` 及其传递，`tickSpring(dt, effectiveDt)` → `tickSpring(dt)`，`assignRanks(dt)` → `assignRanks()` |
| 8 | `invokeLater` 全仓唯一调用点、在 per-animator 循环内（P0-1） | ✅ | `RenderScheduler.java:182`，位于 `L160 for (CaretAnimator animator : ANIMATORS)` 体内；`invokeLater(RenderScheduler::` = 0；`runDue\|FRAME_PENDING` = 0 |
| 9 | 每 animator 保留自己的 `framePending`（禁止全局） | ✅ | `CaretAnimator` 中 `final AtomicBoolean framePending`（实例字段，非 static）；`RenderScheduler` L175 用 `animator.framePending.compareAndSet` |
| 10 | `wakeUp` 先设 `nextDueNanos` 再 `interrupt` | ✅ | `RenderScheduler.wakeUp()` L100-104：`animator.nextDueNanos = System.nanoTime();` 在 `t.interrupt();` 之前 |
| 11 | T1-1 常量只在 W3-1/W3-2/W3-3 范围内 | ✅ | `MAX_SLEEP_MS=600`（W3-1 ≤600）、`BUSY_RETRY_MS=1`（W3-2）、`DUE_TOLERANCE_NANOS=1_000_000L`（W3-3 ≤1ms）；均未参与动画/物理/几何计算 |
| 12 | T0-1 fps 日志 isDebugEnabled 门控 | ✅ | `recordFrameStats` 中 `if (LOG.isDebugEnabled()) { ... LOG.debug(...) }`，`String.format` 在门控内 |
| 13 | T0-1 窗口 ≥5s | ✅ | `STATS_WINDOW_NANOS = 5_000_000_000L` |
| 14 | T0-1 编辑器标识在**行尾**（P0-8） | ✅ | 格式串尾部追加 `editor=%s#%d`，前六个字段顺序未动 |
| 15 | `build/analyze-log.ps1` 与 `diag/extract.py` 未被改 | ✅ | `git diff --name-only` 均不含 |
| 16 | T0-4 7 字段只删声明 | ✅ | diff 显示仅删字段声明与注释；新版 grep 命中 0、基线 7（证明 grep 有效） |
| 17 | T0-5 埋点被 isDebugEnabled 短路、不在热路径拼串 | ✅ | `if (LOG.isDebugEnabled() && costNanos > REFRESH_TARGET_SLOW_NANOS)`；起点在快速路径提前返回**之后** |
| 18 | `lastDispatchedDueNanos` 为 `volatile` | ✅ | `volatile long lastDispatchedDueNanos = Long.MIN_VALUE;`（调度线程写、EDT 读，跨线程可见性必要） |
| 19 | `scheduleNextDue()` 防御 `Long.MIN_VALUE` 溢出 | ✅ | 显式分支：`if (dispatched == Long.MIN_VALUE) next = now + delay;` |
| 20 | 观感常量零变更 | ✅ | `run-audit.ps1` 19 项 DIFF = 0 |
| 21 | 物理/几何数值逐位一致 | ✅ | 三份 parity harness base 与 new 字节完全相同 |

**P0-1 ~ P0-10 逐条**：P0-1 ✅（未批量 tick）、P0-2 ✅（坐标仍 `int`，`Polygon` 保留）、P0-3 ✅（`glowLayers`/`maxTrailDistanceFactor` 未动）、P0-4 ✅（未改 plugin.xml/build/dist/VERSIONS）、P0-5 ✅（`TrailCorner.jump`、`DampedSpring.update`、`computeNextDelay` 均未改）、P0-6 ✅（`nx/ny` 仍局部）、P0-7 ✅（wakeUp 立即投递语义保留）、P0-8 ✅（fps 行尾追加）、P0-9 ✅（`hideNativeCaret=false`/`alwaysHighFps=true` 未动）、P0-10 ✅（未引入设置界面）。

---

## 2. 裁决 (A)：契约 §2.6.2 与 §2.6.7 的矛盾

**矛盾事实**：§2.6.2 要求到期时刻在 tick **结束**才写；§2.6.7 要求「每个到期周期 ≤1 次 `invokeLater`」且括号注明「任务开始即复位 pending」。二者冲突——pending 在任务开始复位后，到期值仍是旧的，1ms 轮询会在长 tick 期间反复投递（实现者实测无护栏时投递数约 2~3 倍）。

**裁决：(A) 接受 `lastDispatchedDueNanos` 字段。**

理由：

1. **它不触碰观感**：该字段只参与"是否投递"的判定（`if (animator.lastDispatchedDueNanos == due) continue;`），与动画时长、物理推进、重绘区域、颜色、几何**零关联**。经 `run-audit.ps1` 验证：19 项观感常量 DIFF=0，parity 逐字节一致。
2. **它不超投**：`Probe C` 场景（到期值 800ms 不变）实测 `C_posts_stale_due_800ms=1`，即该到期周期只投递 1 次。
3. **它不漏投**：`Probe D`（全部过期）`D_posts_all_overdue=3`，每个 animator 各得 1 次；`Probe A`（3 个同时到期）`A_posts_a1/a2/a3` 各 1；`Probe E` 线程建-退-再建-再退全部正确。
4. **它是对 §2.6.7 的实现细化**，不是对其精神的违背——目的是"消除 EDT 繁忙时的恶性堆积"，正是 §2.6.7 的初衷。

**但须记录一条契约修订**：§2.6.7 应补充说明"为防止长 tick 期间的重复投递，允许引入 per-animator 的'已投递到期值'守卫字段"。当前 `OPTIM-BATCH1.md` 已 FROZEN 且被绑定 SHA256，故**本轮不修改契约文件**，在此以审查记录形式留痕。

---

## 3. 裁决 (B)：r2 的 `scheduleNextDue()` 是否越界

**改动内容**：到期时刻推算基准从「tick 结束的 `now` + delay」改为「上次投递的到期值 + delay，并 `Math.max(·, now)` 追赶」。

**裁决：属于「基础设施时序细化」，在允许范围内；但应补入契约白名单。**

理由：

1. **它没有改任何观感常量** —— 改的是"何时登记下一次到期"，不是"动画多长""物理如何推进"。
2. **它让行为更接近基线**，而非偏离：基线是「先 sleep 再投递」，当单帧耗时 > delay 时不会额外多等一个 delay；r2 的修法正是复现该语义。修改前后 parity 逐字节一致，说明物理/几何未受影响。
3. **它是修复 t4 finding 的必要手段**，非主动扩权。
4. **但它确实改变了调度时序的可观测行为**（帧率矩阵），因此**应当补入契约白名单**——建议新增条目：

   > **W4**：`nextDueNanos` 的推算基准（允许从"tick 结束的 now"改为"上次投递的到期值 + delay"并做追赶），前提是「不改变 `currentDelayMs` 的任何取值、不改变 `computeNextDelay()` 逻辑、且 parity 逐字节一致」。

   同样，因契约已 FROZEN，本轮以审查记录留痕，不改文件。

---

## 4. Findings

| id | severity | problem | requiredFix | file |
|---|---|---|---|---|
| F1 | **low** | `NeovideConfig.java` 的 `alwaysHighFps` javadoc 仍以 `{@code smoothedDt}` 举例，而该字段已按 T0-3 删除 | **本批不改**（§4.4 禁止改注释措辞，且不影响行为）；留待下一批"设置界面前置清理"时一并修正 | `core/NeovideConfig.java` |
| F2 | **low** | 交付 jar 沿用基线 jar 自带的 `OffsetTest.class`（基线即有，非本批引入） | **本批不处理**；在 t6 交付说明中如实记录即可，不从 jar 中删改 | `dist/*.jar` |
| F3 | **medium** | **【已更正的错误】原 F3 说「契约 §3 存在 `Select-String -Recurse` 笔误」——这是事实错误。** 实测 `OPTIM-BATCH1.md` 全文 grep `-Include` = **0 命中**、`Select-String.*-Recurse` = **0 命中**；契约 §3 的 `Select-String` 全部作用于单文件（`-Path $rs`、单个 `NeovideConfig.java`），`-Recurse` 只出现在 `Get-ChildItem`/`Remove-Item`（pwsh 7 完全可用）。那句命令的真实出处是 `tools/parity/run-audit.ps1:104` 的**故意失败反例**，captain 误当契约原文转述。<br>**但真正值得记录的是另一个坑**：`Select-String -Path <目录> -Include *.java`（**不带** `-Recurse`）**不报错、恒返回 0 命中** —— 实测同一棵基线树：死字段正则 金标准 **7** vs 该写法 **0**；`public` 金标准 **113** vs **0**。这是**静默假阴性**，会把「7 个死字段已清零」验成「基线本来就 0」，足以让验证结论反转 | 已改为金标准写法 `Get-ChildItem -Recurse -Filter *.java <src> \| Select-String -Pattern '<正则>'`；契约本身无需修改 | `VERIFY-BATCH1.md` §8.1 |
| F4 | **medium** | **真机手感未验证** —— 探针是 headless + 伪造 Editor，`tick=25ms` 为合成值 | 由用户实机安装确认；这是本批唯一无法自动化的验收项 | — |
| F5 | **low** | `lastDispatchedDueNanos` 在 dispose 与投递交错的极短窗口未做压力测试 | 后续可加一次并发压测；当前 `isDisposed()` 前置检查已覆盖主要路径 | `ui/RenderScheduler.java` |
| F6 | **low** | 帧率矩阵 R2/R4 的 −2 帧是**系统性差异而非随机噪声**：独立验证方 4 轮实测 base 的 R2 恒 `59`、R4 恒 `59`（**零方差**），new 稳定 `57`；4 轮 + 交错 5 轮共**九轮无一轮反转**。幅度约 **−3.4%**，恰好压在 ≤3 帧判定线内。R1/R3 则**系统性变快** +12~+14 帧（约 +7~8%）。机制已定位：独立验证方把 `BUSY_RETRY_MS` 临时改 0 后 R2 56→58、R4 57→58（三变体交错 5 轮排除机器漂移） | 判据达标（≤3 帧）故**不阻塞交付**；但报告表述必须写「系统性差异」而非「噪声」。`BUSY_RETRY_MS` 是否改 0 属取舍（W3-2 允许 0 或 1，改 0 会引入热自旋），**留给用户决定**，本批不动 | `VERIFY-BATCH1.md` §6.3/§6.6 |

**F1~F3、F5 均不阻塞交付**；**F4 属于必须由用户确认的项**，已在交付说明中明确。

---

## 5. verdict

**pass**

依据：`objective` 与 `acceptance` 全部满足——

- 21 项逐条核对全 ✅
- P0-1 ~ P0-10 全部未违反
- 物理/几何 parity 逐字节一致
- 19 项观感常量 DIFF = 0
- EDT 任务粒度未变
- 帧率矩阵：EDT 繁忙时 −2~−3 帧（系统性，非噪声，−3.4%），**在 ≤3 帧判定线内**；EDT 空闲时 +9%（系统性变快）
- 两个契约矛盾点均已明确裁决（A 接受 / B 允许但应补契约记录）

**附带条件**（不影响 pass，但必须让用户知晓）：

1. **真机手感未验证**（F4）——需用户安装 0.9.1-zeroopt 后主观确认。
2. **本次审查由 captain 执行，存在 §0 所述的独立性缺陷**——建议额度恢复后由 reviewer 独立复核。
3. **两处契约修订建议**（§2 的 W-补充、§3 的 W4）已记录但未写入冻结文件。
4. **F6（系统性差异）**：EDT 繁忙时的 −3% 是稳定复现的系统性偏移（base 与 new 各自零方差），不是随机噪声。它达标，但如实标注为「略慢于基线」，不宣称"完全等价"。

---

## 5.1 本报告已更正的错误（留痕）

| 项 | 原写法 | 更正 | 触发来源 |
|---|---|---|---|
| F3 | 「契约 §3 存在 `Select-String -Recurse` 笔误」 | **事实错误**：`OPTIM-BATCH1.md` 全文 grep `-Include` = 0 命中、`Select-String.*-Recurse` = 0 命中。该命令实际出自 `tools/parity/run-audit.ps1:104` 的**故意失败反例**，captain 误当契约原文转述 | 独立验证代理指出 + captain 实测复核 |
| 帧率表述 | 「全部进入噪声范围」 | **不准确**：4 轮采样显示 base/new 各自零方差，−2~−3 帧是系统性差异 | 同上 |
| base 基线值 | captain 草稿取 R2=58、new=56 | **采信独立验证方的 R2=59 / new=57**。依据：与 `DispatchCountProbe` 的 `phase5_posts_slow_tick_25ms=59`、以及交错实验中 BASE 的 R2=58~59 吻合。**两版结论一致**（都是 −2 帧、≤3、达标、系统性非噪声），仅中心值差 1 | 独立验证代理 §10 |

### 5.2 独立验证方的额外加固（captain 未要求、但提高可信度）

1. **47 项常量清单**（不止 19 项），按契约 §3.3 正式口径：基线输出哈希 `04D93CAC…` **与冻结值逐字符吻合**；新版唯一 diff 是 `smoothedDt +=` 行按预期消失。
2. **t7 修复的独立 A/B**：在**副本**里还原旧写法，实测 R2 44/45、R4 36/37、R5 47/48、R6 47/48 —— 回退可独立复现，不依赖 captain 数字。
3. **jar ↔ 源码一致性**：用当前 `src/` 重编 16 个 class，与 `_verify-tier1.jar` 内同名 class **16/16 SAME**。
   > captain 另行独立复核：把 `_verify-tier1.jar` 与 `_verify-repair.jar` 各自解包，**19 个文件逐一哈希比对全部相同**，差异仅来自 zip 时间戳 → 交付的 `0.9.1-zeroopt.jar` 内容正确。
4. **P0-8 实证**：按新格式串真实渲染一行 fps 日志，喂给 `build/analyze-log.ps1` 与 `diag/extract.py` 的**原正则**，两者均能正确解析（7 分组齐全）→ 行尾追加字段未破坏证据链。
5. **T0-2 字节码级**：`drawQuadGlow` 的 2 个 `newarray double` 与 1 个 `new Path2D` 确实消失；`reset()` 仍在循环内；`nx/ny` 仍是局部数组（P0-6 满足）。
6. **口径提醒**：`-Pattern 'invokeLater'`（不带括号）会得 **5**，因为类注释里还有 4 处引用；真实调用点数是 **1**。别把 5 当调用点数。

**教训**：引用契约条文必须 grep 原文核对，不能凭记忆或凭下游脚本转述；报告里的"噪声"必须用多轮采样的方差来证明，而不是靠单轮差异小就假定。

---

## 6. 未覆盖 / 存疑

| 项 | 状态 |
|---|---|
| 真机 IDEA 内 EDT 负载与主观手感 | 未验（F4） |
| `tick=25ms` 探针值 | 合成固定耗时，非真实卡顿分布 |
| fps 日志 T0-1 真机实跑 | 仅静态验证 |
| 多光标（Alt 列选） | 本批不支持，上游 `SpringTrail` 未移植 |
| 叠加模式下控制台光标 | 未碰（契约外） |

---

## 7. 复现命令

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1"
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-audit.ps1"
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\probe\run-probe.ps1"
```

回退目标：`dist\jetbrains-neovide-cursor-0.9.0-ahf-default.jar`
SHA256 = `FA161E4F77C8C99992381DD489F7C172A38F56BB62DC3C2D45D8856AD8C95CD5`
