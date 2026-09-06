# Narrow local-machine rule for the currently installed SteamVR driver host.
# Run elevated. Does not change driver settings or start tracking.
$ErrorActionPreference = 'Stop'
$ruleName = 'MoonlightPortal-VRto3D-BlockRemoteTracking-4242'
$programPath = 'C:\Program Files (x86)\Steam\steamapps\common\SteamVR\bin\win64\vrserver.exe'
$remoteRanges = @('0.0.0.0-126.255.255.255', '128.0.0.0-255.255.255.255')
$resultPath = Join-Path $PSScriptRoot 'tracking-firewall-result.json'
try {
    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Administrator elevation is required to create the firewall rule.'
    }
    if (-not (Test-Path -LiteralPath $programPath -PathType Leaf)) { throw 'Expected SteamVR host not found.' }
    $existing = Get-NetFirewallRule -PolicyStore PersistentStore -Name $ruleName -ErrorAction SilentlyContinue
    if ($existing) {
        # Never overwrite an existing rule, even when it has the expected name.
        $port = $existing | Get-NetFirewallPortFilter
        $address = $existing | Get-NetFirewallAddressFilter
        $app = $existing | Get-NetFirewallApplicationFilter
        if ($existing.Direction -ne 'Inbound' -or $existing.Action -ne 'Block' -or
            $existing.Enabled -ne 'True' -or $existing.Profile -ne 'Any' -or
            $port.Protocol -ne 'UDP' -or $port.LocalPort -ne '4242' -or
            $app.Program -ine $programPath -or
            (Compare-Object @($address.RemoteAddress) $remoteRanges)) {
            throw 'An existing rule with this name differs; inspect it before proceeding.'
        }
    } else {
        New-NetFirewallRule -PolicyStore PersistentStore -Name $ruleName `
            -DisplayName 'Moonlight portal: block remote VRto3D tracking (UDP 4242)' `
            -Description 'Only this SteamVR executable and UDP 4242; IPv4 loopback 127.0.0.0/8 excluded. PortalHost uses 127.0.0.1.' `
            -Direction Inbound -Action Block -Enabled True -Profile Any `
            -Program $programPath -Protocol UDP -LocalPort 4242 -RemoteAddress $remoteRanges | Out-Null
    }
    $active = Get-NetFirewallRule -PolicyStore ActiveStore -Name $ruleName
    if ($active.Enabled -ne 'True' -or $active.Action -ne 'Block') { throw 'Rule is not active.' }
    $record = [ordered]@{
        success = $true
        timestamp = (Get-Date -Format o)
        rule = ($active | Select-Object Name,Enabled,Direction,Action,Profile,PrimaryStatus,EnforcementStatus)
        port = ($active | Get-NetFirewallPortFilter | Select-Object Protocol,LocalPort,RemotePort)
        address = ($active | Get-NetFirewallAddressFilter | Select-Object LocalAddress,RemoteAddress)
        application = ($active | Get-NetFirewallApplicationFilter | Select-Object Program)
        firewallProfiles = @(Get-NetFirewallProfile | Select-Object Name,Enabled)
        remotePacketTested = $false
        trackingEnabledByScript = $false
        rollbackCommand = "Remove-NetFirewallRule -Name '$ruleName'"
    }
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resultPath -Encoding UTF8
    exit 0
} catch {
    [ordered]@{ success = $false; error = $_.Exception.Message; timestamp = (Get-Date -Format o) } |
        ConvertTo-Json | Set-Content -LiteralPath $resultPath -Encoding UTF8
    exit 1
}
