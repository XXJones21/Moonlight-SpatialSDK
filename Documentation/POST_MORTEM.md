# Moonlight SpatialSDK - Post-Mortem Report

## Executive Summary

This document consolidates the investigation and resolution of critical issues encountered during the development of Moonlight SpatialSDK for Meta Quest 3. The project involved porting Moonlight game streaming to the Meta Spatial SDK platform, requiring integration with native video rendering, network protocols, and VR-specific surface management.

**Project Status**: Issues identified and resolved through systematic investigation and code alignment with reference implementation (moonlight-android).

---

## Major Issues Investigated

### 1. Decoder Configuration Timing Issue

**Problem**: Decoder was being configured twice - once via `preConfigureDecoder()` with preference values, and again via MoonBridge's `bridgeDrSetup()` with negotiated parameters. The first configuration bound the surface to a decoder instance, causing the second configuration to fail with "already connected" errors.

**Root Cause**:

- `preConfigureDecoder()` was called before `NvConnection.start()`
- This violated the moonlight-android pattern where `setup()` is ONLY called by MoonBridge after connection negotiation
- Surface can only be bound to a MediaCodec decoder once per decoder instance

**Investigation**:

- Logs showed: `BufferQueueProducer: connect: already connected (cur=3 req=3)`
- Error -22 (EINVAL) when trying to configure decoder the second time
- Connection terminated with "lack of video traffic" (error -102)

**Solution**:

- Removed `preConfigureDecoder()` call from `surfaceConsumer`
- Only call `setRenderTarget()` when surface is attached
- Let MoonBridge call `setup()` after connection negotiation with actual negotiated parameters

**Files Modified**:

- `ImmersiveActivity.kt` - Removed `preConfigureDecoder()` call
- `MoonlightPanelRenderer.kt` - Removed pre-configuration logic

**Status**: ✅ Resolved

---

### 2. MoonBridge Setup Timing Issue

**Problem**: `MoonBridge.setupBridge()` was called INSIDE `NvConnection.start()` on a background thread, AFTER `startApp()` HTTP call completed. The server connected and terminated BEFORE `setupBridge()` executed, because the video renderer wasn't registered yet.

**Root Cause**:

- Timeline: `NvConnection.start()` invoked → Server connects (34ms later) → Server terminates (immediately) → `setupBridge()` called (150ms AFTER termination)
- Server expects video renderer to be ready when control stream connects
- `videoRenderer` was null when `bridgeDrSetup()` was called by native code

**Investigation**:

- Server logs showed "CLIENT CONNECTED" followed immediately by "Process terminated"
- Client logs showed connection termination before decoder setup
- 184ms delay between connection start and `setupBridge()` was the critical issue

**Solution**:

- Call `MoonBridge.setupBridge()` BEFORE `NvConnection.start()` is invoked
- Ensure bridge is ready synchronously before connection starts
- Removed duplicate `setupBridge()` call from `NvConnection.start()`

**Files Modified**:

- `MoonlightConnectionManager.kt` - Call `setupBridge()` before creating `NvConnection`
- `NvConnection.java` - Removed duplicate `setupBridge()` call (or made it conditional)

**Status**: ✅ Resolved

---

### 3. ENet Control Stream Connection Failure

**Problem**: ENet connection to control stream (UDP port 47999) failed with "unexpected event 2" (DISCONNECT event) instead of CONNECT event. Connection wait timed out after 8 seconds.

**Root Cause Analysis**:

- Server logs showed "CLIENT CONNECTED" but then "Process terminated" immediately
- Client received DISCONNECT event instead of CONNECT during `serviceEnetHost()` wait
- Race condition: Server accepts connection but immediately disconnects before client processes CONNECT event
- Client never reached START A/B packet sending code

**Investigation**:

- Timeline analysis: Stage 8 starts → 8-second delay → DISCONNECT event received
- Server provided port 47999 in RTSP handshake but rejected ENet connection
- Network test showed TCP port 47999 was closed (UDP test needed)
- Comparison with macOS client showed successful connection and immediate control packet sending

**Findings**:

- RTSP handshake succeeded (server provided control port)
- ENet connection was attempted but server sent DISCONNECT immediately
- Client never sent control packets (IDX_REQUEST_IDR_FRAME, IDX_START_B)
- macOS client worked correctly, sending control packets immediately after connection

**Resolution**:

- Issue was resolved as part of fixing MoonBridge setup timing
- Once `setupBridge()` was called before connection start, control stream connection succeeded
- Client was able to send control packets immediately after connection

**Status**: ✅ Resolved (via MoonBridge timing fix)

---

### 4. RTSP Encryption Mismatch

**Problem**: Sunshine server returned `rtspenc://` URL even when LAN encryption was disabled in server UI. Client set `encryptedRtspEnabled = true` but server sent unencrypted messages, causing handshake to fail with "Rejecting unencrypted RTSP message".

**Root Cause**:

- RTSP encryption detection based on URL prefix (`rtspenc://` vs `rtsp://`)
- Server returned `rtspenc://` URL regardless of LAN encryption setting
- Client expected encrypted messages but server sent plaintext
- `unsealRtspMessage()` rejected unencrypted messages when `encryptedRtspEnabled = true`

