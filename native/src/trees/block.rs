//! The blocks tree decoration deals in: a name from the exported configs plus a
//! small state, and the handful of properties of a block that vanilla's
//! placement code looks at (the `dirt`, `logs` and `replaceable_by_trees` tags,
//! whether it blocks motion, whether it is opaque).

use std::collections::HashMap;

pub type BlockId = u16;

/// A block: which one, and the state bits that matter for how it looks. For
/// logs the axis (0 y, 1 x, 2 z); for a vine the faces (see `vine`); for cocoa
/// the age in bits 2.. and the facing in bits 0..1; for mushroom blocks the six
/// faces (`mushroom`); 0 otherwise.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub struct Block {
  pub id:    BlockId,
  pub state: u8,
}

impl Block {
  pub const fn new(id: BlockId) -> Block { Block { id, state: 0 } }

  pub const fn with(id: BlockId, state: u8) -> Block { Block { id, state } }
}

pub const AIR: BlockId = 0;
pub const WATER: BlockId = 1;
pub const GRASS_BLOCK: BlockId = 2;
pub const DIRT: BlockId = 3;
pub const PODZOL: BlockId = 4;
pub const STONE: BlockId = 5;
pub const SAND: BlockId = 6;
pub const RED_SAND: BlockId = 7;
pub const GRAVEL: BlockId = 8;
pub const TERRACOTTA: BlockId = 9;
pub const SNOW_BLOCK: BlockId = 10;
pub const VINE: BlockId = 11;
pub const COCOA: BlockId = 12;
pub const BEE_NEST: BlockId = 13;

const BUILTIN: [&str; 14] = [
  "minecraft:air",
  "minecraft:water",
  "minecraft:grass_block",
  "minecraft:dirt",
  "minecraft:podzol",
  "minecraft:stone",
  "minecraft:sand",
  "minecraft:red_sand",
  "minecraft:gravel",
  "minecraft:terracotta",
  "minecraft:snow_block",
  "minecraft:vine",
  "minecraft:cocoa",
  "minecraft:bee_nest",
];

/// Vine faces, as state bits.
pub mod vine {
  pub const NORTH: u8 = 1;
  pub const EAST: u8 = 2;
  pub const SOUTH: u8 = 4;
  pub const WEST: u8 = 8;
  pub const UP: u8 = 16;
}

/// Mushroom block faces, as state bits.
pub mod mushroom {
  pub const NORTH: u8 = 1;
  pub const EAST: u8 = 2;
  pub const SOUTH: u8 = 4;
  pub const WEST: u8 = 8;
  pub const UP: u8 = 16;
  pub const DOWN: u8 = 32;
}

mod class {
  pub const AIR: u16 = 1;
  pub const FLUID: u16 = 2;
  /// The `dirt` block tag.
  pub const DIRT: u16 = 4;
  pub const GRASS_BLOCK: u16 = 8;
  pub const MYCELIUM: u16 = 16;
  pub const LOG: u16 = 32;
  pub const LEAVES: u16 = 64;
  /// `blocksMotion`: what the ocean floor heightmap counts.
  pub const SOLID: u16 = 128;
  /// `isSolidRender`: a full, light blocking cube.
  pub const OPAQUE: u16 = 256;
  pub const VINE: u16 = 512;
  /// The `mushroom_grow_block` tag.
  pub const MUSHROOM_GROW: u16 = 1024;
  /// Part of the `replaceable_by_trees` tag besides leaves.
  pub const REPLACEABLE: u16 = 2048;
}

/// The names the configs mention, numbered, with the classes of each.
pub struct Blocks {
  names:   Vec<String>,
  classes: Vec<u16>,
  index:   HashMap<String, BlockId>,
}

impl Blocks {
  pub fn new() -> Blocks {
    let mut b = Blocks { names: Vec::new(), classes: Vec::new(), index: HashMap::new() };
    for name in BUILTIN {
      b.intern(name);
    }
    b
  }

  pub fn intern(&mut self, name: &str) -> BlockId {
    if let Some(&id) = self.index.get(name) {
      return id;
    }
    let id = self.names.len() as BlockId;
    self.names.push(name.to_string());
    self.classes.push(classify(name));
    self.index.insert(name.to_string(), id);
    id
  }

  pub fn name(&self, id: BlockId) -> &str { &self.names[id as usize] }

  pub fn lookup(&self, name: &str) -> Option<BlockId> { self.index.get(name).copied() }

  fn has(&self, b: Block, c: u16) -> bool { self.classes[b.id as usize] & c != 0 }

  pub fn is_air(&self, b: Block) -> bool { self.has(b, class::AIR) }

  pub fn is_fluid(&self, b: Block) -> bool { self.has(b, class::FLUID) }

