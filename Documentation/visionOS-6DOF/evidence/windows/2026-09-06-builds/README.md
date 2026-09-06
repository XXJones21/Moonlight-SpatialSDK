# Windows portal builds and synthetic protocol checks — 2026-09-06

Status: Windows builds and isolated protocol checks passed. The patched backend
has not been injected; live runtime tracking and rendering remain unvalidated.
The user authorized continuing preparation while deferring the existing Native
Stereo eye mismatch. No game termination or reinjection was performed by this
work. At the final process check Hogwarts was no longer running; the reason for
its exit is unknown. The old injector (PID 22300) and SteamVR remained running.

## Build results

| Component/check | Result | Evidence |
|---|---|---|
| PortalCore Release x64 | Built; CTest 1/1 passed | core-build.txt, core-tests.txt |
| PortalHost Release x64 | Built; CTest 1/1 passed | host-build.txt, host-tests.txt |
| Existing Python tooling tests | 5 passed | python-tests.txt |
| UEVR backend, LuaVR, plugin nullifier | Built Release x64 | uevr-build-fixed.txt |
| Backend rebuild after committing fix | Passed; embedded revision refreshed | uevr-build-final.txt |
| Relay with synthetic sender | 810/810 state packets received | relay-smoke-results.json |
| Actual PortalSession receiver outside game | 3 sessions accepted; reset and expiry observed | session-integration-results.json |

Toolchain: Visual Studio 2022 BuildTools 17.14, MSVC 19.44.35221,
CMake 4.1.0-rc3, Python 3.13.5. Explicit generator instance:
`C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools`.
The first sandboxed PortalCore configure selected older Community tools and
failed SDK metadata access; fresh configure in the user context succeeded.

Restored submodules at the existing gitlink pins using authenticated HTTPS:

| Dependency | Commit |
|---|---|
| UESDK | f37f61c62d63af68645cf4a8271e924a37ac65b1 |
| glm | cc98465e3508535ba8c7f6208df934c156a018dc |
| imgui | c6e0284ac58b3f205c95365478888f7b53b077e2 |
| spdlog | 8cc0698331f5a22101abbf29566adf18ae5e3942 |

The initial full UEVR build failed with MSVC C3079/C2597: the unqualified
namespace variable `gameBasis` inside a static PortalFrame member resolved to
the instance member with the same name. Renamed the namespace variable to
`epochGameBasis`, retaining the instance member and behavior. The full build
then passed. The generated CMakeLists change only sorts seven portal entries
to match cmake.toml generation. Both changes were committed in the external fork:
`0b59830367fe6833dbec92c87de286655c69a9b8`.

Exported the updated patch and manifest with `tools/visionos-portal/export_uevr_patch.py`.
Patch SHA256: `a537bf30768c3b4bb43175752a69588374177c28ea2051cbb5714ef1f2c2300f`.
Reverse application check against the clean authored checkout passed. The main
repository whitespace check passed excluding the generated patch: its preserved
diff context contains space-plus-tab indentation that Git flags when inspecting
the patch as ordinary text. Patch bytes were retained to preserve the export hash.
The manifest validation text was updated after export to describe measured results.
Shared PortalCore source hash checks remained enabled and passed configuration.

## Reproduction commands

Run from the repository root in the user context. Prepend Git's `usr/bin` and
`mingw64/bin` directories to PATH for submodule and UEVR generation utilities.

```powershell
$vsInstance = 'C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools'
cmake -S PortalCore -B PortalCore/build -G 'Visual Studio 17 2022' -A x64 "-DCMAKE_GENERATOR_INSTANCE=$vsInstance"
cmake --build PortalCore/build --config Release
ctest --test-dir PortalCore/build -C Release --output-on-failure
cmake -S PortalHost -B PortalHost/build -G 'Visual Studio 17 2022' -A x64 "-DCMAKE_GENERATOR_INSTANCE=$vsInstance" -DPORTAL_HOST_BUILD_TESTS=ON
cmake --build PortalHost/build --config Release
ctest --test-dir PortalHost/build -C Release --output-on-failure
python -m unittest discover -s tools/visionos-portal/tests -p 'test_*.py'
cmake -S External/UEVR-6DOF-Window -B External/UEVR-6DOF-Window/build -G 'Visual Studio 17 2022' -A x64 "-DCMAKE_GENERATOR_INSTANCE=$vsInstance" -DCMAKE_BUILD_TYPE=Release '-DPORTAL_CORE_SOURCE_DIR=D:/Tools/Moonlight-SpatialSDK/PortalCore'
$env:_CL_ = '/MP4'
cmake --build External/UEVR-6DOF-Window/build --config Release --target uevr LuaVR vr-plugin-nullifier --parallel 2
```

## Synthetic check scope

`relay_smoke.py` runs the built host on loopback 14243 with disposable backend
14244 and tracking 14242 receivers. Static, sweep and roll each sent 270 states
over three seconds. All 810 states were received; tracking counts were 270, 270
and 269. Static values were constant; sweep/roll varied. Packet sizes and source
addresses were checked. Roll sent reset retries because no renderer acknowledged
the reset. Host termination was forced by the harness; graceful host shutdown
was not tested.

`portal_session_probe.cpp` was separately compiled against the actual fork's
PortalSession.cpp and PortalCore library. `session_integration.py` ran that receiver
on 4244, the actual host on 4243, and a disposable tracking receiver on 14242.
Static/sweep/roll ran two seconds each. The probe observed 388 state samples,
accepted all three sessions, accepted reset epoch 2, and observed three stale
transitions after sender cessation. The receiver exited normally after lease
expiry. This tests actual session acceptance/lifetime code without game injection.
It does not measure a precise expiry latency. No renderer published a healthy
output status or acknowledged a rendered reset. The host log is empty due to
buffered output on termination; acceptance evidence is the probe CSV and results.

Neither harness sent tracking to SteamVR's actual 4242 socket. SteamVR motion,
axis signs/offset composition, frame latching, renderer status and reset acknowledgment
remain untested. VRto3D currently listens on 0.0.0.0:4242 with consumption disabled;
restrict external access before enabling tracking. Its measured raw/standing Y
offset must be accounted for during live tracking validation.

## Staged package and next user checkpoint

Package: `D:/Tools/Moonlight-SpatialSDK/External/local-validation/UEVR-portal-0b59830`.
Per-file source paths, sizes and verified copy hashes are in [package-manifest.json](package-manifest.json).
The original Downloads injector package was preserved. This package combines
its original frontend/config and OpenXR loader with the newly built backend,
OpenVR library, LuaVR and nullifier. OpenVR is required for the portal path.
Backend dependency inspection is recorded in backend-dependencies.txt.

Final checks verified all eight packaged file sizes/hashes and found the full
expected revision string inside UEVRBackend.dll. Both temporary probe/relay
processes had exited and ports 4243/4244 were free. Only SteamVR's 4242 listener
remained, with `use_open_track=false`.

The supported baseline profile is staged as `staged-config.txt`, with Portal
Output and the older room-anchored 6DOF Window disabled. It is not yet installed:
the running game can overwrite profile changes, and persisted aim/decoupled-pitch
settings are currently incompatible with portal output.

Next checkpoint: user closes Hogwarts Legacy and the old UEVR injector, leaving
SteamVR running. Confirm both have exited, preserve the latest profile, apply
the staged supported config, then launch the new injector and have the user
start/load the game and inject OpenVR. Verify loaded module provenance, Native
Stereo, ordinary gamepad behavior and saved supported settings before enabling
Portal Output or introducing live runtime tracking. Visual mismatch remains
an explicitly deferred issue; this report does not mark its gate passed.
