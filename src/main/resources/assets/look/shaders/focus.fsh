#version 330 core

// Depth of field focus distance: the world depth at the center of the screen, smoothed over time in
// a 1x1 float texture (ping-pong, like exposure.fsh). Sky counts as far away.

uniform sampler2D uDepth;
uniform sampler2D uPrev;
uniform float uA;           // projection[2][2]
uniform float uB;           // projection[3][2]
uniform float uDt;
uniform int uReset;

in vec2 vNdc;
out vec4 fragColor;

void main() {
  ivec2 size = textureSize(uDepth, 0);
  float d = 0.0;
  for (int i = 0; i < 9; i++) {
    ivec2 o = ivec2(i % 3 - 1, i / 3 - 1) * 2;
    d += texelFetch(uDepth, clamp(size / 2 + o, ivec2(0), size - 1), 0).r;
  }
  d /= 9.0;
  float dist = d >= 0.99999 ? 512.0 : uB / (d * 2.0 - 1.0 + uA);
  dist = clamp(dist, 0.3, 512.0);
  float prev = texelFetch(uPrev, ivec2(0), 0).r;
  if (uReset != 0 || prev <= 0.0) {
    fragColor = vec4(dist, 0, 0, 1);
    return;
  }
  fragColor = vec4(exp2(mix(log2(prev), log2(dist), 1.0 - exp(-4.0 * uDt))), 0, 0, 1);
}
