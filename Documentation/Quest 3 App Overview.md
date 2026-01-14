# Quest 3 App Overview

## Overview

The Moonlight-SpatialSDK Quest 3 application is a hybrid VR streaming client that brings PC game streaming to Meta Quest 3 using the Moonlight protocol and Meta Spatial SDK. The app launches in 2D panel mode for connection setup and keyboard input, then seamlessly transitions to immersive VR mode for mixed reality streaming with advanced spatial features including spatial audio, room integration, and interactive controls.

## What You'll Learn

- How the hybrid app architecture leverages both 2D and VR modes
- The connection flow from pairing to streaming
- Video panel rendering with multiple modes (standard, lighting)
- Advanced VR features: spatial audio, room mesh integration (MRUK)
- User controls: panel scaling, snap-to-wall, and interactive button shelf
- Input forwarding from Quest controllers to the PC
- Stream lifecycle management and recovery after sleep/wake cycles

## Architecture

### Application Flow

```
App Launch → PancakeActivity (2D Panel Mode)
              ↓
         Connection UI & Pairing
              ↓
    User Clicks "Connect to PC"
              ↓
     ImmersiveActivity (VR Mode)
              ↓
    Video Panel + Spatial Features
```

### Hybrid App Pattern

The application uses Meta's hybrid app pattern to work around virtual keyboard issues in immersive mode:

1. **Default Launcher**: PancakeActivity (2D panel mode) provides reliable text input
2. **VR Transition**: Once paired and connected, launches ImmersiveActivity
3. **Settings Access**: Settings button in VR opens PancakeActivity as overlay

**Why This Pattern?**

Meta Horizon OS has known issues with virtual keyboard positioning in immersive mode. The keyboard can fail to position properly, become invisible, or block UI interaction. By launching in 2D panel mode first, the app leverages the stable system keyboard in the Home environment for reliable text input during pairing and configuration.

### Key Components

| Component | Purpose | Location |
|-----------|---------|----------|
| **PancakeActivity** | 2D panel mode for connection UI and pairing | `PancakeActivity.kt` |
| **ImmersiveActivity** | VR mode for video streaming and spatial features | `ImmersiveActivity.kt` |
| **MoonlightConnectionManager** | Connection lifecycle and pairing management | `MoonlightConnectionManager.kt` |
| **MoonlightPanelRenderer** | Video decoder bridging to Spatial panel | `MoonlightPanelRenderer.kt` |
| **PanelManager** | Root entity for unified panel positioning | `PanelManager.kt` |
| **PanelPositioningSystem** | Positions panels in front of user | `PanelPositioningSystem.kt` |

## Getting Started

### Prerequisites

- Meta Quest 3 or Quest 3S
- PC running Moonlight-compatible server (Sunshine or GeForce Experience)
- Both devices on same network
- Permissions: INTERNET, USE_SCENE, USE_ANCHOR_API

### Launch Flow

1. **First Launch**: App opens PancakeActivity in 2D panel mode
2. **Enter Server Details**: Input host IP address and port (default 47989)
3. **Pairing**: If not paired, app generates 4-digit PIN to enter on PC
4. **Connect**: Click "Connect to PC" to launch ImmersiveActivity
5. **Stream Ready**: Video panel appears in VR with interactive controls

## Activities

### PancakeActivity (2D Launcher)

**Purpose**: Default launcher providing connection UI, pairing, and stream configuration.

**Key Features**:

- Card-based connection UI matching immersive design
- Server connection cards showing host/IP and connection options
- Pairing dialog with PIN generation and entry
- Stream configuration (resolution, FPS, bitrate, codec)
- Application selection dropdown for launching specific games
- Working system keyboard for reliable text input
- Options dialog for pairing reset and advanced settings

**Launch Configuration**:

```xml
<activity android:name=".PancakeActivity" android:exported="true">
  <layout android:defaultHeight="550dp" android:defaultWidth="800dp" />
  <intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
    <category android:name="com.oculus.intent.category.2D" />
  </intent-filter>
</activity>
```

**Connection Flow**:

1. User enters host/port or selects saved connection
2. App checks pairing status via `checkPairing()`
3. If not paired, generates PIN and calls `pairWithServer()`
4. On successful pairing, launches ImmersiveActivity with connection params
5. ImmersiveActivity receives host, port, and appId via Intent extras

### ImmersiveActivity (VR Mode)

**Purpose**: Immersive VR activity for video streaming with spatial features.

**Launch Configuration**:

```xml
<activity
  android:name="com.example.moonlight_spatialsdk.ImmersiveActivity"
  android:launchMode="singleTask"
  android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="com.oculus.intent.category.VR" />
  </intent-filter>
</activity>
```

**Key Features**:

- Video panel with two rendering modes (standard, lighting)
- ButtonShelf controls for quick access to features
- Corner-based panel scaling with hover-activated handles
- Snap-to-wall feature for wall-constrained movement
- Spatial audio from video panel position
- Room mesh integration (MRUK) for spatial awareness
- Stereoscopic 3D with runtime depth control
- Bias lighting for ambient glow effect
- Controller input forwarding to PC
- Sleep/wake recovery for seamless streaming

**Lifecycle Overview**:

```kotlin
onCreate()
  → Initialize decoder, audio, connection manager
  → Register ECS components and systems
  → Read connection params from Intent

registerFeatures()
  → VRFeature, ComposeFeature, MRUKFeature, SpatialAudioFeature

onSceneReady()
  → Configure scene (passthrough, lighting)
  → Create PanelManager entity
  → Create video panel entity (dynamic registration)
  → Query and hide connection panel
  → Create ButtonShelf entity

registerPanels()
  → Disconnect dialog panel
  → Button shelf panel
  → Stereo depth slider panel
  → (Video panel registered dynamically in createVideoPanelEntity)
```

## Core Systems

### Panel Management

**PanelManager**

The PanelManager creates a root entity that serves as the parent for all panel entities, enabling unified positioning:

```kotlin
class PanelManager {
    fun create(): Entity {
        return Entity.create(
            Transform(),
            Visible(true),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y)
        )
    }
}
```

**PanelPositioningSystem**

Positions the PanelManager entity in front of the user's head at a comfortable viewing distance (default 1.0m) with eye-level offset. Executes each frame until positioned, with retry logic if head tracking isn't ready.

**Panel Hierarchy**:

```
PanelManager (root entity)
├── Video Panel Entity
│   ├── ButtonShelf Entity (ScaledChild)
│   ├── Stereo Depth Slider Entity (ScaledChild)
│   └── Bias Lighting Entity (ScaledChild)
└── (Other panels as needed)
```

### Connection Management

**MoonlightConnectionManager**

Manages the complete connection lifecycle:

**Key Methods**:

| Method | Purpose | Thread |
|--------|---------|--------|
| `checkPairing()` | Check if server requires pairing | Background |
| `pairWithServer()` | Pair with server using PIN | Background |
| `startStream()` | Start streaming session | Background |
| `stopStream()` | Stop streaming and cleanup | Background |
| `getCurrentConnectionParams()` | Get connection params for recovery | Any |
| `checkAndRestartVideoStreamIfNeeded()` | Recover stream after sleep | Background |

**Background Execution**:

All network operations run on `Executors.newSingleThreadExecutor()` to prevent ANR (Application Not Responding) errors. Callbacks are posted to the main thread for UI updates.

**Pairing Flow**:

1. **Check Pairing**: `checkPairing()` queries server
2. **Generate PIN**: Client generates 4-digit PIN using `PairingManager.generatePinString()`
3. **User Enters PIN**: User enters generated PIN on PC (Sunshine/GFE)
4. **Establish Pairing**: `pairWithServer()` performs certificate exchange
5. **Persist Certificate**: Certificate cached for future connections

