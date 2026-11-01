# 数字输入框 + 辉光淡出 修复交付报告（2026-11-01）

> 依据：`docs/HANDOFF-NEXT-2026-11-01.md` §三 的用户原话
> 「滑块旁边的数字输入没效果，辉光用输入改1 没有效果应用也没有效果」
> 「辉光淡入淡出没有效果吗我改到最大500观感没有变化，这个文案也要改」
>
> 计划与根因推导：`plans/fix-input-and-fade.md`
> 交付版本：**v0.9.119**（第二轮修复后的最终安装版）
> 提交：代码 `4af8965`、文档 `a62afe5`（2026-11-01）
> **发版前审查修正**：2026-11-01 最终审查发现多光标串扰回归与本文两处结论错误，已在本版修正；见 §15。

---

## 一、结论（先说结果）

| 项 | 结论 |
|---|---|
| BUG-A 输入框 | **正常路径一直是通的**（键入 → apply → 落库，14/14 实测）。用户感知到的「没效果」有两个真实原因：① 键入只挪滑块/标签，**不写 pending、不刷预览**；② 辉光关闭时整组控件**禁用**，无法输入（用户配置 `useShadow=false`）。禁用控件在 apply 时仍会写入它未变的旧值——本项目没有调用 `applyIfEnabled`，`CellImpl.shouldSaveOnApply()` 恒返回 true。 |
| BUG-B 辉光淡出 | **第一轮判定 (b) 被第二轮实测推翻**。真实主因是 B-4：淡出起点取了「最后可见帧」的强度（≈0.015），且 `× fade` 被乘了两次；同时用户配置属于 (c)「前提没满足」（`useShadow=false` + `overlayFadeOut=false`）。「保持期不续帧」是真实缺陷，但不是用户看不见淡出的主因。 |
| 门禁 | `V-PROBES RESULT: ALL PASS`（116 个 `V*.java` / 131 个 `.java` / 33,311 行） |
| D0 | `core/NeovideConfig.java` 零 diff |
| 变异测试 | 6 组（5 组第一轮 + 1 组第二轮），全部按预期变红、恢复后全绿 |
| 提交 | 已提交：代码 `4af8965`、文档 `a62afe5` |

---

## 二、研究记录（先查证，不凭记忆）

### 2.1 输入框：数据流与「断点」

平台行为用 `javap` 反编译确认（`intellij.platform.ide.impl.jar`）：

- `TextFieldKt.bindIntText` → `bindText` → `CellImpl.bind(...)`
- `CellImpl.bind` 只注册三件事：`onApply`（组件 → prop）、`onReset`（prop → 组件）、
  `onIsModified`（比较）。**没有 document 监听**。
- `OnChangeManager.applyBinding` 只是在 `binding=true` 下执行一次初始化，
  不是注册监听器（第一版读字节码时曾误判，已用 `-c` 逐条确认）。

完整链路：

```
用户键入 "1"
  └─ Document 变化
       ├─ 平台 bindIntText：无监听
       └─ 我们的 DocumentListener → onTextEdited(b)
            ① 解析整数（非法直接 return）
            ② slider.setValue(snapToTick(v))      ← 滑块会动
            ③ valueLabel.setText(v)               ← 标签显示精确值
            ④ refreshDerivedHints()
            ⑤ refreshPresetDisplayForInput()      ← 只改预设下拉
            ✗ 不写 pending、不刷预览               ← 断点 1（时机）
点「应用」→ apply()
  ├─ A1 colorBindings.pushAll(pending)
  ├─ A2 super.apply()：滑块先写吸附值，输入框后写精确值（越界抛 ValidationException）
  ├─ A3..A5 逐字段 pending vs baseline → 写 State → applyConfig()
  └─ afterPendingEdited() → refreshPreviews()   ← 预览在这里才更新
```

### 2.2 淡出：消费链

```
CaretAnimator.tickSpring()
  → glowFade(now) → GlowIntensity.fade(overlayFadeOut, lastAnimatingNanos, now, delay, duration)
  → panel.setGlowFade(fade)
  → CaretTrailPanel.drawQuadGlow():  peak = glowOpacity × (glowColor.a/255) × quadGlow × glowFade
```

