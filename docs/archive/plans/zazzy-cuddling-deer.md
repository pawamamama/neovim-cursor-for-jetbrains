# 辉光淡出：限速变暗（B 方案，0.9.126）

## Context（为什么改）

用户要的是：**辉光跟着拖尾一起变暗，「保持时长 / 淡出时长」仍然有效，不能突兀消失，不按层数变暗，不影响连续打字 / 跨行 / 多光标。**

现状 0.9.125 的实际时间线（单行打字，默认保持 50ms、淡出 75ms）：

```
0 ~ 122ms     拖尾在收拢，亮度被钉在 1.0
122ms         光标视觉上停住
122 ~ 172ms   继续保持 1.0
172 ~ 247ms   才开始线性淡出
```

根因是 `CaretAnimator.tickSpring()` 里的两行：

```java
if (glowStrength > 0f) {        // 拖尾还有偏移
    lastAnimatingNanos = now;   // 计时起点每帧重置
    fade = 1f;                  // 强制不淡出
}
```

加上 `GlowIntensity.glowForGroup()` 在“本组最近活动过”时直接返回 `holdGlow`（通常 1.0），完全绕开当前偏移。所以辉光只能等拖尾归零、再等保持期结束，才开始淡——用户感知为“停下后才熄灭”。

两个已被否决的极端：

- **0.9.124（亮度=偏移曲线）**：曲线只在最后 2px（0.5~2.5）下降，淡出从 ≈1.5% 开始，肉眼不可见，参数无效。
- **0.9.118（计时绑到按键那一帧）**：缎带拖尾 424ms，辉光 120ms 就归零，72% 的时间无光。

所以本次不改默认值、不改强度曲线、不改层数语义，只把“到点乘 0”换成“限制下降速度”。

## 决策

- **只做 B**。C（感知曲线 `(1-t)^3`）留作实机后的可选第二步；若作用到全程会把 10 键/秒的呼吸从 0.57 放大到 0.19，所以本次不做。
- 不改 `NeovideConfig`（D0 红线）、不改默认值、不改 UI 文案、不改层数语义。
- 不在本阶段提交、打 tag、动 PyCharm。

## 核心模型

### 纯函数（放在 `core/GlowIntensity.java`，生产与预览共用）

```java
// 目标 = 当前偏移的强度曲线
float target = GlowIntensity.strengthFor(offset);

// 每帧推进：上升立即，下降受 1/淡出时长 限制
if (!overlayFadeOut) return target;                 // 开关关闭：保持旧行为
if (target >= current) return target;               // 上升立即（AC-2）
if (holdActive) return current;                     // 保持期内不降
if (fadeOutSeconds <= 0) return target;             // 0 秒 = 立即
return max(target, current - dtSeconds / fadeOutSeconds);
```

- `holdActive = overlayFadeOut && lastMoveNanos != 0 && now - lastMoveNanos < cursorDisappearDelay`。
- **保持期从「最后一次移动」起算**，不是从拖尾收拢起算。这是 B 能做到“收拢期就开始暗”的关键；若从收拢起算，就退回 0.9.125。
- 目标始终是 `strengthFor(offset)`：拖尾还在时目标不会归零，所以缎带不会中途灭。
- `overlayFadeOut = false` 时直接返回曲线值，保持关闭开关的旧语义。

### 状态

- 每帧维护**每个 `SpringTrail` 的亮度状态**：`brightness`（0~1）与 `lastMoveNanos`。
- 存储放在 `CaretAnimator` 内：`IdentityHashMap<SpringTrail, GlowState>`，不修改 `SpringTrail`（保持在 3 个生产文件的白名单内）。
- 每帧用 `glowStates.keySet().retainAll(activeTrails)` 清理已消失的 trail。
- 分组：组目标 = 组内最大偏移的 `strengthFor`；组亮度 = 组内 trail 亮度的最大值；组最后移动 = 组内 `lastMoveNanos` 的最大值。算出的新亮度**写回组内每个 trail**，保证相邻行亮度一致、拆组后各自带着正确状态继续。
- 从未移动过的 trail：`brightness = 0`、`lastMoveNanos = 0`，静止组永远是 0（多光标不串扰）。

### 调度

- `tickSpring()` 返回 `anyAnimating || needFrames`。
- `needFrames` 逐组计算并 OR：
  - 开关关闭 → false；
  - 亮度为 0 → false；
  - 目标 > 0 → true（拖尾还在，但 `anyAnimating` 通常已覆盖）；
  - 保持期且剩余 > 一个空闲帧（120ms）→ false（画面恒定，不空转）；
  - 保持期剩余 ≤ 空闲帧，或正在下降 → true（保证淡出被逐帧采样）。
