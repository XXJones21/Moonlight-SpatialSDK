# 3DS-Style Stereo Depth Control Implementation Plan

## Algorithm Overview

### Core Concept

The Nintendo 3DS depth slider scales the effective inter-ocular distance (stereo separation) between the left and right eye views. At minimum (0.0), both eyes see identical images (monoscopic). At maximum (1.0), full original parallax is preserved. Intermediate values scale disparity proportionally.

### Depth model

- Zero-parallax plane: Fixed at image center (horizontal center for side-by-side, vertical center for over-under)
- Depth scaling: `S ∈ [0.0, 1.0]` where:
    - `1S = 0.0`: True monoscopic (both eyes sample same half, zero disparity)
    - `S = 1.0`: Full original parallax (each eye samples its designated half)
    - `S ∈ (0.0, 1.0)`: Scaled parallax (convergence toward center)

### Why Current Approach Fails

The current implementation applies a simple horizontal offset (eyeOffset * 0.01) which:

- Doesn't properly scale disparity from center
- May cause UV clamping issues at edges
- Doesn't achieve true monoscopic at S=0.0
- Lacks principled convergence behavior

## Implementation Strategy

3.1 Clean Stereo Separation (Already Implemented)

Current shader correctly:

- Left eye samples [0.5, 1.0] (right half) - inverted to fix swap
- Right eye samples [0, 0.5] (left half) - inverted to fix swap
- No blending between halves

3.2 Adjustable Depth via Disparity Remapping

Algorithm:

For side-by-side format with zero-parallax at center:

1. Base UV mapping (current, for S=1.0):
- Left eye: `baseUV.x = 0.5 + inputUV.x * 0.5` → samples [0.5, 1.0]
- Right eye: `baseUV.x = inputUV.x * 0.5` → samples [0, 0.5]
2. Convergence toward center (for S < 1.0):
- Compute distance from center: centerDist = inputUV.x - 0.5 (range [-0.5, +0.5])
- Scale disparity: scaledDist = centerDist * S
- Remap UVs:
    - Left eye: `remappedUV.x = 0.5 + scaledDist + 0.5 * S`
    - Right eye: `remappedUV.x = 0.5 + scaledDist - 0.5 * S`
3. True monoscopic at S=0.0:
- Both eyes sample center: `remappedUV.x = 0.5 + centerDist` → both sample [0.25, 0.75] (centered region)

UV Clamping:

- Clamp remappedUV.x to valid range for each eye's half:
- Left eye: clamp to [0.5, 1.0] when S > 0
- Right eye: clamp to [0, 0.5] when S > 0
- At S=0, both clamp to [0.25, 0.75] (safe center region)

Mathematical Formulation:

```
For side-by-side, with inputUV.x ∈ [0, 1] and center at 0.5:
  centerDist = inputUV.x - 0.5  // Distance from center [-0.5, +0.5]
  
  if (eyeIndex < 0.5):  // Left eye
    remappedUV.x = 0.5 + (centerDist * S) + (0.5 * S)
    // At S=0: remappedUV.x = 0.5 + centerDist (centered)
    // At S=1: remappedUV.x = 0.5 + centerDist + 0.5 = 1.0 + centerDist (maps to [0.5, 1.0])
  
  else:  // Right eye
    remappedUV.x = 0.5 + (centerDist * S) - (0.5 * S)
    // At S=0: remappedUV.x = 0.5 + centerDist (centered, same as left)
    // At S=1: remappedUV.x = 0.5 + centerDist - 0.5 = centerDist (maps to [0, 0.5])
  
  // Clamp to valid texture range
  rmappedUV.x = clamp(remappedUV.x, eyeMin, eyeMax)
```

3.3 Comfort and Tuning Guidelines

Safe Parallax Ranges:

- Maximum comfortable disparity: ~2-3% of screen width for typical viewing distances
- For 1920px width: ~38-57 pixels maximum disparity
- Negative parallax (pop-out): More aggressive clamping (max 1-2% screen width)

Slider Mapping:

- Default: 0.5 (moderate depth, comfortable for most users)
- Range: [0.0, 1.0] with non-linear easing recommended
-Consider exponential curve: effectiveS = pow(sliderValue, 1.5) for gentler low-end, stronger high-end

Clamping Strategy:

- Clamp UVs to prevent sampling outside valid texture regions
- At edges, gracefully reduce disparity to avoid artifacts
- Consider edge feathering for smooth transitions

## Implementation Details

### Files to Modify

1. `app/src/shaders/stereo_video.frag`
- Replace simple convergence offset with proper disparity scaling algorithm
- Implement center-based convergence with S scaling
- Add proper UV clamping per eye
- Support both side-by-side and over-under formats
2. `app/src/main/java/com/example/moonlight_spatialsdk/systems/stereo/StereoVideoSystem.kt`
- Update convergenceOffset calculation if needed
- Ensure depthFactor (S) is properly passed to shader
- Add constants for comfort limits (max disparity, clamping ranges)
3. `app/src/main/java/com/example/moonlight_spatialsdk/panels/stereoDepthSlider/StereoDepthSliderCompose.kt`
- Consider non-linear slider mapping for better UX
- Update slider range/behavior if needed

### Shader Implementation

Key Changes:

- Remove simple eyeOffset * 0.01 approach
- Implement center-distance-based disparity scaling
- Ensure S=0.0 produces true monoscopic (both eyes identical)
- Proper UV clamping to prevent sampling artifacts

Uniforms:

- matParams.x: depthFactor (S) - [0.0, 1.0]
- matParams.y: stereoFormat - 0.0 = side-by-side, 1.0 = over-under
- stereoParams.y: Reserved for future headset-based convergence (currently unused)

### Testing Protocol

1. Depth Test Pattern:
- Create test video with clear foreground/midground/background objects
- Verify S=0.0: All objects appear flat (monoscopic)
- Verify S=1.0: Full depth preserved
- Verify intermediate S: Smooth depth scaling
2. Comfort Validation:
- Test at various S values for extended viewing
- Verify no eye strain at recommended ranges
- Check edge cases (S near 0.0, S near 1.0)
3. UV Clamping:
- Verify no sampling artifacts at screen edges
- Check smooth transitions when S changes rapidly

## Future Enhancements (Not in This Plan)

- Headset-based convergence: Use headset transform to update zero-parallax plane in real-time
- LookAt function: Make convergence plane follow headset position relative to video plane
- Per-content depth analysis: Automatically detect optimal S value based on video content
- Depth map support: If depth maps become available, implement 2D+depth warping variant

## Comparison to Overlay Method

### Overlay Approach (Current Problem):

❌ Blends both halves → ghosting, conflicting disparity cues
❌ Cannot tune depth properly
❌ Causes visual discomfort and nausea

### 3DS-Style Approach (This Implementation):

✅ Maintains per-eye separation (no blending)
✅ Scalar control over perceived depth (S parameter)
✅ Respects stereoscopic comfort limits
✅ True monoscopic at S=0.0
✅ Smooth, principled depth scaling