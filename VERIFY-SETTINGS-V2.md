# VERIFY-SETTINGS-V2 —— 0.9.5 设置界面重做的独立验证

> 验证者：verifier（t4，attempt 1bfea9d6-1586-4f44-83f8-9005afd95414）
> 被测对象：t2（4 个缺陷修复）+ t3（面板重做 / 预设 / 吸附 / 动画参数 / Tools 子菜单）
> 工作区：D:\deepseekwork\neovim-cursor-pr
> **本文所有数字都是我自己重跑得到的。没有引用实现者的任何结论。**
> 实现者的自述只在 §11 做「差异对照」时出现，且逐条标注。

---

## 0. 结论摘要

**功能层面：全部通过。** 4 个缺陷全部修复（并在**真实的 0.9.4 源码树**上复现了修复前行为），
预设正交、吸附、动画参数边界、默认值零改动、三件套、帧率不回归全部达标。

**另有 5 条「文档 / 契约 / 呈现」层面的偏差**（F-1…F-5）。**没有一条造成用户数据损失或功能错误**，
但 F-1 会让面板文案与滑块能力自相矛盾、F-2 会让下一个维护者拿到错的因果模型 ——
建议在 t6 一并处理。逐条见 §10。

> **【captain 已裁决（2026-10-20）】5 条全部「接受」，且全部判为「改文档 / 文案，不改行为」——
> 不需要返修 t3，直接进 t5。** 裁决明细见 §10.0。

| 验收项（t4 契约） | 判定 | 证据 |
|---|---|---|
| VERIFY-SETTINGS-V2.md 存在，每项含命令 / 原始输出 / SHA256 / 判定 | **PASS** | 本文 |
| 三件套全过（parity 逐字节 / audit DIFF=1 且 D 段=12 / IME 29） | **PASS** | §2 |
| 4 个缺陷独立复现且修复后通过（不采信实现者探针） | **PASS** | §3 |
| 变异测试：每个缺陷回滚后探针必红（忠实还原 + diff 证据） | **PASS** | §4 |
| 预设正交性逐字段验证（换动画不改颜色、换颜色不改动画） | **PASS** | §5 |
| 滑块吸附：非刻度落位 / 任意值接受 / 非法值处理 | **PASS** | §6 |
| animationLength / shortAnimationLength 四端点 + 越界 clamp | **PASS** | §7 |
| 帧率不回归（p50 / p95 / p99，≥3 轮） | **PASS** | §8 |
| 默认值零改动 + 不做清单未越界 | **PASS** | §9 |
| 与实现者自述的差异如实记录 | **PASS** | §10 / §11 |

**我自己写的探针总量（当前树）：119 条断言 = 19 + 14 + 23 + 25 + 12 + 10 + 11 + 2 + 3，其中 118 PASS / 1 FAIL。**
那 1 条 FAIL 是 F-2 的「机械判据」上报器（契约 V13 原文的反射判据），
同一探针里的行为判据（键入 333 → apply → 落库 333）PASS。
另有 `VBaseline` 的 6 条断言在**两个树上各跑一次**：0.9.4 上 1 PASS / 5 FAIL，0.9.5 上 6 PASS / 0 FAIL ——
那是「修复前必红」的证据，不计入上表。

---

## 1. 复跑方法与被测字节身份

### 1.1 被测源码 SHA256（验证时的最终形态，22 个 .java）

```
FBA802892EF6E36047C2D8F5B0033AF5609F2097ABA6E9120BD2442191251A4B  core/AfterimageTrail.java
0F19F5D9D7947F52F94E8F8876A456344DBF1782DD7E6B3A5A8463BDDDCE3D85  core/DampedSpring.java
41779D2117AE316FA0603A9814EBAC17755152226C2F7BD5F5799061E1E7DF50  core/NeovideColors.java
A43F8F2712C0CB2D1183BAB409EF8BD649E024B6541E1C89407E90F4C2A4A8F8  core/NeovideConfig.java          <= 与 HEAD 逐字节相同（D0）
EE15A576B55C179BC2938B888A18E6DFE31A2D16DC3EDDB3B6F42AF5033B69F7  core/TrailCorner.java
5F5325227B8C676034A200E34B0C0604CDEA8E2273D1AAFD9C52AC306F908355  settings/CursorPresets.java      <= 新增
4B8BBF7674B1A0F568FBF69B0963A0D50DB03C331CEBE473816F43C079451232  settings/NeovideCursorSettings.java
49272E3D21116D70DD10F624CEDB2BDBF43C9A45C26B8EB9C9BDF527E6177337  ui/AnimPresetGroup.java          <= 新增
55D8EA5359538649BEDB3A67F3CB97DB5498267675D02B7C98F47F6A5121EF80  ui/CaretAnimator.java
C1F75533B5C434C3CD41879A0D3185D6BC5C0E8AE0FF19BD318A87C5FD399A92  ui/CaretSchemeWatcher.java       <= 新增
7436683CB6EAB8CE836D5F65464153EDD42FD8B9D4750E4555D4FBC441C3BD19  ui/CaretTrailPanel.java
B7FD7D9BB34FDE7E68B7569BCE94D937C40B823F0B5699EAC33866AAADD5ABCA  ui/ColorPresetGroup.java         <= 新增
1298D295DA51C25E235B108554EF5BEC755A09D7746EDC5C254F1F121CE3EB22  ui/CursorPresetApplier.java      <= 新增
6BEC830CA7889EB59AE4BCAFE4A70C4A26432808529B01DF48B1A5C97AFF695E  ui/NeovideCaretManager.java
A89BE4256BF7533D9254575168FA9EB08766DCE0EA9CFF682474B07642099292  ui/RenderScheduler.java
250D59F896EA9C3ECEFBAE1E16746CBEF1C225404E9B3BEE052E6E9E5EC7F884  ui/ToggleAlwaysHighFpsAction.java
2E44C5DE2F590E4AC28ECD3750F4BAA5B87701F1DC64AFC9CCF070D733962164  ui/ToggleFadeOutAction.java
09AF01DF33EB85129C140B2823B7CF3FCFE577289D78F649609258683C5FE7E7  ui/ToggleHideNativeCaretAction.java
A9D81E9C0BB32FC20454F8E6E884D2DBEDAD2B85A6EEA2D56B74534390ADAF4A  ui/ToggleNeovideAction.java
82A5D31910E295A2266931590E58230D0F2A025640C61B1FBF939EDB4CA405CE  ui/TogglePaintDiagAction.java
670C57B30B19C162C586BE977752E187996EFA5F38171DC8EBBF2DE6D7EF3638  ui/ToggleTrailModeAction.java
1571ED65A64491514B3A289C47857922E488DF0B6C07AB95A2435704DD9235EE  ui/settings/NeovideCursorConfigurable.java
```

`src/main/resources/META-INF/plugin.xml` SHA256 = `E957177F68D28A0D385FA421C48B9A370CD0357654869D1363BFB44A89065ACB`

冻结输入（契约 / 方案 / 经验）：

- `SETTINGS-UI-CONTRACT.md` 98780 bytes，SHA256 = `DBEAD2787D2DCD3A85EEC96896DF0CE2F1DC79B695F563793D0ECC31241412A7`（= v1.2）
- `SETTINGS-UI-REDESIGN.md` 10387 bytes，SHA256 = `C188AA3B19F60D17BC0D0B939A1A712E64E26818FBADF910695BAA4B909327C1`
- `LESSONS.md` 20712 bytes，SHA256 = `A7CF4C6921EB382EFCF6787E11725318A9440CDB5AF2D574D63C7F0987ACD9EE`

### 1.2 我自建的探针（全部在 `tools/settings-probe/`，可原样复跑）

| 探针 | 断言 | 覆盖 |
|---|---|---|
| `V0Env.java` | — | 离线平台桩（Application / PropertiesComponent / EditorColorsManager / EditorFactory / MessageBus / ActionManager / SingleAlarm scope holder） |
| `VUi.java` / `VFields.java` | 2 | 组件定位（遍历组件树，**不用**实现者的 `*ForProbe` 钩子）+ State 逐位快照 |
| `VDefects.java` | 19 | 缺陷 1 / 缺陷 2 / 缺陷 3 / V11 |
| `VScheme.java` | 14 | 缺陷 4（含消息总线端到端） |
| `VSnap.java` | 23 | 吸附 / 双向绑定 / 非法值三档 / 动画参数四端点与越界 |
| `VPreset.java` | 25 | 预设取值 / 正交性（逐位）/ 刻度网格 / 无第四真相源 |
| `VTools.java` | 12 | Tools 两个子菜单 + 单选 + 只改该改的字段 |
| `VSpring.java` | 10 | **独立复算** DampedSpring 的上下限量化依据 + 短跳分支有效时长 + 前缘失效的**两个**边界（8ms / 50ms） |
| `VHints.java` | 11 | 两条动态耦合提示（禁止写死数字） |
| `VMixed.java` | 2 | 拖动与键入的先后（契约 §6.1 未覆盖的混合场景） |
| `VOrder.java` | 3 | 构建顺序 / 回调顺序 / 精确值是否被覆盖 → 上报 F-2 |
| `VBaseline.java` | 6 | **同一份断言**在 `git archive HEAD`（0.9.4）与当前树上各跑一次 |

一键复跑：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\build-v-probes.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-v-mutations.ps1
```

最后一次完整运行的汇总（`build\v-probes-final3.log`）：

```
  VDefects   exit=0  PASS=39  FAIL=0    === V-DEFECTS 结果：PASS 19，FAIL 0 ===
  VScheme    exit=0  PASS=14  FAIL=0    === V-SCHEME  结果：PASS 14，FAIL 0 ===
  VSnap      exit=0  PASS=30  FAIL=0    === V-SNAP    结果：PASS 23，FAIL 0 ===
  VPreset    exit=0  PASS=54  FAIL=0    === V-PRESET  结果：PASS 25，FAIL 0 ===
  VTools     exit=0  PASS=12  FAIL=0    === V-TOOLS   结果：PASS 12，FAIL 0 ===
  VSpring    exit=0  PASS=10  FAIL=0    === V-SPRING  结果：PASS 10， FAIL 0 ===
  VHints     exit=0  PASS=11  FAIL=0    === V-HINTS   结果：PASS 11，FAIL 0 ===
  VMixed     exit=0  PASS=2   FAIL=0    === V-MIXED   结果：PASS 2， FAIL 0 ===
  VOrder     exit=1  PASS=2   FAIL=1    <= 上报 F-2；同一探针内的行为判据 PASS
