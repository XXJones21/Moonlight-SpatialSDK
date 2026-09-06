# Moonlight visionOS + UEVR 6DOF Window: Developer Onboarding

Last updated: 2026-09-06
Owner: Josh (XXJones21)
Working branch in this repo: `claude/moonlight-visionos-realitykit-42ra2f`
Companion document with deeper notes: `Documentation/visionOS-UEVR-Investigation.md`

This document is written so that a developer (or a fresh AI session) with no prior context can
pick the project up. It covers the goal, what exists today, what was learned about every
dependency, the design that was chosen, the decisions already made, and the exact next steps.

---

## 1. The goal in one paragraph

Build an Apple Vision Pro app that shows a PC game, rendered by the UEVR "6DOF Window" fork, as a
stereoscopic 3D window anchored in the real room. Think "3D diorama you can play with a gamepad",
not a fully immersive VR title. The window has real parallax: as your head moves, you look into the
game world through the window and the window edges stay fixed in the room. The reference that
proves it is possible is a Quest 3 demo posted by @Azadux on X (see section 9, video not yet
reviewed). The transport preference is Moonlight, but the owner is open to Apple's Foveated
Streaming as a fallback or a fast first proof.

---

## 2. Repositories and links

### This project

| What | Where |
|---|---|
| Quest 3 app (existing, Kotlin, Meta Spatial SDK) | https://github.com/XXJones21/Moonlight-SpatialSDK |
| Investigation notes | `Documentation/visionOS-UEVR-Investigation.md` on the branch above |
| This onboarding doc | `Documentation/visionOS-UEVR-Onboarding.md` |

### UEVR

| What | Where |
|---|---|
| UEVR 6DOF Window fork (the feature we depend on) | https://github.com/elliotttate/UEVR-6DOF-Window |
| Upstream UEVR | https://github.com/praydog/UEVR |
| UEVR docs | https://praydog.github.io/uevr-docs |
| CutsceneComfort plugin (talks to the fork) | https://github.com/elliotttate/uevr-cutscene-comfort |

### PC-side VR runtime bridge (Moonlight path)

| What | Where |
|---|---|
| VRto3D, SteamVR HMD driver that outputs side-by-side to a monitor | https://github.com/oneup03/VRto3D |
| VRto3D docs | https://oneup03.github.io/VRto3D/ |
| OpenTrack protocol reference driver | https://github.com/r57zone/OpenVR-OpenTrack |
| Sunshine (streaming host) | https://github.com/LizardByte/Sunshine |

### visionOS client bases

| What | Where |
|---|---|
| moonlight-ios-vision, Moonlight client with native visionOS target, RealityKit mode, SBS 3D (GPL-3) | https://github.com/RikuKunMS2/moonlight-ios-vision (branch `vision-testflight`) |
| Upstream moonlight-ios | https://github.com/moonlight-stream/moonlight-ios |
| moonlight-common-c (protocol core, used by every client) | https://github.com/moonlight-stream/moonlight-common-c |
| VisionVNC (VNC + Moonlight visionOS app, another reference) | https://github.com/illixion/VisionVNC |

### Apple Foveated Streaming path (alternative transport)

| What | Where |
|---|---|
| Foveated Streaming framework docs (visionOS 26.4+) | https://developer.apple.com/documentation/FoveatedStreaming |
| WWDC26 session 286, "Use foveated streaming to bring immersive content to visionOS" | https://developer.apple.com/videos/play/wwdc2026/286/ |
| Apple's Windows endpoint reference + OpenXR sample | https://github.com/apple/StreamingSession |
| NVIDIA CloudXR SDK (required on the PC for this path) | https://docs.nvidia.com/cloudxr-sdk/latest/usr_guide/foveated_streaming/index.html |
| Clear XR (indie app that streams any OpenXR PC app to Vision Pro this way) | https://github.com/clear-xr/clearxr-server and https://github.com/clear-xr/clearxr-visionos |
| ALVR visionOS (SteamVR streaming, full VR, another baseline) | https://github.com/alvr-org/alvr-visionos |
| ALVR issue on adopting Foveated Streaming | https://github.com/alvr-org/ALVR/issues/3206 |

