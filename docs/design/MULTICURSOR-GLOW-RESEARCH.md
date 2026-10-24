# 多光标辉光降级 —— 调研报告

> 用户需求（2026-10-24）：
> 「辉光层数随光标数降级 3 做成独立多光标区域吧
>   开启后多光标降级，加一个多光标完全不辉光只拉伸
>   一共两个功能但是得看架构支持吗难不难做 做好后bug修复时常多吗 做调研」

---

## 一、需求拆解（其实是 2 个功能）

| # | 功能 | 语义 |
|---|---|---|
| **F1** | **多光标辉光降级** | 光标数 ≥ N 时，辉光层数自动减少（8 → 4 → 2） |
| **F2** | **多光标完全不辉光** | 多光标时彻底关辉光，只保留拉伸形状 |

两个都放「独立多光标区域」（新建一个设置分组）。

---

## 二、架构可行性 —— **完全支持，改动小**

### 2.1 关键事实（已实测）

```java
// NeovideConfig.java:（public 可写）
public int glowLayers = 8;

// CaretTrailPanel.java:487（唯一读取点）
int layers = config.glowLayers;
```

**只有两处**：一个定义、一个读取。没有缓存、没有派生、没有多份真相。

### 2.2 运行期改值的现成通道

`applyConfig()` 已经每帧在跑这条链：

```
State ──syncStateToConfig()──▶ CONFIG.glowLayers
      ──onModeChanged()────▶ animator 刷新缓存
      ──下一帧绘制────────▶ panel 读新值
```

**所以不需要新架构，只要在 `syncStateToConfig()` 里加一行「按光标数调整」。**

### 2.3 D0 约束的真实范围

```powershell
# run-t9-full.ps1 的 D0
git diff --exit-code -- src/main/java/.../core/NeovideConfig.java
```

**D0 守的是「源码文件不许改」，不是「字段不许改」。**
运行期改 `CONFIG.glowLayers` 的值完全合法。

---

## 三、实现方案

### F1：按光标数降级

```java
// NeovideCursorSettings.syncStateToConfig() 末尾加：
int effectiveLayers = state.glowLayers;
if (state.multiCursorGlowDegrade) {
    int n = 当前光标数;
    if (n >= 8)      effectiveLayers = Math.min(effectiveLayers, 2);
    else if (n >= 4) effectiveLayers = Math.min(effectiveLayers, 4);
}
c.glowLayers = effectiveLayers;
```

**难点**：`syncStateToConfig()` 拿不到光标数（它是纯数据层）。
**解法**：由 `CaretAnimator` 在每帧算好后通知，或用 `NeovideCaretManager` 聚合。

### F2：完全不辉光

```java
// CaretTrailPanel.java:483
if (!config.useShadow || config.glowLayers <= 0 || glowWidth <= 0.5f) {
    return;   // ← 已有早退分支
}
```

**F2 极其简单**：多光标时把 `effectiveLayers` 设为 0 即可，**现有代码已经处理 0**。

---

## 四、难度评估

| 功能 | 难度 | 改动量 | 新增探针 |
|---|---|---|---|
| **F2 完全不辉光** | ⭐ 极低 | 约 20 行 | 2 条 |
| **F1 按光标数降级** | ⭐⭐ 低 | 约 50 行 | 3 条 |
| 设置 UI（新分组） | ⭐ 低 | 约 40 行 | 2 条 |

**合计约 110 行 + 7 条探针。**

---

## 五、Bug 修复时长的真实评估

用户问：「做好后bug修复时常多吗」

### 风险点

| 风险 | 等级 | 说明 |
|---|---|---|
| **性能未实测** | 🔴 高 | 你不知道「8 个光标 × 8 层辉光」到底卡不卡。**没有基线数据就不该先优化。** |
| 光标数变化时机 | 🟡 中 | 拖选过程中光标数一直在变 → 层数频繁跳变，可能闪烁 |
| 与相邻行分组冲突 | 🟡 中 | 现在按 `groupId` 共享辉光，降级要按「全局光标数」还是「组内数」？ |

### 我的判断

**F2 值得做**（简单、无争议、立刻见效）。

**F1 需要先有数据**：

```
如果 8 个光标 × 8 层根本不卡 → F1 是纯浪费，还引入跳变风险
如果确实卡 → 先做 F2（不辉光）就够了，不必做 F1（降级）
```

**建议顺序**：
1. 先实测「多光标 + 满辉光」的真实帧率
2. 若卡 → 直接做 F2（最简单有效）
3. 若 F2 不够 → 再做 F1

---

## 六、结论

| 问题 | 答案 |
|---|---|
| 架构支持吗 | ✅ **完全支持**，无需改动核心 |
| 难做吗 | ✅ **F2 极简（20 行），F1 简单（50 行）** |
| bug 修复时长大吗 | ⚠️ **取决于是否有性能基线**。没有基线就是盲目优化 |
| 会不会碰 D0 | ✅ **不会**（只改运行期值，不改源码） |

**我的建议：先量基线，再决定做 F1 还是只做 F2。**
