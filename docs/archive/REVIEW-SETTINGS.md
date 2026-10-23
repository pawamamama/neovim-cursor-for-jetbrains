# REVIEW-SETTINGS.md —— 设置界面（0.9.4-settings）契约逐条裁决 + SHA256 绑定审查

> **审查者**：reviewer（t5 首轮 / t8 第二轮 / **t9 第三轮**）
> **本轮性质**：**verdict 重新绑定到当前字节**。t8 之后 t6 集成交付又改了文档（契约 v2→v2.1、验证报告新增 §12），
> 门控判定 t8 覆盖的 revision 已不存在。本轮在同一实现上重做审查，并**独立核验 t6 新增、此前未经任何审查的内容**。
> **裁决依据**：`SETTINGS-CONTRACT.md` **v2.1**
> **工作目录**：`D:\deepseekwork\neovim-cursor-pr`
> **审查纪律**：只审查，**未改 `src/` 任何字符**（`git diff HEAD -- src/` 输出 0 字符）；未改任何交付物；未装 jar、未重启 IDEA。

---

## 0. 最终裁决（第三轮）

| 项 | 值 |
|---|---|
| **verdict** | **pass** |
| 六条 findings（R-01…R-06） | **保持关闭** |
| 绑定 | **当前字节已绑定**（§1 全表为我重算） |
| t6 新增内容独立核验 | **§12.2 的 27 条指纹 27/27 一致**；§9.5 波动表 50/50 一致 |
| 新发现 | 2 条 **low**（N3-1、N3-2，均为文档口径问题，不影响交付） |
| 全部判据 | parity / audit / IME / verify-t4 / non-edt **全部 exit 0**（我独立重跑） |

**结论**：当前字节已绑定，六条 findings 保持关闭，**t6 新增内容经独立核验一致**。
§12.2 那张 27 行的哈希表**没有任何编造**（全量 27/27 逐格命中）。唯一需要留意的是 §12.1 的一句**归因**不准确、
§12.4 记录的交付哈希与当前 dist 文件不同 —— 两者都不影响"内容指纹"这套做法的正确性与可用性，故判 low。

---

## 1. SHA256 绑定表（**全部我自己 Get-FileHash / git 计算**，与 captain 给的值一致，无差异）

| # | 对象 | SHA256 | 大小 | 与 captain 通报 |
|---|---|---|---|---|
| 1 | `SETTINGS-CONTRACT.md`（**v2.1**） | `A42BF7D60BF8CC24C6C2729F9F1C01F4652F2C8F6BE918DC73619C289534B0D5` | 77,758 B / 837 行 | **一致** |
| 2 | `VERIFY-SETTINGS.md` | `BF58CC7A5E099574CADCDBF2A666375CB5F915BA88374538F1F88424CA4BF030` | 66,213 B / 1,142 行 | **一致** |
| 3 | `REVIEW-SETTINGS.md`（本文，t8 版） | `7EC1E69E172058D0B6373C873F139219430BB216EAC152C8655A3F391EC3FC16` | 19,685 B / 211 行 | — |
| 4 | `src/.../ui/NeovideCaretManager.java` | `55A8FA5ACBD8422DDC6CD7417508ACB7BD8CC7F77707BA48014B8E0F29B22606` | — | **一致** |
| 5 | `src/.../settings/NeovideCursorSettings.java` | `CCED3A3FA914CD811E4DE5133EF14ED1CE32C98145D7DA8ECB09FF44B5244920` | — | **一致** |
| 6 | `src/.../ui/settings/NeovideCursorConfigurable.java` | `EE261A09ECEE8C26EED41FA51006B08D265D5EB2D98C6E398D47F2B677E52104` | — | （本轮新增记录） |
| 7 | `src/.../core/NeovideConfig.java` | `A43F8F2712C0CB2D1183BAB409EF8BD649E024B6541E1C89407E90F4C2A4A8F8` | — | （禁改文件，见 §5） |
| 8 | **git diff HEAD** | `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855` | **0 字节** | 空 diff 的 SHA256 |
| 9 | **HEAD** | `3c3944d3c585a68e59a5eb6da3a9840b6514a30a`（短 `3c3944d`） | — | **一致** |
| 10 | **交付 jar 整体** | `D30B23E2FF59C33051C67F1614B586A2CAD70B3266EF7515F83DB9E0DD527F4D` | 70,056 B | **一致** |
| 11 | **jar 内容指纹** `META-INF/plugin.xml` | `7D5CDEF493B4DFA6` | 5,292 B | **一致** |
| 12 | `VERSIONS.md` | `18AE86C19FE3A125B76EFA4EDAAABF2E770A39DB6C0C229E79701AF8E6E22E68` | 15,866 B / 255 行 | （本轮新增记录） |
| 13 | `LESSONS.md` | `E9515FA7DF525F0BBFAB5B94FD040A5FB961AFCD63BDEE6EA8A68B087475FC7C` | 16,263 B / 422 行 | 全程未变 |

