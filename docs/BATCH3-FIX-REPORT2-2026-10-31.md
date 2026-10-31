# 第 3 批审查收尾 · 第一批（5 条 MEDIUM）交付报告（2026-10-31）

> 依据：`docs/HANDOFF-BATCH3-AUDIT-2026-10-31.md` §三 + `docs/CODE-AUDIT-2026-10-30.md` §9.3
> 上游清单：`docs/HANDOFF-NEXT-2026-10-31.md` §3.1（第 6 步第 1 批）
> 施工计划与逐条证据：`plans/batch3-fix2.md`
> 分支：`main` · 代码提交：`e25a4ff`（**已 push 到 `fork/main`**）

---

## 零、一句话结论

本轮修完第 3 批审查剩下的 **5 条 MEDIUM**：
**P-M-11（dispose 漏释放预览）/ P-M-8（预览 rank 是生产算法的复制品）/
P-M-5（预设数值快照不 clamp）/ P-M-6（颜色快照不校验 HEX）/ P-M-3（颜色坏快照静默）**。
全量门禁 **105 条目 / 1155 PASS / 2 FAIL（仅 VRegr2 informational）/ ALL PASS**；
**9 条变异测试全部按预期变红**；D0（`core/NeovideConfig.java`）与
`ui/SpringTrail.java`、`docs/BUG-LOG.md` 零 diff。

**两个独立审查代理都指出：P-M-5 / P-M-6 / P-M-3 只修了设置面板这条入口，
Tools 菜单的 `ui/CursorPresetApplier` 仍带同一缺陷（白名单外）** —— 见 §四「未修、只登记」。

---

## 一、代码与分支状态（收工时实测）

```
分支            main
HEAD            e25a4ff  fix(settings): 第 3 批收尾首批 5 条 MEDIUM …
上一个提交      ce39138  docs: 第 3 批审查修复交付报告（2026-10-31）
远端            fork/main...HEAD = 0 0（推送后实测）
工作区          仅三个开工前就存在的未跟踪文件：
                build/night-run-report.md、docs/HANDOFF-NEXT-2026-10-31.md、
                plans/batch3-review-prompt.md
门禁            105 条目 / 1155 PASS / 2 FAIL（仅 VRegr2）/ V-PROBES RESULT: ALL PASS
D0              core/NeovideConfig.java 零 diff
禁区            ui/SpringTrail.java（只在变异测试中临时改过，已撤销，零 diff）、
                docs/BUG-LOG.md 零 diff
```

开工基线与任务书的差异（**任务书三处前提与代码不符，已在施工前校正**）：

| 任务书写的 | 实测 |
|---|---|
| 工作区只有两个未跟踪文件 | **三个**（多 `docs/HANDOFF-NEXT-2026-10-31.md`，另一会话的进度快照） |
| 门禁基线 103 条目 / 1124 PASS | ✅ 完全一致 |
| 复用「`SettingsField` 的 clamp 区间表」 | **该表不存在**（`grep min\|max\|range\|clamp` 零命中；第 1 批 H-1 的建议至今没落地） |
| P-M-8 可「改成调用生产方法」 | 需要改 `ui/SpringTrail.java` 或 `core/`，**都在白名单外** |

---

## 二、五条修复：根因 → 改法 → 证据

### P-M-11（MEDIUM）`disposeUIResources()` 漏释放 3 个预览与 5 个组件引用

**根因**：dispose 逐个置 null 了颜色面板 / 下拉 / 按钮，判据是「已脱离组件树的对象不得被
继续强引用」；同一判据完全适用于 `inlinePreview / crossPreview / glowPreview /
previewConfig / trailModeCombo / paintDiagCheck / animRestoreBtn / colorRestoreBtn`，
只是当初没做到底。其中 **`previewConfig` 最关键**：`refreshPreviews()` 的守卫就是
`if (previewConfig == null) return;`，不置 null 时，dispose 之后排队到达的事件仍会把参数
写进 previewConfig，再对三棵已脱离组件树的预览逐个 `onConfigChanged() + repaint()`。

**改法**：dispose 末尾补 **8 个 null**。为什么正好 8 个：审计 §9.3 逐项点名了 8 个字段
（3 预览 + previewConfig + 2 个非 DSL 控件 + 2 个恢复按钮）；`bindings` / `*PresetItems` /
`session` 是「已清空的容器」，不在这 8 个之内。

