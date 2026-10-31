# 第 2 批第 5 轮：U-H-1 核实 + G 批 8 条 + H 批 6 条

> 依据：`docs/CODE-AUDIT-2026-10-30.md` 第八节
> 日期：2026-10-31 · 分支 main · 基线 HEAD `1caf7e5`（任务书写 `c6655b2`，其后 3 个提交均为纯 docs）
> **收工报告（交接用）**：[`docs/HANDOFF-BATCH2-ROUND5.md`](../docs/HANDOFF-BATCH2-ROUND5.md) —— 本文件是施工计划，那份是结果与遗留问题。

---

## 一、基线（实测，非引用）

```
git status --porcelain          ?? build/night-run-report.md（唯一未跟踪项，src/ 干净）
git rev-list --left-right --count fork/main...HEAD   0 0
门禁  101 条目 / 1072 PASS / 2 FAIL（仅 VRegr2 R-5/R-6，informational）/ 末行 ALL PASS
```

汇总数由 `awk` 对 101 行逐探针输出求和得到，不是目测。

---

## 二、U-H-1 核实结论：**不是 bug**（撤销）

### 判定链（javap 实证）

`D:\deepseekwork\_idea-anim\idea-libs\intellij.platform.ide.impl.jar`

```
EditorImpl.visualPositionToXY(vp)              // 只做 read action 包装 + 装箱
  -> EditorView.visualPositionToXY(vp)         // 只做 prepareToMapping + 转发
    -> EditorCoordinateMapper.visualPositionToXY(vp)
         y = visualLineToY(line) = visualLineToYRange(line)[0]
             = insets.top + line*lineHeight + customRegionsYAdjustment + inlayBlockHeight
         x = getStartX(line) = insets.left (+ prefix text width)  或 Fragment.visualColumnToX(column)
```

- 整条链**没有任何 `ScrollingModel` / `visibleArea` / 视口偏移**。
  `EditorCoordinateMapper` 内 grep `ScrollingModel|visibleArea|Viewport|scroll` **0 命中**。
- 反向 API 一致：`EditorImpl.xyToLogicalPosition` → `EditorView.xyToVisualPosition` → mapper，
  只把负坐标钳到 0，**不加滚动偏移**。说明坐标空间就是 contentComponent。
- 平台自己的证据：`EditorView implements VisibleAreaListener`，而
  `EditorView.visibleAreaChanged(VisibleAreaEvent)` 方法体只有 `checkFontRenderContext(null); return;`
  —— 滚动**不改变坐标映射状态**。

### 结论

`visualPositionToXY` 返回的是 **contentComponent 坐标系下的内容坐标**，滚动时**不变**。
覆盖层 `CaretTrailPanel` 正是 `content.add(panel)` + `setBounds(0,0,content 宽高)`，
随内容组件一起滚动，因此快速路径早退后沿用旧坐标是**正确的**。

审查报告的推断（「返回值含滚动偏移」）被推翻。报告自己在 8.6 待确认第 1 条
已标注该前提是「平台语义推断、需实机一验」，本次核实把它判为**假**。

**2026-10-31 用户已认可撤销**（原话：「认可，撤销」）。审计条目已标注撤销，
第八节汇总计数加了复核注（有效 HIGH 8 → 7，原始计数保留以便追溯）。

### 残余不确定性（如实记录）

- 证据取自本机 `idea-libs` 副本（项目编译用的同一份）。
  PyCharm 2025.2（252）安装目录未在本机定位，未单独比对那一份 jar。
- 用户「从没见过」与该结论一致，不构成反证也不需要反证。

### 副作用

- 本部分**未改任何生产代码**。
- 按白名单允许，在 `docs/CODE-AUDIT-2026-10-30.md` 第八节该条目下加一行复核结论。

---

## 三、G 批（8 条）改法与判据

