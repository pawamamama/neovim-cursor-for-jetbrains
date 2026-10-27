# 新会话交接：项目了解 + 重构方案

> 2026-10-27　给新会话的两轮材料。
> **顺序：第一轮读「项目了解」，第二轮读「重构方案」并挑错，第三轮才动手。**

---

# 第一轮：项目了解

---

## 这是什么

**JetBrains IDE 插件，给光标加拖尾动画。**

普通光标是硬跳。这个插件让光标移动时四角各挂一根弹簧，按运动方向分配不同速度 ——
形状被拉成平行四边形，看起来像 Neovide（Neovim 的 GUI）那种顺滑拖尾。

**核心一句话**：不是残影叠加，是**同一块矩形被四个角拉变形**。

---

## 真实规模（2026-10-27 实测）

```
生产代码    34 文件 / 15,130 行
探针代码    91 个 V 探针 / 18,397 行     ← 比生产代码还多
文档        83 个 docs + 17 个根目录 md
```

**探针比产品代码多，这是本项目最独特的特征。**
不是「写完顺手测一下」，是「改任何行为都必须先有探针守着」。

---

## 三个包

### `core/` —— 物理引擎（5 文件 / 1,084 行）

**最稳定，也最不能动。**

| 文件 | 职责 |
|---|---|
| `DampedSpring.java` | 阻尼弹簧积分。首句 `if (animationLength <= dt) { position = 0; }` —— **时长小于一帧 = 瞬移** |
| `TrailCorner.java` | 单角时长分配。`jump()` 分两支：短跳 / 跨行 |
| `NeovideConfig.java` | **D0 门禁冻结，一个字都不能改** |
| `AfterimageTrail.java` | 残影模式 |
| `NeovideColors.java` | 颜色解析 |

### `settings/` —— 状态与预设（4 文件 / 1,102 行）

| 文件 | 职责 |
|---|---|
| `NeovideCursorSettings.java` | **State 的 30 个字段**，唯一真相源 |
| `SettingsField.java` | 字段表，`FIELDS` / `PRESET_FIELDS` / `RUNTIME_SWITCH_KEYS` |
| `CursorPresets.java` | 三个内置预设（基础 / 缎带 / 纯平滑输入） |
| `CustomPresets.java` | 用户自建预设 |

### `ui/` —— 绘制与调度（25 文件 / 11,368 行）

| 文件 | 行数 | 职责 |
|---|---|---|
| `NeovideCursorConfigurable.java` | **3,110** | 设置面板（全项目最大） |
| `CaretAnimator.java` | **2,617** | 每个编辑器的驱动器 |
| `CaretTrailPanel.java` | 1,109 | 真正的绘制 |
| `NeovideCaretManager.java` | 932 | 生命周期 |
| `SpringTrail.java` | 330 | 四角 + rank 排名 |
| `RenderScheduler.java` | — | 共享调度线程 |

---

## 一帧怎么走完

```
① RenderScheduler 到点（8ms / 120ms / 600ms 三档）
        ↓
② CaretAnimator.refreshTarget()
        ↓
③ 【快速路径】位置没变？→ 直接 return
        ↓
④ 读光标位置 → cp.x / cp.y / lineHeight
        ↓
⑤ SpringTrail.setTarget() → 变了就 jump()
        ↓
⑥ TrailCorner.jump() 分两支：
        短跳  → shortAnimationLength × trailFactors[rank]
        跨行  → animationLength × (0/0.5/1)
        ↓
⑦ 每帧 DampedSpring.update(dt) 推向目标
        ↓
⑧ CaretTrailPanel 画 quad + 辉光
```

---

## 三个真相源

| 层 | 在哪 | 谁写 | 谁读 |
|---|---|---|---|
| **State** | `NeovideCursorSettings.State` | 面板、预设 | `syncStateToConfig()` |
| **CONFIG** | `NeovideConfig` | `syncStateToConfig()` | `TrailCorner` / `CaretTrailPanel` |
| **预设表** | `CursorPresets.ANIM_PRESETS` | 硬编码 | 用户点预设时才写进 State |

**判断改动是否安全，先问：它动的是哪一层？**

---

## 三道门禁

### ① D0：`core/NeovideConfig.java` 零 diff

```bash
cd D:/deepseekwork/neovim-cursor-pr
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

盲区：比的是「工作区 vs 索引」。改了再 commit，D0 看不见。

### ② 全量探针（唯一权威入口）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1
```

**⚠️ 不许并发跑两份** —— 共用 `build\v2-probe\classes`，编译那步会清空目录 →
另一份 `ClassNotFoundException` → 失败数从 8 暴涨到 18。**这是假象。**

**基线（2026-10-27 实测）**：
```
91 个探针 / 8 个未通过 / 总 FAIL 26
未通过：VDefects(1) VSnap(10) VTools(1) VHints(1) VCustom(1)
        VReset2(2) VSepBound(7) VSleep(1)
另有 VRegr2 为红但归入 informational，不计入 8
```

