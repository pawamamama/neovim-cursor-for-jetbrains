# 修复：数字输入框无效 + 辉光淡出无效果（2026-11-01）

> 依据 `docs/HANDOFF-NEXT-2026-11-01.md` §三 的用户原话。
> 交付后的完整报告：`docs/FIX-INPUT-FADE-REPORT-2026-11-01.md`（本文是计划与根因推导，报告含审查修正与验收）。
> 本文档的每一条根因都有实测证据，不写「可能」。

---

## 〇、Research 记录

### 0.1 第 1 轮：本仓库

| 查什么 | 结论 |
|---|---|
| 数字输入框的构建 | `NeovideCursorConfigurable.addSlider()` 第 1821 行起：滑块 → `row.intTextField(IntRange, init)` → `TextFieldKt.bindIntText(...)` → 手挂 `DocumentListener` + `FocusListener` |
| 输入框 → pending 的同步路径 | **只在 `apply()`**。平台 `CellImpl.bind()` 只注册三件事：`onApply`（组件 → prop）、`onReset`（prop → 组件）、`onIsModified`（比较）。**没有 document 监听**（javap 实证：`OnChangeManager.applyBinding` 只是在 `binding=true` 下执行一次初始化，不是注册监听）。 |
| 淡出的消费链 | `CaretAnimator.tickSpring → glowFade() → GlowIntensity.fade() → panel.setGlowFade() → CaretTrailPanel.drawQuadGlow()` 的 `peak = … * glowFade` |
| 预览窗的淡出 | `AnimationPreview.step()` 同一套 `GlowIntensity.fade`，`physicsTimer` 8ms 常驻 |
| 说明文案 | `src/main/resources/messages/NeovideCursorBundle*.properties` 的 `settings.row.glowFade / fadeHold / fadeDuration / comment.fadeOff` |

### 0.2 输入框数据流（完整链路图）

```
用户键入 "1"
  │
  ├─► Swing Document 变化
  │     ├─► 平台 bindIntText：无监听（见上）
  │     └─► 我们的 DocumentListener → onTextEdited(b)
  │           ① 解析整数，非法则 return
  │           ② slider.setValue(snapToTick(v))   ← 滑块会动
  │           ③ valueLabel.setText(v)            ← 标签显示精确值
  │           ④ refreshDerivedHints()
  │           ⑤ refreshPresetDisplayForInput()   ← 只改预设下拉，不刷预览
  │           ✗ 不写 pending（契约 §6.1：仅在 apply 时写）
  │
点「应用」→ NeovideCursorConfigurable.apply()
  ├─ A1 colorBindings.pushAll(pending)
  ├─ A2 super.apply() → DialogPanel 遍历 apply 回调
  │      ├─ 滑块的 onApply：pending = 滑块吸附值
  │      └─ 输入框的 onApply：pending = getValidatedIntValue(text)   ← 精确值在这里落地
  │           （越界/非法 → 抛 ValidationException，被 catch，pending 保持滑块值）
  ├─ A3..A5 逐字段比较 pending vs baseline → 写 State → applyConfig()
  └─ afterPendingEdited() → refreshPreviews()   ← 预览在这里才更新
```

**断点有两个（都不是「链断了」，而是「时机不对」）**：
1. 键入后 **pending 不变** → 预览窗不刷新（`refreshPresetDisplayForInput` 不调 `refreshPreviews`），
   用户拖滑块能看到预览实时变、在输入框里键入却看不到 —— 观感上就是「输入没效果」。
2. 若目标控件处于**禁用态**（辉光关闭时整组置灰），Document 根本不会变；
   即使能改，`CellImpl.shouldSaveOnApply()` 对禁用组件返回 false，apply 也不会写。

**实测证据（VInputCommit 诊断，headless）**：14 个滑块逐个「键入精确值 → apply」，
14/14 的 State 都变了（其中 `shortAnimationLength / animationLength` 两条是诊断脚本自己的
单位换算写错，State 实际 110→130 / 100→120，是对的）。
所以**正常路径没有断**；用户看到的是上面两个时机/禁用问题。

### 0.3 用户实际配置（决定性证据）

`%APPDATA%\JetBrains\IntelliJIdea2026.1\options\NeovideCursorSettings.xml`：

```
overlayFadeOut = false      ← 淡出开关是关的
useShadow      = false      ← 辉光本身是关的
```

在这个配置下：
- 辉光三个滑块 + 输入框全部禁用（`enabledIf(shadowOn)`，实测 `enabled=false`）；
- 淡出两个滑块同样禁用（`enabledIf(fadeOn)`）；
- 即使打开它们，`GlowIntensity.fade` 在 `overlayFadeOut=false` 时恒返回 1。

**所以「改到最大 500 观感没有变化」的第一原因是开关没开** —— 这不是功能缺失，
但也是一个真实的 UX 陷阱：面板没有任何文字说明「这两个值需要先开启辉光 / 淡出」。

### 0.4 第 2 轮：外部检索

不适用。本轮修的是本仓库自有逻辑，平台 DSL 行为已用 javap 直接反编译确认
（`TextFieldKt` / `CellImpl.bind` / `OnChangeManager`），无需外部资料。

