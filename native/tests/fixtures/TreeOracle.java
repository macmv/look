// Generates native/tests/fixtures/trees.json: the terrain-and-decoration config the mod exports for
// a vanilla overworld, plus what the game's own tree features place on a synthetic world, which
// native/tests/trees.rs checks the Rust port against, block for block.
//
// The world is a function of x and z (see top()), a uniform biome (or two, split along x), and
// whatever the features placed so far. The game's real PlacedFeature and TreeFeature classes run
// on it through a proxy WorldGenLevel, driven by the same loop as ChunkGenerator.applyBiomeDecoration.
//
// Run it headless against the mod's classes and Minecraft (no game window), like Oracle.java:
//   ./gradlew compileJava
//   CP=<sourceSets.main.runtimeClasspath>
//   javac -proc:none -cp "$CP" -d /tmp/oracle native/tests/fixtures/TreeOracle.java
//   java -cp "/tmp/oracle:$CP" TreeOracle 12345 native/tests/fixtures/trees.json [full.json]
//
// The optional third argument also writes every placed block, to see what differs when the Rust
// side disagrees.

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.macmv.look.TreeExport;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.FluidState;

public class TreeOracle {
  static final int SEA = 63;
  static final int MIN_Y = -64;
  static final int HEIGHT = 384;

  /** A triangle wave in 0..p. */
  static int tri(int v, int p) {
    return Math.abs(Math.floorMod(v, 2 * p) - p);
  }

  /** The first air block above the terrain at a column. Keep in sync with trees.rs. */
  static int top(int x, int z, boolean flat) {
    if (flat) return 70;
    return 58 + tri(x + 2 * z, 24) / 3 + tri(3 * x - z, 40) / 5 + tri(z - x, 16) / 4 - 4;
  }

  /** The stub level: terrain from top() under whatever has been placed. */
  static final class Stub implements InvocationHandler {
    final boolean flat;
    final Holder<Biome> west, east;
    final int splitX;
    final Map<BlockPos, BlockState> placed = new HashMap<>();
    final Map<BlockPos, BlockEntity> entities = new HashMap<>();
    final long seed;
    int centerX, centerZ;
    WorldGenLevel proxy;

    Stub(long seed, boolean flat, Holder<Biome> west, Holder<Biome> east, int splitX) {
      this.seed = seed;
      this.flat = flat;
      this.west = west;
      this.east = east;
      this.splitX = splitX;
    }

