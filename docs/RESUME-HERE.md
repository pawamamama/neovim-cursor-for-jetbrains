# 下次开工指令

> 复制下面「一句话指令」给 AI 即可继续开发。
> 本文档所有事实已于 2026-10-23 核对。

---

## 一句话指令（直接复制）

```
读 D:\deepseekwork\neovim-cursor-pr\docs\RESUME-HERE.md 后继续开发。
先跑 tools\settings-probe\run-t9-full.ps1 确认门禁 12 段全绿，
然后按 P0 → P1 顺序做，每步都要先写探针再写实现。
```

---

## 项目现状（核对基线）

| 项 | 值 |
|---|---|
| HEAD | `d289cb3` |
| 生产代码 | **29 个 java** |
| 探针 | **44 支** `V*.java` |
| 已发布 | **v0.9.14**（`neovide-cursor-0.9.14.jar` / 129,015 B / `F67BD071`） |
| 门禁 | 12 段 + parity + D0 + D0b |
| 仓库 | https://github.com/pawamamama/neovim-cursor-for-jetbrains |
| 上游 | v0.5.1（我们已回馈 PR #10） |

---

## 必读文档（按顺序）

| 文档 | 作用 |
|---|---|
| `docs/NEXT-DEVELOPMENT.md` | **核心贡献 + 四项待开发功能规划 + 技术债** |
| `docs/design/MULTICURSOR-AND-NEXT.md` | 多光标完整调研（含上游架构） |
| `START-HERE.md` | 项目入口 |
| `LESSONS.md` | 踩坑记录（含我犯过的 5 次错） |
| `docs/archive/ARCHIVE-PERFORMANCE.md` | 性能调查真相 |
| `ASSERTIONS.md` | 断言清单 |

---

## 开发规则（硬性）

### 1. 先写探针，再写实现

**这不是建议，是这轮五次踩坑换来的教训。**

我犯过的错（全部记在 `docs/archive/ARCHIVE-PERFORMANCE.md`）：

| # | 错误 | 教训 |
|---|---|---|
| 1 | 「98% 时间在 repaint」 | 从注释反推，从未实测 |
| 2 | 「105fps 是硬上限」 | 注释里的历史数字不能当现值 |
| 3 | 改帧间隔到 5ms | 没测 EDT 承受能力 |
| 4 | **「maxGap 中位 375ms」** | **正则字段错位，那字段根本不存在** |
| 5 | 「前缘/后缘解耦」 | 违反 `f[0]≡1` 恒等式，几何必翻折 |

**共同模式：先用推断或错误解析"造"一个数据，再基于它下结论。**

### 2. 边界（不可越）

| 规则 | 说明 |
|---|---|
| `core/` 改动 | 必须跑 `run-t9-full.ps1`，parity 必须 ALL OK |
| 默认值 | D0b 金表会拦（`tools/parity/defaults-golden.txt`） |
| 不加 Gradle | 只用 `build\offline-build.ps1` |
| 不删文件 | 只移动或重命名 |
| 动画参数 | 不许改默认值除非显式 `-UpdateGolden` |

### 3. 常用命令

```powershell
# 全量门禁（12 段）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-t9-full.ps1

# 只跑探针（快）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1

# 变异测试（验证探针真的能抓到 bug）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-v-mutations.ps1

# 默认值金表
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\parity\run-defaults-gate.ps1

# 打包（须先关 IDEA）
pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 `
  -Version 0.9.15 `
  -OutName neovide-cursor-0.9.15.jar `
  -BaseJar "C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\neovide-cursor-pawamamama\lib\jetbrains-neovide-cursor-0.9.8.BACKUP.jar"
