# A-like 20ms 跨行吸附实验 执行报告（2026-11-06）

> 状态：已实现并全量验证；**只打包、未安装、未提交、未 push、未建 PR**
> 分支：`claude/determined-kowalevski-c1c64b`（HEAD `f43ca55`，未切换、未 reset）
> 施工依据：同目录 `PROMPT.md`（唯一依据）
> 产物：`dist/neovide-cursor-0.9.136-a-like-snap.jar`
> 大小：249,220 字节　SHA256：`41d11aa64fb5b1a8fd9548b293f008360360962b4a98b230fa25d69405f8ad23`
> jar 内版本：`0.9.136-a-like-snap`；`since-build=242` / `until-build=261.*`
> IDEA：**未安装、未启动**

## 1. 结论

**完成。** 在已有设备级默认关的 `crossShapeExperiment` 开关下，把**跨行 / 鼠标拖动 / 键盘大跨度**分支改为 A-like：

```text
leadingAlignment = 归一化运动方向 · 角点方向
if leadingAlignment > 0.5:
    animationLength = 0.02s          // 正 20ms，走弹簧，不瞬移
else:
    animationLength = config.animationLength × {1.0, 0.72, 0.42, 0.22}[rank]
```

单行短跳（打字 / 删除 / 同行 ≤ `typingMaxChars`）不读该开关、不读对齐度、不读 20ms 常量；连续 30 次「f」的逐次断言证明开关 true/false 结果逐位相同且恒等于短跳金值。

本轮**没有**重做 2026-11-05 的实验开关、持久化、置灰、预览接线；与 2026-11-05 报告的关系是：**同一个 `crossShapeExperiment` 开关的 A-like 变体**，只替换了「开启后走什么公式」。

验证结果：

| 项 | 结果 |
|---|---|
| 全量门禁（最终） | `V-PROBES RESULT: ALL PASS`（116 个探针进程；唯一 exit=1 是 informational 的 `VRegr2`，R-5/R-6 为既有真缺口，脚本按设计不计入门） |
| 新探针 | `VCrossShapeExperiment PASS=33 FAIL=0` |
| D0 `core/NeovideConfig.java` | `D0_EXIT=0`（零 diff） |
| 源码编译 | 37 个 `.java`，classpath 381 个 jar |
| 变异 M0（旧公式） | `VCrossShapeExperiment 28/5` → 探针红、总门禁 FAILED |
| 变异 M1（默认改 true） | 4 个探针红（`VCrossShapeExperiment` / `VDefaultFreeze` / `VHints` / `VerifyPanel`） |
| 变异 M2（吸附放进短跳） | 5 个探针红（含 `X-5b/X-5c/X-6c`、`VTypingSpan T-2a/T-4`） |
| 变异 M3（预览不接开关） | `X-7h` 单条红 |
| 恢复后 | 最终门禁 `ALL PASS` |

**不宣称「手感已像 A」** —— 观感必须由用户明天实机 A/B 裁决。

## 2. 改动清单

### 2.1 生产代码（本轮只有 1 个文件）

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `src/main/java/.../core/TrailCorner.java` | ① `A_LIKE_TRAIL_FACTORS = {1.0,0.72,0.42,0.22}` 替换旧 `{1.0,0.9,0.5,0.3}`；② 新增 `A_LIKE_LEADING_ANIMATION_LENGTH = 0.02` 与 `A_LIKE_LEADING_ALIGNMENT_THRESHOLD = 0.5`；③ `crossShapeExperimentLength(...)` 增加对齐度入参：`> 0.5` 返回 20ms，否则 `animationLength × 系数[rank]`；④ 类注释 / `jump` 注释 / 方法注释同步说明「20ms 正时长、只作用于非短跳分支」 | 用户认可的 VS Code 版 1.0.1 实际参数：`animationLength=0.1`、`shortAnimationLength=0.11`、rank 系数 `{1.0,0.72,0.42,0.22}`、`leadingSnapThreshold=0.5`、`snapAnimationLength=0.02`。旧 no-snap 公式在跨行 10 字符时让右侧两角一快一慢，实机视频表现为「右下角先到位、那一点拉伸很长」；A-like 让前缘成对拿 20ms |

