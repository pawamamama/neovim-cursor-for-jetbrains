# 代码审查 · 2026-10-30 · 第 1 批（core / settings）

> **用途**：本报告供决策会话直接使用。三条产品决策已由用户拍板（见第一节），
> 发现清单里涉及决策的条目已按决策改写「建议方向」。
>
> 本轮只新建本文件。生产代码、探针、其他文档、配置一个字节未动。

---

## 零、基线与方法

### 0.1 前置检查（code-review.md 的 Pre-Review Requirements）

```
=== git status --porcelain ===
?? build/night-run-report.md
?? docs/HANDOFF-DECISION-2026-10-30.md
=== git log --oneline -3 ===
36cbaa4 fix(install): 结构指纹漏计 *Global 工厂，把 27 个字段数成 25
f112bff docs: 新增 2026-10-30 交接文档
4005e0d docs: 第 37 行标签页名单补上第 7 页「辉光」
=== sync fork/main...HEAD ===
0	0
```

| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 已修改（M）文件 | 无 | 无 | ✅ |
| 未跟踪文件 | 2 个 | 恰好这 2 个 | ✅ |
| HEAD | `36cbaa4` | `36cbaa4` | ✅ |
| 与 fork/main 同步 | `0 0` | `0 0` | ✅ |

### 0.2 门禁基线

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1
```

连续跑两次，数字一致（排除四-G-2 的并发假红）。

| 指标 | 数值 | 说明 |
|---|---|---|
| 总条目 | **99** | 表格行数 |
| PASS | **1008** | 全部断言的 PASS 合计 |
| FAIL | **2** | 全部落在 `VRegr2`（R-5 / R-6） |
| 崩溃 / 非零退出 | **1** | `VRegr2 exit=1`（因其自身 2 条 FAIL 退出非零） |

末行 `V-PROBES RESULT: ALL PASS`。`VRegr2` 是 informational，已排除在失败门外
（`PROJECT-DECISIONS.md` §六）。本轮不报 R-5 / R-6。

### 0.3 范围

10 文件 / **4314 行**（`wc -l` 实测）：

| 文件 | 行数 | 备注 |
|---|---|---|
| `core/NeovideConfig.java` | 290 | D0 红线，只读 |
| `core/DampedSpring.java` | 101 | |
| `core/TrailCorner.java` | 450 | |
| `core/AfterimageTrail.java` | 174 | |
| `core/NeovideColors.java` | 69 | |
| `core/GlowIntensity.java` | 109 | |
| `settings/NeovideCursorSettings.java` | 1143 | 全项目最大之一 |
| `settings/SettingsField.java` | 770 | |
| `settings/CursorPresets.java` | 560 | |
| `settings/CustomPresets.java` | 648 | |

不在范围：`ui/` 全部、`ui/settings/` 全部、`tools/settings-probe/` 全部、`docs/` 全部、
`build/` 下的一次性脚本、git 历史。审查中允许看 `ui/` 的**调用点**（几行）。

### 0.4 工作流

本任务是**代理层任务**：4 个审查代理并行，按
`C:\Users\wangw\.claude\rules\common\agents.md` 的 Multi-Perspective Analysis 划分视角。
命令层 16 个均不适配「审既有代码」（`/code-review` 只审未提交改动、`/quality-gate` 不覆盖
Java、`/security-scan` 面向 Web 攻击面、`verification-loop` 是改完代码之后、`delivery-gate`
是提交前）。

判据与分级照 `C:\Users\wangw\.claude\rules\common\code-review.md`。

### 0.5 四个代理的视角与返回状态

| # | 视角 | 代理 | 状态 | 原始返回 |
|---|---|---|---|---|
| 4.1 | 通用质量（Senior engineer） | `code-reviewer` | ✅（首次 429 限流，重试 1 次成功） | 0C / 2H / 6M / 5L |
| 4.2 | 静默失败 | `silent-failure-hunter` | ✅ 首次成功 | 0C / 2H / 7M / 4L |
| 4.3 | 安全（桌面插件攻击面） | `security-reviewer` | ✅ 首次成功 | 0C / 0H / 1M / 3L |
| 4.4 | Java 语言层 + 并发 | `java-reviewer` | ✅（首次 429 限流，重试 1 次成功） | 0C / 2H / 5M / 8L |

两次 429 均为「商汤小浣熊：模型请求频率超限」，各重试一次后成功，**四个视角一个不缺**。

### 0.6 陷阱记录检索（四处）

#### ① `CLAUDE.md` 第五节「十四类陷阱」—— 命中

| 陷阱 | 与本轮的关系 |
|---|---|
| 陷阱 1「一个字段承担两个职责」 | `charsMoved` 双职责已拆（核实通过，见 4.2） |
| 陷阱 2「字段接通 ≠ 字段生效」六层链路 | `NeovideCursorSettings` + `SettingsField` 是六层的 ①②③④ |
| 陷阱 7「参数三份清单必须同源」 | 核实三份清单已统一为同一批 13 个字段（见 4.3） |
| 陷阱 8「设备/环境开关清单」 | `runtimeSwitch` 标志已存在，但恢复路径的对应分支**不可达**（M-6） |
| 陷阱 14「D0 门禁不能破」 | `core/NeovideConfig.java` 只读，只报不改（L-10） |

原文摘录（陷阱 7）：

> **加字段时必须同时想清楚这三件事**（进不进快照 / 内置预设写不写 / 匹配比不比），
> 守门探针 `VFieldDomain` 会当场抓出不齐的那一条。

#### ② `docs/WORK-DISCIPLINE.md` —— 命中

| 纪律 | 与本轮的关系 |
|---|---|
| 四-B 三层拷贝 | 核对 State 全部 33 字段在 `loadState` 与 `copyFrom` 中无遗漏（见 4.1） |
| 四-D 一字段两职责 | `charsMoved` 已拆 |
| 四-E 字段接通 ≠ 生效 | `restore()` 零生产调用、探针在测死方法（M-6） |
| 四-G 探针假通过 | 同上，属「假通过①」变种 |
| 四-I 不确定就问 | 三条产品决策已交用户拍板（第一节） |
| 四-J 看可维护性 | `NeovideCursorSettings` 的拆分边界（M-9） |

原文摘录（四-G）：

> 1. **探针必须读「绘制/调度真正用的那份数据」**，不是旁边的影子副本
> 2. **探针必须调用生产方法**，不许自己实现一遍算法再验证自己的实现
> 3. 判断方法：把这个字段/方法改坏，探针**必须红**。不红就是假通过

#### ③ `LESSONS.md` / `docs/NEXT-STEPS.md` —— 命中

| 位置 | 内容 | 与本轮的关系 |
|---|---|---|
| `LESSONS.md` 坑 21 | 修 A 引入 B 是共享可变状态的必然产物 | `DampedSpring` / `TrailCorner` 的可变状态（M-4） |
| `LESSONS.md` 坑 22 | 单类 1582 行 / 54 字段 = God Class | `NeovideCursorSettings` 1143 行 → M-9 |
| `NEXT-STEPS.md` §7.1 | `slopeTicks` / `stretchRatioK` 的域外变化 | 本轮核实 `slopeTicks` 回落目标已与默认值一致 |
| `NEXT-STEPS.md` §四-B | 「左半边」实现要点（`TrailCorner.setLeadingSnap()`） | 决策 2 的排期目标 |

#### ④ `C:\Users\wangw\.claude\WORKFLOW-ALIGNMENT.md` 第五节 —— **四处均未命中**

第五节 5 条（Claude Desktop configLibrary、`inferenceModels`、Desktop 4.29 前端结构、
`git push` 走 SSH、`settings.json` 被 cc-switch 重写）全是工具链/环境坑，
与本轮 10 个插件源码文件无关。

### 0.7 已定决策与本轮发现的两处文档过期

**已定决策（`docs/PROJECT-DECISIONS.md`）**：

- §六：VRegr2 的 R-5 / R-6 暂时不修
- §六：不修平台 252 冻结（已修已验）
- §六：不对「彩虹（动态）」预设加配置迁移代码（纪律四-K）

**本轮发现两处文档过期（决策会话动手前请先更正）**：

| 文档 | 过期内容 | 实际状态（本轮核实） |
|---|---|---|
| `PROJECT-DECISIONS.md` §七、§8.2、§五 P9-A1 | 「等面板分标签重构时一并处理」 | **标签栏改造已完成**：`NeovideCursorConfigurable.java:1022-1050` 已是 `JBTabbedPane` + 7 个 `addTab` + 7 个 `registerIntegratedPanel`（提交 `373a666` 分 6 页、`96a8f70` 加辉光页）。§七 那 4 项跟进（`shadowColor` 改名、彩虹下 `tailColor` 置灰、三条填充路径标注、BUG-010 颜色侧派生）的**前提已消失，现在是可做的** |
| `CLAUDE.md` §十 | 「NeovideCursorConfigurable 3110 行」 | 实测 **3695 行** |

---

## 一、本轮已拍板的产品决策（2026-10-30）

> 三条决策由用户当场拍板。下面每条都给出**落地改动清单**，决策会话可直接照做。
> 这三条会改变 M-1 / M-2 / M-11 三条发现的处理方式。

### 决策 1：9 位 HEX 颜色按 **`#RRGGBBAA`（RGBA）** 解释

**依据**：跟 `NeovideColors.parse` 现在真正在用的语义走（它从原 JS 版 `cursorHexToRgba` 移植，
javadoc 第一句就写明），不为一个低频路径去改每帧执行的绘制热路径。

**语义确认**：

```
#FFC0CB      → 粉色，不透明
#FFC0CB80    → 粉色 + 50% 透明（RGBA：末两位是 alpha）
#80FFC0CB    → RGB(0x80,0xFF,0xC0) 青绿色 + 80% 不透明  ← 这是 RGBA 的必然结果，不是 bug
```

**注意一个既有语义**：`NeovideColors.java:53` 是 `alpha = (a / 255f) * clamp01(opacity)`，
即**颜色自带的 alpha 会与 `tailOpacity` 相乘**。`#FFC0CB80` + `tailOpacity=0.5` → 实际 25% 不透明。
本次决策**接受**这个相乘语义，不做「有 alpha 就覆盖 opacity」的改动。

**落地改动（3 处）**：

| # | 文件:行 | 改什么 | 不改会怎样 |
|---|---|---|---|
| 1 | `settings/SettingsField.java:303-306` | javadoc 从 `#AARRGGBB` 改成 `#RRGGBBAA`，并写明「末两位是 alpha，与 `tailOpacity` 相乘」 | 注释继续撒谎，下一个人按 ARGB 手改 XML 会得到青绿色 |
| 2 | `settings/NeovideCursorSettings.java:758-772`（`sanitizeColor`） | 从「只认 7 位」放宽到「7 位或 9 位」 | **9 位颜色活不过一次重启**，被打回默认粉 |
| 3 | `core/NeovideColors.java:29-51` | **不改**。它本来就是 RGBA，是「实际正在用的」那一份 | — |

**校验逻辑不用动**：`SettingsField.isValidHexColor`（308-329）本来就接受 7 位或 9 位，
只是注释写错了方向。

**探针影响**：改 `sanitizeColor` 的行为会让「9 位颜色被拒」相关的断言失效。
动手前先 `grep -rn "sanitizeColor\|#AARRGGBB" tools/settings-probe/` 确认有没有探针锁了这个行为；
若有，那是**契约变更**，按 §六 的规则改断言方向并在旁边注明，不是修生产代码迁就断言。

### 决策 2：`slope < 0` 的语义**排期到「半边拖尾（`leadingSnap`）」那一批**一起处理

**现状（本轮核实）**：

- `-1` 这个值今天**进不来**：`loadState` 把越界回落成 0，setter 只收 0..100，`State.slopeTicks` 默认 0
- 实现是 `clamp(slope, 0f, 100f)` → 负数夹成 0（纯矩形）
- 三处注释（`NeovideCursorSettings.java:544`、`:430-431`、`:888`）写着「<0 = 用 k 推导」，与实现相反

**为什么等**：`leadingSnap` 那一批本来就要改 `deriveTrailFactors` 的入参语义，
一次改完不用碰两遍。**在此之前代码与注释都不动**。

**要记在案的两件事**：

1. 这是一条**已知误导**：下一个读代码的人会按注释办事。做 `leadingSnap` 时必须把三处注释一起清掉。
2. `docs/PROJECT-DECISIONS.md` §8.1 那条「卡在用户手上」可以标注为「已排期到 leadingSnap 批次」。

**探针影响**：无（不改代码）。

### 决策 3：预设名**不支持 emoji / 任何非 BMP 字符**

**这条把 M-2 从「修 bug」翻转成「守契约」**——现在的拒绝行为是**预期行为**，不是缺陷。

**落地改动（4 处，全部是文档/日志/探针，不动 `isXmlLegal` 的判定逻辑）**：

| # | 文件:行 | 改什么 |
|---|---|---|
| 1 | `settings/CustomPresets.java:354`（`isValidName` 的 javadoc） | **删掉「emoji 都可以」的承诺**，改成「只支持 BMP 字符（`U+0020`–`U+D7FF`、`U+E000`–`U+FFFD`）；emoji 等非 BMP 字符不支持，2026-10-30 决定」 |
| 2 | `settings/CustomPresets.java:356-373`（`nameError`） | 文案从「名字里不能包含分隔控制符」改成能区分两种原因：含分隔符 / 含不支持的字符 |
| 3 | `settings/CustomPresets.java:300-304`（`format` 跳过非法名） | 日志从 `java.util.logging` 改走项目统一的 `LOG`，让「预设少了一条」在 `idea.log` 里查得到（同时解决 L-7） |
| 4 | `tools/settings-probe/` 新增一条断言 | 断言「emoji 名被拒」是**预期行为**，并在断言消息里写明「这是 2026-10-30 的产品决定，不是缺陷」——防止将来有人把它当 bug「修好」把决策推翻 |

**为什么第 4 条必须有**：目前项目里没有任何探针覆盖「合法 emoji 名」这个方向，
`VSepBound` 只测了「非法控制符必须被拒」。没有探针守，这个决策在三个月后就是一段「看起来像 bug 的代码」。

**探针影响**：新增断言属于**新增契约**，不是改现有断言。

---

## 二、结论

**WARNING**（按 `rules/common/code-review.md` 的 Approval Criteria）

- CRITICAL **0** 条 → 不 BLOCK
- HIGH **3** 条 → 不能 APPROVE，落到 WARNING（「仅 HIGH 问题，谨慎合并」）
- MEDIUM **11** 条 / LOW **10** 条

三条 HIGH 全部集中在**「同一条数据有两条合法性定义」**这一根因上，
没有一条涉及数据损坏、越权或不可恢复的丢失（不可恢复丢配置的那条在 M-3，触发条件窄）。

---

## 三、发现清单（按严重度排序）

### 去重说明

多视角命中同一根因的已合并，并标注「N 个视角同时发现」。
代理原始返回合计 44 条，去重后 **24 条**（3 HIGH / 11 MEDIUM / 10 LOW）。

---

## HIGH

### [H-1] 自定义预设恢复路径不校验取值域，与 `loadState` 对同一字段的合法性定义不一致

- **位置**：`settings/SettingsField.java:236-300`（`decode`），对照 `settings/NeovideCursorSettings.java:124-147`（`loadState` 的 clamp）
- **代码**：
    ```java
    // SettingsField.decode()
    case FLOAT: {
        float f = Float.parseFloat(raw.trim());
        if (Float.isNaN(f) || Float.isInfinite(f)) {
            return false;
        }
        v = f;
        break;
    }
    case INT: {
        v = Integer.parseInt(raw.trim());
        break;
    }
    ```
    对照 `NeovideCursorSettings.loadState()`：
    ```java
    target.glowLayers = clamp(from.glowLayers, 1, 12);
    target.shadowBlurFactor = clamp(from.shadowBlurFactor, 0.05f, 0.60f);
    target.animationLength = clamp(from.animationLength, ANIM_LENGTH_MIN, ANIM_LENGTH_MAX);
    ```
- **失败场景**：
    1. `customAnimPresets` 里出现 `glowLayers=999`（手改 XML，或旧版本写下的「当年合法、今天越界」的值）；
    2. 用户在下拉里选中该预设 → `restoreSkipping` → `decode` 只做类型校验 → `State.glowLayers = 999`；
    3. `syncStateToConfig()` 原样进 `CONFIG`，消费侧**也不 clamp** —— `CaretTrailPanel.drawQuadGlow` 按 `glowLayers` 循环 `fill(Path2D)`；
    4. 每帧每四边形 `fill` 999 次（`NeovideConfig.java:47-51` 自己记录 8 层已占 EDT 采样 9.3%）→ 界面卡死级掉帧；
    5. 重启后 `loadState` 把它 clamp 回 12 → 用户看到「我点的那个预设，重启后效果变了」。

    同源可注入值：`animationLength=0.5`（弹簧拖上百秒）、`shadowBlurFactor=1e9`、`glowOpacity=5`（`pow(1-peak,...)` 得 NaN → 辉光静默全灭）。
- **为什么是问题**：陷阱 2「字段接通 ≠ 字段生效」的镜像 —— **同一个字段有两条入口，一条设防一条不设防**。
    `loadState` 的注释明确写着「XML 可能是旧版本、也可能是用户手改过的」，`decode` 走的是同一种数据，却没有同等防护。
    消费侧不 clamp 意味着绘制热路径完全信任上游，而上游在这里被绕开了。
- **发现视角**：**4 个视角同时发现** —— code-reviewer（HIGH）、silent-failure-hunter（MEDIUM ×2）、security-reviewer（MEDIUM）、java-reviewer（HIGH）
- **上次审计是否报过**：**新发现**（0.9.87 的 P-7 / P-8 涉及 `applySlopeOverlay`，不是这条）
- **建议方向**：把「合法区间」收进字段表（`INT`/`FLOAT` 各带一个 clamp 算子），让 `decode` 与 `loadState` 共用同一份常量；绘制热路径的 `glowLayers` 再加一次兜底 clamp。
    **注意与决策 1 同批做**：`clamp(float)` 的 NaN 回落（M-5）也要一起改，否则「区间」和「NaN」会分裂成两份规则。

---

### [H-2] `restoreSkipping` 的返回值在三个调用点全部被丢弃，解析失败时上层仍宣称「已应用」

- **位置**：`settings/SettingsField.java:696-719`（`restore` 返回 `applied` 计数）、`ui/CursorPresetApplier.java:116-121`
- **代码**：
    ```java
    // CursorPresetApplier.java:116-121
    SettingsField.restoreSkipping(s, snap);
    s.animCustomName = name;
    svc.persistQuietly();
    NeovideCaretManager.applyConfig();
    LOG.info("neovide-cursor: 已应用自定义预设 " + name);
    return true;
    ```
