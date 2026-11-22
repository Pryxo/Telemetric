package dev.telemetric.game;

import dev.telemetric.ui.clickgui.ClickGuiScreen;
import dev.telemetric.ui.hud.HudEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Supplier;

/**
 * MC ADAPTER: opens one of our screens from a chat command. The chat screen closes itself after it ran the command, so
 * the screen is opened on the next client tick ({@link #tick}, called from {@code Telemetric#onEndTick}).
 */
public final class ScreenOpener {

    private static volatile Supplier<Screen> pending;

    private ScreenOpener() {}

    public static void clickGui() {
        pending = ClickGuiScreen::new;
    }

    public static void hudEditor() {
        pending = () -> new HudEditorScreen(false);
    }

    public static void tick(Minecraft client) {
        Supplier<Screen> s = pending;
        if (s == null) return;
        pending = null;
        client.setScreen(s.get());
    }
}
