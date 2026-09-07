param([switch]$ValidateOnly)
$ErrorActionPreference = 'Stop'
function Get-PortalDisplays {
    foreach ($device in @(Get-PnpDevice -Class Display)) {
        $hardwareIds = (Get-PnpDeviceProperty -InstanceId $device.InstanceId -KeyName 'DEVPKEY_Device_HardwareIds').Data
        if (@($hardwareIds) -contains 'Root\MttVDD') { $device }
    }
}
$setupRoot = 'D:\Tools\Moonlight-SpatialSDK\External\local-validation\sunshine-sbs'
$configPath = 'C:\VirtualDisplayDriver\vdd_settings.xml'
$helper = Join-Path $setupRoot 'nefcon-v1.14.0\x64\nefconw.exe'
$expected = @{
    'C:\VirtualDisplayDriver\MttVDD.inf' = '550D211FE481E74DFE3F9D724ED78BE48B3A9113405965D683D9373E8D672F5D'
    'C:\VirtualDisplayDriver\MttVDD.dll' = 'C9CA837F57A98FBD43BC416A7F535A95843626E7759EAF85CF0CD7CE334DBB05'
    'C:\VirtualDisplayDriver\mttvdd.cat' = '08A0093FC9B2E32B287A6F8A77CA4DE0A31830D29FC33D2B13A918DC859468F6'
}
$expected[$helper] = '4AB5D41AF3422833316BCB323BDF53F16C3C37891929D2AC8B78E99136A95AF5'
foreach ($filePath in $expected.Keys) {
    if ((Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash -ne $expected[$filePath]) { throw "Changed file: $filePath" }
}
foreach ($signedPath in @($helper,'C:\VirtualDisplayDriver\MttVDD.dll','C:\VirtualDisplayDriver\mttvdd.cat')) {
    if ((Get-AuthenticodeSignature -LiteralPath $signedPath).Status -ne 'Valid') { throw "Invalid signature: $signedPath" }
}
$backups = Get-Content -LiteralPath (Join-Path $setupRoot 'backup-manifest.json') -Raw | ConvertFrom-Json
$configBackup = @($backups | Where-Object { $_.source -eq $configPath })
if ($configBackup.Count -ne 1) { throw 'Expected one original display configuration backup' }
if ((Get-FileHash -LiteralPath $configBackup[0].backup -Algorithm SHA256).Hash -ne $configBackup[0].sha256) { throw 'Backup hash mismatch' }
if ((Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash -ne $configBackup[0].sha256) { throw 'Display configuration changed since backup' }
[xml]$proposed = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'vdd_settings.xml') -Raw
$mode = @($proposed.vdd_settings.resolutions.resolution)
if ($mode.Count -ne 1 -or $mode[0].width -ne '2560' -or $mode[0].height -ne '736' -or $mode[0].refresh_rate -ne '60' -or $proposed.vdd_settings.monitors.count -ne '1') { throw 'Unexpected proposed mode' }
$existing = @(Get-PortalDisplays)
if ($existing.Count) { throw 'An existing virtual display device must be reviewed before installing another' }
$registry = Get-ItemProperty 'HKLM:\SOFTWARE\MikeTheTech\VirtualDisplayDriver' -ErrorAction SilentlyContinue
if ($registry.VDDPATH -and $registry.VDDPATH.TrimEnd('\') -ne 'C:\VirtualDisplayDriver') { throw 'Driver configuration path differs from expected location' }
if ($ValidateOnly) { Write-Output 'PASS: pinned driver/helper hashes, signatures, original backup, proposed mode and absent device verified; no changes made'; exit 0 }
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this setup elevated' }
if (@(Get-Process -Name HogwartsLegacy,UEVRInjector,vrserver -ErrorAction SilentlyContinue).Count) { throw 'Close game, injector and SteamVR before changing display topology' }
$resultPath = Join-Path $setupRoot 'display-install-result.json'
if (Test-Path -LiteralPath $resultPath) { throw 'A previous installation result must be reviewed before retrying' }
$result = [ordered]@{ time=(Get-Date).ToString('o'); configurationBackup=$configBackup[0].backup; deviceIds=@(); success=$false }
try {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'vdd_settings.xml') -Destination $configPath
    $result.configSHA256 = (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash
    $installer = Start-Process -FilePath $helper -ArgumentList @('install','C:\VirtualDisplayDriver\MttVDD.inf','Root\MttVDD') -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $setupRoot 'driver-install.txt') -RedirectStandardError (Join-Path $setupRoot 'driver-install.stderr.txt')
    $result.installerExitCode = $installer.ExitCode
    if ($installer.ExitCode -ne 0) { throw "Driver installer exit code $($installer.ExitCode); inspect result before retrying" }
    Start-Sleep -Seconds 3
    $devices = @(Get-PortalDisplays)
    if ($devices.Count -ne 1 -or $devices[0].Status -ne 'OK') { throw 'Expected exactly one healthy virtual display device' }
    $result.success = $true
} finally {
    $result.deviceIds = @(Get-PortalDisplays | Select-Object -ExpandProperty InstanceId)
    $result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resultPath
}
Write-Output 'Virtual display installed. Next verify display mode, scaling, HDR state and desktop origin before changing Sunshine.'
