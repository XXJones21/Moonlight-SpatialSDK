# Align the approved AVP-only rule with its source observed by Windows.
$ErrorActionPreference='Stop'
$ruleName='MoonlightPortal-VisionPro-1921680182-4243'
$programPath='D:\Tools\Moonlight-SpatialSDK\PortalHost\build\Release\portal_host.exe'
try {
    $rule=Get-NetFirewallRule -Name $ruleName -ErrorAction Stop
    $address=$rule | Get-NetFirewallAddressFilter
    $port=$rule | Get-NetFirewallPortFilter
    $app=$rule | Get-NetFirewallApplicationFilter
    if($address.RemoteAddress -ne '192.168.0.182' -or $port.LocalPort -ne '4243' -or
       $port.Protocol -ne 'UDP' -or $app.Program -ne $programPath -or
       $rule.Action -ne 'Allow' -or $rule.Direction -ne 'Inbound') { throw 'Existing rule differs from approved baseline; no change made.' }
    [ordered]@{name=$rule.Name;remoteAddress=$address.RemoteAddress;program=$app.Program;
        localPort=$port.LocalPort;protocol=$port.Protocol;profile=[string]$rule.Profile} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'firewall-before-peer-alignment.json')
    Set-NetFirewallRule -Name $ruleName -RemoteAddress '10.1.95.13' `
        -NewDisplayName 'Moonlight portal: AVP observed source 10.1.95.13 UDP 4243'
    [ordered]@{success=$true;timestamp=(Get-Date -Format o);name=$ruleName;
        previousPeer='192.168.0.182';observedPeer='10.1.95.13';
        rollbackCommand="Set-NetFirewallRule -Name '$ruleName' -RemoteAddress '192.168.0.182'"} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'firewall-peer-alignment-result.json')
} catch {
    [ordered]@{success=$false;error=$_.Exception.Message} | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $PSScriptRoot 'firewall-peer-alignment-result.json')
    exit 1
}
