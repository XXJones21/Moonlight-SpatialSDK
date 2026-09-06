# Mac onboarding: Moonlight 6DOF visionOS portal

## Read this first

Continue branch **moonlight-6dof-vision**. Phases **0–6 were authored on Windows**
as seven source checkpoints. No application builds, automated test runs,
simulator/headset runs, UEVR injection, Sunshine capture validation or latency
measurements were performed. Source review found and corrected integration
issues, but this is **not a known-working build**. Begin with compilation and
functional validation; phase 7 latency tuning comes afterward.

The user's intent is a room-fixed, movable/resizable stereo portal on Vision Pro
with the Moonlight-SpatialSDK connection/settings/controls experience as close
as practical. One PC, one game and one gamepad. No wall pinning, room mesh,
reflections backend, curved screen or motion-controller emulation in this scope.

Start by reading these repository documents:

1. `docs/superpowers/specs/2026-09-05-moonlight-6dof-vision-design.md`
2. `docs/superpowers/plans/2026-09-05-moonlight-6dof-vision.md`
3. `Documentation/visionOS-6DOF/Execution.md`
4. `Quest-UI-UX-Parity.md` and `Feature-parity.md` alongside this guide
5. `Coordinate-contract.md`, `Protocol-v1.md`, `Frame-metadata-v1.md`
6. `PC-portal-pipeline.md`, `Moonlight-client.md`, `Closed-loop.md`

The original plan's intermediate verification gates were explicitly deferred by
the user. They remain outstanding; don't infer a pass from a source commit.

## Checkpoints and transfer

| Phase | Commit | Scope |
|---|---|---|
| 0 | `1de4b67` | Dependency pins, setup/handoff baseline, shared Xcode scheme |
| 1 | `0b7f02d` | PortalCore geometry/protocol, Windows relay, synthetic sender/fixtures |
| 2 | `7008f65` | UEVR off-axis hooks, D3D11/D3D12 direct SBS, metadata patch |
| 3 | `964b7e8` | Mixed-space portal, ARKit tracking, physical gestures, preview |
| 4 | `8318632` | Moonlight imports, dependency wiring, connection UI, video/audio/input |
| 5 | `730687f` | Frame identity gate/crop, tracked geometry loop, session lifecycle |
| 6 | Branch's final phase-6 commit | Parity controls, recovery, effects, capability UI, this guide |

Phase 4 also incorporates the reviewed D3D12 partial-resource creation fix into
the reproducible PC patch. The final external source commit is
`562dbe3d0ae389d3616d1db62f025c43cb5cf1f1`; its patch manifest is authoritative.

No remote push was performed during implementation. Transfer these local commits
to the Mac through the user's chosen Git remote or a Git bundle before expecting
the branch there to contain the work. Preserve unrelated local changes to
`Documentation/Quality-of-Life-Improvements.md`,
`Documentation/multi-display-implementation-plan.md` and `MyApplication/`.

## Restore and build on Mac

From the repository root, after transferring the commits:

```sh
git switch moonlight-6dof-vision
python3 tools/visionos-portal/bootstrap_apple_dependencies.py
open Moonlight-6Dof-Vision/Moonlight-6Dof-Vision.xcodeproj
```

The bootstrap restores pinned OpenSSL/Opus binaries and headers into ignored
`Moonlight-6Dof-Vision/Dependencies`. It clones the donor if needed. Imported
Moonlight/common-c source is already tracked in the application; do not add the
donor's entire Xcode target. Review the restored SHA inventory and license notices.
The source donor is `RikuKunMS2/moonlight-ios-vision` at
`fb349830ac980ab73dbd653b5b9c813c3b249198`.

The supplied project retains its **visionOS 27.0 deployment target** and current
project format. Use a compatible Xcode/visionOS SDK or deliberately adjust the
deployment target after API review. Update signing team locally as needed.
The shared scheme is `Moonlight-6Dof-Vision`. App sources use the existing
filesystem-synchronized group. Both target configurations include
`PortalDependencies.xcconfig`, the Objective-C bridging/prefix headers and native
link dependencies. Imported non-source notices/manifests are excluded from
automatic resource membership to avoid duplicate output filenames.

