# run-v-mutations.ps1 —— verifier 自建（t4）：忠实变异反向验证
#
# 做法（LESSONS.md 坑 12）：
#   把 src/main/java 复制到 build/v-mut/<变异名>/src，只在【副本】上做字面替换，
#   然后编译副本 + 跑探针，确认探针变红。工作区源码始终是修复后形态，git diff 永远干净。
#
# 每个变异都打印 diff（副本 vs 工作区），确认删除/改写的恰好是修法引入的那几行。
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-v-mutations.ps1
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\run-v-mutations.ps1 -Only MUT-A
#
# 退出码：0 = 全部按期望变红且互不连带；非 0 = 有变异没变红（探针覆盖不足）或变异无效。
#
# 安全：不使用 PowerShell 自动变量名（$home/$env/$args/$input/$PSHOME/$host/$pwd/$error/$matches）做赋值。

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs',
    [string]$Only = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = $Repo
$tmpRoot = Join-Path $repoRoot 'build\v-mut'
$srcMain = Join-Path $repoRoot 'src\main\java'
$toolDir = Join-Path $repoRoot 'tools\settings-probe'
$fakeHome = Join-Path $repoRoot 'build\v2-probe\fake-home'

if (-not (Test-Path -LiteralPath $fakeHome)) { throw "fake-home 不存在，先跑一次 build-v-probes.ps1" }

