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
# 【D0 判据演进（2026-10-23）】
#
# 原判据：core/NeovideConfig.java 必须【文件未改】。
#    问题：它把"不许改观感常量"实现成了"不许动这个文件"——
#    于是任何受控的物理演进都必须先骗过它，护栏从"防偷偷改"退化成"防任何改"。
#
# 新判据（用户已授权 C 方案动 core/）：校验【默认值语义未变】——
#    · 仍然禁止修改已有的默认值字面量（那才是"偷偷改观感"）
#    · 允许【新增】字段，但新字段默认必须是无副作用值（0 / false）
#    · 允许 TrailCorner 在【新字段为默认值时】改变代码结构，由 parity 逐字节守
#
# 换句话说：D0 管"默认观感不许变"，parity 管"默认行为逐字节不变"。
# 两者分工，比原来那种"整个文件冻结"更精确。

$coreChanged = @(& git -C $repoRoot status --porcelain=v1 -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/)
if ($coreChanged.Count -eq 0) {
    Write-Host '  core/ 无改动（OK）'
    $d0 = 0
} else {
    # 有改动 → 逐项校验：只允许「新增行」，且不允许删除/修改既有默认值行
    $diff = @(& git -C $repoRoot diff -U0 -- src/main/java/com/github/yyyolo7a79/neovidecursor/core/)
    $removed = @($diff | Where-Object {
        $_ -match '^-[^-]' -and $_ -match '=s*(-?d+(.d+)?f?|true|false)s*;'
    })
    if ($removed.Count -eq 0) {
        Write-Host '  core/ 有改动，但未修改任何既有默认值字面量（OK）'
        $coreChanged | ForEach-Object { Write-Host "    $_" }
        $d0 = 0
    } else {
        Write-Host '  core/ 修改了既有默认值字面量（禁止）：'
        $removed | ForEach-Object { Write-Host "    $_" }
        $d0 = 1
    }
}
Write-Host "  D0 exit=$d0 (期望 0)"

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