//! The feature definitions, parsed from the JSON `TreeExport.java` writes (the
//! game's own codecs' output). Anything the port does not know is an error for
//! that one feature, which is then left out, so a datapack's exotic tree only
//! costs its own trees.

use serde_json::Value;

use super::{
  block::{mushroom, Block, BlockId, Blocks},
  world::Heightmap,
};
use crate::rng::WorldgenRandom;

type Result<T> = std::result::Result<T, String>;

fn field<'a>(v: &'a Value, key: &str) -> Result<&'a Value> {
  v.get(key).ok_or_else(|| format!("missing {key}"))
}

fn int(v: &Value, key: &str) -> Result<i32> {
  field(v, key)?.as_i64().map(|n| n as i32).ok_or_else(|| format!("{key} is not an int"))
}

fn float(v: &Value, key: &str) -> Result<f32> {
  field(v, key)?.as_f64().map(|n| n as f32).ok_or_else(|| format!("{key} is not a number"))
}

fn kind(v: &Value) -> Result<&str> {
  v.get("type").and_then(Value::as_str).ok_or_else(|| "missing type".to_string())
}

/// `IntProvider`.
#[derive(Clone, Debug)]
pub enum IntProvider {
  Constant(i32),
  Uniform(i32, i32),
  BiasedToBottom(i32, i32),
  Clamped(Box<IntProvider>, i32, i32),
  Weighted(Vec<(IntProvider, i32)>),
}

impl IntProvider {
  pub fn parse(v: &Value) -> Result<IntProvider> {
    if let Some(n) = v.as_i64() {
      return Ok(IntProvider::Constant(n as i32));
    }
    Ok(match kind(v)? {
      "minecraft:constant" => IntProvider::Constant(int(v, "value")?),
      "minecraft:uniform" => {
        IntProvider::Uniform(int(v, "min_inclusive")?, int(v, "max_inclusive")?)
      }
      "minecraft:biased_to_bottom" => {
        IntProvider::BiasedToBottom(int(v, "min_inclusive")?, int(v, "max_inclusive")?)
      }
      "minecraft:clamped" => IntProvider::Clamped(
        Box::new(IntProvider::parse(field(v, "source")?)?),
        int(v, "min_inclusive")?,
        int(v, "max_inclusive")?,
      ),
      // Needs the gaussian source's cached second value; nothing in the overworld uses it.
      "minecraft:clamped_normal" => {
        return Err("clamped normal providers are not supported".into())
      }
      "minecraft:weighted_list" => {
        let mut list = Vec::new();
        for e in field(v, "distribution")?.as_array().ok_or("distribution is not a list")? {
          list.push((IntProvider::parse(field(e, "data")?)?, int(e, "weight")?));
        }
        IntProvider::Weighted(list)
      }
      other => return Err(format!("unknown int provider {other}")),
    })
  }

  pub fn sample(&self, rng: &mut WorldgenRandom) -> i32 {
    match self {
      IntProvider::Constant(n) => *n,
      IntProvider::Uniform(lo, hi) => rng.between(*lo, *hi),
      IntProvider::BiasedToBottom(lo, hi) => {
        let inner = rng.next_int_bound(hi - lo + 1) + 1;
        lo + rng.next_int_bound(inner)
      }
      IntProvider::Clamped(source, lo, hi) => source.sample(rng).clamp(*lo, *hi),
      IntProvider::Weighted(list) => {
        let total: i32 = list.iter().map(|(_, w)| w).sum();
        if total == 0 {
          return 0;
        }
        let mut i = rng.next_int_bound(total);
        for (p, w) in list {
          i -= w;
          if i < 0 {
            return p.sample(rng);
          }
        }
        0
      }
    }
  }
}

