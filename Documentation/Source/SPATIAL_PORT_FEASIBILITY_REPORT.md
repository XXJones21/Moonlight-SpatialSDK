# Moonlight Android → Meta Spatial SDK Port Feasibility Report

## Executive Summary

This report assesses the feasibility of porting Moonlight Android to Meta's Spatial SDK platform, enabling game streaming with MR (Mixed Reality) features including wall-snapping panels, scalable theater-like displays, bias lighting, and reflections. The port is **technically feasible** but requires significant architectural refactoring.

**Key Findings:**

- ✅ Core streaming functionality can be adapted to Spatial SDK panels
- ✅ MRUK anchoring system from PremiumMediaSample can be directly reused
- ✅ Hero lighting/bias lighting system is compatible with Moonlight's video texture
- ⚠️ Requires migration from Java to Kotlin for better Spatial SDK integration
- ⚠️ Single-activity architecture needs refactoring for Spatial SDK's multi-process model
- ⚠️ MediaCodec decoder integration requires adaptation to Spatial SDK's panel surface system

---

## 1. PremiumMediaSample Pattern Analysis

### 1.1 MRUK Anchoring System

**Location:** `PremiumMediaSample/app/src/main/java/com/meta/spatial/samples/premiummediasample/systems/anchor/AnchorSnappingSystem.kt`

**Key Components:**

- `AnchorSnappingSystem`: Handles wall/ceiling/floor detection and snapping
- `Anchorable` component: Marks entities that can snap to MRUK planes
- `AnchorOnLoad` component: Auto-snaps entities on app start
- Uses `MRUKPlane`, `MRUKAnchor`, and `MRUKLabel` for spatial understanding

**Reusability:** ✅ **High** - Can be directly integrated with minimal modifications

**Integration Points:**

```kotlin
// From ExoVideoEntity.kt - shows how to add anchoring to video panels
entity.setComponents(
    PanelDimensions(Vector2(panelSize.x, panelSize.y)),
    Anchorable(0.02f),  // Offset from wall
    AnchorOnLoad(scaleProportional = true, distanceCheck = MAX_SPAWN_DISTANCE + 0.5f),
    Grabbable(true, GrabbableType.PIVOT_Y),
    ScaledParent(),
    Scale(),
    Scalable(),
)
```

### 1.2 Hero Lighting / Bias Lighting System

**Location:**

- `systems/heroLighting/HeroLightingSystem.kt` - Main lighting controller
- `systems/heroLighting/WallLightingSystem.kt` - Wall mesh generation for reflections

**How It Works:**

1. `HeroLightingSystem` extracts texture from video panel (`SceneTexture`)
2. Registers materials that receive lighting (walls, floor, ceiling)
3. Updates shader uniforms with panel position, rotation, scale, and lighting intensity
4. `WallLightingSystem` creates quad meshes on MRUK-detected surfaces
5. Custom shader (`mruk_hero_lighting.frag/vert`) applies video texture as emissive lighting

**Reusability:** ✅ **High** - Works with any `SceneTexture`, compatible with Moonlight's video stream

**Key Code Pattern:**

```kotlin
// Register video panel texture for lighting
heroLightingSystem.registerMaterial(wallMaterial, custom = true)

// Update lighting intensity
heroLightingSystem.lightingAlpha = 0.5f // 0.0 to 1.0

// WallLightingSystem automatically creates meshes on MRUK planes
// and applies the registered material
```

### 1.3 Panel Scaling System

**Location:**

- `systems/scalable/AnalogScalableSystem.kt` - Controller thumbstick scaling
- `systems/scalable/TouchScalableSystem.kt` - Touch/pinch scaling with corner handles

**Features:**

- Analog scaling via controller thumbsticks (up/down)
- Touch scaling with visual corner handles
- Min/max scale constraints (default: 0.5x to 5x)
- Proportional scaling maintains aspect ratio

**Reusability:** ✅ **High** - Can be directly applied to Moonlight video panels

