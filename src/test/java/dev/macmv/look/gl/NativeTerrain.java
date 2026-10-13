package dev.macmv.look.gl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParser;
import dev.macmv.look.Lod;
import dev.macmv.look.NativeLib;
import dev.macmv.look.SectionKey;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The native terrain engine on vanilla's overworld, without a game: the config is the fixture the
 * Rust tests check against ({@code native/tests/fixtures/overworld.json}, exactly what
 * {@code TerrainExport} writes for a vanilla world, with its seed). Tiles are requested and waited
 * for here, where the game spreads that over frames, so the result does not depend on timing.
 */
final class NativeTerrain implements TestScene.BrickSource, TestScene.HeightFieldSource, AutoCloseable {
  private static final Path FIXTURE =
      Path.of(System.getProperty("look.terrainFixture", "native/tests/fixtures/overworld.json"));

  private static final Path TREE_FIXTURE =
      Path.of(System.getProperty("look.treeFixture", "native/tests/fixtures/trees.json"));

  private static final int PLAINS_GRASS = 0x91BD59;

  private final long handle;
  private final ByteBuffer keys = NativeLib.allocate(4096 * 8);
  /** Tiles asked for and not seen finished yet, by key (with the heightfield flag). */
  private final Set<Long> waiting = new HashSet<>();

  private final double camX;
  private final double camZ;
  private final double forwardX;
  private final double forwardZ;
  private final double halfAngle;

  /**
   * @param yaw the view direction in vanilla's convention (0 looks along +z, 90 along -x), and
   *     {@code halfAngle} how far from it (degrees) a far tile may be and still be generated:
   *     tiles outside the view cannot show up, and there are tens of thousands of them
   */
  NativeTerrain(double camX, double camZ, float yaw, float halfAngle) {
    this(camX, camZ, yaw, halfAngle, false);
  }

  /** @param trees whether to grow trees: the fixture world's tree features (see {@code TreeExport}) */
  NativeTerrain(double camX, double camZ, float yaw, float halfAngle, boolean trees) {
    NativeLib.load();
    this.camX = camX;
    this.camZ = camZ;
    this.forwardX = -Math.sin(Math.toRadians(yaw));
    this.forwardZ = Math.cos(Math.toRadians(yaw));
    this.halfAngle = Math.toRadians(halfAngle);

    JsonObject fixture;
    try (Reader in = Files.newBufferedReader(FIXTURE)) {
      fixture = JsonParser.parseReader(in).getAsJsonObject();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + FIXTURE.toAbsolutePath(), e);
    }
    long seed = fixture.get("seed").getAsLong();
    JsonObject cfg = fixture.getAsJsonObject("config");

    // The fixture was written without the game's colormaps, so many biomes have a grass color of 0,
    // which would draw all of their grass black. The real export asks the live biome; stand in
    // vanilla's plains color for those.
    JsonArray grass = cfg.getAsJsonObject("biomes").getAsJsonArray("grass_colors");
    for (int i = 0; i < grass.size(); i++) {
      if (grass.get(i).getAsInt() == 0) grass.set(i, new JsonPrimitive(PLAINS_GRASS));
    }
    if (trees) {
      try (Reader in = Files.newBufferedReader(TREE_FIXTURE)) {
        JsonObject treeFixture = JsonParser.parseReader(in).getAsJsonObject();
        if (treeFixture.get("seed").getAsLong() != seed) throw new IllegalStateException("tree fixture is for another seed");
        cfg.add("decoration", treeFixture.getAsJsonObject("decoration"));
      } catch (IOException e) {
        throw new IllegalStateException("Cannot read " + TREE_FIXTURE.toAbsolutePath(), e);
      }
    }
    byte[] config = cfg.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

    handle = NativeLib.terrainCreate(seed, config, Lod.outer(0) + 6, Integer.getInteger("look.seedThreads", 0));
    NativeLib.terrainSetIds(handle, TestScene.HF_IDS);
    if (trees) {
      // The test atlas has oak logs and oak leaves.
      String[] names = NativeLib.terrainTreeBlocks(handle).split("\n");
      int[] ids = new int[names.length];
      for (int i = 0; i < ids.length; i++) {
        String n = names[i];
        ids[i] = n.endsWith("_log") || n.endsWith("_wood") ? TestScene.LOG : n.endsWith("_leaves") ? TestScene.LEAVES : 0;
      }
      NativeLib.terrainSetTreeIds(handle, ids);
    }
    NativeLib.terrainCamera(handle, (int) Math.floor(camX), (int) Math.floor(camZ));
  }

  // --- BrickSource -------------------------------------------------------------------------------

  @Override
  public float reachBlocks() {
    return Float.POSITIVE_INFINITY;
  }

  @Override
  public void prepare(List<int[]> columns) {
    for (int[] c : columns) request(c[0], c[1], c[2]);
    await();
  }

  @Override
  public int fill(int level, int sx, int sy, int sz, ByteBuffer out) {
    return NativeLib.terrainFetch(handle, level, sx, sy, sz, out);
  }

  // --- HeightFieldSource -------------------------------------------------------------------------

  @Override
  public boolean wants(int engineLevel, int sx, int sz) {
    double tile = 16.0 * (1 << engineLevel);
    double dx = (sx + 0.5) * tile - camX;
    double dz = (sz + 0.5) * tile - camZ;
    double dist = Math.hypot(dx, dz);
    if (dist < tile * 2) return true;
    double cos = (dx * forwardX + dz * forwardZ) / dist;
    // A tile is wider than a point: widen the cone by the angle it covers.
    double slack = Math.atan2(tile * 0.75, dist);
    return Math.acos(Math.max(-1, Math.min(1, cos))) <= halfAngle + slack;
  }

  @Override
  public void prepare(int engineLevel, List<int[]> tiles) {
    for (int[] t : tiles) requestHeightfield(engineLevel, t[0], t[1]);
    await();
  }

  @Override
  public boolean take(int engineLevel, int sx, int sz, ByteBuffer out) {
    return NativeLib.terrainHeightfieldTake(handle, engineLevel, sx, sz, out);
  }

  /** Asks for a tile of a voxel level (a column of sections). */
  void request(int level, int sx, int sz) {
    if (NativeLib.terrainRequest(handle, level, sx, sz)) waiting.add(key(level, sx, sz, false));
  }

  /** Asks for a tile of the far heightfield, at engine level {@code level}. */
  void requestHeightfield(int level, int sx, int sz) {
    if (NativeLib.terrainHeightfieldRequest(handle, level, sx, sz)) waiting.add(key(level, sx, sz, true));
  }

  /** Blocks until everything requested so far is finished. */
  void await() {
    long deadline = System.nanoTime() + 10 * 60_000_000_000L;
    while (!waiting.isEmpty()) {
      keys.clear();
      int n = NativeLib.terrainPoll(handle, keys);
      for (int i = 0; i < n; i++) waiting.remove(keys.getLong(i * 8));
      if (n == 0) {
        if (System.nanoTime() > deadline) throw new IllegalStateException(waiting.size() + " tiles never finished");
        try {
          Thread.sleep(2);
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
      }
    }
  }

  /** What the engine reports finished: a SectionKey with y = 0, and the heightfield flag in the top bit. */
  private static long key(int level, int sx, int sz, boolean hf) {
    long k = SectionKey.pack(level, sx, 0, sz);
    return hf ? k | Long.MIN_VALUE : k;
  }

  @Override
  public void close() {
    NativeLib.terrainDestroy(handle);
  }
}
