package dev.telemetric.map;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much of each loaded map tile texture actually shows ground: a {@value #CELLS}×{@value #CELLS} grid of the
 * share of non-transparent pixels per cell. The map fog reads it to know where the map is known. Thread-safe (the
 * fog renders off the client thread). Pure Java.
 */
public final class MapCoverage {

    public static final int CELLS = 32;

    private static final Map<String, byte[]> GRIDS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong VERSION = new java.util.concurrent.atomic.AtomicLong();

    /** Goes up whenever any tile's coverage is added or removed. */
    public static long version() {
        return VERSION.get();
    }

    private MapCoverage() {}

    /** Records the coverage of texture {@code id} from its ARGB pixels ({@code size}×{@code size}). */
    public static void put(String id, int[] argb, int size) {
        put(id, grid(size, (x, y) -> argb[y * size + x] >>> 24));
    }

    /** Pixel alpha (0..255) at (x, y). */
    public interface AlphaSource {
        int alpha(int x, int y);
    }

    /** Builds a coverage grid from any pixel source (e.g. a PNG while it loads). */
    public static byte[] grid(int size, AlphaSource src) {
        byte[] out = new byte[CELLS * CELLS];
        int cell = Math.max(1, size / CELLS), step = Math.max(1, cell / 4); // 4×4 samples per cell is plenty
        for (int cy = 0; cy < CELLS; cy++) {
            for (int cx = 0; cx < CELLS; cx++) {
                int hit = 0, n = 0;
                for (int y = cy * cell + step / 2; y < (cy + 1) * cell && y < size; y += step) {
                    for (int x = cx * cell + step / 2; x < (cx + 1) * cell && x < size; x += step) {
                        if (src.alpha(x, y) > 24) hit++;
                        n++;
                    }
                }
                out[cy * CELLS + cx] = (byte) (n == 0 ? 0 : Math.round(255f * hit / n));
            }
        }
        return out;
    }

    public static void put(String id, byte[] grid) {
        GRIDS.put(id, grid);
        VERSION.incrementAndGet();
    }

    public static void remove(String id) {
        if (GRIDS.remove(id) != null) VERSION.incrementAndGet();
    }

    /**
     * Coverage 0..1 of texture {@code id} at (fx, fy) inside it (0..1 each), or -1 if the texture isn't loaded.
     */
    public static float at(String id, double fx, double fy) {
        byte[] g = GRIDS.get(id);
        if (g == null) return -1;
        int cx = Math.clamp((int) (fx * CELLS), 0, CELLS - 1), cy = Math.clamp((int) (fy * CELLS), 0, CELLS - 1);
        return (g[cy * CELLS + cx] & 0xFF) / 255f;
    }

    /** Texture id of map tile (tu, tv). */
    public interface TileIds {
        String id(int tu, int tv);
    }

    /**
     * Known-area lookup over square map tiles {@code units} map units wide; where no tile texture is loaded it asks
     * {@code fallback} (or answers 0 = unknown).
     */
    public static FogField.Known tiles(double units, TileIds ids, FogField.Known fallback) {
        Map<Long, String> cache = new ConcurrentHashMap<>();
        return (u, v) -> {
            int tu = (int) Math.floor(u / units), tv = (int) Math.floor(v / units);
            String id = cache.computeIfAbsent((long) tu << 32 | (tv & 0xFFFFFFFFL), k -> ids.id(tu, tv));
            float c = at(id, u / units - tu, v / units - tv);
            return c >= 0 ? c : fallback != null ? fallback.at(u, v) : 0;
        };
    }
}
