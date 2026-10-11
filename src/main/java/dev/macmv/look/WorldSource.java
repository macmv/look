package dev.macmv.look;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Blocks from the chunks the client has loaded.
 *
 * <p>Level 0 is exact: sections are decoded from the raw chunk packet when we still have it (see
 * {@link WireChunk}), and read from the live chunk otherwise. Coarser levels are point sampled from
 * the loaded chunks, one block per voxel (the center of its cube), so they only exist where chunks
 * are loaded. They are rebuilt when a chunk arrives, not on every block change, and they keep their
 * last data when a chunk unloads, which is what lets distant terrain stay on screen.
 *
 * <p>Fed by mixins: {@link #ingestWire}, {@link #markDirty}, {@link #blockChanged} and
 * {@link #unloadChunk}.
 */
public final class WorldSource implements VoxelSource {
  public static final WorldSource INSTANCE = new WorldSource();

  /** Chunks the client keeps loaded past the render distance. */
  private static final int VIEW_MARGIN = 3;

  /** Raw packet data per chunk (keyed by {@link ChunkPos#toLong}), consumed as sections rebuild. */
  private final Map<Long, WireChunk> wire = new HashMap<>();

  private WorldSource() {}

  // --- Events (cheap, no GL) -------------------------------------------------------------------

  /**
   * Vanilla's "section needs a re-render" signal. It also fires for neighbor updates, which do not
   * change voxels, so it only starts tracking sections we have never seen. Real content changes
   * come in through {@link #ingestWire} and {@link #blockChanged}.
   */
  public void markDirty(int sx, int sy, int sz) {
    if (!VoxelWorld.isTracked(0, sx, sy, sz)) VoxelWorld.invalidate(0, sx, sy, sz);
  }

  /** A block changed in section (sx, sy, sz): its packet snapshot is stale and it needs a rebuild. */
  public void blockChanged(int sx, int sy, int sz) {
    wire.remove(ChunkPos.asLong(sx, sz));
    VoxelWorld.invalidate(0, sx, sy, sz);
  }

  /**
   * A chunk packet is being applied: remember its section bytes so the sections can be decoded
   * without touching the live chunk, and rebuild every section of it. Must be called before vanilla
   * consumes {@code buf}; this only reads a view.
   */
  public void ingestWire(int cx, int cz, FriendlyByteBuf buf) {
    if (!VoxelWorld.trackLevel()) return;
    TintMap.invalidate(cx, cz);
    WireChunk chunk = WireChunk.parse(new FriendlyByteBuf(buf.duplicate()), VoxelWorld.sectionCount());
    if (chunk == null) wire.remove(ChunkPos.asLong(cx, cz));
    else wire.put(ChunkPos.asLong(cx, cz), chunk);

    announceColumn(cx, cz, null);
  }

  /** The exact level goes with the chunk. Coarse levels keep what they have. */
  public void unloadChunk(ChunkPos pos) {
    wire.remove(pos.toLong());
    TintMap.invalidate(pos.x, pos.z);
    VoxelWorld.dropColumn(0, pos.x, pos.z);
  }

  /**
   * Queues the sections this source feeds that cover chunk column (cx, cz), at every level it is
   * assigned to. {@code chunk} limits it to the non-air sections of that chunk when given.
   */
  private void announceColumn(int cx, int cz, LevelChunk chunk) {
    for (int level = 0; level < Lod.LEVELS; level++) {
      if (VoxelWorld.sourceAt(level) != this) continue;

      if (chunk == null) {
        int min = VoxelWorld.levelMin(level);
        for (int sy = min; sy < min + VoxelWorld.levelRows(level); sy++) {
          VoxelWorld.invalidate(level, cx >> level, sy, cz >> level);
        }
      } else {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
          if (sections[i].hasOnlyAir()) continue;
          VoxelWorld.invalidate(level, cx >> level, chunk.getSectionYFromSectionIndex(i) >> level, cz >> level);
        }
      }
    }
  }

  // --- VoxelSource -----------------------------------------------------------------------------

  @Override
  public float reachBlocks() {
    return (Minecraft.getInstance().options.getEffectiveRenderDistance() + 1) * 16f;
  }

  @Override
  public boolean has(int level, int sx, int sz) {
    if (level != 0) return true;
    ClientLevel l = Minecraft.getInstance().level;
    return l != null && l.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false) != null;
  }

  @Override
  public void announceAll() {
    Minecraft mc = Minecraft.getInstance();
    ClientLevel l = mc.level;
    if (l == null || mc.player == null) return;

    // The whole client-side chunk range, including chunks outside the view, so they can be
    // promoted later without waiting for another event.
    int reach = Math.max(2, mc.options.getEffectiveRenderDistance()) + VIEW_MARGIN + 1;
    int cx = mc.player.blockPosition().getX() >> 4;
    int cz = mc.player.blockPosition().getZ() >> 4;

    for (int dx = -reach; dx <= reach; dx++) {
      for (int dz = -reach; dz <= reach; dz++) {
        LevelChunk chunk = l.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
        if (chunk != null) announceColumn(cx + dx, cz + dz, chunk);
      }
    }
  }

  @Override
  public void reset() {
    wire.clear();
  }

  @Override
  public void trim(int chunkX, int chunkZ, int radiusChunks) {
    wire.keySet()
        .removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - chunkX), Math.abs(ChunkPos.getZ(k) - chunkZ)) > radiusChunks);
  }

  @Override
  public int fill(int level, int sx, int sy, int sz, ByteBuffer out) {
    ClientLevel l = Minecraft.getInstance().level;
    if (l == null) return UNAVAILABLE;
    return level == 0 ? fillExact(l, sx, sy, sz, out) : fillSampled(l, level, sx, sy, sz, out);
  }

  // --- Level 0 ---------------------------------------------------------------------------------

  private int fillExact(ClientLevel l, int sx, int sy, int sz, ByteBuffer out) {
    LevelChunk chunk = l.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
    if (chunk == null) return UNAVAILABLE;
    TintMap.ensureChunk(l, chunk);

    int solid = fromWire(sx, sy, sz, out);
    if (solid >= 0) return solid;

    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
    return section.hasOnlyAir() ? 0 : fromSection(section, out);
  }

  /** Decodes from the packet data, or returns -1 if there is none (or it is unusable). */
  private int fromWire(int sx, int sy, int sz, ByteBuffer out) {
    long chunkKey = ChunkPos.asLong(sx, sz);
    WireChunk chunk = wire.get(chunkKey);
    if (chunk == null) return -1;

    int solid = chunk.take(sy - VoxelWorld.minSection(), out);
    if (chunk.isDone()) wire.remove(chunkKey);
    return solid;
  }

  /** Slow path: one block state at a time from the live chunk. */
  private static int fromSection(LevelChunkSection section, ByteBuffer out) {
    int solid = 0;
    int i = 0;
    for (int y = 0; y < 16; y++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          int id = BlockClassifier.voxelId(section.getBlockState(x, y, z));
          if (id != 0) solid++;
          out.putShort(i << 1, (short) id);
          i++;
        }
      }
    }
    return solid;
  }

  // --- Coarse levels ---------------------------------------------------------------------------

  /**
   * One block per voxel, taken from the middle of its cube. Cheap (4096 lookups however coarse the
   * level) but it aliases: anything thinner than a voxel can vanish. Fine for terrain.
   */
  private static int fillSampled(ClientLevel l, int level, int sx, int sy, int sz, ByteBuffer out) {
    int size = 1 << level;
    int half = size >> 1;
    int x0 = sx << (level + 4);
    int y0 = sy << (level + 4);
    int z0 = sz << (level + 4);
    int minY = l.getMinBuildHeight();
    int maxY = l.getMaxBuildHeight();

    int solid = 0;
    boolean anyChunk = false;
    LevelChunk chunk = null;
    int chunkX = Integer.MIN_VALUE;
    int chunkZ = Integer.MIN_VALUE;

    for (int z = 0; z < 16; z++) {
      for (int x = 0; x < 16; x++) {
        int bx = x0 + x * size + half;
        int bz = z0 + z * size + half;
        if ((bx >> 4) != chunkX || (bz >> 4) != chunkZ) {
          chunkX = bx >> 4;
          chunkZ = bz >> 4;
          chunk = l.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
          if (chunk != null) TintMap.ensureChunk(l, chunk);
        }
        if (chunk != null) anyChunk = true;

        for (int y = 0; y < 16; y++) {
          int by = y0 + y * size + half;
          int id = 0;
          if (chunk != null && by >= minY && by < maxY) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(by));
            if (!section.hasOnlyAir()) id = BlockClassifier.voxelId(section.getBlockState(bx & 15, by & 15, bz & 15));
          }
          if (id != 0) solid++;
          out.putShort(((y * 16 + z) * 16 + x) << 1, (short) id);
        }
      }
    }
    return anyChunk ? solid : UNAVAILABLE;
  }
}