### RealityKit stereo rendering

| What | Where |
|---|---|
| Camera Index Switch shader graph node (per-eye texture selection) | https://developer.apple.com/documentation/ShaderGraph/realitykit/Camera-Index-Switch-(RealityKit) |
| Working stereo demo | https://github.com/halmueller/ShaderGraphStereo |
| Apple sample: displaying a stereoscopic image | https://developer.apple.com/documentation/visionOS/displaying-a-stereoscopic-image-in-visionos |
| PS VR2 Sense controllers on visionOS 26 (not needed now, gamepad only) | https://appleinsider.com/articles/25/06/09/playstation-vr2-controller-support-comes-to-apple-vision-pro-with-visionos-26 |

### Reference demo

| What | Where |
|---|---|
| @Azadux Quest 3 6DOF window demo (not yet reviewed, see section 9) | https://x.com/Azadux/status/2096387882214941118 |
| Local copy on Josh's PC | `C:\Users\josh2\Downloads\ssstwitter.com_1788674213549.mp4` |

---

## 3. What exists today: the Quest 3 app

Repo: `XXJones21/Moonlight-SpatialSDK`, folder `Moonlight-SpatialSDK/`.

- Kotlin app on Meta Spatial SDK. Moonlight protocol via `moonlight-common-c` (JNI) plus the
  moonlight-android Java layer under `app/src/main/java/com/limelight/`.
- Native decode: `app/src/main/jni/native_decoder.c` wraps `AMediaCodec` and renders straight to
  the Spatial panel `Surface`. Bridged by `MoonlightPanelRenderer.kt` and `MoonBridge.java`.
- Two activities: `PancakeActivity.kt` (2D launcher, pairing, settings, keyboard works there) and
  `ImmersiveActivity.kt` (VR, ~2300 lines, panel creation, ButtonShelf, scaling, MRUK snap-to-wall,
  bias lighting, spatial audio, sleep/wake recovery).
- `MoonlightConnectionManager.kt` owns pairing and stream lifecycle. Bluetooth gamepad input is
  forwarded through Moonlight's input channel via `ControllerHandler`.
- Key docs: `Documentation/Quest 3 App Overview.md`, `Documentation/POST_MORTEM.md`,
  `Documentation/Native_Rendering_Overview.md`, `Documentation/Quality-of-Life-Improvements.md`.

Stereo history worth knowing:

- A side-by-side stereo panel was built (`StereoMode.LeftRight`, OpenGL frame duplication in
  `native_decoder.c`, a 3DS-style depth slider) and removed from `main` in commit `5abe19d`
  ("3D removal set for main"). Deleted design docs are recoverable from git:
  `git show 5abe19d^:Documentation/Stereoscopic-3D-Foundation.md`,
  `git show 5abe19d^:Documentation/3ds-style-depth-control.md`,
  `git show 3e2fa8d:Documentation/PC-Side-Stereoscopic-Feasibility-Report.md`.
- A Python PC-side "DesktopSpatial" client (DXGI capture, Depth Anything, SBS) was also removed in
  `5abe19d`.

Nothing in the Kotlin, JNI, or Spatial SDK code is reusable on Apple platforms. What carries over
is the Moonlight protocol knowledge, the panel-in-the-room product model, and the stereo lessons.

---

## 4. What the UEVR 6DOF Window fork really does (verified from source)

Read from `elliotttate/UEVR-6DOF-Window`, default branch `master`, latest commit "Release
room-anchored 6DOF window v0.2" (2026-08-02). The feature is `src/mods/WindowMode.{hpp,cpp}` with
hooks in `src/mods/vr/D3D11Component.cpp` and `D3D12Component.cpp`. Base is UEVR nightly 01139.

Mechanics:

1. UEVR renders the game as a normal VR app: full per-eye field of view, per-eye projection from
   the runtime, 6DOF head tracking from OpenXR or OpenVR.
2. On "Recenter Window In Front Of Me" (or first valid frame) the mod stores the HMD pose in
   tracking space as the anchor (`m_anchor_origin/right/up/back`) and derives a rectangle of
   `PlaneWidth x PlaneHeight` meters at `AnchorDistance` meters along `-anchor_back`.
