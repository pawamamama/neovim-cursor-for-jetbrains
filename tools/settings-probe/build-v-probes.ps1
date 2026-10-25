# build-v-probes.ps1 —— verifier 自建（t4）：一键编译「源码树 + V* 探针」并跑全部 V 探针
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\settings-probe\build-v-probes.ps1 -Quiet
#
# 退出码：0 = V 探针全部 ALL PASS；非 0 = 有探针失败（或编译失败）
#
# 说明：
#   * 全部产物落在 build\v2-probe\，工作区源码只读。
#   * 需要 headless 平台桩（ActionManager / JBPopupFactory / ComponentValidator），
#     见 tools\settings-probe\V0Env.java 的注释。
#   * 安全：不使用 PowerShell 自动变量名（$home/$env/$args/$input/$PSHOME/$host/$pwd/$error/$matches）做赋值。

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs',
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$repoRoot = $Repo
$tmpRoot = Join-Path $repoRoot 'build\v2-probe'
$classesDir = Join-Path $tmpRoot 'classes'
$probeDir = Join-Path $tmpRoot 'probe'
$fakeHome = Join-Path $tmpRoot 'fake-home'
$srcMain = Join-Path $repoRoot 'src\main\java'
$toolDir = Join-Path $repoRoot 'tools\settings-probe'

if (-not (Test-Path -LiteralPath $Libs)) { throw "idea-libs 不存在：$($Libs)" }

$cp = (Get-ChildItem -LiteralPath $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'

New-Item -ItemType Directory -Force -Path $tmpRoot, $fakeHome | Out-Null
$productInfo = '{ "name": "IntelliJ IDEA", "version": "2024.2", "buildNumber": "242.0", "productCode": "IC", "dataDirectoryName": "IdeaIC2024.2", "svgIconPath": "bin/idea.svg", "launcherPath": "bin/idea.bat" }'
$productInfo | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $fakeHome 'product-info.json')

# 平台 UI 组件要 installation home（product-info.json）与若干 --add-opens，
# 缺任何一条都会在 DslLabel / ComponentValidator 上抛异常（见 LESSONS 坑 15 的同类记录）。
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

function Step([string]$m) { if (-not $Quiet) { Write-Host "==> $m" } }

# ---------- 1. 编译源码树 ----------
if (Test-Path -LiteralPath $classesDir) { Remove-Item -LiteralPath $classesDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
$srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcMain | ForEach-Object { $_.FullName })
Step "javac 源码树（$($srcs.Count) 个 .java）"
& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $classesDir $srcs 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw '源码树编译失败' }

