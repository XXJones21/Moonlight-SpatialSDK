# Mac onboarding: Moonlight 6DOF visionOS portal

## Read this first

**Desktop-first validation (2026-09-06):** Immersive Options now has a persisted
**6DoF Window** toggle, off by default even on installations with the old
always-on metadata settings. Off requests ordinary Moonlight dimensions
(720p = 1280×720; 1440p = 2560×1440), displays the complete image in both eyes,
and does not require the portal relay, frame tags or crop rows. The panel can
still be placed and resized in the room. On restores the strict tracked SBS
portal path, including matching frame metadata and the 16-row crop.
Switching modes restarts an active connection with the latest saved settings and
the new stream layout. It does not enable the PC's UEVR Portal Output setting.

For the first headset check, leave 6DoF Window off and select the desired codec
and resolution in Configure Stream. Valid selections save automatically; the
view explicitly reports selections that cannot be saved. Both **Connect** and
**Reconnect** use the latest saved settings. Reconnect retains the previous
server/app destination, not a cached settings snapshot. An already-running stream
keeps its negotiated settings until reconnected. This supersedes the original
handoff's manual Apply and previous-settings Reconnect behavior after device
logs showed Auto still being requested during intended H.264 tests.
Once ordinary streaming is verified, configure the supported PC game/exporter
and matching capture mode before turning 6DoF Window on.

The last available run log showed video queue overflows and an audio-session
lookup error, without a definitive crash backtrace. Source investigation found
out-of-bounds multi-NAL copying, inconsistent picture-buffer ownership and
temporary parameter-set pointers in the imported decoder; these paths were
corrected. Decoder start/stop now serialize display-link access on the main
thread and report VideoToolbox failures through unified logging. These fixes
still require headset playback/reconnect verification.

Focused Swift checks (ordinary/SBS dimensions, Annex B conversion, and strict
portal frame rejection) run on the Mac without launching the app:

```sh
DEVELOPER_DIR=/Users/jones/Downloads/Xcode-beta.app/Contents/Developer \
  bash tools/visionos-portal/tests/run_swift_tests.sh
```

The shared Run scheme disables the debugger, Main Thread Checker, performance
checker, GPU validation and frame capture, matching the Hearth Vision workaround.
OpenSSL is embedded and signed from the selected platform framework directly;
the pinned donor's outer XCFramework signature is invalid. Pinned source binaries
are retained; Xcode signs the embedded framework using the app's identity.

**Mac build update (2026-09-06):** Pinned Apple dependencies were restored, and
the Debug app built successfully through Xcode for **Any visionOS Device
(arm64)** using the visionOS 27.0 SDK. The first compilation required a UIKit
import in the Swift bridging header, separate declarations for two observable
properties, removal of a donor-only window-audio helper call (the app's
`CoreAudioRenderer` owns audio), and the nested Opus header search path.
Compiler warnings remain, including concurrency and pointer-lifetime warnings.
No app launch, automated tests, headset validation or PC validation was performed.

**Simulator build limitation:** The pinned donor's `visionOS-Sim/libopus.a`
contains objects built for visionOS devices; the simulator linker rejects it.
The simulator build command below remains blocked until a correctly built
simulator Opus library is supplied. Use the generic device build destination
for the current build baseline; dependency pins and binaries were not changed.

The following records the original Windows handoff status:

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
- Valid stream-setting edits save automatically. Connect and Reconnect use the
  latest saved settings; active streams change only when reconnected. Closing
  Settings leaves streaming alive.
- P6DV is 200-byte LE; P6DR is a 32-byte explicit epoch transition. P6FM is a
  44-byte tag repeated in both eye strips. Read the contracts before changes.
- Fresh status is necessary diagnostics but cannot label a video frame. Only
  decoded metadata tied to the active texture can reveal the image.
- No latency, comfort, game compatibility or visual-parity pass is claimed yet.

## Device crash diagnosis and export (2026-09-06)

The 08:52 HEVC and 08:57 H.264 crash dumps both identify `EXC_BAD_ACCESS`, null
address, on `portal.audio` in `AVAudioPCMBuffer initWithPCMFormat:frameCapacity:`.
The request's `surroundAudioInfo=104792072` means eight channels. The old
`initStandardFormatWithSampleRate:channels:` initializer returns nil above two
channels. The app passed that nil format to the buffer initializer. The user
confirmed switching 7.1 to Stereo stops the crash while the grey panel remains.
These are separate failures: video format-description errors remain unresolved.

