# 决策报告：Step B 撤回后的状态与待拍板项（2026-11-04）

> **给决策会话**：这份文档自包含，读完即可拍板，不需要回翻任何对话。
> **本会话只做决策与排期，不改代码。**
> 仓库 `D:\deepseekwork\neovim-cursor-pr` · 分支 `fix/bug-016-completion-flicker` ·
> HEAD `fddd529` · 已装 **0.9.133**（这是记录时的构建；**2026-11-04 实测为 0.9.131**，见 §9）·
> 远程 `fork/main...HEAD = 0/7`（**未 push**）。

---

## 0. 一页结论

| 项 | 当前事实 |
|---|---|
| Step B（同行 burst） | **已实施后又撤回**，HEAD 为撤回提交 |
| 0.9.133 的运行时行为 | **与 0.9.131 相同**（源码差异仅注释；class 仅行号表不同） |
| 全量门禁 | `V-PROBES RESULT: ALL PASS`（115 个注册探针） |
| D0 | `core/NeovideConfig.java` 零 diff |
| 反证变异 | 重新加回 Step B → T-2b/T-7/B-2/U-4 红；恢复后 ALL PASS |
| 安装 | 0.9.133；个人配置 12/12 还原、0 丢弃 |
| 未解决 | ① 用户说「跨行帧变少」；② 预设名 / `animationLength` 是否被重置待核对（`trailSize=1.0` 已确认是期望值）；③ A/D/E 未修 |
| 需要拍板 | 全量回退目标、配置核对、选词观感、install.ps1、分支历史 |

---

## 1. 发生了什么（时间线）

```
0.9.127（v0.9.127 标签）
  ↓
aeff707  2026-11-03  跨行前缘 55ms 下限 + 编辑后像素复查 + install.ps1 备份时机
  ↓
Step C   79acfd3     撤回 55ms 下限（真跨行前缘回到 0ms），用户实机验收通过
  ↓
Step B   5de3b37     同一行 charsMoved>3 从跨行改到短跳（同行 burst）
  ↓
用户反馈：连打偶发无拖尾「从未感受到」；选词/End/鼠标拖选观感变化可见
  ↓
fddd529  2026-11-04  撤回 Step B，安装 0.9.133
```

### 1.1 这个「bug」是谁记录的

- 首次修复提交：`c13f517`（2026-10-27 05:30），
  分支 `claude/typing-jitter-fix-6c8c96`，
  提交说明写「用户观察：按住 f 连打时拖尾长度不一致，辉光最明显」，
  尾部署名 `Co-Authored-By: Claude Opus 5`。
- **没有 issue 链接、没有日志、没有原始对话**，只有这一句「用户观察」。
- 它属于 **0.9.90**，距撤回时 **42 个版本**。
- 后续 `aeff707 → Step C → Step B` 一路把它当既定 bug 带下来，
  执行 Step B 前没有再向用户确认症状是否仍能复现。

### 1.2 为什么撤回

- 用户明确表示：这个症状**从未感受到**。
- 用户能明显看到的是副作用：`Ctrl+Shift+方向键`选词、`End/Home`、鼠标拖选、
  粘贴/接受补全等**同行大跳**的拖尾变短、变斜、帧变少。
- 结论：收益不可感知、副作用可见的改动不应保留。

---

## 2. 版本对比（实证）

### 2.1 0.9.133 vs 0.9.131

**运行时行为相同。**

- `git diff 79acfd3..HEAD -- src/main/java`：
  只有 `TrailCorner.java` 的 **注释**变化（5 行新增、2 行替换）；
- 逐字节比较 jar：
  - 所有 class 中只有 `TrailCorner.class` 不同 —— 原因是注释改变了行号表；
  - 其余 class **完全一致**；
  - `plugin.xml` 只差版本号。

> 所以「全量回退到 0.9.131」在行为上是 **no-op**，只会丢探针加固与文档。

### 2.2 0.9.133 vs 0.9.127

去掉注释后，生产代码只剩这些真实差异：

| 文件 | 差异 | 影响 |
|---|---|---|
| `CaretAnimator.java` + `SpringTrail.java` | 「编辑后像素复查」：文档戳变化的下一帧强制走慢路径（`recheckThisFrame` / `recheckNextFrame` / `lastStamp()`） | 修 BUG-016-2：平台晚一帧返回旧像素时，光标不再停在旧位置 |
| `NeovideCursorConfigurable.java` | 面板 `leading` 计算加 `clamp`；`leading=0` 显示 `0 ms` 而不是「第一帧」 | 只影响设置页文字 |
| `TrailCorner.java` | 只有注释 | 无行为变化 |

> 动画判据、短跳公式、跨行公式都已回到 0.9.127。
> 回退到 0.9.127 会丢掉「编辑后像素复查」这个真修复，但不会改变动画手感。

### 2.3 0.9.133 vs 0.9.130

