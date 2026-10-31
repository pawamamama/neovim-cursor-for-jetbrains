# 交接 · 审计第 2 批第 5 轮（U-H-1 核实 + G 批 8 条 + H 批 6 条，未提交）

> 日期：2026-10-31 · 分支 `main` · 开工 HEAD `1caf7e5`（任务书写 `c6655b2`，其后 3 个提交均为纯 docs）
> 依据：`docs/CODE-AUDIT-2026-10-30.md` 第八节 · 计划稿 `plans/audit-fix-batch2-round5.md`

---

## 一句话

**U-H-1 是误报，已撤销（用户 2026-10-31 认可）；G/H 两批 14 条全部完成，观感零变化；门禁 102 条目 / 1103 PASS / 2 FAIL（仅 VRegr2）/ ALL PASS；16 组变异测试全部按预期变红；未提交。**

---

## 一、环境事实

```
仓库          D:\deepseekwork\neovim-cursor-pr
HEAD          1caf7e5
与 fork 同步   git rev-list --left-right --count fork/main...HEAD = 0 0
工作区        改动未提交（见本文件 §六）
D0 门禁       PASS（core/NeovideConfig.java 零 diff）
IDE           未装本轮产物 —— 本轮的「零观感变化」由代码层证据 + 探针保证，未做实机对比
```

---

## 二、U-H-1 核实：不是 bug（撤销）

### 判定链（javap，`D:\deepseekwork\_idea-anim\idea-libs\intellij.platform.ide.impl.jar`）

```
EditorImpl.visualPositionToXY(vp)
  └─ lambda$visualPositionToXY$43 → EditorView.visualPositionToXY(vp)      // 只 prepareToMapping 后转发
       └─ EditorCoordinateMapper.visualPositionToXY(vp)
            y = visualLineToY(line) = visualLineToYRange(line)[0]
            x = getStartX(line) 或 Fragment.visualColumnToX(column)
```

`visualLineToYRange` 的 y 计算（字节码逐句转写）：

```
insets.top + visualLine * lineHeight
  + FoldingModelInternal.getCustomRegionsYAdjustment(...).first
  + InlayModelEx.getHeightOfBlockElementsBeforeVisualLine(...)
```

四样东西：内边距、行高、折叠调整、inlay 高度。**没有一样与滚动有关。**
`EditorCoordinateMapper` 全类 grep `ScrollingModel|visibleArea|Viewport|scroll` = **0 命中**。

反向验证：`EditorImpl.xyToLogicalPosition` → `EditorView.xyToVisualPosition` → mapper，只把负坐标钳到 0，
**不加滚动偏移** —— 坐标空间就是 contentComponent。

平台自身旁证：`EditorView implements VisibleAreaListener`，其 `visibleAreaChanged(VisibleAreaEvent)`
方法体只有 `checkFontRenderContext(null); return;` —— 滚动不改变坐标映射状态。

### 结论

`visualPositionToXY` 返回**内容坐标**，滚动时不变。覆盖层 `CaretTrailPanel` 挂在 `contentComponent`
（`content.add(panel)` + `setBounds(0,0,content 宽高)`），随内容一起滚动 —— 快速路径早退**是正确的**。

报告 8.6 待确认第 1 条自己标注过「`visualPositionToXY` 含滚动偏移属平台语义推断」，
本次核实判该推断为**假**。用户「从没见过」与该结论一致。

### 文档落点

- `docs/CODE-AUDIT-2026-10-30.md`：条目标题加「【2026-10-31 撤销，不是 bug】」；
  8.2 与末尾汇总块各加一条复核注（有效 HIGH 8→7，**原始计数保留以便追溯**）；
  前 770 行 sha1 仍为 `3fd18a4060d0f2b1b2349d99fb5e2a39dca3ae1a`。
- `plans/audit-fix-batch2-round5.md` §二 记录用户认可。
- `docs/NEXT-STEPS.md` §7.1 第 10 行补「用户当日已认可撤销」。

