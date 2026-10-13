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
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

/**
 * Mirrors the client level into GPU memory as sparse bricks.
 *
 * <p>Layout:
 * <ul>
 *   <li>An indirection 3D texture (R32UI), one texel per 16^3 section. Value 0 is empty, otherwise
 *       {@code brickSlot + 1}. X and Z wrap (toroidal). The levels of the LOD pyramid ({@link Lod})
 *       are stacked along Y: each has its own rows, and a section of level k is 16 voxels of
 *       2^k blocks, so it covers {@code 16 << k} blocks. All levels share one pool of brick slots.
 *   <li>A texture buffer (R16UI) holding all bricks: 4096 voxel ids per slot. A section that is
 *       one solid voxel id throughout (solid stone underground, mostly) does not get a brick of its
 *       own: all sections of that id point at one shared brick.
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
 *   (untracked) --event--> WAITING --rebuild--> LOADED | EMPTY | STARVED | LOADING
 *   LOADING --source finished (invalidate)--> WAITING
 *   WAITING | LOADING | LOADED | EMPTY | STARVED --left the view--> OUT_OF_RANGE
 *   OUT_OF_RANGE --entered the view--> WAITING
 *   any --chunk dropped--> (untracked)
 * </pre>
 *
 * <p>Sections are keyed by {@link SectionKey} (level + section coordinates). Where their blocks come
 * from is up to the {@link VoxelSource} assigned to their level; the world source is the default.
 *
 * <p>Events only change state and CPU bookkeeping; GL is touched in {@link #update()}. Sources that
 * decode synchronously never leave a section LOADING; sources that work in the background answer
 * {@link VoxelSource#PENDING} and report back with {@link #invalidate}.
 */
public final class VoxelWorld {
  /** Where a section is in its life. A slot is owned exactly by LOADED sections (and WAITING ones that were LOADED). */
  private enum SectionState {
    /** Needs a (re)build and is queued. May still own a slot, which keeps showing the old data. */
    WAITING,
    /** The source is producing the data in the background. May still own a slot with the old data. */
    LOADING,
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
  public static final int UNIT_TINT = 15;

  private static final int BRICK_VOXELS = 16 * 16 * 16;
  private static final int BRICK_BYTES = BRICK_VOXELS * 2;
  private static final long BUDGET_NANOS = 2_000_000L;
  /**
   * Extra sections kept past what a source can reach. Sections stay resident up to one section past
   * the view (hysteresis), and two sections alias in the wrapping grid at distance
   * {@link #GRID_XZ}, so {@code 2 * (Lod.outer + 1) + 1} must stay below it.
   */
  private static final int REACH_MARGIN = 2;

  /** Where each level gets its blocks. */
  private static final VoxelSource[] sources = new VoxelSource[Lod.LEVELS];

  /** The state of every tracked section, keyed by {@link SectionKey}. */
  private static final Map<Long, SectionState> states = new HashMap<>();
  /** Sections in WAITING, a mirror of {@link #states} so the queue needs no scan. */
  private static final LinkedHashSet<Long> waiting = new LinkedHashSet<>();
  /** Sections in STARVED, likewise. */
  private static final LinkedHashSet<Long> starved = new LinkedHashSet<>();
  /** Sections in LOADING, likewise. */
  private static final LinkedHashSet<Long> loading = new LinkedHashSet<>();
  /** Indirection cells whose CPU value differs from the texture, flushed once per update. */
  private static final LinkedHashSet<Integer> dirtyCells = new LinkedHashSet<>();

  static {
    Arrays.fill(sources, WorldSource.INSTANCE);
    // The loaded world, with generated terrain beneath it where there are no chunks (singleplayer
    // only; elsewhere that is just the loaded world).
    VoxelSource layered = new LayeredSource(WorldSource.INSTANCE, SeedSource.INSTANCE);
    for (int k = 0; k < Lod.LEVELS; k++) sources[k] = layered;
  }

  private static ClientLevel level;
  private static int minSection;
  private static int sectionCount;

