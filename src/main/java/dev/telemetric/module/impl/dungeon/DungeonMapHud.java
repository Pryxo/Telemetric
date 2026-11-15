package dev.telemetric.module.impl.dungeon;

import dev.telemetric.Telemetric;
import dev.telemetric.dungeon.Hole;
import dev.telemetric.dungeon.Point;
import dev.telemetric.dungeon.RoomLayout;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.hud.HudModule;
import dev.telemetric.module.Category;
import dev.telemetric.module.impl.misc.HudSettingsModule;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.render.ColorUtil;
import dev.telemetric.ui.render.Render2D;
import dev.telemetric.ui.theme.Theme;

import java.util.List;

/**
 * Dungeon minimap HUD element (Dungeon panel). Draws the room layout the Wayfinder's analysis detected
 * ({@link DungeonTracker#analysis()}), north up, with the spawn room, the boss drop room, Dungeon Goal rooms
 * (a clear mob still alive) and rooms that still hold a treasure in their own colours, room connections as corridors and every player as their skin's face.
 */
public final class DungeonMapHud extends HudModule {

    private static final int PAD = 3;
    private static final int ROOM_GAP = 1;
    /** Gap between neighbouring rooms in map pixels (connections bridge it). */

    private final NumberSetting size = add(new NumberSetting("Map Size",
            "Width and height of the map", 100, 50, 220, 2).withSuffix("px"));
    private final GroupSetting colors = add(new GroupSetting("Colors", "Colours of the map's rooms"));
    private final ColorSetting roomColor = add(colors.child(new ColorSetting("Room Color",
            "Normal rooms and connections", 0xFF7A5230)));
    private final ColorSetting spawnColor = add(colors.child(new ColorSetting("Spawn Color",
            "The room you entered the dungeon in", 0xFF3FAE4A)));
    private final ColorSetting bossColor = add(colors.child(new ColorSetting("Boss Drop Color",
            "The room with the drop down to the boss", 0xFFE0332B)));
    private final ColorSetting treasureColor = add(colors.child(new ColorSetting("Treasure Color",
            "Rooms that still hold a treasure", 0xFFE8C93A)));
    private final ColorSetting goalColor = add(colors.child(new ColorSetting("Dungeon Goal Color",
            "Rooms with a clear mob still alive (dungeons that have them, e.g. Midnight Stronghold)", 0xFF9B4DDB)));
    private final BooleanSetting showBackground = add(new BooleanSetting("Background",
            "Draw the dark backdrop behind the map (off: only the rooms)", true));
    private final BooleanSetting showLinks = add(new BooleanSetting("Show Connections",
            "Draw the walkable connections between rooms", true));
    private final NumberSetting linkLength = add(new NumberSetting("Connection Length",
            "Extra space between rooms: longer connections, rooms keep their size", 0, 0, 10, 1)
            .withSuffix("px"));
    private final BooleanSetting showHeads = add(new BooleanSetting("Player Heads",
            "Show players as their skin's face", true));
    private final BooleanSetting showOthers = add(new BooleanSetting("Other Players",
            "Also show other players (off: only you)", true).visibleWhen(showHeads::get));
    private final NumberSetting headSize = add(new NumberSetting("Head Size",
            "Size of the player faces", 7, 4, 16, 1).withSuffix("px").visibleWhen(showHeads::get));

    private boolean acquired;
    /** Last boss drop of this run: a re-analysis may briefly lose it. */
    private Hole lastHole;
    private int holeGeneration = -1;

    public DungeonMapHud() {
        super("Dungeon Map", "Minimap of the dungeon rooms (spawn, boss drop, treasure) and players",
                Category.DUNGEON, 0.0, 0.3, false);
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
        lastHole = null;
    }

