# 发版前最终审查（2026-11-01）

> **后续状态（2026-11-01）**：§零 的 P0 已全部处理 —— 多光标串扰已修（逐组判据 + `VFadeTick` 24 条）、交付报告两处 HIGH 已更正、状态文档数字已统一，并已打包安装 **v0.9.120**。修复记录见 §十。§零~§九 保留审查当时的事实与定级，不再改写。

> 审查对象：`main` @ `a62afe5`（已推 fork，`git rev-list --left-right --count fork/main...HEAD` = `0 0`）
> 审查范围：今日代码提交 `4af8965`、文档提交 `a62afe5`、`tools/settings-probe/V*.java` 全体系
> 审查方式：3 个代理并行（文档复核 / 代码质量 / 探针体系）+ 调度者独立复核
> 本文件是本次审查**唯一新建**的内容；未改动任何生产代码、探针或既有文档。

---

## 零、结论

**能不能发版：有条件能。**

当前已安装的 `0.9.119` **确实包含本次修复**（不是「装了旧代码」）：

```
dist/neovide-cursor-0.9.119.jar        2026-11-01 11:07:48
所有改动源文件 mtime                   ≤ 11:02:05
jar 内 com/github/.../core/GlowIntensity.class   存在
已装 plugin.xml <version>0.9.119
```

门禁基线也用现有产物复核过：**113 个已注册探针 / 1288 PASS / 2 FAIL（仅 VRegr2 的 R-5、R-6）**，与提交信息一致；D0 门禁 `core/NeovideConfig.java` 零 diff（`git diff --exit-code` 退出码 0）。

但**不是无条件可发**。发版前必须处理的条件（P0）：

| # | 条件 | 依据 |
|---|---|---|
| P0-1 | **多光标「静止分组」在淡出期间被按满亮度绘制**：修掉，或由用户明确接受该观感 | 代码审查 HIGH，调度者已逐行复核（见 §二 #1） |
| P0-2 | **交付报告两处 HIGH 必须修正**：第一轮结论被第二轮推翻却未认错；`shouldSaveOnApply()` 的平台证据与字节码相反 | 文档复核 HIGH，调度者已用 `javap` 复核（见 §一 H-1、H-2） |
| P0-3 | **状态文档数字自相矛盾**：`NEXT-STEPS` 仍写 97 个探针、`HANDOFF` 写 112 个、`plans/` 写 33,111 行 / 130 文件 | 调度者直接核对文件（见 §一 M-3、L-2） |

P0-1 是**运行时行为**问题，P0-2 / P0-3 是**验收依据**问题。代码本身没有崩溃、数据丢失、EDT 违规或监听器泄漏；若用户接受 P0-1 的观感，且 P0-2 / P0-3 在发版说明里修正，则可以发。

> 说明：本次**没有重跑门禁**（后台执行被权限分类器拒绝，见 §九）。上述 1288 / 2 来自 `build/v2-probe/*.txt` 里 11:42–11:44 那次完整运行的产物，只统计 `$mains` 里的 113 个探针；`baseline.txt`、`red-VLeadingSnap.txt`、`gate-final*.txt` 等历史产物已排除。

---

## 一、文档复核（代理 1）

核对对象：`docs/FIX-INPUT-FADE-REPORT-2026-11-01.md`（506 行）
对应提交：`4af8965`（代码）+ `a62afe5`（文档）

**统计：一致 38 条 / 夸大 5 条 / 漏写 5 条 / 错误 11 条。**

### 1.1 HIGH

#### H-1　BUG-B 第一轮判定被第二轮推翻，报告未明确认错（夸大）

- **报告位置**
  - 第 17 行：「BUG-B 辉光淡出 | **判定 (b) 功能实现了，但参数在特定配置下不起作用**；同时用户当前配置属于 (c)…代码里有两条真缺陷：保持期不续帧、残影模式完全不淡出。」
  - 第 148 行（§4.1）：「**(b) 功能实现了，但参数在特定配置下不起作用**」
  - 第 406 行（§12 Q3）：「淡出判定？(b)，用户配置同时是 (c)。」
  - 第 422 行（AC-004）：「淡出『是否实现』明确判定 + 证据 | ✅ | §4」
  - 第二轮修复与 B-4 只出现在第 430–463 行（§14）。
- **实际**：第一轮的 `needsMoreFrames` 确实存在且真实（`GlowIntensity.java:149`），但它没有解决用户看到的现象。第二轮找到 B-4：旧实现把「辉光最后一次**可见**时的强度」当淡出起点，`strengthFor(0.53) ≈ 0.015`，等于从 1.5% 淡到 0。最终代码改成「最后一次移动」（`CaretAnimator.java:2810`、`AnimationPreview.java:806`）。
- **判定**：夸大 —— 第一轮结论被推翻，但 §1、§4.1、§12 Q3、AC-004 仍把它当最终结论；全文检索「第一轮 / 推翻 / 判断错 / 更正」，只在 §八 出现过一次「第一轮」字样，**没有一句认错**。
- **严重度**：HIGH

#### H-2　A-2 的平台证据与字节码相反（错误）

- **报告位置**：第 101 行「`CellImpl.shouldSaveOnApply()` 对禁用组件返回 false」
- **实际证据**
  - 全仓库 `applyIfEnabled` 出现 **0 次**（`src/main/java` 与 `tools/settings-probe` 都是 0）。
  - `javap -p -c` 反编译 `intellij.platform.ide.impl.jar` 的 `CellImpl.shouldSaveOnApply()`：
    ```
    getfield applyIfEnabled
    ifeq 17            // applyIfEnabled == false → iconst_1（返回 true）
    getfield viewComponent
    invokevirtual JComponent.isEnabled()
    ifeq 21            // 只有 applyIfEnabled==true 且组件禁用 → iconst_0
    ```
  - 本项目只调用 `enabledIf(...)`（`NeovideCursorConfigurable.java:1910-1913`），它不会设置 `applyIfEnabled`。
- **结论**：在本项目里 `shouldSaveOnApply()` **永远返回 true**；禁用控件在 apply 时仍会执行 onApply。「apply 也不保存」不成立，真正成立的只有「禁用控件收不到键入」。
- **严重度**：HIGH（不影响 A-1 修法本身，但验收报告的关键根因证据是错的）

### 1.2 MEDIUM

#### M-1　§4.3 的 `useLastGlow` 代码与提交代码相反（错误）

- **报告**（第 181–197 行）：
  ```java
  public static boolean useLastGlow(boolean overlayFadeOut, float currentGlowStrength, float fade) {
      ...
      return !(currentGlowStrength > 0f);
  }
  boolean fading = GlowIntensity.useLastGlow(config.overlayFadeOut, glowStrength, fade);
  ```
