package dev.telemetric.ui.theme;

import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.ui.render.ColorUtil;

/**
 * The built-in palettes. Add a constant to add a preset, and it shows up in the (searchable) Theme dropdown.
 * The first four are tuned by hand. The rest are derived from a base colour plus an accent pair by {@link #dark} /
 * {@link #light}, so every preset stays consistent.
 */
public enum ThemePreset implements EnumSetting.Nameable {

    // ---- purple (tuned by hand) ----
    MIDNIGHT("Midnight", new Theme(
            0xA0050408,
            0xFF0F0D14, 0xFF1A1426, 0xFF130F1C,
            0xFF14111B, 0xFF1D1828, 0xFF221A33,
            0xFF0B0A10, 0xFF272135, 0xFF2A2238,
            0xFFEDE9F6, 0xFF9A90B0, 0xFF5C5470,
            0xFF8B5CF6, 0xFFC084FC,
            0x80000000)),

    AMETHYST("Amethyst", new Theme(
            0xA00A0610,
            0xFF1A1226, 0xFF2A1B42, 0xFF1F1530,
            0xFF1E152C, 0xFF2A1E3E, 0xFF34244F,
            0xFF150F20, 0xFF35284D, 0xFF3B2B55,
            0xFFF3EEFF, 0xFFB3A3D1, 0xFF6E5F8A,
            0xFFA855F7, 0xFFE879F9,
            0x80000000)),

    OBSIDIAN("Obsidian", new Theme(
            0xB0000000,
            0xFF080808, 0xFF111014, 0xFF0A0A0C,
            0xFF0B0B0D, 0xFF16141A, 0xFF1B1526,
            0xFF050505, 0xFF1E1B24, 0xFF1F1C26,
            0xFFE6E6E6, 0xFF8E8A96, 0xFF55525C,
            0xFF7C3AED, 0xFFA78BFA,
            0x90000000)),

    NEBULA("Nebula", new Theme(
            0xA0060512,
            0xFF110E1F, 0xFF1F1838, 0xFF151127,
            0xFF151127, 0xFF211B3B, 0xFF2A2050,
            0xFF0D0A18, 0xFF2B2548, 0xFF2E2752,
            0xFFEEEBFF, 0xFFA49CCB, 0xFF625A88,
            0xFF9D7BFF, 0xFFF0ABFC,
            0x80000000)),

    // ---- purple / pink ----
    LAVENDER("Lavender", dark(0xFF15121C, 0xFFB794F6, 0xFFE9D5FF)),
    VAMPIRE("Vampire", dark(0xFF1C1D27, 0xFFBD93F9, 0xFFFF79C6)),
    PASTEL("Pastel", dark(0xFF1B1B29, 0xFFCBA6F7, 0xFFF5C2E7)),
    SYNTHWAVE("Synthwave", dark(0xFF140B22, 0xFFE64A98, 0xFF33D4E6)),
    SAKURA("Sakura", dark(0xFF170E15, 0xFFF472B6, 0xFFFBCFE8)),
    ROSE("Rose", dark(0xFF160B10, 0xFFF43F5E, 0xFFFDA4AF)),
    GALAXY("Galaxy", dark(0xFF0D0B1E, 0xFF7C3AED, 0xFF22D3EE)),
    NEON_TOKYO("Neon Tokyo", dark(0xFF0C0A16, 0xFFFF2E88, 0xFF00E5FF)),
    BUBBLEGUM("Bubblegum", dark(0xFF1A0F1A, 0xFFFF5FA2, 0xFFA78BFA)),

    // ---- red / orange / yellow ----
    CRIMSON("Crimson", dark(0xFF140A0C, 0xFFDC2626, 0xFFF87171)),
    BLOOD_MOON("Blood Moon", dark(0xFF110606, 0xFFB91C1C, 0xFFF97316)),
    SUNSET("Sunset", dark(0xFF150D0B, 0xFFF97316, 0xFFEC4899)),
    EMBER("Ember", dark(0xFF130C08, 0xFFEA580C, 0xFFFBBF24)),
    AMBER("Amber", dark(0xFF14100A, 0xFFF59E0B, 0xFFFDE047)),
    GOLD("Gold", dark(0xFF121008, 0xFFEAB308, 0xFFFEF08A)),
    COPPER("Copper", dark(0xFF130E0B, 0xFFC2703D, 0xFFF0B27A)),
    CYBER("Cyber", dark(0xFF0E0E0A, 0xFFFCEE0A, 0xFF00F0FF)),
    INFERNO("Inferno", dark(0xFF140806, 0xFFFF4D1A, 0xFFFFC233)),

