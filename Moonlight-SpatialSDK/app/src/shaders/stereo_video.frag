#version 400
#extension GL_ARB_separate_shader_objects:enable
#extension GL_ARB_shading_language_420pack:enable

#include <data/shaders/Uniforms.glsl>
#include <customBindingFrag.glsl>

// DEBUG MODE: Output solid colors to test StereoMode
// 
// When StereoMode.LeftRight is set on the material, the SDK should automatically
// handle UV mapping so that:
//   - Left eye samples texture [0, 0.5] horizontally
//   - Right eye samples texture [0.5, 1.0] horizontally
//
// However, with a custom shader, we might need to handle this ourselves.
// For debugging, we'll output colors to see what's happening.
//
// Test strategy:
// 1. Output red for left eye, blue for right eye
// 2. If StereoMode is working, left eye should see red, right eye should see blue
// 3. If both eyes see the same color, StereoMode isn't working with custom shader

void main() {
    vec4 params = g_MaterialUniform.matParams;
    float stereoFormat = params.y; // 0.0 = side-by-side, 1.0 = over-under
    
    vec2 inputUV = vertexOut.emissiveCoord;
    
    // DEBUG: The challenge is we can't directly detect which eye is being rendered
    // If StereoMode.LeftRight is working with custom shader, the SDK might:
    // Option A: Modify UVs before they reach shader (left eye gets [0,0.5] range, right gets [0.5,1.0])
    // Option B: Handle it at texture sampling level (we still get [0,1] UVs but texture is sampled differently)
    // Option C: Doesn't work with custom shaders at all
    
    // For debugging, let's try to determine which half of the texture we should be sampling
    // If StereoMode is working, we can check by sampling the texture at different positions
    
    vec4 debugColor;
    
    if (stereoFormat < 0.5) {
        // Side-by-side format
        // Test: Sample texture at inputUV - if StereoMode works, this should be correct
        // But we can't verify without knowing which eye
        
        // Alternative: Output based on a test pattern
        // Since both eyes render full quad, we'll output a pattern that helps identify the issue
        // Output red on left side of quad, blue on right side
        // If StereoMode is working AND splitting correctly, each eye should see different content
        // But this won't help us distinguish eyes...
        
        // Better approach: Output colors that will be clearly different if StereoMode works
        // We'll use the texture sampling to determine which half we're in
        // Sample the texture - if StereoMode is working, left eye samples [0, 0.5], right samples [0.5, 1.0]
        
        // For now, output a simple test: red for left half of quad, blue for right half
        // This is just to see the quad structure - won't tell us about StereoMode
        // But if we see this pattern correctly, at least the shader is running
        if (inputUV.x < 0.5) {
            debugColor = vec4(1.0, 0.0, 0.0, 1.0); // RED
        } else {
            debugColor = vec4(0.0, 0.0, 1.0, 1.0); // BLUE
        }
        
        // Note: This will show red on left side, blue on right side for BOTH eyes
        // To test StereoMode, we need to see if each eye sees different content
        // The user will need to check visually: left eye should see red, right eye should see blue
        // If both eyes see the same pattern, StereoMode isn't working with custom shader
    } else {
        // Over-under format
        if (inputUV.y > 0.5) {
            debugColor = vec4(1.0, 0.0, 0.0, 1.0); // RED for top
        } else {
            debugColor = vec4(0.0, 0.0, 1.0, 1.0); // BLUE for bottom
        }
    }
    
    outColor = debugColor;
}
