package dev.telemetric.map;

import java.util.Arrays;

/**
 * A copy of one chunk's blocks as look ids ({@link VoxelStore#look}), 0 = air. Only the sections between the lowest
 * and highest non-empty one are kept. Immutable once built, so the bake thread can read it freely.
 */
public final class VoxelChunk {

    public final int chunkX, chunkZ;
    /** Lowest block Y stored. */
    public final int minY;
    /** Number of block layers stored (0 for an empty chunk). */
    public final int height;
    /** index = ((y - minY) << 8) | (z << 4) | x */
    private final short[] ids;

    public VoxelChunk(int chunkX, int chunkZ, int minY, int height, short[] ids) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.height = height;
        this.ids = ids;
    }

    public static VoxelChunk empty(int chunkX, int chunkZ) {
        return new VoxelChunk(chunkX, chunkZ, 0, 0, new short[0]);
    }

    /** Look id at local x/z and world y; 0 outside the stored layers. */
    public int id(int lx, int y, int lz) {
        int dy = y - minY;
        if (dy < 0 || dy >= height) return 0;
        return ids[(dy << 8) | (lz << 4) | lx];
    }

    /** The raw look ids ({@code ((y - minY) << 8) | (z << 4) | x}). Read only. */
    public short[] ids() {
        return ids;
    }

    public int maxY() {
        return minY + height - 1;
    }

    public boolean sameAs(VoxelChunk o) {
        return o != null && o.minY == minY && o.height == height && Arrays.equals(o.ids, ids);
    }
}
