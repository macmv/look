package dev.macmv.look.mixin;

import dev.macmv.look.VoxelWorld;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
  /** Tells the voxel world a chunk is going away, so its bricks can be freed. */
  @Inject(method = "drop", at = @At("HEAD"))
  private void look$drop(ChunkPos pos, CallbackInfo ci) {
    VoxelWorld.unloadChunk(pos);
  }
}
