package dev.macmv.look;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * JNI bindings to the Rust crate in {@code native/}. Keep calls coarse (one per brick or batch)
 * and pass data through direct buffers.
 */
public final class NativeLib {
  private static boolean loaded;

  private NativeLib() {}

  /** Extracts the bundled library for this platform to a temp directory and loads it. */
  public static synchronized void load() {
    if (loaded) return;

    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

    String osId = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
    String archId = (arch.equals("aarch64") || arch.equals("arm64")) ? "aarch64" : "x86_64";
    String file = osId.equals("windows") ? "look_native.dll"
        : osId.equals("macos") ? "liblook_native.dylib" : "liblook_native.so";

    String resource = "/natives/" + osId + "-" + archId + "/" + file;

    try (InputStream in = NativeLib.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("No bundled native library for " + osId + "-" + archId + " (" + resource + ")");
      }

      // A fresh directory per launch, so two copies of the jar (or an old running game) never collide.
      Path dir = Files.createTempDirectory("look-native");
      Path target = dir.resolve(file);
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      target.toFile().deleteOnExit();
      dir.toFile().deleteOnExit();

      System.load(target.toAbsolutePath().toString());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to extract native library", e);
    }

    loaded = true;
  }

  /** Allocates a direct, native-order buffer, as the native side expects. */
  public static ByteBuffer allocate(int bytes) {
    return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
  }

  public static native String version();

  /**
   * Builds 4x4x4 occupancy masks for a 16^3 brick.
   *
   * @param ids 4096 u16 block ids, native order, direct buffer (8192 bytes)
   * @param out 64 u64 masks, native order, direct buffer (512 bytes)
   */
  public static native void buildOccupancy(ByteBuffer ids, ByteBuffer out);

  // --- Predicted terrain (native/src/engine.rs) ----------------------------------------------
  //
  // An engine samples the world's noise generator on a pool of native threads. It is described by
  // the JSON from TerrainExport and the seed, asked for tiles (one section column of a pyramid
  // level), and builds bricks out of the finished ones. All calls come from the render thread.

  /**
   * Starts an engine and returns its handle. Throws if the config cannot be used (an unsupported
   * density function, for example).
   *
   * @param wantSections tiles farther than this from the camera, in sections of their level, are dropped
   * @param threads worker threads, or 0 for the number of cores minus two
   */
  public static native long terrainCreate(long seed, byte[] config, int wantSections, int threads);

  /** Stops the workers and frees the engine; the handle is dead afterwards. */
  public static native void terrainDestroy(long handle);

  /** Voxel ids for bricks: per surface kind the top and filler id, then stone and water (14 ints). */
  public static native void terrainSetIds(long handle, int[] ids);

  /** Camera position in blocks, to work on the nearest tiles first. */
  public static native void terrainCamera(long handle, int x, int z);

  /** Queues a tile unless it is queued or done. Returns whether it was newly queued. */
  public static native boolean terrainRequest(long handle, int level, int sx, int sz);

  /** Writes up to {@code out.capacity() / 8} keys of finished tiles (a {@link SectionKey} with y = 0, native order) and returns how many. */
  public static native int terrainPoll(long handle, ByteBuffer out);

  /**
   * Builds one brick (4096 u16 voxel ids, native order, direct) of a finished tile. Returns how
   * many voxels are not air, or -1 if the tile is not finished.
   */
  public static native int terrainFetch(long handle, int level, int sx, int sy, int sz, ByteBuffer out);

  /**
   * Writes the grass colors of a finished tile as RGBA texels (row major, direct buffer of at least
   * 256 bytes) and returns the texels per side, or -1 if the tile is not finished.
   */
  public static native int terrainFarTint(long handle, int level, int sx, int sz, ByteBuffer out);

  /**
   * Queues a tile of the far heightfield (see {@link HeightField}): the same computation as a voxel
   * tile, but its key comes back from {@link #terrainPoll} with the sign bit set, and it is forgotten
   * once taken. Returns whether it was newly queued.
   */
  public static native boolean terrainHeightfieldRequest(long handle, int level, int sx, int sz);

  /**
   * Takes a finished heightfield tile: writes 256 native-endian i16 heights, then 256 RGBA texels
   * (grass color, then the kind of the column) into {@code out} (direct, at least 1536 bytes). Returns
   * false if the tile is not finished.
   */
  public static native boolean terrainHeightfieldTake(long handle, int level, int sx, int sz, ByteBuffer out);

  /** Fills {@code out} with {tiles queued or running, tiles kept, tiles computed so far}. */
  public static native void terrainStats(long handle, long[] out);
}
