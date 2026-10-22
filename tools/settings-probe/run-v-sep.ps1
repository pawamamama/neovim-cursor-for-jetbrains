# run-v-sep.ps1 —— 只编译并运行 VSep 探针（F2 分隔符迁移边界）
#
# 用法：pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-v-sep.ps1
# 退出码：0 = VSep 全 PASS；非 0 = FAIL 或编译失败
param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs'
)
$ErrorActionPreference = 'Stop'

$repoRoot = $Repo
$tmpRoot = Join-Path $repoRoot 'build\v2-probe'
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

if (-not (Test-Path -LiteralPath $classesDir)) {
    New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
    $srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcMain | ForEach-Object { $_.FullName })
    Write-Host "==> javac 源码树（$($srcs.Count) 个 .java）"
    & javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $classesDir $srcs 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '源码树编译失败' }
}

New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
$need = @('V0Env.java', 'VUi.java', 'VSep.java') | ForEach-Object { Join-Path $toolDir $_ }
Write-Host '==> javac 探针（V0Env + VUi + VSep）'
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classesDir;$cp" -d $probeDir $need 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'VSep 探针编译失败' }

Write-Host '==> run VSep'
& java @jvm -cp "$probeDir;$classesDir;$cp" VSep 2>&1 |
    Where-Object { $_ -notmatch 'Do not use an alarm' -and $_ -notmatch '已停用' }
exit $LASTEXITCODE