**证据**：

```
RED      dispose 后 8 个引用全为「非null」
GREEN     8 个全为 null；dispose 后 refreshPreviews() 不抛异常
变异 M-1  删 previewConfig = null   → D-1b 红（只有它是非 null）
变异 M-2  删 inlinePreview = null   → D-1b 红（只有它是非 null）
```

### P-M-8（MEDIUM）预览的四角 rank 排序是生产算法的复制品（陷阱 3 同类）

**根因**：`AnimationPreview.assignRanks()` 手写了一份「稳定插入排序 + 索引 tiebreak」，
与生产 `SpringTrail.assignRanks()` 分家。0.9.77 修生产时预览没跟上，已经出过一次
「生产变斜、预览仍是矩形」。

**改法（本轮选了任务书的第二条路「断言两者一致」）**：

1. 把排序抽成 `public static int[] ranksForAlignment(double[])`，
   `assignRanks(cx,cy)` 改成「量对齐度 → 调它」——**纯提取，行为逐位不变**；
2. 新增探针 `VPreviewRank`：反射驱动生产 `SpringTrail`（`setTarget` 两次 → `applyCharsMoved(1)`
   → `update(0)` 触发 `assignRanks()`），读它自己算出的**对齐度**与**四角时长**
   （rank 由「时长降序」解出 —— 探针不复制排序），再把同一份对齐度喂给预览、逐角比对。

**为什么不选「调用生产方法」**：`SpringTrail.assignRanks()` 是 `private` 且直接把结果写进四角
（`corners[order[k]].jump(..., k)`），没有可复用的纯函数入口；共用必须改
`ui/SpringTrail.java` 或 `core/`，两者都在白名单外（任务书第六节第 1 条）。
抽 `core/RankOrder.ranks(double[])` 的建议已登记，与 P-M-7 的重构同批做。

**证据**：

```
自然红    无 —— 两份实现今天恰好一致，缺陷是「将来改一边另一边不跟随」，属潜伏型
GREEN     R-1..R-6 全绿（右移 / 左移 / 右下 / 左上 / 单像素 + 排列校验）
变异 M-7  生产 SpringTrail 排序 > 改 >=  → R-1/R-2/R-5 红（生产 [1,3,2,0] vs 预览 [0,2,3,1]）
变异 M-8  预览 ranksForAlignment 同样改 → R-1/R-2/R-5 红
```

> 斜跳两个场景（R-3 / R-4）四个对齐度天然互异，没有并列，所以 `>=` 变异对它们无影响 ——
> 这正好说明探针抓的是「索引 tiebreak」这条判据，而不是随便乱红。

### P-M-5（MEDIUM）自定义动画预设的数值快照不经量程校验就进 State

**根因**：同一个字段有两条入口 —— `loadState()` 有 clamp，`SettingsField.decode()`
**只做类型校验、不 clamp**。于是手改/旧版写下的 `glowLayers=99` 会一路进 State、进 CONFIG、
落盘；同一会话里滑块显示 12、输入框显示 99（`syncAllControlsFromPending` 写的是 exact），
重启后 `loadState` 悄悄改回 12 —— 用户看到「值自己变了」。

**改法**：面板侧新增 `clampPresetNumerics(State)`，在两条自定义预设还原路径
（`onAnimPresetPicked` 的自定义分支、`restoreAnimPreset` 的自定义分支）各调一次：

- 量程取自面板**既有的** `SPECS`（`SliderSpec{min,max,readTick,writeTick}`）——
  不新造第二张量程表（纪律四-J 第 2 条）；
- **跳过 `runtimeSwitch`**（`cursorWidthPx` / `cursorHeightRatio` 也是设备级开关，预设无权改）；
- 判断用「原始值 vs 端点原始值」而不是直接比刻度 —— 因为 `shadowBlurFactor` 的刻度是**反的**
  （锐度 0 = 最柔 0.60），且 `sharpnessOf` 自己就把输入夹进了 0..100，
  只比刻度会让一个 1e9 的外扩幅度「看起来合法」；
