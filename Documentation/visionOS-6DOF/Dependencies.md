# Pinned dependencies

| Component | Source | Commit |
|---|---|---|
| UEVR | https://github.com/elliotttate/UEVR-6DOF-Window (agent/6dof-window-mode) | fb31341e860b15e116a15123820c95f044ff0a0f |
| Moonlight vision donor | https://github.com/RikuKunMS2/moonlight-ios-vision (vision-testflight) | fb349830ac980ab73dbd653b5b9c813c3b249198 |

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