    BlockState base(int x, int y, int z) {
      if (y < MIN_Y || y >= MIN_Y + HEIGHT) return Blocks.AIR.defaultBlockState();
      int t = top(x, z, flat);
      if (y < t) {
        if (y == t - 1) return (t <= 64 ? Blocks.SAND : Blocks.GRASS_BLOCK).defaultBlockState();
        if (y >= t - 4) return (t <= 64 ? Blocks.SAND : Blocks.DIRT).defaultBlockState();
        return Blocks.STONE.defaultBlockState();
      }
      return y < SEA ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    BlockState get(BlockPos p) {
      BlockState s = placed.get(p);
      return s != null ? s : base(p.getX(), p.getY(), p.getZ());
    }

    boolean canWrite(BlockPos p) {
      return Math.abs((p.getX() >> 4) - centerX) <= 1 && Math.abs((p.getZ() >> 4) - centerZ) <= 1;
    }

    int height(Heightmap.Types type, int x, int z) {
      BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
      for (int y = 140; y >= MIN_Y; y--) {
        if (type.isOpaque().test(get(m.set(x, y, z)))) return y + 1;
      }
      return MIN_Y;
    }

    @Override
    public Object invoke(Object self, java.lang.reflect.Method method, Object[] args) throws Throwable {
      switch (method.getName()) {
        case "hashCode": return System.identityHashCode(self);
        case "equals": return self == args[0];
        case "toString": return "StubLevel";
        case "getBlockState": return get((BlockPos) args[0]);
        case "getFluidState": return get((BlockPos) args[0]).getFluidState();
        case "isStateAtPosition": {
          @SuppressWarnings("unchecked")
          java.util.function.Predicate<BlockState> pred = (java.util.function.Predicate<BlockState>) args[1];
          return pred.test(get((BlockPos) args[0]));
        }
        case "isFluidAtPosition": {
          @SuppressWarnings("unchecked")
          java.util.function.Predicate<FluidState> pred = (java.util.function.Predicate<FluidState>) args[1];
          return pred.test(get((BlockPos) args[0]).getFluidState());
        }
        case "setBlock": {
          BlockPos p = ((BlockPos) args[0]).immutable();
          if (!canWrite(p) || p.getY() < MIN_Y || p.getY() >= MIN_Y + HEIGHT) return false;
          BlockState s = (BlockState) args[1];
          placed.put(p, s);
          if (s.is(Blocks.BEE_NEST)) entities.put(p, new BeehiveBlockEntity(p, s));
          else entities.remove(p);
          return true;
        }
        case "ensureCanWrite": return canWrite((BlockPos) args[0]);
        case "getHeight":
          if (args != null && args.length == 3) return height((Heightmap.Types) args[0], (Integer) args[1], (Integer) args[2]);
          return HEIGHT;
        case "getMinBuildHeight": return MIN_Y;
        case "getMaxBuildHeight": return MIN_Y + HEIGHT;
        case "getBiome": {
          BlockPos p = (BlockPos) args[0];
          return p.getX() < splitX ? west : east;
        }
        case "getSeed": return seed;
        case "getBlockEntity": {
          BlockEntity e = entities.get((BlockPos) args[0]);
          if (args.length == 2) return e != null && e.getType() == args[1] ? java.util.Optional.of(e) : java.util.Optional.empty();
          return e;
        }
        case "getBlockTicks":
        case "getFluidTicks": return tickAccess();
        case "getLevel": return null;
        case "setCurrentlyGenerating":
        case "scheduleTick":
        case "blockUpdated":
        case "levelEvent":
        case "gameEvent": return null;
        default:
          if (method.isDefault()) return InvocationHandler.invokeDefault(self, method, args);
          throw new UnsupportedOperationException(method.toString());
      }
    }

    @SuppressWarnings("unchecked")
    Object tickAccess() {
      return Proxy.newProxyInstance(
          Stub.class.getClassLoader(),
          new Class<?>[] {net.minecraft.world.ticks.LevelTickAccess.class},
          (p, m, a) -> m.getName().equals("hasScheduledTick") ? false : m.getReturnType() == boolean.class ? false : m.getReturnType() == int.class ? 0 : null);
    }
  }

  static int stateBits(BlockState s) {
    Block b = s.getBlock();
    if (s.is(BlockTags.LOGS) && s.hasProperty(BlockStateProperties.AXIS)) {
      return switch (s.getValue(BlockStateProperties.AXIS)) {
        case X -> 1;
        case Z -> 2;
        default -> 0;
      };
    }
    if (b == Blocks.VINE) {
      return (s.getValue(VineBlock.NORTH) ? 1 : 0)
          | (s.getValue(VineBlock.EAST) ? 2 : 0)
          | (s.getValue(VineBlock.SOUTH) ? 4 : 0)
          | (s.getValue(VineBlock.WEST) ? 8 : 0)
          | (s.getValue(VineBlock.UP) ? 16 : 0);
    }
    if (b == Blocks.COCOA) {
      return (s.getValue(CocoaBlock.AGE) << 2) | s.getValue(CocoaBlock.FACING).get2DDataValue();
    }
    if (b instanceof HugeMushroomBlock) {
      return (s.getValue(HugeMushroomBlock.NORTH) ? 1 : 0)
          | (s.getValue(HugeMushroomBlock.EAST) ? 2 : 0)
          | (s.getValue(HugeMushroomBlock.SOUTH) ? 4 : 0)
          | (s.getValue(HugeMushroomBlock.WEST) ? 8 : 0)
          | (s.getValue(HugeMushroomBlock.UP) ? 16 : 0)
          | (s.getValue(HugeMushroomBlock.DOWN) ? 32 : 0);
    }
    return 0;
  }

