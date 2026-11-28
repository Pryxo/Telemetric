package dev.telemetric.module.impl.visual;

import dev.telemetric.data.BossTable;
import dev.telemetric.data.BossTable.Boss;
import dev.telemetric.data.BossTable.Spot;
import dev.telemetric.data.TelosData;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ResourceAdapter;
import dev.telemetric.hud.HudIcon;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.hud.TextRun;
import dev.telemetric.module.Category;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.tracking.RealmBossTracker;
import dev.telemetric.tracking.RealmBossTracker.State;
import dev.telemetric.tracking.RealmBossTracker.Tracked;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The realm's bosses as a HUD list (their world waypoints are {@link WaypointsModule}). State (alive, fought, portal
 * open, defeated) comes from {@link RealmBossTracker}; lines and timers from {@code tables/Boss.json}. Realm only,
 * and inside a zone (Shadowlands, Radiant Isles) only that zone's bosses.
 */
public final class BossTrackerModule extends LinesHudModule {

    private final BooleanSetting autoTrack = add(new BooleanSetting("Auto Track",
            "Run /bosses when you enter a realm and read the list (the menu closes by itself)", true));
    private final BooleanSetting background = add(new BooleanSetting("Read in Background",
            "Auto Track reads the /bosses menu without ever showing it", true));
    private final BooleanSetting showList = add(new BooleanSetting("Show List",
            "Show the boss list on the HUD (World → Waypoints works without it)", true));
    private final BooleanSetting compact = add(new BooleanSetting("Compact",
            "Hide distances and the \"Alive\" text: only the boss name and who is fighting it", false));

    private final GroupSetting bossGroup = add(new GroupSetting("Bosses", "Optional bosses (always tracked, shown only when on)"));
    private final BooleanSetting showCog = add(bossGroup.child(new BooleanSetting("Cog Sentinel",
            "Show Cog Sentinel in the list", false)));
    private final BooleanSetting showOmni = add(bossGroup.child(new BooleanSetting("Voided Omnipotent",
            "Show Voided Omnipotent in the list", false)));

    private final GroupSetting colors = add(new GroupSetting("Colors", "State colours of the list"));
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
    /** Bosses of the place you're in, refreshed every tick (render code only reads this). */
    private volatile List<Tracked> here = List.of();
    private volatile boolean realm;

