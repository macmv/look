package dev.macmv.look;

/**
 * Packs a pyramid level and section coordinates into one long. A section at level {@code k} is a
 * 16^3 brick of voxels that are each {@code 2^k} blocks wide, so it spans {@code 16 << k} blocks and
 * its coordinates are block coordinates shifted right by {@code k + 4}.
 *
 * <p>Layout, high to low: 1 unused bit (keys are never negative), 3 bits level, 16 bits y, 22 bits
 * x, 22 bits z. x, y and z are signed.
 */
public final class SectionKey {
  private SectionKey() {}

  public static long pack(int level, int x, int y, int z) {
    return ((long) level << 60) | ((y & 0xFFFFL) << 44) | ((x & 0x3FFFFFL) << 22) | (z & 0x3FFFFFL);
  }

  public static int level(long key) {
    return (int) (key >>> 60) & 7;
  }

  public static int x(long key) {
    return (int) (key << 20 >> 42);
  }

  public static int y(long key) {
    return (int) (key << 4 >> 48);
  }

  public static int z(long key) {
    return (int) (key << 42 >> 42);
  }
}
