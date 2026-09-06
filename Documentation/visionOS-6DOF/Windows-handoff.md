# Windows handoff: virtual headset and 6DoF portal validation

Updated 2026-09-06. Branch: `moonlight-6dof-vision`. Client baseline:
`3e3f270` (audio buffering/controller routing), following `68e7e16` (working
basic visionOS streaming). Windows UEVR fixes through `67d9785` are preserved
in `patches/UEVR-portal.patch` with commit/tree/hash provenance in its manifest.

## Start the next session here

**Windows checkpoint, 2026-09-06:** The stationary VRto3D HMD is validated.
**Latest live result:** `67d9785` now displays SBS with the metadata strip. The
user confirms visible output; 60 sampled valid/paired successful output records
span 70 seconds, with advancing frames at approximately 60 fps and no sampled
GPU stalls. Three transient unpaired/stale records recover in the next sample.
Capture and visionOS portal video remain open.

**Next implementation:** Follow the [Sunshine SBS headset delivery plan](../../docs/superpowers/plans/2026-09-06-sunshine-sbs-headset.md): provision a dedicated 2560x736 capture display, verify Sunshine source/encode dimensions, have the Mac session validate decoded metadata and panel presentation, then qualify live geometry and recovery. The physical monitor rejected this capture mode. Client/Xcode work stays with the Mac session.

**Latest fix:** `67d9785` corrects inconsistent COM identity checks and replacement
of a GPU fence while retaining completion values from its predecessor. The
`4e519e6` live test briefly showed SBS, then logged 79 valid, paired frame failures
with `gpu_recreation_pending`. The offline production-branch regression
reproduced that progression before the fix and passes after it; independent
review found no blockers. The live result above confirms presentation in this run. See the
[Present-fix checkpoint](evidence/windows/2026-09-06-present-fix/README.md).
The final committed-revision Release build, eight package hashes, embedded
revision and exported patch reverse-check passed. The package is ready at
`External/local-validation/UEVR-portal-67d9785`; its loaded backend is verified.
The user confirmed live AVP head pose maps cleanly into the bb7e3ef-injected
game's VRto3D view with Portal Output/window/diagnostics off. Preserve that
baseline; proceed toward exact-size capture of the direct SBS output.

The user reports stable Native Stereo after disabling frame generation/upscaling,
with eye appearance still mismatched, and authorized deferring that visual issue.
PortalCore, PortalHost, patched UEVR and companions now build on Windows; unit
tests and synthetic relay/standalone receiver checks pass. Patched packages
have been injected; the latest live test used revision `67d9785`.
See the [build evidence](evidence/windows/2026-09-06-builds/README.md)
and projection-fix evidence for provenance.
Historical [runtime tracking checks with synthetic input](evidence/windows/2026-09-06-runtime-tracking/README.md)
passed static/sweep/roll and all six isolated axes through the injected game's
SteamVR runtime, with Portal Output off. Complete status/recovery, capture and
headset validation remain open. The first enabled Portal Output test subsequently
[crashed in a recurring UEVR/Steam Overlay Present chain](evidence/windows/2026-09-06-renderer-protocol/README.md).
The overlay-disabled restart then exposed flashing with Portal Output on and no
pose sender. A [projection initialization fix](evidence/windows/2026-09-06-projection-fix/README.md)
was reproduced with a regression, built and packaged as revision `bb7e3ef`.
The user confirmed Portal Output can now be toggled without flashing. A later
crash on this revision still shows the recurring UEVR/Steam Overlay Present
chain, despite the per-game overlay setting being disabled. The Present
recursion recovery in d700ced subsequently executed without the prior crash
over eight minutes of observation. Revision 67d9785 addresses the subsequent
GPU lifetime stall; its successful live direct output is recorded above.

**Latest user direction:** Stop synthetic-pose tests and proceed toward live
Vision Pro viewing. AVP is `192.168.0.182`; Windows Ethernet is `10.1.95.5`.
The live-only relay is running. The user explicitly approved the scoped UDP
4243 firewall rule after the initial automatic rejection; it is now applied
and independently verified. Live capture then established the AVP's observed
source is `10.1.95.13`. The user approved this correction; the firewall and relay
now use that peer. PortalHost accepts advancing live head poses with changing
position/orientation and no receiver errors. No Xcode change was needed for this
link; client-side changes belong in the user's separate Mac session. Live UEVR
camera movement is confirmed, and renderer-derived status leaves the host.
Return-status receipt on the client and portal video remain open.
The current monitor also rejects the required 2560x736@60 mode. Follow the
[headset preparation checkpoint](evidence/windows/2026-09-06-headset-preparation/README.md).
Do not resume the synthetic sequence below or claim headset video is ready.

