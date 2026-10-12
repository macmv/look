package dev.macmv.look;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    LightMap.markColumn(cx, cz);
    WireChunk chunk = WireChunk.parse(new FriendlyByteBuf(buf.duplicate()), VoxelWorld.sectionCount());
    if (chunk == null) wire.remove(ChunkPos.asLong(cx, cz));
    else wire.put(ChunkPos.asLong(cx, cz), chunk);

    announceColumn(cx, cz, null);
  }

  /** The exact level goes with the chunk. Coarse levels keep what they have. */
  public void unloadChunk(ChunkPos pos) {
    wire.remove(pos.toLong());
    TintMap.invalidate(pos.x, pos.z);
    LightMap.dropColumn(pos.x, pos.z);
    VoxelWorld.dropColumn(0, pos.x, pos.z);
  }

  /**
   * Queues the sections this source feeds that cover chunk column (cx, cz), at every level it is
   * assigned to. {@code chunk} limits it to the non-air sections of that chunk when given.
   */
  private void announceColumn(int cx, int cz, LevelChunk chunk) {
    for (int level = 0; level < Lod.LEVELS; level++) {
      if (!VoxelWorld.sourceAt(level).usesLoadedChunks()) continue;

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
  public boolean usesLoadedChunks() {
    return true;
  }

  /**
   * Whether any chunk of a coarse section's footprint could be loaded, going by how far the client
   * keeps chunks around the player. Cheap, so layered sources can skip the real lookups elsewhere.
   */
  public boolean mayHaveChunks(int level, int sx, int sz) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) return true;

    int reach = Math.max(2, mc.options.getEffectiveRenderDistance()) + VIEW_MARGIN + 1;
    int minX = sx << level;
    int minZ = sz << level;
    int span = (1 << level) - 1;
    int px = mc.player.blockPosition().getX() >> 4;
    int pz = mc.player.blockPosition().getZ() >> 4;
    int dx = Math.max(0, Math.max(minX - px, px - (minX + span)));
    int dz = Math.max(0, Math.max(minZ - pz, pz - (minZ + span)));
    return Math.max(dx, dz) <= reach;
  }

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
    if (level == 0) return fillExact(l, sx, sy, sz, out);

    int solid = sampleColumns(l, level, sx, sy, sz, out, false);
    return sampledColumns > 0 ? solid : UNAVAILABLE;
  }

  /**
   * Writes the loaded columns of a coarse section over whatever is already in {@code out}, leaving
   * columns without a loaded chunk alone. Returns how many columns were written.
   */
  int overlay(int level, int sx, int sy, int sz, ByteBuffer out) {
    ClientLevel l = Minecraft.getInstance().level;
    if (l == null || level == 0) return 0;
    sampleColumns(l, level, sx, sy, sz, out, true);
    return sampledColumns;
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

  /** Columns that had a loaded chunk in the last {@link #sampleColumns} call. */
  private static int sampledColumns;

  /**
   * Each voxel is reduced from a 2x2x2 sample of its cube by {@link LodReduce}, so grass, leaves and
   * snow on the surface win over what is under them. Sampling is cheap however coarse the level (a
   * fixed 8 blocks per voxel), but anything thinner than the sample spacing can still vanish.
   *
   * <p>Columns whose chunk is not loaded are written as air, or skipped with {@code keepMissing}.
   * Returns the number of non-air voxels written; {@link #sampledColumns} gets the loaded columns.
   */
  private static int sampleColumns(
      ClientLevel l, int level, int sx, int sy, int sz, ByteBuffer out, boolean keepMissing) {
    int size = 1 << level;
    int half = size >> 1;
    int lo = size >> 2;
    int hi = (size * 3) >> 2;
    int x0 = sx << (level + 4);
    int y0 = sy << (level + 4);
    int z0 = sz << (level + 4);

    int solid = 0;
    int columns = 0;
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    int[] samples = new int[8];
    boolean[] exposed = new boolean[8];

    for (int z = 0; z < 16; z++) {
      for (int x = 0; x < 16; x++) {
        int cx = x0 + x * size + half;
        int cz = z0 + z * size + half;
        LevelChunk center = l.getChunkSource().getChunk(cx >> 4, cz >> 4, ChunkStatus.FULL, false);
        if (center != null) {
          TintMap.ensureChunk(l, center);
          columns++;
        } else if (keepMissing) {
          continue;
        }

        for (int y = 0; y < 16; y++) {
          int id = 0;
          if (center != null) {
            int n = 0;
            for (int dy = 0; dy < 2; dy++) {
              for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                  int bx = x0 + x * size + (dx == 0 ? lo : hi);
                  int by = y0 + y * size + (dy == 0 ? lo : hi);
                  int bz = z0 + z * size + (dz == 0 ? lo : hi);
                  LevelChunk c = (bx >> 4) == (cx >> 4) && (bz >> 4) == (cz >> 4)
                      ? center
                      : l.getChunkSource().getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, false);
                  int i = (dy * 2 + dz) * 2 + dx;
                  if (c == null) {
                    samples[i] = 0;
                    continue;
                  }
                  samples[i] = idAt(c, pos.set(bx, by, bz));
                  // Lower samples are covered by the sample above; upper ones by the next block up.
                  if (dy == 1) exposed[i] = isOpen(idAt(c, pos.set(bx, by + 1, bz)));
                }
              }
            }
            for (int i = 0; i < 4; i++) exposed[i] = isOpen(samples[i + 4]);
            id = LodReduce.reduce(samples, exposed);
          }
          if (id != 0) solid++;
          out.putShort(((y * 16 + z) * 16 + x) << 1, (short) id);
        }
      }
    }
    sampledColumns = columns;
    return solid;
  }

  /** Whether nothing solid is in a sample with this id: air, or a plant. */
  private static boolean isOpen(int id) {
    return id == 0 || BlockClassifier.kind(id) == BlockClassifier.KIND_PLANT;
  }

  /** Voxel id of the block at {@code pos}, with snow layers counted as snow blocks. */
  private static int idAt(LevelChunk chunk, BlockPos pos) {
    BlockState state = chunk.getBlockState(pos);
    if (state.isAir()) return 0;
    if (state.is(Blocks.SNOW)) return BlockClassifier.voxelId(Blocks.SNOW_BLOCK.defaultBlockState());
    return BlockClassifier.voxelId(state);
  }
}
