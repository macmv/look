package dev.macmv.look;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Writes everything the native terrain generator needs to know about a world as JSON, straight from
 * the live registries, so datapacks and custom noise settings are honored:
 *
 * <ul>
 *   <li>{@code sea_level}, {@code min_y}, {@code height} from the noise settings;
 *   <li>{@code roots}: the density functions the generator evaluates (surface density, with and
 *       without jagged peaks, the full sloped cheese with the 3D noise, the final density with caves, and the six
 *       climate parameters), in the same format as the game's data files;
 *   <li>{@code functions}: the registry density functions those refer to, by name, and
 *       {@code noises}: the noise parameters they use;
 *   <li>{@code biomes}: the climate parameter points of the biome source, with per biome flags
 *       (see {@code native/src/biome.rs}), temperature, grass and foliage color;
 *   <li>{@code decoration}: the features that grow trees (see {@link TreeExport}).
 * </ul>
 *
 * The world seed is not part of it; it goes to the native side separately.
 */
public final class TerrainExport {
  // Keep in sync with native/src/biome.rs.
  private static final int OCEAN = 1;
  private static final int RIVER = 2;
  private static final int BEACH = 4;
  private static final int BADLANDS = 8;
  private static final int MOUNTAIN = 16;
  private static final int DESERT = 32;
  private static final int STEEP_ROCK = 64;
  private static final int STONE = 128;

  private final RegistryOps<JsonElement> ops;
  private final HolderLookup.RegistryLookup<DensityFunction> functionRegistry;
  private final HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noiseRegistry;

  private final JsonObject functions = new JsonObject();
  private final Set<String> noiseNames = new LinkedHashSet<>();

