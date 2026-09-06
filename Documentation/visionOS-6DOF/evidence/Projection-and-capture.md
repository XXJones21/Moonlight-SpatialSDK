# Projection and final capture gate — deferred

The direct pre-compositor SBS fallback has been authored for D3D11 and D3D12 because VRto3D preservation has not been measured. No bridge has won the runtime gate. No compilation, test, capture, instrumented execution or debugging was performed. See ../PC-portal-pipeline.md and ../Frame-metadata-v1.md for the source contract.

Later evidence must include:

1. Unmodified baseline gate results and exact installed driver keys. Inspect native runtime texture bounds, lens warping, reprojection and resolution negotiation before claiming runtime SBS preserves custom pixels.
2. For each D3D11 and D3D12 game profile, enable debug Portal logs and capture frame/eye/sequence/revision plus origins/frusta. Change accepted state between the left/right view and projection callbacks: all four must refer to the first latched state; the next stereo frame must accept the newer one. No AFR pairing is allowed.
3. Static, x/y/z, yaw/pitch/roll and rotated-window sweeps. Project calibrated physical corners to the four image corners; verify translation parallax, calibrated eye separation, portal-aligned view direction, gamepad motion, world scale, and no second camera-origin subtraction.
4. Run PortalDiagnostics through the direct output and Sunshine. Preserve eye 0/1, corners 1..4, the depth panels, frame/revision text, identical valid P6FM tags, and exact 2W x (H+16). Decode tag CRC after the actual codec and color conversion. Compare reference source pixels with final capture; all color/crop/scaling differences must be explained.
5. Confirm normal game scene/HUD pixels, desktop menu configuration, and restrictions for UI-only swapchains. Confirm old WindowMode/CutsceneComfort masks cannot crop the portal output.
6. Invalidate tracking, wait beyond the 250 ms lease, send duplicate/reordered/session-reset/epoch packets, restart the game, resize source and display, change D3D resources, and provoke busy GPU slots/device removal. No stale right-eye source, valid stale tag, mismatched frame, resource reuse before completion or false healthy status is acceptable.
7. Save exact launch steps, profile, source/frame logs, encoded capture screenshots/CSV and D3D debug-layer output. Both GPU backends need independent evidence. Mark the gate passed only after the artifacts demonstrate it.

Capture dimensions: default 2560x736; bench 3840x1096. Content aspect remains 16:9 per eye after cropping the 16 metadata rows. Request 60 fps; achieved cadence is unmeasured. No runtime projection validation or performance number is implied by source availability.
