package dev.macmv.look.gl;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33C;

/**
 * A block atlas stitched from the real vanilla block textures, which are read from the Minecraft
 * jar on the test classpath. Sprites are 16x16 and laid out on a grid; the mip chain is a plain box
 * filter. It stands in for the atlas vanilla stitches at startup, which the mod samples.
 */
final class TestAtlas {
  static final int TILE = 16;
  static final int GRID = 8;
  static final int SIZE = TILE * GRID;
  /** Vanilla's default mipmap levels; 16 px sprites reach one texel at level 4. */
  static final int MAX_LEVEL = 4;

  private final Map<String, float[]> rects = new HashMap<>();
  private int texture;

  TestAtlas(List<String> sprites) {
    if (sprites.size() > GRID * GRID) throw new IllegalArgumentException("atlas full");
    int[] pixels = new int[SIZE * SIZE];
    for (int i = 0; i < sprites.size(); i++) {
      String name = sprites.get(i);
      BufferedImage img = load(name);
      int tx = (i % GRID) * TILE, ty = (i / GRID) * TILE;
      for (int y = 0; y < TILE; y++) {
        for (int x = 0; x < TILE; x++) pixels[(ty + y) * SIZE + tx + x] = img.getRGB(x, y);
      }
      // u0, v0, du, dv in atlas fractions
      rects.put(name, new float[] {tx / (float) SIZE, ty / (float) SIZE, TILE / (float) SIZE, TILE / (float) SIZE});
    }

    texture = GL33C.glGenTextures();
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
    int size = SIZE;
    int[] level = pixels;
    for (int l = 0; l <= MAX_LEVEL; l++) {
      ByteBuffer buf = BufferUtils.createByteBuffer(size * size * 4);
      for (int p : level) {
        buf.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
      }
      buf.flip();
      GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, l, GL33C.GL_RGBA8, size, size, 0, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, buf);
      level = halve(level, size);
      size /= 2;
    }
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAX_LEVEL, MAX_LEVEL);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST_MIPMAP_LINEAR);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
  }

  private static BufferedImage load(String name) {
    String path = "/assets/minecraft/textures/block/" + name + ".png";
    try (InputStream in = TestAtlas.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("No texture " + path + " on the classpath");
      BufferedImage img = ImageIO.read(in);
      // Animated textures are a strip of frames; the first one is what a still frame shows.
      if (img.getWidth() != TILE) throw new IllegalStateException(path + " is not " + TILE + " px wide");
      return img.getSubimage(0, 0, TILE, TILE);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read " + path, e);
    }
  }

  private static int[] halve(int[] src, int size) {
    int half = size / 2;
    int[] out = new int[half * half];
    for (int y = 0; y < half; y++) {
      for (int x = 0; x < half; x++) {
        int a = 0, r = 0, g = 0, b = 0;
        for (int k = 0; k < 4; k++) {
          int p = src[(y * 2 + (k >> 1)) * size + x * 2 + (k & 1)];
          a += p >>> 24;
          r += (p >> 16) & 255;
          g += (p >> 8) & 255;
          b += p & 255;
        }
        out[y * half + x] = (a / 4) << 24 | (r / 4) << 16 | (g / 4) << 8 | b / 4;
      }
    }
    return out;
  }

  int texture() {
    return texture;
  }

  /** {@code {u0, v0, du, dv}} of a sprite. */
  float[] rect(String name) {
    float[] r = rects.get(name);
    if (r == null) throw new IllegalArgumentException("sprite not in the atlas: " + name);
    return r;
  }
}
