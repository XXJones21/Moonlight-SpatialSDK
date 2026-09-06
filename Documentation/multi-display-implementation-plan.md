# Multi-Display & Virtual Monitor Implementation Plan

## Executive Summary

This document outlines the implementation strategy for adding multi-monitor support and virtual display creation to Moonlight-SpatialSDK, enabling a macOS-like virtual display experience for Quest 3 users similar to Apple Vision Pro's virtual display feature.

---

## Core Design Principle: Dynamic Virtual Display Lifecycle

### The Problem with Static Virtual Displays

If virtual monitors are configured to always exist on the Windows desktop, users face a critical UX issue:

- **Lost content**: Windows and applications can be dragged or opened on the virtual display
- **Invisible input**: Mouse cursor can move to the virtual display and become "lost"
- **Navigation trap**: Without the VR headset connected, users cannot see or interact with content on virtual displays
- **Startup issues**: Applications may remember their position and launch on the invisible virtual display

### The Solution: On-Demand Virtual Display Creation

Virtual displays must be **dynamically created when the VR client requests them** and **destroyed when no longer needed**. This ensures:

1. Virtual displays only exist while actively streaming to the headset
2. Content cannot become trapped on invisible displays
3. Clean desktop state when VR session ends
4. No residual virtual monitors after disconnection

### Lifecycle States

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        VIRTUAL DISPLAY LIFECYCLE                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   [Not Connected]     [Connected - Primary Only]     [Multi-Display]   │
│        │                        │                          │            │
│        │   Connect              │   "Add Screen"           │            │
│        ├───────────────────────►│   Button Press           │            │
│        │                        ├─────────────────────────►│            │
│        │                        │                          │            │
│        │                        │   Virtual display        │            │
│        │                        │   created on server      │            │
│        │                        │   Panel spawned in VR    │            │
│        │                        │   Stream started         │            │
│        │                        │                          │            │
│        │                        │◄─────────────────────────┤            │
│        │                        │   "Close" panel          │            │
│        │                        │   or Disconnect          │            │
│        │                        │                          │            │
│        │◄───────────────────────┤   Virtual display        │            │
│        │   Disconnect           │   destroyed on server    │            │
│        │   (all virtual         │   Panel removed in VR    │            │
│        │   displays destroyed)  │   Stream stopped         │            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Key Behaviors

| Event | Client Action | Server Action |
|-------|---------------|---------------|
| Connect (multi-display enabled) | Send capability flag | Prepare for virtual display commands |
| "Add Screen" button pressed | Create panel, request display | Create virtual display via driver, start stream |
| Panel closed by user | Destroy panel, notify server | Destroy virtual display, stop stream |
| Disconnect | Destroy all panels | Destroy ALL virtual displays created this session |
| Connection lost unexpectedly | N/A | Timeout → destroy all session virtual displays |

---

## Settings & UI Requirements

### 1. Stream Settings: Multi-Monitor Checkbox

A new setting in the stream configuration to enable multi-monitor capability.

**Location**: Stream settings (pre-connection configuration)

**Setting Definition**:

```kotlin
// In PreferenceConfiguration.java or StreamSettings.kt
const val MULTI_MONITOR_ENABLED_PREF = "multi_monitor_enabled"
const val MULTI_MONITOR_ENABLED_DEFAULT = false

data class MultiMonitorSettings(
    val enabled: Boolean = false,
    val maxVirtualDisplays: Int = 3,           // Limit concurrent virtual displays
    val defaultResolution: Resolution = Resolution.FHD_1080P,
    val defaultRefreshRate: Int = 60
)
```

**UI Mockup** (in stream settings):

```
┌─────────────────────────────────────────────┐
│  STREAM SETTINGS                            │
├─────────────────────────────────────────────┤
│                                             │
│  Resolution:        [1920x1080 ▼]           │
│  Frame Rate:        [60 FPS ▼]              │
│  Bitrate:           [20 Mbps ▼]             │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│  [✓] Enable Multi-Monitor Support           │
│      Allows adding virtual displays         │
│      while connected. Requires compatible   │
│      server with virtual display driver.    │
│                                             │
│      Max Virtual Displays: [3 ▼]            │
│      Default Resolution:   [1080p ▼]        │
│                                             │
└─────────────────────────────────────────────┘
```

**Behavior When Enabled**:

1. Client sends `CAPABILITY_MULTI_DISPLAY` flag during connection handshake
2. Server responds with `SUPPORTS_VIRTUAL_DISPLAYS` if capable
3. "Add Screen" button becomes visible on ButtonShelf during stream
4. If server doesn't support it, show info message and disable button

**Behavior When Disabled**:

- Single-display mode (current behavior)
- "Add Screen" button hidden
- No virtual display protocol messages sent

### 2. ButtonShelf: "Add Screen" Button

A new button on the in-VR control shelf to dynamically add virtual displays.

**Location**: ButtonShelf (alongside Settings, Reset Scale, Disconnect)

**Visual Design**:

