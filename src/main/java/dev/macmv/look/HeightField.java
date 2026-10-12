package dev.macmv.look;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.opengl.GL33C;

/**
 * Far terrain as a heightfield, for the pyramid levels past the voxel ones ({@link Lod#LEVELS}).
 *
 * <p>Out there terrain is only a surface, so a column of 2D texels says all there is to say, and
 * the shader marches rays over them (see {@code traceHeightfield} in {@code voxel.fsh}). That is
 * much cheaper than bricks, so the texels can be {@code 2^FINER_SHIFT} times finer than a voxel of
 * the same pyramid level: level k covers the same distances as before, in texels of
 * {@code 2^(k - FINER_SHIFT)} blocks.
 *
 * <p>Each level has a wrapping window of {@link #WINDOW_TILES} x {@link #WINDOW_TILES} tiles of
 * 16 x 16 texels around the camera, in three array textures (one layer per level): the height of
 * every texel, its kind and biome grass color, and the highest texel of each tile, which lets rays
 * skip tiles they pass over. Tiles come from the native engine, the same ones the voxel levels use
 * (a tile of engine level {@code k - FINER_SHIFT} holds exactly the texels of level k), asked for
 * with a flag that makes the engine forget them once taken.
 *
 * <p>A cell of the window holds whichever tile was uploaded to it last. When the camera moves, the
 * cell's tile changes and the old one is stale, so its maximum is set to {@link #NONE} (the shader
 * skips it) until the new one arrives.
 */
public final class HeightField {
  public static final int TILE = 16;
  public static final int WINDOW_TILES = 128;
  public static final int SIZE = WINDOW_TILES * TILE;
  public static final int LEVELS = Lod.HF_LEVELS;
  /** A texel of pyramid level k is {@code 2^(k - FINER_SHIFT)} blocks wide. */
  public static final int FINER_SHIFT = 2;

  public static final int UNIT_HEIGHT = 18;
  public static final int UNIT_ATTR = 19;
  public static final int UNIT_TILES = 20;

  /** Height of a texel, or the maximum of a tile, that has no terrain (and is skipped). */
  public static final short NONE = Short.MIN_VALUE;

  /** Cells of a level's window looked at per frame. A whole level takes a dozen frames. */
  private static final int SCAN_PER_LEVEL = 1280;
  private static final int REQUESTS_PER_FRAME = 512;
  /** Frames after which a tile that never arrived (the engine dropped it) is asked for again. */
  private static final int RETRY_FRAMES = 1800;
  /** If the camera moves this many tiles at once, everything in the window is stale. */
  private static final int JUMP_TILES = 24;
  private static final long NO_KEY = Long.MIN_VALUE;

  static final class Level {
    /** Per window cell: the key of the tile held there, or {@link #NO_KEY}. */
    final long[] resident = new long[WINDOW_TILES * WINDOW_TILES];
    final long[] requested = new long[WINDOW_TILES * WINDOW_TILES];
    final int[] requestedAt = new int[WINDOW_TILES * WINDOW_TILES];
    final TileCell[] state = new TileCell[WINDOW_TILES * WINDOW_TILES];
    /** The maximum the GPU holds for each cell once the queued uploads and hides have been flushed. */
    final short[] gpuMax = new short[WINDOW_TILES * WINDOW_TILES];
    int cursor;
    int camTileX = Integer.MIN_VALUE;
    int camTileZ;
    /** The GPU still shows tiles of before a jump or a world change: reset its maxima to NONE. */
    boolean clearGpu = true;
    /** Illegal {@link TileCell} transitions seen, and the first one. */
    int violations;
    String firstViolation;

    Level() {
      reset();
    }

    void reset() {
      Arrays.fill(resident, NO_KEY);
      Arrays.fill(requested, NO_KEY);
      Arrays.fill(state, TileCell.EMPTY);
      Arrays.fill(gpuMax, NONE);
      cursor = 0;
      camTileX = Integer.MIN_VALUE;
      clearGpu = true;
    }

    /** The only way a cell changes state. An illegal edge is counted, not fatal: it is a glitch, not a crash. */
    void move(int cell, TileCell to) {
      TileCell from = state[cell];
      if (!from.canGo(to) && violations++ == 0) firstViolation = "cell " + cell + ": " + from + " -> " + to;
      state[cell] = to;
    }

