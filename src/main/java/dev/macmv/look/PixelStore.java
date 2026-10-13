package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL33C;

/**
 * Vanilla's texture uploads (the atlas, animated sprites) set the pixel-store skip offsets and
 * leave them set. They are global GL state, so every upload and readback of ours would start
 * reading or writing at that offset, past the end of our buffers. Reset them before touching any.
 */
final class PixelStore {
  private PixelStore() {}

  /** Puts every unpack and pack parameter back to its GL default. */
  static void reset() {
    GlStateManager._pixelStore(GL33C.GL_UNPACK_ROW_LENGTH, 0);
    GlStateManager._pixelStore(GL33C.GL_UNPACK_IMAGE_HEIGHT, 0);
    GlStateManager._pixelStore(GL33C.GL_UNPACK_SKIP_PIXELS, 0);
    GlStateManager._pixelStore(GL33C.GL_UNPACK_SKIP_ROWS, 0);
    GlStateManager._pixelStore(GL33C.GL_UNPACK_SKIP_IMAGES, 0);
    GlStateManager._pixelStore(GL33C.GL_UNPACK_ALIGNMENT, 4);
    GlStateManager._pixelStore(GL33C.GL_PACK_ROW_LENGTH, 0);
    GlStateManager._pixelStore(GL33C.GL_PACK_IMAGE_HEIGHT, 0);
    GlStateManager._pixelStore(GL33C.GL_PACK_SKIP_PIXELS, 0);
    GlStateManager._pixelStore(GL33C.GL_PACK_SKIP_ROWS, 0);
    GlStateManager._pixelStore(GL33C.GL_PACK_SKIP_IMAGES, 0);
    GlStateManager._pixelStore(GL33C.GL_PACK_ALIGNMENT, 4);
  }
}
