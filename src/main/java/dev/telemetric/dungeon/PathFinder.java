package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/**
 * Multi-goal A* over the standable cells of a {@link DungeonMap}. Pure Java, safe to run on a worker
 * thread. Moves: 8 directions, walk, step/jump up 1, drop down up to {@link #MAX_FALL} blocks, and
 * climb up/down ladders and vines, and ride known <b>jump pads</b> ({@link #lifts}: straight up the pad's
 * shaft until a block — barrier ceilings included — stops it, then sideways onto a ledge).
 * Also sprint-jumps straight across 1..{@link #MAX_GAP} wide gaps ({@link #gapJumps}).
 * Diagonals may not cut corners. Cost ≈ distance walked (+ penalties for jumps and falls). Only real
 * player moves — no flying through air; unreachable goals give a partial route to the closest cell.
 */
public final class PathFinder {

    /** Highest drop the router takes on purpose (the boss hole is deeper and is only ever a goal). */
    public static final int MAX_FALL = 6;
    private static final double DIAG = Math.sqrt(2);
    /** Hops/drops cost about a short detour, so walking around a bump beats hopping over it. */
    private static final double JUMP_COST = 1.5;
    private static final double FALL_COST = 0.8;
    /** Extra cost per block of fall damage (falls over 3 blocks hurt). */
    private static final double FALL_DAMAGE_COST = 0.6;
    /** Ladders are ~half walking speed. */
    private static final double CLIMB_COST = 1.8;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    /** Highest a jump pad counts as throwing the player (blocks above the pad's feet height). */
    static final int MAX_LIFT = 16;
    /** A lift lands on ledges up to this many columns from the pad (Chebyshev distance). */
    static final int LIFT_REACH = 3;
    /** At most this many landings per pad (nearest columns first). */
    static final int MAX_LANDINGS = 8;
    /** Riding a pad: a little more than walking the same distance (walking stays preferred). */
    private static final double LIFT_COST = 2.0;

    private PathFinder() {}

    /** Horizontal target used for the heuristic: goal area centre and its radius. */
    public record Anchor(double x, double z, double radius) {}

    /**
     * A found path. {@code complete} = it ends on a goal; otherwise no goal is reachable from the start
     * (sealed section, unexplored chunks) and the path ends at the reachable cell closest to the goals.
     */
    /** {@code capped}: the search stopped at its node limit (the goal may still be walkable). */
    public record Result(long[] cells, boolean complete, boolean capped) {}

    private static final class Node {
        final long cell;
        double g;
        double f;
        Node parent;
        boolean closed;

        Node(long cell) {
            this.cell = cell;
        }
    }

    /**
     * @return cells from start to the reached goal (inclusive); if no goal can be reached, a partial path
     *         to the closest reachable cell ({@link Result#complete()} false). Null if there are no goals or
     *         {@code cancelled} turned true.
     */
    public static Result find(DungeonMap map, long start, LongSet goals, List<Anchor> anchors, int maxNodes,
                              BooleanSupplier cancelled) {
        if (goals.isEmpty()) return null;
        double weight = 1;
        Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Node first = new Node(start);
        first.f = weight * heuristic(start, anchors);
        nodes.put(start, first);
        open.add(first);
        Node best = first;
        double bestH = first.f;
        int expanded = 0;
        boolean capped = false;
        long[] neighbour = new long[NEIGHBOURS + 4];
        double[] cost = new double[NEIGHBOURS + 4];
        long[] longFall = new long[4];

        while (!open.isEmpty()) {
            Node current = open.poll();
            if (current.closed) continue;
            current.closed = true;
            if (goals.contains(current.cell)) return new Result(reconstruct(current), true, false);
            double h = current.f - current.g;
            if (h < bestH - 1e-9 || (Math.abs(h - bestH) <= 1e-9 && current.g < best.g)) {
                best = current;
                bestH = h;
            }
            if (++expanded > maxNodes) {
                capped = true;
                break;
            }
            if ((expanded & 1023) == 0 && cancelled.getAsBoolean()) return null;

            int n = neighbours(map, current.cell, neighbour, cost);
            // the deep drops Reachability (minimap) counts as reachable: same move set, just expensive
            int deep = Reachability.longFalls(map, current.cell, longFall);
            for (int i = 0; i < deep; i++) {
                int fall = Cell.y(current.cell) - Cell.y(longFall[i]);
                neighbour[n] = longFall[i];
                cost[n++] = 1 + fall * FALL_COST + (fall - 3) * FALL_DAMAGE_COST;
            }
            for (int i = 0; i < n; i++) {
                double g = current.g + cost[i];
                Node next = nodes.get(neighbour[i]);
                if (next == null) {
                    next = new Node(neighbour[i]);
                    nodes.put(neighbour[i], next);
                } else if (next.closed || g >= next.g) {
                    continue;
                }
                next.g = g;
                next.f = g + weight * heuristic(next.cell, anchors);
                next.parent = current;
                open.add(next); // lazy decrease-key: stale entries are skipped via `closed`
            }
        }
        return new Result(reconstruct(best), false, capped);
    }