```
┌─────────────────────────────────────────────────────────────┐
│                      BUTTON SHELF                           │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────────┐   │
│   │ ⚙️      │  │ ↻       │  │ ✕       │  │ + Screen    │   │
│   │Settings │  │ Reset   │  │Disconnect│  │             │   │
│   └─────────┘  └─────────┘  └─────────┘  └─────────────┘   │
│                                                             │
│   (existing)   (existing)   (existing)   (NEW - only       │
│                                           visible when      │
│                                           multi-monitor     │
│                                           enabled)          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Button States**:

| State | Appearance | Behavior |
|-------|------------|----------|
| Ready | "+ Screen" with icon | Tap to add display |
| Creating | Spinner + "Adding..." | Disabled during creation |
| Max Reached | Grayed out | Tooltip: "Maximum displays reached" |
| Server Unsupported | Hidden | Multi-monitor not available |

**Implementation**:

```kotlin
// In ButtonShelfEntity.kt or new AddScreenButton.kt
class AddScreenButton(
    private val multiPanelManager: MultiPanelManager,
    private val virtualDisplayService: VirtualDisplayService
) {
    private var isCreating = false

    fun onClick() {
        if (isCreating) return
        if (multiPanelManager.getDisplayCount() >= maxDisplays) {
            showMaxDisplaysToast()
            return
        }

        isCreating = true
        updateButtonState(ButtonState.CREATING)

        scope.launch {
            val result = createVirtualDisplay()
            isCreating = false

            when (result) {
                is Success -> {
                    // Panel will be created by VirtualDisplayService callback
                    updateButtonState(ButtonState.READY)
                }
                is Failure -> {
                    showErrorToast(result.message)
                    updateButtonState(ButtonState.READY)
                }
            }
        }
    }

    private suspend fun createVirtualDisplay(): Result<Int> {
        // 1. Request virtual display from server
        val displayId = virtualDisplayService.createVirtualDisplay(
            width = settings.defaultResolution.width,
            height = settings.defaultResolution.height,
            refreshRate = settings.defaultRefreshRate
        )

        // 2. Create panel in VR space
        multiPanelManager.addDisplay(displayId)

        // 3. Start streaming to new panel
        multiStreamManager.startStream(displayId)

        return displayId
    }
}
```

### 3. Per-Panel Close Button

Each additional virtual display panel needs a close button to remove it.

**Visual Design**:

```
┌──────────────────────────────────────────────┐
│  ┌──[X]                                      │
│  │                                           │
│  │                                           │
│  │         VIRTUAL DISPLAY 2                 │
│  │         (Streaming Content)               │
│  │                                           │
│  │                                           │
│  │                                           │
│  └───────────────────────────────────────────│
│                                              │
│  Close button [X] appears on hover/gaze     │
│  Only on virtual displays (not primary)      │
└──────────────────────────────────────────────┘
```

**Close Button Behavior**:

1. User clicks [X] on virtual display panel
2. Show brief confirmation (optional, can be disabled in settings)
3. Send `RemoveVirtualDisplay` command to server
4. Server destroys virtual display (Windows moves content to other displays)
5. Stop stream for that display
6. Remove panel entity from VR scene
7. Recalculate layout for remaining panels

**Implementation**:

```kotlin
class PanelCloseButton(
    private val displayId: Int,
    private val multiPanelManager: MultiPanelManager,
    private val virtualDisplayService: VirtualDisplayService
) {
    fun onClick() {
        scope.launch {
            // 1. Stop streaming
            multiStreamManager.stopStream(displayId)

            // 2. Tell server to destroy virtual display
            virtualDisplayService.removeVirtualDisplay(displayId)

            // 3. Remove panel from VR
            multiPanelManager.removeDisplay(displayId)

            // 4. Recalculate layout
            layoutSystem.recalculateLayout()
        }
    }
}
```

### 4. Connection Handshake Protocol

**Extended Handshake Flow**:

```
┌──────────────┐                              ┌──────────────┐
│    Client    │                              │    Server    │
│  (Quest 3)   │                              │  (Sunshine)  │
└──────┬───────┘                              └──────┬───────┘
       │                                             │
       │  1. CONNECT (with capabilities)             │
       │  ─────────────────────────────────────────► │
       │  { capabilities: [MULTI_DISPLAY] }          │
       │                                             │
       │  2. CONNECT_ACK (with server caps)          │
       │  ◄───────────────────────────────────────── │
       │  { supports: [VIRTUAL_DISPLAYS],            │
       │    maxDisplays: 4,                          │
       │    driverVersion: "1.2.0" }                 │
       │                                             │
       │  3. START_STREAM (primary display)          │
       │  ─────────────────────────────────────────► │
       │                                             │
       │  4. STREAM_STARTED                          │
       │  ◄───────────────────────────────────────── │
       │                                             │
       │  ... user clicks "Add Screen" ...           │
       │                                             │
       │  5. CREATE_VIRTUAL_DISPLAY                  │
       │  ─────────────────────────────────────────► │
       │  { width: 1920, height: 1080, hz: 60 }      │
       │                                             │
       │  6. VIRTUAL_DISPLAY_CREATED                 │
       │  ◄───────────────────────────────────────── │
       │  { displayId: 2, bounds: {x,y,w,h} }        │
       │                                             │
       │  7. START_STREAM (display 2)                │
       │  ─────────────────────────────────────────► │
       │                                             │
       │  8. STREAM_STARTED (display 2)              │
       │  ◄───────────────────────────────────────── │
       │                                             │
       │  ... user closes virtual display ...        │
       │                                             │
       │  9. REMOVE_VIRTUAL_DISPLAY                  │
       │  ─────────────────────────────────────────► │
       │  { displayId: 2 }                           │
       │                                             │
       │  10. VIRTUAL_DISPLAY_REMOVED                │
       │  ◄───────────────────────────────────────── │
       │                                             │
       │  ... user disconnects ...                   │
       │                                             │
       │  11. DISCONNECT                             │
       │  ─────────────────────────────────────────► │
       │                                             │
       │        Server automatically destroys        │
       │        all virtual displays from session    │
       │                                             │
