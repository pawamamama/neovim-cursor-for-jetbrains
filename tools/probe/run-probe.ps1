# run-probe.ps1 —— T1-1 调度行为运行期探针（t4 verifier 产出，可原样复跑）
#
# 两个探针：
#   DispatchCountProbe —— 基线与本批工作区**都能编译**，用于同一序列下的投递计数对比
#                         与线程数对比（N 个编辑器 → 基线 N 条线程 / 新版 1 条）
#   T1SchedulerProbe   —— 只对本批工作区（依赖 nextDueNanos / lastDispatchedDueNanos），
#                         覆盖"3 个同时到期 / 只有 1 个到期 / 到期值长期不变 / 全部过期 /
#                         线程建-退-再建-再退 / 长 tick 投递频率"等边界
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\probe\run-probe.ps1

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$BaseRef = '321b0a0',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs',
    [string]$Work = (Join-Path $env:TEMP 'nc-t4-probe')
)

$ErrorActionPreference = 'Stop'
if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
New-Item -ItemType Directory -Force $Work | Out-Null

# 基线源码导出（不碰工作区）
$baseSrcRoot = Join-Path $Work 'base-src'
$zip = Join-Path $Work 'base-src.zip'
& git -C $Repo archive --format=zip -o $zip $BaseRef src/main/java
if ($LASTEXITCODE -ne 0) { throw 'git archive 失败' }
Expand-Archive -Path $zip -DestinationPath $baseSrcRoot -Force

$cp = (Get-ChildItem $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
$crossProbe = Join-Path $Repo 'tools\probe\com\github\yyyolo7a79\neovidecursor\ui\DispatchCountProbe.java'
$regimeProbe = Join-Path $Repo 'tools\probe\com\github\yyyolo7a79\neovidecursor\ui\RegimeProbe.java'
$newProbe = Join-Path $Repo 'tools\probe\com\github\yyyolo7a79\neovidecursor\ui\T1SchedulerProbe.java'

$trees = @(
    @{ Tag = 'base'; Src = (Join-Path $baseSrcRoot 'src\main\java') },
    @{ Tag = 'new'; Src = (Join-Path $Repo 'src\main\java') }
)

foreach ($t in $trees) {
    $tag = $t.Tag
    $cls = Join-Path $Work "classes-$tag"
    $pb = Join-Path $Work "probe-$tag"
    New-Item -ItemType Directory -Force $cls, $pb | Out-Null

    Write-Host "==> [$tag] 编译源码树"
    $srcs = @(Get-ChildItem -Recurse -File -Filter *.java $t.Src | ForEach-Object { $_.FullName })
    & javac --release 21 -nowarn -encoding UTF-8 -classpath $cp -d $cls $srcs
    if ($LASTEXITCODE -ne 0) { throw "[$tag] 源码树编译失败" }

    Write-Host "==> [$tag] 编译 DispatchCountProbe（跨树探针）"
    & javac --release 21 -nowarn -encoding UTF-8 -classpath "$cls;$cp" -d $pb $crossProbe $regimeProbe
    if ($LASTEXITCODE -ne 0) { throw "[$tag] DispatchCountProbe 编译失败" }

    $o = Join-Path $Work "$tag-dispatch.txt"
    & java '-Dfile.encoding=UTF-8' '--add-opens' 'java.desktop/java.awt.event=ALL-UNNAMED' -cp "$pb;$cls;$cp" com.github.yyyolo7a79.neovidecursor.ui.DispatchCountProbe > $o
    Write-Host "  输出 → $o （exit=$LASTEXITCODE）"

    $o3 = Join-Path $Work "$tag-regime.txt"
    & java '-Dfile.encoding=UTF-8' '--add-opens' 'java.desktop/java.awt.event=ALL-UNNAMED' -cp "$pb;$cls;$cp" com.github.yyyolo7a79.neovidecursor.ui.RegimeProbe > $o3
    Write-Host "  输出 → $o3 （exit=$LASTEXITCODE）"
}

# 新版专属探针
$clsNew = Join-Path $Work 'classes-new'
$pbNew = Join-Path $Work 'probe-new-only'
New-Item -ItemType Directory -Force $pbNew | Out-Null
Write-Host '==> [new] 编译 T1SchedulerProbe'
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$clsNew;$cp" -d $pbNew $newProbe
if ($LASTEXITCODE -ne 0) { throw '[new] T1SchedulerProbe 编译失败' }
$o2 = Join-Path $Work 'new-t1scheduler.txt'
& java '-Dfile.encoding=UTF-8' '--add-opens' 'java.desktop/java.awt.event=ALL-UNNAMED' -cp "$pbNew;$clsNew;$cp" com.github.yyyolo7a79.neovidecursor.ui.T1SchedulerProbe > $o2
Write-Host "  输出 → $o2 （exit=$LASTEXITCODE）"

Write-Host ''
Write-Host '============ 汇总 ============'
foreach ($f in 'base-dispatch.txt', 'new-dispatch.txt', 'base-regime.txt', 'new-regime.txt', 'new-t1scheduler.txt') {
    $p = Join-Path $Work $f
    Write-Host "--- $f ---"
    Get-Content $p
}
Write-Host ''
Write-Host '==> 探针结束'
