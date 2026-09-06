# visionOS + RealityKit + UEVR 6DOF Window: Investigation

Date: 2026-09-06
Status: research only, no code changes. Open questions are at the end.

## 1. What we have today (Quest 3)

- Kotlin app on Meta Spatial SDK. Moonlight protocol via `moonlight-common-c` (JNI) plus the
  moonlight-android Java layer (`com.limelight.*`). Native `AMediaCodec` decode straight to the
  panel `Surface` (`MoonlightPanelRenderer.kt`, `native_decoder.c`).
- Video is a room-placed 2D panel with scaling, snap-to-wall (MRUK), bias lighting, spatial audio,
  Bluetooth gamepad passthrough to the PC over Moonlight's input channel.
- A stereoscopic side-by-side (SBS) panel path was built (`StereoMode.LeftRight`, OpenGL frame
  duplication, a 3DS-style depth slider) and then removed from `main` in commit `5abe19d`
  ("3D removal set for main"). The deleted design docs are still in git history:
  `Documentation/Stereoscopic-3D-Foundation.md`, `Documentation/3ds-style-depth-control.md`,
  `DesktopSpatial/Documentation/PC-Side-Stereoscopic-Feasibility-Report.md`.
- Nothing on the Quest side reads head pose for rendering purposes beyond initial panel placement.

Only the Moonlight protocol pieces carry over conceptually to visionOS. None of the Kotlin, JNI, or
Spatial SDK code is reusable on Apple platforms.

## 2. What UEVR-6DOF-Window actually is

