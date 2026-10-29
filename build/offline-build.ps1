<#
  Neovide Cursor —— 离线编译 + 打包（完全不需要 Gradle）

  为什么要有这个脚本：本机没有可用的 Gradle 分发，但 IDEA 自己已经装好了，
  它 lib 下的 381 个 jar 就是完整的编译期 classpath。HANDOFF 第五节记了这套流程，
  这里把它固化下来，避免每次手打一长串命令。

  用法（必须用 pwsh 7）：
    pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 -Version 0.4.5 -OutName my.jar

  产出：dist\<OutName>
  安装：先【关闭 IDEA】（它内存映射锁住 jar），再拷到
        %APPDATA%\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.4.3.jar
#>
param(
  # 插件版本号，用于注入 plugin.xml（Gradle 原本代劳，手工打包必须自己补，否则插件不加载）
  [Parameter(Mandatory = $true)][string]$Version,
  # 产出的 jar 文件名（放在 dist\ 下）
  [Parameter(Mandatory = $true)][string]$OutName,
  # 容器 jar：以官方原版为底，只替换变动的 class。
  # 为什么不能从零打包：官方 class 经过 IntelliJ 插桩（@NotNull 参数检查等），
  # 全量替换自己编译的版本会丢掉插桩、行为不一致。
  [string]$BaseJar = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib\jetbrains-neovide-cursor-0.4.3.jar.orig',
  # 兼容区间下界：2024.2 平台的 build 号。低于它的 IDE 直接拒绝加载（这是好事）。
  [string]$SinceBuild = '242',
  # 兼容区间上界：261.* = 2026.1 全系放行，262 起必须出新版本。
  #
  # 【为什么必须有上界】
  # until-build 留空时，任何未来版本的 IDE 都会加载本插件。一旦 JetBrains 移除
  # 我们用到的某个平台 API，用户升级 IDE 后看到的是 NoSuchMethodError ——
  # 现象像"IDE 坏了"，用户不会想到是插件。加上界就把"崩"换成"暂不兼容，等新版"。
  #
  # 【为什么写 261.* 而不是 261.25134.95】
  # 精确 build 号会把同一大版本里后续的小版本（2026.1.4 等）一起拒掉，
  # 而 261.x 内部的平台 API 是兼容的。只锁大版本，粒度才对。
  #
  # 【维护】每年复核一次：值取「本机真机冒烟过的最高 IDE 大版本」。
  # 2026-10-28 实测上界 = IDEA 2026.1（build 261）。
  [string]$UntilBuild = '261.*'
)

$ErrorActionPreference = 'Stop'

$repo    = Split-Path -Parent $PSScriptRoot          # ...\neovim-cursor-pr
$srcDir  = Join-Path $repo 'src\main\java'
$resXml  = Join-Path $repo 'src\main\resources\META-INF\plugin.xml'
$libs    = 'D:\deepseekwork\_idea-anim\idea-libs'
$classes = Join-Path $repo 'build\classes'
$dist    = Join-Path $repo 'dist'

# 空串守卫：把空的 since-build / until-build 写进 idea-version，
# 前者会让老版本 IDE 也放行（装机后撞字节码锁），后者等于「全放行」。
# 两种都是「装上了但坏」—— 在打包阶段炸掉，比在用户机器上炸好。
if ([string]::IsNullOrWhiteSpace($SinceBuild)) { throw 'SinceBuild 为空：idea-version 缺下界' }
if ([string]::IsNullOrWhiteSpace($UntilBuild)) { throw 'UntilBuild 为空：发布必须显式声明兼容上界' }

if (-not (Test-Path $BaseJar)) { throw "容器 jar 不存在：$BaseJar" }
if (-not (Test-Path $libs))    { throw "idea-libs 不存在：$libs" }
if (-not (Test-Path $resXml))  { throw "plugin.xml 不存在：$resXml" }

