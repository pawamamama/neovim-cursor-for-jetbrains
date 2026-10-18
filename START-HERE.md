# 新会话入口 —— 请先读这一页

> 这是一份**给全新 AI 会话的交接**。读完这一页你就知道全部现状。
> 详细内容在 `HANDOFF-FINAL.md`，遇到具体问题时再查。

---

## 0. 一句话现状

**插件已能正常使用，用户表示「常驻刷新已经非常好用了」。**
**下一步是「设置界面」，让用户自己调参，不要再一轮轮试。**

---

## 1. 项目是什么

**IntelliJ IDEA 插件**：`com.github.yyyolo7a79.neovidecursor`
Neovide 风格的「拖尾光标」——光标移动时留下被拉伸的渐隐残影 + 辉光。

- 用户仓库：`D:\deepseekwork\neovim-cursor-pr`
- 当前分支：**`feat/settings-ui-ready`**
- 当前提交：**`74d3a8a`**（已推送到 `pawamamama/neovim-cursor-for-jetbrains`）
- 上游：`yyyyolo7a79-sketch/neovim-cursor-for-jetbrains`（作者已说「燃尽了」）

---

## 2. 当前安装的版本（权威）

```
v0.9.0-ahf-default
哈希: FA161E4F77C8C999
大小: 39937 B
```

**安装路径**：
```
C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.5.0.jar
```

**Tools 菜单（4 项）**：
```
Neovide 拖尾光标（开关）
Neovide 光标：性能诊断（禁用重绘）
Neovide 光标：残影模式（低帧率更稳）
Neovide 光标：隐藏原生光标（常驻绘制）    ← 默认不勾
```

**关键配置**：
| 参数 | 值 | 说明 |
|---|---|---|
| `alwaysHighFps` | **true** | **常驻刷新默认开启，无菜单项** |
| `hideNativeCaret` | **false** | 保留原生光标（默认）|
| `glowLayers` | **8** | |
| `ACTIVE_DELAY_MS` | 8ms | |
| `shortAnimationLength` | 0.11 | 单行动画时长 |
| `animationLength` | 0.10 | 跨行动画时长 |
| `trailFactors` | {1.0, 0.72, 0.42, 0.22} | 四角梯度，4.5 倍 |

---

## 3. 回退（随时可用）

```powershell
# 回 0.8.0
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-080.ps1"

# 回 0.7.1
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-071.ps1"

# 回 uni（用户最认可的原版基线）
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-uni.ps1"
```

**所有 40 个 jar 在 `dist\`，完整对照表见 `VERSIONS.md`。**

---

## 4. 核心结论（省得你重新排查）

### 插件本身不慢

| 指标 | 实测 |
|---|---|
| 12 层辉光绘制 | **0.05ms/帧**（8ms 预算的 0.6%）|
| 完整 Swing paint | 0.09ms/帧（1.1%）|
| 插件占 EDT 采样 | **3.26%**（45 分钟 JFR）|

### 真正瓶颈：EDT 抢占

插件日志（`delay=8ms` = 调度器正常）：
```
maxGap=358ms   peakRepaintArea=5494px
maxGap=399ms   peakRepaintArea=4958px
```
**投递出去的任务在 EDT 队列里排了 120~400ms。**

### 已用实测排除的方向（别再验证）

| 假设 | 排除依据 |
|---|---|
| 绘制慢 | 0.05ms/帧 |
| 重绘面积大 | **面积大 67 倍反而快 15 倍** |
| GC | 最长 15ms vs 卡顿 900ms+ |
| GPU | JBR 25 无 D3D/OpenGL 管线；光标仅 64px |
| 插件占 EDT | 仅 3.26% |
| **IDEA 自身绘制** | `ScaledBlit.Scale` 占 EDT **12.6%** ← 大头 |

---

## 5. 下一步任务：设置界面（用户明确要求）

### 目标

`Settings → Editor → Neovide Cursor` 出现一个面板，用户拖动滑块实时看效果，参数自动保存。

### 需要 3 件事

**1. `NeovideConfig` 实现 `PersistentStateComponent`**

现在是纯内存单例，**重启即丢**。需要：
```java
public class NeovideConfig implements PersistentStateComponent<NeovideConfig> {
    @Override public NeovideConfig getState() { return this; }
    @Override public void loadState(NeovideConfig state) { ... }
}
```
并在 `plugin.xml` 注册 `applicationService`。

**2. 新建 `NeovideConfigurable`**

用 `Configurable` 接口 + `FormBuilder` 做滑块/颜色选择器。

**3. `plugin.xml` 注册**
```xml
<applicationConfigurable parentId="editor"
    instance="...NeovideConfigurable"
    id="neovide.cursor.settings"
    displayName="Neovide Cursor"/>
