# Moonlight 6DOF visionOS design

Status: records the architecture agreed in conversation; numerical defaults and bridge selection below are proposed implementation choices to validate.

UI/UX refinement: [Quest UI and UX parity contract](../../../Documentation/visionOS-6DOF/Quest-UI-UX-Parity.md) is required reading. It records the subsequent source deep dive and supersedes the original plan's assumed startup size, distance, scale bounds, and generic settings layout.

## Goal and scope

Build an Apple Vision Pro client that displays a PC game as a stereoscopic portal fixed in the real room. Leaning and moving changes the view into the game. The gamepad controls gameplay; headset motion controls the viewing perspective. Reuse Moonlight transport and the existing UEVR 6DOF Window fork. Preserve the product functionality of the Quest SpatialSDK app with native visionOS implementations.

The first working slice is one flat portal, one PC, one game, SDR video, stereo audio, and one Bluetooth gamepad. End-to-end completion additionally includes close UI/UX parity for the Quest connection/settings screens, free placement/scaling, saved preferences, recovery, spatial audio, room dimming, panel-local lighting, and supported stream settings. Wall snapping/pinning, persistent room anchors, and room-mesh reflection work are deferred by the user's scope clarification; retain the Reflections preference row as unavailable until that work resumes. Curvature, motion controllers, multiple simultaneous game streams, SharePlay, and a CloudXR client are outside this implementation.

## Inspected starting point

- Repository: `D:\Tools\Moonlight-SpatialSDK`, branch `moonlight-6dof-vision`, remote baseline `2d70657` (`empty xcode project`).
- App project: `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision.xcodeproj`.
- App sources: `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision/`.
- The template currently uses a volumetric launcher and full immersion. It has no streaming or ARKit tracking implementation.
- Project settings specify `XROS_DEPLOYMENT_TARGET = 27.0`, Swift language mode `5.0`, and default MainActor isolation. Preserve those settings initially; they describe the supplied project, not a claim about the user's installed headset OS.
- UEVR source baseline: `elliotttate/UEVR-6DOF-Window`, branch `agent/6dof-window-mode`, commit `fb31341e860b15e116a15123820c95f044ff0a0f`.
- Moonlight reference: `RikuKunMS2/moonlight-ios-vision`, branch `vision-testflight`, commit `fb349830ac980ab73dbd653b5b9c813c3b249198`.
- Existing local work in `Documentation/Quality-of-Life-Improvements.md`, `Documentation/multi-display-implementation-plan.md`, and `MyApplication/` is unrelated to this plan.

## Architecture

The new Xcode project is the product shell. Bring the proven Moonlight connection, input, audio, decoder, and stereo material components into that target behind a small adapter. Do not replace the new project with the entire reference app or port Android/JNI rendering.

A mixed `ImmersiveSpace` hosts a movable RealityKit plane and an ARKit tracking service. SwiftUI provides the launcher, settings, and attached controls. The client owns portal geometry. It sends full snapshots of the device/head transform and portal transform to a PC bridge. UEVR uses a coherent snapshot to render two off-axis views through the same physical rectangle. Sunshine captures an SBS presentation surface, and the client decodes that stream into a RealityKit texture selected per eye.

The UI follows `PancakeActivity.ConnectionPanel2D` and the active Quest shelf rather than the imported Moonlight app's navigation. Settings reuses the connection screen as a companion window and may close without ending tracking/streaming. The shelf is Settings, Resize, Immersive, Disconnect; Resize resets dimensions, and Immersive only toggles selected effects. Corner gestures perform uniform scaling around a fixed center.

The initial PC bridge candidate is SteamVR plus VRto3D, retaining UEVR's runtime lifecycle and using VRto3D's SBS presentation. Before relying on it, verify that its compositor/output path preserves the custom projection without warping or cropping. If it fails that gate, retain the runtime for UEVR lifecycle but export the portal textures directly to a capturable window. Removing the VR runtime entirely is a separate redesign, not an incidental fallback.

## Required invariants

1. The portal stays fixed after placement; the Apple follow-view sample supplies tracking mechanics, not continuous portal movement or smoothing.
2. All physical geometry uses meters in a right-handed, Y-up coordinate system. Explicit transforms convert ARKit/device, portal, runtime, and Unreal coordinates.
3. The device anchor is not assumed to be the eye midpoint. A configurable device-to-head transform and eye separation form the initial eye model; calibration is verified on hardware.
4. Both eye renders use the same latched pose, geometry revision, and tracking epoch. A recenter is an explicit state transition.
5. Projection and view orientation both change for a portal. Head rotation changes eye positions; it does not blindly rotate the portal cameras away from the window. Preserve gamepad camera behavior and game-world origin mapping.
6. A normal masked VR frame is not treated as an already rectified portal texture. Reuse the fork's anchor/settings and stereo hooks; add a dedicated portal output mode.
7. Video carries both eyes in one full-width SBS frame. A half is mapped to each eye without extra aspect-ratio stretching, convergence shifts, or headset distortion.
8. Tracking and networking never block SwiftUI or the game render thread. Callbacks obey the template's actor isolation.
9. Tracking loss cannot leave gameplay inputs held or silently resume in an incompatible coordinate frame. The portal remains locally fixed while content pauses/fades and a new valid state is established.
10. Stream latency is measured from motion to changed portal content. A stable RealityKit plane alone is not evidence of responsive parallax.

## Initial operating defaults