### 残余不确定性

证据取自本机 `idea-libs` 副本（项目编译用的同一份）。**PyCharm 2025.2（252）那份 jar 未单独比对**
—— 安装目录在本机没定位到。要 100% 覆盖两个目标平台，可对它复跑同一组 javap。

---

## 三、G 批 8 条（热路径微优化）

| 条目 | 改法 | 关键点 |
|---|---|---|
| **U-M-2** | `CaretAnimator` 增实例字段 `quadScratchX/Y`、`groupMaxScratch`；抽出 `static fillQuadXs/fillQuadYs` | `addQuad` 是**立即拷贝**语义，所以复用安全；数组必须实例级 —— 静态会让多编辑器串台 |
| **U-M-10** | `byOffset` 的结果存进 `byOffsetX` 供日志复用；日志整段包 `if (CursorDiag.enabled())` | 原来诊断关着也二次 `offsetToXY`，且那次求值在 try 外，抛出会打断整条慢路径 |
| **U-M-1** | `CONFIG` 改 `volatile`；新增纯函数 `isEdtContractViolated` + `assertEdtContract`（只 warn 不抛） | **没有做「整体替换引用」**：`CaretAnimator` 构造时持有该对象，换引用会让已开编辑器冻在旧配置上。理由写在 `CONFIG` 的 javadoc 里 |
| **U-L-1** | `alignment` 改 `alignmentScratch` 成员复用，长度取 `CORNER_RELATIVE.length` | 同一函数原来两套策略（一个复用、一个每次 new） |
| **U-L-3** | **轮次 2~4 已修**，本轮只补回归守门 | `identityHashCode(this)` 全文件仅 1 处（traceId 初始化） |
| **U-L-5** | 去掉 0.5px 迟滞，改成**按输入判重**（宽/高/系数三者都没变才跳过） | 见下「取舍说明」 |
| **U-L-6** | `drawQuadGlow` 顶部补 `!Float.isFinite(quadGlow) || !isFiniteQuad()`；`peak<=0f` 改 `!(peak>0f)` | 后者顺手把 NaN 也挡住（NaN 与 0 比较恒 false） |
| **U-L-10** | `currentGlowLayers()` 每帧只取一次 | 原来守卫与赋值处各调一次 |

**U-L-5 的取舍（任务要求二选一）**：没有改「迟滞基准」，而是**按输入判重**。
理由：改成「上次真正更新的值」在「系数固定、尺寸小步变化」时仍然会吞掉一步；
按输入判重才是「输入变了输出必变」，亚像素抖动照样被吸收。调用点只在几何变化与设置应用，
不在每帧热路径，所以去掉阈值不增加任何写入。

---

## 四、H 批 6 条（死代码与注释）

| 条目 | 改法 |
|---|---|
| **U-M-23** | 从每帧链摘除 `setGlowStrength`；`glowStrength`/`updateCorners`/`ucX/ucY` 标注「预览与探针专用」；删掉「为 0 时完全不绘制光晕」的谎言，写清真正的两个读者（预览窗 / updateCorners） |
| **U-L-2** | 删 `CaretAnimator.CORNER_RELATIVE` 死表（零读者）；`SpringTrail` 那份是唯一活表；`tools/TrailParityTest` 注释改指 `SpringTrail` |
| **U-L-4** | no-trail 分支补 `currentDelayNs = hiddenTierDelayNs(false)` / `wasHidden`，重新可见时置 `forceResync` |
| **U-L-11** | ① 合并 `trails` 的双 javadoc（写清为什么必须 HashMap：块选区用 Integer 键，`Integer.valueOf` 缓存外每次新对象）；② `refreshGlowPadding` 的 javadoc 归位到方法本身；③ 删 `newCenterX` 幽灵代码与过期行号 `:1018-1019` / `:1007`；④ 修正「静态光标连慢路径都进不去」的反向断言（控制台编辑器**会**走 refreshTarget，跳过它的是 refreshGlowPadding 自己）；⑦ `CaretTrailPanel` 悬空注释组改成普通注释；⑧ manager 侧 dispose 描述已由第 4 轮补上真实调用者 |
| **U-L-12** | `frameSeq` 声明处改口径：「每进入一次慢路径递增 1，不是显示帧号；采样是每 15 次慢路径一次」 |
| **U-L-13** | 新增纯函数 + `applyConfigOnEdt` 入口守卫 + **5 个写 CONFIG 的 public setter** 各接一条（`setTrailMode` / `setHideNativeCaret` / `setOverlayFadeOut` / `setAlwaysHighFps` / `setEnabled`）。只 warn 不抛，`Application` 为空（离线探针）时放行 |

