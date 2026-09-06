# Quest to visionOS UI and UX parity contract

Source inspection: 2026-09-05, `moonlight-6dof-vision`, Quest source inherited from `6e213b1`. This records executable source behavior, intended user actions, and explicit visionOS adaptations. No Quest or Vision Pro visual session was run for this document; pixel/layout and gesture feel require the comparison gate below.

The user requested the closest practical one-to-one match for connection UI, settings, and freely positioning/scaling the plane. **Wall snapping/pinning is deferred.** Do not add a Snap button, wall-placement workflow, plane-detection requirement, or persistent room-anchor dependency to the current implementation.

## Source map and authority

All links point to this repository. Line numbers in the prose identify the inspected baseline and may move as source evolves.

| Source | What was traced |
|---|---|
| [AndroidManifest.xml](../../Moonlight-SpatialSDK/app/src/main/AndroidManifest.xml) | `PancakeActivity` is the launcher; default 800 x 550 dp panel |
| [PancakeActivity.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/PancakeActivity.kt) | `ConnectionPanel2D`, saved host, server cards, pairing, app list, debug column, Options, stream configuration, immersive options |
| [MoonlightPairingHelper.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/MoonlightPairingHelper.kt) | Lightweight pairing/host/app capability queries, independent of the video decoder |
| [IdentityStore.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/IdentityStore.kt) | Client identity and pinned certificate reset |
| [ImmersiveActivity.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/ImmersiveActivity.kt) | Panel creation, dimensions, shelf callbacks, settings overlay, stream/reconnect dialogs, scene and stream lifecycle |
| [PanelPositioningSystem.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/PanelPositioningSystem.kt) | Initial placement 1 m ahead and 0.1 m below eye level; horizontal heading |
| [PanelManager.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/PanelManager.kt) | Group root; `GrabbableType.PIVOT_Y` |
| [TouchScalableSystem.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/systems/scalable/TouchScalableSystem.kt) | Four corners, plane-projected drag, physical width clamp, timeout |
| [PointerInfoSystem.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/systems/pointerInfo/PointerInfoSystem.kt) | Local controller/hand ray hover, not headset gaze |
| [ButtonShelfCompose.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/panels/buttonShelf/ButtonShelfCompose.kt) | Exact control order, labels, and selected states |
| [ButtonShelfEntity.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/entities/ButtonShelfEntity.kt) | 0.9 x 0.12 m shelf, bottom attachment, own scale 1 |
| [ButtonShelfVisibilitySystem.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/systems/buttonShelfVisibility/ButtonShelfVisibilitySystem.kt) | Reveal/3 s timeout; suppression during grab/scale |
| [ScaleChildrenSystem.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/systems/scaleChildren/ScaleChildrenSystem.kt) | Child offsets updated after parent scale; shelf stays attached |
| [ImmersiveSettings.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/data/ImmersiveSettings.kt) | Four persistent effect preferences, all off initially |
| [LightingPassthroughHandler.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/systems/lighting/LightingPassthroughHandler.kt) | Dimming intent: 30% passthrough brightness while enabled |
| [MoonlightConnectionManager.kt](../../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/MoonlightConnectionManager.kt) | Real stream state, reconnect parameters, stage messages, HDR fallback, input lifetime |
| [PreferenceConfiguration.java](../../Moonlight-SpatialSDK/app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java) | Resolution/FPS defaults, format/audio mapping, automatic bitrate |

`OptionsPanelLayout.kt` and `res/layout/connection_panel.xml` contain older connection layouts. The traced active launcher uses `ConnectionPanel2D`; do not use the older always-visible Host/Port/App ID form as the new app's main screen. Commented-out desktop stereo controls and stereo depth sliders are historical code, not active parity requirements.

## 1. Launcher and connection flow

Preserve this hierarchy at the normal window size. Use native SwiftUI controls and typography, keeping the grouping, labels, action order, and relative emphasis. Android dp values are reference proportions rather than a claim of physical equivalence to Apple points.

```text
                [icon] Moonlight Connection
                --------------------------
                      status message

 [server name / Ready to connect]  [+ Connect to PC / Pair New Server]
 [host:port when paired         ]

 Application [dropdown]            Debug Info
                                  Controller: ... / No controller connected
                                  Resolution / FPS / Audio OR Not streaming
 ----------------------------------------------------------
 [Disconnect OR transient pairing/checking/retry state]
 [Options]

 Stream Configuration             (hidden until Configure Stream)
 [capability summary and status]
 Resolution / FPS / Format / Audio
 Enable HDR / Prefer Full Range
 [Apply Stream Settings]

 [Launch Immersive Mode (No Connection)]
```

