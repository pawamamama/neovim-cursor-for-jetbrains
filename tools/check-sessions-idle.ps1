# 后台监控：所有 DSH 会话是否都已空闲
# 用法：pwsh -File check-sessions-idle.ps1 [-IntervalSec 30] [-Rounds 40]
param(
    [int]$IntervalSec = 30,
    [int]$Rounds = 40
)

$sessionsRoot = "C:\Users\wangw\.dsh\sessions"
$logFile = "D:\deepseekwork\neovim-cursor-pr\build\session-watch.log"

function Write-Log($msg) {
    $line = (Get-Date -Format "HH:mm:ss") + "  " + $msg
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

Write-Log "=== 开始监控（间隔 ${IntervalSec}s，共 $Rounds 轮）==="

$prev = @{}
for ($r = 1; $r -le $Rounds; $r++) {
    $files = Get-ChildItem $sessionsRoot -Recurse -File -Filter "session.v4.jsonl.zstd" -ErrorAction SilentlyContinue

    $active = @()
    foreach ($f in $files) {
        $key = $f.FullName
        $size = $f.Length
        $prevSize = if ($prev.ContainsKey($key)) { $prev[$key] } else { -1 }
        $grew = ($prevSize -ge 0 -and $size -gt $prevSize)
        $prev[$key] = $size

        # 最近 90 秒内修改过 = 活跃
        $recent = ((Get-Date) - $f.LastWriteTime).TotalSeconds -lt 90
        if ($grew -or $recent) {
            $bucket = $f.Directory.Parent.Name
            $active += [PSCustomObject]@{
                Bucket = $bucket
                Delta  = if ($prevSize -ge 0) { $size - $prevSize } else { 0 }
                AgeSec = [int]((Get-Date) - $f.LastWriteTime).TotalSeconds
            }
        }
    }

    if ($active.Count -eq 0) {
        Write-Log ("第 $r 轮：全部空闲 ✓  —— 可以关机")
        Write-Log "=== 结果：所有会话已停止 ==="
        exit 0
    } else {
        $desc = ($active | ForEach-Object { "$($_.Bucket)(age=$($_.AgeSec)s,+ $($_.Delta))" }) -join "  "
        Write-Log ("第 $r 轮：仍在运行 → " + $desc)
    }

    if ($r -lt $Rounds) { Start-Sleep -Seconds $IntervalSec }
}

Write-Log "=== 达到最大轮数，仍未全部空闲 ==="
exit 1
