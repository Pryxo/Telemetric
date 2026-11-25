package dev.telemetric.module.impl.dungeon;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.telemetric.Telemetric;
import dev.telemetric.dungeon.DungeonAnalysis;
import dev.telemetric.dungeon.Hole;
import dev.telemetric.dungeon.Point;
import dev.telemetric.dungeon.WalkMask;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.MapAdapter;
import dev.telemetric.hud.HudColor;
import dev.telemetric.hud.HudModule;
import dev.telemetric.map.MapProjection;
import dev.telemetric.map.MapTiles;
import dev.telemetric.module.Category;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;
import dev.telemetric.map.FogField;
import dev.telemetric.map.MapCoverage;
import dev.telemetric.worldmap.MapFog;
import dev.telemetric.worldmap.MapWaypoints;
import dev.telemetric.worldmap.WorldMapSource;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static dev.telemetric.map.MapProjection.PX_PER_UNIT;
import static dev.telemetric.map.MapProjection.TILE_PX;

/**
 * The Minimap HUD element. Outside dungeons it shows the world map ({@link WorldMapSource}: shipped tiles, or the
 * loaded chunks baked live) with players, boss / miniboss spots and the tracked waypoint. Inside a dungeon it's the
 * dungeon minimap, drawn like a little model of the dungeon (diorama look, see
 * {@code map/}): only the floor you can walk on and the walls around it, cut above head height so you look into the
 * rooms; everything else sits under drifting clouds. North is up and the map follows you. Spawn, boss drop, treasures
 * and Dungeon Goal mobs are marked, the Wayfinder route is drawn on top, and players show as their skin's face.
 * <p>
 * No room boxes: the shape comes straight from the walkable cells ({@link WalkMask}) and the real blocks.
 */
public final class DungeonMapHud extends HudModule {

    private static final int PAD = 2;
    /** Rays look this far below the lowest cut (deep pits, the boss arena under the floor). */
    private static final int LOOK_BELOW = 48;
    /** The mask is rebuilt for your height once you've moved this far up or down. */
    private static final double LEVEL_STEP = 3;
    private static final int UPLOADS_PER_FRAME = 4;

    private final NumberSetting size = add(new NumberSetting("Map Size",
            "Width and height of the map", 100, 50, 220, 2).withSuffix("px"));
    private final NumberSetting range = add(new NumberSetting("Map Range",
            "How many blocks the map shows across", 48, 16, 160, 2).withSuffix(" blocks"));
    private final BooleanSetting worldMode = add(new BooleanSetting("Outside Dungeons",
            "Show the world map when you're not in a dungeon (shared tiles where the data repo has them, else the loaded chunks)", true));
    private final NumberSetting worldRange = add(new NumberSetting("World Range",
            "How many blocks the map shows across outside dungeons", 128, 32, 512, 8).withSuffix(" blocks").visibleWhen(worldMode::get));
    private final NumberSetting worldHeight = add(new NumberSetting("Map Height",
            "How far above your feet the world map is cut (blocks higher up, e.g. roofs and clouds, are left out)",
            32, 4, 96, 4).withSuffix(" blocks").visibleWhen(worldMode::get)
            .onChange(v -> WorldMapSource.get().setClearance(v.intValue()))); // also for the M screen
    { addFrameSettings(true); } // HUD Settings → Background / Bg Opacity / Accent Bar first, then the colours

