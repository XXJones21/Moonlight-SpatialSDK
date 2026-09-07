#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$setupRoot = 'D:\Tools\Moonlight-SpatialSDK\External\local-validation\sunshine-sbs'
Start-Transcript -LiteralPath (Join-Path $setupRoot 'sunshine-refresh-transcript.txt') -Append | Out-Null
try {
    $logPath = 'C:\Program Files\Sunshine\config\sunshine.log'
    Copy-Item -LiteralPath $logPath -Destination (Join-Path $setupRoot ('sunshine-before-refresh-'+(Get-Date -Format yyyyMMdd-HHmmss)+'.log'))
    Restart-Service -Name SunshineService
    Start-Sleep -Seconds 5
    Get-Service -Name SunshineService | Format-List Name,Status
    Copy-Item -LiteralPath $logPath -Destination (Join-Path $setupRoot 'sunshine-after-refresh.log')
} finally { Stop-Transcript | Out-Null }
