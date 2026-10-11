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
}
