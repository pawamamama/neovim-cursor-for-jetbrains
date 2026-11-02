# v0.9.126 — 辉光淡出：跟着拖尾一起暗

> 2026-11-02 · tag `v0.9.126` → `1dcaa7a` · IDEA 2026.1 与 PyCharm 2025.2 均已安装
>
> 完整版本说明：[VERSION-0.9.126.md](https://github.com/pawamamama/neovim-cursor-for-jetbrains/blob/main/docs/VERSION-0.9.126.md) ·
> 决策层报告：[GLOW-FADE-0.9.126-REPORT-2026-11-02.md](https://github.com/pawamamama/neovim-cursor-for-jetbrains/blob/main/docs/GLOW-FADE-0.9.126-REPORT-2026-11-02.md)

## 一句话

辉光不再是「光标停下后才熄灭」，而是**跟着拖尾收拢一起变暗**；
「保持时长 / 淡出时长」两个参数重新可感知；多光标拆组后静止光标不再单独发光。

## 亮点

- **辉光淡出（本次核心）**：亮度目标 = 拖尾自身强度曲线；每帧最多下降
  `dt / 淡出时长`。参数决定「降多慢」，不再决定「何时开始」；跨行缎带不中途灭，
  不按层数跳变。
- **多光标拆组不孤光**：每个 trail 保存自己的亮度，组内只共享「显示最大值」；
  移动光标跳走 / 被移除后，留下的静止光标不会以组满亮为起点单独淡出。
- **设置面板 7 个标签页**：总开关 / 颜色 / 光标行手感 / 列手感 / 辉光 / 特殊 / 调试；
  14 滑块 + 10 复选框 + 4 下拉，全部实时生效并持久化。
- **预设**：3 个动画 + 6 个颜色内置预设；最多 50 个自定义；父链快照；
  13 个预设域字段统一（不再出现「切预设把颜色改回粉色」）。
- **国际化**：165 个键，英文 root + 简体中文覆盖。
- **平台兼容**：平台 252 read action 修复（PyCharm 2025.2 不再冻结）；
  IDEA 2026.1 与 PyCharm 2025.2 均已实测。

## 与 0.9.83 相比

`v0.9.83` 是最后一个 tag 基线。此后 **106 个提交 / 239 个文件 /
+42,323 −3,464 行**（含文档，2026-11-02 实测）。按主题：

| 领域 | 变化 |
|---|---|
| 设置 / 预设 | 7 标签页；P11 重排；i18n 165 键；父链预设；预设域 26→13 |
| 颜色 | 彩虹 / 双拼 / 纯色三条互斥路径；彩虹辉光雾色改中性白；颜色移出动画预设 |
| 拖尾 / 输入 | 默认斜度 0；字号 / block inlay 漂移修复；按字符判断短跳；leadingSnap 回退为只留开关 |
| 辉光 / 淡出 | 0.9.118→0.9.126 连续迭代：修跨行中途灭、停下才暗、闪灯、参数无效、拆组孤光 |
| 多光标 / 平台 | 平台 252 read action；多光标静止光标发光；PyCharm 同步 |
| 构建 / 探针 / 文档 | 门禁脚本修复；113 个注册探针；VDocCheck；安装脚本结构指纹；文档归档与索引 |

完整逐版本对照见 [VERSION-0.9.126.md §四](https://github.com/pawamamama/neovim-cursor-for-jetbrains/blob/main/docs/VERSION-0.9.126.md)。

## 为什么手感更好

- **拉伸，不是残影**：四角独立阻尼弹簧 + 方向排名；前缘跟得紧、后缘拖得慢，
  光标被拉成不规则四边形，而不是叠残影。
- **按字符判断短跳**：原生 offset 给出精确字符数，中文单字 / emoji / 换字号不误判。
- **三个连续参数**：拉伸强度 / 输入平滑 / 斜度，替代上游写死的四档系数。
- **辉光跟着拖尾暗**：目标曲线 + 限速下降，参数真实影响尾巴长度；跨行不中断。
- **多光标隔离**：静止光标不被邻居点亮，拆组不孤光。

## 验证

| 项 | 结果 |
|---|---|
| 全量门禁 | **113 个注册探针 ALL PASS** |
| 辉光专项 | `VFadeTick` 45/45；`VFadePreview` 8/8 |
| 结构 / 性能审计 | `VAuditBatch2Round5` 32/32 |
| 文档一致性 | `VDocCheck` 4/4（版本三处一致、探针数=116） |
| D0 红线 | `core/NeovideConfig.java` 零 diff |
| 变异测试 | 6 组均判红（忽略淡出时长 / 去掉限速 / 保持期失效 / 组亮度写回 / 两步写回 / 每帧分配） |
| 安装 | IDEA 2026.1 + PyCharm 2025.2，jar 内 0.9.126；md5 `4cbbd854719879bfd522b63df8e80897` |
| 个人配置 | 12/12 字段还原，丢弃 0 |
| 实机验收 | 用户确认通过 |

## 安装 / 升级 / 回滚

```powershell
# 先看清单，不动文件
pwsh -NoProfile -ExecutionPolicy Bypass -File build/install.ps1 -Version 0.9.126 -DryRun

# 安装（不自动启动 IDE）
pwsh -NoProfile -ExecutionPolicy Bypass -File build/install.ps1 -Version 0.9.126 -NoStart
```

- `install.ps1` 只面向 IDEA 2026.1；PyCharm 需手动替换
  `plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar`（文件名恒为 0.9.8.jar）。
- 升级会先备份个人配置，装完全量还原；结构指纹应为 28。
- 回滚：`dist/neovide-cursor-0.9.125.jar` + `build/fade-pre-0.9.126.patch`；
  不要只改 `install.ps1 -Version`（会用当前源码重新打包）。

## 已知问题

- **连续 10 键/秒**：亮度约在 **0.32 ~ 1.0** 起伏（当前配置 25ms/50ms）；
  把保持时长调到 **100ms** 可零代码规避，或增大淡出时长。
- `VRegr2` R-5/R-6（`typingSmoothness`）仍是 informational 缺口，不在失败门内。
- `glowFade` 已是恒 1 的死乘子，计划下一迭代删除。
- 0.9.84~0.9.125 未补打 tag；GitHub Release 从本版开始恢复。
- Marketplace 上架状态、2024.2 平台真机验证：仓库内无记录，标「待核实」。

## 贡献者

- **pawamamama**（[@pawamamama](https://github.com/pawamamama)）—— 本 fork 维护者与提交作者。
  自 v0.9.83 以来 **106 个提交 / 239 个文件 / +42,323 −3,464 行**（代码 + 测试 + 文档）；
  当前 **生产 37 文件 / 21,342 行**，**探针 131 文件 / 33,487 行**（116 个 `V*.java`，113 个注册）。
- **yyyyolo7a79-sketch** —— 原项目与核心拖尾物理。
- **LengineerC/vscode-neovide-cursor**、**Neovide** —— 参数语义与拖尾效果来源。

---

完整版本说明：[docs/VERSION-0.9.126.md](https://github.com/pawamamama/neovim-cursor-for-jetbrains/blob/main/docs/VERSION-0.9.126.md)
