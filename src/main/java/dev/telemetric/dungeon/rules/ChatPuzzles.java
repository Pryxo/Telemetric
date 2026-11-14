package dev.telemetric.dungeon.rules;

import dev.telemetric.chat.ChatPattern;

/**
 * Puzzle rooms tracked from server messages (Dreadwood Thicket: "X is attempting the Wave Room!", "... has
 * completed ...", "The Colour Room has reset!"), shown in Dungeon Info: one row per puzzle
 * ({@code dungeon/PuzzleBoard}). Room names are unknown until the first message about them, so rows start as "???".
 *
 * @param label    label ("Completed Room"; not shown since 0.19.6)
 * @param slots    number of puzzle rooms in the dungeon (rows shown, max completed count)
 * @param attempt  attempt message, named groups {@code player} and {@code room}
 * @param complete completion message, named groups {@code player} and {@code room}
 * @param reset    reset / fail message, named group {@code room}
 */
public record ChatPuzzles(String label, int slots, ChatPattern attempt, ChatPattern complete, ChatPattern reset) {
}
