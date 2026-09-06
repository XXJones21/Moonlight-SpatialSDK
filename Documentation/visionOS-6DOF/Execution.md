# Implementation record

User direction (2026-09-06): author phases 0 through 6 in sequence on Windows,
with one commit per phase. Defer builds, tests, runtime verification and debugging
until the Mac handoff. Phase 7 latency work follows functional validation.
This overrides the original plan's intermediate build/test gates; unchecked
verification boxes are intentionally pending and must not be reported as passing.

| Phase | Source work | Verification |
|---|---|---|
| 0 | Dependency pins, reproducible setup, shared scheme | Deferred |
| 1 | Geometry, state/reset protocol, ordered relay, synthetic sender and fixtures authored; source review complete | Deferred |
| 2 | OpenVR portal hooks, frame-associated direct D3D11/D3D12 SBS output, metadata, reproducible fork patch authored | Deferred |
| 3 | Mixed immersive portal, tracked physical geometry, gestures, preview stereo, validated pose sender authored; recovery/encoding source review addressed | Deferred |
| 4 | Pinned Moonlight source import, Xcode dependency wiring, pairing/settings UI, decoder/audio/gamepad, complete session teardown authored; source review fixes applied | Deferred |
| 5 | Tracking/video loop, strict status parsing, decoded eye metadata gate/crop, manipulation revisions and generation-safe recovery authored | Deferred |
| 6 | Quest parity controls/settings, capability feedback, recovery UI, persisted effects/calibration, audio positioning, lighting and Mac onboarding authored | Deferred |

Preserve unrelated Quality-of-Life-Improvements.md, multi-display plan and
MyApplication changes. Commit only this investigation's files. No remote push.
