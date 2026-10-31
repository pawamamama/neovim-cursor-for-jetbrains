# 第 3 批审查 · 交接报告（2026-10-31）

> 给**下一个会话**（决策会话或修复会话）。读完这一份 + 报告 §九，就能直接决定下一步。
> 完整证据在 `docs/CODE-AUDIT-2026-10-30.md` **§九**（2360 行里的 629 行是本轮新增）。
> 施工背景在 `plans/panel-refactor.md` 与 `docs/DECISIONS-PANEL-2026-10-31.md`。

---

## 零、一句话现状

面板重构已完成并提交（`a1efc13`，已推 `fork/p11-panel-refactor`）；
全量审查跑完，**1 CRITICAL + 3 HIGH + 11 MEDIUM + 16 LOW，结论 BLOCK**；
**重构本身零回归**，BLOCK 由一条「落盘失败被吞掉」驱动。**代码一行未改。**

---

## 一、代码与分支状态（收工时实测）

```
分支            p11-panel-refactor
HEAD            a1efc13  refactor(ui): 设置面板按标签页空间重排（P11）
远端            fork/p11-panel-refactor...HEAD = 0 0（已备份）
与 main 关系    fork/main...HEAD = 0 1（领先一个提交，未合并）
工作区          M docs/CODE-AUDIT-2026-10-30.md（+629 / −0，只追加）
                ?? build/night-run-report.md
                ?? plans/batch3-review-prompt.md
门禁            102 条目 / 1103 PASS / 2 FAIL（仅 VRegr2）/ ALL PASS（exit 0）
D0              core/NeovideConfig.java 零 diff（本轮未动任何生产代码）
```

提交 `a1efc13` 的 4 个文件：`NeovideCursorConfigurable.java`、`VUi.java`、
`DECISIONS-PANEL-2026-10-31.md`、`plans/panel-refactor.md`（新增）。
`AnimationPreview.java` **零改动**（AC-002）。

---

## 二、审查方法与可信度

- 4 个代理并行：通用质量 / Java 状态同步与平台 API / 静默失败 / 安全（已收窄，禁 OWASP）。
  四个全部成功，原始 34 条 → 去重合并 31 条。
- 调度者对**每一条 P0/P1/P2 的行号与代码原文**做了 sed/grep 复核（清单见报告 §9.1.4 末尾）。
- 未做变异测试（白名单禁改生产代码与探针）；未实机。

---

## 三、发现总表

```
P0 CRITICAL  1
P1 HIGH      3
P2 MEDIUM   11
P3 LOW      16
```

### 必须先修的三条（按优先级）

**1. P-C-1（CRITICAL，唯一 BLOCK 依据）——落盘失败被吞掉**
- 位置：`NeovideCursorConfigurable.java:3222`（`svc.persistQuietly();` 返回值丢弃）。
- 产生侧：`NeovideCursorSettings.java:696-757` 返回三态 `PersistResult`；`FAILED` 只 `LOG.warn`。
- 后果：磁盘只读 / 序列化自检失败 / `saveSettings()` 抛异常 → 面板照常「应用成功」、对话框正常关闭
  → 重启后设置全部回滚，全程零提示。
- 改法（两道）：① 接住返回值；`FAILED` 时**不**推进 baseline；
  ② 给用户可见信号（错误框 / 平台通知 / 阻止关闭）。
- 风险：极小，只动 apply 的返回处理；注意 `CONFIRMED` 也不等于「磁盘已写入」（`:688-694` 自述）。
- 同类调用点不在 8 文件内：`CursorPresetApplier:73/138/200/228`、`NeovideCaretManager:348`（可同一批修，也可只登记）。

**2. P-H-1（HIGH）——两条派生提示用两参 `deriveTrailFactors`**
- 位置：`NeovideCursorConfigurable.java:3000-3001`、`:3081-3082`；运行期三参在 `NeovideCursorSettings:464-465`。
- 后果：默认参数下面板显示「后缘 110 ms / 前缘 46 ms」，真实是 95 ms / 35 ms；
  同一页预览已用三参 → 数字与预览互相矛盾。
