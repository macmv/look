//! What `TreeFeature.place` does after a tree is down: `updateLeaves` works out
//! which blocks around the tree hold together (the logs, the leaves within 6
//! steps of one, whatever the decorators added) and
//! `StructureTemplate.updateShapeAtEdge` then re-evaluates every block on the
//! outside of that shape against its neighbors. The only blocks the trees make
//! that react are vines, which disappear when nothing holds them, and cocoa,
//! which needs a jungle log; but a vine that a later tree's leaves cut off from
//! the one above it is still there when the next tree is checked for
//! free space, so it decides how tall the next jungle tree may grow, and with
//! it every draw after.

use super::{
  block::{vine, Block, AIR, VINE},
  tree::P,
  world::Level,
};

/// `Direction.values()`: down, up, north, south, west, east.
const DIRECTIONS: [P; 6] = [(0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0)];

const DOWN: P = (0, -1, 0);

fn add(a: P, b: P) -> P { (a.0 + b.0, a.1 + b.1, a.2 + b.2) }

fn neg(a: P) -> P { (-a.0, -a.1, -a.2) }

/// `LeavesBlock.getOptionalDistanceAt`: logs are 0, leaves carry their distance
/// in their state.
fn distance_at(level: &Level, b: Block) -> Option<i32> {
  if level.blocks.is_log(b) {
    Some(0)
  } else if level.blocks.is_leaves(b) {
    Some(b.state as i32)
  } else {
    None
  }
}

struct Shape {
  min:  P,
  size: P,
  full: Vec<bool>,
}

impl Shape {
  fn index(&self, x: i32, y: i32, z: i32) -> usize {
    ((y * self.size.2 + z) * self.size.0 + x) as usize
  }

  fn inside(&self, p: P) -> bool {
    let (x, y, z) = (p.0 - self.min.0, p.1 - self.min.1, p.2 - self.min.2);
    x >= 0 && y >= 0 && z >= 0 && x < self.size.0 && y < self.size.1 && z < self.size.2
  }

  fn fill(&mut self, p: P) {
    let i = self.index(p.0 - self.min.0, p.1 - self.min.1, p.2 - self.min.2);
    self.full[i] = true;
  }

  fn is_full(&self, p: P) -> bool {
    self.full[self.index(p.0 - self.min.0, p.1 - self.min.1, p.2 - self.min.2)]
  }
}

/// `TreeFeature`'s tail: `logs` are what the trunk placer wrote, `leaves` what
/// the foliage placer wrote, `decorations` what the decorators added.
pub fn update_after_tree(level: &mut Level, logs: &[P], leaves: &[P], decorations: &[P]) {
  let mut all = logs.iter().chain(leaves).chain(decorations);
  let Some(&first) = all.next() else { return };
  let (mut lo, mut hi) = (first, first);
  for &p in all {
    lo = (lo.0.min(p.0), lo.1.min(p.1), lo.2.min(p.2));
    hi = (hi.0.max(p.0), hi.1.max(p.1), hi.2.max(p.2));
  }
  let size = (hi.0 - lo.0 + 1, hi.1 - lo.1 + 1, hi.2 - lo.2 + 1);
  let mut shape = Shape { min: lo, size, full: vec![false; (size.0 * size.1 * size.2) as usize] };

  update_leaves(level, &mut shape, logs, decorations);
  update_shape_at_edge(level, &shape);
}

/// `TreeFeature.updateLeaves`: fills the shape with the logs and every leaf
/// that connects to one through at most 6 steps, and writes the leaves'
/// distances on the way.
fn update_leaves(level: &mut Level, shape: &mut Shape, logs: &[P], decorations: &[P]) {
  for &p in decorations {
    if shape.inside(p) {
      shape.fill(p);
    }
  }

  let mut buckets: Vec<Vec<P>> = vec![Vec::new(); 7];
  for &p in logs {
    if !buckets[0].contains(&p) {
      buckets[0].push(p);
    }
  }

  let mut k = 0usize;
  loop {
    while k >= 7 || !buckets[k].is_empty() {
      if k >= 7 {
        return;
      }
      let p = buckets[k].remove(0);
      if !shape.inside(p) {
        continue;
      }
      if k != 0 {
        let b = level.get(p.0, p.1, p.2);
        level.set(p.0, p.1, p.2, Block::with(b.id, k as u8));
      }
      shape.fill(p);

      for d in DIRECTIONS {
        let n = add(p, d);
        if shape.inside(n) && !shape.is_full(n) {
          let b = level.get(n.0, n.1, n.2);
          if let Some(od) = distance_at(level, b) {
            let o = od.min(k as i32 + 1);
            if o < 7 {
              let o = o as usize;
              if !buckets[o].contains(&n) {
                buckets[o].push(n);
              }
              k = k.min(o);
            }
          }
        }
      }
    }
    k += 1;
  }
}

