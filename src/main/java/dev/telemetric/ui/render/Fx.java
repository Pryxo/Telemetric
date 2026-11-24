package dev.telemetric.ui.render;

import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.theme.Theme;
import dev.telemetric.ui.theme.ThemeManager;

/**
 * Shared accent effects of the ClickGUI: the flowing accent gradient (Theme → Flowing Gradient), the accent glow
 * (Theme → Glow) and text colours that stay readable on accent fills. All colours come from the palette.
 */
public final class Fx {

    /** GUI units for one accent → accent 2 → accent cycle of the flowing gradient. */
    public static final int FLOW_SPAN = 160;
    /** How far vertical position shifts the flow, so stacked rows don't all show the same colour. */
    private static final double FLOW_TILT = 0.6;
    /** Sample spacing of flowing fills (one gradient quad per step). */
    private static final int FLOW_STEP = 6;
    /** Width of the accent glow in GUI units. */
    public static final int GLOW_SIZE = 4;

    private Fx() {}

    /** Progress 0..1 of the flow loop ({@link Durations#FLOW_LOOP}); stays 0 while animations are off. */
    public static double flowPhase() {
        int period = Durations.loop(Durations.FLOW_LOOP);
        if (period <= 0) return 0;
        return (System.currentTimeMillis() % period) / (double) period;
    }

    /** The accent at screen position (x, y): flowing between accent and accent 2, or the plain accent with Flowing Gradient off. */
    public static int flow(ThemeManager themes, Theme p, int x, int y) {
        if (!themes.flowing()) return p.accent();
        return wave(p, (x + y * FLOW_TILT) / FLOW_SPAN - flowPhase());
    }

    private static int wave(Theme p, double pos) {
        double t = 0.5 - 0.5 * Math.cos(2 * Math.PI * pos);
        return ColorUtil.lerp(p.accent(), p.accentAlt(), t);
    }

    /**
     * A horizontal accent → accent 2 fill (header lines, accent headers, slider fills), flowing with Flowing Gradient
     * on, else a plain left-to-right gradient. {@code alpha} (0..1) fades it.
     */
    public static void accentFill(Render2D r, ThemeManager themes, Theme p, int x, int y, int w, int h, int radius,
                                  int corners, double alpha) {
        if (w <= 0 || h <= 0) return;
        if (themes.flowing()) {
            double phase = flowPhase();
            double tilt = y * FLOW_TILT;
            r.fillH(x, y, w, h, radius, corners,
                    px -> ColorUtil.fade(wave(p, (px + tilt) / FLOW_SPAN - phase), alpha), FLOW_STEP);
        } else {
            int left = ColorUtil.fade(p.accent(), alpha), right = ColorUtil.fade(p.accentAlt(), alpha);
            r.fillH(x, y, w, h, radius, corners, px -> ColorUtil.lerp(left, right, (px - x) / (double) w), w);
        }
    }

    /** A soft glow of {@code color} around a rect, scaled by Theme → Glow and {@code strength} (0..1). */
    public static void glow(Render2D r, ThemeManager themes, int x, int y, int w, int h, int radius, int color, double strength) {
        double g = themes.glow() * strength;
        if (g <= 0.01) return;
        r.glow(x, y, w, h, radius, ColorUtil.withAlpha(color, (int) Math.round(150 * Math.min(1, g))), GLOW_SIZE);
    }

    /** Whichever of the palette's text and panel colours reads better on {@code fill} (for text on accent fills). */
    public static int onFill(Theme p, int fill) {
        double lf = ColorUtil.luminance(fill);
        double lt = ColorUtil.luminance(p.text()), lp = ColorUtil.luminance(p.panel());
        int best = Math.abs(lt - lf) >= Math.abs(lp - lf) ? p.text() : p.panel();
        return ColorUtil.withAlpha(best, 255);
    }
}
