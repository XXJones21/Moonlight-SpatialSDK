param([switch]$IncludeMediaControl,[switch]$AllComponents)
$ErrorActionPreference='Stop'
$prefix=if($IncludeMediaControl){'portal-udp-control'}else{'portal-udp'}
if($AllComponents){$prefix+='-all'}
$captureStarted=$false
$filterAdded=$false
$logPath=Join-Path $PSScriptRoot ($prefix+'-operations.txt')
try {
    $status=(& pktmon status 2>&1 | Out-String)
    $status | Set-Content -LiteralPath $logPath
    if($LASTEXITCODE -ne 0 -or $status -notmatch 'not running'){throw 'Packet Monitor not confirmed idle; no capture changes made.'}
    $filters=(& pktmon filter list 2>&1 | Out-String)
    $filters | Add-Content -LiteralPath $logPath
    if($LASTEXITCODE -ne 0 -or $filters -notmatch '^\s*Packet Filters:\s*None\s*$'){throw 'Existing/unknown filters; no capture changes made.'}
    & pktmon filter add MoonlightPortalLive4243 -t UDP -p 4243 | Add-Content -LiteralPath $logPath
    if($LASTEXITCODE -ne 0){throw 'Adding diagnostic filter failed.'}
    $filterAdded=$true
    if($IncludeMediaControl){
        & pktmon filter add MoonlightVideoControl47998 -t UDP -p 47998 | Add-Content -LiteralPath $logPath
        if($LASTEXITCODE -ne 0){throw 'Adding media control filter failed.'}
    }
    # With media included, retain only Ethernet/IPv4/UDP headers, not video payload.
    $packetSize=if($IncludeMediaControl){42}else{256}
    $components=if($AllComponents){'all'}else{'nics'}
    & pktmon start --capture --comp $components --pkt-size $packetSize --file-size 16 --file-name (Join-Path $PSScriptRoot ($prefix+'.etl')) | Add-Content -LiteralPath $logPath
    if($LASTEXITCODE -ne 0){throw 'Starting capture failed.'}
    $captureStarted=$true
    Start-Sleep -Seconds 12
} catch {
    $_.Exception.Message | Add-Content -LiteralPath $logPath
} finally {
    if($captureStarted){ & pktmon stop | Add-Content -LiteralPath $logPath }
    if($filterAdded){ & pktmon filter remove | Add-Content -LiteralPath $logPath }
}
if($captureStarted){
    & pktmon etl2txt (Join-Path $PSScriptRoot ($prefix+'.etl')) --out (Join-Path $PSScriptRoot ($prefix+'.txt')) --hex --verbose | Add-Content -LiteralPath $logPath
}
