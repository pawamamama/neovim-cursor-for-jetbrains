# 分支整理 + 单行斜拉伸核查（2026-10-27）

> 只读核查与方案，**未执行任何 git 操作、未改任何代码、未装 jar**（遵守 R1~R4）。

---

## Context —— 为什么做这份核查

用户反馈：「明确说过不要单行斜拉伸，但装上 0.9.90 后发现它仍然存在，
怀疑是分支问题 —— 当前 `fix/f7-override-read` 上带了很多历史改动」。

要回答两个问题：**斜拉伸从哪来**、**分支要不要整理**。

---

# 任务 1：单行斜拉伸的确定结论

## 1.1 结论先说

**不是分支带进来的，是当前分支自身的「出厂默认值」与「预设声明值」不一致。**

```
出厂 State.slopeTicks   = 100   （完全斜）← 首次安装就用这个
「基础」预设声明 slope  = 0     （纯矩形）← 只有用户点了才生效
两者相等? false
```

实测证据（2026-10-27 跑 `_slopeprobe`）：

```
=== State 全新实例的 slopeTicks ===
  slopeTicks = 100
=== 三个内置预设声明的 slope ===
  基础 = 0    缎带 = 0    纯平滑输入 = 100
=== 结论 ===
  出厂 State.slopeTicks=100  而「基础」预设声明=0  两者相等? false

=== trailFactors 实算（k=4.5, smooth=0）===
  slope=100 -> 1.00 0.72 0.42 0.22      ← 完全斜
  slope=0   -> 0.86 0.86 0.32 0.32      ← 纯矩形（有拉伸、无倾斜）
```

## 1.2 「单行斜拉伸」由哪个参数控制

**是 `slopeTicks`（斜度），不是 `stretchRatioK`。** 两者是不同维度：

| 参数 | 控制什么 | 基础预设 | 缎带预设 |
|---|---|---|---|
| `stretchRatioK` | **拉伸强度**（矩形被拉多长） | 4.5 | 8.0 |
| `slopeTicks` | **斜度**（同侧两角速度差 → 平行四边形还是矩形） | 0 | 0 |
| `State.slopeTicks` **初值** | ← 首次安装实际用这个 | **100** | — |

生产路径（关键，证明不是预设决定的）：

```
NeovideCursorSettings.syncStateToConfig()
  → NeovideCursorSettings.java:424
    c.trailFactors = deriveTrailFactors(s.stretchRatioK, s.typingSmoothness, s.slopeTicks)
                                                                              ^^^^^^^^^^^^
                                                                    读的是 State，不是预设
```

预设的值只有经 `applyAnimPresetFields()` 才写进 State：

```
NeovideCursorConfigurable.java:2352   s.slopeTicks = p.slopeTicksValue();
```

**而首次安装不会调用它** —— 所以 State 的初值 100 直接生效。

## 1.3 上一次「用户确认不要斜拉伸」是哪次提交

**`46747b8`（2026-10-26 13:00）**，提交信息原文：

> ## 1. 预设斜度：只有「纯平滑输入」倾斜
>
> 用户要求：「只有纯平滑输入会单行倾斜，其他两个都是零只拉伸」
>
> ```
>   基础        100 -> 0   （纯矩形拉伸，无倾斜）
>   缎带        100 -> 0
>   纯平滑输入  100 -> 100 （保持，唯一带倾斜的）
> ```

对应代码 `CursorPresets.java:341`、`:343`、`:355`：

```java
// 第 14 参 = 斜度。0 = 纯矩形（有拉伸、无倾斜）
new AnimPreset("基础",  ..., smooth=0,   slope=0)
new AnimPreset("缎带",  ..., smooth=0,   slope=0)
// 唯一保留倾斜的预设（用户要求：只它单行倾斜）
new AnimPreset("纯平滑输入", ..., smooth=20, slope=100)
```

**这次改动是对的，和用户要求完全一致 —— 但它只改了预设表，没改 State 初值。**

## 1.4 之后有没有被改回来

**没有。** 预设表那次改动一直保留到现在。

问题在于 `46747b8` **只改了一半**：

| 位置 | 值 | 改了吗 |
|---|---|---|
| `CursorPresets` 三个预设的 slope | 基础/缎带=0 | ✅ 改了 |
| `NeovideCursorSettings.State.slopeTicks` 初值（`:901`） | 100 | ❌ 没改 |

所以：**点过「基础」或「缎带」的用户看不到斜拉伸**（预设把 0 写进 State 了）；
**全新安装的用户直接吃 State 初值 100，一定会看到斜拉伸。**

这正好解释了用户的观感：「我明明说过不要，装了新的还有」。

## 1.5 为什么守门探针没抓住

