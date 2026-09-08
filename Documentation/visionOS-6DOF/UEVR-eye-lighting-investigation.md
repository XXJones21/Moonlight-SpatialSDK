# Hogwarts per-eye lighting investigation — 2026-09-06

## Implementation checkpoint — 2026-09-08

`4007df4` implements the approved D3D12 Native Stereo Fix portal integration.
Build, offline eye-copy/UI/frame-cache/lifetime tests and independent review
pass; the separate package is ready, but has not been staged or injected.
The next test is the user-confirmed native-fix reference followed by Portal
Output on with live AVP poses. See [evidence and live procedure](evidence/windows/native-stereo-fix/README.md).
Fog remains unchanged and untested. Earlier references below to an unsupported
native-fix exporter describe `e0d3fa8`, not this new implementation.

## Live comparison — 2026-09-08

**Loaded-game reference confirmed:** Josh subsequently loaded the common-room
save and confirmed the comparison during the requested gameplay check. The
attached two-eye screenshot shows consistent room lighting with Native Stereo
Fix on and Portal Output off. Together with the preceding no-flicker report,
this establishes the user-observed reference for portal integration. It does
not qualify the unsupported portal mode or long-duration performance.

The camera-freeze symptom is consistent with the inspected control flow:
Portal Output bypasses the ordinary view-offset path, but its Native Stereo Fix
compatibility rejection leaves the portal frame invalid and prevents applying
the portal view. This source explanation is not a new live trace.

**Native Stereo Fix test response:** Following the Native Stereo Fix instructions,
Josh confirmed the result with a screenshot showing much closer left/right
lighting in the menu scene. Re-enabling Portal Output then stopped head tracking
and produced no SBS, by user observation. The current exporter explicitly
rejects Native Stereo Fix as `unsupported_portal_configuration`, consistent
with missing output; the tracking symptom has not been independently traced.
Josh then compared Use Same Stereo Pass enabled and disabled and reported no
visible difference and no flickering. The saved file at the prior read still
reported Native Stereo Fix false and could not verify the live toggle; the
test result is user-observed. Loaded gameplay lighting, longer-term stability,
and stereo depth still need qualification.

There is currently no observed flicker that warrants importing the original
tiny-scene-capture workaround. Fog remains untested and is no longer the next
blocking test after the Native Stereo Fix improvement. Next visual gate: load
the same indoor gameplay scene with Portal Output off and Native Stereo Fix on,
then compare both eyes while stationary and during camera/character movement.

Next implementation target, once the working live combination is recorded:
support Native Stereo Fix's separate eye targets and frame/projection association
in Portal. Do not merely remove the compatibility guard. Leave Portal Output
off for further reference-mode visual tests.

Josh reports that the left/right lighting mismatch remains with Portal Output
disabled, and switching from Native Stereo to Alternating/AFR makes the eyes
match. The accompanying VRto3D screenshot shows the mismatched menu scene.
This establishes that the symptom can occur without the direct portal exporter;
it does not identify a single faulty lighting effect or qualify AFR for Portal.

The fog test remains unperformed because menu interaction was blocked. Given
the rendering-mode comparison, the next test is Native Stereo Fix in VRto3D,
with Portal Output kept off. Change only Native Stereo Fix after returning to
plain Native Stereo and confirming the mismatch. Record Use Same Stereo Pass;
test that option separately if needed. Compare the same loaded indoor scene.

A subsequent disk read showed Native Stereo, Native Stereo Fix false, Same Pass
false, Ghosting Fix true and Portal Output true. These saved values do not match
the reported live comparison and must not override Josh's observation; they
are not confirmation of the current in-memory state. No profile was rewritten.

## Findings from the original profile

Compared the current saved profile with the verified backup in
`External/local-validation/baseline-20260906-105618/UEVR-HogwartsLegacy`.
The original config and three relevant Lua files also match the later
`HogwartsLegacy.pre-portal-20260906-115855` backup byte-for-byte.

Profile metadata names Pande4360, jbusfield and DJ, game version 30 Apr 2025,
profile remarks v1.07; the Lua main script identifies itself as v1.08.
The description's v1.06c changelog explicitly records an automatic Native
Stereo Fix flickering fix. This is a rendering-aware profile, not just bindings.

