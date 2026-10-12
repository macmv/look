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
uniform int uDebug;        // 1: step-count heatmap instead of colors, 2: tint by pyramid level, 3: no mipmaps, 4: unlit

uniform usampler3D uIndirection;  // section -> brick slot + 1, 0 when empty
uniform usamplerBuffer uBricks;   // 4096 u16 voxel ids per slot
uniform usamplerBuffer uColors;   // voxel id -> 0xRRGGBB, used when a block has no texture
uniform samplerBuffer uFaces;     // per (id, face, layer): atlas uv mapping, see BlockClassifier
uniform sampler2D uAtlas;         // vanilla block atlas
uniform sampler2DArray uTint;     // biome tint per column, see TintMap: grass, foliage, lower grass, lower foliage, split, far grass per level
uniform vec2 uAtlasSize;          // atlas size in texels
uniform float uMaxLod;            // highest mip level of the atlas
uniform int uMinY;               // lowest block y of the level, for the tint's lower biome
uniform float uPixelAngle;        // radians covered by one pixel
uniform usampler3D uLightIndirection;  // level 0 section -> light slot + 1, 0 when unknown, see LightMap
uniform usamplerBuffer uLight;    // 4096 u8 per slot: sky light << 4 | block light
uniform float uSkyDarken;         // vanilla's sky brightness for the time of day and weather, 0.2..1
uniform float uAmbientLight;      // dimension ambient light, lifts the darkest light levels

in vec2 vNdc;
out vec4 fragColor;

const float EPS = 1e-3;
const int TINT_BLOCKS = 2048;  // blocks the tint texture covers before wrapping: TintMap.SIZE * CELL
const int TINT_CELL = 4;       // TintMap.CELL
const int TINT_SPLIT_UNIT = 2; // TintMap.SPLIT_UNIT
const int TINT_SIZE = 512;     // TintMap.SIZE
const int TINT_FAR_FIRST = 5;  // TintMap.FAR_FIRST
const int FACE_ROW_TEXELS = 24;  // BlockClassifier.ID_FLOATS / 4: texels in one row of the face table
const int META_START = (16384 + 1024) * FACE_ROW_TEXELS;  // BlockClassifier.META_START / 4
const int META_TEXELS = 3;       // BlockClassifier.META_FLOATS / 4

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

// True when the level-`lvl` voxel at integer coordinate `vc` (in voxels of that level) occludes light.
bool occludes(int lvl, ivec3 vc) {
  ivec3 sec = vc >> 4;
  ivec3 rows = uLevel[lvl];
  if (sec.y < rows.x || sec.y >= rows.x + rows.y) return false;
  uint slot = readSlot(lvl, sec);
  if (slot == 0u) return false;
  ivec3 l = vc & 15;
  uint id = texelFetch(uBricks, int(slot - 1u) * 4096 + (l.y * 16 + l.z) * 16 + l.x).r;
  if (id == 0u) return false;
  return ((texelFetch(uColors, int(id)).r >> 24) & 1u) != 0u;
}

// Sky and block light (0..15) at a block, level 0. Unknown sections count as open sky.
vec2 lightAt(ivec3 b) {
  ivec3 sec = b >> 4;
  if (sec.y < uLevel[0].x || sec.y >= uLevel[0].x + uLevel[0].y) return vec2(15.0, 0.0);
  uint slot = texelFetch(uLightIndirection, ivec3(sec.x & (uGrid.x - 1), sec.y - uLevel[0].x, sec.z & (uGrid.z - 1)), 0).r;
  if (slot == 0u) return vec2(15.0, 0.0);
  ivec3 l = b & 15;
  uint v = texelFetch(uLight, int(slot - 1u) * 4096 + (l.y * 16 + l.z) * 16 + l.x).r;
  return vec2(float(v >> 4), float(v & 15u));
}

// Vanilla's corner rule: 0 (darkest) to 3 (open).
float cornerAO(bool side1, bool side2, bool corner) {
  if (side1 && side2) return 0.0;
  return 3.0 - float(side1) - float(side2) - float(corner);
}

