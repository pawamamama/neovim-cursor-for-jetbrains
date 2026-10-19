# NEXT-TASK-IME.md —— 水杉输入法光标错位（交接给新会话）

> 生成：2026-10-19
> 基线：`git HEAD = d74de44`（已提交 0.9.2-imetime-fade，工作区干净）
> 当前安装：**0.9.1-zeroopt**（`C718675E046CAB82`）—— 0.9.2 尚未安装
> 上游参考：`yyyyolo7a79-sketch/neovim-cursor-for-jetbrains` @ `6bed09d`

---

## 0. 一句话

**0.9.2 里的 F1-b 判据对「水杉输入法」无效，因为它依赖一个不成立的前提。**
需要换成**几何判据**。原生 Windows 输入法（微软拼音）本来就不出这个 bug。

---

## 1. 现象（用户原话，唯一的现场证据）

> "我用中文输入……这种无效字符的时候……会随机卡到某个字符中间
> 而且是 比如中这个字 光标会卡在 **竖那一个笔画** 上
> 而且好像 按右方向 变向左移动但是不稳定复现
> 而且字符卡在中间……一定在 十个字符 百分之50 之后卡住"

### 1.1 决定性鉴别结果（用户实测，2026-10-19）

| 输入法 | 是否出现 |
|---|---|
| **Windows 原生**（微软拼音） | **不出现** |
| **水杉输入法**（Metasequoia IME，v0.9.5） | **出现** |

> 这一条推翻了我们此前"这是通用 IME 组合态问题"的假设。

### 1.2 另外四条已确证的观测（此前实验）

| 实验 | 结果 | 推论 |
|---|---|---|
| 滚动编辑器 | **无效** | 插件无滚动监听（全仓 `Scroll\|VisibleArea\|Viewport` = 0 命中） |
| 切标签页再切回 | **无效** | 不改内容区尺寸，不触发 `forceResync` |
| 改窗口大小 | 恢复 | `forceResync` 被置位 |
| **继续输入一个字符** | **立即恢复，落到行尾** | 坐标是**缓存**问题，不是算错 |

---

## 2. 已确证的机制（含第三方输入法侧的证据）

### 2.1 水杉输入法有一项固有设计：光标可停在「拼音音节内部」

从水杉仓库的 issue #940（**v0.11.0 修复，2026-10-18 发布**）原文摘录：

> **问题**：全拼 `nihaoya` 选「你好」后剩 `ya`，左移光标一次，候选窗整窗消失。
> **根因**：`quantized_prefix_end()` 把光标向下取整到完整单元边界。光标落在
> **首单元内部**（全拼首单元是多字母如 `ya`/`hao`）时 floor 到 0……

**关键事实**：水杉在组合期间，光标位置（caret）**可以落在拼音音节内部**，
即 `caret > 0` 但不在完整字符边界上。

### 2.2 为什么这会让我们的覆盖层错位

```
水杉：组合期间把 caret 放在音节内部（例如 "ya" 的 y 与 a 之间）
   ↓
IDE：editor.getVisualPosition() 返回一个 column
   ↓
editor.visualPositionToXY(vp) 按「文档里的字符」换算 x
   ↓
但组合串是临时文本，拼音字母宽度 ≠ 最终汉字宽度
   ↓
算出的 x 落在汉字宽度的一半处  ← 用户看到的「卡在竖笔画上」
```

### 2.3 为什么 0.9.2 的 F1-b 对它无效

0.9.2 的判据（`FIX-BATCH2.md` §2.3）是：

```java
// CaretAnimator.refreshTarget()
if (!forceResync
        && lastCaretOffset >= 0
        && caretOffset == lastCaretOffset      // ← 前提：真实插入点「没变」
        && vp.column != lastVisualColumn
        && imeSkipFrames < IME_MAX_SKIP_FRAMES) {
    imeSkipFrames++;
    return false;
}
```

**对水杉无效的原因**：水杉的情况下 **`offset` 也变了**（光标真的进了音节内部），
所以 `caretOffset == lastCaretOffset` 为假 → 判据永不触发 → 覆盖层照旧画错。

