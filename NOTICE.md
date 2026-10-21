# NOTICE —— 本项目是 MIT fork

本仓库是 **[yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains)** 的独立 fork，
由 **pawamamama** 维护。

## 上游

- 上游项目：`neovim-cursor-for-jetbrains`
- 上游版权：`Copyright (c) 2026 yyyyolo7a79-sketch`
- 协议：**MIT**（原文见本仓库 `LICENSE`，**未修改**）
- fork 基线：上游 `6bed09d`（v0.5.0，2026-10-16）

## 本 fork 的改动（相对上游，量级：大改）

| 领域 | 内容 |
|---|---|
| 设置界面 | 从零新增（1,600+ 行）：19 个参数、滑块+输入框双向绑定、吸附、折叠分组、7 动画预设 / 5 颜色预设 |
| 持久化 | 新增 application 级 `PersistentStateComponent`（roamingType = DISABLED） |
| 预设语义 | 「X（已修改）」就地改名 + 基准下标持久化 + 一键恢复 |
| 物理/手感 | 短跳梯度可调（输入平滑）、七预设短跳统一 0.11s、辉光锐度反向映射 |
| 菜单 | Tools 只留 3 项；性能诊断移入面板「高级（诊断）」 |
| 调度 | 单线程 + 每 animator 独立 invokeLater（禁止批量 tick） |
| 缺陷修复 | 隐藏原生光标污染配色、scheme 恢复、Tools/面板不同步、二次 apply 冲掉、IME 边界等 |
| 验证资产 | 200+ 条离线断言（parity / audit / IME / 探针 / 变异测试） |

## MIT 合规说明

MIT 允许 fork、修改、改名、闭源再分发，**唯一硬性要求是保留原始版权声明与许可全文**。
因此：

1. `LICENSE` 文件**原样保留**，未改动一个字；
2. 本 `NOTICE.md` 显式声明上游来源与本 fork 的改动；
3. 插件描述里链接上游仓库；
4. 插件 id 改为 `com.pawamamama.neovidecursor`，与上游可并存、不互相覆盖。

> 注意：Java 包名仍保留 `com.github.yyyolo7a79.neovidecursor`（内部实现细节，改名会牵动 22 个源文件 + 全部探针）。
> 这不影响 MIT 合规。若要彻底改名，建议放在结构重构那一版一起做。
