//! Minecraft's Xoroshiro128++ random source and the rules that turn a world
//! seed into per-noise generators. Everything here has to match the game bit
//! for bit, or every noise value (and so the whole terrain) comes out
//! different; `tests/oracle.rs` checks it against values from vanilla.

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
  /// A source from a 128 bit seed. An all zero state would never move, so
  /// vanilla swaps it.
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

  pub fn next_int(&mut self) -> i32 { self.next_long() as i32 }

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
  /// The source for a noise (or octave) name: the MD5 of the name, mixed with
  /// this factory's seed.
  pub fn from_hash_of(&self, name: &str) -> Xoroshiro {
    let digest = md5::compute(name.as_bytes()).0;
    let lo = u64::from_be_bytes(digest[0..8].try_into().unwrap());
    let hi = u64::from_be_bytes(digest[8..16].try_into().unwrap());
    Xoroshiro::from_128(lo ^ self.lo, hi ^ self.hi)
  }
}

/// `WorldgenRandom` over a Xoroshiro source: what the game seeds per chunk and
/// per feature when it decorates. It is not the Xoroshiro's own `nextInt` and
/// friends: `WorldgenRandom` is a `BitRandomSource`, which builds every method
/// out of `next(bits)` (here the top bits of the next long) the way
/// `java.util.Random` does, so `nextInt(bound)`, `nextBoolean`, `nextLong` and
/// `nextDouble` all draw differently from `Xoroshiro`'s.
#[derive(Clone)]
pub struct WorldgenRandom {
  inner: Xoroshiro,
}

impl Default for WorldgenRandom {
  fn default() -> Self { Self::new() }
}

impl WorldgenRandom {
  pub fn new() -> Self { WorldgenRandom { inner: Xoroshiro::from_seed(0) } }

  /// `setSeed`: starts over from a 64 bit seed, widened like
  /// `Xoroshiro::from_seed`.
  pub fn set_seed(&mut self, seed: i64) { self.inner = Xoroshiro::from_seed(seed); }

  /// `next(bits)`: the top `bits` bits of the next long, as an int.
  fn next(&mut self, bits: u32) -> i32 { (self.inner.next_long() >> (64 - bits)) as i32 }

  pub fn next_int(&mut self) -> i32 { self.next(32) }

  /// `BitRandomSource.nextInt(bound)` for `bound > 0`.
  pub fn next_int_bound(&mut self, bound: i32) -> i32 {
    debug_assert!(bound > 0);
    if bound & (bound - 1) == 0 {
      return ((bound as i64 * self.next(31) as i64) >> 31) as i32;
    }
    loop {
      let j = self.next(31);
      let k = j % bound;
      if j.wrapping_sub(k).wrapping_add(bound - 1) >= 0 {
        return k;
      }
    }
  }

  /// `BitRandomSource.nextLong`: two ints, the second added with its sign.
  pub fn next_long(&mut self) -> i64 {
    let i = self.next(32);
    let j = self.next(32);
    ((i as i64) << 32).wrapping_add(j as i64)
  }

  pub fn next_bool(&mut self) -> bool { self.next(1) != 0 }

  pub fn next_float(&mut self) -> f32 { self.next(24) as f32 * 5.960_464_5e-8_f32 }

  pub fn next_double(&mut self) -> f64 {
    let i = self.next(26) as i64;
    let j = self.next(27) as i64;
    (((i << 27) + j) as f64) * (1.110_223e-16_f32 as f64)
  }

  /// `Mth.randomBetweenInclusive`.
  pub fn between(&mut self, min: i32, max: i32) -> i32 { self.next_int_bound(max - min + 1) + min }

  /// `WorldgenRandom.setDecorationSeed`: the per chunk seed that every
  /// feature's seed derives from. `x` and `z` are the chunk's lowest block
  /// coordinates.
  pub fn set_decoration_seed(&mut self, world_seed: i64, x: i32, z: i32) -> i64 {
    self.set_seed(world_seed);
    let m = self.next_long() | 1;
    let n = self.next_long() | 1;
    let seed = (x as i64).wrapping_mul(m).wrapping_add((z as i64).wrapping_mul(n)) ^ world_seed;
    self.set_seed(seed);
    seed
  }

  /// `WorldgenRandom.setFeatureSeed`: `index` is the feature's place in its
  /// decoration step's list.
  pub fn set_feature_seed(&mut self, decoration_seed: i64, index: i32, step: i32) {
    self.set_seed(decoration_seed.wrapping_add(index as i64).wrapping_add((10000 * step) as i64));
  }
}