  /// The `dirt` block tag (`Feature.isDirt`).
  pub fn is_dirt(&self, b: Block) -> bool { self.has(b, class::DIRT) }

  pub fn is_grass_block(&self, b: Block) -> bool { self.has(b, class::GRASS_BLOCK) }

  pub fn is_mycelium(&self, b: Block) -> bool { self.has(b, class::MYCELIUM) }

  pub fn is_log(&self, b: Block) -> bool { self.has(b, class::LOG) }

  pub fn is_leaves(&self, b: Block) -> bool { self.has(b, class::LEAVES) }

  /// The `jungle_logs` tag, which cocoa grows on.
  pub fn is_jungle_log(&self, b: Block) -> bool {
    self.has(b, class::LOG) && self.name(b.id).contains("jungle_")
  }

  pub fn is_vine(&self, b: Block) -> bool { self.has(b, class::VINE) }

  pub fn blocks_motion(&self, b: Block) -> bool { self.has(b, class::SOLID) }

  pub fn is_solid_render(&self, b: Block) -> bool { self.has(b, class::OPAQUE) }

  pub fn is_mushroom_grow(&self, b: Block) -> bool { self.has(b, class::MUSHROOM_GROW) }

  /// `TreeFeature.validTreePos`: air, or in the `replaceable_by_trees` tag.
  pub fn valid_tree_pos(&self, b: Block) -> bool {
    self.has(b, class::AIR | class::REPLACEABLE | class::LEAVES | class::FLUID)
  }

  /// `TreeFeature.isAirOrLeaves`.
  pub fn is_air_or_leaves(&self, b: Block) -> bool { self.has(b, class::AIR | class::LEAVES) }
}

impl Default for Blocks {
  fn default() -> Self { Self::new() }
}

fn classify(name: &str) -> u16 {
  use class::*;
  let short = name.strip_prefix("minecraft:").unwrap_or(name);
  match short {
    "air" | "cave_air" | "void_air" => return AIR,
    // The only fluid in the replaceable_by_trees tag; lava is neither.
    "water" => return FLUID | REPLACEABLE,
    "vine" => return VINE | REPLACEABLE,
    "cocoa" => return 0,
    "grass_block" => return DIRT | GRASS_BLOCK | SOLID | OPAQUE,
    "mycelium" => return DIRT | MYCELIUM | SOLID | OPAQUE | MUSHROOM_GROW,
    "podzol" => return DIRT | SOLID | OPAQUE | MUSHROOM_GROW,
    "dirt" | "coarse_dirt" | "rooted_dirt" | "moss_block" | "mud" | "muddy_mangrove_roots" => {
      return DIRT | SOLID | OPAQUE
    }
    "crimson_nylium" | "warped_nylium" => return SOLID | OPAQUE | MUSHROOM_GROW,
    "short_grass" | "fern" | "dead_bush" | "glow_lichen" | "tall_grass" | "large_fern"
    | "sunflower" | "lilac" | "rose_bush" | "peony" | "hanging_roots" | "pitcher_plant"
    | "seagrass" | "tall_seagrass" | "snow" | "moss_carpet" => return REPLACEABLE,
    _ => {}
  }
  if short.ends_with("_leaves") {
    // Leaves block motion but are not opaque (they are `noOcclusion`).
    return LEAVES | SOLID | REPLACEABLE;
  }
  if short.ends_with("_log") || short.ends_with("_wood") {
    return LOG | SOLID | OPAQUE;
  }
  if short.ends_with("_sapling") || short.ends_with("_propagule") {
    return 0;
  }
  SOLID | OPAQUE
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn builtin_ids_match_names() {
    let b = Blocks::new();
    assert_eq!(b.name(DIRT), "minecraft:dirt");
    assert_eq!(b.name(BEE_NEST), "minecraft:bee_nest");
    assert_eq!(b.lookup("minecraft:vine"), Some(VINE));
  }

  #[test]
  fn tree_tags() {
    let mut b = Blocks::new();
    let oak = b.intern("minecraft:oak_leaves");
    let log = b.intern("minecraft:birch_log");
    assert!(b.valid_tree_pos(Block::new(AIR)));
    assert!(b.valid_tree_pos(Block::new(oak)));
    assert!(b.valid_tree_pos(Block::new(WATER)));
    assert!(b.valid_tree_pos(Block::new(VINE)));
    assert!(!b.valid_tree_pos(Block::new(log)));
    assert!(!b.valid_tree_pos(Block::new(STONE)));
    assert!(
      b.is_dirt(Block::new(GRASS_BLOCK))
        && b.is_dirt(Block::new(PODZOL))
        && !b.is_dirt(Block::new(SAND))
    );
    assert!(b.blocks_motion(Block::new(oak)) && !b.is_solid_render(Block::new(oak)));
  }
}
