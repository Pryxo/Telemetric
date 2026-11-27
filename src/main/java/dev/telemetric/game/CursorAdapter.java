package dev.telemetric.game;

/**
 * Keep Cursor state, read by {@code MouseHandlerMixin}: when on, the mouse is never moved to the window centre on
 * grab (screen closed) or release (screen opened), so container → container keeps the cursor where it was.
 */
public final class CursorAdapter {

    private static volatile boolean enabled;

    private CursorAdapter() {}

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }
}
