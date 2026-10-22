# run-defaults-gate.ps1 -- 默认值金表门禁
#
# 作用：把 NeovideConfig 的【全部】public 默认值 dump 成稳定文本，
#       与仓库里冻结的金表 tools/parity/defaults-golden.txt 逐字节比对。
#
# 为什么需要它（第三轮审查 gate-auditor 指出的漏洞）：
#   TrailParityTest 只快照 11 个字段，run-audit A 段覆盖 14 个，
#   而 NeovideConfig 共 24 个字段 —— 12 个观感字段完全不在任何关卡视野里：
#     tailColor / shadowColor / tailOpacity / shadowBlurFactor / useShadow /
#     overlayFadeOut / cursorDisappearDelay / cursorFadeOutDuration / trailMode /
#     afterimageLifetime / alwaysHighFps / hideNativeCaret
#   改了它们门禁全绿。
#
# 为什么不用「diff 正则」：
#   上一轮试过，被构造 13 个反例全部绕过（0.11f -> 0.12f、-> (0.12f)、
#   把新值移到新增行、trailFactors 数组、tailColor 字符串、trailMode 枚举...）。
#   文本差异无法表达语义。本门禁读的是【运行期的值】：
#     · 0.11f / 0.1100f / 1.1e-1f -> 同样输出，零误报
#     · 任何真改 -> 输出变化
#     · 新增字段 -> 自动出现，判据永不失效
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools/parity/run-defaults-gate.ps1
#   pwsh ... -UpdateGolden     # 显式更新金表（改默认值时必须走这一步，形成审计痕迹）

param(
    [string]$Repo = "D:\deepseekwork\neovim-cursor-pr",
    [string]$Libs = "D:\deepseekwork\_idea-anim\idea-libs",
    [switch]$UpdateGolden
)

$ErrorActionPreference = "Stop"
function Step($m) { Write-Host "==> $m" }

$goldenPath = Join-Path $Repo "tools\parity\defaults-golden.txt"
$dumpSrc    = Join-Path $Repo "tools\parity\DefaultsDump.java"
$work       = Join-Path $Repo "build\defaults-gate"
$srcMain    = Join-Path $Repo "src\main\java"

if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force $work | Out-Null

$cp = (Get-ChildItem $Libs -Filter "*.jar" | ForEach-Object { $_.FullName }) -join ";"

Step "编译源码树"
$srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcMain | ForEach-Object { $_.FullName })
& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $work $srcs 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "源码树编译失败" }

Step "编译 DefaultsDump"
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$work;$cp" -d $work $dumpSrc 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "DefaultsDump 编译失败" }

Step "dump 默认值"
$outFile = Join-Path $work "defaults.txt"
$javaArgs = @("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", "$work;$cp", "DefaultsDump")
& java @javaArgs 2>&1 | Out-File -FilePath $outFile -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw "DefaultsDump 运行失败" }

if ($UpdateGolden) {
    Copy-Item $outFile $goldenPath -Force
    $h = (Get-FileHash $goldenPath -Algorithm SHA256).Hash.Substring(0, 16)
    Step "已更新金表 (SHA16=$h)"
    Write-Host ""
    Write-Host "【注意】这是【显式】改变默认观感的唯一途径。"
    Write-Host "        提交时请说明改了哪个字段、为什么。"
    exit 0
}

if (-not (Test-Path $goldenPath)) {
    Step "金表不存在，请先运行 -UpdateGolden 建立基线"
    exit 1
}

$golden = Get-Content $goldenPath -Raw
$actual = Get-Content $outFile -Raw

if ($golden -ceq $actual) {
    $h = (Get-FileHash $goldenPath -Algorithm SHA256).Hash.Substring(0, 16)
    Step "默认值金表一致 (SHA16=$h)"
    exit 0
}

Step "默认值金表【不一致】"
Write-Host ""
Write-Host "--- 差异（- = 金表 / + = 当前）---"
$gl = $golden -split "`r?`n"
$al = $actual -split "`r?`n"
$max = [Math]::Max($gl.Count, $al.Count)
$diffCount = 0
for ($i = 0; $i -lt $max; $i++) {
    $g = if ($i -lt $gl.Count) { $gl[$i] } else { "<缺失>" }
    $a = if ($i -lt $al.Count) { $al[$i] } else { "<缺失>" }
    if ($g -cne $a) {
        Write-Host ("  - " + $g)
        Write-Host ("  + " + $a)
        $diffCount++
    }
}
Write-Host ""
Write-Host "共 $diffCount 处差异。"
Write-Host ""
Write-Host "如果这是【有意】改默认观感："
Write-Host "  1. 确认理由充分（它会改变所有用户的默认手感）"
Write-Host "  2. 运行 -UpdateGolden 更新金表"
Write-Host "  3. 提交信息里说明改了哪个字段、为什么"
Write-Host "如果不是有意的：立刻回退。"
exit 1