The source uses a rounded, themed, vertically scrolling panel with 16 dp padding, a centered title/icon, dividers with reduced opacity, and mostly 12 dp section spacing. Server cards have equal width and a 10 dp gap. Application and debug columns share a row. Dialogs are centered over a dimmed background, usually 80% of the parent width, with rounded backgrounds, 16 dp padding, and clearly separated actions. Preserve that visual structure before adding new portal controls.

| ID | Observed behavior | Required visionOS result |
|---|---|---|
| C01 | Last host, port (47989 default), and app ID (0 fallback) are loaded. One selected server is represented; there is no server-library grid. `PancakeActivity` 72–103, 234–259. | Restore the same connection context. Keep the two-card design; don't substitute the imported Moonlight app's navigation. |
| C02 | Left card shows fetched server name, else host/placeholder; host:port appears when paired. Clicking a paired card saves host/port/app ID and starts immersive streaming. 444–483. | The card is the launch action. Pairing success alone does not start streaming. Show busy state and prevent duplicate starts. |
| C03 | Right card reads `Connect to PC`, then `Pair New Server` when paired. Opens `Connect to Server` with `IP Address`, `Port`, Cancel/Connect. 491–514, 892–977. | Same entry point, prefilled fields, action order, and keyboard usability. Portal tracking ports stay in advanced diagnostics, not this dialog. |
| C04 | Connect checks pairing first; if needed, generates a client PIN and displays `Pairing PIN` / `Enter this PIN on your server:` / OK. 358–389, 958–994. | Same PIN direction and distinct checking/pairing/paired/failed states. OK dismisses the PIN presentation; it is not proof of pairing success. |
| C05 | Successful pairing closes the PIN and reports `Paired! Click Connect to continue`. Incorrect PIN and concurrent pairing have specific retry messages. | Preserve understandable outcome/retry behavior. Retry creates a fresh attempt/PIN; stale callbacks from a previous host cannot change the current screen. |
| C06 | App list has dropdown, `Loading applications...`, error text and `App ID (fallback)` when loading fails. Saved ID is retained if present, else the first returned app is selected. 334–352, 530–571. | Preserve all states and selected application identity. Reset/refetch the list on host change; never show the old host's apps. |
| C07 | Debug column shows controller names/count, then current stream resolution/FPS/audio or `Not streaming`. 173–197, 576–622. | Preserve location and concise labels. Use live session values, not a copied snapshot of settings. Report per-eye display resolution with secondary SBS transport dimensions when relevant. |
| C08 | `Launch Immersive Mode (No Connection)` is always available. 882–887. | Open a usable preview plane and controls without pairing/network. Static stereo fixture can fill the preview; no server is required to test positioning. |
| C09 | Shelf Settings opens the same launcher UI as an overlay while VR remains active. `ImmersiveActivity` 1265–1288. | Open/reuse the same connection/settings window without dropping ARKit, video, audio, or input. Closing this settings window must leave the experience running. |

Lifecycle detail: keep launcher and settings presentation separate from the explicitly associated experience-control scene. Apple's example that closes a space when its associated window disappears must not make closing the ordinary settings overlay end the game.

## 2. Options, stream settings, and effect preferences

`Options` is a modal containing three stacked cards in this order, followed by Cancel:

1. `Configure Stream` / `Hide Stream Configuration`, subtitle `Device Capabilities`. It dismisses Options and toggles the inline configuration section; opening it starts capability loading.
2. `Reset Client Pairing`, subtitle `Clear cert & UID`. It dismisses Options, clears pairing identity/certificates, and returns to a re-pair-required state.
3. `Immersive Options`, subtitle `Enable immersive features`. It dismisses Options and opens the effect-preference dialog.

Source: `PancakeActivity` 998–1158. Keep portal calibration and transport diagnostics in a secondary advanced section rather than inserting engineering fields ahead of these actions.

