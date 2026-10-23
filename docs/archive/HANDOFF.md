# HANDOFF —— Neovim Cursor for JetBrains

> 最后更新：2026-10-16 深夜。**下一任请先读第 0 节和第五节。**
> 完整历史（含已被推翻的分析）归档在 `HANDOFF-full-history.md`。

---

## 0. 一句话现状

**用户满意的那版 = `9739ae5`**，已装在 IDEA 里，源码在分支
`fix/adaptive-delay-reset-on-wakeup`，**src 干净、无需任何改动**。
用户明确说**不再改代码**。若你正想动手，先读第五节的「我已经犯过的错」。

---

## 一、权威事实：怎么确认「用户满意的那版」是哪个提交

**不要靠版本号推断。** 用户嘴里的「4.3 版」指的是**文件名**
`jetbrains-neovide-cursor-0.4.3.jar`，而那个 jar 里的 `plugin.xml` 写的是 `0.4.4`。
名字与内容不一致，是这个项目最容易把人带沟里的一点。

确认方法：**逐 class 字节级比对**（不是比签名 —— 签名相同不代表方法体相同）。
用离线构建脚本从某提交编译出 baseline，再与待确认 jar 逐 class 比哈希。

结论：`dist/jetbrains-neovide-cursor-0.4.4-rollback.jar`
（SHA256 前 16 位 `1FDD317469661D3A`，36527 字节）与从 `9739ae5`
编译出的结果 **13 个 class 逐字节完全相同**。这就是用户满意的那版。

`dist/jetbrains-neovide-cursor-0.4.4-pr-9739ae5.jar`（33476 字节）是从源码
重新构建的等价物，两者可互换。

**安装后的验收命令**（必须是 `1FDD317469661D3A`）：

```powershell
(Get-FileHash "C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.4.3.jar").Hash.Substring(0,16)
```

| 哈希前 16 位 | 是什么 |
|---|---|
| `1FDD317469661D3A` | **用户满意的 PR 版（正确）** |
| `94B2D91662BEEE2D` | 我做的 0.4.7「关闭拉伸」—— 会导致卡死 |
| `A7CC02E300D706FE` | 我做的 0.4.5「参数关闭拉伸」—— 也会导致卡死 |

---

## 二、PR 状态（已联网核实）

| 项 | 值 |
|---|---|
| PR | [pull/1](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/pull/1) |
| 标题 | `fix(scheduling): 从空闲档唤醒时正确重置帧间隔估计量` |
| 状态 | **`open`，`merged_at: null` —— 未合并** |
| head | `9739ae5696239322fc45cd7040ef706933a4082f` = 本地 `9739ae5`（一致） |
| base | 提 PR 时是 `ee378a2` |
| 作者 | `pawamamama`（fork）→ `yyyyolo7a79-sketch/neovim-cursor-for-jetbrains` |

**PR 里就是用户说的「第一次有 bug / 第二次才好」两个提交：**

- `9d82894`（第一次，有 bug）：判据写 `smoothedIntervalMs > MAX_ADAPTIVE_DELAY_MS`。
  `wakeUp()` 每次击键都会调用，而打字时真实帧间隔本就超阈值 → 每次击键都把它按回
  8ms → 调度器以编辑器交付不了的频率提交，白占 EDT。
- `9739ae5`（第二次，正确）：改用**「刚才所处档位」**判断 ——
  `currentDelayMs > MAX_ADAPTIVE_DELAY_MS`。空闲档才重置，活跃档不干预。

PR 描述里的实测：从空闲档唤醒，修复前 **26 帧 / 715ms**，修复后 **1 帧 / 8ms**。

> ⚠️ **上游已前进**：`git fetch` 显示 `origin/main` 已到
> `6bed09d`（`feat: 弹簧拖尾对齐 VS Code 原版 + 多光标拖尾（v0.5.0）`，2026-10-16），
> 相对我们的基线动了 **10 个文件、+772/−349 行**，新增
> `RenderScheduler.java`、`SpringTrail.java`、`ToggleHideNativeCaretAction.java`。
> **PR 与上游新版已有较大差距**，要不要处理由用户决定。

---

## 三、上游 v0.5.0 与我们的关键差异（读上游踩坑记录得到）

| 参数 | 我们基线 `9739ae5` | 上游 v0.5.0 |
|---|---|---|
| `hideNativeCaret` | **`true`**（隐藏原生光标） | **`false`**（保留原生光标） |
| `snapAnimationLength` | `0.02` | **`0`**（瞬时贴合） |
| `MIN_ANIMATION_FRAMES`（低帧率补偿） | 有（6 帧） | **已删除** |
| `tailColor` / `shadowColor` | `#FFC0CB` 粉 | **仍是 `#FFC0CB`**（未做主题色跟随） |
| 菜单 | 开关 / 性能诊断 / 残影模式 | 多一项**隐藏原生光标** |

