#version 430
#extension GL_ARB_separate_shader_objects:enable
#extension GL_ARB_shading_language_420pack:enable

#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>
#include <customBindingVert.glsl>

void main() {
    App2VertexUnpacked app = getApp2VertexUnpacked();
    
    vec4 wPos4 = g_PrimitiveUniform.worldFromObject * vec4(app.position, 1.0);
    
    // Pass through standard vertex outputs (vertexOut is defined in customBindingVert.glsl)
    // The fragment shader will use vertexOut.emissiveCoord for UV coordinates
    vertexOut.emissiveCoord = app.uv;
    vertexOut.albedoCoord = app.uv;
    vertexOut.roughnessMetallicCoord = app.uv;
    vertexOut.occlusionCoord = app.uv;
    vertexOut.normalCoord = app.uv;
    vertexOut.color = vec4(1.0);
    vertexOut.lighting = vec3(0.0);
    vertexOut.worldNormal = vec3(0.0, 0.0, 1.0);
    vertexOut.worldPosition = vec3(wPos4.xyz);
    
    gl_Position = getClipFromWorld() * wPos4;
    
    postprocessPosition(gl_Position);
}

