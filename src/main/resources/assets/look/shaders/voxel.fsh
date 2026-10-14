#version 330 core

const int MAX_LEVELS = 8;
const int HF_LEVELS = 5;      // Lod.HF_LEVELS: pyramid levels past the voxel ones, drawn from a heightfield

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
uniform int uHfEnabled;    // 1: levels past uLevels are drawn from the heightfield
uniform int uHfIds[14];    // SeedSource.voxelIds: per surface kind a top and filler id, then stone and water
uniform isampler2DArray uHfHeight;  // HeightField: surface height per texel, a layer per far level
uniform sampler2DArray uHfAttr;     // rgb: biome grass color (water color for water, tree color for trees), a: kind (0 none, 1 + surface, 7 water over gravel, 8 water over sand, 9 + height above ground for trees)
uniform isampler2DArray uHfTiles;   // highest texel of each 16 x 16 tile, to skip tiles a ray passes over
uniform isampler2DArray uHfSuper;   // highest tile of each 8 x 8 tiles, to skip those at once
uniform int uHfCeiling;             // HeightField.ceiling(): no visible column stands higher
uniform int uDebug;        // 5: no biome tint, 6: mip level fixed at 2, 7: alpha test off, 1: step-count heatmap instead of colors, 2: tint by pyramid level, 3: no mipmaps, 4: unlit

uniform usampler3D uIndirection;  // section -> brick slot + 1, EMPTY_CELL when all air, 0 when not resolved yet
uniform usamplerBuffer uBricks;   // 4096 u16 voxel ids per slot
uniform usamplerBuffer uColors;   // voxel id -> 0xRRGGBB, used when a block has no texture
uniform samplerBuffer uFaces;     // per (id, face, layer): atlas uv mapping, see BlockClassifier
uniform sampler2D uAtlas;         // vanilla block atlas
uniform sampler2DArray uTint;     // biome tint per column, see TintMap: grass, foliage, lower grass, lower foliage, split, far grass per level, then far foliage per level
uniform vec2 uAtlasSize;          // atlas size in texels
uniform float uMaxLod;            // highest mip level of the atlas
uniform int uMinY;               // lowest block y of the level, for the tint's lower biome
uniform float uPixelAngle;        // radians covered by one pixel
uniform usampler3D uLightIndirection;  // level 0 section -> light slot + 1, 0 when unknown, see LightMap
uniform usamplerBuffer uLight;    // 4096 u8 per slot: sky light << 4 | block light
uniform float uSkyDarken;         // vanilla's sky brightness for the time of day and weather, 0.2..1
uniform float uAmbientLight;      // dimension ambient light, lifts the darkest light levels
uniform float uTime;              // game ticks, wrapped to a multiple of the sway period
uniform int uWaterId;             // voxel id of water, for the texture of its surface
uniform vec3 uFogColor;           // vanilla's fog this frame, used when the camera is under water
uniform vec2 uFogRange;           // fog start and end
uniform float uSeaLevel;          // height of the sea: the far heightfield stores the seabed under it, and water fills up to here

#ifdef ENHANCED
// Compiled in only for Enhanced mode (docs/enhanced.md), so the Vanilla program is unchanged.
uniform vec3 uSunDir;             // unit vector toward the sun, world space
uniform vec3 uMoonDir;            // unit vector toward the moon
uniform int uMoonPhase;           // 0 full .. 7
uniform float uRain;              // smoothed rain level, 0..1
uniform float uThunder;           // smoothed thunder level, 0..1

// --- Sun shadows (ShadowPass.java, docs/enhanced.md 3.3). Positions are relative to uCamBlock. ---
uniform int uSunLighting;         // 1: sun / moon and sky ambient lighting, 0: vanilla's light (Nether, End)
#include "shadow_lookup.glsl"
#endif

in vec2 vNdc;
out vec4 fragColor;

const float EPS = 1e-3;
const int TINT_BLOCKS = 2048;  // blocks the tint texture covers before wrapping: TintMap.SIZE * CELL
const int TINT_CELL = 4;       // TintMap.CELL
const int TINT_SPLIT_UNIT = 2; // TintMap.SPLIT_UNIT
const int TINT_SIZE = 512;     // TintMap.SIZE
const int TINT_FAR_FIRST = 6;  // TintMap.FAR_FIRST
const int TINT_WATER_LAYER = 5;  // TintMap.WATER_LAYER
const int FACE_ROW_TEXELS = 24;  // BlockClassifier.ID_FLOATS / 4: texels in one row of the face table
const int META_START = (8192 + 1024) * FACE_ROW_TEXELS;  // BlockClassifier.META_START / 4
const int HF_FINER = 2;          // HeightField.FINER_SHIFT: texels are 2^(level - 2) blocks wide
const int HF_WATER = 7;          // terrain.rs HF_WATER: water over a gravel seabed
const int HF_WATER_SAND = 8;     // terrain.rs HF_WATER_SAND: water over a sand seabed (warm oceans)
const int HF_TREE = 9;           // terrain.rs HF_TREE: a column that is a tree, colored by its texel
const int HF_NONE = -32768;      // HeightField.NONE: tile max of a tile that has not arrived
const int HF_BLANK = -32767;     // HeightField.BLANK: tile max of a tile that arrived with no terrain
const int HF_WINDOW = 128;       // HeightField.WINDOW_TILES
const int HF_SUPER_SHIFT = 3;      // HeightField.SUPER: tiles per super cell, as a shift
const int HF_SUPER_CELLS = 16;     // HeightField.SUPER_CELLS
const float HF_BLEND_TILES = 2.0; // tiles before a level hands over that its heights blend toward the next
const int META_TEXELS = 3;       // BlockClassifier.META_FLOATS / 4
const int QUAD_HDR_START = META_START + 8192 * META_TEXELS;  // BlockClassifier.HDR_START / 4
const int QUAD_POOL_START = QUAD_HDR_START + 8192 * 3;       // BlockClassifier.POOL_START / 4
const int QUAD_TEXELS = 5;       // BlockClassifier.QUAD_FLOATS / 4
const int MAX_QUADS = 48;        // BlockClassifier.MAX_QUADS
const float SWAY_OMEGA = 0.0490874;  // 2 pi / 128 ticks, so wrapping uTime at a multiple of 128 is seamless
const uint ID_MASK = 0x1FFFu;    // BlockClassifier.MAX_ID: a brick texel is the voxel id, then the fill height
const uint FILL_SHIFT = 13u;     // BlockClassifier.FILL_SHIFT, in eighths of a voxel, 0 for a whole one

const uint EMPTY_CELL = 0xFFFFFFFFu;   // BrickTable.EMPTY_CELL: a resolved, all-air section. A cell of 0 is not resolved yet.

// The raw indirection value: brick slot + 1, EMPTY_CELL, or 0 while the section is not resolved.
uint readCell(int lvl, ivec3 sec) {
  ivec3 c = ivec3(sec.x & (uGrid.x - 1), uLevel[lvl].z + sec.y - uLevel[lvl].x, sec.z & (uGrid.z - 1));
  return texelFetch(uIndirection, c, 0).r;
}

// Brick slot + 1, 0 when the section has no bricks (all air, or not resolved).
uint readSlot(int lvl, ivec3 sec) {
  uint cell = readCell(lvl, sec);
  return cell == EMPTY_CELL ? 0u : cell;
}

// Pyramid level to read at a position relative to the camera. Level k holds voxels of 2^k blocks and
// is used for horizontal (Chebyshev) distances in [uBase * 2^(k-1), uBase * 2^k), matching the square
// windows the sections are built in. Along a ray the distance only grows, so the level never drops.
int levelAt(vec3 rel) {
  float h = max(abs(rel.x), abs(rel.z));
  if (h < uBase) return 0;
  return min(uLevels + HF_LEVELS - 1, 1 + int(floor(log2(h / uBase))));
}