- **实际**：`GlowIntensity.java:200` 是 `useLastGlow(boolean overlayFadeOut, boolean moved, float fade)`，第 209 行 `return !moved;`；调用点 `CaretAnimator.java:2858` 传的是 `moved`。正确版本只在 §14.4 出现。
- **判定**：错误（§4.3 未标注已被取代，照抄会实现错误判据）。

#### M-2　「6 组变异测试」只有第 6 组有可核对产物（夸大）

- **报告**：第 20、257–266、496–497 行称「6 组……全部按预期变红、恢复后全绿」。
- **实际**
  - 第 6 组有产物：`build/mut3/.../AnimationPreview.java` 与生产文件唯一差异正是 `if (moved && strength > 0f)` → `if (strength > 0f)`，与 §14.5 描述逐字一致。
  - M1–M5 在 `build/` 下**找不到对应产物**；`build/v2-probe/m1..m7` 的 mtime 是 10-31 11:14–11:20，属于上一轮。报告也没给可复制的命令或输出文件。
  - 期望红名单不完整：M3 还会让 T-10a/T-10b 红；M4 还会让 `VSnap` TYP-2 红。
  - 终态绿可核对：`VInputCommit.txt` 7/0、`VFadeTick.txt` 20/0、`VFadePreview.txt` 5/0，均为 11:44 的实际输出。
- **判定**：夸大（「全部按预期变红」中 5/6 不可复现）。

#### M-3　「三份状态文档已同步」过度陈述（夸大）

- **报告**：第 302–303 行「三份状态文档……已同步，`VDocCheck` 4/4 通过」。
- **实际**：状态块确实同步；但 `docs/NEXT-STEPS.md:357-358` 仍写着「**97 个** `V*.java`」「已同步为 0.9.104 / 97 个探针」，与同一文件头部的 116 / 0.9.119 自相矛盾。`VDocCheck` 只解析状态块，不扫这一段，所以绿灯掩盖了它。
- **判定**：夸大。

#### M-4　A-2 的「禁用控件无法输入」没有代码修复，也未列入已知缺口（漏写）

- **实际**：提交没有改 `addSlider` / `enabledIf` / `shadowOn` 的任何逻辑。`useShadow=false` 时三个辉光滑块与输入框仍是禁用状态，`onTextEdited` 的新逻辑不会执行。唯一补救是文案（`settings.row.glowFade` 加「需开启『启用辉光』」）。
- **判定**：漏写（§10 已知缺口未列这一条）。

### 1.3 LOW

| # | 报告位置 | 它说的 | 实际 | 判定 |
|---|---|---|---|---|
| L-1 | 第 243 行 | H-3 导致 **T-7** 假绿 | 源码读取守卫是 **T-11**（`VFadeTick.java:103/105`），T-7 是「本帧在移动」 | 错误 |
| L-2 | 第 250 行 | 已改 **33,111 行 / 130 文件** | `NEXT-STEPS.md:27` 是 **33,311 / 131**；报告 §7.2/§14.6 自己也写 33,311/131 | 错误 |
| L-3 | 第 291 行 | 渲染判据 **4** 条 | T-7、T-8、T-9、T-10a、T-10b 共 **5** 条；8+5+4+3=20 才自洽 | 错误 |
| L-4 | 第 342–359 行 | 生产（3）/ 探针（3）/ 文档（5） | 分别实际列了 4 / 5 / 6 项 | 错误 |
| L-5 | 第 356–359 行 | 文档清单 | 漏写 `docs/HANDOFF-NEXT-2026-11-01.md`（a62afe5 新增 121 行） | 漏写 |
| L-6 | 第 390–393 行 | 复现命令可跑「两个新探针」 | `<idea-libs/*.jar>` 是字面占位符；命令只跑 `VInputCommit` 一个 | 错误 |
| L-7 | 第 409–411 行 | `stateVersion` 只在一处出现 | `grep -rn stateVersion src/main/java` 共 **22 处**，都在 `NeovideCursorSettings.java` | 错误 |
| L-8 | 第 8、21 行 | 「未提交仓库」「提交：未提交」 | 代码已提交 `4af8965`，报告已提交 `a62afe5` | 错误（元数据自相矛盾） |
| L-9 | 第 130–131 行 | 代价写在 `refreshPresetDisplayForInput` 的 javadoc | 实际写在 `onTextEdited` 方法内的行注释（`NeovideCursorConfigurable.java:2071` 附近） | 错误 |
| L-10 | 第 223 行 | T-8「用最后亮度 × fade」 | 现在是「用保持亮度」，fade 由 `panel.glowFade` 单独乘一次 | 错误（描述过时） |
| L-11 | 第 311 行 | VRegr2 只有 R-5/R-6 | `build/v2-probe/VRegr2.txt` 还有 `deriveOnSmoothChange` 的 **NullPointerException**（`VRegr2.java:338`），该段之后未执行 | 漏写 |
| L-12 | 第 467 行 | 淡出起点改为「最后一次**移动**时的强度」 | 实际是 `moved && glowStrength > 0f`，微移（offset < 0.5px）不更新起点 | 漏写 |
| L-13 | 第 17 行 | B-2 被列为「两条真缺陷」之一 | §4.3 与 §10 都写明 B-2 **不做**，§1 没标「其中一条本轮未修」 | 漏写/歧义 |
| L-14 | 第 457 行 | 「用户看到变暗，是应用时预览重置……」 | 合理推断，但没有日志/复现，未标「推测」 | 夸大 |

### 1.4 一致条目（38 条）

> 以下逐条用 `git show` / 当前源码 / `javap` / 11:44 门禁产物核对，均一致。行号为报告行号。