```

---

## 待办优先级

### P0：光标宽度对齐（半天）

**问题**：`NeovideConfig.cursorWidth = 2f` 硬编码，IDEA 原生光标实测约 5px。
保留原生光标时两条边都露出来 → 用户看到「两个光标」。

**修法已定位**：

```java
// CaretAnimator.refreshTarget()
double width = config.cursorWidth;
try {
    int real = editor.getSettings().getLineCursorWidth();
    if (real > 0) width = real;
} catch (Throwable ignored) { }
```

**分层**（用户明确要求）：

| 模式 | 用哪个宽度 |
|---|---|
| `hideNativeCaret=true`（常驻绘制） | `config.cursorWidth`（用户可调） |
| `hideNativeCaret=false`（保留原生） | `getLineCursorWidth()` 对齐 |

**先写探针**：验证读到的宽度与原生光标实际像素宽度一致。

---

### P1：多光标支持（约 2 天）

**上游实现**：`Map<Caret, SpringTrail>` + `startFrom(source)` + `MAX_TRAILS=64`

**三步走**（详见 `docs/design/MULTICURSOR-AND-NEXT.md`）：

| 步 | 内容 | 验证 |
|---|---|---|
| 1 | 引入 `SpringTrail`，仍只画主光标 | parity 逐位一致 |
| 2 | 绘制层支持多四边形 | Alt+拖拽列选能看到多条 |
| 3 | 新光标从相邻起飞 | 列选时新光标"滑下来" |

**关键细节**：
- `IdentityHashMap` 按引用判等（`Caret` 没有可靠的 `equals`）
- 快速路径缓存键 = `(行, 列, 文档修改戳)` —— 只看行列会在 IME 上屏后错位

---

### P2：接管其他输入框（需先调研）

| 目标 | 状态 |
|---|---|
| 主编辑器 | ✅ 已支持 |
| 控制台 | ⚠️ 部分（`staticCaret` 分支） |
| 搜索框 / 查找替换 | ❓ 不是 `Editor` |
| 对话框文本框 | ❓ `JTextComponent` |

**调研方向**：`KeyboardFocusManager` 监听焦点 + 判断 `JTextComponent`。
**风险**：全局接管可能影响性能，需严格限定范围。

---

### P3：内置终端（技术不确定）

**关键问题**：IDEA 终端的光标**不是 `Editor`**。

三条路径（风险从低到高）：

| 路径 | 说明 | 风险 |
|---|---|---|
| JediTerm 集成 | 拿 `TerminalTextBuffer` 光标位置 | 内部 API，版本敏感 |
| 自绘覆盖层 | 在终端组件上挂面板 | 坐标对齐难 |
| **不做** | 终端本身有光标闪烁 | — |

**必须先做开关**（`enableInTerminal`，默认关）。

---

## 已完成的（不用再做）

- ✅ 设置面板 + 持久化 + 预设系统（232 KB，上游没有）
- ✅ 按字符判断的输入逻辑（4 次踩坑换来的）
- ✅ `stretchRatioK` / `typingSmoothness` 两个手感参数
- ✅ 5 个 README + 语言切换栏
- ✅ v0.9.14 Release + 4 段 60fps 演示视频
- ✅ 工作区整理（60 md → 16，95 jar → 3）
- ✅ D0b 默认值金表（补上 12 个盲区字段）

---

## 绝对不要做的事

| 禁止 | 原因 |
|---|---|
| 恢复「前缘硬吸附」的默认开启 | 与「平滑输入」矛盾，用户明确反对 |
| 把 `shortAnimationLength` 提到 0.24 | 实测只让后缘滞后从 2.8px 恶化到 15.9px |
| 改 `ACTIVE_DELAY_MS` 到 5ms | 常驻刷新降频会变卡（已试过，用户否定） |
| 按 `active` 参数给常驻刷新降频 | 滚动/选择/补全不触发 `caretPositionChanged`，会卡 |
| 用 diff 正则判断默认值被改 | 13 个反例全部绕过（已证伪） |
| 改 `DampedSpring` 的衰减因子 `c` | 会逐帧累积放大（上游踩坑 #4） |

---

## 用户偏好（重要）

1. **只用中文回答**
2. **命令给可直接复制的纯文本，用绝对路径**
3. **先给方案再动手**——「能不能做 X」是探询，不是开工令
4. **不要反复确认**——授权范围内的事一次做完
5. **删除/移动前给清单确认**

---

## 用户实测反馈（已确认的现象）

| 反馈 | 对应问题 | 状态 |
|---|---|---|
| 「横向拉伸变小了」 | `typingSmoothness=1.0` 压平梯度 | 已修为默认 0 |
| 「连续删除光标跟不上」 | 同一原因（滞后 22.81px） | 已修 |
| 「Tools 里没有自定义预设」 | 只遍历编译期常量 | 已修 |
| 「两个光标」 | 宽度 2px vs 5px 不一致 | **未修（P0）** |
| 「拖尾太短一闪就没」 | 上游 `short=0.05` | 我们用的是 0.11 |
| 「帧率下降」 | 我改 5ms 导致的 | 已回退 |
