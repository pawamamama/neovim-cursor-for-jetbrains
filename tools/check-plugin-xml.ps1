
param([string]$XmlPath)

$ErrorActionPreference = 'Stop'
[xml]$doc = Get-Content -LiteralPath $XmlPath -Raw

$fail = 0
function Chk($name, $ok, $detail) {
    if ($ok) { Write-Host ("  PASS  " + $name) }
    else { Write-Host ("  FAIL  " + $name + "   " + $detail); $script:fail++ }
}

Write-Host "=== plugin.xml 安装前安全体检 ==="

# 1) 绝不能有 separator（会让 ActionManagerImpl.processSeparatorNode 抛 NPE，IDE 启动崩溃）
$seps = $doc.SelectNodes("//separator")
Chk "无 <separator> 元素（曾导致 IDE 启动崩溃）" ($seps.Count -eq 0) ("发现 " + $seps.Count + " 个")

# 2) <actions> 下每个子元素都必须是 action 或 group
$actsNode = $doc.SelectSingleNode("/idea-plugin/actions")
if ($actsNode -ne $null) {
    $bad = @()
    foreach ($c in $actsNode.ChildNodes) {
        if ($c.NodeType -ne 'Element') { continue }
        if ($c.Name -ne 'action' -and $c.Name -ne 'group') { $bad += $c.Name }
    }
    Chk "<actions> 下只有 action / group" ($bad.Count -eq 0) ("非法元素: " + ($bad -join ','))
    Write-Host ("         实际: " + (($actsNode.ChildNodes | Where-Object { $_.NodeType -eq 'Element' } | ForEach-Object { $_.Name }) -join ', '))
} else {
    Write-Host "  (无 <actions> 节点)"
}

# 3) 所有 add-to-group 的父元素必须是 action 或 group
$atgs = $doc.SelectNodes("//add-to-group")
$badParents = @()
foreach ($a in $atgs) {
    $p = $a.ParentNode.Name
    if ($p -ne 'action' -and $p -ne 'group') { $badParents += ($p + "->" + $a.GetAttribute("group-id")) }
}
Chk "所有 <add-to-group> 的父元素是 action/group" ($badParents.Count -eq 0) ($badParents -join '; ')
Write-Host ("         共 " + $atgs.Count + " 个 add-to-group")

# 4) group 必须有 id 和 class（没有就没法实例化）
$groups = $doc.SelectNodes("/idea-plugin/actions/group")
foreach ($g in $groups) {
    $id = $g.GetAttribute("id"); $cls = $g.GetAttribute("class")
    Chk ("group '" + $id + "' 有 id + class") ($id -ne '' -and $cls -ne '') "id=$id class=$cls"
}

# 5) action 必须有 id + class
$actionNodes = $doc.SelectNodes("/idea-plugin/actions/action")
foreach ($a in $actionNodes) {
    $id = $a.GetAttribute("id"); $cls = $a.GetAttribute("class")
    Chk ("action '" + $id + "' 有 id + class") ($id -ne '' -and $cls -ne '') "id=$id class=$cls"
}

# 6) 每个 action/group 的 class 必须是全限定名
$allWithClass = $doc.SelectNodes("//*[@class]")
$badClass = @()
foreach ($x in $allWithClass) {
    $c = $x.GetAttribute("class")
    if ($c -notmatch '\.') { $badClass += $c }
}
Chk "所有 class 是全限定名" ($badClass.Count -eq 0) ($badClass -join ',')

Write-Host ""
if ($fail -eq 0) { Write-Host "=== 体检通过（$fail 项失败）==="; exit 0 }
else { Write-Host "=== 体检失败（$fail 项）==="; exit 1 }
