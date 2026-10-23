# Neovide Cursor for JetBrains — 交接与项目总结

> **给上游（yyyyolo7a79-sketch）**：这份文档是本 fork 的完整交接。
> 原作者说「我已经燃尽了」——这份记录了一个用户花了整整两天、跑了几十次构建、
> 用三名 AI 审计员交叉验证后，对这款插件的全部认知。
> 如果能帮到你少走一些弯路，就值了。

- **Fork 仓库**：`pawamamama/neovim-cursor-for-jetbrains`
- **归档分支**：`feat/settings-ui-ready`
- **上游基线**：`6bed09d`（v0.5.0）
- **最终版本**：`v0.9.0-ahf-default`（哈希 `FA161E4F77C8C999`）

---

## 一、一句话结论

**插件本身不慢，慢的是 EDT 调度。**
在 Swing 覆盖层架构下，能做的优化已经做完。
要实质改善，只能**改架构**（全局 RenderScheduler）或**接受现状**。

**决定性数据**（插件自己的日志）：

```
delay=8ms（调度器正常）   maxGap=358ms   peakRepaintArea=5494px
delay=8ms                 maxGap=399ms   peakRepaintArea=4958px
delay=8ms                 maxGap=121ms   peakRepaintArea=4422px
```

- `delay=8ms` → 调度器每 8ms **都正确投递了任务**
- `maxGap=120~400ms` → **投递出去的任务在 EDT 队列里排了几百毫秒**
- `peakRepaintArea` 仅 4000~5000px → **重绘面积小，不是绘制慢**

---

## 二、已实测排除的方向（别再重复验证）

| 假设 | 实测数据 | 结论 |
|---|---|---|
| 绘制太慢 | 12 层辉光 **0.05ms/帧**（占 8ms 预算 0.6%）| ❌ |
| 重绘面积大 | 面积 157290px→141fps；面积 2345px→8.9fps | ❌ **面积大反而快 15 倍** |
| GC 停顿 | 最长 15ms，而卡顿记录是 900ms~31s | ❌ 差 60~2000 倍 |
| GPU 加速 | JBR 25 无 D3D/OpenGL 管线；光标仅 2×32=64 像素 | ❌ 技术不可行且无收益 |
| 插件占 EDT | 45 分钟 JFR（9641 采样）：插件 **3.26%** | ❌ 占比极低 |
| **IDEA 自身绘制** | `ScaledBlit.Scale` 占 EDT **12.6%**（与插件无关）| ⚠️ **这是大头** |
| 每编辑器一条线程 | JFR：`CaretAnimator.startScheduler` 占插件采样 **90%**（空转）| ⚠️ 见 P0 |

---

## 三、本 fork 相对上游 v0.5.0 的改动

### ✅ 采纳的（有证据支撑）

| 改动 | 理由 |
|---|---|
| **移除 `MAX_STEP_RATIO = 0.5` 单帧位移截断** | 大位移时每帧触发截断并清零速度 → 曲线切成锯齿 → 「删除到上一行走到二分之一处停顿」。上游 v0.5.0 也删了 |
| **移除 `MIN_ANIMATION_FRAMES` 6 帧补偿** | 它把四角时长抬成同一值、抹平梯度。⚠️ **但本 fork 后来在 12ms 基线上又加回了，见下方「争议」** |
| `glowLayers` 6 → **8** | 用户接受度最高（6 层台阶可见、12 层成本高） |
| **`hideNativeCaret` 真正生效** | 上游把它做成了死字段（`NeovideCaretManager` 无条件调 `applyNativeCaretHidden()`）。本 fork 改成读取配置 + 加菜单开关 |
| **`alwaysHighFps` 默认 true，去掉菜单项** | 用户实测「常驻刷新挡位非常舒适」；保留一个永远该开着的开关只会困惑 |
| **短跳判据改为 offset 差值** | 见下方「中文卡顿」 |
| fps 统计补 `maxGap` 字段 | 用于区分「真阻塞」与「统计口径失真」 |

### ⚠️ 争议 / 已知不完美

**1. `MIN_ANIMATION_FRAMES` 的去留**

- 上游踩坑 #20 说它是错的（抹平四角梯度）
- **但在 12ms 帧率下，它是「不开常驻刷新也能看见动画」的关键**
- 本 fork 最终在 12ms 基线上保留了它，配合 `trailFactors` 四档共存
- **建议**：如果帧率能稳定在 8ms，可以去掉；否则保留

**2. `ACTIVE_DELAY_MS` 的取值**

用户实测过 8ms / 11ms / 12ms：

| 值 | 用户评价 |
|---|---|
| 8ms（125fps）| 「傻快」「帧率不稳定」|
| 11ms | 「有点快」|
| **12ms（83fps）** | **「非常舒适」** ← 最终选择 |

**结论：12ms 是这个用户机器上的最佳值。** 但不同机器/字号可能不同。

**3. `hideNativeCaret = true` vs `false`**

