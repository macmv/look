//! Predicted terrain from the world seed: where the surface is and what it is
//! made of, sampled one column per voxel and expanded into bricks on demand.
//!
//! The surface height is vanilla's own: the highest block where the final
//! density is positive. The density is the router's `sloped_cheese`
//! (continents, erosion and ridges through the terrain splines, jagged peaks,
//! and the 3D `old_blended_noise`) under the overworld's top and bottom slides,
//! and, like the game, it is only evaluated at the corners of 4 x 8 x 4 block
//! noise cells and interpolated trilinearly in between. The router's
//! `final_density` adds the caves on top. It does not know about aquifers,
//! surface rules, trees or structures. A block is picked from the biome at the
//! surface.
//!
//! The corners are expensive (the blended noise is 40 octaves of Perlin noise),
//! so the search for the surface starts from a cheap 2D estimate
//! (`initial_density`, the shape without the 3D noise) and only evaluates
//! corners near it. Coarse levels, whose voxels are much wider than a cell,
//! skip the interpolation and sample the density at the voxel's center.
//!
//! `tests/oracle.rs` measures it against
//! `NoiseBasedChunkGenerator.getBaseHeight`.

use std::{collections::HashMap, sync::Arc};

use serde_json::Value;

use crate::{
  biome::{flags, BiomeIndex, BiomeInfo},
  density::{Compiler, Graph, Point, Root, Scratch},
  forest::{Canopy, Forest, TreeCache, TreeVoxel},
  rng::Xoroshiro,
};

/// Density above which a point counts as terrain. It must stay above the
/// world-top slide (about -0.08).
const SURFACE_THRESHOLD: f64 = 0.0;
/// Size of a noise cell in blocks: the density is only computed at cell
/// corners.
const CELL_W: i32 = 4;
const CELL_H: i32 = 8;
/// How far above the 2D estimate the search for the surface starts, in blocks.
/// The 3D noise moves the surface by up to roughly this much.
const SEARCH_MARGIN: i32 = 48;
/// How far below the surface a voxel tile looks for an air gap under an
/// overhang, in blocks. Deeper gaps are caves, which only matter once a ray
/// can get into them.
const GAP_SCAN_DEPTH: i32 = 96;
/// The shortest gap kept at level 0, in blocks (coarser levels keep gaps of at
/// least a voxel).
const MIN_GAP: i32 = 4;
/// Height difference between the columns on either side of one, per block of
/// run, from which vanilla's `steep` surface rule holds: 4 blocks over 2.
const STEEP_SLOPE: i32 = 2;
/// Vanilla cools a biome by this much per block above y 80, and snows below a
/// temperature of 0.15.
const LAPSE_PER_BLOCK: f32 = 0.05 / 40.0;

pub const NO_TERRAIN: i16 = i16::MIN;
pub const BRICK_VOXELS: usize = 16 * 16 * 16;

/// What the top of a column is made of. The order is shared with
/// `SeedSource.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Surface {
  Grass   = 0,
  Sand    = 1,
  RedSand = 2,
  Gravel  = 3,
  Snow    = 4,
  Stone   = 5,
}

pub const SURFACES: usize = 6;

impl Surface {
  fn from_u8(v: u8) -> Surface {
    match v {
      0 => Surface::Grass,
      1 => Surface::Sand,
      2 => Surface::RedSand,
      3 => Surface::Gravel,
      4 => Surface::Snow,
      _ => Surface::Stone,
    }
  }
}

/// Voxel ids to write into bricks, set by Java (they come from
/// `BlockClassifier`). Per surface kind the top block and the filler below it,
/// then stone and the water stand-in.
#[derive(Clone, Debug, Default)]
pub struct Ids {
  pub top:    [u16; SURFACES],
  pub filler: [u16; SURFACES],
  pub stone:  u16,
  pub water:  u16,
  /// Voxel id per block id of the tree decoration (see `forest::Forest`), 0
  /// for blocks that are not drawn.
  pub trees:  Arc<[u16]>,
}

impl Ids {
  pub const LEN: usize = SURFACES * 2 + 2;

  pub fn from_slice(v: &[i32]) -> Option<Ids> {
    if v.len() != Self::LEN {
      return None;
    }
    let mut ids = Ids::default();
    for k in 0..SURFACES {
      ids.top[k] = v[k * 2] as u16;
      ids.filler[k] = v[k * 2 + 1] as u16;
    }
    ids.stone = v[SURFACES * 2] as u16;
    ids.water = v[SURFACES * 2 + 1] as u16;
    Some(ids)
  }
}

/// The surface of one level-k section column: 16 x 16 samples, indexed `z * 16
/// + x`.
pub struct Tile {
  pub top:        [i16; 256],
  /// Per column, the height of the underside of the overhang slab (`gap_top`)
  /// and of the ground below it (`gap_bottom`): the blocks in
  /// `gap_bottom..gap_top` are air under solid ground that reaches up to `top`.
  /// Equal values (the default, 0 and 0) mean the column has no gap.
  pub gap_top:    [i16; 256],
  pub gap_bottom: [i16; 256],
  pub surface:    [u8; 256],
  /// Grass color (0xRRGGBB) of the biome of each column.
  pub grass:      [u32; 256],
  /// Water color (0xRRGGBB) of the biome of each column.
  pub water:      [u32; 256],
  /// Foliage color (0xRRGGBB) of the biome of each column.
  pub foliage:    [u32; 256],
  /// Tree blocks of a voxel tile, in voxels of the tile's level, sorted by `y`.
  pub trees:      Vec<TreeVoxel>,
  /// Tree canopies of a heightfield tile.
  pub canopy:     Option<Box<Canopy>>,
}

