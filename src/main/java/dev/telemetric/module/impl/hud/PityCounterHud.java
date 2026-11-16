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
import dev.telemetric.hud.ShinyColors;
import dev.telemetric.hud.TextRun;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.tracking.LootTracker;
import dev.telemetric.tracking.TrackingStore;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
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
    private final BooleanSetting showChance = add(new BooleanSetting("Show Chance",
            "Each item's drop chance per kill (%) after its name", true));
    private final BooleanSetting shinyCounts = add(new BooleanSetting("Shiny Counts",
            "Also show the separate pity of each item's shiny variant (in the shiny colours)", false));
    private final BooleanSetting separateShiny = add(new BooleanSetting("Separate Shiny",
            "Shiny variants as their own rows; off = in brackets on the item's row: Item 1% (0.03%): 52 (1994)", false)
            .visibleWhen(shinyCounts::get));
    private final GroupSetting chat = add(new GroupSetting("Chat", "Drop and pity lines in the chat"));
    private final BooleanSetting dropMessage = add(chat.child(new BooleanSetting("Drop Message",
            "Replace the server's line for your drop with one that shows the pity it came at", true)));
    private final BooleanSetting shareButton = add(chat.child(new BooleanSetting("Share Button",
            "Add a ⧉ to the drop line: click it to put a share text into the chat box", true)
            .visibleWhen(dropMessage::get)));
    private final BooleanSetting icons = add(chat.child(new BooleanSetting("Icons",
            "Rarity icon in front of the drop line and the item's picture before its name (needs the Telos pack)", true)
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
            Drop shiny = shinyCounts.get() ? table.shinyOf(d) : null;
            if (shiny == d) shiny = null;
            row(lines, d.name(), d.chance(), store.pity(d.key()), rarityColor(d), icon(d),
                    shiny == null ? null : shiny.chance(), shiny == null ? 0 : store.pity(shiny.key()), shiny == null ? null : icon(shiny));
        }
        return lines;
    }

    /**
     * One item's row(s): {@code Name 1%: 52}; with a shiny variant either {@code Name 1% (0.03%): 52 (1994)} (bracket
     * parts in the moving shiny colours) or a second row {@code Shiny Name 0.03%: 1994} all in shiny colours.
     */
    private void row(List<Line> out, String name, double chance, int pity, int rgb, HudIcon icon,
                     Double shinyChance, int shinyPity, HudIcon shinyIcon) {
        String label = cut(name) + chanceText(" ", chance);
        if (shinyChance == null) {
            out.add(new Line(label, Integer.toString(pity), rgb, 0, List.of(), icon));
            return;
        }
        if (separateShiny.get()) {
            out.add(new Line(label, Integer.toString(pity), rgb, 0, List.of(), icon));
            String shinyLabel = cut("Shiny " + name) + chanceText(" ", shinyChance);
            List<TextRun> labelRuns = ShinyColors.runs(shinyLabel, 0);
            out.add(new Line(shinyLabel, "", rgb, 0, List.of(), shinyIcon != null ? shinyIcon : icon)
                    .styledRuns(labelRuns, ShinyColors.runs(Integer.toString(shinyPity), shinyLabel.length() + 2)));
            return;
        }
        List<TextRun> labelRuns = new ArrayList<>();
        labelRuns.add(new TextRun(label, 0, null));
        String shinyChanceText = chanceText("", shinyChance);
        if (!shinyChanceText.isEmpty()) labelRuns.addAll(ShinyColors.runs(" (" + shinyChanceText + ")", 0));
        List<TextRun> valueRuns = new ArrayList<>();
        String count = Integer.toString(pity);
        valueRuns.add(new TextRun(count, 0, null));
        valueRuns.addAll(ShinyColors.runs(" (" + shinyPity + ")", shinyChanceText.length() + 3));
        out.add(new Line(label, count, rgb, 0, List.of(), icon).styledRuns(labelRuns, valueRuns));
    }

    /** {@code prefix + "1%"} / {@code "0.033%"} (2–3 significant digits), or "" when off / unknown. */
    private String chanceText(String prefix, double percent) {
        if (!showChance.get() || percent <= 0) return "";
        BigDecimal v = BigDecimal.valueOf(percent).round(new MathContext(percent >= 1 ? 3 : 2, RoundingMode.HALF_UP));
        return prefix + v.stripTrailingZeros().toPlainString() + "%";
    }

    private List<Line> previewLines() {
        List<Line> out = new ArrayList<>();
        row(out, "Blunder Bow", 0.6, 42, rarityColor("irradiated"), new HudIcon.Item("telos:material/weapon/bow/ut-blunder"),
                shinyCounts.get() ? 0.012 : null, 1994, new HudIcon.Item("telos:material/weapon/bow/ut-blunder-shiny"));
        row(out, "Slime Archer", 2, 15, rarityColor("companion"), new HudIcon.Item("telos:material/pet/skull_cavern"),
                null, 0, null);
        return out;
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
        int rgb = drop.shiny() ? ShinyColors.START : rarityColor(drop) & 0xFFFFFF;
        ChatText line = ChatText.of();
        String glyph = icons.get() ? rarityGlyph(drop) : "";
        if (!glyph.isEmpty()) line.glyph(glyph, null).add(" ", ChatText.WHITE);
        if (drop.shiny()) shiny(line, label, ChatText.BOLD);
        else line.add(label, rgb, ChatText.BOLD);
        line.add(" - ", ChatText.DARK_GRAY).add("Dropped ", ChatText.GRAY);
        if (icons.get() && !drop.model().isEmpty()) line.sprite(drop.model(), "").add(" ", ChatText.WHITE);
        if (drop.shiny()) shiny(line, drop.name(), ChatText.UNDERLINED);
        else line.add(drop.name(), rgb, ChatText.UNDERLINED);
        line.add(" at ", ChatText.GRAY).add(Integer.toString(pityAt), ChatText.YELLOW).add(" pity from ", ChatText.GRAY);
        if (drop.shiny()) shiny(line, from, 0);
        else line.add(from, rgb);
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
        ChatText tagText = ChatText.of();
        if (!glyph.isEmpty()) tagText.glyph(glyph, null).add(" ", ChatText.WHITE);
        tagText.add(label + " Drop", rgb, ChatText.BOLD);
        line.tag(new ChatText.Tag(rgb, tagText, label + " Drop"));
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
                if (shiny != null && shiny != d) shiny(hover, " (Shiny: " + store.pity(shiny.key()) + ")", 0);
            }
            line.add(" [" + g.getKey() + "]", rgb).hover(hover);
        }
        ChatAdapter.post(line);
    }

    /** Appends {@code text} in the shiny colours (a still gradient: chat lines are not redrawn per frame). */
    private static void shiny(ChatText line, String text, int styles) {
        int[] rgb = ShinyColors.gradient(text.codePointCount(0, text.length()));
        int i = 0;
        for (int c = 0; c < text.length(); ) {
            int n = Character.charCount(text.codePointAt(c));
            line.add(text.substring(c, c + n), rgb[i++], styles);
            c += n;
        }
    }

    /** The rarity's chat icon from {@code rarities.json} ({@code glyph}, server-pack default font), "" if none. */
    private static String rarityGlyph(Drop d) {
        TelosData.Rarity r = TelosData.RARITIES.get().any(d.shiny() ? "shiny" : d.isCompanion() ? "companion" : d.lootKey());
        return r == null || r.glyph() == null ? "" : r.glyph();
    }

    /** Loot group label ("Bloodshot", "Companion"). */
    private static String groupLabel(Drop d) {
        String key = d.isCompanion() ? "companion" : d.lootKey();
        TelosData.Rarity r = TelosData.RARITIES.get().any(key);
        if (r != null) return r.label();
        return key.isEmpty() ? "Loot" : Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
