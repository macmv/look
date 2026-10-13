//! The overworld's biome table: parameter points in six climate dimensions, and
//! the lookup that finds the nearest one for a sampled climate. Only what the
//! terrain surface needs is kept per biome (flags and a temperature), computed
//! by the Java side from tags and the biome definition.

use serde_json::Value;

/// Bit flags describing a biome, shared with `TerrainExport.java`.
pub mod flags {
  pub const OCEAN: u32 = 1;
  pub const RIVER: u32 = 2;
  pub const BEACH: u32 = 4;
  pub const BADLANDS: u32 = 8;
  pub const MOUNTAIN: u32 = 16;
  pub const DESERT: u32 = 32;
  /// Bare rock on steep ground (jagged and frozen peaks, snowy slopes).
  pub const STEEP_ROCK: u32 = 64;
  /// Bare rock everywhere (stony peaks and shores).
  pub const STONE: u32 = 128;
  /// Sand under the water instead of gravel (warm and lukewarm oceans).
  pub const WARM_SEABED: u32 = 256;
}

#[derive(Clone, Copy, Debug)]
pub struct BiomeInfo {
  pub flags:       u32,
  pub temperature: f32,
  /// Grass color (0xRRGGBB) of the biome, for tinting grass blocks.
  pub grass:       u32,
  /// Foliage color (0xRRGGBB) of the biome, for tinting leaves.
  pub foliage:     u32,
  /// Water color (0xRRGGBB) of the biome.
  pub water:       u32,
}

/// Used for biomes the export gave no grass color for (plains).
const DEFAULT_GRASS: u32 = 0x91BD59;
/// Used for biomes the export gave no foliage color for (plains).
const DEFAULT_FOLIAGE: u32 = 0x77AB2F;
/// Used for biomes the export gave no water color for.
const DEFAULT_WATER: u32 = 0x3F76E4;

struct Point {
  min:    [i64; 6],
  max:    [i64; 6],
  offset: i64,
  biome:  u16,
}

/// A node of the search tree: the bounding box of everything below it, and the
/// smallest offset (which adds to every point's fitness) among them.
struct Node {
  min:           [i64; 6],
  max:           [i64; 6],
  min_offset_sq: i64,
  /// Children are either a range of `points` (leaf) or node indices.
  kind:          Kind,
}

enum Kind {
  Leaf { start: usize, end: usize },
  Inner { children: Vec<usize> },
}

/// Points per leaf, and children per inner node.
const LEAF: usize = 8;
const FANOUT: usize = 4;

pub struct BiomeIndex {
  biomes: Vec<BiomeInfo>,
  /// Reordered so every leaf is a contiguous range.
  points: Vec<Point>,
  nodes:  Vec<Node>,
  root:   usize,
}

/// `Climate.Parameter.distance`: zero inside the range, else the gap to the
/// nearer end.
#[inline]
fn distance(min: i64, max: i64, target: i64) -> i64 {
  let above = target - max;
  let below = min - target;
  if above > 0 {
    above
  } else {
    below.max(0)
  }
}

impl BiomeIndex {
  /// Reads `{ "flags": [..], "temperatures": [..], "grass_colors": [..]
  /// (optional), "points": [[12 range bounds, offset, biome]..] }`.
  pub fn from_json(v: &Value) -> Result<BiomeIndex, String> {
    let flags = v.get("flags").and_then(Value::as_array).ok_or("biomes without flags")?;
    let temps =
      v.get("temperatures").and_then(Value::as_array).ok_or("biomes without temperatures")?;
    if flags.len() != temps.len() {
      return Err("biome flags and temperatures differ in length".into());
    }
    let grass = v.get("grass_colors").and_then(Value::as_array);
    let water = v.get("water_colors").and_then(Value::as_array);
    let foliage = v.get("foliage_colors").and_then(Value::as_array);
    let biomes: Vec<BiomeInfo> = flags
      .iter()
      .zip(temps)
      .enumerate()
      .map(|(i, (f, t))| BiomeInfo {
        flags:       f.as_u64().unwrap_or(0) as u32,
        temperature: t.as_f64().unwrap_or(0.5) as f32,
        grass:       grass
          .and_then(|g| g.get(i))
          .and_then(Value::as_u64)
          .map_or(DEFAULT_GRASS, |c| c as u32 & 0xFFFFFF),
        foliage:     foliage
          .and_then(|g| g.get(i))
          .and_then(Value::as_u64)
          .map_or(DEFAULT_FOLIAGE, |c| c as u32 & 0xFFFFFF),
        water:       water
          .and_then(|g| g.get(i))
          .and_then(Value::as_u64)
          .map_or(DEFAULT_WATER, |c| c as u32 & 0xFFFFFF),
      })
      .collect();

    let raw = v.get("points").and_then(Value::as_array).ok_or("biomes without points")?;
    let mut points = Vec::with_capacity(raw.len());
    for p in raw {
      let a = p.as_array().filter(|a| a.len() == 14).ok_or("bad biome point")?;
      let n = |i: usize| a[i].as_i64().ok_or("bad biome point value");
      let mut min = [0; 6];
      let mut max = [0; 6];
      for d in 0..6 {
        min[d] = n(d * 2)?;
        max[d] = n(d * 2 + 1)?;
      }
      let biome = n(13)? as usize;
      if biome >= biomes.len() {
        return Err("biome point refers to an unknown biome".into());
      }
      points.push(Point { min, max, offset: n(12)?, biome: biome as u16 });
    }
    if points.is_empty() {
      return Err("no biome points".into());
    }

    let mut nodes = Vec::new();
    let mut ordered = Vec::with_capacity(points.len());
    let root = build(points, &mut ordered, &mut nodes);
    Ok(BiomeIndex { biomes, points: ordered, nodes, root })
  }

