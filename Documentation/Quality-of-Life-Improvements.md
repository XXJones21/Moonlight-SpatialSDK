# Quest 3 App - Quality of Life Improvements

## Overview

This document outlines quality of life improvements for the Moonlight-SpatialSDK Quest 3 application, focusing on both user experience (UX) and infrastructure improvements. These recommendations are based on comprehensive review of the documentation, codebase, and actual testing feedback.

## Implementation Status Legend

- IMPLEMENTED: Feature completed and tested
- IN PROGRESS: Currently under development
- PLANNED: Documented for future implementation

---

## IMPLEMENTED: In-Session Reconnection Without App Restart

### Problem Identified During Testing

When a user disconnects a session, they cannot reconnect without fully exiting the app and manually closing out of the immersive space.

### Implementation Status: IMPLEMENTED

**What Was Changed**:

1. Modified `disconnect()` function to store connection parameters and show reconnection dialog
2. Added `ReconnectDialog` Composable UI with three options:
   - Reconnect (Primary button) - Reconnects immediately
   - Return to Home - Original behavior
   - Cancel - Dismiss dialog
3. Added panel registration for `reconnect_dialog_panel`
4. Updated ButtonShelf disconnect button to trigger reconnection dialog

**How It Works**:

- User clicks Disconnect → Dialog appears in VR
- User clicks Reconnect → Video panel recreated, stream reconnects
- Immersive space preserved throughout entire process
- No need to exit to Home or manually restart

**Files Modified**:

- [ImmersiveActivity.kt](Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/ImmersiveActivity.kt)
- [ids.xml](Moonlight-SpatialSDK/app/src/main/res/values/ids.xml)

**Testing Steps**:

1. Connect to PC and start streaming
2. Click Disconnect on ButtonShelf
3. Verify reconnection dialog appears
4. Click "Reconnect" - should reconnect immediately
5. Verify stream resumes without exiting immersive space

---

## IMPLEMENTED: Runtime Input Device Hot-Plug Support

### Problem Identified During Testing

Users cannot use Bluetooth controllers that are paired after the streaming session has started. When a controller is turned on mid-session, it shows as connected to the Quest but input does not pass through to the host PC.

### Implementation Status: IMPLEMENTED

**What Was Changed**:

1. Added `InputDeviceListener` to ImmersiveActivity to detect new gamepad/joystick devices
2. Added `reinitializeControllerHandler()` method to MoonlightConnectionManager
3. Implemented automatic ControllerHandler reinitialization when new devices detected
4. Added comprehensive logging for debugging controller hot-plug events

**How It Works**:

- User connects to PC and starts streaming
- User turns on Bluetooth controller mid-session
- InputDeviceListener detects new gamepad device
- After 500ms delay, ControllerHandler is destroyed and recreated
- New ControllerHandler enumerates all devices including newly added controller
- Controller input now forwards to host PC

**Files Modified**:

- [ImmersiveActivity.kt](Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/ImmersiveActivity.kt) - Added InputDeviceListener
- [MoonlightConnectionManager.kt](Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/MoonlightConnectionManager.kt) - Added reinitializeControllerHandler()

**Testing Steps**:

1. Connect to PC and start streaming
2. Turn off Bluetooth controller
3. Turn on controller mid-session
4. Verify controller input works
5. Check logcat for "New input device detected" and "ControllerHandler reinitialized"

---

## IN PROGRESS: Panel Edge Lighting Emission - Individual Edge Control

### Problem Identified During Testing

The bias lighting (ambilight) implementation exhibits **mirroring artifacts** where opposite edges display identical content instead of unique edge-specific lighting:

- Top and bottom edges show the same content (e.g., Windows taskbar appears on both)
- Left and right edges show the same content
- Moving cursor on top-left creates glow on bottom-left that moves in the opposite direction

### Implementation Architecture

The system uses a single quad mesh with a 9-slice shader:

1. **Video Panel**: `ReadableVideoSurfacePanelRegistration` creates the media panel with `mips = 4` for texture sampling
2. **Texture Distribution**: `HeroLightingSystem` extracts the video texture and provides it to registered materials
3. **Bias Lighting Entity**: `BiasLightingEntity` creates a single quad mesh scaled to `panelSize + GLOW_PADDING * 2`
4. **Shader**: `bias_lighting_9slice.frag` determines which edge to sample based on UV coordinates

**Key Files**:

- [bias_lighting_9slice.frag](../Moonlight-SpatialSDK/app/src/shaders/bias_lighting_9slice.frag) - Fragment shader
- [bias_lighting_9slice.vert](../Moonlight-SpatialSDK/app/src/shaders/bias_lighting_9slice.vert) - Vertex shader
- [BiasLightingEntity.kt](../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/entities/BiasLightingEntity.kt) - Entity management
- [HeroLightingSystem.kt](../Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/systems/heroLighting/HeroLightingSystem.kt) - Texture distribution
- [customBindingFrag.glsl](../Moonlight-SpatialSDK/app/src/shaders/customBindingFrag.glsl) - MaterialUniform block definition

**Legacy Files** (not currently used):

- [bias_lighting.frag](../Moonlight-SpatialSDK/app/src/shaders/bias_lighting.frag) - Old 4-mesh approach shader
- [bias_lighting.vert](../Moonlight-SpatialSDK/app/src/shaders/bias_lighting.vert) - Old 4-mesh vertex shader

### Investigation & Attempts Summary

#### Attempt 1: Add Per-Edge Control Uniforms

**Goal**: Extend shader with `edgeControl` and `edgeFalloff` uniforms for per-edge intensity control.

**Changes Made**:

1. Added `edgeControl` and `edgeFalloff` to `MaterialUniform` block in `customBindingFrag.glsl`
2. Modified shader to use `g_MaterialUniform.edgeControl` and `g_MaterialUniform.edgeFalloff`
3. Added API methods to `BiasLightingEntity.kt`

**Result**: Vulkan shader compilation error - "non-opaque uniforms outside a block"

**Fix Applied**: Moved uniforms into existing `MaterialUniform` block (Vulkan requires all non-opaque uniforms in blocks)

#### Attempt 2: Fix Upside-Down Left/Right Edges

**Problem**: Left and right edges displayed content upside-down.

**Original Code**:

```glsl
videoUV = vec2(0.0, 1.0 - edgePosY);  // Left edge
videoUV = vec2(1.0, 1.0 - edgePosY);  // Right edge
```

**Fix Applied**: Removed `1.0 -` flip:

```glsl
videoUV = vec2(0.0, edgePosY);  // Left edge
videoUV = vec2(1.0, edgePosY);  // Right edge
```

**Result**: Fixed upside-down issue, but duplication persists.

#### Attempt 3: Use Raw UV Coordinates

**Hypothesis**: Panel-relative coordinates (`videoPosY`) cause duplication because left and right edges at the same quad height get the same Y value.

**Change Made**: Use raw `uv.y` instead of calculated `videoPosY`:

```glsl
videoUV = vec2(0.0, uv.y);  // Left edge
videoUV = vec2(1.0, uv.y);  // Right edge
```

**Result**: **Scale issue** - cursor position no longer matched glow position (1:1 correspondence broken). The glow appeared much higher than the cursor because raw UV covers the entire quad including padding.

#### Attempt 4: Restore Panel-Relative Mapping with Y-Flip

**Hypothesis**: Video textures are Y-flipped in GLSL, confirmed by PremiumMediaSample reference implementation.

**Reference from PremiumMediaSample** (`heroLighting.glsl` line 198):

```glsl
surfaceUV.y = clamp01(1 - surfaceUV.y);  // Y-FLIP
```

**Changes Made**:

1. Add `1.0 -` to left/right edge Y coordinates
2. Swap top/bottom edge video rows (top samples y=0, bottom samples y=1)

```glsl
if (uv.x < panelLeft) {
    float videoY = 1.0 - clamp((uv.y - panelBottom) / (panelTop - panelBottom), 0.0, 1.0);
    videoUV = vec2(0.0, videoY);
} else if (uv.x > panelRight) {
    float videoY = 1.0 - clamp((uv.y - panelBottom) / (panelTop - panelBottom), 0.0, 1.0);
    videoUV = vec2(1.0, videoY);
} else if (uv.y < panelBottom) {
    float videoX = clamp((uv.x - panelLeft) / (panelRight - panelLeft), 0.0, 1.0);
    videoUV = vec2(videoX, 1.0);  // Swapped
} else if (uv.y > panelTop) {
    float videoX = clamp((uv.x - panelLeft) / (panelRight - panelLeft), 0.0, 1.0);
    videoUV = vec2(videoX, 0.0);  // Swapped
}
```

**Result**: No change - mirroring still present.

