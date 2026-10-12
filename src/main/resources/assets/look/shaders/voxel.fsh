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
uniform sampler2DArray uHfAttr;     // rgb: biome grass color (water color for water), a: kind (0 none, 1 + surface, 7 water)
uniform isampler2DArray uHfTiles;   // highest texel of each 16 x 16 tile, to skip tiles a ray passes over
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
uniform float uTime;              // game ticks, wrapped to a multiple of the sway period

in vec2 vNdc;
out vec4 fragColor;

const float EPS = 1e-3;
const int TINT_BLOCKS = 2048;  // blocks the tint texture covers before wrapping: TintMap.SIZE * CELL
const int TINT_CELL = 4;       // TintMap.CELL
const int TINT_SPLIT_UNIT = 2; // TintMap.SPLIT_UNIT
const int TINT_SIZE = 512;     // TintMap.SIZE
const int TINT_FAR_FIRST = 5;  // TintMap.FAR_FIRST
const int FACE_ROW_TEXELS = 24;  // BlockClassifier.ID_FLOATS / 4: texels in one row of the face table
const int META_START = (8192 + 1024) * FACE_ROW_TEXELS;  // BlockClassifier.META_START / 4
const int HF_FINER = 2;          // HeightField.FINER_SHIFT: texels are 2^(level - 2) blocks wide
const int HF_NONE = -32768;      // HeightField.NONE
const int HF_WINDOW = 128;       // HeightField.WINDOW_TILES
const int META_TEXELS = 3;       // BlockClassifier.META_FLOATS / 4
const int QUAD_HDR_START = META_START + 8192 * META_TEXELS;  // BlockClassifier.HDR_START / 4
const int QUAD_POOL_START = QUAD_HDR_START + 8192 * 3;       // BlockClassifier.POOL_START / 4
const int QUAD_TEXELS = 5;       // BlockClassifier.QUAD_FLOATS / 4
const int MAX_QUADS = 48;        // BlockClassifier.MAX_QUADS
const float SWAY_OMEGA = 0.0490874;  // 2 pi / 128 ticks, so wrapping uTime at a multiple of 128 is seamless
const uint ID_MASK = 0x1FFFu;    // BlockClassifier.MAX_ID: a brick texel is the voxel id, then the fill height
const uint FILL_SHIFT = 13u;     // BlockClassifier.FILL_SHIFT, in eighths of a voxel, 0 for a whole one

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

