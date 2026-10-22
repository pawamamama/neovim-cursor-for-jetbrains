# 开发档案（0.9.9）—— 权威快照

> 生成：2026-10-22 夜 ｜ 分支 `fix/f7-override-read` ｜ HEAD `98399d5`
>
> **本文是当前状态的唯一权威描述。** 与旧文档冲突时以本文为准。

---

## 0. 三句话

1. **抖动已修**（顶点坐标 int → double），**自定义预设已可用**（内置只读 + 自动派生副本），t9 12 段全绿，探针 **234 条全绿**。
2. **重构做了 3 层**：字段表（Phase 3）、EditSession（Phase 1）、探针门面（Phase 0）+ Phase 2 前两步。
3. **拆类（Phase 2 第 3 步）暂停** —— 实测它不是「纯搬家」而是一条 7 方法调用链，硬拆会让两个类互相持有引用。已量化的风险见 §7。

---

## 1. 硬事实

| 项 | 值 |
|---|---|
| 分支 | `fix/f7-override-read` |
| HEAD | `98399d5` |
| **用户机器已装** | **0.9.9-subpixel**（`AED74681` / 122,782 B）—— 2026-10-22 15:19 装入 |
| 回退目标 | `build/rollback-0.9.9-subpixel.ps1` → 回到 0.9.8（`41F16B3F` / 102,223 B） |
| 本地产物 | `dist/jetbrains-neovide-cursor-0.9.9-subpixel.jar` = **122,782 B / `AED74681`**（已装） |
| 并列存档 | `0.9.9-custompresets` = 118,122 B / `147C66C5`（不含抖动修复，未装） |
| 安装状态 | **已安装**（用户明确要求，IDEA 当时未运行） |
| t9 | **12 段全绿**，D0 exit=0，`core/` 零改动 |
| V 探针 | **234 条全绿**（12 个探针） |
| 变异 | t9 的 7 个 + t7 的 5 个，全部按期望变红 |

---

## 2. 自定义预设（0.9.9 的核心功能）

### 2.1 模型

内置预设**永久只读**；用户改动动画字段 → **自动派生**「X 副本」并切过去。

```
选中「原版粉色」（内置，只读）
        ↓
拉动「辉光层数」8 → 10
        ↓
① 自动出现「原版粉色 副本」并切过去
② 原版粉色一位数都没动
③ 之后拖任何滑块 = 在改这个副本
```

### 2.2 依据（不是我拍的）

- **GIMP 官方文档**：编辑内置预设时自动创建副本，因此 `Duplicate Tool Preset` 命令**永远是禁用的** —— 不需要。
- **IntelliJ IDEA 配色方案**：内置方案只读，靠 `Duplicate` / `Rename` / `Delete`。

### 2.3 与 0.9.8 的区别（重要）

| | 0.9.8 | 0.9.9 |
|---|---|---|
| 改内置预设 | **污染它** | **不动它** |
| 显示 | 「原版粉色（已修改）」 | 派生「原版粉色 副本」 |
| 恢复 | 「恢复为 X 默认值」按钮 | 切回内置即可；副本保留 |
| 存储 | `animPresetOverrides`（6 字段覆盖） | `customAnimPresets`（**全 19 参数快照**） |
| 元数据 | `animPresetBase` | `animPresetBase` + `animCustomName` |

**「已修改」概念已删除**，`MODIFIED_SUFFIX` 常量一并删除。

### 2.4 UI

下拉 = 7 个内置 + N 个自定义。三个按钮：**新建预设… / 重命名… / 删除**（内置选中时后两个置灰）。

名称**允许中文、空格、分号、等号** —— 分隔符用不可键入的 U+0001 / U+0002。

---

## 3. 重构三层（已完成）

### 3.1 字段表（`settings/SettingsField.java`，476 行）

**治的病**：同一个参数在 4 处各有一份手抄副本。

| 位置 | 重构前 | 重构后 |
|---|---|---|
| `apply()` | 20 段手写 if | 遍历表 |
| `dirtyAnyField()` | 21 行 `\|\|` 链 | 遍历表（**唯一实现**） |
| `syncStateToConfig()` | 17 行赋值 | 遍历表 |
| `syncConfigToState()` | 17 行赋值 | 遍历表 |

**加一个参数从 8 处降到 2 处**（表一行 + State 一行）。

关键设计：**比较语义逐字段携带**，不能统一 ——
float 用 `Float.compare`（NaN / -0.0f 两个边界）、HEX 用 `equalsIgnoreCase`、
文本把 null 当 `""`（否则 NPE 让整页打不开）。

### 3.2 EditSession（`ui/settings/EditSession.java`，Phase 1）

**治的病**：`pending` / `baseline` 是裸字段，任何方法都能赋值，时序漏一步就是静默 bug。

收成 4 个命名方法：`openAt` / `reloadFrom` / `commitBaselineTo` / `close`。

**RF-4 达成**：赋值点从 7 处 → **2 处，且都在 `syncSessionViews()` 内**。

最有价值的是 `reloadFrom`：它替代了 apply() 里**两条独立语句**
`baseline = deepCopy(target); pending = deepCopy(target);` ——
R-01 那个 bug（第二次点应用冲掉 Tools 改动）**就是漏了第二条**。

### 3.3 探针门面（`ui/settings/ProbeFacade.java`，Phase 0）

**治的病**：263 处探针调用直接绑在面板类上，一拆类就全崩。

22 个方法的接口，`NeovideCursorConfigurable` 实现它。**只声明、不搬实现、不做转换**。

**等价性 19/19**：逐项验证「经门面」与「经实例方法」返回**同一个对象引用**（不是同值）。

