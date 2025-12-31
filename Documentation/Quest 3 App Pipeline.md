# Quest 3 App Pipeline

This document provides a comprehensive breakdown of the Moonlight-SpatialSDK Quest 3 application architecture, focusing on the immersive-only VR mode structure, connection management, and video streaming integration.

**Architecture**: The Quest 3 app uses Meta Spatial SDK with a hybrid app pattern, launching into 2D panel mode first (workaround for virtual keyboard issues), then transitioning to immersive VR mode for Moonlight game streaming. The app features a connection panel for setup and a video panel for streaming, both managed through a PanelManager entity.

## Client Architecture

### File Structure

- **2D Activity**: `PancakeActivity.kt` - 2D panel activity (default launcher) for connection UI and pairing
- **Immersive Activity**: `ImmersiveActivity.kt` - VR activity for video streaming (launched from 2D panel)
- **Core Components**:
  - `PanelManager.kt` - Manages root entity for all panel entities
  - `PanelPositioningSystem.kt` - Positions PanelManager entity in front of user
  - `MoonlightConnectionManager.kt` - Connection lifecycle, pairing, and stream management
  - `MoonlightPanelRenderer.kt` - Bridges Spatial panel Surface to Moonlight native decoder
  - `LegacySurfaceHolderAdapter.kt` - Adapter for Moonlight's SurfaceHolder interface

**Purpose**: Hybrid Moonlight game streaming application launching into 2D panel mode first (workaround for Meta Horizon OS virtual keyboard issues), then transitioning to immersive VR mode with passthrough. Features connection panel for setup and video panel for streaming, both managed through PanelManager.

---

## TABLE OF CONTENTS

