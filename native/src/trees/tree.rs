//! `TreeFeature` and the trunk placers, foliage placers and decorators it uses,
//! ported from vanilla 1.21.1. Every draw from the random source happens in the
//! order vanilla makes it, and the float / double arithmetic keeps the types
//! Java gives it, since the draws decide the shapes and, because one source
//! runs through a whole chunk's feature, where the next trees go.
//!
//! Left out: root placers (mangroves), and the block updates vanilla does after
//! placing (leaf distances), which change no block.

use std::{collections::HashSet, f32::consts::TAU, f64::consts::PI, sync::OnceLock};

use super::{
  block::{vine, Block, COCOA, VINE},
  config::{Decorator, FoliagePlacer, IntProvider, TreeConfig, TrunkPlacer},
  javaset, shape,
  world::Level,
};
use crate::rng::WorldgenRandom;

pub type P = (i32, i32, i32);

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Dir {
  North,
  East,
  South,
  West,
}

pub const HORIZONTAL: [Dir; 4] = [Dir::North, Dir::East, Dir::South, Dir::West];

impl Dir {
  pub fn step(self) -> (i32, i32) {
    match self {
      Dir::North => (0, -1),
      Dir::East => (1, 0),
      Dir::South => (0, 1),
      Dir::West => (-1, 0),
    }
  }

  pub fn opposite(self) -> Dir {
    match self {
      Dir::North => Dir::South,
      Dir::East => Dir::West,
      Dir::South => Dir::North,
      Dir::West => Dir::East,
    }
  }

  pub fn clockwise(self) -> Dir {
    match self {
      Dir::North => Dir::East,
      Dir::East => Dir::South,
      Dir::South => Dir::West,
      Dir::West => Dir::North,
    }
  }

  fn positive(self) -> bool { matches!(self, Dir::East | Dir::South) }

  /// The axis as a log's state: 1 for x, 2 for z.
  pub fn axis_state(self) -> u8 {
    match self {
      Dir::East | Dir::West => 1,
      _ => 2,
    }
  }

  /// `Direction.get2DDataValue`, which cocoa stores its facing as.
  fn data_2d(self) -> u8 {
    match self {
      Dir::South => 0,
      Dir::West => 1,
      Dir::North => 2,
      Dir::East => 3,
    }
  }

  /// The vine face on the side of a block that this direction points away from.
  fn vine_face(self) -> u8 {
    match self {
      Dir::North => vine::NORTH,
      Dir::East => vine::EAST,
      Dir::South => vine::SOUTH,
      Dir::West => vine::WEST,
    }
  }
}

fn off(p: P, dx: i32, dy: i32, dz: i32) -> P { (p.0 + dx, p.1 + dy, p.2 + dz) }

fn rel(p: P, d: Dir, n: i32) -> P {
  let (sx, sz) = d.step();
  (p.0 + sx * n, p.1, p.2 + sz * n)
}

fn random_dir(rng: &mut WorldgenRandom) -> Dir { HORIZONTAL[rng.next_int_bound(4) as usize] }

fn manhattan(a: P, b: P) -> i32 { (a.0 - b.0).abs() + (a.1 - b.1).abs() + (a.2 - b.2).abs() }

/// `Mth.floor(float)`.
fn floor_f(f: f32) -> i32 {
  let i = f as i32;
  if f < i as f32 {
    i - 1
  } else {
    i
  }
}

/// `Mth.floor(double)`.
fn floor_d(d: f64) -> i32 {
  let i = d as i32;
  if d < i as f64 {
    i - 1
  } else {
    i
  }
}

/// `Mth`'s lookup table sine.
fn sin_table() -> &'static [f32; 65536] {
  static TABLE: OnceLock<Box<[f32; 65536]>> = OnceLock::new();
  TABLE.get_or_init(|| {
    let mut t = Box::new([0f32; 65536]);
    for (i, v) in t.iter_mut().enumerate() {
      *v = ((i as f64) * PI * 2.0 / 65536.0).sin() as f32;
    }
    t
  })
}

fn mth_sin(f: f32) -> f32 { sin_table()[((f * 10430.378f32) as i32 & 65535) as usize] }

fn mth_cos(f: f32) -> f32 { sin_table()[((f * 10430.378f32 + 16384.0f32) as i32 & 65535) as usize] }

/// Positions in the order they were first added, deduplicated, like the hash
/// sets vanilla fills.
#[derive(Default)]
struct PosSet {
  order: Vec<P>,
  seen:  HashSet<P>,
}

impl PosSet {
  fn add(&mut self, p: P) {
    if self.seen.insert(p) {
      self.order.push(p);
    }
  }

  fn contains(&self, p: P) -> bool { self.seen.contains(&p) }

  fn is_empty(&self) -> bool { self.order.is_empty() }

