# Native Stereo Fix portal — 2026-09-08

Implemented and qualified offline at `4007df45c6017eccc3354df603b0a97043fda7ae`.
Live game/headset acceptance is pending. After Josh confirmed closure, staging
verified all eight package files and backed up the profile to
`External/local-validation/pre-native-fix-4007df4-20260908-122827`.
The saved profile already matched the intended settings, so no setting changes
were needed. Injection was subsequently confirmed from the game log at 12:30:59.
Josh confirmed initial output, then exposed a pause/loading recovery failure.
See the [frame-identity recovery correction](../frame-identity-recovery/README.md).
See [profile transition](profile-transition.json).

## What changed

The D3D12 portal now supports OpenVR Native Stereo with Native Stereo Fix. It
copies the left game target and separate native-fix right target into the existing
SBS output, then appends the HUD/menu composition and metadata as before.
Construction order identifies the eyes before Use Same Stereo Pass relabeling.
Both submissions and source identities must match one immutable portal frame;
the render-frame alias resolves back to that frame for metadata. Missing pairs
fail closed with `native_eye_pair_unavailable`. Both GPU sources remain retained
until their output slot's fence completes.

Mode, session/epoch and device resets invalidate stale associations. Offline
tests exposed two cache defects during implementation: recycled view addresses
invalidated completed in-flight evidence, and reused frame IDs could return an
old tracking epoch. Both regressions now pass against the production frame cache.

AFR and D3D11 Native Stereo Fix remain unsupported. Keep 1280x720 per eye,
2560x736 capture and 60 fps. No fog, motion-controller scripts or Mac code changed.

## Evidence

- Release x64 build passed again after committing; backend embeds the full revision.
- 231 D3D12 checks: 36 eye-copy pixel frames plus six combined eye/HUD/menu/metadata
  frames. Combined tests hold three slots in flight, release caller references,
  then reuse slots after fence completion. Debug layer enabled with no errors.
- 20 native-pair state-machine checks and 50 production frame-cache checks passed.
- Existing 24-frame WARP UI test, GPU lifetime regressions (including both source
  references), 32 secondary Present cases and 72 projection cases passed.
- Independent code review and follow-up found no remaining blockers.
- Eight package hashes, embedded revision and exported patch reverse-check passed.

See [verification.json](verification.json), [package manifest](package-manifest.json),
and the adjacent test logs. Copy tests initially failed without the helper;
omitting source-state restoration produced debug-layer errors. Cache tests failed
on each of the two defects above before their corrections.

Package: `External/local-validation/UEVR-portal-4007df4`.
Backend SHA256: `15e3582032ac96613db723f5fb4ef7eb0c8eb723572d8688e7cf2a5c7d33e310`.
The prior `e0d3fa8` package remains available for rollback.

## Live staging and acceptance

### First live result

Josh reports the new build "worked mostly", then a UI loading screen caused
SBS to stop and prevented access to the UEVR menu. This is initial live progress,
not sustained recovery qualification. The game remained running (PID 92360),
as did SteamVR and the live pose relay. Inspection found the portal HWND 1642074
still present but hidden. The actual game HWND 1052718 had reopened over the
virtual display at `(2560,0)-(5120,736)`. It was moved to the physical monitor at
`(80,80)-(1680,1080)` and foreground activation succeeded, to restore menu access.
The UEVR log was zero bytes, so no specific suppression reason or GPU failure
is established. Josh's next check is Insert in the actual game window, then
Portal Output off/on and whether SBS returns. No process was restarted.

Josh confirmed off/on restored SBS. He reports the failure occurs whenever a
flat pause menu or loading screen begins. Automatic recovery is therefore not
qualified. The `CutsceneComfort.WindowMode.v1` handler was inspected: it applies
temporary aperture parameters and requests aperture recentering; it does not
toggle Portal Output or reset PortalFrame. The current per-game profile contains
no plugin or Lua scripts. This provides no evidence of that event causing this
run's failure. Investigate interrupted stereo submissions and frame-cache state
through the transition before changing the compatibility bridge. A bounded
passive status capture attempted without administrator rights failed with
WinError 10013 and collected no packets. Next live gate: keep AVP connected,
reproduce the failure, exit the pause menu and leave Portal Output enabled
without toggling so the failed state can be inspected.

### Procedure

1. Josh closes Hogwarts Legacy and UEVRInjector. Run this folder's
   `stage-profile.py` in Josh's Windows context. It checks closure twice,
   verifies the package, backs up the full profile, and only changes the explicit
   runtime/native-fix/portal settings. Same Pass and unrelated settings are preserved.
2. Launch this package's injector and the game. Inject with OpenVR/D3D12,
   Native Stereo + Native Stereo Fix enabled, Portal Output initially off.
   Confirm the matching-eye reference in the loaded common room.
3. Keep the actual game window on the physical monitor for Windows mouse input.
   Connect the live Vision Pro session and enable Portal Output. Confirm SBS,
   continuing head tracking and matching eye lighting in the source and headset.
4. Check HUD/menu composition, stationary and moving views, toggle Portal Output
   off/on, then disconnect/reconnect. Confirm output resumes without gray-panel
   persistence, flashing or a stuck camera. Preserve logs and screenshots of both
   eyes; the headset screen recording alone only shows the left eye.

Actual game render-alias association, both-eye presentation and sustained recovery
are live gates; offline fixtures cannot qualify them. Defer resolution/90 fps
changes until these pass.
