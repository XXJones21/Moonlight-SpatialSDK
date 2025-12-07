# Moonlight-Android vs Moonlight-SpatialSDK Video Setup Comparison

## Executive Summary

**Critical Finding**: We are calling `decoderRenderer.setup()` BEFORE `NvConnection.start()`, which is incorrect. In moonlight-android, `setup()` is ONLY called by MoonBridge AFTER the connection negotiates the actual video format/width/height with the server.

## Detailed Comparison

### 1. Decoder Creation

#### moonlight-android (Game.java:376-394)

```java
decoderRenderer = new MediaCodecDecoderRenderer(
    this,
    prefConfig,
    new CrashListener() { ... },
    tombstonePrefs.getInt("CrashCount", 0),
    connMgr.isActiveNetworkMetered(),
    willStreamHdr,
    glPrefs.glRenderer,
    this);
```

- Created in `onCreate()`
- NOT configured at creation time
- Only stores preferences and creates decoder instances (avcDecoder, hevcDecoder, av1Decoder)

#### Moonlight-SpatialSDK (MoonlightPanelRenderer.kt:25-36)

```kotlin
private val decoderRenderer: MediaCodecDecoderRenderer by lazy {
  MediaCodecDecoderRenderer(
      activity,
      prefs,
      crashListener,
      consecutiveCrashCount,
      meteredData,
      requestedHdr,
      glRenderer,
      perfOverlayListener,
  )
}
```

- Created lazily when first accessed
- NOT configured at creation time
- **Status**: ✅ CORRECT - Same as moonlight-android

---

### 2. MediaCodecHelper Initialization

#### moonlight-android (Game.java:340)

```java
MediaCodecHelper.initialize(this, glPrefs.glRenderer);
```

- Called in `onCreate()` BEFORE creating decoderRenderer
- Must be called before any decoder operations

#### Moonlight-SpatialSDK (ImmersiveActivity.kt:102)

```kotlin
MediaCodecHelper.initialize(this, "spatial-panel")
```

- Called in `onCreate()` BEFORE creating decoderRenderer
- **Status**: ✅ CORRECT - Same as moonlight-android

---

### 3. Surface Attachment and Connection Start

#### moonlight-android (Game.java:2487-2501)

```java
@Override
public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    if (!attemptedConnection) {
        attemptedConnection = true;
        
        decoderRenderer.setRenderTarget(holder);  // 1. Set surface
        conn.start(new AndroidAudioRenderer(...),  // 2. Start connection
                   decoderRenderer, 
                   Game.this);
    }
}
```

**Flow:**

1. `setRenderTarget()` - Attach surface to decoder
2. `conn.start()` - Start connection (decoder NOT configured yet)
3. Connection negotiates with server
4. MoonBridge calls `bridgeDrSetup()` with ACTUAL negotiated format/width/height
5. `decoderRenderer.setup()` is called by MoonBridge with negotiated values

#### Moonlight-SpatialSDK (ImmersiveActivity.kt:150-155, 232-246)

```kotlin
surfaceConsumer = { panelEntity, surface ->
    SurfaceUtil.paintBlack(surface)
    moonlightPanelRenderer.attachSurface(surface)  // 1. Set surface
    isSurfaceReady = true
    startStreamIfReady()  // 2. Check if ready, then start
}

private fun startStreamIfReady() {
    if (params != null && isPaired && isSurfaceReady) {
        moonlightPanelRenderer.preConfigureDecoder()  // 3. ❌ WRONG: Setup BEFORE start
        connectionManager.startStream(...)  // 4. Start connection
    }
}
```

**Flow:**

1. `attachSurface()` → `setRenderTarget()` - Attach surface to decoder
2. `preConfigureDecoder()` → `decoderRenderer.setup()` - ❌ Configure with PREFERENCE values
3. `conn.start()` - Start connection (decoder already configured with wrong values)
4. Connection negotiates with server
5. MoonBridge calls `bridgeDrSetup()` with ACTUAL negotiated format/width/height
6. `decoderRenderer.setup()` is called AGAIN by MoonBridge with negotiated values

**Status**: ❌ **INCORRECT** - We're calling `setup()` twice and with wrong values the first time

