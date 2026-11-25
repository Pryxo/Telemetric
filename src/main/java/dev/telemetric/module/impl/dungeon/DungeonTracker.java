package dev.telemetric.module.impl.dungeon;

import dev.telemetric.Telemetric;
import dev.telemetric.chat.ChatBus;
import dev.telemetric.dungeon.rules.ChatCounter;
import dev.telemetric.dungeon.rules.ChatPuzzles;
import dev.telemetric.dungeon.PuzzleBoard;
import dev.telemetric.dungeon.Cell;
import dev.telemetric.dungeon.DungeonAnalysis;
import dev.telemetric.dungeon.DungeonMap;
import dev.telemetric.dungeon.PathFinder;
import dev.telemetric.dungeon.Point;
import dev.telemetric.dungeon.DungeonHint;
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
 * Shared dungeon state for every dungeon feature (Wayfinder, Dungeon Info): the walk map, glowing treasures, and the
 * analysis derived from them (reachable floor, holes).
 * <p>
 * It only runs while at least one feature has {@link #acquire() acquired} it and the player is in
 * {@code telos:dungeon/<n>}. Scanning runs on the client thread under a time budget, while analysis and path finding
 * run on one background thread ({@link #worker()}), so nothing heavy happens per frame.
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
    /** The HUD-box location seen before entering, and the ticks spent waiting for it to change. */
    private String lastHudLocation;
    private int nameWait;
    private static final int NAME_WAIT_TICKS = 40;
    private long ticks;
    private List<Point> treasures = List.of();
    private volatile DungeonAnalysis analysis = DungeonAnalysis.EMPTY;
    /** Union of reachable cells. Only the analysis job touches it (a new set per dungeon). */
    private LongOpenHashSet reached = new LongOpenHashSet();
    private Future<?> analysisJob;
    private long lastAnalysisMs;
    private int minDropDepth = 10;
    private int analysisDropDepth = -1;
    /** Where the boss is: the boss-bar mob's feet, or else the boss nameplate (last seen this run). Null until seen. */
    private Point bossAnchor;
    private Point analysisBossAnchor;
    private volatile int generation;
    /** Spawn position and rules, set when entering the dungeon. */
    private volatile DungeonHint hint = DungeonHint.NONE;
    /** This dungeon's rules ({@link DungeonRulebook}). DEFAULT outside dungeons. */
    private DungeonRules rules = DungeonRules.DEFAULT;
    /** Where the dungeon starts (its return portal, or else where the player entered). Null outside a dungeon. */
    private volatile Point spawn;
    /** Whether {@link #spawn} is the return portal ({@link DungeonAdapter#returnPortalPos}). */
    private boolean portalFound;
    /** The hint changed (the start moved onto the portal), so analyse again even if the map didn't change. */
    private volatile boolean hintChanged;
    /** This dungeon's goal ("clear") mob name, normalised ({@link DungeonRules#goalMob()}). Null for none. */
    private String goalMob;
    /** Goal mob nameplates that are loaded right now: entity id to position. */
    private final Map<Integer, Point> goalAlive = new HashMap<>();
    /** Goal mobs whose plate vanished out of the player's range. They're still alive, just not tracked right now. */
    private final List<Point> goalAway = new ArrayList<>();
    /** Positions of the goal mobs counted as killed this run (kept for the whole run). */
    private final List<Point> goalKills = new ArrayList<>();
    private volatile List<Point> goals = List.of();
    private volatile int goalKilled, goalTotal;
    /** This dungeon's chat objective ({@link DungeonRules#chatCounter()}), plus its subscription and progress. */
    private ChatCounter counter;
    private ChatBus.Subscription counterSub, counterTotalSub;
    /** Count nameplates are read every few ticks within this many blocks of the player. */
    private static final double NAMEPLATE_RANGE = 16;
    private static final int NAMEPLATE_INTERVAL_TICKS = 4;
    private volatile int counterValue, counterTotal;
    /** This dungeon's puzzle rooms ({@link DungeonRules#chatPuzzles()}), plus their subscriptions and state. */
    private ChatPuzzles puzzleRule;
    private final List<ChatBus.Subscription> puzzleSubs = new ArrayList<>();
    private volatile PuzzleBoard puzzles;

    /** A plate that shows up this close (blocks, squared) to a known goal mob is that same mob, not a new one. */
    private static final double GOAL_SAME_SQ = 8 * 8;
    /** A plate that vanishes this close to the player (horizontal blocks, squared) was killed; farther away it's only untracked. */
    private static final double GOAL_KILL_RANGE_SQ = 24 * 24;
    /** If the player gets this close (squared) to an away mob's spot and there's no plate there, it was killed while away. */
    private static final double GOAL_CONFIRM_RANGE_SQ = 12 * 12;

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

    /** The entrypoint calls this every client tick (on Telos). Cheap when idle. */
    public void tick() {
        if (users == 0) return;
        if (!DungeonAdapter.isInDungeon()) {
            if (inDungeon || map.size() > 0) reset();
            lastHudLocation = DungeonAdapter.hudLocation();
            nameWait = 0;
            return;
        }
        Object token = DungeonAdapter.levelToken();
        if (token != levelToken) {
            reset();
            levelToken = token;
            nameWait = 0;
        }
        if (!inDungeon) {
            // the HUD box can still show the previous place for a moment, so wait for it to change (up to about 2 s)
            String hud = DungeonAdapter.hudLocation();
            if ((hud == null || hud.equals(lastHudLocation)) && ++nameWait < NAME_WAIT_TICKS) return;
            inDungeon = true;
            DungeonAdapter.setActive(true);
            dungeonName = DungeonAdapter.dungeonName();
            lastHudLocation = dungeonName; // hopping straight to the next dungeon waits for a new name again
            if (dungeonName != null) seenDungeons.add(dungeonName);
            rules = DungeonRulebook.rulesFor(dungeonName);
            goalMob = rules.goalMob() == null ? null : DungeonAdapter.normalizeName(rules.goalMob());
            startCounter(rules.chatCounter());
            startPuzzles(rules.chatPuzzles());
            // The dungeon starts at its return portal, which is where the Telos builder starts the piece grid. The player
            // arrives on it but may have walked off by the time the HUD names the dungeon, so the portal is the exact spot.
            Point portal = DungeonAdapter.returnPortalPos();
            portalFound = portal != null;
            setSpawn(portal != null ? portal : DungeonAdapter.playerPos());
            Telemetric.debug("Dungeon entered: dimension={} name={} spawn={} (portal {}) rules=[{}]",
                    DungeonAdapter.dimensionId(), dungeonName, spawn, portalFound, rules);
        }
        // the portal wasn't loaded yet on entry, so keep looking and then move the start room onto it
        if (!portalFound && ticks % TREASURE_INTERVAL_TICKS == 0) {
            Point portal = DungeonAdapter.returnPortalPos();
            if (portal != null) {
                portalFound = true;
                setSpawn(portal);
                Telemetric.debug("Dungeon return portal found later: {}", portal);
            }
        }
        ticks++;
        if (ticks % NAMEPLATE_INTERVAL_TICKS == 0) readCounterNameplates();
        if (!rules.mapped()) { // no Wayfinder or map here, so skip the block scan and room analysis
            if (ticks % TREASURE_INTERVAL_TICKS == 0) {
                treasures = dedupe(DungeonAdapter.yellowGlowingEntities());
                updateGoals();
            }
            return;
        }
        if (ticks % RESCAN_INTERVAL_TICKS == 0) DungeonAdapter.enqueueAround(1);
        DungeonAdapter.scanPending(map, SCAN_BUDGET_NANOS);
        // jump pads (yellow dust fountains), which routes and room links may ride
        for (long pad : DungeonAdapter.drainJumpPads()) {
            if (map.addPad(Cell.x(pad), Cell.y(pad), Cell.z(pad))) {
                Telemetric.debug("Dungeon jump pad at {} {} {}", Cell.x(pad), Cell.y(pad), Cell.z(pad));
            }
        }
        if (ticks % TREASURE_INTERVAL_TICKS == 0) {
            treasures = dedupe(DungeonAdapter.yellowGlowingEntities());
            updateGoals();
            // The boss mob is often invisible or not sent until the fight, so its nameplate marks the arena.
            // When the dungeon rule says there's no boss level, a boss bar mob isn't an arena anchor.
            Point boss = rules.bossLevel() ? BossBarAdapter.bossMobPos() : null;
            String source = "boss-bar mob";
            if (boss == null && rules.bossLevel()) {
                boss = BossBarAdapter.bossMarkerPos();
                source = "boss nameplate";
            }
            // keep the last anchor once the boss goes out of range, and ignore it moving by a block or two
            if (boss != null && (bossAnchor == null || farApart(boss, bossAnchor))) {
                bossAnchor = boss;
                Telemetric.debug("Dungeon boss anchor ({}): {}", source, boss);
            }
        }
        scheduleAnalysis();
    }

    /** Sets the start point and the hint built on it (null if unknown). */
    private void setSpawn(Point spawn) {
        this.spawn = spawn;
        hint = spawn == null ? new DungeonHint(false, 0, 0, Integer.MIN_VALUE, rules)
                : new DungeonHint(true, (int) Math.floor(spawn.x()), (int) Math.floor(spawn.z()),
                (int) Math.floor(spawn.y() + 0.01), rules);
        hintChanged = true;
    }

    private void scheduleAnalysis() {
        if (analysisJob != null && !analysisJob.isDone()) return;
        long version = map.version();
        boolean depthChanged = analysisDropDepth != minDropDepth || analysisBossAnchor != bossAnchor || hintChanged;
        Point player = DungeonAdapter.playerPos();
        long start = player == null ? Long.MIN_VALUE : PathFinder.startCell(map, player);
        // standing somewhere the last flood didn't reach (dropped into an arena, a door opened...)
        boolean newArea = start != Long.MIN_VALUE && !analysis.reachable().contains(start);
        if (version == analysis.mapVersion() && !depthChanged && !newArea) return;
        long now = System.currentTimeMillis();
        if (!depthChanged && now - lastAnalysisMs < ANALYSIS_INTERVAL_MS) return;
        lastAnalysisMs = now;
        int depth = minDropDepth;
        int gen = generation;
        LongOpenHashSet union = reached;
        DungeonHint roomHint = hint;
        Point bossAt = bossAnchor;
        analysisDropDepth = depth;
        analysisBossAnchor = bossAt;
        hintChanged = false;
        analysisJob = worker.submit(() -> {
            try {
                DungeonAnalysis result = DungeonAnalysis.compute(map, depth, start, union, roomHint, bossAt);
                if (gen != generation) return; // dropped if the dungeon changed in the meantime
                analysis = result;
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("Dungeon analysis failed", e);
            }
        });
    }

    /**
     * Follows the goal mobs by their nameplate entities. A plate that disappears close to the player (chunk still
     * loaded) was killed. One that disappears far away is only out of tracking range and is kept as "away". Every
     * counted mob keeps its position for the whole run, so a plate that shows up near a known away or killed mob is
     * that same mob again, never a new one. Either an away mob came back in range, or a "killed" one got its plate
     * re-sent or re-tracked, which undoes the kill. If the player reaches an away mob's spot and there's no plate
     * there, it was killed while away.
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
            // a newly seen plate: either an away mob coming back in range, or the plate of a mob already counted as killed
            if (!removeNear(goalAway, plate.pos()) && removeNear(goalKills, plate.pos())) {
                Telemetric.debug("Dungeon goal mob at {} is still alive (kill undone)", plate.pos());
            }
        }
        for (Iterator<Map.Entry<Integer, Point>> it = goalAlive.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Point> e = it.next();
            if (present.contains(e.getKey())) continue;
            it.remove();
            Point p = e.getValue();
            if (DungeonAdapter.isLoaded(p.x(), p.z()) && horizontalSq(player, p) <= GOAL_KILL_RANGE_SQ) {
                goalKills.add(p);
                Telemetric.debug("Dungeon goal mob killed at {}", p);
            } else {
                goalAway.add(p);
            }
        }
        // an away mob whose spot the player is now standing near, with no plate around it, died while untracked
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
            Telemetric.debug("Dungeon goal mob killed while away at {}", p);
        }
        List<Point> live = new ArrayList<>(goalAlive.values());
        live.addAll(goalAway);
        goals = List.copyOf(live);
        goalKilled = goalKills.size();
        goalTotal = live.size() + goalKills.size();
    }

    /** Removes the entry of {@code list} nearest to {@code p} within {@link #GOAL_SAME_SQ}. Returns true if one was removed. */
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
        generation++;
        hint = DungeonHint.NONE;
        rules = DungeonRules.DEFAULT;
        spawn = null;
        portalFound = false;
        hintChanged = false;
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
        startCounter(null);
        startPuzzles(null);
        bossAnchor = null;
        analysisBossAnchor = null;
        if (analysisJob != null) analysisJob.cancel(false);
        analysisJob = null;
        map.clear();
        reached = new LongOpenHashSet();
        analysis = DungeonAnalysis.EMPTY;
        DungeonAdapter.setActive(false);
    }

    /**
     * True for a different boss place: more than 2 blocks apart in Y, or more than 24 apart sideways. A boss walking
     * around its arena (or the mob vs. its nameplate, about 1.7 higher) is the same anchor. Every anchor change forces
     * an immediate re-analysis.
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

    /** False in dungeons whose rules turn off the Wayfinder ({@link DungeonRules#mapped()}). */
    public boolean isMapped() {
        return rules.mapped();
    }

    /** Dungeon names seen this session, for the blacklist options. */
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

    /** Goal mobs seen this run (alive plus killed). */
    public int goalTotal() {
        return goalTotal;
    }

    /** Listens for this dungeon's objective message (null for none), dropping the previous subscription and progress. */
    private void startCounter(ChatCounter next) {
        if (counterSub != null) counterSub.cancel();
        if (counterTotalSub != null) counterTotalSub.cancel();
        counterSub = null;
        counterTotalSub = null;
        counter = next;
        counterValue = 0;
        counterTotal = next == null ? 0 : next.total();
        if (next == null) return;
        counterSub = ChatBus.on(next.pattern(), m -> {
            int n = parseCount(m.group("n"));
            int total = parseCount(m.group("total"));
            if (total <= 0 || n < 0 || n > total) return;
            counterTotal = total;
            counterValue = Math.max(counterValue, n);
        });
        if (next.totalPattern() != null) {
            counterTotalSub = ChatBus.on(next.totalPattern(), m -> {
                int total = parseCount(m.group("total"));
                if (total > 0) counterTotal = total;
            });
        }
    }

    /** Raises the objective count from the server's count nameplates (e.g. "(75/110)" after a renown pick-up). */
    private void readCounterNameplates() {
        ChatCounter c = counter;
        if (c == null || c.nameplate() == null) return;
        for (String name : DungeonAdapter.nameplates(NAMEPLATE_RANGE)) {
            java.util.regex.Matcher m = c.nameplate().matcher(name);
            if (!m.matches()) continue;
            int n = parseCount(m.group("n"));
            if (n > counterValue) counterValue = n;
        }
    }

    /** Parses a count group. Returns -1 if it's too long for an int. */
    private static int parseCount(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Listens for this dungeon's puzzle messages (null for none), dropping the previous subscriptions and state. */
    private void startPuzzles(ChatPuzzles next) {
        for (ChatBus.Subscription s : puzzleSubs) s.cancel();
        puzzleSubs.clear();
        puzzleRule = next;
        puzzles = next == null ? null : new PuzzleBoard(next.slots());
        if (next == null) return;
        PuzzleBoard board = puzzles;
        puzzleSubs.add(ChatBus.on(next.attempt(), m -> board.attempt(m.group("player"), m.group("room"))));
        puzzleSubs.add(ChatBus.on(next.complete(), m -> board.complete(m.group("room"))));
        puzzleSubs.add(ChatBus.on(next.reset(), m -> board.reset(m.group("room"))));
    }

    /** This dungeon's puzzle rule ({@link DungeonRules#chatPuzzles()}), or null. */
    public ChatPuzzles puzzleRule() {
        return puzzleRule;
    }

    /** The puzzle room state this run, or null if the dungeon has no puzzle rule. */
    public PuzzleBoard puzzles() {
        return puzzles;
    }

    /** This dungeon's chat objective ({@link DungeonRules#chatCounter()}), or null. */
    public ChatCounter counter() {
        return counter;
    }

    /** Objective progress so far this run (the count from the last matching message). */
    public int counterValue() {
        return counterValue;
    }

    /** The objective total (from the message, or else the rule's expected total). */
    public int counterTotal() {
        return counterTotal;
    }

    public ExecutorService worker() {
        return worker;
    }

    /** Changes every time the tracked dungeon is reset, so async results can tell they're stale. */
    public int generation() {
        return generation;
    }

    public void setMinDropDepth(int depth) {
        this.minDropDepth = Math.max(3, depth);
    }

    /** Only lower-case letters and digits, with a leading "The " dropped: "Kobold's Den" = "Kobolds Den", "The Aviary" = "Aviary". */
    public static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceFirst("^\\s*the\\s+", "")
                .replaceAll("[^a-z0-9]", "");
    }
}