```

### 5. Disconnect Cleanup Guarantee

**Critical Requirement**: All virtual displays MUST be destroyed when session ends.

**Implementation Layers**:

```kotlin
// Layer 1: Clean disconnect (client-initiated)
class MoonlightConnectionManager {
    fun disconnect() {
        // Destroy all virtual displays before disconnecting
        virtualDisplayService.destroyAllSessionDisplays()

        // Then disconnect normally
        nvConnection.stop()
    }
}

// Layer 2: Connection lost (network failure)
// Server-side implementation required
class VirtualDisplaySessionManager {
    private val sessionDisplays = mutableMapOf<SessionId, List<DisplayId>>()

    fun onConnectionLost(sessionId: SessionId) {
        // Destroy all virtual displays for this session
        sessionDisplays[sessionId]?.forEach { displayId ->
            virtualDisplayDriver.destroyDisplay(displayId)
        }
        sessionDisplays.remove(sessionId)
    }

    // Heartbeat timeout triggers cleanup
    fun onHeartbeatTimeout(sessionId: SessionId) {
        onConnectionLost(sessionId)
    }
}

// Layer 3: Server restart/crash recovery
// On server startup, check for orphaned virtual displays
class VirtualDisplayDriver {
    fun cleanupOrphanedDisplays() {
        val activeDisplays = enumerateVirtualDisplays()
        val activeSessions = sessionManager.getActiveSessions()

        activeDisplays
            .filter { it.sessionId !in activeSessions }
            .forEach { destroyDisplay(it.id) }
    }
}
```

---

## Current Architecture Analysis

### What We Have Today

| Component | Current State | Limitation |
|-----------|--------------|------------|
| Panel System | Single `videoPanelEntity` | Hardcoded to one display |
| Resolution | Global `prefs.width/height` | No per-panel configuration |
| Positioning | `PanelPositioningSystem` | Head-forward only, single panel |
| Stream | One H.264/HEVC/AV1 stream | Unified bitrate/resolution |
| Input | Single `ControllerHandler` | No panel-aware routing |

### Key Files to Modify

| File | Purpose | Changes Needed |
|------|---------|----------------|
| [ImmersiveActivity.kt](app/src/main/java/com/example/moonlight_spatialsdk/ImmersiveActivity.kt) | Main VR activity | Multi-panel lifecycle |
| [PanelManager.kt](app/src/main/java/com/example/moonlight_spatialsdk/PanelManager.kt) | Panel parent entity | Multi-panel collection |
| [PanelPositioningSystem.kt](app/src/main/java/com/example/moonlight_spatialsdk/systems/PanelPositioningSystem.kt) | Panel placement | Layout algorithms |
| [MoonlightPanelRenderer.kt](app/src/main/java/com/example/moonlight_spatialsdk/MoonlightPanelRenderer.kt) | Surface bridge | Multi-surface routing |
| [PreferenceConfiguration.java](app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java) | Stream settings | Per-display config |

---

## Implementation Phases

### Phase 1: Multi-Panel Infrastructure (Foundation)

#### 1.1 Create Multi-Panel Manager

**New File**: `MultiPanelManager.kt`

```kotlin
data class VirtualDisplay(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val position: Vector3,
    val rotation: Quaternion,
    val scale: Float = 1.0f,
    val isVirtual: Boolean = false  // true = created by app, false = physical
)

class MultiPanelManager(private val activity: ImmersiveActivity) {
    private val displays = mutableMapOf<Int, VirtualDisplay>()
    private val panelEntities = mutableMapOf<Int, Entity>()

    fun addDisplay(config: VirtualDisplay): Entity
    fun removeDisplay(displayId: Int)
    fun updateDisplayPosition(displayId: Int, position: Vector3, rotation: Quaternion)
    fun getDisplayCount(): Int
    fun getAllDisplays(): List<VirtualDisplay>
}
```

#### 1.2 Panel Registration System Update

**Modify**: `ImmersiveActivity.kt`

- Replace single `videoPanelEntity` with `Map<Int, Entity>`
- Create dynamic panel registration for each display
- Support unique panel IDs: `R.id.display_panel_0`, `R.id.display_panel_1`, etc.

**New Resource IDs** (add to `ids.xml`):

```xml
<item name="display_panel_0" type="id"/>
<item name="display_panel_1" type="id"/>
<item name="display_panel_2" type="id"/>
<item name="display_panel_3" type="id"/>
<!-- Support up to 4 virtual displays -->
```

#### 1.3 Display Configuration Data Model

**New File**: `DisplayConfiguration.kt`

```kotlin
data class DisplayConfiguration(
    val displays: List<DisplayConfig>,
    val layoutMode: LayoutMode,
    val globalSettings: GlobalDisplaySettings
)

