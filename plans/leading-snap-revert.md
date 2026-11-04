# 计划：半边拖尾（leadingSnap）有选择回退 —— 只留开关控件，逻辑掏空

日期：2026-11-01 ｜ 依据：docs/archive/reports/LEADING-SNAP-ISSUES-2026-11-01.md、docs/archive/decisions/LEADING-SNAP-DECISION-2026-11-01.md
基线：main @ c2ae1e9 ｜ 性质：**不是 git revert** —— 删实现逻辑，保留 UI 控件与 State 字段。

---

## Research 记录

### 第 1 轮（本仓库）

**两个提交各改了什么**（`git show --stat`）：
- `30fe004` feat：新增设备级开关 leadingSnap（前缘 0ms 吸附模型）。22 文件 +1245/−69，含
  State/SettingsField/Configurable 六层接线、SpringTrail.assignRanks 锁前缘、
  TrailCorner.setLeadingSnap、AnimationPreview 接入、VLeadingSnap 552 行探针。
- `c2ae1e9` fix：模型改为「四角同长 110ms + 绘制层固定尾巴」。8 文件 +300/−113，
  引入 halfTrail/quadDirs/fillHalfTrail/HALF_TRAIL_CAP_WIDTHS/四角同长覆盖块
  （setAnimationLengthForTrail）。

**halfTrail 相关符号全仓 grep 结论**（生产 6 文件 + 探针 + 文档）：

| 符号 | 生产引用点 | 专属？ | 处置 |
|---|---|---|---|
| `SpringTrail.leadingSnap` / `setLeadingSnap` | SpringTrail:154/163；CaretAnimator:260/2249/3134/3149/3164 | 是 | 删 |
| `SpringTrail.dirX/dirY` | SpringTrail:160/206-207；唯一消费者 CaretAnimator:2907 | 是（半边拖尾） | 删 |
| `assignRanks` 四角覆盖块 | SpringTrail:351-372 | 是 | 删 |
| `CaretTrailPanel.halfTrail/quadDirs/setHalfTrail/fillHalfTrail/addQuad 5参重载` | CaretTrailPanel:100/103/106/629/972；CaretAnimator:2728/2873/2907；AnimationPreview:298 | 是 | 删 |
| `TrailCorner.HALF_TRAIL_CAP_WIDTHS` | TrailCorner:130；CaretTrailPanel:981 | 是 | 删 |
| `TrailCorner.setAnimationLengthForTrail` | TrailCorner:148；SpringTrail:370；AnimationPreview:300/781 | 是（只服务半边拖尾） | 删 |
| `CaretAnimator.leadingSnap` | :260/2249/2873/3134/3149/3164 | 是 | 删 |
| `AnimationPreview.leadingSnap/setLeadingSnap/leadingSnapForProbe` | :276/293/306；Configurable:936 | 是 | 删 |
| `AnimationPreview.dirX/dirY/lastPushX/lastPushY/pushSeen` | :282/283-284/711-722/971 | 是 | 删 |
| `CaretTrailPanel.dirX/dirY`（:139-140 数组）与 computeOffsetDirections | 辉光方向计算 | **否** | 保留 |
| `State.leadingSnap` / SettingsField 第 28 项 / copyFrom / loadState / 复选框 | 保留清单 | — | 保留 |

**「开关保留但逻辑不生效」先例**：src 下没有现成的「已声明但未实现」注释先例
（grep 尚未启用/未实现/占位 均为无关命中）。文案措辞按任务书三条要求自拟，
注释风格沿用本仓库「【日期 + 决策 + 理由】」的中文注释惯例。

### 第 2 轮（外部）

**不适用**。原因：纯删除任务，不新增任何功能、不引入任何库，没有可复用的外部实现可言。

---

## 删除清单（逐符号）