V-PROBES RESULT: ALL PASS
```

（`PASS=` 列含逐行明细，比探针结尾那一行多，因为每条明细本身就是一条断言。）

### 1.3 探针环境：四处「平台缺失」，都是桩而不是被测代码缺陷

实现者 t3 自述里提醒了 2 处，我实测共需 **4 处**（第 3、4 处是我补的）：

| # | 触发点 | 缺什么 | 我的处理 |
|---|---|---|---|
| 1 | `collapsibleGroup` 构造 | `ActionManager.getInstance()` 要 `CollapsiblePanel-toggle` | 继承 `ActionManager` 写桩（抽象类不能 Proxy） |
| 2 | `intTextField` 的文本校验器 | `JBPopupFactory.getInstance()` 建气泡 | **不伪造 JBPopupFactory**，改用平台官方旁路：把 `ComponentValidator` 的 `disableValidation` 置 true 且 `validator` 置 null（`revalidate()` 的守卫就是 `validator != null`） |
| 3 | `DialogPanel.registerValidators` → `ComponentValidator` → `SingleAlarm` | 包级服务 `com.intellij.util.SingleAlarmSharedCoroutineScopeHolder` | 反射构造（持有一个 `CoroutineScope`，不会被触发） |
| 4 | `DslLabel`（comment 标签） | `--add-opens java.desktop/javax.swing.text.html.parser` | 补 JVM 参数（缺它会 `InaccessibleObjectException`） |

> 第 2 处我刻意**不**用「伪造 JBPopupFactory」：它 31 个抽象方法，伪造等于自欺，
> 而且会把「校验器根本没被禁用」这件事掩盖掉。用 `disableValidation` 是平台自己的开关，
> 不影响被测的绑定 / 吸附 / 脏字段逻辑。

---

## 2. 三件套（我自己重跑）

### 2.1 parity —— 逐字节一致 + SHA256

命令：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1
```

原始输出（`build\v3-parity.log`）：

```
base             PhysicsParity    行数=72    SHA256=0A4FDDE69C3EBC32DF23AEF17D8EAFF15CDA0B25E5E04152B7076AB8C1003883
base             GeometryParity   行数=25    SHA256=4057AFA03E768C842990FD4B7B4B6D5AF7ED46830A848E8ED99900FFFB526B44
base             TrailParityTest  行数=158   SHA256=409C5728506FF36D648559D764AB288E7B55753DA92D6251BD44E81F756A08C7
new              PhysicsParity    行数=72    SHA256=0A4FDDE69C3EBC32DF23AEF17D8EAFF15CDA0B25E5E04152B7076AB8C1003883
new              GeometryParity   行数=25    SHA256=4057AFA03E768C842990FD4B7B4B6D5AF7ED46830A848E8ED99900FFFB526B44
new              TrailParityTest  行数=158   SHA256=409C5728506FF36D648559D764AB288E7B55753DA92D6251BD44E81F756A08C7
PhysicsParity    PARITY OK   （diff 为空）
GeometryParity   PARITY OK   （diff 为空）
TrailParityTest  PARITY OK   （diff 为空）
==> ALL PARITY OK
```

**判定 PASS**：三份输出在「基线 321b0a0」与「当前工作区」两棵树上**逐字节相同**
（`git diff --no-index` 为空），三份 SHA256 两侧完全一致。

### 2.2 audit —— A 段 DIFF=1 且必须是 hideNativeCaret；D 段排除 settings/ 后 = 12

命令：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\parity\run-audit.ps1
```

原始输出（`build\v3-audit.log` 的 A 段全文）：

```
SAME     glowLayers = [0-9]+                | public int glowLayers = 8;
SAME     glowOpacity = [0-9.]+f             | public float glowOpacity = 0.55f;
SAME     shadowBlurFactor = [0-9.]+f        | public float shadowBlurFactor = 0.2f;
SAME     trailFactors = \{[^}]+\}           | public float[] trailFactors = {1.0f, 0.72f, 0.42f, 0.22f};
SAME     float animationLength = [0-9.]+f   | public float animationLength = 0.1f;
SAME     shortAnimationLength = [0-9.]+f    | public float shortAnimationLength = 0.11f;
SAME     trailSize = [0-9.]+f               | public float trailSize = 1f;
SAME     typingMaxChars = [0-9.]+f          | public float typingMaxChars = 3f;
SAME     cursorWidth = [0-9.]+f             | public float cursorWidth = 2f;
SAME     maxTrailDistanceFactor = [0-9.]+f  | public float maxTrailDistanceFactor = 60f;
SAME     animationResetThreshold = [0-9.]+f | public float animationResetThreshold = 0.09f;
SAME     afterimageLifetime = [0-9.]+f      | public float afterimageLifetime = 0.22f;
SAME     alwaysHighFps = (true|false)       | public boolean alwaysHighFps = true;
DIFF     hideNativeCaret = (true|false)     | public boolean hideNativeCaret = true;
SAME     int ACTIVE_DELAY_MS = [0-9]+       | static final int ACTIVE_DELAY_MS = 8;
SAME     int IDLE_DELAY_MS = [0-9]+         | private static final int IDLE_DELAY_MS = 120;
SAME     int IDLE_UNFOCUSED_DELAY_MS = [0-9]+ | private static final int IDLE_UNFOCUSED_DELAY_MS = 600;
SAME     int MAX_ADAPTIVE_DELAY_MS = [0-9]+ | private static final int MAX_ADAPTIVE_DELAY_MS = 40;
SAME     MAX_DT = [0-9.]+ / [0-9]+          | private static final double MAX_DT = 1.0 / 20;
---- 19 项中 DIFF 数 = 1 ----
```

D 段（死字段 grep）我按「排除 `settings/`」口径独立复算：

```
总命中 = 37
排除 settings/ 后 = 12
  core/NeovideConfig.java:198    core/NeovideConfig.java:215
  core/NeovideConfig.java:221    core/NeovideConfig.java:223
  ui/CaretAnimator.java:1175     ui/CaretAnimator.java:1176
  ui/CaretAnimator.java:1226     ui/CaretAnimator.java:1230
  ui/CaretTrailPanel.java:108    ui/CaretTrailPanel.java:109
  ui/NeovideCaretManager.java:354  ui/NeovideCaretManager.java:355
```

**判定 PASS**：DIFF 数 = 1 且那一项**确实**是 `hideNativeCaret`（其余 18 项 SAME）；
排除 `settings/` 后 **= 12**，与契约 §10.3 的口径逐项对得上（4 + 4 + 2 + 2）。

### 2.3 IME 几何判据 —— 29 项

命令：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\ime-probe\run-ime-geom-test.ps1
```

原始输出尾部（`build\v3-ime.log`）：

```
  PASS  A13b 行尾但非 virtual-space 时，x 不一致仍应判为中间态   refreshTarget=false（期望 false：排除条件不能过宽）
  PASS  B1 被拦的帧不触碰角点坐标（判据只影响「何时重算」）   moved=false cornersUnchanged=true
=== 结果：通过 29，失败 0 ===
ALL OK
==> ALL OK（29 项边界全通过）
```

**判定 PASS（29/29）**。

---

## 3. 4 个缺陷的独立验证

### 3.0 方法：先证明「修复前必红」

Captain 提醒过「不要只验修复后全绿」。我做的是**最强的那种**：
把 `git archive HEAD`（= 0.9.4 真实源码，17 个 .java）导出到 `build\v-baseline\`，编译成独立 classes，
然后让**同一份探针**（`VBaseline.java`）在两棵树上各跑一次。
它只用两棵树都存在的 API（组件树遍历 / `isModified()` / 反射调 `applyNativeCaretHidden`），
所以**不存在「探针只对 0.9.5 有效」的自我安慰**。

复跑命令：

```powershell
git -C D:\deepseekwork\neovim-cursor-pr archive --format=zip -o build\v-baseline\base.zip HEAD src/main/java
Expand-Archive build\v-baseline\base.zip build\v-baseline -Force
# 基线 classes（17 个 .java）
javac --release 21 -nowarn -g -encoding UTF-8 -classpath <idea-libs> -d build\v2-probe\baseline-classes <17 个 .java>
javac --release 21 -nowarn -encoding UTF-8 -classpath "<baseline-classes>;<libs>" -d build\v2-probe\baseline-probe tools\settings-probe\VBaseline.java tools\settings-probe\V0Env.java
# 同一份探针跑两次
java <jvm-args> -cp "<baseline-probe>;<baseline-classes>;<libs>" VBaseline BASELINE-0.9.4
java <jvm-args> -cp "<current-probe>;<current-classes>;<libs>"   VBaseline CURRENT-0.9.5
```

**基线 0.9.4 的真实输出**：

```
=== V-BASELINE 对照探针（树 = BASELINE-0.9.4）===
    组件统计：slider=8 textField=8 label=8 checkbox=0
  FAIL  B-1 滑块拖动时标签跟随（0.9.4 期望 0/N；0.9.5 期望 N/N）   [0/8 跟随]
  FAIL  B-2 每个滑块恰好 1 个自挂 ChangeListener   [0/8 恰为 1]
  FAIL  B-3 刻度标签条目数 <= 3   [超限 8 个，条目数 = 51/201/21/101/12/56/101/71/]
  FAIL  B-4 Tools 关掉的 overlayFadeOut 不被面板 apply 冲回   [overlayFadeOut=true]   <= 被冲回
  PASS  B-5 拖滑块后 isModified()=true（0.9.4 现状也是 true；不得回归）   [true]
  FAIL  B-6 切主题后原生光标【不】复活   [路径=0.9.4 无监听器（只有无参 applyNativeCaretHidden）
                                         新 scheme 光标 = java.awt.Color[r=255,g=0,b=0]（复活=true）]
