# run-br-probe.ps1 —— t9 探针：R-11 的 <br/> 分行渲染验证
#
# 用法：pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-br-probe.ps1
#
# 安全：不使用 PowerShell 自动变量名（$home/$env/$args/$input/$PSHOME/$host/$pwd/$error/$matches）做普通赋值。

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs'
)

$ErrorActionPreference = 'Stop'
$tmpRoot = Join-Path $Repo 'build\probe-br'
$srcMain = Join-Path $Repo 'src\main\java'

function Step($m) { Write-Host "==> $m" }

$classpath = (Get-ChildItem -LiteralPath $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null

$fakeHome = Join-Path $tmpRoot 'fake-idea-home'
if (-not (Test-Path -LiteralPath $fakeHome)) { New-Item -ItemType Directory -Force -Path $fakeHome | Out-Null }
'{ "name": "IntelliJ IDEA", "version": "2024.2", "buildNumber": "242.0", "productCode": "IC", "dataDirectoryName": "IdeaIC2024.2", "svgIconPath": "bin/idea.svg", "launcherPath": "bin/idea.bat" }' |
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $fakeHome 'product-info.json')

$jvmArgs = @(
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
New-Item -ItemType Directory -Force -Path $classes, $probeOut | Out-Null

$srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcMain | ForEach-Object { $_.FullName })
Step "javac 源码树（$($srcs.Count) 个 .java）"
& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $classpath -d $classes $srcs
if ($LASTEXITCODE -ne 0) { throw '源码树编译失败' }

Step 'javac ProbeBrRendering'
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classes;$classpath" -d $probeOut (Join-Path $Repo 'tools\settings-probe\ProbeBrRendering.java')
if ($LASTEXITCODE -ne 0) { throw '探针编译失败' }

$snapshot = Join-Path $tmpRoot 'br-probe.txt'
Step '运行 ProbeBrRendering'
$raw = & java @jvmArgs -cp "$probeOut;$classes;$classpath" ProbeBrRendering 2>&1
$code = $LASTEXITCODE
Set-Content -LiteralPath $snapshot -Value ($raw | Out-String) -Encoding UTF8
$raw | ForEach-Object { Write-Host $_ }
Write-Host ''
Write-Host "  输出存档：$snapshot"
Write-Host "  exit=$code"
if ($code -ne 0) { throw "ProbeBrRendering 失败（exit $code）" }
Write-Host 'ALL BR PROBES OK'
