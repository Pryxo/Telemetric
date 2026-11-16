package dev.telemetric.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code Extractor/loottable.json}: Dungeon → Boss → drops. Feeds the Pity Counter (which drops a boss kill
 * counts for), drop detection (a dropped item's model / name → its drop) and the drop messages. Pure Java.
 *
 * @param dungeons dungeon key ({@link Names#placeKey}) → its loot bosses, final boss last ({@value #REALM} =
 *                 minibosses and bosses outside dungeons)
 */
public record LootTable(Map<String, List<BossLoot>> dungeons, Map<String, List<BossLoot>> bossesByKey,
                        Map<String, Drop> byModel, Map<String, List<Drop>> byName, Map<String, Drop> byKey,
                        Map<String, String> aliases) {

    static final LootTable EMPTY = new LootTable(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    /** Bucket of minibosses and bosses outside any dungeon. */
    public static final String REALM = "Realm";
    /** Boss name not written yet in {@code manual/bosses.json}. */
    public static final String UNKNOWN = "???";

    /**
     * One drop of a boss.
     *
     * @param key     stable id for counters: the item id ({@code telos:weapon/bow/ut-blunder}, shiny ids end in
     *                {@code -shiny}), else {@code <kind>:<name key>}
     * @param kind    {@code item}, {@code material}, {@code pet}, {@code mount}, {@code mountSkin} or {@code fragment}
     * @param rarity  item rarity ({@code bloodshot}) or companion rarity ({@code usual}), may be empty
     * @param lootKey loot group ({@code irradiated} … {@code unholy}, {@code companion}), empty for fragments
     * @param model   item model as dropped on the ground ({@code telos:material/...}), may be empty
     * @param from    where it drops besides the boss ({@code specificMob}, {@code anyMob}, {@code puzzle},
     *                {@code each}), may be empty
     */
    public record Drop(String key, String name, String kind, String rarity, String lootKey, String id, String model,
                       boolean shiny, double chance, String from) {

        /**
         * Counts towards pity: a real loot-group drop (not a fragment / tier equipment) of the boss kill itself —
         * not one from other mobs or a puzzle.
         */
        public boolean hasPity() {
            return !lootKey.isEmpty() && !"fragment".equals(kind)
                    && !"anyMob".equals(from) && !"specificMob".equals(from) && !"puzzle".equals(from);
        }

        public boolean isCompanion() {
            return "pet".equals(kind) || "mount".equals(kind) || "mountSkin".equals(kind);
        }
    }

    /** One boss's drops. {@code boss} may be {@value #UNKNOWN} for a dungeon whose final boss is not named yet. */
    public record BossLoot(String dungeon, String boss, List<Drop> drops) {

        /** Drops that count towards pity (normal and shiny). */
        public List<Drop> pityDrops() {
            List<Drop> out = new ArrayList<>();
            for (Drop d : drops) if (d.hasPity()) out.add(d);
            return out;
        }

        public boolean isRealm() {
            return REALM.equals(dungeon);
        }
    }

    static LootTable parse(JsonObject root) {
        Map<String, List<BossLoot>> dungeons = new LinkedHashMap<>();
        Map<String, List<BossLoot>> bosses = new HashMap<>();
        Map<String, Drop> byModel = new HashMap<>(), byKey = new HashMap<>();
        Map<String, List<Drop>> byName = new HashMap<>();
        for (Map.Entry<String, JsonElement> d : root.getAsJsonObject("loot").entrySet()) {
            List<BossLoot> list = new ArrayList<>();
            for (Map.Entry<String, JsonElement> b : d.getValue().getAsJsonObject().entrySet()) {
                List<Drop> drops = new ArrayList<>();
                for (JsonElement e : b.getValue().getAsJsonObject().getAsJsonArray("drops")) {
                    Drop drop = drop(e.getAsJsonObject());
                    drops.add(drop);
                    if (!drop.hasPity()) continue;
                    byKey.putIfAbsent(drop.key(), drop);
                    if (!drop.model().isEmpty()) byModel.putIfAbsent(drop.model(), drop);
                    if (!drop.id().isEmpty()) byModel.putIfAbsent(drop.id(), drop);
                    if (!drop.shiny()) byName.computeIfAbsent(Names.key(drop.name()), k -> new ArrayList<>()).add(drop);
                }
                BossLoot loot = new BossLoot(d.getKey(), b.getKey(), List.copyOf(drops));
                list.add(loot);
                if (!UNKNOWN.equals(b.getKey())) {
                    bosses.computeIfAbsent(Names.key(b.getKey()), k -> new ArrayList<>()).add(loot);
                }
            }
            dungeons.put(Names.placeKey(d.getKey()), List.copyOf(list));
        }
        Map<String, String> aliases = new HashMap<>();
        JsonObject al = root.getAsJsonObject("aliases");
        if (al != null) {
            for (Map.Entry<String, JsonElement> e : al.entrySet()) {
                aliases.put(Names.placeKey(e.getKey()), Names.placeKey(e.getValue().getAsString()));
            }
        }
        bosses.replaceAll((k, v) -> List.copyOf(v));
        byName.replaceAll((k, v) -> List.copyOf(v));
        return new LootTable(Map.copyOf(dungeons), Map.copyOf(bosses), Map.copyOf(byModel), Map.copyOf(byName),
                Map.copyOf(byKey), Map.copyOf(aliases));
    }

    private static Drop drop(JsonObject o) {
        String name = JsonFields.str(o, "name"), kind = JsonFields.str(o, "kind"), id = JsonFields.str(o, "id");
        boolean shiny = o.has("shiny") && o.get("shiny").getAsBoolean();
        String key = !id.isEmpty() ? id : kind + ":" + Names.key(name) + (shiny ? "-shiny" : "");
        return new Drop(key, name, kind, JsonFields.str(o, "rarity"), JsonFields.str(o, "lootKey"), id,
                JsonFields.str(o, "model"), shiny, o.has("chance") ? o.get("chance").getAsDouble() : 0,
                JsonFields.str(o, "from"));
    }

    // ------------------------------------------------------------------ lookups

    /** The loot bosses of the dungeon with this in-game (HUD) name, final boss last; empty if unknown. */
    public List<BossLoot> dungeon(String placeName) {
        String key = Names.placeKey(placeName);
        if (key.isEmpty() || key.equals(Names.key(REALM))) return List.of();
        List<BossLoot> list = dungeons.get(key);
        if (list == null && aliases.containsKey(key)) list = dungeons.get(aliases.get(key));
        return list == null ? List.of() : list;
    }

    /** A realm boss's loot (minibosses, world bosses), or null. */
    public BossLoot realm(String bossName) {
        for (BossLoot b : dungeons.getOrDefault(Names.key(REALM), List.of())) {
            if (Names.key(b.boss()).equals(Names.key(bossName))) return b;
        }
        return null;
    }

    /**
     * The loot a "Defeated &lt;boss&gt;" counts for: that boss in the current dungeon ({@code placeName}, may be
     * null), else the realm boss of that name, else any boss of that name. Null if the boss drops nothing known.
     */
    public BossLoot forDefeat(String bossName, String placeName) {
        String key = Names.key(bossName);
        for (BossLoot b : dungeon(placeName)) if (Names.key(b.boss()).equals(key)) return b;
        BossLoot realm = realm(bossName);
        if (realm != null) return realm;
        List<BossLoot> any = bossesByKey.get(key);
        return any == null || any.isEmpty() ? null : any.getFirst();
    }

    /** Drop with this dropped-item model or item id ({@code telos:material/...-shiny} included), or null. */
    public Drop byModel(String model) {
        return model == null ? null : byModel.get(model);
    }

    /** Non-shiny drops with this name (several bosses can share one item). */
    public List<Drop> named(String name) {
        return byName.getOrDefault(Names.key(name), List.of());
    }

    /** The shiny variant of a drop, or null. */
    public Drop shinyOf(Drop drop) {
        if (drop == null || drop.shiny()) return drop;
        return byKey.get(drop.id().isEmpty() ? drop.key() + "-shiny" : drop.id() + "-shiny");
    }

    /** Drop by counter key, or null. */
    public Drop byKey(String key) {
        return byKey.get(key);
    }

    /**
     * The boss that drops this: in the dungeon {@code placeName} if it has it there, else the first one listed
     * (realm bosses come last in the file, so dungeons win). Null if none.
     */
    public BossLoot ownerOf(Drop drop, String placeName) {
        if (drop == null) return null;
        for (BossLoot b : dungeon(placeName)) if (b.drops().contains(drop)) return b;
        for (List<BossLoot> list : dungeons.values()) {
            for (BossLoot b : list) if (b.drops().contains(drop)) return b;
        }
        return null;
    }

    /**
     * The drop whose name starts {@code text} ("Holy Cross [..]" → Holy Cross), the longest name winning; case,
     * spaces and punctuation ignored. Null if none.
     */
    public Drop startingWith(String text) {
        String key = Names.key(text);
        String best = null;
        for (String name : byName.keySet()) {
            if (key.startsWith(name) && (best == null || name.length() > best.length())) best = name;
        }
        return best == null ? null : byName.get(best).getFirst();
    }
}
