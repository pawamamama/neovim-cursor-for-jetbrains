# HANDOFF-ARCHITECTURE —— 插件接手手册

> 生成：2026-10-20　｜　基线：0.9.5-settings-ui
> **这份文档是写给「接手的 AI」看的，不是写给专家看的。**
>
> ⚠️ **先读 `DECISIONS-0.9.6.md`** —— 那是最新的设计决策台账（2026-10-20），
> 本文的 §7（后续方向）和 §9（重构方向）在它里面有更新的裁决。
> 假设读者能力：中等模型（如 deepseek-v4.1-flash 级别），能读懂 Java、能跑 PowerShell、
> 但**不知道这个项目的任何历史**。所以每个结论都带「为什么」和「怎么验证」。

---

## 0. 五分钟速览

### 这是什么

一个 IntelliJ IDEA 插件：给光标加 Neovide 风格的「拖尾」效果——光标移动时留下被拉伸的渐隐残影 + 辉光。

### 现在什么状态

| 项 | 值 |
|---|---|
| 版本 | 0.9.5-settings-ui |
| 上游 | `yyyyolo7a79-sketch/neovim-cursor-for-jetbrains` @ `6bed09d`（v0.5.0，**2026-10-16 后无新提交**） |
| 我们比上游多 | 设置界面 / CJK 修复 / IME 修复 / 辉光淡出 / 预设系统 |
| 上游比我们多 | **多光标拖尾**（唯一一项） |
| 本机构建 | `build/offline-build.ps1`（**无 Gradle**，纯 javac + 381 个 idea jar） |

### 三分钟上手

```powershell
# 1) 构建（必须先确认 javac 在 PATH）
javac -version    # 期望 25.0.2

pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 `
  -Version 0.9.6 `
  -OutName mytest.jar `
  -BaseJar "D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.5-settings-ui.jar"

# 2) 跑验证三件套（缺一不可）
pwsh -File tools\parity\run-parity.ps1            # 三份输出必须逐字节一致
pwsh -File tools\parity\run-audit.ps1             # 19 项常量，只允许 hideNativeCaret 一项 DIFF
pwsh -File tools\ime-probe\run-ime-geom-test.ps1  # 29 项边界全过

# 3) 跑全量回归（0.9.5 起有一键脚本）
pwsh -File tools\settings-probe\run-t9-full.ps1   # 11 项，期望 ALL PASSED
```

**如果 2 或 3 有红的，先别改代码 —— 说明你的环境有问题（见 §8 排错）。**

---

## 1. 架构地图（先看这一节，再动任何代码）

### 1.1 四层结构（依赖只能向上，不能向下）

```
┌─────────────────────────────────────────────────────────┐
│  ui/settings/   设置面板（唯一的 UI 层）                  │
│    NeovideCursorConfigurable  1582 行 ← 最大的类，也是 bug 集中地
└─────────────────────────────────────────────────────────┘
                        ↓ 依赖
┌─────────────────────────────────────────────────────────┐
│  ui/   编辑器覆盖层 / 动画 / 调度 / 菜单动作              │
│    NeovideCaretManager   857  编辑器生命周期 + 全局开关 + applyConfig
│    CaretAnimator        1458  单编辑器的动画状态机 + 调度
│    CaretTrailPanel       450  实际绘制（Swing JComponent）
│    RenderScheduler       186  全插件共享 1 条调度线程
│    CaretSchemeWatcher    138  监听配色方案变更
│    AnimPresetGroup / ColorPresetGroup    Tools 菜单子菜单
│    Toggle*Action         6 个菜单开关（每个 24-35 行）
└─────────────────────────────────────────────────────────┘
                        ↓ 依赖
┌─────────────────────────────────────────────────────────┐
│  settings/   持久化层                                     │
│    NeovideCursorSettings 719  @State + PersistentStateComponent
│    CursorPresets         215  预设常量表（纯数据，零依赖）
└─────────────────────────────────────────────────────────┘
                        ↓ 依赖
┌─────────────────────────────────────────────────────────┐
│  core/   纯计算，零平台依赖（可在无 IDE 环境下编译运行）   │
│    NeovideConfig         290  24 个字段的默认值（★ 零 diff 铁律）
│    TrailCorner           361  四角弹簧 + 短跳判据
│    DampedSpring          101  临界阻尼弹簧闭式解
│    AfterimageTrail       174  残影模式的历史位置队列
│    NeovideColors          69  HEX → Color
└─────────────────────────────────────────────────────────┘
```

**实际依赖关系（grep 验证过的）**：

```
NeovideCursorSettings      -> core
AnimPresetGroup            -> settings
ColorPresetGroup           -> settings
CursorPresetApplier        -> settings
CaretAnimator              -> core
CaretTrailPanel            -> core
NeovideCaretManager        -> core, settings
ToggleTrailModeAction      -> core
NeovideCursorConfigurable  -> core, settings, ui
```

### 1.2 每个类一句话职责

| 类 | 一句话 | 改动风险 |
|---|---|---|
| `NeovideConfig` | 24 个参数的**编译期默认值**，同时是 parity harness 的输入 | 🔴 **零 diff 铁律，一行都不能改** |
| `TrailCorner` | 一个角点的弹簧状态 + 短跳/跨行分支判据 | 🟠 高（手感来源） |
| `DampedSpring` | 临界阻尼弹簧的闭式解（对时间可加） | 🟠 高（改错了拖尾会变肉） |
| `AfterimageTrail` | 残影模式的历史位置环形队列 | 🟡 中 |
| `NeovideColors` | `#RRGGBB` + opacity → `java.awt.Color` | 🟢 低 |
| `NeovideCursorSettings` | 持久化真相，落 `<config>/options/NeovideCursorSettings.xml` | 🔴 高（持久化丢了用户会炸） |
| `CursorPresets` | 7 动画预设 + 5 颜色预设，**纯常量，不是真相源** | 🟢 低 |
| `NeovideCaretManager` | 全局单例：编辑器注册/注销、总开关、`applyConfig()` 编排 | 🔴 高（核心枢纽） |
| `CaretAnimator` | 单编辑器：读光标 → 推进物理 → 提交重绘 → 排下一帧 | 🔴 高（最复杂） |
| `CaretTrailPanel` | 真正 `paintComponent` 的地方 | 🟠 高（观感） |
| `RenderScheduler` | 全插件**共享 1 条**调度线程（历史上是 N 条） | 🟠 高（改了会掉帧） |
| `CaretSchemeWatcher` | 订阅 `EditorColorsManager.TOPIC`，切主题时重新隐藏原生光标 | 🟡 中 |
| `NeovideCursorConfigurable` | 设置面板：布局 + 绑定 + apply/reset/isModified | 🔴 **最高**（见 §6） |---

