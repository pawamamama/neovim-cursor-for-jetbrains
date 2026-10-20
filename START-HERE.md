# 新会话入口 —— 请先读这一页

> 给全新 AI 会话的交接。**读完这一页就知道全部现状。**
> 遇到具体问题：先查 [`LESSONS.md`](LESSONS.md)（踩坑与验证方法），
> 再查对应批次的契约（`OPTIM-BATCH1.md` / `FIX-BATCH2.md` / `FIX-BATCH3.md`）。

---

## 0. 一句话现状（2026-10-20）

**插件能用，用户认可。四轮已交付并安装：`0.9.4-settings`（第一个带设置界面的版本）。**
**工作区干净，HEAD = `3c3944d`。上游 issue/PR 已提 6 条。**

**0.9.4 交付了什么**（详见 `VERSIONS.md`）：
- 新增 `Settings | Tools | Neovide Cursor` 设置页（16 项参数）
- 新增 `NeovideCursorSettings`（`@State` + `PersistentStateComponent`），持久化到
  `<config>/options/NeovideCursorSettings.xml`
- 迁移并删除 `PropertiesComponent` 的两个旧键（消除三真相源）
- 新增 `NeovideCaretManager.applyConfig()` 编排（S0–S8）
- 修掉 `alwaysHighFps` 读不到时兜底 `false` 的 bug
- **参数默认值零改动**（`NeovideConfig.java` 零 diff，三件套全过）

**但设置界面有 4 个已实测确认的缺陷，见 §6.5 —— 这是下一轮的全部工作。**

**不要再做的三件事**（已验证走到尽头）：

**不要再做的三件事**（已验证走到尽头）：

1. 优化绘制 —— 12 层辉光只有 **0.05ms/帧**（8ms 预算的 0.6%）
2. 减少重绘面积 —— **面积大 67 倍反而快 15 倍**
3. 叠加层数调参 —— 降辉光层数实测**无 fps 收益**

**真正的瓶颈是 EDT 抢占**：插件只占 EDT 的 10.7%，而 IDE 自身 `ScaledBlit.Scale` 占 12.6%。

---

## 1. 项目是什么

**IntelliJ IDEA 插件**：`com.github.yyyolo7a79.neovidecursor`
Neovide 风格的「拖尾光标」—— 光标移动时留下被拉伸的渐隐残影 + 辉光。

| 项 | 值 |
|---|---|
| 用户仓库 | `D:\deepseekwork\neovim-cursor-pr` |
| 当前分支 | **`feat/settings-ui-ready`** |
| 当前提交 | **`3c3944d`** |
| 上游 | `yyyyolo7a79-sketch/neovim-cursor-for-jetbrains` @ `6bed09d`（v0.5.0） |
| 用户 fork | `pawamamama/neovim-cursor-for-jetbrains` |
| 上游作者状态 | 说要「用 Opus 5.5 重构」 |

---

## 2. 当前安装的版本（权威，已实测）

```
v0.9.4-settings
大小:   70,056 B
SHA256: D30B23E2FF59C33051C67F1614B586A2CAD70B3266EF7515F83DB9E0DD527F4D
内容指纹: META-INF/plugin.xml = 7D5CDEF493B4DFA6   ← 判断内容请用这个
```

> ⚠️ **jar 的整体 SHA256 每次重建都会变**（zip 条目记录 .class 的 mtime，而 javac 每次重编
> 都会刷新它）。所以整体哈希只能验「文件没被替换」，不能验「内容有没有变」。
> 判断内容一致请看 `VERIFY-SETTINGS.md` §12.2 的 27 条稳定指纹表。

**安装路径**（文件名仍是旧的 `0.5.0.jar`，安装脚本固定此名）：

```
C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.5.0.jar
```

### Tools 菜单（5 项，0.9.4 未改）

