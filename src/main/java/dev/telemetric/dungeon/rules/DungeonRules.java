package dev.telemetric.dungeon.rules;

/**
 * Per-dungeon mechanics the analysis switches on by dungeon name ({@link DungeonRulebook}). Every flag is a
 * <b>reusable mechanic</b> implemented once in {@code dungeon/} — a new dungeon that shares a mechanic just turns
 * the flag on in the rulebook, no new code.
 * <p>
 * Pure Java (no Minecraft imports); travels to the worker thread inside {@code RoomLayout.Hint}.
 *
 * @param roomShape        how rooms are cut out of the floor (see {@link RoomShape})
 * @param indoorRoomsOnly  floor with no roof over the room level is scenery, never a room
 *                         ({@code IndoorFloors}): outdoor terrain around the build, ground over outer arcades
 * @param goalMob          name of the dungeon's "clear" mobs (Dungeon Goal), or null: counted killed/total in
 *                         Dungeon Info, their rooms coloured on the Dungeon Map ({@code DungeonTracker#goals()})
 * @param mapped           false = no block scan / room analysis: Wayfinder and Dungeon Map stay off here
 *                         (Dungeon Info still works)
 * @param bossLevel        false = the dungeon has no boss drop / boss level (the goal is its clear mobs): no boss
 *                         anchor, boss air or boss drop, so no drop room on the map and no boss-drop route
 *                         ({@code DungeonAnalysis#compute})
 * @param chatCounter      objective counted from a server message (Resounding Ruins books "(2/5)"), shown in
 *                         Dungeon Info ({@code DungeonTracker#counter()}), or null
 * @param chatPuzzles      puzzle rooms tracked from server messages (Dreadwood Thicket), shown in Dungeon Info
 *                         ({@code DungeonTracker#puzzles()}), or null
 */
public record DungeonRules(RoomShape roomShape, boolean indoorRoomsOnly, String goalMob, boolean mapped,
                           boolean bossLevel, ChatCounter chatCounter,
                           ChatPuzzles chatPuzzles) {

    /** What every dungeon without an entry gets: the piece grid, outdoor floor allowed, no goal mob, mapped, boss drop. */
    public static final DungeonRules DEFAULT = new DungeonRules(RoomShape.GRID, false, null, true, true, null, null);

    /** How room segments are detected. */
    public enum RoomShape {
        /**
         * Same-size pieces on a grid starting at the spawn room ({@code RoomLayout} piece size + wall-line DP).
         * Most Telos dungeons.
         */
        GRID,
        /**
         * Rooms of any size: long halls, wide halls and corridors, each its own rectangle grown from open floor
         * ({@code FreeformRooms}). For dungeons not built from same-size pieces (Dwarven Frostkeep).
         */
        FREEFORM
    }

    public DungeonRules withRoomShape(RoomShape shape) {
        return new DungeonRules(shape, indoorRoomsOnly, goalMob, mapped, bossLevel, chatCounter, chatPuzzles);
    }

    public DungeonRules withIndoorRoomsOnly(boolean on) {
        return new DungeonRules(roomShape, on, goalMob, mapped, bossLevel, chatCounter, chatPuzzles);
    }

    public DungeonRules withGoalMob(String name) {
        return new DungeonRules(roomShape, indoorRoomsOnly, name, mapped, bossLevel, chatCounter, chatPuzzles);
    }

    public DungeonRules withMapped(boolean on) {
        return new DungeonRules(roomShape, indoorRoomsOnly, goalMob, on, bossLevel, chatCounter, chatPuzzles);
    }

    public DungeonRules withBossLevel(boolean on) {
        return new DungeonRules(roomShape, indoorRoomsOnly, goalMob, mapped, on, chatCounter, chatPuzzles);
    }

    public DungeonRules withChatCounter(ChatCounter counter) {
        return new DungeonRules(roomShape, indoorRoomsOnly, goalMob, mapped, bossLevel, counter, chatPuzzles);
    }

    public DungeonRules withChatPuzzles(ChatPuzzles puzzles) {
        return new DungeonRules(roomShape, indoorRoomsOnly, goalMob, mapped, bossLevel, chatCounter, puzzles);
    }

    @Override
    public String toString() {
        return "rooms=" + roomShape + (indoorRoomsOnly ? " indoor-only" : "")
                + (goalMob != null ? " goal=\"" + goalMob + "\"" : "") + (mapped ? "" : " unmapped") + (bossLevel ? "" : " no-boss")
                + (chatCounter != null ? " counter=\"" + chatCounter.label() + "\"" : "")
                + (chatPuzzles != null ? " puzzles=" + chatPuzzles.slots() : "");
    }
}
