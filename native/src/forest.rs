//! Trees on predicted terrain: runs the decoration port (`trees`) over the
//! terrain a seed generates, one chunk at a time, and turns what it places into
//! what tiles carry: voxels for the brick levels, canopy heights for the far
//! heightfield.
//!
//! Every chunk is decorated on its own, as if its neighbors were empty (see the
//! note in `trees`), so a chunk's trees depend on nothing but the seed and can
//! be cached and shared by every tile that reaches over its border.

use std::{
  collections::{BTreeSet, HashMap, VecDeque},
  sync::{Arc, Mutex},
};

use serde_json::Value;

use crate::{
  biome::flags,
  density::Scratch,
  terrain::{surface_of, CornerCache, Surface, Terrain, NO_TERRAIN},
  trees::{
    block::{
      Block, BlockId, DIRT, GRASS_BLOCK, GRAVEL, RED_SAND, SAND, SNOW_BLOCK, STONE, TERRACOTTA,
    },
    world::{Ground, Level},
    Decoration,
  },
};

/// A block a feature placed, relative to the chunk that was decorated.
#[derive(Clone, Copy, Debug)]
pub struct TreeBlock {
  pub dx: i8,
  pub dz: i8,
  pub y:  i16,
  pub id: BlockId,
}

/// A voxel of a tile with a tree block in it: `x` and `z` are columns of the
/// tile, `y` is the voxel row counted from the world's origin.
#[derive(Clone, Copy, Debug)]
pub struct TreeVoxel {
  pub x:  u8,
  pub z:  u8,
  pub y:  i16,
  pub id: BlockId,
}

/// Per column of a heightfield tile: the height of the top of the tree there
/// (`NO_TERRAIN` for none) and its color.
pub struct Canopy {
  pub top: [i16; 256],
  pub rgb: [u32; 256],
}

/// How a block looks from far away.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Look {
  /// Not part of a tree's silhouette.
  None,
  Fixed(u32),
  /// Leaves tinted with the biome's foliage color.
  Foliage,
}

pub struct Forest {
  decoration: Decoration,
  looks:      Vec<Look>,
}

/// Leaf textures are not white, so tinting one with the foliage color gives a
/// darker color than the foliage color itself.
const FOLIAGE_SHADE: f32 = 0.75;

fn look_of(name: &str) -> Look {
  let short = name.strip_prefix("minecraft:").unwrap_or(name);
  if let Some(wood) = short.strip_suffix("_leaves") {
    return match wood {
      "birch" => Look::Fixed(0x80A755),
      "spruce" => Look::Fixed(0x619961),
      "cherry" => Look::Fixed(0xE3A0BD),
      "azalea" => Look::Fixed(0x5A7A2C),
      "flowering_azalea" => Look::Fixed(0x6F7E45),
      _ => Look::Foliage,
    };
  }
  if let Some(wood) = short.strip_suffix("_log").or_else(|| short.strip_suffix("_wood")) {
    let wood = wood.strip_prefix("stripped_").unwrap_or(wood);
    return Look::Fixed(match wood {
      "oak" => 0x6F5836,
      "spruce" => 0x3A2813,
      "birch" => 0xCFCFC6,
      "jungle" => 0x58451F,
      "acacia" => 0x676157,
      "dark_oak" => 0x3C2D17,
      "mangrove" => 0x5B3A38,
      "cherry" => 0x3C2F36,
      _ => 0x6F5836,
    });
  }
  match short {
    "red_mushroom_block" => Look::Fixed(0xC52B26),
    "brown_mushroom_block" => Look::Fixed(0x957250),
    "mushroom_stem" => Look::Fixed(0xD4CDBE),
    _ => Look::None,
  }
}

fn shade(rgb: u32, by: f32) -> u32 {
  let c = |shift: u32| ((((rgb >> shift) & 0xFF) as f32 * by) as u32).min(255) << shift;
  c(16) | c(8) | c(0)
}

impl Forest {
  pub fn from_json(v: &Value) -> Result<Forest, String> {
    let decoration = Decoration::from_json(v)?;
    let mut looks = Vec::new();
    let mut id = 0;
    while let Some(name) = decoration.blocks.name_of(id) {
      looks.push(look_of(name));
      id += 1;
    }
    Ok(Forest { decoration, looks })
  }