- 改法：两处改三参。
- **契约变更**：`VHints:54-56` 的 oracle 用两参自算，必须同步改成三参；
  否则会红 3 条 —— 这是**契约变更，不是缺陷**，不许反过来改生产代码迁就断言。

**3. P-H-3（HIGH）——坏快照时下拉「声称已切换」**
- 位置：`NeovideCursorConfigurable.java:2047`（`:2525` 同缺陷）。
- 现状：`restoreSkipping()` 返回 `applied`，被丢弃；返回 0 时仍写 `animCustomName`。
- 参照实现：`CursorPresetApplier:120-145` 已处理 `applied == 0`（不写名字、不落盘、返回 false）——
  **照抄那一段即可**，顺带消除两条路径分家。
- 注意：`0 < applied < total` 的「部分缺失」是文档化的向后兼容（旧预设少键），不要报成错误。

**4. P-H-2（HIGH，但需要先拍板）——锐度换算不可逆**
- 位置：`SettingsUiUtil.java:59-68` + `NeovideCursorConfigurable.java:1904-1913`（失焦无条件写回）。
- 事实：`sharpnessOf(0.2f)=73`，`blurOfSharpness(73)=0.19849999f`，差 1.5e-3 > `EPS=1e-6`。
- 后果：点一下输入框再移出（什么都不改）→ pending 变脏 → 应用按钮点亮 →
  当场派生「基础 副本」。
- **两个修法，请用户选**（见第五节）。

### 其余（P2 / P3）

P2 简表（完整条目见报告 §9.3）：
- P-M-1 `apply()` 逐字段写活 State，无回滚；
- P-M-2 搜索关键词映射错（「常驻/高帧/fps」→ 辉光层数），且多数项点了没反应；
- P-M-3 颜色坏快照静默；
- P-M-4 「检查更新」失败与没反应不可区分；
- P-M-5 预设数值快照不 clamp；
- P-M-6 颜色快照不校验 HEX；
- P-M-7 3784 行内聚性（已附三步切分方案）；
- P-M-8 预览 rank 算法复制品；
- P-M-9 抑制标志不保存前值（潜伏，**已确认无死锁**）；
- P-M-10 `bindCheck` 绕过预设显示刷新；
- P-M-11 dispose 未释放三个预览（最便宜，补 8 个 null）。

P3 16 条以注释/死代码/命名为主，报告里每条都给了行号，可批量处理。

---

## 四、修复时的硬约束

1. **D0 红线**：`core/NeovideConfig.java` 零 diff。
2. **不要为了让测试通过而改断言**。唯一的契约变更是 `VHints` 的 oracle（P-H-1），
   改它要在旁边写明「契约变更，非缺陷」。
3. **探针常数本轮没有变**：`VUi.KEYS/SPEC`、`VAddSetting:115`（14/9/4）、`VTabPages` 均已同步，
   修 P-C-1 / P-H-1 / P-H-3 不需要动这些数。
4. **六层链路**：若给 P-H-2 选择「改默认值」路线，改的是 `State` 默认值与 `ANIM_PRESETS` 常量，
   不是只改换算函数——先读 `SettingsField` 与 `CursorPresets` 再动。
5. **门禁只跑一份**（四-G-2 并发假红），改完必须删 `build\v2-probe\classes` 全量重编译。
6. 修完必须重跑：全量门禁 + D0 + `git diff -- ui/settings/AnimationPreview.java`（若不该动它）。

---

## 五、需要用户拍板的四件事（问法已备好）

