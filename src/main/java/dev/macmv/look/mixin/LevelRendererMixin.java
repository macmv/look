package dev.macmv.look.mixin;

import dev.macmv.look.LookState;
import dev.macmv.look.VoxelRenderer;
import dev.macmv.look.VoxelWorld;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
  /**
   * Every path that marks a section for re-render (chunk load, block change, neighbor updates)
   * funnels through here, which makes it the single place to learn that voxel data changed.
   */
  @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
  private void look$sectionDirty(int x, int y, int z, boolean reRenderOnMainThread, CallbackInfo ci) {
    VoxelWorld.markDirty(x, y, z);
  }

  /** Block changes are what actually alters voxel data (setSectionDirty also fires for neighbors). */
  @Inject(method = "blockChanged", at = @At("HEAD"))
  private void look$blockChanged(
      BlockGetter level, BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
    VoxelWorld.blockChanged(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
  }

  /**
   * Replaces the opaque section layers with the raycast pass. Vanilla draws solid, cutout_mipped
   * and cutout first, then entities and block entities, then translucent, so drawing our pass
   * (with real depth) at the solid call lets everything else render on top.
   *
   * <p>TODO: the translucent layer is still vanilla, and vanilla still compiles section meshes.
   */
  @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
  private void look$renderSectionLayer(
      RenderType type,
      double camX,
      double camY,
      double camZ,
      Matrix4f view,
      Matrix4f projection,
      CallbackInfo ci) {
    if (type == RenderType.solid()) LookState.poll();

    // Vanilla keeps building meshes, so letting it draw again is all "off" takes.
    if (!LookState.voxelEnabled) return;

    if (type == RenderType.solid()) {
      VoxelRenderer.render(view, projection, camX, camY, camZ);
    }

    if (type == RenderType.solid() || type == RenderType.cutoutMipped() || type == RenderType.cutout()) {
      ci.cancel();
    }
  }
}
