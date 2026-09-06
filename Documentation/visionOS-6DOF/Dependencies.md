# Pinned dependencies

| Component | Source | Commit |
|---|---|---|
| UEVR | https://github.com/elliotttate/UEVR-6DOF-Window (agent/6dof-window-mode) | fb31341e860b15e116a15123820c95f044ff0a0f |
| Moonlight vision donor | https://github.com/RikuKunMS2/moonlight-ios-vision (vision-testflight) | fb349830ac980ab73dbd653b5b9c813c3b249198 |
| VRto3D reference | https://github.com/oneup03/VRto3D | edac2b23a982f3d51d7cac6674131f032d9f6515 |
| Moonlight C core | https://github.com/moonlight-stream/moonlight-common-c | a517f7cbcaf37ae0003979382d4e6348f37b8b2d |
| ENet | https://github.com/cgutman/enet | c6bb0e50118d08252eee308de8412751218442d6 |

Reference checkouts live under ignored External/. UEVR changes are exported as
a patch for other machines; do not depend on an unpublished local checkout.
Initialize public submodules at their recorded gitlinks. UEVR's UESDK submodule
requires separate authenticated access; no credentials are included here.
Sunshine, SteamVR and VRto3D installed versions are not yet measured.

The donor's source and third-party license notices must accompany imported files.
UEVR's root LICENSE says Copyright (c) 2022-2025 praydog, All rights reserved;
do not assume it is permissively licensed. The Moonlight donor is GPL-3.0.
VRto3D uses LGPL-3.0. This branch is an implementation
checkout, not a distribution or an assertion of license compatibility.

Phase 2 authored fork commit: `562dbe3d0ae389d3616d1db62f025c43cb5cf1f1` on the local
`visionos-portal` branch. Complete base-to-authored patch and its SHA256/source-tree
manifest are in `patches/UEVR-portal.patch` and `patches/UEVR-portal.json`.
Use `tools/visionos-portal/bootstrap_uevr.py` for source-only reproduction.
This records source authorship; no build, tests, installed runtime or capture
validation was performed. PortalCore is supplied through an explicit absolute
CMake path and normalized content hashes recorded in the fork.
