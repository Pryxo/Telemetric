package dev.telemetric.map;

import dev.telemetric.data.MapTable;
import dev.telemetric.data.TelosData;
import dev.telemetric.dungeon.Point;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;

/** Which Telos Map region you're in right now. */
public final class MapLocator {

    private MapLocator() {}

    /** In a realm world (not the hub or a dungeon). */
    public static boolean inRealm() {
        String dim = DungeonAdapter.dimensionId();
        return dim != null && dim.startsWith("telos:realm");
    }

    /**
     * Your map region (zone from the HUD location, else by coordinates), or null outside a realm or without map data.
     */
    public static MapTable.Region current() {
        if (!inRealm()) return null;
        Point me = DungeonAdapter.playerPos();
        if (me == null) return null;
        MapTable map = TelosData.MAP.get();
        if (map.regions().isEmpty()) return null;
        String zone = TelosData.BOSSES.get().zoneOf(PlayerAdapter.locationName());
        return map.locate(zone, me.x(), me.z());
    }
}