=== V-BASELINE(BASELINE-0.9.4) 结果：PASS 1，FAIL 5 ===
```

**当前 0.9.5 的真实输出**（同一份探针字节不变）：

```
=== V-BASELINE 对照探针（树 = CURRENT-0.9.5）===
    组件统计：slider=10 textField=10 label=10 checkbox=0
  PASS  B-1 滑块拖动时标签跟随   [10/10 跟随]
  PASS  B-2 每个滑块恰好 1 个自挂 ChangeListener   [10/10 恰为 1]
  PASS  B-3 刻度标签条目数 <= 3   [超限 0 个，条目数 = 3/3/3/3/3/3/3/3/3/3/]
  PASS  B-4 Tools 关掉的 overlayFadeOut 不被面板 apply 冲回   [overlayFadeOut=false]
  PASS  B-5 拖滑块后 isModified()=true   [true]
  PASS  B-6 切主题后原生光标【不】复活   [路径=0.9.5 CaretSchemeWatcher.handleSchemeChange
                                         新 scheme 光标 = java.awt.Color[r=0,g=0,b=0]（复活=false）]
=== V-BASELINE(CURRENT-0.9.5) 结果：PASS 6，FAIL 0 ===
```

> **B-6 的路径选择是刻意做的**：0.9.4 全仓库没有监听器，所以「切主题」= 什么都不发生 → 光标复活；
> 0.9.5 有 `CaretSchemeWatcher` 订阅 `EditorColorsManager.TOPIC`，回调里走 force 版。
> 若两棵树都只调无参 `applyNativeCaretHidden`，0.9.5 也会**按设计**早退 ——
> 那样测的就不是「缺陷是否修好」而是「早退语义是否保留」，会**误报**。
> 探针用反射探测树上有没有 `CaretSchemeWatcher` 来自动选路径，并把实际走的路径打印出来。

### 3.1 缺陷 1（滑块不跟手）—— 修复前 0/8，修复后 10/10

命令：`pwsh -File tools\settings-probe\build-v-probes.ps1`（跑 `VDefects`）。

驱动方式与用户一致：直接 `JSlider.setValue(...)`，读右侧 `JLabel` 文本；
每个滑块测**两次不同目标**（最大刻度 → 中点刻度），避免「只跳一次」被误判为跟随。

```
--- 缺陷 1：滑块 → 标签实时跟随 ---
    PASS cursorDisappearDelay   listeners=1  max:「50 ms」→「500 ms」  mid:「500 ms」→「250 ms」
    PASS cursorFadeOutDuration  listeners=1  max:「75 ms」→「1000 ms」  mid:「1000 ms」→「500 ms」
    PASS tailOpacity            listeners=1  max:「1.00」→「1.00」  mid:「1.00」→「0.50」
    PASS glowOpacity            listeners=1  max:「0.55」→「1.00」  mid:「1.00」→「0.50」
    PASS glowLayers             listeners=1  max:「8 层」→「12 层」  mid:「12 层」→「6 层」
    PASS shadowBlurFactor       listeners=1  max:「0.20」→「0.60」  mid:「0.60」→「0.30」
    PASS trailSize              listeners=1  max:「1.00」→「1.00」  mid:「1.00」→「0.50」
    PASS stretchRatioK          listeners=1  max:「4.5x 后缘 110 ms / 前缘 24 ms...」→「8.0x ...前缘 14 ms...」
    PASS animationLength        listeners=1  max:「0.10 s」→「0.35 s」  mid:「0.35 s」→「0.20 s」
    PASS shortAnimationLength   listeners=1  max:「0.11 s」→「0.25 s」  mid:「0.25 s」→「0.13 s」
  PASS  D1-1 10/10 滑块拖动时数值标签跟随（两次不同目标都跟随）   [10/10]
  PASS  D1-2 每个滑块恰好 1 个 ChangeListener（未受 showValueHint 污染）   [10/10]
```

> 头两行 `tailOpacity` / `trailSize` 的「max」一列没有变化是**正确行为**：
> 它们拖前的值就是上限 100（=1.00）。我第一版探针把目标写死成「最大刻度」，于是这两条假报 FAIL；
> 改成「目标必须 != 当前值」后通过。**这条记在这里，因为探针自身也会撒谎 —— 假 FAIL 同样是缺陷。**

**判定 PASS**。对照 §3.0：0.9.4 是 `0/8 跟随` + `listener=0`。

### 3.2 缺陷 2（刻度数字挤压）—— 修复前 8/8 超限（51/201/...），修复后全部 3 条

```
--- 缺陷 2：刻度标签条目数 <= 3 ---
    PASS cursorDisappearDelay   labelTable=3 paintLabels=true paintTicks=false snap=true
    PASS cursorFadeOutDuration  labelTable=3 paintLabels=true paintTicks=false snap=true
    PASS tailOpacity            labelTable=3 ...   PASS glowOpacity / glowLayers / shadowBlurFactor
    PASS trailSize              labelTable=3 ...   PASS stretchRatioK / animationLength / shortAnimationLength
  PASS  D2-1 10/10 滑块：labelTable 属于 [2,3] 且 paintLabels=true / paintTicks=false / snap=true
```

**判定 PASS**。我另外核了 `paintLabels=true` 这一条**必须显式设**：`SliderKt.labelTable` 的字节码是
`new Hashtable -> setLabelTable`，**不会**碰 `paintLabels`（javap 复核，与契约 A-6 一致）。

### 3.3 缺陷 3（Tools 覆盖面板）—— 修复前被冲回 true，修复后保住 false

```
--- 缺陷 3：Tools 改动不被面板覆盖 ---
  PASS  D3-1 Tools 关掉的 overlayFadeOut 在面板 apply 后仍为 false   [overlayFadeOut=false]
  PASS  D3-2 Tools 关掉的 alwaysHighFps 在面板 apply 后仍为 false     [alwaysHighFps=false]
  PASS  D3-3 用户拖过的 glowLayers 生效（=4）                        [glowLayers=4]
  PASS  D3-4 用户没碰的 overlayFadeOut 保住 Tools 的 false            [overlayFadeOut=false]
  PASS  D3-5 连续两次 apply 后 glowLayers 仍为 6                      [glowLayers=6]
```

D3-3 / D3-4 是**双向**判据：既要「Tools 不被覆盖」，也要「用户改动必须生效」——
只做前者会把修法做成「谁都不写」。D3-5 专抓 `baseline` 忘记前移的写法。

**判定 PASS**。对照 §3.0：0.9.4 的 `overlayFadeOut` 被冲回 `true`。

### 3.4 缺陷 4（切主题后原生光标复活）—— 14 条断言

```
  PASS  S-0  首次隐藏后旧 scheme 的 CARET_COLOR 变成全透明   [rgba(0,0,0,0)]
  PASS  S-0b savedCaretColor 记下旧 scheme 的原色            [rgba(169,183,198,255)]
  PASS  S-1  【修复前行为必须保留】无参版本在已隐藏时早退：新 scheme 光标仍可见  [rgba(255,0,0,255)]
  PASS  S-2  force=true 后新 scheme 的 CARET_COLOR 变成全透明               [rgba(0,0,0,0)]
  PASS  S-2b force=true 时 savedCaretColor 更新为新 scheme 的原色（不是旧主题色） [rgba(255,0,0,255)]
  PASS  S-3  handleSchemeChange（enabled + hideNativeCaret）后新 scheme 被重新隐藏 [rgba(0,0,0,0)]
  PASS  S-3b hideNativeCaret=false 时切主题不动配色                          [rgba(0,0,255,255)]
  PASS  S-3c enabled=false 时切主题不动配色                                  [rgba(0,0,255,255)]
  PASS  S-4pre 端到端前置：savedCaretColor 已为非 null（模拟「已经隐藏过一次」） [rgba(17,34,51,255)]
  PASS  S-4  install() 建立订阅（首次返回 true）
  PASS  S-4b install() 幂等（第二次返回 false）
  PASS  S-4c 订阅的是 EditorColorsManager.TOPIC 上的 EditorColorsListener   [topic=EditorColorsListener]
  PASS  S-4d 经 TOPIC 广播后新 scheme 被重新隐藏（端到端）                    [rgba(0,0,0,0)]
  PASS  S-5  dispose() 后 listener 被清空（不泄漏）                           [null]
```

> 两个设计要点：
> 1. **S-1 故意断言「无参版仍然早退」**。这条如果为了「让缺陷探针变绿」而去掉，会让老调用点
>    （每次新建编辑器都会调一次）重复写全局配色 —— 那是把缺陷换成另一个缺陷。
> 2. **S-4pre 是我加的前置**。第一版探针没做「先隐藏过一次」，于是 `savedCaretColor` 是 null，
>    去掉 force 的变异**也不会早退**，S-4d 就测不出「监听器真的走了 force」。这条前置让 MUT-D 忠实变红（见 §4）。

**判定 PASS**。对照 §3.0：0.9.4 的新 scheme 光标是 `r=255,g=0,b=0`（复活）。

---

## 4. 变异测试反向验证（忠实还原 + diff 证据）

命令：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-v-mutations.ps1
```

做法：把 `src/main/java` **复制**到 `build\v-mut\<MUT>\src`，只在副本上做字面替换，
然后编译副本 + 跑探针。**工作区源码始终是修复后形态**（`git status` 全程干净）。
每次变异都打印 `git diff --no-index`，确认改动恰好是修法引入的那几行。

