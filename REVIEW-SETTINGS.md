# REVIEW-SETTINGS.md —— 设置界面（0.9.4-settings）契约逐条裁决 + SHA256 绑定审查

> **审查者**：reviewer（t5 首轮 / **t8 第二轮复审**）
> **被审对象**：t7 修复（对 t5 六条 findings 的处置）；t2/t3 实现承前
> **裁决依据**：`SETTINGS-CONTRACT.md` **v2**（v1 冻结 + §8 修订记录；修订由 t5 的 R-04/R-06 触发）
> **工作目录**：`D:\deepseekwork\neovim-cursor-pr`
> **审查纪律**：只审查，**未改 `src/` 任何字符**；未装 jar、未重启 IDEA。所有数字都是我自己重跑/自算得到的。

---

## 0. 最终裁决（第二轮）

| 项 | 值 |
|---|---|
| **verdict** | **pass** |
| 六条 findings 状态 | **R-01…R-06 全部关闭** |
| blocker / high / medium | 0 / 0 / 0 |
| 本轮新发现 | 1 条 **low**（N-1，三方均未提及；不影响验收） |
| 契约条文 | 全部 PASS（v2 正文与 §8 修订记录一致） |
| 三件套 + 探针 | 全部 exit 0（我独立重跑） |

**结论**：t5 提出的 2 high / 1 medium / 3 low **全部按契约要求关闭**，且我把三条真 bug 的修复做了**反向验证**（把修复回滚 → 断言变红），证明新增探针真的有效，而不是"探针通过"。契约 v2 的 R1–R7 修订已在正文落实、未越界改实现。唯一新发现是一条 low 级交互边界，不构成阻塞。

---

## 1. SHA256 绑定表（本轮全部重算）

| # | 对象 | SHA256 | 大小 | 说明 |
|---|---|---|---|---|
| 1 | `SETTINGS-CONTRACT.md`（**v2**） | `8A5C10698563799037BF21DE9B3EF1579C4D3D53721B48AB98C77D2E12A49A6B` | 71,944 B / 779 行 | **v1 冻结值 `4EAD982A…` → v2 已变更**，变更范围见 §4 |
| 2 | `VERIFY-SETTINGS.md`（本轮证据） | `DFA8651971C1A629CA790FAB52207E49299D9CF1EE1F6B7DDCA4254F2E4EA179` | 61,965 B / 1,073 行 | v1 时为 `68C059BB…`（59,603 B / 1,046 行） |
| 3 | `LESSONS.md`（方法论） | `E9515FA7DF525F0BBFAB5B94FD040A5FB961AFCD63BDEE6EA8A68B087475FC7C` | 16,263 B / 422 行 | 未变 |
| 4 | `REVIEW-SETTINGS.md`（本文，首轮） | `0F8782B40A1BE77DA82FDDA1FBBA74039D92D878E1EA663799C4ED5962072ED4` | 39,229 B / 335 行 | 首轮版，本轮已更新 |
| 5 | **构建产物** `dist\settings-ui-smoke.jar` | `32DB05D8CB1384206A691AE4F3424D5BA1507983942FF1435FC39E2CD7A6D7C8` | 70,056 B | mtime 2026-10-19 22:42:40（t7 重建；首轮为 `E04B4D31…` / 67,883 B） |
| 6 | **git diff SHA256**（`git diff HEAD` 全量） | `FEBE531378FC84985572D2EA71123F22D196416096A3EF4D95B99C02B6AF9FFB` | 7 files, +2315/−59 | 首轮为 `9F923080…` / 5 files +1626/−52 |
| 7 | **HEAD** | `ba67ed383614ca865f1395be1a0a2eede94da28f` | — | 与契约 §0 记录一致，**未变** |

