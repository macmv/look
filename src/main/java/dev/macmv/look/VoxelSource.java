package dev.macmv.look;

import java.nio.ByteBuffer;

/**
 * Where the blocks of one or more pyramid levels come from. {@link VoxelWorld} owns residency, slots
 * and GL; a source only has to produce bricks. Assign sources per level with
 * {@link VoxelWorld#setSource}.
 *
 * <p>A source feeds the world in one of two ways, and may use both:
 * <ul>
 *   <li><b>Push:</b> call {@link VoxelWorld#invalidate} whenever a section's content is new or
 *       changed (the world source does this for chunk packets and block updates).
 *   <li><b>Pull:</b> return true from {@link #enumerated} and the world asks for every section of
 *       the level that falls inside the view, as the camera moves. Use this for generators that can
 *       produce any section on demand.
 * </ul>
 *
 * <p>All methods run on the render thread.
 */
public interface VoxelSource {
  /** Returned by {@link #fill} when the section has no data and should not be tracked. */
  int UNAVAILABLE = -1;
  /**
   * Returned by {@link #fill} when the data is being produced in the background. The section waits
   * in the LOADING state until the source calls {@link VoxelWorld#invalidate} for it, which makes
   * the world ask again.
   */
  int PENDING = -2;

  /**
   * Writes the 4096 voxels of a section into {@code out} (absolute puts, u16 voxel ids from
   * {@link BlockClassifier}, index {@code (y * 16 + z) * 16 + x}) and returns how many are not air.
   * Returns 0 for an all-air section (then {@code out} may be left unwritten, unless the source is
   * layered under another, see {@link LayeredSource}), {@link #UNAVAILABLE} if there is no data and
   * {@link #PENDING} if it is not ready yet. A voxel at {@code level} covers {@code 2^level} blocks in each direction; the
   * section's min corner is at block {@code (sx, sy, sz) << (level + 4)}.
   */
  int fill(int level, int sx, int sy, int sz, ByteBuffer out);

  /**
   * How far from the camera this source can provide anything, in blocks. Bounds the ray march and
   * the size of the window the world keeps resident.
   */
  float reachBlocks();

  /**
   * Whether this source's levels are built from the chunks the client has loaded, so the world
   * source announces chunk arrivals for them.
   */
  default boolean usesLoadedChunks() {
    return false;
  }

  /** Called once per frame before sections are rebuilt, for sources that work in the background. */
  default void poll() {}

  /** Whether the world should pull every in-view section of this source's levels. */
  default boolean enumerated() {
    return false;
  }

  /** Whether the column still exists. Tracked sections of columns that vanished are dropped. */
  default boolean has(int level, int sx, int sz) {
    return true;
  }

  /** Announce every section you currently have via {@link VoxelWorld#invalidate}. */
  default void announceAll() {}

  /** Drop cached data; the world was cleared (level switch or reload). */
  default void reset() {}

  /** Drop cached data farther than {@code radiusChunks} (Chebyshev) from the given chunk. */
  default void trim(int chunkX, int chunkZ, int radiusChunks) {}
}
