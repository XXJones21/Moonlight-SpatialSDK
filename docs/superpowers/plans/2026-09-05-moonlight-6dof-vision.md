# Moonlight 6DOF visionOS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Use superpowers:subagent-driven-development only if parallel agent execution is requested. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a playable, room-anchored stereoscopic UEVR portal on Vision Pro using Moonlight, with the Quest app's panel and streaming functionality implemented natively.

**Architecture:** Keep the new visionOS project as the app shell and import proven Moonlight components behind an adapter. Feed ARKit pose and portal geometry to a Windows bridge, render synchronized off-axis eye views in the UEVR fork, and stream full SBS back onto a RealityKit plane. Validate VRto3D as the first runtime/capture bridge and use direct SBS export if it alters the portal projection.

**Tech Stack:** SwiftUI, RealityKit, ARKit, VideoToolbox, Metal, Network, GameController, Moonlight protocol/core, Sunshine, Windows C++23/MSVC/CMake, UEVR, SteamVR/VRto3D.

**Spec:** [Agreed architecture and constraints](../specs/2026-09-05-moonlight-6dof-vision-design.md).

**Required UI/UX reference:** [Quest UI and UX parity contract](../../../Documentation/visionOS-6DOF/Quest-UI-UX-Parity.md). This source-backed follow-up defines the active screens, gestures, settings, and acceptance IDs. Implement the familiar UI during phases 3–5; phase 6 is its completion/comparison gate, not the first time it is designed.

## Global constraints

- Work on `moonlight-6dof-vision`; fetched baseline is `2d70657`. Preserve unrelated local changes and the Quest application.
- Keep the supplied project deployment target at visionOS 27.0 initially. Confirm the actual Mac SDK and device OS before the first Apple build; lower the target only after checking every used API and imported dependency.
- One flat portal, one PC, one game, one gamepad for the first working slice. Implement the remaining parity features before claiming full completion.
- Current scope excludes wall pinning/snapping, room-anchor persistence, and room-mesh reflection implementation. Omit Snap and any wall-sensing setup. Preserve free movement and scaling without scene-understanding authorization.
- Mixed immersion and room-fixed geometry. Use Apple follow-view behavior only for optional controls or explicit recenter previews.
- Use HEVC SDR, 3840 x 1080 SBS at 60 fps and 50 Mbps as the explicit bench profile. User-facing defaults match Quest: 1280 x 720 per eye, 60 fps, auto codec, Stereo, HDR/full-range off, automatic bitrate. Keep the benchmark and saved user defaults separate.
- Initial portal is 0.7 m high, width from one eye's content aspect, 1 m horizontally ahead and 0.1 m below the viewer, upright. Corner resize clamps physical width to 0.5–10 m; Resize resets size only. Preserve the source's connection/settings layout and shelf action semantics.
- Both eyes use one frame snapshot. Device-to-eye calibration, portal orientation, Unreal world scale, and runtime coordinate conversion are explicit.
- No claim of a working visionOS build from Windows checks alone. Mac simulator validation does not prove device stereo, tracking, or comfort.
- Each task ends with its specified evidence, review of the diff, and a narrowly scoped commit during execution. No global `git add .` in this checkout.
- No installations, source imports, application changes, or dependency forks have been performed by writing this plan.

## Phases and completion gates

| Phase | Deliverable | Where it can be validated |
|---|---|---|
| 0 | Reproducible dependencies and unchanged baseline builds | Windows + Mac |
| 1 | Tested portal math and shared state protocol | Windows |
| 2 | PC-only off-axis rendering, pose input, and capturable SBS | Windows |
| 3 | Mixed-space visionOS portal with static stereo and ARKit | Mac + Vision Pro; authoring on Windows |
| 4 | Moonlight video, audio, pairing, and gamepad in the new app | Windows host + Mac + Vision Pro |
| 5 | Closed-loop 6DOF portal and synchronized manipulation | Windows host + Vision Pro |
| 6 | SpatialSDK feature parity and recovery | Mac + Vision Pro + Windows host |
| 7 | Latency tuning, game compatibility, and release handoff | Both platforms and hardware |

Execution order: 0 -> 1 -> 2, with the first Mac baseline check performed as soon as a Mac is available. Phases 3 and 4 can be authored independently of PC rendering, but both must pass before phase 5. Do not accumulate a large uncompiled Swift import while waiting for a Mac. Until Mac access is available, complete the portable/PC gates and prepare small reviewable Apple changes with validation marked pending.

## Source layout and ownership

Path aliases below are exact roots, not names to substitute freely:

| Alias | Root | Ownership |
|---|---|---|
| `APP` | `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision/` | visionOS source |
| `PROJECT` | `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision.xcodeproj/` | Xcode target/scheme |
| `CORE` | `PortalCore/` | portable C++ projection, protocol, replay tests |
| `HOST` | `PortalHost/` | Windows pose relay, local UEVR connection, diagnostics |
| `TOOLS` | `tools/visionos-portal/` | scripts, fixture generation, result checks |
| `EVIDENCE` | `Documentation/visionOS-6DOF/` | setup, pinned versions, results, game profiles |
| `UEVR` | `External/UEVR-6DOF-Window/` | separately versioned fork checkout |
| `ML` | `External/moonlight-ios-vision/` | pinned reference checkout, not linked wholesale |

Ignore `External/`, build outputs, and host-local configuration in this repo. Record exact upstream URLs/SHAs and copied file lists under `EVIDENCE`. Commit UEVR source changes in its own checkout; record its resulting SHA in this repo. Keep an exported patch if a writable fork remote is not yet configured. Creating/publishing external repositories is not a prerequisite to local implementation.

## Phase 0 — Establish reproducible baselines

### Task 0.1: Pin dependencies, build UEVR, and identify test hardware

**Files:** create `EVIDENCE/Dependencies.md`, `EVIDENCE/Hardware-and-builds.md`, `EVIDENCE/Compatibility.md`; modify root `.gitignore` only for new generated/reference paths.

**Produces:** pinned source checkouts, toolchain inventory, one unchanged UEVR build, and a selected installed game/profile for repeatable tests.