  // Section rows of each pyramid level. Level k holds sections of 16 << k blocks, so its y range is
  // the level 0 range shifted right by k. Rows of all levels are stacked in the indirection texture.
  private static final int[] levelMin = new int[Lod.LEVELS];
  private static final int[] levelRows = new int[Lod.LEVELS];
  private static final int[] levelRow0 = new int[Lod.LEVELS];
  private static int totalRows;

  // CPU mirror of the indirection texture, which section owns each cell and the brick slots.
  private static final BrickTable table = new BrickTable();
  private static boolean warnedFull;

  // Player position in sections of each level, and how far from it each level is built.
  private static boolean viewValid;
  private static final int[] viewX = new int[Lod.LEVELS];
  private static final int[] viewZ = new int[Lod.LEVELS];
  private static final int[] viewOuter = new int[Lod.LEVELS];

  /** A reload was requested while there was no player to center it on. */
  private static boolean refreshPending;
  /** Something other than the view changed what sweepView would find. */
  private static boolean sweepRequested;

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
  /**
   * Sampler object for the atlas unit. Vanilla flips the atlas texture's min filter between NEAREST
   * and NEAREST_MIPMAP_LINEAR from frame to frame; with NEAREST, textureLod ignores the level, so
   * far blocks flickered between sharp and blurred. A sampler overrides the texture's own state.
   */
  private static int atlasSampler;
  private static float atlasWidth = 1;
  private static float atlasHeight = 1;
  private static int atlasMaxLevel;

  private static ByteBuffer brickScratch;
  /** Hash of what was last written to each brick slot, to compare against the GPU in {@link #verify}. */
  private static long[] slotHash = new long[0];
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
    return table.used();
  }

  public static int capacity() {
    return table.capacity();
  }

  /** Sections waiting to be rebuilt. */
  public static int queued() {
    return waiting.size() + starved.size();
  }

  /** Sections whose source is still producing them. */
  public static int loading() {
    return loading.size();
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

  /** Lowest section y of the level (vanilla's), i.e. of pyramid level 0. */
  public static int minSection() {
    return minSection;
  }

  /** Section count of the level (vanilla's), i.e. of pyramid level 0. */
  public static int sectionCount() {
    return sectionCount;
  }

  /** Lowest section y at pyramid level {@code k}. */
  public static int levelMin(int k) {
    return levelMin[k];
  }

  /** Number of section rows at pyramid level {@code k}. */
  public static int levelRows(int k) {
    return levelRows[k];
  }

  /** Rows of the indirection texture, over all levels. */
  public static int totalRows() {
    return totalRows;
  }

  /** Per level {@code (lowest section y, rows, first texture row)}, padded to {@link Lod#MAX_LEVELS}. */
  public static int[] levelParams() {
    int[] out = new int[Lod.MAX_LEVELS * 3];
    for (int k = 0; k < Lod.LEVELS; k++) {
      out[k * 3] = levelMin[k];
      out[k * 3 + 1] = levelRows[k];
      out[k * 3 + 2] = levelRow0[k];
    }
    return out;
  }

  /** Number of resident sections at each level. */
  public static int[] loadedPerLevel() {
    int[] n = new int[Lod.LEVELS];
    for (Map.Entry<Long, SectionState> e : states.entrySet()) {
      if (e.getValue() == SectionState.LOADED) n[SectionKey.level(e.getKey())]++;
    }
    return n;
  }

  // --- Sources ---------------------------------------------------------------------------------

  public static VoxelSource sourceAt(int level) {
    return sources[level];
  }

  /** Makes {@code source} provide pyramid level {@code level} and rebuilds everything. */
  public static void setSource(int level, VoxelSource source) {
    sources[level] = source;
    refreshAll();
  }

  // --- Events (cheap, no GL) -------------------------------------------------------------------

  /**
   * The content of a section is new or changed. Sources call this; sections outside the level's
   * window are parked until the view reaches them.
   */
  public static void invalidate(int level, int sx, int sy, int sz) {
    if (!trackLevel() || !validY(level, sy)) return;
    track(SectionKey.pack(level, sx, sy, sz));
  }

  /**
   * Asks for the next update to re-check the whole view, as if the player had moved. For sources
   * whose availability changes (enumerated sources are only asked for sections during a sweep).
   */
  public static void requestSweep() {
    sweepRequested = true;
  }

  /** Whether the section already has a state (is queued, resident, empty, ...). */
  public static boolean isTracked(int level, int sx, int sy, int sz) {
    return trackLevel() && states.containsKey(SectionKey.pack(level, sx, sy, sz));
  }

  /** The data of a whole column is gone: stop tracking all its sections and free their slots. */
  public static void dropColumn(int level, int sx, int sz) {
    if (!trackLevel()) return;
    for (int sy = levelMin[level]; sy < levelMin[level] + levelRows[level]; sy++) {
      forget(SectionKey.pack(level, sx, sy, sz));
    }

    // An enumerated source can still produce the column (generated terrain behind the real
    // chunk), but only hears about it in a sweep, and the one for this move already ran.
    if (sources[level].enumerated()) sweepRequested = true;
  }

  /**
   * Throws away all voxel data and asks every source to announce what it has again. Used after a
   * resource reload, which invalidates every voxel id.
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

    // The view is recomputed by the next update(), whose sweep also enumerates pulling sources.
    switchLevel(l);

    // Pushing sources announce here.
    for (VoxelSource source : distinctSources()) source.announceAll();
  }

  private static Set<VoxelSource> distinctSources() {
    Set<VoxelSource> set = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    set.addAll(Arrays.asList(sources));
    return set;
  }

  private static boolean validY(int level, int sy) {
    return sy >= levelMin[level] && sy < levelMin[level] + levelRows[level];
  }

  /** How many sections around the player a level is built, limited by what its source can reach. */
  private static int outerFor(int level) {
    float section = 16f * (1 << level);
    int reach = (int) Math.min(1 << 20, Math.ceil(sources[level].reachBlocks() / section)) + REACH_MARGIN;
    return Math.max(1, Math.min(Lod.outer(level), reach));
  }

  /** Farthest distance in blocks the shader may march, so it never reads past what is built. */
  public static float maxDistBlocks() {
    float reach = 0;
    for (int k = 0; k < Lod.LEVELS; k++) reach = Math.max(reach, sources[k].reachBlocks());
    return Math.min(Lod.maxDist(), reach);
  }

  /** Refreshes the view from the player. Returns true if it moved or changed size. */
  private static boolean updateView() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) return false;

