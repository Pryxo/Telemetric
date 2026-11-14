package dev.telemetric.module.impl.misc;

import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.theme.ThemePreset;

/** UI-only: colours and look of the ClickGUI. Read by {@link dev.telemetric.ui.theme.ThemeManager}. */
public final class ThemeModule extends Module {

    public final EnumSetting<ThemePreset> preset = add(new EnumSetting<>(
            "Preset", "Base colour palette of the interface", ThemePreset.MIDNIGHT));

    public final BooleanSetting customAccent = add(new BooleanSetting(
            "Custom Accent", "Override the preset's accent colours", false));

    public final ColorSetting accent = add(new ColorSetting(
            "Accent", "Primary accent colour", 0xFF8B5CF6, false).visibleWhen(customAccent::get));

    public final ColorSetting accentAlt = add(new ColorSetting(
            "Accent 2", "Secondary accent used in gradients", 0xFFC084FC, false).visibleWhen(customAccent::get));

    public final BooleanSetting headerGradient = add(new BooleanSetting(
            "Header Gradient", "Draw an accent gradient line under panel headers", true));

    public final NumberSetting panelOpacity = add(new NumberSetting(
            "Panel Opacity", "Opacity of panels and rows", 96, 40, 100, 1).withSuffix("%"));

    public final NumberSetting cornerRadius = add(new NumberSetting(
            "Corner Radius", "Roundness of panels and controls", 3, 0, 6, 1));

    public final BooleanSetting shadows = add(new BooleanSetting(
            "Shadows", "Soft drop shadow behind panels", true));

    public final BooleanSetting textShadow = add(new BooleanSetting(
            "Text Shadow", "Render text with a drop shadow", false));

    public ThemeModule() {
        super("Theme", "Colours, preset and look of the interface", Category.MISC, false);
    }
}
