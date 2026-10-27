## v0.9.33 —— 多光标 + IME 探针全绿

> 基于 v0.9.32，补上了 IME 探针的 29 项全过。

## 与 v0.9.32 的差异

只改了测试，**产品代码一字未动**：

| 断言 | 之前 | 现在 |
|---|---|---|
| IME 探针 | 23/29（2 项红，脚本改成警告绕过） | **29/29 全过，恢复硬性门禁** |

### 那 2 项为什么一直是红的

**都是探针自身的基线没对齐，不是产品缺陷：**

- **A2**：基线 `lastColumn=3`，A2 又没改 `vpColumn` → 快速路径抢跑 `return false`，走不到 `forceResync` 判断
- **A5**：循环里 `vpColumn` 恒定 → 从第 2 帧起快速路径就返回 → `imeSkipFrames` 累加不到 15

修好后 A5 的数据：30 帧内拦截 29 次、放行 1 次、**首次放行正好在第 16 帧**（= `IME_MAX_SKIP_FRAMES + 1`）。

## 功能

与 v0.9.32 相同：多光标 + 块选区 + 诊断日志。详见 [v0.9.32](https://github.com/pawamamama/neovim-cursor-for-jetbrains/releases/tag/v0.9.32)。

## 验证

```
门禁     12 段全绿
ime      29 项全过
parity   ALL PARITY OK
D0/D0b   exit=0
```