**上游没有做「跟随主题色」** —— `NeovideConfig` 里没有任何
`Scheme`/`Theme`/`follow` 字段。原因是踩坑 #19：改成保留原生光标后，
「自己画一个跟主题同色的光标」这件事就不再需要了。

---

## 四、用户问过的「他那个光标怎么确定主题色」

参考实现（`dsh-claude-style`，本地副本 `D:\deepseekwork\dsh-claude-style`）
**确实是动态读的**，在**接管前的一瞬间**读原生光标的计算色：

`packages/client/src/features/caret/caret.ts` 第 283-291 行：

```ts
const adoptCaretColor = (input: HTMLElement, layer: CaretLayer) => {
  const style = getComputedStyle(input)
  const color = style.caretColor === 'auto' || style.caretColor === 'rgba(0, 0, 0, 0)'
      ? style.color        // 透明/auto → 退而用文本色
      : style.caretColor   // 否则照抄原生光标色
  layer.caret.style.setProperty(CARET_COLOR_PROPERTY, color)
}
```

配 `caret.css` 第 43 行的三级回退：

```css
background: var(--dsh-claude-caret-color,               /* ① 读到的原生色 */
            var(--dsw-alias-state-business-primary,     /* ② 主题强调色 */
            currentColor));                             /* ③ 当前文本色 */
```

三个要点：

1. **不是查色号，是问运行时「原生光标现在什么颜色」** —— 主题换色自动跟随，
   插件不需要知道任何主题名。
2. **时机**：注释写明 *"only readable while the mark is not written yet;
   afterwards the computed value is transparent"* —— 必须在**动手隐藏之前**读。
3. **读到透明要退让**：说明别的插件已先接管，照抄会画出「什么都没有」。

**本插件其实也读了**（`NeovideCaretManager.java:256`，时机同样正确），
`sanitizeCaretColor()` 也做了同样的三级回退。但它**只用于还原**，
`savedCaretColor` 没有任何 getter 暴露出去；真正画拖尾的
`CaretTrailPanel.refreshStyles()` 用的是写死的 `config.tailColor`。
所以现状是：**读到了，但没拿来画。**

用户的主题是 **Material Oceanic**，该 `.icls` **没有定义 `CARET_COLOR`**，
所以 `sanitizeCaretColor()` 走的是 `getDefaultForeground()` 兜底。
该主题的蓝色系只有 `#80CBC4`（青）、`#676E95`（灰蓝），
accent 色是 `#6494ed`（`options/material_theme_new.xml` 里实测读到）。

**用户尚未决定用哪个颜色，也尚未决定是否做动态跟随。不要擅自改。**

---

## 五、我已经犯过的错 —— 下一任必读

### 错误 1：没读项目自己的踩坑记录就动手（最严重的）

用户给过 README 链接，我没读就去改。结果**两次实现都是上游早已记录并撤销的**：

> **#20「低帧率补偿」把拖尾的拉伸抹平了**
> 为了防止短动画被 `animationLength <= dt` 吃掉，曾强制每个角的时长至少为 `6 × dt`。
> 但原版四个角的时长是**有梯度的**……补偿后全部被抬到约 0.06s，四角同速，
> **光标只是整体平移，不再被拉伸。**
> **教训**：原版在 60fps 下最短的角一帧到位，是**设计行为**而非缺陷。
> 「修复」之前先确认它真的是 bug。

> **#19 拖尾不该「当光标用」——保留原生光标才是原版观感**
> 移植初期把原生光标隐藏、由覆盖层四边形充当光标。结果光标的跟随完全取决于
> EDT 调度：编辑器一忙，光标本身就「跟不上手」——这是体验远不如 VS Code 版的
> **首要原因**。原版默认 `hideNativeCursor: false`。
> **教训**：移植时最该对齐的不是参数，而是「谁负责什么」的职责划分。

**我做的「关闭拉伸」，本质就是把四角时长抹平**（#20 的翻版）；
**「关掉硬吸附」更糟** —— 它让所有角都必须走完完整时长，每帧多画一段，
于是**卡死**。用户原话：「不行卡死我了」。

**而且我本地 README 是旧的（18 条），上游有 21 条** —— 多出来的三条里
正好有宣判我改动的那条。已单独写成
[SKILL-upstream-lessons-before-patching.md](../SKILL-upstream-lessons-before-patching.md)。

### 错误 2：把「删除卡」当成性能结论，用算术代替实测

我算了一次「拉伸使重绘面积大 30 倍」，就把它当成性能论证。
用户随后说**「人家根本不卡」** —— 那套分析从未被实测证实，已作废。

### 错误 3：`git add -A` 把文档也加进跟踪，切分支时被 git 删掉

`HANDOFF.md`、`SKILL-*.md`、`dist/` **刻意保持未跟踪**：
它们只在部分分支上被跟踪，切分支时 git 会把工作区里的副本删掉。
**真实踩过两次。** 用 `git add <具体路径>`，永远不要 `git add -A`。

### 错误 4：反复改视觉/时序参数，却没有可靠的验证手段