预览窗 `AnimationPreview.step()` 用同一份 `GlowIntensity.fade`（`physicsTimer` 8ms 常驻）。

### 2.3 用户实际配置（决定性证据）

`%APPDATA%\JetBrains\IntelliJIdea2026.1\options\NeovideCursorSettings.xml`：

```
overlayFadeOut = false      ← 淡出开关关着
useShadow      = false      ← 辉光本身关着
glowLayers     = 12
targetFps      = 144
```

在该配置下（headless 实测）：

```
useShadow=true  reset 后 glowLayers 输入框 enabled=true
useShadow=false reset 后 glowLayers 输入框 enabled=false   ← 无法输入
```

且 `GlowIntensity.fade` 在 `overlayFadeOut=false` 时恒返回 1 —— 没有淡出过程。

### 2.4 外部检索

不适用。平台 DSL 行为已用 `javap` 直接反编译确认，无需外部资料。

---

## 三、BUG-A：数字输入框

### 3.1 根因（两条，均实测）

| # | 根因 | 证据 |
|---|---|---|
| A-1 | 键入只挪滑块/标签，**不写 pending、不刷预览**；预览要等 apply 才更新 | `onTextEdited` 全文；`refreshPresetDisplayForInput` 只改下拉；`refreshPreviews` 只在 `afterPendingEdited` / `syncAllControls` 被调 |
| A-2 | 目标组禁用时输入框不可编辑，面板没有解释 | `addSlider(..., shadowOn)`；禁用控件收不到键入。**注意**：本项目未调用 `applyIfEnabled`，`CellImpl.shouldSaveOnApply()` 恒返回 true，apply 仍会写入未变的旧值——「apply 也不保存」不成立（见 §15） |

**为什么不是「链路断了」**：14 个滑块逐个「键入精确值 → apply」，
14/14 的 State 都按键入值落库（`VInputCommit` 之前的一次性诊断脚本实测）。

### 3.2 修法

`NeovideCursorConfigurable.onTextEdited()`：

```java
// 量程内：立即写 pending（越界仍不写，保持 apply 的「拒绝越界」语义）
if (v >= b.spec.min && v <= b.spec.max) {
    b.spec.writeTick.accept(pending, v);
}
try {
    refreshDerivedHints();
    refreshPresetDisplayForInput(b, v);   // 越界值时也给出正确的下拉显示
    refreshPreviews();                    // ← BUG-A 的直接修法：与滑块路径同一终点
} catch (Throwable ex) {                  // 与滑块路径 P-L-7 同构
    LOG.warn("neovide-cursor: 输入框刷新失败（pending 已写入 " + v + "）", ex);
}
```

**为什么越界仍不写**：`apply()` 走平台的 `getValidatedIntValue`，越界抛
`ValidationException` 被 catch，不写 pending（`ProbeSettingsV2` S8 的既有契约）。
键入路径用同一组 `IntRange` 边界，两边语义一致。

**为什么不改成 `refreshPresetDisplay()`（审查 M-1）**：那会在每次按键上派生/更新
「XX 副本」。键入是中间态（"1"→"12"→"125"），逐键派生用户没要求；
`apply()` 末尾本来就会 `afterPendingEdited()`，最终状态一致。代价写在
`refreshPresetDisplayForInput` 的 javadoc 里。

### 3.3 验证（红 → 绿 → 变异红 → 恢复绿）

| 阶段 | 证据 |
|---|---|
| 红 | 新探针编译失败：`previewGlowLayersForProbe` / `needsMoreFrames` 不存在 |
| 绿 | `VInputCommit` 7/7：I-1 面板建成；**I-2 键入后 pending 立即更新 14/14**；I-3 apply 后 State 14/14；I-4 越界不进 pending；I-4b apply 后非越界；I-5 reset 回灌一致；I-6 预览配置同步 |
| 变异红 | 删 `writeTick(pending, v)` → I-2 变 **0/14**、I-6 红；删 `refreshPreviews()` → I-6 红 |
| 恢复绿 | 恢复后 7/7 全绿 |

