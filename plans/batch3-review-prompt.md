# 第 3 批审查 · 施工提示词（可直接整块粘贴给执行会话）

> 落盘原因：这份提示词原先只存在于对话里，会话压缩后会丢。
> 生成：2026-10-31 · 格式沿用 `docs/CODE-AUDIT-2026-10-30.md` 前两批
> 使用：把下面 text 代码块里的内容整块粘给执行会话

---

```text
【任务：全量代码审查 · 第 3 批（ui/settings/ 设置面板）】

仓库：D:\deepseekwork\neovim-cursor-pr
分支：main（HEAD 应为 ea3f12d）
你的角色：审查调度者。派 4 个代理并行审，你汇总。

★ 审查对象是**当前工作区**，不是 HEAD ★
  面板重构刚做完、**尚未提交**（NeovideCursorConfigurable.java、VUi.java、
  DECISIONS-PANEL-2026-10-31.md、plans/panel-refactor.md 处于 M / ?? 状态）。
  要审的是重构后的代码 —— 用工作区版本，不是 git show HEAD。

============================================================
零、本任务走哪条工作流（先读这一节，别自己发明流程）
============================================================

依据：C:\Users\wangw\.claude\WORKFLOW-ALIGNMENT.md 的三层结构
（命令层 16 / 代理层 14 / 技能层 311），加上
C:\Users\wangw\.claude\rules\common\code-review.md 的判据。

── 为什么不用命令层（逐条给理由，不是一句「不适用」）──

  /code-review      Local 模式跑 `git diff --name-only HEAD`，只审**未提交改动**。
                    本任务要审整个 ui/settings/ 8 文件 5552 行，
                    而工作区里只有重构带来的 89 行改动 ——
                    跑它等于只审了 1.6%，剩下 98% 的存量代码一行都看不到。

  /quality-gate     只覆盖 .ts/.tsx/.js/.jsx/.json/.md、.go、.py。**Java 不在范围。**

  /security-scan    面向 Web 攻击面。本批第五节 5.4 明确禁止 OWASP 打分 ——
                    会对这个桌面插件产生整屏误报。

  verification-loop 「改完代码之后」的六阶段验证。本任务是**只读审查**，没有「改完」。

  delivery-gate     是 Stop hook（查学习日志 mtime、磁盘、话术正则），**不是代码门禁**。

── 所以：本任务是代理层任务 ──

4 个审查代理并行，视角按 rules/common/agents.md 的
Multi-Perspective Analysis 划分：

| 视角 | 代理 | brief 要点 |
|---|---|---|
| 通用质量（Senior engineer） | code-reviewer | 重构回归 + 内聚性 |
| 并发与线程 + 平台 API | java-reviewer | pending/State 同步、抑制标志 |
| 静默失败 | silent-failure-hunter | 用户点了没反应 |
| 安全（收窄到面板面） | security-reviewer | 搜索索引 / 预设名渲染 / 配置回写 |

判据与分级照 rules/common/code-review.md：
Review Checklist / Severity Levels / Approval Criteria
（有 CRITICAL → BLOCK；只有 HIGH → WARNING；其余 APPROVE）。

── 审查任务不走 development-workflow.md 的五步 ──

那五步是**改代码**的流程。本任务只读，对应 code-review.md 自己的三阶段：

    Phase 1 GATHER  → 第一节（前置检查与基线）
    Phase 2 REVIEW  → 第五节（4 个代理并行）
    Phase 3 REPORT  → 第六、七节（汇总分级）

Phase 4 VALIDATE 用全量门禁替代（本项目没有 CI）；
Phase 5 DECIDE 落在 9.2 节的结论。

============================================================
一、前置检查
============================================================

    git status --porcelain
    git log --oneline -3
    git rev-list --left-right --count fork/main...HEAD

期望：HEAD = ea3f12d，同步 0 0；
工作区有 M 的 NeovideCursorConfigurable.java / VUi.java / DECISIONS-PANEL，
以及 ?? 的 plans/panel-refactor.md、build/night-run-report.md。

门禁基线（仓库根，不带 -Repo，绝不并发跑两份）：
    pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1
期望：102 条目 / 1103 PASS / 2 FAIL（仅 VRegr2 informational）/ ALL PASS

============================================================
二、陷阱记录检索（四处）
============================================================

  · CLAUDE.md 第五节：陷阱 1（一字段两职责）、2（六层，⑤⑥层）、
    6（预览与生产同源）、7（三份清单同源）、8（设备开关）、12（文档会撒谎）
  · CLAUDE.md §7.0 B/C（探针契约变更、控件定位表的插入位置）
  · docs/WORK-DISCIPLINE.md：四-D、四-E、四-G、四-J
  · docs/DECISIONS-PANEL-2026-10-31.md §九（重构施工记录，含一条推翻原判断的实测）

============================================================
三、本轮范围（8 文件 / 5552 行）
============================================================

    ui/settings/NeovideCursorConfigurable.java   3784 行   ← 主战场
    ui/settings/AnimationPreview.java             971 行
    ui/settings/PluginInfoBar.java                230 行
    ui/settings/ColorPanelBinding.java            153 行
    ui/settings/EditSession.java                  127 行
    ui/settings/ProbeFacade.java                  102 行
    ui/settings/SettingsSearchIndex.java           97 行
    ui/settings/SettingsUiUtil.java                88 行

不在本轮：ui/ 其余文件（第 2 批已审）、core/、settings/、tools/settings-probe/。

============================================================
四、已知的、不要重报的（本轮返工成本最高的一条）
============================================================

**`rowComment` 是能解析 HTML 的** —— 走 `DslLabel`（`JEditorPane`，
contentType=`text/html`），24 处 `<br/>` 一直正常换行。
2026-10-31 重构时被实测推翻过一次（DECISIONS-PANEL §9.2）。
真正漏字面量的是列手感页那两条**普通 JLabel**（`singleMoveHint` / `crossMoveHint`），
已在重构中改成 HTML 三行。

其它已定案：
  · 辉光始终用 `shadowColor`，从未跟随光标本体色
  · 静止时仍每帧重绘 —— 用户 2026-10-31 拍板「先保证观感，保持现状」
  · 预览窗尺寸 520×110 锁定，不许改
  · VRegr2 的 R-5 / R-6 是已知功能缺口，informational，不报

============================================================
五、派 4 个代理（同一条消息，并行）
============================================================

共同约束（每个 brief 都完整贴）：
  · 范围：只审这 8 个文件的**工作区版本**
  · 只读：不许写任何文件、不许改任何代码
  · 输出契约：文件:行号 / 代码原文 1~5 行 / 问题一句话 /
    失败场景（什么操作 → 什么结果）/ 严重度 CRITICAL·HIGH·MEDIUM·LOW
  · 不许报：第四节列的已定案项

── 5.1 code-reviewer（通用质量 + 刚重构的代码）──
  · 重点看**重构引入的回归**：控件从 A 组搬到 B 组，
    但它的状态绑定还指向原来那条路径？
  · 次重点：`NeovideCursorConfigurable` 3784 行的内聚性。
    不要只报「太大」—— 第 1 批对 `NeovideCursorSettings` 给过同类分析
    （四类责任 / 切分边界 / 引用成本），按那个深度来
  · 检查 `build*Group()` 之间有没有可抽公共的重复布局代码

── 5.2 java-reviewer（状态同步 + 线程 + 平台 API）──
  · **跳过 Framework Detection**（JetBrains 插件，不是 Spring/Quarkus）
  · 重点：**pending 与 State 的同步**。`afterPendingEdited()` 是唯一编辑入口吗？
    还有没有别的路径绕过它？
  · `suppressDerive` / `syncingControls` 两个抑制标志：
    有没有「某个顺序下两个都为 true，编辑被永久吞掉」的可能
  · 平台 API：`JEditorPane` 的 content type、`JBUI.scale` 用法、
    `DialogPanel` 规范（有没有自创像素值绕过缩放）
  · 三份 `AnimationPreview` 实例（inline / cross / glow）的懒创建与 dispose

── 5.3 silent-failure-hunter（用户操作无响应）──
  · 关注点：**用户点了没反应 / 改了不生效 / 回滚了没有提示**
  · 逐个检查控件回调的 catch：吞掉之后用户看到什么？
  · 重点：`apply()` 里的逐字段脏合并（:2596 附近）——
    任何一条抛会不会让整个 apply 半途而废
  · 重点：`reset()` 与 `isModified()` 的对称性

── 5.4 security-reviewer（收窄到面板特有面）──
  · 不是 Web 应用，**禁止 OWASP 打分**
  · 面板独有面只有三处：
    ① 设置搜索索引（`SettingsSearchIndex`）：会不会把搜索词写进日志
    ② 预设名显示：用户可输入文本有没有在 UI 里被当 HTML 渲染
    ③ 配置回写：apply 会不会把越界值直接写进 State 再落盘
  · 没有可报的就写「0 条」，不要凑数

============================================================
六、汇总（Delegation Completion Contract）
============================================================

  · 4 个代理**全部**返回后再汇总
  · 任何代理失败 → 报告写明缺哪个视角
  · 去重合并，标注「N 个视角同时发现」
  · 冲突标「待确认」，给两种解释
  · 改代理给的严重度必须写理由

============================================================
七、报告（追加到 docs/CODE-AUDIT-2026-10-30.md）
============================================================

在文件末尾追加，**不要重写前两批的内容**：

   ## 九、第 3 批（ui/settings/ 设置面板）

   ### 9.1 基线与方法（HEAD / 工作区 / 门禁四数 / 4 代理状态 / 陷阱检索）
   ### 9.2 结论（BLOCK / WARNING / APPROVE，按 Approval Criteria）
   ### 9.3 发现清单（按严重度，格式同前两批）
   ### 9.4 十类检查覆盖结论
   ### 9.5 与面板重构（P11）的衔接
        重构有没有引入回归？§9.2 那条 `<br/>` 更正是否被后续代码尊重？
   ### 9.6 待确认
   ### 9.7 本轮未覆盖

============================================================
八、白名单
============================================================

   唯一允许改动的文件：docs/CODE-AUDIT-2026-10-30.md（追加）
   不 commit、不 push、不动任何生产代码或探针

============================================================
九、停下来问（只在需要实机时）
============================================================

  1. 要改生产代码才能确认的 → 写进「待确认」
  2. 需要装 jar 看界面的 → 标「需实机」
  3. 代理结论冲突且无法从代码判定 → 待确认

============================================================
十、禁止话术
============================================================

  「这是预先存在的问题」「为了通过测试我调整了断言」「探针过期了」「先跳过」
  「应该没问题 / 理论上」「已修复」「顺带修复了」「无关紧要的格式调整」

============================================================
十一、AC
============================================================

AC-001 4 个代理都真的跑了，状态如实
AC-002 每条发现带文件:行号 + 代码原文，可核对
AC-003 每条发现能说清失败场景
AC-004 报告只追加，前两批一个字没改
AC-005 没有把第四节列的已定案项当新发现
AC-006 9.5 节必须回答：重构有没有引入回归
AC-007 收工时 git status 只多一个已修改的文件（CODE-AUDIT）

============================================================
十二、回报（六项）
============================================================

  1) 前置检查 + 门禁四个数字
  2) 陷阱检索：四处各命中什么
  3) 4 个代理各自摘要（一句话 + 发现条数）
  4) 发现摘要：P0/P1/P2/P3 各几条，P0/P1 各一行标题
  5) 9.5 节结论：重构有没有引入回归
  6) 收工时 git status + 未验证部分
```