**一致性交叉校验**：
- jar 内所有 `.java` 与 `resources` 的 mtime 均**不晚于** jar（新于 jar 的 .java = 0，resource = 0）→ **jar 不是过期产物**，与 t7 源码同步。
- jar 内含 **`NeovideCursorSettings$PersistResult.class`** 与 **`NeovideCaretManager$Snapshot.class`** → 证明 R-02/R-03 的修复代码确实进了产物，不只是留在源码里。
- jar 内 `plugin.xml` 含注入的 `<version>0.9.4</version>`，且 `instance=` 指向 `ui.settings.NeovideCursorConfigurable`（与 v2 契约一致）。
- **契约 v2 有自己的版本记录**（§8 R1–R7），不是"悄悄改掉冻结文本"——这一点很重要，见 §4。

---

## 2. 六条 findings 的关闭裁决

| ID | 原 severity | 关闭 | 证据位置（file:line + 断言编号） |
|---|---|---|---|
| **R-01** | high | ✅ **关闭** | `NeovideCaretManager.java:133-153`；断言 `NonEdtProbe` D1：「state.enabled=false 时 editorCreated 后 ANIMATORS 为空」+「CONFIG.enabled 已同步为 false」+ 对照组「enabled=true 时有 1 个」 |
| **R-02** | high | ✅ **关闭** | `NeovideCaretManager.java:445-463`（`Snapshot` :466-483 / `applyConfigOnEdt(@NotNull Snapshot old)` :486）；断言 D2：「非 EDT 调用后入队」「CONFIG 立刻可见新值」「EDT 执行完 saveSettings ≥ 1」「setter 真的落了盘」 |
| **R-03** | medium | ✅ **关闭** | `NeovideCursorSettings.java:206-226`（switch 三态）/ `:406-466`（`persistQuietly`）/ `:480-487`（`PersistResult` 枚举）；断言 D3 共 6 条（DEFERRED 版本号=1 / 不删键 / 值已搬 / EDT 后删键 / 收敛仍 1 / 刷盘失败保留键） |
| **R-04** | low | ✅ **关闭** | `kFromTrailFactors()` 已从源码**删除**（我逐文件 grep：src 命中 **0**，连方法签名 `public static float kFromTrailFactors` 也是 0）；契约 §2.5 该条改为【v2 裁决：第一期不做】并给出三条理由（正文 :170-181） |
| **R-05** | low | ✅ **关闭** | `VERIFY-SETTINGS.md:602-632` 新增「口径说明」+ 5 场景全表的跨遍跨度；R4 单独说明（:619-629）。**我逐格对账 50/50 一致**，见 §3 |
| **R-06** | low | ✅ **关闭** | 契约 v2 §3.1.6(`:333`)/§3.1.7(`:343/:348/:361-363`) 已改为 `ui.settings.NeovideCursorConfigurable`；§8 表 R1/R2/R3 记载该笔误 |

**判定**：六条**全部关闭**，无残留。

---

## 3. 反向验证（本轮最重要的一节）

> 探针"通过"不等于"探针有效"。**只有把修复回滚后断言变红，才算证据。**
> 我把源码复制到 `build/t8-scratch/mut/`（不碰工作区 `src/`），逐条还原成修复前的形态，重编译后跑 `NonEdtProbe`。

| 变异 | 还原成什么 | `NonEdtProbe` 结果 | 结论 |
|---|---|---|---|
| **R-01** | 把单条 `if(!CONFIG.enabled) return;` 放回方法最开头，删掉同步后的两次重判（忠实还原 t5 描述的原始形态） | **通过 12，失败 1**；`FAIL R-01 修后：state.enabled=false 时 editorCreated 后 ANIMATORS 为空  实际 size=1` | **探针有效** ✔ |
| **R-02** | 把 `syncStateToConfig()` 移回 `snapshot()` **之前**（快照拍到新值） | **通过 11，失败 2**；`FAIL … saveSettings ≥ 1  实际本次新增 0`、`FAIL … 落了盘  新增 0 次` | **探针有效** ✔ |
| **R-03** | DEFERRED 分支改回「回退版本号 + 立刻删键」 | **通过 9，失败 4**；版本号 1→0、旧键仍在 false、收敛后仍 1→0、刷盘失败保留 false | **探针有效** ✔ |

