# A-like 20ms 跨行吸附实验 施工提示词（2026-11-06）

> 状态：待执行
> 仓库：`D:\deepseekwork\neovim-cursor-pr`
> 执行工作区：`D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\sleepy-swanson-ffc2cf`
> 分支：`claude/determined-kowalevski-c1c64b`（当前 HEAD `f43ca55`；不要切分支、不要 reset/checkout 已有未提交改动）
> 角色：新会话只做执行，不做产品裁决；用户离线，遇阻写 REPORT §6，不等待。
> 本轮只做一件事：在已有的设备级实验开关 `crossShapeExperiment` 下，把跨行/鼠标拖动大跨度分支改成 A-like 20ms 前缘吸附。不要重做已有实验开关、持久化、置灰或预览接线。

---

## 1. 背景与现状（必须保留）

当前 worktree 已有未提交改动，包含：

- `NeovideCursorSettings.State.crossShapeExperiment`（设备级、默认 false、`runtimeSwitch=true`）；
- `SettingsField` 注册、copyFrom、loadState；
- `CaretAnimator` → `SpringTrail` → `TrailCorner` 的设备开关下发；
- `AnimationPreview` 的 CROSS-only setter；
- 设置页「跨行形状实验（设备级 · 默认关）」复选框，开启后置灰 `trailSize`；
- 守门探针 `VCrossShapeExperiment`，当前 25 条断言全绿；
- 当前全量门禁 `V-PROBES RESULT: ALL PASS`，D0 `EXIT=0`；
- 当前工作区已有 `docs/sessions/2026-11-05-cross-shape-experiment/REPORT.md`。

**不要重做以上内容。** 新会话先 `git status --porcelain` 和 `git diff --stat` 确认它们在；若不在，先停下并在 REPORT 写清，不要 reset。

用户实机反馈（视频 `D:\deepseekwork\video\neovim\2026-11-06 00-12-28.mkv`）：

- 当前实验在跨行 10 字符、动画时长测试值 0.35s 时，出现「右下角先到位、那一点拉伸很长、后半段才出现/中间像断开」；
- 用户明确说明：**0.35 只是测试值，实际使用 0.1s**；
- 用户喜欢当前 VS Code 已装的 A 版：`30d98f9b2.neovide-cursor-1.0.1`；
- 该 A 版本地实际参数：`animationLength=0.1`、`shortAnimationLength=0.11`、`rank factors={1.0,0.72,0.42,0.22}`、`useHardSnap=true`、`leadingSnapThreshold=0.5`、`snapAnimationLength=0.02`；
- 用户要求：**只在实验开关的跨行/鼠标拖动大跨度路径使用 A-like 20ms 吸附；绝不用于打字、删除、单行短跳或连续 `ffffffff...`。**

## 2. 目标

在 `crossShapeExperiment == true` 时，`TrailCorner.jump()` 的**非短跳（跨行 / 鼠标拖动 / 键盘大跨度）分支**改为 A-like：

```text
leadingAlignment = 归一化位移方向 · 角点方向
if leadingAlignment > 0.5:
    animationLength = 0.02s
else:
    animationLength = config.animationLength × {1.0, 0.72, 0.42, 0.22}[rank]
```

要求：

- 20ms 是**正时长**，不是 0ms；不得出现 `snapAnimationLength=0` / 前缘瞬时归零 / 半边拖尾模型。
- `animationLength` 读取现有配置值（用户实际 0.1），不要写死、不要改默认值。
- `trailSize` 不参与该分支（保持当前实验的接管逻辑）。
- `crossShapeExperiment == false` 时，旧三档跨行公式逐字保留。
- 单行短跳分支不读 `crossShapeExperiment`，不读 `leadingAlignment`，不读吸附常量。

## 3. 非目标

- 不把硬吸附用于短跳、打字、删除、连续 `ffffffff...`、单行移动。
- 不复活 0ms 吸附、`useHardSnap` 0ms 模型、半边拖尾三版失败模型。
- 不改 `shortAnimationLength` / `slopeTicks` / `stretchRatioK` / `typingSmoothness` / `typingMaxChars` / `charsMoved` 判据。
- 不改预设、颜色、辉光、淡出、帧调度、`glowLayers`、`CursorPresets`。
- 不改 `core/NeovideConfig.java`（D0）、`build/install.ps1`、`build/offline-build.ps1`、既有 docs。
- 不做「缎带预设 + 鼠标实验」互斥；用户要求只记录。
- 不提交、不 push、不建分支、不建 PR、不自动安装、不启动 IDEA。
- 不新增第二个 UI 控件；现有复选框已经是用户授权的实验开关。

## 4. 允许改的文件

- `src/main/java/com/github/yyyolo7a79/neovidecursor/core/TrailCorner.java`
- `tools/settings-probe/VAddSetting.java`（同文件内 `VCrossShapeExperiment` 探针的契约更新，避免新增第 119 个 `V*.java`）
- 仅按真实门禁需要更新契约常数：`VPreset.java` / `VDefaultFreeze.java` / `VLoadState.java` / `VSwitchKeysGap.java` / `VTabPages.java` / `VerifyPanel.java`
- `tools/settings-probe/build-v-probes.ps1`（只允许加/改探针注册类名，不保留编译方式改动）
- `docs/sessions/2026-11-06-a-like-hard-snap/REPORT.md`

其余文件只读。需要改白名单外文件：停下，在 REPORT §6 写「待用户裁决」，不要自行扩大范围。

明确禁区：