---

## 4. 本轮修掉的真实 bug（全部由探针抓到）

| # | bug | 抓它的断言 |
|---|---|---|
| 1 | 名称分隔符用 `;`，名字含分号时条目被切碎 | `VCustom` C-6/C-8/C-10 |
| 2 | 自动派生在 `reset()` 装载历史配置时误触发 → 应用按钮恒亮 | `VDefects` V11-3c / `VerifyPanel` U13 |
| 3 | 恢复按钮文案不跟自定义预设名 | `ProbeSettingsV2` M5-4 |
| 4 | `suppressDerive` 在两个 try/finally 中，第一段抛异常会让它**永久卡住** | 代码审查发现 |

**第 2 个修了三次才对**：前两次把抑制放在调用点，每次都漏掉
`syncAllControlsFromPending()` 末尾那一次。最终把抑制放进该方法**内部**。

---

## 5. 我犯过并已纠正的错误（诚实记录）

| # | 错误 | 纠正方式 |
|---|---|---|
| 1 | 基线对照跑在 worktree 里却没传 `-Repo`，实际测的是主仓库 → 误判「VTools 是基线自带红」 | 带 `-Repo` 重跑，确诊是我引入的回归 |
| 2 | `dirtyAnyField` 写了两份（生产 + 探针用），探针测的是**影子实现** | 收敛为唯一实现，探针调同一个函数 |
| 3 | 批量替换全限定名时**误伤 import 语句** | 编译当场失败，已修 |
| 4 | 改文件时把 LF 写成 CRLF，导致变异脚本 here-string 匹配失败 | 归一为 LF |
| 5 | 误读 t7 的**变异预期变红**为失败 | 分阶段隔离统计 |

---

## 6. 门禁与变异（重构让护栏失效时必须一起改）

| 脚本 | 变异 | 本轮改动 |
|---|---|---|
| `run-v-mutations.ps1` | 6 个（MUT-A..F） | **MUT-C** 改到 `SettingsField.differs`（手写 if 已不存在） |
| `run-t7-probe.ps1` | 5 个（MUT-R01/02/03/R09a/R09b） | **MUT-R01** 改到 `EditSession.reloadFrom` |
| `run-4bugs-probe.ps1` | 4 个 | 无需改（未触及） |

**规律**：每次重构都会让某个变异脚本失配，脚本会显式报错
「源码已变，请同步本脚本」——这是护栏在起作用，不是脚本坏了。

---

## 7. Phase 2（拆类）：未做，风险已量化

### 7.1 现状

`NeovideCursorConfigurable` = **2172 行 / 88 个方法**。目标每类 ≤600 行。

### 7.2 分段实测

| 段 | 行数 | 抽取难度 |
|---|---|---|
| 类声明 + 字段 | 275 | — |
| 会话/抑制标志 | 110 | 已抽（EditSession） |
| **面板构建 `createPanel`** | **1071** | ⚠️ 引用 **22 个私有成员、253 处** |
| reset / apply / isModified | 260 | ⚠️ 与面板状态深耦合 |
| 搜索定位 | 77 | ✅ 零状态耦合，可安全抽 |
| 小工具 | 154 | ⚠️ 静态与实例混杂 |

### 7.3 建议的三步拆分（每步独立提交 + 全量门禁）

1. **抽「搜索定位」**（77 行，零耦合）—— 低风险
2. **抽「小工具」的纯静态部分**（`blurOfSharpness` / `sharpnessOf` / `fmt2` / `modeText`）—— 低风险
3. **抽「面板构建」**（1071 行 / 253 处引用）—— **高风险，单独一轮做**

第 3 步是唯一真正危险的：要把 22 个成员改 `package-private` 或改成传参，
跨文件搬家一次出错就要从头定位。

---

## 8. 红线（全程有效，一条没破）

| # | 约束 | 实测 |
|---|---|---|
| R1 | 不破坏手感 | 已装 jar 全程未动，仍是 0.9.8 |
| R2 | 改完必须审查 | 每批都跑 t9 + V 探针 + 变异 |
| R4 | 不擅自安装 jar | 未安装任何新 jar |
| R6 | `NeovideConfig` 零 diff | **D0 exit=0** |
| R10 | 物理/渲染参数不动 | **parity 逐字节一致** |

---

## 9. 常用命令

```powershell
# 全量门禁（12 段，最权威）
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-t9-full.ps1

# V 探针（230 条）
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\build-v-probes.ps1

# 变异（6 个）
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-v-mutations.ps1

# 构建
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1 `
  -Version 0.9.9 -OutName my.jar `
  -BaseJar 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\neovide-cursor-pawamamama\lib\jetbrains-neovide-cursor-0.9.8.BACKUP.jar'
```

---

## 10. 文件索引

| 文件 | 作用 |
|---|---|
| `PROGRESS.md` | 当前指针（先读） |
| `ARCHIVE-0.9.9.md` | **本文** —— 权威开发档案 |
| `NEXT-REFACTOR.md` | 重构总方案（Phase 0–4 + RF-1..8 判据） |
| `settings/SettingsField.java` | 字段表 + 判脏唯一实现 + 快照编解码 |
| `settings/CustomPresets.java` | 自定义预设纯函数层 |
| `ui/settings/EditSession.java` | 编辑会话（pending/baseline 唯一写入口） |
| `ui/settings/ProbeFacade.java` | 探针门面接口（22 方法） |
| `tools/settings-probe/VCustom.java` | 自定义预设 25 条 |
| `tools/settings-probe/VFacadeEquiv.java` | 门面等价性 19 条 |
