# Sunshine SBS capture preparation — 2026-09-06

## Current checkpoint

**Latest:** The user enabled Portal Output and confirmed game video appears on
the headset and controller input works. The supplied headset recording was
sampled at 3/8/12 seconds. Missing UI, scene artifacts and physical size remain
open. Editing Portal Eye Content Width produced content_aspect_mismatch;
physical size must be adjusted in visionOS with the pixel contract unchanged.
See [first headset video and follow-ups](first-headset-video.md). The earlier
grey-panel investigation below is historical.

The live 17:40 session now negotiates 2560x736x60 and SDR HEVC. The user still
sees grey and cannot use the visionOS-connected controller. Inspection found
the game desktop window on the virtual display, no separate UEVRPortalSBS
window, and existing client code disabling controller forwarding while the
portal is hidden. The game window has been moved back to the physical desktop
for user operation. See [controller/UI findings and Mac handoff](controller-and-ui-handoff.md).
Next enable Portal Output from the accessible desktop and inspect direct output;
actual decoded metadata and headset presentation remain unverified.

At 17:34, SunshineService loaded the virtual display selection and reports
capture size 2560x736, offset 2560x0 and display refresh 60 Hz. Its display
inventory confirms SDR (HDR Disabled), 100% scaling, extended/non-primary,
and device ID `{9acddf6d-43cc-576e-9aff-0c5fc80b4cc8}` (DISPLAY5).
Physical DISPLAY1 remains primary at (0,0), 2560x1440; its measured refresh is
now 119.998 Hz, compared with 144 Hz in the earlier baseline. This run did not
explicitly set that physical-display refresh rate; the cause is unverified.

Sunshine configuration adds only this output_name and
dd_configuration_option=disabled (documented in the installed version's
configuration.md), preventing automatic display-mode changes. Existing app
entries are preserved and `UEVR Portal (SBS)` is added. Capture selection is
global, including the existing Desktop entry. Relevant logs and the applied
configuration hashes are preserved under External/local-validation/sunshine-sbs.
NVENC startup probes find HEVC support at this capture size; this is not an
actual negotiated headset stream. AV1 YUV444 probe errors are followed by its
unsupported capability result; the intended initial codec remains SDR HEVC.

The closed game's config was backed up to uevr-before-display-config.txt in
that local directory. Only PortalDesktopX was changed from 0 to 2560;
Y=0, eye size 1280x720, Portal Output/window/diagnostics off are retained.
Eight package files were hash-verified; the 67d9785 injector was reopened as
PID 11196 and SteamVR launch was requested. PortalHost PID 46808 remains running.
Next user checkpoint: ensure OpenTrack/live AVP input is enabled after the
SteamVR restart, load/inject the game, enable Portal Output only, and connect
the AVP to the portal entry at exactly 2560x736/60 SDR HEVC with 6DoF mode on.
Window placement and real encoded/decoded metadata still require this live run.

## Installation history

The user closed the game, injector, SteamVR and Moonlight stream. The first
elevation request was canceled/expired; a replacement request was accepted.
Windows PowerShell 5 exposed array-wrapping differences in ConvertFrom-Json;
backup selection was corrected and read-only validation then passed under
the actual elevated executable. The backup itself remained intact.

The signed driver is now registered as `ROOT\DISPLAY\0000`, hardware ID
`Root\MttVDD`, PnP status OK. Sunshine dxgi-info.exe sees a new attached
`\\.\DISPLAY5` at 2560x736 on the RTX 4080, alongside physical DISPLAY1 at
2560x1440. The proposed XML is installed. No Sunshine configuration has changed.
Refresh rate, HDR/scaling and origin are pending Sunshine's refreshed inventory.

The GUI-subsystem installer helper returned asynchronously under PowerShell 5,
leaving LASTEXITCODE unset; the wrapper initially recorded failure before the
device appeared. The original result is preserved. Independent hardware ID,
PnP health, XML hash and DXGI checks confirmed installation completed; the
result was reconciled with the exact device ID for rollback. No reinstall was
performed. The setup script now uses Start-Process -Wait -PassThru and separate
output/error files. Its revised install path has not needed a second live run.

The next administrator request ran refresh-sunshine.ps1 to preserve the log
and restart SunshineService while disconnected. At this checkpoint the request
was pending; it later expired. The replacement request completed at 17:32,
providing the measured inventory used for configuration above.

## Preparation history

Source checkpoint `4da8aac` was committed and pushed by the user. UEVR `67d9785`
remains the verified live SBS baseline. The following records describe the
pre-installation state and validation.

