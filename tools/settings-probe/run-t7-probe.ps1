# run-t7-probe.ps1 —— t7 三条 medium findings（R-01/R-02/R-03）的回归探针 + 忠实变异
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-t7-probe.ps1
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-t7-probe.ps1 -SkipMutation
#
# 退出码：0 = 全通过（含 3 次变异全部按期望变红）；非 0 = 有失败项
#
# 设计（LESSONS 坑 12「变异必须忠实还原」）：
#   变异 = 把源码【复制】到 $tmpRoot 下，在副本上做字面替换，编译【副本】再跑探针。
#   工作区源码始终保持修复后形态，git status 全程干净。
#   每次变异先打印 git diff --no-index，确认删除行恰好是修法引入的那几行。
#
# 安全：不使用 PowerShell 自动变量名（$home/$env/$args/$input/$PSHOME/$host/$pwd/$error/$matches）做普通赋值。
#      函数名用驼峰 —— pwsh 7.6.6 上 Build-And-Probe 这类两段连字符名能定义成功但调用被解析成 Build-AndProbe。
#      所有临时产物都在 $tmpRoot（= build\ 下），绝不 cd 到仓库根、绝不在仓库根解包。

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs',
    [switch]$SkipMutation
)

$ErrorActionPreference = 'Stop'
$tmpRoot = Join-Path $Repo 'build\probe-t7'
$libsDir = $Libs
$srcMain = Join-Path $Repo 'src\main\java'

function Step($m) { Write-Host "==> $m" }

if (-not (Test-Path -LiteralPath $libsDir)) { throw "idea-libs 不存在：$libsDir" }
if (-not (Test-Path -LiteralPath $srcMain)) { throw "源码目录不存在：$srcMain" }

$classpath = (Get-ChildItem -LiteralPath $libsDir -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
Step "idea-libs jar 数 = $((Get-ChildItem -LiteralPath $libsDir -Filter '*.jar').Count)"

New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null

# 平台 UI 组件会找 installation home（product-info.json），给一个假安装目录。
# 变量名必须是 $fakeHome（$home 是 PowerShell 只读自动变量，赋值会静默失败）。
$fakeHome = Join-Path $tmpRoot 'fake-idea-home'
if (-not (Test-Path -LiteralPath $fakeHome)) { New-Item -ItemType Directory -Force -Path $fakeHome | Out-Null }
$productInfo = '{ "name": "IntelliJ IDEA", "version": "2024.2", "buildNumber": "242.0", "productCode": "IC", "dataDirectoryName": "IdeaIC2024.2", "svgIconPath": "bin/idea.svg", "launcherPath": "bin/idea.bat" }'
$productInfo | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $fakeHome 'product-info.json')

$swingArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Djava.awt.headless=true',
    "-Didea.home.path=$fakeHome",
    "-Didea.config.path=$(Join-Path $tmpRoot 'cfg')",
    "-Didea.system.path=$(Join-Path $tmpRoot 'sys')",
    '--add-opens=java.desktop/javax.swing=ALL-UNNAMED',
    '--add-opens=java.desktop/java.awt=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.swing=ALL-UNNAMED'
)

<#
  编译一棵源码树 + ProbeT7，返回探针 class 目录。
  $SourceRoot = src\main\java 的根（正常 = 工作区；变异 = 副本）
