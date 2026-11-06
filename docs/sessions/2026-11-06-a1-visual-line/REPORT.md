# A1 视觉行分支统一 执行报告（2026-11-06）

> 状态：待验收
> 提交：本提交（`git log -1` 查看；按项目纪律不写死 hash）
> 分支：`claude/a1-visual-line-05e7f0`（基线 `46d8502`）
> 产物：`dist/neovide-cursor-0.9.138-a1.jar`
> SHA256：`b81ec276b403eb81d2caaf68393daac492f0245c6542ef5bef99ede0252688ff`

## 1. 结论

A1 已实现：单光标与多光标统一为「**同一屏幕视觉行 → SHORT，视觉行变化 → CROSS**」，
旧的字符数判据（`updateCharsMoved` / `lastCaretOffset`）与多光标像素兜底
（`updateBlockCharsMoved` 恒 `-1`）已从生产分支删除；`AnimationPreview` 改为
`INLINE → 0` / `CROSS → Integer.MAX_VALUE`，与真机共用
`SpringTrail.semanticCharsMoved(...)` 这一份判据。

**本文不评价实机手感。** 同行大跳（Tab / End / Ctrl+方向 / 鼠标 / 按词移动）
由变化前的前缘 0ms 跨行形状改为短跳后的观感，必须由用户按 §7 实机验收。

## 2. 根因

依据 `docs/sessions/2026-11-06-multicursor-branch-diag/REPORT.md` §9 的 320 条实机
`[branch]` 日志：

| 分组 | 记录 | SHORT | CROSS | 同视觉行 CROSS | 真换行 CROSS |
|---|---:|---:|---:|---:|---:|
| 多光标 `multi=true` | 296 | 196 | 100 | **93** | 7 |
| 单光标 `multi=false` | 24 | 18 | 6 | 0 | 6 |
| 合计 | 320 | 214 | 106 | 93 | 13 |

- 多光标 296 条全部 `charsMoved=-1`，确认走 `TrailCorner.jump()` 的 16px 像素兜底；
  100 条 CROSS 里 93 条是同一视觉行误判，其中 49 条只是 `dx=17px` 刚过阈值。
- 单光标 24 条符合「同行短跳 / 换行跨行」，无反例。
- 7 帧同帧多光标分支混合，其中 5 帧严格同向、同视觉行（A1 后统一为 SHORT）；
  2 帧含真实换行（A1 后仍混合，语义正确）。
- 全部 320 条 `aLike=false`、`slopeTicks=0`；A-like=true / slope=100 样本不足，
  因此本轮**不做 B1**，只统一分支选择。

