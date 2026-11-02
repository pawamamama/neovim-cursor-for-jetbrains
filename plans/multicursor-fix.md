# 多光标三异常修复计划（A 光标消失 / B 卡住+辉光常亮 / C 孤立光标串亮）

- 日期：2026-11-02
- 分支：main（HEAD `262db56`）
- 目标：先只读定位，再按「根因 → 改法 → 证据」修；不提交，等用户实机复现
- 相关文档：`CLAUDE.md` 陷阱 1/2/3/4/6、`docs/WORK-DISCIPLINE.md` 四-D/四-G/四-J

---

## 一、Research 记录（可复现，不靠印象）

### 1.1 本仓库现状（key / 清理 / 辉光）

| 问题 | 现状 | 位置 |
|---|---|---|
| trail 的 key 怎么算 | 多光标（`useBlock=true`）用 **行号**：`Integer(-1000000 - lineNo)`；`lineNo` 来自 `Document.getLineNumber(blockStarts[i])` | `ui/CaretAnimator.java:430`（`blockTrailKey`）、`:449`（`resolveBlockKey`）、`:2246`（生产调用点） |
| 一个槽位对应谁 | `blockStarts[i]` 与 `carets.get(i)` 来自同一个 `getAllCarets()` 序列，但生产只用了 offset，**丢掉了 caret 身份** | `:2160`、`:2246` |
| `useBlock` 是什么 | `getBlockSelectionStarts().length > 1` —— 在这个平台上它的真实含义是「**光标数 > 1**」，不是「有块选区」 | `:2160-2170` |
| 清理时机 | 每帧慢路径末尾：`trails.entrySet().removeIf(e -> e.getValue().lastSeenFrame != frameSeq)` | `:2474` |
| 辉光强度 | 逐 trail 的 `maxOffset` → `GlowIntensity.strengthFor` → `advanceTrailGlow`；**显示亮度**取同组最大值 | `:2796-2810` |
| 同组判定 | 行号连续的光标同组（`groupAdjacentLines`） | `:3345` |

### 1.2 平台契约（用已安装 IDE 的字节码核实，不是推测）

安装版本：**IntelliJ IDEA 2026.1.3，build IU-261.25134.95**（`/d/java_learning/idea2026/IntelliJ IDEA 2026.1.3/build.txt`）。

**① `getBlockSelectionStarts()` 是「每个 caret 一项」，不是「每行一项」**

`platform/editor-ui-api/.../SelectionModel.java` 官方 javadoc：

> Returns an array of start offsets in the document for ranges selected in the document currently.
> Works both for a single-caret and a multiple-caret selection (for carets not having a selection,
> caret position is returned).
> **array size is equal to the number of carets existing in the editor currently.**

261 的 `SelectionModelImpl.getBlockSelectionStarts()` 字节码（`intellij.platform.ide.impl.jar`）就是：

```java
List<Caret> carets = myEditor.getCaretModel().getAllCarets();
int[] starts = new int[carets.size()];
for (Caret c : carets) starts[i++] = c.getSelectionStart();
```

结论：`useBlock` 分支里的「第 i 行」其实一直是「第 i 个 caret」。本插件把它当成「块选区各行」，
并进一步用「行号」当 key —— 只要**两个 caret 落在同一行**，key 立刻塌成一个。

**② 行尾按 → 会把光标移到下一行行首（本机 virtual space 关闭）**

- `EditorSettingsExternalizable` 的默认值：`IS_VIRTUAL_SPACE = false`
  （261 的 `EditorSettingsExternalizable$OptionSet` 构造器字节码 `iconst_0; putfield IS_VIRTUAL_SPACE`）。
- 本机 `%APPDATA%\JetBrains\IntelliJIdea2026.1\options\editor.xml` 里**没有** `IS_VIRTUAL_SPACE` 项
  → 用户用的是默认值 **false**（虚拟空间关闭）。
- `CaretImpl.moveCaretRelatively`（平台源码，261 同构）：

```java
if (!editorSettings.isVirtualSpace() && lineShift == 0 && columnShift == 1) {
    if (newColumnNumber > getLastVisualLineColumnNumber(editor, newLineNumber) &&
        newLineNumber < lastLine) {
        newColumnNumber = 0;
        newLineNumber++;          // ← 行尾按右键：跳到下一行行首
    }
}
```

