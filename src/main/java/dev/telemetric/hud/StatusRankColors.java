package dev.telemetric.hud;

import dev.telemetric.data.TelosData;
import dev.telemetric.ui.animation.Durations;

import java.util.ArrayList;
import java.util.List;

/**
 * Animated colours of a HeadStatus title by leaderboard position (Nametags → Show Status). Positions map to a
 * {@link Tier} slot (#1, #2, #3, #4–10, #11–20, #21–25; everything below stays plain); the selected {@link Theme}
 * (Nametags → Status Theme, from {@code StatusColor.json}) gives each slot its {@link ColorCycle} (one colour = solid). The theme is read at draw time, so switching
 * it recolours existing name tags at once. Pure Java.
 */
public final class StatusRankColors {

    private StatusRankColors() {}

    /** Per-character step of the tier gradients (frames). */
    private static final double STEP = 0.35;

    private static ColorCycle gradient(int... frames) {
        return new ColorCycle(frames, STEP, Durations.STATUS_GRADIENT_LOOP);
    }

    /** Leaderboard slot. {@code wave} = characters also bob up and down (Nametags → Top 1 Wave). */
    public enum Tier {
        TOP_1(true, "#1"), TOP_2(false, "#2"), TOP_3(false, "#3"), TOP_10(false, "#4-10"),
        TOP_20(false, "#11-20"), TOP_25(false, "#21-25");

        public final boolean wave;
        /** Slot key in {@code StatusColor.json}. */
        public final String key;

        Tier(boolean wave, String key) {
            this.wave = wave;
            this.key = key;
        }
    }

    /**
     * A colour set for all slots, in {@link Tier} order. Themes come from {@code data/tables/StatusColor.json}
     * ({@link TelosData#STATUS_COLORS}); add new ones there and they show up in the Status Theme dropdown.
     */
    public static final class Theme {
        private static final ColorCycle WHITE = gradient(0xFFFFFF);
        /** Used when the table is missing / empty: #1 shiny, the rest white. */
        private static final Theme FALLBACK = new Theme("Telos Realms", new ColorCycle[]{
                ShinyColors.CYCLE, WHITE, WHITE, WHITE, WHITE, WHITE});

        public final String displayName;
        private final ColorCycle[] cycles;

        private Theme(String displayName, ColorCycle[] cycles) {
            this.displayName = displayName;
            this.cycles = cycles;
        }

        public ColorCycle cycle(Tier tier) {
            return cycles[tier.ordinal()];
        }

        private static TelosData.StatusColors builtFrom;
        private static List<Theme> built = List.of(FALLBACK);

        /** All themes of the current table snapshot (rebuilt when the table is swapped). */
        public static synchronized List<Theme> all() {
            TelosData.StatusColors table = TelosData.STATUS_COLORS.get();
            if (table != builtFrom) {
                List<Theme> out = new ArrayList<>();
                table.themes().forEach((name, slots) -> {
                    ColorCycle[] c = new ColorCycle[Tier.values().length];
                    for (Tier t : Tier.values()) c[t.ordinal()] = toCycle(slots.get(t.key));
                    out.add(new Theme(name, c));
                });
                built = out.isEmpty() ? List.of(FALLBACK) : List.copyOf(out);
                builtFrom = table;
            }
            return built;
        }

        private static ColorCycle toCycle(TelosData.StatusSlot slot) {
            if (slot == null) return WHITE;
            if (slot.shiny()) return ShinyColors.CYCLE;
            return gradient(slot.rgb());
        }

        /** Theme with that display name (case-insensitive), else the first one. */
        public static Theme byName(String name) {
            List<Theme> all = all();
            for (Theme t : all) if (t.displayName.equalsIgnoreCase(name)) return t;
            return all.get(0);
        }

        /** Name of the first (default) theme. */
        public static String defaultName() {
            return all().get(0).displayName;
        }

        public static List<String> names() {
            return all().stream().map(t -> t.displayName).toList();
        }
    }

    private static volatile String themeName = "";

    public static Theme theme() {
        return Theme.byName(themeName);
    }

    public static void setTheme(String name) {
        themeName = name == null ? "" : name;
    }

    /** Current theme's cycle for {@code tier}. */
    public static ColorCycle cycle(Tier tier) {
        return theme().cycle(tier);
    }

    /** Tier of a leaderboard position, or null (no position / below #25). */
    public static Tier forPosition(int position) {
        if (position <= 0) return null;
        if (position == 1) return Tier.TOP_1;
        if (position == 2) return Tier.TOP_2;
        if (position == 3) return Tier.TOP_3;
        if (position <= 10) return Tier.TOP_10;
        if (position <= 20) return Tier.TOP_20;
        if (position <= 25) return Tier.TOP_25;
        return null;
    }
}