**Important**: The PIN is generated by the *client* (Quest app), not the server. Users must enter the displayed PIN on their PC.

**Network Security**:

```xml
<!-- network_security_config.xml -->
<network-security-config>
  <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

Android 9+ blocks cleartext traffic by default. This configuration allows HTTP for initial pairing handshake. After pairing, connections use HTTPS with certificate pinning.

### Video Rendering

**Three Panel Registration Modes**:

The video panel supports three distinct rendering modes based on `ImmersiveSettings`:

#### 1. Standard Mode

**Purpose**: Maximum performance direct-to-surface rendering

**Configuration**:

```kotlin
VideoSurfacePanelRegistration(
    R.id.ui_example,
    surfaceConsumer = { panelEntity, surface ->
        SurfaceUtil.paintBlack(surface)
        moonlightPanelRenderer.attachSurface(surface)
        moonlightPanelRenderer.preConfigureDecoder()
    },
    settingsCreator = {
        MediaPanelSettings(
            shape = QuadShapeOptions(width = panelWidth, height = panelHeight),
            display = PixelDisplayOptions(width = streamWidth, height = streamHeight),
            rendering = MediaPanelRenderOptions(
                isDRM = false,
                stereoMode = StereoMode.None,
                zIndex = 0
            )
        )
    }
)
```

**Use Case**: Default mode for standard game streaming

#### 2. Lighting Emission Mode

**Purpose**: Texture sampling for advanced lighting effects

**Configuration**:

```kotlin
ReadableVideoSurfacePanelRegistration(
    R.id.ui_example,
    surfaceConsumer = { panelEntity, surface ->
        SurfaceUtil.paintBlack(surface)
        moonlightPanelRenderer.attachSurface(surface)
        moonlightPanelRenderer.preConfigureDecoder()
    },
    settingsCreator = {
        ReadableMediaPanelSettings(
            shape = QuadShapeOptions(width = panelWidth, height = panelHeight),
            display = PixelDisplayOptions(width = streamWidth, height = streamHeight),
            rendering = ReadableMediaPanelRenderOptions(
                mips = 4, // For shader sampling
                stereoMode = StereoMode.None
            )
        )
    }
)
```

**Use Case**: Enables ambilight effects, hero lighting, and wall reflections

**Key Features**:

- `ReadableVideoSurfacePanelRegistration` allows texture sampling
- `mips = 4` enables blur for lighting effects
- `HeroLighting` component for emissive glow
- Wall lighting system for MRUK surface reflections

**Panel Dimension Calculation**:

All modes use `calculatePanelSize()` as single source of truth:

```kotlin
private fun calculatePanelSize(force16x9: Boolean = false, useDoubledWidth: Boolean = false): Vector2 {
    val aspect = if (force16x9) {
        16f / 9f
    } else if (prefs.height != 0) {
        val width = if (useDoubledWidth) prefs.width * 2 else prefs.width
        width.toFloat() / prefs.height.toFloat()
    } else {
        16f / 9f
    }
    return Vector2(aspect * basePanelHeightMeters, basePanelHeightMeters)
}
```

**Entity Creation Pattern**:

1. Register panel using `executeOnVrActivity` (lifecycle alignment)
2. Create entity manually AFTER registration
3. SDK calls `surfaceConsumer` callback with `panelEntity` and `surface`
4. Add components via `addVideoPanelComponents()` helper
5. Panel starts hidden, shown when stream is ready

### Input Handling

**Controller Input Forwarding**:

Quest controller inputs are forwarded to the PC using Moonlight's `ControllerHandler`:

```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (shouldForwardInputs && !allowControllerUIInput) {
        val inputDevice = event.device
        if (inputDevice != null &&
            (inputDevice.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
             inputDevice.supportsSource(InputDevice.SOURCE_JOYSTICK))) {
            return controllerHandler?.handleButtonEvent(event) ?: false
        }
    }
    return super.dispatchKeyEvent(event)
}

override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    if (shouldForwardInputs && !allowControllerUIInput) {
        val inputDevice = event.device
        if (inputDevice != null &&
            (inputDevice.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
             inputDevice.supportsSource(InputDevice.SOURCE_JOYSTICK))) {
            return controllerHandler?.handleMotionEvent(event) ?: false
        }
    }
    return super.dispatchGenericMotionEvent(event)
}
```

**Input Gate**: `shouldForwardInputs` flag prevents input forwarding when no connection exists, allowing UI navigation during setup.

## Features

### Video Panel Modes

**Mode Comparison**:

| Feature | Standard | Lighting Emission |
|---------|----------|-------------------|
| **Performance** | Highest | Medium |
| **Panel Type** | VideoSurfacePanelRegistration | ReadableVideoSurfacePanelRegistration |
| **Texture Sampling** | No | Yes (mips = 4) |
| **Custom Shaders** | No | Yes (lighting) |
| **Use Cases** | Default streaming | Ambilight, reflections |

<!-- REMOVED: 3D Desktop Client - Stereoscopic 3D section removed -->

### Spatial Audio

**SpatialAudioManager**:

Manages spatialized audio where sound emanates from the video panel's position in 3D space:

```kotlin
fun enableSpatialAudio(entity: Entity, androidAudioSessionId: Int, channelCount: Int = 2) {
    // Register Android audio session with Spatial Audio feature
    spatialAudioFeature.registerAudioSessionId(
        MOONLIGHT_AUDIO_SESSION_REGISTRATION_ID,
        androidAudioSessionId
    )

    // Attach AudioSessionId component to entity
    val audioType = when (channelCount) {
        1 -> AudioType.MONO
        2 -> AudioType.STEREO
        else -> AudioType.SOUNDFIELD
    }
    entity.setComponent(AudioSessionId(MOONLIGHT_AUDIO_SESSION_REGISTRATION_ID, audioType))
}
```

**Usage**: Toggle via ButtonShelf "Spatialize" button

**Benefits**:

- Audio appears to come from panel position
- Spatial awareness as user moves in room
- Enhanced immersion for streaming content

### Room Integration (MRUK)

**RoomMeshManager**:

Integrates with Meta's Mixed Reality Utility Kit (MRUK) for room understanding:

```kotlin
class RoomMeshManager(private val mrukFeature: MRUKFeature) {
    fun loadSceneFromDevice(
        onSceneLoaded: (() -> Unit)? = null,
        onSceneLoadFailed: ((MRUKLoadDeviceResult) -> Unit)? = null
    ) {
        mrukFeature.loadSceneFromDevice().whenComplete { result, exception ->
            if (result == MRUKLoadDeviceResult.SUCCESS) {
                initializeMeshColliders()
                onSceneLoaded?.invoke()
            }
        }
    }
}
```

**Features**:

- Wall, floor, and ceiling detection
- Procedural mesh generation for room surfaces
- Invisible collision geometry for raycasting
- Snap-to-wall anchoring support
- Wall lighting reflections (when lighting emission enabled)

**Permission Required**:

```xml
<uses-permission android:name="com.oculus.permission.USE_SCENE" />
<uses-permission android:name="com.oculus.permission.USE_ANCHOR_API" />
```

### Lighting Effects

**Hero Lighting System**:

Emissive lighting effect from video panel edges:

```kotlin
class HeroLightingSystem(
    autoDetectTexture: Boolean = true,
    isProcessingShaders: Boolean = true
) : System {
    // Processes entities with HeroLighting component
    // Emits light based on panel edge colors
}
```

**Wall Lighting System**:

Video reflections on MRUK room surfaces:

```kotlin
class WallLightingSystem : System {
    // Projects video texture onto walls, floor, ceiling
    // Requires ReadableVideoSurfacePanelRegistration
}
```

**Bias Lighting Entity**:

Ambient glow effect around panel edges (ambilight):

```kotlin
class BiasLightingEntity(
    private val videoPanelEntity: Entity,
    private val systemManager: SystemManager
) {
    // Creates ambient glow matching panel edge colors
    // Enhances perceived contrast and immersion
}
```

### User Controls

**ButtonShelf**:

Hover-activated control panel at bottom of video panel:

```
┌─────────────────────────────────────┐
│                                     │
│         Video Panel                 │
│                                     │
└─────────────────────────────────────┘
  [⚙️] [🔍] [🔊] [📌] [✕]
