# 项目导览

> ⚠️ **本文停在 2026-10-24（0.9.23 时代），状态数字已大面积过时。**
> **当前状态请看 [`START-HERE.md`](../START-HERE.md)。**
> 本文仍有价值的部分：目录结构、构建命令、硬约束清单（这些变化慢）。
> 已过时的部分：所有版本号、文件数量、jar 清单。
>
> ~~本文是唯一权威的「工作区地图」~~（已不是 —— `START-HERE.md` 才是状态入口）
> 事实核对时间：**2026-10-24 14:10** ｜ git HEAD：`4a2042f`（现已 `4bbbcfc`+）

---

## 一、先看这三条（最容易搞错的）

### 1. 已装版本 ≠ 文件名 ≠ 已发布版本

```
文件名恒为：jetbrains-neovide-cursor-0.9.8.jar   ← 加载机制依赖它，不代表版本
真实版本：  解包读 META-INF/plugin.xml 的 <version>

当前状态：
  用户机器已装   0.9.22
  本地最新       0.9.23（待装，IDEA 在跑）
  GitHub 已发布  v0.9.14   ← 落后 9 个版本
```

**核对命令（复制即用）：**

```powershell
$inst = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\neovide-cursor-pawamamama\lib\jetbrains-neovide-cursor-0.9.8.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($inst)
$z.Entries | Where-Object { $_.FullName -match 'plugin\.xml$' } | ForEach-Object {
    $sr = New-Object System.IO.StreamReader($_.Open()); $t = $sr.ReadToEnd(); $sr.Close()
    ($t -split "`n" | Select-String '<version>').Line.Trim()
}
$z.Dispose()
```

### 2. 安装必须【先清空 lib 目录】

**这是 10-24 踩过的坑**：`lib/` 里堆了 12 个 jar（每版都把旧的改名成 `prev-*.jar` 留在原地），
IDEA 会把**所有** jar 当插件扫描 —— 结果加载的是 0.9.14 而不是 0.9.17。
**我因此误判「已安装」，用户测的其实是旧版本。**

```powershell
$lib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\neovide-cursor-pawamamama\lib'
$backup = 'D:\deepseekwork\_jar-backup-20261024'
New-Item -ItemType Directory -Force -Path $backup | Out-Null
Get-ChildItem $lib -Filter '*.jar' | ForEach-Object {
    Move-Item $_.FullName (Join-Path $backup ($_.BaseName + '-' + (Get-Date -Format 'HHmmss') + '.jar')) -Force
}
Copy-Item 'D:\deepseekwork\neovim-cursor-pr\dist\neovide-cursor-0.9.23.jar' `
          (Join-Path $lib 'jetbrains-neovide-cursor-0.9.8.jar') -Force
```

**装完必须验证：`lib` 里只剩 1 个 jar。**

### 3. 文档会过时，代码不会

我（AI）多次把 md 写成「已核对」但实际是旧的。
**任何版本号、提交号、文件数，都以命令输出为准，不要信 md。**

---

## 二、工作区结构

```
D:\deepseekwork\neovim-cursor-pr\
├── src/main/java/.../          30 个 .java（生产代码）
│   ├── core/                   5 个：物理与配置（被 D0 冻结）
│   ├── settings/               4 个：持久化层
│   └── ui/                     20 个：编辑器集成 + 设置面板
├── tools/
│   ├── settings-probe/         55 个探针 + 门禁脚本
│   ├── parity/                 上游一致性校验
│   ├── ime-probe/              IME 几何判据（已知 6 项破损）
│   └── check-sessions-idle.ps1 多会话空闲监控
├── docs/
│   ├── PROJECT-GUIDE.md        ← 本文
│   ├── RESUME-HERE.md          下次开工指令
│   ├── NEXT-DEVELOPMENT.md     待开发功能
│   ├── design/                 设计文档（当前有效的）
│   └── archive/                历史归档（只读，勿当现状）
├── dist/                       构建产物
└── build/offline-build.ps1     唯一的构建入口（不用 Gradle）
```

---

## 三、构建与验证

### 构建（必须先关 IDEA）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1 `
  -Version 0.9.24 `
  -OutName neovide-cursor-0.9.24.jar `
  -BaseJar 'D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.14-conclusion.jar'
```

### 全量门禁（12 段）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-t9-full.ps1
```

**必须看到 `ALL T9 VERIFICATION PASSED`。**

