# D3D12 portal UI restoration — 2026-09-06

## Scope and cause

Josh confirmed both the game HUD/menus and the UEVR settings overlay disappear
when Portal Output is enabled, despite being visible in ordinary UEVR/VRto3D.
The direct exporter copied only the scene texture. The separate Slate/game UI
and framework ImGui target were submitted to SteamVR but absent from the
Sunshine capture. This is separate from the game's outstanding eye mismatch.

Revision `e0d3fa8` adds both layers to the **D3D12** exporter used by the test
game. D3D11 separate-UI composition remains outside this change.

- HUD uses the retained OpenVR UI copy. The original game UI target has already
  been cleared by the time PortalOutput runs; sampling it would miss the HUD.
- UEVR's configurable alpha inversion has already been applied to that copy.
  Both layers use premultiplied alpha blending, HUD first and framework second.
- Each layer is aspect-fitted into each eye's content rectangle. The scene is
  copied unchanged. UI is flat on the portal plane, not a second world-space
  SteamVR quad. VR UI distance/wrist settings do not move this flattened copy.
- Closed framework UI is omitted; absent game UI is omitted. Framework timing
  follows the existing runtime path: the previous framework texture is used
  before Framework renders its next menu image on the same queue.
- Per-slot textures, descriptors and pipeline objects remain alive through the
  existing output fence. No new GPU wait, scene readback or source texture write
  is introduced. The output alone transitions for blending; metadata is written
  afterward and all UI draws are clipped above the final 16 rows.

## Verification

`portal_ui_test.cpp` compiles the actual `PortalUI12.hpp` compositor and checks
GPU-readback pixels using the WARP adapter. It sends no poses or network traffic
and does not inject or interact with the running game.

- Before implementation, the scene-only baseline failed with
  `HUD absent or alpha incorrect in left eye`.
- After implementation: **24 frames pass**, using three reused compositor slots,
  RGBA/BGRA output, HUD transparency, menu-over-HUD order, closed/reopened menu,
  absent HUD, resized UI sources, preserved aspect and every metadata pixel.
- D3D12 debug layer enabled; no error/corruption messages in this offline run.
- Existing output lifetime regression passes canonical COM identity, repeated
  buffer reuse, pending resize retention and fence-generation replacement.
- Existing secondary-Present regression passes 32 cases plus main-path checks.
- Existing projection regression passes 72 float/double cases.
- Independent code review found no material issues in UI alpha/state handling,
  submission order or fenced ownership. Release backend build passes.

The pixel test waits between frames. It does not qualify overlapping live GPU
work, game-specific Slate contents, headset legibility, menu interaction or
remaining scene glitches. Those require the fresh-injection test below.

Reproduce with Visual Studio 2022 Build Tools in the actual Windows user context:

```powershell
cmake -S Documentation/visionOS-6DOF/evidence/windows/portal-ui -B External/local-validation/portal-ui-warp -G "Visual Studio 17 2022" -A x64 "-DCMAKE_GENERATOR_INSTANCE=C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools"
cmake --build External/local-validation/portal-ui-warp --config Release
& .\External\local-validation\portal-ui-warp\Release\portal_ui_test.exe
& Documentation/visionOS-6DOF/evidence/windows/portal-ui/run-regressions.ps1
```

## Live checkpoint — pending

At 18:29 local time, game/injector closure was verified, all eight package hashes
and the embedded revision passed, and the complete saved profile was backed up.
All 24 baseline settings already matched; the profile was not rewritten. The
new injector was launched from `External/local-validation/UEVR-portal-e0d3fa8`.
See `profile-transition.json` and `injector-start.json`. Actual injection and UI
acceptance remain pending; the existing PortalHost and SteamVR are running.

Do not replace an injected backend or rewrite the game's saved profile while
the game/injector are running. Close both before selecting the new package.
Retain `UEVR-portal-67d9785` for rollback.

1. Confirm saved **1280x720 per eye**, Portal desktop `(2560,0)`, Portal Output
   on, room-anchored window/diagnostics off, and the supported Native Stereo
   baseline. Check live scene allocation is **2560x720** after injection; the
   earlier width experiment last logged 5120x720 despite saved defaults.
