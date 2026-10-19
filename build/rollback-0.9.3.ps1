# 一键回退到 v0.9.2-imetime-fade（安装 0.9.3 之前的那一版）
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-0.9.3.ps1"
#
# 前提：完全退出 IDEA（它会内存映射锁住 jar）。
#
# 为什么需要它：0.9.3 换掉了 IME 中间态的判据（F1-b → F1-c 几何判据）。
# 判据类改动一旦在真机上表现不对，必须能立刻回到 0.9.2，而不是重新构建。

$ErrorActionPreference = 'Stop'

$lib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib'
$jar = Join-Path $lib 'jetbrains-neovide-cursor-0.5.0.jar'

# 0.9.2 的权威副本：dist 里那份（sha 前 16 位 7AC9D80E58CD8567）
$src = 'D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.2-imetime-fade.jar'
if (-not (Test-Path $src)) { throw "找不到回退目标: $src" }
$expect = '7AC9D80E58CD8567'

if (-not (Test-Path $lib)) { New-Item -ItemType Directory -Force $lib | Out-Null }

if (Test-Path $jar) {
    $bak = "$jar.replaced-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item $jar $bak
    Write-Host "旧文件已备份为：$(Split-Path $bak -Leaf)"
}

Copy-Item $src $jar
$got = (Get-FileHash $jar -Algorithm SHA256).Hash.Substring(0, 16)
Write-Host ("已回退 : {0} B  {1}" -f (Get-Item $jar).Length, $got)
if ($got -ne $expect) {
    Write-Host ("⚠️ 哈希不符：期望 {0}，实际 {1}" -f $expect, $got) -ForegroundColor Yellow
}
Write-Host "请重启 IDEA 生效。"
