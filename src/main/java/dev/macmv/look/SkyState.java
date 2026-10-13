package dev.macmv.look;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL33C;

/**
 * Sky, cloud and fog settings for the Enhanced program (docs/enhanced.md sections 4.1 to 4.3). The
 * sun, moon and rain come from {@link VoxelRenderer.Atmosphere}; this holds the rest and uploads it.
 * Everything here is read only by the {@code ENHANCED} program, see {@code sky.glsl}.
 */
public final class SkyState {
  /** Feature bits for {@link #features}. */
  public static final int SKY = 1, CLOUDS = 2, FOG = 4, ALL = SKY | CLOUDS | FOG;

  /**
   * Which parts are on. {@link #update} clears them outside the overworld, where vanilla keeps
   * drawing its own sky. Tests clear them to compare against Vanilla.
   */
  public static int features = ALL;

  /** 0 off, 1 vanilla-style blocky grid, 2 soft volumetric. */
  public static int cloudStyle = 2;

  /** Height of the cloud layer in blocks, and how far the wind has moved the pattern (x, z). */
  public static float cloudHeight = 192.33f;
  public static float cloudScrollX;
  public static float cloudScrollZ;
  /** Cloud color after vanilla's day, night and rain dimming. */
  public static float[] cloudColor = {1f, 1f, 1f};

  private SkyState() {}

  /** True when Enhanced owns the sky, so vanilla's sky should not draw. */
  public static boolean ownsSky() {
    return LookState.voxelEnabled && LookState.enhanced && (features & SKY) != 0;
  }

  /** True when Enhanced owns the clouds, so vanilla's clouds should not draw. */
  public static boolean ownsClouds() {
    return LookState.voxelEnabled && LookState.enhanced && (features & CLOUDS) != 0 && cloudStyle != 0;
  }

  /** Reads the level; call once per frame on the render thread before drawing in Enhanced mode. */
  public static void update(ClientLevel level, float partial) {
    if (level == null || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
      features = 0;
      return;
    }
    features = ALL;
    float h = level.effects().getCloudHeight();
    cloudHeight = Float.isNaN(h) ? 192.33f : h + 0.33f;
    // Vanilla moves the pattern 0.03 blocks per tick toward -x.
    cloudScrollX = (float) (((level.getGameTime() % 4000000L) + partial) * 0.03);
    cloudScrollZ = 0f;
    Vec3 c = level.getCloudColor(partial);
    cloudColor = new float[] {(float) c.x, (float) c.y, (float) c.z};
  }

  /** Sets the sky uniforms on the (bound) Enhanced program. */
  public static void upload(int program) {
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uSkyFeatures"), features);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uCloudStyle"), cloudStyle);
    GL33C.glUniform1f(GL33C.glGetUniformLocation(program, "uCloudHeight"), cloudHeight);
    GL33C.glUniform2f(GL33C.glGetUniformLocation(program, "uCloudScroll"), cloudScrollX, cloudScrollZ);
    GL33C.glUniform3f(GL33C.glGetUniformLocation(program, "uCloudColor"), cloudColor[0], cloudColor[1], cloudColor[2]);
  }
}
