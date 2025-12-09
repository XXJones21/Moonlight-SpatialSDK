# Quest 3 App Pipeline

This document provides a comprehensive breakdown of the Moonlight-SpatialSDK Quest 3 application architecture, focusing on the hybrid 2D/immersive mode structure, connection management, and video streaming integration.

**Architecture**: The Quest 3 app uses Meta Spatial SDK with a hybrid app pattern, supporting both 2D panel mode and immersive VR mode for Moonlight game streaming.

## Client Architecture

### File Structure

- **2D Activity**: `PancakeActivity.kt` - 2D panel activity for connection UI and pairing
- **Immersive Activity**: `ImmersiveActivity.kt` - VR activity for video streaming
- **Core Components**:
  - `MoonlightConnectionManager.kt` - Connection lifecycle, pairing, and stream management
  - `MoonlightPanelRenderer.kt` - Bridges Spatial panel Surface to Moonlight native decoder
  - `LegacySurfaceHolderAdapter.kt` - Adapter for Moonlight's SurfaceHolder interface
  - `OptionsPanelLayout.kt` - Compose UI components (legacy, currently unused)

**Purpose**: Hybrid Moonlight game streaming application supporting both 2D window mode and immersive VR mode with passthrough.

---

## TABLE OF CONTENTS

1. [Main Activities](#main-activities)
2. [Connection Management](#connection-management)
3. [Video Panel Rendering](#video-panel-rendering)
4. [Pairing System](#pairing-system)
5. [Communication Flow](#communication-flow)
6. [Current State & Future Enhancements](#current-state--future-enhancements)

---

## MAIN ACTIVITIES

### PancakeActivity (2D Mode)

**File**: `PancakeActivity.kt`

**Purpose**: 2D panel activity for connection setup, pairing, stream preference selection, and launching immersive.

**Key Features (current)**:

- Host/port/appId inputs with persistence (`connection_prefs`).
- Pairing flow (check → generate PIN → pair) using `MoonlightPairingHelper` and `PairingManager`.
- Optional app list fetch once paired (populates appId dropdown).
- Optional server capability fetch (res/fps/format filtering) and stream preference selector (resolution/fps/format stored in shared prefs for immersive).
- Stream flags surfaced in UI: HDR enable, and “prefer full range” (stored in prefs).
- Launches `ImmersiveActivity` with host/port/appId extras or launches immersive without params.

**UI Actions**:

- “Connect & Launch Immersive”: checks pairing; if paired, saves prefs and starts immersive with extras; if not, triggers PIN pairing then allows connect.
- “Configure Stream”: fetches server capabilities (if host provided) and updates selectable resolution/fps/format; writes to shared prefs.
- “Reset Client Pairing”: clears cert/UID and resets UI to re-pair.
- “Launch Immersive Mode (No Connection)”: opens immersive without starting stream.

**Pairing Flow (current)**:

1. User enters host/port → taps “Connect & Launch Immersive”.
2. `checkPairing(host, port)` runs on background thread.
3. If not paired: generate PIN, display it, call `pairWithServer`; on success, UI enables Connect.
4. If paired: launch `ImmersiveActivity` with host/port/appId.

---

### ImmersiveActivity (VR Mode)

**File**: `ImmersiveActivity.kt`

**Purpose**: Immersive VR activity for video streaming

**Key Features**:

- Video panel registration for Moonlight stream
- Passthrough mode enabled
- Connection management with pairing check
- Scene setup with lighting and environment

**Lifecycle Methods**:

#### `onCreate()`

**Purpose**: Initialize streaming components and stash pending connection params.

**Key Steps**:

1. Initialize `MediaCodecHelper` and create `MoonlightPanelRenderer` (native decoder), `AndroidAudioRenderer`, `MoonlightConnectionManager`.
2. Read connection params from Intent extras (host/port/appId); store as pending (no connect yet).
3. Init `NetworkedAssetLoader`.

**Connection Parameters**:

- Received via Intent extras from `PancakeActivity`
- `host`: Server hostname or IP
- `port`: Server port (default: 47989)
- `appId`: Application ID to launch (0 for desktop)

#### `onSceneReady()` - Lines 110-126

**Purpose**: Configure scene after Spatial SDK initialization

**Key Steps**:

1. Disable locomotion (prevents controller movement)
2. Enable passthrough for mixed reality
3. Set lighting environment (ambient, sun, intensity)
4. Update IBL environment from assets
5. Set view origin (position and rotation)

**Scene Configuration**:

```kotlin
scene.enablePassthrough(true)
scene.setLightingEnvironment(
    ambientColor = Vector3(0f),
    sunColor = Vector3(7.0f, 7.0f, 7.0f),
    sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
    environmentIntensity = 0.3f
)
scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
```

#### `registerPanels()` - current

**Purpose**: Register the direct-to-surface media panel for Moonlight.

**Panel Registration (current code path)**:

```kotlin
VideoSurfacePanelRegistration(
    R.id.ui_example,
    surfaceConsumer = { panelEntity, surface ->
        panelEntity.setComponent(Visible(true))
        panelEntity.setComponent(
            Transform(Pose(Vector3(0f, 1.1f, -1.5f), Quaternion(0f, 0f, 0f, 1f)))
        )
        panelEntity.setComponent(Grabbable(enabled = true))

        SurfaceUtil.paintBlack(surface)
        moonlightPanelRenderer.attachSurface(surface)   // single decoder render target
        moonlightPanelRenderer.preConfigureDecoder()    // seeds decoder; no second decoder

        isSurfaceReady = true
        pendingConnectionParams?.let { (host, port, appId) ->
            connectToHost(host, port, appId)
        }
    },
    settingsCreator = {
        MediaPanelSettings(
            shape = computePanelShape(), // derives physical aspect from prefs
            display = PixelDisplayOptions(width = prefs.width, height = prefs.height),
            rendering = MediaPanelRenderOptions(
                isDRM = false,
                stereoMode = StereoMode.None,
            ),
        )
    },
)
```

**Panel Configuration (current behavior)**:

- Shape: computed from `prefs.width/prefs.height` to keep physical aspect aligned with the stream.
- Display: `PixelDisplayOptions(width = prefs.width, height = prefs.height)` (no hardcoded 1080p).
- Rendering: monoscopic; DRM currently off.
- Surface handling: paint black → attachSurface → preConfigureDecoder → mark surface ready → start pending connection if present.

#### `registerFeatures()` - Lines 74-88

**Purpose**: Register Spatial SDK features

**Features Registered**:

- `VRFeature` - VR functionality
- `ComposeFeature` - Compose UI support
- `IsdkFeature` - Hand tracking (deprecated, auto-registered by VRFeature)
- Debug features (if `BuildConfig.DEBUG`):
  - `CastInputForwardFeature` - Input forwarding
  - `HotReloadFeature` - Hot reload support
  - `OVRMetricsFeature` - Performance metrics
  - `DataModelInspectorFeature` - Data model inspection

**Key State Variables**:

```kotlin
private val prefs: PreferenceConfiguration
private val moonlightPanelRenderer: MoonlightPanelRenderer
private val audioRenderer: AndroidAudioRenderer
private val connectionManager: MoonlightConnectionManager
private val _connectionStatus: MutableStateFlow<String>
private val _isConnected: MutableStateFlow<Boolean>
```

---

## CONNECTION MANAGEMENT

### MoonlightConnectionManager

**File**: `MoonlightConnectionManager.kt`

**Purpose**: Manages Moonlight streaming connection lifecycle, pairing, and status updates

**Key Features**:

- Pairing status checking (background thread)
- Server pairing with PIN (background thread)
- Stream initialization and lifecycle
- Connection status callbacks
- Background thread execution (prevents ANR)

**Initialization**:

```kotlin
class MoonlightConnectionManager(
    private val context: Context,
    private val activity: Activity,
    private val decoderRenderer: VideoDecoderRenderer,
    private val audioRenderer: AndroidAudioRenderer,
    private val onStatusUpdate: ((String, Boolean) -> Unit)? = null
) : NvConnectionListener
```

**Background Execution**:

- All network operations run on `Executors.newSingleThreadExecutor()`
- Prevents ANR (Application Not Responding) errors
- Callbacks execute on background thread (UI updates should use `runOnUiThread`)

#### `checkPairing()` - Lines 45-62

**Purpose**: Check if server requires pairing

**Flow**:

1. Create `NvHTTP` instance with server address
2. Call `http.getPairState()` to check pairing status
3. Return result via callback: `(isPaired: Boolean, error: String?)`

**Usage**:

```kotlin
connectionManager.checkPairing(host, port) { isPaired, error ->
    if (isPaired) {
        // Proceed with connection
    } else {
        // Show PIN entry UI
    }
}
```

#### `pairWithServer()` - Lines 72-104

**Purpose**: Pair with server using PIN

**Flow**:

1. Create `NvHTTP` and `PairingManager` instances
2. Get server info
3. Call `pairingManager.pair(serverInfo, pin)`
4. Return result via callback: `(success: Boolean, error: String?)`

**Pair States**:

- `PAIRED` - Successfully paired
- `PIN_WRONG` - Incorrect PIN
- `ALREADY_IN_PROGRESS` - Another device is pairing
- `FAILED` - Pairing failed

**Usage**:

```kotlin
connectionManager.pairWithServer(host, port, pin) { success, error ->
    if (success) {
        // Pairing successful, proceed with connection
    } else {
        // Show error message
    }
}
```

#### `startStream()` - Lines 115-149

**Purpose**: Start Moonlight streaming session

**Flow**:

1. Create `ComputerDetails.AddressTuple` with host/port
2. Build `StreamConfiguration` from preferences:
   - App ID (0 for desktop)
   - Resolution, refresh rate, bitrate
   - Video format (H264, HEVC, AV1)
   - Audio configuration
3. Create `NvConnection` instance
4. Start connection: `connection.start(audioRenderer, decoderRenderer, this)`

**Stream Configuration**:

- Resolution: From `prefs.width` × `prefs.height`
- Refresh Rate: From `prefs.fps`
- Bitrate: From `prefs.bitrate`
- Video Format: Auto (H264, HEVC, AV1) or forced
- Audio: From `prefs.audioConfiguration`

**Background Execution**: Runs on executor thread to prevent ANR

#### `stopStream()` - Lines 154-160

**Purpose**: Stop streaming and clean up resources

**Flow**:

1. Call `connection.stop()` to stop Moonlight stream
2. Set `connection = null`
3. Set `isConnected = false`

**Background Execution**: Runs on executor thread

#### NvConnectionListener Implementation

**Callback Methods**:

- `stageStarting()` - Connection stage started
- `stageComplete()` - Connection stage completed
- `stageFailed()` - Connection stage failed
- `connectionStarted()` - Stream connected successfully
- `connectionTerminated()` - Stream disconnected
- `connectionStatusUpdate()` - Connection quality update
- `displayMessage()` - Status message from Moonlight
- `displayTransientMessage()` - Transient status message

**Status Updates**: All callbacks invoke `onStatusUpdate` callback if provided

---

## VIDEO PANEL RENDERING

### MoonlightPanelRenderer

**File**: `MoonlightPanelRenderer.kt`

**Purpose**: Bridge the Spatial panel Surface to a single Moonlight `NativeDecoderRenderer` (native AMediaCodec path).

**Key Points (current)**:

- Single decoder instance (`by lazy`); no duplicate decoders.
- `attachSurface(surface)`: wraps the Surface in `LegacySurfaceHolderAdapter`, calls `decoderRenderer.setRenderTarget(holder)`.
- `preConfigureDecoder()`: calls `decoderRenderer.setup(format, prefs.width, prefs.height, prefs.fps)` to seed configuration early. During stream start, Moonlight sees matching params and skips reconfiguration (“Decoder already configured with compatible parameters”).
- Call order in `surfaceConsumer`: paint black → attachSurface → preConfigureDecoder → mark `isSurfaceReady` → start pending connection if present.

---

### Video Pipeline Trace (current instrumented path)

Step-by-step flow with expected logging and current gaps:

1. Panel surface ready (ImmersiveActivity `surfaceConsumer`)  
   - Paint black → `attachSurface` (sets render target) → `preConfigureDecoder` (seed setup) → `isSurfaceReady=true` → `connectToHost` if pending params.

2. Connection starts  
   - `MoonlightConnectionManager.startStream` builds `NvConnection` with `NativeDecoderRenderer` (via `VideoDecoderRenderer`) and `AndroidAudioRenderer`. Sunshine may renegotiate/rewrap formats within the same RTSP session (e.g., initial HDR/AV1 attempt, then SDR/HEVC fallback).

3. Decoder setup (client)  
   - `NativeDecoderRenderer.setup` configures the native AMediaCodec; skips reconfigure if params already match. Frame counters reset at start.

4. Decode-unit delivery  
   - `submitDecodeUnit` logs are present for IDR and subsequent frames, confirming packets reach the renderer.

5. Negotiated formats (observed)  
   - Native decoder logs show output format with dataspace=260, `color-range=2` (limited), `color-standard=130817`, `color-transfer=65791`, hdr=0. Colors improved after full-range/HDR flags, but the codec still advertises limited range.

6. Panel overlay  
   - A translucent/white overlay can appear; taking the headset off/on can clear it. Likely compositor/panel-layer behavior rather than decoder.

---

### LegacySurfaceHolderAdapter

**File**: `LegacySurfaceHolderAdapter.kt`

**Purpose**: Adapter to bridge Android `Surface` to Moonlight's `SurfaceHolder` interface

**Implementation**: Implements Moonlight's `SurfaceHolder` interface, wrapping Android `Surface` for compatibility with Moonlight's decoder renderer.

---

## PAIRING SYSTEM

### Pairing Flow

**Purpose**: Moonlight requires PIN pairing for first-time connections to ensure security

**Flow**:

1. **Check Pairing**: `MoonlightConnectionManager.checkPairing()`
   - Queries server for pairing status
   - Returns `PAIRED` or `NOT_PAIRED`
2. **If Not Paired**: Generate and display PIN
   - **Client generates PIN** using `PairingManager.generatePinString()` (4-digit random)
   - **Client displays PIN** prominently to user
   - **User enters PIN on server** (Sunshine/GFE pairing dialog)
   - **Client automatically starts pairing** with generated PIN
3. **Pair with Server**: `MoonlightConnectionManager.pairWithServer()`
   - Uses client-generated PIN for pairing handshake
   - Server validates PIN (user must enter same PIN on server)
   - Establishes secure certificate pairing via challenge-response
4. **Persist Identity & Certificate**:
   - Single persistent client ID reused for all NvHTTP/NvConnection calls
   - Paired server certificate cached and injected into every HTTP/connection attempt
5. **If Paired**: Proceed with connection
   - Server certificate stored for future connections
   - No PIN required for subsequent connections

**Key Point**: The PIN is **generated by the client**, displayed to the user, and the user enters it on the server. This is the reverse of what many users expect - the server does NOT generate the PIN.

**Pairing States**:

- `NOT_PAIRED` - Server requires pairing
- `PAIRED` - Server is paired, ready to connect
- `PIN_WRONG` - Incorrect PIN entered
- `ALREADY_IN_PROGRESS` - Another device is currently pairing
- `FAILED` - Pairing process failed

**Implementation**:

- Uses Moonlight's `PairingManager` class
- Handles SHA-1 (Gen 6) and SHA-256 (Gen 7+) hashing
- Establishes secure certificate exchange
- Certificate stored for future connections

**Network Security Configuration**:

- **File**: `app/src/main/res/xml/network_security_config.xml`
- **Purpose**: Allows cleartext (HTTP) traffic for initial pairing handshake
- **Configuration**: `cleartextTrafficPermitted="true"` in base config
- **Why Needed**: Pairing uses HTTP before server certificate is established
- **Security**: After pairing, all connections use HTTPS with certificate pinning
- **Reference**: Set in `AndroidManifest.xml` via `android:networkSecurityConfig="@xml/network_security_config"`

**Note**: Android 9+ blocks cleartext traffic by default. This configuration is required for Moonlight pairing to work, matching the moonlight-android implementation.

---

## COMMUNICATION FLOW

### Connection Flow

**2D Mode (PancakeActivity)**:

1. User enters host/port/appId; may fetch server capabilities and set stream prefs (res/fps/format).
2. “Connect & Launch Immersive”:
   - `checkPairing(host, port)` → if not paired, generate PIN and `pairWithServer`.
   - On paired: saves prefs, launches `ImmersiveActivity` with host/port/appId extras.
3. App list fetch (optional) after pairing to populate appId.

**Immersive Mode (ImmersiveActivity)**:

1. onCreate(): create decoder/audio/connection manager; stash pending host/port/appId; no pairing here.
2. onSceneReady(): configure lighting/passthrough; create video panel entity; register panels.
3. Panel `surfaceConsumer`: paint black → attachSurface → preConfigureDecoder → mark `isSurfaceReady` → if pending params, call `connectToHost`.
4. `connectToHost` sets `isPaired=true`, stores params, calls `startStreamIfReady`.
5. `startStreamIfReady`: if surface ready and paired, calls `connectionManager.startStream` with prefs (resolution/fps/bitrate/format) and pending host/port/appId.
6. MoonBridge handles decoder callbacks; renderer already pre-configured so native setup skips reconfiguration.
7. stopStream() on shutdown/disconnect cleans up connection/decoder.

**Status Updates**:

- All connection stages report via `NvConnectionListener` callbacks.
- Status updates flow to `onStatusUpdate` callback.
- UI updates via `StateFlow` in `ImmersiveActivity`.

---

## CURRENT STATE & FUTURE ENHANCEMENTS

### Current Implementation

**✅ Completed**:

- Hybrid 2D/immersive architecture
- 2D connection UI with keyboard support
- PIN pairing system (client-generated PIN)
- Network security configuration (cleartext traffic for pairing)
- Background thread execution (prevents ANR)
- Video panel in immersive mode
- Passthrough mode enabled
- Connection lifecycle management

**⚠️ Limitations**:

- Video panel only works in immersive mode
- No 2D video display (connection UI only in 2D)
- No MRUK features (anchoring, wall detection)
- No scaling/interaction systems
- Static panel registration (not dynamic)
- Codec negotiates limited range/dataspace despite full-range/HDR flags; colors are now correct but rely on codec behavior.
- Occasional translucent/white overlay on the panel; likely compositor/panel-layer related (can clear after headset off/on).

### Future Enhancements

**Phase 1: Hybrid Video Display** (From SAMPLE_ARCHITECTURE_ANALYSIS.md):

- Add video panel to `PancakeActivity` for 2D streaming
- Ensure video connection persists when switching modes
- Support video in both 2D window and immersive mode

**Phase 2: MRUK Integration**:

- Add `MRUKFeature` to `ImmersiveActivity`
- Request `USE_SCENE` permission
- Load MRUK scene on activity start
- Add anchoring components to video panel entity

**Phase 3: Advanced Features**:

- Add scaling systems (`AnalogScalableSystem`, `TouchScalableSystem`)
- Register video panel entity with scalable system
- Add panel layer alpha for transitions
- (Optional) Add lighting systems

---

## KEY FILES REFERENCE

### Main Activities

- `PancakeActivity.kt` - 2D panel activity with connection UI
- `ImmersiveActivity.kt` - VR activity with video streaming

### Core Components

- `MoonlightConnectionManager.kt` - Connection and pairing management
- `MoonlightPanelRenderer.kt` - Video decoder integration
- `LegacySurfaceHolderAdapter.kt` - Surface adapter for Moonlight

### Configuration Files

- `AndroidManifest.xml` - App manifest with network security config reference
- `res/xml/network_security_config.xml` - Network security config allowing cleartext traffic for pairing

### Moonlight Integration

- `com.limelight.nvstream.NvConnection` - Moonlight connection class
- `com.limelight.nvstream.http.NvHTTP` - HTTP communication
- `com.limelight.nvstream.http.PairingManager` - PIN pairing
- `com.limelight.binding.video.NativeDecoderRenderer` - Native video decoder (AMediaCodec)
- `com.limelight.binding.audio.AndroidAudioRenderer` - Audio renderer

---

## RELATED DOCUMENTATION

- `Documentation/SAMPLE_ARCHITECTURE_ANALYSIS.md` - Comparison of HybridSample vs PremiumMediaSample
- `Documentation/moonlight-migration-plan.md` - Migration planning document
- `Documentation/SPATIAL_PORT_FEASIBILITY_REPORT.md` - Feasibility analysis

---

## SUMMARY

The Moonlight-SpatialSDK Quest 3 app is a hybrid application that:

**Strengths**:

- ✅ Hybrid 2D/immersive architecture following Meta's best practices
- ✅ PIN pairing system for secure first-time connections (client-generated PIN)
- ✅ Network security configuration for pairing compatibility
- ✅ Background thread execution prevents ANR
- ✅ Clean separation between connection UI and video streaming
- ✅ Passthrough mode for mixed reality experience

**Areas for Enhancement**:

- ⚠️ Video display currently only in immersive mode
- ⚠️ No MRUK features (anchoring, wall detection)
- ⚠️ No scaling/interaction systems
- ⚠️ Static panel registration (not dynamic like PremiumMediaSample)

**Architecture Alignment**:

- ✅ Follows Meta Spatial SDK hybrid app pattern
- ✅ Matches HybridSample structure for mode switching
- 🔄 Ready for PremiumMediaSample-style enhancements (MRUK, scaling)

The core streaming functionality is working, with pairing support and proper lifecycle management. The next phase is to add video display in 2D mode and enhance immersive mode with MRUK features.
