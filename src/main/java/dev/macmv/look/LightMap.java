package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

/**
 * Vanilla's sky and block light of the loaded chunks, mirrored to the GPU for level 0 of the pyramid.
 *
 * <p>Same shape as the block data, but independent of it, because empty (all air) sections have no
 * brick and still have light:
 * <ul>
 *   <li>An indirection 3D texture (R32UI) with one texel per section, value 0 for "no data" and
 *       {@code slot + 1} otherwise. X and Z wrap like {@link VoxelWorld}'s grid; there are only
 *       the rows of level 0.
 *   <li>A texture buffer (R8UI) of 4096 bytes per slot, {@code sky << 4 | block} per voxel, indexed
 *       {@code (y * 16 + z) * 16 + x}. Sections that are one light value throughout (open sky,
 *       solid rock) share one slot per value.
 * </ul>
 *
 * <p>Sections are marked dirty by {@link #mark} (vanilla's light update hook) and by chunk loads,
 * and rebuilt on the render thread within a time budget.
 */
public final class LightMap {
  public static final int UNIT_INDIRECTION = 16;
  public static final int UNIT_LIGHT = 17;

  private static final int BRICK_BYTES = 4096;
  private static final int CAPACITY = 16384;
  private static final long BUDGET_NANOS = 1_000_000L;

  private static ClientLevel level;
  private static int minSection;
  private static int rows;

  /** Sections (level 0 {@link SectionKey}s) that need a rebuild. */
  private static final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
  private static int[] indirection;
  private static long[] owner;
  private static final LinkedHashSet<Integer> dirtyCells = new LinkedHashSet<>();

  private static final int[] freeSlots = new int[CAPACITY];
  private static int freeCount;
  /** Per slot the light value it is filled with (-1 for a private brick), and how many sections use it. */
  private static final int[] slotUniform = new int[CAPACITY];
  private static final int[] slotRefs = new int[CAPACITY];
  private static final Map<Integer, Integer> uniformSlots = new HashMap<>();
  private static boolean warnedFull;

  private static boolean glReady;
  private static boolean texStale = true;
  private static int indirectionTex;
  private static int lightBuf;
  private static int lightTex;
  private static ByteBuffer scratch;
  private static IntBuffer cellScratch;

  private LightMap() {}

  // --- Events (cheap, no GL) -------------------------------------------------------------------

  /** Light changed in section (sx, sy, sz). */
  public static void mark(int sx, int sy, int sz) {
    if (!trackLevel() || sy < minSection || sy >= minSection + rows) return;
    dirty.add(SectionKey.pack(0, sx, sy, sz));
  }

  /** A chunk arrived: every section of the column needs a look, once its light is in. */
  public static void markColumn(int cx, int cz) {
    for (int sy = minSection; sy < minSection + rows; sy++) mark(cx, sy, cz);
  }

  /** A chunk is gone: its light goes with it. */
  public static void dropColumn(int cx, int cz) {
    if (!trackLevel()) return;
    for (int sy = minSection; sy < minSection + rows; sy++) {
      long key = SectionKey.pack(0, cx, sy, cz);
      dirty.remove(key);
      release(key);
    }
  }

  // --- Per-frame update (GL) -------------------------------------------------------------------

  /** Rebuilds dirty sections and uploads the changes. Call once per frame before drawing. */
  public static void update() {
    if (!trackLevel()) return;

    int prevUnit = GlStateManager._getActiveTexture();
    try {
      if (!glReady) initGl();
      if (texStale) createIndirectionTexture();

      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_INDIRECTION);
      GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, indirectionTex);