  static String describe(BlockPos p, BlockState s) {
    return p.getX() + "," + p.getY() + "," + p.getZ() + "," + BuiltInRegistries.BLOCK.getKey(s.getBlock()) + ":" + stateBits(s);
  }

  static long fnv(List<String> lines) {
    long h = 0xcbf29ce484222325L;
    for (String line : lines) {
      for (byte b : (line + "\n").getBytes(StandardCharsets.UTF_8)) {
        h ^= (b & 0xffL);
        h *= 0x100000001b3L;
      }
    }
    return h;
  }

  /** ChunkGenerator.applyBiomeDecoration, for the biomes of the stub and without structures. */
  static void decorate(
      Stub level,
      ChunkGenerator generator,
      List<FeatureSorter.StepFeatureData> steps,
      Set<Holder<Biome>> biomes,
      JsonObject decoration,
      int cx,
      int cz) {
    level.centerX = cx;
    level.centerZ = cz;
    BlockPos origin = new BlockPos(cx * 16, MIN_Y, cz * 16);
    WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
    long l = random.setDecorationSeed(level.seed, origin.getX(), origin.getZ());

    for (int k = 0; k < steps.size(); k++) {
      it.unimi.dsi.fastutil.ints.IntSet indices = new it.unimi.dsi.fastutil.ints.IntArraySet();
      for (Holder<Biome> holder : biomes) {
        List<HolderSet<PlacedFeature>> lists = generator.getBiomeGenerationSettings(holder).features();
        if (k < lists.size()) {
          FeatureSorter.StepFeatureData data = steps.get(k);
          lists.get(k).stream().map(Holder::value).forEach(f -> indices.add(data.indexMapping().applyAsInt(f)));
        }
      }
      int[] sorted = indices.toIntArray();
      java.util.Arrays.sort(sorted);
      for (int p : sorted) {
        // Only the features the export carries: the rest of the decoration is not modelled.
        if (!decoration.getAsJsonArray("steps").get(k).getAsJsonObject().has(Integer.toString(p))) continue;
        PlacedFeature feature = steps.get(k).features().get(p);
        random.setFeatureSeed(l, p, k);
        feature.placeWithBiomeCheck(level.proxy, generator, random, origin);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    long seed = Long.parseLong(args[0]);
    Path out = Path.of(args[1]);
    Path full = args.length > 2 ? Path.of(args[2]) : null;

    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()));
    HolderLookup.Provider lookup = RegistryDataLoader.load(
        resources, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), RegistryDataLoader.WORLDGEN_REGISTRIES);

    // Block tags (dirt, logs, replaceable_by_trees, ...) are bound by a data pack reload in the
    // game; do the same for blocks, or every tag check is false.
    var tagLoader = new net.minecraft.tags.TagLoader<Holder<Block>>(BuiltInRegistries.BLOCK::getHolder, Registries.tagsDirPath(Registries.BLOCK));
    Map<net.minecraft.tags.TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>();
    tagLoader.loadAndBuild(resources).forEach((id, holders) ->
        tags.put(net.minecraft.tags.TagKey.create(Registries.BLOCK, id), List.copyOf(holders)));
    BuiltInRegistries.BLOCK.bindTags(tags);

    Climate.ParameterList<Holder<Biome>> parameters = lookup
        .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
        .value()
        .parameters();
    NoiseGeneratorSettings settings =
        lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();

    MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromList(parameters);
    ChunkGenerator generator = new NoiseBasedChunkGenerator(
        source, lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD));
    List<FeatureSorter.StepFeatureData> steps = FeatureSorter.buildFeaturesPerStep(
        List.copyOf(source.possibleBiomes()), holder -> holder.value().getGenerationSettings().features(), true);

    // The biomes in the order the terrain export numbers them.
    List<Holder<Biome>> order = parameters.values().stream().map(com.mojang.datafixers.util.Pair::getSecond).distinct().toList();
    JsonObject decoration = TreeExport.export(order);

    JsonObject root = new JsonObject();
    root.addProperty("seed", seed);
    root.add("decoration", decoration);

    JsonArray scenarios = new JsonArray();
    JsonObject fullOut = new JsonObject();

