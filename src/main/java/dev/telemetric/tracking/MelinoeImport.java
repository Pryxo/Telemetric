package dev.telemetric.tracking;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.config.ModPaths;
import dev.telemetric.data.LootTable;
import dev.telemetric.data.LootTable.Drop;
import dev.telemetric.data.Names;
import dev.telemetric.data.TelosData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Melinoe's pity file ({@link ModPaths#MELINOE_PITY}: one flat object, {@code "DOOM_BOW": 12},
 * {@code "DOOM_BOW-shiny": 80}, plus {@code "_version"}) into our counters. Each name is matched to a drop by its
 * name ({@link #match}; {@code -shiny} = that drop's shiny variant), and the count is <b>added</b> to the pity
 * of the active scope (the character you're on), so kills already counted here are kept.
 */
public final class MelinoeImport {

    private static final String SHINY = "-shiny";
    private static final Set<String> STOP_WORDS = Set.of("the", "of");
    /** Ability item kinds, which Melinoe sometimes names differently ("Cinder Skull" for Cinder Scripture). */
    private static final Set<String> ABILITY_TYPES = Set.of("skull", "scripture", "star", "trap", "tome", "quiver",
            "seal", "bomb", "charm", "orb", "totem");

    /** What a file holds once matched: drop key to count, and the Melinoe names no drop matched. */
    public record Plan(Map<String, Integer> counts, List<String> unknown) {}

    private MelinoeImport() {}

    public static boolean fileExists() {
        return Files.isRegularFile(ModPaths.MELINOE_PITY);
    }

    /** Reads and matches the file. Throws if it's missing or not a JSON object. */
    public static Plan read() throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(ModPaths.MELINOE_PITY, StandardCharsets.UTF_8))
                .getAsJsonObject();
        LootTable loot = TelosData.LOOT.get();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        List<Drop> candidates = candidates(loot);
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String name = e.getKey();
            if (name.startsWith("_") || !e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isNumber()) continue;
            int n = e.getValue().getAsInt();
            if (n <= 0) continue;
            Drop drop = match(loot, candidates, name);
            if (drop == null) unknown.add(name);
            else counts.merge(drop.key(), n, Integer::sum);
        }
        return new Plan(counts, unknown);
    }

    /** Adds the plan to the active scope of {@code store}. Returns the number of counters changed. */
    public static int apply(Plan plan, TrackingStore store) {
        for (Map.Entry<String, Integer> e : plan.counts().entrySet()) {
            store.setPity(e.getKey(), store.pity(e.getKey()) + e.getValue());
        }
        if (!plan.unknown().isEmpty()) {
            Telemetric.LOGGER.info("Melinoe pity import: no drop for {}", String.join(", ", plan.unknown()));
        }
        return plan.counts().size();
    }

    /**
     * The drop for a Melinoe name. Melinoe keeps older or shorter names, so after the exact {@link Names#key name key}
     * it tries, each only when exactly one drop fits: the same words (ignoring "the" / "of" and plural s, "Cultist
     * Hood" = "Hood Of The Cultist"), one name's words inside the other's ("Sceptre Of The Fractured Firmament" =
     * "Fractured Firmament"), the same words apart from the ability type ("Cinder Skull" = "Cinder Scripture"), and
     * one letter off ("Etherealmare" = "Etherrealmare").
     */
    private static Drop match(LootTable loot, List<Drop> candidates, String melinoeName) {
        boolean shiny = melinoeName.endsWith(SHINY);
        String base = shiny ? melinoeName.substring(0, melinoeName.length() - SHINY.length()) : melinoeName;
        Drop d = exact(loot, base);
        if (d == null) d = fuzzy(candidates, base);
        if (d == null) return null;
        Drop target = shiny ? loot.shinyOf(d) : d;
        return target != null && target.tracked() ? target : null;
    }

    private static Drop exact(LootTable loot, String name) {
        List<Drop> named = loot.byName().get(Names.key(name));
        if (named == null) return null;
        for (Drop d : named) if (d.tracked()) return d;
        return null;
    }

    private static Drop fuzzy(List<Drop> candidates, String name) {
        Set<String> w = words(name);
        if (w.isEmpty()) return null;
        Drop d = unique(candidates, c -> words(c.name()).equals(w));
        if (d == null) d = unique(candidates, c -> {
            Set<String> cw = words(c.name());
            return cw.size() >= 2 && w.containsAll(cw) || cw.containsAll(w);
        });
        Set<String> core = withoutTypes(w);
        if (d == null && !core.isEmpty()) d = unique(candidates, c -> withoutTypes(words(c.name())).equals(core));
        String key = Names.key(name);
        if (d == null && key.length() >= 8) d = unique(candidates, c -> oneOff(Names.key(c.name()), key));
        return d;
    }

    /** The one candidate that passes, or null for none or several. */
    private static Drop unique(List<Drop> candidates, Predicate<Drop> test) {
        Drop found = null;
        for (Drop c : candidates) {
            if (!test.test(c)) continue;
            if (found != null && !Names.key(found.name()).equals(Names.key(c.name()))) return null;
            if (found == null) found = c;
        }
        return found;
    }

    private static Set<String> words(String name) {
        Set<String> out = new HashSet<>();
        for (String part : name.toLowerCase(Locale.ROOT).replace("'", "").split("[^a-z0-9]+")) {
            if (part.isEmpty() || STOP_WORDS.contains(part)) continue;
            out.add(part.length() > 3 && part.endsWith("s") ? part.substring(0, part.length() - 1) : part);
        }
        return out;
    }

    private static Set<String> withoutTypes(Set<String> words) {
        Set<String> out = new HashSet<>(words);
        out.removeAll(ABILITY_TYPES);
        return out;
    }

    /** True if {@code a} and {@code b} differ by one inserted, removed or changed letter. */
    private static boolean oneOff(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1 || a.equals(b)) return false;
        int i = 0, j = 0, edits = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (a.length() > b.length()) i++;
            else if (b.length() > a.length()) j++;
            else { i++; j++; }
        }
        return edits + (a.length() - i) + (b.length() - j) <= 1;
    }

    /** Every tracked normal (not shiny) drop, for the fuzzy matches. */
    private static List<Drop> candidates(LootTable loot) {
        List<Drop> out = new ArrayList<>();
        for (List<Drop> named : loot.byName().values()) {
            for (Drop d : named) if (d.tracked() && !d.shiny()) out.add(d);
        }
        return out;
    }
}
