package dev.telemetric.module.impl.visual;

import dev.telemetric.chat.ChatText;
import dev.telemetric.data.BossTable;
import dev.telemetric.data.BossTable.Spot;
import dev.telemetric.data.MapMarkerTable;
import dev.telemetric.data.MapTable;
import dev.telemetric.data.Names;
import dev.telemetric.data.TelosData;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.ChatAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.WorldLabelAdapter;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.map.MapLocator;
import dev.telemetric.map.WaypointCatalog;
import dev.telemetric.map.WaypointCatalog.Entry;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.setting.impl.TextSetting;
import dev.telemetric.setting.impl.ToggleListSetting;
import dev.telemetric.setting.impl.ToggleListSetting.Row;
import dev.telemetric.tracking.RealmBossTracker;
import dev.telemetric.tracking.RealmBossTracker.State;
import dev.telemetric.tracking.RealmBossTracker.Tracked;
import dev.telemetric.ui.render.BadgeText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * World → Waypoints: world markers (label + beam) for everything the Telos Map knows. Bosses (and their fixed markers,
 * the Cog Stabilizers) are <b>live</b>: shown where and while the Boss Tracker ({@link RealmBossTracker}) says
 * they're up, coloured by state. Every other map point (minibosses, Void Crystals, …) is <b>static</b>: shown at all of
 * its spots while you're in its region. Each one is switched on or off in the Points list
 * ({@link WaypointCatalog}); realm only.
 */
public final class WaypointsModule extends Module {

    private static final String STYLE_ICON = "Icon", STYLE_TEXT = "Text", STYLE_BOTH = "Both";
    /** Waypoints fade in over FADE blocks past Hide Within; far ones are pulled in to MAX_DRAW (same spot on screen). */
    private static final double FADE = 50, MAX_DRAW = 192, LOOK_DOT = 0.995;

    private final BooleanSetting beams = add(new BooleanSetting("Beams", "Light beam above each waypoint", true));
    private final ModeSetting style = add(new ModeSetting("Style",
            "What each waypoint shows: its icon, its text (name, fighter, timer, distance) or both",
            STYLE_BOTH, STYLE_ICON, STYLE_TEXT, STYLE_BOTH));
    private final BooleanSetting showDistance = add(new BooleanSetting("Show Distance",
            "Distance in the waypoint text", true));
    private final NumberSetting labelSize = add(new NumberSetting("Label Size",
            "Waypoint text size (stays the same on screen at any distance)", 1.0, 0.5, 2.5, 0.1).withSuffix("x"));
    private final NumberSetting hideWithin = add(new NumberSetting("Hide Within",
            "Waypoints closer than this are hidden (you're there); they fade in over the next 50 blocks",
            20, 0, 100, 5).withSuffix("m"));
    private final KeybindSetting teleportKey = add(new KeybindSetting("Quick Teleport",
            "Look at a boss waypoint someone is fighting and press: /tp to that player", KeybindSetting.NONE)
            .onPress(this::quickTeleport));

    private final GroupSetting pointGroup = add(new GroupSetting("Points",
            "Every boss, miniboss and marker of the Telos Map: switch each one on or off"));
    private final TextSetting search = add(pointGroup.child(new TextSetting("Search",
            "Name, type or region (\"shadowlands\", \"miniboss\")", "", 40).notPersistent()));
    private final ToggleListSetting points = add(pointGroup.child(new ToggleListSetting("Waypoints",
            "Click a row or a group's switch; All / None apply to what's listed", this::rows, search::get)));

    private final GroupSetting stateGroup = add(new GroupSetting("Boss States", "Which live bosses get a waypoint"));
    private final BooleanSetting showAvailable = add(stateGroup.child(new BooleanSetting("Available",
            "Bosses nobody fights yet", true)));
    private final BooleanSetting showFighting = add(stateGroup.child(new BooleanSetting("Fighting",
            "Bosses someone is fighting", true)));
    private final BooleanSetting showPortal = add(stateGroup.child(new BooleanSetting("Portal",
            "Defeated bosses whose portal is still open", true)));

    private final GroupSetting colors = add(new GroupSetting("Colors", "Waypoint colours"));
    private final ColorSetting availableColor = add(colors.child(new ColorSetting("Available Color",
            "Boss alive, nobody fighting", 0xFFF2F2FF, false)));
    private final ColorSetting fightingColor = add(colors.child(new ColorSetting("Fighting Color",
            "Someone is fighting the boss", 0xFF55FF55, false)));
    private final ColorSetting portalColor = add(colors.child(new ColorSetting("Portal Color",
            "Boss defeated, portal open", 0xFFFFD700, false)));
    private final ColorSetting idleColor = add(colors.child(new ColorSetting("Idle Color",
            "Boss not spawned (always-present bosses, fixed markers)", 0xFF555555, false)));
    /** Colours of the static points, by {@code mapMarkers.json} category id. */
    private final Map<String, ColorSetting> pointColors = new LinkedHashMap<>();
    private final ColorSetting fallbackColor = new ColorSetting("Point Color", "", 0xFFFFFFFF, false);