### 1.4 Panel Registration & Video Rendering

**Location:** `entities/ExoVideoEntity.kt`

**Two Rendering Modes:**

1. **Readable Surface Panel** (`ReadableVideoSurfacePanelRegistration`)
   - Supports custom shaders (required for hero lighting)
   - Less performant, allows texture sampling
   - Used when bias lighting is needed

2. **Direct-to-Surface Panel** (`VideoSurfacePanelRegistration`)
   - Most performant, direct MediaCodec/ExoPlayer rendering
   - Required for DRM content
   - Cannot use custom shaders

**Reusability:** ✅ **High** - Moonlight can use Direct-to-Surface for performance, or Readable for lighting

---

## 2. Moonlight Android Architecture Analysis

### 2.1 Current Architecture

**Entry Points:**

- `AppView.java` - App selection/grid view (Activity)
- `Game.java` - Main streaming activity (Activity)
- `StreamView.java` - Custom SurfaceView for video rendering

**Video Pipeline:**

```
NvConnection (JNI bridge) 
  → MediaCodecDecoderRenderer 
    → SurfaceHolder (from StreamView)
      → MediaCodec decoder
        → Renders to Surface
```

**Key Components:**

- `NvConnection.java`: Manages connection lifecycle, RTSP handshake, stream negotiation
- `MediaCodecDecoderRenderer.java`: Handles H.264/HEVC/AV1 decoding via MediaCodec
- `Game.java`: Activity that hosts StreamView, handles input, manages connection
- JNI Layer: `MoonBridge.java` → Native C++ code (`moonlight-common-c`)

**Input Handling:**

- Touch events → Virtual controller or mouse/keyboard
- USB controllers → Direct input forwarding
- Keyboard/mouse → Network packets

### 2.2 Integration Challenges

#### Challenge 1: Activity-Based Architecture

**Current:** Single Activity (`Game`) with fullscreen SurfaceView
**Spatial SDK:** Requires `AppSystemActivity` (extends `BaseMrukActivity`) with panel registrations

**Solution:**

- Create new `MoonlightImmersiveActivity` extending `BaseMrukActivity`
- Replace `StreamView` SurfaceView with `VideoSurfacePanelRegistration`
- Keep `Game.java` logic but refactor to work within Spatial SDK lifecycle

#### Challenge 2: Surface Rendering

**Current:** `SurfaceHolder` from `StreamView` passed to `MediaCodecDecoderRenderer.setRenderTarget()`
**Spatial SDK:** Panel registration provides `Surface` via callback

**Solution:**

```kotlin
// In MoonlightVideoEntity.kt (new file)
VideoSurfacePanelRegistration(
    id,
    surfaceConsumer = { panelEnt, surface ->
        // Convert Surface to SurfaceHolder or use Surface directly
        decoderRenderer.setRenderTarget(surface) // Adapt MediaCodecDecoderRenderer
    },
    settingsCreator = {
        MediaPanelSettings(
            shape = QuadShapeOptions(width, height),
            display = PixelDisplayOptions(width, height),
            // ... other settings
        )
    }
)
```

#### Challenge 3: Java → Kotlin Migration

**Current:** Entire codebase is Java
**Spatial SDK:** Kotlin-first, many samples in Kotlin

**Solution:**

- Gradual migration: Keep core Java classes, create Kotlin wrappers
- Or full migration: Convert key classes to Kotlin (recommended for long-term)

#### Challenge 4: Input Handling

**Current:** Android `OnTouchListener`, `OnKeyListener` on Activity/View
**Spatial SDK:** Controller input via `Controller` component, hand tracking, pointer system

**Solution:**

- Map Spatial SDK controller input to Moonlight's input packet format
- Use `PointerInfoSystem` for raycasting/interaction
- Forward controller button states to `ControllerHandler`

---

## 3. Port Strategy: Quest 3/Pro MR Implementation

### 3.1 Application Structure

