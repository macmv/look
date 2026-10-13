#version 330 core

// Enhanced resolve pass: takes the HDR world pass and writes it into the bound framebuffer (the main
// one in game) together with the depth, so vanilla's entities, particles and weather draw on top.

uniform sampler2D uColor;   // RGBA16F world pass, straight alpha, alpha 0 where nothing was hit
uniform sampler2D uDepth;   // depth of the world pass
uniform float uExposure;

in vec2 vNdc;
out vec4 fragColor;

// Plain for now: exposure and a clamp, so Enhanced matches Vanilla until the lighting changes.
vec3 tonemap(vec3 c) {
  return clamp(c * uExposure, 0.0, 1.0);
}

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  vec4 c = texelFetch(uColor, px, 0);
  if (c.a == 0.0) discard;

  gl_FragDepth = texelFetch(uDepth, px, 0).r;
  fragColor = vec4(tonemap(c.rgb), c.a);
}
