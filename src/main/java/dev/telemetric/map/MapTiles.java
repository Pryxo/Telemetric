package dev.telemetric.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static dev.telemetric.map.MapProjection.COS;
import static dev.telemetric.map.MapProjection.SIN;
import static dev.telemetric.map.MapProjection.TILE_UNITS;

/**
 * Live-baked tiles over one {@link VoxelStore}: which tiles exist, which are stale, baking them on one background thread
 * and composing them with the current {@link MapMask}. The render side asks for the tiles it shows
 * ({@link #request}), uploads finished pixels ({@link #pollUploads}) and draws by {@link #textureId}.
 * <p>
 * Pure Java. Threading: {@link #request}, {@link #setView}, {@link #setMask}, {@link #pollUploads} and {@link #clear}
 * run on the client thread; bakes run on the map thread and hand results back through a queue.
 */
public final class MapTiles {

    /** Tile pixels ready to upload as a texture. */
    public record Upload(String textureId, int[] argb) {}

    private static final int MAX_IN_FLIGHT = 2;
    /** Most tiles kept (oldest unused ones are dropped, textures released). */
    private static final int MAX_TILES = 96;

    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Telemetric-Map");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private final String prefix;
    private final VoxelStore store;
    private final Map<Long, Tile> tiles = new HashMap<>();
    private final ConcurrentLinkedQueue<Upload> ready = new ConcurrentLinkedQueue<>();
    private final List<String> released = new ArrayList<>();
    private MapCut cut;
    private int minY;
    private int viewGeneration;
    private volatile MapMask mask = MapMask.ALL;
    private long maskVersion;
    private int inFlight;
    private long useClock;

    private static final class Tile {
        final int tu, tv;
        TileBaker.Baked baked;
        boolean dirty = true, baking, uploaded;
        long composedMask = -1;
        long lastUse;

        Tile(int tu, int tv) {
            this.tu = tu;
            this.tv = tv;
        }
    }

    /** @param prefix texture id prefix, e.g. {@code "dungeon"} (ids look like {@code telemetric:map/dungeon_3_-2}) */
    public MapTiles(String prefix, VoxelStore store) {
        this.prefix = prefix;
        this.store = store;
    }

    public VoxelStore store() {
        return store;
    }

    /** Sets the cut and the lowest layer; every tile gets baked again. */
    public void setView(MapCut cut, int minY) {
        this.cut = cut;
        this.minY = minY;
        viewGeneration++;
        for (Tile t : tiles.values()) t.dirty = true;
    }

    /**
     * Swaps in a new cut where only some columns changed: just the tiles those columns reach get baked again.
     * {@code changed} holds column keys ({@code x << 32 | z}).
     */
    public void setCut(MapCut cut, int minY, it.unimi.dsi.fastutil.longs.LongCollection changed) {
        if (this.cut == null || minY != this.minY) {
            setView(cut, minY);
            return;
        }
        this.cut = cut;
        int top = cut.max();
        java.util.Set<Long> marked = new java.util.HashSet<>();
        for (long col : changed) {
            int x = (int) (col >> 32), z = (int) col;
            int tu = MapProjection.tile(x);
            int tv0 = MapProjection.tile((z - 1) * SIN - (top + 1) * COS), tv1 = MapProjection.tile((z + 2) * SIN - minY * COS);
            for (int du = -1; du <= 1; du++) { // AO / shadows / outlines reach a little sideways
                for (int tv = tv0; tv <= tv1; tv++) {
                    if (!marked.add(key(tu + du, tv))) continue;
                    Tile t = tiles.get(key(tu + du, tv));
                    if (t != null) t.dirty = true;
                }
            }
        }
    }

    public MapCut cut() {
        return cut;
    }

    /** New mask: every baked tile gets composed again (cheap, no re-bake). */
    public void setMask(MapMask mask) {
        this.mask = mask;
        maskVersion++;
    }

    /** Marks the tiles a changed chunk reaches as stale. */
    public void chunkChanged(int chunkX, int chunkZ) {
        if (cut == null) return;
        int cutY = cut.max();
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        int tu0 = MapProjection.tile(x0 - 2), tu1 = MapProjection.tile(x0 + 18);
        double v0 = (z0 - 2) * SIN - (cutY + 1) * COS, v1 = (z0 + 18) * SIN - minY * COS;
        int tv0 = MapProjection.tile(v0), tv1 = MapProjection.tile(v1);
        for (Tile t : tiles.values()) {
            if (t.tu >= tu0 && t.tu <= tu1 && t.tv >= tv0 && t.tv <= tv1) t.dirty = true;
        }
    }

    /** Picks up chunk changes from the store. */
    public void syncStore() {
        store.drainChanged(k -> chunkChanged(VoxelStore.keyX(k), VoxelStore.keyZ(k)));
    }

