#version 330 core

// Auto-exposure, step 1: a small texture of (log2 luminance * coverage, coverage) of the HDR image.
// Its mip chain averages it down to one texel, where the ratio is the covered mean log luminance.

uniform sampler2D uColor;
uniform ivec2 uOutSize;

in vec2 vNdc;
out vec4 fragColor;

void main() {
  ivec2 src = textureSize(uColor, 0);
  vec2 cell = vec2(src) / vec2(uOutSize);
  vec2 origin = floor(gl_FragCoord.xy) * cell;
  vec2 sum = vec2(0.0);
  for (int y = 0; y < 4; y++) {
    for (int x = 0; x < 4; x++) {
      ivec2 p = min(ivec2(origin + (vec2(x, y) + 0.5) * 0.25 * cell), src - 1);
      vec4 c = texelFetch(uColor, p, 0);
      float l = dot(min(c.rgb, vec3(64.0)), vec3(0.2126, 0.7152, 0.0722));
      sum += vec2(log2(max(l, 1e-3)) * c.a, c.a);
    }
  }
  fragColor = vec4(sum / 16.0, 0.0, 1.0);
}