| 变异 | 忠实还原的形态 | 变红断言 | 结果 |
|---|---|---|---|
| **MUT-A** 回滚缺陷 1 修法 | 删掉 `b.slider.addChangeListener(...)` **整段**（10 行，无残留） | D1-1、D1-2（+ 10 条逐滑块明细） | 13 条红 OK |
| **MUT-B** 回滚缺陷 2 修法 | 三关键刻度 → 整条网格 `for (t=min; t<=max; t+=step)`，`setPaintTicks(true)` | D2-1（+ 10 条明细，labelTable 11/41/21/.../24） | 12 条红 OK |
| **MUT-C** 回滚缺陷 3 修法 | 16 处脏标记判断 → `if (true)`（等价原「无条件下发」） | D3-1、D3-2（+ D3-4） | 4 条红 OK |
| **MUT-D** 回滚缺陷 4 修法 | `if (!force && savedCaretColor != null)` → `if (savedCaretColor != null)`（单行） | S-2、S-2b、S-3、S-4d | 5 条红 OK |
| **MUT-E** 回滚 V11 修法 2 | `isModified()` 去掉 `dirtyAnyField(pending, cur)` 那一半 | V11-1 | 2 条红 OK |
| **MUT-F** 回滚 V11 修法 3 | 滑块 getter 从 `snapToTick(...)` 改回 `spec.readTick.applyAsInt(pending)` | V11-3c、V11-4a、V11-4b（落库变 350） | 4 条红 OK |

汇总：`V-MUTATIONS RESULT: ALL PASS（每个修法回滚后探针都按期望变红）`

**关于「互不连带」**：脚本末尾会打印「MUT-x 连带变红 N 条」。那 N 条**不是**连带，
而是**同一条断言的逐项明细**（我把每个滑块的明细也当成一条断言，故 D1-1 变红时 10 条明细一起红）。
按「断言的语义」判定，连带数为 0：

- MUT-A 只打红 **缺陷 1** 的断言（D1-x），不打红 D2/D3/S/V11 的任何一条；
- MUT-B 只打红 **缺陷 2**（D2-1），不打红 D1/D3/S；
- MUT-C 只打红 **缺陷 3**（D3-1 / D3-2 / D3-4），D3-3（用户改动生效）**仍然 PASS** —— 这条最关键：
  说明「全量写」的反向不是「谁都不写」，判据确实分得开；
- MUT-D 只打红 **缺陷 4**（S-2 / S-2b / S-3 / S-4d），S-1（无参早退）仍 PASS；
- MUT-E 只打红 V11-1（B-1 方向），V11-3c（B-2 方向）仍 PASS；
- MUT-F 只打红 V11-3c / V11-4a / V11-4b（B-2 方向），V11-1 仍 PASS。

**MUT-E 与 MUT-F 互不连带**是契约 §11.1 的硬要求，实测满足：修法 2 与修法 3 各自独立可回滚。

### 4.1 忠实性核验（逐条 diff）

MUT-D（最关键的一条，单行替换）：

```diff
@@ -636,3 +636,3 @@ public class NeovideCaretManager implements EditorFactoryListener {
         try {
-            if (!force && savedCaretColor != null) {
+            if (savedCaretColor != null) {
                 return; // 已经隐藏过，无需重复操作
```

删除行**恰好**是修法引入的那一行，没有多余守卫、没有顺序改动 → 忠实。

MUT-A（整段删除，diff hunk 头 `@@ -665,12 +665,2 @@`）：12 行里删掉 10 行代码、保留 2 行注释分隔，
删掉的是 `b.slider.addChangeListener(e -> { ... });` 整个语句块，无残留 → 忠实。

MUT-C（16 处）：diff 里 16 组 `- if (pending.X != baseline.X) {` / `+ if (true) {`，
**只**改了条件表达式，赋值体一行没动 → 忠实（等价于「全量写」）。

### 4.2 独立复跑实现者的 4 个变异（不采信其数字，但核对形态）

`pwsh -File tools\settings-probe\run-4bugs-probe.ps1`（`build\v4-4bugs.log`）：

```
  修复后形态                          : 全绿 OK
  MUT-1（回滚 isModified 的第 3 半）   : 变红断言 1 条
  MUT-2（回滚 snapToTick）             : 变红断言 3 条
  MUT-3（回滚 ChangeListener）         : 变红断言 12 条
  MUT-4（回滚 force 重入）             : 变红断言 3 条
ALL 4BUG PROBES OK
```

**与实现者自述的差异**：实现者报 MUT-2 = 3 条、MUT-3 = 12 条，我复跑得到相同数字。
（我自己的 MUT-B / MUT-A 分别是 12 / 13 条，因为断言的**粒度**不同 —— 我这版把「逐滑块明细」也算成断言。
**只是计数口径不同，红 / 绿的结论一致。**）

> Captain 提醒的「阶段 2 的 FAIL 和真失败长得一样」我完全避开：我的脚本把每个变异的输出
> 落到 `build\v-mut\<MUT>\<Probe>.txt` 再按阶段解析，从不截 `Select-Object -Last N`。

---

## 5. 预设正交性（逐字段、逐位）

命令：`build-v-probes.ps1` → `VPreset`（25 条断言）。

比对方式：`NeovideCursorSettings.State` 的**每个 public 字段**做快照，
`float` 用 `Float.floatToRawIntBits`（**不是 EPS，不是「差不多」**），
`String` 用原值，`enum` 用 `name`。

### 5.1 换 7 个动画预设 → 颜色字段逐位不变

先把 `tailColor=#123456` / `shadowColor=#ABCDEF` 设成「一定不等于任何预设」的值，再逐个切换：

```
--- V5a：换动画预设 → 颜色字段逐位不变 ---
    PASS 预设[0] 原版粉色  tail=String:#123456  shadow=String:#ABCDEF
         animationLength       float:0x3dcccccd(0.1)   ->  float:0x3dcccccd(0.1)
         shortAnimationLength  float:0x3de147ae(0.11)  ->  float:0x3de147ae(0.11)
         trailSize             float:0x3f800000(1.0)   ->  float:0x3f800000(1.0)
         glowLayers            Integer:8               ->  Integer:8
         shadowBlurFactor      float:0x3e4ccccd(0.2)   ->  float:0x3e4ccccd(0.2)
         glowOpacity           float:0x3f0ccccd(0.55)  ->  float:0x3f0ccccd(0.55)
    PASS 预设[1] 缎带      tail=String:#123456  shadow=String:#ABCDEF
         animationLength       float:0x3dcccccd(0.1)   ->  float:0x3e8f5c29(0.28)
         （其余 5 个字段逐位不变）
    PASS 预设[2] Neovide 官方  animationLength -> 0x3e19999a(0.15)  shortAnimationLength -> 0x3d23d70a(0.04)
    PASS 预设[3] 社区·快        animationLength -> 0x3d75c28f(0.06)  shortAnimationLength -> 0x3d23d70a(0.04)
    PASS 预设[4] 社区·平滑      animationLength -> 0.10  short -> 0.04  trailSize -> 0x3e99999a(0.30)
                               blur -> 0x3e800000(0.25)  opacity -> 0x3ee66666(0.45)
    PASS 预设[5] 柔和          animationLength -> 0x3e3851ec(0.18)  short -> 0.08  trailSize -> 0.80
                               glowLayers -> 6  blur -> 0.30  opacity -> 0.40
    PASS 预设[6] 高对比        animationLength -> 0x3df5c28f(0.12)  short -> 0.10  glowLayers -> 10
                               blur -> 0x3e19999a(0.15)  opacity -> 0x3f400000(0.75)
  PASS  V5a-1 7 个动画预设都不改任何颜色字段（逐位）   [0 个预设污染颜色]
```

三个补充判据（同一探针）：

```
  PASS  V5a2 7 个动画预设都不改 stretchRatioK / trailMode   [0 个越界]
  PASS  V4-9  预设表数量：动画 7 + 颜色 5                   [anim=7 color=5]
  PASS  V4-1   animationLength 预设1 == 默认值   [0.1(0x3dcccccd) vs 0.1(0x3dcccccd)]
  PASS  V4-2   shortAnimationLength 预设1 == 默认值   [0.11(0x3de147ae)]
  PASS  V4-3   trailSize 预设1 == 默认值   [1.0(0x3f800000)]
  PASS  V4-4   shadowBlurFactor 预设1 == 默认值   [0.2(0x3e4ccccd)]
  PASS  V4-5   glowOpacity 预设1 == 默认值   [0.55(0x3f0ccccd)]
  PASS  V4-6   glowLayers 预设1 == 默认值   [8 vs 8]
  PASS  V4-7   tailColor 预设1 == 默认值   [#FFC0CB vs #FFC0CB]
  PASS  V4-8   shadowColor 预设1 == 默认值   [#FFC0CB vs #FFC0CB]
  PASS  V4-10  State 初值 animationLength == 默认值   [0.1(0x3dcccccd)]
  PASS  V4-11  State 初值 shortAnimationLength == 默认值   [0.11(0x3de147ae)]
  PASS  V4-12  5 个颜色预设的 HEX 全部大写
```

### 5.2 换 5 个颜色预设 → 动画字段逐位不变

先把 6 个动画字段 + `stretchRatioK` 设成「非任何预设」的组合，再逐个切换：

```
  PASS  V5b-1 5 个颜色预设都不改任何动画字段 / stretchRatioK（逐位）   [0 个预设越界]
```

逐字段 before/after 在 `build\v2-probe\VPreset.txt` 里（每个预设打印 7 行：6 个动画字段 + stretchRatioK）。

### 5.3 刻度网格覆盖（V3）

不只看面板量程，而是把「默认值 + 全部预设值」逐个对 `(v - min) % step == 0`：

```
    PASS cursorDisappearDelay     全部 1 个取值都在网格上（[0,500] step=50）
    PASS cursorFadeOutDuration    全部 1 个取值都在网格上（[0,1000] step=25）
    PASS tailOpacity              全部 1 个取值都在网格上（[0,100] step=5）
    PASS glowOpacity              全部 8 个取值都在网格上（默认 55 + 7 个预设）
    PASS glowLayers               全部 8 个取值都在网格上（[1,12] step=1）
    PASS shadowBlurFactor         全部 8 个取值都在网格上（[5,60] step=5）
    PASS trailSize                全部 8 个取值都在网格上（[0,100] step=5）
    PASS stretchRatioK            全部 1 个取值都在网格上（[10,80] step=5）
    PASS animationLength          全部 8 个取值都在网格上（[5,35] step=1）
    PASS shortAnimationLength     全部 8 个取值都在网格上（[2,25] step=1）
  PASS  V3-1 默认值 + 全部预设值的越界数 = 0   [越界 0 项]
  PASS  V3-2 面板 10 个滑块的量程与契约 §6.2 逐项一致   [0 项不符]
```

