package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.opengl.GL33C;

import java.nio.FloatBuffer;

/**
 * The Enhanced final post pass (docs/enhanced.md 9.2): LDR effects on the finished frame, vanilla's
 * entities and translucent layer included. It runs at the end of level rendering (see
 * {@code LevelRendererMixin}), copies the main framebuffer into a scratch texture and draws one
 * fullscreen pass over it with {@code post.fsh}. Every effect is a switch in {@link EnhancedSettings}.
 *
 * <p>Depth comes from the world pass ({@link EnhancedTarget}), so entities and particles are not
 * defocused or motion blurred by their own depth; they take the depth of the terrain behind them.
 */
public final class FinalPost {
  /**
   * What the pass needs to know about the camera.
   *
   * @param view camera rotation (camera-relative world to view), as given to the world pass
   * @param projection perspective projection
   * @param cam camera position, world space
   * @param prevViewProj last frame's projection * view
   * @param prevCam last frame's camera position
   * @param sunDir unit vector toward the sun, for the lens flare
   * @param fluid 0 none, 1 camera in water, 2 camera in lava
   * @param time seconds, for the distortion and grain
   * @param frame frame counter, for the grain and blur jitter
   */
  public record Params(
      Matrix4f view,
      Matrix4f projection,
      double[] cam,
      Matrix4f prevViewProj,
      double[] prevCam,
      float[] sunDir,
      int fluid,
      float time,
      int frame) {}

  private static int program;
  private static int scratchTex;
  private static int scratchW;
  private static int scratchH;

  private static Matrix4f lastViewProj;
  private static double[] lastCam;
  private static float[] sunDir = {0f, 1f, 0f};
  private static Matrix4f curView;
  private static Matrix4f curProj;
  private static double[] curCam;
  private static Matrix4f prevViewProj;
  private static double[] prevCam;
  private static int frameCounter;

  private FinalPost() {}

  /** Called by the world pass each Enhanced frame with the camera it used, to remember it for the final post. */
  static void capture(Matrix4f view, Matrix4f projection, double x, double y, double z, float[] sun) {
    prevViewProj = lastViewProj != null ? lastViewProj : new Matrix4f(projection).mul(view);
    prevCam = lastCam != null ? lastCam : new double[] {x, y, z};
    curView = new Matrix4f(view);
    curProj = new Matrix4f(projection);
    curCam = new double[] {x, y, z};
    sunDir = sun;
    lastViewProj = new Matrix4f(projection).mul(view);
    lastCam = curCam;
  }

  /** The hook at the end of level rendering. Does nothing unless the world pass drew into the HDR target this frame. */
  public static void runFrame() {
    if (!EnhancedTarget.drawn()) return;
    try {
      if (!LookState.enhanced || !LookState.voxelEnabled || curView == null) return;
      Minecraft mc = Minecraft.getInstance();
      var main = mc.getMainRenderTarget();

      int fluid = 0;
      Camera camera = mc.gameRenderer.getMainCamera();
      FogType type = camera.getFluidInCamera();
      if (type == FogType.WATER) fluid = 1;
      else if (type == FogType.LAVA) fluid = 2;

      int prevUnit = GlStateManager._getActiveTexture();
      frameCounter++;
      float time = (System.nanoTime() / 1_000_000L % 1_000_000L) / 1000f;
      run(main.frameBufferId, main.width, main.height,
          new Params(curView, curProj, curCam, prevViewProj, prevCam, sunDir, fluid, time, frameCounter));
      GL33C.glActiveTexture(prevUnit);
      main.bindWrite(true);
    } finally {
      EnhancedTarget.endFrame();
    }
  }

