package dev.telemetric.ui.profile;

/**
 * Fixed Telos colours for the profile screen, deliberately not from the theme (like {@code WarningColors}): Glory,
 * Seasonal and Ironman read the same as on the server whatever theme you use. Everything else comes from the palette.
 */
final class ProfileColors {

    private ProfileColors() {}

    /** {@code /balance}: Glory is a white ❖, Seasonal Glory a gold one, both amounts aqua. */
    static final int GLORY = 0xFFFFFFFF;
    static final int SEASONAL = 0xFFFFAA00;
    static final int GLORY_VALUE = 0xFF55FFFF;
    static final int IRONMAN = 0xFFE0782E;
    static final int GROUP_IRONMAN = 0xFFE0A63A;
    static final int ONLINE = 0xFF55E07A;
}
