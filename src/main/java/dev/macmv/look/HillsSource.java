package dev.macmv.look;

import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * A procedural heightmap, to exercise the pyramid beyond the chunks the server sends. It needs no
 * world data and can produce any section at any level, so it is {@link #enumerated}. It is a
 * stand-in for the seed-based terrain source: enable it for the coarse levels by changing the
 * source selection in {@link VoxelWorld}.
 */
public final class HillsSource implements VoxelSource {
  @Override
  public float reachBlocks() {
    return Float.POSITIVE_INFINITY;
  }

  @Override
  public boolean enumerated() {
    return true;
  }

  /** Terrain height in blocks at a column. */
  static double height(double x, double z) {
    return 72
        + 55 * Math.sin(x * 0.0021) * Math.cos(z * 0.0017)
        + 22 * Math.sin(x * 0.011 + z * 0.007)
        + 7 * Math.sin(x * 0.043) * Math.sin(z * 0.037);
  }

  @Override
  public int fill(int level, int sx, int sy, int sz, ByteBuffer out) {
    ClientLevel l = Minecraft.getInstance().level;
    if (l == null) return UNAVAILABLE;

    int stone = BlockClassifier.voxelId(Blocks.STONE.defaultBlockState());
    int grass = BlockClassifier.voxelId(Blocks.GRASS_BLOCK.defaultBlockState());

    int size = 1 << level;
    int half = size >> 1;
    int x0 = sx << (level + 4);
    int y0 = sy << (level + 4);
    int z0 = sz << (level + 4);
    int minY = l.getMinBuildHeight();
    int maxY = l.getMaxBuildHeight();

    int solid = 0;
    for (int z = 0; z < 16; z++) {
      for (int x = 0; x < 16; x++) {
        double h = height(x0 + x * size + half, z0 + z * size + half);
        for (int y = 0; y < 16; y++) {
          int by = y0 + y * size + half;
          int id = 0;
          if (by >= minY && by < maxY && by < h) id = by + size >= h ? grass : stone;
          if (id != 0) solid++;
          out.putShort(((y * 16 + z) * 16 + x) << 1, (short) id);
        }
      }
    }
    return solid;
  }
}
