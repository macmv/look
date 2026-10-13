package dev.macmv.look;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Rebindable keys, shown in the Controls menu under "Look". Registered by {@code OptionsMixin}. */
public final class LookKeys {
  public static final String CATEGORY = "key.categories.look";

  public static final KeyMapping TOGGLE_RENDERER =
      new KeyMapping("key.look.toggle_renderer", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY);
  public static final KeyMapping TOGGLE_RENDER_MODE =
      new KeyMapping("key.look.toggle_render_mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, CATEGORY);
  public static final KeyMapping TOGGLE_DEBUG =
      new KeyMapping("key.look.toggle_debug", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);

  public static final KeyMapping RELOAD =
      new KeyMapping("key.look.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, CATEGORY);

  public static final KeyMapping VERIFY =
      new KeyMapping("key.look.verify", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F4, CATEGORY);

  private LookKeys() {}

  public static KeyMapping[] all() {
    return new KeyMapping[] {TOGGLE_RENDERER, TOGGLE_RENDER_MODE, TOGGLE_DEBUG, RELOAD, VERIFY};
  }
}