First build without claiming device readiness:

```sh
xcodebuild -project Moonlight-6Dof-Vision/Moonlight-6Dof-Vision.xcodeproj \
  -scheme Moonlight-6Dof-Vision -destination 'generic/platform=visionOS Simulator' \
  -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO build
```

Resolve compiler/linker/SDK issues first, then build for a signed physical Vision
Pro. Pay particular attention to Swift/Objective-C generated types, RealityKit
gesture/material APIs, Metal parameter bindings, public CVMetalTextureCache pixel
formats, framework slices and AVAudioEngine channel layouts. Simulator behavior
does not establish device tracking, stereo eye assignment or hardware decoding.

## Architecture and files

```text
AVP WorldTrackingProvider + portal physical geometry
  -> P6DV/P6DR UDP -> PortalHost Windows relay
  -> UEVR immutable engine frame -> off-axis Native Stereo
  -> direct GPU SBS window + per-eye P6FM strips
  -> Sunshine capture/encode -> Moonlight common-c
  -> VideoToolbox -> decoded identity gate -> Metal strip crop
  -> DrawableQueue -> camera-index RealityKit material
```

- `PortalCore`: portable C++23 geometry, state acceptance, reset/status protocol,
  frame metadata contract. Tests and independent fixtures are authored, unrun.
- `PortalHost`: Windows-only relay, exclusive UDP bind, configured client IPv4,
  ordered reset retries, freshest motion, OpenTrack compatibility output.
- `Documentation/visionOS-6DOF/patches`: complete reproducible UEVR delta and pins.
- App `Portal/PortalSceneController.swift`: scene, ARKit lifecycle, placement,
  gestures, calibration and explicit recovery Recenter.
- `Portal/PortalSessionCoordinator.swift`: shared connection, sender, gate,
  current texture installation, suppression and recovery.
- `Portal/PortalFrameGate.swift`: read both decoded eye tags, CRC and ID checks.
- `Portal/PortalTransport.swift`, `PortalState.swift`, `PortalStatusParser.swift`:
  queue-owned Network socket and explicit scalar LE protocol serialization.
- `Connection/`: launcher, pairing, settings and capabilities.
- `Streaming/`: Swift session, Objective-C adapter, Keychain, gamepad and audio.
- `ThirdParty/Moonlight/Video`: adapted VT decoder, Metal conversion/crop shaders.
- `AppModel.swift`, `ImmersiveView.swift`: lifetime ownership, shelf/recovery UI,
  effects and audio positioning; the Settings window has no teardown callback.

## PC bench preparation

The Mac cannot build/inject the Windows UEVR backend. Keep a Windows PC available.
The current Windows machine already has the authored external checkout. For a
fresh machine, restore source with:

```sh
python tools/visionos-portal/bootstrap_uevr.py --clone
```

This applies/stages the manifest's source tree; it does not fetch the gated UESDK,
install runtime services or build. Obtain the required UESDK access, initialize
the needed submodules, regenerate/inspect CMake from cmake.toml, and build the
fork following its own instructions with an absolute
`-DPORTAL_CORE_SOURCE_DIR=<this-repo>/PortalCore`. The build pins normalized hashes
of the shared source. Deliberate PortalCore changes require updating those pins
and re-exporting the external patch. UEVR's own licensing terms apply.

Use **OpenVR + ordinary Native Stereo**, the game's normal gamepad camera, and
the fork's **Portal Output** setting. OpenXR, AFR and incompatible camera/roomscale
modes fail closed. SteamVR/OpenVR and the selected driver still supply lifecycle;
the capture source is the direct pre-compositor window. VRto3D's projection
preservation is unproven and is not assumed by this route.

Start SDR HEVC, stereo audio, **1280×720 per eye at 60 fps requested**:

- PC exporter eye dimensions: 1280×720.
- Actual SBS display/capture/encode dimensions: **2560×736**.
- Benchmark alternative: 1920×1080 per eye -> **3840×1096**, 60 fps, 50 Mbps.
- The extra 16 rows are metadata; do not crop them in Sunshine.
- Set a matching physical/virtual display mode and exporter desktop X/Y origin.
  Avoid scaling/stretching. Actual mode availability and capture remain unproven.