3. Every frame, per eye, `WindowMode::build_constants` computes the eye origin and unprojection
   rays from `vr->get_hmd_transform`, `vr->get_eye_transform`, `vr->get_projection_matrix`. A
   full-screen HLSL pixel shader intersects each pixel ray with the anchored plane (or cylinder when
   curvature > 0), computes a rounded-rect signed distance, feathers it, and alpha-blends the
   surround color over pixels outside the aperture.
4. Blend is `SrcAlpha / InvSrcAlpha` on the final color target with alpha = mask * opacity, so the
   surround is opaque. UEVR submits with `XR_ENVIRONMENT_BLEND_MODE_OPAQUE`. Depth and UI swapchains
   are untouched.
5. Settings are per game in UEVR's `config.txt` as `WindowMode_*` keys: Enabled, LockAspect,
   PlaneWidth (default 2.4), PlaneHeight (1.35), AnchorDistance (2.0), Feather (0.10),
   CornerRadius, Curvature, SurroundRed/Green/Blue, Opacity, ExternalBridgeAvailable.
6. `apply_cutscene_comfort_state(payload)` parses a text payload from the CutsceneComfort plugin
   event `CutsceneComfort.WindowMode.v1` to temporarily override the aperture.
7. Validation per the author's release notes: D3D11 + OpenXR tested in Meta XR Simulator. D3D12
   path loads in Halo Campaign Evolved but a final visual A/B is still open.

The two facts that shape everything:

- The output is two full-FOV eye images with a colored surround. It is a VR frame, not a texture
  of the window contents. It is only correct when a VR compositor displays it as projection
  layers at the eye pose it was rendered from, with reprojection.
- The mod needs a VR runtime on the PC that presents a headset and feeds it head poses.

How UEVR builds projections (matters for the plan): each runtime fills
`raw_projections[eye] = {tanLeft, tanRight, tanUp, tanDown}` and `projections[eye]` in
`update_matrices` every frame (`src/mods/vr/runtimes/OpenXR.cpp` ~line 465-570,
`OpenVR.cpp` ~line 181). `VR::get_projection_matrix(eye)` (`src/mods/VR.cpp:3063`) returns the
runtime's matrix. Asymmetric frusta are already supported because OpenXR FOV is asymmetric.

---

## 5. How the Quest 3 demo most likely works (unverified until the video is reviewed)

The Quest presents itself to the PC as a normal PCVR headset through Virtual Desktop, Steam Link,
or Link. UEVR renders full-FOV stereo with the window mask. The Quest compositor displays the eye
images with reprojection. The real room is Virtual Desktop's chroma-key passthrough, and the
fork's "Surround Color" is set to the key color. No Moonlight, no flat panel, no custom headset
app. The fork's author validated exactly this pipeline in the Meta XR Simulator.

The Vision Pro equivalent of that path is a real VR runtime bridge: Apple Foveated Streaming
(CloudXR) or ALVR. With Moonlight as the transport the headset is not a VR runtime, so the PC side
must render differently (section 7).

---

## 6. Candidate architectures

### A. Apple Foveated Streaming + NVIDIA CloudXR + UEVR in OpenXR mode (no Moonlight)

- visionOS 26.4 added `FoveatedStreaming`. `FoveatedStreamingSession.connect()` discovers a PC
  endpoint over mDNS, pairs by scanning a QR code, then streams an OpenXR app rendered through
  CloudXR. Xcode has a "Foveated Streaming App" template. Apple's `apple/StreamingSession` has the
  Windows endpoint reference (`StreamingSession-WindowsApp`) and the protocol is documented.
- Receiver: `ImmersiveSpace(foveatedStreaming: session) { RealityView { ... } }`, progressive
  immersion. RealityKit content composites with the stream; if the OpenXR app supplies depth they
  occlude each other.
- Clear XR (indie, TestFlight) proves third parties can ship this and reports needing an RTX 40/50
  GPU. Josh has an RTX 4080.