    private final ColorSetting cloudColor = addHudColor(new ColorSetting("Cloud Color",
            "The clouds over everything you can't walk to", 0xFFD7DEE8));
    private final ColorSetting spawnColor = addHudColor(new ColorSetting("Spawn Color",
            "Ring around the spawn marker", 0xFF3FAE4A));
    private final ColorSetting bossColor = addHudColor(new ColorSetting("Boss Drop Color",
            "Ring around the boss drop marker", 0xFFE0332B));
    private final ColorSetting treasureColor = addHudColor(new ColorSetting("Treasure Color",
            "Ring around treasure markers", 0xFFE8C93A));
    private final ColorSetting goalColor = addHudColor(new ColorSetting("Dungeon Goal Color",
            "Clear mobs still alive (dungeons that have them, e.g. Midnight Stronghold)", 0xFF9B4DDB));
    private final ColorSetting bossSpotColor = addHudColor(new ColorSetting("Boss Spot Color",
            "World map: boss spawn spots and bosses seen nearby", 0xFFE0332B));
    private final ColorSetting minibossSpotColor = addHudColor(new ColorSetting("Miniboss Spot Color",
            "World map: miniboss spots and minibosses seen nearby", 0xFFE8A03A));
    private final ColorSetting trackColor = addHudColor(new ColorSetting("Track Color",
            "Ring / edge arrow of the waypoint you Track (world map: click a marker, Track)", 0xFFFFFFFF));
    private final HudColor selfBorder = paletteColor("Your Head Border", "Frame around your own face", Theme::text);
    private final HudColor otherBorder = paletteColor("Others' Head Border", "Frame around other players' faces", Theme::shadow);
    private final BooleanSetting showRoute = add(new BooleanSetting("Show Route",
            "Draw the Wayfinder route on the map", true));
    private final NumberSetting iconSize = add(new NumberSetting("Icon Size",
            "Size of the spawn / boss drop / treasure markers", 9, 5, 16, 1).withSuffix("px"));
    private final BooleanSetting showHeads = add(new BooleanSetting("Player Heads",
            "Show players as their skin's face", true));
    private final BooleanSetting showOthers = add(new BooleanSetting("Other Players",
            "Also show other players (off: only you)", true).visibleWhen(showHeads::get));
    private final NumberSetting headSize = add(new NumberSetting("Head Size",
            "Size of the player faces", 7, 4, 16, 1).withSuffix("px").visibleWhen(showHeads::get));

    private boolean acquired;
    private final MapTiles tiles = new MapTiles("dungeon", DungeonTracker.get().voxels());
    private WalkMask mask = WalkMask.EMPTY;
    private CompletableFuture<Built> maskJob;
    private LongSet maskReach;
    private int maskReachSize = -1;
    private double maskRefY = Double.NaN;
    private int trackerGeneration = -1, looksGeneration = -1;
    /** The view follows your height smoothly, so jumping doesn't bob the map. */
    private double viewY = Double.NaN;
    private long lastFrameNanos;
    /** The last boss drop of this run, since a re-analysis may lose it for a moment. */
    private Hole lastHole;

    private record Built(WalkMask mask, LongArrayList changed) {}

    public DungeonMapHud() {
        super("Minimap", "Map around you: the world (shared or live-baked tiles, boss spots) or, in dungeons, where you can walk",
                Category.INVENTORY, 0.0, 0.3, false);
    }

    @Override
    protected void onEnable() {
        if (!acquired) {
            acquired = true;
            DungeonTracker.get().acquire();
            DungeonTracker.get().acquireBlocks();
        }
    }

    @Override
    protected void onDisable() {
        if (acquired) {
            acquired = false;
            DungeonTracker.get().releaseBlocks();
            DungeonTracker.get().release();
        }
        resetView();
        for (String id : tiles.drainReleased()) MapAdapter.release(id);
        WorldMapSource.get().releaseAll();
    }

    private void resetView() {
        tiles.clear();
        mask = WalkMask.EMPTY;
        maskJob = null;
        maskReach = null;
        maskReachSize = -1;
        maskRefY = Double.NaN;
        viewY = Double.NaN;
        lastHole = null;
    }

    @Override
    public boolean hasData() {
        return dungeonData() || worldMode.get() && WorldMapSource.get().available();
    }

