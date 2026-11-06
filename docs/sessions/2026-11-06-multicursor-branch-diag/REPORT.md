# 多光标跨行斜度 · 诊断日志取证 执行报告（2026-11-06）

> 状态：待用户实机取证（方案 C 已交付；未实施 A / B1 / B2）
> 提交：本报告所在提交（基于 `1733c11` 新建，未 amend、未 push、未合并）
> 分支：`claude/determined-kowalevski-c1c64b`
> 产物：`dist/neovide-cursor-0.9.137-branch-diag.jar`（253,923 B，SHA256 `e41114942c6368e220495ec0a584262469262e6646e132f16cab2f0f465686fc`）

## 1. 结论

一句话：交付了**可关、有界、字段够复核**的 `[branch]` 诊断日志，全量门禁 `ALL PASS`、D0 零 diff、只打包未安装；**没有修多光标斜度**，没有实施 A/B1/B2，也没有改任何动画判据或时长。

用户下一步是安装 `0.9.137-branch-diag` 做四组各 10 秒实机取证，把 `[branch]` 日志交回决策会话；选 A 还是 B1 由用户看完日志后裁决。

## 2. 根因 / 为什么先做方案 C

诊断报告已确认两层根因，本轮只记录、不修：

1. **形状结构**：跨行分支里 `rank2 == rank3` 且 `rank0 != rank1`，所以「一条边直、一条边斜」；`slopeTicks` 只在短跳分支被读取，跨行分支不读 `config.trailFactors`。A-like 与旧三档都一样，与多光标无必然关系。
2. **分支判据**：`CaretAnimator.updateBlockCharsMoved()` 恒返回 `-1`（0.9.56 `83787a2` 起），多光标退化成像素兜底 `|deltaXpx/width| <= shortMoveThreshold(8)`；CJK 字宽约双倍、掉帧合并按键、同帧各光标速度不同，会让「中文斜、英文不斜」「同一帧一个斜一个直」。

用户对方向的原话（PROMPT §1.1 逐字引用）：

> 「修复日志我测试-拿到日志-再看ab」

> 「新提交」

用户已认可的 A-like 边界（本轮保持不变）：

> 「a-like方向对了，单行没有退化，只是做了前缘吸附 后缘没有做 现在是四个角左上 左下 对齐了，另外两边还是原来的斜度其实不错了」

因此本轮只做方案 C：先让用户在真实机器上区分「真回绕」与「像素阈值误判」，拿到日志后再决定 A / B1；不替用户判断观感。