## 2. 关键不变量（动了就出事，没有任何例外）

这 7 条是**血的代价换来的**。每次改代码前扫一眼，改完再扫一眼。

| # | 不变量 | 为什么 | 怎么验证 |
|---|---|---|---|
| **I-1** | `core/NeovideConfig.java` **一个字符都不能改** | 它是 3 份 parity harness 的输入 + `run-audit.ps1` 的 19 项字面量靶子 | `git diff --exit-code -- src/main/java/.../core/NeovideConfig.java` 必须 exit 0 |
| **I-2** | `core/` 目录**不能新增文件** | 比 I-1 更强的形态。新增文件会让「core 是纯默认值来源」这个语义垮掉 | `git status --porcelain core/` 必须为空 |
| **I-3** | `ACTIVE_DELAY_MS = 8` 不许改 | 用户实测 12ms 会「傻快」 | `run-audit.ps1` 的 A 段会检查 |
| **I-4** | 改任何参数后，**必须走 `applyConfig()`**，不能直接写 `CONFIG` | 颜色 / `glowPadding` / `afterimageLifetime` 是**缓存的**，直接写字段画面不刷新 | 见 §5.3 |
| **I-5** | 滑块 getter **必须返回 snap 后的值** | 否则用户输入的精确值会被刻度值覆盖（B-2 类 bug） | 见 §6.3 |
| **I-6** | `isModified()` **必须有两半**（`super` + `dirtyAnyField`） | 少一半就踩 B-1（按钮变灰）或 B-2（按钮永亮） | 见 §6.3 |
| **I-7** | 打 jar 的 `-BaseJar` 必须是**上一个已安装版本** | 因为官方 class 经过 IntelliJ 插桩，全量替换会丢插桩 | 见 §8.1 |

---

## 3. 数据流：一个参数从「用户点一下」到「屏幕上变化」

**这是理解整个插件的钥匙。** 19 个参数分布在 4 个地方，搞不清它们的流向就会写出同步 bug。

### 3.1 四个存储位置

```
① NeovideConfig 的字段        ← 编译期默认值（永不改，I-1）
② NeovideCursorSettings.State ← 持久化真相（XML 落盘）
③ NeovideCaretManager.CONFIG  ← 运行期快照（绘制代码每帧读它）
④ Configurable.pending        ← 面板预览副本（用户编辑中，未 OK）
   Configurable.baseline      ← 面板打开时的快照（脏判据的基准）
```

### 3.2 启动时：② → ③

```
平台加载 XML → NeovideCursorSettings.loadState(state)
                        ↓
第一次 editorCreated 时：
  NeovideCaretManager.syncStateToConfig()   ← 纯字段拷贝，无副作用
                        ↓
              CONFIG（运行期快照就位）
```

⚠️ **注意顺序坑**：`syncStateToConfig()` 必须在 `if (!CONFIG.enabled) return;` **之前**调用。
否则启动瞬间 `CONFIG.enabled` 是编译期 `true`，会绕过「用户关掉了插件」这个判据。
（这是 0.9.4 的 R-01 回归，已修，回归探针在 `CaretAnimator`/`NeovideCaretManager` 相关探针里）

### 3.3 用户在面板改值：④ → ② → ③

```
用户拖滑块
    ↓  ChangeListener（:666-682）
pending.<字段> = 新值       ← 只写 pending，不碰 state
    ↓
用户点「应用 / OK」
    ↓  apply()  :1081
A1  回读 ColorPanel（DialogPanel 追踪不到它）
A2  super.apply()   ← DialogPanel 把绑定控件的值写进 pending
A3  逐字段比较 pending vs baseline → 只写「真改过」的字段到 target(State)
A4  svc.persistQuietly()   ← 落盘
A5  NeovideCaretManager.applyConfig()   ← ★ 唯一生效入口
A6  baseline = deepCopy(target)
A7  pending  = deepCopy(target)   ← 必须与 baseline 同源（R-01）
A8  super.reset() + syncAllControlsFromPending()  ← 控件也要跟上（A7 单做不够）
```

### 3.4 用户点 Tools 菜单：① → ③ → ②

```
ToggleXxxAction.setSelected()
    ↓
NeovideCaretManager.setXxx(on)
    ↓
CONFIG.<字段> = on
    ↓
syncConfigToStateAndPersist()   ← CONFIG → State → 落盘
```

⚠️ **Tools 与面板会打架**：面板开着的时候点 Tools，`pending` 还是旧值。
`apply()` 的「逐字段比较 baseline」就是为了不冲掉 Tools 的改动（R-01）。
**不要把它改回「无条件 target = pending」——那是原缺陷。**---

## 4. ★★★ 核心章节：「修 bug 引入新 bug」的模式库

**这一节是本项目最贵的知识。** 0.9.5 三轮审查共产生 13 条 findings，
**其中至少 7 条是「上一次修复引入的」**。这不是巧合，是结构性的。

### 4.1 病根：四地同步，没有单一权威

