---
name: jetbrains-plugin-offline-build
description: Build, patch, package, and install IntelliJ Platform (JetBrains IDE) plugins without Gradle — compile offline against the locally installed IDE's jars, inject the plugin.xml version Gradle normally adds, swap individual classes into the official jar, and install past the running-IDE jar lock. Also covers frame-rate/EDT diagnosis for plugins that make the IDE feel slow, the 2024.2+ API changes (RadioAction removed, the one safe overlay mount point), and keeping CRLF out of a PR diff. Use when writing or modifying a JetBrains IDE plugin, when a plugin's behavior must be changed but it ships no settings UI, or when a plugin built by hand fails to load or installs but does nothing.
---

给 IntelliJ IDEA / PyCharm / CLion 等 JetBrains IDE 开发或改造插件时用。
核心价值：**不装 Gradle、不下载 SDK，直接用本机已装的 IDE 离线编译打包**，
以及插件开发中最容易踩的几个坑（jar 被锁、plugin.xml 必须注入版本、行尾符污染 diff）。

## 何时用

- 要写一个新 IDE 插件，或改造一个现有的第三方插件
- 用户想调某个插件的行为但作者没提供设置界面（改源码重编译）
- 需要离线构建（网络差、不能下数 GB 的 IDE 分发包）

## 前置侦察（先做这一步）

```powershell
# 1. IDE 装在哪
Get-ChildItem 'D:\java_learning' -Filter '*IDEA*' -Directory -Recurse -Depth 1

# 2. 本机有没有 JDK / kotlinc
& javac -version
Test-Path "$ideaRoot\plugins\Kotlin\kotlinc\bin\kotlinc.bat"

# 3. 用户插件目录（能写吗）
$pdir = "$env:APPDATA\JetBrains\<Product><Version>\plugins"
```

**关键**：编译用系统 JDK 比 JBR 稳。
IDE 的 `lib` 里**没有 kotlin-stdlib**（在 `plugins/Kotlin/kotlinc/lib`）。

## 离线编译（核心技巧）

**难点**：IDE 的 `lib` 路径常含空格（`IntelliJ IDEA 2026.1.3`），
而 classpath 用 `;` 连接、argfile 按空格断行 —— 直接拼必然失败。

**解法：建一个无空格的硬链接目录**（不占额外空间）：

```javascript
for (const j of fs.readdirSync(src).filter(f => f.endsWith(".jar")))
  fs.linkSync(path.join(src, j), path.join(dst, j));
```

**显式列出全部 jar**（kotlinc 不认 `*`，那是 java 命令的特性）：

```powershell
$cp = (Get-ChildItem 'D:\...\idea-libs' -Filter '*.jar' |
       ForEach-Object { $_.FullName }) -join ';'
```

**Java 插件**：

```powershell
& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d <out> <所有 .java>
```

Kotlin 插件同理；若必须用 argfile，**每个路径单独加双引号**。

**编译目标**：2024.2+ 平台要求 **Java 21**。2024.1 及更早跑 JBR 17，
加载 Java 21 字节码会 `UnsupportedClassVersionError`。

## 打包（关键坑）

**坑 1：`plugin.xml` 必须在 jar 内** `META-INF/plugin.xml`，不能散在 `lib/`。

**坑 2：源码的 `plugin.xml` 通常缺 `<version>` 与 `<idea-version>`** ——
那是 Gradle 注入的。手工打包必须补，否则插件**不加载**：

```xml
<idea-plugin>
    <version>0.5.0</version>
    <idea-version since-build="242" />
```

`until-build` 刻意留空 = 兼容所有后续版本（JetBrains 官方建议）。

**坑 3：不要全量替换 class。** 官方 class 可能经过 **IntelliJ 插桩**
（`@NotNull` 参数检查）。以**官方原版 jar 为容器**，只替换改动的 class：

```powershell
Copy-Item <原版.jar> <产出.jar> -Force
& jar --update --file <产出.jar> -C <classes目录> .
```

**坑 4：`jar --update` 只加不删** —— 改名/删除的 class 会残留。

## 安装与验证

```powershell
# 目标
$env:APPDATA\JetBrains\<Product><Version>\plugins\<dir>\lib\<plugin>.jar
```

**IDEA 运行时会锁 jar（内存映射），安装前必须关闭 IDEA。**
典型报错：`请求的操作无法在使用用户映射区域打开的文件上执行`。

**验证装进去的确实是新版**：

```powershell
& javap -p -c -cp <产出.jar> <全限定类名> | Select-String '你的新方法名'
```

## 行尾符污染（PR 场景必看）

上游常是 **LF**，Windows 编辑器容易写入 **CRLF**，diff 会变成"整文件重写"。
实例：真实只改 513 行，PR 显示 `+1053/-573`。

**先加 `.gitattributes`**：

```
* text=auto eol=lf
*.jar binary
*.png binary
```

**提交前检查**：`if (content.includes("\r\n")) { /* 统一成 \n */ }`

## 平台 API 变迁（2024.2+）

- **`RadioAction` 已被移除** —— 单选菜单用 `ToggleAction` +
  `isSelected()` 返回"当前状态是否等于本项"，靠推导实现单选语义
- 挂编辑器覆盖层的**唯一安全位置**是 `editor.getContentComponent()`：
  它是 `layout == null` 的 JTextComponent，不会在 `revalidate()` 时被拉伸，
  且坐标系与 `visualPositionToXY()` 一致。用 `JLayeredPane` 或父容器都会被拉伸。
- `com.intellij.openapi.editor.impl.*` 是内部 API，会变；尽量只用公开接口

## 排查"插件让 IDE 变卡"

**先分离瓶颈，别急着改绘制**：

1. 让插件打印 fps + 重绘面积 + 调度间隔
2. **fps 低但重绘面积小** → 瓶颈在 **EDT 调度**，不是绘制。
   AWT 事件队列里原生键鼠事件优先于 `invokeLater` 提交的任务；
   **Swing 逐帧自绘做不到 CSS transition 那种交给 GPU 合成器的丝滑**
3. **重绘面积大** → 才是绘制问题（缩小重绘包围盒）

**反模式（踩过）**：看到"删除时卡"就禁止删除时的动画 ——
若瓶颈本在 EDT，这既没解决问题，又丢了动效。**先量，再改。**

`javax.swing.Timer` 有 **15.6ms 精度天花板**（内部走 `Object.wait`）；
要高帧率用「后台线程 `Thread.sleep` + `invokeLater`」。

## 参考实现

Neovide 风格拖尾光标（物理与绘制注释详尽，值得读）：
`https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains`
其 README 的「Lessons Learned」记录挂载点、帧率瓶颈等一手结论。
