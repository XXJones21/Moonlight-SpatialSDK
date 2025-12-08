# Native Video Path Plan – Findings and Path Forward

## Summary of Findings (Java MediaCodec Path)

- Decoder setup **does run**: logs show `setup ENTER`, `initializeDecoder ENTER`, `configureAndStartDecoder: calling videoDecoder.configure()`, `bridgeDrStart`, `start: starting renderer + choreographer threads`.
- Decode units reach Java: `bridgeDrSubmitDecodeUnit` and `submitDecodeUnit` logs for IDR and PICDATA are present every run.
- **Missing**: all MediaCodec I/O logs (`PICDATA branch`, `fetchNextInputBuffer`, `queueInputBuffer`, `dequeueOutputBuffer`). Despite repeated clean builds and a full uninstall/reinstall, these logs never appear.
- Conclusion: MediaCodec input/output path is not executing on-device even though setup and start are invoked. Either the Java renderer isn’t the active decode path at runtime, or something prevents the input thread from progressing; repeated instrumentation is not observed in logs, suggesting a mismatch or runtime bypass.

## What We Tried (Java path)

- Added detailed logging in `MediaCodecDecoderRenderer` for setup/initialize/configure/start, submitDecodeUnit branches, and fetch/queue/dequeue.
- Added bridge-side logging in `MoonBridge` for submitDecodeUnit and start.
- Verified configuration and thread start logs appear after clean uninstall/reinstall.
- No fetch/queue/dequeue or PICDATA-branch logs ever surfaced in multiple runs.

## Current Issues

- Video decode produces a black screen; audio works.
- Java MediaCodec renderer appears configured but never processes input buffers on-device (no fetch/queue/dequeue).
- Instrumentation is not surfacing beyond submitDecodeUnit, implying the Java decode path is effectively not running for buffers.

## Native Video Path Outline

Goal: bypass Java MediaCodec renderer and decode natively, rendering directly to the Spatial SDK surface (or a native surface bridge).

### Hook Points (JNI/native)

- `moonlight-core/callbacks.c`:
  - Already calls `BridgeDrSetup`/`BridgeDrStart`/`BridgeDrSubmitDecodeUnit`.
  - Replace/augment `BridgeDrSubmitDecodeUnit` to feed a native decoder instead of calling back into Java.
- Surface acquisition:
  - Expose the Spatial panel Surface to native (via JNI) or create a native surface texture target compatible with the Spatial SDK layer.

### Decoder options (native)

- Use NDK MediaCodec via C/C++ (AMediaCodec) to stay on hardware decode but entirely native.
- Alternatively, integrate a software decoder (e.g., FFmpeg) as a fallback, but that risks performance/power.
- Preserve CSD handling (SPS/PPS/VPS) as in the current Java path; submit IDR + CSD before PICDATA.

### Data flow (native)

1) On setup: create/configure AMediaCodec with mime (AVC/HEVC/AV1) and negotiated width/height/fps.
2) On start: start codec; spawn input/output threads analogous to the current Java renderer threads.
3) On `submitDecodeUnit` (native):
   - Push non-PICDATA (SPS/PPS/VPS) as CSD.
   - For PICDATA: dequeue input buffer → copy NALU → queue with PTS → output via render flag to the target surface.
4) On output: release output buffers with render=true to the provided surface.

### Integration steps

- Add a native decoder module under `app/src/main/jni` (or extend `moonlight-core`) that:
  - Accepts a Java-provided Surface (SurfaceTexture/ANativeWindow) via JNI.
  - Replaces the call to Java `videoRenderer.submitDecodeUnit` with native queueing.
- Gate this behind a runtime switch so we can A/B between Java and native for testing.

### Risks / Considerations

- AMediaCodec surface handling must align with Spatial SDK’s surface; will need to ensure the provided Surface is usable natively (ANativeWindow_fromSurface).
- AV1 support and HDR/color keys: start minimal (no color keys), add guarded params as needed.
- Error handling/recovery: start with simple stop/recreate on failure; finer-grain recovery can follow.

## Next Actions

### Implementation Plan (Quest 3 native decoder)

1) Surface handoff (decide now)
   - Preferred: reuse the Spatial panel Surface directly. Export Surface from Kotlin to JNI and wrap with `ANativeWindow_fromSurface`.
   - Alternate: create an intermediate SurfaceTexture in native and feed Spatial via texture sharing (only if direct Surface fails).
   - Open question: Can we safely obtain the panel Surface on the native side without lifecycle conflicts? (need confirmation)

2) JNI entry points
   - Add JNI to receive: `Surface`, `videoFormat` (H264/HEVC/AV1), `width/height/fps`.
   - Add JNI to start/stop/cleanup the native decoder.
   - Feature flag to choose native vs Java path at runtime (env var / build flag / pref).

3) Native AMediaCodec wrapper (C/C++)
   - Initialize AMediaCodec with mime from `videoFormat` and set `AMEDIAFORMAT_KEY_WIDTH/HEIGHT` and optional `FRAME_RATE`.
   - Bind output surface: `AMediaCodec_configure` with `ANativeWindow`.
   - Start codec; create two threads:
     - Input thread: dequeue input buffer → copy DU → queue with PTS and flags (SYNC on IDR, CSD on codec-config).
     - Output thread: dequeue output buffer → `AMediaCodec_releaseOutputBuffer(bufferIdx, true)` to render to the surface.
   - Handle CSD: on IDR, submit SPS/PPS/VPS first with `BUFFER_FLAG_CODEC_CONFIG`.
   - Keep logging counters for queued/rendered frames.

4) Wire into Moonlight callbacks
   - In `BridgeDrSubmitDecodeUnit` (native), route to native AMediaCodec queue instead of Java `submitDecodeUnit` when native flag is on.
   - Keep Java path available for fallback.

5) Error handling / recovery (minimal first)
   - On codec errors: stop and recreate codec.
   - If Surface becomes invalid: signal up and fail gracefully.

6) Build/packaging
   - Add native sources under `app/src/main/jni` (or extend `moonlight-core`) for the AMediaCodec wrapper.
   - Ensure Gradle builds and packages the native lib; expose JNI symbols in `MoonBridge`.

7) Validation steps
   - Log: native queue/render counters, surface ready.
   - Verify frames render (no black screen) on Quest 3.
   - Compare against Java path via feature flag.

### Open questions to resolve before coding

- Confirm direct access to the Spatial panel Surface in native: is `ANativeWindow_fromSurface` on the panel Surface stable across lifecycle?
- Do we need HDR/color keys initially? (Plan: start minimal, no color keys.)
- AV1 necessity for Quest 3: do we require AV1 in v1, or can we start with H264/HEVC only?
- Preferred feature-flag mechanism (build config vs runtime pref) for A/B between native and Java decoder.