---

## 四、BUG-B：辉光淡出

### 4.1 判定

**第一轮判定 (b) 是错的，已被 §14 的实测推翻。** 真实主因是 B-4（淡出起点取错 + 双重乘 fade）；
用户当前配置同时属于 (c)「前提没满足」。§4.4 的 20/20 只能证明纯函数正确，不能证明用户可见。

不是 (a)：`GlowIntensity.fade` 有唯一实现，生产与预览共用，链路完整。

### 4.2 三条缺陷

| # | 缺陷 | 证据 |
|---|---|---|
| B-1 | **保持期不续帧**：旧判据 `fade > 0 && fade < 1`，保持期 `fade == 1` → false；光标刚停下时 `anyAnimating` 也已 false → 调度落到空闲 **120ms**。默认淡出 **75ms** 比一个空闲帧还短 → 整条曲线被跨过去，表现为「瞬间熄灭」。把保持调到 500ms 只是让熄灭晚点发生。 | `CaretAnimator.tickSpring` 旧 `fading` 行；`computeNextDelayNs` 的 `IDLE_DELAY_MS=120`；`cursorFadeOutDuration` 默认 0.075s |
| B-2 | **残影模式完全不淡出**：`tickAfterimage` 从不计算/写入 `glowFade`，`paintGhosts` 也不用它。 | `CaretAnimator.tickAfterimage`；`CaretTrailPanel.paintGhosts` |
| B-3 | **文案不提前提**：没说明「需要开启辉光」「只淡辉光层」「残影模式不适用」。 | 资源包 |

### 4.3 修法（含审查后修正）

初版只有一个布尔 `fading`，同时决定「要不要出帧」和「用最后亮度还是逐组强度」。
审查 H-1 指出：运动期间 `lastAnimatingNanos` 每帧刷新 → `fade == 1` →
新判据恒 true → **所有分组读全局强度**，多光标下「一个在动、一个静止」时
静止光标也会发光（`CaretTrailPanel` 注释里写明已修掉的串扰）。

最终拆成两个纯函数（都在 `GlowIntensity`，探针可逐段断言）：

```java
// 调度：只要还有可淡的辉光，就在「淡出中」或「保持期即将结束」时续帧
public static boolean needsMoreFrames(boolean overlayFadeOut, float lastGlowStrength,
                                      float fade, long millisUntilFadeStarts, long idleFrameMs) {
    if (!overlayFadeOut) return false;
    if (!(lastGlowStrength > 0f)) return false;   // NaN 也挡在这里
    if (!(fade > 0f)) return false;
    if (fade < 1f) return true;                   // 淡出中：逐帧
    return millisUntilFadeStarts <= idleFrameMs;  // 保持期：只在最后一帧之前续帧
}

// 逐组渲染：本组最近活动过 → 保持亮度（移动帧刚刷新，与曲线连续）；
// 从未活动 / 已过淡出窗口 → 逐组曲线（静止组为 0，多光标下不串扰）。
public static float glowForGroup(double offset, float holdGlow,
                                 boolean overlayFadeOut, boolean groupRecentlyActive, float fade) {
    if (useLastGlowForGroup(overlayFadeOut, groupRecentlyActive, fade)) {
        return holdGlow;
    }
    return strengthFor(offset);
}
```

`CaretAnimator.tickSpring()` 里：

```java
long sinceLastVisibleMs = lastAnimatingNanos == 0L ? Long.MAX_VALUE
        : Math.max(0L, (now - lastAnimatingNanos) / 1_000_000L);
long holdRemainingMs = config.cursorDisappearDelay <= sinceLastVisibleMs
        ? 0L : config.cursorDisappearDelay - sinceLastVisibleMs;
boolean needFrames = GlowIntensity.needsMoreFrames(
        config.overlayFadeOut, lastGlowStrength, fade, holdRemainingMs, IDLE_DELAY_MS);
float trailGlow = GlowIntensity.glowForGroup(offForGlow, effectiveGlow,
        config.overlayFadeOut, groupRecentlyActive[trail.groupId], fade);
...
return anyAnimating || needFrames;   // 调度不再用 fading
```

