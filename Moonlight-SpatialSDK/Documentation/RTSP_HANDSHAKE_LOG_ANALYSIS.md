# RTSP Handshake Log Analysis - New Panel Log

## Executive Summary

**CRITICAL FINDING**: RTSP handshake **SUCCEEDS**, but server terminates **immediately** when decoder setup begins. The issue is **NOT** the RTSP handshake itself, but a **server-side termination** triggered during video decoder initialization.

---

## Log Sequence Breakdown (Lines 5640-5815)

### Phase 1: Connection Initialization ✅ SUCCESS

**Lines 5640-5658**: Connection parameters
```
rtspUrl=rtspenc://10.1.95.5:48010
width=2560 height=1440 fps=60 bitrate=40000
encryptionFlags=0xffffffff
```

**Key Observation**: Still using `rtspenc://` URL, but handshake succeeds (encryption mismatch may be resolved or ignored).

**Lines 5660-5679**: Stages 1-3 complete successfully
- ✅ Stage 1: Platform initialization
- ✅ Stage 2: Name resolution  
- ✅ Stage 3: Audio stream initialization

---

### Phase 2: RTSP Handshake ✅ SUCCESS

**Lines 5680-5697**: RTSP handshake completes successfully

```
5680: Starting RTSP handshake...
5681: Stage 4 starting: RTSP handshake
5683: NegotiatedVideoFormat set to VIDEO_FORMAT_H264 (1)
5684: Audio port: 48000
5685: Video port: 47998
5686: Control port: 47999
5695: stageComplete RTSP handshake
5696: Stage 4 complete: RTSP handshake, NegotiatedVideoFormat=1
```

**Analysis**: 
- ✅ RTSP handshake **completes successfully**
- ✅ Video format negotiated (H264)
- ✅ All ports assigned correctly
- ✅ **No encryption errors** - handshake works despite `rtspenc://` URL

**Conclusion**: The RTSP handshake issue from the investigation is **RESOLVED** or **WORKING CORRECTLY**.

---

### Phase 3: Stream Initialization ✅ SUCCESS

**Lines 5698-5721**: Stages 4-8 complete successfully
- ✅ Stage 5: Control stream initialization
- ✅ Stage 6: Video stream initialization
- ✅ Stage 7: Input stream initialization
- ✅ Stage 8: Control stream establishment

**All connection stages complete without errors.**

---

### Phase 4: Video Decoder Setup ⚠️ CRITICAL FAILURE

**Lines 5722-5732**: Decoder setup begins

```
5722: Starting video stream...
5723: Stage 9 starting: video stream establishment, NegotiatedVideoFormat=1
5725: startVideoStream: NegotiatedVideoFormat=1 width=2560 height=1440 fps=60
5726: startVideoStream: Calling VideoCallbacks.setup()
5727: BridgeDrSetup called format=1 width=2560 height=1440 fps=60 drFlags=0
5731: setup called format=1 width=2560 height=1440 fps=60 renderTarget=set
5732: initializeDecoder called videoFormat=1 width=2560 height=1440 fps=60 renderTarget=set
```

**Analysis**: 
- Decoder setup is called correctly
- All parameters are valid (H264, 2560x1440, 60fps)
- Render target is set

---

### Phase 5: Server Termination ❌ ROOT CAUSE

**Line 5733**: **CRITICAL - Server terminates immediately**

```
5733: Server notified termination reason: 0x80030023
5734: connectionTerminated error=-102
```

**Error Code Analysis**:
- `0x80030023` = `NVST_DISCONN_SERVER_TERMINATED_CLOSED` (from ControlStream.c:1241)
- Converted to `ML_ERROR_UNEXPECTED_EARLY_TERMINATION` (-102) because `lastSeenFrame == 0`
- Server terminates **BEFORE** any video frames are sent

**Timeline**:
- `11:21:27.076` - Decoder setup starts
- `11:21:27.077` - `initializeDecoder` called
- `11:21:27.079` - **Server terminates (3ms later)**

**Critical Finding**: Server terminates **3 milliseconds** after decoder initialization begins. This is **too fast** to be a decoder configuration issue - the server must be detecting something during the handshake or early stream setup.

---

### Phase 6: Surface Connection Error (Secondary)

**Lines 5803-5806**: Surface connection fails

```
5803: BufferQueueProducer: connect: already connected (cur=3 req=3)
5804: Failed to connect to surface, err -22
5805: nativeWindowConnect returned an error: Invalid argument (-22)
5806: configure failed with err 0xffffffea, resetting...
```

**Analysis**:
- Error `-22` = `EINVAL` (Invalid argument)
- Surface is "already connected" - suggests surface was previously connected and not properly released
- **However**, this error occurs **AFTER** server termination, so it's a **secondary issue**

