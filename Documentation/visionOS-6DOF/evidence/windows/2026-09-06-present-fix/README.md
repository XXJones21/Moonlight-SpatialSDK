# D3D12 portal presentation — 2026-09-06

## Current checkpoint: 67d9785 live direct output confirmed

The user now reports a clean visible SBS window, with a screenshot showing both
eyes and the metadata strip. Process inspection confirms the backend loaded
from UEVR-portal-67d9785. The saved log has 60 valid, paired, status=none/detail=none
records between 15:28:00.725 and 15:29:10.842, advancing frame 2159 to 6366
(approximately 60 frames/second). No sampled gpu_busy or gpu_recreation_pending
records occur. Three unpaired_or_stale_frame records each precede recovery in
the next sampled output record; their cause is not established. The secondary
Present recovery logged once at 15:27:59.773, and the game remained responsive.
Live AVP poses continue advancing. See 67d9785-live-log.txt and
67d9785-live-summary.json. This validates sustained direct presentation in this
run; metadata decoding through capture and visionOS video remain unverified.

## GPU lifetime fix provenance

The user injected 4e519e6 and briefly saw the SBS window appear. Release logs
contain 79 sampled output failures with valid=true, paired=true and
detail=gpu_recreation_pending (plus three unpaired/stale records). See
4e519e6-live-log.txt and 4e519e6-live-summary.json. Valid incoming geometry and
paired frames did not produce sustained direct presentation.

Inspection found inconsistent COM identity comparison: the caller compared a
device-interface address with its canonical IUnknown, while create() used the
canonical identity and could return without recreating resources. The caller
then replaced the fence with a new zero-valued fence while retaining the old
serial and slot completion values. Reusing the rotating buffers can therefore
produce gpu_busy followed by persistent gpu_recreation_pending. The actual
live interface addresses were not measured; the next live test must establish
whether this fix resolves the observed failure.

Revision 67d97855b1f81fb3226b43b8c4447a5eb9f1d022 uses one canonical device/queue
identity predicate throughout and replaces the fence, serial and slots together
only during actual recreation. Existing GPU completion checks remain in place.
test_output_lifetime.py extracts production lifecycle branches with narrow COM
and GPU stubs. Before the fix it failed five assertions and reproduced the
same error progression. It now passes eight-frame reuse, interface aliases,
busy resize retention, resized reuse, queue change and device change. See
lifetime-before.txt and lifetime-after.txt. This is an offline regression, with
no pose sender or game input. Independent review found no blocking issues;
the initial full MSVC Release build passed (build-lifetime.txt).

The source also shares the canonical identity predicate with D3D11, but D3D11
has not been runtime-qualified. Direct SBS pixels, capture provisioning and
visionOS portal video remain open. Preserve the live AVP/VRto3D baseline.

The final committed-revision Release build passed (build-lifetime-final.txt).
Package: `External/local-validation/UEVR-portal-67d9785`. All eight copied file
hashes and the embedded full revision verified; see package-manifest-67d9785.json.
Backend SHA256: `cc74fd4a9ded849578dcd0693de877c348f0532cfe026193eea8dbf1dd351c1a`.
The exported patch passed reverse-application checking, SHA256
`97b1eefc2df029579e6bdf02e49c4f6505397fd53ab8b6cda1d3720b4944dd2b`.
Previous packages are preserved. The new package has not been injected, and
the active game's profile has not been changed. Next: user closes game and
injector, leaving SteamVR running; verify closure, back up and stage the profile
with `stage_profile.py --revision 67d9785`, and open the new injector for the
user's live test. No synthetic poses or Xcode changes are involved.

At 15:25 local time the user confirmed both applications closed, and process
inspection verified neither game nor injector remained. SteamVR and PortalHost
were still running. All ten profile files were backed up with verified hashes
to `External/local-validation/pre-67d9785-20260906-152547`; all 20 baseline keys
and eight package hashes were verified. The new injector opened as PID 74080.
See profile-transition-67d9785.json and injector-start-67d9785.json. Await user
loading and injection with Portal Output, room-anchored window and diagnostics
initially off, followed by enabling Portal Output only for the live check.

## Previous checkpoint: d700ced live result and 4e519e6 diagnostics

The user enabled Portal Output and reported stable VRto3D camera movement, but
no visible separate portal window. The process/window observer found the game
created its own 2560x736 portal window at 14:47:23, but it remained hidden in all
recorded samples. At 14:47:23.271 the real game logged "Restored secondary
Present bytes; retrying once". Game PID 45236 was still alive at 14:56:15, over
eight minutes later. This is evidence of the new recovery path executing without
the prior crash in this run; it does not prove successful direct presentation.

A passive 15-second Ethernet capture recorded 1,456 live P6DV state packets and
137 outgoing renderer-derived statuses. Session and epoch match the live input;
accepted sequence advances 996–2444 and renderFrameID advances 28857–29751.
Every sampled status is direct_sbs / outputUnavailable / trackingValid=false,
although the last AVP input has trackingValid=true. The game owns loopback 4244.
These observations establish live state reaching the renderer and return status
leaving the host. Client receipt and rendered pixels remain unverified. See
live-portal-status-summary.json, live-portal-status.json, d700ced-live-log.txt,
d700ced-live-config.txt, and d700ced-live-state.json.

The precise suppression detail was logged with SPDLOG_DEBUG, compiled out of
the Release binary. Merely selecting Debug in the UEVR UI would not restore it.
Revision `4e519e6d1d538e3501e0679922abf1cd52800f1e` adds one info record per second
containing frame, sequence, geometry, valid/paired flags, status, and exact
failure detail. An atomic timestamp limits logging across threads. This is
diagnostic-only; rendering, metadata and acceptance gates are unchanged.

