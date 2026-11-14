package dev.telemetric.module.impl.hud;

import dev.telemetric.game.ResourceAdapter;
import dev.telemetric.hud.HudIcon;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Icons for Telos Realms Tab-list stats, taken from the server resource pack.
 * <p>
 * Resolution order (see {@link #resolve}):
 * <ol>
 *   <li>the glyph the Tab entry itself shows in front of the label,</li>
 *   <li>the pack's font "shortcut" character for that stat — only while the Telos pack is loaded
 *       (checked via the glyph's texture),</li>
 *   <li>the PNG straight from the server resource pack ({@code telos:textures/...}), for stats without
 *       a font shortcut,</li>
 *   <li>fallback: a copy of that PNG extracted from the pack and bundled with the mod
 *       ({@code assets/telemetric/textures/hud/telos/}) — used only when the server pack isn't loaded.</li>
 * </ol>
 * The server always wins, so pack updates show up without a mod update.
 * Glyph code points come from the pack's {@code minecraft:default}, {@code telos:misc} and
 * {@code telos:material} font files. Fame Boost has no pack icon.
 */
final class TelosIcons {

    private static final String DIR = "textures/hud/telos/";
    private static final String MISC = "telos:misc", MATERIAL = "telos:material";

    /** glyph = font shortcut (null = none), packTexture = the server pack's PNG, fallback = bundled copy of it. */
    private record Spec(HudIcon.Glyph glyph, String packTexture, HudIcon.Texture fallback) {}

    private static final Map<String, Spec> BY_LABEL = Map.ofEntries(
            // General Info
            // server/glory PNGs have transparent padding: draw only the opaque box so they line up with the glyphs
            Map.entry("server", new Spec(null, "telos:textures/icon/misc/boss.png", new HudIcon.Texture(DIR + "server.png", 32, 32, 5, 4, 23, 26))),
            Map.entry("glory", new Spec(null, "telos:textures/material/misc/wiki_economy.png", new HudIcon.Texture(DIR + "glory.png", 16, 16, 2, 1, 12, 14))),
            Map.entry("soul points", glyph(0x15F8D, MATERIAL, "telos:textures/material/misc/soulpoint1.png", "soul_points", 32, 32)),
            Map.entry("guild", glyph(0x15471, null, "telos:textures/icon/leaderboard/guild_1.png", "guild", 32, 32)),
            // Character Info
            Map.entry("fame", png("telos:textures/icon/misc/convert.png", "fame", 32, 32)),
            Map.entry("tier points", png("telos:textures/material/misc/tier_active.png", "tier_points", 16, 16)),
            Map.entry("irradiated fragments", glyph(0x161B2, MATERIAL, "telos:textures/material/fragment/irradiated.png", "fragment_irradiated", 32, 32)),
            Map.entry("gilded fragments", glyph(0x160F0, MATERIAL, "telos:textures/material/fragment/gilded.png", "fragment_gilded", 32, 32)),
            Map.entry("royal fragments", glyph(0x15D41, MATERIAL, "telos:textures/material/fragment/royal.png", "fragment_royal", 32, 32)),
            Map.entry("bloodshot fragments", glyph(0x161B1, MATERIAL, "telos:textures/material/fragment/bloodshot.png", "fragment_bloodshot", 32, 32)),
            // Stats Info (the tooltip stat symbols)
            Map.entry("attack", stat(0x16115, "attack", "attack")),
            Map.entry("speed", stat(0x16114, "speed", "speed")),
            Map.entry("defense", stat(0x16116, "defense", "defense")),
            Map.entry("health", stat(0x16112, "health", "health")),
            Map.entry("vitality", stat(0x1610F, "vitality", "vitality")),
            Map.entry("evasion", stat(0x16113, "evasion", "evasion")),
            Map.entry("critical chance", stat(0x16110, "critchance", "crit_chance")),
            Map.entry("critical damage", stat(0x16070, "damage", "crit_damage")),
            Map.entry("loot boost", png("telos:textures/material/misc/wiki_loot.png", "loot_boost", 16, 16)));

    /** Mastery tier 0..6 → glyph in {@code telos:misc}. */
    private static final int[] MASTERY_GLYPHS = {0x15F10, 0x15F12, 0x15F15, 0x15F18, 0x15F19, 0x15F1B, 0x15F1C};
    /** Stats whose (white) tooltip symbol is tinted with the stat's colour. */
    private static final Set<String> TINTED = Set.of("attack", "speed", "defense", "health", "vitality",
            "evasion", "critical chance"); // critical damage's symbol is already coloured
    /** Class ruleset ("mode") glyphs in {@code telos:misc}: normal, hardcore ironman, seasonal. */
    private static final int RULESET_NORMAL = 0x370, RULESET_HARDCORE = 0x371, RULESET_SEASONAL = 0x372;
    static final String[] CLASSES = {"arcanist", "assassin", "huntress", "knight", "necromancer", "samurai"};
    private static final Pattern DIGIT = Pattern.compile("\\d+");
    private static final Pattern ROMAN = Pattern.compile("\\b(VI|IV|V|III|II|I)\\b");

    private TelosIcons() {}

    /**
     * @param label   stat label as configured (e.g. "Mastery")
     * @param value   plain value text ("" when unknown, e.g. editor preview) — used for Mastery and Class
     * @param fromTab glyph the Tab entry shows, or null
     * @return icon to draw, or null for none
     */
    static HudIcon resolve(String label, String value, HudIcon.Glyph fromTab) {
        String key = label.toLowerCase(Locale.ROOT);
        // the glyph the Tab list shows wins (for Class the caller passes only the mode glyph from the value)
        if (fromTab != null) return fromTab;
        Spec spec = switch (key) {
            case "mastery" -> mastery(value);
            case "class" -> ruleset(value);
            default -> BY_LABEL.get(key);
        };
        if (spec == null) return null;
        if (spec.packTexture() != null && ResourceAdapter.exists(spec.packTexture())) {
            // server pack loaded: its font glyph, or its PNG for stats without one
            return spec.glyph() != null ? spec.glyph() : spec.fallback().withPath(spec.packTexture());
        }
        return spec.fallback();
    }

    private static Spec mastery(String value) {
        int tier = masteryTier(value);
        if (tier < 0) return null;
        return glyph(MASTERY_GLYPHS[tier], MISC, "telos:textures/icon/mastery/" + tier + ".png", "mastery_" + tier, 32, 32);
    }

    /** First number 0..6, or a roman numeral I..VI, in the value; -1 if none. */
    private static int masteryTier(String value) {
        if (value == null) return -1;
        Matcher m = DIGIT.matcher(value);
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group());
                return n >= 0 && n < MASTERY_GLYPHS.length ? n : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        Matcher r = ROMAN.matcher(value.toUpperCase(Locale.ROOT));
        if (!r.find()) return -1;
        return switch (r.group(1)) {
            case "I" -> 1; case "II" -> 2; case "III" -> 3; case "IV" -> 4; case "V" -> 5; case "VI" -> 6;
            default -> -1;
        };
    }

    /** True if this stat's icon is a white symbol that should be tinted with the stat colour. */
    static boolean tinted(String label) {
        return TINTED.contains(label.toLowerCase(Locale.ROOT));
    }

    /** True if {@code g} is a Mastery tier glyph. */
    static boolean isMasteryGlyph(HudIcon.Glyph g) {
        if (g == null || g.text().isEmpty()) return false;
        int cp = g.text().codePointAt(0);
        for (int m : MASTERY_GLYPHS) if (m == cp) return true;
        return false;
    }

    /** True if {@code g} is a class ruleset (mode) glyph. */
    static boolean isRulesetGlyph(HudIcon.Glyph g) {
        if (g == null || g.text().isEmpty()) return false;
        int cp = g.text().codePointAt(0);
        return cp == RULESET_NORMAL || cp == RULESET_HARDCORE || cp == RULESET_SEASONAL;
    }

    /** Class mode icon from the value text (seasonal / hardcore ironman, else normal). */
    private static Spec ruleset(String value) {
        String v = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (v.contains("season")) return glyph(RULESET_SEASONAL, MISC, "telos:textures/icon/ruleset/seasonal.png", "ruleset_seasonal", 32, 32);
        if (v.contains("hardcore") || v.contains("ironman"))
            return glyph(RULESET_HARDCORE, MISC, "telos:textures/icon/ruleset/hardcore_ironman.png", "ruleset_hardcore_ironman", 32, 32);
        return glyph(RULESET_NORMAL, MISC, "telos:textures/icon/ruleset/normal.png", "ruleset_normal", 32, 32);
    }

    private static Spec stat(int cp, String packName, String file) {
        return glyph(cp, null, "telos:textures/glyph/tooltip/symbol/" + packName + ".png", file, 13, 9);
    }

    private static Spec glyph(int cp, String font, String packTexture, String file, int w, int h) {
        return new Spec(new HudIcon.Glyph(Character.toString(cp), font), packTexture, texture(file, w, h));
    }

    private static Spec png(String packTexture, String file, int w, int h) {
        return new Spec(null, packTexture, texture(file, w, h));
    }

    private static HudIcon.Texture texture(String file, int w, int h) {
        return new HudIcon.Texture(DIR + file + ".png", w, h);
    }
}
