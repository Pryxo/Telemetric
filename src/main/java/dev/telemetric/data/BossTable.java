package dev.telemetric.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code tables/Boss.json}: every boss — realm bosses (zone, spawn spots, Boss Tracker data + icon) and dungeon bosses
 * ({@code dungeon}), with drops and boss bar hashes. Pure Java.
 *
 * @param loot the drops of the same file ({@link TelosData#LOOT})
 * @param bars the boss bar hashes of the same file ({@link TelosData#BOSS_BARS})
 */
public record BossTable(List<Boss> all, Map<String, Boss> realmByKey, List<Boss> tracked, int portalSeconds,
                        List<String> zones, LootTable loot, TelosData.BossBars bars) {

    static final BossTable EMPTY = new BossTable(List.of(), Map.of(), List.of(), 30, List.of(), LootTable.EMPTY,
            TelosData.BossBars.EMPTY);

    /** A fixed world spot (spawn point or extra waypoint). */
    public record Spot(String name, int x, int y, int z) {}

    /**
     * Boss Tracker data of a realm boss.
     *
     * @param call          the text after {@code "[<Boss>] "} when the boss names the player fighting it (group
     *                      {@code player}), or null
     * @param spawn         line that marks an always-present boss alive, or null
     * @param defeat        line that sends it back to idle, or null
     * @param idle          always present (Shadowlands minibosses, Cog Sentinel): listed grey until it spawns
     * @param portalSeconds how long its portal stays after a defeat
     * @param progress      its portal opens once every world boss is down (n/m count shown)
     * @param waypoints     extra fixed markers shown with it (Cog Stabilizers)
     * @param counter       chat line that sets its own n/m count (groups {@code n}, {@code max}), e.g.
     *                      {@code (1/5) Void Crystals shattered}, or null
     * @param countFrom     bosses whose defeat adds 1 to its count (Defender: Reaper, Warden, Herald)
     * @param counterMax    count it spawns / opens at (until the chat line or {@code /bosses} names it)
     * @param readyText     shown once the count is full ("Spawned", "Open", …)
     * @param menuName      its {@code /bosses} item name when that differs ("Tenebris Portal"), or empty
     * @param ready         whole server line that fills the count ("A portal to Tenebris has opened in …"), or null
     */
    public record Tracker(Pattern call, Pattern spawn, Pattern defeat, boolean idle, int portalSeconds,
                          boolean progress, List<Spot> waypoints, Pattern counter, List<String> countFrom,
                          int counterMax, String readyText, Pattern ready, String menuName) {
        /** Listed in the Boss Tracker's bottom section (Raphael and the n/m counter bosses). */
        public boolean counted() {
            return progress || counter != null || !countFrom.isEmpty();
        }
    }

    /**
     * @param id      name key ({@link Names#key})
     * @param zone    separate realm region a realm boss spawns in ({@code Shadowlands}), empty = the open realm
     * @param icon    item model of its Boss Tracker icon ({@code telos:material/boss/anubis}), may be empty
     * @param dungeon dungeon of a dungeon boss, else empty
     * @param tracker Boss Tracker data, or null when it is not tracked
     * @param areas   HUD location names (bottom-right box) where the Pity Counter shows this realm boss's pity when
     *                no boss is near and no boss bar is up; {@link Names#placeKey}s, may be empty
     */
    public record Boss(String id, String name, String zone, String icon, List<Spot> positions, String dungeon,
                       Tracker tracker, List<String> areas) {
        public boolean isDungeonBoss() {
            return !dungeon.isEmpty();
        }
    }

    static BossTable parse(JsonObject root) {
        int defaultPortal = root.has("portalSeconds") ? root.get("portalSeconds").getAsInt() : 30;
        List<Boss> all = new ArrayList<>();
        Map<String, Boss> realm = new HashMap<>();
        List<Boss> tracked = new ArrayList<>();
        List<String> zones = new ArrayList<>();
        JsonArray bosses = enabled(root);
        for (JsonElement e : bosses) {
            JsonObject o = e.getAsJsonObject();
            String name = JsonFields.str(o, "name"), dungeon = JsonFields.str(o, "dungeon");
            String zone = dungeon.isEmpty() ? JsonFields.str(o, "zone") : "";
            Boss b = new Boss(Names.key(name), name, zone, JsonFields.str(o, "icon"), spots(o, "positions", name),
                    dungeon, tracker(o.getAsJsonObject("tracker"), name, defaultPortal), areas(o));
            all.add(b);
            if (b.isDungeonBoss()) {
                if (b.tracker() != null && b.tracker().counted()) tracked.add(b); // spawned by a realm counter (Voided Omnipotent)
                continue;
            }
            if (!zone.isEmpty() && !zones.contains(zone)) zones.add(zone);
            realm.putIfAbsent(Names.key(name), b);
            if (b.tracker() != null) tracked.add(b);
        }
        // areas without realm bosses of their own: the open realm's bosses are hidden there too (Voided Abyss)
        if (root.has("emptyZones") && root.get("emptyZones").isJsonArray()) {
            for (JsonElement z : root.getAsJsonArray("emptyZones")) {
                if (!zones.contains(z.getAsString())) zones.add(z.getAsString());
            }
        }
        return new BossTable(List.copyOf(all), Map.copyOf(realm), List.copyOf(tracked), defaultPortal, List.copyOf(zones),
                LootTable.parse(bosses), TelosData.BossBars.parse(bosses));
    }

    /**
     * The {@code bosses} array without the ones named in the root {@code disabled} list (boss names, matched by
     * {@link Names#key}): their entries stay in the file but are never loaded — no tracker, pity, loot or boss bar.
     */
    private static JsonArray enabled(JsonObject root) {
        JsonArray src = root.getAsJsonArray("bosses"), out = new JsonArray();
        if (src == null) return out;
        java.util.Set<String> off = new java.util.HashSet<>();
        if (root.has("disabled") && root.get("disabled").isJsonArray())
            for (JsonElement d : root.getAsJsonArray("disabled"))
                if (d.isJsonPrimitive()) off.add(Names.key(d.getAsString()));
        for (JsonElement e : src)
            if (off.isEmpty() || !off.contains(Names.key(JsonFields.str(e.getAsJsonObject(), "name")))) out.add(e);
        return out;
    }

    /**
     * The zone (separate realm region, e.g. Shadowlands) a place name lies in, or null for the open realm.
     * Matches loosely: "The Shadowlands" is in zone "Shadowlands".
     */
    public String zoneOf(String placeName) {
        String key = Names.placeKey(placeName);
        if (key.isEmpty()) return null;
        for (String z : zones) if (key.contains(Names.placeKey(z))) return z;
        return null;
    }

    /** The zone a boss belongs to, or null for the open realm (and dungeon bosses). */
    public String zoneOf(Boss boss) {
        return boss.zone().isEmpty() ? null : boss.zone();
    }

    /** Realm (non-dungeon) boss by name, any spelling ("cog sentinel", "Cog Sentinel"), or null. */
    public Boss realm(String name) {
        return name == null ? null : realmByKey.get(Names.key(name));
    }

    /** Realm bosses whose {@code areas} hold this HUD location name, file order; empty if none. */
    public List<Boss> inArea(String placeName) {
        String key = Names.placeKey(placeName);
        if (key.isEmpty()) return List.of();
        List<Boss> out = new ArrayList<>();
        for (Boss b : all) if (!b.isDungeonBoss() && b.areas().contains(key)) out.add(b);
        return out;
    }

    /** {@code "areas": ["Ancient Grove", …]} (or one string) → place keys. */
    private static List<String> areas(JsonObject o) {
        JsonElement a = o.get("areas");
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        if (a.isJsonPrimitive()) out.add(Names.placeKey(a.getAsString()));
        else if (a.isJsonArray()) for (JsonElement n : a.getAsJsonArray()) if (n.isJsonPrimitive()) out.add(Names.placeKey(n.getAsString()));
        out.removeIf(String::isEmpty);
        return List.copyOf(out);
    }

    private static Tracker tracker(JsonObject t, String boss, int defaultPortal) {
        if (t == null) return null;
        return new Tracker(regex(t, "call", boss), regex(t, "spawn", boss), regex(t, "defeat", boss),
                t.has("idle") && t.get("idle").getAsBoolean(),
                t.has("portalSeconds") ? t.get("portalSeconds").getAsInt() : defaultPortal,
                t.has("progress") && t.get("progress").getAsBoolean(), spots(t, "waypoints", boss),
                regex(t, "counter", boss), names(t, "countFrom"),
                t.has("counterMax") ? t.get("counterMax").getAsInt() : 5,
                t.has("readyText") ? t.get("readyText").getAsString() : "Spawned", regex(t, "ready", boss), JsonFields.str(t, "menuName"));
    }

    private static List<String> names(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (var e : o.getAsJsonArray(key)) out.add(e.getAsString());
        return List.copyOf(out);
    }

    /** A hand-written regex; a broken one is dropped (logged) instead of failing the whole table. */
    private static Pattern regex(JsonObject o, String key, String boss) {
        String s = JsonFields.str(o, key);
        if (s.isEmpty()) return null;
        try {
            return Pattern.compile(s);
        } catch (PatternSyntaxException ex) {
            dev.telemetric.Telemetric.LOGGER.warn("Boss.json: bad {} regex for {}: {}", key, boss, ex.getDescription());
            return null;
        }
    }

    private static List<Spot> spots(JsonObject o, String key, String defaultName) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return List.of();
        List<Spot> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) {
            JsonObject p = e.getAsJsonObject();
            String name = JsonFields.str(p, "name");
            out.add(new Spot(name.isEmpty() ? defaultName : name, p.get("x").getAsInt(), p.get("y").getAsInt(),
                    p.get("z").getAsInt()));
        }
        return List.copyOf(out);
    }
}