对齐度**复用生产方法** `TrailCorner.calculateDirectionAlignment(...)`——它正是 `SpringTrail.assignRanks()` 给同一角点定 rank 时用的那一份，避免「rank 判据」与「吸附判据」两套算法打架。

`crossShapeExperiment == false` 时旧三档公式逐字保留；`trailSize` 不参与实验分支；`shortAnimationLength` / `slopeTicks` / `stretchRatioK` / `typingSmoothness` / `typingMaxChars` / `charsMoved` 判据全部未动。

### 2.2 探针（本轮）

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `tools/settings-probe/VAddSetting.java` | 重写同文件顶层类 `VCrossShapeExperiment`（25 → **33 条断言**）；新增反射驱动生产 `ui.SpringTrail` 的辅助方法（`setTarget→update→assignRanks→jump`），不复制 rank 排序/时长公式；新增 `Constructor` 导入 | 覆盖 A-1~A-6；旧 X-4c「四角四档不同」与新 X-6a「行为层零硬吸附」按 PROMPT §7 更新方向；新增「短跳分支零吸附」静态断言与「连续 f」行为断言 |

断言构成：X-1a~g（7）、X-2a/b（2）、X-3（1）、X-4a~h（8）、X-5a~c（3）、X-6a~d（4）、X-7a~h（8）= **33**。

本轮**没有**新增 `V*.java`（仍 118 个），**没有**改动其它探针契约常数（没有新增 State 字段或 UI 控件），`build-v-probes.ps1` 未改。

### 2.3 其它

| 文件 | 说明 |
|---|---|
| `docs/sessions/2026-11-06-a-like-hard-snap/REPORT.md` | 本报告 |
| `build/a-like-*.log` | 门禁/变异/打包日志（构建产物，未提交） |

## 3. 命令与关键输出

### 3.1 全量门禁（最终，串行）

```bash
R="D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/sleepy-swanson-ffc2cf"
pwsh -NoProfile -ExecutionPolicy Bypass -File "$R/tools/settings-probe/build-v-probes.ps1" -Repo "$R" -Libs "D:\libs" > "$R/build/a-like-final-gate.log" 2>&1
```

关键输出（原文）：

```text
VCrossShapeExperiment exit=0  PASS=33  FAIL=0
V-PROBES RESULT: ALL PASS
```

- 探针进程数：116（脚本 `$mains` 全量；非 informational 全部 exit=0）。
- 唯一 exit=1：`VRegr2 exit=1 PASS=13 FAIL=2`，两条为 R-5 / R-6（`typingSmoothness` 不参与 match/派生），`$informational = @('VOrder','VOrtho','VRegr2')` 按既有设计不计入通过门，本轮未动。
- 编译：`javac 源码树（37 个 .java）`、`javac V 探针（118 个）`。

### 3.2 D0

```bash
git -C "$R" diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

```text
D0_EXIT=0
```

### 3.3 打包（只打包、不安装）

首次直接在工作区长路径下调用时，Windows 命令行超限：

```text
javac.exe ... 文件名或扩展名太长
```

按 PROMPT §6 允许的方式，用短路径目录联接执行（**未修改脚本**；临时联接 `D:\wt-a-like` 已在打包完成后删除，未影响 worktree）：

```powershell
New-Item -ItemType Junction -Path 'D:\wt-a-like' -Target '<worktree>'
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\wt-a-like\build\offline-build.ps1" `
  -Version 0.9.136-a-like-snap `
  -OutName neovide-cursor-0.9.136-a-like-snap.jar `
  -BaseJar D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.14-conclusion.jar
