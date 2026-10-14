package dev.macmv.look;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Program;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Matrix3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33C;

/**
 * Refracts everything vanilla draws as triangles (entities, block entities, particles, the block
 * outline) through the water surface, so it lines up with the terrain the raycast pass refracts.
 *
 * <p>Vanilla's core shaders are patched as they are loaded ({@link #patch}, called from
 * {@code ProgramMixin}): the {@code ModelViewMat * vec4(Position, 1.0)} of each one becomes
 * {@code look_eye(ModelViewMat, Position)}, which moves the vertex to where the water shows it
 * ({@code entity_refract.glsl} has the optics). That is per vertex, so no entity needs its own shader
 * and nothing is drawn twice.
 *
 * <p>The shader needs the height of the water over a column, which is a small texture around the camera
 * built from the client level ({@link #rebuild}). It is only on between the raycast pass and the end of
 * level rendering ({@link #begin}, {@link #end}): the hand, the GUI and inventory models draw with the
 * same programs and must not move.
 */
public final class EntityRefraction {
  /** Texture unit of the water heights, on raw GL like the other data textures (GlStateManager tracks 12). */
  public static final int UNIT = 28;
  /** Blocks per side of the window of columns, must match LOOK_SIZE in entity_refract.glsl. */
  static final int SIZE = 128;
  /** Value of a column with no water, must match LOOK_NO_WATER in entity_refract.glsl. */
  private static final float NO_WATER = -1.0e4f;
  /** The window is rebuilt when the camera is this far from its center, or this many ticks after the last build. */
  private static final int REBUILD_DISTANCE = 16;
  private static final long REBUILD_TICKS = 40;

  /**
   * The vanilla core shaders patched: everything that draws a world object after the terrain. The
   * terrain layers, sky, clouds and GUI shaders are left alone.
   */
  private static final Pattern WANTED = Pattern.compile(
      "rendertype_(entity_.*|armor_.*|glint.*|eyes|energy_swirl|item_entity_translucent_cull|leash|lightning|"
          + "lines|crumbling|outline|text.*|beacon_beam|breeze_wind|translucent_moving_block)|particle");
  private static final Pattern EYE = Pattern.compile("ModelViewMat\\s*\\*\\s*vec4\\(([^;]*?),\\s*1\\.0\\s*\\)");

  /** Names of the vertex programs {@link #patch} changed. */
  private static final Set<String> PATCHED = ConcurrentHashMap.newKeySet();

  /** Uniform locations of one program. */
  private record Locations(int on, int view, int camBlock, int camFrac, int window, int water, int waves, int time, int pixel) {
    static Locations of(int program) {
      return new Locations(
          GL33C.glGetUniformLocation(program, "LookOn"),
          GL33C.glGetUniformLocation(program, "LookView"),
          GL33C.glGetUniformLocation(program, "LookCamBlock"),
          GL33C.glGetUniformLocation(program, "LookCamFrac"),
          GL33C.glGetUniformLocation(program, "LookWindow"),
          GL33C.glGetUniformLocation(program, "LookWater"),
          GL33C.glGetUniformLocation(program, "uWaterWaves"),
          GL33C.glGetUniformLocation(program, "LookTime"),
          GL33C.glGetUniformLocation(program, "LookPixelAngle"));
    }
  }

  private static final Map<Integer, Locations> locations = new HashMap<>();
  /** Per program, the {@link #epoch} its uniforms were last set for. */
  private static final Map<Integer, Integer> uploaded = new HashMap<>();
  /** Bumped whenever the values change (every frame it is on, and when it goes off). */
  private static int epoch;

  private static boolean active;
  private static final float[] view = new float[9];
  private static int camBlockX, camBlockY, camBlockZ;
  private static float camFracX, camFracY, camFracZ;
  private static float time;
  private static float pixelAngle;
  private static float waves;

  private static int texture;
  private static final FloatBuffer data = BufferUtils.createFloatBuffer(SIZE * SIZE);
  private static ClientLevel builtFor;
  private static int windowX, windowZ;
  private static long builtTick = Long.MIN_VALUE;

  private EntityRefraction() {}

  /**
   * Rewrites the vertex stage of a vanilla core shader. Returns the source unchanged for shaders that
   * are not refracted.
   */
  public static String patch(Program.Type type, String name, String source) {
    if (type != Program.Type.VERTEX) return source;
    String shader = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    if (!WANTED.matcher(shader).matches()) return source;

    Matcher m = EYE.matcher(source);
    if (!m.find()) return source;
    // Programs are made again on a resource reload, and their ids can be reused.
    locations.clear();
    uploaded.clear();
    String body = m.replaceAll(r -> "look_eye(ModelViewMat, " + Matcher.quoteReplacement(r.group(1)) + ")");

    // Right after #version, which has to stay the first line.
    int eol = body.indexOf('\n') + 1;
    PATCHED.add(name);
    return body.substring(0, eol) + glsl() + body.substring(eol);
  }

