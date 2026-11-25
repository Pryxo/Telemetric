package dev.telemetric.map;

/**
 * How one block state looks on the map: a 4×4 colour grid for its top face and one for its side face (sampled from
 * the block's texture, already tinted), plus how rays treat it. Built by {@code game/MapAdapter}.
 *
 * @param top      16 ARGB colours, row-major (z then x) — the top face as seen from above
 * @param side     16 ARGB colours, row-major (top row first) — the south face
 * @param kind     one of the KIND constants
 * @param emission block light 0–15 (glowing blocks stay bright in shadow)
 */
public record BlockLook(int[] top, int[] side, byte kind, int emission) {

    /** Rays pass through (air, barriers, light blocks, invisible blocks). */
    public static final byte AIR = 0;
    /** Rays stop here. */
    public static final byte SOLID = 1;
    /** Water: tints what's under it, deeper water hides more. */
    public static final byte WATER = 2;
    /** Glass, ice and other see-through full blocks: a light tint, the ray goes on. */
    public static final byte GLASS = 3;
    /** Small things without collision (flowers, grass, torches, carpets): a partial tint over what's below. */
    public static final byte DECOR = 4;

    public static final BlockLook NONE = new BlockLook(new int[16], new int[16], AIR, 0);

    public static BlockLook flat(int top, int side, byte kind, int emission) {
        int[] t = new int[16], s = new int[16];
        java.util.Arrays.fill(t, top);
        java.util.Arrays.fill(s, side);
        return new BlockLook(t, s, kind, emission);
    }
}
