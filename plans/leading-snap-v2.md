# leadingSnap v2 实现计划：回退绘制层尾巴，改走路线 C

> 依据：`docs/archive/reports/LEADING-SNAP-ISSUES-2026-11-01.md`
> 起点：HEAD `c2ae1e9`，工作区只有两个未跟踪文档。
> 出稿：planner 代理（2026-11-01），主会话按白名单与任务书做了三处收敛。

---

## Research 记录

### 第 1 轮：本仓库

| 查什么 | 结论 |
|---|---|
| `c2ae1e9` 引入的 halfTrail 符号 | `CaretTrailPanel.halfTrail/quadDirs/setHalfTrail/fillHalfTrail/addQuad(5参)`、`CaretAnimator.setHalfTrail + addQuad(dir)`、`AnimationPreview.setHalfTrail/dirX/dirY/pushSeen`、`SpringTrail.dirX/dirY`、`TrailCorner.HALF_TRAIL_CAP_WIDTHS` |
| 删除后会断的调用点 | 全仓 grep：`CaretAnimator:2906`、`AnimationPreview:966` 是 5 参 addQuad 仅有的生产调用；`setHalfTrail` 只在 `CaretAnimator:2728/2873`、`AnimationPreview:298`；`HALF_TRAIL_CAP_WIDTHS` 只在 `CaretTrailPanel:981` |
| 唯一能按 rank 给时长的入口 | `TrailCorner.jump`（短跳分支 :312-324；跨行分支 :384-398；末尾 :405-406 写 spring） |
| 0ms 精确到位的机制 | `DampedSpring.update:96` 的 `animationLength <= dt` 短路 |
| 不能误删的撞名符号 | `CaretTrailPanel.dirX/dirY` 数组是**辉光外扩方向**，与 halfTrail 无关 |

### 第 2 轮：外部

- `gh search code snapAnimationLength` 命中多家上游：`yyyyolo7a79-sketch/neocursor-fix`、`Govindggupta/neovide-cursor`、`AlexDaPiggie/Neovide_Cursor_Modification`。
- `neocursor-fix` 原文：吸附角用 `snapAnimationLength`（v1.2.7 = 0，注释里旧值 0.02），后角用 `baseTime × trailFactor`；其 `hideNativeCursor=false`，前缘被真光标盖住，所以 0ms 不暴露。
- 多家把 **0.02s** 作为「瞬时吸附时长」的旧值 —— 本项目取它作为后缘固定短尾，有数值依据。
- **没有现成「前缘 0 + 后缘固定 0.02」的实现**；这是本项目在隐藏原生光标前提下的自创组合，必须靠探针与实机双重验证。
- Neovide 官方（`cursor_renderer.rs`）仍是四角统一 `cursor_animation_length` 的临界阻尼弹簧，无半边模式。

---

## 一、删除清单（逐符号）

| # | 符号 | 文件:行 | 只服务 halfTrail？ | 处置 |
|---|---|---|---|---|
| 1 | `TrailCorner.HALF_TRAIL_CAP_WIDTHS` | `core/TrailCorner.java:129-130` | 是（唯一读者 `CaretTrailPanel:981`） | 删 |
| 2 | `CaretTrailPanel.halfTrail` 字段 | `CaretTrailPanel.java:94-100` | 是 | 删 |
| 3 | `CaretTrailPanel.quadDirs` | `CaretTrailPanel.java:102-103` | 是（读者 :642-647、:955-956 同步消失） | 删 |
| 4 | `CaretTrailPanel.setHalfTrail` | `CaretTrailPanel.java:105-108` | 是（3 个调用者同步删） | 删 |
| 5 | `CaretTrailPanel.addQuad(5 参)` | `CaretTrailPanel.java:624-650` | 是 | 删；3 参版（:620-622）保留并把实现体内联，去掉 dirBase 段 |
| 6 | `paintQuad` 方向读取 + halfTrail 分支 | `CaretTrailPanel.java:954-961` | 是 | 删，恢复无条件 `g2.fill(path)` |
| 7 | `CaretTrailPanel.fillHalfTrail` | `CaretTrailPanel.java:964-1006` | 无其他调用者 | 删 |
| 8 | `tickStaticCaret` 的 `setHalfTrail(false)` | `CaretAnimator.java:2727-2728` | 是 | 删（含注释） |
| 9 | `tickSpring` 的 `setHalfTrail(leadingSnap)` | `CaretAnimator.java:2872-2873` | 是 | 删（含注释）；`leadingSnap` 字段保留（L-10/L-11 依赖） |
| 10 | `moving` 变量 + 5 参 addQuad | `CaretAnimator.java:2902-2907` | 是 | 删，回 3 参 |
| 11 | `SpringTrail.dirX/dirY` | `SpringTrail.java:159-160` | 唯一读者 `CaretAnimator:2907` | 删 |
| 12 | `SpringTrail.setTarget` 方向记录块 | `SpringTrail.java:199-209` | 是 | 删 |
| 13 | `SpringTrail.config` 字段 | `SpringTrail.java:156-157` | 骨架 #14 后变死字段（仅构造器传参给 TrailCorner） | 删字段与赋值，构造器形参保留 |
| 14 | `assignRanks` 的 leadingSnap 覆盖块 | `SpringTrail.java:351-372` | — | **替换**（见二） |
| 15 | `AnimationPreview.dirX/dirY/lastPushX/lastPushY/pushSeen` | `AnimationPreview.java:282-284` | 是 | 全删 |
| 16 | `pushTarget` 方向记录块 | `AnimationPreview.java:709-722` | 是 | 删 |
| 17 | `pushTarget` 的 leadingSnap 覆盖块 + `lastRanks` | `AnimationPreview.java:776-783`、`:279` | `lastRanks` 只写不读 | **替换覆盖块**；`lastRanks` 连 `arraycopy` 删（四-J④） |
| 18 | `paintComponent` 的 5 参 addQuad | `AnimationPreview.java:966-971` | 是 | 改 3 参 |
| 19 | `setLeadingSnap` 里的 `panel.setHalfTrail` + 四角同长 | `AnimationPreview.java:293-303` | — | **替换**为「只存标志 + repaint」 |

