package dev.telemetric.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Telos profile answers ({@code /telos/profile}, {@code /telos/character}) as plain records, parsed leniently:
 * missing fields are 0 / empty / null, unknown ones are ignored. Field names are Telos's own (see
 * {@code API Server/docs/mod-integration.md}); times are Unix ms, durations ms.
 */
public final class ProfileData {

    private ProfileData() {}

    /** An item slot: Telos key ({@code weapon/bow/ut-doom}, no namespace), its trait keys and an applied skin. */
    public record Item(String key, String uuid, List<String> traits, String skinId) {}

    /** The server-resolved form of an item: definition name/components plus Telos's own lore lines. */
    public record Expanded(String name, JsonObject components, List<LoreLine> lore) {}

    /** One lore line: a JSON text component, or a divider bar ({@code json == null}); {@code wrap} = may be wrapped. */
    public record LoreLine(String json, boolean wrap) {}

    /**
     * @param skillTree {@code skillTreeSelection}: one list per path (A, B; empty = path not used), each with one pick
     *                  per tier from the bottom up (null = tier not picked). Tier sizes 1, 2, 2, 3, 2, 3; a pick is the
     *                  column in a 3-wide grid, so 2-node tiers use 0 and 2 (checked against the in-game tree).
     */
    public record ClassInfo(String type, long soulPoints, int transcendence, long maxFame, long totalFame,
                            long playTime, int deaths, List<List<Integer>> skillTree) {}

    public record Companions(String pet, String petSkin, String mount, String mountSkin, List<String> unlocked) {}

    public record StashPage(int page, boolean seasonal, List<Item> items) {}

    public record CharacterSummary(String id, String type, String ruleset, String group, long fame, long playTime,
                                   long created, long deathTime, Map<String, Item> equipped) {}

    public record Player(String id, String username, long lastPlayed, long playTime, double glory, double seasonalGlory,
                         String sticker, Companions companions, String activeCharacter, int characterSlots,
                         boolean premiumPass, double passXp, int passQuests, List<StashPage> stash,
                         List<ClassInfo> classes, Map<String, Expanded> expanded) {

        public long totalFame() {
            long s = 0;
            for (ClassInfo c : classes) s += c.totalFame();
            return s;
        }

        public int deaths() {
            int s = 0;
            for (ClassInfo c : classes) s += c.deaths();
            return s;
        }

        public long soulPoints() {
            long s = 0;
            for (ClassInfo c : classes) s += c.soulPoints();
            return s;
        }

        public int stashItems() {
            int n = 0;
            for (StashPage p : stash) for (Item i : p.items()) if (i != null) n++;
            return n;
        }
    }

    public record Profile(Player player, List<CharacterSummary> characters, long fetchedAt, boolean stale) {}

    public record CharacterDetail(String id, ClassInfo type, String ruleset, String group, long fame, long playTime,
                                  long created, long lastPlayed, int potions, Map<String, Double> fragments,
                                  List<Item> inventory, int hotBarSlot, List<Item> backpack,
                                  Map<String, Expanded> expanded, long fetchedAt) {}

    // ---------------------------------------------------------------- parsing

    public static Profile profile(JsonObject body, long fetchedAt, boolean stale) {
        JsonObject p = obj(body, "player");
        List<CharacterSummary> chars = new ArrayList<>();
        for (JsonObject c : objects(body, "characters")) chars.add(summary(c));
        return new Profile(player(p == null ? new JsonObject() : p), chars, fetchedAt, stale);
    }

