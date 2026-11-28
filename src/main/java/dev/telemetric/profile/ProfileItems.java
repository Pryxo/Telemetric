package dev.telemetric.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.GameItem;
import dev.telemetric.game.ProfileItemAdapter;
import dev.telemetric.game.ProfileItemAdapter.Line;
import dev.telemetric.game.ServerGate;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.module.impl.hud.TelosIcons;
import dev.telemetric.profile.ProfileData.Expanded;
import dev.telemetric.profile.ProfileData.Item;
import dev.telemetric.profile.ProfileData.LoreLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns profile items into something drawable: the Telos item model, a rarity colour and a tooltip that looks like the
 * in-game one. Items the server already resolved ({@link Expanded}) use Telos's own lore lines; the rest (equipment in
 * the character list, companions) get lore built from the item / trait definitions the same way.
 * <p>
 * Pure data code: the {@link GameItem} itself is built by {@link ProfileItemAdapter}.
 */
public final class ProfileItems {

    /**
     * Wrappable lore lines (ability text) are only split at line breaks here; {@link ProfileItemAdapter} wraps them to
     * the tooltip's pixel width (the widest other line), like Telos does in game. Off Telos they are cut at this many
     * characters instead.
     */
    private static final int WRAP_CHARS = 44;

    private static volatile JsonObject itemDefs = new JsonObject();
    private static volatile JsonObject traitDefs = new JsonObject();
    private static final Map<String, View> CACHE = new ConcurrentHashMap<>();

    private ProfileItems() {}

    /**
     * A drawable item.
     *
     * @param rgb   rarity colour 0xRRGGBB (0 = none)
     * @param model item model id, e.g. {@code telos:material/weapon/bow/ut-doom}
     */
    public record View(String key, String name, int rgb, String model, GameItem item) {}

    /** Installs the definitions once loaded (either may be empty); drops views built without them. */
    public static void setDefinitions(JsonObject items, JsonObject traits) {
        if (items != null) itemDefs = items;
        if (traits != null) traitDefs = traits;
        CACHE.clear();
    }

    public static boolean hasDefinitions() {
        return itemDefs.size() > 0;
    }

    /** The view of an item, using the server-resolved {@code expanded} entry when there is one. */
    public static View view(Item item, Map<String, Expanded> expanded) {
        if (item == null) return null;
        Expanded ex = expanded == null ? null : expanded.get(item.uuid());
        String cacheKey = item.uuid() + "|" + item.key() + "|" + (ex != null);
        return CACHE.computeIfAbsent(cacheKey.isEmpty() ? item.key() : cacheKey, k -> build(item, ex));
    }

    /** A view of a plain definition key (companions, stickers): name, model and generated tooltip. */
    public static View ofKey(String key) {
        if (key == null || key.isEmpty()) return null;
        return view(new Item(ProfileData.stripNs(key), "key:" + key, List.of(), null), null);
    }

    /** The definition name of a key, or a prettified key. */
    public static String name(String key) {
        JsonObject def = ProfileData.obj(itemDefs, ProfileData.stripNs(key));
        String n = def == null ? "" : ProfileData.str(def, "name");
        return n.isEmpty() ? ProfileData.pretty(key) : n;
    }

    /** True if the item definitions know this key. */
    public static boolean known(String key) {
        return itemDefs.has(ProfileData.stripNs(key));
    }

    /** True for a real pet / mount (its definition has a {@code companion_amulet}); skins have none. */
    public static boolean isCompanion(String key) {
        JsonObject def = ProfileData.obj(itemDefs, ProfileData.stripNs(key));
        return def != null && ProfileData.obj(ProfileData.obj(def, "components"), "companion_amulet") != null;
    }

    /** All definition keys starting with {@code prefix} ({@code pet/}, {@code mount/}). */
    public static List<String> keysWithPrefix(String prefix) {
        List<String> out = new ArrayList<>();
        for (String k : itemDefs.keySet()) if (k.startsWith(prefix)) out.add(k);
        return out;
    }

