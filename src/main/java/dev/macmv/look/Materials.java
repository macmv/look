package dev.macmv.look;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import org.lwjgl.opengl.GL33C;

/**
 * Integrated PBR (docs/enhanced.md 5.1): a material record per voxel id, with no resource pack
 * involved. {@link BlockClassifier} calls {@link #classify} for every id it hands out; the shader
 * reads the packed result through {@code materialOf(id)} (Enhanced program only).
 *
 * <p>The table is one u32 per voxel id, uploaded as an {@code R32UI} texture buffer on
 * {@link #UNIT}. Layout, low bits first:
 * <pre>
 *   0..7    roughness, 0 (mirror) .. 255 (fully rough)
 *   8..15   reflectance F0 of a dielectric, 0..255 = 0..1 (0.04 is typical). Ignored for metals.
 *   16..23  emission strength, 0..255 = 0..{@link #EMISSION_MAX} (a multiple of the albedo, in HDR)
 *   24      metal: F0 is the albedo itself and there is no diffuse term
 *   25      subsurface: leaves, plants, wool; light shines through when the sun is behind
 *   26      flicker: torches, campfires, lanterns; block light should wobble
 *   27..29  wetness response, 0..7 = 0..1: how much rain darkens and smooths it (0 for glass, ice, metal)
 *   30      texel emission: only the bright, saturated texels glow (ore flecks, flames)
 *   31      texel smoothness: only the bright, saturated texels are smooth (gems in an ore)
 * </pre>
 *
 * <p>Only emission is consumed so far. Roughness, F0, metal, subsurface, flicker and wetness are
 * stored for the specular, reflection, subsurface and weather work to read.
 */
public final class Materials {
  /** Texture unit of the table. Above the units the other tables use (10..23). */
  public static final int UNIT = 31;
  /** Emission strength of byte 255. */
  public static final float EMISSION_MAX = 4f;

  /** What a block looks like to the lighting model. All values are 0..1 except emission. */
  public record Material(
      float roughness,
      float f0,
      float emission,
      boolean metal,
      boolean subsurface,
      boolean flicker,
      float wetness,
      boolean texelEmission,
      boolean texelSmoothness) {}

  /** Stone-ish defaults for blocks that nothing else matches. */
  public static final Material DEFAULT = new Material(0.85f, 0.04f, 0f, false, false, false, 0.8f, false, false);

  private static final int[] table = new int[BlockClassifier.MAX_ID + 1];
  private static boolean dirty = true;
  private static int buf;
  private static int tex;

  static {
    Arrays.fill(table, pack(DEFAULT));
  }

  private Materials() {}

  // --- Packing ---------------------------------------------------------------------------------

  private static int byteOf(float v, float max) {
    return Math.max(0, Math.min(255, Math.round(v / max * 255f)));
  }

  public static int pack(Material m) {
    int p = byteOf(m.roughness(), 1f);
    p |= byteOf(m.f0(), 1f) << 8;
    p |= byteOf(m.emission(), EMISSION_MAX) << 16;
    if (m.metal()) p |= 1 << 24;
    if (m.subsurface()) p |= 1 << 25;
    if (m.flicker()) p |= 1 << 26;
    p |= Math.max(0, Math.min(7, Math.round(m.wetness() * 7f))) << 27;
    if (m.texelEmission()) p |= 1 << 30;
    if (m.texelSmoothness()) p |= 1 << 31;
    return p;
  }

  /** The inverse of {@link #pack}, up to the quantization. */
  public static Material unpack(int p) {
    return new Material(
        (p & 255) / 255f,
        ((p >> 8) & 255) / 255f,
        ((p >> 16) & 255) / 255f * EMISSION_MAX,
        (p & 1 << 24) != 0,
        (p & 1 << 25) != 0,
        (p & 1 << 26) != 0,
        ((p >> 27) & 7) / 7f,
        (p & 1 << 30) != 0,
        (p & 1 << 31) != 0);
  }

  // --- Classification --------------------------------------------------------------------------

  private static boolean has(String n, String... parts) {
    for (String s : parts) if (n.contains(s)) return true;
    return false;
  }

