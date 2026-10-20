# run-4bugs-probe.ps1 —— t2 的 4 个缺陷探针一键复跑 + 变异测试反向验证
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-4bugs-probe.ps1
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-4bugs-probe.ps1 -SkipMutation
#
# 退出码：0 = 全通过（含 4 次变异全部按期望变红）；非 0 = 有失败项
#
# 设计要点（LESSONS.md 坑 12「变异必须忠实还原」）：
#   变异 = 把源码【复制】到 $tmpRoot 下，在副本上做字面替换，
#   然后编译【副本】并跑探针。工作区源码始终保持修复后形态，
#   因此 git diff 永远是干净的，不会有人误提交变异版本。
#   每次变异后打印 diff（副本 vs 工作区），确认删除的恰好是修复引入的那几行。
#
# 安全：本脚本不使用 PowerShell 自动变量名（$home/$env/$args/$input/$PSHOME/$host/$pwd/$error/$matches）做普通赋值。
#      所有临时产物都在 $tmpRoot（= build\ 下），绝不 cd 到仓库根、绝不在仓库根解包。

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs',
    [switch]$SkipMutation
)

$ErrorActionPreference = 'Stop'
$tmpRoot = Join-Path $Repo 'build\probe-4bugs'
$libsDir = $Libs
$srcMain = Join-Path $Repo 'src\main\java'

function Step($m) { Write-Host "==> $m" }

if (-not (Test-Path -LiteralPath $libsDir)) { throw "idea-libs 不存在：$libsDir" }
if (-not (Test-Path -LiteralPath $srcMain)) { throw "源码目录不存在：$srcMain" }

$classpath = (Get-ChildItem -LiteralPath $libsDir -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
Step "idea-libs jar 数 = $((Get-ChildItem -LiteralPath $libsDir -Filter '*.jar').Count)"

New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null

# 平台 UI 组件会找 installation home（product-info.json），所以给一个假安装目录。
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
  编译一棵源码树 + 两个探针，返回探针的 class 输出目录。
  $SourceRoot = src\main\java 的根（正常 = 工作区；变异 = 副本）
  $Tag        = 用于命名中间目录
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

    foreach ($p in @('Probe4Bugs', 'ProbeSchemeChange')) {
        & javac --release 21 -nowarn -encoding UTF-8 -classpath "$classes;$classpath" -d $probeOut (Join-Path $Repo "tools\settings-probe\$p.java")
        if ($LASTEXITCODE -ne 0) { throw "[$Tag] 探针 $p 编译失败" }
    }
    return $probeOut
}

<# 跑一个探针，返回 @{ Code; Output }。#>
function Run-Probe([string]$ProbeOut, [string]$Classes, [string]$Main, [string]$Snapshot) {
    $out = & java @swingArgs -cp "$ProbeOut;$Classes;$classpath" $Main 2>&1
    $code = $LASTEXITCODE
    $text = ($out | Out-String)
    Set-Content -LiteralPath $Snapshot -Value $text -Encoding UTF8
    return @{ Code = $code; Output = $text }
}

function Show-Tail([string]$Text, [int]$N = 12) {
    $lines = $Text -split "\r?\n"
    $from = [Math]::Max(0, $lines.Count - $N)
    for ($i = $from; $i -lt $lines.Count; $i++) { Write-Host "    $($lines[$i])" }
}

# ==================== 阶段 1：修复后形态，必须全绿 ====================
Write-Host ''
Write-Host '############ 阶段 1 / 修复后形态：探针必须全绿 ############'
$fixedClasses = Join-Path $tmpRoot 'classes-fixed'
$fixedProbe = BuildProbeTree $srcMain 'fixed'

$r1 = Run-Probe $fixedProbe $fixedClasses 'Probe4Bugs' (Join-Path $tmpRoot 'fixed-4bugs.txt')
Write-Host ''
Write-Host "  Probe4Bugs        exit=$($r1.Code)"
if ($r1.Code -ne 0) {
    Write-Host '  ---- 输出尾部 ----'
    Show-Tail $r1.Output 40
    throw 'Probe4Bugs 在修复后形态上失败（应全绿）'
}

$r2 = Run-Probe $fixedProbe $fixedClasses 'ProbeSchemeChange' (Join-Path $tmpRoot 'fixed-scheme.txt')
Write-Host "  ProbeSchemeChange exit=$($r2.Code)"
if ($r2.Code -ne 0) {
    Write-Host '  ---- 输出尾部 ----'
    Show-Tail $r2.Output 40
    throw 'ProbeSchemeChange 在修复后形态上失败（应全绿）'
}
Write-Host '  修复后形态：两个探针全绿 OK'

if ($SkipMutation) {
    Write-Host ''
    Write-Host 'ALL 4BUG PROBES OK（已跳过变异测试）'
    exit 0
}