> 第一次做 R-01 变异时我只插了顶部早退、保留了同步后的重判，探针仍全绿 —— 说明**变异必须忠实**。改成"删掉重判"的忠实还原后立刻变红。这个细节本身就是"探针确实在测修复点"的旁证。
> 变异副本已清理（`build/t8-scratch/mut` 已删除），工作区 `src/` 未被触碰。

**关于 captain 的疑问「NonEdtProbe 覆盖得到 R-01 吗」**：**覆盖到了**。R-01 虽然不是线程问题，但该探针的假平台提供了 `EditorFactory` 桩（`NonEdtProbe.java:307-309`，走 `Application.getService(EditorFactory.class)`），所以能真正驱动 `editorCreated` 并数 `ANIMATORS`。D1 段三条断言正是 R-01 的专项断言，且我上面的变异证明它会失败。

---

## 4. 契约 v2 的修订审查（captain 重点④）

**裁决：修订合法、已落实、未越界。**

§8「v2 修订记录」列出 R1–R7。我逐条核对"是否只在附录表里写了、正文没改"：

| 记录 | 正文是否真的落实 | 位置 |
|---|---|---|
| R1 XML 片段类名 | ✅ 落实 | §3.1.6 `:333` `instance="…ui.settings.NeovideCursorConfigurable"` |
| R2 类骨架类名 | ✅ 落实 | §3.1.7 `:343` `class NeovideCursorConfigurable extends BoundConfigurable`、`:348` 构造器 |
| R3 包名 `ui/settings/` | ✅ 落实 | §3.1.7 `:361-363` 明确写出「实际落点（v2 更正）」及职责划分理由 |
| R4 §2.5 非规范数组 → 第一期不做 | ✅ 落实且**自洽** | 正文 `:170-181`：标题已改【v2 裁决：第一期不做】，并给出三条理由（不可达 / 反推会漂移 / 与"OK 生效"冲突），明写「v1 里那个 `kFromTrailFactors()` 已从源码删除」。我核对源码：该方法**确实已删**（grep 0 命中）。**契约与实现自洽** ✔ |
| R5 S7 必须补 `refreshGlowPadding()` | ⚠️ **正文未改，仅在 §8 表**（见 N-2） | §3.3.2 的 S0–S8 伪码 `:437-445` **仍是** `S7 for(...) a.onModeChanged();`，没有 `refreshGlowPadding()`；§3.3.3 的 glowPadding 行 `:466` 仍声称「forceResync 一置位就必走慢路径…这是唯一保证」 |
| R6 S0 必须早于写 CONFIG | ⚠️ **正文未改，仅在 §8 表**（见 N-2） | 同上，`:437` 仍写 `S0 取 service 的 state…记 old = 当前 CONFIG 的关键值快照`，未提"非 EDT 必须先拍快照再同步" |
| R7 `PersistResult` 三态 | ⚠️ **正文未改，仅在 §8 表**（见 N-2） | §3.2.2 伪码 `:402-405` **仍是** `boolean persisted = persistQuietly(); if (persisted) deleteOldKeys… else state.stateVersion = 0;` —— 与实际实现的 `switch(PersistResult)` 不一致 |

**结论**：R1–R4 在正文落实；**R5/R6/R7 只在 §8 修订表里记载，正文的伪码没有同步更新**。这属于文档一致性问题（记 N-2，low），**不影响实现正确性**（实现是对的，是契约正文滞后）。但既然 v2 自称"修订记录"，正文理应与之一致，否则下一个人读 §3.3.2/§3.2.2 会按旧的、已知有缺陷的伪码去理解。

---

## 5. 我独立重跑/重算的全部判据

