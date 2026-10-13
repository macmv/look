package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33C;

/**
 * The HDR side of Enhanced post processing (docs/enhanced.md 7.2 and 7.3): bloom and automatic
 * exposure computed from the world pass, plus the smoothed focus distance for depth of field. The
 * results are textures the resolve pass (and final post) read; nothing here draws to the screen.
 *
 * <p>A temporal stage (TAA) would run before {@link #process}, and hand it the accumulated image
 * instead of the raw world pass.
 *
 * <p>Textures are bound with raw GL on units the caller does not use (above the 12 GlStateManager
 * tracks, above the voxel data units); the caller restores the active unit and the framebuffer.
 */
public final class PostChain {
  private static final int LUMA_SIZE = 128;
  private static final int LUMA_TOP_LEVEL = 7;
  private static final int MAX_BLOOM_LEVELS = 6;

  private static int base = -1;

  private static int bloomDownProgram;
  private static int bloomUpProgram;
  private static int lumaProgram;
  private static int exposureProgram;
  private static int focusProgram;

  private static final int[] bloomFbo = new int[MAX_BLOOM_LEVELS];
  private static final int[] bloomTex = new int[MAX_BLOOM_LEVELS];
  private static final int[] bloomW = new int[MAX_BLOOM_LEVELS];
  private static final int[] bloomH = new int[MAX_BLOOM_LEVELS];
  private static int bloomLevels;
  private static int bloomForW;
  private static int bloomForH;

  private static int lumaFbo;
  private static int lumaTex;

  private static final PingPong exposure = new PingPong();
  private static final PingPong focus = new PingPong();

  private static long lastExposureNanos;
  private static long lastFocusNanos;

  private PostChain() {}

  /** One 1x1 float value that is updated from its previous value every frame. */
  private static final class PingPong {
    final int[] fbo = new int[2];
    final int[] tex = new int[2];
    int cur;
    boolean reset = true;

    void create() {
      if (fbo[0] != 0) return;
      for (int i = 0; i < 2; i++) {
        tex[i] = texture(GL33C.GL_R32F, GL33C.GL_RED, GL33C.GL_FLOAT, 1, 1, GL33C.GL_NEAREST);
        fbo[i] = framebuffer(tex[i]);
      }
    }
  }

  /** Texture unit for the post chain's own inputs: three consecutive units above everything else. */
  private static int unit() {
    if (base < 0) {
      int max = GL33C.glGetInteger(GL33C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
      base = Math.min(28, max - 3);
    }
    return base;
  }

  /** The bloom result (half resolution, linear filtered), or 0 when bloom is off. Valid after {@link #process}. */
  public static int bloomTexture() {
    return EnhancedSettings.bloom && bloomLevels > 0 ? bloomTex[0] : 0;
  }

  /** The 1x1 exposure texture (red channel is the multiplier), or 0 when auto-exposure is off. */
  public static int exposureTexture() {
    return EnhancedSettings.autoExposure && exposure.fbo[0] != 0 ? exposure.tex[exposure.cur] : 0;
  }

  /** Drops temporal state: the next frame jumps straight to its exposure and focus. */
  public static void resetHistory() {
    exposure.reset = true;
    focus.reset = true;
  }

  /**
   * Computes bloom and exposure from the HDR image {@code color} ({@code w} x {@code h}). Leaves the
   * bound framebuffer and viewport as they were.
   */
  public static void process(int color, int w, int h) {
    boolean doBloom = EnhancedSettings.bloom;
    boolean doExposure = EnhancedSettings.autoExposure;
    if (!doBloom && !doExposure) return;

    int prevFbo = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
    IntBuffer vp = BufferUtils.createIntBuffer(4);
    GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, vp);

    RenderSystem.disableBlend();
    RenderSystem.disableDepthTest();
    RenderSystem.depthMask(false);
    RenderSystem.disableCull();
    GlStateManager._colorMask(true, true, true, true);
    GlStateManager._glBindVertexArray(VoxelRenderer.vao());

    if (doBloom) bloom(color, w, h);
    if (doExposure) exposure(color);

    GlStateManager._glBindVertexArray(0);
    GlStateManager._glUseProgram(0);
    GlStateManager._glBindFramebuffer(GL33C.GL_FRAMEBUFFER, prevFbo);
    GlStateManager._viewport(vp.get(0), vp.get(1), vp.get(2), vp.get(3));
    RenderSystem.depthMask(true);
  }

  private static void bloom(int color, int w, int h) {
    if (bloomDownProgram == 0) {
      bloomDownProgram = link("bloom_down.fsh");
      bloomUpProgram = link("bloom_up.fsh");
    }
    if (w != bloomForW || h != bloomForH) createBloom(w, h);

    int u = unit();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + u);

