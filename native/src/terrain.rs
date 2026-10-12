//! Predicted terrain from the world seed: where the surface is and what it is made of, sampled one
//! column per voxel and expanded into bricks on demand.
//!
//! The surface height is vanilla's own: the highest block where the final density is positive. The
//! density is the router's `sloped_cheese` (continents, erosion and ridges through the terrain
//! splines, jagged peaks, and the 3D `old_blended_noise`) under the overworld's top and bottom
//! slides, and, like the game, it is only evaluated at the corners of 4 x 8 x 4 block noise cells
//! and interpolated trilinearly in between. It does not know about caves, aquifers, surface rules,
//! trees or structures. A block is picked from the biome at the surface.
//!
//! The corners are expensive (the blended noise is 40 octaves of Perlin noise), so the search for
//! the surface starts from a cheap 2D estimate (`initial_density`, the shape without the 3D noise)
//! and only evaluates corners near it. Coarse levels, whose voxels are much wider than a cell, skip
//! the interpolation and sample the density at the voxel's center.
//!
//! `tests/oracle.rs` measures it against `NoiseBasedChunkGenerator.getBaseHeight`.

use std::collections::HashMap;

use serde_json::Value;

use crate::biome::{flags, BiomeIndex, BiomeInfo};
use crate::density::{Compiler, Graph, Point, Root, Scratch};
use crate::rng::Xoroshiro;

/// Density above which a point counts as terrain. It must stay above the world-top slide (about
/// -0.08).
const SURFACE_THRESHOLD: f64 = 0.0;
/// Size of a noise cell in blocks: the density is only computed at cell corners.
const CELL_W: i32 = 4;
const CELL_H: i32 = 8;
/// How far above the 2D estimate the search for the surface starts, in blocks. The 3D noise moves
/// the surface by up to roughly this much.
const SEARCH_MARGIN: i32 = 48;
/// Rise per block of run (between neighboring columns) above which grass and snow cannot hold on and
/// the bare rock shows.
const STEEP_SLOPE: f32 = 1.2;
/// Vanilla cools a biome by this much per block above y 80, and snows below a temperature of 0.15.
const LAPSE_PER_BLOCK: f32 = 0.05 / 40.0;

pub const NO_TERRAIN: i16 = i16::MIN;
pub const BRICK_VOXELS: usize = 16 * 16 * 16;

/// What the top of a column is made of. The order is shared with `SeedSource.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Surface {
  Grass = 0,
  Sand = 1,
  RedSand = 2,
  Gravel = 3,
  Snow = 4,
  Stone = 5,
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

/// Voxel ids to write into bricks, set by Java (they come from `BlockClassifier`). Per surface kind
/// the top block and the filler below it, then stone and the water stand-in.
#[derive(Clone, Copy, Debug, Default)]
pub struct Ids {
  pub top: [u16; SURFACES],
  pub filler: [u16; SURFACES],
  pub stone: u16,
  pub water: u16,
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

/// The surface of one level-k section column: 16 x 16 samples, indexed `z * 16 + x`.
pub struct Tile {
  pub top: [i16; 256],
  pub surface: [u8; 256],
  /// Grass color (0xRRGGBB) of the biome of each column.
  pub grass: [u32; 256],
}

/// Layout of a tile's grass colors as texels of the far tint texture (see `TintMap.java`): a texel
/// is `max(8, 2 << level)` blocks wide, so the wrapping texture always spans a whole level. Returns
/// `(texels per side, columns per texel side)`.
pub fn far_tint_layout(level: u32) -> (usize, usize) {
  let cell = 8usize.max(2usize << level);
  let cols = cell >> level;
  (16 / cols, cols)
}

impl Tile {
  pub fn empty() -> Tile {
    Tile { top: [NO_TERRAIN; 256], surface: [0; 256], grass: [0; 256] }
  }