  private TerrainExport(HolderLookup.Provider registries) {
    this.ops = RegistryOps.create(JsonOps.INSTANCE, registries);
    this.functionRegistry = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);
    this.noiseRegistry = registries.lookupOrThrow(Registries.NOISE);
  }

  /** The config as UTF-8 JSON. Throws if the world cannot be described (for example legacy noise). */
  public static byte[] export(
      HolderLookup.Provider registries,
      NoiseGeneratorSettings settings,
      Climate.ParameterList<Holder<Biome>> biomes) {
    if (settings.useLegacyRandomSource()) throw new UnsupportedOperationException("legacy random source");
    return new TerrainExport(registries).build(settings, biomes).toString().getBytes(StandardCharsets.UTF_8);
  }

  private JsonObject build(NoiseGeneratorSettings settings, Climate.ParameterList<Holder<Biome>> biomes) {
    JsonObject cfg = new JsonObject();
    NoiseSettings noise = settings.noiseSettings();
    cfg.addProperty("sea_level", settings.seaLevel());
    cfg.addProperty("min_y", noise.minY());
    cfg.addProperty("height", noise.height());

    NoiseRouter router = settings.noiseRouter();
    JsonObject roots = new JsonObject();
    root(roots, "initial_density_without_jaggedness", router.initialDensityWithoutJaggedness());
    root(roots, "initial_density", withJaggedness(router));
    // The full terrain shape with the 3D noise, if the registry has it (vanilla overworld does).
    DensityFunction sloped = slopedCheese();
    if (sloped != null) root(roots, "sloped_cheese", sloped);
    // What the game actually generates from, with the caves and the world's top and bottom slides.
    root(roots, "final_density", router.finalDensity());
    root(roots, "temperature", router.temperature());
    root(roots, "vegetation", router.vegetation());
    root(roots, "continents", router.continents());
    root(roots, "erosion", router.erosion());
    root(roots, "depth", router.depth());
    root(roots, "ridges", router.ridges());
    cfg.add("roots", roots);
    cfg.add("functions", functions);

    JsonObject noises = new JsonObject();
    for (String name : noiseNames) {
      ResourceKey<NormalNoise.NoiseParameters> key = ResourceKey.create(Registries.NOISE, ResourceLocation.parse(name));
      NormalNoise.NoiseParameters params = noiseRegistry.getOrThrow(key).value();
      noises.add(name, encode(NormalNoise.NoiseParameters.DIRECT_CODEC, params));
    }
    cfg.add("noises", noises);

    cfg.add("biomes", biomes(biomes));
    // Where trees grow. Optional: without it the terrain is just treeless.
    try {
      List<Holder<Biome>> order = biomes.values().stream().map(Pair::getSecond).distinct().toList();
      cfg.add("decoration", TreeExport.export(order));
    } catch (RuntimeException e) {
      LookMod.LOGGER.warn("Tree decoration is unavailable: {}", e.toString());
    }
    return cfg;
  }

  /**
   * The surface density with the jagged peaks term, as vanilla's sloped cheese has it but without
   * the 3D noise: {@code 4 * quarter_negative((depth + jaggedness * half_negative(jagged)) * factor)}.
   * Falls back to the plain density if the registry lacks the pieces (a datapack replaced them).
   */
  private DensityFunction withJaggedness(NoiseRouter router) {
    try {
      DensityFunction jaggedness = named("minecraft:overworld/jaggedness");
      DensityFunction factor = named("minecraft:overworld/factor");
      Holder<NormalNoise.NoiseParameters> jagged = noiseRegistry.getOrThrow(Noises.JAGGED);
      DensityFunction peaks = DensityFunctions.mul(jaggedness, DensityFunctions.noise(jagged, 1500.0, 0.0).halfNegative());
      DensityFunction depth = DensityFunctions.add(router.depth(), peaks);
      return DensityFunctions.mul(DensityFunctions.constant(4.0), DensityFunctions.mul(depth, factor).quarterNegative());
    } catch (RuntimeException e) {
      return router.initialDensityWithoutJaggedness();
    }
  }

  private DensityFunction slopedCheese() {
    var key = ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.parse("minecraft:overworld/sloped_cheese"));
    return functionRegistry.get(key).isPresent() ? named("minecraft:overworld/sloped_cheese") : null;
  }

  private DensityFunction named(String name) {
    var key = ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.parse(name));
    return new DensityFunctions.HolderHolder(functionRegistry.getOrThrow(key));
  }

  private void root(JsonObject roots, String name, DensityFunction f) {
    JsonElement json = encode(DensityFunction.HOLDER_HELPER_CODEC, f);
    roots.add(name, json);
    walk(json);
  }

  private <T> JsonElement encode(Codec<T> codec, T value) {
    return codec.encodeStart(ops, value).getOrThrow();
  }

  /**
   * Finds what a function's JSON refers to: strings that name a registry density function (pulled
   * in, recursively) and noise names (under {@code noise}, or {@code argument} of the shift types).
   */
  private void walk(JsonElement e) {
    if (e.isJsonArray()) {
      for (JsonElement v : e.getAsJsonArray()) walk(v);
    } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
      reference(e.getAsString());
    } else if (e.isJsonObject()) {
      JsonObject obj = e.getAsJsonObject();
      String type = obj.has("type") ? obj.get("type").getAsString() : "";
      boolean shift = type.endsWith("shift") || type.endsWith("shift_a") || type.endsWith("shift_b");
      for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
        String key = entry.getKey();
        if (key.equals("type")) continue;
        if ((key.equals("noise") || (shift && key.equals("argument"))) && entry.getValue().isJsonPrimitive()) {
          noiseNames.add(entry.getValue().getAsString());
          continue;
        }
        walk(entry.getValue());
      }
    }
  }

  private void reference(String name) {
    if (functions.has(name)) return;
    ResourceLocation id = ResourceLocation.tryParse(name);
    if (id == null) return;
    functionRegistry.get(ResourceKey.create(Registries.DENSITY_FUNCTION, id)).ifPresent(holder -> {
      JsonElement json = encode(DensityFunction.DIRECT_CODEC, holder.value());
      functions.add(name, json);
      walk(json);
    });
  }

  private static JsonObject biomes(Climate.ParameterList<Holder<Biome>> list) {
    Map<Holder<Biome>, Integer> index = new LinkedHashMap<>();
    JsonArray points = new JsonArray();

    for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : list.values()) {
      Climate.ParameterPoint p = entry.getFirst();
      int biome = index.computeIfAbsent(entry.getSecond(), b -> index.size());
      JsonArray a = new JsonArray();
      for (Climate.Parameter param :
          List.of(p.temperature(), p.humidity(), p.continentalness(), p.erosion(), p.depth(), p.weirdness())) {
        a.add(new JsonPrimitive(param.min()));
        a.add(new JsonPrimitive(param.max()));
      }
      a.add(new JsonPrimitive(p.offset()));
      a.add(new JsonPrimitive(biome));
      points.add(a);
    }

    JsonArray flags = new JsonArray();
    JsonArray temperatures = new JsonArray();
    JsonArray grass = new JsonArray();
    JsonArray foliage = new JsonArray();
    JsonArray water = new JsonArray();
    for (Holder<Biome> biome : index.keySet()) {
      int f = 0;
      if (biome.is(BiomeTags.IS_OCEAN)) f |= OCEAN;
      if (biome.is(BiomeTags.IS_RIVER)) f |= RIVER;
      if (biome.is(BiomeTags.IS_BEACH)) f |= BEACH;
      if (biome.is(BiomeTags.IS_BADLANDS)) f |= BADLANDS;
      if (biome.is(BiomeTags.IS_MOUNTAIN)) f |= MOUNTAIN;
      if (biome.is(Biomes.DESERT)) f |= DESERT;
      if (biome.is(Biomes.JAGGED_PEAKS) || biome.is(Biomes.FROZEN_PEAKS) || biome.is(Biomes.SNOWY_SLOPES)) f |= STEEP_ROCK;
      if (biome.is(Biomes.STONY_PEAKS) || biome.is(Biomes.STONY_SHORE)) f |= STONE;
      flags.add(f);
      temperatures.add(biome.value().getBaseTemperature());
      grass.add(biome.value().getGrassColor(0, 0) & 0xFFFFFF);
      foliage.add(biome.value().getFoliageColor() & 0xFFFFFF);
      water.add(biome.value().getWaterColor() & 0xFFFFFF);
    }

    JsonObject out = new JsonObject();
    out.add("flags", flags);
    out.add("temperatures", temperatures);
    out.add("grass_colors", grass);
    out.add("foliage_colors", foliage);
    out.add("water_colors", water);
    out.add("points", points);
    return out;
  }
}