| ID | Setting | Source choices/default and required behavior |
|---|---|---|
| S01 | Resolution | 640x360, 854x480, 1280x720, 1920x1080, 2560x1440, 3840x2160; UI default 1280x720. In the portal app these describe **per-eye content**, with a short helper stating that stereo streaming uses twice the width. Capability filtering uses full encoded dimensions including any metadata strip. |
| S02 | FPS | 30, 60, 90, 120; default 60. Preserve selector order; disable unavailable combinations based on the actual device, host, and display. |
| S03 | Format | auto, h264, hevc, av1; default auto. Native labels may use H.264/HEVC/AV1 capitalization. Keep the same meaning. Don't copy the Quest capability code's unconditional AV1 addition. |
| S04 | Audio | Stereo, 5.1 Surround, 7.1 Surround; default Stereo. Preserve the dropdown; spatial positioning is a separate effect preference. |
| S05 | Enable HDR | Default off; helper `Request HDR from server`. Display a specific SDR-fallback outcome when the host/device can't support the request. |
| S06 | Prefer Full Range | Default off; helper `Client output color range`. Retain this control with verified video-range handling. |
| S07 | Apply Stream Settings | Settings edit a draft. Apply persists them and shows completion feedback. Source copy says to apply before launch; the active stream is not silently rebuilt. While streaming, state that changes apply on the next connection. |
| S08 | Capabilities | Opening configuration starts loading and shows success/error/no-host states. Preserve that feedback but derive capabilities from the actual Apple decoder and host rather than the source's hardcoded summary. |
| S09 | Bitrate | No bitrate control exists in this active Compose screen. Runtime uses stored/automatic Moonlight bitrate. Keep automatic bitrate in the primary UI; an explicit override belongs in advanced diagnostics. The 50 Mbps bench profile is a test setting, not evidence of Quest UI defaults. |
| S10 | Effect preferences | `Enable spatial audio`, `Room Dimming`, `Lighting Emission`, `Reflections`, in that order. All false initially. Each switch persists immediately; Close only closes the dialog. |
| S11 | Effect activation | Shelf `Immersive` selects whether the enabled effects are active. It does **not** mean opening/closing an Apple ImmersiveSpace. Turning it off must leave tracking and gameplay running. Preserve preferences when deactivated. |

Source: `PancakeActivity` 263–297, 689–795, 838–887, 1163–1290; `ImmersiveSettings`; `ImmersiveActivity` 961–1101. The Quest implementation reads the effect preferences at activation and contains some creation-time effect decisions. On visionOS, support applying active supported effects consistently; do not recreate the video plane just to toggle an effect.

