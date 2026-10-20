# MEASURED-FACTS —— 实测数据的唯一存放处

> 原则：**凡是"我记得是…"的数字，都必须在这里有出处**。没有出处的数字不许引用。
> 生成：2026-10-20。新增实测请追加到对应小节。

---

## 1. 计时精度（本机 Win11 / JDK 25.0.2，2026-10-20 实测）

### 1.1 1ms 间隔，各 300 次（超时 = 实际 - 目标）

| 机制 | 平均 | p50 | 最大 |
|---|---|---|---|
| `Thread.sleep(1ms)` | 1.71 ms | 2.00 | 3.02 |
| `LockSupport.parkNanos(1ms)` | **1.16 ms** | 1.00 | 2.52 |
| `javax.swing.Timer(1ms)` | 2.12 ms | 2.00 | **99.58** |

### 1.2 8ms 间隔（= 我们的 ACTIVE_DELAY_MS），各 150 次

| 机制 | 平均 | p50 | p99 |
|---|---|---|---|
| `sleep(8ms)` | 0.47 ms | 0.29 | 2.01 |
| `parkNanos(8ms)` | **0.26 ms** | 0.04 | 1.36 |
| `Timer(8ms)` | 0.75 ms | 0.71 | 2.14 |

### 1.3 结论

1. 上游 README 的 `parkNanos = 15.35ms ❌` **在本机不成立**（它比 sleep 更准）；
2. `Timer` 也没有被钉在 64fps（8.75ms ≈ 114fps）；15.6ms 是**系统时间分辨率**，取决于是否有进程调过 `timeBeginPeriod(1)`（IDEA 自己会调）；
3. **该记的真正理由**：`Timer` 的任务跑在 **EDT** 上，与绘制抢同一个线程 —— 不是精度问题；
4. 我们用的 `Thread.sleep` 在 8ms 档位够用（p99 超时 2ms）。

---

## 2. 代码规模快照（2026-10-20）

| 文件 | 行数 | 备注 |
|---|---|---|
| `ui/settings/NeovideCursorConfigurable.java` | **1,594** | 54 个方法；`apply()` 从 1093 行起，内含 **18 段逐字段脏合并** |
| `ui/CaretAnimator.java` | 1,458 | 单光标假设在 `:865` / `:917` |
| `ui/NeovideCaretManager.java` | 900 | 运行期快照 `CONFIG` |
| `settings/NeovideCursorSettings.java` | 719 | 持久化 + 双向同步 |
| `core/NeovideConfig.java` | 290 | **I-1 零 diff** |
| `settings/CursorPresets.java` | 215 | 7 个动画预设 + 5 个颜色预设 |
| `ui/RenderScheduler.java` | 186 | 一条线程；每 animator 各自 invokeLater |

---

## 3. 探针资产（回归护栏）

| 资产 | 数量 | 备注 |
|---|---|---|
| `run-t9-full.ps1` | 11 段 | 一键全量 |
| V-PROBES | 119 断言 | 契约 V1–V15 |
| settings-v2 | 68 | 布局/预设/吸附 |
| t7 | 24 | R-01/R-02/R-03 |
| IME 几何 | 29 | 边界 |
| audit | 19 项 + D 段 | 观感常量 |
| parity | 3 份逐字节 | 物理/几何/轨迹 |
| 变异 | 6 个（真实值 12/11/3/4/1/2） | 探针有效性 |
| 探针文件引用 | **197 处 / 15 个 java 文件** | 重构时必须先解耦（NEXT-REFACTOR Phase 0） |

---

## 4. 上游断言核查（中立）

| 上游断言 | 核查结果 |
|---|---|
| `Timer` 15.61ms ❌ / `parkNanos` 15.35ms ❌ / `sleep` 1.56ms ✅ | **结论对、理由错**（见 §1） |
| `repaint` 105.6fps vs `paintImmediately` 36.9fps | 逻辑可信，**离线无法复现**（需真机），记为"采信未验" |
| Lesson #19：隐藏原生光标 = 体验上限低 | ⚠️ **与我们默认相反**（`hideNativeCaret=true`、`alwaysHighFps=true`），解释了 A + D 同时出现；改默认需用户拍板 |
| Lesson #20：低帧率补偿会抹平四角梯度 | 与我们的 A 组同源；我们改**总时长**不碰梯度，不冲突 |
| Lesson #21：fps 计数器把空闲算进分母 | **要抄**：我们自己的帧率数据也很久没重跑 |

---

## 5. 优化方向 backlog（有入口、有收益、有风险）

| # | 方向 | 入口 | 预期收益 | 风险 | 需真机 |
|---|---|---|---|---|---|
| O-1 | 空闲不交脏区（不再每帧 `repaint`） | `CaretAnimator.computeNextDelay()` | 空闲 CPU / EDG 压力 | 低 | 否 |
| O-2 | 隐藏标签页降频（现在只是不画、仍 8ms 跑） | `CaretAnimator.computeNextDelay()` `:696` | 多标签页时 CPU | 低 | 是 |
| O-3 | 辉光 padding 早退（`:1007` 已有一处早退挡住重算） | `refreshGlowPadding()` | 每帧少一次全层重算 | 中 | 是 |
| O-4 | 预设匹配 O(n) → 预计算指纹 | `CursorPresetApplier.matchAnimPreset` | 面板打开/拖动时的开销 | 低 | 否 |
| O-5 | `deepCopy` 频率（`apply()` 里两次全量拷贝） | `NeovideCursorConfigurable` A6/A7 | 每次 apply 少两次 19 字段拷贝 | 低 | 否 |
| O-6 | 面板类拆分（1,594 → 3 类） | NEXT-REFACTOR Phase 2 | 编译/认知成本 | 中 | 否 |
| O-7 | 多光标（SpringTrail） | NEXT-REFACTOR / UPSTREAM-REVIEW | **功能**（上游唯一比我们强） | 中高 | 是 |
| O-8 | 行缎带累积物理（欠阻尼） | `DampedSpring` | **功能**（用户最想要） | 高 | 是 |
| O-9 | 流光 RGB / 辉光双色 | `CaretTrailPanel.paintQuad()` `:227` | **功能**（用户要，后期） | 高（增加绘制） | 是 |
| O-10 | 残影模式插值（打字时不跳） | `CaretAnimator.tickAfterimage()` | 手感 | 中 | 是 |
| O-11 | `BUSY_RETRY_MS 1 → 0` 是否安全 | `RenderScheduler:55` | 极少见的 1ms 退避 | 低 | 否 |
| O-12 | 帧率数据重跑（用修好的计数法） | 诊断开关 | 结论可信度 | 低 | 是 |

**排序建议**：O-1/O-2/O-4/O-5（低风险、无需真机）可以夹在里程碑之间做；O-7/O-8 按 ROADMAP；O-9 等用户明确要求。

---

## 6. 未验证清单（诚实记录，别当已验证）

| 项 | 状态 |
|---|---|
| 面板真机渲染效果 | 未验证（只有用户肉眼看） |
| EDT 竞争下的手感 | 未验证 |
| 主题切换后光标颜色 | 未验证 |
| 安装后插件加载 | 已验证一次（0.9.5 能开） |
| 帧率（125fps 声称） | **数据过期**（计数法有 bug，需按 O-12 重跑） |
| 多光标 | 未实现 |
