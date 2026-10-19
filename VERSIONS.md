# 版本对照表（权威）

> 生成于 2026-10-18。**每次构建前必查此表，每次构建后必追加一行。**
> 这是本项目唯一可信的版本记录 —— 之前的失误正是缺少它导致的。

## 关键教训

**我在 `uni` 基础上连续叠加 9 次改动，却没有记录每次改了什么、对应哪个 jar。**
结果用户无法分辨"哪一版好"，我也无法定位是哪次改动破坏了手感。

**根因：从 `0.6.0-faithful` 起，我把 `ACTIVE_DELAY_MS` 从 12ms 改成了 8ms，
并且删掉了 `MIN_ANIMATION_FRAMES`。而这两项是 `uni` 手感的关键组成。**

---

## 全部版本（按构建时间）

| 时间 | 版本 | 常驻刷新 | 帧间隔 | 辉光 | 6帧补偿 | 短跳判据 | 哈希 |
|---|---|---|---|---|---|---|---|
| 10-17 19:28 | `0.5.0-alwaysfps` | ✅ | 8ms | 6 | 有 | 归一化 | `AE818C4D` |
| 10-17 19:37 | `0.5.1-recovery` | ✅ | 8ms | 6 | 有 | 归一化 | `12099C72` |
| **10-17 19:50** | **`0.5.2-unified`（uni）** | **✅** | **12ms** | **6** | **有** | **归一化** | **`B4C8A284`** |
| 10-18 14:20 | `0.6.0-faithful` | ✅ | 8ms | 8 | **无** | — | `EF5B6FCF` |
| 10-18 14:27 | `0.6.1-typing80` | ✅ | 8ms | 8 | 无 | — | `FC3441D7` |
| 10-18 14:35 | `0.6.2-delreset` | ✅ | 8ms | 8 | 无 | — | `5ABC6092` |
| 10-18 14:52 | `0.6.3-stretch` | ✅ | 8ms | 8 | 无 | — | `815D1D79` |
| 10-18 15:02 | `0.6.4-cjkfix` | ✅ | 8ms | 8 | 无 | — | `BA8052B1` |
| 10-18 15:07 | `0.6.5-charbased` | ✅ | 8ms | 8 | 无 | 归一化 | `3C8226F7` |
| 10-18 15:32 | `0.7.0-auditfix` | ✅ | 8ms | 8 | 无 | 归一化 | `48D4C046` |
| **10-18 15:44** | **`0.7.1-offset`** | ✅ | 8ms | 8 | 无 | **offset** | **`EA2DC457`** |
| 10-18 15:53 | `0.7.2-fps-indep` | ✅ | 8ms | 8 | 无 | 像素+字符 | `E9884321` |
| **10-18 21:32** | **`0.9.1-zeroopt`** | **✅** | **8ms** | **8** | **无** | **offset** | **`C718675E046CAB82`** |
| 10-19 12:29 | `0.9.2-imetime-fade` | ✅ | 8ms | 8 | 无 | offset | `7AC9D80E58CD8567` |
| **10-19 13:05** | **`0.9.3-imegeom`** | **✅** | **8ms** | **8** | **无** | **offset + 几何判据** | **`610AE7E9676E2392`** |
| **10-19 23:40** | **`0.9.4-settings`** | **✅** | **8ms** | **8** | **无** | **offset + 几何判据**（未改） | **`D7DC6665ADE902BE`** |

> ⚠️ **本表 0.7.3 ～ 0.9.0 未记录**（那些构建发生在记录习惯中断期间）。
> 不要为了"补齐"而编造内容；需要追溯请直接看 `dist\` 里的 jar 文件名与时间戳。
> `0.9.0-ahf-default`（`FA161E4F77C8C999`）是当前基线 / 回退目标，它未列入上表但确实存在。

### `0.9.1-zeroopt` 改动摘要（零观感优化·批次1）

**目标：不改变任何影响手感的常量、几何算法与调度语义，只降开销。**

Tier 0（五项）：
1. **fps 日志门控** —— `LOG.info` → `LOG.isDebugEnabled()` 门控的 `LOG.debug`；统计窗口 1s → 5s；行尾追加编辑器标识（`editor=<Kind>#<hash>`，必须留在行尾以免破坏 `build/analyze-log.ps1` 与 `diag/extract.py` 的固定顺序正则）
2. **每帧分配复用** —— `drawQuadGlow` 的 `Path2D` 与两个 `double[4]` 提为字段（几何算式一字未改）
3. **删空转链路** —— `smoothedDt` / `effectiveDt`（`assignRanks` 从未使用该形参）
4. **删 7 个零引用字段** —— `cursorDisappearDelay`、`cursorFadeOutDuration`、`snapAnimationLength`、`useHardSnap`、`leadingSnapFactor`、`shortJumpMaxChars`、`typingMergeChars`
5. **`refreshTarget()` 耗时埋点** —— 仅 >2ms 且 Debug 开启时记一行

