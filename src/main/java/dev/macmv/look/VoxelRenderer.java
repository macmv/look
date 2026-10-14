package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

/**
 * Draws one fullscreen triangle and raycasts the world in the fragment shader, writing color and
 * depth into the main framebuffer. The world comes from {@link VoxelWorld}.
 */
public final class VoxelRenderer {
  /** The Vanilla program, and the one compiled with {@code #define ENHANCED}, made on first use. */
  private static Program vanilla;
  private static Program enhanced;
  private static Program shadow;
  private static int vao;

  /** A linked program with its uniform locations looked up once. */
  static final class Program {
    final int id;
    private final java.util.HashMap<String, Integer> locations = new java.util.HashMap<>();

    Program(int id) {
      this.id = id;
    }

    int loc(String name) {
      return locations.computeIfAbsent(name, n -> GL33C.glGetUniformLocation(id, n));
    }
  }

  private VoxelRenderer() {}

  /**
   * Everything the raycast pass needs besides the textures on their units, so the pass can run
   * without a Minecraft instance (see the render tests).
   *
   * @param levelParams per pyramid level (lowest section y, rows, first row), see {@link SectionLayout}
   * @param hfIds {@code SeedSource.voxelIds()} when the heightfield is drawn, else null
   * @param viewportHeight pixels, to size the mip level picked for a hit
   * @param waterId voxel id of a whole water voxel, whose sprite and tint the water surface uses
   * @param fogColor vanilla's fog color this frame, used under water
   * @param fogStart where vanilla's fog starts and ends this frame, which under water is the water fog
   * @param atmosphere sun, moon and weather for the Enhanced program, null for Vanilla
   */
  public record Frame(
      Matrix4f view,
      Matrix4f projection,
      double camX,
      double camY,
      double camZ,
      int gridRows,
      int[] levelParams,
      float maxDist,
      int minY,
      float atlasWidth,
      float atlasHeight,
      float atlasMaxLevel,
      int viewportHeight,
      int debug,
      int[] hfIds,
      float time,
      float skyDarken,
      float ambientLight,
      int waterId,
      float[] fogColor,
      float fogStart,
      float fogEnd,
      float seaLevel,
      Atmosphere atmosphere) {}

  /**
   * What the Enhanced program needs to know about the sky. A frame with one is drawn with the
   * Enhanced program (into the HDR target, see {@link EnhancedTarget}); a frame without is Vanilla.
   *
   * @param sunDir unit vector toward the sun, world space
   * @param moonDir unit vector toward the moon
   * @param moonPhase 0 (full) to 7, as {@code getMoonPhase}
   * @param rain smoothed rain level, 0..1
   * @param thunder smoothed thunder level, 0..1
   */
  public record Atmosphere(float[] sunDir, float[] moonDir, int moonPhase, float rain, float thunder) {}

