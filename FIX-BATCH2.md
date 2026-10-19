# FIX-BATCH2.md —— 批次2 契约（冻结版）

> 状态：**FROZEN**
> 基线：`git HEAD = 9f8ccb7`（`feat/settings-ui-ready`）
> 当前安装：`0.9.1-zeroopt`（`C718675E046CAB82…`）
> 产出：`0.9.2-imetime-fade`
> 契约作者：captain（原 reviewer 因 provider 限流不可用；契约冻结不影响独立性，独立验证由 subagent 承担）

---

## 0. 本批要做的三件事（一句话版）

| 编号 | 修什么 | 用户原话依据 |
|---|---|---|
| **F1** | 中文输入法组合态时，覆盖层光标卡在半个字形处 | "光标会卡在竖那一个笔画上" |
| **F2** | 停止输入后拖尾**硬切**消失；移植上游淡出，并做成开关 | "淡出做开关" |
| **F3** | `hideNativeCaret` 默认改为 `true`（默认勾选「隐藏原生光标」） | "以后默认勾选" |

---

## 1. 基线事实（已逐项核实，非记忆）

| 事实 | 验证方式 | 结果 |
|---|---|---|
| 我们的 fork **没有** `lastAnimatingNanos` | `grep lastAnimatingNanos src` | **0 命中** |
| 我们的 fork **没有** `overlayAlpha()` 方法 | 同上 | **0 命中** |
| `CaretTrailPanel` **没有** `AlphaComposite` | `grep AlphaComposite` | **0 命中** |
| 上游 `6bed09d` 有完整淡出实现 | `git show origin/main:.../CaretAnimator.java` | L607 / L630-640 |
| 上游淡出**分成两条分支**（按 `hideNativeCaret`） | 同上级码 | **关键，见 §3.2** |

---

## 2. F1：IME 组合态光标错位（两层）

### 2.1 现象复述（用户实测，用作验收）

- 中文输入法组合态（拼音已输入、候选未选）时，覆盖层光标偶尔卡在某字的**半个字形**处
- **滚动编辑器：无效果**
- **切标签页再切回：无效果**
- **改窗口大小：恢复**
- **继续输入一个字符：立即恢复，且落到行尾**
- 不稳定复现（约"10 字以上、一半概率"），**纯视觉**，不影响文本/真实光标/点击位置

### 2.2 F1-a（必做）：caret 事件强制失效缓存

**目标文件**：`ui/CaretAnimator.java`，方法 `caretPositionChanged`（基线约 L509）

**允许的 diff**：仅新增一行 `forceResync = true;`

```java
@Override
public void caretPositionChanged(@NotNull CaretEvent event) {
    forceResync = true;      // ← 新增
    wakeUp();
}
```

**明确禁止**：
- 不得把 `wakeUp()` 删掉或改顺序（`forceResync` 必须在 `wakeUp()` 之前或之后都行，但两者都要在）
- 不得在 `caretAdded` / `caretRemoved` 里也加（本批不做多光标）

**判定**：用户手测 —— 错位后**滚动编辑器**应恢复（现在不恢复）。

### 2.3 F1-b（必做）：用 caret offset 交叉校验跳过中间态

**目标文件**：`ui/CaretAnimator.java`，方法 `refreshTarget()`（基线约 L804-888）

**位置要求**：必须在**快速路径之后**（`forceResync` 检查之后）、`visualPositionToXY` 调用**之前**。

**判据逻辑**：

```
if (!forceResync
    && lastCaretOffset >= 0
    && 当前 getOffset() == lastCaretOffset      // 真实插入点没动
    && vp.column != lastVisualColumn)           // 但视觉列变了
=> 判定为 IME 组合中间态：本帧 return false，且【不得】更新 lastVisualColumn
```

**允许的 diff 范围**：仅在该位置插入上述判断块（约 6-10 行，含注释）。