**判定 PASS**。这条最容易「一打开设置页默认值就漂移」，我把默认刻度单独核过：
`0.075f x 1000 = 75` 必须在 step=25 的网格上（50 的网格会漂到 50 或 100）；
`0.55 / 0.45 / 0.40 / 0.75` 必须在 step=5 的网格上（25 的网格会漂到 50）。全过。

---

## 6. 滑块吸附 + 输入框双向绑定

命令：`build-v-probes.ps1` → `VSnap`（23 条）。

### 6.1 非刻度值必须落到刻度（`setValue`）

```
    PASS cursorDisappearDelay   请求 166 -> 落位 150（step=50 网格=true 最近=true 就近取整=true）
    PASS cursorFadeOutDuration  请求 83  -> 落位 75 （step=25 ...）
    PASS tailOpacity            请求 16  -> 落位 15 （step=5 ...）
    PASS glowOpacity            请求 16  -> 落位 15
    PASS shadowBlurFactor       请求 21  -> 落位 20
    PASS trailSize              请求 16  -> 落位 15
    PASS stretchRatioK          请求 26  -> 落位 25
  PASS  SNAP-1 7/7 可测滑块（step>1）的 setValue(非刻度) 全部落到最近刻度
```

（step=1 的 3 个滑块没有「非刻度整数」可测，按设计跳过 —— 这条我一开始算成 8/8，是自己的计数 bug，已改。）

### 6.2 精确值 333 不被吸附值 350 覆盖

```
  PASS  TYP-1 键入 333 后滑块吸附到 350                                     [slider=350]
  PASS  TYP-2 键入期间 pending 仍为旧值 50（apply 前不写）                  [pending=50]
  PASS  TYP-3 标签显示用户输入的精确值 333（不篡改）                        [label="333 ms"]
  PASS  TYP-4 apply 后落库 333（未被 350 覆盖）                             [state=333]
  PASS  TYP-5 apply 后 isModified()=false（精确值 vs 刻度值的差异不制造假脏） [false]
```

### 6.3 非法值三档

```
  PASS  ILL-1 键入越界 9999 时 pending 不变（不在键入中 clamp）   [pending=50]
  PASS  ILL-2 失焦后 clamp 到上限 500 并写入 pending              [pending=500]
  PASS  ILL-3 失焦后输入框文本规整为 500                          [tf="500"]
  PASS  ILL-4 键入非数字不写 pending、不抛异常                     [pending=50]
  PASS  ILL-5 非数字失焦后回滚为 pending 值 50                     [tf="50" pending=50]
  PASS  ILL-6 空串不写 pending、不抛异常                           [pending=50]
  PASS  ILL-7 空串失焦后回滚为 50                                  [tf="50"]
  PASS  ILL-8 非法值处理后 isModified()=false（没制造假脏）        [false]
```

「键入中不 clamp、失焦才 clamp」是刻意的（`9999` 可能是 `999` 的前缀），我单独核了这条语义成立。

### 6.4 契约 §6.1 没覆盖的场景：拖动与键入的先后（我加的）

```
    场景1（先输 333 再拖 400）：落库 = 400，滑块 = 400，输入框 = "400"
  PASS  MX-1 先输后拖：落库 == 400（最后一次操作是拖动）
    场景2（先拖 400 再输 333）：落库 = 333，滑块 = 350，输入框 = "333"
  PASS  MX-2 先拖后输：落库 == 333（最后一次操作是键入）
```

**语义正确**：永远是「最后一次用户操作」胜出。契约 §6.1 的表格没写这两个组合，建议补进去（与 F-2 相关）。

---

## 7. animationLength / shortAnimationLength 边界

### 7.1 四端点 + 越界 clamp（`VSnap`）

```
  PASS  BND-0 滑块量程：cross=[5,35] short=[2,25]
  PASS  BND-1 animationLength 下端点 0.05 落库         [0.05 (raw=0x3d4ccccd)]
  PASS  BND-2 animationLength 上端点 0.35 落库         [0.35 (raw=0x3eb33333)]
  PASS  BND-3 shortAnimationLength 下端点 0.02 落库    [0.02 (raw=0x3ca3d70a)]
  PASS  BND-4 shortAnimationLength 上端点 0.25 落库    [0.25 (raw=0x3e800000)]
  PASS  BND-5 state 里 animationLength=9.99 时滑块被 clamp 到 35    [slider=35]
  PASS  BND-6 state 里 shortAnimationLength=-5 时滑块被 clamp 到 2   [slider=2]
  PASS  BND-7 键入 999 失焦后 clamp 到 0.35 并落库                   [0.35 (raw=0x3eb33333)]
  PASS  BND-8 面板里没有 animationResetThreshold 滑块（契约 §7.5 禁止项）  [sliders=10]
```

### 7.2 独立复算「上限 0.35」的量化依据（`VSpring`，驱动**真实的** `DampedSpring`）

`dt = 8ms`（= `ACTIVE_DELAY_MS`），初始位移 32px（一行），数到静止：

```
  animLen    首帧走完   运动帧数   运动时长
  0.02       47.51      6          48
  0.03       28.87      9          72
  0.04       19.12      13         104
  0.05       13.52      16         128
  0.06       10.05      19         152
  0.10       4.15       32         256
  0.15       1.98       49         392
  0.28       0.61       92         736
  0.35       0.39       115        920      <= 契约 A-11 报 115，我复算 115
  0.40       0.30       131        1048
  0.50       0.20       164        1312     <= 契约 A-11 报 164，我复算 164
```

```
  PASS  SPR-1 上端 0.35 的运动帧数在 100-130 之间   [实测 115 帧]
  PASS  SPR-2 0.50 的运动帧数在 150-180 之间        [实测 164 帧]
  PASS  SPR-3 0.35 与 0.50 的帧数差 > 30            [差 49 帧]
  PASS  SPR-4 下端 0.05 在正常帧 dt=8ms 下【不会】首帧归零  [16 帧 / 128ms]
  PASS  SPR-5 animationLength=0.05 且 dt=MAX_DT(0.05) 时第一次 update 即归零  [moved=false]
  PASS  SPR-6 animationLength=0.06 配 dt=0.05 时不会一帧归零
```

**结论**：契约 §7.2 的量化依据**可复现**（115 / 164 逐值吻合）。
但「下限 0.05s 是硬边界」的说法需要精确化：0.05 在正常帧（dt=8ms）下能跑 128ms，
只有某一帧的 dt 冲到 50ms（MAX_DT）时才会被首句短路。契约 §7.2 的**正文**写对了
（「若 animationLength <= 0.05，**任何一次最大 dt** 都会一帧短路」），但 §9.4 的**面板文案**把
「最大 dt」这个前提省掉了 —— 见 F-1 / F-5。

### 7.3 短跳分支的有效时长（我追加的，因为发现 §7.1 与 §9.4 矛盾）

`TrailCorner.jump():221` 的短跳分支是 `shortAnimationLength x trailFactors[rank]`。
真正的有效时长是**乘完系数之后**的值：

```
    trailFactors = [1.00, 0.72, 0.42, 0.22]
    short=0.02： rank0=20.0ms rank1=14.4ms rank2=8.4ms rank3=4.4ms(一帧归零)
    short=0.05： rank0=50.0ms rank1=36.0ms rank2=21.0ms rank3=11.0ms
    short=0.11： rank0=110.0ms rank1=79.2ms rank2=46.2ms rank3=24.2ms
    short=0.25： rank0=250.0ms rank1=180.0ms rank2=105.0ms rank3=55.0ms
  PASS  SPR-7 shortAnimationLength=0.02（滑块下限）时，rank3 前缘的有效时长 4.4ms 低于一个 8ms 帧
         -> 该分支被 DampedSpring 首句短路
```

**判定 PASS**（参数边界本身正确）。但这组数据把 F-1 从「文案措辞问题」升级成「面板自相矛盾」：
滑块允许 0.02，而同一行的文案说「0.05 是硬边界」；且 0.02 时 rank3 分支确实被短路。

**追加实测（SPR-8）：前缘在「正常帧」下的失效边界**

`DampedSpring.update` 首句是 `animationLength <= dt` 则归零。正常帧 dt = 8ms，
而 rank3 有效时长 = `short × 0.22`，所以算术边界是 `8 / 0.22 / 1000 = 0.0364`。
我用真实 `DampedSpring` 逐刻度实测，确认它落在 0.03 与 0.04 之间：

```text
short=0.01  rank3=  2.2ms  一帧归零
short=0.02  rank3=  4.4ms  一帧归零      <= 滑块下限
short=0.03  rank3=  6.6ms  一帧归零
short=0.04  rank3=  8.8ms  能跑          <= 第一个能跑的刻度
short=0.05  rank3= 11.0ms  能跑
short=0.09  rank3= 19.8ms  能跑
```

`PASS SPR-8 实测边界：short=0.03 时前缘一帧归零、short=0.04 时能跑（第一个能跑的刻度 = 0.04）`

**追加实测（SPR-9）：长帧（dt = MAX_DT = 50ms）下的失效边界**

同一个 `DampedSpring.update` 首句，把 dt 换成 50ms，算术边界变成 `50 / 0.22 / 1000 = 0.2273`：

```text
short=0.18  rank3= 39.6ms  一帧归零
short=0.19  rank3= 41.8ms  一帧归零
short=0.20  rank3= 44.0ms  一帧归零
short=0.21  rank3= 46.2ms  一帧归零
short=0.22  rank3= 48.4ms  一帧归零
short=0.23  rank3= 50.6ms  能跑          <= 长帧下第一个能跑的刻度
short=0.24  rank3= 52.8ms  能跑
short=0.25  rank3= 55.0ms  能跑          <= 滑块上限
```

`PASS SPR-9 长帧边界实测：short=0.22 前缘一帧归零、short=0.23 能跑（第一个能跑的刻度 = 0.23）`

