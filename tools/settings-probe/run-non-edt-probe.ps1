# run-non-edt-probe.ps1 —— 非 EDT 分支专项回归护栏（t5 审查 R-01 / R-02 / R-03）
#
# 为什么单独一个脚本：t2/t3/t4 的全部离线探针里假 Application 的 isDispatchThread()
# 一律 return true，非 EDT 分支零覆盖 —— R-02 / R-03 两个缺陷正是从这里漏出去的。
#
# 用法（先跑一次 build\offline-build.ps1，让 build\classes 是最新的）：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-non-edt-probe.ps1
#
# 退出码：0 = 全通过；非 0 = 有失败项

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs'
)

$ErrorActionPreference = 'Stop'

# 变量命名纪律：不要用 $home / $env / $args / $input / $host / $pwd / $error / $matches ——
# 它们是 PowerShell 的自动变量，普通赋值会静默失败并把后续操作指向系统位置。
$repoRoot = $Repo
$classes  = Join-Path $repoRoot 'build\classes'
$probeDir = Join-Path $repoRoot 'tools\settings-probe'
$tmpRoot  = Join-Path $repoRoot 'build\non-edt-probe'
$out      = Join-Path $tmpRoot 'out'
$log      = Join-Path $probeDir 'non-edt-probe-output.txt'

if (-not (Test-Path $classes)) { throw 'build\classes 不存在，先跑 build\offline-build.ps1' }
if (-not (Test-Path $Libs)) { throw "idea-libs 不存在：$Libs" }

$cp = (Get-ChildItem $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

Write-Host "==> 编译 NonEdtProbe"
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classes;$cp" -d $out (Join-Path $probeDir "NonEdtProbe.java")
if ($LASTEXITCODE -ne 0) { throw "NonEdtProbe 编译失败" }

Write-Host ""
Write-Host "==> 运行 NonEdtProbe"
& java '-Dfile.encoding=UTF-8' '-Djava.awt.headless=true' -cp "$out;$classes;$cp" NonEdtProbe 2>&1 |
    Tee-Object -FilePath $log | ForEach-Object { $_ }
$code = $LASTEXITCODE

Write-Host ""
Write-Host "输出存档：$log"
if ($code -ne 0) { throw "NonEdtProbe 有失败项（exit $code）" }
Write-Host "ALL OK（非 EDT 分支：R-01 / R-02 / R-03 全通过）"
