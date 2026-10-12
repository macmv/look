package dev.macmv.look.gl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.macmv.look.Lod;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Draws fixed scenes through the real raycast shader and compares them with checked-in images.
 * Needs a display (CI: {@code xvfb-run ./gradlew test}); without one the tests are skipped, unless
 * {@code LOOK_REQUIRE_GL} is set, in which case that is a failure. The images are Mesa llvmpipe
 * output, so a hardware GPU is skipped too (see {@code LOOK_GL_ANY}). Regenerate the images with
 * {@code ./gradlew test -PupdateGolden} and look at them before committing.
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

    // The checked-in images are Mesa software output. A GPU rounds differently along ray marching
    // edges, so comparing against it is noise; LOOK_GL_ANY=1 does it anyway.
    String renderer = gl.describe().toLowerCase();
    boolean software = renderer.contains("llvmpipe") || renderer.contains("softpipe") || renderer.contains("swrast");
    assumeTrue(software || System.getenv("LOOK_GL_ANY") != null, "not a software renderer (try LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a ...)");
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

  @Test
  void blocksCloseUp() {
    try (TestScene scene = new TestScene(atlas, TestWorlds.HILLS, 4, 0, 96)) {
      assertTrue(scene.slots() > 0, "scene has no bricks");
      BufferedImage img = draw(scene, 4.5, 80.5, 0.5, 0f, 18f, 0);
      Golden.check("blocks_closeup", img);
    }
  }

  @Test
  void blocksFromAbove() {
    try (TestScene scene = new TestScene(atlas, TestWorlds.HILLS, 4, 8, 96)) {
      BufferedImage img = draw(scene, 3.2, 84.0, 4.3, 25f, 55f, 0);
      Golden.check("blocks_from_above", img);
    }
  }

  @Test
  void hillsToTheHorizon() {
    try (TestScene scene = new TestScene(atlas, TestWorlds.HILLS, 0, 0, 700)) {
      BufferedImage img = draw(scene, 0.5, 110.0, 0.5, 35f, 8f, 0);
      Golden.check("hills_horizon", img);
    }
  }

  /**
   * Camera 1000 blocks up over vanilla's terrain for a fixed seed, made by the native engine, looking
   * out over everything the pyramid reaches (about 1000 chunks).
   */
  @Test
  void heightFieldFromHigh() {
    try (NativeTerrain terrain = new NativeTerrain(FAR_X, FAR_Z, FAR_YAW, 60f);
        TestScene scene = new TestScene(atlas, terrain, terrain, FAR_X, FAR_Z, Lod.maxDist())) {
      BufferedImage img = drawBig(scene, FAR_X, 1000.0, FAR_Z, FAR_YAW, FAR_PITCH, 0);
      assertTrue(terrainFraction(img) > 0.2, "almost nothing was drawn, the heightfield is probably empty");
      Golden.check("heightfield_far", img);
    }
  }

  /** The same view tinted per pyramid level, to see where the heightfield levels start and end. */
  @Test
  void heightFieldLevelTint() {
    try (NativeTerrain terrain = new NativeTerrain(FAR_X, FAR_Z, FAR_YAW, 60f);
        TestScene scene = new TestScene(atlas, terrain, terrain, FAR_X, FAR_Z, Lod.maxDist())) {
      BufferedImage img = drawBig(scene, FAR_X, 1000.0, FAR_Z, FAR_YAW, FAR_PITCH, 2);
      Golden.check("heightfield_far_levels", img);
    }
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

  @Test
  void hillsLevelTint() {
    try (TestScene scene = new TestScene(atlas, TestWorlds.HILLS, 0, 0, 700)) {
      BufferedImage img = draw(scene, 0.5, 110.0, 0.5, 35f, 8f, 2);
      Golden.check("hills_levels", img);
    }
  }
}