| # | 条目 | 改法 | 探针判据（红→绿） |
|---|---|---|---|
| G1 | U-M-2 | `CaretAnimator` 增 `quadScratchX/Y` 复用；`groupMaxOffset` 改成员数组按需扩容；抽出 `fillQuadXs/fillQuadYs` 供探针直接调用 | 结构：`tickSpring`/`tickStaticCaret` 内无 `new double[]{`；行为：`fillQuadXs` 写入值与四角 `getCurrentX()` 逐位相等 |
| G2 | U-M-10 | `Point byOffset` 提到 try 外；整段日志包 `if (CursorDiag.enabled())` | 结构：IME 判据区间内 `offsetToXY` 只出现一次，且 `imeSkipPicked` 在 `CursorDiag.enabled()` 内 |
| G3 | U-M-1 | `CONFIG` 改 `volatile` 引用；`applyConfigOnEdt` 入口加 EDT 契约守卫（平台存在时）；纯函数 `isEdtContractViolated(hasApp,isEdt)` 供探针断言 | 反射：字段含 volatile；行为：真值表 3 组 |
| G4 | U-L-1 | `alignment` 改 `alignmentScratch` 成员复用 | 结构：`assignRanks` 内无 `new double[`；反射：字段存在且长度 4 |
| G5 | U-L-3 | **已在轮次 2~4 修复**（`traceId` 已在 `:2686`/`:2741` 使用） | 守门：全文件 `System.identityHashCode(this)` 仅 1 处（traceId 初始化） |
| G6 | U-L-5 | 迟滞基准改为「上次的原始输入（宽/高/系数）」，输入没变才跳过 | 行为：同尺寸下系数 0.20→0.21，`getGlowWidth()` 必须从 6.4 变为 6.72 |
| G7 | U-L-6 | `drawQuadGlow` 顶部补 `isFinite` 守卫（与 `buildRgbWashPaint:285` 同构） | 结构：守卫存在 |
| G8 | U-L-10 | `currentGlowLayers()` 每帧缓存一次；辉光算式只保留一处说明 | 结构：`drawQuadGlow` 内 `currentGlowLayers()` 调用恰 1 次 |

## 四、H 批（6 条）改法与判据

| # | 条目 | 改法 | 探针判据 |
|---|---|---|---|
| H1 | U-M-23 | 从每帧链摘除 `setGlowStrength`；`glowStrength`/`updateCorners`/`ucX/ucY` 标注「预览与探针专用」；删掉「为 0 时完全不绘制光晕」的谎言 | 结构：`tickSpring` 内无 `setGlowStrength`；面板 javadoc 无该表述 |
| H2 | U-L-2 | 删 `CaretAnimator.CORNER_RELATIVE` 死表；`tools/TrailParityTest:45` 注释改指 `SpringTrail` | 反射：`CaretAnimator` 无该字段；`SpringTrail` 有且长度为 4 |
| H3 | U-L-4 | no-trail 分支补 `currentDelayNs`/`wasHidden` 更新（不可见时降频；重新可见时置 forceResync） | 结构：该分支内引用 `currentDelayNs` 与 `wasHidden` |
| H4 | U-L-11 | ① 合并 `trails` 双 javadoc（写清为什么 HashMap）；② `refreshGlowPadding` javadoc 归位；③ 删 `newCenterX` 幽灵代码引用与过期行号；④ 修正「静态光标只能靠 refreshGlowPadding」的过期断言；⑦ `CaretTrailPanel` 悬空注释组归位；⑧ `NeovideCaretManager:693` 行号引用去掉 | 结构：`newCenterX` 0 命中；`refreshGlowPadding` javadoc 与 `void refreshGlowPadding()` 相邻；行号引用 `:1018-1019`/`:1007` 0 命中 |
| H5 | U-L-12 | `frameSeq` 声明处注释改口径：每 15 次慢路径采样一次，不是帧号 | 结构：注释含「慢路径」且不含「帧号」 |
| H6 | U-L-13 | 新增纯函数 `isEdtContractViolated` + 在 `applyConfigOnEdt` 入口接守卫；**不**全包撒 | 行为：真值表；结构：`applyConfigOnEdt` 调用了守卫 |

### 白名单外残项（如实登记，不做）

- U-L-11⑥ 的第三说法在 `RenderScheduler:167`（不在白名单）——本文件修两处后三处已一致，
  `RenderScheduler` 侧不改。
- U-L-11⑧ 的 `CaretSchemeWatcher:35-36` 在本轮「绝对不许改」名单内 —— 只修 `NeovideCaretManager` 侧。
- U-L-12 的 `CursorDiag` 文档在本轮禁止修改 —— 只修 `CaretAnimator` 侧。
- U-H-1 报告段落 `docs/CODE-AUDIT-2026-10-30.md` 允许追加一行复核结论。

---

## 五、执行顺序

