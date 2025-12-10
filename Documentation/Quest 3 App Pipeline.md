# Quest 3 App Pipeline

This document provides a comprehensive breakdown of the Moonlight-SpatialSDK Quest 3 application architecture, focusing on the immersive-only VR mode structure, connection management, and video streaming integration.

**Architecture**: The Quest 3 app uses Meta Spatial SDK with an immersive-only pattern, launching directly into VR mode for Moonlight game streaming. The app features a connection panel for setup and a video panel for streaming, both managed through a PanelManager entity.

## Client Architecture

### File Structure

- **Immersive Activity**: `ImmersiveActivity.kt` - VR activity (default launcher) for connection UI and video streaming
- **2D Activity**: `PancakeActivity.kt` - 2D panel activity (legacy, not default launcher) for connection UI and pairing
- **Core Components**:
  - `PanelManager.kt` - Manages root entity for all panel entities
  - `PanelPositioningSystem.kt` - Positions PanelManager entity in front of user
  - `MoonlightConnectionManager.kt` - Connection lifecycle, pairing, and stream management
  - `MoonlightPanelRenderer.kt` - Bridges Spatial panel Surface to Moonlight native decoder
  - `LegacySurfaceHolderAdapter.kt` - Adapter for Moonlight's SurfaceHolder interface
  - `ConnectionPanelImmersive.kt` - Compose UI for connection management in VR

**Purpose**: Immersive-only Moonlight game streaming application launching directly into VR mode with passthrough. Features connection panel for setup and video panel for streaming, both managed through PanelManager.

---

## TABLE OF CONTENTS

