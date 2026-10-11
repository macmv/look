//! Predicted terrain from the world seed: where the surface is and what it is made of, sampled one
//! column per voxel and expanded into bricks on demand.
//!
//! The surface height comes from the noise router's `initial_density_without_jaggedness`, the
//! function the game itself uses to estimate the surface before a chunk exists: a smooth 2D shape
//! (continents, erosion, mountains, rivers) plus a vertical gradient, so the surface is the highest
//! point where it exceeds a threshold. When the config has an `initial_density` root it also includes
//! the jagged peaks term (see `TerrainExport.java`), otherwise it is the smooth shape only. It does
//! not know about caves, overhangs, surface rules, trees or structures. A block is picked from the
//! biome at the surface.
//!
//! Calibrated against `NoiseBasedChunkGenerator.getBaseHeight` on two seeds: with a threshold of 0
//! and +7 blocks the surface is within about 3.5 blocks of vanilla's on average (rms 6). The worst
//! columns are jagged peaks, up to ~50 blocks off.

use serde_json::Value;

use crate::biome::{flags, BiomeIndex, BiomeInfo};
use crate::density::{Compiler, Graph, Point, Root, Scratch};
use crate::rng::Xoroshiro;

/// Density above which a point counts as terrain. Vanilla's preliminary surface uses 0.390625, but
/// 0 sits closer to the real boundary. It must stay above the world-top slide (about -0.08).
const SURFACE_THRESHOLD: f64 = 0.0;
/// Blocks added to the found surface. Without the 3D noise and jaggedness terms the estimate is
/// consistently this much lower than the real terrain.
const SURFACE_BIAS: i32 = 7;
/// Smallest vertical step of the scan for the surface. Larger voxels scan with their own size.
const MIN_STEP: i32 = 16;

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
}

impl Tile {
  pub fn empty() -> Tile {
    Tile { top: [NO_TERRAIN; 256], surface: [0; 256] }
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

pub struct Terrain {
  pub params: TerrainParams,
  graph: Graph,
  initial_density: Root,
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
    let initial_density =
      if roots.contains_key("initial_density") { root("initial_density")? } else { root("initial_density_without_jaggedness")? };
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
      initial_density,
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

  pub fn density(&self, s: &mut Scratch, x: i32, y: i32, z: i32) -> f64 {
    self.graph.eval(self.initial_density, Point { x, y, z }, s)
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

  /// Height of the first air block above the terrain at a column: blocks below it are solid.
  /// Scans down from the build limit in steps (the last one always lands on the world floor, so a
  /// step larger than the terrain's depth cannot skip it), then bisects the step that crossed the
  /// threshold (the density only falls with height inside one step, so that is safe).
  pub fn surface_top(&self, s: &mut Scratch, x: i32, z: i32, step: i32) -> Option<i32> {
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
        return Some((solid + 1 + SURFACE_BIAS).min(max_y));
      }
      if y <= min_y {
        return None;
      }
      above = y;
      y = (y - step).max(min_y);
    }
  }

  /// Evaluates one of the exported root functions by its config name, for tests and debugging.
  pub fn eval_root(&self, name: &str, s: &mut Scratch, x: i32, y: i32, z: i32) -> Option<f64> {
    let root = match name {
      "initial_density_without_jaggedness" => self.initial_density,
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
    let size = 1i64 << level;
    let half = size >> 1;
    let step = MIN_STEP.max(size as i32);
    let x0 = (sx as i64) << (level + 4);
    let z0 = (sz as i64) << (level + 4);
    let TerrainParams { min_y, max_y, sea_level } = self.params;

    for z in 0..16usize {
      for x in 0..16usize {
        let bx = (x0 + x as i64 * size + half) as i32;
        let bz = (z0 + z as i64 * size + half) as i32;
        let Some(top) = self.surface_top(s, bx, bz, step) else { continue };

        let by = top.clamp(min_y, max_y - 1);
        let biome = self.biome_at(s, bx, by, bz);

        let col = z * 16 + x;
        tile.top[col] = top as i16;
        tile.surface[col] = surface_of(biome, top, sea_level) as u8;
      }
    }
    tile
  }
}

/// Picks what the top of a column is made of from its biome and height.
pub fn surface_of(biome: BiomeInfo, top: i32, sea_level: i32) -> Surface {
  let f = biome.flags;
  if f & (flags::OCEAN | flags::RIVER) != 0 || top <= sea_level {
    return if biome.temperature < 0.2 { Surface::Gravel } else { Surface::Sand };
  }
  if biome.temperature <= 0.15 {
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