### 白名单外残项（登记，未做）

| 项 | 原因 |
|---|---|
| U-L-11⑥ 的第三说法（`RenderScheduler:167`） | 不在白名单；本文件修完两处后三处已一致 |
| U-L-11⑧ 的 `CaretSchemeWatcher:35-36` | 本轮「绝对不许改」名单 |
| U-L-12 的 `CursorDiag` 文档 | 本轮禁止修改，只修 `CaretAnimator` 侧 |
| `setPaintDisabled` 的 EDT 守卫 | 审查提过；它主要服务探针/测试，本轮只给写 CONFIG 的入口接线，未扩到它 |

---

## 五、探针与变异测试

新增 `tools/settings-probe/VAuditBatch2Round5.java`（31 条断言），登记进 `build-v-probes.ps1` 的 `$mains`。

```
门禁基线（开工）  101 条目 / 1072 PASS / 2 FAIL（VRegr2）/ ALL PASS
门禁收工          102 条目 / 1103 PASS / 2 FAIL（VRegr2）/ ALL PASS
```

**变异测试 16/16 全部在预期断言上变红**（撤改动后逐条恢复绿）：

| 变异 | 断言 | 结果 |
|---|---|---|
| 恢复 `new double[]{` | G1-a | RED |
| 恢复每帧 `new double[maxGroup+1]` | G1-c | RED |
| 日志实参恢复二次 `offsetToXY` | G2-a | RED |
| 去掉 `volatile` | G3-a | RED |
| 翻转 EDT 契约真值 | G3-b | RED |
| `alignment` 恢复每次新建 | G4-a | RED |
| 再写一处 `identityHashCode(this)` | G5 | RED |
| 恢复 0.5px 迟滞 | G6-b | RED |
| 去掉 `isFinite` 守卫 | G7 | RED |
| 恢复两次 `currentGlowLayers()` | G8 | RED |
| 恢复每帧 `setGlowStrength` | H1-a | RED |
| 放回死表 `CORNER_RELATIVE` | H2-a | RED |
| no-trail 分支去掉降频 | H3-a | RED |
| 注释里放回 `newCenterX` | H4-a | RED |
| `frameSeq` 恢复旧注释 | H5 | RED |
| 去掉 `applyConfigOnEdt` 入口守卫 | H6-a | RED |

### 关于「结构判据」的诚实边界

本批一部分断言读的是**源码文本**（分配与注释没有行为面）。按陷阱 4，这类判据天生弱，
逐条靠变异测试补强。本轮**三次被自己的解释性注释误伤**（G1-b / G7 / H6-a：注释里复述了
被禁的 token，导致「改坏也绿」或「没改也红」），都是变异测试发现的，已改成
「匹配带引号的调用」「区间结束标记避开辅助方法」等更精确的写法。

---

## 六、工作区状态