| 段 | 守什么 |
|---|---|
| build | 代码能编译打包 |
| parity | 与上游行为一致 |
| audit | 19 项观感参数 |
| ime | IME 几何判据（**6 项已知破损，标记为警告**） |
| t7 / br / 4bugs | 各批次回归 |
| v-mutations | 变异测试（验证探针有效） |
| settings-v2 | 设置面板 |
| t4-verify | 持久化 |
| D0 | `core/NeovideConfig.java` 冻结 |
| D0b | 默认值金表（24 个字段） |

---

## 四、硬约束（违反会让门禁变红）

| 约束 | 说明 |
|---|---|
| **`core/NeovideConfig.java` 冻结** | D0 用 `git diff --exit-code`，改一个字就红 |
| **默认值不能变** | D0b 金表比对 24 个 public 字段的运行期值 |
| **不用 Gradle** | 只有 `build/offline-build.ps1` |
| **先写探针再写实现** | 光这轮就因跳过它返工 6 次 |

---

## 五、当前功能状态

### 已完成

| 功能 | 版本 |
|---|---|
| 拖尾物理（四角弹簧） | — |
| 设置面板（11 滑块 + 7 复选框） | — |
| 持久化 + 预设 | — |
| 多光标（`Map<Caret, SpringTrail>`） | 0.9.16 |
| 光标宽度对齐（`EditorUtil.getDefaultCaretWidth`） | 0.9.17 |
| 块选区 + 相邻行分组 | 0.9.18 |
| 块选区每行一条线 | 0.9.20 |
| 块选区共享 charsMoved | 0.9.21 |
| 选区跟随 lead | 0.9.23（**待验证**） |

### 已知限制（如实记录）

| 项 | 状态 |
|---|---|
| 残影模式多光标 | ❌ 仍是单光标 |
| IME 探针 6 项 | ⚠️ 未适配完，门禁标为警告 |
| 辉光层数随光标数降级 | ❌ 未做 |
| GitHub 发布 | ⚠️ 落后 9 个版本（停在 v0.9.14） |

---

## 六、版本演进

| 版本 | 内容 |
|---|---|
| 0.9.14 | 最后一个已发布版本 |
| 0.9.16 | 多光标基础 |
| 0.9.17 | 光标宽度改用官方 API |
| 0.9.18 | 块选区支持 + 相邻行分组 |
| 0.9.19 | ~~块选区整块矩形~~（**已回退，方案错误**） |
| 0.9.20 | 块选区恢复为每行一条线 |
| 0.9.21 | 块选区共享 charsMoved |
| 0.9.22 | 选区跟随 lead（**修反了**） |
| 0.9.23 | 修正 0.9.22 + 块选区跟 blockEnds |

---

## 七、文档地图

### 有效（反映当前状态）

| 文档 | 内容 |
|---|---|
| **PROJECT-GUIDE.md**（本文） | 工作区地图 + 硬约束 |
| `docs/RESUME-HERE.md` | 下次开工指令 |
| `docs/NEXT-DEVELOPMENT.md` | 待开发功能 |
| `docs/archive/VERSIONS.md` | 版本核对命令（已归档，0.9.4 时代） |
| `docs/design/MULTICURSOR-RESEARCH.md` | 上游多光标调研 |
| `docs/design/MULTICURSOR-PROGRESS.md` | 多光标进度 |
| `LESSONS.md` | 踩坑记录（根目录，权威汇总，仍有效） |
| `docs/archive/ASSERTIONS.md` | 断言清单（已归档，早期） |

### 历史归档（**只读，勿当现状**）

`docs/archive/` 下全部文件。典型陷阱：

| 文件 | 为什么不能当现状 |
|---|---|
| `ARCHIVE-PERFORMANCE.md` | 性能数据来自旧版，已修正过 5 处错误结论 |
| `ARCHIVE-SMOOTH-CONCLUSION.md` | 第 4 节有失效横幅 |
| `PROGRESS.md` | 停在 2026-10-22（0.9.9 时代）。2026-10-27 从根目录移入本目录 |
| `VERSIONS.md` / `ROADMAP.md` / `0.9.8-TASKS.md` 等 | 2026-10-27 从根目录批量移入本目录，历史提交完整保留 |

### 上游资料

| 位置 | 内容 |
|---|---|
| `D:\deepseekwork\upstream-research\` | 上游 v0.5.1 完整代码 + 调研报告 |
| `docs/design/UPSTREAM-PITFALLS.md` | 上游 22 条踩坑记录 |

---

## 八、下次开工

```
读 D:\deepseekwork\neovim-cursor-pr\docs\PROJECT-GUIDE.md，
再读 docs\RESUME-HERE.md，然后跑 run-t9-full.ps1 确认门禁。
```

**每次一个功能做完就本地 git commit**（未推送，等用户决定）。