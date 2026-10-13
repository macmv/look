package dev.macmv.look;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

/**
 * Biome tint colors (grass and foliage) as a wrapping 2D texture array with one RGBA8 texel per
 * {@link #CELL} x {@link #CELL} block columns. Layers:
 * <ul>
 *   <li>0, 1: grass and foliage color at the surface of the column;
 *   <li>2, 3: the same for the biome underneath, when the column has a second one (caves);
 *   <li>4: R is where the lower biome ends, see {@link #SPLIT_UNIT}. Read with texelFetch.
 *   <li>{@link #WATER_LAYER}: water color at the surface of the column.
 *   <li>{@link #FAR_FIRST} + level: grass color of predicted terrain (see {@link SeedSource}), for
 *       columns without a loaded chunk. Texels are {@link #farCell} blocks wide and alpha says
 *       whether one is filled.
 *   <li>{@link #FAR_FOLIAGE_FIRST} + level: the same for foliage, which predicted trees use.
 *   <li>{@link #FAR_WATER_FIRST} + level: the same for water.
 * </ul>
 * Columns with one biome have the lower layers equal to the upper ones. The shader samples the
 * color layers with bilinear filtering.
 *
 * <p>Colors come from {@link Biome#getGrassColor} / {@link Biome#getFoliageColor}, so colormaps,
 * overrides, swamp and dark forest modifiers and datapack biomes match vanilla. Each texel is a box
 * average over the player's "Biome Blend" radius, like {@code ClientLevel.calculateBlockTint}, taken
 * across chunk borders. A chunk therefore depends on its 8 neighbors: when one arrives, the loaded
 * ones around it are refilled (a few per frame).
 *
 * <p>Chunks fill their texels the first time a source reads them ({@link #ensureChunk}). Texels stay
 * after a chunk unloads, so distant terrain that outlives its chunk keeps its color. Everything runs
 * on the render thread.
 */
public final class TintMap {
  /** Texels per side. Covers {@code SIZE * CELL} blocks before wrapping; keep in sync with the shader. */
  public static final int SIZE = 512;
  /** Blocks per texel side. */
  public static final int CELL = 4;
  /** The split layer stores the lower biome's top as this many blocks above the level floor. */
  public static final int SPLIT_UNIT = 2;
  /** Layers filled from loaded chunks. */
  private static final int NEAR_LAYERS = 6;
  public static final int WATER_LAYER = 5;
  public static final int FAR_FIRST = NEAR_LAYERS;
  /** {@code FAR_FIRST + Lod.LEVELS + level}: foliage color of predicted terrain, laid out like the grass. */
  public static final int FAR_FOLIAGE_FIRST = NEAR_LAYERS + Lod.LEVELS;
  /** {@code FAR_FIRST + 2 * Lod.LEVELS + level}: water color of predicted terrain. */
  public static final int FAR_WATER_FIRST = NEAR_LAYERS + 2 * Lod.LEVELS;
  public static final int LAYERS = NEAR_LAYERS + 3 * Lod.LEVELS;
  private static final int CHUNK_TEXELS = 16 / CELL;
  /** Neighbor refills per {@link #flush}. */
  private static final int REFILLS_PER_FLUSH = 6;
  /** Vanilla's water color for a column with no biome to ask. */
  private static final int DEFAULT_WATER = 0x3F76E4;

  private static final Set<Long> filled = new HashSet<>();
  private static final Set<Long> stale = new LinkedHashSet<>();
  private static final List<int[]> pendingPos = new ArrayList<>();
  private static final List<byte[]> pendingData = new ArrayList<>();
  /** Far uploads: (layer, texel x, texel z, texels per side) and the RGBA texels. */
  private static final List<int[]> pendingFarPos = new ArrayList<>();
  private static final List<byte[]> pendingFarData = new ArrayList<>();

  private static ClientLevel lastLevel;
  private static int radius = -1;
  private static int tex;
  private static boolean needsClear = true;
  private static ByteBuffer scratch;

