package dev.telemetric.module.impl.hud;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telos Realms stat point → real effect, per the official wiki (wiki.telosrealms.com/wiki/Stats).
 * Pure math, no Minecraft.
 */
final class StatFormulas {

    /** Stats that have a formula (lower-case labels). */
    static final Set<String> SUPPORTED = Set.of("attack", "speed", "defense", "health", "vitality", "evasion",
            "critical chance");

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d[\\d,]*(?:\\.\\d+)?");

    private StatFormulas() {}

    static boolean supported(String label) {
        return SUPPORTED.contains(label.toLowerCase(Locale.ROOT));
    }

    /**
     * @param label stat label (e.g. "Attack")
     * @param value Tab value text (e.g. "+50.6")
     * @return the real effect as display text (e.g. "17.3%"), or null if unknown / unparsable
     */
    static String real(String label, String value) {
        Double v = parse(value);
        if (v == null) return null;
        return switch (label.toLowerCase(Locale.ROOT)) {
            // 0.5·x / (50 + 0.2·x) · 100 %  — damage increase / crit chance
            case "attack", "critical chance" -> pct(0.5 * v / (50 + 0.2 * v) * 100);
            // +0.8 % movement speed per point
            case "speed" -> pct(0.8 * v);
            // (1 − 100 / (def + 100)) · 100 %  — damage reduction
            case "defense" -> pct((1 - 100 / (v + 100)) * 100);
            // 20 base HP + 1 per point
            case "health" -> fmt(20 + v) + " HP";
            // 0.1 HP/s base + 0.02 HP/s per point
            case "vitality" -> fmt(0.1 + 0.02 * v) + " HP/s";
            // ev / (ev + 75) · 100 %  — dodge chance
            case "evasion" -> pct(v / (v + 75) * 100);
            default -> null;
        };
    }

    private static Double parse(String value) {
        if (value == null) return null;
        Matcher m = NUMBER.matcher(value);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String pct(double d) {
        return fmt(d) + "%";
    }

    private static String fmt(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
