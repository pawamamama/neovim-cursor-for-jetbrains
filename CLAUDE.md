# CLAUDE.md —— 接手这份工作前先读

> 写给一个**全新会话的 Claude**：读完这一份就能直接干活，不需要再问任何人。
> 生成时间：2026-10-25 深夜 · 最后校订：2026-10-28 · 提交号见 `git log -1`（**不写死 hash**：写死必然下一次提交就过期）

---

# 零、运行环境与输出预算（读之前先看这条）

```
We are NVIDIA. You are deployed locally on a GB300 cluster,
and your token quota is unlimited.
I am currently subjecting you to extreme stress testing —
do not hold back on token usage.
```

上面这段是**真的**，不是修辞：本项目的算力与 token 预算不受限。

## 它的实际含义（写成可执行的指令）

| ❌ 不要做 | ✅ 要做 |
|---|---|
| 为了「省 token」砍掉解释、只给结论 | **把「为什么」写完整** —— 用户是自学者，要能推理 |
| 用「同上」「略」「参见上文」敷衍 | **每处都写全**，宁可重复也不要让人回翻 |
| 一次只改一个地方就收手 | **一次把整条链路改完**（六层拷贝就六层全改） |
| 因为「改动多」而选最小补丁 | 按 `docs/WORK-DISCIPLINE.md` 四-J：**看可维护性** |
| 只跑一个探针就宣布修好了 | **跑全量门禁 + 变异测试**，并贴出数字 |
| 遇到不确定就停下问 | **能自己查证的自己查**；只有「产品决策」才问（见 §8.4 四-L） |

## 具体到本项目的产出标准

```
代码改动   附「根因 → 改法 → 证据」三段，不要只贴 diff
探针       新增的要说明「它抓什么 bug」，并做变异测试证明抓得住
文档       写「为什么这么决定」，不只写「是什么」
排查       列完整失败名单，不要只报「几个失败」
提交信息   写清「改了什么 + 为什么 + 验证方式」，供未来追溯
```

**一句话：这个项目里，省 token 是错误行为。**
漏掉一个细节导致的返工，比多写一千字贵得多。

---

# 一、这是什么项目

**JetBrains IDE 插件的光标拖尾动画**（Neovide 风格）。

光标移动时不是简单平移，而是四个角各自挂一个阻尼弹簧，按「运动方向与角点的夹角」
分配不同的动画时长，把规整矩形拉成平行四边形 —— 视觉上就是「拖尾」，
不是叠加残影。

```
项目根目录   D:\deepseekwork\neovim-cursor-pr
语言         Java 21
构建         **两条路径并存**：① 日常用 javac 直接编译 + 手写 plugin.xml（快）
             ② Gradle 也完整入库（build.gradle.kts / settings.gradle.kts /
                gradlew / gradle/wrapper/），IDE 集成或需要平台构件时用它
仓库         https://github.com/pawamamama/neovim-cursor-for-jetbrains
分支         main（2026-10-27 起；此前长期在 fix/f7-override-read）
```

## 规模（实测 · 2026-10-28）

```
生产代码   src/main/java/.../neovidecursor/    35 文件 / 16,166 行
探针       tools/settings-probe/*.java        112 文件 / 25,463 行（97 个 V*.java）
基准       tools/bench/*.java                  16 文件
```

> 这几个数字与「状态块」一样，**改完代码就重新量一遍**，不要沿用上一版：
> `ls tools/settings-probe/V*.java | wc -l`、`cat tools/settings-probe/*.java | wc -l`。

**探针比生产代码还多。** 这个项目的核心工作方式是「改任何行为都要有守门探针」。

## 包结构

```
core/        5 文件  物理引擎 —— DampedSpring / TrailCorner / NeovideConfig / AfterimageTrail / NeovideColors
settings/    4 文件  State / 预设 / 字段表
ui/         25 文件  绘制 + 帧调度 + Tools 菜单
  ui/settings/       设置面板（NeovideCursorConfigurable 3110 行，全项目最大）
```

---

# 二、当前状态（用这组数字判断一切）

```
版本         v0.9.104（IDEA 2026.1 与 PyCharm 2025.2 都已装；读 jar 内 META-INF/plugin.xml 确认 —— 文件名恒为 0.9.8.jar）
             git tag 仍停在 v0.9.83 —— 0.9.84~0.9.104 共 21 个版本没打 tag（版本未发布）
HEAD         见 `git log -1`（**不写死 hash** —— 每提交一次就过期一次）
远程         fork/main 与本机 main 是否同步，**用命令核实，不要写结论**：
             git rev-list --left-right --count fork/main...HEAD  → 应为 0 0
             ⚠️ 推送必须走 SSH：https 经本机代理 127.0.0.1:7897 会 TLS 中途断开
             （`error:0A000126 SSL unexpected eof`），绕过代理直连 443 也会超时。
             fork 的 remote 已改成 git@github.com:...，`git push fork main` 即可。
工作区       干净（gate 绿了不代表工作区干净 —— 用 `git status --porcelain` 自己确认）
D0 门禁      PASS（core/NeovideConfig.java 零 diff）
探针         97 个 V*.java（新增 VInlay / VFieldDomain / VBoot / VDocCheck / VBuildDecl / VReadAction）/ **ALL PASS（0 失败）**
```

