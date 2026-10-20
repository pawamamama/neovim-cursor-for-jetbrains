# LESSONS.md —— 踩坑记录与经验总结（权威汇总）

> 生成：2026-10-19　更新：2026-10-20
> 当前 HEAD：`3c3944d`　当前安装：`0.9.4-settings`（内容指纹 `7D5CDEF493B4DFA6`）
> 第八部分是 0.9.4 批次新增的 6 条教训（坑 11–16），**尤其坑 16：用户实测 > 我的推断**。
> 第九部分是 0.9.5 批次新增的 6 条教训（坑 17–22），**尤其坑 21（修 A 引入 B 的病根）与坑 22（God Class 数据）**。
> 本文取代此前散落在各处的踩坑记录。**遇到问题先查这里**，再查对应的批次契约。
>
> 写作原则：**每条都要有可复现的证据**。没有数据的结论一律标注"推断"。

---

## 第一部分：这个项目的真相（先破除三个误解）

### 误解 1：插件画得慢 → 错，它快得离谱

| 指标 | 实测值 | 占 8ms 预算 |
|---|---|---|
| 12 层辉光绘制 | **0.05 ms/帧** | 0.6% |
| 完整 Swing paint | **0.09 ms/帧** | 1.1% |
| 插件占 EDT 采样（45 分钟 JFR） | **3.26%** | — |

**所以"优化绘制"这条路早就走到尽了。** 任何以"画得更快"为目标的改动，收益上限是 0.6%。

### 误解 2：重绘面积大 → 错，面积大反而更快

| 重绘面积 | fps |
|---|---|
| 1,063,867 px | **66.6** |
| 85,387 px | 101.7 |
| **3,551 px** | **15.7** |

**面积差了 300 倍，帧率却是大面积更高。** 所以"收紧重绘区域能提帧率"这个直觉是错的
——它只影响 EDT 的绘制耗时，而瓶颈不在那儿。

### 误解 3：卡是插件造成的 → 只有 10.7%

EDT 内部栈顶 TOP 5（45 分钟 JFR）：

| 方法 | 占 EDT |
|---|---|
| `sun.java2d.loops.ScaledBlit.Scale` | **12.6%** ← 单项就比整个插件高 |
| `FillParallelogram` | 11.9% |
| `MaskFill` | 5.9% |
| `DrawGlyphListLCD` | 3.7% |
| `WComponentPeer.getLocationOnScreen` | 3.3% |

**真正的瓶颈：EDT 抢占。** 插件日志里 `delay=8ms`（说明调度器正常）配 `maxGap=358ms`
—— 投递出去的任务在 EDT 队列里排了几百毫秒。

---

## 第二部分：这一路踩过的坑（按代价排序）

### 坑 1（最贵）：把"分步推进"当成优化 —— 它是数学上的空操作

**当时的动机**：低帧率下第一帧就吃掉大部分位移，想把它拆成多步让动画均匀。

**实测结果**：

```
一次 update(0.12)      = 6.828984590243152
分 15 次 update(0.008) = 6.828984590243153
差 = 8.88e-16   ← 完全等价
```

因为 `DampedSpring.update()` 是**闭式解**，对时间可加。而 `updateCorners()` /
`submitRepaint()` 在 steps 循环**之外** —— 一次 tick 只画一帧，中间状态根本不渲染。

**「15 步塞进 1 帧」=「1 步推进」，对用户完全一样。** 而且实测更糟 38~46 个百分点。

**教训**：动动画之前先确认"这次改动会不会真的产生中间帧"。

---

### 坑 2：单帧位移截断（`MAX_STEP_RATIO`）会毁掉弹簧曲线

早期加了"每帧位移不超过剩余的 50%，超了就清零速度"。触发条件：

```
平时 dt ≈ 8ms, animationLength = 0.05 → 单帧走 13.6%  < 50%  不触发
卡顿时 dt 被钳到 33.3ms                → 单帧走 74.5%  > 50%  触发截断
```

**越卡越锯齿** —— 高延迟和截断互相放大。而且一旦截断就清零速度，
下一帧从零加速，弹簧不再是临界阻尼曲线，动作发"肉"。

**现在的做法**：严格按原版闭式解推进，**不做任何截断**。