The Release build passed (build-output-logging.txt). Package
`External/local-validation/UEVR-portal-4e519e6` has all eight file hashes and the
embedded revision verified; package-manifest-4e519e6.json records provenance.
Backend SHA256: `8792d7c46097f87e759239315cfc26085f75fda250a50c372d58d757303101ac`.
Exported patch reverse-check passed; SHA256
`bd95ceac209683e02ed478ef1e21a900bd5498a31c679d78156f9b80ab1731fc`.
The original, bb7e3ef and d700ced packages remain intact. The user confirmed both
windows closed. The old game initially remained without a visible window; it
exited before the identity-checked cleanup script acted, which recorded zero
terminated processes. No forced termination was performed. At 15:07, all ten
profile files were backed up with verified hashes to
External/local-validation/pre-4e519e6-20260906-150743, and all 20 supported keys
were merged/verified. Eight package hashes and embedded revision rechecked.
The 4e519e6 injector opened as PID 54332; SteamVR and PortalHost remained running.
See profile-transition-4e519e6.json and injector-start-4e519e6.json. Await user
loading/injection and the live Portal Output check. No synthetic input or client
edit is needed.

The user confirmed live AVP head motion maps cleanly into the injected game's
VRto3D view using bb7e3ef, OpenVR/Native Stereo, Portal Output off, Room-Anchored
Window off, and diagnostics off. Game PID 70796 loaded the matching package.
This is the ordinary live camera checkpoint, not off-axis output qualification.

## Demonstrated dispatch defect

Two earlier game dumps show recurring gameoverlayrenderer64 -> UEVR
present_internal -> UEVR present calls. The separate projection-initialization
fix stopped visible flashing but did not eliminate this crash. Both secondary
swapchain branches in D3D12Hook::present_internal return through the saved
original Present pointer before reaching the existing game recursion guard.

test_secondary_present.py extracts the actual production dispatch prefix and
compiles it with narrow COM/hook-memory stubs. A simulated overlay re-enters the
same real dispatch. A sentinel bounds the pre-fix recursion; no live game,
SteamVR input, GPU, or synthetic pose sender is used. Before the fix, it compiled
and failed 24 checks for unbounded recursion/recovery. The initial fix passed 28
cases plus game-path preservation; its MSVC Release backend build passed.
Independent review found that ProtectionOverride can throw when VirtualProtect
fails. Four additional cases reproduced that issue (8 failing assertions).
Recovery now catches standard restoration exceptions and returns a failed
HRESULT; an unsuccessful instruction-cache flush also fails. All 32 cases plus
game-path preservation pass in regression-review-after.txt. The earlier
regression-after.txt intentionally preserves the pre-review 28-case result.

## Change and limits

Both early forwarding branches now use a thread-local active-call stack keyed
by swapchain and Present/Present1 entry point. A repeated call uses the same
on-disk original-byte restoration already present in UEVR's game path, with at
most one retry. It flushes the instruction cache after restoration. Missing
bytes, a direct self-hook, or another recursion after repair return
DXGI_ERROR_INVALID_CALL, never a false S_OK. PortalOutput already treats a failed
Present HRESULT as present_failed and suppresses successful frame reporting.

Independent nested swapchains remain allowed. Original arguments and failure
results pass through. RAII clears the active-call stack on return or C++ unwind.
The game swapchain's existing callback and recovery path is unchanged.

The regression covers phase-one filtered and phase-two secondary windows, both
Present variants, successful/error forwarding, recoverable/unrecoverable loops,
an ineffective repair, cleanup after failure, and distinct nested swapchains.
Hook-memory writes are simulated: this does not prove the installed overlay's
machine-code bytes can be repaired or that real portal pixels are presented.
Live game validation is still required. No D3D11 behavior was changed.

Independent reviewer confirmed the exception finding is resolved and found no
remaining blockers for a controlled live test after the final build. Source is
committed as `d700cede698f66c583af104ba065c19f1ca85b59` in the dedicated fork.

## Next live checkpoint

Final committed-revision Release build passed (build-final.txt). The new package
is `D:/Tools/Moonlight-SpatialSDK/External/local-validation/UEVR-portal-d700ced`.
All eight copied file hashes and embedded revision verified; see
package-manifest.json. Backend SHA256:
`11b8493fb8351ef1e9189bea778d29f787e791ac1432b19973f002a89f701ddb`.
The exported patch passed reverse-application checking and has SHA256
`da66eb315cae68f1fe808dc2140fe3734ecd403d1f728ecc0daeecd941f58b92`.
The original and bb7e3ef packages remain intact; the game still uses bb7e3ef.

The user confirmed game and injector closed. Process inspection confirmed both
were stopped. At 14:40 local time, ten profile files were backed up with verified
hashes to `External/local-validation/pre-d700ced-20260906-144037`; all 20 staged
baseline keys were merged and verified while preserving other settings. All
eight package hashes and embedded revision were rechecked. The new injector
opened as PID 27512; SteamVR and PortalHost remained running. See
profile-transition.json, config-before-injection.txt, and injector-start.json.
The user subsequently confirmed the ordinary live camera baseline works with
Portal Output still off. Inspection verified game PID 45236 loaded d700ced from
the new package with the expected backend hash. Saved OpenVR/Native Stereo and
the three disabled output/window flags match; live relay poses continue. See
baseline-injection-verified.json. A five-minute read-only observer was started
as PID 38996 to record game survival and portal-window visibility during the
next user toggle (live-output-45236.jsonl). It generates no tracking input.

Next enable Portal Output only and inspect survival, renderer status and direct
window appearance. The 2560x736 capture-display limitation remains separate.
