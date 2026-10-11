package dev.macmv.look;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LookMod implements ClientModInitializer {
  public static final String ID = "look";
  public static final Logger LOGGER = LoggerFactory.getLogger(ID);

  @Override
  public void onInitializeClient() {
    NativeLib.load();
    LOGGER.info("Loaded {}", NativeLib.version());
  }
}
