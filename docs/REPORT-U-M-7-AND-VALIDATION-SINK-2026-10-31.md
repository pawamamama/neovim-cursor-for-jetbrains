# 交付报告：U-M-7 光标串行回归 + 校验下沉（2026-10-31）

> 写给**决策会话**：这一份把两件事（一个 bug 修复、一个跨层重构）的事实、证据、
> 行为变化、风险与待决策项写在一起。所有数字都是本机实测，命令可复现。
> 细节来源：`plans/validation-sink.md`（校验下沉的执行记录与双代理审查）、
> `docs/CODE-AUDIT-2026-10-30.md`（U-M-7 与 H-1 / H-2 的原始条目）。

---

## 零、一页结论

| 事项 | 状态 | 证据 |
|---|---|---|
| **U-M-7**（慢路径中途抛出 → 拖尾永久停错位置，即「写方法时光标串到其他行」） | ✅ 已修 | 新探针 `VCatchRollback` 6/6；变异（把清零挪回慢路径开头）实测 C-2/C-4 变红 |
| **校验下沉 U-1/U-2/U-3**（预设快照校验从三个调用方下沉到 `SettingsField`；`sanitizeColor` 收 9 位） | ✅ 已完成 | 新探针 `VValidationSink` 34/34；变异 M-1..M-9 全部验证 |
| 全量门禁（单份串行跑） | ✅ ALL PASS | **107 条目 / 1195 PASS / 2 FAIL（仅 informational 的 VRegr2）/ exit 0** |
| D0 红线（`core/NeovideConfig.java` 零 diff） | ✅ | `git diff --exit-code` 通过 |
| 已安装版本 | ✅ **v0.9.113** | jar 内 `<version>0.9.113</version>`，兼容区间 242 ~ 261.* |
| 实机复现验证 | ⏳ **待用户确认** | 需要用户打开 IDEA 在 `@Contract` 场景复现一次 |
| git 提交 / push | ⛔ **未做**（用户未指示） | 全部改动仍在工作区 |

**一句话**：代码是对的、门禁是绿的、jar 已经装上去了；**但磁盘上装的东西领先于 git 里的任何提交**，
这是决策会话最需要先处理的一条（见 §七 D-1）。

---

## 一、范围与起点

- 起点：`main` @ `923170f`（已推 fork，同步 0 0）。
- 本轮两个任务：
  1. **U-M-7**：用户报「写方法时候光标串到其他行，昨天修复了今天又出来了」——
     BUG-006 的同一形态，由慢路径中途抛出触发。
  2. **校验下沉**：把预设快照的量程/颜色校验从「三个调用方各写一遍」下沉到
     `settings/SettingsField`（U-1 Tools 入口不 clamp / U-2 不校验 HEX / U-3 9 位颜色重启丢失）。
- 两件事都改在同一个工作区，因此**同一个 jar 里同时包含两者**。

规模（实测，`find src/main/java -name '*.java'` / `wc -l`）：

```
生产代码   36 文件 / 20,547 行
探针       125 文件 / 31,690 行（110 个 V*.java）
改动量     11 个跟踪文件：+786 / -394；另加 2 个新探针 + 1 份计划文档（未跟踪）
```

---

## 二、改动清单（按文件）

### 2.1 U-M-7（光标串行）

| 文件 | 位置 | 改动 |
|---|---|---|
| `ui/CaretAnimator.java` | ≈:790 | 新增包私有静态钩子 `afterMarkHookForProbe`（生产恒 `null`，一次静态字段读） |
| 同上 | ≈:1336 | 新增 `commitResync(resyncRequested, inlayConsumed)`：只在成功收尾清零 |
| 同上 | ≈:1878 | 慢路径开头把 `forceResync` / `inlayGeometryDirty` **拍进局部快照** |
| 同上 | ≈:2019 | **删除**原来的两句提前清零（缺陷本体） |
| 同上 | ≈:2305 | 逐光标门改用 `resyncRequested`（原字段那时已被清空，判据从未生效） |
| 同上 | ≈:2315 | `markAt` 之后注入点（仅探针使用） |
| 同上 | ≈:2407 / ≈:2442 | 两个成功出口调用 `commitResync(...)` |
| `ui/CaretAnimatorProbe.java` | — | 新增 `setAfterMarkHook(Runnable)` 转发（生产不调用） |
| `tools/settings-probe/VCatchRollback.java` | 新文件 | 6 条断言，见 §三.2 |