**保持期为什么不能全速续帧**（审查 M-3/L-1）：画面逐帧完全相同，
`cursorDisappearDelay` 量程到 500ms，全速续帧等于每次停手后空转最多 62 帧。
「只在距淡出开始 ≤ 一个空闲帧时续帧」既保证淡出曲线被采样，又不付这笔开销。

**B-2 不做**：残影是另一套渲染模型（残影有自己的 lifetime 淡出），
改它属于产品决策；本轮在文案里写明「不适用」。

### 4.4 验证

`VFadeTick` **24/24**（发版前审查后新增 T-16a/b/c、T-17）：

```
T-1  开关关闭 → 不续帧
T-2  从未亮过 → 不续帧
T-3  保持期还剩 400ms → 不空转
T-3b 保持期只剩 100ms → 必须续帧        ← BUG-B 的核心
T-4  淡出中 fade=0.5 → 续帧
T-5  fade=0 → 停止
T-6a lastGlowStrength=NaN → 不续帧
T-6b fade=NaN → 不续帧
T-7  本组未活动 → 不用保持亮度         ← H-1（多光标串扰）守门
T-8  本组刚活动 + 淡出中 → 用保持亮度
T-9  本组刚活动 + 保持期 → 用保持亮度
T-10a/b 开关关闭 / fade=0 → 不用最后亮度
T-11 能读到 CaretAnimator 源码
T-12/13 源码调用 needsMoreFrames / glowForGroup
T-14 源码不再出现旧的 `fade > 0f && fade < 1f`
T-16a/b/c 逐组取值：活动组保持、静止组 0、运动组用曲线
T-17 源码不再调用全局 useLastGlow
T-15a/b/c 淡出曲线回归
```

---

## 五、审查发现与修正

两位审查代理（`code-reviewer` + `java-reviewer`）各自独立跑门禁并给出报告。
**初版被判定 BLOCK，两个 HIGH 都成立**，已全部修正。

| 编号 | 级别 | 问题 | 处理 |
|---|---|---|---|
| H-1 | HIGH | `fading` 同时当「续帧」与「用全局强度」两个判据；运动期间恒 true → 多光标静止光标发光 | **已修**：拆成两个纯函数，T-7 守门 |
| H-2 | HIGH | `VSnap` TYP-2 断言旧契约「键入不写 pending」→ 门禁红 | **已改断言方向**，注明「契约变更，非缺陷」 |
| H-3 | HIGH | `VFadeTick` 读不到源码时 T-11 假绿（fail-open） | **已修**：读不到源码直接 FAIL + 打印原因 |
| M-1 | MEDIUM | 键入不派生预设副本，与拖滑块不对称 | **保留**（见 §3.2 末），并更新 javadoc |
| M-2 | MEDIUM | 三处 §6.1 契约注释与代码相反 | **已更新** javadoc / 注释 |
| M-3/L-1 | MEDIUM | 保持期整段 8ms 空转 | **已修**：只在距淡出开始 ≤ 一个空闲帧时续帧 |
| M-4/L-3 | MEDIUM | 键入路径刷新无 try/catch | **已修**：与滑块路径同构 |
| M-5/L-2 | MEDIUM | I-5 是同义反复 | **已重写**：断言回灌后控件文本 == pending 刻度 |
| M-6 | MEDIUM | NaN 防御无断言 | **已补** T-6a/T-6b |
| M-7/L-4 | MEDIUM | NEXT-STEPS 探针行数字自相矛盾 | **已改** 33,311 行 / 131 文件 |
| L-3 | LOW | 探针自称复刻真实键入，实际一次性插入 | **已改为逐字符** |
| L-5 | LOW | 仓库根多出误建目录 | **已删** |

> 备注：审查期间机器上同时有门禁进程在跑，出现过 `ClassNotFoundException` 假红
> （CLAUDE.md 四-G-2 记录的已知现象）。最终数字以收工时单独跑的一次为准。

