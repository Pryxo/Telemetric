package dev.telemetric.map;

/**
 * The angled camera every map pixel is traced through (diorama look). The camera looks north and down at
 * {@link #PITCH_DEG} below the horizon, so the map shows block tops and their south faces. Map space is in blocks:
 * <ul>
 *   <li>{@code u = x} (east is right)</li>
 *   <li>{@code v = z * sin - y * cos} (south is down, higher blocks move up the screen)</li>
 * </ul>
 * Fixed north-up: there is no turning, so a baked tile never goes stale because of the view.
 */
public final class MapProjection {

    public static final double PITCH_DEG = 50;
    public static final double SIN = Math.sin(Math.toRadians(PITCH_DEG));
    public static final double COS = Math.cos(Math.toRadians(PITCH_DEG));
    /** How far north a ray moves per block it goes down. */
    public static final double Z_PER_Y = COS / SIN;

    /** Map units (blocks) per tile edge. */
    public static final int TILE_UNITS = 64;
    /** Texture pixels per map unit. */
    public static final int PX_PER_UNIT = 4;
    public static final int TILE_PX = TILE_UNITS * PX_PER_UNIT;

    private MapProjection() {}

    public static double u(double x) {
        return x;
    }

    public static double v(double y, double z) {
        return z * SIN - y * COS;
    }

    /** The z where a ray through map row {@code v} crosses height {@code y}. */
    public static double zAt(double v, double y) {
        return (v + y * COS) / SIN;
    }

    /** Tile index that holds map coordinate {@code m}. */
    public static int tile(double m) {
        return (int) Math.floor(m / TILE_UNITS);
    }
}
