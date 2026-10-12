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
  private static int program;
  private static int vao;
  private static int uInvViewProj;
  private static int uView;
  private static int uProj;
  private static int uCamBlock;
  private static int uCamFrac;
  private static int uGrid;
  private static int uLevels;
  private static int uBase;
  private static int uLevel;
  private static int uMaxDist;
  private static int uDebug;
  private static int uHfEnabled;
  private static int uHfIds;
  private static int uAtlasSize;
  private static int uMaxLod;
  private static int uPixelAngle;
  private static int uMinY;
  private static int uSkyDarken;
  private static int uAmbientLight;
  private static int uTime;

  private VoxelRenderer() {}

  /**
   * Everything the raycast pass needs besides the textures on their units, so the pass can run
   * without a Minecraft instance (see the render tests).
   *
   * @param levelParams per pyramid level (lowest section y, rows, first row), see {@link SectionLayout}
   * @param hfIds {@code SeedSource.voxelIds()} when the heightfield is drawn, else null
   * @param viewportHeight pixels, to size the mip level picked for a hit
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
      float ambientLight) {}

  /** Must be called on the render thread with the main framebuffer bound. */
  public static void render(Matrix4f view, Matrix4f projection, double camX, double camY, double camZ) {
    if (program == 0) init();

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
        sky[0], sky[1], sky[2]);

    int prevUnit = GlStateManager._getActiveTexture();
    VoxelWorld.bind();

    RenderStats.beginGpu();
    draw(frame);
    RenderStats.endGpu();

    GL33C.glActiveTexture(prevUnit);
    RenderStats.endVoxelFrame();
  }

  /**
   * Draws the raycast pass into the bound framebuffer. The data textures must already be bound to
   * their units ({@link VoxelWorld#bind} does that in game).
   */
  public static void draw(Frame f) {
    if (program == 0) init();

    // Vanilla caches GL state in GlStateManager, so go through it for anything it tracks.
    RenderSystem.disableBlend();
    RenderSystem.disableCull();
    RenderSystem.enableDepthTest();
    RenderSystem.depthFunc(GL33C.GL_LEQUAL);
    RenderSystem.depthMask(true);

    GlStateManager._glUseProgram(program);
    GlStateManager._glBindVertexArray(vao);

    try (MemoryStack stack = MemoryStack.stackPush()) {
      FloatBuffer buf = stack.mallocFloat(16);

      Matrix4f invViewProj = new Matrix4f(f.projection()).mul(f.view()).invert();

      invViewProj.get(buf);
      GL33C.glUniformMatrix4fv(uInvViewProj, false, buf);
      f.view().get(buf);
      GL33C.glUniformMatrix4fv(uView, false, buf);
      f.projection().get(buf);
      GL33C.glUniformMatrix4fv(uProj, false, buf);
    }

    int bx = (int) Math.floor(f.camX());
    int by = (int) Math.floor(f.camY());
    int bz = (int) Math.floor(f.camZ());
    GL33C.glUniform3i(uCamBlock, bx, by, bz);
    GL33C.glUniform3f(uCamFrac, (float) (f.camX() - bx), (float) (f.camY() - by), (float) (f.camZ() - bz));
    GL33C.glUniform3i(uGrid, VoxelWorld.GRID_XZ, f.gridRows(), VoxelWorld.GRID_XZ);
    GL33C.glUniform1i(uLevels, Lod.LEVELS);
    GL33C.glUniform1f(uBase, Lod.BASE_BLOCKS);
    GL33C.glUniform3iv(uLevel, f.levelParams());

    GL33C.glUniform1f(uMaxDist, f.maxDist());

    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uIndirection"), VoxelWorld.UNIT_INDIRECTION);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uBricks"), VoxelWorld.UNIT_BRICKS);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uColors"), VoxelWorld.UNIT_COLORS);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uFaces"), VoxelWorld.UNIT_FACES);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uAtlas"), VoxelWorld.UNIT_ATLAS);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uTint"), VoxelWorld.UNIT_TINT);

    // Angle one pixel covers, used to pick a mip level from the distance of a hit.
    float pixelAngle = 2f / (f.projection().m11() * Math.max(1, f.viewportHeight()));
    GL33C.glUniform2f(uAtlasSize, f.atlasWidth(), f.atlasHeight());
    GL33C.glUniform1f(uMaxLod, f.atlasMaxLevel());
    GL33C.glUniform1f(uPixelAngle, pixelAngle);
    GL33C.glUniform1i(uMinY, f.minY());

    GL33C.glUniform1i(uDebug, f.debug());
    GL33C.glUniform1i(uHfEnabled, f.hfIds() != null ? 1 : 0);
    if (f.hfIds() != null) GL33C.glUniform1iv(uHfIds, f.hfIds());
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uHfHeight"), HeightField.UNIT_HEIGHT);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uHfAttr"), HeightField.UNIT_ATTR);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uHfTiles"), HeightField.UNIT_TILES);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uLightIndirection"), LightMap.UNIT_INDIRECTION);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uLight"), LightMap.UNIT_LIGHT);
    GL33C.glUniform1f(uTime, f.time());
    GL33C.glUniform1f(uSkyDarken, f.skyDarken());
    GL33C.glUniform1f(uAmbientLight, f.ambientLight());

    GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);

    GlStateManager._glBindVertexArray(0);
    GlStateManager._glUseProgram(0);
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

  private static void init() {
    int vs = compile(GL33C.GL_VERTEX_SHADER, "voxel.vsh");
    int fs = compile(GL33C.GL_FRAGMENT_SHADER, "voxel.fsh");

    program = GL33C.glCreateProgram();
    GL33C.glAttachShader(program, vs);
    GL33C.glAttachShader(program, fs);
    GL33C.glLinkProgram(program);
    if (GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS) == 0) {
      throw new IllegalStateException("Failed to link voxel program: " + GL33C.glGetProgramInfoLog(program));
    }
    GL33C.glDeleteShader(vs);
    GL33C.glDeleteShader(fs);

    uInvViewProj = GL33C.glGetUniformLocation(program, "uInvViewProj");
    uView = GL33C.glGetUniformLocation(program, "uView");
    uProj = GL33C.glGetUniformLocation(program, "uProj");
    uCamBlock = GL33C.glGetUniformLocation(program, "uCamBlock");
    uCamFrac = GL33C.glGetUniformLocation(program, "uCamFrac");
    uGrid = GL33C.glGetUniformLocation(program, "uGrid");
    uLevels = GL33C.glGetUniformLocation(program, "uLevels");
    uBase = GL33C.glGetUniformLocation(program, "uBase");
    uLevel = GL33C.glGetUniformLocation(program, "uLevel");
    uMaxDist = GL33C.glGetUniformLocation(program, "uMaxDist");
    uDebug = GL33C.glGetUniformLocation(program, "uDebug");
    uHfEnabled = GL33C.glGetUniformLocation(program, "uHfEnabled");
    uHfIds = GL33C.glGetUniformLocation(program, "uHfIds");
    uAtlasSize = GL33C.glGetUniformLocation(program, "uAtlasSize");
    uMaxLod = GL33C.glGetUniformLocation(program, "uMaxLod");
    uPixelAngle = GL33C.glGetUniformLocation(program, "uPixelAngle");
    uSkyDarken = GL33C.glGetUniformLocation(program, "uSkyDarken");
    uAmbientLight = GL33C.glGetUniformLocation(program, "uAmbientLight");
    uMinY = GL33C.glGetUniformLocation(program, "uMinY");
    uTime = GL33C.glGetUniformLocation(program, "uTime");

    // Core profile needs a VAO bound even though the triangle comes from gl_VertexID.
    vao = GL33C.glGenVertexArrays();
  }

  static int compile(int type, String name) {
    int shader = GL33C.glCreateShader(type);
    GL33C.glShaderSource(shader, readShader(name));
    GL33C.glCompileShader(shader);
    if (GL33C.glGetShaderi(shader, GL33C.GL_COMPILE_STATUS) == 0) {
      throw new IllegalStateException("Failed to compile " + name + ": " + GL33C.glGetShaderInfoLog(shader));
    }
    return shader;
  }

  private static String readShader(String name) {
    String path = "/assets/" + LookMod.ID + "/shaders/" + name;
    try (InputStream in = VoxelRenderer.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("Missing shader " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read shader " + path, e);
    }
  }
}
