//! Vanilla's density functions, interpreted. The graph is read from the same
//! JSON the game loads (exported from the live registry, so datapacks and
//! custom worlds are honored), compiled into a flat node array, and evaluated
//! at single points.
//!
//! Only the node types the terrain surface and climate need are supported;
//! anything else makes compilation fail with the type's name, so the caller can
//! fall back to not predicting terrain.
//!
//! The `flat_cache`, `cache_2d` and `cache_once` markers keep their meaning:
//! they are memoized per thread on the point they were last evaluated at.
//! Terrain shape depends on (x, z) only, and the height search evaluates the
//! same column dozens of times, so this is most of the speed.

use std::collections::HashMap;

use serde_json::Value;

use crate::{
  noise::{BlendedNoise, NormalNoise},
  rng::PositionalFactory,
};

type Id = u32;

#[derive(Debug)]
enum Node {
  Const(f64),
  Add(Id, Id),
  Mul(Id, Id),
  Min(Id, Id),
  Max(Id, Id),
  Abs(Id),
  Square(Id),
  Cube(Id),
  HalfNegative(Id),
  QuarterNegative(Id),
  Squeeze(Id),
  Clamp {
    input: Id,
    min:   f64,
    max:   f64,
  },
  YGradient {
    from_y: f64,
    to_y:   f64,
    from:   f64,
    to:     f64,
  },
  Noise {
    noise: u32,
    xz:    f64,
    y:     f64,
  },
  ShiftedNoise {
    noise: u32,
    sx:    Id,
    sy:    Id,
    sz:    Id,
    xz:    f64,
    y:     f64,
  },
  Shift {
    noise: u32,
    mode:  ShiftMode,
  },
  /// `rarity * |noise(pos / rarity)|`, with the rarity picked from the input
  /// (the cave shapes).
  WeirdScaled {
    input:   Id,
    noise:   u32,
    tunnels: bool,
  },
  Spline(u32),
  Blended(u32),
  RangeChoice {
    input:        Id,
    min:          f64,
    max:          f64,
    in_range:     Id,
    out_of_range: Id,
  },
  /// Memoized on (x, z).
  Memo2D {
    arg:  Id,
    slot: u32,
  },
  /// Memoized on (x, y, z).
  MemoPoint {
    arg:  Id,
    slot: u32,
  },
  /// Markers that only matter inside the game's chunk generator.
  Pass(Id),
}

#[derive(Debug, Clone, Copy)]
enum ShiftMode {
  A,
  B,
  Both,
}

#[derive(Debug)]
struct Spline {
  coordinate: Id,
  points:     Vec<SplinePoint>,
}

#[derive(Debug)]
struct SplinePoint {
  location:   f32,
  derivative: f32,
  value:      SplineValue,
}

#[derive(Debug)]
enum SplineValue {
  Const(f32),
  Nested(u32),
}

/// Where a function is evaluated, in blocks.
#[derive(Clone, Copy)]
pub struct Point {
  pub x: i32,
  pub y: i32,
  pub z: i32,
}

#[derive(Clone, Copy)]
struct Memo {
  x:     i32,
  y:     i32,
  z:     i32,
  valid: bool,
  value: f64,
}

/// Per-thread evaluation state: the memo for each cache node.
pub struct Scratch {
  memo: Vec<Memo>,
}

pub struct Graph {
  nodes:      Vec<Node>,
  splines:    Vec<Spline>,
  noises:     Vec<NormalNoise>,
  blended:    Vec<BlendedNoise>,
  memo_slots: usize,
}

impl Graph {
  pub fn scratch(&self) -> Scratch {
    Scratch { memo: vec![Memo { x: 0, y: 0, z: 0, valid: false, value: 0.0 }; self.memo_slots] }
  }

  pub fn eval(&self, root: Root, p: Point, s: &mut Scratch) -> f64 { self.node(root.0, p, s) }