  /// The list the decorators see: the set's iteration order, then a stable sort
  /// by height.
  fn decorator_list(&self) -> Vec<P> {
    let mut list: Vec<P> =
      javaset::iteration_order(&self.order).into_iter().map(|i| self.order[i]).collect();
    list.sort_by_key(|p| p.1);
    list
  }
}

struct Attachment {
  pos:           P,
  radius_offset: i32,
  double_trunk:  bool,
}

fn attach(pos: P, radius_offset: i32, double_trunk: bool) -> Attachment {
  Attachment { pos, radius_offset, double_trunk }
}

pub struct TreePlacer<'a, 'b, 'c> {
  level:  &'a mut Level<'b>,
  rng:    &'a mut WorldgenRandom,
  cfg:    &'c TreeConfig,
  /// What the trunk placer wrote (logs, and the dirt under them): vanilla's
  /// `set2`.
  logs:   PosSet,
  /// What the foliage placer wrote: `set3`.
  leaves: PosSet,
  /// What the decorators wrote: `set4`.
  decor:  Vec<P>,
}

/// `TreeFeature.place`. Returns whether a tree was placed.
pub fn place_tree(
  level: &mut Level,
  rng: &mut WorldgenRandom,
  cfg: &TreeConfig,
  origin: P,
) -> bool {
  let mut t = TreePlacer {
    level,
    rng,
    cfg,
    logs: PosSet::default(),
    leaves: PosSet::default(),
    decor: Vec::new(),
  };
  let placed = t.do_place(origin);
  if placed && (!t.logs.is_empty() || !t.leaves.is_empty()) {
    if !cfg.decorators.is_empty() {
      let logs = t.logs.decorator_list();
      let leaves = t.leaves.decorator_list();
      for d in &cfg.decorators {
        t.decorate(d, &logs, &leaves);
      }
    }
    shape::update_after_tree(t.level, &t.logs.order, &t.leaves.order, &t.decor);
    true
  } else {
    false
  }
}

impl<'a, 'b, 'c> TreePlacer<'a, 'b, 'c> {
  // --- the level
  // -------------------------------------------------------------------------------

  fn valid_tree_pos(&mut self, p: P) -> bool {
    let b = self.level.get(p.0, p.1, p.2);
    self.level.blocks.valid_tree_pos(b)
  }

  fn is_air(&mut self, p: P) -> bool {
    let b = self.level.get(p.0, p.1, p.2);
    self.level.blocks.is_air(b)
  }

  fn is_free(&mut self, p: P) -> bool {
    if self.valid_tree_pos(p) {
      return true;
    }
    let b = self.level.get(p.0, p.1, p.2);
    self.level.blocks.is_log(b)
  }

  fn is_vine(&mut self, p: P) -> bool {
    let b = self.level.get(p.0, p.1, p.2);
    self.level.blocks.is_vine(b)
  }

  fn put_trunk(&mut self, p: P, b: Block) {
    self.logs.add(p);
    self.level.set(p.0, p.1, p.2, b);
  }

  fn put_leaf(&mut self, p: P, b: Block) {
    self.leaves.add(p);
    self.level.set(p.0, p.1, p.2, b);
  }

  // --- TreeFeature
  // -----------------------------------------------------------------------------

  fn tree_height(&mut self) -> i32 {
    let c = self.cfg;
    c.base_height
      + self.rng.next_int_bound(c.height_rand_a + 1)
      + self.rng.next_int_bound(c.height_rand_b + 1)
  }

  fn do_place(&mut self, origin: P) -> bool {
    let i = self.tree_height();
    let j = self.foliage_height(i);
    let k = i - j;
    let l = self.foliage_radius(k);
    let (min_y, max_y) = (self.level.min_y(), self.level.max_y());
    let top = origin.1 + i + 1;
    if !(origin.1 >= min_y + 1 && top <= max_y) {
      return false;
    }

    let o = self.max_free_tree_height(i, origin);
    let clipped = self.cfg.size.min_clipped_height();
    if !(o >= i || clipped.is_some_and(|c| o >= c)) {
      return false;
    }

    let attachments = self.place_trunk(o, origin);
    for a in &attachments {
      let offset = self.cfg.offset.sample(self.rng);
      self.create_foliage(o, a, j, l, offset);
    }
    true
  }

  fn max_free_tree_height(&mut self, i: i32, origin: P) -> i32 {
    for j in 0..=i + 1 {
      let k = self.cfg.size.size_at_height(i, j);
      for l in -k..=k {
        for m in -k..=k {
          let p = off(origin, l, j, m);
          if !self.is_free(p) || (!self.cfg.ignore_vines && self.is_vine(p)) {
            return j - 2;
          }
        }
      }
    }
    i
  }

