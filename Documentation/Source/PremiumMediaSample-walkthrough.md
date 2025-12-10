# PremiumMediaSample Complete Walkthrough

This document provides a comprehensive breakdown of every file in the PremiumMediaSample project, explaining what each file does, how it functions, and how the entire project works together. This mirrors the depth and structure of `Documentation/Quest 3 App Pipeline.md`.

## Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture Overview](#architecture-overview)
3. [Root-Level Files](#root-level-files)
4. [Data Models](#data-models)
5. [Entities](#entities)
6. [Activities and Lifecycle](#activities-and-lifecycle)
7. [ViewModels](#viewmodels)
8. [Panels and UI](#panels-and-ui)
9. [Systems](#systems)
10. [IPC and Services](#ipc-and-services)
11. [Events](#events)
12. [Utilities and Helpers](#utilities-and-helpers)
13. [Resources and Configuration](#resources-and-configuration)
14. [Shaders](#shaders)
15. [Complete Runtime Flow](#complete-runtime-flow)
16. [Three Video Flows Explained](#three-video-flows-explained)

---

## Project Overview

**Purpose**: PremiumMediaSample is a comprehensive demonstration of Meta Spatial SDK's immersive video playback capabilities. It showcases three different video types (rectilinear readable, equirectangular 180°, and DRM-protected), multiple viewing modes (TV, Cinema, Equirect180, Home), dynamic panel management, MRUK integration, lighting systems, and inter-process communication between panel activities and the immersive activity.

**Key Features**:

- Three distinct video playback paths (readable, direct-to-surface, DRM)
- Multiple viewing modes with state transitions
- MRUK scene integration (wall/ceiling/floor detection and anchoring)
- Dynamic lighting systems (hero lighting, wall lighting, passthrough tinting)
- Panel lifecycle management with readiness gating
- Inter-process communication for panel/immersive coordination
- Touch and analog scaling systems
- Tween-based animations for smooth transitions

---

## Architecture Overview

The application follows a multi-process architecture:

1. **Main Process**: `ImmersiveActivity` - VR immersive experience, entity management, systems
2. **Home Panel Process**: `HomePanelActivity` - Content selection UI (separate process)
3. **Controls Panel Process**: `ControlsPanelActivity` - Playback controls UI (separate process)
4. **IPC Service**: `IPCService` - Messenger-based communication hub

**Component Layers**:

- **Activities**: Lifecycle management, feature registration, system setup
- **ViewModels**: State management, business logic, entity orchestration
- **Entities**: 3D scene objects (panels, video players, cinemas)
- **Systems**: Frame-by-frame execution (ECS pattern)
- **Panels**: Compose-based UI in separate processes
- **IPC**: Cross-process messaging for coordination

---

## Root-Level Files

### Const.kt

**Purpose**: Centralized constants for spawn distances, lighting, and timing configurations.

**Key Constants**:

- `SPAWN_DISTANCE = 1f` - Default distance to spawn entities in front of user
- `MAX_SPAWN_DISTANCE = 3f` - Maximum distance for anchor snapping
- `SCREEN_FOV = 70f` - Field of view for screen sizing calculations
- `ANCHOR_SPAWN_DISTANCE = 1.5f` - Distance for anchor-based spawning
- `LIGHT_AMBIENT_COLOR = Vector3(5f)` - Ambient lighting color
- `LIGHT_SUN_COLOR = Vector3(0f)` - Sun/directional light color (disabled)
- `LIGHT_SUN_DIRECTION = -Vector3(1.0f, 3.0f, 2.0f)` - Sun direction vector
- `TIMINGS` object - Fade durations for panels and lighting (milliseconds)

**Usage**: Referenced throughout the codebase for consistent spacing, timing, and lighting values.

---

### Utils.kt

**Purpose**: Comprehensive utility functions for entity manipulation, spatial calculations, controller input, and panel management.

**Key Functions**:

**Panel Management**:

- `getDisposableID()` - Generates unique temporal IDs for panel registration (starts at 1500000, increments)
- `AppSystemActivity.unregisterPanel(panelId)` - Unregisters panel and removes from PanelCreationSystem

**Spatial Math**:

- `projectPointOntoPlane(point, planePoint, planeNormal)` - Projects a 3D point onto a plane
- `projectRayOntoPlane(rayOrigin, rayDirection, planePoint, planeNormal)` - Finds intersection of ray with plane
- `hitTestBox(point, boxCenter, boxSize, boxRotation)` - Checks if point is inside oriented bounding box
- `lookAt(forward, up, targetDirection)` - Creates quaternion rotation to face target direction
- `fromAxisAngle(axis, angle)` - Creates quaternion from axis-angle representation

**Coordinate Transforms**:

- `fromAbsoluteToLocal(globalPose, localRelativeTo)` - Converts global pose to local space relative to parent
- `setAbsolutePosition(entity, absolutePosition)` - Sets entity position in world space (handles parenting)
- `getAbsoluteTransform(entity)` - Gets world-space transform (handles TransformParent hierarchy)

**Controller Input**:

- `getControllers()` - Returns list of local Controller components
- `getAnyKeyDown()` - Checks if any controller button was pressed this frame
- `getKeyDown(buttonBitsMask)` - Checks if specific button combination was pressed
- `getKey(buttonBitsMask)` - Checks if button is currently held
- `getKeyUp(buttonBitsMask)` - Checks if button was released this frame

**Head Tracking**:

- `getHeadPose()` - Gets user's head position and rotation via AvatarAttachment query
- `placeInFrontOfHead(entity, distanceAway, offset, pivotType, angleYAxisFromHead)` - Places entity in front of head
- `getPoseInFrontOfHead(...)` - Calculates pose in front of head without applying
- `getPoseInFrontOfVector(vector, ...)` - Calculates pose in front of arbitrary pose/vector

**Size and FOV Calculations**:

- `getSize(entity)` - Gets entity size from PanelDimensions and Scale components
- `setSize(entity, size)` - Sets entity size via Scale component
- `getFovFromSize(size, distance, basedOnWidth)` - Calculates FOV from size and distance
- `setDistanceAndSize(entity, size, distance, ...)` - Sets entity position and size based on distance
- `updateSizeFromFov(entity, fov, ...)` - Updates entity size to match desired FOV
- `getSizeFromFov(entity, distance, fov, ...)` - Calculates size needed for FOV at distance
- `setDistanceFov(entity, distance, fov, ...)` - Sets both distance and size to achieve FOV

**Mesh Generation**:

- `quadTriangleMesh(width, height, material, xSubDivisions, ySubdivisions)` - Creates subdivided quad mesh for lighting systems

**Time Conversion**:

- `Int.millisToFloat()` - Converts milliseconds to seconds (for tween durations)

**Usage**: These utilities are used extensively throughout the codebase for spatial calculations, entity positioning, input handling, and panel management.

---

### SurfaceUtil.kt

**Purpose**: Utility for painting a black frame to a Surface before video playback begins, preventing white flash.

**Key Function**:

- `paintBlack(surface: Surface)` - Creates temporary EGL context, clears surface to black, swaps buffers, destroys context

**Implementation Details**:

- Uses EGL10 API to create temporary OpenGL ES 2.0 context
- Config: 8-bit RGBA, no depth/stencil
- Clears with `GLES20.glClearColor(0f, 0f, 0f, 1f)`
- Swaps buffers to display black frame
- Cleans up EGL resources immediately after

**Usage**: Called in `ExoVideoEntity` surface consumers before attaching ExoPlayer surface to prevent white overlay flash.

---

## Data Models

### data/Description.kt

**Purpose**: Simple data class for media item title and description text.

**Structure**:

```kotlin
data class Description(var title: String, var description: String) : Serializable
```

**Usage**: Used in `HomeItem` to provide metadata for video selection UI.

---

### data/HomeItem.kt

**Purpose**: Represents a selectable media item in the home panel.

**Structure**:

- `id: String` - Unique identifier
- `description: Description?` - Optional title/description
- `thumbId: Int` - Drawable resource ID for thumbnail
- `media: MediaSource` - Video source configuration
- `showInMenu: Boolean` - Whether to display in home panel

**Usage**: Populates home panel carousel; selected item triggers video playback.

---

### data/MediaSource.kt

**Purpose**: Comprehensive configuration for video playback, including source, shape, stereo mode, dimensions, and rendering options.

**Key Properties**:

- `videoSource: VideoSource` - Raw resource ID or URL (with optional DRM license)
- `stereoMode: StereoMode` - None, LeftRight, or UpDown
- `videoShape: VideoShape` - Rectilinear or Equirect180
- `videoDimensionsPx: Size` - Video resolution in pixels
- `position: Long` - Resume position in milliseconds
- `mips: Int` - Mipmap levels for readable surfaces
- `audioCodecType: AudioCodecType` - Audio codec type (currently None)

**Computed Properties**:

- `aspectRatio: Float` - Calculates aspect ratio accounting for stereo mode (divides width/height by 2 for side-by-side/top-bottom)

**Helper Methods**:

- `isRemote()` - Checks if video source is HTTP/HTTPS URL
- `isStream()` - Checks if video source is DASH manifest (.mpd)

**Nested Types**:

- `VideoSource` sealed class:
  - `Raw(videoRaw: Int)` - Android resource ID
  - `Url(videoUrl: String, drmLicenseUrl: String?)` - URL with optional DRM license
- `VideoShape` enum: `Rectilinear`, `Equirect180`
- `AudioCodecType` enum: `None` (placeholder)

**Usage**: Passed to `ExoVideoEntity.create()` to configure video panel registration and playback.

---

### data/Size.kt

**Purpose**: Simple 2D size representation with conversion to Vector2.

**Structure**:

- `x: Int, y: Int` - Width and height in pixels
- `toVector2(): Vector2` - Converts to Spatial SDK Vector2

**Usage**: Used in `MediaSource` for video dimensions and aspect ratio calculations.

---

### data/PoseAndSize.kt

**Purpose**: Combines pose (position + rotation) and size for entity state snapshots.

**Key Methods**:

- `fromEntity(entity)` - Creates PoseAndSize from entity's absolute transform and size
- `applyToEntity(entity, forcedSize, keepWidthAspect)` - Applies pose and size to entity
- `copy(copyFrom, applyTo, keepWidthAspect)` - Copies pose/size from one entity to another

**Usage**: Used in `CinemaStateHandler` to save/restore TV panel position when transitioning between Cinema and TV modes.

---

### data/SceneLightingSettings.kt

**Purpose**: Defines lighting and passthrough multipliers for different cinema states and playback states.

**Key Types**:

- `CinemaState` enum: `Cinema`, `TV`, `Equirect180`, `Home`
- `LightMultipliers` data class:
  - `passthroughMultiplier: Float` - Passthrough opacity (0=opaque, 1=transparent)
  - `lightingMultiplier: Float` - Hero lighting intensity (0=off, 1=full)
- `SceneLightingSettings` data class:
  - `playingLighting: LightMultipliers` - Settings when video is playing
  - `pausedLighting: LightMultipliers` - Settings when video is paused

**Predefined Multipliers**:

- `CINEMA_PLAYING`: passthrough=0 (opaque), lighting=1 (full)
- `CINEMA_PAUSED`: passthrough=0.1 (mostly opaque), lighting=1
- `TV_PLAYING`: passthrough=0.25, lighting=0.5
- `TV_PAUSED`: passthrough=1 (transparent), lighting=0.5
- `EQUIRECT_180_PLAYING`: passthrough=0.25, lighting=0.5
- `EQUIRECT_180_PAUSED`: passthrough=1, lighting=0.5
- `HOME_PLAYING/PAUSED`: passthrough=0.5, lighting=0 (off)

**Usage**: Referenced by `LightingPassthroughHandler` to determine lighting/passthrough values for state transitions.

---

### data/debug/DebugItem.kt (and subclasses)

**Purpose**: Base class and implementations for debug panel UI elements.

**Hierarchy**:

- `DebugItem` (abstract base) - Serializable base class
- `DebugButtonItem(label, onClick)` - Button with click handler
- `DebugSliderItem(label, initialValue, onValueChanged, range, steps, roundToInt)` - Slider control
- `DebugToggleItem(label, initialValue, onValueChanged)` - Toggle switch
- `DebugEnumItem(label, initialValue, onValueChanged)` - Enum selector slider
- `DebugStringArrayItem(label, values, initialValue, onValueChanged)` - String array selector
- `DebugLabelItem(label)` - Static label (no interaction)
- `DebugData(items: MutableList<DebugItem>)` - Container for debug panel items

**Usage**: Used by `DebugControlsEntity` to create runtime-adjustable controls for cinema FOV, angle, and distance.

---

## Entities

### entities/FadingPanel.kt

**Purpose**: Abstract base class for entities that support fade in/out animations using `PanelLayerAlpha`.

**Key Features**:

- Manages `TweenPanelLayerAlpha` tween instances
- Provides `fadeVisibility()` for animated transitions
- Provides `setVisible()` for instant visibility changes
- Automatically manages `Visible` component and `PanelLayerAlpha` synchronization

**Implementation**:

- Uses `TweenEngineSystem` to animate `PanelLayerAlpha.layerAlpha` from current to target (0 or 1)
- Sets `Visible(true)` before fade-in, `Visible(false)` after fade-out
- Cancels previous tween if new fade is requested

**Usage**: Extended by `HomePanelEntity`, `ControlsPanelEntity`, and `ExoVideoEntity` for consistent fade behavior.

---

### entities/ExoPlayerExt.kt

**Purpose**: Extension functions and utilities for ExoPlayer configuration, media source setup, and analytics.

**Key Functions**:

**Player Creation**:

- `buildCustomExoPlayer(context, isRemote)` - Creates ExoPlayer with custom configuration:
  - Forces MediaCodec asynchronous queueing
  - Enables decoder fallback
  - For remote sources: custom buffer durations (10s min, 30s max, 1s start, 2s resume)

**Media Source Setup**:

- `ExoPlayer.setMediaSource(mediaItem, context)` - Sets media source from `MediaSource`:
  - Handles `VideoSource.Raw` (Android resources)
  - Handles `VideoSource.Url` (HTTP/HTTPS, DASH manifests, DRM)
- `ExoPlayer.setMediaSource(uri, context, licenseServer, position)` - Low-level setup:
  - Detects `.mpd` files for DASH manifest handling
  - Configures DRM (Widevine) if license server provided
  - Sets resume position if provided

**Analytics**:

- `ExoPlayer.addAnalyticLogs(logId)` - Adds comprehensive analytics listener:
  - Video size changes
  - Dropped frames
  - Playback state changes
  - Track selection changes
  - Codec initialization (audio/video)
  - Error logging (codec errors, DRM errors, player errors)

**Quality Control**:

- `ExoPlayer.setHighQuality()` - Selects highest quality track in first track group
- `ExoPlayer.getLastTrackIndex(groupIndex)` - Gets index of last (highest quality) track
- `ExoPlayer.setQualityForTrackGroup(groupIndex, trackIndex)` - Sets specific track selection override

**Usage**: Used by `ExoVideoEntity` to configure and manage ExoPlayer instances.

---

### entities/ExoVideoEntity.kt

**Purpose**: Creates and manages video playback entities with support for direct-to-surface and readable rendering paths.

**Key Features**:

- Two registration paths: `VideoSurfacePanelRegistration` (direct, DRM-capable) and `ReadableVideoSurfacePanelRegistration` (readable, post-processing)
- Supports rectilinear (quad) and equirectangular 180° shapes
- Handles stereo modes (None, LeftRight, UpDown)
- Links ExoPlayer audio to SpatialAudio via `AudioSessionId`
- Fade in/out via `FadingPanel` base class
- Control panel polling integration

**Creation Flow**:

1. `ExoVideoEntity.create()` determines rendering style:
   - Direct-to-surface if DRM enabled OR non-rectilinear shape
   - Readable if rectilinear non-DRM (enables mips/post-processing)
2. Creates appropriate panel registration with `surfaceConsumer`
3. In `surfaceConsumer`: paints black, sets media source, prepares player, attaches surface, links audio
4. Creates entity with `Panel(id)`, `Transform`, `Visible(false)`, `PanelLayerAlpha(0f)`
5. For rectilinear: adds `PanelDimensions`, `Anchorable`, `AnchorOnLoad`, `Grabbable`, `ScaledParent`, `Scale`, `Scalable`, optional `HeroLighting`

**Panel Registration Paths**:

**Direct-to-Surface** (`VideoSurfacePanelRegistration`):

- Highest performance
- Supports DRM (Widevine)
- Required for Equirect180 (no readable path for 180°)
- Settings: `MediaPanelSettings` with shape (Quad or Equirect180), pixel display, render options (stereo, DRM flag, zIndex)

**Readable** (`ReadableVideoSurfacePanelRegistration`):

- Enables mipmap generation and post-processing
- Required for `HeroLighting` shader effects
- Lower performance than direct
- Settings: `ReadableMediaPanelSettings` with mips count, stereo mode

**Audio Integration**:

- Listens for ExoPlayer `STATE_READY`
- Registers audio session ID with `SpatialAudioFeature`
- Determines `AudioType` from channel count (MONO, STEREO, SOUNDFIELD)
- Sets `AudioSessionId` component on panel entity

**Lifecycle Methods**:

- `showPlayer(onShowComplete)` - Fades in panel, starts playback, begins control polling
- `hidePlayer(onHideComplete)` - Pauses playback, stops polling, fades out panel
- `togglePlay(isPlaying)` - Play/pause with auto-restart if near end
- `destroy()` - Unregisters panel, destroys entity, clears surface, stops polling

**Usage**: Created by `ImmersiveViewModel.createExoPanel()` when user selects media item from home panel.

---

### entities/HomePanelEntity.kt

**Purpose**: Manages the home panel entity for content selection UI.

**Key Features**:

- `ActivityPanelRegistration` linking to `HomePanelActivity` (separate process)
- Quad shape panel with dp-per-meter display scaling
- Transparent theme
- Anchorable and anchor-on-load for MRUK integration
- Grabbable with PIVOT_Y rotation
- Fade visibility support

**Entity Components**:

- `Panel(R.id.HomePanel)` - Links to registered panel
- `PanelDimensions(Vector2(WIDTH_IN_METERS, HEIGHT_IN_METERS))` - Physical size
- `Transform()` - Initial transform (positioned later)
- `Anchorable(0.02f)` - Can snap to MRUK planes with 2cm offset
- `AnchorOnLoad(distanceCheck, scaleProportional)` - Auto-anchors on load, scales proportionally
- `Visible(false)` - Starts hidden
- `PanelLayerAlpha(0f)` - Starts fully transparent
- `Grabbable(PIVOT_Y)` - Can be grabbed and rotated around Y-axis

**Panel Registration**:

- `ActivityPanelRegistration` with `HomePanelActivity::class.java`
- `UIPanelSettings`: quad shape, dp-per-meter display (1178 dp/m), transparent theme

**Fade Methods**:

- `fadeVisibility(isVisible, onComplete)` - Fades panel in/out with Circle easing

**Usage**: Created in `ImmersiveViewModel.initializeEntities()`, shown when transitioning to Home state.

---

### entities/ControlsPanelEntity.kt

**Purpose**: Manages the controls panel entity with dynamic parenting to video entities.

**Key Features**:

- Can be parented to video entity (attached below) or detached (positioned independently)
- Uses `ScaleChildrenSystem` to maintain relative positioning when parent scales
- Supports cinema mode positioning (in front of user, relative to video)
- Debug-adjustable FOV, angle, and distance for cinema mode
- Fade visibility support

**Entity Components**:

- `Panel(R.id.ControlsPanel)` - Links to registered panel
- `PanelDimensions(Vector2(WIDTH_IN_METERS, HEIGHT_IN_METERS))` - Physical size
- `Transform()` - Initial transform
- `PanelLayerAlpha(0f)` - Starts transparent
- `Visible(false)` - Starts hidden
- `TransformParent(Entity.nullEntity())` - Initially unparented

**Parenting System**:

- `attachToEntity(target)` - Parents to video entity:
  - Gets `PanelDimensions` from target to calculate offset
  - Positions below video: `offset.y = -panelDimensions.dimensions.y * 0.5f`
  - Sets `TransformParent` to target
  - Adds `ScaledChild` component for `ScaleChildrenSystem`
  - Forces scale children update
- `detachFromEntity()` - Unparents:
  - Disables `ScaledChild`
  - Saves global position
  - Sets `TransformParent` to null entity
  - Restores global transform

**Cinema Mode**:

- `movePanelForCinema(videoEntity)` - Positions panel in front of user:
  - Calculates direction from head to video
  - Uses `setDistanceFov()` with debug-adjustable FOV, angle, distance
  - Matches video rotation
  - Stores video entity reference for updates

**Debug Variables** (modifiable via debug panel):

- `controlsFov: Float = 33f` - Field of view for cinema positioning
- `controlsAngle: Float = 24f` - Vertical angle offset
- `controlsDistance: Float = 1.25f` - Distance from head

**Usage**: Created in `ImmersiveViewModel.initializeEntities()`, attached/detached based on cinema state and video shape.

---

### entities/VRCinemaEntity.kt

**Purpose**: Creates a virtual cinema room with walls/ceiling/floor for immersive viewing experience.

**Key Features**:

- Creates 6-sided room (or floor-only mode) with custom mesh materials
- Uses MRUK mesh naming convention for wall lighting integration
- Configurable screen size, distances, and padding
- Can position relative to user or TV position
- Visibility toggling

**Configuration** (`VRCinemaConfig`):

- `screenSize: Vector2` - Physical screen dimensions (default 22m × 12.375m)
- `distanceToScreen: Float` - Distance from user to screen (default 15m)
- `distanceToWallBehindYou: Float` - Distance from user to back wall (default 2m)
- `distanceBehindScreen: Float` - Screen offset from back wall (default 0.5m)
- `screenPadding: Float` - Padding around screen (default 0.65m)
- `floorOnly: Boolean` - If true, only creates floor plane (default false)
- Computed `cinemaSize: Vector3` - Full room dimensions

**Entity Structure**:

- Root entity with `Transform` (positioned via config)
- Child plane entities for each face:
  - Uses mesh URI: `"mesh://WallLightingSystem_" + MRUKLabel.WALL_FACE`
  - `Mesh`, `Transform`, `Hittable(NoCollision)`, `Scale`, `TransformParent`, `Visible`
  - Faces: Up (ceiling), Down (floor), Right, Left, Forward (back wall), Backward (front wall)

**Positioning Methods**:

- `setCinemaPoseRelativeToUser(headPose)` - Positions cinema in front of user:
  - Distance: `(cinemaSize.z * 0.5f) - distanceToWallBehindYou`
  - Height offset: `screenSize.y / 6f` (head at 1/3 from bottom)
- `setCinemaPoseRelativeToTV(tvPose, userPose)` - Positions relative to TV and user:
  - Similar to user-relative but accounts for TV position
  - Adds user-to-TV offset
- `getScreenPose()` - Returns pose for screen position:
  - At back wall position: `cinemaSize.z * 0.5f - distanceBehindScreen`
  - Uses cinema rotation

**Visibility**:

- `setVisible(visible)` - Toggles visibility of all plane entities

**Usage**: Created by `CinemaStateHandler` when transitioning to Cinema state, positioned based on video entity location.

---

### entities/ImageBoxEntity.kt

**Purpose**: Factory for creating flat image box entities (used for corner handles in scaling system).

**Key Features**:

- Creates flat quad using `Box` component with zero depth
- Applies Android drawable resource as texture
- Unlit material for UI elements

**Structure**:

- `Mesh(Uri.parse("mesh://box"))` - Uses built-in box mesh
- `Box(Vector3(-hWidth, -hHeight, 0f), Vector3(hWidth, hHeight, 0f))` - Flat box (z=0)
- `Material` with `baseTextureAndroidResourceId`, `alphaMode=1`, `unlit=true`
- Accepts vararg `ComponentBase` for additional components (Transform, Visible, etc.)

**Usage**: Used by `TouchScalableSystem` to create corner handle indicators for scaling UI.

---

### entities/DebugControlsEntity.kt

**Purpose**: Creates debug panel entity for runtime adjustment of cinema controls positioning.

**Key Features**:

- Dynamic panel registration with Compose UI
- Debug sliders for FOV, angle, and distance
- Exit button to return to home

**Panel Registration**:

- Uses `getDisposableID()` for unique panel ID
- `PanelRegistration` with Compose content
- Config: 80% screen fraction, dynamic height based on item count, 45% width, 600 DPI, transparent theme
- `DebugPanel(debugData)` Compose function renders UI

**Debug Schema**:

- `DebugSliderItem` for controls FOV (10-80°, int)
- `DebugSliderItem` for controls angle (0-50°, int)
- `DebugSliderItem` for controls distance (0.25-4m)
- `DebugButtonItem` for "Exit to Menu"

**Entity**:

- `Panel(id)`, `Transform()`, `Grabbable(PIVOT_Y)`

**Usage**: Created in debug builds by `ImmersiveViewModel.initializeEntities()`, positioned in front of head when head tracking available.

---

## Activities and Lifecycle

### immersive/BaseMrukActivity.kt

**Purpose**: Base class providing MRUK (Mixed Reality Understanding Kit) feature integration and scene loading.

**Key Features**:

- Registers `MRUKFeature` and `VRFeature`
- Handles `USE_SCENE` permission request
- Loads MRUK scene from device on permission grant
- Provides override point `onLoadedMrukScene()` for subclasses

**Lifecycle**:

1. `registerFeatures()` - Registers `MRUKFeature` and `VRFeature`
2. `onCreate()` - Checks `USE_SCENE` permission, requests if needed
3. `onRequestPermissionsResult()` - On grant, calls `loadMrukScene()`
4. `loadMrukScene()` - Calls `mrukFeature.loadSceneFromDevice()`, on success calls `onLoadedMrukScene()`

**Permission Handling**:

- Requests `com.oculus.permission.USE_SCENE`
- Logs grant/denial
- Only proceeds with scene loading after permission granted

**Usage**: Extended by `ImmersiveActivity` to provide MRUK scene understanding for anchoring and wall detection.

---

### immersive/ImmersiveActivity.kt

**Purpose**: Main immersive VR activity managing the entire VR experience, systems, entities, and IPC coordination.

**Key Responsibilities**:

- Feature registration (MRUK, VR, Compose, SpatialAudio, debug tools)
- System registration (tween, pointer, scaling, lighting, anchoring, panel management)
- Component registration (custom ECS components)
- Scene configuration (passthrough, lighting environment)
- IPC message handling (home panel, control panel commands)
- HMD lifecycle (pause/resume on mount/unmount)
- Panel registration (home and controls panels)

**Feature Registration** (`registerFeatures()`):

- Inherits from `BaseMrukActivity`: `MRUKFeature`, `VRFeature`
- Adds: `OVRMetricsFeature` (with `HeapMetrics`), `ComposeFeature`, `SpatialAudioFeature`
- Debug builds: `CastInputForwardFeature`, `DataModelInspectorFeature`

**System Registration** (`onCreate()`):

1. Binds IPC service connection
2. Initializes `NetworkedAssetLoader` with OkHttp fetcher
3. Unregisters `LocomotionSystem` (prevents controller movement)
4. Hides controllers and hands via `AvatarSystem`
5. Registers component types: `Scalable`, `ScaledParent`, `ScaledChild`, `Anchorable`, `AnchorOnLoad`, `HeroLighting`, `ReceiveLighting`, `PanelLayerAlpha`
6. Registers systems:
   - `TweenEngineSystem` - Animation engine
   - `PointerInfoSystem` - Raycast hover detection
   - `AnalogScalableSystem` - Analog stick scaling
   - `ScaleChildrenSystem` (late) - Child scale synchronization
   - `HeadCheckerSystem` - Waits for head tracking
   - `HeroLightingSystem` - Dynamic lighting from video
   - `AnchorSnappingSystem` - MRUK plane snapping
   - `TouchScalableSystem` - Touch-based scaling
   - `WallLightingSystem` - MRUK wall lighting
   - `PanelLayerAlphaSystem` - Panel opacity management
   - `PanelReadySystem` - Panel readiness gating

**Scene Configuration** (`onSceneReady()`):

- Enables passthrough: `scene.enablePassthrough(true)`
- Sets lighting environment: ambient color, sun color/direction from `Const.kt`
- Calls `immersiveViewModel.initializeEntities(scene)`

**IPC Message Handling** (`handleIPCMessage()`):

- `HOME_PANEL_CONNECTED` - Triggers `onHomePanelDrawn()` when Compose finishes
- `HOME_PANEL_SELECT_ITEM` - Extracts `HomeItem`, calls `playHomeItem()`
- `CONTROL_PANEL_CLOSE_PLAYER` - Transitions to home
- `CONTROL_PANEL_RESTART_VIDEO` - Seeks to start
- `CONTROL_PANEL_TOGGLE_PLAY` - Play/pause toggle
- `CONTROL_PANEL_TOGGLE_MUTE` - Audio mute toggle
- `CONTROL_PANEL_SEEK_TO` - Seeks to position
- `CONTROL_PANEL_SET_PASSTHROUGH` - Updates passthrough level
- `CONTROL_PANEL_SET_LIGHTING` - Updates lighting level
- `CONTROL_PANEL_SET_CINEMA_STATE` - Changes cinema state (TV/Cinema)

**HMD Lifecycle**:

- `onVRPause()` - Pauses app (calls `pauseApp()`)
- `onVRReady()` - Resumes app (calls `resumeApp()`)
- `onHMDUnmounted()` - Pauses app
- `onHMDMounted()` - Resumes app
- `onSpatialShutdown()` - Destroys entities, unbinds IPC

**Panel Registration** (`registerPanels()`):

- Returns list of `HomePanelEntity.panelRegistration()` and `ControlsPanelEntity.panelRegistration()`

**Usage**: Main entry point for VR experience, coordinates all systems and entities.

---

### panels/homePanel/HomePanelActivity.kt

**Purpose**: Separate-process Activity hosting the home panel Compose UI for content selection.

**Key Features**:

- Runs in `:home_panel` process (isolated from immersive)
- Binds IPC service (send-only, no incoming messages)
- Sets Compose content (`HomeView`)
- Sends `HOME_PANEL_SELECT_ITEM` when user selects media
- Notifies immersive when Compose fully drawn via `reportFullyDrawn()`

**Lifecycle**:

1. `onCreate()` - Binds IPC, sets `onItemSelectedHandler` to send IPC message, sets Compose content
2. `reportFullyDrawn()` - Called by Compose `ReportDrawn()`, sends `IPCService.NOTIFY_HOME_PANEL_DRAWN`
3. `onDestroy()` - Unbinds IPC service

**IPC Communication**:

- Sends `HOME_PANEL_SELECT_ITEM` with `HomeItem` serialized in Bundle
- Sends `NOTIFY_HOME_PANEL_DRAWN` when UI ready

**Usage**: Registered as `ActivityPanelRegistration` in `ImmersiveActivity.registerPanels()`, displays content selection carousel.

---

### panels/controlsPanel/ControlsPanelActivity.kt

**Purpose**: Separate-process Activity hosting the controls panel Compose UI for playback controls.

**Key Features**:

- Runs in `:controls_panel` process (isolated from immersive)
- Binds IPC service on `CONTROL_PANEL_CHANNEL`
- Receives player state updates (~11ms polling)
- Receives control panel configuration updates
- Sends control actions (play/pause, mute, seek, passthrough, lighting, cinema state)

**Lifecycle**:

1. `onCreate()` - Binds IPC, sets Compose content (`ControlsPanel`)
2. `handleIPCMessage()` - Handles incoming IPC:
   - `IMMERSIVE_UPDATE_PLAYER_STATE` - Updates ViewModel with player state
   - `IMMERSIVE_UPDATE_CONTROL_PANEL` - Updates buttons, enables/disables sliders
3. `onDestroy()` - Unbinds IPC service

**IPC Message Codes**:

- `IMMERSIVE_UPDATE_PLAYER_STATE` - Player state (playing, buffering, muted, progress, duration)
- `IMMERSIVE_UPDATE_CONTROL_PANEL` - Cinema state, lighting enabled flag

**Control Actions** (sent via ViewModel):

- Play/pause toggle
- Mute toggle
- Seek to position
- Update passthrough level
- Update lighting level
- Set cinema state (Cinema/TV)
- Close player (return to home)

**Usage**: Registered as `ActivityPanelRegistration` in `ImmersiveActivity.registerPanels()`, displays playback controls.

---

## ViewModels

### immersive/ImmersiveViewModel.kt

**Purpose**: Central orchestration of entities, state transitions, and business logic for the immersive experience.

**Key Responsibilities**:

- Entity lifecycle management (home panel, controls panel, video entities)
- State transitions (home → playing → home)
- Cinema state coordination
- Lighting and passthrough management
- ExoPlayer lifecycle
- Control panel visibility
- Scaling system registration

**Initialization** (`initializeEntities()`):

1. Creates ExoPlayer via `buildCustomExoPlayer()`
2. Gets `TweenEngine` and `TouchScalableSystem` references
3. Creates `LightingPassthroughHandler`
4. Creates `ControlsPanelEntity` and `HomePanelEntity`
5. Creates `ControlPanelVisibilitySystem` and registers it
6. Creates debug controls panel (debug builds only)
7. Creates `CinemaStateHandler`

**Head Found** (`onHeadFound()`):

- Positions debug panel (if enabled)
- Positions home panel using `setDistanceFov()`

**Home Panel Ready** (`onHomePanelDrawn()`):

- Waits for `PanelReadySystem` to confirm panel ready
- Shows home panel with fade

**Show Home** (`showHome()`):

- Fades in home panel
- Sets cinema state to Home
- Optionally moves home to TV position (if returning from playing)

**Play Home Item** (`playHomeItem()`):

1. Fades out home panel
2. Creates `ExoVideoEntity` via `createExoPanel()`
3. Determines cinema state (TV for rectilinear, Equirect180 for 180°)
4. Waits for `PanelReadySystem` to confirm video panel ready
5. Shows player (fade in, start playback, begin control polling)
6. Attaches controls panel if rectilinear
7. Registers video entity with touch scalable system (if TV mode)
8. Sets cinema state via `CinemaStateHandler`

**Transition to Home** (`transitionToHome()`):

1. Fades lighting multiplier to 0
2. Unregisters video entity from scalable system
3. Hides scalable system UI
4. Stops control panel visibility tracking
5. Hides player (pause, stop polling, fade out)
6. Shows home panel
7. Destroys video entity

**Playback Control**:

- `togglePlay()` - Toggles playback, updates lighting
- `toggleAudioMute()` - Sets ExoPlayer volume
- `seekTo()` - Seeks ExoPlayer to position
- `restartVideo()` - Seeks to start

**Lighting/Passthrough**:

- `setPassthrough()` - Updates passthrough tint
- `setLighting()` - Updates lighting intensity

**Pause/Resume**:

- `pauseApp()` - Pauses ExoPlayer, remembers playing state
- `resumeApp()` - Resumes if was playing before pause

**Cleanup** (`destroy()`):

- Destroys controls panel, video entity, home panel

**Usage**: Central coordinator for all immersive experience logic, called by `ImmersiveActivity` IPC handlers and lifecycle methods.

---

### panels/homePanel/HomePanelViewModel.kt

**Purpose**: ViewModel for home panel UI, provides list of selectable media items.

**Key Features**:

- Defines three hardcoded media items:
  1. **Sk8 Chickens** - Rectilinear stereoscopic (LeftRight), 3840×1080 total, readable path, mips=9
  2. **Apo Island** - Equirectangular 180° stereoscopic, 5760×2880 total, direct-to-surface, mips=9
  3. **Sintel** - 4K DRM monoscopic, 3840×1636, direct-to-surface, mips=1, Widevine license
- `onItemSelectedHandler` callback set by `HomePanelActivity` to send IPC message

**Media Item Details**:

- Each `HomeItem` includes: ID, thumbnail drawable, description, `MediaSource` configuration
- `MediaSource` specifies: video URL, stereo mode, shape, dimensions, mips, DRM license (if applicable)

**Usage**: Used by `HomePanelCompose` to display media carousel, triggers IPC on selection.

---

### panels/controlsPanel/ControlsPanelViewModel.kt

**Purpose**: ViewModel for controls panel UI, manages player state and control actions.

**Key Features**:

- State flows for media state, control buttons, passthrough slider, lighting slider
- IPC message sending for all control actions
- State updates from immersive activity (player state polling)

**State Flows**:

- `mediaState: StateFlow<MediaState>` - Playing, buffering, muted, progress, duration
- `controlButtons: StateFlow<List<ControlsPanelButton>>` - Dynamic button list (Cinema/TV)
- `passthroughState: StateFlow<SliderState>` - Value, interactable, visible
- `lightingState: StateFlow<SliderState>` - Value, interactable, visible

**Control Actions** (send IPC):

- `onPlayPauseToggle()` - Sends `CONTROL_PANEL_TOGGLE_PLAY`
- `onMuteToggle()` - Sends `CONTROL_PANEL_TOGGLE_MUTE`
- `onSeekTo()` - Sends `CONTROL_PANEL_SEEK_TO`
- `onUpdatePassthrough()` - Sends `CONTROL_PANEL_SET_PASSTHROUGH`, updates local state
- `onUpdateLighting()` - Sends `CONTROL_PANEL_SET_LIGHTING`, updates local state
- `onStopWatching()` - Sends `CONTROL_PANEL_CLOSE_PLAYER`
- `updateCinemaState()` - Sends `CONTROL_PANEL_SET_CINEMA_STATE`

**State Updates** (from IPC):

- `updateState()` - Updates media state from polling data
- `passthroughSetInteractable()` - Enables/disables passthrough slider
- `lightingSetInteractable()` - Enables/disables lighting slider
- `updateControlButtons()` - Updates button list (Cinema/TV buttons)

**Usage**: Used by `ControlsPanelCompose` to display controls and send user actions to immersive activity.

---

## Panels and UI

### panels/PanelUtils.kt

**Purpose**: Shared UI utilities and composables for panel styling and interactions.

**Key Functions**:

**Shadow and Visual Effects**:

- `Modifier.drawOutlineCircularShadowGradient()` - Creates radial gradient halo effect around composable
- `Modifier.dropShadow()` - Adds blurred shadow with offset

**UI Components**:

- `FadeIcon()` - Stacks two icons with opacity fade between them
- `HoverIconButton()` - Icon button with hover halo effect
- `HoverButton()` - Generic button with hover halo effect
- `MetaButton()` - Styled button with hover shadow, Inter18 font

**Utilities**:

- `formatTime(seconds: Float)` - Formats seconds as "M:SS"

**Usage**: Used by both `HomePanelCompose` and `ControlsPanelCompose` for consistent styling.

---

### panels/homePanel/HomePanelCompose.kt

**Purpose**: Compose UI for home panel content selection carousel.

**Key Components**:

- `HomeView()` - Main container, calls `ReportDrawn()` when layout complete
- `HomeItems()` - LazyRow displaying filtered media items
- `HomeItem()` - Individual media card with thumbnail, hover effects
- `HoverContent()` - Overlay shown on hover with title, description, badges, Play button
- `SteroModeBadge()` - Displays "3D" or "2D" badge based on stereo mode
- `BadgeBox()` - Styled badge container

**Styling Constants** (`HomePanelConstants`):

- Panel dimensions: 1296×650 DP, 1178 DP per meter
- Background color: `#1C2B33`
- Item size: 400×600 DP
- Typography: Inter18 font family, various weights

**Interaction**:

- Hover detection via `hoverable()` modifier
- Play button triggers `homeViewModel.onItemSelectedHandler` (sends IPC)

**Usage**: Set as content in `HomePanelActivity`, displays media selection carousel.

---

### panels/controlsPanel/ControlsPanelCompose.kt

**Purpose**: Compose UI for controls panel playback controls.

**Key Components**:

- `ControlsPanel()` - Main container with three sections:
  1. Top row: Mute, Rewind 10s, Play/Pause, Forward 10s, Stop Watching
  2. Middle: Progress slider with time display
  3. Bottom: Passthrough slider, Lighting slider, Cinema/TV buttons
- `FadeSlider()` - Slider with icon that fades based on value
- `ControlsPanel()` overload - Stateless version for previews

**Styling Constants** (`ControlsPanelConstants`):

- Panel dimensions: 800×220 DP, 1000 DP per meter
- Background colors: `#1c2b33` (primary), `#283943` (secondary), `#0064e0` (blue)
- Disabled color: `#324047`

**State Management**:

- `isScrubbing` - Tracks if user is dragging progress slider
- `displayProgress` - Temporary progress during scrubbing
- `lightingValue`, `passthroughValue` - Local slider values

**Interaction**:

- Play/pause button shows buffering indicator
- Progress slider updates `displayProgress` during drag, seeks on release
- Passthrough/lighting sliders update via ViewModel callbacks
- Cinema/TV buttons trigger state changes

**Usage**: Set as content in `ControlsPanelActivity`, displays playback controls.

---

### panels/DebugControlsPanel.kt

**Purpose**: Compose UI for debug panel with sliders and buttons.

**Key Components**:

- `DebugPanel()` - Container with `LazyColumn` of debug items
- `DebugPanelItem()` - Routes to specific item renderer based on type
- `DebugPanelSlider()` - Slider with label and value display
- `DebugPanelEnumSlider()` - Enum selector slider
- `DebugPanelStringArray()` - String array selector slider
- `DebugPanelToggle()` - Toggle switch
- `DebugPanelButton()` - Button
- `DebugPanelLabel()` - Static label (no-op)

**Styling**:

- Background: `#303D46`
- Rounded corners: 20dp
- Item height: 40dp
- Spacing: 8dp between items

**Usage**: Used by `DebugControlsEntity` to render debug controls for cinema positioning.

---

## Systems

### systems/panelReady/PanelReadySystem.kt

**Purpose**: Ensures panels are fully ready (SceneObject available) before executing callbacks, preventing visual glitches.

**Key Features**:

- Tracks entities waiting for readiness
- Defers callback execution one frame after SceneObject is available
- Prevents visible stutter from premature visibility changes

**Execution Flow**:

1. `executeWhenReady(entity, callback)` - Registers entity and callback
2. Each frame, checks if entity has `SceneObject` via `SceneObjectSystem`
3. When `SceneObject` available, adds callback to `nextFrameQueue`
4. Next frame, executes all queued callbacks
5. Removes entity from tracking after callback queued

**Dependencies**:

- Must run before `MeshCreationSystem` (to catch mesh creation)
- Must run after `TweenEngineSystem` (to allow tween updates)

**Usage**: Called by `ImmersiveViewModel` before showing home panel and video panel to ensure rendering context is ready.

---

### systems/panelLayerAlpha/PanelLayerAlphaSystem.kt

**Purpose**: Applies `PanelLayerAlpha` component values to panel layer color scale/bias for opacity control.

**Key Features**:

- Watches for `PanelLayerAlpha` component changes using `changedSince()` API
- Gets `PanelSceneObject` from `SceneObjectSystem`
- Sets layer `colorScaleBias` alpha channel to `PanelLayerAlpha.layerAlpha`
- Uses `changedSince` to detect tween-driven changes in same frame

**Dependencies**:

- Must run before `MeshCreationSystem` and `TweenEngineSystem` (to apply before rendering)

**Usage**: Enables fade animations for panels via `FadingPanel` base class and tween system.

---

### systems/tweenEngine/TweenEngineSystem.kt

**Purpose**: Animation engine for smooth transitions of transforms, scales, materials, and panel alpha.

**Key Features**:

- Wraps Universal Tween Engine library
- Registers accessors for Spatial SDK types:
  - `TweenFloat` - Simple float values
  - `Vector3`, `Vector4` - Vector types
  - `TweenTransform` - Entity transforms (position, rotation, pose)
  - `TweenScale` - Entity scale
  - `TweenMaterial` - Material base color
  - `TweenSceneMaterial` - Scene material attributes
  - `TweenPanelLayerAlpha` - Panel opacity
  - `TweenFarPlane` - Hero lighting far plane
  - `TweenLightingPassthrough` - Lighting/passthrough multipliers
- Updates tween engine each frame with delta time
- Cancels all tweens on system destroy

**Extension Functions**:

- `Tween<T>.value(Vector3/Vector4/Quaternion/Pose/Color4)` - Convenience methods for setting target values

**Usage**: Used throughout codebase for fade animations, lighting transitions, and smooth entity movements.

**Tween Accessor Files**:

- `TweenFloat.kt` / `TweenFloatAccessor.kt` - Float value tweening
- `TweenTransform.kt` / `TweenTransformAccessor.kt` - Transform tweening (position, rotation, pose)
- `TweenScale.kt` / `TweenScaleAccessor.kt` - Scale tweening
- `TweenMaterial.kt` / `TweenMaterialAccessor.kt` - Material color tweening
- `TweenSceneMaterial.kt` / `TweenSceneMaterialAccessor.kt` - Scene material attribute tweening
- `TweenPanelLayerAlpha.kt` / `TweenPanelLayerAlphaAccessor.kt` - Panel alpha tweening
- `Vector3Accessor.kt` / `Vector4Accessor.kt` - Vector type accessors

**Tween Engine Documentation**: See `systems/tweenEngine/readme.md` for usage examples and ease equations.

---

### systems/pointerInfo/PointerInfoSystem.kt

**Purpose**: Tracks controller/hand raycast intersections with scene entities for hover detection.

**Key Features**:

- Raycasts from controller/hand forward direction (5m max distance)
- Tracks left and right pointer entities separately
- Updates every frame
- Provides `checkHover(entity)` for hover queries

**Execution**:

1. Queries all local Controller entities
2. For each active controller, gets transform and forward direction
3. Raycasts from controller position along forward direction
4. Stores intersection entity in `leftEntity` or `rightEntity` based on controller side
5. Clears entity reference if no intersection

**Helper**:

- `isRightControllerOrRightHand()` - Determines if controller is right side based on `AvatarAttachment` type

**Usage**: Used by `ControlPanelVisibilitySystem` to detect hover over controls, used by `TouchScalableSystem` for corner selection.

---

### systems/scalable/TouchScalableSystem.kt

**Purpose**: Touch-based scaling system using corner handles and controller/hand triggers.

**Key Features**:

- Creates four corner handle entities (`ImageBoxEntity`) at panel corners
- Shows/hides corners based on hover and selection
- Supports scaling via trigger drag from corner
- Auto-hides after inactivity (1.5 seconds)
- Tracks currently scaling entities

**Corner Handles**:

- Four `ImageBoxEntity` instances with `corner_round` drawable
- Positioned at panel corners, rotated to face outward
- Size: 0.1m × 0.1m
- Initially hidden, shown on entity hover

**Scaling Logic**:

1. User hovers over scalable entity (detected via `PointerInfoSystem`)
2. Corner handles appear at entity corners
3. User presses trigger while pointing at corner handle
4. System projects controller ray onto entity plane
5. Calculates distance from plane center to intersection point
6. Scales entity based on distance ratio to original corner distance
7. Updates corner handle positions as entity scales
8. Clamps scale between min (0.5×) and max (5×)

**Entity Registration**:

- `registerEntity(entity)` - Adds entity to scalable list, stores initial dimensions
- `unregisterEntity(entity)` - Removes entity, clears scaling state
- `forceHide(animate)` - Hides corners immediately or with delay

**Usage**: Registered entities can be scaled by users via corner handles. Used for video panels in TV mode.

---

### systems/scalable/AnalogScalableSystem.kt

**Purpose**: Analog stick-based scaling system for continuous scale adjustment.

**Key Features**:

- Uses thumbstick up/down for scale increase/decrease
- Supports both left and right controllers
- Scale speed configurable per entity via `Scalable.speed`
- Respects `PanelDimensions` for proportional scaling

**Execution**:

1. Checks if pointer is hovering over entity with `Scalable` component
2. Reads thumbstick button state (thumb up/down)
3. Calculates scale delta: `deltaTime * globalScaleSpeed * scalable.speed`
4. Adds/subtracts scale delta based on thumbstick direction
5. Clamps scale between min (0.5×) and max (5×)
6. Updates `Scale` component

**Button Mapping**:

- Right controller: `ButtonThumbRU` (up), `ButtonThumbRD` (down)
- Left controller: `ButtonThumbLU` (up), `ButtonThumbLD` (down)

**Usage**: Alternative to touch scaling, provides continuous scale adjustment via analog input.

---

### systems/scaleChildren/ScaleChildrenSystem.kt

**Purpose**: Synchronizes child entity positions when parent entity scales, maintaining relative positioning.

**Key Features**:

- Watches for `ScaledParent` entities with changed `Scale` components
- Finds all children with `ScaledChild` and `TransformParent` components
- Updates child transform based on parent scale and `ScaledChild.localPosition`
- Applies `pivotOffset` for pivot point adjustment

**Execution**:

1. Queries entities with `ScaledParent` and changed `Scale`
2. For each parent, finds children via `TransformParent` relationship
3. For each child with `ScaledChild` (if enabled):
   - Calculates new position: `localPosition * parentScale + pivotOffset`
   - Updates child `Transform` component

**Child Configuration**:

- `ScaledChild.localPosition` - Local position relative to parent (before scaling)
- `ScaledChild.pivotOffset` - Offset from scaled position (for pivot points)
- `ScaledChild.isEnabled` - Toggle scaling behavior

**Force Update**:

- `forceUpdateChildren(parent)` - Manually triggers update for all children

**Usage**: Used by `ControlsPanelEntity` to maintain position below video panel when video scales.

---

### systems/anchor/AnchorSnappingSystem.kt

**Purpose**: Snaps `Anchorable` and `AnchorOnLoad` entities to MRUK planes (walls, ceiling, floor).

**Key Features**:

- One-time snapping on app start for `AnchorOnLoad` entities
- Continuous snapping during grab for `Anchorable` entities
- Supports wall rotation alignment (faces wall normal)
- Supports ceiling/floor position-only snapping (no rotation)
- Distance checking for `AnchorOnLoad` (max distance from head)
- Proportional scaling option for `AnchorOnLoad`

**Initial Snapping** (`initSnapToAnchors()`):

1. Waits for MRUK planes and head tracking to be available
2. For each `AnchorOnLoad` entity:
   - Raycasts from head through entity position to find plane intersection
   - Checks distance is within `distanceCheck`
   - Calculates pose on plane (with rotation for walls)
   - Optionally scales proportionally if `scaleProportional=true`
   - Sets entity transform in local space

**Grab-Time Snapping** (`processGrabbedAnchorable()`):

1. For each grabbed `Anchorable` entity:
   - Checks if entity center is within snap zone (0.1m thick box on plane)
   - If not in box, uses raycast from head through movement offset
   - Snaps position to plane with `Anchorable.offset` normal offset
   - For walls: rotates to face wall normal (with slerp smoothing)
   - For ceiling/floor: only snaps position, preserves rotation
   - Stores rotation in map for smooth transitions

**Plane Validation**:

- Only snaps to planes with labels: `WALL_FACE`, `CEILING`, `FLOOR`
- Validates plane is in current room (via `MRUKAnchor.roomUuid`)

**Usage**: Used by home panel and video panels for automatic wall/ceiling/floor placement.

---

### systems/heroLighting/HeroLightingSystem.kt

**Purpose**: Provides dynamic lighting from video content to scene materials, creating ambient lighting effects.

**Key Features**:

- Auto-detects video texture from entities with `HeroLighting` component
- Registers materials with `ReceiveLighting` component
- Updates material attributes with video position, direction, size, and lighting parameters
- Supports custom shaders via `ReceiveLighting.customShader`
- Tracks registered materials for updates

**Texture Detection**:

- Watches for entities with changed `HeroLighting` component
- Gets `SceneObject` mesh material texture
- Sets as emissive texture for all registered materials

**Material Registration**:

- `registerMaterial(material, custom)` - Registers material for lighting updates:
  - Sets emissive texture (if available)
  - Sets stereo mode
  - Updates material attributes
- Materials can be standard (uses roughness/metallic) or custom (uses `matParams` attribute)

**Material Updates**:

- Watches for `HeroLighting` entities with changed `Transform` or `Scale`
- Extracts position, rotation (euler), and size
- Updates all registered materials with:
  - `emissiveFactor` (Vector4): position (x,y,z) and width (w)
  - `albedoFactor` (Vector4): rotation (x,y,z) and height (w)
  - `matParams` (custom) or roughness/metallic (standard): lighting alpha and far plane

**Lighting Parameters**:

- `lightingAlpha: Float` - Overall lighting intensity (0-1)
- `lightingDebugFarPlane: Float` - Far plane distance for lighting calculations

**Shader Processing**:

- If `ReceiveLighting.customShader` is set, overrides mesh default shader
- Processes materials next frame after shader is set

**Usage**: Used by video panels with `HeroLighting` component to provide dynamic ambient lighting to scene (walls, objects with `ReceiveLighting`).

---

### systems/heroLighting/WallLightingSystem.kt

**Purpose**: Creates lighting planes on MRUK-detected walls, ceiling, and floor using custom shaders.

**Key Features**:

- Spawns quad meshes on MRUK planes (walls, ceiling, floor)
- Uses custom shader (`mruk_hero_lighting`) for lighting effects
- Registers mesh creators for each plane type/direction combination
- Supports direction-based material selection (different materials for different wall directions)
- Visibility toggling for instant show/hide

**Mesh Registration**:

- For each material in `materialsMap`:
  - Creates mesh name: `"mesh://WallLightingSystem_" + label + direction`
  - Registers mesh creator with `registerMeshCreator()`
  - Creates subdivided quad mesh (4×4 subdivisions) with material
  - Registers material with `HeroLightingSystem` for lighting updates

**Plane Spawning**:

- Watches for changed `MRUKPlane` components
- Validates plane is in current room
- Gets plane normal and matches to material map direction (if specified)
- Creates entity with:
  - `Mesh` with registered mesh URI
  - `Transform` from MRUK plane
  - `Hittable(NoCollision)`
  - `Scale` from plane size
  - `TransformParent` to system (for organization)
  - `Visible` based on system visibility

**Material Map**:

- Maps `WallLightingFace` (label + optional direction) to `SceneMaterial`
- Default map includes: WALL_FACE, CEILING, FLOOR with white Display P3 material
- Custom shader: `mruk_hero_lighting` with custom attributes

**Visibility Control**:

- `transitionInstant(visible)` - Shows/hides all planes instantly
- Sets scale to 0 on X-axis when hidden (maintains Y/Z for potential animation)

**Usage**: Creates ambient lighting environment in cinema/TV modes, receives lighting from video via `HeroLightingSystem`.

---

### systems/controlPanelVisibility/ControlPanelVisibilitySystem.kt

**Purpose**: Manages controls panel visibility based on user interaction, inactivity, and scaling state.

**Key Features**:

- Auto-hides after 3 seconds of inactivity
- Shows on controller button press (if hidden or hovering)
- Hides during video scaling
- Hides when video is grabbed
- Extends active time when hovering over controls
- Supports fade and instant visibility changes

**Execution Logic**:

1. **Inactivity Check**: If visible and inactive > 3s, hide
2. **Button Press**: If button pressed:
   - If scaling video, hide immediately
   - If hovering controls or hidden, show
   - Otherwise, hide
3. **Hover Extension**: If hovering controls, reset active time
4. **Grab Detection**: If video grabbed, hide immediately

**State Management**:

- `controlsVisible: Boolean` - Current visibility state
- `controlsActiveTime: Long` - Last activity timestamp
- `wasGrabbingVideo: Boolean` - Previous grab state
- `activelyTracking: Boolean` - Whether system should respond to input

**Methods**:

- `fadeAndStartTracking()` - Shows controls with fade, enables tracking
- `fadeAndStopTracking()` - Hides controls with fade, disables tracking
- `setControlsVisibility(isVisible, fade)` - Sets visibility (fade or instant)

**Usage**: Automatically manages controls panel visibility for optimal UX, preventing UI obstruction during interaction.

---

### systems/headChecker/HeadCheckerSystem.kt

**Purpose**: Waits for head tracking to be available (non-zero position), then triggers callback and unregisters.

**Key Features**:

- Checks head position each frame
- When head position is non-zero, head tracking is available
- Calls callback and unregisters itself
- One-time execution

**Usage**: Used by `ImmersiveActivity` to delay home panel positioning until head tracking is ready.

---

### systems/metrics/HeapMetrics.kt

**Purpose**: Provides heap memory usage metrics to OVR Metrics overlay.

**Key Features**:

- Extends `OVRMetricsGroup`
- Tracks used heap memory in MB
- Updates every frame
- Displayed in OVR Metrics debug overlay

**Usage**: Registered with `OVRMetricsFeature` in `ImmersiveActivity` for performance monitoring.

---

## IPC and Services

### service/IPCService.kt

**Purpose**: Central IPC service using Android Messenger pattern for cross-process communication.

**Key Features**:

- Manages client registration per channel
- Routes messages between channels
- Handles home panel ready notification
- Supports multiple clients per channel

**Channels**:

- `IMMERSIVE_CHANNEL = 0` - Immersive activity
- `CONTROL_PANEL_CHANNEL = 1` - Controls panel activity

**Message Types**:

- `REGISTER_CLIENT` - Client registers for channel, receives reply Messenger
- `UNREGISTER_CLIENT` - Client unregisters from channel
- `PASS_MSG` - Routes message from one channel to another (arg1=recipient, arg2=message code)
- `NOTIFY_HOME_PANEL_DRAWN` - Home panel notifies it's ready, service forwards to immersive

**Client Management**:

- `clientMap: Map<Int, List<Messenger>>` - Maps channel ID to list of registered clients
- Clients register with `replyTo` Messenger for receiving messages
- Service sends to all clients in target channel

**Message Routing**:

- `sendToChannel(msg, channel)` - Sends message to all clients in channel
- Handles `RemoteException` (client crashed) by removing from map

**Usage**: Bound by all activities (immersive, home panel, controls panel) for cross-process coordination.

---

### service/IPCServiceConnection.kt

**Purpose**: Wrapper for binding to `IPCService` and sending/receiving messages.

**Key Features**:

- Handles service binding/unbinding
- Manages incoming/outgoing Messengers
- Provides convenience methods for message sending
- Auto-registers/unregisters on bind/unbind

**Initialization**:

- `IPCServiceConnection(context, handler, ipcChannel)`:
  - `handler: IPCMessageHandler?` - Optional handler for incoming messages
  - `ipcChannel: Int?` - Channel to register on (if handler provided)

**Service Binding**:

- `bindService()` - Binds to `IPCService`, auto-registers if handler provided
- `unbindService()` - Unregisters and unbinds service

**Message Sending**:

- `messageService(mWhat, mArg1, mArg2, bundle, mReplyTo)` - Low-level message sending
- `messageProcess(toProcess, messageCode, bundle)` - Convenience for `PASS_MSG` routing

**Incoming Messages**:

- Creates `IncomingHandler` Messenger if handler provided
- Handler receives messages via `handleIPCMessage(Message)`

**Usage**: Used by all activities to communicate with IPC service and other processes.

---

### service/IPCMessageHandler.kt

**Purpose**: Interface for handling incoming IPC messages.

**Key Methods**:

- `handleIPCMessage(msg: Message)` - Processes incoming message

**Implementation**: `IncomingHandler` wraps handler and executes on main looper.

**Usage**: Implemented by `ImmersiveActivity` and `ControlsPanelActivity` for message handling.

---

## Events

### events/ExoPlayerEvent.kt

**Purpose**: Custom event type for ExoPlayer lifecycle events.

**Key Features**:

- Extends `EventArgs` for Spatial SDK event system
- Event name: `"ExoPlayerEventArgs.ON_END"` - Fired when playback ends

**Usage**: Fired by `ImmersiveViewModel` when ExoPlayer reaches end, triggers lighting transition to paused state.

---

## Immersive Handlers

### immersive/CinemaStateHandler.kt

**Purpose**: Manages state transitions between viewing modes (Home, TV, Cinema, Equirect180).

**Key Responsibilities**:

- State machine for cinema modes
- Entity positioning and sizing per state
- Wall lighting visibility control
- Controls panel attachment/detachment
- VRCinemaEntity management
- Recenter functionality

**State Transitions**:

**To TV State**:

- Enables wall lighting
- Attaches controls panel to video entity
- Positions video based on previous state:
  - From Cinema: Restores last TV position or recenters
  - From TV: Recenters
  - From Home: Uses home panel position, sets FOV to `SCREEN_FOV`
  - From Equirect180: Not possible

**To Cinema State**:

- Enables wall lighting
- Creates `VRCinemaEntity` if not exists (floor-only mode)
- Positions cinema relative to user or TV
- Sets video size to cinema screen size (22m × aspect ratio)
- Positions video at cinema screen pose
- Detaches controls panel, positions in front of user
- Makes cinema visible

**To Equirect180 State**:

- Disables wall lighting
- Detaches controls panel
- Enables grabbable on controls panel
- Recenters video and controls

**To Home State**:

- Disables wall lighting
- Shows home panel
- Clears home item reference

**State Exit Handling**:

- From Cinema: Hides cinema, clears attached cinema reference
- From TV: Saves pose/size for restoration
- From Equirect180: Disables grabbable on controls
- From Home: Sends IPC to update control panel configuration

**Recenter** (`recenter()`):

- TV: Sets distance/FOV, snaps to anchor
- Cinema: Repositions cinema relative to user, updates controls
- Home: Sets distance/FOV, snaps to anchor
- Equirect180: Places video and controls in front of head

**Usage**: Called by `ImmersiveViewModel` for state transitions and recenter operations.

---

### immersive/LightingPassthroughHandler.kt

**Purpose**: Manages passthrough tinting and lighting intensity transitions.

**Key Features**:

- Passthrough LUT (Look-Up Table) tinting for opacity control
- Lighting multiplier management
- Tween-based transitions between states
- State-based lighting/passthrough values

**Passthrough Tinting**:

- `tintPassthrough(passthroughValue)` - Creates LUT for passthrough opacity:
  - LUT maps RGB values (0-15 each) to tinted values
  - Multiplies by `passthroughValue * tintMultiplier`
  - Applies LUT to scene via `scene.setPassthroughLUT()`
- `currentPassthrough: Float` - Base passthrough value (0=opaque, 1=transparent)
- `tintMultiplier: Float` - State-based multiplier (0-1)

**Lighting Management**:

- `setLighting(value)` - Sets lighting intensity via `HeroLightingSystem.lightingAlpha`
- `currentLighting: Float` - Current lighting value (0-1)
- `lightingMultiplier: Float` - State-based multiplier (0-1)

**State Transitions**:

- `transitionLighting(cinemaState, isPlaying)` - Gets multipliers from `SceneLightingSettings.lightingDefaults`
- `transitionLighting(lightingData, tweenDuration)` - Tweens passthrough and lighting multipliers
- `fadeLightingMultiplier()` - Fades lighting to 0 (for home transition)

**Tween Integration**:

- Uses `TweenLightingPassthrough` wrapper with accessors:
  - `TINT_MULTIPLIER` - Passthrough multiplier tween
  - `LIGHTING_MULTIPLIER` - Lighting multiplier tween

**Usage**: Called by `ImmersiveViewModel` and `CinemaStateHandler` for lighting/passthrough state changes.

---

### immersive/ControlPanelPollHandler.kt

**Purpose**: Periodically sends ExoPlayer state to controls panel via IPC.

**Key Features**:

- Polls ExoPlayer state every ~11ms (~90Hz)
- Sends state updates to controls panel channel
- Runs on main looper
- Start/stop control

**State Data Sent**:

- `isPlaying: Boolean` - Playback state (playing or playWhenReady)
- `isBuffering: Boolean` - Buffering state
- `isMuted: Boolean` - Volume == 0
- `progress: Float` - Current position in seconds
- `duration: Float` - Total duration in seconds

**Execution**:

- `start()` - Posts runnable to main looper, schedules next update
- `stop()` - Removes callbacks
- Runnable: Calls `updateState()`, schedules next update (11ms delay)

**IPC Message**:

- Sends to `CONTROL_PANEL_CHANNEL`
- Message code: `IMMERSIVE_UPDATE_PLAYER_STATE`
- Bundle contains all state fields

**Usage**: Started when video panel is shown, stopped when hidden. Provides real-time playback state to controls UI.

---

## Resources and Configuration

### res/values/styles.xml

**Purpose**: Defines transparent panel theme.

**Key Style**:

- `PanelAppThemeTransparent` - Transparent window theme:
  - `windowIsTranslucent = true`
  - `windowBackground = transparent`
  - `windowNoTitle = true`
  - `backgroundDimEnabled = false`

**Usage**: Applied to all panel activities to prevent white overlays.

---

### res/values/ids.xml

**Purpose**: Defines panel resource IDs.

**IDs**:

- `HomePanel` - Home panel ID
- `ControlsPanel` - Controls panel ID

**Usage**: Referenced in `Panel` components and panel registrations.

---

### components/*.xml

**Purpose**: Custom ECS component definitions for Spatial SDK.

**Components Defined**:

- `Anchorable.xml` - Float attribute `offset` (default 0f) - Offset from plane when anchoring
- `AnchorOnLoad.xml` - Float `distanceCheck` (default 1f), Boolean `scaleProportional` (default false) - Auto-anchor on load
- `HeroLighting.xml` - Boolean `isEnabled` (default true) - Enables hero lighting from entity
- `PanelLayerAlpha.xml` - Float `layerAlpha` (default 1.0f) - Panel opacity (0-1)
- `ParentFollows.xml` - Boolean `isEnabled` (default true) - Parent follows child (unused in sample)
- `ReceiveLighting.xml` - Boolean `isEnabled` (default true), String `customShader` (default ""), Boolean `hasProcessed` (default false) - Receives hero lighting
- `Scalable.xml` - Float `speed` (default 1.0f), Float `min` (default MIN_VALUE), Float `max` (default MAX_VALUE) - Scaling configuration
- `ScaledChild.xml` - Vector3 `localPosition` (default 0,0,0), Vector3 `pivotOffset` (default 0.5,1.4,1.0), Boolean `isEnabled` (default true) - Child scaling configuration
- `ScaledParent.xml` - Marker component (no attributes) - Indicates entity scales children

**Usage**: Registered in `ImmersiveActivity.onCreate()` via `componentManager.registerComponent()`.

---

### AndroidManifest.xml

**Purpose**: Application manifest with process declarations and permissions.

**Key Declarations**:

- Main activity: `ImmersiveActivity` (launcher, VR category)
- Panel activities: `HomePanelActivity` (`:home_panel` process), `ControlsPanelActivity` (`:controls_panel` process)
- Service: `IPCService` (not exported)
- Permissions: `USE_SCENE`, `HAND_TRACKING`, `USE_ANCHOR_API`, `PASSTHROUGH`, `RENDER_MODEL`, `MODIFY_AUDIO_SETTINGS`
- Features: VR headtracking (required), hand tracking (optional), passthrough (optional), virtual keyboard (optional)
- Meta-data: Passthrough splash, supported devices (Quest 2/Pro/3), hand tracking version (V2.0)

**Usage**: Defines app structure, processes, and capabilities.

---

## Shaders

### shaders/mruk_hero_lighting.vert

**Purpose**: Vertex shader for wall lighting system.

**Key Features**:

- Includes standard Spatial SDK vertex includes
- Generates hero lighting vertex data (rect space position, normal, camera position, screen size)
- Outputs clip space position

**Usage**: Used by `WallLightingSystem` materials for dynamic wall lighting.

---

### shaders/mruk_hero_lighting.frag

**Purpose**: Fragment shader for wall lighting system.

**Key Features**:

- Includes hero lighting fragment functions
- Calculates hero lighting from video
- Discards pixels if lighting alpha too low
- Outputs lit color

**Usage**: Used by `WallLightingSystem` materials for dynamic wall lighting effects.

---

## Complete Runtime Flow

### Application Startup

1. **BaseMrukActivity.onCreate()**:
   - Checks `USE_SCENE` permission
   - Requests if not granted
   - On grant: calls `loadMrukScene()`

2. **MRUK Scene Loading**:
   - `mrukFeature.loadSceneFromDevice()` loads room understanding
   - On success: calls `onLoadedMrukScene()` (no-op in base, overridden by ImmersiveActivity)

3. **ImmersiveActivity.onCreate()**:
   - Binds IPC service connection
   - Initializes `NetworkedAssetLoader`
   - Unregisters locomotion system
   - Hides controllers and hands
   - Registers all component types (Scalable, Anchorable, etc.)
   - Registers all systems (Tween, Pointer, Scaling, Lighting, etc.)

4. **ImmersiveActivity.onSceneReady()**:
   - Enables passthrough
   - Sets lighting environment
   - Calls `immersiveViewModel.initializeEntities(scene)`

5. **ImmersiveViewModel.initializeEntities()**:
   - Creates ExoPlayer
   - Gets system references (TweenEngine, TouchScalable)
   - Creates `LightingPassthroughHandler`
   - Creates `ControlsPanelEntity` and `HomePanelEntity`
   - Creates and registers `ControlPanelVisibilitySystem`
   - Creates debug panel (debug builds)
   - Creates `CinemaStateHandler`

6. **Panel Registration**:
   - `ImmersiveActivity.registerPanels()` returns Home and Controls panel registrations
   - Spatial SDK creates panel activities in separate processes
   - `HomePanelActivity` and `ControlsPanelActivity` start, bind IPC, set Compose content

7. **Home Panel Ready**:
   - `HomePanelCompose` calls `ReportDrawn()` when layout complete
   - `HomePanelActivity.reportFullyDrawn()` sends `NOTIFY_HOME_PANEL_DRAWN` IPC
   - `IPCService` forwards to `ImmersiveActivity`
   - `ImmersiveActivity` receives `HOME_PANEL_CONNECTED`
   - Calls `immersiveViewModel.onHomePanelDrawn()`

8. **Head Tracking Available**:
   - `HeadCheckerSystem` detects non-zero head position
   - Calls `onHeadFound()` callback
   - `ImmersiveViewModel.onHeadFound()` positions home and debug panels

9. **Home Panel Display**:
   - `ImmersiveViewModel.onHomePanelDrawn()` waits for `PanelReadySystem`
   - When ready, calls `showHome(true)`
   - `showHome()` fades in home panel, sets cinema state to Home

### Media Selection and Playback

1. **User Selects Media**:
   - User hovers over media item in home panel
   - Clicks "PLAY" button
   - `HomePanelViewModel.onItemSelectedHandler` triggered
   - Sends `HOME_PANEL_SELECT_ITEM` IPC with `HomeItem`

2. **ImmersiveActivity Receives Selection**:
   - `handleIPCMessage()` extracts `HomeItem`
   - Calls `immersiveViewModel.playHomeItem(homeItem)`

3. **Video Entity Creation**:
   - `playHomeItem()` fades out home panel
   - Calls `createExoPanel(mediaItem)`
   - `createExoPanel()` creates `ExoVideoEntity`:
     - Determines rendering style (direct vs readable)
     - Registers appropriate panel registration
     - Creates entity with components
   - Determines cinema state (TV for rectilinear, Equirect180 for 180°)

4. **Panel Ready Wait**:
   - `playHomeItem()` waits for `PanelReadySystem.executeWhenReady()`
   - System confirms `SceneObject` is available
   - Callback executes next frame

5. **Video Panel Display**:
   - `showPlayer()` called:
     - Starts control panel polling
     - Fades in panel via `FadingPanel.fadeVisibility()`
     - Sets `playWhenReady = true` on fade complete
   - Attaches controls panel if rectilinear
   - Registers video entity with touch scalable system (if TV mode)
   - Sets cinema state via `CinemaStateHandler`

6. **Cinema State Transition**:
   - `CinemaStateHandler.setCinemaState()`:
     - **TV Mode**: Enables wall lighting, attaches controls, positions video
     - **Cinema Mode**: Creates/spawns VRCinemaEntity, detaches controls, positions in front
     - **Equirect180 Mode**: Disables wall lighting, detaches controls, recenters
   - `LightingPassthroughHandler.transitionLighting()` tweens lighting/passthrough

### Playback Control

1. **State Polling**:
   - `ControlPanelPollHandler` sends state every ~11ms
   - `ControlsPanelActivity` receives `IMMERSIVE_UPDATE_PLAYER_STATE`
   - Updates `ControlsPanelViewModel` state flows
   - UI updates automatically via Compose state collection

2. **User Controls**:
   - User interacts with controls (play/pause, mute, seek, sliders)
   - `ControlsPanelViewModel` sends IPC messages
   - `ImmersiveActivity` receives and routes to `ImmersiveViewModel`
   - `ImmersiveViewModel` executes actions (toggle play, mute, seek, etc.)

3. **Control Panel Visibility**:
   - `ControlPanelVisibilitySystem` manages visibility:
     - Shows on button press (if hidden or hovering)
     - Hides after 3s inactivity
     - Hides during scaling or grabbing
     - Extends active time when hovering

4. **Cinema Mode Toggle**:
   - User clicks "Cinema" or "TV" button
   - Sends `CONTROL_PANEL_SET_CINEMA_STATE` IPC
   - `CinemaStateHandler` transitions state
   - Repositions video, controls, and lighting

### Shutdown

1. **User Exits**:
   - User clicks "Stop Watching" or closes app
   - `ImmersiveViewModel.transitionToHome()` called
   - Fades lighting, unregisters scalable entity, stops control tracking
   - Hides player (pause, stop polling, fade out)
   - Shows home panel
   - Destroys video entity

2. **App Shutdown**:
   - `ImmersiveActivity.onSpatialShutdown()` called
   - `ImmersiveViewModel.destroy()` destroys all entities
   - Unbinds IPC service
   - Super shutdown called

---

## Three Video Flows Explained

### Flow 1: Rectilinear Readable (Sk8 Chickens)

**Media Configuration**:

- Shape: `Rectilinear`
- Stereo: `LeftRight` (3840×1080 total, 1920×1080 per eye)
- Source: DASH URL (non-DRM)
- Mips: 9 levels
- Dimensions: 1920×1080 per eye

**Rendering Path**:

- Uses `ReadableVideoSurfacePanelRegistration` (enables mips/post-processing)
- Panel shape: `QuadShapeOptions` (aspect ratio × 0.7m height)
- Display: `PixelDisplayOptions(1920, 1080)` per eye
- Rendering: `ReadableMediaPanelRenderOptions` with mips=9, stereo=LeftRight
- Entity gets `HeroLighting` component (enables dynamic lighting)

**Playback Flow**:

1. Panel registration creates readable surface
2. ExoPlayer sets DASH media source
3. Surface attached, player prepared
4. Audio session linked to SpatialAudio
5. Panel fades in, playback starts
6. Hero lighting system extracts video texture
7. Lighting applied to walls/objects with `ReceiveLighting`
8. Controls panel attached below video
9. User can scale video via corner handles
10. Cinema/TV mode transitions available

**Why Readable**: Enables mipmap generation and shader-based post-processing (hero lighting effects).

---

### Flow 2: Equirectangular 180° (Apo Island)

**Media Configuration**:

- Shape: `Equirect180`
- Stereo: `LeftRight` (5760×2880 total, 2880×2880 per eye)
- Source: DASH URL (non-DRM)
- Mips: 9 levels (not used, direct path)
- Dimensions: 2880×2880 per eye

**Rendering Path**:

- Uses `VideoSurfacePanelRegistration` (direct-to-surface, required for 180°)
- Panel shape: `Equirect180ShapeOptions(radius=50f)` - Spherical projection
- Display: `PixelDisplayOptions(2880, 2880)` per eye
- Rendering: `MediaPanelRenderOptions` with stereo=LeftRight, `zIndex=-1` (renders behind other panels)
- Entity does NOT get `HeroLighting` (180° doesn't support readable path)

**Playback Flow**:

1. Panel registration creates direct surface with equirectangular projection
2. ExoPlayer sets DASH media source
3. Surface attached, player prepared
4. Audio session linked to SpatialAudio
5. Panel fades in, playback starts
6. Controls panel detached, positioned in front of user
7. Wall lighting disabled (no hero lighting for 180°)
8. User can grab and move video panel
9. No Cinema/TV mode (equirectangular only)

**Why Direct-to-Surface**: Equirectangular 180° projection requires direct rendering path, no readable surface support.

---

### Flow 3: DRM-Protected (Sintel)

**Media Configuration**:

- Shape: `Rectilinear`
- Stereo: `None` (monoscopic)
- Source: DASH URL with Widevine DRM license server
- Mips: 1 (not used, direct path)
- Dimensions: 3840×1636

**Rendering Path**:

- Uses `VideoSurfacePanelRegistration` (direct-to-surface, required for DRM)
- Panel shape: `QuadShapeOptions` (aspect ratio × 0.7m height)
- Display: `PixelDisplayOptions(3840, 1636)`
- Rendering: `MediaPanelRenderOptions` with `isDRM=true`, stereo=None
- Entity does NOT get `HeroLighting` (DRM requires direct path, no readable)

**Playback Flow**:

1. Panel registration creates direct surface
2. ExoPlayer sets DASH media source with DRM configuration:
   - Widevine UUID
   - License server URL
3. DRM session established, content decrypted
4. Surface attached, player prepared
5. Audio session linked to SpatialAudio
6. Panel fades in, playback starts
7. Controls panel attached below video
8. Wall lighting enabled (but no hero lighting from video)
9. User can scale video via corner handles
10. Cinema/TV mode transitions available
11. Lighting slider disabled (no hero lighting for DRM)

**Why Direct-to-Surface**: DRM-protected content requires secure direct rendering path, cannot use readable surface (security restriction).

---

## Summary

PremiumMediaSample is a comprehensive demonstration of Meta Spatial SDK's capabilities, showcasing:

**Architecture**:

- Multi-process design (immersive + panel processes)
- IPC coordination via Messenger service
- ECS pattern with custom systems and components

**Video Playback**:

- Three distinct rendering paths (readable, direct, DRM)
- Support for rectilinear and equirectangular 180° formats
- Stereo mode support (mono, side-by-side, top-bottom)
- Dynamic panel creation and lifecycle management

**User Experience**:

- Multiple viewing modes (Home, TV, Cinema, Equirect180)
- Smooth state transitions with tween animations
- MRUK integration for room-aware placement
- Dynamic lighting from video content
- Touch and analog scaling systems
- Auto-hiding controls panel

**Systems Integration**:

- Panel readiness gating
- Lighting systems (hero + wall)
- Anchoring and snapping
- Scaling and parenting
- Pointer and hover detection
- Audio spatialization

Every file in the project serves a specific purpose in this comprehensive demonstration, from low-level utilities to high-level state management, all working together to create a polished immersive video experience.
