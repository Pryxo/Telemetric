package dev.telemetric.module.impl.visual;

import dev.telemetric.data.MapMarkerTable;
import dev.telemetric.data.MapTable;
import dev.telemetric.data.TelosData;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.hud.HudModule;
import dev.telemetric.map.MapLocator;
import dev.telemetric.map.MapMarkers;
import dev.telemetric.map.MapMarkers.Marker;
import dev.telemetric.module.Category;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.map.MapRenderer;
import dev.telemetric.ui.map.MapView;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.List;

/**
 * World → Minimap: the Telos Map around you as a round or square HUD element, north-up or turning with you. Shows
 * the same markers as the map (Boss Tracker colours) with you as your head in the middle.
 * Realm only, and only where {@code tables/map.json} has an image.
 */
public final class MinimapHud extends HudModule {

    private static final String CIRCLE = "Circle", SQUARE = "Square";
    private static final int SIZE = 84, MARKER = 10, RIM = 1;
    private static final double MIN_RANGE = 24, MAX_RANGE = 320;

    private final ModeSetting shape = add(new ModeSetting("Shape", "Round or square", CIRCLE, CIRCLE, SQUARE));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate",
            "Turn the map with you (off: north is always up)", true));
    private final NumberSetting range = add(new NumberSetting("Range",
            "Blocks from the centre to the edge", 80, MIN_RANGE, MAX_RANGE, 4).withSuffix(" blocks"));
    private final BooleanSetting coords = add(new BooleanSetting("Coordinates", "Your X Y Z below the map", true));

    private final GroupSetting markerGroup = add(new GroupSetting("Markers", "What the minimap shows"));
    private final MultiChoiceSetting hidden = add(markerGroup.child(new MultiChoiceSetting("Hidden",
            "Marker types the minimap leaves out", () -> TelosData.MAP_MARKERS.get().labels(),
            MapModule.hiddenByDefault(MapMarkerTable.Category::minimap))));

    private final GroupSetting keys = add(new GroupSetting("Keys", "Zoom the minimap"));
    private final KeybindSetting zoomIn = add(keys.child(new KeybindSetting("Zoom In", "Smaller range", KeybindSetting.NONE)
            .onPress(() -> zoom(0.8))));
    private final KeybindSetting zoomOut = add(keys.child(new KeybindSetting("Zoom Out", "Larger range", KeybindSetting.NONE)
            .onPress(() -> zoom(1.25))));

    private final MapView view = new MapView();

    public MinimapHud() {
        super("Minimap", "The Telos Map around you on the HUD: bosses, players", Category.WORLD, 1.0, 0.0, false);
    }

    private void zoom(double factor) {
        range.set(Math.clamp(Math.round(range.get() * factor / 4) * 4.0, MIN_RANGE, MAX_RANGE));
    }

    @Override
    protected boolean drawsOwnFrame() {
        return true;
    }

    @Override
    public boolean hasData() {
        MapTable.Region region = MapLocator.current();
        return region != null && !region.tiles().isEmpty();
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        return SIZE;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        return SIZE + (coords.get() ? 4 + r.fontHeight() : 0);
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        Theme p = palette();
        boolean round = shape.is(CIRCLE);
        float half = SIZE / 2f;
        float[] clip = round ? MapRenderer.circle(half, half, half, 64) : square(0, 0, SIZE, 3);
        float[] rim = round ? MapRenderer.circle(half, half, half + RIM, 64) : square(-RIM, -RIM, SIZE + RIM * 2, 3 + RIM);
        int n = clip.length / 2;
        r.polygon(rim, n, p.border());
        r.polygon(clip, n, ColorUtil.withAlpha(p.panel(), 235));

        MapTable.Region region = MapLocator.current();
        Point me = PlayerAdapter.feet(PlayerAdapter.partialTick());
        float yaw = WorldRenderAdapter.cameraYaw();
        if (region == null || me == null) {
            drawSelf(r, half, p);
            if (preview) r.textCentered("Realm only", (int) half, (int) half + 10, p.textMuted());
            drawCoords(r, null, p);
            return;
        }
        double scale = half / range.get();
        view.set(me.x(), me.z(), scale, rotate.get() ? Math.PI - Math.toRadians(yaw) : 0, half, half);
        double ppu = Render2D.guiScale() * effectiveScale();
        MapRenderer.drawTiles(r, region, view, clip, n, ppu, p.control());

        r.pushClip(0, 0, SIZE, SIZE);
        try {
            List<Marker> markers = MapMarkers.collect(region, c -> !hidden.contains(c.label()));
            for (Marker m : markers) drawMarker(r, m, round, half, p);
            drawSelf(r, half, p);
        } finally {
            r.popClip();
        }
        if (rotate.get()) drawNorth(r, round, half, p);
        drawCoords(r, me, p);
    }

    private void drawMarker(Render2D r, Marker m, boolean round, float half, Theme p) {
        double sx = view.sx(m.x(), m.z()) - half, sy = view.sy(m.x(), m.z()) - half;
        double edge = half - MARKER * 0.45;
        double reach = round ? Math.hypot(sx, sy) : Math.max(Math.abs(sx), Math.abs(sy));
        if (reach > edge) return;
        double size = MARKER;
        r.pushAlpha(m.active() ? 1 : m.category().fade());
        MapRenderer.marker(r, m, half + sx, half + sy, size, false, p);
        r.popAlpha();
    }

    /** You: your head in the middle, framed in the accent colour. */
    private static void drawSelf(Render2D r, float half, Theme p) {
        String skin = null;
        for (DungeonAdapter.PlayerMarker pm : DungeonAdapter.players()) if (pm.self()) skin = pm.skin();
        MapRenderer.head(r, skin, half, half, 7, 1.2, p.accent(), p.textMuted());
    }

    /** "N" on the rim where north is. */
    private void drawNorth(Render2D r, boolean round, float half, Theme p) {
        double a = view.rotation; // screen angle of world north (-Z): R(rot)·(0,-1) = (sin, -cos)
        double dx = Math.sin(a), dy = -Math.cos(a);
        double edge = half - 5;
        double f = round ? edge : edge / Math.max(Math.abs(dx), Math.abs(dy));
        double x = half + dx * f, y = half + dy * f;
        MapRenderer.disc(r, x, y, 5, ColorUtil.withAlpha(p.panel(), 230));
        r.textCentered("N", (int) Math.round(x) + 1, (int) Math.round(y) - r.fontHeight() / 2 + 1, p.text());
    }

    private void drawCoords(Render2D r, Point me, Theme p) {
        if (!coords.get()) return;
        String text = me == null ? "X  Y  Z" : (int) Math.floor(me.x()) + "  " + (int) Math.floor(me.y()) + "  " + (int) Math.floor(me.z());
        r.textCentered(r.trim(text, SIZE), SIZE / 2, SIZE + 4, p.textMuted());
    }

    private static float[] square(float x, float y, float s, float rad) {
        int seg = 3;
        float[] out = new float[4 * (seg + 1) * 2];
        float[][] c = {{x + s - rad, y + rad}, {x + s - rad, y + s - rad}, {x + rad, y + s - rad}, {x + rad, y + rad}};
        int k = 0;
        for (int i = 0; i < 4; i++) {
            double start = -Math.PI / 2 + i * Math.PI / 2;
            for (int j = 0; j <= seg; j++) {
                double ang = start + (Math.PI / 2) * j / seg;
                out[k++] = (float) (c[i][0] + Math.cos(ang) * rad);
                out[k++] = (float) (c[i][1] + Math.sin(ang) * rad);
            }
        }
        return out;
    }
}
