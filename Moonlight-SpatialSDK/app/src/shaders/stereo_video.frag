#version 400
#extension GL_ARB_separate_shader_objects:enable
#extension GL_ARB_shading_language_420pack:enable

#include <data/shaders/Uniforms.glsl>
#include <customBindingFrag.glsl>

// Simple test shader for 5120x1440p ultrawide panel
// Outputs red on left half, blue on right half
// Works with StereoMode.None (both eyes see full texture)

void main() {
    vec2 inputUV = vertexOut.emissiveCoord;
    
    // Simple split: left half red, right half blue
    if (inputUV.x < 0.5) {
        outColor = vec4(1.0, 0.0, 0.0, 1.0); // RED for left half
    } else {
        outColor = vec4(0.0, 0.0, 1.0, 1.0); // BLUE for right half
    }
}