/// What a sampled tile is for, which decides what trees it carries.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TileKind {
  /// No trees.
  Plain,
  /// Bricks: trees as voxels.
  Voxel,
  /// Far heightfield: trees as column heights.
  Heightfield,
}

/// Layout of a tile's grass colors as texels of the far tint texture (see
/// `TintMap.java`): a texel is `max(8, 2 << level)` blocks wide, so the
/// wrapping texture always spans a whole level. Returns `(texels per side,
/// columns per texel side)`.
/// Offset in bytes of the foliage colors in what `Tile::write_far_tint` writes
/// (a texture of at most 8 x 8 texels comes before).
pub const FAR_TINT_FOLIAGE: usize = 256;
/// Bytes `Tile::write_far_tint` needs.
/// Offset in bytes of the water colors.
pub const FAR_TINT_WATER: usize = 2 * FAR_TINT_FOLIAGE;
pub const FAR_TINT_BYTES: usize = 3 * FAR_TINT_FOLIAGE;

pub fn far_tint_layout(level: u32) -> (usize, usize) {
  let cell = 8usize.max(2usize << level);
  let cols = cell >> level;
  (16 / cols, cols)
}

impl Tile {
  pub fn empty() -> Tile {
    Tile {
      top:        [NO_TERRAIN; 256],
      gap_top:    [0; 256],
      gap_bottom: [0; 256],
      surface:    [0; 256],
      grass:      [0; 256],
      water:      [0; 256],
      foliage:    [0; 256],
      trees:      Vec::new(),
      canopy:     None,
    }
  }

  /// Writes the tile as RGBA texels of the far tint textures, row major, and
  /// returns the texels per side. The grass colors come first, then (at
  /// `FAR_TINT_FOLIAGE`) the foliage colors and (at `FAR_TINT_WATER`) the
  /// water colors in the same layout. A texel takes
  /// the color of the first grass column of its group (so a coast does not
  /// tint its grass with the sea's biome), else of the group's middle column.
  /// Texels with no terrain have alpha 0.
  pub fn write_far_tint(&self, level: u32, out: &mut [u8]) -> usize {
    let (side, cols) = far_tint_layout(level);
    for tz in 0..side {
      for tx in 0..side {
        let col = |dz: usize, dx: usize| (tz * cols + dz) * 16 + tx * cols + dx;
        let mut pick = col(cols / 2, cols / 2);
        'search: for dz in 0..cols {
          for dx in 0..cols {
            let c = col(dz, dx);
            if self.top[c] != NO_TERRAIN && self.surface[c] == Surface::Grass as u8 {
              pick = c;
              break 'search;
            }
          }
        }
        let o = (tz * side + tx) * 4;
        let alpha = if self.top[pick] == NO_TERRAIN { 0 } else { 0xFF };
        let colors = [
          (self.grass[pick], o),
          (self.foliage[pick], FAR_TINT_FOLIAGE + o),
          (self.water[pick], FAR_TINT_WATER + o),
        ];
        for (rgb, o) in colors {
          out[o] = (rgb >> 16) as u8;
          out[o + 1] = (rgb >> 8) as u8;
          out[o + 2] = rgb as u8;
          out[o + 3] = alpha;
        }
      }
    }
    side
  }

  /// Writes the 4096 voxels of section row `sy` of this column into `out` and
  /// returns how many are not air. Always writes the whole brick, since
  /// callers may layer other data over it.
  ///
  /// The voxel that holds the surface, and the topmost water voxel, are only
  /// filled up to the real height (see `with_fill`), so a coarse level's
  /// terraces are not a whole voxel tall.
  pub fn fill_brick(
    &self,
    level: u32,
    sy: i32,
    t: &TerrainParams,
    ids: &Ids,
    out: &mut [u16; BRICK_VOXELS],
  ) -> u32 {
    let size = 1i64 << level;
    let half = size >> 1;
    let y0 = (sy as i64) << (level + 4);
    let filler_depth = 4.max(2 * size);
    let sea = t.sea_level as i64;

    let mut solid = 0;
    for z in 0..16 {
      for x in 0..16 {
        let col = z * 16 + x;
        let top = self.top[col];
        let surface = Surface::from_u8(self.surface[col]) as usize;
        let (top_id, filler_id) = (ids.top[surface], ids.filler[surface]);

        for y in 0..16 {
          let vy = y0 + (y as i64) * size;
          let mut voxel = 0;
          if top != NO_TERRAIN && vy >= t.min_y as i64 && vy < t.max_y as i64 {
            let top = top as i64;
            if vy >= top {
              if vy < sea {
                voxel = with_fill(ids.water, sea - vy, size);
              }
            } else if vy + size >= top {
              // The voxel holding the surface. When that voxel also holds the
              // sea level it is the water, unless voxels are single blocks:
              // coarser ones would lose the shallows, and the seabed is a
              // voxel below that is not far off. Any deeper seabed keeps its
              // partial fill like land does, with the water in the voxel above.
              voxel = if top < sea && size > 1 && vy + size > sea {
                with_fill(ids.water, sea - vy, size)
              } else {
                with_fill(top_id, top - vy, size)
              };
            } else {
              let (gap_top, gap_bottom) = (self.gap_top[col] as i64, self.gap_bottom[col] as i64);
              let center = vy + half;
              let depth_id = |from: i64| {
                if from - center <= filler_depth {
                  filler_id
                } else {
                  ids.stone
                }
              };
              if gap_top <= gap_bottom || center >= gap_top {
                voxel = depth_id(top);
              } else if center >= gap_bottom {
                // Under the overhang. Whatever is below the sea level there
                // is flooded.
                if vy < sea {
                  voxel = with_fill(ids.water, sea - vy, size);
                }
              } else if vy + size >= gap_bottom {
                // The ground under the overhang is a second surface.
                voxel = with_fill(top_id, gap_bottom - vy, size);
              } else {
                voxel = depth_id(gap_bottom);
              }
            }
          }
          if voxel != 0 {
            solid += 1;
          }
          out[(y * 16 + z) * 16 + x] = voxel;
        }
      }
    }

    // Trees on top of the terrain, which they replace where they overlap it.
    let (row0, row1) = ((sy as i32) * 16, (sy as i32) * 16 + 16);
    let first = self.trees.partition_point(|t| (t.y as i32) < row0);
    for t in &self.trees[first..] {
      if t.y as i32 >= row1 {
        break;
      }
      let id = ids.trees.get(t.id as usize).copied().unwrap_or(0);
      if id == 0 {
        continue;
      }
      let at = ((t.y as i32 - row0) as usize * 16 + t.z as usize) * 16 + t.x as usize;
      if out[at] == 0 {
        solid += 1;
      }
      out[at] = with_fill(id, t.fill as i64, size);
    }
    solid
  }
}