**根因（一句话）**：两个「下一帧必须重算」的标志在**活还没干完**的时候就被清零了，
而 `markAt` 又在目标几何算出来之前就标了「已到位」——
一旦两者之间任何一句抛出，下一帧的顶层判据就认为「光标没动」，直接走快速路径，
拖尾永久停在不该停的位置，而且此后连异常都不再产生。

### 2.2 校验下沉（U-1 / U-2 / U-3）

| 文件 | 改动 |
|---|---|
| `settings/SettingsField.java` | 字段自带 `min/max` + `range(lo,hi)`（13 个数值字段）；`decode` 越界**夹回**并记 warn；新增 `RestoreReport{applied,clamped,rejected}` 与 `restoreSkippingReported`；新增颜色下沉点 `restoreColorSnapshot` |
| `settings/NeovideCursorSettings.java` | `loadState` 的 13 处内联 `clamp(x,lo,hi)` 改为「原样拷贝 + 遍历字段表统一夹取」；`sanitizeColor` 改为只调 `isValidHexColor`（7/9 位同源） |
| `ui/settings/NeovideCursorConfigurable.java` | `SPECS` 的刻度端点由字段表区间**导出**（删掉 26 个端点字面量）；删除 `clampPresetNumerics` / `rawAtTick`；两条还原路径改消费下沉层报告；`applyColorSnapshot` 改为委托 |
| `ui/CursorPresetApplier.java` | Tools 入口改消费报告（夹回/拒绝各一行 warn）；颜色入口改走 `restoreColorSnapshot`，非法即拒绝且不写名字不落盘 |
| `tools/settings-probe/VFieldDomain.java` | **仅夹具**：`perturb()` 从 `decode` 改 `writeRaw`（否则端点值扰动会被夹回，D-1 误报）。**断言一字未改** |
| `tools/settings-probe/build-v-probes.ps1` | 登记 `VValidationSink` / `VCatchRollback` |
| `CLAUDE.md` / `START-HERE.md` / `docs/NEXT-STEPS.md` | 探针数 108→110、生产 20,178→20,547 行、探针行数重测 |

---

## 三、证据

### 3.1 门禁（最后一份，串行单跑）

```
$ pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr
EXIT=0
107 条目 / 1195 PASS / 2 FAIL（仅 VRegr2）/ V-PROBES RESULT: ALL PASS
基线（开工前）：105 条目 / 1155 PASS / 2 FAIL
```

两条 FAIL 是 `VRegr2` 的 R-5 / R-6（`typingSmoothness` 不参与预设匹配 / 不派生副本），
**开工前就有**，按项目约定属 informational、不进门。

与两件事直接相关的四条探针：

```
VInlay          5/5   BUG-006 正常路径未回归（inlay 撑高→下移一行→移除复位）
VFontScale      5/5   快速路径 / 字号判据未被改坏
VCatchRollback  6/6   本轮新增：慢路径中途抛出 → 下一帧仍重算
VValidationSink 34/34 本轮新增：两条入口同源 / 区间唯一 / 颜色唯一 / 9 位往返
```

### 3.2 变异测试（改坏生产 → 断言必红 → 撤销 → 恢复绿）

**U-M-7**（本次）：把清零挪回慢路径开头（原写法）

