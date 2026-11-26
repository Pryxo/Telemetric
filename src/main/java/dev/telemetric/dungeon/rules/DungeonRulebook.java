package dev.telemetric.dungeon.rules;

import dev.telemetric.chat.ChatPattern;
import dev.telemetric.data.TelosData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which {@link DungeonRules} each dungeon uses. Names are matched normalised (lower case, only letters and digits,
 * so "Kobold's Den" = "kobolds den"), the same way as the Wayfinder blacklist.
 * <p>
 * To add a dungeon, write one {@code rule(...)} line that combines existing mechanics. A new mechanic needs a flag in
 * {@link DungeonRules} and its own class in {@code dungeon/}.
 */
public final class DungeonRulebook {

    private static final Map<String, DungeonRules> RULES = new LinkedHashMap<>();

    static {
        // Clear mobs: two "Keeper of Dusk". No boss drop.
        rule("Midnight Stronghold", DungeonRules.DEFAULT.withGoalMob("Keeper of Dusk").withBossLevel(false));
        // Clear mob: "Dyerftum Overlord". No boss drop, just the spawn and 2 clear mobs. Its boss bar ("The Creator") sits on
        // a mob at y 30 under the spawn, not on a boss level.
        rule("Illarius' Hideout", DungeonRules.DEFAULT.withGoalMob("Dyerftum Overlord").withBossLevel(false));
        // Books: each pick-up sends a random line plus "(x/5)", the line italic and "(x/5)" bold.
        rule("Resounding Ruins", rulesFor("Resounding Ruins").withChatCounter(new ChatCounter("Books", 5,
                ChatPattern.builder("Resounding Ruins book", "(?<line>[^()\\n]+) \\((?<n>\\d+)/(?<total>\\d+)\\)")
                        .run("[^()\\n]+ ", ChatPattern.ITALIC)
                        .run("\\(\\d+/\\d+\\)", ChatPattern.BOLD)
                        .build())));
        // Renown: all runs italic, only the colour differs. The goal is "Gain 110 renown to prove yourselves worthy before
        // Cherubim.", progress is "The garden recognizes your progress. (61/110)" every 50, and each pick-up spawns an
        // armor stand named "(75/110)" (gold/yellow, plain).
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
        // 3 puzzle rooms, 2 of which spawn the boss; no Wayfinder. The messages only differ by colour, with no bold or
        // italic, so each is one plain run.
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

    /** Rules for this dungeon, or {@link DungeonRules#DEFAULT} if it has none (or the name is unknown). */
    public static DungeonRules rulesFor(String dungeon) {
        if (dungeon == null) return DungeonRules.DEFAULT;
        String key = normalize(dungeon);
        DungeonRules r = RULES.getOrDefault(key, DungeonRules.DEFAULT);
        // no Wayfinder for dungeons that dungeons.json lists as unmapped (matched on the normalised name)
        for (String u : TelosData.DUNGEON_TABLE.get().unmapped()) if (normalize(u).equals(key)) return r.withMapped(false);
        return r;
    }

    static String normalize(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
