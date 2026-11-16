package dev.telemetric.module.impl.hud;

import dev.telemetric.dungeon.PuzzleBoard;
import dev.telemetric.dungeon.rules.ChatCounter;
import dev.telemetric.dungeon.rules.ChatPuzzles;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.ServerInfoAdapter;
import dev.telemetric.game.TelosServer;
import dev.telemetric.hud.LinesHudModule;
import dev.telemetric.module.impl.dungeon.DungeonTracker;
import dev.telemetric.setting.impl.BooleanSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * Dungeon facts: clear mobs killed/total (dungeons with a goal mob rule), the dungeon's chat objective (Resounding
 * Ruins books x/5, {@code DungeonRules#chatCounter}), treasure chests still glowing (only
 * while there are some), players in the dungeon and puzzle rooms ({@code DungeonRules#chatPuzzles}: completed
 * count + one row per room with the player attempting it).
 * Hidden outside dungeons ({@code telos:dungeon/<n>}, {@code telos:neo_eden/<n>}). Acquires {@link DungeonTracker} itself, so it works without the
 * Wayfinder or Dungeon Map.
 */
public final class DungeonInfoHud extends LinesHudModule {

    private final BooleanSetting showGoal = add(new BooleanSetting("Show Clear Mobs",
            "Clear mobs killed / total (only in dungeons that have them, e.g. Midnight Stronghold)", true));
    private final BooleanSetting showObjective = add(new BooleanSetting("Show Objective",
            "Dungeon objective from server messages (e.g. Books 2/5 in Resounding Ruins, puzzle rooms in Dreadwood Thicket)", true));
    private final BooleanSetting showTreasure = add(new BooleanSetting("Show Treasure Chests",
            "Treasure chests still glowing in the dungeon (row hidden when there are none)", true));
    private final BooleanSetting showPlayers = add(new BooleanSetting("Show Players",
            "Players in the dungeon (yourself included)", true));
    private final BooleanSetting useServerColor = addUseServerColor();

    private boolean acquired;

    public DungeonInfoHud() {
        super("Dungeon Info", "Clear mobs, objective, treasure chests and players of the current dungeon", 0.0, 0.45, false);
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
        String id = dungeonId(DungeonAdapter.dimensionId(),
                ServerInfoAdapter.tabStat("server").map(ServerInfoAdapter.TabStat::value).orElse(null));
        if (id != null) rows.add(new Line("Dungeon ID", id));
        if (showGoal.get() && tracker.hasGoal()) {
            rows.add(new Line("Clear Mobs", tracker.goalKilled() + "/" + tracker.goalTotal()));
        }
        ChatCounter counter = tracker.counter();
        if (showObjective.get() && counter != null) {
            rows.add(new Line(counter.label(), tracker.counterValue() + "/"
                    + (tracker.counterTotal() > 0 ? Integer.toString(tracker.counterTotal()) : "???")));
        }
        int chests = tracker.treasures().size();
        if (showTreasure.get() && chests > 0) rows.add(new Line("Treasure Chests", Integer.toString(chests)));
        if (showPlayers.get()) rows.add(new Line("Players", Integer.toString(DungeonAdapter.players().size())));
        // puzzle rooms (Dreadwood Thicket): dungeon-specific info, so on/off with Show Objective
        PuzzleBoard puzzles = tracker.puzzles();
        ChatPuzzles puzzleRule = tracker.puzzleRule();
        if (showObjective.get() && puzzles != null && puzzleRule != null) {
            for (PuzzleBoard.Puzzle p : puzzles.puzzles()) {
                String value = p.completed() ? "Completed" : p.player() != null ? p.player() : "";
                rows.add(new Line(p.room() != null ? p.room() : "???", value));
            }
        }
        // in a dungeon the panel always shows something (all rows off / no chests → player count)
        if (rows.isEmpty()) rows.add(new Line("Players", Integer.toString(DungeonAdapter.players().size())));
        return rows;
    }

    /**
     * Region (first entry of the Tab "Server" stat, "[test, realm-1]" → "test") + instance id from the dimension:
     * "telos:dungeon/2" → "2", other "telos:neo_eden/2" → "/neo_eden2" (region + "/"). Null without a dimension.
     */
    static String dungeonId(String dimension, String server) {
        if (dimension == null) return null;
        String path = dimension.substring(dimension.indexOf(':') + 1);
        boolean numbered = path.startsWith("dungeon/");
        String id = numbered ? path.substring("dungeon/".length()) : path.replace("/", "");
        TelosServer parsed = TelosServer.parse(server);
        // "[EU, Astra]" → "EU"; a value without a region → its only part
        String region = parsed == null ? "" : parsed.region().isEmpty() ? parsed.realm() : parsed.region();
        return numbered || region.isEmpty() ? region + id : region + "/" + id;
    }

    private List<Line> placeholder() {
        List<Line> rows = new ArrayList<>();
        rows.add(new Line("Dungeon ID", "test2"));
        if (showGoal.get()) rows.add(new Line("Clear Mobs", "1/2"));
        if (showObjective.get()) rows.add(new Line("Books", "2/5"));
        if (showTreasure.get()) rows.add(new Line("Treasure Chests", "2"));
        if (showPlayers.get() || rows.isEmpty()) rows.add(new Line("Players", "3"));
        return rows;
    }
}
