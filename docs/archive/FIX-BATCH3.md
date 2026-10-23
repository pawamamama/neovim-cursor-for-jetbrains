# FIX-BATCH3.md —— IME 组合态判据换成几何判据

> 状态：**已实现并离线验证；真机未验证**
> 基线：`git HEAD = d74de44`（0.9.2-imetime-fade 已提交）
> 产出：`dist\jetbrains-neovide-cursor-0.9.3-imegeom.jar`（44,523 B，`610AE7E9676E2392…`）
> 回退：`build\rollback-0.9.3.ps1` → 0.9.2（`7AC9D80E58CD8567`）

---

## 1. 问题：批次2 的 F1-b 判据对水杉无效

批次2 的 F1-b 用「`getOffset()` 没变、但 `vp.column` 变了」来识别 IME 中间态。

**这个前提在本 bug 的实际形态下不成立**：水杉输入法组合期间，光标会移动
**进"拼音音节内部"** —— `offset` 和 `column` **都变了**。于是：

```
offset 变 + column 变  →  F1-b 判定为「正常移动」  →  中间态被正常画了出去
```

这正是用户反馈「F1-b 对它无效」的原因。判据选错了"观测点"。

---

## 2. 修法：几何判据（F1-c）

### 2.1 判据

```java
Point byOffset = editor.offsetToXY(caretOffset);   // 按"真实插入点"换算
Point byVisual = editor.visualPositionToXY(vp);    // 按"平台给的视觉位置"换算
if (byOffset.x != byVisual.x) → 判定为中间态，本帧不重算
```

**这不是猜 vp 是否"合理"，而是直接检查"我们即将画出去的那个点"与
"按真实 offset 应处的点"是否重合。**

### 2.2 为什么这两个 API 会分岔（已在 IDEA 261 字节码里核实）

两者走**完全不同的换算路径**：

| API | 路径 | 迭代键 |
|---|---|---|
| `visualPositionToXY(vp)` | `EditorCoordinateMapper.visualPositionToXY` | **vp.column（视觉列）**，调 `Fragment.visualColumnToX` |
| `offsetToXY(offset)` | `EditorCoordinateMapper.offsetToXY` | **offset**（先钳进 `[0, textLength]` 并 `alignToCodePointBoundary`），调 `Fragment.offsetToX` |

正常状态下（vp 确实对应 offset 所在的那个字符边界）两者给出**同一个 x**。

IME 组合期间，平台给的 vp 是按「组合串」的临时字符宽度算的，而组合串（拼音/候选）
与最终汉字的宽度不同 → vp 对应的视觉列与 offset 真实所在的字符边界**不是同一个位置**
→ 两个 x 分岔，差值正好是**半个字宽**。

这与用户观察到的「卡在『中』的竖笔画上」完全吻合。

### 2.3 必须保留的：`IME_MAX_SKIP_FRAMES = 15`

判据拦截时**不更新** `lastVisualColumn`，因此若条件持续成立，拦截会变成**无限期冻结**。
批次2 的 F1-b 就被验证方实测出这个问题（连续 200 帧全被拦、偏差 30px ≈ 1.5 字符）。

**本批原封不动保留这个上界**：连续 15 帧后强制放行一次，最坏只是「120ms 后自愈」。

### 2.4 新增防护：排除 virtual space

光标越过行尾时（IntelliJ **默认开启** virtual space，按 → 可把光标推到行尾之后），
两个 API **本来就应当**给出不同的 x：

- `visualPositionToXY` → 行尾 x + 空格宽 × 越界列数
- `offsetToXY` → offset 被钳到 `textLength`，永远返回行尾 x

这是设计如此，不是中间态。**不排除的话，用户每次按 → 到行尾之后覆盖层都会被冻结**
—— 一个比原 bug 更显眼的新缺陷。`isInVirtualSpace()` 负责排除：
`offset == 行尾` 且 `vp.column > 该行字符数`。

---

## 3. 性能影响

| 项 | 变化 |
|---|---|
| `visualPositionToXY` | **不变**（每帧仍只算 1 次；判据与后续定位共用同一个 `p`） |
| `offsetToXY` | **新增 1 次**，但仅在「未命中快速路径」的帧上（有光标移动时才走） |
| `getOffset` | **不变**，仍每帧 1 次（复用给 `charsMoved`，P0-5 满足） |
| 快速路径帧 | **完全不变**：0 次坐标换算、0 次 `getOffset` |

---

## 4. 验证证据（全部可复跑）

