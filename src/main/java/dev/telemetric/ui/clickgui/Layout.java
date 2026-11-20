package dev.telemetric.ui.clickgui;

/** Spacing constants for the ClickGUI (GUI units). Tweak them here, not in the components. */
public final class Layout {

    public static final int HEADER_HEIGHT = 20;
    public static final int MODULE_HEIGHT = 18;
    public static final int SETTING_HEIGHT = 16;
    public static final int OPTION_HEIGHT = 12;
    public static final int PAD = 6;
    public static final int SETTINGS_INDENT = 3;
    /** Extra indent per level for children of a group toggle. */
    public static final int GROUP_INDENT = 6;
    /** Empty space above the first and below the last setting of an open module. */
    public static final int SETTINGS_PAD_Y = 3;
    /** Width of the drag grip at the left edge of movable setting rows. */
    public static final int GRIP_WIDTH = 6;
    public static final int PANEL_GAP = 10;
    public static final int PANEL_MARGIN_TOP = 24;
    public static final int PANEL_FOOTER = 3;
    public static final int SHADOW_SIZE = 6;
    public static final int TEXT_OFFSET_Y = 3;
    public static final int SCROLL_STEP = 24;

    private Layout() {}

    /** The Y offset that vertically centres a line of text in a row of {@code rowHeight}. */
    public static int textY(int rowHeight) {
        return (rowHeight - 8) / 2;
    }
}
