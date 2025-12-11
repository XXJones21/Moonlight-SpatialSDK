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
  - Uses `ANativeWindow_fromSurface` for output; applies dataspace hints (HAL_DATASPACE_V0_SRGB for SDR, HAL_DATASPACE_ST2084 for HDR) on the surface based on stream configuration.
  - Input thread runs inline via `nativeDecoderSubmit`; output thread renders continuously.
  - **Decoder Selection**: Attempts explicit decoder selection via JNI bridge to `MediaCodecHelper.findBestDecoderForMime()`, falls back to `createDecoderByType()` if unavailable.
  - **QTI Decoder Detection**: Detects Qualcomm (QTI) decoders by name (`c2.qti.*`, `omx.qcom.*`) to conditionally set color keys.
  - **Conditional Color Configuration**:
    - HDR mode: Only sets `COLOR_RANGE` (FULL), lets decoder detect color transitions automatically.
    - SDR mode: Sets `COLOR_RANGE`, `COLOR_STANDARD` (BT709), `COLOR_TRANSFER` (SRGB) for non-QTI decoders.
    - QTI decoders: Skips color keys entirely (they use C2 parameters instead).
  - **Early HDR Inference**: Automatically enables HDR mode when 10-bit format mask is detected in video format.
  - **Low Latency Configuration**: Sets vendor-specific low latency options (QTI, HiSilicon, Exynos, Amlogic) and `KEY_MAX_OPERATING_RATE` for Qualcomm decoders.
  - **Adaptive Playback**: Configures `KEY_MAX_WIDTH` and `KEY_MAX_HEIGHT` when supported for dynamic resolution changes.
  - **State Machine**: Tracks decoder state (UNINITIALIZED, CREATED, CONFIGURED, STARTED, ERROR, STOPPED) throughout lifecycle.
  - **Error Recovery**: Implements flush and restart recovery mechanisms with attempt tracking.
  - **Comprehensive Logging**: Logs negotiated input/output formats (color-standard/range/transfer), decoder name, QTI status, state transitions, and configuration decisions.
- `app/src/main/java/com/limelight/nvstream/jni/MoonBridge.java`
  - JNI exports for native decoder control (`nativeDecoderSetSurface`, `nativeDecoderSetup/Start/Stop/Cleanup/Submit`).
  - **Decoder Selection Bridge**: `findBestDecoderForMime()` - bridges to `MediaCodecHelper.findFirstDecoder()` for explicit decoder selection.
  - **Capability Checking Bridge**:
    - `decoderSupportsLowLatency()` - checks `FEATURE_LowLatency` support (Android R+).
    - `decoderSupportsAdaptivePlayback()` - checks `FEATURE_AdaptivePlayback` support.
    - `decoderSupportsMaxOperatingRate()` - checks Qualcomm decoder support for max operating rate.
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
- **HDR Detection**: Automatically infers HDR mode from 10-bit format mask (0x2200) in video format, enabling HDR before static metadata arrives.
- **Decoder Restart on HDR Change**: If HDR state changes after decoder configuration, decoder is released and will be reconfigured on next setup call.
- CSD vs PICDATA:
  - Non-PICDATA buffers are queued with `AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG` and PTS=0.
  - PICDATA uses monotonic PTS (enqueueTimeMs * 1000, monotone-bumped).
- Flags: IDR frames set KEY_FRAME flag; no Java-side fused-IDR logic.
- **Error Recovery**: On `dequeueInputBuffer` failures, attempts flush recovery first, then restart recovery if needed, with maximum attempt tracking.

## Current Limitations

- **QTI Decoder Detection**: System property-based detection is unreliable. Device may be incorrectly identified as non-QTI even when using QTI decoder (`c2.qti.av1.decoder`). However, detection from actual decoder name works correctly and is used for color key decisions.
- **Color Range Override**: Decoder may override our `COLOR_RANGE=FULL` setting and output `COLOR_RANGE=LIMITED` based on stream metadata. This is expected decoder behavior, especially for QTI decoders.
- **Panel Overlay**: A translucent/white layer sometimes appears; removing headset or sleep/wake cycle can clear it. This is a known Spatial SDK platform limitation with surface color space initialization, not a decoder configuration issue. See `POST_MORTEM.md` for details.
- **HDR**: HDR signaling path exists (UI toggle, native hook, early inference, static metadata support), but end-to-end validation on Quest 3 hardware is still pending.
- **AV1**: AV1 decoding is functional but not yet fully validated across all scenarios.
- **MediaPanelRenderOptions**: Does not expose mips/forceSceneTexture; direct-to-compositor flags would require PanelConfigOptions if needed.

## Build Notes

- `Android.mk` links `-lmediandk -landroid -lnativewindow` and compiles `../native_decoder.c`.
- No Gradle changes required beyond existing JNI build.

## How to Exercise

1) Launch immersive; panel surface attaches and preconfigures the native decoder.  
2) Start a stream via Pancake → Immersive path; Sunshine provides stream config.  
3) Watch for native logs (`NativeDecoder`) to confirm setup/queue/pts activity.  
4) Expect no Java decoder activity; all decode goes through native path.

## Implemented Features (Post-Baseline)

### Phase 1: Critical Fixes ✅

- QTI decoder detection (via system properties and decoder name)
- Conditional color key setting (HDR: only COLOR_RANGE, SDR: all keys, QTI: skip keys)
- Early HDR inference from 10-bit format mask
- Decoder name logging

### Phase 2: Decoder Selection ✅

- JNI bridge to `MediaCodecHelper.findBestDecoderForMime()` for explicit decoder selection
- Uses `AMediaCodec_createCodecByName()` with selected decoder from MediaCodecHelper
- **MediaCodecHelper Initialization**: `MediaCodecHelper.initialize()` is called in `ImmersiveActivity.onCreate()` with Quest 3's Adreno 740 GPU identifier before creating decoder renderer
- Successfully selects preferred decoders (e.g., `c2.qti.av1.decoder` for AV1) using MediaCodecHelper's preference and blacklisting logic

### Phase 3: Performance Optimizations ✅

- Low latency configuration via JNI capability checks
- Vendor-specific low latency options (QTI, HiSilicon, Exynos, Amlogic)
- `KEY_MAX_OPERATING_RATE` for Qualcomm decoders (Android M+)
- Adaptive playback support with `KEY_MAX_WIDTH` and `KEY_MAX_HEIGHT`

### Phase 4: Robustness ✅

- Decoder state machine (UNINITIALIZED, CREATED, CONFIGURED, STARTED, ERROR, STOPPED)
- Error recovery mechanisms (flush and restart recovery)
- Error recovery attempt tracking (max 3 attempts)
- Enhanced logging with decoder state, name, and configuration decisions

## Next Steps

### Completed Actions ✅

- **MediaCodecHelper Initialization**: ✅ Implemented in `ImmersiveActivity.onCreate()` with Quest 3's Adreno 740 GPU identifier
- **QTI Detection**: ✅ Using actual decoder name for QTI detection (works correctly)
- **Color Keys for QTI**: ✅ Correctly skipping color keys for QTI decoders (matching moonlight-android approach)

### Future Enhancements

- Add perf counters (queued/rendered) and optional tracing.
- Feature flags for HDR/AV1/recovery once baseline is stable.
- Platform workaround for white overlay (may require Spatial SDK API changes).
- Remove system property-based QTI detection in favor of decoder name detection.
- Add decoder capability logging when MediaCodecHelper is available.
