package dev.telemetric.game;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * MC ADAPTER helper for Custom Bags: paints the Telos "shiny" look onto a custom bag picture.
 * <p>
 * Modelled on the server pack's shiny pouch ({@code telos:mob/pouch/shiny}): its textures are 9-frame animations at
 * 2 ticks per frame (0.9 s loop) whose hue runs backwards round the colour wheel — pink-red → purple → blue → cyan
 * → green → yellow → orange — with a pastel outline ({@code shiny_outline}) of the same hue and white glints
 * ({@code shiny_effect}). Here that becomes: a luminance-keeping rainbow tint, a pastel outline along the picture's
 * transparent edges (or its border for photos), and a white diagonal glint that sweeps across.
 * <p>
 * Runs on the render thread only. The output buffer is reused between calls; {@link #release} frees it.
 */
final class ShinyEffect {

    private static final float HUE_START = 345f, PERIOD_MS = 900f, GLINT_MS = 1800f;

    private static NativeImage buf;

    private ShinyEffect() {}

    /**
     * @param src      picture (ARGB), not changed
     * @param ms       time since the pop started
     * @param strength 0..1, how strong tint and glint are
     * @return the shiny version (owned here, valid until the next call)
     */
    static NativeImage apply(NativeImage src, long ms, float strength) {
        int w = src.getWidth(), h = src.getHeight();
        if (buf == null || buf.getWidth() != w || buf.getHeight() != h) {
            release();
            buf = new NativeImage(w, h, false);
        }
        int[] px = src.getPixels();
        float hue = ((HUE_START - 360f * (ms % (long) PERIOD_MS) / PERIOD_MS) % 360f + 360f) % 360f;
        int tint = hsv(hue, 0.8f, 1f);
        float tr = (tint >> 16 & 0xFF) / 255f, tg = (tint >> 8 & 0xFF) / 255f, tb = (tint & 0xFF) / 255f;
        // pastel outline = tint mixed 60 % toward white
        int oR = (int) (255 * (tr + (1 - tr) * 0.6f)), oG = (int) (255 * (tg + (1 - tg) * 0.6f)),
                oB = (int) (255 * (tb + (1 - tb) * 0.6f));
        int edge = Math.max(1, Math.min(w, h) / 48);
        float glintAt = (ms % (long) GLINT_MS) / GLINT_MS * 1.6f - 0.3f, glintWidth = 0.07f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x], a = p >>> 24;
                if (a == 0) {
                    buf.setPixel(x, y, 0);
                    continue;
                }
                if (isEdge(px, w, h, x, y, edge)) {
                    buf.setPixel(x, y, a << 24 | oR << 16 | oG << 8 | oB);
                    continue;
                }
                float r = (p >> 16 & 0xFF) / 255f, g = (p >> 8 & 0xFF) / 255f, b = (p & 0xFF) / 255f;
                float lum = 0.299f * r + 0.587f * g + 0.114f * b, k = 0.35f + 0.9f * lum;
                r += (Math.min(1f, tr * k) - r) * strength;
                g += (Math.min(1f, tg * k) - g) * strength;
                b += (Math.min(1f, tb * k) - b) * strength;
                float d = (x + y) / (float) (w + h - 2 > 0 ? w + h - 2 : 1);
                float glint = Math.max(0f, 1f - Math.abs(d - glintAt) / glintWidth) * 0.65f * strength;
                r += (1 - r) * glint;
                g += (1 - g) * glint;
                b += (1 - b) * glint;
                buf.setPixel(x, y, a << 24 | (int) (r * 255) << 16 | (int) (g * 255) << 8 | (int) (b * 255));
            }
        }
        return buf;
    }

    /** Opaque pixel next to transparency (or the image border) within {@code k} pixels. */
    private static boolean isEdge(int[] px, int w, int h, int x, int y, int k) {
        if (x < k || y < k || x >= w - k || y >= h - k) return true;
        return px[y * w + x - k] >>> 24 < 128 || px[y * w + x + k] >>> 24 < 128
                || px[(y - k) * w + x] >>> 24 < 128 || px[(y + k) * w + x] >>> 24 < 128;
    }

    private static int hsv(float hue, float s, float v) {
        float c = v * s, hp = hue / 60f, xx = c * (1 - Math.abs(hp % 2 - 1)), m = v - c, r, g, b;
        if (hp < 1) { r = c; g = xx; b = 0; }
        else if (hp < 2) { r = xx; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = xx; }
        else if (hp < 4) { r = 0; g = xx; b = c; }
        else if (hp < 5) { r = xx; g = 0; b = c; }
        else { r = c; g = 0; b = xx; }
        return (int) ((r + m) * 255) << 16 | (int) ((g + m) * 255) << 8 | (int) ((b + m) * 255);
    }

    static void release() {
        if (buf != null) {
            buf.close();
            buf = null;
        }
    }
}
