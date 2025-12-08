# Decoder Setup Analysis - Root Cause Investigation

## Problem Summary

The decoder is never configured during connection, causing "lack of video traffic" error (-102) and black screen.

## Critical Finding: Decoder Setup Flow

### How Decoder Setup Works in Moonlight-Core

1. **Connection Flow** (from `Connection.c`):
   - `LiStartConnection()` is called with video renderer callbacks
   - Connection progresses through stages:
     - STAGE_PLATFORM_INIT (1)
     - STAGE_NAME_RESOLUTION (2)
     - STAGE_AUDIO_STREAM_INIT (3)
     - STAGE_RTSP_HANDSHAKE (4) ← **NegotiatedVideoFormat is set here**
     - STAGE_CONTROL_STREAM_INIT (5)
     - STAGE_VIDEO_STREAM_INIT (6)
     - STAGE_INPUT_STREAM_INIT (7)
     - STAGE_CONTROL_STREAM_START (8)
     - STAGE_VIDEO_STREAM_START (9) ← **Decoder setup happens here**
     - STAGE_AUDIO_STREAM_START (10)
     - STAGE_INPUT_STREAM_START (11)

2. **Video Format Negotiation** (from `RtspConnection.c`):
   - During RTSP handshake (stage 4), `NegotiatedVideoFormat` is set based on server response
   - This happens in `performRtspHandshake()` → RTSP SETUP response parsing
   - Format is set to one of: `VIDEO_FORMAT_H264`, `VIDEO_FORMAT_H265`, `VIDEO_FORMAT_AV1_MAIN8`, etc.

3. **Decoder Setup Call** (from `VideoStream.c:319-331`):

   ```c
   int startVideoStream(void* rendererContext, int drFlags) {
       // This assertion MUST pass for setup to be called
       LC_ASSERT(NegotiatedVideoFormat != 0);
       
       // This is where bridgeDrSetup() is invoked
       err = VideoCallbacks.setup(NegotiatedVideoFormat, StreamConfig.width,
           StreamConfig.height, StreamConfig.fps, rendererContext, drFlags);
       // ...
   }
   ```

4. **JNI Bridge** (from `callbacks.c:107-124`):

   ```c
   int BridgeDrSetup(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) {
       JNIEnv* env = GetThreadEnv();
       // Calls Java: MoonBridge.bridgeDrSetup()
       err = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, BridgeDrSetupMethod, 
                                         videoFormat, width, height, redrawRate);
       // ...
   }
   ```

5. **Java Bridge** (from `MoonBridge.java`):

   ```java
   public static int bridgeDrSetup(int videoFormat, int width, int height, int redrawRate) {
       if (videoRenderer != null) {
           return videoRenderer.setup(videoFormat, width, height, redrawRate);
       }
       return -1;
   }
   ```

## Root Cause Analysis

### What We See in Logs

- ✅ Surface attached (`MoonlightPanelRenderer.attachSurface`)
- ✅ Render target stored (`MediaCodecDecoderRenderer.setRenderTarget`)
- ✅ Connection starts (`NvConnection.start` invoked)
- ✅ Stage "Moonlight" starts (only ONE stage logged)
- ❌ **NO `bridgeDrSetup` logs** - decoder setup never called
- ❌ **NO `MediaCodecDecoderRenderer.setup` logs** - decoder never configured
- ❌ Connection terminates after ~11 seconds with "lack of video traffic"

### Critical Issue

The connection is **failing before reaching STAGE_VIDEO_STREAM_START** (stage 9), which is when `VideoCallbacks.setup()` is called.

**Possible causes:**

1. **Connection fails during early stages** (before RTSP handshake completes)
   - If RTSP handshake fails, `NegotiatedVideoFormat` remains 0
   - `startVideoStream()` will assert and fail, never calling `setup()`

2. **Stage logging issue** - We only see "stageStarting Moonlight" but no other stages
   - This suggests connection might be failing very early
   - Or stage callbacks aren't being properly forwarded

3. **Video renderer not registered** - If `MoonBridge.setupBridge()` isn't called before `LiStartConnection()`, callbacks won't work
   - But we see `NvConnection.start` is called, which should call `MoonBridge.setupBridge()`

## Verification Steps Needed

### 1. Check if RTSP Handshake Completes

- Look for RTSP-related logs in native code
- Verify `NegotiatedVideoFormat` is set (should be non-zero)
- Check if connection reaches stage 4 (RTSP handshake)

### 2. Check Stage Progression

- Add logging to see ALL stages, not just "Moonlight"
- Verify stages are being reported correctly
- Check if connection fails at a specific stage

### 3. Verify Renderer Registration

- Confirm `MoonBridge.setupBridge()` is called with correct renderer
- Verify `VideoCallbacks.setup` pointer is not NULL
- Check if `bridgeDrSetup` method ID is resolved correctly

### 4. Check Connection Context

- Verify all connection parameters are valid
- Check if server is reachable and responding
- Verify RTSP session URL is correct

## Next Steps

1. **Add native logging** to track:
   - When `NegotiatedVideoFormat` is set
   - When `startVideoStream()` is called
   - When `VideoCallbacks.setup()` is invoked
   - All connection stages

2. **Add Java-side logging** to track:
   - When `MoonBridge.setupBridge()` is called
   - When `bridgeDrSetup()` is invoked
   - All stage callbacks

3. **Compare with Moonlight-Android** to verify:
   - Same connection flow
   - Same renderer registration
   - Same stage progression

## Files to Review

### Native Code (moonlight-core)
- `callbacks.c` - JNI bridge functions
- `Connection.c` - Connection lifecycle and stages
- `VideoStream.c` - Video stream initialization and decoder setup
- `RtspConnection.c` - RTSP handshake and format negotiation

### Java Code (Moonlight-SpatialSDK)
- `MoonBridge.java` - JNI bridge and renderer registration
- `NvConnection.java` - Connection wrapper
- `MoonlightConnectionManager.kt` - Connection lifecycle management
- `MediaCodecDecoderRenderer.java` - Decoder implementation