**明确禁止**：
- 不得修改 `update()/jump()/snapTo()` 或任何弹簧/几何算法
- 不得改变 `visualPositionToXY` 的调用方式
- 不得新增**每帧**额外的 `getOffset()` 调用 —— `getOffset()` 已在同方法内被调用（供 `charsMoved` 用），**必须复用那一次的结果**，不得重复调用
- 不得影响 `charsMoved` 的现有计算

**边界（必须逐条满足，验证方要专门测）**：

| 场景 | 期望 |
|---|---|
| 首次调用（`lastCaretOffset < 0`） | **不得**误判为中间态，必须正常走完首次定位 |
| `forceResync == true`（窗口尺寸变化） | **优先放行**，必须重算，不得被 F1-b 拦截 |
| 正常移动光标（offset 与 vp 同时变） | 正常放行 |
| IME 组合中（offset 不变、vp 变） | 跳过本帧，**不更新** `lastVisualColumn` |
| 组合结束（offset 与 vp 同时变） | 正常放行，落到正确位置 |

**判定**：用户手测 —— 组合态错位的**瞬间**也不应画错（或至多一帧不掉）。

---

## 3. F2：辉光淡出（方案 B —— 只淡辉光，不淡光标本体）

> **⚠️ 本节已于 2026-10-19 改版**：原契约照搬上游「整体覆盖层淡出」，但发现它与 F3 冲突 ——
> `hideNativeCaret=true` 时上游会 `setOverlayAlpha(1f)` 永不淡出（因为覆盖层就是光标本体，
> 淡出等于光标消失）。用户明确要 `hideNativeCaret` 默认 `true`，这会导致**淡出默认不可见**。
>
> **改为方案 B**：只对**辉光层**做淡出，光标本体始终不透明。这样默认档也能看到淡出，
> 且不会出现「光标自己消失」。**这是有意偏离上游实现，必须保留光标本体不淡出。**

### 3.0 为什么方案 B 更对（设计依据）

| 问题 | 方案 A（上游）| 方案 B（本批）|
|---|---|---|
| `hideNativeCaret=true` 时 | 永不淡出（光标=覆盖层） | **辉光照常淡出**，光标主体保持 |
| 静止时「一圈辉光像两个光标」 | 不解决（`glowStrength` 已归 0 但硬切） | **辉光柔和熄灭**，正好治这个 |
| 违背上游意图？ | — | 否。上游那条分支防的是「光标消失」，我们只淡辉光，光标不消失 |

**关键结构**：`paintQuad()` 内部本来就是两步 —— `drawQuadGlow(g2, polygon)` 画辉光，然后
`g2.setColor(fillColor); g2.fillPolygon(polygon)` 画光标本体。**只要给前者乘 alpha、后者保持 1.0 即可**，
不需要动 `paintComponent` 的全局 composite（那样会连光标一起淡）。

### 3.1 数值（与上游 `6bed09d` 一致，不得自行调整）

| 字段 | 类型 | 值 | 说明 |
|---|---|---|---|
| `cursorDisappearDelay` | `int` | `50` | 拖尾停止后，辉光保持满强度的毫秒数 |
| `cursorFadeOutDuration` | `float` | `0.075f` | 之后线性淡出的秒数（75ms） |
| `overlayFadeOut` | `boolean` | `true` | **新增开关**，默认开 |

> 这三个是本批**唯一**允许新增的字段与数值。其余观感常量一律不得变（见 §5）。

### 3.2 淡出只作用于辉光（**不是**整体 overlayAlpha）

**`CaretTrailPanel` 新增**：

```java
/** 辉光淡出系数（0~1），只乘在 drawQuadGlow 的图层 alpha 上，不影响光标本体 */
private float glowFade = 1f;

public void setGlowFade(float fade) {
    this.glowFade = fade < 0f ? 0f : (fade > 1f ? 1f : fade);
}
```

**`drawQuadGlow()` 内**：把峰值不透明度乘上 `glowFade`（**唯一改动的算式位置**）：