### 变异测试记录（独立副本，不碰工作区）

| 变异 | 期望红 | 实测 |
|---|---|---|
| M1 保持期不续帧（`return false`） | T-3b | 红 ✓ |
| M2 CaretAnimator 写回旧的单一判据 | T-12/T-13/T-14 | 红 ✓ |
| M3 `useLastGlow` 恒 true（H-1 回归） | T-7 | 红 ✓ |
| M4 `onTextEdited` 不写 pending | I-2（0/14）、I-6 | 红 ✓ |
| M5 `onTextEdited` 不刷预览 | I-6 | 红 ✓ |
| 全部恢复 | — | 20/20、7/7 全绿 ✓ |

---

## 六、文案改前 / 改后

| key | 改前 | 改后 |
|---|---|---|
| `settings.group.fade` | 淡出 | 淡出（仅辉光层） |
| `settings.row.glowFade` | 辉光淡出（停止后柔和熄灭） | 辉光淡出（停止后柔和熄灭 · 需开启「启用辉光」） |
| `settings.row.fadeHold` | 淡出前保持时长 | 淡出前保持时长（辉光维持满亮度多久） |
| `settings.row.fadeDuration` | 淡出时长 | 淡出时长（之后线性熄灭所需时间） |
| `settings.comment.fadeOff` | 淡出关闭时这两个值不生效，辉光会瞬间消失。 | 淡出关闭时这两个值不生效：辉光会立即消失。本组只淡**辉光层**，光标本体始终不透明。<br/>**「残影」渲染模式下不适用**（残影有自己的淡出节奏）。 |

英文 root 同步改；`VI18n` 13/13 绿（键集合、占位符、零中文 UI 字面量均未破坏）。

---

## 七、探针与门禁

### 7.1 新增 / 修改的探针

| 探针 | 断言数 | 守什么 |
|---|---|---|
| `VInputCommit`（新） | 7 | 14 个滑块逐个「键入 → pending → apply → State」、越界、回灌、预览同步 |
| `VFadeTick`（新） | 24 | 调度判据 8 条、渲染判据 8 条、源码守卫 5 条、曲线回归 3 条 |
| `VFadePreview`（新，第二轮加） | 5 | 真实驱动预览窗逐帧采样：保持期亮度、淡出起点亮度、单调性、采样帧数、总时长 |
| `VSnap`（改） | 23 | TYP-2 契约变更（键入即写 pending），其余不动 |

### 7.2 规模（实测）

```
生产代码   37 文件 / 21,414 行
探针       116 个 V*.java / 131 个 .java / 33,311 行
```

三份状态文档（CLAUDE.md / START-HERE.md / docs/NEXT-STEPS.md）已同步，
`VDocCheck` 4/4 通过（D-1 版本 0.9.119 三处一致；D-2 探针数 116 三处一致）。

### 7.3 门禁

```
V-PROBES RESULT: ALL PASS
```

`VRegr2` 仍是已知 informational（R-5/R-6 真功能缺口，本轮不动）。
补记：该探针在 R-5/R-6 之后第 8 段 `deriveOnSmoothChange` 抛 `NullPointerException`
（`VRegr2.java:338`），其后的断言不再执行；「2 条红」不等于「只跑了这 2 条」。

---

## 八、安装（第一轮 v0.9.118；最终版见 §14.6）

```
DryRun：17 个字段备份并还原，丢弃 0；结构指纹 28（与 CLAUDE.md §7.0 一致）
安装：dist/neovide-cursor-0.9.118.jar  (245,858 B)
      jar 内 <version>0.9.118</version>
      since-build 242 ~ until-build 261.*
配置：17/17 字段回写（useShadow/overlayFadeOut/glowLayers/animCustomName 等原样）
备份：D:\deepseekwork\_config-backup\personal-state-20261101-182008.json
IDEA：安装时未运行，下次打开生效
```

### 验收步骤（必读）

你的配置里两个开关都是关的，直接测仍然「没变化」：