> Read Documentation/visionOS-6DOF/Windows-handoff.md and the linked PC pipeline
> and protocol documents. Inspect the existing Windows checkout and installed
> tools before changing anything. Our first objective is to establish a virtual
> HMD that UEVR can use without a physical PCVR headset or Virtual Desktop.
> Investigate SteamVR + VRto3D using OpenVR, which matches our existing portal
> fork. Establish a static headset and stable Native Stereo rendering first;
> then build/validate PortalHost and the patched UEVR path, introduce synthetic
> poses, and finally connect the Vision Pro. Keep evidence of each passed gate.
> Do not claim the earlier six phases prove Windows runtime functionality.

## What works, and what remains open

- The physical Apple Vision Pro runs the custom Moonlight client. The user
  confirmed BasicMoonlightPanel video at 1440p/60, with approximately 3 ms
  reported latency and zero reported video frame drops during that test.
- Both H.264 and HEVC negotiation/decoder paths were investigated. A native
  parameter-set constant mismatch was corrected. Basic streaming subsequently
  worked; this is not evidence of correct stereo portal capture.
- The user confirmed the static-audio problem was fixed by changing the player
  queue completion accounting. Start the Windows test with stereo audio.
- **Closing Settings after connecting still stops audio.** Explicit immersive
  scene association did not fix it. Leave Settings open for initial Windows
  tests and track this separately; do not diagnose that known client issue as
  a Windows runtime failure.
- Controller routing changes are present, but exclusive routing while gazing at
  Settings/panels still needs explicit device confirmation.
- BasicMoonlightPanel and SixDoFPanel are separate entities. The 6DoF Window
  toggle controls their selection; the shelf's Effects toggle is independent.
  Confirm the actual startup log (`6DoF=false/true`) rather than assuming the
  saved selection. An accidentally enabled toggle previously confused testing.
- Windows portal source was authored and exported, but the recorded Windows
  build, injection, projection, capture, and runtime gates remain open. Earlier
  documents saying all validation was deferred describe that authoring stage;
  later Mac validation does not close Windows gates.

## Runtime decision and architecture

Sunshine handles media capture/encoding and ordinary gamepad input. A separate
virtual headset driver supplies the Windows VR runtime with an HMD.

An OpenXR instance/session created in a companion app would not itself expose
a headset to UEVR. UEVR is an application using a runtime; it needs the runtime's
system, graphics/session lifecycle, frame timing, views, and poses. Upstream
UEVR supports OpenXR and OpenVR, but **our authored Portal Output path currently
requires OpenVR**. `PortalFrame::latch` rejects other runtimes, and frame/pose
association is implemented through the OpenVR path. Merely removing that check
would not implement OpenXR support.

Recommended initial route: **SteamVR + VRto3D virtual HMD + UEVR OpenVR**.
SteamVR should detect the virtual HMD; direct detection of the Vision Pro is
not required. VRto3D documents operation without motion controllers and accepts
OpenTrack UDP tracking. Inspect the installed version and configuration before
assuming the pinned reference's settings/conventions still apply.

```text
Vision Pro: head pose + portal geometry + eye separation
    -> PortalHost UDP 4243
        -> patched UEVR, loopback UDP 4244: portal views/projections
        -> VRto3D OpenTrack input, loopback UDP 4242: virtual HMD tracking

SteamVR + virtual HMD -> UEVR OpenVR lifecycle/timing
UEVR direct pre-compositor SBS + metadata -> Sunshine -> SixDoFPanel
Xbox controller -> Moonlight/Sunshine -> ordinary game controls
```

The portal requires head position/orientation, panel position/orientation,
physical width/height, and eye separation. Head pose alone cannot determine the
off-axis projections. These values already exist in protocol v1.

Keep one owner for portal projection. The fork maps external tracking into its
latched frame and preserves the ordinary game camera. Validate that runtime
tracking does not apply an additional head/roomscale offset. The OpenTrack
adapter's mathematical inversion exists, but actual SteamVR pose composition
has not been measured.

