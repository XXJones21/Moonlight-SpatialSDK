# Windows Portal Validation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to execute this plan task by task in the current session. Steps use checkboxes for tracking. Coordinate live game and headset checks with the user.

**Goal:** Demonstrate a working Windows-to-Vision-Pro off-axis stereo portal, with ordinary gamepad camera controls and verified frame identity.

**Architecture:** SteamVR and a VRto3D virtual HMD supply the OpenVR lifecycle required by the existing UEVR portal fork. PortalHost routes external geometry to UEVR and head tracking to VRto3D. UEVR exports direct pre-compositor stereo pixels and metadata; Sunshine streams them to SixDoFPanel.

**Tech Stack:** Windows, SteamVR/OpenVR, VRto3D, UEVR, C++/CMake/MSVC, Python, Sunshine, visionOS Moonlight.

**Execution update, 2026-09-06:** The user explicitly authorized proceeding to builds and tracking preparation despite the unresolved left/right visual mismatch. Current Native Stereo is reported stable after disabling Frame Generation/DLSS. Keep the stereo visual gate open, defer its tuning, and continue independent build/relay work. This user direction overrides the strict gate-order rule below for this continuation; it does not waive frame metadata, supported portal configuration, or tracking validation requirements.

**Spec:** [Windows handoff](../../../Documentation/visionOS-6DOF/Windows-handoff.md), [PC pipeline](../../../Documentation/visionOS-6DOF/PC-portal-pipeline.md), and [protocol](../../../Documentation/visionOS-6DOF/Protocol-v1.md).

**Latest execution update:** Live AVP tracking drives the injected game. Revision `67d9785` fixes the GPU resource lifetime stall following the earlier projection and secondary Present fixes. The user sees direct SBS with metadata; 60 successful sampled output records span 70 seconds with no sampled GPU stalls. Three transient unpaired/stale records recover by the next sample. Exact-size capture and headset video remain open. Proceed with the [Sunshine SBS headset delivery plan](2026-09-06-sunshine-sbs-headset.md). All historical synthetic senders remain stopped; use live AVP input only. See the [live output evidence](../../../Documentation/visionOS-6DOF/evidence/windows/2026-09-06-present-fix/README.md).

## Global constraints

**Live headset checkpoint:** Windows observed the connected AVP as 10.1.95.13.
After explicit user approval of the address correction, the scoped firewall and
relay peer were updated. PortalHost now accepts advancing live pose sequences
with changing head position/orientation, without synthetic input. Game/UEVR
consumption and visible direct SBS now pass in the tested D3D12 run. Client
status receipt and valid portal video remain unverified.
Client changes, if needed, belong in the separate Mac session.

- All Windows runtime gates start open. Source inspection and passing unit tests do not establish runtime or device success.
- Use OpenVR and Native Stereo. Keep Portal Output off until the ordinary stereo baseline passes.
- Preserve existing forks, game profiles, SteamVR settings, and Sunshine configuration. Do not bootstrap over the existing authored checkout.
- Apply the complete unsupported-mode list in PC-portal-pipeline.md before enabling Portal Output.
- Initial portal capture: SDR HEVC, stereo audio, 60 fps requested, 1280x720 per eye, 2560x736 total including 16 metadata rows. Display scaling is 100%; no cropping or rescaling.
- Later benchmark: 1920x1080 per eye, 3840x1096 total. Achieved performance must be measured.
- Headset traffic uses UDP 4243. UEVR UDP 4244 and OpenTrack UDP 4242 remain loopback.
- Keep Vision Pro Settings open for the known audio lifecycle issue. Confirm the startup log's actual 6DoF selection.
- Never bypass metadata validation to display a frame. Never disable the shared-source hash check to make a build pass.
- Validate the selected game's actual D3D backend first. D3D11 and D3D12 require separate evidence; one game's success cannot certify both.

## Ownership and evidence

Codex handles inventory, backups, setup, source/dependency work, builds, diagnostics, trace analysis, and evidence. The user pilots game saves/menus/controller input and the Vision Pro. Each live checkpoint includes a short action script and a specific observation to report; the user need not remain in the headset during build work.

