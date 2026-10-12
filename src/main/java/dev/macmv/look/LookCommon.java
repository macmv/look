package dev.macmv.look;

import dev.macmv.look.net.WorldDescriptorPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for both sides, so it must not touch client classes. On a server (dedicated, or the
 * integrated server opened to LAN) it tells each joining player how to rebuild the overworld.
 */
public final class LookCommon implements ModInitializer {
  public static final String ID = "look";
  public static final Logger LOGGER = LoggerFactory.getLogger(ID);

  /** The payload for the running server, built on the first remote join. Null until then or if unavailable. */
  private static WorldDescriptorPayload payload;
  private static boolean payloadBuilt;

  @Override
  public void onInitialize() {
    PayloadTypeRegistry.playS2C().register(WorldDescriptorPayload.TYPE, WorldDescriptorPayload.CODEC);

    ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> describeTo(handler.getPlayer(), server));
    // The generator config can change with datapacks, and the next server must not reuse this one's.
    ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> forget());
    ServerLifecycleEvents.SERVER_STOPPED.register(server -> forget());
  }

  private static void describeTo(ServerPlayer player, MinecraftServer server) {
    // The host of a singleplayer world reads the generator directly.
    if (server.isSingleplayerOwner(player.getGameProfile())) return;
    // A vanilla client, or one without this mod, has no receiver for it.
    if (!ServerPlayNetworking.canSend(player, WorldDescriptorPayload.TYPE)) return;

    WorldDescriptorPayload p = payload(server);
    if (p != null) ServerPlayNetworking.send(player, p);
  }

  private static synchronized WorldDescriptorPayload payload(MinecraftServer server) {
    if (payloadBuilt) return payload;
    payloadBuilt = true;

    WorldDescriptor world = WorldDescriptor.of(server);
    if (world == null) return null;

    WorldDescriptorPayload p = WorldDescriptorPayload.of(world);
    if (p.gzipConfig().length > WorldDescriptorPayload.MAX_WIRE_BYTES) {
      LOGGER.warn("World description is {} bytes compressed, over the packet limit; clients get no predicted terrain", p.gzipConfig().length);
      return null;
    }
    LOGGER.info("World description for clients: seed {}, {} bytes of config ({} compressed)", p.seed(), world.config().length, p.gzipConfig().length);
    return payload = p;
  }

  private static synchronized void forget() {
    payload = null;
    payloadBuilt = false;
  }
}