---

### 坑 3：`MIN_ANIMATION_FRAMES` 低帧率补偿，把拉伸抹平了

原意是"保证动画至少跨 6 帧"。但在 12ms 帧率下：

```
0.015s 的动画 → 被抬到 6 × 12ms = 0.072s
0.050s 的动画 → 被抬到 6 × 12ms = 0.072s
```

**四角时长全变成同一个值 → 梯度消失 → 光标只是整体平移，不再被拉伸。**

上游 README 踩坑 #20 记录了同一个错误。**现在的状态：不加。**

---

### 坑 4：短跳判据用像素 → 中文永远判错

`TrailCorner.jump()` 用 `hypot(dx/width, dy/height) <= 8` 判断"是否短跳"，
而 `cursorWidth = 2px`：

| 操作 | dx | jumpX | len | 判定 |
|---|---|---|---|---|
| 英文 1 字符 | 10px | 5.0 | 5.0 | ✅ 短跳 |
| **中文 1 字** | **20px** | **10.0** | **10.0** | ❌ **跨行分支** |

而跨行分支 `trailSize=1` 使 `leading = 0` → rank≥2 前缘时长 0 → 弹簧短路 → **瞬移**。
这正是「英文丝滑、中文卡」的成因。

**失败过的三种替代方案**（别再走）：

| 方案 | 为什么失败 |
|---|---|
| 硬编码 16px | 字号 20px 时中文宽 20px，删一个就超出 |
| `FontMetrics.charWidth('m') × 2.5` | `'m'` 是西文推进量，中文是 2 倍 —— 只是换个魔数位置 |
| 第0列 → 第1列实测宽 | **取的是行首字符**，光标在中文区时仍翻倍 |

**正确解**：用编辑器原生 `getOffset()` 差值 —— 那就是精确的字符个数，
与字体/字号/全角半角/连字/emoji 全部无关。

---

### 坑 5：IME 中间态判据，第一版完全无效

**第一版判据**：`getOffset()` 没变 但 `vp.column` 变了 → 判定为组合中间态。

**为什么无效**：某些第三方输入法（水杉输入法）会把光标**放进拼音音节内部**，
此时 **offset 和 column 都变了**，前提不成立 → 判据永不触发。

**第二版判据（有效）**：

```java
editor.offsetToXY(offset).x != editor.visualPositionToXY(vp).x
```

依据（IDEA 261 字节码核实，不是猜）：

```
visualPositionToXY → 按 vp.column（视觉列）迭代 fragment → Fragment.visualColumnToX
offsetToXY         → 先钳进 [0,textLength] + alignToCodePointBoundary
                     → 再按 offset 迭代 fragment → Fragment.offsetToX
```

正常状态下两者指向同一字符边界、给出同一个 x。组合串是临时文本，
字符宽度与最终汉字不同，于是差出**半个字宽**。

**教训**：写判据前先确认"这个前提在目标场景下是否成立"。
我的第一版假设了"offset 不变"，而目标场景恰好是"offset 变了"。

---

### 坑 6：几何判据必须排除 virtual space，否则引入更糟的新 bug

IntelliJ 默认开启 virtual space，按 → 能把光标推到行尾之后。此时两个 API
**本就应当给出不同 x**：

```
visualPositionToXY → 行尾 x + 空格宽 × 越界列数
offsetToXY         → 被钳到行尾 offset，返回行尾 x
```

**不排除的后果**：每次把光标推到行尾都会被判成 IME 组合态并冻结。
**这比原 bug 更糟** —— 原 bug 只在第三方输入法下出现，这个每次按 → 都出现。

**排除判据**（保守方向：宁可多走一次重算，也不要错误冻结）：

```java
private boolean isInVirtualSpace(int caretOffset, VisualPosition vp) {
    Document doc = editor.getDocument();
    int line = doc.getLineNumber(caretOffset);
    int lineEnd = doc.getLineEndOffset(line);
    if (caretOffset != lineEnd) return false;        // 不在行尾
    int lineLength = lineEnd - doc.getLineStartOffset(line);
    return vp.column > lineLength;                    // vp 真的越界
}
```

**必须有两个方向的用例**，否则排除条件容易写太宽：