- 方向键 handler 是 `EditorActionHandler.ForEachCaret`（`MoveCaretLeftOrRightHandler`），
  对**每个 caret** 各执行一次 → 多光标时每个到达行尾的光标都会独立跳到下一行行首，且**不与下一行原光标合并**
  （`mergeOverlappingCaretsAndSelections` 只在选区/位置重叠时合并，列 0 与列 N 不重叠）。

### 1.3 复现步骤与现场数字（用户文件）

```
0: public class Map {                                  // 19 列
1:     private int x,y;                                // 22 列
2:     private int width;                              // 24 列
3:     private int height;                             // 25 列
4:     //游戏地图块的容器                              // 17 列  ← 最短
5:     private List<MapTile> tiles = new ArrayList<>(); // ~54 列 ← 最长
6: }
```

- 第 4 行（中文注释行）是参与列选的最短行 → 它在第 **17** 次右移时先到达行尾；
- 下一次右键，它的 caret 跳到**第 5 行（List 行）行首**（平台行为 ②）；
- 此时第 5 行上有 **两个 caret**（列 0 与列 ~17），`getBlockSelectionStarts()` 返回两个落在第 5 行的 offset；
- 生产 key = `Integer(-1000000 - 5)` **重复**：
  - 槽位 4 与槽位 5 取到**同一个 `SpringTrail`** → 第 4 行没有自己的 trail → **现象 A（该行光标消失）**；
  - 这个共享 trail 每帧被两个槽位先后 `setTarget`（列 0 ↔ 列 N）→ `maxOffset` 永远不收敛、
    `glowStates.lastMoveNanos` 每帧被刷新 → **现象 B（停住 + 辉光常亮）**；
- 按左键：列 0 的 caret 回到第 4 行行尾 → 两行各自一个 caret → key 恢复唯一 → **现象 A 消失（"它又出现"）**；
- 现象 C：同一个「一条 trail 被两个槽位共享」的机制在光标合并/孤立后留下共享的 `lastMoveNanos`/组亮度，
  表现就是「孤立那一个跟着另一个一起亮」。

这套推导同时解释了「为什么恰好是中文注释行先消失、为什么在 List 行附近卡住」——
两行正是「最短行」与「它下面那一行」。

---

## 二、上游 vscode-neovide 对照（`LengineerC/vscode-neovide-cursor`，517 行）

| 维度 | 上游 | 本插件 | 差异 / 结论 |
|---|---|---|---|
| 多光标 trail 的 key | `cursorId = target.dataset.cursorId`，随机生成后写回 **DOM 光标元素**；`Map<cursorId, {instance,target,...}>` | 多光标用**行号** `Integer(-1000000-lineNo)` | 上游 key 的基数 = **DOM 元素身份**，同一行多个光标（多个 `.cursor` 元素）天然两个 key；我们的行号 key 会塌。**这是本次根因** |
| 一行会不会算出两个 key | 会，且必须会：每个 `.cursor` 元素一个 key | 不会，同一行只会有一个 key | 我们的设计前提是「一行一个光标」，平台已不保证 |
| 销毁时机 | 每 500ms `scanCursors()` 重新查询 `.cursor` 元素；不在当前集合或 `!target.isConnected` 的条目**从 Map 删除**；绘制循环只遍历 Map | 每帧慢路径末尾按 `lastSeenFrame != frameSeq` 删除 | 上游用「元素身份 + 存在性」，我们用「帧号标记」，两者都正确；**但 key 塌时清理救不了**（一个 key 对应两个槽位，删了会两个一起没） |
| 销毁前是否「归位」 | **没有**：元素消失即删除实例，不做 snapTo | 也没有（直接 removeIf） | 一致，不需要补归位 |
| 辉光强度 | `context.shadowColor/shadowBlur` 在每次 `drawCursorShape` 时逐实例绘制；**没有强度曲线、没有共享状态、静止时也一直画** | 逐 trail 的 `maxOffset` 曲线 + 同组共享显示亮度 | 上游天然逐光标隔离；我们的「邻行同组取 max」是**用户明确要的**（避免相邻行光晕互相打架），不是 bug。但共享 trail 会让一个槽位的移动点亮另一个槽位 —— 修掉 key 塌陷后自然消失 |

> 上游同样没有「同一行两个光标」的专门处理——因为它用 DOM 身份，根本不需要。
> 结论：我们不是「上游也这样」，而是**我们的 key 规则比上游弱**。

---

## 三、三个假设与验证方法

