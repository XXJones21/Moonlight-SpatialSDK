# Windows inventory, 2026-09-06

Read-only inventory following Windows-handoff.md. No installation, configuration
change, build, test run, injection, or device measurement was performed. All
runtime acceptance gates remain open.

## Source

- Main checkout: `moonlight-6dof-vision`, HEAD `6e0c8ef` (Windows handoff).
- Existing UEVR checkout: `External/UEVR-6DOF-Window`, branch `visionos-portal`,
  HEAD `562dbe3`, matching the manifest's authored commit.
- Existing VRto3D reference: `External/VRto3D`, HEAD `edac2b2`, matching the pin.
- Git status reported no changes in these three checkouts. Git warned that the
  user's global ignore file was inaccessible. Per-command safe.directory was
  used for these user-owned repositories; global Git settings were not changed.
- UEVR's `dependencies/submodules/UESDK` directory exists but contains no entries.
  Authenticated access is unverified. `git submodule status` failed because Git's
  shell could not find basename/sed/git-sh-setup; this does not prove access denial
  to the upstream dependency.
- No UEVRBackend.dll or VRto3D driver DLL was found within the respective source
  checkouts. The running injector's executable path was unavailable.

## Installed tools observed

| Component | Observation |
|---|---|
| Windows | Registry reports release 25H2, build 26200.9168; ProductName says Windows 10 Home, so that label is not used to identify the OS generation |
| GPU | NVIDIA GeForce RTX 4080; driver 610.88 from nvidia-smi |
| C++ tools | VS 2022 Build Tools 17.14.36717.8; vswhere confirms x86/x64 C++ component |
| CMake | Executed binary reports 4.1.0-rc3 |
| Python | Executed default interpreter reports 3.13.5, under miniconda3 |
| Sunshine | Uninstall registry reports 2025.628.4510; process running |
| SteamVR | Installed under the default Steam library; vrserver and vrmonitor running; settings lastVersionNotice is 2.16.7, not an independently verified runtime version |
| UEVR | UEVRInjector process running; version/build provenance unverified |
| VRto3D | Pinned source present; installed binary/version not established |

## First-gate evidence

The September 6 entries in Steam's `logs/vrserver.txt` include:

```text
10:19:10.098 Refusing connect from 64252: HogwartsLegacy because VRInitError_Driver_WirelessHmdNotConnected
10:19:16.081 Refusing connect from 64252: HogwartsLegacy because VRInitError_Driver_WirelessHmdNotConnected
```

SteamVR's saved LastKnown settings identify `vrlink` and an Oculus Quest2.
This is historical configuration, not a live pose measurement. The standard
SteamVR `drivers` directory contains no VRto3D folder. Access to the user's
`AppData/Local/openvr/openvrpaths.vrpath` was denied, so external registration
could not be ruled out. No stationary connected HMD has been demonstrated.

Hogwarts Legacy appears in the runtime attempts, but the intended test game,
exact game build, renderer, injector build, profile, and save location still
need confirmation. Capture display geometry/scaling and headset IPv4 are also
unmeasured. Do not infer them from the old streaming baseline.

## Next steps

1. Inspect the external-driver registry in the user's context and establish the
   installed VRto3D version, or acquire and register an appropriate build after
   checking its instructions. Preserve existing runtime configuration.
2. Demonstrate a connected virtual HMD with a stationary valid pose, then test
   ordinary UEVR OpenVR Native Stereo with Portal Output off. Record both eyes
   and normal gamepad camera movement using a backed-up game profile.
3. Restore required UEVR dependencies, build/test PortalCore and PortalHost,
   and build the portal fork with the explicit PortalCore source path.
4. Proceed through synthetic tracking, direct portal capture/metadata, and
   Vision Pro integration in the handoff's order. Keep Settings open for audio.
