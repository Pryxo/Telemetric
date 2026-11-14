package dev.telemetric.game;

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
 * Pure data — no Minecraft imports.
 */
public final class TelosLocations {

    private TelosLocations() {}

    /** Biome id → location name. */
    private static final Map<String, String> BY_BIOME = Map.ofEntries(
            Map.entry("telos:hub", "The Nexus"),
            Map.entry("telos:beach", "Beach"),
            Map.entry("telos:permafrost", "Permafrost"),
            Map.entry("telos:neo_eden", "Neo Eden"),
            Map.entry("telos:skull_cavern", "Skull Cavern"),
            Map.entry("telos:thornwood_wargrove", "Thornwood Wargrove"),
            Map.entry("telos:goblin_lair", "Goblin Lair"),
            Map.entry("telos:desert_temple", "Desert Temple"),
            Map.entry("telos:tomb_of_shadows", "Tomb of Shadows"),
            Map.entry("telos:sakura_shrine", "Sakura Shrine"),
            Map.entry("telos:secluded_woodland", "Secluded Woodland"),
            Map.entry("telos:abyss_of_demons", "Abyss of Demons"),
            Map.entry("telos:undead_lair", "Undead Lair"),
            Map.entry("telos:ice_cave", "Ice Cave"),
            Map.entry("telos:dwarven_frostkeep", "Dwarven Frostkeep"),
            Map.entry("telos:treasure_cave", "Treasure Cave"),
            Map.entry("telos:purgatory", "Depths of Purgatory"),
            Map.entry("telos:frozen_ruins", "Frozen Ruins"),
            Map.entry("telos:kobolds_den", "Kobold's Den"),
            Map.entry("telos:corvus_crypt", "Corvus Crypt"),
            Map.entry("telos:omnipotents_citadel", "Omnipotent's Citadel"),
            Map.entry("telos:fungal_cavern", "Fungal Cavern"),
            Map.entry("telos:corsairs_conductorium", "Corsair's Conductorium"),
            Map.entry("telos:midnight_stronghold", "Midnight Stronghold"),
            Map.entry("telos:anubis_lair", "Anubis Lair"),
            Map.entry("telos:tartarus", "Tartarus"),
            Map.entry("telos:raphaels_castle", "Raphael's Castle"),
            Map.entry("telos:raphaels_chamber", "Raphael's Chamber"),
            Map.entry("telos:cultists_hideout", "Cultist's Hideout"),
            Map.entry("telos:illarius_hideout", "Illarius' Hideout"),
            Map.entry("telos:aurora_sanctum", "Aurora Sanctum"),
            Map.entry("telos:aviary", "The Aviary"),
            Map.entry("telos:rustborn_kingdom1", "Rustborn Kingdom: Part I"),
            Map.entry("telos:rustborn_kingdom2", "Rustborn Kingdom: Part II"),
            Map.entry("telos:rustborn_kingdom3", "Rustborn Kingdom: Part III"),
            Map.entry("telos:dreadwood_thicket", "Dreadwood Thicket"),
            Map.entry("minecraft:warped_forest", "Resounding Ruins"),
            Map.entry("telos:celestials_province", "Celestial's Province"),
            Map.entry("telos:seraphs_domain", "Seraph's Domain"));

    /** All known dungeon names (for pickers such as the Wayfinder blacklist). */
    public static final List<String> DUNGEONS = List.of(
            "Abyss of Demons", "Anubis Lair", "Aurora Sanctum", "Celestial's Province", "Corsair's Conductorium",
            "Corvus Crypt", "Cultist's Hideout", "Dawn of Creation", "Depths of Purgatory", "Desert Temple",
            "Dreadwood Thicket", "Dwarven Frostkeep", "Freddy's Pizzeria", "Frozen Ruins", "Fungal Cavern",
            "Goblin Lair", "Ice Cave", "Illarius' Hideout", "Kobold's Den", "Midnight Stronghold",
            "Omnipotent's Citadel", "Onyx's Castle", "Peaks of Purgatory", "Raphael's Castle", "Raphael's Chamber",
            "Resounding Ruins", "Rustborn Kingdom: Part I", "Rustborn Kingdom: Part II", "Rustborn Kingdom: Part III",
            "Sakura Shrine", "Secluded Woodland", "Seraph's Domain", "Shatters", "Skull Cavern", "Tartarus",
            "Tenebris", "The Aviary", "Thornwood Wargrove", "Tomb of Shadows", "Treasure Cave", "Undead Lair");

    /** Location for a biome id, or null when the biome is unknown or shared by several places. */
    public static String forBiome(String biomeId) {
        return biomeId == null ? null : BY_BIOME.get(biomeId);
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