- 面板的 `glowFade` 固定为 1，最终亮度由每个四边形的 `quadGlow` 携带，避免全局 `fade` 串扰。

## 逐帧行为表（模拟结果）

单行打字 D=8px，L=0.11s，hold=50ms：

| 时间 | 0.9.125 | B（fade=75） | B（fade=300） | B（fade=500） | 光晕面积（近似） |
|---|---|---|---|---|---|
| 0ms | 1.000 | 1.000 | 1.000 | 1.000 | 701 px² |
| 50ms | 1.000 | 1.000 | 1.000 | 1.000 | 648 px² |
| 100ms | 1.000 | 0.573 | 0.893 | 0.936 | 613 px² |
| 300ms | 0.000 | 0.000 | 0.227 | 0.536 | 599 px² |

跨行 D=32px，L=0.10s：

| 时间 | 0.9.125 | B（75） | B（300） | 光晕面积 |
|---|---|---|---|---|
| 0ms | 1.000 | 1.000 | 1.000 | 1009 px² |
| 50ms | 1.000 | 1.000 | 1.000 | 775 px² |
| 100ms | 1.000 | 1.000 | 1.000 | 642 px² |
| 300ms | 0.000 | 0.000 | 0.360 | 599 px² |

缎带 0.28s D=32px：0/50/100/300ms 两种模型都接近满亮（B 在 300ms 为 0.97）；差异出现在 430ms 后的收尾，B 跟着曲线下降，fade=300 再补一段尾巴。

连续打字 10 键/秒（L=0.11，hold=50，fade=75）：亮度在 **0.573 ~ 1.000** 之间呼吸。这是 B 的已知代价；实机若不可接受，优先让用户把保持时长调到 100ms 以上（零代码），或另开“连续输入保护”任务。

## 改动文件（严格 3 生产 + 2 探针）

### 1. `src/main/java/.../core/GlowIntensity.java`

- 保留 `strengthFor()` 与 `OFFSET_START/OFFSET_END`。
- 删除 `fade()` / `useLastGlowForGroup()` / `glowForGroup()`（B 下不再有“全局 fade 乘子”）。
- 新增：
  - `advanceGlow(boolean overlayFadeOut, float current, float target, double dtSeconds, double fadeOutSeconds, boolean holdActive)`；
  - `needsMoreFrames(boolean overlayFadeOut, float brightness, float target, boolean holdActive, long holdRemainingMs, long idleFrameMs)`；
  - `holdRemainingMs(long lastMoveNanos, long nowNanos, int delayMs)`（纯函数，便于探针）。
- 所有非有限值守卫沿用现有风格：NaN/Infinity 一律按 0 处理，不静默空转。
- 类 javadoc 改写为“目标曲线 + 限速下降”，保留 0.9.118 / 0.9.124 / 0.9.125 的历史说明。

### 2. `src/main/java/.../ui/CaretAnimator.java`

- 删除 `lastAnimatingNanos`、`lastGlowStrength`、`glowFade(now)`。
- 新增私有静态类 `GlowState { float brightness; long lastMoveNanos; }` 与 `IdentityHashMap<SpringTrail, GlowState> glowStates`。
- `refreshTarget()` / `refreshTargetInReadAction()` 增加 `long now` 参数（唯一调用点在 `runTick`），在 `trail.setTarget(...)` 返回 true 时写 `state.lastMoveNanos = now`。
- `tickSpring()`：
  - 保留 `groupMaxOffset` 计算；
  - 删除 `groupRecentlyActive` / `recentWindowNs`；
  - 新增 `groupBrightness` / `groupLastMove` / `groupTarget` / `groupHold` / `groupNeedsFrames` 复用数组；
  - 按组调用 `advanceGlow`，写回组内每个 trail 的 `brightness`；
  - 渲染时 `trailGlow = groupBrightness[groupId]`，`panel.setGlowFade(1f)`；
  - `needFrames` 逐组 OR。
- `dt` 用现有 `tickSpring(dt, now, moved)` 的 `dt`（秒）；`cursorDisappearDelay` 毫秒转纳秒。
- 不改 `SpringTrail`、`CaretTrailPanel`、`NeovideConfig`。

### 3. `src/main/java/.../ui/settings/AnimationPreview.java`

- 删除 `lastGlowVisibleNanos`；保留 `justAdvanced`，新增 `lastMovedNanos`。
- `step()` 与生产同源：`moved = justAdvanced`；`target = strengthFor(maxOffset)`；`holdActive = ...`；`lastGlowStrength = advanceGlow(...)`；`panel.setGlowStrength(lastGlowStrength)`；`panel.setGlowFade(1f)`。
- 预览是单光标单组，组状态就是它自己的状态。

