package dev.telemetric.stash;

import dev.telemetric.game.GameItem;
import dev.telemetric.game.StashAdapter;

import java.util.Arrays;

/**
 * One saved stash page: the 45 item slots (5 rows x 9) as serialized item JSON ({@code null} means empty).
 * Items are decoded lazily for drawing ({@link #item(int)}), because decoding needs the world's registries.
 */
public final class StashPage {

    public static final int SLOTS = 45;

    private final String[] slots;
    private final long savedAt;
    private GameItem[] decoded;

    public StashPage(String[] slots, long savedAt) {
        if (slots.length != SLOTS) throw new IllegalArgumentException("A stash page has " + SLOTS + " slots");
        this.slots = slots.clone();
        this.savedAt = savedAt;
    }

    public String slot(int i) { return slots[i]; }

    public long savedAt() { return savedAt; }

    public boolean isEmpty() {
        for (String s : slots) if (s != null) return false;
        return true;
    }

    /** True if both pages hold exactly the same items. */
    public boolean sameContent(StashPage other) {
        return other != null && Arrays.equals(slots, other.slots);
    }

    /** The decoded item in slot {@code i}, or {@code null} if it's empty or can't be decoded. */
    public GameItem item(int i) {
        if (slots[i] == null) return null;
        if (decoded == null) decoded = new GameItem[SLOTS];
        if (decoded[i] == null) decoded[i] = StashAdapter.decode(slots[i]);
        return decoded[i];
    }
}
