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
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public class Oracle {
  // The surface search, exactly as the native side does it.
  static final double THRESHOLD = 0.0;
  static final int BIAS = 7;

  static Integer surfaceTop(DensityFunction d, int x, int z, int step, int minY, int maxY) {
    if (d.compute(new DensityFunction.SinglePointContext(x, maxY - 1, z)) > THRESHOLD) return null;
    int above = maxY;
    int y = maxY - 1;
    while (true) {
      if (d.compute(new DensityFunction.SinglePointContext(x, y, z)) > THRESHOLD) {
        int solid = y;
        int air = above;
        while (air - solid > 1) {
          int mid = (solid + air) >> 1;
          if (d.compute(new DensityFunction.SinglePointContext(x, mid, z)) > THRESHOLD) solid = mid;
          else air = mid;
        }
        return Math.min(solid + 1 + BIAS, maxY);
      }
      if (y <= minY) return null;
      above = y;
      y = Math.max(y - step, minY);
    }
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

    // The surface search.
    JsonArray surface = new JsonArray();
    int minY = settings.noiseSettings().minY();
    int maxY = minY + settings.noiseSettings().height();
    for (int i = 0; i < 300; i++) {
      int x = rnd.nextInt(60000) - 30000, z = rnd.nextInt(60000) - 30000;
      int step = i % 3 == 0 ? 16 : i % 3 == 1 ? 32 : 128;
      JsonObject o = new JsonObject();
      o.addProperty("x", x);
      o.addProperty("z", z);
      o.addProperty("step", step);
      Integer top = surfaceTop(router.initialDensityWithoutJaggedness(), x, z, step, minY, maxY);
      if (top != null) o.addProperty("top", top);
      surface.add(o);
    }
    root.add("surface", surface);

    Files.writeString(out, gson.toJson(root));
    System.out.println("wrote " + out + " (" + Files.size(out) + " bytes), biomes " + index.size() + ", points " + biomes.values().size());
  }
}