Settings Reset Spatialize Snap Disconnect
```

**Button Functions**:

| Button | Icon | Function |
|--------|------|----------|
| **Settings** | ⚙️ | Opens PancakeActivity overlay for stream config |
| **Reset Scale** | 🔍 | Resets panel to 1.0x scale |
| **Spatialize** | 🔊 | Toggles spatial audio + room mesh |
| **Snap to Wall** | 📌 | Toggles wall-constrained movement |
| **Disconnect** | ✕ | Ends stream, returns to 2D panel mode |

**Visibility System**:

```kotlin
class ButtonShelfVisibilitySystem(
    buttonShelf: ButtonShelfEntity,
    videoPanelEntity: Entity
) : System {
    // Shows when user hovers over video panel
    // Hides after 1.5 seconds of inactivity
    // Hides when panel is grabbed or scaled
}
```

**Focus Management**:

ButtonShelf clears focus after each button click to prevent Compose from capturing controller input, ensuring game controls continue working.

**Corner-Based Scaling**:

Interactive scaling system with visual corner handles:

```kotlin
class TouchScalableSystem(
    minScale: Float = 0.5f,
    maxScale: Float = 10.0f
) : System {
    // Corner handles appear on panel hover
    // Grab corner with trigger to scale
    // Position and rotation locked during scaling
    // Proportional scaling on X/Y axes
}
```

**Scaling Features**:

- Scale range: 0.5x to 10.0x
- Four corner handles with proportional sizing
- Auto-hide after 1.5 seconds inactivity
- Works with snap-to-wall (handles respect wall plane)

**Snap to Wall**:

Wall-constrained panel movement:

```kotlin
component WallSnap {
    isEnabled: Boolean
    isSnappedToWall: Boolean
    wallPlaneNormal: Vector3
    wallPlanePoint: Vector3
    wallOffset: Float = 0.02f // 2cm from wall
}
```

**Snap Flow**:

1. User toggles "Snap" button on ButtonShelf
2. `WallSnap` component added to video panel
3. User grabs panel → raycast finds nearest wall
4. Panel snaps to wall, stores plane data
5. While grabbed: X/Y movement allowed, Z locked to wall plane
6. Rotation locked to face outward from wall
7. Corner scaling handles also projected onto wall plane

**AnchorSnappingSystem**:

```kotlin
class AnchorSnappingSystem : System {
    // Detects nearest wall via raycast
    // Projects panel position onto wall plane
    // Maintains consistent offset from wall
}
```

## API Reference

### Key Classes

**ImmersiveActivity**

| Method | Purpose |
|--------|---------|
| `onCreate()` | Initialize decoder, systems, connection manager |
| `registerFeatures()` | Register VR, MRUK, Spatial Audio features |
| `onSceneReady()` | Configure scene, create panels |
| `registerPanels()` | Register UI panels with SDK |
| `createVideoPanelEntity()` | Dynamic video panel registration |
| `connectToHost()` | Initiate streaming connection |
| `disconnect()` | Stop stream, cleanup resources |
| `updateVideoPanelScale()` | Programmatic panel scaling |
| `toggleSpatialize()` | Toggle spatial audio + room mesh |
| `toggleSnapToWall()` | Toggle wall-constrained movement |

**MoonlightConnectionManager**

| Method | Purpose |
|--------|---------|
| `checkPairing(host, port, callback)` | Check pairing status |
| `pairWithServer(host, port, pin, callback)` | Pair with server |
| `startStream(host, port, appId, prefs)` | Start streaming |
| `stopStream()` | Stop streaming |
| `isConnected()` | Check connection state |
| `getCurrentConnectionParams()` | Get connection info for recovery |
| `checkAndRestartVideoStreamIfNeeded()` | Recover after sleep |

**ImmersiveSettings**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `spatialAudioEnabled` | Boolean | false | Spatialized audio from panel |
| `roomDimmingEnabled` | Boolean | false | Dim passthrough during streaming |
| `lightingEmissionEnabled` | Boolean | false | Emissive lighting from panel |
| `reflectionsEnabled` | Boolean | false | Video reflections on walls |
<!-- REMOVED: 3D Desktop Client - Stereoscopic depth removed -->
<!-- | `stereoscopicDepthEnabled` | Boolean | false | Stereoscopic 3D mode | -->

## Advanced Implementation

### Lifecycle Implementation Details

The ImmersiveActivity follows a precise initialization sequence to ensure all components are ready before streaming begins.

**Complete Execution Order**:

1. **onCreate()**: Core initialization
   - Initialize MediaCodecHelper with GPU renderer string ("Adreno (TM) 740")
   - Create MoonlightPanelRenderer (decoder)
   - Create AndroidAudioRenderer
   - Create MoonlightConnectionManager
   - Register ECS components (Scalable, ScaledParent, ScaledChild, Anchorable, WallSnap, HeroLighting)
   - Register systems (HeroLightingSystem, WallLightingSystem, PointerInfoSystem, TouchScalableSystem, AnchorSnappingSystem)
   - Read connection params from Intent (host, port, appId)
   - Store as pendingConnectionParams (don't connect yet)

2. **registerFeatures()**: SDK feature registration
   - VRFeature
   - ComposeFeature
   - PhysicsFeature (for MRUK colliders)
   - MRUKFeature
   - SpatialAudioFeature

3. **onSceneReady()**: Scene configuration
   - Disable locomotion: `systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)`
   - Enable passthrough: `scene.enablePassthrough(true)`
   - Configure lighting environment (ambient, sun direction, IBL)
   - Initialize LightingPassthroughHandler for room dimming
   - Create PanelPositioningSystem and register
   - Register ScaleChildrenSystem as late system
   - Create PanelManager root entity
   - Call createVideoPanelEntity()
   - Call createButtonShelfEntity()

4. **createVideoPanelEntity()**: Dynamic panel registration
   - Load ImmersiveSettings to determine panel type
   - Call executeOnVrActivity to register panel (ensures activity is ready)
   - Register panel with appropriate registration type (VideoSurfacePanelRegistration, ReadableVideoSurfacePanelRegistration)
   - surfaceConsumer callback is triggered with (panelEntity, surface)
   - attachSurface() and preConfigureDecoder() called
   - isSurfaceReady = true
   - startStreamIfReady() checks if isPaired && isSurfaceReady

**MediaCodecHelper Initialization**:

```kotlin
// Quest 3/3S uses Adreno 740 GPU
private fun getQuestGlRenderer(): String {
    return "Adreno (TM) 740"
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Initialize BEFORE creating decoder renderer
    val glRenderer = getQuestGlRenderer()
    MediaCodecHelper.initialize(this, glRenderer)

    // Create decoder renderer
    moonlightPanelRenderer = MoonlightPanelRenderer(
        activity = this,
        prefs = prefs,
        crashListener = CrashListener { _ -> }
    )
}
```

**Component Registration Sequence** (onCreate):

```kotlin
// Scaling components
componentManager.registerComponent<Scalable>(Scalable.Companion)
componentManager.registerComponent<ScaledParent>(ScaledParent.Companion)
componentManager.registerComponent<ScaledChild>(ScaledChild.Companion)

