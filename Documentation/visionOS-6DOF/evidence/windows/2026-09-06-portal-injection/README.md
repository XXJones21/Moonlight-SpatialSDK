# Patched backend injection checkpoint — 2026-09-06

Status: patched injection confirmed; preparing live synthetic tracking.

The user confirmed the new build works as intended so far, with Portal Output
and Room-Anchored 6DOF Window off. The screenshot shows revision 0b598303 and
both eyes presenting the game menu. This does not independently establish
in-world motion or resolve the previously deferred eye mismatch.

Loaded module paths confirm UEVRBackend.dll and openvr_api.dll from the new
package. The saved profile retains OpenVR, Native Stereo, aim/movement method 0,
controllers off, decoupled pitch off, roomscale off and Native Stereo Fix off.
Portal output and diagnostics remain false. See loaded-modules.json,
config-after-injection.txt, injection-log.txt and user-confirmed-injection.png.

## Tracking preparation

The stock driver still binds IPv4 0.0.0.0:4242, with OpenTrack consumption off.
The tool process is not a Windows administrator, even outside the filesystem
sandbox. restrict-opentrack.ps1 prepares a persistent inbound block scoped to
the actual SteamVR vrserver.exe, UDP 4242, all firewall profiles, and IPv4
addresses outside 127.0.0.0/8. It does not enable tracking. Windows firewall
supports this combination of application, port and remote-address ranges:
[Microsoft New-NetFirewallRule documentation](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule).

The UAC-elevated helper applied the rule successfully at 12:48:47 local time.
tracking-firewall-result.json records the result, and a separate ActiveStore
readback confirmed the enabled inbound block, exact application/port/ranges
and Enforced status. All Windows firewall profiles are enabled. Active-rule
inspection is not an external packet test. The driver remains IPv4-only; this
rule does not claim to change its bind address or qualify a future IPv6 receiver.
Rollback removes only rule MoonlightPortal-VRto3D-BlockRemoteTracking-4242.

The installed V5.0.0 README and release source support enabling OpenTrack live
through Ctrl+Home, Tracking, Enable OpenTrack, keeping port 4242. This avoids
a SteamVR restart. Use the common-room save for subsequent visible motion
checks; first keep portal output off to measure ordinary runtime pose delivery.

## Profile transition

The user closed the previous injector and launched Hogwarts Legacy. Before
changing the profile, the old injector was confirmed absent and both game
processes were inspected for loaded UEVRBackend/LuaVR modules; none were found.
Actual game PID: 36256. Launcher PID: 72056.

Ten current profile files were backed up and copy hashes verified at
`D:/Tools/Moonlight-SpatialSDK/External/local-validation/pre-portal-injection-20260906-124225`.
The exact manifest and applied keys are in [profile-transition.json](profile-transition.json).
Merged all 20 staged settings into config.txt, preserving other preferences.
The resulting profile is [config-before-injection.txt](config-before-injection.txt).

All packaged binary hashes were checked before launching the injector from
`D:/Tools/Moonlight-SpatialSDK/External/local-validation/UEVR-portal-0b59830`.
Start-Process returned PID 50016. The injector is an interactive user checkpoint;
no injection was performed by this preparation. Portal Output and the older
room-anchored 6DOF Window remain disabled in the saved profile.

Next: load the common-room save, select OpenVR and actual Hogwarts game in the
new injector, and inject once. Verify Native Stereo in the live menu and close
the menu to persist live values. User tests normal movement/camera and reports
whether both eyes remain stable, allowing the previously deferred visual
mismatch. Then inspect loaded module paths, backend revision, fresh logs and
saved settings before proceeding to live synthetic tracking.