| # | 报告位置 | 声称 | 证据 |
|---|---|---|---|
| C1 | §9 342–355 | 生产 4 文件 +91/−0、+50/−11、+21/−6、+68/−12；VSnap 12 处、build-v-probes 16 处 | `git show 4af8965 --numstat` 一致（91/0、50/11、21/6、68/12、10/2、15/1） |
| C2 | §9.1 364–369 | GlowIntensity 非 D0 文件；既有 `strengthFor`/`fade` 零改动 | diff 只在第 117 行后追加；`core/NeovideConfig.java` 零 diff |
| C3 | §4.3 188–199 | `tickSpring(dt, now, moved)`、`needFrames`、`return anyAnimating || needFrames` | `CaretAnimator.java:1536/2855/2975` 一致 |
| C4 | §14.4 470–471 | 去掉重复的 `× fade`，只由 `panel.glowFade` 乘一次 | `CaretAnimator.java:2815`；`CaretTrailPanel.java:1000-1001` |
| C5 | §14.4 472 | 预览 `advance()` 置 `justAdvanced`、`step()` 消费 | `AnimationPreview.java:585/802-813` |
| C6 | §3.2 110–122 | `onTextEdited` 量程内写 pending、`refreshPreviews()`、try/catch | `NeovideCursorConfigurable.java:2055-2077` |
| C7 | §6 272–280 | 5 个 key 中英文改动 | 两个 `.properties` diff 逐字一致 |
| C8 | §7.1 293 | VSnap TYP-2 改为「键入即写」 | `VSnap.java:169-171`；`VSnap.txt` TYP-2 PASS pending=333 |
| C9 | §7.1/§11 | build-v-probes 登记三只新探针 | `$mains` 新增 3 项，数组闭合（113 项） |
| C10 | §7.1 290 | VInputCommit 7 条 | `VInputCommit.txt` PASS 7 / FAIL 0 |
| C11 | §7.1 291 | VFadeTick 20 条 | `VFadeTick.txt` PASS 20 / FAIL 0 |
| C12 | §7.1 292 | VFadePreview 5 条 | `VFadePreview.txt` PASS 5 / FAIL 0 |
| C13 | §7.1 293 | VSnap 23 条 | `VSnap.txt` PASS 23 / FAIL 0 |
| C14 | §6 280 | VI18n 13/13 | `VI18n.txt` PASS 13 / FAIL 0 |
| C15 | §7.2 303 | VDocCheck 4/4，D-1=0.9.119、D-2=116 | `VDocCheck.txt` 一致 |
| C16 | §7.3 308 | V-PROBES RESULT: ALL PASS | 113 个注册探针合计 1288 PASS / 2 FAIL（仅 VRegr2，informational） |
| C17 | §1 19 / AC-007 | D0 零 diff | `git diff` 为空（退出码 0） |
| C18 | §7.2 298 | 生产 37 文件 / 21,414 行 | `find` + `wc -l` 实测一致 |
| C19 | §7.2 299 | 探针 116 V*.java / 131 .java / 33,311 行 | 实测一致 |
| C20 | §8 318 | 17 字段备份、丢弃 0、结构指纹 28 | `personal-state-20261101-182008.json` 与 install.ps1 正则一致 |
| C21 | §8 319–321 | 0.9.118 jar 245,858 B、version 0.9.118 | `unzip -p` 一致 |
| C22 | §14.6 505 | 最终装 0.9.119 | dist jar 存在；已装 plugin.xml 0.9.119 |
| C23 | §14.2 444–451 | FadeDiag 逐帧数据（delay=500、duration=1000） | `build/diag/FadeDiag.java:14-15` 一致，数字与公式自洽 |
| C24 | §4.2 156 | B-1：旧判据漏掉保持期；75ms < 120ms | `IDLE_DELAY_MS=120`；`cursorFadeOutDuration=0.075f` |
| C25 | §4.2 157 | B-2：`tickAfterimage` 不写 `glowFade` | `tickSpring` 只有一处 `setGlowFade`；`paintGhosts` 不引用 |
| C26 | §4.2 158 | B-3：改前文案没有前提 | 资源包 diff 的 `-` 行确实没有 |
| C27 | §2.1/§3.1 | A-1：旧 `onTextEdited` 不写 pending、不刷预览 | 旧实现只调 `refreshDerivedHints` + `refreshPresetDisplayForInput` |
| C28 | §3.2 124–126 | 越界由平台 `getValidatedIntValue` 拒绝 | `javap` 确认 `validateIntInRange` 抛 `ValidationException` |
| C29 | §2.1 29–35 | `bind` 只注册 onApply/onReset/onIsModified，无文档监听 | `javap -c CellImpl.bind` 确认 |
| C30 | §2.2 63 | `peak = glowOpacity × (a/255) × quadGlow × glowFade` | `CaretTrailPanel.java:1000-1001` 逐字一致 |
| C31 | §2.2 66 | 预览 `physicsTimer` 8ms 常驻 | `AnimationPreview.java:106/385` |
| C32 | §4.2 156 | 默认保持 50ms、淡出 75ms、空闲 120ms | `NeovideCursorSettings.java:1000/1003`；`CaretAnimator.java:149` |
| C33 | §9.1 371 | `CaretTrailPanel.java` 本次未改，早已乘 `glowFade` | `4af8965` 不含该文件 |
| C34 | §14.5 496 | 变异 `moved && strength > 0f` → `strength > 0f` | `build/mut3` 与生产文件唯一差异正是该行 |
| C35 | §12 Q2 404 | 输入框代码来自 `cc93064`（2026-10-20） | `git show cc93064` 一致 |
| C36 | §12 Q6 410 | VDeadField 全绿、两个淡出字段有消费者 | `VDeadField.txt` 4/0；`CaretAnimator.java:2853/2991` |
| C37 | §2.3 72–77 | 用户配置 `overlayFadeOut=false`、`useShadow=false`、`glowLayers=12`、`targetFps=144` | 备份 JSON 四项逐字一致 |
| C38 | §4.3 206–207 / §10 377 | B-2 不做，残影有自己的淡出 | 报告明写「B-2 不做」 |

---

## 二、代码质量（代理 2）

范围：`4af8965` 改过的生产代码。结论先行：**无 CRITICAL；1 条 HIGH、3 条 MEDIUM、5 条 LOW。**

### 2.1 HIGH

#### #1　多光标「静止分组」在淡出期间被按满亮度绘制

- **位置**：`CaretAnimator.java:2858`、`GlowIntensity.java:200-210`、`CaretAnimator.java:2916`、`CaretTrailPanel.java:993-997`
- **代码**
  ```java
  boolean fading = GlowIntensity.useLastGlow(config.overlayFadeOut, moved, fade);
  ...
  float trailGlow = glowStrengthForValue(offForGlow, effectiveGlow, fading);
  // glowStrengthForValue: if (fading) return effectiveGlow;   ← 全局值，与 offForGlow 无关
  ```
- **为什么是问题**（调度者已逐行复核）
  - `moved` 是 `refreshTarget()` 的返回值，只在「光标目标位置发生变化」的那一帧为 true（`CaretAnimator.java:1471`；返回 `changed`，见 `:2500`）。
  - 一次按键后弹簧还要推进约 15–20 帧，这些帧 `moved == false`，于是 `fading == true`，**所有分组**都读全局 `lastGlowStrength`。
  - `lastGlowStrength` 是**单个全局字段**（`CaretAnimator.java:610`），由全局 `maxOffset` 算出的 `glowStrength` 更新（`:2783`、`:2810`）。
  - 旧代码的 `lastAnimatingNanos` 在 `glowStrength > 0` 的每一帧刷新，淡出起点大约只有 0.015，肉眼不可见；新代码是满亮度。默认 `multiCursorNoGlow = false`（`NeovideCursorSettings.java:896`），不会被掩盖。
