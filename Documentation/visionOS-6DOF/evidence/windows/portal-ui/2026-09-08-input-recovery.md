# Input and SBS recovery investigation — 2026-09-08

The user could hover UEVR options but Windows left clicks did not activate them.
SBS also stopped before the volumetric-fog test could be performed. No fog or
stereo settings were changed, and the lighting tests remain pending.

## Observed state

- Actual game PID 51664 loaded `UEVRBackend.dll` and `openvr_api.dll` from
  `External/local-validation/UEVR-portal-e0d3fa8`.
- The visible `UnrealWindow` (HWND 14552286) had reopened at
  `[2560, 0, 5120, 736]`, exactly overlapping the dedicated capture bounds.
- `UEVRPortalSBS` (HWND 33953088) was hidden when inspected after disconnection.
- Sunshine recorded HEVC capture at 2560x736 and client-requested 60 fps for
  the 10:44:52 connection, followed by disconnection at 10:46:10. This does not
  establish what caused the preceding loss of SBS.
- PortalHost remained running as PID 46808. Its live pose trace last changed
  at 10:46:09. The game remained running; process survival alone does not prove
  healthy rendering.
- The current profile's `log.txt` was empty despite saved log level 2. The
  reason is unknown. Historical logs cannot establish this run's source status.
- Saved settings still select Native Stereo, Native Stereo Fix off,
  Portal Output on, 1280x720 per eye, wrist UI off and VR controllers disabled.

## Minimal intervention and hypothesis

Moved only the actual game desktop window to `[80, 80, 1680, 1080]` using
`sunshine-sbs/game_window.py --pid 51664 --move`. Windows reported successful
foreground activation. The SBS window and profile were not modified.

Source inspection shows mouse position is polled independently by the ImGui
Win32 backend, whereas clicks enter via window button messages. The portal
output window uses `DefWindowProcW` and is excluded from the game hooks; it
does not forward menu input. This can explain hover without activation when
the user interacts over a presentation window instead of the game window,
but delivery to the wrong window has not yet been measured during a click.
Framework mouse emulation is gated on VR controller use and the wrist input
path is gated on wrist UI; neither is established as the cause here.

Asked the user to reconnect, try a sidebar-page click over the actual game
desktop window, and leave the session connected for inspection. Click recovery
and SBS recovery are unconfirmed. Do not mark the fog test complete.