    @Override
    public boolean hasData() {
        DungeonTracker tracker = DungeonTracker.get();
        return tracker.isInDungeon() && tracker.isMapped() && !tracker.analysis().rooms().rooms().isEmpty()
                && (nearRooms(tracker.analysis().rooms())
                    || Telemetric.get().modules().get(WayfinderModule.class).isTracking());
    }

    /** Horizontal / vertical slack around a room for "the player is in the mapped dungeon". */
    private static final int NEAR_XZ = 8, NEAR_Y = 10;

    /** False when the player is away from every mapped room (e.g. down in the boss arena): the map hides. */
    private static boolean nearRooms(RoomLayout layout) {
        Point pos = DungeonAdapter.playerPos();
        if (pos == null) return false;
        double x = pos.x(), y = pos.y(), z = pos.z();
        for (RoomLayout.Room room : layout.rooms().values()) {
            if (x >= room.minX() - NEAR_XZ && x <= room.maxX() + 1 + NEAR_XZ
                    && z >= room.minZ() - NEAR_XZ && z <= room.maxZ() + 1 + NEAR_XZ
                    && Math.abs(y - room.floorY()) <= NEAR_Y) return true;
        }
        return false;
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        return size.getInt();
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        return size.getInt();
    }

    // ------------------------------------------------------------------ drawing

    enum Kind { ROOM, SPAWN, BOSS, TREASURE, GOAL }

    /** A room in block coordinates ({@code max} inclusive); {@code room} is null for the editor sample. */
    private record Box(int minX, int minZ, int maxX, int maxZ, Kind kind, RoomLayout.Room room) {
        double cx() { return (minX + maxX + 1) / 2.0; }
        double cz() { return (minZ + maxZ + 1) / 2.0; }
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        Theme p = Telemetric.get().theme().palette();
        HudSettingsModule style = Telemetric.get().modules().get(HudSettingsModule.class);
        int s = size.getInt();
        if (showBackground.get() && style.background.get()) {
            r.roundedRect(0, 0, s, s, 3, ColorUtil.fade(p.panel(), style.backgroundOpacity.get() / 100.0));
        }

        List<Box> rooms;
        List<long[]> links;
        List<DungeonAdapter.PlayerMarker> players;
        if (hasData()) {
            DungeonTracker tracker = DungeonTracker.get();
            RoomLayout layout = tracker.analysis().rooms();
            rooms = classify(tracker, layout);
            links = layout.links();
            players = showHeads.get() ? DungeonAdapter.players() : List.of();
            drawMap(r, p, s, rooms, layout, links, players);
        } else if (preview) {
            drawMap(r, p, s, sampleRooms(), null, List.of(), List.of());
        }
    }

    private List<Box> classify(DungeonTracker tracker, RoomLayout layout) {
        if (tracker.generation() != holeGeneration) {
            holeGeneration = tracker.generation();
            lastHole = null;
        }
        Hole hole = tracker.analysis().bossDrop();
        if (hole != null) lastHole = hole;
        else hole = lastHole;
        RoomLayout.Room boss = hole == null ? null : at(layout, hole.centerX(), hole.centerZ());
        Point spawnPos = tracker.spawn();
        RoomLayout.Room spawn = spawnPos == null ? null : at(layout, spawnPos.x(), spawnPos.z());
        List<Point> treasures = tracker.treasures();
        List<Point> goals = tracker.goals();

        List<Box> out = new java.util.ArrayList<>(layout.rooms().size());
        for (RoomLayout.Room room : layout.rooms().values()) {
            Kind kind = Kind.ROOM;
            if (room == boss) kind = Kind.BOSS;
            else if (room == spawn) kind = Kind.SPAWN;
            else {
                for (Point g : goals) {
                    if (at(layout, g.x(), g.z()) == room) {
                        kind = Kind.GOAL;
                        break;
                    }
                }
                if (kind == Kind.ROOM) for (Point t : treasures) {
                    if (at(layout, t.x(), t.z()) == room) {
                        kind = Kind.TREASURE;
                        break;
                    }
                }
            }
            out.add(new Box(room.minX(), room.minZ(), room.maxX(), room.maxZ(), kind, room));
        }
        return out;
    }