# ---------- 1) 编译 ----------
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$cp      = (Get-ChildItem $libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
$sources = @(Get-ChildItem -Recurse -File -Filter '*.java' $srcDir | ForEach-Object { $_.FullName })
Write-Host "[1/4] javac：$($sources.Count) 个 .java，classpath $((Get-ChildItem $libs -Filter '*.jar').Count) 个 jar"

& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac 失败 (exit $LASTEXITCODE)" }

# ---------- 2) 注入 plugin.xml ----------
$xml = [System.IO.File]::ReadAllText($resXml)
if ($xml -notmatch '<idea-plugin>') { throw "plugin.xml 里找不到 <idea-plugin> 开标签" }
# 【兼容区间必须写成一对】since-build 与 until-build 在同一个 <idea-version> 标签上，
# 缺任意一个的后果都不是"报错"，而是"静默装上了但行为不对"：
#   缺 since-build → 2024.2 以前的 IDE 也放行 → 撞 --release 21 的字节码锁；
#   缺 until-build → 未来所有 IDE 全放行 → 平台 API 一移除就是 NoSuchMethodError。
# VBuildDecl 探针会守这整段（含"上界 >= 下界""只锁大版本"）。
$inject = "<idea-plugin>`r`n    <version>$Version</version>`r`n    <idea-version since-build=`"$SinceBuild`" until-build=`"$UntilBuild`" />"
$xml = $xml -replace '<idea-plugin>', $inject

$xmlDir = Join-Path $classes 'META-INF'
New-Item -ItemType Directory -Force -Path $xmlDir | Out-Null
# 不带 BOM 的 UTF-8：带 BOM 会让部分 XML 解析器报错
[System.IO.File]::WriteAllText((Join-Path $xmlDir 'plugin.xml'), $xml,
  (New-Object System.Text.UTF8Encoding($false)))
Write-Host "[2/4] plugin.xml 已注入 version=$Version since-build=$SinceBuild until-build=$UntilBuild"

# ---------- 3) 打包 ----------
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$outJar = Join-Path $dist $OutName
Copy-Item $BaseJar $outJar -Force
& jar --update --file $outJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar --update 失败 (exit $LASTEXITCODE)" }
Write-Host "[3/4] 已打包：$outJar  ($((Get-Item $outJar).Length) 字节)"

# ---------- 4) 自检 ----------
# 只验证"确实带上了版本号"这一条：缺它插件会静默不加载，是最贵的坑。
$verify = Join-Path $repo 'build\verify'
if (Test-Path $verify) { Remove-Item -Recurse -Force $verify }
New-Item -ItemType Directory -Force -Path $verify | Out-Null
Push-Location $verify
& jar --extract --file $outJar META-INF/plugin.xml
Pop-Location
$xmlPath = Join-Path $verify 'META-INF\plugin.xml'
$v = Select-String -Path $xmlPath -Pattern '<version>|<idea-version'
Write-Host "[4/4] 自检："
$v | ForEach-Object { Write-Host "      $($_.Line.Trim())" }
if (-not $v) { throw "自检失败：jar 内的 plugin.xml 没有 version / idea-version" }

# 逐个属性断言，缺一个就炸 —— 不能只靠上面那行"打印出来看着有"。
# 这里读的是【jar 里那份 XML】（而不是注入前的模板），所以能证明注入真的落地了。
$verifyXml = [System.IO.File]::ReadAllText($xmlPath)
foreach ($tag in @('since-build', 'until-build')) {
    $m = [regex]::Match($verifyXml, $tag + '="([^"]+)"')
    if (-not $m.Success) {
        throw "自检失败：jar 内 plugin.xml 缺少 $tag 属性（兼容区间必须两端都有，否则是「装上了但坏」）"
    }
    Write-Host "      $tag = $($m.Groups[1].Value)  OK"
}

Write-Host "`n完成。安装前必须先关闭 IDEA（它会锁住 jar）。"