1. 设置 → 光标 → **「辉光」页**：勾选「启用辉光」。
2. **「辉光淡出」页**：勾选「辉光淡出」。
3. 「淡出前保持时长」= 0、「淡出时长」= 500，再反过来试一次。
4. **验输入框**：在数字框里键入（不点应用），预览窗应立即变化；点应用后编辑器生效。
5. **验淡出**：敲几下停下，辉光应先保持、再平滑熄灭，而不是突然消失。

---

## 九、文件清单与白名单

```
生产（4）
  M core/GlowIntensity.java                 +91 / -0   ← 白名单外，见 §9.1
  M ui/CaretAnimator.java                   +50 / -11
  M ui/settings/AnimationPreview.java       +21 / -6     ← 第二轮修复新增
  M ui/settings/NeovideCursorConfigurable.java  +68 / -12
文案（2）
  M src/main/resources/messages/NeovideCursorBundle.properties
  M src/main/resources/messages/NeovideCursorBundle_zh_CN.properties
探针（5）
  M tools/settings-probe/VSnap.java
  M tools/settings-probe/build-v-probes.ps1
  ?? tools/settings-probe/VInputCommit.java
  ?? tools/settings-probe/VFadeTick.java
  ?? tools/settings-probe/VFadePreview.java
文档（6）
  M CLAUDE.md / M START-HERE.md / M docs/NEXT-STEPS.md / M docs/INDEX.md
  ?? docs/HANDOFF-NEXT-2026-11-01.md
  ?? plans/fix-input-and-fade.md
  ?? docs/FIX-INPUT-FADE-REPORT-2026-11-01.md（本文件）
```

### 9.1 白名单外的唯一一处

`core/GlowIntensity.java`（+91 / -0 行）：

- 它不是 D0 文件（D0 只锁 `core/NeovideConfig.java`，本轮零 diff）。
- 它是淡出曲线的**唯一实现**；若把判据写进 `CaretAnimator`，探针只能扫源码字符串，
  无法逐段断言（`CaretAnimator` 需要真实 `Editor` 才能构造）。
- **既有 `strengthFor` / `fade` 行为零改动**，T-15a/b/c 回归守着。

`ui/CaretTrailPanel.java` **没有改** —— 它早就在乘 `glowFade`。

---

## 十、已知缺口与不做的事

1. **残影模式不参与辉光淡出**（B-2）。需要另一套计时语义，属产品决策。
2. **键入不派生「XX 副本」**（与拖滑块不对称），理由见 §3.2；apply 后状态一致。
3. **保持期不是「零成本」**：即使只在结束时续帧，最后 ≤120ms 仍是逐帧。
4. 默认值全部未动（`VDefaultFreeze` 规则）。

---

## 十一、复现命令

```bash
# 全量门禁（唯一权威入口）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr

# 只跑两个新探针（用门禁产物；库目录按本机实际路径）
java -Djava.awt.headless=true -Dneovide.repo=D:/deepseekwork/neovim-cursor-pr \
  -classpath "build/v2-probe/classes;build/v2-probe/probe;src/main/resources;D:/deepseekwork/_idea-anim/idea-libs/*.jar" \
  VInputCommit
java -Djava.awt.headless=true -Dneovide.repo=D:/deepseekwork/neovim-cursor-pr \
  -classpath "build/v2-probe/classes;build/v2-probe/probe;src/main/resources;D:/deepseekwork/_idea-anim/idea-libs/*.jar" \
  VFadeTick
```

> 注意：门禁脚本的 `-Repo` 请用**正斜杠**。用反斜杠且经 bash 传递时反斜杠会被吃掉，
> 脚本会相对路径地误建一个 `deepseekworkneovim-cursor-pr/` 目录（本轮已清理）。

---

## 十二、六个必答问题

1. **哪几跳断了？** 不是断，是时机：键入不写 pending → 预览不动；apply 那跳一直是通的。
2. **一直存在还是最近引入？** 一直存在。输入框代码来自 `cc93064`（2026-10-20），
   「只在 apply 写」是当时的契约。