回顾 §3.1 的四个存储位置。**同一个参数在四个地方各有一份值**，而「让它们一致」这件事：

- 没有单一入口
- 没有事务
- 没有自动化保证
- 每次修复都是「在两地之间补一条同步」

于是必然发生：

```
修 A → 引入 B → 修 B → 引入 C → 修 C → 引入 D ...
```

**实际发生的四连**（这是真实历史，不是假设）：

| 轮次 | 修了什么 | 引入了什么 | 症状 |
|---|---|---|---|
| 1 | 滑块补 `ChangeListener`（让数值跟手） | **B-1** | 应用按钮变灰，改动被静默丢弃 |
| 2 | 修 B-1：getter 用 snap | **B-2** | 精确值与刻度不一致时，应用按钮永远亮着 |
| 3 | 修缺陷 3：`apply()` 前移 baseline | **R-01** | 第二次点应用把 Tools 的改动冲掉 |
| 4 | 修 R-03：加 `HIDDEN_SCHEMES` 记录表 | **R-09** | 切回旧主题时原色被兜底前景色覆盖 |

### 4.2 五个具体复发模式（照着查）

#### 模式 A：补了监听器，忘了 `isModified()`

**触发条件**：你给某个控件加了 `ChangeListener` / `DocumentListener`，让它「实时写 `pending`」。

**为什么出事**：
```
DialogPanel 的 isModified() 靠「控件值 vs 绑定 getter」判断。
你补 listener 后，控件值与 getter 始终一致 → super.isModified() 恒 false
→ 「应用」按钮永远灰 → 用户改了但点不了 OK → 改动静默丢失
```

**正确做法**：`isModified()` 必须**两半都有**：
```java
return super.isModified()          // 抓「控件值 vs getter」
    || colorPanelModified(cur)      // ColorPanel 不被 DialogPanel 追踪
    || dirtyAnyField(pending, state); // 抓「pending vs state」← 补 listener 后必须靠这半
```

**验证**：改任一滑块 → `isModified()` 必须 `true`。

#### 模式 B：修了「按钮变灰」，把按钮搞成「永亮」

**触发条件**：你为了修模式 A，让 slider 的 getter 返回 `snapToTick(pending)`。

**为什么出事**：用户输入精确值 `333`，滑块落在刻度 `350`。getter 返回 `350`，
而 `pending` 里是 `333`… 但如果 getter 不做 snap，`pending` 会**被滑块值覆盖成 350**（丢数据）。

**正确做法**：`snapToTick` 必须 **clamp + 吸附两件事都做**，且 getter 用它：
```java
static int snapToTick(int raw, int min, int max, int step) {
    int c = Math.min(max, Math.max(min, raw));   // ← clamp
    return Math.round(c / step) * step;          // ← 吸附
}
// 滑块 getter：() -> snapToTick(readTick(pending), min, max, step)
// 输入框 getter：() -> readTick(pending)   ← 不 snap，保住精确值
```

**验证**：输入 `333` → 滑块显示 `350`、输入框显示 `333`、`isModified()` 为 `false`（不是永真）、apply 后落库是 `333`。

⚠️ **注意**：契约里曾写「构建顺序（滑块先/输入框后）是保护机制」，
**这是错的**——verifier 用三个反向实验证明真正起作用的是 getter 的 snap，不是构建顺序。
（构建顺序仍建议保持，但别以为它是保护。）

#### 模式 C：`apply()` 只前移 `baseline`，不同步 `pending`

**触发条件**：你在 `apply()` 末尾加了 `baseline = deepCopy(target)`，以为就够了。

**为什么出事**：
```
第 1 次 apply：pending 是旧值，但「pending vs baseline」判据不成立 → Tools 的值保住 ✔
但 pending 没更新 → isModified() 仍 true → 按钮保持点亮 → 用户会再点一次
第 2 次 apply：baseline 已是 Tools 的新值，pending 是旧值 → 判据成立 → 旧值写回 → Tools 被冲掉
```

**正确做法**：`apply()` 末尾**三件事**，缺一不可：
```java
baseline = deepCopy(target);                              // A6
pending  = deepCopy(target);                              // A7 ← 必须与 baseline 同源
super.reset(); syncAllControlsFromPending();              // A8 ← 控件也要跟上！
```

⚠️ **A8 不能用 `syncAllControlsFromPending()` 单独代替**——它**不覆盖复选框与下拉**。
必须先 `super.reset()` 让 DialogPanel 刷绑定的控件，再手工刷非绑定的。

**验证**：Tools 改 X → apply → apply → X 仍是 Tools 的值（不是被冲回）。

#### 模式 D：修了「新值被隐藏」，忘了「旧值没恢复」

**触发条件**：你加了方案变更监听，在新 scheme 上重新隐藏光标。

**为什么出事**：
```
A 主题：隐藏 → A 的 CARET_COLOR = 透明
切到 B：监听触发 → B 也隐藏   ← 你修的是这个
        但 A 的透明色【没人恢复】← 你忘了这个
取消勾选：只恢复当前 B
切回 A：A 还是透明 → 光标不可见且无法自愈
```

**正确做法**：用 `IdentityHashMap<EditorColorsScheme, Color>` 记住「我们改过哪些 scheme、原色是什么」，
恢复时**逐个**恢复所有历史 scheme。

#### 模式 E：修好模式 D，又踩「二次 force 覆盖原色」

**触发条件**：你加了 `HIDDEN_SCHEMES`，用 `put` 记原色。

**为什么出事**：
```
切回【已经隐藏过的 A】时，force 重入
此时 A 的 CARET_COLOR 是 rgba(0,0,0,0)
→ sanitizeCaretColor 看到 alpha==0，判为「历史遗留污染」
→ 返回【兜底前景色】
→ put(A, 兜底色) 覆盖掉第一次记下的正确原色
→ 取消勾选后用户拿到兜底色，自己设的颜色永久丢失
```

