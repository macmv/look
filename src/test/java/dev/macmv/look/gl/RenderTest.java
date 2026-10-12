package dev.macmv.look.gl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
  private static final float[] SKY = {0.47f, 0.65f, 1.0f};

  private static GlContext gl;
  private static TestAtlas atlas;

  @BeforeAll
  static void setUp() {
    String[] why = new String[1];
    gl = GlContext.tryCreate(W, H, why);
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
    return gl.render(scene.frame(W, H, x, y, z, yaw, pitch, 70f, debug), scene, SKY);
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

  @Test
  void hillsLevelTint() {
    try (TestScene scene = new TestScene(atlas, TestWorlds.HILLS, 0, 0, 700)) {
      BufferedImage img = draw(scene, 0.5, 110.0, 0.5, 35f, 8f, 2);
      Golden.check("hills_levels", img);
    }
  }
}
