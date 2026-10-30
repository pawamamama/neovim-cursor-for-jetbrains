# 第 1 批修复计划（健壮性 / 零观感变化）

> 依据：`docs/CODE-AUDIT-2026-10-30.md` 第六节「修复批次建议」第 1 批 8 条 ——
> **H-2 / H-3 / M-4 / M-10 / L-4 / L-5 / L-6 / L-8**
> 基线：HEAD `36cbaa4`，工作区 3 个未跟踪文档、0 个已修改，`fork/main...HEAD = 0 0`
> 门禁基线（实测两次一致）：**99 条目 / 1008 PASS / 2 FAIL**（都在 `VRegr2`，informational）
> 产出方式：`planner` 代理出稿 → 我按磁盘实况逐条核对并修正 → 落盘

---

## 0. 白名单与先决检查

### 0.1 允许改动的文件（13 个）

| 类别 | 文件 |
|---|---|
| 生产（9） | `settings/SettingsField.java`、`settings/NeovideCursorSettings.java`、`settings/CustomPresets.java`、`ui/CursorPresetApplier.java`、`core/DampedSpring.java`、`core/GlowIntensity.java`、`core/AfterimageTrail.java`、`core/NeovideColors.java`、`core/TrailCorner.java` |
| 探针设施 | `tools/settings-probe/V0Env.java`（加两个默认关闭的注入开关）、`tools/settings-probe/VAuditBatch1.java`（新建）、`tools/settings-probe/build-v-probes.ps1`（注册 `$mains`） |
| 文档（3） | `CLAUDE.md`、`START-HERE.md`、`docs/NEXT-STEPS.md` |

**明令不动**：`core/NeovideConfig.java`（D0 零 diff）；任何现有探针文件的断言判定条件；
`CustomPresets.format` 里那条 `java.util.logging`（L-7 属第 3 批）；
`NeovideCursorConfigurable.java`（面板两条 `restoreSkipping` 调用点见 §4.2，本批不动）。

### 0.2 先决检查（H-3 的「有意 / 疏漏」二选一）

```
git log -S "按「旧键不存在」处理" --oneline -- src/main/java/com/github/yyyolo7a79/neovidecursor/settings/NeovideCursorSettings.java
```

审计 §5.1 把这条留给人定。**本计划默认按「疏漏」修**。若查证确为有意设计，H-3 退化为
「只在该 catch 的注释里写明取舍」，其余 7 条不受影响。

---

## 1. Research 记录

### 1.1 第 1 轮：仓内范式逐条对应

| 本批项 | 复用的仓内范式 | 位置 | 复用什么 |
|---|---|---|---|
| 日志设施 | 项目统一用 IntelliJ `Logger` | `NeovideCursorSettings.java:53`、`CursorPresetApplier.java:24`、`CursorDiag.java:36`（共 7 处） | **不新引 `java.util.logging`**；唯一 JUL 在 `CustomPresets.format:301`，那是 L-7 的债，本批不碰 |
| L-4 / M-10 有界日志 | `CursorDiag` 的预算表 | `CursorDiag.java:151-169`（`TRAIL_MAX_LINES` + `TRAIL_BUDGETS`）、`:580-597`（达上限只补一条汇总） | 「逐条上限 + 一条总量」两段式；并发安全集合 |
| H-3 迁移闸门 | `persistQuietly` 的三态 | `NeovideCursorSettings.java:322-340`、`:701-710`、`:729-736` | 已有「落盘未确认就不删键」；H-3 把「读失败」并入同一条路，**不新增状态** |
| H-2 日志措辞 | `decode` HEX 分支的「后果 + 现状」写法 | `SettingsField.java:273-289` | 文案写「值非法 → 保持当前值」，与代码自洽 |
| M-4 NaN 守卫写法 | `clamp(float)` 的 NaN 分支及理由注释 | `NeovideCursorSettings.java:744-750` | 先判非有限再走正常路径 |
| M-4 / L-8 自愈语义 | `TrailCorner.update` 的「目标变化才重算偏移」 | `core/TrailCorner.java:352-358` | reset 后 `position=0` ⇒ 角点落在目标上，是「没有拖尾」而不是「光标消失」 |
| 探针钩子 | 全仓 88 处 `*ForProbe()` | 例 `CaretTrailPanel.java:461,466` | 新增 `setVelocityForProbe` / `getAnimationLengthForProbe` / `animationLengthForProbe` |
| 纯注释项的守门 | 读源码做断言的探针 | `VDeadField`（读方法体源码）、`VBuildDecl`（读 `install.ps1` / `SettingsField.java`） | L-6 只能这样守，否则「注释写了等于没写」 |
| 探针输出格式 | 全部 V 探针 | 例 `VBuildDecl.java:368-386` | 必须打 `"  PASS  "` / `"  FAIL  "`（脚本按 `^\s+PASS ` 计数），失败 `System.exit(1)` |

### 1.2 第 2 轮：外部检索（**实际执行过，记录搜过什么**）

| 手段 | 查询 | 结果 |
|---|---|---|
| `gh search code` | `Double.isFinite NaN guard spring position velocity reset` | 0 命中 |
| `gh search code` | `isFinite guard damped spring java position reset` | 0 命中 |
| `gh search code` | `DampedSpringAnimation` | 命中 8 个上游/派生仓库（LengineerC、ywwolo7a79-sketch/neocursor-fix、Govindggupta 等） |
| `gh search code` | `stateVersion migration PropertiesComponent rollback` | 0 命中 |
| `gh search repos` | `neovide cursor` | 命中 5 个移植项目 |
| `gh api` 取上游源码 | `LengineerC/vscode-neovide-cursor:neovide-cursor.js` 的 `DampedSpringAnimation.update` | **上游没有任何 NaN 守卫**：`if (animationLength <= dt \|\| this.position === 0) reset();` 之后直接闭式解推进，`NaN === 0` 为 false ⇒ 一样会永久 NaN |

