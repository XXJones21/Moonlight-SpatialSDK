# WebRTC Transition Plan for Moonlight SpatialSDK

## Overview

This plan transitions the Moonlight SpatialSDK from RTSP-based streaming to WebRTC while preserving the existing decoder, connection management, and pairing infrastructure. The RTSP protocol layer will be replaced with WebRTC, maintaining compatibility with the existing MediaCodec decoder and Spatial SDK integration.

## Sunshine Server Investigation Results

### Key Findings

**Sunshine does NOT currently support WebRTC.**

Investigation of the Sunshine repository (`https://github.com/LizardByte/Sunshine`) revealed:

1. **Protocol Support**: Sunshine currently only supports RTSP protocol
   - Found `src/rtsp.cpp` and `src/rtsp.h` files confirming RTSP implementation
   - No WebRTC-related code found in the repository
   - No WebRTC issues or feature requests in GitHub issues

2. **Current Architecture**: 
   - Sunshine uses RTSP for video streaming
   - Server-side encoding (H264/HEVC/AV1) via hardware encoders
   - RTSP handshake and negotiation for video format selection
   - UDP-based control channel for input

3. **Implications for WebRTC Transition**:
   - **Option A**: Add WebRTC support to Sunshine (server-side changes required)
   - **Option B**: Create RTSP-to-WebRTC bridge/proxy (intermediate layer)
   - **Option C**: Use alternative server with WebRTC support (not using Sunshine)
   - **Option D**: Implement WebRTC client that can work with a future Sunshine WebRTC implementation

### Recommended Approach

Given the requirement to preserve "everything possible" and use Sunshine's server setup, we recommend **Option D with Option A as a future contribution**:

1. **Immediate**: Implement WebRTC client-side infrastructure that can work independently
2. **Short-term**: Create a proof-of-concept WebRTC implementation
3. **Long-term**: Contribute WebRTC support to Sunshine (server-side) to enable full integration

This approach allows us to:
- Build and test WebRTC client infrastructure immediately
- Preserve all existing decoder, pairing, and connection management code
- Prepare for future Sunshine WebRTC support
- Potentially contribute WebRTC support back to Sunshine

## Current Architecture Analysis

### Components to Preserve

1. **MediaCodecDecoderRenderer** (`app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java`)
   - Handles H264/HEVC/AV1 decoding via Android MediaCodec
   - Surface-based rendering (compatible with WebRTC)
   - Frame submission interface can be adapted for WebRTC

2. **AndroidAudioRenderer** (`app/src/main/java/com/limelight/binding/audio/AndroidAudioRenderer.java`)
   - Audio playback via Android AudioTrack
   - Can receive PCM audio from WebRTC AudioTrack

3. **MoonlightConnectionManager.kt** (`app/src/main/java/com/example/moonlight_spatialsdk/MoonlightConnectionManager.kt`)
   - Connection lifecycle management
   - Pairing logic (protocol-agnostic)
   - Stream configuration management

4. **PairingManager** (`app/src/main/java/com/limelight/nvstream/http/PairingManager.java`)
   - PIN-based pairing with Sunshine server
   - HTTP-based pairing (independent of streaming protocol)

5. **PreferenceConfiguration** (`app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java`)
   - Resolution, bitrate, codec preferences
   - All configuration can be reused for WebRTC

6. **MoonlightPanelRenderer.kt** (`app/src/main/java/com/example/moonlight_spatialsdk/MoonlightPanelRenderer.kt`)
   - Surface management for Spatial SDK panels
   - Decoder integration

### Components to Replace

1. **RTSP Connection Layer**
   - `moonlight-core/moonlight-common-c/src/RtspConnection.c` - RTSP handshake
   - `moonlight-core/moonlight-common-c/src/Connection.c` - RTSP connection lifecycle
   - `moonlight-core/moonlight-common-c/src/VideoStream.c` - RTSP video stream handling

