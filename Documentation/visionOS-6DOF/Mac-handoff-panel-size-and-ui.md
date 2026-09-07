# Mac handoff: larger portal and UI verification

## Current checkpoint

Josh confirmed actual game video and Xbox controller input on Vision Pro after
enabling UEVR Portal Output. Head pose already drives the Windows game camera.
The latest Windows follow-up adds separate game HUD/menus and the UEVR settings
overlay to the D3D12 direct SBS output. See
[Windows UI build and live-test status](evidence/windows/portal-ui/README.md).
Do not treat an offline compositor test as headset validation.

The physical panel is too small. Editing **Portal Eye Content Width** changes
the render raster, not the physical size, and broke output during the live run.
Josh reset the saved values; confirm the next live allocation is 2560x720 before
continuing. Higher resolutions and up to 90 fps are later goals. Stabilize first.

**Latest live update:** e0d3fa8 is injected and 2560x720 allocation is verified.
The corrected 18:40:14 headset recording visibly confirms both the game's main
menu and UEVR settings overlay in the recorded left eye. It does not demonstrate
click handling, gameplay HUD behavior, a gray-panel transition or right-eye quality.
Josh reports clean, centered UI/menu output, but Windows mouse hover worked
without left-click and headset video later became gray without recovering on
reconnect. The game desktop window had reopened over the SBS capture display;
Windows moved it to the physical monitor. Click/headset recovery after that
move remain unconfirmed. Windows output did resume successfully after reconnect,
so correlate source visibility/pixels with decoded-frame and host-status gates
before attributing the gray panel to a renderer crash. Preserve each failed
gate's reason, session/epoch/revision/frame identity, and texture installation
state in Mac diagnostic logs. Do not bypass validity checks to force visibility.

Josh states screen recording includes only the **left eye**. Right-eye UEVR
glitches remain present. The earlier supplied `(2).mov` was a duplicate of the old
17:47 recording; the corrected 18:40:14 recording verifies the new menu layers.
Validate the right eye in the
headset or using actual Windows SBS capture; a clean left-eye recording cannot
close stereo acceptance.

## Fixed Windows contract

- OpenVR + Native Stereo + D3D12, Portal Output on, room-anchored window and
  diagnostics off. Retain the existing supported profile restrictions.
- Eye content **1280x720**, full capture/decoded raster **2560x736**, **60 fps**,
  SDR HEVC. The final 16 rows carry two copies of frame metadata.
- Virtual display UUID `{9acddf6d-43cc-576e-9aff-0c5fc80b4cc8}`, origin `(2560,0)`,
  100% scaling. Sunshine app `UEVR Portal (SBS)`.
- Windows Ethernet `10.1.95.5`; AVP reports `192.168.0.182`; Windows observes
  the headset peer as `10.1.95.13`. Existing relay/firewall configuration works.
- Preserve metadata CRC/session/epoch/revision checks and freshness handling.
  Do not rescale the entire encoded frame or include metadata in visible eyes.
- Both UI layers are composited by Windows. The Mac should display each complete
  eye image once; it should not invent or duplicate HUD/menu layers.

## Mac implementation

Inspect the current checkout first; Windows has not edited any Swift/Xcode files.
Relevant paths below are relative to `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision/`.

1. In `Portal/PortalSceneController.swift`, centralize the physical default
   size shared by initialization and `resetSize()`. Current height is 0.7 m,
   width about 1.244 m at 16:9. Use **2.4 x 1.35 m as the proposed first device
   trial**, subject to Josh's comfort/space feedback. Preserve the current
   placement and aspect ratio. Keep the ordinary flat-panel default intact if
   the controller is shared; apply this larger default to the 6DoF portal.
2. Find the shelf action labeled `Resize`: it currently calls `resetSize()`.
   Label the reset action clearly as **Reset size**, and make actual enlargement
   discoverable through the existing corner handles. Keep controls visible
   during manipulation. Do not add a resolution setting to this flow.
3. Preserve `changed()` / `onGeometryChanged` behavior for all size changes.
   Width and height must be sent together with an advancing geometry revision;
   height stays width divided by 16:9. Reset/reconnect must not silently revert
   an actively selected size unless the user requested a reset.
4. Check `PortalSessionCoordinator`, `PortalFrameGate` and `Panels/SixDoFPanel`
   when validating resized geometry. Keep stream dimensions fixed and require
   valid frames for the new geometry before showing them. Do not loosen gates
   to conceal stale images during a resize.

## Device checks with Josh

- Record client commit/build and actual decoded 2560x736 raster at the existing
  60 fps request. Confirm both headset eyes have their respective scene image.
- Check the game HUD, pause/game menu and UEVR overlay separately. Open and close
  each; verify menu text is legible and no old overlay remains after closing.
  UEVR controller shortcuts remain L3+R3 toggle and RT shortcuts as displayed by
  UEVR. Visibility does not establish working gaze/pinch-to-mouse interaction.
- Enlarge with corner handles, reset size, then resize again. Observe an
  advancing geometry revision and restored valid presentation after each change.
  Verify the physical panel grows while encoded dimensions stay unchanged.
- Confirm Xbox input while the game is visible and after opening/closing menus.
  The controller is paired to visionOS. The current client disables gamepad
  forwarding when the portal is hidden by its validity gate; a connected device
  label alone does not prove forwarding. Avoid broad controller changes unless
  this behavior remains a problem with valid video.
- Compare a static scene and slow movement between the Windows SBS source and
  headset. Record remaining eye lighting/flickering artifacts separately from
  UI visibility and panel size; do not change several UEVR options at once.

After size/UI acceptance, stabilize the game profile and qualify tracking loss,
recenter, reconnect and sustained playback. Only then design coordinated higher
resolution/90 fps modes across render allocation, metadata, virtual display,
Sunshine negotiation, decoder and presentation. Raising a single UEVR width or
fps setting is not an end-to-end mode change.

## Prompt for the Mac session

> Read Documentation/visionOS-6DOF/Mac-handoff-panel-size-and-ui.md and the linked
> Windows UI evidence. Live portal video and controller input now work through
> Sunshine. Implement the physical portal sizing/control changes in this Mac
> checkout, keeping 1280x720 per eye, 2560x736 full raster and 60 fps fixed.
> Windows e0d3fa8 now includes game UI and the UEVR settings overlay in the encoded
> eye images, confirmed in the corrected left-eye headset recording. A gray-panel
> recovery problem and Windows mouse click handling remain unresolved;
> verify both on device and correlate recovery with Windows evidence. Preserve
> metadata and geometry freshness checks. Josh can pilot the Vision Pro and
> game. Stop at the first checkpoint requiring his direct device input, report
> exactly what to test, and keep higher resolutions/90 fps for after stability.
