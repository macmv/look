//! `PlacedFeature` and the features that pick other features: runs a feature's
//! placement modifiers over an origin the way vanilla's lazy streams do, one
//! position all the way through before the next starts, so the random source is
//! drawn from in the same order.

use super::{
  block::{mushroom, Block, WATER},
  config::{Feature, IntProvider, Modifier, MushroomConfig, PlacedFeature, Plant, Predicate},
  tree::{place_tree, P},
  world::{Heightmap, Level},
};
use crate::{noise::biome_info, rng::WorldgenRandom};

/// What a `Biome` placement modifier asks: whether the feature is one of the
/// biome's at a spot.
pub type BiomeCheck<'a> = &'a dyn Fn(usize) -> bool;

/// `PlacedFeature.place` / `placeWithBiomeCheck` (`biome` is the check for the
/// latter).
pub fn place(
  level: &mut Level,
  rng: &mut WorldgenRandom,
  feature: &PlacedFeature,
  origin: P,
  biome: Option<BiomeCheck>,
) -> bool {
  run(level, rng, feature, 0, origin, biome)
}

fn run(
  level: &mut Level,
  rng: &mut WorldgenRandom,
  pf: &PlacedFeature,
  i: usize,
  pos: P,
  biome: Option<BiomeCheck>,
) -> bool {
  let Some(modifier) = pf.modifiers.get(i) else {
    return place_feature(level, rng, &pf.feature, pos);
  };

  match modifier {
    Modifier::Count(count) => {
      let n = count.sample(rng);
      let mut any = false;
      for _ in 0..n {
        any |= run(level, rng, pf, i + 1, pos, biome);
      }
      any
    }
    Modifier::InSquare => {
      let x = rng.next_int_bound(16) + pos.0;
      let z = rng.next_int_bound(16) + pos.2;
      run(level, rng, pf, i + 1, (x, pos.1, z), biome)
    }
    Modifier::SurfaceWaterDepth(max) => {
      let floor = level.height(Heightmap::OceanFloor, pos.0, pos.2);
      let surface = level.height(Heightmap::WorldSurface, pos.0, pos.2);
      surface - floor <= *max && run(level, rng, pf, i + 1, pos, biome)
    }
    Modifier::Heightmap(kind) => {
      let y = level.height(*kind, pos.0, pos.2);
      y > level.min_y() && run(level, rng, pf, i + 1, (pos.0, y, pos.2), biome)
    }
    Modifier::Biome => {
      let here = level.biome(pos.0, pos.1, pos.2);
      biome.map_or(true, |check| check(here)) && run(level, rng, pf, i + 1, pos, biome)
    }
    Modifier::Filter(predicate) => {
      holds(level, predicate, pos) && run(level, rng, pf, i + 1, pos, biome)
    }
    Modifier::NoiseThresholdCount { level: threshold, below, above } => {
      let noise = biome_info(pos.0 as f64 / 200.0, pos.2 as f64 / 200.0);
      let n = if noise < *threshold { *below } else { *above };
      let mut any = false;
      for _ in 0..n {
        any |= run(level, rng, pf, i + 1, pos, biome);
      }
      any
    }
    Modifier::NoiseBasedCount { ratio, factor, offset } => {
      let noise = biome_info(pos.0 as f64 / factor, pos.2 as f64 / factor);
      let n = ((noise + offset) * *ratio as f64).ceil() as i32;
      let mut any = false;
      for _ in 0..n {
        any |= run(level, rng, pf, i + 1, pos, biome);
      }
      any
    }
    Modifier::Rarity(chance) => {
      rng.next_float() < 1.0f32 / *chance as f32 && run(level, rng, pf, i + 1, pos, biome)
    }
  }
}

/// `BlockPredicate.test` at `pos`.
fn holds(level: &mut Level, predicate: &Predicate, pos: P) -> bool {
  match predicate {
    // A sapling survives on the dirt tag (or farmland, which the terrain does
    // not make).
    Predicate::SaplingSurvives { offset } => {
      let at = (pos.0 + offset[0], pos.1 + offset[1], pos.2 + offset[2]);
      let below = level.get(at.0, at.1 - 1, at.2);
      level.blocks.is_dirt(below)
    }
    Predicate::MatchingBlocks { offset, blocks } => {
      let b = level.get(pos.0 + offset[0], pos.1 + offset[1], pos.2 + offset[2]);
      blocks.contains(&b.id)
    }
    Predicate::Not(inner) => !holds(level, inner, pos),
    Predicate::AllOf(list) => list.iter().all(|p| holds(level, p, pos)),
  }
}

/// `SimpleBlockFeature`: the provider's draw comes first, then the survival
/// check.
fn place_plant(level: &mut Level, plant: &Plant, pos: P) -> bool {
  let below = level.get(pos.0, pos.1 - 1, pos.2);
  if !level.blocks.plant_survives(plant.lower, below) {
    return false;
  }
  if let Some(upper) = plant.upper {
    let above = level.get(pos.0, pos.1 + 1, pos.2);
    if !level.blocks.is_air(above) {
      return false;
    }
    level.set(pos.0, pos.1 + 1, pos.2, upper);
  }
  level.set(pos.0, pos.1, pos.2, plant.lower)
}

