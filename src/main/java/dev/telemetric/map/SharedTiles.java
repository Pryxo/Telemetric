package dev.telemetric.map;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which pre-baked world tiles ({@link WorldTiles}) a map shows: asks the source for the files of the tiles in view,
 * hands back PNGs to load as textures and texture ids to release when tiles go out of use. The render side does the
 * texture work ({@code game/MapAdapter}). Pure Java, client thread only.
 */
public final class SharedTiles {

    /** Finds a tile's PNG on disk, or null while it's missing (downloading) or if the area has no such tile. */
    @FunctionalInterface
    public interface Source {
        Path file(int zoom, int tu, int tv);
    }

    /** A PNG to load as texture {@code id}. */
    public record Load(String id, Path file) {}

    private static final int MAX_TILES = 80;

    private final String prefix;
    private final Source source;
    private final int maxZoom;
    private final Map<String, Long> used = new HashMap<>();
    private final List<Load> loads = new ArrayList<>();
    private final List<String> released = new ArrayList<>();
    private long clock;

    /** @param prefix texture id prefix ({@code "world_hub_3"}, include the area version so new tiles get new ids) */
    public SharedTiles(String prefix, Source source, int maxZoom) {
        this.prefix = prefix;
        this.source = source;
        this.maxZoom = maxZoom;
    }

    public int maxZoom() {
        return maxZoom;
    }

    public String textureId(int zoom, int tu, int tv) {
        return "telemetric:map/" + prefix + "_" + zoom + "_" + tu + "_" + tv;
    }

    /**
     * Marks the tiles of {@code zoom} (and the next coarser zoom, drawn underneath while the fine ones load) that
     * cover map rect {@code [u0, u1] × [v0, v1]} as used, and queues the ones not loaded yet.
     */
    public void request(int zoom, double u0, double v0, double u1, double v1, java.util.function.Predicate<String> loaded) {
        clock++;
        for (int z = zoom; z <= Math.min(maxZoom, zoom + 1); z++) {
            int tu0 = WorldTiles.tile(u0, z), tu1 = WorldTiles.tile(u1, z);
            int tv0 = WorldTiles.tile(v0, z), tv1 = WorldTiles.tile(v1, z);
            if ((long) (tu1 - tu0 + 1) * (tv1 - tv0 + 1) > MAX_TILES / 2) continue; // too many at this zoom
            for (int tv = tv0; tv <= tv1; tv++) {
                for (int tu = tu0; tu <= tu1; tu++) {
                    String id = textureId(z, tu, tv);
                    used.put(id, clock);
                    if (loaded.test(id)) continue;
                    Path f = source.file(z, tu, tv);
                    if (f != null) loads.add(new Load(id, f));
                }
            }
        }
        evict();
    }

    /** PNGs to load since the last call. */
    public List<Load> drainLoads() {
        List<Load> out = new ArrayList<>(loads);
        loads.clear();
        return out;
    }

    /** Texture ids no longer in use. */
    public List<String> drainReleased() {
        List<String> out = new ArrayList<>(released);
        released.clear();
        return out;
    }

    /** Lets every tile go (its textures come out of {@link #drainReleased}). */
    public void clear() {
        released.addAll(used.keySet());
        used.clear();
        loads.clear();
    }

    private void evict() {
        if (used.size() <= MAX_TILES) return;
        List<Map.Entry<String, Long>> all = new ArrayList<>(used.entrySet());
        all.sort(Map.Entry.comparingByValue());
        for (Map.Entry<String, Long> e : all) {
            if (used.size() <= MAX_TILES) break;
            if (e.getValue() == clock) continue;
            used.remove(e.getKey());
            released.add(e.getKey());
        }
    }
}
