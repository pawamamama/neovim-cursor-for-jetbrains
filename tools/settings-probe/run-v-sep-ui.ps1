param(
    [string]$Repo = 'D:\deepseekwork\neovim-cursor-pr',
    [string]$Libs = 'D:\deepseekwork\_idea-anim\idea-libs'
)
$ErrorActionPreference = 'Stop'
$repoRoot = $Repo
$tmpRoot = Join-Path $repoRoot 'build\sep-bound'
$classesDir = Join-Path $tmpRoot 'classes'
$probeDir = Join-Path $tmpRoot 'probe'
$fakeHome = Join-Path $tmpRoot 'fake-home'
$toolDir = Join-Path $repoRoot 'tools\settings-probe'
$cp = (Get-ChildItem -LiteralPath $Libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
$jvm = @('-Dfile.encoding=UTF-8','-Djava.awt.headless=true',"-Didea.home.path=$fakeHome",
    '--add-opens=java.desktop/javax.swing=ALL-UNNAMED','--add-opens=java.desktop/java.awt=ALL-UNNAMED',
    '--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED','--add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED',
    '--add-opens=java.desktop/sun.swing=ALL-UNNAMED','--add-opens=java.desktop/java.awt.event=ALL-UNNAMED')
$need = @('V0Env.java', 'VUi.java', 'VSepUi.java') | ForEach-Object { Join-Path $toolDir $_ }
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$classesDir;$cp" -d $probeDir $need 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'VSepUi 编译失败' }
& java @jvm -cp "$probeDir;$classesDir;$cp" VSepUi 2>&1 | Where-Object { $_ -notmatch 'Do not use an alarm' -and $_ -notmatch '已停用' }
exit $LASTEXITCODE