    private boolean dungeonData() {
        DungeonTracker tracker = DungeonTracker.get();
        return tracker.isInDungeon() && tracker.isMapped() && !tracker.analysis().reachable().isEmpty();
    }

    @Override
    protected boolean drawsOwnFrame() {
        return true;
    }

    /** The old map-only "Background" toggle (before HUD Settings) still turns the backdrop off. */
    @Override
    public void fromJson(JsonObject json) {
        super.fromJson(json);
        if (json.has("settings") && json.get("settings").isJsonObject()) {
            JsonObject values = json.getAsJsonObject("settings");
            JsonElement old = values.get("Background");
            if (!values.has("HUD Background") && old != null && old.isJsonPrimitive() && !old.getAsBoolean()) {
                applyGlobalBackground(false);
            }
        }
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        return size.getInt();
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        return size.getInt();
    }

    // ------------------------------------------------------------------ state

    /** Keeps tiles, mask and textures in step with the tracker. Runs while drawing (client thread). */
    private void update(DungeonTracker tracker, Point player) {
        if (tracker.generation() != trackerGeneration) {
            trackerGeneration = tracker.generation();
            resetView();
        }
        int looks = MapAdapter.looksGeneration();
        if (looks != looksGeneration) {
            if (looksGeneration != -1) { // resource reload: every block copy and tile is stale
                resetView();
                tracker.recopyBlocks();
            }
            looksGeneration = looks;
        }
        if (maskJob != null && maskJob.isDone()) {
            Built b = maskJob.getNow(null);
            maskJob = null;
            if (b != null && !b.mask().isEmpty()) {
                tiles.setCut(b.mask(), b.mask().min() - LOOK_BELOW, b.changed());
                tiles.setMask(b.mask());
                mask = b.mask();
            }
        }
        DungeonAnalysis a = tracker.analysis();
        LongSet reach = a.reachable();
        boolean moved = Double.isNaN(maskRefY) || Math.abs(player.y() - maskRefY) >= LEVEL_STEP;
        if (maskJob == null && (moved || reach != maskReach && reach.size() != maskReachSize)) {
            maskReach = reach;
            maskReachSize = reach.size();
            maskRefY = player.y();
            double ref = player.y();
            WalkMask old = mask;
            maskJob = CompletableFuture.supplyAsync(() -> {
                WalkMask m = WalkMask.build(reach, ref);
                return new Built(m, m.changedFrom(old));
            });
        }
        tiles.syncStore();
        if (tracker.generation() != trackerGeneration) return;
        Hole hole = a.bossDrop();
        if (hole != null) lastHole = hole;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        int s = size.getInt();
        drawFrame(r, s, s, alignRight);
        Point player = DungeonAdapter.playerPos();
        if (player != null && !dungeonData() && worldMode.get() && WorldMapSource.get().available()) {
            for (String id : tiles.drainReleased()) MapAdapter.release(id);
            drawWorld(r, s, player);
            return;
        }
        if (!dungeonData() || player == null) {
            for (String id : tiles.drainReleased()) MapAdapter.release(id);
            if (preview) drawSample(r, s);
            return;
        }
        DungeonTracker tracker = DungeonTracker.get();
        update(tracker, player);

        int inner = s - 2 * PAD;
        double scale = inner / range.get(); // HUD px per block
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1e6;
        lastFrameNanos = now;
        int smooth = Durations.scaled(Durations.STANDARD);
        if (Double.isNaN(viewY) || smooth == 0 || Math.abs(player.y() - viewY) > 12) viewY = player.y();
        else viewY += (player.y() - viewY) * (1 - Math.exp(-dt / smooth));
        double cu = player.x(), cv = MapProjection.v(viewY, player.z());
        double half = inner / 2.0 / scale;
        tiles.request(cu - half, cv - half, cu + half, cv + half);
        MapAdapter.syncTextures(tiles, UPLOADS_PER_FRAME);

        View view = new View(PAD + inner / 2.0 - cu * scale, PAD + inner / 2.0 - cv * scale, scale, PAD, inner);
        r.pushClip(PAD, PAD, inner, inner);
        r.rect(PAD, PAD, inner, inner, backdrop());
        drawTiles(r, view, cu - half, cv - half, cu + half, cv + half);
        fog.draw(r, PAD, PAD, inner, inner, view.ox(), view.oy(), scale,
                MapCoverage.tiles(MapProjection.TILE_UNITS, tiles::textureId, null),
                FogField.Style.compact(cloudColor.get()), MapCoverage.version());
        if (showRoute.get()) drawRoute(r, view);
        drawMarkers(r, view, tracker);
        if (showHeads.get()) drawPlayers(r, view, DungeonAdapter.players());
        drawTracked(r, view);
        r.popClip();
    }

