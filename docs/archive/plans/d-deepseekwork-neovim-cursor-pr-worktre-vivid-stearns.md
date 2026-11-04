# BUG-007：启动后第一次键盘操作必卡一下 —— 定位与修复计划

## Context（为什么做、要解决什么）

**现象**（用户原话，2026-10-28）：
- 每次启动 IDEA 后，**第一次碰键盘**（输入/删除）必定卡一下；之后整场会话不再复现。
- 表现有两种形态：「光标把字符的一半吸附过去」或「直接消失到下一个位置」。
- **启动后干等两分钟再动，照样复现** → 排除"冷启动 CPU 忙"。

**用户本轮新增的关键观察（决定性的）**：

> 开头不碰键盘 → 用鼠标引导光标 17→20 **不会卡**；只要第一次碰键盘就卡一下。

即：触发条件是「**第一次修改文档**」，不是「第一次使用光标」。
鼠标点击只改光标位置、不改文档；键盘第一次修改文档。这条把排查面收窄了一个数量级。

**本机已核实的环境事实**（读 `%APPDATA%\JetBrains\IntelliJIdea2026.1\options\NeovideCursorSettings.xml`
+ `core/NeovideConfig.java:264,98`）：

| 项 | 值 | 后果 |
|---|---|---|
| `hideNativeCaret` | `true`（默认，XML 未写=默认） | **原生光标全透明，我们画的覆盖层是唯一可见光标** |
| `alwaysHighFps` | `true`（默认） | 调度器全程 ~144fps，**没有空闲降频档** |
| `targetFps` | 144 | 帧间隔约 6.94ms |
| 当前动画预设 | 「基础 副本」：`trailSize=1.0`、`animationLength=0.1`、`shortAnimationLength=0.11` | 跨行分支 `leading = 0.1×(1-1.0) = 0` |

推论（**是可证的机制，不是猜测**）：既然唯一可见的光标由我们绘制，
「光标消失」只可能来自覆盖层那几帧没画出东西 —— `CaretTrailPanel.paintComponent`
在 `quadCount<=0 && !hasGhosts` 时直接 `return`（`CaretTrailPanel.java:721-726`），面板
`setOpaque(false)`（`:446`），于是只剩编辑器背景；「吸附半个字符」只可能来自
目标坐标算错（`visualPositionToXY` / `offsetToXY` 分岔，或用了尚未更新的布局）。

**现有诊断日志的真实能力（必须写清，否则会照着错的文档排查）**：

- 文件：`<config>/neovide-cursor-diag.log`，**每次 IDE 运行第一次写日志时 TRUNCATE**
  （`CursorDiag.java:68-88`），不是启动时清空。
- `[frame]` / `[trail]` **没有 `rawDt` 字段**。`rawDt` 只在 `idea.log` 的 debug 行
  （`CaretAnimator.java:1071-1074`），需要开 Debug Log。
- `[trail] dt=` 是**处理后的 dt**（正常 ≤50ms / 短停顿 8ms / 长停顿=rawDt）。
- `[frame] #N` 是**慢路径刷新序号**，不是渲染帧号；`frameSeq` 只在慢路径自增
  （`CaretAnimator.java:1596`）。默认采样 `%15`，**启动首帧 `#1` 永远不打印**。
- `[frame] targetMoved` 调用点恒传 `false`（`:2272`）→ `I1不冻结` 恒 OK，不能当证据。
- `[frame]`/`[trail]` 只在 SPRING 模式打印（调用点在 `tickSpring`）。

**结论**：BUG-LOG 里「抓 `[frame]`/`[trail]` 首帧看 rawDt」这条**照做拿不到 rawDt**。
所以必须先补一段"只写日志、不改行为"的诊断（用户已批准），再采集。

**目标**：拿到"首次键盘操作前后 60 帧"的逐帧证据，据此写出**确定的根因**并修复；
新增守门探针 + 变异测试；6 红名单逐字不变；D0 零 diff。

---

## 阶段 A：取证（先不改任何行为）

### A0. 先跑一次全量门禁，确认基线（**第一件事，不许跳**）

```bash
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr
```

- 期望：末尾 `V-PROBES RESULT: FAILED（6 个探针未通过）`，名单逐字为
  `VTools / VHints / VCustom / VReset2 / VSepBound / VSleep`。
- 不是 6 个就先停下查原因，**不要**把差异当成"文档过期"糊过去。
- **绝不并发跑第二次**（`build/v2-probe/classes` 共用，会 ClassNotFoundException 假红）。

### A1. 加首用诊断（只写日志，零行为变化）

