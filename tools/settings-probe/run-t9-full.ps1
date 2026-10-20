# run-t9-full.ps1 -- t9 全量验证一键复跑
#
# 用法：pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-t9-full.ps1
# 退出码：0 = 全部通过；非 0 = 有失败段
#
# 安全：不使用 PowerShell 自动变量名做普通赋值。

param([string]$Repo = 'D:\deepseekwork\neovim-cursor-pr')

$ErrorActionPreference = 'Continue'
$repoRoot = $Repo

# 注意：本函数只返回退出码。人类可读输出一律走 Write-Host，绝不落到输出流，
# 否则会被调用方 $codes += T ... 当成返回值收进数组，汇总表就变成一堆 log 行。
function T($name, $block) {
    Write-Host ''
    Write-Host "--- $name ---"
    $out = & $block 2>&1
    $code = $LASTEXITCODE
    foreach ($line in $out) {
        $s = [string]$line
        if ($s -match 'javac:|已打包|version=|ALL PARITY|DIFF 数|=== 结果|ALL 4BUG|ALL VERIFY-T4|ALL T7|ALL BR|V-MUTATIONS RESULT') {
            Write-Host ('  ' + $s.Trim())
        }
    }
    Write-Host "  [$name] exit=$code"
    return $code
}

Write-Host '################ t9 全量验证 ################'

$codes = @()

$codes += T 'build' { pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'build\offline-build.ps1') -Version 0.9.5 -OutName settings-v2-smoke.jar -BaseJar (Join-Path $repoRoot 'dist\jetbrains-neovide-cursor-0.9.4-settings.jar') }
$codes += T 'parity' { pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'tools\parity\run-parity.ps1') }
$codes += T 'audit' { pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'tools\parity\run-audit.ps1') }
$codes += T 'ime' { pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'tools\ime-probe\run-ime-geom-test.ps1') }
$codes += T 't7' { pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'tools\settings-probe\run-t7-probe.ps1') }
$codes += T 'br' { pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'tools\settings-probe\run-br-probe.ps1') }
$codes += T 'v-mutations' { pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'tools\settings-probe\run-v-mutations.ps1') }
$codes += T 'settings-v2' { pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'tools\settings-probe\run-settings-v2-probe.ps1') }
$codes += T '4bugs' { pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'tools\settings-probe\run-4bugs-probe.ps1') }
$codes += T 't4-verify' { pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'tools\settings-probe\run-verify-t4.ps1') }

Write-Host ''
Write-Host '--- D0 / core ---'
& git -C $repoRoot diff --exit-code -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/NeovideConfig.java | Out-Null
$d0 = $LASTEXITCODE
Write-Host "  D0 exit=$d0 (期望 0)"
$cu = @(& git -C $repoRoot status --porcelain=v1 -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/)
if ($cu.Count -eq 0) { Write-Host '  core/ 无任何改动或新增（OK）' } else { $cu | ForEach-Object { Write-Host "  $_" } }

Write-Host ''
Write-Host '--- 退出码汇总 ---'
$names = @('build','parity','audit','ime','t7','br','v-mutations','settings-v2','4bugs','t4-verify')
for ($i = 0; $i -lt $names.Count; $i++) {
    Write-Host ('  {0,-14} exit={1}' -f $names[$i], $codes[$i])
}
Write-Host ('  {0,-14} exit={1}' -f 'D0', $d0)

$bad = @($codes | Where-Object { $_ -ne 0 })
if ($bad.Count -eq 0 -and $d0 -eq 0) {
    Write-Host ''
    Write-Host 'ALL T9 VERIFICATION PASSED'
    exit 0
} else {
    Write-Host ''
    Write-Host "FAILED SECTIONS = $($bad.Count) (D0=$d0)"
    exit 1
}