  // --- trunks
  // ----------------------------------------------------------------------------------

  fn trunk_block(&self) -> Block { self.cfg.trunk }

  /// `TrunkPlacer.placeLog`.
  fn place_log(&mut self, p: P, axis: Option<u8>) -> bool {
    if !self.valid_tree_pos(p) {
      return false;
    }
    let mut b = self.trunk_block();
    if let Some(a) = axis {
      b.state = a;
    }
    self.put_trunk(p, b);
    true
  }

  fn place_log_if_free(&mut self, p: P) {
    if self.is_free(p) {
      self.place_log(p, None);
    }
  }

  /// `TrunkPlacer.setDirtAt`.
  fn set_dirt_at(&mut self, p: P) {
    let b = self.level.get(p.0, p.1, p.2);
    let blocks = self.level.blocks;
    let is_dirt = blocks.is_dirt(b) && !blocks.is_grass_block(b) && !blocks.is_mycelium(b);
    if self.cfg.force_dirt || !is_dirt {
      let dirt = self.cfg.dirt;
      self.put_trunk(p, dirt);
    }
  }

  fn place_trunk(&mut self, height: i32, pos: P) -> Vec<Attachment> {
    match &self.cfg.trunk_placer {
      TrunkPlacer::Straight => {
        self.set_dirt_at(off(pos, 0, -1, 0));
        for j in 0..height {
          self.place_log(off(pos, 0, j, 0), None);
        }
        vec![attach(off(pos, 0, height, 0), 0, false)]
      }
      TrunkPlacer::Forking => self.place_forking(height, pos),
      TrunkPlacer::Fancy => self.place_fancy(height, pos),
      TrunkPlacer::Giant => self.place_giant(height, pos),
      TrunkPlacer::MegaJungle => self.place_mega_jungle(height, pos),
      TrunkPlacer::DarkOak => self.place_dark_oak(height, pos),
      TrunkPlacer::Cherry { .. } => self.place_cherry(height, pos),
    }
  }

  fn place_forking(&mut self, i: i32, pos: P) -> Vec<Attachment> {
    self.set_dirt_at(off(pos, 0, -1, 0));
    let mut list = Vec::new();
    let direction = random_dir(self.rng);
    let j = i - self.rng.next_int_bound(4) - 1;
    let mut k = 3 - self.rng.next_int_bound(3);
    let (mut l, mut m) = (pos.0, pos.2);
    let (sx, sz) = direction.step();
    let mut top: Option<i32> = None;

    for n in 0..i {
      let o = pos.1 + n;
      if n >= j && k > 0 {
        l += sx;
        m += sz;
        k -= 1;
      }
      if self.place_log((l, o, m), None) {
        top = Some(o + 1);
      }
    }
    if let Some(t) = top {
      list.push(attach((l, t, m), 1, false));
    }

    l = pos.0;
    m = pos.2;
    let direction2 = random_dir(self.rng);
    if direction2 != direction {
      let ox = j - self.rng.next_int_bound(2) - 1;
      let mut p = 1 + self.rng.next_int_bound(3);
      top = None;
      let (sx2, sz2) = direction2.step();
      let mut q = ox;
      while q < i && p > 0 {
        if q >= 1 {
          let r = pos.1 + q;
          l += sx2;
          m += sz2;
          if self.place_log((l, r, m), None) {
            top = Some(r + 1);
          }
        }
        q += 1;
        p -= 1;
      }
      if let Some(t) = top {
        list.push(attach((l, t, m), 0, false));
      }
    }
    list
  }

  fn place_giant(&mut self, i: i32, pos: P) -> Vec<Attachment> {
    let below = off(pos, 0, -1, 0);
    self.set_dirt_at(below);
    self.set_dirt_at(off(below, 1, 0, 0));
    self.set_dirt_at(off(below, 0, 0, 1));
    self.set_dirt_at(off(below, 1, 0, 1));
    for j in 0..i {
      self.place_log_if_free(off(pos, 0, j, 0));
      if j < i - 1 {
        self.place_log_if_free(off(pos, 1, j, 0));
        self.place_log_if_free(off(pos, 1, j, 1));
        self.place_log_if_free(off(pos, 0, j, 1));
      }
    }
    vec![attach(off(pos, 0, i, 0), 0, true)]
  }

  fn place_mega_jungle(&mut self, i: i32, pos: P) -> Vec<Attachment> {
    let mut list = self.place_giant(i, pos);
    let mut j = i - 2 - self.rng.next_int_bound(4);
    while j > i / 2 {
      let f = self.rng.next_float() * TAU;
      let (mut k, mut l) = (0, 0);
      for m in 0..5 {
        k = (1.5f32 + mth_cos(f) * m as f32) as i32;
        l = (1.5f32 + mth_sin(f) * m as f32) as i32;
        self.place_log(off(pos, k, j - 3 + m / 2, l), None);
      }
      list.push(attach(off(pos, k, j, l), -2, false));
      j -= 2 + self.rng.next_int_bound(4);
    }
    list
  }

