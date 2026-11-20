package dev.telemetric.module.impl.hud;

import dev.telemetric.chat.ChatBus;
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
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.tracking.LootTracker;
import dev.telemetric.tracking.PityScopes;
import dev.telemetric.tracking.TrackingStore;
import dev.telemetric.ui.notification.Toasts;

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
 * Pity Counter: kills since each drop of the boss you're at. That's the dungeon you're in, or else the realm boss found
 * by (1) its boss bar or (2) one of its spawn spots ({@code Boss.json} positions) within Boss Range. If no boss is
 * found, the HUD hides. The counting happens in {@link LootTracker} (always on). This module shows it and writes the
 * chat lines: your drop with its pity (plus a share button), and a pity summary after each kill leaderboard.
 */
public final class PityCounterHud extends LinesHudModule {

    /** Goes after the name of a drop that comes from a named mob instead of the boss (the wiki's footnote mark). */
    static final String MOB_MARK = "**";
    private static final long CONTEXT_MS = 500, RESET_CONFIRM_MS = 4000;

    private final NumberSetting bossRange = add(new NumberSetting("Boss Range",
            "Show a realm boss only while one of its spawn spots is this close (blocks); "
                    + "without a boss bar or spot in range the HUD hides", 75, 10, 200, 5));
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
    private final ActionSetting resetSeasonal = add(new ActionSetting("Reset Seasonal Pity",
            "New season: set every seasonal pity back to 0 (click twice to confirm)", this::resetSeasonal));


    private record Context(String title, List<Drop> drops) {}

    private volatile Context context = new Context(null, List.of());
    private long resetArmedAt;
    private long contextAt;
    private int lastContribution;

    public PityCounterHud() {
        super("Pity Counter", "Kills since each drop of the boss you are at (dungeon, boss bar or boss spawn); hidden without one",
                0.0, 0.20, false);
        addUseServerColor();
        LootTracker.get().addListener(new LootTracker.Listener() {
            @Override
            public void onKill(BossLoot loot, int contributionBoost) {
                lastContribution = contributionBoost;
            }

            @Override
            public void onDrop(Drop drop, int pityAt, String from) {
                if (isEnabled() && dropMessage.get()) postDrop(drop, pityAt, from);
            }
        });
        ChatBus.replace(LootTracker.DROP_LINE, m -> isEnabled() && dropMessage.get() && LootTracker.get().ownDrop(m) != null);
    }

    /** The first click arms it, and a second one within {@link #RESET_CONFIRM_MS} clears the seasonal pity pool. */
    private void resetSeasonal() {
        long now = System.currentTimeMillis();
        if (now - resetArmedAt > RESET_CONFIRM_MS) {
            resetArmedAt = now;
            Toasts.info("Click again to reset all seasonal pity");
            return;
        }
        resetArmedAt = 0;
        int n = LootTracker.get().store().clearPityScope(PityScopes.SEASONAL);
        Toasts.success(n == 0 ? "Seasonal pity was already empty" : "Seasonal pity reset (" + n + " counters)");
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
        context = findContext();
    }

    private Context findContext() {
        String dim = DungeonAdapter.dimensionId();
        if (dim == null || dim.startsWith("telos:hub")) return new Context(null, List.of());
        LootTable loot = TelosData.LOOT.get();
        if (DungeonAdapter.isInDungeon()) {
            String place = DungeonAdapter.dungeonName();
            List<BossLoot> bosses = loot.dungeon(place);
            return bosses.isEmpty() ? new Context(null, List.of()) : new Context(place, merged(bosses));
        }
        double range = bossRange.get();
        BossLoot bl = nearestPlate(loot, range); // 1. the nearest boss nameplate with a loot pool (minibosses have no bar)
        if (bl == null) {
            for (String boss : BossBarAdapter.knownBosses()) { // 2. boss bar
                bl = loot.realm(boss);
                if (bl != null && !merged(List.of(bl)).isEmpty()) break;
                bl = null;
            }
        }
        if (bl != null) return new Context(bl.boss(), merged(List.of(bl)));
        Context area = areaContext(loot); // 3. the HUD location box, giving that area's bosses (Boss.json "areas")
        if (area != null) return area;
        bl = realmLoot(loot, nearestSpawn(range)); // 4. a spawn spot from the data
        return bl == null ? new Context(null, List.of()) : new Context(bl.boss(), merged(List.of(bl)));
    }

    /** The realm boss of the nearest nameplate within {@code range} whose boss has pity drops, or null. */
    private static BossLoot nearestPlate(LootTable loot, double range) {
        for (DungeonAdapter.NearPlate p : DungeonAdapter.namePlatesNear(range)) {
            BossLoot bl = loot.realm(p.letters());
            if (bl != null && !merged(List.of(bl)).isEmpty()) return bl;
        }
        return null;
    }

    /** Pity of the realm bosses listed for the HUD box location ({@code areas} in Boss.json), or null. */
    private static Context areaContext(LootTable loot) {
        BossTable table = TelosData.BOSSES.get();
        String place = DungeonAdapter.hudLocation();
        List<BossTable.Boss> inArea = table.inArea(place);
        if (inArea.isEmpty()) inArea = table.inArea(place = PlayerAdapter.locationName()); // part-specific or biome name
        List<BossLoot> bosses = new ArrayList<>();
        for (BossTable.Boss b : inArea) {
            BossLoot bl = loot.realm(b.name());
            if (bl != null) bosses.add(bl);
        }
        List<Drop> drops = merged(bosses);
        if (drops.isEmpty()) return null;
        return new Context(bosses.size() == 1 ? bosses.getFirst().boss() : place, drops);
    }

