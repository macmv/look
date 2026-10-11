package dev.macmv.look.mixin;

import dev.macmv.look.LookKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.apache.commons.lang3.ArrayUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public abstract class OptionsMixin {
  @Shadow @Final @Mutable public KeyMapping[] keyMappings;

  /**
   * The key list is built in the constructor and then {@code load()} reads saved bindings, so
   * adding ours right before the load lets the Controls menu show them and saved rebinds apply.
   */
  @Inject(method = "load", at = @At("HEAD"))
  private void look$addKeys(CallbackInfo ci) {
    for (KeyMapping key : LookKeys.all()) {
      if (!ArrayUtils.contains(keyMappings, key)) keyMappings = ArrayUtils.add(keyMappings, key);
    }
  }
}