  fn node(&self, id: Id, p: Point, s: &mut Scratch) -> f64 {
    match &self.nodes[id as usize] {
      Node::Const(v) => *v,
      Node::Add(a, b) => self.node(*a, p, s) + self.node(*b, p, s),
      Node::Mul(a, b) => {
        let x = self.node(*a, p, s);
        if x == 0.0 {
          0.0
        } else {
          x * self.node(*b, p, s)
        }
      }
      Node::Min(a, b) => {
        let x = self.node(*a, p, s);
        let y = self.node(*b, p, s);
        x.min(y)
      }
      Node::Max(a, b) => {
        let x = self.node(*a, p, s);
        let y = self.node(*b, p, s);
        x.max(y)
      }
      Node::Abs(a) => self.node(*a, p, s).abs(),
      Node::Square(a) => {
        let x = self.node(*a, p, s);
        x * x
      }
      Node::Cube(a) => {
        let x = self.node(*a, p, s);
        x * x * x
      }
      Node::HalfNegative(a) => {
        let x = self.node(*a, p, s);
        if x > 0.0 {
          x
        } else {
          x * 0.5
        }
      }
      Node::QuarterNegative(a) => {
        let x = self.node(*a, p, s);
        if x > 0.0 {
          x
        } else {
          x * 0.25
        }
      }
      Node::Squeeze(a) => {
        let c = self.node(*a, p, s).clamp(-1.0, 1.0);
        c / 2.0 - c * c * c / 24.0
      }
      Node::Clamp { input, min, max } => self.node(*input, p, s).clamp(*min, *max),
      Node::YGradient { from_y, to_y, from, to } => {
        let t = (p.y as f64 - from_y) / (to_y - from_y);
        if t < 0.0 {
          *from
        } else if t > 1.0 {
          *to
        } else {
          from + t * (to - from)
        }
      }
      Node::Noise { noise, xz, y } => {
        self.noises[*noise as usize].value(p.x as f64 * xz, p.y as f64 * y, p.z as f64 * xz)
      }
      Node::ShiftedNoise { noise, sx, sy, sz, xz, y } => {
        let dx = p.x as f64 * xz + self.node(*sx, p, s);
        let dy = p.y as f64 * y + self.node(*sy, p, s);
        let dz = p.z as f64 * xz + self.node(*sz, p, s);
        self.noises[*noise as usize].value(dx, dy, dz)
      }
      Node::Shift { noise, mode } => {
        let (x, y, z) = match mode {
          ShiftMode::A => (p.x, 0, p.z),
          ShiftMode::B => (p.z, p.x, 0),
          ShiftMode::Both => (p.x, p.y, p.z),
        };
        self.noises[*noise as usize].value(x as f64 * 0.25, y as f64 * 0.25, z as f64 * 0.25) * 4.0
      }
      Node::WeirdScaled { input, noise, tunnels } => {
        let v = self.node(*input, p, s);
        let rarity = if *tunnels {
          // RarityValueMapper.TUNNELS
          if v < -0.5 {
            0.75
          } else if v < 0.0 {
            1.0
          } else if v < 0.5 {
            1.5
          } else {
            2.0
          }
        } else {
          // RarityValueMapper.CAVES
          if v < -0.75 {
            0.5
          } else if v < -0.5 {
            0.75
          } else if v < 0.0 {
            1.0
          } else if v < 0.5 {
            1.5
          } else {
            2.0
          }
        };
        rarity
          * self.noises[*noise as usize]
            .value(p.x as f64 / rarity, p.y as f64 / rarity, p.z as f64 / rarity)
            .abs()
      }
      Node::Spline(i) => self.spline(*i, p, s) as f64,
      Node::Blended(i) => self.blended[*i as usize].value(p.x as f64, p.y as f64, p.z as f64),
      Node::RangeChoice { input, min, max, in_range, out_of_range } => {
        let v = self.node(*input, p, s);
        if v >= *min && v < *max {
          self.node(*in_range, p, s)
        } else {
          self.node(*out_of_range, p, s)
        }
      }
      Node::Memo2D { arg, slot } => {
        let m = s.memo[*slot as usize];
        if m.valid && m.x == p.x && m.z == p.z {
          return m.value;
        }
        let value = self.node(*arg, p, s);
        s.memo[*slot as usize] = Memo { x: p.x, y: p.y, z: p.z, valid: true, value };
        value
      }
      Node::MemoPoint { arg, slot } => {
        let m = s.memo[*slot as usize];
        if m.valid && m.x == p.x && m.y == p.y && m.z == p.z {
          return m.value;
        }
        let value = self.node(*arg, p, s);
        s.memo[*slot as usize] = Memo { x: p.x, y: p.y, z: p.z, valid: true, value };
        value
      }
      Node::Pass(a) => self.node(*a, p, s),
    }
  }