data class DisplayConfig(
    val id: Int,
    val resolution: Resolution,
    val refreshRate: Int,
    val physicalSizeMeters: Vector2,
    val positionOffset: Vector3,  // Relative to primary display
    val rotationOffset: Float     // Y-axis rotation in degrees
)

data class GlobalDisplaySettings(
    val primaryDisplayId: Int,
    val autoArrange: Boolean,
    val curveRadius: Float?,      // For curved arrangement
    val gapBetweenDisplays: Float
)

enum class LayoutMode {
    SIDE_BY_SIDE,           // Horizontal arrangement
    STACKED,                // Vertical arrangement
    GRID_2X2,               // 2x2 grid
    CURVED_PANORAMA,        // Arc around user
    FREE_FORM               // User-positioned
}

enum class Resolution(val width: Int, val height: Int) {
    HD_720P(1280, 720),
    FHD_1080P(1920, 1080),
    QHD_1440P(2560, 1440),
    UHD_4K(3840, 2160)
}
```

---

### Phase 2: Layout & Positioning System

#### 2.1 Multi-Panel Layout System

**New File**: `MultiPanelLayoutSystem.kt`

```kotlin
class MultiPanelLayoutSystem : SystemBase() {

    fun calculateLayout(
        headPose: Pose,
        displays: List<VirtualDisplay>,
        mode: LayoutMode,
        settings: GlobalDisplaySettings
    ): Map<Int, Pose> {
        return when (mode) {
            LayoutMode.SIDE_BY_SIDE -> calculateSideBySide(headPose, displays, settings)
            LayoutMode.STACKED -> calculateStacked(headPose, displays, settings)
            LayoutMode.CURVED_PANORAMA -> calculateCurved(headPose, displays, settings)
            LayoutMode.GRID_2X2 -> calculateGrid(headPose, displays, settings)
            LayoutMode.FREE_FORM -> getCurrentPositions(displays)
        }
    }

    private fun calculateSideBySide(
        headPose: Pose,
        displays: List<VirtualDisplay>,
        settings: GlobalDisplaySettings
    ): Map<Int, Pose> {
        // Calculate total width
        val totalWidth = displays.sumOf { it.width.toDouble() } +
                        (displays.size - 1) * settings.gapBetweenDisplays

        var currentX = -totalWidth / 2
        return displays.associate { display ->
            val x = currentX + display.width / 2
            currentX += display.width + settings.gapBetweenDisplays

            display.id to Pose(
                Vector3(x.toFloat(), headPose.t.y - 0.1f, headPose.t.z - 1.0f),
                Quaternion.lookAt(headPose.t)
            )
        }
    }

    private fun calculateCurved(
        headPose: Pose,
        displays: List<VirtualDisplay>,
        settings: GlobalDisplaySettings
    ): Map<Int, Pose> {
        val radius = settings.curveRadius ?: 2.0f
        val totalArc = Math.PI / 3  // 60 degrees total
        val arcPerDisplay = totalArc / displays.size

        return displays.mapIndexed { index, display ->
            val angle = -totalArc / 2 + arcPerDisplay * (index + 0.5)
            val x = (radius * sin(angle)).toFloat()
            val z = (radius * cos(angle)).toFloat()

            display.id to Pose(
                Vector3(x, headPose.t.y - 0.1f, headPose.t.z - z),
                Quaternion.fromAxisAngle(Vector3.UP, (-angle).toFloat())
            )
        }.toMap()
    }
}
```

#### 2.2 Gesture-Based Panel Positioning

**Enhancement**: Allow users to grab and reposition individual panels

```kotlin
// Add to each panel entity
Grabbable(enabled = true, GrabbableType.PIVOT_Y)
PanelDraggable(
    snapToGrid = false,
    minDistance = 0.3f,
    maxDistance = 5.0f,
    collisionAvoidance = true
)
```

---

### Phase 3: Server-Side Virtual Display Integration

#### 3.1 Virtual Display Driver Communication

This phase requires coordination with the PC-side Moonlight server and a virtual display driver.

**Recommended Approach**: Use [Virtual Display Driver](https://github.com/itsmikethetech/Virtual-Display-Driver) or [IddSampleDriver](https://github.com/roshkins/IddSampleDriver)

**New Protocol Extension** (custom control messages):

```kotlin
// Client -> Server: Request virtual display creation
data class CreateVirtualDisplayRequest(
    val requestId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Int,
    val name: String
)

// Server -> Client: Virtual display created
data class VirtualDisplayCreated(
    val requestId: Int,
    val displayId: Int,
    val success: Boolean,
    val errorMessage: String?
)

// Client -> Server: Remove virtual display
data class RemoveVirtualDisplayRequest(
    val displayId: Int
)
```

#### 3.2 Display Enumeration

On connection, query server for available displays:

```kotlin
interface DisplayEnumerationService {
    suspend fun getAvailableDisplays(): List<ServerDisplay>
    suspend fun createVirtualDisplay(config: VirtualDisplayConfig): Result<Int>
    suspend fun removeVirtualDisplay(displayId: Int): Result<Unit>
}

