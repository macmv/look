package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.function.ToIntFunction;
import org.lwjgl.opengl.GL33C;

/**
 * Enhanced mode's sun (and moon) shadow map, see {@code docs/enhanced.md} sections 3.3 to 3.5.
 *
 * <p>Two cascades of an orthographic view from the light, both centered on the camera. Each texel
 * holds the distance a ray travelled before it hit the world, found with the same {@code trace} the
 * main pass uses (voxel.fsh compiled with {@code SHADOW_PASS}, so the LOD pyramid and the heightfield
 * are shadow casters too). The maps are rebuilt every frame; their centers are snapped to whole
 * texels along the light's right and up axes so the edges do not crawl when the camera moves.
 *
 * <h2>Using the map from a shader</h2>
 * Every Enhanced draw that wants shadows calls {@link #setUniforms} on its program, and in GLSL
 * (voxel.fsh, {@code ENHANCED}, not {@code SHADOW_PASS}) uses
 * <ul>
 *   <li>{@code float sunVisibility(vec3 pos, vec3 normal)}: filtered, 0 shadowed to 1 lit.
 *   <li>{@code float sunVisibilityFast(vec3 pos)}: one tap, no filtering or normal offset, for
 *       things that sample many times per pixel (volumetric light).
 * </ul>
 * {@code pos} is relative to {@code uCamBlock}, the space the ray {@code o + d * t} is in. The raw
 * data is {@code uShadowMap0} (near) and {@code uShadowMap1} (far): R32F, nearest filtered, the ray
 * distance from the map's plane to the first hit, {@code 1e6} for no hit. Lookups go through
 * {@code shadowCoords(cascade, pos)}, which gives texel xy and the point's own ray distance; the point
 * is lit when the stored distance is not smaller than its own (minus a bias).
 *
 * <p>The light is the sun by day and the moon by night ({@code uShadowDir}, the unit vector toward
 * it). Past the far cascade, and where the map is not valid, lookups return the {@code fallback}
 * (the sky light of the receiver, in the three-argument overload).
 */
public final class ShadowPass {
  /** Texture units for the maps, on raw GL like the other data textures (GlStateManager tracks only 12). */
  public static final int UNIT_NEAR = 26;
  public static final int UNIT_FAR = 27;

  /** Half width in blocks of each cascade's square, and its texels per side. */
  private static final float[] half = {48f, 256f};
  private static final int[] size = {512, 1024};

  /** Share of a vanilla lit color that is direct light, for things vanilla shades (see {@link #setEntityUniforms}). */
  private static final float SUN_SHARE = 0.5f;
  private static final float MOON_SHARE = 0.12f;

  /** The light is never lower than this (y component), so the rays do not run along the ground. */
  private static final float MIN_ELEVATION = 0.06f;
  /** Rays start this many half widths from the center, toward the light. */
  private static final float RAY_START = 2f;

  private static int fbo;
  private static final int[] tex = new int[2];
  private static final int[] allocated = new int[2];

  // The state of the last render, read by setUniforms.
  private static boolean active;
  private static final float[] dir = new float[3];
  private static final float[] right = new float[3];
  private static final float[] up = new float[3];
  private static final float[] center = new float[6];
  private static final float[] ray = new float[2];
  private static float strength;
  private static float daylight;

  // Timing, same scheme as RenderStats.
  private static final int RING = 4;
  private static final int[] queries = new int[RING];
  private static final boolean[] pending = new boolean[RING];
  private static int head;
  private static boolean measuring;
  private static long gpuNanos;
  private static int gpuSamples;
  private static String stats;

  private ShadowPass() {}

  /** Changes the cascades (half width in blocks, texels per side), for tests and future settings. */
  public static void configure(float nearHalf, int nearSize, float farHalf, int farSize) {
    half[0] = nearHalf;
    half[1] = farHalf;
    size[0] = nearSize;
    size[1] = farSize;
  }

  /** Whether the last {@link #render} built a map, i.e. whether Enhanced uses sun lighting this frame. */
  static boolean active() {
    return active;
  }

  /** The last frame's timing for the HUD, or null. */
  static String statsLine() {
    return active ? stats : null;
  }