**正确做法（两处都要改，只改一处不够）**：
```java
// (a) 保住第一次的原色
HIDDEN_SCHEMES.putIfAbsent(scheme, savedCaretColor);   // 不是 put！

// (b) 兜底恢复段要跳过「已由记录表恢复过」的 scheme
boolean currentHandledByMap = false;
try {
    currentScheme = EditorColorsManager.getInstance().getGlobalScheme();
    synchronized (HIDDEN_SCHEMES) {
        currentHandledByMap = HIDDEN_SCHEMES.containsKey(currentScheme);
    }
} catch (Throwable ignored) { }
// ... 然后循环逐个恢复 + clear() ...
Color current = currentHandledByMap ? null : savedCaretColor;  // ← 关键
```

⚠️ **顺序坑**：`currentHandledByMap` 必须在 `clear()` **之前**算出来，
否则 `containsKey` 恒 false，判据失效、(b) 等于没改。

⚠️ **探针设计坑**：验证这个时，**「真实原色」必须与 `getDefaultForeground()` 故意取不同值**
（如 `#A9B7C6` vs `#00FF00`）。两值相同的话断言会**假绿**，无法区分「恢复真原色」与「被兜底覆盖」。

### 4.3 改代码前的自查清单（照着念）

```
[ ] 我改的是哪个「存储位置」？(Config / State / CONFIG / pending)
[ ] 改完之后，另外三个位置怎么保持一致？谁负责同步？
[ ] 如果这个参数有缓存（颜色 / glowPadding / lifetime），我调了刷新入口吗？
[ ] 我加监听器了吗？加了的话 isModified() 的另一半还在吗？
[ ] 我改了 apply() 的末尾吗？baseline / pending / 控件 三者都前移了吗？
[ ] 这个改动会不会让「已经是我们写进去的值」被当成「用户原值」读走？
[ ] 我改的源码里有字面量锚点被 .ps1 脚本依赖吗？（见 §8.4）
[ ] 我写的新探针，能不能在「修复前形态」上失败？
```

### 4.4 一条元规则

> **每修一个 bug，都要问：这个修复会让哪个「原本正确的地方」变成错误？**
>
> 如果答不出来，说明你还没理解这次修复的影响面。

这条规则的价值：本轮的 R-09（模式 E）如果被问过这个问题，大概率能提前发现——
「加了记录表之后，force 重入会不会覆盖记录？」---

## 5. 排错决策树（按症状查，不按理论查）

### 5.1 症状：光标不跟手 / 拖尾卡

```
先分清是「光标本体卡」还是「拖尾卡」
│
├─ 光标本体卡（按方向键，光标要等一下才出现）
│   └─ 检查 hideNativeCaret + alwaysHighFps 的组合：
│      · 隐藏原生 + 常驻刷新开  → 应该不卡（默认档）
│      · 隐藏原生 + 常驻刷新关  → 空闲降到 120ms，静止时正常，动起来会顿
│      · 不隐藏 + 常驻刷新关    → 光标本体不卡（原生画的），只有拖尾卡
│      → 用户说「卡」时，先问是哪种组合。别再像我一样以为「关常驻必卡」
│        （真相：caretPositionChanged 每次都 wakeUp()，立刻回 8ms 档）
│
└─ 拖尾卡（光标本体流畅，但残影一顿一顿）
    ├─ 看日志里的 fps 行（需要 -Ddsh.debug 或 LOG.isDebugEnabled）
    │   · delay 正常但 maxGap 很大 → EDT 抢占，不是你的问题
    │   · delay 本身就大 → 调度问题，看 RenderScheduler
    └─ 关键认知：**瓶颈是 EDT 抢占，不是绘制**
        实测：12 层辉光只占 0.05ms/帧（8ms 预算的 0.6%）
        IDE 自己 ScaledBlit.Scale 占 EDT 12.6%，插件总共才 10.7%
```

⚠️ **不要做的事**（已验证是死路）：
1. 优化绘制 —— 收益上限 0.6%
2. 缩小重绘面积 —— **面积大 67 倍反而快 15 倍**
3. 降辉光层数 —— 实测**无 fps 收益**

### 5.2 症状：改了设置但画面没变

```
按参数分类，因为不同参数的刷新机制不同：
│
├─ 颜色类（tailColor / shadowColor / tailOpacity / glowOpacity）
│   └─ CaretTrailPanel.fillColor / glowColor 是【缓存的】
│      必须调 panel.refreshStyles() —— 通常通过 applyConfig() 的 S7 触发
│
├─ 辉光外扩（shadowBlurFactor）
│   └─ glowPadding 是【构造时算一次 + 慢路径重算】
│      ⚠️ 陷阱：onModeChanged() 置 forceResync=true 也【不够】
│      因为 refreshTarget() 在 :1007 有早退「位置没变就返回」，在算 padding 之前
│      → 必须额外调 refreshGlowPadding()
│
├─ 残影存活时长（afterimageLifetime）
│   └─ AfterimageTrail.lifetime 只有 onModeChanged() 会重设
│
└─ 时序类（animationLength / shortAnimationLength / trailSize / trailFactors）
    └─ 这些是【下一次光标移动时】才读，不需要刷新。看起来没变是因为你没动光标
```

**一句话**：改完设置后如果画面没变，先确认「这个参数是不是缓存的」，再看 `applyConfig()` 的 S7 有没有跑。

### 5.3 症状：设置面板的按钮状态不对

```
├─ 按钮永远是灰的（改了也点不了）
│   └─ B-1：super.isModified() 恒 false。检查 isModified() 有没有 dirtyAnyField 那半
│
├─ 按钮永远亮着（什么都没改也亮）
│   └─ B-2：getter 没 clamp/snap，或 apply 后没前移 baseline/pending
│      检查 apply() 末尾的 A6/A7/A8 三件事
│
└─ 点了一次应用，按钮还亮（诱导你点第二次）
    └─ 同 B-2：pending 没随 baseline 一起前移
```

