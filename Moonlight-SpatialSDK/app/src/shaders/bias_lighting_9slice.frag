/**
 * Fragment shader for bias lighting effect.
 * Edge-specific sampling with corrected UV mapping to prevent mirroring.
 * Samples only from the nearest edge of the video texture, with proper
 * position mapping along each edge. Applies blur via mip levels and
 * distance-based falloff from panel edges.
 */

#version 400
#extension GL_ARB_separate_shader_objects: enable
#extension GL_ARB_shading_language_420pack: enable

#include <data/shaders/Uniforms.glsl>
#include <customBindingFrag.glsl>

void main() {
    vec2 quadSize = g_MaterialUniform.emissiveFactor.xy;
    vec2 panelSize = g_MaterialUniform.emissiveFactor.zw;
    float glowPadding = g_MaterialUniform.albedoFactor.x;
    // Use fixed intensity - HeroLightingSystem may override matParams.x to 0
    float intensity = 0.8;
    float mipLevel = g_MaterialUniform.stereoParams.y;
    float debugMode = g_MaterialUniform.stereoParams.z;
    
    vec2 uv = vertexOut.albedoCoord;
    
    // Calculate panel bounds in UV space
    // Panel is centered in the quad
    float padX = glowPadding / quadSize.x;
    float padY = glowPadding / quadSize.y;
    
    float panelLeft = padX;
    float panelRight = 1.0 - padX;
    float panelBottom = padY;
    float panelTop = 1.0 - padY;
    
    // Check if inside panel area - discard (transparent)
    if (uv.x > panelLeft && uv.x < panelRight && 
        uv.y > panelBottom && uv.y < panelTop) {
        discard;
    }
    
    // Calculate distance to panel edge (in UV space)
    float distLeft = panelLeft - uv.x;
    float distRight = uv.x - panelRight;
    float distBottom = panelBottom - uv.y;
    float distTop = uv.y - panelTop;
    
    // Find which edge we're closest to and the distance
    float distX = max(distLeft, distRight);
    float distY = max(distBottom, distTop);
    
    // Distance from panel edge (0 = at panel, padX/padY = at outer edge)
    float dist = max(distX, distY);
    
    // Normalize distance (0 = at panel edge, 1 = at outer edge)
    float maxPad = max(padX, padY);
    float normDist = clamp(dist / maxPad, 0.0, 1.0);
    
    // Falloff: 1.0 at panel edge, 0.0 at outer edge
    float falloff = 1.0 - normDist;
    falloff = pow(falloff, 1.5); // Softer falloff curve
    
    // Edge-specific sampling: Only sample from the edge we're closest to
    // This prevents mirroring by ensuring each glow region samples only its corresponding edge
    // Map position along each edge to video UV coordinates correctly
    
    // Calculate position along panel edges (0-1 range)
    float edgePosX = clamp((uv.x - panelLeft) / (panelRight - panelLeft), 0.0, 1.0);
    float edgePosY = clamp((uv.y - panelBottom) / (panelTop - panelBottom), 0.0, 1.0);
    
    // Determine which edge we're in (use explicit region checks, not distance comparison)
    // Priority: horizontal edges over vertical for corners
    vec2 videoUV;
    
    if (uv.x < panelLeft) {
        // Left glow region: sample from left edge (x=0) of video
        // edgePosY: 0 = panel bottom, 1 = panel top
        // Video texture Y may be flipped - use same flip as old 4-mesh shader
        // Old shader used: vec2(0.0, 1.0 - biasOut.texCoord.x) for left edge
        // This suggests Y needs to be flipped for left/right edges
        videoUV = vec2(0.0, 1.0 - edgePosY);
    } else if (uv.x > panelRight) {
        // Right glow region: sample from right edge (x=1) of video
        // Apply same Y flip as left edge (matches old 4-mesh shader)
        videoUV = vec2(1.0, 1.0 - edgePosY);
    } else if (uv.y < panelBottom) {
        // Bottom glow region: sample from bottom edge (y=0) of video
        // edgePosX: 0 = panel left, 1 = panel right
        videoUV = vec2(edgePosX, 0.0);
    } else if (uv.y > panelTop) {
        // Top glow region: sample from top edge (y=1) of video
        videoUV = vec2(edgePosX, 1.0);
    } else {
        // Shouldn't happen (we discard inside panel), but fallback
        videoUV = vec2(0.5, 0.5);
    }
    
    // Sample the video texture with blur (mip level)
    vec4 videoColor = textureLod(emissive, videoUV, mipLevel);
    
    // Debug mode: show the ring shape
    if (debugMode > 0.5) {
        outColor = vec4(falloff, falloff, falloff, falloff);
        return;
    }
    
    // Skip if too faint
    if (intensity <= 0.01 || falloff <= 0.01) {
        discard;
    }
    
    // Final output with falloff and intensity
    vec3 finalColor = videoColor.rgb * intensity * 2.0;
    float finalAlpha = falloff * intensity;
    
    outColor = vec4(finalColor * finalAlpha, finalAlpha);
}