    /**
     * What is wrong with the cells, empty if all is well. Each state implies what the cell holds, and
     * after the queued GPU writes land the GPU shows a cell exactly when it is SHOWN.
     */
    List<String> checkInvariants(List<Upload> pendingUploads, List<int[]> pendingHides, int level) {
      List<String> bad = new ArrayList<>();
      if (violations > 0) bad.add(violations + " illegal transitions, first " + firstViolation);

      short[] gpu = gpuMax.clone();
      if (clearGpu) Arrays.fill(gpu, NONE);
      // Mirror flush(): hides first, then uploads, in queue order.
      for (int[] h : pendingHides) if (h[0] == level) gpu[h[1]] = NONE;
      for (Upload u : pendingUploads) if (u.level == level) gpu[u.cellZ * WINDOW_TILES + u.cellX] = u.max;

      int held = 0;
      for (int cell = 0; cell < state.length && bad.size() < 8; cell++) {
        TileCell s = state[cell];
        if (s.holdsTile() != (resident[cell] != NO_KEY) && s != TileCell.REQUESTED) {
          bad.add("cell " + cell + " is " + s + " but resident key is " + resident[cell]);
        }
        if (s == TileCell.EMPTY && requested[cell] != NO_KEY) bad.add("cell " + cell + " is EMPTY but requested " + requested[cell]);
        if (s == TileCell.REQUESTED && requested[cell] == NO_KEY) bad.add("cell " + cell + " is REQUESTED but asked for nothing");
        if (resident[cell] != NO_KEY) held++;
        boolean visible = gpu[cell] != NONE;
        if (visible != (s == TileCell.SHOWN)) bad.add("cell " + cell + " is " + s + " but the GPU maximum is " + gpu[cell]);
      }
      if (held != residentCount[level]) bad.add("holds " + held + " tiles but counted " + residentCount[level]);
      return bad;
    }
  }

  record Upload(int level, int cellX, int cellZ, byte[] data, short max) {}

  private static final Level[] levels = new Level[LEVELS];
  private static final List<Upload> uploads = new ArrayList<>();
  /** Window cells whose maximum must be reset to NONE: {level, cell}. */
  private static final List<int[]> hides = new ArrayList<>();
  private static final long[] residentCount = new long[LEVELS];

  private static ByteBuffer take;
  private static ByteBuffer heights;
  private static ByteBuffer attrs;
  private static ByteBuffer one;
  private static ByteBuffer none;
  private static int heightTex;
  private static int attrTex;
  private static int tilesTex;
  private static int frame;

  static {
    for (int i = 0; i < LEVELS; i++) levels[i] = new Level();
  }

  private HeightField() {}

  /** Pyramid level of heightfield level {@code i}. */
  public static int pyramidLevel(int i) {
    return Lod.LEVELS + i;
  }

  /** Engine tile level (texel size in blocks is {@code 1 << engineLevel}) of heightfield level {@code i}. */
  private static int engineLevel(int i) {
    return pyramidLevel(i) - FINER_SHIFT;
  }

  /** Forgets everything, for a new world. The GPU data is invalidated on the next flush. */
  public static void reset() {
    for (Level l : levels) l.reset();
    uploads.clear();
    hides.clear();
    Arrays.fill(residentCount, 0);
  }

  /** Whether any tile was ever uploaded (the textures exist only then). */
  public static boolean active() {
    return heightTex != 0;
  }

  /** Checks every level's cells against their states and the GPU writes queued; empty if all is well. */
  public static List<String> checkInvariants() {
    List<String> bad = new ArrayList<>();
    for (int i = 0; i < LEVELS; i++) {
      for (String line : levels[i].checkInvariants(uploads, hides, i)) bad.add("far level " + i + ": " + line);
    }
    return bad;
  }

  /** A line for the debug HUD: tiles held per level. */
  public static String statsLine() {
    return "far tiles: " + Arrays.toString(residentCount);
  }

  // --- Residency -------------------------------------------------------------------------------

