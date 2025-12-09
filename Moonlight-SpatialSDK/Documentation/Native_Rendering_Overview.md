# Native Rendering Overview (Quest 3)

This document introduces the native video decode/render path now used by default in the Quest 3 Spatial SDK build. It bypasses the Java `MediaCodecDecoderRenderer` and feeds Sunshine stream data directly into an NDK `AMediaCodec`, rendering to the Spatial panel surface.

## Goals

- Eliminate the broken Java MediaCodec path for our Quest 3 target.
- Keep the data flow aligned with Sunshine-provided stream config (format/width/height/fps).
- Keep a simple, debuggable foundation that can be extended (HDR, AV1, recovery) after we confirm baseline decode/render works.

## High-Level Flow

1) Surface arrives from the Spatial panel.  
2) JNI receives the Surface and wraps it as `ANativeWindow`.  
3) Native decoder sets up `AMediaCodec` with mime from `videoFormat` and stream dimensions.  
4) Moonlight callbacks route decode units straight into the native queue (no Java fallback).  
5) Output thread dequeues and renders buffers to the panel surface.

## Components

- `app/src/main/jni/native_decoder.c` / `.h`
  - NDK `AMediaCodec` wrapper (setup/start/submit/stop/cleanup).
  - Uses `ANativeWindow_fromSurface` for output; applies a dataspace hint (HAL_DATASPACE_V0_JFIF) on the surface to match Sunshine’s SDR JPEG/full-range signaling.
  - Input thread runs inline via `nativeDecoderSubmit`; output thread renders continuously.
  - Logs negotiated input/output formats (color-standard/range/transfer) for debugging.
- `app/src/main/java/com/limelight/nvstream/jni/MoonBridge.java`
  - JNI exports for native decoder control (`nativeDecoderSetSurface`, `nativeDecoderSetup/Start/Stop/Cleanup/Submit`).
- `app/src/main/java/com/limelight/binding/video/NativeDecoderRenderer.java`
  - Implements `VideoDecoderRenderer`; forwards all calls to the native decoder and exposes HDR mode passthrough.
- `app/src/main/java/com/example/moonlight_spatialsdk/MoonlightPanelRenderer.kt`
  - Owns a single `NativeDecoderRenderer`; attaches the Spatial panel surface and preconfigures decoder.
- `app/src/main/java/com/example/moonlight_spatialsdk/MoonlightConnectionManager.kt`
  - Supplies the native renderer to `NvConnection.start`; no Java renderer fallback. HDR callbacks are forwarded to the native decoder.
- `app/src/main/java/com/example/moonlight_spatialsdk/PancakeActivity.kt`
  - Stream config UI now includes HDR enable and “prefer full range” toggles; values are stored in shared prefs and flow into stream config.
- `app/src/main/jni/moonlight-core/Android.mk`
  - Builds `native_decoder.c`, links `mediandk` and `android`.

## Lifecycle

- Surface attach: `MoonlightPanelRenderer.attachSurface(surface)` → `nativeDecoderSetSurface`.
- Preconfigure: `preConfigureDecoder()` calls `nativeDecoderSetup` with prefs (format/width/height/fps).
- Start: `NvConnection.start()` triggers `bridgeDrStart` → `nativeDecoderStart`.
- Submit: `BridgeDrSubmitDecodeUnit` now feeds `nativeDecoderSubmit` for all decode-unit types (SPS/PPS/VPS/PICDATA).
- Stop/Cleanup: `nativeDecoderStop` and `nativeDecoderCleanup` tear down codec/window.

## Data Handling

- Mime selection: `video/hevc` if H265 mask, `video/av01` if AV1 mask, else `video/avc`.
- CSD vs PICDATA:
  - Non-PICDATA buffers are queued with `AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG` and PTS=0.
  - PICDATA uses monotonic PTS (enqueueTimeMs * 1000, monotone-bumped).
- Flags: IDR frames set KEY_FRAME flag; no Java-side fused-IDR logic.

## Current Limitations

- Codec negotiation: output format currently reports dataspace 260, `color-range=2` (limited), `color-standard=130817` (platform default), `color-transfer=65791`, so colors still depend on codec defaults despite surface hint.
- Panel overlay: a translucent/white layer sometimes appears; removing headset can clear it. Likely compositor/panel config rather than decoder.
- HDR: signaling path exists (UI toggle, native hook), but HDR metadata and end-to-end validation are still pending.
- AV1: not yet validated.
- Error handling: minimal; decoder recovery/recreation is not yet implemented.
- Performance knobs (low-latency params, adaptive playback) are not set; this is a minimal bring-up.
- MediaPanelRenderOptions does not expose mips/forceSceneTexture; direct-to-compositor flags would require PanelConfigOptions if needed.

## Build Notes

- `Android.mk` links `-lmediandk -landroid -lnativewindow` and compiles `../native_decoder.c`.
- No Gradle changes required beyond existing JNI build.

## How to Exercise

1) Launch immersive; panel surface attaches and preconfigures the native decoder.  
2) Start a stream via Pancake → Immersive path; Sunshine provides stream config.  
3) Watch for native logs (`NativeDecoder`) to confirm setup/queue/pts activity.  
4) Expect no Java decoder activity; all decode goes through native path.

## Next Steps (after baseline validation)

- Add codec recovery (flush/restart) and better error surfacing.
- Wire HDR static info and guard AV1 paths.
- Add perf counters (queued/rendered) and optional tracing.
- Feature flags for HDR/AV1/recovery once baseline is stable.
- If overlay persists, consider a PanelConfigOptions path to set `mips=1`, `forceSceneTexture=false`, `enableTransparent=false` explicitly (MediaPanelRenderOptions doesn’t expose these).
