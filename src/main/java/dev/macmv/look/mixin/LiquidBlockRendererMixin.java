package dev.macmv.look.mixin;

import dev.macmv.look.LookState;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps water out of vanilla's section meshes while the voxel renderer is on: the raycast pass draws
 * water itself, translucent and in the same ray as what is behind it. Lava and everything else
 * translucent (glass, ice) stay vanilla. Toggling the renderer rebuilds the meshes, see
 * {@link LookState#setVoxelEnabled}.
 */
@Mixin(LiquidBlockRenderer.class)
public class LiquidBlockRendererMixin {
  @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
  private void look$skipWater(
      BlockAndTintGetter level, BlockPos pos, VertexConsumer buffer, BlockState blockState, FluidState fluidState, CallbackInfo ci) {
    if (LookState.voxelEnabled && fluidState.is(FluidTags.WATER)) ci.cancel();
  }
}