**必须保留**：`CaretTrailPanel.dirX/dirY`（辉光外扩）、`TrailCorner.setAnimationLengthForTrail`（新逻辑两处调用）、面板开关与 `CaretAnimator.leadingSnap` 字段。

---

## 二、新时长逻辑

### 2.1 TrailCorner 新增（插在 `animationLengthForProbe` 之后）

```java
/** 最近一次 jump 是否走了「单行短跳」分支（半边拖尾只在短跳时改时长）。 */
public boolean lastJumpWasShort() { return lastShortJump; }

/** 半边拖尾尾巴的最长时长（秒）：rank 0 用它，rank 1 用它的 2/3。 */
public static final double HALF_TRAIL_SECONDS = 0.02;
public static final double HALF_TRAIL_MID_RATIO = 2.0 / 3.0;

/** 半边拖尾：rank → 该角的动画时长（秒）。rank 2/3 = 0（当帧精确到位）。 */
public static double halfTrailDurationForRank(int rank) {
    if (rank <= 0) return HALF_TRAIL_SECONDS;
    if (rank == 1) return HALF_TRAIL_SECONDS * HALF_TRAIL_MID_RATIO;
    return 0.0;
}
```

### 2.2 SpringTrail.assignRanks（替换 :351-372）

```java
if (leadingSnap) {
    for (int k = 0; k < n; k++) {
        TrailCorner c = corners[order[k]];
        if (!c.lastJumpWasShort()) continue;   // 跨行分支完全不碰
        c.setAnimationLengthForTrail(TrailCorner.halfTrailDurationForRank(k));
    }
}
```

必须在 jump 循环之后（jump 末尾 :405-406 会覆盖时长）。

### 2.3 为什么不放进 jump 内部

1. 开关归属：`leadingSnap` 是设备级 State，由 `CaretAnimator.syncGlobalSettings()` 推送；放进 jump 要给每个角再加 setter 与新建 trail 时的补推，多两条会漏的手写链路。
2. 职责分层：jump 是「按配置算物理时长」，半边是分配策略，归调用方。
3. 跨行隔离：jump 的跨行分支保持原样，策略不进去搅。
4. 可测性：rank→时长是纯函数；短跳判据由 jump 自己的 `lastShortJump` 记录，两侧调用方都不复刻（陷阱 3）。

### 2.4 AnimationPreview

```java
if (leadingSnap) {
    for (int i = 0; i < corners.length; i++) {
        if (corners[i].lastJumpWasShort()) {
            corners[i].setAnimationLengthForTrail(TrailCorner.halfTrailDurationForRank(ranks[i]));
        }
    }
}
```

`setLeadingSnap` 只 `leadingSnap = on; repaint();`。

### 2.5 rank→角 映射（探针期望）

右移 DX=12：TL=0、BL=1、TR=2、BR=3 → 角序 TL/TR/BR/BL 时长 `[TAIL, 0, 0, MID]`。
左移镜像：`[0, TAIL, MID, 0]`。

---

## 三、尾巴常量

- `TrailCorner.HALF_TRAIL_SECONDS = 0.02`、`HALF_TRAIL_MID_RATIO = 2/3`；不进 `NeovideConfig`（D0）。
- 实测（dt=8ms）：后缘偏移 12 → 6.30 → 2.05 → 0.57 → 0.30px，约 3 帧收敛。
- 实机嫌长/短只改 `HALF_TRAIL_SECONDS` 一个数。

---

## 四、置灰（与任务书一致的最小范围）

新增两个派生属性（**不改变残影模式下 short/smooth 原有行为**）：

