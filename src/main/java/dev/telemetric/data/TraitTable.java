package dev.telemetric.data;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code tables/traits.json}: the unique traits of untiered items, with their description template and the value of
 * every placeholder per tier. Feeds Trait Details.
 *
 * @param byKey trait {@link Names#key} to trait
 * @param tiers tier letters, lowest first ({@code D C B A S})
 */
public record TraitTable(Map<String, Trait> byKey, List<String> tiers) {

    static final TraitTable EMPTY = new TraitTable(Map.of(), List.of());

    /**
     * The tier glyphs shown in front of a trait's name in item lore, lowest tier first (pack font, U+16249 … U+16245).
     * Same order as {@link #tiers}, so the wiki's "Royal: max 𖉅" means S.
     */
    public static final String TIER_GLYPHS = "𖉉𖉈𖉇𖉆𖉅";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    /**
     * @param slot   {@code weapon} / {@code ability} / {@code armour}
     * @param values for each placeholder {@code {n}}: tier letter to value text
     */
    public record Trait(String name, String slot, String template, List<Map<String, String>> values) {

        /** The description with every placeholder filled in for {@code tier} ({@code "?"} where the table has no value). */
        public String describe(String tier) {
            Matcher m = PLACEHOLDER.matcher(template);
            StringBuilder out = new StringBuilder(template.length() + 16);
            while (m.find()) {
                int i = Integer.parseInt(m.group(1));
                String v = i < values.size() ? values.get(i).get(tier) : null;
                m.appendReplacement(out, Matcher.quoteReplacement(v == null || v.isEmpty() ? "?" : v));
            }
            m.appendTail(out);
            return out.toString();
        }
    }

    static TraitTable parse(JsonObject root) {
        List<String> tiers = new ArrayList<>();
        for (String letter : JsonFields.strings(root, "tiers")) if (!letter.isEmpty()) tiers.add(letter);
        Map<String, Trait> byKey = new HashMap<>();
        for (String slot : List.of("weapon", "ability", "armour")) {
            for (JsonObject o : JsonFields.objects(root, slot)) {
                String name = JsonFields.str(o, "name"), template = JsonFields.str(o, "template");
                if (name.isEmpty() || template.isEmpty()) continue;
                List<Map<String, String>> values = new ArrayList<>();
                for (JsonObject v : JsonFields.objects(o, "values")) {
                    Map<String, String> perTier = new HashMap<>();
                    for (String tier : tiers) perTier.put(tier, JsonFields.str(v, tier));
                    values.add(Map.copyOf(perTier));
                }
                byKey.putIfAbsent(Names.key(name), new Trait(name, slot, template, List.copyOf(values)));
            }
        }
        return new TraitTable(Map.copyOf(byKey), List.copyOf(tiers));
    }

    /** The trait with this display name, or null. */
    public Trait get(String name) {
        return byKey.get(Names.key(name));
    }

    /** Tier index (0 is the lowest) of the tier glyph at the start of {@code text}, or -1. */
    public static int tierOfGlyph(String text) {
        if (text == null || text.isEmpty()) return -1;
        int cp = text.codePointAt(0);
        int idx = TIER_GLYPHS.indexOf(cp);
        return idx < 0 ? -1 : TIER_GLYPHS.codePointCount(0, idx);
    }

    /** The letter of tier {@code index} ({@code 0} is D), or null. */
    public String tier(int index) {
        return index >= 0 && index < tiers.size() ? tiers.get(index) : null;
    }
}
