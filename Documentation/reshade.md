# Investigating ReShade + SuperDepth3D + Layer.fx Diorama Setup

Goal: Recreate and generalize the “playable diorama” technique that uses ReShade, SuperDepth3D, Virtual Desktop, and Layer.fx framing to present flat AAA games as SBS 3D “windows” in passthrough on Quest-class headsets.

---

## 1. High-level Architecture

- Game renders normally on PC (DX11/DX12/OpenGL/Vulkan-with-DXVK if needed).
- ReShade injects:
  - **SuperDepth3D.fx** to convert depth buffer into SBS 3D output.
  - **Layer.fx** (or equivalent) to render a textured frame PNG in front of the game image.
- Virtual Desktop (or similar streamer) sends resulting SBS image to Quest.
- Quest app / VD 3D mode displays SBS as a floating screen in passthrough, giving a diorama-like portal effect.

Questions to validate empirically:
- Exact ReShade preset chain and execution order (Layer.fx before/after SuperDepth3D).
- VD settings required for correct SBS interpretation and passthrough window size.

---

## 2. Environment & Tools Checklist

- OS / GPU:
  - Windows 10/11 with recent NVIDIA/AMD drivers.
- Core tools:
  - ReShade installer (latest from reshade.me).
  - SuperDepth3D shader pack (Depth3D info / GitHub).
  - Layer.fx shader and sample PNG frame asset(s).
  - Virtual Desktop (PC streamer + Quest client).
- Test games:
  - One DX11 title with known good depth buffer (e.g., older AAA with no aggressive post-processing).
- VR side:
  - Quest 3 (or similar) running Virtual Desktop in a passthrough-capable mode.

Open questions:

- Any game-specific quirks (e.g., TAA/DOF that break depth).
- Whether depth buffer access needs “Generic Depth” disabled or adjusted in ReShade.

---

## 3. ReShade + SuperDepth3D Setup

### 3.1 Basic Installation Flow

1. Run `ReShade_Setup.exe`, point to `Game.exe`, select appropriate renderer (DX11/12/OpenGL).
2. Deselect all effect packages except the Depth3D/SuperDepth3D set, or manually add `SuperDepth3D.fx` later.
3. Launch the game and open ReShade UI (`Home` or `Shift+F2` depending on version).
4. Create a new preset and enable `SuperDepth3D.fx`.
5. Disable post-processing in-game that corrupts depth (motion blur, DOF, film grain, chromatic aberration).

### 3.2 SuperDepth3D Configuration Targets

**Recommended Settings (Tested with Fallout 4):**

| Setting | Value | Purpose |
|---------|-------|---------|
| **Divergence (Depth)** | 100.0 | Controls overall depth strength. High values make the scene feel like a deep display case. |
| **Zero Parallax (ZPD)** | 0.027 | Keeps the world inside the screen like a window. Use 0.100 for pop-out effect (may interfere with mouse clicking). |
| **Depth Map** | DM1 Reversed | Required for correct depth so the sky doesn't appear closer than the ground. |

**Output format**: SBS 3D (Side-by-Side) - the stream width is doubled (e.g., 5120×1440 for two 2560×1440 views).

**Depth tuning notes:**

- Higher ZPD values look more dramatic but can interfere with precise mouse clicking
- Adjust global depth scale for comfortable parallax at target screen size
- Foreground/background separation should behave like a layered pop-up book, not full geometry 3D

**Artifacts:**

- Experiment with depth detection and halo reduction settings as documented on the ReShade forum

**Verification tasks:**

- Capture screenshots to verify correct left/right views
- Check that UI elements are either flat or at a comfortable depth

---

## 4. Layer.fx Framing Pipeline

### 4.1 Conceptual Behavior

- Layer.fx draws a textured PNG frame on top of the scene, treated as a separate “layer” with its own depth behavior.
- The Reddit author sets the frame’s stereoscopic depth *ahead* of the game window, like a physical window frame in front of a diorama.
- Depth for the frame is tuned similar to Magic‑Eye stereograms, decoupled from the game’s depth buffer.

### 4.2 Implementation Steps to Test

1. Place `Layer.fx` in the ReShade shaders directory used by the game.
2. Prepare a high‑resolution frame PNG with transparent center and decorative border.
3. In ReShade:
   - Enable `Layer.fx` after SuperDepth3D (or experiment with ordering).
   - Point the shader to the frame PNG.
   - Use its parameters to:
     - Scale and position the frame so that it tightly surrounds the rendered game area.
     - Assign stereo disparity so the frame appears closer than game content.