### 5.4 症状：设置丢了 / 重启后回到默认

```
├─ 全部设置都丢
│   └─ 检查 <config>/options/NeovideCursorSettings.xml 是否生成
│      路径：%APPDATA%\JetBrains\IntelliJIdea2026.1\options\
│      没生成 → 看 idea.log 里「迁移落盘失败」或「applyConfig 完成」
│
├─ 只有个别字段丢
│   └─ 该字段没被写进 State，或 apply() 的 A3 逐字段比较漏了它
│      检查：State 有多少字段？现在应该是 19 个
│      （15 基础 + animationLength + shortAnimationLength + stateVersion + stretchRatioK）
│
├─ 从 0.9.3 升级后设置被重置
│   └─ 旧键迁移失败。检查 PropertiesComponent 里的两个旧键：
│      neovide.cursor.alwaysHighFps / neovide.cursor.overlayFadeOut
│      迁移逻辑在 NeovideCursorSettings.migrateIfNeeded()
│
└─ 点第二次「应用」把设置冲掉了
    └─ R-01（模式 C）。检查 apply() 末尾 A7/A8
```

### 5.5 症状：原生光标看不见 / 双光标

```
├─ 光标完全看不见（连拖尾也没有）
│   └─ CARET_COLOR 被写成透明但覆盖层没画。检查 editor 是否被 install 了
│
├─ 拖尾有，但原生光标也显示（双套绘制）
│   └─ 三种可能：
│      1. hideNativeCaret 就是 false（配置如此，不是 bug）
│      2. 切过主题，透明色丢了但插件以为还隐藏着（模式 D，0.9.5 已修）
│      3. savedCaretColor 的早退判据让重新隐藏没发生
│
└─ 卸载插件后光标依然隐形 ⚠️ 这是【已记录的真实事故】
    └─ 原因：透明色被 IDE 落盘写进 colors/*.icls
       自救：Settings | Editor | Color Scheme | General | Caret 手动设回可见色
       代码里有 sanitizeCaretColor() 兜底，但它只能防「再次恢复成隐形」
       **这条要写进用户文档，不能只靠代码防**
```

### 5.6 症状：构建或脚本失败

见 §8「环境问题」（探针桩缺失、PowerShell 自动变量、.gitignore 坑都在那）。---

## 6. 验证资产（200+ 条断言，改代码前后都要跑）

### 6.1 全量清单

| 脚本 | 断言数 | 检查什么 | 期望 |
|---|---|---|---|
| `tools/parity/run-parity.ps1` | 3 份输出 | 物理/几何/轨迹的**逐字节一致** | `ALL PARITY OK` |
| `tools/parity/run-audit.ps1` | 19 + D 段 | 观感常量、死字段、调度结构 | DIFF=1 且是 `hideNativeCaret` |
| `tools/ime-probe/run-ime-geom-test.ps1` | 29 | IME 中间态几何判据 | `ALL OK（29 项）` |
| `tools/settings-probe/run-settings-v2-probe.ps1` | 68 | 布局/预设/吸附/动画参数 | 68/68 |
| `tools/settings-probe/run-4bugs-probe.ps1` | 全绿 + 4 变异 | 4 个原始缺陷 + 变异忠实度 | `ALL 4BUG PROBES OK` |
| `tools/settings-probe/run-t7-probe.ps1` | 24 | R-01/R-02/R-03 + 3 变异 | `ALL T7 PROBES OK` |
| `tools/settings-probe/run-non-edt-probe.ps1` | 13 | 非 EDT 分支（R-01/R-02/R-03 原漏网处） | `ALL OK` |
| `tools/settings-probe/build-v-probes.ps1` | 119 | verifier 自建的契约 V1-V15 | 118 PASS / 1 FAIL（F-2 上报器） |
| `tools/settings-probe/run-v-mutations.ps1` | 6 变异 | 变异忠实度 | 6 个全红 |
| **`tools/settings-probe/run-t9-full.ps1`** | **11 项** | **一键全量（含上面多数）** | **`ALL T9 VERIFICATION PASSED`** |

