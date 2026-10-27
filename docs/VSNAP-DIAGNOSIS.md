# VSnap 诊断报告 —— 10 条红的根因

> 2026-10-27。**本文只诊断，不改断言、不改代码。**
> 结论先说：**10 条红里有 8 条是探针的索引过期，1 条是量程表过期，
> 1 条指向真实的产品行为（就近取整未实现），但那条属于观感判断，需用户拍板。**

---

## 一、先说结论

| 分类 | 条数 | 断言 | 性质 |
|---|---|---|---|
| 探针用 `textFields.get(0)` 拿错了控件 | 7 | TYP-1/3/4、ILL-2/3/5/7 | **断言过期** |
| 探针假设「第 0 个 = 淡出前保持时长」已失效 | 1 | BND-7 | **断言过期** |
| 量程表硬编码「step>1 恒为 7 个」 | 1 | SNAP-1 | **断言过期** |
| 就近取整未实现（65 → 60 而非 65） | 1 | cursorHeightRatio 那条 | **真实行为**，需用户定 |

**都不是产品功能回归。** 用户看到的设置面板是好的。

---

## 二、根因：面板滑块顺序变了，探针还在用索引 0

`VSnap` 里到处是 `ui.textFields.get(0)` / `ui.sliders.get(0)`，
它假设「第 0 个 = `cursorDisappearDelay`（淡出前保持时长，单位 ms）」。

实测（`VUi.nameOf(i)`，2026-10-27）：

```
索引  字段名                    量程/步长
  0   shortAnimationLength      [2, 25] 步长 1    ← 单位「秒」
  1   typingSmoothness          [0, 100] 步长 5
  2   stretchRatioK             [10, 80] 步长 5
  3   slopeTicks                [0, 100] 步长 5
  4   trailSize                 [0, 100] 步长 5
  5   animationLength           [5, 35] 步长 1
  6   tailOpacity               [0, 100] 步长 5
  7   cursorWidthPx             [1, 5] 步长 1
  8   cursorHeightRatio         [30, 100] 步长 10
  9   glowOpacity               [0, 100] 步长 5
 10   glowLayers                [1, 12] 步长 1
 11   shadowBlurFactor          [0, 100] 步长 1
 12   cursorDisappearDelay      [0, 500] 步长 50  ← 探针以为它在 0
 13   cursorFadeOutDuration     [0, 1000] 步长 25
```

**`cursorDisappearDelay` 从第 0 位掉到了第 12 位。**

于是探针键入 `333` 时：

```
探针以为：写进 cursorDisappearDelay（ms，量程 0~500）→ 期望标签 "333 ms"
实际：    写进 shortAnimationLength（秒，量程 2~25）
         333 × /100 → 3.33 秒
         标签渲染成 "3.33 s"
         滑块被 clamp 到上限 25
```

这解释了全部 7 条 TYP/ILL 的观测值：`slider=25`（短动画时长上限）、
`label="3.33 s"`、`state=50`（cursorDisappearDelay 根本没被碰，还是初值 50）、
`tf="25"`（失焦 clamp 到 25）。

**产品行为是正确的** —— 333 秒超出该滑块量程，clamp 到上限本来就对。

---

## 三、BND-7 是同一个根因的另一种表现

```
FAIL  BND-7 键入 999 失焦后 clamp 到 0.35 并落库   [9.99 (raw=0x411fd70a)]
```

它期望的是 `animationLength`（秒，上限 0.35），但实测落库 9.99 ——
因为第 0 个文本框是 `shortAnimationLength`，量程不同。

这条其实和上面 7 条是**同一个根因**（索引错位），只是它没有用 `get(0)` 直接读，
而是通过 state 断言，所以单独列出来了。

---

## 四、SNAP-1 是量程表过期

```
FAIL  SNAP-1 全部可测滑块（step>1）的 setValue(非刻度) 都落到最近刻度
     [8/9（契约里 step>1 恒为 7 个）]
```

注释原文（`VSnap.java`）：

> 契约里步长 >1 的滑块恒为 7 个：50/25/5/5/5/5/5 那七个

实测现在是 **9 个**（新增了 `cursorWidthPx` 步长 1 不算，但有 `slopeTicks`、
`cursorHeightRatio` 等）。**这不是功能问题** —— 8/9 里那 8 个都正确吸附了，
唯一"失败"的那个是 `cursorHeightRatio`（见下条）。断言写的「恒为 7」过期了。

## 五、cursorHeightRatio：这是真实行为，需要用户定

```
FAIL cursorHeightRatio  量程=[30,100] step=10 面板=[30,100]
                        请求 65 → 落位 60（网格=true 最近=true 就近取整=false）
```

请求 65，落到 60。65 距 60 和 70 都是 5，**并列时需要「就近取整」决定往哪边靠**，
而当前实现返回 `就近取整=false`（即不做就近取整，JSlider 默认向下）。

**这是真实的产品行为**：用户把光标高度设成 65%，实际会得到 60%。
这算不算 bug？
- 如果用户认为「滑块就该吸附到整十」→ 现在是对的，只需改探针期望
- 如果用户认为「我设 65 就该是 65」→ 需要改 `snapToTick` 的就近取整逻辑

**按 R1（不改观感）与纪律四-I（语义不清晰先问），这条我不动，交给用户决定。**

---

## 六、如果要修，怎么修（供参考，本轮未执行）

### 6.1 TYP/ILL/BND-7 这 8 条 —— 改断言方向

把 `ui.textFields.get(0)` 换成**按 key 查找**。`VUi` 已经提供了现成方法：

```java
// tools/settings-probe/VUi.java:268  —— 按字段 key 找滑块
public JSlider sliderByKey(String key)
// tools/settings-probe/VUi.java:275  —— 按滑块找同一行的输入框
public JTextComponent textFieldOf(JSlider slider)
```

用法：

```java
JSlider sl = ui.sliderByKey("cursorDisappearDelay");
JTextComponent tf = ui.textFieldOf(sl);
int idx = ui.sliders.indexOf(sl);          // 要拿 valueLabels 时用这个下标
```

这样面板顺序再变也不会红。这是「改方向不删断言」：
断言仍然严格（键入 333 → 期望 333），只是**不再假设控件位置**。

### 6.2 SNAP-1 —— 改断言方向

把「恒为 7 个」改成「与 `VUi.SPEC` 里 step>1 的条数同源」，
不再写死数字。理由同 `VDefects` 那次：**钉死当时的真实数量，下次加滑块必红**。

### 6.3 cursorHeightRatio —— 等用户拍板

见上。要么改探针期望（接受 60），要么改 `snapToTick` 实现就近取整（用户设 65 得 65）。

---

## 七、本轮做了什么、没做什么

| 项 | 状态 |
|---|---|
| 诊断根因 | ✅ 已查清并给出实测数据 |
| 改断言 | ❌ 本轮不做（任务书要求「只诊断」） |
| 改产品代码 | ❌ 未动一个字节 |
| `VDefects` 的 V11-3b | 这是同源问题，已随本节诊断归入，未单独修 |