| 判据 | 我的做法 | 结果 |
|---|---|---|
| NeovideConfig 零 diff | `git diff --exit-code` | **exit 0** ✔ |
| 35 项禁改字面量 | 自写脚本，**逐文件** `Select-String -SimpleMatch -Path <单文件>`（避开 LESSONS §3.3 假阴性） | **missing = 0 / 35** ✔ |
| parity | `run-parity.ps1 -Work build/t8-scratch/parity` | **3× PARITY OK + ALL PARITY OK** ✔ |
| audit | `run-audit.ps1` 同上 | **19 项 DIFF = 1**，唯一 DIFF 是 `hideNativeCaret`（基线 false → 新版 true）✔ |
| IME | `run-ime-geom-test.ps1` | **通过 29，失败 0** ✔ |
| 持久化/applyConfig/面板 | `run-verify-t4.ps1` | **1 + 3 + 52 + 26 + 18 = 100 条断言全过**，FAIL 行 0 ✔ |
| 非 EDT 专项 | `run-non-edt-probe.ps1` | **通过 13，失败 0** ✔ |
| 旧键清除 | 逐文件 grep（17 个 .java） | `PropertiesComponent` 仅 `NeovideCursorSettings.java` 10 命中；`neovide.cursor.` 仅该文件 2；`restorePersistedSettings` **0**；`static volatile boolean enabled` **0**；`kFromTrailFactors` **0**；`CONFIG.enabled` **14** ✔ |
| EP 注册 | 读源码 xml + `jar xf` 取 jar 内 xml | `applicationService` 与 `applicationConfigurable` 都在；`parentId="tools"`、`id` 插件前缀、`displayName` 在 XML、`instance` 指向真实类 ✔ |
| 不做清单未越界 | 面板内 8 个禁名 grep | **命中 0** ✔（R-04/R-06 只动契约文本，未连带改实现） |
| applyConfig 顺序 | 直读 `:445-565` | S0 快照(非 EDT)`:457` / S1 `:501` / S2–S6 `:507-521`（S6 最后）/ S7 `:534-546`（onModeChanged + refreshGlowPadding）/ S8 `:549-555` —— **与契约一致，且修复了 S0 时机** ✔ |
| V14 无实时预览 | 面板 grep | `onChanged` **0**、`whenStateChangedFromUi` **0** ✔ |
| §2.2 七条警告文案 | 脚本把契约表格文本与 Java 多行常量拼接后**逐字符**全等比较 | **7/7 MATCH**（113/101/71/112/106/103/36 长度全等）✔ |
| audit D 段新基数 | 排除 `settings/` 包，只数 `src/` | **12**（NeovideConfig 4 / CaretAnimator 4 / CaretTrailPanel 2 / NeovideCaretManager 2）✔ 与 captain 定义一致 |
| 新增工具是否越界 | `NonEdtProbe.java` 位置 | 在 `tools/settings-probe/`，**不在 `src/`** → 不影响 D 段基数 ✔ |

---

## 6. captain 重点①：编造数字的残留核查

**裁决：无编造残留。**

我把 `VERIFY-SETTINGS.md` §9.5 的**波动表（:613-617）**逐格与它自己引用的原始 `SUMMARY` 行（:563-567 / :571-575 / :581-585 / :589-593）对账，**每场景 10 格 × 5 场景 = 50 格**：

```
R1  10/10 格全部对账一致 ✔      R2  10/10 ✔      R3  10/10 ✔
R4  10/10 ✔                     R5  10/10 ✔
合计不一致格数 = 0
```

对账口径：区间上下端、区间宽度（同遍 3 轮极差）、跨遍跨度（两遍合并极差）。**全部自算复现，无一格偏差。**

同时我核对了 §9.6 用 ROUND 级原始数据（`build/verify-t4/frame*/{base,new}-frame.txt`，共 60 条 ROUND 行）独立复算"是否存在系统性差异"：按报告 `:631` 自设门槛（方向跨遍一致 **且** |差| > 自身跨遍跨度 **且** 方差为零），5 场景 × 4 指标 = 20 项，**判定为系统性差异的 = 0**。→ **报告「无系统性差异」的核心结论成立**，且这次是我用 ROUND 原始数据、不是抄它的表。

---

## 7. 本轮新发现（三方均未提及）

