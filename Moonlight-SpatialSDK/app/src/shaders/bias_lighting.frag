#version 400
#extension GL_ARB_separate_shader_objects:enable
#extension GL_ARB_shading_language_420pack:enable

#include <data/shaders/Uniforms.glsl>
#include <customBindingFrag.glsl>

layout(location = 10) in struct {
  vec2 texCoord;
  float falloffDistance;
} biasOut;

void main(){
  // matParams.x = lightingAlpha (intensity) - controlled by HeroLightingSystem
  // stereoParams.y = edgeType (0=top, 1=bottom, 2=left, 3=right)
  // stereoParams.z = debugMode (1.0 = debug with solid colors)
  vec4 params = g_MaterialUniform.matParams;
  vec4 stereoParams = g_MaterialUniform.stereoParams;
  
  float intensity = params.x;
  float edgeType = stereoParams.y;
  float debugMode = stereoParams.z;
  
  vec4 edgeColor;
  
  // Debug mode: use solid color from emissive texture directly
  if(debugMode > 0.5){
    edgeColor = texture(emissive, vec2(0.5, 0.5));
    // Solid color, full opacity for debug visibility
    outColor = vec4(edgeColor.rgb, 1.0);
    return;
  }
  
  // Skip if intensity is too low
  if(intensity <= 0.01){
    discard;
  }
  
  // Calculate video UV based on edge type
  // Edge types: 0=top, 1=bottom, 2=left, 3=right
  // Note: Video texture Y is flipped (y=0 is bottom of video, y=1 is top)
  vec2 videoUV;
  
  if(edgeType < 0.5){
    // Top edge: sample top row of video (y=1 in flipped coords), x varies along edge
    videoUV = vec2(biasOut.texCoord.x, 1.0);
  } else if(edgeType < 1.5){
    // Bottom edge: sample bottom row (y=0 in flipped coords), x varies
    videoUV = vec2(biasOut.texCoord.x, 0.0);
  } else if(edgeType < 2.5){
    // Left edge: sample left column (x=0), y varies (inverted to match video orientation)
    videoUV = vec2(0.0, 1.0 - biasOut.texCoord.x);
  } else {
    // Right edge: sample right column (x=1), y varies (inverted to match video orientation)
    videoUV = vec2(1.0, 1.0 - biasOut.texCoord.x);
  }
  
  // Sample video texture at moderate mip level for smooth blur while preserving edge distinction
  // emissive sampler contains the video texture
  float mipLevel = 4.0;
  edgeColor = textureLod(emissive, videoUV, mipLevel);
  
  // Calculate falloff: 1.0 at inner edge (panel), 0.0 at outer edge
  // biasOut.falloffDistance is 0 at inner, 1 at outer
  float falloff = 1.0 - biasOut.falloffDistance;
  
  // Apply power curve for softer falloff
  falloff = pow(falloff, 1.5);
  
  // Final color with falloff applied
  vec3 finalColor = edgeColor.rgb * intensity * 2.0;
  float finalAlpha = falloff * intensity;
  
  // Additive blending: output color that will be added to scene
  outColor = vec4(finalColor * finalAlpha, finalAlpha);
}

