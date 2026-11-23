package dev.telemetric.ui.clickgui.window;

import dev.telemetric.ui.framework.GuiContext;
import dev.telemetric.ui.render.Render2D;

/**
 * A live preview shown in the right column of the Settings window (see {@code Module#createPreview}). The window
 * gives it a box each frame; while a press it accepted is held, the window keeps sending it the mouse position (also
 * outside the box) through {@link #render}.
 */
public interface SettingsPreview {

    /** The narrowest column the preview still works in. */
    default int minWidth() {
        return 120;
    }

    void render(Render2D r, GuiContext ctx, int x, int y, int w, int h, int mouseX, int mouseY, float delta);

    /** A press inside the box. Return true to take it (the window then captures the mouse until release). */
    default boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    default void mouseReleased(double mouseX, double mouseY, int button) {}

    default boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return false;
    }

    default void onClose() {}
}
