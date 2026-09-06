# Baseline preparation — 2026-09-06

Status: preparation performed; waiting for the user's uninjected game check.
No runtime validation gate has passed. No game, driver, profile, or capture
settings were changed. No application was started, stopped, or injected.

## Verified backups

Local backup directory (ignored by Git):

`External/local-validation/baseline-20260906-105618/`

All 62 copied files matched their source SHA256 hashes. `manifest.json` in
that directory records each source path, backup path, and hash. Preserve this
directory for rollback; it is intentionally not part of the portable evidence.

| Backup | Original location |
|---|---|
| UEVR-HogwartsLegacy/ | C:/Users/josh2/AppData/Roaming/UnrealVRMod/HogwartsLegacy/ |
| Game-WindowsNoEditor/ | C:/Users/josh2/AppData/Local/Hogwarts Legacy/Saved/Config/WindowsNoEditor/ |
| steamvr.vrsettings | C:/Program Files (x86)/Steam/config/steamvr.vrsettings |
| openvrpaths.vrpath | C:/Users/josh2/AppData/Local/openvr/openvrpaths.vrpath |
| sunshine.conf | C:/Program Files/Sunshine/config/sunshine.conf |

Restore only the affected configuration with its owning application stopped,
after preserving any subsequent intentional edits. Game saves were not copied
or modified. Sunshine pairing state and credentials were not collected.

## Inventory additions

- Hogwarts Legacy: installed Steam app 990080, build ID 20773316, under
  `D:/SteamLibrary/steamapps/common/Hogwarts Legacy`. It was not running during
  this inventory. User selection of this game/save remains to be confirmed.
- Previous UEVR profile log, September 6 at 10:19:09: `Hooking D3D12`.
  This identifies the previous attempt's backend, not a newly tested session.
- Saved game settings: 2560x1440, FullscreenMode=1, HDR disabled.
- Existing UEVR profile describes first-person motion-controller gameplay,
  head/right-hand locomotion, and Synced Sequential rendering. It includes
  camera/controller Lua scripts. Its configuration has VR_DecoupledPitch=true
  and VR_AimModifyPlayerControlRotation=true. Preserve it and prepare a separate
  ordinary-gamepad Native Stereo baseline before injection; do not assume this
  profile meets the portal restrictions.
- UEVRInjector is running, but process queries did not expose its executable
  path. Downloads contains UEVR(1) and UEVR(2) directories; neither is asserted
  to be the active build. Build provenance remains open.
- SteamVR log confirms runtime 2.16.7 (v1781734990) at its September 6 startup.
- The user-context OpenVR registry lists only the Virtual Desktop Streamer
  OpenVRDriver as an external driver. Together with the previously inspected
  standard SteamVR driver directory, no registered VRto3D driver was found.
  Existing drivers were left intact.
- Ethernet PC address: 10.1.95.5. Headset IPv4 remains unmeasured.
- Windows Forms reports one screen, DISPLAY1, primary, bounds 2560x1440 at
  origin (0,0). AppliedDPI registry value is 96; current effective per-monitor
  scaling and refresh rate still need direct verification before portal capture.
- Git/UESDK dependency access remains unverified; the earlier empty UESDK
  checkout finding has not been resolved. No builds or synthetic tests ran.

## Required user checkpoint

1. Confirm Hogwarts Legacy is the intended test game by launching it normally,
   without UEVR injection (or identify the intended replacement).
2. Load a repeatable save near visible straight edges and objects at different
   distances. Record the location/save name.
3. Walk, turn the camera with the controller, and open/close a menu. Report any
   control or rendering issue. Leave the game at that location afterward.
4. Identify the running UEVR injector's executable folder/build if available.

The Vision Pro is not needed for this checkpoint. Resume virtual-HMD setup
after this baseline observation; coordinate any subsequent runtime restart
with the user's live game state. Later stages remain unexecuted.