**结论**：
1. M-4 / M-10 / L-4 / L-8 **没有现成实现可抄**（M-4 在 upstream 上是同一个 bug），
   只能按本仓范式自己写 → **必须有本地探针兜底**，否则就是无人验证的新逻辑。
2. IntelliJ 侧的「迁移读失败回滚」在公开代码里搜不到通用解法；本仓自己的 `FAILED` 分支
   就是唯一的可信范式。
3. 未出现「外部方案与本项目既有范式冲突」的情况（审计 §五.7 的停下条件未触发）。

### 1.3 环境事实（本批设计的前提，已实测）

| 事实 | 怎么验的 | 结论 |
|---|---|---|
| 探针运行环境有平台 jar | `build-v-probes.ps1:22` 的 `-classpath` 含全部 idea-libs | `Logger` 可用 |
| `Logger.getInstance` 在裸 JVM 可用 | 写 5 行测试类 + idea-libs 跑 → `LOGGER_OK class=DefaultLogger` | 可用，不需要完整 Application |
| `Logger.setFactory` 能捕获日志 | 同一测试里装自定义 `Logger.Factory` → `captured=[WARN test-cat hello-capture, …]` | **探针可以离线断言「某条 warn 被打过」** |
| `Logger` 的抽象方法只有 5 个 | `javap` | `isDebugEnabled` / `debug(String,Throwable)` / `info(String,Throwable)` / `warn(String,Throwable)` / `error(String,Throwable,String...)` |
| `core` 当前零平台依赖 | `grep '^import' core/` 只有 `java.awt` / `java.util` | 本批会打破它（见 §2） |

---

## 2. 设计取舍：core 引入平台 Logger

**问题**：L-4（`NeovideColors`）与 L-8（`TrailCorner`）需要 `warn`，而 `core` 目前零平台依赖。

**为什么不用「新增 `core/CoreLog.java` 日志门面」**：新增文件**超出白名单**；且「门面要谁来注入」
本身是新风险（漏注入 = 全部日志静默消失，正是本批要消灭的病）。

**选定做法（把破坏面压到最小）**：

1. **不写 `static final Logger LOG` 字段**（类初始化就要加载 `com.intellij…Logger`，
   在没有平台 jar 的场合会 `NoClassDefFoundError`）。改为**惰性取**，只在真要打日志时调。
2. 包一层 `safeWarn`，`catch (Throwable)` 里降级到 stderr（只报一次）——
   **日志子系统故障不得变成新的故障源**。
3. 只落在 **2 个** core 文件（`NeovideColors`、`TrailCorner`）。`DampedSpring` / `GlowIntensity` /
   `AfterimageTrail` 的修复审计没要求记日志，**不引 Logger**。

```java
// 与 core/TrailCorner 里的 safeWarn 保持同步；批次 2 抽 CoreLog 后合并。
// core 在无平台的基准程序里也要能跑完，所以这里只吞「日志子系统自己」的异常。
private static volatile boolean logBroken;

private static void safeWarn(String msg) {
    try {
        com.intellij.openapi.diagnostic.Logger.getInstance(NeovideColors.class)
                .warn("neovide-cursor: " + msg);
    } catch (Throwable t) {
        if (!logBroken) {
            logBroken = true;
            System.err.println("[neovide-cursor] 日志不可用（只报一次）: " + t);
        }
    }
}
```

**代价（必须写进 commit message）**：
- `safeWarn` 在两个 core 文件各一份（约 10 行重复）—— 白名单造成的、被记录的异味。
- 审计第七节「`core` 无静态可变集合」自本批起失效（L-4 的有界集合、L-8 的一次性 `AtomicBoolean`）。
  那是带日期的快照，不改它；批次 2 在 `CLAUDE.md` 补现状。
- `tools/parity/run-parity.ps1:43,59,68` 与探针门禁都带 idea-libs，所以这些工具不受影响。
  唯一残余风险是「有人手工 `java -cp classes` 不带 idea-libs」——那时只在真出现非法值时
  打一条 stderr，不中断。

**settings / ui 侧不受此约束**：`NeovideCursorSettings`、`SettingsField`、`CustomPresets`、
`CursorPresetApplier` 都已在依赖平台的包里，直接用 `static final Logger LOG`（与现有 7 处一致）。

---

## 3. 逐条修复

### 3.1 H-2 —— 恢复失败不可观测

**根因**：`SettingsField.decode` 的每个 `return false` 与 `catch (RuntimeException)` 都零出口；
`restoreSkipping` 的返回值在 `CursorPresetApplier.applyCustomAnimPreset:116` 被丢弃。
于是「整条快照全坏」与「全部应用成功」在调用方**完全一样**：都写名字、都落盘、都打「已应用」、都返回 true。

**改法**

`settings/SettingsField.java`
1. 顶部加 `private static final Logger LOG = Logger.getInstance(SettingsField.class);`
   与 `private static final int RAW_LOG_MAX = 24;`。
2. 新增私有方法（插在 `decode` 之后）：

```java
/**
 * 记录一次「已知键、值非法」的拒绝。
 *
 * <p>为什么能确定是「非法」而不是「缺键」：未知键在 restore/restoreSkipping 的
 * byKey() 就被 continue 掉了，缺键根本不会进 decode —— 所以 decode 返回 false
 * 一定等于「这个键存在、但值非法」。
 *
 * <p>值必须截断：预设串可能被手改成几 KB。日志本身也包 try/catch，
 * 因为 decode 的第一契约是「绝不抛异常」（见其 javadoc）。
 */
private boolean reject(String raw, String why) {
    String shown = raw == null ? "(null)"
            : (raw.length() <= RAW_LOG_MAX ? raw
               : raw.substring(0, RAW_LOG_MAX) + "…(共 " + raw.length() + " 字符)");
    try {
        LOG.warn("neovide-cursor: 预设字段 " + key + " 的值非法（" + why
                + "），保持当前值。值=[" + shown + "]");
    } catch (Throwable ignored) {
        // 日志失败不能反过来破坏「decode 不抛异常」
    }
    return false;
}
```