/// Height of a heightfield texel that has no terrain.
pub const NO_HEIGHT: i16 = i16::MIN;
/// Kind code of a water heightfield texel. Land is `1 + Surface`, 0 is no
/// terrain.
pub const HF_WATER: u8 = 7;
/// Kind code of a texel whose column is a tree's canopy or trunk: its color is
/// the whole story. The code is this plus the tree's height above the ground
/// (up to 200), which lets the shader color the bare trunk below the canopy.
pub const HF_TREE: u8 = 8;
/// Bytes of one heightfield tile: 256 native-endian i16 heights, then 256 RGBA
/// texels.
pub const HF_BYTES: usize = 256 * 2 + 256 * 4;

impl Tile {
  /// Writes the tile as heightfield texels, for ray marching terrain that is
  /// too far for voxels: per column the height of the surface (the seabed
  /// under water, whose surface the shader puts at the sea level;
  /// `NO_HEIGHT` without terrain), then per column RGBA where rgb
  /// is the biome's grass color (water color over water) and alpha the kind (0
  /// none, `1 + Surface`, `HF_WATER`).
  pub fn write_heightfield(&self, sea_level: i32, out: &mut [u8; HF_BYTES]) {
    for col in 0..256 {
      let top = self.top[col];
      let (mut height, mut kind) = if top == NO_TERRAIN {
        (NO_HEIGHT, 0)
      } else if (top as i32) < sea_level {
        (top, HF_WATER)
      } else {
        (top, 1 + self.surface[col])
      };
      let mut rgb = if kind == HF_WATER { self.water[col] } else { self.grass[col] };
      if let Some(c) = self.canopy.as_deref() {
        if kind != 0 && c.top[col] != NO_TERRAIN && c.top[col] > height {
          // A plant is as tall as the cap, so the shader finds no trunk under
          // it.
          let rise = if c.plant[col] { 200 } else { (c.top[col] as i32 - height as i32).min(200) };
          kind = HF_TREE + rise as u8;
          height = c.top[col];
          rgb = c.rgb[col];
        }
      }
      out[col * 2..col * 2 + 2].copy_from_slice(&height.to_ne_bytes());

      let o = 512 + col * 4;
      out[o] = (rgb >> 16) as u8;
      out[o + 1] = (rgb >> 8) as u8;
      out[o + 2] = rgb as u8;
      out[o + 3] = kind;
    }
  }
}

/// Bit position of the fill height in a brick's u16 texels, above the voxel id.
/// Shared with `BlockClassifier.FILL_SHIFT` and the shader.
pub const FILL_SHIFT: u32 = 13;
/// Fill heights are multiples of 1 / FILL_STEPS of a voxel.
pub const FILL_STEPS: i64 = 8;

/// The voxel `id` filled up to `fill` blocks of its `size` blocks of height. A
/// fill field of 0 means the whole voxel, 1..7 that many eighths. A voxel is
/// never rounded away entirely.
pub fn with_fill(id: u16, fill: i64, size: i64) -> u16 {
  if fill >= size {
    return id;
  }
  let steps = ((fill * FILL_STEPS + size / 2) / size).clamp(1, FILL_STEPS);
  if steps >= FILL_STEPS {
    id
  } else {
    id | ((steps as u16) << FILL_SHIFT)
  }
}

