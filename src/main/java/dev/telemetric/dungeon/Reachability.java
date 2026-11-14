package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Which standable cells the player can actually walk to — the same moves as {@link PathFinder}.
 * <p>
 * The walk map also contains surfaces nobody can reach: the top of the dungeon's roof, ledges in the
 * walls, the inside of sealed rooms. Hole detection and the room grid only look at reachable cells, so the
 * roof never becomes a "floor" or a "boss drop". Callers keep a running union (the area reachable from
 * every position the player stood at), so dropping into an arena does not forget the level above it.
 */
public final class Reachability {

    /**
     * Deepest fall that still counts as "you can get there" (blocks). Routes stop at {@link PathFinder#MAX_FALL}
     * (fall damage), but Telos hides whole sections under a drop you are meant to take: Frozen Ruins has a pad room
     * at y 99 under 14-deep holes in the y 113 floor, its jump pads lead back up, and the boss shaft starts in it.
     * As deep as a pad throws you; deeper falls (boss shafts, ~70) stay holes, never "walkable".
     */
    static final int MAX_REACH_FALL = PathFinder.MAX_LIFT;
    private static final int[][] DIRS4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private Reachability() {}

    /**
     * Breadth-first flood from {@code start}: every cell reachable from it (at most {@code maxNodes}).
     * Empty if {@code start} is {@link Long#MIN_VALUE}.
     */
    public static LongOpenHashSet flood(DungeonMap map, long start, int maxNodes) {
        LongOpenHashSet into = new LongOpenHashSet();
        if (start == Long.MIN_VALUE) return into;
        into.add(start);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(start);
        long[] next = new long[PathFinder.NEIGHBOURS];
        double[] cost = new double[PathFinder.NEIGHBOURS];
        int added = 1;
        while (!queue.isEmpty() && added < maxNodes) {
            long cell = queue.dequeueLong();
            int n = PathFinder.neighbours(map, cell, next, cost);
            for (int i = 0; i < n; i++) {
                if (into.add(next[i])) {
                    added++;
                    queue.enqueue(next[i]);
                }
            }
            n = longFalls(map, cell, next);
            for (int i = 0; i < n; i++) {
                if (into.add(next[i])) {
                    added++;
                    queue.enqueue(next[i]);
                }
            }
        }
        return into;
    }

    /**
     * Falls deeper than {@link PathFinder#MAX_FALL} (up to {@link #MAX_REACH_FALL}) off the stand into a straight
     * neighbour column; the shorter ones are already walking moves. Fills {@code out}, returns how many.
     */
    static int longFalls(DungeonMap map, long cell, long[] out) {
        int x = Cell.x(cell), y = Cell.y(cell), z = Cell.z(cell);
        int count = 0;
        for (int[] d : DIRS4) {
            int nx = x + d[0], nz = z + d[1];
            if (!map.isKnown(nx, nz) || !map.isOpen(nx, y, nz) || !map.isOpen(nx, y + 1, nz)) continue;
            int land = map.landingY(nx, nz, y);
            if (land == Integer.MIN_VALUE) continue;
            int fall = y - land;
            if (fall > PathFinder.MAX_FALL && fall <= MAX_REACH_FALL) out[count++] = Cell.pack(nx, land, nz);
        }
        return count;
    }
}