**两个边界必须分开说（这是 F-1 文案的根因）**：

| 口径 | `dt` | 真实阈值 | 滑块区间内的含义 |
|---|---|---|---|
| 日常帧 | 8 ms | **0.0364 s** | `0.02–0.03` 日常就瞬移；`0.04` 起日常正常 |
| 长帧 | 50 ms | **0.2273 s** | `0.02–0.22` 卡顿时前缘归零；`0.23` 起卡顿也正常 |

**追加实测（SPR-10）：captain 第一版文案的反证**

`short=0.20` 时 rank3 = 44.0ms，确实 < 50ms。若按「低于 0.05s / 短于单帧 50ms 就归零」的说法，
0.20 也该被归零 —— 但实测：

```text
PASS  SPR-10  short=0.20: dt=8ms 跑=true / dt=50ms 跑=false
```

即 **0.20 在日常帧下完全正常**（44ms ≫ 8ms），只在卡顿时前缘归零。
所以「拿 50ms 口径去定义一个 0.05 的分界」是错的 —— captain 已复核确认并改成两边界三段式文案（§10.0.1）。

---

## 8. 帧率不回归（p50 / p95 / p99 / maxGap，3 轮）

命令：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\frame-probe\run-frame-compare.ps1
```

做法：`git archive` 导基线（默认 `8906609`）与当前树，各编译、各跑 3 轮 x 5 个场景 x 1500ms 窗口，
统计**相邻帧间隔**的分位数。判据按 `LESSONS.md` §3.2：噪声只会压低性能，所以报跨轮区间。

| 场景 | 基线 p50 | 当前 p50 | 基线 p95 | 当前 p95 | 判定 |
|---|---|---|---|---|---|
| R1 常驻刷新 / tick 0 | 8.03-8.04 | 8.03-8.08 | 9.03-9.52 | 9.02-9.49 | 区间重叠，无回归 |
| R2 常驻刷新 / tick 25 | 26.35-26.57 | 26.44-26.65 | 27.43-27.67 | 27.52-27.85 | 区间重叠，无回归 |
| R3 自适应 / tick 0 | 8.03-8.04 | 8.03-8.03 | 9.01-9.20 | 8.98-9.54 | 区间重叠，无回归 |
| R4 自适应 / tick 25 | 26.33-26.55 | 26.27-26.81 | 27.58-27.94 | 27.83-27.96 | 区间重叠，无回归 |
| R5 常驻 + 每 100ms 移动 | 25.30-25.60 | 25.42-25.59 | 26.89-27.20 | 26.81-27.05 | 区间重叠，无回归 |

p99 / maxGap（同一份日志）：

```
基线 R1 p99=9.60~10.02  maxGap=10.01~11.00   |  当前 R1 p99=9.11~9.70  maxGap=9.24~10.55
基线 R2 p99=27.95~27.97 maxGap=27.99~28.05   |  当前 R2 p99=27.86~28.24 maxGap=27.91~28.52
基线 R3 p99=9.11~10.01  maxGap=9.24~10.05    |  当前 R3 p99=9.52~10.01 maxGap=9.73~10.52
基线 R4 p99=28.01~28.33 maxGap=28.22~28.60   |  当前 R4 p99=27.93~28.28 maxGap=27.98~28.56
基线 R5 p99=27.11~27.68 maxGap=27.22~27.71   |  当前 R5 p99=27.85~28.15 maxGap=27.93~28.52
```

**判定 PASS（无系统性回归）**。理由：两边的**区间全部重叠**，中心值差 <= 0.2ms
（远小于一个 8ms 帧间隔）。按 `LESSONS` §3.2 的判据（「零方差 + 差 2 帧才算系统性差异」），
这里连方差都重叠，不构成系统性差异。

> **诚实说明**：这是**离线探针**（真实 `CaretAnimator` + 真实调度线程，用假 Editor 驱动），
> 不是真机 IDEA。它能抓「调度 / 绘制热路径的算法回归」，抓不到「真实渲染后端 + 真机 GPU」的差异。
> 真机复核步骤见 §12。

---

## 9. 默认值零改动 + 不做清单

### 9.1 D0：`core/NeovideConfig.java` 零 diff

```powershell
git -C D:\deepseekwork\neovim-cursor-pr diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java
-> exit 0，无输出

git -C ... status --porcelain -- src/main/java/.../core/
-> （空）   <= 连「新增文件」都没有
```

**判定 PASS**。而且 `core/` 整个目录相对 HEAD **完全干净**（没有新增文件）——
这比契约要求的 D0 更强（`CursorPresets` 被放在 `settings/`，见 F-4）。

### 9.2 D1：35 项禁改字面量

命令：`SETTINGS-CONTRACT.md` §5.3 的脚本原样执行。

```
禁改项缺失数 = 0   （期望 0）
检查项数 = 35
```

**判定 PASS**。

### 9.3 不做清单（契约 §10.4 / §10.5 / §10.2）

| 判据 | 期望 | 实测 |
|---|---|---|
| 多光标：`getCarets` | 0 | **0** |
| `core/AfterimageTrail.java` 零 diff | exit 0 | **exit 0** |
| `ui/CaretTrailPanel.java` 零 diff | exit 0 | **exit 0** |
| 辉光双色：`shadowColor2 / shadowColorEnd` | 0 | **0** |
| `ACTIVE_DELAY_MS` 仍不可配 | 声明字面量不变、无 UI 通路 | **11 处引用全是既有代码 / 注释，声明 `= 8` 未变** |
| Jewel / Compose：`@Composable / org.jetbrains.jewel` | 0 | **0** |
| `build.gradle.kts` 零 diff | exit 0 | **exit 0** |
| N-1 `new NeovideConfig` in CursorPresets | 0 | **1 处命中，但整行是 javadoc 注释**；排除注释行 = **0**（见 F-4 附注） |
| N-2 `SHORT_ANIMATION_LENGTH = ` | 0 | **0** |
| N-3 `0.1f` / `0.11f` 只在两处声明 | 是 | **是**（line 653 / 663；其余两处是 javadoc 提到，不是字面量赋值） |
| §5.8 `preset` in NeovideCursorSettings | 0 | **0** |
| §5.8 `NeovideCaretManager` in CursorPresets | 0 | **0** |
| §10.5 `onChanged / cellValidation / validationInfo` | 0 | **0** |
| §10.6 脚本自动变量赋值 | 0 | **0**（另有 38 处 `$repoRoot` / `$tmpRoot` 使用） |
| §10.6 `Remove-Item -Recurse` 目标 | 必须含 `$tmpRoot` / `build\` | **16 处全部指向 build 下的派生路径** |

**V14**：`State` 的 public 实例字段数 = **19**。
**V15**：默认值能匹配回预设 1；7 个动画预设 + 5 个颜色预设应用后都能反查回来；
Tools 两条写入路径同样正交（`V15-5` / `V15-6`）。

**判定 PASS**。

---

## 10. Findings（F-1 ... F-5）—— **captain 已裁决**

> 这 5 条**没有一条**造成用户数据损失或功能错误。F-1 / F-2 属于「会让下一个人踩坑」的准确性问题。
> 我按契约 §0.1 的口径列出，由 t5 审查 / t6 集成裁决。

### 10.0 裁决结果（captain 2026-10-20，本轮）

**五条全部「接受」，且全部判为「改文档 / 文案，不改行为」—— 不需要返修 t3，直接进 t5。**

| 编号 | 裁决 | 处理方式 | 是否触碰被测代码逻辑 |
|---|---|---|---|
| **F-1** | **接受** | 改文案（**量程 0.02–0.25 保持不变**） | 否（只改 `W_SHORT_LEN` 文案常量） |
| **F-2** | **接受** | 改 `addSlider` 注释 + 契约 V13 **由机械判据降级为行为判据** | 否（只改注释与契约条文） |
| **F-3** | **接受** | t6 给阶段 2 的 FAIL 加 `[预期变红]` 前缀 | 否（只改探针脚本输出） |
| **F-4** | **接受** | `CursorPresets` 保留在 `settings/`（captain 已裁决采纳）；N-1 的 grep 判据改为**排除注释行** | 否（文档口径 + 判据口径） |
| **F-5** | **接受** | §9.4 文案补「最大 dt」前提 | 否（文档） |

**F-1 为什么改文案而不是改量程**（captain 原话要点）：`0.02–0.25` 是用户明确批准的范围；
「0.05 硬边界」是契约写文案时的过度简化。真实情况是 **0.02–0.05 区间前缘分支会被短路**
（观感上 = 前缘瞬移），但**后缘（rank0）仍然正常** —— 不是「不能用」，是「前缘不拉伸」。
所以文案要说明副作用，而不是禁止区间。

captain 采纳的新文案：

```text
短动画时长 = 同行打字/退格时的拖尾时长。
注意：低于 0.05s 时，前缘（rank3）的有效时长会短于单帧 50ms 而被一帧归零
（观感上前缘瞬移、后缘仍有拖尾）；0.05s 以上四角都能正常展开。
```

> **我对这段新文案的一条精确化补充（供 t6 落字时参考）**：
> 我用 `VSpring` 的 SPR-8 把「前缘在正常帧下的失效边界」**实测**出来了（不靠算术推）：
> `DampedSpring.update` 首句是 `animationLength <= dt` 则归零，正常帧 dt = 8ms，
> 而 rank3 有效时长 = `short × 0.22`，所以边界应在 `8 / 0.22 / 1000 = 0.0364` 附近。
>
> 实测（`build\v2-probe\VSpring.txt`）：
>
> ```text
> short=0.01  rank3=  2.2ms  一帧归零
> short=0.02  rank3=  4.4ms  一帧归零      <= 滑块下限
> short=0.03  rank3=  6.6ms  一帧归零
> short=0.04  rank3=  8.8ms  能跑          <= 第一个能跑的刻度
> short=0.05  rank3= 11.0ms  能跑
> ```
>
> **两个分界是不同的，新文案只描述了后者**：
> - **0.036s**（正常帧 dt=8ms 的边界）：`short < 0.036` 时前缘**在正常帧率下就永远瞬移**；
> - **0.05s**（captain 文案里的分界）：这是**长帧 dt=50ms** 的边界 —— `short ∈ [0.036, 0.05)`
>   正常帧能跑，只在某一帧冲到 50ms（切屏恢复）时被归零。
>
> 所以对 `0.02` 这个**滑块下限**，新文案「短于单帧 50ms 而被一帧归零」是偏轻描淡写的 ——
> 它不需要等长帧，正常帧率下就已经归零了。
>
### 10.0.1 【已升级为实质错误，非精度问题】captain 原文案的 0.05 阈值被 SPR-8/SPR-9 推翻

captain 复核后确认：他给的第一版文案**两个数都错**，并下了新裁决（改用下面这个两边界版本）。

**错的不是「精度」，是「把两个不同的 dt 口径混成一个阈值」**：

| 口径 | `dt` | rank3 有效时长必须 > | 真实阈值 = `dt / 0.22 / 1000` | captain 原文案写的 |
|---|---|---|---|---|
| **日常帧** | 8 ms | 8 ms | **0.0364 s** | （未提） |
| **长帧**（卡顿/切屏恢复） | 50 ms（`MAX_DT`） | 50 ms | **0.2273 s** | 「0.05s」← **错** |

原文案「低于 0.05s 时……短于单帧 50ms 而被一帧归零」有两个问题：
1. 50ms 口径的真实阈值是 **0.227s**，不是 0.05；
2. 它拿 0.05 当分界，却同时混用了 8ms 与 50ms 两个 dt。

**反证（我的 SPR-10 实测）**：`short=0.20` 时 rank3 = **44.0ms**，确实 < 50ms，
按原文案也该「被归零」—— 但实测它在**日常 8ms 帧下完全正常**（44ms ≫ 8ms）。
原文案会让用户以为 0.20 也不可用，**那是错的**。

```text
PASS  SPR-9  长帧边界实测：short=0.22 前缘一帧归零、short=0.23 能跑
             （算术边界 50/0.22/1000 = 0.2273，落在两者之间）[长帧下第一个能跑的刻度 = 0.23]