Source read from `elliotttate/UEVR-6DOF-Window` (default branch `master`, tag "Release
room-anchored 6DOF window v0.2", 2026-08-02). The feature lives in `src/mods/WindowMode.{hpp,cpp}`
and hooks in `D3D11Component.cpp` / `D3D12Component.cpp`.

How it works, verified in code:

1. UEVR still renders the game as a normal VR app: full per-eye field of view, per-eye projection
   from the runtime, 6DOF head tracking from OpenXR or OpenVR.
2. On "Recenter Window In Front Of Me" the mod captures the HMD pose in tracking space and derives a
   room-anchored rectangle (width, height, distance, optional curvature).
3. Every frame, for each eye, a full-screen pixel shader runs on UEVR's final color target. It
   unprojects each pixel to a ray from that eye, intersects it with the anchored rectangle or
   cylinder, and alpha-blends a surround color over everything outside the aperture (feather and
   rounded corners are done in the same signed-distance calculation).
4. Depth and UI swapchains are untouched. UEVR submits the eye images with
   `XR_ENVIRONMENT_BLEND_MODE_OPAQUE`. The surround is written opaque (`SrcAlpha/InvSrcAlpha` with
   alpha = mask * opacity), so there is no transparent surround for passthrough.

Consequences that shape the visionOS design:

- The output is two full-FOV eye images with a black (or colored) surround, not a texture of the
  window contents. They are only correct when displayed the way a VR compositor displays projection
  layers: at the eye pose they were rendered from, ideally with late reprojection.
- The mod is a consumer of a VR runtime. It needs something on the PC that presents itself as an
  OpenXR or OpenVR headset and feeds it Vision Pro head poses.
- The window pose lives in the PC runtime's tracking space. To show the window at the same physical
  spot on Vision Pro, the two tracking spaces must be aligned (a shared recenter event is enough).

## 3. Candidate architectures

### A. Apple Foveated Streaming + NVIDIA CloudXR + UEVR in OpenXR mode (no Moonlight)

visionOS 26.4 added the first-party `FoveatedStreaming` framework. `FoveatedStreamingSession`
connects to a PC endpoint discovered over mDNS, pairs via QR code, and streams an OpenXR app rendered
through NVIDIA CloudXR. Apple's `apple/StreamingSession` repo has the Windows endpoint reference
and the Foveated Streaming Protocol is documented so a third party can implement the session side.
Xcode ships a "Foveated Streaming App" template. Clear XR, an indie TestFlight app, already streams
arbitrary OpenXR PC apps this way, adds PS VR2 Sense controller support, and reports needing an
RTX 40/50 series GPU for CloudXR.

The receiver is `ImmersiveSpace(foveatedStreaming: session) { RealityView { ... } }`, progressive
immersion, and native RealityKit content composites with the stream; if the OpenXR app supplies
depth, they occlude each other correctly.

For this project that means: UEVR-6DOF-Window in OpenXR mode targets CloudXR's OpenXR runtime; the
visionOS app is the receiver and adds RealityKit UI, bias lighting, room dimming, etc. around it.
Head pose, reprojection, foveation, and controller transport are all handled by Apple and NVIDIA.

Pros: lowest latency and best comfort of any option, correct per-eye display of UEVR's output for
free, PS VR2 controllers, eye-tracked foveation. Least code to write. Cons: no Moonlight at all,
NVIDIA-only PC, CloudXR license terms, visionOS 26.4 minimum, passthrough outside the window depends
on whether CloudXR honors alpha (UEVR submits opaque today, so the surround would be black unless the
fork is changed to write alpha and UEVR is switched to alpha blend mode).

### B. Moonlight/Sunshine + VRto3D + UEVR in OpenVR mode, displaying the eye images as-is

VRto3D is an open-source SteamVR HMD driver that renders one 2W x H SBS frame to a chosen monitor.
It accepts OpenTrack 6DoF poses over UDP (default port 4242) and binds `INADDR_ANY`, so Vision Pro
can send poses directly over the LAN. UEVR explicitly supports it. Sunshine captures that display
and Moonlight streams it as a 2W x H video.

On visionOS the SBS frame would be decoded with VideoToolbox, written to a RealityKit texture, and
split per eye with a `CameraIndexSwitch` shader graph. Because each half is a full-FOV eye render,
the quad has to be head-locked and sized to UEVR's FOV, and the frame must be reprojected with the
pose it was rendered from. Moonlight has no per-frame pose metadata channel, so a side channel
(frame id embedded in the video or a UDP timestamp echo) is required to do that reprojection.
End-to-end latency will be roughly 60-100 ms (render, encode, network, decode, present) with no
async timewarp from the platform, which is the same comfort problem every naive VR streamer hits.

Pros: keeps Moonlight, Sunshine, any GPU vendor, and the whole existing connection/pairing model.
Cons: we would be re-implementing a VR compositor on top of RealityKit without platform reprojection.
The 6DOF Window fork buys little here because the window mask is baked into a head-locked image.

### C. Moonlight/Sunshine + VRto3D + UEVR with an off-axis "portal" projection (recommended if Moonlight is the requirement)

Change what UEVR renders instead of masking it: per eye, compute a generalized (asymmetric) perspective
projection whose frustum is exactly the anchored window rectangle seen from that eye position
(the CAVE / zSpace / Looking Glass approach). The rendered image then IS the window content.

On visionOS the SBS frame goes on a room-anchored quad of the same physical size and pose, left half
to left eye, right half to right eye via `CameraIndexSwitch`. The window never lags the head because
RealityKit places the quad. Latency only shows up as slightly stale in-window parallax, which is far
more tolerable than a lagging full-FOV image. Head pose still goes Vision Pro -> OpenTrack UDP ->
VRto3D -> UEVR every frame.

This needs a UEVR-side change (the fork already has the anchored rectangle, eye positions, and
`get_projection_matrix`; UEVR's `FFakeStereoRenderingHook` already overrides the engine's stereo
projection, so the hook point exists). VRto3D's FOV is fixed at driver init, so the projection change
must happen in UEVR, not the driver. The mask pass becomes unnecessary or reduces to a feather.

Pros: Moonlight stays the transport, the visionOS client is a "stereo panel" app very close to what
was already prototyped on Quest, comfort is good, any GPU vendor. Cons: requires C++ work in the UEVR
fork and coordination with its author, no motion controllers unless a custom OpenVR controller driver
is written (see section 5), curvature would need matching mesh geometry on both sides.

### D. ALVR for visionOS

Full VR streaming to SteamVR, App Store, PS VR2 support, hand tracking. It is the "just play UEVR on
Vision Pro" answer but it is neither Moonlight nor RealityKit, so it is listed only as a baseline to
compare latency and comfort against.

## 4. visionOS client building blocks (verified)

- `RikuKunMS2/moonlight-ios-vision` (GPL-3.0, branch `vision-testflight`, v11.0.22, April 2026) is a
  Moonlight client with a native visionOS target. It already has: VideoToolbox decode into
  `TextureResource.DrawableQueue` (`DrawableVideoDecoder.swift`), a RealityKit stream view with
  flat and curved meshes (`RealityKitStreamView.swift`), an SBS 3D mode using a
  `CameraIndexSwitch` shader graph (`SBSMaterial.usda`), immersive environments, reactive ambient
  lighting, HDR, AV1, SharePlay, and gamepad input. It is the natural starting point for B or C.
- Head pose: `ARKitSession` + `WorldTrackingProvider.queryDeviceAnchor(atTimestamp:)` works in any
  `ImmersiveSpace`, including mixed immersion with passthrough. Sending it as an OpenTrack packet
  (six doubles: x, y, z, yaw, pitch, roll) over UDP is trivial.
- Per-eye texture selection: RealityKit `Camera Index Switch` node (Reality Composer Pro). Memory
  leaks from early visionOS versions were fixed in visionOS 2.3.
- Metal alternative: `CompositorServices` in mixed immersion gives direct per-eye rendering and a
  reprojection-friendly pipeline, at the cost of leaving RealityKit for the video layer.
- Controllers: visionOS 26 supports PS VR2 Sense controllers through GameController with 6DoF
  tracking, touch detection, and vibration. Xbox and DualSense gamepads are supported as on iOS.

## 5. Input paths

- Gamepad: Moonlight's input channel works unchanged (options B, C). With Foveated Streaming (A)
  controller input has to travel over the CloudXR / Clear XR channel instead.
- Motion controllers for UEVR's 6DOF motion controls: VRto3D explicitly has no VR controller
  emulation. Getting PS VR2 Sense poses into UEVR over the Moonlight path would need a custom OpenVR
  controller driver on the PC fed from the Vision Pro. Option A gets this through Clear XR's server
  or by extending Apple's endpoint sample.

## 6. Risks and unknowns to verify on hardware

- Whether CloudXR / Foveated Streaming preserves alpha or only depth for compositing with
  passthrough (matters for a see-through surround in option A).
- Real end-to-end latency of Sunshine -> Moonlight -> VideoToolbox -> DrawableQueue on Vision Pro at
  2x2560x1440 or 2x1920x1080, 90 Hz.
- Tracking-space alignment drift between visionOS world tracking and the OpenTrack-fed runtime; a
  shared "recenter" gesture is the minimum, a periodic re-sync may be needed.
- UEVR fork stability: the D3D12 path is described as unvalidated in its own release notes.
- moonlight-ios-vision is a large, fast-moving fork; forking it means tracking its churn.

## 7. Decisions (2026-09-06)

- Product goal: a playable "3D diorama" window in the real room, not an immersive VR title.
- Transport: Moonlight. Foveated Streaming stays as a fallback if latency proves unacceptable.
- PC: RTX 4080. Private fork of UEVR-6DOF-Window is acceptable.
- Input: Bluetooth gamepad over Moonlight's input channel. No motion controllers.
- Surround: real room (mixed-immersion `ImmersiveSpace`, passthrough on).
- Client base: `moonlight-ios-vision`, RealityKit mode, Metal acceptable where needed.
- No curved window in the first version.

Head pose on visionOS requires an open `ImmersiveSpace`; that is fine because the RealityKit stream
view already lives in one, passthrough stays on, and SwiftUI windows can coexist with it.

## 8. Chosen design: option C, the portal projection

The one correction to the original idea: sending head pose to the fork as it exists today is not
enough, because its Window Mode renders full-FOV eye images and masks them. Displaying those on a
quad gives a double projection. The fork must instead render each eye through the window rectangle
(generalized off-axis perspective). UEVR already builds per-eye projections from four FOV tangents
refreshed every frame (`raw_projections` -> `projections[eye]` in `update_matrices`), and it handles
asymmetric frusta natively, so the change is:

1. Keep the fork's anchored rectangle (origin, right, up, back, width, height, distance).
2. Per frame, per eye: eye position `E` from the runtime; window plane at distance `d` along
   `anchor_back`. Tangents relative to the window's basis:
   `tanL = (winLeft - E.x) / (d_e)`, `tanR = (winRight - E.x) / d_e`, likewise up/down, where `d_e` is
   the eye's perpendicular distance to the window plane and coordinates are in the window frame.
3. Override the runtime view rotation with the window's orientation (the eye looks perpendicular to
   the window, not along the head's forward), and override the four tangents. UEVR's existing
   projection builder does the rest. Clamp tangents when the eye approaches the plane.
4. The mask pass becomes optional (feather only). The full submitted eye image is the window
   content, so VRto3D's side-by-side output is exactly what the Vision Pro quad should show.

PC pipeline:

```
Vision Pro head pose --UDP OpenTrack (port 4242)--> VRto3D (SteamVR HMD driver, SBS output)
                                                       |
                            UEVR fork (OpenVR runtime, portal projection per eye)
                                                       |
                     Sunshine captures the SBS display --> Moonlight video + audio + gamepad input
Vision Pro recenter/window size --UDP control msg--> UEVR fork WindowMode (request_recenter, W/H/d)
```

Vision Pro pipeline (fork of moonlight-ios-vision):

- Mixed `ImmersiveSpace` with the existing RealityKit stream view in SBS mode
  (`SBSMaterial.usda`, Camera Index Switch). Quad physical size = window W x H.
- `ARKitSession` + `WorldTrackingProvider.queryDeviceAnchor` at display rate. Convert to OpenTrack
  (x, y, z in cm, yaw, pitch, roll in degrees) and send over UDP to the PC.
- Recenter: capture the device anchor, place the quad at head + forward * d with size W x H, send a
  recenter command to the UEVR fork so both sides anchor from the same pose. Because VRto3D's
  tracking space is fed only by Vision Pro poses, the two spaces stay aligned by construction.
- Everything else (pairing, decode via VideoToolbox into `DrawableQueue`, gamepad input, audio) is
  already in the fork.

Known parameters to expose: window width/height/distance, IPD (VRto3D config, visionOS has no IPD
API), OpenTrack port, PC address, tracking filter off on the VRto3D side (pose is already clean).

VRto3D settings that matter: `async_enable` off, motion smoothing is already disabled by the driver,
`display_index` set to the virtual or real display Sunshine captures, `render_width/height` per eye.

## 9. Phases

1. PC only: VRto3D + UEVR fork + Sunshine producing a side-by-side stream; verify with any Moonlight
   client and a fixed OpenTrack sender (a Python script) that the window responds to pose.
2. UEVR fork: portal projection override plus the UDP control socket for recenter and window size.
   Validate with the OpenTrack script sweeping the eye position: window edges must stay fixed.
3. visionOS: fork moonlight-ios-vision, add the pose sender and recenter flow, size the SBS quad
   from W x H, confirm eye assignment on device (Camera Index Switch is mono in the simulator).
4. Latency and comfort pass: measure end to end, tune Sunshine bitrate/codec (HEVC or AV1, 90 Hz),
   decide whether the Foveated Streaming fallback is needed.

## Sources

- https://github.com/elliotttate/UEVR-6DOF-Window (README, RELEASE_NOTES_v0.2.0-alpha.1.md, src/mods/WindowMode.cpp)
- https://github.com/oneup03/VRto3D (README, vrto3d/src/hmd_device_driver.cpp OpenTrack thread)
- https://github.com/apple/StreamingSession
- https://developer.apple.com/wwdc26/guides/visionos/ and WWDC26 session 286 "Use foveated streaming to bring immersive content to visionOS"
- https://github.com/clear-xr/clearxr-server and https://github.com/clear-xr/clearxr-visionos
- https://github.com/RikuKunMS2/moonlight-ios-vision (branch vision-testflight)
- https://github.com/alvr-org/alvr-visionos and https://github.com/alvr-org/ALVR/issues/3206
- https://developer.apple.com/documentation/ShaderGraph/realitykit/Camera-Index-Switch-(RealityKit)
- https://github.com/halmueller/ShaderGraphStereo