Store each run under `Documentation/visionOS-6DOF/evidence/windows/<run-id>/`, including a README with versions, commands, settings, pass/fail results, and artifact paths. Redact credentials from collected logs. Update `evidence/profiles/baseline.json`, `evidence/PC-baseline.md`, and `evidence/Projection-and-capture.md` only for measured configurations. Preserve a record of the ordinary stereo baseline separately from portal results.

Existing implementation areas, inspected or changed only as a demonstrated failure requires:

- `PortalCore/`: geometry, protocol, and portable tests.
- `PortalHost/`: Windows UDP relay, OpenTrack conversion, and adapter tests.
- `tools/visionos-portal/send_pose.py` and `tests/`: synthetic sender and existing tool tests.
- `External/UEVR-6DOF-Window/src/mods/portal/`: frame latching, session, and direct output.
- UEVR runtime and stereo hooks named in PC-portal-pipeline.md: camera ownership and eye/frame association.
- `Documentation/visionOS-6DOF/patches/UEVR-portal.*`: export deliberate committed fork changes through `export_uevr_patch.py` for transfer to the Mac.

## Gate 0: Reproducible test setup

- [x] Confirm the test game. Hogwarts Legacy, Steam build 20773316; previous injection used D3D12. User identified the dedicated nightly-01139/v0.2.0-alpha.1 package, whose revision file matches fb31341. Original and active profiles are recorded in the [stereo setup evidence](../../../Documentation/visionOS-6DOF/evidence/windows/2026-09-06-native-stereo/README.md); the new injected session's renderer still requires fresh confirmation.
- [x] Have the user select a repeatable save/location with near and distant objects, visible straight edges, and room for ordinary controller movement. User confirmed normal uninjected Hogwarts Legacy controls/menu operation and left the game in a house common room.
- [x] Locate and back up the actual game profile and runtime/capture settings before edits. Record restoration paths. Completed 2026-09-06: 62 files verified by SHA256; see [baseline preparation](../../../Documentation/visionOS-6DOF/evidence/windows/2026-09-06-baseline/README.md).
- [ ] Complete the [initial inventory](../../../Documentation/visionOS-6DOF/evidence/Windows-inventory-2026-09-06.md): inspect external driver registration in the user context, installed SteamVR/VRto3D versions, display resolution/origin/scaling, and PC/headset IPv4 addresses.
- [x] Verify Git shell tooling and authenticated UESDK access without printing credentials. Restored the pinned submodules through authenticated HTTPS in the user context; see [build evidence](../../../Documentation/visionOS-6DOF/evidence/windows/2026-09-06-builds/README.md).

**User checkpoint:** Load the chosen save and demonstrate ordinary movement and camera control before injection.

**Pass:** A recorded game/profile/location and recoverable configuration. Unresolved dependencies remain explicit blockers for the full fork build.

## Gate 1: Stationary virtual HMD

- [x] Inspect the installed driver, or select a VRto3D build after checking its upstream installation instructions and correspondence with the pinned tracking implementation. Record binary provenance and configuration keys from the actual version. Official V5.0.0 package SHA256 verified; see [HMD evidence](../../../Documentation/visionOS-6DOF/evidence/windows/2026-09-06-virtual-hmd/README.md).
- [x] Register/configure VRto3D, preserving other drivers and settings. Coordinate any SteamVR restart with the user. Registered externally and restarted SteamVR; Hogwarts stayed running.
- [x] Start without external pose input or motion controllers. Verify a connected, active HMD and sample runtime pose validity and position/orientation for a proposed 60-second stationary check. 6,280 complete identical samples over 100.50 seconds; explicit connected/valid/Running_OK fields checked afterward. Visible user confirmation remains pending.
- [x] If no pose inspection tool is available, make a small read-only OpenVR pose probe a separate bounded implementation task. A green status icon alone does not verify pose stability. Used built-in vrcmd plus a disposable read-only probe for explicit validity and raw/standing transforms.

