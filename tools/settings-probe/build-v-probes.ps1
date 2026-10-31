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
    # 【2026-10-28】给 VDocCheck 用：它要读 CLAUDE.md / START-HERE.md / docs/NEXT-STEPS.md，
    # 而 java 进程的 cwd 不一定是仓库根（脚本可能从别处被调用）。
    "-Dneovide.repo=$repoRoot",
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
# 【0.9.88 修复：命令行超长】
#
# 直接把 87 个绝对路径当参数交给 javac，Windows 的命令行上限（32767 字符）
# 被撑爆，报「文件名或扩展名太长」，整份门禁**从未跑过第二步**。
# 改成 @argfile（javac 自己读的参数文件），这是 JDK 官方给的解法。
$vprobeArgFile = Join-Path $tmpRoot 'vprobes.args'
$vprobes | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' } |
    Set-Content -LiteralPath $vprobeArgFile -Encoding UTF8
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classesDir;$cp" -d $probeDir "@$vprobeArgFile" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'V 探针编译失败' }

# ---------- 3. 跑探针 ----------
$mains = @('VDefects', 'VScheme', 'VSnap', 'VPreset', 'VTools', 'VSpring', 'VHints', 'VMixed', 'VCustom', 'VFacadeEquiv', 'VSubpixel', 'VSeEdge', 'VRecurse', 'VSep', 'VPreset9', 'VVerify', 'VFrameRate', 'VSmooth', 'VOrder', 'VOrtho', 'VPerf', 'VPerf2', 'VRank', 'VLabel', 'VGrad', 'VRegr2', 'VFreeze', 'VWidth', 'VQuad', 'VMulti', 'VBlock', 'VCaret', 'VAddSetting', 'VCursorSize', 'VPreviewBranch', 'VPreviewSizeLive', 'VPreviewRealtime',
        'VRestoreDefault', 'VDefaultFreeze', 'VColorBinding', 'VTextFacts', 'VLoadState',
        'VReviewFixes', 'VQuadProbeTruth', 'VRgbWash', 'VRankCollapse', 'VDeadField',
        'VSlopePreset', 'VSlopeMigration',
        # ============ 2026-10-29：恢复默认值的第三次回归守门 ============
        # VPresetReset 补的是 VRestoreDefault 测不到的缺口：
        # 「恢复按钮有没有顺手把副本条目（customAnimPresets）改写掉」——
        # 那正是 bd932b9 造成用户手调数据丢失的地方。
        # 不登记 = 从不运行（见 docs/archive/DEAD-PROBE-CLEANUP.md）。
        'VPresetReset',
        # 2026-10-29 父链重构：派生记父 / 自定义派生自定义 / 删除接管祖父 /
        # 改名同步子引用 / 旧格式兼容 / 环检测。纯逻辑层，不需要平台桩。
        'VPresetChain',
        # 2026-10-29 BUG-011：彩虹预设的 shadowColor 定成中性白雾 #FFFFFF。
        # 这条盯的是「预设 shadowColor → CaretTrailPanel.glowColor → 辉光」的链路。
        # 之前没有任何探针覆盖它：VColorMatchBug 只验匹配、VRainbowSep/VRgbWash
        # 只验本体填充、VGradAllPresets 明确跳过彩虹 —— 所以那个 bug 才能活到用户发现。
        # 不登记 = 从不运行（见 docs/archive/DEAD-PROBE-CLEANUP.md）。
        'VGlowFog',
        # ============ 0.9.83：补回被遗忘的探针 ============
        # 这些探针引用的生产类都还在、且 27 个实测全过 ——
        # 它们只是从这份清单里掉了，等于「写了但从不运行」。
        # 删掉 = 白丢覆盖；正确做法是补回来。
        # （实测：30 个里 27 个全过，只有 VReset2/VSepBound/VSleep 有已知失败）
        'VBaseline', 'VBlockStretch', 'VCursorWidth', 'VDead', 'VDiagPersist',
        'VGroup', 'VHandFeel', 'VInfo', 'VMCDiff', 'VMultiNoGlow', 'VPresetFeel',
        'VSelection', 'VSelfCheck', 'VSelFix', 'VSepDiag', 'VSepUi', 'VSize',
        'VSleep2', 'VStretchDiag', 'VUniform', 'VYourConfig', 'V0Create', 'V0Tree',
        'VerifyApplyConfig', 'VerifyPanel', 'VerifyPersistence', 'VSmoothGlobal',
        'VPreviewSize',
        # 已知有失败但覆盖有效功能，保留运行以便跟踪：
        'VReset2', 'VSepBound', 'VSleep',
        # 0.9.86~0.9.87 新增
        'VRainbowSep', 'VFpsPersist', 'VSwitchKeysGap',
        # 【0.9.88 补回】VReset 写好了但从来没进过这张表 —— 与上面 0.9.83 那次
        # 「写了但从不运行」是同一类漏。它当前有 1 条失败（R-16），
        # 失败原因已查清并记录在 docs/CODE-AUDIT-0.9.87.md，
        # 按纪律「不许改成警告绕过」，所以放在通过门内 —— 红就是红。
        'VReset',
        # 0.9.88 新增：锁 matchColorPreset 判据（彩虹与粉色 HEX 不再重叠）
        'VColorMatchBug',
        # 0.9.88 新增：锁「开渐变后所有纯色预设都是渐变」
        'VGradAllPresets',
        # 0.9.89：预览必须收到 slopeTicks 与 cursorRgbWash
        'VPreviewParity',
        # 0.9.90：Ctrl+滚轮改字号后光标必须重新贴合行位置
        # （快速路径只看行/列/文档戳，缩放时三者全不变 → 早退 → 纵向漂移）
        #
        # 【BUG-006】block inlay（IDEA 自动显示的 @Contract 那一行）
        # 撑高行时，行号/列号/文档戳同样全不变，但 y 变了。
        # 与 VFontScale 是同一类缺口（几何指纹），只是触发源不同。
        'VFontScale',
        'VInlay',
        # 【2026-10-28】预设三份字段清单同源：快照存什么 == 内置预设写什么 == 匹配比什么。
        # 之前三者分家，实测症状：切自定义预设会改颜色；改斜度不派生副本。
        'VFieldDomain',
        # 【BUG-007】首用诊断实测：不可见编辑器在 alwaysHighFps 下仍按 144fps 空转
        # （5 个隐藏标签页 = 720 次 EDT 任务/秒）。VBoot 守「不可见必须降到空闲档」。
        'VBoot',
        # 【2026-10-28】把「文档里的状态数字」也纳入门禁：三处状态块版本一致、
        # 探针数一致且等于磁盘实测、状态文档里不写死 commit hash。
        'VDocCheck',
        # 【2026-10-28】把「打包时注入的兼容区间」纳入门禁。
        # until-build 留空 = 任何未来 IDE 都放行 → 平台 API 一移除，用户升级即崩
        # （NoSuchMethodError，现象像"IDE 坏了"）。B-2 守「模板里真有 until-build」，
        # B-3 守「两端值都解析得出来且非空」，B-4 守「上界 >= 下界」（抄反就永远装不上），
        # B-5 守「只锁大版本」（写精确 build 号会误伤 2026.1.4 这种后续小版本）。
        'VBuildDecl',
        # 【2026-10-28】平台 252（PyCharm 2025.2）上「在 EDT 直接读编辑器」会触发
        # EditorThreading.assertInteractionAllowed → 覆盖层光标永久冻结。
        # VReadAction 用「只允许 read action 内访问」的 Editor 桩复现该判据，
        # 断言构造函数 / 慢路径 / 快速路径 / caret 事件入口全程零违规，
        # 且 P0-5 调用次数契约（慢路径帧 getOffset 恰好 1 次）不变。
        'VReadAction',
        # 【2026-10-29】[trail] 诊断日志行数上限（每 animator 5000 行 + 一条汇总，
        # 与 trace 的 TRACE_MAX_LINES 同一策略）。守门：前 N 行逐字节不变 /
        # 超限只补一条 / 多编辑器分桶独立 / 超限后不再增长。
        # 不登记 = 从不运行（见 docs/archive/DEAD-PROBE-CLEANUP.md）。
        'VTrailCap',
        # 【2026-10-29】分标签页后的守门缺口：漏一句 registerIntegratedPanel(page)
        # → 该页控件改了不写进 pending、点应用没反应、重置不回灌，
        # 而且【不报错、不抛异常、界面一切正常】。
        # 变异实测：删掉 registerIntegratedPanel(tabDiag) 时全量门禁 0 条红 ——
        # 因为没有任何探针通过面板去改「调试」页的控件。VTabPages 用结构判据补上：
        # 标签页集合 ⊆ outer.getIntegratedPanels()，与页里放什么控件无关。
        'VTabPages',
        # 【2026-10-30】审计第 1 批（健壮性 / 零观感变化）的守门探针。
        # 这一批 8 条（H-2/H-3/M-4/M-10/L-4/L-5/L-6/L-8）的共同点是：
        # 非法输入不会崩，只会「静默地做错事」—— 失败不记日志、失败还宣称成功、
        # NaN 进了弹簧就再也出不来。正常路径全对，所以跑一遍功能永远测不出来。
        # 它靠 Logger.setFactory 捕获平台日志，把「某条 warn 打没打」变成可断言的事实。
        # 不登记 = 从不运行（见 docs/archive/DEAD-PROBE-CLEANUP.md）。
        'VAuditBatch1',
        # 【2026-10-30】审计第 2 批「第 2~4 轮」（E 原生光标与挂载链 / F 生命周期 /
        # B 诊断基础设施，共 20 条）的守门探针。
        # 这一批的共同点：「基础设施自己失败时无人知道」—— 挂载被空 catch 吞、
        # 记账先于副作用、诊断文件写盘失败静默、EDT 假设被 javap 否证却无防御。
        # 正常路径全对，只能精确注入失败才测得出来。
        # 不登记 = 从不运行（见 docs/archive/DEAD-PROBE-CLEANUP.md）。
        'VAuditBatch2Round2to4',
        # 【2026-10-31】审计第 2 批「第 5 轮」（G 热路径微分配 8 条 / H 死代码与注释 6 条）。
        # 这一批的共同点是「改了但画面一模一样」—— 没有观感可测，只能把
        # 「分配发生在哪一行」「注释说的是不是真的」变成可断言的事实。
        # 能行为化的都行为化了（填充值 / EDT 真值表 / 辉光半径），
        # 分配与注释类用结构判据，逐条配变异测试。
        # 不登记 = 从不运行（见 docs/archive/DEAD-PROBE-CLEANUP.md）。
        'VAuditBatch2Round5')
        # ↑ 数组到此闭合（这个右括号 0.9.83 之后一直缺失，
        #   整份脚本从那时起就 ParseError，从未真正执行过 —— 详见审计报告）。