### ③ 逐行看 `git diff`

**这条比前两条重要** —— 探针覆盖率再高，也测不到「你改了不该改的地方」。

---

## 五个坑

**1. 注释会撒谎**
`TrailCorner.java:203` 注释写「修复：判据不再直接用累计字符数」，
`:210` 代码**原封不动还是那样**。→ **看代码，别信注释。**

**2. 文档数字会过期**
`CLAUDE.md` 写「探针 87 个 / 79 个通过」，实测 91 / FAIL 26。→ **自己跑一遍。**

**3. 「字段接通」≠「字段生效」**
`targetFps` 曾经：声明有、FIELDS 有、copyFrom 有、loadState 有 ——
但消费代码读的是常量。→ **加字段必须问：谁在读它？**

**4. 探针会骗你**
① 测的是生产不用的平行实现；② 断言太弱，改坏了也不红。
→ **验真唯一方法：变异测试。**

**5. 安装脚本会清空配置**
`build/install.ps1` 每次删 `NeovideCursorSettings.xml`。**不是 bug。**

---

## 纪律

| 纪律 | 内容 |
|---|---|
| **四-K** | 只有用户一个，**不写兼容代码** |
| **四-L** | 问**产品决策**，不问**实现细节** |
| **四-M** | **字段名必须带中文**：`slopeTicks（斜度）`、`trailSize（拖尾长度）` |
| **二.6** | 探针报红先查原因，**改方向不删断言** |
| **四-G** | 探针必须调用生产方法；变异测试验真 |

**用户水平**：能读 Java，写过坦克大战 / QQ 终端网络，但**不参与实现决策**。
判据：**这个问题如果答错，用户能看出来吗？** 看不出来就别问。

---

## 当前状态

```
分支        main
HEAD        8f58b85
工作区      干净
已装 jar    0.9.92（只有斜度修复 + 光标漂移修复，**不含**连打修复）
```

### 未合并的连打修复

```
分支  claude/typing-jitter-fix-6c8c96（在 worktree d0-gate-probe-count-996441 里）
提交  c13f517  fix(trail): 给跨行分支的前缘时长加下限
```

**用户实测反馈（重要）**：
> 「没有明显修复，而且用鼠标拖尾明显变小，缎带没有变化」

**结论：这个修复方案可能本身有问题。不要假定它是对的，也不要合进 main。**

### 待查的观感问题

**「闪烁动画快」**（用户 2026-10-27 命名）

- 现象：基础和缎带打字时**一闪一闪**，之前有过，现在又出现
- 用户感觉辉光有点敷衍
- **已排除**：不是连打修复引起的（0.9.92 已剥离该修复，问题需重新验证）
- **已确认**：`CaretTrailPanel` 近期被动过
  - `305fdae` 彩虹与光标渐变分离（+53/-13）
  - `a8110fd` 光标渐变改双拼（+65/-20）
- **已知平台问题**：`JBR-6047` 高刷（>60Hz）+ GPU 加速 → 闪烁，JetBrains 未修
- **已修复过的同类问题**：`c202420` 亚像素坐标（Polygon int → Path2D double），可确认仍在 main 上

---

# 第二轮：重构方案（RUNTIME_SWITCH_KEYS → runtimeSwitch）

---

## ⚠️ 先纠正一处错误

旧的 `docs/REFACTOR-FEASIBILITY.md` 里有一句是错的：

> 「一个字段属不属于预设，由两处决定 —— `inPreset` 布尔值 + `RUNTIME_SWITCH_KEYS` 字符串数组。两处不一致时，谁赢？」

**实测：`inPreset` 对所有字段都是 `true`，它根本没有区分作用。**

`SettingsField.java:311-374` 的所有工厂方法末尾都写死：

```java
gc != null, true);      // ← 第二个 true 就是 inPreset
```

**所以真正决定「进不进预设」的，只有 `RUNTIME_SWITCH_KEYS` 这一份。**

这不是「两个判据打架」，是**「一个有效判据 + 一个摆设」**。

---

## 改动清单

### 生产代码

| # | 路径 | 改什么 |
|---|---|---|
| 1 | `src\main\java\...\settings\SettingsField.java` | 构造器加 `boolean runtimeSwitch` 参数 + 字段 |
| 2 | 同上 | 5 个工厂方法（`bools`/`ints`/`floats`/`hexColors`/`enums`）加同名参数 |
| 3 | 同上 | 9 个字段传 `runtimeSwitch=true`，其余传 `false` |
| 4 | 同上 | `restoreSkipping` 改成 `if (f.runtimeSwitch) continue;` |
| 5 | 同上 | **删掉 `RUNTIME_SWITCH_KEYS` 数组** |
| 6 | `src\main\java\...\ui\CursorPresetApplier.java:114-116` | 改调用（去掉数组参数） |
| 7 | `src\main\java\...\ui\settings\NeovideCursorConfigurable.java:1775-1776` | 同上 |

### 探针

