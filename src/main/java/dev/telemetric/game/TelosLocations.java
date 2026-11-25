package dev.telemetric.game;

import dev.telemetric.data.TelosData;

import java.util.List;
import java.util.Map;

/**
 * Telos location names, collected in game (HUD location box and biome id per place, 2026-11-14).
 * <p>
 * The HUD box is the main source. This table refines it, and it's the fallback when the box is missing:
 * <ul>
 *   <li>multi-part dungeons show the same HUD name for every part ("Rustborn Kingdom"), so the biome id tells us the
 *       part ("telos:rustborn_kingdom2" → "Rustborn Kingdom: Part II");</li>
 *   <li>some biomes don't match the place ("telos:purgatory" = Depths of Purgatory, "minecraft:warped_forest" =
 *       Resounding Ruins). Biomes shared by several places ("minecraft:the_void": Dawn of Creation and Tenebris) are
 *       left out.</li>
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
     * The location name. That's the HUD box name, made specific by the biome when the box only shows the base name of a
     * multi-part dungeon. Without a HUD name it's the biome's table entry. Null if neither is known.
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