> #8 的 `E3B0C442…` 就是**空字符串的 SHA256** —— 本批已全部提交，工作区相对 HEAD 无源码改动，这是正确状态（不是缺失）。
> 所有数值与 captain 通报**逐位相同**，无差异需要说明。

---

## 2. 【本轮重点】独立核验 VERIFY-SETTINGS.md §12 的 27 条稳定指纹

### 2.1 做法

我自己 `jar --extract` 交付 jar 到 `build/t9-scratch/entries`，对**每一个条目**算 SHA256 前 16 位，与 §12.2 表逐条对账。**全量 27 条，不抽查。**

### 2.2 结果

```
条目数        ：表 27 / jar 实际 27
名称匹配      ：27/27
字节数一致    ：27/27
SHA256 前16位 ：27/27
jar 中未被表列出的条目：0
```

**>>> 27 条一致 / 0 条不一致。**

逐条比对覆盖了 core/(7)、settings/(3)、ui/(9)、ui/settings/(3)、META-INF/(2)、OffsetTest.class(1)、以及 6 个 Toggle*Action.class。
其中 `META-INF/plugin.xml` = `7D5CDEF493B4DFA6`（5,292 B）与 VERSIONS.md 的内容指纹声明**完全吻合**。

**这是一张诚实的表** —— 考虑到 impl-core 在 t7 曾主动交代过"为了让表格看起来完整而编了 R1/R3/R5 的数字"，
本轮我按 captain 要求做了全量而非抽查，**结论是 §12.2 没有任何编造残留**。

### 2.3 顺带验证 §12.1 的**论断**（重建是否真的稳定）

我在**不改动 `dist/` 交付物**的前提下，用交付 jar 当容器底、以 `jar --update` 重打两次：

| 实验 | 结果 |
|---|---|
| 同一份 classes 重打两次（`--update`） | 整体哈希 `D30B23E2…` **与交付 jar 完全相同**（r1 == r2 == delivered） |
| 解包后逐条比对 r1 vs delivered | **差异 0 / 27** |
| 把某个 `.class` 的 mtime 改掉再打 | 整体哈希**变了** |

由此确立 `jar` 的真实语义：
- **class 条目的 zip mtime = 源文件 mtime**（我构造 mtime=`10:00:00` 的源，zip 里就是 `10:00:00`，**不是构建时刻**）；
- 项目用的是 `jar --update`（`build/offline-build.ps1:69`）；
- 所以**整体哈希是否变，取决于 `build/classes` 里 `.class` 的 mtime**，而 `javac` 每次重编译都会刷新它 → 跨构建整体哈希确实会变。

**判定：§12.1 的结论成立**（整体哈希不能跨构建比对内容，必须用条目指纹），**但归因不准确** → 记为 **N3-2（low）**，详见 §6。

---

## 3. §9.5 波动表独立对账（t8 做过 50/50，本轮改动后重做）