| Item | Original profile | Current portal | Implication |
|---|---|---|---|
| Saved rendering mode | `VR_RenderingMethod=2` (Alternating/AFR) | `0` (Native Stereo) | Original saved configuration is not evidence of stable plain Native Stereo. Description separately discusses synchronized sequential and experimental native-fix modes. |
| Runtime | OpenXR | OpenVR | Do not restore the full profile as an isolated lighting test. |
| Startup Native Stereo rule | `scripts/main.lua:81` enables `VR_NativeStereoFix` if mode is 0 | Off; no original scripts active | Strong rendering-path difference worth testing outside Portal first. |
| Startup synchronized rule | `main.lua:83` enables Ghosting Fix if mode is 1 | Off, native mode | Ghosting Fix is not an equivalent toggle for the current mode. |
| Volumetric fog | `scripts/config/config.lua:101`: false; `main.lua:750–758` writes `r.VolumetricFog=0` | No matching override in current Engine.ini; original script absent | Best small, isolated visual test. Current live CVar still needs readback. |
| Flicker fixer | `scripts/libs/flicker_fixer.lua` creates a 64x64 SceneCaptureComponent2D; default 5 s delay / 1 s duration; only triggers with Native Stereo + Native Stereo Fix | Absent | A workaround coupled to native-fix rendering, not an exposure correction. Do not copy it alone and expect it to run. |
| Controller aim/decoupled pitch | Controller aim and movement orientation 3; decoupled pitch forced on | Portal-owned camera, aim 0, decoupled pitch off | These must remain separated from rendering fixes. Restoring the script would conflict with portal camera ownership. |

The current saved game settings have ray-traced shadows/reflections/AO off,
upscaling None, frame generation Off, ray reconstruction false, HDR output off.
Current UEVR already has Disable HDR Compositing, Disable HZB Occlusion and
Disable Instance Culling enabled. These are saved values, not independent live
CVar measurements. Do not repeat these toggles as if they were untested defaults.

## Why Native Stereo Fix needs real portal integration

In `External/UEVR-6DOF-Window/src/mods/vr/FFakeStereoRenderingHook.cpp`:

- Around line 3150, the same-pass option changes a secondary view's pass to
  primary and temporarily changes visible view-family count during construction.
- Around lines 3310–3436, the native-fix render path renders one view, switches
  the output target, swaps the views and renders again. It explicitly decrements
  the scene frame count to correct right-eye motion vectors.
- In `src/mods/vr/D3D12Component.cpp`, the ordinary runtime path uses
  `m_scene_capture_tex` as the right-eye source under Native Stereo Fix.
- `src/mods/portal/PortalFrame.cpp` currently rejects Native Stereo Fix and both
  AFR variants. PortalOutput currently assumes a complete double-wide scene
  texture. Removing the rejection alone would not implement the different
  right-eye acquisition path or establish frame identity correctness.

These are source-level reasons to investigate the mode, not proof that it fixes
this run's lighting. Exposure, fog and other game stereo effects remain possible
contributors until isolated by a live comparison.

## Controlled test order

Keep 1280x720 per eye, 2560x736 capture and 60 fps fixed. Use a stationary indoor
scene containing both the bright and dark regions that differ between eyes.
Capture the actual Windows SBS source and inspect each eye on Vision Pro.
Headset screen recording is left-eye-only per Josh and cannot judge eye matching.

1. **Fog only, current Portal mode.** Read and record the live `r.VolumetricFog`
   value. Temporarily set it to 0 using the engine CVar interface. Compare the
   same scene before/after, then restore the recorded value. Judge broad haze,
   lighting difference, flicker and frame cadence separately. Do not import the
   original motion-controller scripts for this test.
2. **Native Stereo Fix reference, Portal Output off.** Preserve the current
   profile. Keep OpenVR and the same scene/resolution. Compare plain Native
   Stereo against Native Stereo Fix in VRto3D, noting the same-pass setting.
   This reference test cannot be done through current direct portal output.
   If it makes no improvement, restore the baseline and investigate the next
   individual rendering effect rather than porting the mode speculatively.
3. **Only if native-fix improves matching:** implement a dedicated supported
   Portal mode that captures the correct two targets, preserves each eye's
   off-axis view/projection and exact frame association, and retains the GPU
   lifetime fixes, UI composition and metadata contract. Verify both rendered
   eyes belong to the same latched portal state before advertising success.
4. **Only if flicker remains in that mode:** assess the original tiny-scene-
   capture workaround independently. Its extra rendering and temporal effects
   need measurement; do not restore unrelated first-person/controller hooks.

If fog changes only haze and native-fix does not improve matching, isolate
exposure/postprocessing, screen-space effects and shadow rendering one at a
time, with readback of actual game CVars and a baseline restoration between
tests. No specific value for those unmeasured effects is prescribed yet.

This investigation changed no live profile, game settings, scripts or renderer
code. The first new live test still requires Josh's visual comparison.