`VDefaultFreeze` 的 F-5 断言「出厂默认 == 基础预设」，但它逐项比对 8 个字段
**漏了 `slopeTicks`**（`VDefaultFreeze.java:168-175`）：

```java
boolean baseMatch = Math.abs(def.animationLength - base.animationLength()) < 1e-4f
        && Math.abs(def.shortAnimationLength - base.shortAnimationLength()) < 1e-4f
        && Math.abs(def.trailSize - base.trailSize()) < 1e-4f
        && def.glowLayers == base.glowLayers()
        && Math.abs(def.shadowBlurFactor - base.shadowBlurFactor()) < 1e-4f
        && Math.abs(def.glowOpacity - base.glowOpacity()) < 1e-4f
        && def.useShadow == base.useShadow()
        && def.overlayFadeOut == base.overlayFadeOut();
        // ← 没有 slopeTicks（也没有 stretchRatioK / typingSmoothness / tailOpacity）
```

更糟的是，它的金表第 61 行把 `slopeTicks` 锁成 `"100"`：

```java
GOLDEN.put("slopeTicks", "100");
```

**等于把这个不一致当成正确值固定下来了。**

## 1.6 关于「那条分支」的澄清

用户提到：「有一条分支是除了纯平滑输入，其余单行斜度都是 0，缎带和基础，
而且能正常矩形拉伸」。

**这条描述与当前 `fix/f7-override-read` 的预设表完全吻合**（基础=0、缎带=0、
纯平滑输入=100，且 `slope=0` 时确实仍保留拉伸 —— 见 `cc781b7` 的实测
`拉伸 5.84px 斜 0.00px`）。

所以用户记忆中的那条分支，很可能就是当前分支的**预设层**；
差异出在 **State 初值层**，不在分支。**不需要切分支，需要改一个默认值。**

## 1.7 修法（已定案，0.9.91 实施）

**用户 2026-10-27 明确**：「没有老用户目前就是我，我不会后台实现啊问我没有用」
→ 按纪律四-K（只有用户一个、不为不存在的人写兼容）与四-L（不问实现细节），
以下决策由我拍板，不再征询：

| # | 位置 | 改前 | 改后 | 理由 |
|---|---|---|---|---|
| 1 | `NeovideCursorSettings.java:901` | `slopeTicks = 100` | **`0`** | 用户要「不斜」，出厂就该不斜 |
| 2 | `VDefaultFreeze.java:61` 金表 | `"slopeTicks" → "100"` | **`"0"`** | 金表跟着改 |
| 3 | `VDefaultFreeze` F-5 | 比 13 项，**漏 slope** | **补 `slopeTicks`** | 本次 bug 的直接盲区 |
| 4 | `NeovideCursorSettings.java:218` 迁移 | `82 → 100` | **`82 → 0`** | 无老用户；用户自己那份归 0 正是他要的 |
| 5 | `NeovideCursorSettings.java:223` 越界回落 | `: 100` | **`: 0`** | 与默认值一致 |
| 6 | `SettingsField.java:411` 越界回落 | `: 100` | **`: 0`** | 同上 |

**关于第 4 条**：那「极少数恰好把滑块停在 82 的用户」就是四-K 说的**不存在的人**。
用户自己机器上若存着 82，归到 0 正合他意（他两次说不要斜）。

**关于 5/6 两条**：越界回落是防御性代码，落到默认值才合理。默认 0，回落也 0。

---

# 任务 2：分支整理方案（只出方案，不执行）

## 2.1 分支清单（只看一周内 = 2026-10-20 之后）

| 分支 | 最后提交 | 领先 fork/main | 落后 | 远端有 | 用途 | 处置建议 |
|---|---|---|---|---|---|---|
| `fix/f7-override-read` | 10-27 | **5** | 0 | ❌ | **当前工作分支**（0.9.90 装的就是它） | **必须留** |
| `claude/d0-gate-probe-status-caadda` | 10-27 | 5 | 0 | ❌ | 今晚的 worktree 分支，与上者**同一个提交** | 可删（内容已在主分支） |
| `claude/d0-gate-probe-count-996441` | 10-26 | 0 | 0 | ✅ | 另一个 worktree，与 fork/main 一致 | 可删（无独有内容） |
| `wip/docs-980377f` | 10-22 | 2 | **159** | ❌ | 0.9.9 时代 WIP，2 个提交 | 归档，不删 |
| `wip/bad-refactor-631db51` | 10-22 | 1 | **159** | ❌ | 字段表重构 WIP（名字已标 bad） | 归档，不删 |
| `feat/settings-ui-ready` | 10-21 | **29** | **161** | ❌ | 0.9.9 主线，含亚像素/前缘硬吸附等**未采用**改动 | **建议留**（唯一保存这些实验的分支） |

