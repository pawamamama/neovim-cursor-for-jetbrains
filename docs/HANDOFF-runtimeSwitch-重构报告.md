# runtimeSwitch 重构 —— 交接报告（本地已提交，未推送）

> 2026-10-27 · 给决策会话
> 代码位置：worktree `D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\quizzical-raman-7c159c`
> 分支 `claude/quizzical-raman-7c159c` · 提交 `016046a` · 工作区干净 · **未推送**

---

## 一、做了什么

把「恢复自定义预设时跳过哪些字段」从**独立字符串数组**改成**字段自身的布尔标志**。

```java
// 改前：清单在别处（SettingsField.java 约 645 行），与字段定义隔 600 行
public static final String[] RUNTIME_SWITCH_KEYS = { "enabled", "targetFps", ... };
SettingsField.restoreSkipping(s, snap, RUNTIME_SWITCH_KEYS);

// 改后：判断写在字段那一行
out.add(ints("targetFps", ..., null, null, true));
SettingsField.restoreSkipping(s, snap);
```

---

## 二、解决了什么问题

| 问题 | 后果 |
|---|---|
| 加设备级字段要跑到 600 行外补字符串，容易忘 | 0.9.87 前只有 4 项、漏 5 个 → 切预设改掉用户帧率/光标尺寸（**已真实发生过**） |
| 字符串拼错编译器不报错 | 静默失效，只有用户发现「设置又变了」 |
| **全仓零探针调用 `restoreSkipping`** | 「切预设真的跳过吗」这条关键路径零覆盖；旧探针只查数组内容 = 测声明不测行为 |

**用户可见行为：零变化。** 纯内部可维护性改善。

---

## 三、改动范围（6 个文件，429 增 / 130 删）

| 文件 | 变化 |
|---|---|
| `settings/SettingsField.java` | +126/−92。加 `runtimeSwitch` 字段与构造器参数；5 个工厂各拆 6/7 参两版（原签名委托传 `false`）；9 字段传 `true`；`restoreSkipping` 改用标志并删 `skip` 参数；删数组；旧 javadoc 迁移 |
| `ui/CursorPresetApplier.java` | 8 行，1 处调用去数组实参 |
| `ui/settings/NeovideCursorConfigurable.java` | 6 行，同上 |
| `tools/settings-probe/VSwitchKeysGap.java` | 重写：A/B 声明断言 + **C 行为断言**（真调 restoreSkipping） |
| `tools/settings-probe/VLoadState.java` | L-5 改数标志位，断言 == 9 |
| `docs/HANDOFF-runtimeSwitch-重构报告.md` | 本报告（上一版） |

**关键设计**：用工厂重载而非改 26 个调用点 → 17 个非 runtime 字段的调用点**一字未动**。

---

## 四、验证结果

| 门禁 | 结果 |
|---|---|
| D0（`core/NeovideConfig.java` 零 diff） | ✅ exit=0 |
| 全量探针 | ✅ **8 个未通过，与基线逐项完全一致** |
| 逐行 diff | ✅ 只有 5 个代码文件，只有 9 个字段被加 `true` |
| 代码级 `RUNTIME_SWITCH_KEYS` 残留 | ✅ 0 处（剩 4 处全在注释讲历史） |

**基线 8 个红灯（均为过期断言，本次未碰）**：
`VDefects(1) VSnap(10) VTools(1) VHints(1) VCustom(1) VReset2(2) VSepBound(7) VSleep(1)`，
另有 `VRegr2` 为 informational，不计入。

**变异测试（两条都验真，均当场还原）**：
- 变异 X：删 `restoreSkipping` 里的 `continue` → C-1 精确报出全部 9 个字段泄漏（`applied=26 期望=17`）
- 变异 Y：`targetFps` 改 `runtimeSwitch=false` → A/B/C-1/C-3 与 `VLoadState` L-5 一起变红

---

## 五、当前状态

```
分支      claude/quizzical-raman-7c159c
提交      016046a（父 a3e678a）
工作区    干净
远程      未推送，未开 PR
主仓库    D:\deepseekwork\neovim-cursor-pr 一直是 a3e678a [main]，未被碰过
```

### 已安装插件

```
版本      0.9.93（解压 plugin.xml 自检确认）
路径      ...\JetBrains\IntelliJIdea2026.1\plugins\neovide-cursor-pawamamama\lib\
旧 jar    已移到 D:\deepseekwork\_jar-backup-20261024（带时间戳）
设置 XML  已删除，备份在 options\NeovideCursorSettings.xml.bak-20261027-184130
配置      已重置为出厂默认（基础预设 / 粉色 / 2px / 顶格 100% / 平滑关闭）
IDEA      未启动
```

**已知瑕疵**：jar 文件名仍是 `jetbrains-neovide-cursor-0.9.8.jar`（`install.ps1` 硬编码），
但内部版本号确为 0.9.93，不影响功能。

---

## 六、回退方式

改动全部收在一个提交里，回退一步到位：

```bash
cd D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/quizzical-raman-7c159c && git reset --hard a3e678a
```

只查看、不撤销：

```bash
cd D:/deepseekwork/neovim-cursor-pr/.claude/worktrees/quizzical-raman-7c159c && git show 016046a
```

连 worktree 都不要：直接删目录即可，主仓库未受影响。

**注意**：已装的 0.9.93 jar 与代码提交是两回事。回退代码不会自动回退插件；
若要回到 0.9.92，需从 `_jar-backup-20261024` 找回旧 jar 重新安装。

---

## 七、推送目标（如后续要推）

```
fork    pawamamama/neovim-cursor-for-jetbrains        ← 用户自己的账号，发布目标，用 git push fork
origin  yyyolo7a79-sketch/neovim-cursor-for-jetbrains ← 原始上游，不推
```

命名反直觉：**「上游 pawamamama」指的是 `fork` remote，不是 `origin`**。

---

## 八、明确未做的事

- 未推送、未开 PR（用户明确要求只保存在本地可回退状态）
- 未碰既有 8 个过期探针断言（属另一任务，纪律二.6：改方向不删断言）
- 未碰 `copyFrom` / `loadState` / 面板 / 闪烁 / 辉光问题（红线 R2、R3、R8）
- 未让 `PRESET_FIELDS` 排除 runtime 字段（快照仍存 26 项、恢复跳 9 项，
  「存了不用」是冗余但不影响行为；改它等于改快照格式，超出本次范围）

---

## 九、给新会话的门禁提醒

在 worktree 里跑探针**必须带 `-Repo`**，否则脚本默认编译主仓库代码、给出假绿：

```bash
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo "D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\quizzical-raman-7c159c"
```

另外：`install.ps1` 与 `dist/` 不在 worktree 里（被 gitignore），
本次是从主仓库拷贝进来的，仅本地存在。

---

## 十、待用户决策

1. **是否推送** —— 当前按用户要求停在本地。如果要发，推 `fork`，版本号 0.9.93。
2. **是否开 PR** 还是直接推分支。
3. **jar 文件名** 是否顺手修掉（`install.ps1` 里硬编码的 `jetbrains-neovide-cursor-0.9.8.jar`）。
