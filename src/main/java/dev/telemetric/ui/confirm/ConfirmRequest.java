package dev.telemetric.ui.confirm;

/**
 * One question for {@link Confirm#show}. {@code onConfirm} runs when the user clicks the confirm button,
 * {@code onCancel} (may be {@code null}) on Cancel, Esc or a click outside the box.
 */
public record ConfirmRequest(String title, String message, String confirmLabel, String cancelLabel,
                             Runnable onConfirm, Runnable onCancel) {

    /** A warning with "Enable" / "Cancel" buttons. */
    public static ConfirmRequest warning(String title, String message, Runnable onConfirm) {
        return new ConfirmRequest(title, message, "Enable", "Cancel", onConfirm, null);
    }
}
