package dev.telemetric.hud;

import dev.telemetric.setting.impl.ColorSetting;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * One theme-derived colour of a HUD element (e.g. its label colour). While the element's "Theme Colors" toggle is on it
 * follows the theme ({@code themeColor}); when it's off, the user's {@link #setting} is used. Made by
 * {@link HudModule#hudColor}.
 */
public final class HudColor {

    public final ColorSetting setting;
    private final IntSupplier themeColor;
    private final BooleanSupplier custom;

    HudColor(ColorSetting setting, IntSupplier themeColor, BooleanSupplier custom) {
        this.setting = setting;
        this.themeColor = themeColor;
        this.custom = custom;
    }

    /** The colour to draw with. */
    public int get() {
        return custom.getAsBoolean() ? setting.get() : themeColor.getAsInt();
    }

    /** The colour the theme gives right now. */
    int themeColor() {
        return themeColor.getAsInt();
    }
}
