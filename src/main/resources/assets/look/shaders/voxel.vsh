#version 330 core

// One oversized triangle covering the whole screen, generated from the vertex id.
out vec2 vNdc;

void main() {
  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
  vNdc = p * 2.0 - 1.0;
  gl_Position = vec4(vNdc, 0.0, 1.0);
}
