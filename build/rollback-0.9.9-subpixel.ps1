# rollback-0.9.9-subpixel.ps1 —— 回退到 0.9.8（用户认可的手感基线）
#
# 用法：先【完全关闭 IDEA】，再运行
#   pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\build\rollback-0.9.9-subpixel.ps1
#
# 为什么必须有这个脚本：0.9.9 装了抖动修复 + 自定义预设 + 三层重构，
# 任何一项若让手感变差，必须能一键回到 0.9.8（41F16B3F / 102,223 B）。

$ErrorActionPreference = 'Stop'

$lib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\neovide-cursor-pawamamama\lib'
$target = Join-Path $lib 'jetbrains-neovide-cursor-0.9.8.jar'
$backup = Join-Path $lib 'jetbrains-neovide-cursor-0.9.8.BACKUP.jar'

# 1) 确认 IDEA 已关闭（jar 被内存映射锁住时写入会失败/损坏）
$procs = Get-Process -Name 'idea64', 'idea' -ErrorAction SilentlyContinue
if ($procs) {
    Write-Host 'IDEA 仍在运行，请先完全退出再重试。' -ForegroundColor Red
    exit 1
}

# 2) 校验备份存在且字节数正确
if (-not (Test-Path -LiteralPath $backup)) { throw "备份不存在：$backup" }
$expected = 102223
$size = (Get-Item -LiteralPath $backup).Length
if ($size -ne $expected) { throw "备份字节数异常：$size（期望 $expected）" }

# 3) 把当前的 0.9.9 挪走（不删，留作证据）
if (Test-Path -LiteralPath $target) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $aside = Join-Path $lib "jetbrains-neovide-cursor-0.9.9-aside-$stamp.jar"
    Move-Item -LiteralPath $target -Destination $aside -Force
    Write-Host "已把 0.9.9 挪到：$aside"
}

# 4) 恢复 0.9.8
Copy-Item -LiteralPath $backup -Destination $target -Force
$now = (Get-Item -LiteralPath $target).Length
$hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.Substring(0, 8)
Write-Host ""
Write-Host "已回退到 0.9.8：$now 字节 / $hash（期望 102223 / 41F16B3F）" -ForegroundColor Green
if ($now -ne $expected -or $hash -ne '41F16B3F') {
    Write-Host '警告：字节数或哈希与期望不符，请人工核对！' -ForegroundColor Yellow
    exit 1
}
Write-Host '回退成功。启动 IDEA 即可。' -ForegroundColor Green
