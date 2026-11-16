package dev.telemetric.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.telemetric.data.SharedData.SharedFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    /**
     * @param rgb   0xRRGGBB
     * @param glyph chat icon in the server pack's default font ("" = none), from {@code manual/rarities.json} glyphs
     */
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

    // ------------------------------------------------------------------ traits

    /** {@code traits.json} — unique trait descriptions per tier (Trait Details). */
    public static final SharedFile<TraitTable> TRAITS = SharedData.file(DIR + "traits.json", TraitTable::parse, TraitTable.EMPTY);

    // ------------------------------------------------------------------ hand-written tables (Extractor/manual/)
    // Read straight from manual/: no extractor step merges them into a generated file.

    static final String MANUAL = DIR + "manual/";

    /** {@code manual/callouts.json} — boss / portal call-out data (Callouts). */
    public static final SharedFile<CalloutTable> CALLOUTS = SharedData.file(MANUAL + "callouts.json", CalloutTable::parse, CalloutTable.EMPTY);

    /** {@code manual/chat.json} — chat tab categories + utility lines (Chat). */
    public static final SharedFile<ChatRules> CHAT = SharedData.file(MANUAL + "chat.json", ChatRules::parse, ChatRules.EMPTY);

    /**
     * @param categories message categories, first match wins ({@code builtin} = a code check such as {@code callout})
     * @param tabs       chat tabs in bar order (after "All")
     * @param utility    whole-message regexes of the server's utility lines
     */
    public record ChatRules(List<Category> categories, List<Tab> tabs, List<String> utility) {
        static final ChatRules EMPTY = new ChatRules(List.of(), List.of(), List.of());

        public record Category(String key, List<Pattern> patterns, String builtin) {}

        /** @param channel server chat mode this tab switches to ({@code default} / {@code guild} / …), or "" */
        public record Tab(String name, String category, Set<String> also, String channel, boolean shown) {
            public boolean shows(String messageCategory) {
                return category.equals(messageCategory) || also.contains(messageCategory);
            }
        }

        static ChatRules parse(JsonObject root) {
            List<Category> categories = new ArrayList<>();
            for (JsonObject c : JsonFields.objects(root, "categories")) {
                List<Pattern> patterns = new ArrayList<>();
                for (String p : JsonFields.strings(c, "patterns")) patterns.add(Pattern.compile(p));
                categories.add(new Category(JsonFields.str(c, "key"), List.copyOf(patterns), JsonFields.str(c, "builtin")));
            }
            List<Tab> tabs = new ArrayList<>();
            for (JsonObject t : JsonFields.objects(root, "tabs")) {
                String name = JsonFields.str(t, "name");
                if (name.isEmpty()) continue;
                tabs.add(new Tab(name, JsonFields.str(t, "category"), Set.copyOf(JsonFields.strings(t, "also")),
                        JsonFields.str(t, "channel"), JsonFields.bool(t, "shown", true)));
            }
            return new ChatRules(List.copyOf(categories), List.copyOf(tabs), JsonFields.strings(root, "utility"));
        }

        public Tab tab(String name) {
            for (Tab t : tabs) if (t.name().equalsIgnoreCase(name)) return t;
            return null;
        }

        public List<String> tabNames() {
            List<String> out = new ArrayList<>(tabs.size());
            for (Tab t : tabs) out.add(t.name());
            return out;
        }

        public Set<String> defaultShown() {
            Set<String> out = new LinkedHashSet<>();
            for (Tab t : tabs) if (t.shown()) out.add(t.name());
            return out;
        }
    }

    /** {@code manual/realms.json} — realm / hub names per region (Keybinds → Realm Selector). */
    public static final SharedFile<Realms> REALMS = SharedData.file(MANUAL + "realms.json", Realms::parse, Realms.EMPTY);

    public record Realms(List<Region> regions) {
        static final Realms EMPTY = new Realms(List.of());

        /** @param prefix start of the Tab "Server" region part that picks this region ("" = any) */
        public record Region(String name, String prefix, List<String> realms, List<String> hubs) {}

        static Realms parse(JsonObject root) {
            List<Region> regions = new ArrayList<>();
            for (JsonObject r : JsonFields.objects(root, "regions")) {
                regions.add(new Region(JsonFields.str(r, "name"), JsonFields.str(r, "prefix").toLowerCase(Locale.ROOT),
                        JsonFields.strings(r, "realms"), JsonFields.strings(r, "hubs")));
            }
            return new Realms(List.copyOf(regions));
        }

        /** True if {@code name} is a realm or hub of any region (case-insensitive). */
        public boolean isKnown(String name) {
            for (Region g : regions) {
                for (String r : g.realms()) if (r.equalsIgnoreCase(name)) return true;
                for (String h : g.hubs()) if (h.equalsIgnoreCase(name)) return true;
            }
            return false;
        }

        /** The region for the Tab "Server" region part ({@code null} / unknown → null). */
        public Region forServerRegion(String region) {
            if (region == null || region.isBlank()) return null;
            String r = region.trim().toLowerCase(Locale.ROOT);
            for (Region g : regions) if (r.startsWith(g.prefix())) return g;
            return null;
        }
    }

    /** {@code manual/discord.json} — Discord Rich Presence application, art keys and texts (Discord RPC). */
    public static final SharedFile<DiscordInfo> DISCORD = SharedData.file(MANUAL + "discord.json", DiscordInfo::parse, DiscordInfo.EMPTY);

    /**
     * @param modeImages / modeNames character mode ({@code normal} / {@code seasonal} / {@code hardcore}) → art key / name
     * @param texts      named texts ({@code connecting}, {@code fighting}, … with {@code {placeholders}})
     * @param areaTexts  area name → second line while idle there
     */
    public record DiscordInfo(String applicationId, String largeImage, String smallImage,
                              Map<String, String> modeImages, Map<String, String> modeNames,
                              Map<String, String> texts, List<Button> buttons, Map<String, String> areaTexts) {
        static final DiscordInfo EMPTY = new DiscordInfo("", "", "", Map.of(), Map.of(), Map.of(), List.of(), Map.of());

        public record Button(String label, String url) {}

        static DiscordInfo parse(JsonObject root) {
            List<Button> buttons = new ArrayList<>();
            for (JsonObject b : JsonFields.objects(root, "buttons")) {
                String label = JsonFields.str(b, "label"), url = JsonFields.str(b, "url");
                if (!label.isEmpty() && url.startsWith("https://") && buttons.size() < 2) buttons.add(new Button(label, url));
            }
            return new DiscordInfo(JsonFields.str(root, "applicationId"), JsonFields.str(root, "largeImage"),
                    JsonFields.str(root, "smallImage"), JsonFields.stringMap(root, "modeImages"),
                    JsonFields.stringMap(root, "modeNames"), JsonFields.stringMap(root, "texts"), List.copyOf(buttons),
                    JsonFields.stringMap(root, "areaTexts"));
        }

        /** Text {@code key} with {@code {name}} placeholders filled from {@code vars} (name, value, …); "" when missing. */
        public String text(String key, String... vars) {
            String t = texts.getOrDefault(key, "");
            for (int i = 0; i + 1 < vars.length; i += 2) t = t.replace("{" + vars[i] + "}", vars[i + 1]);
            return t;
        }
    }
}
