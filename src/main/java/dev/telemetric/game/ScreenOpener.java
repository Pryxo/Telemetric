package dev.telemetric.game;

import dev.telemetric.ui.clickgui.ClickGuiScreen;
import dev.telemetric.ui.confirm.Confirm;
import dev.telemetric.ui.confirm.ConfirmScreen;
import dev.telemetric.ui.hud.HudEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Supplier;

/**
 * Opens one of our screens from a chat command. The chat screen closes itself after running the command, so the screen
 * opens on the next client tick ({@link #tick}, called from {@code Telemetric#onEndTick}).
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

    /** Gives an open {@link Confirm} dialog a screen of its own, unless the ClickGUI is already showing it. */
    public static void confirmHost() {
        pending = () -> {
            Screen current = Minecraft.getInstance().screen;
            if (!Confirm.isOpen() || current instanceof ClickGuiScreen || current instanceof ConfirmScreen) return null;
            return new ConfirmScreen(current);
        };
    }

    public static void tick(Minecraft client) {
        Supplier<Screen> s = pending;
        if (s == null) return;
        pending = null;
        Screen next = s.get();
        if (next != null) client.setScreen(next);
    }
}
