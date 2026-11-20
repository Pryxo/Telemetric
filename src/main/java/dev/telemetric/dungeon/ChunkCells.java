package dev.telemetric.dungeon;

/**
 * Immutable walkability summary of one 16×16 chunk column. It's the only thing the dungeon code keeps per chunk: a
 * few shorts per block column instead of every block.
 * <p>
 * For each block column it stores:
 * <ul>
 *   <li>{@code stand}: ascending feet-Y values a player can stand at (2 blocks of head room, floor below);</li>
 *   <li>{@code runs}: ascending {@code [lo, hi]} pairs of vertically contiguous passable blocks
 *       ({@link #OPEN} or {@link #CLIMB}), used for head-room checks and to find where you land when you
 *       walk off an edge;</li>
 *   <li>{@code climb}: {@code [lo, hi]} pairs of contiguous {@link #CLIMB} blocks (ladders, vines).</li>
 * </ul>
 * You can stand on a passable block with 2 head room over a {@link #SOLID} floor, on a {@link #LOW} block with 2
 * above it, on any {@link #CLIMB} block (you hold on to the ladder), and in the open block right above a ladder's
 * top. {@link #build} creates it from per-block classes; the MC adapter does the block reading.
 */
public final class ChunkCells {

    /** No collision, you walk or fall through it (air, plants, water, light, open doors...). */
    public static final byte OPEN = 0;
    /** Collision top at most ½ block (bottom slab, carpet, snow layer). You stand inside this block. */
    public static final byte LOW = 1;
    /** A normal solid block, which is a floor for the block above. */
    public static final byte SOLID = 2;
    /** Collision taller than a block (fence, wall). It blocks movement but isn't a usable floor. */
    public static final byte TALL = 3;
    /** Never walk into it (lava, fire). */
    public static final byte HAZARD = 4;
    /** Climbable with no real collision (ladder, vine, scaffolding). You move up and down inside it and never fall. */
    public static final byte CLIMB = 5;

    private static final short[] NONE = new short[0];

    public final int chunkX;
    public final int chunkZ;
    /** Y just above the highest scanned block. A run reaching past it is open to the sky. */
    private final int topY;
    /** Y of the lowest scanned block. A column that's one open run from here up has no blocks at all. */
    private final int baseY;
    private final short[][] stand; // index (lz << 4) | lx
    private final short[][] runs;
    private final short[][] climb;

