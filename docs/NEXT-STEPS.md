# 下一步做什么

> 状态：**v0.9.102 本地已装**（未发布）· fork/main 与本地是否同步用 `git rev-list --left-right --count fork/main...HEAD` 核实（**不写死 hash**）· git tag 停在 0.9.83
> 本文最后更新：2026-10-27

> ⚠️ **本文的数字曾长期失真，请勿直接采信或转抄。**
>
> 历史上这里写过「探针清单 82 个 / 通过 72 / 82」——那个数字来自**一份从未执行过的脚本**，
> `CLAUDE.md` 第 11 章专门批评过这件事。后来它还在原地，又被抄进了别处。
>
> **任何状态数字，跑一遍再写。** 命令见 `CLAUDE.md` 第十一章「状态数字的自检命令」。

---

## 一、当前硬状态（2026-10-27 实测）

| 项 | 值 |
|---|---|
| 版本 | **v0.9.98**（读 jar 内 `META-INF/plugin.xml` 确认，不是文件名） |
| dist 最新 jar | 0.9.92（2026-10-27 06:46） |
| git tag | 停在 **v0.9.83**（0.9.84~0.9.98 缺 15 个，版本未发布） |
| 探针文件 | **94 个 `V*.java`**（全部 .java 109 个，2026-10-28 实测） |
| 探针基线 | **ALL PASS（0 红）** —— 2026-10-28 修绿了最后 6 个过期断言探针，见 §7.1 第 1 项 |
| D0 门禁 | PASS（`core/NeovideConfig` 零 diff） |
| PhysicsParity | PASS |
| 生产代码 | **15,164 行 / 34 文件** |
| 探针代码 | **23,570 行 / 106 文件** |
| 构建 | javac + 手写 plugin.xml；Gradle 也完整入库（两条路径并存） |

---

## 二、已完成（不要再做一次）

| 项 | 完成版本 | 守门探针 |
|---|---|---|
| 预览窗失真（`charsMoved` 显式设置） | 0.9.65 | `VPreviewBranch` |
| 预览实时（尺寸 + RGB 真的进入绘制） | 0.9.67 | `VPreviewRealtime` |
| 面板文案三处缺失说明 | 0.9.72 | `VTextFacts` |
| 设置面板 S1/S3 重构 | 0.9.60 / 0.9.62 | 组件树逐位比对 |
| `SPECS[]` 下标改按 key 查 | 0.9.71 | `VAddSetting` |
| `ColorPanelBinding` 三向同步 | 0.9.71 | `VColorBinding` |
| `loadState` 漏拷 5 字段 | 0.9.72 | `VLoadState` |
| 自定义预设恢复未跳过环境开关 | 0.9.72 | `VLoadState` L-5 |
| 平滑移出预设 | 0.9.73 | `VSmoothGlobal` |
| 审查抓到的 4 条 bug | 0.9.73 | `VReviewFixes` |
| P4 技术债（死代码 / 死数据 / HEX 校验） | 0.9.74 | `VQuadProbeTruth` |
| RGB 色相循环 + 彩虹预设 | 0.9.75 | `VRgbWash` |
| 亚毫秒调度 + `targetFps` | 0.9.76 | `VDeadField` |
| **rank 塌缩（单行斜缎带）** | **0.9.77** | `VRankCollapse` |
| 斜度拆成独立参数 | 0.9.79 | `VSlopePreset` |
| 斜度进预设域 | 0.9.80 | `VSlopePreset` |
| 斜度语义改对（0=纯矩形） | 0.9.81 | `VSlopePreset` |
| 斜度迁移（82→100，消除幽灵副本） | 0.9.82 | `VSlopeMigration` |
| 探针覆盖度 49 → 80 | 0.9.83 | — |
| 光标渐变改为双拼 | 0.9.85 | `VRgbWash` |
| 彩虹/双拼分离三条互斥路径 | 0.9.86 | `VRainbowSep` |
| 预设斜度：只有纯平滑倾斜 | 0.9.87 | `VSlopePreset` |
| `RUNTIME_SWITCH_KEYS` 4 → 9 项 | 0.9.87 | `VSwitchKeysGap` |
| 清理 36 个临时排查文件 | 0.9.87 | — |