fn place_feature(level: &mut Level, rng: &mut WorldgenRandom, feature: &Feature, pos: P) -> bool {
  // `Feature.place` does nothing for an origin it cannot write to.
  if !level.can_write(pos.0, pos.2) {
    return false;
  }
  match feature {
    Feature::Tree(cfg) => place_tree(level, rng, cfg, pos),
    Feature::Mushroom(cfg) => place_mushroom(level, rng, cfg, pos),
    Feature::RandomSelector { features, default } => {
      for (pf, chance) in features {
        if rng.next_float() < *chance {
          return place(level, rng, pf, pos, None);
        }
      }
      place(level, rng, default, pos, None)
    }
    Feature::RandomPatch { tries, xz_spread, y_spread, feature } => {
      let (j, k) = (xz_spread + 1, y_spread + 1);
      let mut placed = 0;
      for _ in 0..*tries {
        // Arguments are evaluated in order: x, y, z.
        let dx = rng.next_int_bound(j) - rng.next_int_bound(j);
        let dy = rng.next_int_bound(k) - rng.next_int_bound(k);
        let dz = rng.next_int_bound(j) - rng.next_int_bound(j);
        if place(level, rng, feature, (pos.0 + dx, pos.1 + dy, pos.2 + dz), None) {
          placed += 1;
        }
      }
      placed > 0
    }
    Feature::SimpleBlock(provider) => {
      let plant = provider.pick(rng).clone();
      place_plant(level, &plant, pos)
    }
    Feature::RandomBoolean { when_true, when_false } => {
      let pick = if rng.next_bool() { when_true } else { when_false };
      place(level, rng, pick, pos, None)
    }
    Feature::Seagrass { probability, short, tall } => {
      place_seagrass(level, rng, *probability, *short, tall, pos)
    }
    Feature::Kelp { head, stem } => place_kelp(level, rng, *head, *stem, pos),
    Feature::SeaPickle { count, pickle } => place_sea_pickles(level, rng, count, *pickle, pos),
    Feature::SimpleRandom(list) => {
      let i = rng.next_int_bound(list.len() as i32) as usize;
      place(level, rng, &list[i], pos, None)
    }
  }
}

fn is_water(level: &mut Level, x: i32, y: i32, z: i32) -> bool { level.get(x, y, z).id == WATER }

/// `SeagrassFeature`.
fn place_seagrass(
  level: &mut Level,
  rng: &mut WorldgenRandom,
  probability: f64,
  short: Block,
  tall: &Plant,
  pos: P,
) -> bool {
  let dx = rng.next_int_bound(8) - rng.next_int_bound(8);
  let dz = rng.next_int_bound(8) - rng.next_int_bound(8);
  let (x, z) = (pos.0 + dx, pos.2 + dz);
  let y = level.height(Heightmap::OceanFloor, x, z);
  if !is_water(level, x, y, z) {
    return false;
  }
  let is_tall = rng.next_double() < probability;
  let below = level.get(x, y - 1, z);
  if !level.blocks.has_sturdy_top(below) {
    return false;
  }
  if is_tall {
    if is_water(level, x, y + 1, z) {
      level.set(x, y, z, tall.lower);
      level.set(x, y + 1, z, tall.upper.unwrap_or(tall.lower));
    }
  } else {
    level.set(x, y, z, short);
  }
  true
}

/// `KelpFeature`: a stem of up to ten blocks with a head on top, or, where the
/// water runs out first, a head on the last block that fit.
fn place_kelp(
  level: &mut Level,
  rng: &mut WorldgenRandom,
  head: Block,
  stem: Block,
  pos: P,
) -> bool {
  let (x, z) = (pos.0, pos.2);
  let mut y = level.height(Heightmap::OceanFloor, x, z);
  if !is_water(level, x, y, z) {
    return false;
  }
  let mut placed = 0;
  let height = 1 + rng.next_int_bound(10);
  for l in 0..=height {
    // `GrowingPlantBlock.canSurvive` of a stem: kelp or a sturdy block below.
    let below = level.get(x, y - 1, z);
    let survives = level.blocks.is_kelp(below) || level.blocks.has_sturdy_top(below);
    if is_water(level, x, y, z) && is_water(level, x, y + 1, z) && survives {
      if l == height {
        level.set(x, y, z, head);
        rng.next_int_bound(4); // the head's age
        placed += 1;
      } else {
        level.set(x, y, z, stem);
      }
    } else if l > 0 {
      // The head goes on the last block of the stem, which needs kelp or a
      // sturdy block under it, and not a head.
      let under = level.get(x, y - 2, z);
      let holds = level.blocks.is_kelp(under) || level.blocks.has_sturdy_top(under);
      if holds && under.id != head.id {
        level.set(x, y - 1, z, head);
        rng.next_int_bound(4);
        placed += 1;
      }
      break;
    }
    y += 1;
  }
  placed > 0
}

