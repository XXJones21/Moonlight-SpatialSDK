# Pairing Flow Remediation – Moonlight Spatial SDK (Quest 3)

This note captures the observed defects in the Quest 3 pairing/connection pipeline, differences from `moonlight-android`, and the remediation steps we should implement. It is intended as the working checklist for closing the infinite pairing loop.

## Current Breaks (Observed)

- **Client identity mismatch** – `checkPairing()`/`pairWithServer()` use hard-coded `0123456789ABCDEF` while `startStream()` uses `Settings.Secure.ANDROID_ID`. The server sees different clients and keeps reporting `NOT_PAIRED`.
- **Paired certificate never persisted** – We never cache `PairingManager`’s paired cert or pass it back into subsequent `NvHTTP`/`NvConnection` calls, so pair state resets.
- **UI updated from background threads** – Pairing/connection callbacks run on an executor and mutate Compose state directly, risking duplicate or stale UI state.
- **Documentation gap** – Quest 3 pipeline doc does not call out the need for a stable unique ID and cert pinning; developers may repeat the mistake.

## Reference Behavior (moonlight-android)

- Generates and persists a single unique ID (`IdentityManager`) and passes it to every `NvHTTP` and `NvConnection`.
- Pins the paired certificate (`pm.getPairedCert()`) and reuses it on all subsequent HTTP/connection calls.
- Performs pairing UI updates on the main thread (`PcView`), avoiding recomposition races.

## Remediation Plan (Implement)

1) **Unify client identity**
   - Add an `IdentityManager` equivalent: generate once, store on disk, and reuse.
   - Pass the same ID into `NvHTTP` for `getPairState()` and `pair(...)`, and into `NvConnection`.
2) **Persist paired certificate**
   - After successful `pair()`, cache `pm.getPairedCert()` locally.
   - Provide that cert when creating `NvHTTP` and `NvConnection` so the server recognizes the paired client.
3) **Main-thread state updates**
   - Post pairing/connection callbacks to the main thread (e.g., `runOnUiThread` or `Dispatchers.Main`) before touching Compose state.
4) **Doc & test updates**
   - Update `Quest 3 App Pipeline.md` to document the unified ID + cert pinning requirements and the corrected pairing state machine.
   - Re-test against Sunshine/GFE: first pair, reconnect without PIN, launch stream; verify no repeat PIN prompts.

## Ready-to-Implement Checklist

- [x] Add persistent unique ID helper; wire into `checkPairing()`, `pairWithServer()`, `startStream()`.
- [x] Cache and reuse paired cert for all HTTP/connection instances.
- [x] Constrain UI state updates to main thread in Compose flow.
- [x] Refresh Quest 3 pipeline documentation with the corrected sequence.
- [ ] Validate on device with Sunshine/GFE host to confirm pairing no longer loops.