    int bx = mc.player.blockPosition().getX();
    int bz = mc.player.blockPosition().getZ();
    boolean changed = !viewValid;
    for (int k = 0; k < Lod.LEVELS; k++) {
      int x = bx >> (4 + k);
      int z = bz >> (4 + k);
      int outer = outerFor(k);
      if (x != viewX[k] || z != viewZ[k] || outer != viewOuter[k]) changed = true;
      viewX[k] = x;
      viewZ[k] = z;
      viewOuter[k] = outer;
    }
    viewValid = true;
    return changed;
  }

  /**
   * Whether a section column is inside the window of its level: a square ring around the player
   * (before there is a view, everything is). {@code slack} widens it on both edges.
   */
  private static boolean inRange(int level, int sx, int sz, int slack) {
    if (!viewValid) return true;
    int d = Math.max(Math.abs(sx - viewX[level]), Math.abs(sz - viewZ[level]));
    if (d > viewOuter[level] + slack) return false;
    int inner = Lod.inner(level);
    return inner <= 0 || d >= inner - slack;
  }

  // --- State transitions -----------------------------------------------------------------------

  /** Content of a section is new or changed: queue it, or park it if it is outside the view. */
  private static void track(long key) {
    int level = SectionKey.level(key);

    // Resident sections get one section of slack so they do not flicker at the edge.
    boolean resident = table.owns(cellIndex(key), key);
    if (inRange(level, SectionKey.x(key), SectionKey.z(key), resident ? 1 : 0)) {
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
    if (state == SectionState.LOADING) loading.add(key);
    else loading.remove(key);
  }

  /** The chunk is gone: stop tracking the section and free whatever it held. */
  private static void forget(long key) {
    states.remove(key);
    waiting.remove(key);
    starved.remove(key);
    loading.remove(key);
    release(key);
  }

  /** Frees the slot of a section, if it owns one, and queues the cell for upload. */
  private static void release(long key) {
    int cell = cellIndex(key);
    if (table.release(key, cell)) dirtyCells.add(cell);
  }

  /**
   * Re-evaluates every tracked section against the view after it moved: sections that left it are
   * parked, parked sections that entered it are queued (nearest first), sections whose column
   * vanished without a notification are forgotten, and enumerated sources are asked for the
   * sections that are new in view.
   */
  private static void sweepView() {
    List<Long> gone = new ArrayList<>();
    List<Long> leaving = new ArrayList<>();
    List<Long> entering = new ArrayList<>();

    for (Map.Entry<Long, SectionState> e : states.entrySet()) {
      long key = e.getKey();
      int lvl = SectionKey.level(key);
      int x = SectionKey.x(key);
      int z = SectionKey.z(key);
      if (!sources[lvl].has(lvl, x, z)) {
        gone.add(key);
      } else if (e.getValue() == SectionState.OUT_OF_RANGE) {
        if (inRange(lvl, x, z, 0)) entering.add(key);
        else if (sources[lvl].enumerated() && !inRange(lvl, x, z, 4)) gone.add(key); // will be asked for again
      } else if (!inRange(lvl, x, z, 1)) {
        leaving.add(key);
      }
    }

    for (long key : gone) forget(key);
    for (long key : leaving) {
      release(key);
      setState(key, SectionState.OUT_OF_RANGE);
    }
    entering.sort(Comparator.comparingLong(VoxelWorld::distanceSq));
    for (long key : entering) setState(key, SectionState.WAITING);

    // Enumerated sources can produce any section, so track the ones that are in view but unseen.
    for (int lvl = 0; lvl < Lod.LEVELS; lvl++) {
      if (!sources[lvl].enumerated()) continue;
      for (int sx = viewX[lvl] - viewOuter[lvl]; sx <= viewX[lvl] + viewOuter[lvl]; sx++) {
        for (int sz = viewZ[lvl] - viewOuter[lvl]; sz <= viewZ[lvl] + viewOuter[lvl]; sz++) {
          if (!inRange(lvl, sx, sz, 0)) continue;
          for (int sy = levelMin[lvl]; sy < levelMin[lvl] + levelRows[lvl]; sy++) {
            long key = SectionKey.pack(lvl, sx, sy, sz);
            if (!states.containsKey(key)) track(key);
          }
        }
      }
    }

    for (VoxelSource source : distinctSources()) source.trim(viewX[0], viewZ[0], viewOuter[0] + 1);
    orderLen = 0;
    orderPos = 0;
  }

  /** Squared distance in chunks from the player to the center of a section. */
  private static long distanceSq(long key) {
    int level = SectionKey.level(key);
    long dx = ((long) SectionKey.x(key) << level) + ((1L << level) >> 1) - viewX[0];
    long dz = ((long) SectionKey.z(key) << level) + ((1L << level) >> 1) - viewZ[0];
    return dx * dx + dz * dz;
  }

  /** Gives starved sections another chance, as many as there are free slots. */
  private static void requeueStarved() {
    if (table.freeCount() == 0 || starved.isEmpty()) return;
    List<Long> retry = new ArrayList<>();
    for (long key : starved) {
      retry.add(key);
      if (retry.size() >= table.freeCount()) break;
    }
    for (long key : retry) setState(key, SectionState.WAITING);
    warnedFull = false;
  }

  /** Switches to the current client level if it changed. Returns false when there is no level. */
  static boolean trackLevel() {
    ClientLevel current = Minecraft.getInstance().level;
    if (current == null) return false;
    if (current != level) switchLevel(current);
    return true;
  }

  private static void switchLevel(ClientLevel l) {
    level = l;
    minSection = l.getMinSection();
    sectionCount = l.getSectionsCount();

    SectionLayout layout = new SectionLayout(minSection, sectionCount);
    System.arraycopy(layout.levelMin, 0, levelMin, 0, Lod.LEVELS);
    System.arraycopy(layout.levelRows, 0, levelRows, 0, Lod.LEVELS);
    System.arraycopy(layout.levelRow0, 0, levelRow0, 0, Lod.LEVELS);
    totalRows = layout.totalRows;

    int cells = GRID_XZ * totalRows * GRID_XZ;
    table.resetCells(cells);

    states.clear();
    waiting.clear();
    starved.clear();
    loading.clear();
    dirtyCells.clear();
    TintMap.reset();
    for (VoxelSource source : distinctSources()) source.reset();
    orderLen = 0;
    orderPos = 0;
    viewValid = false;
    refreshPending = false;
    warnedFull = false;
    indirectionStale = true;
  }

  // --- Per-frame update (GL) -------------------------------------------------------------------

  /** Uploads pending changes. Call once per frame before drawing. */
  public static void update() {
    if (!trackLevel()) return;

    int prevUnit = GlStateManager._getActiveTexture();
    PixelStore.reset();
    try {
      if (!glReady) initGl();
      checkAtlas();
      if (refreshPending || TintMap.takeRadiusChanged()) refreshAll();
      if (indirectionStale) createIndirectionTexture();

      for (VoxelSource source : distinctSources()) source.poll();

      boolean moved = updateView();
      if (moved || sweepRequested) {
        sweepRequested = false;
        sweepView();
      }
      requeueStarved();

      // Everything below uploads to the indirection texture on this unit, and bricks via the
      // (unit independent) texture buffer binding point.
      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_INDIRECTION);
      GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, indirectionTex);

      processWaiting();
      flushCells();
      TintMap.flush(UNIT_TINT);
      HeightField.flush();
      LightMap.update();

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
      order[i] = key;
      orderScratch[i] = (viewValid ? distanceSq(key) : 0) << 32 | i;
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
      cellScratch.put(0, table.indirection(cell));
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
    if (atlasSampler == 0) {
      atlasSampler = GL33C.glGenSamplers();
      GL33C.glSamplerParameteri(atlasSampler, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST_MIPMAP_LINEAR);
      GL33C.glSamplerParameteri(atlasSampler, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
      GL33C.glSamplerParameteri(atlasSampler, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
      GL33C.glSamplerParameteri(atlasSampler, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
    }
    GL33C.glSamplerParameterf(atlasSampler, GL33C.GL_TEXTURE_MAX_LOD, atlasMaxLevel);
    GL33C.glBindSampler(UNIT_ATLAS, atlasSampler);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_TINT);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_ARRAY, TintMap.texture());
    LightMap.bind();
    HeightField.bind();
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
    for (int[] range : BlockClassifier.takeFaceRanges()) {
      FloatBuffer buf = MemoryUtil.memAllocFloat(range[1]);
      try {
        buf.put(BlockClassifier.faces(), range[0], range[1]).flip();
        GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, faceBuf);
        GL33C.glBufferSubData(GL33C.GL_TEXTURE_BUFFER, (long) range[0] * 4, buf);
      } finally {
        MemoryUtil.memFree(buf);
      }
    }
  }

  // --- Section handling ------------------------------------------------------------------------

  private static int cellIndex(long key) {
    int level = SectionKey.level(key);
    int row = levelRow0[level] + SectionKey.y(key) - levelMin[level];
    return (row * GRID_XZ + (SectionKey.z(key) & (GRID_XZ - 1))) * GRID_XZ + (SectionKey.x(key) & (GRID_XZ - 1));
  }

  /** WAITING -> LOADED, EMPTY or STARVED. The caller has already taken the key off the queue. */
  private static void rebuildSection(long key) {
    rebuilt++;
    int lvl = SectionKey.level(key);

    int solid = sources[lvl].fill(lvl, SectionKey.x(key), SectionKey.y(key), SectionKey.z(key), brickScratch);
    if (solid == VoxelSource.PENDING) {
      // Keeps any slot it owns, so the old data stays on screen until the new data arrives.
      setState(key, SectionState.LOADING);
      return;
    }
    if (solid == VoxelSource.UNAVAILABLE) {
      // Not there (any more). If it shows up, the source announces it again.
      forget(key);
      return;
    }
    if (solid == 0) {
      release(key);
      setState(key, SectionState.EMPTY);
      return;
    }

    int cell = cellIndex(key);
    int uniform = solid == BRICK_VOXELS ? uniformId() : -1;

    BrickTable.Placement placed = table.place(key, cell, uniform);
    if (placed.evicted() != BrickTable.NO_OWNER) {
      dirtyCells.add(cell);
      if (states.containsKey(placed.evicted())) setState(placed.evicted(), SectionState.OUT_OF_RANGE);
    }
    if (placed.slot() < 0) {
      if (!warnedFull) {
        LookMod.LOGGER.warn("Out of brick slots ({}); some terrain will be missing", table.capacity());
        warnedFull = true;
      }
      setState(key, SectionState.STARVED);
      return;
    }
    dirtyCells.add(cell);

    if (placed.upload()) {
      slotHash[placed.slot()] = hash(brickScratch);
      GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, brickBuf);
      brickScratch.clear();
      GL33C.glBufferSubData(GL33C.GL_TEXTURE_BUFFER, (long) placed.slot() * BRICK_BYTES, brickScratch);
    }

    setState(key, SectionState.LOADED);
  }

  /** FNV-style hash of a brick's bytes (absolute reads, native order). */
  private static long hash(ByteBuffer brick) {
    long h = 0xcbf29ce484222325L;
    for (int i = 0; i < BRICK_BYTES; i += 8) {
      h ^= brick.getLong(i);
      h *= 0x100000001b3L;
      h ^= h >>> 29;
    }
    return h;
  }

  /**
   * Checks every layer against the one before it and logs the result: the slot bookkeeping against
   * itself, the section states against the slots, the GPU's indirection texture and brick buffer
   * against the CPU mirror, and what each source produces now against what was uploaded. Slow
   * (reads GPU memory back, refills every resident section); meant to be run by hand while the
   * picture is flickering.
   *
   * @return a one-line summary
   */
  public static String verify() {
    if (!glReady || level == null) return "not ready";
    List<String> bad = new ArrayList<>();
    PixelStore.reset();

    // 1. The bookkeeping alone.
    bad.addAll(table.checkInvariants());
    bad.addAll(HeightField.checkInvariants());
    bad.addAll(HeightField.verifyGpu());
    bad.addAll(TintMap.verifyGpu(UNIT_TINT));

    // 2. States against the table: a LOADED section owns its cell, nobody else owns a cell.
    int loaded = 0;
    int ownerless = 0;
    for (Map.Entry<Long, SectionState> e : states.entrySet()) {
      long key = e.getKey();
      boolean owns = table.owns(cellIndex(key), key);
      if (e.getValue() == SectionState.LOADED) {
        loaded++;
        if (!owns) ownerless++;
      } else if (owns && e.getValue() != SectionState.WAITING && e.getValue() != SectionState.LOADING) {
        bad.add("section " + describe(key) + " is " + e.getValue() + " but owns a slot");
      }
    }
    if (ownerless > 0) bad.add(ownerless + " LOADED sections do not own their cell");

    // 3. The GPU's indirection texture against the table.
    int cells = GRID_XZ * totalRows * GRID_XZ;
    IntBuffer gpuCells = ByteBuffer.allocateDirect(cells * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    int prevUnit = GlStateManager._getActiveTexture();
    int cellMismatch = 0;
    int brickMismatch = 0;
    try {
      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_INDIRECTION);
      GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, indirectionTex);
      GL33C.glGetTexImage(GL33C.GL_TEXTURE_3D, 0, GL33C.GL_RED_INTEGER, GL33C.GL_UNSIGNED_INT, gpuCells);
      for (int cell = 0; cell < cells; cell++) {
        int x = cell % GRID_XZ;
        int z = (cell / GRID_XZ) % GRID_XZ;
        int y = cell / (GRID_XZ * GRID_XZ);
        int gpu = gpuCells.get((z * totalRows + y) * GRID_XZ + x);
        if (gpu != table.indirection(cell) && !dirtyCells.contains(cell)) {
          if (cellMismatch++ < 5) bad.add("cell " + cell + ": GPU " + gpu + ", CPU " + table.indirection(cell));
        }
      }

      // 4. The GPU's brick buffer against the hash taken when each slot was written.
      ByteBuffer back = ByteBuffer.allocateDirect(BRICK_BYTES).order(ByteOrder.nativeOrder());
      boolean[] seen = new boolean[table.capacity()];
      for (int cell = 0; cell < cells; cell++) {
        int slot = table.indirection(cell) - 1;
        if (slot < 0 || seen[slot]) continue;
        seen[slot] = true;
        back.clear();
        GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, brickBuf);
        GL33C.glGetBufferSubData(GL33C.GL_TEXTURE_BUFFER, (long) slot * BRICK_BYTES, back);
        if (hash(back) != slotHash[slot] && brickMismatch++ < 5) bad.add("slot " + slot + ": GPU bytes differ from what was written");
      }
    } finally {
      GL33C.glActiveTexture(prevUnit);
    }

    // 5. What the sources say now against what is resident. A source that is not deterministic, or
    // whose output depends on something that changed without an invalidate, shows up here.
    ByteBuffer fresh = ByteBuffer.allocateDirect(BRICK_BYTES).order(ByteOrder.nativeOrder());
    int[] changed = new int[Lod.LEVELS];
    int pending = 0;
    int gone = 0;
    for (Map.Entry<Long, SectionState> e : new ArrayList<>(states.entrySet())) {
      long key = e.getKey();
      if (e.getValue() != SectionState.LOADED) continue;
      int lvl = SectionKey.level(key);
      int solid = sources[lvl].fill(lvl, SectionKey.x(key), SectionKey.y(key), SectionKey.z(key), fresh);
      if (solid == VoxelSource.PENDING) {
        pending++;
        continue;
      }
      int slot = table.indirection(cellIndex(key)) - 1;
      if (solid < 0 || solid == 0 || slot < 0) {
        gone++;
        continue;
      }
      boolean same;
      int uniform = table.uniformOf(slot);
      if (uniform >= 0) same = solid == BRICK_VOXELS && (fresh.getShort(0) & 0xFFFF) == uniform;
      else same = hash(fresh) == slotHash[slot];
      if (!same) {
        if (changed[lvl]++ < 3) bad.add("section " + describe(key) + " now fills differently from what was uploaded");
      }
    }

    for (String line : bad) LookMod.LOGGER.warn("verify: {}", line);
    int changedTotal = Arrays.stream(changed).sum();
    String summary =
        String.format(
            "%d problems | %d loaded | cells off %d | bricks off %d | refill differs %d %s | pending %d | gone %d",
            bad.size(), loaded, cellMismatch, brickMismatch, changedTotal, Arrays.toString(changed), pending, gone);
    LookMod.LOGGER.info("verify: {}", summary);
    return summary;
  }

  private static String describe(long key) {
    return "L" + SectionKey.level(key) + " (" + SectionKey.x(key) + ", " + SectionKey.y(key) + ", " + SectionKey.z(key) + ")";
  }

  /** The voxel id {@link #brickScratch} is entirely filled with, or -1 if it is mixed. */
  private static int uniformId() {
    short first = brickScratch.getShort(0);
    for (int i = 1; i < BRICK_VOXELS; i++) {
      if (brickScratch.getShort(i << 1) != first) return -1;
    }
    return first & 0xFFFF;
  }

  // --- GL resources ----------------------------------------------------------------------------

  private static void initGl() {
    int maxTexels = GL33C.glGetInteger(GL33C.GL_MAX_TEXTURE_BUFFER_SIZE);
    int wanted = 32768;
    int capacity = Math.max(1, Math.min(wanted, maxTexels / BRICK_VOXELS));
    table.initSlots(capacity);
    slotHash = new long[capacity];

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
        GL33C.GL_TEXTURE_BUFFER, (long) BlockClassifier.FACE_FLOATS * 4, GL33C.GL_DYNAMIC_DRAW);
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

    ByteBuffer zeros = MemoryUtil.memCalloc(GRID_XZ * totalRows * GRID_XZ * 4);
    try {
      GL33C.glTexImage3D(
          GL33C.GL_TEXTURE_3D, 0, GL33C.GL_R32UI,
          GRID_XZ, totalRows, GRID_XZ, 0,
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