| # | 路径 | 改什么 |
|---|---|---|
| 8 | `tools\settings-probe\VSwitchKeysGap.java` | **改方向**：从查数组改成查字段标志 |
| 9 | `tools\settings-probe\VLoadState.java:100-103` | **必须改**（数组删了会编译不过） |

**9 个 runtimeSwitch=true 的字段**：
```
enabled（总开关）、hideNativeCaret（隐藏原生光标）、trailMode（绘制模式）、
alwaysHighFps（常驻刷新）、targetFps（目标帧率）、cursorWidthPx（光标宽度）、
cursorHeightRatio（光标高度比例）、diagLogging（诊断日志）、multiCursorNoGlow（多光标不辉光）
```

---

## 探针影响

### `VSwitchKeysGap` —— 改方向，不删

现在：从 `RUNTIME_SWITCH_KEYS` 数组建集合，断言 5 个设备级字段都在。

改后：从 `FIELDS` 里筛 `runtimeSwitch=true`，同样的断言，**再加一条反向断言**
（不该跳过的字段必须 `runtimeSwitch=false`）。

**断言数量应该变多，不是变少。**

### `VLoadState` L-5 —— 必须改

引用 `RUNTIME_SWITCH_KEYS` 会编译失败。改成数带标志的字段数。

### 其他探针

**预测不会红**：`VDefaultFreeze` 不碰预设恢复；`VCustom` 测快照往返（`PRESET_FIELDS`，不动）；
`VPreset`/`VSlopePreset` 走 `applyAnimPresetFields`。

**但必须实测**，基线 8 个，重构后**不得增加**。

---

## 分步执行

### 步骤 1：只加字段，不改行为

改 `SettingsField`：加字段 + 构造器参数 + 工厂参数 + 9 个字段传 true。

**`restoreSkipping` 暂不改**（继续用旧数组）。

**验证**：全量探针 —— **应该还是 8 个未通过**。

**这一步的关键**：行为零变化。数字变了说明改错了。

### 步骤 2：改 `restoreSkipping` 用新标志

内部改成 `if (f.runtimeSwitch) continue;`，保留 `skip` 参数但不使用。

**验证**：全量探针 —— **还是 8 个**。

### 步骤 3：删数组 + 改调用 + 改探针

删 `RUNTIME_SWITCH_KEYS`，改两处调用，改两个探针。

**验证**：
```bash
# ① 编译必须过
# ② D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
echo "D0 exit=$?"
# ③ 全量探针
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1
```

**预期**：8 个未通过。

### 步骤 4：变异测试（强制）

把 `targetFps` 的 `runtimeSwitch` 从 `true` 改成 `false`，重跑。

**预期**：`VSwitchKeysGap` 必须变红。**没红就重写探针。**

改回 `true`，重跑确认全绿。

### 步骤 5：提交

逐行确认只动了这 5 个文件，commit body 写清「改了什么 / 为什么 / 怎么验的」。

---

## 红线

| # | 红线 |
|---|---|
| R1 | **D0 不能破**：`core\NeovideConfig.java` 零 diff |
| R2 | **不碰 `copyFrom` / `loadState`**（第二优先，风险更大） |
| R3 | **不碰面板**（3,110 行，完全不同的复杂度） |
| R4 | **不许并发跑两次门禁** |
| R5 | **探针基线不得增加**：8 个，重构后还是 8 个 |
| R6 | **改方向不删断言** |
| R7 | 单次改动 ≤150 行（预计 80~100 行） |
| R8 | **不碰「闪烁」「辉光」相关问题** —— 那是独立的手艺问题，不在本重构范围 |

---

## 执行流程（用户明确要求）

1. **第一轮**：读「项目了解」，**不要动手**，说完理解了什么就停
2. **第二轮**：读本文「重构方案」全文，**挑错**
   - 改动清单有没有漏文件
   - 探针影响分析对不对
   - 分步顺序有没有逻辑漏洞
   - 有没有碰红线
3. **第三轮**：用户说「做」之后才给命令并执行

**不要跳过第 2 步的挑错环节。**

---

## 工作区

```bash
cd D:/deepseekwork/neovim-cursor-pr
git switch -c refactor/runtime-switch-flag main
```

**必须在主仓库 `D:\deepseekwork\neovim-cursor-pr` 做，不要在 worktree 里做。**

理由：
- 主仓库在 `main`（8f58b85），工作区干净
- worktree `d0-gate-probe-status-caadda` 挂在另一个分支上，且是给别的会话用的
- worktree `d0-gate-probe-count-996441` 挂着连打修复分支，**绝对不能在那上面改**

**门禁命令里不要加 `-Repo`**（默认就指向主仓库）。

---

## 已安装版本

```
0.9.92 = main (8f58b85)
       = 斜度修复（slopeTicks 默认 0）
       + 光标纵向漂移修复
       + VDefects 断言修正
       − 连打修复（已剥离，用户要求的）
```

**重构完成后不要自动装 jar**，等用户决定。
