# Sample Architecture Analysis: HybridSample vs PremiumMediaSample

## Executive Summary

This document outlines the major architectural differences between `HybridSample` and `PremiumMediaSample` to guide the Moonlight-SpatialSDK implementation for hybrid 2D/immersive video streaming.

## Key Architectural Differences

### 1. **Launch Mode Architecture**

#### HybridSample

- **2D Activity**: `PancakeActivity` (ComponentActivity)
  - Launches as default launcher with `com.oculus.intent.category.2D`
  - Simple Compose UI with button to switch to immersive
  - Panel size: 800dp × 550dp (2D window)
  - **No video content** - just UI for switching modes

- **Immersive Activity**: `HybridSampleActivity` (AppSystemActivity)
  - Launches with `com.oculus.intent.category.VR`
  - Registers a Compose panel (`R.id.hybrid_panel`) that shows same UI
  - Can return to 2D via `launchPanelModeInHome()`
  - **No video streaming** - just demonstrates mode switching

#### PremiumMediaSample

- **VR-Only**: Single `ImmersiveActivity` (extends `BaseMrukActivity`)
  - Launches directly to VR with `com.oculus.intent.category.VR`
  - **No 2D mode** - purely immersive experience
  - Advanced video streaming with ExoPlayer
  - MRUK (Mixed Reality Understanding Kit) integration
  - Advanced scaling, anchoring, lighting systems

### 2. **Video Panel Registration**

#### HybridSample

- **No video panels** - only UI panels using `PanelRegistration` with Compose

#### PremiumMediaSample

- **Dynamic Panel Registration**: Uses `SpatialActivityManager.executeOnVrActivity` to register panels at runtime
- **Two Panel Types**:
  1. `ReadableVideoSurfacePanelRegistration` - For non-DRM, readable video
  2. `VideoSurfacePanelRegistration` - For DRM or direct-to-surface rendering
- **Panel Creation**: Panels created in `ExoVideoEntity.createReadableSurfacePanel()` or `createDirectToSurfacePanel()`
- **Entity-Based**: Each video panel is an `Entity` with `Panel(id)` component

### 3. **Video Display Architecture**

#### PremiumMediaSample Video Flow

```
ExoPlayer → VideoSurfacePanelRegistration → Panel Surface → Entity with Panel Component
```

**Key Components:**

- `ExoVideoEntity`: Manages video panel lifecycle
  - Creates panel registration dynamically
  - Attaches ExoPlayer to panel surface
  - Manages panel visibility, scaling, positioning
  - Base panel size: 0.7 meters (configurable)

- `VideoSurfacePanelRegistration`:
  - `surfaceConsumer`: Receives Surface, attaches ExoPlayer
  - `settingsCreator`: Defines panel shape, display, rendering options
  - Supports both Quad and Equirect180 shapes
  - Supports stereo modes

- **Panel Settings**:

  ```kotlin
  MediaPanelSettings(
      shape = QuadShapeOptions(width, height),  // or Equirect180ShapeOptions
      display = PixelDisplayOptions(width, height),
      rendering = MediaPanelRenderOptions(
          isDRM = false,
          stereoMode = StereoMode.None,
          zIndex = 0
      ),
      style = PanelStyleOptions(themeResourceId)
  )
  ```

### 4. **MRUK (Mixed Reality Understanding Kit) Features**

#### PremiumMediaSample MRUK Integration

- **Base Class**: `BaseMrukActivity` extends `AppSystemActivity`
  - Registers `MRUKFeature` in `registerFeatures()`
  - Requests `com.oculus.permission.USE_SCENE` permission
  - Loads scene from device: `mrukFeature.loadSceneFromDevice()`

- **MRUK Systems**:
  1. **AnchorSnappingSystem**: Snaps panels to real-world surfaces
  2. **WallLightingSystem**: Projects lighting onto detected walls
  3. **HeroLightingSystem**: Dynamic lighting based on video content
  4. **HeadCheckerSystem**: Detects user head position for placement

- **Anchoring Components**:
  - `Anchorable`: Component for entities that can be anchored
  - `AnchorOnLoad`: Auto-anchor on load with distance checks
  - Uses `MRUKLabel.WALL_FACE` for wall detection

### 5. **Scaling and Interaction Systems**

#### PremiumMediaSample Advanced Features

- **Scalable System**: 
  - `AnalogScalableSystem`: Controller thumbstick scaling
  - `TouchScalableSystem`: Touch/pinch gesture scaling
  - `ScaleChildrenSystem`: Hierarchical scaling
  - Components: `Scalable`, `ScaledParent`, `ScaledChild`

- **Panel Layer Alpha**: Fade in/out transitions
- **Grabbable**: Pivot-based rotation (Y-axis)
- **Tween Engine**: Smooth animations for transitions

### 6. **Panel Management**

#### PremiumMediaSample

- **Separate Process Panels**: Home and Controls panels run in separate processes
  - `HomePanelActivity` in `:home_panel` process
  - `ControlsPanelActivity` in `:controls_panel` process
- **IPC Communication**: Uses `IPCService` for cross-process communication
- **Panel Registration**: Uses `ActivityPanelRegistration` for separate-process panels

#### HybridSample

- **In-Process Panels**: All panels in same process
- **Simple Compose Panels**: Direct Compose integration via `composePanel`

### 7. **Scene Setup**

#### HybridSample

```kotlin
override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setLightingEnvironment(...)
    scene.updateIBLEnvironment("environment.env")
    scene.enableHolePunching(true)  // Passthrough
    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
}
```

