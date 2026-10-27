---
name: neovide-cursor-plugin-handoff
description: 接手 D:\deepseekwork\neovim-cursor-pr 这个 JetBrains 光标拖尾插件项目时先读。覆盖三件事：项目的地形（34 个生产文件 / 80 个探针 / 三份参数清单）、动任何代码前必须跑的门禁（D0 零 diff + PhysicsParity + 全量探针）、以及这个项目特有的六类陷阱（参数归属混乱、探针复刻逻辑、预设两套机制、颜色/动画域重叠、字段四层拷贝、改预设不动断言）。当用户要求修改这个插件的观感参数、加设置项、改预设、修手感问题时使用。
---

# 接手这个项目之前

## 一、先认清地形

```
生产代码    src/main/java/.../neovidecursor/    34 文件 / ~14k 行
探针        tools/settings-probe/V*.java        80 个（71 通过）
基准程序    tools/bench/Bench*.java             16 个（性能实测用）
```

**关键：探针数量比生产代码还多。** 这个项目的惯例是「改任何行为都要有守门探针」。

### 目录职责

| 目录 | 职责 | 能改吗 |
|---|---|---|
| `core/` | 物理引擎（DampedSpring / TrailCorner / NeovideConfig） | **`NeovideConfig` 零 diff（D0 门禁）** |
| `settings/` | State / 预设 / 字段表 | 可改，但要同步四层 |
| `ui/` | 绘制 + 调度 | 可改 |
| `ui/settings/` | 设置面板 | 可改 |

---

## 二、三道门禁（改完必跑）

```powershell
# ① D0：core/NeovideConfig.java 必须零 diff
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java

# ② PhysicsParity：逐字节黄金基准
java -cp "build\parity;build\v2-probe\classes;<idea-libs>" PhysicsParity

# ③ 全量探针（从文件读清单，不要手写副本）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1
```

**当前基线**：探针 71/80 通过，9 个已知失败都是过期断言（见 `docs/NEXT-STEPS.md`）。

---

## 三、六类陷阱（都踩过）

### 陷阱 1：参数归属有三套清单，别把差异当 bug

```
AnimPreset                    14 个观感参数
SettingsField.PRESET_FIELDS   26 个（给自定义预设快照）
applyAnimPresetFields 实写    13 个（给内置预设）
```

**26 ≠ 14 是设计结果**，不是 bug。完整归属表见 `docs/PARAMETER-MAP.md`。

**但**：`RUNTIME_SWITCH_KEYS`（自定义预设跳过清单）曾经只有 4 项，
漏了 5 个设备级字段（`targetFps` / `cursorWidthPx` / `cursorHeightRatio` /
`diagLogging` / `multiCursorNoGlow`）。0.9.87 补到 9 项。

### 陷阱 2：改了一个字段，四处都要动

```
① State 字段声明
② SettingsField.FIELDS 注册
③ State.copyFrom() 拷贝
④ loadState() 拷贝
⑤ 消费者（真正用它的地方）
⑥ UI 控件
```

**漏任何一处 = 「重启即失效」**。`targetFps` 出过一模一样的问题。
守门探针：`VDeadField`（检查字段是否被真正消费）、`VLoadState`（检查拷贝完整性）。

### 陷阱 3：探针会复刻逻辑而不是调用生产代码

`AnimationPreview.assignRanks()` 曾经有一份**独立的 rank 实现**，
与生产代码的 `SpringTrail.assignRanks()` 分家，导致「预览不变但实际变了」。

**规则**：探针要么调用生产方法，要么断言生产行为，不许自己重写一遍算法。

### 陷阱 4：颜色域与动画域重叠

`tailColor` / `shadowColor` / `rgbGradient` 三个字段：
- 由**颜色预设**写
- 同时又标着 `inPreset=true`，所以**自定义动画预设也会写**

「动画与颜色正交」只在**内置预设**这条路径上成立。改这块前先读 `PARAMETER-MAP.md` §5。

### 陷阱 5：改预设必须同步改断言

改 `AnimPreset` 的默认值 → `VSlopePreset` / `VDefaultFreeze` / `VPreset` 会红。
**按纪律：改断言方向，不许删断言。** 在断言旁边写清「为什么方向变了」。

### 陷阱 6：`install.ps1` 会清空用户配置

`build\install.ps1` 的设计是**每次干净安装都删配置 XML**（用户 0.9.70 明确要求）。
所以「改了设置但重启就没了」很可能不是 bug，是安装脚本清空。
验证方法：跑 `VFpsPersist` 之类的持久化探针，确认链路本身是通的。

---

## 四、这个项目的历史包袱（不要重复踩）

| 坑 | 结论 | 出处 |
|---|---|---|
| 前缘硬吸附（左半边） | **做不到**：上游 2 角点、我们 4 角，几何上不成立 | `UPSTREAM-VSCODE-RESEARCH.md` §3.1 |
| 辉光跟随光标 | **从未存在过**，一直是独立 `shadowColor` | 全部 git 历史 |
| 描边替代多层 fill | 实测 0.94x（更慢） | `PERFORMANCE-FPS.md` |
| `timeBeginPeriod` 提帧率 | 无收益 | 同上 |
| 自适应辉光降级 | 用户否决（与多光标开关语义重复） | `NEXT-STEPS.md` §3 |

---

## 五、工作方式（用户明确要求）

1. **语义不清晰先问，不许猜** —— 见 `WORK-DISCIPLINE.md` 四-I。
   本项目在「RGB/彩虹/双拼/辉光」上连猜错 6 次。
2. **看可维护性，不是最小改动** —— 见四-J。
   两次「最小改动」累计产生 140 行死代码。
3. **加 UI 控件前先问**（用户 0.9.74 明确要求）。
4. **删除/移动前先列清单确认**。
5. **文档写「为什么」，不只写「是什么」** —— 用户是自学者，要能推理。

---

## 六、常用命令

```powershell
# 编译（日常走 javac；Gradle 也已完整入库，需要平台构件时可用）
$libs='D:\deepseekwork\_idea-anim\idea-libs'
$cp=(Get-ChildItem -LiteralPath $libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
Get-ChildItem -Recurse -File -Filter *.java 'src\main\java' | ForEach-Object { $_.FullName } | Set-Content build\v2-probe\srcs.txt
javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d build\v2-probe\classes "@build\v2-probe\srcs.txt"

# 打包 + 安装（会清空配置，先跟用户确认）
pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.93 -NoStart
```

---

## 七、文档地图

| 文档 | 什么时候读 |
|---|---|
| `WORK-DISCIPLINE.md` | **动手前必读**（570 行，含 12 条纪律） |
| `PARAMETER-MAP.md` | 改任何参数归属前 |
| `NEXT-STEPS.md` | 想知道当前状态 / 下一步做什么 |
| `design/UPSTREAM-VSCODE-RESEARCH.md` | 想对齐上游手感前 |
| `design/RANK-COLLAPSE-FIX.md` | 改 rank / 斜度前 |
| `PERFORMANCE-FPS.md` | 想优化性能前（含已证伪的方向） |
| `RELEASE-SAFETY.md` | 发版前 |