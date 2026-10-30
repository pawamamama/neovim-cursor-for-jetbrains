# 交接 · 审计第 2 批第 1 轮（已提交并推送）

> 给接手的会话：读完这一份就能接着干，不需要回翻对话。
> 生成时间：2026-10-30 · 提交 `0410976` · 已推送 `fork/main`

---

## 一句话

第 2 批（ui/ 动画引擎）里「调度线程生命周期 + read action 覆盖面」的 **8 条** 已全部修完、
验证、提交、推送；审查过程中又发现并修掉 **3 条**（1 HIGH + 2 MEDIUM）。
门禁 **100 条目 / 1048 PASS / 2 FAIL（仅 VRegr2，informational）/ ALL PASS**，D0 零 diff。

---

## 一、环境事实

```
仓库      D:\deepseekwork\neovim-cursor-pr
分支      main
HEAD      0410976（已推送 fork/main，两边同步）
上一个    6f7d585（第 1 批）
门禁      pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1
          → 100 条目 / 1048 PASS / 2 FAIL / ALL PASS
D0        git diff --exit-code -- .../core/NeovideConfig.java → 0
探针文件  103 个 V*.java（本批未增减文件，只加强内容）
```

**不要并发跑两份门禁**（共用 `build\v2-probe\classes`，会 ClassNotFoundException 假红）。

---

## 二、本批改了什么（8 条原文编号）

| 编号 | 问题 | 修法落点 |
|---|---|---|
| **U-H-2** | `tickSpring` 的 `getAllCarets()` 未包 read action，252 平台在诊断采样帧抛 → 丢帧 + 每秒约 8 条堆栈 | `CaretAnimator`，包进 `readEditor` |
| **U-M-19** | `dispose()` 注销监听器未包 read action，抛出被空 catch 吞 → 监听器泄漏 | `CaretAnimator`，与注册侧同口径 |
| **U-M-4** | `refreshFrameGap` 全仓无 `+1`、恒 0，限流恒假 | 抽 `shouldLogFailure(long)`，删死字段 |
| **U-M-9** | caret 事件白付两次 `getAllCarets()`，且 `readEditor` 外无 try | 一次取值 + try + 诊断兜底 |
| **U-H-3** | 连续失败 3 次置 `thread=null` 后无人重建，全体动画停摆 | 退出前置过期 + `wakeUp` 锁内重建 |
| **U-H-4** | `loop` 的 catch 体内 `LOG.warn` 抛出即线程死亡 | catch 体自保护 + UEH（两条创建路径都装） |
| **U-M-5** | `dispatchDue` 回滚把「本轮已成功投递」抹掉 | 不再回滚；warn 按 animator 限流 |
| **U-M-25** | `runTick` 只有 finally 没有 catch | 加 catch + 计数限流（与 U-M-4 同一判据） |

### 审查追加（不在原任务书 8 条内，但不修就是新缺陷）

| 编号 | 问题 | 后果 |
|---|---|---|
| **H-1** | `consecutiveFailures` 是 `static`，重建的线程继承上一代的 3 | 「自愈」后的线程一次偶发异常就再死，日志还会报"连续 4 次"误导排查 → 改为 `loop()` 局部变量 |
| **MEDIUM-1** | `posted` 若写成「尝试过就 true」，`invokeLater` 持续抛时跳过唯一 1ms 退避 | 100% CPU 紧循环，且异常在 catch 内被吃掉连自愈都到不了 → 改为「成功才 true」 |
| **MEDIUM-2** | `refreshFailureCount` 只在慢路径末尾归零，三条成功早退不归零 | 偶发异常第二次起被限流静默 → 改 `finally { if (success) ... }` |

**H-1 与 MEDIUM-1 都是本批自己引入/暴露的**，不是历史遗留。

---

## 三、探针契约变更

| 探针 | 变化 |
|---|---|
| `VReadAction` | 12 → **16** 条。新增 R-11（诊断采样帧 tick 链零违规）、R-11b（**差分**断言：采样帧比不采样帧多读一次 `getAllCarets`）、R-12（`dispose` 零违规）、R-13（`caretAdded` 只读一次）。原有 12 条一字未改 |
| `VReviewFixes` | 7 → **16** 条。A-1 由「源码 token 存在」改为**反射调用生产判据 `shouldLogFailure`** 的行为断言；新增 A-1b/A-1c/A-2..A-8 |

**为什么 R-11b 必须是差分**：`refreshTarget` 的慢路径自己就会调 `getAllCarets`，
只断言「> 0」在诊断块一次没执行时也成立 —— 那是四-G 的「假通过 A」。
差分才能证明诊断块真跑了。