与原始 `SUMMARY` 行（`:563-567` / `:571-575` / `:581-585` / `:589-593`）逐格对账，
每场景 10 格（遍1 区间/宽度、遍2 区间/宽度、跨遍跨度、新版的同五项）× 5 场景 = **50 格**：

```
R1 10/10 OK   R2 10/10 OK   R3 10/10 OK   R4 10/10 OK   R5 10/10 OK
§9.5 波动表：50/50 格一致，不一致 0
```

**§9.4 增补部分（:602-632 的口径说明 + R4 单独说明）也复核过**：R4 的"方向一致但幅度小于自身跨度 → 仍判噪声"表述与数据吻合（基线跨遍跨度 0.70 ms > 偏移 0.29 ms）。t8 提的 R-05 已被实质性关闭。

---

## 4. 契约 v2.1 三处修改与实现自洽性

| # | 检查项 | 裁决 | 证据 |
|---|---|---|---|
| 1 | §3.3.2 伪码 S7 是否含 `refreshGlowPadding()` | **已落实** | 契约 `:453-456`：`S7 for(...) { a.onModeChanged(); a.refreshGlowPadding(); }`；实现 `NeovideCaretManager.java:534-546` 正是两步。**伪码与实现一致** |
| 2 | §3.2.2 伪码是否已是三态 `PersistResult` | **已落实** | 契约 `:403-408`：`PersistResult persisted = persistQuietly(); switch(...) { CONFIRMED / DEFERRED / FAILED }`，并附 `:410-414` 的「为什么必须三态」；实现 `NeovideCursorSettings.java:206-226` + 枚举 `:480-487`。**一致** |
| 3 | §3.3.3 是否已把「forceResync…唯一保证」改成两步保证并写明旧句已证伪 | **已落实** | 契约 `:478`：明确写「**单靠 ① 不够**（v1 曾断言……**该断言已被证伪**）」，给出 `CaretAnimator:1007` 早退证据，并写明「真实保证是**两步**」「这正是 R-05 当初产生的根源」 |
| 4 | **R5/R6/R7 现在是否真的在正文落实**（t8 指出当时只在 §8 表） | **全部落实** | R5 → §3.3.2 伪码 S7（`:453-456`）；R6 → S0 语义与「S0 必须早于任何写 CONFIG」说明（`:443-463`）；R7 → §3.2.2 伪码（`:403-408`）+ 三态理由（`:410-414`）。**不再只是附录表** |
| 5 | §8 修订记录新增 N-2a/N-2b/N-2c | **已登记** | 契约 `:831-833` 三条，逐条对应 t8 的 N-2 三处 |

**结论：v2.1 的三处修改与实现完全自洽，R5/R6/R7 已在正文落实。t8 的 N-2 关闭。**

---

## 5. 全部判据我独立重跑（exit code 均为 0）

| 判据 | 我跑的命令 | 结果 |
|---|---|---|
| parity | `run-parity.ps1 -Work build/t9-scratch/parity` | **exit 0**；3 x `PARITY OK` + `ALL PARITY OK`（0A4FDDE6 / 4057AFA0 / 409C5728 两两相等） |
| audit | `run-audit.ps1` 同上 | **exit 0**；19 项 **DIFF = 1**，唯一 DIFF 是 `hideNativeCaret`（基线 false → 新版 true）；B 段正确报告「git diff 为空」 |
| audit D 段 | 逐文件统计，排除 `settings/` | **12**（NeovideConfig 4 / CaretAnimator 4 / CaretTrailPanel 2 / NeovideCaretManager 2）—— 与预期一致 |
| IME | `run-ime-geom-test.ps1` | **exit 0**；通过 29，失败 0 |
| verify-t4 | `run-verify-t4.ps1` | **exit 0**；1+3+52+26+18 = **100 条断言全过** |
| non-EDT | `run-non-edt-probe.ps1` | **exit 0**；通过 13，失败 0 |
| 默认值零改动 | `git diff --exit-code -- core/NeovideConfig.java` | **exit 0**，零 diff；35 项禁改字面量 **missing = 0** |
| 旧键清除 | 逐文件 grep（17 个 .java） | `PropertiesComponent` 仅 `NeovideCursorSettings.java` 10 命中；`restorePersistedSettings` 0；`static volatile boolean enabled` 0；`kFromTrailFactors` 0 |

