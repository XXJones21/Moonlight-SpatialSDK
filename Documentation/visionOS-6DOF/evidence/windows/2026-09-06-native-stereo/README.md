# Ordinary Native Stereo setup — 2026-09-06

Status: injected; ordinary stereo gate remains open due to eye mismatch.

## User-directed continuation

The user subsequently reported stable video with no visible flicker after
disabling Frame Generation and DLSS, while the left/right appearance remained
mismatched. They explicitly requested deferring the mismatch and proceeding
with the next Windows build/tracking steps. This authorizes continued work
without marking the visual stereo gate passed.

Fresh saved settings confirm Native Stereo (RenderingMethod=0), FrameGeneration
Off, UpscaleMode=None, ray tracing options off, and NVIDIA Ray Reconstruction
off. The latest inspected log contains routine hook messages without recurring
duplicate-submit errors. See `stable-mismatched-user-observation.png`.

The user also reports the main game view is black and interacting through the
VRto3D presentation is difficult. Preserve this as an unresolved usability issue
for later headset-assisted diagnosis. The warning visible in the UEVR screenshot
is not sufficient evidence that frame generation remains enabled after the
saved setting changed.

AimMethod=3, MovementOrientation=3, and DecoupledPitch=true are still persisted.
These remain incompatible with the planned ordinary-camera portal profile and
must be corrected and verified before Portal Output is enabled. Do not count
the current session as proof of independent gamepad/portal camera ownership.

## First injected observation and correction

The user injected the identified package through OpenVR. Loaded module paths
confirm that package's UEVRBackend.dll and openvr_api.dll; the fresh log confirms
D3D12. The initial observation was stable left-eye scenery and a flickering/black
right-eye scene with HUD and UEVR UI still visible.

The persisted profile at 12:00:45 again contained RenderingMethod=2, controller
aim/movement orientation=3, and DecoupledPitch=true, despite the verified staged
configuration before injection. The mechanism restoring those old values is
not established. There were no original camera scripts in the new profile.

The log repeatedly reported compositor Submit error 108. In the OpenVR
compositor error enum this is AlreadySubmitted (duplicate eye submission before
the next WaitGetPoses), not the unrelated VR initialization error numbered 108.

The user then confirmed the live menu was set to alternating AFR and changed it
to Native Stereo. They reported this corrected the issue "for the most part."
The second screenshot confirms Native Stereo and Native Stereo Fix disabled,
but still shows substantially different lighting/scene detail between eyes.
See `native-stereo-user-observation.png`.

The inspected log ends its repeated error 108 reports at 12:03:19 during D3D12
texture recreation; subsequent entries through 12:03:57 contain routine hook
messages and no further error 108. This supports improvement after the switch,
but does not prove complete visual correctness or long-term stability.

The config file still had its 12:00:45 timestamp when inspected after the live
switch, so it does not establish the current in-memory rendering method. The
fork saves configuration when the UEVR menu visibility changes; close the menu
and inspect the resulting save before another injection/restart.

Saved Hogwarts graphics settings select DLSS Quality, Intel XeFG frame
generation, ray-traced shadows/reflections/AO, and NVIDIA Ray Reconstruction.
Their live values require menu confirmation. Next controlled visual check:
disable Frame Generation only, retain Native Stereo, and compare the same
stationary scene. UEVR's [official troubleshooting guidance](https://docs.uevr.io/index.html)
associates rapid flickering with DLSS Frame Generation; treating XeFG as a
candidate here is an inference, not an established diagnosis. Ray tracing and
upscaling remain separate variables for later checks if needed.

No settings were changed by Codex during these diagnostic reads. Do not enable
Native Stereo Fix or advance to portal output to bypass this unresolved gate.

## Passed prerequisite

The user confirmed SteamVR visibly shows a connected headset without a
headset-disconnected error, and Hogwarts remains usable in the common room.
Together with the recorded runtime pose measurements, this completes the
stationary virtual-HMD checkpoint. Ordinary stereo gameplay remains untested.

## Baseline backend provenance

The user identified the running injector package as:

`C:/Users/josh2/Downloads/UEVR-6DOF-Window-nightly-01139-v0.2.0-alpha.1`

Package revision.txt and README identify fork commit
`fb31341e860b15e116a15123820c95f044ff0a0f`, matching the handoff's pinned baseline.
This is the dedicated 6DOF Window fork before our authored portal patch,
not the authored `562dbe3` portal backend and not unmodified upstream UEVR.
Package identity is supported by local metadata and these recorded hashes;
no reproducible-build or publisher-signature claim is made.

| Binary | SHA256 |
|---|---|
| UEVRBackend.dll | E4478312CD3EFEE950A0B94DE9B02A105368A34991A1EC1D06F7F5E77AE31CD7 |
| UEVRInjector.exe | F61A9C75F605FF097BE5FC60F574D832DEDCCF975C64BEE6465B9D86A12021F2 |

The package's revision and README are copied alongside this report. The README's
historical validation results are not results from this Windows test.

## Profile transition

- Game executable: `D:/SteamLibrary/steamapps/common/Hogwarts Legacy/Phoenix/Binaries/Win64/HogwartsLegacy.exe`.
- Game PID at setup: 46572. The separate launcher PID is 37660.
- Inspected 200 loaded modules before changing the profile; no UEVRBackend.dll
  or LuaVR.dll was present.
- Original profile preserved at
  `C:/Users/josh2/AppData/Roaming/UnrealVRMod/HogwartsLegacy.pre-portal-20260906-115855`.
  All 31 file hashes matched before and after the move.
- Active profile remains the conventional
  `C:/Users/josh2/AppData/Roaming/UnrealVRMod/HogwartsLegacy` directory, containing
  only the staged ordinary-stereo config at setup. No original camera scripts,
  plugins, or UObject attachments were copied into it.
- Config: OpenVR, Native Stereo (RenderingMethod=0), game aim and movement
  orientation, no decoupled pitch/roomscale/VR controllers/snap turn, old
  WindowMode disabled. PortalOutput/diagnostic flags are also false; the
  baseline binary predates that feature.
- Exact paths and active configuration hash: `profile-transition.json`.
- Profile values were checked against the existing fork source. No game save,
  graphics settings, Sunshine settings, or driver settings changed in this step.

The injector window did not expose actionable descendants to UI Automation in
this session. No injection was attempted through another mechanism.

## User checkpoint

1. In the identified injector, select OpenVR and the running game process
   (PID 46572 at setup, not its launcher), then click Inject once.
2. Verify Rendering Method is Native Stereo; leave 6DOF Window disabled.
3. Inspect the VRto3D presentation for both eye views, then focus Hogwarts for
   controller input. Auto-focus is currently disabled in VRto3D.
4. Walk, turn the camera, inspect near/far objects, and open/close a menu.
   Observe for approximately two minutes if stable. Report blank/duplicate eyes,
   flicker, unwanted camera motion, missing controls, or errors.

If injection fails, retain the logs and stop at that failure. Do not switch to
OpenXR/alternating rendering to force this gate through. The Vision Pro is not
needed for this ordinary Windows stereo check.

After user feedback, verify fresh game/runtime logs and profile values before
marking the stereo gate passed. Then proceed to portal builds and synthetic
tracking within the previously authorized scope.

## Restoration

With the injected game closed, preserve any new desired baseline settings and
restore the original profile directory from the exact preserved path above.
Keep the separate initial backup under External/local-validation as a second
recovery copy. Do not restore or swap profiles while an injected session can
write them back.
