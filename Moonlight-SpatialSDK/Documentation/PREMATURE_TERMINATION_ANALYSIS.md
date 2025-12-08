# Premature Server Termination Analysis

## Executive Summary

**ROOT CAUSE**: `MoonBridge.setupBridge()` is called INSIDE `NvConnection.start()` on a background thread, AFTER `startApp()` HTTP call completes. The server connects and terminates BEFORE `setupBridge()` executes, because the video renderer isn't registered yet.

**Timeline**:
- 13:24:31.984: `NvConnection.start()` invoked (starts background thread)
- 13:24:32.018: Server connects (`CLIENT CONNECTED`) - 34ms later
- 13:24:32.018: Server terminates (`Process terminated`) - immediately after connection
- 13:24:32.168: `MoonBridge.setupBridge()` called - 150ms AFTER server termination

**Problem**: Server expects video renderer to be ready when control stream connects, but `setupBridge()` hasn't been called yet, so `videoRenderer` is null and `bridgeDrSetup()` can't be called.

---

## Code Flow Analysis

### Current Flow (PROBLEMATIC)

1. **`ImmersiveActivity.surfaceConsumer`** (line 184):
   - `attachSurface(surface)` - sets render target
   - `preConfigureDecoder()` - configures decoder (causes "already connected" errors)
   - `connectToHost()` - initiates connection

2. **`MoonlightConnectionManager.startStream()`** (line 219):
   - Creates `NvConnection`
   - Calls `connection.start(audioRenderer, decoderRenderer, this)`

3. **`NvConnection.start()`** (line 382-428):
   - **Runs on NEW THREAD** (line 384)
   - Calls `startApp()` - HTTP call to launch app (takes ~34ms)
   - Acquires semaphore (line 417)
   - **THEN calls `MoonBridge.setupBridge()`** (line 428) - TOO LATE!
   - Calls `MoonBridge.startConnection()` (line 440)

4. **Server Side**:
   - Receives RTSP PLAY request
   - Control stream connects
   - **Expects video renderer to be ready**
   - **Terminates because `bridgeDrSetup()` can't be called** (videoRenderer is null)

---

## Root Cause

`MoonBridge.setupBridge()` MUST be called BEFORE `NvConnection.start()` is invoked, not inside it. The current code calls it inside `NvConnection.start()` on a background thread, after `startApp()` completes. This means:

1. Connection starts (RTSP handshake begins)
2. Server connects (control stream established)
3. Server expects video decoder to be ready
4. `bridgeDrSetup()` is called by native code
5. But `videoRenderer` is still null because `setupBridge()` hasn't executed yet
6. Server terminates because no video frames are received

---

## Solution

**Call `MoonBridge.setupBridge()` BEFORE `NvConnection.start()`**

### Implementation Plan

1. **Modify `MoonlightConnectionManager.startStream()`**:
   - Call `MoonBridge.setupBridge()` BEFORE creating `NvConnection`
   - Ensure bridge is ready before connection starts

2. **Remove `preConfigureDecoder()` call**:
   - This causes "already connected" errors
   - Let MoonBridge handle decoder setup after negotiation

3. **Timing**:
   - `setupBridge()` should be called synchronously before `NvConnection.start()`
   - This ensures `videoRenderer` is registered when native code calls `bridgeDrSetup()`

---

## Expected Flow (CORRECT)

1. **Panel surface ready**:
   - `attachSurface(surface)` - sets render target
   - NO `preConfigureDecoder()` call

2. **Before starting connection**:
   - `MoonBridge.setupBridge(decoderRenderer, audioRenderer, connectionListener)` - **CALL FIRST**
   - This registers the video renderer

3. **Start connection**:
   - `NvConnection.start()` - connection begins
   - `startApp()` - HTTP call
   - `MoonBridge.startConnection()` - native connection starts
   - RTSP handshake
   - Control stream connects
   - **`bridgeDrSetup()` is called** - videoRenderer is ready!
   - Decoder is configured with negotiated values
   - Video frames start flowing

---

## Code Changes Required

### File: `MoonlightConnectionManager.kt`

**Location**: `startStream()` method, before `NvConnection` creation

**Change**: Call `MoonBridge.setupBridge()` before creating `NvConnection`:

```kotlin
fun startStream(...) {
    executor.execute {
        try {
            // ... existing code ...
            
            // CRITICAL: Setup bridge BEFORE creating NvConnection
            // This ensures videoRenderer is registered when native code calls bridgeDrSetup()
            MoonBridge.setupBridge(decoderRenderer, audioRenderer, this)
            Log.i(tag, "startStream: MoonBridge.setupBridge() called before connection start")
            
            connection = NvConnection(...)
            Log.i(tag, "startStream: NvConnection created, calling start()")
            connection?.start(audioRenderer, decoderRenderer, this)
            // Note: NvConnection.start() will call setupBridge() again, but it's idempotent
            // However, we should remove the duplicate call from NvConnection.start()
        } catch (e: Exception) {
            // ... error handling ...
        }
    }
}
```

### File: `NvConnection.java`

**Location**: `start()` method, line 428

**Change**: Remove duplicate `setupBridge()` call (or make it conditional):

```java
synchronized (MoonBridge.class) {
    // Only setup bridge if not already set up
    if (MoonBridge.videoRenderer == null) {
        LimeLog.info("NvConnection: setupBridge called with videoRenderer=" + ...);
        MoonBridge.setupBridge(videoDecoderRenderer, audioRenderer, connectionListener);
    } else {
        LimeLog.info("NvConnection: setupBridge already called, skipping");
    }
    // ... rest of code ...
}
```

---

## Verification

After fix, expected log sequence:

1. `startStream: MoonBridge.setupBridge() called before connection start`
2. `MOONBRIDGE_SETUPBRIDGE_CALLED`
3. `NvConnection.start invoked`
4. `NvConnection: startConnection called`
5. RTSP handshake logs
6. `CLIENT CONNECTED` (server side)
7. `bridgeDrSetup called` (native code)
8. `MOONBRIDGE_BRIDGEDRSETUP_CALLED`
9. Video frames start flowing
10. **NO "Process terminated" message**

---

## Risks

1. **Idempotency**: `MoonBridge.setupBridge()` should be safe to call multiple times
2. **Thread safety**: Ensure `setupBridge()` is called on correct thread
3. **Timing**: Must be called before `NvConnection.start()`, but after decoder renderer is created

---

## Related Issues

- Panel registration happens at 13:24:31.787
- Connection starts at 13:24:31.984
- Server connects at 13:24:32.018
- `setupBridge()` called at 13:24:32.168 (too late)

The 184ms delay between connection start and `setupBridge()` is the critical issue.