  /**
   * Builds the maps for {@code f} (an Enhanced frame) if {@code wanted}; the voxel data must be bound.
   * Leaves the framebuffer, viewport and program for the caller to set again. Must be called outside
   * a GL timer query.
   */
  public static void render(VoxelRenderer.Frame f, boolean wanted) {
    active = false;
    VoxelRenderer.Atmosphere atm = f.atmosphere();
    if (!wanted || atm == null) return;

    float[] light = atm.sunDir()[1] >= 0f ? atm.sunDir() : atm.moonDir();
    lightBasis(light);
    float weather = (1f - 0.85f * atm.rain()) * (1f - 0.5f * atm.thunder());
    strength = (atm.sunDir()[1] >= 0f ? SUN_SHARE * smoothstep(0f, 0.15f, atm.sunDir()[1]) : MOON_SHARE * smoothstep(0f, 0.15f, atm.moonDir()[1])) * weather;
    daylight = smoothstep(-0.1f, 0.25f, atm.sunDir()[1]) * (1f - 0.5f * atm.rain());

    if (fbo == 0) fbo = GlStateManager.glGenFramebuffers();
    for (int c = 0; c < 2; c++) allocate(c);

    double cam0 = f.camX(), cam1 = f.camY(), cam2 = f.camZ();
    double bx = Math.floor(cam0), by = Math.floor(cam1), bz = Math.floor(cam2);

    if (queries[0] == 0) {
      for (int i = 0; i < RING; i++) queries[i] = GL33C.glGenQueries();
    }
    collect();
    measuring = !pending[head];
    if (measuring) GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, queries[head]);

    VoxelRenderer.Program p = VoxelRenderer.shadowProgram();
    GlStateManager._glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
    RenderSystem.disableDepthTest();
    RenderSystem.disableBlend();
    RenderSystem.disableCull();
    GlStateManager._colorMask(true, true, true, true);
    GlStateManager._glUseProgram(p.id);
    GlStateManager._glBindVertexArray(VoxelRenderer.vao());

    for (int c = 0; c < 2; c++) {
      // Snap the center to whole texels in the light's right / up axes. Along the light it does not
      // matter where the plane is, so that follows the camera exactly.
      double texel = 2.0 * half[c] / size[c];
      double u = Math.floor((cam0 * right[0] + cam1 * right[1] + cam2 * right[2]) / texel) * texel;
      double v = Math.floor((cam0 * up[0] + cam1 * up[1] + cam2 * up[2]) / texel) * texel;
      double l = cam0 * dir[0] + cam1 * dir[1] + cam2 * dir[2];
      center[c * 3] = (float) (right[0] * u + up[0] * v + dir[0] * l - bx);
      center[c * 3 + 1] = (float) (right[1] * u + up[1] * v + dir[1] * l - by);
      center[c * 3 + 2] = (float) (right[2] * u + up[2] * v + dir[2] * l - bz);
      ray[c] = RAY_START * half[c];

      GlStateManager._glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, tex[c], 0);
      GlStateManager._viewport(0, 0, size[c], size[c]);

