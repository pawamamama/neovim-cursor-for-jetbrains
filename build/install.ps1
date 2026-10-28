<#
  install.ps1 —— 干净安装 Neovide Cursor（带个人配置备份/还原）

  用法：
    pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.94
    pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.94 -DryRun
    pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.94 -NoStart

  -DryRun  只打印「将备份什么 / 将丢弃什么」，不动任何文件、不编译、不关 IDEA。
           改完脚本先跑它，确认清单对了再真装。

  它做什么（按顺序）：
    0) 读出当前个人配置，打印备份/丢弃清单（-DryRun 到此结束）
    1) 编译 + 打包（调 offline-build.ps1）
    2) 关闭 IDEA
    3) 【干净安装】清空插件 lib 目录 + 删除设置 XML + 删除诊断日志
    4) 拷贝新 jar
    5) 自检 jar 内版本号 → 把个人配置写回一份新 XML
    6) 启动 IDEA

  为什么还要「干净安装」（用户 2026-10-25 要求）：
    排障期间反复出现「设置文件只剩 3 个字段」「tailOpacity 停在 0.05」这类
    脏状态，耗费大量时间排查到底是谁写坏的。干净安装保证每次验证都从
    出厂默认值开始，把「代码 bug」和「历史脏数据」彻底分开。

  为什么现在又多了一步「备份 + 还原」（用户 2026-10-28 要求）：
    干净安装会把用户手调的东西一起清掉。而有一批数据是「面板里点不出来第二次」的
    —— 尤其是自定义预设（含编辑内置预设时自动派生的「xx 副本」），丢了只能手工重调。
    所以改成：清空之前先把个人配置抄一份 → 装完 → 再写回去。

  ==================== 维护须知（重要，别删这段） ====================

  【什么时候必须回来改这个脚本】

    1. **State 增加了新字段**（`SettingsField.java` 里多了一条 `out.add(...)`）：
       默认策略是「不在丢弃名单就备份」，所以新字段默认会被备份 —— 这通常正是你要的。
       但脚本会在每次安装前打印「结构指纹」，并与上次安装时记录的值对比，
       **两个数字对不上时会出现一行 ⚠ 警告** —— 看到它就说明该人工复核一次。

    2. **某个字段的语义变了**（比如 tailColor 拆成两个字段）：
       旧值写回去可能是脏数据。此时要么把它加进 $dropFields，要么在还原后做一次迁移。

    3. **平台改了配置文件路径 / 组件名**（IDEA 大版本升级）：
       见下面的 $ideaCfg 与 "NeovideCursorSettings" 组件名。

  【结构指纹是什么、为什么需要它】
    「备份」只有在「配置结构没变」时才等价于「无损」。
    本脚本把备份当时的 `SettingsField.java` 字段数记进备份目录（结构指纹），
    下次安装时和当前源码对比 —— 一旦不一致就警告。
    这不是万能的（字段改名时数量可能不变），但能抓住最常见的一种：加了新字段。
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$BaseJar = '',
    [switch]$KeepLog,
    # 只装不启动 IDEA。用户要求：「安装之后不用启动」——
    # 每次安装都强拉 IDE 会打断他手上的工作，也抢焦点。
    [switch]$NoStart,
    # 只打印计划，不做任何修改（不编译、不关 IDEA、不动配置）
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$ideaCfg = "C:\Users\wangw\AppData\Roaming\JetBrains\IntelliJIdea2026.1"
$pluginDir = Join-Path $ideaCfg "plugins\neovide-cursor-pawamamama"
$pluginLib = Join-Path $pluginDir "lib"
$settingsXml = Join-Path $ideaCfg "options\NeovideCursorSettings.xml"
$diagLog = Join-Path $ideaCfg "neovide-cursor-diag.log"
$backupRoot = "D:\deepseekwork\_jar-backup-20261024"
$cfgBackupDir = "D:\deepseekwork\_config-backup"
$jarName = "neovide-cursor-$Version.jar"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"

if (-not $BaseJar) {
    $BaseJar = Join-Path $repo "dist\jetbrains-neovide-cursor-0.9.14-conclusion.jar"
}

# ==================== 个人配置：保留什么、丢弃什么 ====================
#
# 策略：**不在丢弃名单里的一律备份并还原**。
# 这样以后加了字段（不写进丢弃名单）会默认被备份，而不是默认被扔掉。
#
# 丢弃名单的每一条都要写清「为什么」，否则下一个人不敢动它。
# 【2026-10-28 用户最终口径】
#   「我希望更新时候所有的系统级别预设都是用户之前定义的所以还原」
#   → **全量还原**：XML 里有什么就备份什么、写回什么，一个字段都不丢。
#     包括 animPresetBase（当前选中哪个内置预设）与颜色三件套 ——
#     这些字段里存的本就是「用户之前定义的值」，丢弃它们等于把用户调好的观感改回出厂。
#
# 为什么保留这个空数组而不是删掉丢弃逻辑：
#   将来某个字段**语义**变了（例如 tailColor 拆成两个字段），旧值写回去就是脏数据，
#   那时把该字段加进这里即可。它是一道「语义变更保险」，不是日常开关。
#   往这里加字段时，必须同时更新 CLAUDE.md「陷阱 11」与 docs/RELEASE-SAFETY.md 的说明。
$dropFields = @()