    private static View build(Item item, Expanded ex) {
        JsonObject def = ProfileData.obj(itemDefs, item.key());
        JsonObject comps = ex != null ? ex.components() : def == null ? new JsonObject() : ProfileData.obj(def, "components");
        if (comps == null) comps = new JsonObject();
        String name = ex != null && !ex.name().isEmpty() ? ex.name() : def != null ? ProfileData.str(def, "name") : "";
        if (name.isEmpty()) name = ProfileData.pretty(item.key());

        JsonObject style = ProfileData.obj(comps, "style");
        String nameColor = color(style, "boldStyle");
        String lineColor = color(style, "lineStyle");

        String tierKind = tierKind(comps);
        boolean shiny = comps.has("shiny");
        TelosData.Rarity rarity = TelosData.RARITIES.get().any(shiny ? "shiny" : tierKind);
        int rgb = rarity != null ? rarity.rgb() : hex(nameColor);
        // the tooltip sprites live in the server pack: without it they would draw as the missing texture
        String tooltipStyle = !ServerGate.onTelos() ? null : shiny ? "telos:shiny"
                : tierKind != null && TelosData.RARITIES.get().byKey().containsKey(tierKind) ? "telos:untiered/" + tierKind
                : packStyle(style);

        boolean telos = ServerGate.onTelos();
        List<Line> lore = new ArrayList<>(ex != null && !ex.lore().isEmpty() ? fromServer(ex.lore(), telos) : generated(item, comps, telos));
        lore.replaceAll(l -> new Line(resolve(statIcons(l.json())), l.wrap()));
        String model = model(item, comps);
        GameItem stack = ProfileItemAdapter.build(model, title(name, nameColor, telos), lore, lineColor, tooltipStyle);
        return new View(item.key(), name, rgb, model, stack);
    }

    /**
     * The item name as Telos sends it in game: {@code [event horizon]} in lower case in its {@code telos:text} font
     * without shadow (the adapter centres it with spaces). Off Telos that font is missing, so the plain name.
     */
    private static String title(String name, String color, boolean telos) {
        JsonObject run = new JsonObject();
        run.addProperty("text", telos ? titleTiles("[" + name.toLowerCase(Locale.ROOT) + "]") : name);
        if (color != null) run.addProperty("color", color);
        if (telos) {
            run.addProperty("font", "telos:text");
            run.addProperty("shadow_color", 0);
        }
        JsonArray parts = new JsonArray();
        parts.add(run);
        return line(parts);
    }

    /**
     * The definition's own tooltip sprite set ({@code style.tooltipStyle}, e.g. {@code background} for skin tokens) as
     * {@code telos:<name>}, or null. Only sets the pack is known to have, so nothing draws as the missing texture.
     */
    private static String packStyle(JsonObject style) {
        String s = style == null ? "" : ProfileData.str(style, "tooltipStyle");
        return PACK_STYLES.contains(s) ? "telos:" + s : null;
    }

    private static final java.util.Set<String> PACK_STYLES = java.util.Set.of("background", "shiny");

    /**
     * The {@code telos:text} title letters are solid banner tiles, and every bitmap glyph advances 1 px past its width,
     * which leaves a 1 px gap between tiles. A {@code U+F801} (-1 px space, {@code telos:spaces}) between letters
     * closes it.
     */
    private static String titleTiles(String s) {
        StringBuilder b = new StringBuilder(s.length() * 2);
        s.codePoints().forEach(cp -> {
            if (!b.isEmpty()) b.append(NEG_1);
            b.appendCodePoint(cp);
        });
        return b.toString();
    }

    private static final char NEG_1 = (char) 0xF801;

    private static String model(Item item, JsonObject comps) {
        if (item.skinId() != null) {
            JsonObject skinDef = ProfileData.obj(itemDefs, item.skinId());
            String m = skinModel(skinDef == null ? null : ProfileData.obj(skinDef, "components"));
            if (m != null) return m;
        }
        // a skin token's own skin_token.itemModel is the look it gives a weapon; the token itself (as in the
        // inventory) uses its key model, telos:material/skin/<name>
        String own = item.key().startsWith("skin/") ? null : skinModel(comps);
        return own != null ? own : "telos:material/" + item.key();
    }

    private static String skinModel(JsonObject comps) {
        JsonObject token = ProfileData.obj(comps, "skin_token");
        String m = token == null ? "" : ProfileData.str(token, "itemModel");
        return m.isEmpty() ? null : m.contains(":") ? m : "telos:" + m;
    }

