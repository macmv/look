package dev.macmv.look;

/**
 * How the section rows of every pyramid level are stacked in the indirection texture. Level k holds
 * sections of {@code 16 << k} blocks, so its y range is the level 0 range shifted right by k.
 */
public final class SectionLayout {
  public final int[] levelMin = new int[Lod.LEVELS];
  public final int[] levelRows = new int[Lod.LEVELS];
  public final int[] levelRow0 = new int[Lod.LEVELS];
  /** Rows of the indirection texture, over all levels. */
  public final int totalRows;

  /** {@code minSection} and {@code sectionCount} are the world's (vanilla's), i.e. of level 0. */
  public SectionLayout(int minSection, int sectionCount) {
    int row = 0;
    for (int k = 0; k < Lod.LEVELS; k++) {
      levelMin[k] = minSection >> k;
      levelRows[k] = ((minSection + sectionCount - 1) >> k) - levelMin[k] + 1;
      levelRow0[k] = row;
      row += levelRows[k];
    }
    totalRows = row;
  }

  /** Per level {@code (lowest section y, rows, first texture row)}, padded to {@link Lod#MAX_LEVELS}. */
  public int[] params() {
    int[] out = new int[Lod.MAX_LEVELS * 3];
    for (int k = 0; k < Lod.LEVELS; k++) {
      out[k * 3] = levelMin[k];
      out[k * 3 + 1] = levelRows[k];
      out[k * 3 + 2] = levelRow0[k];
    }
    return out;
  }

  /** Index of the indirection cell of section ({@code sx}, {@code sy}, {@code sz}) at {@code level}. */
  public int cellIndex(int level, int sx, int sy, int sz) {
    int mask = VoxelWorld.GRID_XZ - 1;
    int row = levelRow0[level] + sy - levelMin[level];
    return (row * VoxelWorld.GRID_XZ + (sz & mask)) * VoxelWorld.GRID_XZ + (sx & mask);
  }
}