---

## 三、明确放弃的（有实测依据，不要重试）

### 1. 前缘硬吸附（`useHardSnap`）

上游机制：对齐度 > 0.5 的角点时长置 0 → 瞬移到位 → 形变体堆在一侧（「半边」观感）。

**0.9.83 实测后放弃**：

```
阈值      吸附角数   拉伸px   斜度px
关闭        0         8.26     4.06
0.3~0.707   2        11.58     2.11   ← 斜度反而变小
>0.707     0         8.26     4.06   ← 完全不触发
```

- 对齐度是**离散的**（纯轴向恒 ±0.707），没有可调区间
- 2 个角同时吸附会**压小斜度**（消除同侧差，而斜正来自那个差）

要真做得先改 rank 体系（用移动向量而非角点残余位移）。
推导已写进 `TrailCorner.java` 注释，也在 `README.zh-CN.md` 的「不采用前缘硬吸附」一节。

### 2. 自适应辉光降级
用户 0.9.64 否决：与已有「多光标关闭辉光」开关语义重复且互斥失效。

### 3. 描边替代多层 fill 画辉光
实测 0.94x（更慢），8/6/4 层各档稳定更慢。

### 4. `timeBeginPeriod` / 忙等 / `parkNanos` 提帧率
实测：`timeBeginPeriod` 无收益（调用前后都 218~225fps）；
忙等更差（180 vs 221）；`parkNanos(4ms)` 实际 8.65ms。

---

## 四、剩余 6 个探针失败（都是过期基线，非新 bug）

| 探针 | 失败数 | 原因 |
|---|---|---|
| `VSepBound` | 7 | 预设名含分隔符被静默丢弃 |
| `VReset2` | 2 | 勾选换预设丢失 / 派生存活 |
| `VTools`/`VHints`/`VCustom`/`VSleep` | 各 1 | 字段数 / 文案 / 副本判据 |

> `VRegr2`（13 通过 / 2 失败）是 **informational**，不计入。
> `VDefects` 与 `VSnap` 已于 2026-10-27 修绿 —— 它们的红不是「过期断言」，
> 而是**索引契约破裂**（探针用 `get(0)` / `get(8)` 定位控件，面板重排后指向了别的字段）。

**预计工作量**：半天。不影响用户功能，但能降低噪声。

---

## 四-B、0.9.87 新发现：「左半边」其实做得到

之前记的是「做不到，上游 2 角点、我们 4 角」。**实测后结论要修正**：

```
锁住的角        左边缘斜  右边缘斜  拉伸px
基线            2.11     4.06     8.26
TR+BR（前缘）   2.11     0.00    11.58   ← 前缘完全直 + 后缘斜 = 左半边
TL+BL（后缘）   0.00     4.06     6.16   ← 后缘直 + 前缘斜 = 右半边
TR（前缘单角）   2.11     7.43    11.58
```

**锁「运动方向那一侧的两个角」，前缘斜度精确为 0.00，就是「左半边」。**
拉伸还从 8.26 涨到 11.58，效果更强。

之前误判的原因：只量了 `max(左,右)`，掩盖了「斜度只在后缘」这个事实。
分离测量后真相完全不同 —— **不是没斜度，是斜度只在后缘。**

**不需要新机制**：`DampedSpring.setAnimationLength()` 是 public，
物理层本来就支持。缺的只是「锁哪两个角」的判断。

**用户已决定（2026-10-25）：做成开关（B 方案），但先不做。**

```
形态：加一个复选框「前缘贴合（半边拖尾）」，默认关
原因：它改变整体观感，做成可选最安全
状态：⬜ 待实现（笔记本没电，押后）
```

实现要点（已验证可行，不需要新机制）：
1. `TrailCorner` 加 `setLeadingSnap()`：把 `springX`/`springY` 的
   `setAnimationLength(0.0)`（该方法已是 public）
