package dev.macmv.look;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Maps block states to voxel ids (0 is air) and keeps a color per voxel id.
 *
 * <p>Only full opaque-ish cubes become voxels. Fluids and non-cube blocks are air for now, so
 * vanilla's translucent layer still draws water, and plants/stairs/etc. are simply missing until
 * non-cube support exists.
 */
public final class BlockClassifier {
  /** Voxel ids are u16 on the GPU. */
  public static final int MAX_ID = 0xFFFF;

  private static final int UNKNOWN = -1;
  private static final int FALLBACK_COLOR = 0xA0B8C8;

  private static int[] voxelIds;
  /** RGB per voxel id, indexed by voxel id. */
  private static final int[] colors = new int[MAX_ID + 1];
  private static boolean colorsDirty = true;

  private BlockClassifier() {}

  public static int voxelId(BlockState state) {
    if (voxelIds == null) {
      voxelIds = new int[Block.BLOCK_STATE_REGISTRY.size()];
      Arrays.fill(voxelIds, UNKNOWN);
    }

    int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
    if (stateId < 0 || stateId >= voxelIds.length) return 0;

    int id = voxelIds[stateId];
    if (id == UNKNOWN) {
      id = classify(state, stateId);
      voxelIds[stateId] = id;
    }
    return id;
  }

  private static int classify(BlockState state, int stateId) {
    if (state.isAir()) return 0;
    if (!state.getFluidState().isEmpty()) return 0;
    if (state.getRenderShape() != RenderShape.MODEL) return 0;
    if (!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return 0;

    // Voxel id 0 is air, so shift state ids up by one.
    int id = stateId + 1;
    if (id > MAX_ID) return 0;

    int color;
    try {
      color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
    } catch (RuntimeException e) {
      color = 0;
    }
    colors[id] = color == 0 ? FALLBACK_COLOR : color;
    colorsDirty = true;

    return id;
  }

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
