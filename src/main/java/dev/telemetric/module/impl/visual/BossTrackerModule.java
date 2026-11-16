package dev.telemetric.module.impl.visual;

import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatText;
import dev.telemetric.data.BossTable;
import dev.telemetric.data.BossTable.Spot;
import dev.telemetric.data.TelosData;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.ChatAdapter;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.WorldLabelAdapter;
import dev.telemetric.game.WorldRenderAdapter;
import dev.telemetric.hud.HudIcon;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.hud.TextRun;
import dev.telemetric.module.Category;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.tracking.RealmBossTracker;
import dev.telemetric.tracking.RealmBossTracker.State;
import dev.telemetric.tracking.RealmBossTracker.Tracked;
import dev.telemetric.ui.render.BadgeText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Boss Tracker: the realm's bosses as a HUD list and as waypoints in
 * the world — alive / being fought (who called it) / portal open (seconds left) / defeated, Raphael's n/m. State
 * comes from {@link RealmBossTracker} ({@code /bosses} menu + server lines); bosses, spots, lines and timers from
 * {@code Extractor/bosses.json}. Only in the realm; in a zone (Shadowlands, Radiant Isles) only that zone's bosses.
 */
public final class BossTrackerModule extends LinesHudModule {

    /** Waypoints closer than this are hidden (you are there); they fade in over {@link #FADE}. */
    private static final String STYLE_ICON = "Icon", STYLE_TEXT = "Text", STYLE_BOTH = "Both";
    private static final double NEAR = 20, FADE = 50, MAX_DRAW = 192, LOOK_DOT = 0.995;

    private final BooleanSetting autoTrack = add(new BooleanSetting("Auto Track",
            "Run /bosses when you enter a realm and read the list (the menu closes by itself)", true));
    private final BooleanSetting showList = add(new BooleanSetting("Show List",
            "Show the boss list on the HUD (waypoints work without it)", true));
    private final BooleanSetting hideDefeatLines = add(new BooleanSetting("Hide Defeat Messages",
            "Hide the server's \"<Boss> has been defeated\" lines while the list shows them", true));

    private final GroupSetting waypointGroup = add(new GroupSetting("Waypoints", "Markers at the bosses' spots"));
    private final BooleanSetting waypoints = add(waypointGroup.child(new BooleanSetting("Show Waypoints",
            "Marker with name, fighter, portal time and distance at each boss", true)));
    private final BooleanSetting beams = add(waypointGroup.child(new BooleanSetting("Beams",
            "Light beam above each waypoint", true).visibleWhen(waypoints::get)));
    private final ModeSetting waypointStyle = add(waypointGroup.child(new ModeSetting("Style",
            "What each waypoint shows: the boss's icon, its text (name, fighter, timer, distance) or both",
            STYLE_BOTH, STYLE_ICON, STYLE_TEXT, STYLE_BOTH).visibleWhen(waypoints::get)));
    private final BooleanSetting showAvailable = add(waypointGroup.child(new BooleanSetting("Available",
            "Waypoints for bosses nobody fights yet", true).visibleWhen(waypoints::get)));
    private final BooleanSetting showFighting = add(waypointGroup.child(new BooleanSetting("Fighting",
            "Waypoints for bosses someone is fighting", true).visibleWhen(waypoints::get)));
    private final BooleanSetting showPortal = add(waypointGroup.child(new BooleanSetting("Portal",
            "Waypoints for defeated bosses whose portal is still open", true).visibleWhen(waypoints::get)));
    private final NumberSetting labelSize = add(waypointGroup.child(new NumberSetting("Label Size",
            "Waypoint text size (stays the same on screen at any distance)", 1.0, 0.5, 2.5, 0.1)
            .withSuffix("x").visibleWhen(waypoints::get)));
    private final KeybindSetting teleportKey = add(waypointGroup.child(new KeybindSetting("Quick Teleport",
            "Look at a boss waypoint someone is fighting and press: /tp to that player", KeybindSetting.NONE)
            .onPress(this::quickTeleport).visibleWhen(waypoints::get)));

