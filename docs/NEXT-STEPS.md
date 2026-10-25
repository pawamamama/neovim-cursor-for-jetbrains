# 下一步做什么

> 状态：**v0.9.83 已发布**
> https://github.com/pawamamama/neovim-cursor-for-jetbrains/releases/tag/v0.9.83
> 本文最后更新：2026-10-25 晚

---

## 一、当前硬状态

| 项 | 值 |
|---|---|
| 版本 | v0.9.83 |
| HEAD | `9df1356` |
| 探针清单 | **80 个**（0.9.83 从 49 补回） |
| 探针通过 | **71 / 80** |
| D0 门禁 | PASS（`core/NeovideConfig` 零 diff） |
| PhysicsParity | PASS |
| 生产代码 | 13949 行 / 34 文件 |
| 探针代码 | ~21725 行 / 84 文件 |

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

## 四、剩余 9 个探针失败（都是过期基线，非新 bug）

| 探针 | 失败数 | 原因 |
|---|---|---|
| `VSnap` | 10 | `VUi` 量程表与面板渲染顺序仍未完全同步 |
| `VSepBound` | 7 | 分隔线逻辑已改 |
| `VDefects` | 6 | 同 `VSnap` |
| `VRegr2` | 2 | 平滑已移出预设域 |
| `VReset2` | 2 | 重置逻辑已改 |
| `VTools`/`VHints`/`VCustom`/`VSleep` | 各 1 | 字段数 / 文案 / 副本判据 |

**预计工作量**：半天。不影响用户功能，但能降低噪声。

---

## 五、可以考虑做的新功能

| # | 功能 | 依据 | 工作量 |
|---|---|---|---|
| 1 | 帧率 144 档的**端到端验证** | `targetFps` 已接通，实际 fps 需诊断日志确认 | 小 |
| 2 | 块选区 `charsMoved` 语义拆参数 | 现在靠传 -1 绕开，判据依赖光标宽度 | 中 |
| 3 | `[trail]` 诊断日志降频 | 8 光标时 328KB / 2 分钟 | 小 |

---

## 六、文档地图

| 文档 | 内容 |
|---|---|
| [WORK-DISCIPLINE.md](WORK-DISCIPLINE.md) | **工作纪律（必读）** 444 行，含 8 类踩坑 |
| [design/RANK-COLLAPSE-FIX.md](design/RANK-COLLAPSE-FIX.md) | 为什么单行打字画不出斜缎带 |
| [design/UPSTREAM-VSCODE-RESEARCH.md](design/UPSTREAM-VSCODE-RESEARCH.md) | 上游四版本对比 + 我们还缺什么 |
| [design/RGB-WASH-REFERENCE.md](design/RGB-WASH-REFERENCE.md) | 上游 wash 实现参数 |
| [PERFORMANCE-FPS.md](PERFORMANCE-FPS.md) | 帧率实测 + 平台限制 |
| [IDEA-PLATFORM-OPTIMIZATION.md](IDEA-PLATFORM-OPTIMIZATION.md) | 平台规范 + 240Hz 核查 |
| [RELEASE-SAFETY.md](RELEASE-SAFETY.md) | 升级不掉用户配置的流程 |
| [DEAD-PROBE-CLEANUP.md](DEAD-PROBE-CLEANUP.md) | 探针覆盖度 49→80 的过程 |
| [VIDEO-SCRIPT.md](VIDEO-SCRIPT.md) | 演示视频脚本（未拍） |
| [BUG-LOG.md](BUG-LOG.md) | 历史 bug 记录 |