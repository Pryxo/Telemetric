package dev.telemetric.module.impl.dungeon;

import dev.telemetric.Telemetric;
import dev.telemetric.dungeon.Cell;
import dev.telemetric.dungeon.DungeonMap;
import dev.telemetric.dungeon.Hole;
import dev.telemetric.dungeon.PathFinder;
import dev.telemetric.dungeon.PathGeometry;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.notification.Toasts;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Draws the shortest walkable route on the floor from you to the nearest treasure room (yellow glowing treasure) or
 * to the boss drop (the deep hole down to the arena). Only inside {@code telos:dungeon/<n>}.
 * <p>
 * Routes come from {@link PathFinder} on the dungeon worker thread. This module only decides when to re-plan (targets
 * changed, the map grew, you left the route, every few seconds) and draws the result.
 */
public final class WayfinderModule extends Module {

    public enum Target implements EnumSetting.Nameable {
        AUTO("Auto"), TREASURE("Treasure"), BOSS_DROP("Boss Drop");

        private final String label;

        Target(String label) {
            this.label = label;
        }

        @Override
        public String displayName() {
            return label;
        }
    }

    public enum Style implements EnumSetting.Nameable {
        CLEAN("Clean"), SHARP("Sharp");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        @Override
        public String displayName() {
            return label;
        }
    }


    private static final int MAX_NODES = 400_000;
    private static final long REPLAN_MIN_MS = 400;
    private static final long REPLAN_MAP_MS = 1200;
    private static final long REPLAN_PERIODIC_MS = 3000;
    private static final double OFF_ROUTE_SQ = 4.0 * 4.0;
    private static final int PAD_DEBUG_COLOR = 0xFF55FF55;
    private static final int PAD_BAD_DEBUG_COLOR = 0xFFFF5555;
    private static final int PAD_DEBUG_RANGE = 128;
    /** Feet within this many blocks of the boss drop's rim count as "at the drop's level". */
    private static final int ROOM_LEVEL_MARGIN = 4;
    /** How far around the drop (blocks) counts as "at the drop", if no wall is in between. */
    private static final int DROP_AREA = 10;
    /** How far a hole has to drop to count as the boss drop. */
    private static final int DROP_DEPTH = 10;

    private final EnumSetting<Target> target = add(new EnumSetting<>("Target",
            "Auto: nearest treasure room, then the boss drop once no treasure is left", Target.AUTO));
    private final EnumSetting<Style> style = add(new EnumSetting<>("Style",
            "Clean: rounded corners · Sharp: straight legs with hard corners", Style.CLEAN));
    private final BooleanSetting showLine = add(new BooleanSetting("Show Line",
            "Draw the guiding route line (the target marker always shows)", true));
    private final NumberSetting lineWidth = add(new NumberSetting("Line Width",
            "Thickness of the route line", 3, 1, 8, 0.5).withSuffix("px"));
    private final BooleanSetting throughWalls = add(new BooleanSetting("Through Walls",
            "Show the route even behind walls and floors", true));
    private final ColorSetting treasureColor = add(new ColorSetting("Treasure Color",
            "Route colour towards a treasure room", 0xFFFFC83D));
    private final ColorSetting bossColor = add(new ColorSetting("Boss Color",
            "Route colour towards the boss drop", 0xFFFF5A4E));
    private final MultiChoiceSetting blacklist = add(new MultiChoiceSetting("Blacklist",
            "Dungeons where the Wayfinder stays off", this::blacklistOptions));
    private final ActionSetting blacklistCurrent = add(new ActionSetting("Blacklist Current",
            "Add or remove the dungeon you are in", this::toggleCurrentDungeon));
    private final GroupSetting debug = add(new GroupSetting("Debug", "Debug view of the jump pad detection"));
    private final BooleanSetting showPads = add(debug.child(new BooleanSetting("Show Jump Pads",
            "Debug: mark every detected jump pad (green box = used for routing, red = no floor found under it)",
            false)));