    private final GroupSetting colors = add(new GroupSetting("Colors", "State colours (list + waypoints)"));
    private final ColorSetting availableColor = add(colors.child(new ColorSetting("Available Color",
            "Alive, nobody fighting", 0xFFF2F2FF, false)));
    private final ColorSetting fightingColor = add(colors.child(new ColorSetting("Fighting Color",
            "Someone is fighting it", 0xFF55FF55, false)));
    private final ColorSetting portalColor = add(colors.child(new ColorSetting("Portal Color",
            "Defeated, portal open", 0xFFFFD700, false)));
    private final ColorSetting idleColor = add(colors.child(new ColorSetting("Idle Color",
            "Not spawned (always-present bosses) / defeated", 0xFF555555, false)));

    private final RealmBossTracker tracker = RealmBossTracker.get();
    private long seenRealmJoin;
    /** Bosses of the place you are in, refreshed each tick (render code reads only this). */
    private volatile List<Tracked> here = List.of();

    public BossTrackerModule() {
        super("Boss Tracker", "Realm bosses: list on the HUD + waypoints (alive, fought by, portal timer)",
                Category.VISUAL, 0.0, 0.30, false);
        addUseServerColor();
        WorldLabelAdapter.addSource(this::labels);
        WorldRenderAdapter.addDrawer(this::drawBeams);
        ChatBus.hide(RealmBossTracker.DEFEATED, m -> isEnabled() && hideDefeatLines.get() && showList.get()
                && !DungeonAdapter.isInDungeon());
    }

    @Override
    protected String title() {
        return "Realm Bosses";
    }

    @Override
    public void onTick() {
        long join = tracker.realmJoinedAt();
        if (join != seenRealmJoin) {
            seenRealmJoin = join;
            if (join != 0 && autoTrack.get()) tracker.scanMenu();
        }
        here = inRealm() ? bossesHere() : List.of();
    }

    @Override
    protected void onDisable() {
        here = List.of();
    }

    // ------------------------------------------------------------------ what is shown

    private static boolean inRealm() {
        String dim = DungeonAdapter.dimensionId();
        return dim != null && dim.startsWith("telos:realm");
    }

    /** Bosses of the place you are in: a zone's own bosses inside it, the open realm's outside. */
    private List<Tracked> bossesHere() {
        BossTable table = TelosData.BOSSES.get();
        String zone = table.zoneOf(PlayerAdapter.locationName());
        List<Tracked> out = new ArrayList<>();
        for (Tracked t : tracker.all()) {
            String bossZone = table.zoneOf(t.boss);
            if (zone == null ? bossZone == null : zone.equals(bossZone)) out.add(t);
        }
        return out;
    }

    private int color(Tracked t) {
        return switch (t.state()) {
            case PORTAL -> portalColor.get();
            case ALIVE -> t.calledBy() != null ? fightingColor.get() : availableColor.get();
            default -> idleColor.get();
        };
    }

    private static double distance(Tracked t) {
        Point me = DungeonAdapter.playerPos();
        Spot s = t.spot();
        if (me == null || s == null) return -1;
        return Math.sqrt(me.distanceSq(s.x() + 0.5, s.y() + 0.5, s.z() + 0.5));
    }

    // ------------------------------------------------------------------ HUD list

    @Override
    protected List<Line> lines(boolean preview) {
        if (!showList.get() || here.isEmpty()) return preview ? previewLines() : List.of();
        long now = System.currentTimeMillis();
        List<Tracked> list = new ArrayList<>();
        Tracked progressBoss = null;
        for (Tracked t : here) {
            if (t.marker) continue;
            if (t.boss.tracker().progress()) progressBoss = t;
            else list.add(t);
        }
        list.sort(Comparator.comparingInt((Tracked t) -> order(t.state())).thenComparing(t -> t.name));
        List<Line> lines = new ArrayList<>(list.size() + 1);
        for (Tracked t : list) lines.add(line(t, now));
        if (progressBoss != null && (tracker.progress() > 0 || progressBoss.state() == State.PORTAL)) {
            lines.add(progressLine(progressBoss, now));
        }
        if (lines.isEmpty() && preview) return previewLines();
        return lines;
    }