```
Moonlight-SpatialSDK/
├── app/
│   ├── src/main/java/com/limelight/
│   │   ├── spatial/                    # NEW: Spatial SDK integration
│   │   │   ├── MoonlightImmersiveActivity.kt
│   │   │   ├── MoonlightVideoEntity.kt
│   │   │   ├── MoonlightInputHandler.kt
│   │   │   └── systems/
│   │   │       ├── MoonlightAnchorSystem.kt    # Wraps AnchorSnappingSystem
│   │   │       └── MoonlightLightingSystem.kt  # Wraps HeroLightingSystem
│   │   ├── Game.java                    # MODIFIED: Refactor for Spatial
│   │   ├── nvstream/                    # UNCHANGED: Core streaming
│   │   └── binding/                     # MODIFIED: Adapt video renderer
│   └── build.gradle                     # MODIFIED: Add Spatial SDK deps
```

### 3.2 Wall-Snapping vs Free-Floating Behavior

**Implementation:**

```kotlin
// MoonlightVideoEntity.kt
class MoonlightVideoEntity(
    private val connection: NvConnection,
    private val decoderRenderer: MediaCodecDecoderRenderer,
    enableAnchoring: Boolean = true,
    enableLighting: Boolean = true
) {
    init {
        entity = Entity.create(
            Panel(id),
            Transform(),
            PanelDimensions(Vector2(width, height)),
            Grabbable(true, GrabbableType.PIVOT_Y),
            Scale(),
            Scalable()
        )
        
        if (enableAnchoring) {
            entity.setComponents(
                Anchorable(0.02f),  // 2cm offset from wall
                AnchorOnLoad(
                    scaleProportional = true,
                    distanceCheck = 5.0f  // Max 5m to snap
                )
            )
        }
        
        if (enableLighting) {
            entity.setComponent(HeroLighting())
        }
    }
    
    fun toggleAnchoring(enabled: Boolean) {
        if (enabled && !entity.hasComponent<Anchorable>()) {
            entity.setComponents(Anchorable(0.02f), AnchorOnLoad(...))
        } else if (!enabled) {
            entity.removeComponent<Anchorable>()
            entity.removeComponent<AnchorOnLoad>()
        }
    }
}
```

**User Control:**

- Toggle via controller button or UI panel
- When anchored: Panel snaps to nearest wall/ceiling/floor
- When free-floating: Panel can be grabbed and moved freely
- Scaling works in both modes

### 3.3 Scalable Theater Panel

**Implementation:**

```kotlin
// MoonlightImmersiveActivity.kt
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Register scaling systems (from PremiumMediaSample)
    systemManager.registerSystem(AnalogScalableSystem(
        pointerInfoSystem,
        minScale = 0.5f,   // 0.5x minimum
        maxScale = 10.0f,  // 10x maximum for theater experience
        globalScaleSpeed = 1.0f
    ))
    systemManager.registerSystem(TouchScalableSystem(
        minScale = 0.5f,
        maxScale = 10.0f
    ))
    
    // Create video entity with large default size
    val videoEntity = MoonlightVideoEntity(
        connection = nvConnection,
        decoderRenderer = decoderRenderer,
        initialSize = Vector2(10.0f, 5.625f)  // ~10m wide, 16:9 aspect
    )
}
```

**Theater Experience:**

- Default size: 10m wide (adjustable)
- User can scale from 0.5x to 10x via:
  - Controller thumbsticks (analog)
  - Touch/pinch gestures (with corner handles)
- Maintains 16:9 aspect ratio
- Distance-based FOV calculation for optimal viewing

### 3.4 Bias Lighting & Reflections Pipeline

**Implementation:**

