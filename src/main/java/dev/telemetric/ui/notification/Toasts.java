package dev.telemetric.ui.notification;

import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Short status messages ("Saved profile 'pvp'") for instant feedback on actions.
 * Post from anywhere; the ClickGUI renders them (see {@code ToastRenderer}).
 */
public final class Toasts {

    public enum Kind { INFO, SUCCESS, ERROR }

    /** How long a toast stays fully visible. */
    public static final long LIFETIME_MS = 2200;
    private static final int MAX_VISIBLE = 4;

    public static final class Toast {
        public final String message;
        public final Kind kind;
        public final long createdAt = System.currentTimeMillis();
        /** 0 → 1 slide/fade in, back to 0 when expiring. */
        public final Animation presence = new Animation(Durations.DRAWER, Easing.OUT_CUBIC, 0);
        /** Animated vertical slot so the stack reflows smoothly. */
        public final Animation slot = new Animation(Durations.STANDARD, Easing.OUT_CUBIC, 0);

        Toast(String message, Kind kind) {
            this.message = message;
            this.kind = kind;
            presence.animateTo(1);
        }

        public boolean expired() {
            return System.currentTimeMillis() - createdAt > LIFETIME_MS;
        }
    }

    private static final List<Toast> ACTIVE = new ArrayList<>();

    private Toasts() {}

    public static void info(String message) { push(message, Kind.INFO); }
    public static void success(String message) { push(message, Kind.SUCCESS); }
    public static void error(String message) { push(message, Kind.ERROR); }

    public static synchronized void push(String message, Kind kind) {
        ACTIVE.add(new Toast(message, kind));
        while (ACTIVE.size() > MAX_VISIBLE) ACTIVE.removeFirst();
    }

    /** Advances lifecycle; returns a snapshot of toasts to draw. */
    public static synchronized List<Toast> tick() {
        ACTIVE.removeIf(t -> t.expired() && t.presence.isAt(0));
        for (Toast t : ACTIVE) if (t.expired()) t.presence.animateTo(0);
        return Collections.unmodifiableList(new ArrayList<>(ACTIVE));
    }
}
