# run-audit.ps1 —— 「零观感优化·批次1」静态审计（t4 verifier 产出，可原样复跑）
#
# 依赖 run-parity.ps1 已把基线源码导出到 $Work\base-src。若不存在则自行导出。
#
# 检查项：
#   A. 19 项观感常量逐项对照（基线 vs 新版，逐字）
#   B. 数值字面量清单：git diff -U0 中所有含数字的 +/- 行（人工归属）
#   C. RenderScheduler.java 的数字字面量清单（对照契约 §4.2 W3-1/W3-2/W3-3）
#   D. 死字段：src/** 与全仓库 grep
#   E. 调度语义结构判定：invokeLater 调用点数、new Thread 数、批量 tick 反例
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\parity\run-audit.ps1

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$BaseRef = '321b0a0',
    [string]$Work = (Join-Path $env:TEMP 'nc-t4-parity')
)

$ErrorActionPreference = 'Stop'
$pk = 'com\github\yyyolo7a79\neovidecursor'
$baseRoot = Join-Path $Work "base-src\src\main\java\$pk"
$newRoot = Join-Path $Repo "src\main\java\$pk"
if (-not (Test-Path $baseRoot)) { throw "基线源码树不存在：$baseRoot（先跑 run-parity.ps1）" }

function Hdr($t) { Write-Host ''; Write-Host "========== $t ==========" }