  /** The GLSL pasted into the patched shaders: the uniforms and {@code look_eye}. */
  public static String glsl() {
    return VoxelRenderer.resolveIncludes(VoxelRenderer.readShader("entity_refract.glsl"));
  }

  /** Turns the refraction on for the entities and particles drawn from now to {@link #end}. */
  static void begin(VoxelRenderer.Frame f) {
    ClientLevel level = Minecraft.getInstance().level;
    if (level == null || !EnhancedSettings.waterRefraction) return;

    long tick = level.getGameTime();
    int cx = (int) Math.floor(f.camX());
    int cz = (int) Math.floor(f.camZ());
    boolean stale = builtFor != level
        || tick - builtTick >= REBUILD_TICKS
        || tick < builtTick
        || Math.abs(cx - (windowX + SIZE / 2)) > REBUILD_DISTANCE
        || Math.abs(cz - (windowZ + SIZE / 2)) > REBUILD_DISTANCE;
    if (stale) rebuild(level, cx, cz, tick);

    new Matrix3f(f.view()).get(view);
    camBlockX = (int) Math.floor(f.camX());
    camBlockY = (int) Math.floor(f.camY());
    camBlockZ = (int) Math.floor(f.camZ());
    camFracX = (float) (f.camX() - camBlockX);
    camFracY = (float) (f.camY() - camBlockY);
    camFracZ = (float) (f.camZ() - camBlockZ);
    time = f.time();
    pixelAngle = 2f / (f.projection().m11() * Math.max(1, f.viewportHeight()));
    waves = EnhancedSettings.waterWaves;

    // The caller restores the active unit.
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
    active = true;
    epoch++;
  }

  /** Back to vanilla's positions: the hand and the GUI draw with the same programs. */
  public static void end() {
    if (!active) return;
    active = false;
    epoch++;
  }

  /**
   * Called when vanilla has applied a shader (its program is in use): hands the patched ones this
   * frame's camera and water. Cheap for the programs already up to date.
   */
  public static void onApply(ShaderInstance shader, Program vertex) {
    if (!PATCHED.contains(vertex.getName())) return;

    int program = shader.getId();
    Integer done = uploaded.get(program);
    if (done != null && done == epoch) return;
    uploaded.put(program, epoch);

    Locations l = locations.computeIfAbsent(program, Locations::of);
    GL33C.glUniform1i(l.on(), active ? 1 : 0);
    if (!active) return;

    GL33C.glUniformMatrix3fv(l.view(), false, view);
    GL33C.glUniform3i(l.camBlock(), camBlockX, camBlockY, camBlockZ);
    GL33C.glUniform3f(l.camFrac(), camFracX, camFracY, camFracZ);
    GL33C.glUniform2i(l.window(), windowX, windowZ);
    GL33C.glUniform1i(l.water(), UNIT);
    GL33C.glUniform1f(l.waves(), waves);
    GL33C.glUniform1f(l.time(), time);
    GL33C.glUniform1f(l.pixel(), pixelAngle);
  }

  /**
   * Fills the window of columns around the block ({@code cx}, {@code cz}) with the height of the water
   * surface: the top of the highest block of each column when that is water, as the raycast shader
   * has it (a full voxel). Water under a roof or a bridge is not seen.
   */
  private static void rebuild(ClientLevel level, int cx, int cz, long tick) {
    if (texture == 0) {
      texture = GL33C.glGenTextures();
      int prev = GlStateManager._getActiveTexture();
      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT);
      GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
      GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_R32F, SIZE, SIZE, 0, GL33C.GL_RED, GL33C.GL_FLOAT, (FloatBuffer) null);
      GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
      GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
      GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
      GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
      GL33C.glActiveTexture(prev);
    }

    windowX = cx - SIZE / 2;
    windowZ = cz - SIZE / 2;
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    data.clear();
    for (int z = 0; z < SIZE; z++) {
      for (int x = 0; x < SIZE; x++) {
        int bx = windowX + x;
        int bz = windowZ + z;
        float surface = NO_WATER;
        LevelChunk chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
        if (chunk != null) {
          int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
          if (chunk.getFluidState(pos.set(bx, top, bz)).is(FluidTags.WATER)) surface = top + 1;
        }
        data.put(surface);
      }
    }
    data.flip();

    int prev = GlStateManager._getActiveTexture();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
    GL33C.glTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL33C.GL_RED, GL33C.GL_FLOAT, data);
    GL33C.glActiveTexture(prev);

    builtFor = level;
    builtTick = tick;
  }
}