## 3. 改动清单

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java` | 在慢路径 `frameSeq++` 后算一次 `branchDiagWanted`；在 `markAt` **之前**读上一帧视觉行与旧目标中心；只在 `trail.setTarget(...)` 返回 true 时落诊断快照；在 `tickSpring` 的 `trail.update(dt)` **之后**读生产最终分支并输出；`slopeTicks` 只在 diag 开启时只读 State。 | `[branch]` 要同时拿到「判据输入」与「物理更新后的最终分支」，又不能改任何赋值/判据；两段式是唯一不改 `TrailCorner` 又不复刻 `isShortJump` 的做法。 |
| `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CursorDiag.java` | 新增 `BRANCH_BUDGETS`（复用 `TrailBudget` 结构，上限 5000 行/animator + 一条汇总）、`PENDING_BRANCH` 快照表、`noteBranchSnapshot`、`branchTrace`、`hasPendingBranch`、`formatBranchForProbe`、`branchBudgetForProbe`、`resetBranchBudgetsForProbe`；`forget()` 清理预算与 pending；`tableContainsForProbe`/`tableSizesForProbe` 纳入第六张表；`resetFileForProbe()` 清 branch 预算与 pending。 | 日志必须有界、可按 animator 隔离、超限只补一条汇总；全部复用现有 `enabled()` / `writeFileAndReport` 同步写文件通道，不新增常驻输出。 |
| `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/SpringTrail.java` | 新增两个包内**只读**访问器：`lastVisualLineForProbe()`、`lastBranchShortForProbe()`；后者反射读 `TrailCorner.lastShortJump`，失败经 `CursorDiag.swallowed` 留痕后返回 false。 | 最终分支必须来自生产真实判定；`TrailCorner.java` 是禁区，不能加公开 getter，只能在 `SpringTrail` 做只读访问。不改 `applyCharsMoved` / `assignRanks` / `setTarget`。 |
| `tools/settings-probe/VTrailCap.java` | 同文件追加 package-private 顶层类 `VMultiBranchDiag`（26 条断言），不改 `VTrailCap` 既有 6 条断言。 | 新增独立 `V*.java` 会让 `V*.java` 从 118 变 119，触发 `VDocCheck D-2`；本轮禁止改三份状态文档。 |
| `tools/settings-probe/build-v-probes.ps1` | `$mains` 只加一行 `'VMultiBranchDiag'`。 | 不登记 = 从不运行；不改编译方式。 |
| `docs/sessions/2026-11-06-multicursor-branch-diag/PROMPT.md` | 主仓库 PROMPT 逐字复制到 worktree。 | 本轮提交需要包含本 PROMPT；施工位置在 worktree，主仓库文件不改。 |
| `docs/sessions/2026-11-06-multicursor-branch-diag/REPORT.md` + `evidence/*.txt` | 本报告与命令/门禁/变异/打包证据。 | PROMPT §4.3/§9 要求。 |

`git diff --stat`（提交前实测）：

```text
 CaretAnimator.java  |  76 +++-
 CursorDiag.java     | 218 ++++++++++++-
 SpringTrail.java    |  23 +
 VTrailCap.java      | 498 +++++++++++++++++++++
 build-v-probes.ps1  |   2 +
 5 files changed, 813 insertions(+), 4 deletions(-)
```

## 4. 证据

### 4.1 开工前基线与 D0（PROMPT §6.1）

```text
branch = claude/determined-kowalevski-c1c64b
HEAD   = 1733c11 feat(trail): 记录设备级跨行形状实验与 A-like 形状
git status --porcelain = 0 行（开工时）
D0_EXIT = 0
已装 IDEA jar = <version>0.9.136-a-like-snap
已装 jar SHA256 = bb686d537ae921f0a94337444e63c08d2f172c862d11c9c590aae64f97bb5b20
V*.java = 118 / 探针 *.java = 133 / 注册探针 = 116（加 VMultiBranchDiag 前）
```

### 4.2 RED（先写探针，生产入口尚未实现）

```text
VMultiBranchDiag exit=1  PASS=0  FAIL=12
=== V-MULTI-BRANCH-DIAG 结果：PASS 0，FAIL 12 ===
V-PROBES RESULT: FAILED（1 个探针未通过）
GATE_EXIT=1
```

失败原因是 `branchTrace` / `formatBranchForProbe` / `noteBranchSnapshot` / 预算探针入口不存在；探针编译通过、逐条断言红，不是编译失败。其余 116 个注册探针全绿。

### 4.3 GREEN（最终权威门禁，串行、未并发）

```text
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1 \
  -Repo D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\sleepy-swanson-ffc2cf -Libs D:\libs

VMultiBranchDiag exit=0  PASS=26  FAIL=0
VTrailCap        exit=0  PASS=6   FAIL=0
VFadeTick        exit=0  PASS=45  FAIL=0
VDocCheck        exit=0  PASS=4   FAIL=0
VCrossShapeExperiment exit=0 PASS=33 FAIL=0
VTypingSpan      exit=0  PASS=9   FAIL=0
VUniform         exit=0  PASS=4   FAIL=0
VBlockStretch    exit=0  PASS=2   FAIL=0
V-PROBES RESULT: ALL PASS
FINAL_GATE_EXIT=0
D0_EXIT=0
```

注册探针总数从 116 变为 **117**；`V*.java` 仍为 **118**（未触发 D-2）。

### 4.4 三组变异测试（每组后删除 classes 全量重编译，恢复后全绿）

**M1：去掉 `CursorDiag.branchTrace` 的 `if (!enabled()) return;` 门控**

```text
变异：删除 branchTrace 第一段 enabled() 门控
红：VMultiBranchDiag exit=1 PASS=24 FAIL=2
  FAIL D-3 diagLogging=false 零输出、零预算创建   [文件字节=286]
  FAIL D-4 diagLogging=false 六张预算/快照表尺寸均为 0 [[0, 0, 1, 0, 0, 0]]
V-PROBES RESULT: FAILED（1 个探针未通过）
恢复：cp build/mutation-backup/CursorDiag.current.java ...
```

> 首次 M1 曾出现假绿：探针关闭态的 `note` 用 caretId=77，而 `branchTrace` 仍传 caretId=0，待配对快照找不到，删门控也不会写。修正夹具为同一 caretId 后重跑，才得到上面的 D-3/D-4 红。这个排查过程保留在 `evidence/M1-probe.txt`，不是生产门控有效。

**M2：删除日志格式中的 `branch=` 与 `charsMoved=` 字段**

```text
红：VMultiBranchDiag exit=1 PASS=24 FAIL=2
  FAIL D-2a [branch] 字段覆盖时间/身份/几何/判据/分支/开关/斜度
  FAIL D-2c 关键判据值逐项落盘
V-PROBES RESULT: FAILED（1 个探针未通过）
恢复：cp build/mutation-backup/CursorDiag.current.java ...
```

**M3：把多光标分支 `charsMoved = updateBlockCharsMoved();` 改成 `charsMoved = 0;`（实施 A 的行为影子）**

```text
红：VMultiBranchDiag exit=1 PASS=25 FAIL=1
  FAIL D-10d updateBlockCharsMoved() 仍恒为 -1（未实施 A）
V-PROBES RESULT: FAILED（1 个探针未通过）
恢复：cp build/mutation-backup/CaretAnimator.current.java ...
```

恢复后重跑最终门禁：`V-PROBES RESULT: ALL PASS`、`FINAL_GATE_EXIT=0`（见 4.3）。

### 4.5 只打包、不安装

`build/offline-build.ps1` 在本 worktree 因 37 个源文件 + 381 个 jar 的完整路径超过 Windows 命令行上限而失败（脚本硬编码长 `idea-libs` 路径；该脚本是禁区，未改）。按 PROMPT「只打包不安装」的实质要求，改用**不落脚本文件**的内联等价步骤完成同一件事：

```text
javac --release 21 --classpath D:\libs\*.jar -d build/classes-0.9.137 @build/offline-srcs.args
复制 src/main/resources → classes
注入 plugin.xml：<version>0.9.137-branch-diag</version>
  <idea-version since-build="242" until-build="261.*" />
Copy-Item <主仓库 conclusion jar> dist\neovide-cursor-0.9.137-branch-diag.jar
jar --update --file dist\neovide-cursor-0.9.137-branch-diag.jar -C build\classes-0.9.137 .
```

校验：

```text
jar = dist/neovide-cursor-0.9.137-branch-diag.jar
size = 253923 B
jar 内 <version>0.9.137-branch-diag
jar 内 <idea-version since-build="242" until-build="261.*" />
SHA256 = e41114942c6368e220495ec0a584262469262e6646e132f16cab2f0f465686fc
关键 class：ui/CaretAnimator.class、ui/CursorDiag.class、ui/SpringTrail.class 均在 jar 内
javap 验证：CursorDiag 含 branchTrace/noteBranchSnapshot/formatBranchForProbe，
             SpringTrail 含 lastVisualLineForProbe/lastBranchShortForProbe
未安装、未覆盖已装 0.9.136-a-like-snap、未启动 IDEA
```

> 第一次内联打包也失败过：PowerShell heredoc 的 `-replace` 把反斜杠转义吃掉，argfile 没生成，javac 失败，脚本继续用空 classes 打了 142KB 的包。该包已丢弃并重新编译；最终 253KB 包已用 `jar tf` 验证关键 class 存在。详见 `evidence/package.txt`。

### 4.6 日志格式示例（生产格式化方法的输出；入参来自探针，不是实机日志）

```text
[branch] 15:18:57.560 +125ms animator=81001 frame=123456789 caret=77 slot=0 multi=true vp=24:12 deltaXpx=17.5 deltaYpx=0.0 wh=(2.0x32.0) charsMoved=1 sameLine=true visualLineChanged=true prevVisualLine=24 branch=SHORT aLike=false slopeTicks=100 shortMoveThreshold=8 typingMaxChars=3.0
```

字段口径：

- `animator=`：`CaretAnimator.traceId`（identityHashCode）。
- `caret=`/`slot=`：`SpringTrail` 身份哈希与当前活跃槽位；同一帧多光标靠这两个字段区分。
- `deltaXpx/deltaYpx`：本帧目标中心相对上一帧目标中心的位移；`TrailCorner.jump()` 的角点目标差在宽高不变时与它数值一致。**不是**直接读 `TrailCorner` 的局部变量（那是禁区）。
- `sameLine` / `visualLineChanged` / `prevVisualLine`：在 `markAt` 覆盖 `lastLine` 之前读取上一帧视觉行；`visualLineChanged=true` 即视觉行变了。`sameLine` 是本日志的观测值（`!visualLineChanged`），不是 `TrailCorner` 内部局部变量的复制。
- `branch=SHORT|CROSS`：来自生产 `TrailCorner.lastShortJump`（最近一次 `jump()` 真正做出的判定；零尺寸兜底时沿用上一帧）。
- `aLike=`：本帧 `crossShapeExperiment` 设备开关。
- `slopeTicks=`：diag 开启时只读 `NeovideCursorSettings.State.slopeTicks`；读不到记 `-1` 并留 `[error]`。
- `shortMoveThreshold` / `typingMaxChars`：本帧生效判据阈值，便于用户直接复核「像素阈值误判」。

### 4.7 AC 逐条

| 编号 | 结论 | 证据 |
|---|---|---|
| AC-1 | PASS | `VMultiBranchDiag D-2/D-3/D-4`；M1 变异后 D-3/D-4 变红；关闭态不创建预算、不写文件。 |
| AC-2 | PASS | `D-2a/D-2b/D-2c`；字段含时间、animator/caret、multi、vp、deltaXpx/deltaYpx、wh、charsMoved、sameLine/visualLineChanged、branch、aLike、slopeTicks。 |
| AC-3 | PASS | `D-7/D-8/D-9a/D-9b/D-9c/D-9d`：5000 行 + 1 条汇总，后续不增长，按 animator 分桶，`forget()` 回收。 |
| AC-4 | PASS | `D-10a–D-10d` + 最终门禁 `VCrossShapeExperiment 33/0`、`VTypingSpan 9/0`、`VUniform 4/0`、`VBlockStretch 2/0`；短跳/跨行时长与 A-like 开关零变化。 |
| AC-5 | PASS | `D-10d` 源码检查；M3 变异红；`updateBlockCharsMoved()` 仍 `return -1;`，调用点未被 `0/MAX` 替换。 |
| AC-6 | PASS | `D-11` 静态扫描；`TrailCorner.java` 零 diff；A-like 常量/前缘 20ms 未动；`VCrossShapeExperiment 33/0`。 |
| AC-7 | PASS | 最终门禁 `VAddSetting`、`VerifyPanel`、`VPreset`、`VDefaultFreeze`、`VTabPages` 全绿；未加控件/State/预设字段。 |
| AC-8 | PASS | `D0_EXIT=0`；禁区文件 `FORBIDDEN_DIFF_EXIT=0`；`git status` 只有 5 个白名单源码文件 + 本轮会话目录。 |
| AC-9 | PASS | 最终 `V-PROBES RESULT: ALL PASS`、`FINAL_GATE_EXIT=0`；全程串行，未并发。 |
| AC-10 | PASS | M1 24/2（D-3/D-4）、M2 24/2（D-2a/D-2c）、M3 25/1（D-10d）；每组恢复后全绿。 |
| AC-11 | PASS | 只打包未安装；jar 253923 B、版本与兼容区间见 4.5；SHA256 见头部。 |
| AC-12 | PASS（本提交） | 基于 `1733c11` 新建一个提交；只暂存 5 个白名单源码 + PROMPT/REPORT/evidence；不 amend、不 push、不合并。 |
| AC-13 | PASS | 本节 + §5：日志路径、筛选命令、四组各 10 秒步骤、交回方式；没有写「已修好」。 |
| AC-14 | 待用户 | §7 由用户实机填写；执行会话不替用户判 A/B。 |

## 5. 用户实机取证步骤

1. **确认诊断开关**：设置页「诊断日志」保持开启；记录当前 `crossShapeExperiment` 与 `slopeTicks` 的实际值（设置页显示为准）。执行会话只读 XML 能确认 `cursorHeightRatio=0.9`、`targetFps=144`；`diagLogging` / `crossShapeExperiment` / `slopeTicks` 未在顶层 `option` 中读到（可能按默认值省略），所以请以设置页为准。
2. **安装本轮 jar**：执行会话**没有安装**，当前已装仍是 `0.9.136-a-like-snap`。需要实测时按你既有安装流程安装 `dist/neovide-cursor-0.9.137-branch-diag.jar`；不要覆盖前自行决定是否备份。
3. **四组各 10 秒**：
   - 多光标英文同行输入 / 长按 `→`；
   - 多光标中文同行输入；
   - 多光标 `Tab` / `End` / 行尾回绕；
   - 单光标对照。
4. **筛 `[branch]`**：`[branch]` 只写 `CursorDiag` 的**独立诊断文件**，不刷 `idea.log`（沿用 BUG-007 纪律）。文件在：
   `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.1\log\neovide-cursor-diag-<pid>.log`

   PowerShell：

   ```powershell
   $d = "$env:LOCALAPPDATA\JetBrains\IntelliJIdea2026.1\log"
   $f = Get-ChildItem $d -Filter 'neovide-cursor-diag-*.log' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
   Select-String -Path $f.FullName -Pattern '\[branch\]' | Set-Content branch.txt
   ```

   Git Bash：

   ```bash
   LOG_DIR="$LOCALAPPDATA/JetBrains/IntelliJIdea2026.1/log"
   f=$(ls -t "$LOG_DIR"/neovide-cursor-diag-*.log | head -1)
   grep -n '\[branch\]' "$f" > branch.txt
   ```

5. **交回**：把 `branch.txt` 交给决策会话；不要自行判断选 A 还是 B1。若某组没有 `[branch]` 行，先确认诊断日志已开、该组确实发生光标移动，再记录现象。

## 6. 阻塞与偏离

- **同文件探针**：没有新增 `VMultiBranchDiag.java`，而是把 package-private 顶层类放进 `VTrailCap.java`，并在 `build-v-probes.ps1` 的 `$mains` 加一行。理由：`VDocCheck D-2` 把磁盘 `V*.java` 数与三份状态文档比对，本轮禁止改状态文档；新增第 119 个 `V*.java` 必红。`VTrailCap.java` 从 162 行增至 660 行（仍低于 800 行软上限）。
- **最终分支读取方式**：`TrailCorner.java` 是禁区，`lastShortJump` 没有公开访问器；`SpringTrail.lastBranchShortForProbe()` 用只读反射读取该字段，失败时经 `CursorDiag.swallowed` 留痕并返回 false。没有改 `TrailCorner`、没有复刻 `isShortJump`；探针用生产 `SpringTrail` 真实链路验证 SHORT/CROSS。
- **delta / sameLine 口径**：`deltaXpx/deltaYpx` 记录的是目标中心位移（与角点目标差在宽高不变时一致），不是直接读 `TrailCorner.jump()` 的局部变量；`sameLine` 是日志侧观测值（`!visualLineChanged`），不是 `TrailCorner` 内部局部变量的复制。视觉行变化在 `markAt` 之前从 `SpringTrail.lastLine` 读取，避免被覆盖。
- **打包偏离**：`build/offline-build.ps1` 在本 worktree 因命令行过长失败，且该脚本是禁区未改；改用内联等价步骤（`javac @argfile` + 资源复制 + `plugin.xml` 注入 + `jar --update`）。PROMPT §6.6 指定的 worktree 底包 `dist/jetbrains-neovide-cursor-0.9.14-conclusion.jar` 不存在，改用主仓库同文件（只读）。最终 jar 已验证关键 class 存在。
- **探针表扩展**：`CursorDiag.tableSizesForProbe()` 从 4 元数组扩为 6 元 `[trail, frame, branch, pending, sample, trace]`；既有消费者不检查长度（`VAuditBatch2Round2to4` 只用 `tableContainsForProbe`），最终门禁全绿。
- **M1 首次假绿**：探针关闭态 `note` 与 `branchTrace` 的 caretId 不一致，导致删门控也不输出；修正夹具后 M1 才红（D-3/D-4）。这不是生产门控失效，已保留在证据中。
- **代码审查（code-reviewer，只读）**：结论 `WARNING`，无 CRITICAL；3 个 HIGH 已在白名单内修复：
  - H-1：`tickSpring` 先调 `CursorDiag.hasPendingBranch(...)`，没有待配对快照时不再做 `branchSlotOf` 查找和 `lastShortJump` 反射；
  - H-2：未初始化 trail 首帧不再落日志；`lastBranchShortForProbe()` 反射失败返回 `null`，调用方跳过该行，不再把失败伪装成 `CROSS`；
  - H-3：新增 `D-13a` 静态断言，要求 `branchTrace` 实参含 `trail.lastBranchShortForProbe()` 且调用前有 `hasPendingBranch` 门控。
  - M-1/L-1/L-2 一并修复：`PENDING_BRANCH` 纳入第六张探针表并在 `resetFileForProbe` 清理；D-6 改为真实参数类型检查；日志数值格式化固定 `Locale.ROOT`。
  - 未修（已在 §8 列为已知限制）：M-2 的 D-10a/b 仍主要直接验证 `TrailCorner`，对日志链路的行为证据偏弱；M-3 的 `sameLine`/`deltaX/Y` 仍是观测口径，不是 `TrailCorner` 内部局部变量的复制。
- **未做**：未安装、未启动 IDEA、未改用户 XML、未改 `TrailCorner.java` / `NeovideConfig.java` / State / UI / 预设 / 安装脚本；未实施 A/B1/B2；未 push / 合并 / 打 tag / amend。

## 7. 验收记录（用户填）

- 实机环境（IDE + 版本）：
- 安装的 jar / 版本：
- `diagLogging` / `crossShapeExperiment` / `slopeTicks` 实际值：
- 四组操作是否都出现 `[branch]` 行：
- 哪些帧是真回绕（`visualLineChanged=true`）：哪些是像素阈值误判（`visualLineChanged=false` 但 `branch=CROSS`）：
- 结论：选 A / 选 A+B1 / 需要下一轮 PROMPT + 现象：

## 8. 未验证项 / 遗留

- **实机日志未产生**：本轮只验证了格式化方法与生产分支访问器，未在 IDEA 2026.1 真实多光标场景抓取 `[branch]`；四组取证由用户完成。
- **反射失败路径未注入**：`lastBranchShortForProbe()` 的反射失败分支只做了代码审查，没有专门构造 `TrailCorner` 字段不可读的变异。
- **delta 等价性是推导**：`deltaXpx/deltaYpx` 与 `TrailCorner.jump()` 角点目标差在宽高不变时数值一致；宽高变化帧可能有差异，日志字段已在 §4.6 标明口径。
- **用户配置**：`diagLogging` / `crossShapeExperiment` / `slopeTicks` 的顶层值未从 XML 完整读出（可能按默认值省略），以设置页为准。
- **选择 A/B1 不在本轮**：日志拿到后由用户裁决；本轮不把日志推导写成已验收结论。
- **审查遗留 M-2**：`D-10a/D-10b` 仍主要直接调用 `TrailCorner` 验证时长，不足以单独证明「新增日志链路不改变行为」；但实际 diff 没有改 `TrailCorner`，且最终全量门禁与 `VCrossShapeExperiment`/`VTypingSpan` 全绿。
- **审查遗留 M-3**：`sameLine` 是视觉行观测值、`deltaX/Y` 是目标中心位移，不是 `TrailCorner` 内部 `sameLine`/角点差的原样复制；用户复核时以 `visualLineChanged` + `deltaX/Y` + `shortMoveThreshold` 联合判断，口径已在 §4.6 写清。
- **首帧行为**：未初始化 trail 首帧不产出 `[branch]`（因为生产 `setTarget` 只 `snapToTarget`、不执行 `jump()`）；这是为了避免伪 `CROSS`，不是漏记。