- `src/main/java/.../core/NeovideConfig.java`（D0 零 diff）
- `src/main/java/.../settings/CursorPresets.java`
- `build/install.ps1`、`build/offline-build.ps1`
- `CLAUDE.md`、`START-HERE.md`、`docs/CURRENT-STATE-2026-11-04.md`、`docs/NEXT-STEPS.md`、`docs/INDEX.md`、`docs/BUG-LOG.md`、`docs/WORKFLOW.md`、`docs/SESSION-PROTOCOL.md`、`docs/UPSTREAM-COMPARE-2026-11-02.md`
- `docs/archive/` 全部
- 用户配置 XML、`%APPDATA%`、`_config-backup/`

## 5. 验收标准

| 编号 | 标准 | 怎么验 |
|---|---|---|
| A-1 | 默认关跨行四角时长仍等于旧三档基线 | 新探针调用生产 `TrailCorner.jump()` |
| A-2 | 开启后水平/近水平大跨度：前缘对齐角为 20ms，且前缘成对；不是 0ms | 新探针参数扫描 |
| A-3 | 开启后非吸附角使用 `{1.0,0.72,0.42,0.22}` × `animationLength` | 新探针 |
| A-4 | 单行短跳（含连续 `ffffffff...`）开关 true/false 四角时长逐位相同 | 新探针 + `VTypingSpan` / `VHints` 仍绿 |
| A-5 | 硬吸附不进入短跳分支；源码剥注释后短跳分支无吸附常量读取 | 新探针静态断言 |
| A-6 | `crossPreview` 消费 A-like；`inlinePreview` / `glowPreview` 不消费 | 新探针 + 现有预览探针 |
| A-7 | 选区/Tab 等大跨度路径允许受实验影响；不新增互斥 | 只记录，不额外断言 |
| A-8 | 全量门禁 `ALL PASS`、D0 零 diff、37 个源码编译 | 既有命令 |
| A-9 | 三组变异（默认改 true / 吸附放进短跳 / crossPreview 不接开关）均红，恢复后全绿 | 变异测试 |
| A-10 | 只打包不安装：产出 A-like 实验 jar，REPORT 写清路径、版本、SHA256 | `offline-build.ps1` |
| A-11 | REPORT 写全命令、门禁数字、变异、未验证项；不写「手感已好」 | 人工抽查 |

## 6. 必跑命令

在 worktree 下执行；门禁串行，禁止并发。当前 worktree 路径过长，原脚本源码 javac 会超 Windows 命令行上限；使用已有的短 `-Libs` 联接方式，不修改脚本编译方式：

```bash
R="D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/sleepy-swanson-ffc2cf"
# 如果 D:\libs 不存在：pwsh -NoProfile -Command 'New-Item -ItemType Junction -Path D:\libs -Target D:\deepseekwork\_idea-anim\idea-libs'
pwsh -NoProfile -ExecutionPolicy Bypass -File "$R/tools/settings-probe/build-v-probes.ps1" -Repo "$R" -Libs "D:\libs" > "$R/build/a-like-final-gate.log" 2>&1
G=$?
grep -E 'V-PROBES RESULT|VCrossShapeExperiment|V-CROSS-SHAPE|exit=[1-9]' "$R/build/a-like-final-gate.log"
printf 'GATE_EXIT=%s\n' "$G"

git -C "$R" diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
printf 'D0_EXIT=%s\n' "$?"
```

变异测试：每次变异后删除 `build/v2-probe/classes` 全量重编译，记录红名单，恢复源码后重跑全绿。至少：

```text
M1：State.crossShapeExperiment 默认 false → true → 默认关断言必红
M2：把 20ms 吸附误放进短跳分支 → 单行/连续 f 断言必红
M3：crossPreview 不推开关 → 预览同源断言必红
```

只打包、不安装（先确认底包在 `D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.14-conclusion.jar`）：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 -Version 0.9.136-a-like-snap -OutName neovide-cursor-0.9.136-a-like-snap.jar -BaseJar D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.14-conclusion.jar
```

若长路径使打包失败，可用短路径目录联接执行脚本，不修改脚本；不得 `install.ps1`。

## 7. 探针契约变更说明

当前 `VCrossShapeExperiment` 的 X-4c「四角四档不同」与 X-6a「行为层零硬吸附」是为旧 no-snap 公式写的，A-like 会改变契约：

- 前缘吸附角会得到相同 20ms，不能再断言「四角四档不同」；
- X-6a 必须改成「默认关无吸附；实验开时仅跨行分支允许 20ms A-like 吸附」，不能删掉断言或改成空断言；
- 必须新增「短跳分支零吸附」的静态/行为断言；
- 旧断言方向变更要在 REPORT 写「契约变更，非缺陷」，并做变异测试。

## 8. 报告要求

写到 `docs/sessions/2026-11-06-a-like-hard-snap/REPORT.md`，包含：

- 结论：完成 / 部分完成 / 被阻塞；
- 与当前 2026-11-05 报告的关系：这是同一实验开关的 A-like 变体；
- 改动清单（文件、行为、为什么）；
- 命令与关键输出；门禁数字；D0；三组变异红/绿；
- 置灰清单：开启后 `trailSize` 仍置灰；`animationLength` 按本轮公式仍是时间基准，是否置灰由实现决定并写清理由；
- 未验证项：未安装、未实机 A/B、未替用户裁决「更像 A」；
- 缎带预设 + 鼠标实验「只记录、不互斥」；
- 遇阻写 §6「待用户裁决」，不等待、不自行扩权。

## 9. 停止线

在「A-like 20ms 只在实验开关的跨行/鼠标大跨度分支、单行/连续 f 完全不变、门禁全绿、D0 零 diff、三组变异有证据、只打包不安装、REPORT 完整」之后停止。不要提交、不要安装、不要启动 IDEA；用户明天回来做 A/B。