// Multiplier for a face table tint: -1 grass, -2 foliage (from the biome tint texture), else a 0xRRGGBB constant.
// Grass of predicted terrain (no loaded chunk) comes from the far layer of the voxel's level.
vec3 tintColor(float tint, vec2 column, float lowLayer, vec3 farTint) {
  if (tint < -0.5) {
    if (gHfTintOn && tint > -1.5) return gHfTint;
    if (farTint.z >= 0.0 && tint > -1.5) return textureLod(uTint, farTint, 0.0).rgb;
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
  int taps = uDebug == 3 ? 1 : int(clamp(ceil(1.0 / cosine), 1.0, 8.0));
  float spread = r * (1.0 / cosine - 1.0);

  int base = variantRow(id, cell) * FACE_ROW_TEXELS + face * 4;
  vec3 rgb = vec3(0.0);
  float alpha = 0.0;

  for (int layer = 0; layer < 2; layer++) {
    vec4 a = texelFetch(uFaces, base + layer * 2);
    vec4 b = texelFetch(uFaces, base + layer * 2 + 1);
    if (b.w < 0.5) break;

    float texelsPerBlock = max(length(a.zw * uAtlasSize), length(b.xy * uAtlasSize));
    float lod = uDebug == 3 ? 0.0 : clamp(log2(max(r * texelsPerBlock, 1e-6)), 0.0, uMaxLod);
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

// Ray march over the far heightfield (see HeightField.java): texels are columns, solid below their
// height, walked in 2D one tile at a time (tiles the ray passes over are skipped by their highest
// texel), then texel by texel inside a tile. `t` is where the ray enters the first far level; the
// level only grows from there. A hit comes out like one from the voxel trace.
bool traceHeightfield(vec3 o, vec3 d, float t, out float tHit, out uint hitId, out int hitAxis, out ivec3 hitVoxel, out int hitLevel, out vec3 albedo, inout int steps, out vec3 hitNormal, out int hitKind) {
  vec3 invD = 1.0 / d;
  float y0 = float(uCamBlock.y) + o.y;   // ray height at t = 0, in blocks
  int last = uLevels + HF_LEVELS - 1;
  // A level's window is 128 tiles wide, one short of what its ring needs when the camera is not
  // tile aligned, so every level hands over to the next one tile early (the next level's tiles start
  // well inside its ring), and the last one just ends.
  float reach = uBase * float(1 << last) - 16.0 * float(1 << (last - HF_FINER));
  ivec2 s = ivec2(d.x > 0.0 ? 1 : -1, d.z > 0.0 ? 1 : -1);
  int axis = 1;

  for (int it = 0; it < 640; it++) {
    if (t > uMaxDist) return false;
    steps++;

    vec3 rel = o + d * t;
    float h = max(abs(rel.x), abs(rel.z));
    if (h >= reach) return false;
    float ry = y0 + d.y * t;
    if (ry > 400.0 && d.y >= 0.0) return false;   // above the world and climbing
    if (ry < -100.0 && d.y <= 0.0) return false;

    int lvl = levelAt(rel);
    if (lvl < last && h >= uBase * float(1 << lvl) - 16.0 * float(1 << (lvl - HF_FINER))) lvl++;
    int layer = lvl - uLevels;
    int sh = lvl - HF_FINER;                 // a texel is 1 << sh blocks wide
    float T = float(1 << sh);
    float eps = EPS * T + t * 2e-7;

    ivec2 camCell = uCamBlock.xz >> sh;
    vec2 camRem = vec2(uCamBlock.xz & ((1 << sh) - 1));   // blocks into the camera's cell
    ivec2 c = ivec2(floor((rel.xz + camRem) / T));          // cell relative to the camera's
    ivec2 tile = (camCell + c) >> 4;
    int tmax = texelFetch(uHfTiles, ivec3(tile & (HF_WINDOW - 1), layer), 0).r;

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
      steps++;
      ivec2 ac = camCell + c;
      int H = texelFetch(uHfHeight, ivec3(ac & (HF_WINDOW * 16 - 1), layer), 0).r;
      if (H != HF_NONE) {
        float fH = float(H);
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

        if (th >= 0.0) {
          vec4 attr = texelFetch(uHfAttr, ivec3(ac & (HF_WINDOW * 16 - 1), layer), 0);
          int kind = int(attr.a * 255.0 + 0.5);
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
            if (kind == 7) {
              id = uint(uHfIds[13]);
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
            if (ok && kind == 7) albedo = attr.rgb;   // water: the biome water color, not the stand-in block
            gHfTintOn = false;
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
    if (t > uMaxDist) return false;
    steps++;

    vec3 rel = o + d * t;
    int lvl = levelAt(rel);
    if (lvl >= uLevels) {
      // Past the voxel levels the ray never comes back to them: the rest is the heightfield.
      if (uHfEnabled == 0) return false;
      return traceHeightfield(o, d, t, tHit, hitId, hitAxis, hitVoxel, hitLevel, albedo, steps, hitNormal, hitKind);
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
      uint raw = texelFetch(uBricks, base + (v.y * 16 + v.z) * 16 + v.x).r;
      uint id = raw & ID_MASK;
      if (id != 0u) {
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

          int kind = int((texelFetch(uColors, int(id)).r >> 25) & 3u);
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
  vec3 hitNormal;
  int hitKind;
  bool hit = trace(o, d, tHit, id, axis, voxel, level, albedo, steps, hitNormal, hitKind);

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
  float shade = (hitKind & 4) != 0 ? 1.0 : dot(hitNormal * hitNormal, vec3(0.6, hitNormal.y > 0.0 ? 1.0 : 0.5, 0.8));

  // Sky and block light (0..1) and ambient occlusion. Only level 0 has light data; the rest is open sky.
  vec2 light = vec2(1.0, 0.0);
  float ao = 1.0;
  if (level == 0) {
    // Fade out over the second half of level 0, where the LOD switch happens.
    float fade = clamp((uBase - tHit) / (0.5 * uBase), 0.0, 1.0);
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
