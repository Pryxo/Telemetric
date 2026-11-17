package dev.telemetric.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dungeon → Boss → drops, read from the bosses of {@code Extractor/Boss.json}. Feeds the Pity Counter (which drops a
 * boss kill counts for), drop detection (a dropped item's model / name → its drop) and the drop messages. Pure Java.
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
    /** Dungeon boss whose name is not written yet ({@code manual/bosses.json}). */
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

    /**
     * One boss's drops. {@code boss} may be {@value #UNKNOWN} for a dungeon whose final boss is not named yet.
     *
     * @param pityOnly {@link Names#key name keys} of the only drops a kill of this boss adds pity to (Boss.json
     *                 {@code pityOnly}, e.g. Seraph's Domain: it drops the earlier bosses' loot, but only its own extra
     *                 item gains pity); empty = every pity drop
     */
    public record BossLoot(String dungeon, String boss, List<Drop> drops, Set<String> pityOnly) {

        /** Drops a kill of this boss adds pity to (normal and shiny). */
        public List<Drop> pityDrops() {
            List<Drop> out = new ArrayList<>();
            for (Drop d : drops) {
                // shinies have no real pity: their counter (kills since one) always counts where they can drop
                if (d.hasPity() && (d.shiny() || pityOnly.isEmpty() || pityOnly.contains(Names.key(d.name())))) out.add(d);
            }
            return out;
        }

        public boolean isRealm() {
            return REALM.equals(dungeon);
        }
    }

    /**
     * From the {@code bosses} array of Boss.json: every boss with a {@code drops} list, dungeon bosses by their
     * {@code location}, every other boss in {@value #REALM} (kept last, see {@link #ownerOf}).
     */
    static LootTable parse(JsonArray bosses, JsonObject aliasTable) {
        Map<String, List<BossLoot>> dungeons = new LinkedHashMap<>();
        List<BossLoot> realm = new ArrayList<>();
        Map<String, List<BossLoot>> byBoss = new HashMap<>();
        Map<String, Drop> byModel = new HashMap<>(), byKey = new HashMap<>();
        Map<String, List<Drop>> byName = new HashMap<>();
        for (JsonElement el : bosses == null ? new JsonArray() : bosses) {
            JsonObject o = el.getAsJsonObject();
            if (!o.has("drops") || !o.get("drops").isJsonArray()) continue;
            String boss = JsonFields.str(o, "name");
            boolean dungeon = "dungeon".equals(JsonFields.str(o, "type"));
            String place = dungeon ? JsonFields.str(o, "location") : REALM;
            List<Drop> drops = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("drops")) {
                JsonObject d = e.getAsJsonObject();
                Drop drop = drop(d, false);
                drops.add(drop);
                if (d.has("shiny") && !drop.id().isEmpty()) drops.add(drop(d, true));
            }
            for (Drop drop : drops) {
                if (!drop.hasPity()) continue;
                byKey.putIfAbsent(drop.key(), drop);
                if (!drop.model().isEmpty()) byModel.putIfAbsent(drop.model(), drop);
                if (!drop.id().isEmpty()) byModel.putIfAbsent(drop.id(), drop);
                if (!drop.shiny()) byName.computeIfAbsent(Names.key(drop.name()), k -> new ArrayList<>()).add(drop);
            }
            Set<String> pityOnly = new HashSet<>();
            if (o.has("pityOnly") && o.get("pityOnly").isJsonArray()) {
                for (JsonElement n : o.getAsJsonArray("pityOnly")) {
                    if (n.isJsonPrimitive()) pityOnly.add(Names.key(n.getAsString()));
                }
            }
            BossLoot loot = new BossLoot(place, boss, List.copyOf(drops), Set.copyOf(pityOnly));
            if (dungeon) dungeons.computeIfAbsent(Names.placeKey(place), k -> new ArrayList<>()).add(loot);
            else realm.add(loot);
            if (!UNKNOWN.equals(boss)) byBoss.computeIfAbsent(Names.key(boss), k -> new ArrayList<>()).add(loot);
        }
        dungeons.replaceAll((k, v) -> List.copyOf(v));
        dungeons.put(Names.key(REALM), List.copyOf(realm));
        Map<String, String> aliases = new HashMap<>();
        if (aliasTable != null) {
            for (Map.Entry<String, JsonElement> e : aliasTable.entrySet()) {
                if (e.getValue().isJsonPrimitive()) aliases.put(Names.placeKey(e.getKey()), Names.placeKey(e.getValue().getAsString()));
            }
        }
        byBoss.replaceAll((k, v) -> List.copyOf(v));
        byName.replaceAll((k, v) -> List.copyOf(v));
        // dungeons keeps the file order (realm bosses last, see ownerOf) — Map.copyOf would scramble it
        return new LootTable(Collections.unmodifiableMap(dungeons), Map.copyOf(byBoss), Map.copyOf(byModel), Map.copyOf(byName),
                Map.copyOf(byKey), Map.copyOf(aliases));
    }

    /**
     * A Boss.json drop (or its shiny version: id + {@code -shiny}, chance = chance / {@code manual/shinyChance.json} divisor). Derived, not stored: kind
     * ({@code kind} only where it differs: id {@code pet/} = pet, {@code mount/} = mount, other id = item, no id with a
     * rarity = material, else fragment), loot group (companions: {@code companion}, fragments: none, else the
     * rarity) and the dropped item model ({@code <ns>:material/<id path>}).
     */
    private static Drop drop(JsonObject o, boolean shiny) {
        String name = JsonFields.str(o, "name"), id = JsonFields.str(o, "id"), rarity = JsonFields.str(o, "rarity");
        String kind = JsonFields.str(o, "kind");
        if (kind.isEmpty()) kind = impliedKind(id, rarity);
        if (shiny) id = id + "-shiny";
        String lootKey = switch (kind) {
            case "fragment" -> "";
            case "pet", "mount", "mountSkin" -> "companion";
            default -> rarity;
        };
        String model = "";
        int colon = id.indexOf(':');
        if (colon > 0) model = id.substring(0, colon) + ":material/" + id.substring(colon + 1);
        String key = !id.isEmpty() ? id : kind + ":" + Names.key(name) + (shiny ? "-shiny" : "");
        double chance = o.has("chance") ? o.get("chance").getAsDouble() : 0;
        if (shiny) chance = TelosData.SHINY_CHANCE.get().of(chance, rarity);
        return new Drop(key, name, kind, rarity, lootKey, id, model, shiny, chance, JsonFields.str(o, "from"));
    }

    /** Kind of a drop without a {@code kind} field (the table generator writes {@code kind} only where this differs). */
    private static String impliedKind(String id, String rarity) {
        if (!id.isEmpty()) {
            String path = id.substring(id.indexOf(':') + 1);
            return path.startsWith("pet/") ? "pet" : path.startsWith("mount/") ? "mount" : "item";
        }
        return rarity.isEmpty() ? "fragment" : "material";
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
