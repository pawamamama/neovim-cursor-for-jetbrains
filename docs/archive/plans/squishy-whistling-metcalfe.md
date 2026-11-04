# 重构：RUNTIME_SWITCH_KEYS → runtimeSwitch 字段标志

## Context

自定义预设快照包含 26 个字段，其中 9 个是「设备/环境级」的（帧率跟屏幕走、光标宽度跟字体走、诊断日志是排查开关），恢复预设时**必须跳过**，否则「切一个自定义预设」会把用户的帧率改掉、甚至把整个插件关掉。

「跳过哪些」现在写成一份独立的字符串数组 `SettingsField.RUNTIME_SWITCH_KEYS`（`SettingsField.java:645`），与字段表 `FIELDS` 分家维护。已经出过事故：0.9.87 之前只有 4 项，漏了 5 个设备级字段。补到 9 项修好了，但病根还在 —— 新增设备级字段时要记得同步敲字符串，敲错编译器不报错，静默失效。

本次把「要不要跳过」从外部清单搬进字段自身的 `runtimeSwitch` 布尔，让判断与字段定义同处一行。

**更大价值在探针侧**：全仓没有任何探针调用 `restoreSkipping`，这条关键路径目前零覆盖。本轮补上真实行为断言。

## 与交接文档的四处偏差（已审查确认）

| # | 文档方案 | 本次采用 | 理由 |
|---|---|---|---|
| 1 | 5 个工厂各加参数 → 26 个调用点全改 | **工厂加重载委托**：原 6 参数版本保留，新增 7 参数版本，仅 9 个 runtime 字段改用新签名 | 17 个非 runtime 调用点一字不动，diff 从 ~140 行降到 ~90 行，审计面更小；R7 更宽松 |
| 2 | 未提及 `presetPointer()` | **必须显式传 `false`** | `SettingsField.java:378` 是第 6 个构造器调用点；它是「当前选中了谁」的指针，既不进预设也不该跳过，传 `true` 会改语义 |
| 3 | 「删掉 `RUNTIME_SWITCH_KEYS` 数组」 | **数组删，但上方 40 行 javadoc 必须迁移** | 那段（`:605-644`）承载「判定标准」「0.9.87 补齐 5 项的历史」「为什么颜色字段不在清单里」三条知识；直接删等于丢文档 |
| 4 | 「保留 `skip` 参数但不使用」 | **直接删 `String... skip` 参数，两处调用同步改** | 留着不用的参数就是死代码，违反纪律四-J；只有 2 个调用点，一次改干净 |

## 改动清单

### 生产代码（3 个文件）

**1. `src/main/java/com/github/yyyolo7a79/neovidecursor/settings/SettingsField.java`**

- 新增 `public final boolean runtimeSwitch;`，javadoc 迁移自原 `RUNTIME_SWITCH_KEYS` 上方的那段（判定标准 + 0.9.87 历史 + 颜色字段说明），并按纪律四-M 写中文说明。
- 构造器末尾加 `boolean runtimeSwitch` 参数并赋值。
- 5 个工厂方法（`bools`/`ints`/`floats`/`hexColors`/`enums`）各拆成两版：
  - 原签名 → 委托给新版，传 `false`（17 个非 runtime 字段零改动）
  - 新签名（多一个 `boolean runtimeSwitch`）→ 实际构造
- `presetPointer()` 构造时传 `false`。
- 9 个字段改用新签名传 `true`：`enabled`、`trailMode`、`hideNativeCaret`、`alwaysHighFps`、`targetFps`、`diagLogging`、`multiCursorNoGlow`、`cursorWidthPx`、`cursorHeightRatio`。
- `restoreSkipping` 改签名为 `(State s, String snapshot)`，内部判据换成 `if (f.runtimeSwitch) continue;`，顺带更新其 javadoc 里「四个运行环境开关」的过时表述（现为 9 个）。
- 删除 `RUNTIME_SWITCH_KEYS` 数组（javadoc 已迁移）。

**2. `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CursorPresetApplier.java:114-116`**
- 去掉 `RUNTIME_SWITCH_KEYS` 实参。

**3. `src/main/java/com/github/yyyolo7a79/neovidecursor/ui/settings/NeovideCursorConfigurable.java:1775-1776`**
- 同上；同步修正上方注释里「4 个运行环境开关」的过时表述。