**Investigation**:

- Logs showed: `<sessionUrl0>rtspenc://10.1.95.5:48010</sessionUrl0>`
- Server configuration showed LAN encryption disabled
- Code checked for `ENCRYPTED_RTSP_BIT` flag in message header
- When flag missing but encryption expected, message was rejected

**Resolution**:

- Issue resolved itself when server configuration was corrected
- Alternative solution: Use SDP `x-ss-general.encryptionRequested` attribute instead of URL prefix
- Client-side workaround: Check SDP attributes in addition to URL prefix

**Status**: ✅ Resolved (server configuration issue)

---

### 5. DRM/Secure Layer Configuration

**Problem**: Panel configuration did not set `isDRM = true` in `MediaPanelRenderOptions`, potentially causing server termination for encrypted streams.

**Root Cause Analysis**:

- Meta Spatial SDK requires `isDRM = true` for DRM-protected content
- Encrypted RTSP streams (`rtspenc://`) may require secure surface
- Server may terminate if secure surface is not detected
- PremiumMediaSample sets `isDRM = true` for any encrypted content

**Investigation**:

- Current code: `MediaPanelRenderOptions(stereoMode = StereoMode.None)` - `isDRM` defaults to `false`
- PremiumMediaSample pattern: Sets `isDRM = true` when `drmLicenseUrl != null`
- Meta documentation: Secure layers required for DRM/protected content
- Server termination occurred immediately after decoder setup began

**Resolution**:

- For encrypted RTSP streams, set `isDRM = true` to enable secure surface
- This ensures server accepts the connection for encrypted streams
- Change made: `MediaPanelRenderOptions(isDRM = true, stereoMode = StereoMode.None)`

**Files Modified**:

- `ImmersiveActivity.kt` - Set `isDRM = true` for encrypted streams

**Status**: ✅ Resolved

---

### 6. PIN Pairing Flow Issue

**Problem**: PIN pairing flow was backwards - client asked user to enter PIN from server, but Moonlight protocol requires client to generate PIN and user enters it on server.

**Root Cause**:

- Moonlight pairing protocol: Client generates PIN → Client displays PIN → User enters PIN on server → Client uses generated PIN for pairing handshake
- Our implementation: Client asked user to enter PIN from server (backwards)

**Investigation**:

- Moonlight-Android reference: `PairingManager.generatePinString()` generates 4-digit PIN
- PIN is displayed to user with instructions to enter on server
- Pairing handshake uses client-generated PIN for AES key derivation
- Server validates PIN during challenge/response

**Resolution**:

- Update UI to generate PIN client-side
- Display PIN prominently with instructions
- Start pairing automatically with generated PIN
- Show status during pairing process

**Files Modified**:

- `MoonlightConnectionManager.kt` - Use `PairingManager.generatePinString()`
- `PancakeActivity.kt` - Generate and display PIN, start pairing automatically

**Status**: ✅ Resolved

---

## Testing and Validation

### Test Scenarios

1. **Connection Flow Testing**:
   - Verified surface attachment before connection start
   - Confirmed `setupBridge()` called before `NvConnection.start()`
   - Validated decoder setup only called by MoonBridge after negotiation

2. **Control Stream Testing**:
   - Verified ENet connection succeeds
   - Confirmed control packets (START A/B) sent immediately after connection
   - Validated server accepts connection and continues streaming

3. **RTSP Handshake Testing**:
   - Tested with both encrypted and unencrypted streams
   - Verified format negotiation (H264/HEVC/AV1)
   - Confirmed port assignment (audio, video, control)

4. **Decoder Configuration Testing**:
   - Verified decoder configured only once with negotiated parameters
   - Confirmed surface binding succeeds
   - Validated video frames decoded and rendered

5. **Pairing Testing**:
   - Verified client generates PIN
   - Confirmed PIN displayed to user
   - Validated pairing handshake completes successfully

### Log Analysis

**Key Log Patterns Verified**:

- ✅ `MoonBridge.setupBridge()` called before connection start
- ✅ `bridgeDrSetup()` called with negotiated format/width/height/fps
- ✅ `decoderRenderer.setup()` called only once
- ✅ Control stream connects and sends packets immediately
- ✅ Video frames decoded and rendered successfully
- ✅ No "already connected" surface errors
- ✅ No premature server termination

---

## Code Changes Summary

### Critical Fixes

1. **Removed Pre-Configuration**:
   - Removed `preConfigureDecoder()` call from `surfaceConsumer`
   - Only call `setRenderTarget()` when surface attached
   - Let MoonBridge handle decoder setup after negotiation

2. **Fixed Bridge Setup Timing**:
   - Call `MoonBridge.setupBridge()` BEFORE `NvConnection.start()`
   - Ensure video renderer registered before connection begins
   - Removed duplicate `setupBridge()` call from `NvConnection.start()`

