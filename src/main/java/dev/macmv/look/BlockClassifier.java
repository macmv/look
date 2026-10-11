package dev.macmv.look;

import dev.macmv.look.mixin.WeightedBakedModelAccessor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Maps block states to voxel ids (0 is air) and keeps the per-id data the shader needs: a map color
 * (fallback) and, for each of the six faces, up to {@link #LAYERS} textured layers taken from the
 * baked model.
 *
 * <p>Only full opaque-ish cubes become voxels. Fluids and non-cube blocks are air for now, so
 * vanilla's translucent layer still draws water, and plants/stairs/etc. are simply missing until
 * non-cube support exists.
 *
 * <p>Voxel ids are handed out sequentially, so the tables only grow with the block states actually
 * seen. They are invalidated by {@link #reset()} when resources reload.
 */
public final class BlockClassifier {
  /** Voxel ids are u16 on the GPU; the face table caps them lower to stay small. */
  public static final int MAX_ID = 0x3FFF;

  /** Textured layers per face: the base, and an overlay (grass side). */
  public static final int LAYERS = 2;
  /** Floats per (id, face, layer): two RGBA texels, see {@link #writeLayer}. */
  private static final int LAYER_FLOATS = 8;
  /** Floats per row of the face table: six faces, each with {@link #LAYERS} layers. */
  public static final int ID_FLOATS = 6 * LAYERS * LAYER_FLOATS;

  /** Most weighted variants of one block state that are kept; the rest are dropped. */
  public static final int MAX_VARIANTS = 8;
  /** Rows after the per-id rows, for variants past the first. Variant 0 of an id lives in row id. */
  public static final int EXTRA_ROWS = 1024;
  /** Rows in the face table: one per id, then the extra variant rows. */
  public static final int ROWS = MAX_ID + 1 + EXTRA_ROWS;
  /**
   * Floats of variant metadata per voxel id, three RGBA texels: (first extra row, variant count, 0, 0),
   * then the cumulative weight of variants 0..3 and 4..7 as a fraction of the total, ending in 1.
   * An id with a count of 0 has a single variant.
   */
  public static final int META_FLOATS = 12;
  /** Where the metadata starts in the face table, after all the rows. */
  public static final int META_START = ROWS * ID_FLOATS;
  /** Total floats in the face table. */
  public static final int FACE_FLOATS = META_START + (MAX_ID + 1) * META_FLOATS;

  /** Face table tint values below zero: follow the biome grass / foliage color, see {@link TintMap}. */
  private static final float TINT_GRASS = -1f;
  private static final float TINT_FOLIAGE = -2f;

  private static final int GRASS_PROBE = 0x0A1B2C;
  private static final int FOLIAGE_PROBE = 0x0D2E3F;
  private static final int WATER_PROBE = 0x10213D;

  /** A level whose only working method is {@code getBlockTint}, returning a marker per resolver. */
  private static final BlockAndTintGetter PROBE =
      (BlockAndTintGetter) Proxy.newProxyInstance(
          BlockClassifier.class.getClassLoader(),
          new Class<?>[] {BlockAndTintGetter.class},
          (proxy, method, args) -> {
            // Matched by shape, since method names differ between dev and production mappings.
            if (args != null && args.length == 2 && args[1] instanceof ColorResolver r) {
              if (r == BiomeColors.GRASS_COLOR_RESOLVER) return GRASS_PROBE;
              if (r == BiomeColors.FOLIAGE_COLOR_RESOLVER) return FOLIAGE_PROBE;
              return WATER_PROBE;
            }
            Class<?> t = method.getReturnType();
            if (t == float.class) return 1f;
            if (t == int.class) return 0;
            if (t == boolean.class) return false;
            if (t == long.class) return 0L;
            if (t == double.class) return 0d;
            return null;
          });

  /** Set in the color table (above the RGB bits) for blocks that cast ambient occlusion. */
  public static final int OCCLUDER_BIT = 1 << 24;

  /**
   * How much a block wants to represent its cell in a coarse level, 0..{@link #LEAVES}. See
   * {@link #priorityOf}.
   */
  public static final int LEAVES = 7;

  private static final byte[] priorities = new byte[MAX_ID + 1];

  private static final int UNKNOWN = -1;
  private static final int FALLBACK_COLOR = 0xA0B8C8;

  private static int[] voxelIds;
  private static int nextId = 1;
  private static boolean warnedFull;
  private static boolean warnedExtra;
  private static int nextExtraRow = MAX_ID + 1;

  /** RGB (plus {@link #OCCLUDER_BIT}) per voxel id, indexed by voxel id. */
  private static final int[] colors = new int[MAX_ID + 1];
  private static boolean colorsDirty = true;

  /** Face table: {@link #ROWS} rows of {@link #ID_FLOATS}, then {@link #META_FLOATS} per voxel id. */
  private static final float[] faces = new float[FACE_FLOATS];

  /** Range of floats in {@link #faces} changed since the last upload. */
  private static final class Dirty {
    int min = Integer.MAX_VALUE;
    int max = -1;

    void mark(int from, int to) {
      min = Math.min(min, from);
      max = Math.max(max, to);
    }

    void markAll(int from, int to) {
      min = from;
      max = to;
    }
  }

  // Separate ranges, since a new block touches its own row, maybe some extra rows, and its metadata,
  // which are far apart in the table.
  private static final Dirty dirtyIds = new Dirty();
  private static final Dirty dirtyExtra = new Dirty();
  private static final Dirty dirtyMeta = new Dirty();

  private BlockClassifier() {}

  /** Forgets every assigned id. Voxel data built with the old ids must be rebuilt afterwards. */
  public static void reset() {
    voxelIds = null;
    nextId = 1;
    warnedFull = false;
    warnedExtra = false;
    nextExtraRow = MAX_ID + 1;
    Arrays.fill(colors, 0);
    Arrays.fill(priorities, (byte) 0);
    Arrays.fill(faces, 0f);
    colorsDirty = true;
    dirtyIds.markAll(0, (MAX_ID + 1) * ID_FLOATS - 1);
    dirtyExtra.markAll((MAX_ID + 1) * ID_FLOATS, META_START - 1);
    dirtyMeta.markAll(META_START, FACE_FLOATS - 1);
  }

  public static int voxelId(BlockState state) {
    if (voxelIds == null) {
      voxelIds = new int[Block.BLOCK_STATE_REGISTRY.size()];
      Arrays.fill(voxelIds, UNKNOWN);
    }

    return voxelIdOfStateId(Block.BLOCK_STATE_REGISTRY.getId(state), state);
  }

  /** Same as {@link #voxelId(BlockState)}, for a raw block state id as sent over the wire. */
  public static int voxelIdOfStateId(int stateId) {
    return voxelIdOfStateId(stateId, null);
  }

  private static int voxelIdOfStateId(int stateId, BlockState state) {
    if (voxelIds == null) {
      voxelIds = new int[Block.BLOCK_STATE_REGISTRY.size()];
      Arrays.fill(voxelIds, UNKNOWN);
    }
    if (stateId < 0 || stateId >= voxelIds.length) return 0;

    int id = voxelIds[stateId];
    if (id == UNKNOWN) {
      if (state == null) state = Block.stateById(stateId);
      id = classify(state);
      voxelIds[stateId] = id;
    }
    return id;
  }

  private static int classify(BlockState state) {
    if (state.isAir()) return 0;
    if (!state.getFluidState().isEmpty()) return 0;
    if (state.getRenderShape() != RenderShape.MODEL) return 0;
    if (!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return 0;

    if (nextId > MAX_ID) {
      if (!warnedFull) {
        LookMod.LOGGER.warn("Out of voxel ids ({}); some blocks will be missing", MAX_ID);
        warnedFull = true;
      }
      return 0;
    }
    int id = nextId++;

    int color;
    try {
      color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
    } catch (RuntimeException e) {
      color = 0;
    }
    priorities[id] = (byte) priorityOf(state);
    colors[id] = (color == 0 ? FALLBACK_COLOR : color) & 0xFFFFFF;
    if (state.canOcclude()) colors[id] |= OCCLUDER_BIT;
    colorsDirty = true;

    try {
      bakeFaces(state, id);
    } catch (RuntimeException e) {
      // Leave the faces empty so the shader falls back to the map color.
      LookMod.LOGGER.warn("Could not read model of {}: {}", state, e.toString());
    }

    return id;
  }

  /** Priority of a voxel id for {@link LodReduce}; 0 for air. */
  public static int priority(int id) {
    return priorities[id];
  }

  /**
   * Hardcoded by tag, in order of how much a block dominates the view of a surface from far away:
   * leaves, snow, grass-like tops, sand and terracotta, everything else, logs, dirt and stone, ores.
   */
  private static int priorityOf(BlockState state) {
    if (state.is(BlockTags.LEAVES)) return LEAVES;
    if (state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) return 6;
    if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL)
        || state.is(Blocks.CRIMSON_NYLIUM) || state.is(Blocks.WARPED_NYLIUM)) return 5;
    if (state.is(BlockTags.SAND) || state.is(BlockTags.TERRACOTTA) || state.is(Blocks.GRAVEL)
        || state.is(Blocks.RED_SAND)) return 4;
    if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore")) return 1;
    if (state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD)) return 2;
    if (state.is(BlockTags.LOGS)) return 2;
    return 3;
  }

  // --- Face table ------------------------------------------------------------------------------

  private static void bakeFaces(BlockState state, int id) {
    Minecraft mc = Minecraft.getInstance();
    BakedModel model = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);

    if (!(model instanceof WeightedBakedModel weighted)) {
      bakeRow(model, state, id);
      return;
    }

    // A blockstate with several variants (grass rotates, for example). Vanilla picks one per
    // position, so every variant gets its own row and the shader picks per voxel.
    List<WeightedEntry.Wrapper<BakedModel>> variants = ((WeightedBakedModelAccessor) weighted).look$variants();
    int count = Math.min(variants.size(), MAX_VARIANTS);
    if (variants.size() > MAX_VARIANTS) {
      LookMod.LOGGER.warn("{} has {} variants; only using the first {}", state, variants.size(), MAX_VARIANTS);
    }

    List<Integer> rows = new ArrayList<>();
    List<Integer> weights = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int row;
      if (i == 0) {
        row = id;
      } else if (nextExtraRow < ROWS) {
        row = nextExtraRow++;
      } else {
        if (!warnedExtra) {
          LookMod.LOGGER.warn("Out of extra variant rows ({}); some blocks will lose variants", EXTRA_ROWS);
          warnedExtra = true;
        }
        break;
      }

      WeightedEntry.Wrapper<BakedModel> variant = variants.get(i);
      bakeRow(variant.data(), state, row);
      rows.add(row);
      weights.add(Math.max(1, variant.weight().asInt()));
    }

    if (rows.size() > 1) writeVariants(id, rows, weights);
  }

  /** Bakes one concrete (non-weighted) model into row {@code row} of the face table. */
  private static void bakeRow(BakedModel model, BlockState state, int row) {
    int[] layerCount = new int[6];
    // Quads can sit in the per-direction (culled) lists or in the general list.
    for (int i = -1; i < 6; i++) {
      Direction cull = i < 0 ? null : Direction.from3DDataValue(i);
      // Fresh source per call, so a model that picks something at random picks the same each time.
      List<BakedQuad> quads = model.getQuads(state, cull, RandomSource.create(42L));
      for (BakedQuad quad : quads) {
        int face = quad.getDirection().get3DDataValue();
        if (layerCount[face] >= LAYERS) continue;
        if (!onBoundary(quad.getVertices(), face)) continue;

        float tint = quad.getTintIndex() >= 0 ? tintOf(state, quad.getTintIndex()) : 0xFFFFFF;

        if (writeLayer(row, face, layerCount[face], quad.getVertices(), tint)) layerCount[face]++;
      }
    }

    if (row > MAX_ID) {
      dirtyExtra.mark(row * ID_FLOATS, (row + 1) * ID_FLOATS - 1);
    } else {
      dirtyIds.mark(row * ID_FLOATS, (row + 1) * ID_FLOATS - 1);
    }
  }

  /**
   * Writes the metadata of {@code id}. {@code rows} are the face table rows of the variants in order;
   * the first is {@code id} itself and the rest are consecutive.
   */
  private static void writeVariants(int id, List<Integer> rows, List<Integer> weights) {
    int o = META_START + id * META_FLOATS;
    faces[o] = rows.get(1);
    faces[o + 1] = rows.size();

    int total = 0;
    for (int w : weights) total += w;

    int sum = 0;
    for (int i = 0; i < MAX_VARIANTS; i++) {
      if (i < weights.size()) sum += weights.get(i);
      faces[o + 4 + i] = i < weights.size() - 1 ? (float) sum / total : 1f;
    }

    dirtyMeta.mark(o, o + META_FLOATS - 1);
  }

  /**
   * The tint of a quad: an RGB constant (0..0xFFFFFF), or {@link #TINT_GRASS} / {@link #TINT_FOLIAGE}
   * when it follows the biome. Which one is found by asking vanilla's {@code BlockColors} with a
   * level that answers every biome lookup with a marker color, so modded blocks work too.
   */
  private static float tintOf(BlockState state, int tintIndex) {
    BlockColors colors = Minecraft.getInstance().getBlockColors();
    try {
      int probe = colors.getColor(state, PROBE, BlockPos.ZERO, tintIndex) & 0xFFFFFF;
      if (probe == GRASS_PROBE) return TINT_GRASS;
      if (probe == FOLIAGE_PROBE) return TINT_FOLIAGE;
    } catch (RuntimeException e) {
      // Not biome based, or it needs more of a level than the probe has.
    }

    try {
      return colors.getColor(state, null, null, tintIndex) & 0xFFFFFF;
    } catch (RuntimeException e) {
      return 0xFFFFFF;
    }
  }

  private static float vx(int[] v, int vertex, int component) {
    return Float.intBitsToFloat(v[vertex * 8 + component]);
  }

  /** True when all four vertices lie on the block boundary plane of {@code face}. */
  private static boolean onBoundary(int[] v, int face) {
    int axis = face >> 1;
    float plane = (face & 1) == 0 ? 0f : 1f;
    // Face order is DOWN, UP, NORTH, SOUTH, WEST, EAST: the axis is y, z, x.
    int comp = axis == 0 ? 1 : (axis == 1 ? 2 : 0);
    // NORTH (2) sits at z = 0, SOUTH (3) at z = 1, WEST (4) at x = 0, EAST (5) at x = 1.
    for (int i = 0; i < 4; i++) {
      if (Math.abs(vx(v, i, comp) - plane) > 1e-3f) return false;
    }
    return true;
  }

  /**
   * Fits uv = uv0 + a * dUV/da + b * dUV/db to the quad, where (a, b) are the two in-face block
   * coordinates in [0, 1]: (x, z) on y faces, (x, y) on z faces and (y, z) on x faces. Each layer
   * is two texels: (u0, v0, du/da, dv/da) and (du/db, dv/db, tint, 1).
   */
  private static boolean writeLayer(int row, int face, int layer, int[] v, float tint) {
    int ca;
    int cb;
    switch (face >> 1) {
      case 0 -> { ca = 0; cb = 2; }
      case 1 -> { ca = 0; cb = 1; }
      default -> { ca = 1; cb = 2; }
    }

    float a0 = vx(v, 0, ca), b0 = vx(v, 0, cb);
    float u0 = vx(v, 0, 4), v0 = vx(v, 0, 5);
    // Vertices 1 and 3 are the neighbors of vertex 0 around the quad.
    float a1 = vx(v, 1, ca) - a0, b1 = vx(v, 1, cb) - b0;
    float u1 = vx(v, 1, 4) - u0, v1 = vx(v, 1, 5) - v0;
    float a3 = vx(v, 3, ca) - a0, b3 = vx(v, 3, cb) - b0;
    float u3 = vx(v, 3, 4) - u0, v3 = vx(v, 3, 5) - v0;

    float det = a1 * b3 - b1 * a3;
    if (Math.abs(det) < 1e-6f) return false;

    float duda = (u1 * b3 - u3 * b1) / det;
    float dvda = (v1 * b3 - v3 * b1) / det;
    float dudb = (u3 * a1 - u1 * a3) / det;
    float dvdb = (v3 * a1 - v1 * a3) / det;

    float originU = u0 - duda * a0 - dudb * b0;
    float originV = v0 - dvda * a0 - dvdb * b0;

    int o = row * ID_FLOATS + (face * LAYERS + layer) * LAYER_FLOATS;
    faces[o] = originU;
    faces[o + 1] = originV;
    faces[o + 2] = duda;
    faces[o + 3] = dvda;
    faces[o + 4] = dudb;
    faces[o + 5] = dvdb;
    faces[o + 6] = tint;
    faces[o + 7] = 1f;
    return true;
  }

  public static float[] faces() {
    return faces;
  }

  /**
   * Returns the float ranges of {@link #faces()} changed since the last call, each as
   * {@code {first, count}}. Empty when nothing changed.
   */
  public static List<int[]> takeFaceRanges() {
    List<int[]> out = new ArrayList<>(3);
    for (Dirty d : new Dirty[] {dirtyIds, dirtyExtra, dirtyMeta}) {
      if (d.max < 0) continue;
      out.add(new int[] {d.min, d.max - d.min + 1});
      d.min = Integer.MAX_VALUE;
      d.max = -1;
    }
    return out;
  }

  // --- Colors ----------------------------------------------------------------------------------

  public static int[] colors() {
    return colors;
  }

  /** Returns true once after any new color was added. */
  public static boolean takeColorsDirty() {
    boolean d = colorsDirty;
    colorsDirty = false;
    return d;
  }
}