data class ServerDisplay(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val refreshRate: Int,
    val isPrimary: Boolean,
    val isVirtual: Boolean,
    val bounds: Rect  // Position in virtual desktop
)
```

---

### Phase 4: Multi-Stream Architecture

#### 4.1 Stream Multiplexing Options

**Option A: Multiple NvConnection Instances** (Recommended for initial implementation)

- Separate streams per display
- Independent bitrate control
- Higher bandwidth, simpler implementation

**Option B: Single Stream with Display Tiling**

- Server composites all displays into one stream
- Client splits frames by region
- Lower bandwidth, complex synchronization

**Option C: H.264 MVC (Multi-View Coding)**

- Single stream with multiple views
- Requires specialized encoder/decoder support
- Most efficient but limited hardware support

#### 4.2 Multi-Stream Manager

**New File**: `MultiStreamManager.kt`

```kotlin
class MultiStreamManager(
    private val activity: ImmersiveActivity
) {
    private val streams = mutableMapOf<Int, StreamConnection>()

    data class StreamConnection(
        val displayId: Int,
        val nvConnection: NvConnection,
        val decoder: MediaCodecDecoderRenderer,
        val surface: Surface
    )

    suspend fun startStreams(displays: List<VirtualDisplay>): Result<Unit>
    suspend fun stopStream(displayId: Int)
    suspend fun stopAllStreams()

    fun onStreamFrame(displayId: Int, frame: VideoFrame)
}
```

#### 4.3 Bandwidth Management

```kotlin
class BandwidthAllocator {
    fun allocateBitrate(
        totalBandwidth: Int,  // e.g., 50 Mbps
        displays: List<VirtualDisplay>
    ): Map<Int, Int> {
        // Allocate based on pixel count (resolution)
        val totalPixels = displays.sumOf { it.width * it.height }
        return displays.associate { display ->
            val pixelRatio = (display.width * display.height).toFloat() / totalPixels
            display.id to (totalBandwidth * pixelRatio).toInt()
        }
    }
}
```

---

### Phase 5: Input Routing & Coordination

#### 5.1 Per-Panel Input Handler

```kotlin
class MultiDisplayInputRouter(
    private val multiPanelManager: MultiPanelManager
) {
    fun routeInput(inputEvent: InputEvent): Int? {
        // Determine which panel the input is targeting
        val ray = calculateInputRay(inputEvent)
        return multiPanelManager.getAllDisplays()
            .find { display -> rayIntersectsPanel(ray, display) }
            ?.id
    }

    fun translateCoordinates(
        displayId: Int,
        localX: Float,
        localY: Float
    ): Pair<Int, Int> {
        // Convert panel-local coordinates to server desktop coordinates
        val display = multiPanelManager.getDisplay(displayId)
        return Pair(
            display.bounds.left + (localX * display.width).toInt(),
            display.bounds.top + (localY * display.height).toInt()
        )
    }
}
```

#### 5.2 Cross-Display Mouse Movement

Handle seamless cursor movement between virtual displays:

```kotlin
class CrossDisplayCursorManager {
    private var currentDisplayId: Int = 0
    private var cursorPosition: Point = Point(0, 0)

