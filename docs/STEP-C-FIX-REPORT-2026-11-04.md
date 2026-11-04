# Step C 修复报告：撤回跨行前缘 55ms 下限

> 交付对象：项目作者（用户）。
> 仓库 `D:\deepseekwork\neovim-cursor-pr` · 分支 `fix/bug-016-completion-flicker` ·
> 提交 `79acfd3`（**未 push**）· 已装 jar **0.9.131**。
> 本文是交付记录，不替代 `docs/BUG-LOG.md §BUG-016`（发生了什么）与
> `docs/HANDOFF-2026-11-03-BUG-016.md`（下一步怎么修）。

---

## 0. 一页结论

| 项 | 结果 |
|---|---|
| 修复内容 | 撤回 `TrailCorner.jump()` 跨行分支的 `leading = max(leading, shortAnimationLength × 0.5)` |
| 生产行为改动 | **只有一处**（其余是面板公式、文案、探针同步） |
| 三个预设的跨行前缘 | 55 ms → **0 ms**（`trailSize=1.0`，恢复缎带形状） |
| 全量门禁 | **`V-PROBES RESULT: ALL PASS`**（115 个注册探针） |
| D0 门禁 | `core/NeovideConfig.java` **零 diff** |
| 变异测试 | 2 组，均能变红并恢复绿 |
| 代码审查 | `code-reviewer` / `java-reviewer` 均 **0 CRITICAL / 0 HIGH** |
| 安装 | **0.9.131** 已装，个人配置 **15/15 字段还原、0 丢弃** |
| 未修 | Step B（连打误判）、A（弹窗闪烁）、D（删除延迟）均未触碰 |
| 实机验收 | **待用户确认**（本文不宣称「修好了」） |

---

## 1. 问题现象与根因

### 1.1 用户观察

装 0.9.130（含 `aeff707`）之后：

> 「跨行密度像没有了，用鼠标拉伸明显感觉到跨行拖尾变小了……之前很密的现在三个预设都变了」

三个内置预设（基础 / 缎带 / 纯平滑输入）的跨行拖尾都变短、变淡。

### 1.2 根因（一句话）

`aeff707` 把「前缘时长下限」加在了**真跨行与「同一行 charsMoved ≥ 4」误判共用**的跨行分支上。
真跨行在 `trailSize=1.0` 时 `leading` 精确为 0 —— **那不是 bug，是缎带形状的来源**
（前缘第一帧咬住新位置、只有后缘慢慢拖出去）。下限把它抬到 55 ms，
于是正常换行/上下跳也被改慢，三个预设的缎带一起变窄。

### 1.3 为什么下限「按定义」会伤到正常路径

`TrailCorner.jump()` 的分支判据（`TrailCorner.java:253-257`）：

```java
boolean sameLine = Math.abs(deltaYpx) <= height * 0.5;
boolean isShortJump = sameLine && charsMoved <= config.typingMaxChars; // 3
```

`isShortJump == false` 时走跨行分支，而这一支同时服务两类移动：

1. **真跨行**：`deltaY > height/2`（换行、上下跳、鼠标大跳）；
2. **同行误判**：`sameLine == true` 但 `charsMoved ≥ 4`（连打/删词时帧间累计量越过阈值）。

下限改的是这一支的公共公式，所以第 1 类无法幸免。**正确的做法是在 Step B 单独拆判据，
而不是给共享分支加固定时长下限。**

---

## 2. 修复前证据（代码与实测）

### 2.1 代码位置（HEAD = aeff707）

| 位置 | 内容 |
|---|---|
| `core/TrailCorner.java:368` | 原公式 `leading = animationLength × clamp(1 - trailSize, 0, 1)` |
| `core/TrailCorner.java:393` | `leading = Math.max(leading, config.shortAnimationLength * 0.5)` |
| `ui/settings/NeovideCursorConfigurable.java:3426-3428` | 面板同源下限 `floorLeading` / `floored` / `Math.max` |
| `resources/messages/*.properties:128` | `settings.hint.crossMoveFloor`（中英各一条） |