3. **淡出判定？** 第一轮 (b) 被第二轮推翻，真实主因是 B-4；用户配置同时是 (c)。见 §4.1、§14。
4. **没实现为什么有开关？** 实现了，受两个开关双重门控；面板原来没写这个前提。
5. **文案改什么？** 见 §6。
6. **同类「写了没人读」字段？** 扫了 State 全部字段，`stateVersion` 只在
   `NeovideCursorSettings.java` 一个文件里出现（22 处赋值/迁移，设计如此）；
   `VDeadField` 全绿。`cursorDisappearDelay` / `cursorFadeOutDuration`
   **有消费者**，只是被开关和残影模式挡住。

---

## 十三、AC 对照

| AC | 结论 | 证据 |
|---|---|---|
| AC-001 键入后 pending 立即更新 | ✅ | `VInputCommit` I-2（14/14） |
| AC-002 应用后 State 更新并落盘 | ✅ | I-3（14/14） |
| AC-003 所有滑块行为一致 | ✅ | 14 个逐个遍历 |
| AC-004 淡出「是否实现」明确判定 + 证据 | ✅（修正后） | §4.1 + §14：第一轮 (b) 被第二轮推翻，主因 B-4 |
| AC-005 文案与真实行为一致（改前/改后） | ✅ | §6 |
| AC-006 全量门禁 ALL PASS | ✅ | §7.3 |
| AC-007 D0 零 diff | ✅ | `git diff --exit-code` |
| AC-008 改动只在白名单内 | ⚠️ 一处例外 | `core/GlowIntensity.java`，理由见 §9.1 |

---

## 十四、第二次修复（2026-11-01 晚，用户实机复测后）

### 14.1 用户反馈

> 「辉光淡出关闭和开启数值调整最大没有效果，预览窗和手打观感一致（无效），
> 但是有一次调整淡出组某一个值应用时候辉光熄灭变暗了（预览窗口动画重置时变淡出缓慢了
> 但是后续没有触发了）」

重点：**预览窗与编辑器一致**，说明两边算法同源；问题不在同步，在算法本身。

### 14.2 实测（FadeDiag：驱动预览窗逐帧采样）

设置 `overlayFadeOut=true / delay=500ms / duration=1000ms`，一次动作后逐帧采样：

```
   0ms  fade=1.000  glow=1.000
 177ms  fade=1.000  glow=0.845
 277ms  fade=1.000  glow=0.015     ← 弹簧收敛，亮度已接近 0
 812ms  fade=0.965  glow=0.015     ← 保持期结束，开始"淡出"
1049ms  fade=0.729  glow=0.011
1750ms  fade=0.027  glow=0.000
```

**结论**：`fade` 确实在按 500ms + 1000ms 变化，但「要淡的那点光」只有 **1.5%**。
旧实现把「辉光最后一次<b>可见</b>的强度」当淡出起点；而弹簧收敛的最后几帧强度
本来就接近 0（offset 刚过 0.5px 时 `strengthFor ≈ 0.015`）。
于是「从最后亮度淡出」= 从 1.5% 淡到 0 —— 代码在跑，肉眼看不见。
推测（无对应日志）：用户那一次看到「变暗」，可能是应用时预览重置、角点跳变让最后记录值恰好较大。

### 14.3 根因（B-4）

| # | 缺陷 | 证据 |
|---|---|---|
| B-4 | **淡出起点取「最后可见帧」的强度（≈0.015），不是「最后一次移动」时的强度**；且 `effectiveGlow = lastGlow × fade` 与 `panel.glowFade` 相乘两次 | FadeDiag 逐帧数据；`GlowIntensity.strengthFor` 的 OFFSET_START=0.5 |

### 14.4 修法

1. `lastGlowStrength` / 计时起点改为「最后一次**移动且辉光可见**（`moved && glowStrength > 0f`）时的强度」。
2. 停止移动后不再让弹簧的自然衰减先吃掉亮度：`glowForGroup(...)`（逐组；内部
   `useLastGlowForGroup(overlayFadeOut, groupRecentlyActive, fade)`）
   —— 本组最近活动过 → 保持亮度（移动帧刚刷新，与曲线连续）；
   从未活动 / 已过窗口 → 逐组曲线（静止组为 0）。
   2026-11-01 发版前审查把「全局 moved」改成「本组最近活动」，修多光标串扰回归。
