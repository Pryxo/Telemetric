package dev.telemetric.module.impl.misc;

import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.notification.Toasts;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.theme.BackdropStyle;
import dev.telemetric.ui.theme.HeaderStyle;
import dev.telemetric.ui.theme.Theme;
import dev.telemetric.ui.theme.ThemePreset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** UI-only: the colours and look of the ClickGUI. {@link dev.telemetric.ui.theme.ThemeManager} reads it. */
public final class ThemeModule extends Module {

    private static final Theme DEFAULTS = ThemePreset.ARCTIC.theme();

    /** Category name → its colour under Category Colors (built-in categories only). */
    private final Map<String, ColorSetting> categoryColorsByName = new LinkedHashMap<>();

    public final EnumSetting<ThemePreset> preset = add(new EnumSetting<>(
            "Preset", "Base colour palette of the interface", ThemePreset.ARCTIC).searchable());

    public final GroupToggleSetting customAccent = add(new GroupToggleSetting(
            "Custom Accent", "Override the preset's accent colours (right-click to edit)", false));

    public final ColorSetting accent = add(customAccent.child(new ColorSetting(
            "Accent", "Primary accent colour", DEFAULTS.accent(), false).solidOnly()));

    public final ColorSetting accentAlt = add(customAccent.child(new ColorSetting(
            "Accent 2", "Secondary accent used in gradients", DEFAULTS.accentAlt(), false).solidOnly()));

    public final ActionSetting copyAccentPreset = add(customAccent.child(new ActionSetting(
            "Copy Accents From Preset", "Fill the custom accents with the selected preset's accents", () -> {
                accent.set(preset.get().theme().accent());
                accentAlt.set(preset.get().theme().accentAlt());
                Toasts.info("Copied accents from " + preset.get().displayName());
            })));

    public final GroupToggleSetting customColors = add(new GroupToggleSetting(
            "Custom Colors", "Use your own colours for every part of the interface instead of the preset's "
            + "(right-click to edit; starts from the current preset)", false));

    public final ColorSetting panelColor = color("Panel", "Panel body, tooltips and toasts", DEFAULTS.panel(), false);
    public final ColorSetting headerColor = color("Header", "Panel header (gradient start)", DEFAULTS.header(), false);
    public final ColorSetting headerEndColor = color("Header End", "Panel header (gradient end)", DEFAULTS.headerEnd(), false);
    public final ColorSetting rowColor = color("Row", "Module row", DEFAULTS.surface(), false);
    public final ColorSetting rowHoverColor = color("Row Hover", "Module row while hovered", DEFAULTS.surfaceHover(), false);
    public final ColorSetting rowEnabledColor = color("Row Enabled", "Module row of an enabled module", DEFAULTS.surfaceActive(), false);
    public final ColorSetting settingsColor = color("Settings Area", "Background of opened settings and dropdown lists", DEFAULTS.settingsBg(), false);
    public final ColorSetting controlColor = color("Controls", "Slider tracks, switches, input boxes, buttons", DEFAULTS.control(), false);
    public final ColorSetting borderColor = color("Borders", "Outlines and hairlines", DEFAULTS.border(), false);
    public final ColorSetting textColor = color("Text", "Primary text", DEFAULTS.text(), false);
    public final ColorSetting mutedTextColor = color("Muted Text", "Values and hints", DEFAULTS.textMuted(), false);
    public final ColorSetting disabledTextColor = color("Disabled Text", "Placeholders and disabled entries", DEFAULTS.textDisabled(), false);
    public final ColorSetting shadowColor = color("Shadow Color", "Drop shadow behind panels", DEFAULTS.shadow(), true);
    public final ColorSetting dimColor = color("Screen Dim", "Full-screen dim behind the GUI (its alpha is scaled by Background Dim)", DEFAULTS.backdrop(), true);

    public final ActionSetting copyPreset = add(customColors.child(new ActionSetting(
            "Copy Colors From Preset", "Fill the custom colours with the selected preset's colours", () -> {
                copyColorsFrom(preset.get().theme());
                Toasts.info("Copied colours from " + preset.get().displayName());
            })));

    public final GroupToggleSetting customHudColors = add(new GroupToggleSetting(
            "Custom HUD Colors", "Your own index (label) and value colours for every HUD element that doesn't use "
            + "the server colours (right-click to edit; off = accent / text colour)", false));

    public final ColorSetting hudIndexColor = add(customHudColors.child(new ColorSetting(
            "HUD Index", "Labels of HUD rows (\"Kills\" in \"Kills: 12\")", DEFAULTS.accent(), false)));

    public final ColorSetting hudValueColor = add(customHudColors.child(new ColorSetting(
            "HUD Value", "Values of HUD rows (\"12\" in \"Kills: 12\")", DEFAULTS.text(), false)));

    public final GroupToggleSetting categoryColors = add(new GroupToggleSetting(
            "Category Colors", "Give every panel (and the Settings windows of its modules) its own accent colour "
            + "(right-click to edit)", true));

    public final ColorSetting combatColor = categoryColor(Category.COMBAT, 0xFFEF4444);
    public final ColorSetting playerColor = categoryColor(Category.PLAYER, 0xFF22C55E);
    public final ColorSetting worldColor = categoryColor(Category.WORLD, 0xFF0EA5E9);
    public final ColorSetting trackingColor = categoryColor(Category.INVENTORY, 0xFFF59E0B);
    public final ColorSetting statsColor = categoryColor(Category.STATS, 0xFFA855F7);
    public final ColorSetting socialColor = categoryColor(Category.SOCIAL, 0xFFEC4899);
    public final ColorSetting miscColor = categoryColor(Category.MISC, 0xFF14B8A6);