- **失败场景**：某条预设快照里的 `tailOpacity` 被写成 `abc`（或整条快照被截断成 `garbage`）：
    `decode` 返回 `false` → 该字段**保持当前值**，不报错 →
    但 `applyCustomAnimPreset` 仍写 `animCustomName`、落盘、打 `LOG.info("已应用自定义预设 X")`、返回 `true` →
    面板因名字匹配显示该预设「已选中」。
    用户看到的是：下拉说切成功了、日志说成功了、实际一半参数没变。整条快照全坏（`applied == 0`）时表现完全相同。
- **为什么是问题**：纪律四-E / 四-G 的同一类病 —— **失败不可观测**。
    `decode` 的宽泛 `catch (RuntimeException) { return false; }`（`SettingsField.java:297-299`）是文档化的「宁干净不崩」策略，
    问题在于这条链路上**没有任何日志或计数出口**，而调用方连返回值都不看。
- **发现视角**：**2 个视角** —— silent-failure-hunter（HIGH）、java-reviewer（LOW，只报了 decode 零日志）
- **上次审计是否报过**：**新发现**
- **建议方向**：`decode` 对「已知键但值非法」记一行日志（与「未知键/旧版缺键」的正常宽容区分开）；`applied == 0` 时调用方不写名字、不打「已应用」。

---

### [H-3] 旧键迁移读失败时仍推进版本号并删除旧键 —— 一次瞬时异常即不可逆丢配置

- **位置**：`settings/NeovideCursorSettings.java:313-327`
- **代码**：
    ```java
            } catch (Throwable t) {
                // 读旧键失败：不阻塞启动，仍把版本号往前推（旧键按「不存在」处理）
                LOG.warn("neovide-cursor: 读取旧键失败，按「旧键不存在」处理", t);
            }
    ...
        state.stateVersion = STATE_VERSION_MIGRATED;
        PersistResult persisted = persistQuietly();
        switch (persisted) {
            case CONFIRMED:
                // 已确认落盘 → 唯一允许删旧键的分支
                deleteOldKeysIfPresent();
    ```
- **失败场景**：升级后首次启动、`noStateLoaded()` 路径（XML 不存在，state 全是构造初值）。
    若 `pc.isValueSet(...)` / `pc.getBoolean(...)` 抛异常（平台早期初始化、服务竞态）：
    catch 打一行 warn → 按「旧键不存在」→ 321 行**无条件**把 `stateVersion` 置 1 →
    `persistQuietly()` 在 EDT 上返回 CONFIRMED → `deleteOldKeysIfPresent()` 删键。
    用户的「常驻刷新 = 关」永久变成默认 `true`，此后分支 B 只删不读，**永远无法恢复**。
- **为什么是问题**：与同一方法 FAILED 分支的承诺直接矛盾 ——
    `FAILED` 分支（334-339）写着「真失败 → 回退版本号，保留旧键，下次启动重试。绝不半途而废」，
    而「读键失败」走的却是「推进版本 + 删键」。两者都是「没读到值」，处置却相反。
- **发现视角**：1 个视角（silent-failure-hunter，HIGH）
- **上次审计是否报过**：**新发现**
- **建议方向**：catch 分支显式标记「迁移未完成」，`stateVersion` 置回 0 并跳过删键（与 FAILED 同路），下次启动重试。
    **先看第五节 5.1 的待确认**——如果作者确认这是有意设计，则本条降为 MEDIUM 并改为「在注释里写明取舍」。

---

## MEDIUM

### [M-1] 颜色预设快照完全不校验 HEX，且「颜色合法性」存在三套互不相同的规则

- **位置**：`settings/CursorPresets.java:539-559`；对照 `settings/SettingsField.java:308-329`、`core/NeovideColors.java:29-51`、`settings/NeovideCursorSettings.java:758-772`
- **代码**：
    ```java
    // CursorPresets.parseColorSnapshot
    if ("tailColor".equals(k)) {
        tail = v;
    } else if ("shadowColor".equals(k)) {
        shadow = v;
    }
    return (tail == null || shadow == null) ? null : new String[]{tail, shadow};
    ```
- **失败场景**：
    - A：`customColorPresets` 写成 `tailColor=not-a-color;shadowColor=#FFFFFF` → 选中 → `s.tailColor = "not-a-color"` →
      `NeovideColors.parse` 静默返回 `Color.BLACK` → 光标变黑，无任何提示，且会被 `persistQuietly()` 落盘。
    - B：写成 `tailColor=#80FFC0CB` → `isValidHexColor` 接受 → 按决策 1 的 RGBA 语义读成青绿色（而非用户以为的粉色）→
      重启时若 `sanitizeColor` 未按决策 1 放宽，则被打回默认粉。
    - 三套规则：`isValidHexColor` 收 7/9 位、`sanitizeColor` 只收 7 位、`NeovideColors.parse` 收 3/6/8 位。
- **为什么是问题**：`SettingsField.decode` 的 HEX 分支（274-288 行）注释逐字记录了「非法 HEX 流进绘制层变黑」的修复动机，
    而颜色预设走的是**另一份**编解码实现，绕开了那次修复。纪律四-J 第 2 条「同一语义存在于两处」的现成样本。
- **发现视角**：**4 个视角同时发现** —— code-reviewer、silent-failure-hunter、security-reviewer、java-reviewer
- **上次审计是否报过**：**新发现**（0.9.87 P-3 是 `rgbGradient` 双职责，不同）
- **建议方向**：`parseColorSnapshot` 复用 `SettingsField.isValidHexColor`（非法返回 `null`，调用方已有「按无此预设处理」分支）。
    **与决策 1 同批做**：三处统一到 RGBA 口径（`sanitizeColor` 放宽到 9 位）。

### [M-2] 预设名里的 emoji / 非 BMP 字符被拒 —— **已拍板「不支持」，本条转为守契约**

- **位置**：`settings/CustomPresets.java:393-408`（`isXmlLegal`）、`:356-373`（`nameError`）、`:354`（javadoc）
- **代码**：
    ```java
        if (c >= 0xE000 && c <= 0xFFFD) {
            return true;
        }
        // 0xD800-0xDFFF 是代理区：孤立代理非法；成对代理由 String 层面处理
        return false;
    ```
- **失败场景**（改前）：用户把预设命名为「😀测试」→ 逐 **UTF-16 char** 判断，成对代理的两个单元都落在 `0xD800-0xDFFF` → 返回 `false` →
    `nameError` 返回「名字里不能包含分隔控制符」（与真实原因无关）→ 四个入口全部拒绝。
    注释说「成对代理由 String 层面处理」，代码里不存在这一层；`isValidName` 的 javadoc 明确承诺「emoji 都可以」。
- **为什么仍是问题**：**行为保留，但文档在撒谎**。决策 3 已定「不支持 emoji」，
    所以 `isXmlLegal` 的判定逻辑**不动**，要动的是三处承诺与文案，并加一条探针把这个决定固定下来。
    否则三个月后没人知道这是「有意」还是「写错了」，这正是本项目最难维护的那类代码。
- **发现视角**：**3 个视角** —— code-reviewer（HIGH）、java-reviewer（MEDIUM）、security-reviewer（LOW）
- **上次审计是否报过**：**新发现**
- **建议方向**：见第一节决策 3 的 4 步落地清单（删 javadoc 承诺 / 改文案 / 日志改走 `LOG` / 加探针断言「emoji 被拒是预期」）。

### [M-3] 非 EDT 线程逐字段写共享静态 `CONFIG`，无任何同步 —— 作者意图「立刻可见」，实现无内存序保证

- **位置**：`ui/NeovideCaretManager.java:49`、`:512-527`；`settings/NeovideCursorSettings.java:394-432`
- **代码**：
    ```java
    // NeovideCaretManager.java:49
    private static final NeovideConfig CONFIG = new NeovideConfig();
    // :512-527
    if (!app.isDispatchThread()) {
        Snapshot old = snapshot();
        syncStateToConfig();          // 让非 EDT 的读者立刻看到新配置
        app.invokeLater(() -> applyConfigOnEdt(old));
        return;
    }
    ```
- **失败场景**：非 EDT 调用 `applyConfig()` 时，`syncStateToConfig()` 在调用线程上逐字段写 `CONFIG`，
    而 `CONFIG` 的字段全是非 volatile 普通 POJO 字段。JMM 下：
    - 回调**之前**的一帧里，EDT 渲染可能读到「新 `animationLength` + 旧 `shortAnimationLength`」这种跨字段不一致 → 一帧形状跳变；
    - 严格按 JMM，没有 happens-before 的写对读线程**没有可见性保证**。
    Java 保证标量与引用赋值原子，所以不会读到「半个 float」，但跨字段撕裂与可见性都不受保护。
- **为什么是问题**：注释写的是「让非 EDT 的读者立刻看到新配置」——**意图需要内存序，实现没有**。
    这类问题会把「改了设置视觉不变」变成无法复现的偶发问题（本项目反复出现过的症状）。
- **发现视角**：1 个视角（java-reviewer，HIGH）
- **严重度调整**：**原判 HIGH，本报告改为 MEDIUM，理由 Z**：`invokeLater` 会建立 happens-before，
    EDT 侧在回调**之后**的读是可见的；残余风险是「回调之前那一帧的跨字段不一致」，属一帧级观感问题，
    不是「永远不生效」。若将来出现非 EDT 高频写路径，应改回 HIGH。
- **上次审计是否报过**：**新发现**
- **建议方向**：给 `state` 与 `CONFIG` 至少加一层 volatile 发布点；或让非 EDT 分支不再直接写 `CONFIG`（快照已在，EDT 侧会重建）。

### [M-4] NaN 一旦进入弹簧就永不自愈；`GlowIntensity` 承诺截断却把 NaN 透传

- **位置**：`core/DampedSpring.java:55-71`、`core/TrailCorner.java:353`、`core/GlowIntensity.java:58-67`、`core/AfterimageTrail.java:171-173`
- **代码**：
    ```java
    // DampedSpring.update
    if (animationLength <= dt || Math.abs(position) < 0.001) {
        position = 0.0; velocity = 0.0; return false;
    }
    // TrailCorner:353
    if (destX != previousDestX || destY != previousDestY) { springX.setPosition(destX - currentX); ... }
    // GlowIntensity.strengthFor
    if (s < 0f) { return 0f; }
    if (s > 1f) { return 1f; }
    return s;
    ```
- **失败场景**：
    - `Math.abs(NaN) < 0.001` 为 `false` → 复位分支永不触发；`destX != previousDestX` 对 NaN 恒 `true`，每帧把 NaN 写回 → 该角点**永久 NaN** → `Path2D.lineTo(NaN, …)` 被 Java2D 静默忽略 → 光标不显示、无日志，直到显式 `jump/snapTo`。
    - `GlowIntensity.strengthFor`：NaN 时两个比较都 false → 返回 NaN → `strength > 0f` 恒 false → 辉光被当成「静止」永不亮。
    - `AfterimageTrail.setLifetime`：`Math.max(0.02, NaN)` 仍是 NaN → 残影 alpha 全 NaN。
- **为什么是问题**：项目为 NaN 专门写过守卫（`NeovideCursorSettings.clamp(float)` 有 `Float.isNaN` 分支并注明原因），
    说明这条链路被认为有风险；但 core 侧三个类完全没有对应守卫。
- **发现视角**：**3 个视角** —— silent-failure-hunter、java-reviewer（×2）、code-reviewer（LOW）
- **上次审计是否报过**：**新发现**
- **建议方向**：`update()` 入口加 `Double.isFinite` 守卫并 `reset()`；`strengthFor` 对非有限偏移返回 0；`setLifetime` 判有限后 `Math.max`。

### [M-5] `clamp(float)` 把 NaN 一律归到下界，对 0..1 字段等于「完全不可见」

- **位置**：`settings/NeovideCursorSettings.java:744-750`
- **代码**：
    ```java
    private static float clamp(float v, float lo, float hi) {
        // NaN 会同时绕过两个比较，必须单独处理：否则 NaN 会进绘制路径
        if (Float.isNaN(v)) {
            return lo;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }
    ```
- **失败场景**：手改 XML 把 `tailOpacity` 写成 `NaN`（`Float.parseFloat("NaN")` 成功，XmlSerializer 正常解析）→ 重启 →
    `clamp(NaN, 0f, 1f)` 返回 `0` → **拖尾完全透明，光标消失**，日志无痕。
    `glowOpacity` 同理 → 无辉光。`stretchRatioK` NaN → `1.0`，而 `syncConfigToState` 里同一字段的 NaN 回落是 `4.5`，**两处策略不一致**。
- **为什么是问题**：方向正确（拦住 NaN），但「归到 lo」对这些字段等于「关掉功能」，而它们的默认值分别是 1.0 / 0.55 / 4.5。
- **发现视角**：1 个视角（silent-failure-hunter）
- **上次审计是否报过**：**新发现**
- **建议方向**：NaN 统一回落「该字段的 State 默认值」；`stretchRatioK` 两处统一到 4.5。**与 H-1 同批做**（同一个「区间」表）。

### [M-6] 「`runtimeSwitch` 跳过」这层保护不可达；`restore()` 零生产调用，探针在测死方法

- **位置**：`settings/SettingsField.java:757-763`（不可达分支）、`:696-719`（`restore`）
- **代码**：
    ```java
            SettingsField f = byKey(key);
            if (f == null || !f.inPreset) {
                continue;
            }
            if (f.runtimeSwitch) {
                continue;                   // 运行环境开关：预设无权改动
            }
    ```
- **失败场景**：不是当下会出错的功能，是**保护是名义上的**：
    5 个工厂都令 `inPreset = !runtimeSwitch`，`hexColors` 又把 `inPreset` 写死 `false`，
    因此「通过 `inPreset` 检查」⇒「`runtimeSwitch == false`」恒成立 —— 第二个 `continue` 永远不执行。
    `restoreSkipping` 与 `restore` 今天**完全等价**，javadoc 描述的「第二道锁」不存在。
    下一个加设备级字段的人若用 `hexColors` 这类工厂，恢复路径不会拦住它，而审查者会以为有保护。
    附带事实：`restore()` 在 `src/main` **零调用**，只有
    `tools/settings-probe/VCustom.java:90,118`、`VRegr2.java:579`、`VSepBound.java:395` 在调 —— 探针测的是生产不用的方法。
- **为什么是问题**：纪律四-G「假通过①」，与 CLAUDE.md 陷阱 3 同类。
- **发现视角**：1 个视角（code-reviewer）
- **上次审计是否报过**：**新发现**（0.9.87 P-15 报的是 `collectTrailTargets` / `quadCount()` / `updateCorners()`）
- **建议方向**：二选一 —— 让 `inPreset` 由 `!runtimeSwitch` 在所有工厂统一派生，或删掉不可达分支并在 javadoc 写明「唯一防线是 `inPreset`」；
    同时把探针改调 `restoreSkipping`（**契约变更**，并在断言旁注明，不是为了让测试变绿）。
    **注意**：删 `restore` 会让上述三个探针编译失败，必须同批改。

### [M-7] `AnimPreset.smoothTicks` 是死字段，但表里填了非中性值（「纯平滑输入」= 20）

- **位置**：`settings/CursorPresets.java:162`、`:220-226`、`:369`
- **代码**：
    ```java
        private final int smoothTicks;
    ...
        /** 输入平滑（0–1）。 */
        public float typingSmoothness() {
            return smoothTicks / 100f;
        }
    ...
            new AnimPreset("纯平滑输入",        10,  11,  100,  8,   60,  30,  false, false,   20, 100,  50,  75,  20, 100),
    ```
- **失败场景**：字段 javadoc 写着「0.9.73 起不再参与预设写入 … 已不再被任何生产代码读写」，
    但它是构造器必填参数，三行预设填了 0 / 0 / **20**，两个访问器仍是 `public`。
    下一个改 `applyAnimPresetFields` 的人看到 `p.typingSmoothness()` 存在、且「纯平滑输入」自称 0.2，
    很自然会补一行 `s.typingSmoothness = p.typingSmoothness();` → 违背 0.9.73 用户拍板的「平滑一直不参与预设」。
- **为什么是问题**：纪律四-J 第 4 条「留了一个看起来能读的死人接口」。
    与已知 VRegr2 R-5/R-6 是**不同的点**：R-5/R-6 说 `typingSmoothness` 不参与 match、不派生副本；本条说的是 `AnimPreset` 里那个字段本身。
- **发现视角**：**2 个视角** —— code-reviewer（MEDIUM）、java-reviewer（LOW）
- **上次审计是否报过**：**新发现**
- **建议方向**：删字段 + 两个访问器 + 构造器该参数，三行表各少一个数；探针（`VRegr2` / `VPreset9`）随之改契约。

### [M-8] `settings` 反向依赖 `ui`，形成包环

- **位置**：`settings/NeovideCursorSettings.java:395`、`:412`、`:419`、`:441`
- **代码**：
    ```java
        NeovideConfig c = com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager.getConfig();
    ...
        boolean diag = com.github.yyyolo7a79.neovidecursor.ui.CursorDiag.enabled();
    ```
- **失败场景**：`ui` 大量依赖 `settings`，而 `settings` 又反向依赖 `ui`（用全限定名调用，说明作者也不想加 import —— 这本身就是掩盖依赖的信号）。
    任何想单独验证 `syncStateToConfig` / `syncConfigToState`（六层的 ③④ 层）的探针，都必须先加载 `ui.NeovideCaretManager`。
    当前不会 NPE，只因 `CONFIG` 是 `private static final NeovideConfig CONFIG = new NeovideConfig()` —— 这条「暂时安全」依赖 ui 侧一行静态初始化，没有东西守它。
- **为什么是问题**：依赖方向倒置，持久化层不再可独立测试。`core` 无反向依赖（已核对），`settings` 本该与 `ui` 同级。
- **发现视角**：1 个视角（code-reviewer）
- **上次审计是否报过**：**新发现**
- **建议方向**：让两个方法收 `NeovideConfig` 参数、由调用方传入（只动两处签名，diff 最小）。

### [M-9] `NeovideCursorSettings` 1143 行不是「大」的问题，是四类互不相干的责任挤在一个类里

- **位置**：`settings/NeovideCursorSettings.java` 全文
- **代码**（结构性）：类里同时有
    (a) 平台持久化服务（`loadState` / `getState` / `persistQuietly` / `PersistResult`，约 600 行）+
    (b) 一次性旧键迁移（`migrateIfNeeded` / `deleteOldKeysIfPresent` / `tryGetPropertiesComponent`，约 100 行）+
    (c) 纯数学（`deriveTrailFactors` 三版 / `applySlopeOverlay` / `round2`，约 150 行）+
    (d) 嵌套 `State`（约 360 行，含 25 个编号字段的长 javadoc）。
- **失败场景**：四块的**变更理由完全不同** —— 改公式不必碰 `loadState`，改迁移不必碰 `State`。
    而它恰是「六层拷贝」的核心文件：每次加字段都要在 1143 行里找四个落点，正是漏拷的温床。
