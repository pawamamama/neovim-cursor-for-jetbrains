# Step B 撤回报告：同行 burst 分支（2026-11-04）

> 交付对象：项目作者（用户）。
> 仓库 `D:\deepseekwork\neovim-cursor-pr` · 分支 `fix/bug-016-completion-flicker`。
> 本文记录 **Step B 的撤回**，不替代 `docs/BUG-LOG.md §BUG-016` 与
> `docs/HANDOFF-2026-11-03-BUG-016.md`。

---

## 0. 一页结论

| 项 | 结果 |
|---|---|
| 决定 | **撤回 Step B**，恢复到 0.9.131 的判据 |
| 原因 | 用户反馈「连打偶发无拖尾」**从未感受到**；而选词 / End / 鼠标拖选等副作用可见 |
| 生产行为改动 | 撤销 `isHorizontalBurst` / `useShortBranch`，同行 4+ 字符重新走跨行 |
| 保留 | Step C（真跨行前缘 0ms）+ 探针加固（T-3/T-6/T-8、NaN 守卫、clamp 镜像） |
| 全量门禁 | **`V-PROBES RESULT: ALL PASS`**（115 个注册探针） |
| D0 | `core/NeovideConfig.java` 零 diff |
| 反证变异 | 重新加回 Step B → T-2b / T-7 / B-2 / U-4 红；恢复后 ALL PASS |
| 安装 | **0.9.133** 已装（jar 内版本核验） |
| 推送 | **未 push** |

---

## 1. 为什么要撤回

### 1.1 用户反馈

- 「连打 `ffff...`、长按退格、Ctrl+Backspace 时同一按键有时有拖尾、有时没有」——
  **用户表示从未感受到这个症状**。
- 反而能明显看到的是：`Ctrl+Shift+方向键`选词、`End` / `Home`、鼠标拖选、
  粘贴 / 接受补全等**同行大跳**的拖尾变短、变斜、帧变少。

### 1.2 这个「bug」的来源（二手归因）

```
c13f517  2026-10-27 05:30
fix(trail): 给跨行分支的前缘时长加下限，修复连打时拖尾跳变
分支：claude/typing-jitter-fix-6c8c96
提交说明：「用户观察：按住 f 连打时拖尾长度不一致，辉光最明显。」
尾部署名：Co-Authored-By: Claude Opus 5 (1M context)

   ↓ 同一思路移植
aeff707  2026-11-03  加回 55ms 跨行前缘下限
   ↓
Step C   2026-11-03  撤回下限，保留「连打误判」待办
   ↓
Step B   2026-11-04  拆出同行 burst 分支
```

关键事实：

- 这条记录**没有 issue 链接、没有日志、没有原始对话**，只有提交说明里一句
  「用户观察」；
- 它发生在 **0.9.90**，距今天 **42 个版本**（0.9.90 → 0.9.133）；
- 中间已经历 Step C、半边拖尾回退、平台 252 修复、多光标修复等大量改动；
- 本次执行前**没有再次向用户确认症状是否仍能复现** —— 这是流程失误。

### 1.3 结论

收益不可感知、副作用可见的改动不应保留。**撤回，而不是继续打补丁。**

---

## 2. 撤回内容

### 2.1 生产代码

| 文件 | 改动 |
|---|---|
| `core/TrailCorner.java` | 删除 `isHorizontalBurst` / `useShortBranch`；`lastShortJump = isShortJump`；分支条件回到 `if (isShortJump)`；同行 4+ 字符重新走跨行。注释补充「Step B 已撤回」 |

真跨行公式逐字未动：

```java
double leading = config.animationLength * clamp(1f - config.trailSize, 0f, 1f);
```

### 2.2 探针（改方向，不删断言）

