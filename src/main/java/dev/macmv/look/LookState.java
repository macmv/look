package dev.macmv.look;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Runtime switches, driven by the rebindable keys in {@link LookKeys} (defaults F6, F7, F8, F9 and F4).
 *
 * <ul>
 *   <li>Toggle renderer: voxel renderer or vanilla's chunk renderer.
 *   <li>Toggle render mode: Vanilla (true-to-vanilla look) or Enhanced (god rays, waves, reflections).
 *   <li>Cycle debug view: off, step-count heatmap of the voxel pass, then pyramid levels tinted, then no mipmaps, then unlit.
 *       Timing stats are always on screen.
 *   <li>Reload: throw away all voxel data and rebuild it from the loaded chunks.
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

  private LookState() {}

  /** Name of the current debug view. */
  public static String debugName() {
    return DEBUG_NAMES[debug];
  }

  /** Call once per frame on the render thread. Key presses are only delivered with no screen open. */
  public static void poll() {
    Minecraft mc = Minecraft.getInstance();

    while (LookKeys.VERIFY.consumeClick()) {
      String summary;
      try {
        summary = VoxelWorld.verify();
      } catch (RuntimeException e) {
        LookMod.LOGGER.error("verify failed", e);
        summary = "failed: " + e;
      }
      mc.gui.getChat().addMessage(Component.literal("Look verify: " + summary));
    }

    while (LookKeys.TOGGLE_RENDERER.consumeClick()) {
      voxelEnabled = !voxelEnabled;
      mc.gui.setOverlayMessage(Component.literal("Look renderer: " + (voxelEnabled ? "voxel" : "vanilla")), false);
      // Vanilla's meshes leave water out while the voxel renderer is on, so they need building again.
      if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
    }

    while (LookKeys.TOGGLE_RENDER_MODE.consumeClick()) {
      enhanced = !enhanced;
      mc.gui.setOverlayMessage(Component.literal("Look render mode: " + (enhanced ? "Enhanced" : "Vanilla")), false);
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