3. 6 个 `return false` 改成 `return reject(...)`：

| 位置 | 现状 | 改成 |
|---|---|---|
| `:238` | `if (raw == null) return false;` | `return reject(null, "值为 null");` |
| `:245-247` | FLOAT 非有限 | `return reject(raw, "不是有限数");` |
| `:262` | BOOL 不认识 | `return reject(raw, "不是 0/1/true/false");` |
| `:268-270` | ENUM 无此常量 | `return reject(raw, "不是合法枚举名");` |
| `:284-286` | HEX 格式不对 | `return reject(raw, "不是 #RRGGBB / #AARRGGBB");` |
| `:297-299` | `catch (RuntimeException ex) return false;` | `return reject(raw, "解析抛异常 " + ex.getClass().getSimpleName());` |

`ui/CursorPresetApplier.java` 的 `applyCustomAnimPreset`：

```java
int applied = SettingsField.restoreSkipping(s, snap);
int total = SettingsField.PRESET_FIELDS.size();          // 现 13，按常量派生，不写死
if (applied == 0) {
    LOG.warn("neovide-cursor: 自定义预设 " + name
            + " 里没有任何可应用的字段（快照可能已损坏），本次不应用、不写名字、不落盘");
    return false;
}
if (applied < total) {
    LOG.warn("neovide-cursor: 自定义预设 " + name + " 只应用了 "
            + applied + "/" + total + " 项，其余值非法已保留当前值");
}
s.animCustomName = name;
svc.persistQuietly();
NeovideCaretManager.applyConfig();
LOG.info("neovide-cursor: 已应用自定义预设 " + name + "（" + applied + "/" + total + " 项）");
return true;
```

**影响面**：`SettingsField.restore`（696-719）不动（M-6 属第 4 批），它也会因 decode 获得日志。
语义变化只有一处：**快照里只有 runtimeSwitch 字段时**（如 `enabled=1`），以前「返回 true 并写名字」，
现在返回 false —— 那种快照本来就什么也没应用，属修正。

**探针**：A-1..A-5。**风险**：低（日志异常已兜住）。

### 3.2 H-3 —— 迁移读失败仍推进版本号并删旧键

**根因**：`migrateIfNeeded` 的 `catch (Throwable)`（313-316）只打一行 warn，控制流**继续落到**
`stateVersion = 1` → `persistQuietly()`（EDT 上恒 `CONFIRMED`）→ `deleteOldKeysIfPresent()`。
一次瞬时读异常 = 旧键永久消失 + 版本已推进 = 下次启动只删不读，**不可恢复**。
这与同方法 `FAILED` 分支「回退版本号、保留旧键」的承诺直接矛盾。

**改法**（`settings/NeovideCursorSettings.java`）
1. `:285` 前加 `boolean readFailed = false;`
2. catch 改为置位 + 说明后果的 warn。
3. `:321` **之前**插入早退：

```java
if (readFailed) {
    // 与下面的 FAILED 分支同路：没读到值 = 没成功。特别是不能删旧键 ——
    // 删键不可逆，而这次我们连「旧键是否存在」都不知道。
    state.stateVersion = 0;
    LOG.info("neovide-cursor: 旧键迁移中止，changed=" + changed
            + "，stateVersion=0，未落盘、未删键");
    return;
}
```

4. 删掉老注释「按『旧键不存在』处理」（那条注释记录的正是错误行为）。
5. **`pc == null` 分支（317-319）保持原样**并补注释：服务拿不到 ≠ 读失败。若把它也判为失败，
   任何一次服务竞态都会把迁移永久卡在未完成态；而干净安装走的正是这条路径
   （`VerifyPersistence` 的 P2b 断言的就是它）。这是本计划显式记录的决策点。

**探针**：A-6、A-6b。**风险**：低-中（行为变更是故意的：读失败每次启动重试）。

### 3.3 M-4 —— NaN 进弹簧后永不自愈

**根因**：`Math.abs(NaN) < 0.001` 恒 false ⇒ 复位分支永不触发；`TrailCorner.update:353` 的
`destX != previousDestX` 对 NaN 恒 true ⇒ 每帧写回 ⇒ 永久 NaN ⇒ `lineTo(NaN,…)` 被 Java2D
静默忽略 ⇒ 光标不显示、零日志。`GlowIntensity.strengthFor` 与 `AfterimageTrail.setLifetime` 原样透传。

**改法**

`core/DampedSpring.java` —— `update` 首句：

```java
// 【M-4】NaN 会同时绕过下面两个比较，复位分支永不触发，而 TrailCorner 会每帧写回
// → 该角点永久 NaN → lineTo(NaN) 被 Java2D 静默忽略 → 光标消失且零日志。
// 这里归零并报「已静止」：角点落在目标位置上（TrailCorner 用 dest - 0），
// 视觉上是「没有拖尾」而不是「光标消失」；下一次 jump() 写入有限值即自愈。
if (!Double.isFinite(position) || !Double.isFinite(velocity)
        || !Double.isFinite(animationLength)) {
    reset();
    return false;
}
```

外加两个探针钩子（`velocity` 无 setter，没有它「删掉 velocity 那条守卫」不会变红）：

```java
public void setVelocityForProbe(double v) { this.velocity = v; }
public double getAnimationLengthForProbe() { return animationLength; }
```

`core/GlowIntensity.java` —— `strengthFor` 计算 `s` 之前：

```java
if (!Double.isFinite(offset)) {
    // NaN 让下面两个比较都 false 并原样返回 → 调用方「strength > 0」恒 false → 辉光永不亮。
    // 注意语义：+Infinity 以前返回 1f，现在返回 0f —— 有意的保守化（非有限偏移本身不合法）。
    return 0f;
}
```