Sunshine's own dxgi-info.exe currently detects only NVIDIA GeForce RTX 4080
output `\\.\DISPLAY1`, 2560x1440, attached to the desktop. Prior CDS_TEST evidence
rejects 2560x736 at 60 Hz on that physical display. No PnP Display device has
hardware ID `Root\MttVDD`; hardware IDs were inspected separately from device
instance IDs. Windows build is 26200 / 25H2 (the legacy ProductName registry
string misleadingly still reads Windows 10 Home).

An existing `C:\VirtualDisplayDriver` directory contains the required driver.
Its INF, DLL and catalog are byte-identical to the official driver-only asset
from [release 25.7.23](https://github.com/VirtualDrivers/Virtual-Display-Driver/releases/tag/25.7.23).
The release packages an INF reporting driver version 11.30.4.434 dated
2024-12-24; package tag and embedded driver version are distinct.

Downloaded archives remain under ignored `External/local-validation/sunshine-sbs`:

- Driver SHA256: `e24210692b442b39af763536330ce78b423f19342b7a7792c26de3944e418b3a`.
- Nefcon v1.14.0 SHA256: `a15557da24a9efca203158de3b43b0eaf982db231f0194031f1ed428bc13e669`.

Both match published GitHub release digests. Windows Authenticode verification
reports Valid for the driver DLL/catalog (SignPath Foundation) and the x64
installer helper (Nefarius Software Solutions e.U.). These checks establish
provenance and signature validity, not runtime compatibility with this machine.

## Configuration and installation boundary

The existing XML and Sunshine's sunshine.conf/apps.json were backed up with
verified SHA256 to
`External/local-validation/sunshine-sbs/before-display-20260906-154247`.
`backup-manifest.json` records exact paths/hashes. Pairing credentials were not
read or copied. Existing driver settings include CustomEdid=true and multiple
modes; the proposed XML replaces these temporarily with one RTX 4080-backed
display, one 2560x736@60 mode, CustomEdid=false, SDR10bit=false and HDRPlus=false.
Windows HDR state and scaling still require measurement after device activation.

The [upstream configuration documentation](https://github.com/VirtualDrivers/Virtual-Display-Driver/wiki/How-to-configure-the-driver)
defines the XML fields and default configuration directory. The local
install-display.ps1 uses Nefcon's verified `install INF HardwareID` interface.
It does not invoke the older installer/uninstaller batch files, which include
unnecessary removal of existing devices/files, or import certificates.

Read-only validation passed: pinned file hashes and signatures, intact original
backup/current XML, proposed dimensions, configuration path and absent matching
hardware device. The rollback script passed PowerShell parsing. Neither script
has performed an installation/removal yet.

After the user closes game, UEVR injector and SteamVR and disconnects Moonlight,
run elevated:

```powershell
& 'D:\Tools\Moonlight-SpatialSDK\Documentation\visionOS-6DOF\evidence\windows\sunshine-sbs\install-display.ps1'
```

The script refuses active game/runtime processes, changed pinned files/settings,
an existing matching device or a previous installation result. It records newly
created device instance IDs and the configuration hash even after a partial
failure. Review `External/local-validation/sunshine-sbs/display-install-result.json`
and `driver-install.txt` before any retry. If installation needs publisher trust
or another prerequisite, inspect the actual failure before making further changes.

Rollback, with the same applications closed, elevated:

```powershell
& 'D:\Tools\Moonlight-SpatialSDK\Documentation\visionOS-6DOF\evidence\windows\sunshine-sbs\rollback-display.ps1'
```

Rollback removes only recorded instance IDs after verifying their hardware IDs,
then restores the original XML with a hash check. It preserves the driver store,
existing files and Sunshine configuration. Inspect partial installation results
before rollback if no device IDs were recorded.

## Next measurements

After installation, verify the new display is extended (physical display still
primary), actual mode 2560x736@60, SDR, 100% scaling and a measured desktop origin.
Then configure UEVR Portal desktop X/Y to that origin. Verify Sunshine can
enumerate/capture the display before changing output selection or reconnecting
the AVP. Client/Xcode changes remain with the Mac session. No synthetic input
is used. See the [delivery plan](../../../../../docs/superpowers/plans/2026-09-06-sunshine-sbs-headset.md).

## Returning to ordinary desktop capture

Disconnect streaming first. Restore sunshine.conf and apps.json from the
hash-verified before-display-20260906-154247 backup, then run refresh-sunshine.ps1
elevated. Review any subsequent configuration changes against sunshine-applied.json
before restoring. This returns output selection to its original physical-display
default. Restore the closed game's XML-style key/value config from
uevr-before-display-config.txt only if it has no later changes to preserve.
Restore Sunshine selection before removing the virtual device with
rollback-display.ps1, so capture is not left targeting a removed display.