1. [Main Activities](#main-activities)
2. [Panel Management](#panel-management)
3. [Connection Management](#connection-management)
4. [Video Panel Rendering](#video-panel-rendering)
5. [Pairing System](#pairing-system)
6. [Communication Flow](#communication-flow)
7. [Current State & Future Enhancements](#current-state--future-enhancements)

---

## MAIN ACTIVITIES

### ImmersiveActivity (VR Mode - Default Launcher)

**File**: `ImmersiveActivity.kt`

**Purpose**: Immersive VR activity that serves as the default launcher. Provides connection UI and video streaming in a single immersive experience.

**Launch Configuration**:

- **Default Launcher**: `ImmersiveActivity` is set as the default launcher in `AndroidManifest.xml`
- **Intent Categories**: `android.intent.category.LAUNCHER` and `com.oculus.intent.category.VR`
- **Launch Mode**: `singleTask` to prevent multiple instances

**Key Features**:

- **Connection Panel**: Compose UI panel for connection setup, pairing, and stream preferences
- **Video Panel**: Direct-to-surface media panel for Moonlight video streaming
- **PanelManager**: Root entity that manages positioning of all panels
- **Dynamic Panel Registration**: Video panel registered using `executeOnVrActivity` for lifecycle alignment
- **Panel Visibility Management**: Connection panel visible initially, video panel hidden until stream ready
- Passthrough mode enabled
- Scene setup with lighting and environment

**Panel Architecture**:

- **PanelManager Entity**: Root entity positioned by `PanelPositioningSystem` in front of user
- **Connection Panel Entity**: Child of PanelManager, visible on launch
- **Video Panel Entity**: Child of PanelManager, hidden until stream is ready
- Both panels positioned at same location (Vector3(0f, 0f, 0f)) relative to PanelManager

---

### PancakeActivity (2D Mode - Legacy)

**File**: `PancakeActivity.kt`

**Purpose**: 2D panel activity for connection setup and pairing (legacy, not default launcher).

**Status**: Available but not used as default launcher. ImmersiveActivity now provides connection UI in VR mode.

**Key Features**:

- Host/port/appId inputs with persistence (`connection_prefs`)
- Pairing flow using `MoonlightPairingHelper` and `PairingManager`
- Optional app list and server capability fetch
- Stream preference configuration
- Can launch `ImmersiveActivity` with connection params

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

#### `onSceneReady()`

**Purpose**: Configure scene after Spatial SDK initialization and create panel entities

**Key Steps**:

1. Disable locomotion (prevents controller movement)
2. Enable passthrough for mixed reality
3. Set lighting environment (ambient, sun, intensity)
4. Update IBL environment from assets
5. Set view origin (position and rotation)
6. Register `PanelPositioningSystem` for panel placement
7. Create `PanelManager` entity (root for all panels)
8. Create video panel entity (hidden initially)
9. Create connection panel entity (visible initially)

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

// Panel management
panelPositioningSystem = PanelPositioningSystem()
systemManager.registerSystem(panelPositioningSystem!!)

panelManager = PanelManager()
val panelManagerEntity = panelManager!!.create()
panelPositioningSystem?.setPanelEntity(panelManagerEntity)

createVideoPanelEntity()
createConnectionPanelEntity()
```

#### `registerPanels()`

**Purpose**: Register the connection panel (Compose UI). Video panel is registered dynamically in `createVideoPanelEntity()`.

**Panel Registration**:

```kotlin
override fun registerPanels(): List<PanelRegistration> {
    // Video panel is registered dynamically in createVideoPanelEntity() using executeOnVrActivity
    // to ensure panelManager is initialized before registration (lifecycle alignment)
    return listOf(
        PanelRegistration(R.id.connection_panel) {
            config {
                fractionOfScreen = 0.8f
                height = basePanelHeightMeters
                width = basePanelHeightMeters * 0.8f
                layoutDpi = 160
                layerConfig = LayerConfig()
                enableTransparent = true
                includeGlass = false
                themeResourceId = R.style.PanelAppThemeTransparent
            }
            composePanel { setContent {
                ConnectionPanelImmersive(...)
            }}
        },
    )
}
```

**Connection Panel Features**:

- Compose UI for connection management
- Host/port/appId inputs with persistence
- Pairing flow (check → generate PIN → pair)
- Stream preference configuration
- Connect button that hides connection panel and starts stream
- Clear pairing button

#### `createVideoPanelEntity()`

**Purpose**: Create video panel entity and register panel dynamically using `executeOnVrActivity`.

**Key Steps**:

1. Register video panel using `SpatialActivityManager.executeOnVrActivity` (ensures activity is ready)
2. Configure `VideoSurfacePanelRegistration` with surface consumer and settings
3. Create entity with `Panel(R.id.ui_example)` component
4. Parent entity to PanelManager
5. Set initial visibility to `false` (shown when stream ready)

**Video Panel Registration**:

```kotlin
SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
    immersiveActivity.registerPanel(
        VideoSurfacePanelRegistration(
            R.id.ui_example,
            surfaceConsumer = { panelEntity, surface ->
                // Store panel entity reference
                videoPanelEntity = panelEntity
                
                // Parent to PanelManager (guaranteed to be initialized)
                val managerEntity = panelManager?.panelManagerEntity
                if (managerEntity != null) {
                    panelEntity.setComponent(TransformParent(managerEntity))
                    panelEntity.setComponent(Transform(Pose(Vector3(0f, 0f, 0f))))
                }
                
                // Panel starts hidden - shown when stream is ready
                panelEntity.setComponent(Visible(false))
                panelEntity.setComponent(Grabbable(enabled = true, type = GrabbableType.PIVOT_Y))
                
                SurfaceUtil.paintBlack(surface)
                moonlightPanelRenderer.attachSurface(surface)
                moonlightPanelRenderer.preConfigureDecoder()
                
                isSurfaceReady = true
                
                // Start connection if pending params exist
                pendingConnectionParams?.let { (host, port, appId) ->
                    connectToHost(host, port, appId)
                }
            },
            settingsCreator = {
                MediaPanelSettings(
                    shape = computePanelShape(),
                    display = PixelDisplayOptions(width = prefs.width, height = prefs.height),
                    rendering = MediaPanelRenderOptions(
                        isDRM = false,
                        stereoMode = StereoMode.None,
                        zIndex = 0 // Rectilinear panels use zIndex 0
                    ),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                )
            },
        )
    )
}

// Create entity after panel registration
videoPanelEntity = Entity.create(
    listOf(
        Panel(R.id.ui_example),
        Transform(Pose(Vector3(0f, 0f, 0f))),
        PanelDimensions(panelSize),
        Scale(Vector3(1f)), // Initial scale of 1.0
        Grabbable(enabled = true, type = GrabbableType.PIVOT_Y),
        Visible(false), // Hidden initially
        TransformParent(panelManagerEntity)
    )
)
```

**Panel Configuration**:

- **Shape**: Computed from `prefs.width/prefs.height` to match stream aspect ratio
- **Display**: `PixelDisplayOptions(width = prefs.width, height = prefs.height)` - supports 4K, 1440p, 1080p
- **Rendering**: Monoscopic (`StereoMode.None`), `zIndex = 0` for rectilinear panels
- **Scale**: Initial scale of 1.0, adjustable via `updateVideoPanelScale()` after connection
- **Surface handling**: Paint black → attachSurface → preConfigureDecoder → mark surface ready → start pending connection if present

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

## PANEL MANAGEMENT

### PanelManager

**File**: `PanelManager.kt`

**Purpose**: Manages the root entity that serves as the parent for all panel entities, allowing all panels to be positioned together as a group.

**Key Features**:

- Creates root entity with `Transform`, `Visible(true)`, and `Grabbable` components
- Serves as parent for both connection panel and video panel entities
- Positioned by `PanelPositioningSystem` in front of user
- All child panels positioned relative to PanelManager (Vector3(0f, 0f, 0f) offset)

**Implementation**:

```kotlin
class PanelManager {
    var panelManagerEntity: Entity? = null
    
    fun create(): Entity {
        panelManagerEntity = Entity.create(
            listOf(
                Transform(),
                Visible(true),
                Grabbable(enabled = true, type = GrabbableType.PIVOT_Y)
            )
        )
        return panelManagerEntity!!
    }
}
```

**Usage**:

- Created in `onSceneReady()` after `PanelPositioningSystem` is registered
- Set as target for `PanelPositioningSystem.setPanelEntity()`
- All panel entities parented to `panelManagerEntity` using `TransformParent` component

### PanelPositioningSystem

**File**: `PanelPositioningSystem.kt`

**Purpose**: Positions the PanelManager entity in front of the user's head at a comfortable viewing distance.

**Key Features**:

- Positions PanelManager entity (not individual panels)
- Calculates position based on head tracking
- Places panel at configurable distance (default: 1.0m)
- Applies eye-level offset for comfortable viewing

**Lifecycle**:

- Registered in `onSceneReady()`
- PanelManager entity set via `setPanelEntity()`
- Executes each frame until panel is positioned
- Retries up to 60 times if head tracking not ready

### Panel Visibility Management

**Initial State**:

- **Connection Panel**: `Visible(true)` - Shown on launch
- **Video Panel**: `Visible(false)` - Hidden until stream ready

**State Transitions**:

1. **App Launch**: Connection panel visible, video panel hidden
2. **User Clicks Connect**: Connection panel set to `Visible(false)`, connection starts
3. **Stream Ready**: Video panel set to `Visible(true)` when `connectionManager.onStatusUpdate` reports `connected = true`

**Implementation**:

```kotlin
// In connectToHost()
connectionPanelEntity?.setComponent(Visible(false))

// In connectionManager.onStatusUpdate callback
if (connected) {
    videoPanelEntity?.setComponent(Visible(true))
}
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

1. **onCreate()**: 
   - Create decoder/audio/connection manager
   - Initialize `pairingHelper`
   - Read connection params from Intent extras or shared preferences
   - Store as pending (no connect yet)

2. **onSceneReady()**: 
   - Configure lighting/passthrough
   - Register `PanelPositioningSystem`
   - Create `PanelManager` entity and set on positioning system
   - Create video panel entity (registers panel dynamically using `executeOnVrActivity`)
   - Create connection panel entity

3. **registerPanels()**: 
   - Register connection panel (Compose UI)
   - Video panel registered dynamically in `createVideoPanelEntity()`

4. **Panel `surfaceConsumer`** (video panel): 
   - Paint black → attachSurface → preConfigureDecoder → mark `isSurfaceReady`
   - Parent panel to PanelManager
   - If pending params exist, call `connectToHost`

5. **User clicks Connect** (connection panel): 
   - Hide connection panel (`Visible(false)`)
   - Call `connectToHost(host, port, appId)`

6. **connectToHost()**: 
   - Sets `isPaired=true`, stores params
   - Calls `startStreamIfReady()`

7. **startStreamIfReady()**: 
   - If surface ready and paired, calls `connectionManager.startStream` with prefs
   - Stream starts with negotiated resolution/fps/bitrate/format

8. **Stream Ready**: 
   - `connectionManager.onStatusUpdate` reports `connected = true`
   - Video panel set to `Visible(true)`

9. **stopStream()**: 
   - On shutdown/disconnect, cleans up connection/decoder

**Status Updates**:

- All connection stages report via `NvConnectionListener` callbacks.
- Status updates flow to `onStatusUpdate` callback.
- UI updates via `StateFlow` in `ImmersiveActivity`.

---

## CURRENT STATE & FUTURE ENHANCEMENTS

### Current Implementation

**✅ Completed**:

- Immersive-only architecture (default launcher)
- Connection panel in VR mode (Compose UI)
- Video panel in immersive mode
- PanelManager for unified panel positioning
- Dynamic panel registration using `executeOnVrActivity` (lifecycle alignment)
- Panel visibility management (connection panel → video panel transition)
- PIN pairing system (client-generated PIN)
- Network security configuration (cleartext traffic for pairing)
- Background thread execution (prevents ANR)
- Passthrough mode enabled
- Connection lifecycle management
- Panel scaling support (`Scale` component, `updateVideoPanelScale()` method)
- zIndex configuration for rectilinear panels

**⚠️ Limitations**:

- Immersive-only (no 2D video display)
- No MRUK features (anchoring, wall detection)
- No advanced scaling/interaction systems (AnalogScalableSystem, TouchScalableSystem)
- Known SDK issue: Video surface color space initialization (affects PremiumMediaSample too)
  - Colors may be incorrect on first frame
  - Resolves after device sleep/wake cycle
  - See `POST_MORTEM.md` for details

### Future Enhancements

**Phase 1: MRUK Integration**:

- Add `MRUKFeature` to `ImmersiveActivity`
- Request `USE_SCENE` permission
- Load MRUK scene on activity start
- Add anchoring components to video panel entity
- Support wall/ceiling/floor detection and anchoring

**Phase 2: Advanced Scaling Systems**:

- Add `AnalogScalableSystem` for controller-based scaling
- Add `TouchScalableSystem` for touch-based scaling
- Register video panel entity with scalable system
- Add `Scalable`, `ScaledParent`, `ScaledChild` components

**Phase 3: Panel Transitions**:

- Add `PanelLayerAlpha` component and system for fade effects
- Implement fade in/out for panel visibility transitions
- Add `FadingPanel` base class pattern (from PremiumMediaSample)

**Phase 4: Advanced Features**:

- Add hero lighting system for video panel
- Add wall lighting system (MRUK integration)
- Add cinema state handler (TV/Cinema modes)
- Add control panel for playback controls

---

## KEY FILES REFERENCE

### Main Activities

- `ImmersiveActivity.kt` - VR activity (default launcher) with connection UI and video streaming
- `PancakeActivity.kt` - 2D panel activity (legacy, not default launcher)

### Core Components

- `PanelManager.kt` - Root entity manager for all panels
- `PanelPositioningSystem.kt` - Positions PanelManager in front of user
- `MoonlightConnectionManager.kt` - Connection and pairing management
- `MoonlightPanelRenderer.kt` - Video decoder integration
- `LegacySurfaceHolderAdapter.kt` - Surface adapter for Moonlight
- `ConnectionPanelImmersive.kt` - Compose UI for connection management in VR

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

The Moonlight-SpatialSDK Quest 3 app is an immersive-only application that:

**Strengths**:

- ✅ Immersive-only architecture launching directly into VR mode
- ✅ PanelManager for unified panel positioning and management
- ✅ Dynamic panel registration using `executeOnVrActivity` (lifecycle alignment)
- ✅ Connection panel in VR mode (Compose UI) for seamless setup
- ✅ Panel visibility management (connection → video panel transition)
- ✅ PIN pairing system for secure first-time connections (client-generated PIN)
- ✅ Network security configuration for pairing compatibility
- ✅ Background thread execution prevents ANR
- ✅ Passthrough mode for mixed reality experience
- ✅ Panel scaling support with `Scale` component

**Architecture Alignment**:

- ✅ Follows Meta Spatial SDK immersive app pattern
- ✅ Uses `executeOnVrActivity` for dynamic panel registration (PremiumMediaSample pattern)
- ✅ PanelManager pattern for unified panel management
- 🔄 Ready for PremiumMediaSample-style enhancements (MRUK, advanced scaling, lighting)

**Known Issues**:

- ⚠️ Video surface color space initialization issue (affects PremiumMediaSample too)
  - Colors may be incorrect on first frame
  - Resolves after device sleep/wake cycle
  - See `POST_MORTEM.md` for details

The core streaming functionality is working, with pairing support and proper lifecycle management. The app launches directly into immersive mode with connection UI, transitioning to video streaming once connected. The next phase is to add MRUK features and advanced scaling systems.
