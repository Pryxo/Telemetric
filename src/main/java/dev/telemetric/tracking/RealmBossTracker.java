package dev.telemetric.tracking;

import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatPattern;
import dev.telemetric.data.BossTable;
import dev.telemetric.data.BossTable.Boss;
import dev.telemetric.data.BossTable.Spot;
import dev.telemetric.data.Names;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.BossMenuAdapter;
import dev.telemetric.game.BossMenuAdapter.MenuItem;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * State of the realm's bosses for the Boss Tracker. Fed by:
 * <ul>
 *   <li>the {@code /bosses} menu ({@code » [Anubis] «} items, lore "alive" / "defeated" / "not spawned", Raphael's
 *       {@code (n/m)}) — read whenever it closes, or automatically on realm entry ({@link #scanMenu});</li>
 *   <li>server lines: {@code [Boss] …} (alive; the boss's {@code call} regex names who fights it; {@code spawn} /
 *       {@code defeat} lines of always-present bosses), {@code Boss has been defeated (n/m)} (portal open for
 *       {@code portalSeconds}; at n = m the {@code progress} boss's portal opens), {@code Boss has spawned at x, y, z}.</li>
 * </ul>
 * Which bosses exist, where, their lines and timers: {@code tables/Boss.json} ({@code tracker} data). Pure logic
 * — the menu comes through {@link BossMenuAdapter}. Chat layouts were not read live yet ({@code anyRuns}).
 */
public final class RealmBossTracker {

    public enum State { IDLE, ALIVE, PORTAL, DEFEATED }

    /** One tracked boss or fixed marker (Cog Stabilizer). */
    public static final class Tracked {
        public final Boss boss;
        public final String name;
        public final boolean marker;
        private Spot spot;
        private State state;
        private String calledBy;
        private long portalUntil;

        Tracked(Boss boss, String name, Spot spot, State state, boolean marker) {
            this.boss = boss;
            this.name = name;
            this.spot = spot;
            this.state = state;
            this.marker = marker;
        }

        public Spot spot() { return spot; }
        public State state() { return state; }
        /** Player the boss named while fighting them, or null. */
        public String calledBy() { return calledBy; }

        /** Seconds left on the portal (0 when none). */
        public int portalSeconds(long now) {
            return state == State.PORTAL ? (int) Math.max(0, (portalUntil - now + 999) / 1000) : 0;
        }
    }

    private static final Pattern MENU_NAME = Pattern.compile("» \\[(.+?)] «");
    private static final Pattern PROGRESS = Pattern.compile("\\((\\d+)/(\\d+)\\)");

    /** {@code [Boss] text} — a boss speaking (no ':' so no player chat line). */
    static final ChatPattern BOSS_LINE = ChatPattern.builder("Boss line",
            "\\[(?<boss>[A-Za-z' ]{2,30})] (?<text>[^\\n]+)").anyRuns().build();
    /** {@code Boss has been defeated (3/10)}. */
    public static final ChatPattern DEFEATED = ChatPattern.builder("Boss defeated",
            "(?<boss>[A-Za-z' ]{2,30}) has been defeated(?: \\((?<n>\\d+)/(?<total>\\d+)\\))?[^\\n]*").anyRuns().build();
    /** {@code Boss has spawned at x, y, z}. */
    static final ChatPattern SPAWNED = ChatPattern.builder("Boss spawned",
            "(?<boss>[A-Za-z' ]{2,30}) has spawned at (?<x>-?[\\d.]+), (?<y>-?[\\d.]+), (?<z>-?[\\d.]+)[^\\n]*")
            .anyRuns().build();

    private static final RealmBossTracker INSTANCE = new RealmBossTracker();

    private final Map<String, Tracked> tracked = new LinkedHashMap<>();
    private int progress, progressMax = 10;
    private long realmJoinedAt;
    private Object world;
    private boolean wasDungeon;
    private boolean started;

    private RealmBossTracker() {}

    public static RealmBossTracker get() {
        return INSTANCE;
    }

    /** Subscribes the chat lines and menu reads (once). Call {@link #clear()} when a new realm is joined. */
    public void start() {
        if (started) return;
        started = true;
        ChatBus.on(BOSS_LINE, m -> onBossLine(m.group(1).trim(), m.group(2)));
        ChatBus.on(DEFEATED, this::onDefeated);
        ChatBus.on(SPAWNED, this::onSpawned);
        BossMenuAdapter.onMenuClosed(this::readMenu);
        TelosData.BOSSES.onChange(t -> clear());
        clear();
    }

    // ------------------------------------------------------------------ read

    public synchronized List<Tracked> all() {
        return List.copyOf(tracked.values());
    }

    public synchronized Tracked get(String name) {
        return tracked.get(Names.key(name));
    }

    /** World bosses down this realm (Raphael's portal opens at max). */
    public int progress() { return progress; }
    public int progressMax() { return progressMax; }

    /** When a realm was last joined from somewhere other than a dungeon (0 = not yet) — time to read /bosses. */
    public long realmJoinedAt() { return realmJoinedAt; }

    // ------------------------------------------------------------------ lifecycle

    /** New realm / hub: forget everything but the always-present bosses and markers. */
    public synchronized void clear() {
        tracked.clear();
        progress = 0;
        progressMax = 10;
        for (Boss b : TelosData.BOSSES.get().tracked()) {
            if (!b.tracker().idle()) continue;
            add(b, b.tracker().spawn() != null ? State.IDLE : State.ALIVE); // no spawn line: always up
            for (Spot w : b.tracker().waypoints()) {
                tracked.put(Names.key(w.name()), new Tracked(b, w.name(), w, State.ALIVE, true));
            }
        }
    }

    /**
     * Every client tick (on Telos): a new hub / realm (not coming back from a dungeon) forgets the old realm's
     * bosses; portals that ran out become defeated.
     */
    public void tick() {
        Object w = PlayerAdapter.worldToken();
        if (w != null && w != world) {
            world = w;
            String dim = DungeonAdapter.dimensionId();
            if (dim == null) dim = "";
            if (dim.startsWith("telos:hub") || dim.startsWith("telos:daily")) {
                clear();
            } else if (dim.startsWith("telos:realm") && !wasDungeon) {
                clear();
                realmJoinedAt = System.currentTimeMillis();
            }
            wasDungeon = DungeonAdapter.isInDungeon();
        }
        expirePortals(System.currentTimeMillis());
    }

    private synchronized void expirePortals(long now) {
        for (Tracked t : tracked.values()) {
            if (t.state != State.PORTAL || now < t.portalUntil) continue;
            t.state = State.DEFEATED;
            if (t.boss.tracker() != null && t.boss.tracker().progress()) progress = 0;
        }
    }

    /** Runs {@code /bosses}, reads the list and closes it (only that menu). */
    public void scanMenu() {
        BossMenuAdapter.runCommandAndRead("bosses", RealmBossTracker::isBossMenu, this::readMenu);
    }

    // ------------------------------------------------------------------ inputs

    private static boolean isBossMenu(List<MenuItem> items) {
        for (MenuItem i : items) if (MENU_NAME.matcher(i.name()).find()) return true;
        return false;
    }

    private synchronized void readMenu(List<MenuItem> items) {
        if (!isBossMenu(items)) return;
        for (MenuItem i : items) {
            Matcher name = MENU_NAME.matcher(i.name());
            if (i.name().contains("Raphael") || i.lore().contains("Raphael")) {
                Matcher p = PROGRESS.matcher(i.lore());
                if (p.find()) {
                    progress = Integer.parseInt(p.group(1));
                    progressMax = Integer.parseInt(p.group(2));
                }
                continue;
            }
            if (!name.find()) continue;
            String boss = name.group(1);
            if (i.lore().contains("This boss is alive")) set(boss, State.ALIVE);
            else if (i.lore().contains("This boss has been defeated")) set(boss, State.DEFEATED);
            else if (i.lore().contains("This boss has not spawned")) remove(boss);
        }
    }

    private synchronized void onBossLine(String name, String text) {
        Boss boss = TelosData.BOSSES.get().realm(name);
        if (boss == null || boss.tracker() == null) return;
        BossTable.Tracker t = boss.tracker();
        if (t.defeat() != null && t.defeat().matcher(text).matches()) {
            Tracked tr = set(name, State.IDLE);
            if (tr != null) tr.calledBy = null;
            return;
        }
        if (t.spawn() != null && t.spawn().matcher(text).matches()) {
            set(name, State.ALIVE);
            return;
        }
        Matcher call = t.call() == null ? null : t.call().matcher(text);
        if (call != null && call.matches()) {
            Tracked tr = set(name, State.ALIVE);
            if (tr != null) tr.calledBy = call.group("player").trim();
            return;
        }
        if (!t.idle()) set(name, State.ALIVE); // any other line of a world boss: it is up
    }

    private synchronized void onDefeated(MatchResult m) {
        String name = m.group(1).trim();
        Boss boss = TelosData.BOSSES.get().realm(name);
        if (boss == null || boss.tracker() == null) return;
        openPortal(name, boss);
        if (m.group(2) == null) return;
        progress = Integer.parseInt(m.group(2));
        progressMax = Integer.parseInt(m.group(3));
        if (progress < progressMax) return;
        for (Boss b : TelosData.BOSSES.get().tracked()) {
            if (b.tracker().progress()) openPortal(b.name(), b);
        }
    }

    private void openPortal(String name, Boss boss) {
        Tracked t = set(name, State.PORTAL);
        if (t == null) return;
        t.portalUntil = System.currentTimeMillis() + boss.tracker().portalSeconds() * 1000L;
    }

    private synchronized void onSpawned(MatchResult m) {
        String name = m.group(1).trim();
        Tracked t = set(name, State.ALIVE);
        if (t == null) return;
        t.calledBy = null;
        if (t.spot == null) { // the line names the spot: use it when the table has none
            try {
                t.spot = new Spot(name, (int) Math.floor(Double.parseDouble(m.group(2))),
                        (int) Math.floor(Double.parseDouble(m.group(3))), (int) Math.floor(Double.parseDouble(m.group(4))));
            } catch (NumberFormatException ignored) {
                // keep no spot
            }
        }
    }

    // ------------------------------------------------------------------ state

    private Tracked set(String name, State state) {
        Tracked t = tracked.get(Names.key(name));
        if (t == null) {
            Boss boss = TelosData.BOSSES.get().realm(name);
            if (boss == null || boss.tracker() == null) return null;
            t = add(boss, state);
        }
        t.state = state;
        if (state == State.IDLE || state == State.DEFEATED) t.calledBy = null;
        return t;
    }

    private Tracked add(Boss boss, State state) {
        Spot spot = boss.positions().isEmpty() ? null : boss.positions().getFirst();
        Tracked t = new Tracked(boss, boss.name(), spot, state, false);
        tracked.put(Names.key(boss.name()), t);
        return t;
    }

    private void remove(String name) {
        Tracked t = tracked.get(Names.key(name));
        if (t != null && !(t.boss.tracker() != null && t.boss.tracker().idle())) tracked.remove(Names.key(name));
    }
}
