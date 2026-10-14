package dev.macmv.look.mixin;

import com.mojang.blaze3d.shaders.Program;
import dev.macmv.look.EntityRefraction;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceMixin {
  @Shadow @Final private Program vertexProgram;

  /** The program is in use again: give the refracting ones the camera and the water of this frame. */
  @Inject(method = "apply", at = @At("TAIL"))
  private void look$entityRefraction(CallbackInfo ci) {
    EntityRefraction.onApply((ShaderInstance) (Object) this, vertexProgram);
  }
}
