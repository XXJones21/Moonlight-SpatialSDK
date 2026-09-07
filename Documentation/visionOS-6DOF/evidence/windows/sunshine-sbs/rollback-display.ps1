# Removes only device instance IDs recorded by install-display.ps1 and restores its original XML.
# Sunshine configuration is not changed by install-display.ps1 or this rollback.
#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$setupRoot = 'D:\Tools\Moonlight-SpatialSDK\External\local-validation\sunshine-sbs'
if (@(Get-Process -Name HogwartsLegacy,UEVRInjector,vrserver -ErrorAction SilentlyContinue).Count) { throw 'Close game, injector and SteamVR before changing display topology' }
$result = Get-Content -LiteralPath (Join-Path $setupRoot 'display-install-result.json') -Raw | ConvertFrom-Json
$backups = Get-Content -LiteralPath (Join-Path $setupRoot 'backup-manifest.json') -Raw | ConvertFrom-Json
$original = @($backups | Where-Object { $_.source -eq 'C:\VirtualDisplayDriver\vdd_settings.xml' })
if ($original.Count -ne 1 -or $result.configurationBackup -ne $original[0].backup) { throw 'Backup identity mismatch' }
if ((Get-FileHash -LiteralPath $original[0].backup -Algorithm SHA256).Hash -ne $original[0].sha256) { throw 'Backup hash mismatch' }
if ((Get-FileHash -LiteralPath 'C:\VirtualDisplayDriver\vdd_settings.xml' -Algorithm SHA256).Hash -ne $result.configSHA256) { throw 'Display configuration changed after setup; review before rollback' }
foreach ($deviceId in @($result.deviceIds)) {
    if ($deviceId -notmatch '^ROOT\\[^\\]+\\[0-9]+$') { throw 'Unexpected device instance ID' }
    $hardwareIds = (Get-PnpDeviceProperty -InstanceId $deviceId -KeyName 'DEVPKEY_Device_HardwareIds').Data
    if (@($hardwareIds) -notcontains 'Root\MttVDD') { throw 'Recorded device is no longer the virtual display' }
    & pnputil.exe /remove-device $deviceId
    if ($LASTEXITCODE -ne 0) { throw "Could not remove recorded device $deviceId" }
}
Copy-Item -LiteralPath $original[0].backup -Destination 'C:\VirtualDisplayDriver\vdd_settings.xml'
if ((Get-FileHash -LiteralPath 'C:\VirtualDisplayDriver\vdd_settings.xml' -Algorithm SHA256).Hash -ne $original[0].sha256) { throw 'Restored configuration hash mismatch' }
Write-Output 'Recorded virtual display removed and original settings restored. Driver files/store remain intact.'