    List<int[][]> sequences = List.of(
        new int[][] {{0, 0}},
        new int[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}});

    // Every biome with trees, alone, on hilly and (for the single chunk) flat ground.
    for (int b = 0; b < order.size(); b++) {
      boolean hasTrees = false;
      for (JsonElement step : decoration.getAsJsonArray("biomes").get(b).getAsJsonArray()) {
        if (!step.getAsJsonArray().isEmpty()) hasTrees = true;
      }
      if (!hasTrees) continue;
      for (int s = 0; s < sequences.size(); s++) {
        for (boolean flat : new boolean[] {false, true}) {
          if (flat && s != 0) continue;
          scenarios.add(run(seed, generator, steps, order, decoration, b, b, 1 << 20, flat, sequences.get(s), fullOut));
        }
      }
    }
    // Two neighbouring biomes, so the chunks see features of both.
    int[][] pairs = {{25, 28}, {21, 41}, {22, 25}, {32, 44}};
    for (int[] pair : pairs) {
      scenarios.add(run(seed, generator, steps, order, decoration, pair[0], pair[1], 16, false, sequences.get(1), fullOut));
    }
    root.add("scenarios", scenarios);

    Files.writeString(out, new Gson().toJson(root));
    if (full != null) Files.writeString(full, new GsonBuilder().setPrettyPrinting().create().toJson(fullOut));
    System.out.println("wrote " + scenarios.size() + " scenarios");
  }

  static JsonObject run(
      long seed,
      ChunkGenerator generator,
      List<FeatureSorter.StepFeatureData> steps,
      List<Holder<Biome>> order,
      JsonObject decoration,
      int westBiome,
      int eastBiome,
      int splitX,
      boolean flat,
      int[][] chunks,
      JsonObject fullOut) {
    String name = order.get(westBiome).unwrapKey().get().location().getPath()
        + (westBiome != eastBiome ? "+" + order.get(eastBiome).unwrapKey().get().location().getPath() : "")
        + (flat ? "/flat" : "/hilly") + "/" + chunks.length;
    String only = System.getProperty("only");
    if (only != null && !only.equals(name)) {
      JsonObject skipped = new JsonObject();
      skipped.addProperty("name", name);
      skipped.addProperty("flat", flat);
      skipped.addProperty("west", westBiome);
      skipped.addProperty("east", eastBiome);
      skipped.addProperty("split_x", splitX);
      skipped.add("chunks", new JsonArray());
      skipped.addProperty("count", 0);
      skipped.addProperty("hash", "cbf29ce484222325");
      return skipped;
    }
    Stub level = new Stub(seed, flat, order.get(westBiome), order.get(eastBiome), splitX);
    level.proxy = (WorldGenLevel) Proxy.newProxyInstance(
        TreeOracle.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, level);

    Set<Holder<Biome>> biomes = new LinkedHashSet<>(List.of(order.get(westBiome), order.get(eastBiome)));
    JsonArray chunkList = new JsonArray();
    for (int[] c : chunks) {
      decorate(level, generator, steps, biomes, decoration, c[0], c[1]);
      JsonArray a = new JsonArray();
      a.add(c[0]);
      a.add(c[1]);
      chunkList.add(a);
    }

    List<String> lines = new ArrayList<>();
    for (Map.Entry<BlockPos, BlockState> e : level.placed.entrySet()) {
      // The nest goes wherever an unseeded shuffle puts it; the draws it makes are what is compared.
      if (e.getValue().is(Blocks.BEE_NEST)) continue;
      lines.add(describe(e.getKey(), e.getValue()));
    }
    lines = new ArrayList<>(new TreeSet<>(lines));

    JsonObject o = new JsonObject();
    o.addProperty("name", name);
    o.addProperty("flat", flat);
    o.addProperty("west", westBiome);
    o.addProperty("east", eastBiome);
    o.addProperty("split_x", splitX);
    o.add("chunks", chunkList);
    o.addProperty("count", lines.size());
    o.addProperty("hash", Long.toHexString(fnv(lines)));
    fullOut.add(name, new Gson().toJsonTree(lines));
    return o;
  }
}
