# 工作纪律（2026-10-24 用户要求，强制遵守）

> 用户原话：「我真服了纪律性好差 你优化一下你工作纪律」
>
> 以下每条都对应我真实犯过的错。**不是建议，是硬规则。**

---

## 一、改代码前

### 1. 先查证，再动手 —— 不许凭印象

| 我犯过的错 | 正确做法 |
|---|---|
| 以为 `-Dneovide.cursor.diag=true` 用户能接受 | 先问「这个门槛你行吗」 |
| 以为预设会覆盖所有手感参数 | 查 `AnimPreset` 字段表，发现根本没有 `stretchRatioK` |
| 以为删掉 `BASE_FONT_SIZE` 没影响 | 它还是 3 处 `new Font` 的兜底字号 |

**规则：说「我认为」之前，先 `grep` 一遍。**

### 2. 改核心物理前，先确认影响面

```
禁区：core/TrailCorner.java / core/DampedSpring.java / core/NeovideConfig.java
理由：PhysicsParity 是与上游逐位比对的黄金基准（72 行浮点 SHA256）
     动一个符号就变红
```

**我犯过：** 为了让「缎带拉伸强度」对跨行生效，改了 `TrailCorner` → parity 立刻红。
**正确做法：** 改成「把滑块移到它真正生效的组」，不动物理。

### 3. 一次只改一件事，改完立刻验证

**我犯过：** 一轮里同时改字号 + 布局 + 换行逻辑 + 删除节奏，出问题时不知道哪个导致的。

---

## 二、验证纪律

### 4. 探针必须复用生产口径，不许自己写第二套

**我犯过：** 探针自己写「字段→刻度」映射，结果 `shadowBlurFactor` 期望 0 实际 73 ——
因为面板的 `readTick` 是 `sharpnessOf()` 转换。探针自己骗自己。

**正确做法：** 探针调 `conf.sliderExactTickForProbe(i)`，用面板自己的 `SPECS.readTick`。

### 5. 说「修好了」必须有实测数字

```
❌ 「应该没问题了」
✅ 「100 次 remeasure 从每帧枚举降到 0ms（VSelfCheck C-3）」
✅ 「trailFactors 三个预设实测 [1.0,0.72,0.42,0.22] 完全相同 → 确认是真 bug」
```

### 6. 探针报红 ≠ 一定是产品 bug，但也绝不许「改成警告」绕过

**我犯过：** IME 探针 2 项红，我把脚本改成「警告 + exit 0」。
**后果：** 后来真出问题就分不清了。

**正确做法：** 逐个查清红的原因。那次查明是探针基线的快速路径抢跑，不是产品缺陷。

---

## 三、交付纪律

### 7. 脚本必须自己先跑通再给用户

| 我犯过的错 |
|---|
| 给了 `showcase.ps1` 的路径，但文件根本不存在 |
| `-Djava.awt.headless=true` 被 PowerShell 拆参数报错 |
| 窗口弹出来但被 IDEA 挡住，用户以为「没出来」 |

**规则：任何给用户的命令，我必须先执行一遍看到成功输出。**

### 8. 装 jar 必须先清空 lib

**真实事故：** `lib/` 堆了 12 个 jar，IDEA 加载的是 0.9.14，我却告诉用户「已装 0.9.17」。

```powershell
# 每次安装前必做
Get-ChildItem $lib -Filter "*.jar" | Move-Item -Destination $backup
```

### 9. 版本号必须从 jar 内部读，不看文件名

文件名恒为 `jetbrains-neovide-cursor-0.9.8.jar`（加载机制依赖）。
真实版本在 `META-INF/plugin.xml` 的 `<version>`。

---

## 四、沟通纪律

### 10. 用户说「不对」时，先复现，不许辩解

**我犯过：** 用户说「光标乱套」，我先解释「我的改动没影响物理」——
虽然事实正确，但没帮上忙。

**正确做法：** 立刻跑诊断，把实测数据摆出来。

### 11. 不确定就问，不许猜

**我犯过：** 「两行输入删除」猜了三种解读，浪费一轮。

### 12. 每次改完写清楚「改了什么 / 为什么 / 怎么验证」

commit message 必须含这三段。不许只写「fix bug」。

---

## 四-B、状态字段的三层拷贝（2026-10-24 血泪教训）

**问题**：诊断日志勾选后，一点应用就消失。查了两轮才修完。

**这个 bug 的教训值得单独成节，因为它暴露了一个系统性陷阱：**

### 加一个 State 字段，必须同时改 3 处

| # | 位置 | 漏了会怎样 |
|---|---|---|
| 1 | `State` 字段声明 | 编译不过（最容易发现） |
| 2 | `SettingsField.FIELDS` 表项 | **`apply()` 遍历表时不拷贝 → 一应用就回滚** |
| 3 | `State.copyFrom()` | **`pending = deepCopy(state)` 时丢值 → 复选框永远不勾** |

**第 2、3 层都是「静默失败」——不报错、不抛异常，只是值不见了。**

我第一次只修了第 2 层，用户说「不能」，才发现还有第 3 层。

### 正确的检查命令（加字段后必跑）

```powershell
# 检查 State 所有字段是否都在 copyFrom 里
cd D:\deepseekwork\neovim-cursor-pr
$f = "src\main\java\...\NeovideCursorSettings.java"
$src = Get-Content $f -Raw
$fields = (Select-String -Path $f -Pattern '^    public (boolean|int|float|String) ([a-zA-Z]+) =' |
    ForEach-Object { $_.Matches[0].Groups[2].Value })
foreach($n in $fields){
    if($src -notmatch ("$n = from\.$n")){ Write-Host ("❌ copyFrom 缺: " + $n) }
}
```

**没有输出 = 全部字段都在。**

