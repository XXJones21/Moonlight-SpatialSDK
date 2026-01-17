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
    
    // Edge-specific sampling: Map each glow region to its corresponding video edge
    // Key insight: Each edge must use the UV coordinate that VARIES ALONG that edge
    // - Left/Right edges: use Y coordinate (varies vertically along edge)
    // - Top/Bottom edges: use X coordinate (varies horizontally along edge)
    // We must remap the full quad UV (0-1 including padding) to panel bounds only

    vec2 videoUV;
    float edgeIntensity = 1.0;      // Default intensity multiplier
    float edgeFalloffExp = 1.5;     // Default falloff exponent

    if (uv.x < panelLeft) {
        // LEFT EDGE: sample from left column (x=0) of video
        // Map the full quad Y range to panel Y range, inverted for texture coordinates
        float videoY = 1.0 - clamp((uv.y - panelBottom) / (panelTop - panelBottom), 0.0, 1.0);
        videoUV = vec2(0.0, videoY);
        edgeIntensity = g_MaterialUniform.edgeControl.z;
        edgeFalloffExp = g_MaterialUniform.edgeFalloff.z;
    } else if (uv.x > panelRight) {
        // RIGHT EDGE: sample from right column (x=1) of video
        // Map the full quad Y range to panel Y range, inverted for texture coordinates
        float videoY = 1.0 - clamp((uv.y - panelBottom) / (panelTop - panelBottom), 0.0, 1.0);
        videoUV = vec2(1.0, videoY);
        edgeIntensity = g_MaterialUniform.edgeControl.w;
        edgeFalloffExp = g_MaterialUniform.edgeFalloff.w;
    } else if (uv.y < panelBottom) {
        // BOTTOM EDGE: sample from bottom row (y=0) of video
        // Map the full quad X range to panel X range for proper video sampling
        float videoX = clamp((uv.x - panelLeft) / (panelRight - panelLeft), 0.0, 1.0);
        videoUV = vec2(videoX, 0.0);
        edgeIntensity = g_MaterialUniform.edgeControl.y;
        edgeFalloffExp = g_MaterialUniform.edgeFalloff.y;
    } else if (uv.y > panelTop) {
        // TOP EDGE: sample from top row (y=1) of video
        // Map the full quad X range to panel X range for proper video sampling
        float videoX = clamp((uv.x - panelLeft) / (panelRight - panelLeft), 0.0, 1.0);
        videoUV = vec2(videoX, 1.0);
        edgeIntensity = g_MaterialUniform.edgeControl.x;
        edgeFalloffExp = g_MaterialUniform.edgeFalloff.x;
    } else {
        // Shouldn't happen (we discard inside panel), but fallback
        videoUV = vec2(0.5, 0.5);
    }

    // Sample the video texture with blur (mip level)
    vec4 videoColor = textureLod(emissive, videoUV, mipLevel);

    // Apply per-edge falloff using the edge-specific falloff exponent
    // Falloff: 1.0 at panel edge, 0.0 at outer edge
    float falloff = 1.0 - normDist;
    falloff = pow(falloff, edgeFalloffExp);

    // Debug mode: show the ring shape
    if (debugMode > 0.5) {
        outColor = vec4(falloff, falloff, falloff, falloff);
        return;
    }

    // Apply per-edge intensity multiplier
    float finalIntensity = intensity * edgeIntensity;

    // Skip if too faint
    if (finalIntensity <= 0.01 || falloff <= 0.01) {
        discard;
    }

    // Final output with per-edge falloff and intensity
    vec3 finalColor = videoColor.rgb * finalIntensity * 2.0;
    float finalAlpha = falloff * finalIntensity;

    outColor = vec4(finalColor * finalAlpha, finalAlpha);
}
