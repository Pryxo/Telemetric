package dev.telemetric.data;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code tables/map.json}: the Telos Map regions and their image tiles ({@link TelosData#MAP}). Each region is one
 * separate part of the realm (open realm, Shadowlands, Radiant Isles, Voided Abyss) with its own images. A tile covers
 * the world blocks {@code x1..x2, z1..z2} (inclusive) and is stretched over them. The images live in the data repo
 * ({@code maps/*.png}) and get downloaded on first use ({@link MapImages}).
 *
 * @param regions in file order; the first is the default
 */
public record MapTable(List<Region> regions) {

    static final MapTable EMPTY = new MapTable(List.of());

    /**
     * @param file   repo path of the PNG ({@code maps/main.png})
     * @param sha256 lower-case hex of the PNG, empty to skip the check
     */
    public record Tile(String file, int x1, int z1, int x2, int z2, String sha256) {
        public int width() { return x2 - x1 + 1; }
        public int height() { return z2 - z1 + 1; }

        public boolean contains(double x, double z) {
            return x >= x1 && x < x2 + 1 && z >= z1 && z < z2 + 1;
        }
    }

    /**
     * @param id    stable key ({@code main}, {@code shadowlands}); used in config and pins
     * @param label tab name
     * @param zone  the Boss.json zone of this region ({@code Shadowlands}), empty for the open realm
     */
    public record Region(String id, String label, String zone, List<Tile> tiles) {
        public boolean isOpenRealm() { return zone.isEmpty(); }

        /** Bounding box of every tile: {minX, minZ, maxX + 1, maxZ + 1} (continuous world coordinates), or null. */
        public double[] bounds() {
            if (tiles.isEmpty()) return null;
            double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (Tile t : tiles) {
                minX = Math.min(minX, t.x1);
                minZ = Math.min(minZ, t.z1);
                maxX = Math.max(maxX, t.x2 + 1);
                maxZ = Math.max(maxZ, t.z2 + 1);
            }
            return new double[]{minX, minZ, maxX, maxZ};
        }

        public boolean contains(double x, double z) {
            for (Tile t : tiles) if (t.contains(x, z)) return true;
            return false;
        }
    }

    public Region byId(String id) {
        for (Region r : regions) if (r.id.equals(id)) return r;
        return null;
    }

    /** The region of a Boss.json zone (null or empty = open realm), or null. */
    public Region forZone(String zone) {
        String z = zone == null ? "" : zone;
        for (Region r : regions) if (r.zone.equalsIgnoreCase(z)) return r;
        return null;
    }

    /**
     * The region you're in: the one of your zone if it has one, else the first one whose tiles cover (x, z), else
     * the open realm. Zones share coordinates with the open realm (Shadowlands is below it), so the zone wins.
     */
    public Region locate(String zone, double x, double z) {
        if (zone != null && !zone.isEmpty()) {
            Region r = forZone(zone);
            if (r != null) return r;
        }
        for (Region r : regions) if (r.isOpenRealm() && r.contains(x, z)) return r;
        for (Region r : regions) if (r.contains(x, z)) return r;
        return forZone("");
    }

    static MapTable parse(JsonObject root) {
        List<Region> regions = new ArrayList<>();
        for (JsonObject o : JsonFields.objects(root, "regions")) {
            String id = JsonFields.str(o, "id");
            if (id.isEmpty()) continue;
            List<Tile> tiles = new ArrayList<>();
            for (JsonObject t : JsonFields.objects(o, "tiles")) {
                String file = JsonFields.str(t, "file");
                if (file.isEmpty() || file.contains("..") || file.startsWith("/")) continue;
                int x1 = JsonFields.integer(t, "x1", 0), z1 = JsonFields.integer(t, "z1", 0);
                int x2 = JsonFields.integer(t, "x2", x1), z2 = JsonFields.integer(t, "z2", z1);
                if (x2 < x1 || z2 < z1) continue;
                tiles.add(new Tile(file, x1, z1, x2, z2, JsonFields.str(t, "sha256").toLowerCase(java.util.Locale.ROOT)));
            }
            String label = JsonFields.str(o, "label");
            regions.add(new Region(id, label.isEmpty() ? id : label, JsonFields.str(o, "zone"), List.copyOf(tiles)));
        }
        return new MapTable(List.copyOf(regions));
    }
}