#### Attempt 5: Correct Top/Bottom Swap Back

**Hypothesis**: The top/bottom swap was wrong based on the old `bias_lighting.frag` which had top edge sampling y=1.0 and bottom edge sampling y=0.0.

**Changes Made**: Reverted top/bottom to match old shader:

```glsl
} else if (uv.y < panelBottom) {
    videoUV = vec2(videoX, 0.0);  // Bottom samples y=0
} else if (uv.y > panelTop) {
    videoUV = vec2(videoX, 1.0);  // Top samples y=1
}
```

**Result**: Awaiting test - current state of shader.

### Root Cause Analysis

The mirroring issue remains unresolved. Key observations:

1. **Duplication Pattern**: Opposite edges show identical content
   - Top/bottom both show taskbar
   - Left/right show same content

2. **Movement Inversion**: Cursor movement on top-left creates glow on bottom-left moving in opposite direction

3. **Working Reference**: The old 4-mesh `bias_lighting.frag` shader worked correctly with explicit `edgeType` parameter

4. **Texture Source**: `HeroLightingSystem` provides the same video texture to all materials - no manipulation or duplication occurs there

### Hypotheses to Investigate

1. **UV Coordinate System**: The 9-slice quad mesh may have a different UV mapping than expected
   - Need to verify UV coordinates at each edge region
   - Consider adding debug output to visualize UV values

2. **Texture Coordinate Origin**: Video textures may have different coordinate systems than expected
   - OpenGL/Vulkan Y-axis conventions
   - Texture wrapping or clamping behavior

3. **Quad Mesh Geometry**: The `SceneMesh.quad()` function may generate UV coordinates that cause mirroring
   - Need to inspect how Meta Spatial SDK generates quad UVs

4. **Panel-Relative Calculation Flaw**: The calculation `(uv.y - panelBottom) / (panelTop - panelBottom)` may produce identical values for opposite edges in ways we haven't identified

### Next Steps

1. **Debug Visualization**: Add shader debug mode to output UV coordinates as colors to visualize the mapping
2. **Inspect Quad UVs**: Examine how `SceneMesh.quad()` generates UV coordinates
3. **Compare with Working Implementation**: Study how PremiumMediaSample's `heroLighting.glsl` samples textures for wall reflections
4. **Consider Alternative Approach**: May need to return to 4-entity approach with explicit edge type parameter if 9-slice cannot be fixed

### Current Shader State

The shader currently has per-edge control uniforms implemented but the core sampling logic still produces mirroring:

```glsl
// Current bias_lighting_9slice.frag edge detection (simplified)
if (uv.x < panelLeft) {
    // LEFT EDGE
    float videoY = 1.0 - clamp((uv.y - panelBottom) / (panelTop - panelBottom), 0.0, 1.0);
    videoUV = vec2(0.0, videoY);
    edgeIntensity = g_MaterialUniform.edgeControl.z;
} else if (uv.x > panelRight) {
    // RIGHT EDGE
    float videoY = 1.0 - clamp((uv.y - panelBottom) / (panelTop - panelBottom), 0.0, 1.0);
    videoUV = vec2(1.0, videoY);
    edgeIntensity = g_MaterialUniform.edgeControl.w;
} else if (uv.y < panelBottom) {
    // BOTTOM EDGE
    float videoX = clamp((uv.x - panelLeft) / (panelRight - panelLeft), 0.0, 1.0);
    videoUV = vec2(videoX, 0.0);
    edgeIntensity = g_MaterialUniform.edgeControl.y;
} else if (uv.y > panelTop) {
    // TOP EDGE
    float videoX = clamp((uv.x - panelLeft) / (panelRight - panelLeft), 0.0, 1.0);
    videoUV = vec2(videoX, 1.0);
    edgeIntensity = g_MaterialUniform.edgeControl.x;
}
```

### Use Cases Blocked by This Issue

- Creating asymmetric lighting effects (e.g., only top/bottom glow for ultrawide monitors)
- Directional ambilight (emphasize certain edges based on room layout)
- Dynamic edge effects (e.g., pulse individual edges based on content)
- Accessibility options (disable certain edges to reduce motion sickness)

### Proposed Solutions

#### Option 1: Extend 9-Slice Shader with Per-Edge Parameters

Modify the existing `bias_lighting_9slice.frag` shader to accept per-edge control parameters:

**Material Uniforms to Add**:

```kotlin
// Add to BiasLightingEntity material creation
setAttribute("edgeControl", Vector4(
    topIntensity,     // x: Top edge intensity multiplier (0-1)
    bottomIntensity,  // y: Bottom edge intensity multiplier (0-1)
    leftIntensity,    // z: Left edge intensity multiplier (0-1)
    rightIntensity    // w: Right edge intensity multiplier (0-1)
))

setAttribute("edgeFalloff", Vector4(
    topFalloff,       // x: Top edge falloff exponent (1.0-3.0)
    bottomFalloff,    // y: Bottom edge falloff exponent
    leftFalloff,      // z: Left edge falloff exponent
    rightFalloff      // w: Right edge falloff exponent
))
```

**Shader Modifications**:

```glsl
// In bias_lighting_9slice.frag
uniform vec4 edgeControl;  // Per-edge intensity
uniform vec4 edgeFalloff;  // Per-edge falloff exponents

// After determining which edge we're in
float edgeIntensity = 1.0;
float edgeFalloffExp = 1.5;  // Default falloff

if (uv.x < panelLeft) {
    // Left edge
    videoUV = vec2(0.0, 1.0 - edgePosY);
    edgeIntensity = edgeControl.z;
    edgeFalloffExp = edgeFalloff.z;
} else if (uv.x > panelRight) {
    // Right edge
    videoUV = vec2(1.0, 1.0 - edgePosY);
    edgeIntensity = edgeControl.w;
    edgeFalloffExp = edgeFalloff.w;
} else if (uv.y < panelBottom) {
    // Bottom edge
    videoUV = vec2(edgePosX, 0.0);
    edgeIntensity = edgeControl.y;
    edgeFalloffExp = edgeFalloff.y;
} else if (uv.y > panelTop) {
    // Top edge
    videoUV = vec2(edgePosX, 1.0);
    edgeIntensity = edgeControl.x;
    edgeFalloffExp = edgeFalloff.x;
}

// Apply per-edge falloff
float falloff = 1.0 - normDist;
falloff = pow(falloff, edgeFalloffExp);

// Apply per-edge intensity
vec4 videoColor = textureLod(emissive, videoUV, mipLevel);
vec3 finalColor = videoColor.rgb * intensity * edgeIntensity;
```

**Advantages**:

- Maintains single mesh entity (performance benefit)
- Maintains existing edge detection logic
- Adds per-edge control without mirroring
- Backward compatible (default all edges to 1.0)

**Disadvantages**:

- Limited to 4 vec4 parameters worth of per-edge control
- Cannot do complex per-edge effects (requires more uniforms)

#### Option 2: 4-Entity Approach with Explicit Edge Type Material Instances

Create 4 separate BiasLightingEntity-like entities with a modified shader that takes an explicit `edgeType` parameter:

**Entity Creation**:

```kotlin
class EdgeLightingEntity(
    private val edgeType: EdgeType,  // TOP, BOTTOM, LEFT, RIGHT
    private val heroLightingSystem: HeroLightingSystem?
) {
    enum class EdgeType(val value: Float) {
        TOP(0.0f),
        BOTTOM(1.0f),
        LEFT(2.0f),
        RIGHT(3.0f)
    }

    private fun createEdgeMaterial(): SceneMaterial {
        return SceneMaterial.custom("edge_lighting", ...).apply {
            // Set edge type in stereoParams.x
            setAttribute("stereoParams", Vector4(
                edgeType.value,  // Edge identifier
                mipLevel,
                0f,
                0f
            ))
        }
    }
}

// In ImmersiveActivity or BiasLightingEntity
val topEdgeEntity = EdgeLightingEntity(EdgeType.TOP, heroLightingSystem)
val bottomEdgeEntity = EdgeLightingEntity(EdgeType.BOTTOM, heroLightingSystem)
val leftEdgeEntity = EdgeLightingEntity(EdgeType.LEFT, heroLightingSystem)
val rightEdgeEntity = EdgeLightingEntity(EdgeType.RIGHT, heroLightingSystem)
```

**Shader Implementation** (new `edge_lighting.frag`):