- 夹回时记 `LOG.warn`（写字段 key，便于排查）+ 给用户一行提示（**文案里不出现英文 key**，
  纪律四-M），并把夹回值**回写该条自定义预设**（自愈）。

**为什么 clamp 而不是整条拒绝**：条目名就叫「快照不 clamp」，`loadState` 的语义也是 clamp；
整条拒绝会让「只有一个字段越界」的老预设全部失效，那是用户可感知的行为变化。
clamp 之后「应用的值 == 重启后读回的值」。

**为什么还要自愈回写**：审查代理实测发现，不回写的话用户在这个坏预设上
**每点一次下拉就弹一次模态警告**，直到他手动拖一次滑块。「站在自定义预设上编辑 →
快照实时保存」本来就是这条路径的既定语义，而夹回正是一次由用户操作引起的编辑。

**证据**：

```
RED      pending=99 / 0.9 / 5.0；无 warn、无提示
GREEN    pending == loadState 对同一脏值的处理（12 / 0.35 / 1.0）；WARN 含 glowLayers；
         提示新增；再选同一条不再提示（自愈）
变异 M-3  clamp 直接返回空列表        → N-1a..N-1e 五条红
变异 M-4  夹取方向对调                → N-1a/b/c 红（pending=1 / 0.05 / 0.0）
变异 M-9  去掉自愈回写                → N-1f 红（第一次=1 第二次=2，快照仍含 99）
```

**为什么 oracle 是 `loadState` 而不是抄数字**：`VBatch3Medium` 把同一份脏数据交给
`new NeovideCursorSettings().loadState(dirty)`，拿它的结果当期望值 ——
这样断言的是「两条入口同源」本身，而不是「面板等于某几个魔数」。

### P-M-6（MEDIUM）自定义颜色预设快照不校验 HEX

**根因**：`CursorPresets.parseColorSnapshot` 只判「两个键都在」，不校验格式；
而动画快照走 `SettingsField.decode` 的 HEX 分支，那里有 `isValidHexColor`。
同一类数据两条入口，一条设防一条不设防。

**改法**：颜色快照统一走 `applyColorSnapshot(State, Entry)`，
校验直接调 `SettingsField.isValidHexColor(String)`（**不新写规则**）。
另一个必须一起做的点：**`isValidHexColor` 内部会 trim，而绘制层的 `NeovideColors.parse`
不 trim** —— 所以写入前必须 `trim()`，否则快照里一个 ` #123456`（前导空格）会
「校验通过、绘制认不出、光标变黑」。动画路径（`decode`）本来就是 trim 后再赋值。

**证据**：

```
RED      tailColor=not-a-color 直接进 pending；无提示
GREEN    非法串不写进 pending（原值保持），有提示；9 位 RGBA 仍合法；带空格的串被 trim
变异 M-5  关掉 isValidHexColor 判断   → N-3a/b/c 红
```

### P-M-3（MEDIUM）颜色预设坏快照：点选与「恢复默认值」都静默无反应

**根因**：`hex == null` 分支只 `refreshPresetDisplay()` 就 `return`，
用户看到的是「点了没反应」；`restoreColorPreset()` 里同类分支连刷新都没有。

**改法**：两处换成与动画侧**同构**的处置（P-H-3 已建立的范式）：
提示用户 + `suppressDerive` 包住 `refreshPresetDisplay()`（避免顺手派生「X 副本」）+
不写 `colorCustomName`（否则下拉声称已切换）。`restoreColorPreset()` 补上原来缺的 `return`。

**证据**：

```
RED      N-4b 无提示
GREEN    坏快照不写半套 / 有提示 / 不写名字 / 下拉复位 / 不派生副本
变异 M-6  去掉 showUserNotice        → N-3c + N-4b 红
```

---

## 三、探针变更

### 新增（2 个文件，共 31 条断言）

`tools/settings-probe/VBatch3Medium.java` —— **25 条**：

