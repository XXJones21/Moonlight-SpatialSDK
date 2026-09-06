# Virtual HMD validation — 2026-09-06

## Result

Automated device discovery, one-shot validity, and stationary pose sampling
passed. User-facing SteamVR state confirmation and ordinary stereo injection
remain pending. This does not establish portal rendering or synthetic tracking.

The user confirmed normal, uninjected Hogwarts Legacy gameplay and controller
movement/menu operation, then left the game in a house common room. The house
and save-slot name were not specified. The same game processes remained alive
through the SteamVR restart and subsequent measurements.

## Driver acquisition and configuration

- Official release: [VRto3D V5.0.0](https://github.com/oneup03/VRto3D/releases/tag/V5.0.0).
- Release tag commit: `7b1bddb264cabc53d61bd8883d07bbfb498c5256`.
- Downloaded asset: `VRto3D.zip`, 5,109,334 bytes.
- SHA256 verified against GitHub's published asset digest:
  `343d63e9bfaaaa03e0ee5aa68e7f6e7cbb0fb527d5b16cfbf981881befebf34e`.
- Release version differs from the source-reference commit `edac2b2`; the
  reference checkout was not modified. The release's tracking source was
  downloaded separately for inspection, not treated as runtime proof.
- Registered external driver path:
  `D:/Tools/Moonlight-SpatialSDK/External/local-validation/vrto3d-v5.0.0/package/vrto3d`.
- Registration command: SteamVR `bin/win64/vrpathreg.exe adddriver` followed by
  that path. Existing Virtual Desktop registration was preserved.
- Active configuration: `C:/Program Files (x86)/Steam/config/vrto3d/default_config.json`.
  This path did not exist before setup. The driver expanded our explicit static
  settings with its defaults; the resulting file is saved as
  `driver-static-config.json` alongside this report.
- SbS, 1280x720 per eye, 60 Hz configured; OpenTrack, controller pitch/yaw,
  tracking filter, LeiaSR tracking, async reprojection and auto-depth disabled.
  Auto-focus is disabled; the game may need focus restored manually.
- No Sunshine/display-mode/game-profile change was made. This ordinary runtime
  presentation is not the later exact-size direct portal capture.

A close-window request did not terminate the old SteamVR session. Only verified
SteamVR runtime processes were then stopped, followed by `vrstartup.exe`.
Neither Hogwarts process was stopped or injected.

## Measurements

- SteamVR 2.16.7 activated HMD `vrto3d.VRto3D-1234`, model Stereo3D-1.
- `vrcmd.exe --background --info` succeeds after previously failing with
  VRInitError_Driver_WirelessHmdNotConnected. Full output: `runtime-info.txt`.
- Runtime reports eye render targets 1280x720 and eye offsets -0.05/+0.05 m,
  reflecting this driver's default depth=0.1. This is not measured Vision Pro IPD.
- `vrcmd.exe --background --pollposes` produced 6,280 complete identical lines
  during a 100.50-second wall-clock capture. Raw output: `static-poses.csv`;
  summary and time bounds: `static-summary.json` and `pose-sampling.json`.
  One incomplete final line caused by stopping the diagnostic is excluded.
  Equality is at the diagnostic's printed precision.
- The disposable `inspect-pose.cpp` probe queried OpenVR's explicit validity
  fields after sampling. Both raw and standing poses report connected=1,
  valid=1, and tracking_result=200 (Running_OK). See `pose-validity.txt`.
- Raw driver position: (0,1,0) m. Standing-universe position: (0,2,0) m.
  The observed standing-space translation is +1 m on Y at this static pose.
  Do not remove it speculatively; account for it explicitly during the synthetic
  tracking/camera-composition gate.
- SteamVR compositor is running. SteamVR logs still contain UI/action-manifest
  and generic-HMD input-binding errors; these are retained as open observations,
  not interpreted as proof of failed ordinary gamepad input.

The read-only validity probe was compiled successfully with VS 2022 Build Tools
17.14.21, using the checkout's `dependencies/openvr/headers` and dynamically
loading the verified package's openvr_api.dll. It does not alter poses/settings.
Its successful exit code was checked. This is a disposable diagnostic, not a
new production dependency.

## Before the next gates

1. User confirms the visible headset state and that the game remains at the
   common-room location. Identify the active UEVR injector folder/build.
2. Use the staged `ordinary-stereo-config.txt` as the basis for a separate
   profile, without loading the original first-person camera/controller scripts.
   The live profile has not yet been replaced. Its original is backed up as
   described in the preceding baseline report.
3. Inject only after profile/backend provenance is recorded. Then perform the
   user-piloted two-eye and ordinary gamepad test. Portal Output stays off.
4. Before synthetic tracking, restrict the driver's OpenTrack receiver to
   PC-local access. Measured endpoint is 0.0.0.0:4242 despite use_open_track=false;
   the release source also binds INADDR_ANY and skips consumption while disabled.
   The loopback requirement is not met by the stock socket bind. Resolve this
   with a measured access restriction or a deliberate driver change before
   enabling input. No firewall rule was created during this static test.

## Rollback

With SteamVR closed, remove this registration using `vrpathreg.exe removedriver`
and the exact registered path above. Preserve any new desired settings before
restoring the original steamvr.vrsettings/openvrpaths.vrpath backups. Archive the
new VRto3D configuration if reverting setup. Do not remove other driver entries.
Do not move/delete the registered driver directory while it is still in use.

Portal builds, synthetic tracking, native stereo gameplay, capture, and Vision
Pro tracking have not been validated in this run.
