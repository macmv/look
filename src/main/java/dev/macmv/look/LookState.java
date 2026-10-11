package dev.macmv.look;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Runtime switches, driven by the rebindable keys in {@link LookKeys} (defaults F7 and F8).
 *
 * <ul>
 *   <li>Toggle renderer: voxel renderer or vanilla's chunk renderer.
 *   <li>Toggle heatmap: step-count heatmap of the voxel pass. Timing stats are always on screen.
 * </ul>
 *
 * Vanilla keeps compiling section meshes while the voxel renderer is on, so switching back is
 * instant.
 */
public final class LookState {
  public static boolean voxelEnabled = true;
  public static boolean heatmap = false;

  private LookState() {}

  /** Call once per frame on the render thread. Key presses are only delivered with no screen open. */
  public static void poll() {
    Minecraft mc = Minecraft.getInstance();

    while (LookKeys.TOGGLE_RENDERER.consumeClick()) {
      voxelEnabled = !voxelEnabled;
      // Events are ignored while vanilla renders, so rebuild everything on the way back.
      if (voxelEnabled) VoxelWorld.refreshAll();
      mc.gui.setOverlayMessage(Component.literal("Look renderer: " + (voxelEnabled ? "voxel" : "vanilla")), false);
    }

    while (LookKeys.TOGGLE_DEBUG.consumeClick()) {
      heatmap = !heatmap;
      mc.gui.setOverlayMessage(Component.literal("Look heatmap: " + (heatmap ? "on" : "off")), false);
    }
  }
}