    // ---- green ----
    EMERALD("Emerald", dark(0xFF08130E, 0xFF10B981, 0xFF6EE7B7)),
    FOREST("Forest", dark(0xFF0E140C, 0xFF65A30D, 0xFFBEF264)),
    MINT("Mint", dark(0xFF0C1513, 0xFF34D399, 0xFFA7F3D0)),
    MATRIX("Matrix", dark(0xFF050A05, 0xFF22C55E, 0xFF86EFAC)),
    TOXIC("Toxic", dark(0xFF0A1008, 0xFFA3E635, 0xFF22D3EE)),

    // ---- blue / cyan ----
    OCEAN("Ocean", dark(0xFF0B1220, 0xFF3B82F6, 0xFF22D3EE)),
    DEEP_SEA("Deep Sea", dark(0xFF06101A, 0xFF0EA5E9, 0xFF2DD4BF)),
    AQUA("Aqua", dark(0xFF071416, 0xFF06B6D4, 0xFF5EEAD4)),
    ARCTIC("Arctic", dark(0xFF0E1419, 0xFF7DD3FC, 0xFFE0F2FE)),
    POLAR("Polar", dark(0xFF262B35, 0xFF88C0D0, 0xFF81A1C1)),
    SAPPHIRE("Sapphire", dark(0xFF0A0D1C, 0xFF4F46E5, 0xFF818CF8)),
    AURORA("Aurora", dark(0xFF07121A, 0xFF2DD4BF, 0xFFA855F7)),
    HOLO("Holo", dark(0xFF0D1220, 0xFF67E8F9, 0xFFF0ABFC)),

    // ---- neutral ----
    SLATE("Slate", dark(0xFF111318, 0xFF94A3B8, 0xFFCBD5E1)),
    GRAPHITE("Graphite", dark(0xFF0E0E0E, 0xFFE5E5E5, 0xFFA3A3A3)),
    COFFEE("Coffee", dark(0xFF16110E, 0xFFC8A27A, 0xFFE7C9A9)),

    // ---- light ----
    PAPER("Paper", light(0xFFF4F4F7, 0xFF7C3AED, 0xFFC084FC)),
    LATTE("Latte", light(0xFFF4EFE9, 0xFFD97706, 0xFFEA580C)),
    SKY("Sky", light(0xFFF0F5FA, 0xFF2563EB, 0xFF06B6D4)),
    BLOSSOM("Blossom", light(0xFFFBF2F6, 0xFFDB2777, 0xFF9333EA));

    private final String displayName;
    private final Theme theme;

    ThemePreset(String displayName, Theme theme) {
        this.displayName = displayName;
        this.theme = theme;
    }

    public Theme theme() {
        return theme;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    // ------------------------------------------------------------------ derived palettes

    /** A dark palette from a panel colour: surfaces step up in brightness and pick up a hint of the accent. */
    private static Theme dark(int base, int accent, int accentAlt) {
        return new Theme(
                ColorUtil.withAlpha(ColorUtil.shade(base, -0.6), 0xA0),
                ColorUtil.shade(base, -0.12),
                ColorUtil.lerp(ColorUtil.shade(base, 0.04), accent, 0.10),
                ColorUtil.lerp(base, accent, 0.05),
                ColorUtil.shade(base, 0.02),
                ColorUtil.lerp(ColorUtil.shade(base, 0.06), accent, 0.06),
                ColorUtil.lerp(base, accent, 0.15),
                ColorUtil.shade(base, -0.4),
                ColorUtil.lerp(ColorUtil.shade(base, 0.10), accent, 0.08),
                ColorUtil.lerp(ColorUtil.shade(base, 0.12), accent, 0.10),
                ColorUtil.lerp(0xFFF0F0F2, accent, 0.06),
                ColorUtil.lerp(0xFF9A9AA4, accent, 0.14),
                ColorUtil.lerp(0xFF5A5A62, accent, 0.10),
                accent, accentAlt,
                0x80000000);
    }

    /** A light palette from a pale panel colour: surfaces step down, with dark text. */
    private static Theme light(int base, int accent, int accentAlt) {
        return new Theme(
                0x60101018,
                base,
                ColorUtil.lerp(ColorUtil.shade(base, -0.06), accent, 0.10),
                ColorUtil.lerp(ColorUtil.shade(base, -0.03), accent, 0.05),
                ColorUtil.shade(base, -0.02),
                ColorUtil.lerp(ColorUtil.shade(base, -0.07), accent, 0.05),
                ColorUtil.lerp(base, accent, 0.18),
                ColorUtil.shade(base, -0.05),
                ColorUtil.lerp(ColorUtil.shade(base, -0.13), accent, 0.05),
                ColorUtil.lerp(ColorUtil.shade(base, -0.20), accent, 0.08),
                ColorUtil.lerp(0xFF16161C, accent, 0.06),
                ColorUtil.lerp(0xFF5A5A68, accent, 0.10),
                ColorUtil.lerp(0xFF9C9CA8, accent, 0.08),
                accent, accentAlt,
                0x40000000);
    }
}
