package dev.telemetric.worldmap;

import dev.telemetric.data.SharedMaps;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.MapAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.TelosLocations;
import dev.telemetric.map.FogField;
import dev.telemetric.map.MapCoverage;
import dev.telemetric.map.MapCut;
import dev.telemetric.map.MapProjection;
import dev.telemetric.map.MapTiles;
import dev.telemetric.map.SharedTiles;
import dev.telemetric.map.VoxelChunk;
import dev.telemetric.map.VoxelStore;
import dev.telemetric.map.WorldTiles;
import dev.telemetric.ui.render.Render2D;

import java.util.HashMap;
import java.util.Map;

/**
 * The world map outside dungeons, shared by the Minimap HUD and the world map screen. In an area the data repo ships
 * tiles for ({@link SharedMaps}) it draws those, downloading them as they come into view; anywhere else it falls back
 * to baking the loaded chunks around you live (like the dungeon map, with one flat cut over the highest block). Places
 * the shipped tiles can't show (locations.json → {@code liveMap}, e.g. the Shadowlands under the realm) bake live too.
 * <p>
 * Client thread. {@link #tick} keeps the live copy fed while someone drew the map in the last few seconds.
 */
public final class WorldMapSource {

    private static final WorldMapSource INSTANCE = new WorldMapSource();
    /** The live copy is only fed while a map was drawn this recently. */
    private static final long WANT_MS = 3000;
    /** Rays look this far below the highest block. */
    private static final int DEPTH = 160;
    /** The cut only drops once it is this much above where it should be. */
    private static final int DROP_SLACK = 16;
    private static final int CAPTURES_PER_TICK = 2, REFRESH_EVERY = 5, REFRESH_TICKS = 600, KEEP_EXTRA = 3, MAX_RADIUS = 12;
    private static final int UPLOADS_PER_FRAME = 4, PNGS_PER_FRAME = 3;

    private final VoxelStore store = new VoxelStore();
    private final MapTiles live = new MapTiles("world", store);
    private final Map<Long, Integer> capturedAt = new HashMap<>();
    private SharedTiles shared;
    private SharedMaps.Area area;
    private String dimension;
    /** In a {@code liveMap} place: the shipped area stays loaded but the live copy is recorded and drawn instead. */
    private boolean liveZone;
    private int looksGeneration = -1;
    private int cutY = Integer.MIN_VALUE;
    /** Section top over the highest captured block. */
    private int blockTop = Integer.MIN_VALUE;
    /** The cut stays at most this far above the player's feet (Minimap → Map Height; clouds higher up are left out). */
    private int clearance = 32;
    private long wantedAt;
    private int ticks;

    private WorldMapSource() {}

    public static WorldMapSource get() {
        return INSTANCE;
    }

    /** True if the world map has something to show here (on a world, not in a dungeon). */
    public boolean available() {
        return DungeonAdapter.dimensionId() != null && !DungeonAdapter.isInDungeon();
    }

    /** The shipped area in use, or null when baking live. */
    public SharedMaps.Area area() {
        return liveZone ? null : area;
    }

    public void tick() {
        ticks++;
        String dim = DungeonAdapter.isInDungeon() ? null : DungeonAdapter.dimensionId();
        if (dim == null || !dim.equals(dimension)) switchTo(dim);
        if (dim == null) return;
        SharedMaps.start();
        if (ticks % 20 == 0) {
            boolean lz = TelosLocations.isLiveMap(PlayerAdapter.locationName());
            if (lz != liveZone) { // the surface copy and the place below don't mix
                liveZone = lz;
                clearLive();
            }
        }
        if (area == null && SharedMaps.ready()) {
            SharedMaps.Area a = SharedMaps.areaFor(dim);
            if (a != null) useArea(a);
        }
        int looks = MapAdapter.looksGeneration();
        if (looks != looksGeneration) {
            if (looksGeneration != -1) clearLive();
            looksGeneration = looks;
        }
        if ((area == null || liveZone) && System.currentTimeMillis() - wantedAt < WANT_MS) {
            feed();
            if (ticks % 20 == 0) updateCut(); // follows the player up/down between captures
        }
    }

    private void switchTo(String dim) {
        dimension = dim;
        liveZone = false;
        clearLive();
        if (shared != null) {
            shared.clear();
            for (String id : shared.drainReleased()) MapAdapter.releasePng(id);
        }
        area = null;
        shared = null;
        SharedMaps.cancelQueued();
    }

