package dev.telemetric.module.impl.hud;

import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.module.impl.dungeon.DungeonTracker;
import dev.telemetric.setting.impl.BooleanSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * Dungeon facts: clear mobs killed/total (dungeons with a goal mob rule), treasure chests still glowing (only
 * while there are some) and players in the dungeon.
 * Hidden outside {@code telos:dungeon/<n>}. Acquires {@link DungeonTracker} itself, so it works without the
 * Wayfinder or Dungeon Map.
 */
public final class DungeonInfoHud extends LinesHudModule {

    private final BooleanSetting showGoal = add(new BooleanSetting("Show Clear Mobs",
            "Clear mobs killed / total (only in dungeons that have them, e.g. Midnight Stronghold)", true));
    private final BooleanSetting showTreasure = add(new BooleanSetting("Show Treasure Chests",
            "Treasure chests still glowing in the dungeon (row hidden when there are none)", true));
    private final BooleanSetting showPlayers = add(new BooleanSetting("Show Players",
            "Players in the dungeon (yourself included)", true));
    private final BooleanSetting useServerColor = addUseServerColor();

    private boolean acquired;

    public DungeonInfoHud() {
        super("Dungeon Info", "Clear mobs, treasure chests and players of the current dungeon", 0.0, 0.45, false);
    }

    @Override
    protected void onEnable() {
        if (!acquired) {
            acquired = true;
            DungeonTracker.get().acquire();
        }
    }

    @Override
    protected void onDisable() {
        if (acquired) {
            acquired = false;
            DungeonTracker.get().release();
        }
    }

    @Override
    protected String title() {
        return "Dungeon Info";
    }

    @Override
    protected List<Line> lines(boolean preview) {
        DungeonTracker tracker = DungeonTracker.get();
        if (!tracker.isInDungeon()) return preview ? placeholder() : List.of();

        List<Line> rows = new ArrayList<>();
        if (showGoal.get() && tracker.hasGoal()) {
            rows.add(new Line("Clear Mobs", tracker.goalKilled() + "/" + tracker.goalTotal()));
        }
        int chests = tracker.treasures().size();
        if (showTreasure.get() && chests > 0) rows.add(new Line("Treasure Chests", Integer.toString(chests)));
        if (showPlayers.get()) rows.add(new Line("Players", Integer.toString(DungeonAdapter.players().size())));
        // in a dungeon the panel always shows something (all rows off / no chests → player count)
        if (rows.isEmpty()) rows.add(new Line("Players", Integer.toString(DungeonAdapter.players().size())));
        return rows;
    }

    private List<Line> placeholder() {
        List<Line> rows = new ArrayList<>();
        if (showGoal.get()) rows.add(new Line("Clear Mobs", "1/2"));
        if (showTreasure.get()) rows.add(new Line("Treasure Chests", "2"));
        if (showPlayers.get() || rows.isEmpty()) rows.add(new Line("Players", "3"));
        return rows;
    }
}
