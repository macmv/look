// Refraction of vanilla geometry (entities, particles, the block outline) seen through the water
// surface, pasted into the vertex stage of vanilla's core shaders by EntityRefraction, which swaps the
// eye space position of each vertex for `look_eye(ModelViewMat, Position)`.
//
// The raycast shader bends the ray of every water pixel by Snell's law and traces on. This moves each
// vertex to where that image of it is: the point on the surface where the camera's ray bends to pass
// through the vertex is found, and the vertex is put on the camera's ray through that point, so it
// lands on the pixel the bent ray would have shown it on. The distance used is the length of the bent
// path, which is also the depth the raycast shader writes for refracted terrain (see voxel.fsh).
//
// Only a camera above the surface looking at geometry below it is handled, the same cases the raycast
// shader refracts with a flat surface. The surface is flat, with the waves only tilting its normal
// (water_waves.glsl), exactly as in voxel.fsh.

uniform int LookOn;               // 0 outside the world pass: nothing moves
uniform mat3 LookView;            // camera rotation: camera-relative world to eye space
uniform ivec3 LookCamBlock;       // block the camera is in
uniform vec3 LookCamFrac;         // camera position inside that block
uniform ivec2 LookWindow;         // block x, z of texel (0, 0) of LookWater
uniform sampler2D LookWater;      // surface height over each column of the window, LOOK_NO_WATER for none
uniform float uWaterWaves;
uniform float LookTime;           // uTime of voxel.fsh
uniform float LookPixelAngle;     // uPixelAngle of voxel.fsh

#include "water_waves.glsl"

const int LOOK_SIZE = 128;         // EntityRefraction.SIZE
const float LOOK_NO_WATER = -1.0e4;
const float LOOK_IOR = 1.33;       // WATER_IOR of voxel.fsh
const float LOOK_MIN_HEIGHT = 0.05;   // the camera has to be this far above the surface

// Height of the water surface over block column `col` (absolute x, z), LOOK_NO_WATER when there is none.
float look_surface(ivec2 col) {
  ivec2 t = col - LookWindow;
  if (t.x < 0 || t.y < 0 || t.x >= LOOK_SIZE || t.y >= LOOK_SIZE) return LOOK_NO_WATER;
  return texelFetch(LookWater, t, 0).r;
}

// Block column of a camera-relative position.
ivec2 look_column(vec2 rel) {
  return LookCamBlock.xz + ivec2(floor(LookCamFrac.xz + rel));
}

// The camera-relative position `e` (world axes) where the camera sees the vertex that is really there.
vec3 look_refract(vec3 e) {
  float hs = look_surface(look_column(e.xz));
  if (hs < 0.5 * LOOK_NO_WATER) return e;

  float cy = float(LookCamBlock.y) + LookCamFrac.y;   // camera height
  float a = cy - hs;                                   // camera above the surface
  float b = hs - (cy + e.y);                           // vertex below it
  float r = length(e.xz);
  if (a < LOOK_MIN_HEIGHT || b <= 0.0 || r < 1e-3) return e;

  // Flat surface: x is the horizontal distance from the camera to the point where the ray enters. The
  // sines of the two angles to the vertical obey sin1 = n * sin2, and sin1 - n * sin2 grows with x.
  float lo = 0.0;
  float hi = r;
  for (int i = 0; i < 16; i++) {
    float x = 0.5 * (lo + hi);
    float rx = r - x;
    float s1 = x * inversesqrt(x * x + a * a);
    float s2 = rx * inversesqrt(rx * rx + b * b);
    if (s1 > LOOK_IOR * s2) hi = x; else lo = x;
  }
  float x = 0.5 * (lo + hi);
  vec2 u = e.xz / r;
  vec2 P = u * x;

  if (uWaterWaves > 0.0) {
    // The waves tilt the normal, which moves where the bent ray lands. Correct the entry point by the
    // miss, scaled by how far the flat solution moves the landing point per unit of entry point.
    float rx = r - x;
    float cos1 = a * inversesqrt(x * x + a * a);
    float cos2 = b * inversesqrt(rx * rx + b * b);
    float along = 1.0 + (b / a) * (cos1 * cos1 * cos1) / (LOOK_IOR * cos2 * cos2 * cos2);
    float across = r / x;
    for (int k = 0; k < 8; k++) {
      vec3 Pv = vec3(P.x, hs - cy, P.y);
      vec3 d = normalize(Pv);
      vec2 wp = vec2(LookCamBlock.xz & 4095) + LookCamFrac.xz + P;
      float foot = length(Pv) * LookPixelAngle / max(abs(d.y), 0.05);
      vec2 g = waveSlope(wp, LookTime, foot);
      vec3 N = normalize(vec3(-g.x, 1.0, -g.y));
      vec3 T = refract(d, N, 1.0 / LOOK_IOR);
      if (T.y > -0.01) break;
      vec2 miss = e.xz - (P + T.xz * (b / -T.y));
      vec2 pu = normalize(P);
      vec2 pw = vec2(-pu.y, pu.x);
      P += pu * (dot(miss, pu) / along) + pw * (dot(miss, pw) / across);
    }
  }

  // The ray has to enter where there is water, else the terrain hides it (or it is not refracted).
  if (look_surface(look_column(P)) < 0.5 * LOOK_NO_WATER) return e;

  vec3 Pv = vec3(P.x, hs - cy, P.y);
  return normalize(Pv) * (length(Pv) + length(e - Pv));
}

// Vanilla's eye space position of `pos`, moved to where the water shows it.
vec4 look_eye(mat4 mv, vec3 pos) {
  vec4 eye = mv * vec4(pos, 1.0);
  if (LookOn == 0) return eye;
  eye.xyz = LookView * look_refract(transpose(LookView) * eye.xyz);
  return eye;
}
