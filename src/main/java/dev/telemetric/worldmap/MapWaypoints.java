package dev.telemetric.worldmap;

import dev.telemetric.data.BossTable;
import dev.telemetric.data.LootTable;
import dev.telemetric.data.Names;
import dev.telemetric.data.TelosData;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.tracking.RealmBossTracker;
import dev.telemetric.ui.notification.Toasts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the world map marks: boss spots ({@code Boss.json}) and miniboss spots ({@code minibosses.json}) of the world
 * you're in, bosses / minibosses seen alive nearby (their nameplates, in tracking range), and the one waypoint you
 * Track (a beam in the world and an arrow on the Minimap until you get there). Client thread.
 */
public final class MapWaypoints {

    public enum Kind { BOSS, MINIBOSS }

    /**
     * @param live  true for a mob seen right now (its nameplate), false for a fixed spot from the tables
     * @param drops "Name (33%)" lines
     */
    public record Waypoint(Kind kind, String name, double x, double y, double z, boolean live, List<String> drops) {
        public boolean sameAs(Waypoint o) {
            return o != null && o.kind == kind && o.name.equals(name) && Math.abs(o.x - x) < 1 && Math.abs(o.z - z) < 1;
        }
    }

    private static final double LIVE_RANGE = 128, ARRIVE = 8, BEAM_DRAW = 192;
    private static final int LIVE_EVERY = 10;
    private static Waypoint tracked;
    private static List<Waypoint> live = List.of();
    private static int ticks;
    private static boolean registered;

    private MapWaypoints() {}

    /** Hooks the Track beam into world rendering (once). */
    public static void register() {
        if (registered) return;
        registered = true;
        WorldRenderAdapter.addDrawer(MapWaypoints::drawBeam);
    }

    /** True in a realm (where Boss.json's realm spots are). */
    private static boolean inRealm() {
        String dim = DungeonAdapter.dimensionId();
        return dim != null && dim.startsWith("telos:realm");
    }

    /** Fixed spots of this world. */
    public static List<Waypoint> spots() {
        List<Waypoint> out = new ArrayList<>();
        String dim = DungeonAdapter.dimensionId();
        if (dim == null || DungeonAdapter.isInDungeon()) return out;
        if (inRealm()) {
            BossTable table = TelosData.BOSSES.get();
            for (BossTable.Boss b : table.all()) {
                if (b.isDungeonBoss() || b.positions() == null) continue;
                List<String> drops = drops(b);
                for (BossTable.Spot s : b.positions()) out.add(new Waypoint(Kind.BOSS, b.name(), s.x() + 0.5, s.y(), s.z() + 0.5, false, drops));
            }
        }
        for (TelosData.Minibosses.Miniboss m : TelosData.MINIBOSSES.get().all()) {
            if (!dim.equals(m.dimension()) && !dim.startsWith(m.dimension() + "/")) continue;
            for (BossTable.Spot s : m.positions()) out.add(new Waypoint(Kind.MINIBOSS, m.name(), s.x() + 0.5, s.y(), s.z() + 0.5, false, m.drops()));
        }
        return out;
    }

    /** Bosses and minibosses whose nameplates are loaded near you right now. */
    public static List<Waypoint> live() {
        return live;
    }

    private static List<String> drops(BossTable.Boss b) {
        List<String> out = new ArrayList<>();
        List<LootTable.BossLoot> loot = TelosData.LOOT.get().bossesByKey().get(b.id());
        if (loot == null) return out;
        for (LootTable.BossLoot l : loot) {
            for (LootTable.Drop d : l.drops()) out.add(d.name());
        }
        return out;
    }

    /** "Alive", "Defeated", … from the realm boss tracker, or null when unknown. */
    public static String state(Waypoint w) {
        if (w.live()) return "Alive (in range)";
        if (w.kind() != Kind.BOSS) return null;
        RealmBossTracker.Tracked t = RealmBossTracker.get().get(w.name());
        if (t == null) return null;
        return switch (t.state()) {
            case ALIVE -> t.calledBy() != null ? "Fought by " + t.calledBy() : "Alive";
            case PORTAL -> "Portal open (" + t.portalSeconds(System.currentTimeMillis()) + "s)";
            case DEFEATED -> "Defeated";
            case IDLE -> "Not spawned";
        };
    }

    public static Waypoint tracked() {
        return tracked;
    }

    public static void track(Waypoint w) {
        tracked = w;
        if (w != null) Toasts.info("Tracking " + w.name());
    }

    public static void tick() {
        ticks++;
        Waypoint t = tracked;
        Point p = DungeonAdapter.playerPos();
        if (t != null && p != null) {
            double dx = p.x() - t.x(), dz = p.z() - t.z();
            if (dx * dx + dz * dz < ARRIVE * ARRIVE) {
                tracked = null;
                Toasts.success("Arrived at " + t.name());
            }
        }
        if (DungeonAdapter.dimensionId() == null) tracked = null;
        if (ticks % LIVE_EVERY == 0) live = scanLive();
    }

    private static List<Waypoint> scanLive() {
        if (DungeonAdapter.dimensionId() == null || DungeonAdapter.isInDungeon()) return List.of();
        Map<String, Waypoint> byKey = new HashMap<>(); // plate letters → a template waypoint
        for (Waypoint w : spots()) byKey.putIfAbsent(Names.key(w.name()), w);
        Set<String> names = new HashSet<>(byKey.keySet());
        if (names.isEmpty()) return List.of();
        List<Waypoint> out = new ArrayList<>();
        for (DungeonAdapter.KnownPlate plate : DungeonAdapter.namePlatesIn(names, LIVE_RANGE)) {
            Waypoint w = byKey.get(plate.letters());
            if (w != null) out.add(new Waypoint(w.kind(), w.name(), plate.pos().x(), plate.pos().y(), plate.pos().z(), true, w.drops()));
        }
        return out;
    }

    private static void drawBeam(WorldRenderAdapter.LineSink sink) {
        Waypoint t = tracked;
        Point cam = WorldRenderAdapter.cameraPos();
        if (t == null || cam == null) return;
        double dist = Math.sqrt(cam.distanceSq(t.x(), t.y(), t.z()));
        double f = dist > BEAM_DRAW ? BEAM_DRAW / dist : 1; // far away: drawn nearer, so it stays inside the view
        double x = cam.x() + (t.x() - cam.x()) * f, z = cam.z() + (t.z() - cam.z()) * f;
        double y0 = cam.y() + (t.y() - cam.y()) * f, y1 = cam.y() + (t.y() + 160 - cam.y()) * f;
        dev.telemetric.ui.theme.Theme p = dev.telemetric.Telemetric.get().theme().palette();
        int color = ((t.kind() == Kind.BOSS ? p.accent() : p.accentAlt()) & 0x00FFFFFF) | 0xA0000000;
        sink.line(x, y0, z, x, y1, z, color, 3f, true);
    }
}
