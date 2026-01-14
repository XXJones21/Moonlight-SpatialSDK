/**
 * Vertex shader for 9-slice bias lighting effect.
 * Based on Meta Spatial Scanner's 9slice.vert
 */

#version 430
#extension GL_ARB_separate_shader_objects: enable
#extension GL_ARB_shading_language_420pack: enable

#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>
#include <customBindingVert.glsl>

void main() {
    App2VertexUnpacked app = getApp2VertexUnpacked();

    vec4 wPos4 = g_PrimitiveUniform.worldFromObject * vec4(app.position, 1.0f);
    vertexOut.albedoCoord = app.uv;
    vertexOut.worldPosition = wPos4.xyz;
    vertexOut.worldNormal = normalize((transpose(g_PrimitiveUniform.objectFromWorld) * vec4(app.normal, 0.0f)).xyz);

    gl_Position = getClipFromWorld() * wPos4;

    postprocessPosition(gl_Position);
}