  /** Runs the pass over the framebuffer {@code fbo} of size {@code w} x {@code h}, using the world pass depth. */
  public static void run(int fbo, int w, int h, Params p) {
    if (!EnhancedSettings.anyFinalPost()) return;
    if (program == 0) program = PostChain.link("post.fsh");

    int u = PostChain.firstUnit();
    GlStateManager._glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + u);
    if (scratchTex == 0 || scratchW != w || scratchH != h) {
      if (scratchTex != 0) GL33C.glDeleteTextures(scratchTex);
      scratchTex = PostChain.texture(GL33C.GL_RGBA8, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, w, h, GL33C.GL_LINEAR);
      scratchW = w;
      scratchH = h;
    }
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, scratchTex);
    GL33C.glCopyTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);

    float projA = p.projection().m22();
    float projB = p.projection().m32();
    int focusTex = 0;
    if (EnhancedSettings.depthOfField) focusTex = PostChain.updateFocus(EnhancedTarget.depthTexture(), projA, projB);

    GlStateManager._glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
    GlStateManager._viewport(0, 0, w, h);
    RenderSystem.disableBlend();
    RenderSystem.disableDepthTest();
    RenderSystem.depthMask(false);
    RenderSystem.disableCull();
    GlStateManager._colorMask(true, true, true, true);

    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + u);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, scratchTex);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + u + 1);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, EnhancedTarget.depthTexture());
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + u + 2);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, focusTex);

    GlStateManager._glUseProgram(program);
    GL33C.glUniform1i(loc("uScene"), u);
    GL33C.glUniform1i(loc("uDepth"), u + 1);
    GL33C.glUniform1i(loc("uFocus"), u + 2);
    GL33C.glUniform2f(loc("uSize"), w, h);
    GL33C.glUniform1f(loc("uTime"), p.time());
    GL33C.glUniform1i(loc("uFrame"), p.frame());

    boolean fluidOn = EnhancedSettings.fluidDistortion && p.fluid() != 0;
    GL33C.glUniform1i(loc("uFluid"), fluidOn ? p.fluid() : 0);
    GL33C.glUniform1f(loc("uFluidAmp"), (p.fluid() == 2 ? 0.006f : 0.003f) * EnhancedSettings.fluidDistortionStrength);

    GL33C.glUniform1i(loc("uVignette"), EnhancedSettings.vignette ? 1 : 0);
    GL33C.glUniform1f(loc("uVignetteStrength"), EnhancedSettings.vignetteStrength);
    GL33C.glUniform1i(loc("uCA"), EnhancedSettings.chromaticAberration ? 1 : 0);
    GL33C.glUniform1f(loc("uCAStrength"), EnhancedSettings.chromaticStrength);
    GL33C.glUniform1i(loc("uGrain"), EnhancedSettings.filmGrain ? 1 : 0);
    GL33C.glUniform1f(loc("uGrainStrength"), EnhancedSettings.grainStrength);
    GL33C.glUniform1i(loc("uSharpen"), EnhancedSettings.sharpen ? 1 : 0);
    GL33C.glUniform1f(loc("uSharpenStrength"), EnhancedSettings.sharpenStrength);
    GL33C.glUniform1i(loc("uDof"), EnhancedSettings.depthOfField ? 1 : 0);
    GL33C.glUniform1f(loc("uDofRadius"), EnhancedSettings.dofRadius);
    GL33C.glUniform1f(loc("uDofStrength"), EnhancedSettings.dofStrength);
    GL33C.glUniform2f(loc("uProjAB"), projA, projB);

    boolean motion = EnhancedSettings.motionBlur;
    GL33C.glUniform1i(loc("uMotion"), motion ? 1 : 0);
    GL33C.glUniform1f(loc("uMotionStrength"), EnhancedSettings.motionBlurStrength);

    Matrix4f viewProj = new Matrix4f(p.projection()).mul(p.view());
    Matrix4f inv = new Matrix4f(viewProj).invert();
    boolean flare = EnhancedSettings.lensFlare;
    GL33C.glUniform1i(loc("uFlare"), flare ? 1 : 0);
    GL33C.glUniform1f(loc("uFlareStrength"), EnhancedSettings.lensFlareStrength);
    Vector4f sun = new Vector4f(p.sunDir()[0], p.sunDir()[1], p.sunDir()[2], 0f).mul(viewProj);
    float visible = sun.w > 1e-4f && p.sunDir()[1] > -0.02f ? 1f : 0f;
    float sx = sun.w > 1e-4f ? sun.x / sun.w * 0.5f + 0.5f : 0.5f;
    float sy = sun.w > 1e-4f ? sun.y / sun.w * 0.5f + 0.5f : 0.5f;
    GL33C.glUniform3f(loc("uSunUv"), sx, sy, visible);

    try (MemoryStack stack = MemoryStack.stackPush()) {
      FloatBuffer buf = stack.mallocFloat(16);
      inv.get(buf);
      GL33C.glUniformMatrix4fv(loc("uInvViewProj"), false, buf);
      p.prevViewProj().get(buf);
      GL33C.glUniformMatrix4fv(loc("uPrevViewProj"), false, buf);
    }
    GL33C.glUniform3f(loc("uCamDelta"),
        (float) (p.cam()[0] - p.prevCam()[0]), (float) (p.cam()[1] - p.prevCam()[1]), (float) (p.cam()[2] - p.prevCam()[2]));

    GlStateManager._glBindVertexArray(VoxelRenderer.vao());
    GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
    GlStateManager._glBindVertexArray(0);
    GlStateManager._glUseProgram(0);

    RenderSystem.depthMask(true);
    RenderSystem.enableDepthTest();
    RenderSystem.enableCull();
  }

  private static int loc(String name) {
    return PostChain.uniform(program, name);
  }
}
