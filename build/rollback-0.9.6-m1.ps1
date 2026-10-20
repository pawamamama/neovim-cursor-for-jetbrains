# 一键回退到 v0.9.5-settings-ui（安装 0.9.6-m1-tools3 之前的那一版）
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-0.9.6-m1.ps1"
#
# 前提：完全退出 IDEA（它会内存映射锁住 jar）。
#
# 为什么需要它：0.9.6-m1 把 Tools 菜单从 7 项砍到 3 项（只留常驻刷新 + 动画预设 + 颜色预设），
# 并把「性能诊断」挪进设置面板的「高级（诊断）」折叠组。
# 菜单里少了 4 个开关是**有意的**（功能都在面板里），但如果真机上发现某个开关在面板里没有，
# 必须能立刻回到 0.9.5，而不是重新构建。
#
# ⚠️ 回退不会动设置文件：<config>\options\NeovideCursorSettings.xml 会留着。
#    0.9.5 与 0.9.6 的 State 字段完全相同（本版只挪了 UI 入口，没加字段），
#    因此双向切换都不会丢值。
#
# 安全：不使用 PowerShell 自动变量名（$home/$env/$args/$input/$host/$pwd/$error/$matches）做普通赋值。

$ErrorActionPreference = "Stop"

$installLib = "C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib"
$installedJar = Join-Path $installLib "jetbrains-neovide-cursor-0.5.0.jar"

# 0.9.5-settings-ui（修掉 separator 启动崩溃后的重建版）的权威副本：dist 里那份
#   SHA256 = 09B927C817F26E2EDCACEC6C3B83E47D20C8F6A505BA4F675C5F416229A497A9
$repoRoot = "D:\deepseekwork\neovim-cursor-pr"
$rollbackSource = Join-Path $repoRoot "dist\jetbrains-neovide-cursor-0.9.5-settings-ui.jar"
$expectHash16 = "09B927C817F26E2E"

if (-not (Test-Path -LiteralPath $rollbackSource)) { throw "找不到回退目标: $rollbackSource" }

$sourceHash16 = (Get-FileHash -LiteralPath $rollbackSource -Algorithm SHA256).Hash.Substring(0, 16)
if ($sourceHash16 -ne $expectHash16) {
    throw ("回退目标哈希不符：期望 {0}，实际 {1}（{2}）" -f $expectHash16, $sourceHash16, $rollbackSource)
}
Write-Host ("回退目标校验通过：{0} B  {1}..." -f (Get-Item -LiteralPath $rollbackSource).Length, $expectHash16)

if (-not (Test-Path -LiteralPath $installLib)) { New-Item -ItemType Directory -Force -Path $installLib | Out-Null }

if (Test-Path -LiteralPath $installedJar) {
    $oldHash16 = (Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash.Substring(0, 16)
    $backupPath = "$installedJar.replaced-$(Get-Date -Format ""yyyyMMdd-HHmmss"")"
    Move-Item -LiteralPath $installedJar -Destination $backupPath
    Write-Host "旧文件（$oldHash16...）已备份为：$(Split-Path -Leaf $backupPath)"
} else {
    Write-Host "当前没有已安装的 jar（干净安装），直接放入 0.9.5-settings-ui。"
}

Copy-Item -LiteralPath $rollbackSource -Destination $installedJar
$installedHash16 = (Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash.Substring(0, 16)
Write-Host ("已回退 : {0} B  {1}..." -f (Get-Item -LiteralPath $installedJar).Length, $installedHash16)
if ($installedHash16 -ne $expectHash16) {
    Write-Host ("⚠️ 哈希不符：期望 {0}，实际 {1}" -f $expectHash16, $installedHash16) -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "回退完成。请重启 IDEA 生效。"
