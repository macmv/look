#version 330 core

// Bloom downsample: 13 taps over the source (a 4x4 texel area), and on the first pass a soft
// threshold so only the bright parts go down the chain.

uniform sampler2D uSrc;
uniform vec2 uTexel;       // 1 / source size
uniform int uFirst;
uniform float uThreshold;
uniform float uKnee;

in vec2 vNdc;
out vec4 fragColor;

vec3 tap(vec2 uv, vec2 o) {
  vec3 c = min(texture(uSrc, uv + o * uTexel).rgb, vec3(64.0));
  if (uFirst != 0) {
    float br = max(c.r, max(c.g, c.b));
    float soft = clamp(br - uThreshold + uKnee, 0.0, 2.0 * uKnee);
    soft = soft * soft / (4.0 * uKnee + 1e-4);
    c *= max(soft, br - uThreshold) / max(br, 1e-4);
  }
  return c;
}

void main() {
  vec2 uv = vNdc * 0.5 + 0.5;
  vec3 e = tap(uv, vec2(0, 0));
  vec3 a = tap(uv, vec2(-2, 2)), b = tap(uv, vec2(0, 2)), c = tap(uv, vec2(2, 2));
  vec3 d = tap(uv, vec2(-2, 0)), f = tap(uv, vec2(2, 0));
  vec3 g = tap(uv, vec2(-2, -2)), h = tap(uv, vec2(0, -2)), i = tap(uv, vec2(2, -2));
  vec3 j = tap(uv, vec2(-1, 1)), k = tap(uv, vec2(1, 1)), l = tap(uv, vec2(-1, -1)), m = tap(uv, vec2(1, -1));
  vec3 r = e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
  fragColor = vec4(r, 1.0);
}