  /// The biome whose parameter point is nearest to `target` (temperature,
  /// humidity, continentalness, erosion, depth, weirdness; quantized like
  /// `Climate.TargetPoint`).
  pub fn nearest(&self, target: [i64; 6]) -> BiomeInfo { self.biomes[self.nearest_index(target).0] }

  /// The biome with this index (of the order the export numbers them in).
  pub fn info(&self, index: usize) -> BiomeInfo { self.biomes[index] }

  /// Like [`nearest`](Self::nearest), but returns the biome's index and the
  /// winning fitness.
  pub fn nearest_index(&self, target: [i64; 6]) -> (usize, i64) {
    let mut best = (i64::MAX, self.points[0].biome);
    self.search(self.root, &target, &mut best);
    (best.1 as usize, best.0)
  }

  /// The fitness of the nearest point found by looking at every point; the
  /// reference for the tree.
  pub fn nearest_index_brute_force(&self, target: [i64; 6]) -> (usize, i64) {
    let mut best = (i64::MAX, self.points[0].biome);
    self.scan(0, self.points.len(), &target, &mut best);
    (best.1 as usize, best.0)
  }

  fn scan(&self, start: usize, end: usize, target: &[i64; 6], best: &mut (i64, u16)) {
    'points: for p in &self.points[start..end] {
      let mut fitness = p.offset * p.offset;
      for d in 0..6 {
        let dist = distance(p.min[d], p.max[d], target[d]);
        fitness += dist * dist;
        if fitness >= best.0 {
          continue 'points;
        }
      }
      *best = (fitness, p.biome);
    }
  }

  /// Lower bound of the fitness of anything below a node.
  fn bound(node: &Node, target: &[i64; 6]) -> i64 {
    let mut sum = node.min_offset_sq;
    for d in 0..6 {
      let dist = distance(node.min[d], node.max[d], target[d]);
      sum += dist * dist;
    }
    sum
  }

  fn search(&self, id: usize, target: &[i64; 6], best: &mut (i64, u16)) {
    match &self.nodes[id].kind {
      Kind::Leaf { start, end } => self.scan(*start, *end, target, best),
      Kind::Inner { children } => {
        // Nearest child first, so the others are likely cut off by what it
        // finds.
        let mut order = [(0i64, 0usize); FANOUT];
        let n = children.len();
        for (slot, &c) in order.iter_mut().zip(children) {
          *slot = (Self::bound(&self.nodes[c], target), c);
        }
        order[..n].sort_unstable();
        for &(bound, c) in &order[..n] {
          if bound < best.0 {
            self.search(c, target, best);
          }
        }
      }
    }
  }
}

/// Builds the subtree for `points` (appending them to `ordered` grouped by
/// leaf) and returns its node index. Splits along the dimension where the
/// points' centers are most spread out.
fn build(mut points: Vec<Point>, ordered: &mut Vec<Point>, nodes: &mut Vec<Node>) -> usize {
  let mut min = [i64::MAX; 6];
  let mut max = [i64::MIN; 6];
  let mut min_offset_sq = i64::MAX;
  for p in &points {
    for d in 0..6 {
      min[d] = min[d].min(p.min[d]);
      max[d] = max[d].max(p.max[d]);
    }
    min_offset_sq = min_offset_sq.min(p.offset * p.offset);
  }

  if points.len() <= LEAF {
    let start = ordered.len();
    ordered.extend(points);
    nodes.push(Node { min, max, min_offset_sq, kind: Kind::Leaf { start, end: ordered.len() } });
    return nodes.len() - 1;
  }

  let spread = |d: usize| {
    let centers = points.iter().map(|p| p.min[d] + p.max[d]);
    centers.clone().max().unwrap() - centers.min().unwrap()
  };
  let dim = (0..6).max_by_key(|&d| spread(d)).unwrap();
  points.sort_unstable_by_key(|p| p.min[dim] + p.max[dim]);

  let per_child = points.len().div_ceil(FANOUT);
  let mut children = Vec::new();
  while !points.is_empty() {
    let rest = points.split_off(per_child.min(points.len()));
    children.push(build(std::mem::replace(&mut points, rest), ordered, nodes));
  }
  nodes.push(Node { min, max, min_offset_sq, kind: Kind::Inner { children } });
  nodes.len() - 1
}