## 3. 改动清单

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `src/main/java/.../ui/CaretAnimator.java` | 删除 `updateBlockCharsMoved()`；在 `markAt` 前读 `trail.lastVisualLine()` 与 `isInitialized()`；单/多光标统一 `SpringTrail.semanticCharsMoved(...)` 后 `applyCharsMoved(semanticChars)`；目标未变的分支由 `-1` 改 `0` | 一条规则替换两套判据；`-1` 清零会让像素兜底仍可达 |
| `src/main/java/.../ui/SpringTrail.java` | 新增 `lastVisualLine()` 与唯一判据 `static semanticCharsMoved(boolean, int, int)`；删除 `updateCharsMoved(int)` 与 `lastCaretOffset`；`lastVisualLineForProbe()` 改为委托 | 判据单一来源，生产 / 预览 / 探针共用；旧字符数判据无生产消费者（grep 确认） |
| `src/main/java/.../ui/settings/AnimationPreview.java` | `pendingCharsMoved` 初值与 INLINE 由 `1` 改 `0`；`CROSS_CHARS_MOVED` 由 `99` 改 `Integer.MAX_VALUE`；同步注释 | 预览必须与真机传同一组语义值（AC-6） |
| `src/main/java/.../core/TrailCorner.java` | **未改** | A1 传 `0` / `MAX` 后：同视觉行 `deltaYpx≈0` 必过 `sameLine`；视觉行变化 `MAX <= typingMaxChars` 恒 false。像素 `sameLine` 只对真实纵向位移生效，不需要额外改判据 |
| `tools/settings-probe/VTrailCap.java` | `VMultiBranchDiag` 新增 A1-1..A1-8（真值表 / dx=1..1000 / 上下回绕 / 每 trail 独立 / 5 光标同帧 / 旧判据删除 / 接线 / `typingMaxChars` 保留）；`D-10d` 改为 A1 接线断言；`D-11` 改为只禁 B1/B2，并新增 `D-11a` 正向接线 | 契约变更，保留断言编号与意图，不删断言 |
| `tools/settings-probe/VMulti.java` | M-2 从 `updateCharsMoved` 序列改为 `lastVisualLine` + 生产语义的逐光标独立测试 | 旧方法已删；保留「每光标独立、不串扰」的测试意图 |
| `tools/settings-probe/VerifyApplyConfig.java` | 删除对已删字段 `lastCaretOffset` 的反射注入，保留 `initialized` 注入 | 字段已删；该注入对被测的 applyConfig 链路无作用 |
| `tools/settings-probe/VPreviewBranch.java` | 单行输入 `1→0`、跨行 `99→MAX`；B-7 改为断言两个常量；新增 B-8 预览常量与生产语义逐位同源 | 预览探针必须锁 A1 语义，不再接受「大于 typingMaxChars 即可」的近似值 |
| `tools/settings-probe/VTypingSpan.java` | 仅顶部 javadoc 增加 A1 说明；T-1..T-8 断言一字未改 | `TrailCorner` 未改，T-8 的 `sameLine` 契约不受 A1 影响 |

**旧判据处理结论：删除，不保留诊断。** `grep` 确认 `updateBlockCharsMoved()` /
`updateCharsMoved()` / `lastCaretOffset` 只有 `CaretAnimator` 分支与三个探针在读；
删除后 `[branch]` 日志的 `charsMoved` 将显示 `0` / `2147483647`，仍可诊断，
但不再有任何生产分支读取字符数。

## 4. 证据

### 4.1 基线（`46d8502`，未改任何文件）

```text
V-PROBES RESULT: ALL PASS
```

完整输出：`evidence/BASELINE-gate.txt`。

### 4.2 TDD RED（只加探针断言、生产代码仍为基线）

```text
VMultiBranchDiag  exit=1  PASS=25  FAIL=2
  FAIL D-10d A1 分支使用 0/MAX，旧 updateBlockCharsMoved 不再被生产分支调用
  FAIL D-11a A1 分支已接线（CaretAnimator 调 semanticCharsMoved，旧判据零引用）
VPreviewBranch    exit=1  PASS=6   FAIL=1
  FAIL B-7 预览跨行常量 = Integer.MAX_VALUE、单行 = 0（与 A1 同源）
```

完整输出：`evidence/RED-gate.txt`。

### 4.3 GREEN（实现后）

```text
V-PROBES RESULT: ALL PASS
VMultiBranchDiag      exit=0  PASS=35  FAIL=0
VPreviewBranch        exit=0  PASS=8   FAIL=0
VMulti                exit=0  PASS=7   FAIL=0
VerifyApplyConfig     exit=0  PASS=26  FAIL=0
VTypingSpan           exit=0  PASS=9   FAIL=0
VI18n                 exit=0  PASS=13  FAIL=0
```

（`VRegr2` 的 R-5 / R-6 是基线已有的 informational，不计入失败门；下同。）

### 4.4 三组变异（先删 `build/v2-probe/classes` 全量重编译）

