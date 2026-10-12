package dev.macmv.look.gl;

import dev.macmv.look.BlockClassifier;
import dev.macmv.look.HeightField;
import dev.macmv.look.LightMap;
import dev.macmv.look.Lod;
import dev.macmv.look.SectionLayout;
import dev.macmv.look.TintMap;
import dev.macmv.look.VoxelRenderer;
import dev.macmv.look.VoxelWorld;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33C;

/**
 * The GPU side of {@link VoxelWorld} for a fixed, generated world: the same textures on the same
 * units with the same layouts, but filled once from a function instead of from a client level, so
 * the shader and {@link VoxelRenderer#draw} can run without Minecraft.
 *
 * <p>What it does not cover: residency (sections coming and going), vanilla's model baking (blocks
 * here are plain cubes with a texture per face, written in the layout {@link BlockClassifier}
 * produces), real biome tints and light (the tint is one color, everything is open sky).
 */
final class TestScene implements AutoCloseable {
  /** The world to draw: a voxel id (see the constants below) per block, 0 for air. */
  interface World {
    int idAt(int x, int y, int z);
  }

  static final int STONE = 1;
  static final int DIRT = 2;
  static final int GRASS = 3;
  static final int SAND = 4;
  static final int LOG = 5;

  /** The vanilla overworld's height range. */
  static final int MIN_SECTION = -4;
  static final int SECTION_COUNT = 24;

  static final int GRASS_COLOR = 0x91BD59;
  static final int FOLIAGE_COLOR = 0x77AB2F;

  /** Textures, in the order the atlas is stitched. */
  static final List<String> SPRITES = List.of(
      "stone", "dirt", "grass_block_top", "grass_block_side", "grass_block_side_overlay",
      "sand", "oak_log", "oak_log_top");

  private static final int OCCLUDER_BIT = 1 << 24;
  private static final int BRICK_VOXELS = 16 * 16 * 16;
  private static final int[] MAP_COLORS = {0, 0x7F7F7F, 0x866043, 0x7FB238, 0xF7E9A3, 0x8F7748};

  final SectionLayout layout = new SectionLayout(MIN_SECTION, SECTION_COUNT);
  final TestAtlas atlas;
  final float reachBlocks;

  private final int indirectionTex;
  private final int brickBuf, brickTex;
  private final int colorBuf, colorTex;
  private final int faceBuf, faceTex;
  private final int tintTex;
  private final int lightIndirectionTex, lightBuf, lightTex;
  private final int hfHeightTex, hfAttrTex, hfTilesTex;
  private final int atlasSampler;
  private int slots;

  TestScene(TestAtlas atlas, World world, double camX, double camZ, float reachBlocks) {
    this.atlas = atlas;
    this.reachBlocks = reachBlocks;

    indirectionTex = buildBricks(world, camX, camZ);
    brickBuf = lastBrickBuf;
    brickTex = lastBrickTex;

    colorBuf = GL33C.glGenBuffers();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, colorBuf);
    IntBuffer colors = ByteBuffer.allocateDirect((BlockClassifier.MAX_ID + 1) * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    for (int id = 1; id < MAP_COLORS.length; id++) colors.put(id, MAP_COLORS[id] | OCCLUDER_BIT);
    GL33C.glBufferData(GL33C.GL_TEXTURE_BUFFER, colors, GL33C.GL_STATIC_DRAW);
    colorTex = texBuffer(VoxelWorld.UNIT_COLORS, GL33C.GL_R32UI, colorBuf);

    faceBuf = GL33C.glGenBuffers();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, faceBuf);
    FloatBuffer faces = ByteBuffer.allocateDirect(BlockClassifier.FACE_FLOATS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    writeFaces(faces);
    GL33C.glBufferData(GL33C.GL_TEXTURE_BUFFER, faces, GL33C.GL_STATIC_DRAW);
    faceTex = texBuffer(VoxelWorld.UNIT_FACES, GL33C.GL_RGBA32F, faceBuf);

    tintTex = buildTint();

    // No light data anywhere: the shader treats unknown sections as open sky.
    lightIndirectionTex = zeroGrid(LightMap.UNIT_INDIRECTION);
    lightBuf = GL33C.glGenBuffers();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, lightBuf);
    GL33C.glBufferData(GL33C.GL_TEXTURE_BUFFER, ByteBuffer.allocateDirect(BRICK_VOXELS), GL33C.GL_STATIC_DRAW);
    lightTex = texBuffer(LightMap.UNIT_LIGHT, GL33C.GL_R8UI, lightBuf);

