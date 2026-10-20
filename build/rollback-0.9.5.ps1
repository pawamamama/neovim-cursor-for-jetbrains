# 一键回退到 v0.9.4-settings（安装 0.9.5-settings-ui 之前的那一版）
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-0.9.5.ps1"
#
# 前提：完全退出 IDEA（它会内存映射锁住 jar）。
#
# 为什么需要它：0.9.5-settings-ui 是设置界面的第二次大改 ——
#   预设置顶 / 折叠组 / 10 个吸附滑块 + 输入框 / 两个动画参数放开 /
#   Tools 新增两个预设子菜单 / 4 个原始缺陷 + 7 条复审缺陷的修法。
# 界面与配色方案改动面比 0.9.4 大得多（含针对 CARET_COLOR 的按 scheme 记录），
# 真机上一旦表现不对，必须能立刻回到 0.9.4-settings，而不是重新构建。
#
# ⚠️ 回退不会动设置文件：<config>\options\NeovideCursorSettings.xml 会留着。
#    0.9.4 的 State 只有 16 个业务字段，**不认识** 0.9.5 新增的
#    animationLength / shortAnimationLength 两项。
#    平台反序列化会忽略未知字段，因此 0.9.4 读它是安全的；但那两项的值会保持在
#    0.9.4 看不动的状态，等再升回 0.9.5 时会重新生效。
#    需要彻底干净的状态时，回退前先备份并删掉上面那个 xml。
#
# 安全：不使用 PowerShell 自动变量名（$home/$env/$args/$input/$host/$pwd/$error/$matches）做普通赋值。

$ErrorActionPreference = 'Stop'

$installLib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib'
$installedJar = Join-Path $installLib 'jetbrains-neovide-cursor-0.5.0.jar'

# 0.9.4-settings 的权威副本：dist 里那份
#   SHA256 = D30B23E2FF59C33051C67F1614B586A2CAD70B3266EF7515F83DB9E0DD527F4D
$repoRoot = 'D:\deepseekwork\neovim-cursor-pr'
$rollbackSource = Join-Path $repoRoot 'dist\jetbrains-neovide-cursor-0.9.4-settings.jar'
$expectHash16 = 'D30B23E2FF59C330'

if (-not (Test-Path -LiteralPath $rollbackSource)) { throw "找不到回退目标: $rollbackSource" }

# 先校验回退目标本身，避免把坏文件拷进去
$sourceHash16 = (Get-FileHash -LiteralPath $rollbackSource -Algorithm SHA256).Hash.Substring(0, 16)
if ($sourceHash16 -ne $expectHash16) {
    throw ("回退目标哈希不符：期望 {0}，实际 {1}（{2}）" -f $expectHash16, $sourceHash16, $rollbackSource)
}
Write-Host ("回退目标校验通过：{0} B  {1}…" -f (Get-Item -LiteralPath $rollbackSource).Length, $expectHash16)

if (-not (Test-Path -LiteralPath $installLib)) { New-Item -ItemType Directory -Force -Path $installLib | Out-Null }

# 先 Move 后 Copy：Move 成功才说明旧文件没被 IDEA 锁住
if (Test-Path -LiteralPath $installedJar) {
    $oldHash16 = (Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash.Substring(0, 16)
    $backupPath = "$installedJar.replaced-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item -LiteralPath $installedJar -Destination $backupPath
    Write-Host "旧文件（$oldHash16…）已备份为：$(Split-Path -Leaf $backupPath)"
} else {
    Write-Host '当前没有已安装的 jar（干净安装），直接放入 0.9.4-settings。'
}

Copy-Item -LiteralPath $rollbackSource -Destination $installedJar
$installedHash16 = (Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash.Substring(0, 16)
Write-Host ("已回退 : {0} B  {1}…" -f (Get-Item -LiteralPath $installedJar).Length, $installedHash16)
if ($installedHash16 -ne $expectHash16) {
    Write-Host ("⚠️ 哈希不符：期望 {0}，实际 {1}" -f $expectHash16, $installedHash16) -ForegroundColor Yellow
    exit 1
}

Write-Host ''
Write-Host '回退完成。请重启 IDEA 生效。'
Write-Host '如需彻底清空 0.9.5 新增的两个动画参数，另删：'
Write-Host "  $env:APPDATA\JetBrains\IntelliJIdea2026.1\options\NeovideCursorSettings.xml"