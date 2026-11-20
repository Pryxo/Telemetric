package dev.telemetric.module.impl.hud;

import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Bag;
import dev.telemetric.hud.HudIcon;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.hud.ShinyColors;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.tracking.LootTracker;
import dev.telemetric.tracking.TrackingStore;
import dev.telemetric.tracking.WikiStatsScan;

import java.util.ArrayList;
import java.util.List;

/**
 * Lifetime Stats: the loot bags you got per bag type, plus your runs, all-time for this account ({@link LootTracker}
 * does the counting). There's one row per bag group of the {@code tables/rarities.json} bags, so a new bag type is a
 * new row without any code.
 */
public final class LifetimeStatsHud extends LinesHudModule {

    private static final String RUNS = "Runs";

    private final MultiChoiceSetting hidden = add(new MultiChoiceSetting("Hidden Rows",
            "Rows this element does not show", LifetimeStatsHud::rowLabels));
    private final ActionSetting updateFromWiki = add(new ActionSetting("Update from Wiki",
            "Reads your all-time bags and completions from /wiki (Dungeons & Bosses) and replaces the counted ones",
            () -> WikiStatsScan.get().start()));

    public LifetimeStatsHud() {
        super("Lifetime Stats", "All-time loot bags per type and runs (this account)", 1.0, 0.20, false);
        addUseServerColor();
    }

    @Override
    protected String title() {
        return "Lifetime Stats";
    }

    private record Row(String label, String stat, int rgb, HudIcon icon, boolean shiny) {}

    /**
     * The rarity's small glyph ({@code glyph} in {@code rarities.json}, the text-sized one /wiki shows; the {@code icon}
     * pouch glyph is far too big for a row). Without one, the loot bag's own model, one line tall: the totem path
     * without {@code _totem} ({@code telos:mob/pouch/halloween}).
     */
    private static HudIcon bagIcon(Bag b) {
        String glyph = b.glyph();
        if (!glyph.isEmpty()) return new HudIcon.Glyph(glyph, null);
        if (!b.icon().isEmpty()) return new HudIcon.Glyph(b.icon(), null); // event bags use their pouch glyph
        String totem = b.totem();
        if (totem == null || totem.isEmpty()) return null;
        return new HudIcon.Item("telos:" + (totem.endsWith("_totem") ? totem.substring(0, totem.length() - 6) : totem));
    }

    /** Bag groups in table order. A group's label is its bag's label, or the group name for shared ones like Event. */
    private static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        TelosData.Bags bags = TelosData.BAGS.get();
        for (String group : bags.groups()) {
            Bag only = null, first = null;
            int count = 0;
            for (Bag b : bags.list()) {
                if (!b.group().equals(group)) continue;
                if (first == null) first = b;
                only = b;
                count++;
            }
            String label = count == 1 ? only.label() : Character.toUpperCase(group.charAt(0)) + group.substring(1);
            int rgb = count == 1 && only.rgb() >= 0 ? 0xFF000000 | only.rgb() : 0;
            rows.add(new Row(label, LootTracker.BAG_PREFIX + group, rgb, bagIcon(first), group.equals("shiny")));
        }
        rows.add(new Row(RUNS, LootTracker.STAT_RUNS, 0, null, false));
        return rows;
    }

    private static List<String> rowLabels() {
        List<String> out = new ArrayList<>();
        for (Row r : rows()) out.add(r.label());
        return out;
    }

    @Override
    protected List<Line> lines(boolean preview) {
        TrackingStore store = LootTracker.get().store();
        List<Line> lines = new ArrayList<>();
        boolean any = false;
        int example = 3;
        for (Row r : rows()) {
            if (hidden.contains(r.label())) continue;
            int n = store.stat(r.stat());
            any |= n > 0;
            String value = Integer.toString(preview && n == 0 ? example++ * 7 : n);
            Line line = new Line(r.label(), value, r.rgb(), 0, List.of(), r.icon());
            if (r.shiny()) // Telos' shiny animation on the label (the value stays in the normal colour)
                line = line.styledRuns(ShinyColors.runs(r.label(), 0), List.of());
            lines.add(line);
        }
        return any || preview ? lines : List.of();
    }
}
