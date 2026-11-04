# leadingSnap 现状问题评估与路线 C 方案（2026-11-01）

> **本文件只做评估与方案，不改代码。** 由决策会话据此生成实现提示词。
> 当前代码状态：`c2ae1e9`（已安装 0.9.116）；工作区干净（只有未跟踪的
> `build/night-run-report.md`，与本功能无关）。

---

## 一、一句话结论

**当前「本体走弹簧 + 绘制层固定尾巴」的模型是错的，原因有两个，都不是调参能解决的：**

1. 本体用 110ms 弹簧 → 连续输入时永远追不上光标（隐藏原生光标后，本体就是唯一光标，必须即时跟手）；
2. 尾巴画成了「与本体同宽、平移 6px、45% 透明」的矩形 → 视觉上是两个光标（一实一淡），不是一条连着的尾巴。

**建议路线 C**：删掉这套绘制模型，回到现有弹簧体系 ——
**前缘（rank 2/3）时长 0ms 即时贴住，后缘（rank 0/1）一个很短的固定时长**，
让四边形本身就是「本体 + 连着的短尾」。不加新参数、不做第二套绘制。

---

## 二、用户实际报告

> 「bug 是完全没有拖尾巴，只有一个残影，是淡一点的光标」
> 「连续时候跟不上」
> 「你引入了别的参数这个代价还有」
> 「现在纯平滑输入能还原 80 的观感」
> 「现在参数都有点多」

---

## 三、根因（两条，对应代码）

### 3.1 本体走弹簧 → 跟不上

`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/SpringTrail.java:368`

```java
if (leadingSnap) {
    for (int k = 0; k < n; k++) {
        corners[order[k]].setAnimationLengthForTrail(config.shortAnimationLength);
    }
}
```

四角统一 `shortAnimationLength`（默认 0.11s）。按键间隔约 100ms，
所以本体永远落后一次移动。隐藏原生光标后，本体是用户看到的**唯一光标**，
落后就是「跟不上」。

### 3.2 尾巴是分离的淡矩形 → 看起来像残影

`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretTrailPanel.java:972`（`fillHalfTrail`）

- 尾巴 = 本体形状整体平移 `宽度 × 3`（2px × 3 = 6px），45% 透明度；
- 宽 2px、和本体之间没有过渡形状 → 渲染出来是「两个 2×32 的竖条」，
  前面实、后面淡。用户描述「只有一个残影、是淡一点的光标」与此完全吻合。

### 3.3 为什么这不是参数问题

- 尾巴透明度、长度都是常量；调它们只会让「残影」更明显或更淡，不会连成尾巴。
- 本体时长是 `shortAnimationLength`；调短会让本体更跟手，但仍然是「滞后—追上」的弹簧，
  不是即时，而且会连带影响所有模式。

---

## 四、为什么不是 slope / smooth 造成的（已有量化结论）

2026-11-01 探查（生产代码实测，详见 `docs/archive/reports/LEADING-SNAP-REPORT-2026-10-31.md` §十一）：

| 结论 | 数据 |
|---|---|
| 单次移动最大横向跨度 | **11.87px < 12px（字符宽）**，任何 slope/smooth 组合都不会覆盖到相邻字符格 |
| 连续删除 100ms/键、默认配置 | 8.3–8.6px |
| 连续删除 100ms/键、slope=100 | 11.1–11.6px（仍 < 12） |
| 真正会超的组合 | `k=8 + slope=100`（12.9px）、`shortAnimationLength ≥ 0.15s`（最高 18.8px） |
| `slopeTicks` 的角色 | 放大器：0→100 加 2.4px |
| `typingSmoothness` 的角色 | 缩小器：0→1 减 5px，但把覆盖时间 117ms→132ms |

**结论：slope/smooth 不是「吞字符」的根因；单次移动本身也不可能吞到更前一格。**
真正的问题是「拉伸矩形持续压在刚走过的那一格上」的**时间**与**形状**，
而当前模型把这个问题放大成了「残影」。

---

## 五、路线 C 设计（建议实现）

### 5.1 行为

只在**单行短跳分支**生效（`TrailCorner.jump` 的 `isShortJump == true` 分支）：

| 角 | 时长 | 效果 |
|---|---|---|
| rank 2 / 3（前缘） | **0 ms** | `DampedSpring.update` 首句 `animationLength <= dt` → 当帧精确到位 |
| rank 1 | 尾巴时长的 2/3 | 过渡档，避免硬折角 |
| rank 0 | **尾巴时长（建议 0.02–0.03s）** | 拖出一条连着的短尾 |