// True when the level-`lvl` voxel at integer coordinate `vc` (in voxels of that level) occludes light.
bool occludes(int lvl, ivec3 vc) {
  ivec3 sec = vc >> 4;
  ivec3 rows = uLevel[lvl];
  if (sec.y < rows.x || sec.y >= rows.x + rows.y) return false;
  uint slot = readSlot(lvl, sec);
  if (slot == 0u) return false;
  ivec3 l = vc & 15;
  uint id = texelFetch(uBricks, int(slot - 1u) * 4096 + (l.y * 16 + l.z) * 16 + l.x).r & ID_MASK;
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

// Grass color of a heightfield hit, which has no loaded chunk and no tint layer to read it from.
vec3 gHfTint = vec3(1.0);
bool gHfTintOn = false;
int gTapCap = 8;   // most texture taps surface() takes along a grazing hit, the far filter takes one
bool gKeepSeeThrough = false;  // surface() returns see-through texels too (water is drawn with its alpha)
float gSurfAlpha = 1.0;        // alpha of the texture surface() last sampled
float gTraceMax = 1.0e30;      // rays stop at this distance (reflections and refraction look a short way)
int gIterCap = 400;            // and after this many section steps

// Water along the ray, tracked by trace(). Only the first surface between water and not-water is kept:
// vanilla draws water as one translucent layer, whatever the depth.
float gWaterT = -1.0;     // distance to that surface, -1 when the ray crossed none
int gWaterAxis = 1;
bool gWaterExit = false;  // the ray was in the water and left it (seen from below)
ivec3 gWaterVoxel = ivec3(0);
int gWaterLevel = 0;
bool gWet = false;        // the ray is in water right now
bool gUnder = false;      // the ray started in water: the camera is under water
ivec3 gLastWaterVoxel = ivec3(0);
int gLastWaterLevel = 0;
// The water surface of a far heightfield texel is not a voxel: it is the texel's column filled up to the sea.
bool gWaterHf = false;    // the kept surface is a far one
float gWaterSize = 1.0;   // blocks per texel of it
vec3 gWaterTint = vec3(1.0);

// Multiplier for a face table tint: -1 grass, -2 foliage (from the biome tint texture), else a 0xRRGGBB constant.
// Grass of predicted terrain (no loaded chunk) comes from the far layer of the voxel's level.
vec3 tintColor(float tint, vec2 column, float lowLayer, vec3 farTint) {
  if (tint < -2.5) {
    if (gHfTintOn) return gHfTint;
    if (farTint.z >= 0.0) return textureLod(uTint, vec3(farTint.xy, farTint.z + 2.0 * float(uLevels)), 0.0).rgb;
    return textureLod(uTint, vec3(column, float(TINT_WATER_LAYER)), 0.0).rgb;
  }
  if (tint < -0.5) {
    if (uDebug == 5) return vec3(1.0);
    if (gHfTintOn && tint > -1.5) return gHfTint;
    if (farTint.z >= 0.0) return textureLod(uTint, vec3(farTint.xy, farTint.z + (tint > -1.5 ? 0.0 : float(uLevels))), 0.0).rgb;
    return textureLod(uTint, vec3(column, lowLayer + (tint < -1.5 ? 1.0 : 0.0)), 0.0).rgb;
  }
  int t = int(tint);
  return vec3(float((t >> 16) & 255), float((t >> 8) & 255), float(t & 255)) / 255.0;
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
  // The block inside the voxel that was hit, for picking variants.
  ivec3 cell = corner + clamp(ivec3(floor(local * vs)), ivec3(0), ivec3(int(vs) - 1));
  vec2 abBlocks = ab * vs;   // position in blocks, the texture repeats every block

  // A pixel covers a disk of radius r (blocks) across the ray. On a surface seen at a shallow angle that
  // is a strip r / cos long along the ray's direction in the surface, so the texture is sampled at a
  // few points along it, each at the mip level of the narrow side. One mip level for the whole strip
  // would either blur the texture across it or alias along it.
  float r = t * uPixelAngle;
  float cosine = max(abs(d[axis]), 0.05);
  vec2 along = axis == 0 ? d.yz : (axis == 1 ? d.xz : d.xy);
  along = normalize(along + vec2(1e-6));
  int taps = uDebug == 3 ? 1 : int(clamp(ceil(1.0 / cosine), 1.0, float(gTapCap)));
  float spread = r * (1.0 / cosine - 1.0);

  int base = variantRow(id, cell) * FACE_ROW_TEXELS + face * 4;
  vec3 rgb = vec3(0.0);
  float alpha = 0.0;

  for (int layer = 0; layer < 2; layer++) {
    vec4 a = texelFetch(uFaces, base + layer * 2);
    vec4 b = texelFetch(uFaces, base + layer * 2 + 1);
    if (b.w < 0.5) break;

    float texelsPerBlock = max(length(a.zw * uAtlasSize), length(b.xy * uAtlasSize));
    float lod = uDebug == 3 ? 0.0 : (uDebug == 6 ? min(2.0, uMaxLod) : clamp(log2(max(r * texelsPerBlock, 1e-6)), 0.0, uMaxLod));
    // Stay half a texel of the chosen mip inside the sprite, or the bilinear filter at a block's edge
    // pulls in the neighboring sprite of the atlas. The tiling puts an edge every block, so it shows.
    float margin = clamp(0.5 * exp2(lod) / texelsPerBlock, 0.001, 0.5);

    vec4 s = vec4(0.0);
    for (int i = 0; i < taps; i++) {
      float off = ((float(i) + 0.5) / float(taps) - 0.5) * spread;
      vec2 abc = clamp(fract(abBlocks + along * off), margin, 1.0 - margin);
      vec2 uv = a.xy + abc.x * a.zw + abc.y * b.xy;
      s += textureLod(uAtlas, uv, lod);
    }
    s /= float(taps);

    s.rgb *= tintColor(b.z, column, lowLayer, farTint);

    rgb = layer == 0 ? s.rgb : mix(rgb, s.rgb, s.a);
    alpha = layer == 0 ? s.a : max(alpha, s.a);
  }

  gSurfAlpha = alpha;
  if (alpha < 0.5 && uDebug != 7 && !gKeepSeeThrough) {
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

// Nearest opaque texel of the model quads of a non-cube voxel `id`. `ol` is the ray origin and `d` its
// direction in voxel units (the voxel is [0, 1]^3, a unit is one voxel of the level), and [t0, t1] the
// part of the ray inside the voxel. Plants are moved by a per-voxel offset and sheared by the wind;
// both keep a quad a parallelogram, so the intersection stays the same. `nrm` points back at the ray.
bool quadSurface(uint id, ivec3 cell, vec3 ol, vec3 d, float t0, float t1, vec2 column, float lowLayer, vec3 farTint, out float tHit, out vec3 nrm, out vec3 albedo, out bool unshaded) {
  int hdr = QUAD_HDR_START + int(id) * 3;
  vec4 h0 = texelFetch(uFaces, hdr);
  int count = min(int(h0.y), MAX_QUADS);
  if (h0.x < 0.0 || count == 0) return false;

  bool plant = h0.z > 0.5;
  vec4 h1 = texelFetch(uFaces, hdr + 1);
  vec3 bmin = h1.xyz;
  vec3 bmax = texelFetch(uFaces, hdr + 2).xyz;

  vec3 shift = vec3(0.0);
  vec3 lean = vec3(0.0);   // shift per unit of height
  vec3 pad = vec3(0.0);
  if (plant) {
    // Vanilla's offset (Mth.getSeed with y = 0, then BlockBehaviour.getOffset). Only the low 28 bits of
    // the 64 bit seed matter, and the low bits of a product only depend on the low bits of its factors,
    // so 32 bit arithmetic gives the same nibbles.
    uint sd = (uint(cell.x) * 3129871u) ^ (uint(cell.z) * 116129781u);
    sd = sd * sd * 42317861u + sd * 11u;
    vec3 off = vec3(float((sd >> 16u) & 15u) / 15.0 - 0.5, float((sd >> 20u) & 15u) / 15.0 - 1.0, float((sd >> 24u) & 15u) / 15.0 - 0.5);
    shift = vec3(clamp(off.x * 0.5, -h0.w, h0.w), off.y * h1.w, clamp(off.z * 0.5, -h0.w, h0.w));
    // The ray only tests the voxel it is in, so keep the quads inside it.
    shift.xz = clamp(shift.xz, -bmin.xz, 1.0 - bmax.xz);

    uint hh = hash3(cell);
    float ph = uTime * SWAY_OMEGA + float(cell.x) * 0.31 + float(cell.z) * 0.23 + float(hh & 255u) * 0.01;
    lean = vec3(1.0, 0.0, 0.4) * (0.03 * (sin(ph) + 0.5 * sin(2.0 * ph + 1.7)));
    pad = vec3(0.1, 0.25, 0.1);
  }

  // Skip the quads when the ray misses the bounds of the model.
  vec3 invD = 1.0 / d;
  vec3 ta = (bmin - pad - ol) * invD;
  vec3 tb = (bmax + pad - ol) * invD;
  vec3 tn = min(ta, tb), tf = max(ta, tb);
  float te = max(max(tn.x, tn.y), max(tn.z, t0));
  float tl = min(min(tf.x, tf.y), min(tf.z, t1));
  if (te > tl) return false;

  float best = tl + 1e-4;
  bool found = false;
  int first = int(h0.x);
  for (int i = 0; i < count; i++) {
    int q = QUAD_POOL_START + (first + i) * QUAD_TEXELS;
    vec4 p0 = texelFetch(uFaces, q);
    vec3 P = p0.xyz;
    vec3 E1 = texelFetch(uFaces, q + 1).xyz;
    vec3 E2 = texelFetch(uFaces, q + 2).xyz;
    if (plant) {
      P += shift + lean * P.y;
      E1 += lean * E1.y;
      E2 += lean * E2.y;
    }

    vec3 n = cross(E1, E2);
    float dn = dot(d, n);
    // Back faces are culled like vanilla does: n points out of the front of the quad. Models that show
    // from both sides (plants) have a second quad per side, so keeping both would z-fight and mirror.
    if (dn > -1e-9) continue;
    float T = dot(P - ol, n) / dn;
    if (T < te - 1e-4 || T >= best) continue;

    vec3 hp = ol + d * T - P;
    float nn = dot(n, n);
    float a = dot(cross(hp, E2), n) / nn;
    float b = dot(cross(E1, hp), n) / nn;
    if (a < 0.0 || a > 1.0 || b < 0.0 || b > 1.0) continue;

    vec4 m0 = texelFetch(uFaces, q + 3);
    vec4 m1 = texelFetch(uFaces, q + 4);
    vec2 uv = m0.xy + a * m0.zw + b * m1.xy;
    float texelsPerUnit = max(length(m0.zw * uAtlasSize) / max(length(E1), 1e-4), length(m1.xy * uAtlasSize) / max(length(E2), 1e-4));
    float lod = clamp(log2(max(T * uPixelAngle * texelsPerUnit, 1e-6)), 0.0, uMaxLod);
    vec4 s = textureLod(uAtlas, uv, lod);
    if (s.a < 0.5) continue;

    best = T;
    found = true;
    unshaded = p0.w < 0.5;
    tHit = T;
    nrm = normalize(n);
    albedo = s.rgb * tintColor(m1.z, column, lowLayer, farTint);
  }

  return found;
}

// Height of far texel `ac` (absolute, in texels of `layer`) as it is drawn. Near the outer edge of a
// level the height is blended toward the next, coarser level's height at the same spot, reaching it
// where the ray hands over to that level, so the two levels meet with no step between them (they are
// point samples of the same terrain at different resolutions, so they disagree by a few blocks).
// The coarse level holds tiles well inside the hand-over, so the whole blend band has data.
// Returns HF_NONE where the texel has no terrain.
float hfHeight(ivec2 ac, int layer) {
  int H = texelFetch(uHfHeight, ivec3(ac & (HF_WINDOW * 16 - 1), layer), 0).r;
  if (H == HF_NONE) return float(HF_NONE);
  int lvl = layer + uLevels;
  int last = uLevels + HF_LEVELS - 1;
  if (lvl >= last) return float(H);

  float T = float(1 << (lvl - HF_FINER));
  vec2 center = (vec2(ac) + 0.5) * T - vec2(uCamBlock.xz);
  float hc = max(abs(center.x), abs(center.y));
  float handoff = uBase * float(1 << lvl) - 16.0 * float(1 << (lvl - HF_FINER));
  float w = smoothstep(handoff - HF_BLEND_TILES * 16.0 * T, handoff, hc);
  if (w <= 0.0) return float(H);

  ivec2 cc = (uCamBlock.xz + ivec2(floor(center))) >> (lvl - HF_FINER + 1);
  // The coarse tile may not have arrived (or been dropped): its texels are then stale or never written.
  if (texelFetch(uHfTiles, ivec3((cc >> 4) & (HF_WINDOW - 1), layer + 1), 0).r == HF_NONE) return float(H);
  int Hc = texelFetch(uHfHeight, ivec3(cc & (HF_WINDOW * 16 - 1), layer + 1), 0).r;
  if (Hc == HF_NONE) return float(H);
  return mix(float(H), float(Hc), w);
}

// Tint inputs of the voxel with min corner `voxel` (block coordinates) at level `lvl`: the world column
// of its center, the lower biome layer, and where its predicted grass color is when no chunk filled it.
void voxelTint(ivec3 voxel, int lvl, float vs, out vec2 column, out float lowLayer, out vec3 farTint) {
  ivec3 center = voxel + int(0.5 * vs);
  column = (vec2(voxel.xz & (TINT_BLOCKS - 1)) + 0.5 * vs) / float(TINT_BLOCKS);
  // Below the column's split height the second biome applies (layers 2 and 3).
  float split = texelFetch(uTint, ivec3((center.xz & (TINT_BLOCKS - 1)) / TINT_CELL, 4), 0).r * 255.0 * float(TINT_SPLIT_UNIT);
  lowLayer = float(center.y - uMinY) < split ? 2.0 : 0.0;

  // No loaded chunk filled this column: use the predicted grass color, if there is one.
  farTint = vec3(0.0, 0.0, -1.0);
  if (texelFetch(uTint, ivec3((center.xz & (TINT_BLOCKS - 1)) / TINT_CELL, 0), 0).a < 0.5) {
    int farShift = max(3, lvl + 1);   // TintMap.farCell
    ivec3 farTexel = ivec3((center.xz >> farShift) & (TINT_SIZE - 1), TINT_FAR_FIRST + lvl);
    if (texelFetch(uTint, farTexel, 0).a > 0.5) {
      farTint = vec3((vec2(voxel.xz) + 0.5 * vs) / float(TINT_SIZE << farShift), float(farTexel.z));
    }
  }
}

// True when the level-`lvl` voxel at integer coordinate `vc` is water.
bool isWaterVoxel(int lvl, ivec3 vc) {
  ivec3 sec = vc >> 4;
  ivec3 rows = uLevel[lvl];
  if (sec.y < rows.x || sec.y >= rows.x + rows.y) return false;
  uint slot = readSlot(lvl, sec);
  if (slot == 0u) return false;
  ivec3 l = vc & 15;
  uint id = texelFetch(uBricks, int(slot - 1u) * 4096 + (l.y * 16 + l.z) * 16 + l.x).r & ID_MASK;
  return id != 0u && ((texelFetch(uColors, int(id)).r >> 25) & 3u) == 3u;
}

// The ray crosses a surface between water and not-water at distance `t`, through face `ax`; `exiting`
// when it comes out of the water. Only the first one is kept.
void markWater(float t, int ax, bool exiting) {
  if (gWaterT >= 0.0) return;
  gWaterT = t;
  gWaterAxis = ax;
  gWaterExit = exiting;
  gWaterVoxel = gLastWaterVoxel;
  gWaterLevel = gLastWaterLevel;
}

// The ray crosses the sea surface above far texel `ac` at distance `t`. Only the first surface is kept.
void markHfWater(float t, bool exiting, ivec2 ac, int sh, vec3 tint) {
  if (gWaterT >= 0.0) return;
  float T = float(1 << sh);
  gWaterT = t;
  gWaterAxis = 1;
  gWaterExit = exiting;
  gWaterHf = true;
  gWaterSize = T;
  gWaterTint = tint;
  gWaterVoxel = ivec3(ac.x << sh, int(floor(uSeaLevel / T) * T), ac.y << sh);
  gWaterLevel = sh + HF_FINER;
}

// A tree column of the far heightfield is solid from the ground to the top of its canopy, but the
// sides of a real canopy sit above bare trunk. The kind carries the tree's height above the ground
// (HF_TREE + blocks), and the lower part of a side, below this share of that height, shows bark.
const float HF_TRUNK_SHARE = 0.4;
const vec3 HF_TRUNK_COLOR = vec3(0.30, 0.22, 0.12);

vec3 hfTreeColor(vec3 leaf, vec2 ac, float depth, float treeH, int hax) {
  if (hax != 1 && depth > (1.0 - HF_TRUNK_SHARE) * treeH) return HF_TRUNK_COLOR;
  float shade = fract(sin(dot(ac, vec2(12.9898, 78.233))) * 43758.5453);
  return leaf * (0.82 + 0.3 * shade);
}

// Color of far texel column at horizontal position `pxz` (relative to uCamBlock) as a ray passing at
// height `yr` would see it: its top when the ray is over it, its side when the ray is a little below the
// top, and nothing when the ray is deep inside it (a nearer column hides it). The far hit filter uses
// this to read what else is under the pixel from the neighboring texels, with no ray of its own.
bool hfSample(vec2 pxz, int layer, int sh, float yr, vec3 d, float tt, out vec3 albedo) {
  float T = float(1 << sh);
  ivec2 ac = (uCamBlock.xz + ivec2(floor(pxz))) >> sh;
  ivec3 tc = ivec3(ac & (HF_WINDOW * 16 - 1), layer);
  float fH = hfHeight(ac, layer);
  if (fH == float(HF_NONE)) return false;
  vec4 attr = texelFetch(uHfAttr, tc, 0);
  int kind = int(attr.a * 255.0 + 0.5);
  if (kind == 0) return false;

  float treeH = float(max(kind - HF_TREE, 0));
  if (kind >= HF_TREE) kind = HF_TREE;

  // A ray well above the column does not touch it here (ground in front of a cliff base, say), so it
  // adds nothing; only a ray within about a pixel of the top reads the top.
  if (yr - fH > max(1.0, tt * uPixelAngle)) return false;
  float depth = max(fH - yr, 0.0);
  if (depth > 4.0 * T) return false;
  int hax = depth > 0.0 ? (abs(d.x) > abs(d.z) ? 0 : 2) : 1;
  float y = depth > 0.0 ? yr : fH;

  uint id;
  if (kind == HF_TREE) {
    id = uint(uHfIds[12]);
  } else if (kind == HF_WATER || kind == HF_WATER_SAND) {
    int base = kind == HF_WATER ? 6 : 2;   // gravel or sand
    id = uint(uHfIds[hax == 1 || depth < 1.0 ? base : base + 1]);
  } else if (hax == 1 || depth < 1.0) {
    id = uint(uHfIds[(kind - 1) * 2]);
  } else if (depth < 4.0) {
    id = uint(uHfIds[(kind - 1) * 2 + 1]);
  } else {
    id = uint(uHfIds[12]);
  }

  float ty = floor(y / T) * T;
  vec2 lxz = clamp((vec2(uCamBlock.xz - ac * (1 << sh)) + pxz) / T, 0.0, 1.0);
  vec3 local = vec3(lxz.x, (y - ty) / T, lxz.y);
  ivec3 corner = ivec3(ac.x * (1 << sh), int(ty), ac.y * (1 << sh));

  gHfTint = attr.rgb;
  gHfTintOn = true;
  gTapCap = 1;
  bool ok = surface(id, corner, T, hax, d, local, tt, vec2(0.0), 0.0, vec3(0.0, 0.0, -1.0), albedo);
  gTapCap = 8;
  gHfTintOn = false;
  if (ok && kind == HF_TREE) albedo = hfTreeColor(attr.rgb, vec2(ac), depth, treeH, hax);
  return ok;
}

// Ray march over the far heightfield (see HeightField.java): texels are columns, solid below their
// height, walked in 2D one tile at a time (tiles the ray passes over are skipped by their highest
// texel), then texel by texel inside a tile. `t` is where the ray enters the first far level; the
// level only grows from there. A hit comes out like one from the voxel trace.
//
// With a `tEnd` this stands in for voxel sections that are not resolved yet: the ray starts at `t`
// inside one (entering through the `axis0` face), only hits up to `tEnd` count, and it reads the first
// heightfield level, which also covers the area under the voxel levels.
// Tile maximum of the tile of `layer` that the position `rel` (relative to the camera block) is in.
int hfTileMaxAt(vec2 relXz, int lvl) {
  int sh = lvl - HF_FINER;
  ivec2 camCell = uCamBlock.xz >> sh;
  vec2 camRem = vec2(uCamBlock.xz & ((1 << sh) - 1));
  ivec2 c = ivec2(floor((relXz + camRem) / float(1 << sh)));
  return texelFetch(uHfTiles, ivec3(((camCell + c) >> 4) & (HF_WINDOW - 1), lvl - uLevels), 0).r;
}

bool traceHeightfield(vec3 o, vec3 d, float t, float tEnd, int axis0, out float tHit, out uint hitId, out int hitAxis, out ivec3 hitVoxel, out int hitLevel, out vec3 albedo, inout int steps, out vec3 hitNormal, out int hitKind) {
  vec3 invD = 1.0 / d;
  float y0 = float(uCamBlock.y) + o.y;   // ray height at t = 0, in blocks
  int last = uLevels + HF_LEVELS - 1;
  // A level's window is 128 tiles wide, one short of what its ring needs when the camera is not
  // tile aligned, so every level hands over to the next one tile early (the next level's tiles start
  // well inside its ring), and the last one just ends.
  float reach = uBase * float(1 << last) - 16.0 * float(1 << (last - HF_FINER));
  ivec2 s = ivec2(d.x > 0.0 ? 1 : -1, d.z > 0.0 ? 1 : -1);
  int axis = axis0;
  // Nothing in the window stands higher than this (the sea fills up to its level, so that counts too).
  float ceilY = max(float(uHfCeiling), uSeaLevel) + 1.0;

  for (int it = 0; it < 640; it++) {
    if (t > min(min(uMaxDist, gTraceMax), tEnd)) return false;
    steps++;

    vec3 rel = o + d * t;
    float h = max(abs(rel.x), abs(rel.z));
    if (h >= reach) return false;
    float ry = y0 + d.y * t;
    if (ry > ceilY && d.y >= 0.0) return false;   // above every column and climbing
    if (ry < -100.0 && d.y <= 0.0) return false;
    if (ry > ceilY + 1.0 && d.y < 0.0) {
      // Above every column and coming down: nothing to hit before the ray reaches the ceiling.
      t = max((ceilY - y0) / d.y, t) + EPS;
      axis = 1;
      continue;
    }

    int lvl = max(levelAt(rel), uLevels);
    if (lvl < last && h >= uBase * float(1 << lvl) - 16.0 * float(1 << (lvl - HF_FINER))) lvl++;
    // A finer level whose tile has not arrived yet (the camera just moved into its range) lets the
    // coarser level, which still holds the terrain, draw instead of leaving a hole.
    for (int k = 0; k < HF_LEVELS - 1; k++) {
      if (lvl >= last || hfTileMaxAt(rel.xz, lvl) != HF_NONE) break;
      lvl++;
    }
    int layer = lvl - uLevels;
    int sh = lvl - HF_FINER;                 // a texel is 1 << sh blocks wide
    float T = float(1 << sh);
    float eps = EPS * T + t * 2e-7;

    ivec2 camCell = uCamBlock.xz >> sh;
    vec2 camRem = vec2(uCamBlock.xz & ((1 << sh) - 1));   // blocks into the camera's cell
    ivec2 c = ivec2(floor((rel.xz + camRem) / T));          // cell relative to the camera's
    ivec2 tile = (camCell + c) >> 4;

    // Eight tiles at a time: a ray over a whole super cell goes to where it leaves it, or hands over to
    // the next level (the data here says nothing about what lies past that).
    ivec2 stile = tile >> HF_SUPER_SHIFT;
    int smax = texelFetch(uHfSuper, ivec3(stile & (HF_SUPER_CELLS - 1), layer), 0).r;
    {
      ivec2 slo = (stile << (HF_SUPER_SHIFT + 4)) - camCell;
      vec2 sedge = vec2(slo) + vec2(d.x > 0.0 ? float(128) : 0.0, d.z > 0.0 ? float(128) : 0.0);
      vec2 tsEdge = (sedge * T - camRem - o.xz) * invD.xz;
      float hand = uBase * float(1 << lvl) - 16.0 * float(1 << (lvl - HF_FINER));
      vec2 tWall = (vec2(d.x > 0.0 ? hand : -hand, d.z > 0.0 ? hand : -hand) - o.xz) * invD.xz;
      float ex = min(tsEdge.x, tWall.x);
      float ez = min(tsEdge.y, tWall.y);
      float ts = min(ex, ez);
      float low = min(ry, y0 + d.y * ts);
      bool over = smax == HF_NONE;
      if (!over && low > max(float(smax), uSeaLevel)) {
        float m = max(float(smax), uSeaLevel);
        if (lvl < last) {
          int cs = texelFetch(uHfSuper, ivec3((stile >> 1) & (HF_SUPER_CELLS - 1), layer + 1), 0).r;
          if (cs != HF_NONE) m = max(m, float(cs));
        }
        over = low > m;
      }
      if (over && ts > t) {
        axis = ex < ez ? 0 : 2;
        t = ts + eps;
        continue;
      }
    }
    int tmax = texelFetch(uHfTiles, ivec3(tile & (HF_WINDOW - 1), layer), 0).r;
    if (tmax <= HF_BLANK) tmax = HF_NONE;
    // Water texels hold their seabed, but the sea fills them up to its level.
    if (tmax != HF_NONE) tmax = max(tmax, int(uSeaLevel));
    if (tmax != HF_NONE && lvl < last) {
      // Heights blend toward the coarser level's near the hand-over, which may stand higher.
      int cmax = texelFetch(uHfTiles, ivec3((tile >> 1) & (HF_WINDOW - 1), layer + 1), 0).r;
      if (cmax != HF_NONE) tmax = max(tmax, cmax);
    }

    // Where the ray leaves this tile.
    ivec2 lo = (tile << 4) - camCell;
    vec2 edge = vec2(lo) + vec2(d.x > 0.0 ? 16.0 : 0.0, d.z > 0.0 ? 16.0 : 0.0);
    vec2 tEdge = (edge * T - camRem - o.xz) * invD.xz;
    float te = min(tEdge.x, tEdge.y);

    float ryExit = y0 + d.y * te;
    if (tmax == HF_NONE || min(ry, ryExit) > float(tmax)) {
      axis = tEdge.x < tEdge.y ? 0 : 2;
      t = te + eps;
      continue;
    }

    // Texel by texel until the ray leaves the tile.
    vec2 next = (vec2(c) + vec2(d.x > 0.0 ? 1.0 : 0.0, d.z > 0.0 ? 1.0 : 0.0)) * T - camRem - o.xz;
    vec2 tNext = next * invD.xz;
    vec2 tDelta = abs(invD.xz) * T;
    float tc = t;

    for (int j = 0; j < 34; j++) {
      if (tc > tEnd) return false;
      steps++;
      ivec2 ac = camCell + c;
      float fH = hfHeight(ac, layer);
      if (fH != float(HF_NONE)) {
        if (fH < uSeaLevel && d.y != 0.0) {
          // A water texel: the sea fills the column from the seabed up to its level. A ray crossing that
          // level inside the texel enters the water (coming down) or leaves it (going up).
          float tSea = (uSeaLevel - y0) / d.y;
          vec4 wattr = texelFetch(uHfAttr, ivec3(ac & (HF_WINDOW * 16 - 1), layer), 0);
          if ((int(wattr.a * 255.0 + 0.5) == HF_WATER || int(wattr.a * 255.0 + 0.5) == HF_WATER_SAND) && tSea >= tc && tSea <= min(tNext.x, tNext.y)) {
            markHfWater(tSea, d.y > 0.0, ac, sh, wattr.rgb);
          }
        }
        float th = -1.0;
        int hax = axis;
        if (y0 + d.y * tc <= fH) {
          th = tc;                       // entered below the surface: the side of this column
        } else if (d.y < 0.0) {
          float tTop = (fH - y0) / d.y;  // comes down onto the top before leaving the texel
          if (tTop <= min(tNext.x, tNext.y)) {
            th = max(tTop, tc);
            hax = 1;
          }
        }

        if (th > tEnd) return false;
        if (th >= 0.0) {
          vec4 attr = texelFetch(uHfAttr, ivec3(ac & (HF_WINDOW * 16 - 1), layer), 0);
          int kind = int(attr.a * 255.0 + 0.5);
          float treeH = float(max(kind - HF_TREE, 0));
          if (kind >= HF_TREE) kind = HF_TREE;
          if (kind > 0) {
            vec3 p = o + d * th;
            float yAbs = y0 + d.y * th;
            float ty = floor(yAbs / T) * T;
            vec2 lxz = clamp((vec2(uCamBlock.xz - ac * (1 << sh)) + p.xz) / T, 0.0, 1.0);
            vec3 local = vec3(lxz.x, (yAbs - ty) / T, lxz.y);
            ivec3 corner = ivec3(ac.x * (1 << sh), int(ty), ac.y * (1 << sh));

            // The top is the surface block, under it a few blocks of filler, then stone.
            float depth = fH - yAbs;
            uint id;
            if (kind == HF_TREE) {
              id = uint(uHfIds[12]);   // any opaque block: the color comes from the texel
            } else if (kind == HF_WATER || kind == HF_WATER_SAND) {
              int base = kind == HF_WATER ? 6 : 2;   // gravel or sand
              id = uint(uHfIds[hax == 1 || depth < 1.0 ? base : base + 1]);
            } else if (hax == 1 || depth < 1.0) {
              id = uint(uHfIds[(kind - 1) * 2]);
            } else if (depth < 4.0) {
              id = uint(uHfIds[(kind - 1) * 2 + 1]);
            } else {
              id = uint(uHfIds[12]);
            }

            gHfTint = attr.rgb;
            gHfTintOn = true;
            bool ok = surface(id, corner, T, hax, d, local, th, vec2(0.0), 0.0, vec3(0.0, 0.0, -1.0), albedo);
            if (ok && kind == HF_TREE) {
              // A tree's canopy or trunk: its own color, a little different from tree to tree.
              albedo = hfTreeColor(attr.rgb, vec2(ac), depth, treeH, hax);
            }
            gHfTintOn = false;
            if (ok && th * uPixelAngle > 0.25 * T) {
              // A pixel that covers several texels would show one of them at random, and the surface
              // here changes from texel to texel (grass, stone, snow, water, a cliff side), so it grains.
              // Average it with four more spots of the pixel's footprint on the ground, read from the
              // neighboring texels: two along the ray, where the footprint is stretched by the grazing
              // angle, and two across it.
              float r = th * uPixelAngle;
              float lh = max(length(d.xz), 1e-4);
              vec2 hd = d.xz / lh;
              vec2 cross2 = vec2(-hd.y, hd.x);
              float alongLen = min(r / max(abs(d.y), 0.05), 4.0 * T);
              vec2 pc = (o + d * th).xz;
              vec3 sum = albedo * 2.0;
              float wsum = 2.0;
              for (int k = 0; k < 4; k++) {
                float sg = (k & 1) == 0 ? -0.5 : 0.5;
                vec2 off = k < 2 ? hd * (sg * alongLen) : cross2 * (sg * r);
                float dt = k < 2 ? sg * alongLen / lh : 0.0;
                vec3 a2;
                if (hfSample(pc + off, layer, sh, yAbs + d.y * dt, d, th + dt, a2)) {
                  sum += a2;
                  wsum += 1.0;
                }
              }
              albedo = sum / wsum;
            }
            if (ok) {
              tHit = th;
              hitId = id;
              hitAxis = hax;
              hitVoxel = corner;
              hitLevel = lvl;
              hitKind = 0;
              hitNormal = vec3(0.0);
              hitNormal[hax] = d[hax] > 0.0 ? -1.0 : 1.0;
              return true;
            }
          }
        }
      }

      if (tNext.x < tNext.y) {
        tc = tNext.x; tNext.x += tDelta.x; c.x += s.x; axis = 0;
      } else {
        tc = tNext.y; tNext.y += tDelta.y; c.y += s.y; axis = 2;
      }
      if (((camCell + c) >> 4) != tile) break;
    }

    t = tc + eps;
  }

  return false;
}

// Two-level DDA over the LOD pyramid: whole sections are skipped when empty, voxels are stepped
// inside bricks. At level k every voxel is 2^k blocks wide and a section is 16 voxels, but the
// stepping is the same, so all the lengths below are just scaled by the voxel size.
// o is relative to uCamBlock. d is normalized with no zero components. hitNormal is the surface normal
// facing the ray, hitKind the BlockClassifier kind of the voxel that was hit, plus 4 for a surface that
// is not shaded by its direction (plants).
bool trace(vec3 o, vec3 d, out float tHit, out uint hitId, out int hitAxis, out ivec3 hitVoxel, out int hitLevel, out vec3 albedo, out int steps, out vec3 hitNormal, out int hitKind) {
  steps = 0;
  vec3 invD = 1.0 / d;
  ivec3 stp = ivec3(sign(d));
  vec3 up = vec3(d.x > 0.0 ? 1.0 : 0.0, d.y > 0.0 ? 1.0 : 0.0, d.z > 0.0 ? 1.0 : 0.0);

  float t = 0.0;
  int axis = 1;

  for (int i = 0; i < 400; i++) {
    if (i >= gIterCap || t > min(uMaxDist, gTraceMax)) return false;
    steps++;

    vec3 rel = o + d * t;
    int lvl = levelAt(rel);
    if (lvl >= uLevels) {
      // Past the voxel levels the ray never comes back to them: the rest is the heightfield.
      if (uHfEnabled == 0) return false;
      gWet = false;
      return traceHeightfield(o, d, t, 1.0e30, 1, tHit, hitId, hitAxis, hitVoxel, hitLevel, albedo, steps, hitNormal, hitKind);
    }
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

    if (sec.y >= rows.x + rows.y) {
      // Above every section of the level and coming down (a camera high up): every section on the way is
      // empty, so go straight to the top of the level, or out of its horizontal window if that is first.
      float top = float(((rows.x + rows.y) << secShift) - uCamBlock.y);
      float tPlane = (top - o.y) * invD.y;
      float R = uBase * float(1 << lvl);
      vec2 tWall = (vec2(d.x > 0.0 ? R : -R, d.z > 0.0 ? R : -R) - o.xz) * invD.xz;
      float tJump = min(tPlane, min(tWall.x, tWall.y));
      axis = tJump == tPlane ? 1 : (tWall.x < tWall.y ? 0 : 2);
      t = max(tJump, t) + eps;
      continue;
    }

    ivec3 secMin = sec << secShift;   // block
    uint slot = EMPTY_CELL;
    if (sec.y >= rows.x && sec.y < rows.x + rows.y) slot = readCell(lvl, sec);

    if (slot == 0u || slot == EMPTY_CELL) {
      if (gWet) {
        markWater(t, axis, true);
        gWet = false;
      }
      // Jump to where the ray leaves this (empty) section.
      vec3 bound = vec3(secMin - uCamBlock) + up * (vs * 16.0);
      vec3 tb = (bound - o) * invD;
      float te = min(tb.x, min(tb.y, tb.z));
      if (slot == 0u && uHfEnabled != 0) {
        // Not resolved yet (still loading): the far heightfield stands in for it until its brick lands.
        if (traceHeightfield(o, d, t, te, axis, tHit, hitId, hitAxis, hitVoxel, hitLevel, albedo, steps, hitNormal, hitKind)) return true;
      }
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
      uint raw = texelFetch(uBricks, base + (v.y * 16 + v.z) * 16 + v.x).r;
      uint id = raw & ID_MASK;
      uint cw = id != 0u ? texelFetch(uColors, int(id)).r : 0u;
      bool waterVoxel = ((cw >> 25) & 3u) == 3u;

      // Water: either a water voxel, or a block that holds some (kelp, waterlogged slabs). Its height
      // is the fill of the voxel, else the one of its id, and a whole voxel when water sits above it.
      float wh = 0.0;
      if (waterVoxel || (cw >> 31) != 0u) {
        uint fl = raw >> FILL_SHIFT;
        wh = fl != 0u ? float(fl) / 8.0 : float((cw >> 27) & 15u) / 8.0;
        if (wh < 1.0 && fl == 0u && isWaterVoxel(lvl, (secMin >> lvl) + v + ivec3(0, 1, 0))) wh = 1.0;
      }
      if (wh > 0.0 || gWet) {
        ivec3 wv = secMin + (v << lvl);
        if (wh > 0.0) {
          gLastWaterVoxel = wv;
          gLastWaterLevel = lvl;
        }
        float ly = (o.y + d.y * tc - float(wv.y - uCamBlock.y)) / vs;   // height in the voxel where the ray enters it
        float tExit = min(tMax.x, min(tMax.y, tMax.z));
        bool wetNow = wh > 0.0 && ly <= wh + 1e-3;
        if (gWet && !wetNow) {
          markWater(tc, axis, true);
          gWet = false;
        }
        if (!gWet && wh > 0.0) {
          float te = -1.0;
          int ax = axis;
          if (wetNow) {
            te = tc;
          } else if (d.y < 0.0) {
            // Enters above the water in this voxel: comes down onto its top.
            float tTop = tc + (wh - ly) * vs / d.y;
            if (tTop < tExit) {
              te = tTop;
              ax = 1;
            }
          }
          if (te >= 0.0) {
            if (tc <= 0.0) gUnder = true;   // the camera is in the water: no surface to draw, just fog
            else markWater(te, ax, false);
            gWet = true;
          }
        }
        if (gWet && wh > 0.0 && wh < 1.0 && d.y > 0.0) {
          // Rises out of the top of the water inside this voxel.
          float tUp = tc + (wh - ly) * vs / d.y;
          if (tUp < tExit) {
            markWater(tUp, 1, true);
            gWet = false;
          }
        }
      }

      if (id != 0u && !waterVoxel) {
        ivec3 voxel = secMin + (v << lvl);   // min corner, block
        float th = tc;                       // where the ray meets the voxel
        int hax = axis;
        vec3 local = (o + d * th - vec3(voxel - uCamBlock)) / vs;

        // A voxel that is only filled part of the way up (the surface of predicted terrain). A ray
        // that enters above the filled part has to come down onto its top face inside the voxel,
        // otherwise it passes over and on.
        bool inside = true;
        uint fill = raw >> FILL_SHIFT;
        if (fill != 0u) {
          float h = float(fill) / 8.0;
          if (local.y > h) {
            inside = false;
            if (d.y < 0.0) {
              float tTop = tc + (h - local.y) * vs / d.y;
              if (tTop < min(tMax.x, min(tMax.y, tMax.z))) {
                inside = true;
                th = tTop;
                hax = 1;
                local = (o + d * th - vec3(voxel - uCamBlock)) / vs;
              }
            }
          }
        }

        if (inside) {
          // The texture covers the whole voxel, so a pixel spans 1/vs as many texels per ray length.
          vec2 column;
          float lowLayer;
          vec3 farTint;
          voxelTint(voxel, lvl, vs, column, lowLayer, farTint);

          int kind = int((cw >> 25) & 3u);
          if (kind == 0) {
            if (surface(id, voxel, vs, hax, d, local, th, column, lowLayer, farTint, albedo)) {
              tHit = th;
              hitId = id;
              hitAxis = hax;
              hitVoxel = voxel;
              hitLevel = lvl;
              hitKind = 0;
              hitNormal = vec3(0.0);
              hitNormal[hax] = d[hax] > 0.0 ? -1.0 : 1.0;
              return true;
            }
          } else {
            // Model quads: intersect them inside this voxel, up to where the ray leaves it (tMax).
            vec3 ol = (o - vec3(voxel - uCamBlock)) / vs;
            float exitT = min(tMax.x, min(tMax.y, tMax.z));
            float qt;
            vec3 qn;
            bool unshaded;
            if (quadSurface(id, voxel >> lvl, ol, d, (tc - eps) / vs, (exitT + eps) / vs, column, lowLayer, farTint, qt, qn, albedo, unshaded)) {
              tHit = qt * vs;
              hitId = id;
              hitAxis = axis;
              hitVoxel = voxel;
              hitLevel = lvl;
              hitKind = kind | (unshaded ? 4 : 0);
              hitNormal = qn;
              return true;
            }
          }
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

// Vanilla's light curve, lifted by the dimension's ambient light, as a color. `light` is sky, block (0..1).
vec3 lightRgb(vec2 light) {
  vec2 curved = mix(light / (4.0 - 3.0 * light), vec2(1.0), uAmbientLight);
  float b = curved.y;
  vec3 blockRgb = vec3(b, b * ((b * 0.6 + 0.4) * 0.6 + 0.4), b * ((b * b * 0.6 + 0.4) * 0.6 + 0.4));
  return vec3(curved.x * uSkyDarken) + blockRgb;
}

// Under water vanilla fogs everything to the water's fog color. Above water there is no fog here.
vec3 underFog(vec3 c, float t) {
  if (!gUnder) return c;
  return mix(uFogColor, c, clamp((uFogRange.y - t) / max(uFogRange.y - uFogRange.x, 1e-3), 0.0, 1.0));
}

vec3 heat(float x) {
  x = clamp(x, 0.0, 1.0);
  return vec3(clamp(4.0 * x - 2.0, 0.0, 1.0), clamp(2.0 - abs(4.0 * x - 2.0), 0.0, 1.0), clamp(2.0 - 4.0 * x, 0.0, 1.0));
}

#ifdef ENHANCED
// --- Materials (docs/enhanced.md 5.1), Enhanced only -----------------------------------------------
// One u32 per voxel id, see Materials.java for the layout. Other features read `materialOf(id)`:
//   roughness, f0 (dielectric reflectance, or the albedo itself when `metal`) -> specular, reflections
//   subsurface  -> foliage translucency (3.9)         flicker -> block light wobble (3.10)
//   wetness     -> rain darkening and smoothing (4.9)  emission -> used below (5.4)
// Only emission is consumed so far.
uniform usamplerBuffer uMaterials;
const float EMISSION_MAX = 4.0;   // Materials.EMISSION_MAX

struct Material {
  float roughness;     // 0 mirror .. 1 rough
  float f0;            // reflectance at normal incidence of a dielectric, 0.04 typical
  float emission;      // multiple of the albedo added to the HDR color, 0 for most blocks
  bool metal;
  bool subsurface;
  bool flicker;
  float wetness;       // 0..1, how much rain affects the block
  bool texelEmission;  // emission applies only to bright saturated texels (flames, ore flecks)
  bool texelSmooth;    // smoothness applies only to bright saturated texels (gems in ore)
};

Material materialOf(uint id) {
  uint p = texelFetch(uMaterials, int(id)).r;
  Material m;
  m.roughness = float(p & 255u) / 255.0;
  m.f0 = float((p >> 8) & 255u) / 255.0;
  m.emission = float((p >> 16) & 255u) / 255.0 * EMISSION_MAX;
  m.metal = ((p >> 24) & 1u) != 0u;
  m.subsurface = ((p >> 25) & 1u) != 0u;
  m.flicker = ((p >> 26) & 1u) != 0u;
  m.wetness = float((p >> 27) & 7u) / 7.0;
  m.texelEmission = ((p >> 30) & 1u) != 0u;
  m.texelSmooth = ((p >> 31) & 1u) != 0u;
  return m;
}

// 0..1: how much a texel looks like a glowing fleck rather than the host material. Bright and saturated.
float texelGlow(vec3 albedo) {
  float hi = max(max(albedo.r, albedo.g), albedo.b);
  float chroma = hi - min(min(albedo.r, albedo.g), albedo.b);
  return smoothstep(0.55, 0.85, hi) * smoothstep(0.15, 0.4, chroma);
}

// Emission of a texel, in multiples of its albedo: uniform for glowing cubes, masked for flames and flecks.
float texelEmission(Material m, vec3 albedo) {
  return m.emission * (m.texelEmission ? texelGlow(albedo) : 1.0);
}

// Roughness of a texel: gems inside an ore are smooth, the rock around them keeps the block's value.
float texelRoughness(Material m, vec3 albedo) {
  return m.texelSmooth ? mix(m.roughness, 0.1, texelGlow(albedo)) : m.roughness;
}

// Light added on top of the lit color, bypassing the lighting function (5.4).
vec3 emissionOf(uint id, vec3 albedo) {
  Material m = materialOf(id);
  return albedo * texelEmission(m, albedo);
}
#define EMISSION(id, albedo) emissionOf(id, albedo)
#ifndef SHADOW_PASS
// ---------------------------------------------------------------------------------------------------
// The Enhanced lighting model on top of the shadow lookups (shadow_lookup.glsl).
// ---------------------------------------------------------------------------------------------------

// --- The light model (docs 3.1, 3.2). Units: 1.0 is a white surface in vanilla's full light. ---
const float SUN_INTENSITY = 0.7;
const float MOON_INTENSITY = 0.14;
const float BLOCK_INTENSITY = 1.3;
const float MIN_LIGHT = 0.025;    // the floor of the ambient term; the dimension's ambient light adds to it

// From sky.glsl, which is pasted in after the light model.
vec3 skyTransmittance(vec3 L);
float cloudShadow(vec3 worldPos, vec3 L);

// Color and strength of the direct light, sun by day and moon by night, fading to nothing at the horizon.
vec3 directLightColor() {
  float sunAmt = smoothstep(0.0, 0.15, uSunDir.y);
  float moonAmt = smoothstep(0.0, 0.15, uMoonDir.y);
  // The atmosphere reddens and dims the sunlight toward the horizon.
  vec3 sunC = sunAmt > 0.0 ? skyTransmittance(uSunDir) * SUN_INTENSITY : vec3(0.0);
  // Moon phase 0 is full, 4 new.
  float phase = 1.0 - float(min(uMoonPhase, 8 - uMoonPhase)) / 4.0;
  vec3 moonC = vec3(0.45, 0.55, 0.95) * MOON_INTENSITY * (0.3 + 0.7 * phase);
  float weather = (1.0 - 0.85 * uRain) * (1.0 - 0.5 * uThunder);
  return (sunC * sunAmt + moonC * moonAmt) * weather;
}

// Light from the sky for a surface facing n: sky above, horizon at the sides, ground bounce below.
vec3 skyAmbient(vec3 n) {
  float day = smoothstep(-0.1, 0.25, uSunDir.y);
  vec3 zenith = mix(vec3(0.03, 0.05, 0.12), vec3(0.40, 0.55, 0.90), day);
  vec3 horizon = mix(vec3(0.03, 0.045, 0.09), vec3(0.50, 0.56, 0.68), day);
  vec3 ground = mix(vec3(0.015, 0.02, 0.03), vec3(0.24, 0.22, 0.19), day);
  // Dusk and dawn warm the horizon.
  float twilight = 1.0 - smoothstep(0.0, 0.35, abs(uSunDir.y));
  horizon = mix(horizon, vec3(0.75, 0.45, 0.35) * 0.7, twilight * 0.5);
  vec3 c = n.y > 0.0 ? mix(horizon, zenith, n.y) : mix(horizon, ground, -n.y);
  return c * (1.0 - 0.4 * uRain);
}

// Irradiance on a surface at `pos` facing `n`, to multiply with its albedo. `light` is sky, block in
// 0..1 and `ao` the voxel ambient occlusion factor.
vec3 enhancedLight(vec3 pos, vec3 n, vec2 light, float ao) {
  float ndl = max(dot(n, uShadowDir), 0.0);
  vec3 direct = vec3(0.0);
  if (ndl > 0.0) {
    // Outside the maps, whether the sky is visible stands in for the shadow.
    float vis = sunVisibility(pos, n, smoothstep(0.35, 0.9, light.x));
    vis *= cloudShadow(vec3(uCamBlock) + pos, uShadowDir);
    direct = directLightColor() * (ndl * vis);
  }
  vec3 ambient = skyAmbient(n) * (light.x * light.x) + vec3(MIN_LIGHT + 0.4 * uAmbientLight);
  float b = light.y;
  vec3 blockRgb = mix(vec3(1.0, 0.52, 0.2), vec3(1.0, 0.85, 0.65), b * b * b) * (pow(b, 2.2) * BLOCK_INTENSITY);
  return direct + (ambient + blockRgb) * ao;
}
#endif
#else
#define EMISSION(id, albedo) vec3(0.0)
#endif

#ifdef SHADOW_PASS
// The shadow pass: one ray per texel of the cascade, from its plane along -uShadowDir, writing how far
// it got. Water does not stop it.
void main() {
  float h = uShadowHalf[uShadowCascade];
  vec3 o = uShadowCenter[uShadowCascade] + uShadowDir * uShadowRay[uShadowCascade]
      + uShadowRight * (vNdc.x * h) + uShadowUp * (vNdc.y * h);
  vec3 d = -uShadowDir;
  d = vec3(abs(d.x) < 1e-6 ? 1e-6 : d.x, abs(d.y) < 1e-6 ? 1e-6 : d.y, abs(d.z) < 1e-6 ? 1e-6 : d.z);

  gTapCap = 1;
  float tHit;
  uint id;
  int axis, level, steps, hitKind;
  ivec3 voxel;
  vec3 albedo, hitNormal;
  bool hit = trace(o, d, tHit, id, axis, voxel, level, albedo, steps, hitNormal, hitKind);
  fragColor = vec4(hit ? tHit : 1.0e6, 0.0, 0.0, 1.0);
}
#else
#ifdef ENHANCED
#include "sky.glsl"
#endif

// Color of the solid surface hit at distance `tHit` along `d` from `o` (camera relative), lit and fogged.
// `tPrior` is how far the ray had already travelled from the camera before `o`, for the fade between
// levels and the water fog; reflections and refraction start their rays at the water.
vec3 shadeHit(vec3 o, vec3 d, float tHit, float tPrior, uint id, ivec3 voxel, int level, vec3 albedo, vec3 hitNormal, int hitKind) {
  float tFade = tPrior + tHit;

  // Flat per-face shading, same factors vanilla uses for its directional light.
  float shade = (hitKind & 4) != 0 ? 1.0 : dot(hitNormal * hitNormal, vec3(0.6, hitNormal.y > 0.0 ? 1.0 : 0.5, 0.8));

  // Sky and block light (0..1) and ambient occlusion. Only level 0 has light data; the rest is open sky.
  vec2 light = vec2(1.0, 0.0);
  float ao = 1.0;
  if (level == 0) {
    // Fade out over the second half of level 0, where the LOD switch happens.
    float fade = clamp((uBase - tFade) / (0.5 * uBase), 0.0, 1.0);
    vec2 l;
    if ((hitKind & 4) != 0) {
      // Plants (unshaded models) get no AO and the light of their own cell, like in vanilla.
      l = lightAt(voxel);
    } else {
      vec3 p = o + d * tHit - vec3(voxel - uCamBlock);
      vec3 an = abs(hitNormal);
      int a = an.x >= an.y && an.x >= an.z ? 0 : (an.y >= an.z ? 1 : 2);
      ivec3 cell = voxel;
      // A face inside the cell (the top of a candle or a slab) is lit from the cell itself; a face on
      // the cell boundary from the cell in front, which is what the AO looks at.
      if ((hitKind & 3) != 0 && (hitNormal[a] > 0.0 ? p[a] < 0.999 : p[a] > 0.001)) cell[a] -= hitNormal[a] > 0.0 ? 1 : -1;
      float occ = ambientOcclusion(cell, a, -hitNormal, p, l);
      ao = mix(1.0, occ, fade);
    }
    light = mix(vec2(1.0, 0.0), l / 15.0, fade);
  }

#ifdef ENHANCED
  vec3 lit;
  if (uSunLighting != 0) {
    // HDR, so no clamp. Unshaded models (plants) are lit as if facing up.
    vec3 ln = (hitKind & 4) != 0 ? vec3(0.0, 1.0, 0.0) : hitNormal;
    lit = enhancedLight(o + d * tHit, ln, light, ao);
  } else {
    lit = min(lightRgb(light) * shade * ao, vec3(1.0));
  }
#else
  vec3 lit = lightRgb(light) * shade * ao;
  lit = min(lit, vec3(1.0));
#endif
  if (uDebug == 4) lit = vec3(1.0);

  if (uDebug == 2) {
    vec3 tint = 0.5 + 0.5 * cos(6.28318 * (float(level) * 0.17 + vec3(0.0, 0.33, 0.67)));
    albedo = mix(albedo, tint, 0.6);
  }

  vec3 rgb = underFog(albedo * lit + EMISSION(id, albedo), tFade);
#ifdef ENHANCED
  rgb = atmosphereHit(rgb, vec3(uCamBlock) + o, d, tHit);
#endif
  return rgb;
}

#ifdef ENHANCED
// --- Water (docs/enhanced.md 4.4 - 4.7) ---------------------------------------------------------------
uniform float uWaterWaves;   // wave slope scale, 0 for a flat surface
uniform int uWaterReflect;   // 0 off, 1 the sky only, 2 the sky and traced reflections of the world
uniform int uWaterRefract;   // 1: trace the bent ray below the surface

const float WATER_IOR = 1.33;
const float WATER_F0 = 0.03;         // reflectance looking straight down (physically 0.02)
const float WATER_ABSORB = 0.3;      // per block, scaled by how far each channel is from the water's color
const float WATER_DEEP = 64.0;       // blocks of water assumed where the refracted ray finds nothing
const float REFRACT_DIST = 96.0;     // beyond this the ray goes straight through, and is only dimmed
const float REFRACT_MAX = 96.0;      // longest refracted ray
const int REFRACT_ITERS = 160;
const float REFLECT_MAX = 200.0;     // longest reflected ray
const int REFLECT_ITERS = 120;
const float REFLECT_FADE_FROM = 128.0;  // traced reflections give way to the sky between these distances
const float REFLECT_FADE_TO = 192.0;
const float REFLECT_MIN_F = 0.035;      // below this much Fresnel the reflection is only the sky

#include "water_waves.glsl"

vec3 safeDir(vec3 d) {
  return vec3(abs(d.x) < 1e-6 ? 1e-6 : d.x, abs(d.y) < 1e-6 ? 1e-6 : d.y, abs(d.z) < 1e-6 ? 1e-6 : d.z);
}

vec3 skyOrFog(vec3 o, vec3 d) {
  return (uSkyFeatures & 1) != 0 ? skyPixel(vec3(uCamBlock) + o, d) : uFogColor;
}

// A short ray for the water, from `o` along `d`: the color of what it hits, shaded. The water globals
// the primary ray left behind are overwritten, so read them first. `fogged` for rays under water.
bool traceShade(vec3 o, vec3 d, float maxT, int iters, float tPrior, bool fogged, out float tHit, out vec3 col) {
  d = safeDir(d);
  gWaterT = -1.0;
  gWet = false;
  gWaterHf = false;
  gKeepSeeThrough = false;
  gHfTintOn = false;
  gUnder = false;
  gTraceMax = maxT;
  gIterCap = iters;
  gTapCap = 2;

  uint id;
  int axis, level, steps, kind;
  ivec3 voxel;
  vec3 albedo, n;
  bool hit = trace(o, d, tHit, id, axis, voxel, level, albedo, steps, n, kind);

  gTraceMax = 1.0e30;
  gIterCap = 400;
  gTapCap = 8;
  gUnder = fogged;
  col = hit ? shadeHit(o, d, tHit, tPrior, id, voxel, level, albedo, n, kind) : vec3(0.0);
  return hit;
}

// What is seen through `depth` blocks of water of color `albedo` lit by `light`: the color behind it,
// absorbed channel by channel (red first), plus the water's own scattered light.
vec3 waterAbsorb(vec3 behind, float depth, vec3 albedo, vec3 light) {
  vec3 sigma = (vec3(1.0) - clamp(albedo, 0.0, 0.95)) * WATER_ABSORB;
  vec3 tr = exp(-sigma * depth);
  return behind * tr + albedo * light * (1.0 - tr);
}

// Distance from the near plane along the primary ray to the first thing the refracted ray under the water
// surface found (the length of the bent path), or past REFRACT_MAX when it found nothing; -1 when the ray
// was not refracted. It is the depth of the pixel, so entities, which entity_refract.glsl moves to the
// same bent path, sort against the terrain they are seen through.
float gRefractT = -1.0;

// The shaded water surface the primary ray found (the water globals): wave normal, Fresnel mix of the
// reflection and the refracted view, absorption. `solidRgb` is the straight-through hit, used when
// the ray is not refracted.
vec4 enhancedWater(vec3 o, vec3 d, bool hit, float tHit, vec3 solidRgb, vec3 waterAlbedo, vec3 waterLit, vec3 wn) {
  float tW = gWaterT;
  bool exitW = gWaterExit;   // the camera is under the surface
  bool under = gUnder;
  vec3 P = o + d * tW;

  vec3 N = wn;
  if (gWaterAxis == 1 && uWaterWaves > 0.0) {
    vec2 p = vec2(uCamBlock.xz & 4095) + P.xz;
    vec2 g = waveSlope(p, uTime, tW * uPixelAngle / max(abs(d.y), 0.05));
    vec3 up = normalize(vec3(-g.x, 1.0, -g.y));
    N = wn.y > 0.0 ? up : -up;
  }
  float c = dot(N, -d);
  if (c < 0.05) {
    N = normalize(N + (0.05 - c) * -d);
    c = max(dot(N, -d), 1e-3);
  }

  float eta = exitW ? WATER_IOR : 1.0 / WATER_IOR;
  vec3 T = refract(d, N, eta);
  bool tir = dot(T, T) < 0.5;   // total internal reflection: refract() gives zero
  float cosS = (exitW && !tir) ? -dot(N, T) : c;
  float F = tir ? 1.0 : WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - cosS, 5.0);

  vec3 refl = vec3(0.0);
  if ((uWaterReflect != 0 && F > 0.002) || tir) {
    vec3 R = reflect(d, N);
    float up = dot(R, wn);
    if (up < 0.02) R = normalize(R + (0.02 - up) * wn);   // a bumpy normal can aim below the flat surface
    vec3 Ro = P + wn * 0.02;
    vec3 sky = exitW ? uFogColor : skyOrFog(Ro, R);
    refl = sky;
    float w = (1.0 - smoothstep(REFLECT_FADE_FROM, REFLECT_FADE_TO, tW)) * smoothstep(REFLECT_MIN_F * 0.8, REFLECT_MIN_F * 1.6, F);
    if (uWaterReflect >= 2 && w > 0.0) {
      float th;
      vec3 col;
      if (traceShade(Ro, R, REFLECT_MAX, REFLECT_ITERS, tW, exitW, th, col)) refl = mix(sky, col, w);
      else if (!exitW) refl = sky;
    }
  }

  vec3 trans = vec3(0.0);
  if (!tir) {
    bool bent = uWaterRefract != 0 && tW < REFRACT_DIST;
    vec3 To = P - wn * 0.01;
    if (!exitW) {
      float depth = WATER_DEEP;
      vec3 behind = vec3(0.0);
      if (bent) {
        float th;
        vec3 col;
        gRefractT = tW + REFRACT_MAX;
        if (traceShade(To, T, REFRACT_MAX, REFRACT_ITERS, tW, false, th, col)) {
          depth = th;
          behind = col;
          gRefractT = tW + th;
        }
      } else if (hit) {
        depth = max(tHit - tW, 0.0);
        behind = solidRgb;
      }
      trans = waterAbsorb(behind, depth, waterAlbedo, waterLit);
    } else {
      if (bent) {
        float th;
        vec3 col;
        if (traceShade(To, T, REFLECT_MAX, REFLECT_ITERS, tW, true, th, col)) {
          trans = col;
        } else {
          gUnder = true;
          trans = underFog(skyOrFog(To, T), tW);
        }
      } else if (hit) {
        trans = solidRgb;
      } else {
        gUnder = true;
        trans = underFog(skyOrFog(To, d), tW);
      }
    }
  }

  gUnder = under;
  vec3 col = trans * (1.0 - F) + refl * F;
  col = atmosphereHit(col, vec3(uCamBlock) + o, d, tW);
  return vec4(col, 1.0);
}
#endif

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
  vec3 hitNormal;
  int hitKind;
  bool hit = trace(o, d, tHit, id, axis, voxel, level, albedo, steps, hitNormal, hitKind);
  bool water = gWaterT >= 0.0;

#ifdef ENHANCED
  // Enhanced owns the sky: shade misses instead of leaving them to vanilla, at the far depth.
  if (!hit && !water && uDebug != 1 && (uSkyFeatures & 1) != 0) {
    gl_FragDepth = 1.0;
    fragColor = vec4(skyPixel(vec3(uCamBlock) + o, d), 1.0);
    return;
  }
#endif
  if (!hit && !water && uDebug != 1) discard;

  // Same depth vanilla would have written for this point (camera-relative position). Water in front of
  // a hit leaves the depth of the hit, so entities in the water still draw over it; water with nothing
  // behind it writes its own.
  float depth = 1.0;
  if (hit || water) {
    vec3 rel = nearP + d * (hit ? tHit : gWaterT);
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

  // Enhanced water traces its own refracted ray, so the straight one behind it is not shaded.
  bool enhancedWaterOn = false;
#ifdef ENHANCED
  enhancedWaterOn = water && uSunLighting != 0 && uDebug != 4 && (hit || (uSkyFeatures & 1) != 0);
  bool bentWater = enhancedWaterOn && uWaterRefract != 0 && gWaterT < REFRACT_DIST;
#else
  bool bentWater = false;
#endif

  vec3 solidRgb = vec3(0.0);
  if (hit && !bentWater) solidRgb = shadeHit(o, d, tHit, 0.0, id, voxel, level, albedo, hitNormal, hitKind);

  if (!water) {
    fragColor = vec4(solidRgb, 1.0);
    return;
  }

  // The first surface between water and not-water on the ray: the still-water texture tinted by the
  // biome, lit like a block, drawn with the texture's alpha over whatever is behind it. Like vanilla,
  // that alpha is the same however deep the water is.
  float vs = gWaterHf ? gWaterSize : float(1 << gWaterLevel);
  vec3 local = clamp((o + d * gWaterT - vec3(gWaterVoxel - uCamBlock)) / vs, 0.0, 1.0);
  vec2 column;
  float lowLayer;
  vec3 farTint;
  if (gWaterHf) {
    // No chunk or tint column out there: the texel carries the biome's water color.
    column = vec2(0.0);
    lowLayer = 0.0;
    farTint = vec3(0.0, 0.0, -1.0);
    gHfTint = gWaterTint;
    gHfTintOn = true;
  } else {
    voxelTint(gWaterVoxel, gWaterLevel, vs, column, lowLayer, farTint);
  }
  vec3 waterAlbedo;
  gKeepSeeThrough = true;
  surface(uint(uWaterId), gWaterVoxel, vs, gWaterAxis, d, local, gWaterT, column, lowLayer, farTint, waterAlbedo);
  gHfTintOn = false;
  float alpha = gSurfAlpha;

  vec2 wl = vec2(1.0, 0.0);
  if (gWaterLevel == 0) wl = lightAt(gWaterVoxel + ivec3(0, int(local.y >= 0.999), 0)) / 15.0;
  vec3 wn = vec3(0.0);
  wn[gWaterAxis] = d[gWaterAxis] > 0.0 ? -1.0 : 1.0;
  float wshade = gWaterExit ? 0.85 : dot(wn * wn, vec3(0.6, wn.y > 0.0 ? 1.0 : 0.5, 0.8));
  vec3 waterLit = uDebug == 4 ? vec3(1.0) : min(lightRgb(wl) * wshade, vec3(1.0));
#ifdef ENHANCED
  if (uSunLighting != 0 && uDebug != 4) waterLit = enhancedLight(o + d * gWaterT, gWaterExit ? -d : wn, wl, 1.0);
  if (enhancedWaterOn) {
    gRefractT = -1.0;
    fragColor = enhancedWater(o, d, hit, tHit, solidRgb, waterAlbedo, waterLit, wn);
    if (gRefractT >= 0.0) {
      // Depth of what the bent ray found, not of what the straight ray would have.
      vec4 clip = uProj * (uView * vec4(nearP + d * gRefractT, 1.0));
      gl_FragDepth = min(clip.z / clip.w * 0.5 + 0.5, 1.0);
    }
    return;
  }
#endif
  vec3 waterRgb = underFog(waterAlbedo * waterLit, gWaterT);
#ifdef ENHANCED
  waterRgb = atmosphereHit(waterRgb, vec3(uCamBlock) + o, d, gWaterT);
  if (!hit && (uSkyFeatures & 1) != 0) {
    // Water with nothing behind it: the sky shows through, so blend here instead of with the target.
    fragColor = vec4(mix(skyPixel(vec3(uCamBlock) + o, d), waterRgb, alpha), 1.0);
    return;
  }
#endif

  if (hit) {
    fragColor = vec4(mix(solidRgb, waterRgb, alpha), 1.0);
  } else {
    fragColor = vec4(waterRgb, alpha);
  }
}
#endif