  /// `CubicSpline.Multipoint.apply`, in `f32` like the game.
  fn spline(&self, index: u32, p: Point, s: &mut Scratch) -> f32 {
    let spline = &self.splines[index as usize];
    let x = self.node(spline.coordinate, p, s) as f32;
    let pts = &spline.points;

    // The last point whose location is <= x.
    let start = pts.partition_point(|pt| !(x < pt.location)) as isize - 1;
    let last = pts.len() as isize - 1;

    let linear_extend = |this: &Self, i: usize, s: &mut Scratch| -> f32 {
      let value = this.spline_value(&pts[i].value, p, s);
      let d = pts[i].derivative;
      if d == 0.0 {
        value
      } else {
        value + d * (x - pts[i].location)
      }
    };

    if start < 0 {
      linear_extend(self, 0, s)
    } else if start == last {
      linear_extend(self, last as usize, s)
    } else {
      let i = start as usize;
      let (g, h) = (pts[i].location, pts[i + 1].location);
      let k = (x - g) / (h - g);
      let (l, m) = (pts[i].derivative, pts[i + 1].derivative);
      let n = self.spline_value(&pts[i].value, p, s);
      let o = self.spline_value(&pts[i + 1].value, p, s);
      let pp = l * (h - g) - (o - n);
      let q = -m * (h - g) + (o - n);
      let lerp = |t: f32, a: f32, b: f32| a + t * (b - a);
      lerp(k, n, o) + k * (1.0 - k) * lerp(k, pp, q)
    }
  }

  fn spline_value(&self, v: &SplineValue, p: Point, s: &mut Scratch) -> f32 {
    match v {
      SplineValue::Const(c) => *c,
      SplineValue::Nested(i) => self.spline(*i, p, s),
    }
  }
}

/// A function in the graph that callers can evaluate.
#[derive(Clone, Copy, Debug)]
pub struct Root(Id);

/// Compiles functions out of the exported config into one shared graph, so
/// functions that several roots refer to are evaluated (and memoized) once.
pub struct Compiler<'a> {
  functions:     &'a serde_json::Map<String, Value>,
  noise_params:  &'a serde_json::Map<String, Value>,
  noise_factory: PositionalFactory,

  graph:     Graph,
  by_name:   HashMap<String, Id>,
  noise_ids: HashMap<String, u32>,
}

impl<'a> Compiler<'a> {
  pub fn new(
    functions: &'a serde_json::Map<String, Value>,
    noise_params: &'a serde_json::Map<String, Value>,
    noise_factory: PositionalFactory,
  ) -> Self {
    Compiler {
      functions,
      noise_params,
      noise_factory,
      graph: Graph {
        nodes:      Vec::new(),
        splines:    Vec::new(),
        noises:     Vec::new(),
        blended:    Vec::new(),
        memo_slots: 0,
      },
      by_name: HashMap::new(),
      noise_ids: HashMap::new(),
    }
  }

  pub fn root(&mut self, v: &Value) -> Result<Root, String> { Ok(Root(self.compile(v)?)) }

  pub fn finish(self) -> Graph { self.graph }

  fn push(&mut self, n: Node) -> Id {
    self.graph.nodes.push(n);
    (self.graph.nodes.len() - 1) as Id
  }

  fn slot(&mut self) -> u32 {
    self.graph.memo_slots += 1;
    (self.graph.memo_slots - 1) as u32
  }

