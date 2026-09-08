# Native Stereo Fix support for the Windows portal

Status: user approved on 2026-09-08; implemented and validated offline at 4007df4; live staging and acceptance pending.

## Problem and evidence

Hogwarts Legacy has substantially mismatched eye lighting in plain Native
Stereo, including with Portal Output disabled. Josh reports matching eyes in
AFR and in Native Stereo with Native Stereo Fix enabled. The latter was checked
in the main menu and loaded common-room scene, with no reported flickering.
Use Same Stereo Pass made no visible difference in the comparison.

The current portal rejects Native Stereo Fix. Enabling Portal Output therefore
produces no accepted SBS frame. Its camera-ownership branch also bypasses
ordinary tracking, explaining why an unsupported combination can appear frozen.

## Scope and alternatives

Implement D3D12/OpenVR Native Stereo Fix support through the existing direct
portal exporter. Keep 1280x720 per eye, 2560x736 including metadata, and 60 fps.
Preserve the existing client protocol and Sunshine capture configuration.

The recommended approach copies the two engine eye sources directly. Copying
SteamVR/VRto3D presentation would lose the direct-source projection and metadata
contract. Adding AFR instead would introduce temporal eye pairing despite an
already working Native Stereo Fix reference. Merely removing the rejection is
incorrect because the second eye resides in a different render target.

This change does not add AFR, D3D11 Native Stereo Fix support, higher resolution,
90 fps, fog overrides, controller scripts, or the original flicker workaround.
Existing ordinary Native Stereo support remains available.

## Source selection and output

Extend the internal D3D12 exporter input to describe the eye source resources,
copy rectangles, expected resource states, and rendering-frame identity.
Ordinary Native Stereo selects the left/right halves of the existing double-wide
source. Native Stereo Fix selects the left-eye region from the game target and
the corresponding region from the separate scene-capture target, matching the
working OpenVR submission path in D3D12Component.cpp.

Validate dimensions, format compatibility, resource device identity and copy
bounds before recording any output. A missing or incompatible second source
must suppress output with a specific diagnostic, never fall back to the stale
right half of the ordinary game texture.

Copy both eye regions into the private output backbuffer on the existing ordered
game command queue. Preserve source states. Retain both resources in the output
slot until its fence completes. Preserve canonical COM identity checks, fence
generation handling, and nonblocking busy-slot/recreation behavior. Reuse the
current HUD/framework composition and append the existing 16-row metadata strip
after both eye copies. No production GPU readback is introduced.

## Frame and projection association

Latch the rendering mode alongside the existing immutable portal pose/geometry
state. Associate the Native Stereo Fix two-view render submission and both eye
sources with that same engine frame. Track actual paired submission in addition
to the existing view/projection observations: merely having two textures or two
projection callbacks is insufficient when scene-capture initialization can
render only one view.

Audit the existing Native Stereo Fix pose-queue clone and scene-frame decrement
against OpenVR's render-thread frame identifier. Carry the original portal frame
identity through the render handoff explicitly where those identifiers differ;
do not select the newest portal state at export time. Both views must retain
their own off-axis projection, including when Use Same Stereo Pass changes the
engine's stereo-pass labels. Eye identity must not depend solely on that label.

Invalidate source-pair evidence on mode changes, resource recreation, reset,
session/epoch changes, and incomplete submissions. Reject stale frame IDs or
mismatched modes. Keep the existing live-state lease check. The compatibility
guard may admit D3D12 Native Stereo Fix only once these conditions are enforced;
D3D11 and other unsupported configurations remain rejected.

## Validation and rollout

1. Add offline failing tests for two distinct eye sources, missing/right-eye
   mismatch, duplicate or stale pairing, mode switches, and initialization with
   only one submitted view. Exercise production pairing/copy helpers.
2. Run D3D12 WARP pixel tests with distinct left/right content, UI composition,
   metadata preservation, supported color formats, and resource reuse under
   fences. Enable the D3D12 debug layer and verify source-state restoration.
3. Run existing projection, Present-recursion, output-lifetime and UI regressions;
   build Release and obtain an independent code review before staging.
4. Package a new revision separately from e0d3fa8 and export the patch with hash
   provenance. Preserve the working reference configuration. Close game/injector
   only when the reviewed replacement package is ready to stage.
5. Josh tests Native Stereo Fix plus Portal Output on: both-eye lighting, live
   head tracking, stereo depth/parallax, HUD/menu, output toggle and reconnect
   recovery. Verify advancing source metadata and matching frame/status evidence.
   Do not send synthetic poses to the live game.

Native Stereo Fix in VRto3D is the reference, not evidence that the new portal
mode already works. Live testing remains the final acceptance gate.