### 6.2 最省事的用法

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-t9-full.ps1
```

**11 项全 exit=0 才算没弄坏别的。** 这条脚本是 0.9.5 建的，以后每版都应该维护它。

### 6.3 探针的三个环境要求（不满足会 NPE，不是代码 bug）

写离线探针驱动真实 `DialogPanel` 时，必须补这三个桩：

| 缺什么 | 谁会要它 | 怎么补 |
|---|---|---|
| `ActionManager.getInstance()` | `collapsibleGroup` 要 `"CollapsiblePanel-toggle"` | 手写 `FakeActionManager extends ActionManager`（**抽象类，不能 Proxy**） |
| `SingleAlarmSharedCoroutineScopeHolder` | DialogPanel 的校验器 | 反射建实例（包私有类，构造器要 `kotlinx.coroutines.CoroutineScope`，用 `GlobalScope.INSTANCE`） |
| `JBPopupFactory.getInstance()` | `intTextField` 的文本校验器 | 手写 `FakePopupFactory extends JBPopupFactory`（**抽象类**，要覆写 30+ 方法） |

外加 5 个 `--add-opens`：

```
--add-opens=java.desktop/javax.swing=ALL-UNNAMED
--add-opens=java.desktop/java.awt=ALL-UNNAMED
--add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED
--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED
--add-opens=java.desktop/sun.swing=ALL-UNNAMED
```

**现成实现**：`tools/settings-probe/ProbeSettingsV2.java` 里的
`FakeActionManager` / `newHolder()` / `FakePopupFactory` 可以直接抄。

### 6.4 写探针的三条铁律

1. **探针必须能在「修复前形态」上失败。** 做法：复制源码到 `build/` 下逆向改回旧形态再跑。
2. **变异必须忠实。** 回滚后 `git diff` 确认删除行**恰好**是修法引入的那几行，不多不少。
   不忠实的变异会得出错误结论（历史教训：只插顶部早退、保留同步后重判 → 探针仍全绿）。
3. **假 FAIL 与假 PASS 同样是探针缺陷。** 三个真实教训：
   - 拖动目标写死成滑块最大刻度 → 拖前已是上限 → 假 FAIL
   - HTML 断言用 `contains` 原串（`DslLabel` 会规范化空白）→ 假 FAIL
   - 「真实原色」与兜底色取同值 → 假绿

---

## 7. 未实现的后续方向（按优先级，含入口和已知坑）

### 7.1 P0：空闲时不往 EDT 交脏区

**问题**：完全静止时也每 8ms 交一次脏区重绘。8 个编辑器 ≈ 1000 次/秒重画**已经正确**的画面。

| 项 | 值 |
|---|---|
| 入口 | `CaretAnimator.submitRepaint()` + `tick()` 末尾的调度决策 |
| 改法方向 | 如果 `currentBounds.equals(lastDirtyRegion)` 且无动画，跳过 `submitRepaint()` |
| 风险 | 🟡 中。跳错了会留下残影擦不掉 |
| 验证 | parity（逐字节）+ 肉眼确认没有脏像素残留 |
| 已知坑 | `lastDirtyRegion` 用于擦除上一帧，跳过了就永远擦不掉 |

### 7.2 P1：隐藏标签页降频

**问题**：`isShowing()` 现在只是「不画」，调度仍 8ms，每帧照样调 `visualPositionToXY`。

| 项 | 值 |
|---|---|
| 入口 | `CaretAnimator.computeNextDelay()`（`:696`） |
| 改法方向 | `if (!content.isShowing()) return IDLE_UNFOCUSED_DELAY_MS;` |
| 风险 | 🟢 低 |
| 验证 | 切标签页前后 fps 对比 |

### 7.3 P2：多光标拖尾（上游唯一比我们强的地方）

**问题**：现在只跟主光标。Alt 列选时只有主光标有拖尾。

| 项 | 值 |
|---|---|
| 证据 | `CaretAnimator:865` 用 `getVisualPosition()`、`:917` 用 `getOffset()` —— 都只返回主光标 |
| 平台支持 | ✅ `CaretModel.getAllCarets()` / `getCaretCount()` 都存在（javap 已确认） |
| 入口 | `NeovideCaretManager.ANIMATORS` 结构要从 `Map<Editor, CaretAnimator>` 改成支持 N 个 |
| 参考 | 上游 `6bed09d` 的 `SpringTrail`（上限 64 个光标） |
| 风险 | 🔴 **高**。要重构 `ANIMATORS` 结构，影响所有 setter 与 `applyConfig()` |
| 建议 | **单独一版做**，不要和别的混 |

### 7.4 P3：流光 RGB 特效（用户明确想要，说「后期做」）

**需求原话**：「那种炫酷 rgb 但是都是浅色系」

| 项 | 值 |
|---|---|
| 入口 | `CaretTrailPanel.paintQuad()`（`:227`）—— 现在是 `g2.setColor(fillColor); g2.fillPolygon(polygon);` |
| 改法方向 | `LinearGradientPaint` 沿四边形长轴，色相随时间旋转 + 低饱和 + 高亮度 |
| 风险 | 🔴 **高**。这违反「不要再做的三件事」之一（优化绘制）的**反面**——是**增加**绘制 |
| 必须做 | 实测 p95 帧间隔，不能凭感觉。GPU 不加速 `fill(Path2D)`，每层都是 CPU 抗锯齿 |
| 建议 | 做成可选开关，默认关。别让它拖累默认档 |

### 7.5 P4：辉光双色渐变

**需求原话**：「颜色是有两个的我希望以后扩散也能两种」

| 项 | 值 |
|---|---|
| 现状 | 已有两个颜色：`tailColor`（拖尾主体）+ `shadowColor`（辉光） |
| 需求 | 辉光本身要能双色（如内粉外青） |
| 入口 | `CaretTrailPanel.drawQuadGlow()`（`:264`）—— 逐层同心外扩 + fill |
| 改法方向 | 每层用不同色，或 `RadialGradientPaint` |
| 风险 | 🟡 中（同 7.4，但要实测） |

### 7.6 P5：残影模式输入不平滑

**用户原话**：「残影输入不平滑这个计入到 md 先不做」→ 明确**本期不做**，但记下来。

| 项 | 值 |
|---|---|
| 入口 | `CaretAnimator.tickAfterimage()` |
| 现象 | 残影模式在打字时轨迹跳跃（每次只记录一个位置，打字间隔短时残影稀疏） |
| 可能方向 | 记录时做插值，或提高 `MAX_GHOSTS` |

### 7.7 P6：`BUSY_RETRY_MS 1 → 0`

| 项 | 值 |
|---|---|
| 收益 | 追平 EDT 繁忙时 −3.4% |
| 代价 | 改成 0 会引入空转 |
| 状态 | 已提上游 issue #8，**收益不值，建议不做** |---

## 8. 环境问题（这台机器特有的坑，别的机器可能没有）

### 8.1 构建：本机没有 Gradle

**必须用** `build/offline-build.ps1`，它做四件事：

```
[1/4] javac --release 21  编译 src/main/java
[2/4] 注入 plugin.xml     把 <version> 和 <idea-version> 拼进去（源码里没有！）
[3/4] jar --update        以 BaseJar 为底，只替换变动的 class
[4/4] 自检               确认 jar 内有 version / idea-version
```

**关键**：`-BaseJar` 必须是**上一个已安装版本的 jar**。
原因：官方 class 经过 IntelliJ 插桩（`@NotNull` 参数检查等），全量替换会丢插桩、行为不一致。

**前置**：`javac 25.0.2` 在 PATH；`D:\deepseekwork\_idea-anim\idea-libs` 有 381 个 jar。

### 8.2 jar 的整体 SHA256 每次重建都会变（不是 bug）

```
原因：javac 每次重编都刷新 .class 的 mtime，jar 把它写进 zip 条目头
实测：连构四次得 BA324F7A / 2F6E99A4 / 629B2E26 / D30B23E2
但解包后 27 个条目逐字节一致
```

**判断内容一致请用**：`VERIFY-SETTINGS.md` §12.2 的 27 条稳定指纹表，
或看 `META-INF/plugin.xml` 的哈希。**整体 SHA256 只能验「文件没被替换」。**

### 8.3 PowerShell 自动变量陷阱（真实事故）

**曾发生**：有人写了 `$home = Join-Path ...`，`$home` 是**只读自动变量**（= USERPROFILE），
赋值静默失败，随后 `Remove-Item -Recurse -Force $home` **指向了 `C:\Users\wangw`**，
真发起了一次递归删除（被沙箱拦下，零损失）。

**禁令**：写脚本禁用这些名字做普通赋值：

```
$home  $env  $args  $input  $host  $pwd  $error  $matches  $PSHOME  $profile
```

改用：`$repoRoot` / `$tmpRoot` / `$fakeHome` / `$probeDir`。

### 8.4 改源码后必须 grep 脚本锚点

**曾发生**：在滑块的 `ChangeListener` 里插入一行 `refreshPresetDisplay();`，
导致 `run-v-mutations.ps1` 的 MUT-A heredoc 锚点失配：

```
EXIT=1「MUT-A：找不到 ChangeListener 整段」
→ B/C/D/E/F 五个变异根本跑不到
```

**代码没问题，纯是脚本锚点没跟着改**，而脚本失败不会告诉你「后 5 个变异没跑」。

**做法**：
```powershell
# 改源码后跑一遍
Get-ChildItem -Recurse -Filter *.ps1 tools | Select-String -Pattern 'refreshPresetDisplay|addChangeListener'
```

### 8.5 PowerShell 的 `-Path 目录 -Include` 恒 0 命中（假阴性！）

```powershell
# ❌ 不报错，但恒返回 0 命中 → 会让你把「已清零」验成「基线本来就 0」
Select-String -Path <目录> -Include *.java -Pattern '<正则>'

