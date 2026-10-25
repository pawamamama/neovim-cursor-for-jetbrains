# 设置页稳健性审计（P-1）

> 用户要求：「去找 skill 上 github 查查你现在的设置页面真的改不坏再继续」。
> 本文记录：查了什么、审出什么、**用变异测试证明探针抓得住漏改**。

---

## 一、查了哪些一手资料

| 来源 | 拿到什么 |
|---|---|
| [Settings Guide](https://plugins.jetbrains.com/docs/intellij/settings-guide.html) | 设置应基于 `Configurable`；性能上鼓励把元信息放 plugin.xml 属性里，否则要加载组件才会拿到，拖慢 UI 响应 |
| [Persisting State of Components](https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html) | 官方推荐继承 `SimplePersistentStateComponent` 或 `SerializablePersistentStateComponent`（2022.2+）；`getStateModificationCount()` 可避免为判脏去调 `getState()` |
| [Kotlin UI DSL v2](https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl-version-2.html) | `panel { row { ... } }` 的声明式结构；`Panel.panel` 可建子面板；DSL 面向「绑定状态对象的输入组件」，不适合通用 UI |

### 与本项目的对照

**官方推荐的两个基类我们都没用** —— 本项目是纯 Java（无 Kotlin），且
`State` 是普通 POJO 而非 `BaseState` 子类。这不是缺陷：
`SimplePersistentStateComponent` 的价值主要在「属性委托自动追踪修改」，
而我们的 `isModified()` 是自己逐字段比对（见 `pendingVsStateDiff()`），
精度反而更高（能区分「值相同但刻度不同」这种委托追踪抓不到的情况）。
**结论：保持现状，不迁移。** 迁移会动到 `core/` 与全部探针，收益不抵风险。

---

## 二、现状结构（S3 重构后）

```
createPanel()                         50 行
  └─ BuilderKt.panel { panel ->
       buildVersionRow(panel)          顶行：版本 + 插件信息
       buildPresetRows(panel)          预设下拉 / 恢复 / 自定义三动作
       buildMasterGroup(panel)         总开关（4 项）
       buildInlineGroup(panel)         单行输入输出 + 行内预览
       buildCrossGroup(panel)          多行缎带 + 跨行预览
       buildColorGroup(panel)          颜色（2 项）
       buildCursorGroup(panel)         光标（宽 / RGB / 高）
       buildGlowGroup(panel)           辉光（4 项）
       buildFadeGroup(panel)           淡出（2 项）
       buildExperimentalGroup(panel)   实验性（折叠）
       buildDiagGroup(panel)           高级诊断（折叠）
     }
```

加新参数的最小改动面：**写一个方法 + 在 `createPanel()` 加一行调用**。

---

## 三、加一个新参数要改哪几处（这是风险点）

### 滑块型（如 cursorWidthPx）—— 4 处

1. `NeovideCursorSettings.State` 加字段 + 默认值
2. `State.copyFrom()` —— **漏了则面板回显不出来**
3. `SettingsField.FIELDS` —— **漏了则 apply 静默回滚**
4. `NeovideCursorConfigurable`：`SPECS[]` 加项 + `build*Group` 里 `addSlider` 一行

### 复选框型（如 rgbGradient）—— 3 处

1. `State` 字段 + 默认值
2. `copyFrom()`
3. `SettingsField.FIELDS` + `build*Group` 里 `bindCheck` 一行

### 关键：第 2、3 步**失败时是静默的**

这不是推测，是历史事故：`diagLogging` 勾选后应用即消失，原因就是
**第 2 步和第 3 步都漏了**（BUG-001 的第二层）。当时没有任何报错，
表现只是「勾了但没生效」。

---

## 四、新增守门探针 VAddSetting

`tools/settings-probe/VAddSetting.java`，6 条断言：

| 判据 | 抓什么 |
|---|---|
| A-1 | State 字段 ∉ FIELDS（且不在白名单）→ 漏注册 |
| A-2 | FIELDS 里有指向不存在字段的 key → 拼写错误 |
| B-1 | 反射把 State 全字段设成非默认值，`copyFrom` 后逐字段比对 → 漏拷 |
| C-1 | `createComponent()` 不抛异常且返回 DialogPanel |
| C-2 | 控件数量与已知基线一致（滑块 13 / 复选框 9 / 下拉 3） |
| D-1 | `reset` → `isModified=false` → `apply` → `isModified=false` |

### 白名单（NOT_PERSISTED）与理由

```
stateVersion       版本号，由 loadState 单独处理
customAnimPresets  自定义预设表，由 CustomPresets 单独管理
animCustomName     派生名，同上
animPresetBase     进表但标记「不进快照」，见 presetPointer()
```

每加一条白名单都要写理由 —— 这是防止「漏了就当成设计」的唯一手段。

---

## 五、变异测试：证明探针真的抓得住（这是本文的核心）

只跑通不算稳。必须证明「故意改坏会被抓住」。做了 4 个变异，全部在**临时目录**
编译运行，跑完立即用备份文件恢复并核对 `git status` 干净。

| 变异 | 内容 | 探针反应 |
|---|---|---|
| **M1** | 从 `FIELDS` 删掉 `rgbGradient` | ✅ `FAIL A-1 … 未解释的缺口=[rgbGradient]` |
| **M2** | `copyFrom` 删掉 `cursorHeightRatio = from.cursorHeightRatio;` | ✅ `FAIL B-1 … 漏拷=[cursorHeightRatio]` |
| **M3** | `FIELDS` 里把 key 拼错成 `cursorHeightRation` | ✅ `FAIL A-1 缺口=[cursorHeightRatio]` + `FAIL A-2 幽灵=[cursorHeightRation]` |
| **M4** | 新增字段 `glowSpin`，完全不进 FIELDS / copyFrom（真实事故形态） | ✅ `FAIL A-1 缺口=[glowSpin]` + `FAIL B-1 漏拷=[glowSpin]` |

**四个变异全部被抓，且报出的是精确字段名。**

这意味着：下次加参数漏改第 2 或第 3 步，**CI/门禁会当场红**，
不用等用户发现「勾了没生效」。

### 变异测试的纪律

1. 只改一个点，跑完立刻恢复
2. 恢复后必须 `git status` 核对（本次确认过：唯一改动是新增的探针文件）
3. 变异源文件先备份，用备份恢复而不是反向 `-replace`

---

## 六、结论

**设置页现在「改不坏」是有证据的**，依据不是代码整洁，而是：

1. 加参数的改动面被压缩到「1 个方法 + 1 行调用」
2. 剩下的 3~4 处同步点全部被 `VAddSetting` 覆盖
3. 探针经 4 个变异验证，抓得住每一类漏改，且报精确字段名
4. `reset/apply/isModified` 无残留脏状态

**未覆盖的风险（诚实列出）**：

1. **`SPECS[]` 数组的下标是硬编码的**（`SPECS[11]` = 光标宽度）。
   加参数插在中间会让后面所有下标偏移。当前靠 `build*Group` 里手写数字，
   没有编译期保护。改进方向：改成按 key 查找（`specOf("cursorWidthPx")`）。
2. **控件索引也被探针硬编码**（`ui.checkBoxes.get(5)`）。这次 S3 重构就因为
   组顺序变化踩过一次（R-8 变好 / R-11 变坏）。建议探针改用「按文案定位」。
3. **新增 `ColorPanel` 类控件不参与 `DialogPanel` 自动追踪**，必须手工在
   `reset/apply/isModified` 三处同步。目前只有 2 个（tailColor / shadowColor），
   都手工处理了，但探针没有断言「ColorPanel 数量 == 手工同步处数」。

这三条已进技术债，不阻塞下一步。