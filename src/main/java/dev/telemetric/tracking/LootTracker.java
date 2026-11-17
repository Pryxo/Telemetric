package dev.telemetric.tracking;

import dev.telemetric.Telemetric;
import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatPattern;
import dev.telemetric.data.LootTable;
import dev.telemetric.data.LootTable.BossLoot;
import dev.telemetric.data.LootTable.Drop;
import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Bag;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.DropAdapter;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerInfoAdapter;

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
 * Pity and loot counting. Always on while on Telos — the Pity Counter,
 * Lifetime Stats and drop messages only <i>show</i> what is counted here, so nothing is missed while they are off.
 * <ul>
 *   <li><b>Boss kill</b> — the kill leaderboard (multi-line server message; {@code Defeated <boss> in <time>} in
 *       dungeons, just {@code Defeated <boss>} at realm bosses — read live 2026-11-16):
 *       every pity drop of that boss ({@link LootTable#forDefeat}, current dungeon first) gets +1, shiny variants
 *       too; {@link #STAT_KILLS} +1, {@link #STAT_RUNS} +1 when it is the dungeon's final boss or a realm boss.</li>
 *   <li><b>Bonus chest</b> ({@code Bonus chest has been unlocked}) — one more roll of the last killed boss: +1.</li>
 *   <li><b>Drop</b> — your glowing loot after a bag opens ({@link DropAdapter}) or the server's
 *       {@code [realm-3] ͱ <you> got <item>[telos:<model>] from <place>} line (read live 2026-11-16; the model id
 *       picks the drop, {@code -shiny} included): that drop's pity goes back to 0 and {@link Listener#onDrop} fires once.</li>
 *   <li><b>Mob kill</b> — a dungeon mob that has drops of its own (Boss.json {@code mob}, e.g. Dyerftum Overlord →
 *       Wild Wisps in Illarius' Hideout): its nameplate (a text display in the Telos plate font) vanishing within
 *       {@value #MOB_KILL_RANGE} blocks of you, chunk still loaded = killed near you: its drops get +1
 *       ({@link Listener#onMobKill}). A plate that shows up again right there within {@value #MOB_RESENT_MS} ms was
 *       only re-sent: the kill is taken back.</li>
 *   <li><b>Bag</b> — a loot bag's totem pop: {@code bag:<group>} +1 ({@code tables/rarities.json} bags).</li>
 * </ul>
 * Counters live in {@link TrackingStore} (per account). Chat layouts were not read live yet ({@code anyRuns}).
 */
public final class LootTracker {

    public static final String STAT_KILLS = "bossKills", STAT_RUNS = "runs", BAG_PREFIX = "bag:";

    /** Events for the HUD / chat modules (client thread). */
    public interface Listener {
        /** A boss kill was counted. {@code contributionBoost} = your loot boost from damage on the leaderboard (%). */
        default void onKill(BossLoot loot, int contributionBoost) {}

        /** A pity drop came at {@code pityAt} (its counter is 0 now); {@code from} = dungeon or boss name. */
        default void onDrop(Drop drop, int pityAt, String from) {}

        /** A kill of a named dungeon mob near you was counted: {@code drops} got +1. */
        default void onMobKill(String mob, List<Drop> drops) {}
    }

    /**
     * Kill leaderboard: several lines, one of them {@code Defeated <boss> in <time>} (dungeon) or {@code Defeated <boss>}
     * (realm boss, no timer). Player names may be hoverable.
     */
    static final ChatPattern LEADERBOARD = ChatPattern.builder("Kill leaderboard",
                    "(?=[^\\n]*\\n).*?\\bDefeated [^\\n]+.*")
            .anyRuns().allowInteractive().build();
    private static final Pattern DEFEAT_LINE = Pattern.compile("\\bDefeated\\s+(.+?)(?:\\s+in\\s+\\d.*)?\\s*$");
    /** {@code [telos:material/pet/anubis_lair]} right after the item name in the drop line. */
    private static final Pattern MODEL_TAG = Pattern.compile("\\[([a-z0-9_.-]+:[a-z0-9_./-]+)]");
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final String SELF_ARROW = "⬅";

    /** {@code Bonus chest has been unlocked!} — no letters before it (so no player name / rank prefix). */
    static final ChatPattern BONUS_CHEST = ChatPattern.builder("Bonus chest unlocked",
            "[^A-Za-z0-9\\n]*Bonus chest has been unlocked[^\\n]*").anyRuns().build();

    /**
     * {@code [realm-3] ͱ <player> got <item>[telos:<model>] from <place>} — an optional {@code [server]} tag, then only
     * glyphs / spaces before the name; the item can have a tooltip.
     */
    public static final ChatPattern DROP_LINE = ChatPattern.builder("Drop line",
                    "(?:\\[[^\\]\\n]*]\\s*)?[^A-Za-z0-9\\n]*(?<player>[A-Za-z0-9_]{3,16}) got (?<item>[^\\n]+)")
            .anyRuns().allowInteractive().build();

    /** The same drop seen twice (glow scan + chat line) within this time counts once. */
    private static final long SAME_DROP_MS = 60_000, BONUS_AFTER_KILL_MS = 10 * 60_000;

    /** A mob nameplate vanishing within this many blocks (horizontal) of you counts as a kill. */
    static final int MOB_KILL_RANGE = 16;
    /** A plate showing up within {@link #MOB_SAME_SQ} of a kill this soon after it = the same mob (plate re-sent). */
    static final long MOB_RESENT_MS = 5_000;
    private static final double MOB_SAME_SQ = 3 * 3, MOB_TELEPORT_SQ = 32 * 32;
    private static final int MOB_SCAN_TICKS = 4;

    private static final LootTracker INSTANCE = new LootTracker();

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Long> recentDrops = new HashMap<>();
    private BossLoot lastKill;
    private long lastKillAt;
    private boolean started;

    /** A named-mob plate being followed: which mob (name key), where it was last seen. */
    private record MobPlate(String mob, Point pos) {}

    /** A counted mob kill, kept {@link #MOB_RESENT_MS} to take it back if the plate comes back. */
    private record MobKill(Point pos, long at, List<Drop> drops) {}

    private final Map<Integer, MobPlate> mobPlates = new HashMap<>();
    private final List<MobKill> mobKills = new ArrayList<>();
    private String mobPlace;
    private Point mobLastPlayer;
    private int mobTicks;

    private LootTracker() {}

    public static LootTracker get() {
        return INSTANCE;
    }

    /** Subscribes the chat lines and bag events (once, at start). */
    public void start() {
        if (started) return;
        started = true;
        ChatBus.on(LEADERBOARD, m -> onLeaderboard(m.group()));
        ChatBus.on(BONUS_CHEST, m -> onBonusChest());
        ChatBus.on(DROP_LINE, this::onDropLine);
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

    /** Counters of the account playing now, set to the pity scope of the character played now ({@link PityScopes}). */
    public TrackingStore store() {
        TrackingStore s = TrackingStore.of(PlayerAdapter.accountId());
        s.setPityScope(PityScopes.get().scope());
        return s;
    }

    public int pity(Drop drop) {
        return drop == null ? 0 : store().pity(drop.key());
    }

    // ------------------------------------------------------------------ named-mob kills

    /** Client tick (on Telos): follows the nameplates of this dungeon's named mobs every few ticks. */
    public void tick() {
        if (++mobTicks % MOB_SCAN_TICKS != 0) return;
        PityScopes.get().tick(TrackingStore.of(PlayerAdapter.accountId()));
        String place = DungeonAdapter.isInDungeon() ? DungeonAdapter.dungeonName() : null;
        Map<String, List<Drop>> drops = place == null ? Map.of() : TelosData.LOOT.get().mobDrops(place);
        Point player = DungeonAdapter.playerPos();
        // new dungeon / no dungeon / teleport (death, portal, world change): forget the plates without counting
        boolean jumped = player == null || mobLastPlayer == null
                || player.distanceSq(mobLastPlayer.x(), mobLastPlayer.y(), mobLastPlayer.z()) > MOB_TELEPORT_SQ;
        if (drops.isEmpty() || jumped || !place.equals(mobPlace)) {
            mobPlates.clear();
            mobKills.clear();
        }
        mobPlace = place;
        mobLastPlayer = player;
        if (drops.isEmpty() || player == null) return;

        long now = System.currentTimeMillis();
        mobKills.removeIf(k -> now - k.at() > MOB_RESENT_MS);
        Set<Integer> present = new HashSet<>();
        for (String mob : drops.keySet()) {
            for (DungeonAdapter.NamePlate plate : DungeonAdapter.namePlates(mob)) {
                present.add(plate.id());
                if (mobPlates.put(plate.id(), new MobPlate(mob, plate.pos())) == null) undoResent(plate.pos());
            }
        }
        for (Iterator<Map.Entry<Integer, MobPlate>> it = mobPlates.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, MobPlate> e = it.next();
            if (present.contains(e.getKey())) continue;
            it.remove();
            Point p = e.getValue().pos();
            double dx = p.x() - player.x(), dz = p.z() - player.z();
            if (!DungeonAdapter.isLoaded(p.x(), p.z()) || dx * dx + dz * dz > MOB_KILL_RANGE * MOB_KILL_RANGE) continue;
            List<Drop> own = drops.get(e.getValue().mob());
            if (own == null || own.isEmpty()) continue;
            TrackingStore store = store();
            for (Drop d : own) store.addPity(d.key());
            mobKills.add(new MobKill(p, now, own));
            String name = own.getFirst().mobName(e.getValue().mob());
            Telemetric.debug("Pity: named mob '{}' killed at {}", name, p);
            for (Listener l : listeners) l.onMobKill(name, own);
        }
    }

    /** A new plate right where a mob was just counted as killed: the same mob, re-sent — take the kill back. */
    private void undoResent(Point pos) {
        for (Iterator<MobKill> it = mobKills.iterator(); it.hasNext(); ) {
            MobKill k = it.next();
            if (k.pos().distanceSq(pos.x(), pos.y(), pos.z()) > MOB_SAME_SQ) continue;
            it.remove();
            TrackingStore store = store();
            for (Drop d : k.drops()) store.removePity(d.key());
            Telemetric.debug("Pity: named mob at {} is still alive (kill undone)", pos);
            return;
        }
    }

    // ------------------------------------------------------------------ kills

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
        countKill(loot);
        lastKill = loot;
        lastKillAt = System.currentTimeMillis();
        int boost = contributionBoost(lines);
        for (Listener l : listeners) l.onKill(loot, boost);
    }

    private void countKill(BossLoot loot) {
        TrackingStore store = store();
        for (Drop d : loot.pityDrops()) store.addPity(d.key());
        store.addStat(STAT_KILLS, 1);
        List<BossLoot> bosses = loot.isRealm() ? List.of() : TelosData.LOOT.get().dungeon(loot.dungeon());
        if (loot.isRealm() || (!bosses.isEmpty() && bosses.getLast() == loot)) store.addStat(STAT_RUNS, 1);
    }

    private void onBonusChest() {
        if (lastKill == null || System.currentTimeMillis() - lastKillAt > BONUS_AFTER_KILL_MS) return;
        TrackingStore store = store();
        for (Drop d : lastKill.pityDrops()) store.addPity(d.key());
    }

    /** Your row of the leaderboard (marked ⬅ or with your name): its last percentage. */
    private static int contributionBoost(String[] lines) {
        String me = PlayerAdapter.name();
        String row = null;
        for (String line : lines) if (line.contains(SELF_ARROW)) row = line;
        if (row == null && me != null) for (String line : lines) if (line.contains(me)) row = line;
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

    /** Your loot boost from the Tab list (%), 0 if not shown. */
    public static int tabLootBoost() {
        return ServerInfoAdapter.tabStat("Loot Boost").map(s -> {
            String digits = s.value().replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits.substring(0, Math.min(digits.length(), 6)));
        }).orElse(0);
    }

    // ------------------------------------------------------------------ drops

    /** The drop a {@link #DROP_LINE} match is about, if it is yours and in the loot table; else null. */
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

    private void onDropLine(MatchResult m) {
        Drop d = ownDrop(m);
        if (d != null) dropped(d);
    }

    private void dropped(Drop drop) {
        long now = System.currentTimeMillis();
        recentDrops.values().removeIf(t -> now - t > SAME_DROP_MS);
        if (recentDrops.putIfAbsent(drop.key(), now) != null) return; // glow scan + chat line: count once
        int at = store().resetPity(drop.key());
        String place = DungeonAdapter.isInDungeon() ? DungeonAdapter.dungeonName() : null;
        BossLoot owner = TelosData.LOOT.get().ownerOf(drop, place);
        String from = place != null ? place : owner != null ? owner.boss() : "?";
        Telemetric.debug("Pity: {} dropped at {} (from {})", drop.name(), at, from);
        for (Listener l : listeners) l.onDrop(drop, at, from);
    }

    // ------------------------------------------------------------------ bags

    private void onBag(String totem) {
        Bag bag = TelosData.BAGS.get().byTotem().get(totem);
        if (bag == null) {
            if (totem.contains("pouch")) Telemetric.debug("Unknown loot bag '{}' (add it to tables/rarities.json: bag / eventBags)", totem);
            return;
        }
        store().addStat(BAG_PREFIX + bag.group(), 1);
    }
}