/// A block state the config places, with only the state bits `Block` keeps.
fn block_state(blocks: &mut Blocks, v: &Value) -> Result<Block> {
  let name = field(v, "Name")?.as_str().ok_or("Name is not a string")?;
  let id = blocks.intern(name);
  let mut state = 0u8;
  if let Some(props) = v.get("Properties").and_then(Value::as_object) {
    let on = |k: &str| props.get(k).and_then(Value::as_str) == Some("true");
    if name.ends_with("_leaves") {
      // Leaves keep their distance to a log in their state.
      state =
        props.get("distance").and_then(Value::as_str).and_then(|d| d.parse().ok()).unwrap_or(7);
    } else if let Some(axis) = props.get("axis").and_then(Value::as_str) {
      state = match axis {
        "x" => 1,
        "z" => 2,
        _ => 0,
      };
    } else if props.contains_key("north") && props.contains_key("up") {
      for (key, bit) in [
        ("north", mushroom::NORTH),
        ("east", mushroom::EAST),
        ("south", mushroom::SOUTH),
        ("west", mushroom::WEST),
        ("up", mushroom::UP),
        ("down", mushroom::DOWN),
      ] {
        if on(key) {
          state |= bit;
        }
      }
    }
  }
  if name.ends_with("_leaves") && state == 0 {
    state = 7;
  }
  Ok(Block::with(id, state))
}

fn state_provider(blocks: &mut Blocks, v: &Value) -> Result<Block> {
  match kind(v)? {
    "minecraft:simple_state_provider" => block_state(blocks, field(v, "state")?),
    other => Err(format!("unsupported state provider {other}")),
  }
}

#[derive(Clone, Debug)]
pub enum TrunkPlacer {
  Straight,
  Forking,
  Fancy,
  Giant,
  MegaJungle,
  DarkOak,
  Cherry {
    branch_count:                 IntProvider,
    branch_horizontal_length:     IntProvider,
    branch_start_offset_from_top: (i32, i32),
    branch_end_offset_from_top:   IntProvider,
  },
}

#[derive(Clone, Debug)]
pub enum FoliagePlacer {
  Blob {
    height: i32,
  },
  Fancy {
    height: i32,
  },
  Bush {
    height: i32,
  },
  Spruce {
    trunk_height: IntProvider,
  },
  Pine {
    height: IntProvider,
  },
  Acacia,
  DarkOak,
  MegaPine {
    crown_height: IntProvider,
  },
  MegaJungle {
    height: i32,
  },
  Cherry {
    height:                          IntProvider,
    wide_bottom_layer_hole_chance:   f32,
    corner_hole_chance:              f32,
    hanging_leaves_chance:           f32,
    hanging_leaves_extension_chance: f32,
  },
}

#[derive(Clone, Debug)]
pub enum Decorator {
  Beehive { probability: f32 },
  Cocoa { probability: f32 },
  LeaveVine { probability: f32 },
  TrunkVine,
  AlterGround { provider: Block },
}

#[derive(Clone, Debug)]
pub enum FeatureSize {
  TwoLayers {
    limit:              i32,
    lower:              i32,
    upper:              i32,
    min_clipped_height: Option<i32>,
  },
  ThreeLayers {
    limit:              i32,
    upper_limit:        i32,
    lower:              i32,
    middle:             i32,
    upper:              i32,
    min_clipped_height: Option<i32>,
  },
}

impl FeatureSize {
  pub fn size_at_height(&self, tree_height: i32, y: i32) -> i32 {
    match self {
      FeatureSize::TwoLayers { limit, lower, upper, .. } => {
        if y < *limit {
          *lower
        } else {
          *upper
        }
      }
      FeatureSize::ThreeLayers { limit, upper_limit, lower, middle, upper, .. } => {
        if y < *limit {
          *lower
        } else if y >= tree_height - upper_limit {
          *upper
        } else {
          *middle
        }
      }
    }
  }

  pub fn min_clipped_height(&self) -> Option<i32> {
    match self {
      FeatureSize::TwoLayers { min_clipped_height, .. }
      | FeatureSize::ThreeLayers { min_clipped_height, .. } => *min_clipped_height,
    }
  }
}