`PortalAudioFormat` now supplies explicit mono/stereo/WAVE 5.1/WAVE 7.1 layouts,
matching common-c's PCM order (FL FR C LFE RL RR SL SR). Audio submission requires
successful session/graph setup and valid format/buffer storage. Verify surround
playback on device; passing format tests alone does not validate the audio route.

In the connection window, use **Prepare Diagnostics**, then **Share Diagnostics**.
Relaunch after a crash and prepare a fresh report. Local lifecycle breadcrumbs
survive launch, cover audio graph/route/interruption events, stream start/teardown,
and immersive lifecycle changes. Storage retains four 256 KiB log segments and
up to five MetricKit JSON reports, each limited to 2 MiB. One export snapshot is
replaced on each preparation. Logs omit request URLs, pairing keys and packets.
No automatic upload or custom signal handler is installed. MetricKit reports
include system crash/hang information when delivered, which may be delayed or
absent; the export is not a guaranteed immediate full `.ips` dump.

For the system `.ips` files, use the paired headset identifier from
`xcrun devicectl list devices`, then:

```sh
DEVELOPER_DIR=/Users/jones/Downloads/Xcode-beta.app/Contents/Developer \
python3 tools/visionos-portal/collect_device_crashes.py \
  --device B2490A15-04F7-5D2E-BF63-503312B3F804 \
  --output /tmp/moonlight-crashes --limit 5
```