> 该判据只覆盖"平台给了新 column 但 offset 未动"这一种中间态，
> 而水杉给的是"offset 和 column 都动了，但落在非字符边界"。

---

## 3. 要改成什么：几何判据

### 3.1 核心思路

**不要相信 `visualPositionToXY(vp)` 的 x；用真实插入点（offset）的 x 做交叉校验。**

```java
// 真实插入点对应的像素位置
Point byOffset = editor.offsetToXY(editor.getCaretModel().getOffset());
// 视觉位置对应的像素位置（当前用的）
Point byVisual = editor.visualPositionToXY(vp);

if (byOffset.x != byVisual.x) {
    // 说明视觉位置与真实插入点不一致 → 处于组合中间态
}
```

### 3.2 为什么用 `offsetToXY` 而不是继续用 vp

- `offsetToXY(offset)` 以**文档真实字符**为基准，是"光标逻辑上应该在的位置"
- `visualPositionToXY(vp)` 以**视觉列**为基准，组合串插入时会偏
- 两者的 x 不一致 ⇒ 视觉位置不可信

### 3.3 实现要点

1. **必须先写探针确证**，不要直接改（见 §4）
2. 保留现有 `IME_MAX_SKIP_FRAMES = 15` 冻结上界（防止误拦）
3. `getOffset()` 仍然只读一次（契约 P0-5）
4. `offsetToXY` 是新增调用 —— **需要评估成本**：它在非快速路径帧才跑，
   而快速路径（vp 未变）已经提前返回，所以只在"光标真的动了"的帧上多一次调用
5. 若 `offsetToXY` 返回 null（offset 越界/编辑器销毁），必须优雅降级、不得抛异常

---

## 4. 第一步：写探针确证（不要跳过）

**在真机上打印组合期间的实际数值**，确认"x 落在非字符边界"这个假说。

### 4.1 探针要打印什么

打开 Debug Log（`Help → Diagnostic Tools → Debug Log Settings`，
填 `#com.github.yyyolo7a79.neovidecursor`），或用 `LOG.warn` 临时强制输出：

```
每帧（仅当 vp 或 offset 变化时）打印：
  vp.line / vp.column
  offset
  visualPositionToXY(vp) → x, y
  offsetToXY(offset)     → x, y    ← 这是要新增的对照
  editor.getDocument().isInEventsHandling()  /  是否有未提交的组合文本
```

### 4.2 判读方法

| 观察 | 结论 |
|---|---|
| 组合期间 `byVisual.x != byOffset.x` | ✅ 假说成立，改几何判据 |
| 两者始终相等 | ❌ 假说错误，需要换思路（可能是 IDE 自己的组合渲染层） |
| 两者相等但**画出来的位置仍错** | 说明问题不在坐标，而在**面板的坐标系/裁剪**（需另查） |

### 4.3 如果探针否定了假说

次选假说：**组合串由 IDE 的 IME 层单独绘制，编辑器文档里根本没有它**，
此时 `visualPositionToXY` 是对的，错的是"我们把覆盖层画在了文档坐标系上"。
这种情况下正确做法是：**组合期间不画覆盖层光标**（检测手段见 §5.2）。

---

## 5. 候选方案（探针结果出来后按情况选）

### 5.1 方案 G（首选，若假说成立）：几何判据

```java
Point byOffset;
try {
    byOffset = editor.offsetToXY(editor.getCaretModel().getOffset());
} catch (Throwable t) {
    byOffset = null;   // 降级
}
if (!forceResync && byOffset != null && byOffset.x != p.x) {
    // 视觉位置与真实插入点不一致 ⇒ 组合中间态
    imeSkipFrames++;
    if (imeSkipFrames < IME_MAX_SKIP_FRAMES) {
        return false;   // 跳过本帧
    }
}
```

### 5.2 方案 H（兜底）：组合期间不画覆盖层

检测"是否处于 IME 组合态"的方法（需逐个验证可行性）：

| 手段 | 可行性 |
|---|---|
| `Document` 上监听 `DocumentListener` 看是否有临时插入/删除 | 需要平台 API |
| 用 `FocusManager`/`InputMethodListener` 监听组合开始结束 | Swing 的 `InputMethodListener` 在 JBR 上是否可靠未验 |
| 检查 `offsetToXY` 与 `visualPositionToXY` 的 x 差异（即方案 G） | 最可控 |