```glsl
// Edge-specific lighting shader
uniform vec4 stereoParams;  // x = edgeType (0=top, 1=bottom, 2=left, 3=right)
uniform sampler2D emissive;
uniform float intensity;

void main() {
    float edgeType = stereoParams.x;
    float mipLevel = stereoParams.y;

    vec2 videoUV;
    float edgePos = biasOut.texCoord.x;  // Position along edge (0-1)

    // Determine video sampling based on edge type
    if (edgeType < 0.5) {
        // TOP EDGE: sample top row of video
        videoUV = vec2(edgePos, 1.0);
    } else if (edgeType < 1.5) {
        // BOTTOM EDGE: sample bottom row of video
        videoUV = vec2(edgePos, 0.0);
    } else if (edgeType < 2.5) {
        // LEFT EDGE: sample left column of video
        videoUV = vec2(0.0, 1.0 - edgePos);
    } else {
        // RIGHT EDGE: sample right column of video
        videoUV = vec2(1.0, 1.0 - edgePos);
    }

    // Sample video at determined UV
    vec4 videoColor = textureLod(emissive, videoUV, mipLevel);

    // Apply falloff (distance from panel edge)
    float dist = biasOut.texCoord.y;  // 0 at panel edge, 1 at glow boundary
    float falloff = 1.0 - dist;
    falloff = pow(falloff, 1.5);

    // Final color
    vec3 finalColor = videoColor.rgb * intensity * falloff;
    float finalAlpha = falloff;

    fragColor = vec4(finalColor * finalAlpha, finalAlpha);
}
```

**Mesh Geometry**:

Each edge entity would use a simple quad mesh positioned along its respective edge:

- Top: positioned above panel, width = panel width + padding, height = glow padding
- Bottom: positioned below panel, width = panel width + padding, height = glow padding
- Left: positioned left of panel, height = panel height + padding, width = glow padding
- Right: positioned right of panel, height = panel height + padding, width = glow padding

**Advantages**:

- Complete independent control per edge
- Can enable/disable individual edges (destroy/create entities)
- Can apply different materials/shaders per edge
- Can animate edges independently (position, intensity, color)

**Disadvantages**:

- 4x entity overhead (4 draw calls instead of 1)
- More complex entity management (positioning, scaling, lifetime)
- Material instance management (4 materials to update)
- Requires new shader and mesh assets

#### Option 3: Hybrid Approach - Single Entity with Edge Mask Texture

Use the existing 9-slice approach but add an edge mask texture that controls per-edge visibility:

**Material Addition**:

```kotlin
// Add edge mask texture to material
setAttribute("edgeMask", edgeMaskTexture)  // R=top, G=bottom, B=left, A=right
```

**Edge Mask Texture**:

Create a procedural or static texture where:
- Red channel = top edge mask (1.0 = visible, 0.0 = hidden)
- Green channel = bottom edge mask
- Blue channel = left edge mask
- Alpha channel = right edge mask

**Shader Modification**:

```glsl
uniform sampler2D edgeMask;

// After determining edge and videoUV
float edgeMaskValue = 1.0;
if (uv.x < panelLeft) {
    edgeMaskValue = texture(edgeMask, vec2(0.25, 0.5)).b;  // Left (blue channel)
} else if (uv.x > panelRight) {
    edgeMaskValue = texture(edgeMask, vec2(0.75, 0.5)).a;  // Right (alpha channel)
} else if (uv.y < panelBottom) {
    edgeMaskValue = texture(edgeMask, vec2(0.5, 0.25)).g;  // Bottom (green channel)
} else if (uv.y > panelTop) {
    edgeMaskValue = texture(edgeMask, vec2(0.5, 0.75)).r;  // Top (red channel)
}

// Apply edge mask to final color
vec3 finalColor = videoColor.rgb * intensity * falloff * edgeMaskValue;
```

**Advantages**:

- Maintains single entity (performance benefit)
- Allows per-edge enable/disable via mask values
- Can be updated dynamically (update texture)
- Minimal shader changes

**Disadvantages**:

- Limited control (only intensity per edge via mask values)
- Requires texture creation and management
- Less flexible than separate entities

### Recommended Approach

**Option 1** (Extend 9-Slice Shader with Per-Edge Parameters) is recommended because:

1. **Performance**: Maintains single mesh entity and single draw call
2. **Simplicity**: Minimal changes to existing working implementation
3. **Flexibility**: Provides per-edge intensity and falloff control
4. **Maintainability**: No new entity management logic required
5. **Backward Compatible**: Existing code continues to work with default parameters

**Implementation Impact**:

- UX Impact: Medium - Enables advanced lighting customization
- Infrastructure Impact: Low - Shader-only modifications
- Testing Required: Verify per-edge parameters work correctly
- No changes to entity lifecycle or management

