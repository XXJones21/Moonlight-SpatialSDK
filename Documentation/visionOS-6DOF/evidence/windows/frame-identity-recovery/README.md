# Portal recovery after pause/loading — 2026-09-08

Replacement: `c34eaa24894a240a97b7eaefbc092c8303cd37a1`, packaged separately at
`External/local-validation/UEVR-portal-c34eaa2`. All eight hashes and embedded
revision verify. Backend SHA256:
`0a278a7b868138e5904ca5175d426ae383b112b08e65eb2c51bf50dd607b2c07`.
The exported source patch reverse-check passes. The package was staged at 13:09:11
after game closure and removal of the lingering old injector. The full profile
backup is `External/local-validation/pre-native-fix-c34eaa2-20260908-130911`.
All eight package hashes passed again; the only setting change was Portal Output
true to false for initial injection. Native Stereo Fix remains enabled. The new
injector was launched from the `c34eaa2` package. Injection and pause/loading
recovery were subsequently confirmed; see final acceptance below.
See [profile transition](profile-transition.json).

## Reproduced cause

Josh reports that `4007df4` initially streams with Native Stereo Fix, but flat
pause/loading transitions turn the headset panel gray. Portal Output off/on or
reconnecting can restore video; resuming gameplay can make it gray again.

The preserved UEVR log confirms successful, paired output with engine frame IDs
moving backward while accepted pose sequences advance and geometry is unchanged:

| Before | After | Pose sequence | Geometry |
|---|---|---|---|
| 3458 at 12:32:01.014 | 6 at 12:32:04.701 | 6651 → 6977 | 1633 |
| 20632 at 12:37:49.917 | 3 at 12:37:51.044 | 6678 → 6782 | 1869 |
| 26055 at 12:45:05.245 | 20699 at 12:45:06.270 | 579 → 668 | 1870 |
| 22386 at 12:45:34.436 | 21 at 12:45:35.445 | 297 → 388 | 1910 |

The old exporter placed `gameFrameID` directly into both video metadata and UDP
`renderFrameID`. `PortalHost/src/StateRelay.cpp` rejects backward status frame IDs
within a session/epoch. The local Swift `PortalFrameGate` likewise rejects backward
video IDs for the expected session/epoch/geometry. This violates the wire ordering
contract during engine scene/view-family changes. An offline regression replaying
these counters against the actual exporter metadata/status functions fails on
`4007df4` with `scene counter rewind must not rewind wire frame identity`.

The CutsceneComfort event only applies temporary aperture settings. No plugin or
Lua script is installed in the current game profile; no evidence implicates that
bridge. It remains unchanged.

## Correction

Each newly latched immutable `PortalFrame` receives an increasing `outputFrameID`.
The counter survives cache, device and Portal Output resets. Both transmitted
metadata and status use this ID. Unreal frame IDs and render aliases continue to
associate the exact pose and eye textures internally. Repeated latches keep their
ID, and an old frame delivered after a newer frame still carries its old lower ID.
The log retains engine `frame=` and adds wire `outputFrame=`.

The counter is local to the backend process. Restarting the game/backend under
an unchanged client session is not covered; start a new streaming session then.
No host or Xcode change is needed for this correction. Mac session should retain
the stale-frame guards. Resolution and cadence remain 1280x720 per eye/60 fps.

## Verification and limits

- Failing test reproduced with the original production metadata/status functions.
- 107 production frame-cache/metadata/status checks pass after the correction.
- 231 D3D12 copy/UI/metadata checks and 20 native-pair checks pass; debug layer on.
- GPU source lifetime, 32 secondary Present and 72 projection regression cases pass.
- Release backend build and independent review pass; review found no blockers.
- Packaging provenance is recorded in the adjacent manifest and verification file.

Josh subsequently confirmed pause/loading recovery with the replacement injected.
Missing genuinely fresh stereo frames still suppress output; this change does not
fabricate frames during loading or relax tracking validity.

## Capture caveat

Later failed-state captures saw only outgoing `staleTracking` status and no inbound
poses or video. Josh confirmed a mix of wearing and removing/disconnecting the
headset, so those samples cannot establish what triggered the original failure.
Do not infer a client pose-loop bug from them. Source observation during that
inactive interval found a hidden SBS HWND and advancing engine frames with stale
tracking. Raw captures/logs remain under ignored `External/local-validation`.

## Historical live test procedure (completed for menu/loading recovery)

Close game and injector before staging the new package. Back up the profile,
preserve Native Stereo Fix and the fixed resolution, and initially keep Portal
Output off. After injection and a connected AVP, enable Portal Output and test
pause/resume twice, then an actual loading transition. Leave the headset connected
through the test. Check both-eye lighting, HUD/menu, continued head tracking, and
automatic recovery without toggling Portal Output. Compare engine `frame=` resets
against advancing `outputFrame=` in the log and decoded source metadata.

## Final acceptance and fullscreen occlusion

After pause/loading recovery, a second gray-panel failure occurred while UEVR
continued logging successful paired output with advancing wire frame IDs. The
actual game HWND and visible SBS HWND occupied the same capture rectangle
`[2560, 0, 5120, 736]`. The game restored itself over SBS after closing its menu;
Josh confirmed this happened without Windows input. Moving the game away was
therefore temporary. The before samples contain no decodable desktop metadata.

The bounded `sunshine-sbs/protect-sbs-window.py --pid 60500` probe made the SBS
HWND topmost without activation, moving, resizing or showing hidden output.
Foreground HWND remained 2624140. Three subsequent desktop samples decoded P6FM
with matching eye tags and valid CRCs, advancing through 29961, 30037 and 30102
within session 7690033629623208480 / epoch 69 / geometry 2193. See the preserved
`occlusion-before.json` and `occlusion-after.json` (their image names refer to
ignored local captures, not tracked images).

Josh then confirmed repeated menu/game transitions work and authorized this
stabilization commit. The supplied 21.1-second headset recording was probed,
hashed and sampled: menus and gameplay appear; some sampled transitions show
black menu rectangles or a gray panel. This is acceptance of recovery, not a
claim of seamless transitions. Small centered menu content remains polish work.
Recording details and the single-eye limitation are in `recording-acceptance.json`.

Permanent source commit `2c3523958a0fc61124b02fca7c8edef9d99fb991` changes
`PortalOutput::Impl::show()` to `HWND_TOPMOST` with `SWP_NOACTIVATE`, preserving
hidden-output gating. It does not move the game or change input routing.
Release builds and regressions pass; the new package has eight verified files
and the committed revision embedded. Backend SHA256:
`e6ecdfb314f2c9f8c481c06e20aa40137d7a02222a6f2a51d83cc7838bc62598`.
Package: `External/local-validation/UEVR-portal-2c35239`. Its Native Stereo Fix
profile is copied from c34eaa2 with Portal Output initially off. The live validated
run used c34eaa2 plus the window probe; this permanent binary is not yet injected.
Josh closed the game and injector after acceptance. No additional live test is
required to commit this checkpoint; use the new package on the next launch.

Resolution/90 fps scaling and broad session-restart qualification remain future
work. No change to stale-frame rejection or CutsceneComfort was required.