| 文件 | 删除内容 |
|---|---|
| core/TrailCorner.java | `HALF_TRAIL_CAP_WIDTHS`；`setAnimationLengthForTrail()`；类头与 jump javadoc 中提及 `setLeadingSnap` 的段落（:15、:190-193）；jump 注释 :377-383 的「已实现」段改为「已回退」口径 |
| ui/SpringTrail.java | `leadingSnap` 字段+javadoc（:146-154）；`setLeadingSnap()`（:162-165）；`dirX/dirY` 字段与 setTarget 方向记录（:159-160、:199-209）；assignRanks 覆盖块（:351-372）；config 字段 javadoc 中「半边拖尾要用」措辞 |
| ui/CaretTrailPanel.java | `halfTrail` 字段（:94-100）；`quadDirs`（:102-103）；`setHalfTrail()`（:105-108）；`addQuad` 5 参重载（3 参版改回独立实现，去 quadDirs 写入）；paintQuads 的 halfTrail 分支（:954-961）；`fillHalfTrail()`（:964-1006）。**保留** :139-140 的 dirX/dirY 数组与 computeOffsetDirections（辉光方向，非本功能） |
| ui/CaretAnimator.java | `leadingSnap` 字段（:253-260）；refreshTarget 新建 trail 处补设（:2246-2249）；tickStaticCaret 的 `setHalfTrail(false)`（:2727-2728）；tickSpring 的 `setHalfTrail(leadingSnap)`（:2872-2873）；addQuad 改回 3 参（:2902-2907）；syncGlobalSettings 中读取（:3134）、catch 重置（:3149）、推送循环及其注释（:3153-3165） |
| ui/settings/AnimationPreview.java | `leadingSnap` 字段（:269-276）；`setLeadingSnap()`（:286-303）；`leadingSnapForProbe()`（:305-308）；`dirX/dirY/lastPushX/lastPushY/pushSeen` 字段与 pushTarget 方向记录（:281-284、:710-722）；pushTarget 覆盖块（:763-783，lastRanks 的 arraycopy 保留）；paintComponent 的 addQuad 改回 3 参（:966-971） |
| ui/settings/NeovideCursorConfigurable.java | pushStyleTo 中 `preview.setLeadingSnap(...)`（:936）；复选框行注释改为回退口径。**复选框本身、位置、bindCheck、enabledIf 全部不动** |

收尾 grep：`halfTrail|fillHalfTrail|quadDirs|HALF_TRAIL_CAP|setLeadingSnap|setAnimationLengthForTrail`
全仓零残留（注释里的也算，一并清）。

## 保留清单（六层）

① State 字段 `public boolean leadingSnap = false;`（NeovideCursorSettings:912，javadoc 改「待实现」口径）
② SettingsField.build() 第 28 个 bools 项（runtimeSwitch=true，SettingsField:860）
③ copyFrom（:237）④ loadState（:1188）
⑤ 设置页标签 3 复选框「前缘贴合（半边拖尾 · 全局）」，位置、bindCheck、enabledIf 不动
⑥ SyncGlobalSettings —— **此层删除**（行为消费点，回退后不存在；这是「六层里少一层」的刻意结果）

## 文案（rowComment 最终措辞）

> [全局] **待实现：此功能尚未实现，勾选不会改变观感。**
> 开关状态会被保存，供实现后使用。不随动画预设变化；残影模式下置灰。

满足三条：①第一句就说勾了没效果；②不承诺任何时间；③无「即将推出」字样。

## 探针改动（VLeadingSnap 重写为新契约）

新契约五类 → 12 条断言：

