# 代码审查（主会话，2026-10-25 深夜）

> 这是主会话自己跑的审查，与子代理 `9f3672f2` 的独立审查并行。
> 子代理报告完成后合并进 `CODE-AUDIT-0.9.87.md`。

审查范围：`v0.9.83..HEAD` 生产代码 6 文件 / 201 行新增。
方法：`code-review` skill 的 8 角度（逐行 / 删除行为 / 跨文件 / 简化 / 效率 / 复用 / 抽象高度 / 约定）。

---

## Blocker 1：`matchColorPreset` 的判据失效

**现象**：选「彩虹（动态）」可能匹配不上，或选「原版粉色」+ 开光标渐变会被误认成彩虹。

**根因**：

```java
// CursorPresetApplier.java:231-233
if (p.tailColor().equalsIgnoreCase(s.tailColor)
        && p.shadowColor().equalsIgnoreCase(s.shadowColor)
        && p.rgbWash() == s.rgbGradient) {   // ← 这个判据 0.9.86 起失效
```

「彩虹」与「原版粉色」两个 HEX 完全相同（`#FFC0CB` / `#FFC0CB`），
只有 `rgbWash` 不同。而 `rgbGradient` 在 0.9.85 之后**已改语义**
（从「走色相循环」变成「光标渐变开关」），不再等于 `rgbWash`。

**后果**：
- 用户选「原版粉色」+ 开「光标渐变」→ `rgbGradient=true` → **误匹配到彩虹**
- 用户选「彩虹」+ 关掉光标渐变 → **匹配不上**，`resolveRgbWash` 返回 false → 彩虹不生效

**这是纪律四-D「一个字段承担两个职责」的又一例。**
0.9.86 我只改了绘制层，**忘了改匹配层**。

**修法**：`matchColorPreset` 的第三个判据改成不比 `rgbGradient`，
改为「两个 HEX 相同」时优先返回第一个匹配（原版粉色），
彩虹靠名字显式选择，不靠反推。

**风险**：改匹配逻辑会影响 Tools 菜单勾选状态 + 面板下拉的选中项。
必须先写探针锁住「选彩虹后能匹配回彩虹」。

---

## Blocker 2：`RGB_END_COLOR` 是死字段

```java
/** RGB 渐变的终止色（保留兼容；新版按色相循环生成，不再使用单色终点） */
private static final java.awt.Color RGB_END_COLOR = new java.awt.Color(0x7B68EE);
```

注释自认「不再使用」，字段还在。删掉。

**修法**：删字段 + 删注释。零风险（无引用）。

---

## High 3：两处缩进错位

```java
// CaretAnimator.java:1873 / :2010
        panel.setRgbEnabled(rgbGradient);
                panel.setCursorRgbWash(cursorRgbWash);   // ← 多 8 格
```

我用 `replace_all` 批量改时没管缩进。功能无碍，但下一眼会以为这行属于别的块。

**修法**：对齐缩进。零风险。

---

## Medium 4：`build-v-probes.ps1` 数组右括号缺失（已由子代理修复）

**这是本轮最严重的发现，由子代理抓出。**

```powershell
$mains = @('VDefects', ..., 'VSwitchKeysGap'   # ← 缺少闭合的 )
```

**从 v0.9.83 起整份脚本 ParseError，从未真正执行过。**

我用 PowerShell 解析器验证：`git show v0.9.83:...` 有 **1 处 ParseError**，
当前版本已被代理修好。

**这解释了为什么「官方门禁从没跑过」—— 一直在用手写命令代替。**

**教训（应该进纪律，但用户说别越级记录）：**
「门禁脚本本身也要被测」—— 一个从不执行的检查等同于没有检查。
加探针时**先跑一次脚本看它是否报错**，不要假设它在跑。

---

## Medium 5：`resolveRgbWash` 的位置可疑（已排除）

我一度怀疑它每帧调用 `matchColorPreset` 有性能问题。
**查证后推翻**：`syncGlobalSettings()` 只有 2 个调用点（安装时 + 模式切换时），
不是每帧。性能无忧。

记下来是因为**这类「看着像性能问题其实不是」的判断失误已经发生过多次**（见纪律四-F）。

---

## Low 6：`buildDualTonePaint` 的 alpha 处理

```java
int alpha = fillColor.getAlpha();
java.awt.Color end = new java.awt.Color(..., alpha);
```

末端色强制用 `fillColor` 的 alpha。若用户把 `tailOpacity` 调到很低，
渐变会变得几乎不可见 —— **这是设计意图还是 bug？**

**判断**：设计意图（渐变的透明度应跟主体一致，不该由辉光透明度决定）。
保留，但注释里已写明理由。

---

## 汇总

| 级别 | 数量 | 项 |
|---|---|---|
| Blocker | 2 | `matchColorPreset` 判据失效 / `RGB_END_COLOR` 死字段 |
| High | 1 | 缩进错位 ×2 |
| Medium | 2 | 门禁脚本 ParseError（代理已修）/ 性能误判（已排除） |
| Low | 1 | 渐变 alpha 语义（设计意图，保留） |

**必须修的：Blocker 1 + 2、High 3。**
Blocker 1 需要先写探针。