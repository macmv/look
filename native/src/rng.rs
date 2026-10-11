//! Minecraft's Xoroshiro128++ random source and the rules that turn a world seed into per-noise
//! generators. Everything here has to match the game bit for bit, or every noise value (and so the
//! whole terrain) comes out different; `tests/oracle.rs` checks it against values from vanilla.

const GOLDEN_RATIO_64: u64 = 0x9E37_79B9_7F4A_7C15;
const SILVER_RATIO_64: u64 = 0x6A09_E667_F3BC_C909;

fn mix_stafford13(mut z: u64) -> u64 {
  z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
  z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
  z ^ (z >> 31)
}

/// `XoroshiroRandomSource`.
#[derive(Clone)]
pub struct Xoroshiro {
  lo: u64,
  hi: u64,
}

impl Xoroshiro {
  /// A source from a 128 bit seed. An all zero state would never move, so vanilla swaps it.
  pub fn from_128(lo: u64, hi: u64) -> Self {
    if lo | hi == 0 {
      Xoroshiro { lo: GOLDEN_RATIO_64, hi: SILVER_RATIO_64 }
    } else {
      Xoroshiro { lo, hi }
    }
  }

  /// A source from a world seed: widened to 128 bits, then scrambled.
  pub fn from_seed(seed: i64) -> Self {
    let lo = seed as u64 ^ SILVER_RATIO_64;
    let hi = lo.wrapping_add(GOLDEN_RATIO_64);
    Self::from_128(mix_stafford13(lo), mix_stafford13(hi))
  }

  pub fn next_long(&mut self) -> u64 {
    let l = self.lo;
    let mut m = self.hi;
    let n = l.wrapping_add(m).rotate_left(17).wrapping_add(l);
    m ^= l;
    self.lo = l.rotate_left(49) ^ m ^ (m << 21);
    self.hi = m.rotate_left(28);
    n
  }

  pub fn next_int(&mut self) -> i32 {
    self.next_long() as i32
  }

  /// `nextInt(bound)` for `bound > 0`: unbiased, via rejection like vanilla.
  pub fn next_int_bound(&mut self, bound: i32) -> i32 {
    debug_assert!(bound > 0);
    let bound_u = bound as u32 as u64;
    let mut l = self.next_int() as u32 as u64;
    let mut m = l * bound_u;
    let mut n = m & 0xFFFF_FFFF;
    if n < bound_u {
      let threshold = (!(bound as u32)).wrapping_add(1) % (bound as u32);
      while n < threshold as u64 {
        l = self.next_int() as u32 as u64;
        m = l * bound_u;
        n = m & 0xFFFF_FFFF;
      }
    }
    (m >> 32) as i32
  }

  pub fn next_double(&mut self) -> f64 {
    (self.next_long() >> 11) as f64 * 1.110_223_024_625_156_5e-16
  }

  /// `forkPositional`: a factory that derives independent sources from names.
  pub fn fork_positional(&mut self) -> PositionalFactory {
    let lo = self.next_long();
    let hi = self.next_long();
    PositionalFactory { lo, hi }
  }
}

/// `XoroshiroRandomSource.PositionalFactory`.
#[derive(Clone)]
pub struct PositionalFactory {
  lo: u64,
  hi: u64,
}

impl PositionalFactory {
  /// The source for a noise (or octave) name: the MD5 of the name, mixed with this factory's seed.
  pub fn from_hash_of(&self, name: &str) -> Xoroshiro {
    let digest = md5::compute(name.as_bytes()).0;
    let lo = u64::from_be_bytes(digest[0..8].try_into().unwrap());
    let hi = u64::from_be_bytes(digest[8..16].try_into().unwrap());
    Xoroshiro::from_128(lo ^ self.lo, hi ^ self.hi)
  }
}