/// `StructureTemplate.updateShapeAtEdge` with `DiscreteVoxelShape.forAllFaces`:
/// for every face between a filled cell and an empty one (or the edge of the
/// shape), lets both blocks react to the other being there.
fn update_shape_at_edge(level: &mut Level, shape: &Shape) {
  let (sx, sy, sz) = shape.size;
  let at = |x: i32, y: i32, z: i32| shape.full[shape.index(x, y, z)];

  // Each pass walks lines along one axis; which axis, and how the loop
  // variables map onto x, y and z, follow `AxisCycle` (none, then forward,
  // then backward).
  let mut faces: Vec<(P, P)> = Vec::new();
  let mut walk =
    |dims: (i32, i32, i32), cell: &dyn Fn(i32, i32, i32) -> P, negative: P, positive: P| {
      for l in 0..dims.0 {
        for m in 0..dims.1 {
          let mut inside = false;
          for n in 0..=dims.2 {
            let c = if n != dims.2 { cell(l, m, n) } else { (0, 0, 0) };
            let full = n != dims.2 && at(c.0, c.1, c.2);
            if !inside && full {
              faces.push((negative, c));
            }
            if inside && !full {
              faces.push((positive, cell(l, m, n - 1)));
            }
            inside = full;
          }
        }
      }
    };
  walk((sx, sy, sz), &|l, m, n| (l, m, n), (0, 0, -1), (0, 0, 1));
  walk((sz, sx, sy), &|l, m, n| (m, n, l), (0, -1, 0), (0, 1, 0));
  walk((sy, sz, sx), &|l, m, n| (n, l, m), (-1, 0, 0), (1, 0, 0));

  for (dir, c) in faces {
    let pos = (shape.min.0 + c.0, shape.min.1 + c.1, shape.min.2 + c.2);
    let pos2 = add(pos, dir);
    let s1 = level.get(pos.0, pos.1, pos.2);
    let s2 = level.get(pos2.0, pos2.1, pos2.2);
    let s3 = updated(level, s1, dir, pos);
    if s1 != s3 {
      level.set(pos.0, pos.1, pos.2, s3);
    }
    let s4 = updated(level, s2, neg(dir), pos2);
    if s2 != s4 {
      level.set(pos2.0, pos2.1, pos2.2, s4);
    }
  }
}

/// `BlockState.updateShape` for the blocks that react: `dir` points from the
/// block to the neighbor that changed.
fn updated(level: &mut Level, b: Block, dir: P, pos: P) -> Block {
  if b.id == VINE {
    if dir == DOWN {
      return b;
    }
    let u = vine_state(level, b, pos);
    return if u.state == 0 { Block::new(AIR) } else { u };
  }
  if level.blocks.name(b.id) == "minecraft:cocoa" {
    let facing = match b.state & 3 {
      0 => (0, 0, 1),
      1 => (-1, 0, 0),
      2 => (0, 0, -1),
      _ => (1, 0, 0),
    };
    if dir == facing {
      let support = level.get(pos.0 + facing.0, pos.1, pos.2 + facing.2);
      if !level.blocks.is_jungle_log(support) {
        return Block::new(AIR);
      }
    }
  }
  b
}

/// `VineBlock.getUpdatedState`: drops every face with nothing to hold it.
fn vine_state(level: &mut Level, b: Block, pos: P) -> Block {
  let above = (pos.0, pos.1 + 1, pos.2);
  let above_block = level.get(above.0, above.1, above.2);
  let mut state = b.state;

  if state & vine::UP != 0 && !level.blocks.blocks_motion(above_block) {
    state &= !vine::UP;
  }
  for (bit, d) in [
    (vine::NORTH, (0, 0, -1)),
    (vine::EAST, (1, 0, 0)),
    (vine::SOUTH, (0, 0, 1)),
    (vine::WEST, (-1, 0, 0)),
  ] {
    if state & bit == 0 {
      continue;
    }
    let wall = level.get(pos.0 + d.0, pos.1, pos.2 + d.2);
    let held =
      level.blocks.blocks_motion(wall) || (above_block.id == VINE && above_block.state & bit != 0);
    if !held {
      state &= !bit;
    }
  }
  Block::with(b.id, state)
}
