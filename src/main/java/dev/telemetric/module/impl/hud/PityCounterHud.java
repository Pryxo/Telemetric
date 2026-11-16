package dev.telemetric.module.impl.hud;

import dev.telemetric.chat.ChatBus;
import dev.telemetric.chat.ChatPattern;
import dev.telemetric.chat.ChatText;
import dev.telemetric.data.BossTable;
import dev.telemetric.data.BossTable.Spot;
import dev.telemetric.data.LootTable;
import dev.telemetric.data.LootTable.BossLoot;
import dev.telemetric.data.LootTable.Drop;
import dev.telemetric.data.TelosData;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.BossBarAdapter;
import dev.telemetric.game.ChatAdapter;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.hud.HudIcon;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.tracking.LootTracker;
import dev.telemetric.tracking.TrackingStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pity Counter (Melinoe's Tracker tab, own implementation): kills since each drop of the boss you are at — the
 * dungeon you are in, the boss whose bar is up, or the nearest realm boss. Counting happens in {@link LootTracker}
 * (always on); this module shows it and writes the chat lines: your drop with its pity (+ share button) and a pity
 * summary after each kill leaderboard.
 */
public final class PityCounterHud extends LinesHudModule {

    /** A realm boss counts as "here" within this many blocks of one of its spawn spots (outside zones). */
    private static final double NEAR_BOSS = 75;
    private static final long CONTEXT_MS = 500, BOSS_BAR_MEMORY_MS = 60_000;

    private final NumberSetting maxChars = add(new NumberSetting("Max Characters",
            "Item names longer than this are cut (0 = no names, counts only)", 15, 0, 30, 1));
    private final BooleanSetting shinyCounts = add(new BooleanSetting("Shiny Counts",
            "Also show the separate pity of each item's shiny variant", false));
    private final GroupSetting chat = add(new GroupSetting("Chat", "Drop and pity lines in the chat"));
    private final BooleanSetting dropMessage = add(chat.child(new BooleanSetting("Drop Message",
            "Replace the server's line for your drop with one that shows the pity it came at", true)));
    private final BooleanSetting shareButton = add(chat.child(new BooleanSetting("Share Button",
            "Add a ⧉ to the drop line: click it to put a share text into the chat box", true)
            .visibleWhen(dropMessage::get)));
    private final BooleanSetting pityLine = add(chat.child(new BooleanSetting("Pity Line",
            "After each boss kill, one line with the boss's pity per loot group (hover for the items)", true)));
    private final BooleanSetting hideBonusChest = add(chat.child(new BooleanSetting("Hide Bonus Chest Messages",
            "Hide the server's bonus chest spawned / unlocked lines (the unlock still counts)", false)));

    private static final ChatPattern BONUS_SPAWNED = ChatPattern.builder("Bonus chest spawned",
            "[^A-Za-z0-9\\n]*Bonus (?:dungeon )?chest has (?:spawned|been unlocked)[^\\n]*").anyRuns().build();

    private record Context(String title, List<Drop> drops) {}

    private volatile Context context = new Context(null, List.of());
    private long contextAt, bossBarAt;
    private String bossBarBoss;
    private int lastContribution;

    public PityCounterHud() {
        super("Pity Counter", "Kills since each drop of the boss you are at (dungeon, boss bar or nearest boss)",
                0.0, 0.20, false);
        addUseServerColor();
        LootTracker.get().addListener(new LootTracker.Listener() {
            @Override
            public void onKill(BossLoot loot, int contributionBoost) {
                lastContribution = contributionBoost;
                if (isEnabled() && pityLine.get()) postPityLine(loot);
            }

            @Override
            public void onDrop(Drop drop, int pityAt, String from) {
                if (isEnabled() && dropMessage.get()) postDrop(drop, pityAt, from);
            }
        });
        ChatBus.hide(LootTracker.DROP_LINE, m -> isEnabled() && dropMessage.get() && LootTracker.get().ownDrop(m) != null);
        ChatBus.hide(BONUS_SPAWNED, m -> isEnabled() && hideBonusChest.get());
    }

    @Override
    protected String title() {
        return context.title();
    }

    // ------------------------------------------------------------------ which boss

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now - contextAt < CONTEXT_MS) return;
        contextAt = now;
        context = findContext(now);
    }

    private Context findContext(long now) {
        String dim = DungeonAdapter.dimensionId();
        if (dim == null || dim.startsWith("telos:hub")) return new Context(null, List.of());
        LootTable loot = TelosData.LOOT.get();
        if (DungeonAdapter.isInDungeon()) {
            String place = DungeonAdapter.dungeonName();
            List<BossLoot> bosses = loot.dungeon(place);
            return bosses.isEmpty() ? new Context(null, List.of()) : new Context(place, merged(bosses));
        }
        for (String boss : BossBarAdapter.knownBosses()) {
            if (loot.realm(boss) != null) {
                bossBarBoss = boss;
                bossBarAt = now;
            }
        }
        String boss = bossBarBoss != null && now - bossBarAt < BOSS_BAR_MEMORY_MS ? bossBarBoss : nearestBoss();
        BossLoot bl = boss == null ? null : loot.realm(boss);
        return bl == null ? new Context(null, List.of()) : new Context(bl.boss(), merged(List.of(bl)));
    }

    /** Nearest realm boss spot: within {@link #NEAR_BOSS} in the open realm, the zone's nearest boss in a zone. */
    private static String nearestBoss() {
        Point me = DungeonAdapter.playerPos();
        if (me == null) return null;
        BossTable table = TelosData.BOSSES.get();
        String zone = table.zoneOf(PlayerAdapter.locationName());
        double best = zone == null ? NEAR_BOSS * NEAR_BOSS : Double.MAX_VALUE;
        String name = null;
        for (BossTable.Boss b : table.realmByKey().values()) {
            if (zone != null && !zone.equals(table.zoneOf(b))) continue;
            for (Spot s : b.positions()) {
                double d = me.distanceSq(s.x() + 0.5, s.y() + 0.5, s.z() + 0.5);
                if (d <= best) {
                    best = d;
                    name = b.name();
                }
            }
        }
        return name;
    }

    /** Non-shiny pity drops of these bosses, each once, in table order. */
    private static List<Drop> merged(List<BossLoot> bosses) {
        Map<String, Drop> out = new LinkedHashMap<>();
        for (BossLoot b : bosses) for (Drop d : b.pityDrops()) if (!d.shiny()) out.putIfAbsent(d.key(), d);
        return List.copyOf(out.values());
    }

    // ------------------------------------------------------------------ HUD rows

    @Override
    protected List<Line> lines(boolean preview) {
        List<Drop> drops = context.drops();
        if (drops.isEmpty()) return preview ? previewLines() : List.of();
        LootTable table = TelosData.LOOT.get();
        TrackingStore store = LootTracker.get().store();
        List<Line> lines = new ArrayList<>(drops.size());
        for (Drop d : drops) {
            String value = Integer.toString(store.pity(d.key()));
            Drop shiny = shinyCounts.get() ? table.shinyOf(d) : null;
            if (shiny != null) value += " (" + store.pity(shiny.key()) + ")";
            lines.add(new Line(cut(d.name()), value, rarityColor(d), 0, List.of(), icon(d)));
        }
        return lines;
    }

    private List<Line> previewLines() {
        return List.of(
                new Line(cut("Blunder Bow"), "42", rarityColor("irradiated"), 0, List.of(),
                        new HudIcon.Item("telos:material/weapon/bow/ut-blunder")),
                new Line(cut("Slime Archer"), "15", rarityColor("companion"), 0, List.of(),
                        new HudIcon.Item("telos:material/pet/skull_cavern")));
    }

    private String cut(String name) {
        int max = maxChars.getInt();
        if (max <= 0) return "";
        return name.length() <= max ? name : name.substring(0, max).stripTrailing() + "…";
    }

    private static HudIcon icon(Drop d) {
        return d.model().isEmpty() ? null : new HudIcon.Item(d.model());
    }

    private static int rarityColor(Drop d) {
        return rarityColor(d.isCompanion() ? "companion" : d.lootKey());
    }

    /** ARGB of a rarity / loot group from {@code rarities.json}, 0 = theme default. */
    private static int rarityColor(String key) {
        TelosData.Rarity r = TelosData.RARITIES.get().any(key);
        return r == null ? 0 : 0xFF000000 | r.rgb();
    }

    // ------------------------------------------------------------------ chat lines

    private void postDrop(Drop drop, int pityAt, String from) {
        int base = LootTracker.tabLootBoost(), contribution = lastContribution;
        String label = drop.shiny() ? "SHINY" : groupLabel(drop).toUpperCase(Locale.ROOT);
        int rgb = drop.shiny() ? ChatText.AQUA : rarityColor(drop) & 0xFFFFFF;
        ChatText line = ChatText.of().add(label, rgb, ChatText.BOLD).add(" - ", ChatText.DARK_GRAY)
                .add("Dropped ", ChatText.GRAY).add(drop.name(), rgb, ChatText.UNDERLINED)
                .add(" at ", ChatText.GRAY).add(Integer.toString(pityAt), ChatText.YELLOW)
                .add(" pity from ", ChatText.GRAY).add(from, rgb);
        int boost = base + contribution;
        if (boost > 0) {
            line.add(" [+" + boost + "% LB]", ChatText.YELLOW).hover(ChatText.of()
                    .add("Loot Boost\n", ChatText.GRAY).add("Base: +" + base + "%\n", ChatText.YELLOW)
                    .add("Contribution: +" + contribution + "%", ChatText.YELLOW));
        }
        if (shareButton.get()) {
            String share = "[" + (drop.shiny() ? "Shiny" : groupLabel(drop)) + "] Dropped " + drop.name() + " at "
                    + pityAt + " pity from " + from + "!" + (boost > 0 ? " [" + base + "% (+" + contribution + ") LB]" : "");
            line.add(" ⧉", ChatText.GRAY, ChatText.BOLD)
                    .hover(ChatText.of().add("Click to share in chat!", ChatText.GRAY)).suggest(share);
        }
        ChatAdapter.post(line);
    }

    /** "Pity: [Bloodshot] [Companion]" — hover a group for its items and counts. */
    private void postPityLine(BossLoot loot) {
        Map<String, List<Drop>> byGroup = new LinkedHashMap<>();
        for (Drop d : loot.pityDrops()) {
            if (!d.shiny()) byGroup.computeIfAbsent(groupLabel(d), k -> new ArrayList<>()).add(d);
        }
        if (byGroup.isEmpty()) return;
        LootTable table = TelosData.LOOT.get();
        TrackingStore store = LootTracker.get().store();
        ChatText line = ChatText.of().add("Pity", ChatText.GRAY, ChatText.BOLD).add(" " + loot.boss() + ":", ChatText.GRAY);
        for (Map.Entry<String, List<Drop>> g : byGroup.entrySet()) {
            int rgb = rarityColor(g.getValue().getFirst()) & 0xFFFFFF;
            ChatText hover = ChatText.of();
            Set<String> done = new LinkedHashSet<>();
            for (Drop d : g.getValue()) {
                if (!done.add(d.key())) continue;
                if (!hover.isEmpty()) hover.add("\n", ChatText.GRAY);
                hover.add(d.name(), rgb).add(": ", ChatText.GRAY).add(Integer.toString(store.pity(d.key())), ChatText.WHITE);
                Drop shiny = table.shinyOf(d);
                if (shiny != null) hover.add(" (Shiny: " + store.pity(shiny.key()) + ")", ChatText.AQUA);
            }
            line.add(" [" + g.getKey() + "]", rgb).hover(hover);
        }
        ChatAdapter.post(line);
    }

    /** Loot group label ("Bloodshot", "Companion"). */
    private static String groupLabel(Drop d) {
        String key = d.isCompanion() ? "companion" : d.lootKey();
        TelosData.Rarity r = TelosData.RARITIES.get().any(key);
        if (r != null) return r.label();
        return key.isEmpty() ? "Loot" : Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
