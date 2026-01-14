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

## PLANNED: Panel Edge Lighting Emission - Individual Edge Control

### Problem Identified During Testing

The current bias lighting (ambilight) implementation uses a single quad mesh with a 9-slice shader that correctly samples individual edges without mirroring. However, attempts to modify the system to use separate light entities for each edge (4 individual entities) or to customize individual edge behaviors result in mirroring artifacts where all edges display the same lighting pattern instead of unique edge-specific lighting.

### Root Cause Analysis

The lighting emission system is implemented using a shader-based approach with a single mesh entity:

**Current Implementation Architecture**:

1. **Single Mesh Entity**: BiasLightingEntity creates one quad mesh with `bias_lighting_9slice` shader
2. **Shader-Based Edge Detection**: Fragment shader determines which panel edge to sample based on UV coordinates
3. **Explicit Region Checks**: Shader uses conditional logic to map each glow region to its corresponding video edge

```glsl
// Current 9-slice shader approach (bias_lighting_9slice.frag)
if (uv.x < panelLeft) {
    // LEFT EDGE: sample from left edge (x=0) of video
    videoUV = vec2(0.0, 1.0 - edgePosY);
} else if (uv.x > panelRight) {
    // RIGHT EDGE: sample from right edge (x=1) of video
    videoUV = vec2(1.0, 1.0 - edgePosY);
} else if (uv.y < panelBottom) {
    // BOTTOM EDGE: sample from bottom edge (y=0) of video
    videoUV = vec2(edgePosX, 0.0);
} else if (uv.y > panelTop) {
    // TOP EDGE: sample from top edge (y=1) of video
    videoUV = vec2(edgePosX, 1.0);
}
```

**Why Separate Entities Cause Mirroring**:

When attempting to create 4 separate BiasLightingEntity instances (one per edge), the mirroring occurs because:

1. **Material Instance Sharing**: All entities register with HeroLightingSystem and receive the same video texture
2. **UV Space Ambiguity**: Each separate mesh has its own UV space (0-1 on both axes)
3. **Lack of Edge Context**: Individual meshes don't know which edge they represent without additional parameters
4. **Shader Parameter Limitation**: The `stereoParams` uniform would need to communicate edge type to each instance
5. **Texture Sampling Confusion**: Without explicit edge identification, the shader samples the same region for all entities

**Previous 4-Mesh Approach (Legacy)**:

An older implementation (`bias_lighting.frag`) attempted to use 4 separate entities with an `edgeType` parameter:

```glsl
// Old 4-mesh shader (bias_lighting.frag)
// stereoParams.y = edgeType (0=top, 1=bottom, 2=left, 3=right)

if(edgeType < 0.5){
    videoUV = vec2(biasOut.texCoord.x, 1.0);  // Top edge
} else if(edgeType < 1.5){
    videoUV = vec2(biasOut.texCoord.x, 0.0);  // Bottom edge
} else if(edgeType < 2.5){
    videoUV = vec2(0.0, 1.0 - biasOut.texCoord.x);  // Left edge
} else {
    videoUV = vec2(1.0, 1.0 - biasOut.texCoord.x);  // Right edge
}
```

This approach was abandoned because:
- Required creating and managing 4 separate entities
- Required 4 separate material instances with different `edgeType` parameters
- Entity positioning and scaling became complex
- Performance overhead of 4 draw calls vs 1
- Material synchronization issues when updating intensity or video texture

### Current State

**What Works**:
- Single mesh entity with 9-slice shader correctly displays unique lighting per edge
- No mirroring artifacts with current implementation
- Efficient single draw call
- Automatic edge detection based on UV coordinates

**What Doesn't Work**:
- Cannot independently control intensity per edge (all edges share one intensity value)
- Cannot disable individual edges (all edges enabled/disabled together)
- Cannot apply different blur levels or falloff curves per edge
- Cannot animate individual edges independently

**Use Cases Blocked**:
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