---

## 一、BUG-A：数字输入框

### 根因（两条，都已实测）

| # | 根因 | 证据 |
|---|---|---|
| A-1 | 键入只挪滑块/标签，**不写 pending、不刷预览**；预览要等 apply 才更新 | `onTextEdited` 全文；`refreshPresetDisplayForInput` 只改下拉；`refreshPreviews` 仅在 `afterPendingEdited`/`syncAllControls` 被调 |
| A-2 | 目标组被禁用时输入框不可编辑、apply 也不保存，面板没有解释 | `addSlider(..., shadowOn)`；`CellImpl.shouldSaveOnApply()` 对禁用组件返回 false；用户配置 `useShadow=false` |

### 改法

1. `onTextEdited`：解析出**在量程内**的值后立即 `writeTick(pending, v)`，
   然后 `refreshDerivedHints()` + `refreshPresetDisplayForInput()` + **`refreshPreviews()`**。
   - 越界值仍不写 pending（保持 apply 时「拒绝越界」的既有语义，探针 S8 不变）。
   - 程序化回灌（`syncingControls=true`）仍然早退，不产生回环。
2. 文案：给「淡出」组补一句前提说明（见 §三）。

### 验证

- 新探针 `VInputCommit`：14 个滑块逐个
  「键入 → pending 立即变 → apply → State 变」；
- 越界键入不写 pending；程序化 setText 不自激；
- 变异测试：注释掉 `onTextEdited` 里的 `writeTick` → 断言必红。

---

## 二、BUG-B：辉光淡出

### 判定：**(b) 功能实现了，但参数在特定配置下不起作用**，另有两处真实缺口

不是 (a)（代码链路完整：`GlowIntensity.fade` 有唯一实现，生产与预览共用），
也不只是 (c)（用户配置没开开关是事实，但代码里另有三条真缺陷）。

| # | 缺陷 | 证据 |
|---|---|---|
| B-1 | **保持期不续帧**：`fading = fade > 0 && fade < 1`，保持期 `fade == 1` → false；若此刻 `anyAnimating` 也已 false，档位落到空闲 120ms（失焦 600ms）。默认淡出 75ms **短于一个空闲帧**，淡出被整段跳过 → 看上去「直接熄灭」。 | `CaretAnimator.tickSpring` 的 `fading` 行；`computeNextDelayNs` 的 `IDLE_DELAY_MS=120`；`cursorFadeOutDuration` 默认 0.075s |
| B-2 | **残影模式完全不淡出**：`tickAfterimage` 从不计算/写入 `glowFade`，`paintGhosts` 也不用它。 | `CaretAnimator.tickAfterimage`；`CaretTrailPanel.paintGhosts` |
| B-3 | **文案不提前提**：没有说明「需要开启辉光」「只淡辉光层」「残影模式不适用」。 | `settings.*` 资源包 |

### 改法（初版 + 审查后修正）

1. 把「是否需要继续出帧」抽成
   `GlowIntensity.needsMoreFrames(overlayFadeOut, lastGlowStrength, fade, millisUntilFadeStarts, idleFrameMs)`：
   - 淡出中（0<fade<1）→ 逐帧；
   - 保持期（fade==1）→ 只在**距淡出开始不足一个空闲帧**时续帧（避免 500ms 全速空转，
     又保证淡出曲线不被跨过去）。
2. **渲染取值与调度分开**（审查 H-1）：新增
   `GlowIntensity.glowForGroup(offset, holdGlow, overlayFadeOut, groupRecentlyActive, fade)`
   （发版前审查把「全局本帧是否移动」改成「本组最近是否活动」，见 §14 与
   `docs/FINAL-AUDIT-2026-11-01.md` F-01）。
   初版把两者压进同一个 `fading` 布尔，运动期间恒为 true → 所有分组读全局强度，
   多光标「静止光标发光」串扰复发。现在 `fading` 只决定「用最后亮度还是逐组强度」，
   `needFrames` 只决定调度。
3. 残影模式的辉光淡出**不做**（另一套渲染模型，残影有自己的 lifetime 淡出）；
   在文案里写明「残影模式不适用」。
4. 文案按 §三 改。

### 验证

- `VFadeTick` 24 条：调度 8 条 + 渲染 8 条 + 源码守卫 5 条 + 曲线回归 3 条（发版前审查新增 T-16a/b/c、T-17）；
- 源码扫描（剥注释后）断言 `CaretAnimator` 调用两个纯函数、且不再出现旧判据；
- 变异测试 5 组（见 §六），每组都红、恢复后全绿。

---

## 三、文案改前 / 改后