0.9.130 含 55ms 跨行前缘下限；0.9.133 已由 Step C 撤回。
**回退到 0.9.130 会把跨行密度再次压窄**（基础/纯平滑前缘 0→55ms，缎带 0→55ms）。

---

## 3. 当前行为（基础预设，单位秒）

| 场景 | 0.9.133 四角 |
|---|---|
| 同行 1..3 | 短跳 `0.0946 / 0.0946 / 0.0352 / 0.0352` |
| 同行 4..6 | **跨行** `0.1000 / 0.0500 / 0 / 0` |
| 真跨行（trailSize=1.0） | 跨行 `0.1000 / 0.0500 / 0 / 0` |

`trailSize` 五档（-0.5 / 0 / 0.5 / 1 / 1.5）均与
`animationLength × clamp(1 - trailSize)` 逐位一致；真跨行前缘在 1.0 时为 0。

---

## 4. 待拍板项

### D1 全量回退目标（最高优先级）

| 选项 | 结果 | 代价 |
|---|---|---|
| A. 不回退 | 0.9.133 已等于 0.9.131 行为 | 无；保留探针加固与文档 |
| B. 回退到 0.9.127 | 动画不变；去掉「编辑后像素复查」 | 丢失 BUG-016-2 的真修复 |
| C. 回退到 0.9.130 | 跨行前缘 55ms 下限回来 | 跨行密度再次变窄，不建议 |

**建议默认**：A。若目标是「没有 Step B」，0.9.133 已经是了。

### D2 跨行帧变少（用户观察）

Step B 的真跨行公式**没有改过**，不能归因于 Step B。

**用户已确认（2026-11-04）**：`trailSize` 当前与期望都是 **1.0**（密度最高，
调低会更难看）；**不要**把 1.0 当成安装重置异常，也不要为斜拖尾去调低它。
因此跨行前缘 = 0 是 1.0 下的预期行为。

仍待核对的是**预设名 / `animationLength`**：

- `install.ps1` 本次只还原 XML 里实际存在的字段；
- `alwaysHighFps`、`stretchRatioK`、`animationLength`、`animPresetBase`、`trailSize`
  这 5 项安装前就不在 XML 里；
- 如果预设指针变了，`animationLength` 可能从 0.28 变 0.10，跨行尾巴会短 2.8 倍。

**需要决策**：先核对预设名与 `animationLength`（推荐），还是排查像素复查/其他代码路径。

### D3 选词 / 大跳观感

Step B 已撤回，选词/End/鼠标拖选已回到 0.9.131 观感。
如果用户仍然不满意：

- 不要再复活 Step B；
- 可选「选区专用分支」（用 `hasSelection()` 区分输入/选区）——但它会同时改鼠标拖选，
  且需要改 `CaretAnimator`，属于新功能/新决策；
- 必须先有日志证明「选区走错分支」，再动手。

### D4 install.ps1 配置还原缺陷

现状：脚本只备份/还原 XML 里实际存在的字段；5 个点名字段不在 XML 时不会被还原。
这是独立缺陷，与 Step B 无关。

**需要决策**：是否单开任务修 `install.ps1`（把 State 全部字段都写盘/还原），
还是先由用户手动在面板确认。

### D5 分支历史与推送

当前 `fix/bug-016-completion-flicker` 本地领先 `fork/main` **7 个提交**，未 push：

```
fddd529 revert(trail): 撤回 Step B 同行 burst 分支
5de3b37 fix(trail): 同行 charsMoved>=4 拆出 burst 分支
cda7141 docs: 补充 Step C 修复报告
79acfd3 revert(trail): 撤回跨行前缘 55ms 下限（Step C）
aeff707 fix(trail): 跨行前缘下限 + 编辑后像素复查 + install.ps1 备份时机
a0099e3 docs: ...
406c5d0 docs: ...
f46e5fb docs: ...
```

**需要决策**：

- 保留 `5de3b37 + fddd529` 两个提交（历史真实，推荐）；
- 还是 squash 成一个（会改写历史，项目纪律不鼓励 `git reset`）；
- 是否 push / 合并到 main（当前未 push；Step C 用户已验收，Step B 已撤回）。

### D6 探针与文档

- 撤回后保留的加固：`VTypingSpan` T-3/T-6/T-8、NaN 守卫、clamp 镜像；
  `VBlockStretch` B-1；`VUniform` U-3；`VTextFacts` / `VHints` 未动。
- 已知文档漂移（白名单外，未改）：
  `build-v-probes.ps1:273-275`、`AnimationPreview.java:670/686/735`、
  `CaretAnimator.java:347/2481`、`NeovideCursorConfigurable.java:1438`、
  `CLAUDE.md` / `NEXT-STEPS.md` 的 `VTypingSpan 5` 旧数字。

**需要决策**：是否单开一个「注释与文档纠偏」任务（`PROMPT-COMMENT-AUDIT-2026-11-04.md`
与 `PROMPT-DOC-CORRECTION-2026-11-04.md` 已在工作区，未跟踪，可能是其他会话准备的）。

