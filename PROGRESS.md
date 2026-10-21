# PROGRESS —— 当前指针

> 更新：2026-10-21 上午（fork 化完成，0.9.7 已安装）

## 1. 我在哪

| 项 | 值 |
|---|---|
| 已装 | **`0.9.7-pawamamama`**（98,878 B / `DF6E6097`） |
| 安装位置 | `plugins\neovide-cursor-pawamamama\lib\jetbrains-neovide-cursor-0.9.7.jar` |
| 旧插件 | 目录已改名 `jetbrains-neovide-cursor.disabled-20261021-101524`（回退用，未删） |
| 插件身份 | id `com.pawamamama.neovidecursor` / vendor `pawamamama` / version `0.9.7` |
| 探针 | t4 24/24；settings-v2 94/94；plugin.xml 7/7 |

## 2. 后续开发路径（按顺序）

| 版本 | 内容 | 关键约束 |
|---|---|---|
| 0.9.7 验收 | 插件列表看 id/作者/版本；复验三个预设 bug；Tools 3 项 | 用户真机 |
| 0.9.8 | **多光标**（SpringTrail 移植，只弹簧模式） | 不动调度层；per-caret 先于累积物理 |
| 0.9.9 | **行缎带累积物理**（欠阻尼回弹） | 天然 per-caret；需真机调 |
| 0.10.0 | **结构重构 Phase 0–3**（探针解耦 → EditSession → 拆类 → 字段表） | 加参数 8 处 → 2 处 |
| 重构同批 | **国际化**（PluginBundle + 探针改按 key 断言）、**包名改 com.pawamamama**（22 文件 + 探针） | 用户已同意重构时做 |
| 可选 | 流光 RGB、双色辉光、残影插值、隐藏标签页降频、帧率重测 | 都要真机实测 |
| 发布 | GitHub fork 仓库：MIT + NOTICE + README（中/英）+ 与上游差异 | LICENSE 原文不许改 |

## 3. 等用户

| # | 事项 |
|---|---|
| W-1 | 0.9.7 真机验收（插件列表是否显示 pawamamama / 0.9.7） |
| W-2 | D 组「隐藏原生 + 关常驻分离」何时动（冻结中） |
| W-3 | 多光标 / 行缎带 / 结构重构先后（已给建议顺序） |
