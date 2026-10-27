# runtimeSwitch 重构 —— 交接报告

> 2026-10-27 · 给决策会话 · 代码在 worktree `quizzical-raman-7c159c`，**未提交**

---

## 一、做了什么

把「恢复自定义预设时跳过哪些字段」从**独立字符串数组**改成**字段自身的布尔标志**。

```java
// 改前：清单在别处（SettingsField.java:645 附近），与字段定义隔 600 行
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
| 加设备级字段要跑到 600 行外补字符串，容易忘 | 0.9.87 前只有 4 项，漏 5 个 → 切预设改掉用户帧率/光标尺寸（**已发生过**） |
| 字符串拼错编译器不报错 | 静默失效，只有用户发现「设置又变了」 |
| **全仓零探针调用 `restoreSkipping`** | 「切预设真的跳过吗」这条关键路径零覆盖；旧探针只查数组内容 = 测声明不测行为 |

**用户可见行为：零变化。** 纯内部可维护性改善。

---

## 三、改动范围

| 文件 | 变化 |
|---|---|
| `settings/SettingsField.java` | +126/−92。加 `runtimeSwitch` 字段+构造器参数；5 个工厂各拆 6/7 参两版（原签名委托传 `false`）；9 字段传 `true`；`restoreSkipping` 改用标志并删 `skip` 参数；删数组；旧 javadoc 迁移 |
| `ui/CursorPresetApplier.java` | 8 行，1 处调用去数组实参 |
| `ui/settings/NeovideCursorConfigurable.java` | 6 行，同上 |
| `tools/settings-probe/VSwitchKeysGap.java` | 重写：A/B 声明断言 + **C 行为断言**（真调 restoreSkipping） |
| `tools/settings-probe/VLoadState.java` | L-5 改数标志位，断言 == 9 |

**关键设计**：用工厂重载而非改 26 个调用点 → 17 个非 runtime 字段调用点**一字未动**。

---

## 四、验证结果

| 门禁 | 结果 |
|---|---|
| D0（`NeovideConfig.java` 零 diff） | ✅ exit=0 |
| 全量探针 | ✅ **8 个未通过，与基线逐项完全一致** |
| 逐行 diff | ✅ 只有 5 文件，只有 9 字段被加 `true` |
| 代码级 `RUNTIME_SWITCH_KEYS` 残留 | ✅ 0 处（剩 4 处全在注释讲历史） |

**基线 8 个红灯（均为过期断言，本次未碰）**：`VDefects(1) VSnap(10) VTools(1) VHints(1) VCustom(1) VReset2(2) VSepBound(7) VSleep(1)`，另有 `VRegr2` 为 informational。

**变异测试（两条都验真）**：
- 变异 X：删 `restoreSkipping` 的 `continue` → C-1 精确报出全部 9 个字段泄漏（`applied=26 期望=17`）
- 变异 Y：`targetFps` 改 `false` → A/B/C-1/C-3 + `VLoadState` L-5 一起变红

---

## 五、已安装

```
版本      0.9.93（jar 内自检通过）
路径      ...\plugins\neovide-cursor-pawamamama\lib\
旧 jar    已移到备份目录
设置 XML  已删除（备份 .bak-20261027-184130）
IDEA      未启动，等用户测试
```

**已知瑕疵**：jar 文件名仍是 `jetbrains-neovide-cursor-0.9.8.jar`（`install.ps1` 写死），但内部版本号确为 0.9.93，不影响功能。

---

## 六、需要决策会话拍板的三件事

1. **推送到哪个 remote**
   ```
   fork    pawamamama/neovim-cursor-for-jetbrains        ← 用户自己的账号，发布目标
   origin  yyyolo7a79-sketch/neovim-cursor-for-jetbrains ← 原始上游，不推
   ```
   用户说的「推送到上游 pawamamama」= **`git push fork`**，命名容易误解。

2. **要不要开 PR**，还是直接推分支。

3. **版本号 0.9.93 是否采用**（当前已装的就是这个）。

---

## 七、未做的事（明确边界）

- 未提交、未推送、未开 PR
- 未碰既有的 8 个过期探针断言（属另一任务）
- 未碰 `copyFrom` / `loadState` / 面板 / 闪烁 / 辉光问题
- 未让 `PRESET_FIELDS` 排除 runtime 字段（快照仍存 26 项、恢复跳 9 项，"存了不用"是冗余但不影响行为；改它等于改快照格式，超出范围）

---

## 八、工作门禁提醒（给新会话）

在本 worktree 跑门禁**必须带 `-Repo`**，否则脚本默认编译主仓库代码，给出假绿：

```bash
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1 -Repo "D:\deepseekwork\neovim-cursor-pr\.claude\worktrees\quizzical-raman-7c159c"
```
