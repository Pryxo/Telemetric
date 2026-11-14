package dev.telemetric.devbuild;

import dev.telemetric.ui.theme.Theme;

/** Fixed palettes of dev-build panels. Never follow the theme preset, accent or opacity. */
public final class DevPalette {

    /** Red/black palette of the Dev panel. */
    public static final Theme RED_BLACK = new Theme(
            0xB0000000,
            0xFF0A0707, 0xFF1C0A0A, 0xFF120707,
            0xFF0E0909, 0xFF1A0F0F, 0xFF2A1010,
            0xFF060404, 0xFF2A1414, 0xFF331919,
            0xFFF2E8E8, 0xFFA89090, 0xFF665050,
            0xFFDC2626, 0xFFF87171,
            0x90000000);

    private DevPalette() {
    }
}
