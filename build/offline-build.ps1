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
  # 2024.2+ 平台的 build 号；until-build 刻意留空 = 兼容所有后续版本
  [string]$SinceBuild = '242'
)

$ErrorActionPreference = 'Stop'

$repo    = Split-Path -Parent $PSScriptRoot          # ...\neovim-cursor-pr
$srcDir  = Join-Path $repo 'src\main\java'
$resXml  = Join-Path $repo 'src\main\resources\META-INF\plugin.xml'
$libs    = 'D:\deepseekwork\_idea-anim\idea-libs'
$classes = Join-Path $repo 'build\classes'
$dist    = Join-Path $repo 'dist'

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
$inject = "<idea-plugin>`r`n    <version>$Version</version>`r`n    <idea-version since-build=`"$SinceBuild`" />"
$xml = $xml -replace '<idea-plugin>', $inject

$xmlDir = Join-Path $classes 'META-INF'
New-Item -ItemType Directory -Force -Path $xmlDir | Out-Null
# 不带 BOM 的 UTF-8：带 BOM 会让部分 XML 解析器报错
[System.IO.File]::WriteAllText((Join-Path $xmlDir 'plugin.xml'), $xml,
  (New-Object System.Text.UTF8Encoding($false)))
Write-Host "[2/4] plugin.xml 已注入 version=$Version since-build=$SinceBuild"

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
$v = Select-String -Path (Join-Path $verify 'META-INF\plugin.xml') -Pattern '<version>|<idea-version'
Write-Host "[4/4] 自检："
$v | ForEach-Object { Write-Host "      $($_.Line.Trim())" }
if (-not $v) { throw "自检失败：jar 内的 plugin.xml 没有 version / idea-version" }

Write-Host "`n完成。安装前必须先关闭 IDEA（它会锁住 jar）。"