    /** {@code gilded}, {@code royal}, … for untiered items, else null. */
    private static String tierKind(JsonObject comps) {
        JsonObject tier = ProfileData.obj(comps, "tier");
        JsonObject type = tier == null ? null : ProfileData.obj(tier, "tierType");
        String kind = type == null ? "" : ProfileData.str(type, "kind");
        return kind.isEmpty() ? null : kind.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- lore

    /** Server lore as lines ({@code null} json = divider bar); ability lines are marked for wrapping. */
    private static List<Line> fromServer(List<LoreLine> lines, boolean telos) {
        List<Line> out = new ArrayList<>(lines.size());
        for (LoreLine l : lines) {
            if (l.json() == null) out.add(Line.BAR);
            else if (l.wrap()) wrapInto(out, l.json(), telos);
            else out.add(new Line(l.json(), false));
        }
        return out;
    }

    /** Adds a wrappable line: split at line breaks (pixel-wrapped later) on Telos, at {@link #WRAP_CHARS} elsewhere. */
    private static void wrapInto(List<Line> out, String json, boolean telos) {
        for (String part : wrap(json, telos ? Integer.MAX_VALUE : WRAP_CHARS)) out.add(part == null ? Line.BAR : new Line(part, true));
    }

    /** Lore from the definitions, in Telos's order: modifiers, traits, description, class + tier points. */
    private static List<Line> generated(Item item, JsonObject comps, boolean telos) {
        List<Line> out = new ArrayList<>();
        // armour: its stats, a divider, then the ability modifiers (like in game)
        JsonObject stats = ProfileData.obj(ProfileData.obj(comps, "stats"), "stats");
        if (stats != null) {
            for (Map.Entry<String, JsonElement> e : stats.entrySet()) {
                double v = e.getValue().isJsonPrimitive() ? e.getValue().getAsDouble() : 0;
                if (v != 0) out.add(new Line(statLine(e.getKey(), v), false));
            }
        }
        List<JsonObject> modifiers = ProfileData.objects(ProfileData.obj(comps, "modifiers"), "modifiers");
        if (!modifiers.isEmpty() && !out.isEmpty()) out.add(Line.BAR);
        for (JsonObject m : modifiers) out.add(new Line(modifierLine(m), false));
        List<String> traits = new ArrayList<>();
        for (String t : item.traits()) {
            String line = traitLine(t);
            if (line != null) traits.add(line);
        }
        if (!traits.isEmpty()) {
            if (!out.isEmpty()) out.add(Line.BAR);
            for (String t : traits) out.add(new Line(t, false));
        }
        JsonObject extra = ProfileData.obj(comps, "extra");
        String text = extra == null ? "" : ProfileData.str(extra, "text");
        if (!text.isEmpty()) {
            if (!out.isEmpty()) out.add(Line.BAR);
            wrapInto(out, text, telos);
        }
        JsonObject classes = ProfileData.obj(comps, "class_requirements");
        JsonObject tier = ProfileData.obj(ProfileData.obj(comps, "tier"), "tierType");
        long points = tier == null ? 0 : ProfileData.lng(tier, "points");
        if (classes != null || points > 0) {
            if (!out.isEmpty()) out.add(Line.BAR);
            JsonArray parts = new JsonArray();
            List<String> allowed = ProfileData.strings(classes, "allowed");
            for (int i = 0; i < allowed.size(); i++) {
                if (i > 0) parts.add(text("/", "white"));
                parts.add(translate("telos:item.class.icon." + allowed.get(i).toLowerCase(Locale.ROOT), "white"));
            }
            if (points > 0) parts.add(text(" " + points + " Tier Points", "gold"));
            out.add(new Line(line(parts), false));
        }
        // a definition with no lore at all still says what it is
        if (out.isEmpty()) out.add(new Line(line(arrayOf(text(ProfileData.pretty(item.key().contains("/") ? item.key().substring(0, item.key().indexOf('/')) : item.key()), "gray"))), false));
        return out;
    }

    private static String modifierLine(JsonObject m) {
        String id = ProfileData.str(m, "id");
        JsonObject value = ProfileData.obj(m, "value");
        String unit = ProfileData.str(m, "unit");
        String valueColor = colorOf(ProfileData.str(m, "color"));
        JsonArray parts = arrayOf(translate("telos:item.modifier." + id + ".icon", "white"),
                translate("telos:item.modifier." + id + ".label", "#FFF2B3"));
        JsonElement v = value == null ? null : value.get("value");
        if (v != null && v.isJsonPrimitive() && !v.getAsJsonPrimitive().isBoolean()) {
            parts.add(text(" » ", "dark_gray"));
            parts.add(text(number(v.getAsDouble()) + unit, valueColor == null ? "white" : valueColor));
        }
        return line(parts);
    }

    private static String statLine(String stat, double v) {
        String id = stat.toLowerCase(Locale.ROOT).replace(' ', '_');
        String color = statColor(id.replace('_', ' '));
        // the icon gets its colour in statIcons, like server lines
        return line(arrayOf(translate("telos:item.stat." + id + ".icon", "white"), text(ProfileData.pretty(stat), "#FFF2B3"),
                text(" » ", "dark_gray"),
                text((v > 0 ? "+" : "") + number(v), color == null ? "white" : color)));
    }

    // ---------------------------------------------------------------- stat symbols

    private static final java.util.regex.Pattern STAT_ICON = java.util.regex.Pattern.compile("telos:item\\.stat\\.([a-z_]+)\\.icon");

    /**
     * Colours the stat symbols the way the Stats Info HUD does ({@link TelosIcons#tinted}): a white symbol takes its
     * stat's Tab-list label colour, Critical Damage's symbol gets no colour (white). Server lines tint it dark purple
     * instead.
     */
    static String statIcons(String json) {
        if (json == null || !json.contains("telos:item.stat.")) return json;
        try {
            JsonElement e = JsonParser.parseString(json);
            statIconsIn(e);
            return e.toString();
        } catch (RuntimeException ex) {
            return json;
        }
    }

    private static void statIconsIn(JsonElement e) {
        if (e == null) return;
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) statIconsIn(c);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        if (o.get("translate") instanceof JsonPrimitive p) {
            java.util.regex.Matcher m = STAT_ICON.matcher(p.getAsString());
            if (m.matches()) {
                String label = m.group(1).replace('_', ' ');
                String color = TelosIcons.tinted(label) ? statColor(label) : "white";
                if (color != null) o.addProperty("color", color);
            }
        }
        statIconsIn(o.get("extra"));
    }