**Files to Modify**:

- [bias_lighting_9slice.frag](Moonlight-SpatialSDK/app/src/shaders/bias_lighting_9slice.frag) - Add per-edge uniforms and logic
- [BiasLightingEntity.kt](Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/entities/BiasLightingEntity.kt) - Add per-edge control API
- [ImmersiveActivity.kt](Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/ImmersiveActivity.kt) - Optional UI for per-edge control

**API Design Example**:

```kotlin
// In BiasLightingEntity
fun setEdgeIntensity(edge: Edge, intensity: Float) {
    when (edge) {
        Edge.TOP -> edgeControl.x = intensity
        Edge.BOTTOM -> edgeControl.y = intensity
        Edge.LEFT -> edgeControl.z = intensity
        Edge.RIGHT -> edgeControl.w = intensity
    }
    updateMaterialUniforms()
}

fun setEdgeFalloff(edge: Edge, falloff: Float) {
    when (edge) {
        Edge.TOP -> edgeFalloff.x = falloff
        Edge.BOTTOM -> edgeFalloff.y = falloff
        Edge.LEFT -> edgeFalloff.z = falloff
        Edge.RIGHT -> edgeFalloff.w = falloff
    }
    updateMaterialUniforms()
}

enum class Edge {
    TOP, BOTTOM, LEFT, RIGHT
}
```

---

## PLANNED: Multiple Connection Management & Favorites System

### Current State

- Only one saved connection (`saved_host`, `saved_port`, `saved_appId` in SharedPreferences)
- No connection history or favorites
- Users must manually re-enter connection details each time

### Proposed Improvement

**UX Impact**: High - Reduces friction for users with multiple PCs or frequent connections

**Infrastructure Impact**: Medium - Requires new data model and UI components

**Implementation**:

- Create `ConnectionProfile` data class with: host, port, appId, displayName, lastConnected, favorite flag
- Replace single SharedPreferences with Room database or JSON file storage
- Add connection list UI in PancakeActivity with:
  - Quick connect buttons for favorites
  - Connection history (last 10 connections)
  - Edit/delete actions
  - Server name detection and display
- Add "Quick Connect" button in ImmersiveActivity ButtonShelf for recent connections

**Files to Modify**:

- `PancakeActivity.kt` - Add connection list UI
- Create `ConnectionProfile.kt` - Data model
- Create `ConnectionRepository.kt` - Data persistence layer
- `ImmersiveActivity.kt` - Add quick reconnect UI

---

## PLANNED: Enhanced Connection Status & Quality Feedback in VR

### Current State

- Connection status exists in `MoonlightConnectionManager` but only logged
- Connection quality updates (`CONN_STATUS_OKAY`, `CONN_STATUS_POOR`) not displayed in VR
- No visual indicators for connection health during streaming

### Proposed Improvement

**UX Impact**: High - Users need real-time feedback on connection quality

**Infrastructure Impact**: Low - Leverage existing status callbacks

**Implementation**:

- Add connection quality indicator to ButtonShelf (signal strength icon)
- Display connection status overlay in VR (non-intrusive, auto-hide after 3 seconds)
- Show connection metrics: latency, frame loss percentage, bitrate
- Color-coded status: Green (Good), Yellow (Fair), Red (Poor)
- Add haptic feedback on connection quality degradation

**Files to Modify**:

- `ImmersiveActivity.kt` - Add status display UI
- `ButtonShelfCompose.kt` - Add connection quality indicator
- `MoonlightConnectionManager.kt` - Expose detailed connection metrics

---

## 3. Automatic Connection Recovery & Retry Logic

### Current State

- Sleep/wake recovery exists but no automatic retry on connection failure
- Users must manually reconnect after network interruptions
- No exponential backoff or retry limits

### Proposed Improvement

**UX Impact**: High - Seamless experience during network hiccups

**Infrastructure Impact**: Medium - Requires connection state machine

**Implementation**:

- Add automatic retry logic with exponential backoff (1s, 2s, 4s, 8s, max 30s)
- Retry up to 3 times before showing error dialog
- Detect network state changes and auto-reconnect when network restored
- Show "Reconnecting..." status during retry attempts
- Add user preference: "Auto-reconnect on failure" (enabled by default)

**Files to Modify**:

- `MoonlightConnectionManager.kt` - Add retry logic
- `ImmersiveActivity.kt` - Handle retry UI states
- Create `ConnectionStateMachine.kt` - Manage connection lifecycle

---

## 4. Unified Settings Management System

### Current State

- Settings scattered across multiple SharedPreferences:
  - `connection_prefs` - Connection details
  - `immersive_settings` - Immersive features
  - `PreferenceManager.getDefaultSharedPreferences()` - Stream settings
  - `GlPreferences` - GPU renderer info
- No centralized settings UI
- Settings accessed from different places (PancakeActivity, ImmersiveActivity)

### Proposed Improvement

**UX Impact**: Medium - Easier to find and manage all settings

**Infrastructure Impact**: High - Requires refactoring settings architecture

**Implementation**:

- Create unified `AppSettings` data class with all settings
- Implement single source of truth for settings (Room database or single SharedPreferences file)
- Create `SettingsRepository` for settings persistence
- Add comprehensive Settings panel accessible from both activities
- Group settings logically: Connection, Streaming, VR Features, Advanced
- Add settings export/import functionality

**Files to Modify**:

- Create `AppSettings.kt` - Unified settings model
- Create `SettingsRepository.kt` - Settings persistence
- `PancakeActivity.kt` - Refactor to use unified settings
- `ImmersiveActivity.kt` - Refactor to use unified settings
- Create `SettingsPanel.kt` - Unified settings UI

---

## 5. Connection Discovery & Auto-Detection

### Current State

- Manual IP address entry required
- No network scanning or server discovery
- Users must know PC IP address

### Proposed Improvement

**UX Impact**: High - Eliminates need to manually enter IP addresses

**Infrastructure Impact**: Medium - Requires network scanning implementation

**Implementation**:

- Add mDNS/Bonjour discovery for Moonlight servers on local network
- Scan common IP ranges (192.168.x.x, 10.x.x.x) for Moonlight servers
- Display discovered servers in connection list with server names
- Add "Scan Network" button in PancakeActivity
- Cache discovered servers for quick access
- Show server status (online/offline) in connection list

**Files to Modify**:

- Create `ServerDiscovery.kt` - Network scanning logic
- `PancakeActivity.kt` - Add discovery UI
- `ConnectionProfile.kt` - Add discovery metadata

---

## 6. Stream Configuration Presets

### Current State

- Users manually configure resolution, FPS, bitrate, codec each time
- No presets for common use cases (Performance, Quality, Balanced)
- Settings not saved per connection

### Proposed Improvement

**UX Impact**: Medium - Faster configuration for common scenarios

**Infrastructure Impact**: Low - Add preset definitions

**Implementation**:

- Create preset system: "Performance" (720p@120fps), "Balanced" (1080p@90fps), "Quality" (1440p@90fps), "Ultra" (4K@60fps)
- Add preset selector in connection configuration
- Save preset per connection profile
- Allow custom presets (user-defined configurations)
- Show estimated bandwidth requirements for each preset

**Files to Modify**:

- Create `StreamPreset.kt` - Preset definitions
- `PancakeActivity.kt` - Add preset selector UI
- `ConnectionProfile.kt` - Store preset preference

---

## 7. Smart Panel Positioning & Memory

### Current State

- Panel positioned at fixed distance (1.0m) in front of user
- No memory of previous positions
- No support for multiple panel layouts

### Proposed Improvement

**UX Impact**: Medium - More personalized and convenient panel placement

**Infrastructure Impact**: Low - Add position persistence

**Implementation**:

- Save panel position, rotation, and scale per session
- Restore last panel position on app launch
- Add preset positions: "Center", "Left", "Right", "Above", "Below"
- Support multiple saved positions (switch between layouts)
- Add "Reset to Default" option
- Remember position per connection profile

**Files to Modify**:

- `PanelPositioningSystem.kt` - Add position persistence
- `ImmersiveActivity.kt` - Save/restore panel state
- `ButtonShelfCompose.kt` - Add position preset buttons

---

## 8. Enhanced Error Messages & Troubleshooting

### Current State

- Generic error messages ("Connection terminated (error: $errorCode)")
- No actionable guidance for users
- Errors logged but not user-friendly

### Proposed Improvement

**UX Impact**: High - Users can self-diagnose and fix issues

**Infrastructure Impact**: Low - Add error message mapping

**Implementation**:

- Create error code to user-friendly message mapping
- Add troubleshooting tips for common errors:
  - Network unreachable → "Check PC is on same network"
  - Pairing failed → "Verify PIN entered correctly on PC"
  - Decoder error → "Try different codec (H264/HEVC)"
- Show error details in expandable section
- Add "Retry" button on error dialogs
- Link to documentation/wiki for detailed troubleshooting

**Files to Modify**:

- Create `ErrorMessages.kt` - Error message mapping
- `MoonlightConnectionManager.kt` - Return detailed error info
- `PancakeActivity.kt` - Display user-friendly errors
- `ImmersiveActivity.kt` - Show error dialogs with actions

---

## 9. Connection State Simplification & State Machine

### Current State

- Multiple boolean flags: `isPaired`, `isSurfaceReady`, `isConnected`, `shouldForwardInputs`
- Complex state synchronization logic
- Potential race conditions with async operations

### Proposed Improvement

**UX Impact**: Low - Internal improvement, but reduces bugs

**Infrastructure Impact**: High - Improves code maintainability and reliability

**Implementation**:

- Implement proper state machine for connection lifecycle:
  - `DISCONNECTED` → `PAIRING` → `PAIRED` → `CONNECTING` → `CONNECTED` → `STREAMING`
- Use sealed class for connection state
- Single source of truth for connection state
- State transitions are explicit and logged
- Eliminate race conditions with proper state management
- Add state transition callbacks for UI updates

**Files to Modify**:

- Create `ConnectionState.kt` - State machine definition
- `MoonlightConnectionManager.kt` - Refactor to use state machine
- `ImmersiveActivity.kt` - Update to use state machine
- Remove redundant boolean flags

---

## 10. Performance Monitoring & Diagnostics

### Current State

- Limited performance metrics available
- No built-in diagnostics or performance monitoring
- Difficult to debug performance issues

### Proposed Improvement

**UX Impact**: Medium - Helps users optimize their setup

**Infrastructure Impact**: Medium - Add metrics collection and display

**Implementation**:

- Add performance overlay (optional, toggleable):
  - FPS counter
  - Frame decode time
  - Network latency
  - Bitrate (current vs target)
  - Frame loss percentage
- Add diagnostics panel accessible from settings:
  - Connection quality history graph
  - Performance statistics (min/max/avg FPS, latency)
  - Network test tool (ping, bandwidth test)
  - Decoder capabilities display
- Export diagnostics report for troubleshooting
- Add "Performance Mode" toggle (reduces overhead for monitoring)

**Files to Modify**:

- Create `PerformanceMonitor.kt` - Metrics collection
- Create `DiagnosticsPanel.kt` - Diagnostics UI
- `ImmersiveActivity.kt` - Add performance overlay
- `MoonlightPanelRenderer.kt` - Expose decode metrics

---

## Priority Ranking

### High Priority (Immediate Impact)

1. **Multiple Connection Management** - Most requested feature, high user value
2. **Enhanced Connection Status in VR** - Critical for user awareness
3. **Automatic Connection Recovery** - Improves reliability significantly

### Medium Priority (Significant Improvement)

4. **Unified Settings Management** - Improves maintainability and UX
5. **Connection Discovery** - Reduces setup friction
6. **Enhanced Error Messages** - Improves user experience during failures

### Lower Priority (Nice to Have)

7. **Stream Configuration Presets** - Convenience feature
8. **Smart Panel Positioning** - Quality of life improvement
9. **Connection State Simplification** - Internal improvement
10. **Performance Monitoring** - Advanced user feature

---

## Implementation Considerations

### Dependencies

- Room database (for connection profiles and settings) - Add to `build.gradle.kts`
- Network scanning library (for server discovery) - Consider mDNS library
- State machine library (optional) - Can implement with sealed classes

### Breaking Changes

- Settings migration: Need migration path from old SharedPreferences to new unified system
- Connection data migration: Migrate single connection to connection profiles

### Testing Requirements

- Connection state machine: Unit tests for all state transitions
- Network discovery: Integration tests with mock servers
- Settings migration: Test migration from old to new format

### Documentation Updates

- Update `Quest 3 App Overview.md` with new features
- Add user guide for connection management
- Document settings structure and migration

---

## Related Documentation

- **Quest 3 App Overview.md** - Current app architecture
- **POST_MORTEM.md** - Known issues and resolutions
- **Quest 3 App Pipeline.md** - Pipeline analysis

---

*Document created: Based on comprehensive codebase review and documentation analysis*
