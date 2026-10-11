package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

/**
 * Mirrors the client level into GPU memory as sparse bricks.
 *
 * <p>Layout:
 * <ul>
 *   <li>An indirection 3D texture (R32UI), one texel per 16^3 section. Value 0 is empty, otherwise
 *       {@code brickSlot + 1}. X and Z wrap (toroidal), Y is offset by the level's min section.
 *   <li>A texture buffer (R16UI) holding all bricks: 4096 voxel ids per slot.
 *   <li>A texture buffer (R32UI) mapping voxel id to an RGB color.
 * </ul>
 *
 * <p>All methods run on the render thread (which is also the client main thread). Events only queue
 * work; GL is touched in {@link #update()}.
 */
public final class VoxelWorld {
  /** Sections per side of the (wrapping) indirection grid. Must be a power of two. */
  public static final int GRID_XZ = 128;

  public static final int UNIT_INDIRECTION = 10;
  public static final int UNIT_BRICKS = 11;
  public static final int UNIT_COLORS = 12;

  private static final int BRICK_VOXELS = 16 * 16 * 16;
  private static final int BRICK_BYTES = BRICK_VOXELS * 2;
  private static final long NO_OWNER = Long.MIN_VALUE;
  private static final long BUDGET_NANOS = 2_000_000L;

  private static final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
  private static final LinkedHashSet<Long> unloads = new LinkedHashSet<>();

  private static ClientLevel level;
  private static int minSection;
  private static int sectionCount;

  // CPU mirror of the indirection texture, plus which section owns each cell.
  private static int[] indirection;
  private static long[] owner;

  private static int[] freeSlots;
  private static int freeCount;
  private static int capacity;
  private static boolean warnedFull;

  private static boolean glReady;
  private static boolean indirectionStale;
  private static int indirectionTex;
  private static int brickBuf;
  private static int brickTex;
  private static int colorBuf;
  private static int colorTex;

  private static ByteBuffer brickScratch;
  private static IntBuffer colorScratch;
  private static IntBuffer cellScratch;

  private VoxelWorld() {}

  // --- Accessors for the draw pass -------------------------------------------------------------

  public static boolean isReady() {
    return level != null && glReady && !indirectionStale;
  }

  public static int minSection() {
    return minSection;
  }

  public static int sectionCount() {
    return sectionCount;
  }

  // --- Events (cheap, no GL) -------------------------------------------------------------------

  public static void markDirty(int sx, int sy, int sz) {
    if (!trackLevel()) return;
    if (sy < minSection || sy >= minSection + sectionCount) return;
    dirty.add(SectionPos.asLong(sx, sy, sz));
  }

  public static void unloadChunk(ChunkPos pos) {
    if (!trackLevel()) return;
    unloads.add(pos.toLong());
  }

  /** Switches to the current client level if it changed. Returns false when there is no level. */
  private static boolean trackLevel() {
    ClientLevel current = Minecraft.getInstance().level;
    if (current == null) return false;
    if (current != level) switchLevel(current);
    return true;
  }

  private static void switchLevel(ClientLevel l) {
    level = l;
    minSection = l.getMinSection();
    sectionCount = l.getSectionsCount();

    int cells = GRID_XZ * sectionCount * GRID_XZ;
    indirection = new int[cells];
    owner = new long[cells];
    Arrays.fill(owner, NO_OWNER);

    dirty.clear();
    unloads.clear();
    resetSlots();
    indirectionStale = true;
  }

  private static void resetSlots() {
    if (freeSlots == null) return;
    freeCount = 0;
    for (int i = capacity - 1; i >= 0; i--) freeSlots[freeCount++] = i;
    warnedFull = false;
  }

  // --- Per-frame update (GL) -------------------------------------------------------------------

  /** Uploads pending changes. Call once per frame before drawing. */
  public static void update() {
    if (!trackLevel()) return;

    int prevUnit = GlStateManager._getActiveTexture();
    try {
      if (!glReady) initGl();
      if (indirectionStale) createIndirectionTexture();

      // Everything below uploads to the indirection texture on this unit, and bricks via the
      // (unit independent) texture buffer binding point.
      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_INDIRECTION);
      GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, indirectionTex);

