package dev.telemetric.ui.render;

import dev.telemetric.ui.animation.Animation;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.animation.Easing;

/** A click ripple: a circle that spreads from the click point over a row and fades out (Interface → Click Ripple). */
public final class Ripple {

    private static final double STRENGTH = 0.32;

    private final Animation spread = new Animation(Durations.RIPPLE, Easing.OUT_CUBIC, 1);
    private int cx, cy;

    /** Starts a ripple at (x, y). With animations off nothing is drawn. */
    public void start(double x, double y) {
        cx = (int) Math.round(x);
        cy = (int) Math.round(y);
        spread.snapTo(0).animateTo(1);
    }

    /** Draws the ripple clipped to the row (x, y, w, h). */
    public void render(Render2D r, int x, int y, int w, int h, int color) {
        if (!spread.isRunning() || w <= 0 || h <= 0) return;
        double t = spread.get();
        double reach = Math.hypot(Math.max(cx - x, x + w - cx), Math.max(cy - y, y + h - cy));
        r.pushClip(x, y, w, h);
        r.circle(cx, cy, Math.max(1, reach * t), ColorUtil.fade(color, (1 - t) * STRENGTH), y, y + h);
        r.popClip();
    }
}