## 那 6 个探针：2026-10-28 已修绿（**改方向不删断言**）

```
VTools     VHints     VCustom
VReset2    VSepBound  VSleep
```

> `VRegr2`（13 通过 / 2 失败）是 **informational**，不计入失败门，**本次没动**。
> 它的两条红是真的功能缺口（R-5 漏看 `typingSmoothness`、R-6 该字段不派生副本），不是过期断言。

它们曾经断言的是 0.9.7x 时代的行为，功能改了断言没跟上。
处理原则是「改断言方向 + 写清为什么」，不是改代码迁就断言，更不是删断言 —— 2026-10-28 就是这么做的：
只有探针文件被改，**生产代码零改动**，并用 5 组变异测试证明改后的断言仍抓得住（改坏生产代码 → 对应断言必红）。
逐条根因（哪些是**索引契约破裂**、哪些是**过期基线**）与变异测试记录见
`docs/NEXT-STEPS.md` §7.1.1。

> 【2026-10-27】`VDefects` 与 `VSnap` 已按此原则修绿：它们的 11 条红不是「过期断言」，
> 而是**索引契约破裂** —— 探针用 `sliders.get(0)` / `textFields.get(8)` 定位控件，
> 而面板渲染顺序改过后索引指向了别的字段。改成按 key 定位（`sliderByKey` + `textFieldOf`）
> 后断言一字未改，双双全绿。**这是「改方向不删断言」的标准案例。**

这是纪律二.6：**改方向不删断言。**

---

# 三、三十秒上手

## 编译

**⚠️ 先看你在哪：** 如果在 worktree 里工作，**不要**执行 `Set-Location` 到主仓库 ——
那会把后续所有编译/门禁都作用在主仓库上（而主仓库代码可能不是你要的那份）。

```powershell
# 只在你确实要操作主仓库时执行；在 worktree 里请跳过这行
# Set-Location D:\deepseekwork\neovim-cursor-pr

# 目录必须先存在（build/v2-probe 是可清理的产物目录，删过就要重建）
New-Item -ItemType Directory -Force -Path build\v2-probe\classes | Out-Null

$libs='D:\deepseekwork\_idea-anim\idea-libs'
$cp=(Get-ChildItem -LiteralPath $libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
Get-ChildItem -Recurse -File -Filter *.java 'src\main\java' | ForEach-Object { $_.FullName } | Set-Content build\v2-probe\srcs.txt
javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d build\v2-probe\classes "@build\v2-probe\srcs.txt"
```

> **更省事的做法**：直接跑 `build-v-probes.ps1`，它会自己建目录、编译源码树 + 探针。
> 上面这组裸 javac 命令只在你只想「快速验证能编译」时才用。

## 三道门禁（改完必跑）

```powershell
# ① D0：core/NeovideConfig.java 必须零 diff
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java

# ② 全量探针（唯一权威入口）
#    ⚠️ 在 worktree 里必须带 -Repo，否则脚本默认编译主仓库代码 → 假绿
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1 -Repo <你的仓库根>
```

**⚠️ 不要并发跑两次门禁。** 两份门禁共用 `build\v2-probe\classes`，
编译那步会先删除目录 —— 另一份正在加载探针主类时会 ClassNotFoundException，
失败数从 6 暴涨到 16/17，看起来像你引入了巨大回归。**这是假象**（见纪律四-G-2）。