```

关键输出：

```text
<version>0.9.136-a-like-snap</version>
<idea-version since-build="242" until-build="261.*" />
since-build = 242  OK
until-build = 261.*  OK
完成。安装前必须先关闭 IDEA（它会锁住 jar）。
```

产物校验：

```text
dist/neovide-cursor-0.9.136-a-like-snap.jar   249220 字节
SHA256 41d11aa64fb5b1a8fd9548b293f008360360962b4a98b230fa25d69405f8ad23
jar 内 TrailCorner.class SHA256 = build/classes/.../TrailCorner.class SHA256
  = 56cd5aecad4066af49eaed65a68ea9491e9119a8405a29e5164db3e89d304c2f
```

即：jar 里的 `TrailCorner` 与本次编译产物逐字节一致，不是上一版残留。

## 4. 变异测试（红/绿证据）

每次变异后由门禁脚本删除 `build/v2-probe/classes` 全量重编译；恢复源码后再跑全量。备份与逐文件 `diff -q` 均通过（`RESTORE_OK`）。

| 编号 | 变异 | 预期 | 实测红名单 | 结论 |
|---|---|---|---|---|
| M0（补充 RED 证据） | `A_LIKE_TRAIL_FACTORS → {1.0,0.9,0.5,0.3}`、阈值 `0.5 → 2.0`（等价于旧 no-snap 公式） | A-like 形状断言全红 | `VCrossShapeExperiment 28/5`：X-4c、X-4e、X-4f、X-4g、X-7h；总门禁 `FAILED（1 个探针未通过）` | 探针抓得住旧实现 |
| M1 | `State.crossShapeExperiment` 默认 `false → true` | 默认关断言红 | `VCrossShapeExperiment 27/6`（X-1c、X-7b/c/e/f/h）、`VDefaultFreeze 3/2`（F-1`漂移=[crossShapeExperiment: 冻结=false 实际=true]`、F-3）、`VHints 7/4`（H-4/5/6/9）、`VerifyPanel 32/2`（U20/U21）；`FAILED（4 个）` | 默认值被守住 |
| M2 | 在短跳分支插入 `if (calculateDirectionAlignment(...) > 0.5) animationLength = 0.02` | 单行/连续 f 断言红 | `VCrossShapeExperiment 30/3`（**X-5b** 实测 `[0.11, 0.02, 0.02, 0.0792]`、**X-5c** 关=false 开=false、**X-6c** 切片命中）、`VTypingSpan 7/2`（T-2a/T-4）、`VPerf 13/4`、`VPerf2 5/3`、`VPreviewRank 3/3`；`FAILED（5 个）` | 短跳零伤被守住；「连续 f」也守住了 |
| M3 | `AnimationPreview.pushTarget()` 里 `setCrossShapeExperiment(crossShapeExperiment)` → `false` | 预览同源断言红 | `VCrossShapeExperiment 32/1`：**X-7h** `开=[0.1,0,0,0.05] 关=[0.1,0,0,0.05]`；`FAILED（1 个）` | 预览是否真的消费开关被守住（X-7e 仍绿，说明只看 flag 不够，必须看时长） |
| 恢复后 | — | 全绿 | `VCrossShapeExperiment 33/0`、`ALL PASS` | — |

**关于「先 RED 再实现」**：本轮先把新探针写好，但在跑第一次门禁前已同步实现（严格意义上没有「实现前的一次 RED」）。**M0 补足了这段证据**：把生产退回旧公式后，新断言恰好 5 条红，说明这些断言不是照着新实现写的橡皮图章。首次门禁曾出现 6 条红，原因是探针驱动把起点写成 `(0,0)`、目标用 `X0,Y0`，运动变成对角线（`TR` 对齐度 0.09，不应吸附）；修正为统一起点后全绿 —— 这是**探针参数错误**，不是生产缺陷，已在日志 `a-like-gate-run1.log` 中留痕。

## 5. 置灰清单（实测）

| 控件 | 关闭时 | 开启时 | 值是否保持 |
|---|---|---|---|
| 拖尾密度 `trailSize` | enabled=true | **enabled=false** | 保持；X-7d / X-7g 断言不变 |
| 跨行动画时长 `animationLength` | enabled=true | **enabled=true** | 保持 |

**为什么 `animationLength` 不置灰**：它仍是本轮公式的时间基准 —— 非吸附角 = `animationLength × {1.0,0.72,0.42,0.22}`，20ms 只作用于吸附角。置灰会让用户无法调节后缘长度，且设置页提示文案原文已写明「跨行动画时长仍作为时间基准」。此决定与 PROMPT §8「是否置灰由实现决定并写清理由」一致。

## 6. 缎带预设 + 鼠标实验：只记录、不互斥

- 未做任何互斥逻辑；`crossShapeExperiment` 仍是设备级 `runtimeSwitch=true`，不在 `AnimPreset` / `PRESET_FIELDS` / `applyAnimPresetFields` / `matchAnimPreset` 中（X-1b/d/e、X-1f：切任何动画预设都不改该字段）。
- 组合后的实际行为（记录，不作裁决）：实验开启时，即使当前是缎带预设（`trailSize=1`），跨行路径也由 A-like 接管，`trailSize` 不参与；关闭实验后恢复预设原有三档行为。
- 用户要求「只记录」已满足。

## 7. 未验证项

1. **未安装**：jar 只在 `dist/`，IDEA 未被替换、未启动。
2. **未实机 A/B**：没有在 IDEA 2026.1 里对比 A 版与 A-like 的观感；本报告不判断「更像 A」「更顺」。
3. **鼠标拖动路径未用真实鼠标验证**：行为上它与键盘大跨度走同一非短跳分支（`charsMoved > typingMaxChars` 或跨行），但本轮只有探针级证据，没有真机拖选录像。
4. **预览只验时长，未验截图**：X-7h 断言四角时长同源；没有对预览画面做像素级/截图对比。
5. **其它 IDE 未验**：只在门禁的 headless 环境与编译层验证；未在 PyCharm 2025.2 冒烟。
6. **已知影响面（非本轮引入）**：同行大跨度（End/Home、同行鼠标拖选 >3 字符）也会走该分支并受实验影响 —— PROMPT A-7 允许；低帧率下 `charsMoved` 累计 >3 被误判为跨行的问题仍存在（Step B 已撤回，判据未动），实验开启时这类帧会拿到 A-like 形状。

## 8. 待用户裁决（不阻塞交付）

1. **观感裁决**：明天实机 A/B（A 版 1.0.1 vs 本 jar），决定是否采用 A-like、是否需要调 `animationLength` 或阈值。
2. **提示文案**：设置页提示仍写「独立四档形状接管」，未提「前缘成对 20ms」。资源文件 `src/main/resources/messages/*` 不在本轮白名单，未改；是否补文案需下一轮授权。
3. **`animationLength` 是否置灰**：本轮按「仍是时间基准」选择**不置灰**；若用户希望开启实验后也锁死时间，需要下一轮改面板。
4. **是否把实验代码转正/合并**：本轮按 PROMPT 不提交、不 push、不建 PR；改动仍在工作区未提交状态。

## 9. 与 2026-11-05 报告的关系

- 同一开关：`crossShapeExperiment`（设备级、默认关、可持久化、面板复选框、开启置灰 `trailSize`、仅 `crossPreview` 消费）。
- 本轮只替换「开关开启后跨行分支的时长公式」：`{1.0,0.9,0.5,0.3}` 四档 → A-like `{1.0,0.72,0.42,0.22}` + 对齐度 > 0.5 的前缘 20ms。
- 2026-11-05 报告中的持久化、置灰、预览接线结论仍然有效；本报告不重复其证据，只补充本轮新增与变更。