2. `SpringTrail.assignRanks()` 末尾，按 `config.leadingSnap` 给
   `order[2]` 和 `order[3]`（rank 2/3 = 前缘两角）调 `setLeadingSnap()`
3. State 加 `leadingSnap` 字段 —— 注意**六层拷贝**（纪律四-B 写的「四层」只覆盖持久化回路，
   缺 ⑤消费者 / ⑥UI 控件；完整清单见 CLAUDE.md 陷阱 2 与 §7.0 落点表）
4. 面板加复选框 —— **加 UI 控件前先问用户**（纪律）
5. 守门探针：断言「开启时前缘斜度 == 0.00 且后缘斜度 > 0」

---

## 四-C、用户手上的待办：三版基础预设的手感（2026-10-28 记）

用户正在**自己调**三个内置动画预设（`CursorPresets.ANIM_PRESETS` 三档）的手感。
调好之后要做两件事，**必须一起做**：

1. 把最终数值写入 `settings/CursorPresets.java` 的 `ANIM_PRESETS` 三档；
2. 同步写入**初始 / 默认值** —— `settings/NeovideCursorSettings.java` 里 `State` 的字段默认值，
   以及 `tools/settings-probe/VDefaultFreeze.java` 的 `GOLDEN` 金表。

**为什么两处必须一起改**：默认值只影响「全新用户 / 刚清空配置」那一瞬间，
预设表只影响「用户点了预设」。两者不一致，就会重现 VDefaultFreeze 守的那类问题 ——
新用户拿到的观感，和他点一下同名预设得到的观感不是同一个。

**改完还要做的事**：
- `VDefaultFreeze` 的金表逐字段登记（不加就红，那是契约变更，不是缺陷）；
- 若三档预设的**字段集合**没变（只是数值变），`VPreset` 的字段数断言（30）不用动；
- 跑全量门禁，确认基线仍是 `ALL PASS`（2026-10-28 起 0 红；此前的「6 红名单」已全部修绿，见 §7.1.1）。

**当前状态：用户调参中，数值尚未提供。不要凭猜测写入任何数字。**

---

## 五、可以考虑做的新功能

| # | 功能 | 依据 | 工作量 |
|---|---|---|---|
| 1 | 帧率 144 档的**端到端验证** | `targetFps` 已接通，实际 fps 需诊断日志确认 | 小 |
| 2 | 块选区 `charsMoved` 语义拆参数 | 现在靠传 -1 绕开，判据依赖光标宽度 | 中 |
| 3 | `[trail]` 诊断日志降频 | 8 光标时 328KB / 2 分钟 | 小 |
| 4 | **设置页加打赏入口**（爱发电 / 微信赞赏码） | 用户 2026-10-28 计划：左侧放二维码，点击可扫码或跳链接 | 小（**低优先级**） |

> 第 4 条的约束（用户原话）：**等插件发布之后再做**。
> 实现时注意：二维码图片要打进 jar 资源（`PluginInfoBar` 已有 GitHub 链接的现成样式可复用），
> 且**不要**自动联网加载图片 —— 那会引入一个新的对外请求。

---

## 五-B、明确暂缓（用户 2026-10-28 决定「先不做，只记录」）

| 项 | 现状 | 真要做的代价 |
|---|---|---|
| **设置面板「光标」组布局重排** | 顺序是 光标宽度(滑块) → 光标渐变(复选框) → 光标高度(滑块)，用户形容「像夹心」 | 见下方「重排会改什么」 |
| **说明文案整体重写**（每条都改成「是什么 + 是否随预设」） | 已加 `[全局]` / `[颜色预设]` 标签与顶部总说明，正文未改 | 7 条警告文案有探针字面量依赖（已知 `VerifyPanel:193` 依赖「常驻按 8ms 刷新」；`docs/archive/design/SETTINGS-UI-CONTRACT.md` 记录 7 条须逐字保留）。逐条重写要同时改探针并重新核对每条事实 |

### 「光标」组重排会改什么（用户问：具体动几处）

