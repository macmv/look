package dev.macmv.look;

import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
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
  /** Floats per voxel id in the face table. */
  public static final int ID_FLOATS = 6 * LAYERS * LAYER_FLOATS;

  private static final int UNKNOWN = -1;
  private static final int FALLBACK_COLOR = 0xA0B8C8;

  private static int[] voxelIds;
  private static int nextId = 1;
  private static boolean warnedFull;

  /** RGB per voxel id, indexed by voxel id. */
  private static final int[] colors = new int[MAX_ID + 1];
  private static boolean colorsDirty = true;

  /** Face table, {@link #ID_FLOATS} per voxel id. */
  private static final float[] faces = new float[(MAX_ID + 1) * ID_FLOATS];
  private static int dirtyMin = Integer.MAX_VALUE;
  private static int dirtyMax = -1;

  private BlockClassifier() {}

  /** Forgets every assigned id. Voxel data built with the old ids must be rebuilt afterwards. */
  public static void reset() {
    voxelIds = null;
    nextId = 1;
    warnedFull = false;
    Arrays.fill(colors, 0);
    Arrays.fill(faces, 0f);
    colorsDirty = true;
    dirtyMin = 0;
    dirtyMax = MAX_ID;
  }

  public static int voxelId(BlockState state) {
    if (voxelIds == null) {
      voxelIds = new int[Block.BLOCK_STATE_REGISTRY.size()];
      Arrays.fill(voxelIds, UNKNOWN);
    }

    int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
    if (stateId < 0 || stateId >= voxelIds.length) return 0;

    int id = voxelIds[stateId];
    if (id == UNKNOWN) {
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
    colors[id] = color == 0 ? FALLBACK_COLOR : color;
    colorsDirty = true;

    try {
      bakeFaces(state, id);
    } catch (RuntimeException e) {
      // Leave the faces empty so the shader falls back to the map color.
      LookMod.LOGGER.warn("Could not read model of {}: {}", state, e.toString());
    }
    dirtyMin = Math.min(dirtyMin, id);
    dirtyMax = Math.max(dirtyMax, id);

    return id;
  }

  // --- Face table ------------------------------------------------------------------------------

  private static void bakeFaces(BlockState state, int id) {
    Minecraft mc = Minecraft.getInstance();
    BakedModel model = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);
    RandomSource random = RandomSource.create(42L);

    int[] layerCount = new int[6];
    // Quads can sit in the per-direction (culled) lists or in the general list.
    for (int i = -1; i < 6; i++) {
      Direction cull = i < 0 ? null : Direction.from3DDataValue(i);
      List<BakedQuad> quads = model.getQuads(state, cull, random);
      for (BakedQuad quad : quads) {
        int face = quad.getDirection().get3DDataValue();
        if (layerCount[face] >= LAYERS) continue;
        if (!onBoundary(quad.getVertices(), face)) continue;

        int tint = 0xFFFFFF;
        if (quad.getTintIndex() >= 0) {
          try {
            tint = mc.getBlockColors().getColor(state, null, null, quad.getTintIndex()) & 0xFFFFFF;
          } catch (RuntimeException e) {
            tint = 0xFFFFFF;
          }
        }

        if (writeLayer(id, face, layerCount[face], quad.getVertices(), tint)) layerCount[face]++;
      }
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
  private static boolean writeLayer(int id, int face, int layer, int[] v, int tint) {
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

    int o = id * ID_FLOATS + (face * LAYERS + layer) * LAYER_FLOATS;
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
   * Returns {@code {firstId, lastId}} of the face table entries changed since the last call, or
   * null when nothing changed.
   */
  public static int[] takeFaceRange() {
    if (dirtyMax < 0) return null;
    int[] r = {dirtyMin, dirtyMax};
    dirtyMin = Integer.MAX_VALUE;
    dirtyMax = -1;
    return r;
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