```java
// 原有
float peak = config.glowOpacity * (glowColor.getAlpha() / 255f) * glowStrength;
// 改为
float peak = config.glowOpacity * (glowColor.getAlpha() / 255f) * glowStrength * glowFade;
```

**光标本体不动**：`paintQuad()` 里 `g2.setColor(fillColor); g2.fillPolygon(polygon);` **一字不改**。

**明确禁止**：
- 不得在 `paintComponent` 上设全局 `AlphaComposite`（那会连光标本体一起淡 → 违背方案 B）
- 不得改 `drawQuadGlow` 的**几何算式**（角点、外扩方向、层数、半序、`Math.pow` 反解）—— 只允许在上面的 `peak` 表达式里多乘一个因子
- 不得改 `glowLayers` / `glowOpacity` / `shadowBlurFactor` / `trailFactors`
- 残影模式（`AFTERIMAGE`）本批**不接入**淡出（保持现状）

### 3.3 `overlayAlpha()` → 改名 `glowFade()`，逻辑对齐上游

```java
/**
 * 辉光淡出系数：拖尾停止后先保持 delay，再线性淡出到 0。
 * 只作用于辉光层；光标本体始终不透明（见 drawQuadGlow）。
 */
private float glowFade(long now) {
    if (!config.overlayFadeOut) {
        return 1f;                     // 开关关闭：等价 0.9.1 行为（不淡出）
    }
    if (lastAnimatingNanos == 0L) {
        return 0f;                     // 尚未运动过：无辉光（与现有 glowStrength 语义一致）
    }
    double sinceStop = (now - lastAnimatingNanos) / 1_000_000_000.0
            - config.cursorDisappearDelay / 1000.0;
    if (sinceStop <= 0) {
        return 1f;                     // 停止后 delay 内：保持满强度
    }
    double fade = config.cursorFadeOutDuration;
    if (fade <= 0 || sinceStop >= fade) {
        return 0f;                     // 淡出完毕
    }
    return (float) (1.0 - sinceStop / fade);
}
```

### 3.4 `tickSpring` 尾部接入（**与上游不同，必须按此改**）

上游那段 `if (hideNativeCaret) setOverlayAlpha(1f)` 的分支结构**本批不采用**。改为：

```java
// tickSpring 尾部（在 pushQuads / computeTrailBounds / submitRepaint 之前）
if (anyAnimating) {
    lastAnimatingNanos = now;
}
float fade = glowFade(now);
panel.setGlowFade(fade);

// 淡出期间必须继续 tick，否则最后一帧不刷新 → 辉光残留
boolean fading = config.overlayFadeOut && fade < 1f;
...
return anyAnimating || fading;
```

**必须保证的三点**：

1. **`lastAnimatingNanos` 只在 `anyAnimating` 时更新** —— 不是每帧更新。
2. **返回值必须带 `fading`**，否则调度器会在淡出完成前降频到 120ms，导致辉光残留不刷掉。
3. **`fade == 0` 后不必再 clearCorners**（与上游不同）：因为辉光没画出来，光标本体还要继续常驻绘制
   （默认档 `hideNativeCaret=true` 时光标本体必须一直可见）。**这一点与上游相反，务必不要照抄上游的
   `if (alpha <= 0f) { clearCorners(); return false; }`**。

### 3.5 新建字段

`CaretAnimator` 新增：
```java
/** 最近一次"仍在运动"的时刻（纳秒）；0 表示从未运动过。仅用于辉光淡出 */
private long lastAnimatingNanos = 0L;
```

### 3.6 判定

- **用户手测（默认档）**：停止输入后，**辉光应柔和熄灭**（约 125ms），而**光标本体始终可见不闪**
- **用户手测（开关关闭）**：行为应与 0.9.1 完全一致（辉光瞬间归零）
- **代码级**：`overlayFadeOut=false` 时 `glowFade()` 恒返回 1f，parity 应一致

---

## 4. F3：`hideNativeCaret` 默认值改 `true`