  private TintMap() {}

  /** Forgets everything; the texture is reset to the default colors on the next {@link #flush}. */
  public static void reset() {
    filled.clear();
    stale.clear();
    pendingPos.clear();
    pendingData.clear();
    pendingFarPos.clear();
    pendingFarData.clear();
    lastLevel = null;
    needsClear = true;
  }

  /** Blocks per texel side of the far layer of {@code level}; keep in sync with the shader. */
  public static int farCell(int level) {
    return Math.max(8, 2 << level);
  }

  /**
   * Queues grass colors of predicted terrain: {@code side} x {@code side} RGBA texels with their
   * corner at texel ({@code x}, {@code z}) of the far layer of {@code level} (wrapping).
   */
  public static void queueFar(int level, int x, int z, int side, byte[] rgba) {
    pendingFarPos.add(new int[] {FAR_FIRST + level, x & (SIZE - 1), z & (SIZE - 1), side});
    pendingFarData.add(rgba);
  }

  /** Like {@link #queueFar}, for the foliage colors. */
  public static void queueFarFoliage(int level, int x, int z, int side, byte[] rgba) {
    pendingFarPos.add(new int[] {FAR_FOLIAGE_FIRST + level, x & (SIZE - 1), z & (SIZE - 1), side});
    pendingFarData.add(rgba);
  }

  /** Like {@link #queueFar}, for the water colors. */
  public static void queueFarWater(int level, int x, int z, int side, byte[] rgba) {
    pendingFarPos.add(new int[] {FAR_WATER_FIRST + level, x & (SIZE - 1), z & (SIZE - 1), side});
    pendingFarData.add(rgba);
  }

  /** True once when the biome blend option changed, after which everything must be rebuilt. */
  public static boolean takeRadiusChanged() {
    int now = Minecraft.getInstance().options.biomeBlendRadius().get();
    boolean changed = radius != -1 && now != radius;
    radius = now;
    return changed;
  }

  /** The chunk's biomes may have changed (new packet) or it is gone: refill it on next use. */
  public static void invalidate(int cx, int cz) {
    long key = ChunkPos.asLong(cx, cz);
    filled.remove(key);
    stale.remove(key);
  }

  /** Queues this chunk's texels if it has not been done since the last {@link #invalidate}. */
  public static void ensureChunk(ClientLevel level, LevelChunk chunk) {
    ChunkPos pos = chunk.getPos();
    if (!filled.add(pos.toLong())) return;
    lastLevel = level;
    stale.remove(pos.toLong());
    fill(level, chunk);

    // The neighbors' blur reached into this chunk, which they could not see until now.
    for (int dz = -1; dz <= 1; dz++) {
      for (int dx = -1; dx <= 1; dx++) {
        long key = ChunkPos.asLong(pos.x + dx, pos.z + dz);
        if ((dx != 0 || dz != 0) && filled.contains(key)) stale.add(key);
      }
    }
  }