    fun moveCursor(deltaX: Int, deltaY: Int) {
        val newX = cursorPosition.x + deltaX
        val newY = cursorPosition.y + deltaY

        // Check if cursor should transition to adjacent display
        val currentDisplay = getDisplay(currentDisplayId)
        when {
            newX < 0 -> transitionToLeftDisplay(newX, newY)
            newX > currentDisplay.width -> transitionToRightDisplay(newX, newY)
            newY < 0 -> transitionToTopDisplay(newX, newY)
            newY > currentDisplay.height -> transitionToBottomDisplay(newX, newY)
            else -> updateCursorPosition(newX, newY)
        }
    }
}
```

---

## UI/UX Design Requirements

### New UI Components Needed

#### 1. Display Configuration Panel

**Location**: Settings menu or dedicated "Displays" panel

**Features**:

- List of connected/available displays
- "Add Virtual Display" button
- Per-display resolution selector
- Layout mode selector (side-by-side, curved, grid, free)
- Arrangement preview visualization

**Mockup**:

```
┌─────────────────────────────────────────────┐
│  DISPLAY CONFIGURATION                      │
├─────────────────────────────────────────────┤
│  Layout: [Side by Side ▼]                   │
│                                             │
│  ┌─────────┐ ┌─────────┐ ┌─────────────┐   │
│  │Display 1│ │Display 2│ │ + Add       │   │
│  │ 1080p   │ │ 1080p   │ │   Virtual   │   │
│  │ Primary │ │         │ │   Display   │   │
│  └─────────┘ └─────────┘ └─────────────┘   │
│                                             │
│  Gap Between: [──●────] 0.1m               │
│  Curve Radius: [────●──] 2.0m              │
│                                             │
│  [ Apply Layout ]  [ Reset to Default ]     │
└─────────────────────────────────────────────┘
```

#### 2. In-VR Display Arrangement UI

**Interaction Model**:

1. Enter "Arrange Mode" via button on ButtonShelf
2. All displays show grab handles and dimension labels
3. User can grab and reposition displays freely
4. Grid snapping optional
5. Exit arrangement mode to lock positions

**Visual Indicators**:

- Dashed outline showing display boundaries
- Connection lines showing cable/stream status
- Color coding: green = streaming, yellow = connecting, red = error

#### 3. Quick Display Switcher

**ButtonShelf Addition**: Display overview button

- Shows miniature previews of all displays
- Tap to bring a display to focus (center view)
- Long-press to enter arrangement mode

#### 4. Virtual Display Creation Wizard

**Flow**:

1. Click "+ Add Virtual Display"
2. Select resolution preset or custom
3. Choose refresh rate
4. Name the display (optional)
5. Confirm creation
6. Display appears in layout (position auto-calculated)

```
┌─────────────────────────────────────┐
│  CREATE VIRTUAL DISPLAY             │
├─────────────────────────────────────┤
│                                     │
│  Resolution:                        │
│  ○ 1080p (1920x1080) - Recommended │
│  ○ 1440p (2560x1440)               │
│  ○ 720p (1280x720) - Performance   │
│  ○ Custom: [    ] x [    ]         │
│                                     │
│  Refresh Rate: [60 Hz ▼]           │
│                                     │
│  Display Name: [Virtual Display 2] │
│                                     │
│  [ Cancel ]          [ Create ]     │
└─────────────────────────────────────┘
```

---

## Technical Considerations

### Performance Impact

| Configuration | Est. Bandwidth | GPU Load | Recommendation |
|---------------|---------------|----------|----------------|
| 1x 1080p @ 60fps | 15 Mbps | Low | Current default |
| 2x 1080p @ 60fps | 30 Mbps | Medium | Good for productivity |
| 3x 1080p @ 60fps | 45 Mbps | Medium-High | Requires good network |
| 2x 1440p @ 60fps | 50 Mbps | High | Power users |
| 1x 4K @ 60fps | 50 Mbps | High | Single ultrawide alternative |

### Quest 3 Limitations

- **Decoder Instances**: Quest 3 supports multiple MediaCodec decoder instances, but practical limit is ~3-4 concurrent H.264 streams
- **Memory**: Each panel surface consumes GPU memory; monitor total allocation
- **Thermal**: Multi-stream decoding generates more heat; implement thermal throttling
- **Network**: WiFi 6E recommended for >30 Mbps aggregate bandwidth

### Server Requirements

- **Virtual Display Driver**: Windows 10/11 with compatible virtual display driver
- **GPU Encoding**: Multi-stream encoding requires adequate GPU resources (RTX 2060+ recommended)
- **Sunshine/Moonlight Server**: May require modifications for multi-display support

---

## Implementation Priority & Milestones

### Milestone 1: Settings & Protocol Foundation

**Goal**: Enable multi-monitor mode and establish server communication

| Task | Component | Priority |
|------|-----------|----------|
| Add "Enable Multi-Monitor" checkbox to stream settings | Client UI | P0 |
| Define protocol messages for capability exchange | Protocol | P0 |
| Implement capability handshake during connection | Client + Server | P0 |
| Server-side virtual display driver integration | Server | P0 |
| Basic create/destroy virtual display commands | Protocol | P0 |

**Deliverable**: Client can connect with multi-monitor flag, server acknowledges capability.

### Milestone 2: Dynamic Virtual Display Lifecycle

**Goal**: "Add Screen" button creates virtual display on-demand

| Task | Component | Priority |
|------|-----------|----------|
| Add "Add Screen" button to ButtonShelf | Client UI | P0 |
| Implement `VirtualDisplayService` for server communication | Client | P0 |
| Server creates virtual display on command | Server | P0 |
| Server destroys virtual display on command | Server | P0 |
| Cleanup all virtual displays on disconnect | Server | P0 |
| Cleanup on connection loss (heartbeat timeout) | Server | P0 |

**Deliverable**: User can add/remove virtual displays dynamically during session.

### Milestone 3: Multi-Panel Rendering

**Goal**: Stream and display multiple monitors in VR

| Task | Component | Priority |
|------|-----------|----------|
| Refactor `ImmersiveActivity` for multi-panel entities | Client | P0 |
| Create `MultiPanelManager` for panel lifecycle | Client | P0 |
| Implement multi-stream decoding | Client | P0 |
| Side-by-side layout algorithm | Client | P1 |
| Per-panel close button [X] | Client UI | P0 |
| Panel positioning and spacing | Client | P1 |

**Deliverable**: Two or more displays visible and streaming simultaneously.

### Milestone 4: Input & Polish

**Goal**: Seamless interaction across displays

| Task | Component | Priority |
|------|-----------|----------|
| Per-panel input routing | Client | P1 |
| Cross-display mouse movement | Client | P1 |
| Panel grab and reposition | Client | P2 |
| Layout mode selector (side-by-side, curved, etc.) | Client UI | P2 |
| Bandwidth allocation per display | Client | P2 |
| Settings persistence for display configuration | Client | P2 |

**Deliverable**: Full multi-monitor experience with intuitive controls.

### MVP Definition

The **Minimum Viable Product** includes Milestones 1-3 with core functionality:

1. ✅ Settings checkbox to enable multi-monitor
2. ✅ "Add Screen" button on ButtonShelf
3. ✅ Dynamic virtual display creation on server
4. ✅ Dynamic virtual display destruction (close button + disconnect cleanup)
5. ✅ Two panels streaming simultaneously
6. ✅ Basic side-by-side positioning

**Not in MVP** (deferred to V1.1+):

- Curved/panoramic layouts
- Advanced layout configuration UI
- Per-display resolution selection (use default)
- Display profiles/presets
- HDR per-display

### Future Enhancements (V1.1+)

- Curved panoramic display mode
- Per-display resolution and refresh rate
- HDR support per-display
- Per-display audio routing
- Display profiles (save/load configurations)
- Ultrawide virtual display option
- Multi-user display sharing

---

## Server-Side Virtual Display Driver Requirements

### Overview

The server component must integrate with a Windows virtual display driver to dynamically create and destroy monitors. This is the most complex part of the implementation as it requires kernel-level driver interaction.

### Recommended Driver: Virtual Display Driver (IddCx)

**Repository**: [github.com/itsmikethetech/Virtual-Display-Driver](https://github.com/itsmikethetech/Virtual-Display-Driver)

This driver uses Windows Indirect Display Driver (IddCx) framework and supports:

- Runtime display creation/destruction
- Custom resolutions and refresh rates
- Multiple simultaneous virtual displays
- No reboot required for changes

### Server-Side Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     SUNSHINE SERVER                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────┐    ┌─────────────────────────────────┐    │
│  │ Moonlight       │    │ Virtual Display Manager         │    │
│  │ Protocol        │───►│                                 │    │
│  │ Handler         │    │ - Session tracking              │    │
│  └─────────────────┘    │ - Display lifecycle             │    │
│                         │ - Heartbeat monitoring          │    │
│                         └───────────┬─────────────────────┘    │
│                                     │                           │
│                                     ▼                           │
│                         ┌─────────────────────────────────┐    │
│                         │ Virtual Display Driver API      │    │
│                         │                                 │    │
│                         │ - CreateVirtualDisplay()        │    │
│                         │ - DestroyVirtualDisplay()       │    │
│                         │ - EnumerateDisplays()           │    │
│                         └───────────┬─────────────────────┘    │
│                                     │                           │
└─────────────────────────────────────┼───────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                  WINDOWS KERNEL (IddCx)                         │
├─────────────────────────────────────────────────────────────────┤
│  Virtual Display Driver (.sys)                                  │
│  - Creates virtual monitors in Windows display topology         │
│  - Provides frame buffer for each virtual display               │
│  - Reports EDID to Windows for resolution support               │
└─────────────────────────────────────────────────────────────────┘
```