PASS  SPR-10 【推翻了 captain 原文案】short=0.20 时 rank3=44ms 虽 < 50ms，
             但日常 8ms 帧下完全正常（不会归零）—— 所以「低于 0.05s 归零」是错的
```

**captain 的最终裁决：改用下面的两边界版本（t6 直接抄这一段）**：

```text
短动画时长 = 同行打字/退格时的拖尾时长。

注意前缘（rank3）的有效时长 = 本值 × 0.22，两种情况下会被单帧短路成「前缘瞬移」：
  · 低于 0.036s：日常帧（8ms）下前缘就整段归零，后缘仍有拖尾
  · 0.036s ~ 0.227s：日常帧能正常展开，只在卡顿（单帧 ≥ 50ms）时前缘才归零
  · 0.227s 以上：四角都能完整展开
实测：0.03 起步步归零，0.04 起能跑。
```

captain 的理由（原话要点）：**用户要的是「我怎么选」，不是「一个魔法阈值」。三段式说清楚代价即可。**
我用 SPR-8 / SPR-9 逐刻度实测的两张表（§7.3）与这三段完全吻合。

**captain 决定把这件事写进 LESSONS 第九部分**（原文要点）：

> 任何人（包括 captain）给的数值结论，落字前必须有一方用真实实现复算过；算术推导不能替代实测。

这对应本轮的核心教训的一个新变体：既有「用户实测 > 我的推断」（坑 16），
现在加了「**他人实测 > 我的算术**」。我的补充看法：这次之所以能抓到，**不是因为我不信任 captain，
而是因为我的探针不接受「一个阈值」这种输入** —— `VSpring` 只会回答「这个 short 值在 dt=X 下能否跑起来」，
所以两个 dt 的口径差异是被**数据形状**逼出来的，而不是靠谁更细心。**判据的形状决定它能抓到什么。**

**F-2 为什么把 V13 从机械判据降级为行为判据**（captain 原话要点）：
我的三个反向实验说明得很清楚 —— 机械判据（callback 表里谁先谁后）**与真实保护机制无关**；
留着它会让后来者以为是顺序在保护，然后「为了安全」去调整顺序，反而可能破坏 snap。
改为行为判据：`键入 333 + apply → 落库必须仍是 333`（MUT-F 会让它红）。

captain 另外做了决定性验证并引用我的产物：读源码确认滑块 getter（`:655`）**有** snap、
输入框 getter（`:632`）**无** snap，再核 `build\v-mut\MUT-F\VDefects.txt` 里
`V11-4b … [cursorDisappearDelay=350] ← FAIL` —— 摘掉 snap 后 333 立刻被覆盖成 350。

**F-4 的裁决附带结论**：`CursorPresets` 保留在 `settings/`（captain 采纳实现者的理由）；
N-1 的机械 grep 判据改为**排除注释行**，避免被 javadoc 里那句 `{@code new NeovideConfig()}` 打成假阳性。

**「7 组 vs 8 组动画预设」已确认**：captain 明确这是契约 §0.2 的 **R-3 显式修订**
（「残影模式」改的是 `trailMode` 而非动画参数，故不进预设表），**不是遗漏**，会在最终报告里说明。

### 10.1 captain 复核时自己重算的两组数据（**其中一组已被更正**）

**F-1 的量化**（captain 独立复算）—— ⚠️ **其中「0.05 阈值」的结论已被我的 SPR-8/SPR-9 推翻，见下**：

```
源码 :213 ANIM_MIN=5   :217 SHORT_MIN=2          <= 滑块允许 0.02
源码 :125 文案「下限 0.05s 是硬边界」            <= 自相矛盾

量化：shortAnimationLength=0.02 × trailFactors[3]=0.22 = 4.4ms
      MAX_DT = 1/20 = 50ms  ->  4.4ms << 50ms，前缘必被一帧短路
```

**「4.4ms」这个数本身是对的**（那是 `short=0.02` 的 rank3 有效时长，我用 SPR-7 独立复现）。
但 captain 据此推出的「**低于 0.05s 归零**」**是错的** —— captain 已复核确认并更正，
正确阈值有两个：**0.036s（8ms 日常帧）/ 0.227s（50ms 长帧）**。详见 §10.0.1。

**这条差异本身就是本轮最有价值的发现**：它证明了「任何数值结论落字前必须有一方用真实实现复算过」——
captain 的算术推导（把 4.4ms 与 50ms 放一起比较，得出 0.05）在**口径上**就混了两种 dt，
而用真实 `DampedSpring` 逐刻度实测（SPR-8 / SPR-9 / SPR-10）能一次抓出两个阈值。
captain 已决定把这条写进 LESSONS 第九部分。

**F-2 的源码定位**（captain 独立核对）：

```
滑块 getter   :655  () -> snapToTick(readTick(pending), ...)   <= 有 snap
输入框 getter :632  () -> readTick(pending)                     <= 无 snap，精确值
```

与我在报告 §5/§6 的判定完全一致。

### F-1（severity: medium）面板文案与滑块量程自相矛盾：shortAnimationLength

- **位置**：`NeovideCursorConfigurable.java:124-125`（`W_SHORT_LEN`）与 `SHORT_MIN = 2`（line 218）
- **事实**：
  - 文案逐字是「短动画时长 = 同行打字/退格时的拖尾时长。**下限 0.05s 是硬边界**（单帧最大步长 0.05s，再短会被一帧归零）。」
  - 滑块量程是 `[2, 25]` 即 **0.02-0.25s**，允许用户选 0.02 / 0.03 / 0.04。
  - 我实测（`VSpring` SPR-7）：`short=0.02` 时 rank3 前缘有效时长 **4.4ms < 8ms 帧** → 该分支被 `DampedSpring` 首句短路。
- **影响**：用户照文案理解会把 0.05 当最小可用值，但滑块允许更低，且更低时**前缘分支确实失效**（后缘 rank0 仍正常）。不是数据损坏，是「文案承诺与实际能力不一致」。
- **根因在契约**：§7.1 给的是 `(2,25,1,1)`，§9.4 的 `W_SHORT_LEN` 却写「下限 0.05s」。实现者逐字照抄了两边，矛盾被原样带进产品。
- **建议修法**（二选一，需 captain 裁决）：
  1. 文案改成「下限 0.02s；低于 0.05s 时**前缘**分支会被单帧短路（后缘仍正常）」；
  2. 或把 `SHORT_MIN` 提到 5（0.05s），文案不动。
- **【captain 已裁决】接受，改文案、量程保持 0.02–0.25 不动** —— 但**不取我给的两个方案中的任何一个**：
  captain 第一版新文案里的「0.05 阈值」被我的 SPR-8/SPR-9/SPR-10 推翻（实质错误，非精度问题），
  最终改用**两边界三段式文案**。详见 §10.0.1。

### F-2（severity: medium）契约 V13 的机械判据不成立；代码注释的因果解释与事实相反

- **位置**：`NeovideCursorConfigurable.java:594-598`（`addSlider` 的 javadoc）与 `:626`（「输入框（必须建在滑块【之后】）」）
- **事实**：
  1. `row.slider(...)` **确实在** `row.intTextField(...)` **之前**调用（契约 §6.7 的**字面**要求满足）。
  2. 但 `SliderKt.bindValue(...)` 在输入框**之后**才调用，所以 `DialogPanel.getApplyCallbacks()` 的 `LinkedHashMap` 里 **10/10 行都是「textField 先、slider 后」** → 契约 V13 的**机械判据**（反射读 key 顺序）**不成立**（实测 `slider-先 0 行 / textField-先 10 行`）。
  3. **行为仍然正确**：`VSnap` TYP-4 实测 apply 后落库 `333`（未被 350 覆盖）。原因不是顺序，而是**修法 3**：滑块 getter 返回 `snap(pending)`，于是滑块的 apply 回调变成**空操作**（组件值 == getter 返回值）。
  4. 我做了三个反向实验证明这一点：
     - 把 `bindValue` 提前到滑块刚建完（**满足**机械判据）→ `VSnap` 23/23 仍全过；
     - 把**整个输入框块**移到滑块之前（真·反序）→ `VSnap` 23/23 仍全过、`VMixed` 2/2 仍全过；
     - 而在**正常顺序**下摘掉 snap getter（MUT-F）→ 落库变成 `350`（数据丢失）。
     → **数据丢失的充分条件是「有滑块 apply 且 getter 不 snap」，与注册顺序无关。**
- **影响**：注释里那句「**调换这三行的顺序就是静默的数据丢失**」在**当前代码里是错的**。下一个维护者若照它去调顺序，会以为动了危险区域；更糟的是，若他为了「满足注释」去调顺序、而因为别的原因去掉 snap getter，注释会给他错误的因果模型。
- **建议修法**：注释改成「真正的保护是滑块 getter 必须 snap（修法 3）；注册顺序在当前实现下**不**影响落库，但保持 滑块→输入框 的构建顺序可让 apply 回调顺序与用户操作顺序一致，仍是更稳的写法」。契约 V13 建议改为**行为判据**（键入 333 → apply → 落库 333），或明确它只能配「bindValue 也在滑块之前」的形态。
- **【captain 已裁决】接受。** 改 `addSlider` 注释；契约 V13 **由机械判据降级为行为判据**
  （`键入 333 + apply → 落库必须仍是 333`，MUT-F 会让它红）。captain 另做了决定性验证：
  读源码确认滑块 getter（`:655`）有 snap、输入框 getter（`:632`）无 snap，
  并复核 `build\v-mut\MUT-F\VDefects.txt` 的 `[cursorDisappearDelay=350] ← FAIL`。

### F-3（severity: low）探针输出呈现：阶段 2（理应变红）与真失败长得一样

Captain 已发现并提醒过我。我实测确认：`run-4bugs-probe.ps1` 的阶段 1 / 阶段 2 共用一个 stdout，
阶段 2 的 `FAIL ...` 行与真失败无法肉眼区分（只靠前缀 `############ 阶段 2` 分隔，
而 `Select-Object -Last N` 会把分隔行截掉）。
**影响**：复跑者会误判 t2 失败（captain 本人差点误判）。
**建议修法**（t6 顺手做）：阶段 2 的每条断言前加 `[预期变红]` 前缀，或阶段 2 只打汇总条数。
我自己的 `run-v-mutations.ps1` 已经把每个变异的输出落到独立文件、按阶段解析，不存在这个坑。
- **【captain 已裁决】接受**，t6 给阶段 2 的 FAIL 加 `[预期变红]` 前缀。

