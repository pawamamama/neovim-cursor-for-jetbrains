# 后续方向调研（2026-10-23）

> 目的：回答「怎么把这个插件做成可扩展效果框架」
> 方法：读本仓库真实源码（不是印象）+ 上游对照 + 现状测量

---

## 一、现状：哪部分是"效果"，哪部分是"框架"

读过 `CaretAnimator`(1458行) 的 28 个方法后，职责边界很清楚：

| 分类 | 方法 | 说明 |
|---|---|---|
| **框架**（与效果无关） | `runTick` / `scheduleNextDue` / `computeNextDelay` / `wakeUp` / `repaintRegion` / `computeTrailBounds` / `submitRepaint` / `recordFrameStats` / `logSlowRefresh` | 调度、重绘、脏区、统计 |
| **框架**（取光标位置） | `refreshTarget` / `isInVirtualSpace` / `caretPositionChanged` | 从编辑器拿坐标 |
| **效果**（可替换） | `tickSpring` / `tickAfterimage` / `tickStaticCaret` | 三种渲染形态 |
| **效果内部**（物理） | `assignRanks` + `TrailCorner` + `DampedSpring` | 四角弹簧 |
| **绘制** | `CaretTrailPanel` | 四边形填充 + 辉光 |

**结论：效果层已经有三份实现**（弹簧 / 残影 / 静态），它们靠 `config.trailMode` 分支。
这证明"多效果"在架构上已经存在，缺的是**统一接口**与**注册机制**。

---

## 二、现有三份效果的依赖清单（实测）

### tickSpring 依赖
```
corners[]          四个 TrailCorner（物理状态）
cursorWidth/Height 光标尺寸
centerX/centerY    目标中心
dt                 帧间隔
panel.*            绘制出口：
  setGlowStrength(float)
  setGlowFade(float)
  updateCorners(x0,y0,...,x3,y3)
config.overlayFadeOut
```

### tickAfterimage 依赖
```
afterimageTrail    残影采样缓冲
centerX/centerY/cursorWidth/cursorHeight
now                时间戳
panel.updateGhosts(List<float[]>)
ghostBuffer        复用缓冲（避免每帧分配）
```

### tickStaticCaret 依赖
```
corners[] + snapTo()     直接吸附
panel.setGlowStrength(0) 不画辉光
panel.updateCorners(...)
```

**共同点**：三者都只做两件事 —— ① 推进自身状态 ② 通过 `panel` 输出要画的东西。
**差异点**：输出的数据形态不同（四角 vs 残影列表）。

---

## 三、最小效果接口（建议）

```java
interface CursorEffect {
    /** 效果名（配置里用、菜单里显示） */
    String id();

    /** 切到本效果时调用一次（清状态、分配缓冲） */
    void onActivate(EffectContext ctx);

    /** 切走时调用（释放、清绘制） */
    void onDeactivate();

    /**
     * 推进一帧。
     * @return true = 仍在运动，需要继续调度
     */
    boolean tick(EffectContext ctx, double dt, long now);

    /** 把当前状态交给绘制层（panel） */
    void render(EffectContext ctx);

    /** 是否需要在光标静止时继续跑（弹簧需要，静态光标不需要） */
    boolean needsIdleTicks();
}
```

`EffectContext` 是只读快照 + 绘制出口：
```java
interface EffectContext {
    double cursorWidth();
    double cursorHeight();
    double centerX();
    double centerY();
    NeovideConfig config();
    TrailPanelSink panel();   // 窄接口：updateCorners / updateGhosts / setGlow*
}
```

**为什么用 `TrailPanelSink` 而不是直接给 `CaretTrailPanel`**：
现在 `CaretTrailPanel` 有 450 行、10+ 个公开方法，效果只用到其中 4-5 个。
窄接口能让"效果能画什么"有边界 —— 否则新效果会不断往 panel 里加方法，又变回上帝对象。

---

## 四、三步改造路线（每步独立可交付）

### 第 1 步：抽接口，不改行为（风险低）

- 新增 `CursorEffect` + `EffectContext` + `TrailPanelSink`
- 把现有三份 `tickXxx` **原样搬进** 三个实现类（代码逐行复制，不改逻辑）
- `CaretAnimator` 改成 `effects.get(config.trailMode).tick(...)`
- **判据**：`run-t9-full` 12 段全绿 + parity 逐字节一致（因为逻辑一字未改）
- **风险**：低。这是纯搬家，行为零变化。

### 第 2 步：注册机制（风险低）

- 新增 `CursorEffectRegistry`（`Map<String, Supplier<CursorEffect>>`）
- `NeovideConfig.TrailMode` 这个枚举 → 换成 String effect id
  （或保留枚举但加 `effectId()`，老配置能迁移）
- **判据**：现有 3 个效果通过注册表取到，UI 下拉从注册表生成
- **风险**：**中** —— 要动 `NeovideConfig` 的枚举，涉及持久化迁移。
  这一步必须单独一轮做，且要有迁移测试。

### 第 3 步：开放第三方（风险中高）

- 用 IntelliJ 的 `EP`（Extension Point）机制：
  `plugin.xml` 声明 `<extensionPoint name="cursorEffect" .../>`
- 效果实现者只需实现接口 + 注册一行
- **判据**：写一个"只用新接口"的 demo 效果，能不改核心就跑起来
- **风险**：中高。要处理"第三方效果崩溃不能拖垮编辑器"（try/catch 边界、超时）。

---

## 五、关键设计决策（需要用户拍板）

| # | 问题 | 选项 | 我的建议 |
|---|---|---|---|
| D1 | 效果之间要不要能**组合**（弹簧 + 辉光 + 彩虹色） | A. 互斥（一次一个）<br>B. 可叠加（效果链） | **A**。现在三份实现都是互斥的，叠加会带来"谁先绘制"的复杂度，而收益不明 |
| D2 | 物理（弹簧）算框架还是效果 | A. 框架提供、效果选用<br>B. 效果自带 | **A 但要可选**。荧光扩散不需要四角弹簧，不该被迫继承 |
| D3 | 四边形约束怎么保证 | A. 接口只允许输出四角<br>B. 允许输出任意 Path2D | **A**（用户明确说"仅限于四边形"）。但 `updateGhosts` 已经是矩形列表，属于"多个四边形"，仍然在约束内 |

---

## 六、当前不该做的

| 项 | 原因 |
|---|---|
| 先拆 `NeovideCursorConfigurable`（2120 行） | 它是**设置 UI**，与效果框架正交。多光标/新效果都不碰它。拆它对"加效果"零帮助 |
| 改 `assignRanks` 让横向也分 4 档 | 那是调观感，属于物理参数，与架构无关。应先定效果框架再谈 |
| 引入 Gradle / Compose | 现有 javac 手工构建已经稳定，换构建系统风险远大于收益 |

---

## 七、与多光标的关系

实测：`NeovideCaretManager` **已经是 `Map<Editor, CaretAnimator>`**（每编辑器一个）。
真正的单光标假设在 `CaretAnimator` 内部：
- `private final TrailCorner[] corners` —— 一个光标一组四角
- `editor.getCaretModel().getOffset()` —— 只取主光标

**多光标 = 把 `TrailCorner[]` 变成 `List<TrailCorner[]>`**，
而这与"效果框架"天然契合：效果接口收 `List<CaretSnapshot>` 而不是单个 `centerX/centerY`。

所以：**第 1 步抽接口时就把参数设计成"多光标友好"**，多光标就不用再改一次接口。