3. 淡出只乘一次：`quadGlow = 保持亮度`，由 `panel.glowFade` 做线性淡出，
   去掉 `effectiveGlow` 里重复的 `× fade`。
4. 预览窗同步：`advance()` 置 `justAdvanced`，`step()` 消费 —— 与生产 `moved` 同义。

修复后同一实测：

```
   0ms  fade=1.000  glow=1.000
 343ms  fade=1.000  glow=1.000     ← 保持期：满亮度
 478ms  fade=0.957  glow=1.000     ← 开始线性淡出
 812ms  fade=0.623  glow=1.000
1317ms  fade=0.118  glow=1.000
```

### 14.5 新探针与变异

`VFadePreview`（**5 条**，真实驱动预览窗逐帧采样）：

```
P-1 保持期亮度 ≥0.9（不是从 1.5% 淡出）
P-2 淡出开始时 glow ≥0.9          ← 核心
P-3 淡出单调下降（不反弹）
P-4 淡出至少被采样 5 帧（不是一步跳 0）
P-5 最终归零且总时长与 delay+duration 同量级
```

变异（把 `if (moved && strength > 0f)` 改回 `if (strength > 0f)`）：
**P-2 红，`glowAtFadeStart=0.0154`** —— 与用户看到的现象逐位一致；恢复后 5/5 绿。

### 14.6 规模更新

```
探针  116 个 V*.java / 131 个 .java / 33,311 行
生产  37 文件 / 21,414 行
门禁  V-PROBES RESULT: ALL PASS
安装  v0.9.119（2026-11-01 晚重新打包；装前 IDEA 正在运行，安装脚本已自动关闭）
```

---

## 十五、发版前审查修正（2026-11-01）

`docs/FINAL-AUDIT-2026-11-01.md` 的三个代理审查发现以下问题，本节记录修正：

1. **多光标串扰回归（HIGH，代码）**：`useLastGlow(overlayFadeOut, moved, fade)` 用全局
   `moved` 判断是否用保持亮度。一次按键后弹簧还要推进十几帧，这些帧 `moved=false`，
   所有分组都读到全局 `lastGlowStrength` —— 没动过的光标也满亮度发光。
   修法：`SpringTrail.lastActiveNanos` 记录逐光标活动时刻，`CaretAnimator` 按组聚合，
   改用 `GlowIntensity.glowForGroup(...)`；`AnimationPreview` 同步。
   `VFadeTick` 新增 T-16a/b/c 与 T-17（共 24 条），变异测试把逐组判据改回恒 true
   → T-7 / T-16b 红，恢复后 24/24 绿。
2. **第一轮判定未认错（HIGH，文档）**：§1、§4.1、§12 Q3、AC-004 已改为
   「第一轮 (b) 被第二轮推翻，主因是 B-4」。
3. **A-2 平台证据错误（HIGH，文档）**：`CellImpl.shouldSaveOnApply()` 在本项目恒返回
   true（未调用 `applyIfEnabled`）；「apply 也不保存」已删除。
4. **数字与计数**：§7.1 的 VFadeTick 20→24；§9 清单计数与漏写的 HANDOFF；
   §4.4 的 T-7/T-11 编号；M-7 的 33,311/131。
5. **文档同步**：`NEXT-STEPS` / `HANDOFF` / `plans/fix-input-and-fade.md` /
   `CLAUDE.md §7.0` 的过期数字已更新。

验证：生产代码 `javac --release 21` 通过；`VFadeTick` 24/24；`VFadePreview` 5/5；
变异（逐组判据改回恒 true）T-7 / T-16b 红，恢复后全绿。
修复后**全量门禁已重跑**：113 个注册探针 / **1292 PASS / 2 FAIL（仅 VRegr2 informational）/ ALL PASS**。