- **为什么是问题**：不是「超过 800 行」的形式问题，是内聚性。（纪律四-J；LESSONS 坑 22）
- **发现视角**：1 个视角（code-reviewer）
- **上次审计是否报过**：**新发现**
- **成本实测（本轮补）**：`State` 被 **54 个探针文件**、**500 处**按全限定名引用（`NeovideCursorSettings.State`），
    生产代码另有 **12 个文件**。拆 `State` 等于一次改 66 个文件的引用。
    **结论：不要「顺手」做**，但**不是**因为面板——面板标签页改造已经完成（见 0.7）。
- **建议方向**（行为零变化的切分边界，建议单独一批做）：
    `settings/NeovideCursorState.java` ← `State`（平台序列化契约，与服务生命周期无关）；
    `settings/TrailFactorMath.java` ← `deriveTrailFactors` / `applySlopeOverlay` / `round2` / `clamp(float)`（纯函数，无平台依赖）；
    迁移三方法抽 `LegacyKeyMigration.java`（一次性代码，将来整文件删）；
    剩下服务本体约 450 行，低于 800 上限且内聚。
    **风险提示**：拆 `State` 会同时改 54 个探针的 import，应在无新功能并行开发时做，跑完全量门禁再合并。

### [M-10] `CustomPresets.parse` 静默丢弃坏条目与重名条目，下一次写回磁盘就永久消失

- **位置**：`settings/CustomPresets.java:226-231`
- **代码**：
    ```java
            Entry e = parseOne(raw);
            if (e == null || find(out, e.name) != null) {
                continue;                       // 坏条目 / 重名 → 忽略后者
            }
    ```
- **失败场景**：
    | 路径 | 行为 | 后果 |
    |---|---|---|
    | 坏条目（缺 `␟` / 空快照） | `parseOne` 返回 `null`，整条跳过 | 内存里消失；**任何一次 `put/remove/rename` 后的 `format` 都会把磁盘上的它一起抹掉**，不可逆 |
    | 重名条目 | 只保留第一个 | 同上，第二份快照永久丢失 |
    | 快照内单个 key 非法 | `decode` 返回 `false` | 见 H-2：静默保留 + 上层宣称成功 |

    整个 `parse` 阶段**零日志**（唯一有日志的是 `format` 里非法名的 JUL warning，见 L-7）。
- **为什么是问题**：静默失败 + 不可逆。虽然手改 XML 才触发，但「下一次保存就抹掉」意味着没有第二次机会。
- **发现视角**：1 个视角（silent-failure-hunter）
- **上次审计是否报过**：**新发现**
- **建议方向**：`parse` 对跳过原因记一次 `LOG.warn`（带条目长度与名字，不打印整条快照）；`format` 前后比较条目数，减少时记一条汇总。

### [M-11] `slope < 0` 的注释三处说「用 k 推导」，实现一律夹成 0 —— **已排期到 `leadingSnap` 批次（决策 2）**

- **位置**：`settings/NeovideCursorSettings.java:544`、`:430-431`、`:888`，实现 `:582-603`
- **代码**：
    ```java
     * @param slope 0–100；{@code < 0} 表示「用 k 推导」（旧值兼容，0.9.80 起不再默认走这条）
    ...
        // 三参版本：slopeTicks >= 0 时用斜度，否则沿用 k 推导（默认 -1 = 旧行为）
    ...
         * 5b. 斜度（0–100，-1 = 用 {@code stretchRatioK} 推导）。
    ```
    实现：`float t = clamp(slope, 0f, 100f) / 100f;` —— 负数夹成 **0（纯矩形）**。
- **失败场景**：维护者按注释把 `slopeTicks` 设为 `-1` 期望「跟随 k」，实际得到「无斜纯矩形」，且会落盘成 0，再也回不到那个语义；
    写探针断言 `applySlopeOverlay(base, -1) == deriveTrailFactors(k)` 会必红，而红的原因是注释错。
    **最近的现实触发点**：做「半边拖尾（`leadingSnap`）」时要改 `deriveTrailFactors` 的入参，很可能按这三处注释以为 `-1` 可用。
- **为什么是问题**：注释与实现相反（纪律四-J 第 3 条）。**不是新 bug** —— `-1` 今天进不来（`loadState` 回落 0、setter 只收 0..100）。
- **发现视角**：**2 个视角** —— code-reviewer、silent-failure-hunter
- **上次审计是否报过**：**0.9.87 已报且仍在**（P-7：「`slope < 0` 被 clamp 成 0，与 javadoc 相反」）
- **处理决定（决策 2）**：**代码与注释都不动**，排期到 `leadingSnap` 那一批一起改。
    届时必须同时清理这三处注释，并把 `PROJECT-DECISIONS.md` §8.1 的「待用户拍板」改成「已排期」。

---

## LOW

### [L-1] `state` 字段非 volatile，`getState()` 把内部引用直接交给任意线程
- **位置**：`settings/NeovideCursorSettings.java:89`、`102-105`
- **代码**：`private State state = new State();` / `public State getState() { return state; }`
- **失败场景**：`loadState` 里 `state = target` 是整体替换，调用方又从任意线程直接改 `state.tailColor`。平台服务初始化有发布保证，实际风险低，但配合 M-3 没有任何内存序保证。
- **发现视角**：java-reviewer ｜ **上次审计**：新发现 ｜ **建议**：`private volatile State state;`

### [L-2] 三份 clamp 实现语义不一致，只有一份处理 NaN
- **位置**：`core/TrailCorner.java:426-428`、`settings/NeovideCursorSettings.java:744-750`、`core/GlowIntensity.java:58-67`
- **代码**：`private static double clamp(double v, double min, double max) { return v < min ? min : (v > max ? max : v); }`
- **失败场景**：`NeovideCursorSettings.clamp` 有 NaN 分支，`TrailCorner.clamp` 没有；`GlowIntensity.strengthFor` 同样透传 NaN。今天不可达（上游都已 clamp），但一旦上游被绕开（见 H-1），辉光强度变 NaN → alpha 0 → 辉光静默消失。
- **发现视角**：code-reviewer、java-reviewer ｜ **上次审计**：新发现 ｜ **建议**：三处 clamp 合并到一处，统一 NaN 语义。**与 H-1 / M-5 同批**。

### [L-3] `AfterimageTrail` 的 `config` 是死字段；`setLifetime` 无上界且挡不住 NaN
- **位置**：`core/AfterimageTrail.java:58`、`171-173`、`113`
- **代码**：`private final NeovideConfig config;` / `public void setLifetime(double lifetime) { this.lifetime = Math.max(0.02, lifetime); }`
- **失败场景**：①`config` 声明+构造器赋值后全类再无引用 → 残影存活时长不会自动跟随 `config.afterimageLifetime`，只能靠调用方手动 `setLifetime`（`CaretAnimator` 两处，漏一处就是「改设置不生效」）；②`Math.max(0.02, NaN)` 返回 NaN；③无上界：`lifetime=1e10` 时 `nowNanos - Long.MAX_VALUE` 溢出回绕 → `while` 一次删空整个历史。
- **发现视角**：java-reviewer ｜ **上次审计**：新发现 ｜ **建议**：删 `config` 或真正接上；`setLifetime` 判有限 + 加下上界。

### [L-4] `NeovideColors.parse` 失败静默返回黑色，两个入口都没有任何痕迹
- **位置**：`core/NeovideColors.java:44-46`、`56-59`
- **代码**：`if (h.length() != 8) { return Color.BLACK; }` / `catch (Exception e) { return Color.BLACK; }`
- **失败场景**：任何一条非法颜色串进入 `CONFIG`（见 M-1 的两条路径）→ 拖尾与辉光同时变黑 → 用户报「光标黑了」，日志里一个字都没有。
- **发现视角**：silent-failure-hunter ｜ **上次审计**：新发现 ｜ **建议**：加一个容量有界的「已报过的非法串」集合，首次失败 `LOG.warn` 带原串。

### [L-5] `tryGetPropertiesComponent()` 吞掉异常返回 `null`，堆栈完全丢失
- **位置**：`settings/NeovideCursorSettings.java:370-379`
- **代码**：`} catch (Throwable t) { return null; }`
- **失败场景**：本批唯一一个「catch 后既不打日志也不调 `CursorDiag`」的 `Throwable`。上层把 `null` 解释成「服务不可用/没有键」，与真正异常无法区分 —— H-3 的不可逆删键就发生在这之后。
- **发现视角**：silent-failure-hunter ｜ **上次审计**：新发现 ｜ **建议**：catch 里 `LOG.warn("PropertiesComponent 获取失败", t)`。

### [L-6] `persistQuietly` 的 `CONFIRMED` 实际只表示「`saveSettings()` 没抛异常」
- **位置**：`settings/NeovideCursorSettings.java:685-688`
- **代码**：`app.saveSettings(); return PersistResult.CONFIRMED;`
- **失败场景**：`saveSettings()` 是 void，返回只说明没抛异常，不代表磁盘真写入了。而 `CONFIRMED` 是唯一允许删旧键的状态。平台内部保存失败但不抛出 → 旧键被删 → 新 state 未落盘 → 迁移值两端都没有。
- **发现视角**：silent-failure-hunter ｜ **上次审计**：新发现 ｜ **建议**：至少把注释改成「未抛异常」；或删键前再 `XmlSerializer` 往返一次磁盘文件。

### [L-7] `CustomPresets.format` 的唯一 warning 走 `java.util.logging`，大概率不落 `idea.log`
- **位置**：`settings/CustomPresets.java:300-304`
- **代码**：`java.util.logging.Logger.getLogger("neovide-cursor").warning("跳过非法预设名…" + e.name.length());`
- **失败场景**：项目其余日志统一走 IDEA 的 `Logger` 或 `CursorDiag`；JUL 的自建 logger 不在 IDEA 的 log4j2 配置里，这条本应用于解释「预设少了一条」的唯一线索大概率不会出现。
- **发现视角**：silent-failure-hunter ｜ **上次审计**：新发现 ｜ **建议**：改用项目统一的 `LOG`。**已并入决策 3 的落地清单第 3 步**。

### [L-8] `TrailCorner.jump` 兜底分支的分母没有零保护
- **位置**：`core/TrailCorner.java:212-215`
- **代码**：`double jumpX = deltaXpx / width;` / `double jumpY = deltaYpx / height;`
- **失败场景**：`width`/`height` 为 0 时 `0/0` 得 NaN、非零/0 得 Infinity，两个比较都走 `false` → 静默退化到跨行分支，无日志。当前 `SpringTrail.applyCharsMoved` 总会写值，触发面窄。
- **发现视角**：silent-failure-hunter ｜ **上次审计**：新发现 ｜ **建议**：分母为 0 时保持上一帧判定并记一次 warn。

### [L-9] 三处「算静止了」的阈值互不相同（0.001 / 0.01 / 0.5）
- **位置**：`core/DampedSpring.java:57,93`、`core/TrailCorner.java:379`
- **代码**：`if (Math.abs(position) < 0.001)` / `return Math.abs(position) >= 0.01;` / `return Math.abs(springX.getPosition()) > 0.5 || ...;`
- **失败场景**：`SpringTrail.update()` 靠 0.5 决定是否继续重绘，因此所有角点 ≤0.5px 时渲染就停了，而弹簧残留偏移最大可达 0.5px 且永久保留。2px 宽光标上这是 25% 宽度误差，肉眼看不出，但让「静止尺寸」与 `cursorWidth` 名义值不一致。
- **发现视角**：java-reviewer ｜ **上次审计**：新发现 ｜ **建议**：三个阈值收进同一处常量，注释写明各自用途。

### [L-10] 过期计数与悬空注释（多处，同一病根：数字没有守卫）
- **位置**：
    `settings/NeovideCursorSettings.java:784-786`、`:1114-1141`（`State.toString` 只打印 23 项，漏 `targetFps`/`slopeTicks`/`diagLogging`/`multiCursorNoGlow`/`cursorWidthPx`/`cursorHeightRatio`/`rgbGradient`/颜色预设三件套 共 10 个）；
    `settings/NeovideCursorSettings.java:510-545`（三块连续 javadoc，前两块不属任何成员，「输入平滑」文档挂错位置）；
    `settings/CursorPresets.java:48-49`（类 javadoc「6 个字段」，实际 15 个）；
    `settings/SettingsField.java:119-124`（注释说三个颜色字段「同时在 inPreset 里」，实际全是 false）；
    `settings/NeovideCursorSettings.java:836-845`（`cursorWidthPx` javadoc「0 = 跟随原生宽度」，代码已 clamp 到 1）；
    `core/NeovideConfig.java:189-190`（`animationResetThreshold` 注释「小于该值不重置」，实际 `TrailCorner.jump()` 无条件 `reset()`）。
- **失败场景**：按 `SettingsField:119-124` 的旧说法去排查颜色预设污染动画预设的问题，会去改一个早已改好的地方；用 `toString()` 判断「字段有没有落地」会漏掉 10 个字段。`animationResetThreshold` 是 D0 冻结的死字段，任何人想调它都不会有任何反应。
    **同类新增样本**：本报告 0.7 记录的 `PROJECT-DECISIONS` §七 与 `CLAUDE.md` 行数两处过期。
- **发现视角**：**3 个视角** —— code-reviewer（×3）、java-reviewer（×3）
- **上次审计**：`animationResetThreshold` = **0.9.87 P-14 已报且仍在**；其余新发现
- **建议**：数字类注释要么删掉具体数字，要么由探针守（本项目已有 `VBuildDecl` 守字段数的先例）。

---

## 四、十类检查的覆盖结论

| # | 检查项 | 谁审的 | 结论 |
|---|---|---|---|
| 1 | 字段六层链路 | code-reviewer + silent-failure-hunter | **通过**。State 全部 33 字段（含 `stateVersion`）在 `loadState`（:115-238）与 `copyFrom`（:1072-1111）中逐项覆盖，无漏拷；`FIELDS` 工厂 27 + 搬运循环 1 = 28 个 `out.add(`，与 CLAUDE.md §7.0 一致 |
| 2 | 一字段两职责 | code-reviewer | **通过**。`charsMoved` 的双职责已拆：每帧写入、目标未变时显式清 `-1`；短跳分支的 `trailFactors` 读取有 `rank < length` 保护（`TrailCorner:229`） |
| 3 | 预设域三份清单同源 | code-reviewer | **通过**。`PRESET_FIELDS`（13）== `applyAnimPresetFields` 写入集合（13）== `matchAnimPreset` 比较集合（13），字段名逐一对上 |
| 4 | 默认值与迁移 | silent-failure-hunter | **未通过**。`clamp` 的 NaN 分支回落 `lo` 而非默认值（M-5）；迁移读失败仍删旧键（H-3）。另核：`slopeTicks` 越界回落目标已与 State 默认 0 一致，0.9.87 之前的「回落 100」问题**已修** |
| 5 | 数值边界 | silent-failure-hunter + java-reviewer | **未通过**。`decode` 无范围校验（H-1）、NaN 传播无守卫（M-4）、`TrailCorner.jump` 分母零（L-8）。HEX 口径已由决策 1 定为 RGBA |
| 6 | 异常与静默失败 | silent-failure-hunter（主）+ java-reviewer（辅） | **未通过**。本批 `catch (Throwable)` 共 6 处，其中 1 处既不记日志也不走 `CursorDiag`（L-5）；`decode` 的 `catch (RuntimeException)` 有 2 条链路完全不可观测（H-2） |
| 7 | 线程与并发 | java-reviewer | **有发现**。`CONFIG` 非 volatile 且存在非 EDT 写（M-3）；`state` 引用发布无保证（L-1）。`core/` 无静态可变集合、无 Timer、无监听器注册，未发现泄漏 |
| 8 | 死代码 | code-reviewer | **有发现**。`restore()` 零生产调用（M-6）、`runtimeSwitch` 分支不可达（M-6）、`smoothTicks` 死字段带非中性值（M-7）、`AfterimageTrail.config`（L-3）、`NeovideConfig.animationResetThreshold`（L-10，D0 冻结） |
| 9 | 命名与行为一致 | code-reviewer + java-reviewer | **未通过**。`slope < 0` 三处注释与实现相反（M-11，已排期）、`cursorWidthPx` javadoc 与 clamp 矛盾（L-10）、`isValidName` javadoc 承诺 emoji 但实现拒绝（M-2，已拍板改为守契约）、`isValidHexColor` 声称 `#AARRGGBB` 而实现按 `RRGGBBAA`（M-1，已拍板 RGBA） |
| 10 | 可维护性 | code-reviewer | **有发现**。`NeovideCursorSettings` 四类责任挤在一个类（M-9，已给切分边界与 500 处引用的成本）；`settings` → `ui` 包环（M-8） |

**没有任何一项「本轮无人覆盖」。** 十项全部有代理结论，6 项给出否定结论。

---

## 五、剩余待确认

以下两条无法只凭代码裁决，交给人定。

### 5.1 迁移读失败时删旧键，是有意还是疏漏？（对应 H-3）

- **解释 A（有意）**：`NeovideCursorSettings.java:314` 的注释「不阻塞启动，仍把版本号往前推（旧键按「不存在」处理）」写得很明确，作者知道这条路径。
- **解释 B（疏漏）**：同方法的 `FAILED` 分支写着「绝不半途而废」，而「读失败」与「写失败」语义上都是「没成功」，处置却相反；且删键不可逆。
- **判定方法**：`git log -S "按「旧键不存在」处理"` 看那次提交的意图；或让作者确认。
- **影响**：若确认为有意 → H-3 降为 MEDIUM，改为「在注释里写明取舍」；若为疏漏 → 按建议方向修。

### 5.2 M-3（非 EDT 写 CONFIG）的严重度

- **代理原判**：HIGH（java-reviewer）。**本报告改为** MEDIUM，理由见该条。
- 分歧点在于「`invokeLater` 是否提供足够的 happens-before」。
- **判定方法**：在非 EDT 路径上实测（改设置后立即读 `CONFIG`，跑 N 次看是否出现旧值），属实机验证。

---

## 六、修复批次建议（给决策会话）

> 排序原则：先做**零观感变化**的健壮性修复，再做**会改变契约**的校验统一，最后做结构重构。
> 每批做完跑一次全量门禁（**不要并发跑两份**，四-G-2）。