| 用例 | 期望 |
|---|---|
| 光标在行尾之后（`vp.column > 字符数`） | 放行（是 virtual space） |
| 光标在行尾且 `vp.column == 字符数` | **仍按几何判据判定**（排除不得过宽） |

第二行是关键：简单写成"offset 在行尾就放行"会把真实的中间态一起放过。

---

### 坑 7：`nextDueNanos` 从"tick 结束的 now"起算，多等一整个 delay

**旧写法**：`nextDueNanos = System.nanoTime() + currentDelayMs`

基线是「**先 sleep(delay) 再投递**」，节拍由 sleep 主导 —— 单帧 EDT 耗时超过 delay 时，
**不会再额外多等**，有效周期就是单帧耗时本身。

而"从 tick 结束的 now 起算"会让周期变成「单帧耗时 + delay」。

实测（1500ms 窗口，tick 人为注入 25ms）：

| 场景 | 基线 | 旧写法 | 修复后 |
|---|---|---|---|
| 常驻刷新 + tick25 | 59 | **44**（−25%） | 56 |
| 自适应 + tick25 | 59 | **37**（−37%） | 57 |
| 常驻 + tick25 + 打字 | 59 | 48 | 60 |

**修法**：从「上次投递的**应到期时刻**」往后累加 delay，并做追赶：

```java
long next = (dispatched == Long.MIN_VALUE)
        ? now + currentDelayMs * 1_000_000L     // 防御 MIN_VALUE 溢出
        : dispatched + currentDelayMs * 1_000_000L;
nextDueNanos = Math.max(next, now);             // 已超时 → 立即到期
```

**教训**：如果没做验证，这会作为一个"悄悄变慢 25%"的版本交付出去。

---

### 坑 8：`fading` 判定缺终止条件 → EDT 永不降频

```java
// 错
boolean fading = fade < 1f && config.overlayFadeOut;
```

`fade == 0` 时 `0 < 1` 恒真 → `tickSpring()` 恒返回 true → `computeNextDelay(active=true)`
→ **EDT 永不降频，空闲也在 8ms 空转**。

**为什么手测发现不了**：本机 `alwaysHighFps` 持久化为 `true`，本来就常驻 8ms。
但**干净安装的用户**（默认 `false`）第一次移动光标后 EDT 就再也不休息。

```java
// 对
boolean fading = config.overlayFadeOut && fade > 0f && fade < 1f;
```

**教训**：`<` 和 `>` 的边界要逐个想清楚；这类 bug 在"默认值恰好掩盖它"的环境里测不出来。

---

### 坑 9：淡出"实现了但像素上不可见"

**第一版**：用 `anyAnimating` 当淡出计时起点，`glowStrength` 归零后开始淡出。

**实测**：`gs>0 且 gf<1` 的帧数 = **0**。因为 `anyAnimating` 与 `glowStrength`
是同一条曲线上的两个读数 —— 淡出刚开始，已经没有辉光可淡了。

**修法**：以「glowStrength 最后一次 > 0 的时刻」为计时起点，并记住那个亮度：

```java
if (glowStrength > 0f) {
    lastGlowStrength = glowStrength;   // 记住归零前的亮度
    lastAnimatingNanos = now;
}
float effectiveGlow = (glowStrength > 0f) ? glowStrength : lastGlowStrength * fade;
```

**教训**：**功能"写了"不等于"有效"**。必须逐帧检查数值，不能只看代码跑了。

---

### 坑 10：`build/` 整个被 gitignore → 回退脚本从未入库

`.gitignore` 里写 `build/` 会让 git **不深入该目录**，于是：

```gitignore
build/
!build/rollback-*.ps1      ← 这条永远不生效
```

**正确写法**：

```gitignore
build/*
!build/rollback-*.ps1
!build/offline-build.ps1
```

**教训**：所有回退脚本一度只存在于本地磁盘，换机器或新会话就全丢。

---

## 第三部分：验证方法（比结论更重要）

### 3.1 物理/几何"逐位一致"是唯一可信的零观感证据

**做法**：用 `Double.doubleToRawLongBits` 输出坐标的十六进制位模式，
基线源码与改动后源码各跑一次，**stdout 必须逐字节相同**。