| 文件 | 改动 |
|---|---|
| `VTypingSpan.java` | T-2b 改回「同行 4..6 仍走跨行、前缘 = 0」；T-7 改回「同行 4..10 仍走跨行」；**保留** T-3 真跨行、T-6 密度五档、T-8 真跨行 + charsMoved=1、NaN/Infinity 守卫、clamp 镜像 |
| `VBlockStretch.java` | B-2 改回「修前确实用了更长的 animationLength」（同行 50 字符 = 0.28 vs 单行 0.11） |
| `VUniform.java` | U-3 保留真跨行；U-4 改为「同行 10 仍与 1 不同（已知误判）」 |
| `VAuditBatch1.java` | A-24 注释恢复「上一帧是跨行」 |

### 2.3 行为对照（基础预设，秒）

| 场景 | Step B 期间 | 撤回后（= 0.9.131） |
|---|---|---|
| 同行 1..3 | 短跳 | 短跳（不变） |
| 同行 4..6 | 短跳（burst） | **跨行**：0.1000 / 0.0500 / 0 / 0 |
| 真跨行（trailSize=1.0） | 跨行 | 跨行（不变） |

---

## 3. 验证

### 3.1 门禁

```
$ pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo 'D:/deepseekwork/neovim-cursor-pr'
V-PROBES RESULT: ALL PASS
```

`VTypingSpan` 9/9、`VBlockStretch` 2/2、`VUniform` 4/4、`VAuditBatch1` 27/27。

### 3.2 D0

```
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
exit=0（零 diff）
```

### 3.3 反证变异 R1

把 Step B 重新加回生产（`isHorizontalBurst = sameLine && !isShortJump`），
预期撤回后的契约守卫变红：

```
VTypingSpan  PASS 7 / FAIL 2（T-2b、T-7）
VBlockStretch PASS 1 / FAIL 1（B-2）
VUniform      PASS 3 / FAIL 1（U-4）
V-PROBES RESULT: FAILED（3 个探针未通过）
```

恢复后：`V-PROBES RESULT: ALL PASS`，无 `MUTATION` / `isHorizontalBurst` 残留。

---

## 4. 安装

```
build/install.ps1 -Version 0.9.133 -NoStart
```

- jar 内 `META-INF/plugin.xml` = **0.9.133**；
- `dist/neovide-cursor-0.9.133.jar` 247,287 B；
- 个人配置 **12/12 字段还原、0 丢弃**（比上次多一个 `diagLogging=true`）。

**仍需用户启动后确认**：`alwaysHighFps`、`stretchRatioK`、`animationLength`、
`animPresetBase`、`trailSize` 这 5 项在安装前就不在 XML 里，本次脚本没有改写它们，
但也不能声称「已还原」。这是 `install.ps1` 的已知风险点，与 Step B 撤回无关。

---

## 5. 跨行帧变少：仍需单独核对

Step B 的真跨行公式**没有改过**，`git diff` 里该行无变化，T-1/T-6 也证明
`trailSize=1.0` 时前缘仍为 0。因此「跨行帧变少」不能归因于 Step B。

最可能的原因仍是**安装时参数被重置**（`trailSize` / 预设指针 / `animationLength`）。
建议启动后在设置面板核对：

1. 当前预设名；
2. `animationLength`；
3. `trailSize`；
4. 用「缎带」与「基础」各按几次 `Enter` / `↓`，跨行尾巴应有 2.8 倍差。

---

## 6. 明确未做

- A（补全弹窗闪烁）、D（删除延迟）、E（上游跨行模型）均未触碰；
- Step C 的 0ms 真跨行前缘保持不动；
- 不再尝试「同行 burst」方案，除非将来有日志证明症状真实存在。

## 7. 流程教训

- **二手归因不能当既定 bug**：提交说明里的「用户观察」不等于用户当前仍能复现；
- **跨 40+ 版本的旧观察必须重新取证**；
- 执行前应先用一句话向用户确认症状，再动手；
- 探针能证明「代码分支变了」，不能证明「用户感受到了修复」。

---

## 8. 仓库状态

```
分支        fix/bug-016-completion-flicker
提交        撤回提交（未 push）
已装        0.9.133
门禁        V-PROBES RESULT: ALL PASS
D0          零 diff
```

**下一步**：用户启动 0.9.133 实机确认选词/大跳恢复旧观感；跨行参数按 §5 核对。