# ==================== 阶段 2：四次忠实变异，必须各自变红 ====================
Write-Host ''
Write-Host '############ 阶段 2 / 变异测试（回滚修法后探针必须变红）############'

<#
  在副本源码树上做一次字面替换，然后编译 + 跑探针。
  $Name       变异名（用于日志）
  $File       相对 src\main\java 的文件路径
  $Old        要被替换掉的字面（必须是修复引入的那几行）
  $New        替换成修复前的形态
  $ExpectMain 期望变红的探针
#>
function Run-Mutation([string]$Name, [string]$File, [string]$Old, [string]$New, [string]$ExpectMain) {
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

    # 变异必须「忠实」：diff 里删除的行必须恰好是本修法引入的那几行
    Write-Host '  变异 diff（副本 vs 工作区，仅本文件）：'
    $diff = & git -C $Repo diff --no-index --unified=1 -- (Join-Path $srcMain $File) $target 2>&1
    $diff | ForEach-Object { Write-Host "    $_" }

    $mutProbe = BuildProbeTree $mutRoot "mut-$Name"
    $mutClasses = Join-Path $tmpRoot "classes-mut-$Name"
    $res = Run-Probe $mutProbe $mutClasses $ExpectMain (Join-Path $tmpRoot "mut-$Name.txt")
    Write-Host "  $ExpectMain exit=$($res.Code)（期望非 0）"
    if ($res.Code -eq 0) {
        Write-Host '  ---- 输出尾部 ----'
        Show-Tail $res.Output 40
        throw "$Name：变异后 $ExpectMain 仍然全绿 —— 说明该修法没有被探针覆盖，或变异不是忠实还原"
    }
    $failed = @($res.Output -split "\r?\n" | Where-Object { $_ -match '^\s+FAIL\s' })
    Write-Host "  变红断言数 = $($failed.Count)"
    $failed | ForEach-Object { Write-Host "    $_" }
    return $failed.Count
}

$confFile = 'com\github\yyyolo7a79\neovidecursor\ui\settings\NeovideCursorConfigurable.java'
$mgrFile = 'com\github\yyyolo7a79\neovidecursor\ui\NeovideCaretManager.java'

$red1 = Run-Mutation 'MUT-1-rollback-isModified' $confFile @'
        NeovideCursorSettings.State cur = currentState();
        return super.isModified()
                || colorPanelModified(cur)
                || dirtyAnyField(pending, cur);
'@ @'
        NeovideCursorSettings.State cur = currentState();
        return super.isModified() || colorPanelModified(cur);
'@ 'Probe4Bugs'

$red2 = Run-Mutation 'MUT-2-rollback-snap' $confFile @'
                (Function0<Integer>) () -> snapToTick(spec.readTick.applyAsInt(pending),
                        spec.min, spec.max, spec.step),
'@ @'
                (Function0<Integer>) () -> spec.readTick.applyAsInt(pending),
'@ 'Probe4Bugs'

$red3 = Run-Mutation 'MUT-3-rollback-changelistener' $confFile @'
        b.slider.addChangeListener(e -> {
            if (syncingControls) {
                return;                                  // 程序化回灌，不回写
            }
            int v = b.slider.getValue();                 // 已被 JSlider 吸附过
            spec.writeTick.accept(pending, v);           // ① 写 pending（脏标记需要）
            valueLabel.setText(spec.valueText.apply(this, v));   // ② 右侧标签实时跟随 ← 缺陷 1
            syncTextFieldIfDifferent(b, v);              // ③ 输入框文字同步
            refreshDerivedHints();
            // ④ 预设下拉的显示必须跟着变（t7 / R-02）。
            //    pending 一变，原先匹配上的预设就可能不再匹配 → 必须重算，
            //    否则下拉继续显示「原版粉色」，而实际值早已不是它（显示层撒谎）。
            refreshPresetDisplay();
        });

'@ '' 'Probe4Bugs'

# 【t6 同步】R-18 把早退判据改成「按 scheme 判定」；回滚成修复前形态 = 无条件早退
# （缺陷 4 的修复前形态根本没有 force 分支，所以不能写成 '!force && ...'）。
$red4 = Run-Mutation 'MUT-4-rollback-force-flag' $mgrFile @'
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
            if (savedCaretColor != null) {
                return; // 【变异】无条件早退（修复前形态，无 force 分支）
            }
'@ 'ProbeSchemeChange'

Write-Host ''
Write-Host '############ 汇总 ############'
Write-Host "  修复后形态                          : 全绿 OK"
Write-Host "  MUT-1（回滚 isModified 的第 3 半）   : 变红断言 $red1 条"
Write-Host "  MUT-2（回滚 snapToTick）             : 变红断言 $red2 条"
Write-Host "  MUT-3（回滚 ChangeListener）         : 变红断言 $red3 条"
Write-Host "  MUT-4（回滚 force 重入）             : 变红断言 $red4 条"
Write-Host ''
Write-Host 'ALL 4BUG PROBES OK'