**一周内的 6 个分支全在这里。** 更早的 7 个（10-16 ~ 10-19）按要求忽略。

## 2.2 未推送提交

| 分支 | 未推送提交 |
|---|---|
| `fix/f7-override-read` | **今晚 5 个**（`8d3e787` `6523f75` `e7eeec8` `4088b24` `e52c39d`） |
| `claude/d0-gate-probe-status-caadda` | 同上（同一批） |
| `wip/docs-980377f` | 2 个（`980377f` `631db51`） |
| `wip/bad-refactor-631db51` | 1 个（`631db51`） |
| `feat/settings-ui-ready` | 29 个 |

**注意：`fork/main` 停在 `2807b5f`，今晚的 5 个提交都还没推上去。**

## 2.3 可以删 / 必须留

**可以删（内容已在别处）：**
- `claude/d0-gate-probe-status-caadda` —— 与 `fix/f7-override-read` 同一提交
- `claude/d0-gate-probe-count-996441` —— 与 `fork/main` 同一提交，零独有内容

**必须留：**
- `fix/f7-override-read` —— 当前工作分支
- `feat/settings-ui-ready` —— 29 个未采用的实验提交，删了就没了

**建议归档（不删，改名加前缀）：**
- `wip/docs-980377f` → `archive/wip/docs-980377f`
- `wip/bad-refactor-631db51` → `archive/wip/bad-refactor-631db51`

理由：落后的 159 个提交里可能有个别文档/思路值得查，但作为「活分支」已无意义。

## 2.4 Worktree 清单

| 路径 | HEAD | 分支/状态 | 处置 |
|---|---|---|---|
| `D:/deepseekwork/neovim-cursor-pr` | `e52c39d` | `fix/f7-override-read` | **主工作区，留** |
| `.../worktrees/d0-gate-probe-status-caadda` | `e52c39d` | `claude/d0-gate-probe-status-caadda` | 与主分支同提交，可移除 |
| `.../worktrees/d0-gate-probe-count-996441` | `2807b5f` | detached | 零独有内容，可移除 |
| `D:/deepseekwork/nc-upstream-pr` | `b92d6e7` | `fix/wakeup-reset-lastnanos` | **10-19，超出「一周内」，按要求不评估** |
| `D:/deepseekwork/_wt_base` | `e3f61e6` | detached | 同上 |
| `Temp/nc-faith` `nc-fork` `nc-glow8` | 各自 | 临时 | 同上 |

## 2.5 「合并到干净基线」方案

**先说结论：不建议现在做。**

当前 `fix/f7-override-read` 已领先 `fork/main` 5 个提交，而且**今晚的修复
（光标漂移 + VDefects + 文档）都在这上面**。所谓「干净基线」如果指 `fork/main`，
它反而**更旧**（缺今晚修复）。

如果真的想重建一条干净分支：

```bash
# 步骤 1：先把今晚的成果推上去（保命）
git push fork fix/f7-override-read

# 步骤 2：从 fork/main 拉新分支
git switch -c clean/baseline-20261027 fork/main

# 步骤 3：只摘想要的提交
git cherry-pick 8d3e787            # 光标漂移修复
git cherry-pick e7eeec8            # VDefects
git cherry-pick 6523f75 e52c39d    # 文档

# 步骤 4：验证（D0 + 全量探针 + 手动 Ctrl+滚轮缩放）
```

**回滚方式**：`git switch fix/f7-override-read` —— 原分支一个字节都没动，
随时回来。所有操作都是新增分支，不删不覆盖。

**不建议现在做的理由：**

1. 当前分支**并不脏** —— 领先的 5 个提交全是今晚干净的工作
2. 真正的问题（斜拉伸）**不在分支上，在默认值上**（见任务 1）
3. cherry-pick 会引入冲突风险，收益接近零

**正确的顺序**：先按任务 1 修默认值 → 验证 → 再考虑分支整理。

---

# 附：本次核查的可复现命令

```bash
# 1. 确认 State 初值与预设声明的差（需先编 _slopeprobe）
java -cp "build/_sprobe;build/v2-probe/classes;<idea-libs>" _slopeprobe

# 2. 确认生产路径读的是 State 不是预设
grep -n "deriveTrailFactors" src/main/java/com/github/yyyolo7a79/neovidecursor/settings/NeovideCursorSettings.java

# 3. 确认用户要求那次提交
git log -1 46747b8

# 4. 确认探针盲区
sed -n '160,190p' tools/settings-probe/VDefaultFreeze.java
sed -n '55,70p'  tools/settings-probe/VDefaultFreeze.java
```

按项目纪律（陷阱 12「文档会撒谎」），**任何数字请自己跑一遍再信**。