- Native direct output currently supports single-sample RGBA8/BGRA8 only.
  HDR/FP16/MSAA/array source formats fail closed; UI HDR support is not proof of
  an end-to-end HDR portal source.
- Scene HUD remains; separate runtime/Slate UI is not composited. Configure from
  the desktop before enabling Portal Output. Games requiring continuous separate
  UI need additional composition before qualification.

Build `PortalHost` on Windows, then run `portal_host --peer <AVP-IPv4>` with the
appropriate path to the executable. Its pose ingress defaults to UDP 4243,
UEVR to loopback 4244, OpenTrack to loopback 4242. Allow the necessary LAN port
for the app; keep native Moonlight/Sunshine pairing/stream ports configured.
The current relay client allowlist is numeric IPv4; begin with IPv4 host entry.

## Functional validation order

1. Compile Apple, PortalCore/PortalHost and UEVR. Fix source integration errors
   without changing the mathematical/wire contracts casually.
2. Run the existing portable tests and Python sender tests. Add Swift fixture
   parity coverage/test-target wiring; no Swift test target was authored yet.
3. Validate the static stereo preview on Vision Pro: left/right labels, four
   corners, UV orientation, initial upright placement, move/yaw/corner resize,
   size reset preserving placement, and independently closable Settings window.
4. Validate plain Moonlight pairing/launch/video/audio/gamepad with the direct
   capture resolution, then inspect post-codec P6FM strips from both eyes.
   CRC/ID failure must suppress the frame; never bypass the gate to make it look
   functional. Use the PC diagnostics to isolate capture/codec from game hooks.
5. Prove PC off-axis projection and exact source-frame attribution. Test head
   translation/rotation, eye separation, world scale, gamepad movement, all
   portal corners, and mutations between left/right callbacks.
6. Close the loop: move/scale/yaw while streaming; ensure old revisions disappear.
   Exercise loss/reorder/duplicate resets, tracking loss, Recenter, network loss,
   disconnect, rapid reconnect, system dismissal and changing stream settings.
   Check input release and resource teardown each time.
7. Capture matching Quest/visionOS states for V01–V10 in the parity contract.
   Verify capability errors, keyboard/PIN flow, saved settings, shelf timing,
   dimming, lighting, audio position and route/interruption recovery.
8. Only after correctness, proceed to phase 7 with real latency instrumentation
   and per-game profiles. Do not treat GPU completion as headset presentation,
   and do not compare unsynchronized PC/AVP clocks directly.

Example portable commands, for later execution:

```sh
cmake -S PortalCore -B build/portal-core
cmake --build build/portal-core
ctest --test-dir build/portal-core --output-on-failure
python3 -m unittest discover -s tools/visionos-portal/tests -p 'test*.py'
```

## Important decisions to preserve

- Room-fixed portal, not continuously head-following; the follow-view Apple
  example informs initial placement/recenter only.
- Initial center 1 m horizontally forward and 0.1 m down; upright yaw, 0.7 m
  height. Width bounds 0.5–10 m; corner resize keeps center and aspect.
- Resize resets dimensions only. Recenter is separate.
- Head pose alone cannot identify an ARKit origin shift. Tracking recovery stays
  paused until explicit yaw-only Recenter from a current tracked pose.
- Nominal 64 mm eye separation and device-to-head offset are user calibration,
  not measured eye transforms. Session/epoch/revision changes invalidate imagery.
- Shelf order: Settings, Resize, Immersive, Disconnect. Immersive is the effects
  master and never closes the tracking space. All effect preferences start off.
- Reconnect uses the previous connection's parameters. Apply edits affect the
  next newly launched connection. Closing Settings leaves streaming alive.
- P6DV is 200-byte LE; P6DR is a 32-byte explicit epoch transition. P6FM is a
  44-byte tag repeated in both eye strips. Read the contracts before changes.
- Fresh status is necessary diagnostics but cannot label a video frame. Only
  decoded metadata tied to the active texture can reveal the image.
- No latency, comfort, game compatibility or visual-parity pass is claimed yet.
