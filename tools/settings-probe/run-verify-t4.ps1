# run-verify-t4.ps1 —— verifier 自建探针（P 持久化往返 / V applyConfig 生效性）一键复跑
#
# 设计原则：**不复用实现者的探针**。所有断言都在本目录的 Verify*.java 里，
# 编译目标是 verifier 自己从当前工作区源码编译出来的一份全新 classes（build\verify-t4\src-classes），
# 不用 build\classes（那是实现者构建的产物，无法证明与源码同步）。
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\settings-probe\run-verify-t4.ps1
#
# 退出码：0 = 全通过；非 0 = 有失败项（脚本抛错）
#
# 安全：本脚本不使用任何 PowerShell 自动变量名（$home/$env/$args/$input/$PSHOME/$host/$pwd/$error/$matches）做赋值。

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs',
    [string]$Work = (Join-Path $Repo 'build\verify-t4')
)

$ErrorActionPreference = 'Stop'
function Step($m) { Write-Host "==> $m" }

$srcRoot = Join-Path $Repo 'src\main\java'
$classes = Join-Path $Work 'src-classes'
$probeOut = Join-Path $Work 'out'
$xmlFile = Join-Path $Work 'roundtrip-state.xml'
$log = Join-Path $Work 'verify-probes.txt'

if (-not (Test-Path -LiteralPath $Libs)) { throw "idea-libs 不存在：$Libs" }
$cp = (Get-ChildItem -LiteralPath $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
Step "idea-libs jar 数 = $((Get-ChildItem -LiteralPath $Libs -Filter '*.jar').Count)"

New-Item -ItemType Directory -Force -Path $Work | Out-Null
if (Test-Path -LiteralPath $log) { Remove-Item -LiteralPath $log -Force }

# ---------- 1. verifier 自己编译源码树（全新，不依赖 build\classes）----------
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$srcs = @(Get-ChildItem -Recurse -File -Filter *.java $srcRoot | ForEach-Object { $_.FullName })
Step "javac 源码树（$($srcs.Count) 个 .java）"
& javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $classes $srcs
if ($LASTEXITCODE -ne 0) { throw '源码树编译失败' }
Step "编译产物类文件数 = $((Get-ChildItem -Recurse -File -Filter *.class $classes).Count)"

# ---------- 2. 编译 verifier 自己的两个探针 ----------
if (Test-Path -LiteralPath $probeOut) { Remove-Item -LiteralPath $probeOut -Recurse -Force }
New-Item -ItemType Directory -Force -Path $probeOut | Out-Null
$probes = @('VerifyPersistence', 'VerifyApplyConfig', 'VerifyPanel')
foreach ($p in $probes) {
    Step "javac 探针 $p"
    & javac --release 21 -nowarn -encoding UTF-8 -classpath "$classes;$cp" -d $probeOut (Join-Path $Repo "tools\settings-probe\$p.java")
    if ($LASTEXITCODE -ne 0) { throw "$p 编译失败" }
}

function Run-Java([string[]]$jvmArgs, [string]$main, [string[]]$mainArgs, [string]$tag) {
    Write-Host ""
    Write-Host "==> 运行 $tag"
    $tmp = Join-Path $Work ("run-" + $tag.Replace(' ', '_').Replace('/', '_').Replace('（', '_').Replace('）', '_') + ".txt")
    & java @jvmArgs -cp "$probeOut;$classes;$cp" $main @mainArgs *> $tmp
    $code = $LASTEXITCODE
    # 只回显 + 追加存档，绝不让管道输出参与返回值（否则 $code 会拿到一个数组）
    Get-Content -LiteralPath $tmp -Encoding UTF8 | ForEach-Object { Write-Host $_ ; Add-Content -LiteralPath $log -Value $_ -Encoding UTF8 }
    Write-Host ""
    Write-Host "  exit=$code"
    return $code
}

# ---------- 3. P：持久化往返（write 一个 JVM，read 另一个 JVM）----------
if (Test-Path -LiteralPath $xmlFile) { Remove-Item -LiteralPath $xmlFile -Force }
$common = @('-Dfile.encoding=UTF-8')

$code = Run-Java $common 'VerifyPersistence' @('write', $xmlFile) 'P1a 写（JVM #1）'
if ($code -ne 0) { throw "VerifyPersistence write 失败（exit $code）" }
Step ("写阶段产出：{0} 字节" -f (Get-Item -LiteralPath $xmlFile).Length)

$code = Run-Java $common 'VerifyPersistence' @('read', $xmlFile) 'P1b 读（JVM #2，全新进程）'
if ($code -ne 0) { throw "VerifyPersistence read 失败（exit $code）：跨进程往返不一致" }

$code = Run-Java $common 'VerifyPersistence' @('all', (Join-Path $Work 'unused.xml')) 'P2/P3 默认回落 + 迁移幂等'
if ($code -ne 0) { throw "VerifyPersistence all 失败（exit $code）" }

# ---------- 4. V：applyConfig 生效性 ----------
# 需要 Swing：headless + 几个 --add-opens（平台代码在纯 JVM 下跑的时候用得到）
$swingArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Djava.awt.headless=true',
    '--add-opens=java.desktop/javax.swing=ALL-UNNAMED',
    '--add-opens=java.desktop/java.awt=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.swing=ALL-UNNAMED'
)
$code = Run-Java $swingArgs 'VerifyApplyConfig' @() 'V applyConfig 生效性'
if ($code -ne 0) { throw "VerifyApplyConfig 失败（exit $code）" }

# ---------- 5. U：面板在无 Project / 无 Editor 下可实例化 ----------
# 平台 UI 组件会找 installation home（product-info.json），所以给一个假安装目录。
# 注意：变量名必须是 $fakeHome（$home 是 PowerShell 只读自动变量，赋值会静默失败）。
$fakeHome = Join-Path $Work 'fake-idea-home'
if (Test-Path -LiteralPath $fakeHome) { Remove-Item -LiteralPath $fakeHome -Recurse -Force }
New-Item -ItemType Directory -Force -Path $fakeHome | Out-Null
$productInfo = '{ "name": "IntelliJ IDEA", "version": "2024.2", "buildNumber": "242.0", "productCode": "IC", "dataDirectoryName": "IdeaIC2024.2", "svgIconPath": "bin/idea.svg", "launcherPath": "bin/idea.bat" }'
$productInfo | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $fakeHome 'product-info.json')

$panelArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Djava.awt.headless=true',
    "-Didea.home.path=$fakeHome",
    "-Didea.config.path=$(Join-Path $Work 'cfg')",
    "-Didea.system.path=$(Join-Path $Work 'sys')",
    '--add-opens=java.desktop/javax.swing=ALL-UNNAMED',
    '--add-opens=java.desktop/java.awt=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.swing=ALL-UNNAMED'
)
$code = Run-Java $panelArgs 'VerifyPanel' @() 'U 面板实例化（无 Project / 无 Editor）'
if ($code -ne 0) { throw "VerifyPanel 失败（exit $code）" }

Write-Host ""
Write-Host "输出存档：$log"
Write-Host "ALL VERIFY-T4 PROBES OK"