#[derive(Clone, Debug)]
pub struct TreeConfig {
  pub trunk:          Block,
  pub foliage:        Block,
  pub dirt:           Block,
  pub force_dirt:     bool,
  pub ignore_vines:   bool,
  pub base_height:    i32,
  pub height_rand_a:  i32,
  pub height_rand_b:  i32,
  pub trunk_placer:   TrunkPlacer,
  pub foliage_placer: FoliagePlacer,
  pub radius:         IntProvider,
  pub offset:         IntProvider,
  pub size:           FeatureSize,
  pub decorators:     Vec<Decorator>,
}

#[derive(Clone, Debug)]
pub struct MushroomConfig {
  pub brown:          bool,
  pub cap:            Block,
  pub stem:           Block,
  pub foliage_radius: i32,
}

#[derive(Clone, Debug)]
pub enum Modifier {
  Count(IntProvider),
  InSquare,
  SurfaceWaterDepth(i32),
  Heightmap(Heightmap),
  Biome,
  Filter(Predicate),
  Rarity(i32),
  /// `NoiseThresholdCountPlacement`.
  NoiseThresholdCount {
    level: f64,
    below: i32,
    above: i32,
  },
  /// `NoiseBasedCountPlacement`.
  NoiseBasedCount {
    ratio:  i32,
    factor: f64,
    offset: f64,
  },
}

/// The `BlockPredicate`s the placement filters use.
#[derive(Clone, Debug)]
pub enum Predicate {
  /// `would_survive` of a sapling: the block `offset` away must be able to
  /// stand on the dirt below.
  SaplingSurvives {
    offset: [i32; 3],
  },
  MatchingBlocks {
    offset: [i32; 3],
    blocks: Vec<BlockId>,
  },
  Not(Box<Predicate>),
  AllOf(Vec<Predicate>),
}

/// What a simple block feature places. A double plant carries its upper half.
#[derive(Clone, Debug)]
pub struct Plant {
  pub lower: Block,
  pub upper: Option<Block>,
}

#[derive(Clone, Debug)]
pub enum PlantProvider {
  Simple(Plant),
  Weighted(Vec<(Plant, i32)>),
}

impl PlantProvider {
  /// `BlockStateProvider.getState`.
  pub fn pick(&self, rng: &mut WorldgenRandom) -> &Plant {
    match self {
      PlantProvider::Simple(p) => p,
      PlantProvider::Weighted(list) => {
        let total: i32 = list.iter().map(|(_, w)| w).sum();
        let mut i = rng.next_int_bound(total);
        for (p, w) in list {
          i -= w;
          if i < 0 {
            return p;
          }
        }
        &list[list.len() - 1].0
      }
    }
  }
}

#[derive(Clone, Debug)]
pub enum Feature {
  Tree(Box<TreeConfig>),
  Mushroom(MushroomConfig),
  RandomSelector {
    features: Vec<(PlacedFeature, f32)>,
    default:  Box<PlacedFeature>,
  },
  RandomBoolean {
    when_true:  Box<PlacedFeature>,
    when_false: Box<PlacedFeature>,
  },
  SimpleRandom(Vec<PlacedFeature>),
  /// `RandomPatchFeature`.
  RandomPatch {
    tries:     i32,
    xz_spread: i32,
    y_spread:  i32,
    feature:   Box<PlacedFeature>,
  },
  /// `SimpleBlockFeature`, for the plants (`Blocks::is_plant`) only.
  SimpleBlock(PlantProvider),
  /// `SeagrassFeature`: tall seagrass with `probability`, else short.
  Seagrass {
    probability: f64,
    short:       Block,
    tall:        Plant,
  },
  /// `KelpFeature`.
  Kelp {
    head: Block,
    stem: Block,
  },
  /// `SeaPickleFeature`.
  SeaPickle {
    count:  IntProvider,
    pickle: Block,
  },
}

