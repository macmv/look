package dev.macmv.look.mixin;

import dev.macmv.look.VoxelWorld;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
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
    VoxelWorld.unloadChunk(pos);
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
    VoxelWorld.ingestWire(x, z, buf);
  }
}
