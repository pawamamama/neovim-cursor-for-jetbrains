# 明天从这里继续（**已过期，本文停在 0.9.57**）

> ⚠️ **本文写于 2026-10-24 夜，那个「明天」早已过去。当前状态看
> [`START-HERE.md`](../START-HERE.md) 或 [`NEXT-STEPS.md`](NEXT-STEPS.md)。**
> 其中提到的版本号、落后版本数、「161 个探针」等数字全部过时。
>
> 2026-10-24 夜完成，2026-10-25 早读这份。

---

## 昨晚做了什么（0.9.57，已装）

| # | 任务 | 位置 |
|---|---|---|
| T1 | 光标宽度 0–5px | 设置 → 总开关 → 「光标宽度」 |
| T2 | 光标高度 30–100% | 设置 → 总开关 → 「光标高度」 |
| T3 | RGB 纵向渐变（实验） | `-Dneovide.cursor.rgb=true` |

**三个都默认不改变原行为**：宽度 0=跟 IDEA、高度 100%=顶格、RGB 关。

---

## 今天要做的

### 1. 先验收昨晚三项

```
打开 IDEA，测试：
  1. 光标宽度拉到 5px → 变粗了吗？
  2. 光标高度拉到 30% → 变短了吗？
  3. 静止时（不动）也保持这个大小吗？  ← 关键
  4. RGB：加 -Dneovide.cursor.rgb=true 重启，看有无纵向渐变
```

### 2. RGB 可调版（你说「明天带你做」）

需要你决定：
```
· 起止色怎么配？（两个 ColorPanel？还是预设几套？）
· 渐变方向：纵向 / 横向 / 沿拖尾年龄
· 要不要跟拖尾年龄联动（尾端更淡）
```

---

## 待办全貌

| # | 事项 | 备注 |
|---|---|---|
| 1 | GitHub 发布追平 | **线上 v0.9.33 / 本地 0.9.57**，落后 24 版 |
| 2 | RGB 可调版 | 今天带做 |
| 3 | 块选区 `charsMoved` 拆参数 | 技术债 |

---

## 调研结论（已存档 docs/design/RGB-CURSORSIZE-RESEARCH.md）

**纯平滑输入为什么不倾斜**：
```
k=2.0 → trailFactors [1.00, 0.82, 0.63, 0.50] → 最大/最小 2.0×
对比基础 k=4.5 → [1.00, 0.72, 0.42, 0.22] → 4.5×
四角时长差小 → 矩形不变形 → 不斜
```
**不是 bug，是参数。**

**平滑输入是自创的**：
```
公式 f'[i] = f[i] + (1-f[i]) × smooth × 0.8
代码注释自称「本插件早期自创的」，Neovim 原版没有
```

---

## 快速命令

```powershell
# 读诊断日志（我直接读盘，不用你复制）
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\read-diag.ps1

# 跑门禁
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-t9-full.ps1

# 构建+安装
pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\build\i57.ps1
```