1. [Main Activities](#main-activities)
2. [Panel Management](#panel-management)
3. [Connection Management](#connection-management)
4. [Video Panel Rendering](#video-panel-rendering)
5. [Video Panel Scaling](#video-panel-scaling)
6. [ButtonShelf Controls](#buttonshelf-controls)
7. [Snap to Wall](#snap-to-wall)
8. [Pairing System](#pairing-system)
9. [Communication Flow](#communication-flow)
10. [Entity Creation Redundancy](#entity-creation-redundancy)
11. [Current State & Future Enhancements](#current-state--future-enhancements)

---

## MAIN ACTIVITIES

### PancakeActivity (2D Mode - Default Launcher)

**File**: `PancakeActivity.kt`

**Purpose**: 2D panel activity that serves as the default launcher. Provides connection UI and pairing with working system keyboard (workaround for virtual keyboard issues in immersive mode).

**Launch Configuration**:

- **Default Launcher**: `PancakeActivity` is set as the default launcher in `AndroidManifest.xml`
- **Intent Categories**: `android.intent.category.LAUNCHER` and `com.oculus.intent.category.2D`
- **Panel Size**: 800dp x 550dp when not in immersive mode

**Key Features**:

- **Card-Based Connection UI**: Modern card-based layout matching immersive version
- **Server Cards**: Shows connected server name/IP and "Connect to PC" / "Pair New Server" options
- **Pairing Dialog**: Overlay dialog for entering server connection details
- **PIN Display**: Dialog showing pairing PIN for server entry
- **Options Dialog**: Stream configuration and reset pairing options
- **Application Selection**: Dropdown for selecting target application
- **Working Keyboard**: System keyboard works reliably in 2D panel mode
- Launches `ImmersiveActivity` with connection params when ready

**Why 2D Panel First?**:

Meta Horizon OS has known issues with virtual keyboard positioning in immersive mode (see `KEYBOARD_VANISH_ANALYSIS.md`). The keyboard fails to position properly, becomes invisible, or blocks UI interaction. By launching in 2D panel mode first, we leverage the stable system keyboard in the Home environment for reliable text input.

---

### ImmersiveActivity (VR Mode)

**File**: `ImmersiveActivity.kt`

**Purpose**: Immersive VR activity for video streaming. Launched from PancakeActivity after connection setup.

**Launch Configuration**:

- **Not Default Launcher**: `ImmersiveActivity` is launched programmatically from PancakeActivity
- **Intent Categories**: `com.oculus.intent.category.VR` only
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

---

### ImmersiveActivity Lifecycle Methods

**File**: `ImmersiveActivity.kt`

**Purpose**: Detailed breakdown of ImmersiveActivity lifecycle and panel creation flow

**Key Features**:

- Video panel registration for Moonlight stream
- Passthrough mode enabled
- Connection management with pairing check
- Scene setup with lighting and environment

**Lifecycle Methods**:

#### `onCreate()`

**Purpose**: Initialize streaming components and stash pending connection params.

**Key Steps**:

1. **Initialize MediaCodecHelper**: Call `MediaCodecHelper.initialize(this, getQuestGlRenderer())` with Quest 3's Adreno 740 GPU identifier. This enables explicit decoder selection, decoder preference logic, and capability checking. Must be called BEFORE creating decoder renderer.
2. Create `MoonlightPanelRenderer` (native decoder), `AndroidAudioRenderer`, `MoonlightConnectionManager`.
3. **Register scaling components and systems**:
   - Register `Scalable` and `ScaledParent` components with component manager
   - Register `PointerInfoSystem` for hover detection
   - Register `TouchScalableSystem` for corner-based scaling (minScale=0.5f, maxScale=10.0f)
4. Read connection params from Intent extras (host/port/appId); store as pending (no connect yet).
5. Init `NetworkedAssetLoader`.

**MediaCodecHelper Initialization**:

```kotlin
// Initialize MediaCodecHelper BEFORE creating decoder renderer
// Quest 3/3S uses Adreno 740 GPU - we use a known value since we're targeting specific hardware
val glRenderer = getQuestGlRenderer() // Returns "Adreno (TM) 740"
Log.i(TAG, "Initializing MediaCodecHelper with GL renderer: $glRenderer")
MediaCodecHelper.initialize(this, glRenderer)
Log.i(TAG, "MediaCodecHelper initialized successfully")
```

**Benefits**:

- Enables explicit decoder selection via `MediaCodecHelper.findBestDecoderForMime()`

- Leverages MediaCodecHelper's decoder preference and blacklisting logic
- Enables capability checking (low latency, adaptive playback support)
- GPU capability detection (low-end Snapdragon, Adreno model detection)

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

**Purpose**: Register panels for Compose UI. Video panel is registered dynamically in `createVideoPanelEntity()`.

**Panel Registration**:

```kotlin
override fun registerPanels(): List<PanelRegistration> {
    // Video panel is registered dynamically in createVideoPanelEntity() using executeOnVrActivity
    // to ensure panelManager is initialized before registration (lifecycle alignment)
    return listOf(
        PanelRegistration(R.id.disconnect_dialog_panel) { /* ... */ },
        PanelRegistration(R.id.button_shelf) { /* ... */ },
        PanelRegistration(R.id.stereo_depth_slider) { /* ... */ },
    )
}
```

**Note**: Connection panel (`ConnectionPanelImmersive`) has been removed. Connection management is now handled entirely by `PancakeActivity` (2D panel mode) before launching into immersive VR mode.

#### `createVideoPanelEntity()`

**Purpose**: Create video panel entity and register panel dynamically using `executeOnVrActivity` with robust retry logic and fallback mechanisms.

**Key Steps**:

1. Register video panel using `SpatialActivityManager.executeOnVrActivity` (ensures activity is ready)
2. Configure panel registration (`PanelCreator`, `ReadableVideoSurfacePanelRegistration`, or `VideoSurfacePanelRegistration`) with surface consumer and settings
3. **SDK creates entity automatically** during registration (provided in callbacks)
4. **Entity verification and retry logic**:
   - Retry registration with exponential backoff (3 attempts: 100ms, 200ms, 400ms delays)
   - Poll for entity creation via Query if SDK callback doesn't set it (10 attempts, 50ms delay)
   - Fallback entity creation if all retries fail
5. Parent entity to PanelManager
6. Set initial visibility based on connection state (visible on launch if no pending connection, hidden if connection pending)

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
        Scalable(), // Enable corner scaling
        ScaledParent(), // Mark as scalable parent
        TransformParent(panelManagerEntity)
    )
)

// Register video panel with scaling system
val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
touchScalableSystem?.registerEntity(videoPanelEntity!!)
```

**Panel Configuration**:

- **Physical Size**: Computed from `prefs.width/prefs.height` to match stream aspect ratio
  - Height: `1.0f` (1.0m base height)
  - Width: `1.0f * (prefs.width / prefs.height)` (e.g., 1.778m for 2560x1440p stream)
  - Aspect ratio matches single-eye video resolution (16:9 for 2560x1440p)
- **PanelConfigOptions** (for direct-to-surface rendering):
  - `width = 1.0f * (prefs.width.toFloat() / prefs.height.toFloat())` - Matches video resolution aspect ratio
  - `height = 1.0f` - Normalized height
  - `layoutWidthInPx = prefs.width * 2` (for stereoscopic) or `prefs.width` (for standard)
  - `layoutHeightInPx = prefs.height`
- **Rendering**: Monoscopic (`StereoMode.None`) or stereoscopic (`StereoMode.LeftRight`/`UpDown`)
- **Scale**: Initial scale of 1.0, adjustable via corner handles or `updateVideoPanelScale()` after connection
- **Scaling Components**: `Scalable()` and `ScaledParent()` components enable corner-based scaling
- **Surface handling**: Paint black → attachSurface → preConfigureDecoder → mark surface ready → start pending connection if present

**Registration Modes**:

1. **Stereoscopic Depth Mode** (Direct-to-surface with `PanelSceneObject`):
   - Uses `PanelSceneObject` with `PanelConfigOptions` for custom shader support
   - Entity created manually with all required components (Transform, Hittable, PanelDimensions, etc.)
   - `PanelConfigOptions.width` uses video resolution aspect ratio: `1.0f * (prefs.width / prefs.height)`
   - `layoutWidthInPx = prefs.width * 2` (5120 for 2560x1440p user resolution)
   - `layoutHeightInPx = prefs.height` (1440)
   - Physical panel dimensions: `1.0f * aspectRatio` (1.778m x 1.0m for 2560x1440p)
   - Entity stored in `videoPanelEntity` and added to SceneObjectSystem

2. **Lighting Emission Mode** (`ReadableVideoSurfacePanelRegistration`):
   - Uses `ReadableVideoSurfacePanelRegistration` for texture sampling
   - SDK provides `panelEntity` in `surfaceConsumer` callback
   - ✅ **Resolved**: SDK-provided entity is stored and used, no duplicate entity creation

3. **Standard Mode** (`VideoSurfacePanelRegistration`):
   - Uses `VideoSurfacePanelRegistration` for direct-to-surface rendering
   - SDK provides `panelEntity` in `surfaceConsumer` callback
   - ✅ **Resolved**: SDK-provided entity is stored and used, no duplicate entity creation

**Retry and Fallback Mechanisms**:

- **Retry Logic**: Up to 3 registration attempts with exponential backoff (100ms, 200ms, 400ms)
- **Entity Verification**: Polls for entity creation via Query if SDK callback doesn't set it (10 attempts, 50ms delay)
- **Fallback Entity**: Creates minimal fallback entity if all retries fail, ensuring at least one panel is visible
- **Visibility Management**: Video panel is visible on launch if no pending connection params exist

#### `onVRPause()` / `onHMDUnmounted()`

**Purpose**: Store connection state before device sleep/pause for resume recovery.

**Key Steps**:

1. Check if connection is active via `connectionManager.isConnected()`
2. If connected, retrieve connection parameters from `connectionManager.getCurrentConnectionParams()`
3. Store connection state (`wasConnectedBeforePause = true`) and parameters (`connectionParamsBeforePause`)
4. Fallback to `pendingConnectionParams` if connection manager doesn't have stored params

**Implementation**:

```kotlin
override fun onVRPause() {
    super.onVRPause()
    wasConnectedBeforePause = connectionManager.isConnected()
    if (wasConnectedBeforePause) {
        // Get connection params from connection manager (stored when stream starts)
        connectionParamsBeforePause = connectionManager.getCurrentConnectionParams()
        if (connectionParamsBeforePause == null) {
            // Fallback to pendingConnectionParams
            connectionParamsBeforePause = pendingConnectionParams
        }
    }
}
```

**Note**: Both `onVRPause()` and `onHMDUnmounted()` implement the same logic to handle different pause scenarios (system pause vs. headset removal).

#### `onVRReady()` / `onHMDMounted()`

**Purpose**: Re-establish video stream after device wake/resume if it died during sleep.

**Key Steps**:

1. Check if we were connected before pause (`wasConnectedBeforePause`)
2. If connection is lost, re-establish stream using stored parameters
3. If connection is still active but video stream may be dead, call `connectionManager.checkAndRestartVideoStreamIfNeeded()`
4. Reset stored state after recovery attempt

**Implementation**:

```kotlin
override fun onVRReady() {
    super.onVRReady()
    if (wasConnectedBeforePause) {
        val isCurrentlyConnected = connectionManager.isConnected()
        if (!isCurrentlyConnected && connectionParamsBeforePause != null) {
            // Connection lost during sleep - re-establish stream
            val (host, port, appId) = connectionParamsBeforePause!!
            pendingConnectionParams = connectionParamsBeforePause
            isPaired = true
            startStreamIfReady()
        } else if (isCurrentlyConnected) {
            // Connection still active - check if video stream needs recovery
            connectionManager.checkAndRestartVideoStreamIfNeeded()
        }
        // Reset state
        wasConnectedBeforePause = false
        connectionParamsBeforePause = null
    }
}
```

**Recovery Strategy**:

- **Connection Lost**: Full stream restart with stored parameters (host, port, appId, prefs)
- **Connection Active**: Video stream recovery via `checkAndRestartVideoStreamIfNeeded()` which performs full stream restart to re-establish video path

**Note**: Both `onVRReady()` and `onHMDMounted()` implement the same recovery logic to handle different resume scenarios.

#### `createConnectionPanelEntity()`

**Purpose**: Create connection panel entity with dimensions matching the panel registration config.

**Key Steps**:

1. Calculate panel dimensions to match registration config:
   - Height: `basePanelHeightMeters * 0.75f` (0.525m)
   - Width: `basePanelHeightMeters * 0.6f` (0.42m)
2. Create entity with `Panel(R.id.connection_panel)` component
3. Parent entity to PanelManager
4. Set initial visibility to `true` (shown on launch, hidden when connecting)

**Connection Panel Entity Creation**:

```kotlin
private fun createConnectionPanelEntity() {
    // Connection panel size - match the registration config to UISetSample "UI Components" panel size
    val connectionPanelHeight = basePanelHeightMeters * 0.75f  // 0.525m
    val connectionPanelWidth = basePanelHeightMeters * 0.6f      // 0.42m
    val panelSize = Vector2(connectionPanelWidth, connectionPanelHeight)
    
    connectionPanelEntity = Entity.create(
        listOf(
            Panel(R.id.connection_panel),
            Transform(Pose(Vector3(0f, 0f, 0f))),
            PanelDimensions(panelSize),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y),
            Visible(true), // Visible initially, hidden when connect is pressed
            TransformParent(panelManagerEntity)
        )
    )
}
```

**Panel Configuration**:

- **Size**: 0.42m × 0.525m (matches UISetSample "UI Components" panel size)
- **Resolution**: `layoutDpi = 240` for improved text clarity
- **Visibility**: Starts visible, hidden when user initiates connection
- **Parenting**: Parented to PanelManager for unified positioning

**Note**: Entity dimensions must match panel registration config to ensure proper rendering. The panel registration uses `fractionOfScreen = 0.4f`, `height = 0.75f * basePanelHeightMeters`, and `width = 0.6f * basePanelHeightMeters`.

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
4. Clear stored connection parameters (`currentConnectionParams = null`)

**Background Execution**: Runs on executor thread

#### `getCurrentConnectionParams()`

**Purpose**: Retrieve stored connection parameters (host, port, appId) for resume recovery.

**Returns**: `Triple<String, Int, Int>?` - Connection parameters or null if no active connection

**Usage**: Called by `ImmersiveActivity` lifecycle methods (`onVRPause`, `onHMDUnmounted`) to store connection state before sleep/pause.

**Storage**: Connection parameters are stored in `currentConnectionParams` when `startStream()` is called.

#### `checkAndRestartVideoStreamIfNeeded()`

**Purpose**: Recover video stream after sleep/wake cycle if it died during sleep.

**Flow**:

1. Check if connection parameters and preferences are stored
2. Always perform full stream restart (not just decoder restart)
3. Stop current connection if it exists
4. Restart stream with stored parameters (host, port, appId, prefs)

**Recovery Strategy**:

- **Full Stream Restart**: Always restarts entire stream, not just decoder
- **Rationale**: Video stream path in connection may be dead even if connection object appears active
- **Ensures**: Both video and audio paths are re-established after sleep/wake

**Background Execution**: Runs on executor thread

**Usage**: Called by `ImmersiveActivity.onVRReady()` / `onHMDMounted()` when connection is still active but video stream may have died.

**Implementation**:

```kotlin
fun checkAndRestartVideoStreamIfNeeded() {
    executor.execute {
        val params = currentConnectionParams
        val prefs = currentPrefs
        if (params == null || prefs == null) {
            return@execute
        }
        
        val (host, port, appId) = params
        
        // Always restart entire stream after sleep/wake cycle
        connection?.stop()
        connection = null
        isConnected = false
        
        Thread.sleep(200) // Ensure connection is fully stopped
        
        startStream(host, port, appId, prefs)
    }
}
```

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

## VIDEO PANEL SCALING

### Corner-Based Scaling System

**Purpose**: Allow users to resize the video panel by grabbing corner handles and dragging.

**Implementation**:

- **System**: `TouchScalableSystem` (from PremiumMediaSample pattern)
- **Components**: `Scalable`, `ScaledParent` (custom ECS components)
- **Dependencies**: `PointerInfoSystem` for hover detection, `ImageBoxEntity` for corner handles

**Key Features**:

- **Corner Handles**: Four corner handle entities appear when user hovers over panel
- **Proportional Scaling**: Corner handles scale proportionally with panel (maintains visibility at all sizes)
- **Position Locking**: Panel position and rotation are locked during scaling to prevent unwanted movement
- **Axis Restriction**: Only X and Y axes are scaled; Z axis remains at 1.0 to prevent depth changes
- **Scale Range**: 0.5x to 10.0x (configurable)
- **Auto-Hide**: Corner handles automatically hide after 1.5 seconds of inactivity

**User Interaction**:

1. User hovers controller/hand over video panel
2. Corner handles appear at panel corners
3. User presses trigger while pointing at a corner handle
4. User drags corner handle in/out to scale panel
5. Panel scales proportionally while position and rotation remain locked
6. Corner handles hide automatically after inactivity

**Component Registration** (in `onCreate()`):

```kotlin
// Register scaling components
componentManager.registerComponent<Scalable>(Scalable.Companion)
componentManager.registerComponent<ScaledParent>(ScaledParent.Companion)

// Register pointer info system (required for hover detection)
val pointerInfoSystem = PointerInfoSystem()
systemManager.registerSystem(pointerInfoSystem)

// Register touch scalable system
systemManager.registerSystem(TouchScalableSystem(minScale = 0.5f, maxScale = 10.0f))
```

**Entity Registration** (in `createVideoPanelEntity()`):

```kotlin
// Entity created with Scalable and ScaledParent components
videoPanelEntity = Entity.create(
    listOf(
        Panel(R.id.ui_example),
        // ... other components ...
        Scalable(), // Enable corner scaling
        ScaledParent(), // Mark as scalable parent
    )
)

// Register entity with scaling system
val touchScalableSystem = systemManager.findSystem<TouchScalableSystem>()
touchScalableSystem?.registerEntity(videoPanelEntity!!)
```

**Lifecycle Management**:

- Entity registered with `TouchScalableSystem` when created
- Entity unregistered on disconnect and shutdown
- Entity re-registered when reconnecting (if entity still exists)
- Locked positions/rotations cleared when scaling ends

**Note**: ButtonShelf also appears when hovering over the video panel, providing quick access to Settings, Reset Scale, and Disconnect controls. See [ButtonShelf Controls](#buttonshelf-controls) section for details.

**Files**:

- `systems/scalable/TouchScalableSystem.kt` - Main scaling system
- `systems/pointerInfo/PointerInfoSystem.kt` - Hover detection
- `entities/ImageBoxEntity.kt` - Corner handle entity creation
- `components/Scalable.xml` - Scalable component definition
- `components/ScaledParent.xml` - ScaledParent component definition
- `res/drawable/corner_round.png` - Corner handle visual

---

## BUTTONSHELF CONTROLS

### ButtonShelf Overview

**Purpose**: Provide quick access controls at the bottom of the video panel for common operations during streaming.

**Implementation**:

- **Entity**: `ButtonShelfEntity` - Manages ButtonShelf entity lifecycle and positioning
- **System**: `ButtonShelfVisibilitySystem` - Manages visibility based on hover, inactivity, grabbing, and scaling
- **UI**: `ButtonShelfCompose` - Compose UI with five interactive buttons
- **Component**: `ScaledChild` - Ensures ButtonShelf scales and positions correctly with video panel

**Key Features**:

- **Hover-Activated**: Appears when user hovers over video panel (similar to scaling handles)
- **Auto-Hide**: Automatically hides after 1.5 seconds of inactivity
- **Smart Hiding**: Hides when panel is being scaled or grabbed
- **Scaling-Aware**: Uses `ScaledChild` component to maintain correct position relative to video panel regardless of scale
- **Parented to Video Panel**: Always positioned below video panel, even when panel is scaled

**Button Functions**:

1. **Settings Button** (Settings icon):
   - Opens `PancakeActivity` as an overlay panel within the immersive scene
   - Allows users to adjust stream settings with a working keyboard while staying in VR
   - Uses `FLAG_ACTIVITY_NEW_TASK` to launch as overlay (immersive activity continues in background)
   - Provides access to:
     - Stream configuration (resolution, FPS, bitrate, codec)
     - Pairing management (pair new server, reset client pairing)
     - Server information
   - **Note**: This is a workaround for virtual keyboard issues in immersive mode - the 2D panel overlay has a reliable system keyboard

2. **Reset Scale Button** (Zoom icon):
   - Resets video panel scale to default size (1.0x)
   - Useful after scaling panel to large or small sizes
   - Instantly restores panel to original dimensions

3. **Spatialize Button** (VolumeOn icon):
   - Toggles spatial audio and room mesh visualization
   - When enabled: Audio appears to emanate from video panel position
   - Requires `USE_SCENE` permission for MRUK room detection
   - See immersive features section for details

4. **Snap to Wall Button** (SidebarPin icon):
   - Toggles wall-constrained panel movement
   - When enabled: Panel snaps to nearest wall on grab
   - Movement constrained to wall plane (X/Y sliding, Z locked)
   - Scaling handles also respect wall plane constraint
   - See [Snap to Wall](#snap-to-wall) section for details

5. **Disconnect Button** (Close icon):
   - Ends the current streaming session
   - Calls `disconnect()` to stop stream and reset connection state
   - Calls `launchPanelModeInHome()` to exit immersive mode and return to 2D panel in Home environment
   - Uses Meta's hybrid app pattern for seamless transition back to Home
   - User can reconnect from the 2D panel after disconnect

**User Interaction**:

1. User hovers controller/hand over video panel
2. ButtonShelf appears at the bottom of the video panel
3. User can interact with any of the five buttons
4. ButtonShelf hides automatically after inactivity or when panel is scaled/grabbed

**Focus Management**:

- ButtonShelf clears focus after each button click using `LocalFocusManager.clearFocus()`
- This prevents Compose panel from capturing Bluetooth controller input after button interactions
- Ensures controller input continues to flow to the game stream after using ButtonShelf

**Component Registration** (in `onCreate()`):

```kotlin
// Register scaling components (required for ButtonShelf)
componentManager.registerComponent<Scalable>(Scalable.Companion)
componentManager.registerComponent<ScaledParent>(ScaledParent.Companion)
componentManager.registerComponent<ScaledChild>(ScaledChild.Companion)

// Register ScaleChildrenSystem (required for ButtonShelf scaling)
systemManager.registerLateSystem(ScaleChildrenSystem())
```

**Entity Creation** (in `onSceneReady()`):

```kotlin
// Create ButtonShelf entity
buttonShelfEntity = ButtonShelfEntity()

// Attach to video panel when it exists
videoPanelEntity?.let { videoEntity ->
    buttonShelfEntity?.attachToEntity(videoEntity)
    
    // Force update children to ensure ButtonShelf is positioned correctly
    val scaleChildrenSystem = systemManager.findSystem<ScaleChildrenSystem>()
    scaleChildrenSystem?.forceUpdateChildren(videoEntity)
    
    // Create and register visibility system
    buttonShelfVisibilitySystem = ButtonShelfVisibilitySystem(
        buttonShelf = buttonShelfEntity!!,
        videoPanelEntity = videoEntity
    )
    systemManager.registerSystem(buttonShelfVisibilitySystem!!)
    buttonShelfVisibilitySystem?.startTracking()
}
```

**ButtonShelfEntity** (`entities/ButtonShelfEntity.kt`):

- **Dimensions**: 0.5m width × 0.12m height
- **Positioning**: Positioned below video panel using `ScaledChild` component
- **Components**: `Panel(R.id.button_shelf)`, `TransformParent`, `ScaledChild`, `Scale(1f)`, `Visible(false)`
- **Attachment**: Attaches to video panel entity via `TransformParent` and `ScaledChild`
- **Scaling**: Uses `ScaledChild` with `localPosition` set to shelf offset and `pivotOffset = Vector3(0f, 0f, 0f)`

**ButtonShelfVisibilitySystem** (`systems/buttonShelfVisibility/ButtonShelfVisibilitySystem.kt`):

- **Purpose**: Manages ButtonShelf visibility based on multiple conditions
- **Visibility Rules**:
  - Shows when user hovers over video panel
  - Hides after 1.5 seconds of inactivity
  - Hides when video panel is being scaled
  - Hides when video panel is grabbed
- **Tracking**: Monitors pointer hover state, inactivity timer, scaling state, and grab state

**Panel Registration** (in `registerPanels()`):

```kotlin
PanelRegistration(R.id.button_shelf) {
    config {
        fractionOfScreen = 0.3f
        height = 0.12f
        width = 0.9f  // Wider to accommodate 5 buttons
        layoutDpi = 240
        layerConfig = LayerConfig()
        enableTransparent = true
        includeGlass = false
        themeResourceId = R.style.PanelAppThemeTransparent
    }
    composePanel { setContent {
        val spatializeEnabled = isSpatializeEnabled.collectAsState()
        val snapEnabled = isSnapEnabled.collectAsState()
        
        ButtonShelfCompose(
            isSpatializeEnabled = spatializeEnabled.value,
            isSnapEnabled = snapEnabled.value,
            onSettingsClick = { startPanelActivityInOverlay() },
            onResetScaleClick = { updateVideoPanelScale(1.0f) },
            onSpatializeClick = { toggleSpatialize() },
            onSnapToWallClick = { toggleSnapToWall() },
            onDisconnectClick = { 
                disconnect()
                launchPanelModeInHome()
            }
        )
    }}
}
```

**Lifecycle Management**:

- ButtonShelf entity created in `onSceneReady()`
- Attached to video panel when video panel is created
- Visibility managed by `ButtonShelfVisibilitySystem`
- Re-attached and re-registered when video panel is recreated after disconnect

**Files**:

- `entities/ButtonShelfEntity.kt` - ButtonShelf entity management
- `systems/buttonShelfVisibility/ButtonShelfVisibilitySystem.kt` - Visibility management system
- `panels/buttonShelf/ButtonShelfCompose.kt` - Compose UI for buttons
- `panels/buttonShelf/ButtonShelfActivity.kt` - Activity wrapper (legacy, not used in primary registration)
- `components/ScaledChild.xml` - ScaledChild component definition (for scaling with parent)
- `systems/scaleChildren/ScaleChildrenSystem.kt` - System that updates child positions when parent scales

---

## SNAP TO WALL

### Overview

**Purpose**: Allow users to snap the video panel to a wall and move it along the wall surface while keeping the distance from the wall locked.

**Implementation**:

- **Component**: `WallSnap` - ECS component storing wall plane data and snap state
- **System**: `AnchorSnappingSystem` - Handles wall detection and constrained movement
- **Integration**: `TouchScalableSystem` - Corner handles respect wall plane constraint

**Key Features**:

- **Toggle-Based**: Enabled/disabled via ButtonShelf "Snap" button
- **Raycast Wall Detection**: On first grab, finds nearest wall via raycast from head through panel
- **Constrained Movement**: While grabbed, panel slides along wall (X/Y free, Z locked)
- **Wall-Facing Rotation**: Panel automatically rotates to face outward from wall
- **Scaling Respects Wall**: Corner scaling handles are also projected onto wall plane

### WallSnap Component

**File**: `components/WallSnap.xml`

**Attributes**:

- `isEnabled` (Boolean): Whether wall snap mode is active
- `isSnappedToWall` (Boolean): Whether currently snapped to a wall
- `wallPlaneNormal` (Vector3): Normal vector of the wall plane
- `wallPlanePoint` (Vector3): A point on the wall plane
- `wallOffset` (Float): Distance offset from wall (default: 0.02m / 2cm)

**Component Definition**:

```xml
<ComponentSchema packageName="com.example.moonlight_spatialsdk">
  <Component name="WallSnap">
    <BooleanAttribute name="isEnabled" defaultValue="false" />
    <BooleanAttribute name="isSnappedToWall" defaultValue="false" />
    <Vector3Attribute name="wallPlaneNormal" defaultValue="0f, 0f, 1f" />
    <Vector3Attribute name="wallPlanePoint" defaultValue="0f, 0f, 0f" />
    <FloatAttribute name="wallOffset" defaultValue="0.02f" />
  </Component>
</ComponentSchema>
```

### Toggle Function

**File**: `ImmersiveActivity.kt`

**Function**: `toggleSnapToWall()`

```kotlin
fun toggleSnapToWall() {
    val newEnabled = !_isSnapEnabled.value
    _isSnapEnabled.value = newEnabled
    
    videoPanelEntity?.let { entity ->
        if (newEnabled) {
            // Add WallSnap component to enable wall-constrained movement
            entity.setComponent(WallSnap(
                isEnabled = true,
                isSnappedToWall = false,
                wallPlaneNormal = Vector3(0f, 0f, 1f),
                wallPlanePoint = Vector3(0f, 0f, 0f),
                wallOffset = 0.02f
            ))
        } else {
            // Disable WallSnap by setting isEnabled to false
            if (entity.hasComponent<WallSnap>()) {
                val wallSnap = entity.getComponent<WallSnap>()
                wallSnap.isEnabled = false
                wallSnap.isSnappedToWall = false
                entity.setComponent(wallSnap)
            }
        }
    }
}
```

### AnchorSnappingSystem Enhancement

**File**: `systems/anchor/AnchorSnappingSystem.kt`

**WallSnap Processing** (in `execute()`):

```kotlin
// Process WallSnap entities (wall-plane-constrained movement)
val wallSnapEntities = Query.where { has(WallSnap.id, Transform.id, Grabbable.id) }.eval()
for (entity in wallSnapEntities) {
    val wallSnap = entity.getComponent<WallSnap>()
    val grabbable = entity.getComponent<Grabbable>()
    
    if (!wallSnap.isEnabled) continue
    
    if (grabbable.isGrabbed) {
        processWallSnapGrabbed(entity, wallSnap, planes)
    } else if (wallSnap.isSnappedToWall) {
        // Released - clear the snapped state for next grab
        wallSnap.isSnappedToWall = false
        entity.setComponent(wallSnap)
    }
}
```

**Key Methods**:

- `processWallSnapGrabbed()`: On first grab, finds nearest wall and stores plane data; while grabbed, projects position onto wall plane
- `findNearestWall()`: Raycasts from head through entity to find nearest wall, falls back to proximity search
- `calculateWallFacingRotation()`: Computes rotation to face outward from wall
- `projectPointOntoPlane()`: Projects a point onto the stored wall plane

### TouchScalableSystem Integration

**File**: `systems/scalable/TouchScalableSystem.kt`

**Wall-Constrained Corner Handles**:

When the video panel is snapped to a wall, corner scaling handles are also projected onto the wall plane to prevent them from "jumping around" in 3D space.

```kotlin
// In updatePanel()
val wallSnap = entity.tryGetComponent<WallSnap>()
val isWallSnapped = wallSnap != null && wallSnap.isEnabled && wallSnap.isSnappedToWall

corners.forEachIndexed { index, corner ->
    var cornerPos = pose.t + offsets[index]
    
    // Constrain corner to wall plane if panel is wall-snapped
    if (isWallSnapped) {
        cornerPos = projectPointOntoWallPlane(cornerPos, wallSnap!!) + 
            wallSnap.wallPlaneNormal * wallSnap.wallOffset
    }
    
    corner.setComponent(Transform(Pose(cornerPos, pose.q.times(cornerRotations[index]))))
}
```

### User Interaction Flow

1. User clicks "Snap" button on ButtonShelf → `toggleSnapToWall()` called
2. WallSnap component added to video panel with `isEnabled = true`
3. User grabs video panel with Meta controller
4. `AnchorSnappingSystem` detects grab, raycasts to find nearest wall
5. Panel instantly snaps to wall, stores plane data in WallSnap component
6. While grabbed, panel slides along wall (X/Y movement allowed, Z locked)
7. Panel rotation locked to face outward from wall
8. User releases panel → `isSnappedToWall` cleared, ready for next grab
9. If scaled while snapped, corner handles stay on wall plane

### Files

- `components/WallSnap.xml` - WallSnap component definition
- `systems/anchor/AnchorSnappingSystem.kt` - Wall detection and constrained movement
- `systems/scalable/TouchScalableSystem.kt` - Corner handle wall projection
- `ImmersiveActivity.kt` - Toggle function and component registration

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

5. **User clicks Server Card** (connection panel):
   - If paired: Hide connection panel (`Visible(false)`)
   - Call `connectToHost(host, port, appId)`
   - If not paired: Opens pairing dialog or initiates pairing flow

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

## ENTITY CREATION REDUNDANCY

### Problem (Resolved)

The video panel entity was previously created twice - once by the SDK during panel registration, and again manually after registration. This caused entity duplication and dimension desync issues, particularly in stereoscopic mode.

### Solution Implemented

✅ **Resolved**: The implementation now uses SDK-provided entities exclusively:

1. **Stereoscopic Depth Mode** (`PanelCreator`):
   - SDK creates entity in `panelCreator` lambda with correct ultrawide dimensions (5120x1440p for 2560x1440p user resolution)
   - Entity stored in `videoPanelEntity` and used directly
   - No manual entity creation overwrites the SDK-provided entity

2. **Lighting Emission Mode** (`ReadableVideoSurfacePanelRegistration`):
   - SDK provides `panelEntity` in `surfaceConsumer` callback
   - Entity stored in `videoPanelEntity` and used directly
   - No duplicate entity creation

3. **Standard Mode** (`VideoSurfacePanelRegistration`):
   - SDK provides `panelEntity` in `surfaceConsumer` callback
   - Entity stored in `videoPanelEntity` and used directly
   - No duplicate entity creation

**Implementation Pattern**:

- SDK-provided entity from callbacks is stored in `videoPanelEntity`
- Additional components are added to the SDK-provided entity (no new entity created)
- Fallback entity creation only occurs if all SDK registration attempts fail
- Retry logic ensures SDK callbacks execute and provide entities

**Status**: ✅ Resolved - All three registration modes now use SDK-provided entities correctly

---

## IMMERSIVEACTIVITY COMPREHENSIVE BREAKDOWN

### Lifecycle Flow

**1. `onCreate()` (Lines 229-340)**

**Purpose**: Initialize all components, systems, and connection managers before scene is ready.

**Execution Order**:

1. **MediaCodecHelper Initialization** (Lines 238-241): Initializes decoder helper with Quest 3's Adreno 740 GPU identifier. Must be called BEFORE creating decoder renderer.
2. **Decoder/Audio Renderer Creation** (Lines 245-251): Creates `MoonlightPanelRenderer`, `AndroidAudioRenderer`, and `MoonlightPairingHelper`.
3. **Component Registration** (Lines 254-265): Registers custom ECS components (`Scalable`, `ScaledParent`, `ScaledChild`, `Anchorable`, `AnchorOnLoad`, `WallSnap`, `HeroLighting`, `ReceiveLighting`).
4. **System Registration** (Lines 268-286): Registers systems (`HeroLightingSystem`, `WallLightingSystem`, `PointerInfoSystem`, `TouchScalableSystem`, `AnchorSnappingSystem`).
5. **Connection Manager Creation** (Lines 288-318): Creates `MoonlightConnectionManager` with status update callback that shows video panel when stream is ready.
6. **Network Asset Loader** (Lines 320-323): Initializes networked asset loading.
7. **Connection Params** (Lines 325-339): Reads connection params from Intent extras, stores in `pendingConnectionParams` (does NOT connect yet).

**Key Point**: No panel entities are created in `onCreate()`. All panel creation happens in `onSceneReady()` or via SDK registration.

**2. `registerPanels()` (Lines 425-588)**

**Purpose**: Return list of panel registrations to SDK. Called by SDK BEFORE `onSceneReady()`.

**Panels Registered**:

1. **Connection Panel** (`R.id.connection_panel`, Lines 434-468): Compose UI panel for connection management. SDK creates entity automatically when registration is processed.
2. **Disconnect Dialog Panel** (`R.id.disconnect_dialog_panel`, Lines 470-505): Compose UI dialog for disconnect confirmation. Entity created manually on menu button press.
3. **Button Shelf Panel** (`R.id.button_shelf`, Lines 507-549): Compose UI panel for video panel controls. Entity created manually in `createButtonShelfEntity()`.
4. **Stereo Depth Slider Panel** (`R.id.stereo_depth_slider`, Lines 551-586): Compose UI panel for depth control. Entity created manually in `StereoDepthSliderEntity`.

**Key Point**: Video panel (`R.id.ui_example`) is NOT registered here. It's registered dynamically in `createVideoPanelEntity()` using `executeOnVrActivity`.

**3. `onSceneReady()` (Lines 368-421)**

**Purpose**: Configure scene and create panel entities after Spatial SDK is fully initialized.

**Execution Order**:

1. **Scene Configuration** (Lines 374-387): Disable locomotion, enable passthrough, set lighting environment, initialize `LightingPassthroughHandler`.
2. **Panel Positioning System** (Lines 393-395): Register `PanelPositioningSystem` for panel placement.
3. **PanelManager Creation** (Lines 401-405): Create `PanelManager` entity (root for all panels) and set on positioning system.
4. **Video Panel Creation** (Line 407): Call `createVideoPanelEntity()` - **CRITICAL**: This registers panel dynamically using `executeOnVrActivity`.
5. **Connection Panel Query** (Line 410): Call `queryAndHideConnectionPanel()` - **CRITICAL**: Queries for SDK-created connection panel entity and hides it.
6. **Button Shelf Creation** (Line 411): Call `createButtonShelfEntity()` - Creates ButtonShelf entity (may not attach if video panel not ready).

**Key Point**: All panel creation happens here, but entities may not be immediately available due to async SDK callbacks.

### Panel Registration Flow

**Connection Panel Registration**:

1. **SDK Registration** (Line 434): `PanelRegistration(R.id.connection_panel)` returned in `registerPanels()`.
2. **SDK Entity Creation**: SDK creates entity automatically when registration is processed (timing is async).
3. **Query for Entity** (Line 410, Lines 1552-1567): `queryAndHideConnectionPanel()` queries for entity with `Panel` component and `panelRegistrationId == R.id.connection_panel`.
4. **Hide Entity** (Line 1562): If found, sets `Visible(false)` to hide it (PancakeActivity handles connection UI).

**Video Panel Registration**:

1. **Dynamic Registration** (Line 407, Lines 1337-1539): `createVideoPanelEntity()` calls `SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->`.
2. **Registration Callback** (Line 1337): `executeOnVrActivity` callback may execute immediately or be delayed (async).
3. **Panel Registration** (Lines 1355, 1439, or 1492): Inside callback, calls `immersiveActivity.registerPanel()` with one of:
   - `PanelCreator` (stereoscopic mode, Line 1355)
   - `ReadableVideoSurfacePanelRegistration` (lighting emission mode, Line 1439)
   - `VideoSurfacePanelRegistration` (standard mode, Line 1492)
4. **Entity Callback** (Lines 1358, 1442, or 1495): SDK calls registration callback (`panelCreator` lambda or `surfaceConsumer`) with entity.
5. **Entity Storage** (Lines 1360, 1446, or 1499): `videoPanelEntity = entity` is set inside callback.
6. **Component Addition** (Lines 1393, 1448, or 1501): `addVideoPanelComponents()` adds all required components, including `Visible(false)`.

**Key Point**: `videoPanelEntity` is set when SDK callbacks execute. Retry logic with exponential backoff ensures callbacks execute, and entity verification/polling provides fallback if callbacks are delayed.

### Entity Creation Flow

**Video Panel Entity**:

- **Created By**: SDK automatically when `registerPanel()` is called inside `executeOnVrActivity` callback.
- **Timing**: Async - depends on `executeOnVrActivity` callback execution and registration callback execution.
- **Retry Logic**: Up to 3 registration attempts with exponential backoff (100ms, 200ms, 400ms delays).
- **Entity Verification**: Polls for entity creation via Query if SDK callback doesn't set it (10 attempts, 50ms delay).
- **Fallback**: Creates minimal fallback entity if all retries fail, ensuring at least one panel is visible.
- **Visibility**: Visible on launch if no pending connection params exist, hidden if connection is pending.
- **Reference**: Stored in `videoPanelEntity` when registration callback executes or via polling/fallback.

**Button Shelf Entity**:

- **Created By**: Manual creation in `createButtonShelfEntity()`.
- **Timing**: Synchronous - created immediately in `onSceneReady()`.
- **Attachment**: Depends on `videoPanelEntity` existing - if null, attachment is deferred until video panel is ready.
- **Visibility**: Managed by `ButtonShelfVisibilitySystem` (hover-activated).

### Async Callback Handling

**Robust Registration**:

1. **Retry Logic**: Registration attempts retry up to 3 times with exponential backoff if `videoPanelEntity` is not set.
2. **Entity Verification**: After each registration attempt, polls for entity creation via Query (10 attempts, 50ms delay).
3. **Fallback Entity**: If all retries and polling fail, creates minimal fallback entity to ensure panel visibility.
4. **Visibility Management**: Video panel is visible on launch if no pending connection params exist, ensuring user sees something immediately.

**Synchronization**:

- ✅ Retry logic handles delayed callbacks
- ✅ Entity verification provides fallback if callbacks don't set entity
- ✅ Fallback entity creation ensures panel appears even if SDK registration fails
- ✅ Visibility logic ensures at least one panel is visible on launch

### Panel Visibility State

**Initial State**:

- **Video Panel**: Visible on launch if no pending connection params exist, hidden if connection is pending.
- **Button Shelf**: Hidden by default (visibility managed by `ButtonShelfVisibilitySystem`).
- **Disconnect Dialog**: Not created until menu button is pressed.

**Visibility Transitions**:

- **Video Panel**: Shown on launch (if no pending connection) or when stream is ready (if connection pending).
- **Button Shelf**: Shown on video panel hover (managed by `ButtonShelfVisibilitySystem`).

**Key Point**: Video panel is now reliably visible on launch due to retry logic, entity verification, and fallback mechanisms. Panels appear even if SDK callbacks are delayed.

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
- Corner-based scaling system (`TouchScalableSystem`) with proportional corner handles
- ButtonShelf controls (Settings, Reset Scale, Spatialize, Snap, Disconnect) with hover-activated visibility
- ButtonShelf focus management (clears focus after button clicks to prevent controller input capture)
- ScaledChild component and ScaleChildrenSystem for hierarchical scaling (ButtonShelf scales with video panel)
- Snap to Wall feature (`WallSnap` component, wall-constrained movement with X/Y sliding and Z locked)
- Wall-aware scaling (corner handles respect wall plane when panel is wall-snapped)
- zIndex configuration for rectilinear panels
- Sleep/wake cycle video stream recovery (automatic re-establishment after device sleep)

**⚠️ Limitations**:

- Immersive-only (no 2D video display)
- No analog stick-based scaling (`AnalogScalableSystem`)
- Known SDK issue: Video surface color space initialization (affects PremiumMediaSample too)
  - Colors may be incorrect on first frame
  - Resolves after device sleep/wake cycle (which also triggers video stream recovery)
  - See `POST_MORTEM.md` for details

### Critical Issues

**Status**: ✅ All critical issues resolved. The app now reliably displays panels on launch with robust retry logic and fallback mechanisms.

### Future Enhancements

**Phase 1: MRUK Integration**:

- Add `MRUKFeature` to `ImmersiveActivity`
- Request `USE_SCENE` permission
- Load MRUK scene on activity start
- Add anchoring components to video panel entity
- Support wall/ceiling/floor detection and anchoring

**Phase 2: Advanced Scaling Systems**:

- ✅ **Completed**: `TouchScalableSystem` for corner-based scaling
  - Corner handles appear on hover
  - Grab corner with trigger to scale panel
  - Scale range: 0.5x to 10.0x
  - Uniform scaling (all axes scale together)
  - Corner handles scale proportionally with panel
- ✅ **Completed**: `ScaledChild` component and `ScaleChildrenSystem` for hierarchical scaling
  - ButtonShelf uses `ScaledChild` to scale and position correctly with video panel
  - Child entities maintain relative position when parent scales
- ✅ **Completed**: ButtonShelf controls with hover-activated visibility
  - Settings button toggles connection panel
  - Reset Scale button resets panel to 1.0x
  - Disconnect button ends stream and returns to connection panel

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
- `entities/ButtonShelfEntity.kt` - ButtonShelf entity management
- `systems/buttonShelfVisibility/ButtonShelfVisibilitySystem.kt` - ButtonShelf visibility management
- `systems/scaleChildren/ScaleChildrenSystem.kt` - Hierarchical scaling system for child entities
- `systems/anchor/AnchorSnappingSystem.kt` - Wall detection and snap-to-wall constrained movement
- `panels/buttonShelf/ButtonShelfCompose.kt` - ButtonShelf Compose UI
- `components/WallSnap.xml` - WallSnap component for wall-constrained movement

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
- ✅ Robust panel registration with retry logic, entity verification, and fallback mechanisms
- ✅ Panel visibility management (video panel visible on launch if no pending connection)
- ✅ PIN pairing system for secure first-time connections (client-generated PIN)
- ✅ Network security configuration for pairing compatibility
- ✅ Background thread execution prevents ANR
- ✅ Passthrough mode for mixed reality experience
- ✅ Panel scaling support with `Scale` component
- ✅ Corner-based scaling system (`TouchScalableSystem`) with proportional handles
- ✅ ButtonShelf controls (5 buttons) with hover-activated visibility and hierarchical scaling support
- ✅ Snap to Wall feature with wall-constrained movement (X/Y sliding, Z locked)
- ✅ Wall-aware scaling handles that respect wall plane constraint

**Architecture Alignment**:

- ✅ Follows Meta Spatial SDK immersive app pattern
- ✅ Uses `executeOnVrActivity` for dynamic panel registration (PremiumMediaSample pattern)
- ✅ PanelManager pattern for unified panel management
- 🔄 Ready for PremiumMediaSample-style enhancements (MRUK, advanced scaling, lighting)

**Known Issues**:

- ⚠️ Video surface color space initialization issue (affects PremiumMediaSample too)
  - Colors may be incorrect on first frame
  - Resolves after device sleep/wake cycle (which also triggers video stream recovery)
  - See `POST_MORTEM.md` for details

**Note**: Video stream recovery after sleep/wake cycles is implemented. The app automatically detects when the video stream has died during sleep and re-establishes it on resume, ensuring seamless streaming experience even after long sleep periods.

The core streaming functionality is working, with pairing support and proper lifecycle management. The app launches directly into immersive mode with connection UI, transitioning to video streaming once connected. The next phase is to add MRUK features and advanced scaling systems.
