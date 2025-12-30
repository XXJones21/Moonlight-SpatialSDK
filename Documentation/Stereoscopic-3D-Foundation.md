# Stereoscopic 3D Foundation - Implementation Summary

## Overview

This document summarizes the foundational work completed to enable 3DS-style stereoscopic 3D depth control in the Moonlight-SpatialSDK Quest 3 application. The implementation provides a stable foundation for building up to the full 3DS-style depth effect with runtime depth control via a spatial slider.

**Status**: Foundation complete - Panel registration issues resolved, debug shader active, ultrawide panel configured. Ready for 3DS-style depth algorithm implementation.

---

## How the SDK Handles StereoMode

### StereoMode Enum

The Meta Spatial SDK provides `StereoMode` enum for controlling how a single texture is mapped to left and right eye views:

- **`StereoMode.None`**: Default mode - entire texture shown identically to both eyes (no UV modifications)
- **`StereoMode.LeftRight`**: Side-by-side stereo - displays left half of texture in left eye, right half in right eye
- **`StereoMode.UpDown`**: Top-bottom stereo - displays top half in left eye, bottom half in right eye
- **`StereoMode.MonoLeft`**: Monoscopic left - displays only left half to both eyes
- **`StereoMode.MonoUp`**: Monoscopic top - displays only top half to both eyes

### Current Implementation

**Panel Configuration** (`ImmersiveActivity.kt`, Line 1448):

```kotlin
stereoMode = StereoMode.None // Currently set to None for debug testing
```

**Why `StereoMode.None`?**:

- We're using a custom shader (`stereo_video`) that manually handles stereo splitting
- `StereoMode.None` ensures both eyes see the full texture (5120x1440p ultrawide)
- Our shader then performs the stereo splitting and depth control logic
- This gives us full control over the stereo rendering pipeline

**Alternative Approach** (Future):

- Could use `StereoMode.LeftRight` to let SDK handle initial splitting
- Then apply depth control in shader on top of pre-split UVs
- Currently using `StereoMode.None` for maximum control during foundation phase

### SDK Stereo Rendering Behavior

When `StereoMode.LeftRight` is set:
- SDK automatically splits texture in half horizontally
- Left eye gets UVs [0.0, 0.5] for X coordinate
- Right eye gets UVs [0.5, 1.0] for X coordinate
- SDK handles separate render passes per eye automatically

When `StereoMode.None` is set (current):
- Both eyes receive full texture UVs [0.0, 1.0]
- Custom shader must handle all stereo logic manually
- Provides maximum flexibility for custom depth algorithms

---

## What Our Shader Is Doing

### Current Shader Implementation

**File**: `app/src/shaders/stereo_video.frag`

**Current State**: Debug mode - outputs solid red for left half, solid blue for right half

```glsl
void main() {
    vec2 inputUV = vertexOut.emissiveCoord;
    
    // Simple split: left half red, right half blue
    if (inputUV.x < 0.5) {
        outColor = vec4(1.0, 0.0, 0.0, 1.0); // RED for left half
    } else {
        outColor = vec4(0.0, 0.0, 1.0, 1.0); // BLUE for right half
    }
}
```

### Shader Purpose

The `stereo_video` shader is designed to:

1. **Manually Split Stereo Texture**: Read from full 5120x1440p texture and split into left/right views
2. **Fix Eye Inversion**: Correct the swapped left/right eye views (known issue with SDK stereo rendering)
3. **Apply 3DS-Style Depth Control**: Scale disparity from center point based on depth slider value
4. **Support Multiple Formats**: Handle both side-by-side and over-under stereo formats

### Shader Uniforms (Prepared but Not Yet Used)

**`matParams`** (Vector4):
- `x`: `depthFactor` (S) - [0.0, 1.0] - 0.0 = monoscopic, 1.0 = full parallax
- `y`: `stereoFormat` - 0.0 = side-by-side, 1.0 = over-under
- `z`, `w`: Reserved for future use