Alternatives, if the first route fails: a dedicated SteamVR HMD driver, or an
independent OpenXR runtime plus a port of our portal integration. Both add
substantial work. Do not begin either merely because SteamVR does not recognize
the physical Vision Pro.

## Inventory before installation or rebuilding

1. Read applicable Windows workspace rules. Inspect branch, local changes,
   existing external forks, and their commits. Preserve local work; do not
   reset an existing fork to make a bootstrap command succeed.
2. Record Windows version, GPU/driver, Visual Studio/C++ toolchain, CMake,
   Python, SteamVR, Sunshine, and VRto3D versions. Use a Visual Studio developer
   shell where appropriate; missing `cl.exe` in ordinary PATH proves little.
3. Identify the test game's exact build, renderer (D3D11/D3D12), UEVR profile,
   and reproducible save/location. Back up the profile before edits.
4. Identify the capture display, resolution, desktop origin, and scaling.
   Record the Vision Pro IPv4 address separately from the PC's address.
5. The previously tested Sunshine host was `10.1.95.5`. Observed ports were
   HTTP 47989, HTTPS 47984, RTSP 48010, video 47998, audio 48000, and control
   47999. Verify actual configuration; these do not replace portal UDP 4243.
6. Confirm UESDK access before attempting a full fork build. Inspect existing
   authenticated checkout/submodules without printing credentials.

## Restore and build source

The authoritative fork manifest is [patches/UEVR-portal.json](patches/UEVR-portal.json).
It records base `fb31341e860b15e116a15123820c95f044ff0a0f`, authored commit
`562dbe3d0ae389d3616d1db62f025c43cb5cf1f1`, authored tree, and patch SHA256.
The authored commit need not exist on the upstream remote: the checked-in
patch reproduces its tree.

For an absent external checkout, from this repository root:

```powershell
python tools/visionos-portal/bootstrap_uevr.py --clone
```

The script verifies/applies and stages the patch. It does not install runtime
services, initialize gated submodules, build, or commit. If the fork already
exists, inspect it first; the bootstrap deliberately refuses incompatible HEAD
or local changes. Its authored-commit fast path is not a substitute for checking
the working tree.

Follow the restored fork's build instructions, initialize required submodules,
and inspect regenerated CMake against `cmake.toml`. Supply an absolute
`-DPORTAL_CORE_SOURCE_DIR=<repository>/PortalCore`. The fork pins normalized
shared-source hashes; investigate a mismatch rather than disabling that check.
Any deliberate external-fork changes must later be committed and exported with
`tools/visionos-portal/export_uevr_patch.py` so they reach the Mac repository.

Build the standalone core/relay and run existing tests. These commands assume
the Visual Studio 2022 generator; adapt to the installed supported toolchain:

```powershell
cmake -S PortalCore -B PortalCore/build -G "Visual Studio 17 2022" -A x64
cmake --build PortalCore/build --config Release
ctest --test-dir PortalCore/build -C Release --output-on-failure

cmake -S PortalHost -B PortalHost/build -G "Visual Studio 17 2022" -A x64 -DPORTAL_HOST_BUILD_TESTS=ON
cmake --build PortalHost/build --config Release
ctest --test-dir PortalHost/build -C Release --output-on-failure

python -m unittest discover -s tools/visionos-portal/tests -p "test_*.py"
```

## Validation gates, in order

### 1. Virtual HMD and ordinary stereo

Inspect/register the selected VRto3D driver using its version's instructions.
Start SteamVR and demonstrate a connected, active virtual HMD with a stable
stationary pose and no motion controllers. Record logs and configuration.

With Portal Output off, inject UEVR into the selected game using **OpenVR and
Native Stereo**. Verify both eyes render and the Xbox controller drives the
ordinary game camera. This isolates runtime/injection problems from portal
geometry and network tracking. Keep a known-working profile for comparison.

### 2. Relay and synthetic tracking

For a local synthetic sender, start the relay in one PowerShell window:

```powershell
.\PortalHost\build\Release\portal_host.exe --peer 127.0.0.1 --trace-poses
```

In another window, run one sender at a time:

```powershell
python tools/visionos-portal/send_pose.py --host 127.0.0.1 --port 4243 --pattern static --duration 10
python tools/visionos-portal/send_pose.py --host 127.0.0.1 --port 4243 --pattern sweep --duration 10
```