      long start = System.nanoTime();
      var it = dirty.iterator();
      while (it.hasNext()) {
        long key = it.next();
        it.remove();
        rebuild(key);
        if (System.nanoTime() - start > BUDGET_NANOS) break;
      }
      flushCells();
    } finally {
      GL33C.glActiveTexture(prevUnit);
    }
  }

  /** Binds the data textures to their fixed units. The caller restores the active unit. */
  public static void bind() {
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_INDIRECTION);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, indirectionTex);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_LIGHT);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, lightTex);
  }

  // --- Sections --------------------------------------------------------------------------------

  private static boolean trackLevel() {
    ClientLevel current = Minecraft.getInstance().level;
    if (current == null) return false;
    if (current != level) switchLevel(current);
    return true;
  }

  private static int cellIndex(long key) {
    int row = SectionKey.y(key) - minSection;
    int g = VoxelWorld.GRID_XZ - 1;
    return (row * VoxelWorld.GRID_XZ + (SectionKey.z(key) & g)) * VoxelWorld.GRID_XZ + (SectionKey.x(key) & g);
  }

  private static void switchLevel(ClientLevel l) {
    level = l;
    minSection = l.getMinSection();
    rows = l.getSectionsCount();
    indirection = new int[VoxelWorld.GRID_XZ * rows * VoxelWorld.GRID_XZ];
    owner = new long[indirection.length];
    Arrays.fill(owner, Long.MIN_VALUE);
    dirty.clear();
    dirtyCells.clear();
    resetSlots();
    texStale = true;
  }

  private static void resetSlots() {
    freeCount = 0;
    for (int i = CAPACITY - 1; i >= 0; i--) freeSlots[freeCount++] = i;
    Arrays.fill(slotUniform, -1);
    Arrays.fill(slotRefs, 0);
    uniformSlots.clear();
    warnedFull = false;
  }

  private static void rebuild(long key) {
    int sx = SectionKey.x(key);
    int sy = SectionKey.y(key);
    int sz = SectionKey.z(key);

    if (level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false) == null) {
      release(key);
      return;
    }

    LevelLightEngine engine = level.getLightEngine();
    SectionPos pos = SectionPos.of(sx, sy, sz);
    DataLayer sky = engine.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
    DataLayer block = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);

    // A missing layer is one value throughout. The sky one is not always 0: above the highest
    // block it is full, which the engine knows how to answer.
    int skyFill = 0;
    if (sky == null) {
      skyFill = engine.getLayerListener(LightLayer.SKY).getLightValue(new BlockPos(sx << 4, sy << 4, sz << 4));
    }

    int first = -1;
    boolean uniform = true;
    for (int y = 0; y < 16; y++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          int s = sky == null ? skyFill : sky.get(x, y, z);
          int b = block == null ? 0 : block.get(x, y, z);
          int v = s << 4 | b;
          scratch.put(((y * 16 + z) << 4) + x, (byte) v);
          if (first < 0) first = v;
          else if (v != first) uniform = false;
        }
      }
    }

    int cell = cellIndex(key);
    if (owner[cell] != Long.MIN_VALUE && owner[cell] != key) release(owner[cell]);
    int own = owner[cell] == key && indirection[cell] != 0 ? indirection[cell] - 1 : -1;

    int slot;
    boolean upload = true;
    if (uniform) {
      Integer shared = uniformSlots.get(first);
      if (shared != null) {
        slot = shared;
        upload = false;
      } else {
        slot = allocSlot();
        if (slot < 0) return;
        slotUniform[slot] = first;
        slotRefs[slot] = 0;
        uniformSlots.put(first, slot);
      }
      if (own != slot) {
        slotRefs[slot]++;
        if (own >= 0) freeSlot(own);
      }
    } else if (own >= 0 && slotUniform[own] < 0) {
      slot = own;
    } else {
      slot = allocSlot();
      if (slot < 0) return;
      if (own >= 0) freeSlot(own);
    }

    owner[cell] = key;
    indirection[cell] = slot + 1;
    dirtyCells.add(cell);

    if (upload) {
      GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, lightBuf);
      scratch.clear();
      GL33C.glBufferSubData(GL33C.GL_TEXTURE_BUFFER, (long) slot * BRICK_BYTES, scratch);
    }
  }

  private static void release(long key) {
    if (owner == null) return;
    int cell = cellIndex(key);
    if (owner[cell] != key) return;
    if (indirection[cell] != 0) freeSlot(indirection[cell] - 1);
    indirection[cell] = 0;
    owner[cell] = Long.MIN_VALUE;
    dirtyCells.add(cell);
  }

  private static int allocSlot() {
    if (freeCount == 0) {
      if (!warnedFull) {
        LookMod.LOGGER.warn("Out of light slots ({}); some light will be missing", CAPACITY);
        warnedFull = true;
      }
      return -1;
    }
    return freeSlots[--freeCount];
  }

  private static void freeSlot(int slot) {
    int value = slotUniform[slot];
    if (value >= 0) {
      if (--slotRefs[slot] > 0) return;
      uniformSlots.remove(value);
      slotUniform[slot] = -1;
    }
    freeSlots[freeCount++] = slot;
  }

  // --- GL resources ----------------------------------------------------------------------------

  private static void flushCells() {
    int g = VoxelWorld.GRID_XZ;
    for (int cell : dirtyCells) {
      int x = cell % g;
      int z = (cell / g) % g;
      int y = cell / (g * g);
      cellScratch.put(0, indirection[cell]);
      GL33C.glTexSubImage3D(
          GL33C.GL_TEXTURE_3D, 0, x, y, z, 1, 1, 1, GL33C.GL_RED_INTEGER, GL33C.GL_UNSIGNED_INT, cellScratch);
    }
    dirtyCells.clear();
  }

  private static void initGl() {
    scratch = ByteBuffer.allocateDirect(BRICK_BYTES).order(ByteOrder.nativeOrder());
    cellScratch = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();

    lightBuf = GL33C.glGenBuffers();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, lightBuf);
    GL33C.glBufferData(GL33C.GL_TEXTURE_BUFFER, (long) CAPACITY * BRICK_BYTES, GL33C.GL_DYNAMIC_DRAW);
    lightTex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_LIGHT);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, lightTex);
    GL33C.glTexBuffer(GL33C.GL_TEXTURE_BUFFER, GL33C.GL_R8UI, lightBuf);

    indirectionTex = GL33C.glGenTextures();
    glReady = true;
  }

  private static void createIndirectionTexture() {
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_INDIRECTION);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, indirectionTex);

    int g = VoxelWorld.GRID_XZ;
    ByteBuffer zeros = MemoryUtil.memCalloc(g * rows * g * 4);
    try {
      GL33C.glTexImage3D(
          GL33C.GL_TEXTURE_3D, 0, GL33C.GL_R32UI, g, rows, g, 0,
          GL33C.GL_RED_INTEGER, GL33C.GL_UNSIGNED_INT, zeros);
    } finally {
      MemoryUtil.memFree(zeros);
    }

    GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_WRAP_R, GL33C.GL_CLAMP_TO_EDGE);
    texStale = false;
  }
}