    /**
     * Asks for the tiles covering map rectangle {@code [u0, u1] × [v0, v1]}: missing or stale ones get baked (nearest
     * to the centre first), baked ones with an old mask get composed again.
     */
    public void request(double u0, double v0, double u1, double v1) {
        if (cut == null) return;
        useClock++;
        int tu0 = MapProjection.tile(u0), tu1 = MapProjection.tile(u1);
        int tv0 = MapProjection.tile(v0), tv1 = MapProjection.tile(v1);
        double cu = (u0 + u1) / 2 / TILE_UNITS, cv = (v0 + v1) / 2 / TILE_UNITS;
        List<Tile> todo = new ArrayList<>();
        for (int tv = tv0; tv <= tv1; tv++) {
            for (int tu = tu0; tu <= tu1; tu++) {
                Tile t = tiles.computeIfAbsent(key(tu, tv), k -> new Tile(keyU(k), keyV(k)));
                t.lastUse = useClock;
                if (t.baking) continue;
                if (t.dirty || t.composedMask != maskVersion) todo.add(t);
            }
        }
        todo.sort((a, b) -> Double.compare(dist(a, cu, cv), dist(b, cu, cv)));
        for (Tile t : todo) {
            if (inFlight >= MAX_IN_FLIGHT) break;
            submit(t);
        }
        evict();
    }

    private static double dist(Tile t, double cu, double cv) {
        double du = t.tu + 0.5 - cu, dv = t.tv + 0.5 - cv;
        return du * du + dv * dv;
    }

    private void submit(Tile t) {
        t.baking = true;
        inFlight++;
        boolean rebake = t.dirty || t.baked == null;
        t.dirty = false;
        MapCut c = cut;
        int low = minY, gen = viewGeneration;
        long mv = maskVersion;
        MapMask m = mask;
        TileBaker.Baked old = t.baked;
        THREAD.submit(() -> {
            TileBaker.Baked b = old;
            int[] pixels = null;
            try {
                if (rebake || b == null) b = TileBaker.bake(store.reader(), t.tu, t.tv, c, low);
                pixels = TileBaker.compose(b, m);
            } catch (RuntimeException e) {
                dev.telemetric.Telemetric.LOGGER.error("Map tile bake failed", e);
            }
            TileBaker.Baked result = b;
            int[] composed = pixels;
            done.add(() -> {
                inFlight--;
                t.baking = false;
                if (gen != viewGeneration) {
                    t.dirty = true;
                    return;
                }
                if (result == null || composed == null) return;
                t.baked = result;
                t.composedMask = mv;
                t.uploaded = true;
                ready.add(new Upload(textureId(t.tu, t.tv), composed));
            });
        });
    }

    private final ConcurrentLinkedQueue<Runnable> done = new ConcurrentLinkedQueue<>();

    /** Finished tiles to upload (call on the client thread, at most {@code max} per frame). */
    public List<Upload> pollUploads(int max) {
        Runnable r;
        while ((r = done.poll()) != null) r.run();
        List<Upload> out = new ArrayList<>();
        Upload u;
        while (out.size() < max && (u = ready.poll()) != null) out.add(u);
        return out;
    }

    /** Texture ids of evicted / cleared tiles; the render side releases them. */
    public List<String> drainReleased() {
        List<String> out = new ArrayList<>(released);
        released.clear();
        return out;
    }

    /** True if tile (tu, tv) has a texture to draw. */
    public boolean hasTexture(int tu, int tv) {
        Tile t = tiles.get(key(tu, tv));
        return t != null && t.uploaded;
    }

    public String textureId(int tu, int tv) {
        return "telemetric:map/" + prefix + "_" + tu + "_" + tv;
    }

    private void evict() {
        if (tiles.size() <= MAX_TILES) return;
        List<Tile> all = new ArrayList<>(tiles.values());
        all.sort((a, b) -> Long.compare(a.lastUse, b.lastUse));
        for (Tile t : all) {
            if (tiles.size() <= MAX_TILES) break;
            if (t.baking || t.lastUse == useClock) continue;
            tiles.remove(key(t.tu, t.tv));
            if (t.uploaded) released.add(textureId(t.tu, t.tv));
        }
    }

    /** Drops every tile (textures go to {@link #drainReleased}). The block copies stay (they belong to the store's owner). */
    public void clear() {
        for (Iterator<Tile> it = tiles.values().iterator(); it.hasNext(); ) {
            Tile t = it.next();
            if (t.uploaded) released.add(textureId(t.tu, t.tv));
            it.remove();
        }
        ready.clear();
        viewGeneration++;
        cut = null;
    }

    private static long key(int tu, int tv) {
        return ((long) tu << 32) | (tv & 0xFFFFFFFFL);
    }

    private static int keyU(long k) {
        return (int) (k >> 32);
    }

    private static int keyV(long k) {
        return (int) k;
    }
}