    private static Player player(JsonObject p) {
        JsonObject comp = obj(p, "companions");
        Companions companions = comp == null ? new Companions(null, null, null, null, List.of())
                : new Companions(strOrNull(comp, "pet"), strOrNull(comp, "petSkin"), strOrNull(comp, "mount"),
                strOrNull(comp, "mountSkin"), strings(comp, "unlocked"));
        JsonObject pass = obj(p, "seasonPass");
        List<StashPage> stash = new ArrayList<>();
        for (JsonObject s : objects(p, "stash")) stash.add(new StashPage(integer(s, "page"), bool(s, "seasonal"), items(arr(s, "items"))));
        List<ClassInfo> classes = new ArrayList<>();
        for (JsonObject c : objects(p, "classes")) {
            ClassInfo ci = classInfo(c);
            if (!"DUMMY".equals(ci.type())) classes.add(ci);
        }
        return new Player(str(p, "id"), str(p, "username"), lng(p, "lastPlayed"), lng(p, "playTime"),
                dbl(p, "normalBalance"), dbl(p, "seasonalBalance"), strOrNull(p, "sticker"), companions,
                strOrNull(p, "character"), integer(p, "characterSlots"),
                pass != null && bool(pass, "premium"), pass == null ? 0 : dbl(pass, "experience"),
                pass == null ? 0 : integer(pass, "quests"), stash, classes, expanded(arr(p, "expandedInventory")));
    }

    private static CharacterSummary summary(JsonObject c) {
        Map<String, Item> eq = new LinkedHashMap<>();
        JsonObject inv = obj(c, "inventory");
        if (inv != null) {
            for (Map.Entry<String, JsonElement> e : inv.entrySet()) {
                Item it = item(e.getValue());
                if (it != null) eq.put(e.getKey(), it);
            }
        }
        return new CharacterSummary(str(c, "id"), str(c, "type"), str(c, "ruleset"), strOrNull(c, "group"),
                lng(c, "fame"), lng(c, "playTime"), lng(c, "created"), lng(c, "deathTime"), eq);
    }

    public static CharacterDetail character(JsonObject body, long fetchedAt) {
        JsonObject c = obj(body, "character");
        if (c == null) c = new JsonObject();
        JsonObject type = obj(c, "type");
        ClassInfo ci = type != null ? classInfo(type)
                : new ClassInfo(str(c, "type"), 0, 0, 0, 0, 0, 0, List.of());
        Map<String, Double> frags = new LinkedHashMap<>();
        JsonObject f = obj(c, "fragments");
        if (f != null) for (Map.Entry<String, JsonElement> e : f.entrySet()) {
            if (e.getValue().isJsonPrimitive()) frags.put(e.getKey(), e.getValue().getAsDouble());
        }
        Map<String, Expanded> ex = new HashMap<>(expanded(arr(c, "expandedInventory")));
        ex.putAll(expanded(arr(c, "expandedBackpack")));
        ex.putAll(expanded(arr(c, "expandedHotBar")));
        return new CharacterDetail(str(c, "id"), ci, str(c, "ruleset"), strOrNull(c, "group"), lng(c, "fame"),
                lng(c, "playTime"), lng(c, "created"), lng(c, "lastPlayed"), integer(c, "potions"), frags,
                items(arr(c, "inventory")), integer(c, "hotBarSlot"), items(arr(c, "backpack")), ex, fetchedAt);
    }

    private static ClassInfo classInfo(JsonObject c) {
        List<List<Integer>> tree = new ArrayList<>();
        JsonArray sel = arr(c, "skillTreeSelection");
        if (sel != null) {
            for (JsonElement row : sel) {
                List<Integer> picks = new ArrayList<>();
                boolean any = false;
                if (row != null && row.isJsonArray()) {
                    for (JsonElement v : row.getAsJsonArray()) {
                        Integer pick = v != null && v.isJsonPrimitive() ? v.getAsInt() : null;
                        any |= pick != null;
                        picks.add(pick);
                    }
                }
                tree.add(any ? Collections.unmodifiableList(picks) : List.of()); // keeps the path index; empty = unused
            }
        }
        return new ClassInfo(str(c, "type"), lng(c, "soulPoints"), integer(c, "transcendenceLevel"), lng(c, "maxFame"),
                lng(c, "totalFame"), lng(c, "playTime"), integer(c, "deaths"), tree);
    }

    private static List<Item> items(JsonArray a) {
        if (a == null) return List.of();
        List<Item> out = new ArrayList<>(a.size());
        for (JsonElement e : a) out.add(item(e));
        return Collections.unmodifiableList(out);
    }