**目标文件**：`core/NeovideConfig.java`

```java
public boolean hideNativeCaret = true;   // 原为 false
```

**javadoc 必须写明（不得省略）**：

> 本项**有意偏离上游踩坑 #19**。上游认为隐藏原生光标后，跟随完全依赖调度器，空闲降频时会"停手后第一次操作卡一下"。但本机实测相反：`alwaysHighFps = true` 使调度恒为 8ms，隐藏原生光标后由覆盖层常驻绘制**反而更跟手**；而保留原生光标时用户反馈"取消勾选后卡一点"。故默认改为 `true`。仍可通过 Tools 菜单手动取消。

**明确禁止**：
- 不得删除 Tools 菜单里的开关（`ToggleHideNativeCaretAction`）
- 不得改 `alwaysHighFps` 的默认值
- 不得改 `setHideNativeCaret` / `install` 的任何逻辑（只改默认值 + javadoc）

**判定**：用户手测 —— 首次启动（或重置配置后）菜单项应为**已勾选**。

---

## 5. 禁止清单（命中即 reject）

### 5.1 数值白名单 —— 本批只允许新增三项

| 项 | 允许值 |
|---|---|
| `overlayFadeOut` | `true`（新字段） |
| `cursorDisappearDelay` | `50`（恢复） |
| `cursorFadeOutDuration` | `0.075f`（恢复） |

**除此之外，以下全部不得变动**（逐项核对）：

`ACTIVE_DELAY_MS=8`、`IDLE_DELAY_MS=120`、`IDLE_UNFOCUSED_DELAY_MS=600`、`MAX_ADAPTIVE_DELAY_MS=40`、`MAX_DT=1.0/20`、`RECOVERY_GAP_SECONDS`、`RECOVERY_STEP_CAP_SECONDS`、`STATS_WINDOW_NANOS=5_000_000_000L`、`REFRESH_TARGET_SLOW_NANOS=2_000_000L`、`GLOW_FADE_START=0.5`、`GLOW_FADE_END=2.5`、`glowLayers=8`、`glowOpacity=0.55f`、`shadowBlurFactor=0.2f`、`animationLength=0.1f`、`shortAnimationLength=0.11f`、`trailSize=1f`、`trailFactors={1.0,0.72,0.42,0.22}`、`typingMaxChars=3f`、`shortMoveThreshold=8f`、`animationResetThreshold=0.09f`、`maxTrailDistanceFactor=60f`、`cursorWidth=2f`、`afterimageLifetime=0.22f`、`alwaysHighFps=true`、`useShadow=true`、`tailOpacity=1.0f`、颜色值、`RenderScheduler` 的 W3 三项。

### 5.2 绝对禁止

| 编号 | 禁止项 |
|---|---|
| **P0-1** | 把多个 animator 合并进同一个 EDT 任务（保持每 animator 各自 `invokeLater`） |
| **P0-2** | 坐标 `int → double` |
| **P0-3** | 改 `TrailCorner.jump()` / `DampedSpring.update()` / `computeNextDelay()` 的任何逻辑 |
| **P0-4** | 改 `ui/RenderScheduler.java` 的调度逻辑与常量 |
| **P0-5** | 在每帧热路径新增 `getOffset()` / `getVisualPosition()` 调用（F1-b 必须复用已有的） |
| **P0-6** | 为"让淡出可见"而改变 §3.2 的两分支结构 |
| **P0-7** | 本批引入设置界面（`PersistentStateComponent` / `NeovideConfigurable`） |
| **P0-8** | 改 `build/analyze-log.ps1` 与 `diag/extract.py`，或改 fps 日志格式 |
| **P0-9** | 改残影模式（`AfterimageTrail` / `paintGhosts`）的任何行为 |

---

## 6. 验证口径

### 6.1 可自动化（必须做，出证据）

