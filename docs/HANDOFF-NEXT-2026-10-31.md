# 进度快照与下一步（2026-10-31）

> 用途：会话压缩或换新会话时，读完这一份就知道「现在在哪、还剩什么」。
> **纪律不在这里** —— 它在 `CLAUDE.md`、`docs/WORK-DISCIPLINE.md`、
> `C:\Users\wangw\.claude\WORKFLOW-ALIGNMENT.md`。这里只记状态与待办。

---

## 一、当前状态（实测 · 2026-10-31 20:10）

```
HEAD      923170f（main，已推送 fork，同步 0 0）—— 本轮两件事的改动**尚未提交**
已装      IDEA 2026.1 = v0.9.113（jar 内自检；文件名恒为 0.9.8.jar）
门禁      107 条目 / 1195 PASS / 2 FAIL（仅 VRegr2 informational）/ ALL PASS
D0        core/NeovideConfig.java 零 diff
工作区    11 个已改文件 + 5 个未跟踪（含本轮两份新探针、plans/validation-sink.md、
          本文件、build/night-run-report.md）
决策入口  docs/REPORT-U-M-7-AND-VALIDATION-SINK-2026-10-31.md（一页结论 + 待决策项 D-1..D-5）
```

> **注意**：磁盘上装的是 v0.9.113，而 git 里最新的提交还是 `923170f`。
> 提交只是把「已经装上去的事实」补进 git，不再产生行为变化（见报告 §七 D-1）。

---

## 二、六步流程：到第 6 步

| 步 | 内容 | 状态 |
|---|---|---|
| 1 | 面板重构（P11） | ✅ `a1efc13` |
| 2 | 面板审查（第 3 批） | ✅ `b5d2270` |
| 3 | 修复可见 bug（1C + 3H + 2M） | ✅ `434fbdb` |
| 4 | 最终全量测试 | ✅ 门禁 1124 PASS |
| 5 | 双端安装测试 | ✅ 用户确认「没问题」 |
| 6 | **修复小问题和待办** | ⬜ **下一步** |

---

## 三、第 6 步的清单

### 3.1 第 3 批审查剩下的 MEDIUM（9 条）

来源：`docs/HANDOFF-BATCH3-AUDIT-2026-10-31.md` §三。
已修的两条是 P-M-2（去搜索）、P-M-10（复选框派生），其余：

| 编号 | 内容 | 备注 |
|---|---|---|
| P-M-1 | `apply()` 逐字段写活 State，无回滚 | 与 P-C-1 同族但更大 |
| P-M-3 | 颜色坏快照静默 | 与第 1 批 M-1 同源 |
| P-M-4 | 「检查更新」失败与没反应不可区分 | |
| P-M-5 | 预设数值快照不 clamp | 与第 1 批 H-1 同源 |
| P-M-6 | 颜色快照不校验 HEX | 与第 1 批 M-1 同源 |
| P-M-7 | 3784 行内聚性（报告附三步切分方案） | 大工程，单独排 |
| P-M-8 | 预览 rank 算法复制品 | 违反陷阱 3，优先 |
| P-M-9 | 抑制标志不保存前值（已确认无死锁） | 潜伏 |
| P-M-11 | dispose 未释放三个预览（补 8 个 null） | **最便宜，建议第一个做** |

#### 3.1.1 已做：U-1 / U-2 / U-3 —— 把校验下沉到唯一一层（2026-10-31）

P-M-3 / P-M-5 / P-M-6 在上一轮只修了**设置面板**这一个入口，
`ui/CursorPresetApplier`（Tools 菜单）那条路原样保留同一缺陷，当时登记为
`plans/batch3-fix2.md` §七 的 U-1 / U-2；U-3 是颜色合法性两套口径。

| 编号 | 内容 | 状态 |
|---|---|---|
| U-1 | Tools 菜单应用自定义**动画**预设不 clamp（`glowLayers=99` 直接落盘，重启被改回 12） | ✅ 校验下沉到 `SettingsField.decode` |
| U-2 | Tools 菜单应用自定义**颜色**预设不校验 HEX（坏串写进 State → 光标变黑） | ✅ 下沉到 `SettingsField.restoreColorSnapshot` |
| U-3 | `sanitizeColor` 只收 7 位 → 9 位 `#RRGGBBAA` 应用后生效、重启被打回默认色 | ✅ 与 `isValidHexColor` 同口径（7/9 位） |

