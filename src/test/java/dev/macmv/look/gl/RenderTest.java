package dev.macmv.look.gl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.macmv.look.EnhancedSettings;
import dev.macmv.look.FinalPost;
import dev.macmv.look.Lod;
import dev.macmv.look.ShadowPass;
import dev.macmv.look.VoxelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Draws fixed scenes through the real raycast shader and compares them with checked-in images.
 * Needs a display (CI: {@code xvfb-run ./gradlew test}); without one the tests are skipped, unless
 * {@code LOOK_REQUIRE_GL} is set, in which case that is a failure. The images are Mesa llvmpipe
 * output, but the tests run on whatever GL renderer is available, including a hardware GPU. Regenerate the images with
 * {@code ./gradlew test -PupdateGolden} and look at them before committing.
 *
 * <p>Building a scene (filling bricks, generating terrain) costs far more than drawing it, so the tests
 * are grouped in nested classes by scene: each builds its scene once and draws every image of its
 * group from it. Drawing changes neither the scene nor the frames.
 */
class RenderTest {
  private static final int W = 320;
  private static final int H = 180;
  /** The far scenes are drawn larger: at 320 x 180 a texel of the far heightfield is a handful of pixels wide. */
  private static final int BIG_W = 1280;
  private static final int BIG_H = 720;
  private static final float[] SKY = {0.47f, 0.65f, 1.0f};

  private static final double FAR_X = 0.5;
  private static final double FAR_Z = 0.5;
  private static final float FAR_YAW = 35f;
  private static final float FAR_PITCH = 9f;
  private static final double TREES_X = -640.5;
  private static final double TREES_Z = -512.5;
  private static final double TREES_Y = 170.0;
  private static final float TREES_YAW = 0f;

  private static GlContext gl;
  private static TestAtlas atlas;

  @BeforeAll
  static void setUp() {
    String[] why = new String[1];
    gl = GlContext.tryCreate(BIG_W, BIG_H, why);
    if (gl == null && System.getenv("LOOK_REQUIRE_GL") != null) {
      throw new IllegalStateException("LOOK_REQUIRE_GL is set but there is no GL: " + why[0]);
    }
    assumeTrue(gl != null, () -> "no GL context: " + why[0]);
    System.out.println("Render tests on " + gl.describe());
    atlas = new TestAtlas(TestScene.SPRITES);
  }

  @AfterAll
  static void tearDown() {
    if (gl != null) gl.close();
  }

  private static BufferedImage draw(TestScene scene, double x, double y, double z, float yaw, float pitch, int debug) {
    return gl.render(scene.frame(W, H, x, y, z, yaw, pitch, 70f, debug), scene, SKY, W, H);
  }

  private static BufferedImage drawBig(TestScene scene, double x, double y, double z, float yaw, float pitch, int debug) {
    return gl.render(scene.frame(BIG_W, BIG_H, x, y, z, yaw, pitch, 70f, debug), scene, SKY, BIG_W, BIG_H);
  }


  private static double terrainFraction(BufferedImage img) {
    int sky = (int) (SKY[0] * 255 + 0.5) << 16 | (int) (SKY[1] * 255 + 0.5) << 8 | (int) (SKY[2] * 255 + 0.5);
    int n = 0;
    for (int y = 0; y < img.getHeight(); y++) {
      for (int x = 0; x < img.getWidth(); x++) {
        if ((img.getRGB(x, y) & 0xFFFFFF) != sky) n++;
      }
    }
    return n / (double) (img.getWidth() * img.getHeight());
  }

  /** Sun and moon at {@code elevDeg} above the horizon toward -x, in the plane vanilla turns the sky in. */
  private static VoxelRenderer.Atmosphere atm(float elevDeg, int phase, float rain, float thunder) {
    float a = (float) Math.toRadians(90.0 - elevDeg);
    float sx = -(float) Math.sin(a), sy = (float) Math.cos(a);
    return new VoxelRenderer.Atmosphere(new float[] {sx, sy, 0f}, new float[] {-sx, -sy, 0f}, phase, rain, thunder);
  }

