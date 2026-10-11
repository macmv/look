//! Perlin noise as the game builds it: `ImprovedNoise` (one octave), `PerlinNoise` (octaves) and
//! `NormalNoise` (two Perlin noises averaged). Matches vanilla's 1.21 non-legacy initialization.

use crate::rng::Xoroshiro;

/// Gradient directions (`SimplexNoise.GRADIENT`).
const GRADIENT: [[i32; 3]; 16] = [
  [1, 1, 0],
  [-1, 1, 0],
  [1, -1, 0],
  [-1, -1, 0],
  [1, 0, 1],
  [-1, 0, 1],
  [1, 0, -1],
  [-1, 0, -1],
  [0, 1, 1],
  [0, -1, 1],
  [0, 1, -1],
  [0, -1, -1],
  [1, 1, 0],
  [0, -1, 1],
  [-1, 1, 0],
  [0, -1, -1],
];

#[inline]
fn lerp(t: f64, a: f64, b: f64) -> f64 {
  a + t * (b - a)
}

#[inline]
fn smoothstep(x: f64) -> f64 {
  x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
}

#[inline]
fn floor(x: f64) -> i32 {
  let i = x as i32;
  if x < i as f64 {
    i - 1
  } else {
    i
  }
}

/// One octave of gradient noise with a random offset and permutation table.
pub struct ImprovedNoise {
  xo: f64,
  yo: f64,
  zo: f64,
  p: [u8; 256],
}

impl ImprovedNoise {
  pub fn new(rng: &mut Xoroshiro) -> Self {
    let xo = rng.next_double() * 256.0;
    let yo = rng.next_double() * 256.0;
    let zo = rng.next_double() * 256.0;

    let mut p = [0u8; 256];
    for (i, v) in p.iter_mut().enumerate() {
      *v = i as u8;
    }
    for i in 0..256 {
      let j = rng.next_int_bound(256 - i as i32) as usize;
      p.swap(i, i + j);
    }
    ImprovedNoise { xo, yo, zo, p }
  }

  #[inline]
  fn p(&self, i: i32) -> i32 {
    self.p[(i & 0xFF) as usize] as i32
  }

  #[inline]
  fn grad_dot(hash: i32, x: f64, y: f64, z: f64) -> f64 {
    let g = &GRADIENT[(hash & 15) as usize];
    g[0] as f64 * x + g[1] as f64 * y + g[2] as f64 * z
  }

  pub fn noise(&self, x: f64, y: f64, z: f64) -> f64 {
    let d = x + self.xo;
    let e = y + self.yo;
    let f = z + self.zo;
    let i = floor(d);
    let j = floor(e);
    let k = floor(f);
    let xr = d - i as f64;
    let yr = e - j as f64;
    let zr = f - k as f64;

    let a = self.p(i);
    let b = self.p(i + 1);
    let aa = self.p(a + j);
    let ab = self.p(a + j + 1);
    let ba = self.p(b + j);
    let bb = self.p(b + j + 1);

    let d000 = Self::grad_dot(self.p(aa + k), xr, yr, zr);
    let d100 = Self::grad_dot(self.p(ba + k), xr - 1.0, yr, zr);
    let d010 = Self::grad_dot(self.p(ab + k), xr, yr - 1.0, zr);
    let d110 = Self::grad_dot(self.p(bb + k), xr - 1.0, yr - 1.0, zr);
    let d001 = Self::grad_dot(self.p(aa + k + 1), xr, yr, zr - 1.0);
    let d101 = Self::grad_dot(self.p(ba + k + 1), xr - 1.0, yr, zr - 1.0);
    let d011 = Self::grad_dot(self.p(ab + k + 1), xr, yr - 1.0, zr - 1.0);
    let d111 = Self::grad_dot(self.p(bb + k + 1), xr - 1.0, yr - 1.0, zr - 1.0);

    let u = smoothstep(xr);
    let v = smoothstep(yr);
    let w = smoothstep(zr);

    let x00 = lerp(u, d000, d100);
    let x10 = lerp(u, d010, d110);
    let x01 = lerp(u, d001, d101);
    let x11 = lerp(u, d011, d111);
    lerp(w, lerp(v, x00, x10), lerp(v, x01, x11))
  }
}

/// Keeps coordinates small so float precision holds up far from the origin.
#[inline]
fn wrap(v: f64) -> f64 {
  v - (v / 3.355_443_2e7 + 0.5).floor() * 3.355_443_2e7
}

/// `PerlinNoise`: octaves of `ImprovedNoise`, each twice the frequency of the last.
pub struct PerlinNoise {
  levels: Vec<Option<ImprovedNoise>>,
  amplitudes: Vec<f64>,
  lowest_freq_input: f64,
  lowest_freq_value: f64,
}

impl PerlinNoise {
  pub fn new(rng: &mut Xoroshiro, first_octave: i32, amplitudes: &[f64]) -> Self {
    let n = amplitudes.len();
    let factory = rng.fork_positional();

    let mut levels = Vec::with_capacity(n);
    for (k, &amp) in amplitudes.iter().enumerate() {
      if amp != 0.0 {
        let octave = first_octave + k as i32;
        levels.push(Some(ImprovedNoise::new(&mut factory.from_hash_of(&format!("octave_{octave}")))));
      } else {
        levels.push(None);
      }
    }

    PerlinNoise {
      levels,
      amplitudes: amplitudes.to_vec(),
      lowest_freq_input: 2f64.powi(first_octave),
      lowest_freq_value: 2f64.powi(n as i32 - 1) / (2f64.powi(n as i32) - 1.0),
    }
  }

  pub fn value(&self, x: f64, y: f64, z: f64) -> f64 {
    let mut sum = 0.0;
    let mut freq = self.lowest_freq_input;
    let mut factor = self.lowest_freq_value;
    for (level, &amp) in self.levels.iter().zip(&self.amplitudes) {
      if let Some(noise) = level {
        sum += amp * noise.noise(wrap(x * freq), wrap(y * freq), wrap(z * freq)) * factor;
      }
      freq *= 2.0;
      factor /= 2.0;
    }
    sum
  }
}

/// `NormalNoise`: two independent Perlin noises, the second sampled at a slightly different scale
/// so their patterns do not line up, scaled to a roughly unit range.
pub struct NormalNoise {
  first: PerlinNoise,
  second: PerlinNoise,
  value_factor: f64,
}

impl NormalNoise {
  pub fn new(rng: &mut Xoroshiro, first_octave: i32, amplitudes: &[f64]) -> Self {
    let first = PerlinNoise::new(rng, first_octave, amplitudes);
    let second = PerlinNoise::new(rng, first_octave, amplitudes);

    let mut lo = i32::MAX;
    let mut hi = i32::MIN;
    for (i, &amp) in amplitudes.iter().enumerate() {
      if amp != 0.0 {
        lo = lo.min(i as i32);
        hi = hi.max(i as i32);
      }
    }
    // A noise with every amplitude zero is flat; avoid dividing by a nonsense deviation.
    let octaves = if hi >= lo { hi - lo } else { 0 };
    let value_factor = 0.166_666_666_666_666_66 / (0.1 * (1.0 + 1.0 / (octaves as f64 + 1.0)));

    NormalNoise { first, second, value_factor }
  }

  pub fn value(&self, x: f64, y: f64, z: f64) -> f64 {
    const SCALE: f64 = 1.018_126_888_217_522_7;
    (self.first.value(x, y, z) + self.second.value(x * SCALE, y * SCALE, z * SCALE)) * self.value_factor
  }
}
