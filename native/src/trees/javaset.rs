//! The order a `java.util.HashSet<BlockPos>` iterates in.
//!
//! Vanilla hands the logs and leaves of a tree to its decorators as lists
//! copied out of hash sets and sorted by height only, so the order inside a
//! height is the hash set's iteration order. The decorators draw random numbers
//! per entry in that order, so it decides where vines hang and which logs get
//! cocoa. The order is a function of the insertion order alone: entries sit in
//! the bucket their spread hash picks, in the order they were added, and
//! buckets are visited in index order.
//!
//! One thing is not modelled: a bucket that grows to more than 8 entries in a
//! table of at least 64 is turned into a tree, which moves its root to the
//! front. That needs 9 positions with colliding hashes, which a tree's shape
//! does not produce.

/// `BlockPos.hashCode`.
fn hash(p: (i32, i32, i32)) -> i32 {
  p.1.wrapping_add(p.2.wrapping_mul(31)).wrapping_mul(31).wrapping_add(p.0)
}

/// `HashMap.hash`: the high bits folded into the low ones.
fn spread(h: i32) -> u32 {
  let h = h as u32;
  h ^ (h >> 16)
}

/// The indices of `items` (distinct, in insertion order) in the order a
/// `HashSet` holding them iterates.
pub fn iteration_order(items: &[(i32, i32, i32)]) -> Vec<usize> {
  let spreads: Vec<u32> = items.iter().map(|&p| spread(hash(p))).collect();

  // Replay the insertions to know the table size, which depends on when it
  // resized.
  let mut capacity = 16usize;
  let mut counts = vec![0u32; capacity];
  for (n, &s) in spreads.iter().enumerate() {
    let size = n + 1;
    let bucket = s as usize & (capacity - 1);
    counts[bucket] += 1;
    // A chain of 9 in a table under 64 grows the table instead of becoming a
    // tree.
    if counts[bucket] >= 9 && capacity < 64 {
      capacity *= 2;
      counts = recount(&spreads[..size], capacity);
    }
    if size > capacity * 3 / 4 {
      capacity *= 2;
      counts = recount(&spreads[..size], capacity);
    }
  }

  let mut order: Vec<usize> = (0..items.len()).collect();
  order.sort_by_key(|&i| spreads[i] as usize & (capacity - 1));
  order
}

fn recount(spreads: &[u32], capacity: usize) -> Vec<u32> {
  let mut counts = vec![0u32; capacity];
  for &s in spreads {
    counts[s as usize & (capacity - 1)] += 1;
  }
  counts
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn small_sets_iterate_by_bucket() {
    // Hashes of (x, 0, 0) are x, so with 16 buckets they iterate by x mod 16.
    let items = [(5, 0, 0), (3, 0, 0), (21, 0, 0), (0, 0, 0)];
    let order: Vec<_> = iteration_order(&items).into_iter().map(|i| items[i]).collect();
    assert_eq!(order, vec![(0, 0, 0), (3, 0, 0), (5, 0, 0), (21, 0, 0)]);
  }

  #[test]
  fn the_table_grows_past_three_quarters() {
    // 13 entries do not fit in 16 buckets at a load of 0.75, so there are 32,
    // and 17 lands in its own bucket after 12 instead of next to 1.
    let items: Vec<_> =
      [17, 3, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12].iter().map(|&x| (x, 0, 0)).collect();
    let order: Vec<_> = iteration_order(&items).into_iter().map(|i| items[i].0).collect();
    assert_eq!(order, vec![1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 17]);
  }
}