| 编号 | 断言 | 来源 |
|---|---|---|
| L-4a | SettingsField.FIELDS 已注册 leadingSnap | 保留 |
| L-4b | syncsToConfig=false | 保留 |
| L-4c | inPreset=false | 保留 |
| L-4d | runtimeSwitch=true | 保留 |
| L-5 | copyFrom 带上 leadingSnap | 保留 |
| L-6 | 写 true → loadState → 读回 true（持久化） | 保留 |
| L-7 | 默认值 false | 保留 |
| L-8 | 切任何动画预设不改 | 保留 |
| L-9 | AnimPreset 不含该字段 | 保留 |
| L-20 | SettingsField.PRESET_FIELDS 不含 leadingSnap | 新增（契约 5） |
| L-21 | 真实 UI 链路（建面板→pending→refreshPreviews→驱动一次移动）：pending.leadingSnap=true 与 false 两种状态下 inlinePreview 四角时长**逐位相同** | 新增（契约 4 = AC-002） |
| L-22 | 五个行为层源文件（TrailCorner/SpringTrail/CaretTrailPanel/CaretAnimator/AnimationPreview）中 `leadingSnap`、`halfTrail`、`setAnimationLengthForTrail` 零出现（防复活守门） | 新增（契约 4 结构保证） |

### 旧断言逐条去向（22 条，含 L-3a）

| 旧断言 | 去向 |
|---|---|
| L-1 开启时刚性矩形 | 删。契约变更（功能回退，2026-11-01 用户决定），非缺陷；行为断言由 L-21 取代 |
| L-1b 四角同长 | 删。同上，由 L-21 取代 |
| L-2 关闭时斜度基线 | 删。开关不再影响行为，无「关闭态」可言；关闭行为回归由既有斜度探针覆盖 |
| L-3 关闭后恢复斜度 | 删。无可逆性可言（功能整体移除） |
| L-3a 可逆场景前半段 | 删。同上 |
| L-12 左移镜像刚性矩形 | 删。同 L-1 |
| L-13a 出厂默认关闭时前缘有位移 | 删。同 L-1 |
| L-13b 出厂默认开启时四角同步 | 删。同 L-1 |
| L-14 预览四角同速 | 删。由 L-21 取代（true/false 预览逐位相同） |
| L-16 预览与生产四角时长逐位一致 | 删。两侧都回到原版梯度模型，一致性由 VPreviewRank 等既有探针覆盖 |
| L-15 pending→预览链路 | 改造进 L-21（L-21 走的就是这条真实链路） |
| L-10 syncGlobalSettings 读 State | 删。syncGlobalSettings 不再读 leadingSnap；由 L-22 反向守门 |
| L-11 syncGlobalSettings 推给 trail | 删。推送循环删除；由 L-22 反向守门 |

其余探针（VDefaultFreeze GOLDEN、VerifyPanel U8/U19、VAddSetting、VLoadState、VPreset、
VSwitchKeysGap、VTabPages）只依赖「开关存在」，保留壳子后全部不受影响，不动。

## 白名单与文档

生产：TrailCorner / SpringTrail / CaretTrailPanel / CaretAnimator / AnimationPreview /
NeovideCursorConfigurable。
探针：VLeadingSnap.java。
文档：plans/leading-snap-revert.md（本文件）、CLAUDE.md §7.1、START-HERE.md、
docs/NEXT-STEPS.md、docs/PARAMETER-MAP.md。
**不碰**：core/NeovideConfig.java、settings/CursorPresets.java、settings/CursorPresetApplier.java。

## 风险

1. L-21 依赖真实 UI 链路（VUi.build → refreshPreviews → inlinePreview 非空）；
   原探针 L-15 已验证过该链路可反射驱动，风险低但跑门禁时见分晓。
2. syncGlobalSettings 的 catch 块删 `leadingSnap = false` 后，try 与 catch 的赋值集合
   仍须对称（原对称性是审查关注点）——删两侧同一行即可保持。
3. 文案变化可能影响按 rowComment 内容断言的探针 —— 已 grep，无此类探针。

## 执行顺序

1. 先改探针 VLeadingSnap 到新契约（RED：L-21 行为差异 + L-22 源码仍含符号 → 必红）
2. 删生产代码（六文件逐符号）
3. 收尾 grep 零残留
4. 全量门禁 → 绿
5. code-reviewer + java-reviewer 审 diff
6. 更新四份文档
7. 停：不提交