- Message channels exist for custom data, and the session exposes conversion between OpenXR and
  ARKit coordinate frames.
- Open item: passthrough outside the window. UEVR submits opaque; to see the room the fork must
  write alpha 0 in the surround and UEVR must use `XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND`.
  Whether CloudXR honors alpha needs a hardware test.
- Pros: correct display of the fork's output for free, lowest latency, platform reprojection.
  Cons: no Moonlight, NVIDIA only, CloudXR licensing, visionOS 26.4 minimum.

### B. Moonlight + VRto3D + UEVR in OpenVR mode, showing the eye images as-is (rejected)

- VRto3D renders a 2W x H side-by-side frame to a chosen monitor, accepts OpenTrack 6DoF poses over
  UDP (default port 4242, binds `INADDR_ANY`, so LAN senders work), disables motion smoothing, and
  has an `async_enable` toggle. Sunshine captures the display, Moonlight streams it.
- The visionOS client would have to head-lock the per-eye halves and reproject them with the
  render-time pose. Moonlight has no per-frame pose channel. This is rebuilding a VR compositor
  without platform help at 60-100 ms latency. Rejected.

### C. Moonlight + VRto3D + UEVR fork with an off-axis "portal" projection (chosen for the Moonlight route)

- Change what UEVR renders instead of masking it. Per eye, per frame, render through the anchored
  window rectangle with a generalized (asymmetric) perspective projection, the CAVE / zSpace /
  Looking Glass technique. The rendered image then is the window content.
- On visionOS the SBS frame goes on a room-anchored quad of the same physical size and pose, left
  half to the left eye and right half to the right eye via Camera Index Switch. The window never
  lags the head because RealityKit places the quad; latency only shows as slightly stale in-window
  parallax.
- Head pose still flows Vision Pro -> OpenTrack UDP -> VRto3D -> UEVR every frame.

### D. ALVR for visionOS

Full VR streaming to SteamVR. Not Moonlight, not RealityKit. Use only as a latency/comfort baseline.

---

## 7. Chosen design details (option C, with A as the fast proof)

### PC side

```
Vision Pro head pose ---UDP OpenTrack, port 4242---> VRto3D (SteamVR HMD driver, SBS output)
                                                        |
                        UEVR fork (OpenVR runtime, portal projection per eye)
                                                        |
                  Sunshine captures the SBS display ---> Moonlight video + audio + gamepad input
Vision Pro recenter / W x H x d ---UDP control msg---> UEVR fork WindowMode
```

UEVR fork changes (private fork of `elliotttate/UEVR-6DOF-Window`):

1. Keep the anchored rectangle code in `WindowMode.cpp` (origin, right, up, back, width, height,
   distance, recenter).
2. Add a per-frame projection override. For each eye: eye position `E` from
   `hmd_transform * get_eye_transform(eye)`; express `E` in the window frame; `d_e` = perpendicular
   distance from `E` to the window plane; tangents
   `tanLeft = (winLeft - E.x)/d_e`, `tanRight = (winRight - E.x)/d_e`,
   `tanUp = (winTop - E.y)/d_e`, `tanDown = (winBottom - E.y)/d_e`. Override the four tangents the
   runtime would have supplied and override the per-eye view rotation to the window's orientation
   (eye looks perpendicular to the window, not along head forward). Clamp when `d_e` gets small.
   Hook candidates: after `update_matrices` in the runtime, or in `VR::get_projection_matrix` and
   the view-matrix path used by `FFakeStereoRenderingHook`.
3. The mask pass becomes optional (feather only), since the whole eye image is the window.
4. Add a tiny UDP control socket in `WindowMode`: `recenter`, `set W H d`. This lets the headset be
   the source of truth for window placement.

VRto3D settings: `async_enable` off, `display_index` = the display Sunshine captures (a virtual
display driver is fine), `render_width/height` per eye (start 1920x1080 or 2560x1440), IPD set to
the user's IPD (visionOS has no IPD API), OpenTrack enabled, tracking filter off.

Sunshine: capture the SBS display, HEVC or AV1, 90 fps target, bitrate 50-100 Mbps on LAN.

