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
 * The state of the realm's bosses for the Boss Tracker. It's fed by:
 * <ul>
 *   <li>the {@code /bosses} menu ({@code » [Anubis] «} items, lore "alive" / "defeated" / "not spawned", Raphael's
 *       {@code (n/m)}), read whenever it closes, or automatically on realm entry ({@link #scanMenu});</li>
 *   <li>server lines: {@code [Boss] …} (alive; the boss's {@code call} regex names who's fighting it; {@code spawn} /
 *       {@code defeat} lines of always-present bosses), {@code Boss has been defeated (n/m)} (portal open for
 *       {@code portalSeconds}; at n = m the {@code progress} boss's portal opens), and
 *       {@code Boss has spawned at x, y, z}.</li>
 * </ul>
 * Which bosses exist, where they are, and their lines and timers come from {@code tables/Boss.json}
 * ({@code tracker} data). The menu comes through {@link BossMenuAdapter}. The chat layouts haven't been
 * read live yet ({@code anyRuns}).
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
        /** The player the boss named while fighting them, or null. */
        public String calledBy() { return calledBy; }

        /** Seconds left on the portal (0 if there's none). */
        public int portalSeconds(long now) {
            return state == State.PORTAL ? (int) Math.max(0, (portalUntil - now + 999) / 1000) : 0;
        }
    }

    private static final Pattern MENU_NAME = Pattern.compile("» \\[(.+?)] «");
    private static final Pattern PROGRESS = Pattern.compile("\\((\\d+)/(\\d+)\\)");

    /** {@code [Boss] text}: a boss speaking (no ':', so it can't be a player chat line). */
    static final ChatPattern BOSS_LINE = ChatPattern.builder("Boss line",
            "\\[(?<boss>[A-Za-z' ]{2,30})] (?<text>[^\\n]+)").anyRuns().build();
    /** {@code Boss has been defeated (3/10)}. */
    public static final ChatPattern DEFEATED = ChatPattern.builder("Boss defeated",
            "(?<boss>[A-Za-z' ]{2,30}) has been defeated(?: \\((?<n>\\d+)/(?<total>\\d+)\\))?[^\\n]*").anyRuns().build();
    /** {@code Boss has spawned at x, y, z}. */
    static final ChatPattern SPAWNED = ChatPattern.builder("Boss spawned",
            "(?<boss>[A-Za-z' ]{2,30}) has spawned at (?<x>-?[\\d.]+), (?<y>-?[\\d.]+), (?<z>-?[\\d.]+)[^\\n]*")
            .anyRuns().build();
    /** {@code (1/5) Void Crystals shattered}: a counter boss's own n/m line (its {@code counter} regex says which boss). */
    static final ChatPattern COUNTER = ChatPattern.builder("Boss counter",
            "\\((?<n>\\d+)/(?<max>\\d+)\\) [^\\n]{2,60}").anyRuns().build();

    /** Any server line, checked against the counter bosses' {@code ready} regexes ("A portal to Tenebris has opened…"). */
    static final ChatPattern ANY_LINE = ChatPattern.builder("Boss ready line", "[^\n]{8,160}").anyRuns().build();

    private static final RealmBossTracker INSTANCE = new RealmBossTracker();

    private final Map<String, Tracked> tracked = new LinkedHashMap<>();
    /** n/m of the counter bosses (Cog Sentinel, Tenebris Portal, Defender), by {@link Names#key}. */
    private final Map<String, int[]> counts = new LinkedHashMap<>();
    private int progress, progressMax;
    private long realmJoinedAt;
    private Object world;
    private boolean wasDungeon;
    private boolean started;

    private RealmBossTracker() {}

    public static RealmBossTracker get() {
        return INSTANCE;
    }

    /** Subscribes to the chat lines and menu reads (once). Call {@link #clear()} when a new realm is joined. */
    public void start() {
        if (started) return;
        started = true;
        ChatBus.on(BOSS_LINE, m -> onBossLine(m.group(1).trim(), m.group(2)));
        ChatBus.on(DEFEATED, this::onDefeated);
        ChatBus.on(SPAWNED, this::onSpawned);
        ChatBus.on(COUNTER, m -> onCounter(m.group(0).trim(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        ChatBus.on(ANY_LINE, m -> onReadyLine(m.group(0).trim()));
        BossMenuAdapter.onMenuClosed(this::readMenu);
        TelosData.BOSSES.onChange(t -> clear());
        clear();
    }


    public synchronized List<Tracked> all() {
        return List.copyOf(tracked.values());
    }

    public synchronized Tracked get(String name) {
        return tracked.get(Names.key(name));
    }

    /** World bosses killed in this realm (Raphael's portal opens at max). */
    public int progress() { return progress; }
    public int progressMax() { return progressMax; }

    /** {n, max} of a counter boss ({@code counter} / {@code countFrom} in Boss.json), or null. */
    public synchronized int[] count(String name) {
        int[] c = counts.get(Names.key(name));
        return c == null ? null : c.clone();
    }

    /** When a realm was last joined from somewhere other than a dungeon (0 if not yet). That's when to read /bosses. */
    public long realmJoinedAt() { return realmJoinedAt; }


    /** New realm or hub: forget everything except the always-present bosses and markers. */
    public synchronized void clear() {
        tracked.clear();
        counts.clear();
        progress = 0;
        Boss pb = TelosData.BOSSES.get().progressBoss();
        progressMax = pb == null ? 0 : pb.tracker().counterMax(); // Boss.json's counterMax until a line gives it
        for (Boss b : TelosData.BOSSES.get().tracked()) {
            BossTable.Tracker tr = b.tracker();
            if (tr.counted() && !tr.progress()) counts.put(Names.key(b.name()), new int[] {0, tr.counterMax()});
            if (!b.tracker().idle()) continue;
            add(b, b.tracker().spawn() != null ? State.IDLE : State.ALIVE); // no spawn line means it's always up
            for (Spot w : b.tracker().waypoints()) {
                tracked.put(Names.key(w.name()), new Tracked(b, w.name(), w, State.ALIVE, true));
            }
        }
    }

    /**
     * Runs every client tick (on Telos). A new hub or realm (not coming back from a dungeon) forgets the old realm's
     * bosses, and portals that ran out turn into defeated.
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
            t.state = State.DEFEATED; // progress was reset when the portal opened, and the kills since then stay
        }
    }

    /** Runs {@code /bosses}, reads the list and closes it (only that menu). */
    public void scanMenu() {
        BossMenuAdapter.runCommandAndRead("bosses", RealmBossTracker::isBossMenu, this::readMenu);
    }


    private static boolean isBossMenu(List<MenuItem> items) {
        for (MenuItem i : items) if (MENU_NAME.matcher(i.name()).find()) return true;
        return false;
    }

    private synchronized void readMenu(List<MenuItem> items) {
        if (!isBossMenu(items)) return;
        BossTable.MenuText text = TelosData.BOSSES.get().menu();
        for (MenuItem i : items) {
            Matcher name = MENU_NAME.matcher(i.name());
            Boss pb = TelosData.BOSSES.get().progressBoss();
            if (pb != null && (i.name().contains(pb.name()) || i.lore().contains(pb.name()))) {
                Matcher p = PROGRESS.matcher(i.lore());
                if (p.find()) {
                    progress = Integer.parseInt(p.group(1));
                    progressMax = Integer.parseInt(p.group(2));
                }
                continue;
            }
            if (!name.find()) continue;
            String boss = name.group(1);
            int[] c = counts.get(Names.key(boss));
            if (c == null) { // listed under another name ("Tenebris Portal" → Voided Omnipotent)
                for (Boss b : TelosData.BOSSES.get().tracked()) {
                    if (Names.key(b.tracker().menuName()).equals(Names.key(boss))) c = counts.get(Names.key(b.name()));
                }
            }
            if (c != null) { // "This boss has not spawned - (2/4)", "This portal hasn't activated yet - (1/5)"
                Matcher p = PROGRESS.matcher(i.lore());
                if (p.find()) {
                    c[0] = Integer.parseInt(p.group(1));
                    c[1] = Integer.parseInt(p.group(2));
                } else if (!text.waiting(i.lore())) {
                    c[0] = c[1]; // no count left, so it's up / open
                }
            }
            if (i.lore().contains(text.alive())) set(boss, State.ALIVE);
            else if (i.lore().contains(text.defeated())) set(boss, State.DEFEATED);
            else if (i.lore().contains(text.notSpawned())) remove(boss);
        }
        trackOpenCounters();
    }

    private synchronized void onBossLine(String name, String text) {
        Boss boss = TelosData.BOSSES.get().realm(name);
        if (boss == null || boss.tracker() == null) return;
        BossTable.Tracker t = boss.tracker();
        if (t.defeat() != null && t.defeat().matcher(text).matches()) {
            Tracked tr = set(name, State.IDLE);
            if (tr != null) tr.calledBy = null;
            feed(boss.name());
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
        if (!t.idle()) set(name, State.ALIVE); // any other line from a world boss means it's up
    }

    private synchronized void onDefeated(MatchResult m) {
        String name = m.group(1).trim();
        Boss boss = TelosData.BOSSES.get().realm(name);
        if (boss == null || boss.tracker() == null) return;
        openPortal(name, boss);
        if (boss.tracker().defeat() == null) feed(boss.name()); // if there's a [Boss] defeat line, that one does the counting
        int[] own = counts.get(Names.key(boss.name()));
        if (own != null) own[0] = 0; // a counter boss starts over once it's down
        if (m.group(2) == null) return;
        progress = Integer.parseInt(m.group(2));
        progressMax = Integer.parseInt(m.group(3));
        if (progress < progressMax) return;
        progress = 0; // full: the portal opens and the next round counts from here
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
        if (t.spot == null) { // the line names the spot, so use it if the table has none
            try {
                t.spot = new Spot(name, (int) Math.floor(Double.parseDouble(m.group(2))),
                        (int) Math.floor(Double.parseDouble(m.group(3))), (int) Math.floor(Double.parseDouble(m.group(4))));
            } catch (NumberFormatException ignored) {
                // couldn't parse the spot, so leave it unset
            }
        }
    }

    /** {@code (n/m) <text>}: goes to the counter boss whose {@code counter} regex matches the line. */
    private synchronized void onCounter(String line, int n, int max) {
        for (Boss b : TelosData.BOSSES.get().tracked()) {
            if (b.tracker().counter() == null || !b.tracker().counter().matcher(line).matches()) continue;
            int[] c = counts.computeIfAbsent(Names.key(b.name()), k -> new int[2]);
            c[0] = n;
            c[1] = max;
            trackOpenCounters();
            return;
        }
    }

    /** A counter boss's {@code ready} line (count full) or {@code down} line (cooldown starts). */
    private synchronized void onReadyLine(String line) {
        for (Boss b : TelosData.BOSSES.get().tracked()) {
            if (b.tracker().ready() == null || !b.tracker().ready().matcher(line).matches()) continue;
            int[] c = counts.computeIfAbsent(Names.key(b.name()), k -> new int[] {0, b.tracker().counterMax()});
            c[0] = c[1];
        }
        trackOpenCounters();
        for (Boss b : TelosData.BOSSES.get().tracked()) {
            if (b.tracker().down() == null || !b.tracker().down().matcher(line).matches()) continue;
            openPortal(b.name(), b); // its cooldown, while the counter keeps counting
            int[] own = counts.get(Names.key(b.name()));
            if (own != null) own[0] = 0;
        }
    }

    /**
     * {@code feeder} went down, so every boss that counts it ({@code countFrom}) gets +1, for every defeat including
     * respawns. It's counted from exactly one line per defeat: its {@code [Boss]} defeat line, or "has been defeated"
     * if it doesn't have one.
     */
    private void feed(String feeder) {
        for (Boss b : TelosData.BOSSES.get().tracked()) {
            if (b.tracker().countFrom().stream().noneMatch(f -> Names.key(f).equals(Names.key(feeder)))) continue;
            int[] c = counts.computeIfAbsent(Names.key(b.name()), k -> new int[] {0, b.tracker().counterMax()});
            if (c[0] < c[1]) c[0]++;
        }
    }


    private Tracked set(String name, State state) {
        Tracked t = tracked.get(Names.key(name));
        if (t == null) {
            Boss boss = TelosData.BOSSES.get().realm(name);
            if (boss == null || boss.tracker() == null) boss = TelosData.BOSSES.get().trackedBoss(name); // a dungeon boss
            if (boss == null || boss.tracker() == null) return null;
            t = add(boss, state);
        }
        t.state = state;
        if (state == State.IDLE || state == State.DEFEATED) t.calledBy = null;
        return t;
    }

    /** A counter boss whose count is full ("Open") gets an entry, so its waypoint can show. */
    private void trackOpenCounters() {
        for (Boss b : TelosData.BOSSES.get().tracked()) {
            if (b.tracker().idle() || tracked.containsKey(Names.key(b.name()))) continue;
            int[] c = counts.get(Names.key(b.name()));
            if (c != null && c[1] > 0 && c[0] >= c[1]) add(b, State.IDLE);
        }
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