    public final EnumSetting<HeaderStyle> headerStyle = add(new EnumSetting<>(
            "Header Style", "Line: dark header with an accent line. Accent Fill: header filled with the accent gradient",
            HeaderStyle.LINE));

    public final BooleanSetting headerGradient = add(new BooleanSetting(
            "Header Gradient", "Draw an accent gradient line under panel headers", true)
            .visibleWhen(() -> headerStyle.get() == HeaderStyle.LINE));

    public final BooleanSetting categoryIcons = add(new BooleanSetting(
            "Category Icons", "Small icon in front of every panel title", true));

    public final BooleanSetting flowingGradient = add(new BooleanSetting(
            "Flowing Gradient", "The accent gradient slowly flows along header lines, enabled bars and slider fills", true));

    public final NumberSetting glow = add(new NumberSetting(
            "Glow", "Soft accent glow around enabled bars, switches, slider knobs and dragged panels", 45, 0, 100, 1)
            .withSuffix("%"));

    public final EnumSetting<BackdropStyle> backdrop = add(new EnumSetting<>(
            "Backdrop", "The dim behind the GUI: Plain, tinted with the accent, or tinted with darker edges",
            BackdropStyle.VIGNETTE));

    public final BooleanSetting blurWorld = add(new BooleanSetting(
            "Blur World", "Blur the world behind the GUI (uses the vanilla Menu Background Blurriness option)", false));

    public final NumberSetting panelOpacity = add(new NumberSetting(
            "Panel Opacity", "Opacity of panels and rows", 96, 40, 100, 1).withSuffix("%"));

    public final NumberSetting cornerRadius = add(new NumberSetting(
            "Corner Radius", "Roundness of panels and controls", 6, 0, 12, 1));

    public final BooleanSetting shadows = add(new BooleanSetting(
            "Shadows", "Soft drop shadow behind panels", true));

    public final BooleanSetting textShadow = add(new BooleanSetting(
            "Text Shadow", "Render text with a drop shadow", false));

    public ThemeModule() {
        super("Theme", "Colours, preset and look of the interface", Category.MISC, false);
        // The first switch-on starts from the current preset instead of Arctic's colours. (On a profile load the toggle
        // loads before its colours, which then overwrite this copy.)
        customAccent.onChange(on -> {
            if (on && accent.get().equals(accent.getDefault()) && accentAlt.get().equals(accentAlt.getDefault())) {
                Theme t = preset.get().theme();
                accent.set(t.accent());
                accentAlt.set(t.accentAlt());
            }
        });
        customColors.onChange(on -> {
            if (on && paletteColors().stream().allMatch(c -> c.get().equals(c.getDefault()))) {
                copyColorsFrom(preset.get().theme());
            }
        });
        customHudColors.onChange(on -> {
            if (on && hudIndexColor.get().equals(hudIndexColor.getDefault())
                    && hudValueColor.get().equals(hudValueColor.getDefault())) {
                Theme t = resolvedPalette();
                hudIndexColor.set(t.accent());
                hudValueColor.set(t.text());
            }
        });
    }

    private ColorSetting color(String name, String description, int defaultArgb, boolean alpha) {
        return add(customColors.child(new ColorSetting(name, description, defaultArgb, alpha).solidOnly()));
    }

    private ColorSetting categoryColor(Category category, int defaultArgb) {
        ColorSetting setting = add(categoryColors.child(new ColorSetting(
                category.getName(), "Accent of the " + category.getName() + " panel", defaultArgb, false).solidOnly()));
        categoryColorsByName.put(category.getName(), setting);
        return setting;
    }

    /**
     * The accent of {@code category} under Category Colors. Categories without their own setting (add-ons) get the
     * theme accent turned around the colour wheel by their position, so they still differ.
     */
    public int categoryAccent(Category category, int themeAccent) {
        ColorSetting setting = categoryColorsByName.get(category.getName());
        if (setting != null) return setting.get();
        int index = Category.values().indexOf(category);
        return ColorUtil.hueShift(themeAccent, 0.38f * (index + 1));
    }

    private List<ColorSetting> paletteColors() {
        return List.of(panelColor, headerColor, headerEndColor, rowColor, rowHoverColor, rowEnabledColor, settingsColor,
                controlColor, borderColor, textColor, mutedTextColor, disabledTextColor, shadowColor, dimColor);
    }

    private void copyColorsFrom(Theme t) {
        panelColor.set(t.panel());
        headerColor.set(t.header());
        headerEndColor.set(t.headerEnd());
        rowColor.set(t.surface());
        rowHoverColor.set(t.surfaceHover());
        rowEnabledColor.set(t.surfaceActive());
        settingsColor.set(t.settingsBg());
        controlColor.set(t.control());
        borderColor.set(t.border());
        textColor.set(t.text());
        mutedTextColor.set(t.textMuted());
        disabledTextColor.set(t.textDisabled());
        shadowColor.set(t.shadow());
        dimColor.set(t.backdrop());
    }

    /** The preset palette with the Custom Colors / Custom Accent overrides applied (before panel opacity). */
    public Theme resolvedPalette() {
        Theme theme = preset.get().theme();
        if (customColors.get()) {
            theme = new Theme(dimColor.get(), panelColor.get(), headerColor.get(), headerEndColor.get(),
                    rowColor.get(), rowHoverColor.get(), rowEnabledColor.get(), settingsColor.get(),
                    controlColor.get(), borderColor.get(), textColor.get(), mutedTextColor.get(), disabledTextColor.get(),
                    theme.accent(), theme.accentAlt(), shadowColor.get());
        }
        if (customAccent.get()) theme = theme.withAccent(accent.get(), accentAlt.get());
        return theme;
    }
}