### Vision Pro side (fork of `RikuKunMS2/moonlight-ios-vision`, branch `vision-testflight`)

What is already there (verified in source):

- `Moonlight Vision/DrawableVideoDecoder.swift`: VideoToolbox decode, `CVMetalTextureCache`,
  writes into `TextureResource.DrawableQueue` (RealityKit texture), NV12 to RGB via Metal.
- `Moonlight Vision/RealityKitStreamView.swift` (~4000 lines): ImmersiveSpace stream view, flat and
  curved meshes, `isSBSVideo` / `videoMode == .sideBySide3D`, loads `/Root/SBSMaterial` from
  `SBSMaterial.usda`.
- `Moonlight Vision/SBSMaterial.usda`: shader graph with `GeometrySwitchCameraIndex` (Camera Index
  Switch) sampling the left half (`uvtiling 0.5,1`) and right half (`uvoffset 0.5,0`).
- Immersive environments, reactive ambient lighting, HDR, AV1, SharePlay, gamepad input,
  "Low Latency Streaming" entitlement in `Moonlight XrOS.entitlements`.

What to add:

1. `ARKitSession` + `WorldTrackingProvider.queryDeviceAnchor(atTimestamp:)` while the immersive
   space is open. Convert the device transform to OpenTrack (x, y, z in centimeters, yaw, pitch,
   roll in degrees, six little-endian doubles) and send over UDP at display rate. Watch the
   handedness: visionOS/ARKit is right-handed Y-up, VRto3D maps OpenTrack into SteamVR's frame in
   `OpenTrackThread` (`vrto3d/src/hmd_device_driver.cpp` ~line 887-1000); verify axis signs on
   hardware.
2. Recenter flow: capture the device anchor, place the SBS quad at `head + forward * d` with size
   `W x H` in meters, send `recenter` and `W H d` to the UEVR fork. Because VRto3D's tracking space
   is fed only by these poses, both sides anchor from the same pose and stay aligned.
3. Settings UI: PC address, OpenTrack port, W, H, d, IPD hint.
4. Keep passthrough (mixed immersion) so the real room surrounds the window.

Note: visionOS requires an open `ImmersiveSpace` for head pose. This is fine; the RealityKit stream
view already runs in one, passthrough stays on, and SwiftUI windows coexist with it. Camera Index
Switch renders mono in the simulator; test eye assignment on device.

### Alternative fast proof (option A)

1. Xcode "Foveated Streaming App" template, or Apple's "Creating a foveated streaming client on
   visionOS" sample.
2. Build `apple/StreamingSession` `StreamingSession-WindowsApp` on the PC with CloudXR SDK.
3. Run UEVR fork in OpenXR mode against CloudXR's runtime. Recenter the window.
4. Fork change for passthrough: write alpha 0 in the surround and set
   `blend_mode = XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND` in `src/mods/vr/runtimes/OpenXR.hpp/.cpp`.
5. Add RealityKit UI around the stream in the `RealityView`.

---

## 8. Decisions already made (2026-09-06)

| Question | Decision |
|---|---|
| Transport | Moonlight preferred. Foveated Streaming acceptable as fallback or first proof. |
| PC GPU | RTX 4080 16 GB. Qualifies for CloudXR. |
| UEVR changes | Yes, private fork is fine. |
| Input | Bluetooth gamepad only. No motion controllers. |
| Surround | Real room via mixed-immersion ImmersiveSpace. |
| Client base | moonlight-ios-vision (GPL-3, Swift/ObjC, RealityKit mode). |
| Curved window | Not in v1. |
| Metal | Acceptable where RealityKit falls short. |
| Product shape | Playable 3D diorama window, not immersive VR. |

---

## 9. Open items and blockers

1. **Review the @Azadux demo video.** It is at `C:\Users\josh2\Downloads\ssstwitter.com_1788674213549.mp4`
   on Josh's PC. It was not reachable from the cloud session. Attach it to the next session or
   commit a short clip under `Documentation/reference/`. Check: real passthrough or rendered room,
   window edges fixed as the head moves, chroma-key surround, any UI that identifies the bridge
   (Virtual Desktop, Steam Link, Link, custom app), whether the game view looks like a portal or a
   full VR frame with a mask.