把 宽度(滑块) → 渐变(复选框) → 高度(滑块) 重排成 宽度 → 高度 → 渐变，需要动 **4 个地方**：

| # | 位置 | 改什么 | 漏了会怎样 |
|---|---|---|---|
| 1 | `NeovideCursorConfigurable.buildCursorGroup()` | 调换三个 `cur.row(...)` 的先后 | 界面顺序没变 |
| 2 | `tools/settings-probe/VUi.java` 的 `KEYS` | 把 `rgbGradient` 从第 2 位移到第 3 位 | **下游探针静默测错字段**（§7.0 的 C 节，0.9.79 出过一次全线错位） |
| 3 | 同文件 `SPEC` | 同步调整（只对滑块生效，复选框不占位） | 同上 |
| 4 | 任何按索引取控件的探针 | `VHints` 的 `idxAnim = 8` 这类硬编码下标 | 探针「通过/失败」都不是它说的那件事 |

**风险等级：中**。不是逻辑风险，而是「不报错、只静默测错」的那类风险 ——
所以要么不做，要么做完立刻跑全量门禁并逐条核对 `VUi.KEYS` 与实际渲染顺序。

---

## 五-C、预览要「像真实打字/删除」（2026-10-28 用户提出）

方案见 [design/PREVIEW-TYPING-SIM.md](design/PREVIEW-TYPING-SIM.md)。

**已实现（0.9.98）**：打字/退格间隔加 ±30% 抖动（固定种子 20261028L）。

**待做（用户 2026-10-28 确认）**：
- 预览窗加「节奏：慢 / 正常 / 快」三档（缩放 `TYPE_MS` / `BACKSPACE_MS` / `HOLD_AFTER_TYPE_MS`）；
- **「加入停顿」开关不做**（用户明确「停顿算了」）。

> ⚠️ 加下拉会让 `VAddSetting:115` 的 `combos == 4`、`VerifyPanel:108` 变红 ——
> 那是契约变更不是缺陷，按 §7.0 的 B 表改常数。

---

## 六、文档地图

| 文档 | 内容 |
|---|---|
| [WORK-DISCIPLINE.md](WORK-DISCIPLINE.md) | **工作纪律（必读）** 741 行，13 条编号纪律 + 13 个专题 |
| [design/RANK-COLLAPSE-FIX.md](design/RANK-COLLAPSE-FIX.md) | 为什么单行打字画不出斜缎带 |
| [design/UPSTREAM-VSCODE-RESEARCH.md](design/UPSTREAM-VSCODE-RESEARCH.md) | 上游四版本对比 + 我们还缺什么 |
| [design/RGB-WASH-REFERENCE.md](design/RGB-WASH-REFERENCE.md) | 上游 wash 实现参数 |
| [PERFORMANCE-FPS.md](PERFORMANCE-FPS.md) | 帧率实测 + 平台限制 |
| [IDEA-PLATFORM-OPTIMIZATION.md](IDEA-PLATFORM-OPTIMIZATION.md) | 平台规范 + 240Hz 核查 |
| [RELEASE-SAFETY.md](RELEASE-SAFETY.md) | 升级不掉用户配置的流程 |
| [DEAD-PROBE-CLEANUP.md](DEAD-PROBE-CLEANUP.md) | 探针覆盖度 49→80 的过程 |
| [VIDEO-SCRIPT.md](VIDEO-SCRIPT.md) | 演示视频脚本（未拍） |
| [BUG-LOG.md](BUG-LOG.md) | 历史 bug 记录 |

---

## 七、未完成清单（2026-10-28 由 BUG-007 一轮核对）

> 这一节只做**清单**，不改变任何既有条目的判断。做之前仍按 CLAUDE.md §7.0 的落点表走。

### 7.1 本插件自己的（可做）

