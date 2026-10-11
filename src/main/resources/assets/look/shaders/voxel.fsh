#version 330 core

const int MAX_LEVELS = 8;

// uView / uProj are vanilla's matrices for this frame. Rays are expressed relative to the camera's
// block (uCamBlock), so float precision stays good no matter how far from the origin we are.
uniform mat4 uInvViewProj;
uniform mat4 uView;
uniform mat4 uProj;
uniform ivec3 uCamBlock;
uniform vec3 uCamFrac;
uniform ivec3 uGrid;       // indirection size: x cells, rows over all levels, z cells
uniform int uLevels;       // levels in the LOD pyramid
uniform float uBase;       // blocks covered by level 0 (horizontally); level k covers up to uBase * 2^k
uniform ivec3 uLevel[MAX_LEVELS];  // per level: lowest section y, section rows, first row in uIndirection
uniform float uMaxDist;
uniform int uDebug;        // 1: step-count heatmap instead of colors, 2: tint by pyramid level

uniform usampler3D uIndirection;  // section -> brick slot + 1, 0 when empty
uniform usamplerBuffer uBricks;   // 4096 u16 voxel ids per slot
uniform usamplerBuffer uColors;   // voxel id -> 0xRRGGBB, used when a block has no texture
uniform samplerBuffer uFaces;     // per (id, face, layer): atlas uv mapping, see BlockClassifier
uniform sampler2D uAtlas;         // vanilla block atlas
uniform vec2 uAtlasSize;          // atlas size in texels
uniform float uMaxLod;            // highest mip level of the atlas
uniform float uPixelAngle;        // radians covered by one pixel

in vec2 vNdc;
out vec4 fragColor;

const float EPS = 1e-3;

uint readSlot(int lvl, ivec3 sec) {
  ivec3 c = ivec3(sec.x & (uGrid.x - 1), uLevel[lvl].z + sec.y - uLevel[lvl].x, sec.z & (uGrid.z - 1));
  return texelFetch(uIndirection, c, 0).r;
}

// Pyramid level to read at a position relative to the camera. Level k holds voxels of 2^k blocks and
// is used for horizontal (Chebyshev) distances in [uBase * 2^(k-1), uBase * 2^k), matching the square
// windows the sections are built in. Along a ray the distance only grows, so the level never drops.
int levelAt(vec3 rel) {
  float h = max(abs(rel.x), abs(rel.z));
  if (h < uBase) return 0;
  return min(uLevels - 1, 1 + int(floor(log2(h / uBase))));
}

// Surface color of voxel `id` where the ray hits face `axis`. `local` is the hit point inside the
// voxel (0..1) and t the ray distance. Returns false for transparent texels (leaves etc.), so the
// caller keeps marching.
bool surface(uint id, int axis, vec3 d, vec3 local, float t, out vec3 albedo) {
  // Face order matches Direction: down, up, north, south, west, east.
  int face = axis == 0 ? (d.x > 0.0 ? 4 : 5) : (axis == 1 ? (d.y > 0.0 ? 0 : 1) : (d.z > 0.0 ? 2 : 3));
  vec2 ab = axis == 0 ? local.yz : (axis == 1 ? local.xz : local.xy);
  ab = clamp(ab, 0.001, 0.999);

  int base = (int(id) * 6 + face) * 4;
  vec3 rgb = vec3(0.0);
  float alpha = 0.0;

  for (int layer = 0; layer < 2; layer++) {
    vec4 a = texelFetch(uFaces, base + layer * 2);
    vec4 b = texelFetch(uFaces, base + layer * 2 + 1);
    if (b.w < 0.5) break;

    vec2 uv = a.xy + ab.x * a.zw + ab.y * b.xy;
    float texelsPerBlock = max(length(a.zw * uAtlasSize), length(b.xy * uAtlasSize));
    float lod = clamp(log2(max(t * uPixelAngle * texelsPerBlock, 1e-6)), 0.0, uMaxLod);
    vec4 s = textureLod(uAtlas, uv, lod);

    int tint = int(b.z);
    s.rgb *= vec3(float((tint >> 16) & 255), float((tint >> 8) & 255), float(tint & 255)) / 255.0;

    rgb = layer == 0 ? s.rgb : mix(rgb, s.rgb, s.a);
    alpha = layer == 0 ? s.a : max(alpha, s.a);
  }

  if (alpha < 0.5) {
    // No layers at all means no model data: use the map color. Otherwise it is a see-through texel.
    if (texelFetch(uFaces, base + 1).w < 0.5) {
      uint c = texelFetch(uColors, int(id)).r;
      albedo = vec3(float((c >> 16) & 255u), float((c >> 8) & 255u), float(c & 255u)) / 255.0;
      return true;
    }
    return false;
  }

  albedo = rgb;
  return true;
}

