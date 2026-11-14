package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongPredicate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds holes: places where walking off a floor edge drops you at least {@code minDepth} blocks onto
 * another floor. Columns of such drops are clustered (8-neighbourhood, similar rim height) into
 * {@link Hole}s, best boss-drop candidate first.
 * <p>
 * Only rims on plausible floor count (see {@link DungeonAnalysis#isFloor}: so the top of the dungeon's roof
 * is never a "drop"), and holes a ladder runs down are skipped — the router climbs those, they are not drops.
 * <p>
 * Cost: one pass over every standable cell × 4 neighbours — a few ms on a worker thread even for
 * large dungeons. Drops into the void (no floor below) are ignored, so open dungeon edges never count.
 */
public final class DropDetector {

    private static final int[][] DIRS4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIRS8 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** A hole counts as a ladder shaft when one column there has ladder over this share of the drop. */
    private static final double LADDER_SHARE = 0.6;

    private DropDetector() {}

    /** @param floor which standable cells count as dungeon floor (rims elsewhere are ignored) */
    public static List<Hole> detect(DungeonMap map, int minDepth, LongPredicate floor) {
        return detect(map, minDepth, floor, cell -> false);
    }

    /** Shallowest fall that still counts when it lands in the boss level (a drop broken by ledges). */
    private static final int MIN_BOSS_FALL = 3;

    /**
     * @param floor  which standable cells count as dungeon floor (rims elsewhere are ignored)
     * @param inBoss landing cells in the boss level: a fall into it counts from {@link #MIN_BOSS_FALL} blocks,
     *               not {@code minDepth} (the boss drop may land on a ledge first, e.g. Treasure Cave)
     */
    public static List<Hole> detect(DungeonMap map, int minDepth, LongPredicate floor, LongPredicate inBoss) {
        Long2IntOpenHashMap dropY = new Long2IntOpenHashMap();     // column → rim y
        Long2IntOpenHashMap dropDepth = new Long2IntOpenHashMap(); // column → depth
        Long2ObjectOpenHashMap<LongArrayList> approach = new Long2ObjectOpenHashMap<>();

        for (ChunkCells chunk : map.chunks()) {
            int bx = chunk.chunkX << 4, bz = chunk.chunkZ << 4;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    short[] stands = chunk.stand(lx, lz);
                    if (stands.length == 0) continue;
                    int x = bx + lx, z = bz + lz;
                    for (short sy : stands) {
                        int y = sy;
                        if (!map.isOpen(x, y + 1, z)) continue;
                        if (!floor.test(Cell.pack(x, y, z))) continue;
                        for (int[] d : DIRS4) {
                            int nx = x + d[0], nz = z + d[1];
                            if (!map.isKnown(nx, nz)) continue;
                            if (map.standNear(nx, y, nz, 1, 1) != Integer.MIN_VALUE) continue;
                            if (!map.isOpen(nx, y, nz) || !map.isOpen(nx, y + 1, nz)) continue;
                            int land = map.landingY(nx, nz, y);
                            if (land == Integer.MIN_VALUE) continue; // void, not a hole
                            int depth = y - land;
                            if (depth < minDepth && !(depth >= MIN_BOSS_FALL && inBoss.test(Cell.pack(nx, land, nz)))) continue;
                            long col = Cell.columnKey(nx, nz);
                            if (!dropY.containsKey(col) || dropY.get(col) < y) {
                                dropY.put(col, y);
                                dropDepth.put(col, depth);
                            }
                            approach.computeIfAbsent(col, k -> new LongArrayList()).add(Cell.pack(x, y, z));
                        }
                    }
                }
            }
        }
        if (dropY.isEmpty()) return List.of();

        // also mark the hole's interior columns (no floor edge next to them) so the size is the real size
        LongOpenHashSet visited = new LongOpenHashSet();
        List<Hole> holes = new ArrayList<>();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (long seed : dropY.keySet().toLongArray()) {
            if (!visited.add(seed)) continue;
            int rimY = dropY.get(seed);
            queue.enqueue(seed);
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            int size = 0;
            long depthSum = 0;
            int depthCount = 0;
            LongOpenHashSet rim = new LongOpenHashSet();
            LongArrayList members = new LongArrayList();
            while (!queue.isEmpty()) {
                long col = queue.dequeueLong();
                members.add(col);
                int x = Cell.columnX(col), z = Cell.columnZ(col);
                size++;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                if (dropDepth.containsKey(col)) {
                    depthSum += dropDepth.get(col);
                    depthCount++;
                }
                LongArrayList a = approach.get(col);
                if (a != null) rim.addAll(a);
                if (size > 4096) continue; // safety: a giant open pit, stop growing
                for (int[] d : DIRS8) {
                    int nx = x + d[0], nz = z + d[1];
                    long n = Cell.columnKey(nx, nz);
                    if (visited.contains(n)) continue;
                    boolean member;
                    if (dropY.containsKey(n)) {
                        member = Math.abs(dropY.get(n) - rimY) <= 2;
                    } else {
                        // interior: open at rim height, no floor nearby, deep landing
                        member = map.isKnown(nx, nz) && map.isOpen(nx, rimY, nz) && map.isOpen(nx, rimY + 1, nz)
                                && map.standNear(nx, rimY, nz, 1, 1) == Integer.MIN_VALUE
                                && deepEnough(map, nx, nz, rimY, minDepth, inBoss);
                    }
                    if (member) {
                        visited.add(n);
                        queue.enqueue(n);
                    }
                }
            }
            int depth = depthCount == 0 ? minDepth : (int) (depthSum / depthCount);
            if (rim.isEmpty()) continue;
            // a ladder shaft is normally climbed, not a drop — unless it leads into the boss level: that is the
            // way into the arena (Treasure Cave: ladder shaft y 98→71, ladder, barrier chute down to the arena)
            if (hasLadder(map, members, rimY, rimY - depth) && !landsInBoss(map, members, rimY, inBoss)) continue;
            holes.add(new Hole(minX, minZ, maxX, maxZ, rimY, depth, size, rim.toLongArray()));
        }
        holes.sort(Comparator.comparingDouble(Hole::score).reversed());
        return holes;
    }

    /** True if a ladder/vine column inside the hole (or right next to it) covers most of the drop. */
    private static boolean hasLadder(DungeonMap map, LongArrayList members, int rimY, int landY) {
        int need = (int) Math.ceil((rimY - landY) * LADDER_SHARE);
        LongOpenHashSet checked = new LongOpenHashSet();
        for (int i = 0; i < members.size(); i++) {
            long col = members.getLong(i);
            int x = Cell.columnX(col), z = Cell.columnZ(col);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!checked.add(Cell.columnKey(x + dx, z + dz))) continue;
                    if (map.climbCount(x + dx, z + dz, landY, rimY) >= need) return true;
                }
            }
        }
        return false;
    }

    /** True if a fall from {@code rimY} in one of the hole's columns lands in the boss level. */
    private static boolean landsInBoss(DungeonMap map, LongArrayList members, int rimY, LongPredicate inBoss) {
        for (int i = 0; i < members.size(); i++) {
            long col = members.getLong(i);
            int x = Cell.columnX(col), z = Cell.columnZ(col);
            int land = map.landingY(x, z, rimY);
            if (land != Integer.MIN_VALUE && rimY - land >= MIN_BOSS_FALL && inBoss.test(Cell.pack(x, land, z))) return true;
        }
        return false;
    }

    /** Falling from {@code rimY} in this column lands {@code minDepth} down, or at least 3 down into the boss level. */
    private static boolean deepEnough(DungeonMap map, int x, int z, int rimY, int minDepth, LongPredicate inBoss) {
        int land = map.landingY(x, z, rimY);
        if (land == Integer.MIN_VALUE) return false;
        int depth = rimY - land;
        return depth >= minDepth || depth >= MIN_BOSS_FALL && inBoss.test(Cell.pack(x, land, z));
    }
}