| 变异 | 实现 | 红名单 | 结论 |
|---|---|---|---|
| M1 删视觉行判断（全 0） | `semanticCharsMoved` 恒 `return 0` | `A1-1`、`A1-4`、`VMulti M-2`、`VPreviewBranch B-8` | 抓住 |
| M2 全 MAX | `semanticCharsMoved` 恒 `return Integer.MAX_VALUE` | `A1-1`、`A1-4`、`A1-5`、`VMulti M-2`、`VPreviewBranch B-8` | 抓住 |
| M3 单光标仍走字符数 | 单光标同行传 `4`（> `typingMaxChars=3`） | `A1-7`、`D-10d`、`D-11a` | 抓住（接线断言） |

M1 / M2 走行为链（生产语义 → `TrailCorner.jump()` → `lastBranchShortForProbe`）；
M3 走源码接线断言（行为探针不驱动 `CaretAnimator`，见 §5）。
完整输出：`evidence/M1-gate.txt` / `M2-gate.txt` / `M3-gate.txt`。

恢复后终检（同一份文件，SHA256 与本 worktree 8/8 一致）：

```text
V-PROBES RESULT: ALL PASS（117 个探针行）
VMultiBranchDiag      exit=0  PASS=35  FAIL=0
VPreviewBranch        exit=0  PASS=8   FAIL=0
VMulti                exit=0  PASS=7   FAIL=0
VerifyApplyConfig     exit=0  PASS=26  FAIL=0
VTypingSpan           exit=0  PASS=9   FAIL=0
VI18n                 exit=0  PASS=13  FAIL=0
D0_EXIT=0
FORBIDDEN_DIFF_EXIT=0
```

完整输出：`evidence/FINAL-gate.txt`。

### 4.5 D0 与禁区

```text
D0_EXIT=0
FORBIDDEN_DIFF_EXIT=0
```

改动仅落在 3 个生产文件 + 5 个探针文件 + 本报告 / evidence；
`NeovideConfig.java`、`CursorPresets.java`、`SettingsField.java`、
`NeovideCursorSettings.java`、`NeovideCursorConfigurable.java`、`build/*.ps1`、
既有 docs 零 diff。

### 4.6 预览同源

- `VPreviewBranch` 8/8：单行 0 与跨行 MAX 走不同分支；B-7 锁常量；
  B-8 用生产 `SpringTrail.semanticCharsMoved(...)` 逐位比对预览常量。
- `VPreviewParity` 12/12（未改，仍绿）：预览与生产收到的参数仍同一链路。

### 4.7 打包（只打包，不安装）

```text
dist/neovide-cursor-0.9.138-a1.jar   253,941 字节
<version>0.9.138-a1</version>
<idea-version since-build="242" until-build="261.*" />
SHA256 b81ec276b403eb81d2caaf68393daac492f0245c6542ef5bef99ede0252688ff
```

`build/offline-build.ps1` 的 `[4/4]` 自检与 `unzip -p ... META-INF/plugin.xml`
两处独立核对一致；**未安装**，已装 `0.9.137-branch-diag` 未被覆盖。

一致性校验：8 个改动文件在本 worktree 与门禁 worktree 的 SHA256 逐项相同
（`PARITY_OK=1`），因此门禁与打包验证的就是将要提交的内容。

## 5. 未验证 / 未覆盖

- **未安装、未实机**：本轮只打包，未覆盖已装 `0.9.137-branch-diag`，未启动 IDEA。
- **中英文 / 场景标签缺失**：320 条日志没有按输入法 / 操作类型打标签；
  AC-15 的实机矩阵由用户完成。
- **A-like=true / slope=100 实机样本缺失**：A1 不改这两个形状；
  但「真换行仍保持 A-like 形状」只在几何探针层验证，未实机。
- **软换行跨视觉行**：探针用 `dy=一行高` 的几何等价输入（A1-3），
  未在真实长软换行文本上实机验证。
- **行为探针不驱动 `CaretAnimator`**：A1-1..A1-5 调生产
  `SpringTrail.semanticCharsMoved` + `TrailCorner.jump`，不经过
  `CaretAnimator.refreshTarget`（headless 下构造真实 `Editor` 成本高）。
  `CaretAnimator` 侧的 0/MAX 接线由 A1-7 / D-10d / D-11a 的源码断言守门，
  M3 已证明这三条抓得住。
