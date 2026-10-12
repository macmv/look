package dev.macmv.look;

import java.nio.ByteBuffer;

/**
 * Generated terrain for the coarse levels, with the chunks the client really has loaded put on top.
 * Where the seed source is unavailable (multiplayer, other dimensions) this is just the world
 * source, so it is safe to assign unconditionally.
 *
 * <p>Within a coarse section, every column whose chunk is loaded is replaced by the real blocks
 * (trees, caves, buildings and all), and the rest keeps the generated surface. So terrain turns
 * from real to generated at the edge of what the server has sent, not at a level boundary.
 */
public final class LayeredSource implements VoxelSource {
  private final WorldSource world;
  private final SeedSource seed;

  public LayeredSource(WorldSource world, SeedSource seed) {
    this.world = world;
    this.seed = seed;
  }

  @Override
  public boolean usesLoadedChunks() {
    return true;
  }

  @Override
  public float reachBlocks() {
    return seed.available() ? seed.reachBlocks() : world.reachBlocks();
  }

  @Override
  public boolean enumerated() {
    return seed.available();
  }

  @Override
  public boolean has(int level, int sx, int sz) {
    return seed.available() || world.has(level, sx, sz);
  }

  @Override
  public void announceAll() {
    world.announceAll();
  }

  @Override
  public void reset() {
    world.reset();
    seed.reset();
  }

  @Override
  public void trim(int chunkX, int chunkZ, int radiusChunks) {
    world.trim(chunkX, chunkZ, radiusChunks);
  }

  @Override
  public void poll() {
    seed.poll();
  }

  @Override
  public int fill(int level, int sx, int sy, int sz, ByteBuffer out) {
    if (!seed.available()) return world.fill(level, sx, sy, sz, out);

    // Level 0 is exact: a loaded chunk is used as is, and where there is none (outside the render
    // distance, or not received yet) the generated terrain stands in.
    if (level == 0) {
      int solid = world.fill(level, sx, sy, sz, out);
      return solid == UNAVAILABLE ? seed.fill(level, sx, sy, sz, out) : solid;
    }

    int solid = seed.fill(level, sx, sy, sz, out);
    if (solid < 0) return solid;

    // The seed source wrote the whole brick; loaded columns replace theirs.
    if (!world.mayHaveChunks(level, sx, sz) || world.overlay(level, sx, sy, sz, out) == 0) return solid;

    solid = 0;
    for (int i = 0; i < 4096; i++) {
      if (out.getShort(i << 1) != 0) solid++;
    }
    return solid;
  }
}