| # | 问题 | 选项 A | 选项 B |
|---|---|---|---|
| 1 | P-C-1 落盘失败时怎么告诉用户 | 弹错误框，面板照常关闭 | 阻止关闭，把设置页留在打开态 |
| 2 | P-H-2 锐度怎么修 | 改换算步长，让默认值落在网格上（观感零变化） | 把默认 `0.2f` 改成 `blurOfSharpness(73)`（会改预设常量，用户可见） |
| 3 | P-M-2 搜索 | 保留搜索，「无匹配」时定位到页顶 | 干脆不实现 `enableSearch`，交给平台 |
| 4 | P-M-10 复选框 | 改复选框也派生副本（与滑块一致） | 不派生，只刷下拉显示态 |

这些问的是**观感/行为**，不是实现细节（纪律四-L）。答错用户能看出来。

---

## 六、不要重报 / 不要重开的（已定案）

- 「`<br/>` 不被 `rowComment` 解析」——**已实测推翻**：`rowComment` 走 DslLabel（JEditorPane text/html），
  24 处一直正常换行；真正漏字面量的是两条普通 `JLabel`，已在重构中修好。
- 辉光始终用 `shadowColor`，从未跟随光标色。
- 静止时仍每帧重绘——用户 2026-10-31 拍板保持现状。
- 预览窗 520×110 / 300×150 锁定。
- `VRegr2` 的 R-5 / R-6 是已知 informational。
- `sizePanel` 不需要 `registerIntegratedPanel`——两个代理独立 javap 证实 `Row.panel{}` 共用同一 `DialogPanelConfig`。
- 抑制标志**没有**「永久吞掉编辑」的死锁路径（P-M-9 是潜伏风险，不是现有 bug）。

---

## 七、下一轮提示词建议（给决策会话）

```
任务：修复第 3 批审查的 P0/P1（可选带上 P-M-11）
基线：分支 p11-panel-refactor，HEAD a1efc13，工作区只有 CODE-AUDIT 与两个未跟踪文件
必读：docs/HANDOFF-BATCH3-AUDIT-2026-10-31.md（本文件）→ CODE-AUDIT §9.3 → §9.5
范围：仅 ui/settings/ 下需要改的文件 + 若选 CursorPresetApplier 路径则加 ui/
白名单：允许改生产代码与 VHints 的 oracle；禁止改 core/NeovideConfig.java；
        禁止为了让断言通过而改其它探针
AC：① P-C-1 有可复现的失败注入方式（例如把配置目录设为只读）证明用户能看到提示；
    ② P-H-1 改后 VHints 的期望值与三参一致；③ P-H-3 用坏快照构造器复现，
    下拉不复位到「已选中」；④ 全量门禁 102/1103/2/ALL PASS；⑤ D0 零 diff
```

---

## 八、未验证 / 需实机

| 项 | 说明 |
|---|---|
| P-L-3 列位注释 | 需要真机看三行按钮落在哪一列（本报告按 `plans/panel-refactor.md:276` 的实测值判断） |
| P-M-2 搜索焦点 | `JLabel` 不可聚焦是代码层结论，实际体验需实机 |
| P-L-13 字号/配色 | 需切换 IDE 字体与浅色主题观察 |
| P-L-9 `buildSearchableOptions` | 只核到「平台可能在后台线程调 createPanel」，未实测 |
| P-C-1 同类调用点 | `CursorPresetApplier` ×4、`NeovideCaretManager` ×1 未展开 |
| 变异测试 | 本批白名单禁改生产代码，无法做「改坏 → 必须红」 |

---

## 九、证据索引

- 报告：`docs/CODE-AUDIT-2026-10-30.md` §九（9.1 基线 / 9.2 结论 / 9.3 清单 / 9.4 十类覆盖 / 9.5 重构回归 / 9.6 待确认 / 9.7 未覆盖）
- 重构计划与实测：`plans/panel-refactor.md`（含面板尺寸、提示宽度、探针契约变更）
- 用户决策原文：`docs/DECISIONS-PANEL-2026-10-31.md`（含 §9.2 对 `<br/>` 判断的更正）
- 门禁日志：`build/gate-batch3.log`（未入库，可复跑 `build-v-probes.ps1` 重新生成）
- 本批任务书：`plans/batch3-review-prompt.md`（未跟踪，可随下一步提交）
