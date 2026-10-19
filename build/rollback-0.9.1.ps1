# 一键回退到「安装 0.9.1 之前」的那一版（0.9.0-ahf-default, FA161E4F77C8C999）
# 用法：pwsh -NoProfile -ExecutionPolicy Bypass -File "D:\deepseekwork\neovim-cursor-pr\build\rollback-0.9.1.ps1"
# 前提：完全退出 IDEA（它会内存映射锁住 jar）
$ErrorActionPreference = 'Stop'
$lib = 'C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\jetbrains-neovide-cursor\lib'
$jar = Join-Path $lib 'jetbrains-neovide-cursor-0.5.0.jar'

# 优先用「安装时刻从插件目录原样复制出来」的那份留档（最可靠，就是你当时用的字节）
$src = Get-ChildItem 'D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.0-ahf-default.INSTALLED-*.jar' -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $src) {
    $src = Get-Item 'D:\deepseekwork\neovim-cursor-pr\dist\jetbrains-neovide-cursor-0.9.0-ahf-default.jar'
}
if (-not $src) { throw "找不到回退目标 jar" }

if (Test-Path $jar) {
    $bak = "$jar.replaced-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item $jar $bak
}
Copy-Item $src.FullName $jar
Write-Host ("已回退 : {0} B  {1}" -f (Get-Item $jar).Length, (Get-FileHash $jar).Hash.Substring(0,16))
Write-Host ("来源   : {0}" -f $src.Name)
Write-Host "期望哈希前16位: FA161E4F77C8C999"
Write-Host "请重启 IDEA 生效。"
