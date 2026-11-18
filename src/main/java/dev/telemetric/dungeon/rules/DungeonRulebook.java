package dev.telemetric.dungeon.rules;

import dev.telemetric.chat.ChatPattern;
import dev.telemetric.data.TelosData;

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
        // clear mobs: two "Keeper of Dusk" (verified in game, 2026-11-14). No boss drop (user, 2026-11-14)
        rule("Midnight Stronghold", DungeonRules.DEFAULT.withGoalMob("Keeper of Dusk").withBossLevel(false));
        // clear mobs: "Dyerftum Overlord" (nameplate read in game, 2026-11-14). No boss drop: spawn + 2 clear mobs
        // (user, 2026-11-14); its boss bar ("The Creator") sits on a mob at y 30 under the spawn, not a boss level
        rule("Illarius' Hideout", DungeonRules.DEFAULT.withGoalMob("Dyerftum Overlord").withBossLevel(false));
        // books: each pick-up sends a random line + "(x/5)" (read in game, 2026-11-14): line italic, "(x/5)" bold
        rule("Resounding Ruins", rulesFor("Resounding Ruins").withChatCounter(new ChatCounter("Books", 5,
                ChatPattern.builder("Resounding Ruins book", "(?<line>[^()\\n]+) \\((?<n>\\d+)/(?<total>\\d+)\\)")
                        .run("[^()\\n]+ ", ChatPattern.ITALIC)
                        .run("\\(\\d+/\\d+\\)", ChatPattern.BOLD)
                        .build())));
        // renown (read live 2026-11-14, all runs italic, colour only differs): goal "Gain 110 renown to prove
        // yourselves worthy before Cherubim.", progress "The garden recognizes your progress. (61/110)" every 50;
        // each pick-up spawns an armor stand named "(75/110)" (gold/yellow, plain)
        rule("Neo Eden", rulesFor("Neo Eden").withChatCounter(new ChatCounter("Renown", 0,
                ChatPattern.builder("Neo Eden renown progress",
                                "The garden recognizes your progress\\. \\((?<n>\\d+)/(?<total>\\d+)\\)")
                        .run(".+", ChatPattern.ITALIC)
                        .build(),
                ChatPattern.builder("Neo Eden renown goal",
                                "Gain (?<total>\\d+) renown to prove yourselves worthy before [A-Za-z' ]+\\.")
                        .run(".+", ChatPattern.ITALIC)
                        .build(),
                java.util.regex.Pattern.compile("\\((?<n>\\d+)/\\d+\\)"))));
        // 3 puzzle rooms, 2 spawn the boss; no Wayfinder / Dungeon Map (user, 2026-11-14). Messages (read live
        // 2026-11-14) are colour-only runs, no bold/italic → one plain run
        rule("Dreadwood Thicket", DungeonRules.DEFAULT.withChatPuzzles(new ChatPuzzles(
                "Completed Room", 3,
                dreadwood("Dreadwood room attempt", "is attempting the "),
                dreadwood("Dreadwood room complete", "has completed the "),
                ChatPattern.builder("Dreadwood room reset",
                                "\\[Dreadwood Civilian\\] The (?<room>[A-Za-z' ]+ Room) has reset!")
                        .run(".+", ChatPattern.PLAIN)
                        .build())));
    }

    /** "[Dreadwood Civilian] &lt;player&gt; &lt;verb&gt;&lt;room&gt;!" with groups {@code player} and {@code room}. */
    private static ChatPattern dreadwood(String name, String verb) {
        return ChatPattern.builder(name, "\\[Dreadwood Civilian\\] (?<player>\\w{1,16}) "
                        + java.util.regex.Pattern.quote(verb) + "(?<room>[A-Za-z' ]+ Room)!")
                .run(".+", ChatPattern.PLAIN)
                .build();
    }

    private DungeonRulebook() {}

    private static void rule(String dungeon, DungeonRules rules) {
        RULES.put(normalize(dungeon), rules);
    }

    /** Rules for this dungeon, {@link DungeonRules#DEFAULT} if it has none (or the name is unknown). */
    public static DungeonRules rulesFor(String dungeon) {
        if (dungeon == null) return DungeonRules.DEFAULT;
        String key = normalize(dungeon);
        DungeonRules r = RULES.getOrDefault(key, DungeonRules.DEFAULT);
        // no Wayfinder / Dungeon Map: dungeons.json → unmapped (location names, matched normalised)
        for (String u : TelosData.DUNGEON_TABLE.get().unmapped()) if (normalize(u).equals(key)) return r.withMapped(false);
        return r;
    }

    static String normalize(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
