# run-settings-v2-probe.ps1 —— t3 探针一键复跑（布局 / 预设 / 正交性 / 吸附 / 动画时序 / Tools 菜单）
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-settings-v2-probe.ps1
#
# 退出码：0 = 全通过；非 0 = 有失败项
#
# 安全：本脚本不使用 PowerShell 自动变量名（$home/$env/$args/$input/$PSHOME/$host/$pwd/$error/$matches）做普通赋值。
#      函数名一律用「无连字符」的驼峰式 —— pwsh 7.6.6 上 Build-And-Probe 这类两段连字符名
#      能定义成功但调用时被解析成 Build-AndProbe（t2 实测踩过），改用驼峰避免。
#      所有临时产物都在 $tmpRoot（= build\ 下），绝不 cd 到仓库根、绝不在仓库根解包。

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs'
)

$ErrorActionPreference = 'Stop'
$tmpRoot = Join-Path $Repo 'build\probe-settings-v2'
$libsDir = $Libs
$srcMain = Join-Path $Repo 'src\main\java'

function Step($m) { Write-Host "==> $m" }

if (-not (Test-Path -LiteralPath $libsDir)) { throw "idea-libs 不存在：$libsDir" }
if (-not (Test-Path -LiteralPath $srcMain)) { throw "源码目录不存在：$srcMain" }

$classpath = (Get-ChildItem -LiteralPath $libsDir -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
Step "idea-libs jar 数 = $((Get-ChildItem -LiteralPath $libsDir -Filter '*.jar').Count)"

New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null

# 平台 UI 组件会找 installation home（product-info.json），所以给一个假安装目录。
# 变量名必须是 $fakeHome（$home 是 PowerShell 只读自动变量，赋值会静默失败）。
$fakeHome = Join-Path $tmpRoot 'fake-idea-home'
if (-not (Test-Path -LiteralPath $fakeHome)) { New-Item -ItemType Directory -Force -Path $fakeHome | Out-Null }
$productInfo = '{ "name": "IntelliJ IDEA", "version": "2024.2", "buildNumber": "242.0", "productCode": "IC", "dataDirectoryName": "IdeaIC2024.2", "svgIconPath": "bin/idea.svg", "launcherPath": "bin/idea.bat" }'
$productInfo | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $fakeHome 'product-info.json')

$swingArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Djava.awt.headless=true',
    "-Didea.home.path=$fakeHome",
    "-Didea.config.path=$(Join-Path $tmpRoot 'cfg')",
    "-Didea.system.path=$(Join-Path $tmpRoot 'sys')",
    '--add-opens=java.desktop/javax.swing=ALL-UNNAMED',
    '--add-opens=java.desktop/java.awt=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.swing=ALL-UNNAMED'
)

$classes = Join-Path $tmpRoot 'classes'
$probeOut = Join-Path $tmpRoot 'probe'
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
if (Test-Path -LiteralPath $probeOut) { Remove-Item -LiteralPath $probeOut -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
New-Item -ItemType Directory -Force -Path $probeOut | Out-Null

$srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcMain | ForEach-Object { $_.FullName })
Step "javac 源码树（$($srcs.Count) 个 .java）"
& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $classpath -d $classes $srcs
if ($LASTEXITCODE -ne 0) { throw '源码树编译失败' }

Step 'javac 探针 ProbeSettingsV2'
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classes;$classpath" -d $probeOut (Join-Path $Repo 'tools\settings-probe\ProbeSettingsV2.java')
if ($LASTEXITCODE -ne 0) { throw '探针编译失败' }

$snapshot = Join-Path $tmpRoot 'settings-v2-probe.txt'
Step '运行 ProbeSettingsV2'
$raw = & java @swingArgs -cp "$probeOut;$classes;$classpath" ProbeSettingsV2 2>&1
$code = $LASTEXITCODE
$text = ($raw | Out-String)
Set-Content -LiteralPath $snapshot -Value $text -Encoding UTF8
$raw | ForEach-Object { Write-Host $_ }

Write-Host ''
Write-Host "  输出存档：$snapshot"
Write-Host "  exit=$code"
if ($code -ne 0) { throw "ProbeSettingsV2 失败（exit $code）" }
Write-Host ''
Write-Host 'ALL SETTINGS-V2 PROBES OK'
