#version 400
#extension GL_ARB_separate_shader_objects:enable
#extension GL_ARB_shading_language_420pack:enable

#include <data/shaders/Uniforms.glsl>
#include <customBindingFrag.glsl>

// Shader Purpose: Merge two textures side-by-side into a single output texture
// Input: Two separate textures (left eye + right eye), each at stream resolution (e.g., 2560x1440p)
// Output: Single side-by-side texture at (2*width)xheight (e.g., 5120x1440p)
// 
// For debugging: Uses red texture for left eye, blue texture for right eye
// Future: Will sample actual video texture and duplicate it for left/right eye views
//
// This shader does NOT perform stereo splitting - that is handled by SDK's StereoMode.LeftRight
// This shader ONLY merges two textures into a side-by-side layout

void main() {
    vec2 inputUV = vertexOut.emissiveCoord;
    
    // Merge two textures side-by-side:
    // Left half [0.0, 0.5]: Left eye texture (red for debugging, 2560x1440p)
    // Right half [0.5, 1.0]: Right eye texture (blue for debugging, 2560x1440p)
    // Output: 5120x1440p side-by-side texture
    
    if (inputUV.x < 0.5) {
        // Left half: Left eye texture (debug: red)
        outColor = vec4(1.0, 0.0, 0.0, 1.0); // RED - represents left eye texture at 2560x1440p
    } else {
        // Right half: Right eye texture (debug: blue)
        outColor = vec4(0.0, 0.0, 1.0, 1.0); // BLUE - represents right eye texture at 2560x1440p
    }
    
    // No other logic - just merge the two textures side-by-side
    // SDK's StereoMode.LeftRight will handle splitting this output for each eye
}
