package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Snapshot of everything derived from a {@link DungeonMap} version (computed on the worker thread).
 *
 * @param mapVersion the {@link DungeonMap#version()} it was computed from
 * @param reachable  standable cells the player can reach (union over every position stood at); read-only
 * @param holes      deep drops next to dungeon floor, best boss-drop candidate first
 * @param rooms      room grid over dungeon floor (minimap data)
 * @param level      the dungeon level: the start room's feet Y (return portal, {@link RoomLayout#level}); every
 *                   "main level" check and the room drawing use it. MIN_VALUE if unknown and too little is scanned
 * @param bossY      lowest landing of the boss drop (the boss level), MIN_VALUE if no boss drop is known
 * @param bossDrop   the boss drop, or null. With a boss anchor only a drop that really falls into the boss level
 *                   (never the best-scoring room fall as a guess); without one, the best main-level candidate.
 */
public record DungeonAnalysis(long mapVersion, LongSet reachable, List<Hole> holes, RoomLayout rooms,
                              int level, int bossY, Hole bossDrop) {

    public static final DungeonAnalysis EMPTY = new DungeonAnalysis(-1, LongSets.EMPTY_SET, List.of(), RoomLayout.EMPTY,
            Integer.MIN_VALUE, Integer.MIN_VALUE, null);

    /** Max cells one flood may add (a huge dungeon is still only ~100k cells). */
    private static final int MAX_REACH = 1_000_000;

    /**
     * @param reached running union of reachable cells, owned by the caller's worker thread; a fresh
     *                flood from {@code start} is added to it ({@code start} may be {@link Long#MIN_VALUE}:
     *                no flood, e.g. mid-air). Re-flooding each time picks up chunks scanned since.
     * @param bossAnchor where the boss is (boss-bar mob feet, or the boss nameplate while the boss is invisible),
     *                   null if not seen: the boss level is then flooded from the floor under it and the boss
     *                   drop is the drop falling into it (see {@link BossArena#floodFromAnchor})
     */
    public static DungeonAnalysis compute(DungeonMap map, int minDropDepth, long start, LongOpenHashSet reached,
                                          RoomLayout.Hint hint, Point bossAnchor) {
        long version = map.version();
        // never flood from the roof top (flying / spectating above the dungeon): it would make the whole
        // roof "reachable floor" and every room look connected
        if (start != Long.MIN_VALUE && !map.isSkyExposed(Cell.x(start), Cell.y(start) + 1, Cell.z(start))) {
            reached.addAll(Reachability.flood(map, start, MAX_REACH));
        }
        LongSet snapshot = LongSets.unmodifiable(new LongOpenHashSet(reached));
        LongPredicate anyFloor = cell -> isFloor(map, snapshot, cell);
        LongPredicate floor = anyFloor;

        // boss level: the air around the boss anchor (else under the drop on the main level), see BossArena.
        // Its ledges are standable and under a ceiling, so they would count as floor: fake rooms/links and
        // ledge "holes".
        // one dungeon level for everything: the start room's feet Y (every dungeon starts at its return portal).
        // The median floor height is only the fallback without a start point: on uneven ground it sits on the hill
        // tops (Illarius' Hideout: 104 vs rooms at 100), which swallowed rooms into the boss air (0.18.10)
        int level = RoomLayout.level(hint, RoomLayout.topFloors(map, floor, hint.spawnY()));
        // only drops you can walk to: an unreachable floor under a ceiling (the crawl space on top of a boss
        // dome, a sealed ledge) still counts as floor for rooms, but a "hole" there is a fall you can never take
        List<Hole> holes = reachableOnly(DropDetector.detect(map, minDropDepth, floor), snapshot);
        // dungeon rule: no boss level (the goal is the clear mobs). No boss air and no boss drop — a deep fall
        // here is scenery (Illarius' Hideout: hill tops 10 above the ground were picked as the "boss drop")
        if (!hint.rules().bossLevel()) {
            return new DungeonAnalysis(version, snapshot, holes, RoomLayout.detect(map, floor, snapshot, hint), level,
                    Integer.MIN_VALUE, null);
        }
        boolean anchored = bossAnchor != null;
        // the boss air stops 2 below the dungeon level
        LongOpenHashSet arena = anchored
                ? BossArena.floodFromAnchor(map, BossArena.anchorCell(map, bossAnchor), level)
                : BossArena.flood(map, BossArena.pickMainLevelDrop(fallsBelow(holes, level), level), level, minDropDepth);
        int bossY = Integer.MIN_VALUE;
        if (!arena.isEmpty()) {
            bossY = Integer.MAX_VALUE;
            for (long c : arena) bossY = Math.min(bossY, Cell.y(c)); // lowest open cell = feet on the arena floor
            // the boss air rises up the drop shaft and can spill into the steps down to its rim (Tomb of Shadows:
            // 1×1 shaft at (83, 59), rim on a stair at y 97 under level 100): cells you can walk to near the main
            // level stay floor, or the real drop loses its rim and vanishes
            int walkLevel = level == Integer.MIN_VALUE ? Integer.MAX_VALUE : level - BossArena.MAIN_BAND;
            LongPredicate main = cell -> anyFloor.test(cell) && (!BossArena.contains(arena, cell)
                    || (snapshot.contains(cell) && Cell.y(cell) >= walkLevel));
            LongOpenHashSet bossAir = arena;
            holes = reachableOnly(DropDetector.detect(map, minDropDepth, main,
                    cell -> BossArena.contains(bossAir, cell)), snapshot);
            floor = main;
        }
        // anchored: only a drop that really falls into the boss level (null until its rim is scanned)
        // only falls that land under the detected dungeon level: a drop off a raised platform / hill
        // top back onto the level is never the way down to the boss
        List<Hole> candidates = fallsBelow(holes, level);
        Hole boss = anchored ? BossArena.pickDropInto(map, candidates, arena) : BossArena.pickMainLevelDrop(candidates, level);
        holes = bossFirst(holes, boss);
        // anchored and nothing falls into the arena yet: no boss drop (a random room fall is worse than none)
        Hole bossDrop = boss != null ? boss : anchored || candidates.isEmpty() ? null : candidates.getFirst();
        return new DungeonAnalysis(version, snapshot, holes, RoomLayout.detect(map, floor, snapshot, hint).withDropRoom(bossDrop), level,
                bossY, bossDrop);
    }

    /**
     * Holes with at least one rim cell the player can walk to ({@code reachable}, the running union of where
     * you stood). A drop behind a still-closed wall appears once the wall opens and the flood reaches its rim.
     */
    static List<Hole> reachableOnly(List<Hole> holes, LongSet reachable) {
        if (holes.isEmpty()) return holes;
        List<Hole> out = new ArrayList<>(holes.size());
        for (Hole h : holes) {
            for (long c : h.approach()) {
                if (reachable.contains(c)) {
                    out.add(h);
                    break;
                }
            }
        }
        return out;
    }

    /** A boss drop must land at least this far under the dungeon level (the boss air starts at level − 2). */
    static final int BELOW_LEVEL = 3;

    /**
     * Boss drop candidates: holes whose fall lands under the dungeon {@code level} (rim − depth ≤ level − 3).
     * The rim itself may sit above the level (Corvus Crypt: rim 105 over level 100); a drop off a hill top or
     * raised platform that only lands back on the level is never the way to the boss. Unknown level: all holes.
     */
    static List<Hole> fallsBelow(List<Hole> holes, int level) {
        if (level == Integer.MIN_VALUE || holes.isEmpty()) return holes;
        List<Hole> out = new ArrayList<>(holes.size());
        for (Hole h : holes) if (h.y() - h.depth() <= level - BELOW_LEVEL) out.add(h);
        return out;
    }

    /** {@code boss} moved to the front ({@link #bossDrop()}), the rest in score order. */
    private static List<Hole> bossFirst(List<Hole> holes, Hole boss) {
        if (boss == null || holes.isEmpty() || holes.getFirst() == boss) return holes;
        List<Hole> out = new ArrayList<>(holes.size());
        out.add(boss);
        for (Hole h : holes) if (h != boss) out.add(h);
        return out;
    }

    /**
     * Plausible dungeon floor: reachable from where the player stood, or under a ceiling. Excludes the
     * top of the roof (open to the sky, never reachable), but keeps sections that are still sealed off
     * (Telos opens walls as you progress) so their drops are known before you get there.
     */
    public static boolean isFloor(DungeonMap map, LongSet reachable, long cell) {
        return reachable.contains(cell) || !map.isSkyExposed(Cell.x(cell), Cell.y(cell) + 1, Cell.z(cell));
    }
}