  /// The names of the blocks features can place, indexed by block id.
  pub fn block_names(&self) -> Vec<String> {
    let mut out = Vec::new();
    let mut id = 0;
    while let Some(name) = self.decoration.blocks.name_of(id) {
      out.push(name.to_string());
      id += 1;
    }
    out
  }

  fn color(&self, id: BlockId, foliage: u32) -> Option<u32> {
    match self.looks.get(id as usize)? {
      Look::None => None,
      Look::Fixed(rgb) => Some(*rgb),
      Look::Foliage => Some(shade(foliage, FOLIAGE_SHADE)),
    }
  }

  /// The blocks the features place when chunk `(cx, cz)` is decorated alone.
  pub fn decorate(&self, terrain: &Terrain, s: &mut Scratch, cx: i32, cz: i32) -> ChunkTrees {
    let mut ground = SeedGround::new(terrain, s);

    // The biomes the chunk's surface is made of decide which features run.
    let mut region = BTreeSet::new();
    for qz in 0..4 {
      for qx in 0..4 {
        region.insert(ground.biome(cx * 16 + qx * 4, 0, cz * 16 + qz * 4));
      }
    }
    if !region.iter().any(|&b| self.decoration.has_trees(b)) {
      return ChunkTrees(Box::new([]));
    }

    let mut level = Level::new(&self.decoration.blocks, &mut ground);
    self.decoration.decorate_chunk(&mut level, terrain.seed(), cx, cz, &region);
    let blocks = level
      .placed()
      .filter(|&(_, _, _, b)| b.id != crate::trees::block::AIR)
      .map(|(x, y, z, b)| TreeBlock {
        dx: (x - cx * 16) as i8,
        dz: (z - cz * 16) as i8,
        y:  y as i16,
        id: b.id,
      })
      .collect();
    ChunkTrees(blocks)
  }

  /// The tree blocks a tile of `level` reaches, as voxels of that level.
  pub fn voxel_trees(
    &self,
    terrain: &Terrain,
    s: &mut Scratch,
    cache: &TreeCache,
    level: u32,
    sx: i32,
    sz: i32,
  ) -> Vec<TreeVoxel> {
    let size = 1i64 << level;
    let (x0, z0) = ((sx as i64) << (level + 4), (sz as i64) << (level + 4));
    let width = 16 * size;

    // (voxel row, z, x) so a sort groups voxels and orders them by row.
    let mut hits: Vec<(i16, u8, u8, BlockId)> = Vec::new();
    self.for_each_block(terrain, s, cache, x0, z0, width, |x, y, z, id| {
      let (vx, vz) = ((x as i64 - x0) >> level, (z as i64 - z0) >> level);
      hits.push(((y >> level) as i16, vz as u8, vx as u8, id));
    });
    hits.sort_unstable();

    let blocks = &self.decoration.blocks;
    let need = ((size * size * size) / 4).max(1) as usize;
    let mut out = Vec::new();
    let mut i = 0;
    while i < hits.len() {
      let mut j = i;
      while j < hits.len()
        && hits[j].0 == hits[i].0
        && hits[j].1 == hits[i].1
        && hits[j].2 == hits[i].2
      {
        j += 1;
      }
      let group = &hits[i..j];

      // A trunk shows at any coarseness; a leaf voxel needs a good share of
      // its volume, or a coarse tree would be a ball of fat leaves.
      let pick = most_common(group.iter().map(|h| h.3).filter(|&id| blocks.is_log(Block::new(id))))
        .or_else(
          || {
            if group.len() >= need {
              most_common(group.iter().map(|h| h.3))
            } else {
              None
            }
          },
        );
      if let Some(id) = pick {
        out.push(TreeVoxel { x: group[0].2, z: group[0].1, y: group[0].0, id });
      }
      i = j;
    }
    out
  }