  private static void fill(ClientLevel level, LevelChunk chunk) {
    ChunkPos pos = chunk.getPos();
    int r = Math.max(0, radius < 0 ? Minecraft.getInstance().options.biomeBlendRadius().get() : radius);
    int minY = level.getMinBuildHeight();
    int maxY = level.getMaxBuildHeight() - 1;

    LevelChunk[] near = new LevelChunk[9];
    for (int dz = -1; dz <= 1; dz++) {
      for (int dx = -1; dx <= 1; dx++) {
        near[(dz + 1) * 3 + dx + 1] =
            dx == 0 && dz == 0 ? chunk : level.getChunkSource().getChunk(pos.x + dx, pos.z + dz, ChunkStatus.FULL, false);
      }
    }

    byte[] data = new byte[NEAR_LAYERS * CHUNK_TEXELS * CHUNK_TEXELS * 4];
    int[] colors = new int[3];

    for (int tz = 0; tz < CHUNK_TEXELS; tz++) {
      for (int tx = 0; tx < CHUNK_TEXELS; tx++) {
        // The texel center, so bilinear sampling reproduces the biome at block positions.
        int x = pos.getMinBlockX() + tx * CELL + CELL / 2;
        int z = pos.getMinBlockZ() + tz * CELL + CELL / 2;
        int y = Math.max(minY, Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) - 1));
        int texel = tz * CHUNK_TEXELS + tx;

        blend(near, pos.x, pos.z, x, y, z, r, colors);
        put(data, 0, texel, colors[0]);
        put(data, 1, texel, colors[1]);
        put(data, WATER_LAYER, texel, colors[2]);

        // Look for a second biome below the surface; there is only ever one more.
        Biome top = chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2).value();
        int lowY = -1;
        int split = 0;
        for (int qy = (y >> 2) - 1; qy >= minY >> 2; qy--) {
          if (chunk.getNoiseBiome(x >> 2, qy, z >> 2).value() != top) {
            lowY = qy * 4 + 2;
            split = qy * 4 + 4;
            break;
          }
        }

