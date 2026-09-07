# Controller and desktop UI checkpoint — 2026-09-06

The Xbox controller is paired to Vision Pro. The user reports input worked in
the earlier client panel and stopped in the grey 6DoF panel without an Xcode
change. Do not attribute this to a new client build or VRto3D takeover without
evidence.

## Existing client behavior to inspect on Mac

`Moonlight-6Dof-Vision/Moonlight-6Dof-Vision/Portal/PortalSessionCoordinator.swift`:

- start() calls hide().
- hide() sets panel opacity to zero and calls moonlight.gamepad.setActive(false).
- The watchdog calls hide() for a stale frame gate, unavailable source, or old
  host status in 6DoF mode.
- revealIfReady() enables the gamepad only after fresh frame and source gates pass.

`Streaming/PortalGamepad.swift` retains the connected controller name independently
of active forwarding; send() returns when active is false. This existing code
explains how a connected controller can stop forwarding in a grey panel without
an Xcode change. Device-side logging is still needed to verify the running client's
specific transition. Do not bypass video metadata validation to regain input.

For the Mac session: log active-forwarding transitions and their reason alongside
frame/source gate state. If controls are intended to work during video recovery,
separate explicit game-input focus/session ownership from visual frame validity;
preserve release-on-stop/disconnect and deliberate focus loss. No Swift source
was modified in this Windows session.

The inspected VRto3D source polls XInput state for optional right-stick pitch/yaw
and menu/hotkeys. The inspected tracking path does not acquire an exclusive input
device. Keep right-stick pitch/yaw disabled for live AVP tracking. Their earlier
saved baseline is disabled; live flags were not independently queried here.

## Windows findings and action

Sunshine's live 17:40 session requests 2560x736x60, advertises a controller bitmap
of 1 and uses SDR HEVC NVENC on the 2560x736 virtual display at (2560,0).
This establishes negotiation, not actual button-event delivery or decoded tags.

Game PID 76928 had one visible UnrealWindow, HWND 419432882, occupying
(2560,0)-(5120,736) on the virtual display. VRto3D's window occupied physical
(0,0)-(2560,1440). No UEVRPortalSBS HWND existed in the game process, consistent
with the user being unable to enable output; saved PortalOutput was false and
the recent renderer log contained no portal-output records.

game_window.py moved only the identified game UnrealWindow to physical desktop
(80,80)-(1680,1080), brought it to foreground and confirmed it remained there
after two seconds. It did not change the configured portal capture origin.
The user must now operate UEVR on that desktop window and enable Portal Output
only; window/diagnostics remain off. Then inspect creation and placement of the
separate UEVRPortalSBS window and current output logs before diagnosing client
metadata parsing. A plain game desktop or VR mirror lacks the required frame tags
and will keep the 6DoF panel gated even if encoded video is flowing.