#### PremiumMediaSample

```kotlin
override fun onSceneReady() {
    super.onSceneReady()
    scene.enablePassthrough(true)
    scene.setLightingEnvironment(...)
    immersiveViewModel.initializeEntities(scene)  // Creates video entities
}
```

## Moonlight-SpatialSDK Requirements

### Current State

- ✅ Has `PancakeActivity` (2D) and `ImmersiveActivity` (VR)
- ✅ Connection UI in 2D mode (keyboard works)
- ✅ Video panel registered in immersive mode
- ❌ Video panel only works in immersive mode
- ❌ No MRUK features (anchoring, wall detection)
- ❌ No scaling/interaction systems
- ❌ No 2D video display

### Required Changes

#### 1. **Hybrid Video Display (2D + Immersive)**

- **2D Mode (`PancakeActivity`)**:
  - Register video panel in 2D activity
  - Use `VideoSurfacePanelRegistration` with smaller size for 2D window
  - Panel should appear in 2D window (800dp × 550dp default)
  - Video should stream while in 2D mode

- **Immersive Mode (`ImmersiveActivity`)**:
  - Keep existing video panel registration
  - Enhance with MRUK features (see below)
  - Larger panel size for immersive experience
  - Add scaling and interaction

#### 2. **MRUK Integration**

- Extend `ImmersiveActivity` from `BaseMrukActivity` (or implement MRUK directly)
- Register `MRUKFeature` in `registerFeatures()`
- Request `com.oculus.permission.USE_SCENE` permission
- Load MRUK scene: `mrukFeature.loadSceneFromDevice()`
- Add anchoring system for video panel

#### 3. **Advanced Features (PremiumMediaSample-style)**

- **Scaling Systems**:
  - `AnalogScalableSystem`: Controller thumbstick to scale video
  - `TouchScalableSystem`: Touch/pinch gestures
  - Register video panel entity with scalable system

- **Anchoring**:
  - Add `Anchorable` component to video panel entity
  - Add `AnchorSnappingSystem` to snap to walls
  - Auto-anchor on load with `AnchorOnLoad`

- **Lighting** (Optional):
  - `HeroLightingSystem`: Dynamic lighting from video
  - `WallLightingSystem`: Project lighting onto walls

#### 4. **Panel Registration Strategy**

**Option A: Dynamic Registration (PremiumMediaSample style)**

```kotlin
// In ImmersiveActivity or ViewModel
SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { activity ->
    activity.registerPanel(
        VideoSurfacePanelRegistration(
            MOONLIGHT_PANEL_ID,
            surfaceConsumer = { _, surface ->
                moonlightPanelRenderer.attachSurface(surface)
            },
            settingsCreator = {
                MediaPanelSettings(...)
            }
        )
    )
}
```

**Option B: Static Registration (Current approach)**

```kotlin
// In ImmersiveActivity.registerPanels()
override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        VideoSurfacePanelRegistration(...)
    )
}
```

**Recommendation**: Use Option B (static) for simplicity, but make panel size/position configurable based on mode.

#### 5. **2D Video Panel Implementation**

For 2D mode, the video panel should:

- Be registered in `PancakeActivity` (or shared between activities)
- Use smaller dimensions suitable for 2D window
- Same `VideoSurfacePanelRegistration` approach
- Panel appears in 2D window, not floating in space

**Challenge**: `VideoSurfacePanelRegistration` is typically for VR panels. For 2D, may need:

- Standard Android `SurfaceView` in Compose
- Or use panel system but with 2D-appropriate settings

## Implementation Plan

### Phase 1: Hybrid Video Display

1. ✅ Keep connection UI in `PancakeActivity` (2D)
2. ⚠️ Add video panel to `PancakeActivity` for 2D streaming
3. ✅ Keep video panel in `ImmersiveActivity` for immersive streaming
4. Ensure video connection persists when switching modes

### Phase 2: MRUK Integration

1. Add `MRUKFeature` to `ImmersiveActivity`
2. Request `USE_SCENE` permission
3. Load MRUK scene on activity start
4. Add anchoring components to video panel entity

### Phase 3: Advanced Features

1. Add scaling systems (`AnalogScalableSystem`, `TouchScalableSystem`)
2. Register video panel entity with scalable system
3. Add panel layer alpha for transitions
4. (Optional) Add lighting systems

## Key Files to Reference

### HybridSample

- `PancakeActivity.kt`: 2D activity with Compose UI
- `HybridSampleActivity.kt`: Immersive activity with panel registration
- `AndroidManifest.xml`: Dual activity configuration

### PremiumMediaSample

- `BaseMrukActivity.kt`: MRUK integration base class
- `ImmersiveActivity.kt`: Main immersive activity with systems
- `ExoVideoEntity.kt`: Video panel creation and management
- `ImmersiveViewModel.kt`: Entity lifecycle management
- `systems/`: All custom systems (scalable, anchor, lighting, etc.)

## Notes

- **Panel Registration Timing**: In PremiumMediaSample, panels are registered dynamically after scene is ready. In current Moonlight, panels are registered statically in `registerPanels()`.
- **Entity Management**: PremiumMediaSample uses Entity-Component-System pattern extensively. Moonlight currently uses simpler panel registration.
- **2D Video Challenge**: Spatial SDK panels are designed for VR. For true 2D video, may need standard Android SurfaceView or investigate if panels work in 2D mode.
