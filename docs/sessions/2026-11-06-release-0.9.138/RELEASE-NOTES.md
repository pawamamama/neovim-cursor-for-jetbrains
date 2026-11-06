# Neovide Cursor for JetBrains v0.9.138-a1 版本说明

> 面向用户与决策层 · 2026-11-06
>
> 本文所有数字都来自本次发布前实测；命令与完整输出见同目录
> [`REPORT.md`](REPORT.md) 与 [`evidence/FINAL-gate.txt`](evidence/FINAL-gate.txt)。
> 本文不宣称未验证的手感，也不把实验开关写成稳定功能。

---

## 一、版本速览

| 项 | 值 | 依据 |
|---|---|---|
| 版本 | **0.9.138-a1** | jar 内 `META-INF/plugin.xml` 的 `<version>0.9.138-a1` |
| 日期 | **2026-11-06** | 发布提交时间 |
| git tag | **`v0.9.138-a1`** | 指向本次发布提交（`git show -s v0.9.138-a1`） |
| 发布分支 | `claude/a1-visual-line-05e7f0`（A1 验收分支） | 本 worktree 从其 HEAD 快进 |
| 发布 jar | `neovide-cursor-0.9.138-a1.jar`，**253,941 B** | `stat` 实测 |
| jar SHA-256 | **`b81ec276b403eb81d2caaf68393daac492f0245c6542ef5bef99ede0252688ff`** | `sha256sum` 实测 |
| 兼容区间 | `since-build="242"` / `until-build="261.*"` | jar 内 `plugin.xml`；`offline-build.ps1` 注入 |
| 已装验收包 | **0.9.138-a1**（IDEA 2026.1.3） | 已装 jar 内版本实测 |
| 全量门禁 | **`V-PROBES RESULT: ALL PASS`**（117 个注册探针） | 本次发布前串行执行 |
| D0 | **`EXIT=0`**，`core/NeovideConfig.java` 零 diff | 本次发布前执行 |
| 实机验收 | **PASS**（IDEA 2026.1.3，Windows 11） | A1 验收记录（2026-11-06） |

> 已安装目录里的文件名恒为 `jetbrains-neovide-cursor-0.9.8.jar`，这是加载约定；
> **不要用文件名判断版本**，请读 jar 内 `META-INF/plugin.xml`。

---

## 二、这是什么

给 JetBrains IDE 换上 Neovide 风格的光标拖尾：光标矩形的四个角各挂一套
X/Y 阻尼弹簧，移动时按运动方向给四角排名并分配不同时长，把规整矩形拉成
一条渐隐的缎带；外面再叠一层多层辉光。

本版的核心变化是 **A1：视觉行分支统一**。它不改变弹簧物理、斜度、拉伸、
颜色、辉光、淡出和帧调度，只改变「这次移动该走短跳还是跨行」的判据。

---

## 三、A1 行为变化（本版唯一的功能变更）

### 3.1 旧行为

| 路径 | 判据 | 问题 |
|---|---|---|
| 单光标 | `|offset 变化| <= typingMaxChars`（字符数） | 同行 `Tab` / `End` / `Ctrl+方向` / 鼠标大跳会被字符数判成跨行 |
| 多光标 | 恒传 `charsMoved = -1`，退回 `TrailCorner` 的 **16px** 像素兜底 | 320 条实机日志中，100 次 CROSS 有 **93 次**发生在同一视觉行；其中 49 次只是 `dx=17px`，刚过阈值 |

### 3.2 新行为

```text
同一屏幕视觉行  -> SHORT 分支（shortAnimationLength）
视觉行发生变化  -> CROSS 分支（animationLength）
```

- 单光标与多光标共用
  `SpringTrail.semanticCharsMoved(initialized, previousVisualLine, currentVisualLine)`。
- 设置页预览发送同一组语义值：`INLINE -> 0`，`CROSS -> Integer.MAX_VALUE`。
- 旧的字符数判据（`lastCaretOffset` / `updateCharsMoved`）与多光标像素兜底
  （`updateBlockCharsMoved`）已从生产路径删除。