2. **Native JNI Bridge (Partial)**
   - `moonlight-core/callbacks.c` - RTSP-specific callbacks
   - `com/limelight/nvstream/jni/MoonBridge.java` - Add WebRTC methods

3. **NvConnection.java** (Refactor)
   - Replace RTSP connection logic with WebRTC PeerConnection
   - Preserve connection lifecycle interface

## WebRTC Architecture

### High-Level Flow

```
Sunshine Server (RTSP) → [Future: WebRTC Server]
                            ↓
                    [WebRTC Signaling Server]
                            ↓
                    [WebRTC Client (Android)]
                            ↓
                    [WebRTC PeerConnection]
                            ↓
        ┌───────────────────┴───────────────────┐
        ↓                                       ↓
[VideoTrack]                              [AudioTrack]
        ↓                                       ↓
[RTP Depacketizer]                    [Audio Renderer]
        ↓
[H264/HEVC/AV1 NAL Units]
        ↓
[MediaCodecDecoderRenderer]
        ↓
[Spatial SDK Panel Surface]
```

### WebRTC Components

1. **PeerConnectionFactory**
   - Creates PeerConnection instances
   - Manages media streams
   - Configures codecs (H264, HEVC, AV1)

2. **PeerConnection**
   - Manages WebRTC connection lifecycle
   - Handles ICE candidate exchange
   - Manages SDP offer/answer exchange

3. **Signaling Client**
   - WebSocket-based signaling server
   - Exchanges SDP offers/answers
   - Exchanges ICE candidates
   - **Note**: Requires signaling server (can be simple WebSocket server)

4. **VideoTrack Processing**
   - Extract RTP packets from WebRTC VideoTrack
   - Depacketize RTP to get H264/HEVC/AV1 NAL units
   - Feed NAL units to MediaCodecDecoderRenderer

5. **AudioTrack Processing**
   - Receive PCM audio from WebRTC AudioTrack
   - Feed to AndroidAudioRenderer

6. **DataChannel**
   - Replace UDP control channel
   - Send controller input packets
   - Receive rumble/feedback

## Implementation Plan

### Phase 1: WebRTC Dependencies & Setup

**Goal**: Add WebRTC dependencies and basic infrastructure

**Tasks**:
1. Add WebRTC dependency to `build.gradle.kts`:
   ```kotlin
   implementation("org.webrtc:google-webrtc:1.0.32006")
   ```
   Or use Maven Central version compatible with Android

2. Add WebSocket library for signaling:
   ```kotlin
   implementation("com.squareup.okhttp3:okhttp:4.12.0") // Already present
   // Or use dedicated WebSocket library
   implementation("org.java-websocket:Java-WebSocket:1.5.3")
   ```

3. Create `WebRTCConnectionManager.kt` skeleton
   - Basic class structure
   - PeerConnectionFactory initialization
   - Placeholder for signaling

**Files to Create/Modify**:
- `app/build.gradle.kts` - Add dependencies
- `app/src/main/java/com/example/moonlight_spatialsdk/WebRTCConnectionManager.kt` - New file

### Phase 2: Signaling Infrastructure

**Goal**: Implement WebRTC signaling for SDP/ICE exchange

**Tasks**:
1. Create `WebRTCSignalingClient.kt`:
   - WebSocket connection to signaling server
   - SDP offer/answer exchange
   - ICE candidate exchange
   - Connection state management

2. Design signaling protocol:
   ```json
   {
     "type": "offer" | "answer" | "ice-candidate",
     "sdp": "...",  // For offer/answer
     "candidate": "...",  // For ICE candidate
     "sessionId": "..."
   }
   ```

3. Integrate with `WebRTCConnectionManager`:
   - Create offer when starting connection
   - Handle answer from server
   - Exchange ICE candidates

**Files to Create**:
- `app/src/main/java/com/example/moonlight_spatialsdk/WebRTCSignalingClient.kt`