Configure VRto3D's OpenTrack receiver for loopback UDP 4242. The relay already
produces that wire format; a separate OpenTrack application is not inherently
required. Compare transmitted motion to the runtime HMD pose, including all
axes and rotation signs. Inspect for additional driver tracking offsets.

With the patched UEVR build, verify state acceptance at loopback 4244, fresh
status returned through 4243, and timeout/reset handling. A relay without a
working portal renderer is expected to report output unavailable.

### 3. Direct portal pixels and frame identity

Enable the fork's **Portal Output**, keeping ordinary Native Stereo and gamepad
camera controls. OpenXR, AFR/sequential rendering, roomscale, head/controller
aiming, and several compatibility modes are unsupported; see the PC pipeline
document for the complete restrictions.

Start SDR HEVC, stereo audio, 60 fps requested, 1280×720 **per eye**. The actual
capture/encode size is **2560×736**, including 16 metadata rows. Match exporter
eye dimensions, capture-display dimensions/origin, and client selection. Use
100% display scaling and avoid cropping/rescaling. The later benchmark is
1920×1080 per eye, **3840×1096** total.

Capture the fork's direct pre-compositor window, not a SteamVR mirror or the
VRto3D presentation window. Sunshine must capture the matching display region;
placing an arbitrary window on a differently sized desktop is insufficient.
Current exporter support is single-sample, one-slice RGBA8/BGRA8; HDR/FP16/MSAA
and array sources fail closed.

Use the exporter's synthetic diagnostic images first. Verify left/right labels,
corners, frusta, and metadata through the capture/codec path. Then test actual
game output. Record source frame IDs, accepted pose sequence, geometry revision,
and both eye observations. Never bypass the client's metadata gate to force an
image onto the panel. Separate Slate/runtime UI is not included in direct output;
configure the game from the desktop before entering portal gameplay.

### 4. Vision Pro tracking and full loop

Stop the local sender and restart PortalHost with `--peer <AVP-IPv4>` (replace
the placeholder). Permit required headset-to-PC UDP 4243 access; keep 4244 and
4242 loopback. Connect the client to the PC, confirm **6DoF Window on**, and
confirm the logged dimensions/mode match the exporter.

Verify head translation, rotation, eye separation, moving/resizing/yawing the
panel, and ordinary gamepad movement. Head rotation affects eye locations;
portal projection looks through the fixed panel rather than applying normal
head-aim camera rotation. Confirm the game's camera does not receive tracking
twice. Test tracking loss, recenter/reset, geometry revision changes, disconnect,
and reconnect: stale or mismatched frames must disappear. Keep Settings open
until its separate audio lifecycle bug is resolved.

## Evidence and remaining work

Record exact versions, game/profile, build commands/results, runtime logs,
driver settings, relay traces, screenshots of both eyes, and capture dimensions
under [evidence/](evidence/). Distinguish source inspection, automated test
results, and device measurements. Redact Moonlight request keys and other
credentials from logs. Update the baseline and compatibility records only for
configurations actually tested.

On the Mac, Xcode 27's debugger must remain disabled for Vision Pro runs. The
shared scheme already reflects that rule. No simulator run is requested.
The local Xcode UI-state file was deliberately excluded from the checkpoint.

## References

- [PC pipeline and restrictions](PC-portal-pipeline.md)
- [Protocol, routing, resets, and OpenTrack conversion](Protocol-v1.md)
- [Frame metadata](Frame-metadata-v1.md)
- [Coordinate contract](Coordinate-contract.md)
- [Dependency pins](Dependencies.md)
- [Previous Windows baseline evidence](evidence/PC-baseline.md)
- [Mac onboarding and device follow-ups](Mac-onboarding.md)
- [VRto3D upstream documentation](https://github.com/oneup03/VRto3D/blob/main/README.md)
- [Valve driver documentation](https://github.com/ValveSoftware/openvr/blob/master/docs/Driver_API_Documentation.md)
- [OpenXR specification](https://registry.khronos.org/OpenXR/specs/1.0-khr/html/xrspec.html)

Upstream documentation was consulted during the preceding investigation.
Our VRto3D reference pin is `edac2b23a982f3d51d7cac6674131f032d9f6515`;
current upstream behavior is not proof that an installed/pinned build behaves
identically. Measure the actual Windows installation before choosing settings.
