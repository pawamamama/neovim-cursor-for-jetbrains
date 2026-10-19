# 一键回退到 v0.9.3-imegeom（安装 0.9.4-settings 之前的那一版）
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-0.9.4.ps1"
#
# 前提：完全退出 IDEA（它会内存映射锁住 jar）。
#
# 为什么需要它：0.9.4-settings 是本插件第一个带「设置界面」的版本，同时改了三处
# 线程 / 落盘路径（R-01 enabled 早退、R-02 非 EDT 快照、R-03 三态落盘）。
# 界面类改动一旦在真机上表现不对，必须能立刻回到 0.9.3-imegeom，而不是重新构建。
#
# ⚠️ 回退不会动设置文件：<config>\options\NeovideCursorSettings.xml 会留着。
#    0.9.3 不认这个文件（它读的是 PropertiesComponent 的两个旧键），所以回退后
#    设置会回到 0.9.3 的旧值。旧键在 0.9.4 首次启动时已被删除，因此回退后
#    alwaysHighFps / overlayFadeOut 会走 0.9.3 的默认兜底（前者 false —— 那正是本批修掉的 bug）。
#    需要保留 0.9.4 的设置时，回退前先备份上面那个 xml。

$ErrorActionPreference = 'Stop'

# 变量命名纪律：不要用 $home / $env / $args / $input / $host / $pwd / $error / $matches 做普通赋值。
$installLib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib'
$installedJar = Join-Path $installLib 'jetbrains-neovide-cursor-0.5.0.jar'

# 0.9.3-imegeom 的权威副本：dist 里那份（SHA256 前 16 位 610AE7E9676E2392）
$repoRoot = 'D:\deepseekwork\neovim-cursor-pr'
$rollbackSource = Join-Path $repoRoot 'dist\jetbrains-neovide-cursor-0.9.3-imegeom.jar'
$expectHash16 = '610AE7E9676E2392'

if (-not (Test-Path $rollbackSource)) { throw "找不到回退目标: $rollbackSource" }

# 先校验回退目标本身，避免把坏文件拷进去
$sourceHash16 = (Get-FileHash $rollbackSource -Algorithm SHA256).Hash.Substring(0, 16)
if ($sourceHash16 -ne $expectHash16) {
    throw ("回退目标哈希不符：期望 {0}，实际 {1}（{2}）" -f $expectHash16, $sourceHash16, $rollbackSource)
}

if (-not (Test-Path $installLib)) { New-Item -ItemType Directory -Force $installLib | Out-Null }

if (Test-Path $installedJar) {
    $backupPath = "$installedJar.replaced-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item $installedJar $backupPath
    Write-Host "旧文件已备份为：$(Split-Path $backupPath -Leaf)"
}

Copy-Item $rollbackSource $installedJar
$installedHash16 = (Get-FileHash $installedJar -Algorithm SHA256).Hash.Substring(0, 16)
Write-Host ("已回退 : {0} B  {1}" -f (Get-Item $installedJar).Length, $installedHash16)
if ($installedHash16 -ne $expectHash16) {
    Write-Host ("⚠️ 哈希不符：期望 {0}，实际 {1}" -f $expectHash16, $installedHash16) -ForegroundColor Yellow
}
Write-Host "请重启 IDEA 生效。"