  /**
   * Asks the engine for the tiles the camera needs and drops the ones it moved away from. Call once
   * per frame with the camera position in blocks.
   */
  public static void update(long handle, int camX, int camZ) {
    frame++;
    int requests = 0;

    for (int i = 0; i < LEVELS; i++) {
      final int level = i;
      Level l = levels[i];
      int j = engineLevel(i);
      int tileBlocks = TILE << j;
      int cx = Math.floorDiv(camX, tileBlocks);
      int cz = Math.floorDiv(camZ, tileBlocks);

      if (l.camTileX == Integer.MIN_VALUE
          || Math.abs(cx - l.camTileX) > JUMP_TILES
          || Math.abs(cz - l.camTileZ) > JUMP_TILES) {
        // Teleported (or just started): none of the window is where it was.
        l.reset();
        uploads.removeIf(u -> u.level == level);
        hides.removeIf(h -> h[0] == level);
        residentCount[i] = 0;
      }
      l.camTileX = cx;
      l.camTileZ = cz;

      int inner = (int) (Lod.BASE_BLOCKS * (1 << (pyramidLevel(i) - 1)) / tileBlocks) - 2;
      int baseX = cx - WINDOW_TILES / 2;
      int baseZ = cz - WINDOW_TILES / 2;

      for (int n = 0; n < SCAN_PER_LEVEL; n++) {
        int cell = l.cursor;
        l.cursor = (l.cursor + 1) % (WINDOW_TILES * WINDOW_TILES);
        int ix = cell % WINDOW_TILES;
        int iz = cell / WINDOW_TILES;
        // The one tile in the window that lands in this cell.
        int sx = baseX + Math.floorMod(ix - baseX, WINDOW_TILES);
        int sz = baseZ + Math.floorMod(iz - baseZ, WINDOW_TILES);

        int d = Math.max(Math.abs(sx - cx), Math.abs(sz - cz));
        if (d < inner) continue;

        long key = pack(sx, sz);
        if (l.resident[cell] == key) continue;

        // What the cell holds is another tile's: keep rays out of it until the right one arrives.
        if (l.state[cell] == TileCell.SHOWN) {
          // An upload of the old tile may still be queued; it would show the cell after the hide.
          final int hidden = cell;
          uploads.removeIf(u -> u.level == level && u.cellZ * WINDOW_TILES + u.cellX == hidden);
          hides.add(new int[] {i, cell});
        }
        if (l.state[cell] == TileCell.SHOWN || l.state[cell] == TileCell.BLANK) l.move(cell, TileCell.STALE);

        if (l.requested[cell] == key && frame - l.requestedAt[cell] < RETRY_FRAMES) continue;
        if (requests >= REQUESTS_PER_FRAME) {
          // Come back to this cell next frame.
          l.cursor = cell;
          break;
        }
        NativeLib.terrainHeightfieldRequest(handle, j, sx, sz);
        l.move(cell, TileCell.REQUESTED);
        l.requested[cell] = key;
        l.requestedAt[cell] = frame;
        requests++;
      }
    }
  }

  /** A heightfield tile finished: fetch it from the engine and queue its upload. {@code key} has the flag bit cleared. */
  public static void finished(long handle, long key) {
    int j = SectionKey.level(key);
    int i = j + FINER_SHIFT - Lod.LEVELS;
    if (i < 0 || i >= LEVELS) return;
    int sx = SectionKey.x(key);
    int sz = SectionKey.z(key);

    if (take == null) take = NativeLib.allocate(TILE * TILE * 6);
    take.clear();
    if (!NativeLib.terrainHeightfieldTake(handle, j, sx, sz, take)) return;

    Level l = levels[i];
    int cell = Math.floorMod(sz, WINDOW_TILES) * WINDOW_TILES + Math.floorMod(sx, WINDOW_TILES);
    // The camera moved on and this cell wants another tile (or everything was reset): drop it.
    if (l.requested[cell] != pack(sx, sz)) return;

    short max = NONE;
    for (int t = 0; t < TILE * TILE; t++) {
      short h = take.getShort(t * 2);
      if (h > max) max = h;
    }
    byte[] data = new byte[TILE * TILE * 6];
    take.get(0, data);
    uploads.add(new Upload(i, Math.floorMod(sx, WINDOW_TILES), Math.floorMod(sz, WINDOW_TILES), data, max));

    if (l.resident[cell] == NO_KEY) residentCount[i]++;
    l.resident[cell] = pack(sx, sz);
    l.move(cell, max != NONE ? TileCell.SHOWN : TileCell.BLANK);
  }

  private static long pack(int sx, int sz) {
    return ((long) sx << 32) | (sz & 0xFFFFFFFFL);
  }

  // --- GPU -------------------------------------------------------------------------------------

  /**
   * Creates the textures if needed and uploads what was queued. Leaves the textures bound on their
   * units, and the active unit changed; the caller restores it.
   */
  public static void flush() {
    if (heightTex == 0) {
      // Not worth 130 MB of textures until there is terrain to put in them.
      if (uploads.isEmpty()) return;
      create();
    }

    GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
    GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0);

