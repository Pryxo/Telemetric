package dev.telemetric.map;

/**
 * Layout of the pre-baked world map tiles shipped in the data repo ({@code Maps/<area>/<zoom>/<tu>_<tv>.png}). Same
 * map space as {@link MapProjection} (u = x, v = z·sin − y·cos). A zoom-0 tile covers {@link #TILE_UNITS} × that many
 * map units at {@link MapProjection#PX_PER_UNIT} px per unit (the live-bake quality); every zoom step doubles the
 * units per tile and halves the detail, the PNG stays {@link #TILE_PX} px. Pure Java.
 */
public final class WorldTiles {

    /** Map units per edge of a zoom-0 tile. */
    public static final int TILE_UNITS = 128;
    /** PNG edge in pixels (every zoom). */
    public static final int TILE_PX = TILE_UNITS * MapProjection.PX_PER_UNIT;
    /** Zoom levels exported (0 … MAX_ZOOM). */
    public static final int MAX_ZOOM = 3;

    private WorldTiles() {}

    public static int units(int zoom) {
        return TILE_UNITS << zoom;
    }

    public static double pxPerUnit(int zoom) {
        return (double) TILE_PX / units(zoom);
    }

    public static int tile(double m, int zoom) {
        return (int) Math.floor(m / units(zoom));
    }

    /** Path of a tile inside its area folder. */
    public static String file(int zoom, int tu, int tv) {
        return zoom + "/" + tu + "_" + tv + ".png";
    }

    /**
     * The coarsest zoom that still has at least {@code screenPxPerUnit} texture pixels per screen pixel (so the
     * texture is never magnified more than needed), clamped to {@code [0, maxZoom]}.
     */
    public static int zoomFor(double screenPxPerUnit, int maxZoom) {
        int z = 0;
        while (z < maxZoom && pxPerUnit(z + 1) >= screenPxPerUnit) z++;
        return z;
    }
}
