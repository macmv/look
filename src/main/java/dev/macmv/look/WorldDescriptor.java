package dev.macmv.look;

import dev.macmv.look.mixin.MultiNoiseBiomeSourceAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * Everything the native terrain engine needs to reproduce a world's overworld: the seed and the
 * {@link TerrainExport} config. Built from a running server, then either used in place
 * (singleplayer) or sent to clients (see {@code WorldDescriptorPayload}).
 *
 * <p>Common code: it must stay loadable on a dedicated server, so no client classes in here.
 */
public record WorldDescriptor(long seed, byte[] config) {
  /**
   * Describes the server's overworld, or null if it cannot be predicted (not a noise generator
   * with a multi noise biome source, legacy noise, a density function the export cannot express).
   */
  public static WorldDescriptor of(MinecraftServer server) {
    ServerLevel level = server.getLevel(Level.OVERWORLD);
    if (level == null) return null;

    ChunkGenerator generator = level.getChunkSource().getGenerator();
    if (!(generator instanceof NoiseBasedChunkGenerator noise)
        || !(generator.getBiomeSource() instanceof MultiNoiseBiomeSource biomes)) {
      return null;
    }

    try {
      NoiseGeneratorSettings settings = noise.generatorSettings().value();
      byte[] config =
          TerrainExport.export(
              server.registryAccess(), settings, ((MultiNoiseBiomeSourceAccessor) (Object) biomes).look$parameters());
      return new WorldDescriptor(server.getWorldData().worldGenOptions().seed(), config);
    } catch (RuntimeException e) {
      // An unusual world (legacy noise, a modded density function): just no predicted terrain.
      LookMod.LOGGER.warn("Predicted terrain is unavailable for this world: {}", e.toString());
      return null;
    }
  }
}