```
Neovide 拖尾光标（开关）
Neovide 光标：性能诊断（禁用重绘）
Neovide 光标：残影模式（低帧率更稳）
Neovide 光标：隐藏原生光标（常驻绘制）      ← 默认已勾选
Neovide 光标：辉光淡出（停止后柔和熄灭）    ← 默认已勾选
```

### 设置页（0.9.4 新增）

`Settings | Tools | Neovide Cursor` —— 16 项参数，分 4 组：
总开关 / 淡出 / 颜色与辉光 / 拖尾手感。

> ⚠️ **Tools 菜单与设置页写同一份 state**，但**面板打开期间点 Tools 会丢失操作**
> （面板的 `pending` 会覆盖回去）。见 §6.5 缺陷 3。

### 关键配置（权威）

| 参数 | 值 | 说明 |
|---|---|---|
| `alwaysHighFps` | **true** | 常驻刷新，恒 8ms（≈125fps）；**0.9.4 前读不到时兜底 false，已修** |
| `hideNativeCaret` | **true** | **批次2 起默认勾选**（有意偏离上游踩坑 #19） |
| `overlayFadeOut` | **true** | 辉光淡出开关（批次2 新增） |
| `cursorDisappearDelay` | 50 | 淡出前保持毫秒数 |
| `cursorFadeOutDuration` | 0.075f | 淡出秒数（75ms） |
| `glowLayers` | 8 | |
| `ACTIVE_DELAY_MS` | 8 | **不要改**（12 会"傻快"，用户实测） |
| `shortAnimationLength` | 0.11 | 单行动画时长 |
| `animationLength` | 0.10 | 跨行动画时长 |
| `trailFactors` | {1.0, 0.72, 0.42, 0.22} | 四角梯度，4.5 倍 |
| `maxTrailDistanceFactor` | 60 | 上游 README 说 4，代码是 60（见 issue #7） |

---

## 3. 回退（随时可用，脚本已入库）

```powershell
# 回 0.9.2（注意：0.9.2 从未装过）
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-0.9.3.ps1"

# 回 0.9.1 / 0.9.0 / 071 / 080 / uni（历史上用户认可的几个基线）
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-0.9.2.ps1"
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-0.9.1.ps1"
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-071.ps1"
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-080.ps1"
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-uni.ps1"
```

**安装/回退的铁律**：
1. **完全退出 IDEA**（它内存映射锁住 jar）
2. **先 `Move-Item` 旧 jar，再 `Copy-Item` 新 jar** —— 直接覆盖会报「用户映射区域」错误
3. 重启 IDEA 生效

