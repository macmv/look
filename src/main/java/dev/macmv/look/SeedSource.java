package dev.macmv.look;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Terrain for the coarse levels, reconstructed from the seed. Singleplayer only: it borrows the
 * integrated server's overworld noise generator and samples it, one column per voxel, so it needs
 * nothing from the network and works arbitrarily far from the player.
 *
 * <p>The height of a column comes from the noise router's {@code initialDensityWithoutJaggedness},
 * the function vanilla itself uses to estimate the surface before a chunk exists. It is a smooth 2D
 * shape (continents, erosion, mountains, rivers) plus a vertical gradient, so the surface is the
 * highest point where it exceeds a threshold, found with a coarse scan down from the build limit and
 * a bisection. It does not know about jagged peaks, caves, overhangs, surface rules, trees or
 * structures. A block is picked from the biome at the surface.
 *
 * <p>Calibrated against {@code NoiseBasedChunkGenerator.getBaseHeight} on two seeds (1200 columns
 * each): the surface is within about 3.5 blocks on average (rms 6) of vanilla's, 14 times faster
 * (about 100 microseconds per column). The worst columns are jagged peaks, up to ~50 blocks off.
 *
 * <p>Sampling is the expensive part (tens of density evaluations per column), so it runs on a small
 * thread pool, one {@link Tile} per level-k section column (16 x 16 samples shared by all its
 * vertical sections). {@link #fill} answers {@link #PENDING} until a tile is ready, and {@link
 * #poll} tells the world when tiles finish.
 */
public final class SeedSource implements VoxelSource {
  public static final SeedSource INSTANCE = new SeedSource();

  /**
   * Density above which a point counts as terrain. Vanilla's preliminary surface uses 0.390625, but
   * 0 sits closer to the real boundary. It must stay above the world-top slide (about -0.08).
   */
  private static final double SURFACE_THRESHOLD = 0.0;
  /**
   * Blocks added to the found surface. Without the 3D noise and jaggedness terms the estimate is
   * consistently this much lower than the real terrain.
   */
  private static final int SURFACE_BIAS = 7;
  /** Smallest vertical step of the scan for the surface. Larger voxels scan with their own size. */
  private static final int MIN_STEP = 16;
  /** Tiles are only computed and kept within this many extra sections of the level's window. */
  private static final int WANT_MARGIN = 6;
  private static final int NO_TERRAIN = Integer.MIN_VALUE;
  private static final long EVICT_INTERVAL_NANOS = 5_000_000_000L;

  /** Stand-in for water, which is not a voxel. Close enough to the ocean from a distance. */
  private static final Block WATER = Blocks.LIGHT_BLUE_CONCRETE;

  private enum Surface {
    GRASS(Blocks.GRASS_BLOCK, Blocks.DIRT),
    SAND(Blocks.SAND, Blocks.SAND),
    RED_SAND(Blocks.RED_SAND, Blocks.TERRACOTTA),
    GRAVEL(Blocks.GRAVEL, Blocks.GRAVEL),
    SNOW(Blocks.SNOW_BLOCK, Blocks.DIRT),
    STONE(Blocks.STONE, Blocks.STONE);

    final Block top;
    final Block filler;

    Surface(Block top, Block filler) {
      this.top = top;
      this.filler = filler;
    }
  }

  /** What the generator gives us, resolved once per level. Immutable and safe to share. */
  private record Terrain(
      DensityFunction density, Climate.Sampler climate, BiomeSource biomes, int seaLevel, int minY, int maxY) {}

  /** The surface of one level-k section column: 16 x 16 samples, indexed {@code z * 16 + x}. */
  private static final class Tile {
    final int[] top = new int[256];
    final byte[] surface = new byte[256];

    static Tile empty() {
      Tile t = new Tile();
      Arrays.fill(t.top, NO_TERRAIN);
      return t;
    }
  }

  private final Map<Long, Tile> tiles = new ConcurrentHashMap<>();
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
  /** Finished tiles the render thread has not told the world about yet. */
  private final Queue<Long> done = new ConcurrentLinkedQueue<>();
  private final ThreadPoolExecutor pool;

  /** Bumped on every reset; jobs from an older generation drop their results. */
  private volatile int generation;
  private volatile Terrain terrain;
  private boolean resolved;
  private volatile int camX;
  private volatile int camZ;
  private long lastEvict = System.nanoTime();

  private SeedSource() {
    int threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
    int[] n = {0};
    pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), r -> {
      Thread t = new Thread(r, "look-seed-" + n[0]++);
      t.setDaemon(true);
      t.setPriority(Thread.NORM_PRIORITY - 1);
      return t;
    });
    pool.allowCoreThreadTimeOut(true);
  }

  /** Whether there is a generator to sample (singleplayer, overworld, noise based). */
  public boolean available() {
    return terrain != null;
  }

  // --- VoxelSource -----------------------------------------------------------------------------

  @Override
  public float reachBlocks() {
    return available() ? Float.POSITIVE_INFINITY : 0f;
  }

  @Override
  public boolean enumerated() {
    return available();
  }

  @Override
  public void reset() {
    generation++;
    pool.getQueue().clear();
    tiles.clear();
    inFlight.clear();
    done.clear();
    terrain = null;
    resolved = false;
  }

  @Override
  public void poll() {
    Minecraft mc = Minecraft.getInstance();
    if (!resolved) resolve(mc);
    if (mc.player != null) {
      camX = mc.player.getBlockX();
      camZ = mc.player.getBlockZ();
    }

    Long key;
    while ((key = done.poll()) != null) {
      int level = SectionKey.level(key);
      int sx = SectionKey.x(key);
      int sz = SectionKey.z(key);
      int min = VoxelWorld.levelMin(level);
      for (int sy = min; sy < min + VoxelWorld.levelRows(level); sy++) VoxelWorld.invalidate(level, sx, sy, sz);
    }

    long now = System.nanoTime();
    if (now - lastEvict > EVICT_INTERVAL_NANOS) {
      lastEvict = now;
      // Far tiles are re-sampled if the player ever goes back; keeping them all would grow forever.
      tiles.keySet().removeIf(k -> !wanted(SectionKey.level(k), SectionKey.x(k), SectionKey.z(k), WANT_MARGIN + 8));
    }
  }

  private void resolve(Minecraft mc) {
    resolved = true;
    terrain = null;
    // The sections of an enumerated source are only asked for during a sweep.
    VoxelWorld.requestSweep();

    IntegratedServer server = mc.getSingleplayerServer();
    ClientLevel client = mc.level;
    if (server == null || client == null || client.dimension() != Level.OVERWORLD) return;

    ServerLevel level = server.getLevel(client.dimension());
    if (level == null) return;
    ServerChunkCache cache = level.getChunkSource();
    ChunkGenerator generator = cache.getGenerator();
    if (!(generator instanceof NoiseBasedChunkGenerator noise)) return;

    NoiseGeneratorSettings settings = noise.generatorSettings().value();
    RandomState random = cache.randomState();
    int minY = settings.noiseSettings().minY();
    terrain =
        new Terrain(
            random.router().initialDensityWithoutJaggedness(),
            random.sampler(),
            generator.getBiomeSource(),
            settings.seaLevel(),
            minY,
            minY + settings.noiseSettings().height());
    LookMod.LOGGER.info("Seed terrain source ready (sea level {}, build range {}..{})", terrain.seaLevel, minY, terrain.maxY);
  }

  @Override
  public int fill(int level, int sx, int sy, int sz, ByteBuffer out) {
    Terrain t = terrain;
    if (t == null) return UNAVAILABLE;

    long key = SectionKey.pack(level, sx, 0, sz);
    Tile tile = tiles.get(key);
    if (tile == null) {
      if (inFlight.add(key)) pool.execute(new Job(key, level, sx, sz, t));
      return PENDING;
    }

    int size = 1 << level;
    int half = size >> 1;
    int y0 = sy << (level + 4);
    int fillerDepth = Math.max(4, 2 * size);
    int stone = BlockClassifier.voxelId(Blocks.STONE.defaultBlockState());
    int water = BlockClassifier.voxelId(WATER.defaultBlockState());

    // Always writes the whole brick, since a layered source may overlay loaded chunks on it.
    int solid = 0;
    for (int z = 0; z < 16; z++) {
      for (int x = 0; x < 16; x++) {
        int top = tile.top[z * 16 + x];
        Surface surface = Surface.values()[tile.surface[z * 16 + x]];
        int topId = BlockClassifier.voxelId(surface.top.defaultBlockState());
        int fillerId = BlockClassifier.voxelId(surface.filler.defaultBlockState());

        for (int y = 0; y < 16; y++) {
          int by = y0 + y * size + half;
          int id = 0;
          if (top != NO_TERRAIN && by >= t.minY && by < t.maxY) {
            if (by < top) {
              // The one voxel holding the surface is the surface block; a few below it are filler.
              id = by + size >= top ? topId : (top - by <= fillerDepth ? fillerId : stone);
            } else if (by < t.seaLevel) {
              id = water;
            }
          }
          if (id != 0) solid++;
          out.putShort(((y * 16 + z) * 16 + x) << 1, (short) id);
        }
      }
    }
    return solid;
  }

  // --- Sampling (background threads) -----------------------------------------------------------

  /** Whether a tile is within {@code margin} sections of the camera's window at its level. */
  private boolean wanted(int level, int sx, int sz, int margin) {
    long cx = ((long) sx << (level + 4)) + (8L << level);
    long cz = ((long) sz << (level + 4)) + (8L << level);
    long d = Math.max(Math.abs(cx - camX), Math.abs(cz - camZ));
    return d <= (long) (Lod.outer(level) + margin) * (16L << level);
  }

  private final class Job implements Runnable, Comparable<Job> {
    final long key;
    final int level;
    final int sx;
    final int sz;
    final int gen = generation;
    final Terrain terrain;
    /** Distance from the camera when queued; nearer tiles go first. */
    final long priority;

    Job(long key, int level, int sx, int sz, Terrain terrain) {
      this.key = key;
      this.level = level;
      this.sx = sx;
      this.sz = sz;
      this.terrain = terrain;
      long cx = ((long) sx << (level + 4)) + (8L << level);
      long cz = ((long) sz << (level + 4)) + (8L << level);
      this.priority = Math.max(Math.abs(cx - camX), Math.abs(cz - camZ));
    }

    @Override
    public int compareTo(Job other) {
      return Long.compare(priority, other.priority);
    }

    @Override
    public void run() {
      try {
        // Out of range now: skip. If it comes back into range the world asks again.
        if (gen != generation || !wanted(level, sx, sz, WANT_MARGIN)) return;

        Tile tile;
        try {
          tile = sample(terrain, level, sx, sz);
        } catch (RuntimeException e) {
          LookMod.LOGGER.warn("Seed terrain sampling failed at level {} ({}, {}): {}", level, sx, sz, e.toString());
          tile = Tile.empty();
        }

        if (gen != generation) return;
        tiles.put(key, tile);
        done.add(key);
      } finally {
        inFlight.remove(key);
      }
    }
  }

  private static Tile sample(Terrain t, int level, int sx, int sz) {
    Tile tile = new Tile();
    int size = 1 << level;
    int half = size >> 1;
    int step = Math.max(MIN_STEP, size);
    int x0 = sx << (level + 4);
    int z0 = sz << (level + 4);

    for (int z = 0; z < 16; z++) {
      for (int x = 0; x < 16; x++) {
        int bx = x0 + x * size + half;
        int bz = z0 + z * size + half;
        int i = z * 16 + x;

        int top = surfaceTop(t, bx, bz, step);
        tile.top[i] = top;
        if (top == NO_TERRAIN) continue;

        int by = Math.max(t.minY, Math.min(t.maxY - 1, top));
        Holder<Biome> biome = t.biomes.getNoiseBiome(bx >> 2, by >> 2, bz >> 2, t.climate);
        tile.surface[i] = (byte) surfaceOf(biome, top, t.seaLevel).ordinal();
      }
    }
    return tile;
  }

  /**
   * Height of the first air block above the terrain at a column: blocks below it are solid. Scans
   * down from the build limit in steps, then bisects the step that crossed the threshold (the
   * density only falls with height inside one step, so that is safe).
   */
  private static int surfaceTop(Terrain t, int x, int z, int step) {
    // Terrain at the very top means this is not a world shaped like the overworld.
    if (t.density.compute(new DensityFunction.SinglePointContext(x, t.maxY - 1, z)) > SURFACE_THRESHOLD) {
      return NO_TERRAIN;
    }

    for (int y = t.maxY - 1; y >= t.minY; y -= step) {
      if (t.density.compute(new DensityFunction.SinglePointContext(x, y, z)) <= SURFACE_THRESHOLD) continue;

      int solid = y; // above the threshold
      int air = Math.min(y + step, t.maxY); // at or below it, or the build limit
      while (air - solid > 1) {
        int mid = (solid + air) >>> 1;
        if (t.density.compute(new DensityFunction.SinglePointContext(x, mid, z)) > SURFACE_THRESHOLD) solid = mid;
        else air = mid;
      }
      return Math.min(solid + 1 + SURFACE_BIAS, t.maxY);
    }
    return NO_TERRAIN;
  }

  private static Surface surfaceOf(Holder<Biome> biome, int top, int seaLevel) {
    float temperature = biome.value().getBaseTemperature();

    if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || top <= seaLevel) {
      return temperature < 0.2f ? Surface.GRAVEL : Surface.SAND;
    }
    if (temperature <= 0.15f) return Surface.SNOW;
    if (biome.is(BiomeTags.IS_BEACH) || biome.is(Biomes.DESERT)) return Surface.SAND;
    if (biome.is(BiomeTags.IS_BADLANDS)) return Surface.RED_SAND;
    if (top >= 200 || (top >= 110 && biome.is(BiomeTags.IS_MOUNTAIN))) return Surface.STONE;
    return Surface.GRASS;
  }
}
