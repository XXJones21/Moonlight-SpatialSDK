# Apple portal implementation

Phase 3 source: a mixed ImmersiveSpace owns WorldTrackingProvider and a room-fixed
RealityKit entity. The independently dismissible Settings window has no
onDisappear teardown. The portal shelf remains reachable by tapping its plane.

Placement uses the first tracked device transform: horizontal forward 1 m,
vertical offset -0.1 m, upright yaw only. Height starts at 0.7 m. Corner resizing
keeps center/aspect and clamps physical width to 0.5–10 m; Resize restores base
dimensions only. Dragging translates in scene coordinates; two-hand rotation
uses world Y. No room-mesh, plane detection, wall snapping or anchor persistence.

ARKit observations and RealityKit entities stay on MainActor. A separate serial
Network queue replaces pending motion and retransmits explicit reset controls.
Tracking loss advances the epoch and pauses output. Recovery requires an explicit
yaw-only Recenter because head motion cannot establish origin continuity.
Device-to-head offset and nominal
64 mm eye separation are calibration values, not measured eye transforms.

The static SBS checkerboard has eye labels and four corner numbers. The shader
graph uses the donor camera-index switch, per the Apple stereo sample. Rounded
corners come from the client mesh rather than the original PC full-FOV mask.

Sources:
- https://developer.apple.com/documentation/visionos/displaying-a-stereoscopic-image-in-visionos
- https://developer.apple.com/documentation/visionos/displaying-a-3d-object-that-moves-to-stay-in-a-person's-view
- https://developer.apple.com/documentation/visionos/associating-a-window-with-an-immersive-space

The associated-window sample intentionally couples dismissal; this app uses
separate lifetime ownership to preserve the Quest Settings semantics.
Settings uses a value-based WindowGroup with the stable value `main`, so repeated
open requests reuse the same window. Return to Home opens it before dismissing
the space. See [WindowGroup](https://developer.apple.com/documentation/swiftui/windowgroup)
and [presenting windows and spaces](https://developer.apple.com/documentation/visionos/presenting-windows-and-spaces).
Build, gesture feel, stereo eye order, UV orientation, tracking recovery, SDK
availability and hardware operation are **unverified**, deferred by user request.

Copied asset: Moonlight Vision/SBSMaterial.usda from donor
fb349830ac980ab73dbd653b5b9c813c3b249198, GPL-3.0 project. Original notices and
the full donor license accompany the source import in phase 4.