```
C-1 PASS 异常被吞、本帧报告「没更新」
C-2 FAIL 抛出后重算请求仍在        [pendingResync=false]      ← 主红点
C-3 PASS 目标几何还没算出来        [centerY=129.5（基线 129.5）]
C-4 FAIL 下一帧必须重算            [返回=false 129.5→129.5，位移 0.0px（期望 19）]
=== 4 PASS / 2 FAIL ===
撤销后：6/6 全绿，centerY 129.5 → 148.5（位移 19.0px）
```

C-4 那 0.0px 就是用户看到的现象：**光标停在前一行**。

**校验下沉**（另一会话，细节见 `plans/validation-sink.md` §七/§八）：
M-1 删 `glowLayers` 区间 / M-2 decode 不夹 / M-3 删 loadState 统一夹取 / M-4 sanitizeColor 收回 7 位 /
M-5 颜色校验跳过 / M-6 夹回日志静默 / M-7 面板端点手写回字面量 / M-8·M-9 `range()` 两条新守卫，全部按预期变红。

### 3.3 红线与白名单

```
D0  core/NeovideConfig.java        零 diff ✓
白名单外                          0 字节改动（已逐文件核对 git status）
docs/BUG-LOG.md                   未触碰
```

---

## 四、行为变化与风险（决策会话必须看的）

| # | 变化 | 影响面 | 判断 |
|---|---|---|---|
| R1 | 预设快照里的**越界数值**由「原样写进 State」改为「夹回端点 + 记日志 + 计入 applied」 | 只有坏数据（手改 XML / 旧版预设）会走到 | 与 `loadState` 同一规则；第 1 批的 N-1 探针本来就拿 `loadState` 当 oracle |
| R2 | `slopeTicks` 越界：原来一律回落 0，现在夹到最近端点（-1→0、**999→100**） | 同上，正常使用进不来 | 刻意统一；面板侧原本就把 999 夹成 100，下沉后才真正一致 |
| R3 | 9 位 `#RRGGBBAA` 不再在重启时被打回默认色 | 用过 9 位颜色的用户 | 9 位按 `RR GG BB AA` 解释（`NeovideColors.parse` 本来就如此），末两位 alpha 与 `tailOpacity` 相乘 |
| R4 | **确定性异常不再静默冻结，而是每帧重试**（日志按 1000 次限流） | 只有「每帧必抛」的 bug 会遇到 | 与原注释承诺一致；代价是持续重试 |
| R5 | `forceResync` 现在**真的**强制全部光标重算（多光标时每次光标事件多 N 次坐标换算） | 多光标用户 | 原注释要的就是这个；单光标无差别 |
| R6 | **磁盘上的 v0.9.113 领先于 git** | 任何人回滚/重装 | 见 §七 D-1 |

---

## 五、已安装版本与实机验证

```
构建   dist\neovide-cursor-0.9.113.jar（227,509 字节）
安装   C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\neovide-cursor-pawamamama\lib\
         （文件名恒为 jetbrains-neovide-cursor-0.9.8.jar，是加载机制要求；版本读 jar 内 META-INF/plugin.xml）
自检   <version>0.9.113   since-build=242  until-build=261.*
配置   安装前备份 → 清空 → 装完全量还原（6 个字段，丢弃 0 个）
备份   D:\deepseekwork\_config-backup\personal-state-20261031-195617.json
启动   用了 -NoStart，IDEA 需手动打开
```

**一个与本次改动无关、但影响「实机复现」的前置事实**：安装前读生效中的
`NeovideCursorSettings.xml`，里面只有 6 个非默认字段（`targetFps` / `glowLayers` /
`shadowBlurFactor` / `customAnimPresets` / `animCustomName` / `stateVersion`）。
也就是说 `tailColor=#4FC3F7`、`slopeTicks=100`、`stretchRatioK=2.0` 这些在今天 **11:02 那次写入**里
就已经回到出厂值了 —— **不是这次安装弄掉的**（本次照 6 个字段全量还原，丢弃 0 个）。
如果用户记得自己设过，值得单独查一次「11:02 是谁写回了默认值」。

