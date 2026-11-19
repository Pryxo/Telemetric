package dev.telemetric.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code tables/serverMessages.json} — server lines Social → Server Messages can hide. {@code bosses}: boss name →
 * its lines (ticking the boss hides all of them); {@code misc}: one line per entry (each is its own option). Every
 * entry is a regex over the whole plain text. Pure Java.
 *
 * @param bosses boss name → its line regexes, in file order
 * @param misc   option label → line regex, in file order
 */
public record ServerMessageTable(Map<String, List<Pattern>> bosses, Map<String, Pattern> misc) {

    static final ServerMessageTable EMPTY = new ServerMessageTable(Map.of(), Map.of());

    static ServerMessageTable parse(JsonObject root) {
        Map<String, List<Pattern>> bosses = new LinkedHashMap<>();
        JsonObject b = JsonFields.obj(root, "bosses");
        if (b != null) {
            for (Map.Entry<String, JsonElement> e : b.entrySet()) {
                if (!e.getValue().isJsonArray() || e.getKey().isBlank()) continue;
                List<Pattern> lines = new ArrayList<>();
                for (String s : JsonFields.strings(b, e.getKey())) {
                    Pattern p = compile(s);
                    if (p != null) lines.add(p);
                }
                bosses.put(e.getKey().strip(), List.copyOf(lines));
            }
        }
        Map<String, Pattern> misc = new LinkedHashMap<>();
        for (String s : JsonFields.strings(root, "misc")) {
            Pattern p = compile(s);
            if (p != null) misc.putIfAbsent(label(s), p);
        }
        return new ServerMessageTable(java.util.Collections.unmodifiableMap(bosses),
                java.util.Collections.unmodifiableMap(misc));
    }

    public List<String> bossNames() {
        return List.copyOf(bosses.keySet());
    }

    public List<String> miscNames() {
        return List.copyOf(misc.keySet());
    }

    /** A regex shown readably in the dropdown: wildcards become "…", escapes and groups are dropped. */
    static String label(String regex) {
        String s = regex
                .replace("[^\\n]*", "…").replace(".*", "…").replace(".+", "…").replace(".?", "")
                .replace("\\d+", "#").replace("\\n", " ");
        s = s.replaceAll("\\(\\?:[^()]*\\)\\?", "")          // optional groups
                .replaceAll("\\[[^\\]]*\\](?:\\{[^}]*\\}|[*+?])?", "") // character classes
                .replaceAll("\\(\\?:([^()|]*)\\|[^()]*\\)", "$1")       // (?:a|b) → a
                .replaceAll("\\(\\?<\\w+>|\\(\\?:|[()]", "")
                .replaceAll("\\\\(.)", "$1");
        s = s.replaceAll("…+", "…").replaceAll("\\s{2,}", " ").strip();
        return s.isEmpty() ? regex : s;
    }

    private static Pattern compile(String regex) {
        if (regex == null || regex.isBlank()) return null;
        try {
            return Pattern.compile(regex, Pattern.DOTALL);
        } catch (PatternSyntaxException ex) {
            dev.telemetric.Telemetric.LOGGER.warn("serverMessages.json: bad regex '{}': {}", regex, ex.getDescription());
            return null;
        }
    }
}
