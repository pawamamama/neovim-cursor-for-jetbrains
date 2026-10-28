# 后续开发文档

> 2026-10-23 ｜ 本轮只做调研与规划，**未实现任何新功能**

---

## 一、本项目的核心贡献（相对上游）

### 1. 按字符判断的输入逻辑（最重要的差异）

**上游用像素反推字符数，我们用编辑器的原生字符偏移。**

| | 上游 | **本项目** |
|---|---|---|
| 判据来源 | `jumpX = 位移 / 光标宽` 再比阈值 | `charsMoved = \|offset - 上一帧 offset\|` |
| 横向阈值 | `max(16px, charWidth × 2.5)` | `charsMoved <= typingMaxChars(3)` |
| 中文/大字号 | 需特殊补偿 `shortMoveMinChars` | **天然正确**，与字体字号无关 |
| emoji / 代理对 | 按 2 计但需自行换算 | `getOffset()` 直接给对 |
| 一帧合并多次击键 | 会误判成长距离 | 有 `sameLine` + 字符数双重约束 |

**代码位置**：`TrailCorner.jump()` 第 198-207 行；`CaretAnimator.refreshTarget()` 传值。

**这是我们踩了四次坑才定下的方案**（源码注释里记录了前三次失败）：
1. 用光标宽归一化 → 本插件 2px 光标永远超不过阈值，短跳分支永不生效
2. 写死 16px → 字号 20px 的中文被判成长距离，「英文不卡、删中文卡」
3. `FontMetrics.charWidth('m')` → 'm' 是西文推进量，中文是 2 倍
4. 取「第 0 列到第 1 列宽度」→ 取的是行首字符，光标在中文区仍翻倍

### 2. 输入手感参数（`typingSmoothness` / `stretchRatioK`）

**上游没有这两个概念**，它的 `trailFactors` 是硬编码数组 `{1.0, 0.9, 0.5, 0.3}`。

| 参数 | 作用 | 我们的默认 |
|---|---|---|
| `stretchRatioK` | 派生四角梯度，1~8 连续可调 | 4.5 |
| `typingSmoothness` | 把梯度朝 1 压平 | 0（保持梯度） |

**`deriveTrailFactors(k, smooth)` 是纯函数**，面板数值与实际运行期用同一套公式
（这一点修过一个"面板显示虚报 6.4 倍"的 bug）。

### 3. 完整的设置系统（232 KB，上游完全没有）

```
settings/          4 个文件   100 KB   持久化 State + 字段表 + 预设 + 自定义预设
ui/settings/       5 个文件   132 KB   设置面板（11 滑块 + 7 复选框 + 预设管理）
```

- 字段表驱动（`SettingsField`），加字段只改一处
- 自动派生副本（内置预设只读）
- 自定义预设存全部 19 参数快照，上限 50
- Tools 菜单 8 项

### 4. 向upstream回馈的三项修复（已合并进 v0.5.1 / PR #10）

| 修复 | 影响 |
|---|---|
| 只在空闲档唤醒时重置计时 | 停手后拖尾不再整段以 25fps 播放 |
| `shortMoveMinChars` | 大字号下单个汉字不再被误判成长距离 |
| IME 坐标缓存加文档修改戳 | 第三方输入法上屏后不再错位 |

---

## 二、待开发功能（本轮只做规划）

### A. 多光标支持

**上游实现方式**（已调研，见 [MULTICURSOR-AND-NEXT.md](design/MULTICURSOR-AND-NEXT.md)）：

```java
Map<Caret, SpringTrail> trails = new IdentityHashMap<>();  // 按引用判等，不能靠 equals
List<SpringTrail> activeTrails;                             // 每帧复用，不新建
MAX_TRAILS = 64                                             // 防全选时爆掉
```

**关键设计：新光标「从相邻起飞」**

```java
SpringTrail source = previous != null ? previous : firstInitializedTrail();
trail.startFrom(source);   // 复制四角当前坐标
```

Alt + 向下拖出列选时，新光标从上一行形状滑下来，而不是凭空出现。