    // The heightfield is never read (uHfEnabled is 0) but its samplers must be complete textures.
    hfHeightTex = dummyArray(HeightField.UNIT_HEIGHT, GL33C.GL_R16I, GL33C.GL_RED_INTEGER, GL33C.GL_SHORT);
    hfAttrTex = dummyArray(HeightField.UNIT_ATTR, GL33C.GL_RGBA8, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE);
    hfTilesTex = dummyArray(HeightField.UNIT_TILES, GL33C.GL_R16I, GL33C.GL_RED_INTEGER, GL33C.GL_SHORT);

    // Same sampler object the game uses for the atlas, see VoxelWorld.bind.
    atlasSampler = GL33C.glGenSamplers();
    GL33C.glSamplerParameteri(atlasSampler, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST_MIPMAP_LINEAR);
    GL33C.glSamplerParameteri(atlasSampler, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
    GL33C.glSamplerParameteri(atlasSampler, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glSamplerParameteri(atlasSampler, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glSamplerParameterf(atlasSampler, GL33C.GL_TEXTURE_MAX_LOD, TestAtlas.MAX_LEVEL);
  }

  /** What {@link VoxelRenderer#draw} needs for a camera at {@code (x, y, z)} looking along yaw / pitch (degrees, vanilla's convention). */
  VoxelRenderer.Frame frame(int width, int height, double x, double y, double z, float yaw, float pitch, float fovY, int debug) {
    Matrix4f view = new Matrix4f().rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw + 180f));
    Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fovY), width / (float) height, 0.05f, 4096f);
    return new VoxelRenderer.Frame(
        view, projection, x, y, z,
        layout.totalRows,
        layout.params(),
        Math.min(Lod.maxDist(), reachBlocks),
        MIN_SECTION * 16,
        TestAtlas.SIZE, TestAtlas.SIZE, TestAtlas.MAX_LEVEL,
        height,
        debug,
        null,
        0f, 1f, 0f);
  }

  /** Binds everything to its unit, like {@link VoxelWorld#bind}. */
  void bind() {
    unit(VoxelWorld.UNIT_INDIRECTION, GL33C.GL_TEXTURE_3D, indirectionTex);
    unit(VoxelWorld.UNIT_BRICKS, GL33C.GL_TEXTURE_BUFFER, brickTex);
    unit(VoxelWorld.UNIT_COLORS, GL33C.GL_TEXTURE_BUFFER, colorTex);
    unit(VoxelWorld.UNIT_FACES, GL33C.GL_TEXTURE_BUFFER, faceTex);
    unit(VoxelWorld.UNIT_ATLAS, GL33C.GL_TEXTURE_2D, atlas.texture());
    GL33C.glBindSampler(VoxelWorld.UNIT_ATLAS, atlasSampler);
    unit(VoxelWorld.UNIT_TINT, GL33C.GL_TEXTURE_2D_ARRAY, tintTex);
    unit(LightMap.UNIT_INDIRECTION, GL33C.GL_TEXTURE_3D, lightIndirectionTex);
    unit(LightMap.UNIT_LIGHT, GL33C.GL_TEXTURE_BUFFER, lightTex);
    unit(HeightField.UNIT_HEIGHT, GL33C.GL_TEXTURE_2D_ARRAY, hfHeightTex);
    unit(HeightField.UNIT_ATTR, GL33C.GL_TEXTURE_2D_ARRAY, hfAttrTex);
    unit(HeightField.UNIT_TILES, GL33C.GL_TEXTURE_2D_ARRAY, hfTilesTex);
  }

  private static void unit(int unit, int target, int tex) {
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
    GL33C.glBindTexture(target, tex);
  }

  // --- Bricks ------------------------------------------------------------------------------------

  private int lastBrickBuf;
  private int lastBrickTex;

  /** Fills the pyramid levels around the camera. Returns the indirection texture. */
  private int buildBricks(World world, double camX, double camZ) {
    int grid = VoxelWorld.GRID_XZ;
    int[] cells = new int[grid * layout.totalRows * grid];
    List<ByteBuffer> bricks = new ArrayList<>();
    Map<Integer, Integer> solid = new HashMap<>();

    for (int k = 0; k < Lod.LEVELS; k++) {
      int size = 1 << k;
      int half = size >> 1;
      int csx = (int) Math.floor(camX) >> (4 + k);
      int csz = (int) Math.floor(camZ) >> (4 + k);
      int reach = (int) Math.ceil(reachBlocks / (16f * size)) + 2;
      int outer = Math.max(1, Math.min(Lod.outer(k), reach));
      int inner = Lod.inner(k);

      for (int sz = csz - outer; sz <= csz + outer; sz++) {
        for (int sx = csx - outer; sx <= csx + outer; sx++) {
          int d = Math.max(Math.abs(sx - csx), Math.abs(sz - csz));
          if (inner > 0 && d < inner) continue;

          for (int row = 0; row < layout.levelRows[k]; row++) {
            int sy = layout.levelMin[k] + row;
            ByteBuffer brick = ByteBuffer.allocateDirect(BRICK_VOXELS * 2).order(ByteOrder.nativeOrder());
            int first = -1;
            boolean uniform = true;
            for (int y = 0; y < 16; y++) {
              for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                  int bx = ((sx << 4) + x) * size + half;
                  int by = ((sy << 4) + y) * size + half;
                  int bz = ((sz << 4) + z) * size + half;
                  int id = world.idAt(bx, by, bz);
                  brick.putShort(((y * 16 + z) * 16 + x) << 1, (short) id);
                  if (first < 0) first = id;
                  else if (id != first) uniform = false;
                }
              }
            }
            if (uniform && first == 0) continue;

            int slot;
            // Like VoxelWorld: a section of one solid id shares a brick with all the others.
            if (uniform && solid.containsKey(first)) {
              slot = solid.get(first);
            } else {
              slot = bricks.size();
              bricks.add(brick);
              if (uniform) solid.put(first, slot);
            }
            cells[texel(k, sx, sy, sz)] = slot + 1;
          }
        }
      }
    }
    slots = bricks.size();

    ByteBuffer all = ByteBuffer.allocateDirect(Math.max(1, slots) * BRICK_VOXELS * 2).order(ByteOrder.nativeOrder());
    for (ByteBuffer b : bricks) all.put(b.duplicate().clear());
    all.flip();
    lastBrickBuf = GL33C.glGenBuffers();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, lastBrickBuf);
    GL33C.glBufferData(GL33C.GL_TEXTURE_BUFFER, all, GL33C.GL_STATIC_DRAW);
    lastBrickTex = texBuffer(VoxelWorld.UNIT_BRICKS, GL33C.GL_R16UI, lastBrickBuf);

    int tex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + VoxelWorld.UNIT_INDIRECTION);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, tex);
    GL33C.glTexImage3D(
        GL33C.GL_TEXTURE_3D, 0, GL33C.GL_R32UI, grid, layout.totalRows, grid, 0,
        GL33C.GL_RED_INTEGER, GL33C.GL_UNSIGNED_INT, cells);
    nearest(GL33C.GL_TEXTURE_3D);
    return tex;
  }

  /**
   * Position of a section in the memory of the indirection texture, which is {@code GRID_XZ} wide,
   * {@code totalRows} high and {@code GRID_XZ} deep (z outermost). {@link SectionLayout#cellIndex}
   * is only VoxelWorld's key for the cell, not this: the game writes cells one by one.
   */
  private int texel(int level, int sx, int sy, int sz) {
    int mask = VoxelWorld.GRID_XZ - 1;
    int row = layout.levelRow0[level] + sy - layout.levelMin[level];
    return ((sz & mask) * layout.totalRows + row) * VoxelWorld.GRID_XZ + (sx & mask);
  }

  /** Number of brick slots in use, for sanity checks that a scene is not empty. */
  int slots() {
    return slots;
  }

  // --- Face table --------------------------------------------------------------------------------

  /**
   * Sprites of a block: {down, up, north, south, west, east} base layer, then an overlay for the
   * sides (or null) and whether the top (and the overlay) follows the biome grass color.
   */
  private record Cube(String[] faces, String sideOverlay, boolean grassTint) {
    static Cube all(String s) {
      return new Cube(new String[] {s, s, s, s, s, s}, null, false);
    }
  }

  private static Cube cube(int id) {
    return switch (id) {
      case STONE -> Cube.all("stone");
      case DIRT -> Cube.all("dirt");
      case SAND -> Cube.all("sand");
      case GRASS -> new Cube(
          new String[] {"dirt", "grass_block_top", "grass_block_side", "grass_block_side", "grass_block_side", "grass_block_side"},
          "grass_block_side_overlay", true);
      case LOG -> new Cube(
          new String[] {"oak_log_top", "oak_log_top", "oak_log", "oak_log", "oak_log", "oak_log"}, null, false);
      default -> throw new IllegalArgumentException("unknown block " + id);
    };
  }

  /**
   * Maps the in-face block coordinates (a, b) to the sprite: s = s0 + sa * a + sb * b and likewise
   * t. The (a, b) axes are (x, z) on y faces, (x, y) on z faces and (y, z) on x faces, and the
   * sprites run top to bottom on the sides, as in vanilla's block models.
   */
  private static final float[][] FACE_MAP = {
    // s0, sa, sb, t0, ta, tb
    {0, 1, 0, 1, 0, -1},  // down
    {0, 1, 0, 0, 0, 1},   // up
    {1, -1, 0, 1, 0, -1}, // north
    {0, 1, 0, 1, 0, -1},  // south
    {0, 0, 1, 1, -1, 0},  // west
    {1, 0, -1, 1, -1, 0}, // east
  };

  private void writeFaces(FloatBuffer faces) {
    for (int id = STONE; id <= LOG; id++) {
      Cube c = cube(id);
      for (int face = 0; face < 6; face++) {
        int o = id * BlockClassifier.ID_FLOATS + face * BlockClassifier.LAYERS * 8;
        // Grass is tinted on its top face only, plus the overlay on the sides.
        float baseTint = c.grassTint && face == 1 ? -1f : 0xFFFFFF;
        writeLayer(faces, o, atlas.rect(c.faces[face]), FACE_MAP[face], baseTint);
        if (c.sideOverlay != null && face >= 2) {
          writeLayer(faces, o + 8, atlas.rect(c.sideOverlay), FACE_MAP[face], -1f);
        }
      }
    }
  }

  /** Two RGBA texels: (u0, v0, du/da, dv/da) and (du/db, dv/db, tint, 1), see BlockClassifier. */
  private static void writeLayer(FloatBuffer out, int o, float[] rect, float[] m, float tint) {
    float u = rect[0], v = rect[1], du = rect[2], dv = rect[3];
    out.put(o, u + du * m[0]);
    out.put(o + 1, v + dv * m[3]);
    out.put(o + 2, du * m[1]);
    out.put(o + 3, dv * m[4]);
    out.put(o + 4, du * m[2]);
    out.put(o + 5, dv * m[5]);
    out.put(o + 6, tint);
    out.put(o + 7, 1f);
  }

  // --- Small textures ----------------------------------------------------------------------------

  private static int texBuffer(int unit, int format, int buffer) {
    int tex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, tex);
    GL33C.glTexBuffer(GL33C.GL_TEXTURE_BUFFER, format, buffer);
    return tex;
  }

  private static void nearest(int target) {
    GL33C.glTexParameteri(target, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(target, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
    GL33C.glTexParameteri(target, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexParameteri(target, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
    GL33C.glTexParameteri(target, GL33C.GL_TEXTURE_WRAP_R, GL33C.GL_CLAMP_TO_EDGE);
  }

  /** An empty section grid like the indirection texture, for the light lookup. */
  private int zeroGrid(int unit) {
    int grid = VoxelWorld.GRID_XZ;
    int tex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, tex);
    GL33C.glTexImage3D(
        GL33C.GL_TEXTURE_3D, 0, GL33C.GL_R32UI, grid, layout.totalRows, grid, 0,
        GL33C.GL_RED_INTEGER, GL33C.GL_UNSIGNED_INT, new int[grid * layout.totalRows * grid]);
    nearest(GL33C.GL_TEXTURE_3D);
    return tex;
  }

  private static int dummyArray(int unit, int internal, int format, int type) {
    int tex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_ARRAY, tex);
    GL33C.glTexImage3D(GL33C.GL_TEXTURE_2D_ARRAY, 0, internal, 1, 1, 1, 0, format, type, (ByteBuffer) null);
    nearest(GL33C.GL_TEXTURE_2D_ARRAY);
    return tex;
  }

  /** One grass and foliage color everywhere; the lower-biome layers equal the upper ones. */
  private static int buildTint() {
    int size = TintMap.SIZE;
    int layers = TintMap.LAYERS;
    ByteBuffer data = ByteBuffer.allocateDirect(size * size * layers * 4);
    for (int layer = 0; layer < layers; layer++) {
      int rgb = layer == 1 || layer == 3 ? FOLIAGE_COLOR : GRASS_COLOR;
      boolean color = layer < 4;
      for (int i = 0; i < size * size; i++) {
        data.put((byte) (color ? rgb >> 16 : 0)).put((byte) (color ? rgb >> 8 : 0)).put((byte) (color ? rgb : 0));
        data.put((byte) (color ? 0xFF : 0));
      }
    }
    data.flip();

    int tex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + VoxelWorld.UNIT_TINT);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_ARRAY, tex);
    GL33C.glTexImage3D(
        GL33C.GL_TEXTURE_2D_ARRAY, 0, GL33C.GL_RGBA8, size, size, layers, 0, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, data);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_REPEAT);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_REPEAT);
    return tex;
  }

  @Override
  public void close() {
    GL33C.glDeleteTextures(new int[] {
      indirectionTex, brickTex, colorTex, faceTex, tintTex, lightIndirectionTex, lightTex,
      hfHeightTex, hfAttrTex, hfTilesTex});
    GL33C.glDeleteBuffers(new int[] {brickBuf, colorBuf, faceBuf, lightBuf});
    GL33C.glDeleteSamplers(atlasSampler);
  }
}