**`stereoParams`** (Vector4):
- `x`, `y`: Reserved for future use
- `z`: `debugMode` - 1.0 = enable debug colors (red/blue), 0.0 = normal rendering
- `w`: Reserved for future use

**Note**: Uniforms are set in `StereoVideoSystem.kt` but shader currently ignores them (debug mode active).

### Vertex Shader

**File**: `app/src/shaders/stereo_video.vert`

Simple pass-through shader that:
- Unpacks vertex data from `App2VertexUnpacked`
- Transforms position to world space
- Passes UV coordinates to fragment shader via `vertexOut.emissiveCoord`
- Uses standard Spatial SDK vertex output structure

---

## How the 3D Panel Is Set Up

### Panel Registration Flow

**1. Registration Trigger** (`ImmersiveActivity.kt`, Line 1416):

When `stereoscopicDepthEnabled` is true in `ImmersiveSettings`:
- Uses `PanelCreator` registration mode (allows custom shader via `panelShader`)
- Calculates ultrawide dimensions (5120x1440p for 2560x1440p user resolution)

**2. Panel Configuration** (`ImmersiveActivity.kt`, Lines 1439-1453):

```kotlin
val panelConfigOptions = PanelConfigOptions().apply {
    layoutWidthInPx = textureWidth  // 5120 for 2560x1440p user resolution
    layoutHeightInPx = textureHeight  // 1440
    width = panelWidth  // Physical width in meters (ultrawide aspect ratio)
    height = panelHeight  // Physical height in meters
    mips = 1  // Disable mipmaps for low latency
    stereoMode = StereoMode.None  // Custom shader handles stereo
    panelShader = "stereo_video"  // Custom shader for stereo processing
    forceSceneTexture = true  // Enable scene texture for shader support
    enableTransparent = false
    themeResourceId = R.style.PanelAppThemeTransparent
}
```

**3. Entity Setup** (`ImmersiveActivity.kt`, Lines 1455-1469):

- Sets `PanelDimensions` BEFORE `PanelSceneObject` creation (critical for correct panel outline)
- Creates `PanelSceneObject` with configured options
- Adds all required components via `addVideoPanelComponents()`
- Gets surface from `PanelSceneObject` for video decoder attachment

**4. Surface Attachment** (`ImmersiveActivity.kt`, Lines 1477-1487):

- Paints surface black initially
- Attaches surface to `MoonlightPanelRenderer`
- Pre-configures decoder with preferences
- Marks `isSurfaceReady = true`

### Panel Dimensions

**Physical Size**: Calculated to match ultrawide aspect ratio:
- Width: `basePanelHeightMeters * aspectRatio` (e.g., 0.7m * 3.556 = 2.489m)
- Height: `basePanelHeightMeters` (0.7m)
- Aspect Ratio: `textureWidth / textureHeight` (5120 / 1440 = 3.556)

**Texture Resolution**: Doubled width for side-by-side stereo:
- User Resolution: 2560x1440p (from preferences)
- Panel Texture: 5120x1440p (doubled width)
- This ensures full desktop per eye when split

### Retry and Fallback Logic

**Robust Registration** (`ImmersiveActivity.kt`, Lines 1396-1409):

- Up to 3 registration attempts with exponential backoff (100ms, 200ms, 400ms delays)
- Entity verification via Query polling (10 attempts, 50ms delay) if callback doesn't set entity
- Fallback entity creation if all retries fail
- Ensures panel appears even if SDK callbacks are delayed

---

## Why Debug Red and Blue Textures

### Current Debug Mode

**Purpose**: Verify that:
1. Custom shader is being applied correctly
2. Panel is rendering at correct ultrawide size (5120x1440p)
3. Both eyes are receiving the shader output
4. UV coordinates are correct (left half vs right half)

**Implementation** (`stereo_video.frag`, Lines 16-20):

