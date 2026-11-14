package dev.telemetric.ui.theme;

import dev.telemetric.setting.impl.EnumSetting;

/** Built-in purple/black palettes. Add a constant to add a preset — it appears in the Theme dropdown. */
public enum ThemePreset implements EnumSetting.Nameable {

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
            0x80000000));

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
}
