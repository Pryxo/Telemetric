package dev.telemetric.module.impl.hud;

import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Bag;
import dev.telemetric.hud.HudIcon;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.tracking.LootTracker;
import dev.telemetric.tracking.TrackingStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Lifetime Stats: loot bags you got per bag type and your boss kills /
 * runs, all-time for this account ({@link LootTracker} counts them). One row per bag group of
 * {@code tables/rarities.json} bags, so a new bag type is a new row without code.
 */
public final class LifetimeStatsHud extends LinesHudModule {

    private static final String KILLS = "Boss Kills", RUNS = "Runs";

    private final MultiChoiceSetting hidden = add(new MultiChoiceSetting("Hidden Rows",
            "Rows this element does not show", LifetimeStatsHud::rowLabels));

    public LifetimeStatsHud() {
        super("Lifetime Stats", "All-time loot bags per type, boss kills and runs (this account)", 1.0, 0.20, false);
        addUseServerColor();
    }

    @Override
    protected String title() {
        return "Lifetime Stats";
    }

    private record Row(String label, String stat, int rgb, HudIcon icon) {}

    /**
     * The bag's pouch glyph ({@code icon} in {@code rarities.json}); without one the loot bag's own model: the totem
     * path without {@code _totem} ({@code telos:mob/pouch/royal}).
     */
    private static HudIcon bagIcon(Bag b) {
        String glyph = b.icon();
        if (!glyph.isEmpty()) return new HudIcon.Glyph(glyph, null);
        String totem = b.totem();
        if (totem == null || totem.isEmpty()) return null;
        return new HudIcon.Item("telos:" + (totem.endsWith("_totem") ? totem.substring(0, totem.length() - 6) : totem));
    }

    /** Bag groups in table order (a group's label = its bag's, or the group name for shared ones like Event). */
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
            rows.add(new Row(label, LootTracker.BAG_PREFIX + group, rgb, bagIcon(first)));
        }
        rows.add(new Row(KILLS, LootTracker.STAT_KILLS, 0, null));
        rows.add(new Row(RUNS, LootTracker.STAT_RUNS, 0, null));
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
            lines.add(new Line(r.label(), Integer.toString(preview && n == 0 ? example++ * 7 : n), r.rgb(), 0,
                    List.of(), r.icon()));
        }
        return any || preview ? lines : List.of();
    }
}
