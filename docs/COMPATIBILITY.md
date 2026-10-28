# 适用范围调研：哪些 IDE、哪些版本、哪些操作系统

> 生成：2026-10-28 · 对应提交 `6065ecb` · 插件 0.9.102
> **本文把「代码层面能证明的」与「必须真机验证的」严格分开**。凡标「未验证」的，都不是结论。

---

## 零、结论速览

| 问题 | 结论 | 把握 |
|---|---|---|
| Linux 能跑吗 | **理论可以**（代码里没有任何 OS 相关逻辑），但**从未在 Linux 上跑过** | 代码级确定 / 真机未验证 |
| macOS 能跑吗 | 同上 | 代码级确定 / 真机未验证 |
| 别人的 IDEA 能用吗 | 能，**前提是 2024.2 或更新** | 声明与字节码都支持，真机只验过本机 2026.1 |
| PyCharm / WebStorm / GoLand / CLion / Rider / PhpStorm / RubyMine / DataGrip 能用吗 | **理论全部可以**（只依赖平台模块，不含任何语言插件） | 依赖声明可证 / 除 PyCharm 外均未验证 |
| Android Studio | 视其平台版本而定（需 ≥242），且只能「Install Plugin from Disk」 | 未验证 |
| Fleet | **不支持**（Fleet 的插件 API 不是 IntelliJ 平台） | 确定 |
| 2022 年的 IDE 能用吗 | **不能。实际门槛是 2024.2** | 确定（双重原因，见 §2） |

---

## 一、代码层面的硬事实（可复核）

| # | 事实 | 出处 / 验证方式 |
|---|---|---|
| 1 | 只依赖 **`com.intellij.modules.platform`**，没有任何语言模块依赖 | `src/main/resources/META-INF/plugin.xml:30` |
| 2 | 打包时注入 `<idea-version since-build="242" />`，**`until-build` 刻意留空**（接受所有更新版本） | `build/offline-build.ps1:25`（`$SinceBuild = '242'`）、`:55` |
| 3 | 编译命令是 **`javac --release 21`** → class 文件版本 **65** | `build/offline-build.ps1:49` |
| 4 | 平台 API 只用 `com.intellij.openapi.*` / `ui.*` / `util.*` / `ide.*` | `grep -rhoE '^import com\.intellij\.[a-z.]+' src/main/java` 统计：openapi 67 处、ui 18、util 2、ide 2 |
| 5 | **没有任何 OS 分支代码**（`os.name` / 路径分隔符 / 原生调用全为 0）；`Windows` 只出现在**注释里的实测数据** | `grep -rn 'os\.name\|File.separator\|win32'` → 0 命中 |
| 6 | **没有使用 Java 21 专属语法**（record / sealed / switch 模式匹配 / instanceof 模式 全为 0） | `grep -rnE 'record [A-Z]\|sealed \|case .* ->'` → 0 命中 |
| 7 | Gradle 构建（`build.gradle.kts` / `gradlew`）在库，可跨平台打包 | `ls build.gradle.kts gradlew` |

第 5、6 条决定了：**「能不能跨平台」不由 Java 代码决定，「能不能上老版本」也不是语言特性挡住的**。

---

## 二、版本门槛：为什么 2024.2 之前全都不行（两道锁）

| 锁 | 机制 | 后果 |
|---|---|---|
| **声明锁** | `since-build="242"` | IDE 低于 2024.2 时**直接拒绝加载**（插件列表里显示不兼容） |
| **字节码锁** | `--release 21` → class 65 | 2024.2 之前的 IDE 自带 **JBR 17 / 11**，JVM 根本读不了 class 65 —— **把 since-build 改小也没用** |

> 你记得"22 年以前用不了"，实测门槛比记忆更严：**2024.2（build 242）以前一律不行**。
> 这两道锁是配套的，不是随手填的数字。

**想支持 2022.x–2024.1 需要付什么代价**（未做，仅供参考）：

1. `--release` 降到 17（代码没写 Java 21 专属语法，语言层面可行）；
2. 用目标版本的 `idea-libs` 重新编译（**当前只对着 2026.1 / build 261 的库编译过**；
   在 242 上是否会 `NoSuchMethodError` 没验过 —— 这是本项目最大的未知）；
3. 全量探针回归（94 个探针里有依赖具体平台行为的，可能要适配）；
4. 每个目标大版本各做一次真机冒烟。

---

## 三、各产品适用性

**为什么理论上都能用**：插件只挂 `com.intellij.modules.platform` —— 这是**所有** IntelliJ
平台产品的公共模块（编辑器、配色方案、Inlay、EDT 调度都在里面）。语言相关能力一个没用。

| 产品 | 依赖是否满足 | 备注 |
|---|---|---|
| IntelliJ IDEA CE / Ultimate | 是 | 本机唯一实测过的产品（2026.1） |
| PyCharm（本地有 2025.2） | 是（2025.2 = build 252 ≥ 242） | **最便宜的下一次真机验证**：同一 jar 丢进 PyCharm 的 plugins 目录 |
| WebStorm / PhpStorm / RubyMine | 是 | 分别验一次"编辑器里拖尾正常 + 终端不双光标" |
| GoLand / CLion / DataGrip | 是 | 同上 |
| Rider | 是 | 前端是 IntelliJ 平台（后端 .NET 无关），未验证 |
| Android Studio | 是（但其平台版本滞后于 IDEA，需 ≥242 的 AS） | 只能从磁盘安装，Marketplace 不一定列出 |
| **Fleet** | **否** | Fleet 插件 API 与 IntelliJ 平台不是一套，本项目代码无法移植 |

