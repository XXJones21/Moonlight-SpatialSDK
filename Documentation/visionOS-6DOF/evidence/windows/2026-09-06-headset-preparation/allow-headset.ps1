# Permit this headset to reach only the compiled portal relay's UDP listener.
$ErrorActionPreference='Stop'
$ruleName='MoonlightPortal-VisionPro-1921680182-4243'
$programPath='D:\Tools\Moonlight-SpatialSDK\PortalHost\build\Release\portal_host.exe'
$resultPath=Join-Path $PSScriptRoot 'firewall-result.json'
try {
    $principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Windows administrator elevation is required.'}
    if(-not (Test-Path -LiteralPath $programPath -PathType Leaf)){throw 'PortalHost executable missing.'}
    if(Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue){throw 'Rule already exists; inspect instead of overwriting.'}
    New-NetFirewallRule -Name $ruleName -DisplayName 'Moonlight portal: Vision Pro 192.168.0.182 UDP 4243' `
        -Direction Inbound -Action Allow -Enabled True -Profile Any -Program $programPath `
        -Protocol UDP -LocalPort 4243 -RemoteAddress '192.168.0.182' | Out-Null
    $rule=Get-NetFirewallRule -PolicyStore ActiveStore -Name $ruleName
    [ordered]@{success=$true;timestamp=(Get-Date -Format o);name=$rule.Name;enabled=[string]$rule.Enabled;
        enforcement=@($rule.EnforcementStatus | ForEach-Object {[string]$_});
        remoteAddress='192.168.0.182';localPort=4243;program=$programPath;
        rollbackCommand="Remove-NetFirewallRule -Name '$ruleName'"} |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resultPath -Encoding utf8
}catch {
    [ordered]@{success=$false;error=$_.Exception.Message} | ConvertTo-Json |
        Set-Content -LiteralPath $resultPath -Encoding utf8
    exit 1
}