**为什么需要它**：主观判断"手感没变"不可靠；而这种比较能抓到 1e-16 级别的差异。

**harness 位置**：`tools/parity/`（PhysicsParity / GeometryParity / TrailParityTest）

**特别有价值的用例**：
- **退化四边形**（四角重合、单条零长边）—— 专抓"把局部数组提为字段后忘记清零"
- **中文删除**（offset 差 1、像素差 20）—— 专抓短跳判据
- **dt 序列跨越 MAX_DT 与 RECOVERY_GAP** —— 专抓时间相关分支

### 3.2 帧率测量必须多轮取样，因为噪声只会压低帧数

`RegimeProbe` 同一二进制连跑 3 次：

| 场景 | run1 | run2 | run3 |
|---|---|---|---|
| R4（基线） | 52 | 52 | **59** |

**噪声不会让你测出更高的帧数**，所以取多轮**最大值**更接近真实能力。

而且必须区分「系统性差异」与「噪声」：

```
base R2: 58, 58, 58, 58   ← 零方差
new  R2: 56, 56, 56, 56   ← 零方差
```

差 2 帧但两边零方差 → 这是**系统性差异**，不是噪声。说成"噪声范围内"是错的。

### 3.3 PowerShell 的一个静默陷阱

```powershell
# ❌ 不报错，但恒返回 0 命中（假阴性！）
Select-String -Path <目录> -Include *.java -Pattern '<正则>'

# ✅ 正确
Get-ChildItem -Recurse -Filter *.java <目录> | Select-String -Pattern '<正则>'
```

实测（同一棵基线源码树）：

| 写法 | `7 个死字段` | `public` |
|---|---|---|
| 逐文件（金标准） | **7** | **113** |
| `-Path 目录 -Include` | **0** | **0** |
| `-Path 目录 -Include -Recurse` | 报错（`Select-String` 无 `-Recurse`） |

**危险之处**：它会把"7 个死字段已清零"验成"基线本来就 0"，
**屏幕上没有任何提示**，足以让验证结论反转。

---

## 第四部分：上游提交清单