**Note**: Requires a signaling server. Options:
- Simple Node.js WebSocket server
- Python WebSocket server
- Integrate into Sunshine (future contribution)

### Phase 3: Video Track Processing

**Goal**: Extract encoded video data from WebRTC and feed to MediaCodec

**Tasks**:
1. Create `WebRTCVideoRenderer.kt`:
   - Implements `VideoSink` interface
   - Receives `VideoFrame` from WebRTC
   - Extracts encoded video data (H264/HEVC/AV1)

2. Create `WebRTCFrameExtractor.kt`:
   - RTP depacketization
   - Extract NAL units from RTP packets
   - Handle fragmented NAL units (FU-A)
   - Handle STAP-A (aggregation packets)

3. Integrate with `MediaCodecDecoderRenderer`:
   - Adapt `submitDecodeUnit()` to accept frames from WebRTC
   - Ensure proper format (ByteBufferDescriptor)
   - Handle codec configuration (SPS/PPS for H264)

**Files to Create**:
- `app/src/main/java/com/example/moonlight_spatialsdk/WebRTCVideoRenderer.kt`
- `app/src/main/java/com/example/moonlight_spatialsdk/WebRTCFrameExtractor.kt`

**Files to Modify**:
- `app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java`
  - Verify `submitDecodeUnit()` can accept WebRTC frames
  - May need adapter for frame format

### Phase 4: Audio Track Processing

**Goal**: Extract audio from WebRTC and feed to AndroidAudioRenderer

**Tasks**:
1. Create `WebRTCAudioRenderer.kt`:
   - Implements `AudioSink` interface
   - Receives PCM audio from WebRTC AudioTrack
   - Converts to format expected by AndroidAudioRenderer
   - Handles sample rate conversion if needed

2. Integrate with `AndroidAudioRenderer`:
   - Ensure format compatibility
   - Handle buffer management

**Files to Create**:
- `app/src/main/java/com/example/moonlight_spatialsdk/WebRTCAudioRenderer.kt`

### Phase 5: Input via DataChannel

**Goal**: Replace UDP control channel with WebRTC DataChannel

**Tasks**:
1. Create `WebRTCInputHandler.kt`:
   - Create DataChannel in PeerConnection
   - Serialize controller input packets
   - Send via DataChannel
   - Receive rumble/feedback messages

2. Replace UDP input in connection flow:
   - Remove UDP socket creation
   - Use DataChannel for all input

**Files to Create**:
- `app/src/main/java/com/example/moonlight_spatialsdk/WebRTCInputHandler.kt`

**Files to Modify**:
- `app/src/main/java/com/limelight/nvstream/NvConnection.java` (if still used)
- Or create new `WebRTCConnection` class

### Phase 6: Integration with MoonlightConnectionManager

**Goal**: Add WebRTC option to existing connection manager

**Tasks**:
1. Extend `MoonlightConnectionManager.kt`:
   - Add `useWebRTC: Boolean` parameter
   - Conditional logic: RTSP vs WebRTC
   - Preserve pairing logic (protocol-agnostic)

2. Update `ImmersiveActivity.kt`:
   - Add WebRTC connection option
   - Initialize WebRTC components
   - Connect video renderer to panel surface

**Files to Modify**:
- `app/src/main/java/com/example/moonlight_spatialsdk/MoonlightConnectionManager.kt`
- `app/src/main/java/com/example/moonlight_spatialsdk/ImmersiveActivity.kt`

### Phase 7: Testing & Optimization

**Goal**: Test end-to-end WebRTC flow and optimize

**Tasks**:
1. **Unit Testing**:
   - Test RTP depacketization
   - Test NAL unit extraction
   - Test frame format conversion

2. **Integration Testing**:
   - Test with mock signaling server
   - Test video decoding pipeline
   - Test audio playback
   - Test input handling