    for (int i = 0; i < LEVELS; i++) {
      if (levels[i].clearGpu) {
        levels[i].clearGpu = false;
        Arrays.fill(levels[i].gpuMax, NONE);
        hideLayer(i);
      }
    }

    for (int[] h : hides) {
      writeTileMax(h[0], h[1] % WINDOW_TILES, h[1] / WINDOW_TILES, NONE);
      levels[h[0]].gpuMax[h[1]] = NONE;
    }
    hides.clear();

    for (Upload u : uploads) {
      heights.clear();
      attrs.clear();
      heights.put(0, u.data, 0, TILE * TILE * 2);
      attrs.put(0, u.data, TILE * TILE * 2, TILE * TILE * 4);
      heights.limit(TILE * TILE * 2);
      attrs.limit(TILE * TILE * 4);

      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_HEIGHT);
      GL33C.glTexSubImage3D(
          GL33C.GL_TEXTURE_2D_ARRAY, 0, u.cellX * TILE, u.cellZ * TILE, u.level, TILE, TILE, 1,
          GL33C.GL_RED_INTEGER, GL33C.GL_SHORT, heights);
      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_ATTR);
      GL33C.glTexSubImage3D(
          GL33C.GL_TEXTURE_2D_ARRAY, 0, u.cellX * TILE, u.cellZ * TILE, u.level, TILE, TILE, 1,
          GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, attrs);
      writeTileMax(u.level, u.cellX, u.cellZ, u.max);
      levels[u.level].gpuMax[u.cellZ * WINDOW_TILES + u.cellX] = u.max;
    }
    uploads.clear();
    GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 4);
  }

  private static void writeTileMax(int level, int cellX, int cellZ, short max) {
    one.clear();
    one.putShort(0, max);
    one.limit(2);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_TILES);
    GL33C.glTexSubImage3D(
        GL33C.GL_TEXTURE_2D_ARRAY, 0, cellX, cellZ, level, 1, 1, 1, GL33C.GL_RED_INTEGER, GL33C.GL_SHORT, one);
  }

  private static void create() {
    ByteOrder order = ByteOrder.nativeOrder();
    heights = ByteBuffer.allocateDirect(TILE * TILE * 2).order(order);
    attrs = ByteBuffer.allocateDirect(TILE * TILE * 4).order(order);
    one = ByteBuffer.allocateDirect(4).order(order);

    heightTex = makeArray(UNIT_HEIGHT, GL33C.GL_R16I, SIZE, LEVELS);
    attrTex = makeArray(UNIT_ATTR, GL33C.GL_RGBA8, SIZE, LEVELS);
    tilesTex = makeArray(UNIT_TILES, GL33C.GL_R16I, WINDOW_TILES, LEVELS);

    // Every tile starts out empty, so rays skip it.
    none = ByteBuffer.allocateDirect(WINDOW_TILES * WINDOW_TILES * 2).order(order);
    for (int i = 0; i < WINDOW_TILES * WINDOW_TILES; i++) none.putShort(i * 2, NONE);
    for (int i = 0; i < LEVELS; i++) levels[i].clearGpu = true;
  }

  /** Makes rays skip every tile of a level. */
  private static void hideLayer(int level) {
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_TILES);
    GL33C.glTexSubImage3D(
        GL33C.GL_TEXTURE_2D_ARRAY, 0, 0, 0, level, WINDOW_TILES, WINDOW_TILES, 1, GL33C.GL_RED_INTEGER, GL33C.GL_SHORT, none);
  }

  private static int makeArray(int unit, int format, int size, int layers) {
    int tex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_ARRAY, tex);
    int pixelFormat = format == GL33C.GL_RGBA8 ? GL33C.GL_RGBA : GL33C.GL_RED_INTEGER;
    int pixelType = format == GL33C.GL_RGBA8 ? GL33C.GL_UNSIGNED_BYTE : GL33C.GL_SHORT;
    GL33C.glTexImage3D(
        GL33C.GL_TEXTURE_2D_ARRAY, 0, format, size, size, layers, 0, pixelFormat, pixelType, (ByteBuffer) null);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_REPEAT);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_REPEAT);
    return tex;
  }

  /** Binds the textures to their fixed units. The caller restores the active unit. */
  public static void bind() {
    if (heightTex == 0) return;
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_HEIGHT);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_ARRAY, heightTex);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_ATTR);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_ARRAY, attrTex);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_TILES);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_ARRAY, tilesTex);
  }
}
