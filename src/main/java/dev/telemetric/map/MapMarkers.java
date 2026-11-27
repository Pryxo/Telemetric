package dev.telemetric.map;

import dev.telemetric.Telemetric;
import dev.telemetric.data.BossTable.Spot;
import dev.telemetric.data.MapMarkerTable;
import dev.telemetric.data.MapMarkerTable.Category;
import dev.telemetric.data.MapMarkerTable.Entry;
import dev.telemetric.data.MapMarkerTable.Source;
import dev.telemetric.data.MapTable;
import dev.telemetric.data.Names;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.module.impl.visual.BossTrackerModule;
import dev.telemetric.tracking.RealmBossTracker;
import dev.telemetric.tracking.RealmBossTracker.State;
import dev.telemetric.tracking.RealmBossTracker.Tracked;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What the Telos Map and the minimap draw on top of the image: the {@code markers} of {@code mapMarkers.json} for
 * the region, plus loaded players. Everything (names, places, icons, notes, colours, state texts) comes from that
 * table; {@code live} categories add the Boss Tracker's state, matched by marker name.
 */
public final class MapMarkers {

    /**
     * @param state     one-line state or note ("Alive", "Fighting: Steve", "Miniboss"), may be empty
     * @param icon      item model ({@code telos:material/boss/anubis}), or null
     * @param calledBy  the player fighting it (left-click teleports there), or null
     * @param skin      player skin texture ({@link Source#PLAYERS}), or null
     * @param active    alive / fought / portal open (drawn fully opaque and above the rest)
     */
    public record Marker(Category category, String label, double x, double y, double z, int color, String state,
                         String icon, String calledBy, String skin, float yaw, boolean active) {
        public Source kind() {
            return category.source();
        }
    }

    private MapMarkers() {}

    /** The categories of {@code mapMarkers.json}. */
    public static List<Category> categories() {
        return TelosData.MAP_MARKERS.get().categories();
    }

    /** The markers of {@code region} in the categories {@code shown} accepts, back to front (inactive first, players last). */
    public static List<Marker> collect(MapTable.Region region, Predicate<Category> shown) {
        MapMarkerTable table = TelosData.MAP_MARKERS.get();
        BossTrackerModule colors = Telemetric.get().modules().get(BossTrackerModule.class);
        long now = System.currentTimeMillis();
        Map<String, Tracked> live = null;

        List<Marker> back = new ArrayList<>(), front = new ArrayList<>();
        for (Entry e : table.markers()) {
            if (!e.region().equals(region.id())) continue;
            Category c = table.byId(e.category());
            if (c == null || c.source() != Source.MARKERS || !shown.test(c)) continue;
            String icon = e.icon() != null ? e.icon() : c.icon();
            if (!c.live()) {
                int color = color(e.color() != null ? e.color() : c.color(), colors);
                for (MapMarkerTable.Spot s : e.spots()) {
                    back.add(new Marker(c, e.name(), s.x(), s.y(), s.z(), color, e.note(), icon, null, null, 0, false));
                }
                continue;
            }
            if (live == null) live = tracked();
            Tracked t = live.get(Names.key(e.name()));
            if (t == null) {
                MapMarkerTable.Spot s = e.spots().getFirst();
                back.add(new Marker(c, e.name(), s.x(), s.y(), s.z(), colors.stateColor(State.DEFEATED, false),
                        table.state("unseen"), icon, null, null, 0, false));
                continue;
            }
            boolean fighting = t.state() == State.ALIVE && t.calledBy() != null;
            String state = switch (t.state()) {
                case ALIVE -> fighting ? table.state("fighting", "player", t.calledBy()) : table.state("alive");
                case PORTAL -> table.state("portal", "seconds", String.valueOf(t.portalSeconds(now)));
                case IDLE -> table.state("idle");
                case DEFEATED -> table.state("defeated");
            };
            if (!e.note().isEmpty()) state = state.isEmpty() ? e.note() : state + " · " + e.note();
            boolean active = t.state() == State.ALIVE || t.state() == State.PORTAL;
            MapMarkerTable.Spot s = nearest(e.spots(), t.spot());
            (active ? front : back).add(new Marker(c, e.name(), s.x(), s.y(), s.z(),
                    colors.stateColor(t.state(), fighting), state, icon, fighting ? t.calledBy() : null, null, 0, active));
        }

        List<Marker> out = new ArrayList<>(back.size() + front.size() + 8);
        out.addAll(back);
        out.addAll(front);
        Category playerCat = table.bySource(Source.PLAYERS);
        if (playerCat != null && shown.test(playerCat)) {
            int color = color(playerCat.color(), colors);
            for (DungeonAdapter.PlayerMarker p : DungeonAdapter.players(PlayerAdapter.partialTick())) {
                if (p.self() || !p.real() || !region.contains(p.x(), p.z())) continue;
                out.add(new Marker(playerCat, p.name(), p.x(), p.y(), p.z(), color, "", playerCat.icon(), null,
                        p.skin(), p.yaw(), true));
            }
        }
        return out;
    }

    /** True if {@code region} has anything of {@code c} (for the legend; players always count). */
    public static boolean any(List<Marker> all, Category c) {
        if (c.source() == Source.PLAYERS) return true;
        for (Marker m : all) if (m.category() == c) return true;
        return false;
    }

    /** The colour a category's legend swatch uses. */
    public static int legendColor(Category c) {
        return color(c.color(), Telemetric.get().modules().get(BossTrackerModule.class));
    }

    /** ARGB of a table colour: its RGB, or the Boss Tracker colour of its state. */
    private static int color(MapMarkerTable.Color c, BossTrackerModule colors) {
        if (c.state() == null) return 0xFF000000 | c.rgb();
        return switch (c.state()) {
            case "fighting" -> colors.stateColor(State.ALIVE, true);
            case "alive" -> colors.stateColor(State.ALIVE, false);
            case "portal" -> colors.stateColor(State.PORTAL, false);
            case "defeated" -> colors.stateColor(State.DEFEATED, false);
            default -> colors.stateColor(State.IDLE, false);
        };
    }

    /** The Boss Tracker's bosses by name key. */
    private static Map<String, Tracked> tracked() {
        Map<String, Tracked> out = new HashMap<>();
        for (Tracked t : RealmBossTracker.get().all()) {
            if (t.marker || t.boss == null) continue;
            out.putIfAbsent(Names.key(t.boss.name()), t);
            out.putIfAbsent(Names.key(t.name), t);
        }
        return out;
    }

    /** The table spot closest to where the Boss Tracker says the boss is (the first one if it doesn't know). */
    private static MapMarkerTable.Spot nearest(List<MapMarkerTable.Spot> spots, Spot at) {
        MapMarkerTable.Spot best = spots.getFirst();
        if (at == null || spots.size() == 1) return best;
        double bestD = Double.MAX_VALUE;
        for (MapMarkerTable.Spot s : spots) {
            double dx = s.x() - at.x() - 0.5, dy = s.y() - at.y(), dz = s.z() - at.z() - 0.5;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }
}
