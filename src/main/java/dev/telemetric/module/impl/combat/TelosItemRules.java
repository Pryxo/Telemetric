package dev.telemetric.module.impl.combat;

import java.util.Map;
import java.util.Set;

/**
 * Telos item facts the item itself does not tell us, keyed by lower-case item name ({@code ItemInfo#nameKey()}).
 * Add an entry here when an item's lore range is missing or wrong, or its cooldown behaves differently.
 */
final class TelosItemRules {

    /** Centre marker drawn where an offset ability lands. */
    enum Marker { DOT, SQUARE }

    /**
     * @param range     radius in blocks (replaces the lore range)
     * @param offset    blocks in front of the player where the area is centred (0 = on the player)
     * @param marker    what marks the centre when {@code offset != 0}
     * @param drawRange false = only the centre marker (the area itself is the marker)
     */
    record RangeRule(float range, float offset, Marker marker, boolean drawRange) {}

    private static final Map<String, RangeRule> RANGES = Map.of(
            "seraph's essence", new RangeRule(6f, 3f, Marker.SQUARE, false),
            "ayahuasca flask", new RangeRule(8f, 0f, Marker.DOT, true),
            "event horizon", new RangeRule(8f, 0f, Marker.DOT, true));

    /** Items whose cooldown the server resets on every world change (the others keep running across worlds). */
    private static final Set<String> WORLD_RESET = Set.of("nature's gift");

    private TelosItemRules() {}

    /** Range override for an item, or null to use its lore range. */
    static RangeRule range(String nameKey) {
        return RANGES.get(nameKey);
    }

    static boolean resetsOnWorldChange(String nameKey) {
        return WORLD_RESET.contains(nameKey);
    }
}