    private static RoomLayout.Room at(RoomLayout layout, double x, double z) {
        return layout.roomAt((int) Math.floor(x), (int) Math.floor(z));
    }

    /**
     * @param layout real layout (link keys resolve through it), or null for the editor sample
     */
    private void drawMap(Render2D r, Theme p, int s, List<Box> rooms, RoomLayout layout, List<long[]> links,
                         List<DungeonAdapter.PlayerMarker> players) {
        if (rooms.isEmpty()) return;
        int bx0 = Integer.MAX_VALUE, bz0 = Integer.MAX_VALUE, bx1 = Integer.MIN_VALUE, bz1 = Integer.MIN_VALUE;
        for (Box b : rooms) {
            bx0 = Math.min(bx0, b.minX);
            bz0 = Math.min(bz0, b.minZ);
            bx1 = Math.max(bx1, b.maxX + 1);
            bz1 = Math.max(bz1, b.maxZ + 1);
        }
        int smallest = Integer.MAX_VALUE;
        for (Box b : rooms) smallest = Math.min(smallest, Math.min(b.maxX - b.minX, b.maxZ - b.minZ) + 1);
        // every room column/row after the first is pushed out by `gap` px (rooms keep their size);
        // starts closer than half a room are one column, so slightly offset rooms don't add extra gaps
        int tol = Math.max(2, smallest / 2);
        // piece-built dungeons: one gap per grid wall line, so every link between neighbouring cells is exactly
        // `gap` long and multi-cell rooms stretch over their inner lines. Free-form rooms: room starts.
        boolean grid = layout != null && layout.owners() == null && layout.xs().length > 0 && layout.zs().length > 0;
        int[] cols = grid ? innerLines(layout.xs(), bx0, bx1) : gapLines(rooms, true, bx0, tol);
        int[] rows = grid ? innerLines(layout.zs(), bz0, bz1) : gapLines(rooms, false, bz0, tol);
        int inner = s - 2 * PAD;
        // the gaps never take more than half the square, so the rooms stay visible
        int gap = linkLength.getInt();
        int lines = Math.max(cols.length, rows.length);
        if (lines > 0) gap = Math.min(gap, inner / 2 / lines);
        double scale = Math.max(0.01, Math.min(
                (inner - gap * cols.length) / (double) Math.max(1, bx1 - bx0),
                (inner - gap * rows.length) / (double) Math.max(1, bz1 - bz0)));
        // centre the map in the square
        double ox = PAD + (inner - ((bx1 - bx0) * scale + gap * cols.length)) / 2 - bx0 * scale;
        double oz = PAD + (inner - ((bz1 - bz0) * scale + gap * rows.length)) / 2 - bz0 * scale;
        Projection proj = new Projection(ox, oz, scale, gap, cols, rows,
                grid ? layout.xs() : null, grid ? layout.zs() : null);

        if (showLinks.get() && layout != null) {
            int thick = Math.max(3, (int) Math.round(smallest * scale * 0.45));
            for (long[] link : links) {
                RoomLayout.Room a = layout.rooms().get(link[0]), b = layout.rooms().get(link[1]);
                if (a == null || b == null) continue;
                boolean horizontal = Math.abs(b.centerX() - a.centerX()) >= Math.abs(b.centerZ() - a.centerZ());
                drawLink(r, proj.room(a), proj.room(b), horizontal, thick, roomColor.get());
            }
        }
        // one icon size for every kind: fits the smallest room
        int iconSize = Math.max(5, Math.min(16, (int) Math.round(smallest * scale * 0.6)));
        for (Box b : rooms) {
            int[] px = proj.box(b);
            r.rect(px[0] + ROOM_GAP, px[1] + ROOM_GAP, px[2] - px[0] - 2 * ROOM_GAP, px[3] - px[1] - 2 * ROOM_GAP,
                    color(b.kind));
        }
        for (Box b : rooms) {
            RoomIcon icon = RoomIcon.of(b.kind);
            if (icon == null) continue;
            int[] px = proj.box(b);
            icon.draw(r, (px[0] + px[2]) / 2, (px[1] + px[3]) / 2, iconSize);
        }
        drawPlayers(r, p, proj, players, s);
    }