Tier 1（一项）：
6. **全局共享调度线程** `ui/RenderScheduler.java` —— N 条 per-editor 调度线程 → 1 条；**EDT 任务粒度不变**（每个到期 animator 各自一次 `invokeLater`，未照搬上游的批量 `runDue`）；每 animator 保留自己的 `framePending`

修复 r2（t4 验证发现的真实回退）：
7. **`nextDueNanos` 推算基准** —— 从「tick 结束的 `now` + delay」改为「上次投递的到期值 + delay」并 `Math.max(·, now)` 追赶。否则 EDT 繁忙（单帧 > delay）时比基线多等一个 delay：实测 R2 59→44、R4 59→37。修复后回到 56/57/60/60

**验证证据**（可复现）：
- 物理/几何 **逐位一致**：三份 parity harness 的 base 与 new 输出字节完全相同（含退化四边形与零长边用例）
- 19 项观感常量 **DIFF = 0**
- 帧率矩阵（3 轮取最大值）：R1 175→188、R2 58→56、R3 176→188、R4 59→57、R5 59→60、R6 58→60
- 详见 `VERIFY-BATCH1.md` / `REVIEW-BATCH1.md` / 契约 `OPTIM-BATCH1.md`

**⚠️ 未验证项**：真机 IDEA 内的主观手感（探针是 headless + 伪造 Editor）。代码层面有证据，手感需实机确认。

### `0.9.3-imegeom` 改动摘要（IME 组合态判据换成几何判据）

**只改一处判断逻辑，不动任何常量/几何/调度。**

问题：`0.9.2` 的 F1-b 判据是「`getOffset()` 没变、但视觉列变了」。水杉输入法组合期间
光标会进入「拼音音节内部」——offset 和 column **都变了**，判据前提不成立，中间态被正常画了出去。

修法（F1-c）：改用**几何判据** —— 比较
`editor.offsetToXY(offset).x` 与 `editor.visualPositionToXY(vp).x`，不一致即视为中间态，本帧跳过重算。
依据是平台这两个 API 走**不同的换算路径**（IDEA 261 字节码已核实）：
前者按 offset 迭代 fragment，后者按视觉列迭代，正常时给出同一个 x，组合态下因组合串与最终汉字宽度不同而分岔。

保持：`IME_MAX_SKIP_FRAMES = 15` 冻结上界原封不动（防止再次出现「无限期冻结」）。
新增防护：排除 **virtual space**（光标在行尾之后时两个 API 本就应当不同，不排除会引入新缺陷）。
性能：`visualPositionToXY` 仍每帧只算一次（与判据共用同一个 `p`），`getOffset()` 仍每帧 1 次。

**验证证据**（可复现）：
- `tools\ime-probe\run-ime-geom-test.ps1` —— **29 项边界全通过**（驱动真实 `refreshTarget`，Proxy 注入坐标）
- parity 三份 harness base/new **逐字节一致**（哈希与 0.9.1 相同）
- 常量审计 **19 项 DIFF = 1**，唯一 DIFF 是 `hideNativeCaret`（0.9.2 已改，非本批引入）
- jar 条目级：相对 0.9.2 **只有 `CaretAnimator.class` 与 `plugin.xml` 变化**

**⚠️ 未验证项（重要）**：本次**没有在真机上跑探针**（用户选择"直接修复"）。
几何判据是否在真机上真的对水杉生效，仍需一次实机确认。
`dist\neovide-ime-probe.zip` 是现成的探针插件，装上打字即出日志，可随时补做这个验证。


### `0.9.4-settings` 改动摘要（设置界面 + 三个前置改造 + alwaysHighFps 兜底修复）

**这是本插件第一个带「设置界面」的版本。** 观感常量一个没动（`NeovideConfig.java` git diff 零输出），
20 项禁改字面量 + 15 项调度常量逐项复核缺失数 0。

#### 一、设置界面（新增）

`Settings | Tools | Neovide Cursor`，16 个可调参数：

| 分组 | 字段 |
|---|---|
| 总开关 | `enabled` / `trailMode` / `hideNativeCaret` / `alwaysHighFps` |
| 淡出 | `overlayFadeOut` / `cursorDisappearDelay` / `cursorFadeOutDuration` |
| 颜色与辉光 | `tailColor` / `tailOpacity` / `useShadow` / `shadowColor` / `glowOpacity` / `glowLayers` / `shadowBlurFactor` |
| 拖尾手感 | `trailSize` / **拖尾拉伸强度**（复合滑块，写标量 `stretchRatioK`，`trailFactors` 由公式派生） |