/// The constants of the world's height range, split off so bricks can be built
/// without the graph.
#[derive(Clone, Copy, Debug)]
pub struct TerrainParams {
  pub sea_level: i32,
  pub min_y:     i32,
  pub max_y:     i32,
}

/// Densities at noise cell corners, by column and cell row, for the columns of
/// one tile (which share corners). `NaN` marks a corner not computed yet.
#[derive(Default)]
pub(crate) struct CornerCache {
  columns: HashMap<(i32, i32), Vec<f64>>,
}

pub struct Terrain {
  pub params:      TerrainParams,
  graph:           Graph,
  /// Cheap 2D estimate of the surface shape (no 3D noise), to know where to
  /// look.
  estimate:        Root,
  /// The same without the jagged peaks, only kept for tests.
  flat:            Root,
  /// The surface shape with the 3D noise, when the world has one.
  sloped_cheese:   Option<Root>,
  /// What the game generates from: the sloped cheese with the caves and the
  /// world's slides.
  final_density:   Option<Root>,
  temperature:     Root,
  humidity:        Root,
  continentalness: Root,
  erosion:         Root,
  depth:           Root,
  weirdness:       Root,
  biomes:          BiomeIndex,
  seed:            i64,
  /// The world's tree features, when the config describes them.
  pub forest:      Option<Forest>,
}

impl Terrain {
  /// Builds the generator for a world. `config` is the JSON written by
  /// `TerrainExport.java`.
  pub fn from_config(seed: i64, config: &[u8]) -> Result<Terrain, String> {
    let cfg: Value =
      serde_json::from_slice(config).map_err(|e| format!("bad terrain config: {e}"))?;
    let obj = cfg.as_object().ok_or("terrain config is not an object")?;

    let int = |key: &str| -> Result<i32, String> {
      obj
        .get(key)
        .and_then(Value::as_i64)
        .map(|v| v as i32)
        .ok_or_else(|| format!("config is missing {key}"))
    };
    let params = TerrainParams {
      sea_level: int("sea_level")?,
      min_y:     int("min_y")?,
      max_y:     int("min_y")? + int("height")?,
    };

    let functions =
      obj.get("functions").and_then(Value::as_object).ok_or("config is missing functions")?;
    let noises = obj.get("noises").and_then(Value::as_object).ok_or("config is missing noises")?;
    let roots = obj.get("roots").and_then(Value::as_object).ok_or("config is missing roots")?;

    // The same factory the game's RandomState derives all its noises from.
    let factory = Xoroshiro::from_seed(seed).fork_positional();
    let mut compiler = Compiler::new(functions, noises, factory);
    let mut root = |name: &str| -> Result<Root, String> {
      let v = roots.get(name).ok_or_else(|| format!("config is missing root {name}"))?;
      compiler.root(v).map_err(|e| format!("{name}: {e}"))
    };

    // Older exports only have the smooth density; the jagged one is the better
    // estimate.
    let flat = root("initial_density_without_jaggedness")?;
    let estimate =
      if roots.contains_key("initial_density") { root("initial_density")? } else { flat };
    let sloped_cheese =
      if roots.contains_key("sloped_cheese") { Some(root("sloped_cheese")?) } else { None };
    let final_density =
      if roots.contains_key("final_density") { Some(root("final_density")?) } else { None };
    let temperature = root("temperature")?;
    let humidity = root("vegetation")?;
    let continentalness = root("continents")?;
    let erosion = root("erosion")?;
    let depth = root("depth")?;
    let weirdness = root("ridges")?;
    let graph = compiler.finish();

    let biomes = BiomeIndex::from_json(obj.get("biomes").ok_or("config is missing biomes")?)?;
    let forest = match obj.get("decoration") {
      Some(v) => Some(Forest::from_json(v)?),
      None => None,
    };

    Ok(Terrain {
      params,
      graph,
      estimate,
      flat,
      sloped_cheese,
      final_density,
      temperature,
      humidity,
      continentalness,
      erosion,
      depth,
      weirdness,
      biomes,
      seed,
      forest,
    })
  }

  pub fn scratch(&self) -> Scratch { self.graph.scratch() }

  /// The 2D estimate of the density.
  pub fn density(&self, s: &mut Scratch, x: i32, y: i32, z: i32) -> f64 {
    self.graph.eval(self.estimate, Point { x, y, z }, s)
  }

  /// The six climate values at a block position, quantized like
  /// `Climate.TargetPoint`.
  pub fn climate(&self, s: &mut Scratch, x: i32, y: i32, z: i32) -> [i64; 6] {
    let p = Point { x, y, z };
    let q = |v: f64| ((v as f32) * 10000.0f32) as i64;
    [
      q(self.graph.eval(self.temperature, p, s)),
      q(self.graph.eval(self.humidity, p, s)),
      q(self.graph.eval(self.continentalness, p, s)),
      q(self.graph.eval(self.erosion, p, s)),
      q(self.graph.eval(self.depth, p, s)),
      q(self.graph.eval(self.weirdness, p, s)),
    ]
  }

  pub fn seed(&self) -> i64 { self.seed }