    private final RealmBossTracker tracker = RealmBossTracker.get();

    /** One marker to draw: a live boss ({@code t} set) or one spot of a static point. */
    private record Wp(double x, double y, double z, String name, String icon, ColorSetting color, Tracked t) {}

    /** What to draw, refreshed every tick (render code only reads this). */
    private volatile List<Wp> current = List.of();

    private List<Entry> rowsFrom;
    private List<Row> rows = List.of();

    public WaypointsModule() {
        super("Waypoints", "World markers: live bosses + every Telos Map point, each one switchable", Category.WORLD);
        for (MapMarkerTable.Category c : WaypointCatalog.staticCategories()) {
            pointColors.put(c.id(), add(colors.child(new ColorSetting(c.label() + " Color",
                    "Waypoints of " + c.label(), defaultColor(c), false))));
        }
        WorldLabelAdapter.addSource(this::labels);
        WorldRenderAdapter.addDrawer(this::drawBeams);
    }

    private static int defaultColor(MapMarkerTable.Category c) {
        MapMarkerTable.Color col = c.waypointColor() != null ? c.waypointColor() : c.color();
        return col.state() == null ? 0xFF000000 | col.rgb() : 0xFFFFFFFF;
    }

    /** The Points list: one row per catalog entry, grouped by category. */
    private synchronized List<Row> rows() {
        List<Entry> all = WaypointCatalog.all();
        if (all != rowsFrom) {
            rowsFrom = all;
            List<Row> out = new ArrayList<>(all.size());
            for (Entry e : all) out.add(new Row(e.key(), e.category().label(), e.name(), meta(e), e.icon(), tooltip(e),
                    e.defaultOn()));
            rows = List.copyOf(out);
        }
        return rows;
    }

    private static String meta(Entry e) {
        if (e.live() || e.spots().size() <= 1) return e.regionLabel();
        return e.regionLabel() + " · " + e.spots().size();
    }

    private static String tooltip(Entry e) {
        if (e.live()) return "Live: shown where and while the Boss Tracker sees it up (" + e.regionLabel() + ")";
        int n = e.spots().size();
        String where = e.regionLabel() + ", " + n + (n == 1 ? " spot" : " spots");
        return e.note().isEmpty() ? where : e.note() + " · " + where;
    }

    @Override
    public void onTick() {
        current = MapLocator.inRealm() ? collect(MapLocator.current()) : List.of();
    }

    @Override
    protected void onDisable() {
        current = List.of();
    }

    /** @param region your map region, or null without map data (then only live bosses) */
    private List<Wp> collect(MapTable.Region region) {
        List<Wp> out = new ArrayList<>();
        // live: the tracker's bosses of the place you're in
        BossTable table = TelosData.BOSSES.get();
        String zone = table.zoneOf(PlayerAdapter.locationName());
        for (Tracked t : tracker.all()) {
            String bossZone = table.zoneOf(t.boss);
            if (zone == null ? bossZone != null : !zone.equals(bossZone)) continue;
            if (t.spot() == null || !liveOn(t) || !stateShown(t)) continue;
            Spot s = t.spot();
            out.add(new Wp(s.x() + 0.5, s.y(), s.z() + 0.5, t.name, t.boss.icon().isEmpty() ? null : t.boss.icon(),
                    stateColor(t), t));
        }
        // static: the map points of this region
        if (region == null) return List.copyOf(out);
        for (Entry e : WaypointCatalog.all()) {
            if (e.live() || !e.region().equals(region.id()) || !points.isOn(e.key(), e.defaultOn())) continue;
            ColorSetting color = pointColors.getOrDefault(e.category().id(), fallbackColor);
            for (MapMarkerTable.Spot s : e.spots()) out.add(new Wp(s.x(), s.y(), s.z(), e.name(), e.icon(), color, null));
        }
        return List.copyOf(out);
    }

    /** The Points switch of a tracked boss (or of its fixed marker). */
    private boolean liveOn(Tracked t) {
        String key = t.marker ? Names.key(t.name) : t.boss.id();
        Entry e = WaypointCatalog.get(key);
        if (e == null) e = WaypointCatalog.get(t.boss.id()); // a marker the map doesn't list: its boss's default
        return points.isOn(key, e == null || e.defaultOn());
    }

    private boolean stateShown(Tracked t) {
        return switch (t.state()) {
            case DEFEATED -> false;
            case IDLE -> t.boss.tracker() == null || t.boss.tracker().idle() || open(t); // a counter boss only when "Open"
            case PORTAL -> showPortal.get();
            case ALIVE -> t.marker || (t.calledBy() != null ? showFighting.get() : showAvailable.get());
        };
    }

    private boolean open(Tracked t) {
        int[] c = tracker.count(t.boss.name());
        return c != null && c[1] > 0 && c[0] >= c[1];
    }