- **失败场景**：多光标放在第 1–2 行（同组）和第 5 行（另一组）→ 只把其中一个光标移走 → 第 5 行那个**完全没动**的光标会在接下来约 125ms 里顶着一圈满强度辉光。`CaretAnimator.java:2842-2845` 与 `CaretTrailPanel.java:993-997` 都写着「多光标下静止光标不应发光」，本次保护只覆盖了目标变化的 1 帧。
- **修复方向**：把 `moved` 换成「本帧该组是否有偏移」的逐组判据；或在 `fading` 为 true 时仍对 `groupMaxOffset == 0` 的分组返回 0。第二种会把「全局淡出」改成「逐组淡出」，观感需用户确认。
- **严重度**：HIGH

### 2.2 MEDIUM

#### #2　淡出计时起点从「拖尾收敛」改成「最后一次移动」，长动画下辉光提前熄灭

- **位置**：`CaretAnimator.java:2809-2815`、`GlowIntensity.java:110-118`
- **失败场景**：把「跨行动画时长」拉到 0.35s（合法上限）→ 按回车 → 拖尾要 ~536ms 才跌破辉光阈值 0.5px，但辉光在 50ms 保持 + 75ms 淡出后、即 **125ms** 就消失，剩下 400ms 拖尾无光晕。默认参数下旧 160ms vs 新 125ms，差 35ms；拉满时差 400ms。
- **性质**：取决于用户对「停止」的定义（停止按键 vs 停止运动）。**这是产品决策，不是自明的 bug。**

#### #3　键入一个字符就刷新预览，预览光标被瞬移回起点

- **位置**：`NeovideCursorConfigurable.java:2074` → `AnimationPreview.java:370-373` → `:560-570`
- **失败场景**：打开设置 → 预览正在打字/停顿（`HOLD_AFTER_TYPE_MS = 2000`）→ 在任意数字输入框里键入 "120" → 三次 `onConfigChanged()`，每次都 `placed = false`；下一次 `paintComponent` 调 `placeIfNeeded()`，把 `centerX` 重置到最左起点并 snap 四角。用户看到「预览坏了」，而本次修复的目的恰恰是让键入能看见预览变化。这是既有机制（拖滑块也会），但本次把它从「拖一次触发一次」提高到「键入一个字符触发一次」。
- **严重度**：MEDIUM

#### #4　「保持期还剩多久」的算术在两处各写一遍

- **位置**：`CaretAnimator.java:2850-2857` vs `GlowIntensity.fade()`（`:110-114`）
- **失败场景**：以后有人改 `cursorDisappearDelay` 的单位或给 `fade()` 加 clamp，只改一边 → `needsMoreFrames` 认为还在保持期而 `fade` 已进入淡出（或反之）→ 淡出又被一个空闲帧跨过去，而 `VFadeTick` 全绿（它只单独测两个纯函数的输入输出）。
- **严重度**：MEDIUM（本项目陷阱 7「同一语义两处维护」）

### 2.3 LOW

| # | 位置 | 问题 | 失败场景 |
|---|---|---|---|
| #5 | `CaretAnimator.java:2855-2857` | `needsMoreFrames` 传的是 `IDLE_DELAY_MS`，但失焦空闲档实际是 600ms（`:1606`） | `alwaysHighFps=false` + 打完字切窗口 → 保持期判定以为下一帧 120ms，实际 600ms → 满亮度辉光停留最多 600ms 后一步消失。旧代码同形态，不算回归 |
| #6 | `NeovideCursorConfigurable.java:2061-2077` | `catch (Throwable)` 会吞 `VirtualMachineError` | 预览刷新中 OOM/StackOverflow 只打 warn，面板停半更新态、进程带病运行；同文件 `apply()` 明确要求致命错误让平台看见 |
| #7 | `NeovideCursorConfigurable.java:2031-2039/2055-2057` | 越界输入时标签、滑块、pending 三值同屏不同，且无反馈 | 范围 0..500 键入 "999" → 标签 999、滑块 500、pending 停在合法前缀 99、预览 99；失焦后收敛到 500，但用户看不到「被拒绝」 |
| #8 | `GlowIntensity.java:143`、`AnimationPreview.java:176/188`、`NeovideCursorConfigurable.java:2011-2012` | 注释仍写「最后一次**可见**」，实际是「最后一次**移动**」；「键入写一次」实际一次编辑会触发 `removeUpdate` + `insertUpdate` 两次 | 本项目把注释当契约用（T-14 扫源码字符串），漂移会误导下一个人 |
| #9 | `AnimationPreview.java:775` vs `:802-803`；`NeovideCursorConfigurable.java:1861-1865/1872` | `justAdvanced` 在早退之后才消费；`b.valueLabel` 在监听器注册之后才赋值 | 当前安全；一旦有人把赋值插到中间就是 NPE |

### 2.4 核对干净的部分（有证据）

- **监听器泄漏**：未发现。`createPanel()` 每次新建 `JBTextField`（新 `Document`），`bindings.clear()` + `disposeUIResources()` 两次清理，没有跨面板共享的 `Document`。
- **重复提交**：没有功能性问题。`bindIntText` 的 setter 只由 `CellImpl.bind` 的 `onApply` 调用（`javap` 实证），文档变更不会触发；「键入写 pending」只有 `onTextEdited` 一条。
- **空值/负数/越界/非数字**：`-?\d+` 挡住 `"1e3"`、拼音、空串、`"-"`；溢出被 `NumberFormatException` 捕获；越界不写 pending；失焦 `normalizeTextField` clamp。
- **EDT**：DocumentListener / FocusListener / Timer 全在 EDT；未新增 `invokeLater` / read-action。
- **每帧开销**：`tickSpring()` 新增只有两次静态纯函数调用和几个 long 运算，**无 new、无字符串拼接、无日志、无反射**；`AnimationPreview.step()` 同样无新分配。
- **NaN**：`fade` / `lastGlowStrength` 为 NaN 时两个函数都用 `!(x > 0f)` 挡住，不会永久续帧。
- **预览同源**：`AnimationPreview.step()` 与 `CaretAnimator.tickSpring()` 现在调同一对 `GlowIntensity.useLastGlow` / `fade` / `strengthFor`，参数语义逐条对齐。
- **资源包**：两个文件**各 165 个 key，无重复，集合完全一致**（调度者用 `diff` 复核，退出码 0）。生产代码 137 个 `message("...")` 字面量全部存在；9 个「看起来没引用」的是由代码拼装的预设 key。

---

## 三、探针体系（代理 3）

**结论：116 个 `V*.java` 不等于 116 个「能失败的真探针」。真实构成是：113 个注册 / 9 个零断言 / 104 个有断言 / 3 个 informational 不计门 / 1 个（`VHandFeel`）有断言但无法让门禁失败。**

### 3.1 空转探针

**9 个已注册探针零断言，全部无条件 `exit=0`，却被计入「通过」：**