  /** Must be called on the render thread with the main framebuffer bound. */
  public static void render(Matrix4f view, Matrix4f projection, double camX, double camY, double camZ) {
    long updateStart = System.nanoTime();
    VoxelWorld.update();
    RenderStats.addUpdate(System.nanoTime() - updateStart);
    if (!VoxelWorld.isReady()) return;

    int[] hfIds = SeedSource.INSTANCE.voxelIds();
    boolean hf = SeedSource.INSTANCE.available() && HeightField.active() && hfIds.length == 14;
    float[] sky = skyAndTime();

    Frame frame = new Frame(
        view, projection, camX, camY, camZ,
        VoxelWorld.totalRows(),
        VoxelWorld.levelParams(),
        VoxelWorld.maxDistBlocks(),
        VoxelWorld.minSection() * 16,
        VoxelWorld.atlasWidth(),
        VoxelWorld.atlasHeight(),
        VoxelWorld.atlasMaxLevel(),
        Minecraft.getInstance().getMainRenderTarget().height,
        LookState.debug,
        hf ? hfIds : null,
        sky[0], sky[1], sky[2],
        BlockClassifier.waterId(8),
        RenderSystem.getShaderFogColor(), RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
        Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getSeaLevel() : 63f,
        LookState.enhanced ? atmosphere() : null);

    int prevUnit = GlStateManager._getActiveTexture();
    VoxelWorld.bind();

    // Sun shadow map, before the timed world pass (timer queries do not nest). Overworld-like dimensions only.
    ShadowPass.render(frame, frame.atmosphere() != null && hasSun(Minecraft.getInstance().level));

    RenderStats.beginGpu();
    if (frame.atmosphere() != null) {
      SkyState.update(Minecraft.getInstance().level, Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
      // Own HDR target first, then resolve it into the main framebuffer. Vanilla draws on top of that.
      var main = Minecraft.getInstance().getMainRenderTarget();
      EnhancedTarget.begin(main.width, main.height);
      FinalPost.capture(view, projection, camX, camY, camZ, frame.atmosphere().sunDir());
      draw(frame);
      main.bindWrite(true);
      EnhancedTarget.resolve();
    } else {
      draw(frame);
    }
    RenderStats.endGpu();

    // Entities, particles and the outline draw after this and are refracted to match the water above.
    if (frame.atmosphere() != null && ShadowPass.active()) EntityRefraction.begin(frame);

    GL33C.glActiveTexture(prevUnit);
    RenderStats.endVoxelFrame();
  }

  /**
   * Draws the raycast pass into the bound framebuffer. The data textures must already be bound to
   * their units ({@link VoxelWorld#bind} does that in game).
   */
  public static void draw(Frame f) {
    Program p = program(f.atmosphere() != null);

    // Vanilla caches GL state in GlStateManager, so go through it for anything it tracks.
    // Opaque pixels write alpha 1, so blending only shows on water with nothing solid behind it.
    // Enhanced draws into a cleared HDR target instead, and the resolve pass does the blending.
    if (f.atmosphere() == null) {
      RenderSystem.enableBlend();
      RenderSystem.blendFuncSeparate(
          GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
          GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
    } else {
      RenderSystem.disableBlend();
    }
    RenderSystem.disableCull();
    RenderSystem.enableDepthTest();
    RenderSystem.depthFunc(GL33C.GL_LEQUAL);
    RenderSystem.depthMask(true);

    GlStateManager._glUseProgram(p.id);
    GlStateManager._glBindVertexArray(vao);

    try (MemoryStack stack = MemoryStack.stackPush()) {
      FloatBuffer buf = stack.mallocFloat(16);

      Matrix4f invViewProj = new Matrix4f(f.projection()).mul(f.view()).invert();

      invViewProj.get(buf);
      GL33C.glUniformMatrix4fv(p.loc("uInvViewProj"), false, buf);
      f.view().get(buf);
      GL33C.glUniformMatrix4fv(p.loc("uView"), false, buf);
      f.projection().get(buf);
      GL33C.glUniformMatrix4fv(p.loc("uProj"), false, buf);
    }

    voxelUniforms(p, f);

    Atmosphere atm = f.atmosphere();
    if (atm != null) {
      GL33C.glUniform3f(p.loc("uSunDir"), atm.sunDir()[0], atm.sunDir()[1], atm.sunDir()[2]);
      GL33C.glUniform3f(p.loc("uMoonDir"), atm.moonDir()[0], atm.moonDir()[1], atm.moonDir()[2]);
      GL33C.glUniform1i(p.loc("uMoonPhase"), atm.moonPhase());
      GL33C.glUniform1f(p.loc("uRain"), atm.rain());
      GL33C.glUniform1f(p.loc("uThunder"), atm.thunder());
      SkyState.upload(p.id);
      GL33C.glUniform1f(p.loc("uWaterWaves"), EnhancedSettings.waterWaves);
      GL33C.glUniform1i(p.loc("uWaterReflect"), EnhancedSettings.waterReflections);
      GL33C.glUniform1i(p.loc("uWaterRefract"), EnhancedSettings.waterRefraction ? 1 : 0);
      ShadowPass.setUniforms(p);
    }

    GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
    RenderSystem.disableBlend();

    GlStateManager._glBindVertexArray(0);
    GlStateManager._glUseProgram(0);
  }

  /** Camera, pyramid and data-texture uniforms shared by the raycast pass and the shadow pass. */
  static void voxelUniforms(Program p, Frame f) {
    int bx = (int) Math.floor(f.camX());
    int by = (int) Math.floor(f.camY());
    int bz = (int) Math.floor(f.camZ());
    GL33C.glUniform3i(p.loc("uCamBlock"), bx, by, bz);
    GL33C.glUniform3f(p.loc("uCamFrac"), (float) (f.camX() - bx), (float) (f.camY() - by), (float) (f.camZ() - bz));
    GL33C.glUniform3i(p.loc("uGrid"), VoxelWorld.GRID_XZ, f.gridRows(), VoxelWorld.GRID_XZ);
    GL33C.glUniform1i(p.loc("uLevels"), Lod.LEVELS);
    GL33C.glUniform1f(p.loc("uBase"), Lod.BASE_BLOCKS);
    GL33C.glUniform3iv(p.loc("uLevel"), f.levelParams());

    GL33C.glUniform1f(p.loc("uMaxDist"), f.maxDist());

    GL33C.glUniform1i(p.loc("uIndirection"), VoxelWorld.UNIT_INDIRECTION);
    GL33C.glUniform1i(p.loc("uBricks"), VoxelWorld.UNIT_BRICKS);
    GL33C.glUniform1i(p.loc("uColors"), VoxelWorld.UNIT_COLORS);
    GL33C.glUniform1i(p.loc("uFaces"), VoxelWorld.UNIT_FACES);
    GL33C.glUniform1i(p.loc("uAtlas"), VoxelWorld.UNIT_ATLAS);
    GL33C.glUniform1i(p.loc("uTint"), VoxelWorld.UNIT_TINT);
    GL33C.glUniform1i(p.loc("uMaterials"), Materials.UNIT);

    // Angle one pixel covers, used to pick a mip level from the distance of a hit.
    float pixelAngle = 2f / (f.projection().m11() * Math.max(1, f.viewportHeight()));
    GL33C.glUniform2f(p.loc("uAtlasSize"), f.atlasWidth(), f.atlasHeight());
    GL33C.glUniform1f(p.loc("uMaxLod"), f.atlasMaxLevel());
    GL33C.glUniform1f(p.loc("uPixelAngle"), pixelAngle);
    GL33C.glUniform1i(p.loc("uMinY"), f.minY());

    GL33C.glUniform1i(p.loc("uDebug"), f.debug());
    GL33C.glUniform1i(p.loc("uHfEnabled"), f.hfIds() != null ? 1 : 0);
    if (f.hfIds() != null) GL33C.glUniform1iv(p.loc("uHfIds"), f.hfIds());
    GL33C.glUniform1i(p.loc("uHfHeight"), HeightField.UNIT_HEIGHT);
    GL33C.glUniform1i(p.loc("uHfAttr"), HeightField.UNIT_ATTR);
    GL33C.glUniform1i(p.loc("uHfTiles"), HeightField.UNIT_TILES);
    GL33C.glUniform1i(p.loc("uHfSuper"), HeightField.UNIT_SUPER);
    GL33C.glUniform1i(p.loc("uHfCeiling"), HeightField.ceiling());
    GL33C.glUniform1i(p.loc("uLightIndirection"), LightMap.UNIT_INDIRECTION);
    GL33C.glUniform1i(p.loc("uLight"), LightMap.UNIT_LIGHT);
    GL33C.glUniform1f(p.loc("uTime"), f.time());
    GL33C.glUniform1f(p.loc("uSkyDarken"), f.skyDarken());
    GL33C.glUniform1f(p.loc("uAmbientLight"), f.ambientLight());
    GL33C.glUniform1i(p.loc("uWaterId"), f.waterId());
    GL33C.glUniform3f(p.loc("uFogColor"), f.fogColor()[0], f.fogColor()[1], f.fogColor()[2]);
    GL33C.glUniform2f(p.loc("uFogRange"), f.fogStart(), f.fogEnd());
    GL33C.glUniform1f(p.loc("uSeaLevel"), f.seaLevel());
  }

  /** Game time for the wind, sky darkening for the time of day and weather, and the dimension's ambient light. */
  private static float[] skyAndTime() {
    ClientLevel level = Minecraft.getInstance().level;
    float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);

    // Wraps at a multiple of the 128 tick sway period, so the wind has no visible jump.
    float time = 0f;
    if (level != null) time = (float) (level.getGameTime() % 12800L) + partial;

    float skyDarken = 1f;
    float ambient = 0f;
    if (level != null) {
      ambient = level.dimensionType().ambientLight();
      if (level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling()) {
        skyDarken = level.getSkyDarken(partial);
      }
    }
    return new float[] {time, skyDarken, ambient};
  }

  /** Whether the dimension has a sun to cast shadows: not the Nether (ceiling) or the End (no sky light). */
  private static boolean hasSun(ClientLevel level) {
    return level != null && level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
  }

  /** Sun, moon and weather from the client level, for the Enhanced program. */
  private static Atmosphere atmosphere() {
    ClientLevel level = Minecraft.getInstance().level;
    float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
    if (level == null) return new Atmosphere(new float[] {0f, 1f, 0f}, new float[] {0f, -1f, 0f}, 0, 0f, 0f);

    // Vanilla turns the sky about the z axis: the sun is overhead at angle 0 and rises toward +x.
    float angle = level.getSunAngle(partial);
    float sx = -(float) Math.sin(angle), sy = (float) Math.cos(angle);
    return new Atmosphere(
        new float[] {sx, sy, 0f}, new float[] {-sx, -sy, 0f},
        level.getMoonPhase(), level.getRainLevel(partial), level.getThunderLevel(partial));
  }

  /** The Enhanced program compiled as the shadow pass (see {@link ShadowPass}), made on first use. */
  static Program shadowProgram() {
    if (shadow == null) {
      program(false);
      shadow = link("#define ENHANCED\n#define SHADOW_PASS\n");
    }
    return shadow;
  }

  private static Program program(boolean enhancedMode) {
    if (vao == 0) {
      // Core profile needs a VAO bound even though the triangle comes from gl_VertexID.
      vao = GL33C.glGenVertexArrays();
    }
    if (enhancedMode) {
      if (enhanced == null) enhanced = link("#define ENHANCED\n");
      return enhanced;
    }
    if (vanilla == null) vanilla = link("");
    return vanilla;
  }

  /** Links the raycast program; {@code defines} go right after {@code #version}, so Vanilla's source is unchanged. */
  private static Program link(String defines) {
    int vs = compile(GL33C.GL_VERTEX_SHADER, "voxel.vsh", "");
    int fs = compile(GL33C.GL_FRAGMENT_SHADER, "voxel.fsh", defines);

    int id = GL33C.glCreateProgram();
    GL33C.glAttachShader(id, vs);
    GL33C.glAttachShader(id, fs);
    GL33C.glLinkProgram(id);
    if (GL33C.glGetProgrami(id, GL33C.GL_LINK_STATUS) == 0) {
      throw new IllegalStateException("Failed to link voxel program: " + GL33C.glGetProgramInfoLog(id));
    }
    GL33C.glDeleteShader(vs);
    GL33C.glDeleteShader(fs);
    return new Program(id);
  }

  static int vao() {
    program(false);
    return vao;
  }

  static int compile(int type, String name, String defines) {
    int shader = GL33C.glCreateShader(type);
    String source = resolveIncludes(readShader(name));
    if (!defines.isEmpty()) {
      // #version has to stay the first line.
      int eol = source.indexOf('\n') + 1;
      source = source.substring(0, eol) + defines + source.substring(eol);
    }
    GL33C.glShaderSource(shader, source);
    GL33C.glCompileShader(shader);
    if (GL33C.glGetShaderi(shader, GL33C.GL_COMPILE_STATUS) == 0) {
      throw new IllegalStateException("Failed to compile " + name + ": " + GL33C.glGetShaderInfoLog(shader));
    }
    return shader;
  }

  private static final java.util.regex.Pattern INCLUDE = java.util.regex.Pattern.compile("(?m)^#include \"([^\"]+)\"[ \\t]*$");

  /** Pastes in the {@code #include "file"} lines, so Enhanced code can live in its own files. */
  static String resolveIncludes(String source) {
    return INCLUDE.matcher(source).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(resolveIncludes(readShader(m.group(1)))));
  }

  static String readShader(String name) {
    String path = "/assets/" + LookMod.ID + "/shaders/" + name;
    try (InputStream in = VoxelRenderer.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("Missing shader " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read shader " + path, e);
    }
  }
}