### API Requirements for Virtual Display Driver

The server needs these capabilities from the virtual display driver:

```cpp
// Required API functions (C++ example)
namespace VirtualDisplayAPI {

    // Create a new virtual display with specified parameters
    // Returns display ID on success, -1 on failure
    int CreateDisplay(
        int width,          // e.g., 1920
        int height,         // e.g., 1080
        int refreshRate,    // e.g., 60
        const char* name    // e.g., "Moonlight Virtual 1"
    );

    // Destroy a virtual display by ID
    // Returns true on success
    bool DestroyDisplay(int displayId);

    // Destroy all virtual displays (for cleanup)
    void DestroyAllDisplays();

    // Get list of active virtual display IDs
    std::vector<int> EnumerateDisplays();

    // Get display bounds in virtual desktop coordinates
    RECT GetDisplayBounds(int displayId);

    // Check if driver is installed and functional
    bool IsDriverAvailable();
}
```

### Session Management on Server

```cpp
class VirtualDisplaySessionManager {
private:
    // Map of session ID to list of virtual display IDs
    std::map<std::string, std::vector<int>> sessionDisplays;

    // Heartbeat timestamps per session
    std::map<std::string, std::chrono::time_point> lastHeartbeat;

    // Heartbeat timeout (e.g., 30 seconds)
    const int HEARTBEAT_TIMEOUT_SECONDS = 30;

public:
    // Called when client requests new virtual display
    int CreateDisplayForSession(
        const std::string& sessionId,
        int width, int height, int refreshRate
    ) {
        int displayId = VirtualDisplayAPI::CreateDisplay(
            width, height, refreshRate,
            ("Moonlight " + sessionId.substr(0, 8)).c_str()
        );

        if (displayId >= 0) {
            sessionDisplays[sessionId].push_back(displayId);
        }
        return displayId;
    }

    // Called when client closes a virtual display
    void DestroyDisplayForSession(
        const std::string& sessionId,
        int displayId
    ) {
        VirtualDisplayAPI::DestroyDisplay(displayId);

        auto& displays = sessionDisplays[sessionId];
        displays.erase(
            std::remove(displays.begin(), displays.end(), displayId),
            displays.end()
        );
    }

    // Called on clean disconnect
    void OnSessionEnd(const std::string& sessionId) {
        for (int displayId : sessionDisplays[sessionId]) {
            VirtualDisplayAPI::DestroyDisplay(displayId);
        }
        sessionDisplays.erase(sessionId);
        lastHeartbeat.erase(sessionId);
    }

    // Called periodically to check for dead sessions
    void CheckHeartbeatTimeouts() {
        auto now = std::chrono::steady_clock::now();

        for (auto it = lastHeartbeat.begin(); it != lastHeartbeat.end(); ) {
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
                now - it->second
            ).count();

            if (elapsed > HEARTBEAT_TIMEOUT_SECONDS) {
                // Session timed out - cleanup virtual displays
                OnSessionEnd(it->first);
                it = lastHeartbeat.erase(it);
            } else {
                ++it;
            }
        }
    }

    // Called on server startup to clean orphaned displays
    void CleanupOrphanedDisplays() {
        // Destroy any virtual displays that exist but have no active session
        for (int displayId : VirtualDisplayAPI::EnumerateDisplays()) {
            VirtualDisplayAPI::DestroyDisplay(displayId);
        }
    }
};
```

