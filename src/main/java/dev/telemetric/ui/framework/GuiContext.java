package dev.telemetric.ui.framework;

import dev.telemetric.Telemetric;
import dev.telemetric.module.impl.misc.InterfaceModule;
import dev.telemetric.ui.theme.Theme;
import dev.telemetric.ui.theme.ThemeManager;

/**
 * Shared per-screen state that every component gets: theme access, keyboard focus and tooltip requests. It keeps
 * static globals out of the UI tree.
 */
public final class GuiContext {

    private final ThemeManager themes;
    private UIComponent focused;
    private UIComponent captured;

    private String tooltipText;
    private String tooltipTitle;
    private Object tooltipOwner;
    private boolean tooltipWarning;

    public GuiContext(ThemeManager themes) {
        this.themes = themes;
    }

    // ------------------------------------------------------------------ theme

    private Theme paletteOverride;

    public Theme palette() {
        return paletteOverride != null ? paletteOverride : themes.palette();
    }

    /**
     * Makes {@link #palette()} return {@code palette} (or the theme again for {@code null}) until the next call. Returns
     * the previous override, so callers can restore it in a {@code finally}.
     */
    public Theme overridePalette(Theme palette) {
        Theme previous = paletteOverride;
        paletteOverride = palette;
        return previous;
    }

    public ThemeManager themes() {
        return themes;
    }

    public int radius() {
        return themes.cornerRadius();
    }

    public InterfaceModule ui() {
        return Telemetric.get().modules().get(InterfaceModule.class);
    }

    // ------------------------------------------------------------------ focus

    /** Gives keyboard focus to {@code component} (the previous focus gets notified). */
    public void focus(UIComponent component) {
        if (focused == component) return;
        UIComponent previous = focused;
        focused = component;
        if (previous != null) previous.onFocusLost();
    }

    public void clearFocus() {
        focus(null);
    }

    public void releaseFocus(UIComponent component) {
        if (focused == component) clearFocus();
    }

    public UIComponent getFocused() {
        return focused;
    }

    public boolean isFocused(UIComponent component) {
        return focused == component;
    }

    // ------------------------------------------------------------------ mouse capture

    /**
     * Captures the mouse for a drag (slider, colour picker). While it's captured, parents pass the real mouse
     * coordinates even outside clip areas, so the drag keeps tracking.
     */
    public void capture(UIComponent component) {
        captured = component;
    }

    public void releaseCapture(UIComponent component) {
        if (captured == component) captured = null;
    }

    public boolean hasCapture() {
        return captured != null;
    }

    // ------------------------------------------------------------------ tooltip

    /** Requests a tooltip this frame. {@code owner} identifies the hovered thing (for dwell timing). */
    public void requestTooltip(Object owner, String text) {
        requestTooltip(owner, null, text);
    }

    /** A tooltip with a {@code title} (feature or setting name) drawn bigger in the accent colour above the text. */
    public void requestTooltip(Object owner, String title, String text) {
        if (text == null || text.isBlank()) return;
        tooltipOwner = owner;
        tooltipTitle = title;
        tooltipText = text;
        tooltipWarning = false;
    }

    /** A tooltip in the fixed yellow warning colours. */
    public void requestWarningTooltip(Object owner, String title, String text) {
        requestTooltip(owner, title, text);
        if (tooltipOwner == owner) tooltipWarning = true;
    }

    public void clearTooltipRequest() {
        tooltipOwner = null;
        tooltipTitle = null;
        tooltipText = null;
        tooltipWarning = false;
    }

    public boolean tooltipWarning() { return tooltipWarning; }

    public String tooltipTitle() { return tooltipTitle; }
    public Object tooltipOwner() { return tooltipOwner; }
    public String tooltipText() { return tooltipText; }
}
