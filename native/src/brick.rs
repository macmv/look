//! Pure voxel-brick logic. No JNI in here so it can be unit tested and
//! benchmarked on its own.

/// Edge length of a brick, in voxels. One brick is one 16x16x16 chunk section.
pub const BRICK: usize = 16;
/// Number of voxels in a brick.
pub const BRICK_VOLUME: usize = BRICK * BRICK * BRICK;
/// Edge length of an occupancy cell, in voxels. Each cell is a 4x4x4 block
/// tracked by a u64 mask.
pub const CELL: usize = 4;
/// Cells per brick edge.
pub const CELLS: usize = BRICK / CELL;
/// Number of occupancy cells in a brick.
pub const CELL_COUNT: usize = CELLS * CELLS * CELLS;

/// Block id 0 is air; everything else is solid for occupancy purposes.
pub const AIR: u16 = 0;

/// Index of a voxel inside a brick. Layout is x fastest, then z, then y (same
/// as vanilla sections).
#[inline]
pub fn voxel_index(x: usize, y: usize, z: usize) -> usize { (y * BRICK + z) * BRICK + x }

/// Builds one 64-bit occupancy mask per 4x4x4 cell. Bit `(y * 4 + z) * 4 + x`
/// of a cell mask is set when that voxel is not air. Cells are laid out in the
/// same x/z/y order as voxels.
pub fn build_occupancy(ids: &[u16; BRICK_VOLUME], out: &mut [u64; CELL_COUNT]) {
  out.fill(0);

  for y in 0..BRICK {
    for z in 0..BRICK {
      for x in 0..BRICK {
        if ids[voxel_index(x, y, z)] == AIR {
          continue;
        }

        let cell = ((y / CELL) * CELLS + z / CELL) * CELLS + x / CELL;
        let bit = ((y % CELL) * CELL + z % CELL) * CELL + x % CELL;
        out[cell] |= 1u64 << bit;
      }
    }
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn empty_brick_has_no_bits() {
    let ids = [AIR; BRICK_VOLUME];
    let mut out = [u64::MAX; CELL_COUNT];
    build_occupancy(&ids, &mut out);
    assert!(out.iter().all(|&m| m == 0));
  }

  #[test]
  fn full_brick_sets_every_bit() {
    let ids = [1u16; BRICK_VOLUME];
    let mut out = [0; CELL_COUNT];
    build_occupancy(&ids, &mut out);
    assert!(out.iter().all(|&m| m == u64::MAX));
  }

  #[test]
  fn single_voxel_lands_in_the_right_cell_and_bit() {
    let mut ids = [AIR; BRICK_VOLUME];
    ids[voxel_index(5, 9, 2)] = 7;
    let mut out = [0; CELL_COUNT];
    build_occupancy(&ids, &mut out);

    // x=5 -> cell 1 bit 1, y=9 -> cell 2 bit 1, z=2 -> cell 0 bit 2.
    let cell = (2 * CELLS + 0) * CELLS + 1;
    let bit = (1 * CELL + 2) * CELL + 1;
    assert_eq!(out[cell], 1u64 << bit);
    assert_eq!(out.iter().filter(|&&m| m != 0).count(), 1);
  }
}
