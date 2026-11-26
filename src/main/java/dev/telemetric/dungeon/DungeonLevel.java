package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;
import java.util.function.LongPredicate;

/** The dungeon level: the one height every "main level" check uses (boss drop, boss air). */
public final class DungeonLevel {

    private DungeonLevel() {}

    /**
     * The start's feet Y ({@link DungeonHint#spawnY}, the return portal), or else the median floor height of the
     * scanned dungeon. MIN_VALUE when neither is known.
     */
    public static int of(DungeonMap map, LongPredicate floor, DungeonHint hint) {
        if (hint.spawnY() != Integer.MIN_VALUE) return hint.spawnY();
        return median(topFloors(map, floor));
    }

    /** Median of each column's highest floor stand, or MIN_VALUE when too little is scanned. */
    static int median(Long2IntOpenHashMap topFloors) {
        if (topFloors.size() < 64) return Integer.MIN_VALUE;
        int[] heights = topFloors.values().toIntArray();
        Arrays.sort(heights);
        return heights[heights.length / 2];
    }

    /** Maps walkable columns to the column's highest floor stand. */
    static Long2IntOpenHashMap topFloors(DungeonMap map, LongPredicate floor) {
        Long2IntOpenHashMap all = new Long2IntOpenHashMap();
        for (ChunkCells chunk : map.chunks()) {
            int bx = chunk.chunkX << 4, bz = chunk.chunkZ << 4;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    short[] s = chunk.stand(lx, lz);
                    if (s.length == 0) continue;
                    int x = bx + lx, z = bz + lz;
                    int top = Integer.MIN_VALUE;
                    for (short y : s) if (floor.test(Cell.pack(x, y, z))) top = y;
                    if (top != Integer.MIN_VALUE) all.put(Cell.columnKey(x, z), top);
                }
            }
        }
        return all;
    }
}
