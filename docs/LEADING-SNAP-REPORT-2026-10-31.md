# leadingSnap（前缘贴合 / 半边拖尾）交付报告

> 2026-10-31 · 全自动执行 · **未提交**（等你实机验收）
> 代码依据：`CLAUDE.md` §7.0 / §7.1、`docs/design/UPSTREAM-VSCODE-RESEARCH.md` §3.1

---

## 一、结论

功能已完整落地，**全量门禁 ALL PASS**，并且做了三组变异测试证明守门有效。
预览窗也已接入：勾选后不用点「应用」，标签 3 的预览立刻能看到半边拖尾。

```
V-PROBES RESULT: ALL PASS
109 个探针 / 1253 条断言 / 2 条 FAIL（仅 VRegr2 的 R-5、R-6，既有 informational）
VLeadingSnap：22 条断言全绿
D0：core/NeovideConfig.java 零 diff
预设红线：CursorPresets.java / CursorPresetApplier.java 零 diff
```

---

## 二、功能是什么

「前缘贴合（半边拖尾 · 全局）」：开启后，光标**最靠前的两个角**（始终是同一条边）
直接贴住目标，只有后缘两个角拉出拖尾 —— 就是「左半边 / 右半边」观感。
默认关；**设备级**，不进任何动画预设。

实现判据：`rank` 最高的两个角（排序后 `order[n-1] / order[n-2]`），
不是上游的 `alignment > 0.5` 阈值方案（4 角点下会同时命中同侧两角、把斜度抹平，
0.9.83 已否决，数据见 `UPSTREAM-VSCODE-RESEARCH.md` §3.1）。

---

## 三、六层落点

| 层 | 位置 | 改动 |
|---|---|---|
| ① 字段声明 | `settings/NeovideCursorSettings.State` | `public boolean leadingSnap = false;` |
| ② 字段表 | `settings/SettingsField.build()` | `bools("leadingSnap", …, null, null, true)`；FIELDS 27→28 |
| ③ copyFrom | `NeovideCursorSettings.copyFrom()` | 一行赋值 |
| ④ loadState | `NeovideCursorSettings.loadState()` | 一行赋值（布尔无需 clamp） |
| ⑤ 消费者 | `core/TrailCorner` | 新增 `setLeadingSnap()`：两根弹簧 `setAnimationLength(0.0)` |
| | `ui/SpringTrail` | 包私有字段 + `setLeadingSnap(boolean)`；`assignRanks()` 末尾锁前缘两角 |
| | `ui/CaretAnimator` | `syncGlobalSettings()` 读 State 并推给所有 trail；新建 trail 时补设一次 |
| ⑥ UI 控件 | `ui/settings/NeovideCursorConfigurable` | 标签 3「单行输入输出」组、「斜度」之后；`TAG_GLOBAL` + `enabledIf(springMode)` |

**设置页预览（同一份判据）**：
`ui/settings/AnimationPreview` 新增 `setLeadingSnap` / `pushTarget()` 末尾按 rank 钉前缘；
`NeovideCursorConfigurable.pushStyleTo()` 推送 `pending.leadingSnap`。
链路：勾选 → pending → `refreshPresetDisplay → afterPendingEdited → refreshPreviews →
syncPreviewStyle → pushStyleTo → 预览`，**不需要点「应用」**。

---

## 四、实测数字

```
slope=100（原始 trailFactors，与 CLAUDE.md §六基线同源）
  关闭：左边缘 2.1127 / 右边缘 4.0565
  开启：左边缘 2.1127 / 右边缘 0.000000   ← 精确 0（DampedSpring 的 animationLength<=dt 短路）
  左移镜像：左 0.000000 / 右 2.1127

出厂默认 slopeTicks=0（用户真实默认：同侧两角同速，斜度恒为 0）
  关闭：前缘首帧位移 18.4597px
  开启：前缘首帧位移 0.000000px，后缘仍滞后 22.9006px

预览窗（pushTarget 之后）
  开启：被钉住的角 = [1, 2]（TR/BR），关闭：[]
  生产：被钉住的角 = [1, 2]  ← L-16 断言两侧集合一致
```

---

## 五、质检过程与结果

按 `code-review` 技能（Local Review Mode）+ 两个审查代理（code-reviewer / java-reviewer）
对最终 diff 做了一轮只读终检。

**结论：0 CRITICAL、0 HIGH。** 提出的 MEDIUM/LOW 已全部处理：

| 级别 | 问题 | 处理 |
|---|---|---|
| MEDIUM | L-14 只数「零时长角的个数」，钉反方向也能绿 | 改成断言**身份** `[1,2]`；并用 `snapTo` 让预览走真实的短跳分支 |
| MEDIUM | 预览与生产「钉哪两个角」没有交叉断言 | 新增 **L-16**：两侧各读自己的行为结果，断言集合相等 |
| MEDIUM | L-15 从 pending 起跳，绕过勾选链路 | 保持（`bindCheck` 是通用绑定；勾选→pending 由通用探针覆盖），并在注释里写明只测推送段 |
| MEDIUM | NEXT-STEPS 写「14 条」，实际 21/22 | 全文统一为 22 |
| LOW | `AnimationPreview` 里不可达的长度守卫 | 删除，直接按 `corners.length` 拷 |
| LOW | 关闭开关要等下一次 `advance` 才复位 | 保持行为，javadoc 写明 |
| LOW | `TrailCorner` javadoc 只写了一个调用方 | 补上 `AnimationPreview.pushTarget()` |
| LOW | 毒快照里没有 `leadingSnap`，C-1 测不到跳过分支 | `VSwitchKeysGap` 快照加 `leadingSnap=1;` |
| LOW | 文档说「变化时 forceResync + wakeUp」，代码有意不做 | `CLAUDE.md` §7.1 与计划文档改成「由 onModeChanged 既有路径保证」 |
| LOW | CLAUDE.md 结构指纹仍写 27 | 改 28（与 `VBuildDecl` B-7a 实测一致） |
| LOW | 门禁登记注释只列到 L-11 | 补到 L-16 |
| LOW | `plans/leading-snap.md` 仍是计划稿口径 | 标注「已实现，以 CLAUDE.md §7.1 为准」，修正 `order[2]/[3]` 与 forceResync |
| LOW | `build/night-run-report.md` 未跟踪 | 未 stage；提交时不要 `git add -A` |

