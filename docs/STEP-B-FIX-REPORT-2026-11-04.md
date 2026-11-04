# Step B 修复报告：同一行 `charsMoved ≥ 4` 误判成跨行

> 交付对象：项目作者（用户）。
> 仓库 `D:\deepseekwork\neovim-cursor-pr` · 分支 `fix/bug-016-completion-flicker` ·
> 本地提交见 §10（**未 push**）· 已装 jar **0.9.132**。
> 本文是交付记录，不替代 `docs/BUG-LOG.md §BUG-016`（发生了什么）与
> `docs/HANDOFF-2026-11-03-BUG-016.md` §十一（下一步）。
>
> **【2026-11-04 已撤回】** 本文记录的 Step B 已于当天撤回：用户反馈目标症状
> 从未感受到，而选词 / 大跳副作用可见。当前行为以
> `docs/STEP-B-REVERT-REPORT-2026-11-04.md` 为准；本文仅保留为历史记录。

---

## 0. 一页结论

| 项 | 结果 |
|---|---|
| 修复内容 | `TrailCorner.jump()` 新增「同行 burst」分类，同一行 `charsMoved > 3` 改走短跳分支 |
| 生产行为改动 | **只有判据一处**；短跳公式与真跨行公式逐字未动 |
| 真跨行密度 | 与 0.9.131 一致（`trailSize=1.0` 时前缘仍为 0） |
| 全量门禁 | **`V-PROBES RESULT: ALL PASS`**（115 个注册探针） |
| D0 门禁 | `core/NeovideConfig.java` **零 diff** |
| 变异测试 | 6 组，均能变红并恢复绿 |
| 代码审查 | `code-reviewer` / `java-reviewer` 均 **0 CRITICAL / 0 HIGH** |
| 安装 | **0.9.132** 已装；个人配置 11/11 字段还原、0 丢弃 |
| 未修 | A（弹窗闪烁）、D（删除延迟）、E（上游跨行模型）均未触碰 |
| 实机验收 | **待用户确认**（本文不宣称「手感已修好」） |

---

## 1. 问题现象与根因

### 1.1 用户观察

连打 / 删词时「同一个按键，这次有拖尾、下次没有」。

### 1.2 根因（一句话）

`charsMoved` 是**帧间累计量**（`SpringTrail.updateCharsMoved`）。一帧内合并 4 次以上击键时，
`charsMoved > typingMaxChars(3)`，同一行的移动被误判成跨行；跨行分支在 `trailSize=1.0` 时
`leading = animationLength × (1 - 1.0) = 0`，前缘第一帧就瞬移，只剩后缘拖出去。

### 1.3 为什么以前修不好

0.9.90 / `aeff707` 曾给跨行 `leading` 加 55ms 下限，但那一支同时服务真跨行与同行误判，
真跨行的缎带形状被一起压窄，Step C 已撤回。正确做法是**拆判据**，不是调共享时长。

---

## 2. 修复前证据

见 `docs/BUG-LOG.md §BUG-016` 的「2026-11-04 Step B 取证」一节。关键数字（基础预设）：

| 场景 | 修复前分支 | 四角（秒） |
|---|---|---|
| 同行 1..3 | 短跳 | 0.0946 / 0.0946 / 0.0352 / 0.0352 |
| 同行 4..6 | **跨行（误判）** | 0.1000 / 0.0500 / 0.0000 / 0.0000 |
| 真跨行 | 跨行 | 0.1000 / 0.0500 / 0.0000 / 0.0000 |

起源为 `74d3a8a`（2026-10-18，`pluginVersion=0.4.3`），不是 0.9.130 的回归。

---

## 3. 改动清单

### 3.1 生产代码（行为改动只有一处）

| 文件 | 改动 |
|---|---|
| `core/TrailCorner.java` | 新增 `isHorizontalBurst = sameLine && !isShortJump`（只在 `charsMoved >= 0` 块内）；`useShortBranch = isShortJump || isHorizontalBurst`；分支条件与 `lastShortJump` 都用 `useShortBranch`；公式逐字未动；同步修正判据注释与跨行分支注释 |

### 3.2 探针（契约变更，非缺陷；未删断言）

| 文件 | 改动 |
|---|---|
| `VTypingSpan.java` | 6 → **9 条**：T-2b 改「同行 4..6 走短跳」；T-3 改真跨行；新增 T-6（跨行密度五档含 clamp 越界）、T-7（同行 burst 4..10、slope=0/100）、T-8（真跨行 + charsMoved=1）；`sameWithin` 增加 NaN/Infinity 守卫 |
| `VBlockStretch.java` | B-2 改为「同行 charsMoved=50 与单行短跳一致」 |
| `VUniform.java` | U-3 改用真跨行（`animationLength=0.28` 拉开差距）；新增 U-4 |
| `VAuditBatch1.java` | A-24 只改注释/标签/变量名，断言逻辑与数量不变 |