`core/AfterimageTrail.java`：

```java
private static final double LIFETIME_MIN = 0.02;
/** 上界 10s：再往上没有观感意义，却让 cutoff 的 long 运算接近溢出。 */
private static final double LIFETIME_MAX = 10.0;

public void setLifetime(double lifetime) {
    if (!Double.isFinite(lifetime)) {
        return;                       // 非法值保持原值，绝不让 NaN 进 alphaOf
    }
    this.lifetime = Math.min(LIFETIME_MAX, Math.max(LIFETIME_MIN, lifetime));
}
```

**影响面**：只改「非有限 / 超上界」输入；合法输入（默认 0.22 / 0.15）逐位不变 → parity 应逐字节相等。
`NeovideConfig.afterimageLifetime` 是常量 0.22，用户改不到，上界不可达。

**探针**：A-12..A-17。**风险**：低。

### 3.4 M-10 —— `CustomPresets.parse` 静默丢弃坏条目与重名条目

**根因**：`parse`（210-233）对 `parseOne == null` 与重名一律 `continue`，零日志；而**下一次任何
`put/remove/rename` 后的 `format` 都会把磁盘上的那条一起抹掉**，不可逆。

**改法**（`settings/CustomPresets.java`）
1. 顶部加 `private static final Logger LOG = Logger.getInstance(CustomPresets.class);`
   与 `private static final int PARSE_WARN_LIMIT = 5;`
2. **改类 javadoc 第 10 行**「纯函数层。无状态、不碰任何单例」→
   「纯函数层：不持有业务状态、可离线独立驱动；唯一副作用是写日志（Logger 属于平台，不是本类的状态）」。
3. `parse` 循环：坏条目 / 重名分别计数，逐条最多报 `PARSE_WARN_LIMIT` 条（带**条目长度**与
   **尽力取到的名字**，截断到 24 字符，**绝不打印快照正文**），末尾补一条总量汇总。
4. `format`：统计 `entries.size()` 与实际写出条数，减少时记一条汇总。
   **保留那条 JUL warning 原样不动**（L-7 属第 3 批）。

**影响面**：`parse` 返回列表、`format` 返回值都不变。调用点均为低频（保存 / 菜单重建）。

**探针**：A-18..A-21。**风险**：低（正常路径零开销；坏表最多 6 条日志）。

### 3.5 L-4 —— `NeovideColors.parse` 失败静默返回黑色

**根因**：`:44-46`（长度不对）与 `:56-59`（`catch`）直接 `return Color.BLACK`。
任何非法颜色进 `CONFIG` → 拖尾与辉光同时变黑，日志零痕迹，用户只能报「光标黑了」。

**改法**：有界（16）的「已报过非法串」集合（`ConcurrentHashMap.newKeySet()`，**不接受 null 键**，
null 用 `"(null)"` 作键）+ 达到上限补一条汇总。三个失败出口各调 `warnInvalid(hex)` 后**照原样返回
`Color.BLACK`**（返回值行为一字不变）。**只在失败分支查集合** ⇒ 成功路径零开销（这是绘制热路径）。

**探针**：A-9..A-11。**风险**：低。

### 3.6 L-5 —— `tryGetPropertiesComponent()` 吞异常

**改法**：catch 里补 `LOG.warn("neovide-cursor: 获取 PropertiesComponent 失败，按「服务不可用」处理", t);`
**不**改成 `CursorDiag.swallowed(...)`：那是 `ui` 包，`settings → ui` 包环（M-8）正在待修，
不该在这批加深它。写进注释。

**探针**：A-8。**风险**：极低。

### 3.7 L-6 —— `CONFIRMED` 的语义（**纯注释，零行为变化**）

三处：`persistQuietly` 的 `@return`、`:685-688` 那行代码上方、`PersistResult.CONFIRMED` 的 javadoc。
统一口径：**`CONFIRMED` 只表示 `saveSettings()` 返回且没抛异常，不代表磁盘已写入**；
「唯一允许删旧键」这个前提的局限写清楚（平台静默失败时两端都丢）。

**探针**：A-22（源码扫描，因为纯注释无法用行为断言守）。

### 3.8 L-8 —— `TrailCorner.jump` 兜底分支的分母零

**根因**：`charsMoved < 0` 时用 `deltaXpx / width`。`width` 为 0 → `0/0=NaN` / `x/0=±Inf` →
两个 `<=` 都 false → 静默退化成跨行分支（时长与短跳差一个量级），零日志。

**改法**（`core/TrailCorner.java`）

```java
/** 上一帧的短跳判定。分母为零时用它兜底，而不是让 NaN 替我们「猜」成跨行。 */
private boolean lastShortJump;                 // 默认 false = 与今天 NaN 的结果一致
/** 零尺寸告警只报一次（每个角点每帧都会 jump，逐次记会刷屏）。 */
private static final java.util.concurrent.atomic.AtomicBoolean ZERO_SIZE_WARNED = ...;

...
} else if (!(width > 0) || !(height > 0)) {
    // 分母为零/非有限：0/0 得 NaN、x/0 得 Infinity，两个 <= 比较都会 false
    // → 静默退化到跨行分支，时长差一个量级。保持上一帧判定更接近眼前正在发生的事。
    // 用 !(w > 0) 而不是 w == 0：顺手把 NaN 尺寸也挡住。
    isShortJump = lastShortJump;
    if (!ZERO_SIZE_WARNED.getAndSet(true)) {
        safeWarn("jump 收到零尺寸（width=" + width + ", height=" + height
                + "），退回上一帧判定；后续不再重复记录");
    }
} else {
    double jumpX = deltaXpx / width;
    double jumpY = deltaYpx / height;
    isShortJump = Math.abs(jumpX) <= config.shortMoveThreshold && Math.abs(jumpY) <= 0.001;
}
if (charsMoved >= 0 || (width > 0 && height > 0)) {
    lastShortJump = isShortJump;               // 只在真有判定时更新
}
```