// Anchor snapping components for MRUK
componentManager.registerComponent<Anchorable>(Anchorable.Companion)
componentManager.registerComponent<AnchorOnLoad>(AnchorOnLoad.Companion)
componentManager.registerComponent<WallSnap>(WallSnap.Companion)

// Hero lighting components
componentManager.registerComponent<HeroLighting>(HeroLighting.Companion)
componentManager.registerComponent<ReceiveLighting>(ReceiveLighting.Companion)
```

**System Registration Order** (onCreate):

```kotlin
// Hero lighting system (autoDetectTexture, isProcessingShaders)
heroLightingSystem = HeroLightingSystem(autoDetectTexture = true, isProcessingShaders = true)
systemManager.registerSystem(heroLightingSystem!!)

// Wall lighting system (starts hidden)
wallLightingSystem = WallLightingSystem(_isVisible = false)
systemManager.registerSystem(wallLightingSystem!!)

// Pointer info system (required for hover detection)
systemManager.registerSystem(PointerInfoSystem())

// Touch scalable system (min/max scale limits)
systemManager.registerSystem(TouchScalableSystem(minScale = 0.5f, maxScale = 10.0f))

// Anchor snapping system (for snap-to-wall)
systemManager.registerSystem(AnchorSnappingSystem())
```

**Scene Configuration** (onSceneReady):

```kotlin
// Disable VR locomotion (we're using MR with passthrough)
systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)

// Enable passthrough for mixed reality
scene.enablePassthrough(true)

// Configure lighting environment
scene.setLightingEnvironment(
    ambientColor = Vector3(0f),
    sunColor = Vector3(7.0f, 7.0f, 7.0f),
    sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
    environmentIntensity = 0.3f
)
scene.updateIBLEnvironment("environment.env")

// Initialize lighting passthrough handler for room dimming
lightingPassthroughHandler = LightingPassthroughHandler(scene)
```

**Panel Creation Timing**:

The video panel is created AFTER onSceneReady() executes, using `executeOnVrActivity` to ensure the activity and panelManager are fully initialized:

```kotlin
private fun createVideoPanelEntity() {
    // Load settings to determine panel type
    immersiveSettings = ImmersiveSettings.load(this)
    val useLightingEmission = immersiveSettings.lightingEmissionEnabled || immersiveSettings.reflectionsEnabled
    // REMOVED: 3D Desktop Client - Stereoscopic mode removed
    // val usePcSideStereoscopic = prefs.stereoscopicModeEnabled

    // Register panel dynamically using executeOnVrActivity
    // This ensures panelManager is initialized before registration
    SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
        immersiveActivity.registerPanel(
            VideoSurfacePanelRegistration(
                R.id.ui_example,
                surfaceConsumer = { panelEntity, surface ->
                    // Surface is ready
                    SurfaceUtil.paintBlack(surface)
                    moonlightPanelRenderer.attachSurface(surface)
                    moonlightPanelRenderer.preConfigureDecoder()
                    isSurfaceReady = true
                    videoPanelEntity = panelEntity

                    // Add components to entity
                    addVideoPanelComponents(panelEntity, panelSize, useLightingEmission)

                    // Start stream if ready
                    startStreamIfReady()
                },
                settingsCreator = { /* panel settings */ }
            )
        )
    }
}
```

### Sleep/Wake Recovery Implementation

The app implements automatic stream recovery after device sleep/wake cycles.

**onVRPause() / onHMDUnmounted()**: Store connection state

```kotlin
override fun onVRPause() {
    super.onVRPause()
    Log.i(TAG, "onVRPause: Storing connection state for resume recovery")

    // Store connection state before pause
    wasConnectedBeforePause = connectionManager.isConnected()
    if (wasConnectedBeforePause) {
        // Get connection params from connection manager
        connectionParamsBeforePause = connectionManager.getCurrentConnectionParams()
        if (connectionParamsBeforePause == null) {
            // Fallback to pendingConnectionParams
            connectionParamsBeforePause = pendingConnectionParams
        }
        Log.i(TAG, "onVRPause: Stored params: $connectionParamsBeforePause")
    }
}
```

**onVRReady() / onHMDMounted()**: Restore connection

```kotlin
override fun onVRReady() {
    super.onVRReady()
    Log.i(TAG, "onVRReady: Checking if video stream needs recovery")

    // If we were connected before pause, check if we need to re-establish video stream
    if (wasConnectedBeforePause) {
        Log.i(TAG, "onVRReady: Was connected before pause, checking video stream health")

        // Check if connection is still alive
        val isCurrentlyConnected = connectionManager.isConnected()
        if (!isCurrentlyConnected && connectionParamsBeforePause != null) {
            val (host, port, appId) = connectionParamsBeforePause!!
            Log.i(TAG, "onVRReady: Connection lost, re-establishing stream")

            // Re-establish connection
            pendingConnectionParams = connectionParamsBeforePause
            isPaired = true
            startStreamIfReady()
        } else if (isCurrentlyConnected) {
            // Connection still exists, but video stream may have died
            Log.i(TAG, "onVRReady: Connection active, checking video decoder state")
            connectionManager.checkAndRestartVideoStreamIfNeeded()
        }

        // Reset state
        wasConnectedBeforePause = false
        connectionParamsBeforePause = null
    }
}
```

**checkAndRestartVideoStreamIfNeeded()**: Full stream restart

```kotlin
fun checkAndRestartVideoStreamIfNeeded() {
    executor.execute {
        try {
            val params = currentConnectionParams
            val prefs = currentPrefs
            if (params == null || prefs == null) {
                Log.w(tag, "Missing connection params, cannot restart")
                return@execute
            }

            val (host, port, appId) = params

            // Always restart entire stream after sleep/wake
            // Video stream path may be dead even if connection object is active
            Log.i(tag, "Restarting video stream after sleep/wake")

            // Stop current connection
            try {
                connection?.stop()
            } catch (e: Exception) {
                Log.d(tag, "Error stopping connection: ${e.message}")
            }
            connection = null
            isConnected = false

            // Small delay to ensure connection is fully stopped
            Thread.sleep(200)

            // Restart stream with same parameters
            startStream(host, port, appId, prefs)
            Log.i(tag, "Full stream restart initiated")
        } catch (e: Exception) {
            Log.e(tag, "Error during video stream recovery", e)
        }
    }
}
```

### Entity Creation Pattern

The app uses a specific pattern for creating video panel entities to avoid duplication issues.

**Why Entity is Created Manually AFTER Registration**:

1. **SDK Provides Entity**: When you register a panel, the SDK creates an entity and provides it via callbacks:
   - `surfaceConsumer` callback receives `(panelEntity, surface)`
   - Entity is returned from the lambda

2. **Use SDK-Provided Entity**: The correct pattern is to use the SDK-provided entity and add components to it:

```kotlin
surfaceConsumer = { panelEntity, surface ->
    // SDK provides panelEntity - use this, don't create a new one
    videoPanelEntity = panelEntity

    // Attach surface
    SurfaceUtil.paintBlack(surface)
    moonlightPanelRenderer.attachSurface(surface)
    moonlightPanelRenderer.preConfigureDecoder()
    isSurfaceReady = true

    // Add components to SDK-provided entity
    addVideoPanelComponents(panelEntity, panelSize, useLightingEmission)

    // Register with scaling system
    touchScalableSystem?.registerEntity(panelEntity)

    // Make visible when ready
    panelEntity.setComponent(Visible(true))
}
```

**Historical Entity Duplication Problem**:

Previous implementations created entities manually after registration, causing duplication:

```kotlin
// OLD PATTERN (INCORRECT):
// SDK creates entity in surfaceConsumer
surfaceConsumer = { panelEntity, surface ->
    Log.i(TAG, "Surface attached for entity=$panelEntity")
    // SDK provides panelEntity but it was logged and ignored
}