- `TrailCorner` 的旧像素兜底（`charsMoved < 0`）只保留给未提供语义值的调用方；
  生产路径不再走它。
- 每个光标保存自己的上一帧视觉行：同一帧里，真换行的光标走 CROSS，
  同行移动的光标走 SHORT，互不串扰。

### 3.3 用户可感知的结果

- 同行大跳（`Tab`、`End`、`Ctrl+方向`、按词移动、同行鼠标跳转）现在与普通打字
  一样走短跳分支，不再突然出现一条跨行缎带。
- 真实换行仍走 CROSS 分支，保持原有的 A-like 缎带形状。
- 单光标与多光标的规则一致，不再因为「用的是多光标」而得到不同形状。

> 实机验收结论：**PASS**。验收环境 IDEA 2026.1.3（Windows 11），安装包
> `0.9.138-a1`，个人配置 8/8 还原。逐项操作细节未写入仓库，详见
> [`docs/sessions/2026-11-06-a1-visual-line/REPORT.md`](../../2026-11-06-a1-visual-line/REPORT.md) §7。

---

## 四、验证与质量

> 下面的结果来自本次发布前**串行执行的一次**全量门禁，不是从旧报告抄的。
> 命令与完整输出见 [`REPORT.md`](REPORT.md) §4 与
> [`evidence/FINAL-gate.txt`](evidence/FINAL-gate.txt)。

| 项 | 结果 |
|---|---|
| 全量门禁 | `V-PROBES RESULT: ALL PASS`；117 个注册探针 |
| A1 专项 | `VMultiBranchDiag` 35/35、`VPreviewBranch` 8/8、`VTypingSpan` 9/9 |
| 预览同源 | `VPreviewParity` 12/12 |
| D0 | `core/NeovideConfig.java` 零 diff，`EXIT=0` |
| 源码编译 | 37 个生产 `.java` |
| 探针规模 | 118 个 `V*.java` / 133 个 `*.java` / 34,901 行 |
| 生产代码规模 | 37 文件 / 21,498 行 |
| 设置模型 | 29 个字段，其中 13 个属于预设域 |
| 设置面板 | 14 个滑块 + 11 个复选框 + 4 个下拉，7 个标签页 |
| 实机验收 | **PASS**，IDEA 2026.1.3，2026-11-06 |

**已知 informational**：`VRegr2` 仍有 R-5 / R-6 两条红（`typingSmoothness` 不参与
`matchAnimPreset` / 改内置预设不派生副本），按 `$informational` 不计入失败门。
这是**既有功能缺口**，不是本版引入，也不在本版修复范围内。

---

## 五、实验性开关（必须看清）

### 5.1 跨行形状实验（`crossShapeExperiment`）

- **设备级、默认关**，位于设置页「特殊」标签页。
- 开启后只接管**跨行 / 鼠标拖动 / 键盘大跨度**路径；单行打字、斜度、拉伸、
  平滑、辉光、颜色、淡出都不变。
- 当前公式：对齐度 `> 0.5` 的角固定 **20ms**，其余角取
  `animationLength × {1.0, 0.72, 0.42, 0.22}`。
- **状态：实验性，尚未改好。** A-like 形状还没有通过观感验收，
  **不建议日常使用**。
- 开启后「拖尾密度」置灰，值保留、不清空；跨行动画时长仍是时间基准。
- 与「缎带」预设组合时可能很难看；这是已知的组合限制。

### 5.2 前缘贴合（`leadingSnap`）

占位复选框：能勾选、能持久化，但**不改变任何行为**；界面文案已写明「待实现」。

### 5.3 残影模式

实验性渲染模式；只跟随主光标，辉光淡出不适用。日常请使用默认的弹簧模式。

---

## 六、兼容性

| 项 | 值 |
|---|---|
| IDE 构建区间 | `since-build="242"` / `until-build="261.*"` -> IntelliJ Platform **2024.2 至 2026.1** |
| Java | 21（class 文件版本 65） |
| 已实机验证 | IntelliJ IDEA 2026.1.3（build 261），Windows 11 |
| 未验证 | 2024.2 运行时；其他 JetBrains 产品（WebStorm、GoLand、CLion、Rider、PhpStorm 等）；Linux / macOS |
| 不支持 | Fleet |

