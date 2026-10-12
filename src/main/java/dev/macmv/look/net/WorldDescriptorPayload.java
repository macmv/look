package dev.macmv.look.net;

import dev.macmv.look.LookCommon;
import dev.macmv.look.WorldDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the {@link WorldDescriptor} of the server's overworld, with the config gzipped
 * (it is JSON, so it shrinks a lot). Sent once when a player joins; the client rebuilds the
 * terrain from it locally, so no terrain itself ever crosses the network.
 */
public record WorldDescriptorPayload(long seed, byte[] gzipConfig) implements CustomPacketPayload {
  public static final Type<WorldDescriptorPayload> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(LookCommon.ID, "world_descriptor"));

  public static final StreamCodec<FriendlyByteBuf, WorldDescriptorPayload> CODEC =
      CustomPacketPayload.codec(WorldDescriptorPayload::write, WorldDescriptorPayload::read);

  /** Vanilla drops clientbound custom payloads over 1 MiB; leave room for the id and the seed. */
  public static final int MAX_WIRE_BYTES = (1 << 20) - 256;
  /** What a received config may inflate to, so a bad payload cannot exhaust memory. */
  private static final int MAX_CONFIG_BYTES = 64 << 20;

  public static WorldDescriptorPayload of(WorldDescriptor world) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(world.config());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new WorldDescriptorPayload(world.seed(), out.toByteArray());
  }

  /** Inflates the config. Throws {@link UncheckedIOException} if the payload is corrupt or too big. */
  public WorldDescriptor descriptor() {
    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(gzipConfig))) {
      byte[] config = gzip.readNBytes(MAX_CONFIG_BYTES + 1);
      if (config.length > MAX_CONFIG_BYTES) throw new IOException("config is over " + MAX_CONFIG_BYTES + " bytes");
      return new WorldDescriptor(seed, config);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static WorldDescriptorPayload read(FriendlyByteBuf buf) {
    return new WorldDescriptorPayload(buf.readLong(), buf.readByteArray(MAX_WIRE_BYTES));
  }

  private void write(FriendlyByteBuf buf) {
    buf.writeLong(seed);
    buf.writeByteArray(gzipConfig);
  }

  @Override
  public Type<WorldDescriptorPayload> type() {
    return TYPE;
  }
}