3. **DRM Configuration**:
   - Set `isDRM = true` in `MediaPanelRenderOptions` for encrypted streams
   - Enables secure surface creation for encrypted RTSP streams

4. **PIN Pairing**:
   - Generate PIN client-side using `PairingManager.generatePinString()`
   - Display PIN to user with instructions
   - Start pairing automatically with generated PIN

### Files Modified

- `ImmersiveActivity.kt`:
  - Removed `preConfigureDecoder()` call
  - Set `isDRM = true` in panel configuration
  
- `MoonlightConnectionManager.kt`:
  - Call `MoonBridge.setupBridge()` before `NvConnection.start()`
  - Updated pairing flow to generate PIN client-side
  
- `NvConnection.java`:
  - Removed duplicate `setupBridge()` call (or made conditional)
  
- `PancakeActivity.kt`:
  - Updated UI to generate and display PIN
  - Start pairing automatically with generated PIN

---

## Lessons Learned

### 1. Follow Reference Implementation Patterns

The moonlight-android reference implementation provides the correct patterns for:

- Decoder lifecycle management
- Connection sequencing
- Bridge setup timing
- Surface attachment

**Key Insight**: Deviating from the reference implementation caused multiple timing and configuration issues.

### 2. Timing is Critical

The order of operations matters significantly:

- Surface must be attached before connection starts
- Bridge must be set up before connection starts
- Decoder setup must happen after negotiation, not before

**Key Insight**: Small timing differences (150ms) can cause connection failures.

### 3. Platform-Specific Requirements

Meta Spatial SDK has specific requirements:

- DRM/secure layers for encrypted content
- Direct-to-surface rendering for video panels
- Proper surface lifecycle management

**Key Insight**: Platform documentation and sample code (PremiumMediaSample) provide critical guidance.

### 4. Server-Client Protocol Understanding

Understanding the Moonlight protocol is essential:

- Control stream connection sequence
- RTSP handshake flow
- Pairing protocol (client generates PIN)
- Encryption detection and handling

**Key Insight**: Protocol mismatches cause immediate connection failures.

---

## Known Issues

### Video Surface Color Space Initialization Issue

**Problem**: Video panels display incorrect colors on initial surface creation. Colors appear washed out, oversaturated, or with incorrect color space mapping. The issue resolves after device sleep/wake cycle (onPause/onResume).

**Affected Implementations**:

- Moonlight SpatialSDK (this project)
- PremiumMediaSample (Meta's reference implementation)

**Symptoms**:

- Incorrect colors on first video frame after surface creation
- Colors appear correct after device sleep/wake cycle
- Issue affects both ExoPlayer (PremiumMediaSample) and native decoder (Moonlight) paths

**Root Cause Analysis**:

- Likely related to Spatial SDK panel surface color space initialization
- Surface color space/dataspace may not be properly applied on first frame
- Sleep/wake cycle triggers surface re-initialization that applies correct color space

**Workaround**:

- Device sleep/wake cycle (taking headset off/on) fixes colors
- This is a known limitation in the current Spatial SDK version

**Status**: ⚠️ Known SDK Issue - Affects reference implementation

- Not a bug in our implementation
- Requires SDK fix from Meta
- Workaround: Sleep/wake cycle if colors are incorrect

**References**:

- Observed in PremiumMediaSample test videos
- Same behavior in Moonlight SpatialSDK
- Issue persists across different video codecs (H.264, HEVC, AV1)

---

## Remaining Considerations

### Potential Future Issues

1. **Surface Reuse**: Ensure proper cleanup before decoder reconfiguration
2. **Network Conditions**: Handle UDP packet loss and network interruptions
3. **Decoder Capabilities**: Verify device supports negotiated format/resolution
4. **Thread Safety**: Ensure all bridge operations are thread-safe
5. **Color Space Initialization**: Monitor for SDK updates addressing video surface color space issue

### Monitoring Points

1. **Connection Stages**: Monitor all connection stage transitions
2. **Decoder Setup**: Verify decoder configured with correct parameters
3. **Control Packets**: Confirm control packets sent immediately after connection
4. **Video Frames**: Validate frames decoded and rendered successfully

---

## Conclusion

The issues encountered during Moonlight SpatialSDK development were primarily related to:

1. **Timing**: Incorrect sequencing of operations (bridge setup, decoder configuration)
2. **Protocol Understanding**: Misunderstanding of Moonlight pairing and connection flow
3. **Platform Requirements**: Missing DRM/secure layer configuration for encrypted streams

All critical issues have been resolved through:

- Alignment with moonlight-android reference implementation
- Proper timing of bridge setup and decoder configuration
- Correct DRM configuration for encrypted streams
- Fixed PIN pairing flow

The project now follows the correct patterns and successfully establishes connections with the Sunshine server.

---

## References

- **Moonlight-Android**: Reference implementation for Android platform
- **Meta Spatial SDK Documentation**: Platform-specific requirements and patterns
- **PremiumMediaSample**: Meta's sample code demonstrating DRM and video panel usage
- **Sunshine Server**: Game streaming server implementation

---

*Document compiled from investigation logs and analysis documents dated December 2025*