    /** Outside dungeons: the world map around you, north up. */
    private void drawWorld(Render2D r, int s, Point player) {
        int inner = s - 2 * PAD;
        WorldMapSource.get().setClearance(worldHeight.get().intValue());
        double scale = inner / worldRange.get();
        double cu = player.x(), cv = MapProjection.v(player.y(), player.z());
        View view = new View(PAD + inner / 2.0 - cu * scale, PAD + inner / 2.0 - cv * scale, scale, PAD, inner);
        r.pushClip(PAD, PAD, inner, inner);
        r.rect(PAD, PAD, inner, inner, backdrop());
        WorldMapSource.get().draw(r, view.ox(), view.oy(), scale, PAD, PAD, PAD + inner, PAD + inner);
        fog.draw(r, PAD, PAD, inner, inner, view.ox(), view.oy(), scale, WorldMapSource.get().known(),
                FogField.Style.surface(cloudColor.get()), MapCoverage.version());
        int is = Math.max(4, iconSize.getInt() - 2);
        for (MapWaypoints.Waypoint w : MapWaypoints.spots()) worldMarker(r, view, w, is, false);
        for (MapWaypoints.Waypoint w : MapWaypoints.live()) worldMarker(r, view, w, is, true);
        if (showHeads.get()) drawPlayers(r, view, DungeonAdapter.players());
        drawTracked(r, view);
        r.popClip();
    }

    private void worldMarker(Render2D r, View view, MapWaypoints.Waypoint w, int is, boolean live) {
        double x = view.x(w.x()), y = view.y(MapProjection.v(w.y(), w.z()));
        if (x < view.pad() || y < view.pad() || x > view.pad() + view.inner() || y > view.pad() + view.inner()) return;
        int color = w.kind() == MapWaypoints.Kind.BOSS ? bossSpotColor.get() : minibossSpotColor.get();
        double rad = live ? is / 2.0 + 1 : is / 2.0;
        r.circle(x, y, rad + 1, ColorUtil.withAlpha(0xFF000000, 160));
        r.circle(x, y, rad, color);
    }

    /** The tracked waypoint: a ring when it's on the map, an arrow at the edge pointing to it when it isn't. */
    private void drawTracked(Render2D r, View view) {
        MapWaypoints.Waypoint t = MapWaypoints.tracked();
        if (t == null) return;
        double x = view.x(t.x()), y = view.y(MapProjection.v(t.y(), t.z()));
        int lo = view.pad() + 4, hi = view.pad() + view.inner() - 4;
        int color = trackColor.get();
        if (x >= lo && y >= lo && x <= hi && y <= hi) {
            r.circle(x, y, iconSize.getInt() / 2.0 + 3, ColorUtil.withAlpha(color, 110));
            r.circle(x, y, iconSize.getInt() / 2.0 + 1.5, ColorUtil.withAlpha(0xFF000000, 160));
            return;
        }
        double c = view.pad() + view.inner() / 2.0;
        double ang = Math.atan2(y - c, x - c);
        double reach = view.inner() / 2.0 - 5;
        double f = reach / Math.max(Math.abs(Math.cos(ang)), Math.abs(Math.sin(ang))); // to the square's edge
        r.icon("chevron-right", c + Math.cos(ang) * f, c + Math.sin(ang) * f, 9, color, ang);
    }

