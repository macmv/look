#version 330 core

// Enhanced resolve pass: takes the HDR world pass, adds bloom, applies exposure, tonemap and color
// grading, and writes it into the bound framebuffer (the main one in game) together with the depth,
// so vanilla's entities, particles and weather draw on top.
//
// A temporal stage (TAA) slots in before bloom: it would replace uColor with the accumulated image.

uniform sampler2D uColor;   // RGBA16F world pass, straight alpha, alpha 0 where nothing was hit
uniform sampler2D uDepth;   // depth of the world pass
uniform sampler2D uBloom;   // half resolution blurred bright parts, see PostChain
uniform sampler2D uExpTex;  // 1x1 automatic exposure
uniform float uExposure;    // fixed multiplier
uniform int uAutoExposure;
uniform float uAutoStrength;
uniform float uBloomStrength; // 0 when bloom is off
uniform int uTonemap;       // 0 clamp, 1 shoulder, 2 ACES, 3 Reinhard
uniform int uGrading;
uniform float uSaturation;
uniform float uVibrance;
uniform float uContrast;
uniform float uBrightness;
uniform vec3 uTint;

in vec2 vNdc;
out vec4 fragColor;

vec3 tonemap(vec3 c) {
  if (uTonemap == 1) {
    // Identity up to T, then an exponential roll-off that reaches 1 asymptotically and is smooth at T.
    const float T = 0.8;
    vec3 over = max(c - T, 0.0);
    return min(c, vec3(T)) + (1.0 - T) * (1.0 - exp(-over / (1.0 - T)));
  } else if (uTonemap == 2) {
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
    return clamp(c, 0.0, 1.0);
  } else if (uTonemap == 3) {
    return c / (1.0 + c);
  }
  return clamp(c, 0.0, 1.0);
}

vec3 grade(vec3 c) {
  c *= uTint;
  c += uBrightness;
  c = (c - 0.5) * uContrast + 0.5;
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
  float k = uSaturation * (1.0 + uVibrance * (1.0 - clamp(sat, 0.0, 1.0)));
  c = mix(vec3(l), c, k);
  return clamp(c, 0.0, 1.0);
}

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  vec4 c = texelFetch(uColor, px, 0);
  if (c.a == 0.0) discard;

  gl_FragDepth = texelFetch(uDepth, px, 0).r;

  vec3 hdr = c.rgb;
  if (uBloomStrength > 0.0) {
    hdr += texture(uBloom, gl_FragCoord.xy / vec2(textureSize(uColor, 0))).rgb * uBloomStrength;
  }

  float exposure = uExposure;
  if (uAutoExposure != 0) exposure *= pow(texelFetch(uExpTex, ivec2(0), 0).r, uAutoStrength);

  vec3 ldr = tonemap(hdr * exposure);
  if (uGrading != 0) ldr = grade(ldr);
  fragColor = vec4(ldr, c.a);
}