  /// The canopies a heightfield tile of `level` reaches. None when no tree is
  /// there at all.
  #[allow(clippy::too_many_arguments)]
  pub fn canopy(
    &self,
    terrain: &Terrain,
    s: &mut Scratch,
    cache: &TreeCache,
    level: u32,
    sx: i32,
    sz: i32,
    foliage: &[u32; 256],
  ) -> Option<Box<Canopy>> {
    let size = 1usize << level;
    let (x0, z0) = ((sx as i64) << (level + 4), (sz as i64) << (level + 4));
    let width = 16 * size;

    // Highest tree block per block column of the tile.
    let mut tops = vec![i16::MIN; width * width];
    let mut ids = vec![0 as BlockId; width * width];
    let mut any = false;
    self.for_each_block(terrain, s, cache, x0, z0, width as i64, |x, y, z, id| {
      if self.looks.get(id as usize).copied().unwrap_or(Look::None) == Look::None {
        return;
      }
      let at = (z as i64 - z0) as usize * width + (x as i64 - x0) as usize;
      if y as i16 + 1 > tops[at] {
        tops[at] = y as i16 + 1;
        ids[at] = id;
        any = true;
      }
    });
    if !any {
      return None;
    }

    let mut canopy = Box::new(Canopy { top: [NO_TERRAIN; 256], rgb: [0; 256] });
    let need = (size * size / 4).max(1);
    for tz in 0..16 {
      for tx in 0..16 {
        let mut covered = 0;
        let mut best = (i16::MIN, 0);
        for dz in 0..size {
          for dx in 0..size {
            let at = (tz * size + dz) * width + tx * size + dx;
            if tops[at] == i16::MIN {
              continue;
            }
            covered += 1;
            if tops[at] > best.0 {
              best = (tops[at], ids[at]);
            }
          }
        }
        if covered >= need {
          let col = tz * 16 + tx;
          if let Some(rgb) = self.color(best.1, foliage[col]) {
            canopy.top[col] = best.0;
            canopy.rgb[col] = rgb;
          }
        }
      }
    }
    Some(canopy)
  }

  /// Calls `f(x, y, z, id)` for every tree block inside the square of `width`
  /// blocks at `(x0, z0)`, decorating the chunks around it as needed (trees
  /// reach a chunk over the border of the one they grow in).
  #[allow(clippy::too_many_arguments)]
  fn for_each_block(
    &self,
    terrain: &Terrain,
    s: &mut Scratch,
    cache: &TreeCache,
    x0: i64,
    z0: i64,
    width: i64,
    mut f: impl FnMut(i32, i32, i32, BlockId),
  ) {
    let (cx0, cx1) = (((x0 >> 4) - 1) as i32, (((x0 + width - 1) >> 4) + 1) as i32);
    let (cz0, cz1) = (((z0 >> 4) - 1) as i32, (((z0 + width - 1) >> 4) + 1) as i32);
    for cz in cz0..=cz1 {
      for cx in cx0..=cx1 {
        let chunk = cache.get(cx, cz, || self.decorate(terrain, s, cx, cz));
        for b in chunk.0.iter() {
          let (x, z) = (cx * 16 + b.dx as i32, cz * 16 + b.dz as i32);
          if (x as i64) >= x0
            && (x as i64) < x0 + width
            && (z as i64) >= z0
            && (z as i64) < z0 + width
          {
            f(x, b.y as i32, z, b.id);
          }
        }
      }
    }
  }
}

fn most_common(ids: impl Iterator<Item = BlockId>) -> Option<BlockId> {
  let mut counts: Vec<(BlockId, usize)> = Vec::new();
  for id in ids {
    match counts.iter_mut().find(|c| c.0 == id) {
      Some(c) => c.1 += 1,
      None => counts.push((id, 1)),
    }
  }
  counts.into_iter().max_by_key(|c| (c.1, std::cmp::Reverse(c.0))).map(|c| c.0)
}

/// The tree blocks of one chunk.
pub struct ChunkTrees(pub Box<[TreeBlock]>);

/// Decorated chunks, kept for the tiles that border them. A chunk is needed by
/// every tile around it, which usually come one after the other, so the oldest
/// ones are dropped first.
pub struct TreeCache {
  inner: Mutex<CacheInner>,
  cap:   usize,
}

#[derive(Default)]
struct CacheInner {
  map:   HashMap<(i32, i32), Arc<ChunkTrees>>,
  order: VecDeque<(i32, i32)>,
}

impl TreeCache {
  pub fn new(cap: usize) -> TreeCache { TreeCache { inner: Mutex::default(), cap } }

  pub fn len(&self) -> usize { self.inner.lock().unwrap().map.len() }

  pub fn is_empty(&self) -> bool { self.len() == 0 }