### 4. `tools/settings-probe/VFadeTick.java`

重写为纯函数断言（约 20 条）：

- 保持期内不降；
- 下降速率 = `dt/fadeOutSeconds`（75/300/500 三档数值不同）；
- 目标地板：不低于 `target`；
- 上升立即回到目标；
- `overlayFadeOut=false` 时直接返回曲线；
- `fadeOutSeconds=0` 立即归零；
- NaN/Infinity 守卫；
- 单调性：无新移动时不允许任何一帧变亮；
- `needsMoreFrames` 的保持期/下降期/结束期三段；
- 源码守卫：`CaretAnimator` 调用 `advanceGlow`，不再出现 `fade > 0f && fade < 1f`、`glowForGroup`、`lastGlowStrength` 保持路径。

### 5. `tools/settings-probe/VFadePreview.java`

重写为真实驱动预览（约 6 条）：

- 拖尾展开期亮度达 1.0；
- 拖尾收拢后仍有亮度 > 0 的尾巴（不是一步归零）；
- 无新动作时亮度单调不增；
- 单帧下降 ≤ `dt/fade + ε`；
- **两个实例（fade=75ms 与 300ms）在同一时刻的亮度可测不同**——这是“参数有效”的守门；
- 从未动作过的预览亮度为 0。

## 变异测试（至少 3 组，做完删 classes 全量重编译）

1. `advanceGlow` 忽略 `fadeOutSeconds`（固定 75ms）→ VFadeTick 的 75/300/500 差异断言必红。
2. `advanceGlow` 直接返回 `target`（无限速）→ VFadePreview 的“尾巴 / 单帧降幅”断言必红。
3. 保持期从“收拢”起算（退回 0.9.125）→ “收拢期亮度已开始下降”断言必红。
4. 组亮度不写回、按全局 `moved` 判定 → 多光标静止组/拆组断言必红（若时间允许，加一条静态组断言）。

每次变异后删除 `build/v2-probe/classes`，避免增量编译复用旧字节码。

## 验证与安装

1. 先保存回滚点：`git diff > build/fade-pre-0.9.126.patch`（未跟踪临时文件）。
2. D0：`git diff --exit-code -- src/main/java/.../core/NeovideConfig.java` 必须 EXIT=0。
3. 全量门禁（只跑一份）：
   `pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo "D:/deepseekwork/neovim-cursor-pr"`
   必须 `V-PROBES RESULT: ALL PASS`（基线 113 个注册探针；VFadeTick / VFadePreview 断言数会变）。
4. 代码审查：`code-reviewer` + `java-reviewer`；CRITICAL/HIGH 必须修。
5. 打包安装 0.9.126（保留 0.9.124 / 0.9.125 的 jar）：
   - `build/install.ps1 -Version 0.9.126 -DryRun`，结构指纹应为 28（本次不加字段），出现警告就停下报告；
   - `build/install.ps1 -Version 0.9.126 -NoStart`；
   - 安装会关闭 IDEA，先确认 IDEA 未运行或提醒存盘。
6. 装完核验三项：jar 内 `<version>0.9.126`、配置还原字段数、已装 jar 的 md5。
7. 交付实机验收清单：
   - 单行打字停下：辉光从当前亮度连续下降，无上升/阶跃；
   - 连续打字 10 键/秒：观察是否有不可接受的呼吸（0.57~1.0）；
   - 缎带跨行 0.28s：全程有光，收尾跟着收拢开始暗；
   - 淡出时长 75 / 300 / 500：肉眼可区分；
   - 多光标：静止组不因另一组移动而发光。
8. **停在这里，等用户说“测试没问题”**；未获明确同意不提交、不打 tag、不动 PyCharm。

## 风险与回滚

- **10 键/秒呼吸**：B 的已知代价（模拟 0.573~1.0）。优先用保持时长 ≥100ms 规避；实机不接受再评估“连续输入保护”。
- **多光标拆组/并组**：per-trail 状态 + 组内写回；拆组后各自延续，并组时亮度取组内最大。
- **调度空转**：保持期用空闲档，剩余 ≤120ms 时恢复逐帧；探针覆盖三段。
- **预览与生产分家**：两处都调 `advanceGlow`，VFadePreview 守门。
- **回滚**：`build/fade-pre-0.9.126.patch` + 重装 `dist/neovide-cursor-0.9.125.jar`。

## 明确不做

- 不改默认值、UI 文案、README、CLAUDE.md 状态数字。
- 不改 `core/NeovideConfig.java`（D0 零 diff）。
- 不改层数语义、不按层数变暗。
- 不做 C（感知曲线），不做“淡入”方向。
- 不提交、不打 tag、不动 PyCharm。
