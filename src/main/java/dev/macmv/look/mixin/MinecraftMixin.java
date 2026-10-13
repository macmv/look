package dev.macmv.look.mixin;

import dev.macmv.look.LookVideoScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
  /**
   * Opens {@link LookVideoScreen} wherever vanilla opens its video settings. The exact class check
   * leaves other mods' subclasses of {@code VideoSettingsScreen} (and ours) alone.
   */
  @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
  private Screen look$videoSettings(Screen screen) {
    if (screen != null && screen.getClass() == VideoSettingsScreen.class) {
      OptionsSubScreenAccessor old = (OptionsSubScreenAccessor) screen;
      return new LookVideoScreen(old.look$lastScreen(), (Minecraft) (Object) this, old.look$options());
    }
    return screen;
  }
}
