//! Grass patches on a flat world, from the JSON the game's codecs write for
//! `patch_grass_plain` and `patch_tall_grass_2`. The noise and the draws are
//! the game's, but there is no oracle for them here: this checks the shape of
//! what comes out.

use std::collections::BTreeSet;

use look_native::trees::{
  block::{BlockId, DIRT, GRASS_BLOCK, SAND, STONE},
  world::{Ground, Level},
  Decoration,
};
use serde_json::{json, Value};

struct Flat {
  sand: bool,
}

impl Ground for Flat {
  fn min_y(&self) -> i32 { -64 }

  fn max_y(&self) -> i32 { 320 }

  fn sea_level(&self) -> i32 { 63 }

  fn top(&mut self, _x: i32, _z: i32) -> Option<i32> { Some(70) }

  fn terrain_block(&mut self, _x: i32, y: i32, _z: i32) -> BlockId {
    match (y, self.sand) {
      (69, false) => GRASS_BLOCK,
      (66..=68, false) => DIRT,
      (66..=69, true) => SAND,
      _ => STONE,
    }
  }

  fn biome(&mut self, _x: i32, _y: i32, _z: i32) -> usize { 0 }
}

fn in_air() -> Value {
  json!({"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"}})
}

fn patch(tries: i32, provider: Value, placement: Value) -> Value {
  json!({
    "feature": {"type": "minecraft:random_patch", "config": {
      "tries": tries, "xz_spread": 7, "y_spread": 3,
      "feature": {
        "feature": {"type": "minecraft:simple_block", "config": {"to_place": provider}},
        "placement": [in_air()]
      }
    }},
    "placement": placement
  })
}

fn deco() -> Decoration {
  let grass =
    json!({"type": "minecraft:simple_state_provider", "state": {"Name": "minecraft:short_grass"}});
  let tall =
    json!({"type": "minecraft:simple_state_provider", "state": {"Name": "minecraft:tall_grass"}});
  let bush =
    json!({"type": "minecraft:simple_state_provider", "state": {"Name": "minecraft:dead_bush"}});
  let surface = json!({"type": "minecraft:heightmap", "heightmap": "WORLD_SURFACE_WG"});
  let blocking = json!({"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"});
  let square = json!({"type": "minecraft:in_square"});
  let biome = json!({"type": "minecraft:biome"});
  let config = json!({
    "steps": [{}, {}, {}, {}, {}, {}, {}, {}, {
      "0": patch(32, grass, json!([
        {"type": "minecraft:noise_threshold_count", "noise_level": -0.8, "below_noise": 5, "above_noise": 10},
        square, surface, biome])),
      "1": patch(32, tall, json!([
        {"type": "minecraft:noise_threshold_count", "noise_level": -0.8, "below_noise": 0, "above_noise": 7},
        {"type": "minecraft:rarity_filter", "chance": 32}, square, blocking, biome])),
      "2": patch(4, bush, json!([square, surface, biome])),
    }],
    "biomes": [[[], [], [], [], [], [], [], [], [0, 1, 2]]],
    "skipped": []
  });
  let deco = Decoration::from_json(&config).unwrap();
  assert!(deco.unsupported.is_empty(), "{:#?}", deco.unsupported);
  deco
}

fn run(deco: &Decoration, ground: &mut Flat, plants: bool) -> Vec<(i32, i32, i32, String)> {
  let region = BTreeSet::from([0]);
  let mut level = Level::new(&deco.blocks, ground);
  let mut out = Vec::new();
  for cx in -2..2 {
    for cz in -2..2 {
      deco.decorate_chunk_with(&mut level, 1234, cx, cz, &region, plants);
    }
  }
  for (x, y, z, b) in level.placed() {
    out.push((x, y, z, deco.blocks.name(b.id).to_string()));
  }
  out.sort();
  out
}

#[test]
fn grass_lands_on_the_surface() {
  let deco = deco();
  let blocks = run(&deco, &mut Flat { sand: false }, true);
  let grass = blocks.iter().filter(|b| b.3 == "minecraft:short_grass").count();
  assert!(grass > 100, "{grass} grass blocks");
  for (_, y, _, name) in &blocks {
    if name == "minecraft:short_grass" {
      assert_eq!(*y, 70, "grass off the surface");
    }
  }
}

#[test]
fn tall_grass_has_two_halves() {
  let deco = deco();
  let blocks = run(&deco, &mut Flat { sand: false }, true);
  let lower: BTreeSet<_> =
    blocks.iter().filter(|b| b.3 == "minecraft:tall_grass").map(|b| (b.0, b.1, b.2)).collect();
  let upper: BTreeSet<_> = blocks
    .iter()
    .filter(|b| b.3 == "minecraft:tall_grass[half=upper]")
    .map(|b| (b.0, b.1 - 1, b.2))
    .collect();
  assert!(!lower.is_empty(), "no tall grass");
  assert_eq!(lower, upper);
}

#[test]
fn only_sand_grows_dead_bushes_not_grass() {
  let deco = deco();
  let blocks = run(&deco, &mut Flat { sand: true }, true);
  assert!(blocks.iter().all(|b| b.3 != "minecraft:short_grass"), "grass on sand");
  assert!(blocks.iter().any(|b| b.3 == "minecraft:dead_bush"), "no dead bush on sand");
}

#[test]
fn leaving_plants_out_places_nothing_here() {
  let deco = deco();
  assert!(run(&deco, &mut Flat { sand: false }, false).is_empty());
  assert!(!deco.has_features(0, false) && deco.has_features(0, true));
}