### 2.2 三个内置预设的参数（`settings/CursorPresets.java:355/357/369`）

`animationLength` = 0.10 / 0.28 / 0.10 s，`shortAnimationLength` = 0.11 s，`trailSize` = 1.0（三者相同）。

### 2.3 跨行四角时长对照

rank 映射：`rank0 = trailing`、`rank1 = (leading + trailing) / 2`、`rank2/3 = leading`。

| 预设 | 有下限（aeff707） | 无下限（恢复后） |
|---|---|---|
| 基础 | 100 / 77.5 / **55** / 55 ms | 100 / 50 / **0** / 0 ms |
| 缎带 | 280 / 167.5 / **55** / 55 ms | 280 / 140 / **0** / 0 ms |
| 纯平滑输入 | 100 / 77.5 / **55** / 55 ms | 100 / 50 / **0** / 0 ms |

下限占后缘的比例：基础 / 纯平滑 **55%**，缎带 **19.6%** —— 这就是「短动画预设变化最大」。

### 2.4 `VTypingSpan` 单跑实测（修复前）

```
配置：animationLength=0.10s shortAnimationLength=0.11s trailSize=1.00 typingMaxChars=3
charsMoved=1..3（短跳） 四角 = [0.0946, 0.0946, 0.0352, 0.0352]
charsMoved=4..6（跨行） 四角 = [0.1000, 0.0775, 0.0550, 0.0550]   ← 前缘 55ms
```

---

## 3. 改动清单

### 3.1 生产代码（行为改动只有第 1 条）

| 文件 | 改动 |
|---|---|
| `core/TrailCorner.java` | 删除 `leading = Math.max(...)`；0.9.90 注释块改写为「2026-11-03 Step C 已撤回 / 同行误判仍存在，留给 Step B / 面板必须与本行同源」 |
| `ui/settings/NeovideCursorConfigurable.java` | 恢复 `long leading = Math.round(rawLeading * 1000f)`；`rawLeading` 增加 `clamp(0,1)` 与生产逐字同源；删除 `floorLeading` / `floored` / `floorNote` 及其传参；两处注释同步改写 |
| `resources/messages/NeovideCursorBundle.properties` | 恢复 `comment.trailSize` / `comment.animLenExtra` / `hint.crossMove`（模板去掉 `{3}`）；删除 `hint.crossMoveFloor` |
| `resources/messages/NeovideCursorBundle_zh_CN.properties` | 同上（中文），两语言键集合与占位符一致 |

### 3.2 探针（契约恢复，不删断言）

| 文件 | 改动 |
|---|---|
| `tools/settings-probe/VTypingSpan.java` | 5 条 → **6 条**：T-1 改真跨行（垂直位移）前缘 = 0；T-2 拆为 T-2a（同行 1..3 逐位 = `shortAnimationLength × trailFactors`）与 T-2b（同行 4..6 走跨行分支、前缘 = 0，标注「已知误判，Step B 修」）；T-3/T-4/T-5 保留；删除死代码 `fmt()`；头部契约描述与横幅同步 |
| `tools/settings-probe/VHints.java` | H-5 改为断言 `= 0 ms` 且不含「触底 / the floor」（比旧断言更强） |
| `tools/settings-probe/VTextFacts.java` | T-2 恢复 aeff707 之前的「前缘 ≤ 1 帧（8ms）、后缘 ≥ 400ms」 |
| `tools/settings-probe/build-v-probes.ps1` | 仅注册注释同步（不再把下限写成修法） |

### 3.3 文档

| 文件 | 改动 |
|---|---|
| `docs/BUG-LOG.md` | §BUG-016 新增「Step C 取证」「Step C 实施结果」两节 |
| `docs/HANDOFF-2026-11-03-BUG-016.md` | 新增 §九（取证/白名单）与 §十（实施记录） |
| `docs/STEP-C-FIX-REPORT-2026-11-04.md` | 本文件 |

### 3.4 明确没有动

`core/NeovideConfig.java`（D0）、`ui/CaretAnimator.java`、`ui/SpringTrail.java`、
`build/install.ps1`、其它探针、`settings/SettingsField.java`、`settings/CursorPresets.java`、
颜色/辉光默认值、UI 控件、`docs/INDEX.md`。