impl PlacedFeature {
  /// Whether the feature places plants rather than trees: those runs are only
  /// worth it for tiles that draw plants.
  pub fn places_plants(&self) -> bool {
    match &self.feature {
      Feature::Tree(_) | Feature::Mushroom(_) => false,
      Feature::RandomPatch { .. }
      | Feature::SimpleBlock(_)
      | Feature::Seagrass { .. }
      | Feature::Kelp { .. }
      | Feature::SeaPickle { .. } => true,
      Feature::RandomSelector { features, default } => {
        default.places_plants() || features.iter().any(|(f, _)| f.places_plants())
      }
      Feature::RandomBoolean { when_true, when_false } => {
        when_true.places_plants() || when_false.places_plants()
      }
      Feature::SimpleRandom(list) => list.iter().any(PlacedFeature::places_plants),
    }
  }
}

#[derive(Clone, Debug)]
pub struct PlacedFeature {
  pub modifiers: Vec<Modifier>,
  pub feature:   Feature,
}

impl PlacedFeature {
  pub fn parse(blocks: &mut Blocks, v: &Value) -> Result<PlacedFeature> {
    let mut modifiers = Vec::new();
    for m in field(v, "placement")?.as_array().ok_or("placement is not a list")? {
      modifiers.push(parse_modifier(blocks, m)?);
    }
    Ok(PlacedFeature { modifiers, feature: parse_feature(blocks, field(v, "feature")?)? })
  }
}

fn parse_modifier(blocks: &mut Blocks, m: &Value) -> Result<Modifier> {
  Ok(match kind(m)? {
    "minecraft:count" => Modifier::Count(IntProvider::parse(field(m, "count")?)?),
    "minecraft:in_square" => Modifier::InSquare,
    "minecraft:surface_water_depth_filter" => {
      Modifier::SurfaceWaterDepth(int(m, "max_water_depth")?)
    }
    "minecraft:heightmap" => Modifier::Heightmap(match field(m, "heightmap")?.as_str() {
      Some("OCEAN_FLOOR") => Heightmap::OceanFloor,
      Some("WORLD_SURFACE") => Heightmap::WorldSurface,
      Some("MOTION_BLOCKING") => Heightmap::MotionBlocking,
      Some("MOTION_BLOCKING_NO_LEAVES") => Heightmap::MotionBlockingNoLeaves,
      Some("OCEAN_FLOOR_WG") => Heightmap::OceanFloorWg,
      Some("WORLD_SURFACE_WG") => Heightmap::WorldSurfaceWg,
      other => return Err(format!("unsupported heightmap {other:?}")),
    }),
    "minecraft:biome" => Modifier::Biome,
    "minecraft:rarity_filter" => Modifier::Rarity(int(m, "chance")?),
    "minecraft:block_predicate_filter" => {
      Modifier::Filter(parse_predicate(blocks, field(m, "predicate")?)?)
    }
    "minecraft:noise_threshold_count" => Modifier::NoiseThresholdCount {
      level: field(m, "noise_level")?.as_f64().ok_or("noise_level is not a number")?,
      below: int(m, "below_noise")?,
      above: int(m, "above_noise")?,
    },
    "minecraft:noise_based_count" => Modifier::NoiseBasedCount {
      ratio:  int(m, "noise_to_count_ratio")?,
      factor: field(m, "noise_factor")?.as_f64().ok_or("noise_factor is not a number")?,
      offset: m.get("noise_offset").and_then(Value::as_f64).unwrap_or(0.0),
    },
    other => return Err(format!("unsupported placement modifier {other}")),
  })
}

fn offset_of(v: &Value) -> [i32; 3] {
  let mut offset = [0; 3];
  if let Some(o) = v.get("offset").and_then(Value::as_array) {
    for (i, n) in o.iter().take(3).enumerate() {
      offset[i] = n.as_i64().unwrap_or(0) as i32;
    }
  }
  offset
}