# ---------------------------------------------------------------- A
Hdr 'A. 19 项观感常量逐项对照（基线 vs 新版）'
$items = @(
    @('core/NeovideConfig.java', 'glowLayers = [0-9]+'),
    @('core/NeovideConfig.java', 'glowOpacity = [0-9.]+f'),
    @('core/NeovideConfig.java', 'shadowBlurFactor = [0-9.]+f'),
    @('core/NeovideConfig.java', 'trailFactors = \{[^}]+\}'),
    @('core/NeovideConfig.java', 'float animationLength = [0-9.]+f'),
    @('core/NeovideConfig.java', 'shortAnimationLength = [0-9.]+f'),
    @('core/NeovideConfig.java', 'trailSize = [0-9.]+f'),
    @('core/NeovideConfig.java', 'typingMaxChars = [0-9.]+f'),
    @('core/NeovideConfig.java', 'cursorWidth = [0-9.]+f'),
    @('core/NeovideConfig.java', 'maxTrailDistanceFactor = [0-9.]+f'),
    @('core/NeovideConfig.java', 'animationResetThreshold = [0-9.]+f'),
    @('core/NeovideConfig.java', 'afterimageLifetime = [0-9.]+f'),
    @('core/NeovideConfig.java', 'alwaysHighFps = (true|false)'),
    @('core/NeovideConfig.java', 'hideNativeCaret = (true|false)'),
    @('ui/CaretAnimator.java', 'int ACTIVE_DELAY_MS = [0-9]+'),
    @('ui/CaretAnimator.java', 'int IDLE_DELAY_MS = [0-9]+'),
    @('ui/CaretAnimator.java', 'int IDLE_UNFOCUSED_DELAY_MS = [0-9]+'),
    @('ui/CaretAnimator.java', 'int MAX_ADAPTIVE_DELAY_MS = [0-9]+'),
    @('ui/CaretAnimator.java', 'MAX_DT = [0-9.]+ / [0-9]+')
)
$diffs = 0
foreach ($it in $items) {
    $rel = $it[0].Replace('/', '\')
    $pat = $it[1]
    $bf = Join-Path $baseRoot $rel
    $nf = Join-Path $newRoot $rel
    $b = ((Select-String -Path $bf -Pattern $pat | ForEach-Object { $_.Line.Trim() }) -join ' || ')
    $n = ((Select-String -Path $nf -Pattern $pat | ForEach-Object { $_.Line.Trim() }) -join ' || ')
    $same = if ($b -eq $n) { 'SAME' } else { $diffs++; 'DIFF' }
    "{0,-8} {1,-34} | {2}" -f $same, $pat, $n
    if ($same -eq 'DIFF') { "         基线 = $b" }
}
"---- 19 项中 DIFF 数 = $diffs ----"

# ---------------------------------------------------------------- B
Hdr 'B. git diff -U0 HEAD -- src/ 中所有含数字的 +/- 行（每条需归属）'
$diffTxt = Join-Path $Work 'srcdiff-u0.txt'
# 【不要把 git diff 直接管道给 Set-Content】diff 为空时管道无输入，Set-Content
# 根本不创建文件，紧接着的 Get-Content 会抛「找不路径」把整个脚本打成 exit 1
# —— 看上去像审计失败，实际是「没有任何改动」这个正常状态。
# 先落到变量再判空写入，两种情况都能继续往下走。
$diffLines = @(& git -C $Repo diff -U0 HEAD -- src/)
if ($diffLines.Count -eq 0) {
    Set-Content -Path $diffTxt -Value '' -Encoding UTF8
    "文件 = $diffTxt  行数 = 0"
    '  （git diff HEAD -- src/ 为空：本工作区相对 HEAD 没有未提交的源码改动）'
} else {
    Set-Content -Path $diffTxt -Value $diffLines -Encoding UTF8
    "文件 = $diffTxt  行数 = $((Get-Content $diffTxt).Count)"
    Get-Content $diffTxt | Select-String '^[+-].*[0-9]' | ForEach-Object { $_.Line }
}

# ---------------------------------------------------------------- C
Hdr 'C. RenderScheduler.java 数字字面量清单（W3 归属）'
$rs = Join-Path $Repo 'src\main\java\com\github\yyyolo7a79\neovidecursor\ui\RenderScheduler.java'
$ln = 0
Get-Content -Encoding UTF8 $rs | ForEach-Object {
    $ln++
    if ($_ -match '[0-9]') { "{0,4}: {1}" -f $ln, $_.Trim() }
}

# ---------------------------------------------------------------- D
Hdr 'D-1 死字段 grep：src/**（等价写法，契约 §3 原文的 -Recurse 在 pwsh 7 不可用）'
$pat = 'cursorDisappearDelay|cursorFadeOutDuration|snapAnimationLength|useHardSnap|leadingSnapFactor|shortJumpMaxChars|typingMergeChars'
$newHits = @(Get-ChildItem -Recurse -Filter *.java (Join-Path $Repo 'src') | Select-String -Pattern $pat)
"新版 src/** 命中 = $($newHits.Count)"
$newHits | ForEach-Object { "  $($_.Path):$($_.LineNumber): $($_.Line.Trim())" }

Hdr "D-2 同一 grep 在基线 $BaseRef 的 src/** 上（对照）"
$baseHits = @(Get-ChildItem -Recurse -Filter *.java (Join-Path $Work 'base-src\src') | Select-String -Pattern $pat)
"基线 src/** 命中 = $($baseHits.Count)"
$baseHits | ForEach-Object { "  $($_.Path):$($_.LineNumber): $($_.Line.Trim())" }

Hdr 'D-3 同一 grep 在**整个仓库**（含文档，用于说明 7 字段的残留提及）'
$repoHits = @(Get-ChildItem -Recurse -File $Repo -Exclude *.jar |
    Where-Object { $_.FullName -notmatch '\\\.git\\' } |
    Select-String -Pattern $pat)
"全仓库（排除 .git 与 jar）命中 = $($repoHits.Count)"
$repoHits | Group-Object { $_.Path.Replace($Repo, '') } | Sort-Object Name |
    ForEach-Object { "  {0,-70} {1}" -f $_.Name, $_.Count }

Hdr 'D-4 契约 §3 原文写法实测（证明 -Recurse 在 pwsh 7 不可用）'
try {
    Select-String -Path (Join-Path $Repo 'src') -Include *.java -Recurse -Pattern 'zzz_never_matches_zzz' -ErrorAction Stop | Out-Null
    '  意外：未报错'
} catch {
    "  报错信息：$($_.Exception.Message)"
}

# ---------------------------------------------------------------- E
Hdr 'E-1 RenderScheduler 中 invokeLater 出现处（含注释）与调用点'
$rsLines = Get-Content -Encoding UTF8 $rs
$ln = 0
$rsLines | ForEach-Object { $ln++; if ($_ -match 'invokeLater') { "{0,4}: {1}" -f $ln, $_.Trim() } }
$callSites = @(Select-String -Path $rs -Pattern 'invokeLater\s*\(').Count
"invokeLater( 出现次数（含注释） = $callSites"
"invokeLater(RenderScheduler:: 命中 = " + @(Select-String -Path $rs -Pattern 'invokeLater\s*\(\s*RenderScheduler::').Count
"runDue|FRAME_PENDING 命中 = " + @(Select-String -Path $rs -Pattern 'runDue|FRAME_PENDING').Count

Hdr 'E-2 per-animator 派发循环（含行号）'
for ($i = 156; $i -lt $rsLines.Count; $i++) { "{0,4}: {1}" -f ($i + 1), $rsLines[$i] }

Hdr 'E-3 全仓库 src/** 中 new Thread 出现处'
$threadHits = @(Get-ChildItem -Recurse -Filter *.java (Join-Path $Repo 'src') | Select-String -Pattern 'new Thread')
"new Thread 命中 = $($threadHits.Count)"
$threadHits | ForEach-Object { "  $($_.Path):$($_.LineNumber): $($_.Line.Trim())" }

Hdr 'E-4 批量 tick 反例扫描：Runnable 体内的 for / forEach'
"RenderScheduler 中 'for (' 命中 = " + @(Select-String -Path $rs -Pattern 'for \(').Count
"RenderScheduler 中 'forEach' 命中 = " + @(Select-String -Path $rs -Pattern 'forEach').Count
"RenderScheduler 中 'ANIMATORS' 出现处："
$ln = 0; $rsLines | ForEach-Object { $ln++; if ($_ -match 'ANIMATORS') { "{0,4}: {1}" -f $ln, $_.Trim() } }
"全 src/** 中同时引用 2 个以上 animator 的 tick 调用扫描（runTick 调用点）："
@(Get-ChildItem -Recurse -Filter *.java (Join-Path $Repo 'src') | Select-String -Pattern 'runTick') |
    ForEach-Object { "  $($_.Path):$($_.LineNumber): $($_.Line.Trim())" }

Write-Host ''
Write-Host '==> run-audit.ps1 结束'
