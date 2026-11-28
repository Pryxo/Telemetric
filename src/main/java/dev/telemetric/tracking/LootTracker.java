package dev.telemetric.tracking;

import dev.telemetric.Telemetric;
import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatPattern;
import dev.telemetric.data.LootTable;
import dev.telemetric.data.LootTable.BossLoot;
import dev.telemetric.data.LootTable.Drop;
import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Bag;
import dev.telemetric.game.DamageNumberAdapter;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.DropAdapter;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.module.ModuleManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pity and loot counting. It's always on while you're on Telos. The Pity Counter, Lifetime Stats and the drop
 * messages only <i>show</i> what gets counted here, so nothing is missed while they're off.
 * <ul>
 *   <li>Boss kill: the kill leaderboard, a multi-line server message ({@code Defeated <boss> in <time>} in
 *       dungeons, just {@code Defeated <boss>} at realm bosses). Every pity drop of that boss
 *       ({@link LootTable#forDefeat}, current dungeon first) gets +1, shiny variants too. {@link #STAT_KILLS} gets +1,
 *       and {@link #STAT_RUNS} +1 when it's the dungeon's final boss or a realm boss.</li>
 *   <li>Bonus chest ({@code Bonus chest has been unlocked}): one more roll of the last killed boss, so +1.</li>
 *   <li>Drop: your glowing loot after a bag opens ({@link DropAdapter}), or the server's
 *       {@code [realm-3] ͱ <you> got <item>[telos:<model>] from <place>} line (the model id picks
 *       the drop, {@code -shiny} included). That drop's pity goes back to 0 and {@link Listener#onDrop} fires once.</li>
 *   <li>Mob kill: a dungeon mob with drops of its own (Boss.json {@code mob}, e.g. Dyerftum Overlord → Wild
 *       Wisps in Illarius' Hideout). If its nameplate (a text display in the Telos plate font) vanishes within
 *       {@value #MOB_KILL_RANGE} blocks of you while the chunk is still loaded, it was killed near you and its drops get
 *       +1 ({@link Listener#onMobKill}). If a plate shows up again right there within {@value #MOB_RESENT_MS} ms, it was
 *       only re-sent and the kill is taken back.</li>
 *   <li>Plate boss kill: a boss with Boss.json {@code "plateKill": true} (no leaderboard on its kill) is followed the
 *       same way (its {@code plate} name, within {@value #BOSS_KILL_RANGE} blocks; realm bosses outside dungeons too)
 *       and its kill counts like a leaderboard kill (contribution boost 0). A leaderboard of it within
 *       {@link #SAME_KILL_MS} isn't counted again, and the other way round. With a {@code killMessage} (text or list) the
 *       plate kill only counts once a server chat line containing it comes within {@value #KILL_MESSAGE_MS} ms before
 *       or after.</li>
 *   <li>Bag: a loot bag's totem pop gives {@code bag:<group>} +1 ({@code tables/rarities.json} bags).</li>
 * </ul>
 * The counters live in {@link TrackingStore} (per account). The chat layouts haven't been read live yet ({@code anyRuns}).
 */
public final class LootTracker {

    public static final String STAT_KILLS = "bossKills", STAT_RUNS = "runs", BAG_PREFIX = "bag:";

    /** Events for the HUD and chat modules (client thread). */
    public interface Listener {
        /** A boss kill was counted. {@code contributionBoost} is your loot boost from damage on the leaderboard (%). */
        default void onKill(BossLoot loot, int contributionBoost) {}

        /** A pity drop came at {@code pityAt} (its counter is 0 now). {@code from} is the dungeon or boss name. */
        default void onDrop(Drop drop, int pityAt, String from) {}

        /** A kill of a named dungeon mob near you was counted, and {@code drops} got +1. */
        default void onMobKill(String mob, List<Drop> drops) {}
    }

    /**
     * The kill leaderboard: several lines, one of which is {@code Defeated <boss> in <time>} (dungeon) or
     * {@code Defeated <boss>} (realm boss, no timer). Player names can be hoverable.
     */
    static final ChatPattern LEADERBOARD = ChatPattern.builder("Kill leaderboard",
                    "(?=[^\\n]*\\n).*?\\bDefeated [^\\n]+.*")
            .anyRuns().allowInteractive().build();
    private static final Pattern DEFEAT_LINE = Pattern.compile("\\bDefeated\\s+(.+?)(?:\\s+in\\s+\\d.*)?\\s*$");
    /** {@code [telos:material/pet/anubis_lair]} right after the item name in the drop line. */
    private static final Pattern MODEL_TAG = Pattern.compile("\\[([a-z0-9_.-]+:[a-z0-9_./-]+)]");
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final String SELF_ARROW = "⬅";

    /**
     * {@code Skull Cavern𕴫 Bonus chest has been unlocked!}, sent when you open the boss's bonus chest with a key. Only a
     * place name (letters and spaces, no {@code :}) and glyphs come before it.
     */
    static final ChatPattern BONUS_CHEST = ChatPattern.builder("Bonus chest unlocked",
            "(?:[A-Za-z' ]{1,40})?[^A-Za-z0-9:\\n]*Bonus chest has been unlocked[^\\n]*").anyRuns().build();

    /**
     * {@code [realm-3] ͱ <player> got <item>[telos:<model>] from <place>}: an optional {@code [server]} tag, then only
     * glyphs and spaces before the name. The item can have a tooltip.
     */
    public static final ChatPattern DROP_LINE = ChatPattern.builder("Drop line",
                    "(?:\\[[^\\]\\n]*]\\s*)?[^A-Za-z0-9\\n]*(?<player>[A-Za-z0-9_]{3,16}) got (?<item>[^\\n]+)")
            .anyRuns().allowInteractive().build();

    /** If the same drop is seen twice (glow scan and chat line) within this time, it counts once. */
    private static final long SAME_DROP_MS = 60_000, BONUS_AFTER_KILL_MS = 30 * 60_000;

    /** A mob nameplate that vanishes within this many blocks (horizontal) of you counts as a kill. */
    static final int MOB_KILL_RANGE = 16;
    /** Same for a {@link BossLoot#plateKill} boss (bigger fights, you're often further away). */
    static final int BOSS_KILL_RANGE = 32;
    /** A plate kill and a leaderboard of the same boss this close together are one kill. */
    private static final long SAME_KILL_MS = 60_000;
    /**
     * A {@link BossLoot#killMessages} line this long before or after the plate kill confirms it (ping, and the server
     * may send the line before or after the plate goes).
     */
    static final long KILL_MESSAGE_MS = 10_000;

    /** Any server chat line, checked against the {@link BossLoot#killMessages} of the plate-kill bosses. */
    static final ChatPattern KILL_MESSAGE = ChatPattern.builder("Boss kill message", "[^\\n]{2,300}").anyRuns().build();
    /** A plate that shows up within {@link #MOB_SAME_SQ} of a kill this soon after it is the same mob (the plate was re-sent). */
    static final long MOB_RESENT_MS = 5_000;
    private static final double MOB_SAME_SQ = 3 * 3, MOB_TELEPORT_SQ = 32 * 32;
    private static final int MOB_SCAN_TICKS = 4;
    /**
     * A damage number this close (horizontally, and {@link #MOB_HIT_DY} vertically) to a followed plate means you hit
     * that mob. Wide, since the plate is a few ticks old and big mobs get their numbers spread around them.
     */
    private static final double MOB_HIT_SQ = 5 * 5, MOB_HIT_DY = 6;

    private static final LootTracker INSTANCE = new LootTracker();

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Long> recentDrops = new HashMap<>();
    private BossLoot lastKill;
    private long lastKillAt;
    /** {@link #lastKill} came from its plate ({@link BossLoot#plateKill}), not its leaderboard. */
    private boolean lastKillByPlate;
    private boolean started;

    /**
     * A named-mob plate being followed: which mob (name key), where it was last seen, and whether a damage number
     * showed up next to it (you hit it). Only hit mobs give pity.
     */
    private record MobPlate(String mob, Point pos, boolean hit) {}

    /**
     * A counted mob kill, kept for {@link #MOB_RESENT_MS} so it can be taken back if the plate comes back. {@code boss}
     * is set for a {@link BossLoot#plateKill} boss ({@code drops} are then its pity drops, {@code run} whether a run was
     * counted too).
     */
    private record MobKill(Point pos, long at, List<Drop> drops, BossLoot boss, boolean run) {}

    /** A plate kill of a boss with {@link BossLoot#killMessages}, waiting up to {@link #KILL_MESSAGE_MS} for the line. */
    private record PendingKill(BossLoot boss, Point pos, long at) {}

    private final List<PendingKill> pendingKills = new ArrayList<>();
    /** When each boss's kill message was last seen (for a line that comes before the plate goes). */
    private final Map<BossLoot, Long> killMessageAt = new HashMap<>();
    private final Map<Integer, MobPlate> mobPlates = new HashMap<>();
    private final List<MobKill> mobKills = new ArrayList<>();
    private String mobPlace;
    private Point mobLastPlayer;
    private int mobTicks;

    private LootTracker() {}

    public static LootTracker get() {
        return INSTANCE;
    }

    /** Subscribes to the chat lines and bag events (once, at start). */
    public void start() {
        if (started) return;
        started = true;
        ChatBus.on(LEADERBOARD, m -> onLeaderboard(m.group()));
        ChatBus.on(BONUS_CHEST, m -> onBonusChest());
        ChatBus.on(DROP_LINE, this::onDropLine);
        ChatBus.on(KILL_MESSAGE, m -> onKillMessage(m.group()));
        DropAdapter.addListener(new DropAdapter.Listener() {
            @Override
            public void bagPopped(String totem) {
                onBag(totem);
            }

            @Override
            public void itemGlowing(String model) {
                Drop d = TelosData.LOOT.get().byModel(model);
                if (d != null) dropped(d);
            }
        });
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** The counters of the account playing now, set to the pity scope of the character you're playing ({@link PityScopes}). */
    public TrackingStore store() {
        TrackingStore s = TrackingStore.of(PlayerAdapter.accountId());
        s.setPityScope(PityScopes.get().scope());
        return s;
    }

    public int pity(Drop drop) {
        return drop == null ? 0 : store().pity(drop.key());
    }


    /** Client tick (on Telos): every few ticks it follows the nameplates of this dungeon's named mobs. */
    public void tick() {
        markHits();
        if (++mobTicks % MOB_SCAN_TICKS != 0) return;
        PityScopes.get().tick(TrackingStore.of(PlayerAdapter.accountId()));
        expireKillMessages();
        String place = DungeonAdapter.isInDungeon() ? DungeonAdapter.dungeonName() : null;
        Map<String, List<Drop>> drops = place == null ? Map.of() : TelosData.LOOT.get().mobDrops(place);
        Map<String, BossLoot> bosses = TelosData.LOOT.get().plateKillBosses(place);
        boolean watch = !drops.isEmpty() || !bosses.isEmpty();
        Point player = DungeonAdapter.playerPos();
        // a new dungeon, no dungeon, or a teleport (death, portal, world change): forget the plates without counting
        boolean jumped = player == null || mobLastPlayer == null
                || player.distanceSq(mobLastPlayer.x(), mobLastPlayer.y(), mobLastPlayer.z()) > MOB_TELEPORT_SQ;
        if (!watch || jumped || !java.util.Objects.equals(place, mobPlace)) {
            mobPlates.clear();
            mobKills.clear();
        }
        mobPlace = place;
        mobLastPlayer = player;
        if (!watch || player == null) return;

        long now = System.currentTimeMillis();
        mobKills.removeIf(k -> now - k.at() > MOB_RESENT_MS);
        Set<Integer> present = new HashSet<>();
        Set<String> names = new java.util.LinkedHashSet<>(drops.keySet());
        names.addAll(bosses.keySet());
        for (String mob : names) {
            for (DungeonAdapter.NamePlate plate : DungeonAdapter.namePlates(mob)) {
                present.add(plate.id());
                MobPlate old = mobPlates.get(plate.id());
                boolean hit = old != null ? old.hit() : undoResent(plate.pos());
                mobPlates.put(plate.id(), new MobPlate(mob, plate.pos(), hit));
            }
        }
        for (Iterator<Map.Entry<Integer, MobPlate>> it = mobPlates.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, MobPlate> e = it.next();
            if (present.contains(e.getKey())) continue;
            it.remove();
            Point p = e.getValue().pos();
            double dx = p.x() - player.x(), dz = p.z() - player.z();
            String key = e.getValue().mob();
            BossLoot boss = bosses.get(key);
            int range = boss != null ? BOSS_KILL_RANGE : MOB_KILL_RANGE;
            if (!DungeonAdapter.isLoaded(p.x(), p.z()) || dx * dx + dz * dz > range * range) continue;
            List<Drop> own = drops.getOrDefault(key, List.of());
            if (own.isEmpty() && boss == null) continue;
            if (!e.getValue().hit()) {
                Telemetric.debug("Pity: named mob at {} died without a damage number near it (not yours, no pity)", p);
                continue;
            }
            if (boss != null) plateBossKill(boss, p, now);
            if (own.isEmpty()) continue;
            TrackingStore store = store();
            for (Drop d : own) if (counts(d)) store.addPity(d.key());
            mobKills.add(new MobKill(p, now, own, null, false));
            String name = own.getFirst().mobName(key);
            Telemetric.debug("Pity: named mob '{}' killed at {}", name, p);
            for (Listener l : listeners) l.onMobKill(name, own);
        }
    }

    /**
     * A {@link BossLoot#plateKill} boss died near you after you hit it: counted like a leaderboard kill, unless a
     * leaderboard for it already counted it just now ({@link #SAME_KILL_MS}).
     */
    private void plateBossKill(BossLoot boss, Point p, long now) {
        if (boss.killMessages().isEmpty()) {
            confirmBossKill(boss, p, now, true);
            return;
        }
        Long msg = killMessageAt.remove(boss);
        if (msg != null && now - msg <= KILL_MESSAGE_MS) {
            confirmBossKill(boss, p, now, false);
            return;
        }
        pendingKills.removeIf(k -> k.boss() == boss);
        pendingKills.add(new PendingKill(boss, p, now));
        Telemetric.debug("Pity: boss '{}' plate gone after your hit, waiting for its kill message", boss.boss());
    }

    /** A server chat line: confirms a waiting plate kill whose boss's kill message it contains, or is kept for one to come. */
    private void onKillMessage(String text) {
        String line = text.toLowerCase(java.util.Locale.ROOT);
        String decoded = DamageNumberAdapter.textFontText(text).toLowerCase(java.util.Locale.ROOT);
        long now = System.currentTimeMillis();
        for (BossLoot boss : TelosData.LOOT.get().killMessageBosses()) {
            boolean said = false;
            for (String m : boss.killMessages()) if (line.contains(m) || decoded.contains(m)) said = true;
            if (!said) continue;
            PendingKill pending = null;
            for (PendingKill k : pendingKills) if (k.boss() == boss) pending = k;
            if (pending != null) {
                pendingKills.remove(pending);
                confirmBossKill(boss, pending.pos(), now, false);
            } else {
                killMessageAt.put(boss, now);
                Telemetric.debug("Pity: kill message of boss '{}' seen, waiting for its plate", boss.boss());
            }
        }
    }

    /** Drops plate kills that got no kill message in time, and kill messages that got no plate kill. */
    private void expireKillMessages() {
        if (pendingKills.isEmpty() && killMessageAt.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<PendingKill> it = pendingKills.iterator(); it.hasNext(); ) {
            PendingKill k = it.next();
            if (now - k.at() <= KILL_MESSAGE_MS) continue;
            it.remove();
            Telemetric.debug("Pity: boss '{}' plate gone but no kill message came (not counted)", k.boss().boss());
        }
        killMessageAt.values().removeIf(at -> now - at > KILL_MESSAGE_MS);
    }

    /**
     * Counts a plate kill like a leaderboard kill. {@code undoable}: kept as a {@link MobKill} so a re-sent plate takes
     * it back (not after a kill message, which proves the boss is dead).
     */
    private void confirmBossKill(BossLoot boss, Point p, long now, boolean undoable) {
        if (lastKill == boss && now - lastKillAt < SAME_KILL_MS) {
            Telemetric.debug("Pity: boss '{}' plate gone, already counted just now", boss.boss());
            return;
        }
        boolean run = countKill(boss);
        lastKill = boss;
        lastKillAt = now;
        lastKillByPlate = true;
        List<Drop> counted = new ArrayList<>();
        for (Drop d : boss.pityDrops()) if (counts(d)) counted.add(d);
        if (undoable) mobKills.add(new MobKill(p, now, counted, boss, run));
        Telemetric.debug("Pity: boss '{}' killed at {} (plate gone after your hit{})", boss.boss(), p,
                undoable ? "" : ", kill message seen");
        for (Listener l : listeners) l.onKill(boss, 0);
    }

    /**
     * Marks the followed plates that got a damage number next to them this tick. Runs every tick (the damage numbers
     * come as one shared batch per tick, {@link DamageNumberAdapter#thisTick}).
     */
    private void markHits() {
        if (mobPlates.isEmpty()) return;
        for (DamageNumberAdapter.DamageNumber n : DamageNumberAdapter.thisTick(ModuleManager.tickCount())) {
            Map.Entry<Integer, MobPlate> nearest = null;
            double best = MOB_HIT_SQ;
            for (Map.Entry<Integer, MobPlate> e : mobPlates.entrySet()) {
                Point p = e.getValue().pos();
                double dx = p.x() - n.x(), dz = p.z() - n.z(), d = dx * dx + dz * dz;
                if (d > best || Math.abs(p.y() - n.y()) > MOB_HIT_DY) continue;
                best = d;
                nearest = e;
            }
            if (nearest == null || nearest.getValue().hit()) continue;
            MobPlate m = nearest.getValue();
            nearest.setValue(new MobPlate(m.mob(), m.pos(), true));
            Telemetric.debug("Pity: named mob '{}' at {} hit ({})", m.mob(), m.pos(), n.text());
        }
    }

    /**
     * A new plate right where a mob was just counted as killed is the same mob, re-sent, so take the kill back.
     *
     * @return true if a kill was taken back (that mob was hit, so the new plate counts as hit too)
     */
    private boolean undoResent(Point pos) {
        for (Iterator<PendingKill> it = pendingKills.iterator(); it.hasNext(); ) {
            PendingKill k = it.next();
            if (k.pos().distanceSq(pos.x(), pos.y(), pos.z()) > MOB_SAME_SQ) continue;
            it.remove();
            Telemetric.debug("Pity: boss '{}' is still alive (plate back, waiting kill dropped)", k.boss().boss());
            return true;
        }
        for (Iterator<MobKill> it = mobKills.iterator(); it.hasNext(); ) {
            MobKill k = it.next();
            if (k.pos().distanceSq(pos.x(), pos.y(), pos.z()) > MOB_SAME_SQ) continue;
            it.remove();
            TrackingStore store = store();
            for (Drop d : k.drops()) store.removePity(d.key());
            if (k.boss() != null) {
                store.addStat(STAT_KILLS, -1);
                if (k.run()) store.addStat(STAT_RUNS, -1);
                if (lastKill == k.boss()) lastKill = null;
            }
            Telemetric.debug("Pity: named mob at {} is still alive (kill undone)", pos);
            return true;
        }
        return false;
    }


    private void onLeaderboard(String text) {
        String[] lines = text.split("\n");
        String boss = null;
        for (String line : lines) {
            Matcher m = DEFEAT_LINE.matcher(line);
            if (m.find()) {
                boss = m.group(1).trim();
                break;
            }
        }
        if (boss == null) return;
        String place = DungeonAdapter.isInDungeon() ? DungeonAdapter.dungeonName() : null;
        BossLoot loot = TelosData.LOOT.get().forDefeat(boss, place);
        if (loot == null) {
            Telemetric.debug("Pity: no loot table for boss '{}' (place {})", boss, place);
            return;
        }
        long now = System.currentTimeMillis();
        if (lastKill == loot && lastKillByPlate && now - lastKillAt < SAME_KILL_MS) {
            Telemetric.debug("Pity: leaderboard for '{}', already counted by its plate", boss);
            return;
        }
        countKill(loot);
        lastKill = loot;
        lastKillAt = now;
        lastKillByPlate = false;
        int boost = contributionBoost(lines);
        for (Listener l : listeners) l.onKill(loot, boost);
    }

    /** @return true if a run was counted too */
    private boolean countKill(BossLoot loot) {
        TrackingStore store = store();
        for (Drop d : loot.pityDrops()) if (counts(d)) store.addPity(d.key());
        store.addStat(STAT_KILLS, 1);
        List<BossLoot> bosses = loot.isRealm() ? List.of() : TelosData.LOOT.get().dungeon(loot.dungeon());
        boolean run = loot.isRealm() || (!bosses.isEmpty() && bosses.getLast() == loot);
        if (run) store.addStat(STAT_RUNS, 1);
        return run;
    }

    private void onBonusChest() {
        if (lastKill == null || System.currentTimeMillis() - lastKillAt > BONUS_AFTER_KILL_MS) return;
        TrackingStore store = store();
        for (Drop d : lastKill.pityDrops()) if (counts(d)) store.addPity(d.key());
    }

    /** Your row of the leaderboard (marked with ⬅ or your name): its last percentage. */
    private static int contributionBoost(String[] lines) {
        String me = PlayerAdapter.name();
        String row = null;
        for (String line : lines) if (line.contains(SELF_ARROW)) row = line;
        if (row == null && me != null && !me.isEmpty()) for (String line : lines) if (line.contains(me)) row = line;
        if (row == null) return 0;
        Matcher m = PERCENT.matcher(row);
        String last = null;
        while (m.find()) last = m.group(1);
        try {
            return last == null ? 0 : (int) Double.parseDouble(last);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Your loot boost from the Tab list (%), or 0 if it isn't shown. */
    public static int tabLootBoost() {
        return ServerInfoAdapter.tabStat("Loot Boost").map(s -> {
            String digits = s.value().replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits.substring(0, Math.min(digits.length(), 6)));
        }).orElse(0);
    }


    /** The drop a {@link #DROP_LINE} match is about, if it's yours and in the loot table. Otherwise null. */
    public Drop ownDrop(MatchResult m) {
        String me = PlayerAdapter.name();
        if (me == null || !me.equals(m.group(1))) return null;
        String item = m.group(2);
        LootTable table = TelosData.LOOT.get();
        Matcher tag = MODEL_TAG.matcher(item);
        if (tag.find()) {
            Drop byModel = table.byModel(tag.group(1));
            if (byModel != null) return byModel;
        }
        Drop d = table.startingWith(item);
        if (d == null) return null;
        if (item.toLowerCase(Locale.ROOT).contains("shiny")) {
            Drop shiny = table.shinyOf(d);
            if (shiny != null) d = shiny;
        }
        return d;
    }

    /**
     * False on Normal. Normal characters can't drop shinies, so shiny pity isn't shown or counted there. Only a mode
     * that was actually read from the Tab list counts, so an unknown mode glyph never hides shinies.
     */
    public static boolean shiniesDrop() {
        PityScopes s = PityScopes.get();
        return !(s.modeKnown() && PityScopes.NORMAL.equals(s.scope()));
    }

    /** Whether {@code d}'s pity is counted in the current mode (no shinies on Normal). */
    private static boolean counts(Drop d) {
        return !d.shiny() || shiniesDrop();
    }

    private void onDropLine(MatchResult m) {
        Drop d = ownDrop(m);
        if (d != null) dropped(d);
    }

    private void dropped(Drop drop) {
        long now = System.currentTimeMillis();
        recentDrops.values().removeIf(t -> now - t > SAME_DROP_MS);
        if (recentDrops.putIfAbsent(drop.key(), now) != null) return; // seen by both the glow scan and the chat line, so count it once
        if (!counts(drop)) return;
        int at = store().resetPity(drop.key());
        String place = DungeonAdapter.isInDungeon() ? DungeonAdapter.dungeonName() : null;
        BossLoot owner = TelosData.LOOT.get().ownerOf(drop, place);
        String from = place != null ? place : owner != null ? owner.boss() : "?";
        Telemetric.debug("Pity: {} dropped at {} (from {})", drop.name(), at, from);
        for (Listener l : listeners) l.onDrop(drop, at, from);
    }


    private void onBag(String totem) {
        if (totem == null) return;
        Bag bag = TelosData.BAGS.get().byTotem().get(totem);
        if (bag == null) {
            if (totem.contains("pouch")) Telemetric.debug("Unknown loot bag '{}' (add it to tables/rarities.json: bag / eventBags)", totem);
            return;
        }
        store().addStat(BAG_PREFIX + bag.group(), 1);
    }
}
