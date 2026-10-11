package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
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
  private static int uAtlasSize;
  private static int uMaxLod;
  private static int uPixelAngle;

  private VoxelRenderer() {}

  /** Must be called on the render thread with the main framebuffer bound. */
  public static void render(Matrix4f view, Matrix4f projection, double camX, double camY, double camZ) {
    if (program == 0) init();

    long updateStart = System.nanoTime();
    VoxelWorld.update();
    RenderStats.addUpdate(System.nanoTime() - updateStart);
    if (!VoxelWorld.isReady()) return;

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

      Matrix4f invViewProj = new Matrix4f(projection).mul(view).invert();

      invViewProj.get(buf);
      GL33C.glUniformMatrix4fv(uInvViewProj, false, buf);
      view.get(buf);
      GL33C.glUniformMatrix4fv(uView, false, buf);
      projection.get(buf);
      GL33C.glUniformMatrix4fv(uProj, false, buf);
    }

    int bx = (int) Math.floor(camX);
    int by = (int) Math.floor(camY);
    int bz = (int) Math.floor(camZ);
    GL33C.glUniform3i(uCamBlock, bx, by, bz);
    GL33C.glUniform3f(uCamFrac, (float) (camX - bx), (float) (camY - by), (float) (camZ - bz));
    GL33C.glUniform3i(uGrid, VoxelWorld.GRID_XZ, VoxelWorld.totalRows(), VoxelWorld.GRID_XZ);
    GL33C.glUniform1i(uLevels, Lod.LEVELS);
    GL33C.glUniform1f(uBase, Lod.BASE_BLOCKS);
    GL33C.glUniform3iv(uLevel, VoxelWorld.levelParams());

    GL33C.glUniform1f(uMaxDist, VoxelWorld.maxDistBlocks());

    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uIndirection"), VoxelWorld.UNIT_INDIRECTION);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uBricks"), VoxelWorld.UNIT_BRICKS);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uColors"), VoxelWorld.UNIT_COLORS);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uFaces"), VoxelWorld.UNIT_FACES);
    GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "uAtlas"), VoxelWorld.UNIT_ATLAS);

    // Angle one pixel covers, used to pick a mip level from the distance of a hit.
    float pixelAngle = 2f / (projection.m11() * Math.max(1, Minecraft.getInstance().getMainRenderTarget().height));
    GL33C.glUniform2f(uAtlasSize, VoxelWorld.atlasWidth(), VoxelWorld.atlasHeight());
    GL33C.glUniform1f(uMaxLod, VoxelWorld.atlasMaxLevel());
    GL33C.glUniform1f(uPixelAngle, pixelAngle);

    int prevUnit = GlStateManager._getActiveTexture();
    VoxelWorld.bind();

    GL33C.glUniform1i(uDebug, LookState.debug);

    RenderStats.beginGpu();
    GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
    RenderStats.endGpu();

    GL33C.glActiveTexture(prevUnit);

    GlStateManager._glBindVertexArray(0);
    GlStateManager._glUseProgram(0);

    RenderStats.endVoxelFrame();
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
    uAtlasSize = GL33C.glGetUniformLocation(program, "uAtlasSize");
    uMaxLod = GL33C.glGetUniformLocation(program, "uMaxLod");
    uPixelAngle = GL33C.glGetUniformLocation(program, "uPixelAngle");

    // Core profile needs a VAO bound even though the triangle comes from gl_VertexID.
    vao = GL33C.glGenVertexArrays();
  }

  private static int compile(int type, String name) {
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
