# Finish only the exact already-closed game session before replacing its injector.
$ErrorActionPreference='Stop'
$resultPath=Join-Path $PSScriptRoot 'closed-game-cleanup.json'
$records=@()
try {
    $expected=@(
        @{Id=45236;Path='D:\SteamLibrary\steamapps\common\Hogwarts Legacy\Phoenix\Binaries\Win64\HogwartsLegacy.exe'},
        @{Id=17168;Path='D:\SteamLibrary\steamapps\common\Hogwarts Legacy\HogwartsLegacy.exe'}
    )
    foreach($item in $expected){
        $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$($item.Id)"
        if(-not $proc){continue}
        if($proc.ExecutablePath -ne $item.Path -or $proc.CreationDate.ToString('yyyy-MM-dd HH:mm:ss') -ne '2026-09-06 14:43:43'){
            throw 'Process identity changed; leave it untouched.'
        }
        $visible=Get-Process -Id $item.Id
        if($visible.MainWindowHandle -ne 0){throw 'Game window is visible again; leave it running.'}
        $records+=@{pid=$item.Id;path=$proc.ExecutablePath;startedAt=$proc.CreationDate.ToString('o');windowHandle=0}
        Stop-Process -Id $item.Id -Force -ErrorAction Stop
    }
    [ordered]@{success=$true;timestamp=(Get-Date -Format o);terminated=$records} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resultPath
}catch{
    [ordered]@{success=$false;error=$_.Exception.Message;terminated=$records} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resultPath
    exit 1
}