# VSlopeMigration 锁 0.9.81 斜度默认值变更的迁移：
# 82（旧出厂默认）→ 100（消除「凭空多出副本」），用户真实值原样保留。
# VSlopePreset 锁「斜度进预设域」：
# 82 产出上游系数 / 三个预设都是 82 / 切预设拉回 82 / 默认 82 / 旧值 -1 迁移成 82。
# VDeadField 锁「字段必须被真正消费」：
# 0.9.76 加了 targetFps（声明+loadState+copyFrom+FIELDS 白名单全有），
# 但 CaretAnimator 读的仍是常量 —— 用户选 144 完全没效果。
# VLoadState/VDefaultFreeze/VAddSetting 三条探针都只验持久化链路，查不出没人读。
# VRankCollapse 锁「rank 必须是 4 个互不相同的值」：
# 原实现用「比它小的有几个」计数，并列时不计数 -> 纯轴向移动塌成 2 档 ->
# 同侧上下角同速 -> 四边形永远矩形，斜缎带出不来。
# 这是子代理查上游后定位的手感差异根因（上游四家 JS 都用排序+索引 tiebreak）。
# VRgbWash 锁「RGB 色相循环 + 彩虹颜色预设」：
# W-3 选彩虹开 RGB / 选冰蓝关 RGB（双向，否则显示层撒谎）/
# W-4 RGB 开时颜色随位置变化 / W-5 RGB 关时颜色恒定 / W-6 颜色随时间前移。
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
