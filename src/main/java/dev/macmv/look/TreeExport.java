package dev.macmv.look;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Describes the features that grow trees, for the native decoration port (see
 * {@code native/src/trees/}).
 *
 * <p>Vanilla seeds every placed feature's random source from its index in its decoration step's
 * list (see {@code ChunkGenerator.applyBiomeDecoration}), and the lists come from sorting the
 * features of all of the biome source's biomes together, so the indices are computed here the same
 * way the game does and exported with the definitions:
 *
 * <ul>
 *   <li>{@code steps}: per decoration step, an object from feature index to the placed feature as
 *       the game's own codec writes it (nested features inlined), for the features that contain a
 *       tree or a huge mushroom;
 *   <li>{@code biomes}: per biome (in the order of {@code biomes} in {@link TerrainExport}), per
 *       step, the indices of those features the biome has;
 *   <li>{@code skipped}: features the codec could not write, with the reason.
 * </ul>
 */
public final class TreeExport {
  private TreeExport() {}

  /** @param biomes the biome source's possible biomes, in the order the terrain export numbers them */
  public static JsonObject export(List<Holder<Biome>> biomes) {
    List<FeatureSorter.StepFeatureData> steps =
        FeatureSorter.buildFeaturesPerStep(biomes, holder -> holder.value().getGenerationSettings().features(), true);

    JsonArray stepsJson = new JsonArray();
    JsonArray skipped = new JsonArray();
    boolean[][] wanted = new boolean[steps.size()][];
    for (int step = 0; step < steps.size(); step++) {
      List<PlacedFeature> features = steps.get(step).features();
      wanted[step] = new boolean[features.size()];
      JsonObject byIndex = new JsonObject();
      for (int i = 0; i < features.size(); i++) {
        PlacedFeature feature = features.get(i);
        if (!growsTrees(feature)) continue;
        try {
          JsonElement json = PlacedFeature.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, feature).getOrThrow();
          byIndex.add(Integer.toString(i), json);
          wanted[step][i] = true;
        } catch (RuntimeException e) {
          JsonArray entry = new JsonArray();
          entry.add(step);
          entry.add(i);
          entry.add(String.valueOf(e.getMessage()));
          skipped.add(entry);
        }
      }
      stepsJson.add(byIndex);
    }

    JsonArray biomesJson = new JsonArray();
    for (Holder<Biome> biome : biomes) {
      JsonArray perStep = new JsonArray();
      List<HolderSet<PlacedFeature>> lists = biome.value().getGenerationSettings().features();
      for (int step = 0; step < steps.size(); step++) {
        JsonArray indices = new JsonArray();
        if (step < lists.size()) {
          FeatureSorter.StepFeatureData data = steps.get(step);
          for (Holder<PlacedFeature> holder : lists.get(step)) {
            int index = data.indexMapping().applyAsInt(holder.value());
            if (wanted[step][index]) indices.add(index);
          }
        }
        perStep.add(indices);
      }
      biomesJson.add(perStep);
    }

    JsonObject out = new JsonObject();
    out.add("steps", stepsJson);
    out.add("biomes", biomesJson);
    out.add("skipped", skipped);
    return out;
  }

  private static boolean growsTrees(PlacedFeature feature) {
    return feature
        .getFeatures()
        .anyMatch(configured -> configured.feature() instanceof TreeFeature || configured.feature() instanceof AbstractHugeMushroomFeature);
  }
}