```java
private final GraphProperty<Boolean> leadingSnapOn = graph.property(Boolean.FALSE);
/** shortAnimationLength / typingSmoothness 的可用性：只受前缘贴合影响。 */
private final GraphProperty<Boolean> trailShapeEditable = graph.property(Boolean.TRUE);
/** stretchRatioK / slopeTicks：原 enabledIf(springMode) 之上再叠加前缘贴合。 */
private final GraphProperty<Boolean> trailShapeSpringEditable = graph.property(Boolean.TRUE);

private void refreshTrailShapeGraying() {
    boolean on = !leadingSnapOn.get();
    trailShapeEditable.set(on);
    trailShapeSpringEditable.set(on && springMode.get());
}
```

写入时机（**四处，缺一不可**）：
- `refreshPreviews()` **第一行**（在 `previewConfig == null` 早退之前）——覆盖用户点击、探针直接调 `refreshPreviewsForProbe()`、以及 `refreshPresetDisplay → afterPendingEdited` 全部路径。
- `graphReset(s)`：`leadingSnapOn.set(s.leadingSnap); refreshTrailShapeGraying();`（覆盖 createPanel / reset / syncAllControlsFromPending）。
- `leadingSnap` 复选框 setter：`pending.leadingSnap = v; leadingSnapOn.set(v); refreshTrailShapeGraying();`。
- 拖尾模式下拉 listener：`springMode.set(...); refreshTrailShapeGraying();`。

四个控件：
- `shortAnimationLength`（:1437）、`typingSmoothness`（:1442）→ `enabledIf(trailShapeEditable)`；
- `stretchRatioK`（:1453）、`slopeTicks`（:1465）→ `enabledIf(trailShapeSpringEditable)`；
- 四条 rowComment 各追加一句「前缘贴合开启时不生效」。
- `animationLength` / `trailSize` 不动。

---

## 五、探针改动（VLeadingSnap）

| 断言 | 新期望 |
|---|---|
| L-2（关） | 不变：左 2.11 / 右 4.06 |
| L-1（开，slope=100） | 右边缘精确 0；左边缘 ≈ 2.60（首跑实测后钉 ±0.05） |
| L-1b（开，四角时长） | 右移 `[TAIL, 0, 0, MID]` 逐位精确 |
| L-12（左移） | 左边缘 0、右边缘 >1.0；时长 `[0, TAIL, MID, 0]` |
| L-3 / L-3a | 不变 |
| L-13a | 不变 |
| L-13b（开，出厂默认） | 前缘首帧位移 == 0，后缘 ≈ 10.0（>5.0） |
| L-17（新增） | 跨行开/关四角时长逐位相等（`[0.2, 0.15, 0.1, 0.1]`） |
| L-18（新增） | 开启时 slope=100 与 slope=0 得到同一组四角时长 |
| L-14（预览） | `[TAIL, 0, 0, MID]`；关闭时无 0 |
| L-16 | 不变 |
| G-1..G-4（新增） | 置灰：pending 打开 + refresh → 四滑块 disabled；关闭 → 恢复；`animationLength`/`trailSize` 仍 enabled |
| 其余 L-4..L-11、L-15 | 不变 |

置灰断言**不改 VUi**：用 `conf.pendingForProbe().leadingSnap = true; conf.refreshPreviewsForProbe();` 驱动，再按 key 读 `isEnabled()`。

变异测试：
1. 删 `SpringTrail.assignRanks` 覆盖块 → L-1/L-1b/L-12/L-13b/L-14/L-16 红。
2. 删 `AnimationPreview.pushTarget` 覆盖块 → L-14/L-16 红。
3. 去掉 `lastJumpWasShort()` 守卫 → L-17 红。
4. （附加）删 `refreshPreviews()` 的置灰刷新 → G-2/G-3 红。

---

## 六、风险与边界

- 静止：后缘约 3 帧收敛到 <0.5px，尾巴消失，无「光标变粗」残留。
- 吞字符：最大覆盖 = 首帧 6.30px < 12px 字符宽，约 24ms；旧模型 18px × 110ms+。
- 低帧率 dt ≥ 20ms：后缘也当帧归零，等于全即时贴合，可接受（不加 min 帧补偿）。
- 多光标：逐 trail 独立；辉光改由后缘偏移驱动，实机确认，不顺手改辉光曲线。
- 跨行：lastJumpWasShort 守卫 + L-17。
- 默认路径：关闭时零变化（L-2/L-3/L-13a）。
- D0：常量进 TrailCorner，`NeovideConfig` 零 diff；预设三件套不动。

---

## 七、验证命令

```powershell
git status --porcelain
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo D:\deepseekwork\neovim-cursor-pr
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
grep -rn "halfTrail\|HALF_TRAIL_CAP_WIDTHS\|quadDirs\|fillHalfTrail\|setHalfTrail" src tools --include=*.java
```
