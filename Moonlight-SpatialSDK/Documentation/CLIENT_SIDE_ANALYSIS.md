# Client-Side Logcat Analysis - Complete Breakdown

## Executive Summary

**Root Cause**: The decoder is being configured TWICE - once via `preConfigureDecoder()` and once via MoonBridge's `bridgeDrSetup()`. The first configuration successfully binds the surface to a decoder instance, but when MoonBridge tries to configure again with the negotiated parameters, it fails because the surface is already connected. This prevents video decoding, causing the server to terminate with "lack of video traffic" (error -102).

## Detailed Timeline

### 1. Surface Attachment (Line 4283)

```
12-06 14:52:34.019 I/ImmersiveActivity(27534): Surface attached for panel entity=com.meta.spatial.core.Entity@10000a
```

- Surface is attached to the panel
- `attachSurface()` is called
- `preConfigureDecoder()` is called immediately after

### 2. First Decoder Configuration - SUCCESS (Line 4599)

```
12-06 14:52:34.077 I/com.limelight.LimeLog(27534): Configuring with format: {max-height=720, color-transfer=3, max-width=1280, low-latency=1, mime=video/hevc, width=1280, color-range=2, frame-rate=60, color-standard=1, height=720}
```

- This is from `preConfigureDecoder()` called in `surfaceConsumer`
- Decoder is successfully configured with preference values
- Surface is bound to this decoder instance

### 3. Stream Start (Lines 4968-4985)

```
12-06 14:52:34.191 I/ImmersiveActivity(27534): Starting stream - surface ready and paired host=10.1.95.5 port=47989 appId=0
12-06 14:52:34.192 I/ImmersiveActivity(27534): startStream invoked host=10.1.95.5 port=47989 appId=0
12-06 14:52:34.208 I/MoonlightConnectionMgr(27534): NvConnection.start invoked host=10.1.95.5
```

- Connection starts after surface is ready and paired
- `NvConnection.start()` is invoked

### 4. Connection Stages Complete (Lines 5732-5799)

```
12-06 14:52:35.643 I/com.limelight.LimeLog(27534): <root status_code="200"><sessionUrl0>rtspenc://10.1.95.5:48010</sessionUrl0><gamesession>1</gamesession></root>
12-06 14:52:35.644 I/MoonlightConnectionMgr(27534): stageComplete Moonlight
12-06 14:52:35.647 I/MoonlightConnectionMgr(27534): stageComplete RTSP handshake
12-06 14:52:35.943 I/MoonlightConnectionMgr(27534): stageComplete control stream initialization
12-06 14:52:35.944 I/MoonlightConnectionMgr(27534): stageComplete video stream initialization
12-06 14:52:35.944 I/MoonlightConnectionMgr(27534): stageComplete input stream initialization
12-06 14:52:35.951 I/MoonlightConnectionMgr(27534): stageComplete control stream establishment
```

- All connection stages complete successfully
- RTSP handshake succeeds
- Video stream initialization completes

### 5. Connection Termination - CRITICAL (Line 5799)

```
12-06 14:52:35.954 W/MoonlightConnectionMgr(27534): connectionTerminated error=-102
```
- Error -102 = "lack of video traffic"

- Server terminates because no video frames are being decoded

### 6. Second Decoder Configuration Attempt - FAILS (Lines 5846-6712)

```
12-06 14:52:35.964 I/com.limelight.LimeLog(27534): Configuring with format: {max-height=720, color-transfer=3, max-width=1280, low-latency=1, mime=video/hevc, width=1280, color-range=2, frame-rate=60, color-standard=1, height=720}
```

- MoonBridge calls `bridgeDrSetup()` which calls `decoderRenderer.setup()`
- This is the SECOND configuration attempt (first was in `preConfigureDecoder()`)

**Multiple configuration attempts all fail with the same error:**

```
12-06 14:52:35.999 E/BufferQueueProducer( 2832): [SurfaceTexture-1347-2832-225](id:b10000000e1,api:3,p:27534,c:2832) connect: already connected (cur=3 req=3)
12-06 14:52:35.999 E/SurfaceUtils(27534): Failed to connect to surface 0xb4000075b1b84010, err -22
12-06 14:52:35.999 E/MediaCodec(27534): nativeWindowConnect returned an error: Invalid argument (-22)
12-06 14:52:35.999 E/MediaCodec(27534): configure failed with err 0xffffffea, resetting...
```

**Error Pattern:**

- `already connected (cur=3 req=3)` - Surface is already connected to a decoder
- `err -22` - Invalid argument (EINVAL)
- The decoder tries multiple times (decoder configuration try: 1, 2, 3, 4, 5...) but all fail
- Eventually: `stageFailed video stream establishment error=-5`

## Root Cause Analysis

### The Problem

1. **First Configuration (preConfigureDecoder)**: 
   - Called in `surfaceConsumer` after `attachSurface()`
   - Successfully configures decoder with preference values
   - Binds surface to decoder instance

2. **Second Configuration (bridgeDrSetup)**:
   - Called by MoonBridge when connection is established
   - Tries to configure decoder with negotiated parameters
   - FAILS because surface is already connected to the first decoder instance

3. **Result**:
   - Decoder cannot be reconfigured
   - No video frames are decoded
   - Server terminates with "lack of video traffic"

### Why This Happens

The MediaCodec decoder can only be configured with a surface ONCE per decoder instance. When `preConfigureDecoder()` configures the decoder, it binds the surface. When MoonBridge tries to configure again (which it does to apply the actual negotiated parameters), the surface is already bound, causing the configuration to fail.

### The Solution

**We should NOT call `preConfigureDecoder()` before the connection starts.** Instead:

1. Only call `setRenderTarget()` when the surface is attached
2. Let MoonBridge call `setup()` when the connection is established (via `bridgeDrSetup()`)
3. This matches the moonlight-android pattern exactly

## Comparison with moonlight-android

In moonlight-android:

- `setRenderTarget()` is called in `surfaceChanged()`
- `NvConnection.start()` is called immediately after
- `setup()` is ONLY called by MoonBridge via `bridgeDrSetup()` after connection negotiation
- No pre-configuration happens

In our code:

- `setRenderTarget()` is called in `surfaceConsumer`
- `preConfigureDecoder()` is called (WRONG - this is the problem)
- `NvConnection.start()` is called
- MoonBridge tries to call `setup()` again but fails because surface is already bound

## Recommended Fix

Remove `preConfigureDecoder()` call from `surfaceConsumer`. Only call `setRenderTarget()` and let MoonBridge handle the decoder configuration when the connection is established.