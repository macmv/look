package dev.macmv.look.gl;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.macmv.look.EnhancedTarget;
import dev.macmv.look.ShadowPass;
import dev.macmv.look.VoxelRenderer;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;

/**
 * A hidden GLFW window with a 3.3 core context and an offscreen framebuffer to draw into, with
 * no Minecraft instance. Needs a display: on a machine without one run under {@code xvfb-run}
 * (Mesa's llvmpipe gives software GL 4.x there).
 */
final class GlContext implements AutoCloseable {
  /** Size of the framebuffer; a render may use any part of it. */
  final int width;
  final int height;
  private final long window;
  private final int fbo;
  private final int colorTex;
  private final int depthTex;

  private GlContext(long window, int width, int height) {
    this.window = window;
    this.width = width;
    this.height = height;

    colorTex = GL33C.glGenTextures();
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, colorTex);
    GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, 0L);
    depthTex = GL33C.glGenTextures();
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, depthTex);
    GL33C.glTexImage2D(
        GL33C.GL_TEXTURE_2D, 0, GL33C.GL_DEPTH_COMPONENT32F, width, height, 0, GL33C.GL_DEPTH_COMPONENT, GL33C.GL_FLOAT, 0L);

    fbo = GL33C.glGenFramebuffers();
    GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
    GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, colorTex, 0);
    GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT, GL33C.GL_TEXTURE_2D, depthTex, 0);
    int status = GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER);
    if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("Test framebuffer incomplete: 0x" + Integer.toHexString(status));
    }
  }

  /** Returns null (with the reason in {@code why[0]}) when there is no way to get a context. */
  static GlContext tryCreate(int width, int height, String[] why) {
    if (!GLFW.glfwInit()) {
      why[0] = "glfwInit failed (no display? run under xvfb-run)";
      return null;
    }
    GLFW.glfwDefaultWindowHints();
    GLFW.glfwWindowHint(GLFW_VISIBLE, 0);
    GLFW.glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    GLFW.glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    GLFW.glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    GLFW.glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
    long window = GLFW.glfwCreateWindow(width, height, "look-test", 0L, 0L);
    if (window == 0L) {
      why[0] = "could not create a GL 3.3 core context";
      GLFW.glfwTerminate();
      return null;
    }
    GLFW.glfwMakeContextCurrent(window);
    GL.createCapabilities();

    // GlStateManager asserts it runs on the render thread; this thread is it.
    if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
    return new GlContext(window, width, height);
  }

  String describe() {
    return GL33C.glGetString(GL33C.GL_RENDERER) + " / GL " + GL33C.glGetString(GL33C.GL_VERSION);
  }

  /** Clears to {@code sky}, runs the raycast pass and reads the result back (top row first). */
  BufferedImage render(VoxelRenderer.Frame frame, TestScene scene, float[] sky) {
    return render(frame, scene, sky, width, height);
  }

  /** Same, at {@code w} x {@code h} pixels, which must fit in the framebuffer. */
  BufferedImage render(VoxelRenderer.Frame frame, TestScene scene, float[] sky, int w, int h) {
    return render(frame, scene, sky, w, h, false);
  }

  /** With {@code shadows}, an Enhanced frame gets the sun shadow map and sun lighting, like the overworld. */
  BufferedImage render(VoxelRenderer.Frame frame, TestScene scene, float[] sky, int w, int h, boolean shadows) {
    if (w > width || h > height) throw new IllegalArgumentException("larger than the framebuffer");
    GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
    GL33C.glViewport(0, 0, w, h);
    GL33C.glColorMask(true, true, true, true);
    GL33C.glDepthMask(true);
    GL33C.glClearColor(sky[0], sky[1], sky[2], 1f);
    GL33C.glClearDepth(1.0);
    GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT | GL33C.GL_DEPTH_BUFFER_BIT);

    scene.bind();
    ShadowPass.render(frame, shadows);
    if (frame.atmosphere() == null) {
      VoxelRenderer.draw(frame);
    } else {
      EnhancedTarget.begin(w, h);
      VoxelRenderer.draw(frame);
      GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
      GL33C.glViewport(0, 0, w, h);
      EnhancedTarget.resolve();
    }
    GL33C.glFinish();

    int err = GL33C.glGetError();
    if (err != 0) throw new IllegalStateException("GL error after draw: 0x" + Integer.toHexString(err));

    ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
    GL33C.glReadPixels(0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, px);

    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int i = ((h - 1 - y) * w + x) * 4;
        int r = px.get(i) & 255, g = px.get(i + 1) & 255, b = px.get(i + 2) & 255;
        img.setRGB(x, y, r << 16 | g << 8 | b);
      }
    }
    return img;
  }

  @Override
  public void close() {
    GLFW.glfwDestroyWindow(window);
    GLFW.glfwTerminate();
  }
}