  /**
   * The material of a block, by its registry path (no namespace, so modded blocks with the usual
   * names are matched too).
   *
   * @param light vanilla's light emission of the state, 0..15; anything that gives light glows
   * @param plant a non-solid plant model (grass, flowers, saplings)
   */
  public static Material classify(String n, int light, boolean plant) {
    float rough = 0.85f, f0 = 0.04f, emit = 0f, wet = 0.8f;
    boolean metal = false, sss = false, flicker = false, tEmit = false, tSmooth = false;

    // --- Class defaults, from the most general to the most specific.
    if (has(n, "leaves")) {
      rough = 0.9f; f0 = 0.02f; sss = true; wet = 0.4f;
    } else if (plant || has(n, "grass", "fern", "vine", "flower", "sapling", "roots", "bush", "lichen", "moss", "kelp", "seagrass", "tulip", "azalea", "crop", "wheat", "stem", "lily")
        && !has(n, "grass_block")) {
      rough = 0.9f; f0 = 0.02f; sss = true; wet = 0.4f;
    } else if (has(n, "wool", "carpet", "banner") || n.endsWith("_bed")) {
      rough = 1f; f0 = 0.02f; sss = true; wet = 0.9f;
    } else if (has(n, "log", "wood", "planks", "hyphae", "door", "fence", "sign", "ladder", "bookshelf", "barrel", "crafting_table")) {
      rough = 0.75f; f0 = 0.03f; wet = 0.6f;
    } else if (has(n, "sand", "gravel", "dirt", "mud", "podzol", "mycelium", "clay", "farmland", "grass_block", "snow")) {
      rough = n.contains("snow") ? 0.7f : 0.95f; f0 = n.contains("snow") ? 0.05f : 0.03f; wet = n.contains("snow") ? 0.2f : 1f;
    }

    // Glass and ice.
    if (has(n, "glass")) {
      rough = 0.02f; f0 = 0.05f; wet = 0f;
    } else if (n.equals("blue_ice")) {
      rough = 0.08f; f0 = 0.06f; wet = 0f;
    } else if (n.equals("ice")) {
      rough = 0.05f; f0 = 0.05f; wet = 0f;
    } else if (n.equals("packed_ice")) {
      rough = 0.15f; f0 = 0.05f; wet = 0f;
    }

    // Metals: F0 is the albedo. Copper weathers from smooth to rough.
    if (has(n, "iron_block", "gold_block", "netherite_block", "copper", "anvil", "iron_bars", "chain", "cauldron", "hopper", "lantern", "iron_door", "iron_trapdoor", "heavy_core", "lightning_rod", "bell")
        && !has(n, "ore", "raw_", "copper_bulb", "sea_", "jack_o", "command_block")) {
      metal = true; wet = 0.1f; f0 = 1f;
      if (has(n, "gold", "bell")) rough = 0.2f;
      else if (has(n, "netherite")) rough = 0.35f;
      else if (has(n, "oxidized")) rough = 0.65f;
      else if (has(n, "weathered")) rough = 0.5f;
      else if (has(n, "exposed")) rough = 0.4f;
      else if (has(n, "copper")) rough = 0.3f;
      else rough = 0.25f;
      if (has(n, "chain", "bars", "lantern", "hopper", "cauldron", "anvil")) rough = Math.max(rough, 0.4f);
    }

    // Gems and polished or glazed materials.
    if (has(n, "diamond_block", "emerald_block", "lapis_block")) {
      rough = 0.12f; f0 = 0.08f; wet = 0.1f;
    } else if (has(n, "amethyst") && !has(n, "cluster", "bud")) {
      rough = 0.1f; f0 = 0.07f; wet = 0.1f;
    } else if (n.equals("obsidian") || n.equals("crying_obsidian")) {
      rough = 0.1f; f0 = 0.06f; wet = 0.2f;
    } else if (has(n, "quartz", "purpur") && !has(n, "ore")) {
      rough = 0.2f; f0 = 0.05f; wet = 0.3f;
    } else if (has(n, "glazed_terracotta")) {
      rough = 0.15f; f0 = 0.05f; wet = 0.2f;
    } else if (has(n, "polished", "smooth_", "_bricks", "chiseled")) {
      rough = 0.35f; f0 = 0.04f; wet = 0.7f;
    } else if (has(n, "concrete") && !has(n, "powder")) {
      rough = 0.7f; wet = 0.7f;
    } else if (has(n, "prismarine")) {
      rough = 0.4f; wet = 0.6f;
    }

    // Ores: rough host stone, smooth gems. The shader tells them apart by the texel.
    if (n.endsWith("_ore") || n.equals("ancient_debris")) {
      rough = 0.6f; f0 = 0.05f; tSmooth = true; wet = 0.8f;
    }
    if (n.equals("redstone_ore") || n.equals("deepslate_redstone_ore")) {
      tEmit = true; emit = 1.6f;
    }

    // --- Emission. Cubes that glow all over, then flames and flecks by texel, then any other light.
    if (has(n, "glowstone", "sea_lantern", "shroomlight", "froglight", "jack_o_lantern", "redstone_lamp", "beacon")) {
      emit = n.contains("sea_lantern") ? 1.3f : (n.contains("froglight") ? 1.5f : 1.2f);
      if (n.contains("glowstone")) emit = 1.5f;
      rough = n.contains("froglight") || n.contains("beacon") || n.contains("sea_lantern") ? 0.3f : 0.6f;
    } else if (n.equals("lava") || n.equals("lava_cauldron")) {
      emit = 2f; rough = 0.3f; f0 = 0.02f; wet = 0f;
    } else if (n.equals("magma_block")) {
      emit = 1.4f; tEmit = true; rough = 0.8f;
    } else if (has(n, "torch", "campfire", "candle", "fire", "end_rod", "lantern", "respawn_anchor", "crying_obsidian", "conduit")) {
      // Light only the bright texels: the flame, not the stick.
      emit = n.contains("end_rod") ? 1.6f : 2f;
      tEmit = true;
      if (has(n, "torch", "campfire", "candle", "fire", "lantern")) flicker = true;
      if (n.contains("end_rod")) { rough = 0.3f; f0 = 0.05f; }
    } else if (light > 0 && emit == 0f) {
      // Unknown light source (modded, lit furnaces, sculk, glow lichen ...): glow by texel.
      emit = 0.4f + 1.2f * light / 15f;
      tEmit = true;
    }
    // Lit versions (candles, torches) only glow when the state gives light.
    if (light <= 0 && emit > 0f && !n.equals("lava") && !n.equals("lava_cauldron") && !n.equals("redstone_ore") && !n.equals("deepslate_redstone_ore")
        && !n.equals("magma_block")) {
      emit = 0f; tEmit = false; flicker = false;
    }

    return new Material(rough, f0, emit, metal, sss, flicker, wet, tEmit, tSmooth);
  }

