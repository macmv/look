package dev.macmv.look;

import dev.macmv.look.net.ClientWorldDescriptor;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;

public final class LookMod implements ClientModInitializer {
  public static final String ID = LookCommon.ID;
  public static final Logger LOGGER = LookCommon.LOGGER;

  @Override
  public void onInitializeClient() {
    ClientWorldDescriptor.register();
    LookSettings.load();
    NativeLib.load();
  }
}
