package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Mechanic <b>free-form rooms</b> ({@link dev.telemetric.dungeon.rules.DungeonRules.RoomShape#FREEFORM}): rooms of
 * any size instead of the piece grid — long halls, wide halls and corridors each become one rectangle.
 * <p>
 * Dwarven Frostkeep is not built from same-size pieces: an 82×9 hall, a 46×8 cross hall, 7-wide corridors 17+ long
 * and a 28×18 spawn room. A fixed grid cut every hall into 14-block chunks.
 * <p>
 * <b>Detection</b> (2D, on the room-level floor columns):
 * <ol>
 *   <li>Seed = the free floor column farthest from any wall/void ({@link #distances}, Chebyshev). The middle of the
 *       widest open space goes first, so big rooms and junctions are claimed before the corridors that lead in.
 *       Ties (a 7-wide corridor and a 7-wide hall): every such seed is grown (≤ {@link #MAX_CANDIDATES}) and the
 *       biggest rectangle wins, so a corridor does not run straight through the hall it ends in.</li>
 *   <li>Grow a rectangle round-robin, one line per side per pass. A side moves out while ≥ {@link #GROW_FILL} of the
 *       new line is free floor within {@link #LEVEL_TOL} of the seed's height. A wall line stops it (pillars and
 *       furniture cost a few cells, not the line); a doorway is narrower than the room, so it stops it too; already
 *       claimed rooms are not free.</li>
 *   <li>Rectangles thinner than {@link #MIN_SIDE} or with fewer than {@link #MIN_CELLS} floor columns are no room
 *       (their cells only stop being seeds). Repeat until no seed is ≥ {@link #MIN_SEED} from a wall.</li>
 *   <li>Left-over floor (doorways, niches, thin strips) joins the nearest room it is walk-adjacent to (BFS).</li>
 *   <li>Links / own levels / dropping unlinked rooms: {@link RoomLayout#linkRooms}, same as the grid, but any two
 *       rooms may link (cells have one owner, so no diagonal-corner problem).</li>
 * </ol>
 * Room bounds are the grown rectangle; {@link RoomLayout#roomAt} uses the per-column owner map.
 */
public final class FreeformRooms {

    /** Share of a new rectangle line that must be free floor for the side to move out. */
    static final double GROW_FILL = 0.8;
    /** Max height difference to the seed's floor within one room (a raised dais is still the room). */
    static final int LEVEL_TOL = 4;
    /** Seeds need this Chebyshev distance to the nearest non-floor column (≥ 3 wide open space). */
    static final int MIN_SEED = 2;
    /** Thinnest room side. */
    static final int MIN_SIDE = 3;
    /** Fewest floor columns of a room. */
    static final int MIN_CELLS = 20;
    /** Most seeds of the same openness tried per room (evenly sampled). */
    private static final int MAX_CANDIDATES = 48;
    /** Safety cap on rooms per layout. */
    private static final int MAX_ROOMS = 256;

    private FreeformRooms() {}

    /**
     * @param floors column key → room-level floor stand (boss level and, if the rule is on, outdoor floor removed);
     *               the caller's map, not modified
     */
    static RoomLayout detect(DungeonMap map, LongPredicate floor, LongSet reachable, Long2IntOpenHashMap floors,
                             int dungeonLevel, RoomLayout.Hint hint) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long c : floors.keySet()) {
            int x = Cell.columnX(c), z = Cell.columnZ(c);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        int w = maxX - minX + 1, h = maxZ - minZ + 1;
        int[] fy = new int[w * h];
        java.util.Arrays.fill(fy, Integer.MIN_VALUE);
        boolean[] free = new boolean[w * h];
        for (var e : floors.long2IntEntrySet()) {
            int i = (Cell.columnZ(e.getLongKey()) - minZ) * w + (Cell.columnX(e.getLongKey()) - minX);
            fy[i] = e.getIntValue();
            free[i] = true;
        }
        int[] owner = new int[w * h];
        java.util.Arrays.fill(owner, -1);
        int[] dist = new int[w * h];
        List<int[]> rects = new java.util.ArrayList<>(); // x0, z0, x1, z1 (grid coords)

        while (rects.size() < MAX_ROOMS) {
            distances(free, w, h, dist);
            int seed = -1, best = MIN_SEED - 1;
            for (int i = 0; i < dist.length; i++) {
                if (dist[i] > best) {
                    best = dist[i];
                    seed = i;
                }
            }
            if (seed < 0) break;
            // every equally open seed is tried, the biggest room wins: a corridor as wide as the hall it runs into
            // would otherwise grow straight through the hall and cut it in two
            int candidates = 0;
            for (int v : dist) if (v == best) candidates++;
            int stride = Math.max(1, candidates / MAX_CANDIDATES);
            int[] r = null;
            int cells = -1, k = 0;
            for (int i = 0; i < dist.length; i++) {
                if (dist[i] != best || k++ % stride != 0) continue;
                int[] g = grow(free, fy, w, h, i % w, i / w);
                int c = 0;
                for (int z = g[1]; z <= g[3]; z++) {
                    for (int x = g[0]; x <= g[2]; x++) if (fits(free, fy, w, x, z, fy[i])) c++;
                }
                if (c > cells) {
                    cells = c;
                    r = g;
                    seed = i;
                }
            }
            int id = rects.size();
            boolean room = r[2] - r[0] + 1 >= MIN_SIDE && r[3] - r[1] + 1 >= MIN_SIDE && cells >= MIN_CELLS;
            int level = fy[seed];
            for (int z = r[1]; z <= r[3]; z++) {
                for (int x = r[0]; x <= r[2]; x++) {
                    int i = z * w + x;
                    if (!fits(free, fy, w, x, z, level)) continue;
                    free[i] = false; // claimed, or (no room) at least never a seed again
                    if (room) owner[i] = id;
                }
            }
            free[seed] = false;
            if (room) rects.add(r);
            else rects.add(null);
        }

        // left-over floor joins the nearest room it is walk-adjacent to
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        for (int i = 0; i < owner.length; i++) if (owner[i] >= 0) queue.enqueue(i);
        while (!queue.isEmpty()) {
            int i = queue.dequeueInt();
            int x = i % w, z = i / w;
            for (int d = 0; d < 4; d++) {
                int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                int j = nz * w + nx;
                if (owner[j] >= 0 || fy[j] == Integer.MIN_VALUE || Math.abs(fy[j] - fy[i]) > LEVEL_TOL) continue;
                owner[j] = owner[i];
                queue.enqueue(j);
            }
        }

        // rooms: rectangle bounds, owned walkable columns, most common floor height
        int n = rects.size();
        int[] walkable = new int[n];
        List<Long2IntOpenHashMap> heights = new java.util.ArrayList<>(n);
        for (int k = 0; k < n; k++) heights.add(new Long2IntOpenHashMap());
        for (int i = 0; i < owner.length; i++) {
            if (owner[i] < 0) continue;
            walkable[owner[i]]++;
            heights.get(owner[i]).addTo(fy[i], 1);
        }
        Map<Long, RoomLayout.Room> rooms = new HashMap<>();
        RoomLayout.Room[] byId = new RoomLayout.Room[n];
        for (int k = 0; k < n; k++) {
            int[] r = rects.get(k);
            if (r == null) continue;
            int floorY = 0, top = -1;
            for (var e : heights.get(k).long2IntEntrySet()) {
                if (e.getIntValue() > top) {
                    top = e.getIntValue();
                    floorY = (int) e.getLongKey();
                }
            }
            byId[k] = new RoomLayout.Room(k, 0, r[0] + minX, r[1] + minZ, r[2] + minX, r[3] + minZ, walkable[k], floorY);
            rooms.put(Cell.columnKey(k, 0), byId[k]);
        }
        Long2ObjectOpenHashMap<RoomLayout.Room> owners = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < owner.length; i++) {
            if (owner[i] >= 0) owners.put(Cell.columnKey(i % w + minX, i / w + minZ), byId[owner[i]]);
        }

        RoomLayout.RoomIndex index = new RoomLayout.RoomIndex() {
            @Override
            public RoomLayout.Room at(int x, int z) {
                return owners.get(Cell.columnKey(x, z));
            }

            @Override
            public boolean mayLink(RoomLayout.Room a, RoomLayout.Room b, int dx, int dz) {
                return true;
            }

            @Override
            public boolean skipEdgeNiches() {
                return false;
            }
        };
        List<long[]> links = RoomLayout.linkRooms(map, floor, reachable, floors, rooms, index, dungeonLevel, hint);
        owners.values().removeIf(r -> !rooms.containsKey(Cell.columnKey(r.gx(), r.gz())));
        return new RoomLayout(new int[0], new int[0], 1, Collections.unmodifiableMap(rooms), List.copyOf(links), 0, 0,
                Collections.unmodifiableMap(owners));
    }

    private static boolean fits(boolean[] free, int[] fy, int w, int x, int z, int level) {
        int i = z * w + x;
        return free[i] && Math.abs(fy[i] - level) <= LEVEL_TOL;
    }

    /** Rectangle {x0, z0, x1, z1} (grid coords) grown round-robin from (sx, sz); see the class doc. */
    static int[] grow(boolean[] free, int[] fy, int w, int h, int sx, int sz) {
        int level = fy[sz * w + sx];
        int x0 = sx, x1 = sx, z0 = sz, z1 = sz;
        boolean[] stop = new boolean[4]; // W, E, N, S
        boolean moved = true;
        while (moved) {
            moved = false;
            for (int side = 0; side < 4; side++) {
                if (stop[side]) continue;
                int line = side == 0 ? x0 - 1 : side == 1 ? x1 + 1 : side == 2 ? z0 - 1 : z1 + 1;
                boolean alongZ = side < 2; // W/E lines run along Z
                if (line < 0 || line >= (alongZ ? w : h)) {
                    stop[side] = true;
                    continue;
                }
                int from = alongZ ? z0 : x0, to = alongZ ? z1 : x1, fit = 0;
                for (int u = from; u <= to; u++) {
                    if (alongZ ? fits(free, fy, w, line, u, level) : fits(free, fy, w, u, line, level)) fit++;
                }
                if (fit < GROW_FILL * (to - from + 1)) {
                    stop[side] = true;
                    continue;
                }
                switch (side) {
                    case 0 -> x0 = line;
                    case 1 -> x1 = line;
                    case 2 -> z0 = line;
                    default -> z1 = line;
                }
                moved = true;
            }
        }
        return new int[]{x0, z0, x1, z1};
    }

    /** Chebyshev distance of every free cell to the nearest non-free cell (outside the grid counts as non-free). */
    static void distances(boolean[] free, int w, int h, int[] dist) {
        final int inf = 1 << 20;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                if (!free[i]) {
                    dist[i] = 0;
                    continue;
                }
                int d = inf;
                d = Math.min(d, x > 0 ? dist[i - 1] + 1 : 1);
                d = Math.min(d, z > 0 ? dist[i - w] + 1 : 1);
                d = Math.min(d, x > 0 && z > 0 ? dist[i - w - 1] + 1 : 1);
                d = Math.min(d, x < w - 1 && z > 0 ? dist[i - w + 1] + 1 : 1);
                dist[i] = d;
            }
        }
        for (int z = h - 1; z >= 0; z--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = z * w + x;
                if (dist[i] == 0) continue;
                int d = dist[i];
                d = Math.min(d, x < w - 1 ? dist[i + 1] + 1 : 1);
                d = Math.min(d, z < h - 1 ? dist[i + w] + 1 : 1);
                d = Math.min(d, x < w - 1 && z < h - 1 ? dist[i + w + 1] + 1 : 1);
                d = Math.min(d, x > 0 && z < h - 1 ? dist[i + w - 1] + 1 : 1);
                dist[i] = d;
            }
        }
    }
}
