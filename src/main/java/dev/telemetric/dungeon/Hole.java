package dev.telemetric.dungeon;

/**
 * A deep drop found in the walk map (a boss drop candidate).
 *
 * @param y        feet height at the rim (where you step in)
 * @param depth    blocks fallen from the rim to the landing floor (averaged over the hole)
 * @param size     number of open block columns that make up the hole
 * @param approach standable rim cells next to the hole (path-finding goals)
 */
public record Hole(int minX, int minZ, int maxX, int maxZ, int y, int depth, int size, long[] approach) {

    public double centerX() {
        return (minX + maxX + 1) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ + 1) / 2.0;
    }

    /** Higher means more likely to be the boss drop: deep, and wider than a stray gap. */
    public double score() {
        return Math.min(depth, 48) * (1 + Math.log(size) / Math.log(2));
    }
}
