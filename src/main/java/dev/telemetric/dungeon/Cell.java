package dev.telemetric.dungeon;

/**
 * Packs a block position into one {@code long} (x: 26 bits, z: 26 bits, y: 12 bits), using the same layout as
 * vanilla's {@code BlockPos#asLong}. Serves as the node id for path finding and hole detection.
 */
public final class Cell {

    private Cell() {}

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    public static int x(long cell) {
        return (int) (cell >> 38);
    }

    public static int y(long cell) {
        return (int) (cell << 52 >> 52);
    }

    public static int z(long cell) {
        return (int) (cell << 26 >> 38);
    }

    /** Key of a 16×16 chunk column. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Key of a single block column (x, z). */
    public static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int columnX(long key) {
        return (int) (key >> 32);
    }

    public static int columnZ(long key) {
        return (int) key;
    }
}
