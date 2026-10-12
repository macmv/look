//! Predicted trees: a port of vanilla's biome decoration for the features that
//! grow trees.
//!
//! The game places the trees of a chunk from a random source seeded by the
//! world seed, the chunk's position and the feature's place in its decoration
//! step (see `ChunkGenerator.applyBiomeDecoration`), so given the world they
//! are the same every time. This module runs those same features, with the same
//! draws in the same order, over the predicted terrain, so the trees land where
//! the game will put them and are shaped the way it shapes them.
//!
//! What it cannot know is the state of the neighbors when the game decorates a
//! chunk: leaves a neighboring chunk's trees reach over the border change where
//! the ocean floor is, and so which of this chunk's tree spots are valid, and
//! the game decorates chunks in whatever order the player's movement loads
//! them. Decorating a chunk alone is the baseline.
//!
//! `tests/oracle.rs` checks it against the game's own classes on a synthetic
//! world.

pub mod block;
pub mod config;
pub mod feature;
pub mod javaset;
pub mod shape;
pub mod tree;
pub mod world;

use std::collections::BTreeSet;

use serde_json::Value;

use block::Blocks;
use config::PlacedFeature;
use world::Level;

use crate::rng::WorldgenRandom;

struct Slot {
  step:   i32,
  index:  i32,
  placed: PlacedFeature,
}

/// The tree features of a world and which biomes have them, from the
/// `decoration` part of the terrain config (see `TreeExport.java`).
pub struct Decoration {
  pub blocks:      Blocks,
  /// Ordered by decoration step, then index in the step, like vanilla runs
  /// them.
  slots:           Vec<Slot>,
  /// Per biome, per slot: whether the biome has the feature.
  biome_has:       Vec<Vec<bool>>,
  /// Features the export described that the port could not parse, with the
  /// reason.
  pub unsupported: Vec<String>,
}

impl Decoration {
  pub fn from_json(v: &Value) -> Result<Decoration, String> {
    let steps = v.get("steps").and_then(Value::as_array).ok_or("decoration has no steps")?;
    let biomes = v.get("biomes").and_then(Value::as_array).ok_or("decoration has no biomes")?;

    let mut blocks = Blocks::new();
    let mut slots = Vec::new();
    let mut unsupported = Vec::new();
    for (step, features) in steps.iter().enumerate() {
      let Some(features) = features.as_object() else { continue };
      let mut indices: Vec<i32> = features.keys().filter_map(|k| k.parse().ok()).collect();
      indices.sort_unstable();
      for index in indices {
        match PlacedFeature::parse(&mut blocks, &features[&index.to_string()]) {
          Ok(placed) => slots.push(Slot { step: step as i32, index, placed }),
          Err(e) => unsupported.push(format!("step {step} feature {index}: {e}")),
        }
      }
    }
    for s in v.get("skipped").and_then(Value::as_array).into_iter().flatten() {
      unsupported.push(format!("export: {s}"));
    }

    let mut biome_has = Vec::new();
    for per_step in biomes {
      let mut has = vec![false; slots.len()];
      for (step, list) in per_step.as_array().into_iter().flatten().enumerate() {
        for index in list.as_array().into_iter().flatten().filter_map(Value::as_i64) {
          if let Some(slot) =
            slots.iter().position(|s| s.step == step as i32 && s.index == index as i32)
          {
            has[slot] = true;
          }
        }
      }
      biome_has.push(has);
    }

    Ok(Decoration { blocks, slots, biome_has, unsupported })
  }

  pub fn biome_count(&self) -> usize { self.biome_has.len() }

  /// Whether a biome has any tree feature at all.
  pub fn has_trees(&self, biome: usize) -> bool {
    self.biome_has.get(biome).is_some_and(|h| h.iter().any(|&b| b))
  }

  /// `ChunkGenerator.applyBiomeDecoration`, for the tree features: decorates
  /// chunk `(cx, cz)` of `level`. `region` is the set of biomes in and around
  /// the chunk, which is what decides which features run (a superset is
  /// harmless: a feature no biome nearby has places nothing).
  pub fn decorate_chunk(
    &self,
    level: &mut Level,
    seed: i64,
    cx: i32,
    cz: i32,
    region: &BTreeSet<usize>,
  ) {
    level.set_center(cx, cz);
    let origin = (cx * 16, level.min_y(), cz * 16);

    let mut rng = WorldgenRandom::new();
    let decoration_seed = rng.set_decoration_seed(seed, origin.0, origin.2);

    for (slot_index, slot) in self.slots.iter().enumerate() {
      if !region.iter().any(|&b| self.biome_has.get(b).is_some_and(|h| h[slot_index])) {
        continue;
      }
      rng.set_feature_seed(decoration_seed, slot.index, slot.step);
      let biome_has = &self.biome_has;
      let check = move |b: usize| biome_has.get(b).is_some_and(|h| h[slot_index]);
      feature::place(level, &mut rng, &slot.placed, origin, Some(&check));
    }
  }
}