    /** The stat's Tab-list label colour ({@code #RRGGBB}), or null when the Tab list doesn't show it. */
    private static String statColor(String label) {
        return ServerInfoAdapter.tabStat(label).map(s -> String.format("#%06X", s.labelColor() & 0xFFFFFF)).orElse(null);
    }

    private static String traitLine(String key) {
        JsonObject t = ProfileData.obj(traitDefs, key);
        if (t == null) return line(arrayOf(text(ProfileData.pretty(key), "gray")));
        int slot = ProfileData.integer(t, "slot"), level = ProfileData.integer(t, "level");
        // the tier glyph is drawn untinted (white), like in game; the description brings its own colours
        JsonArray parts = arrayOf(translate("telos:item.trait." + slot + "." + level + ".icon", "white"));
        for (JsonElement e : MiniText.toJson(ProfileData.str(t, "description"))) parts.add(e);
        return line(parts);
    }

    // ---------------------------------------------------------------- translate keys

    /**
     * Replaces Telos's translate keys ({@code telos:item.modifier.damage.icon}) with their text from
     * {@code translations.json}: in game the server resolves them, the API sends them raw. Unknown {@code .label}
     * keys become a prettified name, other unknown Telos keys disappear (rather than showing the raw key).
     */
    static String resolve(String json) {
        if (json == null || !json.contains("\"translate\"")) return json;
        try {
            JsonElement e = JsonParser.parseString(json);
            resolveIn(e, TelosData.TRANSLATIONS.get().byKey());
            return e.toString();
        } catch (RuntimeException ex) {
            return json;
        }
    }