  fn noise(&mut self, name: &str) -> Result<u32, String> {
    if let Some(&i) = self.noise_ids.get(name) {
      return Ok(i);
    }
    let params =
      self.noise_params.get(name).ok_or_else(|| format!("missing noise parameters for {name}"))?;
    let first_octave = params
      .get("firstOctave")
      .and_then(Value::as_i64)
      .ok_or_else(|| format!("noise {name} has no firstOctave"))? as i32;
    let amplitudes: Vec<f64> = params
      .get("amplitudes")
      .and_then(Value::as_array)
      .ok_or_else(|| format!("noise {name} has no amplitudes"))?
      .iter()
      .map(|a| a.as_f64().ok_or_else(|| format!("noise {name} has a bad amplitude")))
      .collect::<Result<_, _>>()?;

    let mut rng = self.noise_factory.from_hash_of(name);
    let id = self.graph.noises.len() as u32;
    self.graph.noises.push(NormalNoise::new(&mut rng, first_octave, &amplitudes));
    self.noise_ids.insert(name.to_string(), id);
    Ok(id)
  }

  fn compile(&mut self, v: &Value) -> Result<Id, String> {
    match v {
      Value::Number(n) => Ok(self.push(Node::Const(n.as_f64().ok_or("bad number")?))),
      Value::String(name) => {
        if let Some(&id) = self.by_name.get(name) {
          return Ok(id);
        }
        let def =
          self.functions.get(name).ok_or_else(|| format!("unknown density function {name}"))?;
        let id = self.compile(def)?;
        self.by_name.insert(name.clone(), id);
        Ok(id)
      }
      Value::Object(obj) => {
        let ty =
          obj.get("type").and_then(Value::as_str).ok_or("density function without a type")?;
        let ty = ty.strip_prefix("minecraft:").unwrap_or(ty);
        self.compile_typed(ty, obj)
      }
      _ => Err("unexpected density function json".into()),
    }
  }

  fn arg(&mut self, obj: &serde_json::Map<String, Value>, key: &str) -> Result<Id, String> {
    let v = obj.get(key).ok_or_else(|| format!("missing {key}"))?;
    self.compile(v)
  }

  fn num(obj: &serde_json::Map<String, Value>, key: &str) -> Result<f64, String> {
    obj.get(key).and_then(Value::as_f64).ok_or_else(|| format!("missing number {key}"))
  }