### 3.3 文档

`docs/BUG-LOG.md`（取证 + 实施结果）、`docs/HANDOFF-2026-11-03-BUG-016.md`（§十一）、
本报告。

### 3.4 明确没有动

`core/NeovideConfig.java`（D0）、`ui/CaretAnimator.java`、`ui/SpringTrail.java`、
`build/install.ps1`、设置面板与两语言资源、`VHints` / `VTextFacts` / `VPreviewBranch` /
`VMultiCursorFix` / `SettingsField` / `CursorPresets`、`docs/INDEX.md`、`build-v-probes.ps1`。

---

## 4. 验证记录

### 4.1 四段验证（实跑）

```
① 红（只改探针、生产未动）
   VTypingSpan  PASS 6 / FAIL 2（T-2b、T-7）
   VBlockStretch PASS 1 / FAIL 1（B-2）
   VUniform      PASS 3 / FAIL 1（U-4）
   V-PROBES RESULT: FAILED（3 个探针未通过）

② 绿（改生产后）
   VTypingSpan 9/9、VBlockStretch 2/2、VUniform 4/4、VAuditBatch1 27/27
   V-PROBES RESULT: ALL PASS

③ 变异红（六段，每段后恢复）
   M1 isHorizontalBurst=false       → T-2b/T-7/B-2/U-4 红
   M2 真跨行加回 55ms 下限          → T-1/T-6 + VTextFacts T-2 红；VHints 绿
   M3 lastShortJump=isShortJump     → 仅 A-24 红
   M4 isShortJump 丢掉 sameLine     → 仅 T-8 红
   M5 删掉跨行 clamp                → 仅 T-6 红（trailSize=1.5）
   M6 短跳时长改 Double.NaN         → T-2a/T-2b/T-4/T-7 红

④ 恢复绿
   V-PROBES RESULT: ALL PASS；D0 零 diff；无 MUTATION 残留
```

### 4.2 最终探针输出（基础预设）

```
同行 1..3（短跳）     [0.0946, 0.0946, 0.0352, 0.0352]
同行 4..6（burst）    [0.0946, 0.0946, 0.0352, 0.0352]
真跨行 trailSize=1.0  [0.1000, 0.0500, 0.0000, 0.0000]
真跨行 trailSize=1.5  [0.1000, 0.0500, 0.0000, 0.0000]   ← clamp 生效
```

---

## 5. 代码审查结论

| 代理 | CRITICAL | HIGH | MEDIUM | LOW | 结论 |
|---|---|---|---|---|---|
| `code-reviewer` | 0 | 0 | 3 | 3 | APPROVE |
| `java-reviewer` | 0 | 0 | 6 | 4 | APPROVE |

### 5.1 已处理

- T-3 修复后会「假绿」→ 已改用真跨行（`VTypingSpan.java`）。
- `sameWithin` 对 NaN 盲区 → 已加 `Double.isFinite` 守卫；M6 变异证明。
- T-6 没有镜像生产 `clamp` → 已镜像并增加 `trailSize=-0.5/1.5` 越界档；M5 变异证明。
- 缺少「真跨行 + `charsMoved ≤ 3`」覆盖 → 新增 T-8；M4 变异证明。
- U-3 位移差贴近阈值 → 跨行时长改为 0.28s，差距从 10% 拉到 2.5 倍。
- 探针段落标题、历史注释、`float` 乘法顺序、跨行分支服务范围 → 已改写。

### 5.2 已知情未处理

| 项 | 原因 |
|---|---|
| `lastShortJump` 名字仍偏「字面短跳」 | 已用 javadoc 明确它记的是「分支」；改名会扩散到任务书与文档 |
| `build-v-probes.ps1:273-275`、`AnimationPreview.java:670/686/735`、`CaretAnimator.java:347/2481`、`NeovideCursorConfigurable.java:1438` 仍按旧判据解释跨行 | **白名单外，未授权修改**；行为正确，只是归因文字过期 |
| `CLAUDE.md` / `docs/NEXT-STEPS.md` 的 `VTypingSpan 5` 旧数字 | 同上 |
| `docs/INDEX.md` 不收录本报告 | 它已有未提交改动，不属本任务，未 stage |

---

## 6. 安装与配置

### 6.1 安装

```
build/install.ps1 -Version 0.9.132 -NoStart
```