**实机验证步骤（待用户执行）**：打开 IDEA → 写一个方法 → 等 IDEA 自动出现灰色
`@Contract(pure = true)` → 看光标是否仍在正确行；再补 `{}` + 回车确认停在下一行。
若仍复现：设置 → 调试 → 高级（诊断） 勾上「诊断日志」（**当前是关的**）→ 重启 →
复现 → 贴 `refreshTarget 抛异常（累计 N 次）` 与 inlay 变化附近的 `[cursor]/[geom]/[tick]` 行。

---

## 六、已知未修 / 登记为后续

| # | 项 | 为什么没做 |
|---|---|---|
| N-1 | `VRegr2` 的 R-5 / R-6：`typingSmoothness` 既不进 `matchAnimPreset` 也不派生副本 | 是**真功能缺口**，但属产品决策（平滑要不要参与预设），且不在本轮白名单 |
| N-2 | `deriveTrailFactors` / `applySlopeOverlay` / `syncConfigToState` 里还有三处同数值 clamp | 它们是纯函数定义域保护，策略与持久化量程不同，改它等于改运行时行为 |
| N-3 | `shadowBlurFactor` 端点双来源（`SettingsUiUtil.SOFT_BLUR/SHARP_BLUR` × 字段表） | 当前两端实测一致（S-1c），消除它需 UI 换算层反向依赖字段表 |
| N-4 | Tools 入口夹回后不回写快照（面板会自愈） | 行为正确（幂等），只是日志噪音；改它要动用户数据 |
| N-5 | `ColorRestoreResult` 把 `s == null` 与「快照缺键」并成一类 | 所有调用方都保证非 null，属 API 洁癖 |
| N-6 | U-M-8 同族（`getLineNumber` 抛出导致每帧重建 N 条 `SpringTrail`） | 与本轮无关，审计已登记 |

---

## 七、建议决策项（给决策会话）

| # | 决策 | 我的建议 | 理由 |
|---|---|---|---|
| **D-1** | **提交方式** | 分两次提交：① `fix(ui): U-M-7 慢路径中途抛出后重算请求丢失`（CaretAnimator + CaretAnimatorProbe + VCatchRollback + 文档数字）；② `refactor(settings): 预设快照校验下沉 + 9 位颜色`（settings/ui/探针/VFieldDomain 夹具 + plans/validation-sink.md） | 两件事的根因、影响面、回滚方式都不同；混在一起以后不好 revert。**注意磁盘上已装 v0.9.113，提交只是把已发布的事实补进 git** |
| **D-2** | 是否 push `fork/main` | 建议 push（历史习惯是每轮推 fork） | 需要用户明确说，我不擅自推 |
| **D-3** | `v0.9.113` 要不要打 tag | 建议打 —— `git tag` 停在 `v0.9.83`，已装版本却到 0.9.113，中间 30 个版本没有锚点 | CLAUDE.md §十一 记录了「不要用 tag 判断版本」这个坑 |
| **D-4** | 实机结果出来前的处置 | 先等用户复现结论，再决定 tag 与发版说明 | U-M-7 是「偶发」类缺陷，探针只能证明机制，不能证明触发 |
| **D-5** | N-1（`typingSmoothness` 两条红） | 单独立项问用户「平滑要不要参与预设」再改 | 属产品决策（纪律四-L） |

---

## 八、复现与回滚

```bash
# 门禁（必须单份串行跑；脚本第一步会删 build\v2-probe\classes 全量重编译）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr

# D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

**U-M-7 单独回滚**：恢复 `ui/CaretAnimator.java` + `ui/CaretAnimatorProbe.java` 两个文件即可；
`VCatchRollback` 依赖 `setAfterMarkHook`，需一并回滚（否则编译失败）。
其余改动与它无耦合。

**配置回滚**：个人配置备份在 `D:\deepseekwork\_config-backup\personal-state-20261031-195617.json`；
历史回滚脚本见 `build/rollback-*.ps1`。