```glsl
if (inputUV.x < 0.5) {
    outColor = vec4(1.0, 0.0, 0.0, 1.0); // RED for left half
} else {
    outColor = vec4(0.0, 0.0, 1.0, 1.0); // BLUE for right half
}
```

**Expected Result**:
- Left eye should see: Red on left half, blue on right half (full ultrawide texture)
- Right eye should see: Red on left half, blue on right half (full ultrawide texture)
- Both eyes see the same because `StereoMode.None` is set

**Why This Is Useful**:
- Confirms shader is active and receiving correct UVs
- Verifies panel is ultrawide (not condensed to 2560x1440p)
- Provides visual confirmation that foundation is working
- Easy to spot if shader isn't being applied (would see video stream instead)

### Debug Mode Control

**System**: `StereoVideoSystem.kt` (Line 54)

```kotlin
var debugMode: Boolean = true  // Enabled by default for testing
```

**Toggle Method**: `setDebugMode(enabled: Boolean)` (Line 123)

When debug mode is disabled, shader will:
- Sample from video texture instead of outputting solid colors
- Apply stereo splitting logic
- Implement 3DS-style depth control (once algorithm is implemented)

---

## Why Ultrawide 5120x1440 Resolution

### The Doubling Requirement

**Problem**: SDK's `StereoMode.LeftRight` splits texture in half automatically:
- If we provide 2560x1440p texture, SDK gives each eye 1280x1440p (half the desktop)
- We need full 2560x1440p desktop per eye for proper stereoscopic viewing

**Solution**: Double the texture width:
- Provide 5120x1440p texture to SDK
- SDK splits it: left eye gets [0, 2560], right eye gets [2560, 5120]
- Each eye receives full 2560x1440p desktop

### Mathematical Foundation

**User Resolution**: 2560x1440p (from preferences)

**Panel Texture Resolution**:
- Width: `prefs.width * 2` = 2560 * 2 = 5120
- Height: `prefs.height` = 1440
- Total: 5120x1440p

**Aspect Ratio**:
- User: 2560 / 1440 = 1.778 (16:9)
- Panel: 5120 / 1440 = 3.556 (32:9 ultrawide)

**Physical Panel Dimensions**:
- Width: `basePanelHeightMeters * aspectRatio` = 0.7m * 3.556 = 2.489m
- Height: `basePanelHeightMeters` = 0.7m

### Why This Matches 3DS Pattern

The Nintendo 3DS uses a similar approach:
- Top screen: 800x240 resolution
- Shows two 400x240 images (one per eye) interleaved
- Parallax barrier directs different columns to each eye
- Total effective resolution per eye: 400x240

Our implementation mirrors this:
- Panel: 5120x1440p total
- Each eye gets: 2560x1440p (full desktop)
- Custom shader handles the splitting and depth control
- Slider controls depth scaling (3DS-style)

---

## Next Steps

### Immediate Next Steps (Foundation Complete)

**1. Implement 3DS-Style Depth Algorithm** (Priority: High)

- Replace debug shader logic with 3DS-style depth scaling
- Implement center-based convergence with S parameter scaling
- Add proper UV clamping to prevent sampling artifacts
- Support both side-by-side and over-under formats

**Files to Modify**:
- `app/src/shaders/stereo_video.frag` - Implement depth algorithm
- `app/src/main/java/com/example/moonlight_spatialsdk/systems/stereo/StereoVideoSystem.kt` - Verify uniform passing

**Reference**: See `Documentation/3ds-style-depth-control.md` for detailed algorithm specification.

**2. Disable Debug Mode** (Priority: Medium)

- Set `debugMode = false` in `StereoVideoSystem.kt` (Line 54)
- Verify shader samples from video texture correctly
- Test with actual Moonlight video stream

**3. Test Eye Inversion Fix** (Priority: High)

- Verify left eye sees left half of desktop, right eye sees right half
- If eyes are inverted, implement UV swapping in shader
- Test with known content (e.g., Windows start menu should be centered, not split)