  /// The index (of the export's biome table) of the biome at a block
  /// position, picked like `biome_at` does.
  pub fn biome_index_at(&self, s: &mut Scratch, x: i32, y: i32, z: i32) -> usize {
    self.biomes.nearest_index(self.climate(s, (x >> 2) << 2, (y >> 2) << 2, (z >> 2) << 2)).0
  }

  pub fn biome_info(&self, index: usize) -> BiomeInfo { self.biomes.info(index) }

  /// The biome at a block position, as `MultiNoiseBiomeSource.getNoiseBiome`
  /// would pick it.
  pub fn biome_at(&self, s: &mut Scratch, x: i32, y: i32, z: i32) -> BiomeInfo {
    // Biomes are stored per 4x4x4 blocks and sampled at the cell's corner.
    self.biomes.nearest(self.climate(s, (x >> 2) << 2, (y >> 2) << 2, (z >> 2) << 2))
  }

  /// First air block above the 2D estimate of the terrain at a column. Scans
  /// down from the build limit in steps (the last one always lands on the
  /// world floor, so a step larger than the terrain's depth cannot skip it),
  /// then bisects the step that crossed the threshold (the density only falls
  /// with height inside one step, so that is safe).
  fn estimate_top(&self, s: &mut Scratch, x: i32, z: i32) -> Option<i32> {
    let TerrainParams { min_y, max_y, .. } = self.params;

    // Terrain at the very top means this is not a world shaped like the
    // overworld.
    if self.density(s, x, max_y - 1, z) > SURFACE_THRESHOLD {
      return None;
    }

    let mut above = max_y; // known to be at or below the threshold, or the
                           // build limit
    let mut y = max_y - 1;
    loop {
      if self.density(s, x, y, z) > SURFACE_THRESHOLD {
        let mut solid = y; // above the threshold
        let mut air = above;
        while air - solid > 1 {
          let mid = (solid + air) >> 1;
          if self.density(s, x, mid, z) > SURFACE_THRESHOLD {
            solid = mid;
          } else {
            air = mid;
          }
        }
        return Some(solid + 1);
      }
      if y <= min_y {
        return None;
      }
      above = y;
      y = (y - 16).max(min_y);
    }
  }

  /// The density the game generates terrain from, at a noise cell corner: the
  /// surface shape under the overworld's top and bottom slides
  /// (`NoiseRouterData.slideOverworld`).
  fn corner_density(&self, s: &mut Scratch, x: i32, y: i32, z: i32) -> f64 {
    let TerrainParams { min_y, max_y, .. } = self.params;
    if let Some(root) = self.final_density {
      return self.graph.eval(root, Point { x, y, z }, s);
    }
    let root = self.sloped_cheese.unwrap_or(self.estimate);
    let d = self.graph.eval(root, Point { x, y, z }, s);

    let gradient = |from_y: i32, to_y: i32, from: f64, to: f64| {
      let t = ((y - from_y) as f64 / (to_y - from_y) as f64).clamp(0.0, 1.0);
      from + t * (to - from)
    };
    let top = gradient(max_y - 80, max_y - 64, 1.0, 0.0);
    let d = -0.078125 + top * (d + 0.078125);
    let bottom = gradient(min_y, min_y + 24, 0.0, 1.0);
    0.1171875 + bottom * (d - 0.1171875)
  }

  /// Height of the first air block above the terrain at a column, as the game
  /// generates it: the density interpolated between the corners of the noise
  /// cell around the column, so this agrees with `getBaseHeight` apart from
  /// caves. `None` if there is no terrain.
  pub fn surface_top(&self, s: &mut Scratch, x: i32, z: i32) -> Option<i32> {
    self.surface_top_cached(s, &mut CornerCache::default(), x, z)
  }

  pub(crate) fn surface_top_cached(
    &self,
    s: &mut Scratch,
    cache: &mut CornerCache,
    x: i32,
    z: i32,
  ) -> Option<i32> {
    self.surface_profile_cached(s, cache, x, z, 0).map(|(top, _)| top)
  }

