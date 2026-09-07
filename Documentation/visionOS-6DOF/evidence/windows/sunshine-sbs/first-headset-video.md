# First headset portal video — 2026-09-06

The user confirms enabling Portal Output made actual game frames appear on the
Vision Pro and controller input work again. This is the first reported live
Windows -> Sunshine -> visionOS portal presentation, following successful live
pose input in the opposite direction. It does not close geometry, UI, stereo
quality, latency or recovery qualification.

User recording (retained locally):
`C:\Users\josh2\Downloads\ScreenRecording_09-06-2026 17-47-59_1.mov`.
Duration 15.69 seconds, recording raster 1280x720 H.264 at reported 29 fps;
these are headset screen-recording properties, not the negotiated Sunshine
stream (2560x736/60 HEVC). Extracted frames at 3, 8 and 12 seconds show the
game character and room displayed on a rounded rectangular passthrough panel.
Visible scene artifacts remain; these samples do not establish their precise
rendering or codec cause or independently verify both headset eyes.

## Resolution change diagnosis

The user identifies Portal Eye Content Width as the UEVR control that broke
output. It changes render resolution, not physical portal width. At 17:54:29
the renderer starts reporting content_aspect_mismatch. Later allocation logs
show intermediate widths as the control was edited, including a final logged
RenderTargetSize After 5120x720 at 17:55:28 (2560x720 per eye). That cannot match
the 16:9 portal or current 2560x736 capture/display/client configuration.

The saved profile currently reads width 1280, height 720 and output enabled,
consistent with the user's report of resetting defaults, but the last logged
live allocation was still 5120x720 before disconnect at 17:57:20. Reconcile live
allocation and output success on the next connection; saved state alone is not
proof of runtime recovery. Subsequent staleTracking records coincide with the
disconnect. Do not remove the aspect guard or rescale/crop metadata to hide this.

The preserved log has 189 sampled success records, 163 aspect rejections, 267
unpaired/stale records and one unsupported-color-resource record across its
sampled portal-output history. These are rate-limited log samples across
multiple actions/disconnects, not frame-loss rates. Raw log and extracted images
remain in External/local-validation/sunshine-sbs.

## Panel size and missing UI

Physical size is visionOS-owned. PortalSceneController defaults/resetSize use
0.7 m height and 16:9 width (about 1.24 m); corner dragging scales width and
derives height from the same base aspect. The shelf button labeled Resize
actually resets to default size. Use corner dragging to enlarge the panel while
keeping the Windows pixel dimensions fixed. Larger default size/clearer control
labels belong to the Mac session if needed.

The current direct exporter captures scene color. Separate UEVR/runtime/Slate UI
is explicitly not composited, as documented in PC-portal-pipeline.md. Whether a
particular missing game HUD element is in that separate target needs checking;
do not promise general UI recovery from a capture setting. Proper UI composition
is a Windows renderer follow-up; retain desktop access for menus meanwhile.

Next priorities: re-establish the fixed-resolution live baseline; enlarge via
visionOS geometry controls; compare source and decoded artifacts during a
controlled game-profile test with the current supported-mode restrictions;
then investigate composition of the required game UI. Do not enable Native
Stereo Fix/AFR/other rejected configurations as an unmeasured portal fix.

## Subsequent UI clarification and build

Josh confirmed BOTH the game UI and UEVR overlay were visible in ordinary UEVR
and absent with Portal Output. The missing separate-texture path is now traced
and implemented for D3D12 in e0d3fa8. Offline build/pixel/debug-layer/regression
checks pass; live acceptance remains pending. See [UI checkpoint](../portal-ui/README.md)
and [Mac size handoff](../../../Mac-handoff-panel-size-and-ui.md). The earlier
paragraphs describe the first-headset-video build before this change.