This reads the device's `systemCrashLogs` domain, including `Retired/`, and copies
only Moonlight reports. It does not launch the app, attach LLDB, or remove device
logs. Keep the scheme debugger disabled. Preserve the matching build's dSYM for
symbolication. Apple documents visionOS diagnostic support in
[MetricKit](https://developer.apple.com/documentation/metrickit).

Run the Mac-hosted format/buffer and diagnostics persistence/rotation tests with:

```sh
DEVELOPER_DIR=/Users/jones/Downloads/Xcode-beta.app/Contents/Developer \
bash tools/visionos-portal/tests/run_audio_diagnostics_tests.sh
```

The plain-video material selection also applies before the first decoded frame:
startup preview and idle mode changes explicitly select mono versus stereo.
With **6DoF Window off**, the preview is one image and the plane uses an
`UnlitMaterial` with the entire video texture for both eyes. It never loads the
SBS eye shader on that path. The stream publishes its initial texture before
native connection startup so a decoder failure cannot leave the old stereo
material installed. The surface still waits for a presented frame before
revealing stream content. Diagnostics record the selected plane material.

### Separate basic and 6DoF panel entities

Each connection now spawns a fresh entity beneath `MoonlightPanelHost`:

- **6DoF Window off:** `BasicMoonlightPanel` applies the complete texture directly
  to its plane through `UnlitMaterial`. It has no SBS shader dependency.
- **6DoF Window on:** `SixDoFPanel` owns the existing `/Root/SBSMaterial` eye shader.
  The coordinator continues to require matching host status and frame metadata.

The two entities have independent surfaces, backdrops, handles, shelves and
lighting entities. `MoonlightPanelEntity` shares only their construction and
geometry; the old panel is detached rather than reused with a different material.
The stable parent preserves world placement. The controls attachment moves to the
new shelf, and recovery/tracking attachments remain on the stable parent. Texture
installations check both the current panel and installation generation so a late
SBS shader load cannot overwrite a newer panel. Preview selects the same entity
mode without opening a connection. Normal stream decoding/transport stays shared.

The 16:12–16:13 UTC diagnostic export confirmed stereo audio started and both
sessions disconnected cleanly; no MetricKit crash payload was present. It logged
full-texture material selection but did not establish successful frame decoding.
New diagnostics identify the mounted entity, native connection stage names, and
the first decoded frame whose GPU copy completes. GPU completion does not prove
headset presentation. Neither this entity split nor a successful build proves
the remaining grey-video issue is resolved.

Run the real RealityKit hierarchy and texture-binding tests on the Mac:

```sh
DEVELOPER_DIR=/Users/jones/Downloads/Xcode-beta.app/Contents/Developer \
bash tools/visionos-portal/tests/run_panel_tests.sh
```

The test needs access to Mac graphics services; it does not use a simulator or
launch the headset app. It checks fresh panel instances, old-panel detachment,
control-bar transfer, preserved placement, direct full-texture binding, and
rejection of stale texture callbacks. Device follow-up: connect with 6DoF off,
exercise all handles/bar actions, then disconnect or toggle/reconnect and check
that only the selected panel appears. Export diagnostics after each attempt.

Apple's [Rendering stereoscopic video with RealityKit](https://developer.apple.com/documentation/realitykit/rendering-stereoscopic-video-with-realitykit)
sample was reviewed during the split. It uses `AVSampleBufferVideoRenderer` with
`VideoPlayerComponent` in Shared Space, an explicit synchronizer, renderer
readiness/backpressure, and renderer-recommended pixel-buffer attributes. It
converts SBS input into two separately tagged eye buffers. That is an alternative
presentation pipeline, not a missing requirement for a mono `UnlitMaterial`.
Our basic entity must not split/tag eyes. The sample can inform a later standard
renderer comparison that bypasses our custom Metal copy; this change retains the
existing Moonlight decoder. H.264 parameter-set validation fails upstream of
either presentation approach, so swapping entities cannot by itself correct it.

### Handshake succeeded; native/Swift buffer ABI mismatch (2026-09-06)

`Moonlight-diagnostics 2.txt` records Auto at 16:24:35 UTC, H.264 at 16:25:18,
and HEVC at 16:25:48. Each creates `BasicMoonlightPanel`, requests 1280×720 SDR
with stereo audio and 6DoF false, completes RTSP and all stream-establishment
stages, and later disconnects cleanly. None records the first GPU-completed video
frame. Xcode console additionally shows HEVC format/session failures. Sunshine
sending correctly sized frames does not establish client decoding success.

A concrete client-side defect was found in `DrawableVideoDecoder.swift`'s global
constant copies, which shadowed the values already imported from `Limelight.h`:

| Buffer | Native C | Removed Swift copy |
| --- | --- | --- |
| SPS | 1 | 2 |
| PPS | 2 | 3 |
| VPS | 3 | 1 |

For H.264 the native PPS was mistaken for an SPS and cleared the stored SPS,
leaving only one parameter set. For HEVC the native SPS was mistaken for a VPS
and cleared the actual VPS, leaving SPS/PPS without VPS. This explains the H.264
“need at least 2 parameter sets” error and incomplete HEVC configuration.
All copied native codec/frame/buffer/result constants were removed. Swift now
uses the bridged C definitions. `run_decoder_abi_tests.sh` compiles the actual
decoder constant declarations with native C getter functions: it failed with the
old shadow definitions and passes with the fix. Device playback still requires
validation; build success alone is insufficient.

Basic mode now omits the portal tracking/recenter attachment and calibration
entry. Basic world tracking still places the panel and samples the audio listener,
but tracking recovery no longer waits for a 6DoF recenter acknowledgement. 6DoF
mode retains its explicit recovery gate. Exported diagnostics now include bounded,
once-per-decoder milestones for native parameter types/lengths, accumulated set
count, first assembled frame, VT session creation/failure, first decoded image,
and first GPU completion. They contain no compressed packet payloads.

```sh
DEVELOPER_DIR=/Users/jones/Downloads/Xcode-beta.app/Contents/Developer \
bash tools/visionos-portal/tests/run_decoder_abi_tests.sh
```

### Distinguish stream mode from immersive effects

A subsequent device log explicitly spawned `SixDoFPanel`, requested
`6DoF=true` at 5120×1456 H.264, and installed the eye shader. That run was not a
BasicMoonlightPanel run: its 2560×1440 per-eye setting became SBS plus metadata.
It received audio but terminated with -100 and no video traffic, before decoding.
The native above-4K H.264 warning is relevant but does not prove the packet-loss
cause. Do not interpret that run as evidence that basic mode loads the eye shader
or that the corrected parameter-set ABI still fails.

The bar's effects master is now labeled **Effects**. It controls dimming, lighting
and spatial audio; it does not change the independently persisted **6DoF Window**
setting. The same 6DoF toggle is visible beside Connect and in Immersive Options,
with selected/active mode labels. Toggle state is published synchronously before
asynchronous restart, Connect/Reconnect wait during that transition, and launch
and toggle changes are logged. `Portal lifecycle` remains the shared diagnostic
prefix used by both modes; look at the named spawned entity and `6DoF=` field to
identify the actual mode.

### Working basic-streaming checkpoint

The user confirmed on the physical Vision Pro that normal Moonlight video and
audio work with **6DoF Window off**, after correcting the native/Swift buffer
constant mismatch. The later SixDoFPanel/no-video run was confirmed to have the
6DoF toggle enabled accidentally. The latest mode-clarity build compiled
successfully; its UI changes still await device confirmation. This checkpoint
establishes basic streaming, not validated 6DoF gameplay or surround playback.