全部加在 `ui/CursorDiag.java`，受**现有 `diagLogging` 开关**控制（默认关 = 生产零变化）。
新增 trace 走**只写文件、不走 `LOG.info`** 的通道（避免 144fps 刷爆 idea.log）；
**不改**任何现有 `[frame]`/`[trail]` 的行格式（`build/analyze-log.ps1`、`diag/extract.py`
用固定顺序正则解析它们）。

新增行（前缀都是新的，不影响既有解析）：

| 标签 | 触发点 | 字段 |
|---|---|---|
| `[boot]` | `CaretAnimator` 构造末尾（每 animator 一次） | id、editorKind、content 尺寸、isShowing、hideNativeCaret/alwaysHighFps/targetFps/trailMode、jvmUptimeMs |
| `[evt]` | `caretPositionChanged` / `caretAdded` / `caretRemoved` | 类型、vp、offset、documentStamp、**stampChanged**、sinceBootMs、`FIRST-KEY` 一次性标记 |
| `[tick]` | `tick()`（内部限量输出） | seq、rawDt、dt、是否命中恢复分支、isShowing、activeTrails、panel 尺寸、content 尺寸、delayMs、tick 耗时 µs、**投递延迟 µs**（`now - lastDispatchedDueNanos`） |
| `[geom]` | 慢路径 `trail.setTarget(...)` 之后（受 `[tick]` 同一次限量控制） | vp、cp=(x,y)、lineHeight、`offsetToXY(offset).x`、`vpToXY.x`、目标 cx/cy/w/h、四角坐标 |
| `[paint]` | `CaretTrailPanel.paintComponent` 入口/出口 | quadCount、quad 包围盒、耗时 µs、是否 `quadCount==0` 早退、异常 |
| `[clear]` | 三处 `clearCorners()` | 原因：`not-showing` / `mode-change` / `clear-trail` |

`[tick]` 的限量规则（关键，否则 144fps 会写爆文件，硬上限 5000 行/animator）：
1. 构造后前 40 个 tick；2. **首次文档戳变化后的 60 个 tick**（正片）；3. 任一 `rawDt > 20ms`；
4. `activeTrails` 非空但本帧什么都没画（`quadCount==0`）；5. `refreshTarget()` 走快速路径 / 被 IME 判据拦截的那一帧。

### A2. 你采集（我给出可直接照做的步骤）

1. 设置 → 其他设置 → Neovide 光标 → 勾「诊断日志」→ **Apply**（勾选不点 Apply 不生效）；
2. 重启 IDEA；
3. 打开任一个平时会卡的文件，**先用鼠标点几下**（把光标从 17 引到 20），**不要碰键盘**；
4. **按一个字符键**（或退格）一次；
5. 停 3 秒，再按一次键（对照：这次应当丝滑）；
6. 把 `C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\neovide-cursor-diag.log` 给我。

（不需要改 `idea64.exe.vmoptions`：新 trace 不走 `shouldSample`。）

### A3. 判定表（日志特征 → 根因 → 修法）

| 日志特征 | 根因 | 修法 |
|---|---|---|
| `[paint]` 首次有动画那帧耗时 >50ms | 首次绘制预热（Path2D/渐变/字体度量/类加载） | 构造期做一次离屏预热绘制（画进 `BufferedImage`，不显示），把一次性成本移到启动 |
| `[tick]` 投递延迟 >100ms、但 tick 自身 <5ms | EDT 被平台首次按键的惰性初始化占住 | 不是我们的 bug；在文档里写清并给出规避，或用"最后一次已知几何继续画"让光标不消失 |
| `[geom]` `vpToXY.x` 与 `offsetToXY.x` 差半字符，且该帧被画出去 | 用了尚未更新的布局坐标 | 该帧按 IME 判据的同款逻辑冻结一帧（复用现有 `imeSkipFrames` 上界），并用 `offsetToXY` 兜底 |
| `[tick]` rawDt 极大且 `dt=rawDt`（恢复分支命中） | 首帧/唤醒帧 `lastNanos` 未重置 | 只在 `lastNanos==0`（真首帧）把 dt 归一帧；改前必须确认日志确实显示是这条 |
| `[paint]` `quadCount==0` 而 `activeTrails>0`，或 `[clear] not-showing` | 覆盖层确实停了绘制 | 修 `isShowing`/面板尺寸闸门，或在不可见时保留上一帧几何 |
| 以上都不成立（耗时全正常） | 我们的绘制被平台重绘时序覆盖 | 继续查 `[paint]` 次序 + RepaintManager 合并行为，再定 |

**纪律**：这张表是"证据 → 修法"的映射，**不是**候选清单。最终根因必须由日志证据唯一确定，
并在 BUG-LOG 里写清"哪一行日志对应哪一条结论"。拿不到唯一对应就继续加诊断，不改生产逻辑。

