package dev.telemetric.dungeon;

import dev.telemetric.map.MapCut;
import dev.telemetric.map.MapMask;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * What the dungeon minimap shows: the floor you can walk on ({@link DungeonAnalysis#reachable()}) plus the walls
 * around it, cut just above head height per column so ceilings go and walls show as a slice. Everything else is left
 * out and the map draws clouds there. No rooms, no grid: the shape comes straight from the walkable cells and the
 * blocks around them.
 * <p>
 * Pure Java; built off the client thread.
 */
public final class WalkMask implements MapMask, MapCut {

    /** Columns this far from walkable floor still get drawn (walls). */
    static final int WALL = 2;
    /** One more ring, drawn half see-through, so the edge into the clouds is soft. */
    static final int EDGE = 3;
    private static final float EDGE_ALPHA = 0.45f;

    public static final WalkMask EMPTY = new WalkMask(new Long2IntOpenHashMap(), new Long2ByteOpenHashMap(),
            Integer.MIN_VALUE, Integer.MIN_VALUE);

    private final Long2IntOpenHashMap top;
    private final Long2ByteOpenHashMap dist;
    private final int max, min;

    private WalkMask(Long2IntOpenHashMap top, Long2ByteOpenHashMap dist, int max, int min) {
        this.top = top;
        this.dist = dist;
        this.max = max;
        this.min = min;
        top.defaultReturnValue(Integer.MIN_VALUE);
        dist.defaultReturnValue((byte) -1);
    }

    /**
     * @param reachable standable cells you can walk to (feet positions)
     * @param refY      where the viewer's feet are: a column with several walkable floors (a bridge over a hall) shows
     *                  the one closest to this height
     */
    public static WalkMask build(LongSet reachable, double refY) {
        if (reachable.isEmpty()) return EMPTY;
        Long2IntOpenHashMap stand = new Long2IntOpenHashMap(reachable.size() / 2);
        stand.defaultReturnValue(Integer.MIN_VALUE);
        for (LongIterator it = reachable.iterator(); it.hasNext(); ) {
            long c = it.nextLong();
            int y = Cell.y(c);
            long col = Cell.columnKey(Cell.x(c), Cell.z(c));
            int cur = stand.get(col);
            if (cur == Integer.MIN_VALUE || Math.abs(y - refY) < Math.abs(cur - refY)) stand.put(col, y);
        }
        Long2IntOpenHashMap top = new Long2IntOpenHashMap(stand.size() * 2);
        Long2ByteOpenHashMap dist = new Long2ByteOpenHashMap(stand.size() * 2);
        top.defaultReturnValue(Integer.MIN_VALUE);
        dist.defaultReturnValue((byte) -1);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE;
        for (var e : stand.long2IntEntrySet()) {
            int t = e.getIntValue() + 1; // feet and head layer stay, the ceiling above goes
            top.put(e.getLongKey(), t);
            dist.put(e.getLongKey(), (byte) 0);
            queue.enqueue(e.getLongKey());
            max = Math.max(max, t);
            min = Math.min(min, t);
        }
        while (!queue.isEmpty()) {
            long col = queue.dequeueLong();
            int d = dist.get(col);
            if (d >= EDGE) continue;
            int x = Cell.columnX(col), z = Cell.columnZ(col), t = top.get(col);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    long n = Cell.columnKey(x + dx, z + dz);
                    int nd = dist.get(n);
                    if (nd == -1) {
                        dist.put(n, (byte) (d + 1));
                        top.put(n, t);
                        queue.enqueue(n);
                    } else if (nd == d + 1 && t > top.get(n)) {
                        top.put(n, t); // a wall between two levels is cut at the higher one
                    }
                }
            }
        }
        return new WalkMask(top, dist, max, min);
    }

    @Override
    public float alpha(int x, int y, int z) {
        int d = dist.get(Cell.columnKey(x, z));
        if (d < 0) return 0f;
        return d <= WALL ? 1f : EDGE_ALPHA;
    }

    @Override
    public int top(int x, int z) {
        return top.get(Cell.columnKey(x, z));
    }

    @Override
    public int max() {
        return max;
    }

    /** Lowest cut anywhere (MIN_VALUE when empty). */
    public int min() {
        return min;
    }

    public boolean isEmpty() {
        return top.isEmpty();
    }

    /** True if column (x, z) is walkable floor (not a wall or edge column). */
    public boolean isFloor(int x, int z) {
        return dist.get(Cell.columnKey(x, z)) == 0;
    }

    /** Column keys whose cut or visibility differs from {@code old}. */
    public LongArrayList changedFrom(WalkMask old) {
        LongArrayList out = new LongArrayList();
        for (var e : top.long2IntEntrySet()) {
            long k = e.getLongKey();
            if (old.top.get(k) != e.getIntValue() || old.dist.get(k) != dist.get(k)) out.add(k);
        }
        for (var e : old.top.long2IntEntrySet()) if (!top.containsKey(e.getLongKey())) out.add(e.getLongKey());
        return out;
    }
}
