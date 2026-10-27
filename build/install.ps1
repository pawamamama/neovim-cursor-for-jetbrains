<#
  install.ps1 —— 干净安装 Neovide Cursor

  用法：
    pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.69

  它做什么（按顺序）：
    1) 编译 + 打包（调 offline-build.ps1）
    2) 关闭 IDEA
    3) 【干净安装】清空插件 lib 目录 + 删除设置 XML + 删除诊断日志
    4) 拷贝新 jar
    5) 自检 jar 内版本号
    6) 启动 IDEA

  为什么要「干净安装」（用户 2026-10-25 要求）：
    排障期间反复出现「设置文件只剩 3 个字段」「tailOpacity 停在 0.05」这类
    脏状态，耗费大量时间排查到底是谁写坏的。干净安装保证每次验证都从
    出厂默认值开始，把「代码 bug」和「历史脏数据」彻底分开。

    尤其重要的两份：
      NeovideCursorSettings.xml  —— 旧值会掩盖新默认值，也会让预设匹配失真
      neovide-cursor-diag.log    —— 混着历史日志就没法按时间定位本次问题
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$BaseJar = '',
    [switch]$KeepLog,
    # 只装不启动 IDEA。用户要求：「安装之后不用启动」——
    # 每次安装都强拉 IDE 会打断他手上的工作，也抢焦点。
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$ideaCfg = "C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1"
$pluginDir = Join-Path $ideaCfg "plugins\neovide-cursor-pawamamama"
$pluginLib = Join-Path $pluginDir "lib"
$settingsXml = Join-Path $ideaCfg "options\NeovideCursorSettings.xml"
$diagLog = Join-Path $ideaCfg "neovide-cursor-diag.log"
$backupRoot = "D:\deepseekwork\_jar-backup-20261024"
$jarName = "neovide-cursor-$Version.jar"

if (-not $BaseJar) {
    $BaseJar = Join-Path $repo "dist\jetbrains-neovide-cursor-0.9.14-conclusion.jar"
}

Write-Host "========== 1/6 编译打包 =========="
& pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'offline-build.ps1') `
    -Version $Version -OutName $jarName -BaseJar $BaseJar
if ($LASTEXITCODE -ne 0) { throw "打包失败" }

$newJar = Join-Path $repo "dist\$jarName"
if (-not (Test-Path $newJar)) { throw "产物不存在：$newJar" }

Write-Host "`n========== 2/6 关闭 IDEA =========="
$ide = Get-Process -Name "idea64" -ErrorAction SilentlyContinue
if ($ide) {
    Write-Host "  发现 $($ide.Count) 个 idea64 进程，正在关闭…"
    $ide | ForEach-Object { $_.CloseMainWindow() | Out-Null }
    Start-Sleep -Seconds 8
    $still = Get-Process -Name "idea64" -ErrorAction SilentlyContinue
    if ($still) {
        Write-Host "  仍存活，强制结束"
        $still | ForEach-Object { Stop-Process -Id $_.Id -Force }
        Start-Sleep -Seconds 3
    }
    Write-Host "  已关闭"
} else {
    Write-Host "  IDEA 未运行"
}

Write-Host "`n========== 3/6 干净安装：清空旧状态 =========="
New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null

# 3a 旧 jar 全部移走（不留任何 .jar 在 lib 里）
if (Test-Path $pluginLib) {
    Get-ChildItem $pluginLib -Filter "*.jar*" -ErrorAction SilentlyContinue | ForEach-Object {
        $dest = Join-Path $backupRoot ($_.BaseName + "-" + (Get-Date -Format "yyyyMMdd-HHmmss") + $_.Extension)
        Move-Item $_.FullName $dest -Force
        Write-Host "  移走旧 jar: $($_.Name)"
    }
} else {
    New-Item -ItemType Directory -Force -Path $pluginLib | Out-Null
}

# 3b 删除设置 XML（连同它的所有备份）
if (Test-Path $settingsXml) {
    $bak = "$settingsXml.bak-" + (Get-Date -Format "yyyyMMdd-HHmmss")
    Copy-Item $settingsXml $bak -Force
    Remove-Item $settingsXml -Force
    Write-Host "  已删除设置 XML（备份到 $bak）"
    Write-Host "  => 下次启动使用出厂默认：基础预设 / 粉色 / 2px / 顶格 100% / 平滑关闭"
} else {
    Write-Host "  设置 XML 不存在（已是干净状态）"
}
Get-ChildItem (Split-Path $settingsXml -Parent) -Filter "NeovideCursorSettings.xml.bak-*" -ErrorAction SilentlyContinue |
    ForEach-Object { Remove-Item $_.FullName -Force }

# 3c 删除诊断日志
if ((Test-Path $diagLog) -and -not $KeepLog) {
    Remove-Item $diagLog -Force
    Write-Host "  已删除诊断日志"
} elseif ($KeepLog) {
    Write-Host "  保留诊断日志（-KeepLog）"
}

Write-Host "`n========== 4/6 安装新 jar =========="
$target = Join-Path $pluginLib "jetbrains-neovide-cursor-0.9.8.jar"
Copy-Item $newJar $target -Force
$len = (Get-Item $target).Length
Write-Host "  已装 $len B  ->  $target"

Write-Host "`n========== 5/6 自检 =========="
$verify = Join-Path $repo "build\verify-install"
if (Test-Path $verify) { Remove-Item $verify -Recurse -Force }
New-Item -ItemType Directory -Force -Path $verify | Out-Null
Push-Location $verify
& jar --extract --file $target META-INF/plugin.xml
Pop-Location
$xmlFile = Join-Path $verify "META-INF\plugin.xml"
$ver = (Select-String -Path $xmlFile -Pattern '<version>([^<]+)</version>').Matches.Groups[1].Value
if ($ver -ne $Version) { throw "版本不符：jar 内是 $ver，期望 $Version" }
Write-Host "  jar 内版本 = $ver  OK"

if ($NoStart) {
    Write-Host "`n========== 6/6 跳过启动（-NoStart）=========="
    Write-Host "  jar 已就位；下次手动打开 IDEA 时生效"
} else {
    Write-Host "`n========== 6/6 启动 IDEA =========="
    Start-Process "D:\java_learning\idea2026\IntelliJ IDEA 2026.1.3\bin\idea64.exe"
    Start-Sleep -Seconds 6
    $p = Get-Process -Name "idea64" -ErrorAction SilentlyContinue
    if ($p) { Write-Host "  IDEA 已启动 PID $($p[0].Id)" } else { Write-Host "  警告：IDEA 似乎没有起来" }
}

Write-Host "`n干净安装完成：v$Version，配置已重置为出厂默认"
