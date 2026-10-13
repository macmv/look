#version 330 core

// Bloom upsample: 3x3 tent over the smaller mip, added onto the bigger one by blending.

uniform sampler2D uSrc;
uniform vec2 uTexel;       // 1 / source size
uniform float uRadius;

in vec2 vNdc;
out vec4 fragColor;

void main() {
  vec2 uv = vNdc * 0.5 + 0.5;
  vec2 o = uTexel * uRadius;
  vec3 s = texture(uSrc, uv).rgb * 4.0;
  s += texture(uSrc, uv + vec2(-o.x, 0)).rgb * 2.0 + texture(uSrc, uv + vec2(o.x, 0)).rgb * 2.0;
  s += texture(uSrc, uv + vec2(0, -o.y)).rgb * 2.0 + texture(uSrc, uv + vec2(0, o.y)).rgb * 2.0;
  s += texture(uSrc, uv + vec2(-o.x, -o.y)).rgb + texture(uSrc, uv + vec2(o.x, -o.y)).rgb;
  s += texture(uSrc, uv + vec2(-o.x, o.y)).rgb + texture(uSrc, uv + vec2(o.x, o.y)).rgb;
  fragColor = vec4(s / 16.0, 1.0);
}
