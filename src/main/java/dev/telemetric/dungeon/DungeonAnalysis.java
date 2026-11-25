package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Snapshot of everything derived from one {@link DungeonMap} version, computed on the worker thread.
 *
 * @param mapVersion the {@link DungeonMap#version()} it was computed from
 * @param reachable  standable cells the player can reach (union over every position stood at); read-only
 * @param holes      deep drops next to dungeon floor, best boss-drop candidate first
 * @param level      the dungeon level, which is the start's feet Y (return portal, {@link DungeonLevel#of}).
 *                   Every "main level" check uses it. MIN_VALUE if unknown and too little is scanned
 * @param bossY      lowest landing of the boss drop (the boss level), MIN_VALUE if no boss drop is known
 * @param bossDrop   the boss drop, or null. With a boss anchor this is only a drop that really falls into the boss
 *                   level, never the best-scoring room fall as a guess. Without one, it's the best main-level candidate.
 */
public record DungeonAnalysis(long mapVersion, LongSet reachable, List<Hole> holes,
                              int level, int bossY, Hole bossDrop) {

    public static final DungeonAnalysis EMPTY = new DungeonAnalysis(-1, LongSets.EMPTY_SET, List.of(),
            Integer.MIN_VALUE, Integer.MIN_VALUE, null);

    /** The most cells one flood may add. Even a huge dungeon is only about 100k cells. */
    private static final int MAX_REACH = 1_000_000;

    /**
     * @param reached running union of reachable cells, owned by the caller's worker thread. A fresh flood from
     *                {@code start} gets added to it ({@code start} may be {@link Long#MIN_VALUE}, meaning no flood,
     *                e.g. mid-air). Re-flooding every time picks up the chunks scanned since.
     * @param bossAnchor where the boss is (boss-bar mob feet, or the boss nameplate while the boss is invisible), or
     *                   null if not seen. With an anchor the boss level is flooded from the floor under it and the
     *                   boss drop is the drop that falls into it (see {@link BossArena#floodFromAnchor})
     */
    public static DungeonAnalysis compute(DungeonMap map, int minDropDepth, long start, LongOpenHashSet reached,
                                          DungeonHint hint, Point bossAnchor) {
        long version = map.version();
        // Never flood from the top of the roof (flying or spectating above the dungeon). It would turn the whole roof
        // into "reachable floor" and make every room look connected.
        if (start != Long.MIN_VALUE && !map.isSkyExposed(Cell.x(start), Cell.y(start) + 1, Cell.z(start))) {
            reached.addAll(Reachability.flood(map, start, MAX_REACH));
        }
        LongSet snapshot = LongSets.unmodifiable(new LongOpenHashSet(reached));
        LongPredicate anyFloor = cell -> isFloor(map, snapshot, cell);
        LongPredicate floor = anyFloor;

        // The boss level is the air around the boss anchor (or under the drop on the main level without one), see
        // BossArena. Its ledges are standable and have a ceiling, so they'd count as floor and cause fake ledge
        // "holes".
        // Everything uses one dungeon level: the start's feet Y (every dungeon starts at its return portal).
        // The median floor height is only a fallback when there's no start point, because on uneven ground it ends
        // up on the hill tops (Illarius' Hideout: 104 vs rooms at 100), which swallowed rooms into the boss air (0.18.10).
        int level = DungeonLevel.of(map, floor, hint);
        // Only drops you can walk to. An unreachable floor under a ceiling (the crawl space on top of a boss dome, a
        // sealed ledge) still counts as floor, but a "hole" there is a fall you can never take.
        List<Hole> holes = reachableOnly(DropDetector.detect(map, minDropDepth, floor), snapshot);
        // Dungeon rule: no boss level (the goal is the clear mobs). So no boss air and no boss drop; a deep fall here is
        // just scenery (in Illarius' Hideout, hill tops 10 above the ground got picked as the "boss drop").
        if (!hint.rules().bossLevel()) {
            return new DungeonAnalysis(version, snapshot, holes, level, Integer.MIN_VALUE, null);
        }
        boolean anchored = bossAnchor != null;
        // the boss air stops 2 below the dungeon level
        LongOpenHashSet arena = anchored
                ? BossArena.floodFromAnchor(map, BossArena.anchorCell(map, bossAnchor), level)
                : BossArena.flood(map, BossArena.pickMainLevelDrop(fallsBelow(holes, level), level), level, minDropDepth);
        int bossY = Integer.MIN_VALUE;
        if (!arena.isEmpty()) {
            bossY = Integer.MAX_VALUE;
            for (long c : arena) bossY = Math.min(bossY, Cell.y(c)); // the lowest open cell is the feet height on the arena floor
            // The boss air rises up the drop shaft and can spill into the steps down to its rim (Tomb of Shadows: a 1×1
            // shaft at (83, 59) with its rim on a stair at y 97, under level 100). Cells you can walk to near the main
            // level stay floor, otherwise the real drop loses its rim and disappears.
            int walkLevel = level == Integer.MIN_VALUE ? Integer.MAX_VALUE : level - BossArena.MAIN_BAND;
            LongPredicate main = cell -> anyFloor.test(cell) && (!BossArena.contains(arena, cell)
                    || (snapshot.contains(cell) && Cell.y(cell) >= walkLevel));
            LongOpenHashSet bossAir = arena;
            holes = reachableOnly(DropDetector.detect(map, minDropDepth, main,
                    cell -> BossArena.contains(bossAir, cell)), snapshot);
        }
        // With an anchor, only a drop that really falls into the boss level counts (null until its rim is scanned).
        // Only falls that land below the detected dungeon level qualify; dropping off a raised platform or a hill
        // top back onto the level is never the way down to the boss.
        List<Hole> candidates = fallsBelow(holes, level);
        Hole boss = anchored ? BossArena.pickDropInto(map, candidates, arena) : BossArena.pickMainLevelDrop(candidates, level);
        holes = bossFirst(holes, boss);
        // anchored, but nothing falls into the arena yet: no boss drop (a random room fall is worse than none)
        Hole bossDrop = boss != null ? boss : anchored || candidates.isEmpty() ? null : candidates.getFirst();
        return new DungeonAnalysis(version, snapshot, holes, level, bossY, bossDrop);
    }

    /**
     * Holes with at least one rim cell the player can walk to ({@code reachable}, the running union of where you've
     * stood). A drop behind a wall that's still closed shows up once the wall opens and the flood reaches its rim.
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

    /** A boss drop has to land at least this far below the dungeon level (the boss air starts at level − 2). */
    static final int BELOW_LEVEL = 3;

    /**
     * Boss drop candidates: holes whose fall lands below the dungeon {@code level} (rim − depth ≤ level − 3).
     * The rim itself can be above the level (Corvus Crypt: rim 105 over level 100). A drop off a hill top or raised
     * platform that just lands back on the level is never the way to the boss. If the level is unknown, all holes
     * qualify.
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
     * Plausible dungeon floor: reachable from where the player stood, or under a ceiling. That rules out the top of the
     * roof (open to the sky, never reachable) but keeps sections that are still sealed off, since Telos opens walls as
     * you progress and we want to know their drops before you get there.
     */
    public static boolean isFloor(DungeonMap map, LongSet reachable, long cell) {
        return reachable.contains(cell) || !map.isSkyExposed(Cell.x(cell), Cell.y(cell) + 1, Cell.z(cell));
    }
}