### 5.3 方案 I（最保守）：仅在"错位"时自愈

既然用户实测「继续输入就恢复」，可以在**检测到 x 差异**后，
不是跳过本帧，而是**直接用 `byOffset` 的 x**（忽略 vp）：

```java
double x = (byOffset != null) ? byOffset.x : p.x;
```

这样即使 vp 错了，画出来的也是正确位置。

---

## 6. 必须遵守的约束（来自既有契约）

| 编号 | 约束 |
|---|---|
| **P0-1** | 不得把多个 animator 合并进同一个 EDT 任务 |
| **P0-2** | 不得改坐标 `int → double` |
| **P0-3** | 不得改 `TrailCorner.jump()` / `DampedSpring.update()` / `computeNextDelay()` |
| **P0-4** | 不得改 `ui/RenderScheduler.java` 的调度逻辑与常量 |
| **P0-5** | `getOffset()` 每帧最多一次（复用） |
| **P0-6** | 不得为"让淡出可见"改 `FIX-BATCH2.md` §3.2 的分支结构 |
| **数值白名单** | 20 项观感常量全部不得变动（清单见 `FIX-BATCH2.md` §5.1） |

---

## 7. 验证要求（机械证据，不可省）

### 7.1 必须跑的

```powershell
# 1) parity —— 必须三份逐字节一致
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1"

# 2) 常量审计 —— 19 项只能有 hideNativeCaret 一项 DIFF
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-audit.ps1"

# 3) 构建
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1" `
  -Version 0.9.3 -OutName "jetbrains-neovide-cursor-0.9.3-imegeom.jar" `
  -BaseJar "D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.2-imetime-fade.jar"
```

前置：`javac 25.0.2` 在 PATH；`D:\deepseekwork\_idea-anim\idea-libs` 有 381 个 jar。

### 7.2 parity 的判据

若改用 `offsetToXY`，`visualPositionToXY` 可能不再被调用 → **parity 可能失败**。
此时需要**先论证**：parity harness 直接驱动 `TrailCorner`/`DampedSpring`，
不经过 `refreshTarget`，所以**理论上仍应一致**。若不一致，说明误改了 core 包 → 必须回滚。

### 7.3 不可自动化（必须用户手测）

- 水杉输入法下组合期间是否仍错位
- 原生输入法是否**没有任何回归**（这是必须守住的底线）

---

## 8. 回退

| 目标 | 脚本 | 哈希前 16 位 |
|---|---|---|
| 0.9.2 | 无（尚未安装） | `7AC9D80E58CD8567` |
| 0.9.1（当前安装） | `build/rollback-0.9.2.ps1` | `C718675E046CAB82` |
| 0.9.0 | `build/rollback-0.9.1.ps1` | `FA161E4F77C8C999` |

安装前必须**完全退出 IDEA**；先 `Move-Item` 旧 jar 再 `Copy-Item` 新的。

---

## 9. 相关文档

| 文件 | 内容 |
|---|---|
| `FIX-BATCH2.md` | 批次2 契约（含 F1 的原始设计与其失效原因） |
| `VERIFY-BATCH2.md` | 批次2 独立验证报告（49 KB，含 5 条 finding） |
| `BUG-REPORT-CJK-IME.md` | 最初的错位取证（四组实验） |
| `OPTIM-BATCH1.md` | 批次1 契约（零观感优化） |
| `START-HERE.md` | 项目总交接（但**已过时**，最新状态看本文档） |

---

## 10. 一次说清「这不是谁的 bug」

- **不是水杉的 bug**：让光标进音节内部是它的正常设计（#940 那个是它自己的候选窗 bug，已修）
- **不是 IDE 的 bug**：`visualPositionToXY` 按文档字符换算，行为正确
- **是我们的 bug**：覆盖层无条件信任 `visualPositionToXY`，没有校验
  「这个 x 是否落在真实字符边界上」

所以修在我们这边，且**必须保证原生输入法零回归**。
