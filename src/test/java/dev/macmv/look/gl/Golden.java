package dev.macmv.look.gl;

import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Compares a rendered image with a checked-in PNG. Software GL on another Mesa version can differ
 * by a rounding step here and there, so a few off-by-a-little pixels are allowed; anything more
 * fails and the actual and a diff image are written for CI to attach.
 */
final class Golden {
  /** Largest per-channel difference that still counts as equal. */
  static final int CHANNEL_TOLERANCE = 4;
  /** Fraction of pixels allowed to differ by more than that. */
  static final double MAX_BAD_FRACTION = 0.002;

  private static final Path GOLDEN_DIR = Path.of(System.getProperty("look.goldenDir", "src/test/resources/golden"));
  private static final Path OUT_DIR = Path.of(System.getProperty("look.renderOut", "build/render-tests"));
  private static final boolean UPDATE = Boolean.getBoolean("look.updateGolden");

  private Golden() {}

  static void check(String name, BufferedImage actual) {
    Path golden = GOLDEN_DIR.resolve(name + ".png");
    try {
      // Leftovers from an earlier run would look like a failure of this one.
      Files.deleteIfExists(OUT_DIR.resolve(name + "-actual.png"));
      Files.deleteIfExists(OUT_DIR.resolve(name + "-diff.png"));

      if (UPDATE) {
        Files.createDirectories(GOLDEN_DIR);
        ImageIO.write(actual, "png", golden.toFile());
        return;
      }

      Files.createDirectories(OUT_DIR);
      if (!Files.exists(golden)) {
        ImageIO.write(actual, "png", OUT_DIR.resolve(name + "-actual.png").toFile());
        fail("No golden image " + golden + "; review build/render-tests/" + name + "-actual.png and run with -PupdateGolden");
      }

      BufferedImage expected = ImageIO.read(golden.toFile());
      if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
        ImageIO.write(actual, "png", OUT_DIR.resolve(name + "-actual.png").toFile());
        fail(name + ": size " + actual.getWidth() + "x" + actual.getHeight() + ", golden is "
            + expected.getWidth() + "x" + expected.getHeight());
      }

      int bad = 0;
      int worst = 0;
      BufferedImage diff = new BufferedImage(actual.getWidth(), actual.getHeight(), BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < actual.getHeight(); y++) {
        for (int x = 0; x < actual.getWidth(); x++) {
          int a = actual.getRGB(x, y), e = expected.getRGB(x, y);
          int d = Math.max(
              Math.abs(((a >> 16) & 255) - ((e >> 16) & 255)),
              Math.max(Math.abs(((a >> 8) & 255) - ((e >> 8) & 255)), Math.abs((a & 255) - (e & 255))));
          worst = Math.max(worst, d);
          if (d > CHANNEL_TOLERANCE) {
            bad++;
            diff.setRGB(x, y, 0xFF0000);
          } else {
            // Faded copy of the golden, so the red pixels have some context.
            int g = ((e >> 16) & 255) / 4 + ((e >> 8) & 255) / 4 + (e & 255) / 4;
            diff.setRGB(x, y, g << 16 | g << 8 | g);
          }
        }
      }

      double fraction = bad / (double) (actual.getWidth() * actual.getHeight());
      if (fraction > MAX_BAD_FRACTION) {
        ImageIO.write(actual, "png", OUT_DIR.resolve(name + "-actual.png").toFile());
        ImageIO.write(diff, "png", OUT_DIR.resolve(name + "-diff.png").toFile());
        fail(String.format(
            "%s: %d pixels (%.3f%%) differ by more than %d, worst %d. See %s",
            name, bad, fraction * 100, CHANNEL_TOLERANCE, worst, OUT_DIR));
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