  private static BufferedImage enhancedSky(
      TestScene scene, double x, double y, double z, float yaw, float pitch, VoxelRenderer.Atmosphere atm, int clouds, int w, int h) {
    int saved = dev.macmv.look.SkyState.cloudStyle;
    dev.macmv.look.SkyState.cloudStyle = clouds;
    try {
      var f = scene.frame(w, h, x, y, z, yaw, pitch, 70f, 0);
      return gl.render(scene.enhanced(f, atm), scene, SKY, w, h);
    } finally {
      dev.macmv.look.SkyState.cloudStyle = saved;
    }
  }

  // --- Enhanced post processing ---------------------------------------------------------------

  private static final float[] HILLS_CAM = {0.5f, 110.0f, 0.5f, 35f, 8f};

  /** Runs {@code body} with every post effect off, then puts the settings back. */
  private static BufferedImage withPost(Runnable setup, java.util.function.Supplier<BufferedImage> draw) {
    var saved = saveSettings();
    try {
      EnhancedSettings.neutral();
      setup.run();
      return draw.get();
    } finally {
      restoreSettings(saved);
    }
  }

  private static java.util.Map<java.lang.reflect.Field, Object> saveSettings() {
    var saved = new java.util.HashMap<java.lang.reflect.Field, Object>();
    try {
      for (var f : EnhancedSettings.class.getFields()) {
        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && !java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
          Object v = f.get(null);
          saved.put(f, v instanceof float[] a ? a.clone() : v);
        }
      }
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
    return saved;
  }

  private static void restoreSettings(java.util.Map<java.lang.reflect.Field, Object> saved) {
    try {
      for (var e : saved.entrySet()) e.getKey().set(null, e.getValue());
    } catch (IllegalAccessException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static FinalPost.Params postParams(VoxelRenderer.Frame f, int fluid, float[] sunDir, float turnDegrees) {
    Matrix4f prev = new Matrix4f(f.projection()).mul(new Matrix4f(f.view()).rotateY((float) Math.toRadians(turnDegrees)));
    double[] cam = {f.camX(), f.camY(), f.camZ()};
    return new FinalPost.Params(f.view(), f.projection(), cam, prev, cam, sunDir, fluid, 1.5f, 3);
  }


  // --- Enhanced shadows -----------------------------------------------------------------------

  /** Camera over the shadow scene, looking at the origin from +x, -z, with the sun to the side so shadows fall across the view. */
  private static BufferedImage shadowScene(TestScene scene, float[] sun, double x, double y, double z, float yaw, float pitch) {
    ShadowPass.configure(32f, 512, 64f, 512);
    var vanilla = scene.frame(SW, SH, x, y, z, yaw, pitch, 70f, 0);
    return gl.render(scene.enhanced(vanilla, sun), scene, SKY, SW, SH, true);
  }

  private static final int SW = 640;
  private static final int SH = 360;


  /** Brightest channel in the box around the leftmost glowstone block. */
  private static int brightest(BufferedImage img) {
    int best = 0;
    for (int y = 78; y < 104; y++) {
      for (int x = 56; x < 90; x++) {
        int p = img.getRGB(x, y);
        best = Math.max(best, Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255)));
      }
    }
    return best;
  }

