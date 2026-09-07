#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$setupRoot = 'D:\Tools\Moonlight-SpatialSDK\External\local-validation\sunshine-sbs'
Start-Transcript -LiteralPath (Join-Path $setupRoot 'sunshine-configure-transcript.txt') -Append | Out-Null
try {
    $backups = Get-Content -LiteralPath (Join-Path $setupRoot 'backup-manifest.json') -Raw | ConvertFrom-Json
    foreach ($source in @('C:\Program Files\Sunshine\config\sunshine.conf','C:\Program Files\Sunshine\config\apps.json')) {
        $entry = @($backups | Where-Object { $_.source -eq $source })
        if ($entry.Count -ne 1 -or (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $entry[0].sha256 -or (Get-FileHash -LiteralPath $entry[0].backup -Algorithm SHA256).Hash -ne $entry[0].sha256) { throw "Configuration or backup changed: $source" }
    }
    $confPath = 'C:\Program Files\Sunshine\config\sunshine.conf'
    $appsPath = 'C:\Program Files\Sunshine\config\apps.json'
    $conf = [IO.File]::ReadAllText($confPath)
    # Disable automatic mode changes; the dedicated display already exposes the exact mode.
    $conf = $conf.TrimEnd()+"`r`noutput_name = {9acddf6d-43cc-576e-9aff-0c5fc80b4cc8}`r`ndd_configuration_option = disabled`r`n"
    $apps = [IO.File]::ReadAllText($appsPath) | ConvertFrom-Json
    if (@($apps.apps | Where-Object { $_.name -eq 'UEVR Portal (SBS)' }).Count) { throw 'Portal app already exists; review before retrying' }
    $apps.apps = @($apps.apps) + @([pscustomobject]@{ name='UEVR Portal (SBS)'; 'image-path'='desktop.png' })
    $utf8 = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($confPath,$conf,$utf8)
    [IO.File]::WriteAllText($appsPath,($apps | ConvertTo-Json -Depth 10),$utf8)
    [ordered]@{ time=(Get-Date).ToString('o'); output_name='{9acddf6d-43cc-576e-9aff-0c5fc80b4cc8}'; app='UEVR Portal (SBS)'; globalCaptureSelection=$true; confSHA256=(Get-FileHash $confPath -Algorithm SHA256).Hash; appsSHA256=(Get-FileHash $appsPath -Algorithm SHA256).Hash } | ConvertTo-Json | Set-Content (Join-Path $setupRoot 'sunshine-applied.json')
    & (Join-Path $PSScriptRoot 'refresh-sunshine.ps1')
} finally { Stop-Transcript | Out-Null }
