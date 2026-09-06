# Moonlight client source integration

The visionOS target imports the pinned Moonlight Vision networking, crypto,
pairing, stream manager, public VideoToolbox/Metal decoder, shaders and common-c
transport sources. `ThirdParty/Moonlight/Import-manifest.json` records original
file hashes and source pins; imported files retain notices and accompanying
licenses. This is source integration, with all compilation and runtime checks
deferred to the Mac handoff.

The app owns one connection independently of the Settings window. The SwiftUI
connection model implements manual host/port entry, PIN pairing, Keychain client
identity and host certificates, server selection, application list/App ID fallback,
saved preferences, capability display, launch and teardown. Pairing does not
launch automatically. Closing PIN presentation does not cancel pairing.

Stream preferences map into real Moonlight launch parameters: per-eye resolution,
frame rate, codec, bitrate, audio channels, color range and HDR. Full capture size
is twice eye width and eye height plus the metadata rows. Some choices exceed the
initial PC exporter support; the baseline remains SDR HEVC/stereo. Hardware codec
capability is checked on the device. Apply affects the next connection.

The decoder uses CVMetalTextureCache, Metal and a RealityKit DrawableQueue;
private fast-path texture APIs are disabled. Audio decodes Opus into bounded PCM
buffers and plays through AVAudioEngine. One extended gamepad forwards buttons,
sticks and triggers; shutdown releases held input before stopping the core.
The native core is serialized through complete teardown before reuse.

## Dependency restoration on Mac or Windows

From the repository root:

```sh
python3 tools/visionos-portal/bootstrap_apple_dependencies.py
```

This copies OpenSSL and Opus headers/binaries from donor commit
`fb349830ac980ab73dbd653b5b9c813c3b249198` into ignored
`Moonlight-6Dof-Vision/Dependencies` and writes a SHA256 inventory. It clones the
pinned donor if absent and refuses a mismatched/modified checkout. It does not
build. Source membership and linker/header paths are configured in
`Moonlight-6Dof-Vision/PortalDependencies.xcconfig`.

The donor is GPL-3.0; common-c and enet notices accompany their source. UEVR has
separate licensing terms and its source is distributed here as a reproducible
patch. Consult the original dependency notices before distribution. Do not
replace imports with current upstream without reviewing ABI and decoder changes.

## Deferred integration checks

- Xcode SDK/Swift/Objective-C header interoperability and framework linkage.
- Pairing, key storage, host certificate continuity, local-network denial/retry.
- Exact negotiated capture size and metadata-preserving codec path.
- Decoder eye order, vertical orientation, color/HDR and resource lifecycle.
- Audio layout/routing, gamepad rumble and connection interruption/reconnect.

No build, test, simulator or headset pass is claimed by this document.
