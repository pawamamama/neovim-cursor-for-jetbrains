# VS Code 上游调研：为什么手感更好

> 由子代理核查 5 个上游仓库，结论已逐条用探针在本项目验证。
> 触发原因：用户反馈「VS Code 上游手感比我们好，没有辉光但拉伸很有特点」。

---

## 一、上游有四个不同的版本（血缘不同，公式已分家）

| # | 仓库 | 文件 | 行数 | 状态 |
|---|---|---|---|---|
| A | [30d98f9b2/Neovide-Cursor](https://github.com/30d98f9b2/Neovide-Cursor) | `neovide-cursor.js` | 660 | 原作者 Sertie，已停更 |
| B | [LengineerC/vscode-neovide-cursor](https://github.com/LengineerC/vscode-neovide-cursor) | `neovide-cursor.js` | 518 | **本项目声称移植的版本** |
| C | [yyyyolo7a79-sketch/neocursor-fix](https://github.com/yyyyolo7a79-sketch/neocursor-fix) | `extension/assets/neovide-cursor.js` | 698 | v1.2.7，**VS Code / Cursor 上真正在跑的** |
| D | [Tasomei/vscode-neovide-cursor-lite](https://github.com/Tasomei/vscode-neovide-cursor-lite) | `cursor-trail.js` | 1102 | 0.5.1，参数化最彻底 |

### 本项目存在版本注释混淆（已记录）

- `NeovideConfig.java:9` 写「原始定义见 neocursor-fix/extension/assets/neovide-cursor.js」→ 那是 **C**
- `TrailCorner.java:11` 写「移植自 neovide-cursor.js 的 Corner 类（第 103-208 行）」→ 那是 **B**
- 但 B 的第 103-208 行没有 Corner 类（`class Corner` 在 115 行，`jump` 在 149-173 行）

实际抄的是 B 的结构，字段名（`cursorConfig`、`rank0TrailFactor`、`useHardSnap`）来自 A/C。

---

## 二、逐条回应五个观察

### 观察 1「没有辉光」→ 配置默认值 + 渲染模型双重差异

| | 上游 D | 上游 A/B/C | 本项目 |
|---|---|---|---|
| 辉光开关 | `useShadow: false`（默认关） | `true`，但用 Canvas `shadowBlur` | `useShadow: true` |
| 实现 | — | **一次高斯模糊**（能量摊开 → 薄雾） | **8 层同心轮廓逐层 fill** |

我们的 8 层叠加，每层不透明度是解出来的 `A = 1-(1-peak)^(1/N)`，峰值恒定 ——
所以是一圈比 Canvas shadowBlur **更实、更大、更「顶在光标上」**的光晕，且只要在动就一直亮。

**结论**：用户看到「VS Code 没有辉光」最可能就是 D 那版默认关闭。

### 观察 2「更像斜缎带」→ 已定位并修复（rank 塌缩）

见 [RANK-COLLAPSE-FIX.md](RANK-COLLAPSE-FIX.md)。

一句话：`assignRanks()` 用「比它小的有几个」计数，相等时不计数 →
纯轴向移动塌成 2 档 → 同侧上下角同速 → 矩形，斜度 0。

### 观察 3「单行输入拉伸像左半边」→ 缺 `useHardSnap` 机制

上游 C 有一个本项目**完全没有**的机制：

```js
const alignment = jvNorm.x * this.rpNorm.x + jvNorm.y * this.rpNorm.y;
const useSnap = cursorConfig.useHardSnap && alignment > 0.5;
const factor = useSnap ? cursorConfig.leadingSnapFactor : (TRAIL_FACTORS[rank] ?? 1);
const lenAnim = useSnap ? cursorConfig.snapAnimationLength : baseTime * clamp(factor, 0, 1);
//                                       ↑ = 0
```

`snapAnimationLength = 0`，配合弹簧 update 开头的 `if (animationLength <= dt) 归零`，
前缘角点**每帧瞬时贴合**。于是整条形变体全堆在光标**左侧** —— 这就是「左半边」。

我们同位置的时长是 `0.11 × 0.42 = 46ms`，`46 > 8ms`，永远进不了短路。
前缘也跟着拖 → 形状是「从两侧一起收的胖矩形」。

全仓 grep `useHardSnap|leadingSnapFactor|snapAnimationLength` → **0 命中**。

### 观察 4「删除相反」→ 核实成立，精确镜像

上游 C 删除时 rank 变 `2 0 1 3`，时长 `0 / 50 / 45 / 0`：TL、BL 瞬移，
TR 50ms、BR 45ms 拖在后面 → 形变体堆在**右侧**。与打字严格镜像，
源于同一个 `alignment > 0.5` 判据（移动向量反向，点积取反）。

### 观察 5「斜度很长」→ 2px 宽度把上游短跳判据撑爆了

上游四家全部用 `cursorDimensions` 归一化位移：`jv.x = Δx / width`。
2px 光标右移一个字符时 `jv.x = 9.2 / 2 = 4.6`：

| 版本 | 短跳阈值 | 4.6 算短跳吗 |
|---|---|---|
| B | `2.001` | ❌ 不算 → 走跨行三档 `0/50/100` |
| C | `8` | ✅ 算 |
| D | `14` | ✅ 算 |

**B 那版在 2px 光标上每次击键都是「大跳」**，前缘 0ms、后缘 100ms，
22px 高 × 2px 宽的细线被拉成一条长对角线 —— 这是「斜度很长」的正解。

我们用 `charsMoved <= typingMaxChars` 换掉了这个判据（为修中文），
打字被推进 4 档分支。**这个改动修好了中文，但顺带删掉了上游的固有行为。**

---

## 三、本项目仍缺的（按影响排序）

| # | 缺什么 | 影响 | 状态 |
|---|---|---|---|
| 1 | rank 塌缩 | 单行打字无斜缎带 | ✅ **0.9.77 已修** |
| 2 | `useHardSnap` / `snapAnimationLength` | 前缘不贴身，「左半边」观感出不来 | ❌ **实测做不到（见 §3.1）** |
| 3 | 短跳系数最小是 0.22 而非 0 | 前缘永远到不了 0ms | ❌ **同上，依赖第 2 条** |
| 4 | 对齐度用「角点残余位移」而非「移动向量」 | 连打时 rank 可能乱序，形状抽一下 | ⬜ 未做 |
| 5 | 短跳纵向判据放宽到半行（上游是 0.001） | 部分跳转误入短跳分支 | ⬜ 未做 |
| 6 | `reset()` 无条件清位置+速度 | 连打滞后累到 26px（D 版只清速度，稳定 12px） | ⬜ 未做 |
| 7 | 辉光默认值与渲染模型 | 观感比上游「更实」 | ⬜ 设计选择，未必是缺陷 |

### 3.1 第 2、3 条：为什么「左半边」做不出来（0.9.83 实测）

**结论：不是实现难度问题，是几何上不成立。** 上游 2 个角点，我们 4 个。

**根因：**

纯轴向移动时，同侧两个角点的对齐度**完全相等**：

```
打字右移：TL=-0.707  TR=+0.707  BR=+0.707  BL=-0.707
                  ↑同侧相等↑           ↑同侧相等↑
```

上游只判「前缘一个点」的 alignment，吸附它之后另一侧自然拖出去 → 半边。
我们是**逐角**判 alignment，阈值只能一刀切：

- `≤0.707` → 同侧两角**同时**吸附 → 时长差被抹平 → 斜度**反而变小**
- `>0.707` → 一个都不吸附 → 完全不触发

**实测数据**（2×32 光标，单行右移一字符，`tools/bench/ZzSnapScan`）：

```
阈值      吸附角数   拉伸px   斜度px
关闭        0         8.26     4.06
0.3~0.707   2        11.58     2.11   ← 斜度从 4.06 降到 2.11
>0.707     0         8.26     4.06   ← 完全不触发
```

**没有可用的中间档位。** 对齐度是离散的 ±0.707，不是连续值。

**如果将来真要做得先改 rank 体系**：把 `calculateDirectionAlignment` 从
「角点残余位移」改成「移动向量」（第三节第 4 条），让「前缘」只对应 1 个角，
再叠加吸附才有意义。那是动核心物理。

---

## 四、如果要继续对齐上游，建议顺序

**先做第 2、3 条（`useHardSnap` + 系数到 0）**，因为它们是「左半边」观感的直接来源，
且改动局限在 `TrailCorner.jump()` 的短跳分支，风险可控。

第 4 条（对齐度改用移动向量）需要动 `calculateDirectionAlignment` 的签名，
影响面较大，建议单独一轮。

第 6 条（`reset` 策略）会改变连打手感，必须先有实测对比，不能凭猜测改。

---

## 五、调研方法说明

- 搜索关键词：`neovide-cursor`、`vscode neovide cursor`、`neo-cursor-fix`、
  `vscode-neovide-cursor-lite`、`neovide-cursor.js vscode custom css js loader`、
  `cursor-trail.js`
- 找到 5 个相关仓库（表中 4 个 + E `Jenlybein/vision-smash-code`，E 无独立机制）
- 上游源码落在 `%TEMP%`，**未修改本项目任何文件**（当时）
- 后续的 rank 修复与探针是本项目自己的改动

### 本报告的可信度边界

上游代码是**读出来的**，不是猜的；但「为什么手感更好」中的**观感判断**
（例如「更实的光晕」「左半边」）来自用户描述 + 代码机制推断，
**没有做像素级对照实验**。如果要严格证明，需要同时录屏两边做逐帧比对。