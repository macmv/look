// Generates native/tests/fixtures/overworld.json: the terrain config the mod would export for a
// vanilla overworld, plus values that vanilla itself computes for it, which native/tests/oracle.rs
// checks the Rust port against.
//
// Run it headless against the mod's classes and Minecraft (no game window):
//   ./gradlew compileJava
//   CP=<sourceSets.main.runtimeClasspath, e.g. printed by a one-off gradle task>
//   javac -proc:none -cp "$CP" -d /tmp/oracle native/tests/fixtures/Oracle.java
//   java -cp "/tmp/oracle:$CP" Oracle 12345 native/tests/fixtures/overworld.json

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.macmv.look.TerrainExport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public class Oracle {
  /** A copy of a record with one component replaced, for swapping the router's final density. */
  @SuppressWarnings("unchecked")
  static <T extends Record> T with(T record, String component, Object value) throws Exception {
    var comps = record.getClass().getRecordComponents();
    Object[] args = new Object[comps.length];
    Class<?>[] types = new Class<?>[comps.length];
    for (int i = 0; i < comps.length; i++) {
      var m = comps[i].getAccessor();
      m.setAccessible(true);
      args[i] = comps[i].getName().equals(component) ? value : m.invoke(record);
      types[i] = comps[i].getType();
    }
    var ctor = record.getClass().getDeclaredConstructor(types);
    ctor.setAccessible(true);
    return (T) ctor.newInstance(args);
  }

  static JsonArray longs(long... v) {
    JsonArray a = new JsonArray();
    for (long l : v) a.add(l);
    return a;
  }

  public static void main(String[] args) throws Exception {
    long seed = Long.parseLong(args[0]);
    Path out = Path.of(args[1]);

    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    // Registries loaded from the vanilla data pack, as a server does; the datagen bootstrap ones use
    // different holder owners and cannot be serialized from.
    var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()));
    HolderLookup.Provider lookup = RegistryDataLoader.load(
        resources, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), RegistryDataLoader.WORLDGEN_REGISTRIES);
    NoiseGeneratorSettings settings =
        lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
    Climate.ParameterList<Holder<Biome>> biomes = lookup
        .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
        .value()
        .parameters();

    Gson gson = new Gson();
    JsonObject root = new JsonObject();
    root.addProperty("seed", seed);
    root.add("config", gson.fromJson(new String(TerrainExport.export(lookup, settings, biomes), StandardCharsets.UTF_8), JsonElement.class));

    RandomState rs = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
    Random rnd = new Random(42);

    // Random source and seeding.
    JsonObject rng = new JsonObject();
    XoroshiroRandomSource r = new XoroshiroRandomSource(seed);
    long[] ls = new long[8];
    for (int i = 0; i < ls.length; i++) ls[i] = r.nextLong();
    rng.add("longs", longs(ls));
    JsonArray ints256 = new JsonArray();
    JsonArray ints7 = new JsonArray();
    JsonArray doubles = new JsonArray();
    for (int i = 0; i < 16; i++) ints256.add(r.nextInt(256));
    for (int i = 0; i < 16; i++) ints7.add(r.nextInt(7));
    for (int i = 0; i < 4; i++) doubles.add(r.nextDouble());
    rng.add("ints256", ints256);
    rng.add("ints7", ints7);
    rng.add("doubles", doubles);
    var factory = new XoroshiroRandomSource(seed).forkPositional();
    var hashed = factory.fromHashOf("minecraft:continentalness");
    rng.add("hash_continentalness", longs(hashed.nextLong(), hashed.nextLong(), hashed.nextLong()));
    var octave = factory.fromHashOf("octave_-9");
    rng.add("hash_octave", longs(octave.nextLong(), octave.nextLong()));
    root.add("rng", rng);

    // Noise values.
    JsonArray noise = new JsonArray();
    for (String name : List.of("continentalness", "erosion", "ridge", "temperature", "vegetation", "offset")) {
      NormalNoise n = rs.getOrCreateNoise(ResourceKey.create(Registries.NOISE, ResourceLocation.withDefaultNamespace(name)));
      for (int i = 0; i < 12; i++) {
        double x = (rnd.nextDouble() - 0.5) * 8000, y = (rnd.nextDouble() - 0.5) * 400, z = (rnd.nextDouble() - 0.5) * 8000;
        JsonObject o = new JsonObject();
        o.addProperty("name", "minecraft:" + name);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("v", n.getValue(x, y, z));
        noise.add(o);
      }
    }
    root.add("noise", noise);

    // Density functions, at block positions (the router's markers act as plain wrappers here).
    NoiseRouter router = rs.router();
    Map<String, DensityFunction> roots = new LinkedHashMap<>();
    roots.put("initial_density_without_jaggedness", router.initialDensityWithoutJaggedness());
    roots.put("temperature", router.temperature());
    roots.put("vegetation", router.vegetation());
    roots.put("continents", router.continents());
    roots.put("erosion", router.erosion());
    roots.put("depth", router.depth());
    roots.put("ridges", router.ridges());
    JsonArray density = new JsonArray();
    for (Map.Entry<String, DensityFunction> e : roots.entrySet()) {
      for (int i = 0; i < 150; i++) {
        int x = rnd.nextInt(40000) - 20000, y = rnd.nextInt(384) - 64, z = rnd.nextInt(40000) - 20000;
        JsonObject o = new JsonObject();
        o.addProperty("root", e.getKey());
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("v", e.getValue().compute(new DensityFunction.SinglePointContext(x, y, z)));
        density.add(o);
      }
    }
    root.add("density", density);

    // Climate target and nearest biome, in the order the export numbers biomes.
    Map<Holder<Biome>, Integer> index = new LinkedHashMap<>();
    biomes.values().forEach(p -> index.computeIfAbsent(p.getSecond(), b -> index.size()));
    JsonArray climate = new JsonArray();
    for (int i = 0; i < 400; i++) {
      int qx = rnd.nextInt(10000) - 5000, qy = rnd.nextInt(96) - 16, qz = rnd.nextInt(10000) - 5000;
      Climate.TargetPoint t = rs.sampler().sample(qx, qy, qz);
      long[] target = {t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness()};
      Holder<Biome> found = biomes.findValue(t);

      // The distance to the chosen point, to compare ties by.
      long fitness = Long.MAX_VALUE;
      for (var p : biomes.values()) {
        if (p.getSecond() != found) continue;
        var pp = p.getFirst();
        long f = pp.offset() * pp.offset();
        var params = List.of(pp.temperature(), pp.humidity(), pp.continentalness(), pp.erosion(), pp.depth(), pp.weirdness());
        for (int d = 0; d < 6; d++) f += params.get(d).distance(target[d]) * params.get(d).distance(target[d]);
        fitness = Math.min(fitness, f);
      }

      JsonObject o = new JsonObject();
      o.addProperty("qx", qx);
      o.addProperty("qy", qy);
      o.addProperty("qz", qz);
      o.add("target", longs(target));
      o.addProperty("biome", index.get(found));
      o.addProperty("fitness", fitness);
      climate.add(o);
    }
    root.add("climate", climate);

    int minY = settings.noiseSettings().minY();
    int maxY = minY + settings.noiseSettings().height();

    // The sloped cheese (surface shape with the 3D noise), wired like the game wires the router, by
    // making it the final density of a copy of the settings.
    DensityFunction sloped = new net.minecraft.world.level.levelgen.DensityFunctions.HolderHolder(
        lookup.lookupOrThrow(Registries.DENSITY_FUNCTION)
            .getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.parse("minecraft:overworld/sloped_cheese"))));
    NoiseGeneratorSettings slopedSettings = with(settings, "noiseRouter", with(settings.noiseRouter(), "finalDensity", sloped));
    DensityFunction slopedWired = RandomState.create(slopedSettings, lookup.lookupOrThrow(Registries.NOISE), seed).router().finalDensity();
    JsonArray slopedValues = new JsonArray();
    for (int i = 0; i < 400; i++) {
      int x = rnd.nextInt(40000) - 20000, y = rnd.nextInt(384) - 64, z = rnd.nextInt(40000) - 20000;
      JsonObject o = new JsonObject();
      o.addProperty("root", "sloped_cheese");
      o.addProperty("x", x);
      o.addProperty("y", y);
      o.addProperty("z", z);
      o.addProperty("v", slopedWired.compute(new DensityFunction.SinglePointContext(x, y, z)));
      slopedValues.add(o);
    }
    root.add("sloped", slopedValues);

    // The game's own surface heights (first air above the highest solid block, caves included), to
    // measure the prediction against.
    var multiNoise = MultiNoiseBiomeSource.createFromList(biomes);
    var generator = new NoiseBasedChunkGenerator(multiNoise, Holder.direct(settings));
    LevelHeightAccessor heights = LevelHeightAccessor.create(minY, maxY - minY);
    JsonArray vanilla = new JsonArray();
    for (int i = 0; i < 600; i++) {
      int x = rnd.nextInt(16000) - 8000, z = rnd.nextInt(16000) - 8000;
      JsonObject o = new JsonObject();
      o.addProperty("x", x);
      o.addProperty("z", z);
      o.addProperty("h", generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, rs));
      vanilla.add(o);
    }
    root.add("vanilla_heights", vanilla);

    Files.writeString(out, gson.toJson(root));
    System.out.println("wrote " + out + " (" + Files.size(out) + " bytes), biomes " + index.size() + ", points " + biomes.values().size());
  }
}