# ✅ 正确：逐个文件
Get-ChildItem -Recurse -Filter *.java <目录> | Select-String -Pattern '<正则>'
```

**这条足以让验证结论反转**，务必用第二种写法。

### 8.6 函数名用两段连字符会被 pwsh 解析错

```powershell
# 实测：pwsh 7.6.6 上定义成功但调用报「术语不会被识别」
function Build-And-Probe { ... }   # ❌ 调用时被解析成 Build-AndProbe
function BuildProbeTree   { ... }  # ✅
```

### 8.7 探针里 `$codes += ...` 会收进意外输出

**曾发生**：`$codes += Test-Something ...` 把函数里 `Select-String` 的输出也收进数组，
出现 `FAILED COUNT = 140` 假报警。

**修法**：函数内人类可读输出一律 `Write-Host`，**只 `return` 退出码**。

---

## 9. 重构方向（下一版建议，见 NEXT-REFACTOR.md 详情）

### 9.1 为什么需要重构

| 指标 | `NeovideCursorConfigurable` |
|---|---|
| 行数 | **1,582** |
| 字段数 | **54** |
| 方法数 | 65 |
| 注释占比 | 24.5%（**正面资产**） |

**0.9.5 三轮审查的 8 条 medium 全部出在这个类的状态同步上。**

### 9.2 三步重构（互不依赖，每步完成都是可交付状态）

| 步骤 | 做什么 | 收益 | 预估 | 风险 |
|---|---|---|---|---|
| 1 | 拆成 `PanelBuilder` / `PanelBinder` / `Configurable` / `ProbeAccessors` | 1,582 → 每个 ≤500 | 半天 | 低 |
| 2 | 引入 `EditSession`（`pending`/`baseline`/`syncingControls` 收进去，唯一写入口） | **消灭四地同步病根** | 1 天 | 中 |
| 3 | `SliderBinding` 提为独立类（自管值↔控件↔标签↔预设匹配） | 消除重复 | 半天 | 低 |

**关键约束**：纯结构整理，**不改任何用户可见行为**。验收判据是「200+ 断言全部保持绿」。

### 9.3 重构后的目标形态

```java
final class EditSession {
    State pending;              // 编辑中的值
    State baseline;             // 打开面板时的快照
    boolean syncingControls;    // 抑制回环

