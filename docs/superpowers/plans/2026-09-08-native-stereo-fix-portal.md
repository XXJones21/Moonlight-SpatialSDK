# Native Stereo Fix Portal Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for the independent copy helper and review; execute the coupled render-hook integration inline. Checkboxes track completion.

**Goal:** Export the user-validated Native Stereo Fix eye pair through the existing D3D12 portal.
**Architecture:** Copy explicit eye sources into the existing private SBS backbuffer. A bounded frame record proves that two distinct native-fix views were submitted for the exact latched portal state before export.
**Tech Stack:** C++20, D3D12/OpenVR, MSVC Release, WARP tests.
**Spec:** `docs/superpowers/specs/2026-09-08-native-stereo-fix-portal-design.md` (user approved).

## Constraints and rulings

- 1280x720 per eye, 2560x736 capture, 60 fps; no client protocol changes.
- No synthetic poses sent to the running game. Offline fixtures are permitted.
- Preserve live game/profile and the e0d3fa8 package until replacement staging.
- Ruling: continue in the existing dedicated `External/UEVR-6DOF-Window` checkout on `visionos-portal`, separate from the Mac client checkout, to preserve the established build/package/export paths. No new worktree or dependency duplication is needed.
- Ruling: copy helper/test work can run independently; engine hook and frame association remain one coupled root task.

## 1. Explicit D3D12 eye copy

Files: create `src/mods/portal/PortalEyeCopy12.hpp` in the external checkout; create offline tests under `Documentation/visionOS-6DOF/evidence/windows/native-stereo-fix/`.

Interface: `PortalEyeSource12 { ID3D12Resource* resource; D3D12_RESOURCE_STATES state; D3D12_BOX box; }`; `PortalEyeCopy12::validate(device, eyes, eyeWidth, height, outputFormat)` returns null on success or a diagnostic; `record(command, destination, eyes, eyeWidth)` copies both regions, restoring source states and handling a shared source once.

- [x] Write/run failing tests: distinct left/right source content, shared-source halves, invalid boxes/format/device, absent eye, restored source states, and metadata rows untouched.
- [x] Implement and run WARP with debug layer; review helper and test evidence.

## 2. Rendering evidence and integration

Files: `PortalFrame.hpp/.cpp`, new small pairing helper if needed, `FFakeStereoRenderingHook.cpp`, `PortalOutput.hpp/.cpp`, `D3D12Component.cpp`.

- [x] Write/run failing tests for a full native pair, startup single view, stale/duplicate pair, different mode or resource identity, invalidation/reset, and reused ring slots.
- [x] Latch native-fix mode with PortalFrame. Record exact two-view native submission and source identities under the same frame; reject incomplete evidence. Bind identities through the existing render frame handoff without using latest state at export.
- [x] Audit constructor eye-index handling before Same Pass rewriting; preserve off-axis matrices and existing pose-queue/frame-count semantics.
- [x] Admit only the supported D3D12/OpenVR native-fix configuration. Validate mode and both resource identities at export; retain both source COM references until slot fence completion.
- [x] Use copy helper for plain and native-fix output, preserve UI and metadata append, then run tests.

## 3. Build, review, and staging gate

- [x] Run `portal-ui/run-regressions.ps1`, existing WARP UI test, new native pair/copy tests, and Release build.
- [x] Obtain independent spec/code review; resolve findings and rerun affected checks.
- [x] Commit external fork, rebuild embedded revision, package separately, verify hashes, export patch/manifest and reverse-check.
- [x] Update evidence/handoff with exact limitations and testing status. Ask for game/injector closure only after the replacement is ready; user performs live acceptance.

## Live acceptance (2026-09-08 checkpoint)

- [x] Close game/injector, back up and stage the profile, then inject package 4007df4.
- [x] User tested live portal output and menu/loading recovery through c34eaa2 plus the nonactivating topmost correction. See frame-identity-recovery evidence.
- [ ] Complete broader toggle/reconnect/session-restart qualification and inject the packaged permanent 2c35239 binary on the next launch.

Offline evidence: Documentation/visionOS-6DOF/evidence/windows/native-stereo-fix/README.md.
