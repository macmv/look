package dev.macmv.look.mixin;

import dev.macmv.look.EntityRefraction;
import dev.macmv.look.FinalPost;
import dev.macmv.look.LookState;
import dev.macmv.look.SkyState;
import dev.macmv.look.VoxelRenderer;
import dev.macmv.look.WorldSource;
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
    WorldSource.INSTANCE.markDirty(x, y, z);
  }

  /** Block changes are what actually alters voxel data (setSectionDirty also fires for neighbors). */
  @Inject(method = "blockChanged", at = @At("HEAD"))
  private void look$blockChanged(
      BlockGetter level, BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
    WorldSource.INSTANCE.blockChanged(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
  }

  /** Enhanced final post: after the translucent layer, particles and weather, before the GUI. */
  @Inject(method = "renderLevel", at = @At("TAIL"))
  private void look$finalPost(CallbackInfo ci) {
    EntityRefraction.end();
    FinalPost.runFrame();
  }

  /** Enhanced draws the sky (sun, moon, stars) in the raycast shader. */
  @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
  private void look$renderSky(CallbackInfo ci) {
    if (SkyState.ownsSky()) ci.cancel();
  }

  /** Enhanced draws the clouds in the raycast shader. */
  @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
  private void look$renderClouds(CallbackInfo ci) {
    if (SkyState.ownsClouds()) ci.cancel();
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
