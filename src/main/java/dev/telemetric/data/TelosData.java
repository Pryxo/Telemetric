package dev.telemetric.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.telemetric.data.SharedData.SharedFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every typed Telos table read from the shared data repo ({@code data/Extractor/*.json}, built by the wiki
 * extractor + the hand-written {@code Extractor/manual/} files, see {@link SharedData} / {@code docs/data.md}).
 * One place to find which file feeds which feature; add a new table here. Values are immutable snapshots
 * (re-read with {@code TABLE.get()} — it may be swapped once the start-up update check finds a newer file).
 * Pure Java.
 */
public final class TelosData {

    /** Repo folder of the extractor's tables. */
    static final String DIR = "Extractor/";

    private TelosData() {}

    /** Loads every table now (cache / bundled copy) and starts the one update check per game start. */
    public static void init() {
        // touching the class runs the static declarations below — nothing else to do
    }

    // ------------------------------------------------------------------ boss bars

    /** {@code bossbars.json} — every boss bar seen in game (Boss Recorder): name hash → boss name. */
    public static final SharedFile<BossBars> BOSS_BARS = SharedData.file(DIR + "bossbars.json", BossBars::parse, BossBars.EMPTY);

    /** @param byHash {@code Component#hashCode()} of the bar name (Telos names are glyphs) → boss name */
    public record BossBars(Map<Integer, String> byHash, Set<String> names) {
        static final BossBars EMPTY = new BossBars(Map.of(), Set.of());

        static BossBars parse(JsonObject root) {
            Map<Integer, String> byHash = new HashMap<>();
            Set<String> names = new HashSet<>();
            for (JsonElement e : root.getAsJsonArray("bosses")) {
                JsonObject o = e.getAsJsonObject();
                String name = JsonFields.str(o, "name");
                if (o.has("bossBarHash")) byHash.put(o.get("bossBarHash").getAsInt(), name);
                if (!name.isEmpty()) names.add(Names.key(name));
            }
            return new BossBars(Map.copyOf(byHash), Set.copyOf(names));
        }

        /** True if a boss bar with this name hash / plain letters belongs to a known boss. */
        public boolean isBossBar(int nameHash, String plainName) {
            return byHash.containsKey(nameHash) || (plainName != null && names.contains(Names.key(plainName)));
        }

        /** Boss name for a bar name hash, or null. */
        public String nameFor(int nameHash) {
            String n = byHash.get(nameHash);
            return n == null || n.isEmpty() ? null : n;
        }
    }

    // ------------------------------------------------------------------ bosses + loot

    /** {@code bosses.json} — wiki bosses (+ positions, icons, Boss Tracker data) and every dungeon loot boss. */
    public static final SharedFile<BossTable> BOSSES = SharedData.file(DIR + "bosses.json", BossTable::parse, BossTable.EMPTY);

    /** {@code loottable.json} — Dungeon → Boss → drops (Pity Counter, drop messages). */
    public static final SharedFile<LootTable> LOOT = SharedData.file(DIR + "loottable.json", LootTable::parse, LootTable.EMPTY);

    // ------------------------------------------------------------------ rarities

    /** {@code rarities.json} — rarity colours, keyed by the last part of the tooltip style. */
    public static final SharedFile<Rarities> RARITIES =
            SharedData.file(DIR + "rarities.json", Rarities::parse, Rarities.EMPTY);

    /** @param rgb 0xRRGGBB */
    /** @param glyph chat icon in the server pack's default font ("" = none), from {@code manual/rarities.json} glyphs */
    public record Rarity(String key, String label, int rgb, String glyph) {}

    /** @param companions pet / mount rarities (usual … legacy), by key */
    public record Rarities(Map<String, Rarity> byKey, Map<String, Rarity> companions) {
        static final Rarities EMPTY = new Rarities(Map.of(), Map.of());

        static Rarities parse(JsonObject root) {
            return new Rarities(rarityMap(root.getAsJsonObject("rarities")), rarityMap(root.getAsJsonObject("companions")));
        }

        private static Map<String, Rarity> rarityMap(JsonObject o) {
            Map<String, Rarity> map = new LinkedHashMap<>();
            if (o == null) return Map.of();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                JsonObject r = e.getValue().getAsJsonObject();
                String key = e.getKey().toLowerCase(Locale.ROOT);
                map.put(key, new Rarity(key, JsonFields.str(r, "label"), JsonFields.rgb(JsonFields.str(r, "color")),
                        JsonFields.str(r, "glyph")));
            }
            return Collections.unmodifiableMap(map);
        }

        /** Rarity for a tooltip-style path ({@code untiered/bloodshot}, {@code tiered/8}), or null. */
        public Rarity forStyle(String stylePath) {
            if (stylePath == null || stylePath.isEmpty()) return null;
            return byKey.get(stylePath.substring(stylePath.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
        }

        /** Any rarity key (item or companion) → its rarity, or null. */
        public Rarity any(String key) {
            if (key == null) return null;
            String k = key.toLowerCase(Locale.ROOT);
            Rarity r = byKey.get(k);
            return r != null ? r : companions.get(k);
        }

        public List<String> labels() {
            List<String> out = new ArrayList<>(byKey.size());
            for (Rarity r : byKey.values()) out.add(r.label());
            return out;
        }
    }

    // ------------------------------------------------------------------ loot bags

    /** {@code loot_info.json} (FAQ → Loot Information) — loot key → pouch glyph (default font, server pack). */
    public static final SharedFile<Map<String, String>> LOOT_INFO = SharedData.file(DIR + "loot_info.json",
            TelosData::parseLootInfo, Map.of());

    static Map<String, String> parseLootInfo(JsonObject root) {
        if (!root.has("loot")) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (JsonElement e : root.getAsJsonArray("loot")) {
            if (!e.isJsonObject()) continue;
            String key = JsonFields.str(e.getAsJsonObject(), "key").toLowerCase(Locale.ROOT);
            String glyph = JsonFields.str(e.getAsJsonObject(), "glyph");
            if (!key.isEmpty() && !glyph.isEmpty()) out.put(key, glyph);
        }
        return Map.copyOf(out);
    }

    /** {@code bags.json} — loot bag types (drop totem model, drop sound) for Custom Bags and Lifetime Stats. */
    public static final SharedFile<Bags> BAGS = SharedData.file(DIR + "bags.json", Bags::parse, Bags.EMPTY);

    /**
     * @param key   file name of the custom image / sound
     * @param totem item model path of the drop animation ({@code telos:<totem>})
     * @param sound suffix of the drop sound ({@code noise:player.bags.create_<sound>})
     * @param model custom model with the image screen ({@code telemetric:bags/<model>})
     * @param group Lifetime Stats row this bag counts in (the key unless several bags share one, e.g. {@code event})
     * @param rgb   bag colour 0xRRGGBB, or -1
     * @param icon  pouch glyph in the server pack's default font ("" = none): bags Loot Information has no icon for
     *              (shiny, event bags); the others use {@link #LOOT_INFO} by key
     */
    public record Bag(String key, String label, String totem, String sound, String model, String group, int rgb,
                      String icon) {}

    public record Bags(List<Bag> list, Map<String, Bag> byTotem, Map<String, Bag> bySound, List<String> groups) {
        static final Bags EMPTY = new Bags(List.of(), Map.of(), Map.of(), List.of());

        static Bags parse(JsonObject root) {
            List<Bag> list = new ArrayList<>();
            Map<String, Bag> byTotem = new HashMap<>(), bySound = new HashMap<>();
            Set<String> groups = new java.util.LinkedHashSet<>();
            for (JsonElement e : root.getAsJsonArray("bags")) {
                JsonObject o = e.getAsJsonObject();
                String key = JsonFields.str(o, "key").toLowerCase(Locale.ROOT);
                if (key.isEmpty() || !key.matches("[a-z0-9_]+")) continue; // used as a file name
                String group = JsonFields.str(o, "group").toLowerCase(Locale.ROOT);
                if (group.isEmpty()) group = key;
                String color = JsonFields.str(o, "color");
                String icon = JsonFields.str(o, "icon");
                Bag b = new Bag(key, JsonFields.str(o, "label"), JsonFields.str(o, "totem"), JsonFields.str(o, "sound"),
                        JsonFields.str(o, "model"), group, color.isEmpty() ? -1 : JsonFields.rgb(color), icon);
                list.add(b);
                byTotem.put(b.totem(), b);
                bySound.put(b.sound(), b);
                groups.add(group);
            }
            return new Bags(List.copyOf(list), Map.copyOf(byTotem), Map.copyOf(bySound), List.copyOf(groups));
        }

        public Bag byLabel(String label) {
            for (Bag b : list) if (b.label().equalsIgnoreCase(label)) return b;
            return null;
        }

        public List<String> labels() {
            List<String> out = new ArrayList<>(list.size());
            for (Bag b : list) out.add(b.label());
            return out;
        }
    }
}
