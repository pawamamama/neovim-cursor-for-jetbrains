# 一键回退到 v0.7.1-offset（用户确认在用的版本）
# 用法: pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-071.ps1"
$ErrorActionPreference = 'Stop'
$lib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib'
$jar = Join-Path $lib 'jetbrains-neovide-cursor-0.5.0.jar'
$src = 'D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.7.1-offset.jar'

if (-not (Test-Path $src)) { throw "找不到回退源: $src" }
if (Test-Path $jar) {
    $bak = "$jar.replaced-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item $jar $bak
    Write-Host "旧 jar 已备份: $(Split-Path $bak -Leaf)"
}
Copy-Item $src $jar
Write-Host ("已回退到 v0.7.1-offset : {0} B  {1}" -f (Get-Item $jar).Length, (Get-FileHash $jar).Hash.Substring(0,16))
Write-Host "请重启 IDEA 生效。"