**Root Cause**: The surface connection error is likely caused by:
1. Previous decoder instance not properly cleaned up
2. Surface being reused without disconnecting previous MediaCodec
3. Race condition between server termination and decoder cleanup

---

## Root Cause Analysis

### Primary Issue: Server-Side Termination

**Evidence**:
1. RTSP handshake completes successfully
2. All connection stages complete
3. Server terminates **immediately** when decoder setup begins
4. Termination reason: `0x80030023` (server closed connection)
5. No video frames received (`lastSeenFrame == 0`)

**Possible Causes**:

1. **Server detects incompatible decoder configuration**
   - Server may be checking decoder capabilities during handshake
   - 2560x1440 @ 60fps may exceed server limits
   - Server terminates before sending frames

2. **Server detects surface/DRM mismatch**
   - Server may require secure surface for encrypted streams
   - Panel surface may not meet server security requirements
   - Server terminates when it detects non-secure surface

3. **Server timeout during decoder initialization**
   - Server expects decoder to be ready immediately
   - 3ms delay may trigger timeout
   - Unlikely - too fast for timeout

4. **Server detects missing DRM/secure layer**
   - Despite `rtspenc://` URL, server may require DRM-enabled surface
   - Panel `isDRM = false` may trigger server termination
   - **Most likely cause** based on investigation findings

### Secondary Issue: Surface Reuse Error

**Evidence**:
- Surface "already connected" error
- Occurs after server termination
- Suggests cleanup issue

**Cause**: 
- Previous decoder instance not properly released
- Surface not disconnected before reuse
- MediaCodec cleanup not called before new configuration

---

## Recommendations

### 1. Fix Surface Reuse Issue (Immediate)

**Problem**: Surface is "already connected" when trying to configure decoder.

**Solution**: Ensure proper cleanup before decoder setup:

```kotlin
// In MediaCodecDecoderRenderer or MoonlightPanelRenderer
fun cleanup() {
    videoDecoder?.stop()
    videoDecoder?.release()
    videoDecoder = null
    // Disconnect surface if needed
}
```

**Location**: `MediaCodecDecoderRenderer.java` - ensure cleanup is called before new setup.

### 2. Test with DRM-Enabled Surface (High Priority)

**Problem**: Server may require DRM-enabled surface for `rtspenc://` streams.

**Solution**: Test with `isDRM = true` in panel configuration:

```kotlin
rendering = MediaPanelRenderOptions(
    isDRM = true,  // Enable DRM for encrypted streams
    stereoMode = StereoMode.None
)
```

**Location**: `ImmersiveActivity.kt:215`

### 3. Add Server Termination Logging (Debugging)

**Problem**: Need more information about why server terminates.

**Solution**: Add logging to capture server termination context:

```c
// In ControlStream.c:1231
Limelog("Server notified termination reason: 0x%08x\n", terminationErrorCode);
Limelog("Termination context: lastSeenFrame=%d, decoderSetupState=%d\n", 
        lastSeenFrame, decoderSetupState);
```

### 4. Verify Decoder Capabilities Match Server Requirements

**Problem**: Server may reject decoder configuration.

**Solution**: 
- Check if 2560x1440 @ 60fps is supported by server
- Verify decoder capabilities match server expectations
- Test with lower resolution first (1920x1080)

### 5. Investigate Server-Side Logs

**Problem**: Need server perspective on termination.

**Solution**:
- Check Sunshine server logs for termination reason
- Look for error messages around `11:21:27.079`
- Verify server configuration matches client expectations

---

## Timeline Summary

| Time | Event | Status |
|------|-------|--------|
| 11:21:26.800 | RTSP handshake starts | ✅ |
| 11:21:26.815 | Video format negotiated (H264) | ✅ |
| 11:21:27.066 | RTSP handshake complete | ✅ |
| 11:21:27.076 | Decoder setup starts | ✅ |
| 11:21:27.077 | `initializeDecoder` called | ✅ |
| 11:21:27.079 | **Server terminates (0x80030023)** | ❌ |
| 11:21:27.107 | Surface connection error | ⚠️ |

**Total time from handshake to termination**: ~279ms
**Time from decoder setup to termination**: ~3ms

---

## Conclusion

1. **RTSP handshake is WORKING** - no encryption mismatch issues
2. **Server terminates immediately** when decoder setup begins
3. **Most likely cause**: Server requires DRM-enabled surface for encrypted streams
4. **Secondary issue**: Surface reuse error needs cleanup fix

**Next Steps**:
1. Test with `isDRM = true` in panel configuration
2. Fix surface cleanup/reuse issue
3. Check Sunshine server logs for termination reason
4. Verify decoder capabilities match server requirements