// Then ANOTHER entity was created manually
videoPanelEntity = Entity.create(
    Panel(R.id.ui_example),
    Transform(),
    // ... components
)
```

**Problem**: This created TWO entities for the same panel ID:
- SDK-created entity with correct `PanelDimensions`
- Manually created entity with wrong dimensions (e.g., 2560x1440)

**Resolution**: Use only the SDK-provided entity from callbacks. Add components to that entity instead of creating a new one.

**surfaceConsumer Callback Timing**:

The `surfaceConsumer` callback is triggered asynchronously after panel registration:

```
registerPanel() called
  ↓
SDK creates panel entity
  ↓
SDK creates surface
  ↓
surfaceConsumer(panelEntity, surface) callback triggered
  ↓
attachSurface() called
  ↓
preConfigureDecoder() called
  ↓
isSurfaceReady = true
  ↓
startStreamIfReady() checks conditions
```

**Entity Synchronization Logic**:

State flags ensure proper synchronization:

```kotlin
// State flags
private var isPaired: Boolean = false
private var isSurfaceReady: Boolean = false
private var videoPanelEntity: Entity? = null
private var pendingConnectionParams: Triple<String, Int, Int>? = null

// Connection only starts when all conditions are met
private fun startStreamIfReady() {
    val params = pendingConnectionParams
    if (params != null && isPaired && isSurfaceReady) {
        val (host, port, appId) = params
        Log.i(TAG, "Starting stream - all conditions met")
        pendingConnectionParams = null
        connectionManager.startStream(host, port, appId, prefs)
    } else {
        Log.d(TAG, "Stream not ready: isPaired=$isPaired isSurfaceReady=$isSurfaceReady")
    }
}
```

### Video Pipeline Trace

Complete step-by-step flow from panel creation to video rendering.

**1. Panel Surface Ready Sequence**:

```
onCreate() → createVideoPanelEntity()
  ↓
executeOnVrActivity { registerPanel(VideoSurfacePanelRegistration(...)) }
  ↓
SDK creates panel entity internally
  ↓
SDK creates Surface for video rendering
  ↓
surfaceConsumer(panelEntity, surface) callback triggered
  ↓
SurfaceUtil.paintBlack(surface) - initialize to black
  ↓
moonlightPanelRenderer.attachSurface(surface) - attach to decoder
  ↓
moonlightPanelRenderer.preConfigureDecoder() - configure MediaCodec
  ↓
isSurfaceReady = true
  ↓
videoPanelEntity = panelEntity (store SDK-provided entity)
```

**2. Connection Start**:

```
startStreamIfReady() checks (isPaired && isSurfaceReady)
  ↓
connectionManager.startStream(host, port, appId, prefs)
  ↓
Background thread executor
  ↓
decoderRenderer.stop() - clean state
  ↓
MoonBridge.setupBridge(decoderRenderer, audioRenderer, listener)
  ↓ (CRITICAL: setupBridge BEFORE NvConnection)
NvConnection created with StreamConfiguration
  ↓
connection.start(audioRenderer, decoderRenderer, listener)
```

**3. Decoder Setup**:

```
NvConnection.start() initiates RTSP handshake
  ↓
Server negotiates format/resolution/fps
  ↓
MoonBridge native code calls bridgeDrSetup(format, width, height, fps)
  ↓
bridgeDrSetup calls decoderRenderer.setup(format, width, height, fps, ...)
  ↓
NativeDecoderRenderer.setup() configures MediaCodec
  ↓
MediaCodec.configure(format, surface, null, 0)
  ↓
Surface is now bound to decoder
```

**4. Decode-Unit Delivery**:

```
Server sends video packets
  ↓
MoonBridge native code receives packets
  ↓
bridgeDrSubmitDecodeUnit(data, offset, length, frameType, ...)
  ↓
decoderRenderer.submitDecodeUnit(...)
  ↓
NativeDecoderRenderer queues data to MediaCodec input buffer
  ↓
MediaCodec decodes H264/HEVC/AV1 frame
  ↓
MediaCodec renders to Surface (bound during configure)
  ↓
Panel displays decoded frame
```

**5. Negotiated Formats**:

```
Client sends supported formats:
- VIDEO_FORMAT_H264 (0x0001)
- VIDEO_FORMAT_H265 (0x0002)
- VIDEO_FORMAT_AV1_MAIN8 (0x0010)
- VIDEO_FORMAT_MASK_10BIT (0x0080) - if HDR enabled

Server responds with selected format:
- Format (H264/HEVC/AV1)
- Width/Height (actual stream resolution)
- FPS (actual frame rate)
- Color space (BT709/BT2020)
- Color range (LIMITED/FULL)

bridgeDrSetup() called with negotiated values:
- format: int (MoonBridge.VIDEO_FORMAT_*)
- width: int (e.g., 2560)
- height: int (e.g., 1440)
- fps: int (e.g., 90)
- colorSpace: int (BT709/BT2020)
- colorRange: int (LIMITED/FULL)
```

**6. Panel Overlay Handling**:

```
Video panel entity has Panel component:
- Panel(R.id.ui_example)
- panelRegistrationId links to VideoSurfacePanelRegistration

SDK manages panel as overlay in VR scene:
- Transform component sets position/rotation
- Grabbable component enables manipulation
- Scale component controls size
- Visible component controls visibility

Panel rendering:
- MediaCodec outputs to Surface
- Surface is composited into VR scene
- SDK handles distortion correction
- SDK handles standard rendering

```

### Lifecycle Methods

**ImmersiveActivity Lifecycle**:

```kotlin
onCreate() → registerFeatures() → onSceneReady() → registerPanels()
                                        ↓
                              createVideoPanelEntity()
                                        ↓
                              surfaceConsumer callback
                                        ↓
                              attachSurface + preConfigureDecoder
                                        ↓
                              startStreamIfReady()
```

**Sleep/Wake Recovery**:

```kotlin
onVRPause() / onHMDUnmounted()
  → Store connection state
  → wasConnectedBeforePause = true
  → connectionParamsBeforePause = (host, port, appId)

onVRReady() / onHMDMounted()
  → Check wasConnectedBeforePause
  → If connection lost: full stream restart
  → If connection active: video stream recovery
  → checkAndRestartVideoStreamIfNeeded()