外加 `public double animationLengthForProbe() { return springX.getAnimationLengthForProbe(); }`
（`springX` 是私有的，光有 `DampedSpring` 的 getter 探针拿不到）。

**影响面**：只有「零/非有限尺寸 + `charsMoved < 0`」会变。生产路径 `SpringTrail` 每帧都
`setCharsMoved(...)`，兜底分支主要出现在预览的早期帧。

**探针**：A-23（红绿驱动）、A-24（绿两侧，只守方向）。**风险**：低。

---

## 4. 契约变更（独立核对结论）

### 4.1 结论：本批 **不应** 让任何现有探针变红

逐条核过断言本体（不是凭印象）：

| 探针 | 为何不受影响 |
|---|---|
| `VCustom` C-40 | 快照由 `SettingsField.snapshot` 产出 → `applied == PRESET_FIELDS.size()` → 不触发 `applied == 0` |
| `VCustom` C-41 / `VPreset9` P-5c | 名字不存在 → 在 `snap == null` 就早退，不到 `restoreSkipping` |
| `VCustom:118` | `"不存在的键"` 在 `byKey` 被跳过、`"坏的"` 在 `eq <= 0` 被跳过 —— **两处都进不了 decode**，计数不变 |
| `VFieldDomain` | 喂的全是合法值；本批不改任何判定，只加日志 |
| `VSwitchKeysGap:125` | `restoreSkipping` 的计数逻辑未动 |
| `VFreeze:22,49` | `0.22 ∈ [0.02, 10.0]`，`isFinite` 通过 |
| `VGlowFog` / `VRgbWash` / `VGradAllPresets` | 只用合法色；返回值不变 |
| `VPreviewParity:149-157` | `strengthFor(0/0.5/1.5/2.5/99)` 全是有限值 |
| `VerifyPersistence` A-1/A-2/P2b | H-3 只改 catch 分支，正常分支一字不变 |
| `VLoadState` / `VDefaultFreeze` / `VAddSetting` / `VBuildDecl` B-7 | **本批不加 State 字段、不改工厂**，27 不变 |
| `VReviewFixes` / `VDeadField` | 扫的是 `CaretAnimator` / `NeovideCaretManager` / `RenderScheduler`，不涉及 |

**唯一可能的「噪声」**（不是红）：`VFieldDomain` 若某个 setter 抛异常，decode 的 catch 会多打一条 warn
—— 只影响输出行，不影响脚本按 `^\s+PASS ` 的计数。

### 4.2 两处「同样丢返回值」的调用点：本批明确不修

`NeovideCursorConfigurable.java:1972`（选中自定义预设回灌 pending）与 `:2450`（恢复默认值）
**同样丢弃 `restoreSkipping` 的返回值**。改它们会动面板行为，超出「零观感变化」与白名单，
**记入第 4 批**。它们现在会自动获得 decode 级 warn（可观测性顺带改善）。
→ **H-2 的 b 半（返回值处置）本批只修了 Tools 菜单那条路径，这一点必须写进 commit message，
否则会被误认为 H-2 已全修。**

### 4.3 一个给第 2 批的提醒

`targetFps` / `slopeTicks` 的 setter 会**静默改写**值却让 `decode` 返回 true（`240 → 125`、越界 → 0）。
所以「`decode == false` ⇒ 值非法」成立，反向不成立。H-1 把区间收进字段表时必须一起处理这两处。

---

## 5. 探针设计：新增 `tools/settings-probe/VAuditBatch1.java`

### 5.1 骨架要点

- 日志捕获工厂（`Logger.Factory` + 继承 `Logger` 的 5 个抽象方法），把 `info` / `warn` 收进两个
  同步列表。**`main` 的第一句必须是 `Logger.setFactory(...)`** —— 生产类的
  `static final Logger LOG` 在类第一次被触达时创建，工厂装晚了那些实例就已经是 `DefaultLogger`，
  于是全部「必须有日志」的断言变成永久红，**且看起来像生产没打日志**。
- 每个探针在脚本里是独立 JVM（`build-v-probes.ps1:244` 逐条 `java`），不需要还原工厂。
- 输出格式与其它探针一致，末尾 `VAUDIT-BATCH1 RESULT: ALL PASS / FAILED`。

### 5.2 断言清单（A-0 … A-24，共 25 条）