**User checkpoint:** Confirm SteamVR's headset state and any visible runtime errors. The Vision Pro is not needed yet. Completed: user confirmed connected headset without the disconnected error and game still usable.

**Pass:** Valid stationary pose samples and no headset-disconnected error for the virtual HMD. Explain any observed drift before proceeding.

**Failure boundary:** Diagnose driver discovery, activation, or pose validity here. A dedicated SteamVR driver is a fallback only after a concrete VRto3D limitation is demonstrated; OpenXR would additionally require porting the portal integration.

## Gate 2: Ordinary stereo in the game

- [x] Establish baseline backend provenance. Package metadata identifies the handoff's fb31341 baseline fork; backend/injector SHA256 values recorded in the stereo setup evidence. This is the dedicated 6DOF Window fork before the authored portal patch, not unmodified upstream UEVR.
- [x] Inject with OpenVR and Native Stereo, Portal Output off. Baseline and patched D3D12 injections recorded; patched package provenance and supported saved settings verified in [injection evidence](../../../Documentation/visionOS-6DOF/evidence/windows/2026-09-06-portal-injection/README.md).
- [ ] Inspect both eyes and the normal runtime presentation; confirm that neither eye is blank, duplicated, or intermittently missing.
- [ ] Save the working profile and a proposed two-minute gameplay observation with relevant runtime/injector logs.

**User checkpoint:** At the saved location, walk, turn the camera, inspect a near object, and open/close a game menu. Report stereo visibility, control behavior, and instability.

**Pass:** Stable two-eye rendering and normal gamepad camera movement without a physical PCVR headset or Virtual Desktop. This does not establish off-axis portal capture.

## Gate 3: Build and validate the portal components

- [x] Initialize required public and authenticated submodules at their recorded commits, preserving the existing authored fork.
- [x] Run the existing standalone build/test commands from the repository root. Release builds passed, CTest passed 1/1 each for core and host, Python passed 5 tests. Explicitly select VS2022 BuildTools on this PC:

```powershell
cmake -S PortalCore -B PortalCore/build -G "Visual Studio 17 2022" -A x64
cmake --build PortalCore/build --config Release
ctest --test-dir PortalCore/build -C Release --output-on-failure
cmake -S PortalHost -B PortalHost/build -G "Visual Studio 17 2022" -A x64 -DPORTAL_HOST_BUILD_TESTS=ON
cmake --build PortalHost/build --config Release
ctest --test-dir PortalHost/build -C Release --output-on-failure
python -m unittest discover -s tools/visionos-portal/tests -p "test_*.py"
```

- [x] Follow the restored UEVR build workflow; inspect generated CMake against `cmake.toml` and supply `-DPORTAL_CORE_SOURCE_DIR=D:/Tools/Moonlight-SpatialSDK/PortalCore`. Release backend and companions passed; final revision `0b598303`, package hashes and commands in build evidence.
- [x] Investigate failures individually and re-run affected checks after fixes. Fixed the demonstrated MSVC name-lookup failure in PortalFrame.cpp; the previously failing full compiler build passes. Exported committed fork fix and checked the patch reverses cleanly against its authored tree.
- [x] Verify the built backend retains the Gate 2 behavior with Portal Output off before adding synthetic input. User confirmed expected operation so far with both output modes off; module paths and saved profile verified. The prior visual mismatch remains explicitly deferred, and in-world tracking behavior still needs measurement.

**User checkpoint:** Briefly repeat the baseline game controls if the injected backend changes.

**Pass:** Recorded successful builds and existing automated tests, identifiable backend binaries, and retained ordinary stereo behavior. No portal runtime claim yet.

## Gate 4: Tracking and protocol behavior

**Current execution:** Synthetic checks below are historical evidence only.
The user requested proceeding with live AVP input, and the live network link has
passed. Use the VRto3D view for the next Windows camera test while visionOS
portal video is unavailable. This does not close the direct-output gate.

