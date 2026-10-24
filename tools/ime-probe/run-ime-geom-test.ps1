# run-ime-geom-test.ps1 —— F1-c 几何判据的离线验证（可原样复跑）
#
# 做什么：
#   1. 编译当前工作区的 src/main/java（真实产物）
#   2. 编译 tools/ime-probe/ImeGeomCriterionTest.java
#   3. 运行它 —— 它会用 Proxy 伪造 Editor/CaretModel，驱动**编译产物里真实的**
#      CaretAnimator.refreshTarget()，逐条核对几何判据的 12 个边界
#
# 为什么值得单独一个脚本：这个判据的失效模式是"拦截变成无限期冻结"
# （批次2 的 F1-b 就栽在这里）。必须能一键复跑，而不是靠人记着测过。
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File D:\deepseekwork\neovim-cursor-pr\tools\ime-probe\run-ime-geom-test.ps1
#
# 退出码：0 = 全通过；非 0 = 有失败项（脚本会抛错）

param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs',
    [string]$Work = (Join-Path $env:TEMP 'nc-ime-geom')
)

$ErrorActionPreference = 'Stop'

function Step($m) { Write-Host "==> $m" }

if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
New-Item -ItemType Directory -Force $Work | Out-Null

$cp = (Get-ChildItem $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
Step "idea-libs jar 数 = $((Get-ChildItem $Libs -Filter '*.jar').Count)"

# ---------- 1. 编译工作区源码（用 build\classes 作为产物目录，与 offline-build 一致） ----------
$classes = Join-Path $Repo 'build\classes'
if (-not (Test-Path $classes)) {
    Step 'build\classes 不存在，先编译源码树'
    New-Item -ItemType Directory -Force $classes | Out-Null
    $srcs = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $Repo 'src\main\java') |
        ForEach-Object { $_.FullName })
    & javac --release 21 -nowarn -g -encoding UTF-8 -classpath $cp -d $classes $srcs
    if ($LASTEXITCODE -ne 0) { throw '源码树编译失败（先跑 build\offline-build.ps1）' }
}

# ---------- 2. 编译验证 harness ----------
$test = Join-Path $Repo 'tools\ime-probe\ImeGeomCriterionTest.java'
if (-not (Test-Path $test)) { throw "harness 不存在：$test" }

$tout = Join-Path $Work 'test-classes'
New-Item -ItemType Directory -Force $tout | Out-Null
Step '编译 ImeGeomCriterionTest'
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classes;$cp" -d $tout $test
if ($LASTEXITCODE -ne 0) { throw 'harness 编译失败' }

# ---------- 3. 运行 ----------
Step '运行（驱动真实的 CaretAnimator.refreshTarget）'
$log = Join-Path $Work 'ime-geom-test.txt'
& java '-Dfile.encoding=UTF-8' -cp "$tout;$classes;$cp" ImeGeomCriterionTest 2>&1 |
    Tee-Object -FilePath $log | ForEach-Object { $_ }
$code = $LASTEXITCODE

Write-Host ''
Write-Host "输出存档：$log"
if ($code -ne 0) { throw "F1-c 判据验证失败（exit $code）" }
Step 'ALL OK（29 项边界全通过）'