| # | 断言 | 修复前 |
|---|---|---|
| A-0 | 装工厂后自己打一条 warn，能在 `WARNINGS` 里找到 | 绿两侧（机制自检：它红说明「捕获坏了」，其余红就都不算数） |
| A-1 | `restoreSkipping(s,"tailOpacity=abc")==0` 且捕获 1 条含 `tailOpacity` 与 `abc` 的 WARN | **红**（0 条） |
| A-2 | `restoreSkipping(s,"tailOpacity=abc;trailSize=0.4")==1` | 绿两侧（防「加日志顺手改了计数」） |
| A-3 | 400 字符的坏值 → 日志长度 < 160 且不含 100 个连续 `x`（截断生效） | **红** |
| A-4 | 全坏快照 → `applyCustomAnimPreset` 返回 **false**、`animCustomName` 未写、INFOS 无「已应用」 | **红**（返回 true 且写了名字） |
| A-5 | 半坏快照 → 返回 true 且捕获到 `"12/13"` 形态（数字按 `PRESET_FIELDS.size()` 派生，不写死） | **红**（无该 WARN） |
| A-6 | 注入「一次 `isValueSet` 抛异常」→ `noStateLoaded()` → `stateVersion==0` 且两个旧键仍在 map 里 | **红**（版本 1、键被删） |
| A-6b | 承接 A-6 的 props（**不重置**），清注入再迁移一次 → `alwaysHighFps == false`（旧键的值救回来了） | **红**（A-6 已把键删掉 → 落回默认 true）**这条直接演示「不可逆丢值」** |
| A-7 | 重新灌旧键、不注入，正常迁移 → `stateVersion==1`、值取到、键已删 | 绿两侧（正常路径回归守卫） |
| A-8 | 注入「取 PropertiesComponent 抛异常」→ `noStateLoaded()` 不抛、捕获含 `PropertiesComponent` 的 WARN、`stateVersion==1` | **红**（无 WARN） |
| A-9 | `parse("#GGGGGG",1f)==BLACK` 且新增 1 条含该串的 WARN；再调一次计数不增 | **红** |
| A-10 | `parse("#FFC0CB",1f)` 不产生 WARN | 绿两侧（happy path 守卫） |
| A-11 | 再灌 40 个互不相同的非法串 → 新增 WARN **≥1 且 ≤17**，且存在一条「已达记录上限」 | **红**（0 条）。注：只写上界「≤17」在修复前也成立，**必须同时写 ≥1** |
| A-12 | `setPosition(NaN); update(0.008)` → false 且 `position==0`、`velocity==0` | **红** |
| A-13 | `setVelocityForProbe(NaN); setPosition(5); update(...)` → 归零 | **红**（这是 `setVelocityForProbe` 存在的理由） |
| A-14 | `setAnimationLength(NaN)` → false 且有限；再设 `0.1` → 能正常推进（自愈） | **红** |
| A-15 | `strengthFor(NaN)==0f`、`strengthFor(+Inf)==0f`、`strengthFor(2.5)==1f`、`strengthFor(0)==0f` | **红** |
| A-16 | 先 `setLifetime(0.22)` 再 `setLifetime(NaN)` → 仍 `0.22` | **红** |
| A-17 | `setLifetime(1e10)==10.0`；`setLifetime(0)==0.02` | **红**（前者） |
| A-18 | 坏条目 + 好条目 → `size==1`，1 条 WARN 含长度且**不含**快照正文 | **红** |
| A-19 | 重名两条 → `size==1`，1 条 WARN 含名字与「重名」 | **红** |
| A-20 | 50 条坏条目 → 逐条 WARN ≤5，且有 1 条汇总含「50」 | **红** |
| A-21 | `parse` 收下一条、`format` 因名字非法丢掉它 → 返回 `""` 且 1 条 WARN 含「丢弃 1」 | **红**（同时证明「parse 收下、format 抹掉」的不可逆链路） |
| A-22 | 源码扫描 `NeovideCursorSettings.java`，`persistQuietly` 附近含「未抛异常」「不代表」 | **红** |
| A-23 | `setCharsMoved(1)+jump(8,20,…)` 记 `L_short`；再 `setCharsMoved(-1)+jump(0,0,…)` → 时长仍 `==L_short` 且有 1 条 WARN | **红**（走跨行分支，时长变化） |
| A-24 | `setCharsMoved(10)+jump(...)` 记 `L_long`；再 `setCharsMoved(-1)+jump(0,0,…)` → 仍 `==L_long` | 绿两侧（修复前 NaN→false→跨行，与「保持上一帧=跨行」同结果；只守方向，不作红绿依据） |

### 5.3 顺序与隔离（必须照做）

`V0Env.svc` 是全局单例、`REPORTED` 是静态集合，顺序错了会串台：

```
A-0（机制自检）
→ H-2 组 A-1..A-5（每组前 V0Env.install() 重置）
→ H-3 组 A-6 / A-6b（**A-6b 故意不重置**，它要观察 A-6 留下的 props 状态）→ A-7（重新灌键）
→ L-5 组 A-8
→ L-4 组 A-10（合法色先跑）→ A-9（单条非法）→ A-11（灌满上限）
→ M-4 组 A-12..A-17（全部新建对象，无全局状态）
→ M-10 组 A-18..A-21（纯函数）
→ L-6 A-22（源码扫描，需要 neovide.repo 或向上找 CLAUDE.md）
→ L-8 A-23 / A-24（AtomicBoolean 一次性标志，用前一步的 WARN 计数快照区分）
```

---

## 6. 对 `V0Env` 的最小改动（两个默认关闭的注入开关）

```java
public static final class FakeProps extends PropertiesComponent {
    /** 探针注入：下一次 isValueSet 抛异常（H-3 的「读旧键失败」）。默认 false。 */
    public static boolean throwOnceOnIsValueSet;
    @Override public boolean isValueSet(String n) {
        if (throwOnceOnIsValueSet) { throwOnceOnIsValueSet = false;
            throw new IllegalStateException("V0Env.FakeProps: 注入的读旧键失败"); }
        return map.containsKey(n);
    }
    ...
}

/** 探针注入：>0 时下一次获取 PropertiesComponent 抛异常（L-5）。默认 0。 */
public static int throwOnPropertiesLookup;
// service() 里 PropertiesComponent 分支：if (throwOnPropertiesLookup > 0) { ...--; throw ...; }
```

两个开关都写清「默认关闭，只有 `VAuditBatch1` 设它」。现有 102 个探针行为不变。

---

## 7. 明确不做（本批边界）

| 相邻问题 | 为什么不做 |
|---|---|
| 面板两处丢弃 `restoreSkipping` 返回值 | 动它 = 改面板行为；第 4 批（见 §4.2） |
| H-1 区间校验 / M-5 clamp 回落默认值 / M-1 颜色快照校验 / L-2 clamp 合并 | 第 2 批 |
| L-7 `format` 的 JUL → LOG | 第 3 批 |
| `restore()` 零调用、`smoothTicks` 死字段、包环 M-8、拆 M-9 | 第 4 / 5 批 |
| L-9 三个静止阈值统一 | 会动观感边界，不符合「零观感变化」 |
| `core/CoreLog.java` 日志门面 | 新增文件超白名单；批次 2 再做 |
| `TrailCorner.update` 里 `destX != previousDestX` 对 NaN 恒真 | 根因是「目标坐标本身 NaN」（尺寸来自编辑器），M-4 只覆盖弹簧内部状态 |