---

## 6. 新发现（本轮 2 条 low）

### N3-1【low】§12.4 记录的交付哈希 `1CA71944…` 与当前 `dist` 里的 jar（`D30B23E2…`）不一致

- **file:line**：`VERIFY-SETTINGS.md:1140`（`SHA256: 1CA71944C2D19B50…`）
- **problem**：该行记录的是 t6 某一次构建产物的哈希，而当前 `dist\jetbrains-neovide-cursor-0.9.4-settings.jar`
  实际是 `D30B23E2…`（70,056 B，mtime 2026-10-19 23:04:16）。读者按 §12.4 校验会得到"文件被替换"的错误结论。
  §12.4 本身已加注「该值只对应 t6 那一次构建产生的文件；重建会变」，所以**不算撒谎**，但把一次性哈希放在"本次交付记录"标题下仍然误导。
- **不是安全问题**：`dist/*.jar` 被 `.gitignore` 忽略、不入库，交付 jar 是可重建产物而非被追踪实体；§12.2 的 27 条指纹才是权威判据。
- **requiredFix**：把 §12.4 的 `SHA256:` 行删除或改为「本例为 t6 构建时的一次性值，已失效；**判定内容请用 §12.2 的 27 条指纹**」，只保留字节数与内容指纹。

### N3-2【low】§12.1 对"整体哈希每次重建都会变"的**归因**不准确

- **file:line**：`VERIFY-SETTINGS.md:1080-1082`（「`jar` 工具会把当前时间写进 zip 条目头」）；`VERSIONS.md:242` 同句
- **problem**：我实测 `jar` 写入 `class` 条目的 zip mtime **等于源文件 mtime**（构造 mtime=`10:00:00` 的源，zip 里就是 `10:00:00`），**不是构建当前时间**。
  真正机制是：`javac` 每次重编译都会刷新 `.class` 的 mtime，`jar --update` 把**源 mtime** 写进条目头 → 跨构建整体哈希才会变。
  旁证：用同一份 `classes` 连跑两次 `jar --update`，整体哈希**完全相同**（`D30B23E2…`，与交付 jar 逐位一致），这与"写入当前时间"的说法直接矛盾。
  仅 `jar --create` 时 `META-INF/` 与 `MANIFEST.MF` 这两个**自动生成**的条目会用当前时间，而项目走的是 `--update` + 预置容器 jar。
- **影响**：**结论正确、可用**（整体哈希不能跨构建比对内容，必须用条目指纹）；只是解释错了原因，会让人误以为"重建必然变"（实际上固定 mtime 就能复现同一哈希）。
- **requiredFix**：改为「`jar` 把**源文件的 mtime** 写进条目头；`javac` 每次重编译刷新 `.class` 的 mtime，因此跨构建整体哈希会变（同一份 `build/classes` 重打则哈希可复现）」。VERSIONS.md:242 同步更正。

---

## 7. 交付物完整性