  /// Like `surface_top_cached`, and when `min_gap` is above 0 also looks below
  /// the surface for the first run of at least `min_gap` air blocks that has
  /// ground under it within `GAP_SCAN_DEPTH`. That is returned as
  /// `(gap_top, gap_bottom)`: air in `gap_bottom..gap_top`, or `(0, 0)` for
  /// none.
  pub(crate) fn surface_profile_cached(
    &self,
    s: &mut Scratch,
    cache: &mut CornerCache,
    x: i32,
    z: i32,
    min_gap: i32,
  ) -> Option<(i32, (i32, i32))> {
    let TerrainParams { min_y, max_y, .. } = self.params;
    let estimate = self.estimate_top(s, x, z)?;

    // Cell rows are aligned to the world, not to the build limit.
    let base = min_y.div_euclid(CELL_H) * CELL_H;
    let rows = (max_y - base + CELL_H - 1) / CELL_H; // the topmost row sits at
                                                     // or above the limit

    let (x0, z0) = (x.div_euclid(CELL_W) * CELL_W, z.div_euclid(CELL_W) * CELL_W);
    let (fx, fz) = ((x - x0) as f64 / CELL_W as f64, (z - z0) as f64 / CELL_W as f64);
    // Corners the column sits exactly on do not need their neighbors.
    let xs: &[(i32, f64)] = if fx == 0.0 { &[(0, 1.0)] } else { &[(0, 1.0 - fx), (CELL_W, fx)] };
    let zs: &[(i32, f64)] = if fz == 0.0 { &[(0, 1.0)] } else { &[(0, 1.0 - fz), (CELL_W, fz)] };

    let mut density = |row: i32| -> f64 {
      let y = base + row * CELL_H;
      let mut sum = 0.0;
      for &(dz, wz) in zs {
        for &(dx, wx) in xs {
          sum += wx * wz * self.corner(s, cache, x0 + dx, z0 + dz, row, y, rows);
        }
      }
      sum
    };

    // Start a margin above the estimate, higher if the density is still
    // positive there.
    let mut top = (((estimate + SEARCH_MARGIN) - base + CELL_H - 1) / CELL_H).clamp(1, rows);
    while density(top) > SURFACE_THRESHOLD {
      if top >= rows {
        return None;
      }
      top = (top + 2).min(rows);
    }

    let mut found = None;
    let mut upper = density(top);
    for row in (0..top).rev() {
      let lower = density(row);
      if lower > SURFACE_THRESHOLD {
        // Density is linear in y inside the cell: find the highest block still
        // above the threshold.
        let mut at = base + row * CELL_H + 1;
        for k in (0..CELL_H).rev() {
          let t = k as f64 / CELL_H as f64;
          if lower + t * (upper - lower) > SURFACE_THRESHOLD {
            at = base + row * CELL_H + k + 1;
            break;
          }
        }
        found = Some((at, row));
        break;
      }
      upper = lower;
    }
    let (found, found_row) = found?;
    let top = found.clamp(min_y, max_y);
    if min_gap <= 0 {
      return Some((top, (0, 0)));
    }

    // Walk further down, block by block, for the first air run (an overhang's
    // underside) with ground below it.
    let floor = (top - GAP_SCAN_DEPTH).max(min_y);
    let first_row = (floor - base).div_euclid(CELL_H);
    let mut air_top = None;
    let mut upper = density(found_row + 1);
    for row in (first_row..=found_row).rev() {
      let lower = density(row);
      for k in (0..CELL_H).rev() {
        let y = base + row * CELL_H + k;
        if y < floor || y >= top {
          continue;
        }
        let t = k as f64 / CELL_H as f64;
        let solid = lower + t * (upper - lower) > SURFACE_THRESHOLD;
        match air_top {
          None if !solid => air_top = Some(y + 1),
          Some(gap_top) if solid => {
            if gap_top - (y + 1) >= min_gap {
              return Some((top, (gap_top, y + 1)));
            }
            air_top = None; // too short to matter: part of the slab
          }
          _ => {}
        }
      }
      upper = lower;
    }
    Some((top, (0, 0)))
  }

  /// Density at one cell corner, memoized for the tile being sampled.
  fn corner(
    &self,
    s: &mut Scratch,
    cache: &mut CornerCache,
    x: i32,
    z: i32,
    row: i32,
    y: i32,
    rows: i32,
  ) -> f64 {
    let column = cache.columns.entry((x, z)).or_insert_with(|| vec![f64::NAN; rows as usize + 1]);
    let v = column[row as usize];
    if !v.is_nan() {
      return v;
    }
    let v = self.corner_density(s, x, y, z);
    column[row as usize] = v;
    v
  }

  /// Evaluates one of the exported root functions by its config name, for tests
  /// and debugging.
  pub fn eval_root(&self, name: &str, s: &mut Scratch, x: i32, y: i32, z: i32) -> Option<f64> {
    let root = match name {
      "initial_density_without_jaggedness" => self.flat,
      "initial_density" => self.estimate,
      "sloped_cheese" => self.sloped_cheese?,
      "final_density" => self.final_density?,
      "temperature" => self.temperature,
      "vegetation" => self.humidity,
      "continents" => self.continentalness,
      "erosion" => self.erosion,
      "depth" => self.depth,
      "ridges" => self.weirdness,
      _ => return None,
    };
    Some(self.graph.eval(root, Point { x, y, z }, s))
  }

  /// Index and fitness of the nearest biome point to a quantized climate
  /// target.
  pub fn nearest_biome(&self, target: [i64; 6]) -> (usize, i64) {
    self.biomes.nearest_index(target)
  }

  /// Samples the surface of the 16 x 16 columns of a level-`level` section
  /// column, without trees.
  pub fn sample_tile(&self, s: &mut Scratch, level: u32, sx: i32, sz: i32) -> Tile {
    self.sample_tile_with(s, level, sx, sz, TileKind::Plain, None)
  }

  /// Like `sample_tile`, with the trees a tile of `kind` carries (if the world
  /// has any), taken from `trees`.
  pub fn sample_tile_with(
    &self,
    s: &mut Scratch,
    level: u32,
    sx: i32,
    sz: i32,
    kind: TileKind,
    trees: Option<&TreeCache>,
  ) -> Tile {
    self.sample_tile_in(s, &mut CornerCache::default(), level, sx, sz, kind, trees)
  }

