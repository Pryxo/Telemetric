package dev.telemetric.dungeon.rules;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which {@link DungeonRules} each dungeon uses. Names are matched normalised (lower case, letters and digits
 * only — "Kobold's Den" = "kobolds den"), the same way as the Wayfinder blacklist.
 * <p>
 * Adding a dungeon: one {@code rule(...)} line combining existing mechanics. A new mechanic gets a flag in
 * {@link DungeonRules}, its own class in {@code dungeon/}, and a line in {@code docs/dungeon.md} (Dungeon rules).
 */
public final class DungeonRulebook {

    private static final Map<String, DungeonRules> RULES = new LinkedHashMap<>();

    static {
        // long halls / corridors of any length, outdoor ground and arcades around the keep (not rooms)
        rule("Dwarven Frostkeep", DungeonRules.DEFAULT
                .withRoomShape(DungeonRules.RoomShape.FREEFORM)
                .withIndoorRoomsOnly(true));
        // clear mobs: two "Keeper of Dusk" (verified in game, 2026-11-14)
        rule("Midnight Stronghold", DungeonRules.DEFAULT.withGoalMob("Keeper of Dusk"));
        // no Wayfinder / Dungeon Map (user request, 2026-11-14); both spellings where the in-game name is unsure
        for (String name : new String[]{
                "Omnipotent's Citadel", "Fungal Cavern", "Tartarus", "Raphael's Castle", "Aurora Sanctum",
                "The Aviary", "Aviary", "Raph's Chamber", "Raph's Champer",
                "Rustborn Kingdom: Part I", "Rustborn Kingdom: Part II", "Rustborn Kingdom: Part III",
                "Resounding Ruins", "Celestial's Province: Part I", "Celestial's Province: Part II",
                "Seraph's Domain", "Dawn of Creation", "Neo Eden", "Tenebris"}) {
            rule(name, DungeonRules.DEFAULT.withMapped(false));
        }
    }

    private DungeonRulebook() {}

    private static void rule(String dungeon, DungeonRules rules) {
        RULES.put(normalize(dungeon), rules);
    }

    /** Rules for this dungeon, {@link DungeonRules#DEFAULT} if it has none (or the name is unknown). */
    public static DungeonRules rulesFor(String dungeon) {
        if (dungeon == null) return DungeonRules.DEFAULT;
        return RULES.getOrDefault(normalize(dungeon), DungeonRules.DEFAULT);
    }

    static String normalize(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
