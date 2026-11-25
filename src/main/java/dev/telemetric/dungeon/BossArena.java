package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.List;

/**
 * The boss level: the open space you land in when you take the boss drop.
 * <p>
 * Telos boss arenas sit under (or next to) the dungeon pieces. Their ledges are standable, have a ceiling over them,
 * and are often only a few blocks below the main floor (Corvus Crypt: ledges at y 85–92, dungeon level 100). So
 * they'd count as dungeon floor and give us "holes" at the ledge edges that score higher than the real drop.
 * <p>
 * So when we have a boss anchor (the boss-bar mob's feet, or the boss's nameplate when the boss itself is
 * invisible), we flood the open air starting from the floor under the anchor. The flood is capped 2 blocks under the
 * main level so it can't climb a drop shaft back up into the rooms. The boss drop is then the drop with the highest
 * rim that falls into that air (ledges may break the fall). Without an anchor we fall back to the deep drop whose rim
 * is on the main level, flooded from where it lands. Every standable cell in that air is the boss level.
 */
public final class BossArena {

    /** The boss drop's rim can be at most this far below the main floor level (Corvus: rim 105, floor 100). */
    public static final int MAIN_BAND = 6;
    /** Flood this far below the lowest landing, to cover pits in the arena floor. */
    private static final int BELOW_LANDING = 4;
    /** Safety cap on flooded air cells. */
    private static final int MAX_CELLS = 2_000_000;
    private static final int[][] DIRS6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private BossArena() {}

    /** How far below the anchor (boss feet or nameplate) to look for the arena floor. */
    private static final int ANCHOR_DOWN = 8;
    /** Columns this far around the anchor are tried for the floor, in case the boss is over a pit or the plate is off-centre. */
    private static final int ANCHOR_RADIUS = 2;

    /**
     * Standable cell under a boss anchor (boss-bar mob feet or the boss nameplate), or MIN_VALUE if that part of the
     * map isn't scanned yet. It's the highest stand between 1 above and {@link #ANCHOR_DOWN} below the anchor, looking
     * at the anchor's own column first and then the nearest rings of columns around it.
     */
    public static long anchorCell(DungeonMap map, Point anchor) {
        if (anchor == null) return Long.MIN_VALUE;
        int ax = (int) Math.floor(anchor.x()), ay = (int) Math.floor(anchor.y()), az = (int) Math.floor(anchor.z());
        for (int r = 0; r <= ANCHOR_RADIUS; r++) {
            int bestY = Integer.MIN_VALUE, bx = 0, bz = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // only ring r
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
     * Open air of the boss level, flooded from the arena floor under the boss anchor ({@link #anchorCell}) and capped
     * 2 blocks under the main level so it can't climb a drop shaft into the rooms. The way down doesn't have to be one
     * free fall (Treasure Cave has ledges and chambers on the way); everything open between the drop and the arena
     * floor is part of this air.
     */
    public static LongOpenHashSet floodFromAnchor(DungeonMap map, long anchor, int level) {
        if (anchor == Long.MIN_VALUE) return new LongOpenHashSet();
        int ax = Cell.x(anchor), ay = Cell.y(anchor), az = Cell.z(anchor);
        int top = level == Integer.MIN_VALUE ? ay + 48 : level - 2;
        if (ay > top) return new LongOpenHashSet(); // anchor is on the main level, so there's no arena under the rooms
        // a feet cell inside a low block (slab, carpet) isn't open, so seed the cell above it
        long seed = map.isOpen(ax, ay, az) ? anchor : Cell.pack(ax, ay + 1, az);
        if (!map.isOpen(Cell.x(seed), Cell.y(seed), Cell.z(seed))) return new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        q.enqueue(seed);
        return floodAir(map, q, ay - BELOW_LANDING, top);
    }

    /**
     * Picks the boss drop for the given boss-level air. It's the drop with the highest rim (the arena's own ledges drop
     * into it too, and the way in is the top one) that has a column whose fall from the rim lands in that air at least
     * 3 blocks down. The landing can be anywhere in the air, not just on the arena floor, so a fall broken by ledges
     * still counts. Returns null if nothing qualifies yet, e.g. because its rim isn't scanned. We don't guess here; a
     * wrong drop is worse than none.
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

    /** Fallback before we've seen any boss anchor: the best drop whose rim is on the main level, or null. */
    public static Hole pickMainLevelDrop(List<Hole> holes, int level) {
        if (level == Integer.MIN_VALUE) return null;
        for (Hole h : holes) if (h.y() >= level - MAIN_BAND) return h;
        return null;
    }

    /**
     * Fallback flood when there's no boss anchor: open-air cells under {@code drop}, seeded where its columns land at
     * least {@code minDepth} down. Empty if it lands nowhere.
     *
     * @param level    dungeon level ({@link DungeonLevel#of})
     * @param minDepth minimum drop depth (shallower landings aren't the arena)
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

    /** 6-neighbour flood of open air within [bottom, top], starting from the seeds. Void and unknown columns stop it. */
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

    /** True if a standable cell is in the boss level's air (its feet cell, or the cell above for a stand inside a low block). */
    public static boolean contains(LongOpenHashSet air, long cell) {
        return air.contains(cell) || air.contains(Cell.pack(Cell.x(cell), Cell.y(cell) + 1, Cell.z(cell)));
    }
}
