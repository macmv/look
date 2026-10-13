#version 330 core

// Auto-exposure, step 2: moves the stored exposure toward what the frame needs, faster one way than
// the other. Writes a 1x1 float texture; uPrev is the other half of the ping-pong pair.

uniform sampler2D uLum;     // see luma.fsh, mipmapped down to 1x1 at level uTopLevel
uniform sampler2D uPrev;
uniform float uTopLevel;
uniform float uDt;
uniform float uKey;
uniform float uMin;
uniform float uMax;
uniform float uUp;
uniform float uDown;
uniform int uReset;

in vec2 vNdc;
out vec4 fragColor;

void main() {
  vec2 s = textureLod(uLum, vec2(0.5), uTopLevel).rg;
  float prev = texelFetch(uPrev, ivec2(0), 0).r;
  if (uReset != 0) prev = 1.0;
  if (s.g < 0.01) {
    fragColor = vec4(prev, 0, 0, 1);
    return;
  }
  float avg = exp2(s.r / s.g);
  float target = clamp(uKey / avg, uMin, uMax);
  if (uReset != 0) {
    fragColor = vec4(target, 0, 0, 1);
    return;
  }
  float lp = log2(prev), lt = log2(target);
  float rate = lt > lp ? uUp : uDown;
  fragColor = vec4(exp2(mix(lp, lt, 1.0 - exp(-rate * uDt))), 0, 0, 1);
}