| # | 假设 | 验证方法 | 结论 |
|---|---|---|---|
| H1 | 行号 key 在「两个 caret 同行」时塌陷，导致 trail 被共享 | 探针 T-4：模拟 6 个 caret 列选右移 20 帧，断言 `trails.size() == carets.size()` 且各槽位 trail 两两不同 | **成立**（修复前红，修复后绿） |
| H2 | 共享 trail 每帧被两个目标交替写入 → `maxOffset` 不收敛、`lastMoveNanos` 被刷新 → 卡住 + 辉光常亮 | 探针 T-2：注释行 caret 回绕到 List 行、List caret 继续移动 40 帧，断言**静止的那个槽位**的 `maxOffset < 0.5` | **成立**（修复前红） |
| H3 | 辉光被组内共享后，静止光标跟着移动光标亮（现象 C） | 探针 T-3：非相邻两行，一行持续移动、一行静止，断言静止行的 quadGlow == 0 | 组内共享是**设计**（相邻行同组），非相邻必须隔离；修复前该场景本就通过 → T-3 作为**回归守门**（变异测试证明能判红） |

**排除项**：`refreshTarget` 异常（idea.log 中 `refreshTarget 抛异常` 计数 = 0）、
调度器活锁（`framePending` 在 `runTick` 开头复位，且 0.9.88 已回滚 invokeLater 失败）、
D0 字段改动（本次不碰 `core/NeovideConfig.java`）。

---

## 四、修复方案（先红后绿）

### 4.1 根因行

`src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java:2246`

```java
key = resolveBlockKey(lineNo, blockStarts[i], prev);
```

以及其唯一实现 `blockTrailKey()`（`:430`）：**只用行号**做 key。

### 4.2 改法（最小且彻底）

1. **多光标槽位的 key 改成 caret 对象本身**：
   - 平台契约 ① 保证 `blockStarts.length == carets.size()` 且顺序一致（都来自 `getAllCarets()`），
     所以 `carets.get(i)` 就是槽位 i 的光标；
   - `Caret` 没有重写 `equals/hashCode`（261 `CaretImpl` 字节码无这两个方法）→ `HashMap` 天然按引用判等；
   - caret 对象跨帧稳定（中文编辑 offset 变化不影响对象身份），此前「行号 key」要解决的问题一并覆盖。
2. **目标点也改成槽位自己的 caret**：`cvp = caret.getVisualPosition()`、`caretOff = caret.getOffset()`。
   - 这去掉 `useEnd`（按主光标方向猜整组用 starts 还是 ends）的跨行误用；
   - 向左扩选/向右扩选都对准**各自 caret 的活动端**。
3. **保留行号 key 作为旧平台兜底**：仅当 `i >= carets.size()`（理论上「1 个 caret + N 行 starts」的旧模型）时使用
   `resolveBlockKey`；同时清掉该槽位的 `prevBlockKeys`，避免残留。
4. 修正 `useBlock` / `blockTrailKey` / `trailSlotCount` 的注释：写清「本平台是 per-caret」这一契约，
   避免下一个维护者再按「每行一项」写代码。
5. 不改 `GlowIntensity`、不改同组显示亮度（那是用户要的），不改单光标路径、不改 D0。

### 4.3 预期行为变化（必须在报告里写清）

- 行尾按 →：caret 会按平台语义跳到下一行行首；**修复后它会以光标的形式出现在下一行（并继续有拖尾），
  不再"凭空消失"**。第 4 行在 caret 离开后本来就没有光标（平台行为），不是插件丢画。
- 同一行出现两个光标时，会出现**两条独立拖尾**；这是平台真实状态。
- 单光标路径代码不变。

---

## 五、探针（TDD：先红后绿 + 变异测试）

文件：`tools/settings-probe/VMultiCursorFix.java`，登记进 `build-v-probes.ps1` 的 `$mains`。

用**可变假 Editor**驱动真实 `CaretAnimator.refreshTarget` / `tickSpring`（同 `VInlay` 的做法），
假平台严格照 1.2 的两条契约实现：

- `getBlockSelectionStarts/Ends` = 每个 caret 的 offset（per-caret，不是 per-line）；
- 右移时行尾回绕到下一行行首（virtual space=false）；同位置 caret 合并（平台 merge）。