- **OK 生效，不做实时预览**（`apply()` 才动运行期状态，Cancel 无需回滚）。
- 联动置灰 4/2/2 条：`useShadow` → 颜色/辉光四项；`overlayFadeOut` → 淡出两项；`trailMode=AFTERIMAGE` → 拖尾手感两项。
- 滑块都是 int 刻度，右侧显示**真实物理量**（`75 ms` / `1.00` / `8 层` / `4.5×  后缘 110 ms / 前缘 24 ms`）。
- 关键警告文案逐字上屏（常驻刷新的 EDT 代价、隐藏原生光标的 `.icls` 落盘副作用、辉光层数的 CPU 成本等）。

#### 二、三个前置改造（设置界面能存在的前提）

1. **`NeovideCursorSettings` 持久化服务** —— `@State` + `PersistentStateComponent`，
   落 `<config>/options/NeovideCursorSettings.xml`（`roamingType=DISABLED`：`alwaysHighFps` 等是性能相关设置）。
   从此「持久化真相」只有一处；`NeovideConfig` 保留为运行期快照 + 默认值来源（热路径零改动、parity harness 依赖它）。
2. **`PropertiesComponent` 两个旧键一次性迁移并删除** —— 三分支（旧键存在 → 搬旧值 / 无旧键 → 用默认 / 已迁移 → 只删不读），
   落盘成功才删键。消除了「`NeovideConfig` 字段 / `PropertiesComponent` / 新 XML」三真相源。
3. **`NeovideCaretManager.applyConfig()` 编排** —— S0 快照 → S1 写字段 → S2–S6 按序调 setter（仅在值真的变化时）
   → S7 刷新三类被缓存的值（`fillColor/glowColor`、`AfterimageTrail.lifetime`、`glowPadding`）→ S8 重排调度。
   它是**唯一的全局生效入口**：直接改 `CONFIG` 字段不会刷新这些缓存。

#### 三、`alwaysHighFps` 兜底 bug 修复

旧代码在 `restorePersistedSettings()` 里读不到键时兜底 **`false`**，而 `NeovideConfig.alwaysHighFps` 的字段默认是 **`true`** ——
**干净安装 + 从没点过菜单的用户，实际拿到 `false`，与文档宣称相反。** 本版删掉整个方法，默认值只剩 `State` 一处。

#### 四、三个线程/落盘回归（第二轮复审抓到，已修）

| 编号 | 缺陷 | 修法 |
|---|---|---|
| R-01 | `if (!CONFIG.enabled) return;` 排在 `syncStateToConfig()` **之前** → 用户关掉插件重启后覆盖层又回来 | 同步提到早退之前，同步后**重判一次** |
| R-02 | 非 EDT 分支先 `syncStateToConfig()` 再 `invokeLater` → EDT 侧 S0 拍到新值 → setter 链**永不执行** | 先拍 `Snapshot` → 再同步 → 带快照 `invokeLater` |
| R-03 | 非 EDT 迁移：日志说「旧键保留」、回调却**无条件删键**，且 `stateVersion` 永不收敛 | `persistQuietly()` 改三态 `{CONFIRMED, DEFERRED, FAILED}`，DEFERRED 不删键也不回退版本号 |

#### 五、验证证据（可复现）

- **parity** 三份 harness base/new **逐字节一致**（`run-parity.ps1`）
- **常量审计** 19 项 DIFF = 1，唯一 DIFF 是 `hideNativeCaret`（0.9.2 已改，非本批引入）
- **IME 几何判据** 29 项边界全过（`run-ime-geom-test.ps1`）
- **t4 验证探针** 1 + 3 + 52 + 26 + 18 = 100 项全过（`run-verify-t4.ps1`）
- **非 EDT 专项探针** 13 项全过（`run-non-edt-probe.ps1`）—— 假 `Application` 的 `isDispatchThread()` **恒 false**，
  这是此前 5 个探针的共同盲区（全部 `return true`），R-02/R-03 正是由此漏网
- **辉光死区定量**（`GlowRadiusProbe`）最大滞后 1 px，判**第一期不改**（见契约 §3.3.4）

#### 六、⚠️ 未验证项（必须如实看待）

1. **U-1 真机 EDT 抢占下的手感** —— 离线探针用 Proxy 伪造 `Editor`，测不到 IDEA 自身绘制对 EDT 的抢占。
2. **U-2 真实 `Application.saveSettings()` 落盘** —— 离线无 `Application`，走的是「无法确认写入 → DEFERRED」分支；
   真机上第一次启动是否如期写出 `NeovideCursorSettings.xml`、旧键是否如期删除，**尚未实测**。
