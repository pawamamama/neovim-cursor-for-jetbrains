# 接管全部 IDEA 输入框 —— 调研与方案

> 2026-10-24 ｜ **只做调研，未改代码**
> 目标：覆盖所有可输入位置，终端做成可选项

---

## 一、IDEA 里的「可输入位置」到底有几类

| 类别 | 具体位置 | 现在支持 |
|---|---|---|
| **编辑器** | 代码编辑区（`MAIN_EDITOR`） | ✅ |
| **控制台** | Run / Debug / Build / Problems 输出区（`CONSOLE`） | ✅（静态光标） |
| **DIFF / PREVIEW** | 差异视图、Markdown 预览 | ✅（走编辑器） |
| **搜索框** | `Ctrl+F`、`Ctrl+Shift+F`、Find in Files | ❌ |
| **对话框输入框** | 重命名、提交信息、设置里的文本框 | ❌ |
| **终端** | Terminal 工具窗 | ❌ |
| **控制台命令输入行** | Run 窗口底部的输入行 | ⚠️ 待确认 |

---

## 二、三条技术路线（已实测 API 存在性）

### 路线 A：`EditorFactoryListener`（现状，已是最大覆盖）

**实测**：`EditorKind` 只有 5 种，`EditorFactoryListener` 能拿到全部：
```
UNTYPED / MAIN_EDITOR / CONSOLE / PREVIEW / DIFF
```

**结论**：所有【基于 Editor 的】输入位置已经全覆盖了。
搜索框、对话框输入框**不是 Editor**，这条路走不到。

### 路线 B：全局 `KeyboardFocusManager`（覆盖搜索框/对话框）

**实测**：JDK 标准 API，无需 IDEA 内部类：
```java
KeyboardFocusManager.getCurrentKeyboardFocusManager()
    .addPropertyChangeListener("focusOwner", e -> { ... })
```

**能拿到**：任何获得焦点的 `JTextComponent`（搜索框、对话框输入框都是）。

**要解决三个问题**：

| 问题 | 说明 |
|---|---|
| 拿不到光标位置 | `JTextComponent` 只有 `getCaretPosition()`（字符偏移），没有像素坐标 |
| 拿不到光标尺寸 | 需要从 `FontMetrics` 算，或找它的 `Caret` 对象 |
| 覆盖层挂哪 | 没有 `contentComponent` 那种「坐标系天然对齐」的容器 |

**结论**：**技术上可行但没有廉价方案**。需要为每个 `JTextComponent` 自己算坐标 + 自建覆盖层。

### 路线 C：终端（`JediTermWidget`）

**实测**：`JediTermWidget` **在 classpath 里**（`com.jediterm.terminal.ui`），
但 `TerminalView`（IDEA 终端插件类）**不在** —— 运行期才加载，需要反射。

**关键接口（已实测）**：
```java
JediTermWidget.getTerminalPanel()        → TerminalPanel
TerminalPanel.setCursor(int x, int y)    → 光标行列（字符单位）
TerminalPanel.myCharSize                 → Dimension（字符像素尺寸，protected）
TerminalPanel.getComponent()             → JComponent（挂覆盖层用）
```

**能算出坐标**：`x * charWidth`、`y * charHeight`。

**但终端不适合拖尾，理由**：

| 理由 | 说明 |
|---|---|
| 终端光标是块状的 | 整格反色，拖尾会破坏字符可读性 |
| 终端高频刷新 | AI CLI 输出时每帧重绘，拖尾会糊 |
| 光标闪烁是终端语义 | 隐藏它破坏「终端在跑」的反馈 |

→ **所以终端做成可选、默认关**，这与你的要求一致。

---

## 三、其他实现是怎么做的（查证结果）

| 项目 | 覆盖范围 |
|---|---|
| **VS Code 版 neovide-cursor** | 只覆盖 Monaco 编辑器（`querySelectorAll('.monaco-editor .cursor')`）。**它的 DOM 方案天然管不到原生输入框** |
| **smear-cursor.nvim** | 只覆盖 Neovim 缓冲区 |
| **上游 JetBrains 版 v0.5.1** | 只覆盖 `Editor`（我们已超过它：控制台也支持了） |

**结论：没有任何现成实现做到「全 IDEA 输入框」。**
这是 IDEA 特有的难题（Swing 没有统一的文本光标抽象）。

---

## 四、建议的实施顺序

### 第一步：终端（P0，收益最明确）

**为什么先做这个**：
- API 已确认可用（`TerminalPanel.setCursor` + `myCharSize`）
- 用户明确要（可选项）
- 风险可控（默认关，坏了不影响别人）

**做法**：
```
1. 反射拿 TerminalPanel（终端插件类不在 classpath）
2. 每帧读 setCursor 的行列 → 算像素坐标
3. 挂覆盖层到 TerminalPanel 的父容器
4. 设置项「终端拖尾」默认关
```

**必须先验证的 3 件事**：
1. 反射在 IDEA 2026.1 上能拿到 `TerminalPanel`
2. `myCharSize` 是 protected —— 能否反射读取
3. 终端重绘频率（AI CLI 输出时）会不会拖垮性能

### 第二步：全局 JTextComponent（P1，成本最高）

**代价**：
- 要自己算字符→像素的映射
- 要为每个组件建覆盖层 + 生命周期管理
- `JTextComponent` 种类多（单行/多行/密码框/富文本），**每个都要单独适配**

**建议**：**先只做单行搜索框**（`SearchTextField`），范围可控。

### 第三步：控制台命令输入行（P2）

需要确认它是 `Editor` 还是普通 `JTextComponent`。

---

## 五、风险评估

| 风险 | 等级 | 说明 |
|---|---|---|
| 终端反射失败 | 中 | IDEA 版本升级会改内部类名，需要 fallback |
| 终端性能 | 中 | AI CLI 高频输出时可能拖垮 |
| 全局 JTextComponent | **高** | 覆盖层生命周期难管，容易泄漏 |
| 影响现有功能 | 低 | 新功能全默认关，不影响单光标/多光标 |

---

## 六、我的建议

**先只做终端**（可选、默认关），**全局 JTextComponent 暂缓**。

理由：
1. 终端 API 已确认可用，成本可控
2. 全局 JTextComponent 的成本远大于收益 —— 搜索框里拖尾价值不大，但适配成本很高
3. 你现在有 55 支探针 + 12 段门禁，**新功能要能加进这套验证体系**，否则就是新的不确定源

**如果你同意，我先做终端。**
如果终端做通了，再评估 JTextComponent 要不要做。