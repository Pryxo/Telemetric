package dev.telemetric.game;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Your current character, from the Tab list "Class" value (mode glyph + {@code Lvl 70 Knight}). Pure parsing;
 * {@link #current()} reads the Tab list through {@link ServerInfoAdapter}.
 *
 * @param level     character level, -1 when not shown
 * @param className class name ("Knight"), "" when unknown
 * @param mode      {@code normal} / {@code seasonal} / {@code hardcore}, "" when the mode glyph is unknown
 */
public record TelosCharacter(int level, String className, String mode) {

    /** Telos classes, lower case. */
    public static final List<String> CLASSES = List.of("arcanist", "assassin", "huntress", "knight", "necromancer", "samurai");

    /** Mode glyphs in the Class value: {@code telos:misc} U+0370 / U+0371 / U+0372; U+18057 = seasonal (seen live). */
    private static final int NORMAL = 0x370, HARDCORE = 0x371, SEASONAL = 0x372, SEASONAL_LIVE = 0x18057;

    public static TelosCharacter parse(String value) {
        if (value == null || value.isBlank()) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        return new TelosCharacter(firstNumber(value), className(lower), mode(value, lower));
    }

    public static Optional<TelosCharacter> current() {
        return ServerInfoAdapter.tabStat("Class").map(s -> parse(s.value()));
    }

    /** Capitalised class name found in {@code text} ("Knight"), or "". */
    public static String className(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String c : CLASSES) {
            if (lower.contains(c)) return Character.toUpperCase(c.charAt(0)) + c.substring(1);
        }
        return "";
    }

    private static String mode(String value, String lower) {
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (cp == NORMAL) return "normal";
            if (cp == HARDCORE) return "hardcore";
            if (cp == SEASONAL || cp == SEASONAL_LIVE) return "seasonal";
            i += Character.charCount(cp);
        }
        if (lower.contains("season")) return "seasonal";
        if (lower.contains("hardcore") || lower.contains("ironman")) return "hardcore";
        return "";
    }

    private static int firstNumber(String s) {
        int i = 0, n = s.length();
        while (i < n && !Character.isDigit(s.charAt(i))) i++;
        if (i == n) return -1;
        int v = 0;
        while (i < n && Character.isDigit(s.charAt(i)) && v < 100_000) v = v * 10 + (s.charAt(i++) - '0');
        return v;
    }
}
