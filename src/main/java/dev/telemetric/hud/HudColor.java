package dev.telemetric.hud;

import dev.telemetric.setting.impl.ColorRamp;
import dev.telemetric.setting.impl.ColorSetting;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One theme-derived colour of a HUD element (e.g. its label colour). While the element's "Theme Colors" toggle is on it
 * follows the theme ({@code themeColor}); when it's off, the user's {@link #setting} is used. Made by
 * {@link HudModule#hudColor}. A {@link ColorRamp}: a gradient setting colours text glyph by glyph
 * ({@link dev.telemetric.ui.render.Render2D#text(String, int, int, ColorRamp, dev.telemetric.ui.render.Render2D.TextLook, int, int)}).
 */
public final class HudColor implements ColorRamp {

    public final ColorSetting setting;
    private final IntSupplier themeColor;
    private final BooleanSupplier custom;
    /** The theme's colour as a ramp (a Theme gradient), or {@code null} for the plain {@link #themeColor}. */
    private final Supplier<ColorRamp> themeRamp;

    HudColor(ColorSetting setting, IntSupplier themeColor, BooleanSupplier custom, Supplier<ColorRamp> themeRamp) {
        this.setting = setting;
        this.themeColor = themeColor;
        this.custom = custom;
        this.themeRamp = themeRamp;
    }

    public int get() {
        return custom.getAsBoolean() ? setting.get() : themeColor.getAsInt();
    }

    @Override
    public int at(float t) {
        if (custom.getAsBoolean()) return setting.at(t);
        return themeRamp == null ? themeColor.getAsInt() : themeRamp.get().at(t);
    }

    @Override
    public boolean isUniform() {
        if (custom.getAsBoolean()) return setting.isUniform();
        return themeRamp == null || themeRamp.get().isUniform();
    }

    /** The colour the theme gives right now. */
    int themeColor() {
        return themeColor.getAsInt();
    }
}