`dist\` 里有全部历史 jar，**`VERSIONS.md` 是对照表**（唯一可信的版本记录）。

---

## 4. 三轮已完成的工作

| 批次 | 版本 | 内容 | 契约/报告 |
|---|---|---|---|
| **1** | `0.9.1-zeroopt` | 零观感优化：fps 日志门控、每帧分配复用、删空转链路、删 7 死字段、**N 条调度线程 → 1 条共享线程** | `OPTIM-BATCH1.md` / `VERIFY-BATCH1.md` / `REVIEW-BATCH1.md` |
| **2** | `0.9.2-imetime-fade` | IME 判据第一版（**对水杉无效**）、**辉光淡出 + 开关**、`hideNativeCaret` 默认改勾选 | `FIX-BATCH2.md` / `VERIFY-BATCH2.md` |
| **3** | `0.9.3-imegeom` | **IME 判据改几何比较** + virtual space 排除 | `FIX-BATCH3.md` / `NEXT-TASK-IME.md` |
| **4** | `0.9.4-settings` | **设置界面 16 项** + `NeovideCursorSettings` 持久化 + 旧键迁移删除 + `applyConfig()` 编排 + `alwaysHighFps` 兜底 bug | `SETTINGS-CONTRACT.md` / `VERIFY-SETTINGS.md` / `REVIEW-SETTINGS.md` |

### 几个值得记住的数字

| 指标 | 值 |
|---|---|
| 空闲档帧率（实测） | **188 帧 / 1.5s ≈ 125fps**（触及 `ACTIVE_DELAY_MS=8` 理论上限） |
| EDT 繁忙档（tick 25ms） | 57~60 帧（基线 58~59，噪声范围内） |
| 线程数 | N 编辑器 → **1 条**（基线 N 条） |
| parity | 三份 harness **逐字节一致** |

---

## 5. 上游 issue / PR 清单

| 编号 | 类型 | 内容 | 状态 |
|---|---|---|---|
| [PR #4](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/pull/4) | PR | `lastNanos` 一行修复 | open |
| [#5](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/5) | issue | CJK 短跳判据（像素 → 字符偏移） | open |
| [#6](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/6) | issue | IME 中间态（**含更正评论**：非通用 IME 问题） | open |
| [#7](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/7) | issue | `maxTrailDistanceFactor` 与 README 矛盾 | open |
| [#8](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/8) | issue | 1ms busy-retry 退避代价 ~3.4% | open |
| [#9](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/9) | issue | virtual space 使两 API 合法分岔 | open |

---

## 6. 未完成（按建议优先级）

| 优先级 | 项 | 收益/说明 |
|---|---|---|
| **P0** | **设置界面 4 个缺陷**（见 §6.5） | 用户已实测反馈，滑块不跟手 + 数字挤压 + Tools 覆盖 + 缺预设 |
| **P1** | **设置界面重做**（`SETTINGS-UI-REDESIGN.md`） | 布局、预设、滑块吸附、`animationLength` 进可调 |
| P2 | 空闲时不再往 EDT 交脏区 | 完全静止也每 8ms 交一次；8 编辑器 ≈ 1000 次/秒重画**已经正确**的画面 |
| P3 | 隐藏标签页降频 | 现在 `isShowing()` 只是不画，调度仍 8ms、每帧照样调 `visualPositionToXY` |
| P4 | 多光标（Alt 列选） | **已确认不支持**（`getCaretModel().getVisualPosition()` 只取主光标）；上游有 `SpringTrail` 可参考 |
| P5 | 流光 RGB 特效 | 用户想要浅色系炫酷效果；需改绘制 + 实测 p95 帧间隔 |
| P6 | 辉光双色渐变 | 用户想要"扩散也能两种颜色"；需改 `drawQuadGlow` |
| P7 | `BUSY_RETRY_MS 1→0` | 追平 EDT 繁忙时 −3.4%；代价轻微空转（见 issue #8） |

---

## 6.5 【重要】设置界面已实测确认的 4 个缺陷（下一轮必修）

> 这些是 2026-10-20 由 captain 写探针**实机复现**的，不是猜的。
> 探针源码在临时目录（未入库），复现方法见每条的「证据」。

### 缺陷 1（高）：8 个滑块的数值标签拖动时**一个都不更新**

**现象**：拖滑块，右边 `50 ms` / `0.55` / `8 层` 这些数字不动。

**根因**（实测）**：`SliderKt.bindValue(...)` 没有在 JSlider 上注册 `ChangeListener`。
```
滑块[0] 上的 ChangeListener 数量 = 0
拖动前 pending.cursorDisappearDelay = 50
拖动后 pending.cursorDisappearDelay = 50      ← pending 不跟随
标签跟随变化的滑块数 = 0 / 8（不跟随 = 8）
```
→ 拖动只改了组件值，`pending` 不变，标签自然不刷新（要到 `apply()` 才取组件值）。

**修法方向**：给每个 slider 显式 `addChangeListener`，或用 `Cell.onChanged`（注意它带
`@ApiStatus.NonExtendable`，第一期契约刻意没用），或改用 `slider + intTextField` 双向绑定。

### 缺陷 2（中）：刻度数字**挤成一团**

**现象**：滑块下方一串数字糊在一起（截图已确认）。

**根因**（实测）：DSL `slider()` 默认 `paintLabels=true`，每个刻度都画标签。
```
滑块[0] range=0..500  step=10  paintLabels=true  labelTable条目=51   ← 51 个数字
滑块[1] range=0..1000 step=5   paintLabels=true  labelTable条目=201  ← 201 个数字！
滑块[3] range=0..100  step=1   paintLabels=true  labelTable条目=101
```

**修法方向**：`SliderKt.labelTable(cell, Map)` 只给关键刻度画标签，或 `setPaintLabels(false)`
改用 `showValueHint()` + 右侧数值标签。

### 缺陷 3（高）：**面板打开期间点 Tools 菜单，操作会被覆盖**

**现象**：面板开着 → 去点 Tools 菜单取消「辉光淡出」→ 回来点 OK → **取消被撤销了**。

**复现结果**（实测）：
```
点 Tools 取消后： state.overlayFadeOut = false  /  pending.overlayFadeOut = true
面板点 OK 后：   state.overlayFadeOut = true   ← Tools 的操作被覆盖回 true
```

**根因**：面板的 `pending` 是 `reset()` 时的快照，`apply()` 无条件用 `pending` 覆盖 state。
期间任何外部改动（Tools 菜单）都会被冲掉。

**修法方向**（三选一，待方案定）：
- (a) Panel 打开时禁用 Tools 菜单项
- (b) `apply()` 前重新读 state，只写用户真正改过的字段（脏字段跟踪）
- (c) Tools 菜单改动时通知已打开的面板刷新 `pending`

### 缺陷 4（中）：切主题后原生光标**复活**，插件以为它还隐藏着

**根因**（代码已确认）：全仓库搜 `EditorColorsListener` / `ColorsListener` / `schemeChanged`
→ **零命中**。而 `applyNativeCaretHidden()` 有早退：

```java
// NeovideCaretManager:606
if (savedCaretColor != null) {
    return;   // ← 以为"已经隐藏过"
}
```

**后果链**：插件隐藏原生光标 → 用户切主题（scheme 整个被换掉，透明色丢失）→
原生光标重新可见 → 但 `savedCaretColor != null`，插件**不会重新隐藏** →
**原生光标 + 覆盖层两套同时绘制**（用户感知：更卡、双光标）。

**这解释了用户那句「原生光标已经废掉了」的困惑** —— 它没废，是切主题后复活了。

---

## 7. 已知但**不影响使用**的问题

| 项 | 影响 |
|---|---|
| 水杉输入法组合态 | **0.9.3 已修**（几何判据），但**缺一次真机日志确认**；探针在 `dist\neovide-ime-probe.zip` |
| `BUSY_RETRY_MS=1` | EDT 繁忙时 −3.4%（系统性，但用户感知不到） |
| 控制台光标 | 由 `tickStaticCaret` 补画（与上游"跳过 CONSOLE"不同） |
| `isInVirtualSpace` 用字符数比视觉列 | 保守方向，只会漏拦不会误拦 |

---

## 8. 工作纪律（血的教训，见 `LESSONS.md`）

1. **改前先查** `LESSONS.md` 与对应批次契约
2. **一次只改一个变量**
3. **改完立刻构建 + 让用户验证**
4. **不合意立刻回退**（脚本现成）
5. **每次构建追加 `VERSIONS.md`**
6. **不要凭直觉调参数** —— 这一路的失败全部源于此

### 验证三件套（改代码后必跑）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1"
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-audit.ps1"
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\ime-probe\run-ime-geom-test.ps1"
```