### N-1 【low】面板「辉光外扩幅度」滑块逐格拖动时，`glowPadding` 不跟随（0.5px 死区与 0.01 步长的交互）

- **file:line**：`CaretTrailPanel.java:138`（`if (Math.abs(newWidth - glowWidth) > 0.5f)`）对照 `NeovideCursorConfigurable.java:331`（`row.slider(5, 60, 20, 1)`，即步长 0.01）
- **problem**：`updateGlowRadius()` 有一条 **0.5px 死区**：只有当新半径与当前 `glowWidth` 相差 > 0.5px 时才更新。面板滑块的步长是 1 刻度 = 0.01，单步 Δ = 0.01 × maxDim；按典型光标高度 32px 算 Δ = **0.32px < 0.5**，于是**单步拨动被丢弃**，`glowWidth` 会"粘住"直到累积超过 0.5px。
  `refreshGlowPadding()` 读的是 `panel.getGlowWidth()`（`CaretAnimator.java:1389-1390`），所以 `glowWidth` 不动 → `glowPadding` 也不动。
  **我用真实 `CaretTrailPanel` 实测**（`build/t8-scratch/DeadbandProbe.java`）：
  ```
  blur=0.20 → glowWidth=6.40  glowPadding=11
  blur=0.21 → glowWidth=6.40  glowPadding=11   ← 单步被丢弃
  blur=0.22 → glowWidth=7.04  glowPadding=12
  blur=0.23 → glowWidth=7.04  glowPadding=12   ← 单步被丢弃
  blur=0.24 → glowWidth=7.68  glowPadding=12
  blur=0.25 → glowWidth=7.68  glowPadding=12   ← 单步被丢弃
  blur=0.26 → glowWidth=8.32  glowPadding=13
  ```
- **严重度评估（我自己定量）**：单调拖动 0.20→0.60 全程，`glowWidth` 相对理论值的**最大偏差 = 0.32px**（必然 < 0.5 阈值）。而 `glowPadding = ceil(glowWidth) + 4` 是**阶梯函数**，0.32px 的滞后最多让 padding 差 **1px**；光标一移动就由慢路径纠正。→ **视觉影响 ≤1px 且短暂**，故判 **low**。
- **为什么三方都没提到**：契约 §3.3.3 的判据用的是"一步到位"（`0.2 → 0.6`，Δ=12.8px ≫ 0.5），**该判据本身不受影响**；实现者的探针 V2 也是直接赋值再调 apply，走的正是"一步到位"。只有**逐格拖动**这一个新 UI 交互才暴露它 —— 而这是设置面板才引入的交互方式（之前编辑器字段只能由代码一次设好）。
- **不是本批引入的回归**：`CaretTrailPanel.java` 在本批 diff 中**numstat 为空（完全未改）**，基线 `8906609:135-141` 就是同一条判据。→ 是**既有行为**被新 UI 放大。
- **requiredFix**：二选一即可，都不改观感默认值：
  (a) 把 `updateGlowRadius` 的死区从 `0.5f` 收紧到 `< 单步 Δ`（例如 `0.01f`），代价是每帧多几次赋值（无实测影响）；
  (b) 或在 `refreshGlowPadding()` 里绕过死区直接算 `Math.ceil(config.shadowBlurFactor * maxDim) + 4`。
  **若判定"接受现状"，请在契约 §3.3.3 明确写一句"逐格拖动时 padding 最多滞后 1px，可接受"**，避免下一个人把它当新 bug 再报一次。

### N-2 【low】契约 v2 的 §8 修订记录有 3 条（R5/R6/R7）只在附录表里，正文伪码未同步