- [ ] With game closed, reconnect AVP and correlate changing accepted head poses
  with read-only raw/standing OpenVR samples. Retain the measured +1 m raw / +2 m
  standing Y baseline when interpreting positions. Confirm live input rather
  than mistaking the driver's retained last pose for fresh tracking.
- [x] Load the fixed game location and inject the bb7e3ef package using OpenVR
  and Native Stereo. Keep Portal Output, Room-Anchored Window, and diagnostics
  off for this ordinary VR camera test; confirm the loaded backend path.
- [x] User visually confirms live head pose drives the SteamVR loading view and
  maps cleanly into the injected game's VRto3D view. Screenshot and live process
  inspection identify bb7e3ef with all three output/window options off. This is
  an ordinary camera pass; detailed axis traces and independent gamepad checks
  below remain separate.
- [ ] Observe VRto3D while the user performs small left/right, up/down, and
  forward/back translations, followed by yaw/pitch/roll. Record corresponding
  live relay and runtime evidence. Check camera direction, continuity, and
  unwanted additional offsets; then check independent gamepad camera input.
- [x] After the ordinary live camera test, investigate the known secondary
  Present recursion before validating Portal Output. Ordinary VR head rotation
  with Portal Output off is expected; it does not prove correct portal-mode
  game-camera ownership or off-axis projection.

  Investigation/update: d700ced fixes the demonstrated unguarded secondary
  dispatch path. 32 offline regression cases, independent review and final
  Release build passed. Live recovery executed without the preceding crash.
  The subsequent GPU stall was reproduced and fixed in 67d9785; live direct SBS
  and valid paired output now pass. See present-fix evidence. End-to-end capture,
  frame identity and recovery checks remain open.

Preparation passed outside the game: the actual PortalHost forwarded 810 synthetic
states across static/sweep/roll tests. A standalone build of actual PortalSession.cpp
accepted all three sessions and reset epoch 2, and expired each session after input
stopped. Tracking packets went to a disposable receiver, never SteamVR. These
checks do not close the runtime tracking, renderer status or reset-ack gates below.

- [x] Start `portal_host.exe --peer 127.0.0.1 --trace-poses` from `PortalHost/build/Release`; capture stdout/stderr to the run evidence directory.
- [x] Restrict the actual VRto3D OpenTrack receiver to PC-local UDP 4242 access and verify tracking offsets. Stock bind remains 0.0.0.0; a scoped firewall rule blocks non-loopback IPv4 senders. Active enforcement verified, external packet test not performed. User enabled OpenTrack live; saved defaults remain disabled.
- [x] Run one sender at a time with `send_pose.py --host 127.0.0.1 --port 4243 --pattern static --duration 10`, then `--pattern sweep`, then `--pattern roll --reset-at 3`. Each sent 900 snapshots; see [runtime evidence](../../../Documentation/visionOS-6DOF/evidence/windows/2026-09-06-runtime-tracking/README.md).
- [x] Compare transmitted values against runtime pose samples, checking each translation/rotation axis and sign. Added isolated positive/negative holds because the shipped patterns lack Z translation and pitch. All six axes passed; raw +1 m Y and standing +2 m Y remain explicit. Neutral restoration verified after every run.
- [ ] With the patched renderer, verify 4244 state acceptance, returned 4243 status, reset acknowledgment, and stale handling beyond the 250 ms lease. Before valid portal output exists, outputUnavailable is expected.

**Current user checkpoint:** Keep the working 67d9785/live AVP setup available.
Coordinate capture-display provisioning and stream reconnects under the new
Sunshine delivery plan; do not repeat the historical synthetic sequence.

**Pass:** Measured tracking conversion, no unexplained extra driver offset, and correct status/reset/stale transitions. Any apparent double tracking must be resolved before device testing.

## Gate 5: Direct portal pixels and capture

- [x] Observe visible direct SBS game output and metadata strip with live AVP
  input on 67d9785; verify advancing valid/paired successful output records.
  This is source presentation evidence, not Sunshine pixel/metadata decoding.

Execute the remaining steps using the [Sunshine delivery plan](2026-09-06-sunshine-sbs-headset.md).