```kotlin
// MoonlightImmersiveActivity.kt
override fun onCreate(savedInstanceState: Bundle?) {
    // ... existing code ...
    
    // Register hero lighting system
    componentManager.registerComponent<HeroLighting>(HeroLighting.Companion)
    componentManager.registerComponent<ReceiveLighting>(ReceiveLighting.Companion)
    
    val heroLightingSystem = HeroLightingSystem(
        autoDetectTexture = true,  // Auto-detect video panel texture
        isProcessingShaders = true
    )
    systemManager.registerSystem(heroLightingSystem)
    
    // Register wall lighting system (creates meshes on MRUK planes)
    systemManager.registerSystem(WallLightingSystem())
    
    // Create video entity with lighting enabled
    val videoEntity = MoonlightVideoEntity(
        connection = nvConnection,
        decoderRenderer = decoderRenderer,
        enableLighting = true
    )
}

// MoonlightVideoEntity.kt - Use Readable panel for lighting
private fun createReadableVideoPanel() {
    // Must use ReadableVideoSurfacePanelRegistration for shader access
    immersiveActivity.registerPanel(
        ReadableVideoSurfacePanelRegistration(
            id,
            surfaceConsumer = { panelEnt, surface ->
                decoderRenderer.setRenderTarget(surface)
                // HeroLightingSystem will auto-detect this texture
            },
            settingsCreator = {
                ReadableMediaPanelSettings(
                    shape = QuadShapeOptions(width, height),
                    rendering = ReadableMediaPanelRenderOptions(
                        mips = true,  // Enable mipmaps for lighting
                        stereoMode = StereoMode.None
                    ),
                    // ... other settings
                )
            }
        )
    )
    
    // Mark entity for lighting
    entity.setComponent(HeroLighting())
}
```

**Lighting Controls:**

- Intensity slider: `heroLightingSystem.lightingAlpha = 0.0f to 1.0f`
- Auto-updates based on video content (frame-by-frame)
- Wall meshes automatically created on MRUK-detected surfaces
- Reflections cast from video panel onto walls/floor/ceiling

### 3.5 Passthrough Integration

**Implementation:**

```kotlin
// MoonlightImmersiveActivity.kt
override fun onSceneReady() {
    super.onSceneReady()
    
    // Enable passthrough (shows real world)
    scene.enablePassthrough(true)
    
    // Set lighting environment for passthrough
    scene.setLightingEnvironment(
        ambientColor = LIGHT_AMBIENT_COLOR,
        sunColor = LIGHT_SUN_COLOR,
        sunDirection = LIGHT_SUN_DIRECTION
    )
}

// Toggle passthrough via UI/controller
fun setPassthrough(enabled: Boolean) {
    scene.enablePassthrough(enabled)
}
```

**User Experience:**

- Passthrough allows seeing real environment while streaming
- Video panel can be anchored to real walls
- Bias lighting blends with real-world lighting
- Toggle via controller button or UI panel

### 3.6 Audio Considerations

**Current:** Moonlight uses `AndroidAudioRenderer` for audio playback
**Spatial SDK:** `SpatialAudioFeature` for 3D spatialized audio

**Implementation:**

```kotlin
// MoonlightVideoEntity.kt
private fun setupSpatialAudio(player: ExoPlayer, panelEntity: Entity) {
    player.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                spatialAudioFeature.registerAudioSessionId(
                    registeredAudioSessionId = 1,
                    player.audioSessionId
                )
                
                val audioType = when (player.audioFormat?.channelCount) {
                    1 -> AudioType.MONO
                    2 -> AudioType.STEREO
                    else -> AudioType.SOUNDFIELD
                }
                
                panelEntity.setComponent(AudioSessionId(1, audioType))
            }
        }
    })
}
```

**Note:** Moonlight's current audio system may need adaptation. Spatial audio is optional but recommended for MR immersion.

---

## 4. Feasibility Assessment

### 4.1 Technical Feasibility: ✅ **FEASIBLE**

**Strengths:**
- ✅ Spatial SDK provides all required components (MRUK, panels, lighting)
- ✅ PremiumMediaSample demonstrates exact patterns needed
- ✅ MediaCodec decoder compatible with Spatial SDK surfaces
- ✅ Moonlight's network/streaming logic can remain unchanged

**Challenges:**

