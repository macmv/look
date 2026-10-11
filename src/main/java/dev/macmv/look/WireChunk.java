package dev.macmv.look;

import java.nio.ByteBuffer;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The block data of one chunk exactly as it came over the wire (the section buffer of a chunk
 * packet), kept in its palette + bit-packed form until the voxel world needs it.
 *
 * <p>Per section the wire holds: a short non-air block count, a paletted container of block state
 * ids, and a paletted container of biomes (skipped here). Decoding that straight into voxel ids
 * takes one pass over the packed longs, instead of 4096 {@code getBlockState} calls.
 *
 * <p>This is a snapshot from packet time. Block updates after that are not in it, so the owner must
 * drop it when the chunk changes (see {@link VoxelWorld#invalidateWire}).
 */
final class WireChunk {
  private static final int VOLUME = 16 * 16 * 16;
  /** Block states use a palette up to this many bits per entry, and the global registry above. */
  private static final int MAX_PALETTE_BITS = 8;
  /** Biomes use a palette up to this many bits per entry. */
  private static final int MAX_BIOME_PALETTE_BITS = 3;

  private static final class Section {
    int blockCount;
    int bits;
    /** Palette of block state ids. Null for the global palette, where values are state ids. */
    int[] palette;
    long[] data;
  }

  private final Section[] sections;
  private int remaining;

  private WireChunk(Section[] sections) {
    this.sections = sections;
    this.remaining = sections.length;
  }

  /**
   * Reads {@code sectionCount} sections from {@code buf}, which must be positioned at the start of
   * the chunk's section data. Only reads from {@code buf}, so pass a view with its own indices if
   * it is going to be consumed afterwards. Returns null if the data is not what we expect.
   */
  static WireChunk parse(FriendlyByteBuf buf, int sectionCount) {
    try {
      Section[] sections = new Section[sectionCount];
      for (int i = 0; i < sectionCount; i++) {
        Section s = new Section();
        s.blockCount = buf.readShort();

        s.bits = buf.readByte() & 0xFF;
        if (s.bits == 0) {
          s.palette = new int[] {buf.readVarInt()};
        } else if (s.bits <= MAX_PALETTE_BITS) {
          int n = buf.readVarInt();
          if (n < 1 || n > (1 << s.bits)) return null;
          s.palette = new int[n];
          for (int p = 0; p < n; p++) s.palette[p] = buf.readVarInt();
        }
        s.data = buf.readLongArray();

        skipBiomes(buf);
        sections[i] = s;
      }
      return new WireChunk(sections);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void skipBiomes(FriendlyByteBuf buf) {
    int bits = buf.readByte() & 0xFF;
    if (bits == 0) {
      buf.readVarInt();
    } else if (bits <= MAX_BIOME_PALETTE_BITS) {
      int n = buf.readVarInt();
      for (int p = 0; p < n; p++) buf.readVarInt();
    }
    int longs = buf.readVarInt();
    buf.skipBytes(longs * 8);
  }

  /** True once every section has been taken. */
  boolean isDone() {
    return remaining == 0;
  }

  /**
   * Decodes one section into {@code out} as 4096 u16 voxel ids and returns how many are non-air.
   * Returns -1 if the section was already taken or is malformed, so the caller can fall back to
   * reading the live chunk. Each section can only be taken once.
   */
  int take(int index, ByteBuffer out) {
    if (index < 0 || index >= sections.length) return -1;
    Section s = sections[index];
    if (s == null) return -1;
    sections[index] = null;
    remaining--;

    if (s.blockCount == 0) return 0;

    if (s.bits == 0) {
      int id = BlockClassifier.voxelIdOfStateId(s.palette[0]);
      if (id == 0) return 0;
      for (int i = 0; i < VOLUME; i++) out.putShort(i << 1, (short) id);
      return VOLUME;
    }

    // Entries never straddle two longs.
    int perLong = 64 / s.bits;
    if (s.data.length < (VOLUME + perLong - 1) / perLong) return -1;

    // Resolve the palette to voxel ids once, not once per voxel.
    short[] ids = null;
    if (s.palette != null) {
      ids = new short[s.palette.length];
      for (int p = 0; p < ids.length; p++) ids[p] = (short) BlockClassifier.voxelIdOfStateId(s.palette[p]);
    }

    long mask = (1L << s.bits) - 1;
    int solid = 0;
    int i = 0;
    for (int w = 0; i < VOLUME; w++) {
      long word = s.data[w];
      for (int k = 0; k < perLong && i < VOLUME; k++, i++) {
        int v = (int) (word & mask);
        word >>>= s.bits;

        short id;
        if (ids != null) {
          if (v >= ids.length) return -1;
          id = ids[v];
        } else {
          id = (short) BlockClassifier.voxelIdOfStateId(v);
        }
        if (id != 0) solid++;
        out.putShort(i << 1, id);
      }
    }
    return solid;
  }
}
