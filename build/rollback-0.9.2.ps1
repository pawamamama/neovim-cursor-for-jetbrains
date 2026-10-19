# 一键回退到 v0.9.1-zeroopt（安装 0.9.2 之前的那一版）
# 用法：pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-0.9.2.ps1"
# 前提：完全退出 IDEA（它会内存映射锁住 jar）
$ErrorActionPreference = 'Stop'
$lib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib'
$jar = Join-Path $lib 'jetbrains-neovide-cursor-0.5.0.jar'

# 0.9.1 的权威副本：dist 里那份（sha 前 16 位 C718675E046CAB82）
$src = 'D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.1-zeroopt.jar'
if (-not (Test-Path $src)) { throw "找不到回退目标: $src" }
$expect = 'C718675E046CAB82'

if (Test-Path $jar) {
    $bak = "$jar.replaced-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item $jar $bak
}
Copy-Item $src $jar
$got = (Get-FileHash $jar -Algorithm SHA256).Hash.Substring(0,16)
Write-Host ("已回退 : {0} B  {1}" -f (Get-Item $jar).Length, $got)
if ($got -ne $expect) {
    Write-Host ("⚠️ 哈希不符：期望 {0}，实际 {1}" -f $expect, $got) -ForegroundColor Yellow
}
Write-Host "请重启 IDEA 生效。"