| 探针 | 行数 | 首次提交 | 实际内容 |
|---|---|---|---|
| `VVerify.java` | 55 | 2026-10-22 | 只 println「P-9a 已修好/仍不一致」，负面结论不产生 FAIL |
| `VCursorWidth.java` | 53 | 2026-10-24 | 反射打印 `getLineCursorWidth` 语义 |
| `VStretchDiag.java` | 22 | 2026-10-25 | 打印三个预设的 trailFactors |
| `VYourConfig.java` | 31 | 2026-10-24 | 打印不同 k/smooth 的 trailFactors |
| `V0Create.java` | 13 | 2026-10-20 | 只打印 `createComponent OK` |
| `V0Tree.java` | 68 | 2026-10-20 | dump 组件树，无断言 |
| `VPreviewSize.java` | 59 | 2026-10-24 | 打印 DPI/尺寸/字号 |
| `VSepDiag.java` | 59 | 2026-10-22 | 只有 println + `System.exit(0)` |
| `VSepUi.java` | 37 | 2026-10-22 | 只有 println + `System.exit(0)` |

调度者复核：前 7 个文件 `grep -cE 'check\(|AssertionError|throw new|System\.exit'` = 0；`VSepDiag` / `VSepUi` 各只有一次 `System.exit(0)`，无断言。

另有 **10 处恒真/叙述性断言**：
`VDeadField.java:34`、`VFacadeEquiv.java:60`、`VFadeTick.java:105`（有 else 分支，可接受）、`VGrad.java:85`、`VI18n.java:98/99`、`VOrder.java:131`、`VPreset9.java:564`、`VPreset9.java:75`（`allSmoothZero()` 内部 `if (false)` 恒返回 true）、`VSep.java:201`。

### 3.2 假通过风险（探针复刻生产逻辑）

**4 个探针各自实现了 rank 算法副本；其中 `VRank` / `VRankCollapse` 的断言只作用于自己的副本。**

| 文件 | 副本位置 | 断言是否调用生产 |
|---|---|---|
| `VRank.java` | `ranksAfterJump()`（`:130`） | **否**。复刻的是**修复前**的「严格更小计数」旧算法，断言「横向只有 {0,2}」实际在锁旧行为 |
| `VRankCollapse.java` | `ranksOf()`（`:53`） | **否**。5 个场景 + 1 条确定性断言全部作用于副本 |
| `VPerf.java` | `horizontalRanks()`（`:510`）、内联 rank（`:581`） | **否**。用于滞后仿真 |
| `VPerf2.java` | `ranksFor()`（`:341`） | **否**。同上 |

调度者复核：`VRank.java` / `VRankCollapse.java` 全文无 `SpringTrail` / `assignRanks` 引用，与代理结论一致。

正确写法（整改模板）：`VPreviewRank.java`（反射驱动生产 `SpringTrail`）、`VFieldDomain.java:90`、`VPreset.java:423`（直接调 `CursorPresetApplier.matchAnimPreset`）、`VBlockStretch.java:81`（反射读生产 `DampingSpring`）。

**混合源码风险**：以下 7 个探针用裸相对路径 `src/main/java/...`，**不读 `-Dneovide.repo`**：

```
VDeadField.java:39      VLoadState.java:42        VPreviewBranch.java:88
VQuadProbeTruth.java:52 VReviewFixes.java:200     VSmoothGlobal.java:28
VCursorSize.java:77
```

调度者逐个 `grep neovide.repo` 复核：这 7 个文件全部 **没有** 该属性；而 `VFadeTick` / `VDocCheck` / `VI18n` / `VLeadingSnap` / `VBuildDecl` / `VBatch3Tail` / `VAuditBatch1` / `VAuditBatch2Round2to4` / `VAuditBatch2Round5` 全部**有**。门禁脚本没有设置 java 进程的工作目录，只传 `-Dneovide.repo`；在 worktree 场景下可能「编译 worktree、检查主仓库」→ 假绿。

### 3.3 契约漂移

**当前无漂移，但任务书/`CLAUDE.md §7.0` 里的期望值已经过期。**

| 断言 | 位置 | 写死值 | 实测值 | 一致 |
|---|---|---|---|---|
| 滑块 / 复选框 / 下拉 | `VAddSetting.java:120` | 14 / 10 / 4 | 14 / 10 / 4 | ✅ |
| 同上 | `VerifyPanel.java:98/106/110` | 14 / 10 / 4 | 14 / 10 / 4 | ✅ |
| State public 实例字段 | `VPreset.java:389-390` | 34 | 34 | ✅ |
| GOLDEN 金表 | `VDefaultFreeze.java` | 34 条 | 34 字段 | ✅ |
| `SettingsField.build()` 工厂条数 | `CLAUDE.md §7.0` / `install.ps1` | 28 | 28 | ✅ |
| 标签页数 | `VTabPages.java:55` | 7 | 7 | ✅ |
| 探针文件总数 | `VDocCheck.java` D-2 | 116 | 磁盘 116 | ✅ |

调度者复核：`addSlider(` 调用 14 次、`bindCheck(` 9 次 + `row.checkBox("")` 1 次 = 10、`row.comboBox(` 4 次、`out.add(\w+\(` 28 条、`GOLDEN.put` 34 条。

**但**：`CLAUDE.md §7.0` 的 B 表仍写「`checks == 9`」「State 字段数 `== 30`」，与现状（10 / 34）不符。探针里已有「契约变更，非缺陷」注释（`VAddSetting.java:119`、`VPreset.java`），代码与断言是同步的，**不是漂移**。

**`VDocCheck` D-2 的口径缺口**：它统计**所有** `V*.java`（含 `V0Env` / `VFields` / `VUi` 三个无 `main` 辅助类），所以「116 个探针」里有 3 个不是探针；若将来有人删掉某个探针的 `main`，文件数仍是 116，D-2 不会红。

### 3.4 未注册探针

**0 个遗漏，0 个重复，0 个不存在。**

- `$mains` 数组：**113 项**
- 有 `static void main` 的 `V*.java`：**113 个**
- 有 main 但不在 `$mains`：**0**
- 在 `$mains` 但文件不存在：**0**
- 重复登记：**0**
- 未注册的 3 个文件（`V0Env` 563 行 / `VFields` 77 行 / `VUi` 412 行）都没有 `main`，是有意不注册的辅助类。

这是本次体检**唯一完全干净**的类别。

### 3.5 informational 黑洞

`build-v-probes.ps1:332`：`$informational = @('VOrder', 'VOrtho', 'VRegr2')`，引入于 2026-10-20（`7426afc`），约 12 天。