fn parse_predicate(blocks: &mut Blocks, p: &Value) -> Result<Predicate> {
  Ok(match kind(p)? {
    "minecraft:would_survive" => {
      let name = field(field(p, "state")?, "Name")?.as_str().unwrap_or("");
      if !name.ends_with("_sapling") && !name.ends_with("_propagule") {
        return Err(format!("unsupported would_survive of {name}"));
      }
      Predicate::SaplingSurvives { offset: offset_of(p) }
    }
    "minecraft:matching_blocks" => {
      let names = match field(p, "blocks")? {
        Value::String(one) => vec![one.as_str()],
        Value::Array(list) => list.iter().filter_map(Value::as_str).collect(),
        _ => return Err("blocks is not a block or a list".into()),
      };
      if names.iter().any(|n| n.starts_with('#')) {
        return Err("block tags in predicates are not supported".into());
      }
      Predicate::MatchingBlocks {
        offset: offset_of(p),
        blocks: names.into_iter().map(|n| blocks.intern(n)).collect(),
      }
    }
    "minecraft:not" => Predicate::Not(Box::new(parse_predicate(blocks, field(p, "predicate")?)?)),
    "minecraft:all_of" => {
      let mut list = Vec::new();
      for e in field(p, "predicates")?.as_array().ok_or("predicates is not a list")? {
        list.push(parse_predicate(blocks, e)?);
      }
      Predicate::AllOf(list)
    }
    other => return Err(format!("unsupported predicate {other}")),
  })
}

fn parse_plant(blocks: &mut Blocks, v: &Value) -> Result<Plant> {
  let state = block_state(blocks, v)?;
  if !blocks.is_plant(state) {
    return Err(format!("unsupported block {}", blocks.name(state.id)));
  }
  let upper = if blocks.is_double_plant(state) {
    let name = format!("{}[half=upper]", blocks.name(state.id));
    Some(Block::new(blocks.intern(&name)))
  } else {
    None
  };
  Ok(Plant { lower: state, upper })
}

fn parse_plant_provider(blocks: &mut Blocks, v: &Value) -> Result<PlantProvider> {
  match kind(v)? {
    "minecraft:simple_state_provider" => {
      Ok(PlantProvider::Simple(parse_plant(blocks, field(v, "state")?)?))
    }
    "minecraft:weighted_state_provider" => {
      let mut list = Vec::new();
      for e in field(v, "entries")?.as_array().ok_or("entries is not a list")? {
        list.push((parse_plant(blocks, field(e, "data")?)?, int(e, "weight")?));
      }
      if list.iter().map(|(_, w)| w).sum::<i32>() <= 0 {
        return Err("weighted state provider without weight".into());
      }
      Ok(PlantProvider::Weighted(list))
    }
    other => Err(format!("unsupported state provider {other}")),
  }
}