判据：
- parity **三份逐字节一致**
- 审计 **19 项只能有 `hideNativeCaret` 一项 DIFF**
- IME 边界 **29 项全过**

### 最重要的一条

> **"我改了" ≠ "它生效了" ≠ "它没弄坏别的"**
>
> 这三件事需要三种证据：`git diff` / **逐帧数值** / **parity 逐字节一致**。
> 这一路三个真问题（淡出 no-op、`fading` 永不终止、`nextDueNanos` 慢 25%）
> 全部是「代码看起来对但数值不对」，全部靠逐帧证据抓出。

---

## 9. 构建方法（本机无可用 Gradle）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1" `
  -Version 0.9.4 `
  -OutName "my.jar" `
  -BaseJar "D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.4-settings.jar"
```

前置：`javac 25.0.2` 在 PATH；`D:\deepseekwork\_idea-anim\idea-libs` 有 381 个 jar。

---

## 10. 文档索引

| 文件 | 什么时候读 |
|---|---|
| **本文件** | 开局必读 |
| **`LESSONS.md`** | **改代码前必读**（踩坑 + 验证方法 + 上游差异） |
| `VERSIONS.md` | 想知道某版改了什么 / 找可用 jar |
| `OPTIM-BATCH1.md` | 批次1 契约（零观感优化的边界） |
| `FIX-BATCH2.md` | 批次2 契约（IME 判据 / 淡出 / 默认值） |
| `FIX-BATCH3.md` | 批次3 契约（几何判据） |
| `VERIFY-BATCH1.md` / `VERIFY-BATCH2.md` | 独立验证报告（含 finding 与证据） |
| `REVIEW-BATCH1.md` | 审查报告（含已更正的两处自身错误） |
| **`SETTINGS-RESEARCH.md`** | 设置界面的调研（24 字段逐个过、Kotlin UI DSL 可 Java 调用已实测） |
| **`SETTINGS-CONTRACT.md`** | 0.9.4 的实现契约 v2.1（16 项字段 / 三个前置改造 / 35 项禁改） |
| **`VERIFY-SETTINGS.md`** | 0.9.4 独立验证报告（九项 + §12 jar 稳定指纹表） |
| **`REVIEW-SETTINGS.md`** | 0.9.4 三轮审查报告（t5 抓出 2 个 high、t8 变异测试、t9 字节绑定） |
| **`SETTINGS-UI-REDESIGN.md`** | 0.9.5 方案（真实参数调研 + 8 动画预设 × 5 颜色预设 + 布局线框） |
| **`SETTINGS-UI-CONTRACT.md`** | 0.9.5 实现契约 v1.2（4 个缺陷修法 + §1.3.1 参考实现骨架） |
| **`VERIFY-SETTINGS-V2.md`** | 0.9.5 独立验证（119 断言，含 5 条偏差裁决） |
| **`REVIEW-SETTINGS-V2.md`** | 0.9.5 两轮审查（抓出 R-01/R-02/R-03/R-09/R-10） |
| **`NEXT-REFACTOR.md`** | **下一版设置面板结构重构计划**（God Class 拆分，不改行为） |
| `BUG-REPORT-CJK-IME.md` | IME 错位的原始取证（四组实验） |
| `UPSTREAM-REPORT-2026-10-18.md` | 给上游的报告（含自我审查） |
| `NEXT-TASK-IME.md` | 水杉输入法问题的交接（已被 0.9.3 解决，留作历史） |
| `HANDOFF-FINAL.md` / `HANDOFF.md` | **已过时**，仅历史参考 |
| `UPSTREAM-PITFALLS.md` | 上游 21 条结论（672 行，可 grep `STATUS=VIOLATED`） |

---

## 11. 一句话给新会话

**0.9.4 已装、能用、参数零改动。但设置界面有 4 个已实测缺陷（§6.5），那是下一轮的全部工作。**

**改代码前先跑验证三件套。真正的瓶颈是 EDT 抢占，剩下的空间是"少做无用功"，不是"画得更快"。**

**判据铁律**：`我改了` ≠ `它生效了` ≠ `它没弄坏别的` —— 三种证据：`git diff` / 逐帧数值 / parity 逐字节一致。
