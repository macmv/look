package dev.macmv.look;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;

/**
 * The options shown on the "Look" and "Shaders" tabs of the video settings screen
 * ({@code LookVideoScreen}), and their persistence in {@code config/look.properties}. Each
 * {@link Entry} is both a widget factory and a property, so adding a setting is one line.
 */
public final class LookSettings {
  private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("look.properties");

  private LookSettings() {}

  public interface Entry {
    String key();

    OptionInstance<?> create();

    void write(Properties out);

    void read(Properties in);
  }

  /** Renderer and level-of-detail settings. */
  public static final List<Entry> LOOK = List.of(
      bool("voxel", () -> LookState.voxelEnabled, LookState::setVoxelEnabled),
      choice("debug", LookState.DEBUG_COUNT, LookState::debugName, () -> LookState.debug, v -> LookState.debug = v));

  /** The master switch for the shaders tab (Enhanced render mode). Saved with the rest, shown as a wide button. */
  public static final Entry SHADERS = bool("enhanced", () -> LookState.enhanced, v -> LookState.enhanced = v);

  /** The switch's widget, calling {@code onChange} after each toggle so the tab can grey out the rest. */
  public static OptionInstance<Boolean> shadersToggle(Runnable onChange) {
    return OptionInstance.createBoolean(caption("enhanced"), LookState.enhanced, v -> {
      LookState.enhanced = v;
      onChange.run();
    });
  }

  /** Enhanced mode post processing, straight onto {@link EnhancedSettings}. */
  public static final List<Entry> SHADER = List.of(
      bool("bloom", () -> EnhancedSettings.bloom, v -> EnhancedSettings.bloom = v),
      number("bloom_strength", 0f, 1f, 0.05f, () -> EnhancedSettings.bloomStrength, v -> EnhancedSettings.bloomStrength = v),
      number("bloom_threshold", 0f, 4f, 0.1f, () -> EnhancedSettings.bloomThreshold, v -> EnhancedSettings.bloomThreshold = v),
      number("bloom_radius", 0.25f, 3f, 0.05f, () -> EnhancedSettings.bloomRadius, v -> EnhancedSettings.bloomRadius = v),
      bool("auto_exposure", () -> EnhancedSettings.autoExposure, v -> EnhancedSettings.autoExposure = v),
      number("exposure_strength", 0f, 1f, 0.05f, () -> EnhancedSettings.exposureStrength, v -> EnhancedSettings.exposureStrength = v),
      number("exposure_compensation", 0.25f, 4f, 0.05f, () -> EnhancedSettings.exposureCompensation, v -> EnhancedSettings.exposureCompensation = v),
      choice("tonemap", 4, LookSettings::tonemapName, () -> EnhancedSettings.tonemap, v -> EnhancedSettings.tonemap = v),
      bool("grading", () -> EnhancedSettings.grading, v -> EnhancedSettings.grading = v),
      number("saturation", 0f, 2f, 0.05f, () -> EnhancedSettings.saturation, v -> EnhancedSettings.saturation = v),
      number("vibrance", -1f, 1f, 0.05f, () -> EnhancedSettings.vibrance, v -> EnhancedSettings.vibrance = v),
      number("contrast", 0.5f, 1.5f, 0.05f, () -> EnhancedSettings.contrast, v -> EnhancedSettings.contrast = v),
      number("brightness", -0.5f, 0.5f, 0.05f, () -> EnhancedSettings.brightness, v -> EnhancedSettings.brightness = v),
      bool("vignette", () -> EnhancedSettings.vignette, v -> EnhancedSettings.vignette = v),
      number("vignette_strength", 0f, 1f, 0.05f, () -> EnhancedSettings.vignetteStrength, v -> EnhancedSettings.vignetteStrength = v),
      bool("sharpen", () -> EnhancedSettings.sharpen, v -> EnhancedSettings.sharpen = v),
      number("sharpen_strength", 0f, 1f, 0.05f, () -> EnhancedSettings.sharpenStrength, v -> EnhancedSettings.sharpenStrength = v),
      bool("chromatic", () -> EnhancedSettings.chromaticAberration, v -> EnhancedSettings.chromaticAberration = v),
      number("chromatic_strength", 0f, 0.02f, 0.001f, () -> EnhancedSettings.chromaticStrength, v -> EnhancedSettings.chromaticStrength = v),
      bool("film_grain", () -> EnhancedSettings.filmGrain, v -> EnhancedSettings.filmGrain = v),
      number("grain_strength", 0f, 0.2f, 0.01f, () -> EnhancedSettings.grainStrength, v -> EnhancedSettings.grainStrength = v),
      bool("dof", () -> EnhancedSettings.depthOfField, v -> EnhancedSettings.depthOfField = v),
      number("dof_radius", 1f, 16f, 0.5f, () -> EnhancedSettings.dofRadius, v -> EnhancedSettings.dofRadius = v),
      bool("motion_blur", () -> EnhancedSettings.motionBlur, v -> EnhancedSettings.motionBlur = v),
      number("motion_blur_strength", 0f, 1f, 0.05f, () -> EnhancedSettings.motionBlurStrength, v -> EnhancedSettings.motionBlurStrength = v),
      bool("lens_flare", () -> EnhancedSettings.lensFlare, v -> EnhancedSettings.lensFlare = v),
      number("lens_flare_strength", 0f, 1f, 0.05f, () -> EnhancedSettings.lensFlareStrength, v -> EnhancedSettings.lensFlareStrength = v),
      bool("fluid_distortion", () -> EnhancedSettings.fluidDistortion, v -> EnhancedSettings.fluidDistortion = v),
      number("fluid_distortion_strength", 0f, 2f, 0.05f, () -> EnhancedSettings.fluidDistortionStrength, v -> EnhancedSettings.fluidDistortionStrength = v));

