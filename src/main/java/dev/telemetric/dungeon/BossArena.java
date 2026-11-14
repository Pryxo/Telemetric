package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.List;

/**
 * The boss level: the open space you land in when you take the boss drop.
 * <p>
 * Telos boss arenas lie under (or next to) the dungeon pieces. Their ledges are standable, under a ceiling and
 * often less than {@code RoomLayout}'s boss cutoff below the main floor (Corvus Crypt: ledges y 85–92, room
 * level 100), so they would count as dungeon floor: fake low rooms, false room links and "holes" at the ledge
 * edges that outscore the real drop. Instead, with a boss anchor (the boss-bar mob's feet, or the boss's
 * nameplate when the boss itself is invisible): flood the open air from the floor under the anchor, capped
 * 2 blocks under the main level so the flood cannot climb a drop shaft back into the rooms; the boss drop is
 * the highest-rim drop that falls into that air (the fall may be broken by ledges). Without an anchor
 * (fallback): the deep drop whose rim is on the main level, flooded from where it lands. Every standable
 * cell in that air is the boss level.
 */
public final class BossArena {

    /** The boss drop's rim must be at most this far below the main floor level (Corvus rim 105, floor 100). */
    public static final int MAIN_BAND = 6;
    /** Flood this far below the lowest landing (pits in the arena floor). */
    private static final int BELOW_LANDING = 4;
    /** Safety cap on flooded air cells. */
    private static final int MAX_CELLS = 2_000_000;
    private static final int[][] DIRS6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private BossArena() {}

    /** How far below the anchor (boss feet / nameplate) to look for the arena floor. */
    private static final int ANCHOR_DOWN = 8;
    /** Columns this far around the anchor are tried for the floor (boss over a pit / plate off-centre). */
    private static final int ANCHOR_RADIUS = 2;