4. Validate that:
   - The frame is stereo‑consistent with SuperDepth3D’s output.
   - The “portal” illusion holds when moving head slightly (within VD’s head‑tracked window).

Open questions:

- Exact Layer.fx parameters for per‑eye offset / convergence.
- How to prevent the frame from inheriting depth-buffer warping.

---

## 5. Virtual Desktop & Headset Configuration

### 5.1 Streaming Settings

- Ensure:
  - Correct refresh rate and resolution for stable SBS 3D (no scaling artifacts).
  - Bitrate high enough to avoid compression noise in depth edges.
- VD video mode:
  - Enable SBS 3D viewing mode (not 2D).
  - Use passthrough environment so the diorama floats in the real room.

### 5.2 Comfort & Presentation

- Tune:
  - Virtual screen size and distance so perceived scale of characters and environments feels “small but tangible,” like a diorama.
  - Head‑lock vs world‑lock options to reduce discomfort while using a controller.
- Record baseline settings (field of view, depth strength, screen distance) that feel good across multiple games.

---

## 6. Moonlight-SpatialSDK Implementation Viability

This section analyzes how to implement the ReShade diorama effect natively using Moonlight-SpatialSDK's existing stereoscopic panel infrastructure, rather than relying on Virtual Desktop.

### 6.1 Existing Infrastructure Assessment

**What the SDK Already Supports:**

1. **StereoMode.LeftRight** - Meta Spatial SDK natively supports side-by-side stereoscopic rendering via `StereoMode` enum in panel configuration:
   ```kotlin
   ReadableMediaPanelRenderOptions(
       mips = 4,
       stereoMode = StereoMode.LeftRight  // Native SBS 3D support
   )
   ```

2. **ReadableVideoSurfacePanelRegistration** - Already used for bias lighting, this panel type allows:
   - Custom shader sampling of video texture
   - Mipmap generation for blur effects
   - Per-frame texture access for advanced effects

