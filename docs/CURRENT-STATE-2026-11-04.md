# 当前真相页（2026-11-04）

> 这份文件的唯一作用：**让新会话和用户在两分钟内知道「现在是什么状态」**，
> 不再去翻一堆互相矛盾的修复报告。
> 数字全部来自 2026-11-04 本轮实测，命令见 §五。
>
> 它不替代 `docs/BUG-LOG.md`（记录「发生过什么」），也不替代
> `docs/WORK-DISCIPLINE.md`（记录「怎么干活」）。

---

## 一、两分钟看懂（大白话）

1. **装的是 0.9.131。** 仓库当前分支上的构建是 0.9.133，但它和 0.9.131
   **运行时行为完全一样**（源码只差注释，class 只差行号表）。
   所以**不要回退、不要重装**，0.9.132 / 0.9.133 只是中间版，不是新基线。
2. **当前预设是「基础测试2」，`trailSize = 1.0`。** `1.0` 既是当前值也是期望值
   —— 密度最高，调低会更难看。旧文档里写的 `trailSize = 0.2` 属于另一个预设
   「纯平滑输入 副本」，是过期记录，**不是被安装脚本重置**。
3. **A（补全弹窗闪烁）还没修。** 2026-11-04 用户决定**暂缓**，理由是「很难告」
   （不是不复现，是排查成本高）。要修就按 `PROMPT-A-POPUP-FLICKER-2026-11-04.md`。
4. **B（同行 `charsMoved ≥ 4` 误判）不是 bug。** 它是**未复现的代码疑点**：
   用户表示「连打时有时无拖尾」从未感受到。Step B 已实施后当天撤回。
5. **C（撤回 55ms 跨行前缘下限）保留，已验收。** 真跨行前缘回到 **0 ms**，
   跨行密度最高，这是缎带形状的来源，不是缺陷。
6. **D（删除延迟）不做**，它是另一条线（`alwaysHighFps=false` 的空闲/自适应档）；
   **E（上游跨行模型实验）不做**，因为 B 已撤回，前提不成立。
7. **选词斜拖尾是正常形状**：`trailSize = 1.0` 时跨行 `leading = 0`，
   前缘 0 ms、后缘 100/280 ms。0.9.131 一样。**不是配置重置，不是 bug。**
8. **下一步只做「跨行密度」实验**：看能否改善跨行斜拖尾的观感。
   A/B/C/D/E 的代码都不动。选区分类、Tab 缩进、预设更新只是附加，等主目标完成再排。

---

## 二、当前真相（表格）

| 项 | 事实（2026-11-04 实测） |
|---|---|
| 已装 jar | **0.9.131**（读 jar 内 `META-INF/plugin.xml`；文件名恒为 `0.9.8.jar`） |
| 文档/验收基线 | **0.9.131** |
| 当前构建 | 0.9.133，行为 = 0.9.131；0.9.132/0.9.133 是中间版，**不回退/不重装** |
| 分支 / HEAD | `fix/bug-016-completion-flicker` / `fddd529`（Step B 已撤回） |
| 远程 | `git rev-list --left-right --count fork/main...HEAD` → **0 7**（未 push） |
| 当前预设 | 面板选中「基础测试2」，`trailSize = 1.0` |
| `trailSize = 0.2` | 属于另一个预设「纯平滑输入 副本」；**过期记录**，不是当前值 |
| 探针 | 118 个 `V*.java` / 133 个 `*.java` / **115 个注册探针** |
| 门禁 | 上次记录 `ALL PASS`；**本轮是文档阶段，没有重跑**（见 §六） |
| D0 | `core/NeovideConfig.java` **零 diff**（最近一次记录） |
| 工作区 | 有文档修改与未跟踪文档；**不要 reset / checkout / clean** |

### A / B / C / D / E 状态

| 项 | 状态 | 一句话 |
|---|---|---|
| **A** 补全弹窗辉光闪烁 / 短跳变短 | **暂缓，不修** | 2026-11-04 用户决定「很难告」，先做跨行密度 |
| **B** 同行 `charsMoved ≥ 4` 误判跨行 | **未复现的代码疑点，不是 bug** | Step B（`5de3b37`）已撤回（`fddd529`），不再修 |
| **C** 撤回 55ms 跨行前缘下限 | **保留，已验收** | 真跨行前缘 0 ms；三个预设密度恢复 |
| **D** 删除延迟 / 常驻刷新 | **不做** | 另一条线，目标路径 `alwaysHighFps=false` |
| **E** 上游跨行模型实验 | **不做** | B 已撤回，前提不成立 |
| 选词斜拖尾 | **正常形状** | `trailSize=1.0` 下跨行 `leading=0` 的固有形状 |

---

## 三、为什么这些结论要写在这里

2026-11-03 到 11-04 之间，同一个 BUG-016 产生了 **六份修复/决策报告**，
其中至少三处互相矛盾（已装版本、`trailSize`、B 是不是 bug）。
如果只看其中一份，很容易得出相反结论 —— 例如：