    /** Array size for {@link #neighbours}. */
    static final int NEIGHBOURS = 14 + MAX_LANDINGS;
    /** Widest gap the router sprint-jumps across. */
    static final int MAX_GAP = 3;
    private static final double GAP_JUMP_COST = 2.0;
    private static final double GAP_WIDTH_COST = 0.5;

    /**
     * Cell the player stands on (or just above, while jumping), or {@link Long#MIN_VALUE} when there is
     * no floor near the given feet position (mid-air, unscanned chunk).
     */
    public static long startCell(DungeonMap map, Point p) {
        int x = (int) Math.floor(p.x()), y = (int) Math.floor(p.y() + 1e-3), z = (int) Math.floor(p.z());
        int sy = map.standNear(x, y, z, 1, 4);
        if (sy != Integer.MIN_VALUE) return Cell.pack(x, sy, z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                sy = map.standNear(x + dx, y, z + dz, 1, 2);
                if (sy != Integer.MIN_VALUE) return Cell.pack(x + dx, sy, z + dz);
            }
        }
        return Long.MIN_VALUE;
    }

    /** Fills reachable neighbour cells + step costs; returns how many. Arrays need ≥ {@link #NEIGHBOURS} slots. */
    static int neighbours(DungeonMap map, long cell, long[] out, double[] cost) {
        int x = Cell.x(cell);
        int y = Cell.y(cell);
        int z = Cell.z(cell);
        boolean headroom = map.isOpen(x, y + 2, z);
        int count = 0;
        // ladders: climb inside the column (the cell above a ladder's top is standable too)
        if (map.isClimb(x, y, z) && map.isStand(x, y + 1, z)) {
            out[count] = Cell.pack(x, y + 1, z);
            cost[count++] = CLIMB_COST;
        }
        if (map.isClimb(x, y - 1, z) && map.isStand(x, y - 1, z)) {
            out[count] = Cell.pack(x, y - 1, z);
            cost[count++] = CLIMB_COST;
        }
        for (int[] d : DIRS) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (!map.isKnown(nx, nz)) continue;
            boolean diagonal = d[0] != 0 && d[1] != 0;
            double base = diagonal ? DIAG : 1;
            int ty;
            double extra = 0;
            if (map.isStand(nx, y, nz)) {
                ty = y;
            } else if (headroom && map.isStand(nx, y + 1, nz)) {
                ty = y + 1;
                extra = JUMP_COST;
            } else if (map.isOpen(nx, y, nz) && map.isOpen(nx, y + 1, nz)) {
                ty = map.landingY(nx, nz, y);
                if (ty == Integer.MIN_VALUE || y - ty > MAX_FALL) continue;
                extra = (y - ty) * FALL_COST + Math.max(0, y - ty - 3) * FALL_DAMAGE_COST;
            } else {
                continue;
            }
            if (diagonal) {
                int hy = Math.max(y, ty);
                if (!passable(map, nx, hy, z) || !passable(map, x, hy, nz)) continue;
            }
            out[count] = Cell.pack(nx, ty, nz);
            cost[count] = base + extra;
            count++;
        }
        if (headroom) count = gapJumps(map, x, y, z, out, cost, count);
        return lifts(map, x, y, z, out, cost, count);
    }

    /**
     * Sprint jumps straight across a 1..{@link #MAX_GAP} wide gap (4 axis directions, shortest gap only),
     * landing on the same level or 1 lower. The arc (feet..head+1) over the gap must be open.
     */
    private static int gapJumps(DungeonMap map, int x, int y, int z, long[] out, double[] cost, int count) {
        for (int dir = 0; dir < 4; dir++) {
            int dx = DIRS[dir][0], dz = DIRS[dir][1];
            for (int w = 1; w <= MAX_GAP; w++) {
                int gx = x + dx * w, gz = z + dz * w;
                if (!map.isKnown(gx, gz)) break;
                if (!map.isOpen(gx, y, gz) || !map.isOpen(gx, y + 1, gz) || !map.isOpen(gx, y + 2, gz)) break;
                if (map.isStand(gx, y, gz)) break; // floor, not a gap: walking covers it
                int below = map.landingY(gx, gz, y);
                if (below != Integer.MIN_VALUE && y - below <= 1) break; // shallow dip: just walk through
                int lx = gx + dx, lz = gz + dz;
                if (!map.isKnown(lx, lz)) break;
                int ty;
                if (map.isStand(lx, y, lz)) ty = y;
                else if (map.isStand(lx, y - 1, lz) && map.isOpen(lx, y + 1, lz)) ty = y - 1;
                else continue; // gap goes on: try one wider
                out[count] = Cell.pack(lx, ty, lz);
                cost[count++] = (w + 1) + GAP_JUMP_COST + w * GAP_WIDTH_COST;
                break;
            }
        }
        return count;
    }

    /**
     * Jump pad moves from the stand (x,y,z), appended at {@code count}; returns the new count. Only from a
     * pad the client has seen ({@link DungeonMap#isPad}): the player is thrown straight up the pad's column
     * while the block above the head is open — any block stops it, so a barrier ceiling over an open-air
     * dungeon (or a cave roof) is the limit, never "through" it — at most {@link #MAX_LIFT}. Landings: stands
     * 2+ blocks above the pad, within {@link #LIFT_REACH} columns, reached sideways at that height through
     * open air, and not on the roof top (sky-exposed). The way back down is an ordinary drop (or none).
     */
    static int lifts(DungeonMap map, int x, int y, int z, long[] out, double[] cost, int count) {
        if (!map.isPad(x, y, z)) return count;
        int top = y;
        while (top < y + MAX_LIFT && map.isOpen(x, top + 2, z)) top++;
        if (top < y + 2) return count;
        int found = 0;
        for (int r = 1; r <= LIFT_REACH; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int nx = x + dx, nz = z + dz;
                    if (!map.isKnown(nx, nz)) continue;
                    for (short s : map.stand(nx, nz)) {
                        if (s < y + 2 || s > top) continue;
                        if (map.isSkyExposed(nx, s + 1, nz) || !airLeg(map, x, z, nx, nz, s)) continue;
                        if (count == out.length || found == MAX_LANDINGS) return count;
                        out[count] = Cell.pack(nx, s, nz);
                        cost[count++] = Math.sqrt(dx * dx + dz * dz) + LIFT_COST;
                        found++;
                    }
                }
            }
        }
        return count;
    }

    /** Sideways at feet height y from column (x,z) to the stand column (tx,tz): feet + head open on the way. */
    private static boolean airLeg(DungeonMap map, int x, int z, int tx, int tz, int y) {
        int dx = tx - x, dz = tz - z;
        int n = Math.max(Math.abs(dx), Math.abs(dz));
        int px = x, pz = z;
        for (int t = 1; t <= n; t++) {
            int cx = x + Math.round((float) dx * t / n), cz = z + Math.round((float) dz * t / n);
            boolean last = t == n;
            if (!last && !(map.isOpen(cx, y, cz) && map.isOpen(cx, y + 1, cz))) return false;
            // no corner cutting on diagonal steps
            if (cx != px && cz != pz && (!map.isOpen(cx, y + 1, pz) || !map.isOpen(px, y + 1, cz)
                    || !map.isOpen(cx, y, pz) && !map.isStand(cx, y, pz) || !map.isOpen(px, y, cz) && !map.isStand(px, y, cz))) {
                return false;
            }
            px = cx;
            pz = cz;
        }
        return true;
    }

    private static boolean passable(DungeonMap map, int x, int y, int z) {
        return (map.isOpen(x, y, z) || map.isStand(x, y, z)) && map.isOpen(x, y + 1, z);
    }

    private static double heuristic(long cell, List<Anchor> anchors) {
        int x = Cell.x(cell);
        int z = Cell.z(cell);
        double best = Double.MAX_VALUE;
        for (Anchor a : anchors) {
            double dx = Math.abs(x + 0.5 - a.x());
            double dz = Math.abs(z + 0.5 - a.z());
            double octile = Math.max(dx, dz) + (DIAG - 1) * Math.min(dx, dz);
            best = Math.min(best, Math.max(0, octile - a.radius()));
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }

    private static long[] reconstruct(Node end) {
        LongArrayList cells = new LongArrayList();
        for (Node n = end; n != null; n = n.parent) cells.add(n.cell);
        LongArrayList path = new LongArrayList(cells.size() + 4);
        for (int i = cells.size() - 1; i >= 0; i--) {
            long c = cells.getLong(i);
            if (!path.isEmpty()) {
                long p = path.getLong(path.size() - 1);
                // a jump pad lift: up the pad's shaft first, then across (the drawn line never cuts the ledge)
                if (Cell.y(c) - Cell.y(p) >= 2 && (Cell.x(c) != Cell.x(p) || Cell.z(c) != Cell.z(p))) {
                    path.add(Cell.pack(Cell.x(p), Cell.y(c), Cell.z(p)));
                }
            }
            path.add(c);
        }
        return path.toLongArray();
    }
}
