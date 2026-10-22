# run-parity.ps1 —— 「零观感优化·批次1」parity 复现脚本（t4 verifier 产出，可原样复跑）
#
# 作用：把基线提交（默认 321b0a0）导出到临时目录（**不覆盖工作区**），
#       分别编译「基线源码树」与「当前工作区源码树」，用同一份 harness 各跑一次，
#       输出 stdout 到临时文件，逐个做逐字节比对并打印 SHA256。
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\parity\run-parity.ps1
#
# 说明：harness 源码 = 契约附录 A 的两份（已逐行照抄到 tools/parity/）
#       + tools/TrailParityTest.java（t4 verifier 自建，只用 core 包）。

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$BaseRef = '321b0a0',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs',
    [string]$Work = (Join-Path $env:TEMP 'nc-t4-parity')
)

$ErrorActionPreference = 'Stop'

function Step($msg) { Write-Host "==> $msg" }

# ---------- 0. 临时区 ----------
if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
New-Item -ItemType Directory -Force $Work | Out-Null
$baseSrcRoot = Join-Path $Work 'base-src'
$zip = Join-Path $Work 'base-src.zip'

# ---------- 1. 基线源码（git archive，不碰工作区） ----------
Step "导出基线 $BaseRef 的 src/main/java 到 $baseSrcRoot"
& git -C $Repo archive --format=zip -o $zip $BaseRef src/main/java
if ($LASTEXITCODE -ne 0) { throw "git archive 失败" }
Expand-Archive -Path $zip -DestinationPath $baseSrcRoot -Force
$baseSrc = Join-Path $baseSrcRoot 'src\main\java'
$baseCount = (Get-ChildItem -Recurse -File -Filter *.java $baseSrc).Count
Step "基线 java 文件数 = $baseCount"
$newSrc = Join-Path $Repo 'src\main\java'
$newCount = (Get-ChildItem -Recurse -File -Filter *.java $newSrc).Count
Step "新版 java 文件数 = $newCount"

# ---------- 2. classpath ----------
$cp = (Get-ChildItem $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
Step "idea-libs jar 数 = $((Get-ChildItem $Libs -Filter '*.jar').Count)，classpath 长度 = $($cp.Length)"

$harnesses = @(
    (Join-Path $Repo 'tools\parity\PhysicsParity.java'),
    (Join-Path $Repo 'tools\parity\GeometryParity.java'),
    (Join-Path $Repo 'tools\TrailParityTest.java')
)

function Run-Tree([string]$srcRoot, [string]$tag) {
    $out = Join-Path $Work "classes-$tag"
    $hout = Join-Path $Work "harness-$tag"
    New-Item -ItemType Directory -Force $out, $hout | Out-Null

    Step "[$tag] javac 源码树（$srcRoot）"
    $srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcRoot | ForEach-Object { $_.FullName })
    & javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $out $srcs
    if ($LASTEXITCODE -ne 0) { throw "[$tag] 源码树编译失败" }

    Step "[$tag] javac harness"
    & javac --release 21 -nowarn -encoding UTF-8 -classpath "$out;$cp" -d $hout $harnesses
    if ($LASTEXITCODE -ne 0) { throw "[$tag] harness 编译失败" }

    foreach ($main in 'PhysicsParity', 'GeometryParity', 'TrailParityTest') {
        $txt = Join-Path $Work "$tag-$main.txt"
        & java '-Dfile.encoding=UTF-8' -cp "$hout;$out;$cp" $main > $txt
        if ($LASTEXITCODE -ne 0) { throw "[$tag] $main 运行失败" }
        $h = (Get-FileHash $txt -Algorithm SHA256).Hash
        $n = (Get-Content $txt).Count
        "{0,-16} {1,-16} 行数={2,-5} SHA256={3}" -f $tag, $main, $n, $h
    }
}

Run-Tree $baseSrc 'base'
Run-Tree $newSrc 'new'

# ---------- 3. 逐字节比对 ----------
Step 'parity 比对（git diff --no-index --exit-code）'

# Declarative exemption: only the SIGNATURE LINE may differ.
#
# 0.9.9 subpixel fix changed the vertex container Polygon(int[]) -> double[]:
#   computeOffsetDirections(Polygon, dx, dy) -> (double[] px, double[] py, dx, dy)
# So the harness emits a different first line (### signature=Polygon -> double[]).
#
# This is NOT a geometry change: every dx/dy raw bit must stay identical.
# We allow exactly one changed +/- pair, and it must match the regex below.
# Any other difference is still FAIL.
# (ASCII only in comments: non-ASCII parentheses broke the PowerShell parser.)
$allowedSignatureDiff = @{
    'GeometryParity' = '^### signature=(Polygon|double\[\])$'
}
$fail = 0
foreach ($main in 'PhysicsParity', 'GeometryParity', 'TrailParityTest') {
    $a = Join-Path $Work "base-$main.txt"
    $b = Join-Path $Work "new-$main.txt"
    $diffLines = @(& git diff --no-index --unified=0 --no-color $a $b)
    $code = $LASTEXITCODE

    if ($code -eq 0) {
        "{0,-16} PARITY OK   (diff empty)" -f $main
        continue
    }

    # Collect real content lines (skip diff headers / hunk markers)
    $changed = @($diffLines | Where-Object {
        ($_ -match '^[+-]') -and ($_ -notmatch '^(\+\+\+|---)')
    })
    $pattern = $allowedSignatureDiff[$main]
    $allAllowed = $false
    if ($pattern -and $changed.Count -eq 2) {
        $oldLine = $changed[0] -replace '^-', ''
        $newLine = $changed[1] -replace '^\+', ''
        $allAllowed = ($oldLine -match $pattern) -and ($newLine -match $pattern)
    }

    if ($allAllowed) {
        "{0,-16} PARITY OK   (signature line only; geometry bits identical)" -f $main
        "{0,-16}   base: {1}" -f '', $changed[0]
        "{0,-16}   new : {1}" -f '', $changed[1]
    } else {
        "{0,-16} PARITY FAIL (diff not empty)" -f $main
        $diffLines | ForEach-Object { "    $_" }
        $fail++
    }
}
Write-Host ''
Step '输出去向'
Get-ChildItem $Work -Filter '*.txt' | Select-Object -ExpandProperty FullName
if ($fail -gt 0) { throw "parity 失败 $fail 项" }
Step 'ALL PARITY OK'
Write-Host ''
Step '输出去向'
Get-ChildItem $Work -Filter '*.txt' | Select-Object -ExpandProperty FullName
if ($fail -gt 0) { throw "parity 失败 $fail 项" }
Step 'ALL PARITY OK'