### Installation Requirements

For users to use multi-monitor support, they need:

1. **Virtual Display Driver installed** on Windows PC
2. **Sunshine server** with multi-display protocol support (requires modification or plugin)
3. **Quest app** with multi-monitor enabled in settings

### Fallback Behavior

If the server doesn't support virtual displays:

1. Client sends `CAPABILITY_MULTI_DISPLAY` during handshake
2. Server responds without `SUPPORTS_VIRTUAL_DISPLAYS` flag
3. Client hides "Add Screen" button
4. User sees only primary display (current behavior)
5. Optionally show info toast: "Multi-monitor requires server update"

---

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| Virtual display driver unavailable | High | Clear user messaging; fallback to single display |
| Quest 3 decoder limit | High | Test early; implement fallback to single high-res stream |
| Network bandwidth | Medium | Adaptive bitrate; quality presets based on network |
| Server compatibility | High | Partner with Sunshine maintainers; provide clear requirements |
| UX complexity | Medium | Sensible defaults; progressive disclosure of advanced options |
| Input synchronization | Low | Use existing Moonlight input protocol; extend minimally |
| Orphaned virtual displays | Medium | Multiple cleanup layers (disconnect, timeout, startup) |

---

## Dependencies

### External

1. **Virtual Display Driver** (Windows)
   - [Virtual Display Driver by itsmikethetech](https://github.com/itsmikethetech/Virtual-Display-Driver)
   - Or custom IddSampleDriver implementation

2. **Moonlight Protocol Extensions**
   - Custom control messages for display enumeration
   - May require Sunshine server modifications

3. **Meta Spatial SDK Updates**
   - Verify multi-panel surface support
   - Test with SDK version 68+

### Internal

1. Refactor `ImmersiveActivity.kt` for multi-panel lifecycle
2. New layout system components
3. Extended settings/preferences storage

---

## Success Criteria

1. **Functional**: User can stream 2+ displays simultaneously
2. **Performance**: Maintain 60fps with dual 1080p displays on WiFi 6
3. **Usability**: Non-technical users can add a virtual display in <30 seconds
4. **Stability**: No crashes during display add/remove operations
5. **Compatibility**: Works with existing Moonlight servers (graceful degradation if multi-display unsupported)

---

## Appendix: File Structure After Implementation

```
app/src/main/java/com/example/moonlight_spatialsdk/
├── ImmersiveActivity.kt              (modified)
├── MoonlightPanelRenderer.kt         (modified)
├── display/
│   ├── MultiPanelManager.kt          (new)
│   ├── DisplayConfiguration.kt       (new)
│   ├── VirtualDisplayService.kt      (new)
│   └── BandwidthAllocator.kt         (new)
├── systems/
│   ├── PanelPositioningSystem.kt     (modified)
│   ├── MultiPanelLayoutSystem.kt     (new)
│   └── CrossDisplayCursorSystem.kt   (new)
├── input/
│   └── MultiDisplayInputRouter.kt    (new)
├── ui/
│   ├── DisplayConfigurationPanel.kt  (new)
│   ├── VirtualDisplayWizard.kt       (new)
│   └── DisplayArrangementOverlay.kt  (new)
└── data/
    ├── ImmersiveSettings.kt          (modified)
    └── DisplayPreferences.kt         (new)

res/values/
└── ids.xml                           (modified - new panel IDs)

res/layout/
├── display_config_panel.xml          (new)
├── virtual_display_wizard.xml        (new)
└── display_arrangement_overlay.xml   (new)
```

---

## Conclusion

Implementing multi-display and virtual monitor support for Moonlight-SpatialSDK is technically feasible given the existing Meta Spatial SDK panel system. The primary challenges are:

1. **Server-side coordination** - Virtual display creation requires Windows driver integration
2. **Multi-stream management** - Balancing bandwidth and decoder resources
3. **UX design** - Making display management intuitive in VR

The recommended approach is to start with the MVP (dual-display via side-by-side arrangement) using multiple NvConnection instances, then iterate based on user feedback and performance data.

This feature would significantly enhance the productivity use case for Moonlight-SpatialSDK, bringing it closer to feature parity with Apple Vision Pro's virtual display capabilities while leveraging the existing streaming infrastructure.