    private ChunkCells(int chunkX, int chunkZ, int baseY, int topY, short[][] stand, short[][] runs, short[][] climb) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.baseY = baseY;
        this.topY = topY;
        this.stand = stand;
        this.runs = runs;
        this.climb = climb;
    }

    private static boolean passable(byte c) {
        return c == OPEN || c == CLIMB;
    }

    /**
     * @param baseY   world Y of index 0 in every column array
     * @param classes 256 columns (index {@code (lz << 4) | lx}) of block classes, all the same length;
     *                blocks outside the array count as {@link #OPEN}
     */
    public static ChunkCells build(int chunkX, int chunkZ, int baseY, byte[][] classes) {
        short[][] stand = new short[256][];
        short[][] runs = new short[256][];
        short[][] climb = new short[256][];
        short[] standBuf = new short[64];
        short[] runBuf = new short[64];
        short[] climbBuf = new short[64];
        for (int col = 0; col < 256; col++) {
            byte[] c = classes[col];
            int h = c == null ? 0 : c.length;
            int sn = 0;
            int rn = 0;
            int cn = 0;
            int runStart = Integer.MIN_VALUE;
            int climbStart = Integer.MIN_VALUE;
            for (int i = 0; i < h; i++) {
                byte here = c[i];
                byte below = i > 0 ? c[i - 1] : OPEN;
                byte up1 = i + 1 < h ? c[i + 1] : OPEN;
                byte up2 = i + 2 < h ? c[i + 2] : OPEN;
                boolean standable = (passable(here) && passable(up1) && below == SOLID)
                        || (here == LOW && passable(up1) && passable(up2))
                        || (here == CLIMB && passable(up1))
                        || (here == OPEN && below == CLIMB && passable(up1));
                if (standable) {
                    if (sn == standBuf.length) standBuf = java.util.Arrays.copyOf(standBuf, sn * 2);
                    standBuf[sn++] = (short) (baseY + i);
                }
                if (here == CLIMB) {
                    if (climbStart == Integer.MIN_VALUE) climbStart = i;
                } else if (climbStart != Integer.MIN_VALUE) {
                    if (cn + 2 > climbBuf.length) climbBuf = java.util.Arrays.copyOf(climbBuf, climbBuf.length * 2);
                    climbBuf[cn++] = (short) (baseY + climbStart);
                    climbBuf[cn++] = (short) (baseY + i - 1);
                    climbStart = Integer.MIN_VALUE;
                }
                if (passable(here)) {
                    if (runStart == Integer.MIN_VALUE) runStart = i;
                } else if (runStart != Integer.MIN_VALUE) {
                    if (rn + 2 > runBuf.length) runBuf = java.util.Arrays.copyOf(runBuf, runBuf.length * 2);
                    runBuf[rn++] = (short) (baseY + runStart);
                    runBuf[rn++] = (short) (baseY + i - 1);
                    runStart = Integer.MIN_VALUE;
                }
            }
            if (runStart != Integer.MIN_VALUE) {
                if (rn + 2 > runBuf.length) runBuf = java.util.Arrays.copyOf(runBuf, runBuf.length * 2);
                runBuf[rn++] = (short) (baseY + runStart);
                // open to the sky, so extend it well up to make head-room checks pass
                runBuf[rn++] = (short) Math.min(Short.MAX_VALUE, baseY + h + 64);
            }
            if (climbStart != Integer.MIN_VALUE) {
                if (cn + 2 > climbBuf.length) climbBuf = java.util.Arrays.copyOf(climbBuf, climbBuf.length * 2);
                climbBuf[cn++] = (short) (baseY + climbStart);
                climbBuf[cn++] = (short) (baseY + h - 1);
            }
            stand[col] = sn == 0 ? NONE : java.util.Arrays.copyOf(standBuf, sn);
            runs[col] = rn == 0 ? NONE : java.util.Arrays.copyOf(runBuf, rn);
            climb[col] = cn == 0 ? NONE : java.util.Arrays.copyOf(climbBuf, cn);
        }
        int top = baseY;
        for (byte[] c : classes) if (c != null) top = Math.max(top, baseY + c.length);
        return new ChunkCells(chunkX, chunkZ, baseY, top, stand, runs, climb);
    }

    static int index(int lx, int lz) {
        return (lz << 4) | lx;
    }

    /** Standable feet-Y values of a column (ascending, never null). Don't modify the array. */
    public short[] stand(int lx, int lz) {
        return stand[index(lx, lz)];
    }

    public boolean isStand(int lx, int lz, int y) {
        for (short s : stand[index(lx, lz)]) {
            if (s == y) return true;
            if (s > y) return false;
        }
        return false;
    }

    /**
     * True if nothing is above y in this column, i.e. it's open to the sky: the top of a dungeon's roof, or an open-air
     * area. Unknown or empty columns count as exposed.
     */
    public boolean isSkyExposed(int lx, int lz, int y) {
        short[] r = runs[index(lx, lz)];
        if (r.length == 0) return true;
        for (int i = 0; i < r.length; i += 2) {
            if (y < r[i]) return false;
            if (y <= r[i + 1]) return r[i + 1] >= topY;
        }
        return false;
    }

    /** True if the block at y is climbable (ladder, vine...). */
    public boolean isClimb(int lx, int lz, int y) {
        short[] r = climb[index(lx, lz)];
        for (int i = 0; i < r.length; i += 2) {
            if (y < r[i]) return false;
            if (y <= r[i + 1]) return true;
        }
        return false;
    }

    /** Number of climbable blocks of a column within [lo, hi]. */
    public int climbCount(int lx, int lz, int lo, int hi) {
        short[] r = climb[index(lx, lz)];
        int n = 0;
        for (int i = 0; i < r.length; i += 2) {
            int a = Math.max(lo, r[i]), b = Math.min(hi, r[i + 1]);
            if (b >= a) n += b - a + 1;
        }
        return n;
    }

    /** True if you can move through the block at y (no collision, or climbable). Columns with no blocks at all are open everywhere. */
    public boolean isOpen(int lx, int lz, int y) {
        short[] r = runs[index(lx, lz)];
        if (r.length == 0) return stand[index(lx, lz)].length == 0;
        for (int i = 0; i < r.length; i += 2) {
            if (y < r[i]) return false;
            if (y <= r[i + 1]) return true;
        }
        return false;
    }

    /** Lowest Y of the open run that contains y, or {@link Integer#MIN_VALUE} if y isn't open. */
    public int runBottom(int lx, int lz, int y) {
        short[] r = runs[index(lx, lz)];
        for (int i = 0; i < r.length; i += 2) {
            if (y < r[i]) return Integer.MIN_VALUE;
            if (y <= r[i + 1]) return r[i];
        }
        return Integer.MIN_VALUE;
    }

    /**
     * True if the column has no blocks at all (the void around a dungeon). Dungeon pieces are solid boxes (floor slab
     * plus roof), so the edge between empty and non-empty columns is a piece edge.
     */
    public boolean isEmptyColumn(int lx, int lz) {
        int i = index(lx, lz);
        if (stand[i].length != 0 || climb[i].length != 0) return false;
        short[] r = runs[i];
        return r.length == 0 || (r.length == 2 && r[0] == baseY);
    }

    /** True if this has the same walkability as another scan of the chunk. Re-scans that change nothing are ignored. */
    public boolean sameAs(ChunkCells other) {
        return other != null && other.chunkX == chunkX && other.chunkZ == chunkZ
                && java.util.Arrays.deepEquals(stand, other.stand) && java.util.Arrays.deepEquals(runs, other.runs)
                && java.util.Arrays.deepEquals(climb, other.climb);
    }

}
