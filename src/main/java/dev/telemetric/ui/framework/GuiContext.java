package dev.telemetric.ui.framework;

import dev.telemetric.Telemetric;
import dev.telemetric.module.impl.misc.InterfaceModule;
import dev.telemetric.ui.theme.Theme;
import dev.telemetric.ui.theme.ThemeManager;

/**
 * Per-screen shared state handed to every component: theme access, keyboard focus and tooltip requests.
 * Avoids static globals inside the UI tree.
 */
public final class GuiContext {

    private final ThemeManager themes;
    private UIComponent focused;
    private UIComponent captured;

    private String tooltipText;
    private String tooltipTitle;
    private Object tooltipOwner;

    public GuiContext(ThemeManager themes) {
        this.themes = themes;
    }

    // ------------------------------------------------------------------ theme

    private Theme paletteOverride;

    public Theme palette() {
        return paletteOverride != null ? paletteOverride : themes.palette();
    }

    /**
     * Makes {@link #palette()} return {@code palette} (or the theme again for {@code null}) until the next call.
     * Returns the previous override so callers can restore it in a {@code finally}.
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

    /** Gives keyboard focus to {@code component} (previous focus is notified). */
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
     * Captures the mouse for a drag (slider, colour picker). While captured, parents pass real mouse
     * coordinates even outside clip areas so the drag keeps tracking.
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

    /** Request a tooltip this frame. {@code owner} identifies the hovered thing (for dwell timing). */
    public void requestTooltip(Object owner, String text) {
        requestTooltip(owner, null, text);
    }

    /** Tooltip with a {@code title} (feature/setting name) drawn bigger in the accent colour above the text. */
    public void requestTooltip(Object owner, String title, String text) {
        if (text == null || text.isBlank()) return;
        tooltipOwner = owner;
        tooltipTitle = title;
        tooltipText = text;
    }

    public void clearTooltipRequest() {
        tooltipOwner = null;
        tooltipTitle = null;
        tooltipText = null;
    }

    public String tooltipTitle() { return tooltipTitle; }
    public Object tooltipOwner() { return tooltipOwner; }
    public String tooltipText() { return tooltipText; }
}
