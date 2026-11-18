package dev.telemetric.game;

import dev.telemetric.data.TelosData;

import java.util.List;
import java.util.Map;

/**
 * Telos location names, collected in game (HUD location box + biome id per place, 2026-11-14).
 * <p>
 * The HUD box is the primary source; this table refines it and is the fallback when the box is missing:
 * <ul>
 *   <li>multi-part dungeons show one HUD name for every part ("Rustborn Kingdom") — the biome id tells the part
 *       ("telos:rustborn_kingdom2" → "Rustborn Kingdom: Part II");</li>
 *   <li>some biomes don't match the place ("telos:purgatory" = Depths of Purgatory,
 *       "minecraft:warped_forest" = Resounding Ruins). Biomes shared by several places
 *       ("minecraft:the_void": Dawn of Creation and Tenebris) are left out.</li>
 * </ul>
 * The names live in {@code data/tables/locations.json}. Pure data — no Minecraft imports.
 */
public final class TelosLocations {

    private TelosLocations() {}

    /** Biome id → location name ({@code locations.json} → {@code biomes}). */
    private static Map<String, String> byBiome() {
        return TelosData.LOCATIONS.get().biomes();
    }

    /** All known dungeon names ({@code locations.json} → {@code dungeons}; pickers such as the Wayfinder blacklist). */
    public static List<String> dungeons() {
        return TelosData.LOCATIONS.get().dungeons();
    }

    /** Location for a biome id, or null when the biome is unknown or shared by several places. */
    public static String forBiome(String biomeId) {
        return biomeId == null ? null : byBiome().get(biomeId);
    }

    /**
     * The location name: the HUD box name, made specific by the biome when the box only shows the base name of a
     * multi-part dungeon; without a HUD name, the biome's table entry. Null when neither is known.
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