fn parse_feature(blocks: &mut Blocks, v: &Value) -> Result<Feature> {
  let config = field(v, "config")?;
  Ok(match kind(v)? {
    "minecraft:tree" => Feature::Tree(Box::new(parse_tree(blocks, config)?)),
    "minecraft:huge_red_mushroom" | "minecraft:huge_brown_mushroom" => {
      Feature::Mushroom(MushroomConfig {
        brown:          kind(v)? == "minecraft:huge_brown_mushroom",
        cap:            state_provider(blocks, field(config, "cap_provider")?)?,
        stem:           state_provider(blocks, field(config, "stem_provider")?)?,
        foliage_radius: int(config, "foliage_radius")?,
      })
    }
    "minecraft:random_patch" => Feature::RandomPatch {
      tries:     int(config, "tries")?,
      xz_spread: int(config, "xz_spread")?,
      y_spread:  int(config, "y_spread")?,
      feature:   Box::new(PlacedFeature::parse(blocks, field(config, "feature")?)?),
    },
    "minecraft:simple_block" => {
      Feature::SimpleBlock(parse_plant_provider(blocks, field(config, "to_place")?)?)
    }
    "minecraft:seagrass" => {
      let tall = blocks.intern("minecraft:tall_seagrass");
      let upper = blocks.intern("minecraft:tall_seagrass[half=upper]");
      Feature::Seagrass {
        probability: field(config, "probability")?.as_f64().ok_or("probability is not a number")?,
        short:       Block::new(blocks.intern("minecraft:seagrass")),
        tall:        Plant { lower: Block::new(tall), upper: Some(Block::new(upper)) },
      }
    }
    "minecraft:kelp" => Feature::Kelp {
      head: Block::new(blocks.intern("minecraft:kelp")),
      stem: Block::new(blocks.intern("minecraft:kelp_plant")),
    },
    "minecraft:sea_pickle" => Feature::SeaPickle {
      count:  IntProvider::parse(field(config, "count")?)?,
      pickle: Block::new(blocks.intern("minecraft:sea_pickle")),
    },
    "minecraft:random_selector" => {
      let mut features = Vec::new();
      for e in field(config, "features")?.as_array().ok_or("features is not a list")? {
        features.push((PlacedFeature::parse(blocks, field(e, "feature")?)?, float(e, "chance")?));
      }
      Feature::RandomSelector {
        features,
        default: Box::new(PlacedFeature::parse(blocks, field(config, "default")?)?),
      }
    }
    "minecraft:random_boolean_selector" => Feature::RandomBoolean {
      when_true:  Box::new(PlacedFeature::parse(blocks, field(config, "feature_true")?)?),
      when_false: Box::new(PlacedFeature::parse(blocks, field(config, "feature_false")?)?),
    },
    "minecraft:simple_random_selector" => {
      let mut list = Vec::new();
      for e in field(config, "features")?.as_array().ok_or("features is not a list")? {
        list.push(PlacedFeature::parse(blocks, e)?);
      }
      Feature::SimpleRandom(list)
    }
    other => return Err(format!("unsupported feature {other}")),
  })
}

fn optional_int(v: &Value, key: &str) -> Option<i32> {
  v.get(key).and_then(Value::as_i64).map(|n| n as i32)
}

