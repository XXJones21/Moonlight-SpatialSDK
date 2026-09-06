# Live synthetic tracking — 2026-09-06

Status: PortalHost-to-VRto3D-to-SteamVR tracking checks passed. Patched renderer
status, frame identity and output remain untested with Portal Output disabled.

The user enabled OpenTrack live in VRto3D and left the game ready. The saved
default driver JSON still said false; actual runtime motion below confirms the
live setting took effect. Do not assume it will survive a SteamVR restart.
The scoped non-loopback UDP 4242 firewall block was already active and verified;
see the preceding injection checkpoint. No external packet test was performed.

## Measured results

| Run | Input | Result |
|---|---|---|
| Static | 10 seconds, 900 full-state packets | Exact expected position and identity rotation at sampled precision |
| Sweep | 10 seconds, 900 packets; X/Y translation and yaw | Maximum contemporaneous position difference 4.60 mm; maximum rotation-matrix element difference 0.00574 |
| Roll/reset | 10 seconds, 900 packets; reset requested at 3 seconds | Correct roll response; sender reached epoch 2; maximum matrix-element difference 0.00576 |
| Isolated axes | Positive/negative 0.1 m translations and 0.15 rad rotations, one-second holds | All six axis signs correct; settled position difference below 0.0000001 m at sampled precision |

9,014 runtime pose rows were recorded across raw and standing tracking spaces.
All reported connected, valid and Running_OK. These are sampled API observations,
not rendered frames or a frame-rate measurement. Results: [summary.json](summary.json)
and each pattern's results JSON, sent CSV, runtime CSV and host log.

Positions compose as external input plus (0,1,0) m in raw space and (0,2,0) m
in standing space, consistent with the earlier stationary measurement. Rotation
matches the input quaternion for these patterns. No extra axis reversal, scale
factor or changing offset was observed. The portal epoch mapping still needs
in-game validation; these measurements alone do not prove camera composition.

The read-only C++ sampler queries raw/standing OpenVR poses with zero prediction
and timestamps each using QPC, aligned to Python's performance-counter clock.
Moving comparisons use the most recent sent pose at that timestamp without a
fitted delay. Differences include asynchronous driver updates and are not an
end-to-end latency measurement. Acceptance thresholds were 60 mm / 0.08 matrix
element for moving samples and 5 mm / 0.01 for static/settled holds, evaluated at
the 95th percentile. The reported maxima above are substantially smaller.
Settled holds exclude their first 350 ms. Test scope is these amplitudes and
patterns, not every possible combined orientation.

After sender cessation, the runtime retained its last pose unchanged during the
observed interval beyond 350 ms (48 rows per standard run), still reporting a
valid HMD. This is driver pose retention, not portal lease enforcement. Invalid
or stale imagery must be suppressed by the portal renderer/client and remains
to be tested. Epoch 2 at the sender confirms the relay reset path only; it is
not acknowledgment of a rendered reset.

Each run sent a neutral full state before stopping its host. Subsequent samples
verified zero input translation and identity rotation in both tracking spaces.
The host was terminated by the harness; graceful host shutdown is not claimed.
Final process/socket inspection confirms no host, sender or sampler remains;
only SteamVR's UDP 4242 listener remains. Hogwarts is still running and the saved
Portal Output, diagnostics and room-anchored window flags remain false.

## Reproduction

Compile sample_runtime.cpp with VS2022 BuildTools, C++17, /MD, and the fork's
dependencies/openvr/headers include directory to
External/local-validation/sample_runtime.exe. It loads the verified VRto3D
package's OpenVR library. The diagnostic does not change runtime configuration.

With the user ready, firewall restriction active, live OpenTrack enabled and
Portal Output off, run each command separately from the repository root:

```powershell
python Documentation/visionOS-6DOF/evidence/windows/2026-09-06-runtime-tracking/run_tracking.py D:/Tools/Moonlight-SpatialSDK static
python Documentation/visionOS-6DOF/evidence/windows/2026-09-06-runtime-tracking/run_tracking.py D:/Tools/Moonlight-SpatialSDK sweep
python Documentation/visionOS-6DOF/evidence/windows/2026-09-06-runtime-tracking/run_tracking.py D:/Tools/Moonlight-SpatialSDK roll
python Documentation/visionOS-6DOF/evidence/windows/2026-09-06-runtime-tracking/run_tracking.py D:/Tools/Moonlight-SpatialSDK axes
```

The additional isolated-axis sender is a disposable diagnostic using the
existing State encoder; the production sender/core/host were unchanged.

## Next user checkpoint

Enable UEVR Portal Output in the 6DOF Window page, retaining 1280x720 per eye,
desktop origin 0,0, Room-Anchored 6DOF Window off and capture diagnostics off.
Close the UEVR menu so settings persist. No sender is running, so content may be
blank until the next valid full-state input. Then verify the game owns loopback
4244 and perform bounded static/reset/stale tests against the actual renderer.
The eye mismatch remains deferred; no Sunshine capture or headset result is
claimed by these tracking checks.
