package dev.telemetric.ui.animation;

/**
 * Motion timing tokens (milliseconds). Always use these instead of literal numbers.
 * <p>
 * Based on production-UI measurements: median ≈ 250 ms, most transitions within 150–300 ms,
 * nothing above 500 ms without a strong reason. See docs/animation.md.
 */
public final class Durations {

    /** Instant feedback: press flashes, caret, click acknowledgement (50–100). */
    public static final int INSTANT = 75;
    /** Micro-interactions: switch knobs, checkbox ticks, slider fill (80–150). */
    public static final int MICRO = 120;
    /** Hover / focus / colour changes (100–200). */
    public static final int HOVER = 150;
    /** Tooltips, dropdowns, popovers (150–250). */
    public static final int TOOLTIP = 200;
    public static final int DROPDOWN = 200;
    /** Standard component transition — the strong default (150–300). */
    public static final int STANDARD = 250;
    /** Cards / accordions (200–300). */
    public static final int ACCORDION = 250;
    /** Modals / dialogs (200–300). */
    public static final int MODAL = 250;
    /** Drawers / sheets / toasts sliding in (250–350). */
    public static final int DRAWER = 300;
    /** Large / full-screen transitions: opening the ClickGUI (300–400). */
    public static final int SCREEN = 350;
    /** Very large / complex sequences (400–500). */
    public static final int COMPLEX = 450;
    /** Hard ceiling. */
    public static final int MAX = 500;

    /** One pass of a looping ambient effect (neon sweep on enabled modules). Not a transition: not capped by MAX. */
    public static final int AMBIENT_LOOP = 1600;

    /** Delay before a tooltip starts to appear (not a transition, a dwell time). */
    public static final int TOOLTIP_DELAY = 400;
    /** Stagger between panels during the open sequence. */
    public static final int STAGGER = 35;
    /** Half period of a blinking text caret (on, then off for as long). */
    public static final int CARET_BLINK = 530;
    /** Half period of a flashing warning (Low Health Warning text: bright, then normal for as long). */
    public static final int ALERT_FLASH = 250;

    private static double speed = 1.0;
    private static boolean enabled = true;

    private Durations() {}

    /**
     * Applies the user's animation preferences. {@code speed} &gt; 1 = faster.
     * Result is clamped to {@link #MAX} unless animations are disabled (then 0).
     */
    public static int scaled(int baseMs) {
        if (!enabled || baseMs <= 0) return 0;
        return (int) Math.min(MAX, Math.round(baseMs / speed));
    }

    /** Period of a looping ambient effect, speed-scaled but not capped; 0 when animations are disabled. */
    public static int loop(int baseMs) {
        if (!enabled || baseMs <= 0) return 0;
        return (int) Math.round(baseMs / speed);
    }

    public static void setSpeed(double multiplier) {
        speed = Math.clamp(multiplier, 0.25, 4.0);
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }
}