- Flat portal: base height 0.7 m and width equal to one eye's content aspect times 0.7 m (1.244444... m for 16:9). Place 1.0 m ahead along horizontal heading, centered 0.1 m below the calibrated viewer; keep upright/yaw-only. Reset size preserves placement. Explicit recenter updates placement without being conflated with Resize.
- Initial assumed eye separation: 0.064 m, exposed as calibration, not asserted as the wearer's measurement.
- Geometry: aspect follows one eye's content, excluding transport padding; corner resizing preserves it and clamps physical width to 0.5–10.0 m. These are meters, not scale multipliers. Scene scale is separate from eye separation.
- User-facing video defaults match Quest: 1280 x 720 per eye, 60 fps, auto format, Stereo, HDR off, Prefer Full Range off, automatic bitrate. The explicit PC bench profile remains 1920 x 1080 per eye / 3840 x 1080 full SBS, HEVC SDR, 60 fps, 50 Mbps. Test 90 fps and 5120 x 1440 only after the bench baseline passes; report actual negotiation.
- Tracking: sample on scene updates, latest-state delivery, no deliberate smoothing or prediction initially. Prediction becomes an opt-in measured change.
- Stale tracking: 250 ms receive-age threshold; reject nonfinite geometry and viewing positions within 0.10 m of or behind the portal plane. Pause/fade instead of producing unbounded projection matrices.
- Transport: proposed LAN state port UDP 4243; OpenTrack output remains local UDP 4242; PC bridge to UEVR uses local UDP 4244. Make ports configurable.
- Platform: author on Windows, build/test portable C++ and PC runtime there; compile/sign visionOS on a Mac with an SDK that supports the project deployment target; verify stereo/tracking on Vision Pro.

These are starting values to tune against measured results. They are not measured performance guarantees.

## Feature parity acceptance

| Quest behavior | visionOS deliverable |
|---|---|
| Pairing, host/app selection, stream settings | Two-card connection screen, app/debug columns, pairing dialogs, Options and inline configuration matching the parity contract; secure credential storage |
| Floating passthrough panel | World-placed RealityKit portal in mixed immersion |
| Grab, rotate, resize, reset | Free translation/upright yaw, four center-based uniform resize handles, width 0.5–10.0 m; Resize resets dimensions only; coherent PC geometry updates |
| ButtonShelf and settings access | Settings / Resize / Immersive / Disconnect; attached controls with interaction-driven reveal and companion settings window that can close independently |
| Bluetooth gamepad passthrough | GameController events through Moonlight input; release inputs on loss |
| Sleep/wake and disconnect recovery | Shared session state machine for scene, tracking, stream, and input |
| Snap to wall / room integration | Deferred; no Snap control, wall detection, or room-anchor setup in the current scope |
| Spatial audio | Stream audio positioned at the portal with tested channel mapping |
| Room dimming / lighting emission / reflections | Preserve effect preference labels/order and activation semantics; implement dimming and panel-local lighting; room-derived reflections deferred |
| Codec/HDR/resolution preferences | Supported configurations validated per device; unavailable modes disabled |

## Evidence and limitations

- [UEVR window source](https://github.com/elliotttate/UEVR-6DOF-Window/blob/fb31341e860b15e116a15123820c95f044ff0a0f/src/mods/WindowMode.cpp) implements a final-eye aperture mask using runtime eye poses/projections. The proposed off-axis output is new work.
- [UEVR release validation](https://github.com/elliotttate/UEVR-6DOF-Window/blob/fb31341e860b15e116a15123820c95f044ff0a0f/RELEASE_NOTES_v0.2.0-alpha.1.md) covers D3D11/OpenXR in Meta XR Simulator; final-eye D3D12 visual validation remains open. D3D11/OpenVR is also a new test combination for this project.
- [UEVR build instructions](https://github.com/elliotttate/UEVR-6DOF-Window/blob/fb31341e860b15e116a15123820c95f044ff0a0f/COMPILING.md) require EpicGames repository access for UESDK, an authenticated submodule checkout, CMake, and a C++23 MSVC toolchain.
- [Moonlight reference decoder](https://github.com/RikuKunMS2/moonlight-ios-vision/blob/fb349830ac980ab73dbd653b5b9c813c3b249198/Moonlight%20Vision/DrawableVideoDecoder.swift) supplies VideoToolbox/Metal/DrawableQueue integration. Its dependent helpers and frameworks must travel with imported code.
- [Apple stereo sample](https://developer.apple.com/documentation/visionos/displaying-a-stereoscopic-image-in-visionos) establishes per-eye material selection.
- [Apple device tracking sample](https://developer.apple.com/documentation/visionos/placing-entities-using-head-and-device-transform) distinguishes device and head transforms and limits device queries to immersive spaces.
- [Apple follow-view sample](https://developer.apple.com/documentation/visionos/displaying-a-3d-object-that-moves-to-stay-in-a-person%27s-view) uses `ARKitSession`, `WorldTrackingProvider`, timestamped queries, and scene-update callbacks. Its sphere interpolation is unsuitable for a fixed portal.
- [Apple window association sample](https://developer.apple.com/documentation/visionos/associating-a-window-with-an-immersive-space) demonstrates coordinated scene lifecycles; it does not synchronize PC rendering geometry.
- [VRto3D](https://github.com/oneup03/VRto3D) documents SBS presentation and OpenTrack input. Pin its revision during setup and inspect its actual license; the old handoff's blanket MIT characterization must not be copied into dependency notices.

No code build, hardware validation, or performance measurement is implied by this design document.