改法与证据（根因 / 区间表落点 / 变异测试 / 两条入口同源的实测）：
**`plans/validation-sink.md`**。守门探针 `VValidationSink`（**34 条**，2026-10-31 审查后定稿）。

#### 3.1.2 同批：U-M-7（光标串到其他行）—— 慢路径中途抛出后重算请求丢失

来源：`docs/CODE-AUDIT-2026-10-30.md` 的 U-M-7；用户 2026-10-31 报「写方法时候光标串到其他行，
昨天修复了今天又出来了」。

改法：`forceResync` / `inlayGeometryDirty` 不再在慢路径正文之前清零，改为**成功收尾才清零**
（`CaretAnimator.commitResync`），中途抛出时请求保持置位、下一帧重算。
守门探针 `VCatchRollback`（6 条）+ 变异验证（把清零挪回开头 → C-2/C-4 变红）。

完整证据与待决策项见 **`docs/REPORT-U-M-7-AND-VALIDATION-SINK-2026-10-31.md`**。
**两处刻意的行为变化**（已在该文档 §六 登记）：
① `slopeTicks` 越界值由「一律回落 0」改为「夹到最近端点」（999 → 100，与面板原有行为一致）；
② 9 位颜色不再在重启时被打回默认。

### 3.2 LOW（16 条）

以注释 / 死代码 / 命名为主，报告里每条都有行号，可批量处理。

### 3.3 第 2 批遗留的 3 条 MEDIUM

`U-M-6`（dispatchDue 无升级检测）、`U-M-7`（forceResync 提前清）、
`U-M-8`（块选区每帧重建 SpringTrail）—— 都在 `ui/`，不在面板范围。

### 3.4 待办与功能

| 项 | 状态 |
|---|---|
| **颜色工坊**（PROJECT-DECISIONS P10） | 流程第 6 步里做 |
| U-M-21 运行时禁用插件钩子 | 需要改 `plugin.xml`，用户定了「算待办」 |
| `slope < 0` 语义 | PROJECT-DECISIONS §7.2，等用户拍板 |
| 双拼强度做成参数条 | 同上 |
| 半边拖尾 `leadingSnap` | Day 2 未开始，产品决策已定（设备级） |
| BUG-014 预设派生丢 3 字段 | 低优先级，待查 |
| BUG-015 拉伸方向 | 待核实 |
| `git tag` 停在 v0.9.83 | 独立工作，补 tag 需逐个确认 |

---

## 四、做第 6 步时的硬约束

1. **D0 红线**：`core/NeovideConfig.java` 零 diff。
2. **不许为了让测试通过改断言**；契约变更必须写明「契约变更，非缺陷」。
3. **每条修复四段输出**：红 → 绿 → 变异红 → 恢复绿。
4. **门禁只跑一份**（四-G-2 并发假红），改完删 `build\v2-probe\classes` 全量重编译。
5. **观感变化的必须让用户实机看**，不许只贴门禁绿。

---

## 五、建议的施工顺序

```
P-M-11（8 个 null，最便宜）
  → P-M-8（预览 rank 复制品，违反陷阱 3）
  → P-M-5 / P-M-6 / P-M-3（三个「校验缺失」，与第 1 批同源，可一批做）
  → P-M-1（apply 无回滚，改法较大）
  → P-M-4 / P-M-9（小）
  → 16 条 LOW（批量）
  → P-M-7（3784 行内聚性，单独一轮）
  → 颜色工坊（功能）
```

---

## 六、相关文档索引

| 需要什么 | 看哪份 |
|---|---|
| 三批审查的全部发现 | `docs/CODE-AUDIT-2026-10-30.md`（第 1/2 批在前 770 行与第八节，第 3 批在第九节） |
| 面板重构的用户决策 | `docs/DECISIONS-PANEL-2026-10-31.md` |
| 第 3 批审查交接 | `docs/HANDOFF-BATCH3-AUDIT-2026-10-31.md` |
| 第 3 批修复报告 | `docs/BATCH3-FIX-REPORT-2026-10-31.md` |
| 第 2 批第 5 轮 | `docs/HANDOFF-BATCH2-ROUND5.md` |
| 工作流三层 | `C:\Users\wangw\.claude\WORKFLOW-ALIGNMENT.md` |
| 14 条纪律 | `docs/WORK-DISCIPLINE.md` |