2. Start the new injector package, load the game and reconnect the existing
   2560x736/60 Sunshine stream. Verify the loaded DLL revision and successful
   paired output. `ui_composition_prepare_failed` is a new explicit failure
   reason if a UI texture/pipeline cannot be prepared.
3. Check game HUD, game pause/menu and UEVR overlay independently in the direct
   SBS window and the headset. Open/close each, confirming no stale image,
   opaque background or doubled UI. Verify Xbox navigation separately.
4. Toggle Portal Output off/on once; check the original VRto3D view and restored
   direct output. Record any new flickering independently of pre-existing eye
   lighting/scene artifacts. Do not change rendering method during this test.
5. Continue with [Mac panel sizing](../../../Mac-handoff-panel-size-and-ui.md).
   Keep the pixel resolution and 60 fps fixed while enlarging the physical panel.

## Subsequent live result and recovery investigation

Injection is now verified from the game log at 18:35:09: committed revision
`e0d3fa877f4a9bcf8eb66a8ae7b6b1833a06e904`. Live allocation returned to
**2560x720**. Josh reports the overlay is visually clean and the UI/menu appear
in the center of the panel. Centered aspect-fit composition is intentional;
this does not establish menu interaction or both-eye scene quality.

Josh reports Windows mouse hover highlights items but left-click does not work,
followed by a gray headset panel that did not recover on reconnect. Inspection
found the game desktop HWND and portal HWND both at `(2560,0)-(5120,736)` on
the virtual capture display. The game desktop window was moved to physical
`(80,80)-(1680,1080)` and foreground activation succeeded. The direct SBS window
remains on the virtual display. It is a display-only copy with no mouse-click
forwarding; use the real game desktop UEVR menu for Windows interaction.
Confirmation that this move restores clicking remains pending.

The preserved output log has 102 rate-limited successful samples and 55
unpaired/stale samples. It reports successful paired output through 18:37:05,
then stale tracking, then successful output again from 18:37:11–21 following
reconnect. A later reconnect also produced successful output at 18:40:38–42,
with both windows in their separated positions and the SBS window visible.
Sunshine records a disconnect at 18:41:06. No post-startup renderer/GPU failure
was found in the inspected interval; these records do not prove correct decoded
pixels or close headset recovery. See `live-recovery-investigation.json`.

The manually captured `reconnected-source.png` was actually taken after the
18:41:06 disconnect and contains the virtual desktop/taskbar, not an active
portal frame. Its failed metadata parse is expected in that state and must not
be cited as an active-source corruption. `watch-source.py` waits for a visible
correctly positioned portal with a recent successful output log, then captures
three real source frames and parses their metadata. It does not send poses or
input. Its captures remain in ignored local validation storage.

The subsequently supplied recording
`C:\Users\josh2\Downloads\ScreenRecording_09-06-2026 17-47-59_1(2).mov`
is byte-identical to the original 17:47 recording (SHA256
`290a5352809850d80b07064bf175e2e9818a724800a2ccb77b4fc1b5c44735a4`).
It cannot validate the new UI build. Josh clarifies that headset screen recording
shows only the left eye; the right eye still has the pre-existing UEVR glitches.
Treat all such recordings as left-eye evidence only. A current UI recording,
Windows click confirmation and sustained headset recovery remain pending.

### Corrected headset recording received

`C:\Users\josh2\Downloads\ScreenRecording_09-06-2026 18-40-14_1.mov`
is the current UI clip, 3.998 seconds, screen-recorded 1280x720 H.264 at reported
29 fps. SHA256:
`9d694eb78b4b6a924166fe56989450e9742fcedfd70096cf91cb88c34daf58ee`.
Frames inspected at 0.3, 1.6 and 3.4 seconds show both the game's main menu and
the UEVR Runtime settings overlay on the headset panel. The overlay title shows
the new e0d3fa8 build. This verifies **left-eye visibility of both menu layers**.
The sampled frames do not show a gray-panel transition or prove working clicks,
in-game HUD behavior, right-eye quality or sustained recovery. Recording frame
rate is not the negotiated 60 fps stream rate.

The three-minute source observer armed at 18:43:24 timed out with zero captures
because no qualifying active source frame was seen during its window. No active
desktop metadata validation is claimed from that observer. Keep further live
capture aligned with a user-confirmed open headset connection.
