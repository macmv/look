package dev.macmv.look;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Runtime switches, driven by the rebindable keys in {@link LookKeys} (defaults F7, F8 and F9).
 *
 * <ul>
 *   <li>Toggle renderer: voxel renderer or vanilla's chunk renderer.
 *   <li>Cycle debug view: off, step-count heatmap of the voxel pass, then pyramid levels tinted, then no mipmaps, then unlit.
 *       Timing stats are always on screen.
 *   <li>Reload: throw away all voxel data and rebuild it from the loaded chunks.
 * </ul>
 *
 * Vanilla keeps compiling section meshes while the voxel renderer is on, and the voxel world keeps
 * queueing changes while vanilla renders, so switching either way is instant.
 */
public final class LookState {
  public static boolean voxelEnabled = true;
  /** 0 off, 1 step-count heatmap, 2 pyramid level tint, 3 no mipmaps, 4 unlit (albedo only). */
  public static int debug = 0;

  private static final String[] DEBUG_NAMES = {"off", "step heatmap", "LOD levels", "no mipmaps", "unlit"};

  private LookState() {}

  /** Name of the current debug view. */
  public static String debugName() {
    return DEBUG_NAMES[debug];
  }

  /** Call once per frame on the render thread. Key presses are only delivered with no screen open. */
  public static void poll() {
    Minecraft mc = Minecraft.getInstance();

    while (LookKeys.TOGGLE_RENDERER.consumeClick()) {
      voxelEnabled = !voxelEnabled;
      mc.gui.setOverlayMessage(Component.literal("Look renderer: " + (voxelEnabled ? "voxel" : "vanilla")), false);
    }

    while (LookKeys.TOGGLE_DEBUG.consumeClick()) {
      debug = (debug + 1) % DEBUG_NAMES.length;
      mc.gui.setOverlayMessage(Component.literal("Look debug view: " + DEBUG_NAMES[debug]), false);
    }

    while (LookKeys.RELOAD.consumeClick()) {
      VoxelWorld.refreshAll();
      mc.gui.setOverlayMessage(Component.literal("Look: reloading voxel data"), false);
    }
  }
}