3. **Width Doubling Logic** - Previously implemented (now removed) at [MoonlightConnectionManager.kt:249-258](Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/MoonlightConnectionManager.kt#L249-L258):

   ```kotlin
   // Previously: val streamWidth = if (prefs.stereoscopicModeEnabled) prefs.width * 2 else prefs.width
   val streamWidth = prefs.width  // Currently disabled
   ```

4. **Shader Infrastructure** - Custom shaders already work with video panels:
   - `stereoParams` uniform exists in [BiasLightingEntity.kt:137](Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/entities/BiasLightingEntity.kt#L137)
   - Fragment shaders can sample video texture via `emissive` sampler
   - Material system supports `setStereoMode(StereoMode.LeftRight)`

**Previous 3D Implementation (Removed):**

The project previously had stereoscopic 3D support that was removed. Key components included:

- `StereoDepthSliderEntity.kt` - Runtime depth adjustment slider
- `Stereo3DVideoPanelEntity.kt` - Dedicated stereo panel entity
- `stereo_video.frag` - Custom stereoscopic shader
- `StereoVideoSystem.kt` - ECS system for stereo management

### 6.2 Implementation Strategy: Native SBS 3D Streaming

**Approach A: Simple SBS Passthrough (Minimal Changes)**

The simplest approach streams ReShade's SBS output directly and lets the SDK handle stereo separation:

1. **PC Side**: Configure ReShade with SuperDepth3D to output SBS 3D at doubled width (e.g., 5120×1440 for two 2560×1440 views)

2. **Quest Side**: Modify panel registration to use stereo mode:

   ```kotlin
   ReadableVideoSurfacePanelRegistration(
       R.id.ui_example,
       surfaceConsumer = { panelEntity, surface -> /* ... */ },
       settingsCreator = {
           ReadableMediaPanelSettings(
               shape = QuadShapeOptions(width = panelWidth, height = panelHeight),
               display = PixelDisplayOptions(width = streamWidth, height = streamHeight),
               rendering = ReadableMediaPanelRenderOptions(
                   mips = 4,
                   stereoMode = StereoMode.LeftRight  // Enable SBS interpretation
               )
           )
       }
   )
   ```

3. **Stream Configuration**: Re-enable width doubling in `MoonlightConnectionManager`:

   ```kotlin
   val streamWidth = if (prefs.stereoscopicModeEnabled) prefs.width * 2 else prefs.width
   ```

**Estimated Effort**: Low - primarily re-enabling commented-out code and adding a UI toggle.

**Approach B: Native Frame Overlay (Layer.fx Equivalent)**

Replace Layer.fx with a Quest-side decorative frame using existing bias lighting infrastructure:

1. **Frame Entity**: Create a `DioramaFrameEntity` similar to `BiasLightingEntity` that:
   - Renders a decorative frame PNG around the video panel
   - Positions at a fixed stereoscopic depth *in front of* the game content
   - Uses `BlendMode.ALPHA_BLEND` for transparency

2. **Depth Control**: The frame's stereo depth is independent of video content:

   ```kotlin
   // Frame renders at fixed Z offset in front of panel
   Transform(Pose(Vector3(0f, 0f, -0.02f)))  // 2cm in front
   ```

3. **Advantages over ReShade Layer.fx**:
   - Frame depth controlled entirely on Quest side (no PC-side shader coordination needed)
   - Frame doesn't require SBS encoding (rendered natively in stereo)
   - Dynamic frame swapping without restarting stream

### 6.3 Comparison: VD vs Moonlight-SpatialSDK

| Feature | Virtual Desktop | Moonlight-SpatialSDK |
|---------|-----------------|---------------------|
| **SBS 3D Support** | Native (built-in mode) | Requires re-enabling `StereoMode.LeftRight` |
| **Passthrough Environment** | Yes | Yes (default mode) |
| **Frame Overlay** | Layer.fx (PC-side) | Native entity (Quest-side, more flexible) |
| **Bias Lighting** | No | Yes (existing implementation) |
| **Latency** | ~20-30ms | ~15-25ms (Moonlight typically lower) |
| **Codec Support** | H.264/HEVC | H.264/HEVC/AV1 |
| **Spatial Audio** | Yes | Yes (SpatialAudioManager) |
| **Room Integration** | Limited | MRUK (wall snap, reflections) |
| **Panel Manipulation** | Fixed screen | Grabbable, scalable, snap-to-wall |

### 6.4 Implementation Roadmap

**Quick Test Plan (Fallout 4 + ReShade SuperDepth3D):**

- [x] **Step 1**: Create a simple 3D panel entity with `StereoMode.LeftRight`
  - Modified video panel registration to use `StereoMode.LeftRight` when stereo enabled
  - No width doubling needed - SuperDepth3D outputs SBS within current resolution (e.g., 2560×1440 split into 2x 1280×1440)
  - SDK automatically splits left/right views to each eye

- [x] **Step 2**: Add stereoscopic toggle to Immersive Settings dialog
  - Added `stereoscopicEnabled` field to `ImmersiveSettings.kt`
  - Added "Stereoscopic 3D (SBS)" toggle in Immersive Options UI
  - Requires app restart to take effect (panel created at launch)

**Future Phases (After Validation):**

**Phase 2: Quality of Life**

- [ ] Runtime depth slider (restore `StereoDepthSliderEntity`)
- [ ] Per-game preset storage for depth strength

**Note:** Bias lighting (`BiasLightingEntity`) is planned for removal due to stability issues. Wall/light reflections will be retained.

### 6.5 Technical Considerations

**Stream Resolution for SBS:**

- Standard: 2560×1440 per eye → 5120×1440 SBS stream
- 4K: 3840×2160 per eye → 7680×2160 SBS stream (may exceed bandwidth)
- Recommended: 2560×1440 @ 72-90fps for optimal balance

**Bandwidth Requirements:**

- 5120×1440 @ 90fps requires ~80-100 Mbps for quality SBS
- AV1 codec recommended for better compression at high resolutions

**Panel Dimension Calculation:**

```kotlin
private fun calculatePanelSize(force16x9: Boolean, useDoubledWidth: Boolean): Vector2 {
    val aspect = if (force16x9) {
        16f / 9f
    } else {
        // For SBS, the doubled width should NOT affect aspect ratio
        // Each eye sees half the stream width
        val effectiveWidth = if (useDoubledWidth) prefs.width else prefs.width
        effectiveWidth.toFloat() / prefs.height.toFloat()
    }
    return Vector2(aspect * basePanelHeightMeters, basePanelHeightMeters)
}
```

**Important**: When `stereoMode = StereoMode.LeftRight`, the SDK automatically splits the stream. Panel aspect ratio should be based on single-eye dimensions (e.g., 2560×1440), not the full SBS width (5120×1440).

### 6.6 Viability Verdict

**High Viability** - Implementing the ReShade diorama effect in Moonlight-SpatialSDK is straightforward:

1. **Core SBS support exists** - Just needs re-enabling
2. **Frame overlay is simpler on Quest** - No Layer.fx coordination needed
3. **Additional features** - Bias lighting, MRUK, spatial audio enhance the experience
4. **Lower latency** - Moonlight protocol typically outperforms VD for game streaming

**Recommended Next Steps:**

1. Re-enable stereoscopic mode (Phase 1) to validate SBS passthrough works
2. Test with SuperDepth3D output to confirm correct eye separation
3. Add diorama frame entity if the basic SBS experience is successful

---

## 7. Depth-Anything v3 as an Alternative Depth Source

This section investigates using Depth-Anything v3 (DA3), a state-of-the-art monocular depth estimation model, to generate real-time depth masks that could be fed into ReShade's Layer.fx or similar shaders to drive stereoscopic 3D effects as an alternative to SuperDepth3D's depth buffer approach.

### 7.1 Concept Overview

**The Core Idea:**

Instead of relying on the game's native depth buffer (which SuperDepth3D extracts and processes), use an external AI model to infer depth from the rendered game frame:

1. **Capture**: Intercept the game's rendered frame (post-processing complete)
2. **Inference**: Feed the frame through Depth-Anything v3 to generate a depth map
3. **Inject**: Provide the depth map to ReShade as a custom texture or alpha mask
4. **Render**: Use Layer.fx, StageDepthPlus.fx, or a custom shader to apply stereoscopic displacement based on the AI-generated depth

**Why Consider This Approach:**

- Works with games that have inaccessible, corrupted, or incomplete depth buffers
- Can estimate depth for UI elements, pre-rendered cutscenes, and 2D overlays that lack depth information
- Depth-Anything v3 produces smoother depth gradients and handles complex scenes (reflections, transparency, fog) that may confuse traditional depth buffer extraction
- Potential for temporal consistency improvements via Video-Depth-Anything variants

### 7.2 Depth-Anything v3 Technical Specifications

**Model Architecture:**

DA3 uses a plain transformer architecture (vanilla DINOv2 encoder) with a unified depth-ray prediction target. Released November 2025, it outperforms DA2 in monocular depth estimation while supporting multi-view consistency.

**Model Variants and Parameters:**

| Model | Parameters | Use Case |
|-------|------------|----------|
| DA3-Small | 0.08B | Edge devices, maximum speed |
| DA3-Base | 0.12B | Balanced performance |
| DA3-Large | 0.35B | High quality, recommended for gaming |
| DA3-Giant | 1.15B | Maximum quality, research use |
| DA3Metric-Large | 0.35B | Metric depth (real-world scale) |
| DA3Mono-Large | 0.35B | Relative depth (optimal for stereo conversion) |

**Inference Performance (TensorRT, RTX 4090, FP16):**

Based on Depth-Anything-V2 TensorRT benchmarks (V3 expected similar or better):

| Model Size | Input Resolution | Inference Time | Effective FPS |
|------------|-----------------|----------------|---------------|
| Small | 518x518 | 3ms | ~333 FPS |
| Base | 518x518 | 6ms | ~166 FPS |
| Large | 518x518 | 12ms | ~83 FPS |

Note: These times include preprocessing and postprocessing. Real-world pipeline overhead (capture, transfer, injection) will reduce effective throughput.

**GPU Memory Requirements:**

- DA3-Streaming mode: < 12GB VRAM for video inference
- DA3-Large single-frame: ~4-6GB VRAM
- DA3-Small: < 2GB VRAM

### 7.3 Integration with Layer.fx and ReShade

**Approach A: External Depth Map Injection**

ReShade shaders can be modified to use custom depth textures instead of the game's depth buffer:

1. Generate depth map via DA3 as a grayscale PNG or texture
2. Use StageDepthPlus.fx (from CorgiFX) which natively supports loading external depth textures via `StageDepthTex`
3. Configure the shader to blend the AI depth map with scene rendering

```hlsl
// Pseudo-code for custom depth texture in ReShade
texture DepthAnythingTex < source = "depth_anything_output.png"; >;
sampler DepthAnythingSampler { Texture = DepthAnythingTex; };

float GetCustomDepth(float2 texcoord) {
    return tex2D(DepthAnythingSampler, texcoord).r;
}
```

**Approach B: Alpha Mask for Layer.fx**

Layer.fx accepts PNG textures with alpha channels. The depth map could drive per-pixel alpha/displacement:

1. Convert DA3 depth output to an RGBA PNG where:
   - RGB = decorative frame or pass-through
   - Alpha = depth-derived displacement factor
2. Layer.fx uses the alpha to control stereoscopic offset per pixel
3. This creates a "depth-aware overlay" effect

**Approach C: Custom ReShade Shader**

Write a dedicated shader that:

1. Reads the AI depth map from a shared texture or file
2. Applies DIBR (Depth-Image-Based Rendering) to generate left/right eye views
3. Outputs SBS 3D similar to SuperDepth3D but using external depth

### 7.4 Technical Feasibility Analysis

**Latency Budget:**

For comfortable gaming at 60 FPS, each frame has ~16.67ms total budget:

| Component | Estimated Latency | Notes |
|-----------|-------------------|-------|
| Frame capture | 1-3ms | Desktop Duplication API or hook |
| CPU->GPU transfer | 0.5-1ms | PCIe bandwidth dependent |
| DA3-Large inference | 12ms | TensorRT FP16 on RTX 4090 |
| Depth map injection | 1-2ms | Texture upload to ReShade |
| **Total Pipeline** | **15-18ms** | Exceeds single-frame budget |

**Critical Issue: Latency Mismatch**

At 60 FPS, the depth map will be 1-2 frames behind the rendered image, causing:

- Stereoscopic artifacts during fast motion
- Depth/color misalignment at object edges
- "Swimming" or "jello" effect on moving objects

**Potential Mitigations:**

1. **Frame Delay Approach**: Delay the final render by 1 frame to synchronize depth with the corresponding frame (adds ~16ms input lag)
2. **Lower Resolution Inference**: Run DA3 at 256x256 or 384x384 and upscale (reduces accuracy at edges)
3. **Asynchronous Pipeline**: Accept the latency and tune for slower-paced games
4. **DA3-Small**: Use the fastest model (~3ms inference) to fit within frame budget

**GPU Resource Contention:**

Running DA3 inference on the same GPU as the game creates resource contention:

- VRAM pressure: Game + DA3 model + ReShade textures
- Compute contention: Game rendering vs ML inference
- Recommendation: Dedicate a second GPU for DA3 inference, or use integrated graphics for light games

### 7.5 Comparison: DA3 vs SuperDepth3D Depth Buffer

| Aspect | SuperDepth3D (Depth Buffer) | Depth-Anything v3 |
|--------|----------------------------|-------------------|
| **Depth Accuracy** | Pixel-perfect (computed from geometry) | Estimated (~95% relative accuracy) |
| **Latency** | Zero (same-frame data) | 12-18ms pipeline delay |
| **Edge Quality** | Sharp, geometry-aligned | Softer, may blur fine details |
| **UI Handling** | Often lacks depth (drawn flat) | Can estimate UI depth contextually |
| **Cutscenes** | No depth in pre-rendered video | Full depth estimation possible |
| **Compatibility** | Requires accessible depth buffer | Works with any rendered frame |
| **Performance Impact** | Minimal (shader-only) | Significant (ML inference) |
| **Temporal Stability** | Stable (geometry-based) | May flicker without temporal models |
| **Complex Effects** | Struggles with transparency, reflections | Handles learned visual patterns |
| **Setup Complexity** | ReShade + shader config | External process + IPC + shader |

**Verdict:**

SuperDepth3D remains superior for games with good depth buffer access due to zero-latency, pixel-perfect depth. DA3 is compelling for:

- Games with broken/inaccessible depth buffers
- Pre-rendered cutscenes and video content
- Applications where approximate depth suffices (ambient 3D effect)

### 7.6 Potential Implementation Approaches

**Option 1: Desktop Client Application (Python/C++)**

Resurrect the previously removed "DesktopSpatial" concept as a dedicated depth processing client:

```
[Game] -> [Desktop Capture] -> [DA3 Inference] -> [Shared Texture/File] -> [ReShade Shader]
```

Components:

- Windows Desktop Duplication API for low-latency capture
- ONNX Runtime or TensorRT for DA3 inference
- Shared memory or memory-mapped file for depth texture transfer
- ReShade shader modified to read from shared texture

Pros: Decoupled from game, works with any title
Cons: High latency, complex IPC, resource overhead

**Option 2: ReShade Add-on/Plugin**

Create a native ReShade add-on that runs DA3 inference within the ReShade pipeline:

- Leverage ReShade's add-on API (introduced in ReShade 5.0+)
- Hook into the frame render pipeline
- Run inference synchronously or asynchronously
- Write depth to a custom texture accessible by shaders

Pros: Tighter integration, potentially lower latency
Cons: Complex to implement, GPU resource contention, C++ development required

**Option 3: Quest-Side Depth Estimation**

Move depth estimation to the Quest headset using the Moonlight-SpatialSDK:

- Stream 2D game video to Quest
- Run DA3-Small on Quest's Snapdragon XR2 (via NNAPI/TFLite)
- Generate stereo views natively on Quest

Pros: Offloads PC GPU, lower network bandwidth (mono stream)
Cons: XR2 inference speed uncertain, Quest thermal constraints, adds complexity to SDK

**Option 4: Hybrid Depth Approach**

Combine SuperDepth3D's depth buffer with DA3 for a "best of both worlds" solution:

- Use game depth buffer where available and valid
- Fall back to DA3 for regions with missing/invalid depth (UI, skybox, cutscenes)
- Blend the two depth sources with configurable weights

Pros: Maximizes depth quality, handles edge cases
Cons: Most complex implementation, potential blending artifacts

### 7.7 Pros/Cons Summary

**Advantages of DA3 Depth Estimation:**

- Universal compatibility (no depth buffer dependency)
- Handles content SuperDepth3D cannot (cutscenes, 2D elements, emulators)
- Produces semantically meaningful depth (understands scene context)
- Active research area with rapid improvements (Video-Depth-Anything for temporal consistency)
- Could enable 3D conversion for streaming services, videos, legacy games

**Disadvantages:**

- Significant latency penalty (12-18ms+ pipeline)
- Lower depth accuracy than native depth buffers
- GPU resource contention with game rendering
- Complex integration (external process, IPC, custom shaders)
- Edge artifacts and temporal instability without careful tuning
- Requires TensorRT optimization for acceptable performance

### 7.8 Recommendations

**Short Term (Experimentation):**

1. Build a proof-of-concept Python script using DA3-Small with ONNX Runtime
2. Capture game frames via OBS virtual camera or similar
3. Output depth maps to a watched folder
4. Use StageDepthPlus.fx in ReShade to load depth textures
5. Evaluate visual quality and latency impact subjectively

**Medium Term (If POC Shows Promise):**

1. Implement TensorRT-optimized DA3-Small inference
2. Use shared memory for depth map transfer (eliminate file I/O)
3. Develop a custom ReShade shader for SBS output using external depth
4. Test with games known to have poor depth buffer support

**Long Term (Production Quality):**

1. Investigate ReShade add-on architecture for native integration
2. Explore Video-Depth-Anything for temporal consistency
3. Consider Quest-side inference for Moonlight-SpatialSDK integration
4. Evaluate hybrid depth approach for maximum compatibility

**Current Verdict:**

DA3 depth estimation is a promising research direction but not ready to replace SuperDepth3D for real-time gaming. The latency penalty and integration complexity make it better suited for:

- Offline/slow-paced applications
- Content without depth buffer access
- Experimental/research purposes

For the Moonlight-SpatialSDK diorama effect, continue using SuperDepth3D with native depth buffers as the primary approach, while monitoring DA3/Video-Depth-Anything developments for future integration.

---

## 8. Experiments & Extensions

Planned experiments:

- Compare:
  - SuperDepth3D “layered 3D” vs true geometry 3D solutions (e.g., UEVR / VorpX) for similar titles.
- Try alternate shaders:
  - Other depth-based SBS shaders or barrel distortion / theater modes from Depth3D packs.
- UX improvements:
  - Different frame PNG designs (thin bezel vs ornate portal).
  - Color grading or vignette to emphasize the “window into another world” effect.

Questions for follow‑up research:

- How robust is the technique on modern engines with heavy temporal effects?
- Can the frame depth be animated subtly (e.g., parallax‑aware highlights) without breaking comfort?
- Is there a clean way to package per‑game presets for easy sharing and reuse?

---