  /// Writes the tile as RGBA texels of the far tint texture, row major, and returns the texels per
  /// side. A texel takes the color of the first grass column of its group (so a coast does not tint
  /// its grass with the sea's biome), else of the group's middle column. Texels with no terrain
  /// have alpha 0.
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
        let rgb = self.grass[pick];
        let o = (tz * side + tx) * 4;
        out[o] = (rgb >> 16) as u8;
        out[o + 1] = (rgb >> 8) as u8;
        out[o + 2] = rgb as u8;
        out[o + 3] = if self.top[pick] == NO_TERRAIN { 0 } else { 0xFF };
      }
    }
    side
  }

  /// Writes the 4096 voxels of section row `sy` of this column into `out` and returns how many are
  /// not air. Always writes the whole brick, since callers may layer other data over it.
  pub fn fill_brick(&self, level: u32, sy: i32, t: &TerrainParams, ids: &Ids, out: &mut [u16; BRICK_VOXELS]) -> u32 {
    let size = 1i32 << level;
    let half = size >> 1;
    let y0 = (sy as i64) << (level + 4);
    let filler_depth = 4.max(2 * size);

    let mut solid = 0;
    for z in 0..16 {
      for x in 0..16 {
        let col = z * 16 + x;
        let top = self.top[col];
        let surface = Surface::from_u8(self.surface[col]) as usize;
        let (top_id, filler_id) = (ids.top[surface], ids.filler[surface]);

        for y in 0..16 {
          let by = y0 + (y as i64) * size as i64 + half as i64;
          let mut id = 0;
          if top != NO_TERRAIN && by >= t.min_y as i64 && by < t.max_y as i64 {
            let by = by as i32;
            let top = top as i32;
            if by < top {
              // The one voxel holding the surface is the surface block; a few below it are filler.
              id = if by + size >= top {
                top_id
              } else if top - by <= filler_depth {
                filler_id
              } else {
                ids.stone
              };
            } else if by < t.sea_level {
              id = ids.water;
            }
          }
          if id != 0 {
            solid += 1;
          }
          out[(y * 16 + z) * 16 + x] = id;
        }
      }
    }
    solid
  }
}

/// The constants of the world's height range, split off so bricks can be built without the graph.
#[derive(Clone, Copy, Debug)]
pub struct TerrainParams {
  pub sea_level: i32,
  pub min_y: i32,
  pub max_y: i32,
}

/// Densities at noise cell corners, by column and cell row, for the columns of one tile (which
/// share corners). `NaN` marks a corner not computed yet.
#[derive(Default)]
struct CornerCache {
  columns: HashMap<(i32, i32), Vec<f64>>,
}

pub struct Terrain {
  pub params: TerrainParams,
  graph: Graph,
  /// Cheap 2D estimate of the surface shape (no 3D noise), to know where to look.
  estimate: Root,
  /// The same without the jagged peaks, only kept for tests.
  flat: Root,
  /// The surface shape with the 3D noise, when the world has one.
  sloped_cheese: Option<Root>,
  temperature: Root,
  humidity: Root,
  continentalness: Root,
  erosion: Root,
  depth: Root,
  weirdness: Root,
  biomes: BiomeIndex,
}

impl Terrain {
  /// Builds the generator for a world. `config` is the JSON written by `TerrainExport.java`.
  pub fn from_config(seed: i64, config: &[u8]) -> Result<Terrain, String> {
    let cfg: Value = serde_json::from_slice(config).map_err(|e| format!("bad terrain config: {e}"))?;
    let obj = cfg.as_object().ok_or("terrain config is not an object")?;

    let int = |key: &str| -> Result<i32, String> {
      obj.get(key).and_then(Value::as_i64).map(|v| v as i32).ok_or_else(|| format!("config is missing {key}"))
    };
    let params = TerrainParams { sea_level: int("sea_level")?, min_y: int("min_y")?, max_y: int("min_y")? + int("height")? };

    let functions = obj.get("functions").and_then(Value::as_object).ok_or("config is missing functions")?;
    let noises = obj.get("noises").and_then(Value::as_object).ok_or("config is missing noises")?;
    let roots = obj.get("roots").and_then(Value::as_object).ok_or("config is missing roots")?;

    // The same factory the game's RandomState derives all its noises from.
    let factory = Xoroshiro::from_seed(seed).fork_positional();
    let mut compiler = Compiler::new(functions, noises, factory);
    let mut root = |name: &str| -> Result<Root, String> {
      let v = roots.get(name).ok_or_else(|| format!("config is missing root {name}"))?;
      compiler.root(v).map_err(|e| format!("{name}: {e}"))
    };

    // Older exports only have the smooth density; the jagged one is the better estimate.
    let flat = root("initial_density_without_jaggedness")?;
    let estimate = if roots.contains_key("initial_density") { root("initial_density")? } else { flat };
    let sloped_cheese = if roots.contains_key("sloped_cheese") { Some(root("sloped_cheese")?) } else { None };
    let temperature = root("temperature")?;
    let humidity = root("vegetation")?;
    let continentalness = root("continents")?;
    let erosion = root("erosion")?;
    let depth = root("depth")?;
    let weirdness = root("ridges")?;
    let graph = compiler.finish();

    let biomes = BiomeIndex::from_json(obj.get("biomes").ok_or("config is missing biomes")?)?;

    Ok(Terrain {
      params,
      graph,
      estimate,
      flat,
      sloped_cheese,
      temperature,
      humidity,
      continentalness,
      erosion,
      depth,
      weirdness,
      biomes,
    })
  }