  fn place_dark_oak(&mut self, i: i32, pos: P) -> Vec<Attachment> {
    let mut list = Vec::new();
    let below = off(pos, 0, -1, 0);
    self.set_dirt_at(below);
    self.set_dirt_at(off(below, 1, 0, 0));
    self.set_dirt_at(off(below, 0, 0, 1));
    self.set_dirt_at(off(below, 1, 0, 1));

    let direction = random_dir(self.rng);
    let j = i - self.rng.next_int_bound(4);
    let mut k = 2 - self.rng.next_int_bound(3);
    let (l, m, n) = pos;
    let (mut o, mut p) = (l, n);
    let q = m + i - 1;
    let (sx, sz) = direction.step();

    for r in 0..i {
      if r >= j && k > 0 {
        o += sx;
        p += sz;
        k -= 1;
      }
      let s = m + r;
      let at = (o, s, p);
      let b = self.level.get(at.0, at.1, at.2);
      if self.level.blocks.is_air_or_leaves(b) {
        self.place_log(at, None);
        self.place_log(off(at, 1, 0, 0), None);
        self.place_log(off(at, 0, 0, 1), None);
        self.place_log(off(at, 1, 0, 1), None);
      }
    }
    list.push(attach((o, q, p), 0, true));

    for r in -1..=2 {
      for s in -1..=2 {
        if (r < 0 || r > 1 || s < 0 || s > 1) && self.rng.next_int_bound(3) <= 0 {
          let t = self.rng.next_int_bound(3) + 2;
          for u in 0..t {
            self.place_log((l + r, q - u - 1, n + s), None);
          }
          list.push(attach((o + r, q, p + s), 0, false));
        }
      }
    }
    list
  }

  fn place_cherry(&mut self, i: i32, pos: P) -> Vec<Attachment> {
    let TrunkPlacer::Cherry {
      branch_count,
      branch_horizontal_length,
      branch_start_offset_from_top,
      branch_end_offset_from_top,
    } = self.cfg.trunk_placer.clone()
    else {
      unreachable!()
    };
    let (lo, hi) = branch_start_offset_from_top;

    self.set_dirt_at(off(pos, 0, -1, 0));
    let j = 0.max(i - 1 + self.rng.between(lo, hi));
    let mut k = 0.max(i - 1 + self.rng.between(lo, hi - 1));
    if k >= j {
      k += 1;
    }
    let l = branch_count.sample(self.rng);
    let bl = l == 3;
    let bl2 = l >= 2;
    let m = if bl {
      i
    } else if bl2 {
      j.max(k) + 1
    } else {
      j + 1
    };

    for n in 0..m {
      self.place_log(off(pos, 0, n, 0), None);
    }

    let mut list = Vec::new();
    if bl {
      list.push(attach(off(pos, 0, m, 0), 0, false));
    }
    let direction = random_dir(self.rng);
    let axis = direction.axis_state();
    list.push(self.cherry_branch(
      i,
      pos,
      axis,
      direction,
      j,
      j < m - 1,
      &branch_horizontal_length,
      &branch_end_offset_from_top,
    ));
    if bl2 {
      list.push(self.cherry_branch(
        i,
        pos,
        axis,
        direction.opposite(),
        k,
        k < m - 1,
        &branch_horizontal_length,
        &branch_end_offset_from_top,
      ));
    }
    list
  }

  #[allow(clippy::too_many_arguments)]
  fn cherry_branch(
    &mut self,
    i: i32,
    pos: P,
    axis: u8,
    direction: Dir,
    j: i32,
    bl: bool,
    horizontal: &IntProvider,
    end_offset: &IntProvider,
  ) -> Attachment {
    let mut cur = off(pos, 0, j, 0);
    let k = i - 1 + end_offset.sample(self.rng);
    let bl2 = bl || k < j;
    let l = horizontal.sample(self.rng) + if bl2 { 1 } else { 0 };
    let target = off(rel(pos, direction, l), 0, k, 0);
    let m = if bl2 { 2 } else { 1 };
    for _ in 0..m {
      cur = rel(cur, direction, 1);
      self.place_log(cur, Some(axis));
    }
    let vertical = if target.1 > cur.1 { 1 } else { -1 };
    loop {
      let o = manhattan(cur, target);
      if o == 0 {
        return attach(off(target, 0, 1, 0), 0, false);
      }
      let f = (target.1 - cur.1).abs() as f32 / o as f32;
      let go_vertical = self.rng.next_float() < f;
      if go_vertical {
        cur = off(cur, 0, vertical, 0);
        self.place_log(cur, None);
      } else {
        cur = rel(cur, direction, 1);
        self.place_log(cur, Some(axis));
      }
    }
  }

