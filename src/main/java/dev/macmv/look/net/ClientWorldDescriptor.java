package dev.macmv.look.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.macmv.look.LookCommon;
import dev.macmv.look.WorldDescriptor;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

/**
 * The world description the connected server sent us, if any. A client in singleplayer never gets
 * one, since it reads the integrated server directly.
 */
public final class ClientWorldDescriptor {
  private static volatile WorldDescriptor received;

  private ClientWorldDescriptor() {}

  public static void register() {
    ClientPlayNetworking.registerGlobalReceiver(
        WorldDescriptorPayload.TYPE,
        (payload, context) -> {
          try {
            received = payload.descriptor();
            LookCommon.LOGGER.info("Server described its world: seed {}, {} bytes of config", received.seed(), received.config().length);
          } catch (UncheckedIOException e) {
            LookCommon.LOGGER.warn("Ignoring a bad world description from the server: {}", e.toString());
          }
        });
    // Do not carry one server's seed over to the next.
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> received = null);
  }

  /** What the server sent, or null if it has not (yet), or never will because it lacks this mod. */
  public static WorldDescriptor received() {
    return received;
  }

  /**
   * The description with the biome colors recomputed from this client's registries and colormaps. A
   * dedicated server never loads the colormaps (only the client's resource reload does), so the
   * colors it exported are black. Biomes the client does not know keep the server's colors.
   */
  public static WorldDescriptor recolored(WorldDescriptor world, RegistryAccess registries) {
    try {
      JsonObject cfg = JsonParser.parseString(new String(world.config(), StandardCharsets.UTF_8)).getAsJsonObject();
      JsonObject biomes = cfg.getAsJsonObject("biomes");
      JsonArray names = biomes.getAsJsonArray("names");
      if (names == null) return world;

      var registry = registries.registryOrThrow(Registries.BIOME);
      JsonArray grass = biomes.getAsJsonArray("grass_colors");
      JsonArray foliage = biomes.getAsJsonArray("foliage_colors");
      JsonArray water = biomes.getAsJsonArray("water_colors");
      for (int i = 0; i < names.size(); i++) {
        ResourceLocation id = ResourceLocation.tryParse(names.get(i).getAsString());
        Biome biome = id == null ? null : registry.get(id);
        if (biome == null) continue;
        grass.set(i, new JsonPrimitive(biome.getGrassColor(0, 0) & 0xFFFFFF));
        foliage.set(i, new JsonPrimitive(biome.getFoliageColor() & 0xFFFFFF));
        water.set(i, new JsonPrimitive(biome.getWaterColor() & 0xFFFFFF));
      }
      return new WorldDescriptor(world.seed(), cfg.toString().getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      LookCommon.LOGGER.warn("Keeping the server's biome colors: {}", e.toString());
      return world;
    }
  }
}