1. 新建探针 `tools/settings-probe/VAuditBatch2Round5.java`（先写断言）→ 登记进 `build-v-probes.ps1`
2. 跑门禁 → 记录 RED 名单
3. 改生产代码 → GREEN
4. 每条做变异测试（改坏 → 对应断言必红 → 还原 → 绿）
5. 四条决策落 `docs/PROJECT-DECISIONS.md` / `docs/NEXT-STEPS.md`
6. code-reviewer + java-reviewer 复查整个 diff
7. 全量门禁 + 收工报告（不提交）

## 六、四条决策落点

| 决策 | 落点 |
|---|---|
| U-M-3 静止仍每帧重绘，保持现状 | `docs/PROJECT-DECISIONS.md` 新增一行 + `CaretAnimator` 提交重绘处加注释 |
| U-M-21 运行时禁用钩子算待办，不放开 plugin.xml | `docs/NEXT-STEPS.md` 待办表 |
| 第 3 批（ui/settings/）等 U-H-1 结论后再开始 | `docs/NEXT-STEPS.md` 排期说明（本轮结论：可开始） |
| U-H-1 结论（用户 2026-10-31 已认可撤销） | 报告正文 + `docs/CODE-AUDIT-2026-10-30.md` 条目撤销标注与汇总复核注 |

## 六甲、执行结果（2026-10-31 实测）

```
门禁基线（开工）  101 条目 / 1072 PASS / 2 FAIL（VRegr2）/ ALL PASS
门禁收工          102 条目 / 1103 PASS / 2 FAIL（VRegr2）/ ALL PASS   ← 新增 1 探针 31 条断言
D0 门禁           PASS（core/NeovideConfig.java 零 diff）
变异测试          16/16 全部在预期断言上变红（撤改动后恢复绿，见下表）
```

| 变异 | 对应断言 | 结果 |
|---|---|---|
| tickSpring 恢复 `new double[]{` | G1-a | RED |
| groupMaxOffset 恢复每帧新建 | G1-c | RED |
| 日志实参恢复二次 offsetToXY | G2-a | RED |
| 去掉 `volatile` | G3-a | RED |
| 翻转 EDT 契约真值 | G3-b | RED |
| alignment 恢复每次新建 | G4-a | RED |
| 再写一处 identityHashCode(this) | G5 | RED |
| 恢复 0.5px 迟滞 | G6-b | RED |
| 去掉 isFinite 守卫 | G7 | RED |
| 恢复两次 currentGlowLayers() | G8 | RED |
| 恢复每帧 setGlowStrength | H1-a | RED |
| 放回死表 CORNER_RELATIVE | H2-a | RED |
| no-trail 分支去掉降频 | H3-a | RED |
| 注释里放回 newCenterX | H4-a | RED |
| frameSeq 恢复旧注释 | H5 | RED |
| 去掉 applyConfigOnEdt 入口守卫 | H6-a | RED |

### 审查复查发现的自身问题（已修）

两个审查代理（code-reviewer / java-reviewer）结论：**0 CRITICAL / 0 HIGH**。
共同抓到一条 MEDIUM：本文件 §七 风险 2 的 javadoc 曾写成「对 CONFIG 的写只发生在 EDT」，
而 `applyConfig()` 的非 EDT 分支确实会在调用方线程上先 `syncStateToConfig()` 再 `invokeLater`。
已按实际行为改写契约，并给 5 个写 CONFIG 的公开 setter 补上同一条守卫（只 warn 不抛）。
另有 4 条 LOW 一并修：注释里的过期行号、探针 G2-a 的 `<=1` 收紧成 `==1`、
G1-a/b 的字面匹配放宽成 `new double[`、`alignmentScratch` 长度改为跟随 `CORNER_RELATIVE.length`、
`updateGlowRadius` 补非有限输入守卫。

## 七、风险与预案

- **风险 1**：G1 的 scratch 数组若在多个 animator 间共享会串台 → 必须是实例字段，不共享。
- **风险 2**：G3 的 volatile 只有引用级可见性，字段级仍非 volatile → 在 javadoc 中写清
  「生产写点全在 EDT、读点也在 EDT；volatile 只保证对象安全发布」，不夸大。
- **风险 3**：H6 的 EDT 守卫若硬抛会打断离线探针 → 守卫在 `Application == null` 时放行，
  且只 warn 不抛（避免在平台合法路径上误伤正常使用）。
- **风险 4**：H4 的注释大改可能改坏 javadoc 结构 → 每处只动被点名的句子，不做全文重排。