维护者机器上的 PyCharm 2025.2 装的是**上游 0.6.0**，仅用于对比，不是本版。

---

## 七、安装 / 升级 / 回滚

### 7.1 从 Release 安装

1. 从 Releases 下载 `neovide-cursor-0.9.138-a1.jar`。
2. IDE：`Settings -> Plugins -> 齿轮 -> Install Plugin from Disk...`
3. 选择 jar，重启 IDE。

### 7.2 用仓库脚本安装（Windows，开发期便利）

```powershell
# 只预览备份/丢弃清单，不动任何文件
pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.138-a1 -DryRun

# 安装但不启动 IDE
pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.138-a1 -NoStart
```

脚本会关闭 IDEA、清空插件 `lib` 目录、设置 XML 和诊断日志，拷贝新 jar，
再把备份的个人配置写回。它写死了 Windows 上 **IDEA 2026.1** 的配置路径，
不会自动安装到 PyCharm。

### 7.3 升级说明

- 本版**没有修改任何默认值**：`VDefaultFreeze` 的黄金值全绿，
  `docs/RELEASE-SAFETY.md` 的「默认值变更记录」仍为空，因此不需要迁移代码。
- 用 `install.ps1` 升级时，个人配置会先备份、装完再全量写回；
  自定义预设、颜色、光标宽高和预设指针都会保留。
- 升级后请读 jar 内 `<version>` 核对，不要看文件名。

### 7.4 回滚

- 手动回滚：把上一版 jar（你保留的备份）复制到已装插件的 `lib` 目录，
  文件名改成 `jetbrains-neovide-cursor-0.9.8.jar`，重启 IDE。
- 回滚的是**真实旧版字节码**；不要只把 `install.ps1 -Version` 改成旧版本号
  再安装 —— 那会用当前源码重新编译，只是贴了旧版本号。

---

## 八、已知限制（与上一版相同）

1. 残影模式只支持单光标。
2. 保留原生光标时可能像「两个光标」（原生约 5px，覆盖层默认 2px）。
3. 帧率受 EDT 限制；2609 条日志样本中位 36.4fps、P90 279ms。
4. 快速连打吃不到超过按键间隔（80–150ms）的长拖尾。
5. 「基础」预设是三者里最跳的。
6. 约 10 键/秒时辉光会呼吸（默认 50ms 保持 / 75ms 淡出，约 0.57–1.0；
   25/50 配置可低到约 0.32）。
7. 终端不会播放动画。
8. `crossShapeExperiment` 与 `leadingSnap` 的状态见 §五。

---

## 九、不在本版范围 / 未验证

- **B1 / B2 未做**：本版没有实施 B1，也没有把 A-like 形状转正。
- **A-like 形状未通过观感验收**：实验开关保持默认关，不建议日常使用。
- **未在 2024.2 运行时验证**：只声明兼容，没有真机跑过。
- **未在其它 JetBrains 产品上验证**：只有 IDEA 2026.1.3 的实机结论。
- **Linux / macOS 未验证**：代码无 OS 分支，但没有真机运行记录。
- **同行大跳的新观感由用户验收**：A1 只改分支选择，不评价具体手感；
  用户结论为 PASS。

---

## 十、复核命令

```bash
# 版本 / tag / jar
git show -s --format='%H %ci %s' v0.9.138-a1
unzip -p dist/neovide-cursor-0.9.138-a1.jar META-INF/plugin.xml | grep -o '<version>[^<]*'
sha256sum dist/neovide-cursor-0.9.138-a1.jar

# 全量门禁（唯一权威入口；串行，只跑一份）
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 \
  -Repo <发布 worktree> -Libs D:\libs

# D0
git diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java

# 数字
ls tools/settings-probe/V*.java | wc -l          # 118
ls tools/settings-probe/*.java | wc -l           # 133
cat tools/settings-probe/*.java | wc -l          # 34901
grep -cE '^\s+out\.add\(\w+\(' src/main/java/com/github/yyyolo7a79/neovidecursor/settings/SettingsField.java  # 29
```
