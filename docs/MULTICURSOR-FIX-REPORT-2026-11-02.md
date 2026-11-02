# 多光标三异常修复报告（现象 A / B / C）

- 日期：2026-11-02
- 计划与证据链：`plans/multicursor-fix.md`
- 代码：`ui/CaretAnimator.java`（根因修复）、`tools/settings-probe/VMultiCursorFix.java`（新探针）
- 状态：**已修复、门禁全绿、变异测试通过；已提交并推送 fork，tag `v0.9.127`；已安装到 IDEA（未启动）**

---

## 一、根因（一行）

`CaretAnimator.refreshTargetInReadAction` 的旧代码（修复前 `:2246`）：

```java
key = resolveBlockKey(lineNo, blockStarts[i], prev);
```

`resolveBlockKey` → `blockTrailKey` 用 **行号** 当 trail key：

```java
return Integer.valueOf(-1000000 - lineNo);
```

平台在 **同一行可以有两个 caret**（不是异常）：

- 261 的 `SelectionModel.getBlockSelectionStarts()` 契约是「每个 caret 一项」，数组长度 = caret 数
  （官方 javadoc 与 `SelectionModelImpl` 字节码均证实，见计划 §1.2①）；
- 本机 virtual space 关闭（`EditorSettingsExternalizable` 默认 `IS_VIRTUAL_SPACE=false`），
  行尾按 → 会把 caret **移到下一行行首**（`CaretImpl.moveCaretRelatively` 的 wrap 分支）。

于是：中文注释行（17 列，最短）先回绕到 List 行（第 5 行）行首 → 第 5 行有两个 caret →
两个槽位算出同一个 key → **共用一条 `SpringTrail`**：

- 先处理的槽位抢走 trail，另一行没有 trail → **现象 A（某行光标消失）**；
- 共享 trail 每帧被两个槽位先后 `setTarget` 到两个位置，两次都返回 true →
  `glowStates.lastMoveNanos` 每帧刷新 → 保持期永不过期 → **现象 B（卡住 + 辉光常亮）**；
- 同一机制在光标合并/孤立后残留共享的移动时刻与组亮度 → **现象 C（孤立光标串亮）**。

## 二、改法

1. 多光标/块选区的每个槽位改用 **caret 对象身份** 当 key（`HashMap`，Caret 未重写 equals/hashCode）；
2. 目标点改为该槽位自己的 `caret.getVisualPosition()` / `caret.getOffset()`
   （不再用 `blockStarts/blockEnds` + 主光标方向猜整组）；
3. 保留行号 key 作为「旧平台（1 caret + N 行 starts）」兜底；`useBlock` 改名 `multiCaret`；
4. `cameraTrail` 改为按主光标 key 判定，并在「主光标不在本帧槽位内（>64 光标）」时兜底取第一条；
5. 多光标判据改为 `count = max(caret 数, starts 数)`（审查 M-1：SelectionModel 调用失败时
   不再把 N 个光标误判成单光标）；
6. 单光标走同一 `slotCaret` 分支，行为与修复前**逐字段等价**（T-5 守门）；D0 零 diff。

## 三、实测（探针 VMultiCursorFix，假平台按 261 契约逐帧驱动真实生产代码）

| 项 | 修复前（M-T1 变异体） | 修复后 |
|---|---|---|
| 6 行右移第 17 帧 | carets=6, trails=**5**, distinctActive=**5** | carets=trails=distinctActive=**6** |
| 同一 caret 的 trail 身份 | 回绕时从 line=4 变成 line=5 | 左右各 20 帧**不变** |
| 停手 60 帧 maxOffset | 0.000（形状会收敛） | 0.000 |
| 停手 60 帧 quadGlow | **1.000（辉光常亮）** | **0.000** |
| 非相邻静止行 quadGlow | 0.000（本就隔离） | 0.000 |
| 单光标 | 1 trail / 1 quad | 1 trail / 1 quad |

