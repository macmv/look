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
  private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");
  private static final Pattern UV2 = Pattern.compile("\\bin\\s+ivec2\\s+UV2\\s*;");
  private static final Pattern FOG_IMPORT = Pattern.compile("(?m)^#moj_import <fog\\.glsl>[ \\t]*$");
  private static final Pattern EYE = Pattern.compile("ModelViewMat\\s*\\*\\s*vec4\\(([^;]*?),\\s*1\\.0\\s*\\)");

  /** Names of the vertex programs {@link #patch} changed. */
  private static final Set<String> PATCHED = ConcurrentHashMap.newKeySet();

  /** Uniform locations of one program. */
  private record Locations(int on, int shadow, int waterColor, int view, int camBlock, int camFrac, int window, int water, int waves, int time, int pixel) {
    static Locations of(int program) {
      return new Locations(
          GL33C.glGetUniformLocation(program, "LookOn"),
          GL33C.glGetUniformLocation(program, "LookShadow"),
          GL33C.glGetUniformLocation(program, "LookWaterColor"),
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
  /** Per program, the locations of the shadow uniforms by name (see {@link ShadowPass#setEntityUniforms}). */
  private static final Map<Integer, Map<String, Integer>> shadowLocations = new HashMap<>();
  /** Per program, the {@link #epoch} its uniforms were last set for. */
  private static final Map<Integer, Integer> uploaded = new HashMap<>();
  /** Bumped whenever the values change (every frame it is on, and when it goes off). */
  private static int epoch;

  private static boolean active;
  /** Whether the vertices move, i.e. {@link EnhancedSettings#waterRefraction}; the shading is on whenever {@link #active}. */
  private static boolean refract;
  private static final float[] waterColor = new float[3];
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
   * Rewrites a vanilla core shader: the vertex stage moves its vertices through the water, the fragment
   * stage shades what is under it and in shadow. Returns the source unchanged for shaders that are not
   * patched. A fragment stage is only patched when the vertex stage of the same name was, since it reads
   * what that one writes (vanilla loads the vertex stage first).
   */
  public static String patch(Program.Type type, String name, String source) {
    String shader = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    if (!WANTED.matcher(shader).matches()) return source;
    return type == Program.Type.VERTEX ? patchVertex(name, source) : patchFragment(name, source);
  }

  private static String patchVertex(String name, String source) {
    Matcher m = EYE.matcher(source);
    Matcher main = MAIN.matcher(source);
    if (!m.find() || !main.find()) return source;
    // Programs are made again on a resource reload, and their ids can be reused.
    locations.clear();
    uploaded.clear();
    shadowLocations.clear();
    String body = m.replaceAll(r -> "look_eye(ModelViewMat, " + Matcher.quoteReplacement(r.group(1)) + ")");

    // The values for the fragment stage start out neutral. Vanilla's light is in the UV2 of the vertex.
    String init = " look_init();" + (UV2.matcher(body).find() ? " look_sky = clamp(float(UV2.y) / 240.0, 0.0, 1.0);" : "");
    body = MAIN.matcher(body).replaceFirst(r -> Matcher.quoteReplacement(r.group() + init));

    // Right after #version, which has to stay the first line.
    int eol = body.indexOf('\n') + 1;
    PATCHED.add(name);
    return body.substring(0, eol) + glsl() + body.substring(eol);
  }

  private static String patchFragment(String name, String source) {
    if (!PATCHED.contains(name) || !source.contains("linear_fog(")) return source;
    Matcher fog = FOG_IMPORT.matcher(source);
    if (!fog.find()) return source;

    // The shading goes in front of the fog, so linear_fog itself becomes the wrapper from here on.
    String shade = VoxelRenderer.resolveIncludes(VoxelRenderer.readShader("entity_shade.glsl"))
        + "\nvec4 look_fog(vec4 c, float d, float s, float e, vec4 fc) { return linear_fog(look_shade(c), d, s, e, fc); }\n"
        + "#define linear_fog look_fog\n";
    return source.substring(0, fog.end()) + "\n" + shade + source.substring(fog.end());
  }

  /** The fragment GLSL pasted in by {@link #patch}, for tests (needs the declarations of fog.glsl before it). */
  public static String fragmentGlsl() {
    return VoxelRenderer.resolveIncludes(VoxelRenderer.readShader("entity_shade.glsl"));
  }

  /** The GLSL pasted into the patched shaders: the uniforms and {@code look_eye}. */
  public static String glsl() {
    return VoxelRenderer.resolveIncludes(VoxelRenderer.readShader("entity_refract.glsl"));
  }

  /** Turns the refraction and the shading on for the entities and particles drawn from now to {@link #end}. */
  static void begin(VoxelRenderer.Frame f) {
    ClientLevel level = Minecraft.getInstance().level;
    if (level == null) return;

    refract = EnhancedSettings.waterRefraction;
    if (refract) {
      long tick = level.getGameTime();
      int cx = (int) Math.floor(f.camX());
      int cz = (int) Math.floor(f.camZ());
      boolean stale = builtFor != level
          || tick - builtTick >= REBUILD_TICKS
          || tick < builtTick
          || Math.abs(cx - (windowX + SIZE / 2)) > REBUILD_DISTANCE
          || Math.abs(cz - (windowZ + SIZE / 2)) > REBUILD_DISTANCE;
      if (stale) rebuild(level, cx, cz, tick);
    }

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

    int rgb = level.getBiome(BlockPos.containing(f.camX(), f.camY(), f.camZ())).value().getWaterColor();
    waterColor[0] = ((rgb >> 16) & 0xff) / 255f;
    waterColor[1] = ((rgb >> 8) & 0xff) / 255f;
    waterColor[2] = (rgb & 0xff) / 255f;

    // The caller restores the active unit.
    if (refract) {
      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT);
      GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
    }
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
    GL33C.glUniform1i(l.on(), active && refract ? 1 : 0);
    GL33C.glUniform1i(l.shadow(), active ? 1 : 0);
    if (!active) return;

    Map<String, Integer> names = shadowLocations.computeIfAbsent(program, id -> new HashMap<>());
    ShadowPass.setEntityUniforms(n -> names.computeIfAbsent(n, k -> GL33C.glGetUniformLocation(program, k)));
    GL33C.glUniform3f(l.waterColor(), waterColor[0], waterColor[1], waterColor[2]);

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