  /** Hills with only the near levels; the camera is over the showcase blocks. */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class HillsNear {
    private TestScene scene;

    @BeforeAll
    void build() {
      scene = new TestScene(atlas, TestWorlds.HILLS, 4, 0, 96);
    }

    @AfterAll
    void close() {
      if (scene != null) scene.close();
    }

    @Test
    void blocksCloseUp() {
      assertTrue(scene.slots() > 0, "scene has no bricks");
      Golden.check("blocks_closeup", draw(scene, 4.5, 80.5, 0.5, 0f, 18f, 0));
    }

    @Test
    void blocksFromAbove() {
      Golden.check("blocks_from_above", draw(scene, 3.2, 84.0, 4.3, 25f, 55f, 0));
    }

    @Test
    void skyNoon() {
      Golden.check("sky_noon", enhancedSky(scene, 4.5, 80.5, 0.5, 90f, -10f, atm(40f, 0, 0f, 0f), 0, W, H));
    }

    @Test
    void skySunset() {
      Golden.check("sky_sunset", enhancedSky(scene, 4.5, 80.5, 0.5, 90f, 4f, atm(3f, 0, 0f, 0f), 0, W, H));
    }

    @Test
    void skyNight() {
      Golden.check("sky_night", enhancedSky(scene, 4.5, 80.5, 0.5, 270f, -35f, atm(-50f, 2, 0f, 0f), 0, BIG_W, BIG_H));
    }

    @Test
    void skyRain() {
      Golden.check("sky_rain", enhancedSky(scene, 4.5, 80.5, 0.5, 90f, -10f, atm(40f, 0, 1f, 0.5f), 0, W, H));
    }

    @Test
    void cloudsGrid() {
      Golden.check("clouds_grid", enhancedSky(scene, 4.5, 80.5, 0.5, 90f, -25f, atm(55f, 0, 0f, 0f), 1, W, H));
    }

    @Test
    void cloudsSoft() {
      Golden.check("clouds_soft", enhancedSky(scene, 4.5, 80.5, 0.5, 90f, -25f, atm(35f, 0, 0f, 0f), 2, W, H));
    }

    @Test
    void cloudsSoftStorm() {
      Golden.check("clouds_soft_storm", enhancedSky(scene, 4.5, 80.5, 0.5, 90f, -25f, atm(35f, 0, 1f, 1f), 2, W, H));
    }
  }