---

## 8. 执行顺序与门禁节奏

**铁律**：门禁共用 `build\v2-probe\classes` 且每次先删目录 —— **绝不并发跑两份**（四-G-2）。

### 阶段 RED
1. 改 `V0Env`（两个开关，默认关）。
2. 新建 `VAuditBatch1.java`，写全 A-0..A-24。
3. `build-v-probes.ps1` 的 `$mains` 里注册 `'VAuditBatch1'`（带一段说明本批守什么的注释）。
4. **同一轮**同步三处文档数字（见 §9），否则 `VDocCheck` D-2 必红。
5. 跑全量门禁，**把实际红名单抄进回报**，与 §5.2「修复前」一列逐条对账。
   对不上的先查清楚（多半是探针写错，而不是生产已经是对的）。

### 阶段 GREEN（按文件分组，每组后跑一次全量）

| 组 | 文件 | 闭环的断言 |
|---|---|---|
| G1 | `SettingsField` + `CursorPresetApplier` | A-1..A-5 |
| G2 | `NeovideCursorSettings`（H-3 + L-5 + L-6 同文件一次改完） | A-6、A-6b、A-7、A-8、A-22 |
| G3 | `CustomPresets` | A-18..A-21 |
| G4 | `DampedSpring` + `GlowIntensity` + `AfterimageTrail` | A-12..A-17（+ parity） |
| G5 | `NeovideColors` + `TrailCorner` | A-9..A-11、A-23、A-24（+ parity） |
| G6 | 全量门禁 + D0 + `git status` | 全部 |

G2 三合一的理由：同一个文件里三处，分开改要跑三次门禁、三次读同一份 diff。
G4 / G5 拆开的理由：core 改动必须配 parity，把「不经 core 的」与「经 core 的」分开能尽早暴露 core 侧问题。

### 变异测试（每条都必须变红；改完必须删 classes 全量重编译）

| 变异 | 必须变红 |
|---|---|
| decode 的 6 处 `reject(...)` 还原成 `return false` | A-1、A-3 |
| 删掉 `applied == 0` 的早退 | A-4 |
| 删掉 `readFailed` 的早退 | A-6、A-6b |
| 删掉 L-5 的 warn | A-8 |
| 删掉 `warnInvalid` 的调用 | A-9、A-11 |
| `MAX_REPORTED` 改 1000 | A-11 |
| 删掉 `update` 首个 `if` | A-12、A-13、A-14 |
| **只删 velocity 那一项**（保留 position/animationLength） | **只有 A-13** —— 这就是 `setVelocityForProbe` 的价值 |
| 删掉 `strengthFor` 的 guard | A-15 |
| 删掉 `setLifetime` 的 `isFinite` | A-16 |
| `setLifetime` 去掉上界 | A-17 |
| 删掉 `parse` 的两处 warn | A-18、A-19、A-20 |
| `PARSE_WARN_LIMIT` 改 1000 | A-20 |
| 删掉 `format` 的汇总 warn | A-21 |
| 删掉 L-6 的注释 | A-22 |
| 删掉零尺寸分支 | A-23（A-24 仍绿，与设计一致） |

---

## 9. 探针数量与文档同步（必须同一轮做完）

新增 1 个 `V*.java` → **102 → 103**。`VDocCheck` 的 D-2 强制三处一致且等于磁盘实测。

| 文件 | 行 | 改什么 |
|---|---|---|
| `CLAUDE.md` | 100 | `探针 102 个` → `103`，括号里追加 `VAuditBatch1` |
| `START-HERE.md` | 21 | `117 文件 / 26,677 行（102 个 V*.java）` → 用**当次实测**数字 |
| `docs/NEXT-STEPS.md` | 22 | `102 个 V*.java（全部 .java 117 个…）` → 当次实测 |

写之前先跑这三条，不许手抄上一个数字：

```
ls tools/settings-probe/V*.java | wc -l
ls tools/settings-probe/*.java  | wc -l
cat tools/settings-probe/*.java | wc -l
```

（`START-HERE.md:21` 与 `:26` 本来就互相矛盾，是既存漂移；这次两个数字用同一次实测。）

---

## 10. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| core 失去零平台依赖 | 中（已显式接受） | 惰性取 + `safeWarn` 兜 `Throwable` + 只落 2 个文件 + parity 双绿 |
| `safeWarn` 在两个 core 文件重复 | 低 | 白名单所致，交叉注释 + 本文件记录；批次 2 抽 `CoreLog` |
| decode 里加日志破坏「绝不抛异常」 | 中 | `reject()` 内 `try/catch(Throwable)` + 注释写清第一契约 |
| H-3 行为变更是故意的，可能被误当回归 | 中 | commit message 三段 + A-6b 直接演示丢值 |
| L-4 引入 core 静态可变集合、L-8 引入静态 `AtomicBoolean` | 低 | 有界 / 一次性；注释 + 备案 |
| `GlowIntensity` 对 `+Inf` 从 `1f` 变 `0f` | 低 | 只在非法偏移触发；注释 + commit message 明写 |
| Logger 工厂装晚了 → 「必须有日志」的断言永久红 | 中 | 工厂是 `main` 第一句 + A-0 机制自检 |
| 门禁并发跑 | 高（假红） | 严格串行；失败先看是不是 `ClassNotFoundException` 暴涨 |
| 有人改了现有探针断言让它变绿 | 高（纪律） | 本批不允许任何现有探针文件有 diff；`git status --porcelain` 逐个核对 |