| 检查 | 结果 |
|---|---|
| jar 内 `<version>0.9.4</version>` | 存在 |
| jar 内 `<idea-version since-build="242" />` | 存在 |
| jar 内 `applicationConfigurable`（`instance=…ui.settings.NeovideCursorConfigurable`、`parentId="tools"`） | 存在且正确 |
| jar 内 `applicationService` | 存在 |
| settings 相关 class | **9 条**：`NeovideCursorSettings.class` / `$PersistResult` / `$State` / `NeovideCaretManager$Snapshot` / `NeovideCursorConfigurable.class` / `$1` / `$2`（+ 两个目录条目） |
| `build\rollback-0.9.4.ps1` | 存在，2,843 B；`$expectHash16 = '610AE7E9676E2392'`（L27），L33-34 做**硬校验**（不符即 throw）、L48 对已安装 jar 再比一次 |
| 回退目标 `dist\...0.9.3-imegeom.jar` | 实际哈希 `610AE7E9676E2392D866CA919EAF6E8C249E8FDF551B2AA51FED8C07298828C6`（44,523 B）—— **与脚本期望值一致** |
| VERSIONS.md「当前安装」准确性 | **属实**。我实测安装目录 `…\lib\jetbrains-neovide-cursor-0.5.0.jar` = 44,523 B、`610AE7E9676E2392…`，与 VERSIONS.md `:224-229` 声明**逐位吻合**；t6 声称"校正过 0.9.0→0.9.3"**属实** |
| VERSIONS.md 是否擅自改「待安装」为已安装 | 未擅自改：`:235` 明写「待安装：0.9.4-settings」，`:253-255` 注明安装留给用户 —— **符合"不装 jar"硬约束** |

---

## 8. git 提交与工作区

| 检查 | 结果 |
|---|---|
| 本批 commit 数 | **8 个**（`ba67ed3..HEAD`） |
| Conventional Commits 合规 | **不合规数 = 0**。8 条均为 `type(scope): subject`：`docs(release)` / `fix(audit)` / `chore(release)` / `docs(settings)` / `test(probe)` / `fix(settings)` / `feat(settings)` x 2 |
| 是否 push | **未 push**：`git rev-parse @{u}` → `fatal: no upstream configured for branch 'feat/settings-ui-ready'` |
| 是否打 tag | **未打新 tag**：`git tag` 只有历史 `v0.2.0 … v0.5.0`，**没有 v0.9.x** |
| 剩余未跟踪文件 | **4 个**：`HANDOFF.md` / `HANDOFF-full-history.md` / `SKILL-jetbrains-plugin-offline-build.md` / `SKILL-upstream-lessons-before-patching.md` |
| 是否本批产物 | **不是**。`git log ba67ed3..HEAD -- <每个文件>` 均返回**空**（从未在本批任何 commit 出现）；四个文件 mtime 均为 **2026-10-16**（本批工作发生在 10-19） |
| **凭据泄漏扫描** | **未发现真实凭据值**。对四个文件做了 11 类高熵/前缀模式扫描（`sk-`/`ghp_`/`gho_`/`xox[baprs]-`/`AKIA`/`AIza`/PRIVATE KEY/`password:`/`secret:`/`token:`/`api_key:`），**疑似命中 0**。仅 `HANDOFF-full-history.md:297/301` 出现 `token` **字样**，经脱敏阅读确认是**策略说明文字**（"凭据只报键名不打印值"、"推送需要 GitHub 凭据……用完应提醒删除"），**不含任何密钥值**；文中提到的 `gho_` 也无值、且注明"一次性，已消耗" |

**判定**：4 个未跟踪文件确非本批产物、无凭据值泄漏、未被擅自提交 —— impl-core 的处置正确。

---

## 9. `run-audit.ps1` 被改的复核（commit c932944）

- **改动内容**（`tools/parity/run-audit.ps1:69-82`）：原来是 `& git … | Set-Content $diffTxt`，空 diff 时不创建文件 → 下一行 `Get-Content` 抛错 → 整个脚本 exit 1（把正常状态误报为审计失败）。改为**先落到变量 `$diffLines`，判空后分支处理**，空时写空文件并打印说明。
- **修复正确**：我本轮实跑，B 段正确输出「`（git diff HEAD -- src/ 为空：本工作区相对 HEAD 没有未提交的源码改动）`」，脚本 **exit 0**。
- **不会让 audit 结论变宽**：`if ($diffLines.Count -eq 0)` 只是**新增**空分支；非空分支仍是原来的 `Select-String '^[+-].*[0-9]'` 原样输出，**没有吞掉任何本该报出的行**，也没有把失败路径改成 success。A 段（19 项常量比对）与 D/E 段完全未动 —— 我核对了 `git show c932944 --stat` 与当前文件，A 段逻辑与 `DIFF 数` 统计方式未变。