  pub fn scratch(&self) -> Scratch {
    self.graph.scratch()
  }

  /// The 2D estimate of the density.
  pub fn density(&self, s: &mut Scratch, x: i32, y: i32, z: i32) -> f64 {
    self.graph.eval(self.estimate, Point { x, y, z }, s)
  }

  /// The six climate values at a block position, quantized like `Climate.TargetPoint`.
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

  /// The biome at a block position, as `MultiNoiseBiomeSource.getNoiseBiome` would pick it.
  pub fn biome_at(&self, s: &mut Scratch, x: i32, y: i32, z: i32) -> BiomeInfo {
    // Biomes are stored per 4x4x4 blocks and sampled at the cell's corner.
    self.biomes.nearest(self.climate(s, (x >> 2) << 2, (y >> 2) << 2, (z >> 2) << 2))
  }

  /// First air block above the 2D estimate of the terrain at a column. Scans down from the build
  /// limit in steps (the last one always lands on the world floor, so a step larger than the
  /// terrain's depth cannot skip it), then bisects the step that crossed the threshold (the
  /// density only falls with height inside one step, so that is safe).
  fn estimate_top(&self, s: &mut Scratch, x: i32, z: i32) -> Option<i32> {
    let TerrainParams { min_y, max_y, .. } = self.params;

    // Terrain at the very top means this is not a world shaped like the overworld.
    if self.density(s, x, max_y - 1, z) > SURFACE_THRESHOLD {
      return None;
    }

    let mut above = max_y; // known to be at or below the threshold, or the build limit
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

  /// The density the game generates terrain from, at a noise cell corner: the surface shape under
  /// the overworld's top and bottom slides (`NoiseRouterData.slideOverworld`).
  fn corner_density(&self, s: &mut Scratch, x: i32, y: i32, z: i32) -> f64 {
    let TerrainParams { min_y, max_y, .. } = self.params;
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

  /// Height of the first air block above the terrain at a column, as the game generates it: the
  /// density interpolated between the corners of the noise cell around the column, so this agrees
  /// with `getBaseHeight` apart from caves. `None` if there is no terrain.
  pub fn surface_top(&self, s: &mut Scratch, x: i32, z: i32) -> Option<i32> {
    self.surface_top_cached(s, &mut CornerCache::default(), x, z)
  }

  fn surface_top_cached(&self, s: &mut Scratch, cache: &mut CornerCache, x: i32, z: i32) -> Option<i32> {
    let TerrainParams { min_y, max_y, .. } = self.params;
    let estimate = self.estimate_top(s, x, z)?;

    // Cell rows are aligned to the world, not to the build limit.
    let base = min_y.div_euclid(CELL_H) * CELL_H;
    let rows = (max_y - base + CELL_H - 1) / CELL_H; // the topmost row sits at or above the limit

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

    // Start a margin above the estimate, higher if the density is still positive there.
    let mut top = (((estimate + SEARCH_MARGIN) - base + CELL_H - 1) / CELL_H).clamp(1, rows);
    while density(top) > SURFACE_THRESHOLD {
      if top >= rows {
        return None;
      }
      top = (top + 2).min(rows);
    }

    let mut upper = density(top);
    for row in (0..top).rev() {
      let lower = density(row);
      if lower > SURFACE_THRESHOLD {
        // Density is linear in y inside the cell: find the highest block still above the threshold.
        for k in (0..CELL_H).rev() {
          let t = k as f64 / CELL_H as f64;
          if lower + t * (upper - lower) > SURFACE_THRESHOLD {
            return Some((base + row * CELL_H + k + 1).clamp(min_y, max_y));
          }
        }
        return Some(base + row * CELL_H + 1);
      }
      upper = lower;
    }
    None
  }

  /// Density at one cell corner, memoized for the tile being sampled.
  fn corner(&self, s: &mut Scratch, cache: &mut CornerCache, x: i32, z: i32, row: i32, y: i32, rows: i32) -> f64 {
    let column = cache.columns.entry((x, z)).or_insert_with(|| vec![f64::NAN; rows as usize + 1]);
    let v = column[row as usize];
    if !v.is_nan() {
      return v;
    }
    let v = self.corner_density(s, x, y, z);
    column[row as usize] = v;
    v
  }

  /// Evaluates one of the exported root functions by its config name, for tests and debugging.
  pub fn eval_root(&self, name: &str, s: &mut Scratch, x: i32, y: i32, z: i32) -> Option<f64> {
    let root = match name {
      "initial_density_without_jaggedness" => self.flat,
      "initial_density" => self.estimate,
      "sloped_cheese" => self.sloped_cheese?,
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

  /// Index and fitness of the nearest biome point to a quantized climate target.
  pub fn nearest_biome(&self, target: [i64; 6]) -> (usize, i64) {
    self.biomes.nearest_index(target)
  }

  /// Samples the surface of the 16 x 16 columns of a level-`level` section column.
  pub fn sample_tile(&self, s: &mut Scratch, level: u32, sx: i32, sz: i32) -> Tile {
    let mut tile = Tile::empty();
    let mut cache = CornerCache::default();
    let size = 1i64 << level;
    let half = size >> 1;
    let x0 = (sx as i64) << (level + 4);
    let z0 = (sz as i64) << (level + 4);
    let TerrainParams { min_y, max_y, sea_level } = self.params;

    for z in 0..16usize {
      for x in 0..16usize {
        let bx = (x0 + x as i64 * size + half) as i32;
        let bz = (z0 + z as i64 * size + half) as i32;
        let Some(top) = self.surface_top_cached(s, &mut cache, bx, bz) else { continue };

        let by = top.clamp(min_y, max_y - 1);
        let biome = self.biome_at(s, bx, by, bz);

        let col = z * 16 + x;
        tile.top[col] = top as i16;
        tile.surface[col] = surface_of(biome, top, sea_level) as u8;
        tile.grass[col] = biome.grass;
      }
    }
    tile.expose_cliffs(level, sea_level);
    tile
  }
}

impl Tile {
  /// Turns grass and snow on steep ground into stone, judged from the neighboring columns of the
  /// tile (the edge columns only see the neighbors they have). A voxel is `2^level` blocks wide, so
  /// the same terrain reads as steeper the coarser the level.
  fn expose_cliffs(&mut self, level: u32, sea_level: i32) {
    let run = (1i32 << level) as f32;
    let mut out = self.surface;
    for z in 0..16usize {
      for x in 0..16usize {
        let col = z * 16 + x;
        let top = self.top[col];
        let kind = self.surface[col];
        if top == NO_TERRAIN || (top as i32) <= sea_level {
          continue;
        }
        if kind != Surface::Grass as u8 && kind != Surface::Snow as u8 {
          continue;
        }

        let mut rise = 0i32;
        for (dx, dz) in [(-1i32, 0i32), (1, 0), (0, -1), (0, 1)] {
          let (nx, nz) = (x as i32 + dx, z as i32 + dz);
          if !(0..16).contains(&nx) || !(0..16).contains(&nz) {
            continue;
          }
          let n = self.top[nz as usize * 16 + nx as usize];
          if n != NO_TERRAIN {
            rise = rise.max((n as i32 - top as i32).abs());
          }
        }
        if rise as f32 / run >= STEEP_SLOPE {
          out[col] = Surface::Stone as u8;
        }
      }
    }
    self.surface = out;
  }
}

/// Picks what the top of a column is made of from its biome and height.
pub fn surface_of(biome: BiomeInfo, top: i32, sea_level: i32) -> Surface {
  let f = biome.flags;
  if f & (flags::OCEAN | flags::RIVER) != 0 || top <= sea_level {
    return if biome.temperature < 0.2 { Surface::Gravel } else { Surface::Sand };
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
  if top >= 200 || (top >= 110 && f & flags::MOUNTAIN != 0) {
    return Surface::Stone;
  }
  Surface::Grass
}
