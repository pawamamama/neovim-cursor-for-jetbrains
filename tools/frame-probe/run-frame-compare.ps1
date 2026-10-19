# run-frame-compare.ps1 —— verifier 自建：帧**间隔分布**对照（基线 HEAD vs 当前工作区）
#
# 与 tools\probe\run-probe.ps1 的区别：
#   run-probe.ps1 数的是「1500ms 里投递了几帧」（平均帧率）；
#   本脚本量的是**相邻帧间隔**的 p50 / p95 / p99 / maxGap —— 用户抱怨的"卡"是后者。
#
# 判据（LESSONS 3.2）：每格跑 N 轮，噪声**只会**把性能压低，
# 所以取跨轮区间比较；若两边区间不重叠且差值明显超过一个理论帧间隔，才算系统性差异。
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\frame-probe\run-frame-compare.ps1
#   -Rounds 3（默认）  -BaseRef 8906609（默认 = 0.9.3-imegeom，即本批改动前的 HEAD）
#
# 安全：不使用 PowerShell 自动变量名（$home/$env/$args/$input/$PSHOME/$host/$pwd/$error/$matches）做赋值。

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$BaseRef = '8906609',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs',
    [string]$Work = (Join-Path $Repo 'build\verify-t4\frame'),
    [int]$Rounds = 3
)

$ErrorActionPreference = 'Stop'
function Step($m) { Write-Host "==> $m" }

if (Test-Path -LiteralPath $Work) { Remove-Item -LiteralPath $Work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Work | Out-Null

$cp = (Get-ChildItem -LiteralPath $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
Step "idea-libs jar 数 = $((Get-ChildItem -LiteralPath $Libs -Filter '*.jar').Count)"

# ---------- 基线源码（git archive，不碰工作区）----------
$baseSrcRoot = Join-Path $Work 'base-src'
$zipPath = Join-Path $Work 'base-src.zip'
Step "导出基线 $BaseRef 的 src/main/java"
& git -C $Repo archive --format=zip -o $zipPath $BaseRef src/main/java
if ($LASTEXITCODE -ne 0) { throw 'git archive 失败' }
Expand-Archive -LiteralPath $zipPath -DestinationPath $baseSrcRoot -Force

$probe = Join-Path $Repo 'tools\frame-probe\com\github\yyyolo7a79\neovidecursor\ui\FrameIntervalProbe.java'
$trees = @(
    @{ Tag = 'base'; Src = (Join-Path $baseSrcRoot 'src\main\java') },
    @{ Tag = 'new'; Src = (Join-Path $Repo 'src\main\java') }
)

foreach ($t in $trees) {
    $tag = $t.Tag
    $cls = Join-Path $Work "classes-$tag"
    $pb = Join-Path $Work "probe-$tag"
    New-Item -ItemType Directory -Force -Path $cls, $pb | Out-Null

    $srcs = @(Get-ChildItem -Recurse -File -Filter *.java $t.Src | ForEach-Object { $_.FullName })
    Step "[$tag] javac 源码树（$($srcs.Count) 个 .java）"
    & javac --release 21 -nowarn -encoding UTF-8 -classpath $cp -d $cls $srcs
    if ($LASTEXITCODE -ne 0) { throw "[$tag] 源码树编译失败" }

    Step "[$tag] javac FrameIntervalProbe"
    & javac --release 21 -nowarn -encoding UTF-8 -classpath "$cls;$cp" -d $pb $probe
    if ($LASTEXITCODE -ne 0) { throw "[$tag] 探针编译失败" }

    $outFile = Join-Path $Work "$tag-frame.txt"
    Step "[$tag] 运行（$Rounds 轮）"
    $jvmArgs = @('-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '--add-opens', 'java.desktop/java.awt.event=ALL-UNNAMED')
    & java @jvmArgs -cp "$pb;$cls;$cp" com.github.yyyolo7a79.neovidecursor.ui.FrameIntervalProbe $Rounds 1500 *> $outFile
    "  exit=$LASTEXITCODE  输出 → $outFile"
}

Write-Host ''
Write-Host '============ 基线 $BaseRef ============'
Get-Content -LiteralPath (Join-Path $Work 'base-frame.txt') | Where-Object { $_ -match '^(ROUND|SUMMARY|PROBE)' }
Write-Host ''
Write-Host '============ 当前工作区 ============'
Get-Content -LiteralPath (Join-Path $Work 'new-frame.txt') | Where-Object { $_ -match '^(ROUND|SUMMARY|PROBE)' }
Write-Host ''
Step '探针结束（判定见 VERIFY-SETTINGS.md）'