| 编号 | 类型 | 内容 | 状态 |
|---|---|---|---|
| [PR #4](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/pull/4) | PR | `lastNanos` 一行修复（空闲后首帧 dt 拉满） | open |
| [#5](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/5) | issue | CJK 短跳判据（像素 → 字符偏移） | open |
| [#6](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/6) | issue | IME 中间态错位（**含更正评论**：非通用 IME 问题） | open |
| [#7](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/7) | issue | `maxTrailDistanceFactor` 与 README 矛盾 | open |
| [#8](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/8) | issue | 1ms busy-retry 退避代价 ~3.4% | open |
| [#9](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/9) | issue | virtual space 使两个 API 合法分岔 | open |

---

## 第五部分：我们 fork 相对上游的差异（如实记录）

### 我们比上游强的

| 项 | 说明 |
|---|---|
| 共享调度线程 | N 条 → 1 条（上游 0.5.0 也有，但实现不同：它批量 tick，我们保留 per-animator 投递） |
| `nextDueNanos` 推算 | 修掉了"多等一个 delay"的回退 |
| CJK 短跳 | 用字符偏移（上游仍是像素） |
| IME 中间态 | 几何判据 + virtual space 排除（上游没有） |
| 日志开销 | `isDebugEnabled` 门控（上游是无条件 `LOG.info`） |
| 每帧分配 | 复用对象（上游每帧 new） |

### 上游比我们强的

| 项 | 说明 |
|---|---|
| **多光标** | 上游有 `SpringTrail`，Alt 列选时有拖尾；我们只跟主光标 |
| **淡出分支设计** | 上游用 `hideNativeCaret` 分两条路，**这是对的**（我们改成了只淡辉光，因为我们的默认档是常驻绘制） |

---

## 第六部分：当前已知但未修的问题

| 项 | 影响 | 为什么没修 |
|---|---|---|
| `BUSY_RETRY_MS=1` 造成 EDT 繁忙时 −3.4% | 小 | 改 0 引入空转，收益不值；已提 issue #8 |
| 多光标无拖尾 | 中（Alt 列选用户） | 需移植 `SpringTrail`，工作量大 |
| ~~设置界面~~ | — | **0.9.4 已做**，但 4 个缺陷待修（见 `START-HERE.md` §6.5） |
| 空闲时仍往 EDT 交脏区 | **可能最大** | P0 待做：8 编辑器 ≈ 1000 次/秒重画已正确的画面 |
| 隐藏标签页仍 8ms 调度 | 中 | P1 待做：`isShowing()` 只是不画，每帧仍换算坐标 |
| 控制台光标由 `tickStaticCaret` 补画 | 低 | 与上游"跳过 CONSOLE"不同，本批按契约未碰 |

---

## 第七部分：如果下一个人要接手

1. **先读本文档**，再看对应批次的契约（`OPTIM-BATCH1.md` / `FIX-BATCH2.md` / `FIX-BATCH3.md`）
2. **改代码前跑一遍**：
   ```powershell
   pwsh -File tools\parity\run-parity.ps1      # 三份必须逐字节一致
   pwsh -File tools\parity\run-audit.ps1       # 19 项常量只能有预期内的 DIFF
   pwsh -File tools\ime-probe\run-ime-geom-test.ps1   # 29 项边界
   ```
3. **一次只改一个变量**，改完立刻构建 + 记录 + 准备回退
4. **不要凭直觉调参数** —— 这一路的失败全部源于此
5. **`VERSIONS.md` 是唯一可信的版本记录**，每次构建必须追加
6. 回滚脚本都在 `build/rollback-*.ps1`（现在已入库）

### 一个反复出现的教训

**"我改了" ≠ "它生效了" ≠ "它没弄坏别的"**。这三件事需要三种不同的证据：

| 断言 | 需要的证据 |
|---|---|
| 改了 | `git diff` |
| 生效了 | **逐帧数值**（不是代码跑通） |
| 没弄坏别的 | **parity 逐字节一致** + 常量审计 |

这一路三个真问题（淡出 no-op、`fading` 永不终止、`nextDueNanos` 慢 25%）
全部是**代码"看起来对"但数值不对**，全部由独立验证用逐帧证据抓出。

---

## 第八部分：0.9.4-settings 批次新增的教训（2026-10-20）

### 坑 11：**测试探针的假设会成为盲区** —— 三个探针同时 return true，两个 high 就这样漏过去

0.9.4 的 t3/t4 跑了 100+ 条断言全绿，但审查（t5）抓出两个 high。根因：

```java
// 五个探针（ApplyConfigProbe:111 / PanelProbe:421 / VerifyApplyConfig:376 /
// VerifyPanel:311 / VerifyPersistence:347）的假 Application：
case "isDispatchThread": return true;   // ← 全部恒 true
```

于是**非 EDT 分支零覆盖**。而面板 `apply()` 本身在 EDT 上跑，**真机手测也发现不了**。

**教训**：探针不仅要覆盖"正常路径"，还要**主动构造反常环境**（非 EDT、无 Project、已 dispose）。
判断探针是否有效，唯一标准是 **"把修复回滚，它会不会变红"**（变异测试）。

### 坑 12：变异必须**忠实**，否则会得出错误结论

t8 复现 R-01 时，第一次只"在顶部插早退"、**保留了同步后的重判**，探针仍然全绿。
改成删掉重判的**忠实还原**才变红。

**教训**："我回滚了"和"我真的回到了修复前的形态"是两件事。变异后要先 diff 确认形态一致。

### 坑 13：`forceResync` 不是万能的 —— 它绕不过**位置未变**的早退

契约原本假定 `onModeChanged()` 置 `forceResync=true` 就能让下一帧慢路径重算 `glowPadding`。
**实测不成立**：`CaretAnimator:1007` 有一条早退：

```java
if (newCenterX == centerX && newCenterY == centerY && height == cursorHeight) {
    return false;   // ← 在 :1018-1019 重算 glowPadding 之前
}
```

光标静止时它恒成立；静态光标编辑器更是整体跳过 `refreshTarget`。

**教训**：说"某标志能让代码走到某处"之前，要**逐条数清中间有几个早退**。
`forceResync` 只绕过了 `:871` 的视觉位置早退，绕不过 `:1007` 的中心/高度早退。

### 坑 14：设置界面的"数值不跟手"—— `bindValue` 不注册 ChangeListener

0.9.4 交付后用户实测反馈"拉参数时候数值不会改变"。实机复现：

```
滑块[0] 上的 ChangeListener 数量 = 0
拖动后 pending.cursorDisappearDelay = 50（拖动前也是 50）
标签跟随变化的滑块数 = 0 / 8
```

`SliderKt.bindValue(cell, getter, setter)` 只在 `DialogPanel.apply()` 时取组件值，
**拖动过程中不回调 setter**。所以 "右标签实时显示" 必须自己 `addChangeListener`。

**教训**：DSL 的"双向绑定"是**面板级**（apply/reset 时同步），不是**控件级**（拖动时同步）。
这两者不是一回事，别想当然。

### 坑 15：切主题会**丢掉**透明的 CARET_COLOR，而插件有早退导致不会重新隐藏

全仓库搜 `EditorColorsListener` / `schemeChanged` → **零命中**。而：

```java
// NeovideCaretManager:606
if (savedCaretColor != null) {
    return;   // 以为"已经隐藏过"
}
```

后果：插件隐藏原生光标 → 用户切主题（scheme 整个换掉，透明色丢失）→ 原生光标复活 →
但插件以为还隐藏着 → **两套绘制叠加**（表现为"更卡"、"双光标"）。

**教训**：任何"改全局可变状态"的操作，都要问一句 **"别人把它改回去怎么办"**。
这里没监听 scheme 变更，就必然要重新隐藏，而早退让这件事不发生。

### 坑 16：用户实测 > 我的推断（这条要写在最前面）

我曾断言「关常驻刷新 + 隐藏原生光标」会"卡到没法用"，建议从 UI 上禁止。
**用户反驳，真机日志证明他是对的**：

```
23:29:21  applyConfig 完成（animators=11, hideNativeCaret=true, alwaysHighFps=false, ...）
```

他关常驻刷新 + 保留隐藏原生，连用 6 次都正常。原因：`caretPositionChanged` 每次光标移动
都调 `wakeUp()` **立刻拉回 8ms**，只有完全静止才降到 120ms —— 那时也不需要响应。

**所以那其实是"单套绘制 + 省电"的最优组合，我却想禁掉它。**

**教训**：涉及观感的判断，**用户的实测优先于我的代码推断**。断言的边界要想清楚：
我错在把"完全静止时降到 120ms"当成"卡"，而静止时人根本不需要光标响应。
---

## 第九部分：0.9.5-settings-ui 批次新增的教训（2026-10-20）

### 坑 17：**判据的形状决定它能抓到什么**（verifier 提出，比「要小心」更可操作）

「`shortAnimationLength` 低于 X 会归零」这种判据**只有一个阈值**，
它天然抓不到「不同 `dt` 口径下有不同阈值」这件事。

`VSpring` 探针不接受「一个阈值」，只回答「**这个值在 `dt=X` 下能否跑起来**」，
于是 8ms 与 50ms 两个口径的差异被**数据形状**逼出来 —— 不是靠谁更细心。

对照：captain 的文案错在用一个阈值概括两个口径；探针因为**不能表达**「一个阈值」而避开了这个错。

**可操作结论**：设计判据时，若真实世界有多个维度（不同的 `dt`、不同的主题、不同的光标模式），
判据就**必须带那个维度**，否则它会把多维事实压成一个数字。

### 坑 18：**假 FAIL 与假 PASS 同样是探针缺陷**（本轮三个实例）

| # | 谁 | 假信号 | 修法 |
|---|---|---|---|
| 1 | verifier | 拖动目标写死成滑块最大刻度 → `tailOpacity`/`trailSize` **假报 FAIL**（拖前已是上限） | 目标必须 ≠ 当前值 |
| 2 | impl-core | HTML 断言用 `contains` 原串 → **假 FAIL**（`DslLabel` 会规范化空白） | 改用「非空白片段间允许任意空白」的正则 |
| 3 | impl-core | 「真实原色」与 `getDefaultForeground()` 取值相同 → **假绿** | 两值故意取不同（`#A9B7C6` vs `#00FF00`） |

**教训**：探针通过不说明什么，探针失败也不说明什么 —— **先证明探针能区分**，再信它的结论。

### 坑 19：**改源码后必须 grep 所有依赖它的脚本锚点**

t7 在滑块 `ChangeListener` 里插入 `refreshPresetDisplay()` 后，
t4 的 `run-v-mutations.ps1` MUT-A 的 heredoc 锚点失配：

```
EXIT=1「MUT-A：找不到 ChangeListener 整段」
→ B/C/D/E/F 五个变异根本跑不到
```

**代码没问题，纯粹是脚本锚点没跟着改**，而脚本失败不会告诉你「后 5 个变异没跑」。

**做法**：改源码后 `grep -r` 一遍所有 `.ps1` 里的字面量锚点；变异脚本要有「锚点失配即报错」的保护。

### 坑 20：**「给人看的」和「给机器判的」必须分开**

两个实例：

1. 探针把「阶段 1 全绿」和「阶段 2 必红」打在同一个 stdout，阶段 2 的 `FAIL` 行与真失败同形 ——
   复跑者只截尾部就**误判失败**（captain 亲自踩过）。
2. `$codes += T ...` 把函数里 `Select-String` 的输出也收进数组 → `FAILED COUNT = 140` **假报警**。

**修法**：函数内人类可读输出一律 `Write-Host`，**只 `return` 退出码**；
预期的「必红」用 `[预期变红]` 前缀或只打汇总条数。

### 坑 21：**修 A 引入 B 是共享可变状态的必然产物** —— 本轮四连

```
修缺陷 1（补 ChangeListener） → 引入 B-1（应用按钮变灰）/ B-2（按钮永亮）
修 B-1/B-2                    → 引入 R-01（第二次 apply 冲掉 Tools）/ R-02（下拉不刷新）/ R-03（旧 scheme 不恢复）
修 R-03（加 HIDDEN_SCHEMES）    → 引入 R-09（切回旧主题时原色被兜底色覆盖）
```

**共同病根**：19 个参数分布在 **4 个地方**（`NeovideConfig` 默认值 /
`NeovideCursorSettings.State` 持久化 / `CONFIG` 运行期 / 面板 `pending` 预览副本），
而「同步」这件事**没有单一权威**。每次修复都是在某两地之间补一条同步，于是引入新边界。

**可操作结论**：凡是跨主题/跨会话的「账本型」状态（`savedCaretColor`、`HIDDEN_SCHEMES`），
每加一条写路径都要问：**「它会不会读到已经被我改过的值？」**

### 坑 22：**单类 1,582 行 / 54 字段 = God Class，而 bug 全出在它身上**

`NeovideCursorConfigurable` 的实测数据：

| 指标 | 值 |
|---|---|
| 行数 | **1,582** |
| 非注释有效代码 | 1,029 |
| 字段数 | **54** |
| 方法数 | 65 |
| 注释占比 | 24.5%（**正面资产**：写的是「为什么」不是「做了什么」） |

**本轮 8 条 medium 全部出在它的状态同步上**（`pending` / `baseline` / `syncingControls` / 控件值 四者之间）。

**但「不可维护」这个判断要分开看**：
- 好的：24.5% 解释性注释 + 200+ 条可复跑探针 + 1,546 行契约写死每个决策理由 → **能读懂也能验证**
- 差的：1,582 行单类、`createPanel()` 一个方法 200+ 行、54 字段的状态空间过大

**结论**：当前是「可维护但不理想」，不是「不可维护」。

### 下一版计划：设置面板结构重构（不改任何行为）

| 重构 | 解决什么 | 预估 |
|---|---|---|
| 拆 `NeovideCursorConfigurable` 为 `PanelBuilder` / `PanelBinder` / `Configurable` 三个类 | 1,582 → 每个 ~400 | 半天 |
| 引入单一 `EditSession` 对象，收进 `pending`/`baseline`/`syncingControls` | 消灭「四地同步」病根，R-01/R-02 类 bug 结构上不再发生 | 1 天 |
| 把 `SliderBinding` 从内部类提出，自管「值↔控件↔标签↔预设匹配」 | 消除 65 个方法里的重复 | 半天 |

**风险很低**：纯结构整理、不改行为，可用现有 200+ 探针当回归护栏（t2/t3/t4/t7/t9 全部探针复用）。