  fn place_fancy(&mut self, height: i32, pos: P) -> Vec<Attachment> {
    // (attachment position, branch base)
    let k = height + 2;
    let l = floor_d(k as f64 * 0.618);
    self.set_dirt_at(off(pos, 0, -1, 0));
    let ratio = 1.0 * k as f64 / 13.0;
    let m = 1.min(floor_d(1.382 + ratio * ratio));
    let n = pos.1 + l;
    let mut o = k - 5;

    let mut list: Vec<(P, i32)> = vec![(off(pos, 0, o, 0), n)];
    while o >= 0 {
      let f = tree_shape(k, o);
      if !(f < 0.0) {
        for _ in 0..m {
          let g = 1.0 * f as f64 * (self.rng.next_float() as f64 + 0.328);
          let h = (self.rng.next_float() * 2.0f32) as f64 * PI;
          let q = g * h.sin() + 0.5;
          let r = g * h.cos() + 0.5;
          let p2 = off(pos, floor_d(q), o - 1, floor_d(r));
          let p3 = off(p2, 0, 5, 0);
          if self.make_limb(p2, p3, false) {
            let s = pos.0 - p2.0;
            let t = pos.2 - p2.2;
            let u = p2.1 as f64 - ((s * s + t * t) as f64).sqrt() * 0.381;
            let v = if u > n as f64 { n } else { u as i32 };
            let p4 = (pos.0, v, pos.2);
            if self.make_limb(p4, p2, false) {
              list.push((p2, p4.1));
            }
          }
        }
      }
      o -= 1;
    }

    self.make_limb(pos, off(pos, 0, l, 0), true);

    // makeBranches
    for &(att, base) in &list {
      let p2 = (pos.0, base, pos.2);
      if p2 != att && trim_branches(k, base - pos.1) {
        self.make_limb(p2, att, true);
      }
    }

    list
      .iter()
      .filter(|&&(_, base)| trim_branches(k, base - pos.1))
      .map(|&(att, _)| attach(att, 0, false))
      .collect()
  }

  fn make_limb(&mut self, from: P, to: P, place: bool) -> bool {
    if !place && from == to {
      return true;
    }
    let d = (to.0 - from.0, to.1 - from.1, to.2 - from.2);
    let steps = d.0.abs().max(d.1.abs()).max(d.2.abs());
    let f = d.0 as f32 / steps as f32;
    let g = d.1 as f32 / steps as f32;
    let h = d.2 as f32 / steps as f32;
    for j in 0..=steps {
      let jf = j as f32;
      let p =
        off(from, floor_f(0.5f32 + jf * f), floor_f(0.5f32 + jf * g), floor_f(0.5f32 + jf * h));
      if place {
        let ax = (p.0 - from.0).abs();
        let az = (p.2 - from.2).abs();
        let k = ax.max(az);
        let axis = if k > 0 {
          if ax == k {
            1
          } else {
            2
          }
        } else {
          0
        };
        self.place_log(p, Some(axis));
      } else if !self.is_free(p) {
        return false;
      }
    }
    true
  }

  // --- foliage
  // ---------------------------------------------------------------------------------

  fn foliage_height(&mut self, tree_height: i32) -> i32 {
    match &self.cfg.foliage_placer {
      FoliagePlacer::Blob { height }
      | FoliagePlacer::Fancy { height }
      | FoliagePlacer::Bush { height }
      | FoliagePlacer::MegaJungle { height } => *height,
      FoliagePlacer::Spruce { trunk_height } => 4.max(tree_height - trunk_height.sample(self.rng)),
      FoliagePlacer::Pine { height } => height.sample(self.rng),
      FoliagePlacer::MegaPine { crown_height } => crown_height.sample(self.rng),
      FoliagePlacer::Cherry { height, .. } => height.sample(self.rng),
      FoliagePlacer::Acacia => 0,
      FoliagePlacer::DarkOak => 4,
    }
  }

  fn foliage_radius(&mut self, k: i32) -> i32 {
    let base = self.cfg.radius.sample(self.rng);
    if matches!(self.cfg.foliage_placer, FoliagePlacer::Pine { .. }) {
      base + self.rng.next_int_bound((k + 1).max(1))
    } else {
      base
    }
  }