  // --- Table -----------------------------------------------------------------------------------

  /** Sets the material of voxel id {@code id}. */
  public static void set(int id, Material m) {
    if (id <= 0 || id >= table.length) return;
    table[id] = pack(m);
    dirty = true;
  }

  /** {@code to} gets the material of {@code from}, for ids made by copying another. */
  public static void copy(int from, int to) {
    table[to] = table[from];
    dirty = true;
  }

  public static Material get(int id) {
    return unpack(table[id]);
  }

  /** Forgets every material; ids are being handed out again from 1. */
  public static void reset() {
    Arrays.fill(table, pack(DEFAULT));
    dirty = true;
  }

  /** The packed table, indexed by voxel id. */
  public static int[] table() {
    return table;
  }

  // --- GPU -------------------------------------------------------------------------------------

  /** Creates the buffer on first use and uploads the table if it changed. Leaves the active unit alone. */
  public static void flush() {
    if (buf == 0) {
      buf = GL33C.glGenBuffers();
      GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, buf);
      GL33C.glBufferData(GL33C.GL_TEXTURE_BUFFER, (long) table.length * 4, GL33C.GL_DYNAMIC_DRAW);
      tex = GL33C.glGenTextures();
      int prev = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
      GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT);
      GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, tex);
      GL33C.glTexBuffer(GL33C.GL_TEXTURE_BUFFER, GL33C.GL_R32UI, buf);
      GL33C.glActiveTexture(prev);
      dirty = true;
    }
    if (!dirty) return;
    dirty = false;
    IntBuffer scratch = ByteBuffer.allocateDirect(table.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    scratch.put(table).flip();
    GL33C.glBindBuffer(GL33C.GL_TEXTURE_BUFFER, buf);
    GL33C.glBufferSubData(GL33C.GL_TEXTURE_BUFFER, 0, scratch);
  }

  /** Binds the table to {@link #UNIT}. The caller restores the active unit. */
  public static void bind() {
    flush();
    GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + UNIT);
    GL33C.glBindTexture(GL33C.GL_TEXTURE_BUFFER, tex);
  }
}