// Two-level DDA over the LOD pyramid: whole sections are skipped when empty, voxels are stepped
// inside bricks. At level k every voxel is 2^k blocks wide and a section is 16 voxels, but the
// stepping is the same, so all the lengths below are just scaled by the voxel size.
// o is relative to uCamBlock. d is normalized with no zero components.
bool trace(vec3 o, vec3 d, out float tHit, out uint hitId, out int hitAxis, out ivec3 hitVoxel, out int hitLevel, out vec3 albedo, out int steps) {
  steps = 0;
  vec3 invD = 1.0 / d;
  ivec3 stp = ivec3(sign(d));
  vec3 up = vec3(d.x > 0.0 ? 1.0 : 0.0, d.y > 0.0 ? 1.0 : 0.0, d.z > 0.0 ? 1.0 : 0.0);

  float t = 0.0;
  int axis = 1;

  for (int i = 0; i < 400; i++) {
    if (t > uMaxDist) return false;
    steps++;

    vec3 rel = o + d * t;
    int lvl = levelAt(rel);
    float vs = float(1 << lvl);       // blocks per voxel
    int secShift = lvl + 4;           // block -> section
    // Float spacing grows with distance, so the nudge past a boundary has to as well.
    float eps = EPS * vs + t * 2e-7;

    ivec3 vox = uCamBlock + ivec3(floor(rel));  // block
    ivec3 sec = vox >> secShift;
    ivec3 rows = uLevel[lvl];

    // Left the vertical extent of the level while heading away from it.
    if (sec.y < rows.x && d.y <= 0.0) return false;
    if (sec.y >= rows.x + rows.y && d.y >= 0.0) return false;

    ivec3 secMin = sec << secShift;   // block
    uint slot = 0u;
    if (sec.y >= rows.x && sec.y < rows.x + rows.y) slot = readSlot(lvl, sec);

    if (slot == 0u) {
      // Jump to where the ray leaves this (empty) section.
      vec3 bound = vec3(secMin - uCamBlock) + up * (vs * 16.0);
      vec3 tb = (bound - o) * invD;
      float te = min(tb.x, min(tb.y, tb.z));
      axis = (tb.x <= tb.y && tb.x <= tb.z) ? 0 : (tb.y <= tb.z ? 1 : 2);
      t = te + eps;
      continue;
    }

    ivec3 v = clamp((vox >> lvl) - (sec << 4), ivec3(0), ivec3(15));
    vec3 next = vec3(secMin - uCamBlock) + (vec3(v) + up) * vs;
    vec3 tMax = (next - o) * invD;
    vec3 tDelta = abs(invD) * vs;
    int base = int(slot - 1u) * 4096;
    float tc = t;

    for (int j = 0; j < 48; j++) {
      steps++;
      uint id = texelFetch(uBricks, base + (v.y * 16 + v.z) * 16 + v.x).r;
      if (id != 0u) {
        ivec3 voxel = secMin + (v << lvl);   // min corner, block
        vec3 local = (o + d * tc - vec3(voxel - uCamBlock)) / vs;
        // The texture covers the whole voxel, so a pixel spans 1/vs as many texels per ray length.
        if (surface(id, axis, d, local, tc / vs, albedo)) {
          tHit = tc;
          hitId = id;
          hitAxis = axis;
          hitVoxel = voxel;
          hitLevel = lvl;
          return true;
        }
      }

      if (tMax.x < tMax.y && tMax.x < tMax.z) {
        tc = tMax.x; tMax.x += tDelta.x; v.x += stp.x; axis = 0;
      } else if (tMax.y < tMax.z) {
        tc = tMax.y; tMax.y += tDelta.y; v.y += stp.y; axis = 1;
      } else {
        tc = tMax.z; tMax.z += tDelta.z; v.z += stp.z; axis = 2;
      }

      if (any(lessThan(v, ivec3(0))) || any(greaterThan(v, ivec3(15)))) break;
    }

    t = tc + eps;
  }

  return false;
}

vec3 heat(float x) {
  x = clamp(x, 0.0, 1.0);
  return vec3(clamp(4.0 * x - 2.0, 0.0, 1.0), clamp(2.0 - abs(4.0 * x - 2.0), 0.0, 1.0), clamp(2.0 - 4.0 * x, 0.0, 1.0));
}

void main() {
  vec4 n = uInvViewProj * vec4(vNdc, -1.0, 1.0);
  vec4 f = uInvViewProj * vec4(vNdc, 1.0, 1.0);
  vec3 nearP = n.xyz / n.w;
  vec3 d = normalize(f.xyz / f.w - nearP);

  // Axis-parallel rays would divide by zero in the DDA.
  d = vec3(abs(d.x) < 1e-6 ? 1e-6 : d.x, abs(d.y) < 1e-6 ? 1e-6 : d.y, abs(d.z) < 1e-6 ? 1e-6 : d.z);

  vec3 o = nearP + uCamFrac;

  float tHit;
  uint id;
  int axis;
  ivec3 voxel;
  vec3 albedo;
  int level = 0;
  int steps;
  bool hit = trace(o, d, tHit, id, axis, voxel, level, albedo, steps);

  if (!hit && uDebug != 1) discard;

  // Same depth vanilla would have written for this point (camera-relative position).
  float depth = 1.0;
  if (hit) {
    vec3 rel = nearP + d * tHit;
    vec4 clip = uProj * (uView * vec4(rel, 1.0));
    depth = clip.z / clip.w * 0.5 + 0.5;
    if (depth > 1.0) {
      if (uDebug != 1) discard;
      depth = 1.0;
    }
  }
  gl_FragDepth = depth;

  if (uDebug == 1) {
    // Misses are shown too: rays that march the full distance are the expensive ones.
    fragColor = vec4(heat(float(steps) / 150.0), 1.0);
    return;
  }

  // Flat per-face shading, same factors vanilla uses for its directional light.
  float shade = axis == 1 ? (d.y < 0.0 ? 1.0 : 0.5) : (axis == 0 ? 0.6 : 0.8);

  if (uDebug == 2) {
    vec3 tint = 0.5 + 0.5 * cos(6.28318 * (float(level) * 0.17 + vec3(0.0, 0.33, 0.67)));
    albedo = mix(albedo, tint, 0.6);
  }

  fragColor = vec4(albedo * shade, 1.0);
}
