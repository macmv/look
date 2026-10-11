package dev.macmv.look;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL33C;

/**
 * Cheap profiling, shown by {@link DebugHud} in both renderer modes so they can be compared.
 *
 * <p>Frame times are wall-clock between HUD draws (so they include vsync waits and the whole game,
 * not just rendering). Raycast GPU time comes from timer queries read a few frames late so nothing
 * stalls; it only exists while the voxel renderer is on.
 */
public final class RenderStats {
  private static final int RING = 4;
  private static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;
  private static final long MAX_FRAME_NANOS = 1_000_000_000L;

  private static final int[] queries = new int[RING];
  private static final boolean[] pending = new boolean[RING];
  private static int head;
  private static boolean measuring;

  // Accumulated over the current reporting interval.
  private static long gpuNanos;
  private static int gpuSamples;
  private static long updateNanos;
  private static int voxelFrames;
  private static int frames;
  private static long frameNanosSum;
  private static long frameNanosMax;

  private static long lastFrame;
  private static long lastReport = System.nanoTime();
  private static List<String> lines = List.of();

  private RenderStats() {}

  // --- Voxel pass hooks ------------------------------------------------------------------------

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

  /** Call once per voxel frame, after the pass. */
  public static void endVoxelFrame() {
    voxelFrames++;
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

  // --- Per-frame, any renderer -----------------------------------------------------------------

  /** Lines for the HUD, refreshed about once a second. */
  public static List<String> lines() {
    return lines;
  }

  /** Call exactly once per frame (the HUD draw does). */
  public static void tickFrame() {
    long now = System.nanoTime();

    // Skip the gap after a menu or pause, which would wreck the average.
    if (lastFrame != 0 && now - lastFrame < MAX_FRAME_NANOS) {
      long dt = now - lastFrame;
      frames++;
      frameNanosSum += dt;
      frameNanosMax = Math.max(frameNanosMax, dt);
    }
    lastFrame = now;

    if (now - lastReport >= REPORT_INTERVAL_NANOS) {
      report();
      lastReport = now;
    }
  }

  private static void report() {
    Minecraft mc = Minecraft.getInstance();
    List<String> out = new ArrayList<>();

    boolean voxel = LookState.voxelEnabled;
    out.add("renderer: " + (voxel ? "voxel" : "vanilla") + (voxel && LookState.debug == 1 ? " (heatmap)" : voxel && LookState.debug == 2 ? " (lod levels)" : ""));

    if (frames > 0) {
      out.add(String.format(
          "fps: %d | frame: %.2f ms avg, %.2f ms worst",
          mc.getFps(), frameNanosSum / 1e6 / frames, frameNanosMax / 1e6));
    } else {
      out.add(String.format("fps: %d", mc.getFps()));
    }

    if (voxel && voxelFrames > 0) {
      int w = mc.getWindow().getWidth();
      int h = mc.getWindow().getHeight();
      double gpuMs = gpuSamples > 0 ? gpuNanos / 1e6 / gpuSamples : 0;
      double nsPerPixel = gpuSamples > 0 ? (gpuNanos / (double) gpuSamples) / ((double) w * h) : 0;

      out.add(String.format("raycast: %.2f ms (%.1f ns/px at %dx%d)", gpuMs, nsPerPixel, w, h));
      out.add(String.format(
          "upload: %.2f ms/frame, %d sections rebuilt", updateNanos / 1e6 / voxelFrames, VoxelWorld.takeRebuilt()));
      out.add(String.format(
          "bricks: %d / %d, queued: %d", VoxelWorld.usedSlots(), VoxelWorld.capacity(), VoxelWorld.queued()));
      out.add("lod bricks: " + java.util.Arrays.toString(VoxelWorld.loadedPerLevel()));
    }

    lines = out;

    gpuNanos = 0;
    gpuSamples = 0;
    updateNanos = 0;
    voxelFrames = 0;
    frames = 0;
    frameNanosSum = 0;
    frameNanosMax = 0;
  }
}