    /** Map space → HUD pixels. */
    /** One fog for whichever map is shown. */
    private final MapFog fog = new MapFog();

    /** Ground under the map where the fog thins out: the cloud colour, darker and bluer (like Diorama's backdrop). */
    private int backdrop() {
        int c = cloudColor.get();
        return 0xFF000000 | Math.round(((c >> 16) & 0xFF) * 0.57f) << 16 | Math.round(((c >> 8) & 0xFF) * 0.62f) << 8
                | Math.round((c & 0xFF) * 0.7f);
    }

    private record View(double ox, double oy, double scale, int pad, int inner) {
        double x(double u) { return ox + u * scale; }
        double y(double v) { return oy + v * scale; }
        double x(Point p) { return x(p.x()); }
        double y(Point p) { return y(MapProjection.v(p.y(), p.z())); }
        int clampX(double x, int margin) { return (int) Math.round(Math.clamp(x, pad + margin, pad + inner - margin)); }
        int clampY(double y, int margin) { return (int) Math.round(Math.clamp(y, pad + margin, pad + inner - margin)); }
    }

    private void drawTiles(Render2D r, View view, double u0, double v0, double u1, double v1) {
        int tu0 = MapProjection.tile(u0), tu1 = MapProjection.tile(u1);
        int tv0 = MapProjection.tile(v0), tv1 = MapProjection.tile(v1);
        r.pushTransform((float) view.ox(), (float) view.oy(), (float) (view.scale() / PX_PER_UNIT));
        for (int tv = tv0; tv <= tv1; tv++) {
            for (int tu = tu0; tu <= tu1; tu++) {
                if (!tiles.hasTexture(tu, tv)) continue;
                r.texture(tiles.textureId(tu, tv), tu * TILE_PX, tv * TILE_PX, TILE_PX, TILE_PX, TILE_PX, TILE_PX);
            }
        }
        r.popTransform();
    }

