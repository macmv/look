package dev.macmv.look.gl;

import dev.macmv.look.BlockClassifier;

/** Worlds the render tests draw. Pure functions of the block position, so they are reproducible. */
final class TestWorlds {
  private TestWorlds() {}

  /** Rolling hills (the same shape as HillsSource) with a row of showcase blocks floating above the origin. */
  static final TestScene.World HILLS = (x, y, z) -> {
    int showcase = showcase(x, y, z);
    if (showcase != 0) return showcase;

    double h = height(x, z);
    if (y >= h) return 0;
    if (y + 1 >= h) return h < 66 ? TestScene.SAND : TestScene.GRASS;
    if (y + 4 >= h) return TestScene.DIRT;
    return TestScene.STONE;
  };

  static double height(double x, double z) {
    return 72
        + 55 * Math.sin(x * 0.0021) * Math.cos(z * 0.0017)
        + 22 * Math.sin(x * 0.011 + z * 0.007)
        + 7 * Math.sin(x * 0.043) * Math.sin(z * 0.037);
  }

  /** One of each block at z = 8, and a log column behind them, to check every face and its orientation. */
  private static int showcase(int x, int y, int z) {
    if (z == 8 && y == 78) {
      return switch (x) {
        case 0 -> TestScene.GRASS;
        case 2 -> TestScene.DIRT;
        case 4 -> TestScene.LOG;
        case 6 -> TestScene.SAND;
        case 8 -> TestScene.STONE;
        default -> 0;
      };
    }
    if (z == 12 && x == 4 && y >= 78 && y < 82) return TestScene.LOG;
    return 0;
  }

  /**
   * A round pond in flat grass: the sand floor slopes down to y 53 in the middle, and the water is a
   * source level (7 eighths) one block under the bank. A log post stands in the water.
   */
  static final TestScene.World POND = (x, y, z) -> {
    double r = Math.sqrt((double) x * x + (double) z * z);
    int bottom = r < 14 ? 59 - (int) Math.round((14 - r) * 0.45) : 59;
    if (y > 59) return y < 64 && x == 6 && z == 2 ? TestScene.LOG : 0;
    if (r >= 14) return y == 59 ? TestScene.GRASS : (y >= 55 ? TestScene.DIRT : TestScene.STONE);
    if (y > bottom) {
      if (y > 58) return 0;
      return y == 58 ? TestScene.WATER | 7 << BlockClassifier.FILL_SHIFT : TestScene.WATER;
    }
    return y >= bottom - 2 ? TestScene.SAND : TestScene.STONE;
  };

  /** Flat stone with a row of blocks at z = 6: glowstone, lava, redstone ore, plain stone for comparison, and glowstone again. */
  static final TestScene.World EMISSIVE = (x, y, z) -> {
    if (y < 60) return y < 57 ? TestScene.STONE : TestScene.DIRT;
    if (y == 60 && z == 6 && x >= -4 && x <= 4 && (x & 1) == 0) {
      return switch (x) {
        case -4 -> TestScene.GLOWSTONE;
        case -2 -> TestScene.LAVA;
        case 0 -> TestScene.REDSTONE_ORE;
        case 2 -> TestScene.STONE;
        default -> TestScene.GLOWSTONE;
      };
    }
    return 0;
  };
}
