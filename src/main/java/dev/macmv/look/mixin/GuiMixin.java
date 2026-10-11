package dev.macmv.look.mixin;

import dev.macmv.look.DebugHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
  @Inject(method = "render", at = @At("TAIL"))
  private void look$renderDebugHud(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
    DebugHud.render(graphics);
  }
}
