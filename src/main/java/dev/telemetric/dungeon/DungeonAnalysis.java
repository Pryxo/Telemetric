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
 * @param mainY      main floor level (median top floor stand), MIN_VALUE if too little is scanned
 * @param bossY      lowest landing of the boss drop (the boss level), MIN_VALUE if no boss drop is known
 * @param bossDrop   the boss drop, or null. With a boss anchor only a drop that really falls into the boss level
 *                   (never the best-scoring room fall as a guess); without one, the best main-level candidate.
 */
public record DungeonAnalysis(long mapVersion, LongSet reachable, List<Hole> holes, RoomLayout rooms,
                              int mainY, int bossY, Hole bossDrop) {

    public static final DungeonAnalysis EMPTY = new DungeonAnalysis(-1, LongSets.EMPTY_SET, List.of(), RoomLayout.EMPTY,
            Integer.MIN_VALUE, Integer.MIN_VALUE, null);

    /** Max cells one flood may add (a huge dungeon is still only ~100k cells). */
    private static final int MAX_REACH = 1_000_000;

    /**
     * @param reached running union of reachable cells, owned by the caller's worker thread; a fresh
     *                flood from {@code start} is added to it ({@code start} may be {@link Long#MIN_VALUE}:
     *                no flood, e.g. mid-air). Re-flooding each time picks up chunks scanned since.
     */
    /**
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
        // main level: floors nearest the spawn Y (the player enters on the room level), median of those
        int mainY = RoomLayout.mainLevel(RoomLayout.topFloors(map, floor, hint.spawnY()));
        // only drops you can walk to: an unreachable floor under a ceiling (the crawl space on top of a boss
        // dome, a sealed ledge) still counts as floor for rooms, but a "hole" there is a fall you can never take
        List<Hole> holes = reachableOnly(DropDetector.detect(map, minDropDepth, floor), snapshot);
        boolean anchored = bossAnchor != null;
        LongOpenHashSet arena = anchored
                ? BossArena.floodFromAnchor(map, BossArena.anchorCell(map, bossAnchor), mainY)
                : BossArena.flood(map, BossArena.pickMainLevelDrop(holes, mainY), mainY, minDropDepth);
        int bossY = Integer.MIN_VALUE;
        if (!arena.isEmpty()) {
            bossY = Integer.MAX_VALUE;
            for (long c : arena) bossY = Math.min(bossY, Cell.y(c)); // lowest open cell = feet on the arena floor
            // the boss air rises up the drop shaft and can spill into the steps down to its rim (Tomb of Shadows:
            // 1×1 shaft at (83, 59), rim on a stair at y 97 under mainY 100): cells you can walk to near the main
            // level stay floor, or the real drop loses its rim and vanishes
            int walkLevel = mainY == Integer.MIN_VALUE ? Integer.MAX_VALUE : mainY - BossArena.MAIN_BAND;
            LongPredicate main = cell -> anyFloor.test(cell) && (!BossArena.contains(arena, cell)
                    || (snapshot.contains(cell) && Cell.y(cell) >= walkLevel));
            LongOpenHashSet bossAir = arena;
            holes = reachableOnly(DropDetector.detect(map, minDropDepth, main,
                    cell -> BossArena.contains(bossAir, cell)), snapshot);
            floor = main;
        }
        // anchored: only a drop that really falls into the boss level (null until its rim is scanned)
        Hole boss = anchored ? BossArena.pickDropInto(map, holes, arena) : BossArena.pickMainLevelDrop(holes, mainY);
        holes = bossFirst(holes, boss);
        // anchored and nothing falls into the arena yet: no boss drop (a random room fall is worse than none)
        Hole bossDrop = boss != null ? boss : anchored || holes.isEmpty() ? null : holes.getFirst();
        return new DungeonAnalysis(version, snapshot, holes, RoomLayout.detect(map, floor, snapshot, hint).withDropRoom(bossDrop), mainY,
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
