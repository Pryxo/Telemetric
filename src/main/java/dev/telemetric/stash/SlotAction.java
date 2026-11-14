package dev.telemetric.stash;

/** A container click the overlay can send for a slot (mapped to the game's click types in {@code StashAdapter}). */
public enum SlotAction {
    /** Left/right click: pick up, place, swap or split (button 0 / 1). */
    PICKUP,
    /** Shift-click. */
    QUICK_MOVE,
    /** Hotbar number key (button = hotbar 0–8) or offhand swap (button 40). */
    SWAP,
    /** Drop key (button 0 = one, 1 = whole stack). */
    THROW,
    /** Double-click: collect matching items onto the cursor. */
    PICKUP_ALL
}