#>
function BuildProbeTree([string]$SourceRoot, [string]$Tag) {
    $classes = Join-Path $tmpRoot "classes-$Tag"
    $probeOut = Join-Path $tmpRoot "probe-$Tag"
    if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
    if (Test-Path -LiteralPath $probeOut) { Remove-Item -LiteralPath $probeOut -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    New-Item -ItemType Directory -Force -Path $probeOut | Out-Null

    $srcs = @(Get-ChildItem -Recurse -File -Filter *.java $SourceRoot | ForEach-Object { $_.FullName })
    Step "[$Tag] javac 源码树（$($srcs.Count) 个 .java）"
    & javac --release 21 -nowarn -g -encoding UTF-8 -classpath $classpath -d $classes $srcs
    if ($LASTEXITCODE -ne 0) { throw "[$Tag] 源码树编译失败" }

    Step "[$Tag] javac ProbeT7"
    & javac --release 21 -nowarn -encoding UTF-8 -classpath "$classes;$classpath" -d $probeOut (Join-Path $Repo 'tools\settings-probe\ProbeT7.java')
    if ($LASTEXITCODE -ne 0) { throw "[$Tag] 探针编译失败" }
    return $probeOut
}

function RunProbe([string]$ProbeOut, [string]$Classes, [string]$Snapshot) {
    $raw = & java @swingArgs -cp "$ProbeOut;$Classes;$classpath" ProbeT7 2>&1
    $code = $LASTEXITCODE
    $text = ($raw | Out-String)
    Set-Content -LiteralPath $Snapshot -Value $text -Encoding UTF8
    return @{ Code = $code; Output = $text }
}

function ShowTail([string]$Text, [int]$N = 14) {
    $lines = $Text -split "\r?\n"
    $from = [Math]::Max(0, $lines.Count - $N)
    for ($i = $from; $i -lt $lines.Count; $i++) { Write-Host "    $($lines[$i])" }
}

# ==================== 阶段 1：修复后形态，必须全绿 ====================
Write-Host ''
Write-Host '############ 阶段 1 / 修复后形态：探针必须全绿 ############'
$fixedClasses = Join-Path $tmpRoot 'classes-fixed'
$fixedProbe = BuildProbeTree $srcMain 'fixed'

$r = RunProbe $fixedProbe $fixedClasses (Join-Path $tmpRoot 'fixed-t7.txt')
Write-Host ''
Write-Host "  ProbeT7 exit=$($r.Code)"
$r.Output -split "\r?\n" | Where-Object { $_ -match '^\s+(PASS|FAIL)\s' -or $_ -match '^--- ' } | ForEach-Object { Write-Host $_ }
if ($r.Code -ne 0) {
    Write-Host '  ---- 输出尾部 ----'
    ShowTail $r.Output 40
    throw 'ProbeT7 在修复后形态上失败（应全绿）'
}
Write-Host '  修复后形态：全绿 OK'

if ($SkipMutation) {
    Write-Host ''
    Write-Host 'ALL T7 PROBES OK（已跳过变异测试）'
    exit 0
}

# ==================== 阶段 2：三次忠实变异，必须各自变红 ====================
Write-Host ''
Write-Host '############ 阶段 2 / 变异测试（回滚修法后探针必须变红）############'

function RunMutation([string]$Name, [string]$File, [string]$Old, [string]$New) {
    Write-Host ''
    Write-Host "---- $Name ----"
    $mutRoot = Join-Path $tmpRoot "mut-src-$Name"
    if (Test-Path -LiteralPath $mutRoot) { Remove-Item -LiteralPath $mutRoot -Recurse -Force }
    Copy-Item -LiteralPath $srcMain -Destination $mutRoot -Recurse -Force

    $target = Join-Path $mutRoot $File
    if (-not (Test-Path -LiteralPath $target)) { throw "$Name：副本里找不到 $File" }
    $text = [System.IO.File]::ReadAllText($target)
    if (-not $text.Contains($Old)) { throw "$Name：副本里找不到待替换的字面（源码可能已变，请同步更新本脚本）" }
    $mutated = $text.Replace($Old, $New)
    [System.IO.File]::WriteAllText($target, $mutated, (New-Object System.Text.UTF8Encoding($false)))

    Write-Host '  变异 diff（副本 vs 工作区，仅本文件）：'
    $diff = & git -C $Repo diff --no-index --unified=1 -- (Join-Path $srcMain $File) $target 2>&1
    $diff | ForEach-Object { Write-Host "    $_" }

    $mutProbe = BuildProbeTree $mutRoot "mut-$Name"
    $mutClasses = Join-Path $tmpRoot "classes-mut-$Name"
    $res = RunProbe $mutProbe $mutClasses (Join-Path $tmpRoot "mut-$Name.txt")
    Write-Host "  ProbeT7 exit=$($res.Code)（期望非 0）"
    if ($res.Code -eq 0) {
        Write-Host '  ---- 输出尾部 ----'
        ShowTail $res.Output 40
        throw "$Name：变异后探针仍然全绿 —— 说明该修法没有被探针覆盖，或变异不是忠实还原"
    }
    $failed = @($res.Output -split "\r?\n" | Where-Object { $_ -match '^\s+FAIL\s' })
    Write-Host "  变红断言数 = $($failed.Count)"
    $failed | ForEach-Object { Write-Host "    $_" }
    return $failed.Count
}

$confFile = 'com\github\yyyolo7a79\neovidecursor\ui\settings\NeovideCursorConfigurable.java'
$mgrFile = 'com\github\yyyolo7a79\neovidecursor\ui\NeovideCaretManager.java'
$sessionFile = 'com\github\yyyolo7a79\neovidecursor\ui\settings\EditSession.java'

# ---- MUT-R01：回滚「pending 与 baseline 同源前移」 ----
# 【0.9.9 Phase 1 同步】原来回滚的是 apply() 里那一行 `pending = deepCopy(target);`。
# 该时序已收进 EditSession.reloadFrom()（两个字段一起前移，结构上漏不掉），
# 所以「修复前形态」现在等价于：只前移 baseline、不动 pending —— 正是 R-01 那个 bug。
$red1 = RunMutation 'MUT-R01-rollback-pending-advance' $sessionFile @'
    void reloadFrom(NeovideCursorSettings.State target) {
        NeovideCursorSettings.State copy = deepCopy(target);
        this.baseline = copy;
        this.pending = deepCopy(copy);
    }
'@ @'
    void reloadFrom(NeovideCursorSettings.State target) {
        this.baseline = deepCopy(target);
    }
'@

# ---- MUT-R02：回滚滑块 ChangeListener 里的 refreshPresetDisplay() ----
$red2 = RunMutation 'MUT-R02-rollback-slider-preset-refresh' $confFile @'
            refreshDerivedHints();
            // ④ 预设下拉的显示必须跟着变（t7 / R-02）。
            //    pending 一变，原先匹配上的预设就可能不再匹配 → 必须重算，
            //    否则下拉继续显示「原版粉色」，而实际值早已不是它（显示层撒谎）。
            refreshPresetDisplay();
'@ @'
            refreshDerivedHints();
'@

# ---- MUT-R03：回滚「恢复所有历史 scheme」→ 只恢复当前那一个（忠实还原 t5 审查时的形态）----
# 形态：把「按记录逐个恢复」的整个循环体挖空，只保留 clear()，
#       于是只剩兜底那一步「恢复 getGlobalScheme()」—— 正是修复前的行为。
# 【t6 同步】锚点已随 t6 的 R-17 改动更新（循环体改成 iterator + it.remove()），
# 这里挖掉「真正写回颜色」那一行即可让整表都不恢复 —— 形态仍是「只恢复当前 scheme」。
$red3 = RunMutation 'MUT-R03-rollback-multi-scheme-restore' $mgrFile @'
                        s.setColor(EditorColors.CARET_COLOR, original);
                        any = true;
                        it.remove();          // ← 只删恢复成功的
'@ @'
                        // 【变异】只恢复当前 scheme（修复前的行为），历史 scheme 一律不碰
'@

# ---- MUT-R09a：只回滚「(a) putIfAbsent → put」（保留 (b) 兜底段判据）----
# 期望：T-03i-4 变红。证明 (a) 单独是必要的。
$red4 = RunMutation 'MUT-R09a-rollback-putIfAbsent' $mgrFile @'
                HIDDEN_SCHEMES.putIfAbsent(scheme, savedCaretColor);
'@ @'
                HIDDEN_SCHEMES.put(scheme, savedCaretColor);
'@

# ---- MUT-R09b：只回滚「(b) 兜底段跳过判据」（保留 (a) putIfAbsent）----
# 期望：T-03i-4 【也】变红 —— 这正是 t8 的核心论断「只改 (a) 不够」：
#       记录值虽然对了，但兜底段仍会用兜底色把记录恢复出的正确原色再盖一次。
$red5 = RunMutation 'MUT-R09b-rollback-fallback-skip' $mgrFile @'
            Color current = currentHandledByMap ? null : savedCaretColor;
'@ @'
            Color current = savedCaretColor;
'@

# ---- MUT-R17：回滚「只删恢复成功的条目」→ 保留无条件 clear() 的形态 ----
# 期望：T-17b 变红（记录被清成 0，无法重试）。
$red6 = RunMutation 'MUT-R17-rollback-unconditional-clear' $mgrFile @'
                        any = true;
                        it.remove();          // ← 只删恢复成功的
'@ @'
                        any = true;
'@

# ---- MUT-R18：回滚「按 scheme 早退」→ 全局 savedCaretColor 早退（修复前形态）----
# 期望：T-18b 变红（同内容的另一个对象 B 被早退、仍可见）。
$red7 = RunMutation 'MUT-R18-rollback-global-earlyreturn' $mgrFile @'
            if (!force) {
                boolean alreadyHidden;
                synchronized (HIDDEN_SCHEMES) {
                    alreadyHidden = HIDDEN_SCHEMES.containsKey(scheme);
                }
                if (alreadyHidden) {
                    return; // 这个 scheme 已经隐藏过，无需重复操作
                }
            }
'@ @'
            if (!force && savedCaretColor != null) {
                return; // 【变异】全局早退（修复前形态）
            }
'@

Write-Host ''
Write-Host '############ 汇总 ############'
Write-Host "  修复后形态                        : 全绿 OK"
Write-Host "  MUT-R01（回滚 pending 前移）      : 变红断言 $red1 条"
Write-Host "  MUT-R02（回滚滑块预设刷新）       : 变红断言 $red2 条"
Write-Host "  MUT-R03（回滚多 scheme 恢复）     : 变红断言 $red3 条"
Write-Host "  MUT-R09a（回滚 putIfAbsent）      : 变红断言 $red4 条"
Write-Host "  MUT-R09b（回滚兜底段跳过判据）    : 变红断言 $red5 条"
Write-Host "  MUT-R17（回滚只删成功条目）       : 变红断言 $red6 条"
Write-Host "  MUT-R18（回滚按 scheme 早退）     : 变红断言 $red7 条"
Write-Host ''
Write-Host 'ALL T7 PROBES OK'