| 编号 | 守什么 |
|---|---|
| A-0 | 日志捕获机制自检（它红则所有「必须有日志」的断言不算数） |
| D-1a/b/c | dispose 前 8 个引用非 null（对照）→ dispose 后全 null → `refreshPreviews()` 不抛异常 |
| N-1a~f | 越界数值夹回且 == `loadState`；有 warn；有提示；**再选不重复提示（自愈）** |
| N-2a~d | 合法值一位不变（含 0.47 / 3.3 这类非网格值 —— 只夹越界、不吸网格） |
| N-3a~e | 非法 HEX 不写进 pending / 有提示 / 9 位仍合法 / **写入前 trim** |
| N-4a~e | 坏快照不写半套 / 有提示 / 不写名字 / 下拉复位 / 不派生副本 |
| N-5 | 结构守门：非 runtimeSwitch 的数值预设字段必须都有滑块规格 |

`tools/settings-probe/VPreviewRank.java` —— **6 条**：五个移动场景下预览 rank == 生产 rank，
外加「并列时 rank 仍是 0/1/2/3 各一次」。

### 工具改动

- `build-v-probes.ps1`：登记 `VBatch3Medium` / `VPreviewRank`（**不登记 = 从不运行**）。
- `NeovideCursorConfigurable` 新增 2 个探针访问器：`disposeNullFlagsForProbe()`、
  `refreshPreviewsForProbe()`（8 项顺序约定写在 javadoc 里）。

### 既有断言

**零放宽**：本轮没有修改任何既有探针文件的断言；PASS 数从 1124 升到 1155，
唯一非零退出仍是 informational 的 `VRegr2`（2 条已知真缺口）。

---

## 四、代码审查（两位代理）与未修登记

```
code-reviewer  APPROVE；0 CRITICAL / 0 HIGH / 3 MEDIUM / 5 LOW
java-reviewer  0 CRITICAL / 0 HIGH；生命周期 / 状态同步 / 浮点边界 / 反射探针
               四项专项全部判定「无缺陷」
```

### 已处理

| 来源 | 问题 | 处理 |
|---|---|---|
| code-reviewer M-1 | 夹回后不回写快照 → 每次选中重复弹框（**已实测**） | 自愈回写 + `N-1f` 守门 + 变异 M-9 |
| java-reviewer 缺陷 2 | `isValidHexColor` 会 trim，写入却未 trim → 带空格的合法串会变黑（**真 bug**） | trim 后写入 + `N-3e` |
| java-reviewer 缺陷 4 | `paintDiagCheck` 的监听器解引用被 dispose 置 null 的字段（潜在 NPE） | 改用 `e.getSource()` |
| java-reviewer 缺陷 5 | `clampPresetNumerics` 未跳过 `runtimeSwitch`（会顺手改设备开关） | 加 `if (f.runtimeSwitch) continue;` |
| java-reviewer 缺陷 8 / code-reviewer L-3 | `D-1c` 走的是 `refreshPresetDisplay`，在 combo 为 null 处早退，名不副实 | 新增 `refreshPreviewsForProbe()`，断言直接打真实守卫 |
| code-reviewer L-2 | 用户可见提示里出现英文字段 key（违反纪律四-M） | 提示只报「几个参数」，key 留在 `idea.log` |
| code-reviewer L-1 | 文档行数与实测差 4 | 全部重测重写（20,178 / 4,294 / 30,605） |
| java-reviewer 缺陷 7 | `VPreviewRank` 注释称「默认 trailFactors 严格递减」—— 实际默认 `[0.86,0.86,0.32,0.32]` 有并列 | 注释改正并写明「删掉 `DISTINCT_FACTORS` 会全红」 |

### 未修、只登记（`plans/batch3-fix2.md` §七）

- **U-1**：Tools 菜单 `CursorPresetApplier.applyCustomAnimPreset()` 仍不 clamp。
- **U-2**：Tools 菜单 `CursorPresetApplier.applyCustomColorPreset()` 仍不校验 HEX。
- **U-3**：`NeovideCursorSettings.sanitizeColor()` 只收 7 位 → 9 位 RGBA 应用后生效、
  重启被打回默认色（第 1 批 M-1「颜色三套规则」未修；`N-3d` 已注明不替它背书）。
- **U-4**：`slopeTicks == 82 → 0` 的历史迁移 → 含 82 的旧快照仍「应用 82、重启后 0」。
- **U-5**：`AnimationPreview.rel` 与 `SpringTrail.CORNER_RELATIVE` 是两张角点顺序表，
  `VPreviewRank` 只比对排序、发现不了顺序表被改。收掉必须把算法抽到 core 共用。