结果：jar 内 `META-INF/plugin.xml` 的 `<version>` = **0.9.132**；
兼容区间 `since-build 242 ~ until-build 261.*`；`dist/neovide-cursor-0.9.132.jar` 247,371 B。
脚本关闭了 IDEA（`-NoStart` 不自动启动）。

### 6.2 个人配置（如实记录）

- 脚本报告：**11/11 字段还原、0 丢弃**。
- 安装前 XML 与安装后 XML **逐字节内容一致**（仅差 XML 声明与换行）。
- **需要用户启动后确认**：`alwaysHighFps`、`stretchRatioK`、`animationLength`、
  `animPresetBase`、`trailSize` 这 5 项在**安装前就不在 XML 里**（说明它们当时等于默认值
  或未被持久化），本次脚本没有改写它们，但也**不能声称「已还原」**。
  这与 MEMORY 记录的「install.ps1 只还原部分字段」是同一已知风险点，不是 Step B 引入的。
- 自定义预设 `customAnimPresets`（4 个，含「个人自定义」「基础调试1」「纯平滑输入 副本」
  「基础测试2」）与 `animCustomName=基础测试2` 均完整保留。

### 6.3 实机验收注意

`trailSize=0.2` 时 `leading = 80ms`，「前缘 0ms」特征不明显。**必须切到
`trailSize=1.0` 的三个内置预设**（基础 / 缎带 / 纯平滑输入）观察 B 的效果。

---

## 7. 未修事项与已知副作用

| 项 | 状态 | 说明 |
|---|---|---|
| **A**：补全弹窗闪烁 / 短跳变短 | **未修** | BUG-016 原始报障，方向未定 |
| **C**：跨行前缘 0ms | **保持** | 本报告不改变 Step C 的任何行为 |
| **D**：删除延迟 / 常驻刷新 | **未修** | 另一条线，目标路径 `alwaysHighFps=false` |
| **E**：上游跨行模型实验 | **未做** | 必须在 B 之后 |
| 同行大跳（Ctrl+End / 行内远点击 / 粘贴 / 接受补全）尾巴变短 | **已知副作用** | 方案 A 固有；未加固定上限，等用户拍板 |
| 缎带同行 burst 后缘 280ms → 93.5ms | **已知副作用** | 同上；基础/纯平滑后缘几乎不变 |

---

## 8. 实机验收步骤（待用户执行）

1. 启动 IDEA（安装脚本已关闭它），确认插件版本 0.9.132。
2. 设置 → 光标行手感：确认 `trailSize`、`stretchRatioK`、预设指针是否与安装前一致
   （XML 里没有这五项，需看面板实际值）。
3. 切到三个内置预设（`trailSize=1.0`）：基础 / 缎带 / 纯平滑输入。
4. 逐项验证：
   - 连打 `ffff...`、长按退格、Ctrl+Backspace → 拖尾稳定，不再忽有忽无；
   - 正常换行 / 上下跳 → 跨行密度与 0.9.131 一致；
   - Ctrl+End / 行内远点击 / 大段删除 → 记录尾巴是否偏短（已知副作用）；
   - 当前 `trailSize=0.2` 的「纯平滑输入」做一次无回归对照。
5. 用户确认后再合并/推送；此前本文只声明「探针绿、已安装」。

---

## 9. 复现命令

```bash
# 全量门禁（唯一权威入口，禁止并发、-Repo 用正斜杠）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo 'D:/deepseekwork/neovim-cursor-pr'

# D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java

# 变异残留扫描
grep -rn "MUTATION" src/main/java tools/settings-probe

# 打包安装
pwsh -NoProfile -ExecutionPolicy Bypass -File build/install.ps1 -Version 0.9.132 -NoStart
```

---

## 10. 仓库状态

```
分支        fix/bug-016-completion-flicker
提交        Step B 本地提交（未 push）
已装        0.9.132（jar 内版本核验）
门禁        V-PROBES RESULT: ALL PASS（115 个注册探针）
D0          零 diff
工作区      M docs/BUG-LOG.md
            M docs/HANDOFF-2026-11-03-BUG-016.md
            M src/main/java/.../core/TrailCorner.java
            M tools/settings-probe/{VTypingSpan,VBlockStretch,VUniform,VAuditBatch1}.java
            ?? docs/STEP-B-FIX-REPORT-2026-11-04.md（本文件）
            M docs/INDEX.md（既有改动，非本任务，不 stage）
            ?? build/night-run-report.md
            ?? docs/DECISION-REPORT-2026-11-03.md
            ?? docs/PROMPT-STEP-B-2026-11-04.md
            ?? docs/PROMPT-STEP-C-2026-11-03.md
```

**下一步**：用户实机验收 → 确认后合并/推送。在用户确认之前，
本报告只声明「探针绿、已安装」，**不声明「手感已修好」**。