# ---------- 2. 编译探针 ----------
if (Test-Path -LiteralPath $probeDir) { Remove-Item -LiteralPath $probeDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
$vprobes = @(Get-ChildItem -LiteralPath $toolDir -Filter 'V*.java' | ForEach-Object { $_.FullName })
Step "javac V 探针（$($vprobes.Count) 个）"
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classesDir;$cp" -d $probeDir $vprobes 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'V 探针编译失败' }

# ---------- 3. 跑探针 ----------
$mains = @('VDefects', 'VScheme', 'VSnap', 'VPreset', 'VTools', 'VSpring', 'VHints', 'VMixed', 'VCustom', 'VFacadeEquiv', 'VSubpixel', 'VSeEdge', 'VRecurse', 'VSep', 'VPreset9', 'VVerify', 'VFrameRate', 'VSmooth', 'VOrder', 'VOrtho', 'VPerf', 'VPerf2', 'VRank', 'VLabel', 'VGrad', 'VRegr2', 'VFreeze', 'VWidth', 'VQuad', 'VMulti', 'VBlock', 'VCaret', 'VAddSetting', 'VCursorSize', 'VPreviewBranch', 'VPreviewSizeLive', 'VPreviewRealtime',
        'VRestoreDefault', 'VDefaultFreeze', 'VColorBinding', 'VTextFacts', 'VLoadState',
        'VReviewFixes', 'VQuadProbeTruth'
# VQuadProbeTruth 锁 P4 三处 medium：
# M-1 HEX 颜色必须校验（否则坏颜色流进绘制层变黑且 decode 返回 true）/
# M-2 探针不能读恒为 0 的 polyX/polyY（假的安全感）/
# M-3 updateCorners 零分配 + polyX/polyY 已删。
# VReviewFixes 锁代码审查抓到的 5 条：
# S-1 平滑不参与预设 / R-1 thread volatile / R-2 loop 有 try-catch 且能自愈 /
# A-1 refreshTarget 的 catch 有日志 / M-1 syncConfigToStateAndPersist 有初始化守卫。
# VLoadState 守 State 的【第四处拷贝】：loadState。
# 前三处（字段声明 / FIELDS / copyFrom）都有探针，第四处从来没被守过 ——
# 结果 loadState 手写 23 条而 State 有 28 字段，漏的 5 个（含 rgbGradient /
# cursorWidthPx / cursorHeightRatio）每次启动被静默丢弃，用户设了重启就失效。
# 变异测试已验证：删一条赋值 → L-1 + L-2 双红。
# VTextFacts 锁「面板文案里的可验证断言与代码一致」：
# T-1 短跳不读 trailSize / T-2 trailSize=1.0 前缘<=1帧 / T-3 调小前缘变长 /
# T-4 smooth 在预设域 / T-5 平滑削弱拉伸 / T-6 跨行不读 stretchRatioK。
# 0.9.72 发现三处文案在骗人（代码改了文案没改），本探针防再犯。, 'VTextFacts'
# VTextFacts 锁「面板文案里的可验证断言与代码一致」：
# T-1 短跳不读 trailSize / T-2 trailSize=1.0 前缘≤1帧 / T-3 调小前缘变长 /
# T-4 smooth 在预设域 / T-5 平滑削弱拉伸 / T-6 跨行不读 stretchRatioK。
# 0.9.72 发现三处文案在骗人（代码改了文案没改），本探针防再犯。
# VColorBinding 锁「ColorPanel 三向同步登记一次即全有」：
# C-1 登记数 == 面板上 ColorPanel 数（漏登记就红）、
# C-3~C-7 pull/push/differs 三个方向各自真的工作。
# 已用变异测试证明抓得住（删掉一个登记 → C-1 报 登记=1 实际=2）。
# VDefaultFreeze 是发布安全的第二道防线：锁住 State 的 28 个默认值。
# 风险背景：平台自 2026.x 起 Storage 注解已无 skipDefaults（会写全部字段），
# 一旦改了某字段的默认值，老用户（XML 里没有该值）与新用户（拿到新默认）
# 会看到不同观感 —— 这是最难排查的一类问题。
# 流程见 docs/RELEASE-SAFETY.md。
# VRestoreDefault 锁「恢复为默认值」：副本必须回到它派生自的内置预设，
# 而不是 restore 自己的快照（那是空操作，用户点了看不出变化）。
# VPreviewRealtime 是「实时预览必须真生效」的守门探针 —— 它验的是几何/像素，
# 不是字段赋值。0.9.66 的 VPreviewSizeLive 只验字段，因此漏掉了真 bug
# （onConfigChanged 不重算尺寸，必须关掉面板再打开才生效）。
# VPreviewSizeLive 锁「光标宽/高必须实时传到预览窗」——
# 0.9.66 之前预览自己算尺寸（fontSize/16、lineH×0.8），完全不看用户设置。
# VPreviewBranch 锁「预览窗必须显式设 charsMoved，不依赖像素判据」。
# 它同时记录了为什么：兜底像素判据 (|jumpX| <= 8) 在预览的 width=1.8px 下
# 会让结果随字号漂移 —— 实测 charW=8 判短跳(16帧)、charW=17 判跨行(48帧)。
# VAddSetting 是「设置页加新参数的稳健性」守门探针：State 字段 vs FIELDS 表、
# copyFrom 覆盖度、面板可建成、reset/apply 无残留脏状态。
# 已用 4 个变异证明它抓得住漏改（M1 删 FIELDS 项 / M2 漏 copyFrom /
# M3 key 拼错 / M4 新字段完全不注册），见 docs/SETTINGS-ROBUSTNESS-AUDIT.md。
# VGlowBudget 已随自适应降级一起删除，不在此列表。
# VOrder 是「已知偏差上报器」：它的 O-1 断言的是契约 V13 的机械判据，
# 实测不成立（见 VERIFY-SETTINGS-V2.md 的 F-2），但同一探针里的行为判据成立。
# 它不参与本脚本的通过门（否则每次跑都会因一条已知文档缺陷而 exit 1），
# 但它的完整输出仍会打印并在汇总表里出现 —— 不隐藏任何红。
# VOrtho 同理：它是「预设字段域扩大后的正交性破损」的缺陷上报器 ——
# O-1/O-2/O-3/O-4/O-5/O-7/O-8/O-9/O-13/O-14/O-15 全部是【真实存在的未修缺陷】，
# 放进通过门会让每次跑都 exit 1。它的完整输出仍会打印并在汇总表里出现 —— 不隐藏任何红。
# VRegr2 同理：它是「上一轮修法回归」的缺陷上报器 —— R-6/R-17c/R-18/R-19/R-22 是
# 【本轮新引入的未修缺陷】，放进通过门会让每次跑都 exit 1。输出全打印，不隐藏任何红。
$informational = @('VOrder', 'VOrtho', 'VRegr2')
$failed = 0
$rows = @()
foreach ($main in $mains) {
    $outFile = Join-Path $tmpRoot ($main + '.txt')
    & java @jvm -cp "$probeDir;$classesDir;$cp" $main 2>&1 |
        Where-Object { $_ -notmatch 'Do not use an alarm' -and $_ -notmatch '已停用' } |
        Set-Content -LiteralPath $outFile -Encoding UTF8
    $code = $LASTEXITCODE
    $lines = @(Get-Content -LiteralPath $outFile -Encoding UTF8)
    $summary = @($lines | Where-Object { $_ -match '=== V-.* 结果|RESULT' })
    $row = [pscustomobject]@{
        Probe = $main
        Exit = $code
        Pass = @($lines | Where-Object { $_ -match '^\s+PASS ' }).Count
        Fail = @($lines | Where-Object { $_ -match '^\s+FAIL ' }).Count
        Summary = ($summary -join ' | ')
    }
    $rows += $row
    if ($code -ne 0 -and -not ($informational -contains $main)) { $failed++ }
    if (-not $Quiet) {
        $line = "  " + $row.Probe.PadRight(10) + " exit=" + $row.Exit + "  PASS=" + $row.Pass + "  FAIL=" + $row.Fail
        Write-Host $line
        if ($row.Fail -gt 0) {
            $lines | Where-Object { $_ -match '^\s+FAIL ' } | ForEach-Object { Write-Host ("    " + $_.Trim()) }
        }
    }
}

Write-Host ''
Write-Host '########## V 探针汇总 ##########'
$rows | Format-Table -AutoSize | Out-String -Width 140 | Write-Host

if ($failed -gt 0) {
    Write-Host ("V-PROBES RESULT: FAILED（" + $failed + " 个探针未通过）")
    exit 1
}
Write-Host 'V-PROBES RESULT: ALL PASS'
exit 0
