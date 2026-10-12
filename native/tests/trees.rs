//! Checks the tree decoration port against the game's own tree features.
//! `tests/fixtures/trees.json` is written by `tests/fixtures/TreeOracle.java`
//! (see its header to regenerate): the exported tree features, and for a
//! synthetic world, the blocks vanilla's `PlacedFeature` / `TreeFeature`
//! classes place in each scenario, as a count and a hash.
//!
//! When one differs, regenerate with the optional third argument of
//! `TreeOracle` and point `LOOK_TREE_FULL` at that file to see which blocks are
//! missing or extra.

use std::collections::{BTreeMap, BTreeSet};

use look_native::trees::{
  block::{BlockId, Blocks, DIRT, GRASS_BLOCK, SAND, STONE},
  world::{Ground, Level},
  Decoration,
};
use serde_json::Value;

const MIN_Y: i32 = -64;
const MAX_Y: i32 = 320;
const SEA: i32 = 63;

/// A triangle wave in `0..=p`.
fn tri(v: i32, p: i32) -> i32 { (v.rem_euclid(2 * p) - p).abs() }

/// The world `TreeOracle.java` builds. Keep in sync with its `top`.
struct Synthetic {
  flat:    bool,
  west:    usize,
  east:    usize,
  split_x: i32,
}

impl Ground for Synthetic {
  fn min_y(&self) -> i32 { MIN_Y }

  fn max_y(&self) -> i32 { MAX_Y }

  fn sea_level(&self) -> i32 { SEA }

  fn top(&mut self, x: i32, z: i32) -> Option<i32> {
    if self.flat {
      return Some(70);
    }
    Some(58 + tri(x + 2 * z, 24) / 3 + tri(3 * x - z, 40) / 5 + tri(z - x, 16) / 4 - 4)
  }

  fn terrain_block(&mut self, x: i32, y: i32, z: i32) -> BlockId {
    let t = self.top(x, z).unwrap();
    if y == t - 1 {
      if t <= 64 {
        SAND
      } else {
        GRASS_BLOCK
      }
    } else if y >= t - 4 {
      if t <= 64 {
        SAND
      } else {
        DIRT
      }
    } else {
      STONE
    }
  }

  fn biome(&mut self, x: i32, _y: i32, _z: i32) -> usize {
    if x < self.split_x {
      self.west
    } else {
      self.east
    }
  }
}

fn fnv(lines: &[String]) -> u64 {
  let mut h = 0xcbf2_9ce4_8422_2325u64;
  for line in lines {
    for b in line.bytes().chain(std::iter::once(b'\n')) {
      h ^= b as u64;
      h = h.wrapping_mul(0x100_0000_01b3);
    }
  }
  h
}

fn describe(blocks: &Blocks, x: i32, y: i32, z: i32, id: BlockId, state: u8) -> String {
  // A leaf's state is its distance to a log, which the oracle does not compare.
  let state = if blocks.name(id).ends_with("_leaves") { 0 } else { state };
  format!("{x},{y},{z},{}:{state}", blocks.name(id))
}

fn run(deco: &Decoration, seed: i64, s: &Value) -> Vec<String> {
  let mut ground = Synthetic {
    flat:    s["flat"].as_bool().unwrap(),
    west:    s["west"].as_u64().unwrap() as usize,
    east:    s["east"].as_u64().unwrap() as usize,
    split_x: s["split_x"].as_i64().unwrap() as i32,
  };
  let region: BTreeSet<usize> = [ground.west, ground.east].into_iter().collect();

  let mut level = Level::new(&deco.blocks, &mut ground);
  for c in s["chunks"].as_array().unwrap() {
    deco.decorate_chunk(
      &mut level,
      seed,
      c[0].as_i64().unwrap() as i32,
      c[1].as_i64().unwrap() as i32,
      &region,
    );
  }

  // Vanilla shuffles the spots a bee nest could take with an unseeded `Random`,
  // so where it ends up cannot be reproduced; the draws it makes are, and
  // those are what is checked.
  let set: BTreeSet<String> = level
    .placed()
    .filter(|&(_, _, _, b)| deco.blocks.name(b.id) != "minecraft:bee_nest")
    .map(|(x, y, z, b)| describe(&deco.blocks, x, y, z, b.id, b.state))
    .collect();
  set.into_iter().collect()
}

#[test]
fn trees_match_vanilla() {
  let fixture: Value = serde_json::from_str(include_str!("fixtures/trees.json")).unwrap();
  let seed = fixture["seed"].as_i64().unwrap();
  let deco = Decoration::from_json(&fixture["decoration"]).unwrap();
  let full: Option<Value> = std::env::var("LOOK_TREE_FULL")
    .ok()
    .map(|p| serde_json::from_slice(&std::fs::read(p).unwrap()).unwrap());

  let mut failures = BTreeMap::new();
  let scenarios = fixture["scenarios"].as_array().unwrap();
  for s in scenarios {
    let name = s["name"].as_str().unwrap();
    if std::env::var("LOOK_TREE_ONLY").is_ok_and(|only| only != name) {
      continue;
    }
    let lines = run(&deco, seed, s);
    let hash = format!("{:x}", fnv(&lines));
    let want_count = s["count"].as_u64().unwrap() as usize;
    if lines.len() == want_count && hash == s["hash"].as_str().unwrap() {
      continue;
    }

    let mut msg = format!("got {} blocks, want {want_count}", lines.len());
    if let Some(expected) = full.as_ref().and_then(|f| f[name].as_array()) {
      let want: BTreeSet<&str> = expected.iter().map(|v| v.as_str().unwrap()).collect();
      let got: BTreeSet<&str> = lines.iter().map(String::as_str).collect();
      let missing: Vec<_> = want.difference(&got).take(6).collect();
      let extra: Vec<_> = got.difference(&want).take(6).collect();
      msg += &format!("; missing {missing:?}; extra {extra:?}");
    }
    failures.insert(name.to_string(), msg);
  }

  if !failures.is_empty() {
    let mut report = format!("{} of {} scenarios differ:\n", failures.len(), scenarios.len());
    for (name, msg) in &failures {
      report += &format!("  {name}: {msg}\n");
    }
    panic!("{report}");
  }
}

#[test]
fn unsupported_features_are_few() {
  let fixture: Value = serde_json::from_str(include_str!("fixtures/trees.json")).unwrap();
  let deco = Decoration::from_json(&fixture["decoration"]).unwrap();
  // Mangrove swamp trees need root placers, which are not ported.
  assert!(deco.unsupported.len() <= 3, "{:#?}", deco.unsupported);
}