- ⚠️ Requires significant refactoring (Activity → Spatial Activity)
- ⚠️ Java → Kotlin migration recommended
- ⚠️ Input system needs adaptation
- ⚠️ Testing on Quest hardware required

### 4.2 Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Performance degradation with readable panels | Medium | Use direct-to-surface when lighting disabled, readable only when needed |
| Input latency in MR mode | Low | Spatial SDK input is low-latency, similar to Android |
| MRUK not detecting walls | Low | Fallback to free-floating mode, user can manually position |
| MediaCodec compatibility | Low | Spatial SDK uses standard Android surfaces |
| Complexity | High | Incremental development, test each feature independently |

### 4.3 Dependencies

**Required:**

- Meta Spatial SDK (latest version)
- Android API 34 (HorizonOS requirement)
- Kotlin (for Spatial SDK integration)
- Gradle 8.0+

**SDK Modules Needed:**

```kotlin
dependencies {
    implementation(libs.meta.spatial.sdk.base)
    implementation(libs.meta.spatial.sdk.vr)
    implementation(libs.meta.spatial.sdk.mruk)        // For wall detection
    implementation(libs.meta.spatial.sdk.toolkit)     // For panels
    implementation(libs.meta.spatial.sdk.spatialaudio) // For 3D audio
    implementation(libs.meta.spatial.sdk.compose)     // For UI panels (optional)
}
```

**Permissions:**
```xml
<uses-permission android:name="com.oculus.permission.USE_SCENE" />  <!-- MRUK -->
<uses-permission android:name="android.permission.INTERNET" />      <!-- Streaming -->
```

### 4.4 Performance Considerations

**Panel Rendering:**

- Direct-to-surface: ~60fps, minimal overhead
- Readable surface (for lighting): ~45-60fps, depends on resolution
- Recommendation: Use readable only when bias lighting enabled

**MRUK Processing:**

- Wall detection: One-time on app start, minimal overhead
- Anchoring updates: Per-frame for grabbed objects only

**Lighting System:**

- Shader updates: Per-frame, but optimized
- Wall mesh count: Depends on room complexity (typically 4-10 meshes)

**Memory:**

- Video texture: ~50-100MB for 4K stream
- MRUK data: <10MB
- Panel overhead: Negligible

---

## 5. Implementation Roadmap

### Phase 1: Foundation (2-3 weeks)

1. Set up Spatial SDK project structure
2. Create `MoonlightImmersiveActivity` (basic panel rendering)
3. Integrate `MediaCodecDecoderRenderer` with panel surface
4. Test basic video streaming in VR

### Phase 2: MR Features (2-3 weeks)

1. Integrate MRUK anchoring system
2. Implement wall-snapping behavior
3. Add free-floating toggle
4. Test on Quest 3 hardware

### Phase 3: Scaling & UX (1-2 weeks)

1. Integrate scaling systems (analog + touch)
2. Add theater mode (large default size)
3. Implement UI controls for scaling/anchoring
4. Polish user experience

### Phase 4: Lighting (2-3 weeks)

1. Integrate `HeroLightingSystem`
2. Set up `WallLightingSystem` with MRUK
3. Create lighting intensity controls
4. Test bias lighting with various video content

### Phase 5: Polish & Optimization (1-2 weeks)

1. Performance optimization
2. Input handling refinement
3. Passthrough integration
4. Final testing and bug fixes

**Total Estimated Time: 8-13 weeks**

---

## 5A. Three-Phase Implementation Plan (detailed, actionable)

The following steps map the roadmap into three shippable phases. Each step is actionable and references the relevant Meta docs where applicable.

### Phase 1: 2D panel bring-up (no MRUK/ISDK)

Goal: Run Moonlight video on a Spatial SDK panel as a flat 2D experience. See [2D panel spawn](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-spawn).

1) Dependencies and permissions
   - In `app/build.gradle.kts`, include Spatial SDK base/toolkit/vr and Kotlin targets (already present in the Mixed Reality template).
   - Ensure `<uses-permission android:name="android.permission.INTERNET" />` is present.

