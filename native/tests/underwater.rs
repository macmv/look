//! Seagrass, kelp and sea pickles on a flat ocean floor, from the JSON the
//! game's codecs write for the placed features. There is no oracle for these
//! here: this checks where things land and what they stand on.

use std::collections::BTreeSet;

use look_native::trees::{
  block::{BlockId, DIRT, SAND, STONE},
  world::{Ground, Level},
  Decoration,
};
use serde_json::{json, Value};

const FLOOR: i32 = 40;

struct Ocean;

impl Ground for Ocean {
  fn min_y(&self) -> i32 { -64 }

  fn max_y(&self) -> i32 { 320 }

  fn sea_level(&self) -> i32 { 63 }

  fn top(&mut self, _x: i32, _z: i32) -> Option<i32> { Some(FLOOR) }

  fn terrain_block(&mut self, _x: i32, y: i32, _z: i32) -> BlockId {
    match y {
      39 => SAND,
      36..=38 => DIRT,
      _ => STONE,
    }
  }

  fn biome(&mut self, _x: i32, _y: i32, _z: i32) -> usize { 0 }
}

fn deco() -> Decoration {
  let spread = json!([
    {"type": "minecraft:in_square"},
    {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR_WG"}
  ]);
  let with = |extra: Value| {
    let mut list = spread.as_array().unwrap().clone();
    list.push(extra);
    list.push(json!({"type": "minecraft:biome"}));
    Value::Array(list)
  };
  let config = json!({
    "steps": [{}, {}, {}, {}, {}, {}, {}, {}, {
      "0": {
        "feature": {"type": "minecraft:seagrass", "config": {"probability": 0.6}},
        "placement": with(json!({"type": "minecraft:count", "count": 48}))
      },
      "1": {
        "feature": {"type": "minecraft:kelp", "config": {}},
        "placement": with(json!({"type": "minecraft:noise_based_count",
          "noise_to_count_ratio": 120, "noise_factor": 80.0, "noise_offset": 0.0}))
      },
      "2": {
        "feature": {"type": "minecraft:sea_pickle", "config": {"count": 20}},
        "placement": [{"type": "minecraft:rarity_filter", "chance": 2},
          {"type": "minecraft:in_square"},
          {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR_WG"},
          {"type": "minecraft:biome"}]
      },
    }],
    "biomes": [[[], [], [], [], [], [], [], [], [0, 1, 2]]],
    "skipped": []
  });
  let deco = Decoration::from_json(&config).unwrap();
  assert!(deco.unsupported.is_empty(), "{:#?}", deco.unsupported);
  deco
}

fn run(deco: &Decoration) -> Vec<(i32, i32, i32, String)> {
  let region = BTreeSet::from([0]);
  let mut ground = Ocean;
  let mut level = Level::new(&deco.blocks, &mut ground);
  for cx in -3..3 {
    for cz in -3..3 {
      deco.decorate_chunk_with(&mut level, 99, cx, cz, &region, true);
    }
  }
  let mut out: Vec<_> =
    level.placed().map(|(x, y, z, b)| (x, y, z, deco.blocks.name(b.id).to_string())).collect();
  out.sort();
  out
}

fn named<'a>(
  blocks: &'a [(i32, i32, i32, String)],
  name: &'a str,
) -> impl Iterator<Item = &'a (i32, i32, i32, String)> + 'a {
  blocks.iter().filter(move |b| b.3 == name)
}

#[test]
fn seagrass_stands_on_the_floor() {
  let blocks = run(&deco());
  assert!(named(&blocks, "minecraft:seagrass").count() > 100, "too little seagrass");
  for b in named(&blocks, "minecraft:seagrass") {
    assert_eq!(b.1, FLOOR, "seagrass off the floor");
  }
}

#[test]
fn tall_seagrass_has_two_halves() {
  let blocks = run(&deco());
  let lower: BTreeSet<_> =
    named(&blocks, "minecraft:tall_seagrass").map(|b| (b.0, b.1, b.2)).collect();
  let upper: BTreeSet<_> =
    named(&blocks, "minecraft:tall_seagrass[half=upper]").map(|b| (b.0, b.1 - 1, b.2)).collect();
  assert!(!lower.is_empty(), "no tall seagrass");
  assert_eq!(lower, upper);
}

#[test]
fn kelp_is_a_stem_with_a_head() {
  let blocks = run(&deco());
  let heads: Vec<_> = named(&blocks, "minecraft:kelp").collect();
  assert!(!heads.is_empty(), "{} kelp heads", heads.len());
  for h in heads {
    assert!(h.1 >= FLOOR && h.1 <= FLOOR + 11, "head at {}", h.1);
    // Everything under a head is stem, down to the floor.
    for y in FLOOR..h.1 {
      assert!(
        blocks.iter().any(|b| (b.0, b.1, b.2) == (h.0, y, h.2) && b.3 == "minecraft:kelp_plant"),
        "no stem at {y} under the head at {h:?}"
      );
    }
  }
}

#[test]
fn sea_pickles_stand_on_the_floor() {
  let blocks = run(&deco());
  let pickles = named(&blocks, "minecraft:sea_pickle").count();
  assert!(pickles > 20, "{pickles} sea pickles");
  assert!(named(&blocks, "minecraft:sea_pickle").all(|b| b.1 == FLOOR));
}