    /**
     * A planned route. When complete, the points include the final leg onto the target. When not complete, the target
     * is in a part you can't reach yet, even through the air (sealed off or unexplored), so the points end at the
     * closest reachable spot. Nothing is ever drawn through blocks.
     */
    private record Route(List<Point> sharp, List<Point> clean, Point target, boolean boss, Hole hole, boolean complete) {}

    private volatile Route route;
    private Future<?> job;
    private boolean acquired;
    private long lastPlanMs;
    private long plannedMapVersion = -1;
    private String plannedKey = "";
    private long lastStart = Long.MIN_VALUE;
    /** The dungeon run ({@link DungeonTracker#generation()}) that the boss latch below belongs to. */
    private int bossGeneration = Integer.MIN_VALUE;
    /** You've been in the boss drop segment this run, so the boss route stays hidden. */
    private boolean bossReached;
    /** The boss drop that {@link #bossReached} was latched for. */
    private Hole bossReachedHole;
    /** The last boss drop seen this run (shown while the analysis has none). */
    private Hole lastHole;

    public WayfinderModule() {
        super("Wayfinder", "Shows the shortest way to treasure rooms and the boss drop", Category.COMBAT);
        WorldRenderAdapter.addDrawer(this::draw);
    }

    @Override
    protected void onEnable() {
        if (!acquired) {
            acquired = true;
            DungeonTracker.get().acquire();
        }
    }

    @Override
    protected void onDisable() {
        if (acquired) {
            acquired = false;
            DungeonTracker.get().release();
        }
        clearRoute();
    }


    @Override
    public void onTick() {
        DungeonTracker tracker = DungeonTracker.get();
        tracker.setMinDropDepth(DROP_DEPTH);
        if (!tracker.isInDungeon() || !tracker.isMapped() || isBlacklisted(tracker.dungeonName())) {
            clearRoute();
            return;
        }
        Point player = DungeonAdapter.playerPos();
        if (player == null) return;
        DungeonMap map = tracker.map();
        long start = PathFinder.startCell(map, player);
        if (start == Long.MIN_VALUE) start = lastStart; // mid-air, so keep planning from the last floor cell
        if (start == Long.MIN_VALUE) return;
        lastStart = start;

        List<Point> treasures = tracker.treasures();
        int generation0 = tracker.generation();
        if (generation0 != bossGeneration) { // new dungeon run: forget the last drop and the "reached" latch
            bossGeneration = generation0;
            bossReached = false;
            bossReachedHole = null;
            lastHole = null;
        }
        Hole hole = tracker.analysis().bossDrop();
        if (hole != null) lastHole = hole;
        else hole = lastHole; // a re-analysis lost the drop for a moment, so keep showing the last one
        // The route stays until you've really been in the boss drop segment (fell into its shaft, or stand on the boss
        // level). Only then is it hidden for the rest of the run. Being low somewhere else (a sunken room, a ledge)
        // doesn't hide it anymore.
        // The latch belongs to the drop that set it. A re-analysis that moves the drop (an early wrong one detected
        // right where you loaded in) clears it again.
        if (hole != null && bossReached && !sameDrop(hole, bossReachedHole)) bossReached = false;
        if (hole != null && !bossReached && inBossSegment(tracker, hole, player)) {
            bossReached = true;
            bossReachedHole = hole;
        }
        if (bossReached) hole = null;
        Target mode = target.get();
        String key = mode + "|" + targetsKey(treasures) + "|" + (hole == null ? "-" : hole.minX() + "," + hole.minZ() + "," + hole.y());
        if ((mode == Target.TREASURE || hole == null) && (mode == Target.BOSS_DROP || treasures.isEmpty())) {
            clearRoute();
            plannedKey = key;
            return;
        }

        long now = System.currentTimeMillis();
        long since = now - lastPlanMs;
        Route current = route;
        boolean replan = !key.equals(plannedKey)
                || (current == null && since > REPLAN_MAP_MS) // the last plan found no route, so don't hammer A*
                || (map.version() != plannedMapVersion && since > REPLAN_MAP_MS)
                || (current != null && offRoute(current, player) && since > REPLAN_MIN_MS)
                || since > REPLAN_PERIODIC_MS;
        if (!replan || (job != null && !job.isDone())) return;

        lastPlanMs = now;
        plannedKey = key;
        plannedMapVersion = map.version();
        int generation = tracker.generation();
        long from = start;
        Hole goalHole = hole;
        List<Point> goals = treasures;
        job = tracker.worker().submit(() -> {
            try {
                Route r = plan(map, from, mode, goals, goalHole, () -> tracker.generation() != generation);
                if (tracker.generation() == generation) route = r;
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("Wayfinder planning failed", e);
            }
        });
    }