2. **Create the private forks** and share the repo names:
   `elliotttate/UEVR-6DOF-Window` (required) and `RikuKunMS2/moonlight-ios-vision` (Moonlight route).
3. **Hardware tests that decide details:** CloudXR alpha handling (option A passthrough), real
   Sunshine -> Moonlight -> VideoToolbox -> DrawableQueue latency at 2x1920x1080 and 2x2560x1440 at
   90 Hz, tracking-space drift between visionOS and the OpenTrack-fed runtime, OpenTrack axis
   signs, D3D12 stability of the fork.
4. **Licensing:** moonlight-ios-vision and this repo are GPL-3. UEVR is MIT. VRto3D is MIT.
   CloudXR has NVIDIA's license terms.

---

## 10. Build plan

1. **PC only.** Install SteamVR + VRto3D + Sunshine, run the unmodified fork in OpenVR mode, drive
   VRto3D with a Python OpenTrack sender (six doubles over UDP to port 4242), view the SBS stream in
   any Moonlight client. Confirms the toolchain and that the window responds to pose.
2. **UEVR fork.** Implement the portal projection override and the UDP control socket. Validate
   by sweeping eye position from the Python sender: window edges must stay fixed in the SBS output
   and the parallax inside must change.
3. **visionOS.** Fork moonlight-ios-vision, add the pose sender and recenter flow, size the SBS quad
   from W x H, confirm eye assignment on device.
4. **Tuning.** Measure end-to-end latency, tune codec and bitrate, compare comfort against option
   A, decide whether to ship the Moonlight route or the Foveated Streaming route.

Suggested first task for a new session: phase 2, because it carries the technical risk and can be
validated on the PC alone.

---

## 11. Quick reference: source locations

UEVR fork (`elliotttate/UEVR-6DOF-Window`):

- `src/mods/WindowMode.hpp` / `.cpp` (969 lines): settings, anchor, HLSL mask shader,
  `build_constants`, `draw_d3d11`, `draw_d3d12`, `apply_cutscene_comfort_state`.
- `src/mods/VR.cpp:3063` `VR::get_projection_matrix`; `VR.hpp` `m_raw_projections`.
- `src/mods/vr/runtimes/OpenXR.cpp` ~465-570 `update_matrices` (tangents -> projections);
  `OpenXR.hpp:195` `blend_mode`; `OpenXR.cpp:1889` `frame_end_info.environmentBlendMode`.
- `src/mods/vr/runtimes/OpenVR.cpp` ~181 `update_matrices`.
- `src/mods/vr/FFakeStereoRenderingHook.cpp`: where UEVR injects its stereo projection into UE.
- `RELEASE_NOTES_v0.2.0-alpha.1.md`: validation boundary.

VRto3D (`oneup03/VRto3D`):

- `vrto3d/src/hmd_device_driver.cpp`: `OpenTrackThread` (~887), `INADDR_ANY` bind (~917, ~944),
  `SetAsync`, motion smoothing disable (~415), `StereoDisplayComponent::GetProjectionRaw` (~2232).

moonlight-ios-vision (`RikuKunMS2/moonlight-ios-vision`, branch `vision-testflight`):

- `Moonlight Vision/DrawableVideoDecoder.swift`, `RealityKitStreamView.swift`,
  `SBSMaterial.usda`, `MoonlightVisionApp.swift`, `Environment/ImmersiveEnvironment.swift`,
  `Limelight/` (shared protocol code), `Moonlight XrOS.entitlements`.

Apple (`apple/StreamingSession`):

- `StreamingSession-WindowsApp/` (endpoint: mDNS, TCP/JSON session, QR pairing, launches CloudXR
  server), `StreamingSession-OpenXRSample/` (OpenXR cube app), `README.md`.

This repo (`XXJones21/Moonlight-SpatialSDK`):

- `Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/ImmersiveActivity.kt`,
  `MoonlightConnectionManager.kt`, `MoonlightPanelRenderer.kt`, `app/src/main/jni/native_decoder.c`.
