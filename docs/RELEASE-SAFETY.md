# 发布安全：升级不掉用户配置

> 本文是发布前的检查清单。**违反任一条，发布就会静默改掉用户的设置。**

---

## 一、核心铁律

**已经有默认值语义的字段，其默认值永远不能改。**

### 为什么

用户的 XML 是旧版本写的。假设 V2 把 `tailOpacity` 的默认值从 `1.0` 改成 `0.9`：

```
用户从没碰过这个滑块，所以 XML 里没有它的「用户值」，只有平台按
「字段声明初值」给的默认值。

升级到 V2 → State 的声明初值变成 0.9 → 所有没动过这个字段的用户
光标都从全不透明变成 90% 不透明。
```

**他们从没调过那个滑块，却看到了不同的光标。** 这是最难排查的一类问题 ——
用户会说「我没改过设置，但它变了」。

### 实测依据

本机 IDEA 2026.1 的 `Storage` 注解**没有 `skipDefaults` 参数**了（已删）：

```java
public interface com.intellij.openapi.components.Storage {
  String file();  String value();  boolean deprecated();
  RoamingType roamingType();  Class<? extends StateStorage> storageClass();
  Class<? extends StateSplitter> stateSplitter();
  ThreeState useSaveThreshold();      // ← 只剩这个，与默认值无关
  boolean exclusive();  boolean exportable();  boolean usePathMacroManager();
}
```

所以平台会**写全部字段**（实测 `XmlSerializer.serialize` 输出 28 个 option，
即使全部等于默认值）。这意味着：

- ✅ 好处：XML 永远完整，不会出现「字段被跳过」的迷惑现象
- ❌ 风险：默认值一改，旧 XML 里那个字段的值会被 `loadState` 原样搬进来 ——
  但**新安装的用户**拿到的是新默认值。同一个版本，老用户和新用户观感不同。

---

## 二、三道防线

### 防线 1：默认值冻结（自动守门）

`tools/settings-probe/VDefaultFreeze.java` —— 5 条断言：

| 判据 | 挡什么 |
|---|---|
| F-1 | 28 个字段的默认值逐个与黄金值比对，改一个就红 |
| F-2 | 新增字段必须显式登记默认值（防止绕过冻结） |
| F-3 | 出厂观感符合用户要求（基础/粉色/2px/顶格/平滑关/总开关开） |
| F-4 | `State` 与 `NeovideConfig` 的共有字段默认值必须一致 |
| F-5 | 出厂默认必须等于「基础」预设（首次安装看到的就是基础） |

**变异测试已验证它抓得住**（见下）：

```
M1 把 tailOpacity 1.0f → 0.9f
   → FAIL F-1 漂移=[tailOpacity: 冻结=1.0 实际=0.9]
   → FAIL F-4 不一致=[tailOpacity: State=0.9 CONFIG=1.0]
   → FAIL F-5 出厂默认 != 基础预置

M2 把 glowLayers 8 → 6
   → FAIL F-1 漂移=[glowLayers: 冻结=8 实际=6]
   → FAIL F-4 / F-5 同样触发
```

### 防线 2：字段级迁移（确实要改默认值时）

**流程（缺一步就会掉配置）**：

```java
// 1) STATE_VERSION_MIGRATED 从 1 改成 2
// 2) loadState 里加迁移段：
if (from.stateVersion < 2) {
    // 【关键】仅当用户值是「旧默认值」才改成新默认值
    //          用户自己调过的值必须原样保留
    if (Float.compare(from.tailOpacity, 1.0f) == 0) {
        target.tailOpacity = 0.9f;
    }
}
// 3) 更新 VDefaultFreeze 的黄金值（一次显式、有记录的决策）
// 4) 在 docs/RELEASE-SAFETY.md 的「默认值变更记录」里写一行
```

**为什么第 2 步的守卫不能省**：不加 `if (用户值 == 旧默认值)`，就会把
**真的调过这个字段**的用户也一起改掉 —— 那正是「升级掉配置」。

### 防线 3：干净安装（开发期验证用）

`build/install.ps1` 每次安装都清掉设置 XML + 诊断日志：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\build\install.ps1 -Version 0.9.70
```

**这不是发布流程的一部分**，是开发期验证用的。它保证每次验证都从出厂默认开始，
把「代码 bug」和「历史脏数据」彻底分开 —— 这次排查「24 个字段消失」
「tailOpacity 停在 0.05」耗费大量时间，就是因为分不清这两者。

---

## 三、发布前检查清单

```
[ ] 1. VDefaultFreeze 全绿（28 个默认值未漂移）
[ ] 2. 若有字段改了默认值：STATE_VERSION_MIGRATED 已 +1，loadState 有对应迁移，
        且迁移带「仅当用户值 == 旧默认值」守卫
[ ] 3. 若有新增字段：已登记进 VDefaultFreeze.GOLDEN，且默认值符合出厂观感要求
[ ] 4. VAddSetting 全绿（新增字段已进 SettingsField.FIELDS + copyFrom）
[ ] 5. D0 PASS（core/NeovideConfig.java 冻结）
[ ] 6. PhysicsParity PASS
[ ] 7. 全部探针与上一版逐条对比，无变差
[ ] 8. 用 install.ps1 干净安装一次，确认出厂观感 = 基础预设 + 粉色 + 2px + 顶格
```

---

## 四、默认值变更记录

| 日期 | 版本 | 字段 | 旧值 | 新值 | 迁移 | 原因 |
|---|---|---|---|---|---|---|
| （空 —— 尚无变更） | | | | | | |

**规则**：这张表每加一行，都意味着一次可能掉配置的发布。
必须同时有迁移代码 + 探针黄金值更新 + 本条记录，三者缺一不可。

---

## 五、历史事故（为什么有这份文档）

### 2026-10-25：用户报「光标半透明」

**现象**：`tailOpacity = 0.05`，光标 5% 不透明度，几乎看不见。

**两个独立原因**：
1. `0.9.67` 把 `tailOpacity` 补进预设域后，切预设会覆盖它 —— 这是新引入的
2. 但 0.05 这个值本身来自**副本快照**：用户在副本里把滑块拖到了最左边（5% 刻度）

**根因不是默认值，是「恢复为默认值」在副本上是空操作**（restore 自己存的快照），
用户点了按钮以为会回到默认，实际什么也没变，所以 0.05 一直留着。

已在 `0.9.68` 修复：副本恢复 = 回到它派生自的内置预设。

### 同一次排障中的错误结论（记下来避免重犯）

| 我当时的判断 | 实际 | 怎么被推翻的 |
|---|---|---|
| `loadState` 会把缺失字段写成零值 | 缺失字段保留声明初值，安全 | 构造只有 2 字段的 `from` 实测 |
| 平台序列化会跳过等于默认值的字段 | 2026.1 已无 `skipDefaults`，28 个全写 | `javap` 查注解 + 序列化计数 |
| `State` 与 `CONFIG` 默认值不一致导致漂移 | 26 个字段全部一致 | 逐字段对照打印 |

**教训**：涉及「数据丢了」的判断，必须**逐层排除**（读路径 / 写路径 / 默认值 / 平台行为），
不能凭「XML 里字段少」就下结论。