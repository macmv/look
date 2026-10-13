package dev.macmv.look;

/**
 * Quality and style knobs for Enhanced mode post processing (docs/enhanced.md sections 7 and 10).
 * Plain static fields so a settings screen can write them later; every effect has its own switch.
 * Nothing here has any effect in Vanilla mode.
 */
public final class EnhancedSettings {
  private EnhancedSettings() {}

  // --- Resolve pass (HDR, before vanilla draws entities) -----------------------------------------

  public static boolean bloom = true;
  /** Scene luminance above this (in HDR units, 1 is display white) starts to bloom. */
  public static float bloomThreshold = 1.0f;
  /** Width of the soft knee below the threshold, in HDR units. */
  public static float bloomKnee = 0.5f;
  /** How much of the blurred bright parts is added back. */
  public static float bloomStrength = 0.15f;
  /** Width of the upsample kernel, in texels of each mip. Around 1 is a clean tent. */
  public static float bloomRadius = 1.0f;

  public static boolean autoExposure = true;
  /** The luminance (geometric mean of the frame) that exposure aims for. */
  public static float exposureKey = 0.25f;
  /** Limits on the automatic multiplier. */
  public static float exposureMin = 0.5f;
  public static float exposureMax = 2.0f;
  /** Adaptation speeds in 1/seconds, when the scene gets darker (exposure rises) and brighter. */
  public static float exposureUpRate = 0.7f;
  public static float exposureDownRate = 1.8f;
  /** 0 ignores the automatic multiplier, 1 applies it fully. */
  public static float exposureStrength = 0.7f;
  /** Fixed multiplier applied on top of the automatic one. */
  public static float exposureCompensation = 1.0f;

  public static final int TONEMAP_CLAMP = 0;
  public static final int TONEMAP_SHOULDER = 1;
  public static final int TONEMAP_ACES = 2;
  public static final int TONEMAP_REINHARD = 3;
  /** Shoulder keeps everything below 0.8 untouched and rolls the rest off, so Vanilla-range lighting looks the same. */
  public static int tonemap = TONEMAP_SHOULDER;

  public static boolean grading = true;
  public static float saturation = 1.0f;
  public static float vibrance = 0.0f;
  public static float contrast = 1.0f;
  public static float brightness = 0.0f;
  /** Multiplied into the final color; a per-time-of-day tint can drive this later. */
  public static float[] tint = {1f, 1f, 1f};

  // --- Final post (LDR, after the translucent layer, before the GUI) ------------------------------

  public static boolean vignette = true;
  public static float vignetteStrength = 0.25f;

  public static boolean chromaticAberration = false;
  /** Offset at the screen corner, as a fraction of the screen width. */
  public static float chromaticStrength = 0.004f;

  public static boolean filmGrain = false;
  public static float grainStrength = 0.04f;

  public static boolean sharpen = true;
  public static float sharpenStrength = 0.25f;

  public static boolean depthOfField = false;
  /** Blur radius at full defocus, in pixels at 1080p. */
  public static float dofRadius = 6.0f;
  /** How strongly distance from the focus plane turns into blur. */
  public static float dofStrength = 1.0f;

  public static boolean motionBlur = false;
  public static float motionBlurStrength = 0.5f;

  public static boolean lensFlare = false;
  public static float lensFlareStrength = 0.4f;

  /** Warp the frame while the camera is under water or in lava. */
  public static boolean fluidDistortion = true;
  public static float fluidDistortionStrength = 1.0f;

  /** Whether any final post effect is on, so the pass can be skipped entirely. */
  public static boolean anyFinalPost() {
    return vignette || chromaticAberration || filmGrain || sharpen || depthOfField || motionBlur || lensFlare || fluidDistortion;
  }

  /** Every post effect off and the tonemap a plain clamp: Enhanced then matches Vanilla. */
  public static void neutral() {
    bloom = false;
    autoExposure = false;
    tonemap = TONEMAP_CLAMP;
    grading = false;
    vignette = chromaticAberration = filmGrain = sharpen = depthOfField = motionBlur = lensFlare = fluidDistortion = false;
  }
}
