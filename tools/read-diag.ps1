$f = "$env:APPDATA\JetBrains\IntelliJIdea2026.1\neovide-cursor-diag.log"
if(-not (Test-Path $f)){ Write-Host "日志文件不存在: $f"; exit 1 }
$size = (Get-Item $f).Length
Write-Host ("文件: " + $f)
Write-Host ("大小: " + $size + " B")
Write-Host ""
Write-Host "=== 各类日志条数 ==="
foreach($k in @("carets","config","preset","error","frame","caret","trail","pick","block")){
    $n = (Select-String -Path $f -Pattern ("\[" + $k + "\]") -ErrorAction SilentlyContinue).Count
    Write-Host ("  [" + $k + "] " + $n)
}
Write-Host ""
Write-Host "=== 最后 20 行 ==="
Get-Content $f -Tail 20 | ForEach-Object { "  " + $_ }