### 同理：SettingsField.FIELDS 也要核对

```powershell
# State 有但 FIELDS 没有的字段 = apply 永远不拷贝
```

### 判断一个新字段该怎么接

| 字段类型 | 要改哪些地方 |
|---|---|
| 只进 State（如 `diagLogging`） | State + FIELDS（CONFIG 两参传 `null`）+ copyFrom |
| 同时进 CONFIG（大多数观感参数） | 上面 3 处 + CONFIG 字段 + FIELDS 的 config 映射 |
| 派生量（如 `trailFactors`） | **不进 FIELDS**，在 `syncStateToConfig()` 里单独派生 |

---

## 四-C、诊断日志：直接读盘，不要让用户复制

**问题**：日志混在 `idea.log` 里，每次排查都要用户复制粘贴。慢、易漏、还打乱对话。

**0.9.53 起改为双写：**

```
<config>/neovide-cursor-diag.log      ← 独立文件，我直接读
%APPDATA%\JetBrains\IntelliJIdea2026.1\neovide-cursor-diag.log
```

**特性：**

| 项 | 行为 |
|---|---|
| 每次启动 | **清空重写**（每次排查都是干净基线，不受历史污染） |
| 写入方式 | 每行立即 `flush()`（崩溃也能看到） |
| 同时 | 仍写 `idea.log`（保持 IDEA 内可搜索） |

**我读日志的命令：**

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\read-diag.ps1
```

### 日志前缀与用途（覆盖性）

| 前缀 | 内容 | 排查什么 |
|---|---|---|
| `[carets]` | 光标数 从几个 → 几个 + 原因 | 多光标乱套、合并 |
| `[config]` | 字段 旧值 → 新值 | 「改了没生效」 |
| `[preset]` | 预设名 + k/anim/short | 「切预设手感不变」 |
| `[error]` | 被 catch 吞掉的异常 | 静默失败 |
| `[frame]` | 帧号 + 光标数 + **fps** + 不变量 | 性能、I1/I4/I5 |
| `[caret]` | vp/lead/实际用哪个 | 选区跟随 |
| `[trail]` | 单光标位置 + 偏移 | 拖尾冻结/失控 |
| `[pick]` | 块选区方向 + 选中数组 | 块选区 |
| `[block]` | 块选区数组原值 | 块选区 |

**采样**：每 15 帧一次（`-Dneovide.cursor.diag.sample=N` 可调），避免刷爆。

---

## 四-D、一个字段承担两个职责 = 定时炸弹（2026-10-24）

**同一个 bug 我修了两次，中间隔了 35 个版本。**

### 时间线

| 版本 | 我做了什么 | 后果 |
|---|---|---|
| 0.9.21 | 让块选区所有行**共享**同一个 `charsMoved`（修加速度不均） | ✅ 加速度修好了 |
| 0.9.56 | 发现多光标横向拉伸是单行的 2.5 倍 | ❌ 上一步**弄坏**了分支判定 |

### 根因

`charsMoved` 这一个值承担了**两个职责**：

```java
// TrailCorner.jump() 里
isShortJump = sameLine && charsMoved <= typingMaxChars(3);
//         ↑ 职责1：选分支（短跳 vs 跨行），决定用哪个时长字段
```

```java
// 加速度判定也用它
animationLength = config.shortAnimationLength * trailFactors[rank];
//                    ↑ 职责2：影响四角时长的相对关系
```

**0.9.21 我把块选区的 `charsMoved` 从「每行各算」改成「共享一个大值」，修好了职责 2，却让职责 1 判错了。**

块选区的 offset 差跨行时天然几十上百，必然 > 3 → 被判成跨行 → 用 `animationLength`(0.28) 而不是 `shortAnimationLength`(0.11) → 拉伸暴涨 2.5 倍。

### 教训

**当一个值同时参与「分支选择」和「数值计算」时，改它之前必须先问：另一个职责会怎样？**

正确的做法是**拆成两个参数**：
- `charsMoved` → 只用于加速度计算
- `deltaY / height` → 只用于分支判定

这次没拆，改用「块选区传 -1 走归一化分支」绕开 —— 能用，但**下次动这块代码时要注意**。

### 检查清单

改任何「共享量」之前：

```powershell
# 1. 这个值被哪些地方读？
grep -n "charsMoved" src/main/java/**/*.java

# 2. 每次读取是「分支判断」还是「数值计算」？
#    两者混用 → 必须拆参数，不许共享
```

---

## 五、当前待办（按优先级）

| # | 事项 | 状态 |
|---|---|---|
| 1 | `stretchRatioK` 加进 `AnimPreset` | 待做 |
| 2 | 三个预设的 `typingSmoothness` 全 0，名不副实 | 待做 |
| 3 | 辉光层数随光标数降级 | 需实机测 |
| 4 | 残影模式多光标 | 待做 |
| 5 | GitHub 发布追平（当前线上 v0.9.33，本地 0.9.48） | 待做 |
| 6 | **预览光标大小/长度可配** —— 用户 2026-10-24 记下，以后做 | 待做 |
| 7 | 多光标辉光降级 | ✅ **已做**：F1 否决（实测无性能问题）；F2 已实现为总开关 |
| 8 | **预览光标大小/长度可配** | 待做（用户 2026-10-24 记） |

---

## 六、用户明确「先不做」的

| 项 | 原因 |
|---|---|
| 残影模式多光标 | 用户原话：「先不做了感觉纯狗屎功能」 |

---

## 七、用户提出但**不算 bug** 的观感差异（不要当 bug 修）

| 现象 | 真相 |
|---|---|
| 预览光标长度和实际不一致 | 预览按字符大小画，实际是顶满行高。**两者都对**，是「按字符」 vs 「顶满」两种表示法 |
