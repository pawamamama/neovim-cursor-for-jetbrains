# 工作流（本项目版）

> 这是「按什么顺序做」，不是「为什么」。判据与血泪教训在 `docs/WORK-DISCIPLINE.md`；
> 命令细节在 `CLAUDE.md` §三；现状只看 `docs/CURRENT-STATE-2026-11-04.md`。
>
> 2026-11-04 建立。**改本文件时同步 `CLAUDE.md` 顶部的「五步工作流」一节**，
> 否则两处说法会分家（这个项目已经因为「同一件事写在两处」翻过车）。

---

## 开工前 30 秒（每次）

1. `git status --porcelain` —— 工作区若有别人的改动，先问，别顺手提交。
2. `git log -1 --oneline`，再跑 `git rev-list --left-right --count fork/main...HEAD` 看有没有没推的提交。
3. 读 `docs/CURRENT-STATE-2026-11-04.md`（版本 / trailSize / A-E 状态 / 下一步）。
4. 判断任务类型，按文末「按任务类型裁剪」决定走几步。

---

## 第 1 步：复现 + 证据（bug 必修）

**目标：把「用户说」变成「我能指着的东西」。**

禁止凭描述直接改代码。至少拿到下面两样：

- **日志行**：`%LOCALAPPDATA%\JetBrains\<产品><版本>\log\idea.log`，
  grep `neovide-cursor|refreshTarget|WARN`。
- **配置现值**：项目根 `verify-state.xml`，或
  `%APPDATA%\JetBrains\<产品><版本>\options\neovide-cursor.xml`。
- **已装版本**：读 jar 内 `META-INF/plugin.xml` 的 `<version>` —— **不要看文件名**
  （磁盘上恒为 `jetbrains-neovide-cursor-0.9.8.jar`，是加载机制要求）。
- **探针复现**：能写成断言的，先写断言让它红（RED），再改生产代码。

产出要写进任务里，不要只在聊天里说：

- 一句话症状 + 触发条件（哪个 IDE / 哪个平台版本 / 什么操作）
- 证据：文件路径 + 行号，或命令 + 输出
- 影响面：只影响观感，还是会卡死 / 冻结编辑器

**判据：说不清「怎么复现」，不许进第 2 步。**
用户描述与代码不一致时，先问，不许猜（纪律四-I）。

---

## 第 2 步：定位 + 方案

1. 从速查表找入口：`CLAUDE.md` §十（弹簧 / rank / 绘制 / 帧调度 / 字段表 / 预设 / 面板 / 预览）。
2. `grep` 出全部读它的地方 —— 一个字段两个职责就是定时炸弹（纪律四-D）。
3. 字段类改动按六层过一遍：
   声明 → `SettingsField.FIELDS` → `copyFrom` → `loadState` → 消费者 → UI 控件。
   加字段直接照 `CLAUDE.md` §7.0 落点清单（A 生产 / B 探针常数 / C 控件定位）。
4. 写下：改哪些文件、改哪一层、探针怎么验、会不会动预设域。
5. 产品决策（观感变不变、要哪个效果）问用户；实现细节自己定（纪律四-L）。

**判据：方案里每个改动都能回答「探针怎么证明它对」。**

---

## 第 3 步：实现 + 守门探针

顺序固定：**先探针 / 先断言，后生产代码。**

- 探针必须调用生产方法或断言生产行为，**不许复刻算法**（纪律四-G）。
- 新行为必须有守门探针；改旧行为必须写清旧断言为什么失效（**改方向不删断言**）。
- 变异测试三步：故意改坏生产代码 → 对应断言必须红 → 恢复 → 全绿。
  变异后必须**删 `build\v2-probe\classes` 全量重编译**，否则 javac 增量会复用旧字节码。
- 同一语义出现在两处 → 先统一再改；名字与行为不符 → 改名，不只加注释（纪律四-J）。

---

## 第 4 步：全量门禁 + D0

```powershell
# 唯一权威测试入口；看最后一行 RESULT，不要看中间某个探针的 PASS
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1 -Repo D:\deepseekwork\neovim-cursor-pr

# D0：核心配置必须零 diff
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
```

- **不要并发跑第二次**：两份门禁共用 `build\v2-probe\classes`，编译会先删目录，
  另一份正在加载探针主类时会 ClassNotFoundException，失败数暴涨 —— 这是假红。
- 新增红先分类：
  - 落在 `CLAUDE.md` §7.0-B 那张「写死总数」的表里 → 改探针常数（**契约变更，非缺陷**）
  - 不在这张表里 → 真回归，回第 2 步
- 文档改动也要跑：`VDocCheck` 会查版本三处一致、探针数、有没有写死 commit hash。

---

## 第 5 步：提交 + 交付

提交信息三段（缺一段就会被要求重写）：

```
<type>: <一句话>

- 改了什么（文件 + 行为）
- 为什么（根因 / 决策依据）
- 验证方式（门禁结果 / 变异测试 / D0 / 实机）

Co-Authored-By: Claude Code <noreply@anthropic.com>
```

- 只 stage 自己改的文件；`git status --porcelain` 逐个确认（陷阱 10）。
- 需要安装时：先 `install.ps1 -Version <ver> -DryRun` 看备份/丢弃清单，再 `-NoStart`。
- 装完从 jar 内读 `<version>` 确认，不要用文件名。
- 文档只改「该改的那一份」：现状改 `CURRENT-STATE`，待办改 `NEXT-STEPS`，
  bug 记录改 `BUG-LOG` —— 同一个数字不要抄进三份。
- `push` 走 SSH：`git push fork main`（https 经本机代理会 TLS 中断）。

---

## 按任务类型裁剪

| 任务 | 走哪几步 | 额外要求 |
|---|---|---|
| bug 修复 | 1→2→3→4→5 | 第 1 步必须有证据；修完加守门探针 |
| 新设置项 / 新参数 | 1→2→3→4→5 | 照 §7.0 落点清单；六层全接 |
| 只改探针 | 1→3→4→5 | 改断言方向要写理由 + 变异测试 |
| 只改文档 | 1→2→5 | 数字跑命令；链接查断链；全量门禁可跳过（VDocCheck 除外） |
| 打包发版 | 2→4→5 | 版本号从 jar 读；先 `-DryRun` |

---

## 三条最容易走错的

1. **把「探针绿」当成「功能对」** —— 探针只证明它断言的那一件事。
2. **为了过门禁去改断言** —— 先判断是契约变更还是真回归，再决定改哪边。
3. **拿文档当现状** —— `docs/archive/` 下一律是历史；任何数字先重跑命令。