2) Activity and feature registration
   - Create `MoonlightImmersiveActivity` extending `AppSystemActivity` (or reuse the template Activity) and register `VideoSurfacePanelRegistration`.

```kotlin
class MoonlightImmersiveActivity : AppSystemActivity() {
  private lateinit var decoderRenderer: MediaCodecDecoderRenderer

  override fun registerPanels(): List<PanelRegistration> {
    val panelId = 1001
    return listOf(
        VideoSurfacePanelRegistration(
            panelId,
            surfaceConsumer = { _, surface ->
              // Connect Moonlight decoder to Spatial panel surface
              decoderRenderer.setRenderTarget(surface)
            },
            settingsCreator = {
              MediaPanelSettings(
                  shape = QuadShapeOptions(width = 1.6f, height = 0.9f),
                  display = PixelDisplayOptions(width = 1920, height = 1080),
                  rendering = MediaPanelRenderOptions(stereoMode = StereoMode.None),
              )
            },
        )
    )
  }
}
```

3) Wire Moonlight decoder to panel surface
   - In the code path where `MediaCodecDecoderRenderer` was given a `SurfaceHolder` (from `StreamView`), pass the panel `Surface` instead (from `surfaceConsumer` above).
   - Keep the existing network/decoder flow (NvConnection → MediaCodecDecoderRenderer) unchanged aside from the target surface.

4) Basic controls and validation
   - Add a simple start/stop hook (e.g., controller button or minimal UI) to start the stream once the panel is ready.
   - Validate: video renders, audio plays, controller input reaches the stream, lifecycle works (pause/resume) on Quest 3/Pro.

### Phase 2: MRUK room reconstruction + ISDK panels

Goal: Enable spatial awareness (MRUK) and ISDK panel registration. See [ISDK panels](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-isdk-panels).

1) Permissions and features
   - Add `<uses-permission android:name="com.oculus.permission.USE_SCENE" />`.
   - Register `MRUKFeature` in `registerFeatures()` and load the scene on startup.

```kotlin
override fun registerFeatures(): List<SpatialFeature> =
    listOf(VRFeature(this), MRUKFeature(this, systemManager))

override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  // Request permission then load scene; on success, proceed to panel registration.
}
```

2) Room reconstruction
   - After MRUK scene load, query MRUK planes/anchors (`MRUKPlane`, `MRUKAnchor`, `MRUKLabel`).
   - For now, just log or visualize detected anchors; do not snap yet.

3) ISDK-backed panel registration
   - If using ISDK panels, register via the ISDK panel API (similar to `VideoSurfacePanelRegistration` but through ISDK).
   - Keep the same surfaceConsumer hook to route the panel surface into `MediaCodecDecoderRenderer`.

4) Validation
   - Confirm MRUK planes are detected in a reconstructed room.
   - Confirm the panel appears and renders through ISDK registration.
   - Ensure passthrough toggles work with the Spatial SDK scene.

### Phase 3: Scaling + UI controls for scaling and anchoring

Goal: Add scaling and anchoring controls (snap vs free-float) with minimal UI.

1) Scaling systems
   - Register analog and touch scaling systems (patterns from PremiumMediaSample).

```kotlin
// In onCreate after systems initialization
systemManager.registerSystem(AnalogScalableSystem(pointerInfoSystem, minScale = 0.5f, maxScale = 10f))
systemManager.registerSystem(TouchScalableSystem(minScale = 0.5f, maxScale = 10f))
```

2) Panel components for scaling and (later) anchoring
   - Add to the video entity: `PanelDimensions`, `Scale`, `Scalable`, `Grabbable`.

```kotlin
entity.setComponents(
    PanelDimensions(Vector2(1.6f, 0.9f)), // adjust to aspect
    Scale(),
    Scalable(),
    Grabbable(true, GrabbableType.PIVOT_Y),
)
```

