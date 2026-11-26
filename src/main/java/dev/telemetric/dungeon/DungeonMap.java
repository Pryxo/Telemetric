package dev.telemetric.dungeon;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Walkability map of the current dungeon, with one immutable {@link ChunkCells} per scanned chunk.
 * <p>
 * Safe for one writer (the client thread, scanning) and any number of readers (the path/analysis worker), since
 * entries are immutable and swapped atomically. Chunks stay after they unload, so routes
 * cover everything you've explored. The map is cleared when the dungeon changes.
 */
public final class DungeonMap {

    /** A pad's particles come from up to this many blocks above the feet height of its stand. */
    private static final int PAD_ABOVE = 3;

    private final ConcurrentHashMap<Long, ChunkCells> chunks = new ConcurrentHashMap<>();
    /** Jump pads seen so far: column key to the block Y of each pad's particle source (immutable arrays). */
    private final ConcurrentHashMap<Long, int[]> pads = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();

    /**
     * Records a jump pad whose particles come from block (x,y,z). Pads are kept until {@link #clear}.
     * @return true if it's new (this bumps the version so routes and rooms get recomputed)
     */
    public boolean addPad(int x, int y, int z) {
        long key = Cell.columnKey(x, z);
        int[] old = pads.get(key);
        if (old != null) for (int v : old) if (Math.abs(v - y) <= 1) return false;
        int[] next = old == null ? new int[]{y} : java.util.Arrays.copyOf(old, old.length + 1);
        next[next.length - 1] = y;
        pads.put(key, next);
        version.incrementAndGet();
        return true;
    }

    /** True if the stand at feet height y in this column is a jump pad (its particles start 0..3 blocks above). */
    public boolean isPad(int x, int y, int z) {
        int[] ys = pads.get(Cell.columnKey(x, z));
        if (ys == null) return false;
        for (int py : ys) if (py >= y && py <= y + PAD_ABOVE && standNear(x, py, z, 0, PAD_ABOVE) == y) return true;
        return false;
    }

    /** Particle source blocks ({@link Cell#pack}) of every recorded jump pad, whether or not it resolved to a stand (debug). */
    public long[] padSources() {
        it.unimi.dsi.fastutil.longs.LongArrayList out = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (var e : pads.entrySet()) {
            int x = Cell.columnX(e.getKey()), z = Cell.columnZ(e.getKey());
            for (int py : e.getValue()) out.add(Cell.pack(x, py, z));
        }
        return out.toLongArray();
    }

    /** Stores a scan. The version only changes if the walkability actually changed. */
    public void put(ChunkCells cells) {
        ChunkCells old = chunks.put(Cell.chunkKey(cells.chunkX, cells.chunkZ), cells);
        if (!cells.sameAs(old)) version.incrementAndGet();
    }

    public void clear() {
        chunks.clear();
        pads.clear();
        version.incrementAndGet();
    }

    /** Bumped on every change, as a cheap "did anything change" check for cached results. */
    public long version() {
        return version.get();
    }

    public int size() {
        return chunks.size();
    }

    public Collection<ChunkCells> chunks() {
        return chunks.values();
    }

    private ChunkCells at(int x, int z) {
        return chunks.get(Cell.chunkKey(x >> 4, z >> 4));
    }

    public boolean isKnown(int x, int z) {
        return at(x, z) != null;
    }

    /** A known column with no blocks at all (the void outside the dungeon pieces). Unknown columns aren't empty. */
    public boolean isEmptyColumn(int x, int z) {
        ChunkCells c = at(x, z);
        return c != null && c.isEmptyColumn(x & 15, z & 15);
    }

    public boolean isStand(int x, int y, int z) {
        ChunkCells c = at(x, z);
        return c != null && c.isStand(x & 15, z & 15, y);
    }

    public boolean isOpen(int x, int y, int z) {
        ChunkCells c = at(x, z);
        return c != null && c.isOpen(x & 15, z & 15, y);
    }

    /** True if nothing is above y in this column (roof top, open air). Unknown columns count as exposed. */
    public boolean isSkyExposed(int x, int y, int z) {
        ChunkCells c = at(x, z);
        return c == null || c.isSkyExposed(x & 15, z & 15, y);
    }

    public boolean isClimb(int x, int y, int z) {
        ChunkCells c = at(x, z);
        return c != null && c.isClimb(x & 15, z & 15, y);
    }

    /** Climbable blocks in a column within [lo, hi] (0 if unknown). */
    public int climbCount(int x, int z, int lo, int hi) {
        ChunkCells c = at(x, z);
        return c == null ? 0 : c.climbCount(x & 15, z & 15, lo, hi);
    }

    public int runBottom(int x, int z, int y) {
        ChunkCells c = at(x, z);
        return c == null ? Integer.MIN_VALUE : c.runBottom(x & 15, z & 15, y);
    }

    /** Standable feet-Y values of a column (ascending, empty if unknown). Don't modify the array. */
    public short[] stand(int x, int z) {
        ChunkCells c = at(x, z);
        return c == null ? new short[0] : c.stand(x & 15, z & 15);
    }

    /** Standable Y in this column closest to y within [y - down, y + up], or MIN_VALUE. */
    public int standNear(int x, int y, int z, int up, int down) {
        int best = Integer.MIN_VALUE;
        int bestDist = Integer.MAX_VALUE;
        for (short s : stand(x, z)) {
            if (s < y - down || s > y + up) continue;
            int d = Math.abs(s - y);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    /**
     * Where you end up if you're at feet height {@code y} in this column and let go: the standable cell at the bottom
     * of the open run (the bottom block itself, or the low block under it). MIN_VALUE if there's no floor (void) or y
     * isn't open.
     */
    public int landingY(int x, int z, int y) {
        int lo = runBottom(x, z, y);
        if (lo == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (isStand(x, lo, z)) return lo;
        if (isStand(x, lo - 1, z)) return lo - 1;
        return Integer.MIN_VALUE;
    }

    /** Feet height of a standable cell, +0.5 when standing inside a low block (slab). */
    public double floorHeight(int x, int y, int z) {
        return isOpen(x, y, z) ? y : y + 0.5;
    }
}