    GlStateManager._glUseProgram(bloomDownProgram);
    GL33C.glUniform1i(uniform(bloomDownProgram, "uSrc"), u);
    GL33C.glUniform1f(uniform(bloomDownProgram, "uThreshold"), EnhancedSettings.bloomThreshold);
    GL33C.glUniform1f(uniform(bloomDownProgram, "uKnee"), Math.max(EnhancedSettings.bloomKnee, 1e-3f));
    int srcW = w, srcH = h;
    int src = color;
    for (int i = 0; i < bloomLevels; i++) {
      GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, src);
      GL33C.glUniform2f(uniform(bloomDownProgram, "uTexel"), 1f / srcW, 1f / srcH);
      GL33C.glUniform1i(uniform(bloomDownProgram, "uFirst"), i == 0 ? 1 : 0);
      target(bloomFbo[i], bloomW[i], bloomH[i]);
      GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
      src = bloomTex[i];
      srcW = bloomW[i];
      srcH = bloomH[i];
    }

    RenderSystem.enableBlend();
    RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
    GlStateManager._glUseProgram(bloomUpProgram);
    GL33C.glUniform1i(uniform(bloomUpProgram, "uSrc"), u);
    GL33C.glUniform1f(uniform(bloomUpProgram, "uRadius"), EnhancedSettings.bloomRadius);
    for (int i = bloomLevels - 2; i >= 0; i--) {
      GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, bloomTex[i + 1]);
      GL33C.glUniform2f(uniform(bloomUpProgram, "uTexel"), 1f / bloomW[i + 1], 1f / bloomH[i + 1]);
      target(bloomFbo[i], bloomW[i], bloomH[i]);
      GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
    }
    RenderSystem.disableBlend();
  }

  private static void createBloom(int w, int h) {
    for (int i = 0; i < MAX_BLOOM_LEVELS; i++) {
      if (bloomFbo[i] != 0) {
        GlStateManager._glDeleteFramebuffers(bloomFbo[i]);
        GL33C.glDeleteTextures(bloomTex[i]);
        bloomFbo[i] = 0;
      }
    }
    bloomForW = w;
    bloomForH = h;
    bloomLevels = 0;
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit());
    int lw = w, lh = h;
    for (int i = 0; i < MAX_BLOOM_LEVELS; i++) {
      lw = Math.max(1, lw / 2);
      lh = Math.max(1, lh / 2);
      bloomW[i] = lw;
      bloomH[i] = lh;
      bloomTex[i] = texture(GL33C.GL_RGBA16F, GL33C.GL_RGBA, GL33C.GL_FLOAT, lw, lh, GL33C.GL_LINEAR);
      bloomFbo[i] = framebuffer(bloomTex[i]);
      bloomLevels++;
      if (lw <= 4 || lh <= 4) break;
    }
  }

  private static void exposure(int color) {
    if (lumaProgram == 0) {
      lumaProgram = link("luma.fsh");
      exposureProgram = link("exposure.fsh");
    }
    if (lumaFbo == 0) {
      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit());
      lumaTex = texture(GL33C.GL_RG16F, GL33C.GL_RG, GL33C.GL_FLOAT, LUMA_SIZE, LUMA_SIZE, GL33C.GL_NEAREST);
      GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR_MIPMAP_NEAREST);
      lumaFbo = framebuffer(lumaTex);
    }
    exposure.create();

    int u = unit();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + u);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, color);
    GlStateManager._glUseProgram(lumaProgram);
    GL33C.glUniform1i(uniform(lumaProgram, "uColor"), u);
    GL33C.glUniform2i(uniform(lumaProgram, "uOutSize"), LUMA_SIZE, LUMA_SIZE);
    target(lumaFbo, LUMA_SIZE, LUMA_SIZE);
    GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);

    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, lumaTex);
    GL33C.glGenerateMipmap(GL33C.GL_TEXTURE_2D);

    long now = System.nanoTime();
    float dt = Math.min((now - lastExposureNanos) * 1e-9f, 0.25f);
    if (now - lastExposureNanos > 2_000_000_000L) exposure.reset = true;
    lastExposureNanos = now;

    int next = 1 - exposure.cur;
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + u + 1);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, exposure.tex[exposure.cur]);
    GlStateManager._glUseProgram(exposureProgram);
    GL33C.glUniform1i(uniform(exposureProgram, "uLum"), u);
    GL33C.glUniform1i(uniform(exposureProgram, "uPrev"), u + 1);
    GL33C.glUniform1f(uniform(exposureProgram, "uTopLevel"), LUMA_TOP_LEVEL);
    GL33C.glUniform1f(uniform(exposureProgram, "uDt"), dt);
    GL33C.glUniform1f(uniform(exposureProgram, "uKey"), EnhancedSettings.exposureKey);
    GL33C.glUniform1f(uniform(exposureProgram, "uMin"), EnhancedSettings.exposureMin);
    GL33C.glUniform1f(uniform(exposureProgram, "uMax"), EnhancedSettings.exposureMax);
    GL33C.glUniform1f(uniform(exposureProgram, "uUp"), EnhancedSettings.exposureUpRate);
    GL33C.glUniform1f(uniform(exposureProgram, "uDown"), EnhancedSettings.exposureDownRate);
    GL33C.glUniform1i(uniform(exposureProgram, "uReset"), exposure.reset ? 1 : 0);
    target(exposure.fbo[next], 1, 1);
    GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
    exposure.cur = next;
    exposure.reset = false;
  }

  /**
   * Updates the smoothed focus distance (blocks) from the center of {@code depth} and returns the
   * texture holding it. Same state contract as {@link #process}.
   */
  public static int updateFocus(int depth, float projA, float projB) {
    if (focusProgram == 0) focusProgram = link("focus.fsh");
    focus.create();

    int prevFbo = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
    IntBuffer vp = BufferUtils.createIntBuffer(4);
    GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, vp);

    RenderSystem.disableBlend();
    RenderSystem.disableDepthTest();
    RenderSystem.disableCull();
    GlStateManager._glBindVertexArray(VoxelRenderer.vao());

    long now = System.nanoTime();
    float dt = Math.min((now - lastFocusNanos) * 1e-9f, 0.25f);
    if (now - lastFocusNanos > 2_000_000_000L) focus.reset = true;
    lastFocusNanos = now;

    int u = unit();
    int next = 1 - focus.cur;
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + u);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, depth);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + u + 1);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, focus.tex[focus.cur]);
    GlStateManager._glUseProgram(focusProgram);
    GL33C.glUniform1i(uniform(focusProgram, "uDepth"), u);
    GL33C.glUniform1i(uniform(focusProgram, "uPrev"), u + 1);
    GL33C.glUniform1f(uniform(focusProgram, "uA"), projA);
    GL33C.glUniform1f(uniform(focusProgram, "uB"), projB);
    GL33C.glUniform1f(uniform(focusProgram, "uDt"), dt);
    GL33C.glUniform1i(uniform(focusProgram, "uReset"), focus.reset ? 1 : 0);
    target(focus.fbo[next], 1, 1);
    GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
    focus.cur = next;
    focus.reset = false;

    GlStateManager._glBindVertexArray(0);
    GlStateManager._glUseProgram(0);
    GlStateManager._glBindFramebuffer(GL33C.GL_FRAMEBUFFER, prevFbo);
    GlStateManager._viewport(vp.get(0), vp.get(1), vp.get(2), vp.get(3));
    return focus.tex[focus.cur];
  }

  // --- GL helpers, shared with FinalPost -----------------------------------------------------------

  /** First of the three consecutive texture units the post passes may use. */
  static int firstUnit() {
    return unit();
  }

  static void target(int fbo, int w, int h) {
    GlStateManager._glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
    GlStateManager._viewport(0, 0, w, h);
  }

  private static final java.util.HashMap<Long, Integer> LOCATIONS = new java.util.HashMap<>();

  static int uniform(int program, String name) {
    return LOCATIONS.computeIfAbsent(((long) program << 32) ^ name.hashCode(), k -> GL33C.glGetUniformLocation(program, name));
  }

  /** A clamped texture of the given size, bound on the active unit. */
  static int texture(int internalFormat, int format, int type, int w, int h, int filter) {
    int tex = GL33C.glGenTextures();
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, tex);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, filter);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, filter);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, 0L);
    return tex;
  }

  /** A framebuffer with {@code tex} as its only color attachment. Leaves it bound. */
  static int framebuffer(int tex) {
    int fbo = GlStateManager.glGenFramebuffers();
    GlStateManager._glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
    GlStateManager._glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, tex, 0);
    int status = GlStateManager.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER);
    if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("Post framebuffer incomplete: 0x" + Integer.toHexString(status));
    }
    return fbo;
  }

  /** A program from the shared fullscreen triangle vertex shader and the named fragment shader. */
  static int link(String fragment) {
    int vs = VoxelRenderer.compile(GL33C.GL_VERTEX_SHADER, "voxel.vsh", "");
    int fs = VoxelRenderer.compile(GL33C.GL_FRAGMENT_SHADER, fragment, "");
    int id = GL33C.glCreateProgram();
    GL33C.glAttachShader(id, vs);
    GL33C.glAttachShader(id, fs);
    GL33C.glLinkProgram(id);
    if (GL33C.glGetProgrami(id, GL33C.GL_LINK_STATUS) == 0) {
      throw new IllegalStateException("Failed to link " + fragment + ": " + GL33C.glGetProgramInfoLog(id));
    }
    GL33C.glDeleteShader(vs);
    GL33C.glDeleteShader(fs);
    return id;
  }
}