| 项 | 方法 | 通过标准 |
|---|---|---|
| 编译 | `build/offline-build.ps1` | 4/4 步通过 |
| 物理/几何未变 | `tools/parity/run-parity.ps1` | 三份 harness 的 base 与 new **逐字节相同** |
| 常量审计 | `tools/parity/run-audit.ps1` | 19 项 DIFF = 0；新增仅 §5.1 三项 |
| 调度语义 | 同上 E 段 | `invokeLater` 调用点仍 = 1 且在 per-animator 循环内；`new Thread` = 1 |
| 开关等价性 | 构建两版（`overlayFadeOut` true/false） | 关闭档的 parity 必须与 0.9.1 一致 |
| jar 内容 | 解包逐文件哈希 | 交付 jar 与已验证产物一致 |

> **⚠️ F1-b 的 parity 说明**：F1-b 改变了"何时重算坐标"，但**不改变坐标换算方式与弹簧算法**。因此 parity harness（它直接驱动 `TrailCorner`/`DampedSpring`，不经过 `refreshTarget`）应**继续逐字节一致**。若发现不一致，说明误改了 core 包 → 直接 reject。

### 6.2 不可自动化（必须由用户手测，写进交付说明）

| 项 | 手测步骤 |
|---|---|
| F1-a | 触发错位 → **滚动编辑器** → 应恢复 |
| F1-b | 中文组合态反复输入 → 观察是否仍有半字形错位 |
| F2 默认档 | 默认（已勾选隐藏原生光标）→ 停止输入 → 观感应与 0.9.1 **无差别** |
| F2 开关档 | Tools 取消勾选隐藏原生光标 → 停止输入 → 应看到约 125ms 淡出 |
| F3 | 首次启动 / 重置配置 → 菜单项应为**已勾选** |

### 6.3 回退

| 目标 | 路径 | 哈希前 16 位 |
|---|---|---|
| 0.9.1 | `dist\jetbrains-neovide-cursor-0.9.1-zeroopt.jar` | `C718675E046CAB82` |
| 0.9.0 | `dist\jetbrains-neovide-cursor-0.9.0-ahf-default.jar` | `FA161E4F77C8C999` |

本批需新增 `build/rollback-0.9.2.ps1`，回退到 **0.9.1**。

---

## 7. 本批明确不做（防止范围膨胀）

- 淡出的**多参数版本**（用户说"多做几版"，属**下一轮**）
- 参考其他项目的光标动效 / UI 库（**下一轮**，用户明示"bug 修完了再做"）
- `BUSY_RETRY_MS 1→0`（EDT 繁忙时 −3.4% 的取舍，未获授权）
- 多光标（`SpringTrail` 移植）
- 控制台光标行为（`tickStaticCaret`）
- 设置界面
- 任何 refactor / 重命名 / 改注释措辞（除 §4 要求的 javadoc）
- 更新 README / HANDOFF 文档

---

## 8. 已知风险（诚实声明）

| 风险 | 说明 | 缓解 |
|---|---|---|
| **F1-b 可能拦掉正常帧** | 判据依赖"offset 不变但 column 变"。若平台在某些正常场景下也出现这种组合（如软换行重排），会误跳过一帧 | 验证方须构造：软换行、折叠代码、注入语言块、行尾换行符等场景确认不误判 |
| **F3 让淡出默认不可见** | 因为 `hideNativeCaret=true` 走"永不淡出"分支 | 这是上游设计，已写入 §3.2；用户可在 Tools 取消勾选后看到淡出 |
| **淡出增加重绘期** | 停止后要多 tick 约 125ms 才完全退出 | 已验证 `fading` 参与返回值；实际开销需实测（停止后本就低负载） |
| **F1 无法自动化** | 需要真实中文输入法 | 只能用户手测 |

---

## 9. 契约指纹

- 基线 HEAD：`9f8ccb7`
- 契约文件本身：`FIX-BATCH2.md`（本文件）
- 上游参考：`git show origin/main:src/.../CaretAnimator.java`（`6bed09d`）
- 前序契约：`OPTIM-BATCH1.md`（批次1，FROZEN）