### 4.1 几何判据的 29 项边界

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\ime-probe\run-ime-geom-test.ps1"
```

**结果：29 项全通过。** harness 用 `Proxy` 伪造 `Editor`/`CaretModel`，
驱动**编译产物里真实的** `CaretAnimator.refreshTarget()`（不重写判据）。

覆盖：首次调用不误判 / `forceResync` 优先 / 正常移动放行 /
**中间态（offset 与 column 都变、x 不一致）必须拦截** / 连续中间态在上界处放行 /
组合结束立即放行 / 坐标 API 抛异常时放行 / `getOffset` 取不到时判据不介入 /
快速路径零换算 / 每帧 `getOffset` 恰好 1 次 / 反向不一致同样拦截 /
virtual space 不误判 / 行尾非 virtual space 仍拦截 / 被拦帧不触碰角点坐标。

### 4.2 parity（物理/几何零改动）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1" -BaseRef 9f8ccb7
```

```
PhysicsParity    PARITY OK   0A4FDDE69C3EBC32DF23AEF17D8EAFF15CDA0B25E5E04152B7076AB8C1003883
GeometryParity   PARITY OK   4057AFA03E768C842990FD4B7B4B6D5AF7ED46830A848E8ED99900FFFB526B44
TrailParityTest  PARITY OK   409C5728506FF36D648559D764AB288E7B55753DA92D6251BD44E81F756A08C7
```

三份哈希与 0.9.1/0.9.2 完全一致 → `core` 包未被触碰。

### 4.3 常量审计（19 项只能有 hideNativeCaret 一项 DIFF）

```
---- 19 项中 DIFF 数 = 1 ----
DIFF     hideNativeCaret = (true|false)     | public boolean hideNativeCaret = true;
```

唯一 DIFF 是 `hideNativeCaret`（0.9.2 的 F3 已改，**非本批引入**）。其余 18 项 SAME。

### 4.4 jar 条目级

相对 `0.9.2-imetime-fade`，**只有 2 个文件变化**：

```
CHANGED  com\github\yyyolo7a79\neovidecursor\ui\CaretAnimator.class
CHANGED  META-INF\plugin.xml          （仅版本号）
```

20 个条目，无增无删。**没有误改任何其它类。**

---

## 5. 真机探针（已备好，本次未跑）

用户选择「直接修复」，因此**没有在真机上执行探针**。
这留下一项未验证：几何判据是否真的对水杉生效。

探针已完整交付，可随时补做：

```powershell
# 1) 构建探针插件（已构建好的在 dist\neovide-ime-probe.zip）
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\build-ime-probe.ps1"
```

2. IDEA → Settings → Plugins → ⚙ → **Install Plugin from Disk** → 选 `dist\neovide-ime-probe.zip`
3. 重启 IDEA，用水杉输入法正常打字（乱打、停在候选上、按方向键都行）
4. 日志写到 `%TEMP%\neovide-ime-probe.log`

**探针同时回答两个问题**（缺一不可）：

| 问题 | 看什么 |
|---|---|
| 组合期间 `p.x != q.x` 是否真的发生？差值是否 ≈ 半个字宽？ | `>>> DX_NONZERO` 行 |
| **正常场景下是否也会不一致**（会不会像 F1-b 那样误伤）？ | 有无 `>>> DX_NONZERO` 出现在非组合期 |

探针还带一个**不依赖输入法**的合成自检：在临时编辑器里放一个 inline inlay，
直接测量两个 API 是否分岔 —— 这条即使不装水杉也能跑，可先验证判据方向对不对。

---

## 6. 未做 / 边界

| 项 | 说明 |
|---|---|
| 真机验证 | **未做**（用户选择直接修复）。探针已备好 |
| 其它输入法 | 只针对水杉的「组合期进入音节内部」形态；Windows 原生 IME 本就不复现 |
| 多光标 | 未涉及（本批仍只看主光标） |
| 纵向错位 | 判据只比较 x。纵向差异属换行/软换行重排，交给正常路径 |
| 软换行/tab 展开 | `isInVirtualSpace` 用「字符数」而非「视觉列数」，对 tab 是保守方向（更不会误判为虚拟空间）；软换行由 `forceResync` 的尺寸检查覆盖 |

---

## 7. 一句话给下一个会话

**判据从「offset 不变但列变了」换成了「两个坐标 API 的 x 不一致」，
上界 15 帧保留，virtual space 已排除。离线 29 项 + parity + 审计全过，真机待验。**