---

### 4. Decoder Setup Timing

#### moonlight-android

- `setup()` is ONLY called by `MoonBridge.bridgeDrSetup()` AFTER connection negotiation
- Called with ACTUAL negotiated format, width, height, fps from server
- Timing: `NvConnection.start()` → Connection negotiation → `MoonBridge.bridgeDrSetup()` → `decoderRenderer.setup()`

#### Moonlight-SpatialSDK

- `setup()` is called TWICE:
  1. First: `preConfigureDecoder()` with PREFERENCE values (WRONG)
  2. Second: `MoonBridge.bridgeDrSetup()` with ACTUAL negotiated values (CORRECT)
- **Status**: ❌ **INCORRECT** - Should only be called once by MoonBridge

---

### 5. What `setup()` Does

From `MediaCodecDecoderRenderer.java:704-711`:

```java
public int setup(int format, int width, int height, int redrawRate) {
    this.initialWidth = width;
    this.initialHeight = height;
    this.videoFormat = format;
    this.refreshRate = redrawRate;
    
    return initializeDecoder(false);  // Creates and configures MediaCodec
}
```

`initializeDecoder()`:

- Creates MediaCodec instance
- Configures it with format, width, height
- Starts the decoder
- **This must happen AFTER connection negotiation to get correct values**

---

## Root Cause Analysis

### Why We Added `preConfigureDecoder()`

We were trying to solve the "lack of video traffic" error by ensuring the decoder is ready before the connection starts. However, this is the wrong approach because:

1. **Wrong Values**: We configure with preference values, but the server may negotiate different values
2. **Double Configuration**: We configure twice, which may cause issues
3. **Timing**: The decoder should be configured AFTER negotiation, not before

### The Real Issue

Looking at the logs, the connection fails at "control stream establishment" (error 11, portFlags 512). This is a NETWORK/CONNECTION issue, not a decoder configuration issue. The decoder being configured early doesn't help if the connection itself fails.

---

## Correct Implementation

### What We Should Do

1. **Remove `preConfigureDecoder()`** - Don't call `setup()` before `NvConnection.start()`
2. **Follow moonlight-android pattern**:
   - `setRenderTarget()` when surface is ready
   - `NvConnection.start()` immediately after
   - Let MoonBridge call `setup()` with negotiated values

### Corrected Flow

```kotlin
surfaceConsumer = { panelEntity, surface ->
    SurfaceUtil.paintBlack(surface)
    moonlightPanelRenderer.attachSurface(surface)  // Only setRenderTarget
    isSurfaceReady = true
    startStreamIfReady()
}

private fun startStreamIfReady() {
    if (params != null && isPaired && isSurfaceReady) {
        // NO preConfigureDecoder() call here!
        connectionManager.startStream(...)  // Start connection
        // MoonBridge will call setup() later with negotiated values
    }
}
```

---

## Key Differences Summary

| Aspect | moonlight-android | Moonlight-SpatialSDK | Status |
|--------|-------------------|---------------------|--------|
| Decoder Creation | onCreate() | Lazy initialization | ✅ OK |
| MediaCodecHelper.init | onCreate() before decoder | onCreate() before decoder | ✅ OK |
| setRenderTarget Timing | surfaceChanged() | surfaceConsumer callback | ✅ OK |
| setup() Call | Only by MoonBridge after negotiation | Called twice: before start + by MoonBridge | ❌ WRONG |
| setup() Values | Actual negotiated values | First: preferences, Second: negotiated | ❌ WRONG |
| Connection Start | Immediately after setRenderTarget | After setRenderTarget + preConfigureDecoder | ❌ WRONG |

---

## Conclusion

**The fix**: Remove `preConfigureDecoder()` and let MoonBridge handle decoder setup after connection negotiation, exactly like moonlight-android does.

The "lack of video traffic" error is likely due to the connection failing (control stream establishment error 11), not decoder configuration timing. We should investigate the connection failure instead of trying to pre-configure the decoder.

---

## Moonlight-Android End-to-End Video Pipeline (Full Reference)