        if (lowY >= 0) blend(near, pos.x, pos.z, x, lowY, z, r, colors);
        put(data, 2, texel, colors[0]);
        put(data, 3, texel, colors[1]);
        data[(4 * CHUNK_TEXELS * CHUNK_TEXELS + texel) * 4] = (byte) Math.min(255, (split - minY) / SPLIT_UNIT);
      }
    }

    pendingPos.add(new int[] {pos.x * CHUNK_TEXELS & (SIZE - 1), pos.z * CHUNK_TEXELS & (SIZE - 1)});
    pendingData.add(data);
  }

  /**
   * Box average of the grass (out[0]), foliage (out[1]) and water (out[2]) colors of the columns within {@code r}
   * blocks of (x, z), all at height {@code y}, like vanilla. {@code near} is the 3x3 chunks around
   * (cx, cz); columns in unloaded chunks are skipped.
   */
  private static void blend(LevelChunk[] near, int cx, int cz, int x, int y, int z, int r, int[] out) {
    long[] sum = new long[9];
    int n = 0;
    for (int bz = z - r; bz <= z + r; bz++) {
      for (int bx = x - r; bx <= x + r; bx++) {
        LevelChunk c = near[((bz >> 4) - cz + 1) * 3 + (bx >> 4) - cx + 1];
        if (c == null) continue;
        Biome biome = c.getNoiseBiome(bx >> 2, y >> 2, bz >> 2).value();
        int g = biome.getGrassColor(bx, bz);
        int f = biome.getFoliageColor();
        int w = biome.getWaterColor();
        sum[0] += g >> 16 & 255;
        sum[1] += g >> 8 & 255;
        sum[2] += g & 255;
        sum[3] += f >> 16 & 255;
        sum[4] += f >> 8 & 255;
        sum[5] += f & 255;
        sum[6] += w >> 16 & 255;
        sum[7] += w >> 8 & 255;
        sum[8] += w & 255;
        n++;
      }
    }

    if (n == 0) {
      out[0] = GrassColor.getDefaultColor();
      out[1] = FoliageColor.getDefaultColor();
      out[2] = DEFAULT_WATER;
      return;
    }
    out[0] = (int) (sum[0] / n) << 16 | (int) (sum[1] / n) << 8 | (int) (sum[2] / n);
    out[1] = (int) (sum[3] / n) << 16 | (int) (sum[4] / n) << 8 | (int) (sum[5] / n);
    out[2] = (int) (sum[6] / n) << 16 | (int) (sum[7] / n) << 8 | (int) (sum[8] / n);
  }

  private static void put(byte[] data, int layer, int texel, int rgb) {
    int o = (layer * CHUNK_TEXELS * CHUNK_TEXELS + texel) * 4;
    data[o] = (byte) (rgb >> 16);
    data[o + 1] = (byte) (rgb >> 8);
    data[o + 2] = (byte) rgb;
    data[o + 3] = (byte) 0xFF;
  }

  public static int texture() {
    return tex;
  }

  /**
   * Creates the texture if needed and uploads what was queued. Leaves the texture bound on the
   * active unit, which is changed to {@code unit}; the caller restores it.
   */
  public static void flush(int unit) {
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
    if (tex == 0) {
      tex = GL33C.glGenTextures();
      GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_ARRAY, tex);
      GL33C.glTexImage3D(
          GL33C.GL_TEXTURE_2D_ARRAY, 0, GL33C.GL_RGBA8, SIZE, SIZE, LAYERS, 0,
          GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
      GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
      GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
      GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_REPEAT);
      GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_REPEAT);
      needsClear = true;
    } else {
      GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_ARRAY, tex);
    }

    if (needsClear) {
      clearToDefaults();
      needsClear = false;
    }
    refillStale();
    if (pendingPos.isEmpty() && pendingFarPos.isEmpty()) return;

    if (scratch == null) scratch = ByteBuffer.allocateDirect(CHUNK_TEXELS * CHUNK_TEXELS * NEAR_LAYERS * 4).order(ByteOrder.nativeOrder());
    GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 4);
    GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0);
    for (int i = 0; i < pendingPos.size(); i++) {
      int[] p = pendingPos.get(i);
      scratch.clear();
      scratch.put(pendingData.get(i)).flip();
      GL33C.glTexSubImage3D(
          GL33C.GL_TEXTURE_2D_ARRAY, 0, p[0], p[1], 0, CHUNK_TEXELS, CHUNK_TEXELS, NEAR_LAYERS,
          GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, scratch);
    }
    pendingPos.clear();
    pendingData.clear();

    for (int i = 0; i < pendingFarPos.size(); i++) {
      int[] p = pendingFarPos.get(i);
      scratch.clear();
      scratch.put(pendingFarData.get(i)).flip();
      GL33C.glTexSubImage3D(
          GL33C.GL_TEXTURE_2D_ARRAY, 0, p[1], p[2], p[0], p[3], p[3], 1,
          GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, scratch);
    }
    pendingFarPos.clear();
    pendingFarData.clear();
  }

  private static void refillStale() {
    if (lastLevel == null || stale.isEmpty()) return;
    Iterator<Long> it = stale.iterator();
    for (int i = 0; i < REFILLS_PER_FLUSH && it.hasNext(); i++) {
      long key = it.next();
      it.remove();
      LevelChunk chunk = lastLevel.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
      if (chunk == null) filled.remove(key);
      else fill(lastLevel, chunk);
    }
  }

  /**
   * Unfilled texels show the same colors vanilla uses for a null level. Alpha of the first layer is
   * 0 there and 255 once a chunk filled it, and the far layers are 0 until predicted terrain does.
   */
  private static void clearToDefaults() {
    int[] colors = new int[LAYERS];
    Arrays.fill(colors, GrassColor.getDefaultColor());
    colors[1] = colors[3] = FoliageColor.getDefaultColor();
    colors[4] = 0;
    colors[WATER_LAYER] = DEFAULT_WATER;
    ByteBuffer buf = MemoryUtil.memAlloc(SIZE * SIZE * 4);
    try {
      GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 4);
      GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0);
      for (int layer = 0; layer < LAYERS; layer++) {
        int c = colors[layer];
        buf.clear();
        for (int i = 0; i < SIZE * SIZE; i++) {
          buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (layer == 0 || layer == 4 || layer >= FAR_FIRST ? 0 : 0xFF));
        }
        buf.flip();
        GL33C.glTexSubImage3D(
            GL33C.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, SIZE, SIZE, 1, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, buf);
      }
    } finally {
      MemoryUtil.memFree(buf);
    }
  }
}