- `PROMPT-A-POPUP-FLICKER` 写「基线 v0.9.127、已装 0.9.127」；
- `DECISION-REPORT-2026-11-04-STEP-B` 写「已装 0.9.133」；
- `STEP-C-FIX-REPORT` 写「当前 `trailSize=0.2`」；
- `PROMPT-COMMENT-AUDIT` 写「C 对 `trailSize=0.2` 无收益，链停止」。

这些说法都已在各自文件里追加了「2026-11-04 更正」，但**原话保留**
（历史不能被抹掉）。要判断现状，只信本文件 + 命令输出。

---

## 四、下一步（停止线）

**只做一件事：跨行密度实验。**
看能否在不破坏 C 的前提下，改善「跨行斜拖尾」的观感。
它需要新提示词，不在 2026-11-04 的文档/注释任务里。

**明确不做**：A（暂缓）、B（已撤回）、C（保持）、D/E（不做）、
选区分类、Tab 缩进统一、预设数值更新。

---

## 五、这些数字怎么来的（可复现）

```bash
git branch --show-current
git log -3 --oneline
git rev-list --left-right --count fork/main...HEAD

unzip -p "$APPDATA/JetBrains/IntelliJIdea2026.1/plugins/neovide-cursor-pawamamama/lib/jetbrains-neovide-cursor-0.9.8.jar" \
  META-INF/plugin.xml | grep -o "<version>[^<]*"

ls tools/settings-probe/V*.java | wc -l        # 118
ls tools/settings-probe/*.java | wc -l         # 133
awk '/\$mains *= *@\(/{f=1} f{print} f&&/^\)/{exit}' tools/settings-probe/build-v-probes.ps1 \
  | grep -o "'V[A-Za-z0-9]*'" | tr -d "'" | sort -u | wc -l   # 115
```

2026-11-04 实测输出：

```
分支    fix/bug-016-completion-flicker
HEAD    fddd529 revert(trail): 撤回 Step B 同行 burst 分支（无可感知收益）
远程    0 7（fork/main 领先 0，本地领先 7，未 push）
已装    <version>0.9.131
探针    118 / 133 / 115
```

---

## 六、没做 / 没验证的部分（如实记录）

1. **本轮没有跑全量门禁，也没有跑 D0。** 这是文档阶段，按任务书要求不跑；
   所以「ALL PASS」「D0 零 diff」是**上一次的记录**，不是本轮实测。
2. **没有安装 jar、没有跑 `install.ps1`、没有启动 IDEA、没有 reset。**
3. **A 没有修**，也没有新的取证；「暂缓」是用户的决定，不是结论。
4. **B 的「未复现」来自用户口述**，没有日志能证明它从未发生；
   撤回的理由是「收益不可感知、副作用可见」，不是「已证明不存在」。
5. **`trailSize = 1.0` 是用户确认的期望值**，但本轮没有去读用户配置 XML 复核
   —— 如果面板显示不同，以面板为准，并回来更新本页。

---

## 七、相关文档怎么读

| 文档 | 它是什么 | 读它的注意事项 |
|---|---|---|
| `docs/BUG-LOG.md` §BUG-016 | 现象、证据、每一步实施/撤回的原始记录 | 按时间顺序读；以文末「2026-11-04 结论汇总」为准 |
| `docs/archive/handoffs/HANDOFF-2026-11-03-BUG-016.md` | 2026-11-03 的下一步修法（方案 A/B） | 方案 A 已实施并撤回；看文末「2026-11-04 更正」 |
| `docs/archive/reports/STEP-C-FIX-REPORT-2026-11-04.md` | Step C 的交付记录 | C 保留、已验收；`trailSize=0.2` 是误读 |
| `docs/archive/reports/STEP-B-FIX-REPORT-2026-11-04.md` | Step B 的交付记录 | **已撤回，不作为基线**，只作历史 |
| `docs/archive/reports/STEP-B-REVERT-REPORT-2026-11-04.md` | Step B 撤回记录 | 0.9.133 行为 = 0.9.131 |
| `docs/archive/decisions/DECISION-REPORT-2026-11-03.md` | 11-03 全天素材 | 已装版本、B/C 状态看文末更正 |
| `docs/archive/decisions/DECISION-REPORT-2026-11-04-STEP-B.md` | Step B 撤回后的决策材料 | 「已装 0.9.133」已更正为实测 0.9.131 |
| `docs/archive/prompts/PROMPT-A-POPUP-FLICKER-2026-11-04.md` | A 的施工提示词（暂缓） | 基线已更正为 0.9.131 |
| `docs/archive/prompts/PROMPT-STEP-B-2026-11-04.md` | Step B 施工提示词 | **已作废** |
| `docs/archive/prompts/PROMPT-STEP-C-2026-11-03.md` | Step C 施工提示词 | **已验收、保留** |
| `docs/archive/prompts/PROMPT-BUG-016-2026-11-03.md` | BUG-016 总提示词 | 只保留 A 的取证方法；B 停止、E 不做 |