    private void useArea(SharedMaps.Area a) {
        area = a;
        shared = new SharedTiles("world_" + a.key() + "_" + a.version(), (z, tu, tv) -> SharedMaps.tileFile(a, z, tu, tv),
                a.maxZoom());
        if (!liveZone) clearLive();
    }

    private void clearLive() {
        live.clear();
        for (String id : live.drainReleased()) MapAdapter.release(id);
        store.clear();
        capturedAt.clear();
        cutY = Integer.MIN_VALUE;
        blockTop = Integer.MIN_VALUE;
    }

    /** Copies new chunks around you (nearest first), refreshes old copies now and then, drops far ones. */
    private void feed() {
        Point p = DungeonAdapter.playerPos();
        if (p == null) return;
        int pcx = (int) Math.floor(p.x()) >> 4, pcz = (int) Math.floor(p.z()) >> 4;
        int radius = Math.min(MAX_RADIUS, MapAdapter.viewChunks());
        int budget = CAPTURES_PER_TICK;
        long oldestKey = 0;
        int oldestTick = Integer.MAX_VALUE;
        for (int ring = 0; ring <= radius && budget > 0; ring++) {
            for (int dx = -ring; dx <= ring && budget > 0; dx++) {
                for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int cx = pcx + dx, cz = pcz + dz;
                    long k = VoxelStore.key(cx, cz);
                    Integer at = capturedAt.get(k);
                    if (at != null) {
                        if (at < oldestTick) {
                            oldestTick = at;
                            oldestKey = k;
                        }
                        continue;
                    }
                    if (capture(cx, cz)) budget--;
                }
            }
        }
        if (budget > 0 && ticks % REFRESH_EVERY == 0 && oldestTick != Integer.MAX_VALUE && ticks - oldestTick > REFRESH_TICKS) {
            capture(VoxelStore.keyX(oldestKey), VoxelStore.keyZ(oldestKey));
        }
        if (ticks % 20 == 0) {
            int keep = radius + KEEP_EXTRA;
            for (long k : store.keys()) {
                if (Math.abs(VoxelStore.keyX(k) - pcx) > keep || Math.abs(VoxelStore.keyZ(k) - pcz) > keep) {
                    store.remove(VoxelStore.keyX(k), VoxelStore.keyZ(k));
                    capturedAt.remove(k);
                }
            }
        }
    }

    private boolean capture(int cx, int cz) {
        if (!MapAdapter.capture(store, cx, cz)) return false;
        capturedAt.put(VoxelStore.key(cx, cz), ticks);
        VoxelChunk c = store.chunk(cx, cz);
        if (c != null && c.height > 0) {
            blockTop = Math.max(blockTop, sectionTop(c.maxY()));
            updateCut();
        }
        return true;
    }

    /** Sets how far above the player's feet the live map is cut; re-cuts at once when it changed. */
    public void setClearance(int blocks) {
        if (blocks == clearance) return;
        clearance = blocks;
        cutY = Integer.MIN_VALUE; // forces the new cut, also downwards
        updateCut();
    }

    /** First Y above the 16-block section holding {@code y} (whole sections, so the cut rarely moves). */
    private static int sectionTop(int y) {
        return ((y >> 4) + 1) << 4;
    }

    /**
     * Cut = highest captured block, but never more than {@link #clearance} above the player, so cloud layers and sky
     * builds don't lift the map into the sky. Rises at once; drops only once it is {@link #DROP_SLACK} too high, so
     * walking down a hill doesn't re-bake everything.
     */
    private void updateCut() {
        if (blockTop == Integer.MIN_VALUE) return;
        Point p = DungeonAdapter.playerPos();
        int want = p == null ? blockTop : Math.min(blockTop, sectionTop((int) Math.floor(p.y()) + clearance));
        if (want > cutY || want <= cutY - DROP_SLACK) {
            cutY = want;
            live.setView(MapCut.flat(cutY), cutY - DEPTH);
        }
    }

    /**
     * Draws the map tiles. Map point {@code (u, v)} lands at screen {@code (ox + u·scale, oy + v·scale)}; the caller
     * clips to {@code [x0, x1] × [y0, y1]}.
     */
    /** Where the map drawn last is known, for the fog over the rest (safe to call from the fog thread). */
    public FogField.Known known() {
        return known;
    }

    private volatile FogField.Known known = (u, v) -> 0;

    public void draw(Render2D r, double ox, double oy, double scale, int x0, int y0, int x1, int y1) {
        wantedAt = System.currentTimeMillis();
        double u0 = (x0 - ox) / scale, u1 = (x1 - ox) / scale, v0 = (y0 - oy) / scale, v1 = (y1 - oy) / scale;
        if (shared != null && !liveZone) drawShared(r, ox, oy, scale, u0, v0, u1, v1);
        else drawLive(r, ox, oy, scale, u0, v0, u1, v1);
    }

    private void drawShared(Render2D r, double ox, double oy, double scale, double u0, double v0, double u1, double v1) {
        int zoom = WorldTiles.zoomFor(scale * Render2D.guiScale(), shared.maxZoom()); // real screen pixels
        shared.request(zoom, u0, v0, u1, v1, MapAdapter::hasTexture);
        for (String id : shared.drainReleased()) MapAdapter.releasePng(id);
        for (SharedTiles.Load l : shared.drainLoads()) MapAdapter.loadPng(l.id(), l.file());
        MapAdapter.pumpPngs(PNGS_PER_FRAME);
        SharedTiles tiles = shared;
        FogField.Known k = null;
        for (int z = Math.min(tiles.maxZoom(), zoom + 1); z >= zoom; z--) {
            int zz = z;
            k = MapCoverage.tiles(WorldTiles.units(z), (tu, tv) -> tiles.textureId(zz, tu, tv), k);
        }
        known = k;
        for (int z = Math.min(shared.maxZoom(), zoom + 1); z >= zoom; z--) { // coarse first, fine on top
            int units = WorldTiles.units(z);
            int tu0 = WorldTiles.tile(u0, z), tu1 = WorldTiles.tile(u1, z);
            int tv0 = WorldTiles.tile(v0, z), tv1 = WorldTiles.tile(v1, z);
            if ((long) (tu1 - tu0 + 1) * (tv1 - tv0 + 1) > 40) continue;
            r.pushTransform((float) ox, (float) oy, (float) (scale * units / WorldTiles.TILE_PX));
            int px = WorldTiles.TILE_PX;
            for (int tv = tv0; tv <= tv1; tv++) {
                for (int tu = tu0; tu <= tu1; tu++) {
                    String id = shared.textureId(z, tu, tv);
                    if (MapAdapter.hasTexture(id)) r.texture(id, tu * px, tv * px, px, px, px, px);
                }
            }
            r.popTransform();
        }
    }

    private void drawLive(Render2D r, double ox, double oy, double scale, double u0, double v0, double u1, double v1) {
        live.syncStore();
        MapTiles tiles = live;
        known = MapCoverage.tiles(MapProjection.TILE_UNITS, tiles::textureId, null);
        // far zoomed out the live copy can't cover the view anyway: only bake what's near you
        double maxSpan = (MAX_RADIUS + 2) * 16 * 2.0;
        if (u1 - u0 > maxSpan || v1 - v0 > maxSpan) {
            Point p = DungeonAdapter.playerPos();
            if (p != null) {
                double cu = p.x(), cv = MapProjection.v(p.y(), p.z()), h = maxSpan / 2;
                u0 = Math.max(u0, cu - h);
                u1 = Math.min(u1, cu + h);
                v0 = Math.max(v0, cv - h);
                v1 = Math.min(v1, cv + h);
            }
        }
        if (u0 < u1 && v0 < v1) live.request(u0, v0, u1, v1);
        MapAdapter.syncTextures(live, UPLOADS_PER_FRAME);
        int tu0 = MapProjection.tile(u0), tu1 = MapProjection.tile(u1);
        int tv0 = MapProjection.tile(v0), tv1 = MapProjection.tile(v1);
        int px = MapProjection.TILE_PX;
        r.pushTransform((float) ox, (float) oy, (float) (scale / MapProjection.PX_PER_UNIT));
        for (int tv = tv0; tv <= tv1; tv++) {
            for (int tu = tu0; tu <= tu1; tu++) {
                if (live.hasTexture(tu, tv)) r.texture(live.textureId(tu, tv), tu * px, tv * px, px, px, px, px);
            }
        }
        r.popTransform();
    }

    /** Lets every texture go (Minimap off, left the world). */
    public void releaseAll() {
        clearLive();
        if (shared != null) {
            shared.clear();
            for (String id : shared.drainReleased()) MapAdapter.releasePng(id);
        }
    }
}