3. **Performance Optimization**:
   - Optimize frame extraction latency
   - Optimize buffer management
   - Profile memory usage
   - Optimize codec configuration

4. **Error Handling**:
   - Connection failures
   - Codec errors
   - Network interruptions
   - Signaling errors

## Sunshine Server WebRTC Support (Future Contribution)

### Proposed Implementation

To enable full WebRTC support with Sunshine, the following server-side changes would be needed:

1. **Add WebRTC Module to Sunshine**:
   - Integrate WebRTC library (libwebrtc or similar)
   - Create WebRTC peer connection manager
   - Handle SDP offer/answer
   - Handle ICE candidate exchange

2. **Video Encoding Integration**:
   - Feed hardware encoder output to WebRTC
   - Configure WebRTC codecs (H264, HEVC, AV1)
   - Manage bitrate and quality settings

3. **Audio Encoding Integration**:
   - Feed audio capture to WebRTC
   - Configure audio codecs (Opus)

4. **Signaling Server**:
   - Add WebSocket signaling endpoint
   - Handle client connections
   - Exchange SDP and ICE candidates

5. **DataChannel for Input**:
   - Receive controller input via DataChannel
   - Replace UDP control channel

### Benefits

- Native WebRTC support in Sunshine
- Better Android compatibility
- Lower latency potential
- Better NAT traversal
- Standard protocol (easier integration)

### Challenges

- Significant server-side development
- Requires WebRTC expertise
- Testing and validation
- Maintaining RTSP compatibility (dual protocol support)

## Alternative: RTSP-to-WebRTC Bridge

If adding WebRTC to Sunshine is not feasible, an intermediate bridge/proxy could be created:

### Architecture

```
Sunshine (RTSP) → RTSP-to-WebRTC Bridge → WebRTC Client (Android)
```

### Implementation

1. **Bridge Server**:
   - Receives RTSP stream from Sunshine
   - Converts RTSP to WebRTC
   - Acts as WebRTC peer
   - Handles signaling

2. **Benefits**:
   - No changes to Sunshine required
   - Can be deployed separately
   - Allows testing WebRTC client immediately

3. **Drawbacks**:
   - Additional latency (extra hop)
   - Additional server to maintain
   - More complex architecture

## Dependencies

### Required Libraries

1. **WebRTC**:
   - `org.webrtc:google-webrtc` (Maven Central)
   - Or compile from source (more control, larger effort)

2. **WebSocket** (for signaling):
   - `com.squareup.okhttp3:okhttp:4.12.0` (already present)
   - Or `org.java-websocket:Java-WebSocket:1.5.3`

3. **Existing Dependencies** (preserved):
   - All Moonlight dependencies remain
   - MediaCodec (Android system)
   - Spatial SDK (already present)

### Build Configuration

```kotlin
// app/build.gradle.kts
dependencies {
    // WebRTC
    implementation("org.webrtc:google-webrtc:1.0.32006")
    
    // WebSocket (if not using OkHttp)
    // implementation("org.java-websocket:Java-WebSocket:1.5.3")
    
    // Existing Moonlight dependencies (preserved)
    // ... all existing dependencies remain
}
```

## File Structure After WebRTC Integration

```
Moonlight-SpatialSDK/
├── app/
│   ├── src/main/java/com/example/moonlight_spatialsdk/
│   │   ├── ImmersiveActivity.kt (MODIFIED - WebRTC option)
│   │   ├── MoonlightConnectionManager.kt (MODIFIED - WebRTC support)
│   │   ├── MoonlightPanelRenderer.kt (PRESERVED)
│   │   ├── LegacySurfaceHolderAdapter.kt (PRESERVED)
│   │   ├── WebRTCConnectionManager.kt (NEW)
│   │   ├── WebRTCSignalingClient.kt (NEW)
│   │   ├── WebRTCVideoRenderer.kt (NEW)
│   │   ├── WebRTCFrameExtractor.kt (NEW)
│   │   ├── WebRTCAudioRenderer.kt (NEW)
│   │   └── WebRTCInputHandler.kt (NEW)
│   └── build.gradle.kts (MODIFIED - WebRTC dependencies)
└── Documentation/
    └── WEBRTC_TRANSITION_PLAN.md (THIS FILE)
```

