# First injected renderer protocol test — 2026-09-06

Status: failed; game crashed during the first static portal input. Do not count
later relay reset/stale responses as successful renderer validation.

Before the test, Hogwarts PID 36256 owned 127.0.0.1:4244. Saved settings confirmed
Portal Output true, 1280x720 per eye, desktop origin 0,0, diagnostics false and
the room-anchored window false. Native Stereo and the supported camera settings
were retained. The user explicitly enabled this mode and authorized the test.

The bounded probe started PortalHost, sent a static full state, requested reset,
paused input, and attempted recovery. Initial responses included direct_sbs with
outputUnavailable; no healthy response, nonzero render frame ID or visible portal
window was observed. The game exited near the beginning of the run. Subsequent
responses came from the relay and cannot establish acceptance by the dead game.
The harness continued its bounded schedule and sent neutral input before stopping;
it did not terminate the game. Observations and phase times are recorded in
observations.jsonl, phases.json and results.json. All test processes have stopped.

## Crash evidence

Windows Application events 1000/1001 report HogwartsLegacy.exe.36256 crashing at
13:12:14–13:12:16 local time, exception 0xc0000005, fault module ntdll.dll offset
0x1272eb. The game's newly created crash directory was empty. Windows saved a
125,671,693-byte dump, preserved locally at
`D:/Tools/Moonlight-SpatialSDK/External/local-validation/HogwartsLegacy-portal-20260906-131214.dmp`.
SHA256: `59cc23442ae5e77acac4ff87f01c47cfc82b4d4ef1d2ca87af28928ac71f0d43`.
The dump stays in the ignored local evidence directory and was not uploaded.

analyze_dump.cpp performs offline analysis using the dump's memory and matching
local PE images for unwind data, plus local PDB symbols. No running game was
attached to a debugger. dump-stacks.txt records reconstructed stacks. Names for
modules without private symbols are nearest exports plus offsets and must not
be interpreted as exact function names.

Exception thread 3560 shows repeated alternation:

```text
gameoverlayrenderer64 (nearest export OverlayHookD3D3 + 0x13e8f)
UEVRBackend!D3D12Hook::present_internal + 0x87
UEVRBackend!D3D12Hook::present + 0x58
[same sequence repeats]
```

The exception is a write at 0x66b40fc8, immediately below RSP 0x66b40fd0,
consistent with stack exhaustion from the repeated hook calls. Upper frames
include exception handling and DLL loading. UEVR frames resolve against the
matching 0b598303 backend PDB; optimization maps the internal return address to
the end of present_internal at D3D12Hook.cpp:535.

Source inspection finds the non-game swapchain branch returns through the
stored original Present pointer before reaching the existing g_present_depth
recursion handling. This is a plausible path for the observed overlay loop when
the additional portal swapchain presents. No production fix has been made and
the exact entry into that loop still needs controlled confirmation.

## Historical next-test proposal (superseded below)

The user has relaunched Hogwarts but was asked to hold injection. Disable Steam
Overlay for Hogwarts only, then relaunch the game so its overlay DLL is absent.
Verify that absence before repeating the same static test with the same backend,
geometry and diagnostics settings. Keep SteamVR running. This isolates the
observed overlay interaction before changing the presentation hook implementation.
If it still fails, preserve the next dump and compare the call path.

The prior six-axis driver tests remain passed. This failure is in the first
direct presentation test; stage 4 renderer status/reset/recovery and all capture
or headset claims remain open. No source or exported-patch change was made here.

## Overlay-disabled restart checkpoint

The user disabled the per-game overlay and restarted Hogwarts. The current
actual game is PID 62348 (launcher 68032). Module inspection found no injected
UEVRBackend or OpenVR DLL, but gameoverlayrenderer64.dll remains loaded in both
processes. Steam's persisted 990080 configuration explicitly contains
OverlayAppEnable=0. Therefore, requiring DLL absence was too strong: this restart
establishes the disabled setting, but module presence alone does not establish
whether the conflicting Present hook remains active. Do not attribute an outcome
to full removal of the overlay component.

The next controlled run retains backend 0b598303 and all portal settings, changing
only the user's overlay setting. Await user injection into PID 62348, then verify
backend provenance and repeat the probe. The probe now takes an actual game PID
and fresh run name, monitors process liveness, and aborts remaining renderer
phases if the game exits. Neutral input cleanup remains separate from renderer
success. This diagnostic edit passed syntax checking; its new crash-abort path
has not been exercised against another game failure.

## Latest outcome and user direction

After revision bb7e3ef's projection fix, the user confirmed Portal Output could
be disabled/re-enabled without flashing. The subsequent already-running probe
was followed by a crash at 13:47:21 local time. Its end-of-probe process-alive
result predates the crash and does not establish sustained stability.

The preserved dump is
`D:/Tools/Moonlight-SpatialSDK/External/local-validation/HogwartsLegacy-portal-bb7e3ef-20260906-134721.dmp`,
125,924,097 bytes, SHA256
`44a8941b9ebf526199f345c6f8ae4ea2df565a83813ad2bcc64ef2a6a8c805e8`.
Offline analysis in [dump-stacks.txt](bb7e3ef-overlay-disabled/dump-stacks.txt)
again finds the recurring gameoverlayrenderer64 / UEVR present_internal /
UEVR present chain on exception thread 72432. The disabled overlay setting did
not eliminate the observed loop. No Present-hook fix has been made.

The user explicitly requested stopping synthetic data tests and moving toward
live headset viewing. Synthetic senders and probes are stopped. Continue with
[headset preparation](../2026-09-06-headset-preparation/README.md); keep renderer
status, capture, and live frame identity gates open.
