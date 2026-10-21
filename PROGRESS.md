> ⚠️ **最新状态：用户关机中断（2026-10-21）**
> WIP 已提交：`810d3be`（+546 行，0.9.8 预设覆盖值**未验证**，没出 jar，没跑过 t9）。
> 接手第一步：跑 `run-t9-full.ps1` 判断绿不绿 → 不绿就按 `0.9.8-TASKS.md` 的 8 处清单补。

# PROGRESS —— 当前指针

> 更新：2026-10-21（关机中断）

## 1. 我在哪

| 项 | 值 |
|---|---|
| 已装（用户机器） | `0.9.7-pawamamama`（98,878 B / `DF6E6097`） |
| 代码 HEAD | `810d3be`（WIP，未验证） |
| 未完成 | 0.9.8 预设覆盖值持久化（8 处已改 7 处？待跑 t9 判定） |

## 2. 恢复顺序（照做就行）

```powershell
# 1) 先判断当前 WIP 是否可用
& D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-t9-full.ps1
# 2) 绿 → 出 jar
& D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1 -Version 0.9.8 `
    -OutName jetbrains-neovide-cursor-0.9.8-preset-memory.jar `
    -BaseJar D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.7-pawamamama.jar
# 3) 红 → 对照 0.9.8-TASKS.md 的 8 处清单，缺哪补哪
```

## 3. 之后只剩两件事（用户已定）

1. 手感基线：叠加模式静止不画本体 + 短跳 0.08；
2. 发布物料：README（中/英）+ 15 秒演示 GIF + B 站脚本。

**功能已冻结**；重构为触发式（见 `NEXT-REFACTOR.md` §12）。