- 前缘 0ms 让**本体即时跟手**（连续输入不落后）；
- 后缘 20–30ms 让尾巴短且快速收掉。首帧跨度估算：
  `L=20ms, dt=8ms` → 剩余约 52% → 约 6px；`L=30ms` → 约 8.5px。
  建议从 **0.02s** 起（≈6px，约半个字符），不满意再微调这一个数。
- **跨行分支（换行/大跳）不动**：仍走原来的 `animationLength × (1 - trailSize)`，
  保住 Enter 时的缎带。这是与当前实现最大的区别之一。

### 5.2 代码落点（供提示词引用）

| 文件 | 改动 |
|---|---|
| `core/TrailCorner.java` | 短跳分支里按传入的「前缘/后缘时长」覆盖；**删除** `HALF_TRAIL_CAP_WIDTHS` 与绘制耦合（`setAnimationLengthForTrail` 可保留供覆盖用） |
| `ui/SpringTrail.java:368` | 覆盖逻辑改为「前缘 0 / 后缘固定短时长」，且**只在短跳时**覆盖；跨行不覆盖 |
| `ui/CaretTrailPanel.java` | **删除** `halfTrail` 字段、`setHalfTrail`、`quadDirs`、`fillHalfTrail`、`addQuad` 的方向重载；恢复单一绘制路径 |
| `ui/CaretAnimator.java` | 删除 `panel.setHalfTrail(...)` 与 `addQuad(..., dir)` 的方向参数（回到原调用） |
| `ui/settings/AnimationPreview.java` | 删除 `setHalfTrail`、`dirX/dirY/pushSeen`、方向参数；预览只按四角时长自然呈现 |
| `settings/NeovideCursorConfigurable.java` | 开关保留；打开时把 `slopeTicks / stretchRatioK / typingSmoothness` 置灰（见 §六） |

### 5.3 尾巴时长怎么给

两种写法，二选一：

- **常量**：`0.02s`（推荐先试）——与 `shortAnimationLength` 解耦，不受用户其它参数影响；
- **比例**：`shortAnimationLength × 0.2`（默认 0.11 → 22ms），随「短动画时长」一起缩放。

不建议用 `animationLength`（那是跨行时长，默认 0.1s，会让尾巴长到约 12px）。

---

## 六、与现有体系的冲突 / 置灰清单

路线 C 下，开关打开时**只有「尾巴时长」有意义**，以下参数不再影响形状：

| 参数 | 开关打开时 | 处理 |
|---|---|---|
| `slopeTicks`（斜度） | 无效（前缘 0、后缘不分上下角） | **置灰** |
| `stretchRatioK`（拉伸强度） | 无效（不走 `trailFactors`） | **置灰** |
| `typingSmoothness`（输入平滑） | 无效 | **置灰** |
| `shortAnimationLength`（短动画时长） | 若尾巴用常量则无效 | 置灰（或让尾巴按比例，则保留） |
| `animationLength` / `trailSize`（跨行） | **仍有效**（跨行分支不动） | 保留 |
| 残影模式 | 已有置灰规则 | 保持 |

预览窗与真机共用同一套四角时长 → 天然同源，不需要额外推送逻辑。

---

## 七、参数审计（回应「参数有点多」）

与拖尾形状相关、且在半边模式下会被绕开的参数有 4 个：
`slopeTicks`、`stretchRatioK`、`typingSmoothness`、`shortAnimationLength`。

三条可选收口（供决策会话取舍）：

1. **最小改动**：保留全部参数，开关打开时置灰上面 4 个（本节推荐，风险最低）；
2. **删开关回家预设**（路线 A）：把「纯平滑输入」预设微调到接近上游，参数不增；
3. **合并参数**：把 `stretchRatioK` 与 `slopeTicks` 合并成一个「斜缎带强度」（改动大，
   涉及预设三份清单与 `matchAnimPreset`，不建议与本轮一起做）。

---

## 八、验收标准（实现完成后逐条验）

1. 开关打开、单行输入：本体**立即**到达新光标位置（不再落后）；
2. 尾巴是**连着**的一段（不是两个分离的竖条），长度 ≤ 约 6px；
3. 连续输入/删除 100ms/键：尾巴长度恒定，不累积、不抖；
4. 停止输入：尾巴在 ~1 帧内消失，只剩 2px 本体；
5. 换行（Enter）：跨行缎带保持原样（开关不影响）；
6. 关闭开关：所有行为与 `c2ae1e9` 之前完全一致（默认 false 路径逐位不变）；
7. 设置页预览与真机形状一致；
8. 全量门禁 ALL PASS（109 探针 / 1253 断言，仅 VRegr2 两条既有 informational）。