- 上游踩坑 #19 明确说应保留原生光标（`false`）
- **实测：保留原生光标确实解决了「跟随延迟」**（原生光标由 IDE 零延迟绘制）
- **但用户反馈**：「原生光标跟上了 插件动画没跟上」——拖尾动画本身仍受 EDT 影响
- **本 fork 默认 `false`（保留原生光标），但提供菜单开关**

---

## 四、中文卡顿的根因与修复（**这是本 fork 最有价值的发现**）

### 根因

上游的短跳判据：

```java
double jumpX = (destX - previousDestX) / width;   // width = cursorWidth = 2px
double jumpY = (destY - previousDestY) / height;
double len = Math.hypot(jumpX, jumpY);
boolean isShortMove = len <= config.shortMoveThreshold;   // 8
```

**问题**：`jumpX = dx / 2px`，结果取决于「一行能装几个字符」：

| 操作 | dx | jumpX | 判定 |
|---|---|---|---|
| 英文 1 字符 | 10px | **5.0** | ✅ 短跳 |
| **中文 1 个字** | **20px** | **10.0** | ❌ **跨行分支** |

**跨行分支下 `trailSize = 1` 使 `leading = 0`**，即前缘时长为 0：

```java
double leading = animationLength * (1 - trailSize);   // = 0
if (rank >= 2) animationLength = leading;             // = 0
```

**而 `DampedSpring.update()` 开头有短路：**

```java
if (animationLength <= dt) { position = 0; velocity = 0; return false; }
```

**→ 前缘第一帧就贴死 → 表现为「中文卡、英文丝滑」。**

### 修复

**改用编辑器的原生字符偏移**（IntelliJ 标准做法）：

```java
// CaretAnimator
int offset = editor.getCaretModel().getOffset();
int charsMoved = Math.abs(offset - lastCaretOffset);
lastCaretOffset = offset;
corner.setCharsMoved(charsMoved);

// TrailCorner
boolean isShortMove;
if (charsMoved >= 0) {
    boolean sameLine = Math.abs(deltaYpx) <= height * 0.5;
    isShortMove = sameLine && charsMoved <= 2;
} else {
    isShortMove = len <= config.shortMoveThreshold;   // 兜底
}
```

**优点**：offset 差值是精确的字符个数，与字体、字号、全角半角、连字、emoji 全部无关。

### 走过的三条弯路（别重蹈）

| 尝试 | 为什么失败 |
|---|---|
| 硬编码 `16px` | 字号 20px 时中文就 20px，直接超出 |
| `FontMetrics.charWidth('m') × 2.5` | `'m'` 是西文推进量，中文是它的 2 倍；乘经验值只是换个魔数 |
| `visualPositionToXY(line,1) - visualPositionToXY(line,0)` | **取的是行首字符宽度**，光标在中文区时仍会翻倍 |

---

## 五、最重要的教训：**不要在缺版本管理的情况下连续叠加改动**

**这个 fork 犯了 8 次错，全部源于「凭直觉调参 + 没记录改了什么」：**

| 改动 | 后果 |
|---|---|
| `ACTIVE_DELAY_MS` 12 → 8ms | 一切都变快，用户说「傻快」|
| 删掉 `MIN_ANIMATION_FRAMES` | 低帧率下动画消失 |
| 硬编码 16px 短跳阈值 | 中文卡 |
| `recovery` 分支 `dt = rawDt` | **不开常驻刷新一帧走完位移 = 卡爆** |
| **分步推进（substep）** | **审计证明是「正确的空操作」，且更糟 38~46pp** |
| 把 `hideNativeCaret` 改成 true | 违反上游踩坑 #19 |

### 分步推进为什么无效（重要，别重试）

**假设**：把 120ms 切成 15 个 8ms 小步，就能像常驻刷新一样平滑展开。

**实测**：

```
一次 update(0.12)      = 6.828984590243152
分15次 update(0.008)   = 6.828984590243153
差 = 8.88e-16   ← 完全等价
```

**而 `updateCorners()` / `submitRepaint()` 在 steps 循环之外 —— 一次 tick 只画一帧，中间状态根本不渲染。**

**所以「15 步塞进 1 帧」和「1 步推进」对用户完全一样。分步唯一的作用是绕开短路，把「归零」变成「几乎归零」= 瞬移。**

**而且比修改前更差**（因为 `min(rawDt, MAX_DT)` 本来钳住了）：

| rawDt | 修改前 | 分步版 | 变化 |
|---|---|---|---|
| 120ms | 54.3% | **93.2%** | **-38.9pp** |
| 300ms | 54.3% | **100.0%** | **-45.7pp** |

---

## 六、建议的后续优化（优先级）

### P0：全局 RenderScheduler（**唯一有实质收益**）

**现状**：每个编辑器（含每个标签页、每个控制台）各开一条 `Thread.sleep` 线程。

**JFR 证据**：`CaretAnimator.startScheduler` 占插件采样 **90%**，全是空转。

