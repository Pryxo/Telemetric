package dev.telemetric.dungeon.rules;

import dev.telemetric.chat.ChatPattern;

import java.util.regex.Pattern;

/**
 * A dungeon objective counted from server messages ("(2/5)" after picking up a book), shown as a Dungeon Info row.
 * The count only ever goes up (it's the highest value seen from any source).
 *
 * @param label        row label ("Books")
 * @param total        expected total before the first message (the message's own total wins once one arrives);
 *                     0 means unknown, shown as "???" until a message names it
 * @param pattern      trusted progress message with named groups {@code n} (count so far) and {@code total}
 * @param totalPattern optional trusted message that only names the goal, group {@code total} (null for none)
 * @param nameplate    optional regex for an armor-stand nameplate the server spawns with the count, group
 *                     {@code n}. The whole plain name has to match. Its total is ignored, only chat sets the total
 */
public record ChatCounter(String label, int total, ChatPattern pattern, ChatPattern totalPattern, Pattern nameplate) {

    public ChatCounter(String label, int total, ChatPattern pattern) {
        this(label, total, pattern, null, null);
    }
}