  /// Like `sample_tile_with`, memoizing corner densities in `cache`. Tiles of
  /// the same level that touch share the corners along their border, so
  /// sampling them one after another with one cache computes those once.
  pub(crate) fn sample_tile_in(
    &self,
    s: &mut Scratch,
    cache: &mut CornerCache,
    level: u32,
    sx: i32,
    sz: i32,
    kind: TileKind,
    trees: Option<&TreeCache>,
  ) -> Tile {
    let mut tile = Tile::empty();
    let mut steep_biome = [false; 256];
    let size = 1i64 << level;
    let half = size >> 1;
    let x0 = (sx as i64) << (level + 4);
    let z0 = (sz as i64) << (level + 4);
    let TerrainParams { min_y, max_y, sea_level } = self.params;

    for z in 0..16usize {
      for x in 0..16usize {
        let bx = (x0 + x as i64 * size + half) as i32;
        let bz = (z0 + z as i64 * size + half) as i32;
        let min_gap = if kind == TileKind::Voxel { MIN_GAP.max(size as i32) } else { 0 };
        let Some((top, gap)) = self.surface_profile_cached(s, cache, bx, bz, min_gap) else {
          continue;
        };

        let by = top.clamp(min_y, max_y - 1);
        let biome = self.biome_at(s, bx, by, bz);

        let col = z * 16 + x;
        tile.top[col] = top as i16;
        // Gaps under the sea are left out: that is water or seabed caves.
        if top >= sea_level {
          (tile.gap_top[col], tile.gap_bottom[col]) = (gap.0 as i16, gap.1 as i16);
        }
        tile.surface[col] = surface_of(biome, top, sea_level) as u8;
        tile.grass[col] = biome.grass;
        tile.water[col] = biome.water;
        tile.foliage[col] = biome.foliage;
        steep_biome[col] = biome.flags & flags::STEEP_ROCK != 0;
      }
    }
    tile.expose_cliffs(level, &steep_biome);
    if let (Some(forest), Some(cache)) = (&self.forest, trees) {
      match kind {
        TileKind::Plain => {}
        TileKind::Voxel => tile.trees = forest.voxel_trees(self, s, cache, level, sx, sz),
        TileKind::Heightfield => {
          tile.canopy = forest.canopy(self, s, cache, level, sx, sz, &tile.foliage, &tile.grass);
        }
      }
    }
    tile
  }
}

impl Tile {
  /// Turns the surface of steep ground into stone in the biomes whose surface
  /// rules ask for it, like vanilla's `steep` condition: the columns on
  /// either side of one (clamped to the tile, as vanilla clamps to the chunk)
  /// differ by 4 blocks over 2 blocks of run, along either axis.
  fn expose_cliffs(&mut self, level: u32, steep_biome: &[bool; 256]) {
    let limit = STEEP_SLOPE * 2 * (1i32 << level);
    let mut out = self.surface;
    for z in 0..16usize {
      for x in 0..16usize {
        let col = z * 16 + x;
        if self.top[col] == NO_TERRAIN || !steep_biome[col] {
          continue;
        }

        let height = |x: usize, z: usize| self.top[z * 16 + x];
        let pairs = [
          (height(x.saturating_sub(1), z), height((x + 1).min(15), z)),
          (height(x, z.saturating_sub(1)), height(x, (z + 1).min(15))),
        ];
        let steep = pairs.iter().any(|&(a, b)| {
          a != NO_TERRAIN && b != NO_TERRAIN && (a as i32 - b as i32).abs() >= limit
        });
        if steep {
          out[col] = Surface::Stone as u8;
        }
      }
    }
    self.surface = out;
  }
}

/// Picks what the top of a column is made of from its biome and height. `top`
/// is the first block above the ground.
///
/// Follows vanilla's overworld surface rules. Ground under water is gravel,
/// whatever the biome and depth, except in the warm oceans where it is sand;
/// the dirt, grass and sand rules only apply to ground the sea does not cover.
pub fn surface_of(biome: BiomeInfo, top: i32, sea_level: i32) -> Surface {
  let f = biome.flags;
  if top < sea_level {
    return if f & flags::WARM_SEABED != 0 { Surface::Sand } else { Surface::Gravel };
  }
  // Higher ground is colder, so snow reaches lower on cold biomes' mountains.
  if biome.temperature - (top - 80).max(0) as f32 * LAPSE_PER_BLOCK <= 0.15 {
    return Surface::Snow;
  }
  if f & (flags::BEACH | flags::DESERT) != 0 {
    return Surface::Sand;
  }
  if f & flags::BADLANDS != 0 {
    return Surface::RedSand;
  }
  if f & flags::STONE != 0 {
    return Surface::Stone;
  }
  Surface::Grass
}

#[cfg(test)]
mod tests {
  use super::*;

  fn ids() -> Ids {
    let mut ids = Ids::default();
    ids.top = [10; SURFACES];
    ids.filler = [11; SURFACES];
    ids.stone = 12;
    ids.water = 13;
    ids
  }

  fn params() -> TerrainParams { TerrainParams { sea_level: 63, min_y: -64, max_y: 320 } }

  fn brick(tile: &Tile, level: u32, sy: i32) -> Box<[u16; BRICK_VOXELS]> {
    let mut out = Box::new([0u16; BRICK_VOXELS]);
    tile.fill_brick(level, sy, &params(), &ids(), &mut out);
    out
  }