连续两次把用户的编辑器弄到卡死。**如果你也无法验证流畅度，就不要改时序和动画常量；
要改，先把回滚命令和安装命令写在同一条消息里。**

---

## 六、分支布局（别搞混）

| 分支 | 内容 | 处置建议 |
|---|---|---|
| `fix/adaptive-delay-reset-on-wakeup` | **当前分支**，`9739ae5` = 用户满意版 | **别动** |
| `fix/clean` | 同为 `9739ae5` | 可删（重复） |
| `feat/no-stretch`（`417910a`） | 我做的代码方案（含两个 bug 修复） | 保留备查，**会卡死** |
| `feat/no-stretch-params`（`bc35075`） | 我做的参数方案（改 `trailFactors`/`useHardSnap`） | 保留备查，**会卡死** |
| `experiment/smooth-and-stretch`（`c716d93`） | 复刻 dsh-claude-style 的整套实验 | 已废弃，保留追溯 |

**未跟踪**（勿 add）：`HANDOFF.md`、`HANDOFF-full-history.md`、
`SKILL-*.md`、`dist/`

---

## 七、构建 / 安装 / 回滚

### 构建（离线，无需 Gradle）

```powershell
& pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\offline-build.ps1" -Version 0.4.4 -OutName 产出名.jar
```

细节见 [SKILL-jetbrains-plugin-offline-build.md](../SKILL-jetbrains-plugin-offline-build.md)。
JDK 25 在 `D:\java_learning\jdk25\bin`，`--release 21`；
classpath 是 `D:\deepseekwork\_idea-anim\idea-libs` 下的 381 个 jar；
脚本以官方 `.orig` jar 为容器、注入 `<version>` 与 `since-build="242"`，
`jar --update` **只加不删**。

### 安装（**IDEA 必须完全退出**，否则 jar 被内存映射锁住）

```powershell
Copy-Item "D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.4.4-rollback.jar" "C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.4.3.jar" -Force
```

### 回滚 / 验收

回滚就是上面那条（rollback jar 即正确版本）。验收：

```powershell
(Get-FileHash "C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.4.3.jar").Hash.Substring(0,16)
```

应为 `1FDD317469661D3A`。

### 取上游最新（git 协议不受 GitHub API 限流影响）

```powershell
cd 'D:\deepseekwork\neovim-cursor-pr'
git fetch origin main
git show origin/main:README.zh-CN.md > build\readme-main.txt
```

> GitHub API（`api.github.com`）会限流（`API rate limit exceeded`），
> `raw.githubusercontent.com` 在本机会 `fetch failed`。
> **用 `git fetch` + `git show`，或走 `Invoke-RestMethod` 的 contents API（限流前可用）。**

---

## 八、尚待用户决定的事项（不要替他决定）

1. **是否采用上游 v0.5.0 的职责划分**（`hideNativeCaret = false`）。
   好处：跟随不再依赖 EDT 调度（#19 的「首要原因」）、不再改全局配色
   → 消除踩坑 #18 那个「卸载插件也无法自愈」的坏值落盘风险、控制台无需补画光标、
   光标恢复闪烁。**代价是与现有观感差异较大。**
2. **拖尾颜色是否改天蓝**，以及是**写死**还是**动态跟随主题**
   （候选：`#4FC3F7` 标准天蓝 / `#6494ed` 主题 accent / `#80CBC4` 主题青色）。
   注意 `tailColor`（本体）与 `shadowColor`（辉光）**要一起改**，
   否则会出现「蓝光标 + 粉光圈」。
3. **PR 是否要跟上上游 `6bed09d`。**
4. 若曾为更新 PR 生成过 GitHub PAT —— **提醒用户去吊销**。

---

## 九、踩坑 #18 的实际风险（与本项目强相关，务必知悉）

改全局配色方案的 `CARET_COLOR` 隐藏原生光标时，
**「只改内存」不等于「不会落盘」**：

```
① 插件把内存里的 CARET_COLOR 改成全透明
        ↓
② 用户在 Settings 里改了任意一项编辑器设置（例如字体），点 OK
        ↓
③ IntelliJ 保存配色方案时把【当时的内存状态】整体落盘
   → colors/_@user_<方案名>.icls 里多出一行：
      <option name="CARET_COLOR" value="00000000" />
        ↓
④ 此后无论是否安装插件，光标都是隐形的 —— 坏值已在磁盘上
```

**卸载插件无法自愈**，用户会以为「我没装插件了为什么光标还是看不见」。
每个 IDE 大版本各存一份 `.icls`（`IntelliJIdea2025.2` / `2026.1` / …）**都要改**，
且**改之前先关掉 IDE**，否则退出时会被内存状态覆盖回去。

本机现状：用户主题是 `_@user_Material Oceanic`，该文件**未发现**被污染的
`CARET_COLOR`；`options/editor.xml.bak-20261016074951` 的存在说明
`editor.xml` 被改动过，如怀疑污染可对照排查。