// Ambient occlusion brightness for a hit on the level 0 voxel `voxel` (block coords) through face
// `axis`. `p` is the hit point inside that voxel (0..1). Corner values come from the 8 voxels around
// the face in the layer in front of it, interpolated like vanilla smooth lighting, with the quad
// split along the diagonal that keeps the gradient from showing a seam.
float ambientOcclusion(ivec3 voxel, int axis, vec3 d, vec3 p, out vec2 light) {
  ivec3 n = ivec3(0);
  n[axis] = d[axis] > 0.0 ? -1 : 1;
  ivec3 f = voxel + n;

  // In-face axes, matching the (a, b) order used for textures: yz, xz, xy.
  ivec3 eu = axis == 0 ? ivec3(0, 1, 0) : ivec3(1, 0, 0);
  ivec3 ev = axis == 2 ? ivec3(0, 1, 0) : ivec3(0, 0, 1);
  vec2 uv = clamp(vec2(dot(p, vec3(eu)), dot(p, vec3(ev))), 0.0, 1.0);

  bool um = occludes(0, f - eu), up = occludes(0, f + eu);
  bool vm = occludes(0, f - ev), vp = occludes(0, f + ev);
  bool c00 = occludes(0, f - eu - ev), c10 = occludes(0, f + eu - ev);
  bool c01 = occludes(0, f - eu + ev), c11 = occludes(0, f + eu + ev);
  float a00 = cornerAO(um, vm, c00);
  float a10 = cornerAO(up, vm, c10);
  float a01 = cornerAO(um, vp, c01);
  float a11 = cornerAO(up, vp, c11);

  // Light at each corner: the average of the open voxels around it, like vanilla's smooth lighting.
  vec2 l0 = lightAt(f);
  vec2 lum = um ? vec2(0.0) : lightAt(f - eu), lup = up ? vec2(0.0) : lightAt(f + eu);
  vec2 lvm = vm ? vec2(0.0) : lightAt(f - ev), lvp = vp ? vec2(0.0) : lightAt(f + ev);
  vec2 l00 = (um && vm) || c00 ? vec2(0.0) : lightAt(f - eu - ev);
  vec2 l10 = (up && vm) || c10 ? vec2(0.0) : lightAt(f + eu - ev);
  vec2 l01 = (um && vp) || c01 ? vec2(0.0) : lightAt(f - eu + ev);
  vec2 l11 = (up && vp) || c11 ? vec2(0.0) : lightAt(f + eu + ev);
  vec2 k00 = (l0 + lum + lvm + l00) / (1.0 + float(!um) + float(!vm) + float(!((um && vm) || c00)));
  vec2 k10 = (l0 + lup + lvm + l10) / (1.0 + float(!up) + float(!vm) + float(!((up && vm) || c10)));
  vec2 k01 = (l0 + lum + lvp + l01) / (1.0 + float(!um) + float(!vp) + float(!((um && vp) || c01)));
  vec2 k11 = (l0 + lup + lvp + l11) / (1.0 + float(!up) + float(!vp) + float(!((up && vp) || c11)));

  float u = uv.x, v = uv.y;
  float ao;
  if (a00 + a11 > a01 + a10) {
    ao = u >= v ? a00 + u * (a10 - a00) + v * (a11 - a10)
                : a00 + v * (a01 - a00) + u * (a11 - a01);
    light = u >= v ? k00 + u * (k10 - k00) + v * (k11 - k10)
                   : k00 + v * (k01 - k00) + u * (k11 - k01);
  } else {
    bool low = u + v <= 1.0;
    ao = low ? a00 + u * (a10 - a00) + v * (a01 - a00)
             : a11 + (1.0 - u) * (a01 - a11) + (1.0 - v) * (a10 - a11);
    light = low ? k00 + u * (k10 - k00) + v * (k01 - k00)
                : k11 + (1.0 - u) * (k01 - k11) + (1.0 - v) * (k10 - k11);
  }
  return 0.55 + 0.15 * ao;
}