3) Anchoring toggle
   - Add `Anchorable(offset = 0.02f)` and `AnchorOnLoad(distanceCheck = 5.0f, scaleProportional = true)` when anchoring is enabled; remove them when free-floating.
   - Provide a simple UI/control binding (e.g., button A toggles anchor mode).

```kotlin
fun setAnchored(enabled: Boolean) {
  if (enabled) {
    entity.setComponents(Anchorable(0.02f), AnchorOnLoad(scaleProportional = true, distanceCheck = 5.0f))
  } else {
    entity.removeComponent<Anchorable>()
    entity.removeComponent<AnchorOnLoad>()
  }
}
```

4) Theater preset
   - Offer a preset size (e.g., width 8–10m) by updating `PanelDimensions`/`Scale` once on load or via UI.

5) Validation
   - Verify scaling works via controller and touch.
   - Verify anchoring snaps to MRUK planes (walls/ceiling/floor) and can return to free-float.
   - Confirm usability in passthrough and fully immersive modes.

---

## 6. Key Code Patterns to Reuse

### 6.1 Panel Registration Pattern

```kotlin
// From ExoVideoEntity.kt - Direct adaptation
VideoSurfacePanelRegistration(
    id,
    surfaceConsumer = { panelEnt, surface ->
        // Moonlight decoder setup
        decoderRenderer.setRenderTarget(surface)
    },
    settingsCreator = {
        MediaPanelSettings(
            shape = QuadShapeOptions(width, height),
            display = PixelDisplayOptions(width, height),
            rendering = MediaPanelRenderOptions(stereoMode = StereoMode.None)
        )
    }
)
```

### 6.2 Anchoring Pattern

```kotlin
// From ExoVideoEntity.kt
entity.setComponents(
    Anchorable(0.02f),
    AnchorOnLoad(scaleProportional = true, distanceCheck = 5.0f),
    Grabbable(true, GrabbableType.PIVOT_Y)
)
```

### 6.3 Lighting Pattern

```kotlin
// From ImmersiveActivity.kt
componentManager.registerComponent<HeroLighting>(HeroLighting.Companion)
componentManager.registerComponent<ReceiveLighting>(ReceiveLighting.Companion)
systemManager.registerSystem(HeroLightingSystem(true, false))
systemManager.registerSystem(WallLightingSystem())

// Entity setup
entity.setComponent(HeroLighting())
```

---

## 7. Documentation References

**Meta Spatial SDK Documentation:**

- [MRUK Integration](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-mruk)
- [Panel Shaders](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-custom-shaders)
- [2D Panel Registration](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-registration)
- [Direct-to-Surface Panels](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-drm#direct-to-surface-rendering)
- [Passthrough](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-passthrough/)

**Key Sample Files:**

- `PremiumMediaSample/app/src/main/java/com/meta/spatial/samples/premiummediasample/`
  - `immersive/ImmersiveActivity.kt` - Main activity pattern
  - `entities/ExoVideoEntity.kt` - Video panel pattern
  - `systems/anchor/AnchorSnappingSystem.kt` - Wall snapping
  - `systems/heroLighting/HeroLightingSystem.kt` - Bias lighting
  - `systems/scalable/` - Scaling implementations

---

## 8. Conclusion

Porting Moonlight Android to Meta's Spatial SDK is **technically feasible** with a **moderate to high effort** required. The PremiumMediaSample provides excellent reference implementations for all required features:

- ✅ Wall-snapping via MRUK
- ✅ Bias lighting and reflections
- ✅ Scalable panels
- ✅ Passthrough integration

The main challenges are architectural (Activity → Spatial Activity migration) and language (Java → Kotlin), but these are manageable with incremental development.

**Recommendation:** Proceed with implementation using the phased approach outlined in Section 5. Start with Phase 1 to validate core streaming functionality, then incrementally add MR features.

---

**Report Generated:** 2025
**Next Steps:** Begin Phase 1 implementation with basic Spatial SDK integration