**上游 v0.5.0 已实现**（`RenderScheduler.java`，111 行）：
所有编辑器共用一条线程，睡到最早的到期时刻，**只投递一次 `invokeLater`**，
在一个 EDT 任务里依次 tick 所有到期的 animator。

**收益**：EDT 投递次数从「N 个编辑器 × 125 次/秒」降到「125 次/秒」。

### P1：设置界面（用户明确要求）

**需要**：
1. `NeovideConfig` 实现 `PersistentStateComponent`（现在参数是纯内存单例，重启即丢）
2. 新建 `NeovideConfigurable`（UI 面板）
3. `plugin.xml` 注册 `<applicationConfigurable parentId="editor" .../>`

**这是标准做法，风险中等。** 用户会自己调参，不需要再一轮轮试。

### P2：踩坑 #6/#11（`maxTrailDistanceFactor` 60 → 4）

**现状**：`NeovideConfig:222 = 60f`（≈1920px）。

**影响**：仅跨屏跳转时的重绘包围盒。日常相邻行移动**完全不受影响**。

**上游结论要求收到 4（≈128px）。**

### P3：踩坑 #12（残影模式辉光用错方法）

`CaretTrailPanel.java:395-403` 用「居中描边」（`BasicStroke`+`drawRect`）做辉光，
是该条明确否定的做法。**仅影响残影模式。**

### P4：踩坑 #21（fps 统计补全）

缺 `FPS_IDLE_GAP_NANOS`（活动帧间隔 >200ms 重开窗口），
导致日志出现 `fps=0.2 / window=243474ms` 这类失真数据。**仅影响诊断。**

---

## 七、参数调优指南（给设置界面用）

| 用户感受 | 调这个 | 当前值 | 建议范围 |
|---|---|---|---|
| 单行拖尾太长 | `shortAnimationLength` | 0.11 | 0.05 ~ 0.15 |
| 拖尾不够明显 | `trailFactors[3]`（前缘）| 0.22 | 0.15 ~ 0.35 |
| 拖尾太飘 | `trailFactors[0]`（后缘）| 1.0 | 0.7 ~ 1.0 |
| 跨行拖尾长度 | `animationLength` | 0.10 | 0.08 ~ 0.15 |
| 辉光浓度 | `glowLayers` / `glowOpacity` | 8 / 0.55 | 6~12 / 0.3~0.7 |
| 光标宽度 | `cursorWidth` | 2 | 2 ~ 3 |
| 帧率 | `ACTIVE_DELAY_MS` | 8 | **12**（本用户最佳）|

---

## 八、文件清单

| 文件 | 内容 |
|---|---|
| `VERSIONS.md` | **37 个 jar 的完整对照表**（时间/参数/哈希）|
| `UPSTREAM-PITFALLS.md` | **上游 21 条踩坑 + 逐条对照当前源码**（672 行）|
| `FINAL-CONCLUSION.md` | 本 fork 的结论总结 |
| `BUGS-FOUND.md` | 已确认的真 bug 清单 |
| `diag/AUDIT-CAPTAIN-NOTES.md` | 审计期间自查记录 |
| `build/offline-build.ps1` | 离线构建（381 jar classpath，无需 Gradle）|
| `build/switch-version.ps1` | 版本切换 |
| `build/rollback-080.ps1` / `rollback-071.ps1` / `rollback-uni.ps1` | 一键回退 |

---

## 九、离线构建方法（本机没有可用 Gradle）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1" `
  -Version 0.9.1 -OutName "my.jar" `
  -BaseJar "D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.4.8-fullfix.jar"
```

**关键点**：
- 用官方 jar 作为**容器**，只替换变动的 class
- 不能从零打包（官方 class 经过 IntelliJ 插桩，全量替换会丢插桩）
- 安装前**必须完全退出 IDEA**，且要 **Move 旧的再 Copy 新的**（直接覆盖会报「用户映射区域」错误）

---

## 十、给上游的话

**这款插件的核心算法（`DampedSpring` / `TrailCorner`）设计得很漂亮**，
问题不在算法，在于 **Swing 覆盖层 + EDT 调度**这个平台限制。

**三个最有价值的建议：**

1. **`hideNativeCaret` 是死字段** —— `NeovideCaretManager` 无条件调
   `applyNativeCaretHidden()`，配置形同虚设。而隐藏原生光标会改写全局配色
   `CARET_COLOR`，是**有落盘副作用**的操作（踩坑 #18 的根源）。

2. **短跳判据对 CJK 有系统性缺陷** —— `dx / cursorWidth` 让中文（全角 2 倍宽）
   落入跨行分支，前缘时长 0 → 短路瞬移。改用 `CaretModel.getOffset()` 差值即可根治。

3. **`effectiveDt` 是空转链路** —— `assignRanks(double dt)` 方法体内**一次都没用到 `dt`**，
   弹簧推进用的是真实 `dt`。所谓「低帧率补偿」当前没有任何行为效果，
   是反复救火留下的空壳，建议删掉或真用起来。

**愿你能把它做得更好。这个用户为它花了整整两天。**
