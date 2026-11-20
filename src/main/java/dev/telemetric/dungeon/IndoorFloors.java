package dev.telemetric.dungeon;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Mechanic: <b>indoor rooms only</b> ({@link dev.telemetric.dungeon.rules.DungeonRules#indoorRoomsOnly}). A room floor
 * needs a roof over the <i>room level</i>.
 * <p>
 * Being under a ceiling isn't enough. Dwarven Frostkeep has outdoor stone ground at y 98 around the keep (room level
 * y 100, roof y 107–110), with arcades under it (floor y 96, the ground as their ceiling). Those gave us 15+ fake
 * rooms. The test here is that the column must have a block at or above {@code max(floor, roomLevel) + 2}, meaning
 * something over the head room of a player standing on the room level. Outdoor ground and everything under it has
 * nothing up there, while the keep's rooms have their roof.
 */
public final class IndoorFloors {

    /** Lowest block above the stand height that counts as the roof (feet + 2 is the first block over the head room). */
    private static final int ROOF_ABOVE = 2;

    private IndoorFloors() {}

    /**
     * @param floors column key to floor stand ({@link RoomLayout#topFloors}); not modified
     * @param roomY  the room level (spawn feet Y, else the main level); MIN_VALUE means use each column's own floor
     * @return the columns of {@code floors} that have a roof over the room level
     */
    public static Long2IntOpenHashMap roofed(DungeonMap map, Long2IntOpenHashMap floors, int roomY) {
        Long2IntOpenHashMap out = new Long2IntOpenHashMap(floors.size());
        for (var e : floors.long2IntEntrySet()) {
            long c = e.getLongKey();
            int y = e.getIntValue();
            int head = (roomY == Integer.MIN_VALUE ? y : Math.max(y, roomY)) + ROOF_ABOVE;
            if (!map.isSkyExposed(Cell.columnX(c), head, Cell.columnZ(c))) out.put(c, y);
        }
        return out;
    }
}