```

## Developer Notes

### Historical Context

**Virtual Keyboard Issues**:

Meta Horizon OS has known issues with virtual keyboard positioning in immersive mode. The keyboard can fail to position properly, become invisible, or block UI interaction. This led to the hybrid app pattern where PancakeActivity (2D panel mode) is the default launcher, providing reliable text input in the Home environment for pairing and configuration. Once connected, the app transitions to ImmersiveActivity for VR streaming.

**Entity Duplication Problem**:

Early implementations created video panel entities manually after panel registration, not realizing the SDK already creates an entity and provides it via the `surfaceConsumer` callback. This caused two entities for the same panel ID with mismatched dimensions. The resolution was to use only the SDK-provided entity from callbacks and add components to it.

### Known Issues and Workarounds

**Video Surface Color Space Initialization Issue**:

**Problem**: Video panels display incorrect colors on initial surface creation. Colors appear washed out, oversaturated, or with incorrect color space mapping. The issue resolves after device sleep/wake cycle (onPause/onResume).

**Affected**: Both Moonlight SpatialSDK and PremiumMediaSample (Meta's reference implementation)

**Root Cause**: Likely related to Spatial SDK panel surface color space initialization. Surface color space/dataspace may not be properly applied on first frame. Sleep/wake cycle triggers surface re-initialization that applies correct color space.

**Workaround**: Take headset off and put it back on (triggers sleep/wake cycle). Colors will be correct after resume. This is a known SDK limitation requiring a fix from Meta.

**Status**: Not a bug in our implementation. Affects reference implementation as well. Issue persists across different video codecs (H.264, HEVC, AV1).

See POST_MORTEM.md for complete details.

### Debugging Tips

**Verify Panel Registration Succeeded**:

```kotlin
// Check if panel entity was created
if (videoPanelEntity == null) {
    Log.w(TAG, "Panel entity is null - registration may have failed")
    return
}

// Verify panel entity has Panel component
val panel = videoPanelEntity?.tryGetComponent<Panel>()
if (panel == null) {
    Log.w(TAG, "Panel entity missing Panel component")
    return
}

// Check panel registration ID matches
if (panel.panelRegistrationId != R.id.ui_example) {
    Log.w(TAG, "Panel registration ID mismatch: expected R.id.ui_example, got ${panel.panelRegistrationId}")
}

Log.i(TAG, "Panel registration verified successfully")
```

**Check if Video Surface is Attached**:

```kotlin
// Surface attachment is confirmed in surfaceConsumer callback
surfaceConsumer = { panelEntity, surface ->
    Log.i(TAG, "Surface attached for panel entity=$panelEntity")
    Log.i(TAG, "Surface valid: ${surface.isValid}")

    // Paint black to verify surface is writable
    try {
        SurfaceUtil.paintBlack(surface)
        Log.i(TAG, "Surface painted black successfully")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to paint surface", e)
    }

    // Check isSurfaceReady flag after attachment
    moonlightPanelRenderer.attachSurface(surface)
    isSurfaceReady = true
    Log.i(TAG, "isSurfaceReady = $isSurfaceReady")
}
```

**Troubleshoot Connection Issues**:

```kotlin
// Verify pairing status
connectionManager.checkPairing(host, port) { isPaired, error ->
    if (!isPaired) {
        Log.w(TAG, "Not paired: $error")
        // Show pairing UI
    } else {
        Log.i(TAG, "Pairing verified")
    }
}

// Check connection state flags
Log.d(TAG, "Connection state: isPaired=$isPaired isSurfaceReady=$isSurfaceReady")
Log.d(TAG, "Pending params: $pendingConnectionParams")

// Monitor connection lifecycle
override fun connectionStarted() {
    Log.i(TAG, "Connection established successfully")
}

override fun connectionTerminated(errorCode: Int) {
    Log.w(TAG, "Connection terminated: errorCode=$errorCode")
}

override fun stageFailed(stageName: String, portFlags: Int, errorCode: Int) {
    Log.e(TAG, "Stage failed: $stageName errorCode=$errorCode portFlags=$portFlags")
}
```

**Verify MRUK Scene Loaded**:

```kotlin
// Load MRUK scene with callbacks
roomMeshManager?.loadSceneFromDevice(
    onSceneLoaded = {
        Log.i(TAG, "MRUK scene loaded successfully")
        // Check if rooms are available
        val rooms = mrukFeature.getLoadedRooms()
        Log.i(TAG, "Loaded ${rooms.size} rooms")
    },
    onSceneLoadFailed = { result ->
        Log.w(TAG, "MRUK scene load failed: $result")
        // Check permissions
        val hasPermission = checkSelfPermission(PERMISSION_USE_SCENE) == PackageManager.PERMISSION_GRANTED
        Log.w(TAG, "USE_SCENE permission granted: $hasPermission")
    }
)
```

## Code Reference

### File Organization

```
Moonlight-SpatialSDK/
├── app/src/main/
│   ├── java/com/example/moonlight_spatialsdk/
│   │   ├── ImmersiveActivity.kt                    # Main VR activity
│   │   ├── PancakeActivity.kt                      # 2D launcher activity
│   │   ├── MoonlightConnectionManager.kt           # Connection lifecycle
│   │   ├── MoonlightPanelRenderer.kt               # Video decoder bridge
│   │   ├── PanelManager.kt                         # Root panel entity
│   │   ├── PanelPositioningSystem.kt               # Panel positioning
│   │   ├── Components.kt                           # ECS components
│   │   ├── entities/
│   │   │   ├── ButtonShelfEntity.kt                # Control buttons
│   │   │   ├── BiasLightingEntity.kt               # Ambient glow effect
<!-- REMOVED: 3D Desktop Client - Stereoscopic entities removed -->
│   │   ├── systems/
│   │   │   ├── buttonShelfVisibility/
│   │   │   │   └── ButtonShelfVisibilitySystem.kt  # Button shelf auto-hide
│   │   │   ├── scalable/
│   │   │   │   └── TouchScalableSystem.kt          # Corner-based scaling
│   │   │   ├── scaleChildren/
│   │   │   │   └── ScaleChildrenSystem.kt          # Child entity scaling
│   │   │   ├── anchor/
│   │   │   │   └── AnchorSnappingSystem.kt         # Wall snapping
│   │   │   ├── audio/
│   │   │   │   └── SpatialAudioManager.kt          # Spatial audio
│   │   │   ├── mruk/
│   │   │   │   └── RoomMeshManager.kt              # Room mesh
│   │   │   ├── heroLighting/
│   │   │   │   ├── HeroLightingSystem.kt           # Panel edge lighting
│   │   │   │   └── WallLightingSystem.kt           # Wall reflections
│   │   │   ├── lighting/
│   │   │   │   └── LightingPassthroughHandler.kt   # Room dimming
<!-- REMOVED: 3D Desktop Client - Stereoscopic systems removed -->
│   │   │   └── pointerInfo/
│   │   │       └── PointerInfoSystem.kt            # Hover detection
│   │   ├── data/
│   │   │   └── ImmersiveSettings.kt                # Feature flags
│   │   └── ui/
│   │       ├── ButtonShelfCompose.kt               # Button shelf UI
<!-- REMOVED: 3D Desktop Client - Stereoscopic UI removed -->
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_pancake.xml                # 2D UI layout
│   │   ├── values/
│   │   │   ├── panel_ids.xml                       # Panel ID definitions
│   │   │   └── strings.xml
│   │   └── xml/
│   │       ├── network_security_config.xml         # Cleartext traffic
│   │       └── provider_paths.xml
│   └── AndroidManifest.xml                         # Permissions, activities
└── Documentation/
    ├── Quest 3 App Overview.md                     # This file
    ├── Quest 3 App Pipeline.md                     # Pipeline analysis
    ├── POST_MORTEM.md                              # Known issues
    ├── SAMPLE_ARCHITECTURE_ANALYSIS.md             # Sample comparison
    └── moonlight-migration-plan.md                 # Integration strategy
