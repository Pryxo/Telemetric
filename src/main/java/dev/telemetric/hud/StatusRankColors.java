package dev.telemetric.hud;

import dev.telemetric.ui.animation.Durations;

/**
 * Animated colours of a HeadStatus title by leaderboard position (Nametags → Show Status): #1 shiny + wave, #2
 * Unholy, #3 Bloodshot, #4–10 Legendary orange, #11–20 purple, #21–25 blue; everything below stays plain. Each tier is
 * a moving {@link ColorCycle} around its base colour (Unholy / Bloodshot from Telos' rarity colours). Pure Java.
 */
public final class StatusRankColors {

    private StatusRankColors() {}

    /** Per-character step of the tier gradients (frames). */
    private static final double STEP = 0.35;

    private static ColorCycle cycle(int... frames) {
        return new ColorCycle(frames, STEP, Durations.STATUS_GRADIENT_LOOP);
    }

    /** One colour tier. {@code wave} = characters also bob up and down. */
    public enum Tier {
        SHINY(ShinyColors.CYCLE, true),
        UNHOLY(cycle(0x94B0B1, 0xB9D0D1, 0xE6F2F2, 0xB9D0D1, 0x94B0B1, 0x728D8E)),
        BLOODSHOT(cycle(0x87060B, 0xB80E16, 0xE8222B, 0xFF5A5A, 0xE8222B, 0xB80E16, 0x87060B, 0x6E0409)),
        LEGENDARY(cycle(0xFF8C00, 0xFFAA00, 0xFFC846, 0xFFE08A, 0xFFC846, 0xFFAA00)),
        EPIC(cycle(0x8D15F0, 0xAA44FF, 0xCC77FF, 0xE6AAFF, 0xCC77FF, 0xAA44FF)),
        RARE(cycle(0x2E6BFF, 0x4C8DFF, 0x6FB4FF, 0xA3D8FF, 0x6FB4FF, 0x4C8DFF));

        public final ColorCycle cycle;
        public final boolean wave;

        Tier(ColorCycle cycle) {
            this(cycle, false);
        }

        Tier(ColorCycle cycle, boolean wave) {
            this.cycle = cycle;
            this.wave = wave;
        }
    }

    /** Tier of a leaderboard position, or null (no position / below #25). */
    public static Tier forPosition(int position) {
        if (position <= 0) return null;
        if (position == 1) return Tier.SHINY;
        if (position == 2) return Tier.UNHOLY;
        if (position == 3) return Tier.BLOODSHOT;
        if (position <= 10) return Tier.LEGENDARY;
        if (position <= 20) return Tier.EPIC;
        if (position <= 25) return Tier.RARE;
        return null;
    }
}
