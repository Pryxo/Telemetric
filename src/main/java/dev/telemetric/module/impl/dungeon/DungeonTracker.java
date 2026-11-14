package dev.telemetric.module.impl.dungeon;

import dev.telemetric.Telemetric;
import dev.telemetric.config.PieceSizeTable;
import dev.telemetric.dungeon.Cell;
import dev.telemetric.dungeon.DungeonAnalysis;
import dev.telemetric.dungeon.DungeonMap;
import dev.telemetric.dungeon.PathFinder;
import dev.telemetric.dungeon.Point;
import dev.telemetric.dungeon.RoomLayout;
import dev.telemetric.dungeon.rules.DungeonRules;
import dev.telemetric.dungeon.rules.DungeonRulebook;
import dev.telemetric.game.BossBarAdapter;
import dev.telemetric.game.DungeonAdapter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Shared dungeon state for every dungeon feature (Wayfinder now, the minimap later): the walk map,
 * glowing treasures, and the derived analysis (holes, room grid).
 * <p>
 * Runs only while at least one feature {@link #acquire() acquired} it and the player is in
 * {@code telos:dungeon/<n>}. Scanning runs on the client thread under a time budget; analysis and path
 * finding run on one background thread ({@link #worker()}), so nothing heavy happens per frame.
 */
public final class DungeonTracker {

    private static final DungeonTracker INSTANCE = new DungeonTracker();

    /** Client-thread time per tick for chunk scanning. */
    private static final long SCAN_BUDGET_NANOS = 1_500_000L;
    private static final int RESCAN_INTERVAL_TICKS = 40;
    private static final int TREASURE_INTERVAL_TICKS = 5;
    private static final long ANALYSIS_INTERVAL_MS = 1500;

    private final DungeonMap map = new DungeonMap();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Telemetric-Dungeon");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private final Set<String> seenDungeons = new LinkedHashSet<>();

    private int users;
    private boolean inDungeon;
    private Object levelToken;
    private String dungeonName;
    private long ticks;
    private List<Point> treasures = List.of();
    private volatile DungeonAnalysis analysis = DungeonAnalysis.EMPTY;
    /** Union of reachable cells; only touched by the analysis job (a new set per dungeon). */
    private LongOpenHashSet reached = new LongOpenHashSet();
    private Future<?> analysisJob;
    private long lastAnalysisMs;
    private int minDropDepth = 10;
    private int analysisDropDepth = -1;
    /** Where the boss is: boss-bar mob feet, else the boss nameplate (last seen this run); null until seen. */
    private Point bossAnchor;
    private Point analysisBossAnchor;
    private volatile int generation;
    /** Last piece size (× 1000 + measured size) logged for this dungeon (log once per change, not per analysis). */
    private volatile int loggedPieceSize;
    /** Size table entry + spawn position for the room grid, set on entering the dungeon. */
    private volatile RoomLayout.Hint hint = RoomLayout.Hint.NONE;
    /** This dungeon's rules ({@link DungeonRulebook}); DEFAULT outside dungeons. */
    private DungeonRules rules = DungeonRules.DEFAULT;
    /** Where the player entered the dungeon (the spawn room); null outside a dungeon. */
    private volatile Point spawn;
    /** Goal ("clear") mob name of this dungeon, normalised ({@link DungeonRules#goalMob()}); null = none. */
    private String goalMob;
    /** Goal mob nameplates currently loaded: entity id → position. */
    private final Map<Integer, Point> goalAlive = new HashMap<>();
    /** Goal mobs whose plate vanished out of the player's range: still alive, just not tracked right now. */
    private final List<Point> goalAway = new ArrayList<>();
    /** Positions of the goal mobs counted as killed this run (kept for the whole run). */
    private final List<Point> goalKills = new ArrayList<>();
    private volatile List<Point> goals = List.of();
    private volatile int goalKilled, goalTotal;

    /** A plate showing up this close (blocks, squared) to a known goal mob is that same mob, not a new one. */
    private static final double GOAL_SAME_SQ = 8 * 8;
    /** A plate vanishing this close (horizontal blocks, squared) to the player = killed; farther = only untracked. */
    private static final double GOAL_KILL_RANGE_SQ = 24 * 24;
    /** An away mob whose spot the player is this close to (squared) with no plate there = killed while away. */
    private static final double GOAL_CONFIRM_RANGE_SQ = 12 * 12;

    /** A run teaches the size table only if its final layout had at least this many rooms. */
    private static final int MIN_ROOMS_TO_LEARN = 3;

    private DungeonTracker() {}

    public static DungeonTracker get() {
        return INSTANCE;
    }

    public void acquire() {
        users++;
    }

    public void release() {
        users = Math.max(0, users - 1);
        if (users == 0) reset();
    }

    /** Called every client tick (on Telos) from the entrypoint. Cheap when idle. */
    public void tick() {
        if (users == 0) return;
        if (!DungeonAdapter.isInDungeon()) {
            if (inDungeon || map.size() > 0) reset();
            return;
        }
        Object token = DungeonAdapter.levelToken();
        if (token != levelToken) {
            reset();
            levelToken = token;
        }
        if (!inDungeon) {
            inDungeon = true;
            DungeonAdapter.setActive(true);
            dungeonName = DungeonAdapter.dungeonName();
            if (dungeonName != null) seenDungeons.add(dungeonName);
            // the player arrives in the spawn room, where the Telos builder starts the piece grid
            Point spawn = DungeonAdapter.playerPos();
            this.spawn = spawn;
            int tableSize = dungeonName == null ? 0 : PieceSizeTable.get().size(dungeonName);
            rules = DungeonRulebook.rulesFor(dungeonName);
            goalMob = rules.goalMob() == null ? null : DungeonAdapter.normalizeName(rules.goalMob());
            hint = spawn == null ? new RoomLayout.Hint(tableSize, false, 0, 0, Integer.MIN_VALUE, rules)
                    : new RoomLayout.Hint(tableSize, true, (int) Math.floor(spawn.x()), (int) Math.floor(spawn.z()),
                    (int) Math.floor(spawn.y() + 0.01), rules);
            Telemetric.LOGGER.info("Dungeon entered: dimension={} name={} table size={} spawn={} rules=[{}]",
                    DungeonAdapter.dimensionId(), dungeonName, tableSize, spawn, rules);
        }
        ticks++;
        if (!rules.mapped()) { // no Wayfinder / map here: skip the block scan and room analysis
            if (ticks % TREASURE_INTERVAL_TICKS == 0) {
                treasures = dedupe(DungeonAdapter.yellowGlowingEntities());
                updateGoals();
            }
            return;
        }
        if (ticks % RESCAN_INTERVAL_TICKS == 0) DungeonAdapter.enqueueAround(1);
        DungeonAdapter.scanPending(map, SCAN_BUDGET_NANOS);
        // jump pads (yellow dust fountains): routes and room links may ride them
        for (long pad : DungeonAdapter.drainJumpPads()) {
            if (map.addPad(Cell.x(pad), Cell.y(pad), Cell.z(pad))) {
                Telemetric.LOGGER.info("Dungeon jump pad at {} {} {}", Cell.x(pad), Cell.y(pad), Cell.z(pad));
            }
        }
        if (ticks % TREASURE_INTERVAL_TICKS == 0) {
            treasures = dedupe(DungeonAdapter.yellowGlowingEntities());
            updateGoals();
            // the boss mob is often invisible / not sent until the fight: its nameplate marks the arena
            Point boss = BossBarAdapter.bossMobPos();
            String source = "boss-bar mob";
            if (boss == null) {
                boss = BossBarAdapter.bossMarkerPos();
                source = "boss nameplate";
            }
            // keep the last anchor once the boss goes out of range; ignore it moving by a block or two
            if (boss != null && (bossAnchor == null || farApart(boss, bossAnchor))) {
                bossAnchor = boss;
                Telemetric.LOGGER.info("Dungeon boss anchor ({}): {}", source, boss);
            }
        }
        scheduleAnalysis();
    }

    private void scheduleAnalysis() {
        if (analysisJob != null && !analysisJob.isDone()) return;
        long version = map.version();
        boolean depthChanged = analysisDropDepth != minDropDepth || analysisBossAnchor != bossAnchor;
        Point player = DungeonAdapter.playerPos();
        long start = player == null ? Long.MIN_VALUE : PathFinder.startCell(map, player);
        // standing somewhere the last flood did not reach (dropped into an arena, a door opened...)
        boolean newArea = start != Long.MIN_VALUE && !analysis.reachable().contains(start);
        if (version == analysis.mapVersion() && !depthChanged && !newArea) return;
        long now = System.currentTimeMillis();
        if (!depthChanged && now - lastAnalysisMs < ANALYSIS_INTERVAL_MS) return;
        lastAnalysisMs = now;
        int depth = minDropDepth;
        int gen = generation;
        LongOpenHashSet union = reached;
        RoomLayout.Hint roomHint = hint;
        Point bossAt = bossAnchor;
        analysisDropDepth = depth;
        analysisBossAnchor = bossAt;
        analysisJob = worker.submit(() -> {
            try {
                DungeonAnalysis result = DungeonAnalysis.compute(map, depth, start, union, roomHint, bossAt);
                if (gen != generation) return; // dropped if the dungeon changed meanwhile
                analysis = result;
                int size = result.rooms().pieceSize(), measured = result.rooms().measuredSize();
                if (result.rooms().freeform()) {
                    int count = result.rooms().rooms().size();
                    if (-count - 1 != loggedPieceSize) { // negative keys: free-form room count
                        loggedPieceSize = -count - 1;
                        Telemetric.LOGGER.info("Dungeon rooms: free-form rooms={} links={}", count,
                                result.rooms().links().size());
                    }
                }
                int logKey = size * 1000 + measured;
                if (size > 0 && logKey != loggedPieceSize) {
                    loggedPieceSize = logKey;
                    Telemetric.LOGGER.info("Dungeon rooms: piece size={} (measured {}) rooms={}", size, measured,
                            result.rooms().rooms().size());
                }
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("Dungeon analysis failed", e);
            }
        });
    }

    /**
     * Follows the goal mobs by their nameplate entities. A plate that disappears close to the player (chunk still
     * loaded) was killed; one that disappears far away is only out of tracking range (kept as "away"). Every counted
     * mob keeps its position for the whole run: a plate showing up near a known away or killed mob is that same mob
     * again (an away one coming back in range, or a re-sent/re-tracked plate of a "killed" one — the kill is undone),
     * never a new one. An away mob whose spot the player reaches with no plate there was killed while away.
     */
    private void updateGoals() {
        if (goalMob == null) return;
        Point player = DungeonAdapter.playerPos();
        Set<Integer> present = new HashSet<>();
        List<Point> seen = new ArrayList<>();
        for (DungeonAdapter.NamePlate plate : DungeonAdapter.namePlates(goalMob)) {
            present.add(plate.id());
            seen.add(plate.pos());
            if (goalAlive.put(plate.id(), plate.pos()) != null) continue;
            // a newly seen plate: an away mob coming back in range, or the plate of a mob already counted as killed
            if (!removeNear(goalAway, plate.pos()) && removeNear(goalKills, plate.pos())) {
                Telemetric.LOGGER.info("Dungeon goal mob at {} is still alive (kill undone)", plate.pos());
            }
        }
        for (Iterator<Map.Entry<Integer, Point>> it = goalAlive.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Point> e = it.next();
            if (present.contains(e.getKey())) continue;
            it.remove();
            Point p = e.getValue();
            if (DungeonAdapter.isLoaded(p.x(), p.z()) && horizontalSq(player, p) <= GOAL_KILL_RANGE_SQ) {
                goalKills.add(p);
                Telemetric.LOGGER.info("Dungeon goal mob killed at {}", p);
            } else {
                goalAway.add(p);
            }
        }
        // an away mob whose spot the player now stands near, with no plate around it, died while untracked
        for (Iterator<Point> it = goalAway.iterator(); it.hasNext(); ) {
            Point p = it.next();
            if (horizontalSq(player, p) > GOAL_CONFIRM_RANGE_SQ || !DungeonAdapter.isLoaded(p.x(), p.z())) continue;
            boolean plateNear = false;
            for (Point s : seen) {
                if (s.distanceSq(p.x(), p.y(), p.z()) <= GOAL_SAME_SQ) { plateNear = true; break; }
            }
            if (plateNear) continue;
            it.remove();
            goalKills.add(p);
            Telemetric.LOGGER.info("Dungeon goal mob killed while away at {}", p);
        }
        List<Point> live = new ArrayList<>(goalAlive.values());
        live.addAll(goalAway);
        goals = List.copyOf(live);
        goalKilled = goalKills.size();
        goalTotal = live.size() + goalKills.size();
    }

    /** Removes the entry of {@code list} nearest to {@code p} within {@link #GOAL_SAME_SQ}; true if one was removed. */
    private static boolean removeNear(List<Point> list, Point p) {
        int best = -1;
        double bestSq = GOAL_SAME_SQ;
        for (int i = 0; i < list.size(); i++) {
            double d = list.get(i).distanceSq(p.x(), p.y(), p.z());
            if (d <= bestSq) { bestSq = d; best = i; }
        }
        if (best < 0) return false;
        list.remove(best);
        return true;
    }

    private static double horizontalSq(Point player, Point p) {
        if (player == null) return Double.MAX_VALUE;
        double dx = player.x() - p.x(), dz = player.z() - p.z();
        return dx * dx + dz * dz;
    }

    private void reset() {
        // teach the size table what this run measured (only a real layout, not a half-scanned start)
        RoomLayout rooms = analysis.rooms();
        if (dungeonName != null && rooms.rooms().size() >= MIN_ROOMS_TO_LEARN) {
            PieceSizeTable.get().recordRun(dungeonName, rooms.measuredSize());
        }
        generation++;
        loggedPieceSize = 0;
        hint = RoomLayout.Hint.NONE;
        rules = DungeonRules.DEFAULT;
        spawn = null;
        inDungeon = false;
        levelToken = null;
        dungeonName = null;
        treasures = List.of();
        goalMob = null;
        goalAlive.clear();
        goalAway.clear();
        goalKills.clear();
        goals = List.of();
        goalKilled = 0;
        goalTotal = 0;
        bossAnchor = null;
        analysisBossAnchor = null;
        if (analysisJob != null) analysisJob.cancel(false);
        analysisJob = null;
        map.clear();
        reached = new LongOpenHashSet();
        analysis = DungeonAnalysis.EMPTY;
        DungeonAdapter.setActive(false);
    }

    /** Merges glowing entities closer than 2 blocks (multi-part treasure models). */
    /**
     * A different boss place: more than 2 blocks apart in Y, or more than 24 apart sideways. A boss walking
     * around its arena (or the mob vs. its nameplate, ~1.7 higher) is the same anchor — every anchor change
     * forces an immediate re-analysis.
     */
    private static boolean farApart(Point a, Point b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return Math.abs(a.y() - b.y()) > 2 || dx * dx + dz * dz > 24 * 24;
    }

    private static List<Point> dedupe(List<Point> points) {
        List<Point> out = new ArrayList<>();
        outer:
        for (Point p : points) {
            for (Point q : out) if (q.distanceSq(p.x(), p.y(), p.z()) < 4) continue outer;
            out.add(p);
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ access

    public boolean isInDungeon() {
        return inDungeon;
    }

    public String dungeonName() {
        return dungeonName;
    }

    /** False in dungeons whose rules turn the Wayfinder and Dungeon Map off ({@link DungeonRules#mapped()}). */
    public boolean isMapped() {
        return rules.mapped();
    }

    /** Dungeon names seen this session (for blacklist options). */
    public Set<String> seenDungeons() {
        return seenDungeons;
    }

    public DungeonMap map() {
        return map;
    }

    public DungeonAnalysis analysis() {
        return analysis;
    }

    /** Where the player entered this dungeon (inside the spawn room), or null. */
    public Point spawn() {
        return spawn;
    }

    public List<Point> treasures() {
        return treasures;
    }

    /** True if this dungeon has goal ("clear") mobs ({@link DungeonRules#goalMob()}). */
    public boolean hasGoal() {
        return goalMob != null;
    }

    /** Positions of the goal mobs still alive (loaded or out of range). */
    public List<Point> goals() {
        return goals;
    }

    /** Goal mobs killed this run. */
    public int goalKilled() {
        return goalKilled;
    }

    /** Goal mobs seen this run (alive + killed). */
    public int goalTotal() {
        return goalTotal;
    }

    public ExecutorService worker() {
        return worker;
    }

    /** Changes whenever the tracked dungeon is reset; lets async results detect they are stale. */
    public int generation() {
        return generation;
    }

    public void setMinDropDepth(int depth) {
        this.minDropDepth = Math.max(3, depth);
    }

    /** Lower-case letters/digits only, so "Kobold's Den" matches "Kobolds Den". */
    public static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
