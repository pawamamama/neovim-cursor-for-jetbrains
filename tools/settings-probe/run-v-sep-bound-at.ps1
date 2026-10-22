# run-v-sep-bound-at.ps1 —— 针对某个 git 修订版跑 VSepBound（审查用：区分「交付版本的问题」与「工作区未提交改动」）
# 用法：pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-v-sep-bound-at.ps1 -Rev c03ea7f
param(
    [Parameter(Mandatory=$true)][string]$Rev,
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs'
)
$ErrorActionPreference = 'Stop'
$repoRoot = $Repo
$tmpRoot = Join-Path $repoRoot ('build\sep-bound-at-' + $Rev)
$srcDir = Join-Path $tmpRoot 'src'
$classesDir = Join-Path $tmpRoot 'classes'
$probeDir = Join-Path $tmpRoot 'probe'
$fakeHome = Join-Path $tmpRoot 'fake-home'
$toolDir = Join-Path $repoRoot 'tools\settings-probe'

$cp = (Get-ChildItem -LiteralPath $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'

if (Test-Path -LiteralPath $tmpRoot) { Remove-Item -LiteralPath $tmpRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $srcDir, $classesDir, $probeDir, $fakeHome | Out-Null
$productInfo = '{ "name": "IntelliJ IDEA", "version": "2024.2", "buildNumber": "242.0", "productCode": "IC", "dataDirectoryName": "IdeaIC2024.2", "svgIconPath": "bin/idea.svg", "launcherPath": "bin/idea.bat" }'
$productInfo | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $fakeHome 'product-info.json')

Push-Location $repoRoot
git archive $Rev src/main/java | tar -x -C $srcDir
Pop-Location

$srcMain = Join-Path $srcDir 'src\main\java'
$srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcMain | ForEach-Object { $_.FullName })
Write-Host "==> [$Rev] javac 源码树（$($srcs.Count) 个 .java）"
& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $classesDir $srcs 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "[$Rev] 源码树编译失败（该版本可能不含本审查用到的 API）" }

$need = @('V0Env.java', 'VUi.java', 'VSepBound.java') | ForEach-Object { Join-Path $toolDir $_ }
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classesDir;$cp" -d $probeDir $need 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "[$Rev] 探针编译失败" }

$jvm = @('-Dfile.encoding=UTF-8','-Djava.awt.headless=true',"-Didea.home.path=$fakeHome",
    '--add-opens=java.desktop/javax.swing=ALL-UNNAMED','--add-opens=java.desktop/java.awt=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED','--add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.swing=ALL-UNNAMED','--add-opens=java.desktop/java.awt.event=ALL-UNNAMED')

Write-Host "==> [$Rev] run VSepBound"
& java @jvm -cp "$probeDir;$classesDir;$cp" VSepBound 2>&1 |
    Where-Object { $_ -notmatch 'Do not use an alarm' -and $_ -notmatch '已停用' }
exit $LASTEXITCODE
