package dev.telemetric.dungeon;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a cell path into polylines we can draw.
 * <ul>
 *   <li>{@link #simplify} does string-pulling. It drops cells as long as a straight line between two cells on the
 *       same level stays on walkable floor, so the route becomes a few straight legs instead of a staircase. Falls
 *       are drawn as "over the edge, then straight down", and ladders as vertical legs. Free-mode legs through the
 *       air (jump pads, high walls) get pulled into straight 3D legs as long as the line stays in open blocks, so no
 *       leg ever passes through a block.</li>
 *   <li>{@link #rounded} replaces every corner with a quadratic curve of a fixed radius ("Clean").</li>
 * </ul>
 * Points sit {@link #LIFT} above the floor so the line hugs it without z-fighting.
 */
public final class PathGeometry {

    public static final double LIFT = 0.06;
    private static final double SAMPLE = 0.3;
    private static final double CLEARANCE = 0.3;
    /** Width check for walk legs. Half a player (0.3) was too strict next to walls. */
    private static final double WALK_CLEARANCE = 0.2;
    private static final int MAX_LOOKAHEAD = 96;
    private static final double CORNER_RADIUS = 1.4;
    private static final int CORNER_STEPS = 8;

    private PathGeometry() {}

    public static Point point(DungeonMap map, long cell) {
        int x = Cell.x(cell), y = Cell.y(cell), z = Cell.z(cell);
        return new Point(x + 0.5, map.floorHeight(x, y, z) + LIFT, z + 0.5);
    }

    /** Straight legs ("Sharp" style, and the input for {@link #rounded}). */
    public static List<Point> simplify(DungeonMap map, long[] cells) {
        List<Point> out = new ArrayList<>();
        if (cells == null || cells.length == 0) return out;
        out.add(point(map, cells[0]));
        int i = 0;
        while (i < cells.length - 1) {
            int k = i + 1;
            int y = Cell.y(cells[i]);
            // Take the farthest cell on this level with a clear straight line, not just the first break, so a single kink in
            // the grid path doesn't split a straight walk into lots of short legs.
            int run = i;
            while (run + 1 < cells.length && run - i < MAX_LOOKAHEAD && Cell.y(cells[run + 1]) == y) run++;
            for (int j = run; j > i + 1; j--) {
                if (straightWalkable(map, cells[i], cells[j])) {
                    k = j;
                    break;
                }
            }
            boolean air = false;
            if (k == i + 1 && (isAir(map, cells[i]) || isAir(map, cells[k]))) {
                while (k + 1 < cells.length && straightOpen(map, cells[i], cells[k + 1])) k++;
                air = true;
            }
            Point p = point(map, cells[k]);
            Point prev = out.getLast();
            if (!air && prev.y() - p.y() >= 1.5 && (Cell.x(cells[k]) != Cell.x(cells[i]) || Cell.z(cells[k]) != Cell.z(cells[i]))) {
                // a fall: walk over the edge at floor height, then straight down (not a slope through the wall)
                out.add(new Point(p.x(), prev.y(), p.z()));
            }
            out.add(p);
            i = k;
        }
        return out;
    }

    /** A free-mode cell in mid-air (neither standable nor a ladder). */
    private static boolean isAir(DungeonMap map, long cell) {
        int x = Cell.x(cell), y = Cell.y(cell), z = Cell.z(cell);
        return !map.isStand(x, y, z);
    }

    /** True if the drawn 3D line between two cells (and ±{@link #CLEARANCE} around it) only crosses open blocks. */
    private static boolean straightOpen(DungeonMap map, long a, long b) {
        Point pa = point(map, a), pb = point(map, b);
        double dx = pb.x() - pa.x(), dy = pb.y() - pa.y(), dz = pb.z() - pa.z();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return true;
        int steps = (int) Math.ceil(len / SAMPLE);
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double sx = pa.x() + dx * t, sy = pa.y() + dy * t + 0.2, sz = pa.z() + dz * t;
            int by = (int) Math.floor(sy);
            for (int o = 0; o < 5; o++) {
                double ox = o == 1 ? CLEARANCE : o == 2 ? -CLEARANCE : 0;
                double oz = o == 3 ? CLEARANCE : o == 4 ? -CLEARANCE : 0;
                if (!map.isOpen((int) Math.floor(sx + ox), by, (int) Math.floor(sz + oz))) return false;
            }
        }
        return true;
    }

    private static boolean straightWalkable(DungeonMap map, long a, long b) {
        int y = Cell.y(a);
        double ax = Cell.x(a) + 0.5, az = Cell.z(a) + 0.5;
        double bx = Cell.x(b) + 0.5, bz = Cell.z(b) + 0.5;
        double dx = bx - ax, dz = bz - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) return true;
        double px = -dz / len * WALK_CLEARANCE, pz = dx / len * WALK_CLEARANCE; // perpendicular offset
        int steps = (int) Math.ceil(len / SAMPLE);
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double sx = ax + dx * t, sz = az + dz * t;
            if (!map.isStand((int) Math.floor(sx), y, (int) Math.floor(sz))) return false;
            if (!map.isStand((int) Math.floor(sx + px), y, (int) Math.floor(sz + pz))) return false;
            if (!map.isStand((int) Math.floor(sx - px), y, (int) Math.floor(sz - pz))) return false;
        }
        return true;
    }

    /** Rounds every interior corner of a polyline ("Clean" style). */
    public static List<Point> rounded(List<Point> pts) {
        if (pts.size() < 3) return new ArrayList<>(pts);
        List<Point> out = new ArrayList<>();
        out.add(pts.getFirst());
        for (int i = 1; i < pts.size() - 1; i++) {
            Point a = pts.get(i - 1), p = pts.get(i), b = pts.get(i + 1);
            double la = Math.sqrt(p.distanceSq(a.x(), a.y(), a.z()));
            double lb = Math.sqrt(p.distanceSq(b.x(), b.y(), b.z()));
            double r = Math.min(CORNER_RADIUS, Math.min(la, lb) * 0.5);
            if (r < 0.05 || la < 1e-6 || lb < 1e-6) {
                out.add(p);
                continue;
            }
            Point start = p.lerp(a, r / la);
            Point end = p.lerp(b, r / lb);
            for (int s = 0; s <= CORNER_STEPS; s++) {
                double t = (double) s / CORNER_STEPS;
                double u = 1 - t;
                out.add(new Point(
                        u * u * start.x() + 2 * u * t * p.x() + t * t * end.x(),
                        u * u * start.y() + 2 * u * t * p.y() + t * t * end.y(),
                        u * u * start.z() + 2 * u * t * p.z() + t * t * end.z()));
            }
        }
        out.add(pts.getLast());
        return out;
    }
}
