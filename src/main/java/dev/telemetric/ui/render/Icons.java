package dev.telemetric.ui.render;

/**
 * GUI icons. Most are <a href="https://lucide.dev/icons/">Lucide</a> icons (ISC licence, see
 * {@code textures/gui/icons/LICENSE-lucide.txt}) drawn through {@link Render2D#icon}. The int centre (cx, cy) is the
 * pixel the old pixel-art icons were centred on, so the Lucide icon is centred on that pixel's middle.
 */
public final class Icons {

    /** Default icon size in GUI units. */
    public static final double SIZE = 8;

    private Icons() {}

    /** Chevron turning from "›" (t = 0, closed) to "⌄" (t = 1, open), centred on (cx, cy). */
    public static void chevron(Render2D r, int cx, int cy, double t, int color) {
        r.icon("chevron-right", cx + 0.5, cy + 0.5, SIZE, color, t * Math.PI / 2);
    }

    /** Warning triangle fitted into the {@code w × h} box at (x, y). */
    public static void warning(Render2D r, int x, int y, int w, int h, int color) {
        r.icon("warning", x + w / 2.0, y + h / 2.0, Math.max(w, h) * 1.2, color);
    }

    /** A star centred on (cx, cy): filled (pinned) or just its outline. */
    public static void star(Render2D r, int cx, int cy, boolean filled, int color) {
        r.icon(filled ? "star-filled" : "star", cx + 0.5, cy + 0.5, SIZE, color);
    }

    /** A gear centred on (cx, cy). {@code turn} (0..1) turns it by one tooth step. */
    public static void gear(Render2D r, int cx, int cy, double turn, int color) {
        r.icon("settings", cx + 0.5, cy + 0.5, 9, color, turn * Math.PI / 4);
    }

    /** An "×" centred on (cx, cy). */
    public static void close(Render2D r, int cx, int cy, int color) {
        r.icon("x", cx + 0.5, cy + 0.5, SIZE, color);
    }

    /** A counter-clockwise arrow ("reset view") centred on (cx, cy). */
    public static void resetView(Render2D r, int cx, int cy, int color) {
        r.icon("rotate-ccw", cx + 0.5, cy + 0.5, SIZE, color);
    }

    /** A small magnifying glass (ring + handle to the bottom right); {@code hole} is the colour behind it. */
    public static void magnifier(Render2D r, int cx, int cy, int color, int hole) {
        r.circle(cx - 1, cy - 1, 3.5, color);
        r.circle(cx - 1, cy - 1, 2.2, hole);
        r.rect(cx + 1, cy + 1, 1, 1, color);
        r.rect(cx + 2, cy + 2, 1, 1, color);
        r.rect(cx + 3, cy + 3, 1, 1, color);
    }

    /** A "‹" back arrow centred on (cx, cy). */
    public static void back(Render2D r, int cx, int cy, int color) {
        r.icon("chevron-left", cx + 0.5, cy + 0.5, SIZE, color);
    }

    /**
     * The panel-header icon of a built-in category ({@code textures/gui/icons/cat-*.png}, drawn in the Lucide style),
     * or {@code null} for categories without one (add-ons).
     */
    public static String categoryIcon(String categoryName) {
        return switch (categoryName) {
            case "Combat" -> "cat-combat";
            case "Player" -> "cat-player";
            case "World" -> "cat-world";
            case "UI + Tracking" -> "cat-tracking";
            case "Stats" -> "cat-stats";
            case "Social" -> "cat-social";
            case "Misc" -> "cat-misc";
            default -> null;
        };
    }

    /** Round diagonal dots of a resize corner; ({@code right}, {@code bottom}) is the corner pixel. */
    public static void resizeGrip(Render2D r, int right, int bottom, int color) {
        for (int row = 0; row < 3; row++) {
            for (int col = 2 - row; col < 3; col++) {
                r.circle(right - 0.5 - (2 - col) * 2.5, bottom - 0.5 - (2 - row) * 2.5, 0.85, color);
            }
        }
    }
}