| 探针 | 断言数 | 已知红/跳过情况 |
|---|---|---|
| `VOrder` | 2 条（O-2、O-3） | O-1 已降级为 INFO；O-3 恒真；**实际只剩 O-2 一条能红**。门禁注释仍写 O-1 在断言，已过期 |
| `VOrtho` | 19 条 | 门禁注释称 12 条是「真实未修缺陷」；探针里 O-8 等已标「【已修】」，注释与现状不符 |
| `VRegr2` | 30+ 条 | 见下 |

`VRegr2` 专项：
- 在 `$informational` 里，不计入失败门。
- `CLAUDE.md §7.3` 写「2 条红（R-5 / R-6）」；门禁脚本注释写「R-6/R-17c/R-18/R-19/R-22」5 条；`build/v2-probe/VRegr2.txt` 实际是 `PASS 13 / FAIL 2`，然后第 8 段 `deriveOnSmoothChange` 抛 `NullPointerException`（`VRegr2.java:338`），之后未执行。
- 三处口径不一致（2 / 5 / 2+NPE）。R-5/R-6 与 `typingSmoothness` 的 `floatsGlobal` 语义冲突，属于**产品语义问题**，不是探针单独能修的。

### 3.6 额外检查

- **不读真实用户配置**：`grep -nE 'APPDATA|LOCALAPPDATA|USERPROFILE|user\.home|System\.getenv' V*.java` → **0 命中**。
- **写工作目录**：`VerifyPersistence.java:76` 默认写 `verify-state.xml`（cwd），`.gitignore:50` 的 `*-state.xml` 覆盖，不污染 git，但会留文件。
- **硬编码绝对路径**：`VAuditBatch2Round5.java:44-46` 的 fallback `D:\deepseekwork\neovim-cursor-pr`（仅在缺 `-Dneovide.repo` 时使用）。
- **计时敏感**：12 个探针用 `Thread.sleep` / `System.nanoTime`；`VSleep` / `VSleep2` 直接断言本机 sleep 精度，换机器可能翻转（设计如此，但不是跨机器稳定契约）。
- **重复覆盖**：`VAddSetting:120` 与 `VerifyPanel:98/106/110` 逐字重复；`VRank` 与 `VRankCollapse` 都测副本；`VPreset` 与 `VDefaultFreeze` 部分重叠；等等。

### 3.7 按严重度排序

| 级别 | 编号 | 问题 |
|---|---|---|
| CRITICAL（门禁可信度） | 1 | `VHandFeel` 有断言但**永远无法让门禁失败**：`main` 末尾只 `println("... RESULT: FAILED")`，没有 `System.exit(1)`、没有抛异常；门禁只看退出码（`build-v-probes.ps1:351`）。当前它 2/2 通过，所以是**潜在**缺口，不是正在掩盖红 |
| HIGH | 2 | 9 个零断言探针被计入「ALL PASS」 |
| HIGH | 3 | `VRank` / `VRankCollapse` 测的是 rank 副本，不是生产 |
| HIGH | 4 | 7 个探针裸读 `src/main/java/...`，忽略 `-Dneovide.repo` |
| HIGH | 5 | `VRegr2` 的 informational 黑洞 + 三处互相矛盾的「已知红」口径 |
| MEDIUM | 6 | 10 处恒真断言 |
| MEDIUM | 7 | `VOrder` / `VOrtho` 门禁注释过期 |
| MEDIUM | 8 | `VDocCheck` D-2 把 3 个辅助类算进探针数 |
| MEDIUM | 9 | `VPerf` / `VPerf2` 用 rank 副本做仿真 |
| MEDIUM | 10 | `VVerify` 的负面结论不可见 |
| LOW | 11–15 | 硬编码路径 fallback、`verify-state.xml`、3 处空 catch、12 个计时敏感探针、7 组重复覆盖 |

---

## 四、去重后的发现总表（按严重度）

> 「N 视角」= 有几个代理独立发现同一问题。「调度者复核」= 本次审查的独立验证结果。

| ID | 发现 | 视角 | 调度者复核 | 统一严重度 |
|---|---|---|---|---|
| F-01 | 多光标静止分组在淡出期按满亮度绘制（可见回归） | 代码 1 | **成立**（`lastGlowStrength` 全局字段、`moved` 仅目标变化帧、`fading = !moved`） | **HIGH** |
| F-02 | 交付报告第一轮结论被推翻却未认错 | 文档 1 | **成立**（§1/§4.1/§12 Q3/AC-004 未改，全文无认错） | **HIGH** |
| F-03 | 报告 A-2 的 `shouldSaveOnApply()` 证据与平台字节码相反 | 文档 1 | **成立**（`applyIfEnabled` 0 次；javap 确认默认返回 true） | **HIGH** |
| F-04 | `VHandFeel` 有断言但门禁无法失败 | 探针 1 | **成立**（无 `System.exit`/throw；门禁只看退出码）；当前 2/2 通过 | **HIGH**（代理 3 标 CRITICAL） |
| F-05 | 9 个零断言探针被计入 ALL PASS | 探针 1 | **成立**（7 个 0 命中；2 个只有 `exit(0)`） | **HIGH** |
| F-06 | `VRank` / `VRankCollapse` 测副本不测生产 | 探针 1 | **成立**（无 `SpringTrail`/`assignRanks` 引用） | **HIGH** |
| F-07 | 7 个探针裸读相对路径，无 `neovide.repo` | 探针 1 | **成立**（逐个 grep 确认） | **HIGH** |
| F-08 | `VRegr2` 三处「已知红」口径矛盾 + NPE 后静默不跑 | 探针 1 | **成立**（2 / 5 / 2+NPE） | **MEDIUM**（R-5/R-6 本身已定案） |
| F-09 | 报告 §4.3 的 `useLastGlow` 代码块过时 | 文档 1 | **成立**（签名与返回值都不同） | **MEDIUM** |
| F-10 | 「6 组变异测试」仅第 6 组有产物 | 文档 1 | **成立**（`build/mut3` 有，M1–M5 无） | **MEDIUM** |
| F-11 | 「三份状态文档已同步」过度陈述 | 文档 1 | **成立**（NEXT-STEPS:357 仍写 97） | **MEDIUM** |
| F-12 | A-2 的禁用输入框未修复也未列已知缺口 | 文档 1 | **成立**（提交未改 `enabledIf`） | **MEDIUM** |
| F-13 | 长动画下辉光提前熄灭（125ms vs 536ms 拖尾） | 代码 1 | 逻辑成立；是否算缺陷取决于产品定义 | **MEDIUM（待用户拍板）** |
| F-14 | 键入一个字就刷新预览 → 光标瞬移回起点 | 代码 1 | **机制成立**（`onConfigChanged` 无条件 `placed=false`） | **MEDIUM** |
| F-15 | 保持期算术在 `CaretAnimator` 与 `GlowIntensity.fade` 两处各写一遍 | 代码 1 | 成立（陷阱 7） | **MEDIUM** |
| F-16 | `CLAUDE.md §7.0` 的契约常数 9 / 30 已过期（实际 10 / 34） | 探针 1 + 调度者 | **成立**（`VAddSetting:120`、`VPreset:389`） | **MEDIUM** |
| F-17–F-30 | 报告 LOW 项（T-7/T-11、33,111/130、渲染 4→5、§9 计数、漏 HANDOFF、复现命令、stateVersion 22 处、未提交、javadoc 位置、T-8 描述、VRegr2 NPE、`glowStrength>0`、B-2 歧义、推断当结论） | 文档 1 | 逐条核对成立 | **LOW** |
| F-31–F-39 | 代码 LOW 项（失焦 600ms 参数不一致、catch Throwable、越界三值不一致、注释漂移、justAdvanced 顺序等） | 代码 1 | 逐条核对成立 | **LOW** |
| F-40 | 10 处恒真断言 / `VDocCheck` 计数口径 / `VOrder`/`VOrtho` 注释过期 / 计时敏感探针 / 硬编码路径 / 重复覆盖 | 探针 1 | 抽样复核成立 | **MEDIUM–LOW** |