- **file:line**：`SETTINGS-CONTRACT.md:444-445`（S7 仍无 `refreshGlowPadding`）、`:437`（S0 未说明非 EDT 时机）、`:402-405`（`persistQuietly` 仍是 boolean）
- **problem**：§8 表登记了 R5/R6/R7 三项修订，但正文 §3.3.2 的 S0–S8 伪码与 §3.2.2 的落盘伪码**仍是 v1 原文**。R5 尤其重要：§3.3.3 的 glowPadding 行（`:466`）至今仍写着「`forceResync` 一置位就必走慢路径…**这是唯一保证**」，而这句已被 t5/t7 共同证伪（`:1007` 早退）。
- **影响**：实现是对的（`applyConfigOnEdt` 确实调了 `refreshGlowPadding()`、`persistQuietly` 确实返回三态），纯文档滞后。但下一个人若按正文伪码理解，会误以为 `forceResync` 足够 —— 这正是 R-05 那条 finding 当初产生的原因。
- **requiredFix**：把 §3.3.2 伪码的 S7 行补上 `refreshGlowPadding()`、补一句 S0 的非 EDT 时机、把 §3.2.2 的伪码换成 `switch(PersistResult)`，并修正 §3.3.3 那句"唯一保证"。

---

## 8. 复跑命令（全部我自己跑过）

```powershell
cd D:\deepseekwork\neovim-cursor-pr

# SHA256 绑定
Get-FileHash -Algorithm SHA256 SETTINGS-CONTRACT.md, VERIFY-SETTINGS.md, dist\settings-ui-smoke.jar
git rev-parse HEAD
git diff HEAD > build\t8-scratch\diff.txt ; Get-FileHash -Algorithm SHA256 build\t8-scratch\diff.txt

# 默认值零改动
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java

# 三件套 + 两个探针套件（-Work 必须指到工作区内，沙箱不允许写 %TEMP%）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\parity\run-parity.ps1       -Work D:\deepseekwork\neovim-cursor-pr\build\t8-scratch\parity
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\parity\run-audit.ps1        -Work D:\deepseekwork\neovim-cursor-pr\build\t8-scratch\parity
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\ime-probe\run-ime-geom-test.ps1 -Work D:\deepseekwork\neovim-cursor-pr\build\t8-scratch\ime
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-verify-t4.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-non-edt-probe.ps1
```

反向验证（变异测试）与 N-1 的复现探针放在 `build/t8-scratch/`（已被 `.gitignore` 忽略），未进入 `src/`：
`DeadbandProbe.java`（N-1）、以及临时变异副本 `mut/`（跑完已删除）。

---

## 9. 结论

**verdict = pass。六条 findings 全部关闭。**

- **R-01**（enabled 早退在同步之前）：已修，`editorCreated` 现在「先同步 → 再判 enabled」，两条 install 路径都被拦住；变异回滚后 D1 断言立刻变红（`ANIMATORS=1`），证明探针有效。
- **R-02**（非 EDT 跳过 S2–S6）：已修，改为「先拍 Snapshot → 再同步 → 带快照 invokeLater」，S0 语义正确；变异回滚后 `saveSettings` 从 0→2 变回 0，断言变红。
- **R-03**（迁移永不收敛）：已修，`PersistResult` 三态让「排队中」与「真失败」语义分开，落盘失败才回退版本号；变异回滚后 4 条断言同时变红。
- **R-04/R-06**：死代码已删、契约类名已更正，且**契约与源码自洽**（`kFromTrailFactors` 两边都 0 命中）。
- **R-05**：波动表 50/50 格与原始 SUMMARY 完全对账，无编造残留；我用 ROUND 原始数据独立复算，**「无系统性差异」结论成立**。

契约 v2 的修订合法、R1–R4 已在正文落实，但 **R5/R6/R7 只写在 §8 表、正文伪码未同步**（N-2，low，纯文档）。另外发现一条**三方均未提及**的交互边界（N-1，low）：辉光外扩幅度逐格拖动时 `glowPadding` 最多滞后 1px，源于 `CaretTrailPanel:138` 的 0.5px 死区与面板 0.01 步长的交互 —— 这是既有行为被新 UI 放大，非本批回归，且不影响 §3.3.3 的判据。

两条 low 都不构成阻塞，**不影响交付**；建议随集成一起处理（合约 §3.3.3/§3.2.2 伪码同步 + 死区口径写进契约）。
