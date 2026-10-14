// Sun shadow map lookups (ShadowPass.java), shared by the raycast shader (voxel.fsh, Enhanced) and the
// fragment stage of the vanilla core shaders (entity_shade.glsl). Positions are relative to the block the
// camera is in. See ShadowPass for what the maps hold.

uniform vec3 uShadowDir;          // unit vector toward the light: the sun by day, the moon by night
uniform vec3 uShadowRight;        // the shadow maps' axes: orthonormal with uShadowDir
uniform vec3 uShadowUp;
uniform vec3 uShadowCenter[2];    // per cascade (0 near, 1 far): center of the map, snapped to texels
uniform float uShadowHalf[2];     // half width of the map in blocks
uniform float uShadowRay[2];      // distance from the map's plane to its center, along uShadowDir
uniform float uShadowTexels[2];   // texels per side
#ifdef SHADOW_PASS
uniform int uShadowCascade;       // the cascade being drawn
#else
uniform sampler2D uShadowMap0;    // R32F: ray distance from the plane to the first hit, SHADOW_MISS for none
uniform sampler2D uShadowMap1;
#endif

#ifndef SHADOW_PASS
const float SHADOW_MISS = 1.0e6;
const vec2 POISSON[12] = vec2[12](
  vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),
  vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893),
  vec2(0.507, 0.064), vec2(0.896, 0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598));

// Interleaved gradient noise (Jimenez): a cheap blue-ish noise from the pixel position, to rotate the kernel.
float shadowNoise() {
  return fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
}

// Stored distance of texel `t` of cascade `c`.
float shadowFetch(int c, ivec2 t) {
  int n = int(uShadowTexels[c]);
  t = clamp(t, ivec2(0), ivec2(n - 1));
  return c == 0 ? texelFetch(uShadowMap0, t, 0).r : texelFetch(uShadowMap1, t, 0).r;
}

// Where `pos` is in cascade `c`: xy the (fractional) texel, z the ray distance from the map's plane to
// the point, the number to compare with shadowFetch.
vec3 shadowCoords(int c, vec3 pos) {
  vec3 q = pos - uShadowCenter[c];
  vec2 uv = vec2(dot(q, uShadowRight), dot(q, uShadowUp)) / uShadowHalf[c] * 0.5 + 0.5;
  return vec3(uv * uShadowTexels[c], uShadowRay[c] - dot(q, uShadowDir));
}

// How far toward the edge of cascade `c`'s box `pos` is: 0 at the center, 1 at the edge, and 2 where
// the map has no rays (behind the plane or past the far end of the rays).
float shadowExtent(int c, vec3 pos) {
  vec3 sc = shadowCoords(c, pos);
  float n = uShadowTexels[c];
  float e = max(abs(sc.x / n - 0.5), abs(sc.y / n - 0.5)) * 2.0;
  if (sc.z < 0.0 || sc.z > uShadowRay[c] + 2.0 * uShadowHalf[c]) e = 2.0;
  return e;
}

// Filtered visibility of `pos` (on a surface facing `n`, with n . L = ndl) in cascade `c`: a blocker
// search to size the penumbra (it narrows toward the occluder, "contact hardening"), then a Poisson
// kernel of that size, rotated per pixel.
float shadowCascade(int c, vec3 pos, vec3 n, float ndl, float rot) {
  float s = 2.0 * uShadowHalf[c] / uShadowTexels[c];   // blocks per texel
  float tanT = clamp(sqrt(max(1.0 - ndl * ndl, 0.0)) / max(ndl, 0.05), 0.0, 4.0);
  // Push the point off the surface along its normal and give the compare a slope scaled bias.
  vec3 sc = shadowCoords(c, pos + n * (0.02 + s * 0.75));
  float rd = sc.z - (0.03 + s * (0.4 + 0.3 * tanT));

  float sn = sin(rot), cs = cos(rot);
  mat2 R = mat2(cs, sn, -sn, cs);

  float sum = 0.0, cnt = 0.0;
  for (int i = 0; i < 8; i++) {
    float ds = shadowFetch(c, ivec2(floor(sc.xy + R * POISSON[i] * 3.0)));
    if (ds < rd) { sum += ds; cnt += 1.0; }
  }
  if (cnt == 0.0) return 1.0;

  float width = clamp(0.035 * (sc.z - sum / cnt), s, 1.2);   // blocks
  float radius = clamp(width / s, 1.0, 12.0);                 // texels
  float lit = 0.0;
  for (int i = 0; i < 12; i++) {
    float ds = shadowFetch(c, ivec2(floor(sc.xy + R * POISSON[i] * radius)));
    lit += ds >= rd ? 1.0 : 0.0;
  }
  return lit / 12.0;
}

// Fraction of the light that reaches `pos`, a point on a surface facing `normal`: the near map, the
// far one past it, and `fallback` beyond the far map's fade (distance fade, docs 3.5).
float sunVisibility(vec3 pos, vec3 normal, float fallback) {
  float ndl = dot(normal, uShadowDir);
  if (ndl <= 0.0) return 0.0;

  float rot = 6.2831853 * shadowNoise();
  float e0 = shadowExtent(0, pos);
  if (e0 < 0.75) return shadowCascade(0, pos, normal, ndl, rot);

  float e1 = shadowExtent(1, pos);
  float far = e1 >= 0.95 ? fallback : mix(shadowCascade(1, pos, normal, ndl, rot), fallback, smoothstep(0.7, 0.95, e1));
  if (e0 >= 0.95) return far;
  return mix(shadowCascade(0, pos, normal, ndl, rot), far, smoothstep(0.75, 0.95, e0));
}

float sunVisibility(vec3 pos, vec3 normal) {
  return sunVisibility(pos, normal, 1.0);
}

// One tap, no normal offset, no filtering: for callers that sample many times per pixel, like volumetric light.
float sunVisibilityFast(vec3 pos) {
  int c = shadowExtent(0, pos) < 0.9 ? 0 : 1;
  if (shadowExtent(c, pos) >= 1.0) return 1.0;
  vec3 sc = shadowCoords(c, pos);
  return shadowFetch(c, ivec2(floor(sc.xy))) >= sc.z - 0.1 ? 1.0 : 0.0;
}
#endif