---

## 10. N-1「不改代码」裁决复核

**裁决成立，我采纳。** 我独立跑了 impl-core 写的 `build/settings-probe/GlowRadiusProbe.java`（用**当前源码**重新编译后运行）：

```
=== 结果：通过 10，失败 0 ===
PASS E1 复现 reviewer 的结论：逐格拖动确实存在 padding 滞后   最大滞后 1 px
     sbf=0.20 -> 11 / 0.21 -> 11（滞后） / 0.22 -> 12 / 0.23 -> 12（滞后）   <- 与我 t8 的四个点完全一致
PASS E2 固定输入下 glowWidth 逐位稳定（无振荡、无累积漂移）   变化 0 次
PASS E2 即使 maxDim 有 1e-7 级抖动，ceil() 后的 padding 也不来回跳   变化 0 次
PASS E3 sbf 单调递增拖动时 padding 单调不减   端点 0.2->11 / 0.6->24
```

四条理由逐条核验：

| 理由 | 是否成立 | 我的证据 |
|---|---|---|
| (1) 非本批回归 | **成立** | `git diff HEAD --numstat -- CaretTrailPanel.java` **为空**（本批完全未改）；基线 `8906609:135-141` 就是同一条 `> 0.5f` 判据 |
| (2) 影响 ≤1px 且自愈 | **成立** | 探针 E1「最大 padding 滞后 = 1 px」；`updateGlowRadius` 在 `CaretAnimator:1018` 每次光标移动慢路径都会调用 → 一动就纠正 |
| (3) 改它属"没验证的性能改动"、超 inScope | **成立** | 探针 E2 已证明去掉死区不振荡（`newWidth` 是 `(shadowBlurFactor, maxDim)` 的纯函数），但 `src/` 确实不在集成交付 inScope 内；本批"不改观感常量"的纪律支持此判断 |
| 契约 §3.3.4 的表述是否准确 | **准确** | `:485-520`：成因（`CaretTrailPanel:138` + 步长 0.01 x 32 = 0.32 < 0.5）、定量表（29/35/41/47/51/57 共 6 格、最大 1px、复现我的四点）、三条理由、以及给第二期的落法（`> 0.5f` → `>= 0.01f`）都写清了 |

---

## 11. 结论

**verdict = pass。**

**当前字节已绑定**（§1 全部 13 项为我自己重算，与 captain 通报逐位一致，无差异）。
**六条 findings 保持关闭**：R-01/R-02/R-03 的修复代码在本轮字节中依旧在位且被 13 条 non-EDT 断言覆盖；
R-04（`kFromTrailFactors` 已删）与 R-06（类名已更正）在 v2.1 与源码两侧自洽；R-05 的波动表现在 50/50 逐格一致。
**t6 新增内容经独立核验一致**：
- **§12.2 的 27 条稳定指纹：27/27 一致，0 条不一致** —— 全量对账，无编造残留；
- §9.5 波动表 **50/50** 一致；
- 契约 v2.1 的 R5/R6/R7 **已在正文落实**（不再只是 §8 表），三处伪码与实现完全自洽；
- 交付物完整（version / idea-version / 9 条 settings class / rollback 脚本哈希校验 / VERSIONS.md 的「当前安装」经我实测属实）；
- 8 个 commit 全部合规、未 push、未 tag；4 个未跟踪文件确非本批产物且**无凭据值泄漏**；
- `run-audit.ps1` 的空 diff 修复正确，未放宽任何判据。

两条新发现（N3-1、N3-2）都是**文档口径**问题，不影响交付可用性，建议随下一版文档一并更正（各改一两行即可）。
