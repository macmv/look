package dev.macmv.look;

import dev.macmv.look.net.ClientWorldDescriptor;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Terrain for the coarse levels, reconstructed from the seed. It describes the overworld noise
 * generator to the native engine (see {@link WorldDescriptor}), from the integrated server in
 * singleplayer or from what the server sent on joining. The engine samples it, one column per
 * voxel, on its own threads, so it needs no terrain from the network and works arbitrarily far from
 * the player.
 *
 * <p>All the work is in {@code native/} (see {@code terrain.rs} for what the prediction is and how
 * good it is, within a fraction of a block of vanilla's surface on average, caves included, and {@code engine.rs} for the
 * threading). This class only forwards: it asks for the tile under a section the first time the
 * section is wanted, answers {@link #PENDING} until the tile is done, and then tells the world
 * which sections to ask again for.
 */
public final class SeedSource implements VoxelSource {
  public static final SeedSource INSTANCE = new SeedSource();

  /** Tiles this many sections beyond a level's window are not worth computing or keeping. */
  private static final int WANT_MARGIN = 6;
  /** Finished tiles reported to the world per frame, to keep the render thread's share small. */
  private static final int KEYS_PER_POLL = 512;

  /** What the top of a column is made of, in the order native/src/terrain.rs expects. */
  private static final Block[][] SURFACES = {
    {Blocks.GRASS_BLOCK, Blocks.DIRT},
    {Blocks.SAND, Blocks.SAND},
    {Blocks.RED_SAND, Blocks.TERRACOTTA},
    {Blocks.GRAVEL, Blocks.GRAVEL},
    {Blocks.SNOW_BLOCK, Blocks.DIRT},
    {Blocks.STONE, Blocks.STONE},
  };

  /** The native engine, or 0 when there is no generator to predict. */
  private long handle;
  private boolean resolved;
  private int[] ids = new int[0];
  /** The blocks trees are made of (see {@link NativeLib#terrainTreeBlocks}) and their voxel ids. */
  private BlockState[] treeBlocks = new BlockState[0];
  private int[] treeIds = new int[0];

  private final ByteBuffer keys = NativeLib.allocate(KEYS_PER_POLL * 8);
  private final ByteBuffer tint = NativeLib.allocate(768);
  private final long[] stats = new long[3];

  private SeedSource() {}

  /** Whether there is a generator to sample (overworld, noise based, and described to us). */
  public boolean available() {
    return handle != 0;
  }

  /** A line for the debug HUD, or null when unavailable. */
  public String statsLine() {
    if (handle == 0) return null;
    NativeLib.terrainStats(handle, stats);
    return String.format("seed tiles: %d queued, %d kept, %d computed; %s", stats[0], stats[1], stats[2], HeightField.statsLine());
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
    if (handle != 0) NativeLib.terrainDestroy(handle);
    handle = 0;
    resolved = false;
    ids = new int[0];
    treeBlocks = new BlockState[0];
    treeIds = new int[0];
    HeightField.reset();
  }

  /**
   * The voxel ids of the predicted terrain (see {@link #pushIds}): per surface kind the top and filler
   * id, then stone and water. Empty until the engine runs.
   */
  public int[] voxelIds() {
    return ids;
  }

  @Override
  public void poll() {
    Minecraft mc = Minecraft.getInstance();
    if (!resolved) resolve(mc);
    if (handle == 0) return;

    pushIds();
    if (mc.player != null) {
      NativeLib.terrainCamera(handle, mc.player.getBlockX(), mc.player.getBlockZ());
      HeightField.update(handle, mc.player.getBlockX(), mc.player.getBlockZ());
    }

    keys.clear();
    int n = NativeLib.terrainPoll(handle, keys);
    for (int i = 0; i < n; i++) {
      long key = keys.getLong(i * 8);
      if (key < 0) {
        // A tile of the far heightfield, not of a voxel level.
        HeightField.finished(handle, key & Long.MAX_VALUE);
        continue;
      }
      int level = SectionKey.level(key);
      int sx = SectionKey.x(key);
      int sz = SectionKey.z(key);
      int side = NativeLib.terrainFarTint(handle, level, sx, sz, tint);
      if (side > 0) {
        byte[] texels = new byte[side * side * 4];
        tint.get(0, texels);
        TintMap.queueFar(level, sx * side, sz * side, side, texels);
        byte[] foliage = new byte[side * side * 4];
        tint.get(256, foliage);
        TintMap.queueFarFoliage(level, sx * side, sz * side, side, foliage);
        byte[] water = new byte[side * side * 4];
        tint.get(512, water);
        TintMap.queueFarWater(level, sx * side, sz * side, side, water);
      }
      int min = VoxelWorld.levelMin(level);
      for (int sy = min; sy < min + VoxelWorld.levelRows(level); sy++) VoxelWorld.invalidate(level, sx, sy, sz);
    }
  }

  /** Sends the voxel ids the native side writes into bricks, whenever the classifier changed them. */
  private void pushIds() {
    int[] now = new int[SURFACES.length * 2 + 2];
    for (int i = 0; i < SURFACES.length; i++) {
      now[i * 2] = BlockClassifier.voxelId(SURFACES[i][0].defaultBlockState());
      now[i * 2 + 1] = BlockClassifier.voxelId(SURFACES[i][1].defaultBlockState());
    }
    now[SURFACES.length * 2] = BlockClassifier.voxelId(Blocks.STONE.defaultBlockState());
    now[SURFACES.length * 2 + 1] = BlockClassifier.waterId(8);

    if (!Arrays.equals(now, ids)) {
      ids = now;
      NativeLib.terrainSetIds(handle, now);
    }

    int[] trees = new int[treeBlocks.length];
    for (int i = 0; i < trees.length; i++) {
      BlockState state = treeBlocks[i];
      trees[i] = state == null ? 0 : BlockClassifier.voxelId(state);
    }
    if (!Arrays.equals(trees, treeIds)) {
      treeIds = trees;
      NativeLib.terrainSetTreeIds(handle, trees);
    }
  }

  /**
   * The blocks tree and plant features place, by the native side's numbering: a block name,
   * optionally followed by properties ({@code minecraft:tall_grass[half=upper]}). Those that are not
   * worth a voxel (water the trees stand in, vines and cocoa pods, which are too thin to see from
   * afar) are null.
   */
  private static BlockState[] resolveTreeBlocks(String names) {
    if (names.isEmpty()) return new BlockState[0];
    String[] lines = names.split("\n");
    BlockState[] out = new BlockState[lines.length];
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      int bracket = line.indexOf('[');
      Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(bracket < 0 ? line : line.substring(0, bracket)));
      if (block == Blocks.AIR || block == Blocks.WATER || block == Blocks.VINE || block == Blocks.COCOA || block == Blocks.BEE_NEST) continue;

      BlockState state = block.defaultBlockState();
      if (bracket >= 0) {
        for (String property : line.substring(bracket + 1, line.length() - 1).split(",")) {
          int eq = property.indexOf('=');
          Property<?> p = block.getStateDefinition().getProperty(property.substring(0, eq));
          if (p != null) state = withValue(state, p, property.substring(eq + 1));
        }
      }
      out[i] = state;
    }
    return out;
  }

  private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
    return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
  }

  private void resolve(Minecraft mc) {
    ClientLevel client = mc.level;
    if (client == null || client.dimension() != Level.OVERWORLD) {
      resolved = true;
      return;
    }

    // Singleplayer reads the generator directly. Otherwise the server describes it to us when we
    // join, a moment after the level exists; keep asking until then (forever, if it lacks this mod).
    IntegratedServer server = mc.getSingleplayerServer();
    WorldDescriptor world = server != null ? WorldDescriptor.of(server) : ClientWorldDescriptor.received();
    if (world == null && server == null) return;

    resolved = true;
    // The sections of an enumerated source are only asked for during a sweep.
    VoxelWorld.requestSweep();
    if (world == null) return;

    try {
      NativeLib.load();
      handle = NativeLib.terrainCreate(world.seed(), world.config(), Lod.outer(0) + WANT_MARGIN, Integer.getInteger("look.seedThreads", 0));
      treeBlocks = resolveTreeBlocks(NativeLib.terrainTreeBlocks(handle));
      LookMod.LOGGER.info("Seed terrain engine started ({} bytes of config, {} tree blocks)", world.config().length, treeBlocks.length);
    } catch (RuntimeException | LinkageError e) {
      LookMod.LOGGER.warn("Predicted terrain is unavailable for this world: {}", e.toString());
      handle = 0;
    }
  }

  @Override
  public int fill(int level, int sx, int sy, int sz, ByteBuffer out) {
    if (handle == 0) return UNAVAILABLE;

    // Always writes the whole brick, since a layered source may overlay loaded chunks on it.
    int solid = NativeLib.terrainFetch(handle, level, sx, sy, sz, out);
    if (solid >= 0) return solid;

    NativeLib.terrainRequest(handle, level, sx, sz);
    return PENDING;
  }
}