| 探针 | 断言 | 修复前 |
|---|---|---|
| T-1 | 同一 caret 在左右各 20 帧内的 trail 实例不变（key 稳定，AC-004 对称） | **红**（回绕时 key 从行号 4 变成行号 5） |
| T-2 | 停手 60 帧后每条 trail 的 `maxOffset < 0.5` | **绿**（见下方「T-2 的实测修正」） |
| T-2b | 停手 60 帧后所有四边形 `quadGlow == 0`（辉光必须熄灭） | **红**（共享 trail 每帧 `setTarget` 两次且都返回 true → `lastMoveNanos` 被刷新 → 保持期永不过期） |
| T-3 | 非相邻行：移动行 quadGlow > 0，静止行 quadGlow == 0 | 绿（回归守门，变异测试证明能判红） |
| T-4 | 每帧 `trails.size() == carets.size()`、`activeTrails` 无重复对象、`quadCount == carets.size()` | **红**（两个槽位共用一条 trail） |
| T-5 | 单光标 10 帧：1 条 trail、1 个 quad、目标跟随（防回归） | 绿 |

**T-2 的实测修正（实测数据推翻了我的第一版设计）**：第一版 T-2 断言「停手后 maxOffset 收敛」，
在**修复前也是绿的**。原因：两个槽位的两次 `setTarget` 都发生在 `tickSpring` 的物理推进**之前**，
`SpringTrail.update` 用的始终是最后一次写入的目标，所以**形状会收敛，辉光不会熄**。
真正的失败观测量是 `glowStates.lastMoveNanos` 每帧被刷新 → 保持期永不结束。
因此保留任务书要求的 T-2（maxOffset），并补 T-2b（quadGlow）作为现象 B 的直接守门。

变异测试（每条都要能判红）：

- M-T1：把 `key = slotCaret` 改回行号 key → **T-1 / T-2b / T-4 红**（实测）；
- M-T3：把 `groupBrightness[groupId]` 改成全局最大 → **T-3 红**（实测 `stillGlow=1.000`）；
- M-T5：`trailSlotCount` 非多光标分支少 1 → **T-5 红**（实测 `trail=false quad=false`）；
- T-2（maxOffset）在 M-T1 下仍绿 —— 已在上面说明原因，它不是现象 B 的有效判据。

### 五-B、审查后调整（2026-11-02，code-reviewer + java-reviewer）

| 审查项 | 调整 |
|---|---|
| M-1 多光标判据依赖 SelectionModel 是否成功 | `count = max(caret 数, starts 数)`；`multiCaret = count > 1`。SelectionModel 抛异常时 N 个光标仍按多光标处理 |
| M-2 目标点改动零探针覆盖 | 新增 S5 / T-6：两个方向相反的带选区 caret，各自对准自己的 lead；M-T6 变异（改回 selectionEnd）实测红 |
| M-3 探针未真正排空调度线程 | `detachFromScheduler()`：unregister + `thread.join` + `SwingUtilities.invokeAndWait` 排空 EDT（同 VReadAction） |
| M-4 >64 光标时 cameraTrail 失准 | 清理后若 cameraTrail 为空或不在 activeTrails，兜底取第一条 |
| 不可达分支 / 死变量 | 删除 `slotCaret == null && !multiCaret` 的不可达分支；删除无读者的 `targetW/H/Cx/Cy` |
| 注释与行为相反 | startFrom、`updateBlockCharsMoved`、`prevBlockKeys` 的「块选区」措辞改为「多光标槽位」，并保留历史说明 |
| 文档行数过期 | 重新实测：生产 21,412 行 / 37 文件；探针 34,440 行 / 132 文件 |

## 六、验收映射

| AC | 本次如何覆盖 |
|---|---|
| AC-001 | 修复后每一行的 caret 都始终有光标；caret 被平台回绕到下一行时，光标**跟着出现在下一行**（不再是消失）。「行内容已到行尾、caret 离开该行」属于平台语义，探针 T-1/T-4 保证不丢画 |
| AC-002 | T-2：静止槽位 maxOffset 收敛 |
| AC-003 | T-1：key 稳定 |
| AC-004 | 探针同时覆盖左移（回绕回上一行行尾） |
| AC-005 | T-3：非相邻隔离；共享 key 修掉后不再串亮 |
| AC-006 | 与预设无关（探针用固定 config，纯几何/身份判据） |
| AC-007 | T-5 单光标回归 |
| AC-008/009/010 | 全量门禁 + D0 + 4 组变异测试 |
