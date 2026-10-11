package dev.macmv.look;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Rebindable keys, shown in the Controls menu under "Look". Registered by {@code OptionsMixin}. */
public final class LookKeys {
  public static final String CATEGORY = "key.categories.look";

  public static final KeyMapping TOGGLE_RENDERER =
      new KeyMapping("key.look.toggle_renderer", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY);
  public static final KeyMapping TOGGLE_DEBUG =
      new KeyMapping("key.look.toggle_debug", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);

  private LookKeys() {}

  public static KeyMapping[] all() {
    return new KeyMapping[] {TOGGLE_RENDERER, TOGGLE_DEBUG};
  }
}
