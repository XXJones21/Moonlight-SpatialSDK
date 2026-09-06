# Sunshine SBS Headset Delivery Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to execute this plan task by task. Steps use checkboxes. Windows work runs here; Xcode changes and device builds belong to the user's separate Mac session.

**Goal:** Display the live UEVR stereo portal on Vision Pro through Sunshine, with valid decoded frame metadata and live head tracking.

**Architecture:** UEVR's direct `UEVR Portal SBS` window fills a dedicated Windows capture display. Sunshine encodes that display without changing its dimensions. The existing visionOS decoder validates the two metadata copies before SixDoFPanel displays the eye images; PortalHost continues carrying live geometry and poses independently.

**Tech Stack:** Windows, UEVR OpenVR/Native Stereo/D3D12, Sunshine/NVENC/HEVC, PortalHost, visionOS Moonlight/VideoToolbox/RealityKit.

**Spec:** [PC pipeline](../../../Documentation/visionOS-6DOF/PC-portal-pipeline.md), [protocol](../../../Documentation/visionOS-6DOF/Protocol-v1.md), [Windows validation plan](2026-09-06-windows-portal-validation.md).

## Global constraints

- Initial mode: **1280x720 per eye; 2560x736 total; 60 fps; SDR HEVC; stereo audio; 100% Windows scaling.** The final 16 rows carry metadata.
- Preserve the entire source image. No crop, letterboxing, rescale, HDR conversion, or capture of the VRto3D/SteamVR mirror.
- Keep OpenVR and Native Stereo. Room-Anchored Window stays off. Keep the existing supported portal profile and deferred eye-lighting mismatch separate from transport failures.
- Use live AVP poses only. Do not restart historical synthetic senders. Optional exporter image diagnostics still use live tracking and are unnecessary for the first game-image delivery attempt.
- Preserve the working physical desktop, profiles and Sunshine pairing. Back up configuration before modifying it. Do not collect or publish `sunshine_state.json` or credentials.
- PC: `10.1.95.5`; AVP reports `192.168.0.182`; observed Windows peer is `10.1.95.13`. Retain approved UDP 4243 peer scope and loopback 4242/4244.
- Keep AVP Settings open for the known audio lifecycle issue. Do not bypass frame validation to remove the grey panel.
- Coordinate any display-driver installation, stream restart or game restart at a user checkpoint. This plan does not install a driver or change the active stream.

## Established baseline and remaining boundary

UEVR revision `67d97855b1f81fb3226b43b8c4447a5eb9f1d022` is built, packaged and injected. The user sees both direct SBS eyes and the metadata strip. Sixty sampled successful output records span 70 seconds, with approximately 60 fps frame progression and no sampled GPU stalls. Three unpaired/stale records recover by the next sample. Live AVP camera mapping works. See [live evidence](../../../Documentation/visionOS-6DOF/evidence/windows/2026-09-06-present-fix/README.md).

The physical display is 2560x1440 at 144 Hz and rejected a non-mutating test of 2560x736 at 60 Hz (`DISP_CHANGE_BADMODE`, -2). A direct window occupying part of that desktop is not yet a correctly sized capture source. A grey client alone cannot distinguish capture selection, negotiation, metadata or frame-gate failures.

## Task 1: Provision an exact-size capture display

**Files/artifacts:** Create `Documentation/visionOS-6DOF/evidence/windows/sunshine-sbs/README.md` and `display.json`; preserve local display/driver configuration backups outside Git. Existing output position controls are in `External/UEVR-6DOF-Window/src/mods/portal/PortalOutput.cpp`; no source change is currently required.

**Interface:** Produces a verified display device ID, physical desktop origin `(x,y)`, 2560x736 mode at 60 Hz and 100% scaling.

- [ ] Inventory the installed Sunshine version and detected display IDs again; save only relevant display and encoder records. Verify the physical display's existing rejection from `evidence/windows/2026-09-06-headset-preparation/display-mode-check.json` rather than repeating disruptive changes.
- [ ] Select a maintained Windows virtual-display driver supporting custom modes and the current Windows/GPU combination. Before installation, record its official source, release, signature, exact install/uninstall commands, configuration path and rollback procedure in the evidence README. Verify these against that release; do not assume Sunshine itself supplies a virtual display.
- [ ] At the installation checkpoint, add a dedicated extended display with 2560x736 at 60 Hz, SDR and 100% scaling. Keep the physical display available for game and UEVR menus. Record the actual device ID and desktop origin; do not assume `(0,0)`.
- [ ] Set UEVR Portal desktop X/Y to that origin, eye width 1280 and eye height 720. Keep Portal Output on and Room-Anchored Window off. Verify the portal client area fills the capture display exactly and the whole metadata strip remains present, without taskbar, menus or cursor obscuring it.
- [ ] Save measured display mode, window client rectangle and a source screenshot. Confirm the existing game still produces valid paired output after moving the window.

**Pass:** A real enabled capture display exposes exactly 2560x736 at 60 Hz and contains only the complete direct portal image. If the selected driver cannot expose/capture this mode, stop at this boundary and document the failure before considering a capture-backend change or a revised protocol.

## Task 2: Route that display through Sunshine

**Files/artifacts:** Back up `%ProgramFiles%/Sunshine/config/sunshine.conf` and `apps.json` locally. Create `evidence/windows/sunshine-sbs/sunshine-session.md` with redacted configuration differences and measured source/encode dimensions.

**Interface:** Consumes Task 1's display ID; produces a Sunshine stream whose visible decoded raster is 2560x736 at requested 60 fps, SDR HEVC and stereo audio.

