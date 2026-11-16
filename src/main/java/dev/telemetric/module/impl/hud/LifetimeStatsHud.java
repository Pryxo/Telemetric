package dev.telemetric.module.impl.hud;

import dev.telemetric.data.TelosData;
import dev.telemetric.data.TelosData.Bag;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.tracking.LootTracker;
import dev.telemetric.tracking.TrackingStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Lifetime Stats (Melinoe's Tracker tab, own implementation): loot bags you got per bag type and your boss kills /
 * runs, all-time for this account ({@link LootTracker} counts them). One row per bag group of
 * {@code Extractor/bags.json}, so a new bag type is a new row without code.
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

    private record Row(String label, String stat, int rgb) {}

    /** Bag groups in table order (a group's label = its bag's, or the group name for shared ones like Event). */
    private static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        TelosData.Bags bags = TelosData.BAGS.get();
        for (String group : bags.groups()) {
            Bag only = null;
            int count = 0;
            for (Bag b : bags.list()) {
                if (!b.group().equals(group)) continue;
                only = b;
                count++;
            }
            String label = count == 1 ? only.label() : Character.toUpperCase(group.charAt(0)) + group.substring(1);
            int rgb = count == 1 && only.rgb() >= 0 ? 0xFF000000 | only.rgb() : 0;
            rows.add(new Row(label, LootTracker.BAG_PREFIX + group, rgb));
        }
        rows.add(new Row(KILLS, LootTracker.STAT_KILLS, 0));
        rows.add(new Row(RUNS, LootTracker.STAT_RUNS, 0));
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
            lines.add(new Line(r.label(), Integer.toString(preview && n == 0 ? example++ * 7 : n), r.rgb(), 0));
        }
        return any || preview ? lines : List.of();
    }
}