```

### Key Components Location Reference

| Component | File Path | Purpose |
|-----------|-----------|---------|
| **Systems** | | |
| TouchScalableSystem | systems/scalable/TouchScalableSystem.kt | Corner-based panel scaling (0.5x-10x) |
| ButtonShelfVisibilitySystem | systems/buttonShelfVisibility/ButtonShelfVisibilitySystem.kt | Auto-hide button shelf after 1.5s |
| ScaleChildrenSystem | systems/scaleChildren/ScaleChildrenSystem.kt | Propagate scale to child entities |
| AnchorSnappingSystem | systems/anchor/AnchorSnappingSystem.kt | Wall-constrained movement |
| PointerInfoSystem | systems/pointerInfo/PointerInfoSystem.kt | Controller hover detection |
| HeroLightingSystem | systems/heroLighting/HeroLightingSystem.kt | Panel edge emissive lighting |
| WallLightingSystem | systems/heroLighting/WallLightingSystem.kt | Video reflections on walls |
<!-- REMOVED: 3D Desktop Client - Stereoscopic systems removed -->
| **Entities** | | |
| ButtonShelfEntity | entities/ButtonShelfEntity.kt | Control button panel (settings, reset, etc.) |
| BiasLightingEntity | entities/BiasLightingEntity.kt | Ambient glow around panel edges |
<!-- REMOVED: 3D Desktop Client - Stereoscopic entities removed -->
| **Managers** | | |
| RoomMeshManager | systems/mruk/RoomMeshManager.kt | MRUK scene loading and mesh generation |
| SpatialAudioManager | systems/audio/SpatialAudioManager.kt | Audio session spatialization |
| LightingPassthroughHandler | systems/lighting/LightingPassthroughHandler.kt | Passthrough dimming control |
| **Compose Panels** | | |
| ButtonShelfCompose | ui/ButtonShelfCompose.kt | Button shelf Compose UI |
<!-- REMOVED: 3D Desktop Client - Stereoscopic UI removed -->
| **Configuration** | | |
| panel_ids.xml | res/values/panel_ids.xml | Panel registration IDs (ui_example, button_shelf, etc.) |
| ImmersiveSettings | data/ImmersiveSettings.kt | Feature toggles (spatial audio, lighting) |

### Method Reference with Signatures

| Method | Class | Signature | Purpose |
|--------|-------|-----------|---------|
| **ImmersiveActivity** | | | |
| onCreate | ImmersiveActivity | `onCreate(savedInstanceState: Bundle?)` | Initialize decoder, systems, connection manager |
| registerFeatures | ImmersiveActivity | `registerFeatures(): List<SpatialFeature>` | Register VR, MRUK, Spatial Audio features |
| onSceneReady | ImmersiveActivity | `onSceneReady()` | Configure scene, create panels |
| registerPanels | ImmersiveActivity | `registerPanels(): List<PanelRegistration>` | Register UI panels with SDK |
| createVideoPanelEntity | ImmersiveActivity | `createVideoPanelEntity()` | Dynamic video panel registration |
| connectToHost | ImmersiveActivity | `connectToHost(host: String, port: Int, appId: Int)` | Initiate streaming connection |
| disconnect | ImmersiveActivity | `disconnect()` | Stop stream, cleanup resources |
| updateVideoPanelScale | ImmersiveActivity | `updateVideoPanelScale(scale: Float)` | Programmatic panel scaling |
| toggleSpatialize | ImmersiveActivity | `toggleSpatialize()` | Toggle spatial audio + room mesh |
| toggleSnapToWall | ImmersiveActivity | `toggleSnapToWall()` | Toggle wall-constrained movement |
| onVRPause | ImmersiveActivity | `onVRPause()` | Store connection state before pause |
| onVRReady | ImmersiveActivity | `onVRReady()` | Restore connection after resume |
| calculatePanelSize | ImmersiveActivity | `calculatePanelSize(force16x9: Boolean, useDoubledWidth: Boolean): Vector2` | Calculate panel dimensions from stream resolution |
| **MoonlightConnectionManager** | | | |
| checkPairing | MoonlightConnectionManager | `checkPairing(host: String, port: Int, callback: (Boolean, String?) -> Unit)` | Check pairing status |
| pairWithServer | MoonlightConnectionManager | `pairWithServer(host: String, port: Int, pin: String, callback: (Boolean, String?) -> Unit)` | Pair with server using PIN |
| startStream | MoonlightConnectionManager | `startStream(host: String, port: Int, appId: Int, prefs: PreferenceConfiguration)` | Start streaming session |
| stopStream | MoonlightConnectionManager | `stopStream()` | Stop streaming and cleanup |
| isConnected | MoonlightConnectionManager | `isConnected(): Boolean` | Check connection state |
| getCurrentConnectionParams | MoonlightConnectionManager | `getCurrentConnectionParams(): Triple<String, Int, Int>?` | Get connection info for recovery |
| checkAndRestartVideoStreamIfNeeded | MoonlightConnectionManager | `checkAndRestartVideoStreamIfNeeded()` | Recover stream after sleep |
| initializeControllerHandler | MoonlightConnectionManager | `initializeControllerHandler(): Boolean` | Initialize controller input forwarding |
| **PanelManager** | | | |
| create | PanelManager | `create(): Entity` | Create root panel entity with Transform, Grabbable |
| **RoomMeshManager** | | | |
| loadSceneFromDevice | RoomMeshManager | `loadSceneFromDevice(onSceneLoaded: (() -> Unit)?, onSceneLoadFailed: ((MRUKLoadDeviceResult) -> Unit)?)` | Load MRUK scene from device |
| initializeMeshColliders | RoomMeshManager | `initializeMeshColliders()` | Create collision geometry for room surfaces |
| **SpatialAudioManager** | | | |
| enableSpatialAudio | SpatialAudioManager | `enableSpatialAudio(entity: Entity, androidAudioSessionId: Int, channelCount: Int = 2)` | Enable spatial audio for entity |
| disableSpatialAudio | SpatialAudioManager | `disableSpatialAudio()` | Disable spatial audio |

## Quick Reference Card

### Configuration Values

| Constant | Value | Description |
|----------|-------|-------------|
| **Panel Dimensions** | | |
| basePanelHeightMeters | 0.7f | Base panel height in meters |
| Panel width | Aspect ratio × 0.7m | Calculated from stream resolution |
<!-- REMOVED: 3D Desktop Client - Stereoscopic width removed -->
| **Timeout Values** | | |
| Poll delay | 50ms | Delay between entity polling attempts |
| Max poll attempts | 10 | Maximum entity polling attempts |
| Connection recovery delay | 200ms | Delay before stream restart |
| Button shelf hide delay | 1.5s | Auto-hide after inactivity |
| **Scale Ranges** | | |
| Minimum scale | 0.5f | Smallest panel size (50%) |
| Maximum scale | 10.0f | Largest panel size (1000%) |
| Default scale | 1.0f | Normal panel size (100%) |
| **Wall Offset** | | |
| Wall offset distance | 0.02f | 2cm from wall surface |
| **Panel Height** | | |
| Base panel height | 0.7f | Default panel height in meters |
| Disconnect dialog height | 0.28f | 0.4 × base height |
| Disconnect dialog width | 0.35f | 0.5 × base height |
| Button shelf height | 0.12f | Fixed height |
| Button shelf width | 0.9f | Fixed width |
| **Default Connection** | | |
| Default port | 47989 | Moonlight/Sunshine default port |
| Default appId | 0 | Desktop streaming (not specific app) |
| **Default Stream Settings** | | |
| Default resolution | 2560×1440 | QHD resolution |
| Default FPS | 90 | Match Quest 3 refresh rate |
| Default bitrate | 50000 kbps | 50 Mbps |
| Default codec | AUTO | H264/HEVC negotiated |

### Key State Variables

| Variable | Type | Meaning |
|----------|------|---------|
| **Connection State** | | |
| isPaired | Boolean | Server pairing verified |
| isSurfaceReady | Boolean | Video surface attached to decoder |
| isConnected | Boolean | Stream connection active |
| pendingConnectionParams | Triple<String, Int, Int>? | (host, port, appId) waiting to connect |
| **Recovery State** | | |
| wasConnectedBeforePause | Boolean | Connection active before sleep |
| connectionParamsBeforePause | Triple<String, Int, Int>? | Params for recovery after wake |
| **Entities** | | |
| videoPanelEntity | Entity? | Main video panel entity (SDK-provided) |
| buttonShelfEntity | ButtonShelfEntity? | Control button panel |
| disconnectDialogPanelEntity | Entity? | Disconnect confirmation dialog |
<!-- REMOVED: 3D Desktop Client - Stereoscopic depth slider removed -->
| biasLightingEntity | BiasLightingEntity? | Ambient glow effect |
| **Features** | | |
| isImmersiveModeEnabled | StateFlow<Boolean> | Spatial audio + room mesh active |
| isSnapEnabled | StateFlow<Boolean> | Wall-constrained movement active |
| showDisconnectDialog | StateFlow<Boolean> | Disconnect dialog visible |
| **Input Forwarding** | | |
| shouldForwardInputs | Boolean | Controller input forwarded to PC |
| allowControllerUIInput | Boolean | Debug flag: allow UI navigation |

### Configuration

**Stream Configuration** (PreferenceConfiguration):

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `width` | Int | 2560 | Stream width in pixels |
| `height` | Int | 1440 | Stream height in pixels |
| `fps` | Int | 90 | Stream framerate |
| `bitrate` | Int | 50000 | Stream bitrate (kbps) |
| `videoFormat` | VideoFormat | AUTO | Codec (H264/HEVC/AV1) |
| `audioConfiguration` | AudioConfiguration | STEREO | Audio channels |
<!-- REMOVED: 3D Desktop Client - Stereoscopic mode removed -->
<!-- | `stereoscopicModeEnabled` | Boolean | false | Enable SBS stereoscopic | -->

**Panel Configuration**:

```kotlin
// Base panel height (all panels scale from this)
private val basePanelHeightMeters = 0.7f