  /// `FoliagePlacer.shouldSkipLocation`.
  fn skip(&mut self, i: i32, j: i32, k: i32, l: i32, bl: bool) -> bool {
    match &self.cfg.foliage_placer {
      FoliagePlacer::Blob { .. } => i == l && k == l && (self.rng.next_int_bound(2) == 0 || j == 0),
      FoliagePlacer::MegaJungle { .. } | FoliagePlacer::MegaPine { .. } => {
        if i + k >= 7 {
          true
        } else {
          i * i + k * k > l * l
        }
      }
      FoliagePlacer::Fancy { .. } => {
        square_f(i as f32 + 0.5) + square_f(k as f32 + 0.5) > (l * l) as f32
      }
      FoliagePlacer::Bush { .. } => i == l && k == l && self.rng.next_int_bound(2) == 0,
      FoliagePlacer::Spruce { .. } | FoliagePlacer::Pine { .. } => i == l && k == l && l > 0,
      FoliagePlacer::Acacia => {
        if j == 0 {
          (i > 1 || k > 1) && i != 0 && k != 0
        } else {
          i == l && k == l && l > 0
        }
      }
      FoliagePlacer::DarkOak => {
        if j == -1 && !bl {
          i == l && k == l
        } else if j == 1 {
          i + k > l * 2 - 2
        } else {
          false
        }
      }
      FoliagePlacer::Cherry { wide_bottom_layer_hole_chance, corner_hole_chance, .. } => {
        let (wide, corner) = (*wide_bottom_layer_hole_chance, *corner_hole_chance);
        if j == -1 && (i == l || k == l) && self.rng.next_float() < wide {
          true
        } else {
          let bl2 = i == l && k == l;
          let bl3 = l > 2;
          if bl3 {
            bl2 || (i + k > l * 2 - 2 && self.rng.next_float() < corner)
          } else {
            bl2 && self.rng.next_float() < corner
          }
        }
      }
    }
  }

  /// `FoliagePlacer.shouldSkipLocationSigned`.
  fn skip_signed(&mut self, i: i32, j: i32, k: i32, l: i32, bl: bool) -> bool {
    if matches!(self.cfg.foliage_placer, FoliagePlacer::DarkOak)
      && !(j != 0 || !bl || (i != -l && i < l) || (k != -l && k < l))
    {
      return true;
    }
    let (m, n) = if bl {
      (i.abs().min((i - 1).abs()), k.abs().min((k - 1).abs()))
    } else {
      (i.abs(), k.abs())
    };
    self.skip(m, j, n, l, bl)
  }

  fn try_place_leaf(&mut self, p: P) -> bool {
    if !self.valid_tree_pos(p) {
      return false;
    }
    let leaf = self.cfg.foliage;
    self.put_leaf(p, leaf);
    true
  }

  fn place_leaves_row(&mut self, center: P, i: i32, j: i32, bl: bool) {
    let k = if bl { 1 } else { 0 };
    for l in -i..=i + k {
      for m in -i..=i + k {
        if !self.skip_signed(l, j, m, i, bl) {
          self.try_place_leaf(off(center, l, j, m));
        }
      }
    }
  }

  fn try_place_extension(&mut self, chance: f32, anchor: P, p: P) -> bool {
    if manhattan(p, anchor) >= 7 {
      return false;
    }
    if self.rng.next_float() > chance {
      return false;
    }
    self.try_place_leaf(p)
  }

  fn place_leaves_row_with_hanging_below(
    &mut self,
    center: P,
    i: i32,
    j: i32,
    bl: bool,
    f: f32,
    g: f32,
  ) {
    self.place_leaves_row(center, i, j, bl);
    let k = if bl { 1 } else { 0 };
    let below = off(center, 0, -1, 0);
    for direction in HORIZONTAL {
      let d2 = direction.clockwise();
      let l = if d2.positive() { i + k } else { i };
      let mut m = off(center, 0, j - 1, 0);
      m = rel(m, d2, l);
      m = rel(m, direction, -i);
      let mut n = -i;
      while n < i + k {
        let bl2 = self.leaves.contains(off(m, 0, 1, 0));
        if bl2 && self.try_place_extension(f, below, m) {
          let down = off(m, 0, -1, 0);
          self.try_place_extension(g, below, down);
        }
        n += 1;
        m = rel(m, direction, 1);
      }
    }
  }