This section summarizes the complete streaming pipeline as implemented in `moonlight-android`, focusing on video surfaces, decoder lifecycle, connection sequencing, and pairing/crypto. This is distilled from a line-by-line review of the repository (Game.java, NvConnection, MoonBridge JNI, MediaCodecDecoderRenderer, AndroidAudioRenderer, IdentityManager, PairingManager, NvHTTP, and helpers).

## Lifecycle Overview

1) **Process start / Activity onCreate (Game.java)**
   - Read prefs, initialize `MediaCodecHelper`, create `MediaCodecDecoderRenderer` (no setup), create `AndroidAudioRenderer`, construct `NvConnection` with uniqueId and optional pinned server cert.
   - Instantiate `IdentityManager` to load or create stable uniqueId (persisted to disk).

2) **Surface path**
   - Activity hosts a `SurfaceView`/holder; `surfaceCreated` may set frame rate hints.
   - `surfaceChanged`: first call triggers `decoderRenderer.setRenderTarget(holder)` **then** `conn.start(audioRenderer, decoderRenderer, listener)`.
   - No decoder `setup()` before start. Surface is valid and attached before `NvConnection.start()`.

3) **Connection start (NvConnection.start)**
   - Builds `StreamConfiguration` from prefs (resolution, fps, bitrate, supported formats, SOPS flag, audio cfg).
   - Uses `NvHTTP` with pinned cert (if paired) and stable uniqueId.
   - Calls native `MoonBridge.startConnection(...)`.

4) **Negotiation → Decoder setup**
   - MoonBridge negotiates with server (RTSP, control/video/input ports).
   - When the server provides negotiated format/width/height/fps, native calls `bridgeDrSetup()` → `decoderRenderer.setup(format, w, h, fps)`.
   - `setup()` creates/configures `MediaCodec`, binds the already-set surface, starts codec, and initializes decode threads.
   - Decoder reconfigurations may occur if server renegotiates; `setup()` handles recreate/reconfigure.

5) **Steady state**
   - Video frames decoded to the surface; audio via `AndroidAudioRenderer`.
   - Connection listener receives stage callbacks; status shown to user.

6) **Teardown**
   - On failure/termination, `connection.stop()` is called; decoder `stop()` releases codec and choreographer thread; audio stopped.

## Pairing / Crypto

- `IdentityManager` persists an 8-byte hex `uniqueId` to disk; reused across sessions.
- `PairingManager` handles PIN; on success, server cert is saved and pinned.
- `NvHTTP` TLS: tries default trust; if untrusted and a pinned cert exists, it pins that exact cert; otherwise fails.
- Every request and `NvConnection` uses the pinned cert (if available) and the stable uniqueId.

## Supported Formats (desktop/Android)

- Offers H.264, HEVC, AV1; HDR variants when enabled. Surface is non-SurfaceTexture (SurfaceHolder).
- SOPS can be toggled; defaults enabled in Moonlight, but it can be disabled if device/pipeline needs simplification.

## Decoder specifics (MediaCodecDecoderRenderer)

- `setRenderTarget(holder)` only stores the surface for later `configure()`.
- `setup(format, w, h, fps)` → `initializeDecoder(false)` → creates `MediaCodec` for the negotiated format, configures with the stored surface, starts codec and decode threads.
- Recovery paths exist (`stop`, `reset`, codec recovery) but are only used after a decoder has been started.

## Ordering Guarantees

1. Surface attached **before** `NvConnection.start`.
2. `setup()` called **only** by MoonBridge after negotiation with real stream params.
3. No pre-configuration of decoder before `start()`.
4. Stable uniqueId and pinned cert drive HTTPS/RTSP crypto; pairing must succeed so the cert is stored.

## Relevance to Spatial SDK

- Panel/video surface must be attached before calling `start`.
- Do **not** call decoder `setup()` prior to connection; let MoonBridge drive configuration.
- Ensure pinned cert is present after pairing; otherwise RTSP auth fails.
- If device decoders are unstable, reducing formats (e.g., H.264 only, SOPS off) can simplify setup.
- Spatial media panel guidance from Meta (see [Meta Spatial SDK media playback docs](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-media-playback)) aligns with: create panel surface, attach surface, start playback/stream once surface is ready; let player/decoder configure after negotiation.