- [ ] Record UEVR SHA `fb31341e860b15e116a15123820c95f044ff0a0f` and Moonlight SHA `fb349830ac980ab73dbd653b5b9c813c3b249198`; clone each reference at that SHA under its alias root. Record VRto3D, Sunshine, and SteamVR versions when installed; do not silently follow their moving default branches.
- [ ] Check EpicGames/UESDK access and recursive submodules before planning UEVR edits. Record missing access as a build dependency; continue standalone `CORE` work if it is unavailable.
- [ ] Inventory Windows GPU/driver, VS C++23 toolchain, CMake, installed Unreal games, and host networking. `cl` missing from ordinary PowerShell does not establish that Visual Studio is absent; use a VS developer shell. Confirm Vision Pro model/OS, available Mac/Xcode SDK, signing setup, and connected gamepad during the first hardware session.
- [ ] Build the unchanged fork in a VS 2022 x64 developer shell:

```powershell
git -C External/UEVR-6DOF-Window submodule update --init --recursive
cmake -S External/UEVR-6DOF-Window -B External/UEVR-6DOF-Window/build -G "Visual Studio 17 2022" -A x64
cmake --build External/UEVR-6DOF-Window/build --config Release --target uevr
```

- [ ] Start with an installed D3D11 game that runs reliably in the fork's Native Stereo mode. If only D3D12 games are available, record that constraint and run the D3D12 checks from task 2.3 at the first PC gate. Record game build, renderer, runtime, UEVR method, scene/save location, and profile settings; no title is assumed installed by this plan.
- [ ] Read actual dependency license files, retain source notices for copied Moonlight code/assets, and document submodule/build requirements. Do not repeat unverified licensing claims from the downloaded handoff.
- [ ] Review and commit only inventory/docs/ignore changes: `docs: pin portal dependencies and baseline setup`.

**Gate:** baseline UEVR build succeeds, or its exact external blocker is recorded while portable tasks proceed. An injection test is recorded separately from compilation.

### Task 0.2: Establish the Apple project build boundary

**Files:** inspect existing `APP/*.swift`, `APP/Info.plist`, `PROJECT/project.pbxproj`; create `PROJECT/xcshareddata/xcschemes/Moonlight-6Dof-Vision.xcscheme` if the scheme is not shared; create `EVIDENCE/Apple-build.md`.

- [ ] On Mac, inspect available SDKs and schemes before changing the template:

```bash
xcodebuild -showsdks
xcodebuild -list -project Moonlight-6Dof-Vision/Moonlight-6Dof-Vision.xcodeproj
xcodebuild -project Moonlight-6Dof-Vision/Moonlight-6Dof-Vision.xcodeproj -scheme Moonlight-6Dof-Vision -configuration Debug -destination 'generic/platform=visionOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

- [ ] Verify the unchanged template launches in the simulator and on device with the user's signing team. Record SDK/build versions and deployment compatibility; a Windows source inspection is not this check.
- [ ] Add shared scheme configuration without copying user-specific `xcuserdata`. Stop tracking generated user-state files only in a narrowly scoped housekeeping change that preserves their local copies.
- [ ] Record the commands and output location, then commit build configuration/doc changes: `build: establish shared visionOS build scheme`.

**Gate:** unchanged app build/launch evidence exists before substantial Moonlight integration. This gate may remain pending while Windows phases 1–2 proceed.

## Phase 1 — Specify and prove geometry and state delivery

### Task 1.1: Implement portable portal geometry with independent correctness tests

**Files:** create `CORE/CMakeLists.txt`, `CORE/include/portal/Geometry.hpp`, `CORE/src/Geometry.cpp`, `CORE/tests/GeometryTests.cpp`, `CORE/fixtures/geometry.json`; create `EVIDENCE/Coordinate-contract.md`.

**Interface contract:** `Vec3 { double x,y,z; }`, `Quaternion { double x,y,z,w; }`, `RigidTransform { Vec3 position; Quaternion orientation; }`, `PortalGeometry { RigidTransform worldFromPortal; double widthMeters,heightMeters; uint64_t revision; }`, `EyeFrustum { double left,right,bottom,top; }`. `portalFrustum(const PortalGeometry&, Vec3 worldEye)` returns `std::optional<EyeFrustum>`. The result consists of signed tangents, not a graphics-API matrix. `Mat4` is a column-major 16-double array; `toUnrealReversedZ(const EyeFrustum&, double nearUnits)` produces the matrix convention verified against the pinned UEVR path.

- [ ] Write a standalone CTest target named `portal_core_tests` and failing tests for a centered eye, translated eye, rotated portal, off-center eye outside the rectangle, and invalid eye-plane distance. Do not require UESDK to build these tests.
- [ ] Keep test assertions active in Release builds: add `/UNDEBUG` to the MSVC test target (`-UNDEBUG` for other compilers), or use a test assertion framework that does not compile checks out. Intentionally break a fixture once to prove the Release CTest run fails.
- [ ] Use an independent geometric oracle: projecting the portal's four corners must yield normalized corners (-1,-1), (+1,-1), (-1,+1), (+1,+1), with tolerance 1e-5. Test both eyes. Near=0.1, eye=(0,0,2), identity portal at origin, W=2.4/H=1.35 gives tangents L=-0.6/R=0.6/B=-0.3375/T=0.3375.

```cpp
PortalGeometry p{{{0,0,0},{0,0,0,1}}, 2.4, 1.35, 1};
auto f = portalFrustum(p, {0.2,0,2});
assert(f.has_value());
assert(std::abs(f->left + 0.7) < 1e-9);
assert(std::abs(f->right - 0.5) < 1e-9);
assert(!portalFrustum(p, {0,0,0.05}).has_value());
assert(!portalFrustum(p, {0,0,-1}).has_value());
```

- [ ] Implement inverse rigid-transform conversion from world eye to portal coordinates. Use local portal +Z toward the initial viewer. For local eye `(ex,ey,ez)`, divide `(-W/2-ex, W/2-ex, -H/2-ey, H/2-ey)` by `ez`. Reject `ez <= 0.10`, nonfinite values, invalid quaternions, or nonpositive dimensions.
- [ ] Derive window-aligned per-eye view rotation separately from eye positions. Convert near distance into Unreal units once using `world_to_meters`; validate the engine's forward-axis and reversed-Z matrix against the actual runtime implementation before calling it equivalent.
- [ ] Add fixtures for head roll/yaw affecting left/right eye positions, a rotated portal, world origin relocation, and eye separation changes. A common rigid transform applied to viewer and portal must leave their relative projections unchanged.
- [ ] Add application geometry cases in addition to the 2.4 x 1.35 m mathematical fixture: base 0.7 m height, upright placement despite head pitch/roll, 0.5/10 m width boundaries, and reset dimensions without changing center/yaw. The larger fixture is not the app startup default.

```powershell
cmake -S PortalCore -B PortalCore/build -G "Visual Studio 17 2022" -A x64
cmake --build PortalCore/build --config Release
ctest --test-dir PortalCore/build -C Release --output-on-failure
```

- [ ] Confirm failure before implementation and passing independent projection tests afterward. Save fixtures and commit: `feat: add tested portal geometry core`.

**Gate:** corner projection and coordinate-invariance tests pass without UEVR, Unity, or a headset.

### Task 1.2: Define full-state messages and implement a replayable host bridge

**Files:** create `CORE/include/portal/Protocol.hpp`, `CORE/src/Protocol.cpp`, `CORE/tests/ProtocolTests.cpp`, `CORE/fixtures/state-v1.bin`; create `HOST/CMakeLists.txt`, `HOST/src/main.cpp`, `HOST/src/StateRelay.cpp`, `HOST/src/OpenTrackAdapter.cpp`; create `TOOLS/send_pose.py`, `TOOLS/tests/test_packets.py`; create `EVIDENCE/Protocol-v1.md`.

**Consumes:** geometry types from 1.1. **Produces:** `StateSnapshot`, `decodeState(std::span<const std::byte>) -> std::optional<StateSnapshot>`, `encodeState(const StateSnapshot&) -> std::array<std::byte,200>`, and host executable `portal_host.exe`.

`StateSnapshot` stores `sessionID`, `trackingEpoch`, `sequence`, `geometryRevision`, `sampleTimeNs`, `targetTimeNs` (six uint64 fields), `trackingValid` (flag bit 0), `worldFromHead`, `worldFromPortal`, width, height, and eye separation. `worldFromHead` already includes client device-to-head calibration. Unknown flag bits are rejected in v1. Quaternion order is x,y,z,w.

Wire layout is little-endian, exactly 200 bytes, encoded field-by-field without native struct packing:

| Bytes | Contents |
|---|---|
| 0–7 | ASCII `P6DV`, uint16 version=1, uint16 length=200 |
| 8–55 | six uint64 fields in the order above |
| 56–63 | uint32 flags, uint32 reserved=0 |
| 64–119 | head position: 3 float64 meters; head quaternion: 4 float64 |
| 120–175 | portal position: 3 float64 meters; portal quaternion: 4 float64 |
| 176–199 | width, height, eye separation: 3 float64 meters |

- [ ] Write decoder tests for the golden packet and for truncation, oversized packets, wrong magic/version, NaN, invalid quaternion norm, stale sequence, and old session/epoch. Keep packet parsing separate from session-order acceptance.
- [ ] Generate the Python fixture independently and require byte-for-byte agreement with the C++ encoder:

```python
import struct
FORMAT = '<4sHH6QII17d'
assert struct.calcsize(FORMAT) == 200
values = (0,0,2, 0,0,0,1, 0,0,0, 0,0,0,1, 2.4,1.35,0.064)
packet = struct.pack(FORMAT, b'P6DV',1,200, 1,1,1,1,1000000000,1000000000, 1,0, *values)
assert len(packet) == 200
```

- [ ] Implement `portal_host --listen 4243 --uevr-port 4244 --opentrack-port 4242 --peer 127.0.0.1` for synthetic testing. At device integration, set `--peer` to the measured headset address from the hardware record. The host receives client snapshots, forwards the original validated snapshot to UEVR on loopback, and emits head poses as OpenTrack's six doubles on loopback. Keep a single latest snapshot; never queue stale motion for playback.
- [ ] Read the pinned VRto3D `OpenTrackThread` conversion and implement its inverse in `OpenTrackAdapter.cpp`; log test translations/rotations and compare SteamVR's received transform to the original. Do not guess yaw/pitch/roll order or signs.
- [ ] Use local receive time for the 250 ms stale threshold. Sender timestamps are correlation values until clocks are explicitly synchronized. Accept a new tracking epoch only through a reset/session transition, not because an arbitrary packet happens to arrive late.
- [ ] Define UEVR status as bounded newline JSON datagrams on the host's outgoing UDP socket: version, sessionID, trackingEpoch, acceptedSequence, geometryRevision, renderFrameID, trackingValid, outputMode, errorCode. Encode 64-bit IDs as decimal strings to avoid JSON numeric precision loss. Errors are `none`, `staleTracking`, `invalidGeometry`, `unsupportedRuntime`, or `outputUnavailable`.
- [ ] Forward UEVR status from the host's bound port 4243 to the currently accepted client endpoint. The Swift sender and status receiver share the same UDP connection. Send status at 10 Hz and immediately on errors/revision transitions; status never substitutes for decoded video frame identity.
- [ ] Add `send_pose.py --host 127.0.0.1 --port 4243 --pattern sweep --duration 10`, `--pattern static`, and `--pattern roll`. It uses the same full-state format and writes sent sequence/timestamps to CSV. Fixture tests run with `python -m unittest discover -s tools/visionos-portal/tests -v`.
- [ ] Verify replay, packet loss/reordering, independent fixture agreement, and clean socket shutdown; commit: `feat: add portal state protocol and Windows relay`.

**Gate:** fake tracking drives the PC bridge, malformed/stale input cannot mutate accepted state, and the runtime conversion round trip is measured.

## Phase 2 — Make the PC render a genuine portal texture

### Task 2.1: Establish the unmodified runtime and capture baseline

**Files:** create `EVIDENCE/PC-baseline.md`, `EVIDENCE/profiles/baseline.json`; use `TOOLS/send_pose.py` and `HOST` from phase 1.

- [ ] Install/configure SteamVR and the pinned VRto3D build, then run the unchanged UEVR fork in OpenVR mode with Native Stereo. First prove a static valid HMD, then enable synthetic OpenTrack input.
- [ ] Select full SBS output and a display mode matching 3840 x 1080. Disable tracking filters, additional stereo/convergence adjustment, and asynchronous reprojection where the pinned driver provides them; record exact configuration keys after reading that version's settings.
- [ ] Capture the selected output with Sunshine and open it in an existing Windows Moonlight client. Verify full width, no crop, eye order, and absence of stretch with distinct left/right labels and corner markers. Verify that capture targets the presentation display, not the game's mono desktop mirror.
- [ ] Sweep x/y/z and yaw/pitch/roll separately. Record the received runtime pose, eye separation, and capture output. Distinguish this normal masked-VR baseline from the portal-projection output in the next task.
- [ ] Save the working launch sequence, display configuration, rollback instructions, and game profile; commit: `docs: record PC stereo and tracking baseline`.

**Gate:** synthetic tracking reaches UEVR and both eyes can be streamed. Failure here is a bridge/setup issue, not a RealityKit issue.

### Task 2.2: Add coherent pose overrides and off-axis projection in UEVR

**Files in `UEVR`:** create `src/mods/portal/PortalSession.hpp/.cpp`, `src/mods/portal/PortalFrame.hpp/.cpp`; modify `src/mods/WindowMode.hpp/.cpp`, `src/mods/VR.cpp`, `src/mods/vr/FFakeStereoRenderingHook.cpp`, and relevant runtime pose-update integration in `src/mods/vr/runtimes/OpenVR.cpp`; update `cmake.toml` and generated build files through the repo's generation workflow. Consume `CORE` through an explicit CMake source path and pin its version.

**Interface:** `PortalSession::latest() -> std::optional<StateSnapshot>` receives on loopback 4244. `PortalFrame::latch(uint64_t gameFrameID) -> std::optional<PortalFrame>` runs once per stereo game frame. A frame contains the accepted snapshot, calibrated per-eye transforms, two `EyeFrustum`s, runtime mapping, and validity/error state.

- [ ] Instrument the existing pose-update, view-offset, and projection paths with frame/eye identifiers. Establish their order in Native Stereo and locate render-thread late updates. Use a shared immutable frame snapshot; do not read the UDP receiver independently for each eye or projection callback.
- [ ] Add a separate `PortalOutput` toggle, default off. Keep existing WindowMode behavior when off. The UDP receiver parses only; it must not modify render-thread state or block a frame.
- [ ] Keep the runtime alive for headset identity/timing/submission, but use the latched external pose consistently for portal camera calculations and any relevant late-update data. OpenTrack supplies the same pose to the runtime for compatibility; it must not become a second independently sampled camera authority. Trace all consumers of `get_hmd_transform`, eye transforms, standing origin, and view extensions before enabling the override.
- [ ] Map the external coordinate origin into runtime/game coordinates explicitly. Initial alignment captures a fixed mapping; portal movement updates the rectangle without resetting the game camera or player origin. Preserve gamepad camera translation/rotation and Unreal world scale while applying the viewer's relative physical offset once.
- [ ] In the stereo view hook, position the eye from the calibrated head transform and use portal-aligned orientation relative to the game camera mapping. In the projection hook, substitute the tested asymmetric matrix. Cover float and double precision engine paths. Avoid clamping away valid asymmetric frusta for eyes outside the window bounds.
- [ ] Make the old full-FOV mask optional in portal output mode. Render edge rounding/feathering in the final portal material initially so stale mask geometry cannot crop the video. Do not accidentally enable CutsceneComfort's full-eye mask on portal output; define its temporary geometry behavior only after baseline integration passes.
- [ ] Reject the invalid/stale states defined in phase 1 and expose them through status. Add a frame-index test that changes state between left and right callbacks; both must retain the same sequence and geometry revision until the next stereo frame.
- [ ] Rebuild UEVR; run synthetic static, lateral, depth, yaw, roll, and rotated-window cases. Verify camera hooks with an overlay listing latched sequence, per-eye origin, and frustum. Commit in the fork: `feat: render synchronized off-axis portal views`; update the recorded UEVR SHA/patch in this repo.

**Gate:** calibrated portal-plane corners project to the image corners; internal parallax changes without double-applying head rotation or translation. Disabling portal mode restores baseline UEVR behavior.

### Task 2.3: Prove the final SBS output and handle compositor incompatibility

**Files:** modify `UEVR/src/mods/vr/D3D11Component.cpp` and `D3D12Component.cpp`; create `UEVR/src/mods/portal/PortalOutput.hpp/.cpp`; create `EVIDENCE/Projection-and-capture.md`.

**Interface:** `PortalOutput` consumes the two eye color textures and `PortalFrame`; it emits a full SBS presentation plus status. D3D11 and D3D12 implementations retain resources until GPU completion; neither reads textures back to the CPU per frame.

- [ ] Add a diagnostic image drawn through the same output path: eye identity, numbered four corners, checkerboard, frame ID, geometry revision, and a known plane/object arrangement at several depths. Compare pre-submission pixels to the final Sunshine capture.
- [ ] Test VRto3D under static, translated, and rolled poses. Verify texture bounds, aspect ratio, runtime lens warping, compositor reprojection, and resolution negotiation. A desktop that looks stereo is insufficient; custom projection pixels must survive unchanged apart from expected color conversion/encoding.
- [ ] If VRto3D preserves the intended views, retain it and record the exact settings. If it changes the views, implement a borderless SBS presentation window using the pre-compositor eye textures. Keep SteamVR/VRto3D for runtime lifecycle initially; Sunshine captures the direct output display. This is the defined fallback, not a requirement to remove the runtime.
- [ ] In a direct output path, use synchronized GPU copies with explicit D3D12 barriers/fences or D3D11 resource lifetimes. Export scene plus required in-game HUD/menu content. The fork leaves separate UI/depth swapchains alone, so compose UI deliberately or provide a usable desktop configuration path.
- [ ] Verify paired eye frame IDs in motion. Native Stereo is preferred; use synchronized sequential only after proving same-tick pairing. AFR requires explicit buffering and validation and is not enabled for the initial portal.
- [ ] Repeat capture validation for the chosen D3D11 and D3D12 cases. Force a resize, game restart, and device resource recreation; check for stale right-eye textures, invalid resource use, and mismatched dimensions.
- [ ] Record which bridge won the gate, capture screenshots/CSV results, and commit: `feat: validate portal SBS presentation`.

**Gate:** one PC-only portal rendering pipeline passes geometry, tracking, and final capture checks. Do not begin comfort tuning against output that fails this gate.

## Phase 3 — Build the native visionOS portal shell

### Task 3.1: Replace template presentation with a mixed-space stereo plane

**Files:** modify `APP/Moonlight_6Dof_VisionApp.swift`, `APP/ContentView.swift`, `APP/ImmersiveView.swift`, `APP/AppModel.swift`, `APP/ToggleImmersiveSpaceButton.swift`, `APP/Info.plist`; create `APP/Portal/PortalEntity.swift`, `APP/Portal/PortalGeometry.swift`, `APP/Portal/PortalControls.swift`, `APP/Portal/PortalManipulation.swift`, `APP/Portal/PortalCornerHandles.swift`, `APP/Resources/Materials/PortalStereo.usda`; create `TOOLS/make_stereo_fixture.py`.

**Interface:** `PortalEntity` owns one plane and its material. `applyGeometry(_ geometry: PortalGeometry)` sets its dimensions/transform; `setTexture(_ texture: TextureResource)` replaces its stereo image. Swift `PortalGeometry` mirrors the phase 1 physical geometry/revision, using `simd_float4x4` for `worldFromPortal`.

- [ ] Make the launcher a conventional SwiftUI window. Set the immersive scene and its Info.plist initial style to mixed consistently. Keep explicit scene transition state for cancellation/error, as the template already does.
- [ ] Implement the initial connection-screen structure from parity C01–C08 with preview/fake session state: centered Moonlight Connection title, two server cards, app/debug columns, Options, and Launch Immersive Mode (No Connection). Preserve grouping and labels using native styling rather than the imported reference app's home screen.

```swift
.immersionStyle(selection: .constant(.mixed), in: .mixed)
```

- [ ] Create a plane with XY dimensions in meters and a normal toward the viewer. Generate an SBS fixture with separate `LEFT`/`RIGHT` labels, a shared grid, and corner markers. Use the inspected Moonlight `SBSMaterial.usda` Camera Index Switch as the initial material, retaining attribution.
- [ ] Implement parity P01–P04/P08: place once from calibrated viewer pose, 1 m along horizontal heading and 0.1 m lower, with yaw-only orientation; height 0.7 m and one-eye aspect. Include free translation/upright yaw and four corner handles. Keep the plane stable as video updates. Resize resets dimensions; recenter remains a separate secondary action.
- [ ] Build the bottom-centered shelf as Settings / Resize / Immersive / Disconnect; omit Snap. Keep the original action meanings and show a usable preview when launched without a connection. Preserve shelf/control readability across scales.
- [ ] Keep the ordinary launcher/settings window independently dismissible while the space remains active (C09). Use Apple's associated-scene lifecycle only for an explicitly coupled experience-control scene if needed. Closing Settings must never stop ARKit/video/audio. Dismissing the actual experience cleans up the session and leaves a usable launcher.
- [ ] Run the phase 0 Mac build command, then verify scene lifecycle in simulator and left/right assignment on device. Record that simulator stereo inspection cannot close the device gate.
- [ ] Commit: `feat: add mixed-space stereo portal shell`.

**Gate:** static stereo appears correctly on a movable room-fixed plane with passthrough and usable controls.

### Task 3.2: Add ARKit tracking and protocol interoperability

**Files:** create `APP/Tracking/DevicePoseProvider.swift`, `APP/Tracking/TrackingSystem.swift`, `APP/Tracking/PoseCalibration.swift`, `APP/Transport/PortalStateEncoder.swift`, `APP/Transport/PortalStateSender.swift`; create `Moonlight-6Dof-Vision/Moonlight-6Dof-VisionTests/PortalStateEncoderTests.swift`; modify `APP/AppModel.swift` and Xcode test target/scheme.

**Interfaces:** `DevicePoseProvider.sample(at timestamp: TimeInterval) -> PoseSample?`; `PoseSample` contains `worldFromDevice`, timestamp, and tracking validity. `PoseCalibration.worldFromHead(device:)` applies the configurable rigid device-to-head offset. `PortalStateSender.send(_ snapshot: StateSnapshot)` is nonblocking and latest-state; Swift state fields match phase 1 exactly.

- [ ] Own one `ARKitSession` and `WorldTrackingProvider` per active experience. Sample `queryDeviceAnchor(atTimestamp:)` from scene updates; handle nil/untracked anchors and session/provider events. Stop the session and sender when the immersive space ends.
- [ ] Apply the calibration transform before encoding; initially expose the assumed 64 mm eye separation and device-to-head offset in a diagnostics view. Establish measured calibration on device rather than treating the device origin as the eyes.
- [ ] Write Swift encoder tests against `CORE/fixtures/state-v1.bin`. Use explicit little-endian integer/double encoding, including quaternion x/y/z/w; do not serialize SIMD memory layout. Add a test for invalid/nonfinite transforms.
- [ ] Use `NWConnection` UDP to the configured host on 4243. Keep networking and packing off MainActor where needed; transfer immutable snapshots from the scene. Add the local-network purpose string and handle denial/retry. Device pose queries alone do not require world-sensing authorization; deferred wall/room-anchor features must not introduce an unrelated permission flow here.
- [ ] Derive a stable session coordinate mapping between RealityKit placement and ARKit origin. Re-express both portal and viewer together after origin changes, or pause and explicitly recenter if continuity cannot be recovered. Increment tracking epoch for resets.
- [ ] Send live poses to `portal_host`, compare signs/units with physical 10 cm movements and head roll, and test tracking loss. Save a short replay trace; commit: `feat: stream calibrated Vision Pro portal state`.

**Gate:** the real headset emits the same protocol as the synthetic sender, and physical movement matches the PC diagnostics without moving the local portal.

## Phase 4 — Integrate Moonlight into the supplied project

### Task 4.1: Import a minimal, buildable streaming adapter

**Files:** create `APP/Streaming/MoonlightSession.swift`, `APP/Streaming/StreamConfiguration.swift`, `APP/Streaming/StreamStatistics.swift`, `APP/Connection/ConnectionViewModel.swift`, `APP/Connection/ConnectionView.swift`, `APP/Connection/ServerPairingSheet.swift`, `APP/Connection/PairingPINSheet.swift`, `APP/Settings/OptionsSheet.swift`, `APP/Settings/StreamConfigurationView.swift`, `Moonlight-6Dof-Vision/ThirdParty/Moonlight/`, `Moonlight-6Dof-Vision/ThirdParty/NOTICE.md`, `EVIDENCE/Moonlight-import.md`; modify `PROJECT/project.pbxproj` and app launch/settings views.

**Interface:** `MoonlightSession` exposes `pair(host:pin:)`, `listApplications(host:)`, `start(configuration:)`, `stop()`, connection state, negotiated format, and statistics. Preserve the reference stack's concrete pairing flow inside the adapter; a displayed PIN is confirmed through the host. This layer owns protocol lifetime, not ARKit or portal transforms.

- [ ] Build the pinned reference visionOS target on Mac first. Inventory its `Limelight/` connection, crypto/pairing, input, audio, `moonlight-common` submodule, framework/linker settings, bridging headers, and decoder helper dependencies.
- [ ] Import the smallest compiling dependency closure into `ThirdParty/Moonlight`, preserving directory relationships and notices. Record every imported file and source SHA. Do not copy its large stream view or app entry point into the new app.
- [ ] Import connection/pairing first, compile, and verify host application enumeration before importing rendering. Store paired credentials using the reference security model/Keychain, not ordinary preferences or diagnostic logs.
- [ ] Bind the phase 3 shell to one observable ConnectionViewModel and shared MoonlightSession. Implement parity C01–C09 and S01–S09, including PIN dialog/retry, paired-card launch, app loading/error/manual-ID fallback, controller/debug column, and Options -> inline configuration. Host/app requests carry identity so late responses cannot replace a newly selected server.
- [ ] Stream settings maintain separate draft, persisted, and active negotiated values. Apply persists res/FPS/format/audio/HDR/full-range for the next connection; effect preferences later persist immediately. Keep automatic bitrate and place the test override under advanced settings. Capability filtering must consider encoded SBS dimensions, not just the per-eye selector.
- [ ] Separate custom adapter code from vendored source so upstream updates are reviewable. Add required framework slices for both device and simulator. Treat unavailable low-latency entitlements as an explicit build configuration, not an assumed signing capability.
- [ ] Test failed PIN, unavailable host, start cancellation, and repeated stop/start; commit: `feat: integrate Moonlight session adapter`.

**Gate:** the new project independently builds and can pair/list/start/stop a session. A reference app build alone does not satisfy this gate.

### Task 4.2: Connect decoded SBS, audio, and gamepad input

**Files:** create `APP/Streaming/StereoVideoRenderer.swift`, `APP/Streaming/StreamAudio.swift`, `APP/Input/GamepadForwarder.swift`; import the pinned `DrawableVideoDecoder.swift` and its Metal/helper dependencies into `ThirdParty/Moonlight`; modify `APP/Portal/PortalEntity.swift`, `APP/Streaming/MoonlightSession.swift`, and Xcode build resources.

**Interfaces:** `StereoVideoRenderer` accepts negotiated video format/dimensions and decoded buffers through the imported callbacks, and supplies `TextureResource` to `PortalEntity`. `GamepadForwarder.releaseAll()` sends neutral state before detaching. `StreamAudio` owns the decoded audio session; it starts in stereo before spatialization work.

- [ ] Connect VideoToolbox -> CVMetalTextureCache/Metal conversion -> DrawableQueue -> stereo material. Request 3840 x 1080 and display each half as 1920 x 1080. Ensure physical plane aspect is based on half the encoded width.
- [ ] Verify color conversion/range with SDR bars, label each eye, and handle negotiated-size changes by recreating queues safely. Bound in-flight frames and release old buffers after GPU completion. Keep a single video frame for both eye samples.
- [ ] Decode audio through the imported Moonlight path. Forward Bluetooth GameController state through Moonlight's controller API, including connect/disconnect and neutral input on focus/session loss. Avoid duplicate input if a controller is also connected to the PC.
- [ ] Stream the synthetic SBS test display first, then phase 2's game output with a fixed synthetic pose. Test 15 minutes, reconnect once, and confirm no aspect stretch, eye swap, increasing decode backlog, or held gamepad input.
- [ ] Record negotiated codec/fps/size, decoder queue behavior, audio continuity, and controller result; commit: `feat: display Moonlight stereo video with audio and gamepad`.

**Gate:** a playable fixed-view stereo game runs in the new Vision Pro app before real head tracking drives its cameras.

## Phase 5 — Close the loop and synchronize manipulation

### Task 5.1: Integrate real tracking, frame identity, and geometry transitions

**Files:** create `APP/Portal/PortalSessionCoordinator.swift`, `APP/Portal/PortalControlsVisibility.swift`, `APP/Transport/PortalStatusReceiver.swift`; modify `HOST/src/StateRelay.cpp`, `UEVR/src/mods/portal/PortalOutput.cpp`, tracking/sender/model files; create `EVIDENCE/End-to-end.md`.

**Interface:** `PortalSessionCoordinator` owns states `idle`, `connecting`, `awaitingTracking`, `streaming`, `reconfiguring`, `recovering`, `failed`. A valid displayed frame is identified by `(sessionID, trackingEpoch, geometryRevision, renderFrameID)`.

- [ ] Replace the synthetic sender with the real Vision Pro state stream. Start the portal only when both tracking and video are valid; keep the user-facing plane stable during connection.
- [ ] Verify left/right, up/down, forward/back, yaw, pitch, and roll individually, followed by diagonal motion. Confirm head rotation doesn't hijack the gamepad camera or double-rotate the scene. Calibrate device-to-head offset, eye separation, and scene scale independently.
- [ ] Implement parity P05–P07 as client-owned geometry revisions: free translation/upright yaw, center-based corner resizing with fixed position/orientation/aspect, physical width 0.5–10 m, and dimension-only reset. For portal-local corner point (x,y), use `s = hypot(x,y)/(0.5*hypot(W0,H0))`, `W = clamp(s*W0,0.5,10)`, `H = W*H0/W0`. Send the entire snapshot for every revision. Recenter uses one captured geometry snapshot rather than independent PC/headset recenter commands taken at different times.
- [ ] Implement B01–B03/P04 interaction behavior: attached shelf, four proportional handles, 1.5 s corner timeout, 3 s shelf timeout, and shelf suppression during manipulation. Use declarative native hover effects where supported and an explicit tap-to-reveal fallback. App timers follow actual input, not assumed raw gaze callbacks. Ensure controls never send gameplay input.
- [ ] Do not mistake a UDP geometry acknowledgment for arrival of matching video. For the first implementation reserve a 16-pixel-high diagnostic strip below each eye's content: total frame 3840 x 1096, with 1920 x 1080 content per eye and nearest-sampled coded ID cells below. Pack session, epoch, revision, frame ID, and CRC using large black/white cells; read after decode and crop the strip from displayed UVs. Repeat IDs per eye and reject mismatches. Reconfigure Sunshine/display/decoder to the measured padded mode; if that capture mode is unavailable, reserve/crop rows within the existing height and update the content aspect explicitly.
- [ ] While moving/resizing, show the local frame/controls immediately and fade game imagery until a decoded matching revision arrives. Old frames cannot be stretched onto newly committed geometry. Continuous drag quality can be optimized after this correctness gate.
- [ ] Test rapid resize/recenter, lost packets, delayed video, and a tracking-epoch reset. Ensure stale video never reactivates an old placement; commit: `feat: synchronize tracked portal geometry and video frames`.

**Gate:** the real viewer sees consistent stereo parallax through the fixed portal, and geometry changes cannot display video rendered for a different rectangle. The metadata strip is an initial diagnostic transport contract; removing it requires an equally reliable frame association mechanism.

### Task 5.2: Make lifecycle recovery deterministic

**Files:** create `APP/Connection/ReconnectSheet.swift`, `Moonlight-6Dof-Vision/Moonlight-6Dof-VisionTests/PortalSessionCoordinatorTests.swift`; modify coordinator, tracking, audio, and gamepad adapters; create `EVIDENCE/Recovery.md`.

- [ ] Write state-machine tests using fake stream/tracking clocks: start cancelled, tracking unavailable, stream lost, host restart, space closed during connection, and repeated disconnect. Assert one session owner, no duplicate sender, and exactly one neutral-input cleanup per active input session.
- [ ] Implement B04–B06: shelf and overlay Disconnect end the real stream, then show Disconnected with Reconnect / Return to Home / Cancel. Reconnect retains host/port/app; Home opens the launcher and ends the experience; Cancel dismisses the dialog without resuming or stranding the user. Don't import the dormant Stream Options menu as a mandatory confirmation step.
- [ ] Test C09 separately: closing ordinary Settings leaves the game running; changing saved stream settings does not silently restart it. Use live negotiated/debug state, not the Quest overlay's Intent snapshot or its local-only Disconnect callback.
- [ ] At 250 ms stale receive age, pause/fade video and release controls; keep the local portal and recovery UI visible. Continue only after valid tracking and a current frame/epoch agree.
- [ ] On headset removal/backgrounding, stop acquisition and drain/release decoder resources safely. On return, confirm coordinate continuity; if unavailable, require an explicit recenter action instead of silently reusing an old room transform.
- [ ] Test network interruption, Windows game crash, SteamVR restart, display mode change, and 20 scene open/close cycles. Record recovery times and whether pairing/settings survive; commit: `fix: coordinate portal recovery and input cleanup`.

**Gate:** the experience recovers or presents a clear recoverable error without hung input, orphan sessions, or unexpected portal relocation.

## Phase 6 — Bring the SpatialSDK product features across

### Task 6.1: Complete and verify Quest UI/UX parity

**Files:** create `APP/Settings/PortalSettings.swift`, `APP/Settings/ImmersiveOptionsSheet.swift`, `Moonlight-6Dof-Vision/Moonlight-6Dof-VisionTests/PortalManipulationTests.swift`, `Moonlight-6Dof-Vision/Moonlight-6Dof-VisionTests/ConnectionViewModelTests.swift`; modify existing connection/settings views, controls, and coordinator; create `EVIDENCE/Feature-parity.md`.

- [ ] Use the completed source deep dive in `EVIDENCE/Quest-UI-UX-Parity.md` as the acceptance checklist: C01–C09, S01–S11, P01–P08, B01–B06. Finish missing behavior from earlier phases without redesigning the navigation.
- [ ] Add the Options -> Immersive Options modal with the four original switch labels/order, all false initially, immediate preference persistence, and Close. Keep the shelf Immersive master toggle distinct from the Apple space lifecycle. Reflections remains visibly unavailable/off while room work is deferred.
- [ ] Persist PC/app choice, stream/effect preferences, calibration, and advanced controls. Maintain portal placement/size during the active session and Settings presentation. Fresh experiences start from the Quest-like placement/base size; room-anchor persistence and wall detection are not dependencies.
- [ ] Add behavioral tests for the resize formula, width endpoints, reset preserving pose, Settings closing without stopping the session, Apply affecting only the next stream, and stale host responses. In the base 16:9 case, assert `resetHeight == 0.7`, `minHeight == 0.28125`, and `maxHeight == 5.625` within numeric tolerance.
- [ ] Run the parity document's V01–V10 comparison procedure. Capture source Quest and visionOS screens for empty/paired/connected/error/configuration/PIN/Options/effect/reconnect states; record actual platform differences. Compare corner manipulation, bottom shelf, inactivity timing, and keyboard behavior on device.
- [ ] Verify no Snap button, wall setup, room permission prompt, or accidentally intercepted gamepad menu action appears. Commit: `feat: match Quest connection settings and plane interaction UX`.

**Gate:** C/S/P/B parity IDs and V01–V10 have source and device evidence; intentional platform adaptations are recorded. No Android API is assumed reusable, and wall work is explicitly deferred.

### Task 6.2: Spatial audio, room dimming, panel lighting, and stream modes

**Files:** create `APP/Effects/PortalLighting.swift`, `APP/Effects/PortalDimming.swift`; modify `APP/Streaming/StreamAudio.swift`, video renderer, settings; add per-device results under `EVIDENCE/Feature-parity.md`. Room-derived reflection rendering is deferred; do not add `PortalReflections.swift` in this scope.

- [ ] Position streamed audio at the portal using the reference client's working audio path as the starting point. Test audio moving with portal placement, head orientation, stereo versus supported surround layouts, and audio/video synchronization. Do not substitute a static-file audio example for continuous decoded PCM without verifying that API path.
- [ ] Reuse the reference decoder's downsampled video output for bias lighting. Rate-limit updates and expose intensity/off controls. Measure its GPU/latency cost with the effect enabled and disabled.
- [ ] Implement Room Dimming through the supported SwiftUI surroundings-effect preference while keeping mixed immersion and tracking active. Compare visually to Quest's 30% passthrough-brightness intent; do not assume identical numerical intensity mapping or guaranteed system enforcement. Disable effects immediately when the shelf Immersive toggle turns off, retaining the saved per-effect switches.
- [ ] Add codec/resolution/fps/HDR controls using actual negotiation and device capabilities. Test HEVC first; validate AV1 decode support on the specific Vision Pro hardware before exposing it as available. Validate HDR metadata/color range/brightness on device with test patterns, including SDR fallback.
- [ ] Record a pass, an explicit platform-specific equivalent, or a concrete remaining limitation for every in-scope parity row. Mark wall-derived reflections and wall pinning deferred per the current request. Other unimplemented effects remain open work. Commit each independent effect after its own visual/performance check.

**Gate:** the agreed feature set has device evidence and measured effects on the core streaming experience.

## Phase 7 — Measure, tune, validate games, and hand off

### Task 7.1: Measure latency and tune only against evidence

**Files:** create `TOOLS/analyze_latency.py`, `EVIDENCE/Performance.md`; extend statistics and host/output instrumentation.

- [ ] Record pose sample/target times, host receive time, latched sequence, render frame ID, capture/encode timing where available, decoder completion, queue age, and displayed identity. Do not subtract clocks from different machines without offset estimation and uncertainty.
- [ ] Use a filmed motion/visual-response experiment to measure actual head-motion-to-content latency. A render-ID overlay and deliberate lateral movement distinguish local plane stabilization from stale internal parallax. Report method and uncertainty, not just a network ping or decoder average.
- [ ] Compare 3840 x 1080 at 60 and 90 fps, then 5120 x 1440 if the device/encoder/capture mode supports it. Test bitrate steps 30/50/80 Mbps and HEVC versus available AV1. Change one variable at a time; retain negotiated values and median/p95/p99 frame/latency statistics.
- [ ] Treat sustained 60 fps, no increasing queue, and a comfortable 20-minute play session as the initial usability gate. A provisional engineering target is p95 motion-to-content latency below 60 ms, subject to measurement method and user comfort review; missing it triggers queue/capture/codec investigation rather than an automatic transport rewrite.
- [ ] Only then test bounded pose prediction. Keep unpredicted and predicted traces, cap the horizon, and test reversal/stop motion. Keep eye positions and projection based on the same predicted pose. UI-follow interpolation from Apple's sample is not the prediction algorithm.
- [ ] If Moonlight remains uncomfortable after measured tuning, document the bottleneck and compare the alternative streaming architecture separately. Do not automatically add CloudXR to this implementation. Commit: `perf: document and tune measured portal latency`.

**Gate:** a repeatable profile meets agreed visual/comfort goals, with bandwidth, thermals, and effects costs recorded.

### Task 7.2: Validate game profiles and prepare reproducible delivery

**Files:** create `EVIDENCE/profiles/` per-game records, `EVIDENCE/Release-checklist.md`, `EVIDENCE/Setup.md`; update project README with the visionOS entry point and links to the plan/results.

- [ ] Validate the original D3D11 game plus a D3D12 title where available. Record UE version, render method, game build, UEVR SHA, runtime/driver/display profile, UI/menu behavior, gamepad camera, cutscenes, motion tests, and known effects failures. Do not infer all UEVR games work from one title.
- [ ] Test clean launch order, pairing, session restart, no-network launch, game exit, display disappearance, headset return, and settings persistence using the release configuration.
- [ ] Run portable tests and Windows UEVR/host Release builds. On Mac run simulator build/test, device build, and the supported archive/signing workflow. Verify packaged shaders, native libraries, notices, and dependent framework slices.
- [ ] Write exact installation/setup instructions with pinned versions, per-game profiles, ports, capture mode, and rollback. Include the runtime bridge decision and expected user-visible tracking-loss behavior.
- [ ] Package a private test build and a PC companion build for review. Publishing, store submission, or public dependency forks are separate actions after the artifacts are reviewable.
- [ ] Review the final diff and evidence matrix, then commit: `docs: add validated visionOS portal setup and compatibility`.

**Final acceptance:** real headset tracking drives synchronized stereo portal projections, Moonlight carries playable video/audio/input, manipulation stays aligned, recovery works, and each parity feature is validated or explicitly listed as outstanding. Windows-only progress cannot close this final gate.

## Evidence ledger and handoff rules

For each task record: commit SHA, platform, exact command or hardware procedure, expected result, observed result, output artifact, and any remaining limitation. Use `not run` for unavailable Mac/device checks; do not substitute source review for execution.

The next execution task is **0.1**, followed by **1.1** while Apple build access is arranged. The key technical decision gate is **2.3**, where the final capture is checked for projection preservation. Source-authoring can continue on Windows, but phases 3–7 retain their explicit Mac/device checks.

## References

The [design document](../specs/2026-09-05-moonlight-6dof-vision-design.md#evidence-and-limitations) contains the inspected source links, Apple samples, and upstream build requirements. Read it before executing this plan. The downloaded onboarding document is historical context; its `master` branch reference, streaming assumptions, and license labels are not authoritative over the pinned source and this plan's validation gates.