  fn create_foliage(&mut self, tree_height: i32, a: &Attachment, j: i32, k: i32, l: i32) {
    let _ = tree_height;
    let bl = a.double_trunk;
    match self.cfg.foliage_placer.clone() {
      FoliagePlacer::Blob { .. } => {
        let mut m = l;
        while m >= l - j {
          let n = (k + a.radius_offset - 1 - m / 2).max(0);
          self.place_leaves_row(a.pos, n, m, bl);
          m -= 1;
        }
      }
      FoliagePlacer::Fancy { .. } => {
        let mut m = l;
        while m >= l - j {
          let n = k + if m != l && m != l - j { 1 } else { 0 };
          self.place_leaves_row(a.pos, n, m, bl);
          m -= 1;
        }
      }
      FoliagePlacer::Bush { .. } => {
        let mut m = l;
        while m >= l - j {
          let n = k + a.radius_offset - 1 - m;
          self.place_leaves_row(a.pos, n, m, bl);
          m -= 1;
        }
      }
      FoliagePlacer::Spruce { .. } => {
        let mut m = self.rng.next_int_bound(2);
        let mut n = 1;
        let mut o = 0;
        let mut p = l;
        while p >= -j {
          self.place_leaves_row(a.pos, m, p, bl);
          if m >= n {
            m = o;
            o = 1;
            n = (n + 1).min(k + a.radius_offset);
          } else {
            m += 1;
          }
          p -= 1;
        }
      }
      FoliagePlacer::Pine { .. } => {
        let mut m = 0;
        let mut n = l;
        while n >= l - j {
          self.place_leaves_row(a.pos, m, n, bl);
          if m >= 1 && n == l - j + 1 {
            m -= 1;
          } else if m < k + a.radius_offset {
            m += 1;
          }
          n -= 1;
        }
      }
      FoliagePlacer::Acacia => {
        let at = off(a.pos, 0, l, 0);
        self.place_leaves_row(at, k + a.radius_offset, -1 - j, bl);
        self.place_leaves_row(at, k - 1, -j, bl);
        self.place_leaves_row(at, k + a.radius_offset - 1, 0, bl);
      }
      FoliagePlacer::DarkOak => {
        let at = off(a.pos, 0, l, 0);
        if bl {
          self.place_leaves_row(at, k + 2, -1, bl);
          self.place_leaves_row(at, k + 3, 0, bl);
          self.place_leaves_row(at, k + 2, 1, bl);
          if self.rng.next_bool() {
            self.place_leaves_row(at, k, 2, bl);
          }
        } else {
          self.place_leaves_row(at, k + 2, -1, bl);
          self.place_leaves_row(at, k + 1, 0, bl);
        }
      }
      FoliagePlacer::MegaPine { .. } => {
        let pos = a.pos;
        let mut m = 0;
        let mut n = pos.1 - j + l;
        while n <= pos.1 + l {
          let o = pos.1 - n;
          let p = k + a.radius_offset + floor_f(o as f32 / j as f32 * 3.5f32);
          let q = if o > 0 && p == m && (n & 1) == 0 { p + 1 } else { p };
          self.place_leaves_row((pos.0, n, pos.2), q, 0, bl);
          m = p;
          n += 1;
        }
      }
      FoliagePlacer::MegaJungle { .. } => {
        let m = if a.double_trunk { j } else { 1 + self.rng.next_int_bound(2) };
        let mut n = l;
        while n >= l - m {
          let o = k + a.radius_offset + 1 - n;
          self.place_leaves_row(a.pos, o, n, bl);
          n -= 1;
        }
      }
      FoliagePlacer::Cherry { hanging_leaves_chance, hanging_leaves_extension_chance, .. } => {
        let at = off(a.pos, 0, l, 0);
        let m = k + a.radius_offset - 1;
        self.place_leaves_row(at, m - 2, j - 3, bl);
        self.place_leaves_row(at, m - 1, j - 4, bl);
        let mut n = j - 5;
        while n >= 0 {
          self.place_leaves_row(at, m, n, bl);
          n -= 1;
        }
        self.place_leaves_row_with_hanging_below(
          at,
          m,
          -1,
          bl,
          hanging_leaves_chance,
          hanging_leaves_extension_chance,
        );
        self.place_leaves_row_with_hanging_below(
          at,
          m - 1,
          -2,
          bl,
          hanging_leaves_chance,
          hanging_leaves_extension_chance,
        );
      }
    }
  }

  // --- decorators
  // ------------------------------------------------------------------------------

  fn set_decoration(&mut self, p: P, b: Block) {
    self.decor.push(p);
    self.level.set(p.0, p.1, p.2, b);
  }

  fn place_vine(&mut self, p: P, face: u8) { self.set_decoration(p, Block::with(VINE, face)); }