**回滚**：8 条互相独立，`git checkout` 单文件即可回退对应那一条；
`TrailCorner` / `NeovideColors` 若因 Logger 出问题，把 `safeWarn` 换回 `System.err.println`
就立刻恢复零依赖（两行改动），这是最后一道保险。

---

## 11. 成功标准

- [ ] 全量门禁：条目 **100**，FAIL 仍为 **2**（都在 `VRegr2`），`VAuditBatch1` 出现在汇总表且 25/25
- [ ] RED 阶段实测红名单与 §5.2「修复前」一列逐条对得上
- [ ] 16 条变异测试全部按表变红（尤其「只删 velocity 守卫 → 只有 A-13 红」）
- [ ] `tools/parity/run-parity.ps1` 三个 harness 全部 OK
- [ ] D0：`core/NeovideConfig.java` 零 diff
- [ ] `VDocCheck` D-1/D-2/D-3 全绿（三处 = 103 = 磁盘实测）
- [ ] `git status --porcelain` 只出现白名单文件
- [ ] 现有探针文件**零 diff**

---

## 12. 需要用户拍板的点（阶段 1 的门）

1. **H-3 按「疏漏」修**（读失败 → 版本回 0、不删键、下次重试）。若你认为原行为是有意的，
   本条降级为「只写注释」。
2. **core 引入平台 Logger**（带 `safeWarn` 兜底），接受 `core` 不再零平台依赖。
3. **`GlowIntensity` 对 `+Infinity` 由「满强度 1f」改为「0f」**（非有限偏移一律当静止）。
4. **本批不修面板两处丢弃返回值的调用点**（记入第 4 批），因此 H-2 只修了 Tools 菜单那条路径。

---

# 执行记录（2026-10-30 实际做完之后补）

## RED（生产代码未改动，只加探针）

`VAuditBatch1` 26 条 → **5 PASS / 21 FAIL**，红名单与 §5.2「修复前」一列逐条对上。
其余 102 个探针全绿（`VRegr2` 是基线里的 informational）—— **没有现有断言变红**，§4 的契约核对成立。

## GREEN

按 G1→G5 改完后：`VAuditBatch1` 26/26，全量 **100 条目 / 1034 PASS / 2 FAIL**（仅 `VRegr2`）/ `ALL PASS`。
`core/NeovideConfig.java` 零 diff。parity 三项 OK。

## 变异测试（每轮都跑全量门禁，含删 classes 重编译）

| 变异 | 实测红名单 | 预期 |
|---|---|---|
| 只删 `DampedSpring` 的 velocity 那一项守卫 | **A-13** | 只有 A-13 ✅ |
| `MAX_REPORTED`→1000 且 `PARSE_WARN_LIMIT`→1000 | **A-11、A-20** | 恰这两条 ✅ |
| 删 `applied == 0` 早退 + 删零尺寸分支 | **A-4、A-23** | 恰这两条 ✅ |
| `decode` 6 处 `reject` 还原成 `return false` + 删 `readFailed` 早退 + 删 L-5 日志 + 删 `applied < total` 告警 | **A-1、A-3、A-5、A-6、A-6b、A-8** | 恰这 6 条 ✅ |
| 关掉 M-10 的全部日志 + L-6 注释改词 + M-4 三处守卫 | **A-12…A-22（11 条）** | 恰这 11 条 ✅ |
| 关掉「汇总去重」（新增 A-20b 之后补做） | **A-20b** | 只有 A-20b ✅ |

合计 6 轮变异，覆盖全部 8 条修复；每一轮的红名单都与预期**逐条相等**，没有多红也没有少红。

## 审查（两个代理并行）之后的改动

`code-reviewer`：0C / 0H / 2M / 5L；`java-reviewer`：0C / 0H / 4M / 6L。两边独立命中同两条：

1. **（MEDIUM，已修）`parse` 的「共跳过 N 条」汇总没有去重** —— `parse` 在读路径上被菜单/面板
   高频调用，磁盘上留一条坏数据就会「每弹一次菜单刷一行」，与本节给逐条日志设上限的理由矛盾。
   修法：`PARSE_SUMMARY_REPORTED` 按 `坏/重名` 计数对去重 + 有界；新增断言 **A-20b** 守它。
   同时把 `reportDropped` 的语义改成「**登记无条件、打印受预算**」——
   反过来做会让同一条坏数据分 10 轮慢慢报出来（这是 A-20b 第一版红掉的真实原因）。
2. **（MEDIUM，已修）`applied < total` 把「旧快照缺键」误报成「值非法」** ——
   用户用旧版本存的预设天然缺新字段的键，那是 `snapshot/restore` 文档化的兼容行为。
   告警措辞改成「缺失的键与非法值都保持当前值」（数字仍是 `applied/total`，A-5 不受影响）。

其余采纳的 LOW/MEDIUM：
- `decode` 的 `catch (RuntimeException)` 补带堆栈（那一类只可能是代码 bug，只留类名没用）。
- `NeovideColors.REPORTED` 改存**截断后**的键（原先条数有界但键长度无界）。
- `CustomPresets` 重名分支的名字也走同一套截断（原先只有「坏」分支截断）。
- `AfterimageTrail` 的「long 回绕」机理注释写错了（double→long 是**饱和**不是回绕），已改成真实后果：
  cutoff 变大负数 → 残影永不过期、几乎全不透明，靠 `MAX_GHOSTS` 兜住。
- 三处文档数字按 §十一 的自检命令**重测**（此前是在一版已经失真的旧数字上做加法）。
- A-22 旁边写清它的边界：只能证明「注释还在」，抓不到「注释与代码不一致」。

未采纳（已记录理由）：`reject()` 不加去重预算（调用点是用户点击级，不会成洪水）；
`TrailCorner` 零尺寸守卫不提到 `charsMoved >= 0` 分支之前（那不是本批引入的，且会改合法路径结构）；
`*ForProbe` 访问器不缩可见性（项目既有 93 处都是 public）。
