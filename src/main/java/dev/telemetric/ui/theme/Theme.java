package dev.telemetric.ui.theme;

import dev.telemetric.ui.render.ColorUtil;

/**
 * An immutable colour palette (all ARGB). Components read semantic tokens from here and never use literals.
 *
 * @param backdrop     full-screen dim behind the GUI (alpha is scaled by the "Background Dim" setting)
 * @param panel        panel body
 * @param header       panel header (gradient start)
 * @param headerEnd    panel header (gradient end)
 * @param surface      module row
 * @param surfaceHover module row hovered
 * @param surfaceActive module row when the module is enabled
 * @param settingsBg   background of an expanded settings accordion
 * @param control      tracks / inputs / switch off-state
 * @param border       hairlines and outlines
 * @param text         primary text
 * @param textMuted    secondary text (values, hints)
 * @param textDisabled disabled text
 * @param accent       primary accent (enabled state, slider fill, focus)
 * @param accentAlt    secondary accent (gradients)
 * @param shadow       drop shadow colour
 */
public record Theme(
        int backdrop,
        int panel,
        int header,
        int headerEnd,
        int surface,
        int surfaceHover,
        int surfaceActive,
        int settingsBg,
        int control,
        int border,
        int text,
        int textMuted,
        int textDisabled,
        int accent,
        int accentAlt,
        int shadow
) {

    /** Interpolates the colours one component at a time (used for animated theme switches). */
    public static Theme lerp(Theme a, Theme b, double t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        return new Theme(
                ColorUtil.lerp(a.backdrop, b.backdrop, t),
                ColorUtil.lerp(a.panel, b.panel, t),
                ColorUtil.lerp(a.header, b.header, t),
                ColorUtil.lerp(a.headerEnd, b.headerEnd, t),
                ColorUtil.lerp(a.surface, b.surface, t),
                ColorUtil.lerp(a.surfaceHover, b.surfaceHover, t),
                ColorUtil.lerp(a.surfaceActive, b.surfaceActive, t),
                ColorUtil.lerp(a.settingsBg, b.settingsBg, t),
                ColorUtil.lerp(a.control, b.control, t),
                ColorUtil.lerp(a.border, b.border, t),
                ColorUtil.lerp(a.text, b.text, t),
                ColorUtil.lerp(a.textMuted, b.textMuted, t),
                ColorUtil.lerp(a.textDisabled, b.textDisabled, t),
                ColorUtil.lerp(a.accent, b.accent, t),
                ColorUtil.lerp(a.accentAlt, b.accentAlt, t),
                ColorUtil.lerp(a.shadow, b.shadow, t));
    }

    /** A copy with a different accent pair. */
    public Theme withAccent(int newAccent, int newAccentAlt) {
        return new Theme(backdrop, panel, header, headerEnd, surface, surfaceHover, surfaceActive, settingsBg,
                control, border, text, textMuted, textDisabled, newAccent, newAccentAlt, shadow);
    }

    /** A copy with the alpha of the panel-like surfaces multiplied by {@code opacity} (0..1). */
    public Theme withSurfaceOpacity(double opacity) {
        return new Theme(backdrop,
                ColorUtil.fade(panel, opacity), ColorUtil.fade(header, opacity), ColorUtil.fade(headerEnd, opacity),
                ColorUtil.fade(surface, opacity), ColorUtil.fade(surfaceHover, opacity),
                ColorUtil.fade(surfaceActive, opacity), ColorUtil.fade(settingsBg, opacity),
                control, border, text, textMuted, textDisabled, accent, accentAlt, shadow);
    }
}