    private static int order(State s) {
        return switch (s) {
            case PORTAL -> 0;
            case ALIVE -> 1;
            case IDLE -> 2;
            case DEFEATED -> 3;
        };
    }

    private Line line(Tracked t, long now) {
        List<TextRun> runs = new ArrayList<>(3);
        switch (t.state()) {
            case PORTAL -> runs.add(new TextRun("Portal " + t.portalSeconds(now) + "s", portalColor.get(), null));
            case IDLE -> runs.add(new TextRun("Idle", idleColor.get(), null));
            case DEFEATED -> runs.add(new TextRun("Defeated", idleColor.get(), null));
            case ALIVE -> {
                if (t.calledBy() != null) runs.add(new TextRun(t.calledBy() + " ", fightingColor.get(), null));
            }
        }
        double d = t.state() == State.DEFEATED ? -1 : distance(t);
        if (d >= 0) runs.add(new TextRun((runs.isEmpty() ? "" : " ") + Math.round(d) + "m", 0, null));
        if (runs.isEmpty()) runs.add(new TextRun("Alive", availableColor.get(), null));
        return new Line(t.name, "", color(t), 0, runs, icon(t));
    }

    private Line progressLine(Tracked t, long now) {
        String value = t.state() == State.PORTAL ? "Portal " + t.portalSeconds(now) + "s"
                : tracker.progress() + "/" + tracker.progressMax();
        int c = t.state() == State.PORTAL || tracker.progress() >= tracker.progressMax()
                ? portalColor.get() : availableColor.get();
        return new Line(t.name, value, color(t), c, List.of(), icon(t));
    }

    private static HudIcon icon(Tracked t) {
        return t.boss.icon().isEmpty() ? null : new HudIcon.Item(t.boss.icon());
    }

    private List<Line> previewLines() {
        return List.of(
                new Line("Anubis", "", fightingColor.get(), 0,
                        List.of(new TextRun("Steve ", fightingColor.get(), null), new TextRun("340m", 0, null)),
                        new HudIcon.Item("telos:material/boss/anubis")),
                new Line("Astaroth", "", portalColor.get(), 0,
                        List.of(new TextRun("Portal 21s", portalColor.get(), null)),
                        new HudIcon.Item("telos:material/boss/astaroth")),
                new Line("Raphael", "7/10", availableColor.get(), availableColor.get(), List.of(),
                        new HudIcon.Item("telos:material/pet/onyx")));
    }

    // ------------------------------------------------------------------ waypoints

    /** Waypoints to draw now: shown state, in this place, with a spot. */
    private List<Tracked> waypointBosses() {
        if (!isEnabled() || !waypoints.get()) return List.of();
        List<Tracked> out = new ArrayList<>();
        for (Tracked t : here) {
            if (t.spot() == null) continue;
            boolean show = switch (t.state()) {
                case DEFEATED -> false;
                case IDLE -> true;
                case PORTAL -> showPortal.get();
                case ALIVE -> t.marker || (t.calledBy() != null ? showFighting.get() : showAvailable.get());
            };
            if (show) out.add(t);
        }
        return out;
    }

    private static double fade(double distance) {
        if (distance < NEAR) return 0;
        return Math.min(1, (distance - NEAR) / FADE);
    }

    /** Pulls far points toward the camera so they stay inside the view distance (same spot on screen). */
    private static double[] clamp(Point cam, double x, double y, double z, double scaleFrom) {
        double f = scaleFrom > MAX_DRAW ? MAX_DRAW / scaleFrom : 1;
        return new double[]{cam.x() + (x - cam.x()) * f, cam.y() + (y - cam.y()) * f, cam.z() + (z - cam.z()) * f, f};
    }