| key | 改前 | 改后 |
|---|---|---|
| `settings.group.fade` | 淡出 | 淡出（仅辉光层） |
| `settings.row.glowFade` | 辉光淡出（停止后柔和熄灭） | 辉光淡出（停止后柔和熄灭 · 需开启「启用辉光」） |
| `settings.row.fadeHold` | 淡出前保持时长 | 淡出前保持时长（辉光维持满亮度多久） |
| `settings.row.fadeDuration` | 淡出时长 | 淡出时长（之后线性熄灭所需时间） |
| `settings.comment.fadeOff` | 淡出关闭时这两个值不生效，辉光会瞬间消失。 | 淡出关闭时这两个值不生效：辉光会立即消失。本组只淡辉光层，光标本体始终不透明；「残影」渲染模式下不适用。 |

英文（root）同步改。

---

## 四、白名单自查

```
生产：ui/settings/NeovideCursorConfigurable.java（输入框与文案）
      ui/CaretAnimator.java（B-1 的消费点）
      core/GlowIntensity.java（B-1 的纯函数——虽未列在白名单，但它是淡出链的
        唯一实现，把判据放这里才能被探针直接调用；不改任何既有曲线值）
探针：tools/settings-probe/VInputCommit.java（新）
      tools/settings-probe/VFadeTick.java（新）
      tools/settings-probe/VFadePreview.java（新，第二轮）
      ui/settings/AnimationPreview.java（第二轮：justAdvanced + 同一套淡出判据）
      tools/settings-probe/VSnap.java（TYP-2 契约变更：键入即写 pending）
      tools/settings-probe/build-v-probes.ps1（登记两个新探针）
文案：src/main/resources/messages/NeovideCursorBundle*.properties
文档：CLAUDE.md / START-HERE.md / docs/NEXT-STEPS.md（探针数）
```

> `core/GlowIntensity.java` 不在任务书的白名单里。它不是 D0 文件
> （D0 只锁 `core/NeovideConfig.java`），且是淡出曲线的唯一实现；
> 若把判据写进 `CaretAnimator`，探针只能读源码字符串、无法直接断言。
> 见报告「白名单外字节变化」一节。

---

## 五、不做的事

- 不改残影模式的渲染模型（产品决策）。
- 不改任何默认值（`VDefaultFreeze` 规则）。
- 不动 `SettingsField` / `State` / 预设域（本轮不新增字段）。
- **不在键入时派生「XX 副本」**（审查 M-1 的建议）：键入是中间态（"1"→"12"→"125"），
  逐键派生是用户没要求的副作用；`apply()` 末尾本来就会 `afterPendingEdited()`，
  最终状态一致。代价已写进 `refreshPresetDisplayForInput` 的 javadoc。

---

## 六、审查发现与修正（2026-11-01）

| 编号 | 级别 | 问题 | 处理 |
|---|---|---|---|
| H-1 | HIGH | `fading` 同时当「续帧」和「用全局强度」两个判据；运动期间恒 true → 多光标静止光标发光 | **已修**：拆成 `needFrames` + `fading`（`useLastGlow`），T-7 守门 |
| H-2 | HIGH | `VSnap` TYP-2 断言旧契约「键入不写 pending」→ 门禁红 | **已改断言方向**，注明「契约变更，非缺陷」 |
| H-3 | HIGH | `VFadeTick` 读不到源码时 T-7 假绿（fail-open） | **已修**：读不到源码直接 FAIL + 打印原因 |
| M-1 | MEDIUM | 键入不派生预设副本，与拖滑块不对称 | **保留**（见 §五 最后一条），并更新 javadoc |
| M-2 | MEDIUM | 三处 §6.1 契约注释与代码相反 | **已更新** javadoc / 注释 |
| M-3/L-1 | MEDIUM | 保持期整段 8ms 空转（最长 500ms） | **已修**：只在距淡出开始 ≤ 一个空闲帧时续帧 |
| M-4/L-3 | MEDIUM | 键入路径的界面刷新没有 try/catch | **已修**：与滑块路径同构 |
| M-5/L-2 | MEDIUM | I-5 是同义反复 | **已重写**：断言回灌后控件文本 == pending 刻度 |
| M-6 | MEDIUM | NaN 防御无断言 | **已补** T-6a/T-6b |
| M-7/L-4 | MEDIUM | NEXT-STEPS 探针行数字自相矛盾 | **已改** 33,311 行 / 131 文件 |
| L-3 | LOW | 探针自称复刻真实键入，实际一次性插入 | **已改为逐字符** |
| L-5 | LOW | 仓库根多出误建目录 | **已删** |

### 变异测试记录（独立副本 `build/mut2`，不碰工作区）

| 变异 | 期望红 | 实测 |
|---|---|---|
| M1 保持期不续帧（`return false`） | T-3b | 红 ✓ |
| M2 CaretAnimator 写回旧的单一判据 | T-12/T-13/T-14 | 红 ✓ |
| M3 `useLastGlow` 恒 true（H-1 回归） | T-7 | 红 ✓ |
| M4 `onTextEdited` 不写 pending | I-2（0/14）、I-6 | 红 ✓ |
| M5 `onTextEdited` 不刷预览 | I-6 | 红 ✓ |
| 全部恢复 | — | 20/20、7/7 全绿 ✓ |
| M6（第二轮）把预览「保持亮度」改回「最后可见亮度」 | P-2（`glowAtFadeStart=0.0154`） | 红 ✓ |