    /** Grid wall lines strictly inside the rooms' bounds (lo, hi): each one is a cell boundary that gets a gap. */
    private static int[] innerLines(int[] lines, int lo, int hi) {
        return java.util.Arrays.stream(lines).filter(v -> v > lo && v < hi).sorted().distinct().toArray();
    }

    /** Sorted room starts (x or z) after {@code first}; starts within {@code tol} of the last kept one are merged. */
    private static int[] gapLines(List<Box> rooms, boolean xAxis, int first, int tol) {
        java.util.TreeSet<Integer> starts = new java.util.TreeSet<>();
        for (Box b : rooms) starts.add(xAxis ? b.minX : b.minZ);
        java.util.List<Integer> out = new java.util.ArrayList<>();
        int last = first;
        for (int st : starts) {
            if (st - last >= tol) {
                out.add(st);
                last = st;
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Corridor between two linked rooms, worked out on their drawn pixel boxes so it always touches both:
     * straight through the middle of the side they share, or an elbow through both centres when they don't line up.
     */
    private static void drawLink(Render2D r, int[] a, int[] b, boolean horizontal, int thick, int c) {
        int half = thick / 2;
        int acx = (a[0] + a[2]) / 2, acz = (a[1] + a[3]) / 2, bcx = (b[0] + b[2]) / 2, bcz = (b[1] + b[3]) / 2;
        if (horizontal) {
            int lo = Math.max(a[1], b[1]) + ROOM_GAP, hi = Math.min(a[3], b[3]) - ROOM_GAP;
            if (hi - lo >= thick) {
                r.rect(Math.min(acx, bcx), (lo + hi) / 2 - half, Math.abs(bcx - acx), thick, c);
                return;
            }
            r.rect(Math.min(acx, bcx) - half, acz - half, Math.abs(bcx - acx) + thick, thick, c);
            r.rect(bcx - half, Math.min(acz, bcz) - half, thick, Math.abs(bcz - acz) + thick, c);
        } else {
            int lo = Math.max(a[0], b[0]) + ROOM_GAP, hi = Math.min(a[2], b[2]) - ROOM_GAP;
            if (hi - lo >= thick) {
                r.rect((lo + hi) / 2 - half, Math.min(acz, bcz), thick, Math.abs(bcz - acz), c);
                return;
            }
            r.rect(acx - half, Math.min(acz, bcz) - half, thick, Math.abs(bcz - acz) + thick, c);
            r.rect(Math.min(acx, bcx) - half, bcz - half, Math.abs(bcx - acx) + thick, thick, c);
        }
    }

    private void drawPlayers(Render2D r, Theme p, Projection proj, List<DungeonAdapter.PlayerMarker> players, int s) {
        int hs = headSize.getInt();
        DungeonAdapter.PlayerMarker self = null;
        for (DungeonAdapter.PlayerMarker m : players) {
            if (m.self()) self = m; // drawn last, on top
            else if (showOthers.get()) drawHead(r, proj, m, hs, s, p.shadow());
        }
        if (self != null) drawHead(r, proj, self, hs, s, p.text());
    }

    /** Face (+ hat layer) centred on the player, kept inside the map square. */
    private static void drawHead(Render2D r, Projection proj, DungeonAdapter.PlayerMarker m, int hs, int s, int border) {
        int x = Math.clamp(proj.x(m.x()) - hs / 2, 1, s - hs - 1), y = Math.clamp(proj.z(m.z()) - hs / 2, 1, s - hs - 1);
        r.rect(x - 1, y - 1, hs + 2, hs + 2, border);
        r.texture(m.skin(), x, y, hs, hs, 8, 8, 8, 8, 64, 64, 0xFFFFFFFF);   // face
        r.texture(m.skin(), x, y, hs, hs, 40, 8, 8, 8, 64, 64, 0xFFFFFFFF);  // hat layer
    }

    private int color(Kind kind) {
        return switch (kind) {
            case SPAWN -> spawnColor.get();
            case BOSS -> bossColor.get();
            case TREASURE -> treasureColor.get();
            case GOAL -> goalColor.get();
            case ROOM -> roomColor.get();
        };
    }

    /** Block → pixel; {@code cols}/{@code rows} are the sorted room starts that get pushed out by {@code gap}. */
    private record Projection(double ox, double oz, double scale, int gap, int[] cols, int[] rows,
                              int[] xs, int[] zs) {
        /** Pixel box of a drawn room: grid rooms via {@link #room}, the editor sample via {@link #rect}. */
        int[] box(Box b) {
            return b.room() != null ? room(b.room()) : rect(b.minX(), b.minZ(), b.maxX(), b.maxZ());
        }

        /**
         * Pixel box of a layout room. On the grid ({@code xs}/{@code zs} set) both edges take the gap shift of the
         * room's own cell: refined room edges sit up to a few blocks off the wall lines, and shifting by the edge
         * blocks put a room that crosses a line onto the wrong side of the gap — it then touched its neighbour and
         * the link between them shrank to a sliver.
         */
        int[] room(RoomLayout.Room r) {
            if (xs != null && r.gx() >= 0 && r.gx() < xs.length && r.gz() >= 0 && r.gz() < zs.length) {
                double ax = xs[r.gx()], az = zs[r.gz()];
                return new int[]{x(r.minX(), ax), z(r.minZ(), az), x(r.maxX() + 1, ax), z(r.maxZ() + 1, az)};
            }
            return rect(r.minX(), r.minZ(), r.maxX(), r.maxZ());
        }

        int x(double blockX) { return x(blockX, blockX); }
        int z(double blockZ) { return z(blockZ, blockZ); }
        /**
         * Pixel box {x0, z0, x1, z1} of a block box (max inclusive). The far edge takes the shift of the room's last
         * block, so a room spanning several columns stretches over the gaps inside it and stays aligned.
         */
        int[] rect(int minX, int minZ, int maxX, int maxZ) {
            return new int[]{x(minX, minX), z(minZ, minZ), x(maxX + 1, maxX), z(maxZ + 1, maxZ)};
        }
        /** {@code anchor} picks the room column whose shift applies (a room's far edge keeps its own shift). */
        int x(double blockX, double anchor) { return (int) Math.round(ox + blockX * scale + gap * count(cols, anchor)); }
        int z(double blockZ, double anchor) { return (int) Math.round(oz + blockZ * scale + gap * count(rows, anchor)); }
        private static int count(int[] starts, double v) {
            int n = 0;
            for (int st : starts) if (st <= v) n++;
            return n;
        }
    }

    /** Placeholder layout for the HUD editor when you are not in a dungeon. */
    private static List<Box> sampleRooms() {
        int[][] cells = { // gx, gz, kind
                {0, 0, 1}, {1, 0, 0}, {2, 0, 3}, {0, 1, 0}, {1, 1, 0}, {2, 1, 0},
                {1, 2, 4}, {2, 2, 2}, {0, 2, 3},
        };
        List<Box> out = new java.util.ArrayList<>();
        for (int[] c : cells) {
            int x = c[0] * 20, z = c[1] * 20;
            out.add(new Box(x, z, x + 19, z + 19, Kind.values()[c[2]], null));
        }
        return out;
    }
}