/// `SeaPickleFeature`.
fn place_sea_pickles(
  level: &mut Level,
  rng: &mut WorldgenRandom,
  count: &IntProvider,
  pickle: Block,
  pos: P,
) -> bool {
  let n = count.sample(rng);
  let mut placed = 0;
  for _ in 0..n {
    let dx = rng.next_int_bound(8) - rng.next_int_bound(8);
    let dz = rng.next_int_bound(8) - rng.next_int_bound(8);
    let (x, z) = (pos.0 + dx, pos.2 + dz);
    let y = level.height(Heightmap::OceanFloor, x, z);
    rng.next_int_bound(4); // how many pickles
    let below = level.get(x, y - 1, z);
    if is_water(level, x, y, z) && level.blocks.has_sturdy_top(below) {
      level.set(x, y, z, pickle);
      placed += 1;
    }
  }
  placed > 0
}

/// `AbstractHugeMushroomFeature` and its two caps.
fn place_mushroom(
  level: &mut Level,
  rng: &mut WorldgenRandom,
  cfg: &MushroomConfig,
  pos: P,
) -> bool {
  let mut i = rng.next_int_bound(3) + 4;
  if rng.next_int_bound(12) == 0 {
    i *= 2;
  }

  // isValidPosition
  let y = pos.1;
  if !(y >= level.min_y() + 1 && y + i + 1 < level.max_y()) {
    return false;
  }
  let below = level.get(pos.0, y - 1, pos.2);
  if !level.blocks.is_dirt(below) && !level.blocks.is_mushroom_grow(below) {
    return false;
  }
  for k in 0..=i {
    // Only the brown cap's stem section is wider than the stem, from the fourth
    // layer up.
    let l = if cfg.brown && k > 3 { cfg.foliage_radius } else { 0 };
    for m in -l..=l {
      for n in -l..=l {
        let b = level.get(pos.0 + m, y + k, pos.2 + n);
        if !level.blocks.is_air(b) && !level.blocks.is_leaves(b) {
          return false;
        }
      }
    }
  }

  if cfg.brown {
    brown_cap(level, cfg, pos, i);
  } else {
    red_cap(level, cfg, pos, i);
  }

  for j in 0..i {
    let at = (pos.0, y + j, pos.2);
    let b = level.get(at.0, at.1, at.2);
    if !level.blocks.is_solid_render(b) {
      level.set(at.0, at.1, at.2, cfg.stem);
    }
  }
  true
}

fn with_faces(cap: Block, set: &[(u8, bool)]) -> Block {
  let mut state = cap.state;
  for &(bit, on) in set {
    if on {
      state |= bit;
    } else {
      state &= !bit;
    }
  }
  Block::with(cap.id, state)
}

fn brown_cap(level: &mut Level, cfg: &MushroomConfig, pos: P, i: i32) {
  let j = cfg.foliage_radius;
  for k in -j..=j {
    for l in -j..=j {
      let bl = k == -j;
      let bl2 = k == j;
      let bl3 = l == -j;
      let bl4 = l == j;
      let bl5 = bl || bl2;
      let bl6 = bl3 || bl4;
      if bl5 && bl6 {
        continue;
      }
      let at = (pos.0 + k, pos.1 + i, pos.2 + l);
      let b = level.get(at.0, at.1, at.2);
      if level.blocks.is_solid_render(b) {
        continue;
      }
      let west = bl || (bl6 && k == 1 - j);
      let east = bl2 || (bl6 && k == j - 1);
      let north = bl3 || (bl5 && l == 1 - j);
      let south = bl4 || (bl5 && l == j - 1);
      let state = with_faces(
        cfg.cap,
        &[
          (mushroom::WEST, west),
          (mushroom::EAST, east),
          (mushroom::NORTH, north),
          (mushroom::SOUTH, south),
        ],
      );
      level.set(at.0, at.1, at.2, state);
    }
  }
}

fn red_cap(level: &mut Level, cfg: &MushroomConfig, pos: P, i: i32) {
  for j in i - 3..=i {
    let k = if j < i { cfg.foliage_radius } else { cfg.foliage_radius - 1 };
    let l = cfg.foliage_radius - 2;
    for m in -k..=k {
      for n in -k..=k {
        let bl5 = m == -k || m == k;
        let bl6 = n == -k || n == k;
        if !(j >= i || bl5 != bl6) {
          continue;
        }
        let at = (pos.0 + m, pos.1 + j, pos.2 + n);
        let b = level.get(at.0, at.1, at.2);
        if level.blocks.is_solid_render(b) {
          continue;
        }
        let state = with_faces(
          cfg.cap,
          &[
            (mushroom::UP, j >= i - 1),
            (mushroom::WEST, m < -l),
            (mushroom::EAST, m > l),
            (mushroom::NORTH, n < -l),
            (mushroom::SOUTH, n > l),
          ],
        );
        level.set(at.0, at.1, at.2, state);
      }
    }
  }
}