      for (Iterator<Long> it = unloads.iterator(); it.hasNext(); ) {
        long pos = it.next();
        it.remove();
        unloadChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos));
      }

      long start = System.nanoTime();
      for (Iterator<Long> it = dirty.iterator(); it.hasNext(); ) {
        long key = it.next();
        it.remove();
        rebuildSection(key);
        if (System.nanoTime() - start > BUDGET_NANOS) break;
      }

      if (BlockClassifier.takeColorsDirty()) uploadColors();
    } finally {
      GL33C.glActiveTexture(prevUnit);
    }
  }

  /** Binds the three data textures to their fixed units. The caller restores the active unit. */
  public static void bind() {
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_INDIRECTION);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, indirectionTex);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_BRICKS);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, brickTex);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_COLORS);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, colorTex);
  }

  // --- Section handling ------------------------------------------------------------------------

  private static int cellIndex(int sx, int sy, int sz) {
    return ((sy - minSection) * GRID_XZ + (sz & (GRID_XZ - 1))) * GRID_XZ + (sx & (GRID_XZ - 1));
  }

  private static void unloadChunkNow(int cx, int cz) {
    // The chunk may have been dropped and re-received before we got here; its dirty events will
    // rebuild it, so only clear when it is really gone.
    if (level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null) return;

    for (int sy = minSection; sy < minSection + sectionCount; sy++) {
      clearIfOwner(SectionPos.asLong(cx, sy, cz), cx, sy, cz);
    }
  }

  private static void rebuildSection(long key) {
    int sx = SectionPos.x(key);
    int sy = SectionPos.y(key);
    int sz = SectionPos.z(key);
    int cell = cellIndex(sx, sy, sz);

    LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
    if (chunk == null) {
      clearIfOwner(key, sx, sy, sz);
      return;
    }

    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
    if (section.hasOnlyAir() || convert(section) == 0) {
      clearIfOwner(key, sx, sy, sz);
      return;
    }

    int slot;
    if (owner[cell] == key && indirection[cell] != 0) {
      slot = indirection[cell] - 1;
    } else {
      // A different section may be aliasing this cell (wrapped grid); evict it first.
      if (owner[cell] != NO_OWNER) releaseCell(cell);

      if (freeCount == 0) {
        if (!warnedFull) {
          LookMod.LOGGER.warn("Out of brick slots ({}); some terrain will be missing", capacity);
          warnedFull = true;
        }
        return;
      }
      slot = freeSlots[--freeCount];
    }

    owner[cell] = key;
    indirection[cell] = slot + 1;

    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, brickBuf);
    brickScratch.clear();
    GL33C.glBufferSubData(GL33C.GL_TEXTURE_BUFFER, (long) slot * BRICK_BYTES, brickScratch);

    writeCell(sx, sy, sz, slot + 1);
  }

  /** Fills {@link #brickScratch} with voxel ids and returns how many are non-air. */
  private static int convert(LevelChunkSection section) {
    ByteBuffer b = brickScratch;
    int solid = 0;
    int i = 0;
    for (int y = 0; y < 16; y++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          int id = BlockClassifier.voxelId(section.getBlockState(x, y, z));
          if (id != 0) solid++;
          b.putShort(i << 1, (short) id);
          i++;
        }
      }
    }
    return solid;
  }

  private static void clearIfOwner(long key, int sx, int sy, int sz) {
    int cell = cellIndex(sx, sy, sz);
    if (owner[cell] != key) return;
    releaseCell(cell);
    writeCell(sx, sy, sz, 0);
  }

  /** Frees the slot and ownership of a cell on the CPU side only. */
  private static void releaseCell(int cell) {
    if (indirection[cell] != 0) freeSlots[freeCount++] = indirection[cell] - 1;
    indirection[cell] = 0;
    owner[cell] = NO_OWNER;
  }

  private static void writeCell(int sx, int sy, int sz, int value) {
    cellScratch.put(0, value);
    GL33C.glTexSubImage3D(
        GL33C.GL_TEXTURE_3D, 0,
        sx & (GRID_XZ - 1), sy - minSection, sz & (GRID_XZ - 1),
        1, 1, 1,
        GL33C.GL_RED_INTEGER, GL33C.GL_UNSIGNED_INT, cellScratch);
  }

  // --- GL resources ----------------------------------------------------------------------------

  private static void initGl() {
    int maxTexels = GL33C.glGetInteger(GL33C.GL_MAX_TEXTURE_BUFFER_SIZE);
    int wanted = Integer.getInteger("look.brickCapacity", 16384);
    capacity = Math.max(1, Math.min(wanted, maxTexels / BRICK_VOXELS));

    freeSlots = new int[capacity];
    resetSlots();

    brickScratch = ByteBuffer.allocateDirect(BRICK_BYTES).order(ByteOrder.nativeOrder());
    colorScratch =
        ByteBuffer.allocateDirect((BlockClassifier.MAX_ID + 1) * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    cellScratch = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();

    brickBuf = GL33C.glGenBuffers();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, brickBuf);
    GL33C.glBufferData(GL33C.GL_TEXTURE_BUFFER, (long) capacity * BRICK_BYTES, GL33C.GL_DYNAMIC_DRAW);
    brickTex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_BRICKS);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, brickTex);
    GL33C.glTexBuffer(GL33C.GL_TEXTURE_BUFFER, GL33C.GL_R16UI, brickBuf);

    colorBuf = GL33C.glGenBuffers();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, colorBuf);
    GL33C.glBufferData(GL33C.GL_TEXTURE_BUFFER, (long) (BlockClassifier.MAX_ID + 1) * 4, GL33C.GL_DYNAMIC_DRAW);
    colorTex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_COLORS);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, colorTex);
    GL33C.glTexBuffer(GL33C.GL_TEXTURE_BUFFER, GL33C.GL_R32UI, colorBuf);
    uploadColors();

    indirectionTex = GL33C.glGenTextures();

    glReady = true;
    LookMod.LOGGER.info(
        "Voxel world ready: {} brick slots ({} MiB)", capacity, (long) capacity * BRICK_BYTES / (1024 * 1024));
  }

  private static void createIndirectionTexture() {
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_INDIRECTION);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, indirectionTex);

    ByteBuffer zeros = MemoryUtil.memCalloc(GRID_XZ * sectionCount * GRID_XZ * 4);
    try {
      GL33C.glTexImage3D(
          GL33C.GL_TEXTURE_3D, 0, GL33C.GL_R32UI,
          GRID_XZ, sectionCount, GRID_XZ, 0,
          GL33C.GL_RED_INTEGER, GL33C.GL_UNSIGNED_INT, zeros);
    } finally {
      MemoryUtil.memFree(zeros);
    }

    GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_WRAP_R, GL33C.GL_CLAMP_TO_EDGE);

    indirectionStale = false;
  }

  private static void uploadColors() {
    colorScratch.clear();
    colorScratch.put(BlockClassifier.colors());
    colorScratch.flip();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, colorBuf);
    GL33C.glBufferSubData(GL33C.GL_TEXTURE_BUFFER, 0, colorScratch);
  }
}
