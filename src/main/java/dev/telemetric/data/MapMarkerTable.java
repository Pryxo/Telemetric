package dev.telemetric.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code tables/mapMarkers.json}: everything the Telos Map and the minimap mark ({@link TelosData#MAP_MARKERS}).
 * Nothing about markers is in code; format in {@code data/README.md}.
 * <ul>
 *   <li>{@code states}: the tooltip text of each Boss Tracker state ({@code {player}}, {@code {seconds}}).</li>
 *   <li>{@code categories}: legend rows / marker types, in drawing order, with look and default visibility.
 *       {@code live} categories take state and colour from the Boss Tracker (matched by marker name).</li>
 *   <li>{@code markers}: every marked thing — name, category, region, icon, note and one or more spots.</li>
 * </ul>
 */
public record MapMarkerTable(Map<String, String> states, List<Category> categories, List<Entry> markers) {

    static final MapMarkerTable EMPTY = new MapMarkerTable(Map.of(), List.of(), List.of());

    /** {@code markers}: the {@code markers} list; {@code players}: loaded players. */
    public enum Source { MARKERS, PLAYERS }

    /**
     * How a marker is drawn. With an icon in the resource pack (its own or the category's), {@code ICON}/{@code DOT}
     * draw a round badge and {@code DIAMOND} a diamond badge with the item inside; without one a dot / diamond.
     */
    public enum Shape { ICON, DOT, DIAMOND, HEAD }

    /**
     * A colour: a fixed 0xRRGGBB, or one of the Boss Tracker's (user-set) state colours.
     *
     * @param state Boss Tracker state ({@code alive}, {@code fighting}, {@code portal}, {@code idle},
     *              {@code defeated}), or null for {@code rgb}
     */
    public record Color(int rgb, String state) {
        static final Color WHITE = new Color(0xFFFFFF, null);

        static Color parse(String s, Color fallback) {
            if (s.isEmpty()) return fallback;
            if (s.startsWith("#")) return new Color(JsonFields.rgb(s), null);
            return new Color(0, s.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * @param label     legend row and settings name
     * @param live      markers show the Boss Tracker's state and state colours
     * @param icon      item model for the legend and for markers without their own, or null
     * @param color     legend colour, and marker colour unless {@code live}
     * @param size      icon size factor
     * @param labelZoom names show from this zoom (GUI pixels per block); {@code NaN} = only on hover
     * @param fade      opacity of inactive markers (not spawned, defeated)
     * @param map       shown on the Telos Map by default
     * @param minimap   shown on the minimap by default
     */
    public record Category(String id, String label, Source source, boolean live, Shape shape, String icon, Color color,
                           double size, double labelZoom, double fade, boolean map, boolean minimap) {
        public boolean labelsAt(double scale) {
            return !Double.isNaN(labelZoom) && scale >= labelZoom;
        }
    }

    /** A block position (the marker sits in its centre). */
    public record Spot(double x, double y, double z) {}

    /**
     * @param region {@code map.json} region id
     * @param icon   item model, or null for the category's
     * @param color  own colour, or null for the category's
     * @param note   tooltip line, may be empty
     * @param spots  where it is (a live marker uses the one the Boss Tracker reports, else the first)
     */
    public record Entry(String category, String name, String region, String icon, Color color, String note,
                        List<Spot> spots) {}

    public Category byId(String id) {
        for (Category c : categories) if (c.id.equals(id)) return c;
        return null;
    }

    /** The first category fed by {@code source}, or null. */
    public Category bySource(Source source) {
        for (Category c : categories) if (c.source == source) return c;
        return null;
    }

    public List<String> labels() {
        List<String> out = new ArrayList<>(categories.size());
        for (Category c : categories) out.add(c.label);
        return out;
    }

    /** The {@code states} text for {@code key} with {@code {name}} placeholders filled, or "". */
    public String state(String key, String... namesAndValues) {
        String s = states.getOrDefault(key, "");
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) s = s.replace("{" + namesAndValues[i] + "}", namesAndValues[i + 1]);
        return s;
    }

    static MapMarkerTable parse(JsonObject root) {
        Map<String, String> states = new HashMap<>(JsonFields.stringMap(root, "states"));
        List<Category> cats = new ArrayList<>();
        for (JsonObject o : JsonFields.objects(root, "categories")) {
            String id = JsonFields.str(o, "id");
            Source source = enumOf(Source.class, JsonFields.str(o, "source"), Source.MARKERS);
            if (id.isEmpty()) continue;
            String label = JsonFields.str(o, "label");
            cats.add(new Category(id, label.isEmpty() ? id : label, source, JsonFields.bool(o, "live", false),
                    enumOf(Shape.class, JsonFields.str(o, "shape"), Shape.DOT), icon(o),
                    Color.parse(JsonFields.str(o, "color"), Color.WHITE), Math.clamp(num(o, "size", 1), 0.2, 5),
                    num(o, "labelZoom", Double.NaN), Math.clamp(num(o, "fade", 1), 0, 1),
                    JsonFields.bool(o, "map", true), JsonFields.bool(o, "minimap", true)));
        }
        List<Entry> markers = new ArrayList<>();
        java.util.Set<String> unknown = new java.util.TreeSet<>();
        for (JsonObject o : JsonFields.objects(root, "markers")) {
            String cat = JsonFields.str(o, "category"), region = JsonFields.str(o, "region");
            if (!cat.isEmpty() && cats.stream().noneMatch(c -> c.id.equals(cat))) unknown.add(cat);
            List<Spot> spots = new ArrayList<>();
            for (JsonObject s : JsonFields.objects(o, "spots")) {
                if (s.has("x") && s.has("z")) spots.add(new Spot(num(s, "x", 0) + 0.5, num(s, "y", 64), num(s, "z", 0) + 0.5));
            }
            if (cat.isEmpty() || region.isEmpty() || spots.isEmpty()) continue;
            markers.add(new Entry(cat, JsonFields.str(o, "name"), region, icon(o),
                    Color.parse(JsonFields.str(o, "color"), null), JsonFields.str(o, "note"), List.copyOf(spots)));
        }
        if (!unknown.isEmpty()) {
            dev.telemetric.Telemetric.LOGGER.warn("mapMarkers.json: markers use unknown categories {} (not drawn)", unknown);
        }
        return new MapMarkerTable(Map.copyOf(states), List.copyOf(cats), List.copyOf(markers));
    }

    private static String icon(JsonObject o) {
        String icon = JsonFields.str(o, "icon");
        return icon.isEmpty() ? null : icon;
    }

    private static double num(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
        return e.getAsDouble();
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
        try {
            return name.isEmpty() ? fallback : Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