```
 M CLAUDE.md                 探针数 104→105、生产行数 19,088→19,449
 M START-HERE.md             探针数 104→105
 M docs/CODE-AUDIT-2026-10-30.md   U-H-1 撤销标注 + 两处复核注
 M docs/NEXT-STEPS.md        探针数 / 生产行数 / §7.1 第 9~10 行
 M docs/PROJECT-DECISIONS.md §六 新增 U-M-3 决策
 M src/.../ui/CaretAnimator.java      G1 G2 G3(守卫) G4 G5 H1 H2 H3 H4 H5 + U-M-3 注释
 M src/.../ui/CaretTrailPanel.java    G6 G7 G8 H1
 M src/.../ui/NeovideCaretManager.java G3 H4 H6 + 5 个 setter 守卫
 M src/.../ui/SpringTrail.java        G4
 M tools/settings-probe/build-v-probes.ps1  登记新探针
 M tools/TrailParityTest.java        单行注释（任务书点名）
?? plans/audit-fix-batch2-round5.md
?? tools/settings-probe/VAuditBatch2Round5.java
?? docs/HANDOFF-BATCH2-ROUND5.md     （本文件）
```

### 白名单外字节变化

| 文件 | 归属 |
|---|---|
| `tools/TrailParityTest.java` | **本轮**：任务书 H2 点名的单行注释修正，数值零变化 |
| `docs/INDEX.md`（M） | **不是本轮**：另一个会话在写「面板二次重构」决策（P11），我未读未写 |
| `docs/DECISIONS-PANEL-2026-10-31.md`（未跟踪） | **不是本轮**：同上 |
| `build/night-run-report.md`（未跟踪） | 开工前就在 |

---

## 七、代码复查（code-reviewer + java-reviewer 一次性复查整个 diff）

**结论：0 CRITICAL / 0 HIGH（运行时可触发缺陷为 0）。**

两个代理共同抓到一条**我自己写错的中庸问题**：`CONFIG` 的新 javadoc 写「对 CONFIG 的写只发生在 EDT」，
而 `applyConfig()` 的非 EDT 分支确实会在调用方线程上先 `syncStateToConfig()` 再 `invokeLater`
（其中 `c.trailFactors = deriveTrailFactors(...)` 会新建并发布数组）。这恰好撞上本轮 H 批的主题
「注释不许撒谎」，已按实际行为改写，并给 5 个写 CONFIG 的 setter 补守卫。

其余被采纳的 LOW：注释里的过期行号（`CaretTrailPanel:554` → 方法名）、探针 G2-a 的 `<=1` 收紧成 `==1`、
G1-a/b 的字面匹配放宽为 `new double[`、`alignmentScratch` 长度改为跟随 `CORNER_RELATIVE.length`、
`updateGlowRadius` 补非有限输入守卫。

**未采纳（记录）**：`setPaintDisabled` 的守卫（见 §四残项）；`volatile` 在引用从不重新赋值时
不提供额外屏障（javadoc 已如实说明，不构成缺陷）。

---

## 八、未验证 / 需实机

1. **「观感零变化」是代码层证据，不是实机对比**：有 G1-e（角点逐位相等）、G6（半径数值）、
   G8（取值次数）这类行为断言，但**没有截图/录屏**。要完全确认需装包肉眼过一遍拖尾、辉光、多光标。
2. **U-H-1 的平台证据只覆盖 `idea-libs` 那份 jar**，PyCharm 252 侧未比对。
3. **U-L-4 的降频效果无实机数据**：结构判据 + 变异测试，未测「隐藏且无拖尾」时的实际 EDT 任务数。
4. **`CONFIG` 非 EDT 分支的竞态是既存设计**（为了让「调用完立刻读 CONFIG」看到新值），
   本轮只把契约写准、加守卫，**没有改行为**。

---

## 九、下一步

1. **提交**（本轮按要求未提交）。建议一条提交，信息里写明「零观感变化 + 16 组变异」。
2. **实机验证**（可选但推荐）：装包后看三处 —— 单行打字拖尾、多光标、辉光淡出。
3. **第 3 批审查（`ui/settings/`）可以开始**：U-H-1 这个前置条件已消除（见 `docs/NEXT-STEPS.md` §7.1 第 10 行）。
4. 若要做 U-H-1 的最终确认，可在 PyCharm 252 上复跑同一组 javap。
