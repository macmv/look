package dev.macmv.look.gl;

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
}
