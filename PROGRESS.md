# PROGRESS —— 当前指针

> 更新：2026-10-22 周末。**自定义预设已可用；t9 尚余 5 段红（待改旧探针）。**

## 0. 一句话

`bc903e1`：自定义预设（自动派生）+ 字段表已完成，**V 探针 211 条全绿**；
t9 的 t7 / settings-v2 / 4bugs / t4-verify / v-mutations 五段仍红，
原因是它们引用已删除的 `animPresetOverrides`。

## 1. 分支与状态

| 项 | 值 |
|---|---|
| 分支 | `fix/f7-override-read`，HEAD = `bc903e1` |
| 用户机器已装 | **0.9.8**（`41F16B3F` / 102,223 B）—— **未动过，手感不变** |
| 本地产物 | 无新 jar（未构建） |
| V 探针 | **211 pass / 0 fail**（11 个探针，含新增 VCustom 25 条） |
| t9 | 7 段绿 / 5 段红 |

## 2. 自定义预设的模型（已实现并验证）

按最新规格（`PROGRESS.md@9220577` §4「S3，已批准」）+ 用户口述：

- **内置预设永久只读**（7 个动画 + 5 个颜色）
- 用户改动任何动画字段 → **自动派生**「{内置名} 副本」并切过去，内置一位数不动
  （依据：GIMP 官方文档「编辑内置预设时自动创建副本，所以 Duplicate 命令永远禁用」；
   IntelliJ 配色方案的内置方案同样只读、靠 Duplicate/Rename/Delete）
- 自定义预设 = **全参数快照**（19 个参数），持久化，可改名 / 可删除
- 名称允许中文、空格、`;`、`=`（分隔符用不可键入的 U+0001 / U+0002）
- **「已修改」概念删除**，`animPresetOverrides` 字段删除

## 3. 新增文件

| 文件 | 作用 |
|---|---|
| `settings/SettingsField.java` | 字段表 20 项；每项自带比较语义；快照编解码；**判脏的唯一实现** |
| `settings/CustomPresets.java` | 自定义预设纯函数层：parse/format/put/get/rename/remove/uniqueName |
| `tools/settings-probe/VCustom.java` | 25 条断言：快照往返 / 增删改查 / 内置只读 / 自动派生 |

## 4. 还差什么（按优先级）

| # | 项 | 说明 |
|---|---|---|
| 1 | **改 4 个旧探针** | `ProbeSettingsV2` / `ProbeT7` / `Probe4Bugs` / `VerifyT4` 仍引用 `animPresetOverrides` |
| 2 | **改变异脚本 MUT-C** | 它正则匹配 `apply()` 的手写 if，已换成遍历表 |
| 3 | **UI 三个按钮** | 新建 / 重命名 / 删除（自动派生已工作，但没有手动入口） |
| 4 | 出 jar + 登记 VERSIONS | 未做 |

## 5. 红线仍然有效

- 已装 jar 是 0.9.8，**未安装任何新 jar**
- `core/` 零改动（D0 exit=0）
- 物理 / 渲染参数没碰
