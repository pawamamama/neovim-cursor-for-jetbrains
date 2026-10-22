param([string]$Repo='D:\deepseekwork\neovim-cursor-pr',[string]$Libs='D:\deepseekwork\_idea-anim\idea-libs')
$ErrorActionPreference='Stop'
$tmp=Join-Path $Repo 'build\sep-bound'
$cp=(Get-ChildItem -LiteralPath $Libs -Filter *.jar | ForEach-Object{$_.FullName}) -join ';'
$need=@('V0Env.java','VSepDiag.java') | ForEach-Object { Join-Path (Join-Path $Repo 'tools\settings-probe') $_ }
& javac --release 21 -nowarn -encoding UTF-8 -classpath "$tmp\classes;$cp" -d "$tmp\probe" $need 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw '编译失败' }
$jvm=@('-Dfile.encoding=UTF-8','-Djava.awt.headless=true',"-Didea.home.path=$tmp\fake-home")
& java @jvm -cp "$tmp\probe;$tmp\classes;$cp" VSepDiag 2>&1 | Where-Object { $_ -notmatch '已停用' }
exit $LASTEXITCODE