- [ ] Apply the supported portal profile, enable Portal Output, and configure 1280x720 eye content with a 2560x736 capture display at its measured desktop origin and 100% scaling.
- [ ] Establish that the display supports this mode and Sunshine negotiates the exact encode size. If it does not, treat display-mode provisioning as a separate task before continuing.
- [ ] Enable exporter diagnostics. Capture the direct pre-compositor window and preserve the entire metadata strip through SDR HEVC; exclude the SteamVR mirror and VRto3D presentation window as portal capture sources.
- [ ] Check eye assignment, all corners and decoded metadata/CRC after the actual codec path using live AVP tracking. Optional exporter image diagnostics may help isolate a capture problem; do not resume synthetic pose senders.
- [ ] Instrument source frame IDs, all four eye view/projection observations, accepted sequence, and geometry revision. Exercise a state update between eye callbacks and verify that one stereo frame retains one latched state.
- [ ] Switch from diagnostics to actual game output; verify ordinary camera control, geometry alignment, and scene HUD. Configure separate Slate/runtime menus from the desktop.

**User checkpoint:** Inspect the diagnostic labels/corners and then the game. Report eye reversal, cropping, scaling, instability, or missing scene content.

**Pass:** Correct final decoded stereo pixels and frame identity at the exact capture dimensions, plus actual game output. A healthy relay status or source screenshot alone is insufficient.

## Gate 6: Vision Pro live tracking and recovery

- [x] Stop synthetic input. Restart PortalHost with `--peer` set to the measured headset IPv4 and allow required headset-to-PC UDP 4243 access; retain loopback 4242/4244. Completed early under user direction: observed peer 10.1.95.13, explicitly approved firewall correction, 660 complete live poses accepted. See headset-preparation evidence. This does not close the remaining Gate 6 checks.
- [ ] Confirm 6DoF Window is on in the actual startup log and client dimensions match the exporter. Keep Settings open and stereo audio enabled.
- [ ] At the fixed game location, guide the user through small left/right, up/down, and forward/back head translations, then yaw/pitch/roll. Compare perceived parallax to the geometry and traces; head rotation must not act as ordinary head-aim camera rotation.
- [ ] Move, resize, and yaw the panel. Verify the rectangle/projection updates while the game origin remains fixed; verify calibrated eye separation and geometry revisions.
- [ ] Use the gamepad to walk and turn while tracking. Check for a second camera offset or unwanted roomscale/head aiming.
- [ ] Exercise tracking loss, recenter/reset, disconnect/reconnect, geometry changes, and host/game restarts. Confirm stale or mismatched images disappear and only current valid frames return.

**User checkpoint:** Pilot the full sequence and report parallax, stability, control behavior, and disappearance/recovery of invalid content. Pause between actions so traces can be correlated.

**Pass:** A complete live loop with correct geometry, independent gamepad camera movement, and reliable invalid-frame suppression/recovery. Save a reproducible launch checklist and profile.

## Follow-on qualification

- [ ] With diagnostics off, measure achieved frame cadence, dropped frames, latency observations, and a proposed ten-minute gameplay run at 1280x720 per eye. Keep these measurements separate from the earlier flat-stream figures.
- [ ] Attempt 1920x1080 per eye only after the initial mode passes; record the new display/encode configuration and performance independently.
- [ ] Complete D3D debug-layer and resource-lifetime checks for the selected backend, including resize/recreation and busy slots. Use controlled diagnostics for device-removal recovery; do not disrupt the user's live session to simulate it.
- [ ] Qualify the other D3D backend with a suitable game/profile separately. Do not claim both are validated from this test game.
- [ ] Track the Settings/audio lifecycle and controller focus issues as separate visionOS follow-ups. Export any committed external-fork fixes and update evidence before handing changes back to the Mac.

**Execution rule:** Fix the earliest failed gate, repeat the affected check, and proceed only when its evidence passes. Build/setup work happens between live checkpoints; user availability is needed only for the explicitly identified pilot actions.