### D7 A / C / D / E 的边界

- A（补全弹窗闪烁）：未修；工作区已有 `PROMPT-A-POPUP-FLICKER-2026-11-04.md`（未跟踪）。
- C（跨行前缘 0ms）：已验收，保持。
- D（删除延迟）：未修；目标路径 `alwaysHighFps=false`。
- E（上游跨行模型实验）：未做；必须在 B 之后，但 B 已撤回，E 的前提需要重新评估。

---

## 5. 复现 / 核验命令

```bash
# 全量门禁（唯一权威入口，禁止并发，-Repo 用正斜杠）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo 'D:/deepseekwork/neovim-cursor-pr'

# D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java

# 版本对比：0.9.131 -> HEAD 的生产代码差异（应只有注释）
git diff 79acfd3..HEAD -- src/main/java

# 0.9.127 -> HEAD 的真实行为差异（去掉注释）
git diff v0.9.127..HEAD -- src/main/java | grep -E "^[+-]" | grep -vE "^[+-][[:space:]]*(//|\*|/\*)"

# 已装版本（读 jar 内 XML，不看文件名）
unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" META-INF/plugin.xml | grep -o "<version>[^<]*"
```

---

## 6. 仓库与工作区状态

```
分支        fix/bug-016-completion-flicker
HEAD        fddd529 revert(trail): 撤回 Step B 同行 burst 分支（无可感知收益）
远程        fork/main...HEAD = 0/7（未 push）
已装        0.9.133
门禁        V-PROBES RESULT: ALL PASS
D0          零 diff

已提交（本分支最近）
  fddd529 revert Step B
  5de3b37 Step B
  cda7141 docs Step C
  79acfd3 Step C
  aeff707 跨行下限 + 像素复查 + install.ps1
  ...

未提交/未跟踪（不属本次撤回，未 stage）
  M docs/INDEX.md
  ?? build/night-run-report.md
  ?? docs/archive/decisions/DECISION-REPORT-2026-11-03.md
  ?? docs/archive/prompts/PROMPT-A-POPUP-FLICKER-2026-11-04.md
  ?? docs/archive/prompts/PROMPT-COMMENT-AUDIT-2026-11-04.md
  ?? docs/archive/prompts/PROMPT-DOC-CORRECTION-2026-11-04.md
  ?? docs/archive/prompts/PROMPT-STEP-B-2026-11-04.md
  ?? docs/archive/prompts/PROMPT-STEP-C-2026-11-03.md
```

---

## 7. 给决策会话的建议（供参考，不代替拍板）

1. **D1 选 A**：0.9.133 已经是 0.9.131 的行为，不必全量回退；回退到 0.9.127 只会丢像素复查。
2. **D2 先核对配置**：`trailSize` 应为 1.0（用户已确认）；重点看预设名与 `animationLength`，再决定是否查代码。
3. **D3 不要再复活 Step B**：没有日志证明症状前，不动判据。
4. **D4 单开 install.ps1 任务**：这是独立缺陷，不要混进 A/D/E。
5. **D5 保留两个提交**：历史真实；是否 push 由用户确认后决定。
6. **D6/D7 分开排期**：注释纠偏、A、D、E 各自独立，不要和当前撤回分支混。

---

## 8. 流程教训（写进决策，避免重演）

- 提交说明里的「用户观察」是**二手归因**，不等于用户当前仍能复现；
- 跨 40+ 版本的旧观察必须重新取证；
- 执行前先用一句话向用户确认症状；
- 探针只能证明「分支变了」，不能证明「用户感受到了修复」；
- 收益不可感知、副作用可见的改动，应撤回而不是继续打补丁。

---

## 9. 2026-11-04 更正

> 本文的版本号是**当时安装的那个构建**，不是本轮实测值。原文保留。

| 位置 | 原文 | 更正 |
|---|---|---|
| 文首 / §0 / §6 | 已装 **0.9.133** | **2026-11-04 实测已装 0.9.131**（读 jar 内 `plugin.xml`）。0.9.132 / 0.9.133 是**中间版**，行为 = 0.9.131，不作为基线 |
| §0「0.9.133 行为与 0.9.131 相同」 | 结论本身 | **保留**：源码差异只有注释，class 只差行号表 |
| §2.2 / §4 D1 | 回退到 0.9.127 的选项 | 不再考虑：当前基线是 0.9.131，且 0.9.127 没有「编辑后像素复查」 |
| §4 D2 | `trailSize` 待核对 | 已由用户确认：当前与期望都是 **1.0**（密度最高） |
| §4 D7 | A 未修、工作区有 A 提示词 | 2026-11-04 用户决定 A **暂缓**（「很难告」） |
| — | — | 工作区现有文档改动是本轮（文档纠正）产生的，不是代码改动 |

**下一步**：只做「跨行密度」实验；A/B/C/D/E 的代码都不动。
完整摘要见 `docs/CURRENT-STATE-2026-11-04.md`。