```

### 风险：**中等**

`NeovideConfig` 是全局静态单例，所有读取方都受影响。**但这是 IntelliJ 标准做法。**

**必须先备份当前 jar**（已在 `dist\` 有全部版本）。

### 调参对照表（做 UI 时用）

| 用户感受 | 参数 | 当前 | 范围 |
|---|---|---|---|
| 单行拖尾太长 | `shortAnimationLength` | 0.11 | 0.05~0.15 |
| **拖尾不够明显** | `trailFactors[3]`（前缘）| 0.22 | 0.15~0.35 |
| 拖尾太飘 | `trailFactors[0]`（后缘）| 1.0 | 0.7~1.0 |
| 跨行拖尾长度 | `animationLength` | 0.10 | 0.08~0.15 |
| 辉光浓度 | `glowLayers` / `glowOpacity` | 8 / 0.55 | 6~12 / 0.3~0.7 |
| 光标粗细 | `cursorWidth` | 2 | 2~3 |
| 帧率 | `ACTIVE_DELAY_MS` | 8 | **12**（本用户最佳）|

---

## 6. 其他可选优化（优先级递减）

| 优先级 | 内容 | 收益 |
|---|---|---|
| P0 | **全局 `RenderScheduler`** | JFR 显示 `startScheduler` 占插件采样 **90%** 空转。上游 v0.5.0 已实现（111 行），能把 EDT 投递从「N 编辑器 × 125 次/秒」降到 125 次/秒 |
| P2 | `maxTrailDistanceFactor` 60 → 4 | 仅跨屏跳转的重绘面积（踩坑 #6/#11）|
| P3 | 残影辉光方法 | `CaretTrailPanel:395-403` 用「居中描边」，是踩坑 #12 否定的做法。仅影响残影模式 |
| P4 | fps 统计补 `FPS_IDLE_GAP_NANOS` | 仅诊断精度 |

---

## 7. ⚠️ 我这一路犯的 8 次错（**别重犯**）

| 改动 | 后果 |
|---|---|
| `ACTIVE_DELAY_MS` 12 → 8ms | 一切都变快，用户说「傻快」 |
| 删掉 `MIN_ANIMATION_FRAMES` | 低帧率下动画消失 |
| 硬编码 16px 短跳阈值 | 中文卡（字号 20px 中文宽 20px）|
| `FontMetrics('m') × 2.5` | 换个魔数位置，仍不准 |
| 第0列→第1列测宽 | **取行首字符宽度**，中文区仍翻倍 |
| `recovery` 分支 `dt = rawDt` | **不开常驻刷新一帧走完位移 = 卡爆** |
| **分步推进（substep）** | **审计证明是「正确的空操作」，且更糟 38~46pp** |
| `hideNativeCaret` 改成 true | 违反上游踩坑 #19 |

### 分步推进为什么无效（**最重要的一条，别再试**）

```
一次 update(0.12)      = 6.828984590243152
分15次 update(0.008)   = 6.828984590243153
差 = 8.88e-16  ← 完全等价
```

**而 `updateCorners()` / `submitRepaint()` 在 steps 循环之外 —— 一次 tick 只画一帧，中间状态根本不渲染。**

**「15 步塞进 1 帧」= 「1 步推进」，对用户完全一样。**

---

## 8. 工作纪律（血的教训）

**用户原话：「我不知道你改的哪版好 你也没做版本管理 气死我了」**

1. **改前先查 `VERSIONS.md` 和 `UPSTREAM-PITFALLS.md`**
2. **一次只改一个变量**
3. **改完立刻构建 + 让用户重启验证**
4. **不合意立刻回退**（回退脚本必须提前准备好）
5. **每次构建追加到 `VERSIONS.md`**
6. **不要凭直觉调参数** —— 这一路的失败全部源于此

**用户会毫不客气地纠正你，而且通常是对的**（他推翻了「分步推进」、指出「常驻刷新下中文也丝滑」从而否定 IME 假设）。

---

## 9. 文档索引

| 文件 | 什么时候读 |
|---|---|
| **本文件** | 开局必读 |
| `HANDOFF-FINAL.md` | 需要完整背景、给上游的话、离线构建方法 |
| `VERSIONS.md` | 想知道某版改了什么 / 找可用的 jar |
| `UPSTREAM-PITFALLS.md` | 改代码前对照上游 21 条结论（672 行，可 grep `STATUS=VIOLATED`）|
| `FINAL-CONCLUSION.md` | 想看结论总结 |
| `BUGS-FOUND.md` | 已确认的真 bug |
| `UPSTREAM-ISSUE.md` | 已提交的 issue #3 全文 |

**上游 Issue 已提交**：
```
https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/issues/3
```

---

## 10. 构建方法（本机无可用 Gradle）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1" `
  -Version 0.9.1 `
  -OutName "my.jar" `
  -BaseJar "D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.4.8-fullfix.jar"
```

**安装时必须：**
1. **完全退出 IDEA**（它内存映射锁住 jar）
2. **先 `Move-Item` 旧的，再 `Copy-Item` 新的**（直接覆盖会报「用户映射区域」错误）

---

## 11. 一句话给新会话

**插件能用了，别乱改参数。用户的诉求是「设置界面」，让他自己调。
如果必须改代码，先查 `UPSTREAM-PITFALLS.md`，一次只改一个变量，准备好回滚。**