    /**
     * Standable cell under a boss anchor (boss-bar mob feet or the boss nameplate), or MIN_VALUE if that
     * part of the map is not scanned yet: the highest stand at most 1 above and {@link #ANCHOR_DOWN} below
     * the anchor, in the anchor's column first, then the nearest rings of columns around it.
     */
    public static long anchorCell(DungeonMap map, Point anchor) {
        if (anchor == null) return Long.MIN_VALUE;
        int ax = (int) Math.floor(anchor.x()), ay = (int) Math.floor(anchor.y()), az = (int) Math.floor(anchor.z());
        for (int r = 0; r <= ANCHOR_RADIUS; r++) {
            int bestY = Integer.MIN_VALUE, bx = 0, bz = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring r only
                    for (short s : map.stand(ax + dx, az + dz)) {
                        if (s <= ay + 1 && s >= ay - ANCHOR_DOWN && s > bestY) {
                            bestY = s;
                            bx = ax + dx;
                            bz = az + dz;
                        }
                    }
                }
            }
            if (bestY != Integer.MIN_VALUE) return Cell.pack(bx, bestY, bz);
        }
        return Long.MIN_VALUE;
    }

    /**
     * Open air of the boss level, flooded from the arena floor under the boss anchor ({@link #anchorCell}),
     * capped 2 blocks under the main level so it cannot climb a drop shaft into the rooms. The way down need
     * not be one free fall (Treasure Cave: ledges / chambers on the way): everything open between the drop
     * and the arena floor is part of this air.
     */
    public static LongOpenHashSet floodFromAnchor(DungeonMap map, long anchor, int level) {
        if (anchor == Long.MIN_VALUE) return new LongOpenHashSet();
        int ax = Cell.x(anchor), ay = Cell.y(anchor), az = Cell.z(anchor);
        int top = level == Integer.MIN_VALUE ? ay + 48 : level - 2;
        if (ay > top) return new LongOpenHashSet(); // anchor on the main level: no arena under the rooms
        // a feet cell inside a low block (slab / carpet) is not open: seed the cell above
        long seed = map.isOpen(ax, ay, az) ? anchor : Cell.pack(ax, ay + 1, az);
        if (!map.isOpen(Cell.x(seed), Cell.y(seed), Cell.z(seed))) return new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        q.enqueue(seed);
        return floodAir(map, q, ay - BELOW_LANDING, top);
    }

    /**
     * The boss drop given the boss level's air: the drop with the highest rim (the arena's own ledges also
     * drop into it, the way in is the top one) that has a column whose fall from the rim lands in that air
     * at least 3 blocks down. Anywhere in it, not just on the arena floor, so a fall broken by ledges counts.
     * Null if none does yet (its rim may still be unscanned): no guessing, a wrong drop is worse than none.
     */
    public static Hole pickDropInto(DungeonMap map, List<Hole> holes, LongOpenHashSet air) {
        if (air.isEmpty()) return null;
        Hole best = null;
        for (Hole h : holes) {
            if ((best == null || h.y() > best.y()) && landsIn(map, h, air)) best = h;
        }
        return best;
    }

    private static boolean landsIn(DungeonMap map, Hole h, LongOpenHashSet air) {
        for (int x = h.minX(); x <= h.maxX(); x++) {
            for (int z = h.minZ(); z <= h.maxZ(); z++) {
                int land = map.landingY(x, z, h.y());
                if (land != Integer.MIN_VALUE && h.y() - land >= 3 && contains(air, Cell.pack(x, land, z))) return true;
            }
        }
        return false;
    }

    /** Fallback before any boss anchor was seen: the best drop whose rim is on the main level, or null. */
    public static Hole pickMainLevelDrop(List<Hole> holes, int level) {
        if (level == Integer.MIN_VALUE) return null;
        for (Hole h : holes) if (h.y() >= level - MAIN_BAND) return h;
        return null;
    }

    /**
     * Fallback flood (no boss anchor): open-air cells under {@code drop}, seeded where its columns land at
     * least {@code minDepth} down (empty if it lands nowhere).
     *
     * @param level    dungeon level ({@link RoomLayout#level})
     * @param minDepth minimum drop depth (landings shallower than this are not the arena)
     */
    public static LongOpenHashSet flood(DungeonMap map, Hole drop, int level, int minDepth) {
        if (drop == null) return new LongOpenHashSet();
        int top = Math.min(level, drop.y()) - 2;
        LongArrayFIFOQueue seeds = new LongArrayFIFOQueue();
        int lowest = Integer.MAX_VALUE;
        for (int x = drop.minX(); x <= drop.maxX(); x++) {
            for (int z = drop.minZ(); z <= drop.maxZ(); z++) {
                int land = map.landingY(x, z, drop.y());
                if (land == Integer.MIN_VALUE || drop.y() - land < minDepth || land > top) continue;
                lowest = Math.min(lowest, land);
                seeds.enqueue(Cell.pack(x, land, z));
            }
        }
        if (seeds.isEmpty()) return new LongOpenHashSet();
        return floodAir(map, seeds, lowest - BELOW_LANDING, top);
    }

    /** 6-neighbour flood of open air within [bottom, top] from the seeds; void / unknown columns stop it. */
    private static LongOpenHashSet floodAir(DungeonMap map, LongArrayFIFOQueue seeds, int bottom, int top) {
        LongOpenHashSet air = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        while (!seeds.isEmpty()) {
            long c = seeds.dequeueLong();
            if (air.add(c)) queue.enqueue(c);
        }
        while (!queue.isEmpty() && air.size() < MAX_CELLS) {
            long cell = queue.dequeueLong();
            int x = Cell.x(cell), y = Cell.y(cell), z = Cell.z(cell);
            for (int[] d : DIRS6) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (ny < bottom || ny > top) continue;
                long n = Cell.pack(nx, ny, nz);
                if (air.contains(n)) continue;
                // void columns (outside the pieces) would carry the flood around the whole dungeon
                if (!map.isKnown(nx, nz) || map.isEmptyColumn(nx, nz) || !map.isOpen(nx, ny, nz)) continue;
                air.add(n);
                queue.enqueue(n);
            }
        }
        return air;
    }

    /** Standable cell in the boss level's air (its feet cell, or the cell above for a stand inside a low block). */
    public static boolean contains(LongOpenHashSet air, long cell) {
        return air.contains(cell) || air.contains(Cell.pack(Cell.x(cell), Cell.y(cell) + 1, Cell.z(cell)));
    }
}
