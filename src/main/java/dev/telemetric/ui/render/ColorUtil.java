package dev.telemetric.ui.render;

/** ARGB colour math. Pure Java, no MC dependency. */
public final class ColorUtil {

    private ColorUtil() {}

    public static int alpha(int argb) { return argb >>> 24; }
    public static int red(int argb) { return (argb >> 16) & 0xFF; }
    public static int green(int argb) { return (argb >> 8) & 0xFF; }
    public static int blue(int argb) { return argb & 0xFF; }

    public static int argb(int a, int r, int g, int b) {
        return (clamp255(a) << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    /** Replaces alpha (0..255). */
    public static int withAlpha(int argb, int alpha) {
        return (clamp255(alpha) << 24) | (argb & 0xFFFFFF);
    }

    /** Multiplies the existing alpha by {@code factor} (0..1). */
    public static int fade(int argb, double factor) {
        return withAlpha(argb, (int) Math.round(alpha(argb) * Math.clamp(factor, 0, 1)));
    }

    /** Linear interpolation of all four channels. */
    public static int lerp(int from, int to, double t) {
        if (t <= 0) return from;
        if (t >= 1) return to;
        return argb(
                (int) Math.round(alpha(from) + (alpha(to) - alpha(from)) * t),
                (int) Math.round(red(from) + (red(to) - red(from)) * t),
                (int) Math.round(green(from) + (green(to) - green(from)) * t),
                (int) Math.round(blue(from) + (blue(to) - blue(from)) * t));
    }

    /** Lightens (amount &gt; 0) or darkens (amount &lt; 0) towards white or black, keeping the alpha. */
    public static int shade(int argb, double amount) {
        int target = amount >= 0 ? 0xFFFFFFFF : 0xFF000000;
        return withAlpha(lerp(argb | 0xFF000000, target, Math.abs(amount)), alpha(argb));
    }

    /** @param h hue 0..1, s 0..1, v 0..1 */
    public static int hsv(float h, float s, float v, int alpha) {
        float hh = (h - (float) Math.floor(h)) * 6f;
        int sector = (int) hh;
        float f = hh - sector;
        float p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (sector) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return argb(alpha, Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }

    /** @return {h, s, v} each 0..1 */
    public static float[] toHsv(int argb) {
        float r = red(argb) / 255f, g = green(argb) / 255f, b = blue(argb) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h = 0;
        if (delta > 0) {
            if (max == r) h = ((g - b) / delta) % 6f;
            else if (max == g) h = (b - r) / delta + 2f;
            else h = (r - g) / delta + 4f;
            h /= 6f;
            if (h < 0) h += 1f;
        }
        float s = max == 0 ? 0 : delta / max;
        return new float[]{h, s, max};
    }

    /** Perceived brightness 0..1 (Rec. 601 weights), e.g. to pick dark or light text on a coloured fill. */
    public static double luminance(int argb) {
        return (red(argb) * 0.299 + green(argb) * 0.587 + blue(argb) * 0.114) / 255.0;
    }

    /** A copy with the hue turned by {@code turn} (0..1 is one full turn), keeping saturation, value and alpha. */
    public static int hueShift(int argb, float turn) {
        float[] hsv = toHsv(argb);
        return hsv(hsv[0] + turn, hsv[1], hsv[2], alpha(argb));
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
