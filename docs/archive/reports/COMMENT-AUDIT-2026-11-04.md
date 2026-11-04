# 注释审查记录（2026-11-04）

> 本轮只改注释 / javadoc / 文档状态行，**不改逻辑、常量、断言、资源、构建脚本**。
> 约定本身写在 `CLAUDE.md §8.5`（`@verified` / `@stale` / `ASK-HUMAN`），本文只记证据。

---

## 1. 范围

用 15 组模式 grep `src/main/java` + `tools/settings-probe`：

```
MAX_DT | 1/30 | 33.3 | 55 ms | shortAnimationLength * 0.5 | Math.max(leading
charsMoved >= 4 | 误判 | 误切 | leadingSnap | useHardSnap | 半边拖尾 | 前缘贴合
crossMoveFloor | floorLeading | floorNote | 已触底 | at the floor
0.4.3 | 0.9.130 | 0.9.132 | 0.9.133 | Step B | Step C
```

结果：**162 处命中 / 33 个文件**。逐条分类：

| 类别 | 数量 | 处理 |
|---|---:|---|
| 真过期，且可用代码/探针直接验证 | 9 | 已改（见 §2） |
| 历史依据（「原值 1/30」「0.9.90~0.9.130 曾加过」） | 多数 | 保留，它们是「为什么改成现在这样」的证据 |
| 普通词「误判」（与 BUG-016 无关） | 多数 | 不动 |
| 两套说法无法判断对错 | **0** | 本轮没有出现；将来出现时用 `ASK-HUMAN` |

---

## 2. 已改的 9 处

| # | 位置 | 原注释 | 现注释 | 依据 |
|---|---|---|---|---|
| 1 | `CaretAnimator` `MAX_DT` javadoc | —（新增） | 加 `@verified 2026-11-04`：`MAX_DT = 1.0/20 = 50ms` | 下一行常量就是 `1.0/20`；`tick()` 的 recovery 分支读 8ms / rawDt |
| 2 | `CaretAnimator` `RECOVERY_GAP_SECONDS` javadoc | 被 `MAX_DT` 截断成 **33.3ms**，弹簧一帧走完 **93%** | 50ms / **98%**，并写明 >100ms 走 recovery 分支 | `MAX_DT=0.05s`；`c=exp(-(4/0.05)×0.05)=0.018` |
| 3 | `CaretAnimator` `wakeUp()` 注释 | `MAX_DT = 1/30 ≈ 33.3ms`；`dt=0.033 → c≈0.070` | `1/20 = 50ms`；`dt=0.050 → c≈0.018` | 同 1；`exp(-4)=0.0183` |
| 4 | `CaretAnimator.syncGlobalSettings()` javadoc | 「这**四项**……前缘贴合」 | 「这些设备级设置……」；列表改为「多光标不辉光 / 光标宽高 / RGB 渐变 / 目标帧率 / 彩虹反推」 | 方法体实际读 5 个 State 字段 + `resolveRgbWash()`，**不读** `leadingSnap` |
| 5 | 同文件 catch 注释两处 | 「四项全局设置」「重置四项默认值」 | 「这些全局设置」「这些默认值」 | 同上；catch 里重置的是 6 个赋值 |
| 6 | `NeovideCursorConfigurable` `W_TRAIL_SIZE` javadoc | 「误判本身留给 Step B」 | 「仍是 0.9.131 的已知行为；Step B 已于 2026-11-04 撤回」 | `fddd529` 撤回提交；`VTypingSpan` T-2b/T-7 契约 |
| 7 | `NeovideCursorConfigurable.syncPreviewStyle()` javadoc | 推送「尺寸 / 渐变 / 彩虹 / **前缘贴合**」 | 去掉前缘贴合，注明已回退 | `grep leadingSnap` 在 `ui/settings/` 只剩面板控件，无预览消费点 |
| 8 | `AnimationPreview` `lastRanks` 注释 | 「（探针读取）」 | 「（当前无读取者，见字段声明）」 | `grep lastRanks` 只有声明与 `arraycopy`，无外部读取 |
| 9 | `CaretTrailPanel` 双拼系数注释 | 10 处清单见 `docs/NEXT-STEPS.md` | 见 `CLAUDE.md §7.2` | NEXT-STEPS 已无「双拼 / 参数条」条目；CLAUDE.md §7.2 有该待办 |

另外一处同批修改：`tools/settings-probe/build-v-probes.ps1` 的注册注释
「留给 Step B」→「Step B 已尝试并撤回」。

---

## 3. 自检时发现并修复的门禁失败

改 CLAUDE.md 状态块时破坏了 `VDocCheck` 的两条契约（**这是真问题，不是文档洁癖**）：

```
FAIL D-1  CLAUDE.md=null / START-HERE.md=0.9.127 / NEXT-STEPS.md=0.9.127
FAIL D-3  CLAUDE.md:101 → fddd529（写死了 7 位 hash）
```

修法：

- `CLAUDE.md` 版本行恢复 `版本         v0.9.131` 的机器可读格式；
- 删掉硬编码 hash，改回「见 `git log -1`」；
- `START-HERE.md` / `NEXT-STEPS.md` 版本同步到 `v0.9.131`，tag 仍记 `v0.9.127`。

修后门禁：**`V-PROBES RESULT: ALL PASS`**。

---

## 4. 验证

```
V-PROBES RESULT: ALL PASS
git diff --exit-code -- src/main/java/.../core/NeovideConfig.java  → 0（D0 零 diff）
git diff -- src tools 过滤后只剩 // * /* # 注释行 → 无逻辑改动
```

---

## 5. 以后怎么做

见 `CLAUDE.md §8.5`。一句话：**能一条命令验证的，不问；验证不了的，写
`ASK-HUMAN(日期) 说法A vs 说法B | 验证方式`，读到的人必须停下问用户。**