## Testing Strategy

### Phase 1: Unit Tests

1. **RTP Depacketization**:
   - Test FU-A fragmentation
   - Test STAP-A aggregation
   - Test single NAL unit packets

2. **NAL Unit Extraction**:
   - Test SPS/PPS extraction
   - Test frame boundaries
   - Test codec configuration

3. **Frame Format Conversion**:
   - Test ByteBufferDescriptor creation
   - Test format compatibility with MediaCodec

### Phase 2: Integration Tests

1. **Mock Signaling Server**:
   - Simple WebSocket server
   - Echo SDP offers/answers
   - Echo ICE candidates

2. **Video Pipeline**:
   - WebRTC → Frame Extractor → MediaCodec → Surface
   - Verify video appears on panel

3. **Audio Pipeline**:
   - WebRTC → Audio Renderer → AndroidAudioRenderer
   - Verify audio playback

4. **Input Pipeline**:
   - Controller → DataChannel → Server
   - Verify input delivery

### Phase 3: End-to-End Tests

1. **With RTSP-to-WebRTC Bridge**:
   - Test full flow with bridge
   - Measure latency
   - Test error recovery

2. **With Future Sunshine WebRTC**:
   - Test native WebRTC support
   - Compare with RTSP performance
   - Validate all features

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| WebRTC library size | Use optimized build, consider ProGuard |
| RTP depacketization complexity | Use well-tested libraries, extensive unit tests |
| Latency concerns | Optimize frame extraction, minimize buffering |
| Codec compatibility | Test H264/HEVC/AV1, handle codec changes gracefully |
| Signaling server dependency | Provide simple reference implementation |
| Sunshine WebRTC support timeline | Build client to work independently, prepare for integration |

## Success Criteria

- ✅ WebRTC client connects to signaling server
- ✅ Video stream decodes and displays on Spatial SDK panel
- ✅ Audio stream plays correctly
- ✅ Controller input works via DataChannel
- ✅ Latency is comparable or better than RTSP
- ✅ All existing features (pairing, configuration) preserved
- ✅ Code is ready for Sunshine WebRTC integration

## Timeline Estimate

- **Phase 1-2**: 1-2 weeks (Dependencies, Signaling)
- **Phase 3-4**: 2-3 weeks (Video/Audio Processing)
- **Phase 5**: 1 week (Input via DataChannel)
- **Phase 6**: 1 week (Integration)
- **Phase 7**: 2-3 weeks (Testing & Optimization)

**Total: 7-10 weeks** for complete WebRTC client implementation

## Next Steps

1. **Immediate**: Review and approve this plan
2. **Week 1**: Add WebRTC dependencies, create basic WebRTCConnectionManager
3. **Week 2**: Implement signaling client, test with mock server
4. **Week 3-4**: Implement video frame extraction and MediaCodec integration
5. **Week 5**: Implement audio processing
6. **Week 6**: Implement DataChannel input
7. **Week 7**: Integration and testing
8. **Week 8+**: Optimization and preparation for Sunshine WebRTC support

## References

- [WebRTC Android API](https://webrtc.github.io/webrtc-org/native-code/android/)
- [Google WebRTC Maven](https://mvnrepository.com/artifact/org.webrtc/google-webrtc)
- [RTP Payload Format for H.264](https://tools.ietf.org/html/rfc6184)
- [Sunshine Repository](https://github.com/LizardByte/Sunshine)
- [Moonlight Protocol Documentation](https://github.com/moonlight-stream/moonlight-docs)


