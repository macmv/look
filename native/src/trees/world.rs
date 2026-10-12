//! The world the decoration runs against: predicted terrain underneath, and
//! whatever the features have placed on top of it so far. Only what vanilla's
//! placement code reads is modelled: block states (for the free space and
//! ground checks) and the two heightmaps the tree features use.

use std::collections::{BTreeMap, HashMap};

use super::block::{Block, BlockId, Blocks, AIR, WATER};

/// The terrain below the features. Heights are what the generator produced
/// before any feature ran.
pub trait Ground {
  fn min_y(&self) -> i32;
  fn max_y(&self) -> i32;
  fn sea_level(&self) -> i32;

  /// The first air block above the terrain at a column (the ocean floor
  /// heightmap's value), or `None` where there is no terrain.
  fn top(&mut self, x: i32, z: i32) -> Option<i32>;

  /// The terrain block at `y`, which is below `top(x, z)`.
  fn terrain_block(&mut self, x: i32, y: i32, z: i32) -> BlockId;

  /// The biome (an index of the biome table) at a block position, with
  /// vanilla's smoothing.
  fn biome(&mut self, x: i32, y: i32, z: i32) -> usize;
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Heightmap {
  /// The highest block that blocks motion, fluids excluded.
  OceanFloor,
  /// The highest block that is not air.
  WorldSurface,
  /// The highest block that blocks motion, or is a fluid.
  MotionBlocking,
  /// `MotionBlocking`, but leaves do not count.
  MotionBlockingNoLeaves,
  /// The terrain's own ocean floor and surface, which features do not change.
  OceanFloorWg,
  WorldSurfaceWg,
}

/// A level that features write into. Writes outside the chunks around `center`
/// are dropped, like `WorldGenRegion.ensureCanWrite` does (a feature may reach
/// one chunk past the one it decorates).
pub struct Level<'a> {
  pub blocks: &'a Blocks,
  ground:     &'a mut dyn Ground,
  overlay:    HashMap<(i32, i32), BTreeMap<i32, Block>>,
  center:     (i32, i32),
  radius:     i32,
}

impl<'a> Level<'a> {
  pub fn new(blocks: &'a Blocks, ground: &'a mut dyn Ground) -> Level<'a> {
    Level { blocks, ground, overlay: HashMap::new(), center: (0, 0), radius: 1 }
  }

  /// Sets the chunk being decorated.
  pub fn set_center(&mut self, cx: i32, cz: i32) { self.center = (cx, cz); }

  pub fn min_y(&self) -> i32 { self.ground.min_y() }

  pub fn max_y(&self) -> i32 { self.ground.max_y() }

  pub fn sea_level(&self) -> i32 { self.ground.sea_level() }

  pub fn biome(&mut self, x: i32, y: i32, z: i32) -> usize { self.ground.biome(x, y, z) }

  fn base_block(&mut self, x: i32, y: i32, z: i32) -> Block {
    if y < self.ground.min_y() || y >= self.ground.max_y() {
      return Block::new(AIR);
    }
    match self.ground.top(x, z) {
      Some(top) if y < top => Block::new(self.ground.terrain_block(x, y, z)),
      _ if y < self.ground.sea_level() => Block::new(WATER),
      _ => Block::new(AIR),
    }
  }

  pub fn get(&mut self, x: i32, y: i32, z: i32) -> Block {
    if let Some(b) = self.overlay.get(&(x, z)).and_then(|col| col.get(&y)) {
      return *b;
    }
    self.base_block(x, y, z)
  }

  /// Whether a write at this column would land
  /// (`WorldGenRegion.ensureCanWrite`).
  pub fn can_write(&self, x: i32, z: i32) -> bool {
    (x.div_euclid(16) - self.center.0).abs() <= self.radius
      && (z.div_euclid(16) - self.center.1).abs() <= self.radius
  }

  pub fn set(&mut self, x: i32, y: i32, z: i32, b: Block) -> bool {
    if !self.can_write(x, z) || y < self.ground.min_y() || y >= self.ground.max_y() {
      return false;
    }
    self.overlay.entry((x, z)).or_default().insert(y, b);
    true
  }

  /// `WorldGenLevel.getHeight`: the first free block above the highest block
  /// the heightmap counts, or the world's bottom when there is none.
  pub fn height(&mut self, kind: Heightmap, x: i32, z: i32) -> i32 {
    let min_y = self.ground.min_y();
    let base = match self.ground.top(x, z) {
      None => min_y,
      Some(top) => match kind {
        Heightmap::OceanFloor | Heightmap::OceanFloorWg => top,
        _ => top.max(self.ground.sea_level()),
      },
    };
    if matches!(kind, Heightmap::OceanFloorWg | Heightmap::WorldSurfaceWg) {
      return base;
    }

    let mut best = base;
    if let Some(col) = self.overlay.get(&(x, z)) {
      for (&y, &b) in col.iter().rev() {
        if y + 1 <= best {
          break;
        }
        let counts = match kind {
          Heightmap::OceanFloor => self.blocks.blocks_motion(b),
          Heightmap::WorldSurface => !self.blocks.is_air(b),
          Heightmap::MotionBlocking => self.blocks.blocks_motion(b) || self.blocks.is_fluid(b),
          Heightmap::MotionBlockingNoLeaves => {
            (self.blocks.blocks_motion(b) || self.blocks.is_fluid(b)) && !self.blocks.is_leaves(b)
          }
          Heightmap::OceanFloorWg | Heightmap::WorldSurfaceWg => unreachable!(),
        };
        if counts {
          best = y + 1;
          break;
        }
      }
    }
    best
  }

  /// Every block the features have placed, in no particular order.
  pub fn placed(&self) -> impl Iterator<Item = (i32, i32, i32, Block)> + '_ {
    self.overlay.iter().flat_map(|(&(x, z), col)| col.iter().map(move |(&y, &b)| (x, y, z, b)))
  }
}