### 探针（2 个文件）

**4. `tools/settings-probe/VSwitchKeysGap.java` —— 改方向 + 补行为断言**

现有断言只检查「9 个字符串在不在数组里」，测的是声明。改为三条：

- **A（正向）**：`FIELDS` 里 `runtimeSwitch == true` 的 key 集合 == 预期 9 个。
- **B（反向）**：其余 17 个字段 `runtimeSwitch == false`（防止把观感字段错标）。
- **C（行为，本轮核心）**：构造源 State，把 9 个 runtime 字段设成与默认相反的「毒值」→ `SettingsField.snapshot(src)` → 用一个全新的 `dst` State 调 `restoreSkipping(dst, snap)` → 断言：
  - 9 个字段保持 `dst` 原值（未被写入）
  - 至少 3 个非 runtime 字段（如 `slopeTicks`/`trailSize`/`glowLayers`）确实被写入

顺带把输出格式改成 `  PASS  xxx` / `  FAIL  yyy`（前导两空格），使 `build-v-probes.ps1:195-196` 的正则 `^\s+PASS ` / `^\s+FAIL ` 能统计到 —— 目前它在汇总表恒为 `0 0 0`，等于门禁看不见它断言了几条。**改格式后必须保持全绿**，否则基线会从 8 变 9。

**5. `tools/settings-probe/VLoadState.java:100-103`**
- L-5 改为数 `FIELDS` 里 `runtimeSwitch == true` 的字段数，断言 `== 9`；删除对已删数组的引用（否则编译失败）及随之无用的 `java.util.Arrays` 用法。

### 不改的文件

`build-v-probes.ps1` 无需改（文件清单不变）。`run-v-mutations.ps1` 不改（变异手工做并当场还原）。

## 执行步骤

**步骤 1：只加标志，不改行为。**
改 `SettingsField` 的字段/构造器/工厂重载/`presetPointer`/9 个调用点。`restoreSkipping` 内部仍用旧数组，数组暂留。
验证：编译通过 + 全量探针仍 8 个未通过。

**步骤 2：切换判据。**
`restoreSkipping` 改用 `f.runtimeSwitch`，删 `String... skip` 参数，同步改两个调用点。
验证：全量探针仍 8 个未通过。

**步骤 3：删数组 + 改两个探针。**
验证：编译通过 + D0 + 全量探针 8 个未通过 + `VSwitchKeysGap` 单独跑全绿。

**步骤 4：变异测试（两条，缺一不可）。**
- **变异 X（测行为）**：删掉 `restoreSkipping` 里的 `if (f.runtimeSwitch) continue;` → 断言 C 必须变红。
- **变异 Y（测声明）**：把 `targetFps` 的 `runtimeSwitch` 改 `false` → 断言 A 必须变红。
- 两条都必须当场还原，还原后 `git diff` 确认干净、重跑全绿。
- 只做 Y 不做 X 等于没验行为 —— 这是交接文档步骤 4 的漏洞。

## 验证

全部门禁在本 worktree 跑，**必须带 `-Repo`**（脚本默认指向主仓库，不带就等于没验证本目录的代码）：

```bash
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo "D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\quizzical-raman-7c159c"
```

- **基线**：8 个未通过（`VDefects1 VSnap10 VTools1 VHints1 VCustom1 VReset2(2) VSepBound7 VSleep1`），重构后必须仍是这 8 个，一个不多一个不少。
- **D0**：`git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java` 返回 0。注意盲区 —— 提交后 D0 看不见，所以检查放在提交前。
- **逐行看 `git diff`**：重点确认 17 个非 runtime 字段的调用点真的没被动。
- **不并发跑两份门禁**（本 worktree 有独立 `build/v2-probe`，物理上不会撞，但仍按纪律串行）。

## 明确不做

- 不 commit、不打包、不装 jar —— 改动留在工作区等你过目。
- 不让 `PRESET_FIELDS` 排除 runtime 字段。现在的快照仍存 26 项、恢复时跳过 9 项，「存了不用」是冗余但不影响行为；改它等于改快照格式，超出本次范围。
- 不碰 `copyFrom` / `loadState` / 面板 / 闪烁 / 辉光问题（红线 R2、R3、R8）。
- 不新建分支，用当前 `claude/quizzical-raman-7c159c`。
