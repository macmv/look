package dev.macmv.look;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

/**
 * Video settings with three tabs: vanilla's own options (the untouched {@link VideoSettingsScreen}
 * content, so resolution, mipmap reloads and the rest behave as before), Look's renderer settings,
 * and the Enhanced-mode shader settings. Swapped in by {@code MinecraftMixin}.
 */
public final class LookVideoScreen extends VideoSettingsScreen {
  private static final String[] TABS = {"look.video.tab.vanilla", "look.video.tab.look", "look.video.tab.shaders"};
  private static final int HEADER_HEIGHT = 58;
  private static final int TAB_WIDTH = 100;

  /** Survives closing the screen, so it reopens where the player left it. */
  private static int tab;

  private final Screen parent;

  public LookVideoScreen(Screen parent, Minecraft minecraft, Options options) {
    super(parent, minecraft, options);
    this.parent = parent;
  }

  @Override
  protected void addTitle() {
    LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(6));
    header.addChild(new StringWidget(title, font), s -> s.alignHorizontallyCenter());

    LinearLayout row = header.addChild(LinearLayout.horizontal().spacing(4), s -> s.alignHorizontallyCenter());
    for (int i = 0; i < TABS.length; i++) {
      int index = i;
      Button button = Button.builder(Component.translatable(TABS[i]), b -> select(index)).width(TAB_WIDTH).build();
      button.active = i != tab;
      row.addChild(button);
    }
    layout.setHeaderHeight(HEADER_HEIGHT);
  }

  @Override
  protected void addOptions() {
    switch (tab) {
      case 1 -> list.addSmall(LookSettings.options(LookSettings.LOOK));
      case 2 -> addShaderOptions();
      default -> super.addOptions();
    }
  }

  private void addShaderOptions() {
    OptionInstance<?>[] rest = LookSettings.options(LookSettings.SHADER);
    List<AbstractWidget> widgets = new ArrayList<>();
    Runnable refresh = () -> {
      for (AbstractWidget w : widgets) w.active = LookState.enhanced;
    };
    list.addBig(LookSettings.shadersToggle(refresh));
    list.addSmall(rest);
    for (OptionInstance<?> option : rest) widgets.add(list.findOption(option));
    refresh.run();
  }

  private void select(int index) {
    if (index == tab) return;
    list.applyUnsavedChanges();
    tab = index;
    // init() appends to the final layout rather than resetting it, so re-running it would stack a
    // second header and list on the first. A fresh screen starts from an empty layout.
    minecraft.setScreen(new LookVideoScreen(parent, minecraft, options));
  }

  @Override
  public void removed() {
    super.removed();
    LookSettings.save();
  }
}