| 批次 | 内容 | 是否影响观感 | 探针影响 |
|---|---|---|---|
| **第 1 批**（健壮性，零风险） | H-2（返回值 + decode 日志）、H-3（迁移删键）、M-4（NaN 守卫）、M-10（解析日志）、L-4 / L-5 / L-6 / L-8 | 否 | 无（只加日志） |
| **第 2 批**（决策 1 + 校验统一） | 决策 1 三处改动、H-1（区间收进字段表）、M-1（颜色快照校验）、M-5（NaN 回落默认值）、L-2（clamp 合并） | 否（只影响非法值路径） | **可能有**：先 `grep -rn "sanitizeColor\|#AARRGGBB\|isValidHexColor" tools/settings-probe/`，命中即契约变更 |
| **第 3 批**（决策 3） | M-2 四步（删承诺 / 改文案 / 日志 / 加探针）、L-7 | 否 | **新增断言**（不是改现有断言） |
| **第 4 批**（结构，可延后） | M-6（不可达分支 + `restore` 零调用）、M-7（`smoothTicks`）、M-8（包环） | 否 | **会红**：删 `restore` 让 3 个探针编译失败；删 `smoothTicks` 要改 `VRegr2`/`VPreset9` 契约 |
| **第 5 批**（大手术，单独评估） | M-9（拆 `NeovideCursorSettings`） | 否 | **会红**：54 个探针文件的 import |
| **不排期** | M-11（slope<0） | — | 无（决策 2：等 `leadingSnap` 批次一起） |

### 会触发的探针契约变更（第 4 / 5 批必读）

改字段数或控件数会新增红，**那是契约变更，不是缺陷**：

| 探针:行 | 断言 | 什么会触发 |
|---|---|---|
| `VAddSetting.java:115` | `sliders == 14 && checks == 9 && combos == 4` | 加滑块 / 复选框 / 下拉 |
| `VerifyPanel.java:97 / 104 / 108` | `sliders == 14` / `checks == 9` / `combos == 4` | 同上 |
| `VPreset.java:385-386` | `State` public 实例字段数 `== 30` | 加任何 State 字段 |
| `VDefaultFreeze.java:40-45` | `GOLDEN` 金表逐字段默认值 | 加字段必须登记 |

**正确做法**：改这些常数并在旁边写「契约变更，非缺陷」。**不要反过来动生产代码去迁就断言。**

---

## 七、本轮未覆盖

| 未覆盖项 | 原因 |
|---|---|
| `ui/` 与 `ui/settings/` 的全部文件 | 不在本轮范围。只看了调用点（`CursorPresetApplier`、`NeovideCaretManager`、`NeovideCursorConfigurable` 的几行） |
| `tools/settings-probe/` 全部探针 | 不在本轮范围。只做了「哪些探针调了 `restore`」这类调用面检索 |
| 0.9.87 审计的 P-3 / P-4 / P-5 / P-6 / P-10 / P-11 / P-12 / P-13 | 全部落在 `ui/` 或已删除代码。本轮只核实了 P-7（slope）与 P-14（`animationResetThreshold`）仍在 |
| EDT 卡死、emoji 输入被拒、光标变黑等**现象级验证** | 需装 jar、启 IDEA 实机复现 |
| `core/NeovideConfig.java` 的任何修改 | D0 红线，只报不改（L-10） |
| M-3 的实测证据 | 需实机压测，本轮只做静态分析 |
| `PROJECT-DECISIONS.md` §七 那 4 项跟进 | 前提已消失（见 0.7），但具体怎么改属面板范围，本轮未审 |

---
---

# 第 2 批 · ui/ 动画引擎与绘制（追加于 2026-10-30 深夜）

> 本节追加于第 1 批报告之后。**第 1 批内容（上文第 1–770 行）一个字未改**，
> 校验方式：追加前 `sha1sum docs/CODE-AUDIT-2026-10-30.md` = `3fd18a4060d0f2b1b2349d99fb5e2a39dca3ae1a`（770 行），
> 追加后复跑 `head -770 | sha1sum`，见本节 8.7 末尾。
>
> 严重度↔任务书 P 级映射：**P0=CRITICAL / P1=HIGH / P2=MEDIUM / P3=LOW**。
> 发现编号前缀 `U-`（ui 批），与第 1 批的 `H-/M-/L-` 不冲突。

## 八、第 2 批（ui/ 动画引擎与绘制）

### 8.1 基线与方法

#### 8.1.1 前置检查

```
=== git status --porcelain（开工时实测）===
M docs/BUG-LOG.md
M docs/INDEX.md
?? build/night-run-report.md
?? docs/CODE-AUDIT-2026-10-30.md
?? docs/HANDOFF-DECISION-2026-10-30.md
=== git log --oneline -3 ===
6f7d585 fix(robustness): 审计第 1 批 8 条——失败可观测化 + NaN 守卫（零观感变化）
36cbaa4 fix(install): 结构指纹漏计 *Global 工厂，把 27 个字段数成 25
f112bff docs: 新增 2026-10-30 交接文档
=== git rev-list --left-right --count fork/main...HEAD ===
0	0
```

| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| HEAD | `6f7d585` | `6f7d585` | ✅ |
| 与 fork/main 同步 | `0 0` | `0 0` | ✅ |
| CODE-AUDIT 本体 | `??` 未跟踪（未被他人动过） | `??` | ✅ |
| 8 文件行数 | 6873 | `wc -l` 实测 6873（2994/330/1163/932/295/170/851/138） | ✅ |
| 已修改（M）文件 | **0** | **2**：`docs/BUG-LOG.md`（+105，今天另一会话写的 BUG-014/015 记录）、`docs/INDEX.md`（±1） | ⚠️ **不符，见下** |

**偏差处置（如实记录）**：任务书规定「前置检查不通过就停」。实际不符的是上述两个文档
——与本轮审查对象（`src/main/java/.../ui/`）无关，`src/` 完全干净（== HEAD），
且任务书担心的具体情形（CODE-AUDIT 变成 M）未发生。**已向用户报告并获准继续**
（选项「继续（推荐）」，2026-10-30 深夜）。这两个文件本轮未读未写。

#### 8.1.2 门禁基线（第 1 批修复后，任务书指定口径）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1
# 仓库根执行，不带 -Repo；本轮只跑一份（四-G-2 并发假红禁令）
```

| 指标 | 实测 | 任务书期望 | 结论 |
|---|---|---|---|
| 总条目 | **100** | 100 | ✅ |
| PASS | **1035** | 1035 | ✅ |
| FAIL | **2**（VRegr2 R-5/R-6，informational，照 PROJECT-DECISIONS §六 排除） | 2 | ✅ |
| 末行 | `V-PROBES RESULT: ALL PASS`（exit 0） | ALL PASS | ✅ |

汇总数由 `awk` 对逐探针输出求和得到，非目测。**本轮四个审查代理被明令禁止跑构建/门禁**，
不存在第二个进程动 `build\v2-probe\classes` 的可能。

#### 8.1.3 范围（8 文件 / 6873 行，行数实测）

| 文件 | 行数 | 角色 |
|---|---|---|
| `ui/CaretAnimator.java` | 2994 | 每帧驱动、多光标、read action（全项目最大消费者） |
| `ui/CaretTrailPanel.java` | 1163 | 绘制、辉光、RGB wash、双拼 |
| `ui/NeovideCaretManager.java` | 932 | 全局 CONFIG、applyConfig 调度 |
| `ui/CursorDiag.java` | 851 | 诊断日志 |
| `ui/SpringTrail.java` | 330 | 单光标四角状态、rank 分配 |
| `ui/RenderScheduler.java` | 295 | 帧调度、活锁风险 |
| `ui/EdtWatchdog.java` | 170 | 看门狗 |
| `ui/CaretSchemeWatcher.java` | 138 | 配色方案监听 |

不在范围：`ui/settings/` 全部（第 3 批）、`CursorPresetApplier` / `AnimPresetGroup` /
`ColorPresetGroup` / `Toggle*.java` / `CaretAnimatorProbe`（第 4 批）。允许且仅允许看
范围外调用点几行——多个发现的 sink 因此标注「范围外」。

#### 8.1.4 四个代理的视角与返回状态

| # | 视角 | 代理 | 状态 | 原始返回 | 工作量实证 |
|---|---|---|---|---|---|
| 4.1 | 通用质量 + 每帧分配 + 状态机 + 文件规模 | `code-reviewer` | ✅ 首次成功 | 0C / 1H / 12M / 5L | 32 次工具调用 / 2170 s / 22.3 万 token |
| 4.2 | 并发 + 线程模型（本批主力，已令其跳过 Framework Detection） | `java-reviewer` | ✅ 首次成功 | 0C / 2H / 10M / 7L | 72 次工具调用 / 2402 s / 19.9 万 token |
| 4.3 | 静默失败 + 失败后死状态 | `silent-failure-hunter` | ✅ 首次成功 | 0C / 8H / 16M / 5L | 38 次工具调用 / 1677 s / 18.7 万 token |
| 4.4 | 安全（已收窄到日志泄露/日志注入/资源耗尽，禁 OWASP 打分） | `security-reviewer` | ✅ 首次成功 | 0C / 0H / 3M / 5L | 48 次工具调用 / 1484 s / 12.4 万 token |

四个视角一个不缺，本轮无 429 无重试。原始返回合计 **74 条**，去重合并后 **49 条**
（0 CRITICAL / 8 HIGH / 28 MEDIUM / 13 LOW）。合并掉 25 条重复。

**汇总时调度者亲自复核过的行号**（防幻觉，逐条 sed/grep 原文）：
`CaretAnimator:2530-2548`（H1/H2 的 getAllCarets 调用点与 runTick 无 catch）、
`:1650-1680`+`:648-656`+`:2115-2122`（U-H-1 快速路径与 forceResync 契约）、
`:2503-2516`（U-M-2 每帧数组）、`refreshFrameGap` 全仓 4 处（U-M-4）、
`RenderScheduler:203-232`+`:84-100`（U-H-3/H-4）、
`NeovideCaretManager:705-740`+`:268-314`（U-H-6/H-5）、
`CursorDiag:777-808`（U-H-8）、`CursorDiag:58-95` + `synchronized|volatile` 全类 0 命中（U-M-12）、
`CursorDiag` 上限与 `[frame]`/`[caret]` 出口（U-M-27）、
`CursorPresetApplier:118-142` + `CustomPresets:525-566`（U-M-28）、
`NeovideConfig:264`（hideNativeCaret 默认 true）、
`VReadAction.java` 的 guard/驱动面（盲区属实）、
`CursorDiag.swallowed/configChanged` 生产接线（P-13 衔接）。

#### 8.1.5 陷阱记录检索（四处，全部命中，贴命中原文）

**① `CLAUDE.md` 第五节十四类陷阱** —— 命中三条，全部与本批直接相关：

```
陷阱 9：`invokeLater` 抛出 → 永久活锁
  RenderScheduler.dispatchDue() 先置 framePending=true 和 lastDispatchedDueNanos=due，
  再 invokeLater。后者抛出时两个标志已成死态 → 该光标彻底不动且无任何日志。0.9.88 加了 try/catch 回滚 + warn。
```
→ 按任务书审「覆盖面」：java-reviewer 穷举置点到 invokeLater 之间的语句后结论
**修复本体覆盖完整**（见 8.4 第 7 项），但同族残留两个入口（U-H-3 / U-H-4）。

```
陷阱 2：字段接通了 ≠ 字段生效了（六层，⑤消费者）
  targetFps 曾经：声明有、loadState 有、copyFrom 有、FIELDS 白名单有，
  但 computeNextDelayNs 读的仍是常量 —— 用户选 144 完全没效果。
```
→ 本批在绘制侧抓到**反向形态**：每帧写了没人读（U-M-23 `setGlowStrength`）。

```
陷阱 4：探针会骗你 —— 假通过 / 假失败 / 并发假红
```
→ 本批实锤一种新形态：VReadAction 给 `getAllCarets`/`removeCaretListener` 挂了 guard 代理，
但**从不调度 `tick()`/`runTick()`/`dispose()`** → U-H-2 与 U-M-19 在 1035 PASS 下静默存在。

**② `docs/WORK-DISCIPLINE.md` 四-E / 四-G / 四-J** —— 命中：

```
四-E（原文摘录）：「但 CaretAnimator.computeNextDelayNs() 读的是常量，从来没读过这个字段
  —— 用户选 144 完全没有效果，是纯死字段。」
四-G（纪律 1-3）：探针必须读绘制真正用的那份数据；必须调用生产方法；改坏必须红。
四-J（判据 1-5）：一个字段两个职责→拆；同一语义两处→先统一；名字与行为不符→改名；
  产生死代码→接回去或删净；同一 bug 第二次出现→前一次是补丁。
