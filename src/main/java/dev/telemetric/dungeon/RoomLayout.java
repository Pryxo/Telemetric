package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Room segments of a dungeon — the data a 2D minimap draws (one box per room, a line per connection).
 * <p>
 * <b>Shape.</b> Telos dungeons are assembled from pieces whose size is <b>chosen per dungeon by its builder</b>
 * (measured: Goblin Lair 20×20; Thornwood Wargrove ~23–25; Treasure Cave 27×26, 26×27, 26×28; Kobold's Den
 * 26×28, 26×26, 28×26). Within one dungeon pieces vary by a block or two, so there is no exact grid either.
 * Each piece is a solid box (floor slab + roof) floating in the void, so wherever the dungeon's outline
 * steps, that step is a piece edge — and the distances between those edges give the piece size.
 * <p>
 * <b>Detection.</b>
 * <ol>
 *   <li>Floor = the highest dungeon-floor stand per column. Columns far below the main level (the boss
 *       arena, always one big room under/next to the pieces) are left out: {@link #BOSS_LEVEL_GAP}.</li>
 *   <li>Evidence per line: footprint edges (non-empty column next to a void column, with main-level floor
 *       just inside) — exact piece edges. Plus the walkable profile (walls between pieces have less floor;
 *       in cave dungeons like Kobold's Den they are often wide open, so the outline matters most).</li>
 *   <li>Piece size: {@link #pieceSize} — the size that best explains the distances between the straight
 *       outline edges (both axes). When the outline is silent or ambiguous (pieces packed in one solid block,
 *       e.g. Desert Temple 20×20), the size whose wall lines land on the walls between pieces;
 *       {@link #FALLBACK_SIZE} when neither says anything.</li>
 *   <li>A small DP picks the chain of wall lines with gaps of piece size ± {@link #slack}:
 *       cost = walkable crossed − {@link #EDGE_WEIGHT} × footprint edge + size penalty.</li>
 *   <li>Each room edge is refined on its own (±{@link #REFINE} blocks, evidence restricted to that
 *       row/column of rooms), so rooms get their own real bounds.</li>
 *   <li>Links = two rooms you can actually walk between: adjacent floor columns across the edge whose
 *       stand heights differ ≤ {@link #LINK_DROP} with head room (step up 1 / drop down). Both stands must
 *       be on their room's own level (walkable from the room's floor without a deep drop): ledges of a
 *       boss arena under the floor slab are "floor" too and would otherwise link every room above it.</li>
 * </ol>
 * Runs on the worker thread after the map changes, never per frame.
 *
 * @param xs         global wall lines on X (sorted; room column i spans roughly xs[i] … xs[i+1]-1)
 * @param zs         global wall lines on Z
 * @param confidence 0..1, how clearly the walls stand out (≥ ~0.5 = piece-built dungeon)
 * @param rooms      rooms by {@link Cell#columnKey(int, int)} of their (gx, gz) indices (per layout, not stable)
 * @param links      connected room pairs (room keys)
 * @param pieceSize    piece size the grid uses (size table, else measured, else fallback), 0 when nothing was detected
 * @param measuredSize piece size measured from this map alone (0 = no evidence); what the size table learns from
 * @param owners       free-form layouts only ({@link FreeformRooms}): column key -> owning room; null on the grid
 */
public record RoomLayout(int[] xs, int[] zs, double confidence, Map<Long, Room> rooms, List<long[]> links,
                         int pieceSize, int measuredSize, Map<Long, Room> owners) {

    public static final RoomLayout EMPTY = new RoomLayout(new int[0], new int[0], 0, Map.of(), List.of(), 0, 0, null);

    /** Room lookup + which room pairs may link across a step (grid neighbours on the grid, any pair free-form). */
    interface RoomIndex {
        Room at(int x, int z);

        /** {@code dx == dz == 0}: a jump pad lift (any direction). */
        boolean mayLink(Room a, Room b, int dx, int dz);

        /** Grid only: skip small parts hugging a room edge (the neighbour's wall face inside overlapping bounds). */
        boolean skipEdgeNiches();
    }

    /** True when rooms are free-form rectangles ({@link dev.telemetric.dungeon.rules.DungeonRules.RoomShape#FREEFORM}). */
    public boolean freeform() {
        return owners != null;
    }

    /** Smallest / largest piece size the outline may suggest. */
    static final int MIN_PIECE = 12;
    static final int MAX_PIECE = 48;
    /** Used when neither the outline nor the walls give a size yet (most Telos dungeons are ~26). */
    static final int FALLBACK_SIZE = 26;
    /** {@link #wallScore} lead a fraction of the outline size needs to replace it (about half a clear wall line). */
    private static final double WALL_MARGIN = 0.5;
    /** Outline edge lines need this many edge columns to count for {@link #pieceSize} (ignores ragged bits). */
    private static final int STRAIGHT_EDGE = 6;
    /** Edge distances up to this many pieces apart are used for {@link #pieceSize}. */
    private static final int MAX_SPAN = 4;
    /** How far a single room edge may move from the global wall line. */
    private static final int REFINE = 2;
    /** DP cost per block a gap differs from the piece size (one walkable column = 1). */
    private static final double SIZE_PENALTY = 0.35;
    /** Bonus per footprint-edge column on a line (worth more than a walkable column crossed). */
    private static final double EDGE_WEIGHT = 1.5;
    /** A footprint edge only counts if main-level floor is this close inside (ignores the boss arena's outline). */
    private static final int EDGE_INSIDE = 6;
    /** Floor columns this far below the median floor height are the boss level, not a dungeon piece. */
    private static final int BOSS_LEVEL_GAP = 14;
    /** Max height difference of a walkable step between rooms (drop down / step up). */
    private static final int LINK_DROP = 3;
    /** {@link #edgeNiche}: a reached room-level part this close to one room edge and this small is the neighbour's. */
    private static final int NICHE_DEPTH = REFINE + 1;
    private static final int NICHE_MAX = 16;
    /** Share of non-void columns a room side may have and still be clipped off as void (stray blocks). */
    private static final double VOID_STRAY = 0.1;
    /** {@link #roomFloorY}: a floor band this share of the biggest (and ≥ {@link #LEVEL_MIN_COLUMNS}) may be the level. */
    private static final double LEVEL_SHARE = 1 / 3.0;
    private static final int LEVEL_MIN_COLUMNS = 16;
    /**
     * A room no link reaches is still kept when this many cells of its own level are reachable and it sits on the
     * dungeon level (±{@link #LINK_DROP}): you can walk there, so it is a room (only reached by a fall, a pad, ...).
     */
    private static final int KEEP_REACHED = 48;

    /**
     * One dungeon piece. Bounds are inclusive block coordinates (the wall line on the low side belongs to it).
     *
     * @param walkable walkable block columns inside the room
     * @param floorY   the room's floor height ({@link #roomFloorY}; for drawing at the right level)
     */
    public record Room(int gx, int gz, int minX, int minZ, int maxX, int maxZ, int walkable, int floorY) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public double centerX() {
            return (minX + maxX + 1) / 2.0;
        }

        public double centerZ() {
            return (minZ + maxZ + 1) / 2.0;
        }
    }

    /** Air margin around a boss drop that {@link #withDropRoom} adds as its room. */
    private static final int DROP_ROOM_PAD = 1;

    /**
     * Free-form layouts only: the boss drop is a hole, not floor, so no room covers it. Adds a room over the drop
     * (its bounds + {@link #DROP_ROOM_PAD}) so it can be marked as the boss drop room. Columns already owned by a
     * room keep their owner. Grid layouts, a null drop, or a drop already inside a room: unchanged.
     */
    public RoomLayout withDropRoom(Hole drop) {
        if (!freeform() || drop == null) return this;
        if (roomAt((int) Math.floor(drop.centerX()), (int) Math.floor(drop.centerZ())) != null) return this;
        int x0 = drop.minX() - DROP_ROOM_PAD, z0 = drop.minZ() - DROP_ROOM_PAD;
        int x1 = drop.maxX() + DROP_ROOM_PAD, z1 = drop.maxZ() + DROP_ROOM_PAD;
        int id = 0;
        for (Room r : rooms.values()) id = Math.max(id, r.gx() + 1);
        Room room = new Room(id, 0, x0, z0, x1, z1, 0, drop.y());
        Map<Long, Room> newRooms = new java.util.HashMap<>(rooms);
        newRooms.put(Cell.columnKey(id, 0), room);
        Map<Long, Room> newOwners = new java.util.HashMap<>(owners);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) newOwners.putIfAbsent(Cell.columnKey(x, z), room);
        }
        return new RoomLayout(xs, zs, confidence, newRooms, links, pieceSize, measuredSize, newOwners);
    }

    public Room roomAt(int x, int z) {
        if (owners != null) return owners.get(Cell.columnKey(x, z));
        return roomAt(rooms, xs, zs, x, z);
    }

    private static Room roomAt(Map<Long, Room> rooms, int[] xs, int[] zs, int x, int z) {
        if (xs.length < 2 || zs.length < 2) return null;
        int gx = index(xs, x), gz = index(zs, z);
        for (int d = 0; d < 9; d++) { // own cell first, then the 8 around it (edges are refined ±REFINE)
            int ix = gx + (d == 0 ? 0 : (d - 1) % 3 - 1), iz = gz + (d == 0 ? 0 : (d - 1) / 3 - 1);
            Room r = rooms.get(Cell.columnKey(ix, iz));
            if (r != null && r.contains(x, z)) return r;
        }
        return null;
    }

    /** Index i of the global interval [lines[i], lines[i+1]) holding v (clamped). */
    private static int index(int[] lines, int v) {
        int i = Arrays.binarySearch(lines, v);
        if (i < 0) i = -i - 2;
        return Math.max(0, Math.min(lines.length - 2, i));
    }

    /**
     * What is known about the dungeon before detection.
     *
     * @param size     piece size from the size table (0 = unknown, measure it)
     * @param hasSpawn whether the spawn position is known; the piece grid starts at the spawn room
     * @param spawnX   block X where the player entered the dungeon
     * @param spawnZ   block Z where the player entered the dungeon
     * @param spawnY   feet block Y where the player entered (the main room level; MIN_VALUE if unknown)
     * @param rules    this dungeon's mechanics ({@link dev.telemetric.dungeon.rules.DungeonRulebook})
     */
    public record Hint(int size, boolean hasSpawn, int spawnX, int spawnZ, int spawnY,
                       dev.telemetric.dungeon.rules.DungeonRules rules) {
        public static final Hint NONE = new Hint(0, false, 0, 0, Integer.MIN_VALUE,
                dev.telemetric.dungeon.rules.DungeonRules.DEFAULT);
    }

    /**
     * @param floor     which standable cells count as dungeon floor (not the top of the roof)
     * @param reachable cells the player can walk to (see {@link Reachability}); picks each room's real space
     * @param hint      known piece size / spawn position ({@link Hint#NONE} when nothing is known)
     */
    public static RoomLayout detect(DungeonMap map, LongPredicate floor, LongSet reachable, Hint hint) {
        Long2IntOpenHashMap all = topFloors(map, floor, hint.spawnY());
        if (all.size() < 64) return EMPTY;
        // dungeon rule: outdoor ground / arcades under it are scenery, not rooms
        if (hint.rules().indoorRoomsOnly()) {
            all = IndoorFloors.roofed(map, all, level(hint, all));
            if (all.size() < 64) return EMPTY;
        }

        // 1) drop the boss level (far below the dungeon level; fallback when no boss drop is known)
        int level = level(hint, all);
        int cutoff = level == Integer.MIN_VALUE ? Integer.MIN_VALUE : level - BOSS_LEVEL_GAP;
        Long2IntOpenHashMap floors = new Long2IntOpenHashMap();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (var e : all.long2IntEntrySet()) {
            if (e.getIntValue() < cutoff) continue;
            long c = e.getLongKey();
            floors.put(c, e.getIntValue());
            int x = Cell.columnX(c), z = Cell.columnZ(c);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        if (floors.size() < 64) return EMPTY;
        // dungeon rule: rooms of any size instead of the piece grid
        if (hint.rules().roomShape() == dev.telemetric.dungeon.rules.DungeonRules.RoomShape.FREEFORM) {
            return FreeformRooms.detect(map, floor, reachable, floors, level, hint);
        }
        long[] cols = floors.keySet().toLongArray();

        // 2) evidence: walkable profiles + footprint edges
        int[] px = new int[maxX - minX + 1], pz = new int[maxZ - minZ + 1];
        for (long c : cols) {
            px[Cell.columnX(c) - minX]++;
            pz[Cell.columnZ(c) - minZ]++;
        }
        LongOpenHashSet edgeX = new LongOpenHashSet(), edgeZ = new LongOpenHashSet(); // (line, u) / (u, line)
        Long2IntOpenHashMap evX = new Long2IntOpenHashMap(), evZ = new Long2IntOpenHashMap();
        footprintEdges(map, floors, edgeX, edgeZ, evX, evZ);

        // 3) piece size from the outline, then global wall lines
        // (the measurement always runs: it is what the size table learns from)
        int measured = pieceSize(px, minX, evX, pz, minZ, evZ);
        int size = hint.size() > 0 ? hint.size() : measured > 0 ? measured : FALLBACK_SIZE;
        int[] xs = hint.hasSpawn() ? anchoredChain(px, minX, evX, size, hint.spawnX()) : chain(px, minX, evX, size);
        int[] zs = hint.hasSpawn() ? anchoredChain(pz, minZ, evZ, size, hint.spawnZ()) : chain(pz, minZ, evZ, size);
        double confidence = (lineConfidence(px, minX, xs) + lineConfidence(pz, minZ, zs)) / 2;

        // 4) per-edge refinement: xEdge[j][i] = west edge of room column i within room row j
        int nx = xs.length - 1, nz = zs.length - 1;
        int[][] xEdge = new int[nz][xs.length];
        int[][] zEdge = new int[nx][zs.length];
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < xs.length; i++) {
                xEdge[j][i] = refine(floors, edgeX, xs[i], zs[j], zs[j + 1] - 1, true);
            }
        }
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < zs.length; j++) {
                zEdge[i][j] = refine(floors, edgeZ, zs[j], xs[i], xs[i + 1] - 1, false);
            }
        }

        // 5) rooms: bounds, walkable count, most common floor height
        Map<Long, Room> rooms = new HashMap<>();
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int x0 = xEdge[j][i], x1 = xEdge[j][i + 1] - 1, z0 = zEdge[i][j], z1 = zEdge[i][j + 1] - 1;
                // clip to the dungeon outline: grid lines follow the piece period, so outer / smaller pieces
                // would otherwise reach into the void around them
                while (x0 < x1 && voidLine(map, floors, x0, z0, z1, true)) x0++;
                while (x1 > x0 && voidLine(map, floors, x1, z0, z1, true)) x1--;
                while (z0 < z1 && voidLine(map, floors, z0, x0, x1, false)) z0++;
                while (z1 > z0 && voidLine(map, floors, z1, x0, x1, false)) z1--;
                if (x1 < x0 || z1 < z0) continue;
                int walkable = 0;
                Long2IntOpenHashMap counts = new Long2IntOpenHashMap(), reachedCounts = new Long2IntOpenHashMap();
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        long key = Cell.columnKey(x, z);
                        if (!floors.containsKey(key)) continue;
                        walkable++;
                        int y = floors.get(key);
                        counts.addTo(y, 1);
                        if (reachable.contains(Cell.pack(x, y, z))) reachedCounts.addTo(y, 1);
                    }
                }
                int area = (x1 - x0 + 1) * (z1 - z0 + 1);
                if (walkable < Math.max(4, (int) (area * 0.06))) continue;
                // the floor you can walk to decides the level: sealed hollows under the roof around a round room
                // (Midnight Stronghold spawn: cell corners at y 91–95, 301 columns vs 245 at the room's y 100)
                // outnumbered the real floor, the room got y 92, nothing linked to it and it was dropped
                int floorY = roomFloorY(reachedCounts.isEmpty() ? counts : reachedCounts, level);
                rooms.put(Cell.columnKey(i, j), new Room(i, j, x0, z0, x1, z1, walkable, floorY));
            }
        }

        // 6) links: a walkable step between the two rooms' own levels. Only the grid neighbour along the step:
        // per-row/column refined edges are staggered at corners, a step there could link two diagonal rooms
        int[] gxs = xs, gzs = zs;
        RoomIndex index = new RoomIndex() {
            @Override
            public Room at(int x, int z) {
                return roomAt(rooms, gxs, gzs, x, z);
            }

            @Override
            public boolean mayLink(Room a, Room b, int dx, int dz) {
                return dx == 0 && dz == 0 ? Math.abs(b.gx() - a.gx()) + Math.abs(b.gz() - a.gz()) == 1
                        : b.gx() - a.gx() == dx && b.gz() - a.gz() == dz;
            }

            @Override
            public boolean skipEdgeNiches() {
                return true;
            }
        };
        List<long[]> links = linkRooms(map, floor, reachable, floors, rooms, index, level, hint);
        return new RoomLayout(xs, zs, confidence, Collections.unmodifiableMap(rooms), List.copyOf(links), size,
                measured, null);
    }

    /**
     * A room's floor height: the most common height inside the band (±{@link #LINK_DROP}) that holds the most
     * floor columns. A plain "most common height" picks hill tops on uneven ground: Illarius' Hideout's pond room
     * spreads its ground over y 98–112 (pond, slopes) while the hills along its walls all stop under the ceiling
     * at 123–125 (80 columns at 124 vs 63 at 100), so the room's level became the hill tops, nothing linked to it
     * and the room was dropped. Of the bands holding at least {@link #LEVEL_SHARE} of the biggest one, the one
     * nearest the dungeon {@code level} wins (MIN_VALUE = unknown: the biggest band). Ties: the bigger band, more
     * columns at the exact height, then the lower height.
     */
    static int roomFloorY(Long2IntOpenHashMap counts, int level) {
        int maxBand = 0;
        for (var h : counts.long2IntEntrySet()) maxBand = Math.max(maxBand, band(counts, (int) h.getLongKey()));
        // a strong band nearest the dungeon level wins over the biggest one: a sunken pit / lower courtyard
        // reached by a 9–12 block fall (Frozen Ruins: 242 columns at y 88–93 vs 213 at the doorways' y 100) must not
        // become the room's level, or the doorways are off its level, nothing links and the room is dropped
        int minBand = level == Integer.MIN_VALUE ? maxBand : Math.max(1, Math.min(maxBand, Math.max(LEVEL_MIN_COLUMNS,
                (int) Math.ceil(maxBand * LEVEL_SHARE))));
        int floorY = 0, bestDist = Integer.MAX_VALUE, bestBand = -1, bestExact = -1;
        for (var h : counts.long2IntEntrySet()) {
            int y = (int) h.getLongKey();
            int band = band(counts, y);
            if (band < minBand) continue;
            int dist = level == Integer.MIN_VALUE ? 0 : Math.abs(y - level);
            int exact = h.getIntValue();
            if (dist < bestDist || dist == bestDist && (band > bestBand || band == bestBand
                    && (exact > bestExact || exact == bestExact && y < floorY))) {
                bestDist = dist;
                bestBand = band;
                bestExact = exact;
                floorY = y;
            }
        }
        // the band's centre need not be its most common height: take that one (the level most of it stands on)
        int best = -1, centre = floorY;
        for (int d = -LINK_DROP; d <= LINK_DROP; d++) {
            int c = counts.get(centre + d);
            if (c > best) {
                best = c;
                floorY = centre + d;
            }
        }
        return floorY;
    }

    /** Floor columns within ±{@link #LINK_DROP} of {@code y}. */
    private static int band(Long2IntOpenHashMap counts, int y) {
        int band = 0;
        for (int d = -LINK_DROP; d <= LINK_DROP; d++) band += counts.get(y + d);
        return band;
    }

    /**
     * Links between rooms you can walk between (Wayfinder moves across a room edge, or a jump pad lift whose pad
     * and landing lie in two rooms), then drops every room nothing links to (sealed pockets, ledges, roof scraps) —
     * except the start room and rooms you can reach on the dungeon level ({@link #KEEP_REACHED}).
     * Shared by the grid and {@link FreeformRooms}; {@code rooms} is modified.
     *
     * @param dungeonLevel the dungeon level ({@link #level}), MIN_VALUE if unknown
     */
    static List<long[]> linkRooms(DungeonMap map, LongPredicate floor, LongSet reachable, Long2IntOpenHashMap floors,
                                  Map<Long, Room> rooms, RoomIndex index, int dungeonLevel, Hint hint) {
        Long2ObjectOpenHashMap<long[]> padLifts = padLifts(map);
        LongOpenHashSet level = roomLevels(map, floor, reachable, floors, rooms, index, padLifts);
        LongOpenHashSet seen = new LongOpenHashSet();
        List<long[]> links = new ArrayList<>();
        for (long c : floors.keySet()) {
            int x = Cell.columnX(c), z = Cell.columnZ(c);
            link(map, floors, level, index, seen, links, x, z, 1, 0);
            link(map, floors, level, index, seen, links, x, z, 0, 1);
        }
        // a jump pad can throw the player over a room edge onto a ledge a few columns away
        for (var e : padLifts.long2ObjectEntrySet()) {
            long pad = e.getLongKey();
            if (!level.contains(pad)) continue;
            Room a = index.at(Cell.x(pad), Cell.z(pad));
            for (long to : e.getValue()) {
                Room b = index.at(Cell.x(to), Cell.z(to));
                if (a == null || b == null || a == b || !level.contains(to)) continue;
                if (!index.mayLink(a, b, 0, 0)) continue;
                addLink(a, b, seen, links);
            }
        }
        // a room nothing connects to is not part of the dungeon (sealed pocket, ledge, roof scrap): drop it.
        // Kept anyway: the start room, and a room on the dungeon level whose own level you can reach — you walk
        // there, so a missed link (a fall in, a pad, an odd doorway) must not wipe it off the map
        LongOpenHashSet keep = new LongOpenHashSet();
        for (long[] l : links) {
            keep.add(l[0]);
            keep.add(l[1]);
        }
        Room start = hint.hasSpawn() ? index.at(hint.spawnX(), hint.spawnZ()) : null;
        if (start != null) keep.add(Cell.columnKey(start.gx(), start.gz()));
        if (dungeonLevel != Integer.MIN_VALUE && !reachable.isEmpty()) {
            Object2IntOpenHashMap<Room> reached = new Object2IntOpenHashMap<>();
            for (long c : level) {
                if (!reachable.contains(c)) continue;
                Room r = index.at(Cell.x(c), Cell.z(c));
                if (r != null) reached.addTo(r, 1);
            }
            for (var e : reached.object2IntEntrySet()) {
                Room r = e.getKey();
                if (e.getIntValue() >= KEEP_REACHED && Math.abs(r.floorY() - dungeonLevel) <= LINK_DROP) {
                    keep.add(Cell.columnKey(r.gx(), r.gz()));
                }
            }
        }
        rooms.keySet().removeIf(k -> !keep.contains(k.longValue()));
        return links;
    }

    /**
     * Dungeon outline: a non-empty column next to a known void column is a piece edge (line = the first
     * column on the high side). Only counted with main-level floor within {@link #EDGE_INSIDE} blocks
     * inside, so the boss arena's outline does not pull the lines.
     */
    private static void footprintEdges(DungeonMap map, Long2IntOpenHashMap floors, LongOpenHashSet edgeX,
                                       LongOpenHashSet edgeZ, Long2IntOpenHashMap evX, Long2IntOpenHashMap evZ) {
        for (ChunkCells chunk : map.chunks()) {
            int bx = chunk.chunkX << 4, bz = chunk.chunkZ << 4;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    if (chunk.isEmptyColumn(lx, lz)) continue;
                    int x = bx + lx, z = bz + lz;
                    if (map.isEmptyColumn(x - 1, z) && floorInside(floors, x, z, 1, 0)) {
                        if (edgeX.add(Cell.columnKey(x, z))) evX.addTo(x, 1);
                    }
                    if (map.isEmptyColumn(x + 1, z) && floorInside(floors, x, z, -1, 0)) {
                        if (edgeX.add(Cell.columnKey(x + 1, z))) evX.addTo(x + 1, 1);
                    }
                    if (map.isEmptyColumn(x, z - 1) && floorInside(floors, x, z, 0, 1)) {
                        if (edgeZ.add(Cell.columnKey(x, z))) evZ.addTo(z, 1);
                    }
                    if (map.isEmptyColumn(x, z + 1) && floorInside(floors, x, z, 0, -1)) {
                        if (edgeZ.add(Cell.columnKey(x, z + 1))) evZ.addTo(z + 1, 1);
                    }
                }
            }
        }
    }

    /**
     * A room side ({@code x = line} if {@code onX}, else {@code z = line}; {@code from..to} on the other axis)
     * is outside the dungeon: no floor and at most {@link #VOID_STRAY} of it non-void (stray blocks, decoration
     * hanging over the edge). Unknown (unscanned) columns count as non-void, so nothing is clipped blindly.
     */
    private static boolean voidLine(DungeonMap map, Long2IntOpenHashMap floors, int line, int from, int to,
                                    boolean onX) {
        int solid = 0, allowed = (int) ((to - from + 1) * VOID_STRAY);
        for (int u = from; u <= to; u++) {
            int x = onX ? line : u, z = onX ? u : line;
            if (floors.containsKey(Cell.columnKey(x, z))) return false;
            if (!map.isEmptyColumn(x, z) && ++solid > allowed) return false;
        }
        return true;
    }

    private static boolean floorInside(Long2IntOpenHashMap floors, int x, int z, int dx, int dz) {
        for (int i = 0; i <= EDGE_INSIDE; i++) {
            if (floors.containsKey(Cell.columnKey(x + dx * i, z + dz * i))) return true;
        }
        return false;
    }

    /**
     * Piece size of this dungeon. Normally {@link #outlineSize}. The outline can be silent or ambiguous when the
     * pieces are packed into one solid block (Desert Temple: no void between pieces, the boss arena under the
     * main level hides a whole side, so maybe one straight edge per axis, or one pair 2 pieces apart). The walls
     * between pieces then decide ({@link #wallScore}):
     * <ul>
     *   <li>no outline answer → the size whose wall-line chain hits walls best (ties → smaller);</li>
     *   <li>outline answer s → s/2, s/3, … replaces it only if its chain hits clearly more walls
     *       ({@link #WALL_MARGIN}); a real size halved runs lines through room centres, so it never does.</li>
     * </ul>
     * A wall-derived size is snapped to the mean gap of its chains (slack lets 18 or 19 chain the same walls as 20).
     * 0 when neither says anything (the caller then uses {@link #FALLBACK_SIZE}).
     */
    static int pieceSize(int[] px, int minX, Long2IntOpenHashMap evX, int[] pz, int minZ, Long2IntOpenHashMap evZ) {
        int outline = outlineSize(evX, evZ);
        int best = 0;
        double bestScore = 0;
        if (outline > 0) {
            best = outline;
            bestScore = wallScore(px, minX, evX, pz, minZ, evZ, outline);
            for (int n = 2; Math.round(outline / (float) n) >= MIN_PIECE; n++) {
                int s = Math.round(outline / (float) n);
                double score = wallScore(px, minX, evX, pz, minZ, evZ, s);
                if (score > bestScore + WALL_MARGIN) {
                    best = s;
                    bestScore = score;
                }
            }
            if (best == outline) return outline;
        } else {
            for (int s = MIN_PIECE; s <= MAX_PIECE; s++) {
                double score = wallScore(px, minX, evX, pz, minZ, evZ, s);
                if (score > bestScore + 1e-9) {
                    best = s;
                    bestScore = score;
                }
            }
            if (best == 0) return 0;
        }
        return meanGap(chain(px, minX, evX, best), chain(pz, minZ, evZ, best), best);
    }

    /** How well the wall-line chains for size s sit on walls, both axes ({@link #wallScore(int[], int[], int)}). */
    private static double wallScore(int[] px, int minX, Long2IntOpenHashMap evX, int[] pz, int minZ,
                                    Long2IntOpenHashMap evZ, int s) {
        return wallScore(px, chain(px, minX, evX, s), minX) + wallScore(pz, chain(pz, minZ, evZ, s), minZ);
    }

    /**
     * Sum over the inner lines of 1 − 2 × (walkable on the line / average walkable per line): a wall line
     * (little floor, a doorway) adds up to +1, a line through a room centre costs about −1 or more. Outer lines
     * (outside the profile) are not judged.
     */
    private static double wallScore(int[] profile, int[] lines, int lo) {
        double total = 0;
        for (int v : profile) total += v;
        if (total <= 0) return 0;
        double avg = total / profile.length, score = 0;
        for (int b : lines) {
            int p = b - lo;
            if (p < 1 || p > profile.length - 2) continue;
            score += 1 - 2 * profile[p] / avg;
        }
        return score;
    }

    /** Rounded mean gap between consecutive lines of both chains ({@code fallback} without any gap). */
    private static int meanGap(int[] xs, int[] zs, int fallback) {
        int sum = 0, n = 0;
        for (int[] lines : new int[][]{xs, zs}) {
            for (int i = 1; i < lines.length; i++) {
                sum += lines[i] - lines[i - 1];
                n++;
            }
        }
        return n == 0 ? fallback : Math.round(sum / (float) n);
    }

    /**
     * Outline-only piece size: every straight outline edge (a line with at least
     * {@link #STRAIGHT_EDGE} edge columns) is a piece edge, so the distance between two parallel ones is a
     * whole number of pieces. Each candidate size s scores every such distance d by how well d ≈ n·s fits
     * (n ≤ {@link #MAX_SPAN}; ±1 for one piece, ±2 for more, since pieces vary a little), divided by n so
     * half the size (fits everything twice over) never beats the real one. Highest score wins, ties → larger.
     * X and Z edges are pooled (pieces are about square). 0 without any fitting pair.
     */
    static int outlineSize(Long2IntOpenHashMap evX, Long2IntOpenHashMap evZ) {
        int[] lx = straightLines(evX), lz = straightLines(evZ);
        int best = 0;
        double bestScore = 0;
        for (int s = MIN_PIECE; s <= MAX_PIECE; s++) {
            double score = fitScore(lx, s) + fitScore(lz, s);
            if (score > 0 && score >= bestScore - 1e-9) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static int[] straightLines(Long2IntOpenHashMap ev) {
        return ev.long2IntEntrySet().stream()
                .filter(e -> e.getIntValue() >= STRAIGHT_EDGE)
                .mapToInt(e -> (int) e.getLongKey()).sorted().toArray();
    }

    private static double fitScore(int[] lines, int s) {
        double score = 0;
        for (int i = 0; i < lines.length; i++) {
            for (int j = i + 1; j < lines.length; j++) {
                int d = lines[j] - lines[i];
                int n = Math.max(1, Math.round(d / (float) s));
                if (n > MAX_SPAN) continue;
                int tol = n == 1 ? 1 : 2, r = Math.abs(d - n * s);
                if (r <= tol) score += (1 - r / (double) (tol + 1)) / n;
            }
        }
        return score;
    }

    /** How far one gap may differ from the piece size (±2, a bit more for big pieces). */
    static int slack(int size) {
        return Math.max(2, Math.round(size * 0.08f));
    }

    /**
     * Chain of wall lines b0 < b1 < … with gaps in size ± {@link #slack}, b0 ≤ lo, last > lo + profile
     * length - 1, minimising walkable columns crossed minus footprint-edge evidence ({@code edges}: line
     * coordinate → edge columns). Lines outside the profile cross no floor.
     */
    static int[] chain(int[] profile, int lo, Long2IntOpenHashMap edges, int size) {
        return chain(profile, lo, edges, size, Integer.MIN_VALUE).lines();
    }

    private record Chain(int[] lines, double cost) {}

    /**
     * {@link #chain(int[], int, Long2IntOpenHashMap, int)} that must contain a line at {@code force}
     * ({@link Integer#MIN_VALUE} = free). Null when no chain can contain it.
     */
    private static Chain chain(int[] profile, int lo, Long2IntOpenHashMap edges, int size, int force) {
        int minSize = size - slack(size), maxSize = size + slack(size);
        int n = profile.length;
        int first = -maxSize + 1, last = n + maxSize; // positions relative to lo
        int slots = last - first + 1;
        int forced = force == Integer.MIN_VALUE ? -1 : force - lo - first;
        if (force != Integer.MIN_VALUE && (forced < 0 || forced >= slots)) return null; // outside every chain
        double[] dp = new double[slots];
        int[] prev = new int[slots];
        Arrays.fill(dp, Double.MAX_VALUE);
        for (int p = first; p <= last; p++) {
            int idx = p - first;
            double cost = (p >= 0 && p < n ? profile[p] : 0) - EDGE_WEIGHT * edges.get(lo + p);
            if (p <= 0 && (forced < 0 || idx <= forced)) { // a start past the forced line would skip it
                dp[idx] = cost;
                prev[idx] = -1;
                continue;
            }
            for (int g = minSize; g <= maxSize; g++) {
                int q = idx - g;
                if (q < 0 || dp[q] == Double.MAX_VALUE) continue;
                if (forced >= 0 && q < forced && idx > forced) continue; // jumps over the forced line
                double v = dp[q] + cost + Math.abs(g - size) * SIZE_PENALTY;
                if (v < dp[idx]) {
                    dp[idx] = v;
                    prev[idx] = q;
                }
            }
        }
        int end = -1;
        for (int p = n; p <= last; p++) {
            int idx = p - first;
            if (forced >= 0 && idx < forced) continue;
            if (dp[idx] != Double.MAX_VALUE && (end < 0 || dp[idx] < dp[end])) end = idx;
        }
        if (end < 0) return null;
        List<Integer> out = new ArrayList<>();
        for (int idx = end; idx >= 0; idx = prev[idx]) out.add(idx + first + lo);
        Collections.reverse(out);
        return new Chain(out.stream().mapToInt(Integer::intValue).toArray(), dp[end]);
    }

    /**
     * Wall-line chain anchored on the spawn room: the Telos builder starts the piece grid there, so one line
     * is the spawn piece's low edge, about half a piece before the spawn (±{@code size/4}, the spawn is not
     * always the room centre). Each candidate edge is forced into the DP; the cheapest chain wins, ties →
     * closest to the half-piece guess. The spawn room is therefore never cut by a grid line.
     */
    static int[] anchoredChain(int[] profile, int lo, Long2IntOpenHashMap edges, int size, int spawn) {
        int guess = spawn - size / 2, reach = Math.max(2, size / 4);
        Chain best = null;
        for (int d = 0; d <= reach; d++) {
            for (int a : d == 0 ? new int[]{guess} : new int[]{guess - d, guess + d}) {
                Chain c = chain(profile, lo, edges, size, a);
                if (c != null && (best == null || c.cost() < best.cost() - 1e-9)) best = c;
            }
        }
        return best != null ? best.lines() : chain(profile, lo, edges, size);
    }

    /** 1 - (walkable on the inner wall lines / average walkable per line), clamped to 0..1. */
    private static double lineConfidence(int[] profile, int lo, int[] lines) {
        double total = 0;
        for (int v : profile) total += v;
        double avg = total / profile.length;
        double onLines = 0;
        int count = 0;
        for (int b : lines) {
            int p = b - lo;
            if (p < 0 || p >= profile.length) continue;
            onLines += profile[p];
            count++;
        }
        if (count == 0 || avg <= 0) return 0;
        return Math.max(0, Math.min(1, 1 - (onLines / count) / avg));
    }

    /**
     * Best position for one room edge near {@code line}: the line (x = const if {@code onX}) with the
     * fewest walkable columns and the most footprint edges between {@code from}..{@code to} on the other
     * axis; ties keep it close.
     */
    private static int refine(Long2IntOpenHashMap floors, LongOpenHashSet edges, int line, int from, int to,
                              boolean onX) {
        int best = line;
        double bestCost = Double.MAX_VALUE;
        for (int d = -REFINE; d <= REFINE; d++) {
            int v = line + d, walkable = 0, edge = 0;
            for (int u = from; u <= to; u++) {
                long key = onX ? Cell.columnKey(v, u) : Cell.columnKey(u, v);
                if (floors.containsKey(key)) walkable++;
                if (edges.contains(key)) edge++;
            }
            double cost = walkable - EDGE_WEIGHT * edge + Math.abs(d) * 0.5;
            if (cost < bestCost) {
                bestCost = cost;
                best = v;
            }
        }
        return best;
    }

    /**
     * Walkable columns → the floor stand you walk on. With a known spawn level ({@code refY}) that is the
     * column's stand closest to it (ties: the lower one) — walkable roofs / upper walkways under a barrier
     * ceiling (Frozen Ruins roof at y 113, spawn 100) must not lift the room level. Without one: the highest.
     */
    public static Long2IntOpenHashMap topFloors(DungeonMap map, LongPredicate floor, int refY) {
        if (refY != Integer.MIN_VALUE) return nearestFloors(map, floor, refY);
        return topFloors(map, floor);
    }

    private static Long2IntOpenHashMap nearestFloors(DungeonMap map, LongPredicate floor, int refY) {
        Long2IntOpenHashMap all = new Long2IntOpenHashMap();
        for (ChunkCells chunk : map.chunks()) {
            int bx = chunk.chunkX << 4, bz = chunk.chunkZ << 4;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    short[] s = chunk.stand(lx, lz);
                    if (s.length == 0) continue;
                    int x = bx + lx, z = bz + lz;
                    int best = Integer.MIN_VALUE, bestDist = Integer.MAX_VALUE;
                    for (short y : s) { // ascending: a tie keeps the lower stand
                        int d = Math.abs(y - refY);
                        if (d < bestDist && floor.test(Cell.pack(x, y, z))) {
                            bestDist = d;
                            best = y;
                        }
                    }
                    if (best != Integer.MIN_VALUE) all.put(Cell.columnKey(x, z), best);
                }
            }
        }
        return all;
    }

    /** Walkable columns → the column's highest floor stand (the level you walk on). */
    public static Long2IntOpenHashMap topFloors(DungeonMap map, LongPredicate floor) {
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

    /**
     * The dungeon level, the one height every "main level" check uses: the start room's feet Y
     * ({@link Hint#spawnY}, the return portal), else {@link #mainLevel} (median floor).
     */
    public static int level(Hint hint, Long2IntOpenHashMap topFloors) {
        return hint.spawnY() != Integer.MIN_VALUE ? hint.spawnY() : mainLevel(topFloors);
    }

    /** Median floor height of {@link #topFloors}, or MIN_VALUE when too little is scanned. Fallback of {@link #level}. */
    public static int mainLevel(Long2IntOpenHashMap topFloors) {
        if (topFloors.size() < 64) return Integer.MIN_VALUE;
        int[] heights = topFloors.values().toIntArray();
        Arrays.sort(heights);
        return heights[heights.length / 2];
    }

    /**
     * Each room's own level: the room's floor split into walk-connected parts (steps / drops ≤ {@link #LINK_DROP},
     * ladders, pad lifts — the {@link PathFinder} moves — plus falls of 7–16 blocks into a pit, without leaving
     * the room). The part(s) at {@code floorY} are flooded first, so a pit you drop into belongs to them.
     * Deeper falls (a boss arena's ledges under the floor slab, ~30+ down) stay apart.
     * <p>
     * Only the room's real space counts: the floor can fall apart into separate parts (in
     * Thornwood Wargrove the octagonal rooms leave sealed corner pockets between their walls, open ground
     * under the barrier ceiling, which touch the next room's pocket across the edge). Kept: every part the
     * player can reach; if the player reached none of them yet (unexplored / sealed room), the biggest part.
     */
    private static LongOpenHashSet roomLevels(DungeonMap map, LongPredicate floor, LongSet reachable,
                                              Long2IntOpenHashMap floors, Map<Long, Room> rooms,
                                              RoomIndex index, Long2ObjectOpenHashMap<long[]> padLifts) {
        // jump pads join a pit to the ledge above it both ways (a room whose floor is the ledge owns the pit)
        Long2ObjectOpenHashMap<LongArrayList> padsBelow = new Long2ObjectOpenHashMap<>();
        for (var e : padLifts.long2ObjectEntrySet()) {
            for (long to : e.getValue()) padsBelow.computeIfAbsent(to, k -> new LongArrayList()).add(e.getLongKey());
        }
        LongOpenHashSet level = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long[] next = new long[PathFinder.NEIGHBOURS];
        double[] cost = new double[PathFinder.NEIGHBOURS];
        for (Room room : rooms.values()) {
            LongOpenHashSet seen = new LongOpenHashSet();
            List<LongOpenHashSet> parts = new ArrayList<>();
            // seeds: the room's floorY columns first (that part also takes the pits it drops into), then every other
            // floor column — a room whose floor spans several levels (cave slopes, a ledge over a pit, a courtyard
            // 10 below the doorways) keeps all of them as parts instead of only what one height can walk to
            for (int pass = 0; pass < 2; pass++)
            for (int x = room.minX(); x <= room.maxX(); x++) {
                for (int z = room.minZ(); z <= room.maxZ(); z++) {
                    long key = Cell.columnKey(x, z);
                    if (!floors.containsKey(key) || (floors.get(key) == room.floorY()) != (pass == 0)) continue;
                    if (index.at(x, z) != room) continue;
                    long seed = Cell.pack(x, floors.get(key), z);
                    if (!seen.add(seed)) continue;
                    // flood one connected part from this seed
                    LongOpenHashSet part = new LongOpenHashSet();
                    part.add(seed);
                    queue.enqueue(seed);
                    while (!queue.isEmpty()) {
                        long cell = queue.dequeueLong();
                        int y = Cell.y(cell);
                        int n = PathFinder.neighbours(map, cell, next, cost);
                        LongArrayList below = padsBelow.get(cell);
                        int nb = below == null ? 0 : below.size();
                        for (int i = 0; i < n + nb; i++) {
                            long c = i < n ? next[i] : below.getLong(i - n);
                            int cx = Cell.x(c), cz = Cell.z(c);
                            boolean climb = cx == Cell.x(cell) && cz == Cell.z(cell);
                            boolean lift = i >= n || Cell.y(c) - y >= 2; // only pad lifts rise 2+ to another column
                            if (!climb && !lift && Math.abs(Cell.y(c) - y) > LINK_DROP) continue;
                            if (seen.contains(c) || !floor.test(c) || index.at(cx, cz) != room) continue;
                            seen.add(c);
                            part.add(c);
                            queue.enqueue(c);
                        }
                        // a pit / lower courtyard you drop into (7–16 blocks, Reachability) is this room too
                        int nf = Reachability.longFalls(map, cell, next);
                        for (int i = 0; i < nf; i++) {
                            long c = next[i];
                            if (seen.contains(c) || !floor.test(c) || index.at(Cell.x(c), Cell.z(c)) != room) continue;
                            seen.add(c);
                            part.add(c);
                            queue.enqueue(c);
                        }
                    }
                    parts.add(part);
                }
            }
            boolean anyReached = false;
            for (LongOpenHashSet part : parts) {
                if (touches(part, reachable) && !(index.skipEdgeNiches() && edgeNiche(part, room))) {
                    level.addAll(part);
                    anyReached = true;
                }
            }
            if (!anyReached) {
                LongOpenHashSet biggest = null;
                for (LongOpenHashSet part : parts) if (biggest == null || part.size() > biggest.size()) biggest = part;
                if (biggest != null) level.addAll(biggest);
            }
        }
        return level;
    }

    /**
     * A small part that hugs one room edge (all within {@link #NICHE_DEPTH} of it): the neighbour's side of
     * the wall that the room bounds overlap (Anubis Lair: lever niches in the west room's wall face at the
     * boss-drop room's edge). Reached from the other room only, so it must not make a link through the wall.
     */
    private static boolean edgeNiche(LongOpenHashSet part, Room room) {
        if (part.size() > NICHE_MAX) return false;
        boolean w = true, e = true, n = true, s = true;
        for (long c : part) {
            int x = Cell.x(c), z = Cell.z(c);
            w &= x - room.minX() < NICHE_DEPTH;
            e &= room.maxX() - x < NICHE_DEPTH;
            n &= z - room.minZ() < NICHE_DEPTH;
            s &= room.maxZ() - z < NICHE_DEPTH;
        }
        return w || e || n || s;
    }

    private static boolean touches(LongOpenHashSet part, LongSet reachable) {
        if (reachable.isEmpty()) return false;
        for (long c : part) if (reachable.contains(c)) return true;
        return false;
    }

    /** Floor column (x,z) and its neighbour along (dx,dz) are in different rooms and a player can step across. */
    private static void link(DungeonMap map, Long2IntOpenHashMap floors, LongOpenHashSet level,
                             RoomIndex index, LongOpenHashSet seen, List<long[]> links,
                             int x, int z, int dx, int dz) {
        int nx = x + dx, nz = z + dz;
        if (!floors.containsKey(Cell.columnKey(nx, nz))) return;
        Room a = index.at(x, z);
        if (a == null) return;
        Room b = index.at(nx, nz);
        if (b == null || b == a || !index.mayLink(a, b, dx, dz)) return;
        if (seen.contains(pairKey(a, b)) || !canStep(map, level, x, z, nx, nz)) return;
        addLink(a, b, seen, links);
    }

    private static long pairKey(Room a, Room b) {
        long ka = Cell.columnKey(a.gx(), a.gz()), kb = Cell.columnKey(b.gx(), b.gz());
        Room rl = ka < kb ? a : b, rh = ka < kb ? b : a;
        return ((rl.gx() & 0xFFFFL) << 48) | ((rl.gz() & 0xFFFFL) << 32) | ((rh.gx() & 0xFFFFL) << 16) | (rh.gz() & 0xFFFFL);
    }

    private static void addLink(Room a, Room b, LongOpenHashSet seen, List<long[]> links) {
        if (!seen.add(pairKey(a, b))) return;
        long ka = Cell.columnKey(a.gx(), a.gz()), kb = Cell.columnKey(b.gx(), b.gz());
        links.add(new long[]{Math.min(ka, kb), Math.max(ka, kb)});
    }

    /** Every known jump pad's stand cell → where it can throw the player ({@link PathFinder#lifts}). */
    private static Long2ObjectOpenHashMap<long[]> padLifts(DungeonMap map) {
        Long2ObjectOpenHashMap<long[]> out = new Long2ObjectOpenHashMap<>();
        long[] next = new long[PathFinder.MAX_LANDINGS];
        double[] cost = new double[PathFinder.MAX_LANDINGS];
        for (long pad : map.padCells()) {
            int n = PathFinder.lifts(map, Cell.x(pad), Cell.y(pad), Cell.z(pad), next, cost, 0);
            if (n > 0) out.put(pad, Arrays.copyOf(next, n));
        }
        return out;
    }

    /**
     * A Wayfinder move ({@link PathFinder#neighbours}: walk, jump up 1, drop ≤ {@link #LINK_DROP} here) leads
     * from a room-level stand in column a to a room-level stand in column b ({@link #roomLevels}), either way.
     * Same move rules as the route, so the room line never links rooms the Wayfinder can't walk between.
     */
    private static boolean canStep(DungeonMap map, LongOpenHashSet level, int ax, int az, int bx, int bz) {
        return moveAcross(map, level, ax, az, bx, bz) || moveAcross(map, level, bx, bz, ax, az);
    }

    private static boolean moveAcross(DungeonMap map, LongOpenHashSet level, int fx, int fz, int tx, int tz) {
        long[] next = new long[PathFinder.NEIGHBOURS];
        double[] cost = new double[PathFinder.NEIGHBOURS];
        for (short y : map.stand(fx, fz)) {
            long from = Cell.pack(fx, y, fz);
            if (!level.contains(from)) continue;
            int n = PathFinder.neighbours(map, from, next, cost);
            for (int i = 0; i < n; i++) {
                long c = next[i];
                if (Cell.x(c) != tx || Cell.z(c) != tz) continue;
                if (y - Cell.y(c) > LINK_DROP) continue; // deep drops (boss hole) are not room links
                if (level.contains(c)) return true;
            }
        }
        return false;
    }
}
