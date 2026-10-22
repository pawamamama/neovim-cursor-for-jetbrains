# run-v-sep-bound.ps1 —— 只编译并运行 VSepBound（F2 分隔符迁移的边界审查）
#
# 用法：pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-v-sep-bound.ps1
# 退出码：0 = 全 PASS；非 0 = 有 FAIL 或编译失败
#
# 与 build-v-probes.ps1 独立：审查期间 VSep.java 被并发改写，本脚本只跑本审查自己的探针，
# 保证结论可复现且不受别人改动影响。
param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs'
)
$ErrorActionPreference = 'Stop'

$repoRoot = $Repo
$tmpRoot = Join-Path $repoRoot 'build\sep-bound'
$classesDir = Join-Path $tmpRoot 'classes'
$probeDir = Join-Path $tmpRoot 'probe'
$fakeHome = Join-Path $tmpRoot 'fake-home'
$srcMain = Join-Path $repoRoot 'src\main\java'
$toolDir = Join-Path $repoRoot 'tools\settings-probe'

if (-not (Test-Path -LiteralPath $Libs)) { throw "idea-libs 不存在：$($Libs)" }

$cp = (Get-ChildItem -LiteralPath $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'

New-Item -ItemType Directory -Force -Path $tmpRoot, $fakeHome | Out-Null
$productInfo = '{ "name": "IntelliJ IDEA", "version": "2024.2", "buildNumber": "242.0", "productCode": "IC", "dataDirectoryName": "IdeaIC2024.2", "svgIconPath": "bin/idea.svg", "launcherPath": "bin/idea.bat" }'
$productInfo | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $fakeHome 'product-info.json')

$jvm = @(
    '-Dfile.encoding=UTF-8',
    '-Djava.awt.headless=true',
    "-Didea.home.path=$fakeHome",
    '--add-opens=java.desktop/javax.swing=ALL-UNNAMED',
    '--add-opens=java.desktop/java.awt=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.swing=ALL-UNNAMED',
    '--add-opens=java.desktop/java.awt.event=ALL-UNNAMED'
)

# 每次都重编源码树：审查期间生产代码可能正在被改，必须针对当前工作树
if (Test-Path -LiteralPath $classesDir) { Remove-Item -LiteralPath $classesDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
$srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcMain | ForEach-Object { $_.FullName })
Write-Host "==> javac 源码树（$($srcs.Count) 个 .java）"
& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $classesDir $srcs 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw '源码树编译失败' }

if (Test-Path -LiteralPath $probeDir) { Remove-Item -LiteralPath $probeDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
$need = @('V0Env.java', 'VUi.java', 'VSepBound.java') | ForEach-Object { Join-Path $toolDir $_ }
Write-Host '==> javac 探针（V0Env + VSepBound）'
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classesDir;$cp" -d $probeDir $need 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'VSepBound 探针编译失败' }

Write-Host '==> run VSepBound'
& java @jvm -cp "$probeDir;$classesDir;$cp" VSepBound 2>&1 |
    Where-Object { $_ -notmatch 'Do not use an alarm' -and $_ -notmatch '已停用' }
exit $LASTEXITCODE