**4. Implement Depth Slider Integration** (Priority: High)

- Connect `StereoDepthSliderEntity` to `StereoVideoSystem.depthFactor`
- Verify slider updates shader uniforms in real-time
- Test depth scaling from 0.0 (monoscopic) to 1.0 (full parallax)

### Future Enhancements

**1. Headset-Based Convergence** (Priority: Low)

- Use headset transform to update zero-parallax plane in real-time
- Implement `lookAt` function so convergence plane follows headset
- Requires headset position/rotation data in shader

**2. Comfort Limits** (Priority: Medium)

- Implement maximum comfortable disparity limits (2-3% screen width)
- Add non-linear slider mapping for better UX
- Consider exponential curve: `effectiveS = pow(sliderValue, 1.5)`

**3. Per-Content Depth Analysis** (Priority: Low)

- Automatically detect optimal S value based on video content
- Analyze disparity in video frames
- Suggest depth slider position

**4. Depth Map Support** (Priority: Low)

- If depth maps become available, implement 2D+depth warping variant
- More accurate depth representation
- Better pop-out effects

---

## Key Files Reference

### Shaders

- `app/src/shaders/stereo_video.vert` - Vertex shader (pass-through)
- `app/src/shaders/stereo_video.frag` - Fragment shader (currently debug mode)

### Systems

- `app/src/main/java/com/example/moonlight_spatialsdk/systems/stereo/StereoVideoSystem.kt` - Stereo video system
  - Manages material setup and uniform updates
  - Handles debug mode toggle
  - Registers video texture from panel

### Entities

- `app/src/main/java/com/example/moonlight_spatialsdk/entities/StereoDepthSliderEntity.kt` - Depth control slider
- `app/src/main/java/com/example/moonlight_spatialsdk/systems/stereoDepthSlider/StereoDepthSliderVisibilitySystem.kt` - Slider visibility management

### Panel Setup

- `app/src/main/java/com/example/moonlight_spatialsdk/ImmersiveActivity.kt` - Panel registration and configuration
  - Lines 1416-1500: Stereoscopic panel registration
  - Lines 1439-1453: Panel configuration with ultrawide dimensions
  - Lines 1455-1469: Entity setup and component addition

### Documentation

- `Documentation/3ds-style-depth-control.md` - Detailed algorithm specification
- `Documentation/Quest 3 App Pipeline.md` - Comprehensive app architecture

---

## Testing Checklist

### Foundation Verification

- [x] Panel appears at correct ultrawide size (5120x1440p)
- [x] Custom shader is applied (`stereo_video`)
- [x] Debug mode shows red/blue split correctly
- [x] Panel dimensions match calculated ultrawide aspect ratio
- [x] Retry logic ensures panel appears even if callbacks are delayed

### Next Phase Testing (To Do)

- [ ] 3DS-style depth algorithm implemented
- [ ] Depth slider controls depth factor (0.0 to 1.0)
- [ ] S=0.0 produces true monoscopic (both eyes identical)
- [ ] S=1.0 produces full original parallax
- [ ] Intermediate S values scale depth smoothly
- [ ] Eye inversion fixed (left eye sees left half, right eye sees right half)
- [ ] UV clamping prevents sampling artifacts at edges
- [ ] Both side-by-side and over-under formats supported

---

## Summary

The foundational work for 3DS-style stereoscopic 3D depth control is complete. The panel registration issues have been resolved with robust retry logic and fallback mechanisms. The ultrawide 5120x1440p panel is correctly configured, and the debug shader confirms the foundation is working.

**Current State**:
- ✅ Panel registration robust and reliable
- ✅ Ultrawide dimensions correctly set (5120x1440p)
- ✅ Custom shader applied and active
- ✅ Debug mode verifies foundation
- ⏳ 3DS-style depth algorithm pending implementation

**Ready For**: Implementation of 3DS-style depth scaling algorithm in `stereo_video.frag` shader.
