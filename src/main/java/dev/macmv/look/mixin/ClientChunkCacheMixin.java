package dev.macmv.look.mixin;

import dev.macmv.look.LightMap;
import dev.macmv.look.WorldSource;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
  /** Tells the voxel world a chunk is going away, so its bricks can be freed. */
  @Inject(method = "drop", at = @At("HEAD"))
  private void look$drop(ChunkPos pos, CallbackInfo ci) {
    WorldSource.INSTANCE.unloadChunk(pos);
  }

  /** Vanilla's light engine changed a section: refresh our copy of its light. */
  @Inject(method = "onLightUpdate", at = @At("HEAD"))
  private void look$light(LightLayer layer, SectionPos pos, CallbackInfo ci) {
    LightMap.mark(pos.x(), pos.y(), pos.z());
  }

  /** Hands the voxel world the raw section bytes of a chunk packet before vanilla consumes them. */
  @Inject(method = "replaceWithPacketData", at = @At("HEAD"))
  private void look$wire(
      int x,
      int z,
      FriendlyByteBuf buf,
      CompoundTag heightmaps,
      Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
      CallbackInfoReturnable<LevelChunk> ci) {
    WorldSource.INSTANCE.ingestWire(x, z, buf);
  }
}