**探针可靠性处理**：`detachFromScheduler()` 之后 `join` 调度线程 + `invokeAndWait` 排空 EDT，
并钉死 `-Dneovide.cursor.diag.sample=15`；否则 R-13/R-11b 会因并发或外部 `-D` 假红。

---

## 四、验证（可复跑的证据）

- **RED**：改完探针、未动生产代码时，VReadAction FAIL 3（R-11/R-12/R-13）、VReviewFixes FAIL 5（A-1..A-5）。
- **GREEN**：生产改完后两者各 16 条全绿。
- **变异测试 11 组**（8 条 + H-1 + MEDIUM-1 + MEDIUM-2），每组「改坏 → 红 → 恢复 → **删 classes 全量重编译** → 绿」全部通过。
- **观感零变化**：只动异常路径、诊断接线、失败计数；正常路径唯一变化是 caret 事件少一次平台读取（变快）。

**变异测试的坑（我自己踩过）**：`build/v2-probe/mutate.ps1` 只编译 `src/`，**不编译探针**。
新增探针断言后必须先跑一次全量门禁（它才编探针进 `probe/`），否则变异组会「假绿」。

---

## 五、未验证（需实机）

| 项 | 原因 |
|---|---|
| U-H-3「重建后真的恢复动画」 | 离线只能验结构。方法：制造连续 3 轮异常 → 敲一次键 → 看拖尾是否恢复 |
| U-H-4「LOG.warn 抛出不杀线程」 | 日志工厂被摘无法离线复现 |
| U-M-5「warn 洪水消失 / 退避仍在」 | 持续抛的环境造不出来 |
| U-H-2「252 上诊断开启不再抖」 | 需装 jar 启 IDEA（任务书禁止） |
| `dispose` 在写动作内获取读锁 | 审查代理标注「无平台实证」 |

---

## 六、还剩什么

第 2 批共 **8 条 HIGH**，本批只做了 U-H-2/U-H-3/U-H-4 三条；**以下仍未修**：

| 编号 | 一句话 | 备注 |
|---|---|---|
| **U-H-1** | 快速路径对「滚动」完全失明 → 光标画在错误的行上（幻影光标） | **本批唯一正常使用必触发的功能缺陷**，优先级最高；需实机确认观感后再改 |
| **U-H-5** | `install()` 的空 catch 吞掉「编辑器挂载失败」→ 该编辑器光标彻底不可见 | 4 视角同时发现 |
| **U-H-6** | `applyNativeCaretHidden` 先记账后写色 → 抛出即污染账本，双光标不可救 | 与 U-M-17/U-M-18 同链，可一批修 |
| **U-H-7** | `CursorDiag` 自己写文件失败是静默的，而 `[tick]/[paint]` 的唯一出口就是它 | 证据基础设施撒谎 |
| **U-H-8** | `paintTrace` 的「光标消失」检测器终身只响一次 | 同上，可一批修 |

MEDIUM 共 28 条（U-M-1..U-M-28），本批只碰了 U-M-4/5/9/19/25。

---

## 七、建议的下一步（按优先级）

1. **U-H-1 滚动失明**——先实机确认（长文件点行 → 滚 3 行 → 看拖尾是否停在旧像素位置），再改。
   注意：它与 U-M-3（静止仍每帧重绘）的修法相互作用，别分开做。
2. **U-H-5/U-H-6 + U-M-17/U-M-18**（原生光标隐藏链 + 挂载失败），同一条链，一批修。
3. **U-H-7/U-H-8 + U-M-27**（诊断基础设施），一批修。
4. 第 3 批：`ui/settings/` 全部（`NeovideCursorConfigurable` 3110 行）。

---

## 八、本轮的临时产物（可清）

`build/v2-probe/` 下（git 忽略，不入库）：
`mutate.ps1`（单探针运行器）、`mutants.sh`（变异脚本）、`mut-*.log`（证据）、`mut-backup/`（源码备份）。
建议**保留日志**作为证据，其余可删。**未删除，等用户决定。**

---

## 九、改过的文件清单（提交 0410976）

```
src/main/java/.../ui/CaretAnimator.java      +182
src/main/java/.../ui/RenderScheduler.java    +163
tools/settings-probe/VReadAction.java        +167
tools/settings-probe/VReviewFixes.java       +121
plans/audit-fix-batch2-round1.md             +500（计划与取舍记录）
```

**未提交**：`docs/BUG-LOG.md`、`docs/INDEX.md`（另一会话的 BUG-014/015）、
`build/night-run-report.md`、`docs/CODE-AUDIT-2026-10-30.md`、`docs/HANDOFF-DECISION-2026-10-30.md`。