    private List<WorldLabelAdapter.Label> labels(float partialTick) {
        List<Tracked> list = waypointBosses();
        Point cam = WorldRenderAdapter.cameraPos();
        if (list.isEmpty() || cam == null) return List.of();
        long now = System.currentTimeMillis();
        List<WorldLabelAdapter.Label> out = new ArrayList<>(list.size());
        for (Tracked t : list) {
            Spot s = t.spot();
            double x = s.x() + 0.5, y = s.y() + 1.5, z = s.z() + 0.5;
            double dist = Math.sqrt(cam.distanceSq(x, y, z));
            double alpha = fade(dist);
            if (alpha <= 0.01) continue;
            double[] p = clamp(cam, x, y, z, dist);
            double height = Math.max(0.25, dist * p[3] * 0.018 * labelSize.get());
            int argb = (color(t) & 0x00FFFFFF) | ((int) Math.round(alpha * 255) << 24);
            boolean icon = !waypointStyle.is(STYLE_TEXT) && !t.boss.icon().isEmpty();
            String text = waypointStyle.is(STYLE_ICON) && icon ? "" : waypointText(t, now, dist);
            out.add(new WorldLabelAdapter.Label(p[0], p[1], p[2], text, BadgeText.Style.PLAIN,
                    height, argb, true).look(false, true).withIcon(icon ? t.boss.icon() : null));
        }
        return out;
    }

    private static String waypointText(Tracked t, long now, double dist) {
        StringBuilder b = new StringBuilder(t.name);
        if (t.calledBy() != null) b.append(" [").append(t.calledBy()).append(']');
        if (t.state() == State.PORTAL) b.append(" [").append(t.portalSeconds(now)).append("s]");
        if (t.state() == State.IDLE) b.append(" [Idle]");
        b.append(" [").append(Math.round(dist)).append("m]");
        return b.toString();
    }

    private void drawBeams(WorldRenderAdapter.LineSink sink) {
        if (!beams.get()) return;
        List<Tracked> list = waypointBosses();
        Point cam = WorldRenderAdapter.cameraPos();
        if (list.isEmpty() || cam == null) return;
        for (Tracked t : list) {
            Spot s = t.spot();
            double x = s.x() + 0.5, z = s.z() + 0.5;
            double dist = Math.sqrt(cam.distanceSq(x, s.y(), z));
            double alpha = fade(dist) * 0.5;
            if (alpha <= 0.01) continue;
            double[] a = clamp(cam, x, s.y(), z, dist), b = clamp(cam, x, s.y() + 120, z, dist);
            int argb = (color(t) & 0x00FFFFFF) | ((int) Math.round(alpha * 255) << 24);
            sink.line(a[0], a[1], a[2], b[0], b[1], b[2], argb, 3f, true);
        }
    }

    // ------------------------------------------------------------------ quick teleport

    private void quickTeleport() {
        if (!waypoints.get()) return;
        Point eyes = PlayerAdapter.eyes();
        double[] look = PlayerAdapter.look();
        String me = PlayerAdapter.name();
        if (eyes == null || look == null) return;
        Tracked best = null;
        double bestDot = LOOK_DOT;
        for (Tracked t : waypointBosses()) {
            if (t.calledBy() == null || t.spot() == null) continue;
            double dx = t.spot().x() + 0.5 - eyes.x(), dy = t.spot().y() + 1.5 - eyes.y(), dz = t.spot().z() + 0.5 - eyes.z();
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6) continue;
            double dot = (dx * look[0] + dy * look[1] + dz * look[2]) / len;
            if (dot > bestDot) {
                bestDot = dot;
                best = t;
            }
        }
        if (best == null) {
            ChatAdapter.post(ChatText.of().add("Look at a boss waypoint someone is fighting to teleport", ChatText.RED));
            return;
        }
        if (best.calledBy().equalsIgnoreCase(me)) {
            ChatAdapter.post(ChatText.of().add("You are the one fighting " + best.name, ChatText.RED));
            return;
        }
        PlayerAdapter.sendCommand("tp " + best.calledBy());
        ChatAdapter.post(ChatText.of().add("Teleporting to ", ChatText.GRAY)
                .add(best.calledBy(), ChatText.YELLOW, ChatText.UNDERLINED).add(" at ", ChatText.GRAY)
                .add(best.name, ChatText.YELLOW, ChatText.BOLD));
    }
}