  fn column(out: &[u16; BRICK_VOXELS]) -> Vec<u16> { (0..16).map(|y| out[(y * 16) * 16]).collect() }

  #[test]
  fn surface_voxel_is_partly_filled() {
    let mut tile = Tile::empty();
    tile.top[0] = 100;
    // Level 3: voxels of 8 blocks, so the section row starts at y 0 and voxel
    // 12 spans 96..104.
    let col = column(&brick(&tile, 3, 0));
    assert_eq!(col[12], 10 | (4 << FILL_SHIFT)); // 4 of 8 blocks
    assert_eq!(col[13], 0);
    assert_eq!(col[11], 11); // filler, full
    assert_eq!(col[0], 12); // stone
  }

  #[test]
  fn aligned_surface_is_a_full_voxel() {
    let mut tile = Tile::empty();
    tile.top[0] = 104;
    let col = column(&brick(&tile, 3, 0));
    assert_eq!(col[12], 10);
    assert_eq!(col[13], 0);
  }

  #[test]
  fn thin_surface_keeps_one_eighth() {
    let mut tile = Tile::empty();
    tile.top[0] = 97; // 1 block into a voxel of 32 would round to nothing
    let col = column(&brick(&tile, 5, 0));
    assert_eq!(col[3], 10 | (1 << FILL_SHIFT));
  }

  #[test]
  fn water_surface_is_flat_at_sea_level() {
    let mut tile = Tile::empty();
    tile.top[0] = 20;
    // Level 3, voxel 7 spans 56..64: water up to 63 is 7 of 8 blocks. Voxel 2
    // (16..24) holds the seabed, which is filled up to 20 like land is, with
    // the water above it.
    let col = column(&brick(&tile, 3, 0));
    assert_eq!(col[7], 13 | (7 << FILL_SHIFT));
    assert_eq!(col[8], 0);
    assert_eq!(col[2], 10 | (4 << FILL_SHIFT));
    assert_eq!(col[3], 13);
    assert_eq!(col[1], 11);

    // Shallows: voxel 7 holds both the seabed (62) and the sea level.
    tile.top[0] = 62;
    let col = column(&brick(&tile, 3, 0));
    assert_eq!(col[7], 13 | (7 << FILL_SHIFT));
  }

  #[test]
  fn single_blocks_keep_the_seabed_under_the_water() {
    let mut tile = Tile::empty();
    tile.top[0] = 60;
    // Level 0: the block at 59 is the seabed, the water starts above it.
    let col = column(&brick(&tile, 0, 3));
    assert_eq!(col[11], 10);
    assert_eq!(col[12], 13);
    assert_eq!(col[14], 13);
    assert_eq!(col[15], 0);
  }

  #[test]
  fn heightfield_texels_carry_height_kind_and_color() {
    let mut tile = Tile::empty();
    tile.top[0] = 100;
    tile.surface[0] = Surface::Snow as u8;
    tile.grass[0] = 0x112233;
    tile.water[1] = 0x445566;
    tile.top[1] = 40; // under the sea
    let mut out = [0u8; HF_BYTES];
    tile.write_heightfield(63, &mut out);

    assert_eq!(i16::from_ne_bytes([out[0], out[1]]), 100);
    assert_eq!(&out[512..516], &[0x11, 0x22, 0x33, 1 + Surface::Snow as u8]);
    assert_eq!(i16::from_ne_bytes([out[2], out[3]]), 40);
    assert_eq!(&out[516..520], &[0x44, 0x55, 0x66, HF_WATER]);
    assert_eq!(i16::from_ne_bytes([out[4], out[5]]), NO_HEIGHT);
    assert_eq!(out[512 + 8 + 3], 0);
  }

  #[test]
  fn overhang_leaves_a_gap_with_ground_below() {
    let mut tile = Tile::empty();
    tile.top[0] = 120;
    tile.gap_top[0] = 100;
    tile.gap_bottom[0] = 80;
    // Level 0, section row 4 spans y 64..80 and row 5 spans 80..96.
    let low = column(&brick(&tile, 0, 4));
    assert_eq!(low[15], 10 | (0 << FILL_SHIFT)); // y 79: the ground under the
                                                 // overhang
    assert_eq!(low[14], 11); // filler below it
    let mid = column(&brick(&tile, 0, 5));
    assert!(mid.iter().all(|&v| v == 0)); // y 80..96 is the gap
    let high = column(&brick(&tile, 0, 6)); // y 96..112
    assert_eq!(high[3], 0); // y 99, still gap
    assert_eq!(high[4], 12); // y 100, the slab (stone: deep below the top)
  }

  #[test]
  fn columns_without_a_gap_are_unchanged() {
    let mut tile = Tile::empty();
    tile.top[0] = 100;
    let col = column(&brick(&tile, 0, 6)); // y 96..112
    assert_eq!(col[1], 11); // y 97, filler
    assert_eq!(col[0], 11); // y 96, 4 deep: still filler
  }

  #[test]
  fn level_zero_has_no_partial_voxels() {
    let mut tile = Tile::empty();
    tile.top[0] = 70;
    let col = column(&brick(&tile, 0, 4)); // y 64..80
    assert!(col.iter().all(|&v| v >> FILL_SHIFT == 0));
    assert_eq!(col[5], 10);
    assert_eq!(col[6], 0);
  }
}
