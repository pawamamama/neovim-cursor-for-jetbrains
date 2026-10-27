# PROGRESS —— 当前指针

---

## 2026-10-27 自主任务（今晚）

| 项 | 值 |
|---|---|
| 分支 | `claude/d0-gate-probe-status-caadda` |
| D0 门禁 | **exit=0 PASS** |
| 全量探针 | **89 个 / 8 个未通过 / 81 个通过**（基线 88/8，新增 VFontScale 全绿） |
| 已装 jar | **未改动**（R3：本晚不产出、不安装） |

### 已完成

| # | 项 | 状态 |
|---|---|---|
| 1 | **Ctrl+滚轮改字号 → 光标纵向漂移**（用户报告） | ✅ 已修 `8d3e787` |
| 2 | F7 取代证明文档 | ✅ `docs/F7-SUPERSEDED.md` |
| 3 | VDefects 断言改方向 | ⬜ 未开始 |
| 4 | VSnap 诊断（不改断言） | ⬜ 未开始 |

### 任务 1（漂移）的关键发现

**根因是两道快速路径，不止一道。** 只改顶层早退（`!forceResync && cameraTrail.isAt(...)`）
探针仍三红 —— 循环内的 `needsUpdate = forceResync || !trail.isInitialized() || !trail.isAt(...)`
照样把重算拦下。两道都接上 `fontScaleChanged` 才生效。

**另一个坑：不能用 `forceResync` 传这个信号。** 后面的 `forceResync = false;`
会把它一笔勾销，实测完全无效。改用局部变量。

**顺带修了一个真实隐患**：`trailSlotCount(useBlock, blockStarts.length, ...)`
在 `useBlock=false` 时 `blockStarts` 可能是 null（`getBlockSelectionStarts()`
抛异常时停在初值）→ NPE。线上没炸是因为真实 IDEA 承诺返回空数组，但无契约保护。

### 原任务 1（摘 F7 patch）为什么改成写文档

**F7 早就修完并提交在当前分支历史里了**（`ca48301`），而且它修的那个 bug
随 `bc903e1` 的预设模型重构一起消失了 —— 覆盖表机制被整体删除，
`animPresetOverrides` 生产代码零引用。

`VOverride` 探针也一起被删（-121 行），它断言的三个 API 已不存在。
要让「7 条全绿」只能回退 `bc903e1`，远超 R6 且触碰 R1。

详见 [docs/F7-SUPERSEDED.md](docs/F7-SUPERSEDED.md)。

### 基线数字（用于控变量对比）

```
2026-10-26 实测：87 个探针 / 8 个未通过 / 79 个通过
2026-10-27 实测：89 个探针 / 8 个未通过 / 81 个通过（+VFontScale 5 绿）
未通过始终是这 8 个：VDefects(6) VSnap(10) VTools(1) VHints(1) VCustom(1)
                      VReset2(2) VSepBound(7) VSleep(1)
另有 VRegr2 为红（FAIL=2 + NPE 崩溃）但脚本归入 informational，不计入 8
```

---

> ⚠️ **以下内容已过时（停在 2026-10-22 / 0.9.9 时代）。**
> **当前状态看 [docs/PROJECT-GUIDE.md](docs/PROJECT-GUIDE.md)。**
> 保留本文仅为历史留痕，不要把这里的版本号当现状。

> 更新：2026-10-22 夜。**t9 12 段全绿 / 探针 230 条全绿；重构完成 Phase 0/1/3，Phase 2 未做。**
>
> **完整状态看 [ARCHIVE-0.9.9.md](docs/archive/ARCHIVE-0.9.9.md)** —— 那是权威档案。本文只留指针。

## 0. 一句话

自定义预设可用，三层重构完成（字段表 / EditSession / 探针门面），门禁全绿。
**唯一未做的是拆类（Phase 2）**，风险已量化，见 ARCHIVE §7。

## 1. 硬事实

| 项 | 值 |
|---|---|
| 分支 / HEAD | `fix/f7-override-read` / `98399d5` |
| **已装（用户机器）** | **0.9.8**（`41F16B3F` / 102,223 B）—— **全程未动** |
| 新产物 | `dist/jetbrains-neovide-cursor-0.9.9-custompresets.jar` = 118,122 B / `147C66C5` |
| 安装状态 | **未安装** |
| t9 | **12 段全绿**，D0 exit=0，`core/` 零改动 |
| 探针 | **230 条全绿**（11 个探针） |

## 2. 重构进度（RF 判据）

| 判据 | 目标 | 实测 | 状态 |
|---|---|---|---|
| RF-1 断言全绿 | 200+ | t9 全绿 + 230 条 | ✅ |
| RF-2 parity 逐字节 | 一致 | exit=0 | ✅ |
| RF-3 `NeovideConfig` 零 diff | 0 行 | 0 行 | ✅ |
| RF-4 pending/baseline 外部赋值 | 0 | **2（均在 syncSessionViews 内）** | ✅ |
| RF-5 每类 ≤ 600 行 | ≤600 | **2172 行** | ❌ |
| RF-6 加参数改动文件 | ≤2 | 2 | ✅ |
| RF-7 变异仍变红 | 全红 | 6+5 全红 | ✅ |
| RF-8 产出 jar | 有 | 118,122 B | ✅ |

**8 条里 7 条达标，1 条未达（RF-5 拆类）。**

## 3. 下一步（按风险从低到高）

| # | 项 | 风险 |
|---|---|---|
| 1 | 你审阅后安装 `0.9.9-custompresets` | 安装前必须完全关闭 IDEA |
| 2 | 实机验收三个按钮（新建/改名/删除） | 离线探针测不到对话框 |
| 3 | Phase 2 拆类 · 第 1 步：抽「搜索定位」(77 行) | 低 |
| 4 | Phase 2 拆类 · 第 2 步：抽纯静态工具 | 低 |
| 5 | Phase 2 拆类 · 第 3 步：抽「面板构建」(1071 行 / 253 处引用) | **高，单独一轮** |

## 4. 红线仍然有效

- 已装 jar 是 0.9.8，**未安装任何新 jar**
- `core/` 零改动（D0 exit=0）
- 物理 / 渲染参数没碰（parity 逐字节一致）