| # | 项 | 位置 | 备注 |
|---|---|---|---|
| 1 | ✅ **已完成 2026-10-28**：6 个过期断言探针全部修绿 —— `VTools / VHints / VCustom / VReset2 / VSepBound / VSleep` | `tools/settings-probe/` | 只改探针、**生产代码零改动**；门禁 `V-PROBES RESULT: ALL PASS`（另有一次独立复跑核对，非本人自证）。逐条根因与修法见 §7.1.1。提交号见 `git log -1` |
| 2 | 旧 `[trail]` 日志仍是「每 15 次慢路径一条、无上限」 | `CaretAnimator.tickSpring` | 本轮新增的 trace 已有 5000 行上限 + `[clear]` 去重，**旧行未动** |
| 3 | 「半边拖尾」开关（`leadingSnap`，默认关） | 5 个落点见 §四-B | 用户已决定形态，但**先不做** |
| 4 | 三版基础预设的最终手感数值落库 | `CursorPresets.ANIM_PRESETS` + `State` 默认值 | 等用户给数值（§四-C） |
| 5 | 块选区 `charsMoved` 语义拆参数 | `TrailCorner.jump` / `CaretAnimator` | §五-2 |
| 6 | 预览窗「节奏：慢 / 正常 / 快」三档 | `AnimationPreview` | 加下拉会让 `VAddSetting:115` 的 `combos == 4`、`VerifyPanel:108` 变红——契约变更，按 §7.0 B 表改常数 |
| 7 | 设置页打赏入口 | `PluginInfoBar` | 约束：**发布之后**再做；图片打进 jar，不联网加载 |
| 8 | `targetFps=144` 的端到端帧率验证 | 诊断日志 `activeFps` / `real fps` | 工具已具备（本轮新增 `[tick]`），但未正式跑一次留档 |

#### 7.1.1 六个探针的根因与修法（2026-10-28 存档，供日后复查）

两类根因，修法不同（分类判据见 `CLAUDE.md` §7.0 的 B/C 两节）：

| 探针:断言 | 类别 | 原来为什么红（实测证据） | 改成了什么方向 |
|---|---|---|---|
| `VHints:H-6` | **索引契约破裂** | 写死 `ui.sliders.get(8)`；而 `VUi.KEYS[8]` 是 `cursorHeightRatio`（量程 30..100），`animationLength` 在 `[5]`。写 `28` 被夹到 30 → 跨行提示仍是 100 ms | 改 `sliderByKey("animationLength")`，**断言原文一字未动**（与 VDefects/VSnap 那 11 条红同一修法） |
| `VReset2:A-1` | **索引契约破裂** | 启发式「点一下再 apply、digest 不变」返回下标 3 = **多光标不辉光**（绑定控件）；性能诊断其实在 `[7]`（反射 `paintDiagCheck` 证实）。绑定控件的未提交勾选被 `super.reset()` 回灌，是**正确**行为 | 改用组件身份定位（同 `VReset.findPaintDiagCheckbox`）；A-1 断言不变 |
| `VReset2:A-4` / `VCustom:C-19` | 过期基线（装置） | 装置手工只写 6~8 个字段，而 `matchAnimPreset` 现在比 13 个（`CursorPresetApplier:195-222`）→「一切等于内置预设」前提不成立，一 refresh 就派生「基础 副本」 | 改用生产入口 `pickAnimPresetForProbe(base)` 整体对齐；**断言不变** |
| `VTools:M-11` | 过期基线（清单） | 手抄的「预设域」只有 9 个键，实测 `slopeTicks 0→100`、`stretchRatioK 8.0→2.0` → 报「域外变化=2」 | 改为遍历 `SettingsField.PRESET_FIELDS` + `animPresetBase`（表内容由 `VFieldDomain` 独立锁定，不算自证） |
| `VCustom:C-5` / `VSepBound:B-9b` | 过期基线（夹具） | 夹具拿 `tailColor` 当「第二个合法项」，但颜色字段 2026-10-28 起 `inPreset=false` → `restore` 按定义跳过，写入数 2→1 | 换成仍在预设域内的键（`trailSize` / `tailOpacity`）；**断言不变** |
| `VSepBound:B-3a/B-3b/B-11c` | 过期基线（行为已变） | `CustomPresets.format` 改成「跳过非法名条目 + 写 warn 日志」（`CustomPresets.format:163-178`），旧断言要求该条目活下来 | 锁定新契约：只跳过那一条、合法条目逐字保留；B-3b 改为直接验 warn 日志出现 |
| `VSepBound:B-10a/B-10b` | 过期基线（行为已变） | `isValidName` 补了 XML 1.0 码点校验，U+000B 在入口即被拒 → 旧断言「建立成功」的前提已不可能成立 | 锁定「入口拒绝 + 落盘回读后没有孤儿条目」 |
| `VSepBound:B-13a/B-13b` | 过期基线（契约已定） | `uniqueName` 只去重、不净化（javadoc 即如此），非法 base 原样返回；`put` 入口拒绝 | 锁定「uniqueName 不偷偷改名」「入口拒绝后原表一条不多也一条不坏」 |
| `VSleep:S-4` | 过期基线（环境） | 旧判据 `timerMed > 10.0`（假定 15.6 ms 时钟台阶），本机 JDK 21 实测 **6.00 ms** | 双向边界 `4ms ≤ 中位 < 15ms`：既排除「Timer 一次都没触发（-1）」，也排除「被抬到 15.6」 |