- **旧测试 `tools/ime-probe/ImeGeomCriterionTest.java`** 反射写 `lastCaretOffset`
  （该字段已删，见 243–244 / 304 / 391 / 420 / 595–597 行）。它**不在门禁内**
  （`build-v-probes.ps1` 只编译 `V*.java`），本轮按白名单未改；若单独运行会失败。
  需要用户决定是删除该旧测试还是迁移到视觉行语义（另立 PROMPT）。
- **`TrailCorner` 的像素兜底分支仍存在**：`charsMoved < 0` 的路径保留给
  未提供语义值的调用方（如探针 B-5 的对照），A1 生产路径不再走它。
  这是「`TrailCorner` 默认不改」的直接结果，不是遗漏。

## 6. 阻塞与偏离

1. **worktree 基线偏差（已修正）**：应用创建的 worktree 分支
   `claude/a1-visual-line-05e7f0` 初始在 `f43ca55`（不是 PROMPT 要求的 `46d8502`）。
   已确认 `f43ca55` 是 `46d8502` 的祖先、无独有提交，`git reset --hard 46d8502`
   对齐基线；无内容丢失。
2. **门禁环境（长路径）**：本 worktree 路径过长，`javac` 源码树编译直接触发
   Windows 命令行上限（`文件名或扩展名太长`）。先试 `D:\a1wt` junction：
   编译可通过，但 `VI18n I-0` 把 classpath 规范化回真实长路径而变红。
   最终在 `D:\a1src`（基于 `46d8502` 的 detached 临时 worktree，短真实路径）
   镜像同一组文件跑门禁；跑前用 SHA256 逐文件比对，确认与本 worktree 内容一致
   （见 §4.7 的终检记录）；跑完删除临时 worktree。`build-v-probes.ps1` 未改。
   打包同理：junction 下 `$PSScriptRoot` 仍解析成真实长路径，`offline-build.ps1`
   的 `javac` 同样超限；因此在 `D:\a1src` 打包后把 jar 复制回本 worktree 的
   `dist/`，SHA256 一致。`build/offline-build.ps1` 未改。
3. **旧判据选择删除而非保留**：PROMPT 允许二选一；`grep` 确认无生产消费者，
   删除更符合「不留第二套判据」。`VMulti` / `VerifyApplyConfig` 按契约同步改写。
4. **`VTypingSpan` 只改注释**：TrailCorner 未变，T-8（真跨行 + `charsMoved=1`
   仍 CROSS）不受 A1 影响；断言未动。
5. **`TrailCorner` 未改**：见 §3 表格与 §5；A1 验收探针未发现
   「同一视觉行被像素 `sameLine` 误判」的可达输入。
6. **无白名单外生产文件改动**；未安装、未 push、未合并、未建 PR、未打 tag。

## 7. 验收记录（用户填）

- 实机环境（IDE + 版本）：IDEA 2026.1.3（Windows 11）
- 本次安装的 jar 版本：`0.9.138-a1`（253,941 B；安装时重打包 SHA256 `1bc8373e…`，dist 原始包 SHA256 `b81ec276…`；同源码同版本）
- 当前配置：`diagLogging=true`；`crossShapeExperiment` 未写盘 = 默认 false；`slopeTicks` 未写盘 = 默认 0
- 单/多光标 × 英文/中文 × Tab / End / Ctrl+方向 / Ctrl+Shift+方向 /
  鼠标同行与跨行 / 选区 / 按词移动 / Enter / 行尾回绕：用户已实机验收，结论 **通过（PASS）**；逐项操作细节未写入仓库，验收记录来源为 2026-11-06 执行会话交接笔记
- 同行大跳是否变成短跳；真换行是否保持现有 A-like 形状；是否可接受：用户确认通过；A1 只改分支选择，未改 A-like/真换行形状
- 结论：**通过**（2026-11-06）