- [ ] Confirm the installed version's output selection syntax in its UI/logs. Set `output_name` to the actual capture display identifier supported by that version. Record whether selection is global; preserve the original selection for ordinary flat streaming.
- [ ] Configure a clearly named portal streaming entry and keep game launching/injection user-controlled during initial validation. Prevent automatic resolution remapping from changing the dedicated mode. Do not assume a client resolution request alone changes the display.
- [ ] Coordinate a stream reconnect with the user and Mac session. Request exactly 2560x736, 60 fps, HEVC SDR and stereo audio. Record requested dimensions separately from actual capture and encoder dimensions.
- [ ] Verify Sunshine selected the intended display/GPU and negotiated the exact output dimensions and codec. Distinguish internal codec alignment/padding from the visible decoded raster; no change to the visible image is acceptable.
- [ ] Save the relevant session log excerpt and verify the normal desktop remains usable for configuration. If the wrong display or dimensions are selected, fix that boundary before investigating headset rendering.

**Pass:** Source selection and stream negotiation are verified by logs; decoded dimensions must also pass Task 3. A healthy RTSP session or pose relay does not prove correct pixels.

**Reference:** [Sunshine configuration](https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2configuration.html) documents display selection and Windows display configuration. These are current upstream docs; confirm availability and identifier syntax against the installed 2025 build before applying settings.

## Task 3: Verify decoder metadata and show the portal on Vision Pro

**Owner:** Mac session handles any Swift changes, Xcode build and device deployment. Windows supplies source/session evidence. No client source is edited from this Windows session.

**Files to inspect on Mac:**
- `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision/ThirdParty/Moonlight/Limelight/Stream/StreamConfiguration.swift`: requested stream parameters.
- `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision/Portal/PortalFrameGate.swift`: metadata parsing, identity/dimension checks and freshness.
- `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision/Portal/PortalSessionCoordinator.swift`: session, epoch and geometry coordination.
- `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision/Portal/Panels/SixDoFPanel.swift` and `Portal/Panels/MoonlightPanelEntity.swift`: accepted-frame presentation and stereo mapping.

**Interface:** Consumes the decoded full SBS pixel buffer and current live session/epoch/revision; produces correctly assigned 1280x720 eye content with metadata excluded from visible eye regions only after validation.

- [ ] Mac session pulls this handoff and verifies the selected 6DoF mode and requested full stream dimensions in startup logs. Do not use the ordinary 1440p flat-stream preset.
- [ ] Inspect the actual decoded buffer width, height and pixel format. Confirm 2560x736 before diagnosing the metadata parser.
- [ ] Record rate-limited frame acceptance/rejection reasons if existing logs lack them: wrong dimensions, unsupported pixel format, bad magic/version/CRC, unequal eye tags, session/epoch/revision mismatch, nonadvancing frame, tracking invalid or presentation stale. Keep instrumentation separate from product UI.
- [ ] Verify both metadata copies agree, CRC passes, identity matches current geometry and frame IDs advance. Correlate a short decoded sample with Windows output frame IDs. Preserve validation and the 250 ms freshness policy.
- [ ] Confirm the panel receives and presents accepted frames and assigns each eye its corresponding 1280x720 image. The lower 16 rows must be decoded for validation but excluded from the visible eye image.
- [ ] User confirms the game is visible in both eyes on the panel, correctly oriented, with no desktop, metadata strip or unintended cropping. Record the result and any remaining eye-appearance mismatch separately.

**Failure isolation:** Wrong dimensions -> Task 2 negotiation; missing/corrupt tags -> capture, codec or parser; good tags but rejected identity -> session/geometry synchronization; accepted frames but grey panel -> client presentation/material path. Do not infer the failing boundary from panel color alone.

**Pass:** Real Sunshine-decoded game frames pass identity/CRC checks and appear in both headset eyes. Windows success alone cannot close this task.

## Task 4: Validate live geometry, recovery and a repeatable launch

**Files/artifacts:** Update `evidence/windows/sunshine-sbs/README.md`, the Windows validation plan and `Documentation/visionOS-6DOF/Windows-handoff.md`. Link Mac device evidence with the exact client commit/build.

**Interface:** Produces a reproducible end-to-end configuration and measured live acceptance results.

- [ ] User performs small translations on all three axes, then head yaw/pitch/roll. Check parallax and verify head rotation does not introduce ordinary head-aim camera rotation.
- [ ] User moves/resizes/yaws the panel, then walks/turns with the gamepad. Verify geometry revision updates, independent game camera control and no doubled tracking offset.
- [ ] Coordinate tracking loss, recenter/reset and reconnect checks one at a time. Verify invalid/stale content is hidden and current valid images return, with matching epochs/revisions. Confirm renderer status receipt on AVP separately from decoded-frame acceptance.
- [ ] Run two minutes of ordinary play with diagnostics off. Record achieved cadence, decode/presentation drops and observed latency; do not reuse earlier flat-stream performance figures.
- [ ] Save the launch/reconnect sequence, exact display identity/mode/origin, Sunshine configuration changes, UEVR profile/package hashes and client revision. Verify returning to the normal streaming configuration using the saved backup.
- [ ] Commit evidence and close only passed gates. Keep longer gameplay, 3840x1096 mode, detailed eye rendering mismatch, D3D11 qualification and Settings/audio lifecycle as separate follow-ups.

**Pass:** The actual game stays visible and geometrically correct under live input, recovers safely from invalid state, and can be launched again from the saved procedure.