---

## 4. 验证记录

### 4.1 四段验证

```
① 红（只改探针、生产未动）
   VTypingSpan  PASS 4 / FAIL 2（T-1、T-2b）
   VHints       PASS 10 / FAIL 1（H-5）
   VTextFacts   PASS 5 / FAIL 1（T-2）
   V-PROBES RESULT: FAILED（3 个探针未通过）

② 绿（改生产 + 文案）
   VTypingSpan 6/6、VHints 11/11、VTextFacts 6/6
   V-PROBES RESULT: ALL PASS

③ 变异红
   A. 把 Math.max 加回 TrailCorner
      → VTypingSpan T-1 / T-2b 红、VTextFacts T-2 红；VHints 仍绿（它测面板）
   B. 只把下限加回面板
      → 仅 VHints H-5 红（实际输出「前缘 = 55 ms」），其余全绿

④ 恢复绿
   V-PROBES RESULT: ALL PASS；D0 零 diff
```

### 4.2 门禁与残留扫描

```
$ pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo 'D:/deepseekwork/neovim-cursor-pr'
V-PROBES RESULT: ALL PASS

$ git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
exit=0（零 diff）

$ grep -rn "shortAnimationLength \* 0\.5|crossMoveFloor|floorLeading|floorNote" src tools
零命中
```

> `VRegr2` 仍是 13 通过 / 2 失败（R-5 `typingSmoothness` 漏看、R-6 不派生副本），
> 属文档标注的 **informational**，与本次改动无关，本次未动。

### 4.3 环境噪音（如实记录）

审查期间另一个 Claude 会话在 23:52–23:54 并发启动了同一条门禁，导致
`build/v2-probe/classes` 被清空、出现 `ClassNotFoundException` 假红
（`CLAUDE.md` 陷阱 4 / 四-G-2 记录的场景）。最终所有数字都来自
「确认无并发 pwsh/java 进程」后的串行运行。

---

## 5. 代码审查结论

### 5.1 两个代理

| 代理 | CRITICAL | HIGH | MEDIUM | LOW | 结论 |
|---|---|---|---|---|---|
| `code-reviewer` | 0 | 0 | 0 | 3 | APPROVE |
| `java-reviewer` | 0 | 0 | 2 | 5 | APPROVE |

### 5.2 已处理

- 探针类头部/横幅仍写「跨度必须稳定」，与 T-2b（跨度确实会变，是已知缺陷）矛盾 → 改为「真跨行前缘 = 0、短跳逐位不变」。
- `fmt()` 死代码 → 删除。
- 「修复只该动跨行分支」措辞 → 改为「本次恢复只该动跨行分支」。
- 「不依赖具体派位」注释与 T-1 逐项断言矛盾 → 改为「按传入 rank 逐项比较，锁定 rank1 中档值」。
- 面板公式缺 `clamp` 而注释写「逐字同源」 → 补 `clamp(1 - trailSize, 0, 1)`。

### 5.3 已知情未处理

| 项 | 原因 |
|---|---|
| `docs/TYPING-JITTER-PLAN.md` 仍把下限写成方案 | 历史决策文档，保留留痕；正文未标注「已撤回」，合并前可补一行说明 |
| `VTextFacts` T-2 的 `crossA[3] <= 8` 是弱断言 | 为「恢复原断言、不删断言」，保留；真正证明前缘 = 0 的是 `crossA[2]` |
| `settings.hint.leadFirstFrame` 现无代码引用 | 本次白名单未授权删除，保留为死键（不显示错误内容） |
| 未跟踪的 `docs/DECISION-REPORT-2026-11-03.md` / `docs/PROMPT-STEP-C-2026-11-03.md` | 未入库；BUG-LOG 若引用需一并提交 |

---

## 6. 安装与配置

### 6.1 安装

```
build/install.ps1 -Version 0.9.131 -NoStart
```

