package dev.macmv.look;

/**
 * Picks the one voxel id that stands for a 2x2x2 group of samples in a coarse level, optimizing for
 * how the surface looks from far away rather than for what most of the cell holds.
 *
 * <p>A sample scores its {@link BlockClassifier#priority} (grass above dirt, snow above stone, ores
 * last), plus a large bonus when it is exposed to the sky side (nothing solid above it). Leaves
 * ignore exposure, since the canopy hides whatever is under it. The highest score wins, ties go to
 * the lowest id so the choice never flickers.
 */
final class LodReduce {
  private static final int EXPOSED_BONUS = 8;

  private LodReduce() {}

  /**
   * @param ids eight voxel ids, 0 for air
   * @param exposed whether the block above each sample is air
   */
  static int reduce(int[] ids, boolean[] exposed) {
    int best = 0;
    int bestScore = -1;
    for (int i = 0; i < ids.length; i++) {
      int id = ids[i];
      if (id == 0) continue;
      int p = BlockClassifier.priority(id);
      int score = p + (exposed[i] || p == BlockClassifier.LEAVES ? EXPOSED_BONUS : 0);
      if (score > bestScore || (score == bestScore && id < best)) {
        best = id;
        bestScore = score;
      }
    }
    return best;
  }
}
