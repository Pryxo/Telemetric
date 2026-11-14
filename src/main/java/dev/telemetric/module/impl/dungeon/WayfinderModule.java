package dev.telemetric.module.impl.dungeon;

import dev.telemetric.Telemetric;
import dev.telemetric.dungeon.Cell;
import dev.telemetric.dungeon.DungeonMap;
import dev.telemetric.dungeon.Hole;
import dev.telemetric.dungeon.PathFinder;
import dev.telemetric.dungeon.PathGeometry;
import dev.telemetric.dungeon.Point;
import dev.telemetric.dungeon.RoomLayout;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.EnumSetting;
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
 * Draws the shortest walkable route on the floor from you to the nearest treasure room (yellow glowing
 * treasure) or the boss drop (the deep hole down to the arena). Only inside {@code telos:dungeon/<n>}.
 * <p>
 * Routes come from {@link PathFinder} on the dungeon worker thread; this module only decides when to
 * re-plan (targets changed, map grew, you left the route, every few seconds) and draws the result.
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
        CLEAN("Clean"), SHARP("Sharp"), ROOM_CONNECTION("Room Connection");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        @Override
        public String displayName() {
            return label;
        }
    }

    /** Telos dungeons (from the server resource pack's portal list) — blacklist options before you visit them. */
    static final List<String> KNOWN_DUNGEONS = dev.telemetric.game.TelosLocations.DUNGEONS;

    private static final int MAX_NODES = 400_000;
    private static final long REPLAN_MIN_MS = 400;
    private static final long REPLAN_MAP_MS = 1200;
    private static final long REPLAN_PERIODIC_MS = 3000;
    private static final double OFF_ROUTE_SQ = 4.0 * 4.0;
    private static final int ROOM_DEBUG_COLOR = 0xB0FFFFFF;
    private static final int LINK_DEBUG_COLOR = 0xFF55FFFF;
    private static final int PAD_DEBUG_COLOR = 0xFF55FF55;
    private static final int PAD_BAD_DEBUG_COLOR = 0xFFFF5555;
    private static final int ROOM_DEBUG_RANGE = 128;
    /** Feet within this many blocks of the boss drop room's floor count as "in the room". */
    private static final int ROOM_LEVEL_MARGIN = 4;
    /** Without a room grid: blocks around the drop that count as its room. */
    private static final int NO_ROOM_PAD = 8;

    private final EnumSetting<Target> target = add(new EnumSetting<>("Target",
            "Auto: nearest treasure room, then the boss drop once no treasure is left", Target.AUTO));
    private final EnumSetting<Style> style = add(new EnumSetting<>("Style",
            "Clean: rounded corners · Sharp: straight legs with hard corners · Room Connection: straight lines from room centre to room centre", Style.CLEAN));
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
    private final NumberSetting dropDepth = add(new NumberSetting("Drop Depth",
            "How far a hole must drop to count as the boss drop", 10, 4, 40, 1).withSuffix(" blocks"));
    private final MultiChoiceSetting blacklist = add(new MultiChoiceSetting("Blacklist",
            "Dungeons where the Wayfinder stays off", this::blacklistOptions));
    private final ActionSetting blacklistCurrent = add(new ActionSetting("Blacklist Current",
            "Add or remove the dungeon you are in", this::toggleCurrentDungeon));
    private final BooleanSetting showRooms = add(new BooleanSetting("Show Rooms",
            "Debug: outline the detected room grid and connections (minimap preview)", false));
    private final BooleanSetting showPads = add(new BooleanSetting("Show Jump Pads",
            "Debug: mark every detected jump pad (green box = used for routing, red = no floor found under it)",
            false));

    /**
     * A planned route. Complete: points include the final leg onto the target. Not complete: the target is
     * in a part you cannot reach yet, even through the air (sealed off / unexplored) - the points end at
     * the closest reachable spot; nothing is drawn through blocks.
     */
    private record Route(List<Point> sharp, List<Point> clean, Point target, boolean boss, Hole hole, boolean complete) {}

    private volatile Route route;
    /** A target (treasure / boss drop not reached yet) exists; see {@link #isTracking()}. */
    private boolean tracking;
    private Future<?> job;
    private boolean acquired;
    private long lastPlanMs;
    private long plannedMapVersion = -1;
    private String plannedKey = "";
    private long lastStart = Long.MIN_VALUE;
    /** Dungeon run ({@link DungeonTracker#generation()}) the boss latch below belongs to. */
    private int bossGeneration = Integer.MIN_VALUE;
    /** You have been in the boss drop segment this run: the boss route stays hidden. */
    private boolean bossReached;
    /** The boss drop {@link #bossReached} was latched for. */
    private Hole bossReachedHole;
    /** Last boss drop seen this run (shown while the analysis has none). */
    private Hole lastHole;

    public WayfinderModule() {
        super("Wayfinder", "Shows the shortest way to treasure rooms and the boss drop", Category.DUNGEON);
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

    // ------------------------------------------------------------------ planning

    @Override
    public void onTick() {
        DungeonTracker tracker = DungeonTracker.get();
        tracker.setMinDropDepth((int) Math.round(dropDepth.get()));
        if (!tracker.isInDungeon() || !tracker.isMapped() || isBlacklisted(tracker.dungeonName())) {
            clearRoute();
            return;
        }
        Point player = DungeonAdapter.playerPos();
        if (player == null) return;
        DungeonMap map = tracker.map();
        long start = PathFinder.startCell(map, player);
        if (start == Long.MIN_VALUE) start = lastStart; // mid-air: keep planning from the last floor cell
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
        else hole = lastHole; // drop briefly lost by a re-analysis: keep showing the last one
        // the route stays until you have really been in the boss drop segment (fell into its shaft, or stand on
        // the boss level); only then is it hidden for the rest of the run. Being low somewhere else (a sunken
        // room, a ledge) no longer hides it
        // the latch belongs to the drop that set it: a re-analysis that moves the drop (an early wrong one
        // detected right where you loaded in) clears it again
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
        tracking = true;

        long now = System.currentTimeMillis();
        long since = now - lastPlanMs;
        Route current = route;
        boolean replan = !key.equals(plannedKey)
                || (current == null && since > REPLAN_MAP_MS) // last plan found no route: don't hammer A*
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
     * You entered the boss drop's room: standing inside the room segment that holds the drop, at about its
     * level (feet within {@link #ROOM_LEVEL_MARGIN} of the room floor / rim), or inside the drop's shaft under the
     * rim. Without a room grid, a box of {@link #NO_ROOM_PAD} blocks around the drop is the room. Deliberately no
     * "below the main level" test: a low Y anywhere (falling while chunks load, a sunken room) latched it before.
     */
    private static boolean inBossSegment(DungeonTracker tracker, Hole hole, Point player) {
        double x = player.x(), y = player.y(), z = player.z();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        boolean inShaft = bx >= hole.minX() - 1 && bx <= hole.maxX() + 1 && bz >= hole.minZ() - 1
                && bz <= hole.maxZ() + 1 && y < hole.y() - 1 && y > hole.y() - hole.depth() - 4;
        if (inShaft) return true;
        RoomLayout.Room room = tracker.analysis().rooms().roomAt((int) Math.floor(hole.centerX()),
                (int) Math.floor(hole.centerZ()));
        boolean inside = room != null && room.contains(hole.minX(), hole.minZ())
                ? room.contains(bx, bz)
                : bx >= hole.minX() - NO_ROOM_PAD && bx <= hole.maxX() + NO_ROOM_PAD
                  && bz >= hole.minZ() - NO_ROOM_PAD && bz <= hole.maxZ() + NO_ROOM_PAD;
        int levelY = room != null ? room.floorY() : hole.y();
        return inside && Math.abs(y - levelY) <= ROOM_LEVEL_MARGIN;
    }

    /** Same boss drop, allowing a few blocks of drift between re-analyses. */
    private static boolean sameDrop(Hole a, Hole b) {
        return b != null && Math.abs(a.centerX() - b.centerX()) <= 4 && Math.abs(a.centerZ() - b.centerZ()) <= 4
                && Math.abs(a.y() - b.y()) <= 3;
    }

    private static Route plan(DungeonMap map, long start, Target mode, List<Point> treasures, Hole hole,
                              java.util.function.BooleanSupplier cancelled) {
        // treasure first; a treasure you cannot walk to yet still gets a partial route (unless the boss
        // drop is fully reachable in Auto mode)
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
     * Player-move route only (walk, 1-up jumps, 1-3 wide gap jumps, drops, ladders, jump pads). If no goal
     * is reachable the route ends at the reachable cell closest to it.
     */
    private static PathFinder.Result search(DungeonMap map, long start, LongOpenHashSet goals,
                                            List<PathFinder.Anchor> anchors, java.util.function.BooleanSupplier cancelled) {
        PathFinder.Result walk = PathFinder.find(map, start, goals, anchors, MAX_NODES, cancelled);
        if (walk == null || walk.complete() || !walk.capped()) return walk;
        // ran out of nodes, not out of floor: one bigger try (still player moves only)
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
     * Where to jump: centre of the open column next to the rim cell the route ends on that drops deepest
     * (y = landing floor). Falls back to the hole centre.
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
            // final step onto the target (boss: into the drop column, at rim height)
            Point last = sharp.getLast();
            Point onFloor = new Point(target.x(), last.y(), target.z());
            if (onFloor.distanceSq(last.x(), last.y(), last.z()) > 0.25) sharp.add(onFloor);
        }
        List<Point> clean = PathGeometry.rounded(sharp);
        return new Route(List.copyOf(sharp), List.copyOf(clean), target, boss, hole, complete);
    }

    /** Standable cells within 2 blocks of a treasure (it usually sits on or in a block). */
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

    /**
     * True while the Wayfinder has something to guide you to this run (a treasure, or the boss drop before you
     * reached it) — the Dungeon Map stays shown then, wherever you stand.
     */
    public boolean isTracking() {
        return isEnabled() && tracking;
    }

    private void clearRoute() {
        tracking = false;
        route = null;
        plannedKey = "";
        plannedMapVersion = -1;
        lastStart = Long.MIN_VALUE;
    }

    // ------------------------------------------------------------------ blacklist

    private List<String> blacklistOptions() {
        List<String> options = new ArrayList<>(KNOWN_DUNGEONS);
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

    // ------------------------------------------------------------------ drawing

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
        if (showRooms.get()) drawRooms(sink, tracker);
        if (showPads.get()) drawPads(sink, tracker);
        Route r = route;
        Point player = DungeonAdapter.playerPos();
        if (r == null || player == null) return;
        List<Point> pts = switch (style.get()) {
            case CLEAN -> r.clean();
            case SHARP -> r.sharp();
            case ROOM_CONNECTION -> roomConnection(r, tracker.analysis().rooms());
        };
        if (pts.isEmpty()) return;
        int color = r.boss() ? bossColor.get() : treasureColor.get();
        float w = (float) (double) lineWidth.get();
        boolean tw = throughWalls.get();

        // start where the player is on the route, so the line never trails behind
        if (showLine.get() && pts.size() >= 2) {
            Nearest n = nearestOnRoute(pts, new Point(player.x(), player.y() + PathGeometry.LIFT, player.z()));
            Point prev = n.point();
            for (int i = n.segment() + 1; i < pts.size(); i++) {
                Point next = pts.get(i);
                sink.line(prev.x(), prev.y(), prev.z(), next.x(), next.y(), next.z(), color, w, tw);
                prev = next;
            }
        }
        // not complete = sealed off / unexplored: the route stops where the open way ends (never a line
        // through blocks); the target marker still shows where to go
        if (r.boss()) drawDrop(sink, r.hole(), r.target(), color, w, tw);
        else drawTreasure(sink, r.target(), color, w, tw);
    }

    /**
     * Room Connection style: the rooms the route passes through, in order, joined centre to centre
     * (like the Show Rooms links), then the last room's centre to the target. Falls back to the sharp
     * route when no room grid is detected.
     */
    private static List<Point> roomConnection(Route r, RoomLayout layout) {
        List<Point> sharp = r.sharp();
        if (layout.rooms().isEmpty() || sharp.isEmpty()) return sharp;
        List<Point> out = new java.util.ArrayList<>();
        RoomLayout.Room last = null;
        for (int i = 0; i < sharp.size(); i++) {
            Point a = sharp.get(i);
            Point b = i + 1 < sharp.size() ? sharp.get(i + 1) : a;
            // sample each leg so rooms crossed between corners are not skipped
            double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
            int steps = Math.max(1, (int) Math.ceil(len / 2.0));
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                RoomLayout.Room room = layout.roomAt((int) Math.floor(a.x() + (b.x() - a.x()) * t),
                        (int) Math.floor(a.z() + (b.z() - a.z()) * t));
                if (room != null && room != last) {
                    out.add(new Point(room.centerX(), room.floorY() + 0.3, room.centerZ()));
                    last = room;
                }
            }
        }
        if (out.isEmpty()) return sharp;
        out.add(r.complete() ? r.target() : sharp.get(sharp.size() - 1));
        return out;
    }

    private static void drawTreasure(WorldRenderAdapter.LineSink sink, Point t, int color, float w, boolean tw) {
        double x = t.x(), y = t.y(), z = t.z(), r = 0.6;
        sink.line(x, y, z, x, y + 2.5, z, color, w, tw);
        diamond(sink, x, y + 0.05, z, r, color, w, tw);
        diamond(sink, x, y + 2.5, z, r * 0.5, color, w, tw);
    }

    /** Square around the column to jump into at rim height, a drop line down to the landing, arrow heads. */
    private static void drawDrop(WorldRenderAdapter.LineSink sink, Hole h, Point land, int color, float w, boolean tw) {
        double rimY = h.y() + PathGeometry.LIFT;
        double cx = land.x(), cz = land.z(), ly = land.y() + PathGeometry.LIFT;
        rect(sink, cx - 0.5, cz - 0.5, cx + 0.5, cz + 0.5, rimY, color, w, tw);
        // small holes: outline the whole opening too (big ones are an arena footprint, not useful)
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

    private void drawRooms(WorldRenderAdapter.LineSink sink, DungeonTracker tracker) {
        RoomLayout layout = tracker.analysis().rooms();
        Point p = DungeonAdapter.playerPos();
        if (layout.rooms().isEmpty() || p == null) return;
        Hole boss = tracker.analysis().bossDrop();
        List<Point> treasures = tracker.treasures();
        // visual only: draw every room and link on one height so the grid reads flat — the dungeon level
        int level = tracker.analysis().level();
        double flatY = level != Integer.MIN_VALUE ? level : layout.rooms().values().iterator().next().floorY();
        RoomLayout.Room bossRoom = bossRoom(layout, boss);
        for (RoomLayout.Room room : layout.rooms().values()) {
            if (Math.abs(room.centerX() - p.x()) > ROOM_DEBUG_RANGE || Math.abs(room.centerZ() - p.z()) > ROOM_DEBUG_RANGE) continue;
            int color = ROOM_DEBUG_COLOR;
            if (room == bossRoom) {
                color = bossColor.get();
            } else {
                for (Point t : treasures) {
                    if (layout.roomAt((int) Math.floor(t.x()), (int) Math.floor(t.z())) == room) {
                        color = treasureColor.get();
                        break;
                    }
                }
            }
            rect(sink, room.minX() + 0.2, room.minZ() + 0.2, room.maxX() + 0.8, room.maxZ() + 0.8,
                    flatY + 0.1, color, 1.5f, true);
        }
        for (long[] link : layout.links()) {
            RoomLayout.Room a = layout.rooms().get(link[0]), b = layout.rooms().get(link[1]);
            if (a == null || b == null) continue;
            double ax = a.centerX(), az = a.centerZ();
            double bx = b.centerX(), bz = b.centerZ();
            if (Math.abs(ax - p.x()) > ROOM_DEBUG_RANGE || Math.abs(az - p.z()) > ROOM_DEBUG_RANGE) continue;
            sink.line(ax, flatY + 0.3, az, bx, flatY + 0.3, bz, LINK_DEBUG_COLOR, 1.5f, true);
        }
    }

    /** The room holding the boss drop (free-form layouts get one added over the drop, {@code RoomLayout#withDropRoom}). */
    private static RoomLayout.Room bossRoom(RoomLayout layout, Hole boss) {
        if (boss == null) return null;
        return layout.roomAt((int) Math.floor(boss.centerX()), (int) Math.floor(boss.centerZ()));
    }

    /**
     * Debug: every jump pad seen. Usable pads (a floor found 0..3 below the particle source): box on that
     * floor. Unresolved ones: red box at the particle source. A vertical tick joins floor and source.
     */
    private void drawPads(WorldRenderAdapter.LineSink sink, DungeonTracker tracker) {
        Point p = DungeonAdapter.playerPos();
        if (p == null) return;
        DungeonMap map = tracker.map();
        for (long src : map.padSources()) {
            int x = Cell.x(src), y = Cell.y(src), z = Cell.z(src);
            if (Math.abs(x - p.x()) > ROOM_DEBUG_RANGE || Math.abs(z - p.z()) > ROOM_DEBUG_RANGE) continue;
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