```
→ U-L-11 注释清单里的「sleep 精度三个互斥说法」按四-J 第 2 条登记；U-M-8 是四-J 第 5 条
（同一 bug 第二次出现：块选区每帧重建 SpringTrail，`:1919-1925` 注释自称已修）。

**③ `docs/PERFORMANCE-FPS.md`** —— 命中（已证伪清单，四个 brief 都写入「不要重提」）：

```
| 需要 timeBeginPeriod(1) 提高系统定时器分辨率 | 调用前后连续 sleep(4) 都是 218~225fps | 不需要 |
| 需要忙等补齐 | sleep(4)+忙等1ms = 180fps，纯 sleep(4) = 221fps | 忙等更差 |
| LockSupport.parkNanos 更准 | parkNanos(4ms) 实际 8.65ms | 远差于 sleep |
```
→ 本批性能类发现全部是「**分配/空转/重复调用**」方向（U-M-2/M-3/M-4/M-5/M-7），
无一重提前四种已证伪做法。另按该文件 §六，每帧 `repaint(x,y,w,h)` 带矩形合规，本轮不重审。

**④ `docs/CODE-AUDIT-2026-10-30.md` 第 1 批报告** —— 命中三条线索并已喂给代理：

```
M-3：CONFIG 静态可变非 volatile —— 本批要看 ui 侧调用点与频率（→ U-M-1，结论「确认+收窄」）
M-8：settings→ui 包环 —— 本批要看静态初始化是否真安全（→ U-M-1 与 8.5，结论「安全，且已有现实代价」）
H-2：返回值被丢弃模式 —— 本批在 ui 发现 2 处新实例（→ U-M-16 / U-M-24）
```

### 8.2 结论

**WARNING**（按 `rules/common/code-review.md` 的 Approval Criteria）

- CRITICAL **0** 条 → 不 BLOCK。
- HIGH **8** 条 → 不能 APPROVE。
- MEDIUM **28** / LOW **13**。

与第 1 批的形态差异（任务书 §零 的预判成立）：本批 HIGH 全部集中在
**「失败之后的死状态」与「调度线程生命周期」**（8 条里占 6 条），
外加 1 条**正常使用必触发的观感 bug**（U-H-1 滚动失明）与 2 条**证据基础设施撒谎**
（U-H-7 写盘静默失败、U-H-8 检测器终身只响一次）。
没有一条是「输入非法会怎样」。**没有任何一条需要推翻数据层假设——全部在 ui/ 热路径本身。**

**与第 1 批判据基准的量化对照**：插件目标 144fps、40fps 以下可感知。
本批唯一「正常使用必触发」的功能项 U-H-1 的失败形态是「光标画在错误的行上」——
不是掉帧，而是**位置错**，比掉帧更容易被用户看见。性能类最大项 U-M-3（静止仍每帧全量重绘）
的量化估计为 0.3%~3.1% 单核持续占用（代理按自家基准 216µs/帧折半推算，标注了推测），
不构成掉帧，与 PERFORMANCE-FPS 的 144fps 实测不冲突。

### 8.3 发现清单（按严重度排序）

---

## HIGH（P1，8 条）

### [U-H-1] 快速路径对「滚动」完全失明 → 覆盖层光标画在错误的行上（幻影光标）

- **位置**：`ui/CaretAnimator.java:1660-1665`（快速路径缓存判据）、`:650-655`（`forceResync` 契约自陈）、`:2119-2120`（像素坐标直接用作角点目标）
- **代码**：
    ```java
    VisualPosition vp = editor.getCaretModel().getVisualPosition();
    if (!forceResync && !fontScaleChanged && !inlayChanged
            && cameraTrail != null && cameraTrail.isInitialized()
            && cameraTrail.isAt(vp.line, vp.column,
                    editor.getDocument().getModificationStamp())) {
        CursorDiag.tickPathTrace(traceId, "fast");
        return false;
    ```
- **失败场景**：长文件把光标点到第 100 行 → 滚轮下滚 3 行（不移动 caret 的任何滚动）→
  文档戳不变、视觉行列不变 → 早退命中 → 角点目标停在滚动前的组件坐标 →
  `tickSpring` 每帧按旧坐标重画。`hideNativeCaret` 默认 `true`（`core/NeovideConfig.java:264`，
  调度者已核），屏幕上唯一可见的光标就是这个错位的假光标；滚出视口则表现为「光标消失」。
  下一次 caret 事件（按键/点击）置 `forceResync` 才自愈——观感 =「滚动时光标不跟，一打字跳回来」。
- **为什么是问题**：`forceResync` 自己的 javadoc（`:651-653`）写明了判据——「视觉位置可能没变，
  但像素坐标已失效，必须绕过快速路径重算」；三条补偿判据（行高 `:1647-1652`、inlay BUG-006、面板尺寸）
  **唯独没有滚动**。全仓检索 `ScrollingModel|VisibleAreaListener|ScrollingListener`
  （含探针目录）：**0 命中**（调度者复跑确认）。这是「同形 bug 第三次出现」：BUG-006 修的是 inlay，
  注释还留在 `:1654-1656`——四-J 第 5 条（同一类问题第二次出现说明上次是补丁）。
- **发现视角**：1 个视角（code-reviewer，HIGH）
- **上次审计是否报过**：**新发现**。BUG-006（inlay）同形不同因；滚动这一维从未被记录，
  `docs/BUG-LOG.md`、`START-HERE.md`、`NEXT-STEPS.md` grep「滚动」零命中（代理实测）。
- **建议方向**：优先把滚动量做成快速路径第 4 缓存分量（同 `lastLineHeight` 模式，每帧多读两个 int，
  不碰 P0-5 昂贵调用名单）；次选 `addVisibleAreaListener`——但新标志**必须进 `needsUpdate` 条件**
  （`:732-743` 记录了 inlay 第一版因只进顶层门而失败的原样教训）。
  探针模板照抄 `VInlay`/`VFontScale` 的「几何变了但 vp 没变 → 必须重算」。
- **证据边界**：缓存分量穷举、监听器不存在、坐标来源——代码层实证；
  `visualPositionToXY` 返回含滚动偏移的组件坐标——平台语义**推测**（代理注明，调度者认同其依据）。
  **最终观感需实机一验**（方法：长文件点行 → 滚 3 行 → 看拖尾是否停在旧位置），按任务书 §8.2 不做。

### [U-H-2] tick 链里有一处编辑器读取没包 read action，而守门探针 VReadAction 根本不跑 tick —— 252 教训的现行缺口

- **位置**：`ui/CaretAnimator.java:2541-2543`（`frameSummary` 实参在调用点求值）；探针盲区 `tools/settings-probe/VReadAction.java:152/162/189/234`
- **代码**：
    ```java
    CursorDiag.frameSummary(System.identityHashCode(this), frameSeq,
            editor.getCaretModel().getAllCarets().size(), diagTotal,
            false, anyAnimating, spread, measuredFps);
    ```
- **失败场景**：用户勾选「诊断日志」→ `diagFrame` 每 15 次慢路径为真 → 252 类平台在 `getAllCarets()`
  抛 `Read access is allowed from inside read-action only` → 该帧的 `computeTrailBounds`/`submitRepaint`
  （`:2546-2547`）不执行（四边形已清空重填、脏区未提交 = 丢一帧）→ `runTick` 只有 `finally` 没有 `catch`
  （`:944-955`，调度者已核），异常穿出 → **每约 15 帧复现一次、每秒约 8 条堆栈**，
  插件自己的日志零线索，观感 =「开了诊断反而抖」。
- **为什么是问题**：三重证据闭合——
  ① 本类自己的契约把 `getAllCarets()` 判为编辑器读取（`:1092-1100`、`:1115-1119` 两处显式
  `readEditor(...)`，注释原文「【平台 252】getAllCarets() 是编辑器读取」），包装却只覆盖 `refreshTarget()`；
  ② java-reviewer 用 javap 反编译本地 `intellij.platform.ide.impl.jar`：`CaretModelImpl.getAllCarets()`
  在 `monitorexit` 之后 `List.sort(CARET_POSITION_COMPARATOR)`，比较器逐次调 `Caret.getVisualPosition()` →
  `CaretImpl.assertNotUpdating + updateCachedStateIfNeeded`——正是历史冻结栈里那一条；
  ③ **守门盲区实证**：`VReadAction` 给 `getAllCarets`/`removeCaretListener` 挂满 `guard(...)` 代理
  （`:322/:328`），但驱动的只有 `refreshTarget`/`refreshTargetInReadAction`，
  **从不调 `runTick`/`tick`/`tickSpring`/`dispose`**（调度者复跑 grep 确认）——
  这就是陷阱 4「假通过」的现行样本：1035 PASS 里 `VReadAction` 12 条全绿，抓不到这一条。
- **发现视角**：**2 个视角同时发现** —— silent-failure-hunter（HIGH）+ java-reviewer（HIGH，并补 javap 证据链）
- **上次审计是否报过**：**新发现**。「平台 252 冻结已修」指的是 `readEditor` 包装的引入；
  本条是**包装覆盖面的缺口**，不是重报已修事实，也不违反「不许重报」清单。
- **建议方向**：这一行整体包进 `readEditor(...)` 或删掉 `getAllCarets().size()` 参数改用本帧已有的计数。
  配套（**需改探针，本轮未动，记入 8.7**）：VReadAction 增加驱动 `runTick`+`dispose` 的用例，
  修完做变异测试（把包装删掉必须红）。

### [U-H-3] 调度线程「连续失败 3 次就自愈」不会自愈 —— 恢复的唯一入口是「打开一个新编辑器」

- **位置**：`ui/RenderScheduler.java:215-224`（置 null + return）；重建入口 `:86-91`（`register`，仅由 `CaretAnimator:935` 构造时调用）
- **代码**：
    ```java
    if (consecutiveFailures >= 3) {
        synchronized (RenderScheduler.class) {
            if (thread == Thread.currentThread()) { thread = null; }
        }
        LOG.warn("... 已置空以便下次 register 重建");
        return;
    }
    ```
- **失败场景**：调度线程连续 3 轮抛异常 → `thread=null`、线程 return → 但 `ANIMATORS` 仍非空 →
  所有已打开文件的 `framePending=false`、`nextDueNanos` 早已过期，**没有任何人再遍历这个集合**。
  用户看到「所有编辑器光标拖尾整体死掉」；`wakeUp()`/caret 事件只 interrupt 一个 null。
  idea.log 里有 3 行 warn，但措辞「以便下次 register 重建」会让排查者以为它自己会好——
  实际恢复需要一次与故障无关的用户动作（开新文件）。
- **为什么是问题**：重试触发源不在故障现场。「下一次重试」机制的重试源必须永远还在——本条不在。
  影响面比原陷阱 9（单光标死）更大：这是**全体停摆**。
- **发现视角**：**2 个视角同时发现** —— java-reviewer（H2）+ silent-failure-hunter（H3），结论一致
- **上次审计是否报过**：**新发现**（0.9.88 修的是 invokeLater 抛出的标志回滚，没动这条自愈路径；
  任务书「要审修复覆盖面」的问题在这里给出了否定答案的一部分——覆盖面缺口不在回滚本身，在退出后的世界）。
- **建议方向**：return 前对每个现存 animator 重置 `nextDueNanos`（走 `scheduleNextDue()`），
  或线程退出时把所有 `nextDueNanos` 置过期 + `wakeUp()` 里检测 `thread==null` 就地重建（重建需拿锁，防双线程）。

### [U-H-4] 处理异常的 catch 体自己无人守护 —— LOG.warn 抛出即线程死亡、`thread` 字段停在死引用，连 U-H-3 的「开新文件」都救不了

- **位置**：`ui/RenderScheduler.java:212-214`（catch 体内的 `LOG.warn` 与字符串拼接）；对照 `:87-91`（`register` 只在 `thread==null` 时新建，非 null 走 `thread.interrupt()`）
- **代码**：
    ```java
    } catch (Throwable t) {
        consecutiveFailures++;
        LOG.warn("neovide-cursor: 调度线程本轮异常（连续 " + consecutiveFailures + " 次）", t);
    ```
- **失败场景**：catch 体内的 `LOG.warn`（或拼接）抛出——日志子系统处于 shutdown、平台 LoggingFactory 已被摘——
  异常穿出 `loop()` 的 run 边界 → 线程死亡，而 `thread` 字段**仍非 null**（置 null 只发生在空集分支与
  `>=3` 分支，本路径到不了）。此后每次 `register()` 走 else 分支 interrupt 一个已死的线程，
  **全体动画永久停摆且只剩最后一条 warn**。无 `Thread.setUncaughtExceptionHandler`（全包 grep 无）。
- **为什么是问题**：0.9.88 的修复把「循环体抛出」堵住了，但「处理抛出的代码抛出」是同一事故的第二扇门。
  触发概率推测（LOG.warn 抛的机会很小），**结构缺陷实证**。修法成本极低。
- **发现视角**：1 个视角（silent-failure-hunter，HIGH；java-reviewer 在其覆盖面分析里确认了
  「修复本体覆盖完整，唯一残留是线程死亡后不重建与 posted 覆盖」——与本条相邻但没抓到 catch 体自身）
- **上次审计是否报过**：**新发现**
- **建议方向**：catch 体再包一层 try + 给线程装 UEH（内嵌「若 `thread`==本线程则置 null」）。
  与 U-H-3 的修法合并成一次改动。

### [U-H-5] `install()` 的空 catch 吞掉「编辑器挂载失败」—— 该编辑器永久没覆盖层且零日志，默认配置下等于光标整个看不见

- **位置**：`ui/NeovideCaretManager.java:268-314`
- **代码**：
    ```java
    ANIMATORS.computeIfAbsent(editor, e -> new CaretAnimator(e, CONFIG));
    } catch (Throwable t) {
        // 单个编辑器挂载失败不应影响其他编辑器
    }
    ```
- **失败场景**：`CaretAnimator` 构造体内任一步抛出（`readEditor` 平台调用、`content.add(panel)`、
  `InlayModel.addListener` 都在覆盖范围内，`CaretAnimator:745-863`）→ catch 吞掉 →
  该编辑器**永远没有拖尾覆盖层**；而全局 `CARET_COLOR` 已被改透明（默认 `hideNativeCaret=true`）→
  **这个文件里光标彻底不可见**，idea.log 与独立诊断文件零线索。
  副产物（security-reviewer 独立发现）：若抛在构造中段，监听器已注册而 `ANIMATORS` 无记录 →
  `editorReleased` 取不到它 → **监听器连带 panel/content 泄漏，调度线程因 `ANIMATORS` 非空永不退出**。
- **为什么是问题**：这是本批 catch 盘点里后果最重的静默点：一次性动作、无重试、无返回值
  （调用点 `:179/:186/:653` 无从判断）。`CursorDiag.swallowed` 正是为这类点存在的，没接。
- **发现视角**：**4 个视角同时发现，严重度有分歧** —— silent-failure-hunter（HIGH）、
  code-reviewer（M-11，MEDIUM）、java-reviewer（L5，LOW）、security-reviewer（L-8，LOW）
- **严重度裁决**：**保持 HIGH**。理由：分歧在触发概率（构造中段抛，未实测到具体抛出点），
  不在后果——后果一旦兑现是「整编辑器光标不可见 + 无任何线索 + 对象泄漏」，
  且这条路径是本批唯一「失败后连诊断通道都建不起来」的点。按 `rules/common/code-review.md`
  「HIGH=bug 或显著质量问题」判。若实机长期测不到触发，可降 MEDIUM，但日志先接上是零成本。
- **上次审计是否报过**：**新发现**（0.9.87 P-13 立了「异常不许静默走 swallowed」的原则，
  6f7d585 按原则接了 8 处，本处是漏网的同类）
- **建议方向**：`LOG.warn(..., editor.toString())` + `CursorDiag.swallowed("installEditor", t)`，
  并让 `install()` 返回 boolean、`editorCreated` 汇总一行「本轮挂载失败 N 个」。
  构造中段泄漏的根修：`CaretAnimator` 构造改为「全部接线成功后才交出 this」或加构造失败自清理。

### [U-H-6] `applyNativeCaretHidden` 先记账后写色 —— 抛出即污染 `HIDDEN_SCHEMES`，此后永久早退，双光标不可救（非 force 路径）

- **位置**：`ui/NeovideCaretManager.java:712-739`
- **代码**：
    ```java
    if (savedCaretColor != null) {
        HIDDEN_SCHEMES.putIfAbsent(scheme, savedCaretColor);      // :732 先记账
    }
    scheme.setColor(EditorColors.CARET_COLOR, new Color(0, 0, 0, 0));  // :735 后副作用
    } catch (Throwable ignored) { /* 配色方案不可用时静默跳过 */ }     // :737
    ```
- **失败场景**：`:735` 抛出（只读 scheme、shutdown 期禁写色、scheme 被回收）→ 表里说这个 scheme
  已隐藏，实际 `CARET_COLOR` 一个字没改 → 早退判据 `HIDDEN_SCHEMES.containsKey(scheme)`（`:712`）
  读的就是这本被污染的账 → **此后所有非 force 调用在 `:714` 永久早退**。
  用户可见结果：`hideNativeCaret=true` 下原生光标与覆盖层叠成**双光标**（正是踩坑 #19 / 缺陷 4 的形态），
  再点多少次「隐藏原生光标」开关（走非 force 路径 `:395`）都救不回来，全程零日志。
  只有切主题（`CaretSchemeWatcher` 传 force）或重启才能压掉。反向产物：假「原色」记录会让
  `restoreNativeCaret()` 打出「恢复」语义但实际无操作。
- **为什么是问题**：记账与副作用顺序颠倒，且两者共用一个静默 catch——状态表一旦与真实世界分叉就无人纠账。
- **发现视角**：1 个视角（silent-failure-hunter，HIGH；调度者已复核代码顺序属实）
- **上次审计是否报过**：**新发现**（缺陷 4 的修复历史在 `:709` 附近注释里，但那条修的是 per-scheme 账本结构，没修顺序）
- **建议方向**：先 `setColor` 成功、再 `putIfAbsent`；catch 里 `CursorDiag.swallowed("hideNativeCaret", t)`。
  与 U-M-17/U-M-18 同属「原生光标隐藏链」，可一批修。

### [U-H-7] CursorDiag 自己的写文件失败是静默的，而 `[tick]/[geom]/[paint]/[boot]` 的唯一出口就是这个文件 —— 证据基础设施在最关键处撒谎

- **位置**：`ui/CursorDiag.java:64-94`（`PrintWriter` 创建与写入）；纪律自陈 `:514-518`
- **代码**：
    ```java
    if (fileWriter != null) {
        fileWriter.println(line);
        fileWriter.flush();       // 立即落盘，崩溃也能看到
    ```
- **失败场景**：`PrintWriter` 的契约就是吞 `IOException`、只置内部 error 标志；本类全文
  **没有任何 `checkError()`**（grep 0 处，调度者核）。磁盘满、文件被排查脚本以独占方式打开、
  轮转/删除后句柄失效——任一发生即所有后续写入静默丢弃，且 `fileWriter != null` 恒真、
  **永不重试创建**。而 `:514-518` 自己立的纪律 ① 是「boot/evt/tick/geom/paint/clear 只写独立文件，
  不走 LOG.info」——**这一整套首用追踪证据没有第二条通路**。用户把一份半截且无停更标记的文件
  发回来排查，结论建立在不存在的证据上。
- **为什么是问题**：CLAUDE.md 第十一节把「历史文档和实测数字大量失真」列为项目最大风险；
  这条是同一病根在日志基础设施上的缺口——日志体系自身的失败不可观测，比某条业务日志缺失更贵。
- **发现视角**：1 个视角（silent-failure-hunter，HIGH）
- **上次审计是否报过**：**新发现**
- **建议方向**：`flush()` 后周期 `checkError()`（每 N 行一次即可），失败则 `close()` 置 null 重建（限次）
  并向 idea.log 打一条「独立文件写入失败，[tick] 类证据已中断」——两条通路之一断了，必须让另一条知道。

### [U-H-8] `paintTrace` 的「光标消失」检测器每 animator 终身只响一次 —— 第二次进入该状态不留一行，缺失的证据会把排查方向带偏

- **位置**：`ui/CursorDiag.java:783-807`
- **代码**：
    ```java
    if (error != null) {
        if (tr.paintErrorLogged) return;
        tr.paintErrorLogged = true;
    } else if (earlyReturn) {
        if (tr.emptyPaintLogged || quads > 0) return;
        tr.emptyPaintLogged = true;
    ```
- **失败场景**：`:777-782` 注释明确 `quadCount==0` 是「覆盖层透明 + 原生光标被隐藏 = 光标消失
  在代码上的唯一形态」。但记录是终身去重：编辑器未布局完时那次正常空绘制会**消耗掉唯一的名额**，
  之后真故障（U-H-5/U-H-6 都能造成）进入同一状态时不再记录；绘制异常也只记首个类名，
  后续**不同类型**异常无痕。排查者按「`[paint]` 里没有 earlyReturn 行」反推「面板一直有内容」——
  方向被日志机制自己带错，比没有日志更糟。
- **为什么是问题**：本项目用这些日志当「证据」下结论（BUG-007 的判据流程就写着「没有 [edt] → 去查别处」）；
  去重策略让「没有」不再等价于「没发生」。
- **发现视角**：1 个视角（silent-failure-hunter，HIGH；调度者已复核分支唯一性属实）
- **上次审计是否报过**：**新发现**
- **建议方向**：改成**状态翻转才记**（记住上一帧是否空绘制/异常，false→true 与 true→false 各记一行），
  既无噪声又覆盖复发。与 U-H-7 同属诊断基础设施，一批修。

---

## MEDIUM（P2，28 条）

### [U-M-1] CONFIG 跨线程可见性（第 1 批 M-3 衔接）—— 确认，并收窄为「公开 API 无守门 + 探针在错误线程跑通反例」

- **位置**：`ui/NeovideCaretManager.java:49`、`:512-530`；读侧 `CaretAnimator` 多处 / `CaretTrailPanel` 多处
- **本批补的实测**（code-reviewer + java-reviewer 双视角交叉，结论一致）：
  `applyConfig()` 生产调用共 5 处（`CursorPresetApplier:74/139/201/229` + 面板 `NeovideCursorConfigurable`），
  **全部在 EDT**；非 EDT 分支（调用线程直接逐字段写 CONFIG）今天**只被探针触发**
  （`NonEdtProbe:175`、`VerifyApplyConfig` 多处——后者还在 `main` 线程上直接调 `animator.onModeChanged()`
  走 Swing 路径，全绿通过）。调度线程只读 4 个 volatile/Atomic 字段，**全仓不存在「调度线程读 CONFIG」**——
  上一批设想的读写交错面不存在。`editorCreated` 理论可在非 EDT 触发（`install():167` 的
  `syncStateToConfig()`），但同一情形下 `content.add(panel)` 本身已是 Swing 违规，CONFIG 不是那里最重的问题。
  新增第三种形态：`core/TrailCorner.java:294` 对非 volatile 的 `config.trailFactors` 做两次独立 load
  （`.length` 与 `[rank]`），理论可跨 load 见两个数组实例——因写侧每次整体新建、恒 4 长，
  越界与半构造不可达，后果仅档位混读一帧（推测）。
- **结论**：维持 MEDIUM（不升不降），风险面从「一帧跨字段不一致」进一步收窄为
  「公开静态 API 留给未来的口子且无任何守门」。修法收敛为一处：`CONFIG` 换 volatile 引用整体替换
  （不动 `NeovideConfig` 字段——D0 冻结），并把「必须 EDT」写成 `assertIsDispatchThread()` 或注释+探针契约。
- **发现视角**：2 个视角（java-reviewer 主、code-reviewer 佐证）｜**上次审计报过**：批 1 M-3（本条是其 ui 侧补全）
- **建议方向**：见 8.5。

### [U-M-2] `tickSpring` 每帧每光标 `new double[4]` ×2 —— 直接违反本类自写的「零分配热路径」承诺，而现成的复用数组躺在接收端

- **位置**：`ui/CaretAnimator.java:2507-2514`（弹簧路径）、`:2356-2363`（静态光标路径）、`:2408`（`groupMaxOffset` 每帧一建）；对照 `ui/CaretTrailPanel.java:55-57`（`ucX/ucY` 复用对，只有零调用的 `updateCorners` 在用）、承诺原文 `CaretAnimator:71`
- **代码**：
    ```java
    panel.addQuad(
            new double[]{ trail.corners[0].getCurrentX(), ... },
            new double[]{ trail.corners[0].getCurrentY(), ... },
            trailGlow);
    ```
- **失败场景（量化）**：单光标 @144fps → 288 个 `double[4]`/秒 ≈ 13.8 KB/s；
  8 光标 → 2304 个/秒 ≈ 110 KB/s，另有 `groupMaxOffset` 144 数组/秒、
  `allCorners()` for-each 每帧 1 个迭代器（`:2795-2810` 注释自称「不分配」但迭代器照分）、
  块选区时 `blockTrailKey` 每次 `Integer.valueOf(-1000000 - lineNo)`（恒出 Integer 缓存，每帧真新对象）。
  不足以单独掉帧（TLAB 分配），但 `addQuad` 是立即拷贝语义（`CaretTrailPanel:560-566`），
  复用两个成员数组即可归零——**违反的是自家判据**（`:76-78` 多光标 32 对象/帧的 GC 论述）。
- **发现视角**：2 个视角（code-reviewer MEDIUM 带量化；silent-failure-hunter 同一位置判 L2，取量化更足的一方）｜**新发现**
- **建议方向**：`CaretAnimator` 持 `quadScratchX/Y`；`groupMaxOffset` 扩容量复用；同批清 L 级三处小分配。

### [U-M-3] 静止后仍每帧 `computeTrailBounds + submitRepaint` —— 弹簧路径无门，同类残影路径有门，默认 `alwaysHighFps=true` 下空闲档不降频

- **位置**：`ui/CaretAnimator.java:2546-2547`（无条件）；对照 `:2624-2628`（`tickAfterimage` 的 `if (moved || hasActiveGhost)`）
- **失败场景（量化，代理估算+调度者核对分支结构属实）**：默认 `shadowBlurFactor=0.2`/`glowLayers=8`/行高 32px
  → 脏区约 37×67px，每帧 8 层 fill + 本体；取本项目基准「1 光标 8 层 fill 216µs」为上界 →
  **悲观 3.1% 单核、按面积折算 0.3%~0.9% 单核，144 次/秒 EDT 绘制任务，持续不断**。
  后果不是掉帧而是空转功耗与 EDT 常驻负载——项目已两次为同类空转付费并修复
  （`:1258-1263` 记录 5 个隐藏编辑器刷 720 次/秒 EDT 空任务）。
- **发现视角**：1 个视角（code-reviewer）｜**新发现**
- **建议方向**：`if (moved || anyAnimating || fading)` 再提交（与残影路径同构）；
  注意与 U-H-1 的修法相互作用——静止跳过后，滚动/几何变化必须走得到置脏路径。

### [U-M-4] `refreshFrameGap` 全仓没有任何一处 +1 —— `refreshTarget` 失败限流只打得出一行，之后永久静默，而 VReviewFixes 守的是文本不是行为

- **位置**：`ui/CaretAnimator.java:2219-2225`（读与清零）、`:2234`（声明）；探针 `tools/settings-probe/VReviewFixes.java:61-64`
- **代码**：
    ```java
    if (refreshFailureCount == 1 || refreshFrameGap >= FAILURE_LOG_EVERY) {
        refreshFrameGap = 0;
        LOG.warn("neovide-cursor: refreshTarget 抛异常（累计 " + refreshFailureCount + " 次）；若是确定性异常拖尾会永久冻结", t);
    ```
- **失败场景**：`grep -rn refreshFrameGap src tools` 实测仅 4 处（`:2202`/`:2220`/`:2221`/`:2234`，
  调度者复跑确认）——**恒为 0，`>=1000` 恒假**。确定性异常时每帧都炸，日志只有第一帧那一行，
  此后无论失败多少万次不再输出；排查者看到的是一条几小时前的 warn，无从判断现在还坏不坏。
  0.9.73 改造注释（`:2206`「不能静默吞掉——否则永久冻结且零线索」）的目的恰好没达到。
  `VReviewFixes` A-1 是源码文本断言（token 在就 PASS）——陷阱 4「假通过②：断言太弱」的现行样本。
- **发现视角**：1 个视角（code-reviewer）｜**新发现**
- **建议方向**：按失败计数节流（`refreshFailureCount % FAILURE_LOG_EVERY == 1`）最省事；
  修探针属契约变更（文本断言改行为断言），本轮不许动探针，记入 8.7。

### [U-M-5] `dispatchDue` 回滚把「本轮已成功投递」一并抹掉 —— B 反复 invokeLater 抛时 1kHz warn 洪水 + 拖慢 A 的实际帧率

- **位置**：`ui/RenderScheduler.java:261-291`（catch 里 `posted = false`）；后果链 `:203-209`
- **失败场景**：A 投递成功、B 抛 → `posted` 被无条件覆盖 false → `loop()` 按「本轮没投出去」睡 1ms →
  B 已回滚 `lastDispatchedDueNanos`，下轮再试再抛 → **约 1000 行 warn/秒进 idea.log**，
  同时调度线程被日志 IO 拖住、A 的帧节奏被殃及。`posted` 的 @return 语义是「本轮至少投了一个」，
  回滚应只回滚失败者、不重写本轮结论。
- **发现视角**：2 个视角、判级分歧 —— silent-failure-hunter（MEDIUM，算的是 warn 洪水）；
  java-reviewer（L1，只看「多睡 1ms」）。**汇总按 MEDIUM**，理由：warn 1kHz 的日志与 IO 后果
  显著大于「1ms 退避」，且该交错由代码路径唯一确定（非推测）。
- **上次审计是否报过**：**新发现**
- **建议方向**：catch 里不动 `posted`；warn 自身按 animator 限流。

### [U-M-6] 「到期但投不出去」没有升级检测 —— 1kHz 忙重算、零日志、与 `register()` 抢同一把类锁

- **位置**：`ui/RenderScheduler.java:203-210`（`!dispatchDue()` → sleep 1ms）+ `:246-263`（CAS 失败即 continue）
- **失败场景**：invokeLater 入队成功但 EDT 长时间卡死 → `framePending` 永不复位 →
  `dispatchDue` 恒 false → 调度线程以 1000 次/秒遍历 + CAS + `synchronized(RenderScheduler.class)`，
  与 `register()`/`wakeUp()` 抢锁。**全体光标停 + 一条本该省 CPU 的守护线程变成纯 CPU 占用，日志零升级**。
  `EdtWatchdog` 的存在本身就证明「EDT 卡 390ms」是真实事故类别；卡死 2s 就触发本场景。
  前提（EDT 永久卡死）为推测，机制为实证。
- **发现视角**：1 个视角（silent-failure-hunter）｜**新发现**
- **建议方向**：仿 `consecutiveFailures` 加「dispatchDue 连续 false N 轮 → 退避升级 + warn 一次」。

### [U-M-7] `forceResync`/`inlayGeometryDirty` 在慢路径开头被消费，后半段抛出即「标志已清、活没干完、缓存已标」——静止查看时无人再触发重算

- **位置**：`ui/CaretAnimator.java:1783-1786`（清零）→ `:2076-2077`（`markAt` 先于全部收尾）→ `:2205-2227`（catch 吞后 `return false`）
- **失败场景**：inlay 撑高行 → 慢路径给主光标 `markAt` 后某句抛出 → 标志已清、戳已标 →
  下一帧顶层 `isAt(...)` 判据成立直接走快速路径 → **分组号、glowPadding、cursorWidth/Height 停在旧值，
  且此后连异常都不再产生**（不再进慢路径）。唯一重试源是下一次光标事件/行高变化/inlay 事件——
  用户静止查看时永不存在。`:2213` 那句 warn 写「若是确定性异常拖尾会永久冻结」，这次正是不确定性异常造成的永久冻结。
- **发现视角**：1 个视角（silent-failure-hunter）｜**新发现**（是 U-H-1 的同族：快速路径判据早于副作用完成）
- **建议方向**：把 `markAt` 挪到慢路径成功尾部（或失败路径回滚戳位）；清零标志同理。

### [U-M-8] 块选区 `getLineNumber` 抛出被空 catch 吞 → 选区 key 退化为 offset → **每帧重建 N 条 SpringTrail**——`:1919-1925` 注释声称已修的那个 bug 的复现路径

- **位置**：`ui/CaretAnimator.java:1939-1944`（吞）+ `:417-421`（fallback key）+ `:2159`（死条目清扫）
- **失败场景**：Alt+列选期间文档被并发修改 → `getLineNumber` 抛 → `lineNo=-1` → key 用会变的 offset →
  每帧 `trails.get(key)` 全 miss → 每帧 `new SpringTrail` ×N（旧条目被帧号清扫）→ 入场动画丢失、
  每帧 N×4 个 TrailCorner 分配，症状正是注释里记录的「最后一行输出、其他不动、tab 像重定位」——
  **四-J 第 5 条：同一 bug 第二次出现，说明前一次是补丁**。日志零痕迹。
- **发现视角**：1 个视角（silent-failure-hunter）｜**上次审计**：0.9.87 时代同类（`:1919-1925` 记录在案），本批是**残余触发路径**
- **建议方向**：catch 接 `swallowed`；`lineNo=-1` 时沿用上一帧 key（offset 兜底只在首见时用）。

### [U-M-9] caret 事件里为一条可能被丢弃的日志白付两次平台读取，且这次 `readEditor` 外面无任何 try —— 抛出会穿出平台 CaretListener 多播器

- **位置**：`ui/CaretAnimator.java:1092-1101`（`caretAdded`）、`:1115-1120`（`caretRemoved`）
- **代码**：
    ```java
    int[] counts = readEditor(() -> new int[]{
            editor.getCaretModel().getAllCarets().size(),
            editor.getCaretModel().getAllCarets().size()
    });
    ```
- **失败场景**：(a) `CursorDiag.caretCountChanged` 的 `enabled()` 在被调方才判（`CursorDiag:440`），
  诊断关着时这**两次 `getAllCarets()`（每次整表复制 + 排序，java-reviewer javap 实证内部 `List.sort`）
  + 一个 `int[2]` 照付**——违反本文件 `:674` 自立的纪律；(b) 这个 `readEditor` 外无 try（同方法的
  `traceCaretEvent` 在 `:893-905` 就包了），编辑器销毁瞬间抛出会穿出 `caretAdded` → 进平台多播器 →
  可能连带中断后续监听器与该次编辑操作。
- **发现视角**：分歧登记——silent-failure-hunter（MEDIUM）；java-reviewer（L4，同一行，判 LOW 并注明
  「注释已承认改一次取值会改掉诊断契约，属知情取舍，但契约值得重定义」）；code-reviewer（**未报**，
  理由「知情取舍」）。**汇总按 MEDIUM**，主要判据取 (b)（无保护抛出穿出多播器是结构性问题，非取舍）；
  (a) 的重复调用本身按分歧处理为：契约该重定义（`guard()` 探针要的是一次读值，可以一次读值两处用）。
- **上次审计是否报过**：**新发现**
- **建议方向**：一次取值、diff 日志用同一份；外面包 try 接 `swallowed`。

### [U-M-10] IME 分支把 `editor.offsetToXY()` 当日志实参重算一遍，且在 try 之外、诊断关着也执行；抛出即打断整条慢路径

- **位置**：`ui/CaretAnimator.java:1745-1776`；昂贵调用自陈 `:1612-1614`
- **代码**：
    ```java
    CursorDiag.imeSkipPicked(imeSkipFrames, p.x, caretOffset,
            editor.offsetToXY(caretOffset).x);   // :1776 —— byOffset 明明已算过，出了 try 就丢了
    ```
- **失败场景**：IME 组合期每被拦一帧白做一次 `offsetToXY`（本类点名三大昂贵调用之一；
  `IME_MAX_SKIP_FRAMES=15` 封顶）；若这次换算抛出 → 落到 `:2205` 被吞 → 慢路径 `return false`——
  **一条诊断日志把动画打停**（叠加 U-M-7 后连重算都不再有）。用户报的「`f;f;` 全角输入撕裂掉帧」
  正压在这条路径上（单次成本推测，调用结构实证）。
- **发现视角**：2 个视角（code-reviewer M-4、silent-failure-hunter M6）｜**新发现**
- **建议方向**：`Point byOffset` 提到 try 外；整段包 `if (CursorDiag.enabled())`
  （同类先例就在本文件 `:2125 geometryWanted`）。

### [U-M-11] `CursorDiag.enabled()` 三连：每帧 2~5 次全局属性表锁 + 服务定位；`catch→false` 让 swallowed 通道在最需要证据时一起哑掉；watchdog 线程非 EDT `getService` + 跨线程读非 volatile `diagLogging` 可**长期读到 false**

- **位置**：`ui/CursorDiag.java:111-128`；调用点 `CaretAnimator:2492/918`、`CaretTrailPanel:770/795`、`CursorDiag:581`；watchdog 侧 `EdtWatchdog:122-124`
- **失败场景**：(a) 每次调用过 `Boolean.getBoolean`（JVM 全局 `props` 锁）+ `getService().getState()`，
  144fps 下单编辑器约 **430~720 次/秒**（调用点数实证，锁竞争成本推测）；
  (b) IDE 重启/服务未就绪期 `getState()` 抛 → 返回 false **且不留痕迹** → 含 `swallowed()` 在内全部
  观测通道静默全关，而 State 里 `diagLogging` 仍是勾选态——用户以为开着；
  (c) 看门狗线程与 EDT 无 happens-before → **`[edt]` 行（BUG-007 唯一要看的那行）可能永不出现**。
- **发现视角**：**3 个视角同时发现** —— code-reviewer（M-7）、silent-failure-hunter（M10）、java-reviewer（M2）｜**新发现**
- **建议方向**：`volatile boolean` 缓存 + 设置变更点失效；catch 里 `LOG.warn` 一次（限流）；
  watchdog 读不到就写不成的问题随缓存一并解决。

### [U-M-12] `CursorDiag.writeFile` 的 `fileWriter/fileTried` 跨线程无守卫 —— 看门狗与 EDT 并发创建会**二次 TRUNCATE 抹掉刚采的证据**，第一个句柄泄漏，行还会撕裂

- **位置**：`ui/CursorDiag.java:64-88`；并发写手 `EdtWatchdog:122-124`
- **代码**：
    ```java
    if (!fileTried) { fileTried = true; ... fileWriter = new PrintWriter(... TRUNCATE_EXISTING); }
    ```
- **失败场景**：全类 `synchronized/volatile` 出现 0 次（grep 实测，调度者复核）。看门狗只在
  「EDT 被占 >200ms」时输出——**正是 EDT 同时也在刷 `[tick]/[paint]` 的时刻，并发是设计场景不是巧合**。
  check-then-act 竞态：两线程都进创建分支 → 第二个以 `TRUNCATE_EXISTING` 打开把第一个 writer
  未落盘缓冲整体抹掉，第一个 `FileOutputStream` 无人 close（句柄泄漏）；`println` 两次底层调用交错出撕裂行。
- **发现视角**：2 个视角（silent-failure-hunter M9、java-reviewer M1）｜**新发现**
- **建议方向**：`writeFile` 整体 `synchronized（CursorDiag.class）`（低频路径，不心疼），
  或创建进 `static {}`；配合 U-H-7 的 checkError 一并改。

### [U-M-13] `TRAIL_BUDGETS`/`SAMPLE_STATE`/`TRACES` 三张按 `identityHashCode` 分桶的 static 表**从不回收** —— 无界增长，且对象 GC 后哈希复用让新 animator 继承旧预算/「已报」标志

- **位置**：`ui/CursorDiag.java:165/423/558`；dispose 侧无注销（`CaretAnimator:2849-2882` 不碰这三张表）
- **失败场景**：`:422` 注释自己承认「弱引用清不掉，于是选择不处理」→ 编辑器开合一次多一组条目。
  增长速率本身低（security-reviewer 实测估 ~225KB/小时，判 LOW），**但哈希复用侧有真实后果**：
  旧 animator 死后其 identityHashCode 可被新对象复用 → 新 editor 继承用完的 5000 行额度/
  FIRST-EDIT 标志 → 「这个窗口的 `[trail]` 日志莫名一行不出」。本类 `:408-424` 刚因同形态串台事故改过
  采样字段，表 key 这半边漏了。（security-reviewer 用 jshell 20 万次采样否掉了「identityHash 为负」
  的怀疑，结论按其记录：负值场景不存在，不立。）
- **发现视角**：**3 个视角** —— silent-failure-hunter（M14）、java-reviewer（M3）、security-reviewer（L-1，仅内存量级）
- **上次审计是否报过**：**新发现**
- **建议方向**：`CaretAnimator.dispose()` 里按 `System.identityHashCode(this)` remove 三个键
  ——回收点现成，改动 <10 行。

### [U-M-14] `glowLayersNow` 是 static，被每个 animator 每帧覆写、被别的 animator 读 —— `[frame]` 行的 `layers=` 报的是别人的值，这是本类定义过的「日志撒谎」形态重演

- **位置**：`ui/CursorDiag.java:405-406`（字段）+ `:337`（读）；写点 `CaretAnimator:2371/2489`
- **失败场景**：勾「多光标不辉光」+ 开两个编辑器（A 单光标 8 层、B 双光标 0 层）→ A 的 `[frame]`
  可能打 `layers=0` → 排查「辉光怎么没了」的人朝错方向查。同一批 static 计数器里
  `lastSampleSeq/lastSampleNs` 0.9.81 已按 animator 身份隔离（`:408-424` 十六行注释），
  **`glowLayersNow` 是唯一漏改的那个**；且它在诊断关闭时也被无条件写。
- **发现视角**：2 个视角（code-reviewer M-9、java-reviewer M10）｜**新发现**
- **建议方向**：作参数传进 `frameSummary`（隔离机制现成），删 static；赋值挪进 `if (diagFrame)`。

### [U-M-15] `EdtWatchdog` 三重失效：只在**新建 animator** 时才启动；配额（20 次）耗尽或 EDT 关闭后**同一 JVM 永不重启**（`started` 单向 true）；javadoc 承诺「抓够 MAX_DUMPS 次线程自行退出」而循环条件让它在 EDT 健康时**20 次/秒常驻永不退出**

- **位置**：`ui/EdtWatchdog.java:60-112`；启动点 `ui/CaretAnimator.java:860-862`
- **失败场景**：(a) 用户设置里勾「诊断日志」但不开新文件 → 看门狗根本不跑，`[edt]` 零行被读成
  「EDT 没被占住」（BUG-007 判据直接反）；(b) 启动期噪声先吃掉配额（`:48-51` 注释自认）→
  真正复现那一下没配额，再关再开诊断也不会重建；(c) EDT 健康时它每秒往 EDT 投 20 个任务——
  **它自己就是被测 EDT 上的负载**，把 `dispatchLag` 读数拉悲观（方向推测），且与用户「不喜欢常驻后台进程」
  的全机偏好直接冲突。另：`sleepQuietly` 捕获中断后不设退出位，若被打断则 2s 等待窗变满 CPU 忙等（推测，当前无人打断）。
- **发现视角**：**3 个视角同时发现** —— code-reviewer（M-8）、silent-failure-hunter（M7）、java-reviewer（M6）｜**新发现**
- **建议方向**：`enabled()` 变为 true 时也拉一次 start；空闲 N 秒自动退出并把 `started` 复位；
  文档「自行退出」要么兑现要么删。

### [U-M-16] `installSchemeWatcher()` 的 boolean 返回值在 3 个调用点全被丢弃 —— 第 1 批 H-2「返回值被丢弃」模式在 ui/ 的新实例

- **位置**：`ui/NeovideCaretManager.java:153/394/644`（调用）+ `:751-753`（方法）；静默根 `ui/CaretSchemeWatcher.java:83-87`（catch 无日志）
- **失败场景**：消息总线不可用/启动早期 → `install()` 返回 false 且**零日志**，三处调用点不看返回值；
  `connection==null` 使每个新建编辑器都会重试（缓解），但若 `:394/:644` 两次重试也失败 →
  主题监听永不装 → 用户切主题 → 缺陷 4（原生光标复活、双套绘制）静默复发。
  `isInstalled()` 生产侧零读者。javadoc 明写「@return true = 本次真正建立了订阅」——声明了契约，没人消费契约。
- **发现视角**：2 个视角（code-reviewer M-10、silent-failure-hunter M8）｜**上次审计**：批 1 H-2 同模式（不同位置）
- **建议方向**：catch 接 `swallowed("installSchemeWatcher", t)`；调用点至少对 false 记一行。

### [U-M-17] `restoreNativeCaret` 九处 catch 全静默，「留在表里等下次重试」的重试源是**用户亲手再拨一次开关**

- **位置**：`ui/NeovideCaretManager.java:858-909`
- **失败场景**：`setColor` 对某旧 scheme 恒抛（只读/已回收，前提推测）→ 条目永久留在 `HIDDEN_SCHEMES` →
  该 scheme 的 `CARET_COLOR` 停在全透明 → 用户切回那个主题时光标不可见且「关不掉」
  ——正是 `:811-816` 注释想根治的现象，本次连「尝试过」都不留（外层 `:907` 也静默）。
  调用点只有 `install()/setHideNativeCaret(false)/setEnabled`——不碰开关就永远没有下一次。
- **发现视角**：1 个视角（silent-failure-hunter）｜**新发现**（与 U-H-6 同链）
- **建议方向**：失败条目首次记 `swallowed`；连续失败 ≥3 次的条目按「账本与事实分叉」处理（删账 + warn）。

### [U-M-18] `handleSchemeChange` 静默吞异常 = 缺陷 4 唯一兜底路径失败无痕（且与 U-H-6 叠加成双层静默）

- **位置**：`ui/CaretSchemeWatcher.java:112-124`；第二层在 `NeovideCaretManager:737`
- **失败场景**：切主题 → force 重入里 `setColor` 抛 → 被两层 `catch (Throwable ignored)` 各吞一次 →
  新 scheme 透明色没写上 → 双光标永久存在，日志零字。
- **发现视角**：1 个视角（silent-failure-hunter）｜**新发现**
- **建议方向**：内层或外层各接一次 `swallowed`；与 U-H-6/U-M-17 一批修（同一条链）。

### [U-M-19] `dispose()` 里摘 caret 监听器没包 read action、抛出被吞 —— 与注册侧口径直接矛盾，探针从不跑 dispose 所以全绿

- **位置**：`ui/CaretAnimator.java:2864-2868`；矛盾对照 `:801-805`（注册侧注释原文「getCaretModel() 本身是编辑器读取，注册动作也放进同一个 read action」）；盲区 `VReadAction`（调度者复核：驱动面只有 refreshTarget 系）
- **失败场景**：252 类平台关闭编辑器时 `getCaretModel()` 抛断言 → 吞掉 → **CaretListener 永久挂在
  正在销毁的 CaretModel 上**，animator/panel 连带不可回收；`disposed=true` 让后续事件空转，
  所以是「漏摘」不是「报错」，无痕。U-M-9(b) 的穿出多播器场景在此串联发生。
- **发现视角**：2 个视角（silent-failure-hunter M13、java-reviewer M4）｜**新发现**
- **建议方向**：注销进 `readEditor`；探针补 dispose 用例（本轮不许动探针，记 8.7）。

### [U-M-20] `CaretSchemeWatcher` 「回调在 EDT」的注释假设被 javap 否证 —— TOPIC 无 `immediateDelivery`，而总线投递**不改线程**；非 EDT 发布者会让 `setColor` 写到全局 scheme 中途态

- **位置**：`ui/CaretSchemeWatcher.java:28-30`（假设）→ `:112-124`（后果：非 EDT 调 `applyNativeCaretHidden(true)` 写全局 `EditorColorsScheme`）
- **失败场景**：任何非 EDT 发布 `EditorColorsManager.TOPIC`（后台 reloadSchemes、第三方插件 setScheme——**存在性推测**）
  → `handleSchemeChange` 跑在该线程 → 与 EDT 的 `refreshStyles()`/`parse(shadowColor)` 并发读写同一 scheme。
  证据：java-reviewer javap——`MessageBusImpl` 字节码中 `invokeLater|assertIsDispatchThread` 命中 0，
  `immediateDelivery` 只决定锁冲突时是否延后，不改投递线程（依赖本地 idea-libs jar 版本，记 8.6）。
- **发现视角**：1 个视角（java-reviewer）｜**新发现**
- **建议方向**：回调首句 `assertIsDispatchThread()` 或包 `invokeLater`；注释按实测重写。

### [U-M-21] 插件无 unload/disable 收尾钩子 —— `RenderScheduler.thread`（daemon，强引用 `ANIMATORS` 全体 animator）、`CaretSchemeWatcher` 总线连接、`ANIMATORS` 静态表都没人摘

- **位置**：`src/main/resources/META-INF/plugin.xml`（无 `Disposable` 挂点——范围外文件，按任务书看调用点）；静态存活物 `RenderScheduler:61/79`、`NeovideCaretManager:46`
- **失败场景**：动态卸载时编辑器仍开 → `editorReleased` 不再派发 → `CaretAnimator.dispose()` 永不执行 →
  调度线程继续往 EDT 投已卸载插件类的 `runTick`，覆盖层继续画，classloader 无法回收。
  触发前提是「用户动态卸载/禁用插件」——本项目日常是 install.ps1 整体重启 IDE，**概率低但后果明确**。
- **发现视角**：1 个视角（java-reviewer）｜**新发现**
- **建议方向**：加 `@Nullable Disposable` 的服务挂点或 `PluginSet` 监听，统一走现有 `setEnabled(false)` 清理链。

### [U-M-22] 构造期 `this` 逃逸 —— `startScheduler()`/`addCaretListener`/`InlayModel.addListener` 都在构造器返回前把 `this` 交给别的线程/持有者

- **位置**：`ui/CaretAnimator.java:848`（register）、`:802-805`（caret 监听）、`:816-837`（inlay 监听捕获 this）
- **失败场景（全程推测）**：调度线程在构造器未返回时被 `wakeUp()` 打断并投递 `runTick` →
  另一线程读 `config/panel/afterimageTrail` 等 final 字段可能拿到 null。
  实际窗口小（`nextDueNanos` 初值 +120ms、register 只 interrupt），**是结构性逃逸点而非当下事故**；
  它与 U-H-5 同族（构造中段抛出时，监听器已经在别人手里）。
- **发现视角**：1 个视角（java-reviewer）｜**新发现**
- **建议方向**：与 U-H-5 一起修——构造尾部统一接线（或 `init()` 两段式），一次性消掉「逃逸+半构造」两个面。

### [U-M-23] `panel.setGlowStrength(effectiveGlow)` 每帧写入一个生产侧无人读取的字段——陷阱 2「⑤消费者」在绘制侧的反向样本，且 javadoc 声称它在生效

- **位置**：`ui/CaretAnimator.java:2441`（每帧写）；`ui/CaretTrailPanel.java:420-427`（javadoc「为 0 时完全不绘制光晕」）、`:932`（`drawQuadGlow` 实际读逐四边形槽位）、`:529`（唯一读者在零调用的 `updateCorners` 里）
- **失败场景**：`updateCorners` 生产零调用（0.9.87 P-15 就报了它，grep 复核属实）→ 全局 `glowStrength`
  的读者只剩**设置页预览窗的另一个面板实例**；生产每帧写 + javadoc 说它会熄灭辉光 →
  下一个按 `:424` 文档去调辉光的人改完画面毫无变化——正撞陷阱 2。
- **发现视角**：1 个视角（code-reviewer）｜**上次审计**：0.9.87 **P-15 已报 `updateCorners()` 零调用**；
  「每帧写无人读 + 注释撒谎」的组合是新证据
- **建议方向**：把 `setGlowStrength`/`updateCorners`/`ucX/ucY` 整体标注「预览与探针专用」并从每帧链摘除，
  或让单光标的 `addQuad` glow 实参真正经这个字段——二选一，别维持现状（四-J 第 4 条）。

### [U-M-24] `applyConfigOnEdt` 在 setter 链失败后仍无条件打「applyConfig 完成」—— 批 1 H-2 的「失败仍报成功」同型

- **位置**：`ui/NeovideCaretManager.java:573-629`
- **失败场景**：S2..S6 任一 setter 抛（其内部就有 U-H-6）→ catch 一条 warn → 继续 → 末尾
  `LOG.info("applyConfig 完成（animators=…）")` 带全套配置值 → 用户「改了没生效」，翻日志找到一行
  **声称完成**的记录，方向被带偏。`:619` 注释「下一次正常 tick 会自己接上」在 U-H-3 死状态下恰好不成立。
- **发现视角**：1 个视角（silent-failure-hunter 原判 **HIGH**）
- **严重度调整**：**原判 HIGH，本报告改为 MEDIUM，理由**：每个抛出点自身有 `LOG.warn`（批 1 H-2 是全链零痕迹），
  本条的实际问题是「末行语义误导」而非「失败不可观测」——可观测性受损，但受损方式是方向性的。
- **上次审计是否报过**：批 1 H-2 同模式（不同位置）
- **建议方向**：失败计数收进局部变量，`完成（失败=N）`，N>0 用 warn。

### [U-M-25] `tick()` 抛出既无计数也无限流 —— 与 `refreshTarget` 的待遇不对等；确定性异常时 125~144 次/秒堆栈外抛

- **位置**：`ui/CaretAnimator.java:944-955`（runTick 只有 finally）；对照 `:2219-2225`（refreshTarget 有计数限流）
- **失败场景**：tick 链上任何一步持续抛（U-H-2 被关掉诊断后此面仍在，只是低频）→ 每帧一条异常
  冲进 IDE 错误记录，插件自己一行不记；`SpringTrail.jumped` 在 `assignRanks()` 之后才清
  （`SpringTrail:205-208`）→ 确定性抛出时 `jumped` 恒 true，每帧重排每帧抛。
- **发现视角**：1 个视角（silent-failure-hunter）｜**新发现**
- **建议方向**：runTick 加计数+首/1000 限流（复用 U-M-4 的正确节流写法一起修）。

### [U-M-26] 独立诊断文件写在 **config 目录**（Windows = 漫游 `%APPDATA%`）而不是 log 目录 —— 编辑行为时间线随「导出设置/漫游/备份代理」外流

- **位置**：`ui/CursorDiag.java:49-62`（`PathManager.getConfigPath()` + `neovide-cursor-diag.log`）
- **失败场景**：`emit()` 双写：idea.log（LOCALAPPDATA\log）+ config 根目录同名文件。后者内容
  （逐次按键的 `vp=行:列`、offset、modificationStamp 时间序列）会被
  Manage IDE Settings → Export、企业 Folder Redirection/UE-V、常规配置备份整目录扫走。
  不含代码文本（安全视角核对 8 文件零 `getText/getChars`），但足以推断代码结构与键入节奏；
  且它不在任何「清日志」的常规路径上——用户以为删了，其实还在漫游。
- **发现视角**：1 个视角（security-reviewer）｜**新发现**
- **建议方向**：换 `PathManager.getLogPath()`，一行改动。

### [U-M-27] 「5000 行上限」只接到 2/10 条 emit 出口——`[frame]`/`[caret]`/`[pick]` 等 8 类无闸门；独立文件无总量/字节/行长上限、不滚动

- **位置**：`ui/CursorDiag.java:151/:192`（TRAIL 闸门）、`:585`（TRACE 闸门）；未接闸门的出口 `:335`（frame）、`:373`（caret，**在逐光标循环内，一次采样 N 条**）等
- **失败场景（量化，常量取自代码）**：diag 开启时活跃 125fps、每 15 帧采样：单光标 ≈17 行/秒，
  8 光标 ≈75 行/秒（≈12 KB/s ≈ **45 MB/小时**）；`sample=1` 系统属性把 `[caret]` 放大到 125 行/秒/光标，
  `[trail]` 封顶后**其余行照旧刷**——**磁盘被无上限的 `[frame]`/`[caret]` 占满，恰好是这个闸门想避免的事**
  （类注释 `:133-136` 自陈动机）。空闲档整夜 ≈5 MB；一周不关 IDE 过 GB；`flush()` 每行摊在 EDT。
- **发现视角**：1 个视角（security-reviewer）｜**新发现**。上限本身实现干净——
  silent-failure-hunter 专项核查确认无「到上限继续 add 再 remove」退化、满闸时不再拼字符串——两条结论合并记录。
- **建议方向**：`emit()` 总闸门 + 文件字节上限/滚动；`[frame]/[caret]` 接现有 per-animator 预算。

### [U-M-28] 预设名日志注入：`LOG.info("已应用自定义预设 " + name)` 默认开启无 diag 门，而 `isValidName` **放行 0x0A/0x0D、无长度上限** —— 可在 idea.log 伪造记录；**主 sink 在 8 文件之外**

- **位置**：范围外 `ui/CursorPresetApplier.java:124/134/140`（sink）+ `settings/CustomPresets.java:562-564`（`isXmlLegal` 放行换行）+ `:525-542`（`isValidName` 无长度上限）；8 文件内唯一未净化的动态文本是异常 message（`CursorDiag:498/:802-806`，diag 门后）
- **代码**：
    ```java
    static boolean isXmlLegal(char c) {
        if (c == 0x09 || c == 0x0A || c == 0x0D) {
            return true;                       // 制表 / 换行 / 回车：XML 允许
    ```
- **失败场景**：预设名 `A\n<伪造的 ERROR 行>` → 每次点这个预设，idea.log 多一条无时间戳前缀的第二行。
  两层后果：(a) 本仓取证链被污染——`build/analyze-log.ps1` 等按 `\n` 切行正则解析，伪造行会被当真实事件；
  (b) 日志被喂给 AI/他人排查时，这是用户可控文本进入分析器的通道（导入他人设置包时可跨机传播）。
  长度方向：一万字预设名 → 一次点击三条超长行 + XML 每次落盘整份存。
- **发现视角**：1 个视角（security-reviewer，MEDIUM）。8 文件内其余可控源逐条核对**全部不可注入**
  （字面量 reason/枚举名/`sanitizeColor` 校验过的 hex/`head()` 定长——其证据清单已复核格式属实）。
- **上次审计是否报过**：批 1 决策 3 只裁了 emoji（合法性判定 `isXmlLegal` 未动）；日志注入是**新维度**。
- **建议方向**：sink 在 `CursorPresetApplier`（第 4 批范围）与 `CustomPresets`（settings 层）——
  输出前替换控制字符 / `isValidName` 拒 0x0A/0x0D + 设长度上限；8 文件内只需给异常 message 截断。
  **登记为跨批线索，本轮不在 ui/ 内修。**

---

## LOW（P3，13 条，登记从简）

### [U-L-1] `assignRanks()` 同函数两套分配策略：`idxScratch` 特意复用并配 15 行注释，`alignment` 每次新建
`ui/SpringTrail.java:235` vs `:269-289`。量级微不足道（打字 ~10 次/秒）；注释的理由已被同函数
`:291-299` 的手写插入排序否证。｜code-reviewer｜新发现｜建议：并入 U-M-2 一次清。

### [U-L-2] 四角顺序表存在三份，其中 `CaretAnimator:232-238` 的 `CORNER_RELATIVE` **零读者**
活表在 `SpringTrail:33-38`（逐字相同），第三份 `tools/TrailParityTest:45` 的注释还指向已死的那份。
角点顺序是「错了就镜像」的不变量，守它只靠注释。｜code-reviewer｜新发现｜删死表。

### [U-L-3] `System.identityHashCode(this)` 三处各算（`:251` 已有 `traceId`，`:2496`、`:2541` 又各算一次）
三处必须是同值（三张表按它分桶）却没有类型保证。｜code-reviewer + java-reviewer｜新发现｜统一用 `traceId`。

### [U-L-4] `activeTrails` 为空时在「不可见降频」逻辑**之前**就 return——0.9.100 修的隐藏编辑器空转在另一分支复现
`CaretAnimator:1248-1270`：`no-trail` 分支不动 `currentDelayNs`/`wasHidden`。低频触发。｜silent-failure-hunter｜新发现。

### [U-L-5] `updateGlowRadius` 的 0.5px 迟滞以当前值为基准，小步长改动被整段吞掉
`CaretTrailPanel:490-496`：「拖了滑块画面没反应」类反馈的候选解释，无日志。｜silent-failure-hunter｜新发现。

### [U-L-6] `drawQuadGlow` 对 NaN 辉光静默不画；同文件 `buildRgbWashPaint:285` 有显式 `isFinite` 守卫，这里漏了
`CaretTrailPanel:939-950` + `addQuad:564` 的 `glow<0f?0f:` 钳位对 NaN 不生效。上游产 NaN 才可达（批 1 M-4/M-5 已堵主要源头）。｜silent-failure-hunter｜批 1 M-4 同族残余。

### [U-L-7] 诊断文件固定名 + `TRUNCATE_EXISTING` + 无独占创建：双 IDE 实例（主仓+worktree 并行调试是本仓常用姿势）会互相截断证据；反射失败回退 `java.io.tmpdir`
`CursorDiag:56-79`。且 `fileTried` 已置 true 后永不重开永不报错。｜security-reviewer｜新发现｜加 PID 后缀或 `EXCLUSIVE`。

### [U-L-8] `[font-scale]`/`[ime-skip]` 只走 `LOG.info` 不进独立文件——与 `:515` 自家纪律相反，且不受任何行数上限
按项目流程只回收独立文件的人，查「全角撕裂」时看不到 `[ime-skip]`，会被「缺证据」反向带偏。
｜security-reviewer｜新发现。

### [U-L-9] EDT 栈（14 层类名+方法名）写进「生来就是为了被复制到别处」的诊断文件
外发时暴露同机第三方插件包名/用户自研插件内部结构；不含代码文本。属流程提示，不建议改实现。
｜security-reviewer｜新发现。

### [U-L-10] `drawQuadGlow` 重复取值、`beginQuads`/`addQuad` 双处置位等每帧微冗余
`CaretTrailPanel:538-541/567`、`:921/925` 两次 `currentGlowLayers()`、`:327` 每帧读 alpha。
单帧百纳秒级（推测），合并的意义在「辉光强度由哪些量算出来」只剩一处说明。｜code-reviewer｜新发现。

### [U-L-11] 注释/文档过期清单（8 处，批 1 L-10 同一病根，数字与路径没守卫）
① `CaretAnimator:269-281` 与 `:282-297` **同一字段挂两块互相矛盾的 javadoc**（一块说必须 IdentityHashMap、
一块说必须 HashMap，现实是 HashMap——读第一块的人会把它「改回去」并重新引入 U-M-8 那个 bug）；
② `refreshGlowPadding` 的说明飘到别的字段头上（`:2779-2795`）；③ `:2785`/`NeovideCaretManager:493`
描述一条**全仓不存在**的早退语句（`newCenterX` 只在注释里存在）；④ 静态光标「跳过 refreshTarget」
两个断言一假一反；⑤ 注释内行号引用过期两处；⑥ **sleep 精度三个互斥说法**
（`:62` 1.56ms / `:107` 15.6ms 并据此定档 8ms / `RenderScheduler:167` 221~223fps 说明粒度足够——
三者不可能同时对）；⑦ `CaretTrailPanel:42-54` 等悬空注释组（0.9.74 事故的成因类型）；
⑧ `CaretSchemeWatcher:35-36` + `NeovideCaretManager:643` 描述的 dispose 回收路径生产不存在
（`dispose()` 零调用者，grep 实测）。范围外顺带登记：`plugin.xml` 描述仍写「不支持多光标」「11 个滑块」。
｜code-reviewer + java-reviewer｜批 1 L-10 同族｜修法同批 1 建议：数字类注释删数或由探针守。

### [U-L-12] `frameSeq` 只在慢路径递增，而 `CursorDiag` 文档与 `shouldSample` 语义按「帧」
实际采样口径是「每 15 次慢路径」——光标静止时采样与帧率脱钩，`[frame]` 的 `#` 号不是帧号。
口径要么改注释要么改计数源。｜java-reviewer｜新发现。

### [U-L-13] ui/ 全包 `assertIsDispatchThread()`/`assertIsReadAccessAllowed()` 命中 0——线程契约全靠注释
违规只能事后靠探针间接抓，而探针覆盖面已知不足（U-H-2/U-M-19）。建议对「public static、任意线程可入」
的入口补断言。｜java-reviewer｜新发现。

---

### 去重与合并登记（任务书 §5.3）

| 合并后 | 来源（视角:原编号） | 备注 |
|---|---|---|
| U-H-2 | SF:H1 + JR:H1 | JR 补 javap 证据链 |
| U-H-3 | JR:H2 + SF:H3 | 结论一致 |
| U-H-5 | SF:H4 + CR:M-11 + JR:L5 + SEC:L-8 | 4 视角，判级分歧已在条目内裁决 |
| U-M-1 | CR:M-13 + JR:M-3结论 + JR:M9 + SF:M16（并入观察面） | M-3 衔接见 8.5 |
| U-M-2 | CR:M-2 + SF:L2 | 取量化方 |
| U-M-4 | CR:M-5 | 调度者 grep 复核 |
| U-M-5 | SF:M1 + JR:L1 | 分歧裁决见条目 |
| U-M-10 | CR:M-4 + SF:M6 | |
| U-M-11 | CR:M-7 + SF:M10 + JR:M2 | 3 视角 |
| U-M-12 | SF:M9 + JR:M1 | |
| U-M-13 | SF:M14 + JR:M3 + SEC:L-1 | 3 视角 |
| U-M-14 | CR:M-9 + JR:M10 | |
| U-M-15 | CR:M-8 + SF:M7 + JR:M6 | 3 视角 |
| U-M-16 | CR:M-10 + SF:M8 | |
| U-M-19 | SF:M13 + JR:M4 | |
| U-M-24 | SF:H6 | 降级 MEDIUM，理由在条目内 |
| U-L-3 | CR:L-3 + JR:L3 | |
| U-L-11 | CR:L-4 + JR:L7 + CR 范围外登记 | |

未合并、按各自机制单列的相邻项：U-H-3 与 U-H-4（同为调度线程停摆，但一个在「置 null 后没人重建」，
一个在「catch 体抛出后连 null 都到不了」，修法不同）；U-H-1 与 U-M-7（同为快速路径判据早于副作用，
一个缺信号源、一个缺回滚）。

### 正面结论（同样需要数字支撑——「查过且干净」也是产出）

1. **RenderScheduler 0.9.88 修复本体覆盖完整**：置标志到 invokeLater 之间只剩两条不可抛语句
   （`lastDispatchedDueNanos = due`、`posted = true`）；catch 回滚两个标志；`framePending` 全仓
   唯一置 true 点（`:261` CAS）。残留问题单列为 U-H-3/U-H-4/U-M-5，**不是「有没有活锁」而是退出后的世界**。
2. **`trails` Map 并发安全成立**：增删遍历全在 EDT 单线程；`ANIMATORS`（editor→animator）与
   `RenderScheduler.ANIMATORS` 分别是 `ConcurrentHashMap` 与 `newKeySet`；帧号标记-清除每帧无条件执行
   + 入口 `Math.min(MAX_TRAILS=64)` 双保险（`CaretAnimator:384/399/2158-2162`，security 复核对称）。
3. **`register`/`loop` 的 `thread` 交接无缺陷**：判空/置 null/重建共用同一把类监视器，四种交错均无害。
4. **8 个文件不存在用户代码内容泄露**：零 `getText()/getChars()`；文档读取仅
   `getModificationStamp/getLineNumber/getLineStart/EndOffset`（`:1854/:1942/:2025/:2301-2315`）。
   文件路径/用户名零入日志（唯一例外：写盘失败 message 含 `%APPDATA%`，diag 门后）。
5. **dispose 正常路径注销完备**：调度 unregister、Hierarchy/Caret 监听、inlay Disposer、panel 移除
   （`CaretAnimator:2849-2882`）——U-M-19 是其中一句话没包读锁，不是漏摘链。
6. **平台 252 既有修复面（refreshTarget/readEditor）无新违规**：U-H-2/U-M-19 是覆盖面外两点，
   其余编辑器读取点逐一核过。
7. **5000 行上限的已接闸门语义干净**：`>=` 拦 + 补一行汇总、满闸时不再拼字符串、
   `writeFile` catch 里的 `emit` 不递归——问题在未接闸门的 8 个出口（U-M-27）。

### 8.4 十类检查的覆盖结论（口径沿用第 1 批 §四，便于纵向对比；范围换成本批 8 文件）

| # | 检查项 | 谁审的 | 结论 |
|---|---|---|---|
| 1 | 字段六层链路（本批主责 ⑤消费者） | code-reviewer + java-reviewer | **有发现**。⑤ 的读取侧全部在 EDT 且确有消费（含 `computeNextDelayNs` 引用 `targetFps` 复核）；反向形态 1 条：每帧写无人读（U-M-23）。`targetFps` 事故模式本身未复发 |
| 2 | 一字段两职责 | code-reviewer | **有发现**。`frameSeq` 同兼「拖尾代数」与「采样帧号」而语义已漂移（U-L-12）；`posted` 兼「本轮结论」与「回滚旗标」（U-M-5）。未发现新的严重双职责字段 |
| 3 | 预设域三份清单同源 | —— | **本批不适用**：8 文件不含字段表/预设表；由第 1 批审过（VFieldDomain 守门）。范围外的预设相关 sink 只作跨批线索登记（U-M-28） |
| 4 | 默认值与迁移 | code-reviewer + java-reviewer + security-reviewer | **通过**。`hideNativeCaret=true`（`:264`）、`alwaysHighFps=true`、`diagLogging=false`、`MAX_TRAILS=64`、`IME_MAX_SKIP_FRAMES=15`、`MAX_DT=1/20` 逐一读码核实；无迁移逻辑在本批文件 |
| 5 | 数值边界 | code-reviewer + java-reviewer | **有发现**。上限只接 2/10 出口、无字节/行长闸门（U-M-27）；0.5px 迟滞吞小步（U-L-5）；`sample=1` 合法放大（并入 U-M-27）。`dispatchDue` CAS 边界穷举**通过** |
| 6 | 异常与静默失败 | silent-failure-hunter（主）+ java-reviewer（辅） | **未通过（本批最大块）**。catch 盘点 54 个：合规记录 14（26%）、纯静默 20、兜底无痕 15、良性控制流 5；8 条 HIGH 里 6 条落在此项。`CursorDiag.swallowed` 接线 2 处（与 6f7d585 一致），判据「低频+可见后果」被用作逐条裁量依据 |
| 7 | 线程与并发 | java-reviewer（主）+ silent-failure-hunter | **有发现**。U-H-2/3/4、U-M-1/11/12/19/20/21/22、U-L-13。**通过项**：trails 并发 0 条、thread 交接 0 条、M-3 生产触发面 0、M-8 静态初始化安全（8.5） |
| 8 | 死代码 | code-reviewer + java-reviewer | **有发现**。`CORNER_RELATIVE` 死表（U-L-2）、`updateCorners` 零调用（0.9.87 P-15 再证 + 新后果 U-M-23）、`CaretSchemeWatcher.dispose()` 零调用（U-L-11⑧）、`updateBlockCharsMoved()` 恒 return -1（登记 U-L-11）。`resolveCursorWidth` 零调用系**已登记技术债**（`:1839-1841` 注释在案、VWidth 在用），不重报 |
| 9 | 命名与行为一致 | code-reviewer + java-reviewer + silent-failure-hunter | **未通过**。U-M-24（「完成」）、U-M-15（「自行退出」不成立）、U-M-14（layers 撒谎）、`glowStrength` javadoc（U-M-23）、sleep 精度三说法（U-L-11⑥）、`getContentComponent` 注释过度收紧（javap 实测为纯字段读取——注释比平台严，不致错但误导） |
| 10 | 可维护性 | code-reviewer（主）+ java-reviewer | **有发现**。探针覆盖面缺口成为**结构性可维护性问题**（VReadAction 不跑 tick/dispose，U-H-2/U-M-19 全绿漏过——修法要动探针，本轮禁）；`CaretAnimator` 拆分分析见下 |

**十项无一「无人覆盖」**；第 3 项如实标注「本批不适用（范围决定）」。

#### 补充：CaretAnimator 2994 行的内聚性判定（任务书 §4.1 指定分析，深度对齐批 1 M-9）

- **判定：不是「大但内聚」，是 7 簇责任交错共用 40+ 字段**：帧率档位与调度（~700 行）、
  几何换算与目标解析（~700，含 640 行单方法 `refreshTargetInReadAction`）、多光标注册表（~250）、
  物理驱动与辉光淡出（~300）、诊断统计（~300）、脏区与重绘提交（~200）、平台接线与设置读取（~250）。
  唯一真正的粘合是「一个编辑器一个实例」+「都在 EDT」。
- **枢纽风险**：`forceResync` **6 个写者、1 个清零点**（在别的簇内部）——`inlayGeometryDirty`
  的存在（`:732-743` 记录第一版失败）就是这个标志生命周期失控的直接史料。
- **拆分边界（行为零变化，建议三批）**：① 诊断/统计簇 → `AnimatorDiagnostics`（零生产副作用）；
  ② 脏区/重绘 → `TrailRepainter`（接口面一个 `Rectangle`）；③ 拖尾注册表 → `TrailRegistry`；
  `refreshTargetInReadAction` 最后拆、先做类内方法切分；调度与平台接线留原地。
- **成本实测**：生产侧真耦合只有 3 个类（`RenderScheduler`/`NeovideCaretManager`/`CaretAnimatorProbe`），
  其余 55 处引用是注释。**成本在探针**：27 个文件 168 行，其中 5 个门禁探针按**方法名反射**私有方法
  （`getDeclaredMethod("refreshTarget")` ×5 等）、一族按**字段名反射**、3 个探针**读源码文本按声明定位**
  （`VDeadField:39-46` 的 `indexOf("private long computeNextDelayNs")`）——方法一搬类就炸、
  改个名就红而行为未变。**结论：该拆，但先给探针把文本断言换成行为断言，再分批搬；与 M-9 同级单独排期。**

### 8.5 与第 1 批的衔接

| 批 1 条目 | 本批结论 | 依据 |
|---|---|---|
| **M-3**（CONFIG 静态可变非 volatile） | **确认 + 收窄，不升不降** | 生产调用图里非 EDT 分支触发面为 **0**（5 个调用点全 EDT，两视角独立穷举）；「调度线程读 CONFIG」的上一批设想面**不存在**——调度线程只碰 4 个 volatile/Atomic；新形态 `TrailCorner` 双 load 后果限于档位混读一帧（推测）。残余=公开 API 无守门 + 探针（`VerifyApplyConfig`/`NonEdtProbe`）反而在错误线程上把该分支验「绿」了。修法收敛：volatile 引用整体替换 + `assertIsDispatchThread()` 或注释化该分支为离线探针专用。见 U-M-1 |
| **M-8**（settings→ui 包环） | **确认运行时安全；维持 MEDIUM 但注明已有现实代价** | javap + 静态初始化穷举：`NeovideConfig`/`settings/*` 零 `static {}`、`getConfig()` 调用点全在实例方法体、`NeovideCaretManager` 静态块只 `new NeovideConfig()`+Logger+syncMap → **无环无死锁路径**（单线程 `<clinit>` 保证 + 监视器阻塞）。但维持安全的前提「任何一方不得在静态初始化里加跨包调用」没有东西守；且 `NeovideCursorSettings:407` 注释显示 **settings 侧曾因这个环放弃使用 `CursorDiag.swallowed`**——包环已经实际影响过一次错误处理决策。结论：不急修，但把「禁止静态期跨包调用」写进文档契约 |
| **H-2**（返回值被丢弃模式） | **模式在 ui/ 成立，新增 2 处** | U-M-16（`installSchemeWatcher()` 3 处丢弃）、U-M-24（`applyConfigOnEdt` 无条件「完成」，本批降级理由见条目）。批 1 提交信息预告的 `NeovideCursorConfigurable:1972/2450` 在第 3 批范围——届时建议同一把尺：返回值/成功日志必须反映失败计数 |
| **L-10**（注释数字无守卫） | **同族扩大** | U-L-11 的 8 处与批 1 L-10 同一病根，新增形态是「互相矛盾的双 javadoc」（U-L-11①）与「描述不存在代码的注释」（U-L-11③） |
| 批 1 §0.2「本轮不报 VRegr2 R-5/R-6」 | **本批同样未报** | 门禁基线里的 2 FAIL 即此两条，四个 brief 均列入禁报清单；抽查确认无代理违令 |

### 8.6 待确认

1. **U-H-1 的最终观感**：代码层穷举属实（缓存分量、监听器不存在、坐标来源、默认隐藏原生光标），
   但 `visualPositionToXY` 含滚动偏移属平台语义推断。**需实机一验**，方法代理已给：
   长文件点第 N 行 → 滚轮滚 3 行不动光标 → 看拖尾是否停在旧像素位置。验证前不动生产代码。
2. **U-M-9 的三视角分歧**已按「问题 (b) 无保护抛出为结构性、(a) 重复调用为知情取舍」拆开裁决，
   但 CR「知情取舍不报」与 SF「MEDIUM」的口径差异记录在案：若第 3 批做「日志参数求值」专题，
   两种解释（探针契约要双读 vs 契约可重定义）应一次性裁定。
3. **U-M-20 的 javap 证据**绑定本地 `_idea-libs` 的 jar 版本；升级平台后「总线投递不改线程」
   的结论需复跑 javap 再确认。按任务书 §8.3 不据此改注释，先标待确认。
4. **U-H-4 触发概率未知**（LOG.warn 抛的机会）：修法（catch 体自保护 + UEH）不依赖概率，
   建议直接做；登记「实测概率」为第 3 批后的可选项。
5. **VReadAction 盲区修复需要改探针**（增加驱动 `runTick`/`dispose` 的用例）——按任务书 §8.5
   本轮不改；**这是本批最重要的一条「验证方法存在但不能现在用」**：U-H-2/U-M-19 的修复落地前，
   门禁对这两条路径永远全绿。
6. **U-M-28 的 sink 全在范围外**（`CursorPresetApplier`=第 4 批、`CustomPresets`=已审层）：
   本轮只登记方向（输出净化/控制符拒绝/长度上限），不在 ui/ 内动刀。
7. **性能类数字**（U-M-2/M-3/M-4/M-11 的 KB/s、µs、占比）均为**代码常量推算 + 本仓既有基准折算**，
   非实机采样；按任务书 §8.2 未装 jar 实测。若要做修复收益评估，需要 `tools/bench` 实跑。

### 8.7 本轮未覆盖

| 未覆盖项 | 原因 |
|---|---|
| `ui/settings/` 全部（含 `NeovideCursorConfigurable`、`AnimationPreview`） | 第 3 批 |
| `CursorPresetApplier` / `AnimPresetGroup` / `ColorPresetGroup` / `Toggle*.java` / `CaretAnimatorProbe` | 第 4 批；本轮只看调用点（U-M-28 的 sink、U-M-1 的调用面） |
| 实机验证：滚动观感（U-H-1）、诊断开启时 252 平台是否真抛（U-H-2）、帧率/GC 数字、warn 洪水实况 | 需装 jar 启 IDEA；任务书 §8.2 禁止，全部标注「需实机」 |
| 变异测试 | 本批白名单禁改生产代码与探针，无法执行「改坏→必须红」的标准验证；U-M-4 附注的 VReviewFixes 弱断言即该缺口的代价示例 |
| 探针修复（VReadAction 补 tick/dispose 用例、VReviewFixes 文本断言改行为断言） | 任务书 §8.5 禁止改探针；已列入 U-H-2/U-M-4/U-M-19 的建议方向 |
| `core/` 与 `settings/` 的内部再审 | 第 1 批已审；本批仅在 javap/调用点层面回看（`TrailCorner:294`、`CustomPresets:562` 两处例外系任务书点名的衔接线索） |
| `tools/bench`、`build/` 脚本 | 不在范围 |
| git 历史逐条比对 0.9.87 的 P-3…P-13 | 本轮只核实与 ui 相关的 P-13（已接线，6f7d585）与 P-15（`updateCorners` 仍零调用）；其余 0.9.87 条目状态未逐条重验 |
| 第 1 批内容的改动 | **零**。追加后 `head -770 docs/CODE-AUDIT-2026-10-30.md | sha1sum` 应仍等于 `3fd18a4060d0f2b1b2349d99fb5e2a39dca3ae1a`（复跑结果见收工回报） |

> **本节四个数字供决策会话引用**：100 条目 / 1035 PASS / 2 FAIL / ALL PASS；
> 49 条去重发现 = 0 CRITICAL + 8 HIGH + 28 MEDIUM + 13 LOW；结论 **WARNING**。
> 第 1 批的 M-3 **确认并收窄**、M-8 **确认安全且已有现实代价**、H-2 模式在 ui/ 新增 2 处。
> 本批修复优先级建议（给决策会话，非本轮执行）：**先修调度面**（U-H-2/3/4 一次提交可同改，
> 全部零观感变化、只动异常路径与诊断接线），**再修 U-H-1**（滚动失明——本批唯一正常使用的功能缺陷），
> 然后 U-H-5/6 隐藏链、U-H-7/8 + U-M-27 诊断基础设施一批。
