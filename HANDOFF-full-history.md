# Neovide Cursor 插件改造 —— 交接文档

> 写给下一个接手的会话。读完这份 + `AGENTS.md`，就能继续开发，不必回看历史对话。
>
> 最后更新：2026-10-16

---

## 一、这是什么

用户在 IntelliJ IDEA 里用的第三方插件 **Neovide Cursor**（作者 yyyolo7a79），
给编辑器加了 Neon/Neovide 风格的"拖尾光标"。

- 上游：`https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains`
- 用户 fork：`https://github.com/pawamamama/neovim-cursor-for-jetbrains`
- **已提的 PR：** [#1](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/pull/1)
  标题「fix(scheduling): 从空闲档唤醒时正确重置帧间隔估计量」，2 文件 +53/-4，`clean`

用户的真实诉求（按时间演进）：

1. 最初想找一个"丝滑光标"插件 → 发现官方"平滑光标"（Snappy/Gliding）不够
2. 在 B 站看到 dsh-claude-style 的输入框光标动效 → 想复刻到 IDEA
3. 装了 Neovide Cursor 后**体验很好**，但发现三个问题：
   - 切走再切回界面，动光标**卡约一秒**才恢复满帧
   - **退格特别卡**，且"直接消失"不丝滑
   - **跨行跳转卡**
4. 用户怀疑缺少"短光标变粗光标的动画"

---

## 二、当前状态（重要）

### 已安装的插件

```
C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\
    jetbrains-neovide-cursor-0.4.3.jar        36527 B   ← 当前生效：0.4.4「第二次修复版」
    jetbrains-neovide-cursor-0.4.3.jar.orig   34825 B   ← 官方原版备份（**随时可切回**）
```

**当前生效版本只含两个调度修复**，明确不含：
平滑档位 / `snapWhileDeleting` / 闪烁 / 跟随主题色。

### 仓库与分支

```
D:\deepseekwork\neovim-cursor-pr          ← 工作副本（基于上游 main = ee378a2）

分支 fix/adaptive-delay-reset-on-wakeup  = 9739ae5  ← **已推送，对应当前 PR**
     9739ae5  fix(scheduling): 仅在空闲档唤醒时重置帧间隔估计量
     9d82894  fix(scheduling): 从空闲档唤醒时一并重置帧间隔估计量
     ee378a2  (上游 main)
```

远端分支已被 force-push 成这个干净版本，**本地与远端一致**。

### 手头的构建产物（`neovim-cursor-pr/dist/`）

| 文件 | 说明 |
|---|---|
| `jetbrains-neovide-cursor-0.4.4-rollback.jar` | **当前装的**，只含两个调度修复 |
| `jetbrains-neovide-cursor-0.4.3.jar.orig`（在插件目录） | 官方原版，对照用 |
| `jetbrains-neovide-cursor-0.5.0-smooth.jar` | **含平滑档位**（已从 PR 撤下，代码仍在 `9b615df`）|
| `jetbrains-neovide-cursor-0.5.1.jar` | 含平滑档位+删除吸附+闪烁（已撤下） |
| `0001~0003-*.patch`、`fix.diff`、`full-changes.diff` | 各阶段补丁 |

---

## 三、关键发现（**别再重复走的弯路**）

### 1. 退格卡顿**大概率不是插件的锅** —— 有硬证据

IDEA 日志里插件自己每秒打印一行统计（`CaretAnimator.recordFrameStats`）：

```
neovide-cursor: fps=0     peakRepaintArea=1876px  delay=8ms
neovide-cursor: fps=1.7   peakRepaintArea=1876px  delay=40ms
neovide-cursor: fps=97.3  peakRepaintArea=1876px  delay=8ms
```

- **重绘面积中位数仅 1876px**（约 43×43 像素，就是光标+光晕）→ **绘制轻到可忽略**
- **fps 在 2.1 ↔ 97.3 之间秒级跳变**，同一份代码、同样面积 → 是 EDT **可用时间片**在变
- 每个低 fps 时刻都伴随 `ProgressManagerImpl - checkCancelled is invoked while write-action is pending`
  （**不是插件产生的日志**）
- 统计：58 条记录里 `fps<10` 占 **25 条（43%）**，`>=45` 只有 14 条

**结论：插件在成片地拿不到 EDT 时间片，而不是画得慢。**

⚠️ **千万不要**因为"卡"就去削弱插件的缓动（例如让它删除时跳过动画）——
已试过（`snapWhileDeleting`），**既没解决卡顿，又让删除变成"直接消失"**，用户明确否定。已回退。

### 2. 用户尚未回答的关键问题

**"原版退格也卡"里的「原版」指哪个？**
1. 关掉 Neovide Cursor 后的 IDEA **原生光标**
2. 还是官方「平滑光标」（Snappy/Gliding）

- 若是 **1** → 卡顿与插件无关，应转去查 EDT 占用者（用户已装 Performance Diagnostics）
- 若是 **2** → 可能是两个动画叠加，需让插件与官方平滑光标二选一

**这是下一步最该先问清的事。**

### 3. 官方原版的已知限制（作者自己写的）

README「Known Limitations」记录了决定性实验：**把绘制整个关掉，fps 依然只有 2~43**。
所以瓶颈是 **EDT 调度**：AWT 事件队列里原生键鼠事件优先于 `invokeLater` 提交的任务。

**这意味着：做不到与参考项目（`dsh-claude-style`）完全一致的丝滑** ——
那个跑在浏览器里，`transition: transform 80ms` 交给 GPU 合成器插值；Swing 是逐帧自绘。
能明显改善，但不能等同。

---

## 四、参考项目：`dsh-claude-style` 的光标实现（已逆向完）

源码解压在 `D:\deepseekwork\_caret-anim-study\dsh-claude-style-0.15.0\`，
核心引擎已提取到 `_caret-anim-study\_extract\Z3-full.js`（4469 字符）。

**机制（与 Neovide 插件本质不同）：**

| | dsh-claude-style | Neovide 插件 |
|---|---|---|
| 手段 | 藏原生光标 + 自绘 div | 藏原生光标 + 自绘 Swing 面板 |
| 位移 | **CSS `transition: transform 80ms ease`**，交 GPU 插值 | 自己逐帧跑阻尼弹簧 |
| 变形 | **无**（始终 2px 竖条） | **有**（四角拉伸成四边形） |
| 闪烁 | `animation: blink 1s step-end`，位置变化时 `currentTime=0` 重置 | **未实现** |
| 中文 | 镜像测量（隐藏 div 复刻排版量 `getClientRects`） | IDEA API 直接给坐标 |

**它监听的事件**：`selectionchange / focusin / focusout / beforeinput / keydown / pointerdown / scroll / resize / fonts.loadingdone`

**它没有"删除时特殊处理"** —— 删除和输入走同一条路径。这反证了我当初给删除加吸附是错的方向。

关键代码片段：

```js
// R = 位置应用
P && (v.caret.style.transitionProperty = "none"),   // 首次出现：禁用过渡，瞬间就位
v.caret.style.transform = "translate(" + F + "px, " + M + "px)",
v.caret.style.height = w.height + "px",
P && (n = pe({ write(){ n=null, !r && (v.caret.style.transitionProperty = "") } })),
// 每次移动重置闪烁相位 → 打字时看不到它灭
for (let O of v.caret.getAnimations())
  O instanceof CSSAnimation && O.animationName === Q3 && (O.currentTime = 0)
```

---

## 五、开发环境（**离线可编译，无需 Gradle**）

### 已打通的构建链

```
1. 源码在  D:\deepseekwork\neovim-cursor-pr\src\main\java\...
2. 编译（离线，用本机 IDEA 的 jar 当 classpath）：
   D:\deepseekwork\_idea-anim\idea-libs\*.jar   ← 381 个 jar 的硬链接，不占额外空间

   javac --release 21 -nowarn -g -encoding UTF-8 \
     -classpath "<idea-libs 全部 jar 以 ; 连接>" \
     -d <out> <全部 .java>
3. 打包：以官方原版 jar 为容器，只替换变化的 class
   copy jetbrains-neovide-cursor-0.4.3.jar.orig  <out>.jar
   jar --update --file <out>.jar -C <classes 目录> .
   ※ **必须往 classes/META-INF/plugin.xml 写入**
     带 <version> 与 <idea-version since-build="242"/> 的版本，
      否则插件不加载（原版由 Gradle 注入）
4. 安装：拷到
   C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.4.3.jar
```

**IDEA 运行时会锁住 jar（内存映射），安装前必须让用户关闭 IDEA。**

### 编译/打包注意

- **行尾符**：上游源码全是 **LF**。用编辑器工具改文件容易引入 CRLF，
  会让 diff 变成"整文件重写"（曾导致 PR 显示 +1053/-573，实际只有 +513/-25）。
  仓库已加 `.gitattributes`（`* text=auto eol=lf`）防复发。
- 原版 class 经过 **IntelliJ 插件插桩**（`@NotNull` 检查等），
  所以**不要全量替换所有 class**，只替换实际改动的。
- `RadioAction` 在 2024.2+ 已被移除，做单选菜单要用 `ToggleAction` + 自行推导选中态。

### 完整源码位置（参考）

```
D:\deepseekwork\_neovide-cursor\src\neovim-cursor-for-jetbrains-0.4.3\   ← v0.4.3 原始产出
D:\deepseekwork\neovim-cursor-pr\                                          ← 工作副本
D:\deepseekwork\_caret-anim-study\                                         ← dsh-claude-style 逆向
D:\deepseekwork\_neovide-cursor\sim\                                      ← 调度器逐帧模拟程序
```

---

## 六、插件的架构要点

```
plugin.xml
  editorFactoryListener → NeovideCaretManager（每个编辑器挂一个 CaretAnimator）
  actions: ToggleNeovide / PaintDiag / TrailMode  （都在 ToolsMenu）

NeovideCaretManager
  - 隐藏原生光标：scheme.setColor(CARET_COLOR, 透明)，原色存 savedCaretColor
  - resolveCaretColor()  可取当前配色方案的光标色（已加）
  - ANIMATORS: Map<Editor, CaretAnimator>

CaretAnimator（核心，~900 行）
  - 后台调度线程 sleep + invokeLater 切 EDT（绕开 javax.swing.Timer 的 15.6ms 精度天花板）
  - 档位：ACTIVE=8ms / IDLE=120ms / IDLE_UNFOCUSED=600ms / MAX_ADAPTIVE=40ms
  - 自适应：smoothedIntervalMs 指数平滑，weight 变快 0.4 / 变慢 0.1
  - tick(): refreshTarget → 按 trailMode 分派 tickSpring / tickAfterimage → computeNextDelay

TrailCorner（拖尾核心）
  - 4 个角各带 X/Y 阻尼弹簧；按运动方向分配滞后系数 → 矩形被拉成四边形
  - trailFactors = {1.0, 0.9, 0.5, 0.3}；最前方角硬吸附
  - 低帧率补偿：保证动画至少跨 6 帧（MIN_ANIMATION_FRAMES）

DampedSpring
  - **解析解**（闭式）弹簧，非逐帧积分 → 掉帧不改变最终落点
  - MAX_STEP_RATIO=0.5 限制单帧位移（**绝不能改衰减因子 c**，会反向放大）
```

### 可调参数（`core/NeovideConfig.java`，**无设置界面**，改代码重编译）

| 参数 | 默认 | 作用 |
|---|---|---|
| `tailColor` / `shadowColor` | `#FFC0CB` | 拖尾色 / 光晕色 |
| `animationLength` | 0.1 | 常规动画时长（拖尾长度） |
| `shortAnimationLength` | 0.05 | 短距离移动时长 |
| `trailFactors` | 1/0.9/0.5/0.3 | 拉伸强度来源 |
| `shadowBlurFactor` | 0.2 | 光晕大小（0.6=原版夸张光雾） |
| `glowOpacity` | 0.55 | 光晕亮度 |
| `maxTrailDistanceFactor` | **60** | 拖尾限幅（作者注释建议卡就调小到 4） |
| `afterimageLifetime` | 0.22 | 残影模式尾巴长度 |

**未被使用的死参数**：`cursorDisappearDelay`(50)、`cursorFadeOutDuration`(0.075)
—— 定义了但全代码无人引用。

---

## 七、下一步该做什么

> ⚠️ **本节原有 5 条里有 3 条是错的，已于 2026-10-16 更正（保留错误记录，便于对照）**
>
> - 原第 3 条说"平滑档位代码在 `9b615df`" → **错**：`9b615df`（=`eb577c4`）是
>   「吸附+闪烁+钳制」那一版；平滑档位在 `cbfce99`（=`fcf0a6b`）。
> - 原第 4 条说"插件**完全没有实现**光标闪烁" → **错**：闪烁在 `eb577c4` 里实现了，
>   只是有缺陷（见下）。
> - 原第 1 条以及 **§三.1 / §三.2 的"退格卡顿"前提** → **已被用户否证**。
>   用户原话：「现在是不卡，是我第一次 pr 过去发现有 bug、ai 修复的第二版非常好、
>   一点也不卡」。那套"退格卡 / 跨行跳转卡"描述的是**更早的版本**，不是现在装的这个。
>   ~~不要再顺着"卡顿"这条线排查。~~
>   ⚠️ **2026-10-16 晚更正：上面这句划线部分又错了。** 用户在装完 `0.4.5` 后明确说
>   「**删除还是卡**，你继续优化试试」。也就是说：**打字/移动不卡，但删除（大位移）卡**。
>   所以"卡顿"这条线**必须继续查**，只是要精确到"删除/跨行这类大位移"。
>   成因与解法见 §十一。
> - 那两个被撤销的实现**都有 bug，不要直接 cherry-pick**：见 §七之二。

### 七之一、当前目标（用户已审批）

把 `dsh-claude-style` 的光标复刻成插件里的**第三个档位**（用户明确要求
「**是加档位不是替换**」）：**一条 2px 竖条，移动时 80ms 平滑滑过去，
静止后按原生节奏闪烁；无拖尾、无辉光、不变形。**

硬性要求（用户原话）：**丝滑流畅、中文不卡。**

### 七之二、被撤销的两个实现里到底有哪些 bug（**别重犯**）

`cbfce99`（平滑档位，283 行新增）：
1. `followSchemeCaretColor` 只在 `refreshStyles()` 里解析，而它只被 `onModeChanged()`
   调用 → **换主题后颜色不变，直到手动切一次档位**。
2. `resolveCaretColor()` 优先返回一次性的 `savedCaretColor` 快照
   → 快照永不刷新，换主题后一直是旧色。
3. `tickSmooth()` **没有消费 `jumped` 标志** → 状态泄漏到弹簧档，切回去时残留旧 rank。
4. 平滑档不画辉光，但 `glowPadding` 仍按辉光半径算 → 重绘包围盒白放大一圈。

`9b615df`（吸附+闪烁+钳制，179 行新增）：
1. `snapWhileDeleting` 的 `deleting` **只在 `documentChanged` 里写、`wakeUp()` 里从不重置**
   → 退格一次之后，**后续所有方向键移动都被当成"删除中"而吸附**，拖尾消失。
   这才是"退格后光标瞬移"的真正成因。
   **而这个方向本身就是错的：参考实现从不特判删除。已彻底放弃。**
2. `applyIdleBlink()` 排在 `tickSpring()` 的 `submitRepaint()` **之后**
   → 只有依赖"repaint 是异步的、EDT 还没处理脏区"这一**隐式假设**才能工作。
   正确顺序：**先算相位（写 alpha），再提交重绘。**

### 七之三、进度

第 0/1/2 步已做完（合并在一个 jar `0.4.5-smooth` 里，理由见 §十）；第 3 步未做。

---

## 八、用户偏好（务必遵守）

- **只用中文回复**
- **命令给可直接复制的纯文本块，绝对路径优先**
- **不要自己猜**：用户明确说过「你看看参考怎么弄的不要自己猜」
- 改动要**实测验证**，不要只靠推理下结论
- 删除/移动文件前先问；`D:\deepseekwork` 之外只读（本任务例外：插件目录已获授权）
- **凭据只报键名不打印值**
- 用户的 token 用完应立即提醒其删除

## 九、凭据与推送

推送需要 GitHub 凭据，机器上原本有一个 `gho_` OAuth token（一次性，已消耗）。
用户后来给过一个 PAT，**用完应提醒删除**。

**稳定的推送方式**（git 直连 github.com:443 时常超时，但 API 通常通）：

```
https://api.github.com/repos/<owner>/<repo>/git/blobs      创建 blob
                            /git/trees                     创建 tree（base_tree）
                            /git/commits                   创建 commit
                            /git/refs/heads/<branch>       PATCH 更新引用
```

或直接 `git push`（多试几次，能通）。

---

## 十、本轮改动（`0.4.5-smooth`，2026-10-16）

产物：`D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.4.5-smooth.jar`
（39274 字节）。

**为什么 0/1/2 三步合成一个 jar**：第 0 步（补"没有光标"的漏洞）用户根本看不见；
第 2 步（闪烁）**只在平滑档生效**，所以单独发一版等于什么都不变。三者无法独立观察，
拆开只会多一轮往返。

### 第 0 步 · 补齐"光标彻底消失"的漏洞（`NeovideCaretManager.install()`）

- **删掉** `if (editor.getProject() == null) return;`。
  它与 README 踩坑 #8 是**同一种错误的同一个形态**：隐藏原生光标走的是**全局**配色方案，
  **跳过 ≠ 放过** —— 那些编辑器照样被隐藏了，却没人补画。
- 改为在 `CaretAnimator` 里把 `getProject() == null` 与 `EditorKind.CONSOLE` 一起走
  **静态光标**分支（只补画一个静止矩形，且不参与高帧率）。
- 顺带把顺序倒过来：**先构造成功、再隐藏原生光标**。原顺序会留下一个窗口 ——
  构造抛异常时原生光标已被全局隐藏、却无人补画。
- `isTerminalEditor` 的跳过**保持不变**（终端光标由终端自己画，不受 `CARET_COLOR` 影响）。

### 第 1 步 · 平滑档位 `TrailMode.SMOOTH`（**新档位，默认仍是 SPRING**）

- `TrailCorner.setUniformAnimationLength()`：四角共用同一弹簧时长
  → 无方向性差异 → **无拉伸**（弹簧档的拉伸正是来自"按运动方向分配不同滞后"）。
- `CaretAnimator.tickSmooth()`：80ms（`smoothAnimationLength`）、**不画辉光**、
  **消费掉 `jumped`**、高度取目标行高（位移是滑的、行高是瞬时的 —— 对齐参考实现的
  "`transform` 带过渡、`height` 不带"）。
- `CaretTrailPanel.updateSmoothRect()`：四角写成一个规整的轴对齐矩形。
- `glowPadding` 改为**按档位算**（`CaretAnimator.updateGlowPadding()`）：
  只有弹簧档需要按辉光半径外扩；平滑档与静态光标留 2px 即可。
- **平滑档的隐藏优势**（值得记住）：四角刚性 → 重绘包围盒**永远是那条小竖条**；
  弹簧档拉伸时包围盒才可能膨胀到跨行。
- 菜单：`工具 → Neovide 光标：渲染模式`（三选一单选组 `NeovideCursor.ModeGroup`）。
  旧的「残影模式」开关**保留** —— 两者读写同一个 `trailMode` 字段，永远不会矛盾。
  平台自 2024.2 起移除了 `RadioAction`，因此用 `ToggleAction` + "选中态由当前模式推导"
  来呈现单选语义。

### 第 2 步 · 空闲闪烁（**仅平滑档生效**）

- `CaretTrailPanel.setCaretAlpha()`；`paintComponent` 在 alpha≈0 时**直接 return**
  （省掉整帧绘制）；`paintQuad` 在"灭"相位不画辉光；残影也乘 alpha。
- `CaretAnimator.applyIdleBlink()`：从**最后一次移动**起算，前 500ms 亮、后 500ms 灭的
  **方波**（对应参考实现的 `step-end` + `@keyframes{50%{opacity:0}}`），移动即重置相位。
  取"最后一次移动"而不是"最后一次重绘"，与参考实现的 `animation.currentTime = 0` 同义。
- **相位必须在 `submitRepaint()` 之前算定** —— 这正是修掉 `9b615df` 的时序依赖。
- **只对平滑档生效是刻意的**：弹簧/残影两档是"整条拖尾"，
  把拖尾一并闪灭会破坏用户已经满意的观感。

### 顺带修掉的真 bug · 换主题后"双光标"+ 跟随色失效（`pollSchemeChange()`）

- 现象：在 Settings 里换配色方案后，新方案的 `CARET_COLOR` 是**它自己的**（可见）颜色
  → **原生光标重新出现，与插件画的叠成双光标**；同时 `savedCaretColor` 还是旧主题的色，
  "跟随主题色"名不副实。
- 修法：`CaretAnimator.tick()` 每帧调 `NeovideCaretManager.pollSchemeChange()`，
  用**配色方案对象身份比较**检测换主题 → 重新隐藏 + 重抓原色 + 让所有面板刷新颜色。
- 为什么轮询而不订阅 `EditorColorsManager.TOPIC`：订阅需要 `Disposable`，
  而 `NeovideCaretManager` 是 `EditorFactoryListener`、没有天然的 Disposable 可用；
  轮询不需要 Disposable、不漏事件、无时序竞争，代价只是一次引用比较。
- `tick()` 只在 EDT 上跑（`invokeLater`），所以 `pollSchemeChange()` 是**单线程**的，
  不存在并发重入问题。

### 构建 / 安装 / 回滚（可直接复制的命令）

```powershell
# 构建（离线，无 Gradle）
pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1" -Version 0.4.5 -OutName my-test.jar

# 安装（必须先完全退出 IDEA，否则 jar 被内存映射锁住）
Copy-Item "D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.4.5-smooth.jar" "C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.4.3.jar" -Force

# 回滚到"用户满意的那一版"（0.4.4，SHA256 1FDD317469661D3A3B4DB08354388A5CF3A7ABA0CC8ED182BE906743404A6C8E）
Copy-Item "D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.4.4-rollback.jar" "C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.4.3.jar" -Force
```

### 还没做

- **第 3 步**：参考实现的 `caretMotion` 三态（`typing` / `move` / `off`）。
  `move` 语义是「**击键瞬间到位，只有方向键/点击才滑动**」，靠 `beforeinput` 早于
  `selectionchange` 区分。**`snapWhileDeleting` 这个方向已彻底放弃。**
- **中文 IME（"中文不卡"）**：**尚未测量**。按用户自己的 SKILL 教训「**先量，再改**」，
  下一步应先加诊断输出拿到数据，**不要凭感觉优化**。
- `reduced motion`（对应配置项尚未加）。

### 已知的、故意的取舍

- 平滑档的"滑"仍是在 EDT 上以 ~100fps 逐帧重绘实现的，**没有 GPU 合成**。
  参考实现用的是 `transition: transform`（浏览器合成器负责插值），所以它 100% 丝滑；
  本插件在 EDT 繁忙时理论上仍可能掉帧 —— 这是 Swing 的物理上限，不是实现缺陷。
  弹簧的解析解保证**掉帧时轨迹仍然正确**（只是采样点变少）。

---

## 十一、删除卡顿：成因、解法与用户的思路转向（2026-10-16 晚）

### 用户的明确指示（原话）

> 「删除还是卡 你继续优化试试 我有思路了**你直接把光标拉伸关闭掉就行了，其他按照那个原版来**
> 我叫你复刻**你不如做裁切**，这个保存到本地git 你重新预设一个就是关闭光标拉伸**可能有bug 你得看看**」

**这是一次方向修正，务必记住**：
- 不要再去"复刻"参考实现（那是我原来的路线）。
- 改成**裁切**已有的、用户满意的原版实现 —— 只砍掉一个东西：**光标拉伸**。

### 先排除的：没有删除专用代码

全源码 grep `delet|Delet|backspace|DocumentListener|snapWhile` → **零匹配**。
所以"删除卡"不是某段删除分支的锅，而是**删除触发的动画本身太重**。

### 成因（本项目自己的注释就是证据）

`CaretAnimator.repaintRegion()` 的注释记录了实测：

> `paintImmediately` 会把 RepaintManager 中所有已排队的脏区一次性同步处理掉 ——
> **删除文本等操作会产生大量脏区**，于是一次全被同步执行，EDT 被长时间阻塞。
> 实测：`repaint` → 峰值 105.6 fps；`paintImmediately` → 峰值仅 36.9 fps。

即：**删除时编辑器自身已经堆了大量脏区**，我们高帧率的重绘提交会让那一趟更重。

而拉伸是**重绘面积的放大器**（这是本轮最关键的一点）：
- 四角滞后不同 → 四边形被拉长 → 去画它的成本按**包围盒**计；
- 辉光是 **12 层同心 alpha 填充**，成本 = 12 × 包围盒面积；
- 一次跨行长跳（退格删掉换行）位移约 500px：
  - **拉伸时**：包围盒 ≈ 500×40 = 20000 px² → 每帧 ≈ **24 万像素**的 alpha 混合；
  - **共用时长后**：四边形恒为规整矩形，包围盒 ≈ 18×35 = 630 px² → 每帧 ≈ **7560 像素**；
  - **相差约 30 倍。**
- 而且本插件的重绘画在**透明覆盖层**上：重绘这一片，Swing 会连带把下面的编辑器文字一起重画。

这正好解释"编辑器本身不卡、只有删除这类大位移卡"。

### 解法一 · `disableStretch`（对原版的裁切）

- `NeovideConfig.disableStretch`（默认 **false**，保持原版观感不变）。
- `TrailCorner.jump()` 里加一个**提前返回分支**：跳过"按运动方向给四角分配不同滞后
  系数 + 前缘硬吸附"这一步，直接让四角共用 `baseTime`。
  **其余算术一字不改**：短距离阈值、低帧率补偿、弹簧重置条件、辉光、颜色、
  动画时长全部沿用原版 → 观感是"原版光标不再被拉扯"，不是换了一套效果。
- 顺带把"动画至少跨 N 帧"这条下界规则收进 `TrailCorner.applyFrameFloor()`，
  让正常路径与该分支共用，避免同一条规则两处各写一遍。
- 开关：`工具 → Neovide 光标：关闭拉伸（消除跨行/删除卡顿）`
  （`ToggleStretchAction`，`NeovideCaretManager.setDisableStretch()`）。

**用户说"可能有bug 你得看看"** —— 已逐条核对过：
1. 四角拿到的 `animationLength` 真的完全相同吗？→ 是。`isShortMove`/`baseTime`/
   `frameDt` 补偿三者对所有角点都是同一个输入（四个角的位移量相同）。
2. 切回拉伸模式会不会残留统一时长？→ 不会。`jump()` 每次都会重新 `setAnimationLength`。
3. 会不会自己撕开？→ 不会。`clampSpring` 的 `maxDistance` 对所有角点也相同，
   因此钳制后仍然刚性。
4. 切换开关时状态干净吗？→ 干净。复用了 `onModeChanged()`（吸附四角 + 清尾巴 +
   重算样式与重绘余量 + 强制下一帧重算坐标）。

### 解法二 · 重绘提交合并（`submitRepaint()`）

小步移动（打字、单字退格）时上一帧与本帧的重绘区域几乎重合，
原来会把同一块像素**两次**交给 `RepaintManager`（`addDirtyRegion` 要向上遍历组件树、
合并矩形）。现在两片区域**相交时合并成一次提交**（并集覆盖新旧位置，擦除语义不变）。

**大步跳转时仍然分开提交** —— 若合并成并集，就会把中间那一大片从未画过内容的区域
一起重绘，那正是 README 踩坑 #7 记录的问题。

### 产物

`dist\jetbrains-neovide-cursor-0.4.6-nostretch.jar`（41029 字节）。

> ⚠️ **已被 §十二 取代** —— 这份是从"复刻路线"的代码上做的，用户要求放弃该路线。
> 本节的"重绘合并"思路仍然有效，但基线应当换成 `9739ae5`。

### 还没做 / 待验证

- 用户尚未实测 `0.4.6`（IDEA 在运行，jar 被锁住装不进去）。
- 若"关闭拉伸"仍不够，下一步的**分离实验**应是：
  保留重绘、把绘制内容减到"一个纯 2px 矩形"（无辉光、无四边形），看 fps ——
  这样才能把"绘制算法慢"与"重绘提交本身有害"分开。
  原 README 的 paint-disabled 实验把这两者**同时**去掉了，只能证伪前者。
- 第 3 步（`caretMotion` 的 `move` 语义）与中文 IME 测量仍未做。

---

## 十二、方向再修正：放弃复刻，回到 `9739ae5` 只做"关闭拉伸"（2026-10-16 深夜）

### 用户的最终指示（原话）

> 「我希望你**拿我之前那版本 只加一个模式** 渲染不要 就是我那个**修复的pr版本**上去改
> **只改一个模式 关闭拉伸** …… 你就从**我之前4.3版**改 ……
> **你现在不要看之前的实现了** 只拿我 pr 修复那个改一个挡位就是关闭拉伸的
> 你试试 **渲染模式没必要要 只要一个 关闭拉伸**」

**结论：§十、§十一 里"复刻 dsh-claude-style"的整条路线作废。**
源码已挪到 `experiment/smooth-and-stretch` 分支，未删除，仅供追溯。

### 权威事实：他满意的那版 = `9739ae5`

不要靠版本号猜 —— 他嘴里的"4.3 版"是**文件名**叫
`jetbrains-neovide-cursor-0.4.3.jar`，而里面的 `plugin.xml` 写的是 `0.4.4`。

已用**逐类反汇编比对**确认：`dist/jetbrains-neovide-cursor-0.4.4-rollback.jar`
（= 他实际在跑、且满意的那个）与从 `9739ae5` 编译出的 `baseline-check.jar`：
**13 个类签名 100% 一致**，只多一个调试用的 `OffsetTest.class`。
四个候选 jar **全都只有 `SPRING` / `AFTERIMAGE` 两个档位** ——
没有平滑档、没有闪烁、没有 `snapWhileDeleting`。

### 分支布局（别再搞混）

| 分支 | 内容 |
|---|---|
| `feat/no-stretch` | **当前工作分支**，起点 `9739ae5`，只做"关闭拉伸" |
| `experiment/smooth-and-stretch` | 复刻路线的全部实验（0.4.5 / 0.4.6），已弃用但保留 |
| `fix/adaptive-delay-reset-on-wakeup` | 已复位回 `9739ae5`，与远端一致 |
| `dist/` | **已退出 git 跟踪**（构建产物；且切分支会把它连带删掉） |

> ⚠️ **踩坑记录**：上一轮 `git add -A` 把 `HANDOFF.md` 与
> `SKILL-jetbrains-plugin-offline-build.md` 也加进了跟踪，于是**切分支时 git
> 把它们从工作区删掉了**。已用
> `git checkout experiment/smooth-and-stretch -- <文件>` 恢复。
> **教训：这两个文档应保持未跟踪，或用 `git add` 精确指定源码路径，永远别用 `git add -A`。**

### 本轮产物：`0.4.7-nostretch`（35011 字节）

只加了一样东西：

- `NeovideConfig.disableStretch`，**默认 `true`** —— 本插件的预设就是"原版效果但不拉伸"。
- `TrailCorner.jump()` 的提前返回分支：跳过"按运动方向分配滞后系数 + 前缘硬吸附"，
  四角共用 `baseTime`；**其余算术一字不改**（短距离阈值、低帧率补偿、弹簧重置条件、
  辉光、颜色、动画时长全部沿用原版）。
- 抽出 `TrailCorner.applyFrameFloor()`，让正常路径与该分支共用同一条帧数下界规则。
- `ToggleCaretStretchAction` + 菜单项 `NeovideCursor.DisableStretch`。
- **删掉菜单里的「残影模式」入口**（用户：「渲染模式没必要要」）。
  `ToggleTrailModeAction.java` 与 `TrailMode.AFTERIMAGE` **代码保留未删**，
  只是不再有入口 —— 没有用户同意不删能跑的代码。

另外修了两个 bug（见下）。

### bug ①：空行上按 Delete / 空格"非常卡"

用户原话：

> 「有一个 bug，**一行中没有任何字符时候 我按删除和空格变得很很卡**（拉伸版也有这个问题）
> **多行效果理想非常好**，输入删除也跟得上」

**注意"拉伸版也有这个问题"** → 这是**原版就有的 bug，不是复刻引入的**。

全源码 grep `delet|backspace|DocumentListener|snapWhile` → **零匹配**，
所以不是某段删除分支的锅。

**本轮的处理（是假设性修复，须实测确认）**：加**亚像素死区**
`CaretAnimator.TARGET_EPSILON = 0.5`。

原来的判据是精确的 `==` 比较：

```java
if (newCenterX == centerX && newCenterY == centerY && height == cursorHeight) return false;
```

位置是 `double`，只要换算结果有一位浮点抖动，"目标变了"就成立 →
`jumped = true` → 重新分配角点参数、重新起跳。**若抖动每帧都发生，弹簧就永远
收不了尾：光标明明停在原地，却一直以最高帧率全量重绘** ——
表现正是"没动却非常卡"。编辑器正在重排时（空行、行高变化、软换行）这类抖动是真实存在的。

半个像素不会误伤正常移动：相邻列间距至少一个空格宽（数像素），换行是整个行高。

**若实测仍卡，下一步的验证手段**：插件已有每秒一次的 `LOG.info` 性能日志
（`CaretAnimator.recordFrameStats`）。复现时看 `idea.log`
（`帮助 → 在资源管理器中显示日志`）：若光标静止在空行上而 fps 仍接近 120、
重绘面积也不大，就说明**动画没收尾**这条假设成立，可据此继续定位抖动来源。

### bug ②：快捷键切字号时"光标从别处飞出来"

用户原话：

> 「我快捷切换大小时候**光标会随机从其他地方飞出来**；
> **原版好，但是去除拉伸差**」

成因清楚：`refreshTarget()` 只在 `!initialized` 时吸附，行高变了仍然走动画
（`jumped = true`）→ 光标从**旧位置**滑过来。
原版因为前缘硬吸附（`useHardSnap`）几乎瞬间到位，所以看不出来；
一旦关掉拉伸（四角共用时长），这个缺陷立刻暴露。

修法：行高变化视为"布局变了"，**直接吸附四角**，不做过渡动画：

```java
boolean layoutChanged = Math.abs(height - cursorHeight) >= TARGET_EPSILON;
...
if (!initialized || layoutChanged) { 全部 snapTo }
```

### 仍需用户实测

`0.4.7-nostretch` 尚未实测 —— IDEA 在运行时 jar 被锁住。安装/回滚命令见 §十。

---

## 十三、「关闭拉伸」最终用参数实现（取代 §十二 的代码方案）

用户问：「人家根本不卡，你直接研究一下参数吧，能直接关闭拉伸吗」

**答案：能，零代码。改两个默认值即可。**

同时「删除卡」这条线彻底作废 —— 用户明确说「人家根本不卡」，
§十一 那套「拉伸 × 30 倍重绘面积」的性能论证**不再作为理由**，
关闭拉伸回归为纯观感偏好。

### 改动（仅 NeovideConfig.java，两行）

| 参数 | 原值 | 新值 |
|---|---|---|
| `trailFactors` | `{1.0f, 0.9f, 0.5f, 0.3f}` | `{1.0f, 1.0f, 1.0f, 1.0f}` |
| `useHardSnap` | `true` | `false` |

### 为什么是这两个，且必须成对

`TrailCorner.jump()` 里只有一个地方决定时长：

    useSnap = useHardSnap && alignment > leadingSnapThreshold
    factor  = useSnap ? leadingSnapFactor : trailFactors[rank]
    animationLength = useSnap ? snapAnimationLength : baseTime * factor

- `trailFactors` 全 1.0 → 不论名次都拿到 `baseTime`；
- 但硬吸附会**绕过该表**改用 `snapAnimationLength`(0.02s)，所以必须同时关掉 `useHardSnap`；
- 于是 `leadingSnapFactor` / `snapAnimationLength` / `leadingSnapThreshold` **全部变成死设置**；
- `maxTrailDistanceFactor` **不是**开关，它只是限幅；而且设为 0 会把弹簧夹死在起点。

### 零形变的证明（为什么不可能是"形变小一点"）

`destX = centerX + relativeX * width`，`relativeX` 是每角固定常数（±0.5）。
位移量要与之做差，`relativeX * width` 自行抵消：

    jumpX = ((cx新 + rx·w) − (cx旧 + rx·w)) / w = (cx新 − cx旧) / w

→ 四角的 `len` / `isShortMove` / `baseTime` 相同；
加上 `useHardSnap=false` 且系数全 1 → `animationLength` 相同；
弹簧初值 `setPosition(新dest − current)` 在规整矩形下同样抵消 → 相同；
`clampSpring` 的 `maxDistance` 四角共用。
**起点相同 + 动力学相同 = 轨迹相同 → 相对位置永不改变。**

（即使某帧已经歪了，因动力学是线性的、`g(t)` 对四角相同，
歪量按 `dᵢ·g(t)` 指数衰减，而且不会再被动画制造出来。）

### 副作用

关掉硬吸附＝不再有「前缘先到」，整条光标都要走完 `baseTime`。
按现有参数：相邻字符 `len ≈ 3.5`、跨行 `len = 1`，
**都落在 `shortMoveThreshold`(8) 之内** → 绝大多数移动走 `shortAnimationLength` = 0.05s。
觉得发飘就压 `shortAnimationLength`（仍然零代码）。

### 验证：严格证明"只改了参数"

`dist/jetbrains-neovide-cursor-0.4.5-nostretch-params.jar` 与
`dist/jetbrains-neovide-cursor-0.4.4-pr-9739ae5.jar` **逐 class 字节比对**：
**11 个类完全相同**，只有 `NeovideConfig.class` 与其嵌套枚举
`NeovideConfig$TrailMode.class` 不同 ——
后者仅因新增注释使**行号表位移**，去掉行号信息反汇编后完全一致。

字节码事实：`trailFactors` 是 4 个 `fconst_1`；`useHardSnap` 是 `iconst_0`。

### 分支与文档

- `feat/no-stretch-params`（从 `9739ae5`）—— 本方案。
- `fix/adaptive-delay-reset-on-wakeup` —— **保持 `9739ae5` 不动**，是用户的 PR 分支。
- `feat/no-stretch`（`417910a`）—— §十二 的代码方案，保留但已被本方案取代。
- ⚠️ `HANDOFF.md` / `SKILL-*.md` / `dist/` **刻意不纳入 git 跟踪**：
  它们只在部分分支上被跟踪，切分支时会被 git 连带删除（已踩过两次）。
