package dev.telemetric.hud;

import java.util.Locale;

/** Number formatting shared by the HUD and the 3D labels. */
public final class Numbers {

    private Numbers() {}

    /**
     * {@code v} with a k / M / B suffix: "12.3k", "1.25M", "2.50B". Meant for values from 10,000 up (smaller ones
     * still get "k"). {@code trimZeros} drops trailing zeros ("1.20M" → "1.2M", "2.00B" → "2B").
     */
    public static String suffixed(double v, boolean trimZeros) {
        String s, unit;
        if (v >= 1e9) {
            s = String.format(Locale.ROOT, "%.2f", v / 1e9);
            unit = "B";
        } else if (v >= 1e6) {
            s = String.format(Locale.ROOT, "%.2f", v / 1e6);
            unit = "M";
        } else {
            s = String.format(Locale.ROOT, "%.1f", v / 1e3);
            unit = "k";
        }
        if (trimZeros && s.contains(".")) s = s.replaceAll("\\.?0+$", "");
        return s + unit;
    }
}