<#
  读配置 XML，返回「要备份的字段 → 值」的有序表。
  组件名写死为 NeovideCursorSettings（见维护须知第 3 条）。
#>
function Read-PersonalOptions {
    param([string]$XmlPath, [string[]]$Drop)

    $map = [ordered]@{}
    if (-not (Test-Path -LiteralPath $XmlPath)) {
        return , $map
    }
    $doc = New-Object System.Xml.XmlDocument
    $doc.Load($XmlPath)
    $nodes = $doc.SelectNodes("//component[@name='NeovideCursorSettings']/option")
    foreach ($opt in $nodes) {
        $name = $opt.GetAttribute('name')
        if ($Drop -contains $name) { continue }
        $map[$name] = $opt.GetAttribute('value')
    }
    return , $map
}

<#
  把「字段 → 值」写成一份新的 NeovideCursorSettings.xml。
  用 XmlWriter 而不是字符串拼接：值里可能出现 & < > 引号（自定义预设名允许任意字符），
  手工拼会把 XML 写坏 —— 写坏之后平台会静默把整份配置当空，用户的自定义预设就没了。
#>
function Write-PersonalOptions {
    param([string]$XmlPath, $Map, [string[]]$Dropped)

    $dir = Split-Path -Parent $XmlPath
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $xs = New-Object System.Xml.XmlWriterSettings
    $xs.Indent = $true
    $xs.IndentChars = "  "
    # 无 BOM 的 UTF-8：与平台自己写出来的文件保持一致
    $xs.Encoding = New-Object System.Text.UTF8Encoding($false)

    $w = [System.Xml.XmlWriter]::Create($XmlPath, $xs)
    try {
        $w.WriteStartDocument()
        $w.WriteStartElement('application')
        $w.WriteStartElement('component')
        $w.WriteAttributeString('name', 'NeovideCursorSettings')
        foreach ($k in $Map.Keys) {
            $w.WriteStartElement('option')
            $w.WriteAttributeString('name', [string]$k)
            $w.WriteAttributeString('value', [string]$Map[$k])
            $w.WriteEndElement()
        }
        $w.WriteEndElement()
        $w.WriteEndElement()
        $w.WriteEndDocument()
    } finally {
        $w.Close()
    }
    Write-Host "  已写入 $XmlPath（$($Map.Count) 个字段；丢弃 $($Dropped.Count) 个）"
}

<#
  结构指纹：当前源码里 SettingsField.FIELDS 的字段数。
  用于下一次安装时判断「配置结构变没变」。
  取不到就返回 -1 —— 绝不让「统计失败」变成「安装失败」。
