# ASSERTIONS —— 本地断言（机器可判，防遗忘）

> 每条都给**可直接复制的命令**和**期望结果**。跑不过就是没过，不许"看着没问题"。
> 断言变更必须同步改这里 —— 这份文件就是"怕忘"的载体。

---

## 0. 铁律断言（每次提交前必跑）

| # | 断言 | 命令 | 期望 |
|---|---|---|---|
| A0-1 | `NeovideConfig` 零 diff | `git -C D:\deepseekwork\neovim-cursor-pr diff --stat -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java` | **空输出** |
| A0-2 | 无 `<separator>` | `Select-String -LiteralPath D:\deepseekwork\neovim-cursor-pr\src\main\resources\META-INF\plugin.xml -Pattern separator` | **无匹配**（有匹配就有启动崩溃风险） |
| A0-3 | 没碰 Gradle | 历史命令里没有 `gradle` | 无 |
| A0-4 | 没安装、没重启 IDEA | 没有落盘到 IDEA 插件目录 / 没重启 | 无 |

---

## 1. 全量探针（一条命令，11 段）

```powershell
& 'D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-t9-full.ps1'
```

**期望**：11 段全绿。任何一段红 → **停下**，不许进入下一步。

| 段 | 内容 | 期望数量 |
|---|---|---|
| 1 | parity（物理/几何/轨迹逐字节） | 3 份完全一致 |
| 2 | audit（观感常量默认值） | 19 项 + D 段 |

> **audit 的 `DIFF 数 = 1` 是既有的、有意的**：`hideNativeCaret` 在批次2 被改成默认 `true`，
> 审计表里登记为允许 `(true|false)`（`run-audit.ps1:45`）。**它不是回归**，exit code 仍是 0。
> 2026-10-20 已查过一遍，别为这 1 个 DIFF 再查第二次。
| 3 | IME 几何边界 | 29 |
| 4 | V-PROBES（契约 V1–V15） | 119 |

> `t4-verify` 段（不在 11 段之内的独立段）目前 **24 条**：P/V 系列 + U1–U18 + M2 新增 U19–U24（Tools 同步显示层）。
| 5 | settings-v2（布局/预设/吸附 + M4 S-1..S-7 + M5-1..M5-11 + M6-1..M6-7） | **93** |
| 6 | 4bugs（4 个原始缺陷） | 全绿 |
| 7 | t7（R-01/R-02/R-03） | 24 |
| 8 | 变异测试有效性 | 6 个变异；真实值 12/11/3/4/1/2 |
| 9 | plugin.xml 安全检查 | 7/7 |
| 10 | 构建 | jar 生成 + 字节数 |
| 11 | 汇总 | 全绿计数 |

> 数量对不上 = **探针本身变了**，要解释清楚再继续（假 FAIL 和假 PASS 都是探针缺陷）。

---

## 2. 单项命令（排查用）

| 用途 | 命令 |
|---|---|
| parity | `& 'D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1'` |
| audit | `& 'D:\deepseekwork\neovim-cursor-pr\tools\parity\run-audit.ps1'` |
| IME | `& 'D:\deepseekwork\neovim-cursor-pr\tools\ime-probe\run-ime-geom-test.ps1'` |
| settings-v2 | `& 'D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-settings-v2-probe.ps1'` |
| 4bugs | `& 'D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-4bugs-probe.ps1'` |
| t7 | `& 'D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-t7-probe.ps1'` |
| 变异 | `& 'D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-v-mutations.ps1'` |
| XML 安全 | `& 'D:\deepseekwork\neovim-cursor-pr\tools\check-plugin-xml.ps1'` |
| 构建 | `& 'D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1'` |

---

## 3. 假绿防范（最容易被自己骗的三条）

| # | 陷阱 | 防法 |
|---|---|---|
| P1 | 探针改了却"还是绿" | 跑变异测试：6 个变异必须**变红**，否则探针已失效 |
| P2 | `Select-String -Path <目录> -Include *.java` **静默 0 命中** | 一律用 `Get-ChildItem -Recurse -Filter *.java` 管道，或 grep 工具 |
| P3 | jar 整体 SHA256 不稳定（zip 时间戳） | 用**字节数 + class 指纹**判断 |

---

## 4. 禁止清单（已踩过的坑）

| 禁止 | 原因 |
|---|---|
| `Remove-Item $home` 之类 | `$home` 是只读自动变量（曾解析成 `C:\Users\wangw`，沙箱拦住） |
| `plugin.xml` 里写 `<separator>` | `separator` 不是 action 对象 → **IDEA 启动 NPE 崩溃**（已发生） |
| 用 `javax.swing.Timer` 做帧调度 | 跑在 EDT 上，和绘制抢线程 |
| 用 `paintImmediately` 替代 `repaint` | 同步刷所有脏区，删字时阻塞 EDT（上游 105.6 → 36.9fps） |
| 改弹簧衰减系数 `c` 来补偿低帧率 | `c` 参与速度积分，改大会**放大**位移（上游 #4） |
| 批量 tick 多个 animator | 契约 P0-1 禁止：改 EDT 任务粒度 + 队头阻塞 |
| 引用别人的性能断言不复现 | 上游 15.6ms 表在本机不成立（见 MEASURED-FACTS §1） |

---

## 5. 每个里程碑必须产出的 5 件

| # | 产出 |
|---|---|
| 1 | jar（`build\` 下带版本号） |
| 2 | 字节数 + SHA256 前 8 位（写进 `VERSIONS.md` 一行） |
| 3 | `build\rollback-<版本>.ps1` |
| 4 | 探针报告（`build\` 下，含每段绿/红） |
| 5 | Conventional Commit |