    private static BossLoot realmLoot(LootTable loot, String boss) {
        return boss == null ? null : loot.realm(boss);
    }

    /** The realm boss with the nearest spawn spot within {@code range} blocks. */
    private static String nearestSpawn(double range) {
        Point me = DungeonAdapter.playerPos();
        if (me == null) return null;
        BossTable table = TelosData.BOSSES.get();
        double best = range * range;
        String name = null;
        for (BossTable.Boss b : table.realmByKey().values()) {
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

    /**
     * The non-shiny pity drops of these bosses, plus the drops of the dungeon's named mobs ({@link Drop#isMobDrop},
     * e.g. Wild Wisps from Dyerftum Overlord), each once, in table order.
     */
    private static List<Drop> merged(List<BossLoot> bosses) {
        Map<String, Drop> out = new LinkedHashMap<>();
        for (BossLoot b : bosses) {
            List<Drop> pity = b.pityDrops();
            for (Drop d : b.drops()) {
                if (!d.shiny() && (pity.contains(d) || d.isMobDrop())) out.putIfAbsent(d.key(), d);
            }
        }
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
            Drop shiny = shinyCounts.get() && LootTracker.shiniesDrop() ? table.shinyOf(d) : null;
            if (shiny == d) shiny = null;
            row(lines, d.name(), d.isMobDrop() ? MOB_MARK : "", d.chance(), store.pity(d.key()), rarityColor(d), icon(d),
                    shiny == null ? null : shiny.chance(), shiny == null ? 0 : store.pity(shiny.key()), shiny == null ? null : icon(shiny));
        }
        return lines;
    }

    /**
     * One item's row(s): {@code Name 1%: 52}. With a shiny variant it's either {@code Name 1% (0.03%): 52 (1994)} (the
     * bracket parts in the moving shiny colours) or a second row {@code Shiny Name 0.03%: 1994} all in shiny colours.
     * {@code mark} goes after the name ({@value #MOB_MARK} means dropped by a named mob, not the boss).
     */
    private void row(List<Line> out, String name, String mark, double chance, int pity, int rgb, HudIcon icon,
                     Double shinyChance, int shinyPity, HudIcon shinyIcon) {
        String label = cut(name) + mark + chanceText(" ", chance);
        if (shinyChance == null) {
            out.add(new Line(label, Integer.toString(pity), rgb, 0, List.of(), icon));
            return;
        }
        if (separateShiny.get()) {
            out.add(new Line(label, Integer.toString(pity), rgb, 0, List.of(), icon));
            String shinyLabel = cut("Shiny " + name) + mark + chanceText(" ", shinyChance);
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

    /** {@code prefix + "1%"} / {@code "0.033%"} (2–3 significant digits), or "" when it's off or unknown. */
    private String chanceText(String prefix, double percent) {
        if (!showChance.get() || percent <= 0) return "";
        BigDecimal v = BigDecimal.valueOf(percent).round(new MathContext(percent >= 1 ? 3 : 2, RoundingMode.HALF_UP));
        return prefix + v.stripTrailingZeros().toPlainString() + "%";
    }

    private List<Line> previewLines() {
        List<Line> out = new ArrayList<>();
        row(out, "Blunder Bow", "", 0.6, 42, rarityColor("irradiated"), new HudIcon.Item("telos:material/weapon/bow/ut-blunder"),
                shinyCounts.get() ? 0.012 : null, 1994, new HudIcon.Item("telos:material/weapon/bow/ut-blunder-shiny"));
        row(out, "Slime Archer", "", 2, 15, rarityColor("companion"), new HudIcon.Item("telos:material/pet/skull_cavern"),
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
        return rarityColor(groupKey(d));
    }

    /** The colour and label key: the drop's {@code rarity} if rarities.json knows it (rarity or coloured event bag), otherwise companion or the loot group. */
    private static String groupKey(Drop d) {
        if (!d.rarity().isEmpty() && TelosData.RARITIES.get().any(d.rarity()) != null) return d.rarity();
        return d.isCompanion() ? "companion" : d.lootKey();
    }

    /** ARGB of a rarity or loot group from {@code rarities.json}, 0 for the theme default. */
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

    /** Appends {@code text} in the shiny colours (animated while Misc → Chat Colors is on). */
    private static void shiny(ChatText line, String text, int styles) {
        line.add(text, ShinyColors.START, styles | ChatText.SHINY);
    }

    /** The rarity's chat icon from {@code rarities.json} ({@code glyph}, in the server pack's default font), or "" if none. */
    private static String rarityGlyph(Drop d) {
        TelosData.Rarity r = TelosData.RARITIES.get().any(d.shiny() ? "shiny" : groupKey(d));
        return r == null || r.glyph() == null ? "" : r.glyph();
    }

    /** The loot group label ("Bloodshot", "Companion"). */
    private static String groupLabel(Drop d) {
        String key = groupKey(d);
        TelosData.Rarity r = TelosData.RARITIES.get().any(key);
        if (r != null) return r.label();
        return key.isEmpty() ? "Loot" : Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
