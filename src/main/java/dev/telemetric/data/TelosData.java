package dev.telemetric.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.telemetric.data.SharedData.SharedFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every typed Telos table read from the shared data repo ({@code data/telos/*.json}, see {@link SharedData}).
 * One place to find which file feeds which feature; add a new table here. Values are immutable snapshots
 * (re-read with {@code TABLE.get()} — it may be swapped once the download finishes). Pure Java.
 */
public final class TelosData {

    private TelosData() {}

    // ------------------------------------------------------------------ bosses

    /** {@code telos/bosses.json} — world and dungeon bosses. */
    public static final SharedFile<Bosses> BOSSES = SharedData.file("telos/bosses.json", Bosses::parse, Bosses.EMPTY);

    /** @param bossBarHash {@code Component#hashCode()} of the boss's boss-bar name (Telos names are glyphs), or null */
    public record Boss(String id, String label, String type, Integer bossBarHash) {}

    public record Bosses(List<Boss> list, Set<Integer> barHashes, Set<String> labels) {
        static final Bosses EMPTY = new Bosses(List.of(), Set.of(), Set.of());

        static Bosses parse(JsonObject root) {
            List<Boss> list = new ArrayList<>();
            Set<Integer> hashes = new HashSet<>();
            Set<String> labels = new HashSet<>();
            for (JsonElement e : root.getAsJsonArray("bosses")) {
                JsonObject o = e.getAsJsonObject();
                Integer hash = o.has("bossBarHash") ? o.get("bossBarHash").getAsInt() : null;
                Boss b = new Boss(str(o, "name"), str(o, "label"), str(o, "bossType"), hash);
                list.add(b);
                if (hash != null) hashes.add(hash);
                if (!b.label().isEmpty()) labels.add(squash(b.label()));
            }
            return new Bosses(List.copyOf(list), Set.copyOf(hashes), Set.copyOf(labels));
        }

        /** True if a boss bar with this name hash / plain letters belongs to a known boss. */
        public boolean isBossBar(int nameHash, String plainName) {
            return barHashes.contains(nameHash) || (plainName != null && labels.contains(squash(plainName)));
        }
    }

    // ------------------------------------------------------------------ rarities

    /** {@code telos/rarities.json} — rarity colours, keyed by the last part of the tooltip style. */
    public static final SharedFile<Rarities> RARITIES =
            SharedData.file("telos/rarities.json", Rarities::parse, Rarities.EMPTY);

    /** @param rgb 0xRRGGBB */
    public record Rarity(String key, String label, int rgb) {}

    public record Rarities(Map<String, Rarity> byKey) {
        static final Rarities EMPTY = new Rarities(Map.of());

        static Rarities parse(JsonObject root) {
            Map<String, Rarity> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("rarities").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                String key = e.getKey().toLowerCase(Locale.ROOT);
                map.put(key, new Rarity(key, str(o, "label"), parseRgb(str(o, "color"))));
            }
            return new Rarities(java.util.Collections.unmodifiableMap(map));
        }

        /** Rarity for a tooltip-style path ({@code untiered/bloodshot}, {@code tiered/8}), or null. */
        public Rarity forStyle(String stylePath) {
            if (stylePath == null || stylePath.isEmpty()) return null;
            return byKey.get(stylePath.substring(stylePath.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
        }

        public List<String> labels() {
            List<String> out = new ArrayList<>(byKey.size());
            for (Rarity r : byKey.values()) out.add(r.label());
            return out;
        }
    }

    // ------------------------------------------------------------------ loot bags

    /** {@code telos/bags.json} — loot bag types (drop totem model, drop sound) for Custom Bags. */
    public static final SharedFile<Bags> BAGS = SharedData.file("telos/bags.json", Bags::parse, Bags.EMPTY);

    /**
     * @param key   file name of the custom image / sound
     * @param totem item model path of the drop animation ({@code telos:<totem>})
     * @param sound suffix of the drop sound ({@code noise:player.bags.create_<sound>})
     * @param model custom model with the image screen ({@code telemetric:bags/<model>})
     */
    public record Bag(String key, String label, String totem, String sound, String model) {}

    public record Bags(List<Bag> list, Map<String, Bag> byTotem, Map<String, Bag> bySound) {
        static final Bags EMPTY = new Bags(List.of(), Map.of(), Map.of());

        static Bags parse(JsonObject root) {
            List<Bag> list = new ArrayList<>();
            Map<String, Bag> byTotem = new HashMap<>(), bySound = new HashMap<>();
            for (JsonElement e : root.getAsJsonArray("bags")) {
                JsonObject o = e.getAsJsonObject();
                Bag b = new Bag(str(o, "key").toLowerCase(Locale.ROOT), str(o, "label"), str(o, "totem"),
                        str(o, "sound"), str(o, "model"));
                if (b.key().isEmpty() || !b.key().matches("[a-z0-9_]+")) continue; // used as a file name
                list.add(b);
                byTotem.put(b.totem(), b);
                bySound.put(b.sound(), b);
            }
            return new Bags(List.copyOf(list), Map.copyOf(byTotem), Map.copyOf(bySound));
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

    // ------------------------------------------------------------------ helpers

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static int parseRgb(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return (int) (Long.parseLong(h, 16) & 0xFFFFFF);
    }

    /** Lower-case letters and digits only ("Golden Freddy" → "goldenfreddy"). */
    static String squash(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.toString();
    }
}
