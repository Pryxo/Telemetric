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
        }
        return into;
    }
}
