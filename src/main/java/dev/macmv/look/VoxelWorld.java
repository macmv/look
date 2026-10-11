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
 * <p>All methods run on the render thread (which is also the client main thread), so there are no
 * data races; the hazard is inconsistent bookkeeping. Every tracked section therefore has exactly
 * one {@link SectionState} in {@link #states}, and every change goes through {@link #setState}:
 *
 * <pre>
 *   (untracked) --event--> WAITING --rebuild--> LOADED | EMPTY | STARVED
 *   WAITING | LOADED | EMPTY | STARVED --left the view--> OUT_OF_RANGE
 *   OUT_OF_RANGE --entered the view--> WAITING
 *   any --chunk dropped--> (untracked)
 * </pre>
 *
 * <p>Events only change state and CPU bookkeeping; GL is touched in {@link #update()}. Decoding is
 * synchronous, so there is no LOADING state: WAITING is rebuilt within a single call.
 */
public final class VoxelWorld {
  /** Where a section is in its life. A slot is owned exactly by LOADED sections (and WAITING ones that were LOADED). */
  private enum SectionState {
    /** Needs a (re)build and is queued. May still own a slot, which keeps showing the old data. */
    WAITING,
    /** Wants a brick slot but the pool was full. Retried as slots free up. */
    STARVED,
    /** Resident and up to date. */
    LOADED,
    /** Up to date and all air. Owns no slot. */
    EMPTY,
    /** The chunk exists but is outside the view radius. Owns no slot; promoted when the view moves. */
    OUT_OF_RANGE
  }

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
  /** Chunks the client keeps loaded past the render distance; the view keeps the same margin. */
  private static final int VIEW_MARGIN = 3;
  /**
   * Largest view radius in chunks. Sections stay resident up to one chunk past the view radius
   * (hysteresis), and two sections alias in the wrapping grid at distance {@link #GRID_XZ}, so
   * 2 * (MAX_RADIUS + 1) + 1 must stay below it.
   */
  private static final int MAX_RADIUS = GRID_XZ / 2 - 2;

  /** The state of every tracked section, keyed by {@link SectionPos#asLong}. */
  private static final Map<Long, SectionState> states = new HashMap<>();
  /** Sections in WAITING, a mirror of {@link #states} so the queue needs no scan. */
  private static final LinkedHashSet<Long> waiting = new LinkedHashSet<>();
  /** Sections in STARVED, likewise. */
  private static final LinkedHashSet<Long> starved = new LinkedHashSet<>();
  /** Indirection cells whose CPU value differs from the texture, flushed once per update. */
  private static final LinkedHashSet<Integer> dirtyCells = new LinkedHashSet<>();
  /** Raw packet data per chunk (keyed by {@link ChunkPos#toLong}), consumed as sections rebuild. */
  private static final Map<Long, WireChunk> wire = new HashMap<>();

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

  // Player position in chunks and the view radius.
  private static boolean viewValid;
  private static int viewCx;
  private static int viewCz;
  private static int viewRadius;

  /** A reload was requested while there was no player to center it on. */
  private static boolean refreshPending;

  // Nearest-first snapshot of the waiting queue, rebuilt when exhausted or when the view moves.
  private static long[] order = new long[0];
  private static int orderLen;
  private static int orderPos;
  private static long[] orderScratch = new long[0];

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
    return waiting.size() + starved.size();
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

  /**
   * Vanilla's "section needs a re-render" signal. It also fires for neighbor updates, which do not
   * change voxels, so it only starts tracking sections we have never seen. Real content changes
   * come in through {@link #ingestWire} and {@link #blockChanged}.
   */
  public static void markDirty(int sx, int sy, int sz) {
    if (!trackLevel() || !validY(sy)) return;
    long key = SectionPos.asLong(sx, sy, sz);
    if (!states.containsKey(key)) track(key);
  }

  /** A block changed in section (sx, sy, sz): its packet snapshot is stale and it needs a rebuild. */
  public static void blockChanged(int sx, int sy, int sz) {
    if (!trackLevel()) return;
    if (!wire.isEmpty()) wire.remove(ChunkPos.asLong(sx, sz));
    if (validY(sy)) track(SectionPos.asLong(sx, sy, sz));
  }

  /**
   * A chunk packet is being applied: remember its section bytes so the sections can be decoded
   * without touching the live chunk, and rebuild every section of it. Must be called before vanilla
   * consumes {@code buf}; this only reads a view.
   */
  public static void ingestWire(int cx, int cz, FriendlyByteBuf buf) {
    if (!trackLevel()) return;
    WireChunk chunk = WireChunk.parse(new FriendlyByteBuf(buf.duplicate()), sectionCount);
    if (chunk == null) wire.remove(ChunkPos.asLong(cx, cz));
    else wire.put(ChunkPos.asLong(cx, cz), chunk);

    for (int sy = minSection; sy < minSection + sectionCount; sy++) track(SectionPos.asLong(cx, sy, cz));
  }

  public static void unloadChunk(ChunkPos pos) {
    wire.remove(pos.toLong());
    if (!trackLevel()) return;
    for (int sy = minSection; sy < minSection + sectionCount; sy++) forget(SectionPos.asLong(pos.x, sy, pos.z));
  }

  /**
   * Throws away all voxel data and queues every loaded section for a rebuild, nearest first. Used
   * after a resource reload, which invalidates every voxel id.
   */
  public static void refreshAll() {
    Minecraft mc = Minecraft.getInstance();
    ClientLevel l = mc.level;
    if (l == null) return;
    if (mc.player == null) {
      // Nothing to center on yet; the voxel ids are stale, so try again once there is a player.
      refreshPending = true;
      return;
    }

    switchLevel(l);
    updateView();

    // Track the whole client-side chunk range, including chunks outside the view, so they can be
    // promoted later without waiting for another event.
    int reach = Math.max(2, mc.options.getEffectiveRenderDistance()) + VIEW_MARGIN + 1;
    int cx = mc.player.blockPosition().getX() >> 4;
    int cz = mc.player.blockPosition().getZ() >> 4;

    List<int[]> offsets = new ArrayList<>();
    for (int dx = -reach; dx <= reach; dx++) {
      for (int dz = -reach; dz <= reach; dz++) offsets.add(new int[] {dx, dz});
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
        track(SectionPos.asLong(x, chunk.getSectionYFromSectionIndex(i), z));
      }
    }
  }

  private static boolean validY(int sy) {
    return sy >= minSection && sy < minSection + sectionCount;
  }

  /** Chunks around the player that get voxel data. */
  private static int keepRadius(Minecraft mc) {
    return Math.min(MAX_RADIUS, mc.options.getEffectiveRenderDistance() + VIEW_MARGIN);
  }

  /** Farthest distance in blocks the shader may march, so it never reads past the loaded range. */
  public static float maxDistBlocks() {
    int viewChunks = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
    return Math.min((MAX_RADIUS - 1), viewChunks) * 16f;
  }

  /** Refreshes the view from the player. Returns true if it moved or changed size. */
  private static boolean updateView() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) return false;

    int cx = mc.player.blockPosition().getX() >> 4;
    int cz = mc.player.blockPosition().getZ() >> 4;
    int r = keepRadius(mc);
    if (viewValid && cx == viewCx && cz == viewCz && r == viewRadius) return false;

    viewCx = cx;
    viewCz = cz;
    viewRadius = r;
    viewValid = true;
    return true;
  }

  private static int viewDistance(int cx, int cz) {
    return Math.max(Math.abs(cx - viewCx), Math.abs(cz - viewCz));
  }

  /** Whether a chunk column is inside the view (before there is a view, everything is). */
  private static boolean inView(int cx, int cz, int slack) {
    return !viewValid || viewDistance(cx, cz) <= viewRadius + slack;
  }

  // --- State transitions -----------------------------------------------------------------------

  /** Content of a section is new or changed: queue it, or park it if it is outside the view. */
  private static void track(long key) {
    int sx = SectionPos.x(key);
    int sy = SectionPos.y(key);
    int sz = SectionPos.z(key);

    // Resident sections get one chunk of slack so they do not flicker at the edge.
    boolean resident = owner[cellIndex(sx, sy, sz)] == key;
    if (inView(sx, sz, resident ? 1 : 0)) {
      setState(key, SectionState.WAITING);
    } else {
      release(key);
      setState(key, SectionState.OUT_OF_RANGE);
    }
  }

  /** The only place that writes {@link #states}; keeps the queues in step with it. */
  private static void setState(long key, SectionState state) {
    states.put(key, state);
    if (state == SectionState.WAITING) waiting.add(key);
    else waiting.remove(key);
    if (state == SectionState.STARVED) starved.add(key);
    else starved.remove(key);
  }

  /** The chunk is gone: stop tracking the section and free whatever it held. */
  private static void forget(long key) {
    states.remove(key);
    waiting.remove(key);
    starved.remove(key);
    release(key);
  }

  /** Frees the slot of a section, if it owns one, and queues the cell for upload. */
  private static void release(long key) {
    int cell = cellIndex(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
    if (owner[cell] != key) return;
    if (indirection[cell] != 0) freeSlots[freeCount++] = indirection[cell] - 1;
    indirection[cell] = 0;
    owner[cell] = NO_OWNER;
    dirtyCells.add(cell);
  }

  /**
   * Re-evaluates every tracked section against the view after it moved: sections that left it are
   * parked, parked sections that entered it are queued (nearest first), and sections whose chunk
   * vanished without a {@code drop} call are forgotten.
   */
  private static void sweepView() {
    List<Long> gone = new ArrayList<>();
    List<Long> leaving = new ArrayList<>();
    List<Long> entering = new ArrayList<>();

    for (Map.Entry<Long, SectionState> e : states.entrySet()) {
      long key = e.getKey();
      int x = SectionPos.x(key);
      int z = SectionPos.z(key);
      if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
        gone.add(key);
      } else if (e.getValue() == SectionState.OUT_OF_RANGE) {
        if (inView(x, z, 0)) entering.add(key);
      } else if (!inView(x, z, 1)) {
        leaving.add(key);
      }
    }

    for (long key : gone) forget(key);
    for (long key : leaving) {
      release(key);
      setState(key, SectionState.OUT_OF_RANGE);
    }
    entering.sort(Comparator.comparingInt(k -> viewDistance(SectionPos.x(k), SectionPos.z(k))));
    for (long key : entering) setState(key, SectionState.WAITING);

    wire.keySet().removeIf(k -> !inView(ChunkPos.getX(k), ChunkPos.getZ(k), 1));
    orderLen = 0;
    orderPos = 0;
  }

  /** Gives starved sections another chance, as many as there are free slots. */
  private static void requeueStarved() {
    if (freeCount == 0 || starved.isEmpty()) return;
    List<Long> retry = new ArrayList<>();
    for (long key : starved) {
      retry.add(key);
      if (retry.size() >= freeCount) break;
    }
    for (long key : retry) setState(key, SectionState.WAITING);
    warnedFull = false;
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

    states.clear();
    waiting.clear();
    starved.clear();
    dirtyCells.clear();
    wire.clear();
    orderLen = 0;
    orderPos = 0;
    viewValid = false;
    refreshPending = false;
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
      if (refreshPending) refreshAll();
      if (indirectionStale) createIndirectionTexture();

      if (updateView()) sweepView();
      requeueStarved();

      // Everything below uploads to the indirection texture on this unit, and bricks via the
      // (unit independent) texture buffer binding point.
      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_INDIRECTION);
      GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, indirectionTex);

      processWaiting();
      flushCells();

      if (BlockClassifier.takeColorsDirty()) uploadColors();
      uploadFaces();
    } finally {
      GL33C.glActiveTexture(prevUnit);
    }
  }

  /** Rebuilds waiting sections nearest-first until the frame budget runs out. */
  private static void processWaiting() {
    long start = System.nanoTime();
    while (true) {
      if (orderPos >= orderLen) {
        if (waiting.isEmpty()) return;
        buildOrder();
      }
      long key = order[orderPos++];
      if (!waiting.remove(key)) continue; // rebuilt, dropped or parked since the snapshot
      rebuildSection(key);
      if (System.nanoTime() - start > BUDGET_NANOS) return;
    }
  }

  /** Snapshots the waiting queue sorted by distance from the player. */
  private static void buildOrder() {
    int n = waiting.size();
    if (order.length < n) {
      order = new long[n * 2];
      orderScratch = new long[n * 2];
    }
    int i = 0;
    for (long key : waiting) {
      long dx = SectionPos.x(key) - viewCx;
      long dz = SectionPos.z(key) - viewCz;
      order[i] = key;
      orderScratch[i] = (viewValid ? dx * dx + dz * dz : 0) << 32 | i;
      i++;
    }
    Arrays.sort(orderScratch, 0, n);

    // orderScratch holds (distance, index); reorder keys through a copy of the unsorted ones.
    long[] unsorted = Arrays.copyOf(order, n);
    for (int j = 0; j < n; j++) order[j] = unsorted[(int) (orderScratch[j] & 0xFFFFFFFFL)];
    orderLen = n;
    orderPos = 0;
  }

  /** Writes every changed indirection cell to the texture. Expects it bound on its unit. */
  private static void flushCells() {
    for (int cell : dirtyCells) {
      int x = cell % GRID_XZ;
      int z = (cell / GRID_XZ) % GRID_XZ;
      int y = cell / (GRID_XZ * GRID_XZ);
      cellScratch.put(0, indirection[cell]);
      GL33C.glTexSubImage3D(
          GL33C.GL_TEXTURE_3D, 0, x, y, z, 1, 1, 1, GL33C.GL_RED_INTEGER, GL33C.GL_UNSIGNED_INT, cellScratch);
    }
    dirtyCells.clear();
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

  /** WAITING -> LOADED, EMPTY or STARVED. The caller has already taken the key off the queue. */
  private static void rebuildSection(long key) {
    rebuilt++;
    int sx = SectionPos.x(key);
    int sy = SectionPos.y(key);
    int sz = SectionPos.z(key);

    LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
    if (chunk == null) {
      // Not there (any more). If it shows up, its packet tracks the section again.
      forget(key);
      return;
    }

    int solid = convertWire(sx, sy, sz);
    if (solid < 0) {
      LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
      solid = section.hasOnlyAir() ? 0 : convert(section);
    }
    if (solid == 0) {
      release(key);
      setState(key, SectionState.EMPTY);
      return;
    }

    int cell = cellIndex(sx, sy, sz);
    int slot;
    if (owner[cell] == key && indirection[cell] != 0) {
      slot = indirection[cell] - 1;
    } else {
      // Cannot happen while the view fits the grid, but never let two sections share a cell.
      if (owner[cell] != NO_OWNER) {
        long other = owner[cell];
        release(other);
        if (states.containsKey(other)) setState(other, SectionState.OUT_OF_RANGE);
      }

      if (freeCount == 0) {
        if (!warnedFull) {
          LookMod.LOGGER.warn("Out of brick slots ({}); some terrain will be missing", capacity);
          warnedFull = true;
        }
        setState(key, SectionState.STARVED);
        return;
      }
      slot = freeSlots[--freeCount];
    }

    owner[cell] = key;
    indirection[cell] = slot + 1;
    dirtyCells.add(cell);

    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, brickBuf);
    brickScratch.clear();
    GL33C.glBufferSubData(GL33C.GL_TEXTURE_BUFFER, (long) slot * BRICK_BYTES, brickScratch);

    setState(key, SectionState.LOADED);
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

