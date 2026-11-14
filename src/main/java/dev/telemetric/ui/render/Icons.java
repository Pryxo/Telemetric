package dev.telemetric.ui.render;

/** Tiny pixel icons drawn from rects (no textures needed). All accept an animation progress {@code t}. */
public final class Icons {

    private Icons() {}

    /** Chevron morphing from "›" (t = 0, closed) to "⌄" (t = 1, open), centred on (cx, cy). */
    public static void chevron(Render2D r, int cx, int cy, double t, int color) {
        for (int i = 0; i < 3; i++) {
            int cxClosed = cx - 1 + i, cyClosedA = cy - 2 + i, cyClosedB = cy + 2 - i;
            int cyOpen = cy - 1 + i, cxOpenA = cx - 2 + i, cxOpenB = cx + 2 - i;
            r.rect(round(lerp(cxClosed, cxOpenA, t)), round(lerp(cyClosedA, cyOpen, t)), 1, 1, color);
            r.rect(round(lerp(cxClosed, cxOpenB, t)), round(lerp(cyClosedB, cyOpen, t)), 1, 1, color);
        }
    }

    /** "+" that collapses into "−" as {@code t} goes 0 → 1. */
    public static void plusMinus(Render2D r, int cx, int cy, double t, int color) {
        r.rect(cx - 3, cy, 7, 1, color);
        int half = round(3 * (1 - t));
        if (half > 0) r.rect(cx, cy - half, 1, half * 2 + 1, color);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int round(double v) {
        return (int) Math.round(v);
    }
}