  /// The chunk's trees, computed by `make` (outside the lock) if not kept.
  pub fn get(&self, cx: i32, cz: i32, make: impl FnOnce() -> ChunkTrees) -> Arc<ChunkTrees> {
    if let Some(c) = self.inner.lock().unwrap().map.get(&(cx, cz)) {
      return c.clone();
    }
    let made = Arc::new(make());
    let mut inner = self.inner.lock().unwrap();
    if inner.map.insert((cx, cz), made.clone()).is_none() {
      inner.order.push_back((cx, cz));
      while inner.order.len() > self.cap {
        if let Some(old) = inner.order.pop_front() {
          inner.map.remove(&old);
        }
      }
    }
    made
  }
}

/// The terrain a seed generates, as the decoration sees it. Heights are the
/// terrain's own (computed lazily, a column at a time, and kept).
pub struct SeedGround<'a> {
  terrain: &'a Terrain,
  s:       &'a mut Scratch,
  cache:   CornerCache,
  tops:    HashMap<(i32, i32), Option<i32>>,
  /// Biome index per 4 x 4 block cell.
  biomes:  HashMap<(i32, i32), usize>,
}

impl<'a> SeedGround<'a> {
  pub fn new(terrain: &'a Terrain, s: &'a mut Scratch) -> SeedGround<'a> {
    SeedGround {
      terrain,
      s,
      cache: CornerCache::default(),
      tops: HashMap::new(),
      biomes: HashMap::new(),
    }
  }

  fn surface(&mut self, x: i32, z: i32, top: i32) -> Surface {
    let biome = self.biome(x, 0, z);
    let info = self.terrain.biome_info(biome);
    surface_of(info, top, self.terrain.params.sea_level)
  }
}

impl Ground for SeedGround<'_> {
  fn min_y(&self) -> i32 { self.terrain.params.min_y }

  fn max_y(&self) -> i32 { self.terrain.params.max_y }

  fn sea_level(&self) -> i32 { self.terrain.params.sea_level }

  fn top(&mut self, x: i32, z: i32) -> Option<i32> {
    if let Some(&t) = self.tops.get(&(x, z)) {
      return t;
    }
    let t = self.terrain.surface_top_cached(self.s, &mut self.cache, x, z);
    self.tops.insert((x, z), t);
    t
  }

  fn terrain_block(&mut self, x: i32, y: i32, z: i32) -> BlockId {
    let top = self.top(x, z).unwrap_or(y + 1);
    let depth = top - y; // 1 for the surface block
    let surface = self.surface(x, z, top);
    let filler = depth <= 4;
    match surface {
      Surface::Grass => {
        if depth == 1 {
          GRASS_BLOCK
        } else if filler {
          DIRT
        } else {
          STONE
        }
      }
      // Snow lies on top of grass in most cold biomes; only the bare peaks
      // are snow all the way.
      Surface::Snow => {
        let info = self.terrain.biome_info(self.biome(x, 0, z));
        if info.flags & flags::STEEP_ROCK != 0 {
          if depth == 1 {
            SNOW_BLOCK
          } else {
            STONE
          }
        } else if depth == 1 {
          GRASS_BLOCK
        } else if filler {
          DIRT
        } else {
          STONE
        }
      }
      Surface::Sand => {
        if filler {
          SAND
        } else {
          STONE
        }
      }
      Surface::RedSand => {
        if depth == 1 {
          RED_SAND
        } else if filler {
          TERRACOTTA
        } else {
          STONE
        }
      }
      Surface::Gravel => {
        if filler {
          GRAVEL
        } else {
          STONE
        }
      }
      Surface::Stone => STONE,
    }
  }

  fn biome(&mut self, x: i32, _y: i32, z: i32) -> usize {
    let cell = (x >> 2, z >> 2);
    if let Some(&b) = self.biomes.get(&cell) {
      return b;
    }
    // Sampled at the surface of the cell's corner, like the game's biome
    // storage.
    let (bx, bz) = (cell.0 << 2, cell.1 << 2);
    let p = self.terrain.params;
    let by = self.top(bx, bz).unwrap_or(p.sea_level).clamp(p.min_y, p.max_y - 1);
    let b = self.terrain.biome_index_at(self.s, bx, by, bz);
    self.biomes.insert(cell, b);
    b
  }
}
