package dev.telemetric.ui.confirm;

import dev.telemetric.game.ScreenOpener;

/**
 * Global confirmation dialog. {@link #show} opens a {@link ConfirmDialog}: over the ClickGUI when it is open
 * (it draws and routes input to {@link #current()}), otherwise in its own {@link ConfirmScreen}.
 * Only one dialog at a time; a new request replaces (cancels) the old one.
 */
public final class Confirm {

    private static ConfirmDialog current;

    private Confirm() {}

    public static void show(ConfirmRequest request) {
        if (current != null && !current.isClosing()) current.cancel();
        current = new ConfirmDialog(request);
        ScreenOpener.confirmHost(); // no-op while the ClickGUI (or a ConfirmScreen) is open
    }

    /** The dialog on screen, or {@code null}. Cleared once its close animation has finished. */
    public static ConfirmDialog current() {
        if (current != null && current.isFinished()) current = null;
        return current;
    }

    public static boolean isOpen() {
        return current() != null;
    }

    /** Cancels the dialog without animation (its host screen went away). */
    public static void dismiss() {
        if (current != null && !current.isClosing()) current.cancel();
        current = null;
    }
}