**我们的改造难度**：`CaretAnimator` 目前是 `TrailCorner[] corners`（单光标一组四角），
需改为 `Map<Caret, SpringTrail>`。三步走（详见上文文档）：
1. 引入 `SpringTrail`（不改绘制，仍只画主光标）→ parity 可守
2. 绘制层支持多四边形（`panel.updateQuads(List<float[]>)`）
3. 新光标从相邻起飞

**工作量估算**：步骤 1 约 1 天，步骤 2 约半天，步骤 3 约半天。

---

### B. 接管 IDEA 所有可输入框

**当前只挂在编辑器上**（`editorFactoryListener` → `NeovideCaretManager`）。

需要覆盖的位置：

| 目标 | 可行性 | 备注 |
|---|---|---|
| 主编辑器 | ✅ 已支持 | |
| 控制台（Build/Run/Problems） | ⚠️ 部分 | 目前走 `staticCaret` 静态光标分支 |
| 搜索框 / 查找替换 | ❓ | 不是 `Editor`，需另找挂载点 |
| 终端（Terminal 工具窗） | ❓ | 见下 |
| 对话框里的文本框 | ❓ | `JTextComponent`，需全局监听 |

**调研方向**：
- 全局 `KeyboardFocusManager` 监听焦点变化，判断是否为 `JTextComponent`
- 或 `ApplicationListener` + `ComponentManager` 遍历组件树
- **风险**：全局接管可能影响性能与稳定性，需严格限定范围

---

### C. 内置终端支持（如果可行）

**关键问题：IDEA 终端的光标不是 `Editor`。**

可能的技术路径：

| 路径 | 说明 | 风险 |
|---|---|---|
| **JediTerm 集成** | IDEA 终端基于 JediTerm，理论上可拿 `TerminalTextBuffer` 的光标位置 | 内部 API，版本敏感 |
| 自绘覆盖层 | 在终端组件上挂类似面板 | 需解决坐标对齐 |
| **不做** | 终端本身有光标闪烁，拖尾意义不大 | — |

**建议**：先做**开关**（`enableInTerminal`，默认关），实现后再评估是否默认开。

**必须验证的三件事**：
1. 能否稳定拿到终端光标坐标（不依赖内部类最好）
2. 终端高频刷新（AI CLI 输出）时会不会拖垮性能
3. 与终端的 block cursor / 反色会不会打架

---

### D. 中英文切换（README 语言切换）

**上游做法**：README 顶部放语言切换栏。

```markdown
**Language** — [English](../README.md) | [**简体中文**](../README.zh-CN.md) | [繁體中文](../README.zh-TW.md) | ...
```

**本项目现状**：5 个 README 文件都在，但**没有加语言切换栏**（我重写时漏了）。

**待办**：在每个 README 顶部加语言切换。

---

## 三、已知技术债

| 项 | 现状 | 建议 |
|---|---|---|
| `CaretAnimator` 71.6 KB | 单文件过大 | 拆出调度 / 绘制 / 坐标三层 |
| `NeovideCursorConfigurable` 116 KB | 单文件过大 | 拆出面板构建器 |
| 根目录 60 个 md | 混杂 | 移入 `docs/` 分类 |
| `dist/` 95 个 jar | 5.94 MB | 只保留最近 3 个 |
| `build/` 129 MB | 临时产物 | 定期清理 |
| 光标宽度硬编码 2px | 与原生 5px 不一致 | 读 `EditorSettings.getLineCursorWidth()` |

---

## 四、优先级建议

| 优先级 | 任务 | 理由 |
|---|---|---|
| **P0** | 光标宽度对齐 | 用户实测到「两个光标」，直接影响可用性 |
| **P0** | README 加语言切换栏 | 低成本，你明确要求 |
| **P1** | 多光标（三步） | 上游有、我们缺，明确的功能差距 |
| **P2** | 接管其他输入框 | 需先调研挂载点，风险中 |
| **P3** | 终端支持 | 技术不确定，先做开关 |
