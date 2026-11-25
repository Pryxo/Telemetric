package dev.telemetric.dungeon;

import dev.telemetric.dungeon.rules.DungeonRules;

/**
 * What we know about the dungeon before analysing it.
 *
 * @param hasSpawn whether the spawn position is known
 * @param spawnX   block X where the dungeon starts (its return portal, or where the player entered)
 * @param spawnZ   block Z of the start
 * @param spawnY   feet block Y of the start, which is the dungeon level (MIN_VALUE if unknown)
 * @param rules    this dungeon's mechanics ({@link dev.telemetric.dungeon.rules.DungeonRulebook})
 */
public record DungeonHint(boolean hasSpawn, int spawnX, int spawnZ, int spawnY, DungeonRules rules) {

    public static final DungeonHint NONE = new DungeonHint(false, 0, 0, Integer.MIN_VALUE, DungeonRules.DEFAULT);
}