      VoxelRenderer.voxelUniforms(p, f);
      // The rays only matter up to the far side of the box, and a texel is the footprint for texture mips.
      float maxT = ray[c] + 2f * half[c];
      GL33C.glUniform1f(p.loc("uMaxDist"), maxT);
      GL33C.glUniform1f(p.loc("uPixelAngle"), (float) texel / ray[c]);
      setLightUniforms(p);
      GL33C.glUniform1i(p.loc("uShadowCascade"), c);
      GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
    }

    GlStateManager._glBindVertexArray(0);
    GlStateManager._glUseProgram(0);
    active = true;

    if (measuring) {
      GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
      pending[head] = true;
      head = (head + 1) % RING;
    }
  }

  /** Sets the uniforms and binds the maps for a program that does lighting (or the shadow pass itself). */
  static void setUniforms(VoxelRenderer.Program p) {
    GL33C.glUniform1i(p.loc("uSunLighting"), active ? 1 : 0);
    if (!active) return;

    setLightUniforms(p);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_NEAR);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, tex[0]);
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT_FAR);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, tex[1]);
    GL33C.glUniform1i(p.loc("uShadowMap0"), UNIT_NEAR);
    GL33C.glUniform1i(p.loc("uShadowMap1"), UNIT_FAR);
  }

  /**
   * Sets the shadow uniforms of a patched vanilla program (entity_shade.glsl) whose uniform locations
   * come from {@code loc}. The maps are still bound to their units from the raycast pass.
   */
  static void setEntityUniforms(ToIntFunction<String> loc) {
    setLightUniforms(loc);
    GL33C.glUniform1i(loc.applyAsInt("uShadowMap0"), UNIT_NEAR);
    GL33C.glUniform1i(loc.applyAsInt("uShadowMap1"), UNIT_FAR);
    GL33C.glUniform1f(loc.applyAsInt("LookShadowStrength"), strength);
    GL33C.glUniform1f(loc.applyAsInt("LookWaterLight"), 0.06f + 0.6f * daylight);
  }

  private static float smoothstep(float a, float b, float x) {
    float t = Math.min(Math.max((x - a) / (b - a), 0f), 1f);
    return t * t * (3f - 2f * t);
  }

  private static void setLightUniforms(VoxelRenderer.Program p) {
    setLightUniforms(p::loc);
  }

  private static void setLightUniforms(ToIntFunction<String> loc) {
    GL33C.glUniform3f(loc.applyAsInt("uShadowDir"), dir[0], dir[1], dir[2]);
    GL33C.glUniform3f(loc.applyAsInt("uShadowRight"), right[0], right[1], right[2]);
    GL33C.glUniform3f(loc.applyAsInt("uShadowUp"), up[0], up[1], up[2]);
    GL33C.glUniform3fv(loc.applyAsInt("uShadowCenter"), center);
    GL33C.glUniform1fv(loc.applyAsInt("uShadowHalf"), half);
    GL33C.glUniform1fv(loc.applyAsInt("uShadowRay"), ray);
    GL33C.glUniform1fv(loc.applyAsInt("uShadowTexels"), new float[] {size[0], size[1]});
  }

  /** Unit light direction (kept above the horizon) and the map's right and up axes. */
  private static void lightBasis(float[] light) {
    float x = light[0], y = Math.max(light[1], MIN_ELEVATION), z = light[2];
    float n = (float) Math.sqrt(x * x + y * y + z * z);
    dir[0] = x / n;
    dir[1] = y / n;
    dir[2] = z / n;

    // right = normalize(dir x (0, 0, 1)): horizontal, and never degenerate with the light above the horizon.
    float rx = dir[1], ry = -dir[0];
    float rn = (float) Math.sqrt(rx * rx + ry * ry);
    right[0] = rx / rn;
    right[1] = ry / rn;
    right[2] = 0f;
    // up = right x dir
    up[0] = right[1] * dir[2] - right[2] * dir[1];
    up[1] = right[2] * dir[0] - right[0] * dir[2];
    up[2] = right[0] * dir[1] - right[1] * dir[0];
  }

  private static void allocate(int c) {
    if (tex[c] != 0 && allocated[c] == size[c]) return;
    if (tex[c] == 0) tex[c] = GL33C.glGenTextures();
    allocated[c] = size[c];
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + (c == 0 ? UNIT_NEAR : UNIT_FAR));
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, tex[c]);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_R32F, size[c], size[c], 0, GL33C.GL_RED, GL33C.GL_FLOAT, 0L);
  }

  private static void collect() {
    for (int i = 0; i < RING; i++) {
      if (!pending[i]) continue;
      if (GL33C.glGetQueryObjecti(queries[i], GL33C.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
      gpuNanos += GL33C.glGetQueryObjectui64(queries[i], GL33C.GL_QUERY_RESULT);
      gpuSamples++;
      pending[i] = false;
    }
    if (gpuSamples >= 30) {
      double ms = gpuNanos / 1e6 / gpuSamples;
      stats = String.format("shadow map: %.2f ms (%d + %d texels)", ms, size[0] * size[0], size[1] * size[1]);
      gpuNanos = 0;
      gpuSamples = 0;
    }
  }
}