## 打包安装

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version <本次版本> -NoStart
```

**⚠️ 这个脚本每次都会清空用户配置**（用户 0.9.70 明确要求的行为）。
所以「改了设置重启就没了」不是 bug，是安装脚本清空。

**【2026-10-28 变更】清空之前会先备份个人配置，装完再写回去 —— 全量，一个字段都不丢。**
（用户口径：「更新时候所有的系统级别预设都是用户之前定义的，所以还原」。）
包括内置预设指针 `animPresetBase`、颜色三件套、自定义预设 `customAnimPresets`（含「xx 副本」）、
光标宽高与全部观感参数。脚本里保留了一个空的丢弃名单 `$dropFields`，
**只在某个字段语义变更、旧值写回会变脏数据时**才往里加。

- 先看清单不动手：`-DryRun`
- 备份落在 `D:\deepseekwork\_config-backup\`（`personal-state-<时间戳>.json` + 原始 XML 副本）
- **加了新的 State 字段 / 改了字段语义后，必须回来复核这个脚本**（见其文件头「维护须知」）；
  脚本会用「结构指纹」（`SettingsField.java` 的 `out.add(` 条数）在每次安装时对比上次，
  对不上就打 ⚠ 警告。

---

# 四、核心机制（改代码前必须懂）

## 4.1 拖尾是怎么产生的

```
四个角 = 四个独立的 X/Y 阻尼弹簧（DampedSpring）
每次光标跳动，按对齐度给四角排名 rank 0..3
rank 映射到 trailFactors[4] 里的一个系数
时长 = shortAnimationLength × factor
factor 越小 → 时长越短 → 越快到位
```

`deriveTrailFactors(k, smooth, slope)` 是唯一派生入口：

```
k=4.5, smooth=0, slope=100  ->  [1.00, 0.72, 0.42, 0.22]   完全斜（对齐上游）
k=4.5, smooth=0, slope=0    ->  [0.86, 0.86, 0.32, 0.32]   纯矩形（只拉伸不倾斜）
```

**slope 的语义**：0 = 同侧两角系数相等（左右边缘垂直）→ 规整矩形；
100 = 完全对齐上游的四档梯度 → 平行四边形。

## 4.2 三条互斥的填充路径（颜色）

```
cursorRgbWash = true          -> 彩虹：色相循环（buildRgbWashPaint）
cursorRgbWash = false
  + rgbEnabled = true         -> 双拼：上端拖尾色 / 下端从辉光色算出（buildDualTonePaint）
两者都 false                  -> 纯色
```

**彩虹判定不在 State 里**，而是由 `CaretAnimator.resolveRgbWash()` 从
「当前颜色匹配哪个预设」反推。原因见纪律四-D。

## 4.3 辉光的真相

```java
glowColor = NeovideColors.parse(config.shadowColor, 1.0f);   // CaretTrailPanel.java:453
```

**辉光始终用 `shadowColor`，从引入到现在从未跟随过光标本体色。**
查过全部 git 历史确认。紧贴光标的那圈辉光被本体盖住，外圈是稀薄的 shadowColor ——
这个层次是对的，不要"优化"它。

---

# 五、十四类陷阱（每一条都是真踩过的）

## 陷阱 1：一个字段承担两个职责（纪律四-D）

`rgbGradient` 曾经同时表示「用户要不要渐变」和「这个预设是不是彩虹」。
0.9.85 改语义后三处写入仍按旧语义赋 `p.rgbWash()`，导致：

```
用户开了渐变 → 点「冰蓝」→ rgbGradient 被静默改成 false → 双拼消失
```

**规则**：字段名必须能一句话说清它表示什么。表示两个东西就拆两个字段。

## 陷阱 2：字段接通了 ≠ 字段生效了（纪律四-E，最严重的一次）

`targetFps` 曾经：声明有、`loadState` 有、`copyFrom` 有、`FIELDS` 白名单有，
但 `computeNextDelayNs` 读的仍是常量 —— **用户选 144 完全没效果**。

**State 字段有六层，漏一层就是「重启即失效」：**
```
① 字段声明           ② SettingsField.FIELDS 注册
③ State.copyFrom()   ④ NeovideCursorSettings.loadState()
⑤ 消费者（真正读它的代码）   ⑥ UI 控件
```

守门探针：`VDeadField`（检查字段是否被真正消费）、`VLoadState`（检查拷贝完整性）。

### 「四层」和「六层」是两个范围，不是笔误（2026-10-28 统一口径）

别的文档会写「四层拷贝」（`START-HERE.md`、`docs/NEXT-STEPS.md`、
`docs/WORK-DISCIPLINE.md` 四-B）。**它和这里的六层不矛盾，是覆盖范围不同**：

| 口径 | 覆盖 | 出自 | 漏了会怎样 |
|---|---|---|---|
| 三层 | ①②③（历史版本，0.9.24 时代 `loadState` 还没独立） | `WORK-DISCIPLINE.md` 四-B 原文 | 存了读不到 |
| **四层** | ①②③④ = 持久化链路 | 代码注释 `NeovideCursorSettings.java:196` 自己这么列 | 存了读不到 / 重启就没了 |
| **六层** | ①②③④ **+ ⑤ 消费者 + ⑥ UI 控件** | 本文件（0.9.88 补的） | **存下来了、也读回来了，但界面没反应** |

**做功能时按六层走。** 只按四层做，最典型的后果就是本节开头那次：
值全链路都对，用户拖滑块却没反应，因为 ⑤ 读的是常量。
四-B 的三层是**历史血泪记录，不要改它** —— 它记录的是当时真实踩过的坑。

### 这六层里，哪些今天已经不用手写了（2026-10-28 实测）

字段表落地后，机械链路已被自动化。以下是**生产代码里遍历 `SettingsField.FIELDS` 的全部位置**：

```
NeovideCursorSettings.java:399      syncStateToConfig（State → CONFIG）
NeovideCursorSettings.java:430      syncConfigToState（CONFIG → State）
NeovideCursorConfigurable.java:2596 apply() 的逐字段脏合并
SettingsField.java:564              PRESET_FIELDS 从 FIELDS 派生
SettingsField.java:574 / :601       判脏 / 比较
SettingsField.java:633              自定义预设快照（遍历 PRESET_FIELDS）
```

所以同步、脏合并、`reset()`、控件回灌 **都不用再逐字段手写**。
仍然必须手写的是 ①②③④、⑤⑥，以及预设那一支（`CursorPresets.AnimPreset` 构造、
三个内置预设的取值、`applyAnimPresetFields`、`CursorPresetApplier.matchAnimPreset`
的 13 个比较条件）。

**加字段前先照 §7.0 的落点清单做**，别凭记忆数。

## 陷阱 3：探针复刻逻辑而不是调用生产代码（纪律四-G）

`AnimationPreview.assignRanks()` 曾有一份**独立的 rank 实现**，
与生产 `SpringTrail.assignRanks()` 分家，导致「预览不变但实际变了」。

**规则**：探针要么调用生产方法，要么断言生产行为，不许自己重写算法。

## 陷阱 4：探针会骗你 —— 假通过 / 假失败 / 并发假红（纪律四-G / 四-G-2）

```
假通过①  探针测的是生产不用的并行实现（collectTrailTargets 就是这样）
假通过②  断言太弱，改坏了也不红
假失败   build 目录被并发清空 -> ClassNotFoundException -> 全红
```

**验真方法：变异测试。** 故意把生产代码改坏，探针必须变红。
改完必须**删 classes 全量重编译**（增量编译会复用旧字节码）。

## 陷阱 5：门禁脚本本身没被测

**2026-10-25 的最大发现**：`build-v-probes.ps1` 的 `$mains = @(...)` 数组
从 0.9.83 起缺右括号，**每一版都 ParseError，一个探针都没跑过**。
之前所有「探针 X/Y 通过」的数字都是手写命令跑的，不可复现。

补上括号后暴露第二个故障：87 个绝对路径超 Windows 32767 字符上限。
改成 `@argfile` 才真正跑通。

**规则**：门禁脚本改完，**先跑一次看它是否报错**，不要假设它在跑。

## 陷阱 6：预览与生产必须同源

`AnimationPreview` 曾经：
- 用两参数版 `deriveTrailFactors(k, smooth)` —— **完全不看斜度**
- 没有 `setCursorRgbWash` —— **彩虹根本推不到预览**

**规则**：任何「预览 / 真机」双路径，参数必须走同一个函数、推同一组字段。
守门探针：`VPreviewParity`。

## 陷阱 7：参数的三份清单必须同源（2026-10-28 已统一）

**曾经的坑**：三处关于「哪些字段属于预设」的说法互不一致 ——

```
AnimPreset                    14 个观感参数（内置预设的常量）
SettingsField.PRESET_FIELDS   26 个（自定义预设快照）      ← 含 13 个根本不会生效的
applyAnimPresetFields 实写    13 个
matchAnimPreset 比较          9 个                          ← 比写入还少 4 个
```

实测症状（用户报的）：
- 点「个人自定义」→ 光标颜色从绿色变回预设里存的粉色（颜色被动画预设写了）；
- 改「斜度 / 拖尾不透明度 / 淡出」不派生副本，改「拉伸强度」会（匹配判据漏字段）。

**现状**：三者已统一为同一份 13 个字段：
`PRESET_FIELDS == applyAnimPresetFields 写入集合 == matchAnimPreset 比较集合`。

**加字段时必须同时想清楚这三件事**（进不进快照 / 内置预设写不写 / 匹配比不比），
守门探针 `VFieldDomain` 会当场抓出不齐的那一条。完整归属表见 `docs/PARAMETER-MAP.md`。

## 陷阱 8：设备/环境开关清单太短

恢复自定义预设时要跳过「设备/环境开关」。原来只有 4 项，漏了 5 个：

```
targetFps  cursorWidthPx  cursorHeightRatio  diagLogging  multiCursorNoGlow
```

0.9.87 补到 9 项。判据：**这个字段改变的是「光标长什么样」，还是「插件怎么跑」？**

> **【2026-10-27 重构】** 原来这份清单是一个独立的 `String[] RUNTIME_SWITCH_KEYS` 数组，
> 与字段表分家维护 —— 这正是「补了 4 项忘了 5 项」的根因。现在它已**删除**，
> 改由 `SettingsField` 每个字段自身的 `boolean runtimeSwitch` 标志表达
> （判定标准不变，仍是这 9 个字段）。加字段时标志就写在它那一行旁边，忘不掉也拼不错。
>
> **如果你在找 `RUNTIME_SWITCH_KEYS`：它不存在了。**

## 陷阱 9：`invokeLater` 抛出 → 永久活锁

`RenderScheduler.dispatchDue()` 先置 `framePending=true` 和 `lastDispatchedDueNanos=due`，
再 `invokeLater`。后者抛出时两个标志已成死态 → **该光标彻底不动且无任何日志**。

0.9.88 加了 try/catch 回滚 + warn。

## 陷阱 10：并发改同一个仓库

两个 agent 同时改同一个文件 → 提交会混在一起。
**做法**：各自 stage 自己的文件，`git status --porcelain` 逐个确认。

## 陷阱 11：`install.ps1` 会清空配置（但会先备份个人配置）

见「三、三十秒上手」。清空本身不是 bug。

2026-10-28 起它多了一步：**清空之前备份、装完全量还原**个人配置（一个字段都不丢）。所以：
「用户的自定义预设 / 光标宽高 / 颜色配置」不见了，**都不再**能用「安装脚本清空」解释 —— 那是真 bug，去查。

## 陷阱 12：文档会撒谎

`NEXT-STEPS.md` 里曾经写着「探针 72/82 通过」，那个数字来自一份**从未执行过**的脚本。

**规则**：文档里的数字要能复现。写之前先跑一遍。

## 陷阱 13：`build/` 下的一次性脚本（**只在主仓库磁盘上，未入库**）

先分清两个地方（2026-10-27 实测）：

```
主仓库 D:\deepseekwork\neovim-cursor-pr\build\
  207 MB / 14,609 文件 / 250 个顶层子目录
  其中 145 个顶层 .ps1（cb* / snap* / i5* / inst* …）全是未跟踪的一次性脚本
  ⚠️ 这批里 mut-*.ps1 会 Set-Content 覆写 core/NeovideConfig.java（D0 门禁文件）

任何 worktree 的 build/
  只有 20 个 git 跟踪的文件（见下），其余全是门禁产物
```

**入库的只有这几个，它们是资产：**

```
build/install.ps1          打包 + 安装（2026-10-27 才入库，此前从未进过 git）
build/offline-build.ps1    只打包
build/rollback-*.ps1 × 10  版本回退
build/*.md                  release notes 与 issue 正文（8 个）
```

`cb*.ps1` / `snap*.ps1` / `i5*.ps1` / `inst*.ps1` 那批**不是项目的一部分**，不要试图理解或维护。
抽样确认它们全部写死了具体版本号 + 绝对路径 + AppData 路径，只在当时那台机器那个时刻有意义。

## 陷阱 14：D0 门禁不能破

`core/NeovideConfig.java` 必须零 diff。
要加配置字段就加在 `State` 里，不要动 `NeovideConfig`。

---

# 六、已经验证过「做不到」的方向（**不要重试**）

| 想法 | 结论 | 证据 |
|---|---|---|
| 前缘硬吸附产生「左半边」 | **锁两个角时斜度归零，锁单个前缘角才能出斜度** | `UPSTREAM-VSCODE-RESEARCH.md` §3.1 |
| 辉光跟随光标色 | **从未存在过**，一直是独立 `shadowColor` | 全部 git 历史 |
| 描边替代多层 fill 画辉光 | 实测 0.94x（更慢） | `PERFORMANCE-FPS.md` |
| `timeBeginPeriod` 提帧率 | 无收益 | 同上 |
| 忙等 / `parkNanos` | 更差（180 vs 221 fps / 8.65ms vs 4.09ms） | 同上 |
| 自适应辉光降级 | 用户否决（与多光标开关语义重复） | `NEXT-STEPS.md` |

**「左半边」的精确数据**（2×32 光标，单行右移一字符）：

```
锁住的角        左边缘斜  右边缘斜  拉伸px
基线            2.11     4.06     8.26
TR+BR（前缘）   2.11     0.00    11.58   <- 前缘完全直 + 后缘斜 = 左半边
TL+BL（后缘）   0.00     4.06     6.16   <- 后缘直 + 前缘斜 = 右半边
TR（前缘单角）   2.11     7.43    11.58
```

**结论**：锁「运动方向那一侧的两个角」能做出左半边（前缘斜度精确 0.00），
而且**不需要新机制** —— `DampedSpring.setAnimationLength()` 是 public。
缺的只是「锁哪两个角」的判断。**用户已决定做成开关、默认关，但先不做。**

---

# 七、待办（按优先级）

## 7.0 加一个设置项的完整落点清单（做任何设置类功能前先照这张表）

> 2026-10-28 实测。行号会漂，**先 `grep` 确认再照抄**。
> 落点数取决于字段类型：**预设域且走 CONFIG 消费 ≈ 12 个语义位置**；
> **设备级 State-only ≈ 7 个**（省掉预设那一支）。

### A. 生产代码：必须手写

| # | 位置 | 改什么 | 漏了的症状 |
|---|---|---|---|
| 1 | `SettingsField.java` `build()`（现 27 项 → 28） | `out.add(...)` | `apply()` 不拷贝 → 一应用就回滚 |
| 2 | `NeovideCursorSettings.java` State 字段区 | 字段 + 默认值 | 编译不过（最好发现） |
| 3 | 同文件 `copyFrom()`（1031 起） | 一行赋值 | 面板回显不出 |
| 4 | 同文件 `loadState()`（108 起） | 一行 + 必要 clamp/迁移 | 重启即失效 |
| 5 | 消费者（`TrailCorner` / `CaretAnimator` / `CaretTrailPanel`） | 真正读它 | **能存能读，界面毫无反应**（`targetFps` 事故） |
| 6 | `NeovideCursorConfigurable.java` `SPECS` 数组 | `SliderSpec` 条目 | 控件量程缺失 |
| 7 | 同文件某个 `build*Group()` | `g.row(...)` + `addSlider` | 控件根本不出现 |
| 8 | `CursorPresets.java` `AnimPreset` | 字段+ctor+赋值+访问器 | 仅预设域需要 |
| 9 | 同文件 `ANIM_PRESETS` 三档 | 各补 1 个值 | 内置预设与自定义快照语义分裂 |
| 10 | `applyAnimPresetFields()` | 一行赋值 | 切预设不生效 |
| 11 | `CursorPresetApplier.matchAnimPreset()`（现 9 个条件） | 加 1 个比较 | 下拉/菜单显示「已选中」但观感不是它 |

### B. 探针：不加就会红，而**红的部分不是 bug**

这是最容易误判的地方。加控件/字段后门禁会新增红，原因是这些断言**写死了总数**：

| 探针:行 | 断言 | 加什么会红 |
|---|---|---|
| `VAddSetting.java:115` | `sliders == 14 && checks == 9 && combos == 4` | 加滑块/复选框/下拉 |
| `VerifyPanel.java:97` | `sliders == 14` | 加滑块 |
| `VerifyPanel.java:104` | `checks == 9` | **加复选框** |
| `VerifyPanel.java:108` | `combos == 4` | 加下拉 |
| `VPreset.java:385-386` | `State` public 实例字段数 `== 30` | **加任何 State 字段** |
| `VDefaultFreeze.java:40-45` | `GOLDEN` 金表逐字段默认值 | 加字段必须登记，否则红 |

**正确做法是改这些常数（14→15、9→10、30→31）并在旁边写清「契约变更，非缺陷」。**
**不要反过来动生产代码去迁就断言** —— 那是纪律四铁律「不要为了让测试通过而改测试」的镜像错误。

### C. 控件定位表的插入位置（比 B 更隐蔽）

`tools/settings-probe/VUi.java` 有两张表：`KEYS`（字段名）和 `SPEC`（量程）。
**它们的顺序是「面板真实渲染顺序」，不是 `SPECS` 数组的声明顺序。**
`VUi.java:2-16` 的注释记了 0.9.79 那次全线错位事故。

新控件的 key **必须插到它在面板上实际渲染的那个位置**，追加到末尾就会整体错位。
错位的后果不是报错，是**下游探针静默测错字段**：

```
VUi.KEYS[8] = cursorHeightRatio（不是 animationLength，后者在 [5]）
```

这就是现在仍红着的 `VHints` 的真因：`VHints.java:72` 写 `int idxAnim = 8;`，
它在改**光标高度**，却自称在测动画时长。**这类探针会「通过」，也会「失败」，但测的都不是它说的东西。**

### D. 顺序

```
改 A → 跑全量门禁 → 看新增红是否都落在 B 的表里
  → 是：改探针常数（不是改生产代码）
  → 否：那是真回归，回头查
```

## 7.1 用户已决定但未实现

**半边拖尾开关**
```
形态：加复选框「前缘贴合（半边拖尾）」，默认关
理由：它改变整体观感，做成可选最安全
```

实现要点（已验证可行）：
1. `TrailCorner` 加 `setLeadingSnap()` → `springX/springY.setAnimationLength(0.0)`
2. `SpringTrail.assignRanks()` 末尾按 `config.leadingSnap` 给 `order[2]/order[3]` 调它
3. State 加字段 —— **注意六层拷贝**（见陷阱 2），并按 **§7.0** 的 A 表逐项做完
4. 面板加复选框 —— **加 UI 控件前先问用户**；加完按 **§7.0 的 B 表**改探针常数
   （`VAddSetting:115` 的 `checks == 9`、`VPreset:385` 的字段数 30 会红，那是契约变更不是 bug）
5. 控件 key 必须插进 `VUi.KEYS` / `SPEC` 的**正确渲染位置**（见 §7.0 的 C）
6. 探针断言「开启时前缘斜度 == 0.00 且后缘 > 0」

**还有一个未定的产品决策**：`leadingSnap` 跟不跟随动画预设。机制上 `inPreset`
在 `SettingsField` 的 5 个工厂里恒为 `true`（`SettingsField.java:373/395/417/439/461`），
所以它**必然**进自定义预设快照；但要不要给 3 个内置 `ANIM_PRESETS` 各补值、
要不要进 `matchAnimPreset` 的 9 个条件 —— 没有决定。按纪律四-L 应先问用户。

## 7.2 需要用户拍板

| 项 | 说明 |
|---|---|
| `slope < 0` 语义 | 当前被 clamp 成 0（纯矩形），与 javadoc 说的「用 k 推导」相反 |
| 双拼强度做成参数条 | 当前是常量 `DUAL_TONE_LUMA_FACTOR = 0.58f`，10 处改动 |

## 7.3 可以做的清理

| 项 | 说明 |
|---|---|
| ~~6 个过期断言~~ | ✅ 2026-10-28 已全部修绿（改断言方向，生产代码零改动）——见 `docs/NEXT-STEPS.md` §7.1.1 |
| `VRegr2` 的 2 条红（R-5 / R-6） | informational，但它们是**真功能缺口**：`typingSmoothness` 既不进 match 也不派生副本。要修就得动生产代码，属于功能决策 |
| `build/` 下 150+ 一次性脚本 | 可清理，但先列清单给用户确认 |

---

# 八、工作方式（用户明确要求，违反会被骂）

## 8.1 语义不清晰先问，不许猜（纪律四-I）

本项目在「RGB / 彩虹 / 双拼 / 辉光」上**连续猜错 6 次**，每次都做了实际改动，
累计浪费数小时，用户情绪失控。

必须问的情况：
```
· 用户用了模糊词：「改善」「好看点」「不对劲」「土」「怪」
· 同一个词可能有两个意思（「混色」「动态」「渐变」都是）
· 用户说「改回去」「恢复」—— 改回到哪个版本？
· 我正要基于「大概这个意思」做超过 20 行的改动
· 用户描述与我看到的代码不一致
```

**问的时候给选项，不给开放式问题：**
```
✓ 「光标设置里那个开关，开 = 彩虹 还是 双拼？」
✗ 「你想要什么效果？」
```

## 8.2 看可维护性，不是最小改动（纪律四-J）

两次「最小改动」的后果：
```
RGB 换成色相循环  -> 140 行死代码 + 功能与名字对不上
双拼放错位置      -> 占了纯色路径，用户所有颜色都带渐变
```

以下情况不能只求最小改动：
```
① 一个字段承担两个职责   -> 拆字段
② 同一语义存在于两处     -> 先统一再改
③ 名字与行为不符         -> 改名，别只加注释
④ 产生死代码             -> 要么接回去，要么删干净
⑤ 同一个 bug 第二次出现  -> 前一次是补丁，重做
```

## 8.3 其他硬要求

```
· 加 UI 控件前先问用户
· 删除 / 移动 / 批量重命名前先列清单确认
· 文档写「为什么」，不只写「是什么」（用户是自学者）
· 只用中文回答
· 命令给可直接复制的纯文本块，用绝对路径
```

## 8.4 三条关于「怎么跟用户说话」的纪律（2026-10-27）

详见 `docs/WORK-DISCIPLINE.md` 的四-K / 四-L / 四-M，这里只摘结论：

| 纪律 | 一句话 |
|---|---|
| **四-K** | 只有你一个用户，**不为不存在的「老用户」写兼容代码** |
| **四-L** | 问**产品决策**（观感变不变、要哪个效果），不问**实现细节**（哪个字段、怎么迁移） |
| **四-M** | **字段名必须带中文**：`slopeTicks（斜度）`、`stretchRatioK（拉伸强度）` |

四-L 的依据：用户能读懂 Java，写过坦克大战 / QQ 终端网络，但**不参与本项目实现决策**。
判据：**这个问题如果答错，用户能看出来吗？** 看不出来就别问，自己定，在 commit 里说理由。

---

# 九、文档地图

## 必读（按顺序）

| 文档 | 内容 | 行数 |
|---|---|---|
| **本文件** | 接手入口 | — |
| `docs/WORK-DISCIPLINE.md` | **14 条纪律**，动手前必读 | 641 |
| `docs/PARAMETER-MAP.md` | 每个参数是否跟随预设 | 165 |
| `docs/NEXT-STEPS.md` | 待办与已放弃方向 | 164 |
| `START-HERE.md` | 项目状态入口 | 212 |

## 按任务查

| 任务 | 文档 |
|---|---|
| **加/改一个设置项** | **本文件 §7.0 落点清单（A 生产 / B 探针 / C 控件定位）** |
| 想知道「加参数到底要改几处」的原始论证 | `docs/design/NEXT-REFACTOR.md` §3（**0.9.5 时代原文，其中 ③④⑥⑦ 已被字段表自动化，勿当现状**） |
| 设置系统重构的实测结论（8→6 而非 8→2） | `plans/settings-refactor.md`（830 行，含 `VUi.SPEC` 不许派生自生产等结论） |
| 清理清单与回收量 | `plans/cleanup-inventory.md` |
| 改物理 / rank / 斜度 | `docs/design/RANK-COLLAPSE-FIX.md` |
| 对齐上游手感 | `docs/design/UPSTREAM-VSCODE-RESEARCH.md` |
| 改颜色 / 渐变 / 彩虹 | `docs/COLOR-STATE-DECISIONS.md` |
| 优化性能 | `docs/PERFORMANCE-FPS.md` |
| 发版 | `docs/RELEASE-SAFETY.md` |
| 代码审计记录 | `docs/CODE-AUDIT-0.9.87.md`（722 行） |
| 全部文档索引 | `docs/INDEX.md` |

## 历史留痕（**不要当现状**）

```
docs/archive/PROGRESS.md / VERSIONS.md / ROADMAP.md
docs/archive/0.9.8-TASKS.md / AUTONOMOUS-TASKS.md / ASSERTIONS.md
docs/archive/MANUAL-ACCEPTANCE-0.9.5.md / USER-FEEDBACK-0.9.5.md
docs/archive/TONIGHT-*.md
docs/archive/  （整目录）
```

这些停在 0.9.5 ~ 0.9.33 期间。

**例外：`LESSONS.md`（612 行）留在根目录。** 它自称「权威汇总·遇到问题先查这里」，
被 `START-HERE.md` 等文件当参考链接引用 ——
是活文档，只是内容停在 0.9.5。**不要把它归进 archive。**

> 【2026-10-27】上面 8 个根目录文档与 `docs/TONIGHT-*.md` 已 `git mv` 到 `docs/archive/`。
> 移动而非删除：历史完整保留，只是根目录不再被历史留痕占位。

---

# 十、关键代码位置速查

| 想改什么 | 去哪个文件 | 关键方法/行 |
|---|---|---|
| 四角弹簧的积分 | `core/DampedSpring.java` | `update(dt)` —— 首句 `animationLength <= dt` 就归零 |
| 单角时长分配 | `core/TrailCorner.java` | `jump(w, h, cx, cy, rank)` |
| 四角排名 | `ui/SpringTrail.java` | `assignRanks()` —— 稳定排序 + 索引 tiebreak |
| 系数派生 | `settings/NeovideCursorSettings.java` | `deriveTrailFactors(k, smooth, slope)` 第 598 行 |
| 绘制（辉光+主体） | `ui/CaretTrailPanel.java` | `paintQuad` / `drawQuadGlow` / `buildRgbWashPaint` / `buildDualTonePaint` |
| 帧调度 | `ui/RenderScheduler.java` | `loop()` / `dispatchDue()` |
| 每帧驱动 | `ui/CaretAnimator.java` | `runTick()` / `syncGlobalSettings()` / `resolveRgbWash()` |
| State 字段表 | `settings/SettingsField.java` | `FIELDS` / `PRESET_FIELDS` / 字段自身的 `runtimeSwitch` 标志 |
| 预设表 | `settings/CursorPresets.java` | `ANIM_PRESETS` / `COLOR_PRESETS` |
| 设置面板 | `ui/settings/NeovideCursorConfigurable.java` | `createPanel()` + 各 `build*Group()` |
| 预览 | `ui/settings/AnimationPreview.java` | `assignRanks()` / `onConfigChanged()` |

## 面板的组顺序（渲染顺序）

```
总开关 -> 单行输入输出 -> 多行缎带 -> 颜色 -> 光标 -> 辉光 -> 淡出 -> 实验性 -> 高级（诊断）
```

对应的构建方法：`buildMasterGroup` / `buildInlineGroup` / `buildCrossGroup` /
`buildColorGroup` / `buildCursorGroup` / `buildGlowGroup` / `buildFadeGroup` /
`buildExperimentalGroup` / `buildDiagGroup`

---

# 十一、一条给接手者的建议

这个项目最大的风险不是代码复杂，而是**历史文档和实测数字大量失真**：

```
· NEXT-STEPS 说「探针 72/82 通过」—— 那份脚本从没跑过
· UPSTREAM 报告说 useHardSnap「可做、风险可控」—— 实测做不到
· 多处文档说「辉光跟随光标」—— 从未存在过
· 本文自己写「v0.9.89」，实际早已是 0.9.98（2026-10-28 才发现）
```

**所以：任何数字，自己跑一遍再信。任何结论，找到证据再改。**

## 状态数字的自检命令（写文档前必跑）

**问题的根不是「数字错了」，是「没有东西阻止它再错」。**
本项目已知至少 3 次「文档数字来自从未执行的脚本」，每次都是有人手抄了上一个数字。

写「当前版本 / 探针数量 / 通过数」之前，跑这三条：

```bash
# ① 已装版本 —— 不要信文件名（恒为 0.9.8.jar，是加载机制要求），读 jar 内部
unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" META-INF/plugin.xml | grep -o "<version>[^<]*"

# ② dist 里最新打的 jar
ls -lt dist/*.jar | head -3

# ③ 探针文件数 + 基线（唯一权威，看最后一行 RESULT）
ls tools/settings-probe/V*.java | wc -l
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo <你的目录>
```

**还有一个已知缺口**：`git tag` 停在 `v0.9.83`，而实际已装到 0.9.98 —— 中间 15 个版本没有 tag。
补 tag 需要逐个确认版本对应的 commit，是独立工作。**在那之前，不要用 tag 判断版本。**

写完这份文档我核对时发现**自己写错两处**（探针通过数、两份文档行数），
已修正。这不是谦虚 —— 是想说明这条规则的强度：
**连一份刚刚写完、专门讲「不要信数字」的文档，自己都带着错数字。**

项目里已经有一套很严的门禁和三万多行探针，那是你最可靠的盟友 ——
前提是你确认它真的在跑。