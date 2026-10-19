# 一键回退到 uni（0.5.2-unified）—— 用户认可的原版基线
$ErrorActionPreference = 'Stop'
$lib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib'
$jar = Join-Path $lib 'jetbrains-neovide-cursor-0.5.0.jar'
$src = 'D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.5.2-unified.jar'
if (-not (Test-Path $src)) { throw "找不到: $src" }
if (Test-Path $jar) {
    $bak = "$jar.replaced-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item $jar $bak
}
Copy-Item $src $jar
Write-Host ("已回退到 uni : {0} B  {1}" -f (Get-Item $jar).Length, (Get-FileHash $jar).Hash.Substring(0,16))
Write-Host "请重启 IDEA 生效。"
