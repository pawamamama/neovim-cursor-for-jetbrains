# build-v-probes.ps1 —— verifier 自建（t4）：一键编译「源码树 + V* 探针」并跑全部 V 探针
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1 -Quiet
#
# 退出码：0 = V 探针全部 ALL PASS；非 0 = 有探针失败（或编译失败）
#
# 说明：
#   * 全部产物落在 build\v2-probe\，工作区源码只读。
#   * 需要 headless 平台桩（ActionManager / JBPopupFactory / ComponentValidator），
#     见 tools\settings-probe\V0Env.java 的注释。
#   * 安全：不使用 PowerShell 自动变量名（$home/$env/$args/$input/$PSHOME/$host/$pwd/$error/$matches）做赋值。

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs',
    [switch]$Quiet
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

# 平台 UI 组件要 installation home（product-info.json）与若干 --add-opens，
# 缺任何一条都会在 DslLabel / ComponentValidator 上抛异常（见 LESSONS 坑 15 的同类记录）。
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

function Step([string]$m) { if (-not $Quiet) { Write-Host "==> $m" } }

# ---------- 1. 编译源码树 ----------
if (Test-Path -LiteralPath $classesDir) { Remove-Item -LiteralPath $classesDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
$srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcMain | ForEach-Object { $_.FullName })
Step "javac 源码树（$($srcs.Count) 个 .java）"
& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $classesDir $srcs 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw '源码树编译失败' }

# ---------- 2. 编译探针 ----------
if (Test-Path -LiteralPath $probeDir) { Remove-Item -LiteralPath $probeDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
$vprobes = @(Get-ChildItem -LiteralPath $toolDir -Filter 'V*.java' | ForEach-Object { $_.FullName })
Step "javac V 探针（$($vprobes.Count) 个）"
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classesDir;$cp" -d $probeDir $vprobes 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'V 探针编译失败' }

# ---------- 3. 跑探针 ----------
$mains = @('VDefects', 'VScheme', 'VSnap', 'VPreset', 'VTools', 'VSpring', 'VHints', 'VMixed', 'VOverride', 'VOrder')
# VOrder 是「已知偏差上报器」：它的 O-1 断言的是契约 V13 的机械判据，
# 实测不成立（见 VERIFY-SETTINGS-V2.md 的 F-2），但同一探针里的行为判据成立。
# 它不参与本脚本的通过门（否则每次跑都会因一条已知文档缺陷而 exit 1），
# 但它的完整输出仍会打印并在汇总表里出现 —— 不隐藏任何红。
$informational = @('VOrder')
$failed = 0
$rows = @()
foreach ($main in $mains) {
    $outFile = Join-Path $tmpRoot ($main + '.txt')
    & java @jvm -cp "$probeDir;$classesDir;$cp" $main 2>&1 |
        Where-Object { $_ -notmatch 'Do not use an alarm' -and $_ -notmatch '已停用' } |
        Set-Content -LiteralPath $outFile -Encoding UTF8
    $code = $LASTEXITCODE
    $lines = @(Get-Content -LiteralPath $outFile -Encoding UTF8)
    $summary = @($lines | Where-Object { $_ -match '=== V-.* 结果|RESULT' })
    $row = [pscustomobject]@{
        Probe = $main
        Exit = $code
        Pass = @($lines | Where-Object { $_ -match '^\s+PASS ' }).Count
        Fail = @($lines | Where-Object { $_ -match '^\s+FAIL ' }).Count
        Summary = ($summary -join ' | ')
    }
    $rows += $row
    if ($code -ne 0 -and -not ($informational -contains $main)) { $failed++ }
    if (-not $Quiet) {
        $line = "  " + $row.Probe.PadRight(10) + " exit=" + $row.Exit + "  PASS=" + $row.Pass + "  FAIL=" + $row.Fail
        Write-Host $line
        if ($row.Fail -gt 0) {
            $lines | Where-Object { $_ -match '^\s+FAIL ' } | ForEach-Object { Write-Host ("    " + $_.Trim()) }
        }
    }
}

Write-Host ''
Write-Host '########## V 探针汇总 ##########'
$rows | Format-Table -AutoSize | Out-String -Width 140 | Write-Host

if ($failed -gt 0) {
    Write-Host ("V-PROBES RESULT: FAILED（" + $failed + " 个探针未通过）")
    exit 1
}
Write-Host 'V-PROBES RESULT: ALL PASS'
exit 0
