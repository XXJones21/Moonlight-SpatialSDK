UEVR 6DOF Window Mode - v0.2.0-alpha.1

Fork commit:
  fb31341e860b15e116a15123820c95f044ff0a0f

Based on UEVR nightly 01139:
  nightly-01139-74b76bc9428a906cbdc69de3ebc1905fd0e9cc57
  upstream source commit 74b76bc9428a906cbdc69de3ebc1905fd0e9cc57

USE
  1. Inject UEVR normally.
  2. Open UEVR's in-game menu.
  3. Select "6DOF Window" in the sidebar.
  4. Enable the mode and adjust its exact physical width, height, distance,
     feather, rounded corners, symmetric curvature, and surround color.
  5. Use "Recenter Window In Front Of Me" to capture a fixed room-space
     anchor. Later head rotation and translation change your view of the
     window instead of moving the window with your head.

The feature is off by default and saved independently for each game. It masks
only the outside of the final submitted color images. UEVR continues rendering
the visible center in normal stereoscopic 3D with normal positional parallax.

CUTSCENE PLUGIN BRIDGE
  The standalone CutsceneComfort plugin can send the generic
  CutsceneComfort.WindowMode.v1 event to apply these controls only while a
  cutscene is active. This public fork contains no game-specific plugin
  allowlist.

VALIDATION
  - Release x64 build: passed.
  - D3D11 OpenXR load and visual on/off compositor A/B: passed.
  - Fixed-anchor movement, centered curvature, exact dimensions, feathering,
    rounded corners, and surround color passed in D3D11/OpenXR with Meta XR
    Simulator.
  - D3D12/OpenXR initialization reached a live session in Halo Campaign
    Evolved, but a trustworthy submitted-frame visual A/B remains open.

Source and current limitations:
  https://github.com/elliotttate/UEVR-6DOF-Window