  private static String tonemapName(int i) {
    return switch (i) {
      case EnhancedSettings.TONEMAP_CLAMP -> "Clamp";
      case EnhancedSettings.TONEMAP_SHOULDER -> "Shoulder";
      case EnhancedSettings.TONEMAP_ACES -> "ACES";
      default -> "Reinhard";
    };
  }

  public static OptionInstance<?>[] options(List<Entry> entries) {
    return entries.stream().map(Entry::create).toArray(OptionInstance<?>[]::new);
  }

  /** Read {@code look.properties}, if there is one. Unparseable values keep the current setting. */
  public static void load() {
    if (!Files.exists(FILE)) return;
    Properties props = new Properties();
    try (Reader in = Files.newBufferedReader(FILE)) {
      props.load(in);
    } catch (IOException e) {
      LookMod.LOGGER.warn("Could not read {}", FILE, e);
      return;
    }
    SHADERS.read(props);
    for (Entry e : LOOK) e.read(props);
    for (Entry e : SHADER) e.read(props);
  }

  public static void save() {
    Properties props = new Properties();
    SHADERS.write(props);
    for (Entry e : LOOK) e.write(props);
    for (Entry e : SHADER) e.write(props);
    try (Writer out = Files.newBufferedWriter(FILE)) {
      props.store(out, "Look settings, edited from Options > Video Settings");
    } catch (IOException e) {
      LookMod.LOGGER.warn("Could not write {}", FILE, e);
    }
  }

  private static String caption(String key) {
    return "look.option." + key;
  }

  private static Entry bool(String key, BooleanSupplier get, Consumer<Boolean> set) {
    return new Entry() {
      public String key() {
        return key;
      }

      public OptionInstance<?> create() {
        return OptionInstance.createBoolean(caption(key), get.getAsBoolean(), set);
      }

      public void write(Properties out) {
        out.setProperty(key, Boolean.toString(get.getAsBoolean()));
      }

      public void read(Properties in) {
        String v = in.getProperty(key);
        if (v != null) set.accept(Boolean.parseBoolean(v));
      }
    };
  }

  /** A slider over {@code [min, max]} in steps of {@code step}. */
  private static Entry number(String key, float min, float max, float step, FloatSupplier get, Consumer<Float> set) {
    int steps = Math.round((max - min) / step);
    return new Entry() {
      public String key() {
        return key;
      }

      public OptionInstance<?> create() {
        int initial = Math.max(0, Math.min(steps, Math.round((get.get() - min) / step)));
        return new OptionInstance<>(
            caption(key),
            OptionInstance.noTooltip(),
            (caption, v) -> Component.translatable("options.generic_value", caption, Component.literal(format(min + v * step, step))),
            new OptionInstance.IntRange(0, steps),
            initial,
            v -> set.accept(min + v * step));
      }

      public void write(Properties out) {
        out.setProperty(key, Float.toString(get.get()));
      }

      public void read(Properties in) {
        String v = in.getProperty(key);
        if (v == null) return;
        try {
          set.accept(Math.max(min, Math.min(max, Float.parseFloat(v))));
        } catch (NumberFormatException ignored) {
        }
      }
    };
  }

  /** A cycling button over {@code 0 .. count-1}. */
  private static Entry choice(String key, int count, IntFunction<String> name, IntSupplier get, IntConsumer set) {
    return new Entry() {
      public String key() {
        return key;
      }

      public OptionInstance<?> create() {
        return new OptionInstance<>(
            caption(key),
            OptionInstance.noTooltip(),
            (caption, v) -> Component.translatable("options.generic_value", caption, Component.literal(name.apply(v))),
            new OptionInstance.IntRange(0, count - 1),
            get.getAsInt(),
            set::accept);
      }

      public void write(Properties out) {
        out.setProperty(key, Integer.toString(get.getAsInt()));
      }

      public void read(Properties in) {
        String v = in.getProperty(key);
        if (v == null) return;
        try {
          set.accept(Math.max(0, Math.min(count - 1, Integer.parseInt(v))));
        } catch (NumberFormatException ignored) {
        }
      }
    };
  }

  private static String format(float v, float step) {
    return step >= 0.1f ? String.format(Locale.ROOT, "%.1f", v) : step >= 0.01f ? String.format(Locale.ROOT, "%.2f", v) : String.format(Locale.ROOT, "%.3f", v);
  }

  private interface FloatSupplier {
    float get();
  }
}
