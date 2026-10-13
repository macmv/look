package dev.macmv.look.gl;

import dev.macmv.look.BlockClassifier;
import dev.macmv.look.HeightField;
import dev.macmv.look.LightMap;
import dev.macmv.look.Lod;
import dev.macmv.look.SectionLayout;
import dev.macmv.look.TintMap;
import dev.macmv.look.VoxelRenderer;
import dev.macmv.look.VoxelSource;
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

  /**
   * Where bricks come from: a {@link VoxelSource} that is told which section columns will be asked
   * for first, so one that works in the background can produce them all at once.
   */
  interface BrickSource extends VoxelSource {
    /** Columns (level, sx, sz) that are about to be filled. */
    default void prepare(List<int[]> columns) {}
  }

  /** Far terrain tiles in the layout HeightField uploads, from whatever makes them. */
  interface HeightFieldSource {
    /** Whether the tile at {@code engineLevel} (texels of {@code 1 << engineLevel} blocks) is worth making: it may be on screen. */
    boolean wants(int engineLevel, int sx, int sz);

    /** All the wanted tiles of a level, so they can be produced together. */
    void prepare(int engineLevel, List<int[]> tiles);

    /** 256 i16 heights then 256 RGBA texels (grass color, kind); false if there is no terrain. */
    boolean take(int engineLevel, int sx, int sz, ByteBuffer out);
  }

  /** A function of the block position as a source. */
  static BrickSource sourceOf(World world) {
    return new BrickSource() {
      @Override
      public float reachBlocks() {
        return Float.POSITIVE_INFINITY;
      }

      @Override
      public int fill(int level, int sx, int sy, int sz, ByteBuffer out) {
        int size = 1 << level;
        int half = size >> 1;
        int solid = 0;
        for (int y = 0; y < 16; y++) {
          for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
              int id = world.idAt(((sx << 4) + x) * size + half, ((sy << 4) + y) * size + half, ((sz << 4) + z) * size + half);
              out.putShort(((y * 16 + z) * 16 + x) << 1, (short) id);
              if (id != 0) solid++;
            }
          }
        }
        return solid;
      }
    };
  }

  static final int STONE = 1;
  static final int DIRT = 2;
  static final int GRASS = 3;
  static final int SAND = 4;
  static final int LOG = 5;
  static final int RED_SAND = 6;
  static final int TERRACOTTA = 7;
  static final int GRAVEL = 8;
  static final int SNOW = 9;
  /** Water: a see-through voxel with the still-water sprite, tinted by the biome. */
  static final int WATER = 10;
  /** Leaves tinted with the foliage color, for predicted trees. */
  static final int LEAVES = 11;
  static final int LAST_BLOCK = LEAVES;

  /** The vanilla overworld's height range. */
  static final int MIN_SECTION = -4;
  static final int SECTION_COUNT = 24;

  static final int GRASS_COLOR = 0x91BD59;
  static final int FOLIAGE_COLOR = 0x77AB2F;
  static final int WATER_COLOR = 0x3F76E4;

  /** Textures, in the order the atlas is stitched. */
  static final List<String> SPRITES = List.of(
      "stone", "dirt", "grass_block_top", "grass_block_side", "grass_block_side_overlay",
      "sand", "oak_log", "oak_log_top", "red_sand", "terracotta", "gravel", "snow", "water_still", "oak_leaves");

  private static final int OCCLUDER_BIT = 1 << 24;
  private static final int BRICK_VOXELS = 16 * 16 * 16;
  private static final int[] MAP_COLORS = {
    0, 0x7F7F7F, 0x866043, 0x7FB238, 0xF7E9A3, 0x8F7748, 0xA7502D, 0x985E43, 0x808080, 0xFFFFFF, 0x4EB5D8};

  final SectionLayout layout = new SectionLayout(MIN_SECTION, SECTION_COUNT);
  final TestAtlas atlas;
  final float reachBlocks;
  private final boolean hf;

  private final int indirectionTex;
  private final int brickBuf, brickTex;
  private final int colorBuf, colorTex;
  private final int faceBuf, faceTex;
  private final int tintTex;
  private final int lightIndirectionTex, lightBuf, lightTex;
  private final int hfHeightTex, hfAttrTex, hfTilesTex, hfSuperTex;
  private final int atlasSampler;
  private int slots;

  TestScene(TestAtlas atlas, World world, double camX, double camZ, float reachBlocks) {
    this(atlas, sourceOf(world), null, camX, camZ, reachBlocks);
  }

  /** With {@code far}, the levels past the voxel ones are filled from the heightfield as well. */
  TestScene(TestAtlas atlas, BrickSource bricks, HeightFieldSource far, double camX, double camZ, float reachBlocks) {
    this.atlas = atlas;
    this.reachBlocks = reachBlocks;
    this.hf = far != null;

    indirectionTex = buildBricks(bricks, camX, camZ);
    brickBuf = lastBrickBuf;
    brickTex = lastBrickTex;

    colorBuf = GL33C.glGenBuffers();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, colorBuf);
    IntBuffer colors = ByteBuffer.allocateDirect((BlockClassifier.MAX_ID + 1) * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    for (int id = 1; id < MAP_COLORS.length; id++) colors.put(id, MAP_COLORS[id] | OCCLUDER_BIT);
    colors.put(WATER, WATER_COLOR | BlockClassifier.KIND_WATER << BlockClassifier.KIND_SHIFT | 8 << BlockClassifier.WATER_HEIGHT_SHIFT);
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

    if (far == null) {
      // The heightfield is never read (uHfEnabled is 0) but its samplers must be complete textures.
      hfHeightTex = dummyArray(HeightField.UNIT_HEIGHT, GL33C.GL_R16I, GL33C.GL_RED_INTEGER, GL33C.GL_SHORT);
      hfAttrTex = dummyArray(HeightField.UNIT_ATTR, GL33C.GL_RGBA8, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE);
      hfTilesTex = dummyArray(HeightField.UNIT_TILES, GL33C.GL_R16I, GL33C.GL_RED_INTEGER, GL33C.GL_SHORT);
      hfSuperTex = dummyArray(HeightField.UNIT_SUPER, GL33C.GL_R16I, GL33C.GL_RED_INTEGER, GL33C.GL_SHORT);
    } else {
      hfHeightTex = hfArray(HeightField.UNIT_HEIGHT, GL33C.GL_R16I, HeightField.SIZE);
      hfAttrTex = hfArray(HeightField.UNIT_ATTR, GL33C.GL_RGBA8, HeightField.SIZE);
      hfTilesTex = hfArray(HeightField.UNIT_TILES, GL33C.GL_R16I, HeightField.WINDOW_TILES);
      hfSuperTex = hfArray(HeightField.UNIT_SUPER, GL33C.GL_R16I, HeightField.SUPER_CELLS);
      fillHeightField(far, camX, camZ);
    }

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
        hf ? HF_IDS : null,
        0f, 1f, 0f,
        WATER,
        new float[] {0.02f, 0.02f, 0.2f}, -8f, 96f,
        63f);
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
    unit(HeightField.UNIT_SUPER, GL33C.GL_TEXTURE_2D_ARRAY, hfSuperTex);
  }

  private static void unit(int unit, int target, int tex) {
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
    GL33C.glBindTexture(target, tex);
  }

  // --- Bricks ------------------------------------------------------------------------------------

  private int lastBrickBuf;
  private int lastBrickTex;

  /** Fills the pyramid levels around the camera. Returns the indirection texture. */
  private int buildBricks(BrickSource source, double camX, double camZ) {
    int grid = VoxelWorld.GRID_XZ;
    int[] cells = new int[grid * layout.totalRows * grid];
    List<ByteBuffer> bricks = new ArrayList<>();
    Map<Integer, Integer> solid = new HashMap<>();

    // The columns of each level's ring: what the world would keep resident around the camera.
    List<int[]> columns = new ArrayList<>();
    for (int k = 0; k < Lod.LEVELS; k++) {
      int csx = (int) Math.floor(camX) >> (4 + k);
      int csz = (int) Math.floor(camZ) >> (4 + k);
      int reach = (int) Math.ceil(reachBlocks / (16f * (1 << k))) + 2;
      int outer = Math.max(1, Math.min(Lod.outer(k), reach));
      int inner = Lod.inner(k);
      for (int sz = csz - outer; sz <= csz + outer; sz++) {
        for (int sx = csx - outer; sx <= csx + outer; sx++) {
          int d = Math.max(Math.abs(sx - csx), Math.abs(sz - csz));
          if (inner > 0 && d < inner) continue;
          columns.add(new int[] {k, sx, sz});
        }
      }
    }
    source.prepare(columns);

    for (int[] col : columns) {
      int k = col[0], sx = col[1], sz = col[2];
      for (int row = 0; row < layout.levelRows[k]; row++) {
        int sy = layout.levelMin[k] + row;
        ByteBuffer brick = ByteBuffer.allocateDirect(BRICK_VOXELS * 2).order(ByteOrder.nativeOrder());
        int n = source.fill(k, sx, sy, sz, brick);
        if (n < 0) throw new IllegalStateException("source has no data for level " + k + " section " + sx + " " + sy + " " + sz);
        if (n == 0) continue;

        int first = brick.getShort(0) & 0xFFFF;
        boolean uniform = true;
        for (int i = 1; i < BRICK_VOXELS && uniform; i++) uniform = (brick.getShort(i << 1) & 0xFFFF) == first;

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

  // --- Heightfield -------------------------------------------------------------------------------

  /**
   * SeedSource.voxelIds layout: a top and a filler id per surface kind (grass, sand, red sand,
   * gravel, snow, stone), then stone and water.
   */
  static final int[] HF_IDS = {
    GRASS, DIRT, SAND, SAND, RED_SAND, TERRACOTTA, GRAVEL, GRAVEL, SNOW, DIRT, STONE, STONE, STONE, WATER};

  private static int hfArray(int unit, int internal, int size) {
    int tex = GL33C.glGenTextures();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D_ARRAY, tex);
    int format = internal == GL33C.GL_RGBA8 ? GL33C.GL_RGBA : GL33C.GL_RED_INTEGER;
    int type = internal == GL33C.GL_RGBA8 ? GL33C.GL_UNSIGNED_BYTE : GL33C.GL_SHORT;
    GL33C.glTexImage3D(GL33C.GL_TEXTURE_2D_ARRAY, 0, internal, size, size, HeightField.LEVELS, 0, format, type, (ByteBuffer) null);
    nearest(GL33C.GL_TEXTURE_2D_ARRAY);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_REPEAT);
    GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D_ARRAY, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_REPEAT);
    return tex;
  }

  /**
   * Fills the three heightfield arrays from the tiles HeightField asks for around the camera: texels
   * of {@code 2^(level - FINER_SHIFT)} blocks, a window of 128 x 128 tiles of 16 x 16 texels per
   * level, wrapping, and the highest texel of each tile (HeightField.NONE where there is no tile).
   */
  private void fillHeightField(HeightFieldSource far, double camX, double camZ) {
    int size = HeightField.SIZE;
    int tiles = HeightField.WINDOW_TILES;
    ByteBuffer heights = ByteBuffer.allocateDirect(size * size * 2).order(ByteOrder.nativeOrder());
    ByteBuffer attrs = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());
    ByteBuffer maxes = ByteBuffer.allocateDirect(tiles * tiles * 2).order(ByteOrder.nativeOrder());
    ByteBuffer tile = ByteBuffer.allocateDirect(HeightField.TILE * HeightField.TILE * 6).order(ByteOrder.nativeOrder());
    int texels = HeightField.TILE * HeightField.TILE;

    for (int i = 0; i < HeightField.LEVELS; i++) {
      // Heightfield level i is engine level i: pyramid level LEVELS + i, texels FINER_SHIFT finer.
      int j = HeightField.pyramidLevel(i) - HeightField.FINER_SHIFT;
      int tileBlocks = HeightField.TILE << j;
      int cx = Math.floorDiv((int) Math.floor(camX), tileBlocks);
      int cz = Math.floorDiv((int) Math.floor(camZ), tileBlocks);
      // The nearest tile HeightField.update asks for.
      int inner = (int) (Lod.BASE_BLOCKS * (1 << (HeightField.pyramidLevel(i) - 1)) / tileBlocks) - 2;

      for (int n = 0; n < size * size; n++) heights.putShort(n * 2, HeightField.NONE);
      for (int n = 0; n < tiles * tiles; n++) maxes.putShort(n * 2, HeightField.NONE);
      for (int n = 0; n < size * size * 4; n++) attrs.put(n, (byte) 0);

      List<int[]> wanted = new ArrayList<>();
      for (int sz = cz - tiles / 2; sz < cz + tiles / 2; sz++) {
        for (int sx = cx - tiles / 2; sx < cx + tiles / 2; sx++) {
          if (Math.max(Math.abs(sx - cx), Math.abs(sz - cz)) < inner) continue;
          if (far.wants(j, sx, sz)) wanted.add(new int[] {sx, sz});
        }
      }
      far.prepare(j, wanted);

      for (int[] t : wanted) {
        tile.clear();
        if (!far.take(j, t[0], t[1], tile)) continue;

        short max = HeightField.NONE;
        for (int ty = 0; ty < HeightField.TILE; ty++) {
          for (int tx = 0; tx < HeightField.TILE; tx++) {
            int n = ty * HeightField.TILE + tx;
            short h = tile.getShort(n * 2);
            int ax = t[0] * HeightField.TILE + tx;
            int az = t[1] * HeightField.TILE + ty;
            int at = (az & (size - 1)) * size + (ax & (size - 1));
            heights.putShort(at * 2, h);
            for (int c = 0; c < 4; c++) attrs.put(at * 4 + c, tile.get(texels * 2 + n * 4 + c));
            if (h > max) max = h;
          }
        }
        maxes.putShort(((t[1] & (tiles - 1)) * tiles + (t[0] & (tiles - 1))) * 2, max);
      }

      GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
      upload(HeightField.UNIT_HEIGHT, i, size, GL33C.GL_RED_INTEGER, GL33C.GL_SHORT, heights);
      upload(HeightField.UNIT_ATTR, i, size, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, attrs);
      upload(HeightField.UNIT_TILES, i, tiles, GL33C.GL_RED_INTEGER, GL33C.GL_SHORT, maxes);
      int sc = HeightField.SUPER_CELLS;
      ByteBuffer supers = ByteBuffer.allocateDirect(sc * sc * 2).order(ByteOrder.nativeOrder());
      for (int sz = 0; sz < sc; sz++) {
        for (int sx = 0; sx < sc; sx++) {
          short m = HeightField.NONE;
          for (int z = 0; z < HeightField.SUPER; z++) {
            for (int x = 0; x < HeightField.SUPER; x++) {
              m = (short) Math.max(m, maxes.getShort(((sz * HeightField.SUPER + z) * tiles + sx * HeightField.SUPER + x) * 2));
            }
          }
          supers.putShort((sz * sc + sx) * 2, m);
        }
      }
      upload(HeightField.UNIT_SUPER, i, sc, GL33C.GL_RED_INTEGER, GL33C.GL_SHORT, supers);
      GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 4);
    }
  }

  private static void upload(int unit, int layer, int side, int format, int type, ByteBuffer data) {
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
    GL33C.glTexSubImage3D(GL33C.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, side, side, 1, format, type, data);
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
      case RED_SAND -> Cube.all("red_sand");
      case TERRACOTTA -> Cube.all("terracotta");
      case GRAVEL -> Cube.all("gravel");
      case SNOW -> Cube.all("snow");
      case WATER -> Cube.all("water_still");
      case LEAVES -> Cube.all("oak_leaves");
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
    for (int id = STONE; id <= LAST_BLOCK; id++) {
      Cube c = cube(id);
      for (int face = 0; face < 6; face++) {
        int o = id * BlockClassifier.ID_FLOATS + face * BlockClassifier.LAYERS * 8;
        // Grass is tinted on its top face only, plus the overlay on the sides.
        float baseTint = id == WATER ? -3f : id == LEAVES ? -2f : c.grassTint && face == 1 ? -1f : 0xFFFFFF;
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
      int rgb = layer == 1 || layer == 3 ? FOLIAGE_COLOR : (layer == TintMap.WATER_LAYER ? WATER_COLOR : GRASS_COLOR);
      boolean color = layer < 4 || layer == TintMap.WATER_LAYER;
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
      hfHeightTex, hfAttrTex, hfTilesTex, hfSuperTex});
    GL33C.glDeleteBuffers(new int[] {brickBuf, colorBuf, faceBuf, lightBuf});
    GL33C.glDeleteSamplers(atlasSampler);
  }
}
