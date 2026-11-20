package dev.telemetric.module.impl.hud;

import dev.telemetric.hud.ShinyColors;
import dev.telemetric.hud.TextRun;

import java.util.List;
import java.util.Locale;

/**
 * The damage tier ladder shared by the DPS Meter and the DPS Log. Each hit gets a tier from its amount, and the tier
 * picks the hit's colour and how loud its DPS Log animation is. The colours are a fixed damage scale (like the
 * server's rarity colours), not theme colours, because they have to read the same on every theme. The top tier (1k+)
 * uses the moving {@link ShinyColors} cycle.
 */
final class DamageTiers {

    /** Lower bound of tiers 1..9 (tier 0 is below 50). */
    private static final int[] MIN = {50, 100, 150, 200, 300, 400, 500, 700, 1000};

    /** ARGB of tiers 1..8: soft green, green, aqua, blue, purple, pink, orange, red. Tier 9 is shiny. */
    private static final int[] COLOR = {
            0xFFB4F0AA, 0xFF55FF55, 0xFF55FFFF, 0xFF5C9DFF, 0xFFB070FF, 0xFFFF6FD8, 0xFFFFAA00, 0xFFFF4040
    };

    /** The highest tier (1k+): shiny text and a pulsing glow. */
    static final int TOP = MIN.length;
    /** From this tier up, a new DPS Log line pops (grows in). */
    static final int POP = 4;
    /** From this tier up, a new DPS Log line flashes a highlight bar. */
    static final int FLASH = 7;
    /** From this tier up, a new DPS Log line shakes. */
    static final int SHAKE = 8;

    private DamageTiers() {}

    static int tier(double amount) {
        int t = 0;
        while (t < MIN.length && amount >= MIN[t]) t++;
        return t;
    }

    /** Lower bound of a tier (0 for tier 0). */
    static int min(int tier) {
        return tier <= 0 ? 0 : MIN[tier - 1];
    }

    /** The solid ARGB of a tier, or 0 (theme default) for tier 0. The top tier returns its current shiny colour. */
    static int color(int tier) {
        if (tier <= 0) return 0;
        if (tier >= TOP) return ShinyColors.argb(0);
        return COLOR[tier - 1];
    }

    /** The styled text of a hit: shiny per-character runs on the top tier, otherwise one run in the tier colour. */
    static List<TextRun> runs(String text, int tier, int fallbackColor) {
        if (tier >= TOP) return ShinyColors.runs(text, 0);
        int c = color(tier);
        return List.of(new TextRun(text, c == 0 ? fallbackColor : c, null));
    }

    /** "12,345", or "12.3k" with {@code compact}. Small hits keep one decimal. */
    static String format(double v, boolean compact) {
        if (compact) {
            if (v >= 1e9) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
            if (v >= 1e6) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
            if (v >= 1e4) return String.format(Locale.ROOT, "%.1fk", v / 1e3);
        }
        if (v >= 1000 || v == Math.rint(v)) return String.format(Locale.ROOT, "%,.0f", v);
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