  fn decorate(&mut self, d: &Decorator, logs: &[P], leaves: &[P]) {
    match d {
      Decorator::LeaveVine { probability } => {
        let p = *probability;
        for &pos in leaves {
          for dir in [Dir::West, Dir::East, Dir::North, Dir::South] {
            if self.rng.next_float() < p {
              let at = rel(pos, dir, 1);
              if self.is_air(at) {
                self.add_hanging_vine(at, dir.opposite().vine_face());
              }
            }
          }
        }
      }
      Decorator::TrunkVine => {
        for &pos in logs {
          for dir in [Dir::West, Dir::East, Dir::North, Dir::South] {
            if self.rng.next_int_bound(3) > 0 {
              let at = rel(pos, dir, 1);
              if self.is_air(at) {
                self.place_vine(at, dir.opposite().vine_face());
              }
            }
          }
        }
      }
      Decorator::Cocoa { probability } => {
        if self.rng.next_float() >= *probability {
          return;
        }
        let Some(first) = logs.first() else { return };
        let base = first.1;
        for &pos in logs.iter().filter(|p| p.1 - base <= 2) {
          for dir in HORIZONTAL {
            if self.rng.next_float() <= 0.25f32 {
              let at = rel(pos, dir.opposite(), 1);
              if self.is_air(at) {
                let age = self.rng.next_int_bound(3) as u8;
                self.set_decoration(at, Block::with(COCOA, (age << 2) | dir.data_2d()));
              }
            }
          }
        }
      }
      Decorator::Beehive { probability } => self.beehive(*probability, logs, leaves),
      Decorator::AlterGround { provider } => {
        // The roots list is always empty here, so the logs (and the dirt under
        // them) are used.
        let Some(first) = logs.first() else { return };
        let y = first.1;
        for &pos in logs.iter().filter(|p| p.1 == y) {
          self.place_circle(off(pos, -1, 0, -1), *provider);
          self.place_circle(off(pos, 2, 0, -1), *provider);
          self.place_circle(off(pos, -1, 0, 2), *provider);
          self.place_circle(off(pos, 2, 0, 2), *provider);
          for _ in 0..5 {
            let j = self.rng.next_int_bound(64);
            let k = j % 8;
            let l = j / 8;
            if k == 0 || k == 7 || l == 0 || l == 7 {
              self.place_circle(off(pos, -3 + k, 0, -3 + l), *provider);
            }
          }
        }
      }
    }
  }

  fn add_hanging_vine(&mut self, p: P, face: u8) {
    self.place_vine(p, face);
    let mut i = 4;
    let mut cur = off(p, 0, -1, 0);
    while self.is_air(cur) && i > 0 {
      self.place_vine(cur, face);
      cur = off(cur, 0, -1, 0);
      i -= 1;
    }
  }

  fn place_circle(&mut self, p: P, provider: Block) {
    for i in -2..=2i32 {
      for j in -2..=2i32 {
        if i.abs() != 2 || j.abs() != 2 {
          self.place_block_at(off(p, i, 0, j), provider);
        }
      }
    }
  }

  fn place_block_at(&mut self, p: P, provider: Block) {
    for i in (-3..=2).rev() {
      let at = off(p, 0, i, 0);
      let b = self.level.get(at.0, at.1, at.2);
      if self.level.blocks.is_dirt(b) {
        self.set_decoration(at, provider);
        break;
      }
      if !self.level.blocks.is_air(b) && i < 0 {
        break;
      }
    }
  }

  fn beehive(&mut self, probability: f32, logs: &[P], leaves: &[P]) {
    if self.rng.next_float() >= probability {
      return;
    }
    let (Some(first_log), Some(last_log)) = (logs.first(), logs.last()) else { return };
    let y = if let Some(first_leaf) = leaves.first() {
      (first_leaf.1 - 1).max(first_log.1 + 1)
    } else {
      (first_log.1 + 1 + self.rng.next_int_bound(3)).min(last_log.1)
    };

    // The directions a nest can sit in, around a log: all but the one it faces
    // back at.
    let spawn = [Dir::East, Dir::South, Dir::West];
    let mut candidates = Vec::new();
    for &log in logs.iter().filter(|p| p.1 == y) {
      for d in spawn {
        candidates.push(rel(log, d, 1));
      }
    }
    if candidates.is_empty() {
      return;
    }
    // Vanilla shuffles the candidates with an unseeded `Random`, so which free
    // one wins cannot be predicted; the first stands in.
    let found =
      candidates.into_iter().find(|&c| self.is_air(c) && self.is_air(rel(c, Dir::South, 1)));
    if let Some(at) = found {
      self.set_decoration(at, Block::with(super::block::BEE_NEST, 0));
      let bees = 2 + self.rng.next_int_bound(2);
      for _ in 0..bees {
        self.rng.next_int_bound(599);
      }
    }
  }
}

fn square_f(f: f32) -> f32 { f * f }

/// `FancyTrunkPlacer.treeShape`.
fn tree_shape(i: i32, j: i32) -> f32 {
  if (j as f32) < i as f32 * 0.3f32 {
    return -1.0;
  }
  let f = i as f32 / 2.0f32;
  let g = f - j as f32;
  let mut h = ((f * f - g * g) as f64).sqrt() as f32;
  if g == 0.0 {
    h = f;
  } else if g.abs() >= f {
    return 0.0;
  }
  h * 0.5f32
}

/// `FancyTrunkPlacer.trimBranches`.
fn trim_branches(i: i32, j: i32) -> bool { j as f64 >= i as f64 * 0.2 }