**变异测试（生产代码改坏 → 探针必须红；验证完已逐条还原，`git status` 只剩 6 个探针文件）**

| 变异 | 应当红 | 实测 |
|---|---|---|
| 跨行提示后缘写死 `100` | `VHints:H-6` | ✅ 仅 H-6 |
| `syncAllControlsFromPending` 里强行 `paintDiagCheck.setSelected(false)` | `VReset2:A-1` | ✅ 仅 A-1 |
| `applyAnimPresetFields` 里写成 `tailOpacity + 0.01f`（写与 match 判据分家） | `VCustom:C-19` | ✅ C-19（另有 VPreset/VOrtho/VFieldDomain 连带红，都属正确报警） |
| 对「纯平滑输入」多写一个域外字段 `cursorWidthPx` | `VTools:M-11` | ✅ 仅 M-11（域外变化 = 1） |
| 删掉 `format` 的非法名校验分支 | `VSepBound:B-3a/B-3b/B-11c` | ✅ 恰好这三条 |

`VSleep:S-4` 没有生产变异点 —— 它量的是 OS/JVM 的定时器分辨率，不是插件行为，判据靠上面的双向边界自证。

**仍然红、但不在本次范围**：`VRegr2`（13 通过 / 2 失败：R-5 漏看 `typingSmoothness`、R-6 派生缺失）。
它在 `build-v-probes.ps1` 的 `$informational` 里，不计入失败门，本次未动。

### 7.2 用户已决定暂缓（**不要自己动手**）

| 项 | 出处 |
|---|---|
| 设置面板「光标」组布局重排 | §五-B |
| 说明文案整体重写（7 条警告文案有探针字面量依赖） | §五-B |
| 预览窗「加入停顿」开关 | §五-C（用户原话「停顿算了」） |

### 7.3 平台侧 / 无法由本插件解决

| 项 | 状态 |
|---|---|
| **BUG-007 残留 ~414ms 首次编辑停顿** | 已定位为平台首次编辑的惰性初始化（本地化资源包加载 + Live Template 上下文解析）。删掉 861 条 Zen 模板后**仍是 413.770ms**，与本插件无关。详见 BUG-LOG |
| BUG-005 终端 / 控制台不播动画 | 未定位（终端光标不受 `CARET_COLOR` 控制，机制上已知难点） |

### 7.4 版本与文档卫生

| 项 | 现状 |
|---|---|
| `git tag` | 停在 `v0.9.83`；实际已装到 **0.9.102**，中间十几个版本无 tag |
| 探针总数 | **94 个** `V*.java`（新增 `VBoot`） |
| `CLAUDE.md` 状态数字 | 已同步为 0.9.102 / 94 个探针；**提交号一律写「见 `git log -1`」**，不写死 hash |