    private ColorSetting stateColor(Tracked t) {
        return switch (t.state()) {
            case PORTAL -> portalColor;
            case ALIVE -> t.calledBy() != null ? fightingColor : availableColor;
            default -> idleColor;
        };
    }

    private double fade(double distance) {
        double near = hideWithin.get();
        if (distance < near) return 0;
        return Math.min(1, (distance - near) / FADE);
    }

    /** Pulls far points toward the camera so they stay inside the view distance (same spot on screen). */
    private static double[] clamp(Point cam, double x, double y, double z, double scaleFrom) {
        double f = scaleFrom > MAX_DRAW ? MAX_DRAW / scaleFrom : 1;
        return new double[]{cam.x() + (x - cam.x()) * f, cam.y() + (y - cam.y()) * f, cam.z() + (z - cam.z()) * f, f};
    }

    private List<WorldLabelAdapter.Label> labels(float partialTick) {
        if (!isEnabled()) return List.of();
        List<Wp> list = current;
        Point cam = WorldRenderAdapter.cameraPos();
        if (list.isEmpty() || cam == null) return List.of();
        long now = System.currentTimeMillis();
        List<WorldLabelAdapter.Label> out = new ArrayList<>(list.size());
        for (Wp w : list) {
            double x = w.x, y = w.y + 1.5, z = w.z;
            double dist = Math.sqrt(cam.distanceSq(x, y, z));
            double alpha = fade(dist);
            if (alpha <= 0.01) continue;
            double[] p = clamp(cam, x, y, z, dist);
            double height = Math.max(0.25, dist * p[3] * 0.018 * labelSize.get());
            int argb = (w.color.get() & 0x00FFFFFF) | ((int) Math.round(alpha * 255) << 24);
            boolean icon = !style.is(STYLE_TEXT) && w.icon != null;
            String text = style.is(STYLE_ICON) && icon ? "" : text(w, now, dist);
            out.add(new WorldLabelAdapter.Label(p[0], p[1], p[2], text, BadgeText.Style.PLAIN,
                    height, argb, true).look(false, true).withIcon(icon ? w.icon : null)
                    .withRamps(new WorldLabelAdapter.Ramps(w.color, null, null)));
        }
        return out;
    }

    private String text(Wp w, long now, double dist) {
        StringBuilder b = new StringBuilder(w.name);
        Tracked t = w.t;
        if (t != null) {
            if (t.calledBy() != null) b.append(" [").append(t.calledBy()).append(']');
            if (t.state() == State.PORTAL) b.append(" [").append(t.portalSeconds(now)).append("s]");
            if (t.state() == State.IDLE) b.append(" [Idle]");
        }
        if (showDistance.get()) b.append(" [").append(Math.round(dist)).append("m]");
        return b.toString();
    }

    private void drawBeams(WorldRenderAdapter.LineSink sink) {
        if (!isEnabled() || !beams.get()) return;
        List<Wp> list = current;
        Point cam = WorldRenderAdapter.cameraPos();
        if (list.isEmpty() || cam == null) return;
        for (Wp w : list) {
            double dist = Math.sqrt(cam.distanceSq(w.x, w.y, w.z));
            double alpha = fade(dist) * 0.5;
            if (alpha <= 0.01) continue;
            double[] a = clamp(cam, w.x, w.y, w.z, dist), b = clamp(cam, w.x, w.y + 120, w.z, dist);
            int argb = (w.color.get() & 0x00FFFFFF) | ((int) Math.round(alpha * 255) << 24);
            sink.line(a[0], a[1], a[2], b[0], b[1], b[2], argb, 3f, true);
        }
    }

    private void quickTeleport() {
        if (!isEnabled()) return;
        Point eyes = PlayerAdapter.eyes();
        double[] look = PlayerAdapter.look();
        String me = PlayerAdapter.name();
        if (eyes == null || look == null) return;
        Wp best = null;
        double bestDot = LOOK_DOT;
        for (Wp w : current) {
            if (w.t == null || w.t.calledBy() == null) continue;
            double dx = w.x - eyes.x(), dy = w.y + 1.5 - eyes.y(), dz = w.z - eyes.z();
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6) continue;
            double dot = (dx * look[0] + dy * look[1] + dz * look[2]) / len;
            if (dot > bestDot) {
                bestDot = dot;
                best = w;
            }
        }
        if (best == null) {
            ChatAdapter.post(ChatText.of().add("Look at a boss waypoint someone is fighting to teleport", ChatText.RED));
            return;
        }
        String caller = best.t.calledBy();
        if (caller.equalsIgnoreCase(me)) {
            ChatAdapter.post(ChatText.of().add("You are the one fighting " + best.name, ChatText.RED));
            return;
        }
        PlayerAdapter.sendCommand("tp " + caller);
        ChatAdapter.post(ChatText.of().add("Teleporting to ", ChatText.GRAY)
                .add(caller, ChatText.YELLOW, ChatText.UNDERLINED).add(" at ", ChatText.GRAY)
                .add(best.name, ChatText.YELLOW, ChatText.BOLD));
    }
}
