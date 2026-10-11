package dev.macmv.look;

import java.util.List;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL33C;

/**
 * Cheap profiling for the voxel pass. GPU time comes from timer queries read a few frames late (so
 * nothing stalls); the rest is CPU-side. Refreshed once a second while the debug view is on and drawn
 * by {@link DebugHud}.
 */
public final class RenderStats {
  private static final int RING = 4;
  private static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

  private static final int[] queries = new int[RING];
  private static final boolean[] pending = new boolean[RING];
  private static int head;
  private static boolean measuring;

  private static long gpuNanos;
  private static int gpuSamples;
  private static long updateNanos;
  private static int frames;
  private static long lastReport = System.nanoTime();
  private static List<String> lines = List.of();

  private RenderStats() {}

  public static void beginGpu() {
    if (queries[0] == 0) {
      for (int i = 0; i < RING; i++) queries[i] = GL33C.glGenQueries();
    }
    collect();

    measuring = !pending[head];
    if (measuring) GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, queries[head]);
  }

  public static void endGpu() {
    if (!measuring) return;
    GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
    pending[head] = true;
    head = (head + 1) % RING;
  }

  public static void addUpdate(long nanos) {
    updateNanos += nanos;
  }

  private static void collect() {
    for (int i = 0; i < RING; i++) {
      if (!pending[i]) continue;
      if (GL33C.glGetQueryObjecti(queries[i], GL33C.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
      gpuNanos += GL33C.glGetQueryObjectui64(queries[i], GL33C.GL_QUERY_RESULT);
      gpuSamples++;
      pending[i] = false;
    }
  }

  /** Lines for the debug HUD, refreshed about once a second. */
  public static List<String> lines() {
    return lines;
  }

  /** Call once per voxel frame. */
  public static void endFrame() {
    frames++;
    long now = System.nanoTime();
    if (now - lastReport < REPORT_INTERVAL_NANOS) return;

    if (LookState.debugView && frames > 0) {
      Minecraft mc = Minecraft.getInstance();
      double gpuMs = gpuSamples > 0 ? gpuNanos / 1e6 / gpuSamples : 0;
      double updMs = updateNanos / 1e6 / frames;
      int w = mc.getWindow().getWidth();
      int h = mc.getWindow().getHeight();
      double nsPerPixel = gpuSamples > 0 ? (gpuNanos / (double) gpuSamples) / ((double) w * h) : 0;

      lines = List.of(
          String.format("look: %d fps", mc.getFps()),
          String.format("raycast: %.2f ms (%.1f ns/px at %dx%d)", gpuMs, nsPerPixel, w, h),
          String.format("upload: %.2f ms/frame, %d sections rebuilt/s", updMs, VoxelWorld.takeRebuilt()),
          String.format("bricks: %d / %d, queued: %d", VoxelWorld.usedSlots(), VoxelWorld.capacity(), VoxelWorld.queued()));
    }

    gpuNanos = 0;
    gpuSamples = 0;
    updateNanos = 0;
    frames = 0;
    lastReport = now;
  }
}
