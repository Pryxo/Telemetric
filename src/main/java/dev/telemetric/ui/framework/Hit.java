package dev.telemetric.ui.framework;

/** Hit tests for mouse input. */
public final class Hit {

    private Hit() {}

    /** True when ({@code px}, {@code py}) is inside the rect at ({@code x}, {@code y}) of size {@code w} × {@code h}. */
    public static boolean inside(double px, double py, double x, double y, double w, double h) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