#>
function Get-SettingsFieldCount {
    $f = Join-Path $repo "src\main\java\com\github\yyyolo7a79\neovidecursor\settings\SettingsField.java"
    if (-not (Test-Path -LiteralPath $f)) { return -1 }
    try {
        # build() 里每个字段都是一条 out.add(<工厂>(...))。
        # 【必须匹配工厂名】`buildPresetFields()` 里还有一句 `out.add(f)`，
        # 只按 `out.add(` 数会把 27 个字段数成 28 —— 探针/文档都会跟着错。
        return @(Select-String -LiteralPath $f `
            -Pattern '^\s*out\.add\((bools|ints|floats|hexColors|enums|presetPointer)\(').Count
    } catch {
        return -1
    }
}

# ==================== 0. 读取并展示个人配置（DryRun 也会走到这里） ====================
Write-Host "========== 0/6 个人配置备份计划 =========="
$personal = Read-PersonalOptions -XmlPath $settingsXml -Drop $dropFields
$fieldCount = Get-SettingsFieldCount

if ($personal.Count -eq 0) {
    Write-Host "  未发现配置 XML（$settingsXml）—— 本次安装没有需要还原的个人配置"
} else {
    Write-Host "  将【备份并还原】以下 $($personal.Count) 个字段："
    foreach ($k in $personal.Keys) {
        $v = [string]$personal[$k]
        if ($v.Length -gt 72) { $v = $v.Substring(0, 72) + "…（共 $($personal[$k].Length) 字符）" }
        Write-Host ("    + {0,-22} = {1}" -f $k, $v)
    }
    if ($dropFields.Count -eq 0) {
        Write-Host "  将【丢弃】：无 —— 全量还原，一个字段都不丢"
    } else {
        Write-Host "  将【丢弃】（回到出厂默认）：$($dropFields -join ', ')"
    }
    Write-Host "  结构指纹（当前源码 SettingsField 字段数）：$fieldCount"
}

# 与上一次安装时记录的结构指纹对比 —— 不一致就醒目提示复核脚本
$lastStampFile = Join-Path $cfgBackupDir "last-structure.txt"
if ((Test-Path -LiteralPath $lastStampFile) -and $fieldCount -gt 0) {
    $lastCount = (Get-Content -LiteralPath $lastStampFile -TotalCount 1).Trim()
    if ($lastCount -ne [string]$fieldCount) {
        Write-Host ""
        Write-Host "  [警告] 配置结构变了：上次安装时 SettingsField 有 $lastCount 个字段，现在有 $fieldCount 个。"
        Write-Host "  [警告] 请人工复核本脚本的 dropFields 与还原逻辑（见文件头『维护须知』）。"
        Write-Host ""
    }
}

if ($DryRun) {
    Write-Host "`n[DryRun] 到此为止：没有编译、没有关闭 IDEA、没有改动任何文件。"
    exit 0
}

if ($personal.Count -gt 0) {
    New-Item -ItemType Directory -Force -Path $cfgBackupDir | Out-Null
    $snapshot = [ordered]@{
        savedAt = (Get-Date).ToString('s')
        version = $Version
        structureFingerprint = $fieldCount
        dropped = $dropFields
        options = $personal
    }
    $jsonFile = Join-Path $cfgBackupDir "personal-state-$stamp.json"
    $snapshot | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $jsonFile -Encoding UTF8
    Write-Host "  备份已存：$jsonFile"
    # 同时留一份原始 XML：万一上面「字段→值」的提取逻辑本身有问题，还能手工回滚
    if (Test-Path -LiteralPath $settingsXml) {
        Copy-Item -LiteralPath $settingsXml -Destination (Join-Path $cfgBackupDir "NeovideCursorSettings-$stamp.xml") -Force
    }
    Set-Content -LiteralPath $lastStampFile -Value ([string]$fieldCount) -Encoding UTF8
}

# ==================== 1. 编译打包 ====================
Write-Host "`n========== 1/6 编译打包 =========="
& pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'offline-build.ps1') `
    -Version $Version -OutName $jarName -BaseJar $BaseJar
if ($LASTEXITCODE -ne 0) { throw "打包失败" }

$newJar = Join-Path $repo "dist\$jarName"
if (-not (Test-Path $newJar)) { throw "产物不存在：$newJar" }

# ==================== 2. 关闭 IDEA ====================
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

# ==================== 3. 干净安装：清空旧状态 ====================
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

# 3b 删除设置 XML
# 个人配置已在第 0 步抄进 $cfgBackupDir，这里删的是「平台会读的那份」。
if (Test-Path $settingsXml) {
    Remove-Item $settingsXml -Force
    Write-Host "  已删除设置 XML（下次启动使用出厂默认）"
} else {
    Write-Host "  设置 XML 不存在（已是干净状态）"
}
# 顺手清掉平台自己可能留下的旧备份；注意**不要**碰 $cfgBackupDir
Get-ChildItem (Split-Path $settingsXml -Parent) -Filter "NeovideCursorSettings.xml.bak-*" -ErrorAction SilentlyContinue |
    ForEach-Object { Remove-Item $_.FullName -Force }

# 3c 删除诊断日志
if ((Test-Path $diagLog) -and -not $KeepLog) {
    Remove-Item $diagLog -Force
    Write-Host "  已删除诊断日志"
} elseif ($KeepLog) {
    Write-Host "  保留诊断日志（-KeepLog）"
}

# ==================== 4. 安装新 jar ====================
Write-Host "`n========== 4/6 安装新 jar =========="
$target = Join-Path $pluginLib "jetbrains-neovide-cursor-0.9.8.jar"
Copy-Item $newJar $target -Force
$len = (Get-Item $target).Length
Write-Host "  已装 $len B  ->  $target"

# ==================== 5. 自检 + 还原个人配置 ====================
Write-Host "`n========== 5/6 自检 + 还原个人配置 =========="
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

# 还原：必须在 IDEA 启动之前写。IDEA 退出时会拿内存状态覆盖这份文件，
# 所以「先启动再写」等于白写。
if ($personal.Count -gt 0) {
    Write-PersonalOptions -XmlPath $settingsXml -Map $personal -Dropped $dropFields

    # 自检：读回来核对，避免「写了一份平台看不懂的 XML」这种静默失败
    $check = Read-PersonalOptions -XmlPath $settingsXml -Drop $dropFields
    $missing = @($personal.Keys | Where-Object { -not $check.Contains($_) })
    if ($missing.Count -gt 0) {
        throw "还原自检失败：这些字段没写进去 -> $($missing -join ', ')"
    }
    Write-Host "  还原自检通过：$($check.Count)/$($personal.Count) 个字段已回写"
} else {
    Write-Host "  无个人配置需要还原"
}

# ==================== 6. 启动 IDEA ====================
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

Write-Host "`n安装完成：v$Version"
if ($personal.Count -gt 0) {
    $dropDesc = if ($dropFields.Count -eq 0) { "无（全量还原）" } else { ($dropFields -join ', ') }
    Write-Host "个人配置：已还原 $($personal.Count) 个字段；丢弃 $dropDesc"
}
