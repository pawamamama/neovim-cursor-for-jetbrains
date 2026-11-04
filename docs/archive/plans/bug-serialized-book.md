# 文档自检 + 新会话 BUG-007 交接指令

## Context

用户想知道：当前这套接手文档（CLAUDE.md / START-HERE / NEXT-STEPS / BUG-LOG / PARAMETER-MAP / INDEX 等）
在经历 0.9.93 → 0.9.98 这一轮大改之后，**还有没有漏的、过期的、互相矛盾的**；
如果没有硬伤，就给出一条可以直接粘给**新会话**的指令，让它去修 BUG-007
（启动 IDEA 后第一次用光标必定卡一下，已记录未定位）。

本文件产出两件事：
1. 文档自检结论（下面「自检结果」一节）
2. 一段可复制的新会话指令（下面「交接指令」一节）

---

## 自检结果

> 由探查代理逐文件核对 + 主会话复核。**只列事实与位置，改法另议。**

（待代理返回后补写）

---

## 交接指令（复制给新会话）

```text
项目：D:\deepseekwork\neovim-cursor-pr（主仓库，直接在这里干，别开 worktree）
当前状态：main @ aaa1f1e，已装 0.9.98，远端 fork/main 已同步；工作区干净。

# 任务：定位并修复 BUG-007

现象：每次启动 IDEA 后，**第一次使用光标**（移动或输入都算）必定卡一下，
"卡到没有"（光标消失一瞬），之后正常，本次会话内不再复现。

关键排除（用户实测）：启动后**干等两分钟再动**，照样复现。
所以不是"冷启动 CPU 忙 / EDT 队列积压"这类解释。

已有记录：docs/BUG-LOG.md 的 BUG-007。里面列的待查方向**全部是推测**，不得当结论。

# 第一步：抓首帧证据，不要先改代码

1. 设置面板 → 高级（诊断）→ 勾选「诊断日志」，应用；
2. 重启 IDEA，启动完成后**立刻**动一下光标（或打一个字），然后静置；
3. 把 %APPDATA%\JetBrains\IntelliJIdea2026.1\neovide-cursor-diag.log 的**前 20 行**抓出来，
   重点看 [frame] 与 [trail] 的首帧：rawDt、dt、carets、frameSeq。

# 已记录的可疑点（推测，必须用日志证实或排除）

1. `tick()` 开头的恢复检测：首帧 `lastNanos = 0` → `rawDt` 极大 → 走「长停顿 → 一次性归位」分支；
2. `CaretAnimator` 构造时 `content` 尺寸还是 0 → `panel.setBounds(..., 1, ...)` →
   首帧 `content.isShowing()` 为 false → `panel.clearCorners()`，于是「第一次使用」才真正建立几何；
3. `RenderScheduler` 首次投递与 `lastDispatchedDueNanos = Long.MIN_VALUE` 的交互。

# 硬约束（违反会让门禁变红 / 破坏已发布行为）

- `core/NeovideConfig.java` 必须零 diff（D0 门禁）；
- 探针基线是 **6 红**：VTools / VHints / VCustom / VReset2 / VSepBound / VSleep ——
  修 bug 前后**名单必须逐字一致**，不许顺手去修它们；
- 不许为了让探针通过而改生产代码；也不许删断言（**改方向可以**，见纪律二.6）；
- 改 `tick()` / `refreshTarget()` 的恢复分支之前**必须先有首帧日志证据** ——
  那是全局都依赖的分支；
- 不要动 0.9.98 已发布的行为：预设快照是 13 个字段、颜色与输入平滑不随动画预设。

# 门禁（仓库根跑，必须带 -Repo）

pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr

（不要并发跑两次门禁，它们共用 build\v2-probe\classes，会出 ClassNotFoundException 假红）

# 完成标准

- 贴出首帧日志的关键行作为证据；
- 根因写清楚（不是「可能是」），并说明与日志的对应关系；
- 修完后：6 红名单逐字不变、D0 PASS、全量门禁跑过；
- 新增守门探针，并做**变异测试**（把修复注释掉，探针必须变红）；
- 在 docs/BUG-LOG.md 的 BUG-007 下补「根因 / 修法 / 证据」，状态改成实际结果。

# 不要做

- 不要推送（`git push fork main` 由用户决定时机；推送走 SSH，https 经代理会 TLS 断连）；
- 不要动 docs/archive/；
- 不要顺手改预设参数或说明文案（有独立的待办在 docs/NEXT-STEPS.md）。
```

---

## 验证方式

1. 自检结论与 `git log` 一致：`git rev-parse --short HEAD` 应为 `aaa1f1e`；
2. 已装版本：读 jar 内 `META-INF/plugin.xml`（文件名恒为 0.9.8.jar，不能凭文件名判断）；
3. 探针数：`ls tools/settings-probe/V*.java | wc -l` 应为 93；
4. 门禁基线：跑 `build-v-probes.ps1 -Repo ...`，失败名单应为那 6 个。
