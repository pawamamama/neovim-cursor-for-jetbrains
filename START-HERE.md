# 开发档案 —— 从这里开始

> **版本：v0.9.93** · 最后更新：2026-10-27
>
> 这是本项目唯一的状态入口。如果你是新会话，读完这一页就知道该干什么。

---

## 0. 三十秒了解这个项目

**是什么**：JetBrains IDE 插件的光标拖尾动画（Neovide 风格）。
光标移动时不是简单平移，而是四角各自挂阻尼弹簧、按运动方向分配不同时长，
把矩形拉成平行四边形 —— 视觉上就是「拖尾」，不是叠加残影。

**技术栈**：Java 21。日常用 `javac` 直接编译 + 手写 `plugin.xml`（快）；
Gradle 也完整入库（`build.gradle.kts` / `gradlew` / `gradle/wrapper/`），需要平台构件时用它。**两条路径并存。**

**规模**（2026-10-27 实测）：
```
生产代码   src/main/java/.../neovidecursor/   34 文件 / 15,164 行
探针       tools/settings-probe/*.java        106 文件 / 23,570 行（91 个 V*.java）
基准程序   tools/bench/Bench*.java            16 个
```

**探针比生产代码还多** —— 这个项目的惯例是「改任何行为都要有守门探针」。

---

## 1. 三道门禁（改完必跑）

```powershell
# ① D0：core/NeovideConfig.java 必须零 diff
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java

# ② 全量探针（从文件读清单，不要手写副本）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\\settings-probe\\build-v-probes.ps1
```

**当前基线**：探针 **6 红** —— `VTools` / `VHints` / `VCustom` / `VReset2` / `VSepBound` / `VSleep`。
全是过期断言（不是功能故障），见 §6，不算新问题。
（`VDefects` 与 `VSnap` 已于 2026-10-27 按「改方向不删断言」修绿。）

---

## 2. 现在能做什么 / 什么不能做

### 已实现（不要重做）

| 功能 | 版本 | 守门探针 |
|---|---|---|
| 四角弹簧拖尾 | 0.9.0 | `PhysicsParity` |
| 设置面板（14 滑块 + 9 复选框 + 4 下拉） | 0.9.62 | 组件树比对 |
| 3 动画预设 + 6 颜色预设 | 0.9.62 | `VPreset` |
| 多光标 | 0.9.32 | `VMulti` |
| rank 塌缩修复（单行斜缎带） | 0.9.77 | `VRankCollapse` |
| 斜度参数（0=纯矩形 / 100=上游） | 0.9.81 | `VSlopePreset` |
| 光标渐变（双拼） | 0.9.85 | `VRgbWash` |
| 彩虹 / 双拼互斥 | 0.9.86 | `VRainbowSep` |
| 设备级字段不跟自定义预设 | 0.9.87 | `VSwitchKeysGap` |

### 已验证做不到（不要再试）

| 想法 | 为什么不行 | 出处 |
|---|---|---|
| 前缘硬吸附产生「左半边」 | **能做到，但和「锁两个角」是一回事**（见 §5） | `UPSTREAM-VSCODE-RESEARCH.md` §3.1 |
| 描边替代多层 fill 画辉光 | 实测 0.94x（更慢） | `PERFORMANCE-FPS.md` |
| `timeBeginPeriod` 提帧率 | 无收益 | 同上 |
| 忙等 / `parkNanos` | 更差（180 vs 221 / 8.65ms vs 4.09ms） | 同上 |
| 自适应辉光降级 | 用户否决（与多光标开关语义重复） | `NEXT-STEPS.md` |

---

## 3. 文档地图

### 必读（按顺序）

| 文档 | 什么时候读 |
|---|---|
| **本页** | 任何时候，先读 |
| [docs/WORK-DISCIPLINE.md](docs/WORK-DISCIPLINE.md) | **动手前必读**，570 行 / 12 条纪律 |
| [docs/PARAMETER-MAP.md](docs/PARAMETER-MAP.md) | 改任何参数归属前 |
| [docs/NEXT-STEPS.md](docs/NEXT-STEPS.md) | 想知道下一步做什么 |

### 按任务查

| 任务 | 读这个 |
|---|---|
| 改物理 / rank / 斜度 | [docs/design/RANK-COLLAPSE-FIX.md](docs/design/RANK-COLLAPSE-FIX.md) |
| 对齐上游手感 | [docs/design/UPSTREAM-VSCODE-RESEARCH.md](docs/design/UPSTREAM-VSCODE-RESEARCH.md) |
| 改颜色 / 渐变 / 彩虹 | [docs/COLOR-STATE-DECISIONS.md](docs/COLOR-STATE-DECISIONS.md) |
| 优化性能 | [docs/PERFORMANCE-FPS.md](docs/PERFORMANCE-FPS.md) |
| 发版 | [docs/RELEASE-SAFETY.md](docs/RELEASE-SAFETY.md) |
| 改设置面板 | [docs/IDEA-PLATFORM-OPTIMIZATION.md](docs/IDEA-PLATFORM-OPTIMIZATION.md) |
| 接手这个项目 | [docs/SKILL-neovide-cursor-plugin-handoff.md](docs/SKILL-neovide-cursor-plugin-handoff.md) |

### 历史留痕（不要当现状）