3. **未在真机打开过设置面板**看实际渲染（离线探针只验证了 `createComponent()` 能建出 65 个组件、字段绑定正确）。
4. **未验证安装后插件加载**（本批全程未装 jar、未重启 IDEA）。

回退：`build\rollback-0.9.4.ps1`（回 0.9.3-imegeom，`610AE7E9676E2392`）。

---

## 两个候选「最佳」的具体差异

用户反馈过两次正面评价，对应两个不同的 jar：

### 候选 A：`uni` = `0.5.2-unified`（`B4C8A284`）

> 用户原话：「我去这版删除不卡了」「常驻刷新挡位非常舒适」

- 帧间隔 **12ms**、辉光 **6 层**、**有** 6 帧补偿
- 短跳判据：**归一化**（`hypot(dx/2, dy/32) <= 8`）
- **已知缺陷**：中文 1 个字 = 20px → `jumpX = 10 > 8` → 落入跨行分支
  → `trailSize=1` 使前缘时长 0 → 短路瞬移 → **中文卡、英文丝滑**

### 候选 B：`0.7.1-offset`（`EA2DC457`）

> 用户原话：「常驻刷新变得可用了但是没有之前好」

- 帧间隔 **8ms**、辉光 8 层、**无** 6 帧补偿
- 短跳判据：**offset**（`|Δoffset| <= 3`）—— **中文问题被根治**
- **问题**：8ms 比 12ms 快 50%，整体"傻快"

---

## 未验证的组合（下一步待定）

**用户理想的可能是两者相加：**

| 项 | 取值 | 来源 |
|---|---|---|
| 帧间隔 | **12ms** | uni |
| 辉光 | **6 层** | uni |
| 6 帧补偿 | **保留** | uni |
| 短跳判据 | **offset** | 0.7.1 |

**即：以 `uni` 为基底，只替换短跳判据为 offset 方案。改动约 20 行，不触碰其他参数。**

---

## 「舒适」到底对应哪个 jar —— 尚待确认

用户说：「我说舒适是基于 uni 改的那个版本但是不知道哪一代」

**按时间线推断：**
- `uni`（19:50，昨天）→ 用户说"删除不卡"
- 之后 9 次改动都在今天 14:20 之后
- 用户说"常驻刷新变得可用了"是在 `0.7.1-offset` 装完之后

**所以「舒适」可能指 `uni` 本身，也可能指 `0.7.1`。仍需用户实测确认。**

---

## 使用规则（自本表建立起生效）

1. **每次构建前**：先在本表查重，确认没有重复造版本
2. **每次构建后**：立刻追加一行，写清「改了什么 + 帧间隔 + 判据类型」
3. **jar 命名**：`0.X.Y-<改动关键词>`，关键词必须能望文知义
4. **一次只改一个变量**，改完立刻让用户验证，不合意马上回滚
5. **回滚命令**必须同时给出

---

## 当前安装（2026-10-19 23:40 只读实测校正）

```
v0.9.3-imegeom
大小: 44,523 B
哈希: 610AE7E9676E2392D866CA919EAF6E8C249E8FDF551B2AA51FED8C07298828C6
路径: C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\
      jetbrains-neovide-cursor-0.5.0.jar
注意: 文件名仍是 0.5.0.jar（安装脚本固定此名），实际内容是 0.9.3-imegeom
```

> 本节此前写的是 `v0.9.0-ahf-default`，已过时。t6 集成交付时用
> `Get-FileHash` 只读实测校正为上表值（未安装、未重启 IDEA，仅比对哈希）。

## 待安装：`0.9.4-settings`

```
dist\jetbrains-neovide-cursor-0.9.4-settings.jar
大小: 70,056 B
SHA256: D7DC6665ADE902BEC6DBF74DBF9F959522C1F085CAAC0B8B7DDE0A5A9B0641EE
前16位: D7DC6665ADE902BE

安装：先完全退出 IDEA → Move-Item 旧 jar 到 .bak → Copy-Item 新 jar → 重启 IDEA
回退：build\rollback-0.9.4.ps1（回 0.9.3-imegeom / 610AE7E9676E2392）
```

> 上一版「待安装 0.9.1-zeroopt」已被 0.9.2 / 0.9.3 / 0.9.4 依次取代，历史记录见上表。
> ⚠️ 0.9.4 的「当前安装」状态以 `START-HERE.md` 与用户实际安装为准 —— **本文件不擅自更新它**，
> 因为本批全程遵守「不装 jar、不重启 IDEA」，安装动作留给用户手动完成。