  fn text<'o>(obj: &'o serde_json::Map<String, Value>, key: &str) -> Result<&'o str, String> {
    obj.get(key).and_then(Value::as_str).ok_or_else(|| format!("missing string {key}"))
  }

  fn compile_typed(
    &mut self,
    ty: &str,
    obj: &serde_json::Map<String, Value>,
  ) -> Result<Id, String> {
    let node = match ty {
      "constant" => Node::Const(Self::num(obj, "argument")?),
      "add" | "mul" | "min" | "max" => {
        let a = self.arg(obj, "argument1")?;
        let b = self.arg(obj, "argument2")?;
        match ty {
          "add" => Node::Add(a, b),
          "mul" => Node::Mul(a, b),
          "min" => Node::Min(a, b),
          _ => Node::Max(a, b),
        }
      }
      "abs" | "square" | "cube" | "half_negative" | "quarter_negative" | "squeeze" => {
        let a = self.arg(obj, "argument")?;
        match ty {
          "abs" => Node::Abs(a),
          "square" => Node::Square(a),
          "cube" => Node::Cube(a),
          "half_negative" => Node::HalfNegative(a),
          "quarter_negative" => Node::QuarterNegative(a),
          _ => Node::Squeeze(a),
        }
      }
      "clamp" => Node::Clamp {
        input: self.arg(obj, "input")?,
        min:   Self::num(obj, "min")?,
        max:   Self::num(obj, "max")?,
      },
      "y_clamped_gradient" => Node::YGradient {
        from_y: Self::num(obj, "from_y")?,
        to_y:   Self::num(obj, "to_y")?,
        from:   Self::num(obj, "from_value")?,
        to:     Self::num(obj, "to_value")?,
      },
      "noise" => Node::Noise {
        noise: self.noise(Self::text(obj, "noise")?)?,
        xz:    Self::num(obj, "xz_scale")?,
        y:     Self::num(obj, "y_scale")?,
      },
      "shifted_noise" => Node::ShiftedNoise {
        noise: self.noise(Self::text(obj, "noise")?)?,
        sx:    self.arg(obj, "shift_x")?,
        sy:    self.arg(obj, "shift_y")?,
        sz:    self.arg(obj, "shift_z")?,
        xz:    Self::num(obj, "xz_scale")?,
        y:     Self::num(obj, "y_scale")?,
      },
      "shift_a" => {
        Node::Shift { noise: self.noise(Self::text(obj, "argument")?)?, mode: ShiftMode::A }
      }
      "shift_b" => {
        Node::Shift { noise: self.noise(Self::text(obj, "argument")?)?, mode: ShiftMode::B }
      }
      "shift" => {
        Node::Shift { noise: self.noise(Self::text(obj, "argument")?)?, mode: ShiftMode::Both }
      }
      "weird_scaled_sampler" => {
        let mapper = Self::text(obj, "rarity_value_mapper")?;
        Node::WeirdScaled {
          input:   self.arg(obj, "input")?,
          noise:   self.noise(Self::text(obj, "noise")?)?,
          tunnels: match mapper {
            "type_1" => true,
            "type_2" => false,
            other => return Err(format!("unknown rarity value mapper {other}")),
          },
        }
      }
      "range_choice" => Node::RangeChoice {
        input:        self.arg(obj, "input")?,
        min:          Self::num(obj, "min_inclusive")?,
        max:          Self::num(obj, "max_exclusive")?,
        in_range:     self.arg(obj, "when_in_range")?,
        out_of_range: self.arg(obj, "when_out_of_range")?,
      },
      "spline" => {
        let spline = obj.get("spline").ok_or("spline without a spline")?;
        match self.compile_spline(spline)? {
          SplineValue::Const(c) => Node::Const(c as f64),
          SplineValue::Nested(i) => Node::Spline(i),
        }
      }
      "old_blended_noise" => {
        // The game seeds it from the name "minecraft:terrain", whatever else
        // the world uses.
        let mut rng = self.noise_factory.from_hash_of("minecraft:terrain");
        let noise = BlendedNoise::new(
          &mut rng,
          Self::num(obj, "xz_scale")?,
          Self::num(obj, "y_scale")?,
          Self::num(obj, "xz_factor")?,
          Self::num(obj, "y_factor")?,
          Self::num(obj, "smear_scale_multiplier")?,
        );
        self.graph.blended.push(noise);
        Node::Blended((self.graph.blended.len() - 1) as u32)
      }
      "flat_cache" | "cache_2d" => {
        Node::Memo2D { arg: self.arg(obj, "argument")?, slot: self.slot() }
      }
      "cache_once" => Node::MemoPoint { arg: self.arg(obj, "argument")?, slot: self.slot() },
      "interpolated" | "cache_all_in_cell" | "blend_density" => {
        Node::Pass(self.arg(obj, "argument")?)
      }
      // No blending happens outside the chunk generator.
      "blend_alpha" => Node::Const(1.0),
      "blend_offset" => Node::Const(0.0),
      other => return Err(format!("unsupported density function type {other}")),
    };
    Ok(self.push(node))
  }

  fn compile_spline(&mut self, v: &Value) -> Result<SplineValue, String> {
    match v {
      Value::Number(n) => Ok(SplineValue::Const(n.as_f64().ok_or("bad spline number")? as f32)),
      Value::Object(obj) => {
        let coordinate = self.arg(obj, "coordinate")?;
        let points = obj.get("points").and_then(Value::as_array).ok_or("spline without points")?;
        let mut out = Vec::with_capacity(points.len());
        for pt in points {
          let pt = pt.as_object().ok_or("bad spline point")?;
          out.push(SplinePoint {
            location:   Self::num(pt, "location")? as f32,
            derivative: Self::num(pt, "derivative")? as f32,
            value:      self
              .compile_spline(pt.get("value").ok_or("spline point without a value")?)?,
          });
        }
        if out.is_empty() {
          return Err("spline without points".into());
        }
        self.graph.splines.push(Spline { coordinate, points: out });
        Ok(SplineValue::Nested((self.graph.splines.len() - 1) as u32))
      }
      _ => Err("unexpected spline json".into()),
    }
  }
}
