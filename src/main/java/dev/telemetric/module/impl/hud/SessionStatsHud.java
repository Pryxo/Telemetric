package dev.telemetric.module.impl.hud;

import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * Session Stats: time played this session — in total, in the
 * Nexus, in dungeons, in the realm — and fame gained (Tab list "Fame", only increases count). Counts while this
 * element is on and you are on Telos; resets on game start or with Reset Session.
 */
public final class SessionStatsHud extends LinesHudModule {

    private static final String PLAYTIME = "Playtime", NEXUS = "Nexus", DUNGEONS = "Dungeons", REALM = "Realm",
            FAME = "Fame Gained";
    private static final List<String> ROWS = List.of(PLAYTIME, NEXUS, DUNGEONS, REALM, FAME);
    /** A longer gap between ticks (lag, alt-tab freeze) adds at most this much. */
    private static final long MAX_STEP_MS = 1000;

    private final ModeSetting format = add(new ModeSetting("Time Format", "How times are written",
            "1h 2m 3s", "1h 2m 3s", "01:02:03"));
    private final MultiChoiceSetting hidden = add(new MultiChoiceSetting("Hidden Rows",
            "Rows this element does not show", () -> ROWS));
    private final ActionSetting reset = add(new ActionSetting("Reset Session", "Start counting from zero again",
            this::resetSession));

    private long playtime, nexus, dungeons, realm, lastTick;
    private int fameGained, lastFame = -1;
    private Object world;

    public SessionStatsHud() {
        super("Session Stats", "Playtime this session (total, Nexus, dungeons, realm) and fame gained", 1.0, 0.45, false);
        addUseServerColor();
    }

    @Override
    protected String title() {
        return "Session";
    }

    private void resetSession() {
        playtime = nexus = dungeons = realm = 0;
        fameGained = 0;
        lastFame = -1;
    }

    @Override
    protected void onDisable() {
        lastTick = 0;
        lastFame = -1;
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        long step = lastTick == 0 ? 0 : Math.min(MAX_STEP_MS, now - lastTick);
        lastTick = now;
        if (!PlayerAdapter.isInWorld()) return;
        playtime += step;
        String dim = DungeonAdapter.dimensionId();
        if (DungeonAdapter.isInDungeon()) dungeons += step;
        else if (dim != null && dim.startsWith("telos:hub")) nexus += step;
        else if (dim != null && dim.startsWith("telos:realm")) realm += step;
        trackFame();
    }

    /** Fame from the Tab list; a new world / character starts a new baseline (no jump counted). */
    private void trackFame() {
        Object w = PlayerAdapter.worldToken();
        if (w != world) {
            world = w;
            lastFame = -1;
        }
        int fame = ServerInfoAdapter.tabStat("Fame").map(s -> {
            String digits = s.value().replaceAll("[^0-9]", "");
            return digits.isEmpty() || digits.length() > 9 ? -1 : Integer.parseInt(digits);
        }).orElse(-1);
        if (fame < 0) return;
        if (lastFame >= 0 && fame > lastFame) fameGained += fame - lastFame;
        lastFame = fame;
    }

    @Override
    protected List<Line> lines(boolean preview) {
        boolean demo = preview && playtime == 0;
        List<Line> lines = new ArrayList<>(ROWS.size());
        add(lines, PLAYTIME, time(demo ? 3_665_000 : playtime));
        add(lines, NEXUS, time(demo ? 600_000 : nexus));
        add(lines, DUNGEONS, time(demo ? 1_500_000 : dungeons));
        add(lines, REALM, time(demo ? 1_565_000 : realm));
        add(lines, FAME, String.format("%,d", demo ? 15420 : fameGained));
        return playtime > 0 || preview ? lines : List.of();
    }

    private void add(List<Line> lines, String label, String value) {
        if (!hidden.contains(label)) lines.add(new Line(label, value));
    }

    private String time(long ms) {
        long s = ms / 1000, h = s / 3600, m = s / 60 % 60, sec = s % 60;
        if (format.is("01:02:03")) return h > 0 ? String.format("%02d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
        return h > 0 ? h + "h " + m + "m " + sec + "s" : m > 0 ? m + "m " + sec + "s" : sec + "s";
    }
}
