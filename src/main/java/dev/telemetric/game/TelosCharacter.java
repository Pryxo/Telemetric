package dev.telemetric.game;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Your current character, from the Tab list "Class" value (mode glyph + {@code Lvl 70 Knight}). Pure parsing;
 * {@link #current()} reads the Tab list through {@link ServerInfoAdapter}.
 *
 * @param level     character level, -1 if it isn't shown
 * @param className class name ("Knight"), "" if unknown
 * @param mode      {@code normal} / {@code seasonal} / {@code hardcore} / {@code group}, "" if the mode glyph is unknown
 */
public record TelosCharacter(int level, String className, String mode) {

    /** Telos classes in lower case. */
    public static final List<String> CLASSES = List.of("arcanist", "assassin", "huntress", "knight", "necromancer", "samurai");

    /**
     * Mode glyphs in the Class value ({@code telos:misc} → {@code telos:icon/ruleset/...}, checked in the server pack on
     * 2026-11-17). Each ruleset has a plain and a {@code glowing/} icon: normal U+0370 / U+18056, hardcore (ironman)
     * U+0371 / U+18055, seasonal U+0372 / U+18057, and {@code group_type/<colour>} (16 colours) U+15DFC to U+15E0D /
     * U+18058 to U+18069.
     */
    private static final int NORMAL = 0x370, HARDCORE = 0x371, SEASONAL = 0x372,
            HARDCORE_GLOW = 0x18055, NORMAL_GLOW = 0x18056, SEASONAL_GLOW = 0x18057,
            GROUP_FIRST = 0x15DFC, GROUP_LAST = 0x15E0D, GROUP_GLOW_FIRST = 0x18058, GROUP_GLOW_LAST = 0x18069;

    public static TelosCharacter parse(String value) {
        if (value == null || value.isBlank()) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        return new TelosCharacter(firstNumber(value), className(lower), mode(value, lower));
    }

    public static Optional<TelosCharacter> current() {
        // TabStat.value has its glyphs removed, so the mode glyph is only in the styled runs.
        return ServerInfoAdapter.tabStat("Class").map(s -> {
            StringBuilder b = new StringBuilder();
            for (var run : s.valueRuns()) b.append(run.text());
            return parse(b.isEmpty() ? s.value() : b.toString());
        });
    }

    /** The capitalised class name found in {@code text} ("Knight"), or "". */
    public static String className(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String c : CLASSES) {
            if (lower.contains(c)) return Character.toUpperCase(c.charAt(0)) + c.substring(1);
        }
        return "";
    }

    private static String mode(String value, String lower) {
        String glyph = modeFromGlyph(value);
        if (!glyph.isEmpty()) return glyph;
        if (lower.contains("season")) return "seasonal";
        if (lower.contains("hardcore") || lower.contains("ironman")) return "hardcore";
        return "";
    }

    /**
     * The mode from the first ruleset glyph in {@code text} ({@code normal} / {@code seasonal} / {@code hardcore} /
     * {@code group}), or "" if there's none. Telos puts the same glyph in front of the rank in chat and name tags
     * ({@code "𕼜Ͱ [Member] OKDang"}), so it works for other players too. Glyphs only: a name can't fake it.
     */
    public static String modeFromGlyph(String text) {
        if (text == null) return "";
        String value = text;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (cp == NORMAL || cp == NORMAL_GLOW) return "normal";
            if (cp == HARDCORE || cp == HARDCORE_GLOW) return "hardcore";
            if (cp == SEASONAL || cp == SEASONAL_GLOW) return "seasonal";
            if ((cp >= GROUP_FIRST && cp <= GROUP_LAST) || (cp >= GROUP_GLOW_FIRST && cp <= GROUP_GLOW_LAST)) return "group";
            i += Character.charCount(cp);
        }
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