Wall-derived reflections stay listed in the audit, but their room-reconstruction backend is deferred with the wall work. Preserve the Reflections row, off and unavailable with a short explanation until implemented. Spatial audio, room dimming, and panel-local lighting do not require wall pinning. On Apple use a supported surroundings-effect preference for dimming, and verify the appearance on device; the Quest 0.3 passthrough multiplier is not an equivalent Apple parameter. [Apple surroundings effects](https://developer.apple.com/documentation/swiftui/view/preferredSurroundingsEffect(_:)).

## 3. Positioning and scaling the plane

| ID | Source behavior | Required visionOS behavior |
|---|---|---|
| P01 | `PanelPositioningSystem` uses 1.0 m distance and 0.1 m eye-level offset. It flattens the head's forward vector onto the horizontal plane and creates yaw-only orientation. No caller overrides those defaults. | Initial placement and explicit recenter are 1 m ahead along horizontal heading, center 0.1 m below the calibrated viewer position, upright. This replaces the earlier plan's fork-derived 2 m distance/full head orientation default. |
| P02 | `basePanelHeightMeters = 0.7`; width is stream aspect x 0.7. At 16:9: 1.244444... x 0.7 m. Decoder dimensions can update the aspect. `ImmersiveActivity` 129, 644–670, 680–710. | Reset/base dimensions use **one eye's content aspect**, excluding padding/metadata: height 0.7 m, width aspect x 0.7. Don't use full SBS aspect. The 2.4 x 1.35 m geometry fixture in the PC math tests remains a test case, not app startup size. |
| P03 | Root and panel use `GrabbableType.PIVOT_Y`. | Free translation, including nearer/farther, plus upright yaw pivot as the default interaction. Use native pinch-drag and a yaw manipulation affordance. Arbitrary pitch/roll controls are optional advanced behavior, not the default parity target. |
| P04 | Four rounded 0.1 m corner entities are placed at the corners and rotate with the panel. Their visual scale follows panel scale; hover refreshes a 1.5 s hide timer. | Four recognizable corner handles, aligned to the current bounds. Preserve visual proportions while respecting native minimum hit-target sizes. A corner gesture owns resize and cannot simultaneously translate the panel. |
| P05 | Resize intersects the controller ray with the panel plane, measures distance to center, divides by half the base diagonal, then clamps **width**. `TouchScalableSystem` 265–303; registration uses 0.5 and 10.0 at `ImmersiveActivity` 348. | Uniform resize around the fixed center. Keep position, depth, orientation, and aspect fixed for the gesture. Width bounds are **0.5–10.0 m**, not 0.5x–10x. At 16:9 height bounds are 0.28125–5.625 m. |
| P06 | Shelf Resize calls `updateVideoPanelScale(1.0f)`; no resize dialog/slider is opened. `ImmersiveActivity` 585–587, 2020–2029. | Preserve `Resize` label and reset action. Add accessible help `Reset panel size`. Reset dimensions only; it must not move/recenter or reset gameplay. |
| P07 | Shelf/children stay attached as the panel scales via `ScaleChildrenSystem`. | Anchor controls below the new bottom edge and move them with the portal. Preserve readable control size; do not blindly multiply the entire SwiftUI control hierarchy by video scale. |
| P08 | Panel is created locally before the stream; one creation path explicitly makes it visible while connecting. | Show local frame/preview/loading state immediately. A network stall must not remove placement controls. Live image is shown only when its geometry revision matches. |

Exact source resize semantics, using the local intersection point `(x,y)` and base dimensions `(W0,H0)`:

```text
requestedScale = hypot(x, y) / (0.5 * hypot(W0, H0))
widthMeters    = clamp(requestedScale * W0, 0.5, 10.0)
uniformScale   = widthMeters / W0
heightMeters   = uniformScale * H0
```

The transform captured at gesture start stays fixed during resize. Converting a targeted drag into portal-local plane coordinates replaces the Quest controller-ray mechanism; do not derive scaling from a world-space distance that changes when the panel rotates. [Apple gesture coordinate conversion](https://developer.apple.com/documentation/realitykit/entitytargetvalue).

### Hover and input adaptation

Quest reveals controls from controller/hand ray hover. visionOS handles gaze-based hover effects outside the app process; raw gaze/hover callbacks cannot be assumed available to drive timers. Use supported declarative hover effects for visual reveal/highlight and native targeted gestures for actual actions. A tap on the video/frame explicitly reveals the shelf/handles and provides a reliable fallback. For application-owned timers, start them from explicit interaction, never inferred gaze. [Apple hover design](https://developer.apple.com/videos/play/wwdc2025/303/).

Verify hover-only reveal using the chosen SwiftUI/RealityKit composition on device. If it cannot reproduce the Quest behavior with public APIs, record the precise difference: tap-to-reveal with the same controls and timeout. Do not quietly replace all panel controls with a permanently visible dashboard. This is a native interaction adaptation, not a reason to block the portal implementation.

## 4. Shelf, stream exit, and reconnect

The original shelf order is `Settings | Resize | Immersive | Snap | Disconnect`. The current visionOS scope is **`Settings | Resize | Immersive | Disconnect`**, centered without an empty Snap slot. Keep symbols semantically equivalent and text visible. Only Immersive has a persistent selected state in this reduced shelf.

| ID | Source behavior | visionOS acceptance |
|---|---|---|
| B01 | Shelf 0.9 x 0.12 m, own scale 1, centered below panel; local Y is `-panelHeight/2 - 0.06`. `ButtonShelfEntity` 16–69. | Same bottom-centered relationship. Compare spacing/readability at minimum/base/maximum video widths; native hit sizes take precedence over copying the source's 0.5 Compose scale literally. |
| B02 | Visible when tracking starts; hover keeps alive; 3 s inactivity hides it; hidden during grab/scale. `ButtonShelfVisibilitySystem` 14–105. | Reveal on entry/explicit interaction, keep usable during shelf interaction, hide after 3 s inactivity, suppress during active manipulation. Restore only when appropriate after release. |
| B03 | Settings launches the 2D overlay; Resize resets scale; Immersive toggles effects. | Those exact actions, with one shared session model backing the launcher and shelf. |
| B04 | Shelf Disconnect immediately calls `disconnect(showReconnection = true)`. It does not first show the separate Stream Options menu. `ImmersiveActivity` 597–600. | End the actual stream, neutralize input, retain last connection parameters, and show Disconnected. Do not introduce a new confirmation step. |
| B05 | Disconnected shows `Connection to [host] ended`, Reconnect, Return to Home, Cancel. Reconnect uses last host/port/app; Home opens launcher and finishes immersive activity; Cancel closes the dialog. 525–558, 1501–1516, 2215–2289. | Same three outcomes. On visionOS Cancel leaves a nonstreaming local scene with a reachable controls/launcher affordance, not a restarted stream. |
| B06 | Separate `Stream Options` code offers Reset Panel Size, End Stream, Cancel; `toggleDisconnectDialog` has no call site in the inspected active source. | Record as dormant UI, not a mandatory extra menu or intercepted gamepad button. Do not change normal gamepad forwarding to invent a trigger. |

## 5. Intentional corrections, not parity regressions

- The launcher-side Disconnect callback currently changes local Compose state only (`PancakeActivity` 634–640). The visionOS button must stop the shared session. Matching its label/layout does not mean copying that incomplete wiring.
- Launcher stream debug values arrive via Intent extras, and some originate from preferences. The new screen observes live negotiated/current state; settings edits cannot falsify the active-stream display.
- Host/app async requests need host/session identity checks. The source's remembered app-list state is not a safe template for switching servers while requests are outstanding.
- Scene initialization retries and menu visibility comments are implementation details. Preserve the observable placement/controls, not the exact retry count, stale hover state, or hidden-panel failure modes.
- Native eye/pinch targeting replaces Quest controllers for panel UI. Gamepad input still goes to the PC; gaze/pinch used for plane manipulation must not also send mouse clicks or gamepad actions.
- Recenter and portal calibration are new 6DOF requirements. Keep them in a compact secondary controls/advanced area; don't overload the existing Resize action or reorder the familiar shelf.

## 6. Implementation mapping

The [implementation plan](../../docs/superpowers/plans/2026-09-05-moonlight-6dof-vision.md) requires this contract at the following tasks, rather than waiting until the end to redesign the UI:

| Tasks | Contract IDs | Files added beyond the original plan |
|---|---|---|
| 3.1 | P01–P04, P08, C08, B01 | `Portal/PortalManipulation.swift`, `Portal/PortalCornerHandles.swift` |
| 4.1 | C01–C09, S01–S09 | `Connection/ConnectionViewModel.swift`, `Connection/ConnectionView.swift`, `Connection/ServerPairingSheet.swift`, `Connection/PairingPINSheet.swift`, `Settings/OptionsSheet.swift`, `Settings/StreamConfigurationView.swift` |
| 5.1 | P05–P07, B02–B03 | `Portal/PortalControlsVisibility.swift` |
| 5.2 | C09, B04–B06 | `Connection/ReconnectSheet.swift` |
| 6.1 | All C/S/P/B IDs | `Settings/ImmersiveOptionsSheet.swift`; semantic UI and geometry tests |
| 6.2 | S10–S11, dimming/audio/lighting behavior | `Effects/PortalDimming.swift`; existing planned audio/lighting adapters |

Paths in this table are relative to `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision/`. Room sensing, `WallPlacement.swift`, the Snap shelf control, and world-anchor persistence are not current tasks. Geometry and settings remain stored for the active session; basic preference persistence does not depend on room anchoring.

## 7. Comparison and acceptance procedure

At implementation time, capture the current Quest app and the new app in matching semantic states. Use the same server/app names and controlled connection responses where possible. Source inspection establishes this contract; images and hardware interaction close the visual/UX gate.

- [ ] **V01:** Empty launcher, saved paired server, application loading, app-list failure, and connected overlay. Compare the two cards, debug column, title/dividers, options position, and scroll behavior.
- [ ] **V02:** Connect to Server and Pairing PIN dialogs; incorrect PIN/concurrent pairing/retry; long host names; keyboard shown. Compare labels, action order, and return navigation.
- [ ] **V03:** Options, expanded configuration, capability failure, Apply feedback, and Immersive Options. Confirm draft-vs-applied stream settings and immediate persistence of effect preferences.
- [ ] **V04:** Initial placement: 1 m horizontal distance, center 0.1 m below viewer, upright, 0.7 m base height. Head tilt at launch must not tilt the plane.
- [ ] **V05:** Each corner resize at minimum/base/maximum width; rotated and translated planes; reset after resize. Assert width bounds, fixed center/orientation, preserved aspect, and unchanged placement after Resize.
- [ ] **V06:** Move nearer/farther, pivot yaw, release; repeat while video/network stalls. Controls remain attached and manipulation does not generate PC input.
- [ ] **V07:** Entry reveal, native hover highlight/reveal where supported, explicit tap fallback, 1.5 s corner and 3 s shelf timers, grab/scale suppression, and accessible control targeting. Document platform differences with a short recording.
- [ ] **V08:** Settings opens and closes during streaming without session/tracking loss. Apply stream settings changes the next connection; active debug information stays truthful.
- [ ] **V09:** Disconnect from shelf and overlay, Reconnect, Return to Home, Cancel; repeat after host failure. No held input, stale overlay status, or inaccessible blank scene.
- [ ] **V10:** Immersive effect toggle preserves the mixed tracking space. All four preferences start off; room-derived reflections remain unavailable in this scope; no wall permission or Snap control appears.

Capture actual differences in `Feature-parity.md` with columns: contract ID, Quest evidence, visionOS evidence, result, accepted platform adaptation. Only claim visual parity after this procedure; compiling the new UI is not sufficient.
