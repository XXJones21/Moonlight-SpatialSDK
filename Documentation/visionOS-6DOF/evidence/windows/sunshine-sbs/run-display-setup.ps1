$ErrorActionPreference = 'Stop'
$logPath = 'D:\Tools\Moonlight-SpatialSDK\External\local-validation\sunshine-sbs\setup-transcript.txt'
Start-Transcript -LiteralPath $logPath -Append | Out-Null
try {
    & (Join-Path $PSScriptRoot 'install-display.ps1')
} catch {
    Write-Output ($_ | Out-String)
    exit 1
} finally {
    Stop-Transcript | Out-Null
}