结果：jar 内 `META-INF/plugin.xml` 的 `<version>` = **0.9.131**；
兼容区间 `since-build 242 ~ until-build 261.*`；安装脚本关闭了 IDEA（`-NoStart` 不自动启动）。

### 6.2 个人配置

脚本先备份、装完还原：**15/15 字段、0 丢弃**。

还原后的实际值（**按用户当前 XML**，不是任务书里的旧快照）：

```
alwaysHighFps     = 未写盘（等于默认值，序列化省略）
stretchRatioK     = 2.0
animationLength   = 0.10（随预设，序列化省略）
animPresetBase    = 2（纯平滑输入）
trailSize         = 0.2
targetFps         = 144
slopeTicks        = 100
typingSmoothness  = 0.2
```

> **验收关键**：`trailSize=0.2` 时 `leading = animationLength × 0.8 = 80 ms`，
> 55 ms 下限本来就不生效。要看密度变化，必须切到 `trailSize=1.0` 的三个内置预设
> （基础 / 缎带 / 纯平滑输入），对比 0.9.127。

---

## 7. 未修事项与风险

| 项 | 状态 | 说明 |
|---|---|---|
| **Step B**：同行 `charsMoved ≥ 4` 误判成跨行 | **未修** | 撤回下限后前缘回到 0，因此「连打时偶尔没有拖尾」会重新出现。这是 0.9.127 既有行为，不是本次引入的回归。 |
| **A**：补全弹窗闪烁 / 短跳变短 | **未修** | BUG-016 原始报障，方向未定，未触碰。 |
| **D**：删除延迟 / 常驻刷新 | **未修** | 根因在 `alwaysHighFps=false` 的空闲/自适应档，另有会话处理。 |
| 同行大跳（Ctrl+End）尾巴偏短 | 既有行为 | 属 Step B 的判据问题，本次不动。 |
| 历史文档仍描述下限 | 已知 | `TYPING-JITTER-PLAN.md` 保留留痕，未改。 |

---

## 8. 实机验收步骤（待用户执行）

1. 启动 IDEA（安装脚本已关闭它），确认插件版本 0.9.131（设置页版本行或 jar 内 `plugin.xml`）。
2. 切到三个内置预设（`trailSize=1.0`）：基础 / 缎带 / 纯平滑输入。
3. 逐项对比 0.9.127：
   - 鼠标拖拽拉伸（最明显）；
   - 正常换行、上下跳；
   - 行首退格、Ctrl+Backspace。
4. 打开设置 → 光标行手感：`trailSize=1.0` 时「跨行动画时长」应显示 `前缘 = 0 ms`，且**不再出现**「已触底」。
5. 若密度恢复、文案正确 → 合并/推送 `79acfd3`；若仍有问题 → 记录操作序列与预设，回到 Step B/其它线排查。

---

## 9. 复现命令

```bash
# 全量门禁（唯一权威入口，禁止并发、-Repo 用正斜杠）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo 'D:/deepseekwork/neovim-cursor-pr'

# D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java

# 残留扫描
grep -rn "shortAnimationLength \* 0\.5\|crossMoveFloor\|floorLeading\|floorNote" src tools

# 单独跑三个相关探针（门禁已编译后可复用）
# 参见 tools/settings-probe/build-v-probes.ps1 的 $jvm 参数
```

---

## 10. 仓库状态

```
分支        fix/bug-016-completion-flicker
提交        79acfd3 revert(trail): 撤回跨行前缘 55ms 下限（Step C）
远程        fork/main 与本机 0 / 4 —— 未 push
已装        0.9.131（jar 内版本核验）
工作区      M docs/INDEX.md（非本任务）
            ?? build/night-run-report.md
            ?? deepseekworkneovim-cursor-pr/
            ?? docs/DECISION-REPORT-2026-11-03.md
            ?? docs/PROMPT-STEP-C-2026-11-03.md
            ?? docs/STEP-C-FIX-REPORT-2026-11-04.md（本文件，未提交）
```

**下一步**：用户实机验收 → 确认后合并/推送。在用户确认之前，
本报告只声明「探针绿、已安装」，**不声明「手感已修好」**。
