package dev.macmv.look;

import net.minecraft.client.Minecraft;

/**
 * Runtime switches, set from the video settings screen ({@link LookVideoScreen}).
 *
 * <ul>
 *   <li>Voxel renderer: the voxel renderer or vanilla's chunk renderer.
 *   <li>Render mode: Vanilla (true-to-vanilla look) or Enhanced (god rays, waves, reflections).
 *   <li>Debug view: off, step-count heatmap of the voxel pass, then pyramid levels tinted, then no mipmaps, then unlit.
 *       Timing stats are always on screen.
 * </ul>
 *
 * Vanilla keeps compiling section meshes while the voxel renderer is on, and the voxel world keeps
 * queueing changes while vanilla renders. Switching is instant for the voxel world, and vanilla's
 * meshes are rebuilt (they leave water out while the voxel renderer is on).
 */
public final class LookState {
  public static boolean voxelEnabled = true;
  /** Render mode: false is Vanilla (true-to-vanilla look), true is Enhanced (god rays, waves, reflections). */
  public static boolean enhanced = false;
  /** 0 off, 1 step-count heatmap, 2 pyramid level tint, 3 no mipmaps, 4 unlit (albedo only), 5 no biome tint, 6 mip level fixed at 2, 7 alpha test off. */
  public static int debug = 0;

  private static final String[] DEBUG_NAMES = {"off", "step heatmap", "LOD levels", "no mipmaps", "unlit", "no biome tint", "mip level fixed at 2", "alpha test off"};
  public static final int DEBUG_COUNT = DEBUG_NAMES.length;

  private LookState() {}

  /** Name of the current debug view. */
  public static String debugName() {
    return DEBUG_NAMES[debug];
  }

  public static String debugName(int view) {
    return DEBUG_NAMES[view];
  }

  /** Switch between the voxel renderer and vanilla's chunk renderer. */
  public static void setVoxelEnabled(boolean enabled) {
    if (voxelEnabled == enabled) return;
    voxelEnabled = enabled;
    // Vanilla's meshes leave water out while the voxel renderer is on, so they need building again.
    Minecraft mc = Minecraft.getInstance();
    if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
  }
}
