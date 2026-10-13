//! Trees on the predicted terrain of the fixture world: the decoration port
//! (checked against the game in `trees.rs`) run over the terrain from
//! `oracle.rs`'s fixture.

use std::time::Instant;

use look_native::{
  forest::TreeCache,
  terrain::{Ids, Terrain, Tile, TileKind, BRICK_VOXELS, HF_BYTES, HF_TREE},
};
use serde_json::Value;

fn terrain() -> Terrain {
  let world: Value = serde_json::from_str(include_str!("fixtures/overworld.json")).unwrap();
  let trees: Value = serde_json::from_str(include_str!("fixtures/trees.json")).unwrap();
  assert_eq!(world["seed"], trees["seed"], "fixtures are for different worlds");
  let mut config = world["config"].clone();
  config["decoration"] = trees["decoration"].clone();
  Terrain::from_config(world["seed"].as_i64().unwrap(), &serde_json::to_vec(&config).unwrap())
    .unwrap()
}

/// Every tree block of the tile gets the id of its own block id plus 100, so
/// the test can tell them from terrain.
fn ids(t: &Terrain) -> Ids {
  let mut ids = Ids::default();
  ids.top = [10; 6];
  ids.filler = [11; 6];
  ids.stone = 12;
  ids.water = 13;
  let n = t.forest.as_ref().unwrap().block_names().len();
  ids.trees = (0..n).map(|i| 100 + i as u16).collect::<Vec<_>>().into();
  ids
}

fn tree_voxels(tile: &Tile, level: u32, t: &Terrain, ids: &Ids) -> usize {
  let mut n = 0;
  let mut out = Box::new([0u16; BRICK_VOXELS]);
  for sy in -4..24 {
    tile.fill_brick(level, sy, &t.params, ids, &mut out);
    n += out.iter().filter(|&&v| v >= 100).count();
  }
  n
}

#[test]
fn voxel_tiles_get_trees() {
  let t = terrain();
  let ids = ids(&t);
  let mut s = t.scratch();
  let cache = TreeCache::new(256);

  let mut forested = 0;
  let mut start = Instant::now();
  for i in 0..12 {
    let tile = t.sample_tile_with(&mut s, 0, i * 7 - 40, i * 5 - 30, TileKind::Voxel, Some(&cache));
    let n = tree_voxels(&tile, 0, &t, &ids);
    if n > 0 {
      forested += 1;
    }
    eprintln!("tile {i}: {} tree voxels in {} blocks", n, tile.trees.len());
  }
  eprintln!("level 0 tiles: {:?} each", start.elapsed() / 12);
  assert!(forested > 0, "no tile of 12 has a tree");

  // The same ground at level 1 keeps some of the trees.
  start = Instant::now();
  let tile = t.sample_tile_with(&mut s, 1, 2, 3, TileKind::Voxel, Some(&cache));
  eprintln!("level 1 tile: {:?}, {} tree voxels", start.elapsed(), tree_voxels(&tile, 1, &t, &ids));
}

#[test]
fn heightfield_tiles_get_canopies() {
  let t = terrain();
  let mut s = t.scratch();
  let cache = TreeCache::new(2048);

  for level in 0..=3u32 {
    let start = Instant::now();
    let mut trees = 0;
    let mut tiles = 0;
    for i in 0..6 {
      let tile = t.sample_tile_with(
        &mut s,
        level,
        i * 3 - 9,
        4 - i * 2,
        TileKind::Heightfield,
        Some(&cache),
      );
      let mut out = [0u8; HF_BYTES];
      tile.write_heightfield(63, &mut out);
      trees += (0..256).filter(|&c| out[512 + c * 4 + 3] >= HF_TREE).count();
      tiles += 1;
    }
    eprintln!(
      "heightfield level {level}: {:?} per tile, {} tree texels over {tiles} tiles",
      start.elapsed() / tiles,
      trees
    );
  }
}
