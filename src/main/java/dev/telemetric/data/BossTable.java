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
 * {@code Extractor/Boss.json}: every boss — wiki minibosses / realm / event bosses (spawn spots, icons, Boss Tracker
 * data) and every dungeon loot boss, with drops and boss bar hashes. Pure Java.
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
     */
    public record Tracker(Pattern call, Pattern spawn, Pattern defeat, boolean idle, int portalSeconds,
                          boolean progress, List<Spot> waypoints) {}

    /**
     * @param id      name key ({@link Names#key})
     * @param type    {@code miniboss}, {@code boss}, {@code eventBoss} or {@code dungeon}
     * @param area    where a realm boss spawns ({@code Realm}, a biome, {@code Shadowlands}, …), may be empty
     * @param icon    item model of its menu icon ({@code telos:material/boss/anubis}), may be empty
     * @param dungeon dungeon of a dungeon boss, else empty
     * @param tracker Boss Tracker data, or null when it is not tracked
     */
    public record Boss(String id, String name, String type, String area, String icon, List<Spot> positions,
                       String dungeon, Tracker tracker) {
        public boolean isDungeonBoss() {
            return "dungeon".equals(type);
        }
    }

    static BossTable parse(JsonObject root) {
        int defaultPortal = root.has("portalSeconds") ? root.get("portalSeconds").getAsInt() : 30;
        List<Boss> all = new ArrayList<>();
        Map<String, Boss> realm = new HashMap<>();
        List<Boss> tracked = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("bosses")) {
            JsonObject o = e.getAsJsonObject();
            String name = JsonFields.str(o, "name");
            String type = JsonFields.str(o, "type"), location = JsonFields.str(o, "location");
            boolean dungeon = "dungeon".equals(type);
            Boss b = new Boss(Names.key(name), name, type, dungeon ? "" : location, JsonFields.str(o, "icon"),
                    spots(o, "positions", name), dungeon ? location : "",
                    tracker(o.getAsJsonObject("tracker"), name, defaultPortal));
            all.add(b);
            if (b.isDungeonBoss()) continue;
            realm.putIfAbsent(Names.key(name), b);
            if (b.tracker() != null) tracked.add(b);
        }
        List<String> zones = new ArrayList<>();
        if (root.has("zones")) for (JsonElement z : root.getAsJsonArray("zones")) zones.add(z.getAsString());
        JsonArray bosses = root.getAsJsonArray("bosses");
        return new BossTable(List.copyOf(all), Map.copyOf(realm), List.copyOf(tracked), defaultPortal, List.copyOf(zones),
                LootTable.parse(bosses, root.getAsJsonObject("aliases")), TelosData.BossBars.parse(bosses));
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

    /** The zone a boss belongs to (its area is a zone), or null. */
    public String zoneOf(Boss boss) {
        for (String z : zones) if (Names.placeKey(z).equals(Names.placeKey(boss.area()))) return z;
        return null;
    }

    /** Realm (non-dungeon) boss by name, any spelling ("cog sentinel", "Cog Sentinel"), or null. */
    public Boss realm(String name) {
        return name == null ? null : realmByKey.get(Names.key(name));
    }

    private static Tracker tracker(JsonObject t, String boss, int defaultPortal) {
        if (t == null) return null;
        return new Tracker(regex(t, "call", boss), regex(t, "spawn", boss), regex(t, "defeat", boss),
                t.has("idle") && t.get("idle").getAsBoolean(),
                t.has("portalSeconds") ? t.get("portalSeconds").getAsInt() : defaultPortal,
                t.has("progress") && t.get("progress").getAsBoolean(), spots(t, "waypoints", boss));
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