// PCG3D integer hash (Jarzynski and Olano), for stable per-voxel randomness.
uint hash3(ivec3 p) {
  uvec3 v = uvec3(p) * 1664525u + 1013904223u;
  v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
  v ^= v >> 16u;
  v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
  return v.x;
}

// Row of the face table to use for voxel `id` at `cell` (block coordinates). Blocks with
// several weighted variants, like the rotations of grass, get one picked from the position, so the
// choice is stable and neighbors differ. Variant 0 is row `id`, the others are consecutive extra rows.
int variantRow(uint id, ivec3 cell) {
  int meta = META_START + int(id) * META_TEXELS;
  vec4 m = texelFetch(uFaces, meta);
  int count = int(m.y);
  if (count < 2) return int(id);

  float r = float(hash3(cell) >> 8u) / 16777216.0;
  vec4 lo = texelFetch(uFaces, meta + 1);
  vec4 hi = texelFetch(uFaces, meta + 2);

  int v = 0;
  for (int i = 0; i < count - 1; i++) {
    float cum = i < 4 ? lo[i] : hi[i - 4];
    if (r < cum) break;
    v++;
  }
  return v == 0 ? int(id) : int(m.x) + v - 1;
}

// Surface color of voxel `id` where the ray hits face `axis`. `local` is the hit point inside the
// voxel (0..1) and t the ray distance in blocks. Returns false for transparent texels (leaves etc.),
// so the caller keeps marching. `column` is the world column of the voxel's center, for biome tint,
// and `corner` its min corner in blocks, with `vs` blocks per voxel: the face shows the block texture
// tiled once per block, so a coarse voxel looks like a patch of ordinary blocks, not one huge block.
bool surface(uint id, ivec3 corner, float vs, int axis, vec3 d, vec3 local, float t, vec2 column, float lowLayer, vec3 farTint, out vec3 albedo) {
  // Face order matches Direction: down, up, north, south, west, east.
  int face = axis == 0 ? (d.x > 0.0 ? 4 : 5) : (axis == 1 ? (d.y > 0.0 ? 0 : 1) : (d.z > 0.0 ? 2 : 3));
  vec2 ab = axis == 0 ? local.yz : (axis == 1 ? local.xz : local.xy);
  // The block inside the voxel that was hit, for picking variants and jittering color.
  ivec3 cell = corner + clamp(ivec3(floor(local * vs)), ivec3(0), ivec3(int(vs) - 1));
  ab = fract(ab * vs);

  int base = variantRow(id, cell) * FACE_ROW_TEXELS + face * 4;
  vec3 rgb = vec3(0.0);
  float alpha = 0.0;

  for (int layer = 0; layer < 2; layer++) {
    vec4 a = texelFetch(uFaces, base + layer * 2);
    vec4 b = texelFetch(uFaces, base + layer * 2 + 1);
    if (b.w < 0.5) break;

    float texelsPerBlock = max(length(a.zw * uAtlasSize), length(b.xy * uAtlasSize));
    float lod = uDebug == 3 ? 0.0 : clamp(log2(max(t * uPixelAngle * texelsPerBlock, 1e-6)), 0.0, uMaxLod);
    // Stay half a texel of the chosen mip inside the sprite, or the bilinear filter at a block's edge
    // pulls in the neighboring sprite of the atlas. The tiling puts an edge every block, so it shows.
    float margin = clamp(0.5 * exp2(lod) / texelsPerBlock, 0.001, 0.5);
    vec2 abc = clamp(ab, margin, 1.0 - margin);
    vec2 uv = a.xy + abc.x * a.zw + abc.y * b.xy;
    vec4 s = textureLod(uAtlas, uv, lod);

    if (b.z < -0.5) {
      // Biome tint: -1 grass, -2 foliage.
      // Grass of predicted terrain (no loaded chunk) comes from the far layer of the voxel's level.
      if (farTint.z >= 0.0 && b.z > -1.5) {
        s.rgb *= textureLod(uTint, farTint, 0.0).rgb;
      } else {
        s.rgb *= textureLod(uTint, vec3(column, lowLayer + (b.z < -1.5 ? 1.0 : 0.0)), 0.0).rgb;
      }
    } else {
      int tint = int(b.z);
      s.rgb *= vec3(float((tint >> 16) & 255), float((tint >> 8) & 255), float(tint & 255)) / 255.0;
    }

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
        ivec3 center = voxel + int(0.5 * vs);
        vec2 column = (vec2(voxel.xz & (TINT_BLOCKS - 1)) + 0.5 * vs) / float(TINT_BLOCKS);
        // Below the column's split height the second biome applies (layers 2 and 3).
        float split = texelFetch(uTint, ivec3((center.xz & (TINT_BLOCKS - 1)) / TINT_CELL, 4), 0).r * 255.0 * float(TINT_SPLIT_UNIT);
        float lowLayer = float(center.y - uMinY) < split ? 2.0 : 0.0;

        // No loaded chunk filled this column: use the predicted grass color, if there is one.
        vec3 farTint = vec3(0.0, 0.0, -1.0);
        if (texelFetch(uTint, ivec3((center.xz & (TINT_BLOCKS - 1)) / TINT_CELL, 0), 0).a < 0.5) {
          int farShift = max(3, lvl + 1);   // TintMap.farCell
          ivec3 farTexel = ivec3((center.xz >> farShift) & (TINT_SIZE - 1), TINT_FAR_FIRST + lvl);
          if (texelFetch(uTint, farTexel, 0).a > 0.5) {
            farTint = vec3((vec2(voxel.xz) + 0.5 * vs) / float(TINT_SIZE << farShift), float(farTexel.z));
          }
        }

        if (surface(id, voxel, vs, axis, d, local, tc, column, lowLayer, farTint, albedo)) {
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
    // Past the projection's far plane (only a few times the render distance) there is no depth
    // left to write, so keep the farthest value: everything vanilla draws is nearer anyway.
    depth = min(clip.z / clip.w * 0.5 + 0.5, 1.0);
  }
  gl_FragDepth = depth;

  if (uDebug == 1) {
    // Misses are shown too: rays that march the full distance are the expensive ones.
    fragColor = vec4(heat(float(steps) / 150.0), 1.0);
    return;
  }

  // Flat per-face shading, same factors vanilla uses for its directional light.
  float shade = axis == 1 ? (d.y < 0.0 ? 1.0 : 0.5) : (axis == 0 ? 0.6 : 0.8);

  // Sky and block light (0..1) and ambient occlusion. Only level 0 has light data; the rest is open sky.
  vec2 light = vec2(1.0, 0.0);
  float ao = 1.0;
  if (level == 0) {
    // Fade out over the second half of level 0, where the LOD switch happens.
    float fade = clamp((uBase - tHit) / (0.5 * uBase), 0.0, 1.0);
    vec3 p = o + d * tHit - vec3(voxel - uCamBlock);
    vec2 l;
    float a = ambientOcclusion(voxel, axis, d, p, l);
    ao = mix(1.0, a, fade);
    light = mix(vec2(1.0, 0.0), l / 15.0, fade);
  }

  // Vanilla's light curve, lifted by the dimension's ambient light.
  vec2 curved = mix(light / (4.0 - 3.0 * light), vec2(1.0), uAmbientLight);
  float b = curved.y;
  vec3 blockRgb = vec3(b, b * ((b * 0.6 + 0.4) * 0.6 + 0.4), b * ((b * b * 0.6 + 0.4) * 0.6 + 0.4));

  vec3 lit = vec3(curved.x * uSkyDarken) + blockRgb;
  lit *= shade * ao;
  lit = min(lit, vec3(1.0));
  if (uDebug == 4) lit = vec3(1.0);

  if (uDebug == 2) {
    vec3 tint = 0.5 + 0.5 * cos(6.28318 * (float(level) * 0.17 + vec3(0.0, 0.33, 0.67)));
    albedo = mix(albedo, tint, 0.6);
  }

  fragColor = vec4(albedo * lit, 1.0);
}