$cp = (Get-ChildItem -LiteralPath $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
$jvm = @(
    '-Dfile.encoding=UTF-8',
    '-Djava.awt.headless=true',
    "-Didea.home.path=$fakeHome",
    '--add-opens=java.desktop/javax.swing=ALL-UNNAMED',
    '--add-opens=java.desktop/java.awt=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.swing=ALL-UNNAMED',
    '--add-opens=java.desktop/java.awt.event=ALL-UNNAMED'
)

$confFile = 'com\github\yyyolo7a79\neovidecursor\ui\settings\NeovideCursorConfigurable.java'
$mgrFile = 'com\github\yyyolo7a79\neovidecursor\ui\NeovideCaretManager.java'

# ------------------------------------------------------------------ 变异定义
# 每项：Name / File / 说明 / 变换（对副本文件文本做函数式改写）/ 期望变红的探针 / 期望红掉的断言前缀
$mutations = @(
    @{
        Name = 'MUT-A'
        File = $confFile
        What = '回滚缺陷 1 的修法（删除显式 slider.addChangeListener 整段）'
        Transform = {
            param($text)
            $old = @'
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

'@
            if (-not $text.Contains($old)) { throw 'MUT-A：找不到 ChangeListener 整段（源码已变，请同步本脚本）' }
            return $text.Replace($old, '')
        }
        Probes = @('VDefects')
        # 契约 §11.1 的 MUT-3 期望集写的是「V2 + V11 断言 1」。
        # 独立复跑发现断言 1 在去掉监听器后【仍然为真】（super.isModified() 依然能看见
        # 组件值 vs getter 的差），所以这里只要求 D1-1 / D1-2 变红；
        # 与契约的这处偏差已写进 VERIFY-SETTINGS-V2.md 的「与契约/实现者自述的差异」。
        Expect = @('D1-1', 'D1-2')
    },
    @{
        Name = 'MUT-B'
        File = $confFile
        What = '回滚缺陷 2 的修法（恢复「整条网格都当刻度标签」= 修复前形态）'
        Transform = {
            param($text)
            $old = @'
        Map<Integer, JComponent> marks = new LinkedHashMap<>();
        marks.put(spec.min, new JLabel(spec.tickText.apply(spec.min)));
        int mid = spec.min + ((spec.max - spec.min) / 2 / spec.step) * spec.step;   // 必须对齐到 step
        if (mid > spec.min && mid < spec.max) {
            marks.put(mid, new JLabel(spec.tickText.apply(mid)));
        }
        marks.put(spec.max, new JLabel(spec.tickText.apply(spec.max)));
        SliderKt.labelTable(cell, marks);
        // labelTable 内部是 new Hashtable + setLabelTable，【不会】替你打开 paintLabels → 必须显式设置
        b.slider.setPaintLabels(true);
        b.slider.setPaintTicks(false);     // 不画刻度线，只保留 label 的位置语义
'@
            # 修复前形态：每个 major tick 都有一个标签（条目数 = (max-min)/step + 1）
            $new = @'
        Map<Integer, JComponent> marks = new LinkedHashMap<>();
        for (int t = spec.min; t <= spec.max; t += spec.step) {
            marks.put(t, new JLabel(spec.tickText.apply(t)));
        }
        SliderKt.labelTable(cell, marks);
        b.slider.setPaintLabels(true);
        b.slider.setPaintTicks(true);
'@
            if (-not $text.Contains($old)) { throw 'MUT-B：找不到三关键刻度整段（源码已变，请同步本脚本）' }
            return $text.Replace($old, $new)
        }
        Probes = @('VDefects')
        Expect = @('D2-1')
    },
    @{
        Name = 'MUT-C'
        File = $confFile
        What = '回滚缺陷 3 的修法（字段级脏标记 → 无条件全量写 = 修复前形态）'
        Transform = {
            param($text)
            $n = 0
            # 逐字段 if 的三种写法统统改成恒真分支：等价于原来的「无条件下发」
            $text = [regex]::Replace($text, 'if \(pending\.\w+ != baseline\.\w+\) \{', { param($m) $script:n++; 'if (true) {' })
            $text = [regex]::Replace($text, 'if \(Float\.compare\(pending\.(\w+), baseline\.\1\) != 0\) \{', { param($m) $script:n++; 'if (true) {' })
            $text = [regex]::Replace($text, 'if \(!eqHex\(pending\.(\w+), baseline\.\1\)\) \{', { param($m) $script:n++; 'if (true) {' })
            if ($script:n -lt 15) { throw ("MUT-C：只匹配到 " + $script:n + " 处脏标记判断（期望 ≥ 15，源码已变请同步本脚本）") }
            return $text
        }
        Probes = @('VDefects')
        Expect = @('D3-1', 'D3-2')
    },
    @{
        Name = 'MUT-D'
        File = $mgrFile
        What = '回滚缺陷 4 的修法（force 重入判断改回无条件早退）'
        Transform = {
            param($text)
            # 【t6 同步】R-18 把早退从「全局 savedCaretColor」改成了「按 scheme 判定」。
            # 回滚成修复前形态 = 换回全局早退。
            $old = @'
            if (!force) {
                boolean alreadyHidden;
                synchronized (HIDDEN_SCHEMES) {
                    alreadyHidden = HIDDEN_SCHEMES.containsKey(scheme);
                }
                if (alreadyHidden) {
                    return; // 这个 scheme 已经隐藏过，无需重复操作
                }
            }
'@
            # 缺陷 4 的【修复前形态】是「完全没有 force 参数」—— 即无条件早退。
            # 注意不要写成 'if (!force && savedCaretColor != null)'：那还保留了 force 分支，
            # 变异体仍能绕过早退，探针不会红（我第一版就是这么写错的）。
            $new = @'
            if (savedCaretColor != null) {
                return; // 【变异】无条件早退（修复前形态，无 force 分支）
            }
'@
            if (-not $text.Contains($old)) { throw 'MUT-D：找不到 force 判断段（源码已变，请同步本脚本）' }
            return $text.Replace($old, $new)
        }
        Probes = @('VScheme')
        Expect = @('S-2 ', 'S-2b', 'S-3 ', 'S-4d')
    },
    @{
        Name = 'MUT-E'
        File = $confFile
        What = '回滚 V11 修法 2（isModified 去掉 pending-vs-state 那一半 → B-1）'
        Transform = {
            param($text)
            $old = @'
        return super.isModified()
                || colorPanelModified(cur)
                || dirtyAnyField(pending, cur);
'@
            $new = @'
        return super.isModified() || colorPanelModified(cur);
'@
            if (-not $text.Contains($old)) { throw 'MUT-E：找不到 isModified 三半表达式（源码已变，请同步本脚本）' }
            return $text.Replace($old, $new)
        }
        Probes = @('VDefects')
        Expect = @('V11-1')
    },
    @{
        Name = 'MUT-F'
        File = $confFile
        What = '回滚 V11 修法 3（滑块 getter 不再 snap → B-2）'
        Transform = {
            param($text)
            $old = @'
                (Function0<Integer>) () -> snapToTick(spec.readTick.applyAsInt(pending),
                        spec.min, spec.max, spec.step),
'@
            $new = @'
                (Function0<Integer>) () -> spec.readTick.applyAsInt(pending),
'@
            if (-not $text.Contains($old)) { throw 'MUT-F：找不到 bindValue 的 snap getter（源码已变，请同步本脚本）' }
            return $text.Replace($old, $new)
        }
        Probes = @('VDefects')
        Expect = @('V11-3c')
    }
)

# ------------------------------------------------------------------ 副本编译 + 跑探针
function BuildCopy([string]$mutRoot, [string]$tag) {
    $cls = Join-Path $mutRoot "classes-$tag"
    $pb = Join-Path $mutRoot "probe-$tag"
    if (Test-Path -LiteralPath $cls) { Remove-Item -LiteralPath $cls -Recurse -Force }
    if (Test-Path -LiteralPath $pb) { Remove-Item -LiteralPath $pb -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $cls, $pb | Out-Null

    $srcs = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $mutRoot 'src') |
        ForEach-Object { $_.FullName })
    & javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $cls $srcs 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "[$tag] 变异源码树编译失败（变异不合法，结论作废）" }

    $vprobes = @(Get-ChildItem -LiteralPath $toolDir -Filter 'V*.java' | ForEach-Object { $_.FullName })
    & javac --release 21 -nowarn -encoding UTF-8 -classpath "$cls;$cp" -d $pb $vprobes 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "[$tag] 探针编译失败" }
    return @{ Classes = $cls; Probe = $pb }
}

