package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL33C;

/**
 * The HDR target Enhanced mode draws the world into, and the resolve pass that tonemaps it into the
 * framebuffer that was bound before (see {@code docs/enhanced.md} section 1.2). Vanilla mode never
 * touches this.
 */
public final class EnhancedTarget {
  /**
   * Texture units for the resolve pass. GlStateManager only tracks the first 12 units and the voxel
   * data sits on 10 to 21 through raw GL calls, so these two also go through raw GL, on units nothing
   * else uses. The caller restores the active unit.
   */
  private static final int UNIT_COLOR = 22;
  private static final int UNIT_DEPTH = 23;
  /** Bloom and exposure results. Unbound ones are bound to texture 0, which the shader does not read. */
  private static final int UNIT_BLOOM = 24;
  private static final int UNIT_EXPOSURE = 25;

  private static int fbo;
  private static int colorTex;
  private static int depthTex;
  private static int width;
  private static int height;
  private static int resolveProgram;
  /** Whether the world pass drew into the target since the last {@link #endFrame}, for the final post. */
  private static boolean drawn;

  private EnhancedTarget() {}

  /** The HDR color texture (RGBA16F, linear filtered). */
  public static int colorTexture() {
    return colorTex;
  }

  /** The depth texture of the world pass: 1 where nothing was hit. */
  public static int depthTexture() {
    return depthTex;
  }

  public static int width() {
    return width;
  }

  public static int height() {
    return height;
  }

  /** True when the world pass has drawn since the final post last ran. */
  public static boolean drawn() {
    return drawn;
  }

  static void endFrame() {
    drawn = false;
  }

  /** Binds the target at {@code w} x {@code h}, resizing if needed, and clears it to "nothing hit". */
  public static void begin(int w, int h) {
    if (fbo == 0 || w != width || h != height) {
      create(w, h);
      PostChain.resetHistory();
    }
    drawn = true;

    GlStateManager._glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
    GlStateManager._viewport(0, 0, w, h);
    GlStateManager._colorMask(true, true, true, true);
    GlStateManager._depthMask(true);
    GlStateManager._clearColor(0f, 0f, 0f, 0f);
    GlStateManager._clearDepth(1.0);
    GlStateManager._clear(GL33C.GL_COLOR_BUFFER_BIT | GL33C.GL_DEPTH_BUFFER_BIT, false);
  }

  /** Draws the target over the currently bound framebuffer, with its depth. */
  public static void resolve() {
    if (resolveProgram == 0) resolveProgram = link();

    // Bloom and exposure first; they bind their own framebuffers and put the old one back.
    PostChain.process(colorTex, width, height);

    RenderSystem.enableBlend();
    RenderSystem.blendFuncSeparate(
        GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
        GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
    RenderSystem.disableCull();
    RenderSystem.enableDepthTest();
    RenderSystem.depthFunc(GL33C.GL_LEQUAL);
    RenderSystem.depthMask(true);

    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_COLOR);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, colorTex);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_DEPTH);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, depthTex);
    int bloom = PostChain.bloomTexture();
    int exposure = PostChain.exposureTexture();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_BLOOM);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, bloom);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_EXPOSURE);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, exposure);

    GlStateManager._glUseProgram(resolveProgram);
    GL33C.glUniform1i(loc("uColor"), UNIT_COLOR);
    GL33C.glUniform1i(loc("uDepth"), UNIT_DEPTH);
    GL33C.glUniform1i(loc("uBloom"), UNIT_BLOOM);
    GL33C.glUniform1i(loc("uExpTex"), UNIT_EXPOSURE);
    GL33C.glUniform1f(loc("uExposure"), EnhancedSettings.exposureCompensation);
    GL33C.glUniform1i(loc("uAutoExposure"), exposure != 0 ? 1 : 0);
    GL33C.glUniform1f(loc("uAutoStrength"), EnhancedSettings.exposureStrength);
    GL33C.glUniform1f(loc("uBloomStrength"), bloom != 0 ? EnhancedSettings.bloomStrength : 0f);
    GL33C.glUniform1i(loc("uTonemap"), EnhancedSettings.tonemap);
    GL33C.glUniform1i(loc("uGrading"), EnhancedSettings.grading ? 1 : 0);
    GL33C.glUniform1f(loc("uSaturation"), EnhancedSettings.saturation);
    GL33C.glUniform1f(loc("uVibrance"), EnhancedSettings.vibrance);
    GL33C.glUniform1f(loc("uContrast"), EnhancedSettings.contrast);
    GL33C.glUniform1f(loc("uBrightness"), EnhancedSettings.brightness);
    GL33C.glUniform3f(loc("uTint"), EnhancedSettings.tint[0], EnhancedSettings.tint[1], EnhancedSettings.tint[2]);

    GlStateManager._glBindVertexArray(VoxelRenderer.vao());
    GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
    GlStateManager._glBindVertexArray(0);
    GlStateManager._glUseProgram(0);
    RenderSystem.disableBlend();
  }

  private static int loc(String name) {
    return GL33C.glGetUniformLocation(resolveProgram, name);
  }

  private static void create(int w, int h) {
    if (fbo != 0) {
      GlStateManager._glDeleteFramebuffers(fbo);
      GL33C.glDeleteTextures(colorTex);
      GL33C.glDeleteTextures(depthTex);
    }
    width = w;
    height = h;

    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_COLOR);
    colorTex = texture(GL33C.GL_RGBA16F, GL33C.GL_RGBA, GL33C.GL_FLOAT);
    // Bloom samples it with bilinear filtering; the resolve pass uses texelFetch, which ignores it.
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
    depthTex = texture(GL33C.GL_DEPTH_COMPONENT32F, GL33C.GL_DEPTH_COMPONENT, GL33C.GL_FLOAT);

    fbo = GlStateManager.glGenFramebuffers();
    GlStateManager._glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
    GlStateManager._glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, colorTex, 0);
    GlStateManager._glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT, GL33C.GL_TEXTURE_2D, depthTex, 0);
    int status = GlStateManager.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER);
    if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("Enhanced framebuffer incomplete: 0x" + Integer.toHexString(status));
    }
  }

  /** A {@code width} x {@code height} nearest-filtered texture, bound on the active unit. */
  private static int texture(int internalFormat, int format, int type) {
    int tex = GL33C.glGenTextures();
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, tex);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, 0L);
    return tex;
  }

  private static int link() {
    int vs = VoxelRenderer.compile(GL33C.GL_VERTEX_SHADER, "voxel.vsh", "");
    int fs = VoxelRenderer.compile(GL33C.GL_FRAGMENT_SHADER, "resolve.fsh", "");
    int id = GL33C.glCreateProgram();
    GL33C.glAttachShader(id, vs);
    GL33C.glAttachShader(id, fs);
    GL33C.glLinkProgram(id);
    if (GL33C.glGetProgrami(id, GL33C.GL_LINK_STATUS) == 0) {
      throw new IllegalStateException("Failed to link resolve program: " + GL33C.glGetProgramInfoLog(id));
    }
    GL33C.glDeleteShader(vs);
    GL33C.glDeleteShader(fs);
    return id;
  }
}