    /**
     * True once you've reached the boss drop: within {@link #DROP_AREA} blocks of it at about its rim level (feet within
     * {@link #ROOM_LEVEL_MARGIN}) with no wall between you and the drop, or inside the drop's shaft under the rim.
     * There's deliberately no "below the main level" test: a low Y anywhere (falling while chunks load, a sunken room)
     * would trigger it.
     */
    private static boolean inBossSegment(DungeonTracker tracker, Hole hole, Point player) {
        double x = player.x(), y = player.y(), z = player.z();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        boolean inShaft = bx >= hole.minX() - 1 && bx <= hole.maxX() + 1 && bz >= hole.minZ() - 1
                && bz <= hole.maxZ() + 1 && y < hole.y() - 1 && y > hole.y() - hole.depth() - 4;
        if (inShaft) return true;
        boolean near = bx >= hole.minX() - DROP_AREA && bx <= hole.maxX() + DROP_AREA
                && bz >= hole.minZ() - DROP_AREA && bz <= hole.maxZ() + DROP_AREA;
        if (!near || Math.abs(y - hole.y()) > ROOM_LEVEL_MARGIN) return false;
        return openLine(tracker.map(), x, Math.floor(y + 0.01) + 1, z, hole.centerX(), hole.centerZ());
    }

