package dev.telemetric.game;

import dev.telemetric.mixin.GuiAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Keeps Telemetric's HUD hidden while Telos shows its black fade. The server sends a title of only the glyph U+15F96,
 * which the pack draws as {@code telos:glyph/title/black.png} over the whole screen (about 2 s after an Unrest kill).
 * Vanilla HUD layers are left alone; {@code HudOverlay} asks {@link #hidden()}.
 */
public final class HudHideAdapter {

    private static final String BLACK_FADE = new String(Character.toChars(0x15F96));

    private static volatile boolean duringBlackFade;

    private HudHideAdapter() {}

    /** Hide Telemetric's HUD while the black fade title is on screen (set by the Hide HUD module). */
    public static void setDuringBlackFade(boolean on) {
        duringBlackFade = on;
    }

    /** True while Telemetric's HUD should not be drawn right now. */
    public static boolean hidden() {
        return duringBlackFade && blackFadeShowing();
    }

    private static boolean blackFadeShowing() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return false;
        GuiAccessor gui = (GuiAccessor) mc.gui;
        Component title = gui.telemetric$getTitle();
        return title != null && gui.telemetric$getTitleTime() > 0 && title.getString().strip().equals(BLACK_FADE);
    }
}
