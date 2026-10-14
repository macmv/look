package dev.macmv.look;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Draws {@link RenderStats} on screen, in both renderer modes unless {@link LookState#statsHud} is off. */
public final class DebugHud {
  private static final int PAD = 3;

  private DebugHud() {}

  public static void render(GuiGraphics g) {
    RenderStats.tickFrame();

    Minecraft mc = Minecraft.getInstance();
    if (mc.options.hideGui || !LookState.statsHud) return;

    var lines = RenderStats.lines();
    if (lines.isEmpty()) return;

    Font font = mc.font;
    int width = 0;
    for (String line : lines) width = Math.max(width, font.width(line));

    int x = 4;
    int y = 4;
    int lineHeight = font.lineHeight + 1;
    g.fill(x - PAD, y - PAD, x + width + PAD, y + lines.size() * lineHeight + PAD - 1, 0x90000000);

    for (String line : lines) {
      g.drawString(font, line, x, y, 0xFFFFFFFF, false);
      y += lineHeight;
    }
  }
}