---

## 九、本次没有做的事（明确记录）

- **没有改任何代码**；工作区 `git diff` 为空；
- 已安装版本仍是 `0.9.116`（等于 `c2ae1e9`）；
- 上面所有结论都来自代码阅读 + 2026-11-01 的实测数据，不是猜测。

---

## 十、给决策会话的提示词要点

1. 先读本文件 + `CLAUDE.md` §7.0 / §7.1 + `docs/design/UPSTREAM-VSCODE-RESEARCH.md` §3.1；
2. 目标是**删掉第二套绘制**（`halfTrail`），把「半边」表达为现有弹簧体系的两个时长：
   前缘 0ms、后缘 0.02s（单行短跳分支 only，跨行不动）；
3. 开关保留为设备级；打开时置灰 `slopeTicks / stretchRatioK / typingSmoothness`
   （尾巴若用常量，`shortAnimationLength` 也置灰）；
4. 探针 `VLeadingSnap` 改成断言：前缘角时长 == 0、后缘角时长 == 尾巴常量、
   跨行分支四角时长不变；变异测试删掉覆盖逻辑必须变红；
5. 不改 `core/NeovideConfig.java`（D0）、`CursorPresets.java`、`CursorPresetApplier.java`；
6. 完成后按 §八 的 8 条验收，并实机确认「连打不落后、尾巴连成一段」。

---

## 十一、落地结果（2026-11-01 实现后补记）

> 引用位置：`CLAUDE.md` §7.1 与 `plans/leading-snap-v2.md` 都回链到本节。
> 本节只追加实现结果，不改上面九节的评估结论。

**结论：路线 C 已落地，第二套绘制已删除。**

| 项 | 结果 |
|---|---|
| 新时长 | rank 2/3 = 0ms、rank 1 = 20ms×2/3、rank 0 = 20ms；只在 `lastJumpWasShort()` 为真时覆盖 |
| 跨行 | 完全不动；探针 L-17 断言开/关逐位相同（`[0.2,0.15,0.1,0.1]`） |
| 删除 | `CaretTrailPanel.halfTrail/quadDirs/setHalfTrail/fillHalfTrail/5 参 addQuad`、`CaretAnimator` 方向实参、`AnimationPreview` 方向字段与 `lastRanks`、`SpringTrail.dirX/dirY/config`、`TrailCorner.HALF_TRAIL_CAP_WIDTHS` |
| 置灰 | `slopeTicks / stretchRatioK / typingSmoothness / shortAnimationLength`，用 `GraphProperty + enabledIf`（与残影模式同一套）；跨行两项不置灰 |
| 探针 | `VLeadingSnap` 22 → 33 条，全绿；门禁 109 探针 / 1264 断言 ALL PASS（VRegr2 两条 informational 不变） |
| 变异 | 删生产覆盖块 → 10 红（含 L-19 11.58px / L-20 累积到 13.00px）；删预览覆盖块 → L-14/L-16 红；去短跳守卫 → L-17 红；删置灰刷新 → G-2 红；置灰公式去掉 springMode → G-5 红；删模式下拉的置灰刷新 → G-5 红 |
| 实测 | 开启时右边缘 0、左边缘 2.5979；四角时长 `[0.02, 0, 0, 0.01333]`；尾巴首帧 6.2992px；连续 10 键 min=max=6.2992px；出厂默认前缘首帧 0、后缘 10.0px |

**与 §五 的差异**：§5.2 写「改动落在 TrailCorner.jump 的短跳分支」，实际落在
`SpringTrail.assignRanks()` / `AnimationPreview.pushTarget()` 的 jump **之后** ——
原因见 `plans/leading-snap-v2.md` §2.3（开关归属 / 职责分层 / 跨行隔离 / 可测性）。
`TrailCorner` 只提供纯函数 `halfTrailDurationForRank()` 与判据访问器
`lastJumpWasShort()`，保证生产与预览两侧同源。

**上一版报告的时效**：`docs/archive/reports/LEADING-SNAP-REPORT-2026-10-31.md` 的「固定尾巴」
模型（四角同长 + 绘制层尾巴）**已被本实现废弃**；其 §十一 的量化数据仍然有效
（slope/smooth 不是吞字符根因）。
