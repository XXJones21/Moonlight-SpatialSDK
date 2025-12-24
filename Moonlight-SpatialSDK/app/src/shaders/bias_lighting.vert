#version 430
#extension GL_ARB_separate_shader_objects:enable
#extension GL_ARB_shading_language_420pack:enable

#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>

#include <customBindingVert.glsl>

layout(location = 10) out struct {
  vec2 texCoord;
  float falloffDistance;
} biasOut;

void main(){
  App2VertexUnpacked app=getApp2VertexUnpacked();

  vec4 wPos4=g_PrimitiveUniform.worldFromObject*vec4(app.position,1.f);

  // Pass through UV coordinates for edge sampling
  biasOut.texCoord = app.uv;
  
  // Calculate falloff based on local Y position (0 = inner edge, 1 = outer edge)
  // The mesh is oriented so Y=0 is at panel edge, Y=1 is outer edge
  biasOut.falloffDistance = app.uv.y;

  gl_Position=getClipFromWorld()*wPos4;

  postprocessPosition(gl_Position);
}