> 「maxOffset 停手后会收敛」是实测结论，也是为什么 T-2 单靠 maxOffset 抓不到现象 B：
> 两次 setTarget 都在物理推进之前，形状用的是最后一次目标；真正不熄的是 `lastMoveNanos` →
> 亮度。因此探针补了 **T-2b（quadGlow）**。

## 四、探针与变异测试

| 探针 | 断言 | 变异 | 结果 |
|---|---|---|---|
| T-1 | 同一 caret 的 trail 身份在左右各 20 帧内不变 | M-T1 行号 key | **红** |
| T-2 | 停手 60 帧后每条 trail maxOffset < 0.5 | M-T1 | 绿（见上，非有效判据） |
| T-2b | 停手 60 帧后所有 quadGlow == 0 | M-T1 | **红（1.000）** |
| T-3 | 非相邻行：移动行 glow>0、静止行 glow==0 | M-T3 全局最大亮度 | **红（stillGlow=1.000）** |
| T-4 | 每帧 trails.size()==carets.size()、槽位 trail 两两不同、quadCount 一致 | M-T1 | **红（6/5）** |
| T-5 | 单光标 1 trail、1 quad、目标跟随 | M-T5 槽位数少 1 | **红** |
| T-6 | 有选区：两个方向相反的 caret 各自对准自己的 lead | M-T6 目标改回 selectionEnd | **红（cxB=131 期望 83）** |

## 五、门禁

- 全量探针：**114 个注册探针 ALL PASS**（`VMultiCursorFix` 9/9；`VFadeTick` 45、`VFadePreview` 8 不变）
- D0：`git diff --exit-code -- core/NeovideConfig.java` → **EXIT=0**
- 规模：生产 21,412 行 / 37 文件；探针 34,440 行 / 132 文件（117 个 `V*.java`，114 个注册）
- `fork/main...HEAD`：`0 0`
- **已安装 0.9.127**：`build/install.ps1 -Version 0.9.127 -BaseJar dist/neovide-cursor-0.9.126.jar -NoStart`
  - jar 内版本 `0.9.127`；已装 jar 与 `dist/neovide-cursor-0.9.127.jar` md5 一致（`7fe8462c…`）
  - 个人配置 11/11 字段还原，丢弃 0 个；安装后 IDEA 已关闭（未自动启动）
- 提交：`git log -1`（`fix(ui): 多光标按 caret 身份建 trail，修复同行 key 塌陷`），
  已推送 `fork/main`（SSH）；tag `v0.9.127` 已建并推送；GitHub Release 已发布

## 六、审查（code-reviewer + java-reviewer）

- **code-reviewer：APPROVE，0 CRITICAL / 0 HIGH**。独立用本机 jar 字节码核实了三件事
  （per-caret、Caret 引用判等、单光标分支逐字段等价），并独立跑探针 + 变异测试得到
  `PASS 7 / FAIL 3` 与本文一致。
- **java-reviewer：0 CRITICAL / 0 HIGH，Warning（MEDIUM/LOW）**。同样独立核实 per-caret
  与引用判等，并指出 `multiCaret` 判据对 SelectionModel 异常敏感（M-1）、探针未覆盖有选区
  场景（M-2）、探针未真正排空调度线程（M-3）、>64 光标时 cameraTrail 失准（M-4）。
- **本轮已处理**：M-1（判据改为 max(caret, starts)）、M-2（新增 S5 / T-6）、
  M-3（join 调度线程 + invokeAndWait 排空 EDT）、M-4（cameraTrail 兜底）；
  另清理了审查指出的不可达分支、死变量 `targetW/H/Cx/Cy`、与行为相反的 startFrom 注释、
  探针类注释的「全部修复前红」表述、以及过期行数。
- **保留的已知缺口**（不影响本次根因，记录在案）：旧平台兜底分支没有端到端驱动
  （261 上不可达）；`MAX_TRAILS=64` 边界无专门探针；`CaretAnimator.dispose()` 未清 `trails`。