$failures = 0
$results = @()

New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null

foreach ($m in $mutations) {
    if ($Only -ne '' -and $m.Name -ne $Only) { continue }

    Write-Host ''
    Write-Host ('########## ' + $m.Name + '  ' + $m.What + ' ##########')

    $mutRoot = Join-Path $tmpRoot $m.Name
    if (Test-Path -LiteralPath $mutRoot) { Remove-Item -LiteralPath $mutRoot -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $mutRoot | Out-Null
    Copy-Item -LiteralPath $srcMain -Destination (Join-Path $mutRoot 'src') -Recurse -Force

    $target = Join-Path (Join-Path $mutRoot 'src') $m.File
    if (-not (Test-Path -LiteralPath $target)) { throw ($m.Name + '：副本里找不到 ' + $m.File) }
    $before = [System.IO.File]::ReadAllText($target)
    $after = & $m.Transform $before
    if ($after -eq $before) { throw ($m.Name + '：变换没有产生任何修改，变异无效') }
    [System.IO.File]::WriteAllText($target, $after, (New-Object System.Text.UTF8Encoding($false)))

    # 变异忠实性：diff 里出现的行必须恰好是修法引入的那几行
    Write-Host '  --- 变异 diff（副本 vs 工作区，仅本文件）---'
    $diff = & git -C $repoRoot diff --no-index --unified=1 -- (Join-Path $srcMain $m.File) $target 2>&1
    $diff | ForEach-Object { Write-Host ('    ' + $_) }

    $built = BuildCopy $mutRoot $m.Name

    foreach ($probe in $m.Probes) {
        $out = Join-Path $mutRoot ($probe + '.txt')
        & java @jvm -cp "$($built.Probe);$($built.Classes);$cp" $probe 2>&1 |
            Where-Object { $_ -notmatch 'Do not use an alarm' -and $_ -notmatch '已停用' } |
            Set-Content -LiteralPath $out -Encoding UTF8
        $code = $LASTEXITCODE
        $lines = @(Get-Content -LiteralPath $out -Encoding UTF8)
        # t6 / t10-(R-20)：只匹配「断言行」，必须锚行首空白 + FAIL + 空白。
        # 原来的宽松 'FAIL ' 会把探针自己的汇总行
        #   === V-DEFECTS 结果：PASS 16，FAIL 3 ===
        # 也算成一条变红 → 每个变异多报 1 条，Red 列数字全部不可复现
        # （真实 12/11/3/4/1/2，旧脚本报 13/12/4/5/2/3）。
        # 与 run-t7-probe.ps1 的 '^\s+FAIL\s' 口径对齐。
        $reds = @($lines | Where-Object { $_ -match '^\s+FAIL\s' })

        Write-Host ('  探针 ' + $probe + ' exit=' + $code + '（期望非 0）  变红 ' + $reds.Count + ' 条')
        $reds | ForEach-Object { Write-Host ('    ' + $_.Trim()) }

        if ($code -eq 0) {
            Write-Host ('  !! 变异后探针仍然全绿 —— 该修法没有被探针覆盖，或变异不忠实')
            $failures++
        }
        foreach ($e in $m.Expect) {
            $hit = @($reds | Where-Object { $_ -match [regex]::Escape($e) })
            if ($hit.Count -eq 0) {
                Write-Host ('  !! 期望变红的断言 "' + $e + '" 没有变红')
                $failures++
            } else {
                Write-Host ('  OK 期望变红：' + $e + '  -> ' + $hit.Count + ' 条')
            }
        }
        $results += [pscustomobject]@{
            Mutation = $m.Name
            Probe = $probe
            Exit = $code
            Red = $reds.Count
            Expected = ($m.Expect -join ', ')
        }
    }
}

Write-Host ''
Write-Host '########## 汇总 ##########'
$results | Format-Table -AutoSize | Out-String -Width 120 | Write-Host

# 互不连带：每个变异只允许打红「它自己该打红」的那几条
Write-Host '互不连带核查（同一次变异里变红的断言必须属于该变异的期望集）：'
foreach ($m in $mutations) {
    if ($Only -ne '' -and $m.Name -ne $Only) { continue }
    $out = Join-Path (Join-Path $tmpRoot $m.Name) 'VDefects.txt'
    if ((Test-Path -LiteralPath $out) -and ($m.Probes -contains 'VDefects')) {
        # t6 / t10-(R-20)：同样只取断言行（口径与上面一致）
        $reds = @(Get-Content -LiteralPath $out -Encoding UTF8 | Where-Object { $_ -match '^\s+FAIL\s' })
        $stray = @()
        foreach ($r in $reds) {
            $isExpected = $false
            foreach ($e in $m.Expect) { if ($r -match [regex]::Escape($e)) { $isExpected = $true } }
            if (-not $isExpected) { $stray += $r.Trim() }
        }
        if ($stray.Count -gt 0) {
            Write-Host ('  ' + $m.Name + ' 连带变红 ' + $stray.Count + ' 条：')
            $stray | ForEach-Object { Write-Host ('     ' + $_) }
        } else {
            Write-Host ('  ' + $m.Name + ' 无连带 ✓')
        }
    }
}

Write-Host ''
if ($failures -gt 0) {
    Write-Host ('V-MUTATIONS RESULT: FAILED（' + $failures + ' 项不符合期望）')
    exit 1
}
Write-Host 'V-MUTATIONS RESULT: ALL PASS（每个修法回滚后探针都按期望变红）'
exit 0