### F-4（severity: low）`CursorPresets` 放在 `settings/` 而不是契约 §5.4 写的 `core/`

- 实现者已主动申报（t3 自述「偏差①」），理由成立：t3 的 inScope 是 `ui/、settings/、plugin.xml、tools/settings-probe/`，`core/` 不在范围内；且这样 `core/` 目录保持**完全干净**（比 D0 更强）。
- 我复核了 §5.8 的两条机械判据（`new NeovideConfig` / `NeovideCaretManager`）在 `CursorPresets.java` 里都是 0 命中（注释行除外）。
- **影响**：契约 §5.4 的示例路径与实现不一致，属文档漂移；V15 判据仍然成立。
- **建议**：在契约 §5.4 加一行「实际落在 `settings/`，理由：`core/` 需保持零 diff」，避免下次审查再问。
- **附带**：N-1 的机械 grep 会被 javadoc 里那句 `{@code new NeovideConfig()}` 打成 1 命中（假阳性）。建议判据改成「排除注释行」。
- **【captain 已裁决】接受。** `CursorPresets` 保留在 `settings/`（采纳实现者的理由）；
  N-1 的 grep 判据改为**排除注释行**。

### F-5（severity: low）§7.2 与 §9.4 对「下限」的解释在措辞上不一致（与 F-1 同源）

契约 §7.2 正文写对了（「任何一次最大 dt 都会一帧短路」），但 §9.4 的面板文案省略了「最大 dt」这个前提，
写成「单帧最大步长 0.05s，再短会被一帧归零」—— 读者会以为**每帧**都归零。
我实测 0.05 在 dt=8ms 下能跑 16 帧 / 128ms（`SPR-4`），只有 dt=50ms 时才归零（`SPR-5`）。
**建议**：文案补一句「仅当某一帧的 dt 达到 50ms（如切屏恢复）时」。
- **【captain 已裁决】接受**，§9.4 文案补「最大 dt」前提。

---

## 11. 与实现者自述的逐项对照

| 实现者自述 | 我的实测 | 一致？ |
|---|---|---|
| t3 探针 68/68 全绿 | 我复跑 `run-settings-v2-probe.ps1` → `通过 68，失败 0` | 一致 |
| MUT-1 红 1 / MUT-2 红 3 / MUT-3 红 12 / MUT-4 红 3 | 我复跑 `run-4bugs-probe.ps1` → 数字完全相同 | 一致 |
| 三件套全过（parity / audit DIFF=1 + D=12 / ime 29） | 我独立跑三件套，全部一致 | 一致 |
| 预设正交（O1 7/7、O2 5/5） | 我用**逐位**快照复核 → 7/7、5/5，且额外验了 stretchRatioK / trailMode 不被碰 | 一致（我的判据更严） |
| 吸附 + 333 不被 350 覆盖 | `TYP-1..TYP-5` 全过 | 一致 |
| `SHORT_ANIMATION_LENGTH` 已删除 | grep 命中 0 | 一致 |
| State 字段数 19 | 反射数 = 19 | 一致 |
| 「构建顺序必须 滑块→输入框，否则丢数据」 | **行为正确，但因果解释不成立** | **不一致 → F-2** |
| 「探针需要 ActionManager / JBPopupFactory 两个桩」 | 实测需 **4 处**（另两处：`SingleAlarmSharedCoroutineScopeHolder`、`--add-opens ...text.html.parser`） | 部分（漏 2 处，但不是被测代码问题） |
| 「本轮改动全在 UI 面板层，绘制热路径零 diff」 | `git status` 复核：`CaretAnimator / TrailCorner / CaretTrailPanel / DampedSpring` 都不在改动清单里 | 一致 |
| 「8 组动画预设」（团队目标原文）vs 实际 7 组 | 实测 `ANIM_PRESETS.length == 7`；第 8 个「残影模式」按契约 §0.2 的 R-3 未进表（归 Tools 既有第 3 项） | 与**契约**一致，与**团队目标 / 方案**的 8 组不同 —— R-3 是显式修订；**captain 已确认知悉**（「残影模式」改 trailMode 而非动画参数，故不进预设表），不是遗漏 |

---

## 12. 未验项与真机复核步骤

**未验（如实标注）**：

1. **真机 IDEA 里的实际观感**（拖动时鼠标跟手、菜单渲染、折叠组动画）。理由：硬约束「不装 jar、不重启 IDEA」，我只能做离线探针 + 真实 `CaretAnimator` 的调度测量。
2. **真机 GPU / 渲染后端下的帧率**。我的帧率是离线 EDT 调度测量，不含真实渲染。§8 的结论只覆盖「调度热路径无回归」。
3. **`build/offline-build.ps1` 的实际构建**。t3 的 verify 列表里有这一条，但它属于 t6 的集成交付；我只验到「`build.gradle.kts` 零 diff」与「22 个 .java 全部 `javac --release 21` 通过」。

**真机复核步骤**（给 captain / t6 在装有 IDEA 的机器上执行）：

```text
1. 装与 t6 产出的 jar 同版本的插件（t6 会给出 SHA256），重启 IDE。
2. 缺陷 1：Settings | Tools | Neovide Cursor -> 拖「淡出前保持时长」滑块，
   右侧数值必须逐像素跟随（松开鼠标也不要滞后）。
3. 缺陷 2：同上页面 -> 每个滑块下方只应有 3 个刻度数字（min / mid / max），不重叠。
4. 缺陷 3：保持设置页打开 -> Tools 菜单取消「辉光淡出」-> 回设置页直接点 OK ->
   再打开 Tools 确认「辉光淡出」仍是未勾选。
5. 缺陷 4：勾选「隐藏原生光标」-> Settings | Editor | Color Scheme 换一个方案 ->
   回到编辑器，原生光标不得出现（只有覆盖层那一个）。
6. 预设正交：记录当前颜色 HEX（面板两个 ColorPanel）-> 逐个切 7 个动画预设 ->
   两个 HEX 必须全程不变；再逐个切 5 个颜色预设 -> 6 个滑块数值必须全程不变。
7. 动画参数：把「跨行动画时长」拖到 0.35 -> 跨行移动应持续约 1 秒后落位（不是瞬间）；
   拖到 0.05 -> 正常帧率下仍有约 128ms 拖尾，切屏回来则瞬移。
8. 帧率：IDE 内开编辑器 + 持续打字，用外部 GPU 计数器或 VisualVM 观察 EDT 占用，
   与 0.9.4 同场景对比（本机基线见 §8）。
```

---

## 13. 附：本次验证产生的所有产物

| 文件 | 内容 |
|---|---|
| `build\v3-parity.log` / `build\v3-audit.log` / `build\v3-ime.log` | 三件套原始输出 |
| `build\v2-probe\*.txt` | 9 个 V 探针的完整输出（VDefects / VScheme / VSnap / VPreset / VTools / VSpring / VHints / VMixed / VOrder） |
| `build\v2-probe\baseline.txt` / `current.txt` | 同一份探针在 0.9.4 与 0.9.5 上的对照 |
| `build\v-mut\<MUT>\*.txt` | 6 个变异各自的探针输出 |
| `build\v-mutations3.log` | 变异汇总（含每次的 diff 与变红明细） |
| `build\v4-4bugs.log` / `build\v4-t3probe.log` | 我复跑实现者两套探针的完整输出 |
| `build\v3-frame.log` | 帧率 3 轮 x 5 场景 x 两棵树 |
| `build\v-order-exp\` / `build\v-order-exp2\` | F-2 的两个反向实验副本（bindValue 提前 / 输入框块整体前移） |