**各产品里都必须复核的两处代码假设**（不是版本问题，是产品差异问题）：

1. **终端编辑器**：`NeovideCaretManager.isTerminalEditor()` 靠**包名前缀**识别
   （`com.intellij.terminal` / `org.jetbrains.plugins.terminal` / `JediTerm`）。
   识别失败 → 终端里出现**双光标**（终端自己画一个 + 我们画一个）。
2. **控制台编辑器**：代码按 `EditorKind.CONSOLE` 走"只补画静止光标"分支。
   各产品的 Build/Run/Services 输出区是否都报 CONSOLE，未逐个验证。

---

## 四、Linux / macOS 支持分析（逐项，标注确定性）

| 环节 | 判断 | 确定性 |
|---|---|---|
| 运行时依赖 | 纯 Swing + Java2D + 平台 API，**无 JNI / 无原生库 / 无系统调用** | 代码可证 |
| 帧调度 | `Thread.sleep` + `onSpinWait`。本机 Windows 实测 1.56ms 精度；**Linux 上通常更好，但没测过** | 未验证 |
| 字体/行高 | 行高一律读 `editor.getLineHeight()`、坐标读 `visualPositionToXY`，**没有写死像素** | 代码可证 |
| 隐藏原生光标 | 改配色方案的 `CARET_COLOR` 为全透明，跨平台一致 | 代码可证 |
| 覆盖层（拖尾画布） | 是编辑器的 Swing 子组件，随组件树缩放 | 代码可证 |
| HiDPI / Wayland 分数缩放 | 覆盖层跟着组件树缩放，理论上与上层一致；**实际观感未验证** | 未验证 |
| 诊断日志路径 | `PathManager.getConfigPath()` → Linux 为 `~/.config/JetBrains/<产品><版本>/` | 代码可证 |
| **安装/打包** | `build/install.ps1`、`offline-build.ps1` 是 **Windows PowerShell**（只是开发流程，不是运行时） | 确定：**Linux 用户目前只能手动放 jar** |

> 结论：**运行时在 Linux 上没有已知阻碍**；真正的缺口是**发布流程**（打包脚本 Windows-only）
> 与**零真机验证**。要用 Gradle 路径补一个跨平台打包，工作量很小（`build.gradle.kts` 已在库）。

---

## 五、必须真机验证的清单（按性价比排序）

| 顺位 | 项 | 验什么 | 成本 |
|---|---|---|---|
| 1 | **PyCharm 2025.2**（你本地就有） | 拖尾是否正常、终端是否双光标、控制台是否双光标 | 极低：装 jar + 重启 |
| 2 | Linux（任一分发版 + 任一款 IDE） | 帧率、HiDPI、Wayland 下的对齐 | 中：需要一台机器/虚拟机 |
| 3 | 一个"重编辑器"产品（CLion 或 Rider） | 大文件、长行、折叠区里的几何与性能 | 中 |
| 4 | Android Studio | 平台版本是否 ≥242、终端识别是否命中 | 中 |

**冒烟顺序（建议）**：打开文件 → 鼠标移动 → 打字 → 跨行跳转 → 多光标 → 切标签页 →
开终端输入 → 开 Build 输出区 → 关插件看光标是否恢复。任何一步在某个产品上异常，
先查 §三 那两处"产品差异假设"，再查版本。

---

## 六、发布前的建议动作（按必要性）

1. **加 `until-build`**（或明确说明不加）：目前留空 = 新版本一律放行，
   而 API 移除会让插件在新 IDE 上抛 `NoSuchMethodError`。建议至少加上"已测试到"的上界，
   例如 `until-build="261.*"`，每年复核一次。取代价：用户升级到 262 后需要新版本 ——
   比"装上了但崩"更好。
2. **PyCharm 冒烟一次**并写进 release notes。
3. 补一条 **Linux / macOS 打包路径**（Gradle 或 shell 脚本），别让用户自己解压。
4. Marketplace 描述写清：**仅 2024.2+**、**Fleet 不支持**、**Linux/macOS 未官方验证**。
5. 若真要支持 2022.x–2024.1：先做 §二 的第 2 步（用老库编译 + 冒烟）再改 `since-build`，
   **顺序不能反** —— 先改声明只会让老 IDE 装上然后崩。

---

## 七、一句话回答三个问题

- **Linux**：代码上没有任何阻碍，但没跑过 —— 属于"应该行，未验证"。
- **PyCharm 等家族**：只依赖平台模块，所以**理论上全家桶通用**；除 IDEA 外都还没真机验过，
  PyCharm 是最便宜的第一次。
- **2022 年以前**：不行，而且不是"以前不行"——**2024.2 以前全部不行**，双重原因
  （声明 `since-build=242` + Java 21 字节码）。
