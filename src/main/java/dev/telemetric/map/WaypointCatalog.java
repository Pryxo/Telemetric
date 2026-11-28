package dev.telemetric.map;

import dev.telemetric.data.BossTable;
import dev.telemetric.data.BossTable.Boss;
import dev.telemetric.data.MapMarkerTable;
import dev.telemetric.data.MapMarkerTable.Category;
import dev.telemetric.data.MapMarkerTable.Source;
import dev.telemetric.data.MapMarkerTable.Spot;
import dev.telemetric.data.MapTable;
import dev.telemetric.data.Names;
import dev.telemetric.data.TelosData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the Waypoints module can mark: every {@code markers} entry of {@code mapMarkers.json}, plus the Boss
 * Tracker's realm bosses the map doesn't list (Voided Omnipotent). An entry is <b>live</b> when the Boss Tracker
 * knows its name (a boss or one of its fixed markers such as the Cog Stabilizers): it's drawn where and while the
 * tracker says so. Every other entry is <b>static</b>: drawn at all of its spots while you're in its region.
 * Rebuilt whenever one of the tables is reloaded.
 */
public final class WaypointCatalog {

    /** Bosses whose waypoints (and fixed markers) are off until switched on: optional bosses, as before. */
    private static final Set<String> OFF_BY_DEFAULT = Set.of(Names.key("Cog Sentinel"), Names.key("Voided Omnipotent"));

    /**
     * @param key         toggle key ({@link Names#key} of the name)
     * @param category    {@code mapMarkers.json} category (list group, static colour)
     * @param region      {@code map.json} region id
     * @param regionLabel its label ("Shadowlands")
     * @param icon        item model, or null
     * @param spots       where it is (centred on the block); live entries use the tracker's spot instead
     * @param live        position and state come from the Boss Tracker
     * @param defaultOn   on until the user switches it
     */
    public record Entry(String key, String name, Category category, String region, String regionLabel, String icon,
                        String note, List<Spot> spots, boolean live, boolean defaultOn) {}

    private static MapMarkerTable builtMarkers;
    private static BossTable builtBosses;
    private static MapTable builtMap;
    private static List<Entry> entries = List.of();
    private static Map<String, Entry> byKey = Map.of();

    private WaypointCatalog() {}

    /** All entries, grouped by category (legend order), the map's order inside each. */
    public static synchronized List<Entry> all() {
        MapMarkerTable markers = TelosData.MAP_MARKERS.get();
        BossTable bosses = TelosData.BOSSES.get();
        MapTable map = TelosData.MAP.get();
        if (markers != builtMarkers || bosses != builtBosses || map != builtMap) {
            builtMarkers = markers;
            builtBosses = bosses;
            builtMap = map;
            entries = build(markers, bosses, map);
            Map<String, Entry> index = new HashMap<>();
            for (Entry e : entries) index.putIfAbsent(e.key(), e);
            byKey = index;
        }
        return entries;
    }

    /** The entry with this toggle key, or null. */
    public static Entry get(String key) {
        all();
        synchronized (WaypointCatalog.class) {
            return byKey.get(key);
        }
    }

    /** The categories that have at least one static entry (they get a colour setting). */
    public static List<Category> staticCategories() {
        List<Category> out = new ArrayList<>();
        for (Entry e : all()) if (!e.live() && !out.contains(e.category())) out.add(e.category());
        return out;
    }

    private static List<Entry> build(MapMarkerTable markers, BossTable bosses, MapTable map) {
        // tracker names → the boss they belong to (the boss itself, or the boss of a fixed marker)
        Map<String, Boss> owner = new HashMap<>();
        for (Boss b : bosses.tracked()) {
            owner.putIfAbsent(b.id(), b);
            if (b.tracker() != null) for (BossTable.Spot w : b.tracker().waypoints()) owner.putIfAbsent(Names.key(w.name()), b);
        }

        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MapMarkerTable.Entry m : markers.markers()) {
            Category c = markers.byId(m.category());
            if (c == null || c.source() != Source.MARKERS) continue;
            String key = Names.key(m.name());
            if (key.isEmpty() || !seen.add(key)) continue;
            Boss b = owner.get(key);
            boolean live = b != null;
            boolean on = live ? !OFF_BY_DEFAULT.contains(b.id()) : false; // map-only points are opt-in
            out.add(new Entry(key, m.name(), c, m.region(), regionLabel(map, m.region()),
                    m.icon() != null ? m.icon() : c.icon(), m.note(), m.spots(), live, on));
        }

        // tracked bosses the map doesn't list, under the first live category
        Category liveCat = null;
        for (Category c : markers.categories()) {
            if (c.source() == Source.MARKERS && c.live()) {
                liveCat = c;
                break;
            }
        }
        if (liveCat != null) {
            for (Boss b : bosses.tracked()) {
                if (b.tracker() != null && b.tracker().progress() || !seen.add(b.id())) continue; // Raphael has no spot
                MapTable.Region r = map.forZone(bosses.zoneOf(b));
                String region = r == null ? "" : r.id();
                List<Spot> spots = new ArrayList<>();
                for (BossTable.Spot s : b.positions()) spots.add(new Spot(s.x() + 0.5, s.y(), s.z() + 0.5));
                out.add(new Entry(b.id(), b.name(), liveCat, region, r == null ? "Realm" : r.label(),
                        b.icon().isEmpty() ? null : b.icon(), "", List.copyOf(spots), true,
                        !OFF_BY_DEFAULT.contains(b.id())));
            }
        }

        List<Category> order = markers.categories();
        out.sort(Comparator.comparingInt(e -> order.indexOf(e.category()))); // stable: map order inside a group
        return List.copyOf(out);
    }

    private static String regionLabel(MapTable map, String id) {
        MapTable.Region r = map.byId(id);
        return r == null ? id : r.label();
    }
}
