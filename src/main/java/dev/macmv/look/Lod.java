package dev.macmv.look;

/**
 * Shape of the level-of-detail pyramid.
 *
 * <p>Level {@code k} stores voxels that are {@code 2^k} blocks wide. The shader picks the level from
 * the horizontal (Chebyshev) distance {@code h} of the ray position from the camera:
 *
 * <pre>
 *   level 0: h in [0, B)        level k >= 1: h in [B * 2^(k-1), B * 2^k)
 * </pre>
 *
 * where {@code B} is {@link #BASE_BLOCKS}. In sections of the level's own size that is the same
 * window for every level: a square ring from {@code BASE_CHUNKS / 2} to {@code BASE_CHUNKS}
 * sections around the camera (level 0 has no hole). So every level costs about the same memory, and
 * the total reach doubles with each added level: 8 levels at the default base of 16 chunks reach
 * 2048 chunks.
 *
 * <p>Configurable with {@code -Dlook.lod.levels=N} and {@code -Dlook.lod.baseChunks=N}.
 */
public final class Lod {
  public static final int MAX_LEVELS = 8;

  public static final int LEVELS = clamp(Integer.getInteger("look.lod.levels", MAX_LEVELS), 1, MAX_LEVELS);
  /** Radius of level 0 in chunks. The wrapping grid limits it, see {@link VoxelWorld#GRID_XZ}. */
  public static final int BASE_CHUNKS = clamp(Integer.getInteger("look.lod.baseChunks", 16), 4, 56);
  public static final float BASE_BLOCKS = BASE_CHUNKS * 16f;

  private Lod() {}

  /**
   * Largest section distance (Chebyshev, in sections of that level) that can be sampled at
   * {@code level}: the ring's outer edge, one section for the camera's offset inside its own
   * section, and one for rays that overshoot the edge inside a brick.
   */
  public static int outer(int level) {
    return BASE_CHUNKS + 2;
  }

  /**
   * Smallest section distance that is ever sampled at {@code level}; nearer sections are not built.
   * The ring starts at {@code BASE_CHUNKS / 2}; this stays two sections inside it, for the camera's
   * offset from the player (third person) and its position inside its own section.
   */
  public static int inner(int level) {
    return level == 0 ? 0 : Math.max(0, BASE_CHUNKS / 2 - 2);
  }

  /** Farthest distance in blocks the pyramid can represent. */
  public static float maxDist() {
    return BASE_BLOCKS * (1 << (LEVELS - 1));
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
