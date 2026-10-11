package dev.macmv.look.mixin;

import dev.macmv.look.VoxelRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
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
    if (type == RenderType.solid()) {
      VoxelRenderer.render(view, projection, camX, camY, camZ);
    }

    if (type == RenderType.solid() || type == RenderType.cutoutMipped() || type == RenderType.cutout()) {
      ci.cancel();
    }
  }
}
