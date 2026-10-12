package dev.macmv.look.net;

import dev.macmv.look.LookCommon;
import dev.macmv.look.WorldDescriptor;
import java.io.UncheckedIOException;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

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
}
