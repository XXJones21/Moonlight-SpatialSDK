# Flashing before pose input — 2026-09-06

Status: user reports intense flashing after reinjection; planned sender test
paused without sending any new poses. Asked user to turn Portal Output off and
observe whether ordinary stereo stabilizes, without restarting or changing other
settings.

The current game PID 62348 loaded UEVRBackend.dll and openvr_api.dll from the
expected UEVR-portal-0b59830 package. Native Stereo remains RenderingMethod=0;
aim/movement=0, decoupled pitch and controllers off. Portal Output is true,
diagnostics and Room-Anchored 6DOF Window false in the captured profile. The game
owns 127.0.0.1:4244; SteamVR owns 0.0.0.0:4242. No PortalHost or pose sampler is
running and no new renderer probe was executed after this injection.

The screenshot shows HUD over largely dark stereo views; temporal flashing is
the user's observation, not something established from one still image. The
overlay-disabled setting was verified in the preceding checkpoint, although the
overlay DLL remains loaded. The prior crash's recursive Present stack does not
establish the cause of this flashing.

## Source finding requiring verification

FFakeStereoRenderingHook::calculate_stereo_projection_matrix has a portal-enabled
path which initializes only output[3][2] when no original projection hook is
available. Later in the function, it fills the complete matrix only when a valid
PortalFrame is available; otherwise the portal branch returns out immediately.
Consequently this branch can return partially initialized output while awaiting
the first state or after lease expiry. The current session has received no valid
external state, and uses the logged nonstandard stereo-hook setup. Whether this
exact projection branch is executing still needs runtime confirmation.

Separately, OpenVR::update_poses substitutes identity and marks the HMD pose
invalid without a valid PortalFrame, while view/projection hooks skip ordinary
camera processing in portal mode. These are additional differences from ordinary
stereo and should not be conflated with the overlay Present recursion.

No production source fix has been made for this observation. First obtain the
Portal Output off comparison. Then establish a deterministic, fully initialized
projection fallback if this path is confirmed, while preserving the exporter
and client's suppression of invalid/stale portal frames. Never bypass metadata
or lease validation to make the preview visible.