fn parse_tree(blocks: &mut Blocks, c: &Value) -> Result<TreeConfig> {
  let trunk = state_provider(blocks, field(c, "trunk_provider")?)?;
  let foliage = state_provider(blocks, field(c, "foliage_provider")?)?;
  let dirt = state_provider(blocks, field(c, "dirt_provider")?)?;
  if c.get("root_placer").is_some() {
    return Err("root placers are not supported".into());
  }

  let tp = field(c, "trunk_placer")?;
  let trunk_placer = match kind(tp)? {
    "minecraft:straight_trunk_placer" => TrunkPlacer::Straight,
    "minecraft:forking_trunk_placer" => TrunkPlacer::Forking,
    "minecraft:fancy_trunk_placer" => TrunkPlacer::Fancy,
    "minecraft:giant_trunk_placer" => TrunkPlacer::Giant,
    "minecraft:mega_jungle_trunk_placer" => TrunkPlacer::MegaJungle,
    "minecraft:dark_oak_trunk_placer" => TrunkPlacer::DarkOak,
    "minecraft:cherry_trunk_placer" => {
      let start = field(tp, "branch_start_offset_from_top")?;
      TrunkPlacer::Cherry {
        branch_count:                 IntProvider::parse(field(tp, "branch_count")?)?,
        branch_horizontal_length:     IntProvider::parse(field(tp, "branch_horizontal_length")?)?,
        branch_start_offset_from_top: (int(start, "min_inclusive")?, int(start, "max_inclusive")?),
        branch_end_offset_from_top:   IntProvider::parse(field(tp, "branch_end_offset_from_top")?)?,
      }
    }
    other => return Err(format!("unsupported trunk placer {other}")),
  };

  let fp = field(c, "foliage_placer")?;
  let height_int = |key: &str| int(fp, key);
  let foliage_placer = match kind(fp)? {
    "minecraft:blob_foliage_placer" => FoliagePlacer::Blob { height: height_int("height")? },
    "minecraft:fancy_foliage_placer" => FoliagePlacer::Fancy { height: height_int("height")? },
    "minecraft:bush_foliage_placer" => FoliagePlacer::Bush { height: height_int("height")? },
    "minecraft:spruce_foliage_placer" => {
      FoliagePlacer::Spruce { trunk_height: IntProvider::parse(field(fp, "trunk_height")?)? }
    }
    "minecraft:pine_foliage_placer" => {
      FoliagePlacer::Pine { height: IntProvider::parse(field(fp, "height")?)? }
    }
    "minecraft:acacia_foliage_placer" => FoliagePlacer::Acacia,
    "minecraft:dark_oak_foliage_placer" => FoliagePlacer::DarkOak,
    "minecraft:mega_pine_foliage_placer" => {
      FoliagePlacer::MegaPine { crown_height: IntProvider::parse(field(fp, "crown_height")?)? }
    }
    "minecraft:jungle_foliage_placer" => {
      FoliagePlacer::MegaJungle { height: height_int("height")? }
    }
    "minecraft:cherry_foliage_placer" => FoliagePlacer::Cherry {
      height:                          IntProvider::parse(field(fp, "height")?)?,
      wide_bottom_layer_hole_chance:   float(fp, "wide_bottom_layer_hole_chance")?,
      corner_hole_chance:              float(fp, "corner_hole_chance")?,
      hanging_leaves_chance:           float(fp, "hanging_leaves_chance")?,
      hanging_leaves_extension_chance: float(fp, "hanging_leaves_extension_chance")?,
    },
    other => return Err(format!("unsupported foliage placer {other}")),
  };

  let sz = field(c, "minimum_size")?;
  let size = match kind(sz)? {
    "minecraft:two_layers_feature_size" => FeatureSize::TwoLayers {
      limit:              int(sz, "limit")?,
      lower:              int(sz, "lower_size")?,
      upper:              int(sz, "upper_size")?,
      min_clipped_height: optional_int(sz, "min_clipped_height"),
    },
    "minecraft:three_layers_feature_size" => FeatureSize::ThreeLayers {
      limit:              int(sz, "limit")?,
      upper_limit:        int(sz, "upper_limit")?,
      lower:              int(sz, "lower_size")?,
      middle:             int(sz, "middle_size")?,
      upper:              int(sz, "upper_size")?,
      min_clipped_height: optional_int(sz, "min_clipped_height"),
    },
    other => return Err(format!("unsupported feature size {other}")),
  };

  let mut decorators = Vec::new();
  for d in field(c, "decorators")?.as_array().ok_or("decorators is not a list")? {
    decorators.push(match kind(d)? {
      "minecraft:beehive" => Decorator::Beehive { probability: float(d, "probability")? },
      "minecraft:cocoa" => Decorator::Cocoa { probability: float(d, "probability")? },
      "minecraft:leave_vine" => Decorator::LeaveVine { probability: float(d, "probability")? },
      "minecraft:trunk_vine" => Decorator::TrunkVine,
      "minecraft:alter_ground" => {
        Decorator::AlterGround { provider: state_provider(blocks, field(d, "provider")?)? }
      }
      other => return Err(format!("unsupported tree decorator {other}")),
    });
  }

  let radius = IntProvider::parse(field(fp, "radius")?)?;
  let offset = IntProvider::parse(field(fp, "offset")?)?;

  Ok(TreeConfig {
    trunk,
    foliage,
    dirt,
    force_dirt: c.get("force_dirt").and_then(Value::as_bool).unwrap_or(false),
    ignore_vines: c.get("ignore_vines").and_then(Value::as_bool).unwrap_or(false),
    base_height: int(tp, "base_height")?,
    height_rand_a: int(tp, "height_rand_a")?,
    height_rand_b: int(tp, "height_rand_b")?,
    trunk_placer,
    foliage_placer,
    radius,
    offset,
    size,
    decorators,
  })
}