    /**
     * True if nothing blocks a straight walk at head height ({@code headY}) from (x, z) to the drop centre: the line is
     * sampled every half block and every sampled cell must be open (unknown counts as open). Walls between rooms block it.
     */
    private static boolean openLine(DungeonMap map, double x, double headY, double z, double tx, double tz) {
        double dx = tx - x, dz = tz - z;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) * 2));
        int hy = (int) headY;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int cx = (int) Math.floor(x + dx * t), cz = (int) Math.floor(z + dz * t);
            if (map.isKnown(cx, cz) && !map.isOpen(cx, hy, cz)) return false;
        }
        return true;
    }

    /** True if it's the same boss drop, allowing a few blocks of drift between re-analyses. */
    private static boolean sameDrop(Hole a, Hole b) {
        return b != null && Math.abs(a.centerX() - b.centerX()) <= 4 && Math.abs(a.centerZ() - b.centerZ()) <= 4
                && Math.abs(a.y() - b.y()) <= 3;
    }

    private static Route plan(DungeonMap map, long start, Target mode, List<Point> treasures, Hole hole,
                              java.util.function.BooleanSupplier cancelled) {
        // Treasure first. A treasure you can't walk to yet still gets a partial route (unless the boss drop is fully
        // reachable in Auto mode).
        PathFinder.Result treasurePath = null;
        Point treasure = null;
        if (mode != Target.BOSS_DROP && !treasures.isEmpty()) {
            LongOpenHashSet goals = new LongOpenHashSet();
            List<PathFinder.Anchor> anchors = new ArrayList<>();
            for (Point t : treasures) {
                addGoalsAround(map, t, goals);
                anchors.add(new PathFinder.Anchor(t.x(), t.z(), 3));
            }
            treasurePath = search(map, start, goals, anchors, cancelled);
            if (treasurePath == null && cancelled.getAsBoolean()) return null;
            if (treasurePath != null) {
                long end = treasurePath.cells()[treasurePath.cells().length - 1];
                treasure = treasures.getFirst();
                for (Point t : treasures) {
                    if (t.distanceSq(Cell.x(end) + 0.5, Cell.y(end), Cell.z(end) + 0.5)
                            < treasure.distanceSq(Cell.x(end) + 0.5, Cell.y(end), Cell.z(end) + 0.5)) treasure = t;
                }
                if (treasurePath.complete()) return route(map, treasurePath.cells(), treasure, false, null, true);
            }
        }
        if (mode != Target.TREASURE && hole != null) {
            LongOpenHashSet goals = new LongOpenHashSet(hole.approach());
            double radius = Math.max(hole.maxX() - hole.minX(), hole.maxZ() - hole.minZ()) / 2.0 + 1;
            List<PathFinder.Anchor> anchors = List.of(new PathFinder.Anchor(hole.centerX(), hole.centerZ(), radius));
            PathFinder.Result r = search(map, start, goals, anchors, cancelled);
            if (r == null && cancelled.getAsBoolean()) return null;
            if (r != null && (r.complete() || treasurePath == null)) {
                long end = r.cells()[r.cells().length - 1];
                long rim = r.complete() ? end : nearestCell(hole.approach(), end);
                return route(map, r.cells(), dropPoint(map, rim, hole), true, hole, r.complete());
            }
        }
        if (treasurePath != null) return route(map, treasurePath.cells(), treasure, false, null, false);
        return null;
    }

    /**
     * A route using player moves only (walking, 1-up jumps, gap jumps 1-3 wide, drops, ladders, jump pads). If no goal
     * is reachable, the route ends at the reachable cell closest to it.
     */
    private static PathFinder.Result search(DungeonMap map, long start, LongOpenHashSet goals,
                                            List<PathFinder.Anchor> anchors, java.util.function.BooleanSupplier cancelled) {
        PathFinder.Result walk = PathFinder.find(map, start, goals, anchors, MAX_NODES, cancelled);
        if (walk == null || walk.complete() || !walk.capped()) return walk;
        // it ran out of nodes, not out of floor, so try once more with a bigger budget (still player moves only)
        return PathFinder.find(map, start, goals, anchors, MAX_NODES * 3, cancelled);
    }

    private static long nearestCell(long[] cells, long to) {
        long best = cells[0];
        double bestD = Double.MAX_VALUE;
        for (long c : cells) {
            double dx = Cell.x(c) - Cell.x(to), dy = Cell.y(c) - Cell.y(to), dz = Cell.z(c) - Cell.z(to);
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /**
     * Where to jump: the centre of the open column next to the route's last rim cell that drops the deepest (y is the
     * landing floor). Falls back to the hole centre.
     */
    private static Point dropPoint(DungeonMap map, long rim, Hole hole) {
        int x = Cell.x(rim), y = Cell.y(rim), z = Cell.z(rim);
        int bestLand = Integer.MAX_VALUE, bx = 0, bz = 0;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int nx = x + d[0], nz = z + d[1];
            if (!map.isOpen(nx, y, nz) || !map.isOpen(nx, y + 1, nz)) continue;
            int land = map.landingY(nx, nz, y);
            if (land != Integer.MIN_VALUE && land < bestLand) {
                bestLand = land;
                bx = nx;
                bz = nz;
            }
        }
        if (bestLand == Integer.MAX_VALUE) return new Point(hole.centerX(), hole.y() - hole.depth(), hole.centerZ());
        return new Point(bx + 0.5, bestLand, bz + 0.5);
    }

    private static Route route(DungeonMap map, long[] cells, Point target, boolean boss, Hole hole, boolean complete) {
        List<Point> sharp = PathGeometry.simplify(map, cells);
        if (complete) {
            // final step onto the target (for the boss, into the drop column at rim height)
            Point last = sharp.getLast();
            Point onFloor = new Point(target.x(), last.y(), target.z());
            if (onFloor.distanceSq(last.x(), last.y(), last.z()) > 0.25) sharp.add(onFloor);
        }
        List<Point> clean = PathGeometry.rounded(sharp);
        return new Route(List.copyOf(sharp), List.copyOf(clean), target, boss, hole, complete);
    }

    /** Standable cells within 2 blocks of a treasure, since it usually sits on or in a block. */
    private static void addGoalsAround(DungeonMap map, Point t, LongOpenHashSet goals) {
        int tx = (int) Math.floor(t.x()), ty = (int) Math.floor(t.y()), tz = (int) Math.floor(t.z());
        for (int r = 2; r <= 4 && goals.isEmpty(); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (short s : map.stand(tx + dx, tz + dz)) {
                        if (Math.abs(s - ty) <= r + 1) goals.add(Cell.pack(tx + dx, s, tz + dz));
                    }
                }
            }
        }
    }

    private static boolean offRoute(Route r, Point p) {
        List<Point> pts = r.sharp();
        if (pts.size() < 2) return false;
        return nearestOnRoute(pts, p).distSq > OFF_ROUTE_SQ;
    }

    private static String targetsKey(List<Point> treasures) {
        StringBuilder sb = new StringBuilder();
        for (Point t : treasures) {
            sb.append((int) Math.floor(t.x())).append(',').append((int) Math.floor(t.y())).append(',')
                    .append((int) Math.floor(t.z())).append(';');
        }
        return sb.toString();
    }

    private void clearRoute() {
        route = null;
        plannedKey = "";
        plannedMapVersion = -1;
        lastStart = Long.MIN_VALUE;
    }


    private List<String> blacklistOptions() {
        List<String> options = new ArrayList<>(dev.telemetric.game.TelosLocations.dungeons());
        Set<String> keys = new HashSet<>();
        for (String s : options) keys.add(DungeonTracker.normalize(s));
        for (String seen : DungeonTracker.get().seenDungeons()) {
            if (keys.add(DungeonTracker.normalize(seen))) options.add(seen);
        }
        return options;
    }

    private boolean isBlacklisted(String dungeon) {
        if (dungeon == null) return false;
        String key = DungeonTracker.normalize(dungeon);
        for (String entry : blacklist.get()) if (DungeonTracker.normalize(entry).equals(key)) return true;
        return false;
    }

    private void toggleCurrentDungeon() {
        DungeonTracker tracker = DungeonTracker.get();
        String name = tracker.isInDungeon() ? tracker.dungeonName() : null;
        if (name == null) {
            Toasts.error("Not in a dungeon (or Wayfinder is off)");
            return;
        }
        String key = DungeonTracker.normalize(name);
        String entry = name;
        for (String option : blacklistOptions()) {
            if (DungeonTracker.normalize(option).equals(key)) {
                entry = option;
                break;
            }
        }
        boolean wasListed = isBlacklisted(name);
        if (wasListed) {
            for (String e : blacklist.get()) if (DungeonTracker.normalize(e).equals(key)) blacklist.toggle(e);
            Toasts.info(name + " removed from the blacklist");
        } else {
            blacklist.toggle(entry);
            Toasts.info(name + " blacklisted");
        }
    }


    private record Nearest(int segment, Point point, double distSq) {}

    private static Nearest nearestOnRoute(List<Point> pts, Point p) {
        int bestSeg = 0;
        Point bestPoint = pts.getFirst();
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < pts.size() - 1; i++) {
            Point a = pts.get(i), b = pts.get(i + 1);
            double abx = b.x() - a.x(), aby = b.y() - a.y(), abz = b.z() - a.z();
            double len = abx * abx + aby * aby + abz * abz;
            double t = len < 1e-9 ? 0 : ((p.x() - a.x()) * abx + (p.y() - a.y()) * aby + (p.z() - a.z()) * abz) / len;
            t = Math.max(0, Math.min(1, t));
            Point q = a.lerp(b, t);
            double d = q.distanceSq(p.x(), p.y(), p.z());
            if (d < bestDist) {
                bestDist = d;
                bestSeg = i;
                bestPoint = q;
            }
        }
        return new Nearest(bestSeg, bestPoint, bestDist);
    }

    private void draw(WorldRenderAdapter.LineSink sink) {
        if (!isEnabled() || !DungeonAdapter.isInDungeon()) return;
        DungeonTracker tracker = DungeonTracker.get();
        if (showPads.get()) drawPads(sink, tracker);
        Route r = route;
        Point player = DungeonAdapter.playerPos();
        if (r == null || player == null) return;
        List<Point> pts = switch (style.get()) {
            case CLEAN -> r.clean();
            case SHARP -> r.sharp();
        };
        if (pts.isEmpty()) return;
        ColorSetting ramp = r.boss() ? bossColor : treasureColor;
        // a gradient runs along the route (you = start, target = end); the target marker uses the end colour
        int color = ramp.at(1);
        float w = (float) (double) lineWidth.get();
        boolean tw = throughWalls.get();

        // start from where the player is on the route, so the line never trails behind them
        if (showLine.get() && pts.size() >= 2) {
            Nearest n = nearestOnRoute(pts, new Point(player.x(), player.y() + PathGeometry.LIFT, player.z()));
            Point prev = n.point();
            double total = 0, done = 0;
            if (!ramp.isUniform()) {
                Point a = prev;
                for (int i = n.segment() + 1; i < pts.size(); i++) {
                    total += dist(a, pts.get(i));
                    a = pts.get(i);
                }
            }
            for (int i = n.segment() + 1; i < pts.size(); i++) {
                Point next = pts.get(i);
                if (total <= 0) {
                    sink.line(prev.x(), prev.y(), prev.z(), next.x(), next.y(), next.z(), color, w, tw);
                } else {
                    double len = dist(prev, next);
                    int steps = Math.max(1, (int) Math.ceil(len / 2)); // short pieces so multi-stop gradients show
                    for (int k = 0; k < steps; k++) {
                        double t0 = k / (double) steps, t1 = (k + 1) / (double) steps;
                        sink.line(prev.x() + (next.x() - prev.x()) * t0, prev.y() + (next.y() - prev.y()) * t0,
                                prev.z() + (next.z() - prev.z()) * t0, prev.x() + (next.x() - prev.x()) * t1,
                                prev.y() + (next.y() - prev.y()) * t1, prev.z() + (next.z() - prev.z()) * t1,
                                ramp.at((float) ((done + len * t0) / total)), ramp.at((float) ((done + len * t1) / total)), w, tw);
                    }
                    done += len;
                }
                prev = next;
            }
        }
        // If it's not complete (sealed off or unexplored), the route stops where the open way ends, never a line through
        // blocks. The target marker still shows where to go.
        if (r.boss()) drawDrop(sink, r.hole(), r.target(), color, w, tw);
        else drawTreasure(sink, r.target(), color, w, tw);
    }

    private static double dist(Point a, Point b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void drawTreasure(WorldRenderAdapter.LineSink sink, Point t, int color, float w, boolean tw) {
        double x = t.x(), y = t.y(), z = t.z(), r = 0.6;
        sink.line(x, y, z, x, y + 2.5, z, color, w, tw);
        diamond(sink, x, y + 0.05, z, r, color, w, tw);
        diamond(sink, x, y + 2.5, z, r * 0.5, color, w, tw);
    }

    /** A square around the column to jump into at rim height, a drop line down to the landing, and arrow heads. */
    private static void drawDrop(WorldRenderAdapter.LineSink sink, Hole h, Point land, int color, float w, boolean tw) {
        double rimY = h.y() + PathGeometry.LIFT;
        double cx = land.x(), cz = land.z(), ly = land.y() + PathGeometry.LIFT;
        rect(sink, cx - 0.5, cz - 0.5, cx + 0.5, cz + 0.5, rimY, color, w, tw);
        // small holes get the whole opening outlined too (big ones are an arena footprint, which isn't useful)
        if (h.maxX() - h.minX() <= 12 && h.maxZ() - h.minZ() <= 12) {
            rect(sink, h.minX(), h.minZ(), h.maxX() + 1, h.maxZ() + 1, rimY, color, w, tw);
        }
        sink.line(cx, rimY + 2.5, cz, cx, ly, cz, color, w, tw);
        arrowHead(sink, cx, rimY + 0.4, cz, color, w, tw);
        arrowHead(sink, cx, ly, cz, color, w, tw);
        diamond(sink, cx, ly + 0.05, cz, 0.6, color, w, tw);
    }

    private static void arrowHead(WorldRenderAdapter.LineSink sink, double x, double y, double z, int color,
                                  float w, boolean tw) {
        sink.line(x - 0.5, y + 0.6, z, x, y, z, color, w, tw);
        sink.line(x + 0.5, y + 0.6, z, x, y, z, color, w, tw);
        sink.line(x, y + 0.6, z - 0.5, x, y, z, color, w, tw);
        sink.line(x, y + 0.6, z + 0.5, x, y, z, color, w, tw);
    }

    /**
     * Debug: every jump pad seen. Usable pads (a floor found 0..3 below the particle source) get a box on that floor,
     * unresolved ones a red box at the particle source. A vertical tick joins the floor and the source.
     */
    private void drawPads(WorldRenderAdapter.LineSink sink, DungeonTracker tracker) {
        Point p = DungeonAdapter.playerPos();
        if (p == null) return;
        DungeonMap map = tracker.map();
        for (long src : map.padSources()) {
            int x = Cell.x(src), y = Cell.y(src), z = Cell.z(src);
            if (Math.abs(x - p.x()) > PAD_DEBUG_RANGE || Math.abs(z - p.z()) > PAD_DEBUG_RANGE) continue;
            int stand = map.standNear(x, y, z, 0, 3);
            if (stand == Integer.MIN_VALUE) {
                rect(sink, x + 0.05, z + 0.05, x + 0.95, z + 0.95, y + 0.5, PAD_BAD_DEBUG_COLOR, 2f, true);
                continue;
            }
            double fy = map.floorHeight(x, stand, z) + 0.05;
            rect(sink, x + 0.05, z + 0.05, x + 0.95, z + 0.95, fy, PAD_DEBUG_COLOR, 2f, true);
            sink.line(x + 0.5, fy, z + 0.5, x + 0.5, y + 0.5, z + 0.5, PAD_DEBUG_COLOR, 2f, true);
        }
    }

    private static void rect(WorldRenderAdapter.LineSink sink, double x0, double z0, double x1, double z1, double y,
                             int color, float w, boolean tw) {
        sink.line(x0, y, z0, x1, y, z0, color, w, tw);
        sink.line(x1, y, z0, x1, y, z1, color, w, tw);
        sink.line(x1, y, z1, x0, y, z1, color, w, tw);
        sink.line(x0, y, z1, x0, y, z0, color, w, tw);
    }

    private static void diamond(WorldRenderAdapter.LineSink sink, double x, double y, double z, double r,
                                int color, float w, boolean tw) {
        sink.line(x - r, y, z, x, y, z - r, color, w, tw);
        sink.line(x, y, z - r, x + r, y, z, color, w, tw);
        sink.line(x + r, y, z, x, y, z + r, color, w, tw);
        sink.line(x, y, z + r, x - r, y, z, color, w, tw);
    }
}