    private static Item item(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        String key = stripNs(str(o, "key"));
        if (key.isEmpty()) return null;
        List<String> traits = List.of();
        String skin = null;
        for (JsonObject f : objects(o, "features")) {
            String type = str(f, "type");
            if ("traits".equals(type)) traits = strings(f, "traits");
            else if ("skin".equals(type)) skin = stripNs(str(f, "skinId"));
        }
        return new Item(key, str(o, "uuid"), traits, skin == null || skin.isEmpty() ? null : skin);
    }

    /** Expanded entries keyed by item uuid (they come in slot order, but the uuid is the safer link). */
    private static Map<String, Expanded> expanded(JsonArray a) {
        if (a == null) return Map.of();
        Map<String, Expanded> out = new HashMap<>();
        for (JsonElement e : a) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject x = e.getAsJsonObject();
            JsonObject it = obj(x, "item");
            JsonObject def = obj(x, "definition");
            if (it == null || def == null) continue;
            List<LoreLine> lore = new ArrayList<>();
            for (JsonObject l : objects(x, "lore")) {
                String type = str(l, "type");
                if ("bar".equals(type)) lore.add(new LoreLine(null, false));
                else if (l.has("component")) lore.add(new LoreLine(str(l, "component"), "wrappable".equals(type)));
            }
            JsonObject comps = obj(def, "components");
            out.put(str(it, "uuid"), new Expanded(str(def, "name"), comps == null ? new JsonObject() : comps, lore));
        }
        return out;
    }

    // ---------------------------------------------------------------- lenient JSON helpers

    public static String stripNs(String key) {
        int i = key.indexOf(':');
        return i >= 0 ? key.substring(i + 1) : key;
    }

    static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    static List<JsonObject> objects(JsonObject o, String k) {
        JsonArray a = arr(o, k);
        if (a == null) return List.of();
        List<JsonObject> out = new ArrayList<>(a.size());
        for (JsonElement e : a) if (e != null && e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    static List<String> strings(JsonObject o, String k) {
        JsonArray a = arr(o, k);
        if (a == null) return List.of();
        List<String> out = new ArrayList<>(a.size());
        for (JsonElement e : a) if (e != null && e.isJsonPrimitive()) out.add(stripNs(e.getAsString()));
        return Collections.unmodifiableList(out);
    }

    static String str(JsonObject o, String k) {
        String s = strOrNull(o, k);
        return s == null ? "" : s;
    }

    static String strOrNull(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static long lng(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
        } catch (NumberFormatException ex) {
            return (long) dbl(o, k);
        }
    }

    static int integer(JsonObject o, String k) {
        return (int) lng(o, k);
    }

    static double dbl(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static boolean bool(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /** {@code NECROMANCER} → {@code Necromancer}, {@code mount/celestial_stag} → {@code Celestial Stag}. */
    public static String pretty(String id) {
        if (id == null || id.isEmpty()) return "";
        String s = id.substring(id.lastIndexOf('/') + 1);
        s = s.substring(s.lastIndexOf(':') + 1);
        StringBuilder sb = new StringBuilder(s.length());
        boolean up = true;
        for (char c : s.toCharArray()) {
            if (c == '_' || c == '-') {
                sb.append(' ');
                up = true;
                continue;
            }
            sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
            up = false;
        }
        return sb.toString();
    }

    /** The mode label of a character: Group Ironman, Ironman, Seasonal or Normal. */
    public static String mode(String ruleset, String group) {
        String r = ruleset == null ? "" : ruleset.toUpperCase(Locale.ROOT);
        if (r.equals("GROUP_IRONMAN") || (r.equals("IRONMAN") && group != null)) return "Group Ironman";
        return switch (r) {
            case "IRONMAN" -> "Ironman";
            case "SEASONAL" -> "Seasonal";
            default -> "Normal";
        };
    }

    /** A character's level from its fame ({@code 1 + √(fame / 37.5)}, the curve Melinoe shows; unverified). */
    public static int level(long fame) {
        return (int) Math.floor(1 + Math.sqrt(Math.max(0, fame) / 37.5));
    }
}
