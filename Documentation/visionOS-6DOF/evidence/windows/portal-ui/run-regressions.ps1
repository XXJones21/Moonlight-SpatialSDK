$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../../../../..").Path
& 'C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/Common7/Tools/Launch-VsDevShell.ps1' -Arch amd64 -HostArch amd64 -SkipAutomaticLocation
Push-Location $repo
try {
    foreach ($test in @(
        'Documentation/visionOS-6DOF/evidence/windows/2026-09-06-present-fix/test_output_lifetime.py',
        'Documentation/visionOS-6DOF/evidence/windows/2026-09-06-present-fix/test_secondary_present.py',
        'Documentation/visionOS-6DOF/evidence/windows/2026-09-06-projection-fix/test_projection_branch.py'
    )) {
        & python $test $repo
        if ($LASTEXITCODE -ne 0) { throw "Regression failed: $test" }
    }
} finally { Pop-Location }