---

## 阶段 B：修复

### B1. 按 A3 选中的那一条修

约束：
- 不改 `core/NeovideConfig.java`（D0 门禁）；
- 不动 0.9.98 已发布行为：预设快照仍是 13 个字段、颜色与输入平滑仍不随动画预设；
- 修复必须**只在异常条件下生效**（首帧 / 几何不一致 / 首次绘制），正常帧路径零变化，
  并用注释写清"为什么"。

### B2. 把决策逻辑提成纯函数，供探针直接调用

沿用既有做法：`ui/CaretAnimatorProbe.java`（现已暴露 `ACTIVE_DELAY_MS()`，被 `VFrameRate` 使用）
里增加包内静态纯函数（示例，按实际修法定名）：

```java
// 例：dt 决策（首帧 / 恢复分支 / 正常）
static double resolveDtForProbe(long lastNanos, double rawDt)
```

**探针必须调用生产函数，不许自己重写算法**（CLAUDE.md 陷阱 3）。

### B3. 新增守门探针 `tools/settings-probe/VBoot.java`

- 默认包、`public final class VBoot`、自带 `check(String, boolean, String)`；
- 断言"首帧 dt 必须是**一帧**而不是 rawDt"、"半字符几何不一致必须被拦"等（按 B1 定稿）；
- 输出格式：`  PASS` / `  FAIL`（两个前导空格），末尾
  `=== V-BOOT 结果：PASS n，FAIL m ===` + `V-BOOT RESULT: ALL PASS|FAILED`；
- **必须 `System.exit(fail > 0 ? 1 : 0)`**（`VHandFeel` 只打 FAILED 不 exit，门禁会误判为绿）；
- **必须登记**：`tools/settings-probe/build-v-probes.ps1:80-120` 的 `$mains` 数组里加 `'VBoot'`
  —— 不登记 = 永远不跑（0.9.83 那次"整份门禁从未执行"的教训）；
- 编译不用登记（脚本按 `V*.java` 全量编译）。

**变异测试**（必须做，且**删 classes 全量重编译**，增量会复用旧字节码）：
1. 把修复注释掉 → `VBoot` 必须变红；
2. 恢复 → 必须变绿；
3. 记录两次结果（贴 PASS/FAIL 计数）到 BUG-LOG。

---

## 阶段 C：收尾

1. **D0**：`git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java` → 零 diff。
2. **全量门禁**：6 红名单**逐字不变**（`VTools/VHints/VCustom/VReset2/VSepBound/VSleep`），
   新增红只允许是"探针总数断言"这类契约变更 —— 本次不加 UI 控件，理论上不该有新红；
   出现新红就是真回归，回头查。
3. **`docs/BUG-LOG.md` 的 BUG-007**：补「根因 / 修法 / 证据 / 变异测试 / 门禁数字」，
   状态从「未定位，本轮不修」改成实际结果；
   同时**订正**该条里"抓 `[frame]`/`[trail]` 的 rawDt"这句（现在不成立）。
4. 不留临时脚本；`build/` 下不新增一次性脚本。

---

## 验证方式（端到端）

| 步骤 | 命令 / 操作 | 期望 |
|---|---|---|
| 编译 | `build-v-probes.ps1 -Repo ...`（自带编译） | 无 ParseError，`$mains` 全部真的跑起来 |
| 探针 | 同上，看 `V-BOOT` 行 | 新增断言全 PASS |
| 变异 | 注释掉修复 + 删 `build/v2-probe/classes` 后重跑 | `VBoot` FAIL，门禁失败数 +1 |
| D0 | `git diff --exit-code -- .../core/NeovideConfig.java` | 无输出 |
| 真机 | 装包后：启动 → 先鼠标点几下 → **第一次按键** | 不再卡；`[tick]` 该帧耗时/延迟正常 |
| 回归 | 正常打字/删除/跨行跳转/多光标/MC 开关 | 观感与 0.9.98 一致 |

装包（真机复验用，注意它会先备份再还原个人配置）：
`pwsh -NoProfile -ExecutionPolicy Bypass -File build/install.ps1 -Version <本次版本> -NoStart`

---

## 不做的事

- 不推送（`git push fork main` 由你决定时机）；不动 `docs/archive/`；
- 不顺手修那 6 个红探针，也不改它们的方向；
- 不为让探针变绿改生产代码，也不删断言；
- 不动预设参数、不动说明文案（有独立待办在 `docs/NEXT-STEPS.md`）；
- 不在拿到日志证据前改 `tick()` / `refreshTarget()` 的恢复分支。