    public BossTrackerModule() {
        super("Boss Tracker", "Realm bosses on the HUD: alive, fought by, portal timer (waypoints: World → Waypoints)",
                Category.COMBAT, 0.0, 0.30, false);
        addUseServerColor();
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
            if (join != 0 && autoTrack.get()) tracker.scanMenu(background.get());
        }
        realm = inRealm();
        here = realm ? bossesHere() : List.of();
    }

    @Override
    protected void onDisable() {
        here = List.of();
        realm = false;
    }


    private static boolean inRealm() {
        String dim = DungeonAdapter.dimensionId();
        return dim != null && dim.startsWith("telos:realm");
    }

    /** Bosses of the place you're in: a zone's own bosses inside it, the open realm's bosses outside. */
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
        return colorSetting(t).get();
    }

    private ColorSetting colorSetting(Tracked t) {
        return switch (t.state()) {
            case PORTAL -> portalColor;
            case ALIVE -> t.calledBy() != null ? fightingColor : availableColor;
            default -> idleColor;
        };
    }

    /** The state colour the list uses. */
    public int stateColor(State state, boolean fighting) {
        return switch (state) {
            case PORTAL -> portalColor.get();
            case ALIVE -> fighting ? fightingColor.get() : availableColor.get();
            default -> idleColor.get();
        };
    }

    private static double distance(Tracked t) {
        Point me = DungeonAdapter.playerPos();
        Spot s = t.spot();
        if (me == null || s == null) return -1;
        return Math.sqrt(me.distanceSq(s.x() + 0.5, s.y() + 0.5, s.z() + 0.5));
    }


    @Override
    protected List<Line> lines(boolean preview) {
        if (!showList.get() || !realm) return preview ? previewLines() : List.of();
        long now = System.currentTimeMillis();
        List<Tracked> list = new ArrayList<>();
        for (Tracked t : here) {
            if (!t.marker && !t.boss.tracker().counted() && shown(t.boss)) list.add(t);
        }
        list.sort(Comparator.comparingInt((Tracked t) -> order(t.state())).thenComparing(t -> t.name));
        List<Line> lines = new ArrayList<>(list.size() + 6);
        for (Tracked t : list) lines.add(line(t, now));
        List<Line> counted = countedLines(now);
        if (!lines.isEmpty() && !counted.isEmpty()) lines.add(Line.DIVIDER);
        lines.addAll(counted);
        if (lines.isEmpty() && preview) return previewLines();
        return lines;
    }

    /** The bottom section below the divider: Raphael first, then the n/m counter bosses in table order. */
    private List<Line> countedLines(long now) {
        List<Line> out = new ArrayList<>(4);
        List<Line> counters = new ArrayList<>(3);
        for (Boss b : TelosData.BOSSES.get().tracked()) {
            if (!b.tracker().counted() || !shown(b)) continue;
            Tracked t = tracker.get(b.name());
            if (b.tracker().progress()) out.add(progressLine(b, t, now));
            else counters.add(counterLine(b, t, now));
        }
        out.addAll(counters);
        return out;
    }

    private Line counterLine(Boss b, Tracked t, long now) {
        State s = t == null ? State.IDLE : t.state();
        int[] c = tracker.count(b.name());
        boolean ready = c != null && c[0] >= c[1] && c[1] > 0
                || s == State.ALIVE && !b.tracker().idle(); // a spawn-only boss that's up (Defender)
        String value;
        int vc;
        if (s == State.PORTAL) {
            // the count keeps running during the cooldown, so show both
            value = t.portalSeconds(now) + "s · " + (c == null ? 0 : c[0]) + "/" + (c == null ? b.tracker().counterMax() : c[1]);
            vc = portalColor.get();
        } else if (s == State.ALIVE && t.calledBy() != null) {
            value = t.calledBy();
            vc = fightingColor.get();
        } else if (ready) {
            value = b.tracker().readyText();
            vc = portalColor.get();
        } else {
            value = c == null ? "0/" + b.tracker().counterMax() : c[0] + "/" + c[1];
            vc = availableColor.get();
        }
        int label = s == State.PORTAL || ready ? portalColor.get()
                : s == State.ALIVE && t.calledBy() != null ? fightingColor.get() : availableColor.get();
        return new Line(b.name(), value, label, vc, List.of(), icon(b));
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
        if (compact.get()) return new Line(t.name, "", color(t), 0, runs, icon(t));
        double d = t.state() == State.DEFEATED ? -1 : distance(t);
        if (d >= 0) runs.add(new TextRun((runs.isEmpty() ? "" : " ") + Math.round(d) + "m", 0, null));
        if (runs.isEmpty()) runs.add(new TextRun("Alive", availableColor.get(), null));
        return new Line(t.name, "", color(t), 0, runs, icon(t));
    }

    private Line progressLine(Boss b, Tracked t, long now) {
        boolean portal = t != null && t.state() == State.PORTAL;
        String count = tracker.progress() + "/" + tracker.progressMax();
        String value = portal ? t.portalSeconds(now) + "s · " + count : count; // kills keep counting during the portal
        int c = portal || tracker.progress() >= tracker.progressMax() ? portalColor.get() : availableColor.get();
        return new Line(b.name(), value, t == null ? availableColor.get() : color(t), c, List.of(), icon(b));
    }

    private static HudIcon icon(Tracked t) {
        return icon(t.boss);
    }

    private static HudIcon icon(Boss b) {
        if (b.icon().isEmpty()) return null;
        // an item model if the pack has one, otherwise its texture (Cog Sentinel's gear is in material/bosses/ and has no model)
        int colon = b.icon().indexOf(':');
        String ns = colon < 0 ? "minecraft" : b.icon().substring(0, colon), path = b.icon().substring(colon + 1);
        if (!ResourceAdapter.exists(ns + ":items/" + path + ".json")
                && ResourceAdapter.exists(ns + ":textures/" + path + ".png")) {
            return new HudIcon.Texture(ns + ":textures/" + path + ".png", 32, 32);
        }
        return new HudIcon.Item(b.icon());
    }

    private List<Line> previewLines() {
        return List.of(
                new Line("Anubis", "", fightingColor.get(), 0,
                        List.of(new TextRun("Steve ", fightingColor.get(), null), new TextRun("340m", 0, null)),
                        new HudIcon.Item("telos:material/boss/anubis")),
                new Line("Astaroth", "", portalColor.get(), 0,
                        List.of(new TextRun("Portal 21s", portalColor.get(), null)),
                        new HudIcon.Item("telos:material/boss/astaroth")),
                Line.DIVIDER,
                new Line("Raphael", "7/10", availableColor.get(), availableColor.get(), List.of(),
                        new HudIcon.Item("telos:material/pet/onyx")),
                new Line("Cog Sentinel", "1/5", availableColor.get(), availableColor.get(), List.of(),
                        new HudIcon.Texture("telos:textures/material/bosses/cog_sentinel.png", 32, 32)),
                new Line("Defender", "Spawned", portalColor.get(), portalColor.get(), List.of(),
                        new HudIcon.Item("telos:material/boss/defender")));
    }


    /** Hidden-by-default bosses: still tracked, only drawn when their toggle is on. */
    private boolean shown(Boss b) {
        if (b == null) return true;
        if (b.name().equalsIgnoreCase("Cog Sentinel")) return showCog.get();
        if (b.name().equalsIgnoreCase("Voided Omnipotent")) return showOmni.get();
        return true;
    }
}