// Calculate panel size from stream resolution
private fun calculatePanelSize(force16x9: Boolean, useDoubledWidth: Boolean): Vector2 {
    val aspect = if (force16x9) {
        16f / 9f
    } else {
        val width = if (useDoubledWidth) prefs.width * 2 else prefs.width
        width.toFloat() / prefs.height.toFloat()
    }
    return Vector2(aspect * basePanelHeightMeters, basePanelHeightMeters)
}
```

## Advanced Topics

### Custom Shaders

**Hero Lighting Shader**:

```kotlin
// Samples panel edge colors
// Projects emissive light into scene
// Requires ReadableVideoSurfacePanelRegistration
```

### Performance Optimization

**Video Panel Rendering**:

1. **Standard Mode**: Use for maximum performance (direct-to-surface)
2. **Lighting Mode**: Highest overhead for texture sampling + shaders

**Decoder Configuration**:

```kotlin
// Initialize MediaCodecHelper with Quest 3 GPU info
MediaCodecHelper.initialize(context, "Adreno (TM) 740")

// Pre-configure decoder before stream starts
moonlightPanelRenderer.preConfigureDecoder()
```

**Benefits**:

- Explicit decoder selection
- Capability checking (low latency, adaptive playback)
- Skip reconfiguration when params match
- Faster stream startup

**Panel Scaling**:

```kotlin
// Use Scale component for efficient scaling
entity.setComponent(Scale(Vector3(scaleFactor)))

// Corner handles scale proportionally with panel
// No need to recreate entities on scale change
```

**Background Thread Execution**:

All network operations in `MoonlightConnectionManager` run on dedicated executor to prevent ANR.

## Troubleshooting

### Common Issues

**Issue**: Virtual keyboard doesn't appear in immersive mode

**Solution**: Use PancakeActivity for text input. App automatically launches in 2D mode for pairing.

---

**Issue**: Colors look incorrect on first frame

**Solution**: Known SDK issue. Colors resolve after device sleep/wake cycle. See `POST_MORTEM.md` for details.

---

**Issue**: Video panel not visible after connection

**Solution**:
- Check `videoPanelEntity?.setComponent(Visible(true))` is called
- Verify `surfaceConsumer` callback executed
- Check logs for entity creation errors

---

**Issue**: Controller input not working in game

**Solution**:
- Ensure `shouldForwardInputs = true` after connection
- Verify `allowControllerUIInput = false` for input forwarding
- Check `controllerHandler` is initialized
- ButtonShelf clears focus after button clicks

---

**Issue**: Stream dies after device sleep

**Solution**: App implements automatic recovery:
- `onVRPause()` stores connection state
- `onVRReady()` restarts stream if needed
- `checkAndRestartVideoStreamIfNeeded()` performs full restart

---

**Issue**: Spatial audio not working

**Solution**:
- Verify `USE_SCENE` permission granted
- Check audio session ID is valid (> 0)
- Ensure `SpatialAudioFeature` registered
- Toggle "Spatialize" button on ButtonShelf

---

**Issue**: Snap to wall not snapping

**Solution**:
- Ensure `USE_SCENE` permission granted
- MRUK scene must load successfully
- Grab panel after enabling snap (doesn't snap until grabbed)
- Check wall detection via raycast logs

---

**Issue**: Stereoscopic mode shows flat image

**Solution**:
<!-- REMOVED: 3D Desktop Client - Stereoscopic troubleshooting removed -->

## Related Documentation

- **Quest 3 App Pipeline**: Detailed pipeline analysis (`Documentation/Quest 3 App Pipeline.md`)
- **Sample Architecture Analysis**: Comparison of HybridSample vs PremiumMediaSample patterns (`Documentation/SAMPLE_ARCHITECTURE_ANALYSIS.md`)
- **Migration Plan**: Moonlight integration strategy (`Documentation/moonlight-migration-plan.md`)
- **Feasibility Report**: Spatial SDK port analysis (`Documentation/SPATIAL_PORT_FEASIBILITY_REPORT.md`)
- **Keyboard Analysis**: Virtual keyboard positioning issues (`Documentation/KEYBOARD_VANISH_ANALYSIS.md`)
- **Post Mortem**: Known issues and resolutions (`Documentation/POST_MORTEM.md`)

---

## Summary

The Moonlight-SpatialSDK Quest 3 app delivers PC game streaming to VR with advanced spatial features:

**Strengths**:

- Hybrid 2D/VR architecture for reliable text input
- Two video panel modes (standard, lighting)
- Spatial audio from panel position
- MRUK room integration for spatial awareness
- Interactive controls (scaling, snap-to-wall, button shelf)
- Controller input forwarding to PC
- Sleep/wake recovery for seamless streaming
- Comprehensive error handling and logging

**Architecture Highlights**:

- Meta Spatial SDK best practices (PanelManager, executeOnVrActivity)
- ECS component system for extensibility
- Background thread execution prevents ANR
- Single source of truth for panel dimensions
- Consistent entity creation across all modes

**Use Cases**:

- **Standard Streaming**: Default mode for 2D game streaming
<!-- REMOVED: 3D Desktop Client - Stereoscopic use case removed -->
- **Immersive Theater**: Spatial audio + ambilight for movie watching
- **Room Integration**: Wall-mounted panels with spatial audio for persistent displays

The app successfully bridges Moonlight's mature streaming protocol with Meta Spatial SDK's cutting-edge VR capabilities, providing a feature-rich mixed reality streaming experience on Quest 3.