**多视角同时发现**：F-11 与调度者独立发现的 `HANDOFF:11`（112 个探针）属于同一类「文档数字失真」，共 2 个视角；F-16 的 9/30 过期值同时出现在任务书、`CLAUDE.md §7.0` 与代理 3 的体检里。

---

## 五、三个代理的冲突与待确认

| # | 冲突/待确认 | 各方说法 | 调度者裁定 |
|---|---|---|---|
| 1 | `VHandFeel` 的级别 | 代理 3：CRITICAL；代理 2：代码无 CRITICAL（范围不含探针）；`rules/common/code-review.md`：CRITICAL = 安全漏洞或数据丢失 | **统一为 HIGH**。它不造成数据丢失，但会让「绿」不可信；当前 2/2 通过，属于潜在缺口，不是正在掩盖红。报告 §三仍保留代理 3 的原始定级并注明理由 |
| 2 | 淡出起点改为「最后一次移动」是 bug 还是取舍 | 代理 2：MEDIUM，取决于「停止」的定义；代码注释：有意为之 | **待用户拍板**（产品决策）。默认参数差 35ms，拉满动画时长差 400ms |
| 3 | 键入刷新预览导致光标瞬移是否可接受 | 代理 2：MEDIUM，可能让用户再次报「输入框没效果」 | **待用户实机确认**；机制已复核成立，但「观感是否可接受」是产品决策 |
| 4 | 报告「14/14 正常路径一直是通的」的证据强度 | 代理 1：只有一次性脚本、无产物，计入「夸大」；代理 2：`javap` 确认 bind 无文档监听，支持该机制 | **不冲突**：机制成立，但「14/14」这一具体测量不可复现。报告中写作「机制有平台字节码证据，数值无产物」 |
| 5 | 任务书/`CLAUDE.md` 的期望 9 / 30 | 任务书 §7.0 与 `CLAUDE.md` 写 9 / 30；探针写 10 / 34 | **以代码为准：10 / 34**。探针已带「契约变更，非缺陷」注释；`CLAUDE.md` 待同步 |
| 6 | `VRegr2` 的「已知红」是 2 条还是 5 条 | `CLAUDE.md`：2 条；门禁注释：5 条；实跑：2 条 + NPE | **待统一口径**。R-5/R-6 是已定案功能缺口，不重报；只报三处文档口径不一致 |
| 7 | 门禁数字 1213 vs 1288 | 调度者第一次用 `^  PASS `（两空格）数出 1213；改用 `^\s+PASS ` 后 1288 | **以 1288 为准**。部分探针输出缩进不是两空格，第一次统计口径错了 |

---

## 六、发版前必须处理的清单（P0 / P1）

### P0（不处理不建议发版）

1. **F-01 多光标静止分组满亮度辉光**（HIGH）
   - 选项 A：修 —— 把 `fading` 改成逐组判据，或在 `fading` 时对 `groupMaxOffset == 0` 的分组返回 0。
   - 选项 B：用户明确接受该观感，并在代码注释与交付报告里改掉「静止光标不发光」的断言。
   - **不能只改注释**：注释现在描述的是错误行为，改注释等于承认回归。

2. **F-02 报告未承认第一轮判断被推翻**（HIGH）
   - 改 §1、§4.1、§12 Q3、AC-004：明确写「第一轮判定 (b) 被第二轮实测推翻，B-4 才是主因；§4.4 的 20/20 只能证明纯函数正确，不能证明用户可见」。

3. **F-03 报告的 `shouldSaveOnApply()` 证据错误**（HIGH）
   - 改成「本项目未调用 `applyIfEnabled`，该方法恒返回 true；禁用控件的问题只是收不到键入」。

4. **F-11 / F-16 文档数字统一**（MEDIUM）
   - `NEXT-STEPS.md:357-358` 的 97 / 0.9.104 → 116 / 0.9.119。
   - `HANDOFF-NEXT-2026-11-01.md:11-13` 的 HEAD 46c712d / 112 个 → 说明这是 4af8965 之前的快照，或更新为 a62afe5 / 116。
   - `plans/fix-input-and-fade.md:206` 的 33,111 / 130 → 33,311 / 131。
   - `CLAUDE.md §7.0` 的 `checks == 9` / `State == 30` → 10 / 34。

### P1（发版前应处理，或明确登记为已知）

5. **F-04 `VHandFeel` 无法失败**：补 `System.exit(fail > 0 ? 1 : 0)`，或从 `$mains` 移除并登记。
6. **F-14 预览每次键入重置**：实机确认；若用户觉得「预览坏了」，改成「只有尺寸变化才 `placed = false`」。
7. **F-09 报告 §4.3 代码块**：换成 `useLastGlow(overlayFadeOut, moved, fade)`，或标注「已被 §14.4 取代」。
8. **F-10 变异测试可复现性**：为 M1–M5 补命令或产物，或把报告措辞改成「M6 有产物，M1–M5 为当时实跑、未留存」。
9. **F-05 / F-06 / F-07 探针体系三项**：不阻塞运行，但「116 个探针 ALL PASS」不能作为发版唯一依据。至少登记成已知缺口，或把零断言探针从 `$mains` 移除。
10. **F-13 长动画淡出**：拿默认 35ms / 拉满 400ms 的数据让用户拍板；若接受现状，补一条探针把「淡出曲线与拖尾寿命无关」写成显式契约。

---

## 七、可以不处理的（P2 / P3 + 理由）

