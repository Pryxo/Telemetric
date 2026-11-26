package dev.telemetric.game;

import dev.telemetric.data.TelosData;

import java.util.List;
import java.util.Map;

/**
 * Telos location names, collected in game (HUD location box and biome id per place, 2026-11-14). The HUD box is the
 * main source; this table refines it and is the fallback when the box is missing.
 * <ul>
 *   <li>Multi-part dungeons show the same HUD name for every part ("Rustborn Kingdom"), so the biome id gives the part
 *       ("telos:rustborn_kingdom2" gives "Rustborn Kingdom: Part II").</li>
 *   <li>Some biomes don't match their place ("telos:purgatory" is Depths of Purgatory, "minecraft:warped_forest" is
 *       Resounding Ruins). Biomes shared by several places ("minecraft:the_void") are left out.</li>
 * </ul>
 * The names live in {@code data/tables/locations.json}. Pure data, no Minecraft imports.
 */
public final class TelosLocations {

    private TelosLocations() {}

    /** Biome id to location name ({@code locations.json} → {@code biomes}). */
    private static Map<String, String> byBiome() {
        return TelosData.LOCATIONS.get().biomes();
    }

    /** All known dungeon names ({@code locations.json} → {@code dungeons}), for pickers like the Wayfinder blacklist. */
    public static List<String> dungeons() {
        return TelosData.LOCATIONS.get().dungeons();
    }

    /** The location for a biome id, or null if the biome is unknown or shared by several places. */
    public static String forBiome(String biomeId) {
        return biomeId == null ? null : byBiome().get(biomeId);
    }

    /**
     * The location name: the HUD box name, made specific by the biome when the box only shows a multi-part dungeon's
     * base name. Without a HUD name, the biome's table entry. Null if neither is known.
     */
    public static String resolve(String hudName, String biomeId) {
        String byBiome = forBiome(biomeId);
        if (hudName == null) return byBiome;
        if (byBiome != null && byBiome.length() > hudName.length()
                && byBiome.regionMatches(true, 0, hudName, 0, hudName.length())
                && byBiome.charAt(hudName.length()) == ':') {
            return byBiome;
        }
        return hudName;
    }
}