  /** Hills out to 700 blocks, with the whole pyramid in view, and the Enhanced post effects drawn over it. */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class HillsFar {
    private TestScene scene;

    @BeforeAll
    void build() {
      scene = new TestScene(atlas, TestWorlds.HILLS, 0, 0, 700);
    }

    @AfterAll
    void close() {
      if (scene != null) scene.close();
    }

    @Test
    void hillsToTheHorizon() {
      Golden.check("hills_horizon", draw(scene, 0.5, 110.0, 0.5, 35f, 8f, 0));
    }

    @Test
    void hillsLevelTint() {
      Golden.check("hills_levels", draw(scene, 0.5, 110.0, 0.5, 35f, 8f, 2));
    }

    /** Far terrain fades into the sky color instead of cutting off at the end of the pyramid. */
    @Test
    void fogFarField() {
      Golden.check("fog_far", enhancedSky(scene, FAR_X, 80.5, FAR_Z, FAR_YAW, FAR_PITCH, atm(40f, 0, 0f, 0f), 0, BIG_W, BIG_H));
    }

    private BufferedImage hillsPost(java.util.function.Function<VoxelRenderer.Frame, FinalPost.Params> params) {
      var vanilla = scene.frame(W, H, HILLS_CAM[0], HILLS_CAM[1], HILLS_CAM[2], HILLS_CAM[3], HILLS_CAM[4], 70f, 0);
      var frame = scene.enhanced(vanilla);
      return gl.render(frame, scene, SKY, W, H, params == null ? null : params.apply(frame));
    }

    /** Bloom with a low threshold, so the bright sunlit parts of the hills glow. */
    @Test
    void enhancedBloom() {
      BufferedImage img = withPost(() -> {
        EnhancedSettings.bloom = true;
        EnhancedSettings.bloomThreshold = 0.45f;
        EnhancedSettings.bloomKnee = 0.2f;
        EnhancedSettings.bloomStrength = 0.4f;
      }, () -> hillsPost(null));
      Golden.check("enhanced_bloom", img);
    }

    /** ACES tonemap, a darker fixed exposure and a saturation / contrast / tint grade. */
    @Test
    void enhancedTonemapAndGrade() {
      BufferedImage img = withPost(() -> {
        EnhancedSettings.tonemap = EnhancedSettings.TONEMAP_ACES;
        EnhancedSettings.exposureCompensation = 1.3f;
        EnhancedSettings.grading = true;
        EnhancedSettings.saturation = 1.4f;
        EnhancedSettings.contrast = 1.15f;
        EnhancedSettings.tint = new float[] {1.05f, 1.0f, 0.9f};
      }, () -> hillsPost(null));
      Golden.check("enhanced_tonemap_grade", img);
    }

    /** Auto-exposure on a well lit scene must settle on a multiplier inside its limits, and change the image. */
    @Test
    void enhancedAutoExposure() {
      BufferedImage base = withPost(() -> {}, () -> hillsPost(null));
      BufferedImage img = withPost(() -> {
        EnhancedSettings.autoExposure = true;
        EnhancedSettings.exposureStrength = 1f;
        EnhancedSettings.exposureKey = 0.5f;
        EnhancedSettings.exposureMax = 2f;
      }, () -> hillsPost(null));
      Golden.check("enhanced_auto_exposure", img);
      long a = 0, b = 0;
      for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
          a += base.getRGB(x, y) & 255;
          b += img.getRGB(x, y) & 255;
        }
      }
      assertTrue(a != b, "auto-exposure changed nothing");
    }

    /** Vignette, chromatic aberration, grain and sharpening together. */
    @Test
    void enhancedScreenEffects() {
      BufferedImage img = withPost(() -> {
        EnhancedSettings.vignette = true;
        EnhancedSettings.vignetteStrength = 0.6f;
        EnhancedSettings.chromaticAberration = true;
        EnhancedSettings.chromaticStrength = 0.02f;
        EnhancedSettings.filmGrain = true;
        EnhancedSettings.grainStrength = 0.08f;
        EnhancedSettings.sharpen = true;
        EnhancedSettings.sharpenStrength = 0.6f;
      }, () -> hillsPost(f -> postParams(f, 0, new float[] {0f, -1f, 0f}, 0f)));
      Golden.check("enhanced_screen_effects", img);
    }

    @Test
    void enhancedDepthOfField() {
      BufferedImage img = withPost(() -> {
        EnhancedSettings.depthOfField = true;
        EnhancedSettings.dofRadius = 10f;
        EnhancedSettings.dofStrength = 2f;
      }, () -> hillsPost(f -> postParams(f, 0, new float[] {0f, -1f, 0f}, 0f)));
      Golden.check("enhanced_dof", img);
    }

    @Test
    void enhancedMotionBlur() {
      BufferedImage img = withPost(() -> {
        EnhancedSettings.motionBlur = true;
        EnhancedSettings.motionBlurStrength = 1f;
      }, () -> hillsPost(f -> postParams(f, 0, new float[] {0f, -1f, 0f}, 2.5f)));
      Golden.check("enhanced_motion_blur", img);
    }

    @Test
    void enhancedLensFlare() {
      BufferedImage img = withPost(() -> {
        EnhancedSettings.lensFlare = true;
        EnhancedSettings.lensFlareStrength = 1f;
      }, () -> hillsPost(f -> {
        // The sun a bit right of and above the view center, in the sky over the hills.
        Matrix4f invProj = new Matrix4f(f.projection()).invert();
        Vector4f v = invProj.transform(new Vector4f(0.3f, 0.45f, 1f, 1f));
        Vector3f d = new Vector3f(v.x / v.w, v.y / v.w, v.z / v.w);
        new Matrix4f(f.view()).invert().transformDirection(d).normalize();
        return postParams(f, 0, new float[] {d.x, d.y, d.z}, 0f);
      }));
      Golden.check("enhanced_lens_flare", img);
    }
  }

  /** The pond, seen from the bank and from under the surface. */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Pond {
    private TestScene scene;

    @BeforeAll
    void build() {
      scene = new TestScene(atlas, TestWorlds.POND, 0, 0, 96);
    }

    @AfterAll
    void close() {
      if (scene != null) scene.close();
    }

    /** Water seen from the bank: the floor shows through the surface, and the post stands in it. */
    @Test
    void pondFromTheBank() {
      Golden.check("pond_bank", draw(scene, -17.5, 63.0, -9.5, 300f, 22f, 0));
    }

    /** Enhanced water from the bank: waves, the post and the sky mirrored in the surface, absorption over the floor. */
    @Test
    void enhancedWaterBank() {
      var f = scene.enhanced(scene.frame(SW, SH, -17.5, 63.0, -9.5, 300f, 8f, 70f, 0), new float[] {-0.5f, 0.45f, 0.3f});
      Golden.check("enhanced_water_bank", gl.render(f, scene, SKY, SW, SH, true));
    }

    /** Looking across the pond at a low angle: strong Fresnel reflection of the far bank and sky. */
    @Test
    void enhancedWaterGrazing() {
      var f = scene.enhanced(scene.frame(SW, SH, -17.5, 60.3, -9.5, 300f, 2f, 70f, 0), new float[] {0.5f, 0.35f, -0.3f});
      Golden.check("enhanced_water_grazing", gl.render(f, scene, SKY, SW, SH, true));
    }

    /** Enhanced from under the surface: Snell's window, total internal reflection outside it. */
    @Test
    void enhancedWaterUnder() {
      var f = scene.enhanced(scene.frame(SW, SH, -3.5, 56.5, -2.5, 300f, -35f, 70f, 0), new float[] {-0.5f, 0.7f, 0.3f});
      Golden.check("enhanced_water_under", gl.render(f, scene, SKY, SW, SH, true));
    }

    /** The same pond from under the surface: fogged, with the surface seen from below. */
    @Test
    void pondUnderWater() {
      Golden.check("pond_under", draw(scene, -3.5, 56.5, -2.5, 300f, 12f, 0));
    }

    /**
     * Enhanced mode draws through the HDR target and resolve pass, and until it gets its own lighting
     * it must look like Vanilla. The 16-bit float target may move a value by a step or two.
     */
    @Test
    void enhancedMatchesVanilla() {
      var saved = saveSettings();
      int savedSky = dev.macmv.look.SkyState.features;
      try {
        EnhancedSettings.neutral();
        // The sky and fog are Enhanced-only features of their own, tested elsewhere.
        dev.macmv.look.SkyState.features = 0;
        matchesVanilla();
      } finally {
        restoreSettings(saved);
        dev.macmv.look.SkyState.features = savedSky;
      }
    }

    private void matchesVanilla() {
      for (double[] cam : new double[][] {{-17.5, 63.0, -9.5, 300, 22}, {-3.5, 56.5, -2.5, 300, 12}}) {
        var vanilla = scene.frame(W, H, cam[0], cam[1], cam[2], (float) cam[3], (float) cam[4], 70f, 0);
        BufferedImage a = gl.render(vanilla, scene, SKY, W, H);
        BufferedImage b = gl.render(scene.enhanced(vanilla), scene, SKY, W, H);
        int bad = 0;
        for (int y = 0; y < H; y++) {
          for (int x = 0; x < W; x++) {
            int p = a.getRGB(x, y), q = b.getRGB(x, y);
            for (int s = 0; s < 24; s += 8) {
              if (Math.abs(((p >> s) & 255) - ((q >> s) & 255)) > 2) {
                bad++;
                break;
              }
            }
          }
        }
        assertTrue(bad == 0, bad + " pixels differ between Vanilla and Enhanced");
      }
    }

    @Test
    void enhancedUnderwaterDistortion() {
      BufferedImage img = withPost(() -> {
        EnhancedSettings.fluidDistortion = true;
        EnhancedSettings.fluidDistortionStrength = 3f;
      }, () -> {
        var f = scene.enhanced(scene.frame(W, H, -3.5, 56.5, -2.5, 300f, 12f, 70f, 0));
        return gl.render(f, scene, SKY, W, H, postParams(f, 1, new float[] {0f, 1f, 0f}, 0f));
      });
      Golden.check("enhanced_underwater", img);
    }
  }

  /** Flat grass with pillars, a post and a slab, for the shadow pass. */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Shadows {
    private TestScene scene;

    @BeforeAll
    void build() {
      ShadowPass.configure(32f, 512, 64f, 512);
      scene = new TestScene(atlas, TestWorlds.SHADOWS, 0, 0, 96);
    }

    @AfterAll
    void close() {
      if (scene != null) scene.close();
    }

    /** Afternoon sun: the pillar, post and slab throw shadows over the grass, soft at the far end. */
    @Test
    void enhancedSunShadows() {
      Golden.check("enhanced_shadows", shadowScene(scene, new float[] {-0.6f, 0.55f, -0.1f}, 14.5, 66.0, -16.5, 41f, 22f));
    }

    /** Low sun: long shadows, orange light. */
    @Test
    void enhancedSunsetShadows() {
      Golden.check("enhanced_sunset", shadowScene(scene, new float[] {-0.8f, 0.2f, -0.1f}, 14.5, 63.0, -16.5, 41f, 12f));
    }

    /** The moon is up (the sun is below the horizon): dim blue light and its own shadows. */
    @Test
    void enhancedMoonShadows() {
      Golden.check("enhanced_moon", shadowScene(scene, new float[] {0.6f, -0.55f, 0.1f}, 14.5, 66.0, -16.5, 41f, 22f));
    }
  }

  /** Emissive blocks in a dim world. */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Emissive {
    private TestScene scene;

    @BeforeAll
    void build() {
      scene = new TestScene(atlas, TestWorlds.EMISSIVE, 0, 6, 96);
    }

    @AfterAll
    void close() {
      if (scene != null) scene.close();
    }

    /**
     * Emissive blocks in a dim world, Enhanced only: glowstone and lava glow all over, redstone ore only
     * at its flecks, plain stone (second from the right) stays dark.
     */
    @Test
    void enhancedEmission() {
      var vanilla = scene.frame(W, H, 0.5, 62.0, 0.5, 0f, 14f, 70f, 0);
      BufferedImage dim = gl.render(scene.enhanced(vanilla, 0.25f), scene, SKY, W, H);
      Golden.check("enhanced_emission", dim);

      // The same blocks in Vanilla get no emission: the glowstone is as dark as the stone around it.
      BufferedImage plain = gl.render(scene.dimmed(vanilla, 0.25f), scene, SKY, W, H);
      int glow = brightest(dim), glowVanilla = brightest(plain);
      assertTrue(glow > glowVanilla + 40, "emission did not brighten the glowstone: " + glow + " vs " + glowVanilla);
    }
  }

  /** Vanilla's terrain for a fixed seed, made by the native engine, seen from high up over the whole pyramid. */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class FarTerrain {
    private NativeTerrain terrain;
    private TestScene scene;

    @BeforeAll
    void build() {
      terrain = new NativeTerrain(FAR_X, FAR_Z, FAR_YAW, 60f);
      scene = new TestScene(atlas, terrain, terrain, FAR_X, FAR_Z, Lod.maxDist());
    }

    @AfterAll
    void close() {
      if (scene != null) scene.close();
      if (terrain != null) terrain.close();
    }

    /** Camera 1000 blocks up, looking out over everything the pyramid reaches (about 1000 chunks). */
    @Test
    void heightFieldFromHigh() {
      BufferedImage img = drawBig(scene, FAR_X, 1000.0, FAR_Z, FAR_YAW, FAR_PITCH, 0);
      assertTrue(terrainFraction(img) > 0.2, "almost nothing was drawn, the heightfield is probably empty");
      Golden.check("heightfield_far", img);
    }

    /** The same view tinted per pyramid level, to see where the heightfield levels start and end. */
    @Test
    void heightFieldLevelTint() {
      Golden.check("heightfield_far_levels", drawBig(scene, FAR_X, 1000.0, FAR_Z, FAR_YAW, FAR_PITCH, 2));
    }
  }

  /** The same terrain with trees grown: bricks have them near, the heightfield past that. */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Trees {
    private NativeTerrain terrain;
    private TestScene scene;

    @BeforeAll
    void build() {
      terrain = new NativeTerrain(TREES_X, TREES_Z, TREES_YAW, 40f, true);
      scene = new TestScene(atlas, terrain, terrain, TREES_X, TREES_Z, Lod.maxDist());
    }

    @AfterAll
    void close() {
      if (scene != null) scene.close();
      if (terrain != null) terrain.close();
    }

    /** A few hundred blocks up, looking over forests out to the edge of the pyramid. */
    @Test
    void heightFieldTrees() {
      Golden.check("heightfield_trees", drawBig(scene, TREES_X, TREES_Y, TREES_Z, TREES_YAW, 4f, 0));
    }

    /** From 600 blocks up and looking down, so most of the frame is far canopy: leaves and the bark under them. */
    @Test
    void heightFieldTreesFromHigh() {
      Golden.check("heightfield_trees_high", drawBig(scene, TREES_X, 600.0, TREES_Z, TREES_YAW, 22f, 0));
    }
  }
}