- **U-6**：`blurOfSharpness(100) = 0.049999997f`，比 `loadState` 的 `0.05f` 低 1 ulp
  （观感无差别）。要逐位同源必须让端点直接取 `NeovideCursorSettings` 的常量。

修 U-1 / U-2 的正确位置是「把校验下沉到 `SettingsField.restoreSkipping` 或一个共用入口」——
那要动 `settings/` 层，与本轮白名单冲突，**下一轮优先**。

---

## 五、门禁与变异（可复现命令）

```powershell
# 全量门禁（唯一权威入口；不要并发跑两份，四-G-2 会假红）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo D:/deepseekwork/neovim-cursor-pr

# D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

```
MUT M-1  删 previewConfig = null                 → D-1b 红            ✅
MUT M-2  删 inlinePreview = null                 → D-1b 红            ✅
MUT M-3  clampPresetNumerics 直接返回空           → N-1a..N-1e 红（5） ✅
MUT M-4  clamp 的夹取方向对调                     → N-1a/b/c 红（3）   ✅
MUT M-5  关掉 isValidHexColor 判断                → N-3a/b/c 红（3）   ✅
MUT M-6  颜色坏快照分支去掉 showUserNotice        → N-3c + N-4b 红     ✅
MUT M-7  生产 SpringTrail 排序 > → >=             → R-1/R-2/R-5 红     ✅
MUT M-8  预览 ranksForAlignment 排序 > → >=       → R-1/R-2/R-5 红     ✅
MUT M-9  去掉「夹回后自愈回写」                    → N-1f 红            ✅
结果：RED-OK=9 / NOT-RED=0；撤销后 VBatch3Medium 25/0、VPreviewRank 6/0
```

> 变异全部在**全新编译目录**下全量重编译后运行（不复用旧字节码）；
> `ui/SpringTrail.java` 的 M-7 改完即撤销，`git diff --stat` 为空。
> 两个审查代理各自跑门禁时都撞上过并发假红（`NoClassDefFoundError`），
> 最终数字以本报告 §一 的单份运行为准。

---

## 六、需要实机确认

| 项 | 怎么确认 |
|---|---|
| P-M-11 | 打开设置页再关闭，反复几次；观察是否仍有预览相关异常或内存增长（`disposeUIResources` 后三棵组件树应可回收） |
| P-M-5 | 手改 `customAnimPresets` 里某条为 `glowLayers=99` → 选中该预设 → 应看到「已按合法区间应用」提示一次；再选一次**不应**再弹；重启后值不变 |
| P-M-6 / P-M-3 | 把某条颜色预设快照改成 `tailColor=not-a-color` → 选中 → 应看到「颜色格式非法…本次切换已取消」，颜色保持不变 |
| P-M-8 | 无法肉眼直接验证（两份实现当前一致）；它的价值在「以后改生产排序会被探针拦住」 |

---

## 七、本轮改动文件清单

```
M  CLAUDE.md                                   规模数字重测 + 探针名单加两个新探针
M  START-HERE.md                               规模数字重测
M  docs/NEXT-STEPS.md                          探针数 106→108、行数重测
A  plans/batch3-fix2.md                        计划 + 四段证据 + §七 已知未修 + §八 审查记录
A  docs/BATCH3-FIX-REPORT2-2026-10-31.md       本报告
M  ui/settings/NeovideCursorConfigurable.java  P-M-11 / P-M-5 / P-M-6 / P-M-3
M  ui/settings/AnimationPreview.java           P-M-8（排序抽成 static 纯函数）
M  tools/settings-probe/build-v-probes.ps1     两个新探针登记
A  tools/settings-probe/VBatch3Medium.java     25 条守门断言
A  tools/settings-probe/VPreviewRank.java      6 条守门断言
```

`settings/` 层**未改一个字节**（只读复用 `SettingsField.isValidHexColor` /
`Field.readState` / `Field.byKey`）；`core/` 未改；`docs/BUG-LOG.md` 未触碰。
白名单外字节变化：**无**（变异的临时改动已全部撤销）。

代码改动在提交 `e25a4ff`（已推 `fork/main`）；本报告作为文档单独提交。