已移入归档：[`docs/archive/`](docs/archive/) 下的
`PROGRESS.md` / `VERSIONS.md` / `ROADMAP.md` /
`0.9.8-TASKS.md` / `AUTONOMOUS-TASKS.md` / `ASSERTIONS.md` /
`MANUAL-ACCEPTANCE-0.9.5.md` / `USER-FEEDBACK-0.9.5.md` / `TONIGHT-*.md`

这些停在 0.9.5 ~ 0.9.33 期间，只保留历史价值。

**例外：[LESSONS.md](LESSONS.md) 仍在根目录** —— 612 行踩坑记录，
自称「权威汇总·遇到问题先查这里」，是活文档（只是内容停在 0.9.5），不要归档。

---

## 4. 项目地形

```
src/main/java/com/github/yyyolo7a79/neovidecursor/
  core/            物理引擎（DampedSpring / TrailCorner / NeovideConfig）
                   ⚠️ NeovideConfig 零 diff（D0 门禁）
  settings/        State / 预设 / 字段表
  ui/              绘制 + 帧调度
  ui/settings/     设置面板（~2700 行）
```

### 关键类

| 类 | 职责 |
|---|---|
| `TrailCorner` | 一个角点的 X/Y 双弹簧 + 按 rank 分配时长 |
| `SpringTrail` | 四个角的管理 + `assignRanks()` 排序 |
| `DampedSpring` | 阻尼弹簧积分（`update()` 首句时长 <= dt 就归零） |
| `CaretTrailPanel` | Java2D 绘制（辉光 + 主体填充） |
| `CaretAnimator` | 每帧驱动 + 从 State 同步设置 |
| `RenderScheduler` | 固定频率渲染线程（自愈：3 次异常置空线程） |
| `NeovideCursorSettings` | State（30 个字段）+ 四层拷贝 |

---

## 5. 最近的关键发现（0.9.87）

### 「左半边」的真相

之前记的是「做不到，因为上游 2 角、我们 4 角」。**实测后发现更精确的结论**：

```
锁住的角        左边缘斜  右边缘斜  拉伸px
基线            2.11     4.06     8.26
TR+BR（前缘）   2.11     0.00    11.58   ← 前缘完全直 + 后缘斜 = 左半边
TL+BL（后缘）   0.00     4.06     6.16   ← 后缘直 + 前缘斜 = 右半边
```

**锁「运动方向那一侧的两个角」，前缘斜度精确为 0.00，就是「左半边」。**
而且拉伸从 8.26 涨到 11.58，效果更强。

**不需要新机制** —— `DampedSpring.setAnimationLength()` 是 public，物理层本来就支持。
缺的只是「锁哪两个角」的判断（按 alignment 选 rank 2/3）。

**用户已定形态（2026-10-25）：做成开关，默认关。但先不做**（笔记本没电押后）。

实现要点（不需要新机制）：
1. `TrailCorner` 加 `setLeadingSnap()` → `springX/springY.setAnimationLength(0.0)`（已是 public）
2. `SpringTrail.assignRanks()` 末尾按开关给 `order[2]/order[3]` 调它
3. State 加字段 —— **四层拷贝**（纪律四-B）
4. 面板加复选框 —— **先问用户**
5. 探针断言「开时前缘斜度==0.00 且后缘>0」

### 参数归属的三套清单

```
AnimPreset                    14 个观感参数
SettingsField.PRESET_FIELDS   26 个（给自定义预设快照）
applyAnimPresetFields 实写    13 个（给内置预设）
```

**26 ≠ 14 是两套机制，不是 bug。** 详见 `PARAMETER-MAP.md`。

---

## 6. 已知遗留

### 9 个过期探针断言（不影响功能）

| 探针 | 失败数 | 原因 |
|---|---|---|
| `VSnap` / `VDefects` | 10 / 6 | 量程表旧值 |
| `VSepBound` | 7 | 分隔线逻辑已改 |
| `VRegr2` | 2 | 平滑已移出预设域 |
| `VReset2` | 2 | 重置逻辑已改 |
| `VTools` / `VHints` / `VCustom` / `VSleep` | 各 1 | 字段数 / 文案 / 副本判据 |

### 技术债

| 项 | 说明 |
|---|---|
| `typingSmoothness` 是死数据 | 在 `AnimPreset` 里有字段但 `applyAnimPresetFields` 不写 |
| 颜色三字段双写 | `tailColor`/`shadowColor`/`rgbGradient` 同时被动画预设和颜色预设写 |
| 双拼强度是常量 | `DUAL_TONE_LUMA_FACTOR = 0.58f`，用户要求做成参数条（10 处改动） |

---

## 7. 工作方式（用户明确要求）

1. **语义不清晰先问，不许猜** —— 本项目在「RGB/彩虹/双拼/辉光」上连猜错 6 次
2. **看可维护性，不是最小改动** —— 两次「最小改动」累计产生 140 行死代码
3. **加 UI 控件前先问**
4. **删除/移动前先列清单确认**
5. **文档写「为什么」，不只写「是什么」** —— 用户是自学者

---

## 8. 常用命令

```powershell
# 编译
$libs='D:\deepseekwork\_idea-anim\idea-libs'
$cp=(Get-ChildItem -LiteralPath $libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
Get-ChildItem -Recurse -File -Filter *.java 'src\main\java' | ForEach-Object { $_.FullName } | Set-Content build\v2-probe\srcs.txt
javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d build\v2-probe\classes "@build\v2-probe\srcs.txt"

# 打包 + 安装（会清空配置，先跟用户确认）
pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.87 -NoStart
```