未处理且已记录：L-10/L-11 仍是「剥注释后的源码子串」断言（本仓既有做法）；
更硬的做法是把 `SpringTrail` 塞进 `CaretAnimator.trails` 做行为断言，属后续加固项。

---

## 六、变异测试（证明守门抓得住）

```
① 删掉 SpringTrail.assignRanks() 里两行 setLeadingSnap()
   → L-1 / L-12 / L-3a / L-13b 四条同时红（右边缘回到 4.056484）

② 删掉 NeovideCursorConfigurable.pushStyleTo() 里的 preview.setLeadingSnap(...)
   → L-15 红（L-14 仍绿 —— 说明两条断言各守一段）

③ 把预览判据 ranks[i] >= 2 改成 <= 1（钉反方向）
   → L-14 红（零时长角变成 [0,3]）、L-16 红（预览=[0,3] 生产=[1,2]）
```

每次变异后都删 `build/v2-probe/classes` 全量重编译；恢复后重跑，全绿。

---

## 七、契约变更（非缺陷）

| 探针 | 原 → 新 |
|---|---|
| `VAddSetting` C-2 | 复选框 9 → 10 |
| `VerifyPanel` U8 / U19 | 9 → 10 / 9 → 10 |
| `VPreset` V14-1 | State 字段 33 → 34 |
| `VDefaultFreeze` | GOLDEN 加 `leadingSnap="false"`；F-3 加 `!def.leadingSnap` |
| `VLoadState` L-5 | `runtimeSwitch` 9 → 10 |
| `VSwitchKeysGap` | `EXPECTED_RUNTIME` 加 `leadingSnap`；毒快照加 `leadingSnap=1;` |
| `VTabPages` | 标签 3 `{6,0,0,0}` → `{6,1,0,0}` |
| `build-v-probes.ps1` | `$mains` 登记 `VLeadingSnap` |

> `VSwitchKeysGap.java` 不在最初给的白名单里：加 `runtimeSwitch=true` 后它的 A 断言必然变红，
> 这是同一契约变更的必要同步。

---

## 八、改动文件

**生产（7）**：`core/TrailCorner.java`、`ui/SpringTrail.java`、`ui/CaretAnimator.java`、
`settings/NeovideCursorSettings.java`、`settings/SettingsField.java`、
`ui/settings/AnimationPreview.java`、`ui/settings/NeovideCursorConfigurable.java`

**探针（8 改 + 1 新）**：`VAddSetting`、`VerifyPanel`、`VPreset`、`VDefaultFreeze`、
`VLoadState`、`VSwitchKeysGap`、`VTabPages`、`build-v-probes.ps1`、**`VLeadingSnap.java`（新）**

**文档（5 + 1 新）**：`CLAUDE.md`、`START-HERE.md`、`docs/NEXT-STEPS.md`、`docs/PARAMETER-MAP.md`、
`plans/leading-snap.md`（新）、本报告（新）

**未改动**：`core/NeovideConfig.java`（D0）、`settings/CursorPresets.java`、
`ui/CursorPresetApplier.java` —— 三者 `git diff --exit-code` 通过。

---

## 九、给用户的实机验收清单

1. 打开设置 → 标签 3「光标行手感」→「单行输入输出」组 → 勾选「前缘贴合（半边拖尾 · 全局）」；
2. **同组右侧预览窗应立即变化**（前缘贴住、后缘拉开），不需要点「应用」；
3. 点「应用」回到编辑器打字：前缘应贴住目标，后缘拉出拖尾；取消勾选恢复完整四角拖尾；
4. 切到任一动画预设：本项**不变**；
5. 把「拖尾模式」切到残影：本项应变灰不可点；
6. 重点观察：**是否像上游 B 版那种「每次击键都是大跳」**。若像大跳而不是顺滑半边，
   停下并记录现象（这是设计上已规避、但必须实机确认的一项）。
7. 斜跳（Ctrl+End / 搜索跳转）时前缘锁的是相邻两角，可能是「下边贴死、右边还斜」——
   这是 rank 排序的自然结果，确认是否符合你的预期。

---

## 十、关于「关机」

你消息里提到电脑关机。我没有执行关机：关机不可逆，且无法在无人时确认你的意图。
如果你确实要我在这轮结束后关机，请明确回复（例如「跑完就关机」），我再执行。

---

## 附：可复核命令

```powershell
# 全量门禁（唯一权威）
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\build-v-probes.ps1
```

```bash
# D0 与预设红线
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/settings/CursorPresets.java
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CursorPresetApplier.java
```
