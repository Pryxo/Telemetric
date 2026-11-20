package dev.telemetric.stash;

/** A container click the overlay can send for a slot (mapped to the game's click types in {@code StashAdapter}). */
public enum SlotAction {
    /** Left or right click: pick up, place, swap or split (button 0 / 1). */
    PICKUP,
    /** Shift-click. */
    QUICK_MOVE,
    /** A hotbar number key (button = hotbar 0–8) or the offhand swap (button 40). */
    SWAP,
    /** The drop key (button 0 drops one, 1 the whole stack). */
    THROW,
    /** Double-click: collects matching items onto the cursor. */
    PICKUP_ALL
}