| 项 | 级别 | 不处理的理由 |
|---|---|---|
| F-17–F-30 报告 LOW 项 | LOW | 不影响运行与验收结论；可在下一次文档整理时一并改 |
| F-31–F-39 代码 LOW 项 | LOW | 当前无失败场景或仅是风格/注释漂移；#5（失焦 600ms）在修改前也是同形态 |
| 10 处恒真断言 | P3 | 其中多数靠异常兜底，属于「弱断言」而非假绿；`VPreset9` 的 `if (false)` 值得单独修，但不影响本次发版 |
| `VDocCheck` 计数口径 | P3 | 只影响「116」这个数字的含义，不影响任何行为 |
| `VOrder` / `VOrtho` 注释过期 | P3 | 两者都是 informational，不参与失败门 |
| 计时敏感探针 | P3 | 设计意图就是量本机；换机器翻转属于预期 |
| 硬编码路径 fallback / `verify-state.xml` / 空 catch | P3 | 当前被 `-Dneovide.repo` / `.gitignore` / 后续断言覆盖 |
| 7 组重复覆盖 | P3 | 冗余不是错误；发版前知道即可 |
| 保持期算术两处（F-15） | P2 | 当前两份实现一致；建议随下一次改动合并，不单独为它发版 |

---

## 八、已定案、本次不重报

以下事项已在项目文档/用户决策中定案，本次审查**不作为新发现**：

- 辉光始终用 `shadowColor`，从未跟随光标本体色。
- 静止时仍每帧重绘（用户拍板保持现状）。
- 半边拖尾已回退为「只留开关」（2026-11-01 决定）。
- 预览窗尺寸锁定。
- `VRegr2` 的 R-5 / R-6 是已知功能缺口。
- 国际化范围外仍为中文的 6 项（`I18N-REPORT §八` 已登记）。

> `VRegr2` 的 NPE 与三处口径矛盾（F-08）**不是**在重报 R-5/R-6，而是报「文档说 2 条、脚本说 5 条、实跑 2 条 + NPE」这一不一致；定级 MEDIUM。

---

## 九、审查方法与证据来源

### 9.1 代理状态（AC-001）

| 代理 | 状态 | 产出 |
|---|---|---|
| 文档复核 | **完成** | 38 一致 / 5 夸大 / 5 漏写 / 11 错误 |
| 代码质量 | **完成** | 1 HIGH / 3 MEDIUM / 5 LOW |
| 探针体系 | **完成** | 五类检查各有结论与名单 |

三个代理全部返回后才汇总，无缺失视角。

### 9.2 调度者独立复核的证据

```
git status --porcelain                          -> 只有 ?? build/night-run-report.md
git log --oneline -6                            -> HEAD a62afe5
git rev-list --left-right --count fork/main...HEAD -> 0 0
git diff --exit-code -- core/NeovideConfig.java -> 退出码 0（D0 PASS）
ls tools/settings-probe/V*.java | wc -l         -> 116
ls tools/settings-probe/*.java | wc -l          -> 131
cat tools/settings-probe/*.java | wc -l         -> 33,311
find src/main/java -name '*.java' | wc -l       -> 37
find src/main/java -name '*.java' -exec cat {} + | wc -l -> 21,414
$mains 条目 / 有 main 的文件 / 差集            -> 113 / 113 / 0
113 个注册探针的 11:42-11:44 输出               -> 1288 PASS / 2 FAIL（仅 VRegr2 R-5/R-6）
javap CellImpl.shouldSaveOnApply()             -> applyIfEnabled=false 时返回 true
grep -rn applyIfEnabled src tools              -> 0
两个 bundle 的 key 集合 diff                    -> 退出码 0（各 165 key）
```

### 9.3 未执行的检查（如实说明）

- **没有重跑门禁**：后台执行 `build-v-probes.ps1` 被权限分类器拒绝。1288 / 2 来自 11:42–11:44 的既有产物，不是本次新跑。若要发版签字，建议在干净环境再跑一次。
- **没有实机验收**：F-01（多光标观感）、F-13（长动画淡出）、F-14（预览重置）都需要用户实机确认，静态审查只能证明机制存在。
- **代理 3 未运行任何探针**：按要求只做静态分析，所以 `VOrtho` 当前具体哪几条红、`VHandFeel` 的历史失败情况没有实测。

### 9.4 本次未修改任何文件

除本文件 `docs/FINAL-AUDIT-2026-11-01.md` 外，没有新增、修改或删除任何生产代码、探针、文档。收工时 `git status --porcelain` 只多这一份未跟踪的 md。

> 以上描述的是**审查当时**的状态。审查之后的修复改动见 §十。

---

## 十、修复记录（2026-11-01，审查之后）

| 项 | 处理 | 验证 |
|---|---|---|
| P0-1 多光标串扰 | `GlowIntensity.useLastGlow` → `useLastGlowForGroup` + `glowForGroup`；`SpringTrail.lastActiveNanos` 逐光标记录；`CaretAnimator` 按组聚合；`AnimationPreview` 同步 | `javac --release 21` 通过；`VFadeTick` 24/24、`VFadePreview` 5/5；变异（逐组判据改回恒 true）T-7 / T-16b 红，恢复后全绿；全量门禁 **1292 PASS / 2 FAIL（VRegr2）/ ALL PASS** |
| P0-2 报告两处 HIGH | §1 / §4.1 / §12 Q3 / AC-004 改为「第一轮 (b) 被第二轮推翻」；A-2 的 `shouldSaveOnApply()` 证据改为「本项目未调 `applyIfEnabled`，恒返回 true」 | 全文无「apply 也不保存」 |
| P0-3 文档数字 | `NEXT-STEPS` 97→116；`HANDOFF` HEAD / 版本 / 探针数；`plans` 33,111/130→33,311/131；`CLAUDE.md §7.0` 9/30→10/34 | `grep` 旧值 0 命中 |
| P1 报告 §4.3 代码块 | 换成 `glowForGroup` 当前实现 | 与源码一致 |
| P1 报告 LOW 项 | T-7/T-11、33,111/130、§9 计数、漏 HANDOFF、复现命令、`stateVersion`、T-8 描述、VRegr2 NPE、B-2 歧义、推断标注 | 逐条改 |
| P1 `VHandFeel` 不可失败 | **未处理** | 仍为潜在缺口 |
| P1 预览每键入重置 | **未处理**（待用户实机确认） | 机制仍在 |
| P1 探针空转/副本/裸路径 | **未处理**（登记为已知） | 仍在 |
| 打包安装 | **v0.9.120 已安装**（2026-11-01 21:24，246,326 B；个人配置 12 字段还原，丢弃 0） | jar 内 `<version>0.9.120`；`javap` 确认 `useLastGlowForGroup` / `glowForGroup` |

修复后**全量门禁已重跑**：113 个注册探针 / **1292 PASS / 2 FAIL（仅 VRegr2 的 R-5、R-6，informational）/ `V-PROBES RESULT: ALL PASS`**。断言总数与预期 `1288 + 4 = 1292` 一致。
