package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
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
 *   <li>A texture buffer (RGBA32F) with the per-face atlas mapping of each voxel id, see
 *       {@link BlockClassifier}. Textures themselves come from vanilla's block atlas.
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
  public static final int UNIT_FACES = 13;
  public static final int UNIT_ATLAS = 14;

  private static final int BRICK_VOXELS = 16 * 16 * 16;
  private static final int BRICK_BYTES = BRICK_VOXELS * 2;
  private static final long NO_OWNER = Long.MIN_VALUE;
  private static final long BUDGET_NANOS = 2_000_000L;
  /** Frames between sweeps that free the bricks of chunks that left the view or were dropped. */
  private static final int SWEEP_INTERVAL = 20;
  /** Chunks the client keeps loaded past the render distance; the sweep keeps the same margin. */
  private static final int VIEW_MARGIN = 3;

  private static final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
  private static final LinkedHashSet<Long> unloads = new LinkedHashSet<>();
  /** Raw packet data per chunk (keyed by {@link ChunkPos#toLong}), consumed as sections rebuild. */
  private static final Map<Long, WireChunk> wire = new HashMap<>();
  /** Sections that found no free brick slot; retried once a sweep frees some. */
  private static final LinkedHashSet<Long> starved = new LinkedHashSet<>();

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

  // Player position in chunks and the keep radius, refreshed every frame.
  private static boolean viewValid;
  private static int viewCx;
  private static int viewCz;
  private static int viewRadius;
  private static int sweepCountdown;

  // Scratch for ordering the dirty queue nearest-first.
  private static long[] dirtyKeys = new long[0];
  private static long[] dirtyOrder = new long[0];
  private static int rebuilt;

  private static boolean glReady;
  private static boolean indirectionStale;
  private static int indirectionTex;
  private static int brickBuf;
  private static int brickTex;
  private static int colorBuf;
  private static int colorTex;
  private static int faceBuf;
  private static int faceTex;

  // Vanilla's block atlas, re-queried whenever resources reload.
  private static Object atlasMarker;
  private static float atlasWidth = 1;
  private static float atlasHeight = 1;
  private static int atlasMaxLevel;

  private static ByteBuffer brickScratch;
  private static IntBuffer colorScratch;
  private static IntBuffer cellScratch;

  private VoxelWorld() {}

  // --- Accessors for the draw pass -------------------------------------------------------------

  public static boolean isReady() {
    return level != null && glReady && !indirectionStale;
  }

  /** Number of sections rebuilt since the last call. */
  public static int takeRebuilt() {
    int n = rebuilt;
    rebuilt = 0;
    return n;
  }

  public static int usedSlots() {
    return capacity - freeCount;
  }

  public static int capacity() {
    return capacity;
  }

  /** Sections waiting to be rebuilt. */
  public static int queued() {
    return dirty.size() + starved.size();
  }

  public static float atlasWidth() {
    return atlasWidth;
  }

  public static float atlasHeight() {
    return atlasHeight;
  }

  public static int atlasMaxLevel() {
    return atlasMaxLevel;
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

  /**
   * Remembers the section bytes of a chunk packet so its sections can be decoded without touching
   * the live chunk. Must be called before vanilla consumes {@code buf}; this only reads a view.
   */
  public static void ingestWire(int cx, int cz, FriendlyByteBuf buf) {
    if (!trackLevel()) return;
    WireChunk chunk = WireChunk.parse(new FriendlyByteBuf(buf.duplicate()), sectionCount);
    if (chunk == null) wire.remove(ChunkPos.asLong(cx, cz));
    else wire.put(ChunkPos.asLong(cx, cz), chunk);
  }

  /** A block in this chunk changed, so its packet snapshot is stale. */
  public static void invalidateWire(int cx, int cz) {
    if (!wire.isEmpty()) wire.remove(ChunkPos.asLong(cx, cz));
  }

  public static void unloadChunk(ChunkPos pos) {
    wire.remove(pos.toLong());
    if (!trackLevel()) return;
    unloads.add(pos.toLong());
  }

  /**
   * Throws away all voxel data and queues every loaded section for a rebuild, nearest first. Used
   * after a resource reload, which invalidates every voxel id.
   */
  public static void refreshAll() {
    Minecraft mc = Minecraft.getInstance();
    ClientLevel l = mc.level;
    if (l == null || mc.player == null) return;

    switchLevel(l);

    int radius = keepRadius(mc);
    int cx = mc.player.blockPosition().getX() >> 4;
    int cz = mc.player.blockPosition().getZ() >> 4;

    List<int[]> offsets = new ArrayList<>();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) offsets.add(new int[] {dx, dz});
    }
    offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));

    for (int[] o : offsets) {
      int x = cx + o[0];
      int z = cz + o[1];
      LevelChunk chunk = l.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
      if (chunk == null) continue;

      LevelChunkSection[] sections = chunk.getSections();
      for (int i = 0; i < sections.length; i++) {
        if (sections[i].hasOnlyAir()) continue;
        dirty.add(SectionPos.asLong(x, chunk.getSectionYFromSectionIndex(i), z));
      }
    }
  }

  /** Chunks around the player that get voxel data. Less than half the grid, so nothing aliases. */
  private static int keepRadius(Minecraft mc) {
    return Math.min(GRID_XZ / 2 - 1, mc.options.getEffectiveRenderDistance() + VIEW_MARGIN);
  }

  private static void updateView() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) {
      viewValid = false;
      return;
    }
    viewCx = mc.player.blockPosition().getX() >> 4;
    viewCz = mc.player.blockPosition().getZ() >> 4;
    viewRadius = keepRadius(mc);
    viewValid = true;
  }

  private static boolean outOfView(int cx, int cz) {
    return viewValid && (Math.abs(cx - viewCx) > viewRadius || Math.abs(cz - viewCz) > viewRadius);
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
    wire.clear();
    starved.clear();
    viewValid = false;
    sweepCountdown = 0;
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
      checkAtlas();
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

      updateView();
      if (--sweepCountdown <= 0) {
        sweepCountdown = SWEEP_INTERVAL;
        sweep();
      }

      processDirty();

      if (BlockClassifier.takeColorsDirty()) uploadColors();
      uploadFaces();
    } finally {
      GL33C.glActiveTexture(prevUnit);
    }
  }

  /** Rebuilds queued sections nearest-first until the frame budget runs out. */
  private static void processDirty() {
    int n = dirty.size();
    if (n == 0) return;

    if (dirtyKeys.length < n) {
      dirtyKeys = new long[n * 2];
      dirtyOrder = new long[n * 2];
    }
    int i = 0;
    for (long key : dirty) {
      long dx = SectionPos.x(key) - viewCx;
      long dz = SectionPos.z(key) - viewCz;
      dirtyKeys[i] = key;
      dirtyOrder[i] = (viewValid ? dx * dx + dz * dz : 0) << 32 | i;
      i++;
    }
    Arrays.sort(dirtyOrder, 0, n);

    long start = System.nanoTime();
    for (int j = 0; j < n; j++) {
      long key = dirtyKeys[(int) (dirtyOrder[j] & 0xFFFFFFFFL)];
      dirty.remove(key);
      rebuildSection(key);
      if (System.nanoTime() - start > BUDGET_NANOS) break;
    }
  }

  /**
   * Frees the bricks of sections whose chunk is gone or out of view. Vanilla does not always call
   * {@code drop} (chunks that fall out of the view range are just replaced), so this is what keeps
   * the slot pool from leaking. Then retries the sections that were starved of slots.
   */
  private static void sweep() {
    for (int cell = 0; cell < owner.length; cell++) {
      long key = owner[cell];
      if (key == NO_OWNER) continue;

      int x = SectionPos.x(key);
      int z = SectionPos.z(key);
      if (outOfView(x, z) || level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
        releaseCell(cell);
        writeCell(x, SectionPos.y(key), z, 0);
      }
    }

    if (viewValid) {
      wire.keySet().removeIf(k -> outOfView(ChunkPos.getX(k), ChunkPos.getZ(k)));
      starved.removeIf(k -> outOfView(SectionPos.x(k), SectionPos.z(k)));
    }

    if (freeCount > 0 && !starved.isEmpty()) {
      dirty.addAll(starved);
      starved.clear();
      warnedFull = false;
    }
  }

  /** Binds the data textures and the block atlas to their fixed units. The caller restores the active unit. */
  public static void bind() {
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_INDIRECTION);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, indirectionTex);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_BRICKS);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, brickTex);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_COLORS);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, colorTex);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_FACES);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, faceTex);
    // Units above vanilla's cached range, so binding straight through GL cannot desync its cache.
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_ATLAS);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, atlasId());
  }

  private static int atlasId() {
    return Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();
  }

  /**
   * Notices a resource reload (the atlas is re-stitched, which replaces its sprite objects). All
   * voxel ids and uvs are stale then, so start over.
   */
  private static void checkAtlas() {
    TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
    Object marker = atlas.getSprite(MissingTextureAtlasSprite.getLocation());
    if (marker == atlasMarker) return;

    boolean first = atlasMarker == null;
    atlasMarker = marker;

    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_ATLAS);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, atlas.getId());
    atlasWidth = Math.max(1, GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_TEXTURE_WIDTH));
    atlasHeight = Math.max(1, GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_TEXTURE_HEIGHT));
    atlasMaxLevel = GL33C.glGetTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAX_LEVEL);

    BlockClassifier.reset();
    if (!first) {
      LookMod.LOGGER.info("Resources reloaded; rebuilding voxel data");
      refreshAll();
    }
  }

  private static void uploadFaces() {
    int[] range = BlockClassifier.takeFaceRange();
    if (range == null) return;

    int from = range[0] * BlockClassifier.ID_FLOATS;
    int count = (range[1] - range[0] + 1) * BlockClassifier.ID_FLOATS;
    FloatBuffer buf = MemoryUtil.memAllocFloat(count);
    try {
      buf.put(BlockClassifier.faces(), from, count).flip();
      GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, faceBuf);
      GL33C.glBufferSubData(GL33C.GL_TEXTURE_BUFFER, (long) from * 4, buf);
    } finally {
      MemoryUtil.memFree(buf);
    }
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
    rebuilt++;
    int sx = SectionPos.x(key);
    int sy = SectionPos.y(key);
    int sz = SectionPos.z(key);
    int cell = cellIndex(sx, sy, sz);

    if (outOfView(sx, sz)) {
      clearIfOwner(key, sx, sy, sz);
      return;
    }

    LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
    if (chunk == null) {
      clearIfOwner(key, sx, sy, sz);
      return;
    }

    int solid = convertWire(sx, sy, sz);
    if (solid < 0) {
      LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
      solid = section.hasOnlyAir() ? 0 : convert(section);
    }
    if (solid == 0) {
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
        starved.add(key);
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

  /**
   * Fills {@link #brickScratch} from the packet data of this section and returns how many voxels
   * are non-air, or -1 if there is no usable packet data (then use {@link #convert}).
   */
  private static int convertWire(int sx, int sy, int sz) {
    long chunkKey = ChunkPos.asLong(sx, sz);
    WireChunk chunk = wire.get(chunkKey);
    if (chunk == null) return -1;

    int solid = chunk.take(sy - minSection, brickScratch);
    if (chunk.isDone()) wire.remove(chunkKey);
    return solid;
  }

  /** Slow path: fills {@link #brickScratch} from the live chunk, one block state at a time. */
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
    int wanted = Integer.getInteger("look.brickCapacity", 32768);
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

    faceBuf = GL33C.glGenBuffers();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, faceBuf);
    GL33C.glBufferData(
        GL33C.GL_TEXTURE_BUFFER, (long) (BlockClassifier.MAX_ID + 1) * BlockClassifier.ID_FLOATS * 4, GL33C.GL_DYNAMIC_DRAW);
    faceTex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_FACES);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, faceTex);
    GL33C.glTexBuffer(GL33C.GL_TEXTURE_BUFFER, GL33C.GL_RGBA32F, faceBuf);

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
