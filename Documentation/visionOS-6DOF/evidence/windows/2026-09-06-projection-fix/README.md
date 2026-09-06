# Deterministic projection before pose arrival — 2026-09-06

Status: defect reproduced, fixed, regression tested and built. The user injected
this revision and confirmed Portal Output toggles without flashing. The separate
UEVR/Steam Overlay Present recursion recurred in a subsequent crash and has not
been fixed or cleared by this change. Synthetic testing is stopped at the user's
request; continue with the live-headset preparation checkpoint.

The user confirmed disabling Portal Output stops the flashing. Re-enabling it
briefly restored the right-eye scene; character movement then resulted in a
black scene while HUD icons remained stable. No new pose sender was running.
This isolates the visual regression to portal-mode behavior in this session;
it does not by itself prove which instruction caused the visible flashing.

Source inspection established a definite defect in the projection hook: with
no original stereo projection hook, only the near-plane element was seeded.
Portal mode then returned this output without filling the other 15 elements
when no valid frame existed, including initial pose wait and lease expiry.

The portal branch now always writes a complete, finite reversed-Z perspective
matrix in the selected float/double precision. Missing/invalid frames use a
symmetric preview projection at the configured eye-content aspect. Valid frames
retain their calibrated off-axis frusta. Invalid near values fall back to the
engine near plane, then one Unreal unit if that value is also invalid.
Only valid calibrated eye projections are recorded as observed. Frame validity,
pairing, metadata and tracking-lease suppression were not relaxed.

## Validation

test_projection_branch.py extracts the actual portal branch from the production
hook and compiles it against PortalCore with narrow engine-boundary stubs. Output
starts poisoned with NaNs except its near-plane element, reproducing the emulated
stereo-device initialization. It checks all matrix coefficients, adjacent buffer
guards and valid-only eye observation for missing/invalid/valid frames, valid and
out-of-range eyes, valid/zero/NaN near input, and float/double precision.

- Before the fix: the harness compiled and failed 1,076 checks.
- After the fix: all 72 cases passed.
- Full UEVR Release x64 build passed, followed by a successful rebuild with the
  committed revision embedded. No production source changes followed the passing
  regression run. Logs: regression-before.txt, regression-after.txt, build.txt,
  build-final.txt.
- Patch reverse-application check passed against the committed tree; exported
  patch hash and embedded binary revision verified.

The test validates the real projection branch in isolation, not the complete
game hook, rendered pixels or visual recovery. Source change is confined to
FFakeStereoRenderingHook.cpp, committed as
`bb7e3ef81c3a5cc55be7b1e274ab7a0a49c99cb3` in the external fork and exported to the
handoff patch. Earlier PortalCore/PortalHost binaries and their tests were not
changed by this fix.

## Package and next checkpoint

Package: `D:/Tools/Moonlight-SpatialSDK/External/local-validation/UEVR-portal-bb7e3ef`.
All eight copied file hashes verified; the backend contains the expected full
revision. [package-manifest.json](package-manifest.json) records source paths and
hashes. The original and 0b59830 packages remain intact. This package was injected
after the profile transition below. The supported baseline config with Portal
Output off is retained alongside the new injector.

At 13:41 local time the user confirmed both game and injector closed. Process
inspection confirmed this, and ten profile files were backed up with verified
hashes to External/local-validation/pre-bb7e3ef-20260906-134129. All 20 staged
settings were merged and verified, preserving other preferences. See
profile-transition.json and config-before-injection.txt. The new package passed
all file hash checks and its injector was opened (PID 42108); SteamVR remained
running. The user subsequently loaded the game, injected, and confirmed Portal
Output on/off no longer caused flashing.

The already-running synthetic renderer probe was followed by a game crash at
13:47:21. Offline dump analysis again found the repeated Present hook chain;
see ../2026-09-06-renderer-protocol/bb7e3ef-overlay-disabled/dump-stacks.txt.
This does not invalidate the user's no-flashing observation, but direct output
and stability remain unvalidated. The user requested no further synthetic tests
and wants to continue using the headset. See the
[live-headset checkpoint](../2026-09-06-headset-preparation/README.md).