    private void drawRoute(Render2D r, View view) {
        WayfinderModule wf = Telemetric.get().modules().get(WayfinderModule.class);
        List<Point> pts = wf.routePoints();
        if (pts.size() < 2) return;
        int color = wf.routeColor();
        for (int i = 1; i < pts.size(); i++) {
            Point a = pts.get(i - 1), b = pts.get(i);
            double ax = view.x(a), ay = view.y(a), bx = view.x(b), by = view.y(b);
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) / 1.5));
            for (int k = 0; k <= steps; k++) {
                double t = (double) k / steps;
                int x = (int) Math.round(ax + (bx - ax) * t), y = (int) Math.round(ay + (by - ay) * t);
                r.rect(x - 1, y - 1, 2, 2, color);
            }
        }
    }

    private void drawMarkers(Render2D r, View view, DungeonTracker tracker) {
        int is = iconSize.getInt();
        int m = is / 2 + 1;
        for (Point g : tracker.goals()) {
            int x = view.clampX(view.x(g), m), y = view.clampY(view.y(g), m);
            r.circle(x, y, is / 2.0 + 1, ColorUtil.withAlpha(0xFF000000, 160));
            r.circle(x, y, is / 2.0, goalColor.get());
        }
        for (Point t : tracker.treasures()) marker(r, view, t, MapIcon.TREASURE, treasureColor.get(), is);
        Point spawn = tracker.spawn();
        if (spawn != null) marker(r, view, spawn, MapIcon.SPAWN, spawnColor.get(), is);
        Hole hole = lastHole;
        if (hole != null) {
            marker(r, view, new Point(hole.centerX(), hole.y(), hole.centerZ()), MapIcon.BOSS_DROP, bossColor.get(), is);
        }
    }

    /** An icon with a coloured ring behind it, kept inside the map when it's off screen. */
    private static void marker(Render2D r, View view, Point p, MapIcon icon, int ring, int is) {
        int m = is / 2 + 1;
        int x = view.clampX(view.x(p), m), y = view.clampY(view.y(p), m);
        r.circle(x, y, is / 2.0 + 1.5, ColorUtil.withAlpha(ring, 200));
        icon.draw(r, x, y, is);
    }

    private void drawPlayers(Render2D r, View view, List<DungeonAdapter.PlayerMarker> players) {
        int hs = headSize.getInt();
        DungeonAdapter.PlayerMarker self = null;
        for (DungeonAdapter.PlayerMarker p : players) {
            if (p.self()) self = p; // drawn last, so it's on top
            else if (showOthers.get()) drawHead(r, view, p, hs, otherBorder.get());
        }
        if (self != null) drawHead(r, view, self, hs, selfBorder.get());
    }

    /** The face (plus hat layer) centred on the player, kept inside the map. */
    private static void drawHead(Render2D r, View view, DungeonAdapter.PlayerMarker p, int hs, int border) {
        double v = MapProjection.v(p.y(), p.z());
        int x = view.clampX(view.x(p.x()), hs / 2 + 1) - hs / 2, y = view.clampY(view.y(v), hs / 2 + 1) - hs / 2;
        r.rect(x - 1, y - 1, hs + 2, hs + 2, border);
        r.texture(p.skin(), x, y, hs, hs, 8, 8, 8, 8, 64, 64, 0xFFFFFFFF);   // face
        r.texture(p.skin(), x, y, hs, hs, 40, 8, 8, 8, 64, 64, 0xFFFFFFFF);  // hat layer
    }

    /** HUD editor placeholder outside a dungeon: clouds around a few floor shapes and the markers. */
    private void drawSample(Render2D r, int s) {
        int inner = s - 2 * PAD;
        View view = new View(PAD + inner / 2.0, PAD + inner / 2.0, inner / range.get(), PAD, inner);
        r.pushClip(PAD, PAD, inner, inner);
        r.rect(PAD, PAD, inner, inner, backdrop());
        int floor = 0xFF8A8478, wall = 0xFF4E4A44;
        int[][] rooms = {{-14, -14, 12, 10}, {2, -12, 12, 10}, {-6, 0, 14, 12}, {-1, -5, 3, 6}};
        for (int[] b : rooms) {
            int x = (int) Math.round(view.x(b[0])), y = (int) Math.round(view.y(b[1] * MapProjection.SIN));
            int w = (int) Math.round(b[2] * view.scale()), h = (int) Math.round(b[3] * MapProjection.SIN * view.scale());
            r.rect(x - 1, y - 2, w + 2, h + 3, wall);
            r.rect(x, y, w, h, floor);
        }
        FogField.Known sample = (u, v) -> {
            for (int[] b : rooms) {
                if (u >= b[0] && u < b[0] + b[2] && v >= b[1] * MapProjection.SIN && v < (b[1] + b[3]) * MapProjection.SIN) return 1;
            }
            return 0;
        };
        fog.draw(r, PAD, PAD, inner, inner, view.ox(), view.oy(), view.scale(), sample,
                FogField.Style.compact(cloudColor.get()), "sample");
        int is = iconSize.getInt();
        MapIcon.SPAWN.draw(r, (int) Math.round(view.x(-8)), (int) Math.round(view.y(-9 * MapProjection.SIN)), is);
        MapIcon.TREASURE.draw(r, (int) Math.round(view.x(8)), (int) Math.round(view.y(-7 * MapProjection.SIN)), is);
        MapIcon.BOSS_DROP.draw(r, (int) Math.round(view.x(1)), (int) Math.round(view.y(6 * MapProjection.SIN)), is);
        r.popClip();
    }

}