    private static void resolveIn(JsonElement e, Map<String, String> table) {
        if (e == null) return;
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) resolveIn(c, table);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        if (o.get("translate") instanceof JsonPrimitive p) {
            String key = p.getAsString();
            String text = table.get(key);
            if (text == null && key.startsWith("telos:")) {
                text = key.endsWith(".label") ? ProfileData.pretty(key.substring(0, key.length() - 6).replace('.', '/')) : "";
            }
            if (text != null) {
                o.remove("translate");
                o.remove("with");
                o.remove("fallback");
                o.addProperty("text", text);
            }
        }
        resolveIn(o.get("extra"), table);
    }

    // ---------------------------------------------------------------- wrapping

    /**
     * Splits one JSON text component at line breaks and into lines of about {@code maxChars} visible characters,
     * styles kept; a {@code <separator>} line becomes null (a divider bar).
     */
    static List<String> wrap(String json, int maxChars) {
        List<JsonObject> runs = new ArrayList<>();
        try {
            flatten(JsonParser.parseString(json), new JsonObject(), runs);
        } catch (RuntimeException e) {
            return List.of(json);
        }
        Lines lines = new Lines();
        for (JsonObject run : runs) {
            if (!run.has("text")) { // translate / sprite: atomic
                lines.add(run, 1);
                continue;
            }
            String s = run.get("text").getAsString();
            int i = 0;
            while (i < s.length()) {
                int nl = s.indexOf('\n', i);
                int end = nl < 0 ? s.length() : nl;
                for (String word : s.substring(i, end).split("(?<= )")) {
                    if (lines.len > 0 && lines.len + word.trim().length() > maxChars) {
                        lines.flush();
                        word = word.stripLeading();
                    }
                    if (word.isEmpty()) continue;
                    JsonObject piece = run.deepCopy();
                    piece.addProperty("text", word);
                    lines.add(piece, word.length());
                    lines.plain.append(word);
                }
                if (nl < 0) break;
                lines.flush();
                i = nl + 1;
            }
        }
        lines.flush();
        return lines.out;
    }

    /** Line builder for {@link #wrap}: a line reading {@code <separator>} becomes a divider bar (null). */
    private static final class Lines {
        final List<String> out = new ArrayList<>();
        final StringBuilder plain = new StringBuilder();
        JsonArray cur = new JsonArray();
        int len;

        void add(JsonObject run, int width) {
            cur.add(run);
            len += width;
        }

        void flush() {
            String p = plain.toString().trim();
            if (p.equals("<separator>")) out.add(null);
            else if (!p.isEmpty() || hasAtom()) out.add(line(cur));
            cur = new JsonArray();
            plain.setLength(0);
            len = 0;
        }

        private boolean hasAtom() {
            for (JsonElement e : cur) if (!e.getAsJsonObject().has("text")) return true;
            return false;
        }
    }

    private static final String[] STYLE_KEYS = {"color", "bold", "italic", "underlined", "strikethrough", "obfuscated", "font", "shadow_color"};

    private static void flatten(JsonElement e, JsonObject inherited, List<JsonObject> out) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonPrimitive()) {
            JsonObject run = inherited.deepCopy();
            run.addProperty("text", e.getAsString());
            out.add(run);
            return;
        }
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) flatten(c, inherited, out);
            return;
        }
        JsonObject o = e.getAsJsonObject();
        JsonObject style = inherited.deepCopy();
        for (String k : STYLE_KEYS) if (o.has(k)) style.add(k, o.get(k));
        if (o.has("text")) {
            if (!o.get("text").getAsString().isEmpty()) flatten(o.get("text"), style, out);
        } else {
            JsonObject atom = o.deepCopy();
            atom.remove("extra");
            for (Map.Entry<String, JsonElement> s : style.entrySet()) if (!atom.has(s.getKey())) atom.add(s.getKey(), s.getValue());
            out.add(atom);
        }
        JsonArray extra = ProfileData.arr(o, "extra");
        if (extra != null) for (JsonElement c : extra) flatten(c, style, out);
    }

    // ---------------------------------------------------------------- small JSON builders

    private static String line(JsonArray parts) {
        JsonObject root = new JsonObject();
        root.addProperty("text", "");
        root.addProperty("italic", false);
        root.add("extra", parts);
        return root.toString();
    }

    private static JsonArray arrayOf(JsonObject... parts) {
        JsonArray a = new JsonArray();
        for (JsonObject p : parts) a.add(p);
        return a;
    }

    private static JsonObject text(String s, String color) {
        JsonObject o = new JsonObject();
        o.addProperty("text", s);
        if (color != null) o.addProperty("color", color);
        return o;
    }

    private static JsonObject translate(String key, String color) {
        JsonObject o = new JsonObject();
        o.addProperty("translate", key);
        if (color != null) o.addProperty("color", color);
        return o;
    }

    /** The {@code color} of a style component stored as a JSON string ({@code "{\"color\":\"#DE7500\"}"}). */
    private static String color(JsonObject style, String key) {
        return style == null ? null : colorOf(ProfileData.str(style, key));
    }

    private static String colorOf(String styleJson) {
        if (styleJson == null || styleJson.isEmpty()) return null;
        try {
            JsonElement e = JsonParser.parseString(styleJson);
            if (e.isJsonObject() && e.getAsJsonObject().get("color") instanceof JsonPrimitive p) return p.getAsString();
        } catch (RuntimeException ignored) {
            // not JSON: no colour
        }
        return null;
    }

    private static int hex(String color) {
        if (color == null || !color.startsWith("#") || color.length() != 7) return 0;
        try {
            return Integer.parseInt(color.substring(1), 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code 7.0} → {@code 7}, {@code 0.6} → {@code 0.6}. */
    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