    void onControlChanged(String field, Object value);  // ← 唯一写入口
    void commitTo(State target);                        // 只写真改过的字段
    boolean isDirty();                                  // 逐字段比较
    void advanceBaseline();                             // apply 后调用
}
```

**这样 R-01/R-02 类 bug 在结构上不可能发生**——因为只有一个写入口。---

## 10. 禁止事项清单（违反即失败）

### 10.1 绝对禁止

| # | 禁止 | 为什么 |
|---|---|---|
| 1 | 改 `NeovideConfig.java` 的任何字符 | 3 份 parity harness 的输入 + 19 项审计靶子 |
| 2 | 在 `core/` 下新增文件 | 破坏「core 是纯默认值来源」语义 |
| 3 | 改 `ACTIVE_DELAY_MS`（现值 8） | 用户实测 12ms 会「傻快」 |
| 4 | 改 `MAX_DT`（现值 1.0/20） | 必须 ≥ `MAX_ADAPTIVE_DELAY_MS`（40ms），否则每帧丢时间 |
| 5 | 直接写 `CONFIG` 字段而不走 `applyConfig()` | 颜色/padding/lifetime 是缓存的，画面不刷新 |
| 6 | 把 `apply()` 改回「无条件 `target = pending`」 | 那是 R-01 原缺陷 |
| 7 | 用 `HIDDEN_SCHEMES.put` 而非 `putIfAbsent` | 会覆盖掉正确原色（R-09） |
| 8 | 在 `isModified()` 里只留一半 | 踩 B-1 或 B-2 |
| 9 | 装 jar / 重启 IDEA（除非用户明确要求） | 这是用户的环境，要经过他同意 |
| 10 | 写脚本时用 `$home` / `$env` / `$args` / `$input` / `$host` / `$pwd` / `$error` / `$matches` | 真实事故：差点递归删除用户目录 |

### 10.2 已验证是死路（别重走）

| 死路 | 实测结论 |
|---|---|
| 「优化绘制能提帧率」 | 12 层辉光只占 **0.05ms/帧**（8ms 预算的 0.6%），收益上限就这么多 |
| 「缩小重绘面积能提帧率」 | **面积大 67 倍反而快 15 倍** |
| 「降辉光层数能提帧率」 | 实测**无 fps 收益**（但确实省 EDT，JFR 显示 `fill(Path2D)` 曾占 9.3%） |
| 「分步推进能让动画更匀」 | `DampedSpring` 是闭式解、对时间可加，分步与一步**数学等价**；实测更糟 38~46% |
| 「单帧位移截断防跳变」 | 越卡越锯齿，且清零速度后不再是临界阻尼 |
| 「`MIN_ANIMATION_FRAMES` 低帧率补偿」 | 把四角时长抹平成同一个值 → **梯度消失 → 拉伸看不见** |

### 10.3 关于「真实瓶颈」的正确认知

```
真正的瓶颈是 EDT 抢占，不是绘制速度。

实测数据（45 分钟 JFR，EDT 栈顶 TOP5）：
  sun.java2d.loops.ScaledBlit.Scale    12.6%  ← IDE 自己画字
  FillParallelogram                    11.9%
  MaskFill                              5.9%
  DrawGlyphListLCD                      3.7%
  插件总共                              10.7%

所以剩下的空间只有「少做无用功」（P0/P1），不是「画得更快」。
```

---

## 11. 接手第一天做什么（按顺序）

### 第 1 小时：只读，不动代码

```
[ ] 读 START-HERE.md（项目现状）
[ ] 读本文件 §1 架构地图 + §2 关键不变量
[ ] 读 LESSONS.md 第九部分（坑 17-22，尤其 21/22）
[ ] 读 VERSIONS.md（当前装的是哪个 VERSION）
[ ] 跑一遍三件套 + run-t9-full.ps1，确认环境是好的
```

### 第 2 小时：确认基线

```powershell
# 确认工作区干净（应该只有已知的未跟踪 md）
git status --porcelain

# 确认默认值零改动
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
echo "exit=$LASTEXITCODE"   # 必须 0

# 确认 core/ 无新增
git status --porcelain -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/

# 确认当前安装的是哪个版本
$lib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib'
(Get-FileHash -Algorithm SHA256 (Join-Path $lib 'jetbrains-neovide-cursor-0.5.0.jar')).Hash.Substring(0,16)
```

### 第 3 小时：如果要做改动

```
[ ] 从 §7 的某个 P 项或 §9 的重构步骤里选一个（不要一次做两件）
[ ] 写一份小契约（哪怕 20 行）：证据 / 改法 / 判据 / 禁改项
[ ] 先写探针，确认它在「改之前」是失败的
[ ] 改代码
[ ] 跑全量验证
[ ] 构建 + 追加 VERSIONS.md + 写回退脚本
[ ] 给用户装（先退出 IDEA、先 Move 后 Copy）
```

### 一句话原则

> **「我改了」≠「它生效了」≠「它没弄坏别的」**
>
> 三种断言需要三种证据：
> - 改了 → `git diff`
> - 生效了 → **逐帧数值 / 断言通过**（不是「代码跑通了」）
> - 没弄坏别的 → **parity 逐字节一致 + 200+ 断言全绿**

这一路的真问题（淡出 no-op、`fading` 永不终止、`nextDueNanos` 慢 25%、
B-1/B-2、R-01/R-02/R-03/R-09）**全部是「代码看起来对但数值不对」**，
全部靠独立验证用逐帧/逐字段证据抓出。

---

## 12. 文档索引（按需要查）

| 想干什么 | 读哪个 |
|---|---|
| 了解现状 | `START-HERE.md` |
| **接手这个项目** | **本文件** |
| 查踩过的坑 | `LESSONS.md`（九部分，坑 1-22） |
| 查某版改了什么 | `VERSIONS.md` |
| 查为什么要重构 | `NEXT-REFACTOR.md` |
| 查 0.9.4 设置界面的设计 | `SETTINGS-RESEARCH.md`（859 行） |
| 查 0.9.4 实现约束 | `SETTINGS-CONTRACT.md` |
| 查 0.9.5 方案 | `SETTINGS-UI-REDESIGN.md` |
| 查 0.9.5 实现契约 | `SETTINGS-UI-CONTRACT.md`（1546 行，v1.2） |
| 查 0.9.5 验证证据 | `VERIFY-SETTINGS-V2.md`（119 断言） |
| 查 0.9.5 审查 findings | `REVIEW-SETTINGS-V2.md` |
| 查上游历史结论 | `UPSTREAM-PITFALLS.md`（672 行，grep `STATUS=VIOLATED`） |

---

## 13. 如果还有问题

1. **先查 `LESSONS.md`** —— 坑 1-22 覆盖了绝大多数已知问题
2. **再跑验证** —— 很多「看起来像 bug」其实是环境问题（§8）
3. **最后才怀疑代码** —— 而且要用探针证明，不要凭读代码推断

> 记住一条：**这个项目的每一个结论都有实测证据**。
> 如果你要推翻某个结论，拿出你自己的实测数据，不要拿「我觉得」。
